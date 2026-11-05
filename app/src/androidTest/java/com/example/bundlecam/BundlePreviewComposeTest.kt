package com.example.bundlecam

import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.example.bundlecam.data.settings.SettingsState
import com.example.bundlecam.data.storage.BundleModality
import com.example.bundlecam.data.storage.CompletedBundle
import com.example.bundlecam.ui.preview.BundlePreviewContent
import com.example.bundlecam.ui.preview.BundlePreviewUiState
import com.example.bundlecam.ui.preview.LoadState
import com.example.bundlecam.ui.preview.PendingDelete
import com.example.bundlecam.ui.theme.ReconTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BundlePreviewComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleBundle(id: String) = CompletedBundle(
        id = id,
        modalities = listOf(BundleModality.Subfolder),
        subfolderUri = Uri.parse("content://com.example.bundlecam/bundles/$id"),
        stitchUri = null,
        thumbnailUris = emptyList(),
        photoCount = 3,
        videoCount = 1,
        voiceCount = 1,
    )

    @Test
    fun right_swipe_selects_with_green_check_reveal_and_left_swipe_deletes_with_undo() {
        val bundle1 = sampleBundle("2026-11-04-s-0001")
        val bundle2 = sampleBundle("2026-11-04-s-0002")
        var uiState by mutableStateOf(
            BundlePreviewUiState(
                bundles = listOf(bundle1, bundle2),
                loadState = LoadState.Loaded,
                processingBundleIds = listOf("2026-11-04-s-0003"),
            ),
        )

        composeRule.setContent {
            ReconTheme {
                BundlePreviewContent(
                    state = uiState,
                    settings = SettingsState(
                        rootUri = Uri.parse("content://com.example.bundlecam/tree/root"),
                        deleteConfirmEnabled = false,
                        deleteDelaySeconds = 5,
                    ),
                    canSendSelected = uiState.selectedBundleIds.isNotEmpty(),
                    onBack = {},
                    onOpenFolder = {},
                    onClearSelection = { uiState = uiState.copy(selectedBundleIds = emptySet()) },
                    onSendSelected = {},
                    onRequestThumbnail = {},
                    onConfirmDelete = { id ->
                        uiState = uiState.copy(
                            selectedBundleIds = uiState.selectedBundleIds - id,
                            pendingDeletes = uiState.pendingDeletes + (id to PendingDelete(System.currentTimeMillis() + 5000L)),
                        )
                    },
                    onUndo = { id ->
                        uiState = uiState.copy(pendingDeletes = uiState.pendingDeletes - id)
                    },
                    onToggleSelection = { id ->
                        val next = if (id in uiState.selectedBundleIds) {
                            uiState.selectedBundleIds - id
                        } else {
                            uiState.selectedBundleIds + id
                        }
                        uiState = uiState.copy(selectedBundleIds = next)
                    },
                    onClearError = {},
                    modifier = Modifier.size(width = 360.dp, height = 800.dp),
                )
            }
        }

        // Processing row is visible and disabled
        composeRule.onNodeWithContentDescription("2026-11-04-s-0003, Processing")
            .assertIsDisplayed()
            .assertIsNotEnabled()

        // 1. Sub-threshold right drag (< 40dp) springs back without selecting
        composeRule.onNodeWithText("2026-11-04-s-0001").performTouchInput {
            down(center)
            moveBy(Offset(20.dp.toPx(), 0f))
            up()
        }
        composeRule.waitForIdle()
        assertTrue(uiState.selectedBundleIds.isEmpty())

        // 2. Right swipe past threshold selects bundle1 and reveals green check
        composeRule.onNodeWithText("2026-11-04-s-0001").performTouchInput {
            swipe(
                start = Offset(60.dp.toPx(), centerY),
                end = Offset(220.dp.toPx(), centerY),
                durationMillis = 200,
            )
        }
        composeRule.waitForIdle()
        assertEquals(setOf(bundle1.id), uiState.selectedBundleIds)
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Deselect ${bundle1.id}").assertIsDisplayed()

        // 3. Tapping the green check reveal deselects bundle1
        composeRule.onNodeWithContentDescription("Deselect ${bundle1.id}").performClick()
        composeRule.waitForIdle()
        assertTrue(uiState.selectedBundleIds.isEmpty())

        // 4. Left swipe on unselected bundle2 requests deletion -> enters PendingDeleteRow
        composeRule.onNodeWithText("2026-11-04-s-0002").performTouchInput {
            swipe(
                start = Offset(240.dp.toPx(), centerY),
                end = Offset(40.dp.toPx(), centerY),
                durationMillis = 200,
            )
        }
        composeRule.waitForIdle()
        assertTrue(bundle2.id in uiState.pendingDeletes)
        composeRule.onNodeWithContentDescription("Undo deletion of ${bundle2.id}")
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()
        assertFalse(bundle2.id in uiState.pendingDeletes)
    }

    @Test
    fun more_menu_and_custom_accessibility_actions_provide_direct_equivalents() {
        val bundle1 = sampleBundle("2026-11-04-s-0001")
        var uiState by mutableStateOf(
            BundlePreviewUiState(
                bundles = listOf(bundle1),
                loadState = LoadState.Loaded,
            ),
        )

        composeRule.setContent {
            ReconTheme {
                BundlePreviewContent(
                    state = uiState,
                    settings = SettingsState(
                        rootUri = Uri.parse("content://com.example.bundlecam/tree/root"),
                        deleteConfirmEnabled = true,
                        deleteDelaySeconds = 5,
                    ),
                    canSendSelected = uiState.selectedBundleIds.isNotEmpty(),
                    onBack = {},
                    onOpenFolder = {},
                    onClearSelection = { uiState = uiState.copy(selectedBundleIds = emptySet()) },
                    onSendSelected = {},
                    onRequestThumbnail = {},
                    onConfirmDelete = { id ->
                        uiState = uiState.copy(
                            pendingDeletes = uiState.pendingDeletes + (id to PendingDelete(System.currentTimeMillis() + 5000L)),
                        )
                    },
                    onUndo = { id ->
                        uiState = uiState.copy(pendingDeletes = uiState.pendingDeletes - id)
                    },
                    onToggleSelection = { id ->
                        val next = if (id in uiState.selectedBundleIds) {
                            uiState.selectedBundleIds - id
                        } else {
                            uiState.selectedBundleIds + id
                        }
                        uiState = uiState.copy(selectedBundleIds = next)
                    },
                    onClearError = {},
                    modifier = Modifier.size(width = 640.dp, height = 800.dp),
                )
            }
        }

        // 1. Open trailing More menu on unselected row -> Select and Delete are shown
        composeRule.onNodeWithContentDescription("Actions for ${bundle1.id}")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithContentDescription("Select ${bundle1.id}").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete ${bundle1.id}").assertIsDisplayed()

        // Click Select from More menu
        composeRule.onNodeWithContentDescription("Select ${bundle1.id}").performClick()
        composeRule.waitForIdle()
        assertEquals(setOf(bundle1.id), uiState.selectedBundleIds)

        // 2. Open trailing More menu on selected row -> only Deselect is shown
        composeRule.onNodeWithContentDescription("Actions for ${bundle1.id}").performClick()
        composeRule.onNodeWithContentDescription("Deselect ${bundle1.id}").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Deselect ${bundle1.id}").performClick()
        composeRule.waitForIdle()
        assertTrue(uiState.selectedBundleIds.isEmpty())

        // 3. Execute TalkBack custom accessibility action "Select 2026-11-04-s-0001"
        val rowMatcher = hasContentDescription("${bundle1.id}, 3 photos, 1 video, 1 voice note")
        val node = composeRule.onNode(rowMatcher).fetchSemanticsNode()
        val selectAction = node.config[SemanticsActions.CustomActions]
            .first { it.label == "Select ${bundle1.id}" }
        composeRule.runOnIdle {
            selectAction.action()
        }
        assertEquals(setOf(bundle1.id), uiState.selectedBundleIds)
    }
}
