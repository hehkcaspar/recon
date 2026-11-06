package com.example.bundlecam.ui.capture

import android.view.Surface
import com.example.bundlecam.data.camera.LensFacing
import com.example.bundlecam.data.camera.StreamGeometry
import com.example.bundlecam.ui.capture.window.HingeFeature
import com.example.bundlecam.ui.capture.window.HingeOrientation
import com.example.bundlecam.ui.capture.window.InsetsPx
import com.example.bundlecam.ui.capture.window.IntRectPx
import com.example.bundlecam.ui.capture.window.Posture
import com.example.bundlecam.ui.capture.window.SurfaceRole
import com.example.bundlecam.ui.capture.window.WindowObservation
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Primary axis of the media queue.
 * - [Vertical]: Upright foldable inner C composition (side rail).
 *   Bottom handle swipes UP to save/commit; top handle swipes DOWN to discard;
 *   item drags horizontally outward to delete; long-press drags vertically to reorder;
 *   gap drags horizontally to split/remove divider.
 * - [Horizontal]: CW90 foldable inner C composition (below preview) and phone/cover bottom dock.
 *   Left handle swipes RIGHT to save/commit; right handle swipes LEFT to discard;
 *   item drags downward to delete; long-press drags horizontally to reorder;
 *   gap drags vertically to split/remove divider.
 */
enum class QueueAxis {
    Horizontal,
    Vertical,
}

enum class CapturePose {
    InnerUpright,
    InnerCW90,
    CompactBottom,
    AdaptiveSide,
    AdaptiveBottom,
}

enum class DockSide {
    Bottom,
    Right,
    Left,
}

enum class QueueProfileToken(
    val profileName: String,
    val mediaDp: Float,
    val edgeTargetDp: Float,
    val handlerWidthDp: Float,
    val handlerLengthDp: Float,
    val stripCrossDp: Float,
    val verticalPaddingDp: Float,
    val gapDp: Float,
    val shutterSizeDp: Float,
    val zoomToShutterGapDp: Float,
    val shutterToQueueGapDp: Float,
) {
    Standard56(
        profileName = "standard56",
        mediaDp = 56f,
        edgeTargetDp = 56f,
        handlerWidthDp = 6f,
        handlerLengthDp = 36f,
        stripCrossDp = 72f,
        verticalPaddingDp = 8f,
        gapDp = 6f,
        shutterSizeDp = 80f,
        zoomToShutterGapDp = 12f,
        shutterToQueueGapDp = 12f,
    ),
    Compact48(
        profileName = "compact48",
        mediaDp = 48f,
        edgeTargetDp = 48f,
        handlerWidthDp = 6f,
        handlerLengthDp = 28f,
        stripCrossDp = 64f,
        verticalPaddingDp = 8f,
        gapDp = 6f,
        shutterSizeDp = 64f,
        zoomToShutterGapDp = 8f,
        shutterToQueueGapDp = 8f,
    ),
}

/**
 * Floating-point rectangle in window-pixel coordinates.
 * At density = 1f, 1px == 1dp, allowing exact verification against `foldable-c-geometry-source.json`.
 */
data class RectPx(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f

    fun toIntRectPx(): IntRectPx = IntRectPx(
        left = x.roundToInt(),
        top = y.roundToInt(),
        right = (x + width).roundToInt(),
        bottom = (y + height).roundToInt(),
    )

    fun overlaps(other: RectPx, epsilon: Float = 0.001f): Boolean =
        x < other.right - epsilon &&
            other.x < right - epsilon &&
            y < other.bottom - epsilon &&
            other.y < bottom - epsilon
}

data class QueueCapacitySpec(
    val profile: QueueProfileToken,
    val availableAxisDp: Float,
    val startMarginDp: Float,
    val endMarginDp: Float,
    val laneLengthDp: Float,
    val visibleCount: Int,
    val endpointMediaGapDp: Float,
    val itemStartDp: Float,
)

data class ControlGroupGeometry(
    val bounds: RectPx,
    val zoomBounds: RectPx,
    val auxBounds: RectPx,
    val shutterBounds: RectPx,
    val lensBounds: RectPx,
    val isSingleZoom: Boolean,
)

data class CaptureGeometryInput(
    val observation: WindowObservation,
    val modality: Modality = Modality.PHOTO,
    val lensFacing: LensFacing = LensFacing.Back,
    val streamGeometry: StreamGeometry? = null,
    val hasMultipleZoomPresets: Boolean = true,
    val settledDockSide: DockSide? = null,
    val handedness: DockSide = DockSide.Right,
)

data class CaptureGeometry(
    val windowWidthPx: Float,
    val windowHeightPx: Float,
    val density: Float,
    val usablePaneBounds: RectPx,
    val pose: CapturePose,
    val dockSide: DockSide,
    val profile: QueueProfileToken,
    val queueAxis: QueueAxis,
    val previewBounds: RectPx,
    val settingsButtonBounds: RectPx,
    val settingsVisualBounds: RectPx,
    val bundlesButtonBounds: RectPx,
    val bundlesVisualBounds: RectPx,
    val modeBarBounds: RectPx,
    val controlGroup: ControlGroupGeometry,
    val queueBounds: RectPx,
    val queueStripBounds: RectPx,
    val queueCapacity: QueueCapacitySpec,
    val gestureRunwayBounds: RectPx,
    val videoTimerBounds: RectPx,
    val voiceOverlayBounds: RectPx,
    val feedbackBounds: RectPx,
    val controlsOverlayPreview: Boolean,
    val showTopHeaderScrim: Boolean,
    val showLeftUtilityScrim: Boolean = false,
)

