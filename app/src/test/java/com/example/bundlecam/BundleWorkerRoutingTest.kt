package com.example.bundlecam

import com.example.bundlecam.pipeline.BundleWorker
import com.example.bundlecam.pipeline.PendingBundle
import com.example.bundlecam.pipeline.PendingPhoto
import com.example.bundlecam.pipeline.PendingVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleWorkerRoutingTest {

    private fun bundle(items: List<com.example.bundlecam.pipeline.PendingItem>) = PendingBundle(
        bundleId = "test-bundle",
        rootUriString = "content://stub",
        stitchQuality = "STANDARD",
        sessionId = "sess-1",
        orderedItems = items,
        capturedAt = 0L,
    )

    private fun photo(path: String, rotation: Int = 0) = PendingPhoto(path, rotation)
    private fun video(path: String, rotation: Int = 0, duration: Long = 1000L) =
        PendingVideo(path, rotation, duration)

    @Test
    fun photoOnlyBundle_allPhotosInOrder_videosEmpty() {
        val b = bundle(listOf(photo("/a.jpg"), photo("/b.jpg"), photo("/c.jpg")))
        val plan = BundleWorker.planRouting(b)
        assertEquals(3, plan.photos.size)
        assertTrue(plan.videos.isEmpty())
        assertEquals(listOf(1, 2, 3), plan.photos.map { it.globalIndex })
    }

    @Test
    fun videoOnlyBundle_allVideosInOrder_photosEmpty() {
        val b = bundle(listOf(video("/v1.mp4"), video("/v2.mp4")))
        val plan = BundleWorker.planRouting(b)
        assertTrue(plan.photos.isEmpty())
        assertEquals(2, plan.videos.size)
        assertEquals(listOf(1, 2), plan.videos.map { it.globalIndex })
    }

    @Test
    fun mixedBundle_globalIndexMatchesListPosition() {
        // [P, V, P, V, P] → photos at global 1, 3, 5; videos at 2, 4
        val b = bundle(
            listOf(
                photo("/p1.jpg"),
                video("/v1.mp4"),
                photo("/p2.jpg"),
                video("/v2.mp4"),
                photo("/p3.jpg"),
            ),
        )
        val plan = BundleWorker.planRouting(b)
        assertEquals(3, plan.photos.size)
        assertEquals(2, plan.videos.size)
        assertEquals(listOf(1, 3, 5), plan.photos.map { it.globalIndex })
        assertEquals(listOf(2, 4), plan.videos.map { it.globalIndex })
    }

    @Test
    fun mixedBundle_globalIndexIsDenseAcrossModalities() {
        // Dense: every item gets the *next* index. Sparseness per modality is a
        // consequence, not a per-modality rule.
        val b = bundle(
            listOf(
                photo("/p.jpg"),
                video("/v.mp4"),
                photo("/p2.jpg"),
                video("/v2.mp4"),
            ),
        )
        val plan = BundleWorker.planRouting(b)
        val allIndices = (plan.photos.map { it.globalIndex } + plan.videos.map { it.globalIndex }).sorted()
        assertEquals(listOf(1, 2, 3, 4), allIndices)
    }

    @Test
    fun emptyBundle_returnsEmptyPlan() {
        val plan = BundleWorker.planRouting(bundle(emptyList()))
        assertTrue(plan.photos.isEmpty())
        assertTrue(plan.videos.isEmpty())
    }

    @Test
    fun preservedMetadata_rotationAndDuration_flowThrough() {
        val b = bundle(
            listOf(
                photo("/p.jpg", rotation = 270),
                video("/v.mp4", rotation = 90, duration = 5432L),
            ),
        )
        val plan = BundleWorker.planRouting(b)
        assertEquals(270, plan.photos[0].item.rotationDegrees)
        assertEquals(90, plan.videos[0].item.rotationDegrees)
        assertEquals(5432L, plan.videos[0].item.durationMs)
    }
}
