package com.example.bundlecam.data.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
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
 * SAF URI overload for thumbnails of saved bundles. Reads the file into memory once so
 * bounds + decode + EXIF share a single ContentProvider roundtrip; opening the stream
 * three times (which `BitmapFactory` would otherwise need since it can't rewind a
 * ContentProvider InputStream) is ~3x the IPC latency.
 */
fun decodeThumbnail(contentResolver: ContentResolver, uri: Uri): ImageBitmap? {
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
 * Extract a poster frame from an MP4 via MediaMetadataRetriever. Used both when pushing
 * a freshly-recorded video onto the queue and by OrphanRecovery when restoring a video
 * from a killed-mid-session staging folder. Returns null if the file is unreadable or
 * corrupt (e.g., zero-byte from a process kill before the muxer flushed).
 */
fun decodeVideoPoster(file: File): ImageBitmap? {
    if (!file.exists() || file.length() == 0L) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        val scaled = if (frame.width > TARGET_PX * 2 || frame.height > TARGET_PX * 2) {
            val ratio = minOf(TARGET_PX * 2f / frame.width, TARGET_PX * 2f / frame.height)
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * ratio).toInt().coerceAtLeast(1),
                (frame.height * ratio).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== frame) frame.recycle() }
        } else frame
        scaled.asImageBitmap()
    } catch (t: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** True if the video at [file] has a readable duration — the sanity check OrphanRecovery uses. */
fun isVideoPlayable(file: File): Boolean {
    if (!file.exists() || file.length() == 0L) return false
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        duration != null
    } catch (t: Throwable) {
        false
    } finally {
        runCatching { retriever.release() }
    }
}
