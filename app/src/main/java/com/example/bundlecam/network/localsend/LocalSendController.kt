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
import okhttp3.Protocol
import java.net.Proxy

/**
 * Top-level coordinator for LocalSend peer-to-peer transfer. Owns the discovery
 * engine (UDP multicast + embedded HTTPS `/register` responder + HTTP `/24` subnet
 * probe) and a base OkHttp client; per-peer SSL configuration (mTLS client cert +
 * fingerprint-pinning trust manager) is layered on via `client.newBuilder()` so the
 * connection pool / dispatcher are shared while each peer's TLS identity is verified
 * during its own handshake.
 */
class LocalSendController(
    context: Context,
    private val settings: SettingsRepository,
    private val bundleLibrary: BundleLibrary,
    private val certManager: LocalSendCertManager,
) {
    private val appContext: Context = context.applicationContext

    // Base client with Proxy.NO_PROXY (so LAN peer traffic never routes through a
    // system HTTP/SOCKS proxy like FlClash) and HTTP/1.1 (matching LocalSend's Rust
    // client/server). Discovery and uploader layer their TLS config via newBuilder().
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private val discovery = LocalSendDiscovery(appContext, certManager, baseClient)
    private val uploader by lazy {
        LocalSendUploader(baseClient, appContext.contentResolver, certManager)
    }

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
        val items = buildList {
            bundles.forEach { bundle ->
                val files = bundleLibrary.listBundleFiles(bundle)
                if (files.isEmpty()) {
                    return SendBundleResult.Failed("Bundle ${bundle.id} has no readable files")
                }
                files.forEach { file ->
                    add(UploadItem(source = file, wireName = LocalSendUploader.wireNameFor(bundle.id, file)))
                }
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
