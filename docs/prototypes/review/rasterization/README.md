# Current raster exception — whole-design review refinements

The1440 ×9000 sheet and exact source/direct/image hashes are recorded in [current-measurements.json](current-measurements.json) and pinned in `../../render-review.mjs`. All nine direct/isolated-file renders match. Eight other sheets pass the strict HTML-image limits. This design-system sheet alone keeps its reviewed, exact-artifact exception; its strict comparison remains false.

- 12,960,000 pixels;34,131 differ in any RGB channel;673 exceed delta8 and95 exceed delta32. Maximum81 stays at the unchanged tutorial curve(254,5410); mean maximum-channel difference0.0038819444/255.
- Against commit933d32a,11,090 signed residual pixels change after intentional relocation/layering edits. Only one newly above8 location appears: delta12 at(253,4235), on the relocated disabled microphone. No new pixel exceeds32.
- The original tutorial/voice-stop hotspots and dense shutter displacement diagnostics are unchanged. Zero-offset error remains far lower than any±1px translation. These measurements and direct/alternate visual review support sparse curve-edge coverage differences; they are not a global tolerance relaxation.
- Independent GPT-6.1-SOL Bohr reviewed all DS sections and then the corrected deletion, tutorial and recording crops in both render contexts: PASS, no visible regression. The [alternate render](design-system-image-context.png) is refreshed. Every future source or raster change invalidates this pin.


[Historical investigations and diagnostic method](../../../archive/rasterization-review-history.md) are retained separately. The existing enlarged tutorial comparison remains valid evidence for that unchanged hotspot.
