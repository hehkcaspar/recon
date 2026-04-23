package com.example.bundlecam.data.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import com.example.bundlecam.data.storage.StorageLayout
import com.example.bundlecam.data.storage.StorageLayout.MediaKind
import java.io.File

private const val TARGET_PX = 160

fun decodeThumbnail(bytes: ByteArray, rotationDegrees: Int): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?: error("Failed to decode thumbnail")
    return decoded.rotateIfNeeded(rotationDegrees).asImageBitmap()
}

/**
 * Path-based overload — decodes straight from disk without loading the full JPEG into
 * memory. Used by orphan recovery so a 50-photo queue doesn't transiently hold ~250MB.
 */
fun decodeThumbnail(file: File, rotationDegrees: Int): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
        ?: error("Failed to decode thumbnail from ${file.name}")
    return decoded.rotateIfNeeded(rotationDegrees).asImageBitmap()
}

/**
 * SAF URI overload for thumbnails of saved bundles. Dispatches by filename extension:
 * - `.jpg` / `.jpeg` → decode JPEG + honor EXIF rotation (photo path).
 * - `.mp4`            → extract a poster frame via MediaMetadataRetriever + SAF fd
 *                       (for video-only bundles in Bundle Preview).
 * - `.m4a`            → synthesize a navy tile with a white mic glyph drawn in
 *                       (for voice-only bundles). Static content, so we generate
 *                       it on demand without touching the file itself.
 *
 * Reads image bytes once so bounds + decode + EXIF share a single ContentProvider
 * roundtrip; opening the stream three times (which `BitmapFactory` would otherwise
 * need since it can't rewind a ContentProvider InputStream) is ~3x the IPC latency.
 */
fun decodeThumbnail(contentResolver: ContentResolver, uri: Uri): ImageBitmap? {
    val name = uri.lastPathSegment.orEmpty()
    return when (StorageLayout.mediaKindFor(name)) {
        MediaKind.Photo -> decodeJpegThumbnail(contentResolver, uri)
        MediaKind.Video -> decodeVideoPoster(contentResolver, uri)
        MediaKind.Voice -> renderVoiceThumbnail()
        null -> null
    }
}

