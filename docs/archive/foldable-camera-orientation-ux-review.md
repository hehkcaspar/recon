# Archived orientation investigation

Historical research from 2026-11-01, before the corrected SVG layouts. Statements below about “current” or “next” designs refer to that earlier snapshot. The screenshot-based corrections are now complete. For current rules use the [camera implementation guidance](../prototypes/camera-layout-implementation.md) and [design contract](../prototypes/README.md). Retained for evidence and rationale, not as an implementation gate.

---

# Foldable capture: orientation and grip review

Reviewed 2026-11-01. Scope: official-source research, supplied Samsung camera screenshots, Recon source and SVG review. No Android runtime changes or replacement screen designs. Ergonomic recommendations below are hypotheses to prototype and test, not measured comfort results.

## Decision

**The current inner-screen matrices are not an implementation-ready orientation or ergonomics contract.** They demonstrate undistorted vectors, but couple wide windows to wide camera frames and tall windows to tall frames. The supplied rear-camera screenshots demonstrate the missing, opposite combinations. Previous visual approvals did not validate this physical relationship.

Preserve the shared components and gesture meanings. Revise the layout policy before drawing another accepted matrix: determine the upright selected camera frame independently, then fit it into the available window and place reachable controls around it. Do not fix this by rotating a scene sideways or swapping every front/rear/Ultra example indiscriminately.

## What the supplied screenshots establish

Dimensions below were read from the original JPEGs. Preview ratios are approximate observations of visible preview boundaries, not measurements of sensor buffers, Android dp or saved files.

| File suffix | Screenshot pixels | Visible upright preview | Native composition |
| --- | --- | --- | --- |
| `212636_Camera.jpg` | 2448 × 1848, wide | Tall, approximately 3:4 | Central preview; zoom left; shutter and other capture controls right |
| `212642_Camera.jpg` | 1848 × 2448, tall | Wide, approximately 4:3 | Preview above; zoom and capture controls below |
| `212655_Camera.jpg` | 1248 × 1972, cover | Tall, approximately 3:4 | Compact shell with several controls over preview |

The user identifies this as Fold8 and the first two as its unfolded states. Samsung's official announcement confirms the standard model opens to a 4:3 display and describes rotating it for a taller reading layout. This supports the form-factor interpretation; it does **not** publish per-camera rotation metadata. [Samsung announcement](https://news.samsung.com/us/samsung-galaxy-z-fold8-ultra-fold8-flip8-foldables-perfected-every-way-of-living/)

The user's explanation is consistent with the evidence: opening the cover-held-upright device can widen the display while the rear camera remains in the same physical pose. Rotating that assembly changes the camera pose too. The screenshots do not establish Android `ROTATION_0`, a specific `SENSOR_ORIENTATION`, the inner selfie camera's mounting, mirroring, or the Ultra's mapping. Nor do they prove zero sensor/output crop: Samsung may apply a selected aspect, zoom or stabilization crop. They establish the visible composition we must accommodate.

## Orientation model: separate the coordinate systems

| Quantity | Meaning | Must not be inferred from |
| --- | --- | --- |
| Window shape | Current usable width and height | Camera lens or sensor orientation |
| Physical pose | How the chassis/camera is held relative to gravity | Window aspect alone |
| Display natural orientation and rotation | Display's reference basis and current rotation within that basis | The word “landscape” in an artboard label |
| Camera identity and facing | Selected logical/physical camera; rear, cover selfie or inner selfie | Screen shape; front does not identify every selfie sensor |
| Buffer/crop geometry and transform | Stream dimensions, selected content rectangle, rotation and mirror transform | A hardcoded rear90/front270 rule |
| Upright frame shape | Width/height of the selected content after the correct transform | UI shell orientation |
| Control arrangement | Side or bottom placement within usable regions | A command to rotate/crop camera content |

