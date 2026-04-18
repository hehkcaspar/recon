package com.example.bundlecam.data.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class StagingSession(val id: String, val dir: File)

class StagingStore(context: Context) {
    private val baseDir: File =
        File(context.applicationContext.filesDir, "staging").apply { mkdirs() }

    fun createSession(): StagingSession {
        val id = UUID.randomUUID().toString()
        val dir = sessionDir(id).apply { mkdirs() }
        return StagingSession(id, dir)
    }

    fun sessionFor(id: String): StagingSession = StagingSession(id, sessionDir(id))

    private fun sessionDir(id: String): File = File(baseDir, "session-$id")

    suspend fun writePhoto(session: StagingSession, bytes: ByteArray): File =
        withContext(Dispatchers.IO) {
            val file = File(session.dir, "${UUID.randomUUID()}.jpg")
            file.writeBytes(bytes)
            file
        }

    suspend fun deleteFile(file: File) = withContext(Dispatchers.IO) {
        file.delete()
    }

    suspend fun deleteSession(session: StagingSession) = withContext(Dispatchers.IO) {
        session.dir.deleteRecursively()
    }

    /**
     * Drop a marker file inside the session directory so [OrphanRecovery] knows this
     * session was explicitly discarded (not merely orphaned by a process death mid-queue).
     * Synchronous because the window between a discard gesture and a possible process
     * kill is narrow — deferring it to a coroutine would race the kill and lose the
     * marker, causing the queue to resurrect on next launch.
     */
    fun markDiscarded(session: StagingSession) {
        runCatching { File(session.dir, DISCARD_MARKER).createNewFile() }
    }

    fun unmarkDiscarded(session: StagingSession) {
        runCatching { File(session.dir, DISCARD_MARKER).delete() }
    }

    fun isDiscarded(session: StagingSession): Boolean =
        File(session.dir, DISCARD_MARKER).exists()

    fun listSessions(): List<StagingSession> =
        baseDir.listFiles { f -> f.isDirectory && f.name.startsWith(SESSION_PREFIX) }
            ?.map { StagingSession(it.name.removePrefix(SESSION_PREFIX), it) }
            ?: emptyList()

    private companion object {
        const val SESSION_PREFIX = "session-"
        const val DISCARD_MARKER = ".discarded"
    }
}
