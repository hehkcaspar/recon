package com.example.bundlecam.data.camera

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

private fun sampleSizeFor(w: Int, h: Int): Int {
    var sample = 1
    while (w / sample > TARGET_PX * 2 || h / sample > TARGET_PX * 2) sample *= 2
    return sample
}
