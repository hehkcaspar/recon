package com.example.bundlecam.data.storage

import android.content.Context
import com.example.bundlecam.data.storage.StorageLayout.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class StagingSession(val id: String, val dir: File)

enum class TakeRecordState {
    Prepared,
    Recording,
    Finalizing,
    Finalized,
    Aborted,
}

data class TakeRecord(
    val takeId: String,
    val mediaKind: MediaKind,
    val ordinal: Int,
    val tempName: String,
    val finalName: String,
    val startedAtMs: Long,
    val state: TakeRecordState,
)

class StagingStore(
    context: Context? = null,
    baseDirectoryOverride: File? = null,
) {
    private val baseDir: File = (baseDirectoryOverride
        ?: File(requireNotNull(context).applicationContext.filesDir, "staging")).apply { mkdirs() }

    private val orderLogMutex = Mutex()

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

    fun allocateVideoOutput(session: StagingSession): File =
        File(session.dir, "${UUID.randomUUID()}.mp4")

    fun allocateVoiceOutput(session: StagingSession): File =
        File(session.dir, "${UUID.randomUUID()}.m4a")

    /**
     * Allocate paired temporary and final files for a tracked [takeId] and [mediaKind].
     * The recorder or photo writer writes to `tempFile`; `finalizeOnce` atomically
     * promotes `tempFile` to `finalFile` once validated.
     */
    fun allocateTakeFiles(
        session: StagingSession,
        takeId: String,
        mediaKind: MediaKind,
    ): Pair<File, File> {
        val ext = when (mediaKind) {
            MediaKind.Photo -> "jpg"
            MediaKind.Video -> "mp4"
            MediaKind.Voice -> "m4a"
        }
        val tempFile = File(session.dir, "$TEMP_PREFIX$takeId.$ext")
        val finalFile = File(session.dir, "$takeId.$ext")
        return tempFile to finalFile
    }

    /**
     * Atomically promotes [tempFile] to [finalFile] if not already promoted.
     * Idempotent: if [finalFile] already exists with non-zero length and [tempFile]
     * is gone, returns true.
     */
    suspend fun promoteTempToFinal(tempFile: File, finalFile: File): Boolean =
        withContext(Dispatchers.IO) {
            orderLogMutex.withLock {
                if (finalFile.exists() && finalFile.length() > 0L && !tempFile.exists()) {
                    return@withLock true
                }
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    runCatching { tempFile.delete() }
                    return@withLock false
                }
                if (tempFile.renameTo(finalFile)) {
                    return@withLock true
                }
                runCatching {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                    finalFile.exists() && finalFile.length() > 0L
                }.getOrDefault(false)
            }
        }

    /**
     * Persist the latest [TakeRecord] state into `.takes` inside [session].
     */
    suspend fun writeTakeRecord(session: StagingSession, record: TakeRecord) =
        withContext(Dispatchers.IO) {
            orderLogMutex.withLock {
                runCatching {
                    val file = File(session.dir, TAKES_LOG)
                    val line = listOf(
                        record.takeId,
                        record.mediaKind.name,
                        record.ordinal.toString(),
                        record.tempName,
                        record.finalName,
                        record.startedAtMs.toString(),
                        record.state.name,
                    ).joinToString("\t") + "\n"
                    file.appendText(line)
                }
            }
        }

    /**
     * Read the latest [TakeRecord] per `takeId` from `.takes` in [session].
     */
    fun readTakeRecords(session: StagingSession): Map<String, TakeRecord> {
        val file = File(session.dir, TAKES_LOG)
        if (!file.exists()) return emptyMap()
        val result = LinkedHashMap<String, TakeRecord>()
        runCatching {
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 7) {
                    val takeId = parts[0]
                    val kind = runCatching { MediaKind.valueOf(parts[1]) }.getOrNull() ?: return@forEachLine
                    val ordinal = parts[2].toIntOrNull() ?: 0
                    val tempName = parts[3]
                    val finalName = parts[4]
                    val startedAt = parts[5].toLongOrNull() ?: 0L
                    val state = runCatching { TakeRecordState.valueOf(parts[6]) }.getOrNull() ?: return@forEachLine
                    result[takeId] = TakeRecord(
                        takeId = takeId,
                        mediaKind = kind,
                        ordinal = ordinal,
                        tempName = tempName,
                        finalName = finalName,
                        startedAtMs = startedAt,
                        state = state,
                    )
                }
            }
        }
        return result
    }

    /**
     * Append one line to `.order` inside the session directory recording the order in
     * which queue items were started. Idempotent by [filename] (and optional [takeId]):
     * repeated calls for the same take never produce duplicate order entries.
     */
    suspend fun appendOrderEntry(
        session: StagingSession,
        filename: String,
        takeId: String? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        orderLogMutex.withLock {
            runCatching {
                val file = File(session.dir, ORDER_LOG)
                if (file.exists()) {
                    val existing = file.readLines().any { line ->
                        val cols = line.split('\t')
                        cols.getOrNull(1) == filename ||
                            (takeId != null && cols.getOrNull(2) == takeId)
                    }
                    if (existing) return@runCatching
                }
                val suffix = if (takeId != null) "\t$takeId" else ""
                file.appendText("$timestampMs\t$filename$suffix\n")
            }
        }
    }

    /**
     * Returns unique filenames from the `.order` log in capture order, or null if no log exists.
     */
    fun readOrderLog(session: StagingSession): List<String>? {
        val file = File(session.dir, ORDER_LOG)
        if (!file.exists()) return null
        return file.readLines().mapNotNull { line ->
            line.split('\t').getOrNull(1)?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    suspend fun deleteFile(file: File) = withContext(Dispatchers.IO) {
        file.delete()
    }

    suspend fun deleteSession(session: StagingSession) = withContext(Dispatchers.IO) {
        session.dir.deleteRecursively()
    }

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

    companion object {
        const val SESSION_PREFIX = "session-"
        const val DISCARD_MARKER = ".discarded"
        const val ORDER_LOG = ".order"
        const val TAKES_LOG = ".takes"
        const val TEMP_PREFIX = ".tmp-"
    }
}
