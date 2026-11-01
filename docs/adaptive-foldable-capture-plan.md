# Adaptive Foldable Capture Plan

Status: planning only. No implementation changes are included in this document.

2026-11-01 correction: read the [camera orientation and grip review](foldable-camera-orientation-ux-review.md). Wide/tall window shape does not determine upright camera frame shape. The inner SVGs now correct the opposite-axis combinations shown in the supplied native-camera screenshots. Use the revised [prototype contract](prototypes/README.md) and [coding guidance/backlog](prototypes/camera-layout-implementation.md); remaining device validation does not block these design corrections.

## Goal

Preserve Recon's current compact phone capture experience while supporting larger, unfolded, and hinge-separated windows with a small, maintainable layout design.

The implementation should respond to the app window and available usable regions, not to Samsung model names, a future iPhone Duo model, or other device-specific checks.

## Product decisions

### Recording transition policy

- Rotation: continue the same video or voice recording seamlessly.
- Fold or unfold: stop and finalize the active recording once, then adapt the UI.
- Ordinary window resize: continue recording unless the transition is classified as a fold/unfold boundary.
- A continuous video remains one output file. Its video orientation metadata is based on the orientation at recording start.

### Layout scope

Use two capture layout shells:

1. **Compact layout** — preserve the existing portrait-phone composition and spacing.
2. **Wide layout** — use a bounded camera preview with controls and the queue placed beside or below it, depending on the available usable region.

For a separating hinge, never span the preview, shutter, focus indicator, queue, or primary gesture target across the hinge.

## Design principles

- Use current window size and hinge geometry, not device-type detection.
- Keep adaptive state in the UI/window layer; do not pass it into storage or bundle-processing code.
- Keep the existing CameraX pipeline stable during ordinary rotation and resizing.
- Add only the smallest abstractions needed to share existing controls between the compact and wide shells.
- Treat rotation as a presentation change and folding between display regions as a capture-session boundary.
- Preserve the current `FIT_CENTER` preview behavior until crop, focus, and capture-coordinate behavior are verified.

## Planned adaptive window model

Add a small UI-layer model containing:

- current window width and height;
- width/height size class;
- current display rotation;
- separating fold or hinge bounds, when present;
- safe drawing and gesture insets;
- a transition classification: `Rotation`, `Fold`, `Unfold`, or `Resize`.

The transition classifier should be a pure, unit-tested function. It should not grow into a large posture hierarchy.

## Recording and lifecycle behavior

### Rotation

- Ordinary orientation changes must not destroy the active recorder.
- Keep `CaptureController` and recording state alive across rotation.
- Update preview transforms for the active display. Select capture target rotation according to the supported physical-orientation/rotation-lock policy, normalized to the active display's reference basis; window width/height must never determine it. Audit 180-degree changes and display switches.
- Resize or reconnect the preview without stopping the recorder.
- Do not invoke the current blanket `ON_PAUSE` stop path for a rotation.

Use the smallest reliable mechanism for preserving the activity/controller across orientation changes. Do not introduce a foreground service or process-wide recording architecture unless real-device testing proves it is necessary.

### Fold or unfold

Observe WindowManager layout changes and classify fold/unfold separately from rotation.

When a fold or unfold is detected:

1. Acquire a single transition guard so repeated callbacks cannot finalize twice.
2. Stop video or voice recording.
3. Await finalization and add the completed item to the current queue.
4. Allow the new compact or wide layout to settle.
5. Rebind only if the camera surface or lens configuration actually requires it.

The stop/finalize operation must be idempotent and must preserve capture order and staged-file recovery behavior.

## Camera plan

- Remove the portrait-only activity restriction.
- Separate display-following preview from capture orientation under rotation lock; use CameraX's documented listener/target-rotation policies and verify active-display coordinate bases. Do not infer rotation from window aspect.
- Keep the existing 4:3 capture configuration initially.
- Do not rebind for ordinary rotation or resize.
- Rebind only for actual camera configuration changes, such as lens or camera mode.
- Keep `PreviewView` as the preview surface.
- Verify preview and selected photo/video output framing under `FIT_CENTER`. Use CameraX `ViewPort`/`UseCaseGroup` when synchronized crop regions are needed, including demonstrated mismatches with `FIT_CENTER`; this decision is not conditional on switching to `FILL_CENTER`. Derive the viewport from the intended capture framing and correct rotation, not an arbitrary window-filling rectangle.

