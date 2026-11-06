package com.example.bundlecam.network.localsend

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

private const val TAG = "Recon/LocalSendDiscovery"
// 64 KB buffer covers the IPv4 UDP datagram max (~65 KB) — LocalSend announces are
// typically <1 KB but the spec doesn't cap them, so we size for the worst case.
private const val RECEIVE_BUFFER_BYTES = 64 * 1024
private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
// receive() / accept() block the IO thread; the soTimeout lets the loop wake up
// periodically to re-check currentCoroutineContext().isActive.
private const val RECEIVE_TIMEOUT_MS = 1_000
private const val REGISTER_SOCKET_TIMEOUT_MS = 3_000
private const val PROBE_CONNECT_TIMEOUT_MS = 600L
private const val PROBE_READ_TIMEOUT_MS = 800L
private const val SCAN_CONCURRENCY = 50
private val ANNOUNCE_BURST_DELAYS_MS = longArrayOf(500L, 2_000L)
private val APPLICATION_JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Resolved peer surfaced from inbound multicast traffic, inbound HTTPS `/register`
 * callbacks, or outbound HTTPS `/register` subnet probes.
 *
 * @param ip dotted-quad IPv4 address taken from the peer connection
 * @param port advertised TCP port for the peer's HTTP server (usually [LOCALSEND_PORT])
 * @param protocol "https" or "http" — Recon refuses non-https in HTTPS mode
 * @param fingerprint advertised/verified fingerprint (= SHA-256 of cert when protocol="https")
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
 * Minimal parsed HTTP/1.1 request used by the embedded discovery `/register` server.
 */
data class RawHttpRequest(
    val method: String,
    val path: String,
    val body: ByteArray,
)

/**
 * Multi-path LocalSend v2.1/v2.2 discovery:
 *
 * 1. **Embedded HTTPS `/api/localsend/v2/register` listener**: LocalSend 1.18+ peers
 *    never reply to UDP multicast announcements over UDP — they only answer by sending
 *    an HTTPS `POST /api/localsend/v2/register` back to the announcer's IP and port.
 *    We bind `LOCALSEND_PORT` (53317) when free, or fall back to an OS-assigned
 *    ephemeral port when 53317 is already occupied (e.g. when the official LocalSend
 *    app is running in the background on the same phone), and advertise that bound port
 *    in our outbound [Announce].
 * 2. **UDP multicast announcement burst + listener (`224.0.0.167:53317`)**: sends an
 *    immediate announce plus follow-up burst packets at +500 ms and +2000 ms (matching
 *    the official LocalSend cadence so a single dropped Wi-Fi multicast frame doesn't
 *    break discovery), and listens for peer-initiated UDP announcements.
 * 3. **Active HTTPS `/api/localsend/v2/register` subnet scan**: probes the local `/24`
 *    LAN subnet on port 53317 with mTLS client authentication so peers are still found
 *    within ~1 s even on routers/networks that filter IPv4 multicast or after a VPN
 *    toggle leaves multicast routing stale.
 */
