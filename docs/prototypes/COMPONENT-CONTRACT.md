# Recon capture components

This is a **proposed static design contract for later Android implementation**, paired with the 1440 × 7360 canonical `recon-capture-design-system.svg`. It is not evidence that the existing Kotlin implements these states. Mode symbols now use the `modeStrip*` names; all generated consumers are rebuilt from shared definitions.

## Geometry and queue families

All interactive specimens are at 1:1 dp-like units. Only camera-scene comparisons are reduced and labeled. Never scale a queue, font or touch target to fit a narrower window. Toolbar glyph boxes are 24dp inside allocated 48 × 48dp targets. Mode uses solid white bold text for selection and gray medium-weight text for other choices in a 250 × 40dp row, with three independent 83⅓ × 48dp targets. Zoom uses a 28dp selected circle within a 32dp-thick dark surface, 12sp text matching modality labels, independent 48 × 48dp targets and 48dp center pitch. Thumbnail media markers are decorative and are not separate controls.

| Component | Contract |
| --- | --- |
| Shutter, every mode | 80 × 80; white ring radius 38.5 and stroke 3, giving exact outer diameter 80 |
| Photo / video idle / voice idle center | 64dp white / 42dp red / 58dp white |
| Recording Stop | 28 × 28 red square, 6dp corners; white ring unchanged; remains enabled |
| Voice idle microphone | Proposed 32dp glyph box; native uses 24dp; retain official proportions |
| Full queue | 360 × 72; thumbnails x59, 121, 183, 245, y8; all 56 × 56 |
| Full edge targets | x0 and x312, y12; each 48 × 48; centers x24 and x336; 6 × 28 handles x21 and x333, y22 |
| Full split | Thumbnails x50, 112, 192, 254; expanded gap x168–192; divider x178, width 4 |
| Compact queue | `queueCompact`, 312 × 72; thumbnails x66, 128, 190, y8; all 56 × 56 |
| Compact edge targets | x0 and x264, y12; each 48 × 48; centers x24 and x288; 6 × 28 handles x21 and x285, y22 |
| Compact split | `queueCompactSplit`; thumbnails x57, 119, 199; gap x175–199; divider x185, width 4 |
| Ordinary / expanded gap | 6dp / 24dp; divider remains centered; outer edge targets never overlap thumbnails |
| Queue clearance | 72dp strip + 48dp downward travel + actual bottom navigation safe area; 24dp is the illustrated safe area |

For the 360dp compact screen, place the 312dp queue at x24 and y=`height − 144`: this leaves 48dp travel and 24dp illustrative bottom safety. Actual system gesture insets can require more room. Queue starts and drag corridors must remain inside the usable region; a component does not certify system-inset safety.

Controls may overlay the preview. The revised cover composition uses a full-width 360 × 480 preview at y72, with controls over a bottom contrast gradient; this contract does not require all controls to sit outside camera content. Overlay controls and queue gestures have input priority over preview focus, pinch and mode swipe wherever their regions intersect. Preserve the queue's 72dp strip, 48dp travel corridor and 24dp illustrative bottom safety, and keep overlay controls legible against changing camera content. A control gesture must not also operate the preview underneath.

Both queues have **no background bar**. Compact Commit/Discard variants replace only the initiating handle with the corresponding colored swiper, retain the opposite handle, and fade inward in the direction of travel. Full variants retain their larger fade regions but use the same disjoint edge targets and centered glyph slots. The compact split has a 6dp first gap and a 24dp second gap; all three items remain fully visible.

Three visible items are a viewport, not a queue limit. Scroll the thumbnail tray horizontally for additional items; keep edge targets fixed and chronology left-to-right. The sheet's “3 of 5 / scroll” caption is an external explanation, not a new mandatory product badge. Empty queues keep subdued handles with no enabled queue actions; a single item is centered at x128 in the compact family.

## Mode and zoom placement and meaning

Keep VIDEO–PHOTO–VOICE horizontal, all three visible, centered in the top bar in every layout, including side docks and VOICE. Mode selection is less frequent; idle preview swipe is the quick path. Selection uses solid white text at weight 700; unselected labels use gray text at weight 500. No underline, dash or selected pill is drawn. Locked selection retains bold weight with its existing disabled-state colors. The 250 × 40dp row starts at x=`window width / 2 − 125`, y28; its three equal targets span y24–72. The design-system VIDEO and VOICE recording compositions likewise keep their locked mode rows at the top. Preserve shutter and queue anchors across mode and zoom changes; hiding zoom for VOICE must not shift them.

