package com.example.bundlecam

import com.example.bundlecam.network.localsend.Announce
import com.example.bundlecam.network.localsend.LocalSendCertManager
import com.example.bundlecam.network.localsend.LocalSendDiscovery
import com.example.bundlecam.network.localsend.LocalSendJson
import com.example.bundlecam.network.localsend.LocalSendKeyManager
import com.example.bundlecam.network.localsend.RegisterRequest
import com.example.bundlecam.network.localsend.RegisterResponse
import kotlinx.serialization.encodeToString
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.security.auth.x500.X500Principal

class LocalSendDiscoveryFilterTest {

    private val ownFp = "ourFingerprint"

    private fun bytesOf(announce: Announce): ByteArray =
        LocalSendJson.encodeToString(announce).toByteArray(Charsets.UTF_8)

    @Test
    fun valid_announce_is_returned() {
        val a = Announce(alias = "Peer", fingerprint = "peerFp", announce = true)
        val bytes = bytesOf(a)
        val parsed = LocalSendDiscovery.parseAnnounce(bytes, bytes.size, ownFp)
        assertNotNull(parsed)
        assertEquals("Peer", parsed?.alias)
    }

    @Test
    fun self_echo_filtered_by_fingerprint() {
        val self = Announce(alias = "Us", fingerprint = ownFp, announce = true)
        val bytes = bytesOf(self)
        val parsed = LocalSendDiscovery.parseAnnounce(bytes, bytes.size, ownFp)
        assertNull(parsed)
    }

    @Test
    fun self_echo_filter_is_case_insensitive() {
        // Some LocalSend implementations (e.g. the official Flutter app via basic_utils)
        // emit the fingerprint in uppercase hex; ours is lowercase. A naive `==` would
        // miss a self-echo if a peer reflected our announce in a different casing.
        val ownLower = "abc123def456"
        val echoUpper = Announce(alias = "Us", fingerprint = "ABC123DEF456", announce = true)
        val bytes = bytesOf(echoUpper)
        assertNull(LocalSendDiscovery.parseAnnounce(bytes, bytes.size, ownLower))
    }

    @Test
    fun announce_false_reply_is_returned() {
        // Peers reply via multicast UDP with announce:false; we still surface them.
        val reply = Announce(alias = "Peer", fingerprint = "peerFp", announce = false)
        val bytes = bytesOf(reply)
        val parsed = LocalSendDiscovery.parseAnnounce(bytes, bytes.size, ownFp)
        assertNotNull(parsed)
        assertFalse(parsed!!.announce)
    }

    @Test
    fun v2_2_announce_without_announce_boolean_defaults_to_true() {
        val v22 = """{"alias":"Peer","version":"2.2","fingerprint":"peerFp","port":53317,"protocol":"https"}"""
            .toByteArray()
        val parsed = LocalSendDiscovery.parseAnnounce(v22, v22.size, ownFp)
        assertNotNull(parsed)
        assertTrue(parsed!!.announce)
    }

    @Test
    fun malformed_json_returns_null() {
        val garbage = "not even json".toByteArray()
        assertNull(LocalSendDiscovery.parseAnnounce(garbage, garbage.size, ownFp))
    }

    @Test
    fun missing_required_fields_returns_null() {
        // Missing fingerprint — required.
        val partial = """{"alias":"Half","port":53317}""".toByteArray()
        assertNull(LocalSendDiscovery.parseAnnounce(partial, partial.size, ownFp))
    }

    @Test
    fun zero_length_returns_null() {
        val buf = ByteArray(8)
        assertNull(LocalSendDiscovery.parseAnnounce(buf, 0, ownFp))
    }

    @Test
    fun negative_length_returns_null() {
        val buf = ByteArray(8)
        assertNull(LocalSendDiscovery.parseAnnounce(buf, -1, ownFp))
    }

    @Test
    fun length_beyond_buffer_returns_null() {
        val buf = ByteArray(8)
        assertNull(LocalSendDiscovery.parseAnnounce(buf, 99, ownFp))
    }

