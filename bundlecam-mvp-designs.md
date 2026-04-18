# BundleCam MVP — Native Designs (v1 scope)

_Pure capture tool. Photos go into a queue, the user commits a bundle, files land in folders. That's it._

---

## What v1 is (and isn't)

**In scope:**
- One-time output folder setup on first launch
- A single capture screen with a photo queue
- A single bundle commit action whose outputs are controlled by settings: a stitched single image AND/OR the raw individual photos with naming pattern, written to two parallel folders (at least one output must be on — both on is the default)
- Auto-captured EXIF: timestamp, GPS, orientation, bundle ID
- An in-app **Bundle Preview** list for reviewing and deleting saved bundles (per-bundle swipe-to-delete with a configurable undo window; modality icons indicate which outputs exist)
- Minimal settings

**Out of scope:**
- Capture modes (Document/Object/Scene), post-processing, auto-crop
- User-entered metadata (tags, notes, prefix chips)
- Full in-app viewer — the Preview screen lists bundles; for opening files the user deep-links out to the system file browser
- Export/share flows — output is files on disk; the user takes it from there
- Pipelines, cloud, OCR, automation hooks

---

## Naming pattern

`{date}-s-{xxxx}-p-{k}.jpg`

Where:
- `date` = calendar date of the bundle, formatted `yyyy-mm-dd`
- `xxxx` = zero-padded daily bundle counter, resets at local midnight
- `k` = zero-padded photo index within the bundle, starting at `01`

Example: `2026-04-14-s-0003-p-02.jpg` — the 2nd photo of the 3rd bundle started on 14 April 2026.

`yyyy-mm-dd` sorts chronologically in any file listing, which matters for every downstream tool that reads the folders in default order.

The stitched image for a bundle is named `{date}-s-{xxxx}-stitch.jpg` (no `-p-k`).

---

## Folder structure on disk

```
<user-selected-root>/
├── bundles/
│   └── {date}-s-{xxxx}/
│       ├── {date}-s-{xxxx}-p-01.jpg
│       ├── {date}-s-{xxxx}-p-02.jpg
│       └── ...
├── stitched/
│   ├── 2026-04-14-s-0001-stitch.jpg
│   ├── 2026-04-14-s-0002-stitch.jpg
│   └── ...
└── .bundlecam-staging/      ← internal; hidden from user
    └── session-{uuid}/
        └── p-{k}.jpg         ← raw photos written at capture time
```

By default a bundle commit writes to both `bundles/{bundle-id}/` (raw photos) and `stitched/` (the stitched image) simultaneously, so downstream tooling can consume either format without recapture. The "Bundle output" settings block exposes per-output toggles (**Individual photos in subfolder** / **Vertical stitched image**) so users who only need one of the two can skip the unused pipeline branch. At least one must remain on — the UI locks the only-on toggle, and the settings repository sanitizes a `(false, false)` read back to `(true, true)` as a safety net.

The two folders share a common bundle ID (`{date}-s-{xxxx}`), so raw photos and the stitched image for the same capture event are always findable and pairable.

`.bundlecam-staging/` is an internal working area, hidden from the user by the leading-dot convention. Its role is covered in the async pipeline section.

---

## User flow

**First launch**
1. App opens to a brief "Pick output folder" screen
2. User taps "Choose folder" → OS folder picker appears
3. App persists the reference and creates `bundles/` and `stitched/` subfolders
4. App drops straight into the capture screen

**Every subsequent launch**
1. App opens directly to the capture screen, folder already configured.

**Capture loop** — the user only ever does five things:

1. **Tap shutter** → photo taken, thumbnail appears at the right end of the queue
2. **Swipe the queue right** (from the left handle) → bundle commits: whichever outputs are enabled in settings are written in parallel (by default the stitched image to `stitched/` and the individual photos to `bundles/{bundle-id}/`), brief success flash, queue clears, daily counter increments
3. **Swipe the queue left** (from the right handle) → discard: queue clears without writing anything (with a brief undo toast)
4. **Swipe a single thumbnail down** → delete just that photo from the queue
5. **Drag a thumbnail horizontally** → reorder within the queue; this ordering determines both the vertical sequence in the stitched image and the `-p-k` index in the loose filenames

That's the entire interaction vocabulary.

---

## Capture screen anatomy

