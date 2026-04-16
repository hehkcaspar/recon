package com.example.bundlecam.data.camera

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

private const val TARGET_PX = 160

fun decodeThumbnail(bytes: ByteArray, rotationDegrees: Int): ImageBitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > TARGET_PX * 2 ||
        bounds.outHeight / sample > TARGET_PX * 2
    ) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?: error("Failed to decode thumbnail")
    return decoded.rotateIfNeeded(rotationDegrees).asImageBitmap()
}
