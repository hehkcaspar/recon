package com.example.bundlecam.network.localsend

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.serialization.encodeToString

private const val TAG = "Recon/LocalSendDiscovery"
private const val RECEIVE_BUFFER_BYTES = 64 * 1024
private const val RECEIVE_TIMEOUT_MS = 1_000

/**
 * Resolved peer surfaced from inbound multicast traffic.
 *
 * @param ip dotted-quad IPv4 address taken from the inbound DatagramPacket
 * @param port advertised TCP port for the peer's HTTP server (usually [LOCALSEND_PORT])
 * @param protocol "https" or "http" — Recon refuses non-https in HTTPS mode
 * @param fingerprint advertised fingerprint (= SHA-256 of cert when protocol="https")
 */
data class Peer(
    val ip: String,
    val port: Int,
    val protocol: String,
    val fingerprint: String,
    val alias: String,
    val deviceModel: String?,
    val deviceType: String?,
) {
    fun baseUrl(): String = "$protocol://$ip:$port"
}

/**
 * Multicast discovery: send one announce on start, listen for inbound announces (whether
 * peer-initiated or `announce:false` replies to our own), surface as [Peer]s on the
 * returned flow.
 *
 * Acquires a [WifiManager.MulticastLock] on [start] and releases it on [stop] — without
 * the lock, Android filters inbound multicast packets to save power, which silences the
 * receive side completely on most devices.
 *
 * Sender-only: this class does NOT run an HTTP `/register` server. Per spec, peers also
 * fall back to multicast UDP responses (with `announce:false`) when no HTTP server is
 * reachable, so we still discover most peers via this single socket.
 */
class LocalSendDiscovery(context: Context) : Closeable {
    private val appContext: Context = context.applicationContext
    private val wifiManager: WifiManager? =
        appContext.applicationContext.getSystemService(WifiManager::class.java)

    @Volatile
    private var session: Session? = null

    /**
     * Starts discovery. Sends an immediate announce; receive loop continues until [stop]
     * or [close]. Multiple concurrent calls share the same session — re-calling start
     * is a no-op (returns the existing flow).
     */
    fun start(scope: CoroutineScope, ownAnnounce: Announce): Flow<Peer> {
        synchronized(this) {
            session?.let { return it.peers.consumeAsFlow() }
            val newSession = Session(appContext, wifiManager, ownAnnounce, scope)
            session = newSession
            newSession.run()
            return newSession.peers.consumeAsFlow()
        }
    }

    suspend fun stop() {
        val s = synchronized(this) {
            val current = session
            session = null
            current
        } ?: return
        s.shutdown()
    }

    override fun close() {
        // Closeable for try-with-resources contracts; suspend stop() is preferred.
        synchronized(this) {
            session?.let {
                it.peers.close()
                it.socket?.runCatching { close() }
                runCatching { it.lock?.release() }
            }
            session = null
        }
    }

    /** Send the announce on demand — useful to re-broadcast if a peer joined late. */
    suspend fun broadcastAnnounce() {
        session?.broadcastOnce()
    }

    private class Session(
        appContext: Context,
        private val wifiManager: WifiManager?,
        private val ownAnnounce: Announce,
        private val scope: CoroutineScope,
    ) {
        var socket: MulticastSocket? = null
        var lock: WifiManager.MulticastLock? = null
        val peers: Channel<Peer> = Channel(capacity = Channel.UNLIMITED)
        private var receiveJob: Job? = null
        private val groupAddress: InetAddress = InetAddress.getByName(LOCALSEND_MULTICAST_GROUP)
        private val groupSocketAddress: InetSocketAddress =
            InetSocketAddress(groupAddress, LOCALSEND_PORT)

        fun run() {
            lock = wifiManager?.createMulticastLock("recon-localsend")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
                    .onFailure { Log.w(TAG, "MulticastLock acquire failed", it) }
            }
            try {
                socket = MulticastSocket(LOCALSEND_PORT).apply {
                    reuseAddress = true
                    soTimeout = RECEIVE_TIMEOUT_MS
                    joinAllInterfaces(this)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "MulticastSocket setup failed", t)
                return
            }
            broadcastOnceBlocking()
            receiveJob = scope.launch(Dispatchers.IO) {
                receiveLoop()
            }
        }

