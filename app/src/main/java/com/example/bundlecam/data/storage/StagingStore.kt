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

    /**
     * Synchronous allocator for recorder outputs. Returns the target `File` without
     * creating it — Recorder / MediaRecorder open it themselves. Because the file may
     * never materialize (process killed mid-record, mic denied), OrphanRecovery treats
     * zero-byte `.mp4` / `.m4a` as discardable.
     */
    fun allocateVideoOutput(session: StagingSession): File =
        File(session.dir, "${UUID.randomUUID()}.mp4")

    fun allocateVoiceOutput(session: StagingSession): File =
        File(session.dir, "${UUID.randomUUID()}.m4a")

    /**
     * Append one line to `.order` inside the session directory recording the order in
     * which queue items were started. Photo `lastModified` matches capture time, but
     * video/voice `lastModified` is *stop* time — without this log, OrphanRecovery would
     * re-order a `[Video, Photo]` capture sequence as `[Photo, Video]` on restore.
     *
     * Format: `{systemClockMs}\t{filename}\n`. Single-writer per session; O_APPEND gives
     * line-level atomicity. Callers invoke this at the point the queue item is logically
     * added (photo: after write; video/voice: before recorder start — file may not exist
     * yet, but OrphanRecovery filters stale entries).
     */
    suspend fun appendOrderEntry(session: StagingSession, filename: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                File(session.dir, ORDER_LOG)
                    .appendText("${System.currentTimeMillis()}\t$filename\n")
            }
        }

    /** Returns filenames from the `.order` log in capture order, or null if no log exists. */
    fun readOrderLog(session: StagingSession): List<String>? {
        val file = File(session.dir, ORDER_LOG)
        if (!file.exists()) return null
        return file.readLines().mapNotNull { line ->
            line.split('\t').getOrNull(1)?.takeIf { it.isNotBlank() }
        }
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
        const val ORDER_LOG = ".order"
    }
}
