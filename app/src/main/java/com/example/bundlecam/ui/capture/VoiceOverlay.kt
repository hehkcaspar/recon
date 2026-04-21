package com.example.bundlecam.ui.capture

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Covers the camera preview when modality is VOICE. The preview stays bound underneath
 * (so switching back to photo/video is instant) but is completely hidden by this scrim
 * to avoid visual confusion about what's being captured.
 *
 * Placeholder for the waveform visualizer described in the multimodal design — MVP
 * scope keeps this minimal: mic glyph, "Tap to record" / "Recording…" label, and a
 * pulsing red dot during an active take.
 */
@Composable
fun VoiceOverlay(
    recording: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrimColor = if (recording) Color.Black.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.72f)
    Box(
        modifier = modifier.background(scrimColor),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (recording) "Recording…" else "Tap shutter to record voice",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (recording) {
                Spacer(Modifier.height(12.dp))
                val infiniteTransition = rememberInfiniteTransition(label = "voice-rec-dot")
                val dotAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "voice-rec-dot-alpha",
                )
                Icon(
                    imageVector = Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    tint = Color(0xFFEF5350).copy(alpha = dotAlpha),
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape),
                )
            }
        }
    }
}