```
At rest (queue has photos, no gesture active):

┌─────────────────────────────────────┐
│   ⚙                              ⚡ │  ← settings | flash
│                                     │
│          ┌───────────────┐         │
│          │  live preview │         │
│          └───────────────┘         │
│                   ⬤                │  ← shutter
│                                     │
│  ║  ┌──┬──┬──┬──┬──┐  ║             │
│  ║  │p1│p2│p3│p4│p5│  ║             │  ← neutral grab handles | queue
│  ║  └──┴──┴──┴──┴──┘  ║             │
└─────────────────────────────────────┘


During a bundle gesture (finger holds left handle, drags →):

│  ║══════════════════▶ ✓             │
│  ▓  ┌──┬──┬──┬──┬──┐  ┃             │  ← green flow toward lit ✓ destination
│  ▓  │p1│p2│p3│p4│p5│  ┃             │
│  ▓  └──┴──┴──┴──┴──┘  ┃             │


During a discard gesture (finger holds right handle, drags ←):

│   ✕ ◀══════════════════║            │
│   ┃ ┌──┬──┬──┬──┬──┐  ▓             │  ← amber flow toward lit ✕ destination
│   ┃ │p1│p2│p3│p4│p5│  ▓             │
│   ┃ └──┴──┴──┴──┴──┘  ▓             │
```

The capture screen is chromeless. No mode toggle, no prefix field, no tagging — just preview, shutter, and a three-part queue strip with neutral handles. Bundling produces whichever outputs are enabled in settings (by default both the raw photos and the stitched image), so the user never has to choose at commit time.

### The three-part queue strip

At rest, the strip has three regions:

- **Left handle (~40pt/dp):** a neutral vertical bar, thin and grey. Reads as a grab-point. No ✕, no ✓, no color. It's a handle.
- **Middle (scrollable):** thumbnails. Direct gesture targets for reorder (horizontal drag) and delete (down swipe).
- **Right handle (~40pt/dp):** symmetric neutral vertical bar.

Both handles are dimmed when the queue is empty and become subtly vibrant when the queue has at least one photo.

### Semantic icons appear at destinations

The ✓ and ✕ are not stamped on static positions. They appear contextually on the **opposite edge** from the finger, because that edge is the destination the swipe is heading toward. An icon at the destination reads correctly ("swiping toward ✓ means save"); an icon at the starting point would contradict the swipe's direction.

| State | Left edge | Right edge |
|---|---|---|
| Rest | neutral handle | neutral handle |
| Finger on left handle | handle with subtle grab-pulse | transforms to green panel with ✓ |
| Finger on right handle | transforms to amber panel with ✕ | handle with subtle grab-pulse |

The transformation is fast (~120ms ease-out) so it feels responsive, not delayed.

Discard uses amber, not red, because discard has an undo — it's reversible. Red reads as "final/dangerous" and would make users hesitant to use a gesture that's actually safe to play with. Amber reads as "warning, but recoverable."

### The five user actions

| Action | Finger starts on | Motion | Result |
|---|---|---|---|
| Capture | Shutter | Tap | Photo added to right end of queue |
| Bundle | Left handle | Drag rightward | Green flow across queue → right edge lights up ✓ → at threshold, queue compresses and flies into the ✓ panel with a bright flash, queue clears |
| Discard | Right handle | Drag leftward | Amber flow across queue → left edge lights up ✕ → at threshold, queue puffs out through the ✕ panel, queue clears, undo toast for 3s |
| Delete one | A specific thumbnail | Swipe down | That thumbnail slides down and fades |
| Reorder | A specific thumbnail | Drag horizontally | Thumbnail sticks to finger, neighbors shift, release to drop |

Because each gesture has a **unique starting region**, disambiguation is deterministic. A touch that begins on a thumbnail will never trigger bundle or discard. A touch that begins on a handle will never trigger reorder. No velocity thresholds, no slop tuning.

### Mid-gesture visual feedback

The moment the finger touches a handle, a color "fills" out from the finger under the queue, tracking the finger position. As the finger drags across, the color advances and the destination edge becomes progressively more vibrant.

- **Bundle**: green gradient flows left-to-right under the queue. Thumbnails subtly tilt right as if being pushed. The ✓ panel on the right pulses brighter as the finger approaches. Crossing the commit threshold (around 60–70% of travel) triggers a haptic tick and the panel "locks on" — at that point, even if the finger lifts early, the gesture completes.
- **Discard**: amber gradient flows right-to-left. Thumbnails subtly tilt left. The ✕ panel on the left pulses. Same threshold behavior.

This continuous feedback makes the gesture feel physical. The user understands exactly where in the swipe they are, what action it commits, and — crucially — that they can abort by dragging back toward the starting handle before crossing threshold. On abort, the queue reverts to rest state, no harm done.

### Completion animations

