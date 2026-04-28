package com.example.bundlecam

import com.example.bundlecam.ui.preview.BundleRowSwipe
import com.example.bundlecam.ui.preview.BundleRowSwipe.SwipeAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function coverage for the [BundleRowSwipe] gesture-decision core. The Compose
 * row widget is not exercised here — only the math of "given current offset + release
 * velocity + selected state, what should fire and where should the row settle."
 */
class BundleRowSwipeTest {

    private val revealWidthPx = 240f
    private val deleteTriggerPx = -432f // ≈ 40% of a 1080-px row
    private val selectMidpointPx = 120f // 50% of revealWidth

    private fun release(
        offsetX: Float,
        velocity: Float = 0f,
        selected: Boolean = false,
    ) = BundleRowSwipe.computeRelease(
        offsetX = offsetX,
        velocity = velocity,
        selected = selected,
        revealWidthPx = revealWidthPx,
        deleteTriggerPx = deleteTriggerPx,
        selectMidpointPx = selectMidpointPx,
    )

    // --- select branches -----------------------------------------------------

    @Test
    fun unselected_drag_past_midpoint_commits_select() {
        val r = release(offsetX = 130f, selected = false)
        assertEquals(revealWidthPx, r.targetOffset)
        assertEquals(SwipeAction.ToggleSelection, r.action)
    }

    @Test
    fun unselected_drag_below_midpoint_springs_back() {
        val r = release(offsetX = 100f, selected = false)
        assertEquals(0f, r.targetOffset)
        assertEquals(SwipeAction.None, r.action)
    }

    @Test
    fun unselected_fast_right_flick_commits_select_even_below_midpoint() {
        val r = release(offsetX = 30f, velocity = 1500f, selected = false)
        assertEquals(revealWidthPx, r.targetOffset)
        assertEquals(SwipeAction.ToggleSelection, r.action)
    }

    @Test
    fun unselected_at_zero_offset_does_not_select_on_fast_right_flick() {
        // No positive position at all — velocity alone shouldn't conjure a selection
        // gesture out of a non-existent pull. The `offsetX > 0f` guard enforces this.
        val r = release(offsetX = 0f, velocity = 5000f, selected = false)
        assertEquals(0f, r.targetOffset)
        assertEquals(SwipeAction.None, r.action)
    }

    // --- deselect branches ---------------------------------------------------

    @Test
    fun selected_drag_back_to_below_midpoint_commits_deselect() {
        val r = release(offsetX = 100f, selected = true)
        assertEquals(0f, r.targetOffset)
        assertEquals(SwipeAction.ToggleSelection, r.action)
    }

    @Test
    fun selected_release_above_midpoint_springs_to_reveal() {
        val r = release(offsetX = 200f, selected = true)
        assertEquals(revealWidthPx, r.targetOffset)
        assertEquals(SwipeAction.None, r.action)
    }

    @Test
    fun selected_fast_left_flick_commits_deselect_even_above_midpoint() {
        val r = release(offsetX = 200f, velocity = -1500f, selected = true)
        assertEquals(0f, r.targetOffset)
        assertEquals(SwipeAction.ToggleSelection, r.action)
    }

    @Test
    fun selected_at_revealWidth_with_fast_left_flick_does_not_deselect() {
        // The fast-left-flick override only applies when offsetX < revealWidth — at
        // exactly the rest position, a flick alone shouldn't trigger anything (the
        // user hasn't physically committed any motion yet).
        val r = release(offsetX = revealWidthPx, velocity = -5000f, selected = true)
        assertEquals(revealWidthPx, r.targetOffset)
        assertEquals(SwipeAction.None, r.action)
    }

    // --- delete branches -----------------------------------------------------

    @Test
    fun unselected_drag_past_delete_threshold_commits_delete() {
        val r = release(offsetX = -500f, selected = false)
        assertEquals(deleteTriggerPx, r.targetOffset)
        assertEquals(SwipeAction.RequestDelete, r.action)
    }

    @Test
    fun unselected_fast_left_flick_from_negative_commits_delete() {
        val r = release(offsetX = -50f, velocity = -1500f, selected = false)
        assertEquals(deleteTriggerPx, r.targetOffset)
        assertEquals(SwipeAction.RequestDelete, r.action)
    }

    @Test
    fun selected_at_negative_offset_is_unreachable_so_branch_inert() {
        // Selected rows are clamped to [0, revealWidth] so this configuration shouldn't
        // happen in practice, but if it does, no delete from a selected row.
        val r = release(offsetX = -500f, selected = true)
        assertEquals(SwipeAction.ToggleSelection, r.action) // matches the deselect branch
        assertEquals(0f, r.targetOffset)
    }

    // --- spring-back ---------------------------------------------------------

    @Test
    fun unselected_at_zero_with_no_velocity_settles_at_zero() {
        val r = release(offsetX = 0f, selected = false)
        assertEquals(0f, r.targetOffset)
        assertEquals(SwipeAction.None, r.action)
    }

    @Test
    fun selected_at_revealWidth_with_no_velocity_settles_there() {
        val r = release(offsetX = revealWidthPx, selected = true)
        assertEquals(revealWidthPx, r.targetOffset)
        assertEquals(SwipeAction.None, r.action)
    }

    // --- clampOffset ---------------------------------------------------------

    @Test
    fun clamp_unselected_caps_at_revealWidth_on_right() {
        assertEquals(revealWidthPx, BundleRowSwipe.clampOffset(500f, selected = false, revealWidthPx))
    }

    @Test
    fun clamp_unselected_allows_negative_for_delete_reveal() {
        assertEquals(-500f, BundleRowSwipe.clampOffset(-500f, selected = false, revealWidthPx))
    }

    @Test
    fun clamp_selected_caps_in_zero_to_reveal_range() {
        assertEquals(0f, BundleRowSwipe.clampOffset(-500f, selected = true, revealWidthPx))
        assertEquals(revealWidthPx, BundleRowSwipe.clampOffset(999f, selected = true, revealWidthPx))
        assertEquals(120f, BundleRowSwipe.clampOffset(120f, selected = true, revealWidthPx))
    }

    // --- wouldFireOnRelease (haptic threshold) -------------------------------

    @Test
    fun wouldFire_unselected_crosses_into_select_zone() {
        assertEquals(false, fire(offsetX = 100f, selected = false))
        assertEquals(true, fire(offsetX = 130f, selected = false))
    }

    @Test
    fun wouldFire_unselected_crosses_into_delete_zone() {
        assertEquals(false, fire(offsetX = -300f, selected = false))
        assertEquals(true, fire(offsetX = -500f, selected = false))
    }

    @Test
    fun wouldFire_selected_crosses_below_midpoint() {
        assertEquals(false, fire(offsetX = 200f, selected = true))
        assertEquals(true, fire(offsetX = 100f, selected = true))
    }

    private fun fire(offsetX: Float, selected: Boolean): Boolean =
        BundleRowSwipe.wouldFireOnRelease(
            offsetX = offsetX,
            selected = selected,
            deleteTriggerPx = deleteTriggerPx,
            selectMidpointPx = selectMidpointPx,
        )
}
