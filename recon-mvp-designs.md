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

- **Photos** are on internal storage from the moment the shutter fires; `OrphanRecovery` restores the most-recent live staging session as an uncommitted queue on next launch. Older live orphans are deleted to bound disk growth.
- **Videos** survive mid-record kill thanks to incremental moov-atom flushes (CameraX 1.6 Media3 Muxer / iOS `movieFragmentInterval`); `OrphanRecovery` probes `.mp4` via `MediaMetadataRetriever.extractMetadata(DURATION)` (Android) / `AVAsset.duration` (iOS) — non-null restores with poster + duration, null is torn → delete.
- **Voice** files have no equivalent partial-file resilience — `MediaRecorder` / `AVAudioRecorder` torn output is unplayable. Same duration probe distinguishes torn vs. playable.
- **Mixed-media order** is preserved via the staging session's `.order` append-log (start-time entries, written before the file even materializes for video/voice). Without it, `lastModified` would reorder a `[Video, Photo, Voice]` capture as `[Photo, Voice, Video]` since recording mtimes are stop-time.
- **Bundle-ID counter** is allocated atomically before any output I/O; worker failure doesn't rewind. Retries reuse the same ID.
- **Worker failure flow** filters pre-existing historical failures on first subscription (the "acknowledged" set), so a transient failure from yesterday doesn't re-surface on every launch.
- **Discard marker is synchronous**: a `.discarded` file is written to the staging directory on the UI thread (one `File.createNewFile()`, ~1 ms) **before** the 3-second undo timer starts. If the process dies inside the window, `OrphanRecovery` sees the marker and deletes the session instead of restoring it. Undo removes the marker.
- **Backpressure is natural**: the worker queue drains at its own pace; capture latency doesn't depend on worker depth.
- **Memory stays flat**: media is on disk, not in RAM. The worker holds at most one bundle's decoded pixels at a time.
- **Lifecycle pause stops active recordings** through the normal async stop paths so `BusyState` resolves to `Idle` on resume. No foreground service — recording only runs while the Activity / scene is foregrounded.

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