object DesignCTokens {
    const val UTILITY_STRIP_DP = 80f
    const val UTILITY_VISUAL_INSET_DP = 16f
    const val UTILITY_HIT_INSET_DP = 24f
    const val TOUCH_TARGET_DP = 48f
    const val MIN_PRIMARY_BAND_DP = 216f
    const val CONTROL_GROUP_WIDTH_DP = 192f
    const val CONTROL_GROUP_HEIGHT_DP = 132f
    const val ZOOM_HEIGHT_DP = 40f
    const val SINGLE_ZOOM_WIDTH_DP = 40f
    const val ZOOM_TO_SHUTTER_OFFSET_DP = 52f // 40dp zoom + 12dp gap
    const val MODE_BAR_MAX_WIDTH_DP = 250f
    const val MODE_BAR_HEIGHT_DP = 40f
    const val UPRIGHT_MODE_TOP_DP = 28f
    const val CW90_MODE_TOP_DP = 20f
    const val DEFAULT_INNER_QUEUE_SPAN_DP = 428f
    const val TIMER_WIDTH_DP = 150f
    const val TIMER_HEIGHT_DP = 32f
    const val FEEDBACK_MAX_WIDTH_DP = 328f
    const val FEEDBACK_MIN_HEIGHT_DP = 48f
}

object CaptureGeometryCalculator {

    private const val HYSTERESIS_RATIO = 1.12f

    /**
     * Pure queue capacity derivation matching `docs/prototypes/queue-geometry.mjs`:
     * `L = max(0, A - M_s - M_e)`
     * `N = max(0, floor((L - 2*E + G) / (T + G)))`
     * `P = (L - 2*E - N*T - (N - 1)*G) / 2`
     */
    fun deriveQueueCapacity(
        availableAxisDp: Float,
        startMarginDp: Float = 0f,
        endMarginDp: Float = 0f,
        profile: QueueProfileToken = QueueProfileToken.Standard56,
        gapOverrideDp: Float? = null,
    ): QueueCapacitySpec {
        val gap = gapOverrideDp ?: profile.gapDp
        val laneLength = (availableAxisDp - startMarginDp - endMarginDp).coerceAtLeast(0f)
        val rawN = floor(
            (laneLength - 2f * profile.edgeTargetDp + gap) / (profile.mediaDp + gap),
        ).toInt()
        val visibleCount = rawN.coerceAtLeast(0)
        val endpointMediaGap = if (visibleCount == 0) {
            0f
        } else {
            (laneLength - 2f * profile.edgeTargetDp -
                visibleCount * profile.mediaDp -
                (visibleCount - 1) * gap) / 2f
        }
        val itemStart = profile.edgeTargetDp + endpointMediaGap
        return QueueCapacitySpec(
            profile = profile,
            availableAxisDp = availableAxisDp,
            startMarginDp = startMarginDp,
            endMarginDp = endMarginDp,
            laneLengthDp = laneLength,
            visibleCount = visibleCount,
            endpointMediaGapDp = endpointMediaGap,
            itemStartDp = itemStart,
        )
    }

    /**
     * Universal Recon intrusion law along the active layout axis:
     * Given the start of the primary control zone ([primaryZoneStartDp]), the uncropped
     * preview span ([previewSpanDp]), and the end of the secondary utility zone ([utilityEndDp]),
     * places the preview at `(primaryZoneStartDp - previewSpanDp).coerceIn(0f, utilityEndDp)`:
     * 1. Ample space (`>= utilityEndDp`): neither utility nor primary zone is intruded.
     * 2. Moderate space (`0 .. utilityEndDp`): preview shifts into the secondary utility
     *    zone FIRST so the primary control zone remains 100% in un-intruded black space.
     * 3. Tight space (`< 0`): preview reaches the outer edge (`0f`, consuming the utility
     *    zone first) and only the remaining excess intrudes into the primary control zone.
     */
    fun computePreviewStartAlongLayoutAxis(
        primaryZoneStartDp: Float,
        previewSpanDp: Float,
        utilityEndDp: Float,
    ): Float = (primaryZoneStartDp - previewSpanDp).coerceIn(0f, utilityEndDp)

    /**
     * Compute the complete [CaptureGeometry] in window-pixel coordinates from measured
     * window observation, camera stream metadata, and active modality.
     */
    fun compute(input: CaptureGeometryInput): CaptureGeometry {
        val obs = input.observation
        val density = obs.density.takeIf { it > 0f } ?: 1f

        // 1. Resolve usable pane when a separating or occluding hinge is present.
        val usablePane = resolveUsablePane(obs, input.handedness)
        val paneWidthDp = usablePane.width / density
        val paneHeightDp = usablePane.height / density

        val isSeparatingHinge = obs.hinge?.let { it.isSeparating || it.isOccluding } == true
        val isDesignCInner = !isSeparatingHinge && isInnerDesignCEligible(obs, paneWidthDp, paneHeightDp)

        if (isDesignCInner) {
            return computeInnerDesignC(input, usablePane, density)
        }
        return computeResponsiveShell(input, usablePane, density)
    }

    private fun isInnerDesignCEligible(
        obs: WindowObservation,
        paneWidthDp: Float,
        paneHeightDp: Float,
    ): Boolean {
        val minDim = minOf(paneWidthDp, paneHeightDp)
        val maxDim = maxOf(paneWidthDp, paneHeightDp)
        if (obs.surfaceRole == SurfaceRole.Cover || obs.surfaceRole == SurfaceRole.Regular) {
            return false
        }
        // Confirmed Inner surface or unfolded inner-class bounds (>= 680dp short edge, >= 840dp long edge)
        if (obs.surfaceRole == SurfaceRole.Inner && minDim >= 600f && maxDim >= 700f) {
            return true
        }
        return !obs.isInMultiWindowMode && minDim >= 680f && maxDim >= 840f
    }

