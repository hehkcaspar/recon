# Camera direction, layout logic and validation backlog

2026-11-01. Design decisions and future coding guidance; no Android implementation in this batch.

## Correct the design from available evidence

The supplied screenshots show the rear PHOTO preview tall (3:4) in the wide inner window and wide (4:3) in the tall inner window. That is enough to replace the previous incorrect compositions. No additional hardware verification is a prerequisite for this design correction.

Keep the most likely correct design and record residual verification tasks. Defer only genuinely unresolved alternatives. The revised front and Ultra scenes follow the same pose study, explicitly as fixtures. They do not assert a sensor angle or front-camera mirroring policy.

## Common ratios: output choice is not sensor shape

Official Samsung support lists 3:4, 9:16, 1:1 and Full, with availability dependent on the device. Pixel Camera offers full-image 4:3 and wide-crop 16:9. The upright dimensions swap on a quarter-turn: 4:3 ↔ 3:4 and 16:9 ↔ 9:16; square stays square. [Samsung ratio choices](https://www.samsung.com/ae/support/mobile-devices/adjusting-the-camera-ratio-on-my-samsung-phone/), [Pixel Camera settings](https://support.google.com/pixelcamera/answer/2838995?hl=en-NZ).

These facts do not imply every sensor is 4:3 or every selected output uses the entire sensor. Selecting 16:9, square or Full can deliberately crop a sensor image. “No preview crop” means no *additional* window-filling crop after that selected output framing. Full is device/output dependent, not a fixed synonym for 9:16.

The [aspect-ratio sheet](recon-capture-aspect-ratios.svg) demonstrates 3:4 PHOTO, 9:16 PHOTO and VIDEO, square PHOTO and variable Full-style examples on phone, cover and inner windows. Wide 4:3 and 16:9 frame directions are also covered by the inner matrices. These are layout capability fixtures, not a new ratio-selector product feature. Read the actual negotiated output rather than hardcoding 3:4 for every preview.

On a 360 × 576 cover with 24-unit top/bottom safe fixtures, a complete 9:16 frame fits at 297 × 528. A 360-wide frame would need 640 height, so it cannot both fill the width and preserve all content. The revised layout fills available height, overlays controls and accepts narrow side bars. A 3:4 frame fits full width at 360 × 480. This is a mathematical constraint, not a reason to shrink both variants into a small control-free rectangle.

## Reliable implementation model

Keep two small inputs independent:

- **Window input:** measured usable bounds, safe drawing/gesture insets, separating-feature rectangles and chosen dock side.
- **Camera input:** selected camera/capabilities, delivered buffer/crop, rotation into preview coordinates, mirroring and chosen output framing.

Use the existing CameraX `PreviewView` for preview transformation. Keep `FIT_CENTER`; do not add a Compose quarter-turn based on window aspect. Android explicitly describes foldables changing display aspect without corresponding camera rotation. [Camera preview guidance](https://developer.android.com/media/camera/camera2/camera-preview).

For layout planning, read the bound Preview’s `resolutionInfo` (`cropRect`, `resolution`, `rotationDegrees`) after binding and refresh it when rotation/configuration changes. Ensure its target rotation matches the intended preview coordinate space, rather than a different still-capture orientation under rotation lock. [ResolutionInfo](https://developer.android.com/reference/androidx/camera/core/ResolutionInfo). A 90° or 270° rotation swaps its width/height; 0° or 180° does not. The ratio after that transform is the upright frame ratio. `CameraInfo.getSensorRotationDegrees(targetRotation)` helps relate sensor coordinates to the target, but is not a substitute for the delivered crop/resolution or PreviewView's transform. Do not attach a competing transformation listener that steals PreviewView's ownership. [CameraInfo](https://developer.android.com/reference/androidx/camera/core/CameraInfo#getSensorRotationDegrees(int)), [PreviewView](https://developer.android.com/reference/androidx/camera/view/PreviewView).

Conceptual layout calculation (not a replacement camera transform):

```text
uprightFrame = rotateDimensions(deliveredCrop, resolvedPreviewRotation)
r = uprightFrame.width / uprightFrame.height
availablePane = usableWindow minus separatingFeature
candidateBounds = pane minus controls, queue, travel and insets
width = min(candidateBounds.width, candidateBounds.height * r)
height = width / r
center this rectangle within candidateBounds
```

On short compact windows, first try a full-width, aspect-preserving preview with controls over a bottom contrast scrim, as demonstrated by the supplied native camera. Reserve safe target and gesture-travel bounds, but do not subtract their entire overlay area from the preview. Overlay controls own their input before the preview. Where separate regions fit well, allocate full-size controls before fitting preview. Evaluate side and bottom arrangements using these constraints. Prefer the arrangement with materially better fitted frame area while keeping the capture group together. Retain the current arrangement through small resizes; use hysteresis rather than flipping at width = height. The exact switch tolerance is a tuning parameter, not an established Android guideline. During an active pointer gesture, retain its owner and geometry or cancel without mutation before relayout.

The prototype demonstrates a right dock for tall frames and bottom dock for wide frames. This is an example result, not a new `if(frameIsTall)` universal rule. A separate left-hand case moves the dock without reversing text, media, chronological order or gesture meanings. The horizontal mode strip remains centered in the top bar in every layout: mode changes are less frequent and preview swiping provides the quick alternative. Horizontal zoom sits directly above the shutter. Side shells with tall frames use an opposite-side vertical zoom rail. Preserve gesture meanings and shutter/queue anchors across modality changes.

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

Anchor bottom layouts using full-window coordinates minus the union of safe drawing/gesture bottom insets,24dp gesture corridor and selected strip height (72 standard /64 dense). Do not sum overlapping insets or subtract twice. Wide side docks retain48dp below the strip as a visual-balance exception. Dense controls are explicitly authored64dp shutters/48dp tiles with labels unchanged and targets≥48dp; choose provisionally below 600dp usable height. Freeze layout/profile during a gesture or cancel first.

The prototype now proposes deletion after >24dp net downward movement plus valid release, rendered travel clamped 32dp. Native currently uses>40dp and has not been changed. This is a deliberate interaction-tuning proposal, needed to reclaim idle space without clipping feedback. Axis ownership must prevent scroll/reorder drift becoming deletion; no velocity-only completion, reversal disarms, cancellation wins before release. Standard tile bottom64 +32 =96; dense 56 +32 =88; both fit strip+24. Keep glow within those bounds. Test worst-case bottommost starts, accidental activation, OS gesture navigation/three-button/taskbar insets and TalkBack before implementation acceptance. [Insets](https://developer.android.com/develop/ui/compose/system/insets), [touch targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

Queue gesture arbitration must distinguish horizontal scrolling, long-press reorder, vertical item deletion, gap split and edge mutation by fixed handle priority at pointer-down, then exposed item/gap regions and intent. No velocity-only destructive completion. Long text must wrap above the fixed capture/queue geometry; recording locks must cover semantic actions as well as touch. See [component contract](COMPONENT-CONTRACT.md).

## Existing code touchpoints

- `CameraPreview.kt`: already uses `FIT_CENTER`; retain its ownership of camera presentation.
- `CaptureScreen.kt`: fixed 3:4 container must become independently fitted frame geometry.
- `CaptureController.kt` / `OrientationCodec.kt`: audit active-display basis, 180° changes and transition handling; keep ongoing recording stable.
- `QueueStrip.kt`: cancellation currently routes through release logic. Separate it; cancellation must not commit/discard. Replace overlapping edge/gap ownership with the documented initial-owner contract (fixed handles win over overflowing media).
- `CaptureViewModel.kt`: guard reorder and every queue mutation during recording, including semantic actions.
- Manifest: portrait restriction belongs to the future adaptive implementation batch, not this SVG work.

## Backlog — validate the revised design, not the discarded draft

1. Device transform trace: camera ID/facing, active display/rotation, buffer/crop, target rotation, sensor relation and fold state; rear/front, both physical rotations, both displays, rotation lock and 180°.
2. Frame/output agreement: photograph a four-corner grid and circle; compare preview with JPEG/EXIF and video playback. Check mirrored preview/output policy separately.
3. Recording: rotate repeatedly without a new file or timer reset; fold/unfold once and under repeated callbacks; check one finalized queue item, audio continuity and recovery.
4. Grip test: one-handed left/right and two-handed, mode→zoom→shutter→queue, with a case and realistic thumb reach. Tune dock placement/hysteresis from this evidence.
5. Gesture tests: slow/fast/reversed/cancelled edge drags, item pull, scroll/reorder, split gaps, interruption by rotation/recording/OS Back. Check neither destructive action nor duplication on cancellation.
6. Accessibility: TalkBack and switch access alternatives, large text/localization, accessible timeout/announcements, three-button navigation, cutouts and larger insets.
7. Minimum-window fallback and horizontal tabletop composition: prototype a scrollable/direct-action fallback if real supported panes fall below the illustrated minimum. This is a deferred layout variant, not a reason to retain wrong existing frames.

Static geometry, portability and independent visual inspection are acceptance evidence for these SVGs. They do not replace the backlog's runtime tests.

### Overflow queue implementation acceptance

Use a clipped, alpha-masked scrolling media layer with stationary edge handles above it. Keep standard 56dp/dense 48dp tiles,6dp ordinary gaps,24dp split gaps and unchanged 48 × 48dp handle targets. Handle regions win pointer-down over media underneath; opacity must never enable click-through. Preserve gesture ownership after pointer-down, including reorder auto-scroll across an edge. Keep endpoint tiles fully accessible and derive accessible bounds from exposed interaction regions, not raw tile bounds. Test both 312dp and 360dp queues at start/middle/end, split near an edge, active commit/discard, scroll cancellation and TalkBack navigation. These are prototype contracts, not implemented runtime behavior.
