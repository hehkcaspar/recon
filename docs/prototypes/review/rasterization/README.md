# Current raster exception — bottom anchoring and dense controls

The current sheet is 1440 ×9000. Source/direct/image hashes are recorded in [current-measurements.json](current-measurements.json) and pinned in `../../render-review.mjs`. Direct HTTP and isolated-file PNGs match exactly; strict HTML-image comparison remains false under this exact-artifact exception. Seven other sheets retain their strict thresholds.

- 12,960,000 pixels;32,692 have any RGB difference;678 exceed delta8 and 95 exceed delta32. Overall maximum 81 remains at the old tutorial curve(254,5410); mean maximum-channel difference0.003776466/255.
- In the existing 1440 ×8280 area,2,228 residual pixels change; their new maximum 10 occurs at(255,3005), a revised delete-thumbnail boundary. The new dense panel contributes 358 pixels above 8, maximum 58, concentrated on the five new 64dp shutter curves.
- Dense PHOTO ring(72,8384,64,64): centroid delta+0.00318/+0.00223px; zero-offset error 1,123 versus best nonzero±1px error 72,080. Dense voice Stop(520,8384,64,64): +0.01011/+0.03046px; zero error 1,814 versus 59,461. These diagnostics and direct/alternate visual inspection support curve-edge coverage differences, not a coherent displacement.
- The two original hotspots retain their exact direct pixels and diagnostic results. Their existing enlarged comparison remains valid. The [full alternate render](design-system-image-context.png) is refreshed.

Independent GPT-6.1-SOL Peirce visually approved the final dense direct/alternate crops, feedback bounds and bottom-dock fixtures without material visual regression. This bounded exception does not certify native gestures, device reach or false-trigger rates. Every future source/raster change invalidates the pin.

---

# Historical reassessment — long-queue revision

Reassessed for the 1440 × 8280 sheet. Exact source/direct/image hashes and measurements are in [current-measurements.json](current-measurements.json) and pinned in `../../render-review.mjs`. All direct HTTP / isolated-file pixels match. Strict HTML-image comparison remains false; the exception is artifact-specific, not a relaxed global threshold.

- 11,923,200 pixels; 33,812 differ in any channel; 320 exceed delta8, 55 exceed delta32; overall maximum 81 remains at the unchanged tutorial curve (254,5410). Mean maximum-channel difference is 0.003583434/255.
- In the pre-existing 1440 × 7360 area, 383 signed RGB residual pixels change, with new maximum 1. All previous above-delta8 residuals remain unchanged.
- The appended overflow panels introduce 19 above-delta8 pixels, maximum 21, at microphone curves and rounded thumbnail edges. No new pixel exceeds32. Coordinates are recorded in the measurements file.
- New microphone region (193,7588,56,56): centroid delta +0.00190/−0.00066px; zero-offset error 150 versus best ±1px error 22,920. New rounded-tile region (1010,7488,56,56): +0.00113/−0.00171px; zero-offset error 61 versus best shifted error 11,864. Neither suggests a coherent displacement.
- The two older hotspot crops retain their prior pixels and diagnostic results. The [alternate full render](design-system-image-context.png) is refreshed; the old eight-times tutorial enlargement is still valid for that unchanged hotspot.

Independent GPT-6.1-SOL Poincare inspected the final direct/alternate overflow crops and found no apparent displacement or visual defect. This bounded assessment concerns SVG compositor edge coverage, not native interaction correctness. The seven other sheets retain their strict thresholds. Any future source or raster change invalidates these hashes and requires reassessment.

---

# Historical reassessment — mode and zoom revision

Reassessed 2026-11-01 for the 1440 × 7360 design-system sheet, including the zoom-size refinement and subsequent `0.5` → `.5` label change. The label change leaves signed RGB document-minus-image residuals identical at every pixel. All 301 pixel locations exceeding delta 8 retain identical signed RGB document-minus-image residuals. Only 32 lower-difference residual pixels changed; their new maximum delta is 4, within the strict tolerance. The subsequent tap-focus ring revision also leaves the signed RGB residual identical at every pixel; its refreshed hashes and audit are in the measurements file. The exact source/direct/image hashes are in [current-measurements.json](current-measurements.json) and pinned in `../../render-review.mjs`. Direct HTTP and isolated-file output match exactly. The strict HTML-image check remains false; this is a narrowly scoped reviewed exception, not a global tolerance increase.

- 10,598,400 total pixels; 23,529 have any channel difference.
- 301 pixels exceed delta 8; 55 exceed delta 32; maximum 81 at (254,5410), on the tutorial marker curve.
- Mean maximum-channel difference: 0.0029754491/255.
- Tutorial-marker region (220,5360,64,72): centroid delta −0.00460/−0.01743px; zero-offset error 898 versus best nonzero ±1px error 22,122. The direct crop is identical to the previous reviewed crop, so the existing eight-times enlargement still represents this hotspot.
- Voice Stop region (1020,4178,96,100): centroid delta −0.00330/+0.02080px; zero-offset error 2,893 versus best nonzero error 85,189. Mode selection was restored to the top of the composed reference. This crop is now identical to the original reviewed crop; the comparison was measured again.