    /**
     * Design C inner foldable composition:
     * - Upright (`width >= height` or `ROTATION_0`/`ROTATION_180` on wide chassis):
     *   80dp left utility strip, stable preview at `x = 80dp`, horizontal mode strip at top of
     *   right rail, vertical queue (`QueueAxis.Vertical`) in the blank side rail sharing the
     *   shutter's x-centerline, and lower-right main control group.
     * - CW90 (`height > width` or `ROTATION_90`/`ROTATION_270` quarter turn):
     *   80dp top utility/mode strip, stable rotated preview at `y = 80dp`, horizontal queue
     *   (`QueueAxis.Horizontal`) in the blank band below the preview on the left, visually
     *   y-centered with the shutter, and ~1:1:1 balanced outer horizontal margins.
     */
    private fun isDesignCUpright(
        input: CaptureGeometryInput,
        wDp: Float,
        hDp: Float,
    ): Boolean {
        val obs = input.observation
        // 1. Explicit display rotation: a 90° or 270° turn from the natural unfolded
        //    orientation enters the CW90 composition.
        if (obs.displayRotation == Surface.ROTATION_90 || obs.displayRotation == Surface.ROTATION_270) {
            return false
        }
        // 2. Hardware hinge orientation when present on the unfolded inner display:
        //    a vertical crease is upright; a horizontal crease is CW90.
        if (obs.hinge != null) {
            return obs.hinge.orientation == HingeOrientation.Vertical
        }
        // 3. Delivered camera stream orientation at ROTATION_0 / ROTATION_180:
        //    a portrait (tall) upright camera stream on the unfolded inner display uses
        //    the upright side-rail composition even on near-square panels (e.g. 2076×2152).
        if (input.streamGeometry != null) {
            return input.streamGeometry.uprightAspectRatio <= 1.05f
        }
        return wDp >= hDp
    }