    @Test
    fun http_peer_is_rejected() {
        // Peers advertising protocol="http" are dropped — their fingerprint is a random
        // string per spec (not a cert hash), so we have no way to authenticate them.
        val httpPeer = Announce(
            alias = "Insecure",
            fingerprint = "randomString",
            protocol = "http",
            announce = true,
        )
        val bytes = bytesOf(httpPeer)
        assertNull(LocalSendDiscovery.parseAnnounce(bytes, bytes.size, ownFp))
    }

    @Test
    fun length_truncates_buffer() {
        // First 7 bytes "{garbage" so the JSON parser fails — stable null even though
        // the trailing bytes might form a valid payload.
        val truncated = """{"alias":"Peer","fingerprint":"peerFp","announce":true}""".toByteArray()
        // Take a prefix that's not valid JSON.
        assertNull(LocalSendDiscovery.parseAnnounce(truncated, 7, ownFp))
    }

    @Test
    fun announce_to_peer_uses_packet_ip_not_advertised() {
        val a = Announce(
            alias = "Peer",
            fingerprint = "peerFp",
            port = 53317,
            protocol = "https",
            announce = true,
        )
        val peer = LocalSendDiscovery.announceToPeer(a, "192.168.1.42")
        assertEquals("192.168.1.42", peer.ip)
        assertEquals(53317, peer.port)
        assertEquals("https", peer.protocol)
        assertEquals("peerFp", peer.fingerprint)
    }

    @Test
    fun announce_to_peer_passes_through_optional_fields() {
        val a = Announce(
            alias = "Peer",
            deviceModel = "MacBook",
            deviceType = "desktop",
            fingerprint = "peerFp",
            announce = true,
        )
        val peer = LocalSendDiscovery.announceToPeer(a, "10.0.0.5")
        assertEquals("MacBook", peer.deviceModel)
        assertEquals("desktop", peer.deviceType)
    }

    @Test
    fun base_url_format() {
        val a = Announce(
            alias = "P",
            fingerprint = "fp",
            port = 53317,
            protocol = "https",
            announce = true,
        )
        val peer = LocalSendDiscovery.announceToPeer(a, "10.0.0.5")
        assertEquals("https://10.0.0.5:53317", peer.baseUrl())
    }

    @Test
    fun parse_register_request_accepts_matching_mtls_cert() {
        val req = RegisterRequest(
            alias = "Special Mango",
            version = "2.2",
            deviceModel = "macOS",
            deviceType = "desktop",
            fingerprint = "C15BBB1A",
            port = 53317,
            protocol = "https",
        )
        val body = LocalSendJson.encodeToString(req).toByteArray(Charsets.UTF_8)
        val peer = LocalSendDiscovery.parseRegisterRequest(
            body = body,
            ip = "192.168.8.151",
            ownFingerprint = ownFp,
            tlsClientCertFingerprint = "c15bbb1a",
        )
        assertNotNull(peer)
        assertEquals("Special Mango", peer?.alias)
        assertEquals("192.168.8.151", peer?.ip)
        assertEquals(53317, peer?.port)
        assertEquals("c15bbb1a", peer?.fingerprint)
    }

    @Test
    fun parse_register_request_rejects_mismatched_mtls_fingerprint() {
        val req = RegisterRequest(
            alias = "Spoofer",
            fingerprint = "claimedFp",
            port = 53317,
            protocol = "https",
        )
        val body = LocalSendJson.encodeToString(req).toByteArray(Charsets.UTF_8)
        val peer = LocalSendDiscovery.parseRegisterRequest(
            body = body,
            ip = "192.168.8.99",
            ownFingerprint = ownFp,
            tlsClientCertFingerprint = "actualCertFp",
        )
        assertNull(peer)
    }

    @Test
    fun parse_register_request_rejects_self_echo() {
        val req = RegisterRequest(
            alias = "Us",
            fingerprint = ownFp.uppercase(),
            port = 53317,
            protocol = "https",
        )
        val body = LocalSendJson.encodeToString(req).toByteArray(Charsets.UTF_8)
        assertNull(
            LocalSendDiscovery.parseRegisterRequest(
                body = body,
                ip = "192.168.8.10",
                ownFingerprint = ownFp,
                tlsClientCertFingerprint = ownFp,
            )
        )
    }

