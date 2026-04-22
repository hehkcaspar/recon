package com.example.bundlecam.ui.capture

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Shutter sibling for VOICE modality. 80dp outer ring, white fill + mic glyph when
 * idle (large interaction target), red dot→rounded-square when recording — mirrors
 * the video shutter's morph but starts from a white circle rather than a red one.
 */
@Composable
fun VoiceShutterButton(
    onClick: () -> Unit,
    recording: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice-shutter-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice-shutter-pulse-alpha",
    )
    val fillSize by animateDpAsState(
        targetValue = if (recording) 28.dp else 58.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "voice-shutter-fill-size",
    )
    val fillCorner by animateDpAsState(
        targetValue = if (recording) 6.dp else 50.dp,
        animationSpec = tween(durationMillis = 220),
        label = "voice-shutter-fill-corner",
    )

    Box(
        modifier = modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = if (recording) "Stop voice recording" else "Start voice recording",
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Box(
                modifier = Modifier
                    .size(fillSize)
                    .clip(RoundedCornerShape(fillCorner))
                    .background(Color(0xFFEF5350).copy(alpha = pulseAlpha)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(fillSize)
                    .clip(RoundedCornerShape(fillCorner))
                    .background(if (enabled) Color.White else Color.White.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
