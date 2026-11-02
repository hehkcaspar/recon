# Documentation map

Updated 2026-11-02. Keep the existing Android implementation separate from the proposed adaptive capture design.

| Purpose | Authoritative entry point |
| --- | --- |
| Existing app, architecture and build | [Repository README](../README.md), [product baseline](../recon-mvp-designs.md), [release runbook](../RELEASE.md) |
| Current proposed Android capture design | [Prototype index](prototypes/README.md), [component contract](prototypes/COMPONENT-CONTRACT.md), nine SVG sheets and generated [geometry](prototypes/layout-geometry.json) |
| Future adaptive implementation | [Implementation plan](adaptive-foldable-capture-plan.md), [camera guidance and runtime backlog](prototypes/camera-layout-implementation.md) |
| Current static acceptance | [Design audit](prototypes/DESIGN-AUDIT.md), [review](prototypes/REVIEW.md), [render manifest](prototypes/review/manifest.json) and [bounded raster exception](prototypes/review/rasterization/README.md) |
| Original visual evidence | [Camera references](references/README.md) |
| Superseded investigations and approvals | [Archive](archive/README.md); historical evidence, never current implementation instructions |

The SVG contract supersedes the baseline's capture-layout proposals, but does not claim the Android code implements them. Update code-facing documentation when that implementation actually changes. Device names and screenshot dimensions are comparison fixtures, not runtime layout constants.

## File retention

- Keep all nine SVGs, their matching PNGs, generation/render scripts, geometry and manifest: they form the reproducible design reference.
- Keep bundled fonts and licenses, original camera screenshots, the exact alternate renderer output and the unchanged tutorial comparison: they are dependencies or verification evidence.
- Historical prose is archived separately. Outdated objections and old file counts must not appear as current acceptance claims.
- No obsolete duplicate prototype files, backup copies or temporary render files were found in the tracked design/reference tree during this cleanup. This is not a claim that the entire application has been audited for dead code.
