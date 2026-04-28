package com.example.bundlecam.network.localsend

import android.content.Context
import android.os.Build
import com.example.bundlecam.data.settings.SettingsRepository
import com.example.bundlecam.data.storage.BundleLibrary
import com.example.bundlecam.data.storage.CompletedBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
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
     * Send one bundle to one peer. Sequential per-bundle; per-file uploads parallelize
     * inside the uploader. Returns the final [SendBundleResult] when the session
     * completes (success / failure / already-received).
     */
    suspend fun send(
        peer: Peer,
        bundle: CompletedBundle,
        onProgress: (SendProgress) -> Unit = {},
    ): SendBundleResult {
        val files = bundleLibrary.listBundleFiles(bundle)
        if (files.isEmpty()) return SendBundleResult.Failed("Bundle has no shippable files")
        val info = Info(
            alias = settings.getOrCreateDeviceAlias(),
            deviceModel = Build.MODEL,
            deviceType = LOCALSEND_DEVICE_TYPE_MOBILE,
            fingerprint = certManager.fingerprintHex(),
        )
        return uploader.sendBundle(peer, bundle.id, info, files, onProgress)
    }

    private suspend fun buildOwnAnnounce(): Announce = Announce(
        alias = settings.getOrCreateDeviceAlias(),
        deviceModel = Build.MODEL,
        deviceType = LOCALSEND_DEVICE_TYPE_MOBILE,
        fingerprint = certManager.fingerprintHex(),
        announce = true,
    )
}