    private fun computeInnerDesignC(
        input: CaptureGeometryInput,
        pane: RectPx,
        density: Float,
    ): CaptureGeometry {
        val wDp = pane.width / density
        val hDp = pane.height / density
        val isUpright = isDesignCUpright(input, wDp, hDp)
        val profile = QueueProfileToken.Standard56
        val singleZoom = input.lensFacing == LensFacing.Front || !input.hasMultipleZoomPresets

        // Determine upright camera frame aspect ratio from delivered CameraX stream or
        // the active modality's target ratio when no stream is bound yet.
        val defaultUprightRatio = if (input.modality == Modality.VIDEO) 9f / 16f else 3f / 4f
        val rawStreamRatio = input.streamGeometry?.uprightAspectRatio ?: defaultUprightRatio

        if (isUpright) {
            val uprightRatio = if (rawStreamRatio > 1f) 1f / rawStreamRatio else rawStreamRatio
            val utilityWidthDp = DesignCTokens.UTILITY_STRIP_DP
            // Step 1: Maximize the uncropped preview across the full window height
            // (y = 0 .. hDp, touching top and bottom screen edges).
            val fhDp = min(hDp, wDp / uprightRatio).coerceAtLeast(1f)
            val fwDp = fhDp * uprightRatio

            // Step 2: Intrude into the 80dp utility zone FIRST before intruding into the
            // primary control rail on the right.
            val previewX = computePreviewStartAlongLayoutAxis(
                primaryZoneStartDp = wDp - DesignCTokens.MIN_PRIMARY_BAND_DP,
                previewSpanDp = fwDp,
                utilityEndDp = utilityWidthDp,
            )
            val previewY = (hDp - fhDp) / 2f
            val previewRect = dpRect(pane, density, previewX, previewY, fwDp, fhDp)

            val blankRightWidthDp = (wDp - previewX - fwDp).coerceAtLeast(0f)
            val primaryWidthDp = maxOf(blankRightWidthDp, DesignCTokens.CONTROL_GROUP_WIDTH_DP)
            val railCenterDp = wDp - primaryWidthDp / 2f

            // Utility buttons in 80dp left strip (visual at 16dp, 48dp hit target at 24dp)
            val bundlesVisual = dpRect(
                pane,
                density,
                DesignCTokens.UTILITY_VISUAL_INSET_DP,
                DesignCTokens.UTILITY_VISUAL_INSET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
            )
            val bundlesHit = dpRect(
                pane,
                density,
                DesignCTokens.UTILITY_HIT_INSET_DP,
                DesignCTokens.UTILITY_HIT_INSET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
            )
            val settingsVisual = dpRect(
                pane,
                density,
                DesignCTokens.UTILITY_VISUAL_INSET_DP,
                hDp - DesignCTokens.UTILITY_VISUAL_INSET_DP - DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
            )
            val settingsHit = dpRect(
                pane,
                density,
                DesignCTokens.UTILITY_HIT_INSET_DP,
                hDp - DesignCTokens.UTILITY_HIT_INSET_DP - DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
            )

            // Mode strip at top of right rail: 250dp when it fits, else primaryWidth - 24dp (e.g. 208dp on Ultra PHOTO)
            val modeWidthDp = min(DesignCTokens.MODE_BAR_MAX_WIDTH_DP, primaryWidthDp - 24f).coerceAtLeast(144f)
            val modeX = railCenterDp - modeWidthDp / 2f
            val modeY = DesignCTokens.UPRIGHT_MODE_TOP_DP
            val modeBarBounds = dpRect(pane, density, modeX, modeY, modeWidthDp, DesignCTokens.MODE_BAR_HEIGHT_DP)
            val modeBottomDp = modeY + DesignCTokens.MODE_BAR_HEIGHT_DP

            // First-principles right-rail allocation below modeBar:
            // Standard 5-item vertical queue is 428dp and control group is 132dp (total 560dp).
            val defaultQueueSpan = DesignCTokens.DEFAULT_INNER_QUEUE_SPAN_DP
            val controlGroupH = DesignCTokens.CONTROL_GROUP_HEIGHT_DP
            val railAvailHDp = (hDp - modeBottomDp).coerceAtLeast(320f)
            val freeVerticalSlackDp = railAvailHDp - (defaultQueueSpan + controlGroupH)
            val extraSlackAboveBaseline = (freeVerticalSlackDp - 92f).coerceAtLeast(0f)
            val topGapDp = if (freeVerticalSlackDp >= 92f) {
                24f + extraSlackAboveBaseline * (11f / 18f)
            } else {
                16f
            }
            val bottomMarginDp = if (freeVerticalSlackDp >= 92f) {
                52f + extraSlackAboveBaseline * (5f / 18f)
            } else {
                24f
            }
            val queueTopDp = modeBottomDp + topGapDp
            val controlTopDp = (hDp - bottomMarginDp - controlGroupH).coerceAtLeast(queueTopDp + 184f)
            val queueLaneHeightDp = defaultQueueSpan.coerceAtMost((controlTopDp - queueTopDp - 16f).coerceAtLeast(168f))
            val queueWidthDp = profile.mediaDp // 56dp target/tile width
            val queueX = railCenterDp - queueWidthDp / 2f
            val queueBounds = dpRect(pane, density, queueX, queueTopDp, queueWidthDp, queueLaneHeightDp)
            val queueStripBounds = dpRect(
                pane,
                density,
                railCenterDp - profile.stripCrossDp / 2f,
                queueTopDp,
                profile.stripCrossDp,
                queueLaneHeightDp,
            )

            val shutterTopDp = controlTopDp + DesignCTokens.ZOOM_TO_SHUTTER_OFFSET_DP
            val controlX = railCenterDp - DesignCTokens.CONTROL_GROUP_WIDTH_DP / 2f
            val shutterX = railCenterDp - profile.shutterSizeDp / 2f
            val auxX = controlX
            val auxY = shutterTopDp + (profile.shutterSizeDp - DesignCTokens.TOUCH_TARGET_DP) / 2f
            val lensX = auxX + 144f
            val zoomBounds = if (singleZoom) {
                dpRect(
                    pane,
                    density,
                    railCenterDp - DesignCTokens.SINGLE_ZOOM_WIDTH_DP / 2f,
                    controlTopDp,
                    DesignCTokens.SINGLE_ZOOM_WIDTH_DP,
                    DesignCTokens.ZOOM_HEIGHT_DP,
                )
            } else {
                dpRect(
                    pane,
                    density,
                    controlX,
                    controlTopDp,
                    DesignCTokens.CONTROL_GROUP_WIDTH_DP,
                    DesignCTokens.ZOOM_HEIGHT_DP,
                )
            }
            val controlGroup = ControlGroupGeometry(
                bounds = dpRect(
                    pane,
                    density,
                    controlX,
                    controlTopDp,
                    DesignCTokens.CONTROL_GROUP_WIDTH_DP,
                    controlGroupH,
                ),
                zoomBounds = zoomBounds,
                auxBounds = dpRect(pane, density, auxX, auxY, DesignCTokens.TOUCH_TARGET_DP, DesignCTokens.TOUCH_TARGET_DP),
                shutterBounds = dpRect(pane, density, shutterX, shutterTopDp, profile.shutterSizeDp, profile.shutterSizeDp),
                lensBounds = dpRect(pane, density, lensX, auxY, DesignCTokens.TOUCH_TARGET_DP, DesignCTokens.TOUCH_TARGET_DP),
                isSingleZoom = singleZoom,
            )

            val capacity = deriveQueueCapacity(
                availableAxisDp = queueLaneHeightDp,
                startMarginDp = 0f,
                endMarginDp = 0f,
                profile = profile,
            )
            val runwayBounds = dpRect(
                pane,
                density,
                queueX,
                queueTopDp,
                queueWidthDp + 32f,
                queueLaneHeightDp,
            )
            val timerBounds = dpRect(
                pane,
                density,
                previewX + fwDp / 2f - DesignCTokens.TIMER_WIDTH_DP / 2f,
                maxOf(84f, previewY + 12f),
                DesignCTokens.TIMER_WIDTH_DP,
                DesignCTokens.TIMER_HEIGHT_DP,
            )
            val voiceOverlayBounds = previewRect
            val feedbackBounds = dpRect(
                pane,
                density,
                previewX + (fwDp - DesignCTokens.FEEDBACK_MAX_WIDTH_DP).coerceAtLeast(0f) / 2f,
                maxOf(92f, previewY + 56f),
                DesignCTokens.FEEDBACK_MAX_WIDTH_DP.coerceAtMost(fwDp),
                DesignCTokens.FEEDBACK_MIN_HEIGHT_DP,
            )
            // Visible glyphs inside the 192dp control group are inset by 12dp (`168dp` visible span);
            // only enable the right-rail overlay scrim when the preview intrudes into the visible controls.
            val intrudesPrimaryRail = (previewX + fwDp) > (wDp - primaryWidthDp + 12f)
            val intrudesUtilityStrip = previewX < utilityWidthDp - 0.5f

            return CaptureGeometry(
                windowWidthPx = pane.width,
                windowHeightPx = pane.height,
                density = density,
                usablePaneBounds = pane,
                pose = CapturePose.InnerUpright,
                dockSide = DockSide.Right,
                profile = profile,
                queueAxis = QueueAxis.Vertical,
                previewBounds = previewRect,
                settingsButtonBounds = settingsHit,
                settingsVisualBounds = settingsVisual,
                bundlesButtonBounds = bundlesHit,
                bundlesVisualBounds = bundlesVisual,
                modeBarBounds = modeBarBounds,
                controlGroup = controlGroup,
                queueBounds = queueBounds,
                queueStripBounds = queueStripBounds,
                queueCapacity = capacity,
                gestureRunwayBounds = runwayBounds,
                videoTimerBounds = timerBounds,
                voiceOverlayBounds = voiceOverlayBounds,
                feedbackBounds = feedbackBounds,
                controlsOverlayPreview = intrudesPrimaryRail,
                showTopHeaderScrim = false,
                showLeftUtilityScrim = intrudesUtilityStrip,
            )
        } else {
            // CW90: 90° clockwise turn of the physical inner layout.
            val cw90Ratio = if (rawStreamRatio < 1f) 1f / rawStreamRatio else rawStreamRatio
            val utilityHeightDp = DesignCTokens.UTILITY_STRIP_DP
            // Step 1: Maximize the uncropped preview across the full window width
            // (x = 0 .. wDp, touching left and right screen edges).
            val fwDp = min(wDp, hDp * cw90Ratio).coerceAtLeast(1f)
            val fhDp = fwDp / cw90Ratio

            // Step 2: Intrude into the top 80dp utility strip FIRST before intruding into
            // the bottom primary control band.
            val previewX = (wDp - fwDp) / 2f
            val previewY = computePreviewStartAlongLayoutAxis(
                primaryZoneStartDp = hDp - DesignCTokens.MIN_PRIMARY_BAND_DP,
                previewSpanDp = fhDp,
                utilityEndDp = utilityHeightDp,
            )
            val previewRect = dpRect(pane, density, previewX, previewY, fwDp, fhDp)

            // Top 80dp utility strip: Settings on top-left, Library on top-right, Mode centered at y=20
            val settingsVisual = dpRect(
                pane,
                density,
                DesignCTokens.UTILITY_VISUAL_INSET_DP,
                DesignCTokens.UTILITY_VISUAL_INSET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
            )
            val settingsHit = dpRect(
                pane,
                density,
                DesignCTokens.UTILITY_HIT_INSET_DP,
                DesignCTokens.UTILITY_HIT_INSET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
            )
            val bundlesVisual = dpRect(
                pane,
                density,
                wDp - DesignCTokens.UTILITY_VISUAL_INSET_DP - DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.UTILITY_VISUAL_INSET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
            )
            val bundlesHit = dpRect(
                pane,
                density,
                wDp - DesignCTokens.UTILITY_HIT_INSET_DP - DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.UTILITY_HIT_INSET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
                DesignCTokens.TOUCH_TARGET_DP,
            )
            val modeBarBounds = dpRect(
                pane,
                density,
                wDp / 2f - DesignCTokens.MODE_BAR_MAX_WIDTH_DP / 2f,
                DesignCTokens.CW90_MODE_TOP_DP,
                DesignCTokens.MODE_BAR_MAX_WIDTH_DP,
                DesignCTokens.MODE_BAR_HEIGHT_DP,
            )

            val queueLaneWidthDp = DesignCTokens.DEFAULT_INNER_QUEUE_SPAN_DP
                .coerceAtMost((wDp - 240f).coerceAtLeast(216f))
            val controlWidthDp = DesignCTokens.CONTROL_GROUP_WIDTH_DP
            val (startMarginDp, betweenGapDp, _) = computeCw90BalancedMargins(
                viewportWidthDp = wDp,
                queueWidthDp = queueLaneWidthDp,
                controlWidthDp = controlWidthDp,
            )

            val blankBottomHeightDp = (hDp - previewY - fhDp).coerceAtLeast(0f)
            val primaryBandHeightDp = maxOf(blankBottomHeightDp, DesignCTokens.CONTROL_GROUP_HEIGHT_DP + 28f)
            val controlGroupH = DesignCTokens.CONTROL_GROUP_HEIGHT_DP
            val controlTopDp = if (blankBottomHeightDp >= DesignCTokens.MIN_PRIMARY_BAND_DP && hDp >= 940f) {
                hDp - 204f
            } else {
                hDp - primaryBandHeightDp + (primaryBandHeightDp - controlGroupH) / 2f
            }
            val shutterTopDp = controlTopDp + DesignCTokens.ZOOM_TO_SHUTTER_OFFSET_DP
            val shutterCenterYDp = shutterTopDp + profile.shutterSizeDp / 2f // 848dp on h=960
            val queueHeightDp = profile.mediaDp // 56dp
            val queueTopDp = shutterCenterYDp - queueHeightDp / 2f // 820dp on h=960
            val queueX = startMarginDp
            val controlX = queueX + queueLaneWidthDp + betweenGapDp
            val shutterX = controlX + 56f
            val auxX = controlX
            val auxY = shutterTopDp + (profile.shutterSizeDp - 48f) / 2f
            val lensX = auxX + 144f

            val zoomBounds = if (singleZoom) {
                dpRect(pane, density, controlX + 76f, controlTopDp, 40f, 40f)
            } else {
                dpRect(pane, density, controlX, controlTopDp, 192f, 40f)
            }
            val controlGroup = ControlGroupGeometry(
                bounds = dpRect(pane, density, controlX, controlTopDp, 192f, 132f),
                zoomBounds = zoomBounds,
                auxBounds = dpRect(pane, density, auxX, auxY, 48f, 48f),
                shutterBounds = dpRect(pane, density, shutterX, shutterTopDp, profile.shutterSizeDp, profile.shutterSizeDp),
                lensBounds = dpRect(pane, density, lensX, auxY, 48f, 48f),
                isSingleZoom = singleZoom,
            )

            val queueBounds = dpRect(pane, density, queueX, queueTopDp, queueLaneWidthDp, queueHeightDp)
            val queueStripBounds = dpRect(
                pane,
                density,
                queueX,
                shutterCenterYDp - profile.stripCrossDp / 2f,
                queueLaneWidthDp,
                profile.stripCrossDp,
            )
            val capacity = deriveQueueCapacity(
                availableAxisDp = queueLaneWidthDp,
                startMarginDp = 0f,
                endMarginDp = 0f,
                profile = profile,
            )
            val runwayBounds = dpRect(
                pane,
                density,
                queueX,
                queueTopDp,
                queueLaneWidthDp,
                queueHeightDp + 32f,
            )
            val timerBounds = dpRect(
                pane,
                density,
                previewX + fwDp / 2f - 75f,
                maxOf(84f, previewY + 12f),
                150f,
                32f,
            )
            val feedbackBounds = dpRect(
                pane,
                density,
                previewX + (fwDp - 328f).coerceAtLeast(0f) / 2f,
                maxOf(92f, previewY + 48f),
                328f.coerceAtMost(fwDp),
                48f,
            )

            val intrudesBottomBand = (previewY + fhDp) > (controlTopDp + 8f)
            val intrudesTopUtility = previewY < utilityHeightDp - 0.5f

            return CaptureGeometry(
                windowWidthPx = pane.width,
                windowHeightPx = pane.height,
                density = density,
                usablePaneBounds = pane,
                pose = CapturePose.InnerCW90,
                dockSide = DockSide.Bottom,
                profile = profile,
                queueAxis = QueueAxis.Horizontal,
                previewBounds = previewRect,
                settingsButtonBounds = settingsHit,
                settingsVisualBounds = settingsVisual,
                bundlesButtonBounds = bundlesHit,
                bundlesVisualBounds = bundlesVisual,
                modeBarBounds = modeBarBounds,
                controlGroup = controlGroup,
                queueBounds = queueBounds,
                queueStripBounds = queueStripBounds,
                queueCapacity = capacity,
                gestureRunwayBounds = runwayBounds,
                videoTimerBounds = timerBounds,
                voiceOverlayBounds = previewRect,
                feedbackBounds = feedbackBounds,
                controlsOverlayPreview = intrudesBottomBand,
                showTopHeaderScrim = intrudesTopUtility,
                showLeftUtilityScrim = false,
            )
        }
    }