Zoom is horizontal directly above the shutter on compact/bottom layouts and on side shells with wide frames: its symbol viewport starts 52dp above the shutter top, with the visible surface inset 4dp vertically. For side shells with tall frames, place a vertical rail opposite the shutter dock, centered on the fitted preview. Text stays upright; zoom values ascend bottom-to-top (0.5, 1×, 2, 5 in the illustrated fixture). Move the rail to the right for a left-hand shutter dock. This rule depends on shell and upright frame shape, not window orientation alone. Four-choice visible surfaces are 176 × 32dp horizontally or 32 × 176dp vertically, within unscaled 192 × 40 / 40 × 192 symbol viewports. The 48 × 48dp targets touch at boundaries but never overlap. A single-choice surface is 32dp across with the same 28dp selected circle and 48dp target. All zoom labels, including continuous values, use 12sp; use text adaptation/expanded controls at large font settings rather than shrinking touch targets.

Choices come from active-camera capabilities and supported zoom range, not a fixed set of optical lenses or a universal rear/front rule. The front single-preset 1× fixture assumes continuous zoom exists; hide the entire control, hit regions and semantics if no adjustment is available. Continuous zoom displays the actual value: the 1.7× specimen replaces the 1× slot, without marking a preset falsely active. Tapping that current-value slot resets to 1×; semantic output must convey “Current zoom 1.7×; reset to 1×” and expose the reset action, rather than promising selection of 1.7×.

Read exact screen bounds from `layout-geometry.json`, generated by `layout-screens.mjs`. Tall-frame right-dock PHOTO previews are 456 × 608 at (96,86) for Fold8 wide and (96,158) for Ultra wide; their shutter tops remain y484/y628 and queue tops y576/y720. The left-hand fixture preview is (408,86), 456 × 608, with zoom targets at x888; right-hand zoom targets are at x24. The near-square tall-frame side fixture preview is (96,270), 360 × 480. Bottom-dock tall-window PHOTO previews are 672 × 504 at (24,118) for Fold8 and 762.6667 × 572 at (50.6667,84) for Ultra; both retain shutter y724, queue y816 and horizontal zoom targets at y668. The tall-window/tall-frame bottom-dock exception fits a 429 × 572 preview at (145.5,84). These are illustrative layout coordinates, not measured device metadata.

Where a compact preview extends behind the header, mode labels sit over a 72% black header scrim spanning y24–72. This keeps inactive `#BDBDBD` labels above 4.5:1 even over a white camera frame; do not judge contrast only against the illustrated test scene. Zoom has its own 72% black surface.

The supplied Samsung cover and iPhone cover/traditional references make zoom visually secondary to capture modes: compact numerals and small circular highlights. Their image pixels do not establish dp values. Recon therefore uses the same 12sp label size as its modes, reduces visual mass (220 × 40 to 176 × 32), and preserves independent 48dp targets. The same tokens apply to phone, cover and inner layouts; do not downscale the whole component on short screens.

## Input, release and cancellation

The initial **visible** zone owns a contact sequence until completion or cancellation. Thumbnail starts cannot be stolen by a divider lane or edge target. Do not expand a 6dp gap into neighboring thumbnail hit regions. One queue mutation at a time. Horizontal tray scrolling, downward deletion, long-press reorder and edge swipes must arbitrate before claiming the gesture. Once two pointers participate in preview zoom, the sequence cannot also focus, change mode or mutate the queue.

| Action | Proposed completion | Cancellation / reversal |
| --- | --- | --- |
| Save queue: left target → right | Release after at least **96dp net inward displacement**, once | Below 96dp restores; velocity alone does not execute |
| Discard queue: right target → left | Same deliberate 96dp gate; then offer Undo | Same gate and restoration; no velocity-only destruction |
| Remove one item: thumbnail ↓ | Release only when net downward travel is **greater than 40dp** | Returning to ≤40dp disarms; pointer cancellation always restores |
| Split / join: visible gap ↓ / ↑ | Release after at least 24dp in the applicable direction; haptic on arming | Below threshold or cancellation restores prior split state |
| Reorder: thumbnail hold + horizontal drag | Release into a destination computed from actual item/gap geometry | Cancellation restores original order and split state |
| Preview swipe, idle | Previous/next mode in VIDEO–PHOTO–VOICE order; intended distance or same-direction velocity gate | Below gate restores; interruption never changes mode |

