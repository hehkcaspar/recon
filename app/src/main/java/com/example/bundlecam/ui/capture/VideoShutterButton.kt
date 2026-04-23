package com.example.bundlecam.ui.capture

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Shutter sibling for VIDEO modality. 80dp outer ring matches [ShutterButton]'s hit
 * target. Idle state: a red dot (~42dp) centered in the ring. Recording: the dot
 * shrinks and morphs into a rounded square (~28dp) with subtle alpha pulse — the
 * iconic "now recording, tap to stop" form factor. The ring itself never changes size.
 */
@Composable
fun VideoShutterButton(
    onClick: () -> Unit,
    recording: Boolean,
    progressFraction: Float?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val pulseAlpha by rememberRecordPulseAlpha(
        from = 0.95f, to = 0.65f, durationMs = 800, label = "video-shutter-pulse",
    )
    val fillAlpha = when {
        !enabled -> 0.45f
        recording -> pulseAlpha
        else -> 1f
    }
    // Morph the inner fill between a record-dot (large red circle) and a stop-square
    // (smaller red rounded-rect). Both are well inside the 80dp ring — leaving
    // breathing room so the ring reads as a distinct outline.
    val fillSize by animateDpAsState(
        targetValue = if (recording) 28.dp else 42.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "video-shutter-fill-size",
    )
    val fillCorner by animateDpAsState(
        targetValue = if (recording) 6.dp else 50.dp,
        animationSpec = tween(durationMillis = 220),
        label = "video-shutter-fill-corner",
    )

    Box(
        modifier = modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = if (recording) "Stop recording" else "Start recording",
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(fillSize)
                .clip(RoundedCornerShape(fillCorner))
                .background(CaptureColors.RecordRed.copy(alpha = fillAlpha)),
        )
        // Progress arc on the outer ring, only when recording with a max-duration cap.
        if (recording && progressFraction != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val sweep = progressFraction.coerceIn(0f, 1f) * 360f
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = Size(size.width, size.height),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}
