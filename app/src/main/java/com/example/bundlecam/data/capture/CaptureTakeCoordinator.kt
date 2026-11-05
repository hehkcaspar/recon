package com.example.bundlecam.data.capture

import android.location.Location
import android.util.Log
import androidx.camera.core.AspectRatio
import androidx.camera.view.PreviewView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.LifecycleOwner
import com.example.bundlecam.data.audio.VoiceRecordingResult
import com.example.bundlecam.data.audio.VoiceSessionContract
import com.example.bundlecam.data.camera.CameraBindPlan
import com.example.bundlecam.data.camera.CameraMode
import com.example.bundlecam.data.camera.CameraSessionContract
import com.example.bundlecam.data.camera.CameraSessionState
import com.example.bundlecam.data.camera.CapturedPhoto
import com.example.bundlecam.data.camera.FlashMode
import com.example.bundlecam.data.camera.LensFacing
import com.example.bundlecam.data.camera.RecordingResult
import com.example.bundlecam.data.camera.decodeThumbnail
import com.example.bundlecam.data.camera.decodeVideoPoster
import com.example.bundlecam.data.camera.decodeVoiceThumbnail
import com.example.bundlecam.data.camera.isMediaFileReadable
import com.example.bundlecam.data.exif.ExifWriter
import com.example.bundlecam.data.storage.StagingSession
import com.example.bundlecam.data.storage.StagingStore
import com.example.bundlecam.data.storage.StorageLayout.MediaKind
import com.example.bundlecam.data.storage.TakeRecord
import com.example.bundlecam.data.storage.TakeRecordState
import com.example.bundlecam.ui.capture.BusyState
import com.example.bundlecam.ui.capture.Modality
import com.example.bundlecam.ui.capture.StagedItem
import com.example.bundlecam.ui.capture.window.ClassifiedHostEvent
import com.example.bundlecam.ui.capture.window.HostEventClassifier
import com.example.bundlecam.ui.capture.window.RawHostEvent
import com.example.bundlecam.ui.capture.window.SurfaceRole
import com.example.bundlecam.ui.capture.window.WindowObservation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Recon/TakeCoordinator"

@JvmInline
value class TakeId(val value: String) {
    companion object {
        fun random(): TakeId = TakeId(UUID.randomUUID().toString())
    }
}

enum class FinalizeReason {
    UserStop,
    CameraXFinalize,
    StableSurfaceChange,
    Backgrounded,
    RouteExit,
    PermissionFailure,
    DeviceError,
    PhotoCompleted,
}

sealed interface FinalizeOutcome {
    val takeId: TakeId

    data class Staged(
        override val takeId: TakeId,
        val item: StagedItem,
        val reason: FinalizeReason,
    ) : FinalizeOutcome

    data class Failed(
        override val takeId: TakeId,
        val errorMessage: String,
        val reason: FinalizeReason,
    ) : FinalizeOutcome

    data class Ignored(
        override val takeId: TakeId,
        val detail: String,
    ) : FinalizeOutcome
}

/**
 * Abstraction over Android media decoding / EXIF stamping during take finalization
 * so `CaptureTakeCoordinator` can be tested deterministically on the JVM as well
 * as in Android instrumentation.
 */
interface MediaFinalizeProbe {
    suspend fun stampPhotoExif(
        file: File,
        capturedAt: Long,
        rotationDegrees: Int,
        location: Location?,
    )

    suspend fun decodePhotoThumbnail(bytes: ByteArray, rotationDegrees: Int): ImageBitmap

    suspend fun isPlayableMedia(file: File): Boolean

    suspend fun decodeVideoPosterFrame(file: File): ImageBitmap?

    suspend fun createVoiceThumbnail(): ImageBitmap
}

