# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Native Android camera app. User-facing name **Recon**; package root and class names retain `bundlecam` / `BundleCam` to avoid an invasive rename. Core flow: capture photos → stage on internal storage → swipe-commit a "bundle" → background worker copies raw JPEGs + produces one vertically-stitched JPEG into the user's SAF-picked folder.

Design spec (interaction/UX intent) lives in `bundlecam-mvp-designs.md`. README.md has a detailed architecture walk-through — keep both in sync when changing pipeline or capture-flow behavior.

## Common commands

```bash
./gradlew assembleDebug       # build debug APK
./gradlew installDebug        # install on connected device/emulator
./gradlew test                # pure-JVM unit tests (app/src/test/)

# Run a single test class or method:
./gradlew :app:test --tests "com.example.bundlecam.DividerOpsTest"
./gradlew :app:test --tests "com.example.bundlecam.StitcherLayoutTest.quality_*"
```

Toolchain: JDK 11, Android `compileSdk`/`targetSdk` 36, `minSdk` 26, Gradle 9 via wrapper, Kotlin 2.2.10, Compose BoM 2026.02.01. No `apply plugin` style — everything is via `libs.versions.toml` version catalog.

## Architecture: the three-phase capture lifecycle

This is the load-bearing mental model. **Don't break the phase boundaries** — especially the Main-thread commit pivot.

**Phase 1 — Shutter (≤100ms, UI-thread budget).** `CaptureViewModel.onShutter` → CameraX returns JPEG → `StagingStore.writePhoto` to `filesDir/staging/session-{uuid}/p-{k}.jpg` → `ExifWriter.stamp` (time/GPS-if-cached/orientation) → `decodeThumbnail` → push `StagedPhoto` onto `uiState.queue`. No SAF writes in this phase.

**Phase 2 — Commit (single frame).** `CaptureViewModel.onCommitBundle` pivots UI **synchronously on Main**: nulls `currentSession`, clears `queue` + `dividers`. Shutter is usable on the very next frame. The actual bookkeeping (bundle-ID allocation from DataStore, `PendingBundle` manifest serialization + file write, `WorkScheduler.enqueue`) runs in a background coroutine afterwards. If the process dies between the UI pivot and the manifest save, the staging session persists on disk and `OrphanRecovery` restores it as an uncommitted queue on next launch.

**Phase 3 — Worker (seconds–tens of seconds, off UI).** `BundleWorker` (CoroutineWorker) loads the manifest, optionally refreshes GPS (bounded 2s), stamps final per-file EXIF (`UserComment=BundleCam:{bundleId}:p{kk}` or `:stitch` + GPS backfill) in one open/save pass, copies raw JPEGs to `bundles/{bundle-id}/` via SAF, runs `Stitcher` into `stitched/{bundle-id}-stitch.jpg`, then deletes staging + manifest. **A process-wide `Mutex` (`workMutex`) serializes all workers** — stitching allocates ~60% of heap and two in parallel reliably OOMs mid-range devices.

### Why these shapes

- **Manifest file indirection (not WorkManager `Data`).** A 50-photo bundle's paths exceed WorkManager's 10KB Data limit. Only `bundleId` passes through `Data`; the worker loads `PendingBundle` JSON from `filesDir/pending/{bundleId}.json`. Orphan recovery replays manifests on next launch.
- **Staging on internal storage, not in memory.** 50 photos = ~2.5MB of thumbnails, not ~250MB of decoded JPEG. Photos are durable from the moment the shutter fires.
- **Bundle ID allocated atomically from DataStore before any SAF I/O.** If the worker fails partway, the counter does not rewind.
- **`WorkScheduler.observeFailures()` filters pre-existing failures** on first emission (see the `acknowledged` set). WorkManager replays historical `WorkInfo`, so without this, every app launch re-surfaces yesterday's transient failure.

## Resilience: `OrphanRecovery`

Runs at `AppContainer` init. Prunes terminal `WorkInfo`; re-enqueues manifests without in-flight work; returns the most-recent orphan staging session (a queue the user was building when the process died) to `CaptureViewModel.init` so it comes back as an uncommitted queue. Older orphan staging sessions are deleted to prevent unbounded disk growth.

## Module layout

Package root: `com.example.bundlecam`. All sources under `app/src/main/java/com/example/bundlecam/`.

