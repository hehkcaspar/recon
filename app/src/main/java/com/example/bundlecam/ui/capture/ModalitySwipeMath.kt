package com.example.bundlecam.ui.capture

/**
 * Pure math for the viewfinder swipe carousel. The modality pill reads left-to-right
 * `VIDEO · PHOTO · VOICE` — photo is the hub, video one step left, voice one step
 * right. Swipe right from PHOTO → VIDEO; swipe left from PHOTO → VOICE; from VIDEO
 * only left returns to PHOTO; from VOICE only right returns to PHOTO. No wrap.
 *
 * Kept separate from the CameraX / Compose code so `SlabSwipeMathTest` can exercise
 * it without pulling Android dependencies.
 */
internal object ModalitySwipeMath {

    /**
     * Decide the target modality for a swipe release from [current]. [dragDp] and
     * [velocityDpPerSecond] use dp/density-independent units to keep tests
     * deterministic across devices.
     *
     * Commits when either the drag distance exceeds [thresholdDp] or the release
     * velocity exceeds [velocityThresholdDpPerSecond] *in the same direction* as the
     * drag. Sub-threshold releases keep [current].
     *
     * Sign convention: positive [dragDp] = rightward = swipe-right = toward VIDEO
     * when starting from PHOTO. Negative = leftward = toward VOICE.
     */
    fun resolveTarget(
        current: Modality,
        dragDp: Float,
        velocityDpPerSecond: Float,
        thresholdDp: Float,
        velocityThresholdDpPerSecond: Float,
    ): Modality {
        val crossedDistance = kotlin.math.abs(dragDp) >= thresholdDp
        val crossedVelocity = kotlin.math.abs(velocityDpPerSecond) >= velocityThresholdDpPerSecond &&
            // Velocity must agree with drag direction; a flick *back* in the final
            // moment shouldn't commit the original direction.
            kotlin.math.sign(velocityDpPerSecond) == kotlin.math.sign(dragDp)
        if (!crossedDistance && !crossedVelocity) return current
        return when (current) {
            Modality.PHOTO -> when {
                dragDp > 0f -> Modality.VIDEO
                dragDp < 0f -> Modality.VOICE
                else -> current
            }
            // From VIDEO, only a leftward release returns to PHOTO. Rightward does nothing
            // because there's no modality further right than PHOTO-as-hub's VIDEO neighbor.
            Modality.VIDEO -> if (dragDp < 0f) Modality.PHOTO else current
            Modality.VOICE -> if (dragDp > 0f) Modality.PHOTO else current
        }
    }
}
