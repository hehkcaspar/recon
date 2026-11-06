package com.example.bundlecam

import com.example.bundlecam.ui.capture.EdgeAction
import com.example.bundlecam.ui.capture.HandleEndpoint
import com.example.bundlecam.ui.capture.QueueAxis
import com.example.bundlecam.ui.capture.QueueGestureMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueGestureArbitrationTest {

    private val gate96 = 96f
    private val deleteGate24 = 24f
    private val maxVisual32 = 32f
    private val dividerGate24 = 24f

    @Test
    fun horizontal_axis_left_handle_rightward_commits_and_right_handle_leftward_discards() {
        // Left handle (Start) swiping right >= 96dp commits
        assertEquals(
            EdgeAction.Commit,
            QueueGestureMath.resolveEdgeRelease(
                axis = QueueAxis.Horizontal,
                endpoint = HandleEndpoint.Start,
                netPrimaryDeltaPx = 96f,
                gatePx = gate96,
                isCancelled = false,
                isLocked = false,
            ),
        )
        // Below 96dp restores
        assertEquals(
            EdgeAction.None,
            QueueGestureMath.resolveEdgeRelease(
                axis = QueueAxis.Horizontal,
                endpoint = HandleEndpoint.Start,
                netPrimaryDeltaPx = 95.5f,
                gatePx = gate96,
                isCancelled = false,
                isLocked = false,
            ),
        )
        // Right handle (End) swiping left <= -96dp discards
        assertEquals(
            EdgeAction.Discard,
            QueueGestureMath.resolveEdgeRelease(
                axis = QueueAxis.Horizontal,
                endpoint = HandleEndpoint.End,
                netPrimaryDeltaPx = -96f,
                gatePx = gate96,
                isCancelled = false,
                isLocked = false,
            ),
        )
        // Wrong direction on right handle does nothing
        assertEquals(
            EdgeAction.None,
            QueueGestureMath.resolveEdgeRelease(
                axis = QueueAxis.Horizontal,
                endpoint = HandleEndpoint.End,
                netPrimaryDeltaPx = 120f,
                gatePx = gate96,
                isCancelled = false,
                isLocked = false,
            ),
        )
    }

    @Test
    fun vertical_axis_bottom_handle_upward_commits_and_top_handle_downward_discards() {
        // Bottom handle (End) swiping UP (-dy <= -96dp) commits
        assertEquals(
            EdgeAction.Commit,
            QueueGestureMath.resolveEdgeRelease(
                axis = QueueAxis.Vertical,
                endpoint = HandleEndpoint.End,
                netPrimaryDeltaPx = -96f,
                gatePx = gate96,
                isCancelled = false,
                isLocked = false,
            ),
        )
        // Top handle (Start) swiping DOWN (+dy >= 96dp) discards
        assertEquals(
            EdgeAction.Discard,
            QueueGestureMath.resolveEdgeRelease(
                axis = QueueAxis.Vertical,
                endpoint = HandleEndpoint.Start,
                netPrimaryDeltaPx = 96f,
                gatePx = gate96,
                isCancelled = false,
                isLocked = false,
            ),
        )
        // Reversal below 96dp on vertical queue restores
        assertEquals(
            EdgeAction.None,
            QueueGestureMath.resolveEdgeRelease(
                axis = QueueAxis.Vertical,
                endpoint = HandleEndpoint.End,
                netPrimaryDeltaPx = -70f,
                gatePx = gate96,
                isCancelled = false,
                isLocked = false,
            ),
        )
    }

    @Test
    fun cancellation_or_recording_lock_never_commits_or_discards_even_past_96dp() {
        for (axis in QueueAxis.entries) {
            for (endpoint in HandleEndpoint.entries) {
                val delta = if (endpoint == HandleEndpoint.Start) 150f else -150f
                assertEquals(
                    EdgeAction.None,
                    QueueGestureMath.resolveEdgeRelease(
                        axis = axis,
                        endpoint = endpoint,
                        netPrimaryDeltaPx = delta,
                        gatePx = gate96,
                        isCancelled = true,
                        isLocked = false,
                    ),
                )
                assertEquals(
                    EdgeAction.None,
                    QueueGestureMath.resolveEdgeRelease(
                        axis = axis,
                        endpoint = endpoint,
                        netPrimaryDeltaPx = delta,
                        gatePx = gate96,
                        isCancelled = false,
                        isLocked = true,
                    ),
                )
            }
        }
    }

    @Test
    fun delete_requires_strictly_greater_than_24dp_and_clamps_visual_to_32dp() {
        assertFalse(QueueGestureMath.shouldCommitDelete(24f, deleteGate24, isCancelled = false, isLocked = false))
        assertTrue(QueueGestureMath.shouldCommitDelete(24.1f, deleteGate24, isCancelled = false, isLocked = false))
        assertFalse(QueueGestureMath.shouldCommitDelete(40f, deleteGate24, isCancelled = true, isLocked = false))
        assertFalse(QueueGestureMath.shouldCommitDelete(40f, deleteGate24, isCancelled = false, isLocked = true))

        // Visual clamp and alpha curve per COMPONENT-CONTRACT.md:
        // half specimen at 12dp -> alpha 0.775; armed at 32dp -> alpha 0.4
        assertEquals(12f, QueueGestureMath.clampedDeleteVisualPx(12f, maxVisual32), 0.001f)
        assertEquals(0.775f, QueueGestureMath.deleteItemAlpha(12f, maxVisual32), 0.001f)
        assertEquals(32f, QueueGestureMath.clampedDeleteVisualPx(48f, maxVisual32), 0.001f)
        assertEquals(0.4f, QueueGestureMath.deleteItemAlpha(32f, maxVisual32), 0.001f)
    }

    @Test
    fun divider_toggle_requires_24dp_in_applicable_direction_and_respects_cancel_and_lock() {
        assertTrue(QueueGestureMath.shouldCommitDividerToggle(24f, hasDivider = false, dividerGate24, isCancelled = false, isLocked = false))
        assertFalse(QueueGestureMath.shouldCommitDividerToggle(23.9f, hasDivider = false, dividerGate24, isCancelled = false, isLocked = false))
        assertTrue(QueueGestureMath.shouldCommitDividerToggle(-24f, hasDivider = true, dividerGate24, isCancelled = false, isLocked = false))
        assertFalse(QueueGestureMath.shouldCommitDividerToggle(24f, hasDivider = true, dividerGate24, isCancelled = false, isLocked = false))
        assertFalse(QueueGestureMath.shouldCommitDividerToggle(30f, hasDivider = false, dividerGate24, isCancelled = true, isLocked = false))
        assertFalse(QueueGestureMath.shouldCommitDividerToggle(30f, hasDivider = false, dividerGate24, isCancelled = false, isLocked = true))
    }

    @Test
    fun reorder_uses_actual_measured_slot_centers_across_expanded_split_gaps() {
        // Full split reference from COMPONENT-CONTRACT.md:
        // thumbnails at x50, 112, 192, 254 (56dp wide -> centers at 78, 140, 220, 282)
        // Notice gap between slot 1 and slot 2 is 24dp expanded split (center delta = 80dp, not 62dp).
        val centers = floatArrayOf(78f, 140f, 220f, 282f)
        assertEquals(
            2,
            QueueGestureMath.computeReorderDestinationIndex(
                currentIndex = 1,
                dragPrimaryPx = 75f,
                slotCentersPx = centers,
                fallbackPitchPx = 62f,
                queueSize = 4,
            ),
        )
        assertEquals(
            0,
            QueueGestureMath.computeReorderDestinationIndex(
                currentIndex = 2,
                dragPrimaryPx = -135f,
                slotCentersPx = centers,
                fallbackPitchPx = 62f,
                queueSize = 4,
            ),
        )
    }

    @Test
    fun delete_glow_bulge_peaks_at_hotspot_scales_with_progress_and_tapers_at_endpoints() {
        val laneLen = 360f
        val mediaPx = 56f
        val featherPx = 24f
        val hotspot = 180f

        // Peak at hotspot is 1.0f
        assertEquals(
            1f,
            QueueGestureMath.deleteGlowBulgeFactor(hotspot, hotspot, laneLen, mediaPx, featherPx),
            0.001f,
        )
        // At +/- 28dp (thumbnail edges), bulge remains prominent (> 0.78f)
        val atThumbEdge = QueueGestureMath.deleteGlowBulgeFactor(
            hotspot + 28f,
            hotspot,
            laneLen,
            mediaPx,
            featherPx,
        )
        assertTrue("Expected > 0.78f at thumbnail edge, got $atThumbEdge", atThumbEdge > 0.78f)

        // Endpoints (0f and laneLen) always taper cleanly to 0f even when dragging the first item (x = 84f)
        assertEquals(
            0f,
            QueueGestureMath.deleteGlowBulgeFactor(0f, 84f, laneLen, mediaPx, featherPx),
            0.0001f,
        )
        assertEquals(
            0f,
            QueueGestureMath.deleteGlowBulgeFactor(laneLen, laneLen - 84f, laneLen, mediaPx, featherPx),
            0.0001f,
        )

        // Peak extent scales across the 24dp delete corridor with progress
        assertEquals(0f, QueueGestureMath.deleteGlowPeakExtentPx(0f, 24f), 0.001f)
        assertEquals(16.2f, QueueGestureMath.deleteGlowPeakExtentPx(0.5f, 24f), 0.001f)
        assertEquals(24f, QueueGestureMath.deleteGlowPeakExtentPx(1f, 24f), 0.001f)
    }
}
