# BundleCam

Native Android camera app for capturing **bundles** of photos. Each bundle produces two outputs in parallel: the raw photos (named and ordered) and a single vertically-stitched image. Designed for machinegun-cadence capture — shoot, shoot, swipe, shoot, shoot, swipe — with zero blocking work on the interaction path.

> User-facing display name is **Recon**. Internal package/class identifiers retain `bundlecam` / `BundleCam` to avoid an invasive codebase-wide rename.

For the user-facing product spec and interaction design, see [`bundlecam-mvp-designs.md`](./bundlecam-mvp-designs.md).

---

## Build & run

Requirements: JDK 11+, Android SDK 36, Gradle 9 (via wrapper).

```bash
./gradlew assembleDebug            # build debug APK
./gradlew installDebug             # install on connected device/emulator
```

Open in Android Studio for preview and live editing.

`local.properties` points Gradle at your Android SDK and is gitignored — it's generated automatically on first open in Android Studio.

### Configuration

- **`minSdk`**: 26 (Android 8.0) — required for adaptive icons, `systemGestureExclusion`, and the CameraX Extensions API.
- **`targetSdk` / `compileSdk`**: 36.
- **JVM toolchain**: Java 11 source/target compatibility.

### Required permissions

| Permission | Why |
|---|---|
| `CAMERA` | Preview + capture |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | GPS stamping in EXIF (optional — capture proceeds if denied) |
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
- [`MainActivity.kt`](./app/src/main/java/com/example/bundlecam/MainActivity.kt) — sets up Compose, observes `SettingsRepository.settings`, routes between `FolderPickerScreen` (first run), `CaptureScreen` (primary), and `SettingsScreen`.
- [`BundleCamApp.kt`](./app/src/main/java/com/example/bundlecam/BundleCamApp.kt) — Application subclass, constructs `AppContainer`, provides `WorkManager.Configuration`.

### `ui/capture/` — the capture screen
- `CaptureScreen.kt` — top bar, preview, zoom, shutter, queue strip, overlays (undo toast + saved shimmer).
- `CaptureViewModel.kt` — state machine (`CaptureUiState`, `BusyState { Idle, Capturing }`, `PendingDiscard`), events (`BundlesCommitted`), and methods for shutter, commit, discard, undo, delete, reorder, zoom, camera mode, divider insert/remove. The commit pivots UI on Main synchronously; all I/O runs in the background.
- `DividerOps.kt` — pure functions for queue-divider arithmetic (`partitionByDividers`, `remapDividersAfterDelete`); extracted from the VM so it's unit-testable without Android.
- `DiscardSlot.kt` — "pending discard" session holder + single-shot undo-window timer. Whoever calls `take()` first owns the session, so a racing Undo tap and the timeout fire can't both act on it.
- `CameraPreview.kt` — wraps `PreviewView`, tap-to-focus, pinch-to-zoom, lifecycle rebind.
- `ShutterButton.kt` — 80dp circle, disabled during capture or camera rebinding.
- `ZoomControl.kt` — 0.5×/1×/2×/5× chips, filtered by hardware zoom range.
- `CameraModeToggle.kt` — EXT (CameraX Extensions) vs ZSL (zero-shutter-lag) segmented toggle.
- `QueueStrip.kt` — two-sided edge-zone swipe to commit / discard, plus per-gap `DividerZone` swipe-down-to-insert / swipe-up-to-remove. Gesture state machine (`GestureState.Idle | Bundling | Discarding`), `VelocityTracker`, haptic edge detection, tide gradient, destination glyph + commit flash. Custom `Layout` in `QueueContent` positions thumbnails + 24dp divider hit zones overlapping into neighbors; dividers Z-above thumbnails + Initial-pass consumption arbitrates the overlap.
- `QueueThumbnail.kt` — vertical drag-to-delete + long-press-then-drag reorder per thumbnail.
- `UndoToast.kt` — "Discarded N photos · Undo" during the 3-second undo window.
- `BundleSavedShimmer.kt` — green pill triggered by `BundlesCommitted`: "Bundle {id} saved" for single-bundle commits, "N bundles saved ({first}–{last})" for multi-bundle splits.
- `CaptureColors.kt` — commit green (`#2E7D32`) + discard amber (`#B26A00`).

### `ui/setup/` — first-run folder picker
- `FolderPickerScreen.kt` — shown while `settings.rootUri == null`; launches `OpenDocumentTree` and persists SAF permissions.

