# BundleCam

Native Android camera app for capturing **bundles** of photos. A bundle produces up to two outputs in parallel — the raw photos (named and ordered) and a single vertically-stitched image — with each output independently toggleable in settings (at least one must be on). Designed for machinegun-cadence capture — shoot, shoot, swipe, shoot, shoot, swipe — with zero blocking work on the interaction path.

> User-facing display name is **Recon**. Internal package/class identifiers retain `bundlecam` / `BundleCam` to avoid an invasive codebase-wide rename.

For the user-facing product spec and interaction design, see [`bundlecam-mvp-designs.md`](./bundlecam-mvp-designs.md).

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
| `CAMERA` | Preview + capture |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | GPS stamping in EXIF — requested up-front on first camera view (after camera permission is granted) so the first capture already carries a fix. Denying is non-fatal; capture proceeds, EXIF just lacks GPS. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Let WorkManager's `SystemForegroundService` run the stitch worker reliably through Doze/backgrounding |
| `POST_NOTIFICATIONS` | Foreground-worker progress notifications on Android 13+ (declared but not requested at runtime — if denied, the worker still runs; only the shade notification is suppressed) |

Output storage uses SAF (Storage Access Framework), not a raw filesystem permission — the user picks a root folder on first launch via `OpenDocumentTree`, and the app persists RW access to that tree.

---

## Stack

| Layer | Library |
|---|---|
| UI | Jetpack Compose + Material 3 (BoM `2026.02.01`) |
| Camera | CameraX `1.4.1` (`core`, `camera2`, `lifecycle`, `view`, `extensions`) |
| Background processing | WorkManager `2.10.0` |
| Persistence (settings / counters) | DataStore Preferences `1.1.1` |
| Manifest serialization | `kotlinx.serialization.json` `1.7.3` |
| Location | Play Services Location `21.3.0` |
| EXIF | AndroidX ExifInterface `1.4.0` |
| Output storage | AndroidX DocumentFile `1.0.1` (SAF) |
| Concurrency | `kotlinx.coroutines` `1.9.0` + `Mutex` |

No DI framework — a plain singleton [`AppContainer`](./app/src/main/java/com/example/bundlecam/di/AppContainer.kt) wires everything up at `Application` creation.

---

## Architecture at a glance

```
         ┌──────────────────────────────────────┐
         │              UI (Compose)            │
         │  CaptureScreen → CaptureViewModel    │
         └──────────────┬───────────────────────┘
                        │
          ┌─────────────┼─────────────────┐
          ▼             ▼                 ▼
   CaptureController  StagingStore   BundleCounterStore
   (CameraX)        (internal FS)   (DataStore)
          │             │                 │
          └─────────────┼─────────────────┘
                        ▼
                  ManifestStore (JSON on disk)
                        │
                        ▼
                  WorkScheduler
                        │
                        ▼
                  BundleWorker  ──── Stitcher
                        │                │
                        ▼                ▼
                  SafStorage (user-picked tree via SAF)
                        │
                        ▼
                 raw photos + stitched.jpg
```

### Three-phase capture lifecycle

**Phase 1 — Shutter (≤100ms, UI thread budget).** CameraX returns a JPEG → write to internal staging (`filesDir/staging/session-{uuid}/p-{k}.jpg`) → stamp EXIF (time, GPS if available, orientation) → decode a small thumbnail → push onto the queue. No SAF writes yet.

**Phase 2 — Commit (single frame).** User swipes the queue. `CaptureViewModel.onCommitBundle` pivots the UI synchronously on Main — nulls `currentSession`, clears `queue` + `dividers` — so the shutter is ready on the very next frame. The actual bookkeeping (allocate bundle IDs, write [`PendingBundle`](./app/src/main/java/com/example/bundlecam/pipeline/PendingBundle.kt) manifests to disk, enqueue `BundleWorker`s) runs in a background coroutine. If the process dies before the manifest is saved, photos are still on staging and `OrphanRecovery` restores the session as a queue on next launch.