    @Test
    fun parse_register_response_prefers_tls_server_cert_fingerprint() {
        // Per LocalSend v2.1/v2.2 spec, RegisterResponse.fingerprint is ignored in HTTPS
        // mode and the server's TLS certificate hash is authoritative.
        val resp = RegisterResponse(
            alias = "Special Mango",
            version = "2.2",
            deviceModel = "macOS",
            deviceType = "desktop",
            fingerprint = "ignoredBodyToken",
        )
        val body = LocalSendJson.encodeToString(resp).toByteArray(Charsets.UTF_8)
        val peer = LocalSendDiscovery.parseRegisterResponse(
            body = body,
            ip = "192.168.8.151",
            port = 53317,
            ownFingerprint = ownFp,
            tlsServerCertFingerprint = "verifiedCertSha256",
        )
        assertNotNull(peer)
        assertEquals("verifiedCertSha256", peer?.fingerprint)
        assertEquals("Special Mango", peer?.alias)
    }

    @Test
    fun subnet_candidates_generates_253_rfc1918_hosts_excluding_self() {
        val candidates = LocalSendDiscovery.subnetCandidatesFor("192.168.8.105")
        assertEquals(253, candidates.size)
        assertFalse(candidates.contains("192.168.8.105"))
        assertFalse(candidates.contains("192.168.8.0"))
        assertFalse(candidates.contains("192.168.8.255"))
        assertTrue(candidates.contains("192.168.8.1"))
        assertTrue(candidates.contains("192.168.8.151"))
        assertTrue(candidates.contains("192.168.8.254"))
    }

    @Test
    fun subnet_candidates_skips_non_private_and_cgnat_addresses() {
        // 100.64.0.0/10 is carrier-grade NAT (cellular WAN), must never be subnet-scanned.
        assertTrue(LocalSendDiscovery.subnetCandidatesFor("100.64.1.2").isEmpty())
        assertTrue(LocalSendDiscovery.subnetCandidatesFor("127.0.0.1").isEmpty())
        assertTrue(LocalSendDiscovery.subnetCandidatesFor("172.32.0.1").isEmpty())
        assertEquals(253, LocalSendDiscovery.subnetCandidatesFor("172.16.0.5").size)
        assertEquals(253, LocalSendDiscovery.subnetCandidatesFor("10.0.0.5").size)
    }

    @Test
    fun read_http_request_parses_post_register_with_body() {
        val json = """{"alias":"Special Mango","fingerprint":"abc","port":53317,"protocol":"https"}"""
        val raw = "POST /api/localsend/v2/register HTTP/1.1\r\n" +
            "Host: 192.168.8.105:53317\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${json.toByteArray().size}\r\n\r\n" +
            json
        val parsed = LocalSendDiscovery.readHttpRequest(ByteArrayInputStream(raw.toByteArray()))
        assertNotNull(parsed)
        assertEquals("POST", parsed!!.method)
        assertEquals("/api/localsend/v2/register", parsed.path)
        assertEquals(json, String(parsed.body, Charsets.UTF_8))
    }

    @Test
    fun key_manager_ignores_foreign_issuer_hints_in_mtls() {
        val held = HeldCertificate.Builder()
            .commonName("Recon")
            .organizationalUnit("Recon")
            .build()
        val km = LocalSendKeyManager(held)
        val foreignIssuer = arrayOf(X500Principal("CN=LocalSend User"))
        val alias = km.chooseClientAlias(arrayOf("EC", "RSA"), foreignIssuer, null)
        assertNotNull("Client alias must be returned despite foreign issuer hint", alias)
        assertEquals(1, km.getCertificateChain(alias)?.size)
        assertNotNull(km.getPrivateKey(alias))
        assertEquals(
            64,
            LocalSendCertManager.computeFingerprintHex(km.getCertificateChain(alias)!![0].encoded).length,
        )
    }
}
