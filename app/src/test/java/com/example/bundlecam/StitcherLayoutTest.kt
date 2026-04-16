package com.example.bundlecam

import com.example.bundlecam.data.settings.StitchQuality
import com.example.bundlecam.pipeline.Stitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [Stitcher.computeLayout] directly — no Bitmap allocation, no Android graphics.
 * Uses a very large heap budget (1 GB) by default so only width-ceiling and height-cap
 * paths are exercised. Budget-specific tests pass a tight budget explicitly.
 */
class StitcherLayoutTest {

    private val generousBudget = 1_000_000_000L

    @Test
    fun clampsCommonWidthToQualityCeiling_Standard() {
        val layout = Stitcher.computeLayout(
            visualDims = listOf(4000 to 3000, 4000 to 3000),
            quality = StitchQuality.STANDARD,
            heapBudget = generousBudget,
        )
        assertEquals(1800, layout.commonWidth)
        // Scaled heights: (3000 * 1800 / 4000).toInt() = 1350 each.
        assertEquals(listOf(1350, 1350), layout.slotHeights)
    }

    @Test
    fun clampsCommonWidthToQualityCeiling_Low() {
        val layout = Stitcher.computeLayout(
            visualDims = listOf(3000 to 2000),
            quality = StitchQuality.LOW,
            heapBudget = generousBudget,
        )
        assertEquals(1600, layout.commonWidth)
        assertEquals(listOf(1066), layout.slotHeights)
    }

    @Test
    fun highQuality_usesNarrowestSourceWidth() {
        val layout = Stitcher.computeLayout(
            visualDims = listOf(4000 to 3000, 2400 to 1600),
            quality = StitchQuality.HIGH,
            heapBudget = generousBudget,
        )
        // min width across sources = 2400, HIGH has no ceiling.
        assertEquals(2400, layout.commonWidth)
        // Scaled heights: 3000 * 2400/4000 = 1800; 1600 unchanged.
        assertEquals(listOf(1800, 1600), layout.slotHeights)
    }

    @Test
    fun heightCap_triggersScaleWhenTotalExceeds32k() {
        // 20 identical 1800×2000 tiles = 40_000 total height → must scale down to 32_000.
        val dims = List(20) { 1800 to 2000 }
        val layout = Stitcher.computeLayout(dims, StitchQuality.HIGH, generousBudget)
        assertTrue("total height $${layout.totalHeight} should be <= 32k",
            layout.totalHeight <= 32_000)
        // Width scales proportionally: 1800 * (32000 / 40000) = 1440.
        assertEquals(1440, layout.commonWidth)
    }

    @Test
    fun heapBudget_scalesSoPlannedBytesFit() {
        // Ask for a ridiculous amount of memory; budget is 10 MB.
        val layout = Stitcher.computeLayout(
            visualDims = listOf(8000 to 6000, 8000 to 6000),
            quality = StitchQuality.HIGH,
            heapBudget = 10_000_000L,
        )
        assertTrue(
            "plannedBytes ${layout.plannedBytes} should be <= budget 10_000_000",
            layout.plannedBytes <= 10_000_000L,
        )
    }

    @Test
    fun singleSource_preservesAspect() {
        val layout = Stitcher.computeLayout(
            visualDims = listOf(3600 to 2400),
            quality = StitchQuality.STANDARD,
            heapBudget = generousBudget,
        )
        assertEquals(1800, layout.commonWidth)
        assertEquals(listOf(1200), layout.slotHeights)
    }

    @Test
    fun minHeightIsAtLeastOnePixel() {
        // Extremely squat source: height would round to 0 without the coerceAtLeast(1).
        val layout = Stitcher.computeLayout(
            visualDims = listOf(4000 to 1),
            quality = StitchQuality.STANDARD,
            heapBudget = generousBudget,
        )
        assertTrue(layout.slotHeights.all { it >= 1 })
    }
}
