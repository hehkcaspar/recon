package com.example.bundlecam.ui.capture

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

private val CommitGreen = CaptureColors.CommitGreen
private val DiscardAmber = CaptureColors.DiscardAmber
private const val EXIT_ANIMATION_MS = 350
private const val FLASH_DURATION_MS = 220L

internal object QueueMetrics {
    val ThumbSize = 56.dp
    val ThumbGap = 6.dp
    val OuterPadding = 8.dp
    // 8dp inward cross-axis touch overslop (giving 16dp runway before the thumbnail edge
    // for divider split/join starts) and 24dp outward delete corridor (so a 32dp-dragged
    // thumbnail and the 24dp Gaussian delete glow stay 100% inside QueueStrip's bounds
    // without ever being clipped by an offscreen layer or scroll container).
    const val InwardOverslopDp = 8f
    const val OutwardDeleteCorridorDp = 24f
    val InwardOverslop = 8.dp
    val OutwardDeleteCorridor = 24.dp
    val DeleteBulgeMaxCrossDp = 24.dp
    val DeleteGlowEndpointFeatherDp = 24.dp
    // 28dp hit span across a 6dp gap intrudes 11dp into each adjacent thumbnail, leaving
    // 34dp of un-overlapped center width on each 56dp thumbnail while making the gap easy
    // to hit with a thumb.
    val DividerHitWidth = 28.dp
    val DividerExpandedGap = 24.dp
    val DividerVisualThickness = 4.dp
    val SlotSize = ThumbSize + ThumbGap
}

enum class HandleEndpoint {
    Start, // Left in Horizontal, Top in Vertical
    End,   // Right in Horizontal, Bottom in Vertical
}

enum class EdgeAction {
    None,
    Commit,
    Discard,
}

/**
 * Pure, unit-testable gesture arbitration and threshold math for the capture queue.
 * Enforces all `COMPONENT-CONTRACT.md` & Design C rules:
 * - Edge commit/discard requires >= 96dp net inward displacement on valid release;
 *   velocity alone NEVER mutates the queue.
 * - `QueueAxis.Horizontal`: Start (Left) -> right (+delta >= 96dp) commits;
 *   End (Right) -> left (-delta <= -96dp) discards.
 * - `QueueAxis.Vertical`: End (Bottom) -> up (-delta <= -96dp) commits;
 *   Start (Top) -> down (+delta >= 96dp) discards.
 * - Item delete requires > 24dp net outward cross-axis displacement on valid release,
 *   with rendered travel clamped to 32dp and alpha `clamp(1 - 0.6 * drag / 32, 0.4, 1.0)`.
 * - Divider split/join requires >= 24dp cross-axis displacement on valid release.
 * - Reorder destination is resolved from actual measured slot centers (including 24dp
 *   expanded split gaps) rather than a fixed 62dp pitch.
 */
object QueueGestureMath {
    const val EDGE_COMMIT_DISCARD_GATE_DP = 96f
    const val DELETE_GATE_DP = 24f
    const val DELETE_MAX_VISUAL_DP = 32f
    const val DIVIDER_GATE_DP = 24f

    fun expectedEdgeActionForHandle(axis: QueueAxis, endpoint: HandleEndpoint): EdgeAction =
        when (axis) {
            QueueAxis.Horizontal -> when (endpoint) {
                HandleEndpoint.Start -> EdgeAction.Commit
                HandleEndpoint.End -> EdgeAction.Discard
            }
            QueueAxis.Vertical -> when (endpoint) {
                HandleEndpoint.Start -> EdgeAction.Discard
                HandleEndpoint.End -> EdgeAction.Commit
            }
        }

    /**
     * Signed inward displacement (positive when dragging inward from [endpoint]).
     */
    fun inwardDisplacementPx(
        endpoint: HandleEndpoint,
        netPrimaryDeltaPx: Float,
    ): Float = when (endpoint) {
        HandleEndpoint.Start -> netPrimaryDeltaPx.coerceAtLeast(0f)
        HandleEndpoint.End -> (-netPrimaryDeltaPx).coerceAtLeast(0f)
    }

    fun isEdgeArmed(
        endpoint: HandleEndpoint,
        netPrimaryDeltaPx: Float,
        gatePx: Float,
    ): Boolean = inwardDisplacementPx(endpoint, netPrimaryDeltaPx) >= gatePx

    fun resolveEdgeRelease(
        axis: QueueAxis,
        endpoint: HandleEndpoint,
        netPrimaryDeltaPx: Float,
        gatePx: Float,
        isCancelled: Boolean,
        isLocked: Boolean,
    ): EdgeAction {
        if (isCancelled || isLocked) return EdgeAction.None
        if (!isEdgeArmed(endpoint, netPrimaryDeltaPx, gatePx)) return EdgeAction.None
        return expectedEdgeActionForHandle(axis, endpoint)
    }

    fun shouldCommitDelete(
        netCrossPx: Float,
        deleteGatePx: Float,
        isCancelled: Boolean,
        isLocked: Boolean,
    ): Boolean = !isCancelled && !isLocked && netCrossPx > deleteGatePx

    fun clampedDeleteVisualPx(
        netCrossPx: Float,
        maxVisualPx: Float,
    ): Float = netCrossPx.coerceIn(0f, maxVisualPx)

    fun deleteProgress(
        netCrossPx: Float,
        deleteGatePx: Float,
    ): Float = if (deleteGatePx <= 0f) 0f else (netCrossPx / deleteGatePx).coerceIn(0f, 1f)

    fun deleteItemAlpha(
        clampedVisualPx: Float,
        maxVisualPx: Float,
    ): Float {
        if (maxVisualPx <= 0f || clampedVisualPx <= 0f) return 1f
        return (1f - 0.6f * (clampedVisualPx / maxVisualPx)).coerceIn(0.4f, 1f)
    }

    /**
     * Normalized `[0f, 1f]` Gaussian bulge height factor at primary-axis coordinate
     * [primaryPx] relative to [hotspotPrimaryPx] inside a queue lane of length
     * [primaryLengthPx]. Uses `sigma = 0.90 * mediaPx` for a wide, organic bell curve
     * and a smoothstep endpoint feather over [endpointFeatherPx] (`24dp`) so the curve
     * tapers cleanly to `0f` at both ends of the queue even when dragging the first or
     * last visible item.
     */
    fun deleteGlowBulgeFactor(
        primaryPx: Float,
        hotspotPrimaryPx: Float,
        primaryLengthPx: Float,
        mediaPx: Float,
        endpointFeatherPx: Float,
    ): Float {
        if (primaryLengthPx <= 0f || mediaPx <= 0f) return 0f
        val sigma = 0.90f * mediaPx
        val dist = primaryPx - hotspotPrimaryPx
        val raw = exp(-(dist * dist) / (2f * sigma * sigma))
        val trimmed = ((raw - 0.006f) / 0.994f).coerceIn(0f, 1f)
        val dEdge = minOf(primaryPx, primaryLengthPx - primaryPx)
        val taper = when {
            dEdge <= 0f -> 0f
            endpointFeatherPx <= 0f || dEdge >= endpointFeatherPx -> 1f
            else -> {
                val t = (dEdge / endpointFeatherPx).coerceIn(0f, 1f)
                t * t * (3f - 2f * t)
            }
        }
        return (trimmed * taper).coerceIn(0f, 1f)
    }

