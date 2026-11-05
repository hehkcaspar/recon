package com.example.bundlecam.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Accessible feedback banner per `COMPONENT-CONTRACT.md`:
 * - Minimum 48dp height with 48dp-tall action button (`64–72 × 48dp` or `144 × 48dp` stacked).
 * - At large font scales (>= 1.5×) or long messages, stacks the action button below a
 *   bounded scrollable message area so complete text and the action remain reachable
 *   without covering `Stop` or stealing queue gestures.
 * - Exposes `LiveRegionMode.Polite` so TalkBack announces new messages once.
 */
@Composable
fun ActionBanner(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.inverseSurface,
    contentColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
) {
    val fontScale = LocalDensity.current.fontScale
    val stackAction = fontScale >= 1.5f || message.length > 72

    Surface(
        modifier = modifier
            .heightIn(min = 48.dp, max = 160.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 6.dp,
    ) {
        if (stackAction) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onAction,
                        modifier = Modifier
                            .widthIn(min = 72.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                )
                TextButton(
                    onClick = onAction,
                    modifier = Modifier
                        .widthIn(min = 64.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
