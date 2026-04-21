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
 * Top-bar modality selector: VIDEO · PHOTO · VOICE. Photo is the hub; Phase F adds a
 * viewfinder swipe peer and the [dragProgress] parameter drives a linear indicator
 * animation. In Phase C only PHOTO is in [enabledModalities] — D lights up VIDEO and
 * E lights up VOICE. Segments outside [enabledModalities] render at 35% alpha and are
 * non-clickable.
 */
@Composable
fun ModalityPill(
    current: Modality,
    enabledModalities: Set<Modality>,
    onChange: (Modality) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") dragProgress: Float = 0f,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.15f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fixed left-to-right order matches the viewfinder swipe carousel added in Phase F:
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
    val textColor = when {
        !enabled -> Color.White.copy(alpha = 0.35f)
        selected -> Color.Black
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
            .background(if (selected && enabled) Color.White.copy(alpha = 0.9f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