- **Bundle commit**: thumbnails slide rightward and compress into the ✓ panel; ✓ flashes bright white-on-green for ~200ms; strong success haptic; where the queue was, a brief green shimmer with "Bundle 2026-04-14-s-0003 · saved" (fades in 150ms, holds 400ms, fades out 150ms); both edges return to neutral handle state, queue area is empty.
- **Discard**: thumbnails slide leftward and dissolve through the ✕ panel with a puff (scale down + fade); medium haptic; bottom toast "Discarded · Undo" with a 3-second progress bar. Tapping Undo within 3s restores the queue exactly.
- **Delete one**: the thumbnail slides downward and fades in ~200ms; light haptic; neighbors close the gap with a spring.
- **Reorder**: thumbnail lifts with a subtle 2dp shadow while dragging; neighbors animate out of the way with a 120ms ease; on release, thumbnail settles into slot with a gentle spring bounce.
- **Capture**: subtle shutter sound (toggleable); the new thumbnail flies in from the shutter button to the queue tail with a 300ms curve, light haptic tick.

### Touch targets

- Shutter: large circle, center-bottom, 80pt/dp
- Queue thumbnails: 56pt/dp squares, rounded 8pt, horizontal scroll if the queue overflows the middle region
- Handles: each ~40pt/dp wide × 72pt/dp tall. Slim at rest; expand visually to ~56pt/dp wide when finger is on them so the hit zone feels confident

No long-press, no pinch, no two-finger gestures, no preview mode. To inspect a photo already taken, the user opens the output folder in their OS file browser after bundling.

---

## Auto-captured metadata

Written to each photo's EXIF at capture time, with no user interaction:

| Field | Source | Notes |
|---|---|---|
| DateTimeOriginal | System clock | Per-photo timestamp |
| GPS coordinates | Last known location | Cached 30s to avoid GPS fix delay on every shot |
| Orientation | Device accelerometer | Honors device rotation |
| Make/Model | Device metadata | Standard |
| Bundle ID | Custom EXIF UserComment | `BundleCam:{bundle-id}:p{k}` — lets downstream tools reconstruct bundle membership and sequence from loose files even if folder structure is lost |

The custom UserComment preserves the product's value proposition even in the worst-case file-hygiene scenario: if files get moved, renamed, or mixed into a larger dataset, membership and sequence are still recoverable from EXIF alone.

---

## Async pipeline — capture → stage → commit → process

The UX target is **machinegun cadence**: shot-shot-shot-swipe-shot-shot-shot-swipe with no perceptible delay. The user must be able to take the next photo within milliseconds of a bundle swipe, regardless of how long stitching and final writes actually take. The lifecycle is split into three phases with the heavy work moved off the interaction path.

### Phase 1 — Capture (every shutter tap, ≤ ~100ms)

The photo is written to the staging folder immediately on capture, not held in memory:

1. Camera captures JPEG → buffer
2. Decode small thumbnail for the queue UI (~30ms)
3. Spawn async task: write full-resolution JPEG to `.bundlecam-staging/session-{uuid}/p-{k}.jpg` with EXIF embedded
4. UI updates the queue with the thumbnail immediately (the thumbnail holds only a small in-memory bitmap + a file path reference to staging)
5. Shutter is ready for the next tap

In-memory footprint per queued photo is just the thumbnail (~50KB), not the full photo (~5MB). The user can queue 50+ photos without memory pressure, and the raw JPEGs are already durably on disk if the app is killed.

### Phase 2 — Bundle commit (on swipe-right, ≤ ~50ms)

The swipe gesture does almost no actual work:

1. Snapshot the current queue state: ordered list of staging file paths + the daily counter's current value
2. Atomically increment the daily counter (so the next bundle gets the next ID even if this one is still processing)
3. Hand off the snapshot to the background worker (enqueue a job)
4. Clear the in-memory queue and return both edges to their empty state
5. UI is immediately ready for the next capture

No file I/O on the main thread. No stitching. No waiting. The swipe returns control in under a frame.

### Phase 3 — Background processing (per bundle, seconds to tens of seconds)

A background worker, one bundle at a time (FIFO, single-concurrency to avoid disk and memory contention), does the real work:

1. **Loose write**: for each staged photo in the captured order, move it from `.bundlecam-staging/session-{uuid}/p-{k}.jpg` to `bundles/{bundle-id}/{bundle-id}-p-{kk}.jpg`. On same-volume filesystems this is a metadata-only rename — microseconds. Embed the bundle-ID EXIF UserComment during the move.
2. **Stitch write**: read the now-final raw files sequentially, normalize widths, concatenate vertically, encode JPEG at quality 0.85, write to `stitched/{bundle-id}-stitch.jpg`. Height cap: 32,000 px (above that, memory allocation and JPEG encoders start to fail on mid-range devices).
3. Clean up the staging session folder.
4. On success: silent.
5. On failure: surface a non-blocking banner above the queue strip ("Bundle 0003 couldn't save · Retry"). Do not interrupt ongoing capture.