    /**
     * Compute the CW90 outer margins `[startMargin, betweenGap, endMargin]` seeking
     * approximately 1:1:1 balance across the bottom band:
     * - Fold8 (w=720, queue=428, controls=192 -> slack=100): `[34, 32, 34]`
     * - Fold8 Ultra (w=864, queue=428, controls=192 -> slack=244): `[84, 80, 80]`
     */
    fun computeCw90BalancedMargins(
        viewportWidthDp: Float,
        queueWidthDp: Float = 428f,
        controlWidthDp: Float = 192f,
    ): Triple<Float, Float, Float> {
        val slack = (viewportWidthDp - queueWidthDp - controlWidthDp).coerceAtLeast(0f)
        val unit4 = floor((slack / 3f) / 4f) * 4f
        val remainder = slack - 3f * unit4
        return when {
            abs(remainder - 4f) < 0.01f && unit4 < 60f -> {
                // e.g. slack = 100, unit4 = 32 -> 34 / 32 / 34
                Triple(unit4 + 2f, unit4, unit4 + 2f)
            }
            abs(remainder - 4f) < 0.01f -> {
                // e.g. slack = 244, unit4 = 80 -> 84 / 80 / 80
                Triple(unit4 + 4f, unit4, unit4)
            }
            else -> {
                val between = (slack / 3f).roundToInt().toFloat()
                val start = ((slack - between) / 2f).roundToInt().toFloat()
                val end = (slack - start - between).coerceAtLeast(0f)
                Triple(start, between, end)
            }
        }
    }

