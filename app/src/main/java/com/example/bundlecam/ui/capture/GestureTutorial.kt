package com.example.bundlecam.ui.capture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private val DemoThumbSize: Dp = 48.dp
private val DemoThumbGap: Dp = 8.dp
private val FingerSize: Dp = 34.dp
private const val DemoThumbCount = 4

private val DemoThumbColors = listOf(
    Color(0xFF3F6D8C),
    Color(0xFF8C5A3F),
    Color(0xFF5A7F4E),
    Color(0xFFB3946F),
)

/**
 * First-run and replayable gesture tutorial overlay per `COMPONENT-CONTRACT.md` § Tutorial:
 * - Covers the available window with an 82% black scrim (`Color.Black.copy(alpha = 0.82f)`),
 *   consuming all underlying pointer input and trapping accessibility traversal (`isTraversalGroup = true`).
 * - Driven directly by [geometry]:
 *   - Preview step (`SwitchModality`) anchors its demo to [CaptureGeometry.previewBounds].
 *   - Queue steps (`Commit`, `Discard`, `DeleteOne`, `Reorder`, `Divide`) anchor their demo to
 *     [CaptureGeometry.queueBounds] / [CaptureGeometry.queueStripBounds] and follow
 *     [CaptureGeometry.queueAxis] (`Horizontal` vs `Vertical`), naming the actual start handle.
 */