### `ui/settings/`
- `SettingsScreen.kt` — output folder (change via SAF picker), stitch quality (LOW/STANDARD/HIGH radio), shutter sound toggle.

### `ui/common/` + `ui/theme/`
- `FolderPicker.kt` — reusable SAF launcher.
- `ActionBanner.kt` — inline dismissible banner (used for `state.lastError`).
- `Theme.kt` / `Color.kt` / `Type.kt` — Material 3 with dynamic color on Android 12+.

### `data/camera/`
- `CaptureController.kt` — owns `ProcessCameraProvider` + `ExtensionsManager`, binds `Preview` + `ImageCapture` to lifecycle via a `bindMutex`, exposes `zoomInfo` / `deviceOrientation` as `StateFlow`, runs an `OrientationEventListener` that snaps to cardinal and pushes `targetRotation` into the `ImageCapture` use case for correct EXIF orientation on tilted captures.
- `BitmapUtils.kt` — `Bitmap.rotateIfNeeded(degrees)` extension (shared by thumbnail decode and stitching).
- `ThumbnailDecoder.kt` — JPEG → small `ImageBitmap` via `inSampleSize`, rotated to match capture orientation. Two overloads: one for in-memory bytes (capture-time path), one for a file path (orphan-recovery path, avoids loading the full JPEG into memory).

