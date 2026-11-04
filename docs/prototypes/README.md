# Recon Android capture design contract

**设计 C 已确定为折叠屏统一静态方案 / Design C is the current foldable static direction · 2026-11-03.** A/B are archived historical studies. C fixes the deeper rules: preview remains physically stable, queue is separated from the shutter group and follows pose axis, endpoint safety margins are chosen before capacity, and visible `N` is the natural result of the measured rectangle. Runtime `WindowMetrics`, density, insets, hinge/separator bounds, gesture arbitration and physical reach acceptance remain pending; there is no fixed 400dp product switch. See the [A/B archive](../archive/foldable-layout-a-b.md) and [measurement reference](recon-capture-display-metrics.svg).

Static SVG reference, revised 2026-11-03. This contract is kept separate from the current Android implementation; native runtime validation remains incomplete. These designs supersede the earlier screen geometry and do not claim device acceptance.

## Read and view

1. [Design system](recon-capture-design-system.svg) and [component behavior contract](COMPONENT-CONTRACT.md): reusable controls, queue states, gestures, feedback and recording; the canonical sheet is 1440 × 9000.
2. [Phone](recon-capture-phone.svg), [cover](recon-capture-cover.svg), [external phone annotations](recon-capture-phone-annotated.svg).
3. [Fold8 C matrix](recon-capture-fold8-c.svg) pairs C upright and CW90 views for rear/front PHOTO and VIDEO. Follow the amber corner outside each screen.
4. [Fold8 Ultra C matrix](recon-capture-fold8-ultra-c.svg): the same C pose matrix with Ultra viewport bounds.
5. [Aspect-ratio designs](recon-capture-aspect-ratios.svg): 15 phone/cover/inner examples for 3:4, 9:16 photo/video, square and variable Full-style output.
6. [Recording and voice screens](recon-capture-recording-states.svg): complete phone, cover and inner states with stable anchors, locked controls, timer and recording error placement.
7. [Layout checks](recon-capture-layout-checks.svg): opposite framing exceptions, left-hand dock with split queue, and a separating-hinge example.
8. [Completion audit](DESIGN-AUDIT.md), [coding guidance and validation backlog](camera-layout-implementation.md), [review evidence](REVIEW.md), [implementation plan](../adaptive-foldable-capture-plan.md).

**Display measurements.** [Reference SVG](recon-capture-display-metrics.svg) and [PNG](review/recon-capture-display-metrics.png) record the community Fold8/Ultra captures and local Pixel foldable AVD cover/unfold readings. Exact pixels and logical density are distinguished from rounded dp. Pixel source logs are in [measurements](measurements/). Run `node docs/prototypes/display-metrics-reference.mjs` to regenerate this standalone reference.

**Archived B study.** The former chassis-fixed control comparison is retained in the [A/B archive](../archive/foldable-layout-a-b.md). It is not a current implementation reference and C never imports its geometry or interaction rules.

**Ultra C focused fixture.** The upright-only [C fixture](recon-capture-fold8-ultra-c-upright.svg) separates the queue from the main controls: the flash/shutter/lens group sits horizontally in the lower-right rail, while this fixture's measured lane happens to show five `standard56` thumbnails, matching its 56 × 56dp endpoint targets and 6 × 36dp visible handlers in the blank region between modality and controls. `compact48` uses 48 × 48dp endpoint targets and 6 × 28dp visible handlers. Runtime capacity remains derived from the measured lane and its endpoint margins. The complete C gesture semantics live in the [gesture guide](recon-capture-foldable-c-gesture-guide.svg), while single-hand reach remains open. The source is [ultra-c-study.py](ultra-c-study.py), and the fixture is covered by the unified review manifest.