@Composable
fun GestureTutorial(
    onDismiss: (shownStepIds: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    seenStepIds: Set<String> = emptySet(),
    geometry: CaptureGeometry? = null,
) {
    val steps = remember(seenStepIds) {
        TutorialStep.entries.filter { it.id !in seenStepIds }
    }
    if (steps.isEmpty()) {
        return
    }
    var stepIndex by remember(steps) { mutableIntStateOf(0) }
    val step = steps[stepIndex.coerceIn(0, steps.lastIndex)]
    val isLast = stepIndex >= steps.size - 1
    val isPreviewZoneStep = step == TutorialStep.SwitchModality
    val density = LocalDensity.current
    val queueAxis = geometry?.queueAxis ?: QueueAxis.Horizontal

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .semantics {
                isTraversalGroup = true
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { if (!it.isConsumed) it.consume() }
                    }
                }
            },
    ) {
        if (geometry != null) {
            if (isPreviewZoneStep) {
                // Step 1 (SwitchModality): anchor both the compact preview-swipe demo
                // and the instructional copy inside `geometry.previewBounds` so the user's
                // eyes and thumb stay in the actual preview region without painting a
                // full-screen opaque slab.
                Box(
                    modifier = Modifier
                        .placeTutorialRect(geometry.previewBounds, density)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TutorialCopyAndNavigation(
                        step = step,
                        axis = queueAxis,
                        stepsCount = steps.size,
                        stepIndex = stepIndex,
                        isLast = isLast,
                        demoContent = {
                            ModalitySwipeDemo(
                                modifier = Modifier
                                    .widthIn(max = 260.dp)
                                    .fillMaxWidth()
                                    .height(148.dp),
                            )
                        },
                        onBack = { if (stepIndex > 0) stepIndex-- },
                        onNextOrDone = {
                            if (isLast) onDismiss(steps.map { it.id }.toSet()) else stepIndex++
                        },
                        onSkip = { onDismiss(steps.map { it.id }.toSet()) },
                    )
                }
            } else {
                // Queue steps (Commit, Discard, DeleteOne, Reorder, Divide):
                // - Demo is anchored directly to the real queue bounds (`queueBounds` for
                //   Vertical queue in Upright; `queueStripBounds` for Horizontal queue in
                //   CW90 / Phone / Cover) and animates along `geometry.queueAxis`.
                // - Instructional copy & navigation sit centered in `geometry.previewBounds`.
                val queueDemoRect = if (queueAxis == QueueAxis.Vertical) {
                    geometry.queueBounds
                } else {
                    geometry.queueStripBounds
                }
                Box(modifier = Modifier.placeTutorialRect(queueDemoRect, density)) {
                    DemoArea(
                        step = step,
                        axis = queueAxis,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .placeTutorialRect(geometry.previewBounds, density)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TutorialCopyAndNavigation(
                        step = step,
                        axis = queueAxis,
                        stepsCount = steps.size,
                        stepIndex = stepIndex,
                        isLast = isLast,
                        demoContent = null,
                        onBack = { if (stepIndex > 0) stepIndex-- },
                        onNextOrDone = {
                            if (isLast) onDismiss(steps.map { it.id }.toSet()) else stepIndex++
                        },
                        onSkip = { onDismiss(steps.map { it.id }.toSet()) },
                    )
                }
            }
        } else {
            // Fallback when composed standalone without CaptureGeometry
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (isPreviewZoneStep) {
                    ModalitySwipeDemo(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .height(210.dp),
                    )
                } else {
                    Spacer(Modifier.height(24.dp))
                }

                TutorialCopyAndNavigation(
                    step = step,
                    axis = queueAxis,
                    stepsCount = steps.size,
                    stepIndex = stepIndex,
                    isLast = isLast,
                    onBack = { if (stepIndex > 0) stepIndex-- },
                    onNextOrDone = {
                        if (isLast) onDismiss(steps.map { it.id }.toSet()) else stepIndex++
                    },
                    onSkip = { onDismiss(steps.map { it.id }.toSet()) },
                )

                if (!isPreviewZoneStep) {
                    DemoArea(
                        step = step,
                        axis = QueueAxis.Horizontal,
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .fillMaxWidth()
                            .height(72.dp),
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun TutorialCopyAndNavigation(
    step: TutorialStep,
    axis: QueueAxis,
    stepsCount: Int,
    stepIndex: Int,
    isLast: Boolean,
    demoContent: (@Composable () -> Unit)? = null,
    onBack: () -> Unit,
    onNextOrDone: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = "Skip",
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Text(
            text = "Gesture Guide",
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = step.titleFor(axis),
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = step.descriptionFor(axis),
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        if (demoContent != null) {
            Spacer(Modifier.height(20.dp))
            demoContent()
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(stepsCount) { i ->
                Box(
                    modifier = Modifier
                        .size(if (i == stepIndex) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == stepIndex) Color.White
                            else Color.White.copy(alpha = 0.35f),
                        ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (stepIndex > 0) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Back", color = Color.White.copy(alpha = 0.9f))
                }
            } else {
                Spacer(Modifier.width(72.dp))
            }
            Button(
                onClick = onNextOrDone,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
            ) {
                Text(
                    text = if (isLast) "Got it" else "Next",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun Modifier.placeTutorialRect(
    rect: RectPx,
    density: Density,
): Modifier = with(density) {
    this@placeTutorialRect
        .offset { IntOffset(rect.x.roundToInt(), rect.y.roundToInt()) }
        .size(
            width = rect.width.toDp(),
            height = rect.height.toDp(),
        )
}

private enum class TutorialStep(val id: String) {
    SwitchModality("modality"),
    CommitBundle("commit"),
    DiscardQueue("discard"),
    DeleteOne("deleteOne"),
    Reorder("reorder"),
    Divide("divide");

    fun titleFor(axis: QueueAxis): String = when (this) {
        SwitchModality -> "Swipe the preview to switch modes"
        CommitBundle -> if (axis == QueueAxis.Vertical) {
            "Swipe up from the bottom handle to save"
        } else {
            "Swipe right from the left handle to save"
        }
        DiscardQueue -> if (axis == QueueAxis.Vertical) {
            "Swipe down from the top handle to discard"
        } else {
            "Swipe left from the right handle to discard"
        }
        DeleteOne -> if (axis == QueueAxis.Vertical) {
            "Pull an item right to delete"
        } else {
            "Pull an item down to delete"
        }
        Reorder -> "Long-press and drag to reorder"
        Divide -> "Swipe between items to split"
    }

    fun descriptionFor(axis: QueueAxis): String = when (this) {
        SwitchModality -> "Swipe the preview — or tap the mode row — to switch between photo, video, and voice."
        CommitBundle -> if (axis == QueueAxis.Vertical) {
            "Start at the bottom queue handle and drag upward to save all queued items as a bundle."
        } else {
            "Start at the left queue handle and drag right to save all queued items as a bundle."
        }
        DiscardQueue -> if (axis == QueueAxis.Vertical) {
            "Start at the top queue handle and drag downward to drop every item in the queue."
        } else {
            "Start at the right queue handle and drag left to drop every item in the queue."
        }
        DeleteOne -> if (axis == QueueAxis.Vertical) {
            "Drag a single thumbnail outward to the right to remove just that item."
        } else {
            "Drag a single thumbnail downward to remove just that item."
        }
        Reorder -> "Press and hold a thumbnail, then drag it along the queue to its new position."
        Divide -> if (axis == QueueAxis.Vertical) {
            "Drag horizontally across a gap between two items to split them into separate bundles; drag back to join."
        } else {
            "Pull down in a gap between two items to split them into separate bundles; pull up to join."
        }
    }
}

@Composable
private fun DemoArea(
    step: TutorialStep,
    axis: QueueAxis,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (step) {
            TutorialStep.SwitchModality -> Unit
            TutorialStep.CommitBundle -> SwipeStripDemo(
                axis = axis,
                isCommit = true,
                tideColor = CaptureColors.CommitGreen,
                destinationGlyph = "✓",
                destinationColor = CaptureColors.CommitGreen,
            )
            TutorialStep.DiscardQueue -> SwipeStripDemo(
                axis = axis,
                isCommit = false,
                tideColor = CaptureColors.DiscardAmber,
                destinationGlyph = "✕",
                destinationColor = CaptureColors.DiscardAmber,
            )
            TutorialStep.DeleteOne -> DeleteOneDemo(axis = axis)
            TutorialStep.Reorder -> ReorderDemo(axis = axis)
            TutorialStep.Divide -> DivideDemo(axis = axis)
        }
    }
}

@Composable
private fun DemoThumb(
    index: Int,
    modifier: Modifier = Modifier,
) {
    val base = DemoThumbColors[index % DemoThumbColors.size]
    Box(
        modifier = modifier
            .size(DemoThumbSize)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(base, base.copy(alpha = 0.75f)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun Finger(
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
) {
    Box(
        modifier = modifier.size(FingerSize),
        contentAlignment = Alignment.Center,
    ) {
        val ringAlpha = if (pressed) 0.95f else 0.7f
        val ringSize = if (pressed) FingerSize else FingerSize - 6.dp
        Box(
            modifier = Modifier
                .size(ringSize)
                .clip(CircleShape)
                .border(2.dp, Color.White.copy(alpha = ringAlpha), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(FingerSize - 16.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (pressed) 0.95f else 0.85f)),
        )
    }
}

@Composable
private fun demoSpanPx(): Float {
    val d = LocalDensity.current
    return with(d) {
        (DemoThumbSize * DemoThumbCount + DemoThumbGap * (DemoThumbCount - 1)).toPx()
    }
}

@Composable
private fun SwipeStripDemo(
    axis: QueueAxis,
    isCommit: Boolean,
    tideColor: Color,
    destinationGlyph: String,
    destinationColor: Color,
) {
    val progress = remember(axis, isCommit) { Animatable(0f) }
    LaunchedEffect(axis, isCommit) {
        progress.snapTo(0f)
        while (true) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            )
            delay(500)
            progress.snapTo(0f)
            delay(250)
        }
    }

    val p = progress.value
    // In Horizontal: Commit sweeps left -> right (+1), Discard sweeps right -> left (-1).
    // In Vertical: Commit sweeps bottom -> top (-1), Discard sweeps top -> bottom (+1).
    val primarySign = when (axis) {
        QueueAxis.Horizontal -> if (isCommit) 1f else -1f
        QueueAxis.Vertical -> if (isCommit) -1f else 1f
    }
    val tilt = primarySign * (p * 3f)
    val tideAlpha = 0.18f + p * 0.45f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        if (p > 0.01f) {
            val tideBrush = if (axis == QueueAxis.Horizontal) {
                if (primarySign > 0f) {
                    Brush.horizontalGradient(
                        colors = listOf(tideColor.copy(alpha = tideAlpha), Color.Transparent),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, tideColor.copy(alpha = tideAlpha)),
                    )
                }
            } else {
                if (primarySign > 0f) {
                    Brush.verticalGradient(
                        colors = listOf(tideColor.copy(alpha = tideAlpha), Color.Transparent),
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, tideColor.copy(alpha = tideAlpha)),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tideBrush),
            )
        }

        // Initiating handle swiper (`queueCommit` / `queueDiscard` in SVG replaces the
        // initiating handle with the colored check/close swiper while opposite stays neutral).
        val initAlpha = (0.35f + p * 0.65f).coerceIn(0f, 1f)
        val initiatorAlign = when (axis) {
            QueueAxis.Horizontal -> if (primarySign > 0f) Alignment.CenterStart else Alignment.CenterEnd
            QueueAxis.Vertical -> if (primarySign > 0f) Alignment.TopCenter else Alignment.BottomCenter
        }
        Box(
            modifier = Modifier
                .align(initiatorAlign)
                .padding(6.dp)
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(destinationColor.copy(alpha = 0.25f + initAlpha * 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = destinationGlyph,
                color = Color.White.copy(alpha = initAlpha),
                fontWeight = FontWeight.Bold,
            )
        }

        if (axis == QueueAxis.Horizontal) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DemoThumbGap),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer { rotationZ = tilt },
            ) {
                repeat(DemoThumbCount) { i -> DemoThumb(index = i) }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(DemoThumbGap),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { rotationZ = tilt },
            ) {
                repeat(DemoThumbCount) { i -> DemoThumb(index = i) }
            }
        }

        val startFraction = if (primarySign > 0f) 0.12f else 0.88f
        val endFraction = if (primarySign > 0f) 0.88f else 0.12f
        val frac = startFraction + (endFraction - startFraction) * p
        Finger(
            modifier = Modifier.graphicsLayer {
                if (axis == QueueAxis.Horizontal) {
                    translationX = frac * w - w / 2f
                } else {
                    translationY = frac * h - h / 2f
                }
            },
        )
    }
}

@Composable
private fun DeleteOneDemo(axis: QueueAxis) {
    val progress = remember(axis) { Animatable(0f) }
    LaunchedEffect(axis) {
        progress.snapTo(0f)
        while (true) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            )
            delay(600)
            progress.snapTo(0f)
            delay(250)
        }
    }

    val p = progress.value
    val targetIndex = 1
    val thumbSizePx = with(LocalDensity.current) { DemoThumbSize.toPx() }
    val gapPx = with(LocalDensity.current) { DemoThumbGap.toPx() }
    val spanPx = demoSpanPx()
    val dropDistancePx = with(LocalDensity.current) { 18.dp.toPx() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        if (axis == QueueAxis.Horizontal) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DemoThumbGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(DemoThumbCount) { i ->
                    val isTarget = i == targetIndex
                    val shift = if (isTarget) p * dropDistancePx else 0f
                    val alpha = if (isTarget) (1f - p * 0.7f) else 1f
                    Box(
                        modifier = Modifier.graphicsLayer {
                            translationY = shift
                            this.alpha = alpha
                        },
                    ) {
                        DemoThumb(index = i)
                    }
                }
            }
            val startX = (w - spanPx) / 2f
            val targetCenter = startX + targetIndex * (thumbSizePx + gapPx) + thumbSizePx / 2f
            Finger(
                modifier = Modifier.graphicsLayer {
                    translationX = targetCenter - w / 2f
                    translationY = p * dropDistancePx
                    this.alpha = (1f - p * 0.5f)
                },
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(DemoThumbGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(DemoThumbCount) { i ->
                    val isTarget = i == targetIndex
                    val shift = if (isTarget) p * dropDistancePx else 0f
                    val alpha = if (isTarget) (1f - p * 0.7f) else 1f
                    Box(
                        modifier = Modifier.graphicsLayer {
                            translationX = shift
                            this.alpha = alpha
                        },
                    ) {
                        DemoThumb(index = i)
                    }
                }
            }
            val startY = (h - spanPx) / 2f
            val targetCenter = startY + targetIndex * (thumbSizePx + gapPx) + thumbSizePx / 2f
            Finger(
                modifier = Modifier.graphicsLayer {
                    translationX = p * dropDistancePx
                    translationY = targetCenter - h / 2f
                    this.alpha = (1f - p * 0.5f)
                },
            )
        }
    }
}

@Composable
private fun ReorderDemo(axis: QueueAxis) {
    val progress = remember(axis) { Animatable(0f) }
    LaunchedEffect(axis) {
        progress.snapTo(0f)
        while (true) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 2200, easing = LinearOutSlowInEasing),
            )
            delay(500)
            progress.snapTo(0f)
            delay(200)
        }
    }

    val p = progress.value
    val pressPhase = (p / 0.2f).coerceIn(0f, 1f)
    val dragPhase = ((p - 0.2f) / 0.6f).coerceIn(0f, 1f)
    val pressed = p in 0.05f..0.85f
    val movingIndex = 1
    val slotShiftPx = with(LocalDensity.current) {
        (DemoThumbSize + DemoThumbGap).toPx()
    }
    val thumbSizePx = with(LocalDensity.current) { DemoThumbSize.toPx() }
    val gapPx = with(LocalDensity.current) { DemoThumbGap.toPx() }
    val spanPx = demoSpanPx()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        if (axis == QueueAxis.Horizontal) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DemoThumbGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(DemoThumbCount) { i ->
                    val isMoving = i == movingIndex
                    val isDisplaced = i == movingIndex + 1
                    val shift = when {
                        isMoving -> dragPhase * slotShiftPx
                        isDisplaced -> -dragPhase * slotShiftPx
                        else -> 0f
                    }
                    val scale = if (isMoving) 1f + pressPhase * 0.1f else 1f
                    Box(
                        modifier = Modifier.graphicsLayer {
                            translationX = shift
                            scaleX = scale
                            scaleY = scale
                        },
                    ) {
                        DemoThumb(index = i)
                    }
                }
            }
            val startX = (w - spanPx) / 2f
            val baseCenter = startX + movingIndex * (thumbSizePx + gapPx) + thumbSizePx / 2f
            Finger(
                pressed = pressed,
                modifier = Modifier.graphicsLayer {
                    translationX = baseCenter + dragPhase * slotShiftPx - w / 2f
                },
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(DemoThumbGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(DemoThumbCount) { i ->
                    val isMoving = i == movingIndex
                    val isDisplaced = i == movingIndex + 1
                    val shift = when {
                        isMoving -> dragPhase * slotShiftPx
                        isDisplaced -> -dragPhase * slotShiftPx
                        else -> 0f
                    }
                    val scale = if (isMoving) 1f + pressPhase * 0.1f else 1f
                    Box(
                        modifier = Modifier.graphicsLayer {
                            translationY = shift
                            scaleX = scale
                            scaleY = scale
                        },
                    ) {
                        DemoThumb(index = i)
                    }
                }
            }
            val startY = (h - spanPx) / 2f
            val baseCenter = startY + movingIndex * (thumbSizePx + gapPx) + thumbSizePx / 2f
            Finger(
                pressed = pressed,
                modifier = Modifier.graphicsLayer {
                    translationY = baseCenter + dragPhase * slotShiftPx - h / 2f
                },
            )
        }
    }
}

