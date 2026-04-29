# Recon

Native Android **multimodal** camera app for capturing **bundles** of photos, videos, and voice notes interleaved in one queue. A bundle produces up to three parallel outputs per modality (`bundles/{id}/photos/`, `videos/`, `audio/`) plus an optional vertically-stitched JPEG (photos only). Each output is independently toggleable in settings (at least one of individual-photos / stitched must be on for photo bundles). Designed for machinegun-cadence capture — shoot, shoot, swipe, swipe-to-video, record, stop, swipe-back, shoot — with zero blocking work on the interaction path. Committed bundles can be transferred peer-to-peer over the LAN via the [LocalSend](https://github.com/localsend/protocol) protocol — multi-select bundles in the in-app browser, pick a peer on the same Wi-Fi, the receiver materializes the bundle's folder structure verbatim.

> The Gradle `namespace` and `applicationId` remain `com.example.bundlecam` — renaming them would orphan existing installs on upgrade. Everything user-facing, every class / log tag / EXIF identifier, and every document uses **Recon**.

**Other docs:**
- [`recon-mvp-designs.md`](./recon-mvp-designs.md) — product + UX + architecture design spec (platform-neutral; canonical source for an iOS port).
- [`RELEASE.md`](./RELEASE.md) — signing, R8, and Play Store release runbook.
- [`BACKLOG.md`](./BACKLOG.md) — post-MVP directions (OCR / MinerU companion, background-record FGS).
- [`CLAUDE.md`](./CLAUDE.md) — condensed architecture reference for Claude Code agents.

This README is the **Android-specific technical reference**: module layout, dependency versions, resilience rationale, and testing. Read `recon-mvp-designs.md` for the product design rationale.

---

## Build & run

Requirements: JDK 11+, Android SDK 36, Gradle 9 (via wrapper).

```bash
./gradlew assembleDebug            # build debug APK
./gradlew installDebug             # install on connected device/emulator
./gradlew assembleRelease          # build signed release APK (see RELEASE.md)
```

Open in Android Studio for preview and live editing.

`local.properties` points Gradle at your Android SDK and is gitignored — it's generated automatically on first open in Android Studio.

For signing, R8, and Play Store packaging, see [RELEASE.md](./RELEASE.md).

### Configuration

- **`minSdk`**: 26 (Android 8.0) — required for adaptive icons, `systemGestureExclusion`, and the CameraX Extensions API.
- **`targetSdk` / `compileSdk`**: 36.
- **JVM toolchain**: Java 11 source/target compatibility.

### Required permissions

| Permission | Why |
|---|---|
| `CAMERA` | Preview + photo capture + video recording. `VideoCapture<Recorder>` uses the same CAMERA grant (no separate video permission on Android). |
| `RECORD_AUDIO` | Mic capture for VOICE (`MediaRecorder` AAC-LC) and the audio track in VIDEO (`Recorder.withAudioEnabled`). Requested on the first VOICE-mode shutter tap, or on the first VIDEO-mode shutter tap when the mic toggle is on (default). Denial keeps the app functional: photo capture works, and VIDEO falls back to silent if the user denies or toggles the mic off. |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | GPS stamping in EXIF — requested up-front on first camera view (after camera permission is granted) so the first capture already carries a fix. Denying is non-fatal; capture proceeds, EXIF just lacks GPS. Video and voice files get no GPS (no EXIF/metadata pass for them). |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Let WorkManager's `SystemForegroundService` run the bundle worker reliably through Doze/backgrounding |
| `POST_NOTIFICATIONS` | Foreground-worker progress notifications on Android 13+ (declared but not requested at runtime — if denied, the worker still runs; only the shade notification is suppressed) |
| `INTERNET` / `ACCESS_NETWORK_STATE` | LocalSend's HTTPS upload to peers on the LAN. |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` | LocalSend discovery — `WifiManager.MulticastLock` is required to receive the inbound multicast announces that surface peers; without the lock Android filters multicast packets to save power. `NEARBY_WIFI_DEVICES` is **not** declared (it'd only be needed for raw nearby-Wi-Fi peer-discovery APIs; outbound multicast on a connected Wi-Fi network doesn't require it). |

No `FOREGROUND_SERVICE_TYPE_MICROPHONE` declaration — recording only runs while the Activity is foregrounded. `LifecycleEventEffect(ON_PAUSE)` stops any in-flight video/voice take so backgrounding doesn't leak a bound mic.

Output storage uses SAF (Storage Access Framework), not a raw filesystem permission — the user picks a root folder on first launch via `OpenDocumentTree`, and the app persists RW access to that tree.

---

## Stack

| Layer | Library |
|---|---|
| UI | Jetpack Compose + Material 3 (BoM `2026.02.01`) |
| Camera (photo + video) | CameraX `1.6.0` (`core`, `camera2`, `lifecycle`, `view`, `extensions`, `video`) |
| Voice recording | `android.media.MediaRecorder` (AAC-LC / MPEG-4 / .m4a / 48kHz mono / 96kbps) |
| Background processing | WorkManager `2.10.0` |
| Persistence (settings / counters) | DataStore Preferences `1.1.1` |
| Manifest serialization | `kotlinx.serialization.json` `1.7.3` (polymorphic sealed `PendingItem` with `classDiscriminator = "type"`) |
| Location | Play Services Location `21.3.0` |
| EXIF | AndroidX ExifInterface `1.4.0` (photo path only) |
| Output storage | AndroidX DocumentFile `1.0.1` (SAF) |
| Video poster / torn-file probe | `android.media.MediaMetadataRetriever` |
| Concurrency | `kotlinx.coroutines` `1.9.0` + `Mutex` |
| LocalSend HTTPS transport | OkHttp `4.12.0` + `okhttp-tls` (HeldCertificate for the per-install self-signed identity) |

**CameraX 1.6.0** is load-bearing: 1.5.1 enabled concurrent `Preview + ImageCapture + VideoCapture<Recorder>` binding, and 1.6.0 switched MP4 writes to Media3 Muxer, which produces playable files even on mid-record process kill — so `OrphanRecovery` can restore video-in-progress recordings after a crash (whereas MediaRecorder-written `.m4a` voice files torn mid-record are deleted at restore time via a retriever-duration probe).

No DI framework — a plain singleton [`AppContainer`](./app/src/main/java/com/example/bundlecam/di/AppContainer.kt) wires everything up at `Application` creation.

---

## Architecture at a glance

```
         ┌──────────────────────────────────────────────────────┐
         │                   UI (Compose)                       │
         │   CaptureScreen → CaptureViewModel                   │
         │     ┌─────────────────────────────────────┐          │
         │     │  ModalityPill · swipe carousel       │          │
         │     │  PHOTO · VIDEO · VOICE                │          │
         │     └─────────────────────────────────────┘          │
         └──────────────┬──────────────────────────┬────────────┘
                        │                          │
          ┌─────────────┼─────────────┐            │
          ▼             ▼             ▼            ▼
   CaptureController  StagingStore  VoiceController
   (Preview +         (internal FS  (MediaRecorder,
    ImageCapture +     + .order log  amplitudeFlow)
    VideoCapture       + discard)
    concurrent)                            │
          │             │                  │
          └─────────────┼──────────────────┘
                        ▼
                ManifestStore (JSON on disk)
                PendingBundle v2 (sealed PendingItem
                { Photo | Video | Voice })
                        │
                        ▼
                WorkScheduler
                        │
                        ▼
                BundleWorker ── planRouting ── Stitcher (photos-only)
                        │
                        ▼
                SafStorage (user-picked tree via SAF)
                        │
                        ▼
                bundles/{id}/{photos,videos,audio}/  +  stitched/
```

### Three-phase capture lifecycle

**Phase 1 — Shutter (UI-thread budget).** `CaptureViewModel.onShutter` dispatches on `_modality.value`:

- **PHOTO** (≤100ms): CameraX `takePicture()` → write the JPEG to internal staging (`filesDir/staging/session-{uuid}/{photo-uuid}.jpg` — each file is a random UUID; queue order lives in the manifest + the session's `.order` append-log, not the filesystem) → stamp EXIF (time, GPS if available, orientation) → decode a small thumbnail → push `StagedItem.Photo` onto the queue.
- **VIDEO** (two taps): first tap → mic-permission pre-check (shared with VOICE; skipped when the user has toggled the left-of-shutter mic button off, in which case the clip records silently) → `CaptureController.startVideoRecording(filesDir/.../{uuid}.mp4, audioEnabled = vm.videoAudioEnabled)` via CameraX `Recorder.prepareRecording(...).withAudioEnabled().start()` (or plain `start()` when audio is off / RECORD_AUDIO denied). Order-log entry appended at start. While recording, CameraX emits `VideoRecordEvent.Status` events (~1 Hz); the listener pushes `event.recordingStats.audioStats.audioAmplitude` into `videoAudioAmplitude: StateFlow<Float>` so the red recording pill can show a live wave. Second tap → `stopVideoRecording()` awaits `VideoRecordEvent.Finalize` → `MediaMetadataRetriever.getFrameAtTime(0)` for the queue poster → push `StagedItem.Video`. Audio mode is frozen at `start()`, so `onToggleVideoAudio` ignores mid-record taps; the modality pill also disables during `Recording`, so VIDEO and VOICE can't contend for the mic.
- **VOICE** (two taps): first tap → `VoiceController.startRecording` via `MediaRecorder` (AAC-LC m4a, 48kHz mono, 96kbps). If `RECORD_AUDIO` is missing, the VM surfaces `micPermissionNeeded` (shared with VIDEO) which triggers the runtime permission launcher from `CaptureScreen`. Order-log at start. Second tap → `stopRecording` → push `StagedItem.Voice` with duration from `SystemClock.elapsedRealtime` deltas. Amplitude polling via `MediaRecorder.getMaxAmplitude()` at ~30Hz feeds the `VoiceOverlay` waveform.

No SAF writes in Phase 1 for any modality. `BusyState { Idle, Capturing, Recording, Rebinding }` is the single source of truth — transitions are flipped synchronously on Main *before* launching the async coroutine so rapid double-taps can't race into duplicate starts.

**Phase 2 — Commit (single frame).** User swipes the queue. `CaptureViewModel.onCommitBundle` pivots the UI synchronously on Main — nulls `currentSession`, clears `queue` + `dividers` — so the shutter is ready on the very next frame. The actual bookkeeping (allocate bundle IDs, write [`PendingBundle`](./app/src/main/java/com/example/bundlecam/pipeline/PendingBundle.kt) manifests to disk with `orderedItems: List<PendingItem>` polymorphic sealed list, enqueue `BundleWorker`s) runs in a background coroutine. If the process dies before the manifest is saved, staged files are still on disk and `OrphanRecovery` restores the session as a queue on next launch.

**Phase 3 — Worker (seconds–tens of seconds, off the UI thread).** `BundleWorker` loads the manifest, calls the pure companion `planRouting(manifest)` to partition `orderedItems` into photos / videos / voices with 1-based **global capture-order indices** (a mixed `[P, V, A, P]` queue produces `-p-001, -v-002, -a-003, -p-004` filenames — sparse per modality, dense across). Then:

- **Photos** (if `saveIndividualPhotos`): bounded 2s GPS refresh → stamp per-file EXIF `UserComment`s (`Recon:{bundleId}:pNNN`) in a single open/save → SAF copy into `bundles/{id}/photos/`.
- **Videos** (always): SAF copy into `bundles/{id}/videos/`. No EXIF — MP4 carries rotation in the `tkhd.matrix` set by CameraX `Recorder` at start-time.
- **Voices** (always): SAF copy into `bundles/{id}/audio/`. No EXIF.
- **Stitch** (if `saveStitchedImage` *and* photos list non-empty): [`Stitcher`](./app/src/main/java/com/example/bundlecam/pipeline/Stitcher.kt) produces one vertical JPEG into `stitched/{id}-stitch.jpg`. **Photo-only** — video frames are never pulled into the stitch, and voice-only / video-only bundles skip stitching entirely (no empty stitch file).

Then deletes the staging session and manifest file. A process-wide `Mutex` (`workMutex`) serializes all workers so stitch heap stays bounded — I/O-bound video copy is currently serialized under the same mutex; splitting is a deferred perf refactor.

### Resilience

- **Queue survives app kill across all three modalities**. Photos are on internal storage the moment shutter fires; video files are continuously flushed by CameraX's Media3 Muxer so a mid-record kill typically leaves a playable MP4; voice files are the common torn-file case because `MediaRecorder` has no equivalent. On next launch, [`OrphanRecovery`](./app/src/main/java/com/example/bundlecam/pipeline/OrphanRecovery.kt) prunes completed `WorkInfo`, re-enqueues any pending manifests without in-flight work, filters the staging session for jpg/mp4/m4a files (skipping dot-files + zero-byte), validates mp4/m4a playability via `MediaMetadataRetriever.extractMetadata(DURATION)` (null → torn → deleted), and restores the queue as sealed `RestoredItem { Photo, Video, Voice }`.
- **Queue ordering across mixed media** comes from a per-session `.order` append-log (`{systemClockMs}\t{filename}\n`) rather than filesystem mtimes — video/voice `lastModified` is *stop()* time, not *start()* time, so a mtime sort would scramble a `[V, P, A]` capture into `[P, A, V]`. The log is appended at start-of-recording (before the file even exists) so a torn file still has a correctly-ordered entry that gets filtered by existence check on restore. Pre-Phase-D sessions without a log fall back to mtime sort.
- **Bundle ID is allocated atomically** from DataStore before any SAF I/O — if the worker fails partway, the counter doesn't rewind.
- **Worker failures are observable**: `WorkScheduler.observeFailures()` emits a flow of failures, filtering out stale pre-existing failures on first subscription (see the `acknowledged` set) so replayed history doesn't spam the user.

---

## Module layout

Package root: `com.example.bundlecam`. All files live under `app/src/main/java/com/example/bundlecam/`.

### Entry point
- [`MainActivity.kt`](./app/src/main/java/com/example/bundlecam/MainActivity.kt) — sets up Compose, observes `SettingsRepository.settings`, routes between `FolderPickerScreen` (first run), `CaptureScreen` (primary), `BundlePreviewScreen`, and `SettingsScreen` via a `when`-chain on two `rememberSaveable` flags.
- [`ReconApp.kt`](./app/src/main/java/com/example/bundlecam/ReconApp.kt) — Application subclass, constructs `AppContainer`, provides `WorkManager.Configuration`.

### `ui/capture/` — the capture screen
- `CaptureScreen.kt` — top bar (settings, `ModalityPill`, **photo-library icon opening the Bundle Preview screen**), viewfinder Box wrapped in `Modifier.draggable` for horizontal modality-swipe, zoom, modality-aware control row, queue strip, overlays. Permissions: camera on mount, GPS in a `LaunchedEffect(Unit)` after camera grants, `RECORD_AUDIO` lazily via `voicePermissionNeeded` signal from the VM. `LifecycleEventEffect(ON_PAUSE)` stops any in-flight video/voice recording. Controls are per-modality:
    - **PHOTO**: flash + photo shutter + lens-flip + zoom + visible camera preview.
    - **VIDEO**: lens-flip + video shutter + zoom + visible camera preview. Flash hidden (no torch wiring).
    - **VOICE**: only the voice shutter. Flash + lens-flip + zoom hidden. Camera preview `alpha=0` so the session stays bound (instant switch-back) but invisible — replaced by `VoiceOverlay` scrim + waveform.
    - During Recording: modality pill locks, flash + lens-flip dimmed.
- `CaptureViewModel.kt` — state machine (`CaptureUiState`, `BusyState { Idle, Capturing, Recording, Rebinding }`, `Modality { PHOTO, VIDEO, VOICE }`, `PendingDiscard`), events (`BundlesCommitted`), per-modality shutter flow (`onShutterPhoto` / `onShutterVideo` / `onShutterVoice`), commit, discard, undo, delete, reorder, zoom, `setModality` (rejected during non-Idle states), divider insert/remove, **lens flip** / **flash cycle** (photo/video only), `onLifecyclePaused` (routes through the normal async stop flows so VM state cleans up properly on resume), `onVoicePermissionResult`, and `onDismissGestureTutorial(shownStepIds: Set<String>)` — merges into `seenTutorialSteps` so future tutorial additions don't re-trigger previously-dismissed steps. `cameraMode` is a `StateFlow` projection from `settings.map { it.cameraMode }`. Sync flip to `BusyState.Capturing` BEFORE launching async coroutines is the race-prevention gate for rapid double-taps.
- `DividerOps.kt` — pure functions for queue-divider arithmetic (`partitionByDividers`, `remapDividersAfterDelete`), generic over `List<T>` — works unchanged on the mixed `List<StagedItem>`.
- `CameraPreview.kt` — wraps `PreviewView`, tap-to-focus, pinch-to-zoom, lifecycle rebind. **Custom gesture handlers that do NOT consume the pointer down** (bespoke `awaitEachGesture { awaitFirstDown() ; waitForUpOrCancellation() }` rather than Compose's stock `detectTapGestures` / `detectTransformGestures`) — the stock handlers call `down.consume()` immediately which starves the ancestor `Modifier.draggable` of the modality-swipe gesture.
- `ShutterButton.kt` / `VideoShutterButton.kt` / `VoiceShutterButton.kt` — sibling composables picked via `when (modality)`. All share the 80dp outer ring + identical hit target; inner fill morphs via `animateDpAsState`: photo = white fill, video = 42dp red circle ↔ 28dp red stop-square, voice = 58dp white-with-mic ↔ 28dp red stop-square.
- `VoiceOverlay.kt` — full-coverage dark scrim + mic glyph + prompt text when idle; pulsing red dot + "Recording…" label + scrolling amplitude-bar waveform (Canvas, 128-sample ring buffer fed by `VoiceController.amplitudeFlow` via direct `StateFlow.collect` — an earlier `snapshotFlow { amplitudeFlow.value }` wrapper never re-emitted past the initial 0 because `StateFlow.value` isn't a Compose-observable state) + `M:SS` elapsed-time caption under the waveform when recording.
- `RecordingTimer.kt` — `VideoRecordingPill`: red capsule + pulsing white dot + `M:SS` caption, ticked every 500ms. Rendered BottomCenter-aligned inside the preview slab Box during a live video recording so the pill overlays the preview without pushing the zoom chips / shutter row / queue down. When `audioEnabled` is true, a `VideoAudioWave` (4 white vertical bars in a 20×12dp Canvas) is appended on the right as a visual hint that the clip carries an audio track; the wave's per-bar height is `floor + sineEnv * sqrt(amplitude)` driven by a continuously-restarting 520ms phase ramp with irregular offsets `[0, 0.18, 0.55, 0.78]`, fed by the smoothed `videoAudioAmplitudeFlow`. The Row uses `wrapContentSize` semantics, so the pill stays horizontally centered as the wave widens it.
- `ModalityPill.kt` — 3-segment pill `VIDEO · PHOTO · VOICE` in the top bar center. Replaces the previous `CameraModeToggle` (EXT/ZSL moved to Settings as `cameraMode` preference). The `Segment` private composable handles four visual states: enabled + selected (white pill + black text), enabled + unselected (transparent + white text), disabled + selected (dimmed-white pill + dark text — recording-state current indicator), disabled + unselected (transparent + dimmed text).
- `ModalitySwipeMath.kt` — pure `object` with `resolveTarget(current, dragDp, velocityDpPerSecond, thresholds): Modality`. Sign-matched drag + velocity commit criteria, no wrap from VIDEO/VOICE endpoints. Unit-testable via `ModalitySwipeMathTest`.
- `ZoomControl.kt` — 0.5×/1×/2×/5× chips, filtered by hardware zoom range. Rendered at `alpha=0` in VOICE (keeps layout height constant across modality switches).
- `QueueStrip.kt` — two-sided edge-zone swipe to commit / discard, plus per-gap `DividerZone` swipe-down-to-insert / swipe-up-to-remove. Gesture state machine, `VelocityTracker`, haptic edge detection, tide gradient, destination glyph + commit flash. `queue: List<StagedItem>` (post-A1 generic over the sealed item hierarchy). `EdgeZone` widths queue-size-aware.
- `QueueThumbnail.kt` — vertical drag-to-delete + long-press-then-drag reorder per thumbnail. `when (item)` branches: photo = raw bitmap; video = poster + play-glyph overlay + duration badge; voice = tinted backdrop + mic icon + duration badge.
- `UndoToast.kt` — "Discarded N items · Undo" during the 3-second undo window.
- `BundleSavedShimmer.kt` — green pill triggered by `BundlesCommitted`: "Bundle {id} saved" for single-bundle commits, "N bundles saved ({first}–{last})" for multi-bundle splits.
- `CaptureColors.kt` — commit green (`#2E7D32`) + discard amber (`#B26A00`).
- `GestureTutorial.kt` — first-run overlay. `TutorialStep` enum has 6 entries each with a stable string ID (`modality, commit, discard, deleteOne, reorder, divide`). `GestureTutorial(seenStepIds: Set<String>, onDismiss: (Set<String>) -> Unit)` filters `TutorialStep.entries - seenStepIds` and renders nothing if empty. Upgraders who had the pre-multimodal v1 Boolean see only the new `modality` step seeded by the upgrade path in `SettingsRepository`. Demo pinned at the bottom 72dp. Settings' "Gesture tutorial → Show" row clears `seenTutorialSteps` and pops to capture so the overlay re-appears.

### `ui/setup/` — first-run folder picker
- `FolderPickerScreen.kt` — shown while `settings.rootUri == null`; launches `OpenDocumentTree` and persists SAF permissions.

### `ui/preview/` — in-app bundle browser
- `BundlePreviewScreen.kt` — Material 3 `Scaffold` with a contextual top bar that swaps between two modes: default (back left, "Bundles" title, `FolderOpen` action on the right that defers to the system file browser via `openFolderInSystemBrowser`) and selection-mode (close-X left, "N selected" title, paper-plane Send action on the right). Body is a `LazyColumn` that renders `ProcessingBundleRow`s first (for in-flight workers whose SAF files haven't landed yet), then `BundleRow`s for completed bundles, with `HorizontalDivider` between all rows. `BackHandler(state.selectionMode)` clears the selection in selection-mode (the default-mode back goes via the parent screen). The Send action opens `LocalSendSheet` for the selected bundles.
- `BundlePreviewViewModel.kt` — `BundlePreviewUiState { bundles, loadState, errorMessage, pendingDeletes, thumbnails, processingBundleIds, selectedBundleIds: Set<String> }` plus a derived `selectionMode = selectedBundleIds.isNotEmpty()`. Selection is gated by the pure companion `isSelectable(bundleId, state)` (must be in the completed list, not in pendingDeletes, not in processingBundleIds). `toggleSelection` / `clearSelection` mutate the set; transitions that take a bundle out of selectable state (e.g. swipe-to-delete) auto-deselect. `selectedBundlesSnapshot()` returns the live `List<CompletedBundle>` for the contextual app bar's send action. `init` collects `WorkScheduler.observeActiveBundleIds()` and keeps `processingBundleIds` in sync — when the active set shrinks, it `loadBundles()` *first* (so the completed row is in place) and *then* drops the IDs, avoiding a blank-gap flash. `loadThumbnail(uri)` decodes async with a `Mutex`-guarded in-flight set to dedup concurrent requests. `onConfirmDelete` / `onUndo` and `onCleared` (flushes still-pending deletes via `container.appScope`) are unchanged from the pre-selection design.
- `BundleRow.kt` — the list item. Four states share the row skeleton (`Modifier.bundleRowLayout()` with `heightIn(min = 68.dp)` so neighbours don't shift):
    - **Normal**: up to 3 thumbnails (128dp fixed-width strip), monospace bundle id, mixed-modality subtitle, modality icons. Two-direction swipe — left to delete, right to select — handled by a single `pointerInput` block whose `onDragEnd` consults the pure helper `BundleRowSwipe.computeRelease(...)` to pick (target offset, action). Live drag uses `mutableFloatStateOf`; release animation drives `Animatable`. Haptic tick on the would-fire-on-release predicate's edge crossings. The `processing` flag (set when the bundle id is also in `processingBundleIds` — the brief overlap window between worker finish and SAF re-scan) skips the `pointerInput` entirely so the row is interaction-frozen until the worker fully unwinds.
    - **Selected** (snap-position): swipe-right snaps the row to a fixed 80dp reveal width, exposing a green strip with a `CheckCircle` icon; tap the strip or swipe-back-to-0 deselects. The row's resting offset *is* its selected state — no separate tint. The selection reveal is a `BoxScope.SelectionRevealZone` with the click target gated on `tappable = selected` so a mid-drag tap doesn't fire a stray toggle. An `animatingExternally` flag suppresses drag handling while the LaunchedEffect on `selected` is animating the row back from a contextual-app-bar clear-all, so a follow-through finger motion right after tapping the X doesn't re-select.
    - **Pending-delete**: same thumbnail strip, title replaced by "Deleting in Xs" (ticked via `derivedStateOf` so recomposition only fires on whole-second boundaries), modality icons replaced by an Undo `TextButton`. `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)` around the button suppresses Material's 48dp touch-target wrapping.
    - **Processing** (`ProcessingBundleRow`): rendered at the top of the list for any bundle id whose worker is ENQUEUED/RUNNING/BLOCKED. `CircularProgressIndicator` in the leading thumb slot, "Processing…" subtitle. Not swipeable.
- `LocalSendSheet.kt` — `ModalBottomSheet` that opens with the selected bundles. Three internal states: `Discovering` (timed-out flag for a 12-second "no peers found" fallback with Try again / Close), `Sending(peerAlias, bundleCount, progress)`, `Done(bundleCount, totalFiles, totalBytes, peer, result)`. `runSend` captures the latest `SendProgress` in a local `var` and feeds its `totalFiles` / `totalBytes` into `Done` on completion, so the success page can show the file count and aggregate byte size without re-walking `bundleLibrary.listBundleFiles`. The shared `Header` early-returns on `Done` — the success state has its own self-contained layout (centered icon-led `DoneBlock`) so the standard title/subtitle pair would just duplicate it. `DoneBlock` renders three lines under a 72dp filled status icon (`CheckCircle` for Success, `Info` for AlreadyReceived, `Error` for Failed): a `titleLarge` headline (`{N} bundle(s) ({F} files, {Y.Y MB})` for Success — the byte formatter is base-1000 with `Locale.US` to keep `.` as the decimal separator regardless of device locale), a `bodyMedium` recipient line (`Sent to {alias}`), and a `bodyMedium` identity line (`{deviceModel} · {fingerprint.take(8)}` mirroring the format used in `PeerListBlock`). The primary action is a full-width `FilledTonalButton("Done")` — TextButton in the right gutter read as a dismissive Cancel-style affordance, which doesn't match this being the only CTA on the sheet. The sheet's `closeAndDismiss` calls `onDismiss()` synchronously and offloads the discovery / send teardown to `container.appScope` so the modal scrim stops blocking touches the moment the user taps Done — the scope captured by the sheet's `rememberCoroutineScope` is cancelled the instant the parent removes the sheet from composition, so anything that needs to *survive* dismissal (in particular `stopDiscovery`'s socket close) has to run on a process-scoped scope.

### `ui/common/` + `ui/theme/`
- `FolderPicker.kt` — reusable SAF launcher.
- `FolderIntent.kt` — `openFolderInSystemBrowser(context, treeUri)`, the `ACTION_VIEW` launcher used by the Bundle Preview screen's action bar.
- `TimedSlot.kt` — generic single-slot "hold item, run cleanup on timeout unless `take()` wins first". Used by both the capture-time discard-undo (`TimedSlot<StagingSession>`) and could be reused elsewhere. The pending-delete map in the preview VM does NOT use this because it needs multiple concurrent slots keyed by bundle id — a direct `MutableMap<String, Job>` is clearer there.
- `ActionBanner.kt` — dismissible pill used for `state.lastError`. Text is capped at `maxLines = 2` with ellipsis so it fits as an overlay on the 72dp queue strip without ballooning its host.
- `Theme.kt` / `Color.kt` / `Type.kt` — Material 3 with dynamic color on Android 12+.

### `ui/settings/`
- `SettingsScreen.kt` — output folder (change via SAF picker; URI wraps; Change button anchored top-right), "Bundle output" block with per-output toggles (`Individual photos in subfolder` / `Vertical stitched image` — the UI locks the only-on switch so ≥1 is enforced, with a caption surfaced only while a lock is active), **Stitch Quality** dropdown nested under the stitched toggle (Material 3 `DropdownMenu`, indented 16dp, disabled when stitching is off), shutter sound toggle, **"Confirm before deleting a bundle"** switch, **"Undo window for bundle deletion"** dropdown (Off / 1s … 10s; 0 means delete immediately with no undo prompt), **"Gesture tutorial → Show"** row that clears `seenGestureTutorial` and pops back to capture so the `GestureTutorial` overlay re-appears (the suspending DataStore write is awaited before `onBack()` so there's no visible lag between tap and overlay). All interactive controls are right-aligned; label + control rows share a `SettingsRow` helper. Labels use `titleSmall` (parent rows) / `bodyMedium` (nested child rows) for consistent hierarchy.

### `data/camera/`
- `CaptureController.kt` — owns `ProcessCameraProvider` + `ExtensionsManager` + `VideoCapture<Recorder>`. Binds `Preview + ImageCapture + VideoCapture` concurrently under `bindMutex` (try/catch falls back to photo-only if the device rejects the triple-bind — `combinedBindSupported` flag). `OrientationEventListener` snaps to cardinal and writes to BOTH `imageCapture.targetRotation` and `videoCapture.targetRotation` — Recorder freezes its rotation at `start()` automatically, so mid-record writes are harmless no-ops. `takePicture()` (photo), `startVideoRecording(outputFile, audioEnabled)` + `stopVideoRecording(): RecordingResult` (with CompletableDeferred awaiting `VideoRecordEvent.Finalize`; `withAudioEnabled()` is called only when both `audioEnabled` is true and RECORD_AUDIO is granted, with a `SecurityException` fallback to silent recording). The same `VideoRecordEvent` listener handles `Status` events: pulls `recordingStats.audioStats.audioAmplitude` into `videoAudioAmplitude: StateFlow<Float>` (range `[0, 1]`, reset to 0 at start and on `Finalize`) so the recording pill can render a live audio-level wave. `setFlashMode(FlashMode.Off/Auto/On)` mutates live `ImageCapture.flashMode`. `bind()` re-reads `currentFlashMode` post-install so writes interleaved with a rebind still land on the new capture.
- `BitmapUtils.kt` — `Bitmap.rotateIfNeeded(degrees)` extension (shared by thumbnail decode and stitching).
- `ThumbnailDecoder.kt` — dispatches on filename extension:
    - `.jpg` / `.jpeg` → BitmapFactory with `inSampleSize`, EXIF rotation. Four overloads: in-memory bytes (capture-time), file path (orphan-recovery), `ContentResolver + Uri` (Bundle Preview from SAF), plus internals.
    - `.mp4` → `decodeVideoPoster`: `MediaMetadataRetriever.getFrameAtTime(0)`, scaled to thumbnail size. File variant (orphan-recovery) and SAF URI variant (Bundle Preview, via `ParcelFileDescriptor`).
    - `.m4a` → `renderVoiceThumbnail`: hand-drawn Canvas bitmap (navy fill + white mic glyph — capsule + arch + post + base). No external icon resource.
    - `decodeVoiceThumbnail` — a tiny 8×8 placeholder for in-queue `StagedItem.Voice` entries (the queue's Voice branch overlays its own mic icon + duration on top, so it just needs a non-null `ImageBitmap`).
    - `isMediaFileReadable(file)` — torn-file probe for both mp4 and m4a (`MediaMetadataRetriever.extractMetadata(DURATION)` → null means unreadable → OrphanRecovery deletes).

### `data/audio/`
- `VoiceController.kt` — wraps `MediaRecorder`. Config: `AudioSource.MIC`, `OutputFormat.MPEG_4`, `AudioEncoder.AAC`, 48kHz mono, 96kbps. `hasPermission()`, `startRecording(file): VoiceRecordingResult` (sealed: `Success / Error / PermissionDenied / NotRecording` — `PermissionDenied` is distinct from `Error` so the VM triggers the permission launcher rather than surface the error banner), `stopRecording()` returns duration from `SystemClock.elapsedRealtime` deltas. An internal `amplitudeScope` launches a coroutine that polls `MediaRecorder.getMaxAmplitude()` every 33ms while recording, exposing `amplitudeFlow: StateFlow<Int>` for the `VoiceOverlay` waveform (feeds a 128-sample ring buffer).

### `data/exif/`
- `ExifWriter.kt` — two entry points: `stamp(...)` at capture time (DateTimeOriginal, orientation, Make/Model, GPS-if-cached); `stampFinalMetadata(file, comment, backfillLocation)` at commit time in one open/save pass (bundle-ID UserComment, plus GPS if the photo doesn't already carry it).
- `OrientationCodec.kt` — canonical conversions between device degrees, `Surface.ROTATION_*`, and ExifInterface orientation tags.

### `data/location/`
- `LocationProvider.kt` — Fused Location with a 30s TTL cache (refresh threshold 15s), mutex-guarded, returns null on permission denial or network failure.

### `data/settings/`
- `SettingsRepository.kt` — DataStore-backed `SettingsState { rootUri, stitchQuality, shutterSoundOn, saveIndividualPhotos, saveStitchedImage, deleteDelaySeconds, deleteConfirmEnabled, seenTutorialSteps: Set<String>, cameraMode: CameraMode }`, exposed as a distinct-until-changed `Flow`. Sanitizes on read so a `(false, false)` output pair can never reach the worker. `deleteDelaySeconds` is clamped to `MIN_DELETE_DELAY_SECONDS..MAX_DELETE_DELAY_SECONDS` (0–10); `deleteConfirmEnabled` defaults to `true`. `cameraMode` is the demoted EXT/ZSL preference — projected into `CaptureViewModel.cameraMode` as a `StateFlow` and live-applied on change (CameraPreview's `LaunchedEffect(mode)` rebinds under `bindMutex`); it's never frozen into a manifest. `seenTutorialSteps` replaced the old Boolean `seenGestureTutorial`: the filter `TutorialStep.entries - seenTutorialSteps` determines which steps to show. Upgrade path: if the legacy key is true on first read, the set is seeded with `V1_TUTORIAL_STEP_IDS = {"commit", "discard", "deleteOne", "reorder", "divide"}` at read time — pure read-side, no DataStore write needed.

### `data/storage/`
- `StagingStore.kt` — internal staging (`filesDir/staging/session-{uuid}/`). `writePhoto(session, bytes)` for photos; `allocateVideoOutput(session)` / `allocateVoiceOutput(session)` return file handles that CameraX `Recorder` / `MediaRecorder` stream into directly (never buffered in RAM). `appendOrderEntry(session, filename)` writes `{systemClockMs}\t{filename}\n` to the session's `.order` log at the point the item is logically added to the queue (photo: after `writePhoto`; video/voice: at start-of-recording); `readOrderLog(session)` returns filenames in order, used by `OrphanRecovery` to restore the queue with correct mixed-media ordering. `markDiscarded(session)` / `unmarkDiscarded(session)` / `isDiscarded(session)` manage a `.discarded` marker file — written synchronously on the capture-screen discard swipe so a process death during the 3-second undo window results in cleanup (not orphan-queue restoration) on next launch.
- `SafStorage.kt` — copy staged files to the user-picked SAF tree, ensures `bundles/` and `stitched/` subtrees exist. MIME type derived from filename extension via `StorageLayout.mimeFor` (important: Drive's DocumentsProvider rejects `video/mp4` files created with `image/jpeg`). Batches one `listFiles()` per call so a 50-photo bundle isn't 50 full directory-listing IPCs; overwrites on name collision so worker retries don't produce `" (1).jpg"` duplicates. `findOrCreateDir` retries `findFile` after a null `createDirectory` to survive concurrent-creator races.
- `BundleLibrary.kt` — read + delete operations for the Bundle Preview screen, plus the file-enumeration helper for LocalSend. `listBundles(rootUri)` parallelizes per-bundle `listFiles()` calls via `coroutineScope { async }.awaitAll()`. Prefers the nested `photos/` subdir for new bundles, falls back to flat layout for legacy pre-Phase-B bundles. `listBundleFiles(bundle): List<BundleFile>` is the shippable-file enumerator the LocalSend uploader consumes — yields each file with its `subfolder` field set to `"photos"` / `"videos"` / `"audio"` / `"stitched"` / `""` (legacy flat) so the uploader can compose `{bundleId}/{subfolder}/{leafName}` wire paths that the receiver materializes back into the bundle's directory layout. `deleteBundle(bundle)` uses `DocumentsContract.deleteDocument(resolver, uri)` directly on each modality URI.
- `BundleFile.kt` — `data class BundleFile(uri, fileName, size, mimeType, subfolder)`. Used by both the LocalSend sender and any other bundle-shipping path.
- `CompletedBundle.kt` — data class returned by `BundleLibrary`: id, modality list (`BundleModality.Subfolder | .Stitch`), subfolder/stitch URIs, thumbnail URIs, `photoCount`, `videoCount`, `voiceCount`.
- `StorageLayout.kt` — canonical naming: bundle IDs (`{date}-s-{0000}`), photo filenames (`-p-{NNN}.jpg`, 3-digit so lexicographic sort holds past 99 items), video filenames (`-v-{NNN}.mp4`), audio filenames (`-a-{NNN}.m4a`), stitched filename (`-stitch.jpg`). Per-modality subfolder constants (`PHOTOS_SUBDIR` / `VIDEOS_SUBDIR` / `AUDIO_SUBDIR`). `mimeFor(fileName)` for `SafStorage.createFile`. EXIF UserComment format (`Recon:{id}:pNNN` / `:stitch` — photo-only).
- `BundleCounterStore.kt` — per-date monotonic counter via DataStore; `allocate()` returns the next ID and resets on date change.

### `pipeline/`
- `PendingBundle.kt` — kotlinx-serializable manifest with `version: Int = 2`: `bundleId`, `rootUriString`, `stitchQuality`, `sessionId`, `orderedItems: List<PendingItem>`, `capturedAt`, and output-mode flags `saveIndividualPhotos` / `saveStitchedImage` (frozen at commit so a settings change mid-flight doesn't alter an already-queued worker; defaults `true` for backward compat). `PendingItem` is a sealed class with subtypes `PendingPhoto` (`@SerialName("photo")`, `localPath + rotationDegrees`), `PendingVideo` (`@SerialName("video")`, `localPath + rotationDegrees + durationMs`), `PendingVoice` (`@SerialName("voice")`, `localPath + durationMs`).
- `ManifestStore.kt` — writes / reads / deletes `filesDir/pending/{bundleId}.json`. `Json` config has `encodeDefaults = true` (so `version: Int = 2` actually gets written), `classDiscriminator = "type"`, and a SerializersModule registering the polymorphic subclasses. `load()` branches on the `version` field: v1 (legacy `orderedPhotos` shape) is hand-decoded into the v2 `orderedItems` structure; v2 decodes polymorphically. Unknown future versions return null with a log error. The migration is read-only — no DataStore write needed.
- `WorkScheduler.kt` — enqueues `BundleWorker` with a per-bundle unique work name (`KEEP` policy), exposes `observeFailures()` that filters stale pre-existing failures, `observeActiveBundleIds()` returning the set of bundle ids in ENQUEUED/RUNNING/BLOCKED state (extracted from each `WorkInfo`'s `bundle_{id}` tag — tags outlive input `Data` across state changes), plus `pruneWork()` for orphan recovery.
- `BundleWorker.kt` — CoroutineWorker; process-wide `Mutex` serializes all workers. Reads manifest, then calls the pure companion function `planRouting(manifest): RoutingPlan` which partitions `orderedItems` into `photos: List<IndexedItem<PendingPhoto>>`, `videos: List<IndexedItem<PendingVideo>>`, `voices: List<IndexedItem<PendingVoice>>` with 1-based **global capture-order indices** (position in `orderedItems`). Branches then run independently:
    - `saveIndividualPhotos && photos.isNotEmpty()`: GPS refresh + per-photo final-EXIF stamp + SAF copy to `bundles/{id}/photos/`.
    - `videos.isNotEmpty()`: SAF copy to `bundles/{id}/videos/` (no EXIF, MP4 carries rotation in its container).
    - `voices.isNotEmpty()`: SAF copy to `bundles/{id}/audio/` (no EXIF).
    - `saveStitchedImage && photos.isNotEmpty()`: `Stitcher` + stitched EXIF stamp + SAF write to `stitched/`.

    Manifest + staging cleanup always runs on success.
- `Stitcher.kt` — **photo-only**. Computes common width (min source width, clamped by `StitchQuality` ceiling: 1600 / 1800 / MAX px), scales heights proportionally, enforces a 32k-px total-height cap and a 60%-of-heap budget, decodes each source with `inSampleSize` to fit its slot, rotates, draws into a single canvas, compresses JPEG at 70 / 82 / 92. Layout math is a pure `companion` function (`computeLayout`) so tests can exercise it without any Bitmap allocation. Never sees videos or voices.
- `OrphanRecovery.kt` — sealed `RestoredItem { Photo, Video, Voice }`. Runs at `AppContainer` init: prunes terminal WorkInfo; re-enqueues manifests without in-flight work; partitions staging sessions into `discarded` vs. `live`, deletes the discarded ones, returns the most-recent live orphan (if any) to `CaptureViewModel.init` as a mixed-media restored queue. File filter: `.jpg` / `.mp4` / `.m4a`, skip dot-files + zero-byte. mp4/m4a files are validated via `isMediaFileReadable` (retriever duration probe) — torn files are deleted. Queue order comes from `StagingStore.readOrderLog()` when present, mtime-sort fallback for pre-Phase-D sessions. Deletes stale older live orphan sessions to prevent unbounded disk growth.

### `network/localsend/` — peer-to-peer bundle transfer (LocalSend protocol v2.1)

A clean-room Kotlin **sender** (no inbound HTTP server) for the [LocalSend protocol](https://github.com/localsend/protocol). One controller, one base OkHttp client, per-peer SSL config layered on with `client.newBuilder()` so the connection pool / dispatcher / timeouts are shared.

- `LocalSendController.kt` — top-level coordinator. Holds the `baseClient: OkHttpClient` (no SSL config), the `LocalSendDiscovery` singleton, and a lazy `LocalSendUploader`. `discover(scope)` returns a `Flow<Peer>`; `send(peer, bundles, onProgress)` accepts a `List<CompletedBundle>` and ships every selected bundle's files in **one** prepare-upload session. The `Info` block (alias + deviceModel + fingerprint) is built once on first call and Mutex-cached for the controller's lifetime — alias lookup hits DataStore, fingerprint hex hits `LocalSendCertManager` which lazily generates the per-install cert on first call.
- `LocalSendDiscovery.kt` — multicast send/listen on `224.0.0.167:53317`. `start()` is suspending and the whole bring-up runs `withContext(Dispatchers.IO)` because socket bind / `joinGroup` / send are blocking JVM calls and the upstream caller (`LaunchedEffect` in the sheet) is on Main; running them on Main trips Android's `NetworkOnMainThreadException`. Acquires a `WifiManager.MulticastLock` (without it inbound multicast is filtered to silence on most devices), joins the group on every non-loopback IPv4 NIC, sends one announce, runs the receive loop on its own coroutine. Receive loop uses `currentCoroutineContext().isActive` (NOT `scope.isActive` — the parent scope stays active when the child receiveJob is cancelled, so a `cancelAndJoin` would hang forever waiting for the loop to exit). Lifecycle is gated by a suspending `Mutex` so a tight stop-then-start sequence finishes the socket close before the next bind tries the same port. Pure-function helpers in the companion: `parseAnnounce` (filters self-echoes by fingerprint, drops malformed JSON, drops non-HTTPS peers — the spec says HTTP peers' fingerprint is just a random string, so we can't authenticate them), `announceToPeer`, `multicastInterfaces`.
- `LocalSendUploader.kt` — the per-session protocol implementation. `send(peer, info, items, onProgress)`:
    1. Builds a peer-specific `OkHttpClient` via `baseClient.newBuilder().sslSocketFactory(...)` whose trust manager is a `FingerprintPinningTrustManager` for *this* peer's announced fingerprint.
    2. `POST /api/localsend/v2/prepare-upload` with `info` + a `files` map keyed by wire fileName (`{bundleId}/{subfolder}/{leaf}`).
    3. If the response omits tokens for any requested file, sends `/cancel` and fails with `"Receiver dropped N file(s); refusing to send a partial bundle"` — silent skip would be data loss disguised as success.
    4. Per-file `POST /api/localsend/v2/upload?sessionId&fileId&token` with the raw bytes streamed via a custom `RequestBody.writeTo(sink)` that pulls from `contentResolver.openInputStream(uri)` in 64 KB chunks (never `readBytes()` on a 20 MB stitched JPEG) and polls the calling Job's `isActive` between chunks so cancellation aborts at the next chunk boundary instead of waiting for OkHttp's socket-close to unwind.
    5. Parallel uploads cap at 3 via a `Semaphore`. Progress callbacks throttle to 50 ms (chunk callbacks fire ~120 events/s peak across the 3 parallel files); lifecycle moments (start, file completion, terminal state) force-emit via a `force = true` flag.
    6. On `CancellationException` / HTTP errors / `IOException`, sends `/cancel` best-effort.
    Pure companion helpers: `wireNameFor(bundleId, file)` composes the receiver-side path; `buildPrepareUploadRequest(items, info)` produces the request payload — both unit-tested without an Android dep.
- `LocalSendTrustManager.kt` — `FingerprintPinningTrustManager` extends `X509ExtendedTrustManager` (NOT just `X509TrustManager`) so the JDK doesn't wrap us in `AbstractTrustManagerWrapper` which adds its own hostname-check against the cert's CN — incompatible with our per-install certs whose CN is "Recon" rather than the peer's IP. The fingerprint check runs *inside* `checkServerTrusted` during the TLS handshake: if the leaf cert's SHA-256 hex matches the announced fingerprint, return (session is authenticated, `peerCertificates` populates normally); otherwise throw `CertificateException`. Doing the check post-handshake in an OkHttp interceptor reading `response.handshake.peerCertificates` was the original design and silently dropped certs because Conscrypt on Android marks the session as unverified when the trust manager is a no-op, which makes `SSLSession.getPeerCertificates()` throw and OkHttp's `Handshake.get` swallow it into an empty list. Also exports a no-op `LocalSendHostnameVerifier` (peers are reached by IP; identity is enforced by fingerprint, not hostname).
- `LocalSendCertManager.kt` — per-install self-signed cert, lazily generated via `okhttp-tls`'s `HeldCertificate` and cached in `filesDir/localsend/cert.pem` (PEM-encoded leaf cert + PKCS#8 private key concatenated). 100-year validity (fingerprint pinning makes expiry checks moot). Pure companion `computeFingerprintHex(derBytes)` returns lowercase hex SHA-256.
- `LocalSendDtos.kt` — every wire shape (`Announce`, `RegisterRequest/Response`, `Info`, `FileMetadata`, `FileTimestamps`, `PrepareUploadRequest/Response`) as `@Serializable` data classes. `LocalSendJson` config: `encodeDefaults = true` (so the `version` field always lands on the wire even though it has a default), `ignoreUnknownKeys = true` (forward-compat for v2.2+), `explicitNulls = false` (omit nullable fields rather than emit `"deviceModel": null` — matches the spec's example payloads).

The protocol's session lifecycle on the *receiver* is interactive: a session stays "active" until the user dismisses the receive panel on their desktop, *not* on file-count completion. So back-to-back prepare-upload calls to the same peer would 409 BLOCKED on the second. We collapse multi-bundle sends into a single session — one prepare-upload, all bundles' files, wire fileNames namespaced by `{bundleId}/...` so the receiver materializes one folder per bundle.

### `di/`
- `AppContainer.kt` — singleton, constructs all `data/` + `pipeline/` + `network/` components (including `BundleLibrary`, `LocalSendCertManager`, `LocalSendController`), provides `configureRoot(uri): Job` which updates `SettingsRepository` then creates the `bundles/` + `stitched/` subtrees. Runs on a public `appScope` (`SupervisorJob + Main.immediate`) rather than a caller-provided composition scope — otherwise a user who pops Settings immediately after picking a folder would cancel `ensureBundleFolders` mid-flight. `appScope` is also used by `BundlePreviewViewModel.onCleared` to flush pending deletes that outlive the VM, and by `LocalSendSheet.closeAndDismiss` to run `stopDiscovery` after the sheet's own scope has been cancelled.

---

## Key design decisions

**Main-thread commit pivot; bookkeeping runs off-thread** — the commit swipe is gated purely on Main-thread state mutations (null `currentSession`, empty `queue`). Bundle-ID allocation (DataStore), manifest serialization (JSON + file write), and worker enqueue (WorkManager DB insert) all run in a background coroutine after the shutter is already ready. If the process dies between the UI pivot and the manifest save, the staging session still has the photos on disk and `OrphanRecovery` restores them as an uncommitted queue on next launch. Net: shutter re-enables within one frame of the swipe — previously 30–100ms of blocked UI.

**Manifest file indirection (vs WorkManager `Data`)** — a 50-photo bundle's photo paths exceed WorkManager's 10KB `Data` input limit. Instead, the VM writes a `PendingBundle` JSON next to the app's internal files; only the `bundleId` goes through `Data`, and the worker loads the rest from disk. This also makes orphan recovery trivial: if the process dies mid-commit, the manifest persists and is replayed on next launch.

**Staging store for resilience** — photos are written to internal storage on capture, not held in memory. A queue of 50 photos costs ~2.5MB of thumbnails, not ~250MB of decoded JPEG. If the process is killed, the staging session persists on disk and `OrphanRecovery` can restore the queue.

**Per-bundle `Mutex` in `BundleWorker`** — stitching a tall image allocates ~60% of heap. Running two in parallel on a mid-range device reliably OOMs. A process-wide `Mutex` forces serial execution; bundles queue up and drain.

**`observeFailures()` filters pre-existing failures** — WorkManager replays historical `WorkInfo` on new `observe()` calls. Without filtering, every app launch would re-surface yesterday's transient bundle failure. The scheduler snapshots the set of failures on first emission as "acknowledged" and only emits fresh failures thereafter.

**Discard marker is synchronous, not coroutine-scoped** — capture-time discard uses a 3-second undo window whose cleanup runs in `viewModelScope`. If the process dies inside that window, the coroutine is cancelled and the staging session files persist, which `OrphanRecovery` would then restore as an "uncommitted queue" — the discarded photos come back. `StagingStore.markDiscarded` drops a `.discarded` marker file *synchronously* on the discard gesture (one `File.createNewFile()` on internal storage, ~1ms — Main-thread-safe), before the timer starts. On next launch, `OrphanRecovery` sees the marker and deletes the session instead of restoring. Undo removes the marker.

**Per-bundle-delete timers are independent** — the Bundle Preview screen's undo window is multi-slot: swiping multiple bundles in quick succession starts separate timers, each hard-deleting on its own expiry. Each row has its own "Undo" button. `BundlePreviewViewModel` uses a `Map<String, Job>` keyed by bundle id (rather than the single-slot `TimedSlot` used by capture-time discard) so no pending delete preempts another.

**Processing rows bridge the worker gap** — the worker takes seconds-to-tens-of-seconds to write a bundle, so a user who commits and immediately navigates to Bundle Preview would otherwise see the newest bundle "missing". `BundlePreviewViewModel` subscribes to `WorkScheduler.observeActiveBundleIds()` and renders a `ProcessingBundleRow` for each in-flight worker at the top of the list. When a bundle leaves the active set (worker finished), the VM refreshes the SAF listing *before* dropping the processing id from state — so the completed row takes over without a blank frame where neither exists. Failed workers simply disappear from the processing set; the existing `observeFailures()` channel surfaces the error on the capture screen.

**Orientation via `OrientationEventListener` + `imageCapture.targetRotation`** — `DisplayRotation` only catches 90°/270° rotations when the activity's `screenOrientation=portrait`. The accelerometer-based listener catches all four, snaps to the nearest cardinal, and feeds the translated `Surface.ROTATION_*` into `ImageCapture` so the **EXIF orientation** tag matches the physical device pose even when the UI doesn't rotate.

**UI counter-rotation (not UI rotation)** — `screenOrientation=portrait` locks the layout. For icons (settings, folder, zoom labels) to feel right-side-up, they get a `rotate(-deviceOrientation)` `Modifier` driven by a cumulative-target `animateFloatAsState` that picks the shortest arc (no 270° spins when going 0° → 90°).

**Gesture model** — the queue strip has three disjoint sibling gesture zones: two `EdgeZone`s at the screen edges (commit / discard via `detectHorizontalDragGestures`) and the tray in between (horizontal scroll + per-thumbnail long-press reorder + vertical drag-to-delete). Because each zone is a separate child of an outer `Box`, Compose's hit-test routes each pointer-down unambiguously — no arbitration, no slop tuning. Commit / discard uses **hybrid thresholds**: commit if `|dragX| >= maxWidth/2` **or** `|velocity| >= 80.dp/s` in the commit direction. Velocity tracking via Compose's `VelocityTracker`. Tide-gradient fills toward the destination edge as progress grows; destination zone shows the accent color + glyph throughout swipe, intensifies on commit, then fades out — driven by `max(destinationAlpha, flashAlpha)` so there's no visual seam between swipe-end and commit animation.

**Queue-size-aware edge zones** — the base `EdgeZone` width is 60dp, but when the tray has empty slack (short queue, thumbs don't fill across) each zone grows by `slack / 2` so the user can start a commit / discard swipe from further inward. Capped at `maxWidth * 0.33f` per side with a `24dp` neutral middle guard — zones can never meet, and on a full tray they collapse back to 60dp. Width changes animate over 180ms linear so a mid-gesture queue-size change (e.g. a burst shot resolving while the user is dragging) doesn't yank the hit region from under the finger. The **destination fill** (tick / cross + commit flash) is decoupled from the input width: it always renders at 60dp anchored to the screen-edge side, so widening the input area doesn't bloat the visual. `systemGestureExclusion()` still goes flush to the screen edge (just over more pixels).

**Edge zones overlap tray, not vice versa** — the `EdgeZone`s are stacked on top of the tray inside a `Box`. The tray is padded 48dp on each side so thumbnails render in the middle 48dp→W-48 slab. `detectHorizontalDragGestures` inside each zone only claims pointers after crossing horizontal touch slop, so taps and vertical drags propagate to thumbnails beneath even when the zone widens into thumbnail territory. Long-press-then-drag reorder still fires from the thumbnail's own `pointerInput` because long-press doesn't move.

---

## Testing

Pure JVM unit tests under `app/src/test/`:

```bash
./gradlew test
```

| Suite | Covers |
|---|---|
| `DividerOpsTest` | `partitionByDividers` (empty queue, single item, out-of-range dividers, multi-dividers) and `remapDividersAfterDelete` (index shift, collapse-on-collision, new-size cutoffs). Generic over `List<T>` so the mixed `List<StagedItem>` works unchanged. |
| `OrientationCodecTest` | cardinal round-trips, `snapToCardinal` boundary angles, `toSurfaceRotation` inverse mapping |
| `StitcherLayoutTest` | quality-ceiling clamping (LOW/STANDARD/HIGH), height-cap scaling at 32k, heap-budget scaling, aspect preservation, min-height floor |
| `StorageLayoutTest` | per-modality filename formats (`-p-NNN.jpg` / `-v-NNN.mp4` / `-a-NNN.m4a`), 3-digit zero-pad, lexicographic sort invariant past 99 items, `mimeFor` per extension, `bundlePhotosPath` / `bundleVideosPath` / `bundleAudioPath` composition |
| `PendingBundleSerializationTest` | v1 legacy manifest (`orderedPhotos`) decodes into v2 shape, empty v1, v2 round-trip with `"type":"photo"` discriminator assertion, future `version > 2` returns null |
| `BundleWorkerRoutingTest` | `planRouting` for photo-only / video-only / voice-only / mixed / empty manifests; global-index assignment (sparse per modality, dense across); metadata flow-through |
| `ModalitySwipeMathTest` | `resolveTarget` — sub-threshold stays put, threshold-crossing commits, velocity shortcut, reverse-flick rejection, no-wrap from VIDEO/VOICE endpoints |
| `BundleRowSwipeTest` | `BundleRowSwipe.computeRelease` decision table — select / deselect / delete commits, fast-flick velocity overrides, direction guards (`offsetX > 0` for select, `< 0` for delete), spring-back rest positions; `clampOffset` per-state bounds; `wouldFireOnRelease` haptic trigger predicate |
| `BundleSelectionLogicTest` | `BundlePreviewViewModel.isSelectable` gates — pendingDelete and processing rows reject selection, completed rows accept |
| `LocalSendDtoSerializationTest` | every wire DTO round-trips, exact spec field names (`fileType` not `mimeType`, `sessionId` not `session_id`), `version` always encoded with `encodeDefaults = true`, nullable omission with `explicitNulls = false`, forward-compat with unknown keys |
| `LocalSendFingerprintTest` | `computeFingerprintHex` known-vector outputs (SHA-256 of empty bytes / `"abc"` / single-byte inputs); 64-char lowercase hex invariants |
| `LocalSendDiscoveryFilterTest` | `parseAnnounce` — valid announces pass, self-echoes filter by fingerprint, malformed JSON returns null without throwing, missing required fields return null, length cap honored, non-HTTPS peers rejected; `announceToPeer` IP/port/protocol mapping |
| `LocalSendUploaderRoutingTest` | `wireNameFor` composes `{bundleId}/{subfolder}/{leaf}` with empty-subfolder fallback; `buildPrepareUploadRequest` keys files by wire path, FileMetadata fields match input, multi-bundle items coexist in one payload, `LinkedHashMap` order preserved |

Manual smoke path for the multimodal capture flow:
1. Fresh install launch → folder picker → choose a test folder → camera + GPS permission prompts → `GestureTutorial` overlay walks through 6 gestures (modality swipe first, then commit/discard/delete-one/reorder/divide); tap Next/Skip through to dismiss. (Regression check: re-open Settings → "Gesture tutorial → Show" should re-trigger.)
2. **Photo capture** (PHOTO modality default): capture 3–5 photos (watch EXIF `DateTimeOriginal`, GPS if permission granted, orientation on a tilted device).
3. **Swipe to VIDEO**: swipe right on the viewfinder. Slab translates with the finger; on release, committed VIDEO continues sliding off and new content appears at center. Pill shows VIDEO; shutter shows the red dot.
4. **Video record**: tap shutter → inner fill morphs to the red stop-square. Record 3 seconds of subject motion. Tap again → stop. Video thumbnail lands in queue with play glyph + duration.
5. **Swipe to VOICE**: swipe left twice (VIDEO → PHOTO → VOICE). Camera preview hides behind the mic-glyph overlay; flash / lens-flip / zoom disappear.
6. **Voice record**: tap shutter → first-run permission prompt, grant. Waveform scrolls with live mic input; "Recording…" label pulses. Say a few words. Tap again to stop. Voice thumbnail lands in queue.
7. **Swipe back to PHOTO** and capture 2 more photos. Queue is now `[P, P, P, V, A, P, P]` (approximate — exact depends on actions above).
8. **Commit**: swipe left handle rightward past half-strip → confirmation pulse → queue clears → "Bundle saved" shimmer. Inspect the chosen folder: `bundles/{date}-s-0001/photos/{id}-p-NNN.jpg` (only photos), `videos/{id}-v-NNN.mp4`, `audio/{id}-a-NNN.m4a`; `stitched/{id}-stitch.jpg` contains only the photos (video frames are NOT included).
9. **Bundle Preview** (photo-library icon, top right): the new bundle renders with 3 thumbnails — photos preferred, video poster frames as fallback for videos-only bundles, hand-drawn mic glyph for voice-only bundles. Subtitle reads `"N photos, M videos, K voice notes"`.
10. **Resilience checks**:
    - Kill the app mid-queue (`adb shell am force-stop com.example.bundlecam.debug`) → reopen → queue is restored with correct mixed-media order.
    - Kill mid-video-record → reopen → torn video file is validated by `MediaMetadataRetriever`; Media3 Muxer's periodic moov-flush usually produces a playable file that survives restore.
    - Kill mid-voice-record → reopen → torn .m4a fails the duration probe and is deleted cleanly.
11. **Mid-recording lockout**: start a video recording → try swiping the viewfinder and tapping the pill → nothing happens (pill is visually locked with current selection dimmed; swipe is gated). Tap shutter to stop.
12. **Delete-one on mixed queue**: swipe down on a video thumbnail → it's removed; queue closes the gap. Dividers remap if the deleted item crossed a divider.
13. **LocalSend transfer**: open Bundle Preview → swipe-right on one or more bundles to snap them to the green-check selected position → contextual app bar shows "N selected" with a paper-plane Send action → tap Send. The bottom sheet should discover any LocalSend desktop on the same Wi-Fi within ~1s (the Mac/Windows app's "Receive" tab needs to be open). Pick a peer → one combined prepare-upload session → file uploads stream in parallel (cap 3) → desktop's Downloads folder grows a `{bundleId}/photos`, `videos`, `audio`, `stitched` subtree per bundle. Confirm the bundle's directory structure on the receiver matches Recon's SAF layout.

---

## License

Unlicensed / internal MVP. No public distribution.