The C continuation in the [CW90 study](recon-capture-fold8-ultra-c-cw90.svg) keeps the queue in the lower blank band on the left and the main controls on the lower-right; the visible count is a result of this fixture's measured lane, not a queue limit. Its source is [ultra-c-cw90-study.py](ultra-c-cw90-study.py). The single numeric source for C is the hand-authored [foldable-c-geometry-source.json](foldable-c-geometry-source.json); [foldable-c-geometry.json](foldable-c-geometry.json) is generated from it and must not be edited directly.

**Complete C matrix.** The [Fold8 C matrix](recon-capture-fold8-c.svg) and [Fold8 Ultra C matrix](recon-capture-fold8-ultra-c.svg) cover rear/front PHOTO and VIDEO in both upright and CW90 poses. Each matrix preserves the source preview fixture and applies the C control/queue reflow with standard interaction tokens. The [C gesture guide](recon-capture-foldable-c-gesture-guide.svg) is a coordinate-faithful Fold8 reference schematic: it shows the actual 960×720 upright and 720×960 CW90 screen bounds, preview bounds, queue bounds and main-control relationship; use the Ultra C matrix for Ultra's different numeric viewport bounds. It also documents the vertical upright queue, horizontal CW90 queue, edge commit/discard, item delete, reorder, split/remove-divider and recording-lock rules. The source generator is [foldable-c-study.py](foldable-c-study.py), driven by the shared JSON source; these are the current static design artifacts, pending runtime validation.

**C方案的意图与精髓 / C composition intent.** C 把 queue 从 shutter 主控组中拆开，但不牺牲 preview 的物理构图：preview 是 chassis-fixed 的基准，姿态改变时只改变 controls 和 queue 的排列轴。正位把 queue 竖放在 modality 与右下主控之间的空白 rail；CW90 把 queue 横放在 preview 下方的空白带，并让 queue 与 shutter 在 y 轴上做视觉中心对齐。CW90 的 queue 左外 margin、queue→主控安全间隔、主控右外 margin 追求约 1:1:1 的视觉比例；Fold8 当前样例得到 34/32/34，Ultra 当前样例得到 84/80/80，这些都是根据实际窗口和组件宽度计算出的结果，不是跨设备固定数字。两种姿态都保留同一套主控层级：zoom 是视觉弱元素，flash / shutter / lens 是捕捉焦点，queue 是靠近但独立的媒体操作区。

The study deliberately reuses the standard interaction tokens: 56dp media, 56 × 56dp edge targets, 6 × 36dp visible standard handlers, 6dp ordinary item gaps, and a 12dp zoom-to-shutter gap when the measured rectangle permits `standard56`. `compact48` uses 48dp media/targets and 6 × 28dp handlers. The visible count in these reference fixtures is a capacity outcome, not a queue limit; a different device or lane naturally yields a different count. The queue changes axis and placement with the pose, not its chronological reading order. The 208dp mode row is used only when the upright side rail is too narrow (Ultra PHOTO in this matrix), with three 69⅓dp slots; Fold8 upright, Ultra VIDEO upright and every CW90 row use the full 250dp strip with three 83⅓dp slots. Both retain 40dp visual labels and 48dp interaction rows from the shared mode symbol.

The design principle is **stable preview, separated capture, reachable queue**: keep the camera frame physically legible, keep the shutter as the visual anchor, keep zoom subordinate, and give queue gestures their own axis and real thumb space. C remains a product-design study rather than runtime acceptance; queue mutation semantics, one-hand reach and rotation/fold transitions still require implementation and device validation.

The queue is allocated by the usable region, not by a device-name preset: first choose `standard56` or `compact48` from the measured rectangle, then place each endpoint handle far enough from OS Back, safe-gesture edges, modality selectors and the main-control group. Those two margins may differ. The resulting lane length plus the fixed tile/gap tokens determines how many thumbnails are visible; long queues use the existing fade-out at both ends. The four thumbnails on the cover are therefore an example of natural capacity, not a rule that every cover must show four.

