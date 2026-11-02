# SVG correction and review record

Scope: eight Android SVG artifacts (design system, phone, cover, two inner matrices, phone annotations, layout checks and aspect-ratio studies), supporting assets and reference documentation. No Kotlin, manifest, iOS or camera implementation changes.

**Historical acceptance:** the earlier five batch gates covered the files at those commits, not the current revised design. Current batch evidence appears below when verified.

**Current design correction:** inner matrices now follow the opposite window/frame axes visible in the supplied Fold8 rear-camera screenshots. This correction needs no additional hardware proof. The whole-design revision also updates queue travel, grip grouping, shared states and developer guidance. Runtime transform and reach tests are recorded in the [backlog](camera-layout-implementation.md); they do not block the corrected static design.

## Portability follow-up

The initial review covered HTTP rendering only. A user subsequently opened the Fold8 SVG in a preview that did not load external shared-symbol references: frames and captions appeared, while camera scenes and controls were blank. This was a delivery defect, despite the correct HTTP renders.

All five SVGs now embed their fonts and required shared definitions. `build-standalone.mjs` regenerates local symbol dependencies from the canonical design system without changing the screen layouts. Each exported file can be viewed without sibling files or a server.

Verification: all five HTTP PNGs are byte-identical to the previously accepted renders; all five isolated local-file renders match HTTP exactly. Phone, cover, Fold8 and Ultra also match exactly in HTML image context. The design-system image-context render differs only by compositor rounding (maximum channel delta 4/255, mean pixel delta approximately 0.003243/255). All 296 local references resolve, XML parses, and the bundler's non-mutating freshness check passes.

Independent GPT-6.1-SOL review: **PASS**, Boole (`01a19801-966b-7ae3-8294-1e66f0d4fc82`). The reviewer visually inspected all five sheets, isolated phone/Fold8/Ultra renders and image-context outputs, independently reran the corrected isolation harness, and confirmed no external dependencies, CSS conflicts or remaining actionable findings. This follow-up is verified before commit.

## Phone annotation separation follow-up

The prior phone mock placed a gesture explanation panel inside the app viewport. Although labeled as an annotation, it consumed screen area and separated the shutter from the queue. That made the composition unsuitable as a clean geometry reference.

Removed the panel and blue safe guides from the clean 360 × 800 phone. Reclaimed the space for a 336 × 448 complete 3:4 preview and a bottom control group with 12-unit visual gaps between zoom, shutter and queue. Side controls retain 48-unit targets and symmetric source-based spacing. The clean phone remains a proposed adaptive layout, not a measured Android runtime screenshot.

Added an external annotation companion generated from the exact clean phone at 1:1. Explanatory text and leaders stay outside its viewport; camera test-scene markings remain inside the sample camera content. The renderer now verifies the annotated phone crop matches all 288,000 clean-phone pixels exactly, as well as checking all six files in HTTP, isolated-file and HTML image contexts. The four unaffected primary SVGs and PNGs match their previous committed hashes.

Independent GPT-6.1-SOL review: **PASS**, Euler (`01a1980c-13d3-7590-959a-99d04fa8d530`), explicitly selected in the spawn request. The reviewer reopened both final PNGs after the side-control spacing correction, checked 48-unit slots and exact 56-unit horizontal gaps, confirmed no clipping or annotations inside the phone, and independently compared all 288,000 phone pixels. Final phone/companion SVG and PNG hashes match `review/manifest.json`; no actionable findings remain. XML, bundler freshness, JavaScript syntax and whitespace checks also pass. This follow-up is verified before commit.

## Voice shutter and feedback width follow-up

The user identified a weak microphone in the shutter family and unexplained banner widths. The voice SVG had reused a 24-unit toolbar glyph and omitted Android's 58-unit white center. Restored that center and enlarged the official microphone glyph box to 32 units for the primary action; the 80-unit button, toolbar glyphs and queue markers keep their sizes. The larger shutter glyph is a documented proposal beyond Android's current 24dp glyph.

Standardized Undo, Saved and Error specimens to 328 × 48, representing a 360-unit compact window with 16-unit side insets. The prior 320/260/300 widths were not a coherent layout rule. Android shares `ActionBanner` for Undo/Error but uses a content-sized Saved shimmer; equalizing Saved is documented as a proposed consistency change. Action labels now share the same 16-unit right inset while retaining 72/64 × 48 targets.

