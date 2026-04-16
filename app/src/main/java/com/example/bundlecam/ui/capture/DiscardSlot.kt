package com.example.bundlecam.ui.capture

import com.example.bundlecam.data.storage.StagingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Holds a staging session in the "pending discard" state and runs a single-shot timer
 * for the undo window. The timer's fire path uses [take] so a racing Undo tap cancels
 * the cleanup without any extra identity check: whoever calls [take] first owns the session.
 */
internal class DiscardSlot(private val scope: CoroutineScope) {
    private var session: StagingSession? = null
    private var job: Job? = null

    fun stash(
        s: StagingSession,
        timeoutMs: Long,
        onTimeout: suspend (StagingSession) -> Unit,
    ) {
        take()
        session = s
        job = scope.launch {
            delay(timeoutMs)
            val taken = take() ?: return@launch
            onTimeout(taken)
        }
    }

    fun take(): StagingSession? {
        val s = session
        session = null
        job?.cancel()
        job = null
        return s
    }
}