    /**
     * Responsive phone, cover, and free-form / split-screen window layout.
     * Evaluates candidate queue rectangle to choose `Standard56` vs `Compact48`,
     * reserves `safeContent` insets + 24dp downward gesture travel corridor,
     * and compares side vs bottom dock by fitted preview area with 12% hysteresis.
     */
    private fun computeResponsiveShell(
        input: CaptureGeometryInput,
        pane: RectPx,
        density: Float,
    ): CaptureGeometry {
        val obs = input.observation
        val safeInsets = obs.safeContentInsetsPx
        val safeTopDp = (safeInsets.top / density).coerceAtLeast(24f)
        val safeBottomDp = (safeInsets.bottom / density).coerceAtLeast(24f)
        val safeLeftDp = (safeInsets.left / density).coerceAtLeast(24f)
        val safeRightDp = (safeInsets.right / density).coerceAtLeast(24f)

        val wDp = pane.width / density
        val hDp = pane.height / density
        val usableHeightDp = hDp - safeTopDp - safeBottomDp

        val defaultRatio = if (input.modality == Modality.VIDEO) 9f / 16f else 3f / 4f
        val frameRatio = input.streamGeometry?.uprightAspectRatio ?: defaultRatio
        val singleZoom = input.lensFacing == LensFacing.Front || !input.hasMultipleZoomPresets

        // On a portrait phone or foldable cover screen, the viewfinder is portrait inside a
        // portrait window: there is no horizontal space beside the viewfinder, so the utility
        // bar is at the top, main controls + horizontal queue are at the bottom, and the
        // viewfinder spans the full screen width `0 .. wDp`.
        val isPortraitPhoneOrCover = obs.surfaceRole == SurfaceRole.Cover ||
            (wDp < hDp && (obs.surfaceRole == SurfaceRole.Regular || minOf(wDp, hDp) < 600f))
        val dockSide = if (isPortraitPhoneOrCover) {
            DockSide.Bottom
        } else {
            chooseDockSide(
                wDp = wDp,
                hDp = hDp,
                safeTopDp = safeTopDp,
                safeBottomDp = safeBottomDp,
                frameRatio = frameRatio,
                settledDock = input.settledDockSide,
                preferredSide = input.handedness,
            )
        }

        val isSideDock = dockSide == DockSide.Right || dockSide == DockSide.Left
        // Choose Standard56 when primary and cross-axis space fit; Compact48 on short windows
        val useCompact48 = !isSideDock && usableHeightDp < 600f
        val profile = if (useCompact48) QueueProfileToken.Compact48 else QueueProfileToken.Standard56

        val queueStripHeightDp = profile.stripCrossDp
        val shutterSizeDp = profile.shutterSizeDp
        val queueGapDp = profile.shutterToQueueGapDp
        val travelDp = if (isSideDock) 48f else 24f

        // Margin-first queue capacity contract (`COMPONENT-CONTRACT.md` § Geometry):
        // On portrait phones and foldable covers, reserve only the left/right safe
        // operating margins (`safeLeftDp` and `safeRightDp`, >= 24dp for OS Back gesture
        // clearance) and let the queue span the full remaining width `L = wDp - M_s - M_e`.
        val candidateQueueWidthDp = if (isPortraitPhoneOrCover) {
            (wDp - safeLeftDp - safeRightDp).coerceAtLeast(160f)
        } else {
            min(360f, (wDp - safeLeftDp - safeRightDp).coerceAtLeast(160f))
        }

        val queueX = when {
            isPortraitPhoneOrCover -> safeLeftDp
            dockSide == DockSide.Right -> wDp - safeRightDp - candidateQueueWidthDp
            dockSide == DockSide.Left -> safeLeftDp
            else -> (wDp - candidateQueueWidthDp) / 2f
        }
        val queueY = hDp - safeBottomDp - travelDp - queueStripHeightDp
        val shutterY = queueY - queueGapDp - shutterSizeDp
        val zoomOffsetDp = if (useCompact48) 44f else 52f
        val zoomY = shutterY - zoomOffsetDp
        val centerX = queueX + candidateQueueWidthDp / 2f

        val topBarBottomDp = safeTopDp + 48f // 72dp when safeTopDp == 24dp
        val primaryTopDp = zoomY - 12f
        val previewCandidateBox = when {
            isPortraitPhoneOrCover -> {
                RectPx(0f, 0f, wDp, (hDp - safeBottomDp).coerceAtLeast(1f))
            }
            isSideDock -> {
                val previewX = if (dockSide == DockSide.Left) 408f else 24f
                val previewW = (wDp - 432f).coerceAtLeast(120f)
                val previewH = (hDp - safeBottomDp - 84f).coerceAtLeast(120f)
                RectPx(previewX, 84f, previewW, previewH)
            }
            else -> {
                val previewW = (wDp - 48f).coerceAtLeast(120f)
                val previewH = (zoomY - 100f).coerceAtLeast(120f)
                RectPx(24f, 84f, previewW, previewH)
            }
        }

        val fittedPreviewDp = fitAspect(previewCandidateBox, frameRatio).let { fitted ->
            if (isPortraitPhoneOrCover) {
                val previewY = computePreviewStartAlongLayoutAxis(
                    primaryZoneStartDp = primaryTopDp,
                    previewSpanDp = fitted.height,
                    utilityEndDp = topBarBottomDp,
                )
                fitted.copy(y = previewY)
            } else {
                fitted
            }
        }
        val previewBounds = dpRect(
            pane,
            density,
            fittedPreviewDp.x,
            fittedPreviewDp.y,
            fittedPreviewDp.width,
            fittedPreviewDp.height,
        )

        val settingsHit = if (isPortraitPhoneOrCover) {
            dpRect(pane, density, 4f, safeTopDp, 48f, 48f)
        } else {
            dpRect(pane, density, 24f, safeTopDp, 48f, 48f)
        }
        val bundlesHit = if (isPortraitPhoneOrCover) {
            dpRect(pane, density, wDp - 52f, safeTopDp, 48f, 48f)
        } else {
            dpRect(pane, density, wDp - 72f, safeTopDp, 48f, 48f)
        }
        val modeWidthDp = 250f.coerceAtMost((wDp - 96f).coerceAtLeast(150f))
        val modeBarBounds = dpRect(pane, density, wDp / 2f - modeWidthDp / 2f, safeTopDp + 4f, modeWidthDp, 40f)

        val auxY = shutterY + (shutterSizeDp - 48f) / 2f
        val auxX = centerX - 120f
        val lensX = centerX + 72f
        val zoomX = centerX - 96f
        val zoomBounds = if (singleZoom) {
            dpRect(pane, density, zoomX + 52f, zoomY, 40f, 40f)
        } else {
            dpRect(pane, density, zoomX, zoomY, 192f, 40f)
        }
        val controlGroup = ControlGroupGeometry(
            bounds = dpRect(pane, density, auxX, zoomY, 240f, (shutterY + shutterSizeDp - zoomY)),
            zoomBounds = zoomBounds,
            auxBounds = dpRect(pane, density, auxX, auxY, 48f, 48f),
            shutterBounds = dpRect(pane, density, centerX - shutterSizeDp / 2f, shutterY, shutterSizeDp, shutterSizeDp),
            lensBounds = dpRect(pane, density, lensX, auxY, 48f, 48f),
            isSingleZoom = singleZoom,
        )

        val queueStripBounds = dpRect(pane, density, queueX, queueY, candidateQueueWidthDp, queueStripHeightDp)
        val queueBounds = dpRect(
            pane,
            density,
            queueX,
            queueY + profile.verticalPaddingDp,
            candidateQueueWidthDp,
            profile.mediaDp,
        )
        val capacity = deriveQueueCapacity(
            availableAxisDp = wDp,
            startMarginDp = queueX,
            endMarginDp = wDp - (queueX + candidateQueueWidthDp),
            profile = profile,
        )
        val runwayBounds = dpRect(
            pane,
            density,
            queueX,
            queueY,
            candidateQueueWidthDp,
            queueStripHeightDp + travelDp,
        )
        val timerBounds = dpRect(
            pane,
            density,
            fittedPreviewDp.x + fittedPreviewDp.width / 2f - 75f,
            maxOf(84f, fittedPreviewDp.y + 12f),
            150f,
            32f,
        )
        val feedbackBounds = dpRect(
            pane,
            density,
            (wDp - 328f).coerceAtLeast(16f) / 2f,
            92f,
            328f.coerceAtMost(wDp - 32f),
            48f,
        )

        val controlsOverlay = isPortraitPhoneOrCover &&
            (fittedPreviewDp.bottom > zoomY + 4f)
        val topHeaderIntruded = isPortraitPhoneOrCover &&
            (fittedPreviewDp.y < topBarBottomDp - 0.5f)

        return CaptureGeometry(
            windowWidthPx = pane.width,
            windowHeightPx = pane.height,
            density = density,
            usablePaneBounds = pane,
            pose = when {
                isPortraitPhoneOrCover -> CapturePose.CompactBottom
                isSideDock -> CapturePose.AdaptiveSide
                else -> CapturePose.AdaptiveBottom
            },
            dockSide = dockSide,
            profile = profile,
            queueAxis = QueueAxis.Horizontal,
            previewBounds = previewBounds,
            settingsButtonBounds = settingsHit,
            settingsVisualBounds = settingsHit,
            bundlesButtonBounds = bundlesHit,
            bundlesVisualBounds = bundlesHit,
            modeBarBounds = modeBarBounds,
            controlGroup = controlGroup,
            queueBounds = queueBounds,
            queueStripBounds = queueStripBounds,
            queueCapacity = capacity,
            gestureRunwayBounds = runwayBounds,
            videoTimerBounds = timerBounds,
            voiceOverlayBounds = previewBounds,
            feedbackBounds = feedbackBounds,
            controlsOverlayPreview = controlsOverlay,
            showTopHeaderScrim = topHeaderIntruded,
        )
    }

