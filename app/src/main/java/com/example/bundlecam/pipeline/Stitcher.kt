package com.example.bundlecam.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import com.example.bundlecam.data.camera.rotateIfNeeded
import com.example.bundlecam.data.settings.StitchQuality
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.sqrt

private const val HEIGHT_CAP_PX = 32_000
private const val HEAP_BUDGET_FRACTION = 0.6

data class StitchSource(
    val file: File,
    val rotationDegrees: Int,
)

internal data class StitchLayout(
    val commonWidth: Int,
    val slotHeights: List<Int>,
) {
    val totalHeight: Int get() = slotHeights.sum()
    val plannedBytes: Long get() = commonWidth.toLong() * totalHeight * 4
}

class Stitcher {
    fun stitch(
        sources: List<StitchSource>,
        quality: StitchQuality,
        destination: File,
    ) {
        require(sources.isNotEmpty()) { "No sources to stitch" }

        val visualDims = sources.map { visualDimensions(it) }
        val layout = computeLayout(visualDims, quality, availableHeapBudget())
        val target = createBitmap(layout.commonWidth, layout.totalHeight)
        val canvas = Canvas(target)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        var currentY = 0
        sources.forEachIndexed { index, source ->
            val slotHeight = layout.slotHeights[index]
            val (rawW, rawH) = visualDims[index]
            drawIntoSlot(source, rawW, rawH, canvas, paint, layout.commonWidth, slotHeight, currentY)
            currentY += slotHeight
        }

        FileOutputStream(destination).use { out ->
            target.compress(Bitmap.CompressFormat.JPEG, jpegQuality(quality), out)
        }
        target.recycle()
    }

    private fun visualDimensions(source: StitchSource): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.file.absolutePath, opts)
        return rotatedSize(opts.outWidth, opts.outHeight, source.rotationDegrees)
    }

    private fun drawIntoSlot(
        source: StitchSource,
        rawW: Int,
        rawH: Int,
        canvas: Canvas,
        paint: Paint,
        slotWidth: Int,
        slotHeight: Int,
        topY: Int,
    ) {
        val path = source.file.absolutePath

        var sample = 1
        while (rawW / (sample * 2) >= slotWidth && rawH / (sample * 2) >= slotHeight) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(path, opts)
            ?: throw IOException("Failed to decode ${source.file.name}")

        val rotated = decoded.rotateIfNeeded(source.rotationDegrees)

        val dstRect = Rect(0, topY, slotWidth, topY + slotHeight)
        val srcRect = Rect(0, 0, rotated.width, rotated.height)
        canvas.drawBitmap(rotated, srcRect, dstRect, paint)
        rotated.recycle()
    }

    private fun jpegQuality(quality: StitchQuality): Int = when (quality) {
        StitchQuality.LOW -> 70
        StitchQuality.STANDARD -> 82
        StitchQuality.HIGH -> 92
    }

    private fun availableHeapBudget(): Long =
        (Runtime.getRuntime().maxMemory() * HEAP_BUDGET_FRACTION).toLong()

    companion object {
        /**
         * Pure layout math — callable from tests without any Bitmap allocation.
         * - Clamps common width to the quality ceiling and the narrowest source.
         * - Scales down to respect [HEIGHT_CAP_PX].
         * - Scales down further to respect [heapBudget] on total bytes.
         */
        internal fun computeLayout(
            visualDims: List<Pair<Int, Int>>,
            quality: StitchQuality,
            heapBudget: Long,
        ): StitchLayout {
            require(visualDims.isNotEmpty()) { "No sources to stitch" }
            require(heapBudget > 0) { "heapBudget must be positive, got $heapBudget" }

            val qualityCeilingPx = when (quality) {
                StitchQuality.LOW -> 1600
                StitchQuality.STANDARD -> 1800
                StitchQuality.HIGH -> Int.MAX_VALUE
            }

            val minWidth = visualDims.minOf { it.first }
            var commonWidth = minWidth.coerceAtMost(qualityCeilingPx)
            var heights = visualDims.map { (w, h) ->
                (h.toFloat() * commonWidth / w).toInt().coerceAtLeast(1)
            }

            var totalHeight = heights.sum()
            if (totalHeight > HEIGHT_CAP_PX) {
                val scale = HEIGHT_CAP_PX.toFloat() / totalHeight
                commonWidth = (commonWidth * scale).toInt().coerceAtLeast(1)
                heights = heights.map { (it * scale).toInt().coerceAtLeast(1) }
                totalHeight = heights.sum()
            }

            val plannedBytes = commonWidth.toLong() * totalHeight * 4
            if (plannedBytes > heapBudget) {
                val scale = sqrt(heapBudget.toDouble() / plannedBytes).toFloat()
                commonWidth = (commonWidth * scale).toInt().coerceAtLeast(1)
                heights = heights.map { (it * scale).toInt().coerceAtLeast(1) }
            }

            return StitchLayout(commonWidth, heights)
        }
    }
}

private fun rotatedSize(w: Int, h: Int, degrees: Int): Pair<Int, Int> =
    if (degrees == 90 || degrees == 270) h to w else w to h
