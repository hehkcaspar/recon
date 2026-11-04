# Current raster exception — whole-design review refinements

The 1440 × 9000 sheet and exact source/direct/image hashes are recorded in [current-measurements.json](current-measurements.json) and pinned in `../../render-review.mjs`. All 16 direct/isolated-file renders match. Fifteen other sheets pass the strict HTML-image limits. This design-system sheet alone keeps its reviewed, exact-artifact exception; its strict comparison remains false. The current pin includes the `standard56` 6 × 36 handler/56dp edge-target revision and the capacity-driven `compact48` idle queue; compact48 handlers remain 6 × 28.

- 12,960,000 pixels;35,884 differ in any RGB channel;681 exceed delta8 and96 exceed delta32. Maximum81 stays at the unchanged tutorial curve(254,5410); mean maximum-channel difference0.0040249228/255.
- The prior pin is retained as historical comparison evidence only. The current canonical sheet intentionally updates standard56 edge-target annotations and capacity wording; no global raster tolerance was relaxed.
- The original tutorial/voice-stop hotspots and dense shutter displacement diagnostics remain unchanged after the current text-only dense-profile annotation update. Zero-offset error remains far lower than any±1px translation. These measurements and direct/alternate visual review support sparse curve-edge coverage differences; they are not a global tolerance relaxation.
- Independent GPT-6.1-SOL Bohr reviewed all DS sections and then the corrected deletion, tutorial and recording crops in both render contexts: PASS, no visible regression. The [alternate render](design-system-image-context.png) is refreshed. Every future source or raster change invalidates this pin.


[Historical investigations and diagnostic method](../../../archive/rasterization-review-history.md) are retained separately. The existing enlarged tutorial comparison remains valid evidence for that unchanged hotspot.