**Phase 3 — Worker (seconds–tens of seconds, off the UI thread).** `BundleWorker` loads the manifest, backfills GPS EXIF on any photo lacking it (bounded 2s location refresh — covers first-capture before the location fix has resolved), stamps per-file EXIF `UserComment`s (`BundleCam:{bundleId}:p{kk}` / `:stitch`) in a single open/save, copies raw JPEGs to `bundles/{bundle-id}/` via SAF, runs [`Stitcher`](./app/src/main/java/com/example/bundlecam/pipeline/Stitcher.kt) to produce one vertical JPEG into `stitched/`, then deletes the staging session and manifest file. A process-wide `Mutex` serializes workers so stitch memory stays bounded.

### Resilience

- **Photos survive app kill**: they're on internal storage the moment shutter fires. On next launch, [`OrphanRecovery`](./app/src/main/java/com/example/bundlecam/pipeline/OrphanRecovery.kt) prunes completed `WorkInfo`, re-enqueues any pending manifests without in-flight work, and restores the most recent orphan staging session (the last queue the user was building) back into the VM.
- **Bundle ID is allocated atomically** from DataStore before any SAF I/O — if the worker fails partway, the counter doesn't rewind.
- **Worker failures are observable**: `WorkScheduler.observeFailures()` emits a flow of failures, filtering out stale pre-existing failures on first subscription (see the `acknowledged` set) so replayed history doesn't spam the user.

---

## Module layout

Package root: `com.example.bundlecam`. All files live under `app/src/main/java/com/example/bundlecam/`.

### Entry point
- [`MainActivity.kt`](./app/src/main/java/com/example/bundlecam/MainActivity.kt) — sets up Compose, observes `SettingsRepository.settings`, routes between `FolderPickerScreen` (first run), `CaptureScreen` (primary), `BundlePreviewScreen`, and `SettingsScreen` via a `when`-chain on two `rememberSaveable` flags.
- [`BundleCamApp.kt`](./app/src/main/java/com/example/bundlecam/BundleCamApp.kt) — Application subclass, constructs `AppContainer`, provides `WorkManager.Configuration`.

### `ui/capture/` — the capture screen
- `CaptureScreen.kt` — top bar (settings, camera-mode toggle, **photo-library icon opening the Bundle Preview screen**), preview, zoom, flash / shutter / lens-flip row, queue strip, overlays (undo toast, saved shimmer, error banner). GPS permission is launched from a `LaunchedEffect(Unit)` the first time the camera UI mounts (not lazily on first shutter). The error banner sits inside the queue `Box` alongside `UndoToast` / `BundleSavedShimmer` so it overlays the queue rather than pushing the shutter + queue down.
- `CaptureViewModel.kt` — state machine (`CaptureUiState`, `BusyState { Idle, Capturing }`, `PendingDiscard`), events (`BundlesCommitted`), and methods for shutter, commit, discard, undo, delete, reorder, zoom, camera mode, divider insert/remove, **lens flip (`onToggleLens` — back/front; rejects mid-capture or mid-rebind)**, and **flash cycle (`onCycleFlash` — Off → Auto → On)**. A VM-internal subscription pipes `flashMode` changes into `captureController.setFlashMode`. Commit pivots UI on Main synchronously; all I/O runs in the background. Uses `TimedSlot<StagingSession>` from `ui/common/` for the discard undo window; the session's staging dir gets a `.discarded` marker file written synchronously on swipe so a process death mid-undo results in cleanup (not zombie-queue restoration) on next launch.
- `DividerOps.kt` — pure functions for queue-divider arithmetic (`partitionByDividers`, `remapDividersAfterDelete`); extracted from the VM so it's unit-testable without Android.
- `CameraPreview.kt` — wraps `PreviewView`, tap-to-focus, pinch-to-zoom, lifecycle rebind.
- `ShutterButton.kt` — 80dp circle, disabled during capture or camera rebinding.
- `ZoomControl.kt` — 0.5×/1×/2×/5× chips, filtered by hardware zoom range.
- `CameraModeToggle.kt` — EXT (CameraX Extensions) vs ZSL (zero-shutter-lag) segmented toggle.
- `QueueStrip.kt` — two-sided edge-zone swipe to commit / discard, plus per-gap `DividerZone` swipe-down-to-insert / swipe-up-to-remove. Gesture state machine (`GestureState.Idle | Bundling | Discarding`), `VelocityTracker`, haptic edge detection, tide gradient, destination glyph + commit flash. Custom `Layout` in `QueueContent` positions thumbnails + 24dp divider hit zones overlapping into neighbors; dividers Z-above thumbnails + Initial-pass consumption arbitrates the overlap. **`EdgeZone` widths are queue-size-aware**: when the tray has slack (short queue), each zone grows by `slack / 2` up to a cap (33% of screen width per side, with a 24dp neutral middle guard) so commits / discards can be initiated from a wider area. The destination fill (tick / cross over the screen-edge side) is constrained to a fixed 60dp regardless of the input width, anchored to the outer edge.
- `QueueThumbnail.kt` — vertical drag-to-delete + long-press-then-drag reorder per thumbnail.
- `UndoToast.kt` — "Discarded N photos · Undo" during the 3-second undo window.
- `BundleSavedShimmer.kt` — green pill triggered by `BundlesCommitted`: "Bundle {id} saved" for single-bundle commits, "N bundles saved ({first}–{last})" for multi-bundle splits.
- `CaptureColors.kt` — commit green (`#2E7D32`) + discard amber (`#B26A00`).