- `ui/capture/` — capture screen, VM, gestures (`QueueStrip`, `QueueThumbnail`), divider arithmetic (`DividerOps`), undo-window holder (`DiscardSlot`). **UI is locked to portrait** (`screenOrientation=portrait` in the manifest) — icons counter-rotate via `Modifier.rotate(-deviceOrientation)` with shortest-arc animation.
- `ui/setup/`, `ui/settings/`, `ui/common/`, `ui/theme/` — first-run folder picker, settings, shared banner/picker, Material 3 theme with dynamic color on Android 12+.
- `data/camera/` — `CaptureController` owns `ProcessCameraProvider` + `ExtensionsManager`, binds use cases under a `bindMutex`; `OrientationEventListener` snaps device degrees to cardinal and writes `ImageCapture.targetRotation` so EXIF orientation reflects physical pose even with the UI locked. Exposes `bind(.., lens: LensFacing, ..)` (back/front selector) and `setFlashMode(FlashMode.Off/Auto/On)` that mutates live `ImageCapture.flashMode`. `bind()` re-applies `currentFlashMode` *after* installing the new capture — otherwise a `setFlashMode()` call that races the rebind targets the about-to-be-discarded old `ImageCapture`.
- `data/exif/` — `ExifWriter.stamp` (capture-time) and `stampFinalMetadata` (worker-time, single open/save). `OrientationCodec` converts between device degrees / `Surface.ROTATION_*` / ExifInterface tags.
- `data/location/` — `LocationProvider` with 30s TTL cache, mutex-guarded, returns null on permission denial.
- `data/settings/` — `SettingsRepository` backed by DataStore Preferences; `SettingsState { rootUri, stitchQuality, shutterSoundOn }` as a distinct-until-changed `Flow`.
- `data/storage/` — `StagingStore` (internal FS), `SafStorage` (writes via `DocumentFile`, batches `listFiles()`, overwrites on name collision so worker retries don't produce `" (1).jpg"`; directory creation goes through `findOrCreateDir` which re-`findFile`s after a null `createDirectory` to survive concurrent-creator races with `ensureBundleFolders`), `StorageLayout` (canonical naming — **change here, not at call sites**), `BundleCounterStore` (per-date monotonic counter).
- `pipeline/` — `PendingBundle` (kotlinx-serializable manifest), `ManifestStore`, `WorkScheduler`, `BundleWorker`, `Stitcher` (layout math is a pure `companion` function `computeLayout` so tests don't need Bitmap allocation), `OrphanRecovery`.
- `di/AppContainer.kt` — plain singleton, no DI framework. Constructed in `BundleCamApp.onCreate`. `configureRoot(uri)` returns a `Job` launched on an internal `appScope` (`SupervisorJob + Main.immediate`) — **do not call it from a composition-scoped `launch`**, or popping the screen mid-setup would cancel `ensureBundleFolders`.

`BundleCamApp` implements `Configuration.Provider` for WorkManager; the manifest `<provider tools:node="remove">` block **disables WorkManager's default auto-init** so our provider is actually used (otherwise the default config wins the race against `Application.onCreate`).

## Testing

Pure JVM tests in `app/src/test/java/com/example/bundlecam/`:

| Suite | Covers |
|---|---|
| `DividerOpsTest` | `partitionByDividers`, `remapDividersAfterDelete` |
| `OrientationCodecTest` | cardinal round-trips, `snapToCardinal` boundaries, `toSurfaceRotation` inverse |
| `StitcherLayoutTest` | quality-ceiling clamping, height cap, heap-budget scaling, aspect preservation |

There are no Android instrumented tests currently wired up beyond the default template. The capture gesture + worker pipeline is validated via a **manual smoke path** (see README.md § Testing).

When adding logic, prefer to put the algorithmic core in a pure function on a `companion object` (see `Stitcher.computeLayout`, `DividerOps`) so it can be unit-tested without an Android dependency.

## Conventions worth knowing

- **Log tag format**: `"BundleCam/<ClassName>"` as a private file constant.
- **Error surfacing**: recoverable failures write `state.lastError` for the banner; unrecoverable shouldn't happen (or they'd crash). Don't add silent `catch` blocks.
- **Commits across phases are atomic from the worker's view**: in `onCommitBundle`, save *all* manifests before enqueuing *any* worker — a mid-loop crash leaves no enqueued workers on partial state.
- **Gesture zones are siblings in a `Box`, not arbitrated**: the commit/discard `EdgeZone`s stack on top of the tray and `systemGestureExclusion()` goes flush to the screen edge. Don't try to merge them into a single gesture handler.
- **`EdgeZone` width ≠ destination-fill width**: edge zones grow on short queues (slack-based formula, capped at 33% per side) to catch swipes from further inward, but the tick/cross destination fill stays pinned at `EdgeZoneMinWidth` (60dp) anchored to the screen edge. Keep these two concerns decoupled when touching `QueueStrip` / `EdgeZone`.
- **Error banner overlays the queue strip** (peer of `UndoToast` / `BundleSavedShimmer` inside the queue `Box`), not a row above ZoomControl. Don't move it back inline — that pushes the shutter + queue down when an error fires. `ActionBanner` caps text at `maxLines = 2` with ellipsis so it stays within the 72dp queue-strip height.
