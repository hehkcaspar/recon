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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.serialization.encodeToString

private const val TAG = "Recon/LocalSendDiscovery"
// 64 KB buffer covers the IPv4 UDP datagram max (~65 KB) — LocalSend announces are
// typically <1 KB but the spec doesn't cap them, so we size for the worst case.
private const val RECEIVE_BUFFER_BYTES = 64 * 1024
// receive() blocks the IO thread; the soTimeout lets the loop wake up periodically to
// re-check scope.isActive so cancelling the discovery scope shuts down within ~1s.
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
 *
 * Thread-safety: [start]/[stop]/[broadcastAnnounce] are serialized through a suspending
 * [lifecycleMutex] so a stop() running mid-flight always finishes its socket close
 * before a subsequent start() tries to bind the port (otherwise a tight stop-then-start
 * sequence would race the SO_REUSEADDR setup against an in-flight close).
 */
class LocalSendDiscovery(context: Context) {
    private val appContext: Context = context.applicationContext
    private val wifiManager: WifiManager? =
        appContext.applicationContext.getSystemService(WifiManager::class.java)

    private val lifecycleMutex = Mutex()
    private var session: Session? = null

    /**
     * Starts discovery. Sends an immediate announce; receive loop continues until [stop].
     * Multiple concurrent calls share the same session — re-calling start is a no-op
     * (returns the existing flow). Suspending so callers wait through any in-flight
     * stop() before this start() runs.
     */
    suspend fun start(scope: CoroutineScope, ownAnnounce: Announce): Flow<Peer> =
        lifecycleMutex.withLock {
            session?.let { return@withLock it.peers.consumeAsFlow() }
            val newSession = Session(wifiManager, ownAnnounce, scope)
            try {
                newSession.startUp()
                session = newSession
                newSession.peers.consumeAsFlow()
            } catch (t: Throwable) {
                // setUp() may have partially acquired resources; ensure both lock and
                // socket are released even on full failure so a follow-up start() can
                // try again from a clean slate.
                runCatching { newSession.shutdown() }
                throw t
            }
        }

    suspend fun stop() {
        lifecycleMutex.withLock {
            val s = session ?: return@withLock
            session = null
            s.shutdown()
        }
    }

    /** Send the announce on demand — useful to re-broadcast if a peer joined late. */
    suspend fun broadcastAnnounce() {
        lifecycleMutex.withLock { session?.broadcastOnce() }
    }

    private class Session(
        private val wifiManager: WifiManager?,
        private val ownAnnounce: Announce,
        private val scope: CoroutineScope,
    ) {
        private var socket: MulticastSocket? = null
        private var lock: WifiManager.MulticastLock? = null
        val peers: Channel<Peer> = Channel(capacity = Channel.UNLIMITED)
        private var receiveJob: Job? = null
        private val groupAddress: InetAddress = InetAddress.getByName(LOCALSEND_MULTICAST_GROUP)
        private val groupSocketAddress: InetSocketAddress =
            InetSocketAddress(groupAddress, LOCALSEND_PORT)

        fun startUp() {
            val newLock = wifiManager?.createMulticastLock("recon-localsend")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
                    .onFailure { Log.w(TAG, "MulticastLock acquire failed", it) }
            }
            val newSocket = try {
                MulticastSocket(LOCALSEND_PORT).apply {
                    reuseAddress = true
                    soTimeout = RECEIVE_TIMEOUT_MS
                    joinAllInterfaces(this)
                }
            } catch (t: Throwable) {
                // Socket open failed (e.g. port still bound from a previous session
                // mid-close) — release the lock we just acquired so we don't hold the
                // wifi awake forever.
                runCatching { newLock?.release() }
                throw IOException("MulticastSocket(${LOCALSEND_PORT}) setup failed", t)
            }
            socket = newSocket
            lock = newLock
            broadcastOnceBlocking()
            receiveJob = scope.launch(Dispatchers.IO) { receiveLoop() }
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
            // NonCancellable so a parent-scope cancellation (e.g. user dismissing the
            // sheet) doesn't leave the socket open and the lock held — we have to
            // finish cleanup even if the caller's job is being torn down.
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
         * fingerprint, drops malformed JSON, and refuses non-HTTPS peers (their
         * advertised fingerprint is per spec a random string, not a cert hash, so we
         * have no way to authenticate them — sending to such a peer would be a MITM-
         * vulnerable channel). Length cap is enforced by the caller's
         * [DatagramPacket.length]; we never look past `length`.
         */
        fun parseAnnounce(buffer: ByteArray, length: Int, ownFingerprint: String): Announce? {
            if (length <= 0 || length > buffer.size) return null
            val text = String(buffer, 0, length, Charsets.UTF_8)
            val announce = runCatching { LocalSendJson.decodeFromString<Announce>(text) }
                .getOrNull() ?: return null
            if (announce.fingerprint == ownFingerprint) return null
            if (announce.protocol != "https") return null
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