class LocalSendDiscovery(
    context: Context,
    private val certManager: LocalSendCertManager? = null,
    private val baseClient: OkHttpClient? = null,
) {
    private val appContext: Context = context.applicationContext
    private val wifiManager: WifiManager? =
        appContext.applicationContext.getSystemService(WifiManager::class.java)

    private val lifecycleMutex = Mutex()
    private var session: Session? = null

    /**
     * Starts discovery. Brings up the embedded `/register` server, sends a multicast
     * announce burst, and runs a concurrent `/24` HTTP `/register` scan until [stop].
     * Multiple concurrent calls share the same session.
     */
    suspend fun start(scope: CoroutineScope, ownAnnounce: Announce): Flow<Peer> =
        lifecycleMutex.withLock {
            session?.let { return@withLock it.peers.consumeAsFlow() }
            val newSession = Session(wifiManager, certManager, baseClient, ownAnnounce, scope)
            try {
                newSession.startUp()
                session = newSession
                newSession.peers.consumeAsFlow()
            } catch (t: Throwable) {
                // startUp() may have partially acquired resources; ensure lock and
                // sockets are released even on failure so a follow-up start() can
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
        private val certManager: LocalSendCertManager?,
        private val baseClient: OkHttpClient?,
        private val ownAnnounce: Announce,
        private val scope: CoroutineScope,
    ) {
        private var socket: MulticastSocket? = null
        private var registerServer: SSLServerSocket? = null
        private var lock: WifiManager.MulticastLock? = null
        val peers: Channel<Peer> = Channel(capacity = Channel.UNLIMITED)
        private var receiveJob: Job? = null
        private var acceptJob: Job? = null
        private var announceBurstJob: Job? = null
        private var subnetScanJob: Job? = null
        @Volatile private var activeAnnounce: Announce = ownAnnounce
        private val groupAddress: InetAddress = InetAddress.getByName(LOCALSEND_MULTICAST_GROUP)
        private val groupSocketAddress: InetSocketAddress =
            InetSocketAddress(groupAddress, LOCALSEND_PORT)

        suspend fun startUp() = withContext(Dispatchers.IO) {
            val newLock = wifiManager?.createMulticastLock("recon-localsend")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
                    .onFailure { Log.w(TAG, "MulticastLock acquire failed", it) }
            }
            lock = newLock

            val heldCert = runCatching { certManager?.getOrCreate() }
                .onFailure { Log.w(TAG, "certManager.getOrCreate failed", it) }
                .getOrNull()

            // 1. Bring up the embedded HTTPS `/register` server first so we know which
            // TCP port to advertise in our UDP multicast announcement.
            val newServer = runCatching { openRegisterServer(heldCert) }
                .onFailure { Log.w(TAG, "HTTPS /register server setup failed", it) }
                .getOrNull()
            registerServer = newServer
            if (newServer != null) {
                activeAnnounce = ownAnnounce.copy(port = newServer.localPort)
                acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(newServer) }
            }

            // 2. Open the UDP multicast socket. Prefer binding LOCALSEND_PORT (53317)
            // so we also hear peer-initiated multicast announces; if 53317 cannot be
            // bound (e.g., locked by another process), fall back to an ephemeral port
            // so outbound multicast announcements still go out.
            var boundToMulticastPort = true
            val newSocket = try {
                MulticastSocket(LOCALSEND_PORT).apply {
                    reuseAddress = true
                    soTimeout = RECEIVE_TIMEOUT_MS
                    joinAllInterfaces(this)
                }
            } catch (primaryErr: Throwable) {
                Log.w(TAG, "MulticastSocket($LOCALSEND_PORT) bind failed; falling back to ephemeral send socket", primaryErr)
                boundToMulticastPort = false
                try {
                    MulticastSocket().apply {
                        soTimeout = RECEIVE_TIMEOUT_MS
                    }
                } catch (fallbackErr: Throwable) {
                    if (newServer == null) {
                        runCatching { newLock?.release() }
                        throw IOException("MulticastSocket(${LOCALSEND_PORT}) setup failed", primaryErr)
                    }
                    null
                }
            }
            socket = newSocket

            broadcastOnceBlocking()
            if (newSocket != null && boundToMulticastPort) {
                receiveJob = scope.launch(Dispatchers.IO) { receiveLoop() }
            }
            announceBurstJob = scope.launch(Dispatchers.IO) {
                for (delayMs in ANNOUNCE_BURST_DELAYS_MS) {
                    delay(delayMs)
                    if (!currentCoroutineContext().isActive) break
                    broadcastOnceBlocking()
                }
            }

            // 3. Probe the local `/24` LAN subnet over HTTPS `/register` so peers are
            // discovered even when the router blocks multicast or LocalSend 1.18+ does
            // not emit UDP replies.
            val probeClient = buildProbeClient(heldCert, expectedFingerprintHex = null)
            subnetScanJob = scope.launch(Dispatchers.IO) {
                runSubnetScan(probeClient)
            }
        }

        suspend fun broadcastOnce() = withContext(Dispatchers.IO) { broadcastOnceBlocking() }

        private fun broadcastOnceBlocking() {
            val s = socket ?: return
            val payload = LocalSendJson.encodeToString(activeAnnounce).toByteArray(Charsets.UTF_8)
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
            val heldCert = runCatching { certManager?.getOrCreate() }.getOrNull()
            while (currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    s.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: SocketException) {
                    if (s.isClosed) break
                    Log.w(TAG, "receive failed", e)
                    continue
                } catch (_: java.io.InterruptedIOException) {
                    break
                }
                val announce = parseAnnounce(packet.data, packet.length, activeAnnounce.fingerprint)
                    ?: continue
                val ip = packet.address?.hostAddress ?: continue
                if (ip in allLocalIpv4Addresses()) continue
                peers.trySendBlocking(announceToPeer(announce, ip))
                if (announce.announce) {
                    // Answer the peer's announcement via HTTP POST /register so
                    // LocalSend 1.18+ also registers us and confirms reachability.
                    scope.launch(Dispatchers.IO) {
                        val client = buildProbeClient(heldCert, announce.fingerprint)
                        probePeer(client, ip, announce.port)
                    }
                }
            }
        }

        private fun openRegisterServer(heldCert: okhttp3.tls.HeldCertificate?): SSLServerSocket {
            val (sslContext, _) = buildLocalSendSslContext(
                heldCertificate = heldCert,
                expectedFingerprintHex = null,
            )
            val factory = sslContext.serverSocketFactory
            val server = runCatching {
                (factory.createServerSocket() as SSLServerSocket).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(LOCALSEND_PORT), 50)
                }
            }.getOrElse {
                (factory.createServerSocket() as SSLServerSocket).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0), 50)
                }
            }
            server.wantClientAuth = true
            server.needClientAuth = false
            server.soTimeout = RECEIVE_TIMEOUT_MS
            return server
        }

        private suspend fun acceptLoop(server: SSLServerSocket) {
            while (currentCoroutineContext().isActive && !server.isClosed) {
                val client = try {
                    server.accept() as SSLSocket
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: SocketException) {
                    if (server.isClosed) break
                    Log.w(TAG, "register server accept failed", e)
                    continue
                } catch (_: java.io.InterruptedIOException) {
                    break
                } catch (t: Throwable) {
                    if (server.isClosed) break
                    Log.w(TAG, "register server accept error", t)
                    continue
                }
                scope.launch(Dispatchers.IO) {
                    handleRegisterClient(client)
                }
            }
        }

        private fun handleRegisterClient(client: SSLSocket) {
            client.use { sock ->
                runCatching {
                    sock.soTimeout = REGISTER_SOCKET_TIMEOUT_MS
                    sock.startHandshake()
                    val remoteIp = sock.inetAddress?.hostAddress ?: return
                    val certFp = runCatching {
                        sock.session.peerCertificates.firstOrNull()?.encoded?.let {
                            LocalSendCertManager.computeFingerprintHex(it)
                        }
                    }.getOrNull()

                    val req = readHttpRequest(sock.inputStream) ?: return
                    val respBytes = buildRegisterResponseBytes()
                    when {
                        req.method == "POST" && req.path == LOCALSEND_API_PATH_REGISTER -> {
                            val peer = if (remoteIp in allLocalIpv4Addresses()) {
                                null
                            } else {
                                parseRegisterRequest(
                                    body = req.body,
                                    ip = remoteIp,
                                    ownFingerprint = activeAnnounce.fingerprint,
                                    tlsClientCertFingerprint = certFp,
                                )
                            }
                            if (peer != null) {
                                peers.trySendBlocking(peer)
                            }
                            writeHttpResponse(sock.outputStream, 200, "OK", respBytes)
                        }
                        req.method == "GET" &&
                            (req.path == "/api/localsend/v2/info" || req.path == "/api/localsend/v1/info") -> {
                            writeHttpResponse(sock.outputStream, 200, "OK", respBytes)
                        }
                        else -> {
                            writeHttpResponse(sock.outputStream, 404, "Not Found", ByteArray(0))
                        }
                    }
                }.onFailure { e ->
                    Log.d(TAG, "register client closed: ${e.message}")
                }
            }
        }

        private fun buildRegisterResponseBytes(): ByteArray =
            LocalSendJson.encodeToString(
                RegisterResponse(
                    alias = activeAnnounce.alias,
                    version = activeAnnounce.version,
                    deviceModel = activeAnnounce.deviceModel,
                    deviceType = activeAnnounce.deviceType,
                    fingerprint = activeAnnounce.fingerprint,
                    download = activeAnnounce.download,
                )
            ).toByteArray(Charsets.UTF_8)

        private fun buildProbeClient(
            heldCert: okhttp3.tls.HeldCertificate?,
            expectedFingerprintHex: String?,
        ): OkHttpClient {
            val (sslContext, tm) = buildLocalSendSslContext(
                heldCertificate = heldCert,
                expectedFingerprintHex = expectedFingerprintHex,
            )
            val root = baseClient ?: OkHttpClient()
            return root.newBuilder()
                .proxy(Proxy.NO_PROXY)
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(PROBE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PROBE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(PROBE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .sslSocketFactory(sslContext.socketFactory, tm)
                .hostnameVerifier(LocalSendHostnameVerifier)
                .build()
        }

        private suspend fun runSubnetScan(client: OkHttpClient) {
            val localIps = localLanIpv4Addresses()
            if (localIps.isEmpty()) return
            val candidates = localIps
                .flatMap { subnetCandidatesFor(it) }
                .filterNot { it in localIps }
                .distinct()
            if (candidates.isEmpty()) return

            val semaphore = Semaphore(SCAN_CONCURRENCY)
            coroutineScope {
                candidates.map { ip ->
                    async {
                        semaphore.withPermit {
                            if (!currentCoroutineContext().isActive) return@withPermit
                            probePeer(client, ip, LOCALSEND_PORT)
                        }
                    }
                }.awaitAll()
            }
        }

        private suspend fun probePeer(client: OkHttpClient, ip: String, port: Int) {
            val url = runCatching {
                "https://$ip:$port$LOCALSEND_API_PATH_REGISTER".toHttpUrl()
            }.getOrNull() ?: return
            val registerDto = RegisterRequest(
                alias = activeAnnounce.alias,
                version = activeAnnounce.version,
                deviceModel = activeAnnounce.deviceModel,
                deviceType = activeAnnounce.deviceType,
                fingerprint = activeAnnounce.fingerprint,
                port = activeAnnounce.port,
                protocol = activeAnnounce.protocol,
                download = activeAnnounce.download,
            )
            val body = LocalSendJson.encodeToString(registerDto)
                .toRequestBody(APPLICATION_JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            runCatching {
                client.newCall(request).awaitResponse().use { response ->
                    if (!response.isSuccessful) return
                    val certFp = runCatching {
                        response.handshake?.peerCertificates?.firstOrNull()?.encoded?.let {
                            LocalSendCertManager.computeFingerprintHex(it)
                        }
                    }.getOrNull()
                    val respBytes = response.body?.bytes() ?: return
                    val peer = parseRegisterResponse(
                        body = respBytes,
                        ip = ip,
                        port = port,
                        ownFingerprint = activeAnnounce.fingerprint,
                        tlsServerCertFingerprint = certFp,
                    ) ?: return
                    peers.trySendBlocking(peer)
                }
            }
        }

        suspend fun shutdown() {
            withContext(NonCancellable) {
                runCatching { socket?.close() }
                runCatching { registerServer?.close() }
                announceBurstJob?.cancelAndJoin()
                subnetScanJob?.cancelAndJoin()
                receiveJob?.cancelAndJoin()
                acceptJob?.cancelAndJoin()
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
            if (announce.fingerprint.isBlank()) return null
            // Case-insensitive: our own fingerprint is always lowercase hex, but a peer
            // implementation that echoes back uppercase (the official Flutter/Rust app)
            // would slip past `==`. Defense-in-depth.
            if (announce.fingerprint.equals(ownFingerprint, ignoreCase = true)) return null
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
         * Pure helper — testable. Parses an inbound `POST /api/localsend/v2/register`
         * body into a [Peer]. When the peer presented an mTLS client certificate during
         * the TLS handshake ([tlsClientCertFingerprint] != null), verifies that any
         * non-blank claimed fingerprint in the JSON matches the certificate hash (mirroring
         * LocalSend 1.18's anti-spoofing check).
         */
        fun parseRegisterRequest(
            body: ByteArray,
            ip: String,
            ownFingerprint: String,
            tlsClientCertFingerprint: String? = null,
        ): Peer? {
            if (body.isEmpty()) return null
            val text = String(body, Charsets.UTF_8)
            val req = runCatching { LocalSendJson.decodeFromString<RegisterRequest>(text) }
                .getOrNull() ?: return null
            if (req.protocol != "https") return null
            val effectiveFp = if (!tlsClientCertFingerprint.isNullOrBlank()) {
                if (req.fingerprint.isNotBlank() &&
                    !req.fingerprint.equals(tlsClientCertFingerprint, ignoreCase = true)
                ) {
                    return null
                }
                tlsClientCertFingerprint
            } else {
                req.fingerprint.takeIf { it.isNotBlank() } ?: return null
            }
            if (effectiveFp.equals(ownFingerprint, ignoreCase = true)) return null
            return Peer(
                ip = ip,
                port = req.port,
                protocol = req.protocol,
                fingerprint = effectiveFp,
                alias = req.alias,
                deviceModel = req.deviceModel,
                deviceType = req.deviceType,
            )
        }

        /**
         * Pure helper — testable. Parses the response body of an outbound
         * `POST /api/localsend/v2/register` probe into a [Peer]. In HTTPS mode the TLS
         * server certificate hash ([tlsServerCertFingerprint]) is authoritative per the
         * LocalSend v2.1/v2.2 spec (`RegisterResponse.fingerprint` is ignored in HTTPS
         * mode); falls back to the body's fingerprint only when no TLS cert hash was
         * supplied.
         */
        fun parseRegisterResponse(
            body: ByteArray,
            ip: String,
            port: Int,
            ownFingerprint: String,
            tlsServerCertFingerprint: String? = null,
        ): Peer? {
            if (body.isEmpty()) return null
            val text = String(body, Charsets.UTF_8)
            val resp = runCatching { LocalSendJson.decodeFromString<RegisterResponse>(text) }
                .getOrNull() ?: return null
            val effectiveFp = tlsServerCertFingerprint?.takeIf { it.isNotBlank() }
                ?: resp.fingerprint.takeIf { it.isNotBlank() }
                ?: return null
            if (effectiveFp.equals(ownFingerprint, ignoreCase = true)) return null
            return Peer(
                ip = ip,
                port = port,
                protocol = "https",
                fingerprint = effectiveFp,
                alias = resp.alias,
                deviceModel = resp.deviceModel,
                deviceType = resp.deviceType,
            )
        }

        /**
         * Pure helper — testable. Generates the 253 peer host addresses in the `/24`
         * subnet of [localIpv4] (`x.y.z.1` .. `x.y.z.254` excluding [localIpv4] itself)
         * for RFC 1918 private LAN addresses (`10/8`, `172.16/12`, `192.168/16`).
         * Non-private or malformed addresses return an empty list so cellular/CGNAT
         * links are never scanned.
         */
        fun subnetCandidatesFor(localIpv4: String): List<String> {
            val parts = localIpv4.split('.')
            if (parts.size != 4) return emptyList()
            val octets = parts.map { it.toIntOrNull() ?: return emptyList() }
            if (octets.any { it !in 0..255 }) return emptyList()
            val (a, b, c, selfHost) = octets
            val isPrivateLan = a == 10 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168)
            if (!isPrivateLan) return emptyList()
            val prefix = "$a.$b.$c."
            return buildList(253) {
                for (host in 1..254) {
                    if (host != selfHost) add("$prefix$host")
                }
            }
        }

        /**
         * Pure helper — testable. Reads a single HTTP/1.1 request (request line,
         * `Content-Length` header, and body) from [input]. Caps headers at
         * [MAX_HTTP_HEADER_BYTES] and body at [RECEIVE_BUFFER_BYTES].
         */
        fun readHttpRequest(input: InputStream): RawHttpRequest? {
            val headerBuf = ByteArrayOutputStream(512)
            var state = 0 // matches \r\n\r\n
            while (headerBuf.size() < MAX_HTTP_HEADER_BYTES) {
                val b = input.read()
                if (b < 0) return null
                headerBuf.write(b)
                state = when {
                    state == 0 && b == '\r'.code -> 1
                    state == 1 && b == '\n'.code -> 2
                    state == 2 && b == '\r'.code -> 3
                    state == 3 && b == '\n'.code -> 4
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (state == 4) break
            }
            if (state != 4) return null

            val headerText = String(headerBuf.toByteArray(), Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(' ') ?: return null
            if (requestLine.size < 2) return null
            val method = requestLine[0].uppercase()
            val path = requestLine[1].substringBefore('?')

            var contentLength = 0
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim()
                    if (name.equals("Content-Length", ignoreCase = true)) {
                        contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: return null
                    }
                }
            }
            if (contentLength < 0 || contentLength > RECEIVE_BUFFER_BYTES) return null

            val body = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val n = input.read(body, offset, contentLength - offset)
                if (n < 0) return null
                offset += n
            }
            return RawHttpRequest(method = method, path = path, body = body)
        }

        private fun writeHttpResponse(
            output: OutputStream,
            statusCode: Int,
            reason: String,
            body: ByteArray,
        ) {
            val header = buildString {
                append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
                if (body.isNotEmpty()) {
                    append("Content-Type: application/json\r\n")
                }
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.ISO_8859_1)
            output.write(header)
            if (body.isNotEmpty()) {
                output.write(body)
            }
            output.flush()
        }

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

        /**
         * Active private-LAN IPv4 addresses on non-cellular/non-VPN interfaces, ordered
         * with Wi-Fi (`wlan*` / `ap*`) first for `/24` HTTP subnet scanning.
         */
        fun localLanIpv4Addresses(): List<String> = try {
            val skipPrefixes = listOf("rmnet", "ccmni", "pdp", "clat", "dummy", "tun", "tap", "ppp", "v4-")
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { nic ->
                    runCatching {
                        val name = nic.name.lowercase()
                        nic.isUp && !nic.isLoopback && ! nic.isVirtual &&
                            skipPrefixes.none { name.startsWith(it) }
                    }.getOrDefault(false)
                }
                .sortedBy { nic ->
                    val name = nic.name.lowercase()
                    when {
                        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("en") -> 0
                        name.startsWith("eth") || name.startsWith("rndis") || name.startsWith("ncm") -> 1
                        else -> 2
                    }
                }
                .flatMap { nic ->
                    nic.inetAddresses.toList().mapNotNull { addr ->
                        val host = addr.hostAddress
                        if (!addr.isLoopbackAddress && addr.isSiteLocalAddress && host != null && host.contains('.')) {
                            host
                        } else {
                            null
                        }
                    }
                }
                .distinct()
        } catch (_: IOException) {
            emptyList()
        }

        /**
         * All IPv4 addresses bound to any active interface on this device (including
         * loopback), used to ignore local loopback/same-device traffic when another
         * LocalSend instance is running concurrently on the same phone.
         */
        fun allLocalIpv4Addresses(): Set<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { nic ->
                    runCatching {
                        nic.inetAddresses.toList().mapNotNull { addr ->
                            addr.hostAddress?.takeIf { it.contains('.') }
                        }
                    }.getOrDefault(emptyList())
                }
                .toSet()
        } catch (_: IOException) {
            emptySet()
        }
    }
}
