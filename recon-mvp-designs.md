# Recon — Design Spec

_Pure capture tool. Photos go into a queue, the user commits a bundle, files land in folders. That's it._

This document is the **canonical product and interaction spec** for Recon. It is platform-neutral: every claim is grounded in the shipped Android reference implementation, but the architecture, resilience model, and interaction vocabulary described here are the target for any port (iOS first).

For the Android-specific technical reference (file layout, module responsibilities, dependency versions), see [`README.md`](./README.md). For release/signing runbook, see [`RELEASE.md`](./RELEASE.md). For post-MVP ideas (OCR / LAN peer-to-peer transfer), see [`BACKLOG.md`](./BACKLOG.md).

---

## Purpose

Recon captures **bundles** of photos. A bundle is an ordered sequence the user decides is related — pages of a document, facets of an object, scenes in a sequence — committed as one unit. A commit produces up to two parallel outputs in the user's chosen folder:

- **Raw photos** in `bundles/{bundle-id}/` — one JPEG per shot, numbered to preserve order.
- **A vertically-stitched JPEG** in `stitched/` — all photos in the bundle composed top-to-bottom into one image.

Both outputs share a common bundle ID, so a downstream pipeline can reconstruct bundle membership from either format.

The UX target is **machinegun cadence**: shoot-shoot-shoot-swipe-shoot-shoot-swipe, with no perceptible delay between any tap and the next. All heavy work (EXIF stamping, SAF I/O, stitching) happens off the interaction path.

## Scope

**In scope:**
- One-time output-folder setup on first launch.
- A single capture screen with a photo queue.
- A single swipe-commit gesture whose outputs are controlled by settings (raw folder AND/OR stitched image; at least one on; both on is the default).
- Auto-captured EXIF: timestamp, GPS, orientation, bundle-ID marker.
- An in-app **Bundle Preview** list for reviewing and deleting saved bundles (per-bundle swipe-to-delete with configurable undo window).
- First-run gesture tutorial, re-triggerable from Settings.
- Minimal settings: output folder, output toggles, stitch quality, shutter sound, delete-confirmation + undo window.

**Out of scope:**
- Capture modes (document / object / scene), post-processing, auto-crop.
- User-entered metadata (tags, notes, prefix chips).
- A full in-app photo viewer — Bundle Preview lists bundles; opening individual files deep-links to the system file browser.
- Export / share flows — output is files on disk; the user takes it from there.
- Pipelines, cloud, OCR, automation hooks. (See [`BACKLOG.md`](./BACKLOG.md) for post-MVP directions.)

---

## The interaction vocabulary

The entire app, once onboarded, is six gestures:

| Gesture | Start region | Motion | Effect |
|---|---|---|---|
| **Capture** | Shutter button | Tap | Photo appended to queue's right end |
| **Commit** | Left handle of queue strip | Drag right past threshold | Queue → bundle(s) written to disk |
| **Discard** | Right handle of queue strip | Drag left past threshold | Queue cleared (with 3-second undo) |
| **Delete one** | A thumbnail in the queue | Swipe down past threshold | Just that photo removed |
| **Reorder** | A thumbnail in the queue | Long-press then drag horizontally | That thumbnail moved within the queue |
| **Divide / Un-divide** | The gap between two adjacent thumbnails | Swipe down to insert a divider; swipe up to remove | Queue partitions into sub-bundles; one swipe-commit produces N parallel bundles |

Because every gesture has a **unique starting region**, disambiguation is deterministic. A touch on a thumbnail never triggers commit or discard; a touch on a handle never triggers reorder. There is no velocity arbitration between competing gestures and no slop tuning.

The six gestures compose: a user can shoot, reorder, insert dividers, delete a bad shot, and commit — all in one queue session — and the pipeline treats it as a single atomic operation at the end.

---

## Capture screen anatomy