    /**
     * Effective cross-axis bulge height in pixels for a given [progress] (`0f..1f`) and
     * [maxBulgePx] (`24dp`), scaling both height (`35%..100%` of [maxBulgePx]) and opacity
     * with [progress] so the red bulge visibly rises as the user pulls the tile outward.
     */
    fun deleteGlowPeakExtentPx(
        progress: Float,
        maxBulgePx: Float,
    ): Float {
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0f || maxBulgePx <= 0f) return 0f
        return maxBulgePx * (0.35f + 0.65f * p)
    }

    fun shouldCommitDividerToggle(
        netCrossPx: Float,
        hasDivider: Boolean,
        dividerGatePx: Float,
        isCancelled: Boolean,
        isLocked: Boolean,
    ): Boolean {
        if (isCancelled || isLocked) return false
        val activeSign = if (hasDivider) -1f else 1f
        return netCrossPx * activeSign >= dividerGatePx
    }

    fun computeReorderDestinationIndex(
        currentIndex: Int,
        dragPrimaryPx: Float,
        slotCentersPx: FloatArray,
        fallbackPitchPx: Float,
        queueSize: Int,
    ): Int {
        if (queueSize <= 1 || currentIndex !in 0 until queueSize) return currentIndex.coerceIn(0, (queueSize - 1).coerceAtLeast(0))
        if (slotCentersPx.size == queueSize) {
            val targetCenter = slotCentersPx[currentIndex] + dragPrimaryPx
            var bestIndex = currentIndex
            var bestDist = Float.MAX_VALUE
            for (i in 0 until queueSize) {
                val dist = abs(slotCentersPx[i] - targetCenter)
                if (dist < bestDist) {
                    bestDist = dist
                    bestIndex = i
                }
            }
            return bestIndex
        }
        val safePitch = fallbackPitchPx.coerceAtLeast(1f)
        val shift = (dragPrimaryPx / safePitch).roundToInt()
        return (currentIndex + shift).coerceIn(0, queueSize - 1)
    }
}

private sealed class GestureState {
    object Idle : GestureState()
    data class ActiveEdge(
        val endpoint: HandleEndpoint,
        val action: EdgeAction,
        val netPrimaryDeltaPx: Float,
        val isArmed: Boolean,
    ) : GestureState()
}

private data class ExitingSnapshot(
    val thumbnails: List<androidx.compose.ui.graphics.ImageBitmap>,
    val action: EdgeAction,
    val axis: QueueAxis,
)

