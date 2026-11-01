# SVG correction and review record

Scope: the five Android SVG prototypes, supporting assets and reference documentation. No Kotlin, manifest, iOS or camera implementation changes.

**Accepted for static prototype reference on 2026-11-01.** All five batch gates passed. The final independent reviewer found no remaining actionable issues after corrections and inspected every current sheet and inner-screen artboard.

## Portability follow-up

The initial review covered HTTP rendering only. A user subsequently opened the Fold8 SVG in a preview that did not load external shared-symbol references: frames and captions appeared, while camera scenes and controls were blank. This was a delivery defect, despite the correct HTTP renders.

All five SVGs now embed their fonts and required shared definitions. `build-standalone.mjs` regenerates local symbol dependencies from the canonical design system without changing the screen layouts. Each exported file can be viewed without sibling files or a server.

Verification: all five HTTP PNGs are byte-identical to the previously accepted renders; all five isolated local-file renders match HTTP exactly. Phone, cover, Fold8 and Ultra also match exactly in HTML image context. The design-system image-context render differs only by compositor rounding (maximum channel delta 4/255, mean pixel delta approximately 0.003243/255). All 296 local references resolve, XML parses, and the bundler's non-mutating freshness check passes.

Independent GPT-6.1-SOL review: **PASS**, Boole (`01a19801-966b-7ae3-8294-1e66f0d4fc82`). The reviewer visually inspected all five sheets, isolated phone/Fold8/Ultra renders and image-context outputs, independently reran the corrected isolation harness, and confirmed no external dependencies, CSS conflicts or remaining actionable findings. This follow-up is verified before commit.

## Batch gates

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

- All five SVGs parse with `xmllint --noout docs/prototypes/*.svg`.
- The reproducible renderer validates unique IDs and all local references, checks all five PNG dimensions and embedded font bytes, and compares HTTP, isolated-file and image-context rendering. The current reference count is recorded in `review/manifest.json`.
- Eighteen displayed scene instances preserve their source aspect ratio, including exact 4:3 / 3:4 and 16:9 / 9:16 frames (portrait fractional values rounded to six decimals).
- All inner-screen queue bottoms remain at least 12 logical units inside the frame. Phone and cover preserve their respective 22/12-unit illustrative bottom insets.
- Current source and PNG hashes match `review/manifest.json`. Do not reuse a visual approval after source changes without rerendering affected artifacts.
- Initial five-batch evidence was generated at 2026-11-01 16:34:24 UTC. That final reviewer independently validated 224 references and all SVG/PNG/asset hashes; inspected full sheets and native-size details including all four Fold8 and all eight Ultra artboards. The portability follow-up regenerates the manifest; its timestamp and hashes describe the current files.
- XML parsing, renderer syntax, ratio checks and whitespace checks (including untracked prototype files) passed. Only `docs/` is changed in the worktree.

## Saved renders

- [Design system and gesture/recording states](review/recon-capture-design-system.png)
- [Traditional phone](review/recon-capture-phone.png)
- [Cover screen](review/recon-capture-cover.png)
- [Fold8 inner: four photo states](review/recon-capture-fold8-inner.png)
- [Ultra inner: four photo and four video states](review/recon-capture-fold8-ultra-inner.png)
- [Render/source/asset manifest](review/manifest.json)

## Acceptance boundary

Each batch gate is a review of rendered static prototypes. All five current SVGs render with their shared dependencies, pass XML/reference validation, preserve exact preview ratios, and received independent review after the final shared changes. `review/manifest.json` ties saved PNG evidence to SVG and font hashes. This is approval of the documented logical-unit layouts in the reference renderer; it does not promise identical rasterization in every SVG viewer or device.

Runtime gestures, TalkBack behavior, font scaling, CameraX transforms, actual hardware insets, hinge handling and recording continuity are not verified by static SVGs. They remain requirements in the [prototype contract](README.md) and [adaptive implementation plan](../adaptive-foldable-capture-plan.md). Device names are scenario labels; no hardware specifications are certified here.
