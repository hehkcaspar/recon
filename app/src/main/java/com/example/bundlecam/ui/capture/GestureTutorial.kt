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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val DemoThumbSize: Dp = 48.dp
private val DemoThumbGap: Dp = 8.dp
private val DemoRowHeight: Dp = 72.dp
private val FingerSize: Dp = 34.dp
private const val DemoThumbCount = 4

// Muted "photo-like" fills so the demo thumbnails read as pictures without being busy.
private val DemoThumbColors = listOf(
    Color(0xFF3F6D8C),
    Color(0xFF8C5A3F),
    Color(0xFF5A7F4E),
    Color(0xFFB3946F),
)

@Composable
fun GestureTutorial(
    onDismiss: (shownStepIds: Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    seenStepIds: Set<String> = emptySet(),
) {
    // Show only steps the user hasn't dismissed yet. V2 users upgrading from v1 see
    // just the new SwitchModality step; fresh installs see the full flow.
    val steps = remember(seenStepIds) {
        TutorialStep.entries.filter { it.id !in seenStepIds }
    }
    if (steps.isEmpty()) {
        // Nothing to show — render nothing. Caller is expected to always wire this
        // composable into the tree; the empty-set case is the steady-state for users
        // who've dismissed every step (the common case after first launch).
        return
    }
    var stepIndex by remember { mutableIntStateOf(0) }
    val step = steps[stepIndex]
    val isLast = stepIndex == steps.size - 1

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            // Swallow any taps/drags that fall outside interactive children so the real
            // capture UI underneath can't be operated while the tutorial is visible.
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { if (!it.isConsumed) it.consume() }
                    }
                }
            },
    ) {
        TextButton(
            onClick = { onDismiss(steps.map { it.id }.toSet()) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("Skip", color = Color.White.copy(alpha = 0.85f))
        }

        // Demo placement follows the *real* gesture zone for the current step:
        //   - SwitchModality → 3:4 preview area at top (matches the real viewfinder
        //     slab, which is where the swipe gesture originates).
        //   - Queue-strip steps → 72dp strip pinned just above navigation bar (matches
        //     the real queue strip, which is where commit/discard/delete/reorder/
        //     divide originate).
        // Text + pager + Next/Back fill the remaining space between. Same outer padding
        // regimen for both layouts so the Skip button sits consistently.
        val isPreviewZoneStep = step == TutorialStep.SwitchModality

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                // Matches the real queue's 12dp bottom gap inside navigationBarsPadding.
                .padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 56dp mirrors the real top bar height; puts the modality demo's top edge
            // where the real preview's top edge sits.
            Spacer(Modifier.height(56.dp))

            if (isPreviewZoneStep) {
                ModalitySwipeDemo(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = "Gesture Guide",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = step.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = step.description,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEachIndexed { i, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (i == stepIndex) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == stepIndex) Color.White
                                else Color.White.copy(alpha = 0.28f),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (stepIndex > 0) {
                    TextButton(onClick = { stepIndex-- }) {
                        Text("Back", color = Color.White.copy(alpha = 0.85f))
                    }
                }
                Button(
                    onClick = {
                        if (isLast) onDismiss(steps.map { it.id }.toSet()) else stepIndex++
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text(
                        text = if (isLast) "Got it" else "Next",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (!isPreviewZoneStep) {
                Spacer(Modifier.height(24.dp))
                // Queue-strip demo sits right where the real queue lives, so the
                // muscle-memory transfer is immediate when the tutorial dismisses.
                DemoArea(
                    step = step,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DemoRowHeight),
                )
            }
        }
    }
}

private enum class TutorialStep(val id: String, val title: String, val description: String) {
    // SwitchModality comes first so Phase-C-era upgraders (who've already seen the
    // five queue-strip steps) get a minimal single-step introduction to the new
    // capability, rather than a redundant 6-step walkthrough.
    SwitchModality(
        id = "modality",
        title = "Swipe the preview to switch modes",
        description = "Swipe the preview — or tap the pill at the top — to switch between photo, video, and voice.",
    ),
    CommitBundle(
        id = "commit",
        title = "Swipe right to save",
        description = "Sweep right across the queue strip to save all queued photos as a bundle.",
    ),
    DiscardQueue(
        id = "discard",
        title = "Swipe left to discard",
        description = "Sweep left across the queue strip to drop every photo in the queue.",
    ),
    DeleteOne(
        id = "deleteOne",
        title = "Flick a photo down to delete",
        description = "Swipe a single thumbnail downward to remove just that photo.",
    ),
    Reorder(
        id = "reorder",
        title = "Long-press and drag to reorder",
        description = "Press and hold a thumbnail, then drag it to its new position.",
    ),
    Divide(
        id = "divide",
        title = "Swipe between to split",
        description = "Swipe down between two photos to split them into separate bundles. Swipe up to undo a split.",
    ),
}

@Composable
private fun DemoArea(
    step: TutorialStep,
    modifier: Modifier = Modifier,
) {
    // Queue-zone steps only — SwitchModality is rendered by ModalitySwipeDemo in
    // the preview position and never routed here.
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (step) {
            TutorialStep.SwitchModality -> Unit
            TutorialStep.CommitBundle -> SwipeStripDemo(
                direction = 1,
                tideColor = CaptureColors.CommitGreen,
                destinationGlyph = "✓",
                destinationColor = CaptureColors.CommitGreen,
                destinationOnRight = true,
            )
            TutorialStep.DiscardQueue -> SwipeStripDemo(
                direction = -1,
                tideColor = CaptureColors.DiscardAmber,
                destinationGlyph = "✕",
                destinationColor = CaptureColors.DiscardAmber,
                destinationOnRight = false,
            )
            TutorialStep.DeleteOne -> DeleteOneDemo()
            TutorialStep.Reorder -> ReorderDemo()
            TutorialStep.Divide -> DivideDemo()
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

/**
 * Layout helper: total width in px of a row of [DemoThumbCount] thumbs with [DemoThumbGap]
 * between each. Shared by every demo so the fake queue is centered identically across steps.
 */
@Composable
private fun demoRowWidthPx(): Float {
    val d = LocalDensity.current
    return with(d) {
        (DemoThumbSize * DemoThumbCount + DemoThumbGap * (DemoThumbCount - 1)).toPx()
    }
}

@Composable
private fun SwipeStripDemo(
    direction: Int,
    tideColor: Color,
    destinationGlyph: String,
    destinationColor: Color,
    destinationOnRight: Boolean,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(direction) {
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
    val tilt = (if (direction > 0) 1 else -1) * (p * 3f)
    val tideAlpha = 0.18f + p * 0.45f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(DemoRowHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()

        if (p > 0.01f) {
            val tideBrush = if (direction > 0) {
                Brush.horizontalGradient(
                    colors = listOf(tideColor.copy(alpha = 0f), tideColor.copy(alpha = tideAlpha)),
                )
            } else {
                Brush.horizontalGradient(
                    colors = listOf(tideColor.copy(alpha = tideAlpha), tideColor.copy(alpha = 0f)),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tideBrush),
            )
        }

        // Destination pill — where the queue is being "swept to".
        val destAlpha = (p - 0.15f).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .align(if (destinationOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 6.dp)
                .width(36.dp)
                .fillMaxHeight(0.65f)
                .clip(RoundedCornerShape(8.dp))
                .background(destinationColor.copy(alpha = 0.25f + destAlpha * 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = destinationGlyph,
                color = Color.White.copy(alpha = 0.25f + destAlpha * 0.75f),
                fontWeight = FontWeight.Bold,
            )
        }

        // Row of fake thumbnails; tilts in the gesture direction to echo the real UI.
        Row(
            horizontalArrangement = Arrangement.spacedBy(DemoThumbGap),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer { rotationZ = tilt },
        ) {
            repeat(DemoThumbCount) { i -> DemoThumb(index = i) }
        }

        // Finger sweeps horizontally. Translations are relative to Box center (default
        // layout position when contentAlignment = Center), so we offset by (target - w/2).
        val startFraction = if (direction > 0) 0.12f else 0.88f
        val endFraction = if (direction > 0) 0.88f else 0.12f
        val targetCenterX = (startFraction + (endFraction - startFraction) * p) * w
        Finger(
            modifier = Modifier.graphicsLayer {
                translationX = targetCenterX - w / 2f
            },
        )
    }
}

@Composable
private fun DeleteOneDemo() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
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
    val rowWidthPx = demoRowWidthPx()
    // Short enough that most of the finger and the falling thumb stay inside the 72dp
    // strip bounds; the red glow + fading thumb sell the delete either way.
    val dropDistancePx = with(LocalDensity.current) { 18.dp.toPx() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(DemoRowHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()

        // Red "delete zone" glow at the bottom.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            CaptureColors.DeleteRed.copy(alpha = 0.3f + p * 0.45f),
                        ),
                    ),
                ),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(DemoThumbGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(DemoThumbCount) { i ->
                val isTarget = i == targetIndex
                val yShift = if (isTarget) p * dropDistancePx else 0f
                val alpha = if (isTarget) (1f - p * 0.7f) else 1f
                Box(
                    modifier = Modifier.graphicsLayer {
                        translationY = yShift
                        this.alpha = alpha
                    },
                ) {
                    DemoThumb(index = i)
                }
            }
        }

        // Finger sits on the target thumb and rides it downward. targetCenterX is
        // computed from the laid-out row, which is centered horizontally in the box.
        val rowStartX = (w - rowWidthPx) / 2f
        val targetCenterX = rowStartX + targetIndex * (thumbSizePx + gapPx) + thumbSizePx / 2f
        Finger(
            modifier = Modifier.graphicsLayer {
                translationX = targetCenterX - w / 2f
                translationY = p * dropDistancePx
                this.alpha = (1f - p * 0.5f)
            },
        )
    }
}

@Composable
private fun ReorderDemo() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
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
    // 0.00–0.20: long-press pulse. 0.20–0.80: drag right one slot. 0.80–1.00: settle.
    val pressPhase = (p / 0.2f).coerceIn(0f, 1f)
    val dragPhase = ((p - 0.2f) / 0.6f).coerceIn(0f, 1f)
    val pressed = p in 0.05f..0.85f
    val movingIndex = 1
    val slotShiftPx = with(LocalDensity.current) {
        (DemoThumbSize + DemoThumbGap).toPx()
    }
    val thumbSizePx = with(LocalDensity.current) { DemoThumbSize.toPx() }
    val gapPx = with(LocalDensity.current) { DemoThumbGap.toPx() }
    val rowWidthPx = demoRowWidthPx()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(DemoRowHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()

        Row(
            horizontalArrangement = Arrangement.spacedBy(DemoThumbGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(DemoThumbCount) { i ->
                val isMoving = i == movingIndex
                val isDisplaced = i == movingIndex + 1
                val xShift = when {
                    isMoving -> dragPhase * slotShiftPx
                    isDisplaced -> -dragPhase * slotShiftPx
                    else -> 0f
                }
                val scale = if (isMoving) 1f + pressPhase * 0.1f else 1f
                val lift = if (isMoving) -pressPhase * 4f else 0f
                Box(
                    modifier = Modifier.graphicsLayer {
                        translationX = xShift
                        translationY = lift
                        scaleX = scale
                        scaleY = scale
                    },
                ) {
                    DemoThumb(index = i)
                }
            }
        }

        val rowStartX = (w - rowWidthPx) / 2f
        val baseCenterX = rowStartX + movingIndex * (thumbSizePx + gapPx) + thumbSizePx / 2f
        val targetCenterX = baseCenterX + dragPhase * slotShiftPx
        Finger(
            pressed = pressed,
            modifier = Modifier.graphicsLayer {
                translationX = targetCenterX - w / 2f
                translationY = -pressPhase * 4f
            },
        )
    }
}

@Composable
private fun DivideDemo() {
    // Phase 0..1: insert divider (finger swipes down). 1..2: hold. 2..3: remove (swipe up).
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
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
    // Finger's vertical position factor: 0 = center resting, 1 = fully down (divider created).
    val fingerYFactor = when {
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
    // Groups on either side of the split slide apart with the divider. The factor eases
    // with dividerAlpha so the spread grows/shrinks in lockstep with the line's fade.
    val spreadFactor = dividerAlpha
    val gapIndex = 1

    val thumbSizePx = with(LocalDensity.current) { DemoThumbSize.toPx() }
    val gapPx = with(LocalDensity.current) { DemoThumbGap.toPx() }
    val rowWidthPx = demoRowWidthPx()
    // Extra gap opened up between the two groups at full divider. Half of this goes to
    // each side (left group slides left, right group slides right), so the center of
    // the expanding gap stays pinned at the original gapCenterX.
    val spreadDeltaPx = with(LocalDensity.current) { 18.dp.toPx() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(DemoRowHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()

        Row(
            horizontalArrangement = Arrangement.spacedBy(DemoThumbGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(DemoThumbCount) { i ->
                // Symmetric spread: left group shifts left by half, right group by half.
                // Divider line at the original gap center remains dead-center between them.
                val shiftX = if (i <= gapIndex) {
                    -spreadFactor * spreadDeltaPx / 2f
                } else {
                    spreadFactor * spreadDeltaPx / 2f
                }
                DemoThumb(
                    index = i,
                    modifier = Modifier.graphicsLayer { translationX = shiftX },
                )
            }
        }

        val rowStartX = (w - rowWidthPx) / 2f
        // Center of gap N = rowStart + (N+1) thumbs + N full gaps + half of gap N.
        val gapCenterX = rowStartX + (gapIndex + 1) * thumbSizePx + gapIndex * gapPx + gapPx / 2f

        if (dividerAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .graphicsLayer { translationX = gapCenterX - w / 2f }
                    .width(3.dp)
                    .height(DemoThumbSize * 0.9f)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(Color.White.copy(alpha = dividerAlpha)),
            )
        }

        // Finger rides vertically through the gap. 0 resting → positive Y = downward.
        val travelPx = thumbSizePx * 0.55f
        Finger(
            modifier = Modifier.graphicsLayer {
                translationX = gapCenterX - w / 2f
                translationY = (fingerYFactor - 0.4f) * travelPx
            },
        )
    }
}

// Modality-tile tints. Kept local to the demo (not added to CaptureColors) since these
// are decorative only — the real UI doesn't tint the preview by modality.
private val DemoPhotoTint = Color(0xFF2F3A4A)  // neutral blue-grey
private val DemoVideoTint = Color(0xFF6B1E1E)  // muted record-red
private val DemoVoiceTint = Color(0xFF1F2A44)  // matches the real voice-overlay navy

/**
 * Demo for the SwitchModality step. Overlays the real viewfinder's position (3:4
 * aspect, upper area) and walks through:
 *   - finger appears right-of-center,
 *   - finger sweeps left while the "current" slab translates with it (matching the
 *     real `Modifier.draggable` + `graphicsLayer.translationX` behavior),
 *   - a "next modality" ghost fills in from the right,
 *   - the mini top-bar pill's selected-segment indicator slides left in sync,
 *   - loop.
 *
 * The loop only shows the left-swipe direction (PHOTO → VIDEO); the description text
 * and the pill's three segments communicate that right-swipe → VOICE is the mirror.
 */
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
        // Center-align so the Finger (no explicit size modifier) sits at (w/2, h/2)
        // by default and translateX/Y become deltas from center, matching the
        // positioning convention in SwipeStripDemo.
        contentAlignment = Alignment.Center,
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        // Slab layer: the "current" modality (PHOTO) translates left; the "next"
        // modality ghost (VIDEO) starts off to the right and slides in. Both are
        // siblings in the same Box so their 3:4 area is shared.
        //
        // At p=0: current = 0 (centered, PHOTO visible), ghost = +w (off-right,
        //   hidden).
        // At p=1: current = -w (off-left, hidden), ghost = 0 (centered, VIDEO
        //   visible).
        // Ghost declared first so current paints on top and the reveal is "current
        // slides off to expose ghost".
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = (1f - p) * w }  // ghost slides in from right
                .background(DemoVideoTint.copy(alpha = 0.75f)),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = -p * w }  // current slides off left
                .background(DemoPhotoTint.copy(alpha = 0.95f)),
        )

        // Mini top-bar pill overlay. Renders "VIDEO · PHOTO · VOICE" with an
        // indicator that slides from centered (PHOTO) to left (VIDEO) in lockstep
        // with the drag progress. Sits inside the demo's top padding so it doesn't
        // fight with the mid-slab finger.
        ModalityPillMini(
            progress = p,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .fillMaxWidth(0.78f)
                .height(28.dp),
        )

        // Finger sweeps horizontally from right-of-center to left-of-center, at the
        // vertical midline (roughly where the user's thumb would land on the real
        // preview). Translations are relative to Box center.
        val startFraction = 0.72f
        val endFraction = 0.28f
        val fingerCenterX = (startFraction + (endFraction - startFraction) * p) * w
        val fingerCenterY = h * 0.58f  // slightly below center — reads as a thumb arc
        Finger(
            modifier = Modifier.graphicsLayer {
                translationX = fingerCenterX - w / 2f
                translationY = fingerCenterY - h / 2f
            },
        )
    }
}

/**
 * 3-segment pill decoration for [ModalitySwipeDemo]. Indicator slides linearly
 * between the center (PHOTO) segment and the left (VIDEO) segment as [progress]
 * runs 0 → 1, matching how the real pill's indicator couples to drag offset.
 */
@Composable
private fun ModalityPillMini(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val segments = listOf("VIDEO", "PHOTO", "VOICE")
    val selectedStart = 1  // PHOTO
    val selectedEnd = 0    // VIDEO
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(50),
            ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val pillW = constraints.maxWidth.toFloat()
            val segmentW = pillW / segments.size
            // Indicator X: interpolate between the centers of segmentStart/segmentEnd.
            val centerStart = segmentW * (selectedStart + 0.5f)
            val centerEnd = segmentW * (selectedEnd + 0.5f)
            val indicatorCenterX = centerStart + (centerEnd - centerStart) * progress
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = indicatorCenterX - segmentW / 2f
                    }
                    .width(with(LocalDensity.current) { segmentW.toDp() })
                    .fillMaxHeight()
                    .padding(horizontal = 2.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.9f)),
            )
            Row(modifier = Modifier.fillMaxSize()) {
                segments.forEachIndexed { i, label ->
                    // Selected-ness is binary here (which segment the indicator is
                    // currently over), so text color flips when the indicator is
                    // roughly over this segment. Matches the real pill's contrast
                    // behavior — black on the white indicator, white elsewhere.
                    val currentSelected =
                        if (progress < 0.5f) selectedStart else selectedEnd
                    val textColor = if (i == currentSelected) {
                        Color.Black
                    } else {
                        Color.White.copy(alpha = 0.85f)
                    }
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
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