### `ui/setup/` — first-run folder picker
- `FolderPickerScreen.kt` — shown while `settings.rootUri == null`; launches `OpenDocumentTree` and persists SAF permissions.

### `ui/preview/` — in-app bundle browser
- `BundlePreviewScreen.kt` — Material 3 `Scaffold` + `TopAppBar` (back left, "Bundles" title, `FolderOpen` action on the right that defers to the system file browser via `openFolderInSystemBrowser`). Body is a `LazyColumn` that renders `ProcessingBundleRow`s first (for in-flight workers whose SAF files haven't landed yet), then `BundleRow`s for completed bundles, with `HorizontalDivider` between all rows. Processing IDs are filtered out of the completed list so a mid-write partial bundle can't appear twice. Empty / loading / error states rendered inline — the "No bundles yet" branch treats processing rows as content, so a just-committed first-bundle user still sees feedback. The folder icon on the capture screen now opens this screen instead of the system browser; the system-browser entry lives only in this screen's action bar.
- `BundlePreviewViewModel.kt` — `BundlePreviewUiState { bundles, loadState, errorMessage, pendingDeletes: Map<String, PendingDelete>, thumbnails: Map<Uri, ImageBitmap>, processingBundleIds: List<String> }`. `init` collects `WorkScheduler.observeActiveBundleIds()` and keeps `processingBundleIds` in sync; when the active set shrinks (a worker finished), it `loadBundles()` *first* (so the completed row is in place) and *then* drops the IDs from `processingBundleIds`, avoiding a blank-gap flash between the processing row disappearing and the completed row loading. `refresh()` delegates to the same private `loadBundles()`. `loadThumbnail(uri)` decodes async with a `Mutex`-guarded in-flight set to dedup concurrent requests. `onConfirmDelete(id)` short-circuits to immediate delete when the undo window is 0s; otherwise it places the bundle in `pendingDeletes` and schedules a per-bundle `Job` whose expiry hard-deletes via `BundleLibrary.deleteBundle`. Multiple bundles can be pending-delete simultaneously — each has its own countdown and its own Undo button. `onUndo(id)` cancels the Job and drops the pending entry. `onCleared` flushes any still-pending deletes via `container.appScope` so leaving the screen doesn't abandon a delete the user already confirmed.
- `BundleRow.kt` — the list item. Three states share the row skeleton (`Modifier.bundleRowLayout()` with `heightIn(min = 68.dp)` so neighbours don't shift when a row swaps between states):
    - **Normal**: up to 3 thumbnails in a 128dp fixed-width strip, monospace bundle id, photo-count subtitle, modality icons (`Outlined.PhotoLibrary` for the photos subfolder, `Outlined.ViewStream` for the stitched image). Swipe-left past ~40% of row width (or with velocity via Compose's `VelocityTracker`, matching `QueueStrip`) reveals a red "Delete" hint and fires `onRequestDelete` on release above threshold. Haptic tick on both threshold crossings so dragging back below the line reads as an armed cancel. An in-flight `Animatable` drives the snap-back / slide-out animation; live drag uses plain `mutableFloatStateOf` so we don't launch a coroutine per touch delta.
    - **Pending-delete**: same thumbnail strip, title replaced by "Deleting in Xs" (the countdown ticks via `derivedStateOf` so recomposition only fires on whole-second boundaries), modality icons replaced by an Undo `TextButton`. `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)` around the button suppresses Material's 48dp touch-target wrapping — otherwise the button would inflate the row by 8dp and push neighbours down.
    - **Processing** (`ProcessingBundleRow`): shown at the top of the list for any bundle id whose worker is ENQUEUED/RUNNING/BLOCKED. A single `CircularProgressIndicator` sits inside the leading thumb slot (the 3-thumb strip width is preserved so the bundle id stays column-aligned with completed rows beneath), title is the bundle id, subtitle is "Processing…". Not swipeable — you can't delete a bundle that's still being written.

### `ui/common/` + `ui/theme/`
- `FolderPicker.kt` — reusable SAF launcher.
- `FolderIntent.kt` — `openFolderInSystemBrowser(context, treeUri)`, the `ACTION_VIEW` launcher used by the Bundle Preview screen's action bar.
- `TimedSlot.kt` — generic single-slot "hold item, run cleanup on timeout unless `take()` wins first". Used by both the capture-time discard-undo (`TimedSlot<StagingSession>`) and could be reused elsewhere. The pending-delete map in the preview VM does NOT use this because it needs multiple concurrent slots keyed by bundle id — a direct `MutableMap<String, Job>` is clearer there.
- `ActionBanner.kt` — dismissible pill used for `state.lastError`. Text is capped at `maxLines = 2` with ellipsis so it fits as an overlay on the 72dp queue strip without ballooning its host.
- `Theme.kt` / `Color.kt` / `Type.kt` — Material 3 with dynamic color on Android 12+.

### `ui/settings/`
- `SettingsScreen.kt` — output folder (change via SAF picker; URI wraps; Change button anchored top-right), "Bundle output" block with per-output toggles (`Individual photos in subfolder` / `Vertical stitched image` — the UI locks the only-on switch so ≥1 is enforced, with a caption surfaced only while a lock is active), **Stitch Quality** dropdown nested under the stitched toggle (Material 3 `DropdownMenu`, indented 16dp, disabled when stitching is off), shutter sound toggle, **"Confirm before deleting a bundle"** switch, **"Undo window for bundle deletion"** dropdown (Off / 1s … 10s; 0 means delete immediately with no undo prompt). All interactive controls are right-aligned; label + control rows share a `SettingsRow` helper. Labels use `titleSmall` (parent rows) / `bodyMedium` (nested child rows) for consistent hierarchy.

### `data/camera/`
- `CaptureController.kt` — owns `ProcessCameraProvider` + `ExtensionsManager`, binds `Preview` + `ImageCapture` to lifecycle via a `bindMutex`, exposes `zoomInfo` / `deviceOrientation` as `StateFlow`, runs an `OrientationEventListener` that snaps to cardinal and pushes `targetRotation` into the `ImageCapture` use case for correct EXIF orientation on tilted captures. `bind(..., lens: LensFacing, ...)` accepts a back/front selector; `setFlashMode(FlashMode.Off/Auto/On)` mutates the live `ImageCapture.flashMode` without a rebind and `bind()` re-reads `currentFlashMode` post-install so writes interleaved with a rebind still land on the new capture.
- `BitmapUtils.kt` — `Bitmap.rotateIfNeeded(degrees)` extension (shared by thumbnail decode and stitching).
- `ThumbnailDecoder.kt` — JPEG → small `ImageBitmap` via `inSampleSize`, rotated to match capture orientation. Three overloads: in-memory bytes (capture-time), file path (orphan-recovery, avoids loading the full JPEG into memory), and `ContentResolver + Uri` (Bundle Preview row thumbnails from SAF). The SAF overload reads the stream once into a byte array and decodes bounds + pixels + EXIF rotation from it so the three stages share a single ContentProvider IPC rather than three.

### `data/exif/`
- `ExifWriter.kt` — two entry points: `stamp(...)` at capture time (DateTimeOriginal, orientation, Make/Model, GPS-if-cached); `stampFinalMetadata(file, comment, backfillLocation)` at commit time in one open/save pass (bundle-ID UserComment, plus GPS if the photo doesn't already carry it).
- `OrientationCodec.kt` — canonical conversions between device degrees, `Surface.ROTATION_*`, and ExifInterface orientation tags.

### `data/location/`
- `LocationProvider.kt` — Fused Location with a 30s TTL cache (refresh threshold 15s), mutex-guarded, returns null on permission denial or network failure.

### `data/settings/`
- `SettingsRepository.kt` — DataStore-backed `SettingsState { rootUri, stitchQuality, shutterSoundOn, saveIndividualPhotos, saveStitchedImage, deleteDelaySeconds, deleteConfirmEnabled }`, exposed as a distinct-until-changed `Flow`. Sanitizes on read so a `(false, false)` output pair can never reach the worker. `deleteDelaySeconds` is clamped to `MIN_DELETE_DELAY_SECONDS..MAX_DELETE_DELAY_SECONDS` (0–10); `deleteConfirmEnabled` defaults to `true`.

### `data/storage/`
- `StagingStore.kt` — internal staging (`filesDir/staging/session-{uuid}/`); per-capture-session folders, per-photo files, session/file delete helpers. `markDiscarded(session)` / `unmarkDiscarded(session)` / `isDiscarded(session)` manage a `.discarded` marker file inside the session directory — written synchronously on the capture-screen discard swipe so a process death during the 3-second undo window results in cleanup (not orphan-queue restoration) on next launch.
- `SafStorage.kt` — copy staged files to the user-picked SAF tree, ensures `bundles/` and `stitched/` subtrees exist. Batches one `listFiles()` per call so a 50-photo bundle isn't 50 full directory-listing IPCs; overwrites on name collision so worker retries don't produce `" (1).jpg"` duplicates. Directory resolution goes through `findOrCreateDir(parent, name)` which retries `findFile` after a null `createDirectory` — necessary because some DocumentsProviders return null when a concurrent creator (e.g. `configureRoot`'s `ensureBundleFolders` running in parallel with the first bundle worker after a folder swap) materializes the same name between our lookup and create. Without the retry, a lost race surfaces as a fatal "Failed to create directory 'bundles'" in the worker.
- `BundleLibrary.kt` — read + delete operations for the Bundle Preview screen. `listBundles(rootUri)` parallelizes per-bundle `listFiles()` calls via `coroutineScope { async }.awaitAll()` so SAF IPC doesn't serialize across N bundles. Merges the `bundles/` subfolder list and the `stitched/` file list by bundle id; collects up to 3 thumbnail URIs per bundle (prefers raw subfolder photos, falls back to the stitched JPEG only when the subfolder wasn't kept). `deleteBundle(bundle)` uses `DocumentsContract.deleteDocument(resolver, uri)` directly on each modality URI — the lower-level API is the right tool for child-of-tree document URIs, and sidesteps `DocumentFile.fromTreeUri`'s gotchas around URI normalization.
- `CompletedBundle.kt` — data class returned by `BundleLibrary`: id, modality list (`BundleModality.Subfolder | .Stitch`), subfolder/stitch URIs, thumbnail URIs, photo count.
- `StorageLayout.kt` — canonical naming: bundle IDs (`{date}-s-{0000}`), photo filenames (`-p-{kk}.jpg`), stitched filename (`-stitch.jpg`), `STITCH_SUFFIX` constant, EXIF UserComment format.
- `BundleCounterStore.kt` — per-date monotonic counter via DataStore; `allocate()` returns the next ID and resets on date change.

### `pipeline/`
- `PendingBundle.kt` — kotlinx-serializable manifest: `bundleId`, `rootUriString`, `stitchQuality`, `sessionId`, ordered `PendingPhoto` list, `capturedAt`, and the output-mode flags `saveIndividualPhotos` / `saveStitchedImage` (frozen at commit so a settings change mid-flight doesn't alter what an already-queued worker produces; default `true` so manifests from older app versions decode with pre-flag behavior).
- `ManifestStore.kt` — writes / reads / deletes `filesDir/pending/{bundleId}.json`.
- `WorkScheduler.kt` — enqueues `BundleWorker` with a per-bundle unique work name (`KEEP` policy), exposes `observeFailures()` that filters stale pre-existing failures, `observeActiveBundleIds()` returning the set of bundle ids in ENQUEUED/RUNNING/BLOCKED state (extracted from each `WorkInfo`'s `bundle_{id}` tag — tags outlive input `Data` across state changes), plus `pruneWork()` for orphan recovery.
- `BundleWorker.kt` — CoroutineWorker; process-wide `Mutex` serializes all workers; reads manifest, then the raw-copy and stitch branches run independently gated on the manifest's output-mode flags — `saveIndividualPhotos` triggers the location refresh + per-photo final-EXIF stamp (UserComment + GPS-backfill in one pass) + SAF copy, `saveStitchedImage` triggers `Stitcher` + stitched-file EXIF stamp + SAF write. Manifest + staging cleanup always runs on success.
- `Stitcher.kt` — computes common width (min source width, clamped by `StitchQuality` ceiling: 1600 / 1800 / MAX px), scales heights proportionally, enforces a 32k-px total-height cap and a 60%-of-heap budget, decodes each source with `inSampleSize` to fit its slot, rotates, draws into a single canvas, compresses JPEG at 70 / 82 / 92. Layout math is a pure `companion` function (`computeLayout`) so tests can exercise it without any Bitmap allocation.
- `OrphanRecovery.kt` — runs at `AppContainer` init: prunes terminal WorkInfo; re-enqueues manifests without in-flight work; partitions staging sessions into `discarded` (have the `.discarded` marker from a discard-undo cut short by process death) vs. `live`, deletes the discarded ones, returns the most-recent live orphan (if any) to `CaptureViewModel.init` so the user's in-flight queue comes back; deletes stale older live orphan sessions to prevent unbounded disk growth.

### `di/`
- `AppContainer.kt` — singleton, constructs all `data/` + `pipeline/` components (including `BundleLibrary`), provides `configureRoot(uri): Job` which updates `SettingsRepository` then creates the `bundles/` + `stitched/` subtrees. Runs on a public `appScope` (`SupervisorJob + Main.immediate`) rather than a caller-provided composition scope — otherwise a user who pops Settings immediately after picking a folder would cancel `ensureBundleFolders` mid-flight. `appScope` is also used by `BundlePreviewViewModel.onCleared` to flush pending deletes that outlive the VM.

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
| `DividerOpsTest` | `partitionByDividers` (empty queue, single item, out-of-range dividers, multi-dividers) and `remapDividersAfterDelete` (index shift, collapse-on-collision, new-size cutoffs) |
| `OrientationCodecTest` | cardinal round-trips, `snapToCardinal` boundary angles (44/45, 134/135, 224/225, 315/316, 360), `toSurfaceRotation` inverse mapping |
| `StitcherLayoutTest` | quality-ceiling clamping (LOW/STANDARD/HIGH), height-cap scaling at 32k, heap-budget scaling, aspect preservation, min-height floor |

Manual smoke path for the capture flow:
1. Launch → folder picker → choose a test folder
2. Capture 3–5 photos (watch EXIF `DateTimeOriginal`, GPS if permission granted, orientation on a tilted device)
3. Swipe left handle rightward past half-strip → confirmation pulse → queue clears → "Bundle saved" shimmer
4. Inspect the chosen folder: `bundles/{date}-s-0001/` has 3–5 raw JPEGs, `stitched/{date}-s-0001-stitch.jpg` is one tall image
5. Capture + swipe right handle leftward → 3s undo toast → tap Undo → queue comes back
6. Capture + swipe right → let undo time out → queue stays gone, no files written
7. On a thumbnail: swipe down → that photo removed; long-press then drag → reorder
8. Kill the app mid-queue → reopen → queue is restored

---

## License

Unlicensed / internal MVP. No public distribution.
