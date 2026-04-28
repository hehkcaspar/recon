package com.example.bundlecam

import com.example.bundlecam.network.localsend.Announce
import com.example.bundlecam.network.localsend.FileMetadata
import com.example.bundlecam.network.localsend.FileTimestamps
import com.example.bundlecam.network.localsend.Info
import com.example.bundlecam.network.localsend.LOCALSEND_PORT
import com.example.bundlecam.network.localsend.LOCALSEND_PROTOCOL_VERSION
import com.example.bundlecam.network.localsend.LocalSendJson
import com.example.bundlecam.network.localsend.PrepareUploadRequest
import com.example.bundlecam.network.localsend.PrepareUploadResponse
import com.example.bundlecam.network.localsend.RegisterRequest
import com.example.bundlecam.network.localsend.RegisterResponse
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSendDtoSerializationTest {

    @Test
    fun announce_roundtrips_with_all_fields() {
        val original = Announce(
            alias = "Nice Orange",
            version = "2.1",
            deviceModel = "Pixel 8",
            deviceType = "mobile",
            fingerprint = "abc123",
            port = 53317,
            protocol = "https",
            download = false,
            announce = true,
        )
        val json = LocalSendJson.encodeToString(original)
        val decoded = LocalSendJson.decodeFromString<Announce>(json)
        assertEquals(original, decoded)
    }

    @Test
    fun announce_omits_nullable_fields_when_null() {
        val a = Announce(alias = "A", fingerprint = "fp", announce = true)
        val json = LocalSendJson.encodeToString(a)
        // explicitNulls=false: deviceModel and deviceType should NOT appear in the JSON
        // when null, matching the spec's example payloads.
        assertFalse("deviceModel should be omitted when null: $json", json.contains("deviceModel"))
        assertFalse("deviceType should be omitted when null: $json", json.contains("deviceType"))
    }

    @Test
    fun announce_always_encodes_version_default() {
        // encodeDefaults=true ensures version lands on the wire even when left at default.
        val a = Announce(alias = "A", fingerprint = "fp", announce = true)
        val json = LocalSendJson.encodeToString(a)
        assertTrue("version must be present: $json", json.contains("\"version\""))
        assertTrue("version must be $LOCALSEND_PROTOCOL_VERSION: $json", json.contains("\"$LOCALSEND_PROTOCOL_VERSION\""))
    }

    @Test
    fun announce_field_names_match_spec_exactly() {
        val a = Announce(
            alias = "A",
            deviceModel = "M",
            deviceType = "mobile",
            fingerprint = "fp",
            announce = true,
        )
        val json = LocalSendJson.encodeToString(a)
        // Spec uses camelCase verbatim — kotlinx-serialization's default matches Kotlin
        // field names, but a stray @SerialName slip would silently break interop.
        listOf("alias", "version", "deviceModel", "deviceType", "fingerprint", "port", "protocol", "download", "announce")
            .forEach { field -> assertTrue("missing $field in $json", json.contains("\"$field\"")) }
    }

    @Test
    fun announce_decodes_with_unknown_fields() {
        // ignoreUnknownKeys=true so a future v2.2 field doesn't break us.
        val futureJson = """
            {
              "alias": "A",
              "version": "2.2",
              "fingerprint": "fp",
              "announce": true,
              "futureField": "ignored"
            }
        """.trimIndent()
        val decoded = LocalSendJson.decodeFromString<Announce>(futureJson)
        assertEquals("A", decoded.alias)
        assertEquals("2.2", decoded.version)
    }

    @Test
    fun announce_decodes_when_announce_field_inverted() {
        // Inbound "response" announces (announce:false) must decode just like outbound.
        val response = """
            {"alias":"Peer","fingerprint":"pf","announce":false,"port":53317,"protocol":"https"}
        """.trimIndent()
        val decoded = LocalSendJson.decodeFromString<Announce>(response)
        assertFalse(decoded.announce)
    }

    @Test
    fun register_request_roundtrips() {
        val original = RegisterRequest(
            alias = "Sender",
            deviceModel = "Pixel",
            deviceType = "mobile",
            fingerprint = "ffaa",
        )
        val json = LocalSendJson.encodeToString(original)
        val decoded = LocalSendJson.decodeFromString<RegisterRequest>(json)
        assertEquals(original, decoded)
        assertFalse("RegisterRequest must NOT include `announce`: $json", json.contains("\"announce\""))
    }

    @Test
    fun register_response_roundtrips_without_port_or_protocol() {
        val original = RegisterResponse(
            alias = "Peer",
            deviceModel = "MacBook",
            deviceType = "desktop",
            fingerprint = "ee11",
        )
        val json = LocalSendJson.encodeToString(original)
        val decoded = LocalSendJson.decodeFromString<RegisterResponse>(json)
        assertEquals(original, decoded)
        assertFalse("RegisterResponse must NOT include `port`: $json", json.contains("\"port\""))
        assertFalse("RegisterResponse must NOT include `protocol\"`: $json", json.contains("\"protocol\""))
    }

    @Test
    fun file_metadata_minimal_fields() {
        val meta = FileMetadata(
            id = "file-1",
            fileName = "image.jpg",
            size = 1234L,
            fileType = "image/jpeg",
        )
        val json = LocalSendJson.encodeToString(meta)
        val decoded = LocalSendJson.decodeFromString<FileMetadata>(json)
        assertEquals(meta, decoded)
        assertNull(decoded.sha256)
        assertNull(decoded.preview)
        assertNull(decoded.metadata)
        // sha256 / preview / metadata not present in JSON.
        assertFalse("sha256 should be omitted when null: $json", json.contains("sha256"))
        assertFalse("preview should be omitted when null: $json", json.contains("preview"))
    }

    @Test
    fun file_metadata_with_timestamps() {
        val meta = FileMetadata(
            id = "f",
            fileName = "v.mp4",
            size = 999L,
            fileType = "video/mp4",
            metadata = FileTimestamps(modified = "2026-04-28T12:00:00Z"),
        )
        val json = LocalSendJson.encodeToString(meta)
        val decoded = LocalSendJson.decodeFromString<FileMetadata>(json)
        assertEquals(meta, decoded)
        assertTrue("metadata block present: $json", json.contains("\"metadata\""))
        assertTrue("modified present: $json", json.contains("\"modified\""))
        assertFalse("accessed (null) omitted: $json", json.contains("\"accessed\""))
    }

    @Test
    fun prepare_upload_request_roundtrips() {
        val req = PrepareUploadRequest(
            info = Info(alias = "S", fingerprint = "fp"),
            files = mapOf(
                "id-1" to FileMetadata(id = "id-1", fileName = "a.jpg", size = 1L, fileType = "image/jpeg"),
                "id-2" to FileMetadata(id = "id-2", fileName = "b.mp4", size = 2L, fileType = "video/mp4"),
            ),
        )
        val json = LocalSendJson.encodeToString(req)
        val decoded = LocalSendJson.decodeFromString<PrepareUploadRequest>(json)
        assertEquals(req, decoded)
        assertEquals(2, decoded.files.size)
        assertEquals("a.jpg", decoded.files["id-1"]?.fileName)
    }

    @Test
    fun prepare_upload_response_roundtrips() {
        val resp = PrepareUploadResponse(
            sessionId = "session-abc",
            files = mapOf("id-1" to "tok-1", "id-2" to "tok-2"),
        )
        val json = LocalSendJson.encodeToString(resp)
        val decoded = LocalSendJson.decodeFromString<PrepareUploadResponse>(json)
        assertEquals(resp, decoded)
        // Spec field name is "sessionId", not "session_id".
        assertTrue("sessionId field name: $json", json.contains("\"sessionId\""))
        assertFalse("must not snake_case: $json", json.contains("session_id"))
    }

    @Test
    fun port_default_is_localsend_port() {
        val a = Announce(alias = "A", fingerprint = "fp", announce = true)
        assertEquals(LOCALSEND_PORT, a.port)
    }
}
