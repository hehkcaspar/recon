package com.example.bundlecam.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.bundlecam.data.camera.CameraMode

@Composable
fun CameraModeToggle(
    current: CameraMode,
    onChange: (CameraMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.15f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment(
            label = "EXT",
            selected = current == CameraMode.Extensions,
            onClick = { onChange(CameraMode.Extensions) },
        )
        Segment(
            label = "ZSL",
            selected = current == CameraMode.ZSL,
            onClick = { onChange(CameraMode.ZSL) },
        )
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(
                onClickLabel = label,
                role = Role.Tab,
                onClick = onClick,
            )
            .background(if (selected) Color.White.copy(alpha = 0.9f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
