package com.example.bundlecam.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single-slot "hold this, then do the final thing after a timeout" helper. Whoever calls
 * [take] first owns the item, so a racing undo tap and the timeout fire path can't both
 * run cleanup. Supports [pause] and [resume] so an overlapping higher-priority Error
 * banner can pause the Undo timeout while the Undo action is covered.
 */
internal class TimedSlot<T>(
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var item: T? = null
    private var job: Job? = null
    private var onTimeoutCallback: (suspend (T) -> Unit)? = null
    private var remainingMs: Long = 0L
    private var startedAtMs: Long = 0L
    var isPaused: Boolean = false
        private set

    fun stash(
        value: T,
        timeoutMs: Long,
        pausedInitially: Boolean = false,
        onTimeout: suspend (T) -> Unit,
    ) {
        take()
        item = value
        onTimeoutCallback = onTimeout
        remainingMs = timeoutMs.coerceAtLeast(1L)
        isPaused = pausedInitially
        if (!pausedInitially) {
            scheduleCountdown(remainingMs)
        }
    }

    fun pause() {
        if (item == null || isPaused) return
        val elapsed = (clockMs() - startedAtMs).coerceAtLeast(0L)
        remainingMs = (remainingMs - elapsed).coerceAtLeast(100L)
        isPaused = true
        job?.cancel()
        job = null
    }

    fun resume() {
        if (item == null || !isPaused) return
        isPaused = false
        scheduleCountdown(remainingMs)
    }

    fun take(): T? {
        val v = item
        item = null
        onTimeoutCallback = null
        remainingMs = 0L
        isPaused = false
        job?.cancel()
        job = null
        return v
    }

    private fun scheduleCountdown(waitMs: Long) {
        startedAtMs = clockMs()
        job?.cancel()
        job = scope.launch {
            delay(waitMs)
            val callback = onTimeoutCallback
            val taken = take() ?: return@launch
            callback?.invoke(taken)
        }
    }
}
