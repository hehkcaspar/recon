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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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

private val CommitGreen = CaptureColors.CommitGreen
private val DiscardAmber = CaptureColors.DiscardAmber
private const val EXIT_ANIMATION_MS = 350
private const val FLASH_DURATION_MS = 220L

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
    queue: List<StagedPhoto>,
    onCommit: () -> Unit,
    onDiscard: () -> Unit,
    onDelete: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
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
        // 125.dp/s matches M3 SwipeToDismissBoxDefaults.velocityThreshold.
        val velocityThresholdPx = with(density) { 125.dp.toPx() }

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
                    .padding(horizontal = 48.dp),
            ) {
                if (exiting == null) {
                    QueueTray(
                        queue = queue,
                        gesture = gesture,
                        tideProgress = animatedProgress,
                        tiltDeg = tiltDeg,
                        onDelete = onDelete,
                        onReorder = onReorder,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    FlyingQueue(
                        snapshot = exiting!!,
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
                    .width(60.dp)
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
                    .width(60.dp)
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
                    onDragCancel = { onDragRelease(0f) },
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
        if (isDestination) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(destinationAccent.copy(alpha = destinationAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                if (progress > 0.1f) {
                    Text(
                        text = destinationGlyph,
                        color = Color.White.copy(alpha = progress.coerceIn(0f, 1f)),
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
            val handleAlignment = when (side) {
                Side.Left -> Alignment.CenterStart
                Side.Right -> Alignment.CenterEnd
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

        if (flashAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = flashAlpha)),
            )
        }
    }
}

@Composable
private fun QueueTray(
    queue: List<StagedPhoto>,
    gesture: GestureState,
    tideProgress: Float,
    tiltDeg: Float,
    onDelete: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
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
        val dragX = when (val g = gesture) {
            is GestureState.Bundling -> g.dragX
            is GestureState.Discarding -> g.dragX
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

        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scrollState)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            queue.forEachIndexed { index, photo ->
                key(photo.id) {
                    QueueThumbnail(
                        item = photo,
                        currentIndex = index,
                        queueSize = queue.size,
                        onDelete = { onDelete(photo.id) },
                        onReorderTo = { target -> onReorder(index, target) },
                        modifier = Modifier.graphicsLayer { rotationZ = tiltDeg },
                    )
                }
            }
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

