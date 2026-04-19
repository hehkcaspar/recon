package com.example.bundlecam.pipeline

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.example.bundlecam.data.camera.decodeThumbnail
import com.example.bundlecam.data.exif.OrientationCodec
import com.example.bundlecam.data.storage.StagingSession
import com.example.bundlecam.data.storage.StagingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "Recon/OrphanRecovery"

data class RestoredPhoto(
    val localFile: File,
    val thumbnail: ImageBitmap,
    val rotationDegrees: Int,
    val capturedAt: Long,
)

data class RestoredQueue(
    val session: StagingSession,
    val items: List<RestoredPhoto>,
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

        val files = mostRecent.dir.listFiles { f -> f.extension == "jpg" }
            ?.sortedBy { it.lastModified() }
            ?: return@withContext null

        if (files.isEmpty()) return@withContext null

        val items = files.map { file ->
            val rotationDegrees = readExifRotation(file)
            RestoredPhoto(
                localFile = file,
                thumbnail = decodeThumbnail(file, rotationDegrees),
                rotationDegrees = rotationDegrees,
                capturedAt = file.lastModified(),
            )
        }

        Log.i(TAG, "Restoring queue from orphan session ${mostRecent.id} (${items.size} photos)")
        RestoredQueue(session = mostRecent, items = items)
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
