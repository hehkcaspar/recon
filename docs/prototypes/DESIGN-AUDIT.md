# Android capture design audit

2026-11-01. Covers the static SVG capture design and developer contract. This is not acceptance of the future Android implementation. Current geometry is generated from `layout-screens.mjs`; current pixel evidence and hashes are in `review/manifest.json`.

## Completion evidence

| Requirement | Current evidence |
| --- | --- |
| Five primary deliverables, self-contained and shared components | Design system, phone, cover, Fold8 inner and Ultra inner SVGs; standalone bundler resolves shared symbols/fonts into each file. Nine total sheets including annotations, ratio/layout checks and recording states. 1,137 references resolve. |
| Native Android controls, consistent geometry | Material Filled vectors and Roboto; standard/dense shutter families, fixed 48dp minimum targets, explicit sizes and shared queue/banner geometry. Entire component sheet independently inspected. |
| Phone and short cover remain usable | Full-frame fitted previews with overlay controls, bottom anchoring, 64dp dense shutter/48dp thumbnails; 360×480/576/640/800 and larger-inset fixtures. No instructional panel inside the app viewport. |
| Inner orientation and aspect ratios | Rear/front, wide/tall windows; corrected tall camera frame in wide unfolded window; 4:3, 16:9, square and variable output examples. Geometry checks uniform aspect preservation. Device/camera metadata assumptions remain labeled as fixtures. |
| Choose layout for content and grip | Side dock retained where useful; near-square tall-frame and wide 16:9 examples gain about54%/44% preview area with bottom docks. Left-hand alternative and separating-hinge example included. |
| Mode and zoom design | Modes remain top, bold white selection without underline; `.5` and12sp zoom labels. Common1× target stays anchored across rear/front capability changes. Square phone frame balanced without moving capture controls. |
| Focus scope | Transient complete white ring; no focus lock, exposure slider, sun or crosshairs. |
| Queue and gesture states | Idle, empty, single, split, scroll start/middle/end, commit/discard, delete pending/armed/cancelled and reorder. Colored swiper replaces its initiating handle; split occupies a24dp gap. Eighteen overflow states validate fixed edge priority and endpoint access. |
| Gesture clarity and alternatives | Exact start zones, axis ownership, release/cancel/reversal behavior, preview64dp/300dp/s rules, non-wrapping mode order, queue96dp and proposed deletion>24dp gates. Actual-handle tutorial cues, gap arrows and selected-item direct actions. |
| Complete recording/voice composition | Eleven full-window states share the idle layout generator. Shutter/queue anchors verified across modes; recording locks navigation/mutating controls, keeps zoom/Stop available; VOICE removes camera UI. Timer and recoverable-error hosts do not collide. |
| Feedback, tutorial and accessibility design | Equal-width banner families, growing/stacked text examples, correct prior-capture error wording, six-step tutorial and direct/semantic action contract. Targets/status bounds checked; native font scaling and accessibility still need implementation tests. |
| Independent review and portability | Bohr reviewed all component sections and corrected direct/alternate crops. Beauvoir reviewed all original screens and all new states. Both report PASS. All9 isolated-file renders equal HTTP;8 strict HTML-image passes,1 documented exact-artifact antialiasing exception. Phone annotation matches all288,000 clean-phone pixels. |

The completed static audit contains55 screen fixtures. Reviewers' IDs, findings and final disposition are recorded in `REVIEW.md`. Historical review entries describe older artifacts; the current component contract and generated geometry take precedence.

## Deferred implementation validation

These tasks require native behavior or hardware, not another speculative SVG rearrangement:

- Actual window/cutout/navigation/taskbar insets, small-window fallback and horizontal tabletop panes.
- Camera buffer/crop/rotation metadata, front mirroring and preview/output agreement across cameras and displays.
- Continuous recording on physical rotation; exactly-once finalization on fold/unfold.
- False-trigger rates and release headroom for deletion/queue gestures; cancellation, edge ownership, reorder auto-scroll and interrupted input.
- Held-device left/right/two-hand reach, including side-dock choice and switching hysteresis.
- TalkBack, Switch Access, localization, large text and accessible feedback timeouts.
- Additional SVG engines beyond the recorded Chrome contexts; do not describe the documented antialiasing exception as universal pixel identity.

No unresolved product choice blocks the revised static references. Left-hand preference presentation, sensor-specific mirroring behavior and final gesture tuning should follow the implementation/device evidence above. Focus locking, exposure adjustment, iOS and non-capture product screens remain outside this round's scope.
