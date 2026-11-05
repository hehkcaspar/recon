package com.example.bundlecam.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
 * Photo shutter button supporting both `Standard56` (80dp outer ring, 64dp white center)
 * and `Compact48` (64dp outer ring, 50dp white center) profiles per `COMPONENT-CONTRACT.md`.
 */
@Composable
fun ShutterButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    profile: QueueProfileToken = QueueProfileToken.Standard56,
) {
    val outerSize = if (profile == QueueProfileToken.Compact48) 64.dp else 80.dp
    val centerSize = if (profile == QueueProfileToken.Compact48) 50.dp else 64.dp
    Box(
        modifier = modifier
            .size(outerSize)
            .clip(CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .semantics {
                contentDescription = "Take photo"
                if (!enabled) disabled()
            }
            .clickable(
                enabled = enabled,
                onClickLabel = "Take photo",
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(centerSize)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Color.White.copy(alpha = 0.5f)),
        )
    }
}
