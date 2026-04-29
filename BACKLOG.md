# Recon — Post-MVP Backlog

Ideas captured during MVP design that are **not** scoped for the current milestone. The core capture-and-commit loop (shoot → queue → swipe → bundle on disk) is complete; these items describe how the product could grow once the core is stable. They are intentionally speculative — architecture sketches, not commitments — and should be re-evaluated when their time comes.

For the product and interaction spec that *is* shipped, see [`recon-mvp-designs.md`](./recon-mvp-designs.md). For the Android reference implementation, see [`README.md`](./README.md).

> Item 2 of this list — **LocalSend interop for peer-to-peer bundle transfer** — has shipped. See `recon-mvp-designs.md` § "LocalSend bundle transfer" for the user-visible UX and `README.md` § `network/localsend/` for the Android implementation. The remaining backlog items renumber accordingly.

---

## 1. OCR / document-understanding pipeline for bundle → Markdown

Post-process a committed bundle (raw JPEGs + stitched image) into a structured Markdown document written to `bundles/{bundle-id}/{bundle-id}.md` alongside the existing outputs.

**Scope note.** Recon is a personal-use project, so license terms (AGPL-3.0 in particular) are not constraints, and the design criterion collapses to "which path produces the highest-quality output". That changes the architecture meaningfully — the cherry-pick-Apache-2.0-models path that earlier drafts treated as a middle tier was a license-driven workaround; without the AGPL constraint and with quality as the goal, it's strictly dominated by running real MinerU off-device. The shape reduces to two paths: a fast on-device fallback and a high-quality desktop companion.

### Best-quality path — MinerU VLM on a desktop companion