@Composable
private fun DivideDemo(axis: QueueAxis) {
    val progress = remember(axis) { Animatable(0f) }
    LaunchedEffect(axis) {
        progress.snapTo(0f)
        while (true) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            )
            delay(400)
            progress.animateTo(
                targetValue = 2f,
                animationSpec = tween(durationMillis = 400),
            )
            delay(100)
            progress.animateTo(
                targetValue = 3f,
                animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            )
            delay(300)
            progress.snapTo(0f)
            delay(200)
        }
    }

    val p = progress.value
    val fingerCrossFactor = when {
        p <= 1f -> p
        p <= 2f -> 1f
        else -> 1f - (p - 2f)
    }.coerceIn(0f, 1f)
    val dividerAlpha = when {
        p < 0.6f -> 0f
        p <= 1f -> ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
        p <= 2f -> 1f
        p <= 2.4f -> 1f - ((p - 2f) / 0.4f).coerceIn(0f, 1f)
        else -> 0f
    }
    val spreadFactor = dividerAlpha
    val gapIndex = 1
    val thumbSizePx = with(LocalDensity.current) { DemoThumbSize.toPx() }
    val gapPx = with(LocalDensity.current) { DemoThumbGap.toPx() }
    val spanPx = demoSpanPx()
    val spreadDeltaPx = with(LocalDensity.current) { 18.dp.toPx() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        if (axis == QueueAxis.Horizontal) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DemoThumbGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(DemoThumbCount) { i ->
                    val shift = if (i <= gapIndex) {
                        -spreadFactor * spreadDeltaPx / 2f
                    } else {
                        spreadFactor * spreadDeltaPx / 2f
                    }
                    DemoThumb(
                        index = i,
                        modifier = Modifier.graphicsLayer { translationX = shift },
                    )
                }
            }
            val startX = (w - spanPx) / 2f
            val gapCenter = startX + (gapIndex + 1) * thumbSizePx + gapIndex * gapPx + gapPx / 2f
            if (dividerAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .graphicsLayer { translationX = gapCenter - w / 2f }
                        .width(3.dp)
                        .height(DemoThumbSize * 0.9f)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White.copy(alpha = dividerAlpha)),
                )
            }
            val travelPx = thumbSizePx * 0.55f
            Finger(
                modifier = Modifier.graphicsLayer {
                    translationX = gapCenter - w / 2f
                    translationY = (fingerCrossFactor - 0.4f) * travelPx
                },
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(DemoThumbGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                repeat(DemoThumbCount) { i ->
                    val shift = if (i <= gapIndex) {
                        -spreadFactor * spreadDeltaPx / 2f
                    } else {
                        spreadFactor * spreadDeltaPx / 2f
                    }
                    DemoThumb(
                        index = i,
                        modifier = Modifier.graphicsLayer { translationY = shift },
                    )
                }
            }
            val startY = (h - spanPx) / 2f
            val gapCenter = startY + (gapIndex + 1) * thumbSizePx + gapIndex * gapPx + gapPx / 2f
            if (dividerAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .graphicsLayer { translationY = gapCenter - h / 2f }
                        .height(3.dp)
                        .width(DemoThumbSize * 0.9f)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White.copy(alpha = dividerAlpha)),
                )
            }
            val travelPx = thumbSizePx * 0.55f
            Finger(
                modifier = Modifier.graphicsLayer {
                    translationX = (fingerCrossFactor - 0.4f) * travelPx
                    translationY = gapCenter - h / 2f
                },
            )
        }
    }
}

