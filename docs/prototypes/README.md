# Recon Android capture prototype contract

Status: static SVG design reference. See [review record](REVIEW.md) for acceptance evidence and limits.

## Reading order

1. [Design system](recon-capture-design-system.svg): canonical tokens, shared symbols, states and gesture diagrams.
2. [Phone](recon-capture-phone.svg) and [cover](recon-capture-cover.svg): compact capture composition.
3. [Fold8 inner](recon-capture-fold8-inner.svg): four photo states.
4. [Fold8 Ultra inner](recon-capture-fold8-ultra-inner.svg): four photo states plus four actual video states.
5. [Adaptive implementation plan](../adaptive-foldable-capture-plan.md): future runtime behavior.

The device names identify the requested comparison scenarios. Their logical artboards and safe insets are **representative design inputs**, not verified hardware specifications or Android window measurements. Runtime layout must use actual window bounds, density, insets and separating-hinge geometry.

## Dimensions and exceptions

All artboards use 1 SVG unit = 1 logical dp-like unit. Sheet titles and captions sit outside device frames. Blue safe guides and gesture annotations are reference aids, not product chrome. All inner artboards render at 1:1; scene specimens on the component sheet are uniformly 0.75× for comparison.

| Item | Contract |
| --- | --- |
| Control icon | Official Google Material Icons Filled, 24-unit glyph box centered in a 48-unit slot |
| Shutter | 80 × 80; 3-unit ring |
| Focus | 80 × 80 nominal indicator bounds |
| Queue | 360 × 72 reference strip; 56 × 56 thumbnails; 8 vertical padding; 6 ordinary gap |
| Split | 24-unit gap; 4-unit divider centered in it; group expands symmetrically; edge handles stay fixed |
| Mode / zoom | 40-unit visual pill with explicit transparent 48-unit tap bounds; zoom targets 48 × 48, centers 55 apart |
| Compact top bar | Deliberate 4-unit gaps between settings, 248-wide mode pill and library; independent 48-high mode targets prevent scaling from reducing the hit height |
| Typography | Bundled Roboto variable font; sheet title 32, panel title 22, component label 18, body 14, small note 12 |
| Colors | Canvas black; white controls; record #EF5350; delete/error #D32F2F; commit #2E7D32; discard #B26A00; voice #1F2A44 |

The existing Android source uses 26dp control icons; this prototype intentionally normalizes glyphs to the official 24dp icon grid while retaining 48dp control slots. Thumbnail media markers are decorative, smaller than control glyphs. The timer uses the darker red for legible white timer text. The original 12dp control spacing is a preferred rhythm, not a requirement to shrink controls on constrained screens.

Error banners use a separate dark error-container tone, `#5B1F24`, behind white text; `#D32F2F` remains the delete/error accent. The banner is a container treatment, not a second deletion-action color. Its Dismiss label has 16-unit right padding and an explicit 64 × 48 action target; Undo has a 72 × 48 target.

| Artboard | Preview | Control alignment | Queue / bottom inset |
| --- | --- | --- | --- |
| Phone 360 × 800 | (45,92), 270 × 360 | Row centers (98,559), (180,559), (262,559) | y706–778 / 22 |
| Cover 360 × 576 | (75,76), 210 × 280 | Row centers (103,448), (180,448), (257,448) | y492–564 / 12 |
| Fold8 landscape 960 × 720 | (38,98), 660 × 495 | Rail center x830 | y632–704 / 16 |
| Fold8 portrait 720 × 960 | (110,70), 500 × 666⅔ | Row center y832 | y876–948 / 12 |
| Ultra landscape 960 × 864 | Photo (38,98), 620 × 465; video (38,156.125), 620 × 348.75 | Rail center x800 | y776–848 / 16 |
| Ultra portrait 864 × 960 | Photo (182,70), 500 × 666⅔; video (244.5,70), 375 × 666⅔ | Row center y832 | y876–948 / 12 |

These coordinates replace the draft plan where its rail overflowed the safe region or its shutter/queue bounds overlapped. Native app insets remain dynamic; the illustrated 12-unit bottom inset must not become a hardcoded navigation-bar height.

## Gestures and state ownership

