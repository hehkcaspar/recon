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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Shutter sibling for VIDEO modality supporting `Standard56` (80dp ring, 42dp idle dot,
 * 28dp stop square with 6dp corners) and `Compact48` (64dp ring, 34dp idle dot, 24dp stop
 * square with 5dp corners).
 */
@Composable
fun VideoShutterButton(
    onClick: () -> Unit,
    recording: Boolean,
    progressFraction: Float?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    profile: QueueProfileToken = QueueProfileToken.Standard56,
) {
    val pulseAlpha = if (recording) {
        rememberRecordPulseAlpha(
            from = 0.95f, to = 0.65f, durationMs = 800, label = "video-shutter-pulse",
        ).value
    } else {
        1f
    }
    val fillAlpha = when {
        !enabled -> 0.45f
        recording -> pulseAlpha
        else -> 1f
    }
    val outerSize = if (profile == QueueProfileToken.Compact48) 64.dp else 80.dp
    val idleDotSize = if (profile == QueueProfileToken.Compact48) 34.dp else 42.dp
    val stopSquareSize = if (profile == QueueProfileToken.Compact48) 24.dp else 28.dp
    val stopCorner = if (profile == QueueProfileToken.Compact48) 5.dp else 6.dp

    val fillSize by animateDpAsState(
        targetValue = if (recording) stopSquareSize else idleDotSize,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "video-shutter-fill-size",
    )
    val fillCorner by animateDpAsState(
        targetValue = if (recording) stopCorner else 50.dp,
        animationSpec = tween(durationMillis = 220),
        label = "video-shutter-fill-corner",
    )
    val label = if (recording) "Stop recording" else "Start recording"

    Box(
        modifier = modifier
            .size(outerSize)
            .clip(CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .semantics {
                contentDescription = label
                if (!enabled) disabled()
            }
            .clickable(
                enabled = enabled,
                onClickLabel = label,
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
        if (recording && progressFraction != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val sweep = progressFraction.coerceIn(0f, 1f) * 360f
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, size.height),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}
