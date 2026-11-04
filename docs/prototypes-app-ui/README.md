# Recon Android app screens

Static SVG references for the Android screens surrounding capture. Open a sheet directly at 100% or use the companion PNG. The captions and measurement notes sit outside the app windows.

| Sheet | Review focus |
| --- | --- |
| [Components](components.svg) · [PNG](review/components.png) | Light/dark tokens, type, controls, gestures, media and modal surfaces |
| [Bundles](bundles.svg) · [PNG](review/bundles.png) | Library, first load, empty/error states, processing and adaptive list placement |
| [Bundle actions](bundle-actions.svg) · [PNG](review/bundle-actions.png) | Select two bundles and send, swipe or tap to delete, cancel and Undo |
| [Setup](setup.svg) · [PNG](review/setup.png) | First folder, cancelled picker, revoked grant and Android camera access |
| [Settings](settings.svg) · [PNG](review/settings.png) | Live preferences, output locks, short cover, menus, long paths and large text |
| [LocalSend](localsend.svg) · [PNG](review/localsend.png) | From discovery through receiver identity, file progress, cancellation and outcomes |

[Interaction contract](INTERACTIONS.md) · [Native baseline](BASELINE.md) · [Generated coordinates and targets](bounds/) · [Independent review](REVIEW.md) · [Render hashes](review/manifest.json) · [Capture designs](../prototypes/README.md)

## Rebuild

From this directory run `python3 build.py`, then `node render.mjs`. Both commands accept individual sheet names. `python3 build.py --check` verifies generated SVG and geometry freshness, symbol references, target size and target separation. The renderer loads each standalone SVG without sibling resources, waits for its embedded font, checks for visible text outside the frame and saves a 1:1 PNG. It then opens the file from disk and requires a byte-identical PNG. See the baseline for runtime dependencies.

After rendering, run `python3 build.py --check --verify-renders` to verify all generated files against the reviewed hashes.

Edit the Python sources and regenerate. The SVG files embed the licensed [Roboto font](../prototypes/assets/Roboto-OFL.txt) and the used icon paths. The paths in `material-icons.json` were transcribed from the AndroidX Compose Material Icons 1.7.8 Filled vector sources, copyright the Android Open Source Project, under the [Apache 2.0 license](../prototypes/assets/Material-Icons-LICENSE.txt). The compact media illustrations are original vector fixtures.