Every screen is self-contained. Open its SVG directly, or use the matching PNG in `review/`. Labels, measurement explanations and gesture instructions live outside the app viewport. The clean 360 × 800 phone is embedded unchanged at 1:1 in its annotation sheet. Test-scene words, grid and asymmetric objects represent camera content, not permanent app labels.

## What changed and why

The supplied Samsung screenshots already demonstrate a tall upright rear-camera frame inside a wide inner window, and a wide upright rear-camera frame inside a tall inner window. Those directions are corrected now. The coding rule is neither “wide window means wide camera” nor its inverse: determine the upright stream direction independently, then fit it. Front and Ultra use explicit illustrative capability/pose fixtures; these are design proposals, not measured sensor metadata.

On a flat, full-screen inner display the utility edge is 80dp in chassis coordinates. Bundles and Settings have 48dp targets set 24dp from both adjoining edges, leaving 8dp between the touch target and the preview. The visible glyph centers are 40dp from each adjacent chassis edge and 40dp from the frame. Align every selected frame to the inside of that strip. A tall 16:9-family stream occupies 405 × 720 at x80 on Fold8. C keeps the preview stable and reflows the controls and queue by pose: upright queue is vertical in the blank side rail; CW90 queue is horizontal in the blank band below the preview. The capture group has one centerline in every pose, and queue/shutter are visually aligned on the active axis. The primary black region is on the right when upright and below the frame after a clockwise turn. Use mirrored placement for the other two grips. Keep labels upright and interpret queue gestures in viewer coordinates.

Queue gestures receive real layout space. Bottom docks reserve a 24-unit gesture corridor below the strip, above the actual bottom system inset; side docks retain 48 units for balance. Runtime allocation is margin first: measure the candidate queue rectangle, choose `standard56` or `compact48` from its available primary/cross-axis space, then reserve endpoint margins for `safeGestures` / OS Back and neighboring controls. Only the remaining lane is passed to the queue renderer; its visible count is `N = floor((L − 2E + G) / (T + G))`, with leftover endpoint media space distributed after the fixed handles. A 360-unit cover with 24-unit margins is merely an example of this calculation; a different device naturally produces a different count. Scroll and the existing fade-out reveal the rest. Compact screens maximize preview width where the complete frame fits; controls can layer over a bottom contrast scrim. Blank space below the queue supports gesture travel.

## Geometry

One SVG unit is one logical dp-like unit. Device names describe comparison scenarios, not runtime branches. Read actual window size, density, insets, camera capabilities and hinge bounds in production.

| Element | Contract |
| --- | --- |
| Toolbar icon | Official Material Filled 24-unit glyph in a 48 × 48 slot |
| Tap focus | Complete white ring, radius 30 / stroke 2; transparent center; no lock or exposure controls |
| Shutter | `standard56` 80 / `compact48` 64; centered3dp white ring; radius 38.5 /30.5 |
| Inner fills | PHOTO 64; VIDEO idle 42; recording stop 28; VOICE idle white disk 58 with black mic glyph box 32 |
| Queue | `standard56`: height72 / tile56 / visible handler6×36; `compact48`: height64 / tile48 / visible handler6×28; vertical padding8; ordinary gap6 |
| Queue capacity | Compute after endpoint safety margins: `L=A−Mₛ−Mₑ`, then `N=floor((L−2E+G)/(T+G))`; visible count is never a product parameter. Overflow adds fading partial tiles. |
| Divider | 4 wide, centered in a 24-unit expanded gap; never covers an item |
| Mode | Horizontal row, top of the controls. Labels stay 12sp and bold when selected. Narrow side rails reduce the space between labels. |
| Zoom | 28-unit selected circle; 12sp labels; visible surface 176 × 32 or 32 × 176; independent 48 × 48 targets at 48-unit pitch |
| Overlay | 328-wide compact specimen, minimum height 48; wraps/grows at larger text sizes |
| Screen fixture insets | 24 top, bottom and drag-side clearance; replace with actual runtime insets |
| Queue gesture runway | Bottom docks24 below strip + actual bottom inset; balanced side docks retain48 |
| Spacing | 4-unit base; mode top = 28; horizontal zoom slot top = shutter top − 52 (surface inset 4); queue top = shutter top +92 `standard56` / +72 `compact48` |

