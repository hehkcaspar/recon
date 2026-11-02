# Recon Android capture design contract

Static SVG reference, revised 2026-11-01. Android application code is unchanged. These designs supersede the earlier screen geometry; runtime validation belongs in the backlog and does not block evident design corrections.

## Read and view

1. [Design system](recon-capture-design-system.svg) and [component behavior contract](COMPONENT-CONTRACT.md): reusable controls, queue states, gestures, feedback and recording; the canonical sheet is 1440 × 7360.
2. [Phone](recon-capture-phone.svg), [cover](recon-capture-cover.svg), [external phone annotations](recon-capture-phone-annotated.svg).
3. [Fold8 inner](recon-capture-fold8-inner.svg): four PHOTO artboards; corrected wide-window/tall-frame and tall-window/wide-frame compositions, rear and front.
4. [Fold8 Ultra inner](recon-capture-fold8-ultra-inner.svg): the same pose study for PHOTO and VIDEO on near-square windows.
5. [Aspect-ratio designs](recon-capture-aspect-ratios.svg): 15 phone/cover/inner examples for 3:4, 9:16 photo/video, square and variable Full-style output.
6. [Layout checks](recon-capture-layout-checks.svg): opposite framing exceptions, left-hand dock with split queue, and a separating-hinge example.
7. [Coding guidance and validation backlog](camera-layout-implementation.md), [review evidence](REVIEW.md), [implementation plan](../adaptive-foldable-capture-plan.md).

Every screen is self-contained. Open its SVG directly, or use the matching PNG in `review/`. Labels, measurement explanations and gesture instructions live outside the app viewport. The clean 360 × 800 phone is embedded unchanged at 1:1 in its annotation sheet. Test-scene words, grid and asymmetric objects represent camera content, not permanent app labels.

## What changed and why

The supplied Samsung screenshots already demonstrate a tall upright rear-camera frame inside a wide inner window, and a wide upright rear-camera frame inside a tall inner window. Those directions are corrected now. The coding rule is neither “wide window means wide camera” nor its inverse: determine the upright stream direction independently, then fit it. Front and Ultra use explicit illustrative capability/pose fixtures; these are design proposals, not measured sensor metadata.

The controls follow the job and grip. Mode is always a horizontal VIDEO–PHOTO–VOICE row centered in the top bar, with all three light-text labels visible and solid white bold text for the selected mode. Compact and bottom layouts, and side shells with wide frames, put horizontal zoom directly above the shutter. Side shells with tall frames put zoom vertically on the side opposite the shutter dock; labels stay upright and values ascend bottom-to-top. Mode selection is less frequent; an idle preview swipe remains the quick path. Shutter and queue retain their anchors when mode or zoom state changes. A separate left-hand example relocates the shutter/queue dock and opposite zoom rail without mirroring text, scene, queue chronology or commit/discard semantics. Physical reach testing remains a backlog task.

Queue gestures receive real layout space. A 72-unit strip reserves 48 additional units below it, above the bottom system inset. Narrow screens show three full-size media tiles in a 312-unit queue, inset 24 units from either edge; scroll reveals the rest. Larger windows retain four tiles in a 360-unit queue. Compact screens maximize preview width where the complete frame fits; controls can layer over a bottom contrast scrim. Blank space below the queue is the gesture travel area, not an instruction panel.

## Geometry

One SVG unit is one logical dp-like unit. Device names describe comparison scenarios, not runtime branches. Read actual window size, density, insets, camera capabilities and hinge bounds in production.

