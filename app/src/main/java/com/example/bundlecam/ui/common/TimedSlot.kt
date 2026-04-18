package com.example.bundlecam.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single-slot "hold this, then do the final thing after a timeout" helper. Whoever calls
 * [take] first owns the item, so a racing undo tap and the timeout fire path can't both
 * run cleanup. Used by both the capture-time discard undo and the bundle-delete undo.
 */
internal class TimedSlot<T>(private val scope: CoroutineScope) {
    private var item: T? = null
    private var job: Job? = null

    fun stash(
        value: T,
        timeoutMs: Long,
        onTimeout: suspend (T) -> Unit,
    ) {
        take()
        item = value
        job = scope.launch {
            delay(timeoutMs)
            val taken = take() ?: return@launch
            onTimeout(taken)
        }
    }

    fun take(): T? {
        val v = item
        item = null
        job?.cancel()
        job = null
        return v
    }
}