The 96dp queue gate is a bounded **prototype proposal**, not a tuned hardware result. It is independent of window width and must be evaluated later. Distance is measured from the original contact, including reversal. An armed indicator clears when displacement falls below its gate. Ordinary pointer cancellation, an extra finger, loss of gesture ownership, lifecycle/window interruption, or departure from the safe corridor restores the original queue **even after arming**. Mutation happens only on an intentional valid release, once. Do not route cancellation through completion.

For deletion, clamp the rendered thumbnail displacement at 48dp; track the contact safely and cancel before it leaves the usable corridor. At 20dp the thumbnail is at y28–84, alpha .75, with glow peak .35. At 48dp it is at y56–112, alpha .40, with glow peak .70. The 18dp Gaussian glow ends at usable y120, above the illustrated y120–144 navigation area. Its horizontal sigma is 56dp under the held item. Progress is `clamp(dragY / 40, 0, 1)`; alpha is `clamp(1 − dragY / 80, .3, 1)`. The ghost slot, blue boundaries and arrows are annotations. The complete thumbnail and contact path must be visible; finger clearance alone does not establish feedback clearance.

Reorder lifts the held item to 1.1×. The sheet shows the source outline and destination marker for review; temporary overlap with a neighboring thumbnail is expected while dragging. Do not calculate destinations using an invariant 62dp pitch across expanded gaps. Reordering may cross a split: splits remain boundaries between ordinal slots, while the item moves; do not silently merge or create splits. Cancel changes neither ordering nor boundaries. Overflow reorder can use bounded edge auto-scroll in the tray, without activating save/discard edge gestures. Move earlier/later remains the precise alternative.

## Accessible direct actions

Tapping a thumbnail opens the familiar `queueActionMenu` specimen. Its title identifies media type and position. All six action rows are 312 × 48dp: Remove item, Move earlier, Move later, Split after, Save queue, Discard queue. Replace Split after with Remove split where a following boundary exists; omit that row on the final item. Disable Move earlier/later at their respective ends. Save/Discard apply to the whole queue; discard offers Undo. Existing split boundaries partition saved bundles. Close the menu after an action and restore sensible focus; outside tap or Back closes it without mutation.

Expose the same commands as semantic/custom actions for TalkBack, Switch Access and Voice Access; no drag-only operation is required. Expose selected modality, zoom value, microphone state and unavailable controls accurately. Thumbnail descriptions must say Photo, Video or Voice rather than describing all media as photos. Decorative glyphs, waveform bars, tutorial illustrations and timer ticks must not create redundant focus stops or continual announcements.

