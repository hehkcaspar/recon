package com.example.bundlecam.ui.capture

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Shutter sibling for VOICE modality supporting `Standard56` (80dp outer ring, 58dp white
 * disk with 32dp mic glyph, 28dp red stop square) and `Compact48` (64dp outer ring, 48dp
 * white disk with 28dp mic glyph, 24dp red stop square) per `COMPONENT-CONTRACT.md`.
 */
@Composable
fun VoiceShutterButton(
    onClick: () -> Unit,
    recording: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    profile: QueueProfileToken = QueueProfileToken.Standard56,
) {
    val pulseAlpha = if (recording) {
        rememberRecordPulseAlpha(
            from = 0.95f, to = 0.65f, durationMs = 800, label = "voice-shutter-pulse",
        ).value
    } else {
        1f
    }
    val outerSize = if (profile == QueueProfileToken.Compact48) 64.dp else 80.dp
    val idleDiskSize = if (profile == QueueProfileToken.Compact48) 48.dp else 58.dp
    val stopSquareSize = if (profile == QueueProfileToken.Compact48) 24.dp else 28.dp
    val stopCorner = if (profile == QueueProfileToken.Compact48) 5.dp else 6.dp
    val micGlyphSize = if (profile == QueueProfileToken.Compact48) 28.dp else 32.dp

    val fillSize by animateDpAsState(
        targetValue = if (recording) stopSquareSize else idleDiskSize,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "voice-shutter-fill-size",
    )
    val fillCorner by animateDpAsState(
        targetValue = if (recording) stopCorner else 50.dp,
        animationSpec = tween(durationMillis = 220),
        label = "voice-shutter-fill-corner",
    )
    val label = if (recording) "Stop voice recording" else "Start voice recording"

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
        if (recording) {
            Box(
                modifier = Modifier
                    .size(fillSize)
                    .clip(RoundedCornerShape(fillCorner))
                    .background(CaptureColors.RecordRed.copy(alpha = pulseAlpha)),
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
                    modifier = Modifier.size(micGlyphSize),
                )
            }
        }
    }
}