[Generated bounds](layout-geometry.json) cover 59 fixtures. [Physical rotation geometry](inner-pose.mjs) defines the inner-screen frames and the three control rows. The C numeric source is [foldable-c-geometry-source.json](foldable-c-geometry-source.json); [foldable-c-geometry.json](foldable-c-geometry.json) is generated reference output with provenance and cross-artifact checks. Edit the source JSON and generators, never the generated JSON.

The following table is a **generic responsive-reference geometry table**, not the current Fold8/Fold8 Ultra C matrix. The C matrix uses the source JSON above and the measured rectangle-fit contract; generic rows may intentionally exercise compact fallbacks or alternate window fixtures.

| Generic screen fixture | Upright PHOTO frame | Generic dock / queue reference |
| --- | --- | --- |
| Phone 360 × 800 | 360 × 480, at (0,72); horizontal zoom above shutter | Shutter y588; queue (24,680), 312 ×72 |
| Cover 360 × 576 | 360 × 480, at (0,72); controls overlay | `compact48` shutter 64 at y392; queue (24,464),312 ×64, four visible items in this fit outcome |
| Fold8 upright | 540 × 720, 80 from the utility edge | Full-height PHOTO, side controls |
| Fold8 clockwise | 720 × 540, 80 from the utility edge | Bottom capture group |
| Ultra upright | 648 × 864, 80 from the utility edge | Full-height PHOTO, side controls |
| Ultra clockwise reference | 864 × 648, 80 from the utility edge | `compact48` bottom capture group only when the measured queue rectangle requires it; this is not the current Ultra C matrix token |

Preview rectangles use uniform scale. On compact screens, the 3:4 frame fills the usable width and controls overlay the image; this intentional visual occlusion is distinct from cropping the camera stream. Controls own input above the preview; taps/swipes beginning on a control cannot also focus or change mode. Other layouts fit the preview in the remaining region. The additional ratio sheet covers 9:16 PHOTO and VIDEO, 1:1 PHOTO, and variable Full-style crops. On the short cover, 9:16 fits to 297 × 528 with controls overlaying the frame: full-width 360 × 640 would exceed available height. VIDEO in inner matrices uses 9:16 or 16:9 in allocated regions. Controls may visually occlude part of an overlaid preview; this is not a crop or stretch of the stream. Zoom shortcuts are capability fixtures, not fixed optical-lens claims. The illustrated rear choices are .5, 1×, 2 and 5; the front single-preset 1× fixture assumes continuous zoom is available, with rear flash unavailable. Hide the zoom control and its input/semantic regions if no zoom adjustment exists. Production controls depend on the selected camera's capabilities, not a universal rear/front assumption. A continuous 1.7× value replaces the 1× slot without falsely selecting a preset; tapping that slot resets to 1×, and semantics describe both the current value and reset action.

The 24-unit toolbar glyph normalization, larger voice mic, consistent overlay widths, white recording rings, capacity-driven queue count and adaptive dock geometry are intentional prototype decisions. Preserve current Android behavior where it is sound, rather than copying accidental limitations. `standard56` handlers are 6 × 36 inside 56 × 56dp endpoint targets; `compact48` handlers are 6 × 28 inside 48 × 48dp endpoint targets. Native idle handlers currently differ.

The layout-check, aspect-ratio and recording-state sheets are generic responsive references. They exercise free-window fallback, alternate stream directions and separating panes; they do not replace the Fold8/Fold8 Ultra C matrices.

Where a compact preview extends behind the header, the header uses a 72% black scrim across y24–72; mode labels remain in that top region. The design-system VIDEO and VOICE recording compositions also keep their locked mode rows at the top.