| Element | Contract |
| --- | --- |
| Toolbar icon | Official Material Filled 24-unit glyph in a 48 × 48 slot |
| Shutter | 80 × 80; centered 3-unit white ring with radius 38.5 |
| Inner fills | PHOTO 64; VIDEO idle 42; recording stop 28; VOICE idle white disk 58 with black mic glyph box 32 |
| Queue | Height 72; thumbnail 56; padding 8 vertically; ordinary gap 6 |
| Queue capacity | 312-wide / three visible items on compact screens; 360-wide / four on inner screens |
| Divider | 4 wide, centered in a 24-unit expanded gap; never covers an item |
| Mode | 250 × 40 horizontal text row at (window width / 2 − 125, 28); three equal 83⅓ × 48 targets spanning y24–72; selected label white / weight 700; no underline |
| Zoom | 36-unit selected circle within a 40-unit-thick dark surface; 48 × 48 targets at 55-unit pitch; four-choice surface 220 × 40 or 40 × 220 |
| Overlay | 328-wide compact specimen, minimum height 48; wraps/grows at larger text sizes |
| Screen fixture insets | 24 top, bottom and drag-side clearance; replace with actual runtime insets |
| Queue gesture runway | 48 below the strip, in addition to the actual bottom inset |
| Spacing | 4-unit base; mode top = 28; horizontal zoom top = shutter top − 52; queue top = shutter top + 92 |

[layout-geometry.json](layout-geometry.json) records exact bounds for all 36 screen fixtures. It is generated from [layout-screens.mjs](layout-screens.mjs), which also validates frame ratios, target separation, screen bounds and downward queue travel.

| Screen | Upright PHOTO frame | Dock / queue |
| --- | --- | --- |
| Phone 360 × 800 | 360 × 480, at (0,72); horizontal zoom above shutter | Shutter y564; queue (24,656), 312 × 72 |
| Cover 360 × 576 | 360 × 480, at (0,72); controls overlay | Shutter y340; queue (24,432), 312 × 72 |
| Fold8 wide 960 × 720 | 456 × 608, at (96,86) | Right shutter y484; opposite vertical zoom; queue (576,576), 360 × 72 |
| Fold8 tall 720 × 960 | 672 × 504, at (24,118) | Bottom shutter y724; queue (180,816), 360 × 72 |
| Ultra wide 960 × 864 | 456 × 608, at (96,158) | Right shutter y628; opposite vertical zoom; queue (576,720), 360 × 72 |
| Ultra tall 864 × 960 | 762.6667 × 572, at (50.6667,84) | Bottom shutter y724; queue (252,816), 360 × 72 |

Preview rectangles use uniform scale. On compact screens, the 3:4 frame fills the usable width and controls overlay the image; this intentional visual occlusion is distinct from cropping the camera stream. Controls own input above the preview; taps/swipes beginning on a control cannot also focus or change mode. Other layouts fit the preview in the remaining region. The additional ratio sheet covers 9:16 PHOTO and VIDEO, 1:1 PHOTO, and variable Full-style crops. On the short cover, 9:16 fits to 297 × 528 with controls overlaying the frame: full-width 360 × 640 would exceed available height. VIDEO in inner matrices uses 9:16 or 16:9 in allocated regions. Controls may visually occlude part of an overlaid preview; this is not a crop or stretch of the stream. Zoom shortcuts are capability fixtures, not fixed optical-lens claims. The illustrated rear choices are 0.5, 1×, 2 and 5; the front single-preset 1× fixture assumes continuous zoom is available, with rear flash unavailable. Hide the zoom control and its input/semantic regions if no zoom adjustment exists. Production controls depend on the selected camera's capabilities, not a universal rear/front assumption. A continuous 1.7× value replaces the 1× slot without falsely selecting a preset; tapping that slot resets to 1×, and semantics describe both the current value and reset action.

The 24-unit toolbar glyph normalization, larger voice mic, consistent overlay widths, white recording rings, safe queue capacity and adaptive dock geometry are intentional prototype decisions. Preserve current Android behavior where it is sound, rather than copying accidental limitations. Shared queue handle geometry is 6 × 28 in this prototype; native idle handles currently differ.

The left-hand side fixture has a 456 × 608 preview at (408,86), queue at (24,576), and zoom targets at x888; the right-hand counterpart has zoom targets at x24. The near-square tall-frame side fixture uses a 360 × 480 preview at (96,270), with queue at (480,816). These bounds come from the generated geometry, not device measurements.

