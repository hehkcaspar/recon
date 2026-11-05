package com.example.bundlecam.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bundlecam.data.camera.ZoomInfo
import java.util.Locale
import kotlin.math.abs

private val StandardPresets: FloatArray = floatArrayOf(0.5f, 1f, 2f, 5f)
private val SelectedZoomCircleColor = Color(0xFF505050)
private val UnselectedZoomTextColor = Color(0xFFCCCCCC)

/**
 * Zoom control matching `zoomChips` (`line 60`) and `zoomSingle` (`line 166`) in
 * `recon-capture-design-system.svg` and `COMPONENT-CONTRACT.md`:
 * - 48 × 48dp independent touch targets on a 48dp center pitch.
 * - 28dp (`r=14`) `#505050` selected circle within a 32dp-thick 72% black surface (`rx=16`).
 * - Selected text: `#FFFFFF` 12sp Bold (700) with `×` suffix (`1×`, `.5×`, `2×`, `5×`).
 * - Unselected text: `#CCCCCC` 12sp Medium (500) without `×` suffix (`.5`, `1`, `2`, `5`).
 * - Continuous zoom value (e.g. `1.7×` when not on a preset) replaces the `1×` slot without
 *   falsely marking `1×` selected; tapping that slot resets to `1×` with semantics
 *   `"Current zoom 1.7x; reset to 1x"`.
 */
@Composable
fun ZoomControl(
    zoomInfo: ZoomInfo?,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    contentRotation: Float = 0f,
    singlePresetMode: Boolean = false,
) {
    if (zoomInfo == null) return
    if (zoomInfo.maxRatio - zoomInfo.minRatio < 0.01f) return

    val available = if (singlePresetMode) {
        listOf(1f.coerceIn(zoomInfo.minRatio, zoomInfo.maxRatio))
    } else {
        val filtered = StandardPresets.filter { it in zoomInfo.minRatio..zoomInfo.maxRatio }
        if (filtered.isEmpty()) listOf(1f.coerceIn(zoomInfo.minRatio, zoomInfo.maxRatio)) else filtered
    }

    val matchedPreset = available.firstOrNull { abs(zoomInfo.currentRatio - it) < 0.05f }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // 32dp-thick 72% black visual surface (`176 × 32dp` for 4 chips, `32 × 32dp` for singleton)
        val visualWidth = (available.size * 48 - 16).coerceAtLeast(32).dp
        Box(
            modifier = Modifier
                .size(width = visualWidth, height = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.72f)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            available.forEach { preset ->
                val isOneSlot = abs(preset - 1f) < 0.05f
                val showContinuousInOneSlot = matchedPreset == null && isOneSlot
                val isSelected = matchedPreset != null && abs(preset - matchedPreset) < 0.05f
                ZoomPresetChip(
                    presetRatio = preset,
                    displayRatio = if (showContinuousInOneSlot) zoomInfo.currentRatio else preset,
                    selected = isSelected,
                    isContinuousResetSlot = showContinuousInOneSlot,
                    onSelect = { onZoomChange(if (showContinuousInOneSlot) 1f else preset) },
                    contentRotation = contentRotation,
                )
            }
        }
    }
}

@Composable
private fun ZoomPresetChip(
    presetRatio: Float,
    displayRatio: Float,
    selected: Boolean,
    isContinuousResetSlot: Boolean,
    onSelect: () -> Unit,
    contentRotation: Float = 0f,
) {
    val label = formatZoomLabel(
        ratio = displayRatio,
        withTimesSuffix = selected || isContinuousResetSlot,
    )
    val a11yDescription = if (isContinuousResetSlot) {
        val formatted = String.format(Locale.US, "%.1f", displayRatio)
        "Current zoom ${formatted}x; reset to 1x"
    } else {
        val formatted = if (presetRatio % 1f == 0f) {
            presetRatio.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", presetRatio)
        }
        "${formatted}x zoom"
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                contentDescription = a11yDescription
                this.selected = selected
            }
            .clickable(
                role = Role.Button,
                onClickLabel = if (isContinuousResetSlot) "Reset to 1x" else "Select $a11yDescription",
                onClick = onSelect,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (selected) SelectedZoomCircleColor else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                modifier = Modifier.rotate(contentRotation),
                color = if (selected || isContinuousResetSlot) Color.White else UnselectedZoomTextColor,
                fontSize = 12.sp,
                fontWeight = if (selected || isContinuousResetSlot) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

internal fun formatZoomLabel(ratio: Float, withTimesSuffix: Boolean): String {
    val core = when {
        ratio < 1f -> ".${(ratio * 10f).toInt()}"
        abs(ratio - ratio.toInt()) < 0.05f -> ratio.toInt().toString()
        else -> String.format(Locale.US, "%.1f", ratio)
    }
    return if (withTimesSuffix) "${core}×" else core
}
