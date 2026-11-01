# SVG correction and review record

Scope: the five Android SVG prototypes, the phone annotation companion, supporting assets and reference documentation. No Kotlin, manifest, iOS or camera implementation changes.

**Accepted for static prototype reference on 2026-11-01.** All five batch gates passed. The final independent reviewer found no remaining actionable issues after corrections and inspected every current sheet and inner-screen artboard.

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

Each batch gate is a review of rendered static prototypes. All five current SVGs render with their shared dependencies, pass XML/reference validation, preserve exact preview ratios, and received independent review after the final shared changes. `review/manifest.json` ties saved PNG evidence to SVG and font hashes. This is approval of the documented logical-unit layouts in the reference renderer; it does not promise identical rasterization in every SVG viewer or device.

Runtime gestures, TalkBack behavior, font scaling, CameraX transforms, actual hardware insets, hinge handling and recording continuity are not verified by static SVGs. They remain requirements in the [prototype contract](README.md) and [adaptive implementation plan](../adaptive-foldable-capture-plan.md). Device names are scenario labels; no hardware specifications are certified here.