### Why this architecture is resilient

- **Photos survive app kill**: they're on disk from the moment of capture, in the staging folder. On relaunch, the app detects orphaned staging sessions and silently restores the queue.
- **Backpressure is natural**: the background worker queue can grow, but capture experience stays fast regardless. If the user bundles faster than stitching completes, bundles pile up in the worker queue and catch up during pauses.
- **Memory pressure stays flat**: photos are on disk, not in RAM; the worker holds at most one bundle's worth of decoded image data at a time during stitching.
- **Camera pipeline never contends**: capture-time disk writes are small async tasks on a utility queue; stitching runs on a lower-priority queue with explicit throttling.

---

## Settings screen

1. **Output folder** — shows current path (full URI wraps below the friendly name), "Change" button anchored top-right re-triggers the folder picker
2. **Bundle output** — two switches for the artifacts a commit produces:
   - `Individual photos in subfolder` (raw JPEGs into `bundles/{bundle-id}/`)
   - `Vertical stitched image` (the single tall JPEG into `stitched/`)
   At least one must remain on — the UI locks whichever switch is the sole-on, and a caption ("At least one output is required.") surfaces only while a lock is active. Individual photos are always saved at original quality.
3. **Stitch Quality** — nested under `Vertical stitched image`, indented; compact Material 3 dropdown (Low / Standard / High) that affects JPEG quality and downscale thresholds for the stitched image only. Disabled when stitching is off (the preference is preserved for when the user re-enables it).
4. **Shutter sound** — On / Off
5. **Confirm before deleting a bundle** — On / Off (default On). When off, swipe-to-delete skips the confirmation dialog and goes straight to the pending-delete state (or straight to hard delete if the undo window is 0s).
6. **Undo window for bundle deletion** — dropdown, `Off` / `1s` … `10s` (default `5s`). Controls both the countdown shown on the pending-delete row and how long the user has to tap Undo before the files are hard-deleted. `Off` removes the pending state entirely — confirmed deletes go through immediately with no undo affordance.
7. **App version** — info only

No accounts, no cloud, no permissions management beyond OS-level.

---

## Bundle Preview screen

Reached from the capture screen's top-right photo-library icon. Pure-native Material 3 styling — `TopAppBar` with back-left, title-center, "open in system file browser" action on the right, and a `LazyColumn` of bundle rows below.

Each row shows:
- Up to three small thumbnails in a fixed-width strip (the strip is always the same width regardless of how many thumbs the bundle has, so row alignment stays consistent). Thumbnails are drawn from the raw subfolder photos when present — they crop cleanly. If only a stitched image exists for a bundle, the stitch is used as the single thumbnail.
- The monospace bundle id and a photo-count subtitle.
- Modality icons on the right: `PhotoLibrary` outline when the raw subfolder exists, `ViewStream` outline when the stitched image exists. Both appear when both outputs were kept.

**Swipe left** on a row to delete. By default (confirmation on) this surfaces an `AlertDialog` summarising which modalities will be removed for that bundle; confirming marks the row pending. A pending row keeps the same layout skeleton — same thumbnails, same width — but swaps the id/count for "Deleting in Xs" in the error color and the modality icons for an `Undo` text button with a live countdown. Tapping `Undo` within the window cancels the delete; letting it expire hard-deletes every modality via SAF.

Multiple bundles can be pending-delete simultaneously — each has its own independent countdown and its own Undo button. Leaving the screen while deletes are pending doesn't cancel them: they continue to completion on the app-scoped coroutine.

**Processing rows.** When a user swipes to commit and immediately opens this screen, the background worker is still writing the bundle (seconds to tens of seconds for stitch) so nothing has landed in SAF yet. The screen subscribes to in-flight worker state and renders a "processing" row at the top of the list for each pending bundle: a circular spinner in the leading thumbnail slot, the monospace bundle id, and a "Processing…" subtitle. Processing rows aren't swipeable. When the worker finishes, the screen refreshes the SAF listing *before* dropping the processing row, so the completed row takes over with no blank frame in between. Failed workers simply disappear from the processing set; the failure banner remains the capture screen's concern.

---

## iOS build notes

**Camera:** AVFoundation with a custom `AVCaptureSession`. Preview via `AVCaptureVideoPreviewLayer`. Capture via `AVCapturePhotoOutput` in high-quality mode. Async capture pipeline so rapid taps don't block.

