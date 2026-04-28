package com.example.bundlecam

import com.example.bundlecam.data.storage.BundleModality
import com.example.bundlecam.data.storage.CompletedBundle
import com.example.bundlecam.ui.preview.BundlePreviewUiState
import com.example.bundlecam.ui.preview.BundlePreviewViewModel
import com.example.bundlecam.ui.preview.PendingDelete
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundleSelectionLogicTest {

    private fun stubBundle(id: String) = CompletedBundle(
        id = id,
        modalities = listOf(BundleModality.Subfolder),
        subfolderUri = null,
        stitchUri = null,
        thumbnailUris = emptyList(),
        photoCount = 1,
    )

    @Test
    fun completed_bundle_is_selectable() {
        val state = BundlePreviewUiState(bundles = listOf(stubBundle("a")))
        assertTrue(BundlePreviewViewModel.isSelectable("a", state))
    }

    @Test
    fun pending_delete_bundle_is_not_selectable() {
        val state = BundlePreviewUiState(
            bundles = listOf(stubBundle("a")),
            pendingDeletes = mapOf("a" to PendingDelete(expiresAtMillis = 1L)),
        )
        assertFalse(BundlePreviewViewModel.isSelectable("a", state))
    }

    @Test
    fun processing_bundle_is_not_selectable() {
        val state = BundlePreviewUiState(
            bundles = listOf(stubBundle("a")),
            processingBundleIds = listOf("a"),
        )
        assertFalse(BundlePreviewViewModel.isSelectable("a", state))
    }

    @Test
    fun unknown_bundle_id_is_not_selectable() {
        val state = BundlePreviewUiState(bundles = listOf(stubBundle("a")))
        assertFalse(BundlePreviewViewModel.isSelectable("nonexistent", state))
    }

    @Test
    fun selectionMode_derived_from_selected_set() {
        val empty = BundlePreviewUiState()
        assertFalse(empty.selectionMode)
        val withOne = BundlePreviewUiState(selectedBundleIds = setOf("a"))
        assertTrue(withOne.selectionMode)
    }
}
