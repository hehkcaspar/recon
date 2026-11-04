# Foldable layout A/B archive

This page is the retention index for the superseded foldable layout studies. A
and B remain available for reproducible review and source geometry, but neither
is a current product direction. The current Fold8 and Fold8 Ultra composition
is design C, indexed from [the prototype contract](../prototypes/README.md).

## Proposal A — historical source geometry

- [Fold8 inner source](../prototypes/recon-capture-fold8-inner.svg)
- [Fold8 Ultra inner source](../prototypes/recon-capture-fold8-ultra-inner.svg)
- [Historical render evidence](../prototypes/review/recon-capture-fold8-inner.png)
- [Historical Ultra render evidence](../prototypes/review/recon-capture-fold8-ultra-inner.png)

These files retain the original preview, utility-strip and camera-capability
fixtures used to reproduce the source chassis geometry. C still reads the A
fixtures as preview/source evidence so that preview bounds remain stable; that
does not make A's control or queue arrangement current.

## Proposal B — historical control study

- [Fixed-dock B sheet](../prototypes/recon-capture-fold8-ultra-fixed-dock.svg)
- [B source generator](../prototypes/ultra-dock-study.py) (legacy generator; its extra symbols are frozen locally)
- [Historical render evidence](../prototypes/review/recon-capture-fold8-ultra-fixed-dock.png)

B explored chassis-fixed controls and a different upright queue arrangement. It
is retained for decision traceability only. C never imports B geometry or B
interaction rules.

## Current replacement

- [Fold8 C matrix](../prototypes/recon-capture-fold8-c.svg)
- [Fold8 Ultra C matrix](../prototypes/recon-capture-fold8-ultra-c.svg)
- [Ultra upright C study](../prototypes/recon-capture-fold8-ultra-c-upright.svg)
- [Ultra CW90 C study](../prototypes/recon-capture-fold8-ultra-c-cw90.svg)
- [C gesture guide](../prototypes/recon-capture-foldable-c-gesture-guide.svg)
- [Machine-readable C geometry](../prototypes/foldable-c-geometry.json)

The render manifest labels A/B as `archived` while continuing to render and
hash them. Only the manifest's `currentFiles` are current design inputs; the
generic responsive sheets are labeled `generic-reference`.
