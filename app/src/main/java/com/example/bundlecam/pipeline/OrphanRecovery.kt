package com.example.bundlecam.pipeline

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.example.bundlecam.data.camera.decodeThumbnail
import com.example.bundlecam.data.camera.decodeVideoPoster
import com.example.bundlecam.data.camera.decodeVoiceThumbnail
import com.example.bundlecam.data.camera.isMediaFileReadable
import com.example.bundlecam.data.camera.readMediaDurationMs
import com.example.bundlecam.data.exif.OrientationCodec
import com.example.bundlecam.data.storage.StagingSession
import com.example.bundlecam.data.storage.StagingStore
import com.example.bundlecam.data.storage.StorageLayout
import com.example.bundlecam.data.storage.StorageLayout.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "Recon/OrphanRecovery"

sealed class RestoredItem {
    abstract val localFile: File
    abstract val thumbnail: ImageBitmap
    abstract val capturedAt: Long

    data class Photo(
        override val localFile: File,
        override val thumbnail: ImageBitmap,
        override val capturedAt: Long,
        val rotationDegrees: Int,
    ) : RestoredItem()

    data class Video(
        override val localFile: File,
        override val thumbnail: ImageBitmap,
        override val capturedAt: Long,
        val rotationDegrees: Int,
        val durationMs: Long,
    ) : RestoredItem()

    data class Voice(
        override val localFile: File,
        override val thumbnail: ImageBitmap,
        override val capturedAt: Long,
        val durationMs: Long,
    ) : RestoredItem()
}

data class RestoredQueue(
    val session: StagingSession,
    val items: List<RestoredItem>,
)

class OrphanRecovery(
    private val stagingStore: StagingStore,
    private val manifestStore: ManifestStore,
    private val workScheduler: WorkScheduler,
) {
    suspend fun recover(): RestoredQueue? = withContext(Dispatchers.IO) {
        workScheduler.pruneCompleted()

        val pendingIds = manifestStore.listPendingIds()
        val inFlightSessionIds = mutableSetOf<String>()

        for (bundleId in pendingIds) {
            val manifest = manifestStore.load(bundleId) ?: continue
            inFlightSessionIds.add(manifest.sessionId)
            if (!workScheduler.isTracked(bundleId)) {
                Log.i(TAG, "Re-enqueueing untracked manifest: $bundleId")
                workScheduler.enqueue(bundleId)
            }
        }

        val allSessions = stagingStore.listSessions()
        val (discarded, live) = allSessions.partition { stagingStore.isDiscarded(it) }
        if (discarded.isNotEmpty()) {
            Log.i(TAG, "Cleaning ${discarded.size} discarded session(s) left by last run")
            discarded.forEach { runCatching { stagingStore.deleteSession(it) } }
        }

        val orphanSessions = live.filter { it.id !in inFlightSessionIds }
        if (orphanSessions.isEmpty()) return@withContext null

        val mostRecent = orphanSessions.maxByOrNull { it.dir.lastModified() }
            ?: return@withContext null

        // Older orphans represent queues the user never committed and that aren't the
        // one we're about to restore. Leaving them on disk forever is a slow leak.
        val stale = orphanSessions.filter { it.id != mostRecent.id }
        if (stale.isNotEmpty()) {
            Log.i(TAG, "Deleting ${stale.size} stale orphan session(s)")
            stale.forEach { runCatching { stagingStore.deleteSession(it) } }
        }

        // Collect candidate files: jpg + mp4 + m4a, skip zero-byte (recorder never
        // flushed), skip hidden dot-files like `.order` and `.discarded`.
        val candidates = mostRecent.dir.listFiles { f ->
            f.isFile && f.length() > 0 && !f.name.startsWith('.') &&
                StorageLayout.mediaKindFor(f.name) != null
        }?.toList() ?: emptyList()

        if (candidates.isEmpty()) return@withContext null

        // Prefer the .order log for queue order (photo lastModified ≈ capture time,
        // but video lastModified = stop() time — mixing them via mtime scrambles order).
        // Fall back to mtime sort for pre-Phase-D sessions without an order log.
        val orderedCandidates = run {
            val logNames = stagingStore.readOrderLog(mostRecent)
            if (logNames != null) {
                val byName = candidates.associateBy { it.name }
                val logOrdered = logNames.mapNotNull { byName[it] }
                // Any files present but not in the log (shouldn't happen, but be safe):
                // append them in mtime order at the end.
                val logged = logOrdered.toSet()
                logOrdered + candidates.filter { it !in logged }.sortedBy { it.lastModified() }
            } else {
                candidates.sortedBy { it.lastModified() }
            }
        }

        val items = orderedCandidates.mapNotNull { file -> restoreItem(file) }

        if (items.isEmpty()) return@withContext null

        Log.i(TAG, "Restoring queue from orphan session ${mostRecent.id} (${items.size} items)")
        RestoredQueue(session = mostRecent, items = items)
    }

    private fun restoreItem(file: File): RestoredItem? = when (StorageLayout.mediaKindFor(file.name)) {
        MediaKind.Photo -> {
            val rotation = readExifRotation(file)
            RestoredItem.Photo(
                localFile = file,
                thumbnail = decodeThumbnail(file, rotation),
                rotationDegrees = rotation,
                capturedAt = file.lastModified(),
            )
        }
        MediaKind.Video -> {
            // CameraX 1.6 Media3 Muxer makes killed-mid-record mp4s usually playable;
            // when it doesn't (edge case, very early kill), the retriever probe fails
            // and we drop the file so the queue isn't polluted with a dead entry.
            if (!isMediaFileReadable(file)) {
                Log.w(TAG, "Dropping unplayable mp4 from orphan session: ${file.name}")
                runCatching { file.delete() }
                null
            } else {
                val poster = decodeVideoPoster(file)
                if (poster == null) null else RestoredItem.Video(
                    localFile = file,
                    thumbnail = poster,
                    // Rotation is baked into the MP4 container (tkhd.matrix), so UI
                    // treats it as 0° and relies on the container for correct playback
                    // orientation. ExifInterface on mp4 would return 0 anyway.
                    rotationDegrees = 0,
                    durationMs = readMediaDurationMs(file),
                    capturedAt = file.lastModified(),
                )
            }
        }
        MediaKind.Voice -> {
            // MediaRecorder has no equivalent of Media3 Muxer's crash resilience; a
            // killed-mid-record .m4a is typically unplayable. Drop torn files so the
            // restored queue is clean.
            if (!isMediaFileReadable(file)) {
                Log.w(TAG, "Dropping unplayable m4a from orphan session: ${file.name}")
                runCatching { file.delete() }
                null
            } else {
                RestoredItem.Voice(
                    localFile = file,
                    thumbnail = decodeVoiceThumbnail(),
                    durationMs = readMediaDurationMs(file),
                    capturedAt = file.lastModified(),
                )
            }
        }
        null -> null
    }

    private fun readExifRotation(file: File): Int {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return OrientationCodec.fromExif(orientation)
    }
}