        suspend fun broadcastOnce() = withContext(Dispatchers.IO) { broadcastOnceBlocking() }

        private fun broadcastOnceBlocking() {
            val s = socket ?: return
            val payload = LocalSendJson.encodeToString(ownAnnounce).toByteArray(Charsets.UTF_8)
            // Send on every NIC so the announce reaches peers reachable via any local
            // network. setNetworkInterface() is per-interface, so we cycle and send once
            // per. SocketException on a single NIC (e.g. tethering interface mid-toggle)
            // doesn't block the others.
            multicastInterfaces().forEach { nic ->
                runCatching {
                    s.networkInterface = nic
                    s.send(DatagramPacket(payload, payload.size, groupAddress, LOCALSEND_PORT))
                }.onFailure { Log.w(TAG, "send failed on ${nic.name}", it) }
            }
        }

        private suspend fun receiveLoop() {
            val s = socket ?: return
            val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
            while (scope.isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                val received = runCatching { s.receive(packet) }
                if (received.isFailure) {
                    val err = received.exceptionOrNull()
                    if (err is SocketTimeoutException) continue
                    if (s.isClosed) break
                    Log.w(TAG, "receive failed", err)
                    continue
                }
                val announce = parseAnnounce(packet.data, packet.length, ownAnnounce.fingerprint)
                    ?: continue
                val ip = packet.address?.hostAddress ?: continue
                peers.trySendBlocking(announceToPeer(announce, ip))
            }
        }

        suspend fun shutdown() {
            withContext(NonCancellable) {
                receiveJob?.cancelAndJoin()
                runCatching { socket?.close() }
                runCatching { lock?.release() }
                peers.close()
            }
        }

        private fun joinAllInterfaces(s: MulticastSocket) {
            multicastInterfaces().forEach { nic ->
                runCatching { s.joinGroup(groupSocketAddress, nic) }
                    .onFailure { Log.w(TAG, "joinGroup failed on ${nic.name}", it) }
            }
        }
    }

    companion object {
        /**
         * Pure helper — testable. Decodes the inbound announce, drops self-echoes by
         * fingerprint, drops malformed JSON. Length cap is enforced by the caller's
         * [DatagramPacket.length]; we never look past `length`.
         */
        fun parseAnnounce(buffer: ByteArray, length: Int, ownFingerprint: String): Announce? {
            if (length <= 0 || length > buffer.size) return null
            val text = String(buffer, 0, length, Charsets.UTF_8)
            val announce = runCatching { LocalSendJson.decodeFromString<Announce>(text) }
                .getOrNull() ?: return null
            if (announce.fingerprint == ownFingerprint) return null
            return announce
        }

        /**
         * Pure helper — testable. Lifts an [Announce] + sender IP into a [Peer]. The
         * announce's `port` is preferred when present; otherwise we fall back to the
         * default LocalSend port (matches the spec's announce-payload behavior).
         */
        fun announceToPeer(announce: Announce, ip: String): Peer = Peer(
            ip = ip,
            port = announce.port,
            protocol = announce.protocol,
            fingerprint = announce.fingerprint,
            alias = announce.alias,
            deviceModel = announce.deviceModel,
            deviceType = announce.deviceType,
        )

        /**
         * Non-loopback IPv4-capable interfaces. Iterating these and sending/joining on
         * each lets us reach peers reachable via Wi-Fi AP, Wi-Fi Direct, USB tethering,
         * or any other simultaneous local link the device exposes.
         */
        fun multicastInterfaces(): List<NetworkInterface> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { nic ->
                    runCatching {
                        nic.isUp && !nic.isLoopback && nic.supportsMulticast() &&
                            nic.inetAddresses.toList().any { addr ->
                                !addr.isLoopbackAddress && addr.hostAddress?.contains('.') == true
                            }
                    }.getOrDefault(false)
                }
        } catch (_: IOException) {
            emptyList()
        }
    }
}