Short-cover feedback uses host (16,92), width 328: compact Undo is 48 high and the restored 2× large-text error is 160 high, ending at y252. `compact48` horizontal zoom targets begin at y336, leaving 84 units between banner and targets; shutter y392 and queue y464 remain anchored. This is a static fit example, not runtime font-scaling verification. For content that exceeds the available host, preserve complete text and reachable actions through an alternate accessible presentation while keeping Stop accessible.

## Behavior contract

- Preview: tap focuses, pinch zooms, horizontal swipe changes idle mode. A pinch cannot also focus or switch mode.
- Queue: left handle inward commits; right handle inward discards; colored swiper replaces that handle. Fades point toward travel. Other handle remains neutral.
- Item pull-down deletes; long-press plus queue-axis drag reorders; the perpendicular gap gesture splits/unsplits. In C, the queue is vertical in upright and horizontal in CW90, following the pose rather than preserving one screen axis.
- Fixed handles win initial hit testing over any overflowing media underneath. Outside those targets, the initial item/gap region owns the gesture until release/cancel. Expanded split gaps must not steal thumbnail input. Ordinary narrow-gap gestures are shortcuts, not accessible tap targets.
- A deliberate release commits an armed mutation. Cancellation, reversal below threshold, interrupted input or recording lock restores the prior state. Velocity alone must not discard media.
- Selected-item actions provide Remove, Move earlier/later and Split after/Remove split without precise dragging. Preserve semantic actions for screen readers.
- During recording, disable mutating pointer handlers and accessibility actions, not just opacity. Stop stays enabled; queued media stays visible.
- Rotation continues the same recording. Fold/unfold stops and finalizes exactly once. Ordinary resize alone is not a fold.

See the component sheet for empty/overflow/split/reorder, armed/cancelled, tutorial, banner and recording examples. These are requirements for later implementation, not claims that existing Android handlers already enforce them.

## Rebuild and review

```sh
# Shared C source is read by both JS and Python generators.
node docs/prototypes/layout-screens.mjs
node docs/prototypes/build-standalone.mjs
python3 docs/prototypes/foldable-c-study.py
python3 docs/prototypes/ultra-c-study.py
python3 docs/prototypes/ultra-c-cw90-study.py
node docs/prototypes/foldable-c-geometry.mjs
node docs/prototypes/display-metrics-reference.mjs
node docs/prototypes/layout-screens.mjs --check
node docs/prototypes/build-standalone.mjs --check
node docs/prototypes/foldable-c-geometry.mjs --check
python3 docs/prototypes/foldable-c-study.py --check
python3 docs/prototypes/ultra-c-study.py --check
python3 docs/prototypes/ultra-c-cw90-study.py --check
python3 docs/prototypes/ultra-dock-study.py
python3 docs/prototypes/ultra-dock-study.py --check
xmllint --noout docs/prototypes/*.svg
node docs/prototypes/render-review.mjs
```

Edit screen geometry in `layout-screens.mjs`, shared symbols in the design-system SVG, and external phone callouts in `build-standalone.mjs`. Do not edit generated symbol blocks or font data. The bundler embeds only used shared definitions with scoped IDs.

The renderer checks all 16 tracked SVG sheets in HTTP, isolated local-file and HTML-image contexts, including the Fold8/Ultra C matrices, focused Ultra C states and C gesture guide; source/PNG hashes and C source/generator provenance are recorded in `review/manifest.json`. The manifest separates 9 `current`, 4 `generic-reference` and 3 `archived` sheets. The archived A/B *layout* evidence is not a current product design input; the two archived inner SVGs remain explicit preview/defs fixtures used to build the C artifacts and are named in the C source graph. Local-file output must equal HTTP. Image-context antialiasing normally allows max channel delta 8/255 and mean pixel delta 0.05/255. One exact design-system source/direct-PNG/image-PNG hash triple has a documented [curve-edge rasterization exception](review/rasterization/README.md); any changed source or raster invalidates the exception and requires refreshed evidence. The annotation sheet must contain the exact 288,000 phone pixels. Independent visual review supplements these structural checks. Android touch arbitration, camera output, accessibility and hand reach still require runtime testing.