Run [MinerU](https://github.com/opendatalab/mineru) (current `mineru` 3.0.x as of April 2026) on a personal desktop, reached over the LAN via the now-shipped LocalSend interop. Use the **VLM backend** (`MinerU2.5-2509-1.2B` — 1.16B-param Qwen2-VL derivative, ~4.6 GB BF16; or `MinerU2.5-Pro-2510` if hardware permits) for the strongest open-source document-understanding output currently available: layout-faithful Markdown with LaTeX formulas, HTML tables, and learned reading order.

Realistic hardware expectations: an Apple Silicon Mac with 16+ GB unified memory runs the VLM workably at CPU/MPS speeds (tens of seconds per page); a Linux/Windows box with a discrete GPU and 8+ GB VRAM runs it near-real-time. If GPU isn't available, the **pipeline backend** (`mineru -b pipeline`) is the workable second choice on CPU-only desktops — slower and slightly less faithful on complex layouts, but no GPU dependency.

### Offline fallback — ML Kit Text Recognition v2 on-device

When no companion is reachable (no LAN, desktop off, on the road), chain a separate `OneTimeWorkRequest` after `BundleWorker` that runs **ML Kit Text Recognition v2** (`com.google.mlkit:text-recognition:16.0.1` for Latin bundled, ~4 MB APK delta per script per ABI; or `play-services-mlkit-text-recognition:19.0.1` unbundled, ~260 KB APK + ~1.5–4 MB Play-Services model fetched on first call; supports Latin / CJK / Japanese / Korean / Devanagari as separate artifacts).

Pure CPU via TFLite + XNNPACK — **no NNAPI / GPU acceleration** for text recognition, so SD 7-class NPU/DSP buys nothing. Latency at full 12MP: ~500–900 ms warm on SD 7-class, ~250–500 ms on Pixel 7a/8a, 1–3 s on SD 4/6-class; cold-start adds 100–400 ms. **Downscale to ~2 MP long-edge before inference** — Google's docs note characters need only 16×16 px and there's "no accuracy benefit beyond 24×24", and downscaling cuts latency ~6× with zero quality loss for document text.

Consumes raw per-page JPEGs from `bundles/{bundle-id}/` (never the stitched composite — stitch is for humans, raw pages are for the model), joins line-level text in reading order with one `## Page N` heading per photo, writes the `.md` sidecar via SAF. **Decode → process → recycle each Bitmap per page** — a 12MP ARGB_8888 bitmap is ~48 MB, so holding all 20 page bitmaps simultaneously will OOM the 512 MB heap; one at a time is comfortably safe.

**Reuse a single `TextRecognizer` for the whole bundle** and `close()` it in `finally` to release native resources; do **not** parallelize `process()` calls (Google's docs warn against concurrent recognizers and parallelism doesn't help anyway). Reuse the process-wide `workMutex` so OCR never races a stitch. WorkManager constraints: `setRequiresStorageNotLow(true)` + `setRequiresBatteryNotLow(true)`; explicitly **not** `setRequiresCharging` / `setRequiresDeviceIdle` (would defer by hours). Surface failures via the existing `ActionBanner`; no status rows. No formulas, no tables-as-HTML, modest reading-order — but lands a usable sidecar with zero setup, fully offline, no GMS dependency if Latin-bundled is used.

### Companion mechanics

The "MinerU companion" can be as thin as a Python script that watches an inbox folder, runs `mineru -p {file_or_dir} -o {out_dir} -b vlm` per bundle, and drops the result back. The architectural commitment is just "the bundle ships off-device; something out there produces a `.md`; the `.md` lands in `bundles/{bundle-id}/`". Concrete options ranked by setup effort:

- **Easiest:** a Syncthing-shared folder between phone and desktop, plus a desktop-side `inotifywait`/`fswatch` shell loop that runs `mineru` on each new bundle and writes the `.md` back into the same folder. Zero protocol work.
- **Cleaner:** a small Python receiver speaking the LocalSend protocol, accepting bundles, running MinerU, and replying via LocalSend's send-back. Pairs symmetrically with the shipped sender.
- **Heaviest:** a first-party Kotlin desktop app (Compose Multiplatform) that wraps `mineru` as a subprocess and presents a peer to the phone. Probably overkill for personal use.

### Routing

Two reasonable defaults — pick one based on usage pattern.

- **(a) Always-fallback, opt-in upgrade.** ML Kit always produces `{bundle-id}.md` shortly after commit; sending the bundle to the desktop companion later overwrites it with the higher-fidelity result. Has the property that something always lands.
- **(b) Tagged-for-companion.** The user marks a bundle as "needs OCR companion" before/after commit; the app routes to the companion when reachable and skips ML Kit for that bundle. Avoids redundant work but means some bundles sit without a `.md` until the desktop is online.

(a) is the safer default; (b) is worth considering if the on-device pass turns out to be wasted effort for the user's actual workflow (e.g. always-have-desktop, never-on-the-road).

### Architectural rules

- OCR output never influences stitch layout — visual and semantic artifacts stay independent and separately reproducible.
- The on-device pass runs as a chained `WorkRequest` after `BundleWorker`, not as a third stage inside it, so failures retry independently and the stitch-mutex scope stays tight.
- Extend the `PendingBundle` manifest with a `sidecarMarkdown` field rather than introducing a second manifest type.
- The companion route doesn't touch the worker pipeline at all — it's a user-initiated LocalSend send followed by an inbound `.md` write whenever the desktop replies (which would also require the inbound side of LocalSend, currently sender-only).

### Open questions

- Should the on-device ML Kit `.md` be considered "draft" and overwritten by the companion result, or kept under different filenames (`{bundle-id}.md` vs. `{bundle-id}.mineru.md`) so the two outputs can be compared?
- How does a future bundles browser surface the per-bundle state ("draft", "upgraded by companion", "send pending")?
- Should the companion script live in the Recon repo (`tools/mineru-companion/`) so the personal-use setup is one `git clone` + `pip install mineru` away?

---

## 2. Background recording via foreground service

Keep recordings running when the Activity pauses — screen lock, home-button press, switching to another app. Currently `CaptureScreen`'s `LifecycleEventEffect(ON_PAUSE)` routes to `CaptureViewModel.onLifecyclePaused()`, which stops both video and voice takes. The MVP ships a `view.keepScreenOn = true` flag scoped to `busy == Recording`, which prevents the display timeout from firing `ON_PAUSE` mid-take; but that fix doesn't help when the user locks the phone manually or switches apps, and voice memos in particular are a modality where it's natural to tap-start then pocket the phone.

### Why a full service, not just dropping the `ON_PAUSE` stop

Android 11+ forbids background access to mic/camera unless the process runs a **foreground service** with the matching `foregroundServiceType`. Without FGS, two failure modes compound: (a) Samsung-class OEMs kill the process on screen lock despite the mic technically still being held; (b) Android 14+ returns silence from `MediaRecorder` (mic muted) even when the recorder reports running. Silently losing audio is the worst possible failure for a voice memo, so the reliable path is FGS, not wake locks.

### Shape of the change — voice-only first cut (recommended)

- New `RecordingService : Service` bound from `MainActivity`. `onStartCommand` calls `startForeground(notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)` with a "Recon is recording" notification carrying a Stop action and a live `elapsedRealtime` timer fed from `_voiceRecording.startedAt`. The service is a **process anchor** — it holds no recording state. `VoiceController` keeps owning `MediaRecorder`; its coroutine scope outlives the Activity already.
- Manifest: add `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`; declare `<service android:name=".RecordingService" android:foregroundServiceType="microphone"/>`.
- `CaptureViewModel.startVoiceRecordingFlow` binds the service before calling `voiceController.startRecording`; `stopVoiceRecordingFlow` unbinds + `stopService` after the recorder finalizes. Bind is async, so the shutter-tap UX needs to kick off the bind and start the recorder only in `onServiceConnected` — acceptable added latency ~50 ms.
- Drop the `VOICE` branch out of `onLifecyclePaused`. The `VIDEO` branch stays.
- Notification Stop action routes a `PendingIntent` into the service, which posts to a `Channel<Unit>` the VM observes; the handler re-enters `stopVoiceRecordingFlow` on the same path as a normal shutter-tap-to-stop, so `BusyState` transitions stay coherent.

### Video is meaningfully harder

CameraX `Recorder` is bound to the Activity's `LifecycleOwner` via `bindToLifecycle`. `ON_STOP` unbinds all use cases, tearing the recorder down mid-stream — no amount of FGS plumbing on the Activity side changes this. Two paths, both invasive:

- Move `CaptureController` out of the Activity and give it a synthetic `LifecycleOwner` tied to the service's lifetime. Camera stays bound while the service is foregrounded; the viewfinder rebinds to the Activity on resume. Triple-bind (`Preview + ImageCapture + VideoCapture`) contention semantics all need re-validation.
- Swap `CameraX.Recorder` for `MediaRecorder` + `Camera2` `CameraCaptureSession` on a dedicated `HandlerThread`. Loses CameraX's orientation/rotation handling; would need to re-implement the `tkhd.matrix` write that `Recorder` does automatically.

Either is a multi-day refactor and also requires `FOREGROUND_SERVICE_CAMERA`. Defer until voice FGS has shipped and the same request returns for video.

### Architectural rules

- The service is an **anchor**, not a new owner of the recorder. Keep all state in `CaptureViewModel` / `AppContainer` so `CaptureScreen`, `OrphanRecovery`, and `BundleWorker` don't need to learn about the service.
- Notification content matches the active modality (`Voice memo recording…` / `Video recording…`) and a tap on the notification body returns to `CaptureScreen`. The Stop action stops the take and leaves the app in whatever state the user last had it in.
- Orphan recovery is unchanged — FGS reduces the probability of a low-memory kill, doesn't eliminate it. `.order` log + the `.m4a`'s `moov`-atom flush cadence are still what restore a torn voice file.

### Open questions

- Dismissibility: on API 34+ the user can swipe the notification away. `setOngoing(true)` (can't dismiss while recording) is the safer default; confirm before shipping.
- Does the user expect a PiP viewfinder while VIDEO-backgrounded? Probably no — PiP is its own scope — but worth flagging.
- If the user backgrounds during VIDEO (voice has FGS, video doesn't), should the app **block** backgrounding with a snackbar, or let the recording get cut? MVP is the latter; product decision pending once voice FGS lands.