Committed bundles can be transferred peer-to-peer over the LAN to any device running the [LocalSend](https://github.com/localsend/protocol) protocol (v2.1). Recon ships the **sender** side only — receiving is out of scope. The affordance lives in Bundle Preview.

### Selection model

A row is the atomic unit; no file-level selection. Swipe right past ~40% of an 80dp reveal width (or fast right flick) snap-translates the row to that offset; the exposed strip is solid green with a check-circle. The resting offset *is* the selected state — no separate tint. Swipe back to 0, or tap the check, to deselect. Swipe left from 0 → standard swipe-to-delete; swipe left from selected → clamped to 0 (deselect-only). Processing rows skip the gesture handler entirely so selection can't race the worker's writes.

When ≥1 bundle is selected, the contextual app bar swaps in: close-X left, "N selected" title, paper-plane Send action right. Tapping close clears selection and animates every selected row back to 0; a suppression flag prevents follow-through finger motion from re-selecting mid-animation.

### Discovery

Tap Send → modal bottom sheet → UDP multicast discovery on `224.0.0.167:53317`. The sheet emits one announce on every non-loopback IPv4 NIC, then surfaces inbound announces (peer-initiated or `announce:false` replies) as `Peer` rows showing alias, deviceModel, and the first 8 hex chars of fingerprint (a trust-on-first-use cue). Spinner while empty; after 12 s with no peers, swap to "No peers found" + `Try again` / `Close`.

Recon does **not** run an inbound HTTP server. Most peers reply via multicast UDP and surface fine; a peer that *only* responds via HTTP `POST /api/localsend/v2/register` won't appear (rare in practice).

### Single session for all selected bundles

LocalSend's receiver treats a session as active until the user dismisses the receive panel on their desktop, *not* until the last file lands. Sequential `prepare-upload` calls to the same peer thus 409 BLOCKED on the second. So **all selected bundles ship in one prepare-upload** — this is non-negotiable.

Wire path per file is `{bundleId}/{subfolder}/{leaf}` (or `{bundleId}/{leaf}` for legacy flat layouts). `subfolder` ∈ `{"photos", "videos", "audio", "stitched"}`. The receiver parses `/` verbatim (with `..` traversal blocked) and materializes Recon's own per-bundle directory layout under its save root.

### Trust model

Each install generates one self-signed RSA cert (cached on disk, lazy on first use). Peers identify by SHA-256 fingerprint of the leaf cert, advertised in the announce. There is no CA validation.

Pinning runs **during** the TLS handshake (Android: inside `X509ExtendedTrustManager.checkServerTrusted`; iOS: inside the `URLSession` delegate's `didReceive challenge`). Match → session authenticates; mismatch → handshake fails fast. Post-handshake checks via an interceptor reading `peerCertificates` are unsafe: Android Conscrypt marks the session unverified when the trust manager is a no-op, which makes `SSLSession.getPeerCertificates()` throw and OkHttp's `Handshake.get` swallow it into an empty list — leaving nothing to verify. The Android trust manager extends `X509ExtendedTrustManager` directly (not just `X509TrustManager`) to dodge `AbstractTrustManagerWrapper`'s hostname check, which would otherwise fail on our per-install cert's CN.

A peer-specific HTTP client is built per `send` call via `client.newBuilder().sslSocketFactory(...)` (Android) / `URLSession(configuration: copy)` (iOS) so the connection pool / dispatcher / timeouts stay shared while each peer's TLS identity is pinned independently.

### Transfer semantics

1. `POST /api/localsend/v2/prepare-upload` with `{info, files: { wireName: { id, fileName, size, fileType } }}`. Receiver shows a confirmation prompt; on accept, replies with `{sessionId, files: { wireName: token }}`.
2. If any requested file is missing from the response tokens, send `/cancel` and fail the whole send. Silently shipping the rest is partial-data masquerading as success.
3. Per file, `POST /api/localsend/v2/upload?sessionId&fileId&token` with bytes streamed in 64 KB chunks. Up to 3 in flight concurrently. Stream from the source URI directly — never `readBytes()` a 20 MB stitched JPEG. Poll `Job.isActive` / `Task.isCancelled` between chunks so cancellation aborts at the next chunk boundary.
4. On any error / cancellation, post `/cancel` best-effort.

Progress is reported as session-level totals (files completed / total, bytes sent / total) plus the wire path of the most recent file. Chunk callbacks fire ~120 events/s peak; throttle UI emissions to 20 Hz, force-emit lifecycle moments (start, per-file completion, terminal state) so counts don't lag the visual.

### Send completion

The sheet swaps to a success state when the receiver acknowledges the last file. The user already picked the recipient on the previous screen, so the question this page answers is "did everything go?", not "where to?" — headline is the **outcome**:

- **Success**: `{N} bundle(s) ({F} files, {Y.Y MB})` as a centered icon-led `titleLarge`. Bytes use base-1000 (KB / MB / GB), one decimal, `Locale.US` so the separator is always `.`. Subtitle lines below: `Sent to {alias}` and `{deviceModel} · {fingerprint[0..8]}` (mirroring the peer-list row format).
- **Already received** / **Send failed**: same icon-led layout, no count headline, recipient identity still shown.

Counts come from the last observed `SendProgress` — no extra walk of `BundleLibrary.listBundleFiles`. Single primary action is a full-width Filled-tonal "Done" (the right weight for the only CTA on a confirmation sheet — a TextButton in the gutter would read as a dismissive Cancel).

### Failure modes

- **No peers found** after 12 s: Wi-Fi blocks multicast (hotel / carrier / AP isolation) or the receiver isn't listening.
- **409 BLOCKED**: receiver still has the previous transfer's panel open. Surface "Tap Done on the receiving device, then retry."
- **403**: receiver declined.
- **204**: receiver had every file already (sha256 dedup if both ends opt in).
- **Mid-transfer drop**: `/cancel` sent best-effort, fail with the underlying error.

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

29. **Snap-position selection on bundle rows, not tap-to-toggle.** A swipe-right on a row snap-translates to a fixed 80dp reveal width and stays there. The row's resting offset *is* its selected state — there is no separate tint. Tap on the green check zone deselects; swipe-back to 0 deselects. No long-press, no tap-anywhere-to-toggle. An explicit, reversible gesture per state change reads better than an ephemeral tap, and a snap-position model gives an obvious target for the "tap the check to undo" affordance. Swipe-left to delete coexists in the same `pointerInput` block, signed-offset clamped per state (selected rows clamp at `[0, revealWidth]` so they can only be deselected, not deleted).

---

## iOS build notes

iOS API mapping for the design above. Assumes iOS 16 minimum, Swift + SwiftUI + Swift Concurrency. Behavior, gestures, and product surface are described in the platform-neutral sections above — do not re-spec them here.

### Camera (photo + video)

- `AVCaptureSession` on a dedicated serial queue. Preview via `AVCaptureVideoPreviewLayer` in a `UIViewRepresentable`; 3:4 aspect ratio.
- **Photo**: `AVCapturePhotoOutput`. ZSL = `isResponsiveCaptureEnabled = true` + `isZeroShutterLagEnabled = true` (iOS 17+); Quality = `photoQualityPrioritization = .quality`.
- **Video**: `AVCaptureMovieFileOutput` attached to the same session as photo. Mic via an `AVCaptureDeviceInput` added/removed per the user's mic toggle (and per `AVAudioSession`/permission); when off, drop the audio input *before* `startRecording(to:recordingDelegate:)` so the clip records silent. Set `movieFragmentInterval = CMTime(seconds: 1, preferredTimescale: 600)` for incremental moov flushes — the iOS analogue of Media3 Muxer resilience.
- **Video format/quality**: target `.mp4` directly via `AVAssetWriter`, or write `.mov` and re-mux. Map `Low/Standard/High` settings to `AVCaptureSession.Preset.{hd1280x720, hd1920x1080, hd4k3840x2160}`.
- **Max video length**: `DispatchWorkItem` scheduled at start; cancel on manual stop.
- **Lens flip**: `AVCaptureDevice.DiscoverySession`; reconfigure under a session lock. Disabled mid-record.
- **Flash**: `AVCapturePhotoSettings.flashMode`, applied per-capture. Persist `currentFlashMode` as instance state and re-apply on every settings instantiation.
- **Zoom**: `videoZoomFactor` + `ramp(toVideoZoomFactor:withRate:)`. Pinch multiplies it. Filter preset chips against the active format's zoom range.
- **Tap-to-focus**: `previewLayer.captureDevicePointConverted(fromLayerPoint:)` → `focusPointOfInterest` + `exposurePointOfInterest`; 500ms ring animation in SwiftUI.
- **Recording pill** (overlays the preview; do not reflow below): `ZStack(alignment: .bottom) { CameraPreview(); RecordingPill(...) }` with `.padding(.bottom, 12)`. Pulsing dot via `.animation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true))`. With audio on, append a 4-bar mini-equalizer (`Canvas`, phase offsets `[0, 0.18, 0.55, 0.78]`, 520ms phase ramp, ~140ms linear smoothing) driven by amplitude tapped from `AVCaptureAudioDataOutput` (`pow(10, db/20)` normalize). `HStack { dot; time; if audioOn { wave } }` keeps the capsule centered as the wave widens it.

### Voice capture

- `AVAudioRecorder` configured `kAudioFormatMPEG4AAC` / 48000 / mono / 96000 bps. `AVAudioSession.sharedInstance()` activated `.playAndRecord` + `.defaultToSpeaker` + `.allowBluetooth` on first record; deactivated on stop.
- **Amplitude**: `recorder.updateMeters()` + `averagePower(forChannel:)` every 33ms; `pow(10, db/20)` to 0–1; expose via `@Published var amplitude: Float`.
- **Waveform**: `Canvas` over a 128-sample ring buffer, scrolling oldest→newest. Bind via `@ObservedObject` / `.onReceive(publisher)`, **not** `publisher.value` reads inside `body` — SwiftUI doesn't track those (same gotcha as Compose's `snapshotFlow { stateFlow.value }`).
- **Elapsed-time caption**: `TimelineView(.periodic(from: startedAt, by: 0.5))`, `.title` weight, since the waveform + timer pair is the focal composition (no preview).
- **Permission**: `AVAudioSession.requestRecordPermission` on first VOICE shutter; "Open Settings" banner on permanent denial (`UIApplication.openSettingsURLString`).
- **Background**: do not declare `UIBackgroundModes audio`. Hook `scenePhase` to call the VM's async stop on backgrounding.
- **Torn-file probe**: open the staged `.m4a` with `AVAsset(url:)` and reject when `duration.seconds` isn't finite or is ≤ 0.

### Orientation

- Lock to portrait via `Info.plist` + `supportedInterfaceOrientations`.
- `CMMotionManager` polling gravity vector at ~10Hz, snapping to cardinal (0/90/180/270).
- Write into `AVCapturePhotoOutput.connection(with: .video)?.videoRotationAngle` (iOS 17+) / `videoOrientation` so the EXIF tag matches physical pose.
- Counter-rotate icons via `.rotationEffect(.degrees(-deviceOrientation))` + spring animation; use a shortest-arc cumulative angle store so 0°→90° doesn't spin through 270°.

### Output-folder persistence (security-scoped bookmark)

- `UIDocumentPickerViewController(.open, .folder)`; persist `url.bookmarkData(options: .withSecurityScope)`.
- **Every access** must bracket `startAccessingSecurityScopedResource()` / `stopAccessingSecurityScopedResource()` — failing this leaves the URL unreachable after relaunch. Wrap in a `withBookmarkedRoot<T>` helper.
- Create `bundles/` and `stitched/` under the root on first access.

### Staging (in `applicationSupportDirectory`, not the bookmark)

- `applicationSupportDirectory/staging/session-{UUID()}/{UUID()}.{jpg|mp4|m4a}` — flat per-session; modality lives in the `StagedItem` enum and `PendingBundle`, not the path.
- `Data.write(to:)` (photos), `AVCaptureMovieFileOutput.startRecording(to:)` (video), `AVAudioRecorder(url:)` (voice) — all point at the staging URL directly.
- **Order log**: `.order` file, append-only `{timestampMs}\t{filename}\n` per item. `FileHandle.seekToEndOfFile() + write(_:)`.

### Manifest store

- `Codable PendingBundle` → JSON via `JSONEncoder` → `applicationSupportDirectory/pending/{bundle-id}.json` via `data.write(to: url, options: [.atomic])`.
- **Polymorphic `PendingItem`**: Codable enum with associated values (`case photo(PendingPhoto)`, etc.) using a custom `init(from:)` that reads a `"type"` discriminator. Top-level `version: Int = 2` — Swift's encoder writes non-optional `let`s with defaults unconditionally, so the field always lands on disk.

### Bundle-ID counter

- `applicationSupportDirectory/counter.plist`: `{lastDate, lastCounter}`. Gate reads/writes behind an `actor`. Reset to 1 on date change.

### Worker

- `OperationQueue(maxConcurrentOperationCount: 1)` for the process-wide serialization. Each `Operation` wraps a detached `Task` in `beginBackgroundTask(withName:)` / `endBackgroundTask` so the OS grants ~30 s even when the user switches away. For long backlogs, register a `BGProcessingTaskRequest` to drain remaining manifests when idle (declare its identifier in `Info.plist`'s `BGTaskSchedulerPermittedIdentifiers`).
- Worker body is the platform-neutral Phase 3 verbatim: load manifest → `planRouting` → per-modality copy + photo final-EXIF → optional stitch → reap manifest + staging.
- **Video poster**: `AVAssetImageGenerator.copyCGImage(at: .zero, ...)` → JPEG via `CGImageDestination`.
- **Voice thumbnail**: hand-drawn 96×96 navy + mic-glyph via Core Graphics.

### Failure surfacing

- `AsyncStream<BundleFailure>` (or Combine `Publisher`) consumed by the capture screen. Snapshot in-flight failures at subscription as "acknowledged" so historical replays don't surface.

### Stitcher

- Pure `computeLayout` ported 1:1 from Kotlin (`LOW=1600`, `STANDARD=1800`, `HIGH=.max`, height cap `32_000`, heap fraction `0.6`).
- Decode `CGImageSource` → apply EXIF orientation → draw into one `CGContext(commonWidth × totalHeight)` → JPEG via `CGImageDestination` at quality `0.70 / 0.82 / 0.92`.
- Write to `NSTemporaryDirectory()` first, then `FileManager.copyItem(at:to:)` into the bookmarked `stitched/` (with security-scope bracketing).

### EXIF

- Capture time: `CGImageDestinationAddImage` with `kCGImagePropertyExif*` + `kCGImagePropertyTIFF*` + `kCGImagePropertyGPS*`.
- Worker time: read existing metadata via `CGImageSourceCreateWithURL`, add `kCGImagePropertyExifUserComment = "Recon:{bundleId}:p{kk}"` (or `:stitch`), backfill GPS if missing, re-encode in one open/save pass.

### Location

- `CLLocationManager` `.desiredAccuracy = kCLLocationAccuracyNearestTenMeters`, `.distanceFilter = 50`, `whenInUse` auth on first capture-screen mount. 30 s TTL cache, 15 s refresh threshold. Worker-time refresh wrapped in a 2 s timeout.

### Settings

- `UserDefaults` keys mirroring the Android set: `rootBookmark`, `stitchQuality`, `cameraMode`, `videoQuality`, `maxVideoLengthSeconds`, `shutterSoundOn`, `saveIndividualPhotos`, `saveStitchedImage`, `deleteDelaySeconds`, `deleteConfirmEnabled`, `seenTutorialSteps`. Expose via `@Published` / `AsyncStream`.
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

The queue strip is three sibling views — left-handle commit zone, middle `UICollectionView` (horizontal scroll + per-cell `UISwipeGestureRecognizer(.down)` for delete + drop-delegate reorder), right-handle discard zone — each owning its own `UIPanGestureRecognizer` direction-filtered to commit-distance-OR-velocity hybrid. Between cells, a 24-pt invisible divider view with its own pan recognizer, z-ordered above. Standard arbitration routes touches; no `shouldRecognizeSimultaneouslyWith` overrides needed. Commit animation: `UIViewPropertyAnimator` over a snapshot of the collection view and the zone's layer.

### Visual design

- Dark capture screen, white controls; system background elsewhere.
- SF Symbols: `gearshape`, `bolt.slash` / `bolt.badge.automatic` / `bolt`, `camera.rotate`, `photo.on.rectangle`, `video`, `mic`, `rectangle.stack`, `waveform`.
- Accent colors: commit `#2E7D32`, discard `#B26A00`, delete (system destructive), record `#D32F2F`, voice `#1F2A44`.
- Respect safe-area bottom inset (home-indicator gesture region).

### Modality switch (swipe + pill)

- `DragGesture()` on the slab container; `.offset(x: dragX)` during drag. Pill indicator translates linearly with `dragProgress = dragX / slabWidth`.
- `@Published var modality: Modality` + a `modalityTransition` state (stable / transitioning-with-pending) so fast double-swipes don't lose intermediate states.
- Per-modality visibility: keep the camera preview in the tree with `.opacity(0).allowsHitTesting(false)` in VOICE so the slot height stays constant on switch-back.
- Two-stage animation: commit-path tween slide off in drag direction then snap to 0; cancel-path `interactiveSpring(response: 0.3, dampingFraction: 1.0)`. **Do not** use a bouncy damping — same overshoot bug as Compose.
- Spatial model: `VIDEO ← PHOTO → VOICE`, no wrap.

### Gesture tutorial

- Six steps (`modality`, `commit`, `discard`, `deleteOne`, `reorder`, `divide`); scrim consumes all pointer events; `seenTutorialSteps: Set<String>` in `UserDefaults` unioned on dismiss.
- **Placement rule**: each step's demo overlays its real gesture zone — the queue-strip five render in a 72-pt bottom strip mirroring the real queue; `modality` renders in the 3:4 preview area.
- Modality demo: ~1.6 s tween + 600 ms hold loop. PHOTO placeholder translates right by `progress * width`; VIDEO ghost slides in from `-width`. Pill indicator lerps from PHOTO segment center to VIDEO segment center; segment text colors lerp **continuously** from black-under-indicator to white-distant (a binary flip at 0.5 flashes visibly).

### LocalSend bundle transfer

A clean-room Swift port of the Android sender. Same shape, same on-the-wire contract, same trust model (per-install self-signed cert, fingerprint pinned during the TLS handshake). The platform-neutral protocol behavior is documented in [LocalSend bundle transfer](#localsend-bundle-transfer); this section is just the iOS API mapping.

**Module shape** (mirrors `network/localsend/`):

- `LocalSendController` — top-level coordinator. Owns one base `URLSession` (default config), one `LocalSendDiscovery`, one lazy `LocalSendUploader`. Mutex-caches the local `Info` block (alias + deviceModel + fingerprint) so each `discover` / `send` call doesn't re-read `UserDefaults` and re-touch the cert manager.
- `LocalSendDiscovery` — multicast send/listen on `224.0.0.167:53317`. Use Apple's `Network.framework`: `NWConnectionGroup` with a multicast `NWEndpoint.Group` listening on the IPv4 group. Send one announce on start, then surface inbound announces as `Peer` records on an `AsyncStream<Peer>`. **No multicast lock equivalent needed** — iOS doesn't filter inbound multicast by default like Android does, so there's no analogue to `WifiManager.MulticastLock`. The `Local Network` privacy permission (`NSLocalNetworkUsageDescription` in `Info.plist` + a `NSBonjourServices` declaration including `_localsend._tcp`) is the iOS-specific gate — first multicast operation triggers the system prompt; denial silently produces zero peers. Surface a "Open Settings to enable Local Network access" hint if discovery times out without prior permission.
- `LocalSendUploader` — per-session protocol implementation. `send(peer, info, items, onProgress) -> SendBundleResult`:
    1. Build a peer-specific `URLSession` with a delegate that overrides `urlSession(_:didReceive:completionHandler:)` to do fingerprint pinning during the TLS handshake (see Trust model below). Reusing the base session via `configuration.copy()` shares URL cache + protocol classes.
    2. `POST /api/localsend/v2/prepare-upload` with the JSON body `{info, files: [wireName: FileMetadata]}`.
    3. If the response omits tokens for any requested file, `POST /cancel` and fail with `"Receiver dropped N file(s); refusing to send a partial bundle"`. **Do not silently skip** — partial-data masquerading as success is the worst outcome.
    4. Per file, `POST /api/localsend/v2/upload?sessionId&fileId&token` with the body streamed via `URLSession.uploadTask(withStreamedRequest:)` + an `InputStream` reading from the staged file URL in 64 KB chunks. Cap parallel uploads at 3 via a `DispatchSemaphore` or actor-protected counter. Poll `Task.isCancelled` between chunks for fast cancellation.
    5. Throttle progress emissions to 50 ms (chunk callbacks fire ~120 events/s peak across 3 parallel uploads); force-emit lifecycle moments (start / per-file completion / terminal state) so the count never lags the visual.
    6. On `CancellationError`, HTTP error, or `URLError`, send `/cancel` best-effort and return `Failed`.
- `LocalSendTrustManager` (delegate-based, since iOS doesn't expose a direct `X509TrustManager` analogue):
    - In the session delegate's `didReceive challenge`, branch on `protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust`.
    - Read `challenge.protectionSpace.serverTrust`, copy the leaf cert (`SecTrustCopyCertificateChain` on iOS 15+, `SecTrustGetCertificateAtIndex(trust, 0)` on older), `SecCertificateCopyData(...)` to get the DER bytes, SHA-256 it, hex-encode lowercase, compare against the announced fingerprint.
    - Match → `completionHandler(.useCredential, URLCredential(trust: serverTrust))`. Mismatch → `completionHandler(.cancelAuthenticationChallenge, nil)`. There's no equivalent to Android's `AbstractTrustManagerWrapper` hostname-check pitfall — iOS's `URLSession` lets the delegate own trust completely.
- `LocalSendCertManager` — per-install self-signed cert. Generate via `Security.framework`: `SecKeyCreateRandomKey` for an RSA 2048 keypair, then build an X.509 cert via the lower-level `SecCertificateCreateWithData` API or — more practically — vendor a `swift-asn1`-based helper to construct the cert. Cache PEM-encoded leaf + private key in the app's Application Support directory at `application-support/localsend/cert.pem`. 100-year validity (fingerprint pinning makes expiry checks moot). Pure helper `computeFingerprintHex(_ derBytes: Data) -> String` returns lowercase 64-char SHA-256 hex.
- `LocalSendDtos.swift` — every wire shape (`Announce`, `RegisterRequest/Response`, `Info`, `FileMetadata`, `FileTimestamps`, `PrepareUploadRequest/Response`) as `Codable` structs. `JSONEncoder.outputFormatting = []` (compact); `JSONDecoder` defaults are fine. The `version` field is `let version: String = "2.1"` — Swift's encoder writes it because non-optional `let`s with defaults serialize unconditionally (analogue of `encodeDefaults = true` in kotlinx.serialization). Nullable fields use `Optional` properties + `encoder.dataEncodingStrategy = ...` defaults that omit `nil` (analogue of `explicitNulls = false`). Decoding ignores unknown keys by default.

**Bundle Browser selection model** (mirror of Android):

- A row's resting offset *is* its selected state — no separate tint. Swipe right past ~40% of an 80dp reveal width snaps the row to that offset; the exposed strip is solid green with a `checkmark.circle.fill` icon.
- Swipe left from 0 → standard swipe-to-delete (existing flow). Swipe left from selected → clamped to 0 (deselect-only, no delete).
- Tap the green check zone or swipe back to 0 → deselect.
- Processing rows (worker still in flight) skip the gesture handler entirely.
- Contextual top toolbar swaps in when ≥1 row selected: close-X left, "N selected" title, paper-plane (`paperplane.fill`) Send action right. Tapping close clears the selection and animates every row back to 0; a suppression flag prevents follow-through finger motion from re-selecting mid-animation.

**Send sheet** (mirror of Android's `LocalSendSheet`):

- Modal sheet (`.sheet(isPresented:)` with `.presentationDetents([.large])`) opening with the selected `[CompletedBundle]`.
- Three internal states: `Discovering(timedOut: Bool)`, `Sending(peerAlias, bundleCount, progress: SendProgress?)`, `Done(bundleCount, totalFiles, totalBytes, peer, result)`.
- 12-second discovery timeout flips to "No peers found" with `Try again` / `Close`. `Try again` rotates a `discoveryAttempt` counter that re-keys the discovery + countdown effects.
- `Sending` shows a `ProgressView(value:total:)` driven by `progress.sentBytes / progress.totalBytes`, with the wire path of the most recent file underneath as monospaced caption.
- `Done` is icon-led: 72pt filled `checkmark.circle.fill` (Success) / `info.circle.fill` (AlreadyReceived) / `xmark.octagon.fill` (Failed) tinted by the result, then three centered text lines:
    - `titleLarge` headline. Success: `"{N} bundle(s) ({F} files, {Y.Y MB})"` — byte formatter is base-1000 with `Locale(identifier: "en_US_POSIX")` so `.` is the decimal separator regardless of device locale. AlreadyReceived: "Already received". Failed: "Send failed".
    - `bodyMedium` recipient line: `"Sent to {peer.alias}"` (Success), `"To {peer.alias}"` (other states).
    - `bodyMedium` identity line: `"{deviceModel} · {fingerprint.prefix(8)}"` — same format used in the peer-list rows.
- Single `FilledTonalButton`-equivalent (`Button(...) { ... }.buttonStyle(.borderedProminent).tint(.secondary)`) labeled "Done" — full-width, the only CTA on the sheet.
- `closeAndDismiss` calls the dismiss callback synchronously and offloads the discovery teardown (`stopDiscovery` may take ~1s for the receive loop's `soTimeout` to wake) onto a process-scoped `Task` (the `AppContainer` analogue's `appScope`), so the modal scrim stops blocking touches the moment the user taps Done.

**Single session for all selected bundles**: bundling N selected bundles into one `prepare-upload` is mandatory — back-to-back sessions to the same peer 409 BLOCKED until the user manually dismisses each on the receiver. Wire fileNames carry `{bundleId}/{subfolder}/{leaf}`; receiver materializes one folder per bundle.

**Permissions**:
- `NSLocalNetworkUsageDescription` in `Info.plist` (required for any LAN traffic on iOS 14+).
- `NSBonjourServices` array including `_localsend._tcp` (lets the system pre-warm the privacy prompt for the LocalSend service type).
- No `NSCameraUsageDescription` / `NSMicrophoneUsageDescription` change — those are already declared for capture.

### Testing

- Port the pure-function test suites 1:1:
    - `DividerOps` → `DividerOpsTests` (partition + remap-on-delete).
    - `OrientationCodec` → `OrientationCodecTests` (cardinal round-trip, snap boundaries, surface-rotation inverse).
    - `Stitcher.computeLayout` → `StitcherLayoutTests` (quality ceilings, height cap, heap budget, aspect preservation).
    - `StorageLayout` → `StorageLayoutTests` (3-digit zero-pad at 1/99/100/999; per-modality subfolder paths; `mimeFor` dispatch).
    - `PendingBundle` round-trip → `PendingBundleSerializationTests` (v1 legacy decode → v2 in-memory shape; v2 round-trips with `version:2`; unknown version returns nil).
    - `BundleWorker.planRouting` → `BundleWorkerRoutingTests` (photo-only, video-only, voice-only, mixed-modality partitions; `shouldStitch` flag).
    - `ModalitySwipeMath.resolveTarget` → `ModalitySwipeMathTests` (distance-threshold, velocity-shortcut, endpoint-no-wrap from VIDEO and VOICE).
    - `LocalSendDtos` round-trip → `LocalSendDtoTests` (every wire shape, exact spec field names, `version` always emitted, optional omission on `nil`, unknown-key tolerance).
    - `LocalSendUploader.wireNameFor` + `buildPrepareUploadRequest` → `LocalSendUploaderRoutingTests` (`{bundleId}/{subfolder}/{leaf}` composition, multi-bundle items coexist in one payload, insertion-order preserved).
    - `LocalSendCertManager.computeFingerprintHex` → `LocalSendFingerprintTests` (known SHA-256 vectors; 64-char lowercase hex invariants).
- Manual smoke path: same as Android — first-run folder pick + permissions + tutorial; capture 3–5 photos; swipe to VIDEO + record a clip; swipe to VOICE + record a memo (grant mic permission); commit; verify per-modality subfolders + stitch in bookmarked folder; swipe-discard + undo; let undo time out; delete-one; reorder; kill the app mid-record; relaunch; torn file is dropped, remaining queue is restored in capture order; multi-select two bundles in Bundle Preview, send to a desktop LocalSend receiver on the same Wi-Fi, verify the receiver materializes one `{bundleId}/{photos,videos,audio,stitched}/` directory per bundle.

---

## See also

- [`README.md`](./README.md) — Android reference implementation: module layout, dependency versions, class-by-class responsibilities.
- [`RELEASE.md`](./RELEASE.md) — Signing, R8, Play Store / AAB packaging runbook.
- [`BACKLOG.md`](./BACKLOG.md) — Post-MVP ideas (OCR / MinerU companion, background-record FGS).
- [`CLAUDE.md`](./CLAUDE.md) — Condensed architecture reference for Claude Code agents.
