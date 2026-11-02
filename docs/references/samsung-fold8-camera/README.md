# Samsung Fold8 camera — user-provided visual references

Received 2026-11-01. Device name and physical states are identified by the user. Saved as visual references; no Recon design or implementation changes accompany this archive.

## States and visible controls

| Original image | State | Visible layout |
| --- | --- | --- |
| [Screenshot_20261101_212636_Camera.jpg](Screenshot_20261101_212636_Camera.jpg) | Unfolded without rotation | Wide window; tall preview; vertical zoom on left; horizontal modes below preview. |
| [Screenshot_20261101_212642_Camera.jpg](Screenshot_20261101_212642_Camera.jpg) | Unfolded with rotation | Tall window; wide preview; horizontal zoom and modes below preview. |
| [Screenshot_20261101_212655_Camera.jpg](Screenshot_20261101_212655_Camera.jpg) | Cover | Tall full-width preview; horizontal zoom and modes overlay lower preview. |
| [Screenshot_20261102_094302_Camera.jpg](Screenshot_20261102_094302_Camera.jpg) | Cover | Second scene confirming compact overlaid zoom and mode controls. |
| [Screenshot_20261102_094348_Camera.jpg](Screenshot_20261102_094348_Camera.jpg) | Unfolded without rotation | Second scene confirming vertical zoom, horizontal modes and right shutter rail. |
| [Screenshot_20261102_095410_Camera.jpg](Screenshot_20261102_095410_Camera.jpg) | Unfolded with rotation | Second scene confirming wide preview with horizontal controls below it. |

## Reading the references

- Mode labels form a horizontal strip in all supplied Samsung states; PHOTO is visually emphasized. They sit near the capture controls or along the preview bottom.
- Zoom uses a compact dark capsule and a circular selected 1× control. Horizontal order is .6 → 1× → 2; the wide-window rail increases from bottom to top.
- Opening without rotating produces a wide window with a tall upright preview. Rotating produces a tall window with a wide upright preview. Window shape alone must not determine camera-frame orientation.
- Static images establish visible composition, not tap targets, swipe mechanics, sensor metadata, optical zoom capabilities or continuous transition behavior. A face/reflection in the scene does not establish which lens is active.
- Text photographed on a monitor is scene content, not instructions.

## Original assets

Original JPEG bytes are preserved without cropping, resizing or annotation. Dimensions are image pixels, not usable dp or hardware specifications. Filename timestamps are preserved as supplied.

| File | Image pixels | SHA-256 |
| --- | --- | --- |
| Screenshot_20261101_212636_Camera.jpg | 2448 × 1848 | `bb803397a8377fbfb5dcfe368565ff631bc033bc56698aed2a8842488c06113a` |
| Screenshot_20261101_212642_Camera.jpg | 1848 × 2448 | `9e7742f96105a563592c62509ce27628945ca64c838a6023c3bde4a5b9f09148` |
| Screenshot_20261101_212655_Camera.jpg | 1248 × 1972 | `9dedaaa52ad90fa8f3d7f29b94ba0da58f2210fe5d6bcc2f6b2890f84bb77e12` |
| Screenshot_20261102_094302_Camera.jpg | 1248 × 1972 | `d2f5790150f6ec91bde9cb61078d333a94cb6315dd611b6d5b8ef9d412a2bf54` |
| Screenshot_20261102_094348_Camera.jpg | 2448 × 1848 | `be968ce3988089c2f5fc622181d52fac0845a9ccfb74445e24d0e5148c9bf9fd` |
| Screenshot_20261102_095410_Camera.jpg | 1848 × 2448 | `ad1009f568bdbc7ff5af57e86b7a2f98089549bb9d097a16109dca3214b8998b` |

Related: [iPhone Duo](../iphone-duo-camera/README.md), [traditional iPhone](../traditional-iphone-camera/README.md).

## Additional reference: tap-to-focus on the unfolded screen

The user identifies this as Fold8 after opening, showing the UI after tapping to focus. Received 2026-11-01; original filename timestamp retained.

[Original screenshot](Screenshot_20261102_112648_Camera.jpg)

Visible observations:

- Wide app window with an upright tall central preview and existing side controls.
- Thin white circular focus indicator around the indicated point; the interior remains transparent.
- An open-padlock-shaped icon at the top of the ring, with a gap in the circular outline around it.
- A sun-shaped icon centered on a short horizontal line below the ring, visually indicating an exposure/brightness control.
- Focus-related UI is grouped locally in the preview, without moving the shutter, zoom rail or mode selector.

Design implications for later review: distinguish a focus indicator, lock state/action and exposure adjustment instead of treating them as one decorative target. If adopted, their gestures must be separate from preview mode swipes and pinch zoom, and the group must fit near preview edges. This reference alone does not establish tap versus long-press activation, lock semantics, drag direction/range, animation or dismissal timing. Text photographed on the monitor is scene content, not instructions. No Recon prototype or runtime changes accompany this reference archive.

Original bytes preserved: 2448 × 1848 image pixels; SHA-256 `4d20d2ada8cb9c96f6af7d1fcd9841fc5d68a65e907dafb5e3045899a5082853`.