@Composable
fun QueueStrip(
    queue: List<StagedItem>,
    dividers: Set<Int>,
    onCommit: () -> Unit,
    onDiscard: () -> Unit,
    onDelete: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onInsertDivider: (Int) -> Unit,
    onRemoveDivider: (Int) -> Unit,
    onDeleteProgress: (progress: Float, hotspotXInRoot: Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    axis: QueueAxis = QueueAxis.Horizontal,
    profile: QueueProfileToken = QueueProfileToken.Standard56,
    locked: Boolean = false,
) {
    val enabled = queue.isNotEmpty() && !locked
    val view = LocalView.current
    val density = LocalDensity.current

    var gesture by remember(axis, profile) { mutableStateOf<GestureState>(GestureState.Idle) }
    var exiting by remember { mutableStateOf<ExitingSnapshot?>(null) }
    var flashAction by remember { mutableStateOf<EdgeAction?>(null) }
    var selectedMenuItemId by remember { mutableStateOf<String?>(null) }
    var deleteProgress by remember(axis, profile) { mutableFloatStateOf(0f) }
    var deleteHotspotInRoot by remember(axis, profile) { mutableFloatStateOf(0f) }
    var stripCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var stripOriginInRootPrimary by remember { mutableFloatStateOf(0f) }

    val animatedDeleteProgress by animateFloatAsState(
        targetValue = deleteProgress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "queue-delete-glow",
    )

    // Cancel any active gesture or open action menu if locked or queue becomes empty.
    LaunchedEffect(locked, queue.isEmpty(), axis, profile) {
        if (locked || queue.isEmpty()) {
            gesture = GestureState.Idle
            deleteProgress = 0f
        }
        if (locked) {
            selectedMenuItemId = null
        }
    }

    LaunchedEffect(exiting) {
        val snap = exiting ?: return@LaunchedEffect
        delay(EXIT_ANIMATION_MS.toLong())
        if (exiting == snap) exiting = null
    }
    LaunchedEffect(flashAction) {
        val act = flashAction ?: return@LaunchedEffect
        delay(FLASH_DURATION_MS)
        if (flashAction == act) flashAction = null
    }

    val commitWithAnimation = {
        if (queue.isNotEmpty() && !locked) {
            exiting = ExitingSnapshot(queue.map { it.thumbnail }, EdgeAction.Commit, axis)
            flashAction = EdgeAction.Commit
            onCommit()
        }
    }
    val discardWithAnimation = {
        if (queue.isNotEmpty() && !locked) {
            exiting = ExitingSnapshot(queue.map { it.thumbnail }, EdgeAction.Discard, axis)
            flashAction = EdgeAction.Discard
            onDiscard()
        }
    }

    val gatePx = with(density) { QueueGestureMath.EDGE_COMMIT_DISCARD_GATE_DP.dp.toPx() }
    val targetDp = profile.edgeTargetDp.dp
    // Cross-axis allocation:
    // - 8dp inward overslop (giving 16dp runway before the thumbnail edge for divider starts)
    // - `stripCrossDp` (72dp Standard56 / 64dp Compact48)
    // - 24dp outward delete corridor (`OutwardDeleteCorridorDp`) so a 32dp-dragged thumbnail
    //   (`16 + 56 + 32 = 104dp` or `16 + 48 + 32 = 96dp`) and the 24dp Gaussian delete glow
    //   stay 100% inside `QueueStrip`'s layout bounds without ever being clipped.
    val totalStripCrossDp = (
        QueueMetrics.InwardOverslopDp +
            profile.stripCrossDp +
            QueueMetrics.OutwardDeleteCorridorDp
        ).dp
    val mediaCrossOffsetDp = (QueueMetrics.InwardOverslopDp + profile.verticalPaddingDp).dp

    val sizeModifier = if (axis == QueueAxis.Horizontal) {
        modifier
            .fillMaxWidth()
            .height(totalStripCrossDp)
    } else {
        modifier
            .width(totalStripCrossDp)
            .fillMaxHeight()
    }

    BoxWithConstraints(
        modifier = sizeModifier
            .onGloballyPositioned { coords ->
                stripCoords = coords
                val pos = coords.positionInRoot()
                stripOriginInRootPrimary = if (axis == QueueAxis.Horizontal) pos.x else pos.y
            }
            .graphicsLayer {
                this.alpha = if (locked) 0.35f else 1f
            },
    ) {
        val activeEdge = gesture as? GestureState.ActiveEdge
        val rawProgress = if (activeEdge != null) {
            (QueueGestureMath.inwardDisplacementPx(activeEdge.endpoint, activeEdge.netPrimaryDeltaPx) / gatePx)
                .coerceIn(0f, 1f)
        } else {
            0f
        }
        val animatedProgress by animateFloatAsState(
            targetValue = rawProgress,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "queue-gesture-progress",
        )
        val tiltDeg by animateFloatAsState(
            targetValue = when (activeEdge?.action) {
                EdgeAction.Commit -> rawProgress * 3f
                EdgeAction.Discard -> -rawProgress * 3f
                else -> 0f
            },
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
            label = "queue-tilt",
        )

        val liveStripOrigin = stripCoords?.takeIf { it.isAttached }?.positionInRoot()?.let { pos ->
            if (axis == QueueAxis.Horizontal) pos.x else pos.y
        } ?: stripOriginInRootPrimary
        val localDeleteHotspotPx = deleteHotspotInRoot - liveStripOrigin

        Box(modifier = Modifier.fillMaxSize()) {
            val snapshot = exiting
            if (snapshot == null) {
                QueueTray(
                    queue = queue,
                    dividers = dividers,
                    axis = axis,
                    profile = profile,
                    locked = locked,
                    gesture = gesture,
                    flashAction = flashAction,
                    tideProgress = animatedProgress,
                    tiltDeg = tiltDeg,
                    deleteProgress = animatedDeleteProgress,
                    deleteHotspotPrimaryPx = localDeleteHotspotPx,
                    onTapItem = { item ->
                        if (!locked) selectedMenuItemId = item.id
                    },
                    onDelete = onDelete,
                    onReorder = onReorder,
                    onInsertDivider = onInsertDivider,
                    onRemoveDivider = onRemoveDivider,
                    onCommitQueue = commitWithAnimation,
                    onDiscardQueue = discardWithAnimation,
                    onDeleteProgress = { progress, hotspotInRoot ->
                        deleteProgress = progress
                        if (progress > 0f) {
                            deleteHotspotInRoot = hotspotInRoot
                        }
                        onDeleteProgress(progress, hotspotInRoot)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FlyingQueue(
                    snapshot = snapshot,
                    profile = profile,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            val startAction = QueueGestureMath.expectedEdgeActionForHandle(axis, HandleEndpoint.Start)
            val endAction = QueueGestureMath.expectedEdgeActionForHandle(axis, HandleEndpoint.End)

            val startHandleModifier = if (axis == QueueAxis.Horizontal) {
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = mediaCrossOffsetDp)
                    .size(targetDp)
            } else {
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = mediaCrossOffsetDp)
                    .size(targetDp)
            }
            val endHandleModifier = if (axis == QueueAxis.Horizontal) {
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = mediaCrossOffsetDp)
                    .size(targetDp)
            } else {
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = mediaCrossOffsetDp)
                    .size(targetDp)
            }

            EdgeZone(
                endpoint = HandleEndpoint.Start,
                axis = axis,
                profile = profile,
                expectedAction = startAction,
                gesture = gesture,
                progress = animatedProgress,
                enabled = enabled,
                locked = locked,
                flashActive = flashAction == startAction,
                onAction = if (startAction == EdgeAction.Commit) commitWithAnimation else discardWithAnimation,
                onDragStart = {
                    gesture = GestureState.ActiveEdge(
                        endpoint = HandleEndpoint.Start,
                        action = startAction,
                        netPrimaryDeltaPx = 0f,
                        isArmed = false,
                    )
                },
                onDragDelta = { deltaPrimary ->
                    val current = gesture as? GestureState.ActiveEdge ?: return@EdgeZone
                    if (current.endpoint != HandleEndpoint.Start) return@EdgeZone
                    val nextDelta = current.netPrimaryDeltaPx + deltaPrimary
                    val armedNow = QueueGestureMath.isEdgeArmed(HandleEndpoint.Start, nextDelta, gatePx)
                    if (armedNow && !current.isArmed) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    gesture = current.copy(netPrimaryDeltaPx = nextDelta, isArmed = armedNow)
                },
                onDragEnd = { cancelled ->
                    val current = gesture as? GestureState.ActiveEdge
                    gesture = GestureState.Idle
                    if (current != null && current.endpoint == HandleEndpoint.Start) {
                        val resolved = QueueGestureMath.resolveEdgeRelease(
                            axis = axis,
                            endpoint = HandleEndpoint.Start,
                            netPrimaryDeltaPx = current.netPrimaryDeltaPx,
                            gatePx = gatePx,
                            isCancelled = cancelled,
                            isLocked = locked,
                        )
                        when (resolved) {
                            EdgeAction.Commit -> commitWithAnimation()
                            EdgeAction.Discard -> discardWithAnimation()
                            EdgeAction.None -> Unit
                        }
                    }
                },
                modifier = startHandleModifier,
            )

            EdgeZone(
                endpoint = HandleEndpoint.End,
                axis = axis,
                profile = profile,
                expectedAction = endAction,
                gesture = gesture,
                progress = animatedProgress,
                enabled = enabled,
                locked = locked,
                flashActive = flashAction == endAction,
                onAction = if (endAction == EdgeAction.Commit) commitWithAnimation else discardWithAnimation,
                onDragStart = {
                    gesture = GestureState.ActiveEdge(
                        endpoint = HandleEndpoint.End,
                        action = endAction,
                        netPrimaryDeltaPx = 0f,
                        isArmed = false,
                    )
                },
                onDragDelta = { deltaPrimary ->
                    val current = gesture as? GestureState.ActiveEdge ?: return@EdgeZone
                    if (current.endpoint != HandleEndpoint.End) return@EdgeZone
                    val nextDelta = current.netPrimaryDeltaPx + deltaPrimary
                    val armedNow = QueueGestureMath.isEdgeArmed(HandleEndpoint.End, nextDelta, gatePx)
                    if (armedNow && !current.isArmed) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    gesture = current.copy(netPrimaryDeltaPx = nextDelta, isArmed = armedNow)
                },
                onDragEnd = { cancelled ->
                    val current = gesture as? GestureState.ActiveEdge
                    gesture = GestureState.Idle
                    if (current != null && current.endpoint == HandleEndpoint.End) {
                        val resolved = QueueGestureMath.resolveEdgeRelease(
                            axis = axis,
                            endpoint = HandleEndpoint.End,
                            netPrimaryDeltaPx = current.netPrimaryDeltaPx,
                            gatePx = gatePx,
                            isCancelled = cancelled,
                            isLocked = locked,
                        )
                        when (resolved) {
                            EdgeAction.Commit -> commitWithAnimation()
                            EdgeAction.Discard -> discardWithAnimation()
                            EdgeAction.None -> Unit
                        }
                    }
                },
                modifier = endHandleModifier,
            )
        }
    }

    val menuTargetIndex = selectedMenuItemId?.let { id -> queue.indexOfFirst { it.id == id } } ?: -1
    if (menuTargetIndex in queue.indices && !locked) {
        val targetItem = queue[menuTargetIndex]
        QueueActionMenuDialog(
            item = targetItem,
            index = menuTargetIndex,
            queueSize = queue.size,
            hasDividerAfter = menuTargetIndex in dividers,
            onDismiss = { selectedMenuItemId = null },
            onRemoveItem = {
                selectedMenuItemId = null
                onDelete(targetItem.id)
            },
            onMoveEarlier = {
                selectedMenuItemId = null
                onReorder(menuTargetIndex, menuTargetIndex - 1)
            },
            onMoveLater = {
                selectedMenuItemId = null
                onReorder(menuTargetIndex, menuTargetIndex + 1)
            },
            onToggleSplitAfter = {
                selectedMenuItemId = null
                if (menuTargetIndex in dividers) {
                    onRemoveDivider(menuTargetIndex)
                } else {
                    onInsertDivider(menuTargetIndex)
                }
            },
            onSaveQueue = {
                selectedMenuItemId = null
                commitWithAnimation()
            },
            onDiscardQueue = {
                selectedMenuItemId = null
                discardWithAnimation()
            },
        )
    }
}

@Composable
private fun EdgeZone(
    endpoint: HandleEndpoint,
    axis: QueueAxis,
    profile: QueueProfileToken,
    expectedAction: EdgeAction,
    gesture: GestureState,
    progress: Float,
    enabled: Boolean,
    locked: Boolean,
    flashActive: Boolean,
    onAction: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: (cancelled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeEdge = gesture as? GestureState.ActiveEdge
    val isInitiator = activeEdge?.endpoint == endpoint
    // Per COMPONENT-CONTRACT.md & recon-capture-design-system.svg (`queueCommit` / `queueDiscard`):
    // ONLY the initiating handle is replaced by the colored swiper; the opposite handle remains
    // a stationary neutral bar, and the swiper color/icon is strictly determined by this handle's
    // `expectedAction` (Commit = green + Check, Discard = amber + Close).
    val isCommitHandle = expectedAction == EdgeAction.Commit
    val swiperBaseColor = if (isCommitHandle) CommitGreen else DiscardAmber
    val swiperMaxOpacity = if (isCommitHandle) 0.78f else 0.82f
    val swiperIcon = if (isCommitHandle) Icons.Filled.Check else Icons.Filled.Close
    val label = if (isCommitHandle) "Commit bundle" else "Discard queue"

    val enabledState by rememberUpdatedState(enabled)
    val lockedState by rememberUpdatedState(locked)
    val onDragStartState by rememberUpdatedState(onDragStart)
    val onDragDeltaState by rememberUpdatedState(onDragDelta)
    val onDragEndState by rememberUpdatedState(onDragEnd)

    val handleThicknessDp = profile.handlerWidthDp.dp
    val handleLengthDp = profile.handlerLengthDp.dp
    val handleWidth = if (axis == QueueAxis.Horizontal) handleThicknessDp else handleLengthDp
    val handleHeight = if (axis == QueueAxis.Horizontal) handleLengthDp else handleThicknessDp

    val initiatorAlpha by animateFloatAsState(
        targetValue = if (isInitiator) (0.55f + progress * 0.45f) else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "initiator-swiper-alpha",
    )
    val flashAlpha by animateFloatAsState(
        targetValue = if (flashActive) 1f else 0f,
        animationSpec = tween(durationMillis = if (flashActive) 80 else 200),
        label = "flash-alpha",
    )
    val swiperAlpha = maxOf(initiatorAlpha, flashAlpha).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .pointerInput(endpoint, axis, enabled, locked) {
                if (!enabled || locked) return@pointerInput
                awaitEachGesture {
                    // Initial pass claims contact in the fixed handle target over any
                    // overflowing media underneath (`COMPONENT-CONTRACT.md` § Input priority).
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    down.consume()
                    onDragStartState()
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        if (!enabledState || lockedState || event.changes.count { it.pressed } > 1) {
                            cancelled = true
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: run {
                            cancelled = true
                            break
                        }
                        if (change.changedToUp()) {
                            change.consume()
                            break
                        }
                        val delta = change.positionChange()
                        val primaryDelta = if (axis == QueueAxis.Horizontal) delta.x else delta.y
                        change.consume()
                        onDragDeltaState(primaryDelta)
                    }
                    onDragEndState(cancelled)
                }
            }
            .semantics {
                contentDescription = label
                if (!enabled || locked) {
                    disabled()
                    customActions = emptyList()
                } else {
                    customActions = listOf(
                        CustomAccessibilityAction(label) { onAction(); true },
                        CustomAccessibilityAction(
                            if (isCommitHandle) "Save queue" else "Discard queue",
                        ) { onAction(); true },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (swiperAlpha > 0.01f) {
            // The unified 56dp/48dp rounded swiper pill background is drawn in `QueueTray`
            // so there is only ONE visual layer matching the thumbnail height and 8dp corner
            // radius. `EdgeZone` renders the crisp white Check / Close glyph centered in the
            // initiating handle target.
            Icon(
                imageVector = swiperIcon,
                contentDescription = null,
                tint = Color.White.copy(alpha = swiperAlpha),
                modifier = Modifier.size(24.dp),
            )
        } else {
            // Stationary handle pill (`queueEdgeHandleStandard` 6×36 / `queueEdgeHandle` 6×28,
            // rx=3, fill=#fff, fill-opacity=0.48 when enabled, 0.24 when empty/locked).
            val barColor = Color.White.copy(
                alpha = if (enabled && !locked) 0.48f else 0.24f,
            )
            Box(
                modifier = Modifier
                    .size(width = handleWidth, height = handleHeight)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun QueueTray(
    queue: List<StagedItem>,
    dividers: Set<Int>,
    axis: QueueAxis,
    profile: QueueProfileToken,
    locked: Boolean,
    gesture: GestureState,
    flashAction: EdgeAction?,
    tideProgress: Float,
    tiltDeg: Float,
    deleteProgress: Float,
    deleteHotspotPrimaryPx: Float,
    onTapItem: (StagedItem) -> Unit,
    onDelete: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onInsertDivider: (Int) -> Unit,
    onRemoveDivider: (Int) -> Unit,
    onCommitQueue: () -> Unit,
    onDiscardQueue: () -> Unit,
    onDeleteProgress: (progress: Float, hotspotXInRoot: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeEdge = gesture as? GestureState.ActiveEdge
    val effectiveAction = activeEdge?.action ?: flashAction
    val effectiveEndpoint = activeEdge?.endpoint ?: when (flashAction) {
        EdgeAction.Commit -> if (axis == QueueAxis.Horizontal) HandleEndpoint.Start else HandleEndpoint.End
        EdgeAction.Discard -> if (axis == QueueAxis.Horizontal) HandleEndpoint.End else HandleEndpoint.Start
        else -> null
    }
    val tideColor = when (effectiveAction) {
        EdgeAction.Commit -> CommitGreen
        EdgeAction.Discard -> DiscardAmber
        else -> Color.Transparent
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(queue.size, axis) {
        if (queue.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    val scrollModifier = if (axis == QueueAxis.Horizontal) {
        Modifier.horizontalScroll(scrollState, enabled = !locked && activeEdge == null)
    } else {
        Modifier.verticalScroll(scrollState, enabled = !locked && activeEdge == null)
    }

    val density = LocalDensity.current
    val fadeOuterPx = with(density) { 24.dp.toPx() }
    val fadeInnerPx = with(density) { profile.edgeTargetDp.dp.toPx() }
    val mediaCrossPx = with(density) { profile.mediaDp.dp.toPx() }
    val mediaCrossOffsetPx = with(density) {
        (QueueMetrics.InwardOverslopDp + profile.verticalPaddingDp).dp.toPx()
    }
    val cornerRadiusPx = with(density) { 8.dp.toPx() }

    BoxWithConstraints(modifier = modifier) {
        val primaryLengthPx = with(density) {
            if (axis == QueueAxis.Horizontal) maxWidth.toPx() else maxHeight.toPx()
        }
        val inwardFillPx = when {
            activeEdge != null -> {
                QueueGestureMath.inwardDisplacementPx(activeEdge.endpoint, activeEdge.netPrimaryDeltaPx)
                    .coerceIn(fadeInnerPx, primaryLengthPx)
            }
            flashAction != null -> primaryLengthPx
            else -> 0f
        }

        // Layer 0 (behind all media, `COMPONENT-CONTRACT.md` § Input, release and cancellation):
        // Gaussian red delete glow anchored to the outer edge of the queue's 24dp delete corridor,
        // oriented along `axis` (`Horizontal` bottom edge bulging upward; `Vertical` right edge
        // bulging leftward).
        if (deleteProgress > 0.01f) {
            DeleteGlow(
                progress = deleteProgress,
                hotspotPrimaryPx = deleteHotspotPrimaryPx,
                axis = axis,
                profile = profile,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (effectiveEndpoint != null && inwardFillPx > 0f && tideColor != Color.Transparent) {
            // Single unified rounded swiper pill matching the exact 56dp/48dp thumbnail
            // cross-axis thickness and 8dp corner radius (eliminating the double-layer
            // 72dp unrounded + 56dp rounded mismatch).
            val originAlpha = if (flashAction != null) {
                0.85f
            } else {
                (0.55f + tideProgress * 0.30f).coerceIn(0f, 0.85f)
            }
            val leadingAlpha = if (flashAction != null) 0.25f else 0.08f
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (axis == QueueAxis.Horizontal) {
                    val crossTop = mediaCrossOffsetPx
                    val fromStart = effectiveEndpoint == HandleEndpoint.Start
                    val startX = if (fromStart) 0f else (size.width - inwardFillPx).coerceAtLeast(0f)
                    val endX = if (fromStart) inwardFillPx.coerceAtMost(size.width) else size.width
                    val brush = Brush.horizontalGradient(
                        colors = if (fromStart) {
                            listOf(tideColor.copy(alpha = originAlpha), tideColor.copy(alpha = leadingAlpha))
                        } else {
                            listOf(tideColor.copy(alpha = leadingAlpha), tideColor.copy(alpha = originAlpha))
                        },
                        startX = startX,
                        endX = endX.coerceAtLeast(startX + 1f),
                    )
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(startX, crossTop),
                        size = Size((endX - startX).coerceAtLeast(1f), mediaCrossPx),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    )
                } else {
                    val crossLeft = mediaCrossOffsetPx
                    val fromStart = effectiveEndpoint == HandleEndpoint.Start
                    val startY = if (fromStart) 0f else (size.height - inwardFillPx).coerceAtLeast(0f)
                    val endY = if (fromStart) inwardFillPx.coerceAtMost(size.height) else size.height
                    val brush = Brush.verticalGradient(
                        colors = if (fromStart) {
                            listOf(tideColor.copy(alpha = originAlpha), tideColor.copy(alpha = leadingAlpha))
                        } else {
                            listOf(tideColor.copy(alpha = leadingAlpha), tideColor.copy(alpha = originAlpha))
                        },
                        startY = startY,
                        endY = endY.coerceAtLeast(startY + 1f),
                    )
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(crossLeft, startY),
                        size = Size(mediaCrossPx, (endY - startY).coerceAtLeast(1f)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    )
                }
            }
        }

        // Alpha-mask overflowing media beneath stationary handles (`COMPONENT-CONTRACT.md`:
        // transparent from outer edge to 24dp, ramp to fully opaque at 56dp/48dp).
        val canScrollBackward = scrollState.value > 0
        val canScrollForward = scrollState.value < scrollState.maxValue
        val needsOverflowMask = canScrollBackward || canScrollForward

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = if (needsOverflowMask) {
                        CompositingStrategy.Offscreen
                    } else {
                        CompositingStrategy.Auto
                    }
                }
                .drawWithContent {
                    drawContent()
                    val len = if (axis == QueueAxis.Horizontal) size.width else size.height
                    if (len > fadeInnerPx * 2f && needsOverflowMask) {
                        val f0 = (fadeOuterPx / len).coerceIn(0f, 0.45f)
                        val f1 = (fadeInnerPx / len).coerceIn(f0 + 0.01f, 0.49f)
                        val stops = buildList {
                            if (canScrollBackward) {
                                add(0f to Color.Transparent)
                                add(f0 to Color.Transparent)
                                add(f1 to Color.Black)
                            } else {
                                add(0f to Color.Black)
                            }
                            if (canScrollForward) {
                                add((1f - f1) to Color.Black)
                                add((1f - f0) to Color.Transparent)
                                add(1f to Color.Transparent)
                            } else {
                                add(1f to Color.Black)
                            }
                        }.toTypedArray()
                        val maskBrush = if (axis == QueueAxis.Horizontal) {
                            Brush.horizontalGradient(colorStops = stops)
                        } else {
                            Brush.verticalGradient(colorStops = stops)
                        }
                        drawRect(brush = maskBrush, blendMode = BlendMode.DstIn)
                    }
                },
        ) {
            QueueContent(
                queue = queue,
                dividers = dividers,
                axis = axis,
                profile = profile,
                locked = locked,
                viewportPrimaryPx = primaryLengthPx.roundToInt(),
                tiltDeg = tiltDeg,
                onTapItem = onTapItem,
                onDelete = onDelete,
                onReorder = onReorder,
                onInsertDivider = onInsertDivider,
                onRemoveDivider = onRemoveDivider,
                onCommitQueue = onCommitQueue,
                onDiscardQueue = onDiscardQueue,
                onDeleteProgress = onDeleteProgress,
                modifier = Modifier
                    .fillMaxSize()
                    .then(scrollModifier),
            )
        }
    }
}

@Composable
private fun QueueContent(
    queue: List<StagedItem>,
    dividers: Set<Int>,
    axis: QueueAxis,
    profile: QueueProfileToken,
    locked: Boolean,
    viewportPrimaryPx: Int,
    tiltDeg: Float,
    onTapItem: (StagedItem) -> Unit,
    onDelete: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onInsertDivider: (Int) -> Unit,
    onRemoveDivider: (Int) -> Unit,
    onCommitQueue: () -> Unit,
    onDiscardQueue: () -> Unit,
    onDeleteProgress: (progress: Float, hotspotXInRoot: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thumbPx = with(density) { profile.mediaDp.dp.roundToPx() }
    val baseGapPx = with(density) { profile.gapDp.dp.roundToPx() }
    val expandedGapPx = with(density) { QueueMetrics.DividerExpandedGap.roundToPx() }
    val edgeTargetPx = with(density) { profile.edgeTargetDp.dp.roundToPx() }
    val dividerHitSpanPx = with(density) { QueueMetrics.DividerHitWidth.roundToPx() }
    val crossOffsetPx = with(density) {
        (QueueMetrics.InwardOverslopDp + profile.verticalPaddingDp).dp.roundToPx()
    }
    val dividerCrossSpanPx = with(density) {
        (profile.stripCrossDp + QueueMetrics.InwardOverslopDp * 2f).dp.roundToPx()
    }
    val extraGapPx = (expandedGapPx - baseGapPx).coerceAtLeast(0)

    val dividerCount = (queue.size - 1).coerceAtLeast(0)
    val expansions = List(dividerCount) { i ->
        key(i) {
            animateFloatAsState(
                targetValue = if (dividers.contains(i)) 1f else 0f,
                animationSpec = tween(durationMillis = 200),
                label = "divider-expansion",
            ).value
        }
    }

    val slotCentersHolder = remember { SlotCentersHolder() }

    val thumbnailModifier = if (tiltDeg != 0f) {
        Modifier.graphicsLayer { rotationZ = tiltDeg }
    } else {
        Modifier
    }

    Layout(
        content = {
            queue.forEachIndexed { index, item ->
                key(item.id) {
                    QueueThumbnail(
                        item = item,
                        currentIndex = index,
                        queueSize = queue.size,
                        axis = axis,
                        profile = profile,
                        hasDividerAfter = index in dividers,
                        locked = locked,
                        slotCentersProvider = { slotCentersHolder.centers },
                        onTapOpenMenu = { onTapItem(item) },
                        onDelete = { onDelete(item.id) },
                        onReorderTo = { target -> onReorder(index, target) },
                        onToggleSplitAfter = {
                            if (index in dividers) onRemoveDivider(index) else onInsertDivider(index)
                        },
                        onCommitQueue = onCommitQueue,
                        onDiscardQueue = onDiscardQueue,
                        onDeleteProgress = onDeleteProgress,
                        modifier = thumbnailModifier,
                    )
                }
            }
            for (i in 0 until dividerCount) {
                key("div-$i") {
                    DividerZone(
                        gapIndex = i,
                        axis = axis,
                        profile = profile,
                        hasDivider = dividers.contains(i),
                        locked = locked,
                        onInsert = { onInsertDivider(i) },
                        onRemove = { onRemoveDivider(i) },
                    )
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val thumbCount = queue.size
        val crossBound = if (axis == QueueAxis.Horizontal) {
            constraints.maxHeight.coerceAtLeast(thumbPx)
        } else {
            constraints.maxWidth.coerceAtLeast(thumbPx)
        }

        val thumbPlaceables = List(thumbCount) { i ->
            measurables[i].measure(Constraints.fixed(thumbPx, thumbPx))
        }
        val divCross = dividerCrossSpanPx.coerceAtMost(crossBound)
        val dividerPlaceables = List(dividerCount) { i ->
            val divConstraints = if (axis == QueueAxis.Horizontal) {
                Constraints.fixed(dividerHitSpanPx, divCross)
            } else {
                Constraints.fixed(divCross, dividerHitSpanPx)
            }
            measurables[thumbCount + i].measure(divConstraints)
        }

        val gapPxByIndex = IntArray(dividerCount) { i ->
            baseGapPx + (extraGapPx * expansions[i]).toInt()
        }
        val totalGapPx = gapPxByIndex.sum()
        val mediaSpanPx = if (thumbCount == 0) 0 else thumbCount * thumbPx + totalGapPx

        // Reserve stationary handle target space at both ends (`edgeTargetPx`) and center
        // short queues inside the usable middle span (`[edgeTargetPx, viewportPrimaryPx - edgeTargetPx)`).
        val minEndpointPadPx = edgeTargetPx + baseGapPx
        val totalPrimaryPx = maxOf(viewportPrimaryPx, mediaSpanPx + minEndpointPadPx * 2)
        val startOffsetPx = if (thumbCount == 0) {
            minEndpointPadPx
        } else {
            val centered = (totalPrimaryPx - mediaSpanPx) / 2
            maxOf(minEndpointPadPx, centered)
        }

        val layoutW = if (axis == QueueAxis.Horizontal) totalPrimaryPx else crossBound
        val layoutH = if (axis == QueueAxis.Horizontal) crossBound else totalPrimaryPx

        layout(layoutW, layoutH) {
            var cursorPrimary = startOffsetPx
            val crossOffset = crossOffsetPx.coerceAtMost((crossBound - thumbPx).coerceAtLeast(0))
            val centers = FloatArray(thumbCount)

            for (i in 0 until thumbCount) {
                centers[i] = cursorPrimary + thumbPx / 2f
                if (axis == QueueAxis.Horizontal) {
                    thumbPlaceables[i].place(cursorPrimary, crossOffset)
                } else {
                    thumbPlaceables[i].place(crossOffset, cursorPrimary)
                }
                cursorPrimary += thumbPx
                if (i < dividerCount) {
                    val gap = gapPxByIndex[i]
                    val gapCenter = cursorPrimary + gap / 2
                    if (axis == QueueAxis.Horizontal) {
                        dividerPlaceables[i].place(gapCenter - dividerHitSpanPx / 2, 0)
                    } else {
                        dividerPlaceables[i].place(0, gapCenter - dividerHitSpanPx / 2)
                    }
                    cursorPrimary += gap
                }
            }
            slotCentersHolder.centers = centers
        }
    }
}

private class SlotCentersHolder(@Volatile var centers: FloatArray = FloatArray(0))

@Composable
private fun DividerZone(
    gapIndex: Int,
    axis: QueueAxis,
    profile: QueueProfileToken,
    hasDivider: Boolean,
    locked: Boolean,
    onInsert: () -> Unit,
    onRemove: () -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { QueueGestureMath.DIVIDER_GATE_DP.dp.toPx() }
    val claimSlopPx = with(density) { 3.dp.toPx() }
    val rejectScrollSlopPx = with(density) { 10.dp.toPx() }
    val view = LocalView.current

    var dragCrossPx by remember(gapIndex, axis, hasDivider) { mutableFloatStateOf(0f) }
    val activeSign = if (hasDivider) -1f else 1f
    val activeProgress = (dragCrossPx * activeSign / thresholdPx).coerceIn(0f, 1f)
    val rawAlpha = if (hasDivider) 1f - activeProgress else activeProgress
    val actionLabel = if (hasDivider) {
        "Remove split after item ${gapIndex + 1}"
    } else {
        "Split after item ${gapIndex + 1}"
    }

    val spanModifier = if (axis == QueueAxis.Horizontal) {
        Modifier
            .fillMaxHeight()
            .width(QueueMetrics.DividerHitWidth)
    } else {
        Modifier
            .fillMaxWidth()
            .height(QueueMetrics.DividerHitWidth)
    }

    Box(
        modifier = spanModifier
            .semantics {
                contentDescription = actionLabel
                if (locked) {
                    disabled()
                    customActions = emptyList()
                } else {
                    customActions = listOf(
                        CustomAccessibilityAction(actionLabel) {
                            if (hasDivider) onRemove() else onInsert()
                            true
                        },
                    )
                }
            }
            .pointerInput(hasDivider, axis, locked) {
                if (locked) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    dragCrossPx = 0f
                    var totalPrimaryPx = 0f
                    var claimed = false
                    var rejectedForPrimaryScroll = false
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } > 1) {
                            cancelled = true
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: run {
                            cancelled = true
                            break
                        }
                        if (change.changedToUp()) {
                            if (claimed) {
                                change.consume()
                                if (QueueGestureMath.shouldCommitDividerToggle(
                                        netCrossPx = dragCrossPx,
                                        hasDivider = hasDivider,
                                        dividerGatePx = thresholdPx,
                                        isCancelled = cancelled,
                                        isLocked = locked,
                                    )
                                ) {
                                    val haptic = if (hasDivider) {
                                        HapticFeedbackConstants.CLOCK_TICK
                                    } else {
                                        HapticFeedbackConstants.CONTEXT_CLICK
                                    }
                                    view.performHapticFeedback(haptic)
                                    if (hasDivider) onRemove() else onInsert()
                                }
                            }
                            dragCrossPx = 0f
                            break
                        }
                        val delta = change.positionChange()
                        val dPrimary = if (axis == QueueAxis.Horizontal) delta.x else delta.y
                        val dCross = if (axis == QueueAxis.Horizontal) delta.y else delta.x
                        totalPrimaryPx += dPrimary
                        dragCrossPx += dCross

                        if (!claimed && !rejectedForPrimaryScroll) {
                            if (abs(totalPrimaryPx) >= rejectScrollSlopPx &&
                                abs(totalPrimaryPx) > abs(dragCrossPx)
                            ) {
                                rejectedForPrimaryScroll = true
                            } else if (dragCrossPx * activeSign >= claimSlopPx &&
                                abs(dragCrossPx) >= abs(totalPrimaryPx) * 0.8f
                            ) {
                                // Claim cross-axis motion in PointerEventPass.Initial at 3dp
                                // so `QueueThumbnail` in PointerEventPass.Main never steals a
                                // split/unsplit gesture starting in the 11dp tile overlap zone.
                                claimed = true
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        if (claimed) change.consume()
                    }
                    dragCrossPx = 0f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (rawAlpha > 0.01f) {
            val barModifier = if (axis == QueueAxis.Horizontal) {
                Modifier
                    .width(QueueMetrics.DividerVisualThickness)
                    .height(profile.mediaDp.dp)
            } else {
                Modifier
                    .height(QueueMetrics.DividerVisualThickness)
                    .width(profile.mediaDp.dp)
            }
            Box(
                modifier = barModifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = rawAlpha)),
            )
        }
    }
}

/**
 * Accessible direct-action menu (`queueActionMenu` in `COMPONENT-CONTRACT.md`):
 * Tapping a thumbnail opens this modal with `312 × 48dp` rows:
 * `Remove item`, `Move earlier`, `Move later`, `Split after` / `Remove split`
 * (omitted on the final item), `Save queue`, `Discard queue`.
 */
@Composable
private fun QueueActionMenuDialog(
    item: StagedItem,
    index: Int,
    queueSize: Int,
    hasDividerAfter: Boolean,
    onDismiss: () -> Unit,
    onRemoveItem: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onToggleSplitAfter: () -> Unit,
    onSaveQueue: () -> Unit,
    onDiscardQueue: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(312.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E),
            contentColor = Color.White,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = "${item.mediaTypeLabel} ${index + 1} of $queueSize",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                QueueMenuActionRow(
                    label = "Remove item",
                    enabled = true,
                    textColor = CaptureColors.DeleteRed,
                    onClick = onRemoveItem,
                )
                QueueMenuActionRow(
                    label = "Move earlier",
                    enabled = index > 0,
                    onClick = onMoveEarlier,
                )
                QueueMenuActionRow(
                    label = "Move later",
                    enabled = index < queueSize - 1,
                    onClick = onMoveLater,
                )
                if (index < queueSize - 1) {
                    QueueMenuActionRow(
                        label = if (hasDividerAfter) "Remove split" else "Split after",
                        enabled = true,
                        onClick = onToggleSplitAfter,
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                QueueMenuActionRow(
                    label = "Save queue",
                    enabled = true,
                    textColor = CommitGreen,
                    onClick = onSaveQueue,
                )
                QueueMenuActionRow(
                    label = "Discard queue",
                    enabled = true,
                    textColor = DiscardAmber,
                    onClick = onDiscardQueue,
                )
            }
        }
    }
}

@Composable
private fun QueueMenuActionRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    textColor: Color = Color.White,
) {
    Box(
        modifier = Modifier
            .width(312.dp)
            .height(48.dp)
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) textColor else Color.White.copy(alpha = 0.35f),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FlyingQueue(
    snapshot: ExitingSnapshot,
    profile: QueueProfileToken,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val targetOffsetPx = with(density) { 360.dp.toPx() }

    val offsetPrimary = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }

    LaunchedEffect(snapshot) {
        offsetPrimary.snapTo(0f)
        alpha.snapTo(1f)
        scale.snapTo(1f)
        val sign = when (snapshot.axis) {
            QueueAxis.Horizontal -> if (snapshot.action == EdgeAction.Commit) 1f else -1f
            QueueAxis.Vertical -> if (snapshot.action == EdgeAction.Commit) -1f else 1f
        }
        val signed = sign * targetOffsetPx
        coroutineScope {
            launch {
                offsetPrimary.animateTo(
                    targetValue = signed,
                    animationSpec = tween(
                        durationMillis = EXIT_ANIMATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
            launch {
                scale.animateTo(
                    targetValue = 0.6f,
                    animationSpec = tween(durationMillis = EXIT_ANIMATION_MS),
                )
            }
            launch {
                delay((EXIT_ANIMATION_MS * 0.3).toLong())
                alpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = (EXIT_ANIMATION_MS * 0.7).toInt(),
                    ),
                )
            }
        }
    }

    val layerMod = modifier.graphicsLayer {
        if (snapshot.axis == QueueAxis.Horizontal) {
            translationX = offsetPrimary.value
        } else {
            translationY = offsetPrimary.value
        }
        this.alpha = alpha.value
        scaleX = scale.value
        scaleY = scale.value
    }

    val mediaCrossOffsetDp = (QueueMetrics.InwardOverslopDp + profile.verticalPaddingDp).dp

    if (snapshot.axis == QueueAxis.Horizontal) {
        Row(
            modifier = layerMod.padding(start = 8.dp, end = 8.dp, top = mediaCrossOffsetDp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            snapshot.thumbnails.forEach { thumb ->
                FlyingThumbBox(thumb, profile)
            }
        }
    } else {
        Column(
            modifier = layerMod.padding(top = 8.dp, bottom = 8.dp, start = mediaCrossOffsetDp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            snapshot.thumbnails.forEach { thumb ->
                FlyingThumbBox(thumb, profile)
            }
        }
    }
}

@Composable
private fun FlyingThumbBox(
    thumb: androidx.compose.ui.graphics.ImageBitmap,
    profile: QueueProfileToken,
) {
    Box(
        modifier = Modifier
            .size(profile.mediaDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray),
    ) {
        Image(
            bitmap = thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Red Gaussian bulge along the outer delete edge of the queue corridor (`COMPONENT-CONTRACT.md`
 * § Input, release and cancellation):
 * - Painted behind all media inside `QueueTray`
 * - Parameterized by [axis] (`QueueAxis.Horizontal`: along the bottom edge of the corridor,
 *   bulging upward with peak at `x = [hotspotPrimaryPx]`; `QueueAxis.Vertical`: along the right
 *   edge of the corridor, bulging leftward with peak at `y = [hotspotPrimaryPx]`)
 * - Peak extent scales with [progress] across `QueueMetrics.DeleteBulgeMaxCrossDp` (`24dp`)
 *   and uses a multi-stop red gradient so the upper body of the Gaussian hill remains vividly
 *   visible behind the dragged thumbnail while feathering cleanly at its tip and queue endpoints.
 */
@Composable
fun DeleteGlow(
    progress: Float,
    hotspotPrimaryPx: Float,
    modifier: Modifier = Modifier,
    axis: QueueAxis = QueueAxis.Horizontal,
    profile: QueueProfileToken = QueueProfileToken.Standard56,
) {
    val density = LocalDensity.current
    val mediaPx = with(density) { profile.mediaDp.dp.toPx() }
    val maxBulgePx = with(density) { QueueMetrics.DeleteBulgeMaxCrossDp.toPx() }
    val featherPx = with(density) { QueueMetrics.DeleteGlowEndpointFeatherDp.toPx() }
    val stepPx = with(density) { 2.dp.toPx() }
    val p = progress.coerceIn(0f, 1f)

    Spacer(
        modifier = modifier.drawWithCache {
            val width = size.width
            val height = size.height
            val peakExtentPx = QueueGestureMath.deleteGlowPeakExtentPx(p, maxBulgePx)
            val path = Path().apply {
                if (width > 0f && height > 0f && peakExtentPx > 0.5f) {
                    if (axis == QueueAxis.Horizontal) {
                        moveTo(0f, height)
                        var x = 0f
                        while (x < width) {
                            val factor = QueueGestureMath.deleteGlowBulgeFactor(
                                primaryPx = x,
                                hotspotPrimaryPx = hotspotPrimaryPx,
                                primaryLengthPx = width,
                                mediaPx = mediaPx,
                                endpointFeatherPx = featherPx,
                            )
                            lineTo(x, height - peakExtentPx * factor)
                            x += stepPx
                        }
                        lineTo(width, height)
                        close()
                    } else {
                        moveTo(width, 0f)
                        var y = 0f
                        while (y < height) {
                            val factor = QueueGestureMath.deleteGlowBulgeFactor(
                                primaryPx = y,
                                hotspotPrimaryPx = hotspotPrimaryPx,
                                primaryLengthPx = height,
                                mediaPx = mediaPx,
                                endpointFeatherPx = featherPx,
                            )
                            lineTo(width - peakExtentPx * factor, y)
                            y += stepPx
                        }
                        lineTo(width, height)
                        close()
                    }
                }
            }
            val colorStops = arrayOf(
                0.00f to Color.Transparent,
                0.25f to CaptureColors.DeleteRed.copy(alpha = 0.52f * p),
                0.65f to CaptureColors.DeleteRed.copy(alpha = 0.78f * p),
                1.00f to CaptureColors.DeleteRed.copy(alpha = 0.88f * p),
            )
            val brush = if (axis == QueueAxis.Horizontal) {
                val startY = (height - peakExtentPx).coerceAtLeast(0f)
                Brush.verticalGradient(
                    colorStops = colorStops,
                    startY = startY,
                    endY = height.coerceAtLeast(startY + 1f),
                )
            } else {
                val startX = (width - peakExtentPx).coerceAtLeast(0f)
                Brush.horizontalGradient(
                    colorStops = colorStops,
                    startX = startX,
                    endX = width.coerceAtLeast(startX + 1f),
                )
            }
            onDrawBehind {
                if (peakExtentPx > 0.5f) {
                    drawPath(path = path, brush = brush)
                }
            }
        },
    )
}
