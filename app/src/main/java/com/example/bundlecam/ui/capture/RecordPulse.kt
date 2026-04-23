package com.example.bundlecam.ui.capture

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * Shared pulse-alpha helper used by all four "live recording" indicators — video
 * shutter fill, voice shutter fill, video REC pill dot, voice overlay REC dot.
 * Keeping one parametric helper keeps their rhythms visually consistent.
 */
@Composable
fun rememberRecordPulseAlpha(
    from: Float,
    to: Float,
    durationMs: Int,
    label: String,
): State<Float> {
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(
        initialValue = from,
        targetValue = to,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "$label-alpha",
    )
}
