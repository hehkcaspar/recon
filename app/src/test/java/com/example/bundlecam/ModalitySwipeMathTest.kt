package com.example.bundlecam

import com.example.bundlecam.ui.capture.Modality
import com.example.bundlecam.ui.capture.ModalitySwipeMath
import org.junit.Assert.assertEquals
import org.junit.Test

class ModalitySwipeMathTest {

    private val threshold = 80f
    private val velocityThreshold = 400f

    private fun resolve(
        current: Modality,
        dragDp: Float,
        velocityDpPerSec: Float = 0f,
    ): Modality = ModalitySwipeMath.resolveTarget(
        current = current,
        dragDp = dragDp,
        velocityDpPerSecond = velocityDpPerSec,
        thresholdDp = threshold,
        velocityThresholdDpPerSecond = velocityThreshold,
    )

    @Test
    fun subThresholdRelease_keepsCurrent() {
        assertEquals(Modality.PHOTO, resolve(Modality.PHOTO, dragDp = 40f))
        assertEquals(Modality.PHOTO, resolve(Modality.PHOTO, dragDp = -40f))
        assertEquals(Modality.VIDEO, resolve(Modality.VIDEO, dragDp = 10f))
    }

    @Test
    fun photoSwipeRight_pastThreshold_goesToVideo() {
        assertEquals(Modality.VIDEO, resolve(Modality.PHOTO, dragDp = 100f))
    }

    @Test
    fun photoSwipeLeft_pastThreshold_goesToVoice() {
        assertEquals(Modality.VOICE, resolve(Modality.PHOTO, dragDp = -100f))
    }

    @Test
    fun velocityShortcut_commitsBeforeDistanceThreshold() {
        // Small drag, but high velocity in the same direction.
        assertEquals(
            Modality.VIDEO,
            resolve(Modality.PHOTO, dragDp = 20f, velocityDpPerSec = 600f),
        )
    }

    @Test
    fun velocityWithoutMatchingDragDirection_doesNotCommit() {
        // User started to swipe right but flicked back left at release.
        assertEquals(
            Modality.PHOTO,
            resolve(Modality.PHOTO, dragDp = 20f, velocityDpPerSec = -600f),
        )
    }

    @Test
    fun fromVideo_onlySwipeLeftReturnsToPhoto() {
        assertEquals(Modality.PHOTO, resolve(Modality.VIDEO, dragDp = -120f))
        // Rightward from VIDEO has no target (no wrap past the left edge).
        assertEquals(Modality.VIDEO, resolve(Modality.VIDEO, dragDp = 120f))
    }

    @Test
    fun fromVoice_onlySwipeRightReturnsToPhoto() {
        assertEquals(Modality.PHOTO, resolve(Modality.VOICE, dragDp = 120f))
        // Leftward from VOICE has no target.
        assertEquals(Modality.VOICE, resolve(Modality.VOICE, dragDp = -120f))
    }

    @Test
    fun zeroDrag_noVelocity_keepsCurrent() {
        assertEquals(Modality.PHOTO, resolve(Modality.PHOTO, dragDp = 0f))
    }
}