Initial independent review found one additional imbalance: the draft photo center was 42 units rather than Android's native 64. Corrected it to 64 while retaining voice58 and video42; all outer button bounds and screen placements are unchanged.

Independent GPT-6.1-SOL review: **PASS**, Erdos (`01a19811-a538-7a93-bf5c-d858ee3f460f`). The reviewer inspected all six final PNGs and confirmed the corrected shutter family is balanced, microphone sizing and banner alignment are sound, and no actionable findings remain. All six SVGs pass HTTP/isolated-file/image rendering, 325 references resolve, and all 288,000 phone/annotation pixels match. Screen layout markup outside generated shared definitions is unchanged; XML, bundler freshness and whitespace checks pass. This follow-up is verified before commit.

## Centered long-press diagram follow-up

Moved the long-press hold marker to the midpoint between symmetric left/right arrows, matching the preview-swipe diagram. Preserved the dashed hold halo and left equal clearance between it and each arrow shaft. Gesture meaning and screen layouts are unchanged. All six files pass HTTP/isolated-file/image rendering, XML and freshness checks; 325 references resolve and all five screen/annotation PNGs match the preceding commit. Independent GPT-6.1-SOL review: **PASS**, Tesla (`01a19815-55bd-7032-8c9c-345cfb7935f4`), after inspecting the PNG and detail crop for symmetry, spacing, gesture meaning and clipping. No actionable findings remain; verified before commit.

## Queue edge-swipe diagram follow-up

Replaced generic circular gesture origins with the actual rounded 6 × 28 neutral handle, shared by the queue and diagram through one symbol. Inward arrows are mirrored with symmetric placement; colored active swipers continue to replace the handle in the separate state specimens. All six SVGs pass HTTP/isolated-file/image rendering, XML and freshness checks; 338 references resolve, and all five screen/annotation PNGs match the preceding commit. Independent GPT-6.1-SOL review: **PASS**, Laplace (`01a19816-e665-7ec0-a670-fa6d122c74b4`), after inspecting the full PNG and exact card crop for handle consistency, inward direction, symmetry, label legibility and clipping. No actionable findings; verified before commit.

## Initial batch gates

Commit workflow: complete a batch, validate it, obtain independent review, resolve findings, then commit before starting the next batch. For this initial correction run, commits were recorded afterward from the final reviewed files and grouped by batch scope; they do not reconstruct the intermediate review snapshots. Shared components therefore include the final integration corrections in the design-system commit.

| Batch | Independent GPT-6.1-SOL review | Disposition |
| --- | --- | --- |
| 1 — Design system | Lovelace, `01a197e2-6285-7f30-af99-323be7216835` | Initial findings fixed; rendered re-review PASS |
| 2 — Phone / cover | Pascal, `01a197e3-3fe5-7c53-b4db-991992d3c679` | Rendered review PASS |
| 3 — Fold8 inner | Popper, `01a197e4-02a5-7ce2-a5a6-96a4831518fc` | Shared target/queue findings fixed; rendered re-review PASS |
| 4 — Ultra inner | Raman, `01a197e4-77b0-7961-9ce2-8080985cbedf` | Eight photo/video artboards re-rendered with bundled Roboto; PASS |
| 5 — Integration | Meitner, `01a197ee-4c51-7a33-874c-60af778af615` | Final rendered re-review PASS; no remaining actionable findings |

## Corrections included

- Fixed split divider centering, stable handle clearance and symmetric thumbnail groups. Split gap now matches the existing 24dp expansion.
- Preserved active colored swipers as replacements for edge handles; directional fades and no underlying neutral bar.
- Repaired typography hierarchy, overflowing text, undersized examples, recording-lock pill and tutorial spacing.
- Replaced hand approximations with official native Material icon paths; kept nominal 24dp glyph / 48dp slot sizing.
- Added explicit mode/zoom/Next tap-bound geometry and documented narrow gesture-lane ownership and accessibility follow-up.
- Aligned compact rows and wide rails, removed cover annotation collisions, and kept queues inside illustrative safe areas.
- Corrected front scene labels, equalized test-scene specimen scales and added bidirectional gesture arrows.
- Replaced Ultra crop-like comparison rectangles with full photo and video matrices, including all four frame markers.
- Bundled Roboto for reproducible typography. Final integration review checked its effect on all five files.
- Corrected the locked mode from PHOTO to VIDEO, added composed VIDEO/VOICE recording control references, and made the phone's mode-swipe diagram bidirectional during the completion audit.
- Final review found moving mode labels, missing delete feedback and an undocumented error-container color. Unified mode label/capsule centers, added a source-grounded 50% deletion specimen and documented the dark error-container exception. All three corrections passed independent re-review.

