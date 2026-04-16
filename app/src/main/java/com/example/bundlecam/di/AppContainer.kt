package com.example.bundlecam.di

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.bundlecam.data.exif.ExifWriter
import com.example.bundlecam.data.location.LocationProvider
import com.example.bundlecam.data.settings.SettingsRepository
import com.example.bundlecam.data.storage.BundleCounterStore
import com.example.bundlecam.data.storage.SafStorage
import com.example.bundlecam.data.storage.StagingStore
import com.example.bundlecam.pipeline.ManifestStore
import com.example.bundlecam.pipeline.OrphanRecovery
import com.example.bundlecam.pipeline.WorkScheduler

private const val TAG = "BundleCam/AppContainer"

class AppContainer(context: Context) {
    private val appContext: Context = context.applicationContext
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val safStorage: SafStorage = SafStorage(appContext)
    val stagingStore: StagingStore = StagingStore(appContext)
    val bundleCounterStore: BundleCounterStore = BundleCounterStore(appContext)
    val manifestStore: ManifestStore = ManifestStore(appContext)
    val workScheduler: WorkScheduler = WorkScheduler(appContext)
    val orphanRecovery: OrphanRecovery = OrphanRecovery(stagingStore, manifestStore, workScheduler)
    val exifWriter: ExifWriter = ExifWriter()
    val locationProvider: LocationProvider = LocationProvider(appContext)

    suspend fun configureRoot(uri: Uri) {
        settingsRepository.setRootUri(uri)
        runCatching { safStorage.ensureBundleFolders(uri) }
            .onFailure { Log.w(TAG, "ensureBundleFolders failed for $uri", it) }
    }
}
