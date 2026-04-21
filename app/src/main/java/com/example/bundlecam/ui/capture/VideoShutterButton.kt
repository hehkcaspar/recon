package com.example.bundlecam.ui.capture

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
 * Shutter sibling for VIDEO modality. Same 80dp outer ring + 8dp padding geometry as
 * [ShutterButton] so the hit target is identical. When idle: shows a small red fill in
 * the center. When recording: inner fill pulses red and a CW progress arc sweeps along
 * the outer ring, keyed on [progressFraction] (0..1 when capped by max duration, else
 * unused — pass null for an unlimited recording).
 */
@Composable
fun VideoShutterButton(
    onClick: () -> Unit,
    recording: Boolean,
    progressFraction: Float?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "video-shutter-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "video-shutter-pulse-alpha",
    )
    val innerScale by animateFloatAsState(
        targetValue = if (recording) 0.68f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "video-shutter-inner-scale",
    )
    val fillAlpha = if (!enabled) 0.45f else if (recording) pulseAlpha else 1f

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
            )
            .padding(8.dp),
    ) {
        // Inner fill: red dot that morphs into a stop-square shape when recording.
        val cornerDp = if (recording) 8.dp else 40.dp
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerDp))
                .background(Color(0xFFEF5350).copy(alpha = fillAlpha)),
        )
        // Progress arc on the outer ring, only when recording with a max-duration cap.
        if (recording && progressFraction != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val sweep = (progressFraction.coerceIn(0f, 1f)) * 360f
                // Start at 12 o'clock (-90°), sweep clockwise.
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
        // Unused geometry params silence Kotlin compiler warnings on the above math.
        @Suppress("unused") val _innerScale = innerScale
    }
}