## Technical evidence

- All six SVGs parse with `xmllint --noout docs/prototypes/*.svg`.
- The reproducible renderer validates unique IDs and all local references, checks all six PNG dimensions and embedded font bytes, and compares HTTP, isolated-file and image-context rendering. It also compares the clean phone with its annotated companion's phone pixels. The current reference count is recorded in `review/manifest.json`.
- Eighteen displayed scene instances preserve their source aspect ratio, including exact 4:3 / 3:4 and 16:9 / 9:16 frames (portrait fractional values rounded to six decimals).
- All inner-screen queue bottoms remain at least 12 logical units inside the frame. Phone and cover preserve their respective 22/12-unit illustrative bottom insets.
- Current source and PNG hashes match `review/manifest.json`. Do not reuse a visual approval after source changes without rerendering affected artifacts.
- Initial five-batch evidence was generated at 2026-11-01 16:34:24 UTC. That final reviewer independently validated 224 references and all SVG/PNG/asset hashes; inspected full sheets and native-size details including all four Fold8 and all eight Ultra artboards. The portability follow-up regenerates the manifest; its timestamp and hashes describe the current files.
- XML parsing, renderer syntax, ratio checks and whitespace checks (including untracked prototype files) passed. Only `docs/` is changed in the worktree.

## Saved renders

- [Design system and gesture/recording states](review/recon-capture-design-system.png)
- [Traditional phone](review/recon-capture-phone.png)
- [Traditional phone with external annotations](review/recon-capture-phone-annotated.png)
- [Cover screen](review/recon-capture-cover.png)
- [Fold8 inner: four photo states](review/recon-capture-fold8-inner.png)
- [Ultra inner: four photo and four video states](review/recon-capture-fold8-ultra-inner.png)
- [Render/source/asset manifest](review/manifest.json)

## Acceptance boundary

Each batch gate records review of rendered static prototypes. The five primary SVGs and annotation companion render with shared dependencies, pass XML/reference validation, and preserve their drawn preview ratios. `review/manifest.json` ties saved PNG evidence to SVG and font hashes. Approval covers historical rendering, shared component geometry and reproducibility in the reference renderer. Inner-screen orientation and ergonomic compositions are superseded pending replacement layouts and device evidence, as explained in the [orientation review](../foldable-camera-orientation-ux-review.md). This does not promise identical rasterization in every SVG viewer or device.

Runtime gestures, TalkBack behavior, font scaling, CameraX transforms, actual hardware insets, hinge handling and recording continuity are not verified by static SVGs. They remain requirements in the [prototype contract](README.md) and [adaptive implementation plan](../adaptive-foldable-capture-plan.md). Device names are scenario labels; no hardware specifications are certified here.

## Whole-design correction and adaptive framing batch — 2026-11-01

**PASS for static design reference and commit**, independently reviewed by GPT-6.1-SOL reviewer Zeno (`01a1982b-d57e-7020-9c86-4f5221c43798`). The reviewer inspected all eight sheets, every design-system panel and gesture legend, all 15 aspect-ratio examples, corrected Fold8 directions, left-hand/hinge cases and the two real-cover feedback examples. A second pass verified the final header/backdrop contrast, corrected video-family heading and final scrim renders. No remaining actionable visual defects were found. Component author/auditor Ramanujan (`01a1981f-f987-73c3-bef4-0aed3be5392d`) was separate from the final reviewer.

Corrected the demonstrated rear-frame directions immediately from the supplied Samsung screenshots. Replaced the undersized compact preview with width-maximized framing and overlay controls; complete 9:16 frames on the short cover use available height. Added square and variable Full-style framing examples, without assuming all cameras deliver 3:4. Inner layouts group capture controls into side/bottom docks and preserve queue gesture travel. Labels and explanations remain outside app viewports.

The component pass restores native white shutter rings/Cameraswitch, balances primary/decorative microphones, separates edge and split hit regions, preserves colored handle replacement and directional fades, and adds explicit pending/armed/reversed/cancelled, reorder, empty/overflow, accessible action, recording-lock, tutorial and long-text states. Existing runtime shortcomings are documented; no Kotlin or manifest changes are included.

