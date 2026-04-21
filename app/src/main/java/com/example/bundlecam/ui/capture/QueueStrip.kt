package com.example.bundlecam.ui.capture

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

private val CommitGreen = CaptureColors.CommitGreen
private val DiscardAmber = CaptureColors.DiscardAmber
private const val EXIT_ANIMATION_MS = 350
private const val FLASH_DURATION_MS = 220L

internal object QueueMetrics {
    val ThumbSize = 56.dp
    val ThumbGap = 6.dp
    val OuterPadding = 8.dp
    val DividerHitWidth = 24.dp
    val SlotSize = ThumbSize + ThumbGap
    val TrayHorizontalPadding = 48.dp
    val EdgeZoneMinWidth = 60.dp
    const val EdgeZoneMaxFraction = 0.33f
    val EdgeZoneMiddleGuard = 24.dp
}

private sealed class GestureState {
    object Idle : GestureState()
    data class Bundling(val dragX: Float, val wasAboveThreshold: Boolean) : GestureState()
    data class Discarding(val dragX: Float, val wasAboveThreshold: Boolean) : GestureState()
}

private data class ExitingSnapshot(
    val thumbnails: List<androidx.compose.ui.graphics.ImageBitmap>,
    val side: Side,
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
    onDeleteProgress: (progress: Float, hotspotXInRoot: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = queue.isNotEmpty()
    val view = LocalView.current
    val density = LocalDensity.current

    var gesture by remember { mutableStateOf<GestureState>(GestureState.Idle) }
    var exiting by remember { mutableStateOf<ExitingSnapshot?>(null) }
    var flashSide by remember { mutableStateOf<Side?>(null) }

    LaunchedEffect(exiting) {
        val snap = exiting ?: return@LaunchedEffect
        delay(EXIT_ANIMATION_MS.toLong())
        if (exiting == snap) exiting = null
    }
    LaunchedEffect(flashSide) {
        val side = flashSide ?: return@LaunchedEffect
        delay(FLASH_DURATION_MS)
        if (flashSide == side) flashSide = null
    }

    val commitWithAnimation = {
        if (queue.isNotEmpty()) {
            exiting = ExitingSnapshot(queue.map { it.thumbnail }, Side.Right)
            flashSide = Side.Right
        }
        onCommit()
    }
    val discardWithAnimation = {
        if (queue.isNotEmpty()) {
            exiting = ExitingSnapshot(queue.map { it.thumbnail }, Side.Left)
            flashSide = Side.Left
        }
        onDiscard()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        val thresholdPx = with(density) { (maxWidth / 2).toPx() }
        // Tuned below M3's 125.dp/s default so a natural swipe doesn't get cancelled.
        val velocityThresholdPx = with(density) { 80.dp.toPx() }

        // Edge zones grow to reclaim empty tray pixels on short queues, so commit/discard
        // can be initiated from a wider region when thumbs don't fill the tray. Cap at a
        // third of screen width per side with a neutral middle strip so the two zones
        // never meet.
        val contentWidth = if (queue.isEmpty()) 0.dp
            else QueueMetrics.OuterPadding * 2 +
                 QueueMetrics.ThumbSize * queue.size +
                 QueueMetrics.ThumbGap * (queue.size - 1)
        val trayWidth = maxWidth - QueueMetrics.TrayHorizontalPadding * 2
        val slack = (trayWidth - contentWidth).coerceAtLeast(0.dp)
        val targetEdgeZoneWidth = (QueueMetrics.EdgeZoneMinWidth + slack / 2)
            .coerceAtMost(maxWidth * QueueMetrics.EdgeZoneMaxFraction)
            .coerceAtMost(maxWidth / 2 - QueueMetrics.EdgeZoneMiddleGuard)
        val edgeZoneWidth by animateDpAsState(
            targetValue = targetEdgeZoneWidth,
            animationSpec = tween(durationMillis = 180, easing = LinearEasing),
            label = "edge-zone-width",
        )

        val progress = when (val g = gesture) {
            GestureState.Idle -> 0f
            is GestureState.Bundling -> (g.dragX / thresholdPx).coerceIn(0f, 1f)
            is GestureState.Discarding -> (-g.dragX / thresholdPx).coerceIn(0f, 1f)
        }
        val animatedProgress by animateFloatAsState(
            targetValue = progress,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "queue-gesture-progress",
        )
        val tiltDeg by animateFloatAsState(
            targetValue = when (gesture) {
                is GestureState.Bundling -> progress * 3f
                is GestureState.Discarding -> -progress * 3f
                else -> 0f
            },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "queue-tilt",
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = QueueMetrics.TrayHorizontalPadding),
            ) {
                val snapshot = exiting
                if (snapshot == null) {
                    QueueTray(
                        queue = queue,
                        dividers = dividers,
                        gesture = gesture,
                        tideProgress = animatedProgress,
                        tiltDeg = tiltDeg,
                        onDelete = onDelete,
                        onReorder = onReorder,
                        onInsertDivider = onInsertDivider,
                        onRemoveDivider = onRemoveDivider,
                        onDeleteProgress = onDeleteProgress,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    FlyingQueue(
                        snapshot = snapshot,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            EdgeZone(
                side = Side.Left,
                gesture = gesture,
                progress = animatedProgress,
                enabled = enabled,
                flashActive = flashSide == Side.Left,
                label = "Commit bundle",
                onAction = commitWithAnimation,
                onDragStart = {
                    gesture = GestureState.Bundling(dragX = 0f, wasAboveThreshold = false)
                },
                onDrag = { delta ->
                    val g = gesture
                    if (g is GestureState.Bundling) {
                        val newDx = (g.dragX + delta).coerceAtLeast(0f)
                        val isAboveThreshold = newDx >= thresholdPx
                        if (isAboveThreshold && !g.wasAboveThreshold) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        gesture = GestureState.Bundling(
                            dragX = newDx,
                            wasAboveThreshold = isAboveThreshold,
                        )
                    }
                },
                onDragRelease = { velocity ->
                    val g = gesture
                    if (g is GestureState.Bundling &&
                        (g.dragX >= thresholdPx || velocity >= velocityThresholdPx)
                    ) {
                        commitWithAnimation()
                    }
                    gesture = GestureState.Idle
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(edgeZoneWidth)
                    .fillMaxHeight()
                    .systemGestureExclusion(),
            )

            EdgeZone(
                side = Side.Right,
                gesture = gesture,
                progress = animatedProgress,
                enabled = enabled,
                flashActive = flashSide == Side.Right,
                label = "Discard queue",
                onAction = discardWithAnimation,
                onDragStart = {
                    gesture = GestureState.Discarding(dragX = 0f, wasAboveThreshold = false)
                },
                onDrag = { delta ->
                    val g = gesture
                    if (g is GestureState.Discarding) {
                        val newDx = (g.dragX + delta).coerceAtMost(0f)
                        val isAboveThreshold = -newDx >= thresholdPx
                        if (isAboveThreshold && !g.wasAboveThreshold) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        gesture = GestureState.Discarding(
                            dragX = newDx,
                            wasAboveThreshold = isAboveThreshold,
                        )
                    }
                },
                onDragRelease = { velocity ->
                    val g = gesture
                    if (g is GestureState.Discarding &&
                        (-g.dragX >= thresholdPx || -velocity >= velocityThresholdPx)
                    ) {
                        discardWithAnimation()
                    }
                    gesture = GestureState.Idle
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(edgeZoneWidth)
                    .fillMaxHeight()
                    .systemGestureExclusion(),
            )
        }
    }
}

private enum class Side { Left, Right }

@Composable
private fun EdgeZone(
    side: Side,
    gesture: GestureState,
    progress: Float,
    enabled: Boolean,
    flashActive: Boolean,
    label: String,
    onAction: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragRelease: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val velocityTracker = remember { VelocityTracker() }
    val isInitiator = when (side) {
        Side.Left -> gesture is GestureState.Bundling
        Side.Right -> gesture is GestureState.Discarding
    }
    val isDestination = when (side) {
        Side.Left -> gesture is GestureState.Discarding
        Side.Right -> gesture is GestureState.Bundling
    }
    val accent = when (side) {
        Side.Left -> CommitGreen
        Side.Right -> DiscardAmber
    }
    val destinationAccent = when (side) {
        Side.Left -> DiscardAmber
        Side.Right -> CommitGreen
    }
    val destinationGlyph = when (side) {
        Side.Left -> "✕"
        Side.Right -> "✓"
    }

    val handleWidth by animateFloatAsState(
        targetValue = if (isInitiator) 10f else 4f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "handle-width",
    )
    val handleHeight by animateFloatAsState(
        targetValue = if (isInitiator) 56f else 44f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "handle-height",
    )
    val destinationAlpha by animateFloatAsState(
        targetValue = if (isDestination) (0.35f + progress * 0.5f) else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "destination-alpha",
    )
    val flashAlpha by animateFloatAsState(
        targetValue = if (flashActive) 1f else 0f,
        animationSpec = tween(durationMillis = if (flashActive) 80 else 200),
        label = "flash-alpha",
    )

    Box(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        velocityTracker.resetTracking()
                        onDragStart()
                    },
                    onDragEnd = { onDragRelease(velocityTracker.calculateVelocity().x) },
                    onDragCancel = { onDragRelease(velocityTracker.calculateVelocity().x) },
                    onHorizontalDrag = { change, delta ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        onDrag(delta)
                    },
                )
            }
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
                if (enabled) {
                    customActions = listOf(
                        CustomAccessibilityAction(label) { onAction(); true },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val destBgAlpha = maxOf(destinationAlpha, flashAlpha)
        val destGlyphAlpha = maxOf(
            if (isDestination) progress.coerceIn(0f, 1f) else 0f,
            flashAlpha,
        )
        val handleAlignment = when (side) {
            Side.Left -> Alignment.CenterStart
            Side.Right -> Alignment.CenterEnd
        }
        if (destBgAlpha > 0f) {
            Box(
                modifier = Modifier
                    .align(handleAlignment)
                    .width(QueueMetrics.EdgeZoneMinWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(destinationAccent.copy(alpha = destBgAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                if (destGlyphAlpha > 0.1f) {
                    Text(
                        text = destinationGlyph,
                        color = Color.White.copy(alpha = destGlyphAlpha.coerceIn(0f, 1f)),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else {
            val barColor = when {
                !enabled -> Color.White.copy(alpha = 0.25f)
                isInitiator -> accent
                else -> Color.White.copy(alpha = 0.85f)
            }
            Box(
                modifier = Modifier
                    .align(handleAlignment)
                    .padding(horizontal = 10.dp)
                    .size(width = handleWidth.dp, height = handleHeight.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun QueueTray(
    queue: List<StagedItem>,
    dividers: Set<Int>,
    gesture: GestureState,
    tideProgress: Float,
    tiltDeg: Float,
    onDelete: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onInsertDivider: (Int) -> Unit,
    onRemoveDivider: (Int) -> Unit,
    onDeleteProgress: (progress: Float, hotspotXInRoot: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val direction = when (gesture) {
        is GestureState.Bundling -> 1
        is GestureState.Discarding -> -1
        else -> 0
    }
    val tideColor = when (gesture) {
        is GestureState.Bundling -> CommitGreen
        is GestureState.Discarding -> DiscardAmber
        else -> Color.Transparent
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(queue.size) {
        if (queue.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val dragX = when (gesture) {
            is GestureState.Bundling -> gesture.dragX
            is GestureState.Discarding -> gesture.dragX
            else -> 0f
        }
        val fillWidth = abs(dragX).coerceAtMost(widthPx)

        if (direction != 0 && fillWidth > 0f) {
            val leadingAlpha = 0.25f + tideProgress * 0.55f
            val trailingAlpha = 0.03f
            Canvas(modifier = Modifier.fillMaxSize()) {
                val startX = if (direction > 0) 0f else size.width - fillWidth
                val endX = if (direction > 0) fillWidth else size.width
                val brush = Brush.horizontalGradient(
                    colors = if (direction > 0) {
                        // Bundle: finger at endX (leading), handle at startX (trailing).
                        listOf(tideColor.copy(alpha = trailingAlpha), tideColor.copy(alpha = leadingAlpha))
                    } else {
                        // Discard: finger at startX (leading), handle at endX (trailing).
                        listOf(tideColor.copy(alpha = leadingAlpha), tideColor.copy(alpha = trailingAlpha))
                    },
                    startX = startX,
                    endX = endX,
                )
                drawRect(
                    brush = brush,
                    topLeft = Offset(startX, 0f),
                    size = Size(endX - startX, size.height),
                )
            }
        }

        QueueContent(
            queue = queue,
            dividers = dividers,
            tiltDeg = tiltDeg,
            onDelete = onDelete,
            onReorder = onReorder,
            onInsertDivider = onInsertDivider,
            onRemoveDivider = onRemoveDivider,
            onDeleteProgress = onDeleteProgress,
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState),
        )
    }
}

@Composable
private fun QueueContent(
    queue: List<StagedItem>,
    dividers: Set<Int>,
    tiltDeg: Float,
    onDelete: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onInsertDivider: (Int) -> Unit,
    onRemoveDivider: (Int) -> Unit,
    onDeleteProgress: (progress: Float, hotspotXInRoot: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thumbPx = with(density) { QueueMetrics.ThumbSize.roundToPx() }
    val baseGapPx = with(density) { QueueMetrics.ThumbGap.roundToPx() }
    val expandedGapPx = with(density) { QueueMetrics.DividerHitWidth.roundToPx() }
    val outerPadPx = with(density) { QueueMetrics.OuterPadding.roundToPx() }
    val dividerWidthPx = with(density) { QueueMetrics.DividerHitWidth.roundToPx() }
    val extraGapPx = (expandedGapPx - baseGapPx).coerceAtLeast(0)

    val dividerCount = (queue.size - 1).coerceAtLeast(0)
    // Animated 0..1 expansion per gap: 0 → base 6dp gap, 1 → 24dp gap.
    // Using key(i) gives each gap a stable Animatable across recompositions.
    val expansions = List(dividerCount) { i ->
        key(i) {
            animateFloatAsState(
                targetValue = if (dividers.contains(i)) 1f else 0f,
                animationSpec = tween(durationMillis = 200),
                label = "divider-expansion",
            ).value
        }
    }

    // Only wrap thumbnails in a tilt layer during the edge-swipe gesture. At rest
    // an identity graphicsLayer still forces an offscreen compositing pass whose
    // edge sampling differs from the direct render — that shift is visible as a
    // ~1px widening when an adjacent gap changes color behind the thumbnail.
    val thumbnailModifier = if (tiltDeg != 0f) {
        Modifier.graphicsLayer { rotationZ = tiltDeg }
    } else {
        Modifier
    }

    Layout(
        content = {
            queue.forEachIndexed { index, photo ->
                key(photo.id) {
                    QueueThumbnail(
                        item = photo,
                        currentIndex = index,
                        queueSize = queue.size,
                        onDelete = { onDelete(photo.id) },
                        onReorderTo = { target -> onReorder(index, target) },
                        onDeleteProgress = onDeleteProgress,
                        modifier = thumbnailModifier,
                    )
                }
            }
            for (i in 0 until dividerCount) {
                key("div-$i") {
                    DividerZone(
                        hasDivider = dividers.contains(i),
                        onInsert = { onInsertDivider(i) },
                        onRemove = { onRemoveDivider(i) },
                    )
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val thumbCount = queue.size
        val parentHeight = constraints.maxHeight

        val thumbPlaceables = List(thumbCount) { i ->
            measurables[i].measure(Constraints.fixed(thumbPx, thumbPx))
        }
        val dividerPlaceables = List(dividerCount) { i ->
            measurables[thumbCount + i].measure(Constraints.fixed(dividerWidthPx, parentHeight))
        }

        val gapPxByIndex = IntArray(dividerCount) { i ->
            baseGapPx + (extraGapPx * expansions[i]).toInt()
        }
        val totalGapPx = gapPxByIndex.sum()

        val totalWidth = if (thumbCount == 0) {
            0
        } else {
            outerPadPx + thumbCount * thumbPx + totalGapPx + outerPadPx
        }

        layout(totalWidth, parentHeight) {
            var cursorX = outerPadPx
            val thumbY = ((parentHeight - thumbPx) / 2).coerceAtLeast(0)
            for (i in 0 until thumbCount) {
                thumbPlaceables[i].place(cursorX, thumbY)
                cursorX += thumbPx
                if (i < dividerCount) {
                    val gap = gapPxByIndex[i]
                    val gapCenterX = cursorX + gap / 2
                    dividerPlaceables[i].place(gapCenterX - dividerWidthPx / 2, 0)
                    cursorX += gap
                }
            }
        }
    }
}

@Composable
private fun DividerZone(
    hasDivider: Boolean,
    onInsert: () -> Unit,
    onRemove: () -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 24.dp.toPx() }
    val slopPx = with(density) { 8.dp.toPx() }
    val view = LocalView.current

    var dragY by remember { mutableFloatStateOf(0f) }
    val lineColor = Color.White

    // Sign flips the "active" drag direction: swipe down inserts, swipe up removes.
    val activeSign = if (hasDivider) -1f else 1f
    val activeProgress = (dragY * activeSign / thresholdPx).coerceIn(0f, 1f)
    val rawAlpha = if (hasDivider) 1f - activeProgress else activeProgress

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(QueueMetrics.DividerHitWidth)
            .pointerInput(hasDivider) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    dragY = 0f
                    var claimed = false
                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) {
                            if (dragY * activeSign >= thresholdPx) {
                                val haptic = if (hasDivider) HapticFeedbackConstants.CLOCK_TICK
                                             else HapticFeedbackConstants.CONTEXT_CLICK
                                view.performHapticFeedback(haptic)
                                if (hasDivider) onRemove() else onInsert()
                            }
                            dragY = 0f
                            break
                        }
                        dragY += change.positionChange().y
                        if (!claimed && dragY * activeSign >= slopPx) claimed = true
                        if (claimed) change.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (rawAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight(0.75f)
                    .clip(RoundedCornerShape(1.dp))
                    .background(lineColor.copy(alpha = rawAlpha)),
            )
        }
    }
}

@Composable
private fun FlyingQueue(
    snapshot: ExitingSnapshot,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val targetOffsetPx = with(density) { 360.dp.toPx() }

    val offsetX = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }

    LaunchedEffect(snapshot) {
        offsetX.snapTo(0f)
        alpha.snapTo(1f)
        scale.snapTo(1f)
        val signed = if (snapshot.side == Side.Right) targetOffsetPx else -targetOffsetPx
        coroutineScope {
            launch {
                offsetX.animateTo(
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

    Row(
        modifier = modifier
            .graphicsLayer {
                translationX = offsetX.value
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        snapshot.thumbnails.forEach { thumb ->
            Box(
                modifier = Modifier
                    .size(56.dp)
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
    }
}

/**
 * Red glow at the bottom of the screen. Its upper edge follows a Gaussian curve
 * `exp(-dx²/2σ²)` peaking under [hotspotXInRoot]. The caller must place this
 * composable so its local x-origin aligns with root x=0 (fillMaxWidth aligned
 * to BottomCenter is fine); hotspotXInRoot is then used directly as the peak x.
 */
@Composable
fun DeleteGlow(
    progress: Float,
    hotspotXInRoot: Float,
    modifier: Modifier = Modifier,
) {
    // Sigma equal to one thumbnail makes the bell's FWHM ≈ 132dp — wide enough
    // to read as "this photo" without spilling across neighbours.
    val sigmaPx = with(LocalDensity.current) { QueueMetrics.ThumbSize.toPx() }
    val stepPx = with(LocalDensity.current) { 2.dp.toPx() }
    Spacer(
        modifier = modifier.drawWithCache {
            val width = size.width
            val height = size.height
            val path = Path().apply {
                if (width > 0f && height > 0f) {
                    moveTo(0f, height)
                    var x = 0f
                    while (x <= width) {
                        val dx = x - hotspotXInRoot
                        val factor = exp(-(dx * dx) / (2f * sigmaPx * sigmaPx))
                        lineTo(x, height - height * factor)
                        x += stepPx
                    }
                    lineTo(width, height)
                    close()
                }
            }
            onDrawBehind {
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            CaptureColors.DeleteRed.copy(alpha = progress * MAX_GLOW_ALPHA),
                        ),
                        startY = 0f,
                        endY = height,
                    ),
                )
            }
        },
    )
}

private const val MAX_GLOW_ALPHA = 0.7f
