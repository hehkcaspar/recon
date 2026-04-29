package com.example.bundlecam.ui.capture

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bundlecam.ui.common.formatClockDuration
import com.example.bundlecam.ui.common.rememberElapsedMs
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.sin

/**
 * Red-pill elapsed-time readout shown above the zoom chips while a video recording
 * is live. The pill + pulsing white dot read as a universal "REC" affordance; the
 * M:SS caption ticks every 500ms.
 *
 * When [audioEnabled] is true a small white soundwave is appended on the right —
 * the visual hint that the clip carries an audio track. The wave's intensity is
 * driven by [audioAmplitudeFlow] (CameraX-reported, ~1Hz), so it visibly responds
 * when the user speaks. The pill stays centered (Row wraps content; the wave just
 * widens it symmetrically around its existing horizontal anchor).
 *
 * Counter-rotates with device orientation to match the other control-row icons.
 */
@Composable
fun VideoRecordingPill(
    startedAtMs: Long,
    contentRotation: Float,
    audioEnabled: Boolean,
    audioAmplitudeFlow: StateFlow<Float>,
    modifier: Modifier = Modifier,
) {
    val elapsedMs = rememberElapsedMs(startedAtMs)
    val dotAlpha by rememberRecordPulseAlpha(from = 0.35f, to = 1f, durationMs = 700, label = "rec-dot")

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(CaptureColors.DeleteRed)
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .rotate(contentRotation),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = dotAlpha)),
        )
        Text(
            text = formatClockDuration(elapsedMs),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        if (audioEnabled) {
            val rawAmplitude by audioAmplitudeFlow.collectAsStateWithLifecycle()
            // Smooth the ~1Hz Status updates so bars don't snap, but keep the response
            // snappy enough that the bars feel reactive when the user starts/stops talking.
            val smoothed by animateFloatAsState(
                targetValue = rawAmplitude,
                animationSpec = tween(durationMillis = 140),
                label = "video-amp-smoothed",
            )
            VideoAudioWave(
                amplitude = smoothed,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .height(12.dp)
                    .width(20.dp),
            )
        }
    }
}

/**
 * 4-bar mini equalizer rendered in white. A continuously-restarting phase ramp drives
 * a sine envelope per bar; irregular phase offsets break the lockstep so the bars don't
 * read as a 2+2 mirrored pair. [amplitude] in [0,1] scales the per-bar height; a small
 * floor keeps them visible during silence so the user sees "audio is on" even when no
 * voice is detected. Drawn inside the red pill — white reads cleanly on the red bg.
 */
@Composable
private fun VideoAudioWave(
    amplitude: Float,
    modifier: Modifier = Modifier,
) {
    // Continuous 0→1 ramp that restarts (vs. ping-pong) — this keeps the sine moving
    // in one direction so the eye reads the wave as "flowing" rather than "breathing".
    // ~520ms per cycle is the sweet spot between "alive" and "frantic" at this size.
    val transition = rememberInfiniteTransition(label = "video-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "video-wave-phase",
    )
    // Irregular offsets — not 0/0.25/0.5/0.75 — so adjacent bars don't pair up
    // visually. The bars read as 4 independent oscillators rather than a step function.
    val phaseOffsets = remember { floatArrayOf(0f, 0.18f, 0.55f, 0.78f) }
    Canvas(modifier = modifier) {
        val barCount = phaseOffsets.size
        val barWidthPx = size.width / (barCount * 2f - 1f) // bar + gap = 2*bar; last has no gap
        val gapPx = barWidthPx
        val centerY = size.height / 2f
        val maxHalf = size.height / 2f
        val floorHalf = (size.height * 0.10f).coerceAtLeast(0.5f)
        // Map raw amplitude to a more perceptual envelope. CameraX values cluster low
        // for ordinary speech; sqrt boosts mid-volume so bars actually move.
        val ampShaped = kotlin.math.sqrt(amplitude.coerceIn(0f, 1f))
        for (i in 0 until barCount) {
            val cyclePos = (phase + phaseOffsets[i]) % 1f
            val sineEnv = (sin(cyclePos * 2f * PI).toFloat() + 1f) / 2f // [0,1]
            // Wider envelope (0.25→1.0 instead of 0.55→1.0) — bigger swing per cycle
            // makes the bars look more vibrant. Combined with the irregular offsets,
            // each bar dips much lower than its neighbours at any given frame.
            val activeHalf = maxHalf * ampShaped * (0.25f + 0.75f * sineEnv)
            val halfHeight = activeHalf.coerceAtLeast(floorHalf)
            val x = i * (barWidthPx + gapPx) + barWidthPx / 2f
            drawLine(
                color = Color.White,
                start = Offset(x, centerY - halfHeight),
                end = Offset(x, centerY + halfHeight),
                strokeWidth = barWidthPx,
                cap = StrokeCap.Round,
            )
        }
    }
}