## UI plan

Reuse the existing components:

- `CameraPreview`
- modality selector
- shutter buttons
- zoom control
- recording indicators
- queue strip

Extract shared controls once, then build the compact overlay and side/bottom arrangements from the revised SVG contract. On short compact windows, maximize the fitted preview and layer controls over a contrast scrim rather than reducing the camera to a thumbnail. Keep the same control sizes and queue gesture runway across shells.

The wide shell should fit the independently oriented camera frame, then place controls in usable regions. A wide window may contain a tall preview, and a tall window a wide preview. Compare a compact side control group and bottom control group using available space and grip reach, rather than width greater than height alone. Keep zoom, mode access and shutter close; evaluate a left/right dock preference without reversing queue chronology or gesture meanings.

Design the horizontal queue's placement, complete gesture travel and safe cancellation alongside the capture group, before accepting layout geometry. Queue gestures are core product behavior, not a later decoration. Keep this one shared queue component; do not rotate its interaction axes with the camera image.

## Research facts: screen geometry and camera orientation

Verified 2026-11-01. These are reference facts, not device-specific layout rules.

### App-usable window sizes

Use the actual app window in dp, including split-screen and hinge-separated regions. Do not use physical screen inches or model names for layout decisions.

| Width class | Range | Typical examples |
| --- | --- | --- |
| Compact | `<600dp` | Traditional phone portrait; foldable cover display |
| Medium | `600–839dp` | Many unfolded foldables in portrait |
| Expanded | `>=840dp` | Many unfolded foldables in landscape |
| Large | `>=1200dp` | Large tablets and desktop-style windows |

Height matters too: an open flippable in landscape can have medium width but compact height, so it may not have enough height for a two-pane layout.

Representative current display geometries:

- Traditional phones are commonly 6.2–6.9 inches with approximately 19.5:9–21:9 panels. Current examples include Pixel 10 at 6.3 inches/`1080 x 2424`, Galaxy S25 at 6.2 inches/`1080 x 2340`, and iPhone 17 at 6.3 inches/`1206 x 2622`.
- Galaxy Z Fold8 cover: 5.5 inches, `1248 x 1972`, approximately 10:16; inner: 7.6 inches, `1848 x 2448`, approximately 4:3 when open. It has dual 50MP rear cameras and 10MP cover/main cameras.
- Galaxy Z Fold8 Ultra cover: 6.5 inches, `1080 x 2520`, approximately 21:9; inner: 8.0 inches, `2504 x 2256`, near-square at approximately 1.11:1. It has 200MP wide, 50MP ultrawide, 10MP telephoto, and 10MP cover/main cameras.
- iPhone Duo outer display: 5.4 inches, `1398 x 2034`; inner display: 7.6 inches, `1878 x 2670`. Apple describes the outer display as compact-width and the inner display as regular-width/regular-height.

Rounded corners, camera cutouts, system bars, safe areas, and hinges reduce the usable content area. The raw display resolution must not become a layout constant.

### Camera aspect and direction

- Camera sensors commonly produce landscape-oriented buffers, with 4:3 the most common sensor aspect ratio.
- Typical Android metadata is rear `SENSOR_ORIENTATION = 90` and front `SENSOR_ORIENTATION = 270`, but the app must query the actual camera characteristics.
- Photo capture should default to 4:3; portrait presentation is 3:4 after rotation.
- Video commonly uses 16:9; portrait presentation is 9:16.
- CameraX commonly supports 4:3 and 16:9 and defaults image capture/image analysis to 4:3.
- Samsung camera apps commonly expose 3:4, 9:16, 1:1, and full-screen choices. These are framing/output choices, not necessarily the sensor-native shape.
- A 4:3 or 16:9 frame cannot fill every foldable panel without either cropping or letterboxing. `FIT_CENTER` preserves the whole frame; `FILL_CENTER` crops.
- Camera direction has two meanings: image rotation and which physical camera faces the user. On foldables, “front” does not always mean “facing the active display.” Keep lens selection, sensor orientation, display rotation, and user-facing direction conceptually separate.

### Fold8-specific camera layout consequences

