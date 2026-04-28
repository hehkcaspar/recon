package com.example.bundlecam.network.localsend

import android.content.ContentResolver
import android.util.Log
import com.example.bundlecam.data.storage.BundleFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Recon/LocalSendUploader"
private const val PARALLEL_UPLOADS = 3
private const val UPLOAD_BUFFER_BYTES = 64 * 1024
private val APPLICATION_JSON = "application/json".toMediaType()

/** Best-effort report of how a bundle send went. UI surfaces these into the banner. */
sealed class SendBundleResult {
    data object Success : SendBundleResult()
    data class Failed(val message: String, val httpStatus: Int? = null) : SendBundleResult()
    data object Cancelled : SendBundleResult()
    data object AlreadyReceived : SendBundleResult()
}

/**
 * Streaming progress for one bundle send. Emitted on every per-file completion and on
 * every chunk write — UI can treat it as a `latest-wins` stream.
 */
data class SendProgress(
    val bundleId: String,
    val totalFiles: Int,
    val completedFiles: Int,
    val totalBytes: Long,
    val sentBytes: Long,
    val currentFileName: String?,
)

class LocalSendUploader(
    private val client: OkHttpClient,
    private val resolver: ContentResolver,
) {
    /**
     * Send one bundle as a single LocalSend session: prepare-upload to negotiate, then
     * parallel per-file uploads (cap [PARALLEL_UPLOADS]). Per the v2.1 spec each file is
     * a raw-bytes POST, not multipart.
     *
     * On any irrecoverable HTTP error or coroutine cancellation, sends `POST /cancel`
     * best-effort so the peer doesn't sit on a half-received session.
     */
    suspend fun sendBundle(
        peer: Peer,
        bundleId: String,
        info: Info,
        files: List<BundleFile>,
        onProgress: (SendProgress) -> Unit = {},
    ): SendBundleResult = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext SendBundleResult.Success

        val totalBytes = files.sumOf { it.size }
        val sentBytes = AtomicLong(0L)
        val completedFiles = AtomicLong(0L)

        fun emitProgress(currentFileName: String?) {
            onProgress(
                SendProgress(
                    bundleId = bundleId,
                    totalFiles = files.size,
                    completedFiles = completedFiles.get().toInt(),
                    totalBytes = totalBytes,
                    sentBytes = sentBytes.get(),
                    currentFileName = currentFileName,
                )
            )
        }

        emitProgress(null)

        // Phase 1 — prepare-upload.
        val prepRequest = buildPrepareUploadRequest(files, info)
        val prepResponse = try {
            prepareUpload(peer, prepRequest)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: HttpStatusException) {
            return@withContext when (e.status) {
                204 -> SendBundleResult.AlreadyReceived
                401 -> SendBundleResult.Failed("Peer requires a PIN — not supported yet", 401)
                403 -> SendBundleResult.Failed("Peer rejected the bundle", 403)
                409 -> SendBundleResult.Failed("Peer is busy with another transfer", 409)
                429 -> SendBundleResult.Failed("Too many requests; try again", 429)
                else -> SendBundleResult.Failed("Prepare failed (HTTP ${e.status})", e.status)
            }
        } catch (e: IOException) {
            return@withContext SendBundleResult.Failed("Prepare failed: ${e.message}")
        }

        // Phase 2 — parallel raw uploads.
        val tokens = prepResponse.files
        val sessionId = prepResponse.sessionId
        val semaphore = Semaphore(PARALLEL_UPLOADS)
        try {
            coroutineScope {
                files.map { f ->
                    val token = tokens[f.fileName]
                    if (token == null) {
                        Log.w(TAG, "no upload token for ${f.fileName} — peer may dedup or reject")
                        return@map null
                    }
                    async {
                        semaphore.withPermit {
                            uploadOne(
                                peer = peer,
                                sessionId = sessionId,
                                fileId = f.fileName,
                                token = token,
                                file = f,
                                onChunk = { delta ->
                                    sentBytes.addAndGet(delta)
                                    emitProgress(f.fileName)
                                },
                            )
                            completedFiles.incrementAndGet()
                            emitProgress(f.fileName)
                        }
                    }
                }.filterNotNull().awaitAll()
            }
        } catch (ce: CancellationException) {
            sendCancelBestEffort(peer, sessionId)
            throw ce
        } catch (e: HttpStatusException) {
            sendCancelBestEffort(peer, sessionId)
            return@withContext when (e.status) {
                403 -> SendBundleResult.Failed("Peer rejected mid-transfer", 403)
                409 -> SendBundleResult.Failed("Peer dropped the session", 409)
                else -> SendBundleResult.Failed("Upload failed (HTTP ${e.status})", e.status)
            }
        } catch (e: IOException) {
            sendCancelBestEffort(peer, sessionId)
            return@withContext SendBundleResult.Failed("Upload failed: ${e.message}")
        }

        SendBundleResult.Success
    }

    private suspend fun prepareUpload(peer: Peer, payload: PrepareUploadRequest): PrepareUploadResponse {
        val url = "${peer.baseUrl()}$LOCALSEND_API_PATH_PREPARE_UPLOAD".toHttpUrl()
        val body = LocalSendJson.encodeToString(payload).toRequestBody(APPLICATION_JSON)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .expectFingerprint(peer.fingerprint)
            .build()
        client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val text = response.body?.string()
                ?: throw IOException("empty body from prepare-upload")
            return LocalSendJson.decodeFromString(text)
        }
    }

    private suspend fun uploadOne(
        peer: Peer,
        sessionId: String,
        fileId: String,
        token: String,
        file: BundleFile,
        onChunk: (delta: Long) -> Unit,
    ) {
        val url = "${peer.baseUrl()}$LOCALSEND_API_PATH_UPLOAD".toHttpUrl()
            .newBuilder()
            .addQueryParameter("sessionId", sessionId)
            .addQueryParameter("fileId", fileId)
            .addQueryParameter("token", token)
            .build()
        val body = StreamingSafBody(
            resolver = resolver,
            uri = file.uri,
            mediaType = file.mimeType.toMediaType(),
            sizeBytes = file.size,
            onChunk = onChunk,
        )
        val request = Request.Builder()
            .url(url)
            .post(body)
            .expectFingerprint(peer.fingerprint)
            .build()
        client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
        }
    }

    private suspend fun sendCancelBestEffort(peer: Peer, sessionId: String) {
        val url = "${peer.baseUrl()}$LOCALSEND_API_PATH_CANCEL".toHttpUrl()
            .newBuilder()
            .addQueryParameter("sessionId", sessionId)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody(null))
            .expectFingerprint(peer.fingerprint)
            .build()
        runCatching { client.newCall(request).awaitResponse().close() }
            .onFailure { Log.w(TAG, "cancel failed for $sessionId", it) }
    }

    companion object {
        /**
         * Pure helper — testable. Build the prepare-upload payload from a bundle's file
         * list. File ID = file name (Recon's naming guarantees uniqueness within a
         * bundle, and human-readable IDs are nicer for receiver-side debugging than
         * synthetic UUIDs).
         */
        fun buildPrepareUploadRequest(files: List<BundleFile>, info: Info): PrepareUploadRequest {
            val map = LinkedHashMap<String, FileMetadata>(files.size)
            files.forEach { f ->
                map[f.fileName] = FileMetadata(
                    id = f.fileName,
                    fileName = f.fileName,
                    size = f.size,
                    fileType = f.mimeType,
                )
            }
            return PrepareUploadRequest(info = info, files = map)
        }
    }
}

/** Marker for a non-2xx HTTP response — caught by the per-bundle send path. */
private class HttpStatusException(val status: Int) :
    IOException("HTTP $status")

/** Streams a SAF Uri into a [BufferedSink] in [UPLOAD_BUFFER_BYTES] chunks. */
private class StreamingSafBody(
    private val resolver: ContentResolver,
    private val uri: android.net.Uri,
    private val mediaType: okhttp3.MediaType?,
    private val sizeBytes: Long,
    private val onChunk: (delta: Long) -> Unit,
) : RequestBody() {
    override fun contentType() = mediaType
    override fun contentLength(): Long = sizeBytes

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("openInputStream returned null for $uri")
        input.use { stream ->
            val buf = ByteArray(UPLOAD_BUFFER_BYTES)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                onChunk(n.toLong())
            }
        }
    }

    // OkHttp may retry — but our SAF stream is one-shot (and large bodies shouldn't be
    // double-read anyway), so refuse retries to avoid double-reading the same file.
    override fun isOneShot(): Boolean = true
}

/** Coroutine-friendly Call.execute that propagates cancellation back to OkHttp. */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
}
