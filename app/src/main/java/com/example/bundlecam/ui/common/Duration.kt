package com.example.bundlecam.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** `M:SS` clock formatting for media durations. Negatives clamp to zero. */
fun formatClockDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Wall-clock elapsed ms since [startedAtMs], re-sampled at [tickMs]. Restarts when
 * [startedAtMs] changes. Used by the video REC pill and the voice overlay's elapsed
 * caption.
 */
@Composable
fun rememberElapsedMs(startedAtMs: Long, tickMs: Long = 500L): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(tickMs)
        }
    }
    return (nowMs - startedAtMs).coerceAtLeast(0L)
}