**Async pipeline:** capture writes use `DispatchQueue.global(qos: .utility)`. Background processing uses a dedicated serial `OperationQueue` with `qualityOfService = .background` and `maxConcurrentOperationCount = 1` (single-concurrency stitching to bound memory). Wrap each bundle's processing in `UIApplication.beginBackgroundTask` so iOS grants the ~30 seconds typically needed to finish a stitch even if the user returns to the home screen. For very large backlog scenarios, register a `BGProcessingTask` that drains remaining bundles when the system is idle and charging.

**Folder persistence:** `UIDocumentPickerViewController` in `.open` mode with `directoryURL` support. The returned URL is security-scoped — save as `bookmarkData(options: .withSecurityScope)` and restore with `startAccessingSecurityScopedResource()` on every app launch. Budget a half-day for getting bookmark handling right.

**File writes:** direct `Data.write(to:)` into subfolders of the bookmarked root. Create `bundles/` and `stitched/` on first access if not present.

**EXIF writes:** `CGImageDestinationAddImage` with a metadata dictionary; custom UserComment via `kCGImagePropertyExifUserComment`.

**Location:** `CLLocationManager` with "When in use" authorization. Request on first shutter tap with auto-location enabled.

**Haptics:** `UIImpactFeedbackGenerator(.light)` for capture; `UINotificationFeedbackGenerator().notificationOccurred(.success)` for bundle commit.