Evidence: final manifest generated **2026-11-01 19:01:32 UTC**; 693 local references resolve; all eight HTTP and isolated-file renders match exactly; 36 generated geometries validate ratios, target separation, bounds and queue travel, including nested split ownership. All 288,000 pixels of the phone match its annotated companion. XML, JavaScript syntax, standalone/layout freshness and whitespace checks pass.

The design-system HTML-image render has one explicit, exact-hash [rasterization exception](review/rasterization/README.md). Its strict image-context result remains **false**, not silently relabeled a pass. Independent review confirmed sparse curve-edge coverage differences without obvious displacement or broken geometry. Other artifacts retain the original strict image-context tolerances. Any change to the pinned source/direct/image hash triple invalidates the exception.

This batch supersedes earlier screen geometry and incomplete state specimens. Official-source coding guidance and the remaining runtime, accessibility, grip and cross-engine validation work are in [camera-layout-implementation.md](camera-layout-implementation.md). Those backlog tasks do not block correcting the demonstrated design errors.

## Mode and zoom reference-learning batch — 2026-11-01

**PASS for the final eight-sheet static design set**, independently reviewed by GPT-6.1-SOL Harvey (`01a19a08-f47c-7690-a523-493b9ede4e06`). The initial review found a cover focus/zoom collision; the focus example was moved clear, then the reviewer reinspected the phone, cover and repeated layout/aspect fixtures. No remaining actionable visual defects were found. Documentation author James was separate from the visual reviewer.

- Replaced heavy segmented mode pills with horizontal text labels and a selected underline, immediately above the shutter. VIDEO–PHOTO–VOICE order and preview-swipe meanings remain consistent.
- Added circular zoom selection, quiet surfaces, an opposite vertical zoom rail for tall-frame side layouts, single-preset capability and continuous 1.7× specimens. Presets are not universal optical-lens claims.
- Preserved shutter and queue anchors, target sizes and gesture runway. Strengthened overlay contrast: inactive mode labels have at least 4.91:1 against the worst-case white camera frame under the specified scrim.
- Moved recording compositions to the same zoom/mode/shutter order. Replaced the short cover's conflicting 160-high banner example with the 72-high multiline state; large-text alternate presentation remains an explicit runtime requirement.
- Archived nine original user-provided Samsung/iPhone reference images with provenance and byte hashes. No Kotlin, manifest or camera implementation changes.

Evidence: manifest generated **2026-11-02T02:17:49.444Z**; 708 local references resolve; 36 generated layouts validate preview ratios, target separation, bounds and queue travel. All eight HTTP and isolated-file renders match. The clean and annotated phone match in all 288,000 pixels. Seven sheets pass strict HTML-image comparison. XML, JavaScript syntax, freshness and whitespace checks pass.

The design-system sheet's exact source/direct/image triple was reassessed: 305 of 10,598,400 pixels exceed delta8, maximum81 on the unchanged tutorial-marker curve. Independent direct/alternate inspection found no visible geometry or text-content change. The strict result remains false with a pinned, documented [curve-edge exception](review/rasterization/README.md); it is not a general tolerance relaxation. The renderer now collects diagnostics for every sheet before reporting portability failure, rather than stopping after the first artifact.

Native input arbitration, TalkBack, large-font presentation, actual camera capability selection and physical hand reach remain implementation acceptance work. This batch supersedes earlier mode/zoom placement and sheet dimensions.

## Top-mode correction — 2026-11-01

**PASS**, independently reinspected by GPT-6.1-SOL Harvey (`01a19a08-f47c-7690-a523-493b9ede4e06`) across all eight rendered sheets, with native-scale details of the 9:16 cover header, large-text feedback and recording compositions. No actionable visual defects found.

User direction supersedes the preceding placement decision: modality is a less frequent change and idle preview swiping is already the quick interaction, so all three modes remain centered in the top bar. Keep the lighter text/underline styling and circular zoom controls. Horizontal zoom returns immediately above the shutter; opposite vertical zoom rails remain. Shutter, queue and gesture travel anchors are unchanged. Restored the 160-high cover feedback specimen, now with 32 units before zoom targets. Header overlays use 72% black for at least 4.91:1 inactive-label contrast against a white preview.

Final manifest: **2026-11-02T02:24:39.736Z**, 708 local references, 36 valid geometries, all eight isolated-file renders identical to HTTP, seven strict image-context passes, and 288,000 exact phone/annotation pixels. Freshness, XML, JavaScript syntax and whitespace checks passed.