private val DemoPhotoTint = Color(0xFF2F3A4A)
private val DemoVideoTint = Color(0xFF6B1E1E)

@Composable
private fun ModalitySwipeDemo(modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.snapTo(0f)
        while (true) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            )
            delay(600)
            progress.snapTo(0f)
            delay(300)
        }
    }
    val p = progress.value

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = (p - 1f) * w }
                .background(DemoVideoTint.copy(alpha = 0.75f)),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = p * w }
                .background(DemoPhotoTint.copy(alpha = 0.95f)),
        )

        ModalityPillMini(
            progress = p,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .widthIn(max = 240.dp)
                .fillMaxWidth(0.78f)
                .height(28.dp),
        )

        val startFraction = 0.28f
        val endFraction = 0.72f
        val fingerCenterX = (startFraction + (endFraction - startFraction) * p) * w
        val fingerCenterY = h * 0.58f
        Finger(
            modifier = Modifier.graphicsLayer {
                translationX = fingerCenterX - w / 2f
                translationY = fingerCenterY - h / 2f
            },
        )
    }
}

@Composable
private fun ModalityPillMini(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val segments = listOf("VIDEO", "PHOTO", "VOICE")
    val selectedStart = 1
    val selectedEnd = 0
    val selectedFloat = selectedStart + (selectedEnd - selectedStart) * progress
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { i, label ->
            val distance = abs(i - selectedFloat).coerceIn(0f, 1f)
            val textColor = androidx.compose.ui.graphics.lerp(
                start = Color.White,
                stop = Color(0xFFBDBDBD),
                fraction = distance,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = textColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (distance < 0.45f) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}