    private fun resolveUsablePane(
        obs: WindowObservation,
        handedness: DockSide,
    ): RectPx {
        val winW = obs.windowWidthPx.coerceAtLeast(1).toFloat()
        val winH = obs.windowHeightPx.coerceAtLeast(1).toFloat()
        val hinge = obs.hinge
        if (hinge == null || (!hinge.isSeparating && !hinge.isOccluding)) {
            return RectPx(0f, 0f, winW, winH)
        }
        return when (hinge.orientation) {
            HingeOrientation.Vertical -> {
                val leftPane = RectPx(0f, 0f, hinge.boundsPx.left.toFloat().coerceAtLeast(0f), winH)
                val rightX = hinge.boundsPx.right.toFloat().coerceAtMost(winW)
                val rightPane = RectPx(rightX, 0f, (winW - rightX).coerceAtLeast(0f), winH)
                if (handedness == DockSide.Left && leftPane.width >= 240f) leftPane else rightPane
            }
            HingeOrientation.Horizontal -> {
                val topH = hinge.boundsPx.top.toFloat().coerceAtLeast(0f)
                val bottomY = hinge.boundsPx.bottom.toFloat().coerceAtMost(winH)
                val bottomH = (winH - bottomY).coerceAtLeast(0f)
                if (topH >= bottomH) {
                    RectPx(0f, 0f, winW, topH)
                } else {
                    RectPx(0f, bottomY, winW, bottomH)
                }
            }
        }
    }

