# Recon — Design Spec

_Pure multimodal capture tool. Photos, videos, and voice recordings go into one interleaved queue, the user commits a bundle, files land in folders. That's it._

This document is the **canonical product and interaction spec** for Recon. It is platform-neutral: every claim is grounded in the shipped Android reference implementation, but the architecture, resilience model, and interaction vocabulary described here are the target for any port (iOS first).

For the Android-specific technical reference (file layout, module responsibilities, dependency versions), see [`README.md`](./README.md). For release/signing runbook, see [`RELEASE.md`](./RELEASE.md). For post-MVP ideas (OCR / LAN peer-to-peer transfer, tutorial polish), see [`BACKLOG.md`](./BACKLOG.md).

---

## Purpose

Recon captures **bundles** of mixed media. A bundle is an ordered sequence the user decides is related — pages of a document, facets of an object, the photo-and-voice-memo for a scene, a video walkaround and its follow-up stills — committed as one unit. The queue interleaves three modalities:

- **PHOTO** — JPEG stills via CameraX `ImageCapture`.
- **VIDEO** — MP4 clips via CameraX `VideoCapture<Recorder>` (Media3 Muxer, audio track included by default; user can toggle audio off via the mic button left of the video shutter).
- **VOICE** — `.m4a` voice memos via `MediaRecorder` (AAC-LC 48kHz mono ~96kbps).

A commit produces up to two parallel outputs in the user's chosen folder:

- **Raw files** in `bundles/{bundle-id}/{photos,videos,audio}/` — one file per captured item, routed by modality into a subfolder.
- **A vertically-stitched JPEG** in `stitched/` — photo-only; videos and voice do not participate in stitch. A partition with zero photos skips the stitch entirely.

Both outputs share a common bundle ID, so a downstream pipeline can reconstruct bundle membership from either format.

The UX target is **machinegun cadence**: shoot-shoot-shoot-swipe-record-stop-shoot-swipe, with no perceptible delay between any tap and the next. All heavy work (EXIF stamping, SAF I/O, stitching, video finalization) happens off the interaction path.

## Scope

**In scope:**
- One-time output-folder setup on first launch.
- A single capture screen with a mixed-modality queue.
- Three capture modalities — photo, video, voice — switched via a top-bar modality pill or a horizontal swipe on the viewfinder (peer gestures, either works).
- A single swipe-commit gesture whose outputs are controlled by settings (raw folder AND/OR stitched image; at least one on; both on is the default).
- Auto-captured EXIF on photos only (timestamp, GPS, orientation, bundle-ID marker). Video and voice carry no EXIF; bundle membership for those is path-based.
- An in-app **Bundle Preview** list for reviewing and deleting saved bundles (per-bundle swipe-to-delete with configurable undo window), rendering mixed-modality thumbnails.
- First-run gesture tutorial (6 steps — modality switch, commit, discard, delete-one, reorder, divide), step-set-persisted so future additions don't re-show everything.
- Minimal settings: output folder, output toggles, stitch quality, camera mode (ZSL/EXT), video quality, max video length, shutter sound, delete-confirmation + undo window.

**Out of scope:**
- Capture modes (document / object / scene), post-processing, auto-crop.
- User-entered metadata (tags, notes, prefix chips).
- A full in-app media viewer — Bundle Preview lists bundles; opening individual files deep-links to the system file browser / OS-default player.
- Export / share flows — output is files on disk; the user takes it from there.
- Background voice recording — recording only runs while the capture Activity is foregrounded; auto-stops on pause. No `FOREGROUND_SERVICE_TYPE_MICROPHONE`.
- Pipelines, cloud, OCR, automation hooks. (See [`BACKLOG.md`](./BACKLOG.md) for post-MVP directions.)

---

## The interaction vocabulary

The entire app, once onboarded, is seven gestures:

| Gesture | Start region | Motion | Effect |
|---|---|---|---|
| **Capture** | Shutter button | Tap (photo: one-shot; video/voice: tap-to-start, tap-to-stop) | Item appended to queue's right end on completion |
| **Switch modality** | Viewfinder area or modality pill | Horizontal swipe on the preview slab, or tap a segment of the top-bar pill | Camera/mic session reconfigures; shutter glyph and viewfinder contents swap (`VIDEO ← PHOTO → VOICE`) |
| **Commit** | Left handle of queue strip | Drag right past threshold | Queue → bundle(s) written to disk |
| **Discard** | Right handle of queue strip | Drag left past threshold | Queue cleared (with 3-second undo) |
| **Delete one** | A thumbnail in the queue | Swipe down past threshold | Just that item removed |
| **Reorder** | A thumbnail in the queue | Long-press then drag horizontally | That thumbnail moved within the queue |
| **Divide / Un-divide** | The gap between two adjacent thumbnails | Swipe down to insert a divider; swipe up to remove | Queue partitions into sub-bundles; one swipe-commit produces N parallel bundles |

Because every gesture has a **unique starting region**, disambiguation is deterministic. A touch on a thumbnail never triggers commit or discard; a touch on a handle never triggers reorder; a horizontal swipe on the preview never triggers reorder (which needs a long-press first). There is no velocity arbitration between competing gestures and no slop tuning.

The seven gestures compose: a user can shoot photos, swipe to VIDEO, record a clip, swipe to VOICE, capture a memo, reorder items, insert a divider, delete a bad shot, and commit — all in one queue session — and the pipeline treats it as a single atomic operation at the end.

---

## Capture screen anatomy

