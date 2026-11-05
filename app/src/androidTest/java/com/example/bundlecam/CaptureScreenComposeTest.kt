package com.example.bundlecam

import android.graphics.Bitmap
import android.net.Uri
import android.view.Surface
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.bundlecam.data.camera.CameraCapability
import com.example.bundlecam.data.camera.CameraReadiness
import com.example.bundlecam.data.camera.CameraSessionState
import com.example.bundlecam.data.camera.FlashMode
import com.example.bundlecam.data.camera.LensFacing
import com.example.bundlecam.data.camera.ZoomInfo
import com.example.bundlecam.data.settings.SettingsState
import com.example.bundlecam.data.settings.V1_TUTORIAL_STEP_IDS
import com.example.bundlecam.ui.capture.BusyState
import com.example.bundlecam.ui.capture.CaptureScreenShell
import com.example.bundlecam.ui.capture.CaptureUiState
import com.example.bundlecam.ui.capture.Modality
import com.example.bundlecam.ui.capture.StagedItem
import com.example.bundlecam.ui.capture.window.SurfaceRole
import com.example.bundlecam.ui.capture.window.WindowObservation
import com.example.bundlecam.ui.theme.ReconTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class CaptureScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val seenAllTutorialSteps = V1_TUTORIAL_STEP_IDS + "modality"

    private fun testItems(): List<StagedItem> {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).asImageBitmap()
        return listOf(
            StagedItem.Photo(
                id = "item-1",
                localFile = File("/tmp/item-1.jpg"),
                thumbnail = bmp,
                capturedAt = 1000L,
                rotationDegrees = 0,
            ),
            StagedItem.Video(
                id = "item-2",
                localFile = File("/tmp/item-2.mp4"),
                thumbnail = bmp,
                capturedAt = 2000L,
                rotationDegrees = 0,
                durationMs = 4000L,
            ),
            StagedItem.Voice(
                id = "item-3",
                localFile = File("/tmp/item-3.m4a"),
                thumbnail = bmp,
                capturedAt = 3000L,
                durationMs = 6000L,
            ),
        )
    }

    @Test
    fun designC_upright_renders_vertical_queue_and_executes_upward_commit_and_downward_discard() {
        var committed = 0
        var discarded = 0
        val deletedIds = mutableListOf<String>()
        val items = testItems()

        val uprightObs = WindowObservation(
            windowWidthPx = 960,
            windowHeightPx = 720,
            density = 1f,
            displayRotation = Surface.ROTATION_0,
            surfaceRole = SurfaceRole.Inner,
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                ReconTheme {
                    CaptureScreenShell(
                        uiState = CaptureUiState(queue = items, busy = BusyState.Idle),
                        settings = SettingsState(
                            rootUri = Uri.parse("content://com.example.bundlecam/tree/root"),
                            seenTutorialSteps = seenAllTutorialSteps,
                        ),
                        cameraState = CameraSessionState(
                            readiness = CameraReadiness.Ready,
                            capability = CameraCapability.PhotoVideo(1L),
                        ),
                        windowObservation = uprightObs,
                        lensFacing = LensFacing.Back,
                        flashMode = FlashMode.Auto,
                        videoAudioEnabled = true,
                        modality = Modality.PHOTO,
                        recordingStartedAtMs = null,
                        zoomInfo = ZoomInfo(1f, 0.5f, 5f),
                        deviceOrientation = 0,
                        renderCameraPreview = false,
                        onShutter = {},
                        onSetModality = {},
                        onToggleLens = {},
                        onCycleFlash = {},
                        onToggleVideoAudio = {},
                        onZoomChange = {},
                        onCommitBundle = { committed++ },
                        onDiscardQueue = { discarded++ },
                        onUndoDiscard = {},
                        onDeleteOne = { deletedIds.add(it) },
                        onReorder = { _, _ -> },
                        onInsertDivider = {},
                        onRemoveDivider = {},
                        onClearError = {},
                        onDismissTutorial = {},
                        onOpenSettings = {},
                        onOpenBundles = {},
                        modifier = Modifier.size(width = 960.dp, height = 720.dp),
                    )
                }
            }
        }

        val item1Bounds = composeRule.onNodeWithContentDescription("Photo 1 of 3")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val item2Bounds = composeRule.onNodeWithContentDescription("Video 2 of 3")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val commitHandleBounds = composeRule.onNodeWithContentDescription("Commit bundle")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val discardHandleBounds = composeRule.onNodeWithContentDescription("Discard queue")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot

        // In Upright C (Vertical Queue):
        // - Top handle is Discard, Bottom handle is Commit (commitHandle.top > discardHandle.top)
        // - Items are stacked vertically (item2.top > item1.top) and share x centerline
        assertTrue(
            "Vertical queue must stack items along Y: item1=$item1Bounds, item2=$item2Bounds",
            item2Bounds.top > item1Bounds.top,
        )
        assertEquals(item1Bounds.center.x, item2Bounds.center.x, 2f)
        assertTrue(
            "Vertical queue must place Commit handle at bottom and Discard handle at top",
            commitHandleBounds.top > discardHandleBounds.top,
        )

        // Swipe bottom handle UP by 120dp -> commits bundle
        composeRule.onNodeWithContentDescription("Commit bundle").performTouchInput {
            val start = center
            val end = Offset(center.x, center.y - 120.dp.toPx())
            swipe(start, end, durationMillis = 200)
        }
        composeRule.waitForIdle()
        assertEquals(1, committed)

        // Swipe top handle DOWN by 120dp -> discards queue
        composeRule.onNodeWithContentDescription("Discard queue").performTouchInput {
            val start = center
            val end = Offset(center.x, center.y + 120.dp.toPx())
            swipe(start, end, durationMillis = 200)
        }
        composeRule.waitForIdle()
        assertEquals(1, discarded)

        // Outward horizontal drag (> 24dp right) on Photo 1 of 3 deletes item-1
        composeRule.onNodeWithContentDescription("Photo 1 of 3").performTouchInput {
            val start = center
            val end = Offset(center.x + 40.dp.toPx(), center.y)
            swipe(start, end, durationMillis = 200)
        }
        composeRule.waitForIdle()
        assertEquals(listOf("item-1"), deletedIds)
    }

    @Test
    fun designC_cw90_renders_horizontal_queue_below_preview_and_executes_horizontal_gestures() {
        var committed = 0
        var discarded = 0
        val items = testItems()

        val cw90Obs = WindowObservation(
            windowWidthPx = 720,
            windowHeightPx = 960,
            density = 1f,
            displayRotation = Surface.ROTATION_90,
            surfaceRole = SurfaceRole.Inner,
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                ReconTheme {
                    CaptureScreenShell(
                        uiState = CaptureUiState(queue = items, busy = BusyState.Idle),
                        settings = SettingsState(
                            rootUri = Uri.parse("content://com.example.bundlecam/tree/root"),
                            seenTutorialSteps = seenAllTutorialSteps,
                        ),
                        cameraState = CameraSessionState(
                            readiness = CameraReadiness.Ready,
                            capability = CameraCapability.PhotoVideo(1L),
                        ),
                        windowObservation = cw90Obs,
                        lensFacing = LensFacing.Back,
                        flashMode = FlashMode.Auto,
                        videoAudioEnabled = true,
                        modality = Modality.PHOTO,
                        recordingStartedAtMs = null,
                        zoomInfo = ZoomInfo(1f, 0.5f, 5f),
                        deviceOrientation = 90,
                        renderCameraPreview = false,
                        onShutter = {},
                        onSetModality = {},
                        onToggleLens = {},
                        onCycleFlash = {},
                        onToggleVideoAudio = {},
                        onZoomChange = {},
                        onCommitBundle = { committed++ },
                        onDiscardQueue = { discarded++ },
                        onUndoDiscard = {},
                        onDeleteOne = {},
                        onReorder = { _, _ -> },
                        onInsertDivider = {},
                        onRemoveDivider = {},
                        onClearError = {},
                        onDismissTutorial = {},
                        onOpenSettings = {},
                        onOpenBundles = {},
                        modifier = Modifier.size(width = 720.dp, height = 960.dp),
                    )
                }
            }
        }

        val item1Bounds = composeRule.onNodeWithContentDescription("Photo 1 of 3")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val item2Bounds = composeRule.onNodeWithContentDescription("Video 2 of 3")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val shutterBounds = composeRule.onNodeWithContentDescription("Take photo")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val commitHandleBounds = composeRule.onNodeWithContentDescription("Commit bundle")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val discardHandleBounds = composeRule.onNodeWithContentDescription("Discard queue")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot

        // In CW90 C (Horizontal Queue below preview):
        // - Items are arranged horizontally (item2.left > item1.left) and share Y center with shutter
        assertTrue(item2Bounds.left > item1Bounds.left)
        assertEquals(shutterBounds.center.y, item1Bounds.center.y, 2f)
        assertTrue(commitHandleBounds.left < discardHandleBounds.left)

        // Sub-96dp swipe on Commit handle does NOT commit
        composeRule.onNodeWithContentDescription("Commit bundle").performTouchInput {
            swipe(center, Offset(center.x + 50.dp.toPx(), center.y), durationMillis = 180)
        }
        composeRule.waitForIdle()
        assertEquals(0, committed)

        // >= 96dp rightward swipe on Commit handle commits
        composeRule.onNodeWithContentDescription("Commit bundle").performTouchInput {
            swipe(center, Offset(center.x + 120.dp.toPx(), center.y), durationMillis = 180)
        }
        composeRule.waitForIdle()
        assertEquals(1, committed)

        // >= 96dp leftward swipe on Discard handle discards
        composeRule.onNodeWithContentDescription("Discard queue").performTouchInput {
            swipe(center, Offset(center.x - 120.dp.toPx(), center.y), durationMillis = 180)
        }
        composeRule.waitForIdle()
        assertEquals(1, discarded)
    }

    @Test
    fun queueActionMenu_and_recording_lockout_and_shutter_sound_policy_verified_in_compose() {
        var photoSoundsPlayed = 0
        var splitInsertedAfter = -1
        var currentModality by mutableStateOf(Modality.PHOTO)
        var currentBusy by mutableStateOf(BusyState.Idle)
        var currentCapability by mutableStateOf<CameraCapability>(CameraCapability.PhotoVideo(1L))
        val items = testItems()

        composeRule.setContent {
            ReconTheme {
                CaptureScreenShell(
                    uiState = CaptureUiState(queue = items, busy = currentBusy),
                    settings = SettingsState(
                        rootUri = Uri.parse("content://com.example.bundlecam/tree/root"),
                        shutterSoundOn = true,
                        seenTutorialSteps = seenAllTutorialSteps,
                    ),
                    cameraState = CameraSessionState(
                        readiness = CameraReadiness.Ready,
                        capability = currentCapability,
                    ),
                    windowObservation = null,
                    lensFacing = LensFacing.Back,
                    flashMode = FlashMode.Auto,
                    videoAudioEnabled = true,
                    modality = currentModality,
                    recordingStartedAtMs = if (currentBusy == BusyState.Recording) 1000L else null,
                    zoomInfo = ZoomInfo(1.7f, 0.5f, 5f),
                    deviceOrientation = 0,
                    renderCameraPreview = false,
                    onPlayPhotoShutterSound = { photoSoundsPlayed++ },
                    onShutter = {},
                    onSetModality = { currentModality = it },
                    onToggleLens = {},
                    onCycleFlash = {},
                    onToggleVideoAudio = {},
                    onZoomChange = {},
                    onCommitBundle = {},
                    onDiscardQueue = {},
                    onUndoDiscard = {},
                    onDeleteOne = {},
                    onReorder = { _, _ -> },
                    onInsertDivider = { splitInsertedAfter = it },
                    onRemoveDivider = {},
                    onClearError = {},
                    onDismissTutorial = {},
                    onOpenSettings = {},
                    onOpenBundles = {},
                    modifier = Modifier.size(width = 360.dp, height = 800.dp),
                )
            }
        }

        // 1. Continuous zoom 1.7x exposes reset semantics
        composeRule.onNodeWithContentDescription("Current zoom 1.7x; reset to 1x")
            .assertIsDisplayed()

        // 2. Tap thumbnail to open QueueActionMenu and invoke "Split after"
        composeRule.onNodeWithContentDescription("Photo 1 of 3").performClick()
        composeRule.onNodeWithText("Photo 1 of 3").assertIsDisplayed()
        composeRule.onNodeWithText("Split after").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(0, splitInsertedAfter)

        // 3. Shutter sound policy: PHOTO plays sound; VIDEO and VOICE do NOT play photo shutter click
        composeRule.onNodeWithContentDescription("Take photo").performClick()
        assertEquals(1, photoSoundsPlayed)

        composeRule.runOnIdle { currentModality = Modality.VIDEO }
        composeRule.onNodeWithContentDescription("Start recording").performClick()
        assertEquals(1, photoSoundsPlayed)

        composeRule.runOnIdle { currentModality = Modality.VOICE }
        composeRule.onNodeWithContentDescription("Start voice recording").performClick()
        assertEquals(1, photoSoundsPlayed)

        // 4. PhotoOnly capability disables VIDEO segment with explicit reason
        composeRule.runOnIdle {
            currentModality = Modality.PHOTO
            currentCapability = CameraCapability.PhotoOnly("Concurrent video unsupported", 2L)
        }
        composeRule.onNodeWithContentDescription("VIDEO unavailable: Concurrent video unsupported")
            .assertIsDisplayed()
            .assertIsNotEnabled()

        // 5. Recording lockout disables Settings, Bundles, Mode, Flip camera, and queue items,
        // while keeping Stop and Zoom enabled.
        composeRule.runOnIdle {
            currentCapability = CameraCapability.PhotoVideo(3L)
            currentModality = Modality.VIDEO
            currentBusy = BusyState.Recording
        }
        composeRule.onNodeWithContentDescription("Settings").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Bundles").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Flip camera").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Photo 1 of 3").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Stop recording").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Current zoom 1.7x; reset to 1x").assertIsEnabled()

        // Custom actions on locked queue item must be empty
        val lockedNode = composeRule.onNodeWithContentDescription("Photo 1 of 3").fetchSemanticsNode()
        val actions = lockedNode.config.getOrElse(SemanticsActions.CustomActions) { emptyList() }
        assertTrue("Locked queue item must not expose custom actions", actions.isEmpty())
    }
}