Independent GPT-6.1-SOL reviewer Harvey inspected direct/alternate crops and found curved-edge raster differences without visible geometry or text-content changes. The new mode and zoom panels introduce no pixels exceeding delta 8. The full current alternate image is [design-system-image-context.png](design-system-image-context.png). All seven other sheets retain the original strict tolerances. Any source or raster change invalidates the pinned exception.

The investigation below records the previous 1440 × 6900 sheet; its old whole-sheet numbers are historical, superseded by the measurements above. Its diagnostic method and scope limitations still apply.

---

# Bounded SVG document/image rendering investigation

Conclusion: the tested hotspots show sparse curve-edge coverage differences, without evidence of a coherent one-pixel displacement. The maximum delta is not on text. No rendering fix was established. No repository files were edited during this investigation. This diagnostic does not replace the existing strict portability check or grant release approval.

## Snapshot and reproduction

The temporary baseline is `baseline.svg`, copied before testing. Headless Chrome rendered the same SVG at 1440 × 6900, device scale 1, as a direct SVG document and as an HTML image. Experiments were performed in temporary files. The final comparison evidence is preserved here. The repository SVG was not changed by any experiment.

The accepted direct render is `../recon-capture-design-system.png`; the alternate renderer output is `design-system-image-context.png`. The exact source and both PNG hashes are pinned in `render-review.mjs`. The comparison image `circle-doc-img-diff-8x.png` shows document, image and four-times-amplified absolute difference, each enlarged eight times using nearest-neighbor sampling.

## Whole-sheet measurements

| Measurement | Result |
| --- | --- |
| Dimensions | 1440 × 6900 = 9,936,000 pixels |
| Maximum RGB channel difference | 81/255 |
| Mean maximum-channel difference per pixel | 0.0031836755/255 |
| Pixels with any difference | 23,503 |
| Pixels exceeding delta 8 | 308 = 0.00310% of the sheet |
| Pixels exceeding delta 32 | 55 = 0.000554% of the sheet |

The maximum is at (254, 5410), on the bottom edge of the tutorial swipe marker's circle. The reported region near (1057, 4193) is on the voice Stop ring. Treating those peaks as font glyph differences was incorrect.

## Local displacement diagnostic

Use fixed local regions around a suspicious mark. Compare the same pixels at zero offset and at every integer offset (dx,dy) in {-1,0,1}². For each offset, sum absolute red-channel differences over the region interior, excluding its outermost pixel. For these white/gray marks on unchanged dark backgrounds, red is a sufficient consistent luminance proxy. Also compute each region's brightness-weighted centroid in document and image renders. These centroid measurements include the stable background and are local diagnostics, not exact geometric fits.

| Region (x,y,width,height) | Centroid delta x / y | Zero-offset error | Best nonzero ±1px error | Nonzero / zero |
| --- | --- | --- | --- | --- |
| Swipe marker (220,5360,64,72) | −0.00460 / −0.01743px | 898 | 22,122 | 24.63× |
| Voice Stop (1020,4178,96,100) | −0.00330 / +0.02080px | 2,893 | 85,189 | 29.45× |

A bounded diagnostic for classifying these tested hotspots as edge rasterization differences is: each centroid axis changes by at most 0.05px, and zero-offset error is at most one tenth of the best nonzero ±1px error. Both measured regions satisfy those bounds. An actual coherent one-pixel translation would instead improve the matching nonzero-offset score. The enlarged comparison shows changed boundary coverage, rather than doubled or relocated shapes.

Scope limits: this supports the classification of these two inspected hotspots; it does not prove every glyph or component is identical, exclude all subpixel contour changes, or establish an accessibility/runtime result. Centroids alone can hide symmetric shape differences, so use the offset test and visual edge comparison together. Do not turn the diagnostic into an unqualified global pixel-tolerance increase.

## Experiments and interpretation

- Geometric-precision text, root precision, shape precision, disabled kerning/ligatures, disabled optical sizing and font smoothing did not remove max81.
- Removing the embedded font face did not remove max81. Font-render hinting and LCD suppression had already been tested by the parent without resolving it.
- Converting circles to equivalent cubic paths did not remove max81; this is not an established fix and should not be applied to the repository.
- Removing all text reduced the remaining vector difference to max1. Adding one simple text label retained max1. Thus some text-containing drawing structure affects the rendering path, but the exact Chrome mechanism is unproven.
- A 3860px crop reduced the maximum to 14 by excluding lower hotspots. It does not prove a renderer height limit or fix the full sheet. The original shorter sheet contains different specimens and is not a controlled height comparison.

## Acceptance boundary

The renderer retains its strict image-context tolerance for every other artifact. This exact design-system SVG/direct-PNG/image-PNG hash triple is a documented exception, so any subsequent source or raster change must be reassessed. The manifest records `strictImageContextPassed: false` and the evidence link; this is not a claim of pixel-identical rendering across engines. Direct HTTP and isolated-file output remain exactly identical. Independent visual review determines whether the sparse edge coverage differences constitute an obvious visual defect. Future cross-engine raster consistency is a backlog item, not a reason to retain the previous incorrect layout.
