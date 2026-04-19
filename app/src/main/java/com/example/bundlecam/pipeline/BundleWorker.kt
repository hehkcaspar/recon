package com.example.bundlecam.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
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
private const val CHANNEL_ID = "recon_pipeline"
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

        Log.i(
            TAG,
            "processManifest bundleId=${manifest.bundleId} photos=${manifest.orderedPhotos.size} " +
                "quality=$quality individual=$saveIndividual stitched=$saveStitched",
        )

        if (saveIndividual) {
            // Bounded-wait location refresh so photos captured before the first fix still
            // get GPS. Scoped to the raw-copy branch because only per-photo EXIF uses it;
            // the stitched JPEG is canvas-rendered and never carried GPS.
            withTimeoutOrNull(LOCATION_REFRESH_TIMEOUT_MS) { container.locationProvider.refresh() }
            val backfillLocation = container.locationProvider.getCachedOrNull()

            manifest.orderedPhotos.forEachIndexed { index, photo ->
                val file = File(photo.localPath)
                container.exifWriter.stampFinalMetadata(
                    file = file,
                    comment = StorageLayout.photoExifComment(manifest.bundleId, index + 1),
                    backfillLocation = backfillLocation,
                )
            }

            val entries = manifest.orderedPhotos.mapIndexed { index, photo ->
                StorageLayout.bundlePhotoName(manifest.bundleId, index + 1) to File(photo.localPath)
            }
            container.safStorage.copyLocalFiles(
                rootUri = rootUri,
                subPath = StorageLayout.bundlePath(manifest.bundleId),
                entries = entries,
            )
        }

        if (saveStitched) {
            val stitcher = Stitcher()
            val stitchFile = File(
                applicationContext.cacheDir,
                StorageLayout.stitchFileName(manifest.bundleId),
            )
            try {
                val sources = manifest.orderedPhotos.map {
                    StitchSource(File(it.localPath), it.rotationDegrees)
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

    private fun createForegroundInfo(bundleId: String): ForegroundInfo {
        ensureChannel()
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

    private fun ensureChannel() {
        val mgr = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Saving bundles",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        const val KEY_BUNDLE_ID = "bundle_id"
        const val KEY_ERROR = "error"
        const val TAG_ALL = "recon_bundle"

        // Process-wide single-concurrency gate for bundle workers.
        private val workMutex = Mutex()
    }
}
