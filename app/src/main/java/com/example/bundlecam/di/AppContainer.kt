package com.example.bundlecam.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.bundlecam.data.audio.VoiceController
import com.example.bundlecam.data.exif.ExifWriter
import com.example.bundlecam.data.location.LocationProvider
import com.example.bundlecam.data.settings.SettingsRepository
import com.example.bundlecam.data.storage.BundleCounterStore
import com.example.bundlecam.data.storage.BundleLibrary
import com.example.bundlecam.data.storage.SafStorage
import com.example.bundlecam.data.storage.StagingStore
import com.example.bundlecam.pipeline.BundleWorker
import com.example.bundlecam.pipeline.ManifestStore
import com.example.bundlecam.pipeline.OrphanRecovery
import com.example.bundlecam.pipeline.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "Recon/AppContainer"

class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val safStorage: SafStorage = SafStorage(appContext)
    val bundleLibrary: BundleLibrary = BundleLibrary(appContext)
    val stagingStore: StagingStore = StagingStore(appContext)
    val bundleCounterStore: BundleCounterStore = BundleCounterStore(appContext)
    val manifestStore: ManifestStore = ManifestStore(appContext)
    val workScheduler: WorkScheduler = WorkScheduler(appContext)
    val orphanRecovery: OrphanRecovery = OrphanRecovery(stagingStore, manifestStore, workScheduler)
    val exifWriter: ExifWriter = ExifWriter()
    val locationProvider: LocationProvider = LocationProvider(appContext)
    val voiceController: VoiceController = VoiceController(appContext)

    // App-scoped so folder-setup work isn't cancelled by UI navigation (e.g., user backing
    // out of Settings immediately after picking a folder). Composition-scoped coroutines
    // would cancel mid-ensureBundleFolders, leaving the directories half-created and the
    // first bundle worker racing to create them itself.
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        appContext.getSystemService(NotificationManager::class.java)?.run {
            if (getNotificationChannel(BundleWorker.CHANNEL_ID) == null) {
                createNotificationChannel(
                    NotificationChannel(
                        BundleWorker.CHANNEL_ID,
                        "Saving bundles",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }

    fun configureRoot(uri: Uri): Job = appScope.launch {
        try {
            settingsRepository.setRootUri(uri)
            safStorage.ensureBundleFolders(uri)
        } catch (t: Throwable) {
            // Directory setup failures aren't fatal — SafStorage.findOrCreateDir will retry
            // on the first worker's commit. Log for diagnostics but don't crash the scope.
            Log.w(TAG, "configureRoot partial failure for $uri", t)
        }
    }
}