The modified design-system source required another raster audit before repinning: 301 pixels exceed delta8, maximum81 at the same tutorial-marker curve; both inspected direct hotspot crops now match the original reviewed crops. Offset/centroid diagnostics remain consistent with curve-edge coverage. Current measurements and hashes are recorded in the raster evidence. Strict image-context comparison remains false for this one exact artifact; reviewer approval covers static visual composition, not native runtime behavior.

## Modality selection without underline — 2026-11-01

**PASS**, independently reviewed by GPT-6.1-SOL Archimedes (`01a19a24-f4d2-7bf0-84a3-a9eef12ce2cb`). Inspected fresh phone, cover, both inner matrices, design-system mode variants and recording references. No residual selection dashes, alignment defects or ambiguous selected states found.

Removed the underline from all five shared modality variants. Selection uses solid white text at weight 700, with inactive gray text at weight 500; locked states retain their existing colors and bold selected weight. Top placement, touch targets, zoom, shutter and queue geometry are unchanged. This supersedes earlier underline guidance.

Evidence refreshed at **2026-11-02T02:46:15.117Z**: all eight render sets regenerated, seven strict image-context passes, all isolated-file outputs exact, 708 resolved references and exact annotated-phone pixels. Layout/freshness, XML and whitespace checks pass. The design-system raster exception was reassessed: signed RGB document-versus-image residuals are identical at every pixel before and after this change. The exact artifact hashes are refreshed; strict result remains false for the same documented curve-edge exception. No native implementation changes.

## Zoom proportions aligned with camera references — 2026-11-01

Independent GPT-6.1-SOL Hegel (`01a19a2b-e038-7d40-a9fa-8cdd2e2a5aa8`) visually approved phone, cover, inner wide/tall and the design-system zoom states after comparison with the supplied iPhone/Fold8 references. The fractional 1.7× value fits, selection remains readable and no target overlaps were found.

Zoom labels now use 12sp, matching modality labels. Four-option visible surfaces shrink from 220 × 40 to 176 × 32, and selected circles from 36 to 28. Targets remain separate 48 × 48 regions at 48 pitch; the full symbol viewport is 192 × 40 (or 40 × 192 vertically). Single-choice and continuous-zoom variants use the same visual tokens. Top modes remain white/bold without underline. These sizes are Recon design choices informed by the reference proportions, not dp measurements inferred from screenshots.

Evidence refreshed at **2026-11-02T02:53:40.917Z**: 36 geometry fixtures pass; 708 local references resolve; all eight isolated-file outputs equal HTTP; seven image-context comparisons pass strict thresholds; the annotated phone remains pixel-identical. XML, freshness and whitespace checks pass.

The reviewer initially observed incomplete exception metadata while the audit was still running. The final renderer now exits successfully with source/direct/image hashes pinned and recorded. Reassessment found all 301 above-delta8 residual pixels unchanged; 32 lower-difference pixels changed with new maximum4. The design-system strict result stays false under its exact documented curve-edge exception. No Kotlin or runtime changes.

Final evidence gate independently cleared by Hegel: hashes match the visual-review snapshot and raster pins; all eight artifacts report portability passed with the design-system strict exception explicitly retained.

## Fractional zoom label — 2026-11-01

**PASS**, independently reviewed by GPT-6.1-SOL Gauss (`01a19a49-0e35-7172-8687-9ec136ce32ca`). Changed displayed `0.5` to `.5` in horizontal, vertical and continuous zoom variants; numeric ratio and accessible full-value meaning are unchanged. Cover and inner renders remain centered, legible and unclipped. All eight render sets refreshed, 36 geometry fixtures valid and 708 references resolved. The exact raster exception was repinned after confirming zero changed document-minus-image residual pixels; strict result remains explicitly false for the same curve-edge exception.

## Tap-focus only — 2026-11-01

**PASS**, independently reviewed by GPT-6.1-SOL Noether (`01a19a53-862f-72d1-a1c7-02d8a4a0002c`). Reviewed cover, phone, inner layouts, design-system focus panel and gesture legend against the supplied Fold8 focus reference. Complete thin white rings are aligned and unclipped; no problematic control overlap or unrelated changes found.

