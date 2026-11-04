# Current SVG review

**Design C is the current foldable static direction · 2026-11-03.** A/B are archived historical studies. Current static acceptance covers C's stable preview, pose-driven queue axis, shared profile tokens, margin-first capacity and role-aware render manifest. Runtime WindowMetrics/insets/hinge/gesture/device acceptance remains pending; there is no fixed 400dp product threshold.

Current acceptance is limited to the static design. [Historical reviews](../archive/prototype-review-history.md) are retained separately; they must not override the [component contract](COMPONENT-CONTRACT.md), [geometry](layout-geometry.json) or [latest manifest](review/manifest.json).

## C source and documentation reconciliation — 2026-11-04

The hand-authored [C geometry source](foldable-c-geometry-source.json) is now the single numeric source for `standard56` / `compact48` profile tokens and Fold8/Fold8 Ultra C pose coordinates. Python and JavaScript generators read that source; [foldable-c-geometry.json](foldable-c-geometry.json) is generated output and records source/generator hashes, zoom bounds, queue bounds and the runtime boundary. The two A inner sheets remain archived layout evidence but are explicit preview/definition fixtures required by the C build and checked against the source preview rectangles.

The geometry validator now checks all matrix modes and poses, focused Ultra sheets, gesture-guide coordinate labels and actual schematic geometry, source-preview stability, 12dp zoom-to-shutter spacing, queue/shutter axis alignment and CW90 outer-margin balance. The render manifest records C provenance without self-referencing the generated JSON, PNGs or manifest. Current static checks pass: 59 generic geometries, 18 overflow states, 1878 SVG references, 16 render roles (9 current / 4 generic-reference / 3 archived), XML, generator freshness, local Markdown links and standalone raster portability.

**Final static gate: APPROVED.** Independent fresh-eyes GPT-6.1-SOL Dewey (`01a1a59a-82c0-76d2-b38b-1ce6b9b0e849`) reviewed the final source, generated JSON, manifest and local PNGs. It confirmed no blocking or non-blocking findings after the queue-lane source derivation was corrected. Runtime WindowMetrics, density, insets, hinge, gesture arbitration, accessibility and held-device reach remain separate acceptance work.

## C unification review — 2026-11-03

**Final static gate: APPROVED.** Independent GPT-6.1-SOL Kant (`01a1a4ca-97a3-7041-be06-7b86c4c87410`) re-reviewed the repaired archive freshness, current C/role-aware wording, raster evidence and orphan cleanup. Runtime/device acceptance remains a separate gate.

1. `queue-geometry.mjs` and its Python mirror produce the same `standard56` / `compact48` tokens and the same `N/P` capacity result.
2. `foldable-c-geometry.json` matches the Fold8/Fold8 Ultra C matrices: stable preview bounds, queue axis/position, queue-to-shutter relationship, zoom-to-shutter `12dp`, and approximately `1:1:1` CW90 outer margins.
3. `render-review.mjs` renders all 16 SVGs but distinguishes `current`, `generic-reference` and `archived` roles in `review/manifest.json`. A/B can remain evidence without becoming current inputs.
4. XML, generator freshness, whitespace, standalone-file portability and the independent fresh-eyes visual/consistency review all pass. The archived B generator is fresh, and the linked design-system raster exception matches the current manifest/measurements. Static evidence does not claim runtime/device acceptance.

## Historical full design audit and state coverage — 2026-11-01

**PASS for the static artifacts reviewed on 2026-11-01.** Two independent GPT-6.1-SOL reviewers inspected the whole artifact set, reported defects, and reviewed the corrections:

- **Bohr** (`01a19a8a-d34e-7d72-aa16-76d5cb50d879`): all sections of the1440 ×9000 component sheet, then corrected direct/alternate deletion, tutorial and recording crops. PASS; no remaining actionable visual defects. An earlier reviewer transport failure was not treated as approval.
- **Beauvoir** (`01a19a7d-068c-7ac3-962c-4e39d55816c1`): all44 original screen fixtures and annotation sheet, then changed inner/ratio/layout fixtures and all11 new recording/voice windows. PASS, including revised prior-capture error wording. Recording PNG SHA256 `c4b9ce76067b380e997f47cb2bf0fda1a4504553ead72907e147a057d622c1cc`.

Corrections: common1× preset stays anchored across rear/front capabilities; two inefficient side compositions use larger bottom-dock previews; phone square frame is vertically balanced; recording component spacing matches the real dock; deletion gradients paint behind media; tutorial arrows start at actual handles/gaps; preview swipe thresholds and endpoint/cancellation rules are explicit. Added eleven complete VIDEO/VOICE windows with stable anchors, locked navigation/queue, reachable Stop and separate timer/error hosts. Recording error copy says **Earlier capture was not saved** so it cannot be mistaken for a failed current recording that is still shown as live. Shared large-banner geometry prevents width/action drift.

Checks at the 2026-11-01 audit: 55 screen geometries,18 overflow states,1× cross-camera anchors, cross-mode shutter/queue anchors, recording input restrictions, real Dismiss target bounds, frame ratios, minimum targets, status/control separation, XML, generator freshness and whitespace. All9 direct/isolated-file renders match;8 strict HTML-image passes; component sheet uses the re-audited exact-hash raster exception (673 pixels above8/255 out of12,960,000; maximum81 unchanged).1,137 references resolve; annotation sheet retains exactly288,000 phone pixels. Exact hashes/timestamp are in `review/manifest.json`.

The [completion audit](DESIGN-AUDIT.md) maps requirements to current evidence and separates implementation/device validation. No Kotlin, manifest, camera or storage behavior was changed.

## Documentation cleanup — 2026-11-02

Independent GPT-6.1-SOL Parfit (`01a19ac2-7323-73c1-839d-854321723be6`) reviewed the cleanup over `fc68562`: **PASS**, no actionable findings. Superseded orientation research and historical prototype/rasterization reviews are preserved in `docs/archive/`; current entry points explicitly distinguish existing Android behavior from proposed designs. All132 local Markdown paths resolve. All9 SVG/PNG pairs still match their reviewed manifest hashes; generation freshness and whitespace checks pass. No visual or native implementation changes. Original camera references, dependencies and still-used raster evidence are retained. Generated Finder metadata was removed from `docs/`.

## Unfolded physical-edge revision, 2026-11-03

GPT-6.1-SOL reviewer Lorentz inspected 25 native-scale crops across the two paired-pose sheets, five inner aspect fixtures and four recording/voice fixtures. The first pass found an off-axis front zoom and three ratio fixtures using the older window layout. The revised crops passed independent reinspection, including equal corner glyph margins, fixed 80dp utility edge, the wider 9:16 control area and the three aligned capture rows. The geometry check covers 59 fixtures. Native grip and interaction testing remains a separate step.

## Ultra chassis-fixed alternative B, 2026-11-03

Independent GPT-6.1-SOL review passed for the nine 1:1 artboards and four queue specimens on the comparison sheet. It verified physical alignment with CW90, upright thumbnails and labels, and the origins and directions of both active handle fades. The checked PNG is 2040 × 5480. Native reach and gesture handling remain to be tested if this variant is selected.

## Display measurement reference, 2026-11-03

GPT-6.1-SOL Einstein inspected the 1680 × 1420 reference PNG at 1:1 and checked all six rows, decimal rounding, derived frame/space values and Pixel logs. It requested embedding the actual capture commands alongside the reduced evidence excerpts. Those commands were added, the generator freshness check passed, and the reviewer cleared the finding. The SVG identifies Samsung measurements as community evidence and the Pixel readings as this local AVD configuration.