Where a compact preview extends behind the header, the header uses a 72% black scrim across y24–72; mode labels remain in that top region. The design-system VIDEO and VOICE recording compositions also keep their locked mode rows at the top.

Short-cover feedback uses host (16,92), width 328: compact Undo is 48 high and the restored 2× large-text error is 160 high, ending at y252. Horizontal zoom targets now begin at y284, leaving 32 units between banner and targets; shutter y340 and queue y432 remain anchored. This is a static fit example, not runtime font-scaling verification. For content that exceeds the available host, preserve complete text and reachable actions through an alternate accessible presentation while keeping Stop accessible.

## Behavior contract

- Preview: tap focuses, pinch zooms, horizontal swipe changes idle mode. A pinch cannot also focus or switch mode.
- Queue: left handle inward commits; right handle inward discards; colored swiper replaces that handle. Fades point toward travel. Other handle remains neutral.
- Item pull-down deletes; long-press plus horizontal drag reorders; gap down/up splits/unsplits. Queue remains horizontal when the device rotates.
- Initial visible region owns the gesture until release/cancel. Expanded split gaps must not steal thumbnail input. Ordinary narrow-gap gestures are shortcuts, not accessible tap targets.
- A deliberate release commits an armed mutation. Cancellation, reversal below threshold, interrupted input or recording lock restores the prior state. Velocity alone must not discard media.
- Selected-item actions provide Remove, Move earlier/later and Split after/Remove split without precise dragging. Preserve semantic actions for screen readers.
- During recording, disable mutating pointer handlers and accessibility actions, not just opacity. Stop stays enabled; queued media stays visible.
- Rotation continues the same recording. Fold/unfold stops and finalizes exactly once. Ordinary resize alone is not a fold.

See the component sheet for empty/overflow/split/reorder, armed/cancelled, tutorial, banner and recording examples. These are requirements for later implementation, not claims that existing Android handlers already enforce them.

## Rebuild and review

```sh
node docs/prototypes/layout-screens.mjs
node docs/prototypes/build-standalone.mjs
node docs/prototypes/layout-screens.mjs --check
node docs/prototypes/build-standalone.mjs --check
xmllint --noout docs/prototypes/*.svg
node docs/prototypes/render-review.mjs
```

Edit screen geometry in `layout-screens.mjs`, shared symbols in the design-system SVG, and external phone callouts in `build-standalone.mjs`. Do not edit generated symbol blocks or font data. The bundler embeds only used shared definitions with scoped IDs.

The renderer checks eight SVGs in HTTP, isolated local-file and HTML-image contexts, with source/PNG hashes in `review/manifest.json`. Local-file output must equal HTTP. Image-context antialiasing normally allows max channel delta 8/255 and mean pixel delta 0.05/255. One exact design-system source/direct-PNG/image-PNG hash triple has a documented [curve-edge rasterization exception](review/rasterization/README.md): 301 of 10,598,400 pixels exceed delta8; the manifest explicitly records that the strict image-context check is false. Any changed source or raster invalidates the exception. The annotation sheet must contain the exact 288,000 phone pixels. Independent visual review supplements these structural checks. Android touch arbitration, camera output, accessibility and hand reach still require runtime testing.

## Assets

Icons use [Google Material Icons](https://github.com/google/material-design-icons) Filled vectors; license in `assets/Material-Icons-LICENSE.txt`. Lens switching uses `cameraswitch` to match the Android source. Roboto comes from [Google Fonts](https://github.com/google/fonts/tree/main/ofl/roboto), with its license in `assets/Roboto-OFL.txt`. Recon-specific scene, focus, waveform and gesture vectors remain shared symbols.

## Archived references

User-provided [iPhone Duo cover/unfolded references](../references/iphone-duo-camera/README.md) and [traditional iPhone camera reference](../references/traditional-iphone-camera/README.md) are archived in `docs/references/` alongside the [Samsung Fold8 references](../references/samsung-fold8-camera/README.md). These visual references do not establish runtime geometry, optics, orientation metadata or an iOS implementation scope.
