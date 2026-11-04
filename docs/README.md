# Documentation map

**设计 C 是当前折叠屏静态方案.** A/B layouts are archived. Fold8 and Fold8 Ultra use the C pose-driven queue/control composition; the [C matrices](prototypes/recon-capture-fold8-c.svg) and generated [C geometry reference](prototypes/foldable-c-geometry.json) are current static outputs. The hand-authored [C geometry source](prototypes/foldable-c-geometry-source.json) is the numeric source of truth; do not edit the generated JSON directly. Runtime WindowMetrics, insets, hinge bounds, gesture arbitration and device acceptance remain pending; there is no fixed 400dp product threshold. The [measurement reference](prototypes/recon-capture-display-metrics.svg) remains calibration evidence.

Updated 2026-11-03. Keep the existing Android implementation separate from the adaptive capture design until native acceptance is complete.

| Purpose | Authoritative entry point |
| --- | --- |
| Existing app, architecture and build | [Repository README](../README.md), [product baseline](../recon-mvp-designs.md), [release runbook](../RELEASE.md) |
| Current Android capture design | [Prototype index](prototypes/README.md), [component contract](prototypes/COMPONENT-CONTRACT.md), current C matrices/gesture guide, display measurements and [C geometry](prototypes/foldable-c-geometry.json) |
| Active adaptive implementation | [Implementation plan](adaptive-foldable-capture-plan.md), [camera guidance and runtime backlog](prototypes/camera-layout-implementation.md) |
| Static artifact review history | [Design audit](prototypes/DESIGN-AUDIT.md), [review](prototypes/REVIEW.md), [render manifest](prototypes/review/manifest.json) and [bounded raster exception](prototypes/review/rasterization/README.md) |
| Proposed Android Setup, Settings, Bundles and LocalSend | [SVG index](prototypes-app-ui/README.md), [interaction contract](prototypes-app-ui/INTERACTIONS.md), [baseline](prototypes-app-ui/BASELINE.md) and [review](prototypes-app-ui/REVIEW.md) |
| Original visual evidence | [Camera references](references/README.md) |
| Superseded investigations and approvals | [Archive](archive/README.md); historical evidence, never current implementation instructions |

The SVGs record the current static capture design. A/B remain historical evidence; generic responsive sheets are labeled as such and do not replace the C matrices. The Android code now contains the first lifecycle, fold-observation, preview-geometry and queue-safety foundations; the plan's native layout, gesture, accessibility and device evidence remain incomplete. Device names and screenshot dimensions are comparison fixtures, not runtime layout constants.

## File retention

- Keep all current SVGs, their matching PNGs, generation/render scripts, geometry and manifest: they form the reproducible design reference.
- Keep bundled fonts and licenses, original camera screenshots, the exact alternate renderer output and the unchanged tutorial comparison: they are dependencies or verification evidence.
- Historical prose is archived separately. Outdated objections and old file counts must not appear as current acceptance claims.
- No obsolete duplicate prototype files, backup copies or temporary render files were found in the tracked design/reference tree during this cleanup. This is not a claim that the entire application has been audited for dead code.
