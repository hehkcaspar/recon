# Recon — Post-MVP Backlog

Ideas captured during MVP design that are **not** scoped for the current milestone. The core capture-and-commit loop (shoot → queue → swipe → bundle on disk) is complete; these items describe how the product could grow once the core is stable. They are intentionally speculative — architecture sketches, not commitments — and should be re-evaluated when their time comes.

For the product and interaction spec that *is* shipped, see [`recon-mvp-designs.md`](./recon-mvp-designs.md). For the Android reference implementation, see [`README.md`](./README.md).

---

## 1. OCR / document-understanding pipeline for bundle → Markdown

Post-process a committed bundle (raw JPEGs + stitched image) into a structured Markdown document written to `bundles/{bundle-id}/{bundle-id}.md` alongside the existing outputs.

**Scope note.** Recon is a personal-use project, so license terms (AGPL-3.0 in particular) are not constraints, and the design criterion collapses to "which path produces the highest-quality output". That changes the architecture meaningfully — the cherry-pick-Apache-2.0-models path that earlier drafts treated as a middle tier was a license-driven workaround; without the AGPL constraint and with quality as the goal, it's strictly dominated by running real MinerU off-device. The shape reduces to two paths: a fast on-device fallback and a high-quality desktop companion.

### Best-quality path — MinerU VLM on a desktop companion

