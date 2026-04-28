package com.example.bundlecam.network.localsend

import android.content.Context
import android.os.Build
import com.example.bundlecam.data.settings.SettingsRepository
import com.example.bundlecam.data.storage.BundleLibrary
import com.example.bundlecam.data.storage.CompletedBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Top-level coordinator for LocalSend peer-to-peer transfer. Owns the multicast
 * discovery socket and a base OkHttp client; per-peer SSL configuration (fingerprint-
 * pinning trust manager) is layered on by the uploader via `client.newBuilder()` so the
 * connection pool / dispatcher / timeouts are shared while each peer's TLS identity is
 * verified during its own handshake.
 *
 * Sender-only — does NOT run an HTTP server. Per the v2.1 spec, peers can also reply
 * via UDP multicast (`announce: false`), so single-socket discovery still surfaces most
 * peers. If a peer ONLY responds via HTTP `/register`, we won't see them — acceptable
 * trade-off for the MVP scope.
 */
class LocalSendController(
    context: Context,
    private val settings: SettingsRepository,
    private val bundleLibrary: BundleLibrary,
    private val certManager: LocalSendCertManager,
) {
    private val appContext: Context = context.applicationContext

    // Base client with no SSL config — the uploader newBuilder()s a peer-specific
    // client per send so the trust manager carries that peer's expected fingerprint.
    private val baseClient: OkHttpClient by lazy { OkHttpClient.Builder().build() }

    private val discovery = LocalSendDiscovery(appContext)
    private val uploader by lazy { LocalSendUploader(baseClient, appContext.contentResolver) }

    // The local Info (alias + deviceModel + fingerprint) is stable per-install; cache
    // it so discover/send don't each hit DataStore (mutex-guarded suspend) and
    // certManager (lazy cert generation on first call, ~100 ms) on every invocation.
    @Volatile private var cachedInfo: Info? = null
    private val infoMutex = Mutex()

    /**
     * Starts (or rejoins) the multicast discovery session and returns a flow of
     * [Peer]s as they reply. Caller is responsible for collecting on a UI scope and
     * calling [stopDiscovery] when the share UI dismisses.
     */
    suspend fun discover(scope: CoroutineScope): Flow<Peer> {
        val ownAnnounce = buildOwnAnnounce()
        return discovery.start(scope, ownAnnounce)
    }

    suspend fun stopDiscovery() {
        discovery.stop()
    }

    /** Re-broadcast our announce — useful if a peer joined late or left and rejoined. */
    suspend fun rebroadcastAnnounce() {
        discovery.broadcastAnnounce()
    }

    /**
     * Send all [bundles] to one peer in a single LocalSend session. Bundling everything
     * into one prepare-upload is mandatory: LocalSend's receiver treats a session as
     * active until the user dismisses the receive UI, so back-to-back sessions to the
     * same peer would 409 BLOCKED until the user clicks "Done" on the desktop. The
     * receiver materializes the per-bundle directory layout from the wire fileNames
     * (`{bundleId}/{subfolder}/{leaf}`) we set in [UploadItem.wireName].
     */
    suspend fun send(
        peer: Peer,
        bundles: List<CompletedBundle>,
        onProgress: (SendProgress) -> Unit = {},
    ): SendBundleResult {
        val items = bundles.flatMap { bundle ->
            bundleLibrary.listBundleFiles(bundle).map { file ->
                UploadItem(source = file, wireName = LocalSendUploader.wireNameFor(bundle.id, file))
            }
        }
        if (items.isEmpty()) return SendBundleResult.Failed("Selection has no shippable files")
        return uploader.send(peer, localInfo(), items, onProgress)
    }

    private suspend fun buildOwnAnnounce(): Announce {
        val info = localInfo()
        return Announce(
            alias = info.alias,
            deviceModel = info.deviceModel,
            deviceType = info.deviceType,
            fingerprint = info.fingerprint,
            announce = true,
        )
    }

    /** Lazily-built local identity, cached for the controller's lifetime. */
    private suspend fun localInfo(): Info {
        cachedInfo?.let { return it }
        return infoMutex.withLock {
            cachedInfo ?: Info(
                alias = settings.getOrCreateDeviceAlias(),
                deviceModel = Build.MODEL,
                deviceType = LOCALSEND_DEVICE_TYPE_MOBILE,
                fingerprint = certManager.fingerprintHex(),
            ).also { cachedInfo = it }
        }
    }
}
