package com.example.bundlecam.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.example.bundlecam.ui.common.formatClockDuration
import com.example.bundlecam.ui.common.rememberElapsedMs
import kotlinx.coroutines.flow.StateFlow

/** Max history length — 128 samples at ~33ms each is ~4.2 seconds of visible waveform. */
private const val WAVEFORM_SAMPLE_COUNT = 128

/**
 * Covers the camera preview when modality is VOICE. Idle state shows a mic glyph +
 * prompt text with a flat centerline waveform. Recording state shows a scrolling
 * symmetric bar visualizer driven by [amplitudeFlow] (fed by VoiceController's
 * `MediaRecorder.getMaxAmplitude()` polling at ~30Hz), a pulsing "Recording…" label,
 * and a pulsing red dot.
 */
@Composable
fun VoiceOverlay(
    recording: Boolean,
    amplitudeFlow: StateFlow<Int>,
    recordingStartedAtMs: Long?,
    modifier: Modifier = Modifier,
) {
    // Ring buffer of recent amplitudes. Using a FloatArray + index pointer is cheaper
    // than rebuilding a List on every tick — important at 30Hz on low-end devices.
    val history = remember { FloatArray(WAVEFORM_SAMPLE_COUNT) }
    // writeIndex is mutated from a LaunchedEffect; we don't use a State since we read
    // it inside the same LaunchedEffect that writes it. Canvas reads `history` via
    // its snapshot during drawing; a second state trigger (historyRevision) forces
    // recomposition for the Canvas on each new sample.
    val historyRevision = remember { androidx.compose.runtime.mutableIntStateOf(0) }

    // Clear the buffer on recording-state transitions so stale samples from a prior
    // session don't linger visually.
    LaunchedEffect(recording) {
        for (i in history.indices) history[i] = 0f
        historyRevision.intValue++
    }

    // Subscribe directly to the StateFlow. An earlier version wrapped the read in
    // snapshotFlow { amplitudeFlow.value } — that only re-emits when a Compose-tracked
    // State read changes; StateFlow.value is not such a read, so the collector fired
    // once for the initial 0 and never again. Symptom: waveform stuck as a flat line
    // the whole recording.
    LaunchedEffect(amplitudeFlow) {
        var writeIndex = 0
        amplitudeFlow.collect { amp ->
            val normalized = (amp / 32767f).coerceIn(0f, 1f)
            val previous = history[writeIndex]
            history[writeIndex] = normalized
            writeIndex = (writeIndex + 1) % history.size
            // Skip the redraw when the write doesn't change the buffer at this index
            // (silence overwriting silence). Keeps Canvas idle during mic-quiet stretches.
            if (normalized != previous) historyRevision.intValue++
        }
    }

    val scrimColor = if (recording) Color.Black.copy(alpha = 0.88f) else Color.Black.copy(alpha = 0.72f)
    Box(
        modifier = modifier.background(scrimColor),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        ) {
            if (!recording) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Tap shutter to record voice",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(20.dp))
            } else {
                val dotAlpha by rememberRecordPulseAlpha(
                    from = 0.4f, to = 1f, durationMs = 700, label = "voice-rec-dot",
                )
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.FiberManualRecord,
                        contentDescription = null,
                        tint = CaptureColors.RecordRed.copy(alpha = dotAlpha),
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape),
                    )
                    Text(
                        text = "Recording…",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
            WaveformStrip(
                history = history,
                revision = historyRevision.intValue,
                recording = recording,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            )
            // Elapsed-time readout lives under the waveform — the voice page has no
            // camera preview, so the focal composition is the waveform band and the
            // timer is its natural caption. (Video keeps its timer in the lens-flip
            // slot on the control row.)
            if (recording && recordingStartedAtMs != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = formatClockDuration(rememberElapsedMs(recordingStartedAtMs)),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

/**
 * Render the amplitude history as a symmetric bar strip centered on the vertical
 * midline. Each sample is a vertical line from `(centerY - h)` to `(centerY + h)`.
 * When idle the strip shows a flat centerline. Scroll direction: oldest samples on
 * the left, newest on the right — fed by the ring buffer's writeIndex.
 *
 * [revision] is keyed into the Canvas so the modifier re-draws on each new sample.
 */
@Composable
private fun WaveformStrip(
    history: FloatArray,
    revision: Int,
    recording: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val lineColor = if (recording) CaptureColors.RecordRed else Color.White.copy(alpha = 0.55f)
        val barWidth = width / history.size
        // Force the draw lambda's closure to capture `revision` — without this, two
        // invocations with identical history/recording produce a structurally-equal
        // modifier and Canvas skips the redraw on a revision bump.
        @Suppress("UNUSED_EXPRESSION") revision
        // Minimum visible centerline so idle-state "flat line" is perceptible.
        val minBarHalfHeight = 0.5.dp.toPx()
        history.forEachIndexed { i, sample ->
            val halfHeight = (sample * height / 2f).coerceAtLeast(minBarHalfHeight)
            val x = i * barWidth + barWidth / 2f
            drawLine(
                color = lineColor,
                start = Offset(x, centerY - halfHeight),
                end = Offset(x, centerY + halfHeight),
                strokeWidth = (barWidth * 0.55f).coerceAtLeast(1.5f),
                cap = StrokeCap.Round,
            )
        }
    }
}