Run [MinerU](https://github.com/opendatalab/mineru) (current `mineru` 3.0.x as of April 2026) on a personal desktop, reached over the LAN via the LocalSend interop in item 2 below. Use the **VLM backend** (`MinerU2.5-2509-1.2B` — 1.16B-param Qwen2-VL derivative, ~4.6 GB BF16; or `MinerU2.5-Pro-2510` if hardware permits) for the strongest open-source document-understanding output currently available: layout-faithful Markdown with LaTeX formulas, HTML tables, and learned reading order.

Realistic hardware expectations: an Apple Silicon Mac with 16+ GB unified memory runs the VLM workably at CPU/MPS speeds (tens of seconds per page); a Linux/Windows box with a discrete GPU and 8+ GB VRAM runs it near-real-time. If GPU isn't available, the **pipeline backend** (`mineru -b pipeline`) is the workable second choice on CPU-only desktops — slower and slightly less faithful on complex layouts, but no GPU dependency.

### Offline fallback — ML Kit Text Recognition v2 on-device

When no companion is reachable (no LAN, desktop off, on the road), chain a separate `OneTimeWorkRequest` after `BundleWorker` that runs **ML Kit Text Recognition v2** (`com.google.mlkit:text-recognition:16.0.1` for Latin bundled, ~4 MB APK delta per script per ABI; or `play-services-mlkit-text-recognition:19.0.1` unbundled, ~260 KB APK + ~1.5–4 MB Play-Services model fetched on first call; supports Latin / CJK / Japanese / Korean / Devanagari as separate artifacts).

Pure CPU via TFLite + XNNPACK — **no NNAPI / GPU acceleration** for text recognition, so SD 7-class NPU/DSP buys nothing. Latency at full 12MP: ~500–900 ms warm on SD 7-class, ~250–500 ms on Pixel 7a/8a, 1–3 s on SD 4/6-class; cold-start adds 100–400 ms. **Downscale to ~2 MP long-edge before inference** — Google's docs note characters need only 16×16 px and there's "no accuracy benefit beyond 24×24", and downscaling cuts latency ~6× with zero quality loss for document text.

Consumes raw per-page JPEGs from `bundles/{bundle-id}/` (never the stitched composite — stitch is for humans, raw pages are for the model), joins line-level text in reading order with one `## Page N` heading per photo, writes the `.md` sidecar via SAF. **Decode → process → recycle each Bitmap per page** — a 12MP ARGB_8888 bitmap is ~48 MB, so holding all 20 page bitmaps simultaneously will OOM the 512 MB heap; one at a time is comfortably safe.

**Reuse a single `TextRecognizer` for the whole bundle** and `close()` it in `finally` to release native resources; do **not** parallelize `process()` calls (Google's docs warn against concurrent recognizers and parallelism doesn't help anyway). Reuse the process-wide `workMutex` so OCR never races a stitch. WorkManager constraints: `setRequiresStorageNotLow(true)` + `setRequiresBatteryNotLow(true)`; explicitly **not** `setRequiresCharging` / `setRequiresDeviceIdle` (would defer by hours). Surface failures via the existing `ActionBanner`; no status rows. No formulas, no tables-as-HTML, modest reading-order — but lands a usable sidecar with zero setup, fully offline, no GMS dependency if Latin-bundled is used.

### Companion mechanics

The "MinerU companion" can be as thin as a Python script that watches an inbox folder, runs `mineru -p {file_or_dir} -o {out_dir} -b vlm` per bundle, and drops the result back. The architectural commitment is just "the bundle ships off-device; something out there produces a `.md`; the `.md` lands in `bundles/{bundle-id}/`". Concrete options ranked by setup effort:

- **Easiest:** a Syncthing-shared folder between phone and desktop, plus a desktop-side `inotifywait`/`fswatch` shell loop that runs `mineru` on each new bundle and writes the `.md` back into the same folder. Zero protocol work.
- **Cleaner:** a small Python receiver speaking the LocalSend protocol (item 2), accepting bundles, running MinerU, and replying via LocalSend's send-back. Pairs well with item 2's clean-room sender.
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
- The companion route doesn't touch the worker pipeline at all — it's a user-initiated send (item 2) followed by an inbound `.md` write whenever the desktop replies.

### Open questions

- Should the on-device ML Kit `.md` be considered "draft" and overwritten by the companion result, or kept under different filenames (`{bundle-id}.md` vs. `{bundle-id}.mineru.md`) so the two outputs can be compared?
- How does a future bundles browser surface the per-bundle state ("draft", "upgraded by companion", "send pending")?
- Should the companion script live in the Recon repo (`tools/mineru-companion/`) so the personal-use setup is one `git clone` + `pip install mineru` away?

---

## 2. LocalSend interop for peer-to-peer bundle transfer

Implement a clean-room Kotlin sender that speaks the [LocalSend protocol](https://github.com/localsend/protocol) (current spec **v2.1**, cut 2026-01-13; reference app v1.17.0) so a committed bundle (raw JPEGs + stitched image, plus any `.md` sidecar) can be pushed to any device on the LAN running LocalSend — desktop, phone, tablet, or our own MinerU companion from item 1.

We are **not** bundling the upstream Flutter app; just enough protocol to send. Realistic effort: **~2–4 days** for a working sender, plus 1–2 days for cert-fingerprint UX and SAF-streaming polish.

### Discovery

On share-sheet open, acquire a `WifiManager.MulticastLock`, open a `MulticastSocket` joined to `224.0.0.167:53317` on every non-loopback IPv4 interface, and emit one announce — JSON `{alias, version:"2.1", deviceModel, deviceType:"mobile", fingerprint, port:53317, protocol:"https", download:false, announce:true}`. Peers reply via `POST /api/localsend/v2/register` (preferred) or a multicast response with `announce:false`. Drop self-echoes by `fingerprint`. Release the lock the moment the sheet closes — the spec's "no background multicast chatter" rule is non-negotiable.

### Transport

HTTPS on TCP `53317`, prefix `/api/localsend/v2/…`. Use **OkHttp 4.12+** with a custom `X509TrustManager` that accepts any self-signed cert plus a no-op `HostnameVerifier`; trust is fingerprint-pinned (SHA-256 of the peer's leaf cert, captured at discovery and re-verified post-handshake), not CA-based. Generate one self-signed cert per install and cache it.

### Upload flow

Three calls per bundle:

1. `POST /api/localsend/v2/prepare-upload` with `{info, files:{fileId:{id, fileName, size, fileType:"image/jpeg", sha256?}}}` (optional `?pin=…`); receiver shows the trust prompt here, response is `{sessionId, files:{fileId:token}}`.
2. Per file, `POST /api/localsend/v2/upload?sessionId=…&fileId=…&token=…` with the raw binary as the request body — **not** multipart, one file per request, parallelizable across files within the bundle.
3. `POST /api/localsend/v2/cancel?sessionId=…` on user abort.

Errors: 401 PIN required, 403 rejected by user, 409 session blocked, 429 rate-limit, 204 "already received". No native resumability — a failed upload re-runs the whole file.

### SAF streaming

A custom `RequestBody.writeTo(sink)` that pulls from `contentResolver.openInputStream(documentFile.uri)` and copies in chunks. Never `readBytes()` the stitched JPEG (can be 20 MB+).

### Permissions

`INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_NETWORK_STATE`. `NEARBY_WIFI_DEVICES` (Android 13+) is **not** required for outbound multicast on an already-connected Wi-Fi network — skip it. JSON: reuse existing `kotlinx.serialization`; add `Info`, `PrepareUploadRequest`, `FileMetadata`, `PrepareUploadResponse`.

### UX shape

Action lives on a future bundles-browser row (long-press → action sheet → bottom sheet that scans on open and dismisses on close, listing peers as they register). Cert fingerprint shown as a short hex on first contact for trust-on-first-use. Gate send on `manifestStore.get(bundleId) == null` so we never ship a bundle whose `BundleWorker` is still running. Progress and failures use the existing `ActionBanner`; no persistent status rows. The capture screen's silence principle stays intact — no "share" affordance lives there.

### Edge cases

- Hotel / carrier networks blocking multicast (no fix — surface "no peers found").
- AP isolation hiding peers on the same SSID (same — user-actionable, not app-fixable).
- IPv6-only Wi-Fi where the IPv4 multicast group is unreachable (LocalSend desktop falls back to a `/24` legacy TCP sweep — defer this to v2, MVP can require IPv4).
- Upstream protocol breaking changes (pin to `v2.1` in README, accept that an eventual bump may be required).

### Open questions

- Trust-on-first-use vs. always show fingerprint.
- Whether to also implement **receive** (would need an embedded HTTP server — Ktor server-cio or NanoHTTPD — and a SAF-folder write target, useful symmetrically for getting the MinerU companion's `.md` back).
- Whether to allow batch-share of multiple bundles in one session or keep it one-bundle-per-share for simplicity.

---

## 3. Tutorial first-page — animated swipe-to-change-mode demo

The multimodal feature (v1.x → v2.x) added a 6-step gesture tutorial whose new first step is `SwitchModality` — swipe the preview or tap the pill at the top to switch between photo, video, and voice. The other five steps (commit, discard, delete-one, reorder, divide) each have a hand-drawn looping `DemoArea` animation that mimics the real gesture on a mock 4-thumbnail queue. **The modality step currently has only a title and description — no demo animation.** Users see a static block where the other steps show a looping mock gesture.

### Scope

Add a `ModalitySwipeDemo` composable in `GestureTutorial.kt` alongside the existing `CommitDemo` / `DiscardDemo` / `DeleteOneDemo` / `ReorderDemo` / `DivideDemo` composables (all in the same file), wire it into the `DemoArea` switch that currently routes the other five.

### Suggested visual

Mirror the capture-slab shape (3:4 rectangle) with a small 3-segment pill above it (`VIDEO · PHOTO · VOICE`). Animate:

1. A finger glyph enters from the right, hovers mid-slab, then drags leftward past 50% width.
2. The slab slides left following the finger; a ghost of the next modality (differently-tinted placeholder — e.g. a red-ringed record circle for VIDEO, a mic glyph for VOICE) enters from the appropriate side.
3. On release, slab settles to the new modality; pill indicator swaps to match.
4. Hold for ~600ms, reverse direction; loop.

Reuse the same `infiniteTransition` + `tween` pattern already in `CommitDemo` so timing feels consistent across steps. Don't render a real `CameraPreview` inside the demo — tint-block placeholders are enough and sidestep the preview-lifecycle complexity for an animated loop.

### Acceptance

- Fresh install: the modality step shows a looping animation with the same visual weight as the other five.
- Upgrader path: the modality step still shows as the only unseen step for pre-multimodal users; the animation is present.
- No new performance cost: the demo runs only while the overlay is visible, same as the other demos.

### Effort

~0.5–1 day. The pattern is well-established in `GestureTutorial.kt`; this is just drawing + animating, no new state plumbing.