- The standard Fold8's numerical 4:3 panel ratio does not imply matching camera axes. The supplied native screenshots show a tall 3:4 rear preview in the wide inner window and a wide 4:3 rear preview in the tall inner window. Front-camera and Ultra mappings require separate evidence.
- The Fold8 Ultra inner display is near-square, so a 4:3 photo preview or 16:9 video preview should be bounded with preserved aspect ratio; use the remaining region for controls or accept letterboxing.
- Both cover displays should use the compact phone shell.
- Do not rotate the preview merely because an unfolded window becomes wide. Use window bounds for Compose layout and CameraX sensor metadata plus actual display rotation for the camera transform.

### Recording implication

CameraX `VideoCapture.setTargetRotation()` does not change an already-running `Recorder` output; it affects recordings started afterward. Therefore:

- rotation may continue in the same recording;
- the live UI and preview may adapt to the new window;
- the output orientation is based on the recording's start configuration;
- fold/unfold remains an appropriate stop/finalize boundary before starting a new configuration.

## Implementation sequence

1. Add and unit-test the rotation/fold/unfold transition classifier.
2. Separate camera-binding readiness from capture `BusyState` so binding changes cannot make an active recording appear idle.
3. Remove portrait-only manifest and documentation assumptions.
4. Verify active-display, physical-pose and camera-transform coordinate bases for preview and EXIF/video setup; derive frame direction independently of window shape.
5. Extract shared controls and implement the compact preview/overlay geometry from the revised prototypes.
6. Add the shared side/bottom dock arrangements, fitting the actual upright stream ratio independently of window shape.
7. Add hinge-boundary and safe-inset placement.
8. Validate queue reach/travel, system-gesture boundaries and cancel-without-action behavior; update external tutorial annotations for both shells.
9. Add Compose/layout and transition tests.
10. Validate on real foldable hardware.

## Verification matrix

Test at minimum:

- compact phone portrait;
- compact phone landscape;
- folded cover display;
- unfolded portrait;
- unfolded landscape;
- flat inner display;
- separating hinge with vertical and horizontal bounds;
- split-screen and resizable windows;
- display cutouts and side navigation bars;
- rotation during video recording;
- rotation during voice recording;
- fold/unfold during video recording;
- fold/unfold during voice recording;
- preview focus, zoom, photo EXIF, video orientation, and queue order.

The current repository has pure JVM tests but no Android instrumented camera/layout tests. Gradle verification also requires a configured Android SDK in the development environment.

## Explicitly deferred

- OEM-specific Samsung APIs.
- Apple/iOS-specific implementation, which is outside this Android app's scope.
- Rear-display and dual-screen presentation modes.
- Dynamic camera resolution switching for every window size.
- A five-state posture architecture.
- Camera rebinding on every fold or resize.
- Foreground-service recording.
- Full `CaptureViewModel` or storage-pipeline rewrites.

## Official references

- [Android window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)
- [Android orientation, aspect ratio, and resizability](https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability)
- [Camera preview on multiple form factors](https://developer.android.com/media/camera/camera2/camera-preview)
- [Camera support for foldable devices](https://developer.android.com/agents/skills/camera/camerax/references/foldables)
- [Jetpack `FoldingFeature`](https://developer.android.com/reference/androidx/window/layout/FoldingFeature)
- [Compose window insets](https://developer.android.com/develop/ui/compose/system/insets)
- [CameraX configuration and aspect ratios](https://developer.android.com/media/camera/camerax/configuration)
- [CameraX `VideoCapture` rotation](https://developer.android.com/reference/androidx/camera/video/VideoCapture#setTargetRotation(int))
- [Google Pixel 10 specifications](https://store.google.com/product/pixel_10_specs)
- [Samsung Galaxy S25 specifications](https://www.samsung.com/us/smartphones/galaxy-s/s25-navy-256gb-sm-s931udbexaa/)
- [Apple iPhone 17 specifications](https://www.apple.com/iphone-17/specs/)
- [Samsung Galaxy Z Fold8/Fold8 Ultra announcement](https://news.samsung.com/us/samsung-galaxy-z-fold8-ultra-fold8-flip8-foldables-perfected-every-way-of-living/)
- [Apple iPhone Duo technical specifications](https://www.apple.com/iphone-duo/specs/)
- [Apple iPhone Duo camera guidance](https://developer.apple.com/videos/play/tech-talks/111465/)
