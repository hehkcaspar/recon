package com.example.bundlecam.data.camera

import android.content.ContentResolver
import android.graphics.BitmapFactory
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