class DefaultMediaFinalizeProbe(
    private val exifWriter: ExifWriter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MediaFinalizeProbe {
    override suspend fun stampPhotoExif(
        file: File,
        capturedAt: Long,
        rotationDegrees: Int,
        location: Location?,
    ) = withContext(ioDispatcher) {
        exifWriter.stamp(
            file = file,
            capturedAt = capturedAt,
            rotationDegrees = rotationDegrees,
            location = location,
        )
    }

    override suspend fun decodePhotoThumbnail(bytes: ByteArray, rotationDegrees: Int): ImageBitmap =
        withContext(defaultDispatcher) {
            decodeThumbnail(bytes, rotationDegrees)
        }

    override suspend fun isPlayableMedia(file: File): Boolean =
        withContext(ioDispatcher) {
            isMediaFileReadable(file)
        }

    override suspend fun decodeVideoPosterFrame(file: File): ImageBitmap? =
        withContext(defaultDispatcher) {
            decodeVideoPoster(file)
        }

    override suspend fun createVoiceThumbnail(): ImageBitmap =
        withContext(defaultDispatcher) {
            decodeVoiceThumbnail()
        }
}

data class ActiveTakeContext(
    val takeId: TakeId,
    val modality: Modality,
    val ordinal: Int,
    val session: StagingSession,
    val tempFile: File,
    val finalFile: File,
    val startedAtMs: Long,
    val startRotationDegrees: Int,
    var state: TakeRecordState,
    var pendingStopReason: FinalizeReason? = null,
    var externalVideoResult: RecordingResult? = null,
    val completionDeferred: CompletableDeferred<FinalizeOutcome> = CompletableDeferred(),
) {
    fun toTakeRecord(): TakeRecord = TakeRecord(
        takeId = takeId.value,
        mediaKind = when (modality) {
            Modality.PHOTO -> MediaKind.Photo
            Modality.VIDEO -> MediaKind.Video
            Modality.VOICE -> MediaKind.Voice
        },
        ordinal = ordinal,
        tempName = tempFile.name,
        finalName = finalFile.name,
        startedAtMs = startedAtMs,
        state = state,
    )
}

data class CoordinatorState(
    val activeTakeId: TakeId? = null,
    val activeModality: Modality? = null,
    val busyState: BusyState = BusyState.Idle,
    val recordingStartedAtMs: Long? = null,
    val lastObservation: WindowObservation? = null,
    val lastConfirmedSurface: SurfaceRole = SurfaceRole.Unknown,
    val micPermissionNeeded: Boolean = false,
)

/**
 * Process-scoped coordinator owned by `AppContainer` that unifies:
 * 1. Single-owner CameraX session attachment/detachment & configuration.
 * 2. Voice session start/stop.
 * 3. `TakeId` allocation, `TakeRecord` persistence (`Prepared → Recording → Finalizing → Finalized/Aborted`),
 *    and `finalizeOnce(TakeId)` idempotency across user stop, unsolicited CameraX `Finalize`,
 *    stable `Inner ↔ Cover` transitions, genuine backgrounding, and device/permission errors.
 */
class CaptureTakeCoordinator(
    val cameraSession: CameraSessionContract,
    val voiceSession: VoiceSessionContract,
    private val stagingStore: StagingStore,
    private val mediaProbe: MediaFinalizeProbe,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val locationRefresh: suspend () -> Unit = {},
    private val cachedLocation: () -> Location? = { null },
) {
    private val stateMutex = Mutex()
    private val ordinalCounter = AtomicInteger(0)
    private val hostClassifier = HostEventClassifier()

    private var activeTake: ActiveTakeContext? = null
    private val completedOutcomes = LinkedHashMap<TakeId, FinalizeOutcome>()

    private val _coordinatorState = MutableStateFlow(CoordinatorState())
    val coordinatorState: StateFlow<CoordinatorState> = _coordinatorState.asStateFlow()

    val cameraState: StateFlow<CameraSessionState> = cameraSession.sessionState

    // Callback invoked on the coordinator's scope whenever a take finishes staging an item or fails.
    var onTakeOutcome: ((FinalizeOutcome) -> Unit)? = null

    suspend fun attachCaptureHost(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        mode: CameraMode,
        lens: LensFacing,
    ) {
        cameraSession.attachHost(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            initialPlan = CameraBindPlan(mode = mode, lens = lens),
        )
    }

    suspend fun detachCaptureHost(lifecycleOwner: LifecycleOwner? = null) {
        cameraSession.detachHost(lifecycleOwner)
    }

    suspend fun updateCameraBindPlan(
        mode: CameraMode,
        lens: LensFacing,
        aspectRatio: Int = AspectRatio.RATIO_4_3,
    ) {
        cameraSession.updateBindPlan(
            CameraBindPlan(
                mode = mode,
                lens = lens,
                aspectRatio = aspectRatio,
            ),
        )
    }

    fun setFlashMode(mode: FlashMode) {
        cameraSession.setFlashMode(mode)
    }

    fun setZoomRatio(ratio: Float) {
        cameraSession.setZoomRatio(ratio)
    }

    fun applyPinchZoom(factor: Float) {
        cameraSession.applyPinchZoom(factor)
    }

    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        cameraSession.focusAt(previewView, x, y)
    }

    fun seedWindowObservation(observation: WindowObservation) {
        hostClassifier.seed(observation)
        cameraSession.updateDisplayRotation(observation.displayRotation)
        _coordinatorState.update {
            it.copy(
                lastObservation = observation,
                lastConfirmedSurface = hostClassifier.lastConfirmedSurface,
            )
        }
    }

    /**
     * Dispatch a raw host/window event through [HostEventClassifier] and enforce the
     * recording continuity vs. finalize-once policy.
     */
    suspend fun onHostEvent(rawEvent: RawHostEvent): ClassifiedHostEvent {
        val classified = hostClassifier.classify(rawEvent)
        when (classified) {
            is ClassifiedHostEvent.PresentationUpdated -> {
                cameraSession.updateDisplayRotation(classified.observation.displayRotation)
                _coordinatorState.update {
                    it.copy(
                        lastObservation = classified.observation,
                        lastConfirmedSurface = classified.lastConfirmedSurface,
                    )
                }
            }
            is ClassifiedHostEvent.StableSurfaceChanged -> {
                cameraSession.updateDisplayRotation(classified.observation.displayRotation)
                _coordinatorState.update {
                    it.copy(
                        lastObservation = classified.observation,
                        lastConfirmedSurface = classified.to,
                    )
                }
                finalizeActiveTakeIfAny(FinalizeReason.StableSurfaceChange)
            }
            is ClassifiedHostEvent.Backgrounded -> {
                finalizeActiveTakeIfAny(
                    if (rawEvent is RawHostEvent.RouteExited) {
                        FinalizeReason.RouteExit
                    } else {
                        FinalizeReason.Backgrounded
                    },
                )
                if (rawEvent is RawHostEvent.RouteExited) {
                    cameraSession.detachHost(null)
                }
            }
            ClassifiedHostEvent.TransientPause,
            ClassifiedHostEvent.ConfigurationRecreating -> {
                // Intentionally does NOT finalize an active take.
            }
        }
        return classified
    }

    fun clearMicPermissionRequest() {
        _coordinatorState.update { it.copy(micPermissionNeeded = false) }
    }

    /**
     * Execute a PHOTO take with a unique [TakeId] and `finalizeOnce(TakeId)` semantics.
     */
    suspend fun capturePhoto(session: StagingSession): FinalizeOutcome {
        val takeId = TakeId.random()
        val now = clockMs()
        val ordinal = ordinalCounter.incrementAndGet()
        val (tempFile, finalFile) = stagingStore.allocateTakeFiles(session, takeId.value, MediaKind.Photo)
        val context = ActiveTakeContext(
            takeId = takeId,
            modality = Modality.PHOTO,
            ordinal = ordinal,
            session = session,
            tempFile = tempFile,
            finalFile = finalFile,
            startedAtMs = now,
            startRotationDegrees = cameraSession.deviceOrientation.value,
            state = TakeRecordState.Prepared,
        )

        val accepted = stateMutex.withLock {
            completedOutcomes[takeId]?.let { return it }
            if (activeTake != null || _coordinatorState.value.busyState != BusyState.Idle) {
                return FinalizeOutcome.Ignored(takeId, "Busy with another take")
            }
            activeTake = context
            _coordinatorState.update {
                it.copy(
                    activeTakeId = takeId,
                    activeModality = Modality.PHOTO,
                    busyState = BusyState.Capturing,
                )
            }
            true
        }
        if (!accepted) return FinalizeOutcome.Ignored(takeId, "Not accepted")

        scope.launch { runCatching { locationRefresh() } }

        // Idempotent order-log entry and Prepared TakeRecord before capture.
        stagingStore.appendOrderEntry(session, finalFile.name, takeId.value, now)
        stagingStore.writeTakeRecord(session, context.toTakeRecord())

        val photoResult = runCatching { cameraSession.takePicture() }
        return finalizePhotoOnce(takeId, photoResult)
    }

    private suspend fun finalizePhotoOnce(
        takeId: TakeId,
        photoResult: Result<CapturedPhoto>,
    ): FinalizeOutcome {
        val ctx = stateMutex.withLock {
            completedOutcomes[takeId]?.let { return it }
            val current = activeTake
            if (current == null || current.takeId != takeId) {
                return FinalizeOutcome.Ignored(takeId, "Stale or unknown photo take")
            }
            if (current.state == TakeRecordState.Finalizing ||
                current.state == TakeRecordState.Finalized ||
                current.state == TakeRecordState.Aborted
            ) {
                return@withLock null
            }
            current.state = TakeRecordState.Finalizing
            current
        } ?: return stateMutex.withLock {
            completedOutcomes[takeId] ?: activeTake?.completionDeferred?.let { null }
        } ?: activeTake?.completionDeferred?.await()
            ?: FinalizeOutcome.Ignored(takeId, "Already finalized")

        stagingStore.writeTakeRecord(ctx.session, ctx.toTakeRecord())

        val outcome: FinalizeOutcome = try {
            val photo = photoResult.getOrThrow()
            withContext(Dispatchers.IO) {
                ctx.tempFile.writeBytes(photo.jpegBytes)
            }
            val promoted = stagingStore.promoteTempToFinal(ctx.tempFile, ctx.finalFile)
            if (!promoted) {
                error("Failed to promote photo file for take ${takeId.value}")
            }
            mediaProbe.stampPhotoExif(
                file = ctx.finalFile,
                capturedAt = ctx.startedAtMs,
                rotationDegrees = photo.rotationDegrees,
                location = cachedLocation(),
            )
            val thumbnail = mediaProbe.decodePhotoThumbnail(photo.jpegBytes, photo.rotationDegrees)
            val staged = StagedItem.Photo(
                id = takeId.value,
                localFile = ctx.finalFile,
                thumbnail = thumbnail,
                rotationDegrees = photo.rotationDegrees,
                capturedAt = ctx.startedAtMs,
            )
            ctx.state = TakeRecordState.Finalized
            stagingStore.writeTakeRecord(ctx.session, ctx.toTakeRecord())
            FinalizeOutcome.Staged(takeId, staged, FinalizeReason.PhotoCompleted)
        } catch (t: Throwable) {
            Log.e(TAG, "Photo take ${takeId.value} failed", t)
            runCatching { ctx.tempFile.delete() }
            runCatching { ctx.finalFile.delete() }
            ctx.state = TakeRecordState.Aborted
            stagingStore.writeTakeRecord(ctx.session, ctx.toTakeRecord())
            FinalizeOutcome.Failed(
                takeId = takeId,
                errorMessage = "Capture failed: ${t.message}",
                reason = FinalizeReason.DeviceError,
            )
        }

        completeTakeContext(ctx, outcome)
        return outcome
    }

    /**
     * Start a VIDEO or VOICE recording take, returning the allocated [TakeId] or null
     * if permission is needed or another take is active.
     */
    suspend fun startRecordingTake(
        modality: Modality,
        session: StagingSession,
        videoAudioEnabled: Boolean,
    ): TakeId? {
        require(modality == Modality.VIDEO || modality == Modality.VOICE) {
            "startRecordingTake requires VIDEO or VOICE"
        }

        val needsMic = (modality == Modality.VOICE) || (modality == Modality.VIDEO && videoAudioEnabled)
        if (needsMic && !voiceSession.hasPermission()) {
            _coordinatorState.update { it.copy(micPermissionNeeded = true) }
            return null
        }

        val takeId = TakeId.random()
        val now = clockMs()
        val ordinal = ordinalCounter.incrementAndGet()
        val mediaKind = if (modality == Modality.VIDEO) MediaKind.Video else MediaKind.Voice
        val (tempFile, finalFile) = stagingStore.allocateTakeFiles(session, takeId.value, mediaKind)
        val context = ActiveTakeContext(
            takeId = takeId,
            modality = modality,
            ordinal = ordinal,
            session = session,
            tempFile = tempFile,
            finalFile = finalFile,
            startedAtMs = now,
            startRotationDegrees = cameraSession.deviceOrientation.value,
            state = TakeRecordState.Prepared,
        )

        val canStart = stateMutex.withLock {
            if (activeTake != null || _coordinatorState.value.busyState != BusyState.Idle) {
                false
            } else {
                activeTake = context
                _coordinatorState.update {
                    it.copy(
                        activeTakeId = takeId,
                        activeModality = modality,
                        busyState = BusyState.Capturing,
                        micPermissionNeeded = false,
                    )
                }
                true
            }
        }
        if (!canStart) return null

        // Write idempotent order-log entry at Prepared time before recorder start.
        stagingStore.appendOrderEntry(session, finalFile.name, takeId.value, now)
        stagingStore.writeTakeRecord(session, context.toTakeRecord())

        when (modality) {
            Modality.VIDEO -> {
                val started = cameraSession.startVideoRecording(
                    takeToken = takeId.value,
                    outputFile = tempFile,
                    audioEnabled = videoAudioEnabled,
                    onUnsolicitedFinalize = { token, result ->
                        scope.launch {
                            onExternalVideoFinalize(TakeId(token), result)
                        }
                    },
                )
                if (!started) {
                    finalizeOnce(
                        takeId = takeId,
                        reason = FinalizeReason.DeviceError,
                        explicitErrorMessage = "Video recording unavailable on this device",
                    )
                    return takeId
                }
            }
            Modality.VOICE -> {
                when (val res = voiceSession.startRecording(tempFile)) {
                    is VoiceRecordingResult.Success -> Unit
                    VoiceRecordingResult.PermissionDenied -> {
                        stateMutex.withLock {
                            context.state = TakeRecordState.Aborted
                            activeTake = null
                            _coordinatorState.update {
                                it.copy(
                                    activeTakeId = null,
                                    activeModality = null,
                                    busyState = BusyState.Idle,
                                    recordingStartedAtMs = null,
                                    micPermissionNeeded = true,
                                )
                            }
                        }
                        stagingStore.writeTakeRecord(session, context.toTakeRecord())
                        return null
                    }
                    is VoiceRecordingResult.Error -> {
                        finalizeOnce(
                            takeId = takeId,
                            reason = FinalizeReason.DeviceError,
                            explicitErrorMessage = "Voice recording failed: ${res.message}",
                        )
                        return takeId
                    }
                    VoiceRecordingResult.NotRecording -> {
                        finalizeOnce(
                            takeId = takeId,
                            reason = FinalizeReason.DeviceError,
                            explicitErrorMessage = "Voice recorder failed to start",
                        )
                        return takeId
                    }
                }
            }
            Modality.PHOTO -> Unit
        }

        val immediateStopReason: FinalizeReason? = stateMutex.withLock {
            val current = activeTake
            if (current == null || current.takeId != takeId) {
                return@withLock null
            }
            if (current.state == TakeRecordState.Finalizing ||
                current.state == TakeRecordState.Finalized ||
                current.state == TakeRecordState.Aborted
            ) {
                return@withLock null
            }
            current.state = TakeRecordState.Recording
            val pendingStop = current.pendingStopReason
            _coordinatorState.update {
                it.copy(
                    busyState = if (pendingStop != null) BusyState.Capturing else BusyState.Recording,
                    recordingStartedAtMs = now,
                )
            }
            pendingStop
        }

        stagingStore.writeTakeRecord(session, context.toTakeRecord())

        if (immediateStopReason != null) {
            finalizeOnce(takeId, immediateStopReason)
        }

        return takeId
    }

    /**
     * Request stop/finalization of the currently active recording take (if any).
     */
    suspend fun finalizeActiveTakeIfAny(reason: FinalizeReason): FinalizeOutcome? {
        val targetId = stateMutex.withLock {
            val current = activeTake ?: return null
            if (current.modality == Modality.PHOTO) return null
            if (current.state == TakeRecordState.Prepared) {
                // Start is still in flight; record pending stop so it finalizes as soon as start settles.
                current.pendingStopReason = chooseHigherPriorityReason(current.pendingStopReason, reason)
                _coordinatorState.update { it.copy(busyState = BusyState.Capturing) }
                return null
            }
            current.takeId
        }
        return finalizeOnce(targetId, reason)
    }

    private suspend fun onExternalVideoFinalize(takeId: TakeId, result: RecordingResult) {
        stateMutex.withLock {
            val current = activeTake
            if (current != null && current.takeId == takeId) {
                current.externalVideoResult = result
            }
        }
        finalizeOnce(takeId, FinalizeReason.CameraXFinalize)
    }

    /**
     * Core invariant: `finalizeOnce(TakeId)` guarantees that any `TakeId` is finalized
     * at most once, producing at most one promoted file, one `.order` entry, and one
     * `StagedItem`. Concurrent or duplicate calls return the same [FinalizeOutcome].
     */
    suspend fun finalizeOnce(
        takeId: TakeId,
        reason: FinalizeReason,
        explicitErrorMessage: String? = null,
    ): FinalizeOutcome {
        var waitDeferred: CompletableDeferred<FinalizeOutcome>? = null
        val ctx: ActiveTakeContext? = stateMutex.withLock {
            completedOutcomes[takeId]?.let { return it }
            val current = activeTake
            if (current == null || current.takeId != takeId) {
                return FinalizeOutcome.Ignored(takeId, "Stale or unknown takeId")
            }
            if (current.state == TakeRecordState.Prepared && explicitErrorMessage == null) {
                // Start is still in flight; remember the stop request and wait for completion.
                current.pendingStopReason = chooseHigherPriorityReason(current.pendingStopReason, reason)
                _coordinatorState.update { it.copy(busyState = BusyState.Capturing) }
                waitDeferred = current.completionDeferred
                return@withLock null
            }
            if (current.state == TakeRecordState.Finalizing ||
                current.state == TakeRecordState.Finalized ||
                current.state == TakeRecordState.Aborted
            ) {
                waitDeferred = current.completionDeferred
                return@withLock null
            }
            current.state = TakeRecordState.Finalizing
            _coordinatorState.update { it.copy(busyState = BusyState.Capturing) }
            current
        }

        if (waitDeferred != null) {
            return waitDeferred!!.await()
        }
        val activeCtx = ctx ?: return FinalizeOutcome.Ignored(takeId, "No context to finalize")

        stagingStore.writeTakeRecord(activeCtx.session, activeCtx.toTakeRecord())

        val outcome: FinalizeOutcome = when {
            explicitErrorMessage != null -> {
                runCatching { activeCtx.tempFile.delete() }
                runCatching { activeCtx.finalFile.delete() }
                activeCtx.state = TakeRecordState.Aborted
                stagingStore.writeTakeRecord(activeCtx.session, activeCtx.toTakeRecord())
                FinalizeOutcome.Failed(takeId, explicitErrorMessage, reason)
            }
            activeCtx.modality == Modality.VIDEO -> finalizeVideoTake(activeCtx, reason)
            activeCtx.modality == Modality.VOICE -> finalizeVoiceTake(activeCtx, reason)
            else -> FinalizeOutcome.Ignored(takeId, "Unhandled modality in finalizeOnce")
        }

        completeTakeContext(activeCtx, outcome)
        return outcome
    }

    private suspend fun finalizeVideoTake(
        ctx: ActiveTakeContext,
        reason: FinalizeReason,
    ): FinalizeOutcome {
        val recResult = ctx.externalVideoResult ?: cameraSession.stopVideoRecording()
        return when (recResult) {
            is RecordingResult.Success -> {
                val promoted = stagingStore.promoteTempToFinal(ctx.tempFile, ctx.finalFile)
                val playable = promoted && mediaProbe.isPlayableMedia(ctx.finalFile)
                val poster = if (playable) mediaProbe.decodeVideoPosterFrame(ctx.finalFile) else null
                if (!promoted || !playable || poster == null) {
                    runCatching { ctx.tempFile.delete() }
                    runCatching { ctx.finalFile.delete() }
                    ctx.state = TakeRecordState.Aborted
                    stagingStore.writeTakeRecord(ctx.session, ctx.toTakeRecord())
                    FinalizeOutcome.Failed(
                        takeId = ctx.takeId,
                        errorMessage = "Video could not be finalized",
                        reason = reason,
                    )
                } else {
                    val staged = StagedItem.Video(
                        id = ctx.takeId.value,
                        localFile = ctx.finalFile,
                        thumbnail = poster,
                        capturedAt = ctx.startedAtMs,
                        rotationDegrees = ctx.startRotationDegrees,
                        durationMs = recResult.durationMs,
                    )
                    ctx.state = TakeRecordState.Finalized
                    stagingStore.writeTakeRecord(ctx.session, ctx.toTakeRecord())
                    FinalizeOutcome.Staged(ctx.takeId, staged, reason)
                }
            }
            is RecordingResult.Error -> {
                runCatching { ctx.tempFile.delete() }
                runCatching { ctx.finalFile.delete() }
                ctx.state = TakeRecordState.Aborted
                stagingStore.writeTakeRecord(ctx.session, ctx.toTakeRecord())
                FinalizeOutcome.Failed(
                    takeId = ctx.takeId,
                    errorMessage = "Video recording failed (${recResult.code})",
                    reason = reason,
                )
            }
        }
    }

    private suspend fun finalizeVoiceTake(
        ctx: ActiveTakeContext,
        reason: FinalizeReason,
    ): FinalizeOutcome {
        val voiceResult = voiceSession.stopRecording()
        return when (voiceResult) {
            is VoiceRecordingResult.Success -> {
                val promoted = stagingStore.promoteTempToFinal(ctx.tempFile, ctx.finalFile)
                val playable = promoted && mediaProbe.isPlayableMedia(ctx.finalFile)
                if (!promoted || !playable) {
                    runCatching { ctx.tempFile.delete() }
                    runCatching { ctx.finalFile.delete() }
                    ctx.state = TakeRecordState.Aborted
                    stagingStore.writeTakeRecord(ctx.session, ctx.toTakeRecord())
                    FinalizeOutcome.Failed(
                        takeId = ctx.takeId,
                        errorMessage = "Voice recording could not be finalized",
                        reason = reason,
                    )
                } else {
                    val thumbnail = mediaProbe.createVoiceThumbnail()
                    val staged = StagedItem.Voice(
                        id = ctx.takeId.value,
                        localFile = ctx.finalFile,
                        thumbnail = thumbnail,
                        capturedAt = ctx.startedAtMs,
                        durationMs = voiceResult.durationMs,
                    )
                    ctx.state = TakeRecordState.Finalized
                    stagingStore.writeTakeRecord(ctx.session, ctx.toTakeRecord())
                    FinalizeOutcome.Staged(ctx.takeId, staged, reason)
                }
            }
            is VoiceRecordingResult.Error -> {
                runCatching { ctx.tempFile.delete() }
                runCatching { ctx.finalFile.delete() }
                ctx.state = TakeRecordState.Aborted
                stagingStore.writeTakeRecord(ctx.session, ctx.toTakeRecord())
                FinalizeOutcome.Failed(
                    takeId = ctx.takeId,
                    errorMessage = "Voice recording failed: ${voiceResult.message}",
                    reason = reason,
                )
            }
            VoiceRecordingResult.PermissionDenied -> {
                runCatching { ctx.tempFile.delete() }
                runCatching { ctx.finalFile.delete() }
                ctx.state = TakeRecordState.Aborted
                stagingStore.writeTakeRecord(ctx.session, ctx.toTakeRecord())
                FinalizeOutcome.Failed(
                    takeId = ctx.takeId,
                    errorMessage = "Voice recording needs mic permission",
                    reason = FinalizeReason.PermissionFailure,
                )
            }
            VoiceRecordingResult.NotRecording -> {
                runCatching { ctx.tempFile.delete() }
                runCatching { ctx.finalFile.delete() }
                ctx.state = TakeRecordState.Aborted
                stagingStore.writeTakeRecord(ctx.session, ctx.toTakeRecord())
                FinalizeOutcome.Ignored(ctx.takeId, "Voice recorder was not recording")
            }
        }
    }

    private suspend fun completeTakeContext(
        ctx: ActiveTakeContext,
        outcome: FinalizeOutcome,
    ) {
        stateMutex.withLock {
            completedOutcomes[ctx.takeId] = outcome
            while (completedOutcomes.size > 64) {
                val eldest = completedOutcomes.keys.firstOrNull() ?: break
                completedOutcomes.remove(eldest)
            }
            if (activeTake?.takeId == ctx.takeId) {
                activeTake = null
                _coordinatorState.update {
                    it.copy(
                        activeTakeId = null,
                        activeModality = null,
                        busyState = BusyState.Idle,
                        recordingStartedAtMs = null,
                    )
                }
            }
            ctx.completionDeferred.complete(outcome)
        }
        onTakeOutcome?.invoke(outcome)
    }

    private fun chooseHigherPriorityReason(
        existing: FinalizeReason?,
        incoming: FinalizeReason,
    ): FinalizeReason {
        if (existing == null) return incoming
        return if (incoming.priority >= existing.priority) incoming else existing
    }

    private val FinalizeReason.priority: Int
        get() = when (this) {
            FinalizeReason.Backgrounded, FinalizeReason.RouteExit -> 40
            FinalizeReason.StableSurfaceChange -> 30
            FinalizeReason.PermissionFailure, FinalizeReason.DeviceError -> 25
            FinalizeReason.UserStop, FinalizeReason.CameraXFinalize, FinalizeReason.PhotoCompleted -> 20
        }
}
