package com.example.bundlecam.network.localsend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val LOCALSEND_PROTOCOL_VERSION = "2.1"
const val LOCALSEND_PORT = 53317
const val LOCALSEND_MULTICAST_GROUP = "224.0.0.167"
const val LOCALSEND_DEVICE_TYPE_MOBILE = "mobile"

const val LOCALSEND_API_PATH_REGISTER = "/api/localsend/v2/register"
const val LOCALSEND_API_PATH_PREPARE_UPLOAD = "/api/localsend/v2/prepare-upload"
const val LOCALSEND_API_PATH_UPLOAD = "/api/localsend/v2/upload"
const val LOCALSEND_API_PATH_CANCEL = "/api/localsend/v2/cancel"

// encodeDefaults so `version` always lands on the wire (DTO defaults to LOCALSEND_PROTOCOL_VERSION).
// explicitNulls = false omits nullable fields like deviceModel when null — the spec's
// example payloads include those fields only when populated, and some receivers reject
// `null` literals.
val LocalSendJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Multicast announcement payload — sent on `224.0.0.167:53317` and also received as
 * peer responses (with `announce: false`). Same shape both directions.
 */
@Serializable
data class Announce(
    val alias: String,
    val version: String = LOCALSEND_PROTOCOL_VERSION,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String,
    val port: Int = LOCALSEND_PORT,
    val protocol: String = "https",
    val download: Boolean = false,
    val announce: Boolean,
)

/**
 * `POST /api/localsend/v2/register` request body — fallback discovery when multicast is
 * blocked (hotel/carrier networks, AP isolation). Same fields as [Announce] minus
 * `announce`.
 */
@Serializable
data class RegisterRequest(
    val alias: String,
    val version: String = LOCALSEND_PROTOCOL_VERSION,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String,
    val port: Int = LOCALSEND_PORT,
    val protocol: String = "https",
    val download: Boolean = false,
)

/**
 * `POST /api/localsend/v2/register` response body — receiver's identity. Note no `port`
 * or `protocol` fields: the responding peer is reachable at the IP that returned this
 * response, on the port the original request was sent to.
 */
@Serializable
data class RegisterResponse(
    val alias: String,
    val version: String = LOCALSEND_PROTOCOL_VERSION,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String,
    val download: Boolean = false,
)

/**
 * Sender-identity block embedded in [PrepareUploadRequest]. Same fields as
 * [RegisterRequest] in the v2.1 spec.
 */
@Serializable
data class Info(
    val alias: String,
    val version: String = LOCALSEND_PROTOCOL_VERSION,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String,
    val port: Int = LOCALSEND_PORT,
    val protocol: String = "https",
    val download: Boolean = false,
)

/**
 * Per-file metadata in [PrepareUploadRequest.files]. `fileType` is a MIME type string
 * (e.g. `image/jpeg`, `video/mp4`, `audio/mp4`). `sha256`, `preview`, `metadata` are all
 * optional — Recon doesn't populate them in the MVP.
 */
@Serializable
data class FileMetadata(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
    val metadata: FileTimestamps? = null,
)

@Serializable
data class FileTimestamps(
    val modified: String? = null,
    val accessed: String? = null,
)

@Serializable
data class PrepareUploadRequest(
    val info: Info,
    val files: Map<String, FileMetadata>,
)

/**
 * `POST /api/localsend/v2/prepare-upload` response. The `files` map maps the file IDs
 * from the request to per-file upload tokens, used as the `?token=` query param on the
 * subsequent `POST /upload` calls.
 */
@Serializable
data class PrepareUploadResponse(
    val sessionId: String,
    val files: Map<String, String>,
)
