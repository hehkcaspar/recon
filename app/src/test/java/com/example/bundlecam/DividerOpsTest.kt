package com.example.bundlecam

import com.example.bundlecam.ui.capture.DividerOps
import org.junit.Assert.assertEquals
import org.junit.Test

class DividerOpsTest {

    @Test
    fun partition_emptyQueue_returnsEmpty() {
        assertEquals(emptyList<List<Int>>(), DividerOps.partitionByDividers(emptyList<Int>(), emptySet()))
        assertEquals(emptyList<List<Int>>(), DividerOps.partitionByDividers(emptyList<Int>(), setOf(0, 1)))
    }

    @Test
    fun partition_noDividers_returnsSingleSegment() {
        val items = listOf("a", "b", "c")
        assertEquals(listOf(items), DividerOps.partitionByDividers(items, emptySet()))
    }

    @Test
    fun partition_dividerAtStart_splitsFirstItem() {
        val items = listOf("a", "b", "c", "d")
        // divider at index 0 means "cut after item 0"
        assertEquals(
            listOf(listOf("a"), listOf("b", "c", "d")),
            DividerOps.partitionByDividers(items, setOf(0)),
        )
    }

    @Test
    fun partition_dividerAtLastValidIndex_splitsLastItem() {
        val items = listOf("a", "b", "c")
        assertEquals(
            listOf(listOf("a", "b"), listOf("c")),
            DividerOps.partitionByDividers(items, setOf(1)),
        )
    }

    @Test
    fun partition_ignoresOutOfRangeDividers() {
        val items = listOf("a", "b", "c")
        // size - 1 = 2 is NOT a valid divider (would leave an empty right segment).
        // Also negative.
        assertEquals(
            listOf(items),
            DividerOps.partitionByDividers(items, setOf(-1, 2, 5)),
        )
    }

    @Test
    fun partition_multipleDividers_areOrderedAndDeduped() {
        val items = (1..5).toList()
        // Cuts at 0, 2 → segments [1], [2,3], [4,5]
        assertEquals(
            listOf(listOf(1), listOf(2, 3), listOf(4, 5)),
            DividerOps.partitionByDividers(items, setOf(2, 0)),
        )
    }

    @Test
    fun partition_singleItem_noDividersPossible() {
        assertEquals(listOf(listOf("a")), DividerOps.partitionByDividers(listOf("a"), setOf(0, 1)))
    }

    @Test
    fun remap_emptyDividers_returnsEmpty() {
        assertEquals(emptySet<Int>(), DividerOps.remapDividersAfterDelete(emptySet(), 0, 5))
    }

    @Test
    fun remap_newSizeBelowTwo_dropsAllDividers() {
        assertEquals(emptySet<Int>(), DividerOps.remapDividersAfterDelete(setOf(0), 1, 1))
        assertEquals(emptySet<Int>(), DividerOps.remapDividersAfterDelete(setOf(0), 0, 0))
    }

    @Test
    fun remap_dividerBeforeRemoved_staysAtSameIndex() {
        // [a|b,c,d], remove c (index 2). After: [a|b,d] → divider still at 0.
        assertEquals(setOf(0), DividerOps.remapDividersAfterDelete(setOf(0), 2, 3))
    }

    @Test
    fun remap_dividerAfterRemoved_shiftsLeftByOne() {
        // [a,b,c|d,e], remove b (index 1). After: [a,c|d,e] → divider now at 1.
        assertEquals(setOf(1), DividerOps.remapDividersAfterDelete(setOf(2), 1, 4))
    }

    @Test
    fun remap_dividerAtRemoved_shiftsLeftByOne() {
        // Divider at index d meant "cut after item d". If d is removed, divider "at d" falls
        // through the deletion — the spec treats the shifted value as d-1.
        assertEquals(setOf(0), DividerOps.remapDividersAfterDelete(setOf(1), 1, 3))
    }

    @Test
    fun remap_dropsOutOfRangeAfterShift() {
        // [a,b|c,d], remove d (index 3). After: [a,b|c] → divider still valid at 1.
        assertEquals(setOf(1), DividerOps.remapDividersAfterDelete(setOf(1), 3, 3))

        // [a,b,c|d], remove d. After: [a,b,c] → no more space for a divider at end.
        // Divider at 2 would shift to 2 (index < removedIndex 3), newSize - 1 = 2 → out of range (2..<2 is empty).
        assertEquals(emptySet<Int>(), DividerOps.remapDividersAfterDelete(setOf(2), 3, 3))
    }

    @Test
    fun remap_multipleDividers_collapseIfCollide() {
        // [a|b|c], remove b (index 1). The two dividers at 0 and 1 both reduce to 0 → deduped.
        assertEquals(setOf(0), DividerOps.remapDividersAfterDelete(setOf(0, 1), 1, 2))
    }
}