private fun decodeJpegThumbnail(contentResolver: ContentResolver, uri: Uri): ImageBitmap? {
    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    val rotation = runCatching {
        when (ExifInterface(bytes.inputStream()).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
    return decoded.rotateIfNeeded(rotation).asImageBitmap()
}

private fun sampleSizeFor(w: Int, h: Int): Int {
    var sample = 1
    while (w / sample > TARGET_PX * 2 || h / sample > TARGET_PX * 2) sample *= 2
    return sample
}

/**
 * Open a [MediaMetadataRetriever], run [block] with it, and guarantee release. Returns
 * null if the data source rejected the file (torn/unreadable) or the block threw.
 */
internal inline fun <T> withMediaRetriever(
    setup: MediaMetadataRetriever.() -> Unit,
    block: MediaMetadataRetriever.() -> T?,
): T? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setup()
        retriever.block()
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/**
 * Extract a poster frame from an MP4 via MediaMetadataRetriever. Used both when pushing
 * a freshly-recorded video onto the queue and by OrphanRecovery when restoring a video
 * from a killed-mid-session staging folder. Returns null if the file is unreadable or
 * corrupt (e.g., zero-byte from a process kill before the muxer flushed).
 */
fun decodeVideoPoster(file: File): ImageBitmap? {
    if (!file.exists() || file.length() == 0L) return null
    return withMediaRetriever(
        setup = { setDataSource(file.absolutePath) },
        block = { extractPoster() },
    )
}

/**
 * SAF URI overload of the video poster extractor. Used by Bundle Preview when a video
 * file is in the SAF tree and we want its first-frame thumbnail for the row.
 */
fun decodeVideoPoster(contentResolver: ContentResolver, uri: Uri): ImageBitmap? {
    val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return null
    return pfd.use {
        withMediaRetriever(
            setup = { setDataSource(it.fileDescriptor) },
            block = { extractPoster() },
        )
    }
}

private fun MediaMetadataRetriever.extractPoster(): ImageBitmap? {
    val frame = getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
    return scaleToPosterSize(frame).asImageBitmap()
}

private fun scaleToPosterSize(frame: Bitmap): Bitmap {
    return if (frame.width > TARGET_PX * 2 || frame.height > TARGET_PX * 2) {
        val ratio = minOf(TARGET_PX * 2f / frame.width, TARGET_PX * 2f / frame.height)
        Bitmap.createScaledBitmap(
            frame,
            (frame.width * ratio).toInt().coerceAtLeast(1),
            (frame.height * ratio).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== frame) frame.recycle() }
    } else frame
}

/**
 * Render a Bundle-Preview-sized voice thumbnail: a navy tile with a white mic glyph
 * drawn by hand (no external icon resource, no ImageVector→Bitmap conversion). Sized
 * to the standard thumbnail target so it doesn't upscale in the row.
 */
private fun renderVoiceThumbnail(): ImageBitmap {
    val size = TARGET_PX * 2
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(VOICE_NAVY_ARGB)

    val white = 0xFFFFFFFF.toInt()
    val fill = android.graphics.Paint().apply {
        color = white
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
    }
    val stroke = android.graphics.Paint().apply {
        color = white
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = size * 0.05f
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    val cx = size / 2f
    val cy = size / 2f - size * 0.05f
    // Mic capsule: rounded vertical pill centered slightly above the midline.
    val capsuleWidth = size * 0.26f
    val capsuleHeight = size * 0.40f
    val capsule = android.graphics.RectF(
        cx - capsuleWidth / 2f,
        cy - capsuleHeight / 2f,
        cx + capsuleWidth / 2f,
        cy + capsuleHeight / 2f,
    )
    canvas.drawRoundRect(capsule, capsuleWidth / 2f, capsuleWidth / 2f, fill)

    // Stand arch under the capsule.
    val archWidth = size * 0.46f
    val archTop = cy + capsuleHeight * 0.20f
    val archBottom = archTop + size * 0.22f
    val archRect = android.graphics.RectF(cx - archWidth / 2f, archTop, cx + archWidth / 2f, archBottom)
    canvas.drawArc(archRect, 0f, 180f, false, stroke)

    // Vertical post from arch center down to the base line.
    val postTop = archBottom - (archBottom - archTop) / 2f
    val postBottom = postTop + size * 0.12f
    canvas.drawLine(cx, postTop, cx, postBottom, stroke)

    // Short base line at the bottom.
    val baseHalf = size * 0.09f
    canvas.drawLine(cx - baseHalf, postBottom, cx + baseHalf, postBottom, stroke)

    return bmp.asImageBitmap()
}

/**
 * Generate a placeholder thumbnail for a voice item. The QueueThumbnail's Voice branch
 * overlays the mic glyph + duration badge on top, so the placeholder just needs to be
 * any ImageBitmap — a tiny tinted square is cheap and uniform. Kept here so the
 * StagedItem contract (thumbnail: ImageBitmap) doesn't need a nullable field.
 */
fun decodeVoiceThumbnail(): ImageBitmap {
    // 8x8 tinted square. Smaller than 1x1 actually isn't cheaper to allocate and 8x8
    // side-steps any zero-size sanity check in a downstream consumer.
    val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(VOICE_NAVY_ARGB)
    return bmp.asImageBitmap()
}

// Raw-ARGB twin of CaptureColors.VoiceNavy — android.graphics.Canvas doesn't take
// compose Color, so we keep the Int representation adjacent for the two Canvas sites.
private const val VOICE_NAVY_ARGB: Int = 0xFF1F2A44.toInt()

/**
 * True if the MP4/M4A at [file] exposes a readable duration via MediaMetadataRetriever.
 * OrphanRecovery uses this as the torn-file probe for both video (`.mp4`, Media3 Muxer
 * written by CameraX Recorder) and voice (`.m4a`, MediaRecorder AAC-LC). A killed
 * recording typically leaves the file unreadable — this check drops those.
 */
fun isMediaFileReadable(file: File): Boolean {
    if (!file.exists() || file.length() == 0L) return false
    return withMediaRetriever(
        setup = { setDataSource(file.absolutePath) },
        block = { extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) },
    ) != null
}

/**
 * Read the MP4/M4A duration in ms, or 0 if unreadable. Shared by OrphanRecovery so it
 * doesn't have to re-open a retriever for every restored video/voice file.
 */
fun readMediaDurationMs(file: File): Long = withMediaRetriever(
    setup = { setDataSource(file.absolutePath) },
    block = { extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() },
) ?: 0L
