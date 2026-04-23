package com.example.bundlecam.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.bundlecam.ui.common.formatClockDuration
import com.example.bundlecam.ui.common.rememberElapsedMs

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
    val elapsedMs = rememberElapsedMs(startedAtMs)
    val dotAlpha by rememberRecordPulseAlpha(from = 0.35f, to = 1f, durationMs = 700, label = "rec-dot")

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(CaptureColors.DeleteRed)
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .rotate(contentRotation),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = dotAlpha)),
        )
        Text(
            text = formatClockDuration(elapsedMs),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
