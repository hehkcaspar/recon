package com.example.bundlecam.pipeline

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.bundlecam.ReconApp
import com.example.bundlecam.data.settings.StitchQuality
import com.example.bundlecam.data.storage.StorageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "Recon/BundleWorker"
private const val NOTIFICATION_ID = 2026
private const val LOCATION_REFRESH_TIMEOUT_MS = 2000L

class BundleWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val container by lazy { (applicationContext as ReconApp).container }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val bundleId = inputData.getString(KEY_BUNDLE_ID)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "missing bundle_id"))

        Log.i(TAG, "doWork start bundleId=$bundleId attempt=$runAttemptCount")

        runCatching { setForeground(createForegroundInfo(bundleId)) }
            .onFailure { Log.w(TAG, "setForeground failed for $bundleId", it) }

        // Serialize all bundle workers across the app process regardless of what
        // scheduling policy WorkManager picks. Single-concurrency keeps stitch memory bounded.
        workMutex.withLock {
            val manifest = container.manifestStore.load(bundleId)
                ?: return@withLock Result.failure(
                    workDataOf(KEY_BUNDLE_ID to bundleId, KEY_ERROR to "manifest not found"),
                )

            try {
                processManifest(manifest)
                Log.i(TAG, "doWork success bundleId=$bundleId")
                Result.success(workDataOf(KEY_BUNDLE_ID to bundleId))
            } catch (t: Throwable) {
                Log.e(TAG, "BundleWorker failed for $bundleId", t)
                Result.failure(
                    workDataOf(
                        KEY_BUNDLE_ID to bundleId,
                        KEY_ERROR to (t.message ?: t.javaClass.simpleName),
                    ),
                )
            }
        }
    }

    private suspend fun processManifest(manifest: PendingBundle) {
        val rootUri = manifest.rootUriString.toUri()
        val quality = runCatching { StitchQuality.valueOf(manifest.stitchQuality) }
            .getOrDefault(StitchQuality.STANDARD)
        val saveIndividual = manifest.saveIndividualPhotos
        val saveStitched = manifest.saveStitchedImage

        // Global capture-order index: each item in orderedItems gets a 1-based index
        // computed from its position in the ordered list. A mixed queue
        // `[Photo, Video, Photo]` produces files `-p-001, -v-002, -p-003` — sparse per
        // modality, dense across modalities. Downstream tools that list the bundle
        // folder recursively see chronological order from filenames alone.
        val plan = planRouting(manifest)

        Log.i(
            TAG,
            "processManifest bundleId=${manifest.bundleId} items=${manifest.orderedItems.size} " +
                "photos=${plan.photos.size} videos=${plan.videos.size} voices=${plan.voices.size} " +
                "quality=$quality individual=$saveIndividual stitched=$saveStitched",
        )

        if (saveIndividual && plan.photos.isNotEmpty()) {
            // Bounded-wait location refresh so photos captured before the first fix still
            // get GPS. Scoped to the raw-copy branch because only per-photo EXIF uses it;
            // the stitched JPEG is canvas-rendered and never carried GPS.
            withTimeoutOrNull(LOCATION_REFRESH_TIMEOUT_MS) { container.locationProvider.refresh() }
            val backfillLocation = container.locationProvider.getCachedOrNull()

            plan.photos.forEach { (photo, globalIndex) ->
                container.exifWriter.stampFinalMetadata(
                    file = File(photo.localPath),
                    comment = StorageLayout.photoExifComment(manifest.bundleId, globalIndex),
                    backfillLocation = backfillLocation,
                )
            }

            copyModality(rootUri, plan.photos, StorageLayout.bundlePhotosPath(manifest.bundleId)) { i ->
                StorageLayout.bundlePhotoName(manifest.bundleId, i)
            }
        }

        // Video/voice: stored as-is. MP4 rotation lives in tkhd.matrix (written by
        // Recorder at start-time), .m4a has no orientation. No EXIF, no stitch.
        copyModality(rootUri, plan.videos, StorageLayout.bundleVideosPath(manifest.bundleId)) { i ->
            StorageLayout.bundleVideoName(manifest.bundleId, i)
        }
        copyModality(rootUri, plan.voices, StorageLayout.bundleAudioPath(manifest.bundleId)) { i ->
            StorageLayout.bundleAudioName(manifest.bundleId, i)
        }

        if (saveStitched && plan.photos.isNotEmpty()) {
            val stitcher = Stitcher()
            val stitchFile = File(
                applicationContext.cacheDir,
                StorageLayout.stitchFileName(manifest.bundleId),
            )
            try {
                val sources = plan.photos.map { (photo, _) ->
                    StitchSource(File(photo.localPath), photo.rotationDegrees)
                }
                stitcher.stitch(sources, quality, stitchFile)

                container.exifWriter.stampFinalMetadata(
                    file = stitchFile,
                    comment = StorageLayout.stitchExifComment(manifest.bundleId),
                )

                container.safStorage.copyLocalFile(
                    rootUri = rootUri,
                    subPath = StorageLayout.STITCHED_PATH,
                    fileName = StorageLayout.stitchFileName(manifest.bundleId),
                    src = stitchFile,
                )
            } finally {
                stitchFile.delete()
            }
        }

        runCatching { container.manifestStore.delete(manifest.bundleId) }
            .onFailure { Log.w(TAG, "Failed to delete manifest for ${manifest.bundleId}", it) }

        // Multi-bundle commits share one staging session. The last worker to finish
        // deletes it; earlier workers defer to OrphanRecovery.
        val stillReferenced = container.manifestStore.isSessionReferenced(
            sessionId = manifest.sessionId,
            excludeBundleId = manifest.bundleId,
        )
        if (!stillReferenced) {
            runCatching {
                container.stagingStore.deleteSession(container.stagingStore.sessionFor(manifest.sessionId))
            }.onFailure { Log.w(TAG, "Failed to delete staging session for ${manifest.bundleId}", it) }
        } else {
            Log.i(TAG, "Staging session ${manifest.sessionId} still referenced; deferring delete")
        }
    }

    private suspend fun copyModality(
        rootUri: Uri,
        items: List<IndexedItem<out PendingItem>>,
        subPath: List<String>,
        nameFor: (Int) -> String,
    ) {
        if (items.isEmpty()) return
        val entries = items.map { (item, index) -> nameFor(index) to File(item.localPath) }
        container.safStorage.copyLocalFiles(rootUri = rootUri, subPath = subPath, entries = entries)
    }

    private fun createForegroundInfo(bundleId: String): ForegroundInfo {
        val notification: Notification =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle("Saving bundle")
                .setContentText(bundleId)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_BUNDLE_ID = "bundle_id"
        const val KEY_ERROR = "error"
        const val TAG_ALL = "recon_bundle"
        const val CHANNEL_ID = "recon_pipeline"

        // Process-wide single-concurrency gate for bundle workers.
        private val workMutex = Mutex()

        /**
         * Pure routing plan: partition [manifest.orderedItems] by type, assigning each a
         * 1-based global capture-order index derived from its position in the list.
         * Extracted as a companion so [BundleWorkerRoutingTest] can exercise it without
         * touching SAF or any Android dependency.
         */
        fun planRouting(manifest: PendingBundle): RoutingPlan {
            val photos = mutableListOf<IndexedItem<PendingPhoto>>()
            val videos = mutableListOf<IndexedItem<PendingVideo>>()
            val voices = mutableListOf<IndexedItem<PendingVoice>>()
            manifest.orderedItems.forEachIndexed { zeroIndex, item ->
                val global = zeroIndex + 1
                when (item) {
                    is PendingPhoto -> photos.add(IndexedItem(item, global))
                    is PendingVideo -> videos.add(IndexedItem(item, global))
                    is PendingVoice -> voices.add(IndexedItem(item, global))
                }
            }
            return RoutingPlan(photos, videos, voices)
        }
    }
}

/** Item plus its global 1-based capture-order index within the bundle. */
data class IndexedItem<T>(val item: T, val globalIndex: Int)

/** Typed partition of a manifest's ordered items. */
data class RoutingPlan(
    val photos: List<IndexedItem<PendingPhoto>>,
    val videos: List<IndexedItem<PendingVideo>>,
    val voices: List<IndexedItem<PendingVoice>>,
)
