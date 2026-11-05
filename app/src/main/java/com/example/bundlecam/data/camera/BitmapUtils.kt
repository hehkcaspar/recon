package com.example.bundlecam.data.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.example.bundlecam.ui.capture.window.IntRectPx
import java.io.ByteArrayOutputStream

fun Bitmap.rotateIfNeeded(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (rotated !== this) recycle()
    return rotated
}

/**
 * Pure helper computing the clamped crop rectangle within `[0, bufferWidth] × [0, bufferHeight]`.
 * Returns `null` when no cropping is required (full buffer or invalid bounds).
 */
fun computeClampedCropRect(
    cropRect: IntRectPx?,
    bufferWidth: Int,
    bufferHeight: Int,
): IntRectPx? {
    if (cropRect == null || bufferWidth <= 0 || bufferHeight <= 0) return null
    val left = cropRect.left.coerceIn(0, bufferWidth)
    val top = cropRect.top.coerceIn(0, bufferHeight)
    val right = cropRect.right.coerceIn(left, bufferWidth)
    val bottom = cropRect.bottom.coerceIn(top, bufferHeight)
    val w = right - left
    val h = bottom - top
    if (w <= 0 || h <= 0) return null
    if (left == 0 && top == 0 && w == bufferWidth && h == bufferHeight) return null
    return IntRectPx(left, top, right, bottom)
}

/**
 * Applies [cropRect] to in-memory JPEG bytes if [cropRect] represents a sub-region
 * of the captured buffer (e.g., when a shared CameraX `ViewPort` crops the sensor frame).
 */
fun applyJpegCropIfNeeded(
    jpegBytes: ByteArray,
    cropRect: IntRectPx?,
    bufferWidth: Int,
    bufferHeight: Int,
): ByteArray {
    val clamped = computeClampedCropRect(cropRect, bufferWidth, bufferHeight) ?: return jpegBytes
    return runCatching {
        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return jpegBytes
        val safeLeft = clamped.left.coerceIn(0, decoded.width - 1)
        val safeTop = clamped.top.coerceIn(0, decoded.height - 1)
        val safeWidth = clamped.width.coerceIn(1, decoded.width - safeLeft)
        val safeHeight = clamped.height.coerceIn(1, decoded.height - safeTop)
        if (safeLeft == 0 && safeTop == 0 && safeWidth == decoded.width && safeHeight == decoded.height) {
            decoded.recycle()
            return jpegBytes
        }
        val cropped = Bitmap.createBitmap(decoded, safeLeft, safeTop, safeWidth, safeHeight)
        if (cropped !== decoded) decoded.recycle()
        val out = ByteArrayOutputStream(jpegBytes.size)
        cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
        cropped.recycle()
        out.toByteArray()
    }.getOrDefault(jpegBytes)
}
