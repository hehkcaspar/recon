# Camera direction, layout logic and validation backlog

**Design C is the current foldable static implementation direction · 2026-11-03.** A/B are archived studies. C keeps preview geometry stable, separates the queue from the shutter group, follows pose for queue axis, and derives capacity after endpoint safety margins. Runtime WindowMetrics, density, insets, hinge/separator bounds and device acceptance remain pending; there is no fixed 400dp product switch. See the [C geometry](foldable-c-geometry.json) and [A/B archive](../archive/foldable-layout-a-b.md).

Updated 2026-11-03. Static design contract and native validation backlog.

## Correct the design from available evidence

The supplied screenshots show the rear PHOTO preview tall (3:4) in the wide inner window and wide (4:3) in the tall inner window. That is enough to replace the previous incorrect compositions. No additional hardware verification is a prerequisite for this design correction.

Keep the most likely correct design and record residual verification tasks. Defer only genuinely unresolved alternatives. The revised front and Ultra scenes follow the same pose study, explicitly as fixtures. They do not assert a sensor angle or front-camera mirroring policy.

## Common ratios: output choice is not sensor shape

Official Samsung support lists 3:4, 9:16, 1:1 and Full, with availability dependent on the device. Pixel Camera offers full-image 4:3 and wide-crop 16:9. The upright dimensions swap on a quarter-turn: 4:3 ↔ 3:4 and 16:9 ↔ 9:16; square stays square. [Samsung ratio choices](https://www.samsung.com/ae/support/mobile-devices/adjusting-the-camera-ratio-on-my-samsung-phone/), [Pixel Camera settings](https://support.google.com/pixelcamera/answer/2838995?hl=en-NZ).

These facts do not imply every sensor is 4:3 or every selected output uses the entire sensor. Selecting 16:9, square or Full can deliberately crop a sensor image. “No preview crop” means no *additional* window-filling crop after that selected output framing. Full is device/output dependent, not a fixed synonym for 9:16.

The [aspect-ratio sheet](recon-capture-aspect-ratios.svg) demonstrates 3:4 PHOTO, 9:16 PHOTO and VIDEO, square PHOTO and variable Full-style examples on phone, cover and inner windows. Wide 4:3 and 16:9 frame directions are also covered by the inner matrices. These are layout capability fixtures, not a new ratio-selector product feature. Read the actual negotiated output rather than hardcoding 3:4 for every preview.

On a 360 × 576 cover with 24-unit top/bottom safe fixtures, a complete 9:16 frame fits at 297 × 528. A 360-wide frame would need 640 height, so it cannot both fill the width and preserve all content. The revised layout fills available height, overlays controls and accepts narrow side bars. A 3:4 frame fits full width at 360 × 480. This is a mathematical constraint, not a reason to shrink both variants into a small control-free rectangle.

## Reliable implementation model

Keep the active display pose, window geometry and delivered camera stream independent:

- **Pose input:** active display transform relative to the unfolded grip, including opposite grips.
- **Window input:** measured usable bounds, safe drawing/gesture insets, separating-feature rectangles and chosen dock side.
- **Camera input:** selected camera/capabilities, delivered buffer/crop, rotation into preview coordinates, mirroring and chosen output framing.

Use the existing CameraX `PreviewView` for preview transformation. Keep `FIT_CENTER`; do not add a Compose quarter-turn based on window aspect. Android explicitly describes foldables changing display aspect without corresponding camera rotation. [Camera preview guidance](https://developer.android.com/media/camera/camera2/camera-preview).

For layout planning, read the bound Preview’s `resolutionInfo` (`cropRect`, `resolution`, `rotationDegrees`) after binding and refresh it when rotation/configuration changes. Ensure its target rotation matches the intended preview coordinate space, rather than a different still-capture orientation under rotation lock. [ResolutionInfo](https://developer.android.com/reference/androidx/camera/core/ResolutionInfo). A 90° or 270° rotation swaps its width/height; 0° or 180° does not. The ratio after that transform is the upright frame ratio. `CameraInfo.getSensorRotationDegrees(targetRotation)` helps relate sensor coordinates to the target, but is not a substitute for the delivered crop/resolution or PreviewView's transform. Do not attach a competing transformation listener that steals PreviewView's ownership. [CameraInfo](https://developer.android.com/reference/androidx/camera/core/CameraInfo#getSensorRotationDegrees(int)), [PreviewView](https://developer.android.com/reference/androidx/camera/view/PreviewView).

Conceptual layout calculation (not a replacement camera transform):

```text
uprightFrame = rotateDimensions(deliveredCrop, resolvedPreviewRotation)
r = uprightFrame.width / uprightFrame.height
availablePane = usableWindow minus separatingFeature
for flat full-screen inner rotation: map the stored chassis rectangle into this display, then return
for other layouts: candidateBounds = pane minus controls, queue, travel and insets
width = min(candidateBounds.width, candidateBounds.height * r)
height = width / r
center this rectangle within candidateBounds
```

On short compact windows, first try a full-width, aspect-preserving preview with controls over a bottom contrast scrim, as demonstrated by the supplied native camera. Reserve safe target and gesture-travel bounds, but do not subtract their entire overlay area from the preview. Overlay controls own their input before the preview. Where separate regions fit well, allocate full-size controls before fitting preview. Evaluate side and bottom arrangements using these constraints. Prefer the arrangement with materially better fitted frame area while keeping the capture group together. Retain the current arrangement through small resizes; use hysteresis rather than flipping at width = height. The exact switch tolerance is a tuning parameter, not an established Android guideline. During an active pointer gesture, retain its owner and geometry or cancel without mutation before relayout.

On a flat, full-screen inner display the utility edge is 80dp in chassis coordinates. Bundles and Settings have 48dp targets set 24dp from both adjoining edges, leaving 8dp between the touch target and the preview. The visible glyph centers are 40dp from each adjacent chassis edge and 40dp from the frame. C treats the preview as the stable physical field and reflows only controls and queue: upright queue is vertical in the side rail, CW90 queue is horizontal in the blank band below the preview, and the queue/shutter centers share the active axis. The outer CW90 margins seek approximately 1:1:1 visual balance; numbers are device outcomes. Compute this frame once per settled display, framing and usable-pane configuration, then map its corners into active display coordinates on each turn. CameraX owns the pixel transform. Resizes, separating hinges and changed stream geometry may require a new fit. See [C matrix](recon-capture-fold8-c.svg), [Ultra C matrix](recon-capture-fold8-ultra-c.svg) and [C geometry](foldable-c-geometry.json).

`FIT_CENTER` preserves the delivered stream when fitting. It cannot establish equal field of view across Preview/ImageCapture/VideoCapture. If their crops differ, configure a consistent selected capture viewport and use-case group; do not choose a viewport merely to fill the UI window. CameraX supports synchronized sensor crop regions through `ViewPort`/`UseCaseGroup`. [Crop configuration](https://developer.android.com/media/camera/camerax/configuration#crop-rect).

## Exceptions to cover in code

| Scenario | Required behavior |
| --- | --- |
| Unfold without turning the handset | Window changes; camera upright direction need not change. Recompute layout from independent inputs. |
| Window resized / split-screen / desktop window | Width/height is not a physical camera-orientation signal. |
| Portrait-natural versus landscape-natural display | Interpret rotations against the active display's reference; avoid universal sensor-angle constants. |
| Rotation lock / 180° change | UI/display rotation and physical capture target can differ. Follow the chosen target-rotation policy and listen for changes that do not recreate the Activity. |
| Front, rear, external or logical camera | Query selected capabilities/transform; front is not inherently a different aspect ratio or a universal mirror decision. |
| Fold-state-dependent logical sensor | Do not cache one permanent `SENSOR_ORIENTATION`; API 32+ may report state-dependent values. For per-frame Camera2 analysis, inspect active physical camera metadata. |
| Not-yet-ready surface / transient zero bounds | Keep last valid geometry or a neutral loading surface; never calculate from zero dimensions. Disable capture until ready. |
| Separating hinge / tabletop posture | Derive usable panes from feature bounds. Keep preview and controls inside usable regions; a flat nonseparating crease does not require wasting half the display. |
| Continuous recording during rotation | Preserve the recording/session. Update presentation without restarting capture. Do not promise per-frame upright video rewriting. |
| Actual fold/unfold boundary | Finalize once, preserve queue ordering, then settle/rebind if required. Repeated layout callbacks must not duplicate finalization. |

Sources: [CameraX rotation policies](https://developer.android.com/media/camera/camerax/orientation-rotation), [SENSOR_ORIENTATION](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_ORIENTATION), [FoldingFeature](https://developer.android.com/reference/androidx/window/layout/FoldingFeature), [VideoCapture target rotation](https://developer.android.com/reference/androidx/camera/video/VideoCapture#setTargetRotation(int)). A recording's output orientation follows its start configuration; changing the target affects subsequent Recorder recordings.

## Safe zones and gestures

Android Back begins at either physical side edge; Home/quick-switch occupy the bottom. Layout must use actual `safeDrawing` and `safeGestures`/mandatory gesture information, not the 24-unit fixture values. Broad gesture exclusion is not a substitute for usable layout. Place internal queue handle start zones inside usable bounds. [Insets](https://developer.android.com/develop/ui/compose/system/insets), [gesture navigation](https://developer.android.com/develop/ui/views/touch-and-input/gestures/gesturenav).

Anchor bottom layouts using full-window coordinates minus the union of safe drawing/gesture bottom insets,24dp gesture corridor and the selected strip height (72 standard /64 compact). Do not sum overlapping insets or subtract twice. Wide side docks retain48dp below the strip as a visual-balance exception. Compact controls are explicitly authored64dp shutters/48dp tiles with labels unchanged and targets≥48dp; use the `<600dp` height check only when the queue rectangle cannot be measured. Freeze layout/profile during a gesture or cancel first.

The prototype proposes deletion after >24dp net downward movement plus valid release, rendered travel clamped 32dp. The native queue now uses that gate, dense 48dp tiles, cancellation-safe edge actions and measured reorder geometry. Axis ownership must still prevent scroll/reorder drift becoming deletion; no velocity-only completion, reversal disarms, cancellation wins before release. Standard tile bottom64 +32 =96; dense 48 +32 =80; both fit strip+24. Keep glow within those bounds. Test worst-case bottommost starts, accidental activation, OS gesture navigation/three-button/taskbar insets and TalkBack before implementation acceptance. [Insets](https://developer.android.com/develop/ui/compose/system/insets), [touch targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

Queue gesture arbitration must distinguish horizontal scrolling, long-press reorder, vertical item deletion, gap split and edge mutation by fixed handle priority at pointer-down, then exposed item/gap regions and intent. No velocity-only destructive completion. Long text must wrap above the fixed capture/queue geometry; recording locks must cover semantic actions as well as touch. See [component contract](COMPONENT-CONTRACT.md).

## Existing code touchpoints

- `CameraPreview.kt`: uses `FIT_CENTER`; retain its ownership of camera presentation and keep focus taps inside the delivered fitted frame.
- `CaptureScreen.kt`: adaptive bottom/side shells now consume delivered frame geometry and safe drawing insets; simulator posture/resize acceptance is recorded below.
- `CaptureController.kt` / `OrientationCodec.kt`: audit active-display basis, 180° changes and transition handling; keep ongoing recording stable.
- `QueueStrip.kt`: cancellation currently routes through release logic. Separate it; cancellation must not commit/discard. Replace overlapping edge/gap ownership with the documented initial-owner contract (fixed handles win over overflowing media).
- `CaptureViewModel.kt`: guard reorder and every queue mutation during recording, including semantic actions.
- Manifest: portrait restriction has been removed; handled orientation/size changes are retained in the capture host.

## Backlog — validate the revised design, not the discarded draft

1. Device transform trace: camera ID/facing, active display/rotation, buffer/crop, target rotation, sensor relation and fold state; rear/front, both physical rotations, both displays, rotation lock and 180°.
2. Frame/output agreement: photograph a four-corner grid and circle; compare preview with JPEG/EXIF and video playback. Check mirrored preview/output policy separately.
3. Recording: rotate repeatedly without a new file or timer reset; fold/unfold once and under repeated callbacks; check one finalized queue item, audio continuity and recovery.
4. Grip test: one-handed left/right and two-handed, mode→zoom→shutter→queue, with a case and realistic thumb reach. Tune dock placement/hysteresis from this evidence.
5. Gesture tests: slow/fast/reversed/cancelled edge drags, item pull, scroll/reorder, split gaps, interruption by rotation/recording/OS Back. Check neither destructive action nor duplication on cancellation.
6. Accessibility: TalkBack and switch access alternatives, large text/localization, accessible timeout/announcements, three-button navigation, cutouts and larger insets.
7. Minimum-window fallback and horizontal tabletop composition: prototype a scrollable/direct-action fallback if real supported panes fall below the illustrated minimum. This is a deferred layout variant, not a reason to retain wrong existing frames.

Static geometry, portability and independent visual inspection are acceptance evidence for these SVGs. They do not replace the backlog's runtime tests.

## Native evidence recorded 2026-11-02

Verified in this workstream:

- `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes with 151 JVM tests, including recording-gate, fold-transition, preview-frame, adaptive-layout and queue-arbitration fixtures.
- The API 36 `recon-api36` emulator installs and launches the debug APK without an app fatal exception. Portrait and forced-landscape rotation leave `MainActivity` resumed.
- The Pixel Fold API 36 AVD (`recon-fold-api36`) launches the debug APK on its 2076×2152 inner display. Open, half-open, closed, and rear-display device states leave the activity present; forced landscape and a reversible 1200×800 display resize also leave the activity resumed. The regular API 36 AVD likewise survives forced landscape and a reversible 900×600 resize.
- With the fold AVD launched using supported `-camera-back emulated -camera-front emulated` flags, the camera service exposes two devices and CameraX opens rear `CameraId-0` with an active combined session. The rendered virtual-scene image is an emulator test pattern; it is live-stream evidence only, not a valid framing or saved-output reference.
- The near-square standard fold-inner fallback now ends the 3:4 PreviewView before the shutter/control row so the native hit surface cannot claim the controls. On the 2076×2152 inner display the rebuilt screen exposed the TextureView at `x=664..1412, y=273..1601` and the shutter/control row at `y=1738..1933`; dense phone/cover fixtures retain their overlay geometry.
- The capture feedback host now renders Error → Undo → Saved in one slot, pauses the discard undo timeout while an error is visible, preserves queued saved confirmations behind higher-priority feedback, exposes live-region announcements, and rejects Undo while capture is busy.
- The tutorial now requests accessibility focus inside a marked traversal group and clears focus on dismissal; capture geometry consumes `safeContent` (drawing plus gesture-protected insets), and divider drags reset on cancellation without committing.
- Long-press thumbnail reorder now retains ownership while auto-scrolling the queue when the held item reaches either viewport edge; the auto-scroll stops on release or cancellation.
- Short queue content now centers within the measured tray while overflow retains its horizontal scroll extent.
- `CameraPreview` now listens to `DisplayManager` changes for the remembered `PreviewView`, covering same-bounds 180° changes that would not necessarily trigger a Compose layout callback.
- The shared video/voice finalizer now clears a lifecycle/fold stop request that races an already-running stop, preventing that stale request from stopping the next take.
- Landscape capture now applies the pure adaptive preview/control/queue rectangles directly: the preview occupies the pane opposite the side rail, the queue follows the settled pose axis, and horizontal hinge panes constrain the fitted frame to the selected pane.
- Dock selection now compares fitted preview area for short landscape versus expanded windows, with regression fixtures for both choices; the compact tutorial demo is height-bounded so its text and actions remain reachable.
- Thumbnail gestures now use one explicit owner per pointer sequence: vertical delete, horizontal tray scroll cancellation, or long-press reorder; release and cancellation reset both visual travel and queue mutation state.
- Stationary edge handles now paint a 48dp fade over overflow media, and divider gaps expose direct Split/Join custom accessibility actions alongside their swipe behavior.
- VOICE now keeps the camera session bound for fast return while disabling PreviewView focus/pinch handlers and removing zoom semantics/hit regions; only the anchor spacer remains.
- Dock resolution now has a 12% fitted-area hysteresis margin, retaining the settled side/bottom choice through small window resizes and switching only when the new arrangement is materially better.
- `./gradlew :app:connectedDebugAndroidTest` completes on both API 36 simulators. The generated reports contain **6 tests per simulator, 12 total, 0 failures** across `recon-api36` and `recon-fold-api36`: five `CaptureAccessibilityTest` cases covering feedback action reachability, tutorial Skip accessibility, recording-mode semantic lockout, in-memory JPEG crop dimensions, and divider Split/Join actions, plus `CaptureActivityRotationTest` covering the real setup activity through a landscape request.
- The same 12-test connected suite also passes on both simulators with 1.3× system text and three-button navigation enabled; the settings were restored to 1.0× and gesture navigation after the run.
- The same 12-test connected suite also passes on both simulators with the installed TalkBack service enabled; TalkBack was disabled after the run. This validates Compose semantics/action discovery under the service, not a full exploratory TalkBack capture session.
- The same 12-test connected suite also passes on both simulators with the installed Switch Access service enabled; Switch Access was disabled after the run. This validates Compose semantics/action discovery under the service, not a full exploratory Switch Access capture session.

Additional framing-pass evidence from the same date:

- `CaptureController` now binds the preview, still and video use cases in a shared CameraX `UseCaseGroup` with `PreviewView.getViewPort()` and the active display rotation. The in-memory photo path applies `ImageProxy.cropRect` before it writes the staged JPEG. The first preview bind waits for a non-zero `PreviewView` size, while later resizes do not renegotiate a live recording.
- The default AVD configuration still exposes only a rear camera, so CameraX's validator reports `CameraIdListIncorrectException: Expected camera missing from device` while checking the front selector. The explicit two-camera launch removes that initialization gap. On the foldable AVD, a native shutter tap queued a photo (`Photo 1 of 1`), the left commit gesture wrote `bundles/2026-11-02-s-0001/photos/2026-11-02-s-0001-p-001.jpg` and `stitched/2026-11-02-s-0001-stitch.jpg`, and a short VIDEO take finalized once and wrote `bundles/2026-11-02-s-0002/videos/2026-11-02-s-0002-v-001.mp4`. The JPEGs are valid JPEG/Exif files and the video is a valid ISO MP4 container; the virtual-scene content remains an emulator test pattern rather than a valid framing target.
- The saved MP4 opened in the AVD's native Photos viewer through a video `VIEW` intent. The player exposed a live `Pause` action, a `SurfaceView`, and an advancing `0:24 / 0:34` timeline, providing decoded playback evidence on the foldable simulator.

Still required before release sign-off:

- Simulator saved-output, queue and decoded-playback evidence is now recorded for photo, stitched photo and video finalization. A framing/focus comparison against a four-corner target remains open because the AVD provider supplies only the synthetic color-block scene; no physical phone or foldable is required for this goal.
- Simulator evidence for 1.3× text, three-button navigation, TalkBack-enabled semantics, and Switch Access-enabled semantics is recorded above. Actual held-device grip is outside this goal.
- Broader capture instrumentation coverage remains: add simulator-facing tests for layout panes, queue gesture outcomes, and fold/lifecycle finalization before release sign-off. Activity rotation, recording semantic lockout, divider actions, and the in-memory crop boundary now have connected coverage.

### Overflow queue implementation acceptance

Use a clipped, alpha-masked scrolling media layer with stationary edge handles above it. The legacy native queue uses dynamic `EdgeZone` sizing (currently a 60dp minimum edge region with a 48dp fade); that implementation detail is distinct from the C static contract, where `standard56` endpoint targets are 56 × 56dp and `compact48` endpoint targets are 48 × 48dp. Both profiles use 6dp ordinary gaps, 24dp split gaps and visible handlers of 6 × 36dp or 6 × 28dp respectively. Handle regions win pointer-down over media underneath; opacity must never enable click-through. Preserve gesture ownership after pointer-down, including reorder auto-scroll across an edge. Keep endpoint tiles fully accessible and derive accessible bounds from exposed interaction regions, not raw tile bounds. Test both 312dp and 360dp queues at start/middle/end, split near an edge, active commit/discard, scroll cancellation and TalkBack navigation. Native cancellation and accessibility behavior still need broader simulator acceptance at the documented queue sizes and navigation modes.

## Complete state composition and reference coverage

`recon-capture-recording-states.svg` closes the static state-composition gap with phone, cover and inner VIDEO/VOICE windows. The native capture now disables Settings/Library and queue mutations during recording, routes recorder failure through the shared finalizer, and keeps timer/shutter/queue state together. A recoverable queue/storage message can coexist below the recording timer; native large-text and TalkBack acceptance remain open, while fold posture/resize smoke has been verified on the Pixel Fold AVD.

Use the current singleton rule in the component contract. Choose side versus bottom layout by fitted frame area and reach, not just frame orientation: the free-window near-square case uses a bottom dock. Center short square frames in the area above controls instead of always pinning them to the header.