| Start zone / state | Input | Result / cancellation |
| --- | --- | --- |
| Preview, idle | Swipe right / left | Previous / next mode in VIDEO–PHOTO–VOICE order; distance or same-direction velocity commits; below threshold returns |
| Preview | Tap / two-finger pinch | Focus / zoom; pinch owns the gesture once multiple pointers participate; a drag is not also a focus tap |
| Left queue edge | Swipe right | Green commit swiper replaces the left neutral handle; fade points right; opposite handle remains |
| Right queue edge | Swipe left | Amber discard swiper replaces the right neutral handle; fade points left; opposite handle remains; undo is offered |
| Thumbnail | Pull down | One-item delete feedback; red glow and downward displacement; cancel before threshold or pointer cancellation restores the item |
| Thumbnail | Long press, then drag left or right | Reorder; snap to slot on release; cancel restores position |
| Visible inter-item gap | Pull down / up | Insert / remove divider; expanded gap is 24 units, ordinary gap 6; threshold haptic |
| Tutorial visible | Input inside scrim | Tutorial consumes input; underlying capture gestures do not run |
| Recording | Mode, queue, lens, flash and mic actions | Locked; stop remains available |

**Gesture arbitration refinement:** ownership is selected by the initial visible zone and retained until release/cancel. A gap gesture must not steal a gesture that began on a thumbnail. Narrow gap lanes are gesture initiation regions, not 48dp tap buttons; their geometry does not satisfy an accessible tap target. Preserve existing screen-reader actions for commit/discard, remove and reorder, and provide split/unsplit semantic actions in implementation. Verify pointer arbitration and accessibility on-device; static rectangles cannot prove runtime hit testing.

The prototype intentionally replaces the legacy queue's active edge-handle decoration with the colored swiper. No neutral handle is drawn beneath an active check/close control. The idle queue has no background bar. Queue and focus examples are separate.

The delete-state specimen shows a 20-unit downward drag at 50% progress: only the dragged video thumbnail moves, to y28 from y8, with alpha 0.75. A dashed outline marks its original slot as an annotation. As in `QueueThumbnail.kt`, progress is `clamp(dragY / 40, 0, 1)` and thumbnail alpha is `clamp(1 - dragY / 80, 0.3, 1)`; deletion commits on release after exceeding 40 units. The usable-screen-bottom glow follows `DeleteGlow`: height 18, Gaussian sigma 56 centered beneath the dragged thumbnail, vertical transparent-to-`#D32F2F` gradient with maximum alpha `0.7 × progress` (0.35 in this specimen). The glow stays above system navigation insets. The specimen's baseline represents that usable bottom, not an extra queue background.

The design sheet also includes composed VIDEO and VOICE recording references: the active mode stays selected but locked; timer/waveform and Stop remain visible; existing queued media stays visible but disabled. Voice hides the camera side controls. These are state assemblies, not extra device layouts. Feedback banners occupy the queue overlay slot without pushing the shutter or queue downward.

## Camera and transitions

Keep lens selection, front/rear facing, sensor-native orientation, display rotation, preview aspect and UI layout orientation separate. Wide window dimensions alone never determine camera rotation. Photo uses 4:3 in landscape / 3:4 in portrait; video uses 16:9 / 9:16. FIT_CENTER preserves complete content, including all four video frame markers. Circles remain circular, labels upright, and asymmetric landmarks expose accidental mirroring. Front preview mirroring remains an explicit future product decision; these test scenes are unmirrored.

Gold lines separate matrix artboards; they are **not physical hinges** and do not validate hinge handling. On a separating hinge, fit the complete preview, focus feedback, primary controls, queue and their gesture regions within an available region. Flat-screen matrices do not prescribe a particular device crease or demonstrate a tabletop posture.

Rotation continues the same video or voice recording. Fold/unfold stops and finalizes once before adapting. These remain implementation requirements, not behavior executed by SVG.

## Reproduce the evidence

Run `node docs/prototypes/render-review.mjs` from the repository. It uses installed Chrome (or `CHROME_BIN`), starts a temporary loopback-only server, validates IDs/references, checks PNG dimensions and font requests, and saves five PNGs plus source/asset hashes under `review/`. It requires local server and browser permissions. It does not run the Android app.

For interactive inspection, serve this directory with `python3 -m http.server 8765 --bind 127.0.0.1 --directory docs/prototypes`, then open the SVG filenames on localhost. Keep the five SVGs and `assets/` together: screen files reference shared symbols. File-preview apps that block external `<use>` resources are not the reference renderer; use the saved PNGs in those apps.

## Asset sources

Official [Google Material Icons](https://github.com/google/material-design-icons) paths come from `src/<category>/<name>/materialicons/24px.svg`: settings, collections, flash_off, flash_auto, flash_on, flip_camera_android, mic, mic_off, check, close and play_arrow. Paths are embedded once in shared symbols; Apache-2.0 license is bundled in `assets/Material-Icons-LICENSE.txt`.

[Roboto from Google Fonts](https://github.com/google/fonts/tree/main/ofl/roboto) is bundled as `assets/Roboto.ttf` with `assets/Roboto-OFL.txt`. Font and icon sources were retrieved on 2026-11-01; hashes are recorded with review renders. Focus indicators, waveform, test scenes and gesture diagrams are Recon-specific vectors.
