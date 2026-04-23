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

/**
 * Top-bar modality selector: VIDEO · PHOTO · VOICE. Segments outside [enabledModalities]
 * render at 35% alpha and are non-clickable; callers use this to dim the pill while a
 * recording is live.
 */
@Composable
fun ModalityPill(
    current: Modality,
    enabledModalities: Set<Modality>,
    onChange: (Modality) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.15f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed left-to-right order matches the viewfinder swipe carousel:
        // swipe right from PHOTO → VIDEO, swipe left from PHOTO → VOICE.
        Segment(
            label = "VIDEO",
            selected = current == Modality.VIDEO,
            enabled = Modality.VIDEO in enabledModalities,
            onClick = { onChange(Modality.VIDEO) },
        )
        Segment(
            label = "PHOTO",
            selected = current == Modality.PHOTO,
            enabled = Modality.PHOTO in enabledModalities,
            onClick = { onChange(Modality.PHOTO) },
        )
        Segment(
            label = "VOICE",
            selected = current == Modality.VOICE,
            enabled = Modality.VOICE in enabledModalities,
            onClick = { onChange(Modality.VOICE) },
        )
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Background: the selected segment always shows its white pill (dimmed when
    // disabled) so the user can read the current modality at a glance — even
    // during a recording when every segment is disabled for tap.
    val background = when {
        selected && enabled -> Color.White.copy(alpha = 0.9f)
        selected && !enabled -> Color.White.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    val textColor = when {
        selected -> Color.Black
        !enabled -> Color.White.copy(alpha = 0.35f)
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(
                enabled = enabled,
                onClickLabel = label,
                role = Role.Tab,
                onClick = onClick,
            )
            .background(background)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
