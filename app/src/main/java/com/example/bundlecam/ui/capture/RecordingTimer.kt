package com.example.bundlecam.ui.capture

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Red-pill elapsed-time readout shown above the zoom chips while a video recording
 * is live. The pill + pulsing white dot read as a universal "REC" affordance; the
 * M:SS caption ticks every 500ms.
 *
 * Counter-rotates with device orientation to match the other control-row icons.
 */
@Composable
fun VideoRecordingPill(
    startedAtMs: Long,
    contentRotation: Float,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(500L)
        }
    }
    val elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L)
    val totalSeconds = elapsedMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val label = "%d:%02d".format(minutes, seconds)

    val infiniteTransition = rememberInfiniteTransition(label = "rec-dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rec-dot-alpha",
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFFD32F2F))
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .rotate(contentRotation),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = dotAlpha)),
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