    private fun chooseDockSide(
        wDp: Float,
        hDp: Float,
        safeTopDp: Float,
        safeBottomDp: Float,
        frameRatio: Float,
        settledDock: DockSide?,
        preferredSide: DockSide,
    ): DockSide {
        val sideBox = RectPx(24f, 84f, (wDp - 432f).coerceAtLeast(1f), (hDp - safeBottomDp - 84f).coerceAtLeast(1f))
        val bottomBox = RectPx(24f, 84f, (wDp - 48f).coerceAtLeast(1f), (hDp - safeBottomDp - 332f).coerceAtLeast(1f))
        val sideFit = fitAspect(sideBox, frameRatio)
        val bottomFit = fitAspect(bottomBox, frameRatio)
        val sideArea = sideFit.width * sideFit.height
        val bottomArea = bottomFit.width * bottomFit.height
        val sideTarget = if (preferredSide == DockSide.Left) DockSide.Left else DockSide.Right

        if (settledDock != null) {
            if (settledDock == DockSide.Bottom && sideArea <= bottomArea * HYSTERESIS_RATIO) {
                return DockSide.Bottom
            }
            if ((settledDock == DockSide.Right || settledDock == DockSide.Left) &&
                bottomArea <= sideArea * HYSTERESIS_RATIO
            ) {
                return settledDock
            }
        }
        return if (sideArea > bottomArea * 1.05f && wDp >= 640f) sideTarget else DockSide.Bottom
    }

    private fun fitAspect(box: RectPx, ratio: Float): RectPx {
        val safeRatio = if (ratio > 0f) ratio else 0.75f
        val w = min(box.width, box.height * safeRatio)
        val h = w / safeRatio
        return RectPx(
            x = box.x + (box.width - w) / 2f,
            y = box.y + (box.height - h) / 2f,
            width = w,
            height = h,
        )
    }

    private fun dpRect(
        pane: RectPx,
        density: Float,
        xDp: Float,
        yDp: Float,
        wDp: Float,
        hDp: Float,
    ): RectPx = RectPx(
        x = pane.x + xDp * density,
        y = pane.y + yDp * density,
        width = wDp * density,
        height = hDp * density,
    )
}