This menu and the split semantic actions are **new design requirements**. Narrow gap gestures remain optional shortcuts and do not count as accessible 48dp tap targets. Relevant Android guidance: [touch targets and defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults), [semantics and custom actions](https://developer.android.com/develop/ui/compose/accessibility/semantics).

## Recording and other control states

VIDEO shows a locked but readable selected mode, live preview, elapsed-time pill, audio status and enabled Stop. PHOTO has flash/lens controls; VIDEO has mic/lens controls; VOICE has neither. While recording, mode, queue, mic and lens are locked. Lock includes pointer detectors and accessibility actions, not just opacity; do not animate a successful exit for a rejected mutation. Camera zoom remains available during video recording, consistent with the native path; hide it and remove hit/semantic regions for VOICE. Unavailable hardware presets are omitted, not shown as working controls.

VOICE idle shows a flat waveform and “Tap shutter to record voice.” VOICE recording shows symmetric red amplitude bars, a red live dot, “Recording…” and elapsed time. Video's pill has audio-on, muted and long-duration examples. Preserve content width for growing elapsed time instead of scaling the text. Idle busy/capturing/rebinding shutters are disabled; active Stop remains enabled. Disabled specimens are visual references; implementation must expose disabled state and prevent activation.

Stop has a solid red square in this static contract; an optional native-style pulse may be retained only if its dimmest endpoint remains legible. Do not rely on animation or color alone to identify recording. Rotation continues the same take; fold/unfold finalizes once. These remain future implementation requirements, not runtime verification.

## Banners and large text

The 328 × 48 examples are **compact minimums**, not fixed heights or universal widths. Use the allocated overlay region minus its insets. Undo and Error action targets are respectively 72 × 48 and 64 × 48; the two-line example uses 72 × 48. Wrapped text has its own reserved area and never extends beneath the action. The standalone 2× text specimen stacks a 144 × 48 action below the message; text and targets retain actual sizes. It is a component reference, not proof that this banner fits every screen. Saved identifiers/ranges wrap as illustrated. Longer/localized content may grow further; preserve the complete message through wrapping or an accessible detail presentation, not silent clipping of actionable information.

The short-cover layout restores the 328 × 160 large-text error at (16,92), including its stacked 144 × 48dp action. It ends at y252; horizontal zoom targets now begin at y284, so both fit with 32dp clearance. Shutter y340 and queue y432 remain anchored. The separate 72dp multiline component specimen remains available, but is not the short-cover large-text fixture. This static composition does not establish runtime font-scaling acceptance. For larger or localized content that cannot fit, retain an alternate accessible presentation with complete text and persistently reachable actions while keeping Stop accessible.

An overlay host must keep the shutter/Stop and the queue's safe travel corridor accessible. Grow upward within a reserved safe host without moving capture controls. If large content cannot fit, use a bounded scrollable message region with its action persistently reachable; do not cover Stop or steal an active queue gesture. Device layout owns the available host geometry.

Only one feedback overlay is visible: **Error > Undo > Saved**. Retain a pending Undo while an error occupies the host; pause its timeout and resume when it becomes reachable. Announce a new message once through appropriate live-region semantics. Honor the user's accessibility-recommended timeout for actionable feedback; do not treat native's existing three-second Undo as proof of accessibility acceptance. Covered banners cannot remain invisible focus targets. Saved feedback does not need a dismiss action.

## Tutorial

The first/middle/final specimens reuse `tutorialShell`, with all six progress dots, Skip, conditional Back and final Got it. The order is Mode, Save queue, Discard queue, Delete one, Reorder, Split/join. Fresh installs see all six; returning users can see the relevant unseen subset with pagination derived from that subset. Descriptions refer to mixed media as items rather than only photos, and queue edge instructions identify the actual start handle.

The 82% black scrim covers the available window; the rounded specimen boundary is a review frame, not a requirement for a small modal tutorial. Preview demos anchor to the preview; queue demos anchor to the queue. Demo gestures cannot mutate real queued media. Keep the text/navigation area scrollable at large text, with at least 48dp navigation targets and system insets respected. Block underlying pointer interactions **and** accessibility focus; move focus into the tutorial, expose the current step, then restore focus when dismissed. Skip remains reachable throughout. The separate visible gesture legend preserves centered two-arrow swipe/hold markers, actual handle edge swipes, focus, pinch, gap directions and deletion instructions.

## Icons, colors and framing

`iconLens` now uses the official [Google Material Icons Filled Cameraswitch](https://github.com/google/material-design-icons/blob/master/src/device/cameraswitch/materialicons/24px.svg), matching `Icons.Filled.Cameraswitch`. Existing Apache-2.0 licensing applies. Toolbar normalization to 24dp from native 26dp and the 32dp voice-shutter mic are explicit proposed adjustments. Queue video uses a 20dp play glyph and duration badge; queue voice uses a 28dp mic and duration badge, matching native component sizing.

Semantic accent colors stay unchanged. White on timer red `#D32F2F` measures 4.98:1; white on commit green `#2E7D32` measures 5.13:1. Explanatory notes now use `#AAA`, above 7:1 on the sheet panels. Discard amber `#B26A00` may carry a white icon but must not be reused behind small white text (4.24:1). A solid white ring and square/circle shape distinguish controls independently of color. Text uses geometric-precision rendering; portability is assessed separately and this setting is not proof of identical rasterization. [Android contrast guidance](https://support.google.com/accessibility/android/answer/7158390?hl=en).

FIT_CENTER fits the **delivered preview stream** without additional fill cropping. It does not prove saved photo/video field-of-view agreement. Use the upright selected output frame independently of window shape; preserve camera/display/crop/rotation/mirror coordinate distinctions. Generic scenes do not certify front-camera mirroring or per-device orientation mapping. Read `../foldable-camera-orientation-ux-review.md` for the camera evidence boundary.

## Known native gaps and validation boundary

The source audit found cancellation routed to queue completion; width-dependent/velocity-only queue release; divider hit regions overlapping thumbnails; no split accessibility actions; recording queue input/exit feedback still active; missing busy guard on reorder; and different tutorial/banner/state geometry. Native voice and video already use white shutter rings. This document specifies the desired behavior and does not claim those implementation gaps were fixed.

Regenerate standalone definitions and review artifacts after the shared SVG stabilizes, then request independent final review. Static XML/reference, geometry, contrast and render checks cannot establish native hit testing, TalkBack focus, font scaling, physical reach, system gesture arbitration, camera-output agreement or recording continuity. Remaining hardware/runtime checks belong to later implementation; they do not prevent correction of the obvious static component issues now.
