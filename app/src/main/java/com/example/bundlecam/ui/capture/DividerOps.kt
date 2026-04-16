package com.example.bundlecam.ui.capture

internal object DividerOps {
    fun <T> partitionByDividers(items: List<T>, dividers: Set<Int>): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val cuts = dividers.filter { it in 0 until items.size - 1 }.sorted()
        if (cuts.isEmpty()) return listOf(items)
        val out = mutableListOf<List<T>>()
        var start = 0
        for (cut in cuts) {
            out.add(items.subList(start, cut + 1))
            start = cut + 1
        }
        out.add(items.subList(start, items.size))
        return out
    }

    fun remapDividersAfterDelete(
        dividers: Set<Int>,
        removedIndex: Int,
        newSize: Int,
    ): Set<Int> {
        if (dividers.isEmpty() || newSize < 2) return emptySet()
        val result = mutableSetOf<Int>()
        for (d in dividers) {
            val shifted = if (d < removedIndex) d else d - 1
            if (shifted in 0 until newSize - 1) result.add(shifted)
        }
        return result
    }
}