**Design language:** dark capture screen with white controls. System gray tones elsewhere. SF Symbols for all icons. No large titles on capture screen (it's chromeless); small titles on settings. Respect Safe Area insets for the bottom queue strip.

**Gesture implementation:** the queue strip is three sibling views in a horizontal stack — `DiscardZoneView`, a `UICollectionView` (middle), and `BundleZoneView`. Each owns its own gestures:

- Middle `UICollectionView`: horizontal scrolling, `UICollectionViewDropDelegate` for reorder (sticky drag on a cell), and per-cell `UISwipeGestureRecognizer(direction: .down)` for delete.
- `BundleZoneView`: a `UIPanGestureRecognizer` filtered to rightward motion. On completion past threshold, fires bundle commit.
- `DiscardZoneView`: a `UIPanGestureRecognizer` filtered to leftward motion. On completion past threshold, fires discard.

Because the zones own their own gestures and are physically separate from the collection view, there is no gesture arbitration needed — touches that begin in a zone don't reach the collection view, and vice versa.

The commit animation (queue contents sliding into the right zone with the zone flashing accent color) is a `UIViewPropertyAnimator` choreographed from the collection view's snapshot and the zone's layer.

---

## Android build notes

**Camera:** CameraX with `Preview` + `ImageCapture` use cases. Preview via `PreviewView`. Capture via `takePicture` with in-memory buffer, then immediately write to the staging folder via the `Dispatchers.IO` coroutine scope. CameraX handles device quirks, removing the need to touch Camera2 directly.

**Async pipeline:** capture-time writes use `CoroutineScope(Dispatchers.IO)` so the shutter returns immediately. Background bundle processing uses **WorkManager** — one `OneTimeWorkRequest` per bundle, with a `uniqueWorkPolicy` that chains them serially under a shared tag. WorkManager provides survival across process death, resume on next launch after interruption, retries with backoff, and device-storage constraint respect. Input data: the staging folder `Uri`, the assigned bundle ID, and the photo ordering.

**Folder persistence:** `ActivityResultContracts.OpenDocumentTree` on first launch. Persist the returned `Uri` in `SharedPreferences`. Call `contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)` so the grant survives app kills and reboots. Access via `DocumentFile.fromTreeUri(context, rootUri)`; create `bundles/` and `stitched/` as child `DocumentFile` directories on first access.

**File writes:** `DocumentFile.createFile("image/jpeg", filename)` then `contentResolver.openOutputStream(docFile.uri)`. Works with any SAF-backed provider including Google Drive folders.

**EXIF writes:** `androidx.exifinterface.media.ExifInterface` on the output file. Supports custom UserComment via `TAG_USER_COMMENT`.

**Location:** `FusedLocationProviderClient` with `PRIORITY_BALANCED_POWER_ACCURACY`. Cache last known location in the ViewModel with a 30s TTL.

**Haptics:** `HapticFeedbackConstants.CONTEXT_CLICK` for capture; `HapticFeedbackConstants.CONFIRM` for commit.

**Design language:** Material 3 with dynamic color. Dark capture screen with tonal surfaces for controls; primary color used for the commit success flash and reorder drop indicator. Standard MD3 type scale. Bottom inset for gesture navigation on modern devices.

**Gesture implementation:** the queue strip is a Compose `Row` with three children — `DiscardZone`, `LazyRow` (middle), `BundleZone`. Each child owns its own `pointerInput` block:

- `LazyRow` middle: reorder via immediate horizontal drag on a thumbnail; per-thumbnail vertical drag threshold for delete.
- `BundleZone` right: `detectHorizontalDragGestures` filtered to positive (rightward) deltas, commits on crossing a distance threshold with terminal velocity.
- `DiscardZone` left: `detectHorizontalDragGestures` filtered to negative (leftward) deltas, commits similarly.

Because each child has its own input handler and they are disjoint in layout, Compose's natural hit-testing handles disambiguation for free — no nested gesture arbitration, no slop tuning.

Stylistically, the zones feel like **drawer handles**: touching one gives an immediate visual response (a subtle color deepen and scale-up of the zone), and the swipe feels like pulling the handle across the queue. Reorder still feels sticky (thumbnail follows finger instantly). The user learns the model in one or two bundles.

---

## Platform comparison (MVP scope)

| Concern | iOS approach | Android approach |
|---|---|---|
| Folder persistence | Security-scoped bookmark (some ceremony) | SAF URI + persistable permission (some ceremony) |
| User sees bundles in their file manager | Files app shows folder if in Files-accessible scope | Any app via SAF — including Drive-synced folders |
| Pipeline-friendliness of output | Files in Files app | Files in user's chosen folder, including cloud-synced — **stronger** |
| Build effort for MVP | ~3–4 weeks solo | ~3–4 weeks solo |

For a pipeline-producer tool, Android is the stronger first platform: `ACTION_OPEN_DOCUMENT_TREE` lets the user point the app at a Drive/Nextcloud/Syncthing folder once, and every bundle automatically ends up wherever their downstream pipeline already reads from. iOS's sandbox makes that flow more friction-ful.

---

## Design decisions

1. **Date format** → `yyyy-mm-dd`. Sorts chronologically in every file listing, which every downstream tool benefits from.

2. **App-kill / relaunch behavior** → **silent auto-resume**. If the app detects an orphaned staging session on launch, the queue is restored transparently and the user lands on the capture screen with the previous photos already queued. No prompt, no confirmation — the user can continue shooting, swipe to bundle, or swipe to discard, exactly as if the app had never quit. The staging session UUID is preserved so if the app is killed again mid-way, the same behavior applies on the next launch.

3. **Empty-queue gesture handling** → **swipes disabled on empty queue**, both handles dimmed and unresponsive. A 1-photo queue commits normally (stitch output is the single photo copied; raw folder contains that one file). Gesture vocabulary stays consistent regardless of queue size.

4. **First-launch coach-marks** → **none**. The neutral handles plus contextual ✓/✕ destinations are self-evident. Revisit post-MVP if user testing shows discoverability problems.

5. **Storage double-cost management** → **no maintenance UI**. Modern devices and user-chosen folders (often cloud-synced) handle this naturally.

6. **In-flight bundle indicator** → **silent**. No badges, no counters, no status rows. Failures still surface as non-blocking banners per the async pipeline spec.

---

## Backlog

Post-MVP ideas, not scoped for the current milestone. Capture here so they don't get lost; re-evaluate once the core capture-and-commit loop is stable.

1. **OCR / document-understanding pipeline for bundle → Markdown.** Post-process a committed bundle (raw JPEGs + stitched image) into a structured Markdown document written to `bundles/{bundle-id}/{bundle-id}.md` alongside the existing outputs. **Scope note:** Recon is a personal-use project, so license terms (AGPL-3.0 in particular) are not constraints, and the design criterion collapses to "which path produces the highest-quality output". That changes the architecture meaningfully — the cherry-pick-Apache-2.0-models path that earlier drafts treated as a middle tier was a license-driven workaround; without the AGPL constraint and with quality as the goal, it's strictly dominated by running real MinerU off-device. The shape reduces to two paths: a fast on-device fallback and a high-quality desktop companion.

    - **Best-quality path — MinerU VLM on a desktop companion.** Run [MinerU](https://github.com/opendatalab/mineru) (current `mineru` 3.0.x as of April 2026) on a personal desktop, reached over the LAN via the LocalSend interop in item 2 below. Use the **VLM backend** (`MinerU2.5-2509-1.2B` — 1.16B-param Qwen2-VL derivative, ~4.6 GB BF16; or `MinerU2.5-Pro-2510` if hardware permits) for the strongest open-source document-understanding output currently available: layout-faithful Markdown with LaTeX formulas, HTML tables, and learned reading order. Realistic hardware expectations: an Apple Silicon Mac with 16+ GB unified memory runs the VLM workably at CPU/MPS speeds (tens of seconds per page); a Linux/Windows box with a discrete GPU and 8+ GB VRAM runs it near-real-time. If GPU isn't available, the **pipeline backend** (`mineru -b pipeline`) is the workable second choice on CPU-only desktops — slower and slightly less faithful on complex layouts, but no GPU dependency.
    - **Offline fallback — ML Kit Text Recognition v2 on-device.** When no companion is reachable (no LAN, desktop off, on the road), chain a separate `OneTimeWorkRequest` after `BundleWorker` that runs **ML Kit Text Recognition v2** (`com.google.mlkit:text-recognition:16.0.1` for Latin bundled, ~4 MB APK delta per script per ABI; or `play-services-mlkit-text-recognition:19.0.1` unbundled, ~260 KB APK + ~1.5–4 MB Play-Services model fetched on first call; supports Latin / CJK / Japanese / Korean / Devanagari as separate artifacts). Pure CPU via TFLite + XNNPACK — **no NNAPI / GPU acceleration** for text recognition, so SD 7-class NPU/DSP buys nothing. Latency at full 12MP: ~500–900 ms warm on SD 7-class, ~250–500 ms on Pixel 7a/8a, 1–3 s on SD 4/6-class; cold-start adds 100–400 ms. **Downscale to ~2 MP long-edge before inference** — Google's docs note characters need only 16×16 px and there's "no accuracy benefit beyond 24×24", and downscaling cuts latency ~6× with zero quality loss for document text. Consumes raw per-page JPEGs from `bundles/{bundle-id}/` (never the stitched composite — stitch is for humans, raw pages are for the model), joins line-level text in reading order with one `## Page N` heading per photo, writes the `.md` sidecar via SAF. **Decode → process → recycle each Bitmap per page** — a 12MP ARGB_8888 bitmap is ~48 MB, so holding all 20 page bitmaps simultaneously will OOM the 512 MB heap; one at a time is comfortably safe. **Reuse a single `TextRecognizer` for the whole bundle** and `close()` it in `finally` to release native resources; do **not** parallelize `process()` calls (Google's docs warn against concurrent recognizers and parallelism doesn't help anyway). Reuse the process-wide `workMutex` so OCR never races a stitch. WorkManager constraints: `setRequiresStorageNotLow(true)` + `setRequiresBatteryNotLow(true)`; explicitly **not** `setRequiresCharging` / `setRequiresDeviceIdle` (would defer by hours). Surface failures via the existing `ActionBanner`; no status rows. No formulas, no tables-as-HTML, modest reading-order — but lands a usable sidecar with zero setup, fully offline, no GMS dependency if Latin-bundled is used.

    - **Companion mechanics.** The "MinerU companion" can be as thin as a Python script that watches an inbox folder, runs `mineru -p {file_or_dir} -o {out_dir} -b vlm` per bundle, and drops the result back. The architectural commitment is just "the bundle ships off-device; something out there produces a `.md`; the `.md` lands in `bundles/{bundle-id}/`". Concrete options ranked by setup effort:
        - *Easiest*: a Syncthing-shared folder between phone and desktop, plus a desktop-side `inotifywait`/`fswatch` shell loop that runs `mineru` on each new bundle and writes the `.md` back into the same folder. Zero protocol work.
        - *Cleaner*: a small Python receiver speaking the LocalSend protocol (item 2), accepting bundles, running MinerU, and replying via LocalSend's send-back. Pairs well with item 2's clean-room sender.
        - *Heaviest*: a first-party Kotlin desktop app (Compose Multiplatform) that wraps `mineru` as a subprocess and presents a peer to the phone. Probably overkill for personal use.

    - **Routing.** Two reasonable defaults — pick one based on usage pattern. (a) **Always-fallback, opt-in upgrade**: ML Kit always produces `{bundle-id}.md` shortly after commit; sending the bundle to the desktop companion later overwrites it with the higher-fidelity result. Has the property that something always lands. (b) **Tagged-for-companion**: the user marks a bundle as "needs OCR companion" before/after commit; the app routes to the companion when reachable and skips ML Kit for that bundle. Avoids redundant work but means some bundles sit without a `.md` until the desktop is online. (a) is the safer default; (b) is worth considering if the on-device pass turns out to be wasted effort for the user's actual workflow (e.g. always-have-desktop, never-on-the-road).

    - **Architectural rules.** OCR output never influences stitch layout — visual and semantic artifacts stay independent and separately reproducible. The on-device pass runs as a chained `WorkRequest` after `BundleWorker`, not as a third stage inside it, so failures retry independently and the stitch-mutex scope stays tight. Extend the `PendingBundle` manifest with a `sidecarMarkdown` field rather than introducing a second manifest type. The companion route doesn't touch the worker pipeline at all — it's a user-initiated send (item 2) followed by an inbound `.md` write whenever the desktop replies.

    - **Open questions.** Should the on-device ML Kit `.md` be considered "draft" and overwritten by the companion result, or kept under different filenames (`{bundle-id}.md` vs. `{bundle-id}.mineru.md`) so the two outputs can be compared? How does a future bundles browser surface the per-bundle state ("draft", "upgraded by companion", "send pending")? Should the companion script live in the Recon repo (`tools/mineru-companion/`) so the personal-use setup is one `git clone` + `pip install mineru` away?

2. **LocalSend interop for peer-to-peer bundle transfer.** Implement a clean-room Kotlin sender that speaks the [LocalSend protocol](https://github.com/localsend/protocol) (current spec **v2.1**, cut 2026-01-13; reference app v1.17.0) so a committed bundle (raw JPEGs + stitched image, plus any `.md` sidecar) can be pushed to any device on the LAN running LocalSend — desktop, phone, tablet, or our own MinerU companion from item 1. We are **not** bundling the upstream Flutter app; just enough protocol to send. Realistic effort: **~2–4 days** for a working sender, plus 1–2 days for cert-fingerprint UX and SAF-streaming polish.

    - **Discovery.** On share-sheet open, acquire a `WifiManager.MulticastLock`, open a `MulticastSocket` joined to `224.0.0.167:53317` on every non-loopback IPv4 interface, and emit one announce — JSON `{alias, version:"2.1", deviceModel, deviceType:"mobile", fingerprint, port:53317, protocol:"https", download:false, announce:true}`. Peers reply via `POST /api/localsend/v2/register` (preferred) or a multicast response with `announce:false`. Drop self-echoes by `fingerprint`. Release the lock the moment the sheet closes — the spec's "no background multicast chatter" rule is non-negotiable.
    - **Transport.** HTTPS on TCP `53317`, prefix `/api/localsend/v2/…`. Use **OkHttp 4.12+** with a custom `X509TrustManager` that accepts any self-signed cert plus a no-op `HostnameVerifier`; trust is fingerprint-pinned (SHA-256 of the peer's leaf cert, captured at discovery and re-verified post-handshake), not CA-based. Generate one self-signed cert per install and cache it.
    - **Upload flow.** Three calls per bundle. (1) `POST /api/localsend/v2/prepare-upload` with `{info, files:{fileId:{id, fileName, size, fileType:"image/jpeg", sha256?}}}` (optional `?pin=…`); receiver shows the trust prompt here, response is `{sessionId, files:{fileId:token}}`. (2) Per file, `POST /api/localsend/v2/upload?sessionId=…&fileId=…&token=…` with the raw binary as the request body — **not** multipart, one file per request, parallelizable across files within the bundle. (3) `POST /api/localsend/v2/cancel?sessionId=…` on user abort. Errors: 401 PIN required, 403 rejected by user, 409 session blocked, 429 rate-limit, 204 "already received". No native resumability — a failed upload re-runs the whole file.
    - **SAF streaming.** A custom `RequestBody.writeTo(sink)` that pulls from `contentResolver.openInputStream(documentFile.uri)` and copies in chunks. Never `readBytes()` the stitched JPEG (can be 20 MB+).
    - **Permissions.** `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_NETWORK_STATE`. `NEARBY_WIFI_DEVICES` (Android 13+) is **not** required for outbound multicast on an already-connected Wi-Fi network — skip it. JSON: reuse existing `kotlinx.serialization`; add `Info`, `PrepareUploadRequest`, `FileMetadata`, `PrepareUploadResponse`.
    - **UX shape.** Action lives on a future bundles-browser row (long-press → action sheet → bottom sheet that scans on open and dismisses on close, listing peers as they register). Cert fingerprint shown as a short hex on first contact for trust-on-first-use. Gate send on `manifestStore.get(bundleId) == null` so we never ship a bundle whose `BundleWorker` is still running. Progress and failures use the existing `ActionBanner`; no persistent status rows. The capture screen's silence principle stays intact — no "share" affordance lives there.
    - **Edge cases.** Hotel / carrier networks blocking multicast (no fix — surface "no peers found"); AP isolation hiding peers on the same SSID (same — user-actionable, not app-fixable); IPv6-only Wi-Fi where the IPv4 multicast group is unreachable (LocalSend desktop falls back to a `/24` legacy TCP sweep — defer this to v2, MVP can require IPv4); upstream protocol breaking changes (pin to `v2.1` in README, accept that an eventual bump may be required).
    - Open questions: trust-on-first-use vs. always show fingerprint; whether to also implement **receive** (would need an embedded HTTP server — Ktor server-cio or NanoHTTPD — and a SAF-folder write target, useful symmetrically for getting the MinerU companion's `.md` back); whether to allow batch-share of multiple bundles in one session or keep it one-bundle-per-share for simplicity.