Replaced the crosshair with a complete, transparent-centered white circle (radius30, stroke2), matching the existing Android resting indicator geometry. User clarification explicitly excludes focus locking and exposure adjustment: no padlock, lock gap, sun icon or exposure track is introduced. Static examples depict a transient tap-feedback moment. The gesture legend's blue contact dot remains an external annotation. Documented existing animation timing without changing Kotlin or runtime behavior.

Final manifest **2026-11-02T03:36:07.532Z**: 709 references resolve; all eight isolated-file renders equal HTTP; seven strict image-context passes; annotated phone exact; 36 generated geometries, XML, freshness and whitespace checks passed. The exact design-system raster exception was refreshed only after confirming the signed RGB difference is unchanged at every pixel. Native transforms and interaction execution remain future validation work.

## Long queue fades and fixed handle ownership — 2026-11-01

**PASS**, independently visually reviewed by GPT-6.1-SOL Poincare (`01a19a5d-e057-7e11-a543-b3f82b52d286`). Reviewed the new 312dp/360dp start, middle, end, split, commit and discard specimens, their full-size cover compositions, shared source geometry and interaction contract. Final direct/alternate crops show no apparent displacement, clipping, spacing or background-bar defect. Corrected the four-state caption and retained the original full-family 72dp swiper fade (compact48dp).

Overflowing thumbnails and dividers use an alpha mask beneath stationary handles. Fixed 48 × 48dp edge targets win initial hit testing over media underneath; initial item/gap ownership persists across an edge. Short queues stay centered. Endpoint padding keeps first/last tiles fully reachable. Long split gaps remain24dp with a centered4dp divider. No in-app instructions, extra count badges, native code or new gesture thresholds.

Final manifest **2026-11-02T03:50:45.073Z**: 868 resolved references, eight isolated-file outputs identical to HTTP, seven strict image-context passes and exact288,000 annotated-phone pixels. All40 screen geometries and12 overflow-state contract checks pass, along with generation freshness, XML, JavaScript syntax and whitespace checks. The source-pinned design-system raster exception is explicitly retained: previous high-delta residuals unchanged; new panels add19 pixels above8 (max21) on curves/corners, with unchanged overallmax81. Updated metrics, alternate render and bounded displacement audit are in `review/rasterization/`. Native pointer/accessibility behavior and physical-device gesture ergonomics remain implementation acceptance work.

## Bottom anchoring and dense short-window controls — 2026-11-01

**PASS**, independently reviewed by GPT-6.1-SOL Peirce (`01a19a66-f8ec-77a3-a0d6-27bec78c4d9c`). Inspected dense components/direct-versus-image crops, cover, short480/640 windows, active deletion and larger bottom-inset fixtures, full phone, both inner tall-window matrices, aspect-ratio cover variants and large-text feedback. No actionable balance, bounds, clipping, duration-readability or overflow/split issues found.

The previous universal72 +48 +inset rule left excessive idle space. Bottom docks now reserve24dp below their strip, with the actual safe inset handled once. Standard phone queue moves24dp down. Dense cover uses64dp shutter,48dp thumbnails in a64dp strip, unchanged11sp durations/12sp mode and zoom text, and48dp handle targets. Its queue moves32dp and shutter52dp downward. Wide inner side docks retain their balanced anchors. A provisional600dp usable-height breakpoint selects dense geometry; freeze it during active gestures.

This includes an explicit **gesture-tuning proposal**: item deletion arms at>24dp net downward travel and executes only on valid release; display displacement caps32dp. Horizontal scroll/reorder ownership prevents downward drift from becoming deletion; reversal, interruption and unsafe-bound cancellation restore the item. Last eligible thumbnail start plus32dp and the complete soft glow fit strip+24dp in both profiles. Split24dp and whole-queue edge96dp gates remain unchanged. The current native>40dp behavior has not been edited; false-trigger rates, reach, profile transitions and actual OS inset behavior require physical-device validation.

Final manifest **2026-11-02T04:09:35.184Z**: all8 isolated-file renders equal HTTP,7 strict HTML-image passes,1 explicit hash-pinned curve-edge exception,1008 resolved references and288,000 exact annotated-phone pixels. All44 screen geometries and18 overflow-state checks pass, including minimum48dp targets, unscaled queue sizes, bottommost deletion/split clearance, XML, generation freshness and whitespace. Raster exception reassessed for the new64dp rings: no coherent displacement in the inspected regions; recorded exact hashes and diagnostics in `review/rasterization/`. No Kotlin, manifest or camera implementation changes.
