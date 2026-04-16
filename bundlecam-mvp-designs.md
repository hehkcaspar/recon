# BundleCam MVP — Native Designs (v1 scope)

_Pure capture tool. Photos go into a queue, the user commits a bundle, files land in folders. That's it._

---

## What v1 is (and isn't)

**In scope:**
- One-time output folder setup on first launch
- A single capture screen with a photo queue
- A single bundle commit action that **always produces both outputs**: a stitched single image AND the raw individual photos with naming pattern, written to two parallel folders
- Auto-captured EXIF: timestamp, GPS, orientation, bundle ID
- Minimal settings

**Out of scope:**
- Capture modes (Document/Object/Scene), post-processing, auto-crop
- User-entered metadata (tags, notes, prefix chips)
- In-app library/browser — the OS file browser serves this role
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

Every bundle commit writes to both `bundles/{bundle-id}/` (raw photos) and `stitched/` (the stitched image) simultaneously. The user does not pick a mode — both artifacts are always produced, because the cheap cost of doubling storage is worth the guarantee that downstream tooling can consume either format without recapture.

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
2. **Swipe the queue right** (from the left handle) → bundle commits: the stitched image is written to `stitched/` AND the individual photos are written to `bundles/{bundle-id}/` in parallel, brief success flash, queue clears, daily counter increments
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

The capture screen is chromeless. No mode toggle, no prefix field, no tagging — just preview, shutter, and a three-part queue strip with neutral handles. Bundling always produces both outputs, so the user never needs to choose.

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

1. **Output folder** — shows current path, "Change" button re-triggers folder picker
2. **Stitch quality** — Low / Standard / High (affects JPEG quality and downscale thresholds for the stitched image only; raw photos always saved at original quality)
3. **Shutter sound** — On / Off
4. **App version / storage used** — info only

No accounts, no cloud, no permissions management beyond OS-level.

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
