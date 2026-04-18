package com.example.bundlecam.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bundlecam.data.camera.ZoomInfo
import kotlin.math.abs

private val Presets: FloatArray = floatArrayOf(0.5f, 1f, 2f, 5f)

@Composable
fun ZoomControl(
    zoomInfo: ZoomInfo?,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    contentRotation: Float = 0f,
) {
    if (zoomInfo == null) return
    val available = Presets.filter { it in zoomInfo.minRatio..zoomInfo.maxRatio }
    if (available.size < 2) return

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        available.forEach { preset ->
            ZoomPresetChip(
                ratio = preset,
                currentRatio = zoomInfo.currentRatio,
                onSelect = { onZoomChange(preset) },
                contentRotation = contentRotation,
            )
        }
    }
}

@Composable
private fun ZoomPresetChip(
    ratio: Float,
    currentRatio: Float,
    onSelect: () -> Unit,
    contentRotation: Float = 0f,
) {
    val selected = abs(currentRatio - ratio) < 0.05f
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(role = Role.Button, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatRatio(ratio, selected),
            modifier = Modifier.rotate(contentRotation),
            color = if (selected) Color.Black else Color.White,
            fontSize = if (selected) 12.sp else 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

private fun formatRatio(ratio: Float, emphasized: Boolean): String {
    val core = when {
        ratio < 1f -> ".${(ratio * 10).toInt()}"
        ratio % 1f == 0f -> ratio.toInt().toString()
        else -> "%.1f".format(ratio)
    }
    return if (emphasized) "${core}×" else core
}