### `data/exif/`
- `ExifWriter.kt` — two entry points: `stamp(...)` at capture time (DateTimeOriginal, orientation, Make/Model, GPS-if-cached); `stampFinalMetadata(file, comment, backfillLocation)` at commit time in one open/save pass (bundle-ID UserComment, plus GPS if the photo doesn't already carry it).
- `OrientationCodec.kt` — canonical conversions between device degrees, `Surface.ROTATION_*`, and ExifInterface orientation tags.

### `data/location/`
- `LocationProvider.kt` — Fused Location with a 30s TTL cache (refresh threshold 15s), mutex-guarded, returns null on permission denial or network failure.

### `data/settings/`
- `SettingsRepository.kt` — DataStore-backed `SettingsState { rootUri, stitchQuality, shutterSoundOn }`, exposed as a distinct-until-changed `Flow`.

### `data/storage/`
- `StagingStore.kt` — internal staging (`filesDir/staging/session-{uuid}/`); per-capture-session folders, per-photo files, session/file delete helpers.
- `SafStorage.kt` — copy staged files to the user-picked SAF tree, ensures `bundles/` and `stitched/` subtrees exist. Batches one `listFiles()` per call so a 50-photo bundle isn't 50 full directory-listing IPCs; overwrites on name collision so worker retries don't produce `" (1).jpg"` duplicates.
- `StorageLayout.kt` — canonical naming: bundle IDs (`{date}-s-{0000}`), photo filenames (`-p-{kk}.jpg`), stitched filename (`-stitch.jpg`), EXIF UserComment format.
- `BundleCounterStore.kt` — per-date monotonic counter via DataStore; `allocate()` returns the next ID and resets on date change.

### `pipeline/`
- `PendingBundle.kt` — kotlinx-serializable manifest: `bundleId`, `rootUriString`, `stitchQuality`, `sessionId`, ordered `PendingPhoto` list, `capturedAt`.
- `ManifestStore.kt` — writes / reads / deletes `filesDir/pending/{bundleId}.json`.
- `WorkScheduler.kt` — enqueues `BundleWorker` with a per-bundle unique work name (`KEEP` policy), exposes `observeFailures()` that filters stale pre-existing failures, plus `pruneWork()` for orphan recovery.
- `BundleWorker.kt` — CoroutineWorker; process-wide `Mutex` serializes all workers; reads manifest, refreshes location with a 2s timeout, stamps final EXIF (UserComment + GPS-backfill) in one pass per file, copies raw photos to SAF, runs `Stitcher`, writes stitch output, deletes staging + manifest on success.
- `Stitcher.kt` — computes common width (min source width, clamped by `StitchQuality` ceiling: 1600 / 1800 / MAX px), scales heights proportionally, enforces a 32k-px total-height cap and a 60%-of-heap budget, decodes each source with `inSampleSize` to fit its slot, rotates, draws into a single canvas, compresses JPEG at 70 / 82 / 92. Layout math is a pure `companion` function (`computeLayout`) so tests can exercise it without any Bitmap allocation.
- `OrphanRecovery.kt` — runs at `AppContainer` init: prunes terminal WorkInfo; re-enqueues manifests without in-flight work; returns the most-recent orphan staging session (if any) to `CaptureViewModel.init` so the user's in-flight queue comes back; deletes stale older orphan sessions to prevent unbounded disk growth.

### `di/`
- `AppContainer.kt` — singleton, constructs all `data/` + `pipeline/` components, provides `configureRoot(uri)` which persists SAF permissions and creates the output subtrees.

---

## Key design decisions

**Main-thread commit pivot; bookkeeping runs off-thread** — the commit swipe is gated purely on Main-thread state mutations (null `currentSession`, empty `queue`). Bundle-ID allocation (DataStore), manifest serialization (JSON + file write), and worker enqueue (WorkManager DB insert) all run in a background coroutine after the shutter is already ready. If the process dies between the UI pivot and the manifest save, the staging session still has the photos on disk and `OrphanRecovery` restores them as an uncommitted queue on next launch. Net: shutter re-enables within one frame of the swipe — previously 30–100ms of blocked UI.

**Manifest file indirection (vs WorkManager `Data`)** — a 50-photo bundle's photo paths exceed WorkManager's 10KB `Data` input limit. Instead, the VM writes a `PendingBundle` JSON next to the app's internal files; only the `bundleId` goes through `Data`, and the worker loads the rest from disk. This also makes orphan recovery trivial: if the process dies mid-commit, the manifest persists and is replayed on next launch.

**Staging store for resilience** — photos are written to internal storage on capture, not held in memory. A queue of 50 photos costs ~2.5MB of thumbnails, not ~250MB of decoded JPEG. If the process is killed, the staging session persists on disk and `OrphanRecovery` can restore the queue.

**Per-bundle `Mutex` in `BundleWorker`** — stitching a tall image allocates ~60% of heap. Running two in parallel on a mid-range device reliably OOMs. A process-wide `Mutex` forces serial execution; bundles queue up and drain.

**`observeFailures()` filters pre-existing failures** — WorkManager replays historical `WorkInfo` on new `observe()` calls. Without filtering, every app launch would re-surface yesterday's transient bundle failure. The scheduler snapshots the set of failures on first emission as "acknowledged" and only emits fresh failures thereafter.

**Orientation via `OrientationEventListener` + `imageCapture.targetRotation`** — `DisplayRotation` only catches 90°/270° rotations when the activity's `screenOrientation=portrait`. The accelerometer-based listener catches all four, snaps to the nearest cardinal, and feeds the translated `Surface.ROTATION_*` into `ImageCapture` so the **EXIF orientation** tag matches the physical device pose even when the UI doesn't rotate.

**UI counter-rotation (not UI rotation)** — `screenOrientation=portrait` locks the layout. For icons (settings, folder, zoom labels) to feel right-side-up, they get a `rotate(-deviceOrientation)` `Modifier` driven by a cumulative-target `animateFloatAsState` that picks the shortest arc (no 270° spins when going 0° → 90°).

**Gesture model** — the queue strip has three disjoint sibling gesture zones: two 60dp `EdgeZone`s at the screen edges (commit / discard via `detectHorizontalDragGestures`) and the tray in between (horizontal scroll + per-thumbnail long-press reorder + vertical drag-to-delete). Because each zone is a separate child of an outer `Box`, Compose's hit-test routes each pointer-down unambiguously — no arbitration, no slop tuning. Commit / discard uses **hybrid thresholds**: commit if `|dragX| >= maxWidth/2` **or** `|velocity| >= 80.dp/s` in the commit direction. Velocity tracking via Compose's `VelocityTracker`. Tide-gradient fills toward the destination edge as progress grows; destination zone shows the accent color + glyph throughout swipe, intensifies on commit, then fades out — driven by `max(destinationAlpha, flashAlpha)` so there's no visual seam between swipe-end and commit animation.

**Edge zones overlap tray, not vice versa** — the two 60dp `EdgeZone`s are stacked on top of the tray inside a `Box`. The tray is padded 48dp on each side so thumbnails render in the middle 48dp→W-48 slab, but the handle touch zones extend 12dp further inward (48-60dp). Only ~4dp of each edge thumbnail actually falls inside the swipe-start zone (the tray has 8dp inner padding), which is negligible — users tap thumbnail centers. The upside: `systemGestureExclusion()` on the edge zones goes flush to screen edge, fully covering Android's back-gesture region.

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