## Assets

Icons use [Google Material Icons](https://github.com/google/material-design-icons) Filled vectors; license in `assets/Material-Icons-LICENSE.txt`. Lens switching uses `cameraswitch` to match the Android source. Roboto comes from [Google Fonts](https://github.com/google/fonts/tree/main/ofl/roboto), with its license in `assets/Roboto-OFL.txt`. Recon-specific scene, focus, waveform and gesture vectors remain shared symbols.

## Archived references

User-provided [iPhone Duo cover/unfolded references](../references/iphone-duo-camera/README.md) and [traditional iPhone camera reference](../references/traditional-iphone-camera/README.md) are archived in `docs/references/` alongside the [Samsung Fold8 references](../references/samsung-fold8-camera/README.md). These visual references do not establish runtime geometry, optics, orientation metadata or an iOS implementation scope.

### Long-queue edge behavior

The component sheet now includes 312dp and 360dp overflow states: start, middle, end, split, commit and discard. Media fades to transparency underneath fixed handles; there is no queue background bar. `standard56` handles retain 56 × 56dp input regions; `compact48` handles retain 48 × 48dp input regions. Both win initial hit testing over underlying thumbnails/gaps. An existing item gesture never turns into an edge action. Endpoint padding keeps the first/last item fully reachable. Four full-size cover fixtures in `recon-capture-layout-checks.svg` show these states over the camera preview. See `COMPONENT-CONTRACT.md` for exact mask, spacing and ownership geometry. Runtime pointer/accessibility arbitration remains implementation work.

### Bottom anchoring and short-window density

Bottom docks use `queueTop = fullWindowHeight − safeBottomInset −24 − stripHeight`. The24dp corridor supports the revised prototype deletion gate (>24dp plus release, display clamped 32dp), not an arbitrary spacer. Side docks keep their balanced anchors. The capacity-driven short-window profile uses 64dp shutters and 48dp tiles in a64dp queue, with 8dp shutter-to-queue spacing; `standard56` remains 80/56/72 with 12dp spacing. Targets stay≥48dp, duration labels11sp and mode/zoom12sp. Use the `<600dp` height rule only as a fallback when the queue rectangle is not measured; freeze the profile during gestures. See the component contract for exact ownership, cancellation, glow bounds and endpoint calculations.

Four new layout checks cover360 ×480,360 ×640, active cover deletion, and an illustrative48dp three-button navigation inset. These complement the existing overflow and large-text fixtures. Read actual runtime insets; never sum overlapping inset types or subtract an inset twice. Android documents [inset handling](https://developer.android.com/develop/ui/compose/system/insets) and [minimum48dp touch targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults); the density breakpoint and gesture distances are Recon proposals, not platform standards. Native behavior is unchanged; accidental deletion, edge reach and profile transitions require held-device tests.

### Whole-design audit refinements

On the inner display a single available zoom sits on the shutter axis. Other windows preserve the common 1× target across lens changes. The square phone frame centers vertically above the zoom controls.

The recording-state sheet contains eleven complete windows: VIDEO recording, VOICE idle and VOICE recording on phone/cover/wide inner, plus short-cover VIDEO with a large error and a rotated inner VIDEO state. VOICE removes the camera image and camera controls, retaining the shutter and queue positions. Recording locks Settings/Library as well as mode, side controls and queue; zoom and Stop remain available. The timer is centered on the fitted camera frame, not blindly on the window. Large recording feedback starts below its timer and leaves Stop clear. These are static design requirements, not native behavior claims.