The capture screen is locked to portrait orientation (see [Design decisions § Portrait lock](#design-decisions)). Every icon counter-rotates to match the device's physical orientation, so labels and glyphs always read right-side-up.

```
┌─────────────────────────────────────┐
│  ⚙    VIDEO · PHOTO · VOICE     🖼  │  ← top bar: settings · modality pill · bundle library
│                                     │
│   ┌─────────────────────────────┐   │
│   │                             │   │
│   │    preview / waveform       │   │  ← 3:4 slab (per-modality content)
│   │  (tap-to-focus, pinch-zoom) │   │
│   │                             │   │
│   └─────────────────────────────┘   │
│         0.5×   1×   2×   5×         │  ← zoom chips (PHOTO + VIDEO only)
│                                     │
│       ⚡auto    ⬤    ⇆             │  ← flash(PHOTO) · shutter · lens flip(PHOTO+VIDEO)
│                                     │
│   ║  ┌──┬──┬──┬──┬──┐  ║            │  ← queue strip: left handle · tray · right handle
│   ║  │p1│v2│a3│p4│p5│  ║            │    (72dp tall, 56dp thumbs, 6dp gap, mixed media)
│   ║  └──┴──┴──┴──┴──┘  ║            │
└─────────────────────────────────────┘
```

### Top bar (inside status-bar padding)

- **Settings** (left) — gear icon, opens the Settings screen.
- **Modality pill** (center) — three-segment `VIDEO · PHOTO · VOICE` pill. The selected segment is highlighted; the indicator animates linearly with viewfinder swipe progress. Disabled mid-recording and mid-rebind (icons dim; taps rejected). The pre-multimodal `EXT / ZSL` camera-mode toggle moved to Settings → Camera mode (live-applied, no commit-time freeze).
- **Bundle library** (right) — photo-library icon, opens the Bundle Preview screen. Disabled until the user has picked an output folder.

### Capture slab (viewfinder + control row)

The viewfinder and control row translate together as one unit during modality swipes (see [Switch modality](#switch-modality)). Content inside the slab is per-modality:

- **PHOTO** — live camera preview, 3:4 aspect ratio. Tap-to-focus fires an `AF + AE` metering action (3-second duration) and shows an animated circle indicator at the tap point (scale 1.5× → 1×, 500ms lifetime). Pinch-to-zoom multiplies the current zoom ratio; the visible ratio updates the zoom chip selection live.
- **VIDEO** — same live preview, same tap-to-focus + pinch-to-zoom. `VideoCapture<Recorder>` is bound alongside `ImageCapture` via CameraX's concurrent-bind; on hardware that rejects the combined bind (camera level FULL or lower), the controller falls back to a modality-specific bind that rebinds on PHOTO↔VIDEO switches only. Flash is not offered in VIDEO (see Controls row).
- **VIDEO (recording)** — a red pill with a pulsing white dot + `M:SS` elapsed-time caption overlays the bottom edge of the preview (BottomCenter, inside the preview Box so it doesn't reflow the zoom chips / shutter row / queue below). When the audio track is on, the pill also shows a small white 4-bar mini-equalizer on its right edge — a visual hint that the clip carries voice. The wave is driven by CameraX's `VideoRecordEvent.Status.recordingStats.audioStats.audioAmplitude` (~1 Hz updates, smoothed via `animateFloatAsState` for responsiveness, with a continuous phase ramp + irregular per-bar offsets so it reads as alive even at constant amplitude) and a small height floor so the bars stay visible during silence. The pill is a `Row` with `wrapContentSize` semantics, so it stays horizontally centered as the wave widens it. Lens-flip stays in its usual right-slot position, disabled/greyed during recording. The pill rotates with device orientation alongside the other control icons.
- **VOICE** — the camera preview hides (alpha=0 but layout preserved to avoid jank on switch back). A **waveform overlay** replaces it: flat centerline while idle, a scrolling amplitude-bar buffer (~128 samples, ~4s visible) while recording, fed by `MediaRecorder.getMaxAmplitude()` polled at ~33ms and collected directly from the `StateFlow` (not via `snapshotFlow { flow.value }`, which would never re-emit since `StateFlow.value` isn't a Compose snapshot state). Elapsed time renders as a large `M:SS` caption under the waveform — the focal composition inside the scrim. Flash and lens-flip buttons hide entirely (neither applies to a mic session).

### Zoom chips

Row of pill-shaped buttons for presets `0.5× / 1× / 2× / 5×`. Shown in PHOTO and VIDEO; hidden in VOICE (the slot is empty, layout preserved). The row filters to only the presets that fall within the device's reported min/max zoom range (a phone without an ultrawide won't show `0.5×`). The currently-selected ratio renders with a trailing `×` suffix. Width transitions animate over 180ms linear when the set of available presets changes.

### Bottom controls row (per-modality)

Three slots laid out as **left · center · right**. Contents vary by modality:

| Slot | PHOTO | VIDEO | VOICE |
|---|---|---|---|
| Left | Flash cycle `Off → Auto → On` (applied live to `ImageCapture`; re-applied after rebinds) | Mic toggle `Mic ↔ MicOff` (drives `videoAudioEnabled`; default on; disabled mid-record because `Recorder` freezes audio mode at `start()`) | _(hidden)_ |
| Center | `ShutterButton` (80dp white ring + white fill) | `VideoShutterButton` (same geometry; idle = red ring + red fill; recording = red ring + **animated 28dp red rounded-square** morph inside, via `animateDpAsState` on `fillSize` 42dp↔28dp and `fillCorner` 40dp↔6dp) | `VoiceShutterButton` (same geometry; idle = mic glyph; recording = stop-square morph, same animation pattern as video) |
| Right | Lens flip `↔` | Lens flip `↔` (disabled while recording) | Elapsed-time readout (e.g. `0:12`) |

Center shutter is **always** 80dp with a 3dp ring and ~42dp of inner fill at idle — so muscle memory stays constant across modalities, only the glyph / colour / animation differs. Disabled during the ≤100ms capture window, during camera rebinds, and (for PHOTO shutter only) during active video/voice recordings in the other modalities. Disabled state dims to 50%.

All icons counter-rotate with device orientation via a spring animation on the shortest rotational arc. The left slot mirrors flash visual styling for the mic toggle: 48dp `IconButton`, 26dp icon, full-white when active, 65%-white when inactive (mic toggled off / flash off), 25%-white when disabled. Flash is not in VIDEO because video flash would need a torch-mode switch (different API path, mid-recording torch toggles are a reliability hazard); voice has no flash concept. Lens flip is not in VOICE because camera state is irrelevant to the mic session.

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

A full-screen black-at-82%-opacity scrim with 6 steps: **modality switch** (first — introduces the viewfinder swipe + pill), commit, discard, delete-one, reorder, divide. Shown steps are determined by filtering the full ordered list against `seenTutorialSteps: Set<String>` in DataStore — fresh installs see all six; upgraders from the pre-multimodal 5-step tutorial see only the `modality` step (v1 step IDs are seeded into the set at first read post-upgrade). The demo strip inside the overlay uses the real 72dp queue height and is pinned to the bottom with a 12dp nav-bar gap, so muscle memory transfers when the overlay dismisses. The scrim consumes every pointer event (the `awaitEachGesture` loop eats every unconsumed change) — do not poke holes, or users can accidentally swipe the real (empty) queue beneath. Re-triggerable from Settings (clears the set).

---

## Gesture mechanics in depth

### Capture

Capture is **modality-aware**: one tap per modality, but the tap semantics differ.

- **PHOTO** — one-shot tap. Gesture completes instantly; no threshold. Short haptic tick + optional shutter sound. New thumbnail appears at the right end of the queue; tray scrolls to make it visible. Shutter re-enables within ~50–200ms depending on device. The underlying write is async (see [Phase 1](#phase-1--capture-100ms-ui-thread-budget)), so the button is usable before the file is fully staged.
- **VIDEO** — tap-to-start, tap-to-stop. On start: `busy = Recording` (synchronously, before the coroutine suspends — prevents a double-tap race); the video-shutter's inner fill morphs from a 42dp red circle to a 28dp red rounded square via `animateDpAsState`; modality pill, lens-flip, and PHOTO-style queue gestures all disable; elapsed-time ticks in the normally-lens-flip slot. On stop: the `Recorder` finalizes (Media3 Muxer flushes MP4 moov atom), the poster frame + duration are read back via `MediaMetadataRetriever`, and a `StagedItem.Video` appends to the queue. A lifecycle pause (Activity backgrounded) routes through the same async stop path so the state machine stays consistent.
- **VOICE** — tap-to-start, tap-to-stop. On start: `MediaRecorder` prepares the `.m4a` file; the shutter glyph morphs (mic → stop-square); the waveform overlay swaps flat-centerline for scrolling amplitude bars. On stop: `MediaRecorder.stop()` finalizes, duration is probed, a `StagedItem.Voice` appends. First-tap ever prompts for `RECORD_AUDIO` permission; permanent denial surfaces an "Open Settings" banner.

Across all three modalities, the start action synchronously flips `BusyState` so a second tap that arrives before the coroutine schedules the recorder cannot start a second session.

### Switch modality

Two equivalent entry points; both dispatch to the same VM action `setModality(Modality)`:

- **Viewfinder swipe**: horizontal drag on the capture slab. The viewfinder and control row translate together as a single unit (`Modifier.graphicsLayer.translationX`). A `Modifier.draggable` on the slab root claims the gesture only after the platform's horizontal touch slop — tap-to-focus, pinch-to-zoom, and vertical pull-to-reorder on the queue still work.
- **Pill tap**: tapping a pill segment in the top bar.

**Spatial model**: `VIDEO ← PHOTO → VOICE` with no wrap. Swipe-left from VOICE or swipe-right from VIDEO has no motion (endpoint).

**Animation**: a two-stage sequence keyed on drag end.
- **Commit path** (threshold crossed): the slab continues sliding off in the drag direction using a `tween` for predictable visual speed, then snaps back to `translationX = 0` while the modality-driven content swap happens — the user sees a committed slide, not a bounce.
- **Cancel path** (below threshold): `DampingRatioNoBouncy` spring back to 0 with minimal initial velocity. No overshoot.

The spring-back animation runs in a `rememberCoroutineScope`-scoped coroutine (`swipeScope`), **not** the `Modifier.draggable` scope. `draggable`'s pointer-input scope is tied to composition and gets cancelled when `setModality()` triggers recomposition of the draggable's lambdas — a lesson from a visible "animation stops halfway" bug during Phase F playtesting.

**Pill indicator**: during drag, the indicator translates linearly with `dragProgress = dragX / slabWidth` clamped to `-1..1`, so the pill reads as a direct physical coupling with the slab.

**Gated off** when `state.busy in { Recording, Rebinding, Capturing }`. A `Modifier.systemGestureExclusion()` is applied on the bottom 24dp of the slab so Android's back-gesture doesn't eat left-swipe tails.

**Per-modality content swap is explicit, not implicit**. In VOICE the camera preview hides (alpha=0, layout preserved), flash and lens-flip controls hide entirely, and the waveform overlay replaces the preview. In VIDEO the flash button hides and the shutter morphs. This is driven by a `when (modality)` in `CaptureScreen`, not by a blanket modifier — each control decides its own visibility rule.

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

Each item gets a **global capture-order index** (3-digit, 001-based), not a per-modality index — the index reflects the item's position in the queue at commit time, so file listings sort into user-visible order regardless of modality.

- Raw photo: `{bundle-id}-p-{kkk}.jpg`.
- Raw video: `{bundle-id}-v-{kkk}.mp4`. A video poster-frame sidecar `{bundle-id}-v-{kkk}.mp4.thumb.jpg` is written next to the video so Bundle Preview can render a thumbnail without decoding the MP4.
- Raw voice: `{bundle-id}-a-{kkk}.m4a`. An analogous `{bundle-id}-a-{kkk}.m4a.thumb.jpg` is generated as a tinted-backdrop + mic-glyph JPEG at worker time.
- Stitched image: `{bundle-id}-stitch.jpg` (photo-only; not emitted for video-only or voice-only partitions).

Examples for a mixed bundle with photo → video → voice → photo in that order:
```
2026-04-14-s-0003-p-001.jpg
2026-04-14-s-0003-v-002.mp4
2026-04-14-s-0003-v-002.mp4.thumb.jpg
2026-04-14-s-0003-a-003.m4a
2026-04-14-s-0003-a-003.m4a.thumb.jpg
2026-04-14-s-0003-p-004.jpg
2026-04-14-s-0003-stitch.jpg   # composed from photos 001 + 004 only
```

The 3-digit index allows up to 999 items per bundle and keeps lexicographic sort correct past item 99 (2-digit width broke past 99 on the pre-multimodal implementation — fixed while routing was being touched).

### Folder structure on disk

```
{user-selected-root}/
├── bundles/
│   └── {bundle-id}/
│       ├── photos/
│       │   ├── {bundle-id}-p-001.jpg
│       │   ├── {bundle-id}-p-004.jpg
│       │   └── ...
│       ├── videos/
│       │   ├── {bundle-id}-v-002.mp4
│       │   ├── {bundle-id}-v-002.mp4.thumb.jpg
│       │   └── ...
│       └── audio/
│           ├── {bundle-id}-a-003.m4a
│           ├── {bundle-id}-a-003.m4a.thumb.jpg
│           └── ...
└── stitched/
    ├── {bundle-id-A}-stitch.jpg
    └── ...
```

Subfolders only exist if the bundle contains that modality — a photo-only bundle has only `bundles/{id}/photos/`. The stitch folder is always at the root.

`bundles/` and `stitched/` are created at folder-pick time (under the SAF tree on Android, under the security-scoped bookmark on iOS). Per-modality subfolders are created lazily by the worker when routing the first item of that type. If any directory is missing at commit time — user deleted it externally, cloud-sync hiccup — the worker recreates it, tolerating race conditions from concurrent creators.

### Legacy flat layout (pre-multimodal)

Bundles written by pre-multimodal versions used a flat layout: `bundles/{id}/{id}-p-01.jpg` directly under the bundle folder. Those bundles continue to render in Bundle Preview via a lazy two-format reader that prefers the nested `photos/` subdir and falls back to the flat layout when absent. **No migration runs**: SAF renames are slow and racy on some DocumentsProviders, and a half-migrated state is user-visible. Two-format read is ~4 lines of code and never risks data loss.

### Internal staging (NOT under the user folder)

Recon does **not** stage raw captures inside the user's output folder. Staging lives entirely in the app's **internal storage**, flat per-session (no modality-subfolder split at staging time — the modality is embedded in the in-memory `StagedItem.{Photo,Video,Voice}` sealed type and carried through in the `PendingBundle` manifest):

- Android: `filesDir/staging/session-{uuid}/{item-uuid}.{jpg|mp4|m4a}`.
- iOS: `FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]/staging/session-{uuid}/{item-uuid}.{jpg|mp4|m4a}`.

There are three reasons for this:

1. **Latency.** Writing every capture into a SAF/bookmark tree would mean an IPC round-trip per photo (Android) or security-scoped-resource dance per photo (iOS). Internal storage is a direct `FileOutputStream` — microseconds.
2. **User folder hygiene.** The user picks a folder to receive committed bundles, not a folder to receive in-flight raw captures. Staging the in-progress queue there would mean the user sees a moving "session-uuid" directory full of raw files until every bundle commits. Unacceptable.
3. **Modality-subfolder split is a commit-time concern.** A user reordering items, deleting one, or inserting a divider affects partitions — the routing decision is cheap to make at commit time from the manifest, expensive to re-do if we'd pre-committed a directory layout.

Committed bundles are **copied** from staging to the user folder by the background worker (see [Phase 3](#phase-3--worker-serial-off-thread-seconds-to-tens-of-seconds)). Rename/move tricks do not apply because internal storage and the user folder are on different filesystems (SAF is a content provider, not a filesystem; a bookmark points into a different sandbox). Copy is the only correct operation.

### Order-preservation log

Each staging session directory contains a hidden append-log: `session-{uuid}/.order` in format `{systemClockMs}\t{filename}\n`. It's appended synchronously when an item is added to the queue (for video/voice, at **start-of-recording** so a process kill mid-record still preserves the intended ordering).

The log exists because **`lastModified` timestamps are not a faithful queue order for mixed media**: a video's mtime is its *stop* time, not its *start* time, so a `[Video, Photo, Voice]` capture sequence would otherwise orphan-restore as `[Photo, Voice, Video]` (the photo finishes first, the voice finishes second, the video finishes last). The order log captures start-time and ensures restoration is faithful. `OrphanRecovery` prefers the log when present; falls back to `lastModified` for legacy sessions without it.

---

## Auto-captured metadata

**Photos only** carry EXIF. Video and voice files carry no Recon metadata — their bundle membership is path-based (the `bundles/{id}/videos/` and `bundles/{id}/audio/` subfolders).

Every photo's EXIF is stamped at capture time (Phase 1) and then finalised at commit time (Phase 3). Users never enter any of this.

| Field | When | Source | Notes |
|---|---|---|---|
| `DateTimeOriginal` + `DateTime` | Phase 1 | System clock, device local timezone | Per-photo timestamp, formatted `yyyy:MM:dd HH:mm:ss`. |
| `Make` / `Model` | Phase 1 | Device metadata | Standard. |
| `Orientation` | Phase 1 | Accelerometer-derived (see [Design decisions § Orientation](#design-decisions)) | EXIF orientation tag reflects physical device pose even with UI locked to portrait. |
| `GPSLatitude` / `GPSLongitude` etc. | Phase 1 (best-effort) + Phase 3 (backfill) | Fused location with 30s TTL cache | Stamped at Phase 1 if a cached fix is available; otherwise backfilled at Phase 3 after a bounded (2s) location refresh. First-capture without GPS is still delivered — just without coordinates. |
| `UserComment` | Phase 3 | Storage layout | Raw photos: `Recon:{bundle-id}:p{kkk}` (3-digit global index). Stitched: `Recon:{bundle-id}:stitch`. Preserves membership + sequence if files are moved or mixed. |

The `UserComment` is the product's worst-case-recovery mechanism **for photos**: even if folder structure is lost, photo membership and per-item order can be reconstructed from EXIF alone.

### Why video and voice skip metadata

- **Video rotation** is handled automatically: `VideoCapture.targetRotation` is frozen at `start()` by CameraX, so the finalised MP4 carries the rotation at start-of-recording — regardless of any mid-recording orientation change. No accelerometer branch-logic is needed.
- **Voice files have no orientation concept** and no audio-EXIF standard across players. An `.m4a` is its bytes; membership lives in the path.
- **Ingestion tools can reconstruct bundle membership** from the folder layout alone for non-photos. The photo-only `UserComment` is the belt; the path layout is the suspenders.

---

## The three-phase async pipeline

Capture → commit → process. Each phase has a hard role; the boundaries between them are the app's load-bearing correctness guarantees.

### Phase 1 — Capture (≤100ms, UI-thread budget; per-modality dispatch)

Per shutter tap, modality-dispatched:

**PHOTO (one-shot)**:
1. Camera delivers a JPEG buffer.
2. Write the JPEG to internal staging (`staging/session-{uuid}/{item-uuid}.jpg`).
3. Stamp capture-time EXIF on the file: DateTimeOriginal, orientation, make/model, and GPS if a cached fix (≤30s old) is available.
4. Decode a small thumbnail (~50KB in-memory bitmap) for the queue UI.
5. Append `StagedItem.Photo` to the queue state; append an entry to the session's `.order` log; shutter re-enables.

**VIDEO (tap-start)**:
1. Permission gate: if the user's mic toggle is on (default) and `RECORD_AUDIO` is denied, trigger the shared permission launcher (`micPermissionNeeded` — same gate as VOICE) and abort the start. On grant, retry from step 2.
2. Synchronously flip `BusyState.Recording` (prevents double-tap race), synchronously capture `videoStartRotation` from the device-orientation stream, append an `.order` log entry pointing at the not-yet-materialized output filename.
3. Allocate an output path in staging (`{UUID}.mp4`).
4. Build `FileOutputOptions` + `Recorder.prepareRecording(ctx, outputOptions)`. Call `.withAudioEnabled()` when both the user toggle is on **and** `RECORD_AUDIO` is granted; otherwise skip it and record silently. Then `.start(main, listener)`. Audio mode is frozen at `start()`, so a mid-record toggle has no effect (`onToggleVideoAudio` ignores the input while busy).
5. Start an optional coroutine-timer enforcing `maxLengthSec` (setting).
6. On tap-stop: `activeRecording.stop()`, await `VideoRecordEvent.Finalize`, read `recordedDurationNanos`, extract poster frame at t=0 via `MediaMetadataRetriever`, push `StagedItem.Video` (with rotation + duration) onto the queue.

**VOICE (tap-start)**:
1. Synchronously flip `BusyState.Recording`. Permission check: `RECORD_AUDIO` — if denied, trigger the permission launcher (first ask) or the "Open Settings" banner (permanently denied).
2. Allocate an output path in staging (`{UUID}.m4a`), append `.order` log entry.
3. Configure `MediaRecorder`: `AudioSource.MIC` + `OutputFormat.MPEG_4` + `AudioEncoder.AAC` + 96kbps + 48kHz + 1 channel; `setOutputFile(...)`; `prepare()`; `start()`.
4. The `VoiceController.amplitudeFlow` starts polling `getMaxAmplitude()` every ~33ms → drives the waveform overlay.
5. On tap-stop: `MediaRecorder.stop()` + `release()`, probe duration via `MediaMetadataRetriever`, push `StagedItem.Voice` (with duration).

Across all modalities:
- In-memory footprint per queued item is **just the thumbnail** (50KB for photos, 8×8 placeholder tile + mic glyph overlay for voice, poster-frame bitmap for video). A mixed 50-item queue is still in the single-MB range.
- Raw files are **durable on disk** from the moment the capture completes. If the process dies between start and stop for a video/voice recording, the file is left in a torn state — Phase 3 / `OrphanRecovery` probes `MediaMetadataRetriever.extractMetadata(DURATION)` to detect this and deletes torn files before restoring the queue.

If the process dies here (before commit), staging + `.order` log persist — see [Resilience](#resilience).

### Phase 2 — Commit (single frame, UI thread)

The swipe-commit gesture must re-enable the shutter by the next frame. It does so by splitting Phase 2 into a **synchronous UI pivot** and a **deferred background step**:

**Synchronous (on the UI thread):**
- Snapshot the queue's ordered list of staging paths + the current set of dividers.
- Null the `currentSession` reference; clear `queue` and `dividers` in UI state.
- Shutter is now usable. This happens in one frame.

**Deferred (coroutine, off UI thread):**
- Partition the snapshot by dividers into one or more segments. Each segment becomes its own bundle.
- For each segment, atomically allocate the next bundle ID from persistent storage (the daily counter).
- Build a `PendingBundle` manifest per segment — JSON-serializable record of: `version: Int = 2`, bundle ID, root URI, stitch quality, session ID, ordered item list (`List<PendingItem>` — sealed polymorphic with `PendingPhoto` + `PendingVideo` + `PendingVoice` variants, `classDiscriminator = "type"`, `encodeDefaults = true` so the `version` field is always persisted), capture timestamp, `saveIndividualPhotos` flag, `saveStitchedImage` flag. Flags are **frozen at commit time** so a settings change mid-flight doesn't change what an already-queued worker produces.
- Pre-flight total item size: `File.length()` summed across all items (video files can be many MB) and sanity-check against SAF target space when queryable. Abort the commit with a banner if insufficient — don't burn a bundle ID on a guaranteed-to-fail copy.
- Write all manifests to internal storage (`pending/{bundle-id}.json`). Write them **all before enqueuing any worker** — a mid-loop crash leaves no enqueued workers on partial state.
- Enqueue one worker per manifest to the platform's background scheduler (WorkManager on Android, OperationQueue + BGProcessingTask on iOS). Only the `bundle-id` goes through the scheduler's input data; the worker loads the rest from the manifest file. This sidesteps Android's 10KB `Data` limit (a 50-item mixed bundle's paths easily exceed it) and makes orphan recovery trivial.
- Emit a `BundlesCommitted({bundleIds})` event → `BundleSavedShimmer` displays the saved pill.

If the process dies **between the UI pivot and the manifest write**, the staging session still exists on internal storage; `OrphanRecovery` at next launch restores it as an uncommitted queue.

### Manifest polymorphism (v1 → v2)

Pre-multimodal manifests used a `PendingPhoto`-only `orderedPhotos` field with no `version` tag. Post-multimodal manifests declare `version: 2`, an `orderedItems: List<PendingItem>` sealed polymorphic field, and individual items tag themselves with `"type": "photo" | "video" | "voice"`.

`ManifestStore.load` branches on the `version` field:
- No field or `version == 1` → decode the legacy shape, wrap each `orderedPhotos` entry as a `PendingItem.Photo` in memory (compatibility shim; no on-disk rewrite).
- `version == 2` → decode the polymorphic shape directly.
- Unknown version → log + return null (fail loudly; never silently mis-decode).

This preserves every in-flight manifest across the multimodal upgrade.

### Phase 3 — Worker (serial, off-thread, seconds to tens of seconds; type-routed)

One worker per bundle manifest, wrapped in a **foreground service** (Android) / `beginBackgroundTask` (iOS) so the OS grants time to finish. A **process-wide mutex** serialises all bundle workers — stitching a tall image allocates ~60% of heap, and two in parallel reliably OOMs mid-range devices.

Per worker:

1. Load `pending/{bundle-id}.json` (branched decode by version, see above).
2. **Plan routing** via a pure `planRouting(manifest)` companion that partitions `orderedItems` into `photos / videos / voices / shouldStitch`. Kept pure so tests (`BundleWorkerRoutingTest`) cover the dispatch without a WorkManager runtime.
3. **Photos** (if present and `saveIndividualPhotos`):
    - Refresh location (bounded 2s). Backfill GPS EXIF on any staged photo missing it. Stamp `UserComment = "Recon:{bundle-id}:p{kkk}"`. Both in one open/save pass per file (avoids paying the ExifInterface open-cost twice).
    - Copy each staged JPEG to `bundles/{bundle-id}/photos/{bundle-id}-p-{kkk}.jpg` via SAF/bookmark. Overwrite on name collision so retries don't produce `" (1).jpg"` duplicates.
4. **Videos** (if present and `saveIndividualPhotos`):
    - Copy each staged MP4 to `bundles/{bundle-id}/videos/{bundle-id}-v-{kkk}.mp4`. No EXIF pass — rotation is already embedded in the MP4 by CameraX.
    - Extract poster frame via `MediaMetadataRetriever.getFrameAtTime(0)`, scale to ~48dp square, save as `{same-basename}.mp4.thumb.jpg` alongside.
5. **Voices** (if present and `saveIndividualPhotos`):
    - Copy each staged `.m4a` to `bundles/{bundle-id}/audio/{bundle-id}-a-{kkk}.m4a`.
    - Generate a static voice thumbnail (tinted navy backdrop + hand-drawn mic glyph, `Paint`/`Canvas`) and save as `{same-basename}.m4a.thumb.jpg`. Generated once at worker time rather than lazily at Bundle Preview read time for cheap read-back.
6. **Stitch** (if `saveStitchedImage` AND `shouldStitch` from routing, i.e. photos.isNotEmpty()):
    - Compute stitch layout (see [Stitcher](#stitcher)) from the raw source photos only — videos and voices don't participate.
    - Render to a canvas, compress JPEG at the quality tier, stamp `UserComment = "Recon:{bundle-id}:stitch"`.
    - Copy to `stitched/{bundle-id}-stitch.jpg` via SAF/bookmark.
    - **Skip** when the partition has zero photos — a video-only or voice-only bundle never emits an empty stitch file.
7. Delete the manifest file.
8. If no other pending manifests reference the session, delete the staging session directory (including its `.order` log).

The global index `{kkk}` is derived from each item's **position in `manifest.orderedItems`**, not a per-modality counter — so a `[Photo, Video, Voice, Photo]` sequence files as `-p-001, -v-002, -a-003, -p-004`.

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
- **Videos survive mid-record process kill by default**, thanks to CameraX 1.6.0's Media3 Muxer: the MP4 is kept playable (moov atom written incrementally) through termination. `OrphanRecovery` probes `.mp4` files via `MediaMetadataRetriever.extractMetadata(DURATION)` — non-null → restore into queue with computed duration + extracted poster frame; null → torn file, delete and skip. The narrow window between start-of-recording and the first muxer flush still produces a torn file; the probe catches it.
- **Voice files torn by process kill are detected and cleaned**. `MediaRecorder` does not have video's partial-file resilience — a killed `.m4a` is unplayable. Same `MediaMetadataRetriever` probe distinguishes: null duration → delete, valid → restore as `RestoredItem.Voice`. No sidecar marker files — the probe is authoritative.
- **Mixed-media queue order is preserved via the `.order` log**. Start-time entries are appended on every item add (including video/voice at start-of-recording), so a `[Video, Photo, Voice]` capture restores as `[Video, Photo, Voice]` — not the `[Photo, Voice, Video]` that `lastModified` ordering would produce. Falls back to `lastModified` for legacy sessions without a log.
- **Bundle-ID counter is atomic**: allocated from persistent storage before any output I/O. Worker failure doesn't rewind it. Retries reuse the same ID.
- **Worker failures are observable without noise**: the failure flow filters pre-existing historical failures on first subscription (the "acknowledged set"). Without this, every app launch would re-surface yesterday's transient failure.
- **Discard marker is synchronous**: when the user swipes-discard, a `.discarded` marker file is written to the staging session directory **synchronously** on the UI thread (one `File.createNewFile()`, ~1ms, Main-thread-safe) **before** the 3-second undo timer starts. If the process dies inside the undo window, the coroutine that would've cleaned up gets cancelled — but the marker is already on disk. `OrphanRecovery` sees the marker on next launch and deletes the session (instead of restoring it as a zombie queue). Undo removes the marker.
- **Backpressure is natural**: the worker queue drains at its own pace. If the user commits faster than stitching, bundles pile up and catch up during a pause. Capture latency never depends on worker depth.
- **Memory stays flat**: media is on disk, not in RAM. The worker holds at most one bundle's decoded pixels at a time.
- **Lifecycle pause stops active recordings**: `ON_PAUSE` triggers `stopActiveRecordingSync()` (video) and `voiceController.stopRecording()` (voice) through the normal async stop paths, so the VM's `BusyState` resolves correctly to `Idle` when the Activity returns. No foreground service required — recording only runs while the Activity is foregrounded.

---

## Settings

All persisted in a key-value store (DataStore on Android, `UserDefaults` / property-list file on iOS). Defaults are chosen so a first-run user has a functional setup after only picking a folder.

| Setting | Default | Meaning |
|---|---|---|
| **Output folder** | _unset_ (blocks capture until chosen) | The user-picked SAF tree (Android) or security-scoped bookmark (iOS). Shows the path; "Change" button re-triggers the picker. |
| **Bundle output: Individual files in subfolder** | On | Whether a commit writes raw items into `bundles/{bundle-id}/{photos,videos,audio}/`. Affects all three modalities uniformly. |
| **Bundle output: Vertical stitched image** | On | Whether a commit writes the composed JPEG into `stitched/`. Photo-only — does not affect videos/voice. |
| **Stitch Quality** | Standard | Low / Standard / High. Affects only the stitched image (width ceiling + JPEG quality per [Stitcher](#stitcher)). Disabled when the stitched toggle is off; preference is preserved. |
| **Camera mode** | ZSL | `ZSL` / `EXT`. Live-applied preference (applied at every camera bind, not frozen into a manifest). Demoted here from the old top-bar pill to make room for the modality pill. |
| **Video quality** | Standard | `Low` / `Standard` / `High`. Maps to a `QualitySelector` on the `Recorder`. |
| **Max video length** | Unlimited | `Unlimited / 15s / 30s / 60s / 300s`. When set, the recording coroutine enforces the cap by calling `activeRecording.stop()`. |
| **Shutter sound** | On | Whether to play the system shutter-click sound on photo capture. Video/voice have their own haptic start/stop feedback, unaffected by this. |
| **Confirm before deleting a bundle** | On | If off, swiping a bundle row in Bundle Preview goes straight to pending-delete (or hard delete if undo window = 0s). |
| **Undo window for bundle deletion** | 5s | Off (0) / 1s / 2s / … / 10s. Controls the countdown shown on a pending-delete row. `Off` removes the pending state entirely — confirmed deletes go through immediately with no undo affordance. |
| **Gesture tutorial** | _(action)_ | "Show" button clears `seenTutorialSteps: Set<String>` and pops back to capture so the overlay re-appears. |
| **App version** | _info only_ | Read-only. |

**Output invariant**: at least one of Individual Files / Stitched must be on. The UI locks whichever switch is the sole-on, showing an inline caption ("At least one output is required.") only while the lock is active. The settings store sanitises a `(false, false)` read (external edit, restore from backup) back to `(true, true)` at read time — a belt-and-suspenders guarantee that a corrupt pair can never silently drop bundles. Note: a voice-only or video-only partition with "Stitched" as the sole-on output still lands on disk (the Individual-Files copy branch runs anyway to avoid dropping the non-photo items) — the sole-on invariant is about guaranteeing an output exists, not about forcing stitch.

**Tutorial-step persistence**: the old boolean `seenGestureTutorial` has been generalised to `seenTutorialSteps: Set<String>` so new tutorial steps added in future versions don't re-surface already-seen ones. On first read post-multimodal upgrade, the old boolean's value is read once: `true` seeds the set with the v1 step IDs (`commit`, `discard`, `deleteOne`, `reorder`, `divide`) so upgraders only see the newly-added `modality` step; `false` or missing seeds an empty set. The old key is thereafter ignored.

Permissions requested on-demand: `CAMERA` on capture-screen mount; `ACCESS_FINE_LOCATION` after camera permission resolves (non-fatal); `RECORD_AUDIO` on first-ever VOICE shutter tap.

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

On first entry to the capture screen, a full-screen overlay scrims the capture UI and walks the user through the gestures they haven't yet seen. The full ordered list is **six steps**:

1. **Modality switch** (new in multimodal) — swipe-to-change-mode + top-bar pill.
2. **Commit** — drag the left handle rightward.
3. **Discard** — drag the right handle leftward.
4. **Delete one** — swipe a thumbnail down.
5. **Reorder** — long-press a thumbnail and drag.
6. **Divide** — swipe down on the gap between thumbs.

Each step renders a looping animated-finger demonstration over a fake 4-thumbnail demo queue. Text sits mid-screen; pager dots + Next button below; demo strip pinned to the bottom 72dp (same location and size as the real queue strip) so muscle memory transfers on dismiss.

- "Skip" (top-right) or "Got it" (last step) both union all step IDs into `seenTutorialSteps` and dismiss.
- Shown steps are filtered against the persisted `seenTutorialSteps: Set<String>` at mount time: a fresh install sees all six; an upgrader from pre-multimodal sees only `modality` (the other five were seeded from the old `seenGestureTutorial == true` boolean). Future step additions will similarly only show the delta.
- The scrim consumes every pointer event — users cannot accidentally fire real gestures on the (empty) queue beneath.
- Re-triggerable anytime from Settings → "Gesture tutorial → Show".

Each step's demo sits in the actual on-screen region of its gesture. The five queue-strip demos (commit/discard/delete-one/reorder/divide) render in the bottom 72dp strip; the `modality` demo renders in the 3:4 preview area (upper slab) with a mini `VIDEO · PHOTO · VOICE` pill at the top, a slab that translates left with a finger-sweep while a video-tinted ghost slides in from the right, and the pill indicator tracking the drag — overlaying the exact space the user will swipe on the real screen after dismissal.

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
    - Monospace bundle ID + mixed-modality subtitle derived from per-modality counts: e.g. `"3 photos, 1 video, 2 voices"`, `"2 videos"`, `"1 photo"`. Counts of zero are omitted; pluralisation follows standard rules.
    - Modality icons on the right: `PhotoLibrary` outline when the raw subfolder contains photos, `VideoLibrary` outline for videos, `Mic` outline for voice, `ViewStream` outline when the stitched image exists. Each icon shows its per-modality count as a badge.
    - Thumbnails use a priority-chain fallback: photo raw subfolder → video poster frames (from the `.thumb.jpg` sidecars written by the worker) → voice glyph tiles (hand-drawn mic-on-navy) → stitched image as final fallback. Photo thumbnails are preferred because they crop cleanly in the 128dp strip.
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

## LocalSend bundle transfer

Committed bundles can be transferred peer-to-peer over the LAN to any device running the [LocalSend](https://github.com/localsend/protocol) protocol (v2.1) — a desktop, another phone, or a dedicated companion script. Recon implements the **sender** side of the protocol; receiving is out of scope. The user-visible affordance lives in the Bundle Preview screen.

### Selection model

A row is the atomic unit. There is no file-level selection; you pick whole bundles. Selection is gesture-driven:

- **Swipe right** on a normal row past ~40% of the 80dp reveal width (or with a fast right flick) → row snap-translates to the right by 80dp and stays there. The exposed leading strip is solid green with a check-circle icon. The row's resting offset *is* its selected state — there is no separate tint.
- **Swipe back** (drag the selected row left toward 0) **or tap the green check** → row animates back to 0, deselected.
- **Swipe left** on an unselected row → standard swipe-to-delete (unchanged).
- A selected row's gesture cone is clamped to `[0, revealWidth]`: a selected row can't be deleted directly. Deselect first, then swipe left.
- A processing row (worker still in flight) skips the gesture handler entirely. The bundle's worker is writing files; selection or delete during that window would race the writes.

The contextual top app bar swaps in when ≥1 bundle is selected: close-X on the left, "N selected" as the title, paper-plane Send action on the right. Tapping close (or system back) clears the selection and animates every selected row back to 0 simultaneously. A dedicated suppression flag prevents a follow-through finger motion right after the X tap from re-selecting a row mid-animation.

### Discovery

Tapping Send opens a modal bottom sheet. On open, the sheet starts UDP multicast discovery on `224.0.0.167:53317`: it acquires `WifiManager.MulticastLock` (without it, Android filters inbound multicast packets to silence on most devices), joins the group on every non-loopback IPv4 NIC, and emits one announce. Peers respond either via multicast UDP (`announce: false`) or via HTTP `POST /api/localsend/v2/register` to the announcer's IP. Recon does **not** run an inbound HTTP server — the protocol allows that, and most peers will fall back to multicast UDP responses.

The sheet shows a spinner while the peer list is empty. After 12 seconds of nothing, the sheet swaps to a "No peers found" message with `Try again` (drains the discovery session, clears the peer list, starts fresh) and `Close` buttons. Discovered peers appear as rows showing the peer's alias, deviceModel, and the first 8 hex chars of their fingerprint (a trust-on-first-use cue).

### Single session for all selected bundles

Tapping a peer kicks off the transfer. **All selected bundles ship in one prepare-upload session.** This is non-negotiable, not a convenience: LocalSend's receiver state machine treats a session as active until the user dismisses the receive panel on their desktop, *not* until the last file lands. Two sequential `prepare-upload` calls to the same peer would 409 BLOCKED on the second until the user manually dismisses the first transfer's panel. Bundling collapses that into one user-visible transfer.

The wire path for each file is `{bundleId}/{subfolder}/{leaf}` (or `{bundleId}/{leaf}` for legacy flat-layout bundles). The `subfolder` is `"photos"` / `"videos"` / `"audio"` for raw modality files, `"stitched"` for the composite. LocalSend's receiver parses `/` separators verbatim and creates the matching directory structure under its save root, with `..` traversal blocked. So a 2-bundle send produces:

```
{receiver-save-root}/
  {bundle-A-id}/
    photos/   {bundle-A-id}-p-001.jpg ...
    videos/   {bundle-A-id}-v-001.mp4 ...
    audio/    {bundle-A-id}-a-001.m4a ...
    stitched/ {bundle-A-id}-stitch.jpg
  {bundle-B-id}/
    ...
```

mirroring Recon's own SAF layout, one self-contained directory per bundle.

### Send completion

Once the receiver acknowledges the last file, the sheet swaps from the progress view to a success state. The user already chose the recipient on the previous screen — the question they're verifying here is "did everything go?", not "where did it go?" — so the headline is the *outcome* (`{N} bundle(s) ({F} files, {Y.Y MB})`), with the recipient and the device identity (`{deviceModel} · {fingerprint[0..8]}`, mirroring the format used in the discovery row) as supporting subtitle lines underneath. Total file count and total byte size are pulled from the last `SendProgress` event the sheet observed — no extra walk of `BundleLibrary.listBundleFiles`. Byte sizes use base-1000 (KB / MB / GB) with one decimal of precision, formatted via `Locale.US` so the decimal separator is always `.`. The single primary action is a full-width Filled-tonal "Done" — Material's medium-emphasis button, the right weight for "the only CTA on a confirmation sheet" without screaming. `AlreadyReceived` and `Failed` states use the same icon-led layout but without the count headline; their headlines are "Already received" and "Send failed" respectively, with the recipient identity still shown so the user knows which peer responded.

### Trust model

Peers identify themselves by SHA-256 fingerprint of their leaf TLS cert, advertised in the discovery announce. Recon generates one self-signed RSA cert per install (cached on disk, lazily on first use), and the same hex hash goes into our own announces.

There is no CA validation. The trust manager is fingerprint-pinned — during the TLS handshake, `checkServerTrusted` computes the leaf cert's SHA-256 and compares to the announced fingerprint. Match → return → session authenticates. Mismatch → throw `CertificateException` → handshake fails fast. Doing this *during* the handshake (rather than post-handshake from an interceptor reading `peerCertificates`) is required: an Android Conscrypt session whose trust manager is a no-op is left unverified, which makes `SSLSession.getPeerCertificates()` throw and OkHttp's `Handshake.get` swallow it into an empty list.

Each peer-specific OkHttpClient is built per `send` call via `baseClient.newBuilder().sslSocketFactory(...)` so the connection pool / dispatcher / timeouts stay shared while the trust manager carries the right peer's expected fingerprint.

### Transfer semantics

The sender-side flow is:

1. `POST /api/localsend/v2/prepare-upload` with `{info, files: { wireName: { id, fileName, size, fileType } }}`. The receiver shows a confirmation prompt; on accept, replies with `{sessionId, files: { wireName: token }}`.
2. If any requested file is missing from the response's tokens, sender posts `/cancel` and fails the whole send with a clear error — silently shipping the rest would be partial-data masquerading as success.
3. Per file, `POST /api/localsend/v2/upload?sessionId&fileId&token` with the raw bytes streamed in 64 KB chunks. Up to 3 files in flight concurrently. The body reads via `contentResolver.openInputStream(uri)` (never `readBytes()` on a 20 MB stitched JPEG) and polls the calling Job's `isActive` between chunks so cancellation aborts at the next chunk boundary.
4. On any error or coroutine cancellation, sender posts `/cancel` best-effort.

Progress is reported as session-level totals (files completed / total, bytes sent / total) plus the wire path of the most recently active file. Chunk callbacks fire ~120 events/s peak across the parallel uploads, so progress emissions are throttled to 20 Hz; lifecycle moments (start, file completion, terminal state) force-emit so the count never lags the visual.

### Failure modes

- **No peers found** after 12s: Wi-Fi network blocking multicast (hotel / carrier / AP isolation) or the receiver isn't listening. Surface "No peers found" + Try again.
- **409 BLOCKED on prepare-upload**: receiver still has the previous transfer's panel open. Surface "Receiver still has the previous transfer open. Tap Done on the receiving device, then retry." — the only actionable cause in the single-session model.
- **403 rejected**: receiver explicitly declined.
- **204 already received**: receiver had every file already (sha256 dedup, if both ends opt in). UI says "Peer already had this content."
- **Mid-transfer drop**: sender posts `/cancel` and fails with "Upload failed: …".

### Sender-only by design

We don't run an inbound HTTP server. Two consequences worth flagging:

- A peer that *only* responds to multicast announces via HTTP `/register` (rather than UDP multicast reply) won't appear in our peer list. Most LocalSend implementations send both, so in practice this is rare.
- Future flows like the BACKLOG OCR-companion's "send the .md back" can't be pure-LocalSend without inbound. They'd require either embedding a small HTTP server or an out-of-band channel (Syncthing, an mDNS service, etc.).

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

18. **CameraX 1.6.0 for Media3-Muxer video resilience.** A mid-record process kill on pre-1.6 video pipelines leaves an unplayable MP4; 1.6.0's Media3 Muxer keeps the moov atom flushable incrementally, so killed recordings are recoverable in most cases. The `OrphanRecovery` probe (`MediaMetadataRetriever.extractMetadata(DURATION) != null`) distinguishes playable from torn and deletes torn files, so the worst case is "lose the in-flight clip" rather than "corrupt the user's bundle".

19. **No foreground service type for mic / camera.** Recording only runs while the capture Activity is foregrounded; `ON_PAUSE` routes through the normal async stop paths to finalize the in-flight recording and reset `BusyState` to `Idle`. `FOREGROUND_SERVICE_TYPE_MICROPHONE` is declared nowhere — it's only required for *background* recording, which we deliberately don't support (scope discipline).

20. **Per-modality subfolders, not a flat mixed bundle folder.** `bundles/{id}/{photos,videos,audio}/` beats `bundles/{id}/` with mixed extensions because (a) file managers render homogeneous folders better than heterogeneous ones, (b) an ingestion tool that wants photos-only can `ls bundles/{id}/photos/` without extension-filtering, (c) subfolders are created lazily, so a photo-only bundle is still a single-subfolder tree. The **3-digit global capture-order index** across all modalities means files still sort chronologically inside a single mixed listing (e.g. `ls bundles/{id}/**/*` in your shell).

21. **Sibling shutter composables, not one parameterised shutter.** `ShutterButton` (photo) stayed 44 lines of Kotlin and unchanged. `VideoShutterButton` and `VoiceShutterButton` are separate composables with the same 80dp geometry. Attempting to fold all three into one parameterised shutter would have bolted progress-arc animation, red pulse, and glyph morph onto a stateless component — siblings share no state, swap is a `when (modality)` in one place in `CaptureScreen`.

22. **Concurrent-bind fallback for hardware level FULL.** Most devices support `Preview + ImageCapture + VideoCapture` bound simultaneously (CameraX 1.5.1+ guarantees the combined bind on hardware level LIMITED and better, which covers nearly all modern phones). On hardware level FULL or lower where `bindToLifecycle` throws `IllegalArgumentException`, `CaptureController` falls back to modality-specific binds (Preview + ImageCapture for PHOTO, Preview + VideoCapture for VIDEO), rebinding on PHOTO↔VIDEO switches. VOICE never rebinds (camera state is irrelevant to the mic).

23. **Start-of-recording order-log entries, not stop-time.** `lastModified` timestamps reflect stop time for video/voice — so a `[Video, Photo, Voice]` capture sequence would orphan-restore as `[Photo, Voice, Video]` (photos finish first, the long video finishes last). The `.order` log captures start-time, so the restored queue matches the intent. Start-time also means a process kill mid-record preserves the intended ordering for any restored items even if the killed one is dropped.

24. **Swipe-slab animation lives in a `rememberCoroutineScope`, not the draggable's internal scope.** `Modifier.draggable`'s `onDragStopped` scope is tied to the pointer-input coroutine that lives inside the modifier — which Compose restarts when the modifier's lambda recomposes (and it recomposes because the lambda closes over state that changes when `setModality()` flips `modality`). The spring-back animation thus got cancelled mid-flight. Running it in a composition-stable `swipeScope` fixes the stop-halfway bug and is the correct pattern for any animation that outlives a gesture.

25. **Two-stage swipe animation, not a bouncy spring.** A spring with initial velocity overshoots and oscillates, which reads as "bounce back and forth" to the user. The commit path instead slides off in the drag direction with a predictable `tween`, then snaps back to 0 as the modality-swapped content arrives — the user sees a decisive slide, not a bouncy settle. The cancel path uses `DampingRatioNoBouncy` for a clean springback.

26. **Per-modality explicit visibility, not a blanket layout switch.** When the user is in VOICE, `CameraPreview`'s alpha goes to 0 (layout preserved to avoid thrashing on switch-back), the flash button and lens-flip button are `if (modality != VOICE)` removed, and the `ZoomControl` alpha goes to 0 (layout preserved similarly). Each control decides its own visibility rule. This is more verbose than a single "hide everything camera-related" modifier, but it (a) makes each rule reviewable in one place, (b) lets VIDEO keep zoom and lens-flip but drop flash, (c) surfaces non-obvious cases during review (the zoom chips *layout* is preserved in VOICE so the viewfinder doesn't shuffle vertically on switch back).

27. **Single LocalSend session for all selected bundles, not one session per bundle.** LocalSend's receiver clears its session only on user dismissal of the receive panel (or explicit `/cancel`), not on file-count completion. Sequential per-bundle sessions to the same peer thus 409 BLOCKED until the user manually dismisses each. Collapsing N bundles into one prepare-upload eliminates the gate entirely — the user sees one "Receiving files" panel listing all files and clicks Done once. The wire fileName carries `{bundleId}/{subfolder}/{leaf}` so the receiver materializes one folder per bundle under its save root.

28. **Fingerprint-pinning during the TLS handshake, not in a post-handshake interceptor.** A no-op trust manager + an OkHttp interceptor reading `response.handshake.peerCertificates` was the obvious design — but Conscrypt on Android marks the SSLSession as unverified when the trust manager doesn't actually validate, which makes `SSLSession.getPeerCertificates()` throw and OkHttp's `Handshake.get` swallow it into an empty cert list. The interceptor then has nothing to verify. Moving the SHA-256 check inside `X509ExtendedTrustManager.checkServerTrusted` solves it: match → return → session is authenticated → everything downstream works. The trust manager extends `X509ExtendedTrustManager` directly (not just `X509TrustManager`) so the JDK doesn't wrap us in `AbstractTrustManagerWrapper` which adds its own hostname-check incompatible with our per-install cert's CN.

29. **Snap-position selection on bundle rows, not tap-to-toggle.** A swipe-right on a row snap-translates to a fixed 80dp reveal width and stays there. The row's resting offset *is* its selected state — there is no separate tint. Tap on the green check zone deselects; swipe-back to 0 deselects. No long-press, no tap-anywhere-to-toggle. The reasoning: an explicit, reversible gesture per state change reads better than an ephemeral tap, and a snap-position model gives an obvious target for the "tap the check to undo" affordance. Swipe-left to delete coexists in the same `pointerInput` block, signed-offset clamped per state (selected rows clamp at `[0, revealWidth]` so they can only be deselected, not deleted).

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

15. **Sealed polymorphic manifest types with explicit `version` field.** The manifest is `sealed class PendingItem` (Photo / Video / Voice) with a kotlinx.serialization `classDiscriminator = "type"` + `version: Int = 2` on the outer `PendingBundle`. **`encodeDefaults = true` is critical** so the version field actually persists. The loader branches on `version`; unknown versions return null (fail loudly). This is the backwards-compatible pattern that earlier drafts tried to achieve via discriminator defaults — the explicit version field is simpler and fails correctly on malformed or downgrade-era manifests.

16. **Capture-order log for mixed-media restoration.** An append-log in each staging session with `{ms}\t{filename}\n` rows, written synchronously at item-add time (for video/voice, at start-of-recording). `OrphanRecovery` prefers the log when present and falls back to `lastModified` for legacy sessions. Without this, mixed captures orphan-restore in the wrong order because `lastModified` reflects stop-time for recordings.

17. **Torn-file probe via media duration, not sidecar markers.** Earlier drafts considered a `.recording` / `.done` sidecar pair per recording. `MediaMetadataRetriever.extractMetadata(DURATION)` distinguishes playable from torn files, so the sidecar adds no information — skipping the sidecar pattern saves ~40 lines and one class of state-machine bugs.

18. **Global capture-order index, not per-modality indices.** File names use a single 3-digit counter (`-p-001, -v-002, -a-003, -p-004`) derived from the item's position in `manifest.orderedItems`. Per-modality counters (`-p-001, -v-001, -a-001, -p-002`) would have drifted index meaning on delete/reorder and broken the visual-sort-by-filename property inside a mixed listing.

---

## iOS build notes

This section is the SOTA target for an iOS port, informed by the Android reference implementation. Assume minimum iOS 16; use Swift + SwiftUI + modern Swift Concurrency.

### Camera (photo + video)

- `AVFoundation` with a custom `AVCaptureSession` running on a dedicated serial queue.
- Preview via `AVCaptureVideoPreviewLayer` (wrapped in a `UIViewRepresentable` for SwiftUI embedding). Aspect ratio 3:4 by constraining the layer bounds; use `videoGravity = .resizeAspect`.
- **Photo capture** via `AVCapturePhotoOutput`. Equivalent of Android's two "camera modes":
    - **ZSL**: `AVCapturePhotoOutput.isResponsiveCaptureEnabled = true` + `isZeroShutterLagEnabled = true` (iOS 17+).
    - **Quality**: `AVCapturePhotoSettings.photoQualityPrioritization = .quality`. No direct "HDR/auto" equivalent to CameraX Extensions; rely on `AVCapturePhotoSettings.isAutoRedEyeReductionEnabled` / `isAutoStillImageStabilizationEnabled` per device capability.
- **Video capture** via `AVCaptureMovieFileOutput` (the high-level path; `AVCaptureVideoDataOutput` is a level deeper and only needed for custom frame processing which Recon does not do). Both outputs can be attached to the same session; AVFoundation handles the multiplexing. Mirror Android's audio-on-by-default behaviour: attach an `AVCaptureDeviceInput` for the built-in mic when starting a clip, **but** drive it from the same user-facing toggle that Android exposes (mic button left of the video shutter). When the toggle is off (or `AVAudioSession`/permission denial blocks the input), drop the audio input from the session before `startRecording(to:recordingDelegate:)` so the clip records silent. The modality switcher disables during recording (matching Android), so VIDEO and VOICE never compete for `AVAudioSession`. Set `movieFragmentInterval` to a small value (e.g. `CMTime(seconds: 1, preferredTimescale: 600)`) so the `.mov`/`.mp4` is incrementally flushed — this is iOS's analogue of Android's Media3-Muxer resilience, giving a playable file even if the app is backgrounded or killed mid-record.
- **Video output format**: output `.mov` then wrap / re-mux to `.mp4` container (Recon's on-disk format is `.mp4`). Alternatively, target `.mp4` directly via `AVAssetWriter` — more code but avoids a re-encode / re-mux step. Use a `QualitySelector`-equivalent preset from the settings-driven `AVCaptureSession.Preset.{hd1280x720, hd1920x1080, hd4k3840x2160}` mapped from the `Low/Standard/High` setting.
- **Max video length**: enforce in a dispatched `DispatchWorkItem` scheduled for `Date() + maxLengthSec` at start-of-recording; `stopRecording()` on fire. Cancel the work item on manual stop.
- Lens flip: set `AVCaptureDevice(discovering: ...)` for back/front via `AVCaptureDevice.DiscoverySession`; reconfigure the session on a background queue, holding a session-lock equivalent of Android's `bindMutex`. Disabled mid-video-recording.
- Flash: `AVCapturePhotoSettings.flashMode = .off / .auto / .on`, applied per-capture. To mirror Android's "flash mode survives rebinds": store `currentFlashMode` as instance state and apply it at every `AVCapturePhotoSettings` instantiation. **Not offered in VIDEO** modality (iOS torch-mode toggles mid-video are a reliability hazard, mirroring the Android decision).
- Zoom: `AVCaptureDevice.videoZoomFactor` with `ramp(toVideoZoomFactor:withRate:)` for smooth chip-to-chip transitions; pinch gesture multiplies `videoZoomFactor`. Hardware-filter chip presets against `device.activeFormat.videoMinZoomFactorForDepthDataDelivery` / `device.maxAvailableVideoZoomFactor`. Available in PHOTO + VIDEO; hidden (with layout preserved) in VOICE.
- Tap-to-focus: on preview tap, translate the tap into device coordinates via `previewLayer.captureDevicePointConverted(fromLayerPoint:)`; set `focusPointOfInterest` + `exposurePointOfInterest` + `focusMode = .autoFocus` + `exposureMode = .autoExpose`. Animate a 500ms ring at the tap point in SwiftUI. Available in PHOTO + VIDEO; disabled in VOICE (the preview is hidden anyway).
- **Video-recording pill overlay**: during a live video recording, render a red capsule at BottomCenter *inside* the preview ZStack (not stacked below it) — pulsing white dot + `M:SS` elapsed-time caption, ticked every 500ms by a `Timer.publish(every: 0.5, ...)` feeding a `@State nowMs`. Inside-the-preview placement is load-bearing: it overlays the viewfinder without reflowing the zoom chips / shutter / queue below. SwiftUI pattern: `ZStack(alignment: .bottom) { CameraPreview(); RecordingPill(startedAt:) }` with `.padding(.bottom, 12)`. Mirror with a pulsing dot alpha via `.animation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true))`. When the audio track is on, append a small white 4-bar mini-equalizer on the right edge of the pill — matching Android's `VideoAudioWave`. Drive the bar heights from an `AVAudioRecorder`-style amplitude on the audio input: tap the `AVCaptureAudioDataOutput` (or use `AVCaptureAudioFileOutput.audioMeters` if attached) to read averagePower per channel, normalise via `pow(10, db/20)`, smooth over ~140ms with a `withAnimation(.linear)` block, and render with `Canvas` (4 bars + irregular phase offsets `[0, 0.18, 0.55, 0.78]` driving a sine envelope on a continuous 520ms phase ramp). The pill stays horizontally centered because the SwiftUI `HStack { dot; time; if audioOn { wave } }` natural sizing widens the capsule symmetrically around its center anchor.

### Voice capture

- `AVAudioRecorder` is the idiomatic iOS analogue of Android's `MediaRecorder`. Configure with:
    - `AVFormatIDKey = kAudioFormatMPEG4AAC`
    - `AVSampleRateKey = 48_000`
    - `AVNumberOfChannelsKey = 1`
    - `AVEncoderAudioQualityKey = .high` (or set `AVEncoderBitRateKey = 96_000`)
- **Audio session**: configure `AVAudioSession.sharedInstance()` with `.playAndRecord` + `.defaultToSpeaker` + `.allowBluetooth`; activate on first voice-record. Deactivate on stop.
- **Amplitude polling** for the waveform: call `recorder.updateMeters()` + `recorder.averagePower(forChannel: 0)` every ~33ms from a `Timer` or `DisplayLink`. Convert the dB value to a 0-1 normalised amplitude via `pow(10, db/20)` and feed the waveform view via a `@Published var amplitude: Float` on an `ObservableObject`.
- **Waveform view**: 128-sample ring buffer, scrolling-bars rendering via `Canvas { context, size in … }` (SwiftUI) — oldest on the left, newest on the right. Flat centerline when idle, tinted red when recording. **Critical observation pattern**: bind the view to the amplitude publisher via `@ObservedObject` (or `.onReceive(publisher)`), *not* by reading `publisher.value` inside a `View.body` — SwiftUI view invalidation doesn't track plain property reads, so direct `.value` access would snapshot once and never update (same gotcha the Android port hit with `snapshotFlow { stateFlow.value }` vs `stateFlow.collect { }`).
- **Elapsed-time caption**: below the waveform, render a large `M:SS` `Text` driven by a `TimelineView(.periodic(from: startedAt, by: 0.5))` or a `Timer`-backed `@State nowMs`. The voice page has no camera preview so the waveform + timer pair is the focal composition — typography should be `.title` weight.
- **Permission**: `AVAudioSession.sharedInstance().requestRecordPermission { granted in … }` on first-ever VOICE shutter tap. Surface an "Open Settings" banner on permanent denial (deep-link with `UIApplication.openSettingsURLString`).
- **Background behaviour**: do not declare `UIBackgroundModes audio` — recording stops on backgrounding (mirror of Android's "no foreground service" decision). Hook `scenePhase` in SwiftUI or `applicationDidEnterBackground` in UIKit to call the VM's async stop path.
- **Torn-file probe**: on orphan-recovery, open each staged `.m4a` with `AVAsset(url:)` and check `asset.duration.seconds.isFinite && duration > 0`. If not, the file was killed mid-record — delete and skip.

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
- Per-item file: `session-{uuid}/{UUID()}.{jpg|mp4|m4a}` — flat per-session directory; modality is carried in the in-memory `StagedItem` sealed type and the `PendingBundle` manifest, not the staging layout.
- Use `FileHandle` or `Data.write(to:)` for photo writes; `AVCaptureMovieFileOutput.startRecording(to:)` for video; `AVAudioRecorder(url:)` for voice — all point at the staging file URL directly. No bookmark dance.
- **Order log**: `session-{uuid}/.order` file, append-only, `{timestampMs}\t{filename}\n` rows. Write synchronously at item-add time for photos and at start-of-recording for video/voice — so the intended capture order is preserved even if a recording is killed mid-flight. Use `FileHandle.seekToEndOfFile()` + `write(_:)` with appending-open, or `Data.write(to:url, options: [.appendOnly])` as the platform permits.

### Manifest store

- JSON-encode the manifest struct (Codable `PendingBundle`) to `application-support/pending/{bundle-id}.json` via `JSONEncoder`.
- Atomicity: `data.write(to: url, options: [.atomic])` so a crash mid-write leaves a valid-or-absent file, never corrupt.
- **Polymorphic `PendingItem`**: model as a Codable enum with associated values (`case photo(PendingPhoto)`, `case video(PendingVideo)`, `case voice(PendingVoice)`), or Swift sealed enums via `@CodingKeys` + a custom `init(from:)` that reads a `"type"` discriminator. Include a top-level `version: Int` field on `PendingBundle` and branch decoding for backward compatibility with pre-multimodal manifests (none exist on iOS yet since this is a greenfield port, but the pattern preserves forward compatibility).

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
- The worker body is manifest-driven (exact mirror of Android): load manifest → partition `orderedItems` into photos/videos/voices via a pure `planRouting(manifest)` → (optionally) copy raw items into `bundles/{id}/{photos,videos,audio}/` respectively + stamp final EXIF on photos only → write per-video `.mp4.thumb.jpg` poster frames + per-voice `.m4a.thumb.jpg` glyph tiles → (optionally, and only if photos.isNotEmpty()) stitch + copy to bookmarked `stitched/` → delete manifest → reap empty staging session.
- **Video poster frames**: extract via `AVAssetImageGenerator(asset:).copyCGImage(at: .zero, actualTime: nil)`; scale to ~48dp square; write as JPEG via `CGImageDestination`.
- **Voice thumbnails**: programmatically render a 96×96 PNG/JPG with a navy backdrop + white mic glyph (Core Graphics paths, no SF Symbol rasterisation needed — the glyph is simple enough to hand-draw). Mirror Android's `renderVoiceThumbnail()`.

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

- Bookmark + flags: `UserDefaults.standard`. Keys mirror Android's set: `rootBookmark`, `stitchQuality` (enum raw), `cameraMode` (ZSL/EXT enum raw), `videoQuality` (enum raw), `maxVideoLengthSeconds: Int?` (nil = unlimited), `shutterSoundOn`, `saveIndividualPhotos`, `saveStitchedImage`, `deleteDelaySeconds`, `deleteConfirmEnabled`, `seenTutorialSteps: Set<String>`.
- Expose as a `Published` stream (Combine) or `AsyncStream` for SwiftUI observation.
- Enforce the at-least-one-output invariant at both write time and read time.
- **UI label for `saveIndividualPhotos`**: render as **"Individual files in subfolder"**, not "Individual photos in subfolder". The underlying storage key retained the `Photos` suffix for manifest + `UserDefaults` backward compatibility, but the gate covers photos, videos, and voice memos equally — so the user-facing label is modality-agnostic. Same label wording as the Android build.
- **Tutorial step IDs** match Android: `modality`, `commit`, `discard`, `deleteOne`, `reorder`, `divide`. Store as a `Set<String>` in `UserDefaults` via `Codable`-backed property or a JSON-encoded string.

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
- SF Symbols for all icons: `gearshape`, `bolt.slash` / `bolt.badge.automatic` / `bolt`, `camera.rotate`, `photo.on.rectangle`, `video`, `mic`, `rectangle.stack`, `waveform`, etc.
- Respect safe areas, especially bottom inset for home-indicator gesture interaction (mirror of Android's `navigationBarsPadding()` + `systemGestureExclusion`).
- Accent colors: commit-green `#2E7D32`, discard-amber `#B26A00`, delete-red (system destructive), record-red `#D32F2F`, voice-navy `#1F2A44`. Map to asset catalog with dark/light variants.

### Modality switch (swipe + pill)

- Wrap the capture slab (preview + control row) in a `DragGesture()` on the container. Translate the slab via `.offset(x: dragX)` during drag. The swipe and the pill stay visually coupled by feeding `dragProgress = dragX / slabWidth` into the pill's selected-segment indicator offset.
- Modality state machine: `@Published var modality: Modality` + a `modalityTransition` state (stable / transitioning-with-pending) so a fast double-swipe resolves to the final intended state without lost transitions (mirror of Android's `ModalityTransition`).
- Per-modality content visibility: `if modality != .voice { CameraPreviewView(...) }` — SwiftUI layout preservation needs an `.opacity(0)` + `.allowsHitTesting(false)` pattern to keep the slot height consistent, mirror of Android's alpha=0 pattern.
- Animation: two-stage commit (tween slide in drag direction, then snap to 0 as modality swaps) vs cancel (`interactiveSpring(response: 0.3, dampingFraction: 1.0)`). Don't use `interactiveSpring` with a bouncy damping — iOS will oscillate identically to Compose.
- Spatial model: `VIDEO ← PHOTO → VOICE`. Swipe *right* (drag PHOTO rightward) reveals VIDEO on the left it was hiding; swipe *left* reveals VOICE on the right. Useful when debugging direction — getting the ghost-reveal side wrong is easy.

### Gesture tutorial (6-step overlay, demo overlays its real gesture zone)

The tutorial scrim consumes all pointer events and walks through the six gestures: `modality`, `commit`, `discard`, `deleteOne`, `reorder`, `divide`. Persistence uses `seenTutorialSteps: Set<String>` in `UserDefaults` (unioned on dismiss), so future step additions only show the delta.

**Placement rule — each step's demo overlays its real on-screen gesture zone**, not a fixed bottom strip:

- The five queue-strip demos (commit / discard / deleteOne / reorder / divide) render in a 72dp-tall strip pinned above the home indicator, matching the real queue's position. The demo is a fake 4-thumbnail row with a looping animated finger.
- The `modality` demo renders in the 3:4 preview area (upper slab, below the top bar), matching the real viewfinder position — where the swipe gesture actually originates.

This is load-bearing for muscle-memory transfer on dismiss: the user's hand is already over the region the gesture uses. SwiftUI pattern — branch the tutorial's `VStack` on step type and put the demo above or below the text block accordingly. `Spacer()` weights handle the slack.

**Modality demo internals** (mirror of Android's `ModalitySwipeDemo`):

- 3:4 rounded-rect card with a mini `VIDEO · PHOTO · VOICE` pill at the top.
- `Animatable(progress: Float)` loop: 0 → 1 over ~1.6s tween + 600ms hold + snap + 300ms gap.
- Current-modality placeholder (PHOTO tint) translates right by `progress * width`.
- Ghost modality placeholder (VIDEO tint) starts at `-width` and slides in behind via `(progress - 1) * width`. Z-order: ghost first (back), current last (front) — so the reveal is "PHOTO slides off to expose VIDEO".
- Finger glyph sweeps from `0.28 * width` to `0.72 * width` horizontally at the vertical midline.
- Pill indicator translates from PHOTO's segment center to VIDEO's segment center linearly with `progress`; segment text colors lerp from black (under the white indicator) to white-0.85 (distant) based on **continuous** distance-from-indicator, not a binary threshold. A binary flip at 0.5 flashes visibly — smooth lerp avoids it.

**Queue-strip demos** are pixel-level mirrors of the real queue's 72dp strip: 56dp thumbs, 6dp gap, muted gradient tints, a `Finger` ring-over-dot glyph tracking the gesture motion. Reuse the Android visual language exactly; the iOS `Canvas` or `ZStack`-with-`.offset` patterns map 1:1 from Compose's `Canvas` / `graphicsLayer.translationX`.

### Testing

- Port the pure-function test suites 1:1:
    - `DividerOps` → `DividerOpsTests` (partition + remap-on-delete).
    - `OrientationCodec` → `OrientationCodecTests` (cardinal round-trip, snap boundaries, surface-rotation inverse).
    - `Stitcher.computeLayout` → `StitcherLayoutTests` (quality ceilings, height cap, heap budget, aspect preservation).
    - `StorageLayout` → `StorageLayoutTests` (3-digit zero-pad at 1/99/100/999; per-modality subfolder paths; `mimeFor` dispatch).
    - `PendingBundle` round-trip → `PendingBundleSerializationTests` (v1 legacy decode → v2 in-memory shape; v2 round-trips with `version:2`; unknown version returns nil).
    - `BundleWorker.planRouting` → `BundleWorkerRoutingTests` (photo-only, video-only, voice-only, mixed-modality partitions; `shouldStitch` flag).
    - `ModalitySwipeMath.resolveTarget` → `ModalitySwipeMathTests` (distance-threshold, velocity-shortcut, endpoint-no-wrap from VIDEO and VOICE).
- Manual smoke path: same as Android — first-run folder pick + permissions + tutorial; capture 3–5 photos; swipe to VIDEO + record a clip; swipe to VOICE + record a memo (grant mic permission); commit; verify per-modality subfolders + stitch in bookmarked folder; swipe-discard + undo; let undo time out; delete-one; reorder; kill the app mid-record; relaunch; torn file is dropped, remaining queue is restored in capture order.

### Effort estimate (from Android delta)

- Camera photo + orientation + zoom + flash + lens flip: **~4 days**.
- Video capture (`AVCaptureMovieFileOutput`, concurrent session config, max-length, poster extraction): **~2 days**.
- Voice capture (`AVAudioRecorder`, audio session lifecycle, amplitude polling, scrolling waveform + M:SS caption): **~1.5 days**.
- Staging + manifest store (Codable + polymorphic item) + bookmark I/O helpers: **~2 days**.
- Worker + background task + operation queue + per-modality routing: **~2 days**.
- Stitcher port: **~1 day** (layout math is pure; CoreImage composition is straightforward).
- EXIF (capture-time + final-pass, photos only): **~1 day**.
- Location provider with TTL: **~0.5 day**.
- Settings + UserDefaults-equivalent (plus cameraMode + videoQuality + maxVideoLength + seenTutorialSteps): **~1 day**.
- Capture UI + queue strip + gestures **including modality-switch swipe** + per-modality control visibility: **~5 days** (gesture mechanics are the most subtle part; the modality-switch slab animation is a re-learnable headache — budget for playtesting).
- Bundle Preview + pending-delete + processing-row + mixed-modality subtitle + per-modality icons: **~2 days**.
- Gesture tutorial (6-step, step-set-persisted, per-step demo positioned over its real gesture zone — preview for `modality`, queue strip for the other five; smooth per-segment pill lerp): **~1.5 days**.
- Polish (haptics tuning, shimmer animations, icon counter-rotation, waveform performance on older devices): **~2 days**.

**Total: ~4–4.5 weeks solo.** The multimodal surface adds about a week to the pre-multimodal estimate; most of the additional time is video+voice pipelines and the modality-swipe gesture tuning, not architecture.

---

## See also

- [`README.md`](./README.md) — Android reference implementation: module layout, dependency versions, class-by-class responsibilities.
- [`RELEASE.md`](./RELEASE.md) — Signing, R8, Play Store / AAB packaging runbook.
- [`BACKLOG.md`](./BACKLOG.md) — Post-MVP ideas (OCR / MinerU companion, LocalSend peer-to-peer transfer).
- [`CLAUDE.md`](./CLAUDE.md) — Condensed architecture reference for Claude Code agents.
