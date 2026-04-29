package com.example.bundlecam

import com.example.bundlecam.network.localsend.Announce
import com.example.bundlecam.network.localsend.LocalSendDiscovery
import com.example.bundlecam.network.localsend.LocalSendJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun announce_false_reply_is_returned() {
        // Peers reply via multicast UDP with announce:false; we still surface them.
        val reply = Announce(alias = "Peer", fingerprint = "peerFp", announce = false)
        val bytes = bytesOf(reply)
        val parsed = LocalSendDiscovery.parseAnnounce(bytes, bytes.size, ownFp)
        assertNotNull(parsed)
        assertFalse(parsed!!.announce)
    }

    @Test
    fun malformed_json_returns_null() {
        val garbage = "not even json".toByteArray()
        assertNull(LocalSendDiscovery.parseAnnounce(garbage, garbage.size, ownFp))
    }

    @Test
    fun missing_required_fields_returns_null() {
        // Missing fingerprint AND announce — both required.
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
}