Google documents the exact category of mismatch on Pixel Fold: a portrait rear-camera preview can appear in a landscape inner display. This is an official counterexample to the old assumption, not proof of Fold8-specific metadata. [Google's foldable guidance](https://android-developers.googleblog.com/2023/06/pixel-fold-and-pixel-tablet-developers-guide.html)

Android's camera guide explicitly warns that folding can change display aspect without changing physical orientation. `PreviewView` handles camera/display transforms; keep that responsibility there instead of adding a second Compose rotation. [Camera preview](https://developer.android.com/media/camera/camera2/camera-preview)

For a selected buffer/crop rectangle of width `w` and height `h`, a quarter-turn swaps the dimensions; a half-turn does not. The layout consumes that resulting upright ratio. CameraX transformation information provides rotation, crop and mirroring information; use the appropriate supported API without intercepting or replacing `PreviewView`'s surface-transform ownership. [TransformationInfo](https://developer.android.com/reference/androidx/camera/core/SurfaceRequest.TransformationInfo)

Do not cache a universal sensor orientation: Android documents fold-state-dependent values for some logical cameras from API32. Query the relevant camera/state; per-frame physical-camera information may be necessary for diagnostics. [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_ORIENTATION)

Display-following preview and physical-pose-following capture under rotation lock are distinct policies. The earlier plan's blanket “display rotation is authoritative” wording is incomplete: CameraX documents both display listeners and orientation listeners depending on supported/locked orientations. Normalize their coordinate bases, handle 180° changes and active-display changes, and verify the chosen policy on hardware. This review does not prove the existing orientation conversion wrong on a particular device. [CameraX rotation guidance](https://developer.android.com/media/camera/camerax/orientation-rotation)

## Correct next prototype matrix

Use explicit labels such as `Wide window / upright tall frame / rear / photo`; keep physical pose and any measured display rotation in external annotations. Avoid the ambiguous `Landscape / Rear` label.

| Window | Upright PHOTO frame | Evidence / purpose |
| --- | --- | --- |
| Wide inner | Tall 3:4 | Supplied Fold8 rear screenshot; required primary case |
| Tall inner | Wide 4:3 | Supplied Fold8 rear screenshot after rotation; required primary case |
| Wide inner | Wide 4:3 | Other valid camera/display configurations; retain as a generic test |
| Tall inner | Tall 3:4 | Other valid camera/display configurations; retain as a generic test |

Test the same independent combinations for video using 9:16/16:9 selected output framing. Repeat verified physical states for each available front camera; until measured, label front/Ultra mappings as test scenarios. Photo screenshots alone cannot certify their video or selfie behavior.

Fit the **selected output frame**, not necessarily the full sensor active array. With a preview region `W × H` and upright ratio `r`, use `previewWidth = min(W, H × r)` and `previewHeight = previewWidth / r`. For example, a tall 3:4 frame in a 912 × 560 available region becomes 420 × 560; a wide 4:3 frame in a 672 × 608 region becomes 672 × 504. These illustrate geometry, not final control coordinates. Equal numerical panel/capture ratios do not guarantee a matching physical axis.

`FIT_CENTER` prevents additional cropping when fitting the delivered preview stream; it does not by itself guarantee that preview and saved photo/video share a field of view. Verify their crop/rotation/zoom agreement, including stabilization and aspect-ratio fallback. Keep all four frame corners visible and circles circular. [PreviewView scaling](https://developer.android.com/reference/androidx/camera/view/PreviewView.ScaleType)

Use `ViewPort`/`UseCaseGroup` if synchronized sensor crop regions are needed, including with `FIT_CENTER`. The viewport must represent the intended capture framing, not force the stream into the window's shape. This corrects the earlier plan's restriction to adding a viewport only after adopting `FILL_CENTER`. [CameraX crop configuration](https://developer.android.com/media/camera/camerax/configuration#crop-rect)

## Grip-first layout recommendation

The task sequence is **aim → choose mode/zoom → capture → inspect/manage queue → capture again**. Minimize grip changes through that sequence, not merely distance to the shutter. Thumb reach depends on hand size, grip, device dimensions and case; dp measures alone cannot certify it.

| Situation | Recommended next prototype | Reason to test |
| --- | --- | --- |
| Cover / traditional phone | Existing compact bottom capture group and horizontal queue | Familiar lower-screen access; preserve full preview within remaining space |
| Wide window + tall frame | Bounded central tall preview; compact capture cluster near a lower outer grip; horizontal queue dock nearby | Uses side space without changing camera direction; avoids the current high zoom / middle shutter / bottom-center queue triangle |
| Tall window + wide frame | Wide preview above; compact bottom control area | Native screenshot supports the arrangement; freed height can accommodate controls and queue |
| Near-square window | Compare side and bottom arrangements against actual frame ratio and available control space | A small width/height change should not cause a wholesale rearrangement |
| Tabletop / separating fold | Fit preview and controls in suitable separate regions | No primary action, focus target or queue gesture may straddle the separating region |

For two hands, Samsung's opposite-side zoom/shutter division is worth testing: one thumb operates zoom while the other captures. For one hand, that division can require reaching across the preview. Default recommendation: keep mode access, zoom and shutter together; permit a simple left/right dock preference using the same components. Do not infer handedness automatically or mirror text, chronology, or commit/discard meanings. Secondary settings can remain less prominent. Test the queue beside the grip as well as bottom-center before choosing final coordinates; do not add duplicate persistent shutters just to avoid making that decision.

An unfolded device may not support every queue-edit task comfortably with one hand. Provide reachable direct actions rather than claiming gesture-only one-hand usability. Keep the same two arrangements and one queue component; do not create a layout class per device model. Stabilize arrangement choice near size boundaries, and never relocate an active gesture's target under the finger. During recording keep Stop accessible and avoid gratuitous control movement; rotation still continues recording, while fold/unfold finalizes once.

Samsung's Flex guidance places viewing content above controls on a tabletop and advises keeping interactive elements away from the crease. A flat, non-separating crease is different from an occluding/separating hinge; do not unnecessarily split every unfolded preview. [Samsung foldable design](https://developer.samsung.com/one-ui/largescreen-and-foldable/designing_for_foldable.html), [FoldingFeature](https://developer.android.com/reference/androidx/window/layout/FoldingFeature)

## Gesture consequences

- Keep the queue **horizontal in the visible UI**, even alongside a portrait camera frame. Left edge → right remains commit; right edge → left remains discard. Down on an item remains delete; down/up in a gap remains split/unsplit. Do not rotate gesture axes with the camera buffer.
- “Queue edge” must mean an internal affordance, not the physical screen edge. Protect start zones and complete drag paths using actual gesture insets; use narrowly scoped exclusions only if necessary. Android Back enters from either side; Home/quick-switch occupy the bottom and cannot be opted out of in the same way. [Android gesture navigation](https://developer.android.com/develop/ui/views/touch-and-input/gestures/gesturenav), [Insets](https://developer.android.com/develop/ui/compose/system/insets)
- A 6-unit ordinary gap is hard to target precisely under a supporting thumb; this is a usability hypothesis, not a measured failure rate. Keep fast split/delete shortcuts but add a selected-item action menu for split/unsplit, remove and reorder, and reachable commit/discard actions. Preserve accessibility actions. Their exact UI needs its own prototype.
- Reserve downward travel below the queue, including deletion glow and reversal room, above mandatory navigation space. A 72-unit idle strip plus a small navigation inset is not evidence that the entire gesture fits. Cancel before an unsafe boundary; do not clip feedback into system navigation.
- Gesture ownership stays with the initial visible zone. Pinch, preview mode-swipe, focus and queue actions must not all consume one contact sequence. Accept one mutating queue gesture at a time; cancel safely on interruption. Avoid a velocity-only destructive discard trigger; tune deliberate minimum travel and clear pre-release feedback on hardware. Preserve Undo.
- Keep mode taps accessible: a large central preview is not automatically within comfortable thumb reach, so preview-swiping cannot be the only efficient mode selector.

## Code findings to carry into implementation

| Finding | Evidence | Implication |
| --- | --- | --- |
| Preview container is always 3:4 | `CaptureScreen.kt:290` | It is a compact assumption, not a general frame-layout rule |
| FIT_CENTER already exists | `CameraPreview.kt:48` | Keep its transform ownership; derive adaptive geometry rather than rotate the whole view |
| Gravity-derived target updates; no demonstrated active-display normalization | `CaptureController.kt:123`, `OrientationCodec.kt:21` | Audit basis changes/rotation lock before replacing or extending the policy; runtime fault not yet demonstrated |
| Preview requests 4:3 even when video use case is bound | `CaptureController.kt:160` | Verify preview/output framing; video16:9 illustrations are not proof of runtime WYSIWYG |
| Portrait restriction remains | `AndroidManifest.xml:43` | Current native behavior is not the adaptive SVG behavior |
| Queue travel threshold scales with half its allocated width | `QueueStrip.kt:157` | Expanding the queue across an inner display would lengthen the required gesture; use a bounded dock and test thresholds |
| Cancellation calls the release callback | `QueueStrip.kt:385`, release conditions at `:258` and `:299` | A cancelled drag can still satisfy commit/discard distance or velocity criteria; separate cancellation from completion before shipping |

The cancellation issue is confirmed by source flow, not reproduced on a running phone. Existing queue accessibility actions are useful and should be retained. No Kotlin changes were made in this research round.

## Next steps and acceptance gate

1. Obtain a small device-state evidence set before declaring any per-model orientation mapping final: model/OS, active display ID, window bounds/insets, display rotation, physical top/hinge direction, selected camera ID/facing, sensor-orientation metadata, delivered buffer/crop/rotation, and resulting photo EXIF/video dimensions. Photograph an asymmetric upright test card in each state; compare preview and saved output. A layout screenshot alone cannot supply these values.
2. Redraw the two screenshot-backed rear cases first, with external annotations for physical pose and frame direction. Include a short capture→zoom→queue interaction sequence and both handedness placements. Do not just swap the scene labels.
3. Add verified front-camera cases, near-square windows, video, rotation lock, 180° rotation, split-screen and separating-fold/tabletop cases. Keep generic opposite-ratio cases even when a particular tested camera does not produce them.
4. Validate real reach with left/right one-hand and two-hand grips, small/large hands and cases. Record regrips, missed actions, accidental split/delete/Back and ability to reach Stop. Static SVG review only validates geometry and legibility.
5. Retain the agreed recording policy: rotation continues the same recording/output; fold/unfold stops and finalizes once. Test that rotating during recording neither rebinds away the recorder nor changes the established output orientation contract.

Independent GPT-6.1-SOL review: **PASS**, Galileo (`01a19818-2811-79c3-abe5-5d9972751b26`). The reviewer inspected both inner-screen PNGs and all three supplied screenshots, critiqued grip/gesture assumptions, and reviewed the final research and plan corrections. Two documentation findings were resolved: crop synchronization is no longer conditional on `FILL_CENTER`, and prior layout acceptance is explicitly limited. Approval covers the research documentation, not hardware behavior or measured ergonomics. Local documentation links and whitespace checks pass. Existing screen SVGs are intentionally unchanged and remain historical proposals while this orientation/ergonomic correction is designed.