The capture screen is locked to portrait orientation (see [Design decisions § Portrait lock](#design-decisions)). Every icon counter-rotates to match the device's physical orientation, so labels and glyphs always read right-side-up.

```
┌─────────────────────────────────────┐
│  ⚙       EXT ▸ ZSL           🖼  │  ← top bar: settings · camera-mode toggle · bundle library
│                                     │
│   ┌─────────────────────────────┐   │
│   │                             │   │
│   │       live preview          │   │  ← 3:4 viewfinder
│   │  (tap-to-focus, pinch-zoom) │   │
│   │                             │   │
│   └─────────────────────────────┘   │
│         0.5×   1×   2×   5×         │  ← zoom chips (hardware-filtered)
│                                     │
│       ⚡auto    ⬤    ⇆             │  ← flash cycle · shutter · lens flip
│                                     │
│   ║  ┌──┬──┬──┬──┬──┐  ║            │  ← queue strip: left handle · tray · right handle
│   ║  │p1│p2│p3│p4│p5│  ║            │    (72dp tall, 56dp thumbs, 6dp gap)
│   ║  └──┴──┴──┴──┴──┘  ║            │
└─────────────────────────────────────┘
```

### Top bar (inside status-bar padding)

- **Settings** (left) — gear icon, opens the Settings screen.
- **Camera-mode toggle** (center) — `EXT / ZSL` segmented pill.
    - `ZSL`: zero-shutter-lag capture via CameraX's `CAPTURE_MODE_ZERO_SHUTTER_LAG`. Uses a frame ring buffer; shortest latency; preferred default.
    - `EXT`: CameraX Extensions in `AUTO` mode (device-chosen HDR / night / beauty) with `CAPTURE_MODE_MINIMIZE_LATENCY`. Falls back to the base selector if extensions aren't available on the device.
    - Mode switch triggers a camera rebind; shutter briefly disabled during rebind.
- **Bundle library** (right) — photo-library icon, opens the Bundle Preview screen. Disabled until the user has picked an output folder.

### Viewfinder

- 3:4 aspect ratio, fills the available width under the top bar.
- **Tap-to-focus**: tapping anywhere on the preview fires an `AF + AE` metering action (3-second duration) and shows an animated circle indicator at the tap point (scale 1.5× → 1×, 500ms lifetime).
- **Pinch-to-zoom**: a two-finger pinch multiplies the current zoom ratio; the visible ratio updates the zoom chip selection live.

### Zoom chips

Row of pill-shaped buttons for presets `0.5× / 1× / 2× / 5×`. The row filters to only the presets that fall within the device's reported min/max zoom range (a phone without an ultrawide won't show `0.5×`). The currently-selected ratio renders with a trailing `×` suffix. Width transitions animate over 180ms linear when the set of available presets changes.

### Bottom controls row

Three circular buttons laid out as **flash · shutter · lens flip**:

- **Flash** (left) — cycles `Off → Auto → On`. Icon changes per mode (`⚡off / ⚡auto / ⚡on`). `Off` renders at 65% opacity; `Auto`/`On` at 100%. Mode is applied live to the `ImageCapture` use case without rebinding, and re-applied after any rebind so it survives lens flips and mode switches.
- **Shutter** (center) — 80dp circle, white border (3dp) with white fill. Disabled during the ≤100ms capture window and during camera rebinds (dims to 50% when disabled).
- **Lens flip** (right) — `↔` icon; toggles between back and front camera. Disabled mid-capture or mid-rebind. Triggers a rebind on the same executor/mutex that the capture path holds, so a tap during capture is rejected cleanly rather than racing.

All three icons counter-rotate with device orientation via a spring animation on the shortest rotational arc.

### Queue strip (72dp tall, pinned above navigation-bar insets)

Three regions:

- **Left handle** (`EdgeZoneMinWidth = 60dp`, grows to fill empty tray on short queues). Neutral vertical bar at rest; transforms into a green `✓` destination panel mid-commit-gesture.
- **Tray** (middle, horizontally scrollable). Rounded 56dp thumbnails with 6dp gap, 8dp outer padding. Between adjacent thumbnails is an invisible 24dp divider hit zone (see [Divide / Un-divide](#gesture-mechanics-in-depth)).
- **Right handle** (symmetric, also 60dp-and-growing). Transforms into an amber `✕` destination panel mid-discard-gesture.

Handles are dimmed when the queue is empty and become subtly vibrant when the queue has at least one photo. `systemGestureExclusion()` is applied flush to the screen edges on both sides so Android's system-gesture navigation doesn't eat the edge-swipe.

### Overlays (peer of the queue strip, layered inside the same `Box`)

These occupy the 72dp queue-strip slot and never push other UI down:

- **UndoToast** — "Discarded N photo(s) · Undo" during the 3-second discard-undo window.
- **BundleSavedShimmer** — green pill that fades in (150ms), holds (900ms), fades out (150ms). Text: `Bundle {id} saved` for single-bundle commits, `N bundles saved ({first}–{last})` when dividers split the queue into multiple bundles.
- **ActionBanner** — dismissible red pill for recoverable errors (e.g. "Capture failed", "No output folder set"). Text capped at two lines with ellipsis so it fits within 72dp.
- **DeleteGlow** — 18dp red gradient at the bottom edge of the queue strip, intensifying with delete-progress as the user drags a thumbnail downward.

### GestureTutorial (first-run scrim, drawn above everything)

A full-screen black-at-82%-opacity scrim with 5 steps (commit, discard, delete-one, reorder, divide). The demo strip inside the overlay uses the real 72dp queue height and is pinned to the bottom with a 12dp nav-bar gap, so muscle memory transfers when the overlay dismisses. The scrim consumes every pointer event (the `awaitEachGesture` loop eats every unconsumed change) — do not poke holes, or users can accidentally swipe the real (empty) queue beneath. Shown once when `seenGestureTutorial == false`; re-triggerable from Settings.

---

## Gesture mechanics in depth

### Capture

- **Start region**: the shutter button.
- **Motion**: single tap. Gesture completes instantly; no threshold.
- **Feedback**: short haptic tick (`CONTEXT_CLICK`) + optional shutter sound (user-toggleable, default on). New thumbnail appears at the right end of the queue; tray scrolls to make it visible.
- **Timing**: shutter re-enables within ~50–200ms depending on device. The underlying write is async (see [Phase 1](#phase-1--capture-100ms-ui-thread-budget)), so the button is usable before the file is fully staged.

### Commit

- **Start region**: left handle of the queue strip.
- **Motion**: horizontal drag rightward.
- **Threshold (hybrid)**: commit fires when `|dragX| ≥ maxWidth / 2` **or** `|velocity| ≥ 80dp/s` in the commit direction. A flick short of halfway still commits if fast enough; a slow deliberate drag past halfway also commits. Velocity tracking uses the platform's standard `VelocityTracker`.
- **Feedback during drag**:
    - Green gradient (the "tide") fills under the queue left-to-right, progress tracking the finger.
    - Thumbnails tilt up to +3° to the right (spring damping).
    - The right edge transforms into a green `✓` destination panel, intensity rising with progress.
    - Handle bars scale up from 4dp/44dp to 10dp/56dp (width/height) to show the handle is "pressed".
    - On crossing 50% travel, a medium haptic tick (`CLOCK_TICK`) fires as a threshold marker.
- **Completion**: green flash (220ms). Thumbnails fly off the right edge (350ms exit). Strong commit haptic (`CONFIRM` on API 30+, else `CONTEXT_CLICK`). `BundleSavedShimmer` plays.
- **Abort**: dragging back below threshold with no velocity reverts the tide + tilt + destination panel with spring animation — no commit fires.

### Discard

Mirror of commit:
- **Start region**: right handle.
- **Motion**: horizontal drag leftward.
- **Same threshold model** (50% distance OR 80dp/s velocity).
- **Feedback**: amber tide right-to-left, thumbnails tilt left, left edge shows an amber `✕` destination panel.
- **Completion**: amber flash (220ms), left-edge exit animation, medium haptic (`LONG_PRESS`). An undo toast appears for 3 seconds.
- **Why amber, not red**: discard is **undoable** within the 3-second window. Red reads as "final / dangerous" and would make users hesitate on a safe gesture. Amber reads as "warning, but recoverable."

### Delete one

- **Start region**: a specific thumbnail.
- **Motion**: vertical drag downward.
- **Feedback**: the thumbnail slides downward, following the finger; a red DeleteGlow intensifies at the bottom edge of the queue strip; haptic tick at threshold.
- **Completion**: thumbnail slides fully down and fades (~200ms). Neighbours close the gap with a spring. Dividers are remapped: if the deleted index split a partition, the divider index after it shifts down by 1; dividers that would straddle fewer than 2 items collapse.
- **Abort**: releasing below threshold springs the thumbnail back into place.

### Reorder

- **Start region**: a specific thumbnail.
- **Motion**: long-press (≥500ms) and then horizontal drag.
- **Feedback**: thumbnail lifts with a small shadow; neighbours shift out of the way with a 120ms ease; the dragged thumbnail follows the finger instantly.
- **Completion**: on release, the thumbnail settles into the nearest slot with a spring bounce. The release index becomes its new position; the `-p-{kk}` index in output filenames reflects this new order.
- **Divider interaction**: reorder does not touch dividers — they keep their absolute indices. Reordering across a divider does not remove it; the partition boundary stays where the user put it.

### Divide / Un-divide

- **Start region**: the 24dp gap between two adjacent thumbnails (the "divider hit zone"). This zone is drawn **above** thumbnails in Z-order and consumes its pointer in the initial pass so the overlapping thumbnails underneath don't steal the touch.
- **Motion**: swipe down past ~24dp to insert a divider at that gap; swipe up past the same threshold to remove it.
- **Feedback**: a vertical divider line appears/disappears with a fade + spread (neighbouring thumbs slide apart ~18dp total, maintaining the center of the gap so the line stays anchored).
- **Completion**: queue is now partitioned. On the next commit swipe, each partition becomes its own bundle, each with its own freshly-allocated bundle ID (see [Phase 2](#phase-2--commit-single-frame-ui-thread)).

### Queue-size-aware edge zones

The base edge-zone width is `EdgeZoneMinWidth = 60dp`. When the tray has empty slack (short queue, thumbs don't fill across), each zone grows by `slack / 2` so the user can begin a commit/discard swipe from further inward. The width is capped two ways:

- Per side, `maxWidth * 0.33` (the `EdgeZoneMaxFraction` — each zone is at most a third of the screen).
- Combined, `maxWidth / 2 - 24dp` (the `EdgeZoneMiddleGuard` — the two zones can never meet; there's always at least a 24dp neutral strip).

Width changes animate over 180ms linear so a mid-gesture queue-size change (e.g. a burst resolving while the user is dragging) doesn't yank the hit region from under the finger. The **destination fill** (the `✓` / `✕` glyph + commit flash) is decoupled from the input width — it always renders at 60dp anchored to the screen-edge side, so widening the input area doesn't bloat the visual.

### Edge zones overlap the tray (not vice versa)

Edge zones are stacked on top of the tray inside an outer `Box`. The tray is padded 48dp on each side so thumbnails render in the middle slab. Horizontal-drag detectors inside each zone only claim pointers after crossing the platform's horizontal touch slop, so taps and vertical drags on thumbnails propagate through even when the zone widens into thumbnail territory. Long-press-then-drag reorder still fires from the thumbnail's own pointer handler because long-press doesn't move.

---

## Naming & folder layout

### Bundle ID

`{date}-s-{counter}` where:
- `date` = local calendar date at bundle-allocation time, `yyyy-mm-dd`. Sorts chronologically in any file listing, which matters for every downstream tool that reads the folders in default order.
- `counter` = zero-padded (4 digits) daily monotonic counter. Resets at local midnight.

Example: `2026-04-14-s-0003`.

The counter is incremented **atomically** from persistent storage at commit time, before any output I/O. If a worker fails partway through writing its bundle, the counter does not rewind — the next commit gets the next ID, and a retry of the failed bundle reuses its allocated ID.

### Filenames

- Raw photo: `{bundle-id}-p-{kk}.jpg` (2-digit photo index, 01-based).
    - e.g. `2026-04-14-s-0003-p-02.jpg` — the 2nd photo of the 3rd bundle on 14 April 2026.
- Stitched image: `{bundle-id}-stitch.jpg`.

### Folder structure on disk

```
{user-selected-root}/
├── bundles/
│   └── {bundle-id}/
│       ├── {bundle-id}-p-01.jpg
│       ├── {bundle-id}-p-02.jpg
│       └── ...
└── stitched/
    ├── {bundle-id-A}-stitch.jpg
    ├── {bundle-id-B}-stitch.jpg
    └── ...
```

The two folders share a common bundle ID, so raw photos and the stitched image for the same capture event are always findable and pairable.

`bundles/` and `stitched/` are created at folder-pick time (under the SAF tree on Android, under the security-scoped bookmark on iOS). If either is missing at commit time — user deleted it externally, cloud-sync hiccup — the worker recreates it, tolerating race conditions from concurrent creators.

### Internal staging (NOT under the user folder)

Recon does **not** stage raw captures inside the user's output folder. Staging lives entirely in the app's **internal storage**:

- Android: `filesDir/staging/session-{uuid}/{photo-uuid}.jpg`.
- iOS: `FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]/staging/session-{uuid}/{photo-uuid}.jpg`.

There are two reasons for this:

1. **Latency.** Writing every capture into a SAF/bookmark tree would mean an IPC round-trip per photo (Android) or security-scoped-resource dance per photo (iOS). Internal storage is a direct `FileOutputStream` — microseconds.
2. **User folder hygiene.** The user picks a folder to receive committed bundles, not a folder to receive in-flight raw captures. Staging the in-progress queue there would mean the user sees a moving "session-uuid" directory full of raw files until every bundle commits. Unacceptable.

Committed bundles are **copied** from staging to the user folder by the background worker (see [Phase 3](#phase-3--worker-serial-off-thread-seconds-to-tens-of-seconds)). Rename/move tricks do not apply because internal storage and the user folder are on different filesystems (SAF is a content provider, not a filesystem; a bookmark points into a different sandbox). Copy is the only correct operation.

---

## Auto-captured metadata

Every photo's EXIF is stamped at capture time (Phase 1) and then finalised at commit time (Phase 3). Users never enter any of this.

| Field | When | Source | Notes |
|---|---|---|---|
| `DateTimeOriginal` + `DateTime` | Phase 1 | System clock, device local timezone | Per-photo timestamp, formatted `yyyy:MM:dd HH:mm:ss`. |
| `Make` / `Model` | Phase 1 | Device metadata | Standard. |
| `Orientation` | Phase 1 | Accelerometer-derived (see [Design decisions § Orientation](#design-decisions)) | EXIF orientation tag reflects physical device pose even with UI locked to portrait. |
| `GPSLatitude` / `GPSLongitude` etc. | Phase 1 (best-effort) + Phase 3 (backfill) | Fused location with 30s TTL cache | Stamped at Phase 1 if a cached fix is available; otherwise backfilled at Phase 3 after a bounded (2s) location refresh. First-capture without GPS is still delivered — just without coordinates. |
| `UserComment` | Phase 3 | Storage layout | Raw photos: `Recon:{bundle-id}:p{kk}`. Stitched: `Recon:{bundle-id}:stitch`. Preserves membership + sequence if files are moved or mixed. |

The `UserComment` is the product's worst-case-recovery mechanism: even if folder structure is lost, bundle membership and per-photo order can be reconstructed from EXIF alone.

---

## The three-phase async pipeline

Capture → commit → process. Each phase has a hard role; the boundaries between them are the app's load-bearing correctness guarantees.

### Phase 1 — Capture (≤100ms, UI-thread budget)

Per shutter tap:

1. Camera delivers a JPEG buffer.
2. Write the JPEG to internal staging (`staging/session-{uuid}/{photo-uuid}.jpg`).
3. Stamp capture-time EXIF on the file: DateTimeOriginal, orientation, make/model, and GPS if a cached fix (≤30s old) is available.
4. Decode a small thumbnail (~50KB in-memory bitmap + path reference) for the queue UI.
5. Append the staged photo to the queue state; shutter re-enables.

In-memory footprint per queued photo is **just the thumbnail**, not the full photo. A 50-photo queue costs ~2.5MB of thumbnails, not ~250MB of decoded JPEG. Raw JPEGs are **durable on disk** from the moment the shutter fires.

If the process dies here, staging persists — see [Resilience](#resilience).

### Phase 2 — Commit (single frame, UI thread)

The swipe-commit gesture must re-enable the shutter by the next frame. It does so by splitting Phase 2 into a **synchronous UI pivot** and a **deferred background step**:

**Synchronous (on the UI thread):**
- Snapshot the queue's ordered list of staging paths + the current set of dividers.
- Null the `currentSession` reference; clear `queue` and `dividers` in UI state.
- Shutter is now usable. This happens in one frame.

**Deferred (coroutine, off UI thread):**
- Partition the snapshot by dividers into one or more segments. Each segment becomes its own bundle.
- For each segment, atomically allocate the next bundle ID from persistent storage (the daily counter).
- Build a `PendingBundle` manifest per segment — JSON-serializable record of: bundle ID, root URI, stitch quality, session ID, ordered photo list (path + rotation), capture timestamp, `saveIndividualPhotos` flag, `saveStitchedImage` flag. Flags are **frozen at commit time** so a settings change mid-flight doesn't change what an already-queued worker produces.
- Write all manifests to internal storage (`pending/{bundle-id}.json`). Write them **all before enqueuing any worker** — a mid-loop crash leaves no enqueued workers on partial state.
- Enqueue one worker per manifest to the platform's background scheduler (WorkManager on Android, OperationQueue + BGProcessingTask on iOS). Only the `bundle-id` goes through the scheduler's input data; the worker loads the rest from the manifest file. This sidesteps Android's 10KB `Data` limit (a 50-photo bundle's paths exceed it) and makes orphan recovery trivial.
- Emit a `BundlesCommitted({bundleIds})` event → `BundleSavedShimmer` displays the saved pill.

If the process dies **between the UI pivot and the manifest write**, the staging session still exists on internal storage; `OrphanRecovery` at next launch restores it as an uncommitted queue.

### Phase 3 — Worker (serial, off-thread, seconds to tens of seconds)

One worker per bundle manifest, wrapped in a **foreground service** (Android) / `beginBackgroundTask` (iOS) so the OS grants time to finish. A **process-wide mutex** serialises all bundle workers — stitching a tall image allocates ~60% of heap, and two in parallel reliably OOMs mid-range devices.

Per worker:

1. Load `pending/{bundle-id}.json`.
2. If `saveIndividualPhotos`:
    - Refresh location (bounded 2s). Backfill GPS EXIF on any staged photo missing it. Stamp `UserComment = "Recon:{bundle-id}:p{kk}"`. Both in one open/save pass per file (avoids paying the ExifInterface open-cost twice).
    - Copy each staged JPEG to `bundles/{bundle-id}/{bundle-id}-p-{kk}.jpg` via SAF/bookmark. Overwrite on name collision so retries don't produce `" (1).jpg"` duplicates.
3. If `saveStitchedImage`:
    - Compute stitch layout (see [Stitcher](#stitcher)) from the raw source photos.
    - Render to a canvas, compress JPEG at the quality tier, stamp `UserComment = "Recon:{bundle-id}:stitch"`.
    - Copy to `stitched/{bundle-id}-stitch.jpg` via SAF/bookmark.
4. Delete the manifest file.
5. If no other pending manifests reference the session, delete the staging session directory.

On failure (I/O error, OOM, crash): the worker returns failure; the manifest persists so the retry at next launch (or via exponential backoff) can resume cleanly.

### Stitcher

Layout math (pure function, testable without bitmap allocation):

1. **Quality ceiling** on width:
    - `LOW`: 1600 px
    - `STANDARD`: 1800 px
    - `HIGH`: unbounded
2. **Initial common width** = min(narrowest source width, quality ceiling).
3. **Scale down proportionally** if total composed height > 32,000 px (`HEIGHT_CAP_PX`): `scale = 32000 / totalHeight`, reduce `commonWidth` and each per-slot height by that factor.
4. **Scale down further** if planned ARGB bytes (`commonWidth * totalHeight * 4`) exceed `60%` of runtime heap (`HEAP_BUDGET_FRACTION`): `scale = sqrt(heapBudget / plannedBytes)`, reduce both dimensions uniformly (square root preserves aspect ratio).
5. Decode each source with a subsample factor (`inSampleSize`) just large enough to fit its slot; rotate per EXIF orientation; draw into a single canvas; compress JPEG at the quality's JPEG tier:
    - `LOW`: 70
    - `STANDARD`: 82
    - `HIGH`: 92

### Resilience

- **Photos survive process kill**: they're on internal storage the moment the shutter fires. On next launch, `OrphanRecovery` scans the staging dir, prunes any sessions marked discarded, re-enqueues manifests whose workers never ran, and restores the most recent live orphan session (the queue the user was building) back into the capture VM. Older live orphans are deleted to prevent unbounded disk growth.
- **Bundle-ID counter is atomic**: allocated from persistent storage before any output I/O. Worker failure doesn't rewind it. Retries reuse the same ID.
- **Worker failures are observable without noise**: the failure flow filters pre-existing historical failures on first subscription (the "acknowledged set"). Without this, every app launch would re-surface yesterday's transient failure.
- **Discard marker is synchronous**: when the user swipes-discard, a `.discarded` marker file is written to the staging session directory **synchronously** on the UI thread (one `File.createNewFile()`, ~1ms, Main-thread-safe) **before** the 3-second undo timer starts. If the process dies inside the undo window, the coroutine that would've cleaned up gets cancelled — but the marker is already on disk. `OrphanRecovery` sees the marker on next launch and deletes the session (instead of restoring it as a zombie queue). Undo removes the marker.
- **Backpressure is natural**: the worker queue drains at its own pace. If the user commits faster than stitching, bundles pile up and catch up during a pause. Capture latency never depends on worker depth.
- **Memory stays flat**: photos are on disk, not in RAM. The worker holds at most one bundle's decoded pixels at a time.

---

## Settings

All persisted in a key-value store (DataStore on Android, `UserDefaults` / property-list file on iOS). Defaults are chosen so a first-run user has a functional setup after only picking a folder.

| Setting | Default | Meaning |
|---|---|---|
| **Output folder** | _unset_ (blocks capture until chosen) | The user-picked SAF tree (Android) or security-scoped bookmark (iOS). Shows the path; "Change" button re-triggers the picker. |
| **Bundle output: Individual photos in subfolder** | On | Whether a commit writes raw JPEGs into `bundles/{bundle-id}/`. |
| **Bundle output: Vertical stitched image** | On | Whether a commit writes the composed JPEG into `stitched/`. |
| **Stitch Quality** | Standard | Low / Standard / High. Affects only the stitched image (width ceiling + JPEG quality per [Stitcher](#stitcher)). Disabled when the stitched toggle is off; preference is preserved. |
| **Shutter sound** | On | Whether to play the system shutter-click sound. |
| **Confirm before deleting a bundle** | On | If off, swiping a bundle row in Bundle Preview goes straight to pending-delete (or hard delete if undo window = 0s). |
| **Undo window for bundle deletion** | 5s | Off (0) / 1s / 2s / … / 10s. Controls the countdown shown on a pending-delete row. `Off` removes the pending state entirely — confirmed deletes go through immediately with no undo affordance. |
| **Gesture tutorial** | _(action)_ | "Show" button clears `seenGestureTutorial` and pops back to capture so the overlay re-appears. |
| **App version** | _info only_ | Read-only. |

**Output invariant**: at least one of Individual Photos / Stitched must be on. The UI locks whichever switch is the sole-on, showing an inline caption ("At least one output is required.") only while the lock is active. The settings store sanitises a `(false, false)` read (external edit, restore from backup) back to `(true, true)` at read time — a belt-and-suspenders guarantee that a corrupt pair can never silently drop bundles.

No accounts, no cloud, no permissions management beyond what the OS already provides.

---

## First-run onboarding

### Folder picker

1. App opens to a brief "Pick output folder" screen with the app name and a short explanation.
2. User taps "Choose folder" → OS folder picker appears.
    - Android: `ACTION_OPEN_DOCUMENT_TREE` with `takePersistableUriPermission` so the grant survives app kills and reboots.
    - iOS: `UIDocumentPickerViewController` in `.open` mode with `directoryURL` support; persist `bookmarkData(options: .withSecurityScope)`.
3. App persists the reference and creates `bundles/` and `stitched/` under the chosen root.
4. App drops into the capture screen.

### Gesture tutorial overlay

On first entry to the capture screen (`seenGestureTutorial == false`), a full-screen overlay scrims the capture UI and walks the user through **all five queue-strip gestures** (commit, discard, delete-one, reorder, divide) with a looping animated finger over a fake 4-thumbnail demo queue. Text sits mid-screen; pager dots + Next button below; demo strip pinned to the bottom 72dp (same location and size as the real queue strip) so muscle memory transfers on dismiss.

- "Skip" (top-right) or "Got it" (last step) both write `seenGestureTutorial = true` and dismiss.
- The scrim consumes every pointer event — users cannot accidentally fire real gestures on the (empty) queue beneath.
- Re-triggerable anytime from Settings → "Gesture tutorial → Show".

### Permissions

Requested at the moment they're needed, not up-front:

- **Camera**: requested when the capture screen first mounts (required; denial blocks capture).
- **Location**: requested after camera permission resolves, from a `LaunchedEffect(Unit)` on first capture-screen mount. Non-fatal: denial just means EXIF lacks GPS.
- **Notifications** (Android 13+): declared but not requested. Worker runs whether or not it's granted; only the shade notification is suppressed.

---

## Bundle Preview screen

Reached from the capture screen's top-right photo-library icon. Material 3 styling (Android) / system styling (iOS): title bar with back, title, and an "open in system file browser" action on the right; body is a vertical list of bundle rows.

### Row anatomy

All three row states share a common skeleton with `heightIn(min = 68.dp)` so neighbour rows don't shift when a row changes state.

- **Normal row** (shipped bundle):
    - Thumbnail strip (up to 3 thumbs, 128dp fixed width — strip width is constant regardless of how many thumbs exist, so rows stay column-aligned).
    - Monospace bundle ID + "N photos" subtitle.
    - Modality icons on the right: `PhotoLibrary` outline when the raw subfolder exists, `ViewStream` outline when the stitched image exists. Both when both outputs were kept.
    - Thumbnails are drawn from the raw subfolder photos when present (they crop cleanly). If only the stitched image exists for a bundle, the stitch is the single thumbnail.
- **Pending-delete row**:
    - Same thumbnail strip (preserving row width).
    - Title replaced by "Deleting in Xs" in the error color (countdown ticks via a derived-state observer that only recomposes on whole-second boundaries).
    - Modality icons replaced by an "Undo" text button.
- **Processing row** (ENQUEUED / RUNNING / BLOCKED worker):
    - `CircularProgressIndicator` in the leading thumbnail slot; the 3-thumb strip width is still reserved so the bundle ID stays column-aligned with completed rows.
    - Title is the bundle ID; subtitle is "Processing…".
    - **Not swipeable** — you can't delete a bundle that's still being written.

### Swipe-to-delete

Swipe left on a normal row past ~40% of row width (or with terminal velocity matching the capture screen's threshold) to arm the delete. Haptic tick on both threshold crossings so dragging back below the line reads as an armed cancel.

- If **confirm-before-delete** is on (default): swipe reveals an alert dialog summarising which modalities will be removed. Confirming puts the row in pending-delete state.
- If **confirm-before-delete** is off: swipe goes straight to pending-delete (or straight to hard delete if the undo window is 0s).

Pending-delete rows have per-row **independent countdowns**. A user can swipe multiple bundles in quick succession — each row starts its own timer and each has its own Undo button. Leaving the screen while deletes are pending does not cancel them; they continue to completion on an app-scoped (not screen-scoped) coroutine. This is a deliberate departure from the capture screen's single-slot discard-undo model: a user tidying up old bundles should be able to queue up many deletes without blocking.

### Processing-row bridge

When a user swipes-commits and immediately opens Bundle Preview, the worker is still running (seconds to tens of seconds) and the bundle has not yet landed in the user folder. The screen subscribes to the worker scheduler's active-bundle-IDs flow and renders a processing row at the top of the list for each in-flight worker. When the worker finishes, the screen refreshes the folder listing **first**, **then** drops the processing ID from state — so the completed row takes over with no blank frame between the processing row disappearing and the real row appearing. Failed workers simply disappear from the processing set; the failure banner is the capture screen's concern.

---

## Design decisions

Decisions with non-obvious rationale, captured so future ports can make equivalent trade-offs (or override them with the "why" in hand).

1. **Date format `yyyy-mm-dd`.** Sorts chronologically in any file listing, which every downstream tool benefits from. Every other format sorts either alphabetically-wrongly (`MM-DD-YYYY`) or ambiguously (`DD/MM/YYYY`).

2. **Silent auto-resume on app-kill / relaunch.** If `OrphanRecovery` finds an uncommitted staging session on launch, the queue is restored transparently — no prompt, no "Continue?" dialog. The user lands on the capture screen with the previous photos already queued and can shoot, swipe-commit, or swipe-discard exactly as if the app had never quit.

3. **Empty-queue swipes are disabled.** Both handles dim and are unresponsive when the queue is empty. A 1-photo queue commits normally (the stitch output is just the single photo copied; the raw folder contains that one file). Gesture vocabulary stays consistent regardless of queue size.

4. **Portrait lock + counter-rotating icons.** The capture screen is locked to portrait via the platform's orientation attribute (`android:screenOrientation="portrait"` / `UIInterfaceOrientationMask.portrait`). For icons to feel right-side-up when the device is held sideways, each icon applies a `rotate(-deviceOrientation)` transform driven by an accelerometer-derived `deviceOrientation` StateFlow. Rotation animation uses a cumulative-target spring that always picks the **shortest arc** (no 270° spins when going 0° → 90°). Why lock: the queue-strip gesture model, edge-zone widths, and shutter muscle-memory all depend on a fixed horizontal axis; allowing landscape would require a second full layout and a second gesture-arbitration story.

5. **Accelerometer-driven EXIF orientation.** Platform display-rotation APIs only report when the activity itself rotates. Because we're locked to portrait, the app would otherwise never notice that the user held the phone sideways. An accelerometer listener (Android: `OrientationEventListener`; iOS: `CoreMotion`) snaps the device pose to the nearest cardinal (0/90/180/270) and writes it into the capture pipeline's `targetRotation` equivalent, so EXIF orientation reflects physical device pose even though the UI doesn't move.

6. **Hybrid commit thresholds (distance OR velocity).** A flick should commit even if short, and a slow deliberate drag should commit once past halfway. The threshold is `|dragX| ≥ maxWidth/2` OR `|velocity| ≥ 80dp/s` in the commit direction (`VelocityTracker`). The `80dp/s` is tuned **below** platform gesture defaults (`125dp/s` on Android) so a natural swipe doesn't get cancelled.

7. **Queue-size-aware edge zones, fixed destination fill.** Edge-zone input widths grow on short queues to let the user begin a swipe from further inward (slack-based formula, capped at 33% of width per side, with a 24dp neutral middle guard so zones never meet). The visual destination fill (tick/cross + commit flash), however, stays pinned at 60dp anchored to the screen edge — the growing input width doesn't bloat the visual. These two concerns stay decoupled so UX doesn't couple to slack math.

8. **Discard is amber, not red.** Discard has an undo, so it's recoverable. Red reads as "final / dangerous" and would make users hesitant to use a gesture that's actually safe to try. Amber reads as "warning, but recoverable."

9. **Synchronous discard marker.** The 3-second undo window for discard is scoped to the VM's coroutine scope — process death inside the window cancels the cleanup coroutine and would otherwise cause the discarded queue to restore on next launch. To prevent this, the discard gesture writes a `.discarded` marker file synchronously on the UI thread (single `File.createNewFile()`, ~1ms on internal storage) before the timer starts. Orphan recovery sees the marker and cleans up. Undo removes the marker. This is the only place in the app where we intentionally do I/O on the UI thread — the trade-off is worth it.

10. **Manifest file indirection, not in-worker data dict.** Worker-input payloads have a size limit on every platform (Android WorkManager: 10KB). A 50-photo bundle's paths exceed it. Rather than compressing paths, we write a JSON manifest per bundle to internal storage and pass only the bundle ID into the worker. This also makes orphan recovery trivial: the manifest is already on disk; re-enqueue by bundle ID.

11. **Process-wide worker mutex.** Stitching a tall image allocates ~60% of heap. Running two workers in parallel reliably OOMs mid-range devices. A static mutex held across `doWork` / `main` serialises workers process-wide; the user sees no difference because the cadence is capture-and-commit, not commit-bundles-in-parallel.

12. **Worker failure flow filters pre-existing failures.** The platform's work-info subscription replays historical failures on every `observe()`. Without filtering, every app launch would re-surface yesterday's transient bundle failure. The scheduler snapshots the set of failures on first emission as "acknowledged" and only emits fresh failures thereafter.

13. **Flags on PendingBundle are frozen at commit time.** `saveIndividualPhotos` / `saveStitchedImage` / `stitchQuality` are captured into the manifest at commit, not read at worker time. A user who commits then changes settings mid-flight gets the settings that were in effect when they swiped, not the settings at the moment the worker happens to run. Predictable > convenient.

14. **Multi-slot pending-delete in Bundle Preview.** The Bundle Preview screen uses a `Map<bundle-id, Job>` for pending deletes — multiple bundles can be pending simultaneously, each with an independent countdown. This is a deliberate departure from the capture screen's single-slot discard-undo. Rationale: capture-time discard is a single binary event ("I'm done with this queue"), while cleanup-time bundle delete is a **batch operation** ("I'm cleaning up the last few shoots"). Forcing serial handling of the latter would make batch cleanup painfully slow.

15. **Processing rows bridge the worker gap.** Without processing rows, a user who commits then immediately opens Bundle Preview sees their bundle "missing" until the worker finishes. The subscription to the scheduler's active-bundle-IDs flow surfaces the in-flight state; the ordering (refresh SAF *before* dropping the processing ID) prevents a blank frame where neither the processing row nor the completed row exists.

16. **No maintenance UI for storage.** Modern devices and user-chosen folders (often cloud-synced) handle storage naturally. Adding "delete old bundles" or "usage totals" UI would grow scope without matching user need.

17. **No in-flight badge / counter on the capture screen.** No "2 bundles processing" chip. The capture screen stays silent unless there's an error. Failures surface as the non-blocking `ActionBanner`; successes surface as the transient `BundleSavedShimmer`.

---

## Implementation principles for any platform

Everything above is interaction. These are the platform-neutral **engineering principles** that any port (iOS next) should adopt. They are the lessons the Android implementation extracted the hard way.

1. **Stage on internal storage, not in memory, not in the user folder.** Memory footprint stays bounded at ~50KB/thumbnail regardless of queue depth. User folder stays clean of in-flight sessions. I/O at capture time stays within the app's sandbox (fastest possible).

2. **Three phases, hard boundaries.**
    - Phase 1 (Shutter, ≤100ms): staging write + capture-time EXIF + thumbnail.
    - Phase 2 (Commit, single frame): UI pivots synchronously; manifest write + worker enqueue runs in a background coroutine **after** the UI is already ready for the next shot.
    - Phase 3 (Worker, serial): manifest-driven; location refresh + GPS backfill + final EXIF + output-folder I/O + stitch + cleanup. **Serialised** process-wide by a mutex to bound heap for stitching.

3. **Manifest file, not in-worker input.** JSON-serialize the per-bundle spec to internal storage; pass only the bundle ID through the scheduler. Orphan recovery replays manifests on next launch.

4. **Bundle-ID counter is atomic.** Allocate from persistent storage **before** any output I/O. Worker failure must never rewind the counter.

5. **Orphan recovery at startup.** Prune terminal worker records; re-enqueue manifests without in-flight work; delete staging sessions marked discarded; restore the most-recent live orphan as an uncommitted queue; delete older orphan sessions to prevent unbounded disk growth.

6. **Worker failure flow filters pre-existing failures** on first subscription. Without this, every launch re-surfaces yesterday's transient failure.

7. **Discard marker is synchronous**, on the UI thread, before the 3-second undo timer. Nothing else in the app does UI-thread I/O; here, the cost of a single `File.createNewFile()` is worth the crash-consistency guarantee.

8. **Gesture zones are disjoint siblings, not nested.** The queue strip is three sibling children of a container (left handle · tray · right handle). Each owns its own gesture handler. Hit-testing routes pointers unambiguously — no arbitration, no slop tuning, no "which gesture wins" code.

9. **Portrait-lock + counter-rotating icons.** Fixed axis for the gesture model; accelerometer-driven icon rotation with shortest-arc animation.

10. **Accelerometer → capture-pipeline rotation.** Platform display-rotation APIs don't catch orientation changes while the UI is locked. Use an accelerometer listener to snap to cardinal angles and write the matching rotation into the capture API's rotation hint, so EXIF orientation is correct regardless of UI pose.

11. **At-least-one output invariant**, enforced both at UI lock-in and at settings-read time (belt and suspenders).

12. **Flags frozen at commit time.** The manifest captures a snapshot of user preferences; the worker doesn't re-read settings mid-flight.

13. **Single open/save pass for final EXIF.** Combine GPS backfill and `UserComment` stamping into one `ExifInterface` open per file. Two passes doubles the I/O cost.

14. **Multi-slot delete timers for bulk cleanup.** Bundle-list delete is batchy by nature; use a map of per-bundle jobs rather than a single-slot timer.

---

## iOS build notes

This section is the SOTA target for an iOS port, informed by the Android reference implementation. Assume minimum iOS 16; use Swift + SwiftUI + modern Swift Concurrency.

### Camera

- `AVFoundation` with a custom `AVCaptureSession` running on a dedicated serial queue.
- Preview via `AVCaptureVideoPreviewLayer` (wrapped in a `UIViewRepresentable` for SwiftUI embedding). Aspect ratio 3:4 by constraining the layer bounds; use `videoGravity = .resizeAspect`.
- Capture via `AVCapturePhotoOutput`. Equivalent of Android's two "camera modes":
    - **ZSL**: `AVCapturePhotoOutput.isResponsiveCaptureEnabled = true` + `isZeroShutterLagEnabled = true` (iOS 17+).
    - **Quality**: `AVCapturePhotoSettings.photoQualityPrioritization = .quality`. No direct "HDR/auto" equivalent to CameraX Extensions; rely on `AVCapturePhotoSettings.isAutoRedEyeReductionEnabled` / `isAutoStillImageStabilizationEnabled` per device capability.
- Lens flip: set `AVCaptureDevice(discovering: ...)` for back/front via `AVCaptureDevice.DiscoverySession`; reconfigure the session on a background queue, holding a session-lock equivalent of Android's `bindMutex`.
- Flash: `AVCapturePhotoSettings.flashMode = .off / .auto / .on`, applied per-capture. To mirror Android's "flash mode survives rebinds": store `currentFlashMode` as instance state and apply it at every `AVCapturePhotoSettings` instantiation.
- Zoom: `AVCaptureDevice.videoZoomFactor` with `ramp(toVideoZoomFactor:withRate:)` for smooth chip-to-chip transitions; pinch gesture multiplies `videoZoomFactor`. Hardware-filter chip presets against `device.activeFormat.videoMinZoomFactorForDepthDataDelivery` / `device.maxAvailableVideoZoomFactor`.
- Tap-to-focus: on preview tap, translate the tap into device coordinates via `previewLayer.captureDevicePointConverted(fromLayerPoint:)`; set `focusPointOfInterest` + `exposurePointOfInterest` + `focusMode = .autoFocus` + `exposureMode = .autoExpose`. Animate a 500ms ring at the tap point in SwiftUI.

### Orientation

- Lock to portrait: in `Info.plist` set `UIInterfaceOrientation` to `UIInterfaceOrientationPortrait` only; override `supportedInterfaceOrientations` at the view-controller level.
- Detect physical orientation via `CoreMotion`: an `CMMotionManager` polling `deviceMotion` at ~10Hz, reading gravity vector, snapping to the nearest cardinal (0/90/180/270).
- Write the orientation into `AVCapturePhotoOutput.connection(with: .video)?.videoRotationAngle` (iOS 17+) or the older `videoOrientation` API so the captured photo's EXIF orientation reflects physical pose.
- Counter-rotate icons in SwiftUI via `.rotationEffect(.degrees(-deviceOrientation))` + `.animation(.spring())`. Use a `DeviceOrientationStore` (observable) that emits shortest-arc cumulative angles so a 0° → 90° rotation doesn't spin through 270°.

### Output-folder persistence

- `UIDocumentPickerViewController` in `.open` mode with `.folder` type. Callback returns a security-scoped URL.
- Persist as `url.bookmarkData(options: .withSecurityScope)` to `UserDefaults` or a plist in application-support.
- **Every access** (not just at pick time) must be bracketed: `url.startAccessingSecurityScopedResource()` → use → `url.stopAccessingSecurityScopedResource()`. Failing to do this will cause the URL to become unreachable after app relaunch. Wrap in a helper `func withBookmarkedRoot<T>(_ block: (URL) throws -> T) rethrows -> T`.
- Create `bundles/` and `stitched/` under the root at first access.

### Staging (internal storage — NOT inside the bookmark)

- Root: `FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]/staging/`.
- Per-session dir: `staging/session-{UUID()}/`.
- Per-photo file: `session-{uuid}/{UUID()}.jpg`.
- Use `FileHandle` or `Data.write(to:)` for writes — direct filesystem, no bookmark dance.

### Manifest store

- JSON-encode the manifest struct (Codable `PendingBundle`) to `application-support/pending/{bundle-id}.json` via `JSONEncoder`.
- Atomicity: `data.write(to: url, options: [.atomic])` so a crash mid-write leaves a valid-or-absent file, never corrupt.

### Bundle-ID counter

- Back it with a single-file JSON or property list at `application-support/counter.plist`: `{lastDate: "yyyy-mm-dd", lastCounter: Int}`.
- Gate reads/writes behind an `actor BundleCounterStore` so concurrent commits serialise on the actor.
- Reset counter to 1 when `lastDate != today`.

### Worker

- One `Operation` per bundle on a dedicated `OperationQueue(name: "recon.bundle-worker", maxConcurrentOperationCount: 1)`. The `maxConcurrentOperationCount = 1` is the equivalent of Android's process-wide worker mutex.
- For each operation:
    1. Wrap in `UIApplication.shared.beginBackgroundTask(withName: "bundle-{id}")` so the OS grants ~30 seconds even if the user switches away.
    2. Run the work on a detached `Task` inside the operation.
    3. End the background task in `finally`.
- For longer backlogs (multiple bundles queued), register a `BGProcessingTaskRequest` (`BackgroundTasks` framework) that drains remaining manifests when the system is idle and charging. Use identifier `{ios-bundle-id}.drain` (reverse-DNS, declared in `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`); set `requiresNetworkConnectivity = false`, `requiresExternalPower = false` unless the queue is large (>5 bundles).
- The worker body is manifest-driven (exact mirror of Android): load manifest → (optionally) copy raw JPEGs to bookmarked `bundles/{id}/` + stamp final EXIF → (optionally) stitch + copy to bookmarked `stitched/` → delete manifest → reap empty staging session.

### Failure surfacing

- The worker publishes failures through a `Publisher<BundleFailure, Never>` (Combine) or `AsyncStream<BundleFailure>`. The capture screen subscribes.
- Mirror Android's "acknowledged-set" trick: snapshot the set of in-flight-to-failed transitions at subscription time and only emit deltas after.

### Stitcher

- `CoreImage` with a `CIFilter` chain or a hand-rolled `CGContext`:
    - Decode each source as `CGImageSource` → `CGImage`.
    - Apply orientation from EXIF via `CGImagePropertyOrientation`.
    - Scale each to common width (pure `computeLayout` function first, ported 1:1 from the Kotlin version — `LOW=1600`, `STANDARD=1800`, `HIGH=.max`, height cap `32_000`, heap fraction `0.6`).
    - Draw each into a single `CGContext` of size `commonWidth × totalHeight`.
    - Encode as JPEG via `CGImageDestination` at quality tier (`0.70 / 0.82 / 0.92` for LOW/STANDARD/HIGH).
- Write to a temp URL in `NSTemporaryDirectory()` first, then copy to the bookmarked `stitched/` via `FileManager.copyItem(at:to:)` with `startAccessingSecurityScopedResource` bracketing.

### EXIF

- At capture time: `CGImageDestinationAddImage` with a metadata dict: `kCGImagePropertyExifDateTimeOriginal`, `kCGImagePropertyTIFFOrientation`, `kCGImagePropertyTIFFMake`, `kCGImagePropertyTIFFModel`, `kCGImagePropertyGPSLatitude` / `...Longitude` if available.
- At worker time: open the file with `CGImageSourceCreateWithURL`, read existing metadata dict, mutate to add `kCGImagePropertyExifUserComment = "Recon:{bundleId}:p{kk}"` (or `:stitch`), backfill GPS if missing and a refreshed location is available, re-encode with `CGImageDestinationCreateWithURL(destURL, type, 1, nil)` + `CGImageDestinationAddImageFromSource` + `CGImageDestinationSetProperties` for the updated metadata.
- Single open/save pass per file, mirroring Android's `stampFinalMetadata`.

### Location

- `CLLocationManager` with `.desiredAccuracy = kCLLocationAccuracyNearestTenMeters`, `.distanceFilter = 50`. Request `whenInUse` authorization on first capture-screen mount.
- Cache last fix with a 30s TTL; refresh via `requestLocation()` (single-shot) when the cache is ≥15s old (coalesce threshold, mirror of Android).
- Worker-time refresh: wrap in `try await withTimeout(2.0) { locationProvider.refresh() }` — if it doesn't complete in 2 seconds, commit proceeds without GPS backfill.

### Settings

- Bookmark + flags: `UserDefaults.standard`. Keys mirror Android's set: `rootBookmark`, `stitchQuality` (enum raw), `shutterSoundOn`, `saveIndividualPhotos`, `saveStitchedImage`, `deleteDelaySeconds`, `deleteConfirmEnabled`, `seenGestureTutorial`.
- Expose as a `Published` stream (Combine) or `AsyncStream` for SwiftUI observation.
- Enforce the at-least-one-output invariant at both write time and read time.

### Haptics

- Capture: `UIImpactFeedbackGenerator(style: .light).impactOccurred()`.
- Commit-threshold cross + commit success: `UINotificationFeedbackGenerator().notificationOccurred(.success)`.
- Discard-threshold cross + discard success: `UINotificationFeedbackGenerator().notificationOccurred(.warning)`.
- Delete-threshold cross: `UIImpactFeedbackGenerator(style: .rigid)`.
- Prepare generators on `viewDidAppear` to warm the haptic engine; low latency matters for threshold feedback.

### Gesture zones

The queue strip is three sibling views, not a nested hierarchy:
- `DiscardZoneView`: a `UIView` owning its own `UIPanGestureRecognizer` filtered to leftward motion; fires discard on completion past threshold (distance OR velocity, mirroring Android's hybrid).
- A middle `UICollectionView` with horizontal scroll, long-press-then-drag reorder via `UICollectionViewDropDelegate`, and per-cell `UISwipeGestureRecognizer(direction: .down)` for delete-one.
- `BundleZoneView`: mirror of `DiscardZoneView`, filtered to rightward motion, fires commit.

Between cells, a 24dp-wide invisible divider view owns a separate pan recognizer for the divide / un-divide gesture. Z-ordered above the cells so it wins the touch.

Because zones and cells are physically separate views with their own recognizers, iOS's standard gesture arbitration routes touches unambiguously — no `shouldRecognizeSimultaneouslyWith` overrides needed.

The commit animation (queue contents sliding into the right zone with the zone flashing accent color) is a `UIViewPropertyAnimator` choreographed from a snapshot of the collection view and the zone's layer.

### Visual design

- Dark capture screen with white controls; system background elsewhere.
- SF Symbols for all icons: `gearshape`, `bolt.slash` / `bolt.badge.automatic` / `bolt`, `camera.rotate`, `photo.on.rectangle`, etc.
- Respect safe areas, especially bottom inset for home-indicator gesture interaction (mirror of Android's `navigationBarsPadding()` + `systemGestureExclusion`).
- Accent colors: commit-green `#2E7D32`, discard-amber `#B26A00`, delete-red (system destructive). Map to asset catalog with dark/light variants.

### Testing

- Port the three pure-function test suites 1:1:
    - `DividerOps` → `DividerOpsTests` (partition + remap-on-delete).
    - `OrientationCodec` → `OrientationCodecTests` (cardinal round-trip, snap boundaries, surface-rotation inverse).
    - `Stitcher.computeLayout` → `StitcherLayoutTests` (quality ceilings, height cap, heap budget, aspect preservation).
- Manual smoke path: same as Android — first-run folder pick + permissions + tutorial; capture 3–5 photos; swipe-commit; verify raw + stitched in bookmarked folder; swipe-discard + undo; let undo time out; delete-one; reorder; kill the app mid-queue; relaunch; queue is restored.

### Effort estimate (from Android delta)

- Camera + orientation + zoom + flash + lens flip: **~4 days**.
- Staging + manifest store + bookmark I/O helpers: **~2 days**.
- Worker + background task + operation queue: **~2 days**.
- Stitcher port: **~1 day** (layout math is pure; CoreImage composition is straightforward).
- EXIF (capture-time + final-pass): **~1 day**.
- Location provider with TTL: **~0.5 day**.
- Settings + DataStore-equivalent: **~1 day**.
- Capture UI + queue strip + gestures: **~4 days** (the gesture mechanics are the most subtle part; budget for playtesting).
- Bundle Preview + pending-delete + processing-row: **~2 days**.
- Gesture tutorial: **~1 day**.
- Polish (haptics tuning, shimmer animations, icon counter-rotation): **~2 days**.

**Total: ~3 weeks solo.** The Android reference is a 1:1 specification; most time goes to the gesture feel, not the architecture.

---

## See also

- [`README.md`](./README.md) — Android reference implementation: module layout, dependency versions, class-by-class responsibilities.
- [`RELEASE.md`](./RELEASE.md) — Signing, R8, Play Store / AAB packaging runbook.
- [`BACKLOG.md`](./BACKLOG.md) — Post-MVP ideas (OCR / MinerU companion, LocalSend peer-to-peer transfer).
- [`CLAUDE.md`](./CLAUDE.md) — Condensed architecture reference for Claude Code agents.
