package com.example.bundlecam

import android.location.Location
import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.LifecycleOwner
import com.example.bundlecam.data.audio.VoiceRecordingResult
import com.example.bundlecam.data.audio.VoiceSessionContract
import com.example.bundlecam.data.camera.CameraBindPlan
import com.example.bundlecam.data.camera.CameraCapability
import com.example.bundlecam.data.camera.CameraReadiness
import com.example.bundlecam.data.camera.CameraSessionContract
import com.example.bundlecam.data.camera.CameraSessionState
import com.example.bundlecam.data.camera.CapturedPhoto
import com.example.bundlecam.data.camera.FlashMode
import com.example.bundlecam.data.camera.RecordingResult
import com.example.bundlecam.data.camera.StreamGeometry
import com.example.bundlecam.data.camera.ZoomInfo
import com.example.bundlecam.data.camera.computeClampedCropRect
import com.example.bundlecam.data.capture.CaptureTakeCoordinator
import com.example.bundlecam.data.capture.FinalizeOutcome
import com.example.bundlecam.data.capture.FinalizeReason
import com.example.bundlecam.data.capture.MediaFinalizeProbe
import com.example.bundlecam.data.capture.TakeId
import com.example.bundlecam.data.storage.StagingStore
import com.example.bundlecam.data.storage.TakeRecordState
import com.example.bundlecam.ui.capture.BusyState
import com.example.bundlecam.ui.capture.Modality
import com.example.bundlecam.ui.capture.StagedItem
import com.example.bundlecam.ui.capture.window.IntRectPx
import com.example.bundlecam.ui.capture.window.RawHostEvent
import com.example.bundlecam.ui.capture.window.SurfaceRole
import com.example.bundlecam.ui.capture.window.WindowObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureTakeCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeCameraSession : CameraSessionContract {
        val state = MutableStateFlow(
            CameraSessionState(
                readiness = CameraReadiness.Ready,
                capability = CameraCapability.PhotoVideo(bindToken = 1L),
            ),
        )
        override val sessionState: StateFlow<CameraSessionState> = state
        override val zoomInfo = MutableStateFlow<ZoomInfo?>(ZoomInfo(1f, 0.5f, 5f))
        override val videoAudioAmplitude = MutableStateFlow(0f)
        override val deviceOrientation = MutableStateFlow(0)

        var nextPhotoResult: Result<CapturedPhoto> =
            Result.success(CapturedPhoto(byteArrayOf(1, 2, 3, 4), 90))
        var videoStartAllowed: Boolean = true
        var activeVideoToken: String? = null
        var activeVideoFile: File? = null
        var unsolicitedCallback: ((String, RecordingResult) -> Unit)? = null
        var stopVideoResult: RecordingResult? = null
        var stopVideoGate: CompletableDeferred<Unit>? = null
        var bindCount = 0

        override suspend fun attachHost(
            lifecycleOwner: LifecycleOwner,
            previewView: PreviewView?,
            initialPlan: CameraBindPlan,
        ) {
            bindCount++
            state.value = state.value.copy(
                readiness = CameraReadiness.Ready,
                bindPlan = initialPlan,
            )
        }

        override suspend fun detachHost(lifecycleOwner: LifecycleOwner?) {
            state.value = state.value.copy(readiness = CameraReadiness.Unbound)
        }

        override suspend fun updateBindPlan(plan: CameraBindPlan) {
            bindCount++
            state.value = state.value.copy(bindPlan = plan)
        }

        override fun updateDisplayRotation(surfaceRotation: Int) {
            state.value = state.value.copy(activeSurfaceRotation = surfaceRotation)
        }

        override fun setFlashMode(mode: FlashMode) {
            state.value = state.value.copy(flashMode = mode)
        }

        override fun setZoomRatio(ratio: Float) {
            zoomInfo.value = zoomInfo.value?.copy(currentRatio = ratio)
        }

        override fun applyPinchZoom(zoomFactor: Float) {}

        override fun focusAt(previewView: PreviewView, x: Float, y: Float) {}

        override suspend fun takePicture(): CapturedPhoto = nextPhotoResult.getOrThrow()

        override fun startVideoRecording(
            takeToken: String,
            outputFile: File,
            audioEnabled: Boolean,
            onUnsolicitedFinalize: ((String, RecordingResult) -> Unit)?,
        ): Boolean {
            if (!videoStartAllowed) return false
            outputFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
            activeVideoToken = takeToken
            activeVideoFile = outputFile
            unsolicitedCallback = onUnsolicitedFinalize
            state.value = state.value.copy(isRecordingVideo = true)
            return true
        }

        override suspend fun stopVideoRecording(): RecordingResult {
            stopVideoGate?.await()
            val file = activeVideoFile
            state.value = state.value.copy(isRecordingVideo = false)
            return stopVideoResult ?: if (file != null) {
                RecordingResult.Success(file, 1500L)
            } else {
                RecordingResult.Error(-1, IllegalStateException("No video file"))
            }
        }

        override fun shutdown() {}
    }

    private class FakeVoiceSession : VoiceSessionContract {
        override val amplitudeFlow = MutableStateFlow(0)
        var permissionGranted: Boolean = true
        var startGate: CompletableDeferred<Unit>? = null
        var activeFile: File? = null
        var stopResultOverride: VoiceRecordingResult? = null

        override fun hasPermission(): Boolean = permissionGranted

        override suspend fun startRecording(outputFile: File): VoiceRecordingResult {
            if (!permissionGranted) return VoiceRecordingResult.PermissionDenied
            startGate?.await()
            outputFile.writeBytes(byteArrayOf(0x0A, 0x0B, 0x0C))
            activeFile = outputFile
            return VoiceRecordingResult.Success(outputFile, 0L)
        }

        override suspend fun stopRecording(): VoiceRecordingResult {
            val file = activeFile ?: return VoiceRecordingResult.NotRecording
            activeFile = null
            return stopResultOverride ?: VoiceRecordingResult.Success(file, 2400L)
        }
    }

    private class FakeMediaProbe : MediaFinalizeProbe {
        val dummyBitmap: ImageBitmap = mock(ImageBitmap::class.java)
        var playable: Boolean = true
        var stampCalls = 0

        override suspend fun stampPhotoExif(
            file: File,
            capturedAt: Long,
            rotationDegrees: Int,
            location: Location?,
        ) {
            stampCalls++
        }

        override suspend fun decodePhotoThumbnail(bytes: ByteArray, rotationDegrees: Int): ImageBitmap =
            dummyBitmap

        override suspend fun isPlayableMedia(file: File): Boolean = playable && file.exists() && file.length() > 0L

        override suspend fun decodeVideoPosterFrame(file: File): ImageBitmap? =
            if (playable) dummyBitmap else null

        override suspend fun createVoiceThumbnail(): ImageBitmap = dummyBitmap
    }

    @Test
    fun photo_video_voice_assign_unique_takeIds_and_preserve_order_log() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val stagingStore = StagingStore(baseDirectoryOverride = tempFolder.newFolder("staging"))
        val camera = FakeCameraSession()
        val voice = FakeVoiceSession()
        val probe = FakeMediaProbe()
        val outcomes = mutableListOf<FinalizeOutcome>()
        var now = 1000L

        val coordinator = CaptureTakeCoordinator(
            cameraSession = camera,
            voiceSession = voice,
            stagingStore = stagingStore,
            mediaProbe = probe,
            scope = scope,
            clockMs = { now++ },
        ).apply {
            onTakeOutcome = { outcomes.add(it) }
        }

        val session = stagingStore.createSession()

        // 1. Photo take
        val photoOutcome = coordinator.capturePhoto(session)
        assertTrue(photoOutcome is FinalizeOutcome.Staged)
        val photoItem = (photoOutcome as FinalizeOutcome.Staged).item as StagedItem.Photo

        // 2. Video take
        val videoTakeId = coordinator.startRecordingTake(Modality.VIDEO, session, videoAudioEnabled = true)
        assertNotNull(videoTakeId)
        assertEquals(BusyState.Recording, coordinator.coordinatorState.value.busyState)
        val videoOutcome = coordinator.finalizeOnce(videoTakeId!!, FinalizeReason.UserStop)
        assertTrue(videoOutcome is FinalizeOutcome.Staged)
        val videoItem = (videoOutcome as FinalizeOutcome.Staged).item as StagedItem.Video

        // 3. Voice take
        val voiceTakeId = coordinator.startRecordingTake(Modality.VOICE, session, videoAudioEnabled = true)
        assertNotNull(voiceTakeId)
        assertEquals(BusyState.Recording, coordinator.coordinatorState.value.busyState)
        val voiceOutcome = coordinator.finalizeOnce(voiceTakeId!!, FinalizeReason.UserStop)
        assertTrue(voiceOutcome is FinalizeOutcome.Staged)
        val voiceItem = (voiceOutcome as FinalizeOutcome.Staged).item as StagedItem.Voice

        assertNotEquals(photoOutcome.takeId, videoTakeId)
        assertNotEquals(videoTakeId, voiceTakeId)
        assertNotEquals(photoOutcome.takeId, voiceTakeId)

        // Verify order log has exactly 3 unique entries in start order
        val orderLog = stagingStore.readOrderLog(session)
        assertEquals(
            listOf(photoItem.localFile.name, videoItem.localFile.name, voiceItem.localFile.name),
            orderLog,
        )

        // Verify TakeRecords are all Finalized
        val records = stagingStore.readTakeRecords(session)
        assertEquals(TakeRecordState.Finalized, records[photoOutcome.takeId.value]?.state)
        assertEquals(TakeRecordState.Finalized, records[videoTakeId.value]?.state)
        assertEquals(TakeRecordState.Finalized, records[voiceTakeId.value]?.state)
    }

    @Test
    fun finalizeOnce_is_idempotent_under_concurrent_userStop_unsolicitedFinalize_and_fold() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val stagingStore = StagingStore(baseDirectoryOverride = tempFolder.newFolder("staging-race"))
        val camera = FakeCameraSession()
        val voice = FakeVoiceSession()
        val probe = FakeMediaProbe()
        val stagedItems = mutableListOf<StagedItem>()

        val coordinator = CaptureTakeCoordinator(
            cameraSession = camera,
            voiceSession = voice,
            stagingStore = stagingStore,
            mediaProbe = probe,
            scope = scope,
        ).apply {
            onTakeOutcome = { outcome ->
                if (outcome is FinalizeOutcome.Staged) stagedItems.add(outcome.item)
            }
        }

        val session = stagingStore.createSession()
        val takeId = coordinator.startRecordingTake(Modality.VIDEO, session, videoAudioEnabled = true)!!
        val gate = CompletableDeferred<Unit>()
        camera.stopVideoGate = gate

        // Launch 3 concurrent finalize triggers: UserStop, StableSurfaceChange, and Backgrounded
        val d1 = async { coordinator.finalizeOnce(takeId, FinalizeReason.UserStop) }
        val d2 = async { coordinator.finalizeOnce(takeId, FinalizeReason.StableSurfaceChange) }
        val d3 = async { coordinator.finalizeOnce(takeId, FinalizeReason.Backgrounded) }
        advanceUntilIdle()

        // Release the recorder stop gate and also fire unsolicited CameraX Finalize
        gate.complete(Unit)
        camera.unsolicitedCallback?.invoke(
            takeId.value,
            RecordingResult.Success(camera.activeVideoFile!!, 1500L),
        )
        advanceUntilIdle()

        val r1 = d1.await()
        val r2 = d2.await()
        val r3 = d3.await()

        assertEquals(r1, r2)
        assertEquals(r2, r3)
        assertTrue(r1 is FinalizeOutcome.Staged)
        assertEquals(1, stagedItems.size)
        assertEquals(1, stagingStore.readOrderLog(session)?.size)
    }

    @Test
    fun rotation_and_resize_continue_recording_while_inner_cover_crossing_finalizes_once() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val stagingStore = StagingStore(baseDirectoryOverride = tempFolder.newFolder("staging-fold"))
        val camera = FakeCameraSession()
        val voice = FakeVoiceSession()
        val probe = FakeMediaProbe()
        val outcomes = mutableListOf<FinalizeOutcome>()

        val coordinator = CaptureTakeCoordinator(
            cameraSession = camera,
            voiceSession = voice,
            stagingStore = stagingStore,
            mediaProbe = probe,
            scope = scope,
        ).apply {
            onTakeOutcome = { outcomes.add(it) }
        }

        val initialInner = WindowObservation(
            windowWidthPx = 960,
            windowHeightPx = 720,
            density = 1f,
            displayRotation = Surface.ROTATION_0,
            surfaceRole = SurfaceRole.Inner,
        )
        coordinator.seedWindowObservation(initialInner)

        val session = stagingStore.createSession()
        val takeId = coordinator.startRecordingTake(Modality.VIDEO, session, videoAudioEnabled = true)!!
        assertEquals(BusyState.Recording, coordinator.coordinatorState.value.busyState)

        // 1. Rotate 90 degrees (CW90) -> must keep recording the same TakeId
        coordinator.onHostEvent(
            RawHostEvent.ConfigurationChanged(
                initialInner.copy(
                    windowWidthPx = 720,
                    windowHeightPx = 960,
                    displayRotation = Surface.ROTATION_90,
                ),
            ),
        )
        assertEquals(BusyState.Recording, coordinator.coordinatorState.value.busyState)
        assertEquals(takeId, coordinator.coordinatorState.value.activeTakeId)
        assertEquals(Surface.ROTATION_90, camera.sessionState.value.activeSurfaceRotation)
        assertTrue(outcomes.isEmpty())

        // 2. Activity onPause -> must NOT finalize
        coordinator.onHostEvent(RawHostEvent.ActivityPaused)
        assertEquals(BusyState.Recording, coordinator.coordinatorState.value.busyState)
        assertTrue(outcomes.isEmpty())

        // 3. Confirmed fold to Cover -> must finalize exactly once
        coordinator.onHostEvent(
            RawHostEvent.WindowChanged(
                WindowObservation(
                    windowWidthPx = 360,
                    windowHeightPx = 576,
                    density = 1f,
                    displayRotation = Surface.ROTATION_0,
                    surfaceRole = SurfaceRole.Cover,
                ),
            ),
        )
        assertEquals(BusyState.Idle, coordinator.coordinatorState.value.busyState)
        assertNull(coordinator.coordinatorState.value.activeTakeId)
        assertEquals(1, outcomes.size)
        val outcome = outcomes.single() as FinalizeOutcome.Staged
        assertEquals(takeId, outcome.takeId)
        assertEquals(FinalizeReason.StableSurfaceChange, outcome.reason)
    }

    @Test
    fun stop_arriving_while_start_is_in_flight_finalizes_once_after_start_settles() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val stagingStore = StagingStore(baseDirectoryOverride = tempFolder.newFolder("staging-inflight"))
        val camera = FakeCameraSession()
        val voice = FakeVoiceSession()
        val probe = FakeMediaProbe()
        val startGate = CompletableDeferred<Unit>()
        voice.startGate = startGate

        val coordinator = CaptureTakeCoordinator(
            cameraSession = camera,
            voiceSession = voice,
            stagingStore = stagingStore,
            mediaProbe = probe,
            scope = scope,
        )

        val session = stagingStore.createSession()
        val startDeferred = async {
            coordinator.startRecordingTake(Modality.VOICE, session, videoAudioEnabled = true)
        }
        advanceUntilIdle()

        // Request stop while startRecordingTake is suspended inside voiceSession.startRecording
        val stopDeferred = async {
            coordinator.finalizeActiveTakeIfAny(FinalizeReason.UserStop)
        }
        advanceUntilIdle()

        // Now allow start to complete
        startGate.complete(Unit)
        advanceUntilIdle()

        val takeId = startDeferred.await()
        stopDeferred.await()
        assertNotNull(takeId)
        assertEquals(BusyState.Idle, coordinator.coordinatorState.value.busyState)
        val records = stagingStore.readTakeRecords(session)
        assertEquals(TakeRecordState.Finalized, records[takeId!!.value]?.state)
    }

    @Test
    fun stale_callback_from_first_take_cannot_complete_second_take() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val stagingStore = StagingStore(baseDirectoryOverride = tempFolder.newFolder("staging-stale"))
        val camera = FakeCameraSession()
        val voice = FakeVoiceSession()
        val probe = FakeMediaProbe()

        val coordinator = CaptureTakeCoordinator(
            cameraSession = camera,
            voiceSession = voice,
            stagingStore = stagingStore,
            mediaProbe = probe,
            scope = scope,
        )

        val session = stagingStore.createSession()
        val firstTakeId = coordinator.startRecordingTake(Modality.VIDEO, session, videoAudioEnabled = true)!!
        val firstCallback = camera.unsolicitedCallback
        val firstFile = camera.activeVideoFile!!

        // Finalize first take normally
        val firstOutcome = coordinator.finalizeOnce(firstTakeId, FinalizeReason.UserStop)
        assertTrue(firstOutcome is FinalizeOutcome.Staged)

        // Start second take
        val secondTakeId = coordinator.startRecordingTake(Modality.VIDEO, session, videoAudioEnabled = true)!!
        assertNotEquals(firstTakeId, secondTakeId)
        assertEquals(BusyState.Recording, coordinator.coordinatorState.value.busyState)

        // Replay stale callback from first take
        firstCallback?.invoke(firstTakeId.value, RecordingResult.Success(firstFile, 999L))
        advanceUntilIdle()

        // Second take must remain actively recording
        assertEquals(BusyState.Recording, coordinator.coordinatorState.value.busyState)
        assertEquals(secondTakeId, coordinator.coordinatorState.value.activeTakeId)
    }

    @Test
    fun stream_geometry_and_crop_rect_math_handle_rotations_and_bounds() {
        val portraitStream = StreamGeometry(
            bufferWidth = 1920,
            bufferHeight = 1440,
            cropRect = IntRectPx(0, 0, 1920, 1440),
            rotationDegrees = 90,
        )
        assertTrue(portraitStream.isQuarterTurn)
        assertEquals(1440, portraitStream.uprightWidth)
        assertEquals(1920, portraitStream.uprightHeight)
        assertEquals(0.75f, portraitStream.uprightAspectRatio, 0.0001f)

        val landscapeStream = StreamGeometry(
            bufferWidth = 1920,
            bufferHeight = 1440,
            cropRect = IntRectPx(0, 0, 1920, 1440),
            rotationDegrees = 0,
        )
        assertEquals(4f / 3f, landscapeStream.uprightAspectRatio, 0.0001f)

        // Full-buffer crop returns null (no second crop needed)
        assertNull(computeClampedCropRect(IntRectPx(0, 0, 1920, 1440), 1920, 1440))

        // Sub-region crop clamps and returns exact sub-rectangle
        val subCrop = computeClampedCropRect(IntRectPx(120, 60, 1800, 1320), 1920, 1440)
        assertEquals(IntRectPx(120, 60, 1800, 1320), subCrop)
    }
}
