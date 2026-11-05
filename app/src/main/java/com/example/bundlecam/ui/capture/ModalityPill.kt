package com.example.bundlecam.ui.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Mode selector strip (`modeStripPhoto` / `modeStripVideo` / `modeStripVoice` /
 * `modeStripVideoLocked` / `modeStripVoiceLocked` in `recon-capture-design-system.svg`
 * lines 57–59 & 105–107, and `COMPONENT-CONTRACT.md` § Geometry):
 * - No background pill or capsule fill (`modeStripNeutral` is transparent).
 * - Three independent `stripWidth / 3 × 48dp` touch targets (`83⅓ × 48dp` at 250dp,
 *   `69⅓ × 48dp` at 208dp narrow upright rail).
 * - Idle colors: `#FFFFFF` 12sp Bold (700) for selected; `#BDBDBD` 12sp Medium (500)
 *   for unselected enabled choices.
 * - Recording-locked colors: `#DDDDDD` 12sp Bold (700) for selected; `#707070` 12sp
 *   Medium (500) for unselected choices.
 */
@Composable
fun ModalityPill(
    current: Modality,
    enabledModalities: Set<Modality>,
    onChange: (Modality) -> Unit,
    modifier: Modifier = Modifier,
    stripWidth: Dp = 250.dp,
    unavailableReasons: Map<Modality, String> = emptyMap(),
) {
    val allLocked = enabledModalities.isEmpty()
    Row(
        modifier = modifier
            .width(stripWidth)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val modalities = listOf(
            Modality.VIDEO to "VIDEO",
            Modality.PHOTO to "PHOTO",
            Modality.VOICE to "VOICE",
        )
        modalities.forEach { (modality, label) ->
            val isSelected = current == modality
            val isEnabled = modality in enabledModalities
            val reason = unavailableReasons[modality]
            Segment(
                label = label,
                selected = isSelected,
                enabled = isEnabled,
                allLocked = allLocked,
                unavailableReason = reason,
                onClick = { onChange(modality) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    allLocked: Boolean,
    unavailableReason: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = when {
        selected && allLocked -> Color(0xFFDDDDDD)
        selected -> Color(0xFFFFFFFF)
        !enabled -> Color(0xFF707070)
        else -> Color(0xFFBDBDBD)
    }
    val a11yDescription = when {
        unavailableReason != null -> "$label unavailable: $unavailableReason"
        !enabled -> "$label locked"
        selected -> "$label, selected"
        else -> label
    }

    Box(
        modifier = modifier
            .semantics {
                contentDescription = a11yDescription
                this.selected = selected
                if (!enabled) disabled()
            }
            .clickable(
                enabled = enabled,
                onClickLabel = label,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
