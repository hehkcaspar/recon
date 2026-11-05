package com.example.bundlecam.ui.capture

import android.app.Application
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bundlecam.ReconApp
import com.example.bundlecam.data.camera.CameraCapability
import com.example.bundlecam.data.camera.CameraSessionContract
import com.example.bundlecam.data.camera.CameraSessionState
import com.example.bundlecam.data.camera.CaptureController
import com.example.bundlecam.data.camera.FlashMode
import com.example.bundlecam.data.camera.LensFacing
import com.example.bundlecam.data.camera.ZoomInfo
import com.example.bundlecam.data.capture.CaptureTakeCoordinator
import com.example.bundlecam.data.capture.CoordinatorState
import com.example.bundlecam.data.capture.FinalizeOutcome
import com.example.bundlecam.data.capture.FinalizeReason
import com.example.bundlecam.data.settings.SettingsState
import com.example.bundlecam.data.storage.StagingSession
import com.example.bundlecam.di.AppContainer
import com.example.bundlecam.pipeline.PendingBundle
import com.example.bundlecam.pipeline.PendingItem
import com.example.bundlecam.pipeline.PendingPhoto
import com.example.bundlecam.pipeline.PendingVideo
import com.example.bundlecam.pipeline.PendingVoice
import com.example.bundlecam.pipeline.RestoredItem
import com.example.bundlecam.ui.capture.window.RawHostEvent
import com.example.bundlecam.ui.common.TimedSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private const val TAG = "Recon/CaptureVM"
private const val UNDO_WINDOW_MS = 3000L

enum class BusyState { Idle, Capturing, Recording, Rebinding }

enum class Modality { PHOTO, VIDEO, VOICE }

/**
 * Pure policy deciding whether `MediaActionSound.SHUTTER_CLICK` may play on a shutter press.
 * Invariant: ONLY `Modality.PHOTO` when `shutterSoundOn == true` and `busy == BusyState.Idle`
 * plays the shutter click. `VIDEO` and `VOICE` never play the photo shutter click sound.
 */
object ShutterSoundPolicy {
    fun shouldPlayPhotoShutterClick(
        modality: Modality,
        shutterSoundOn: Boolean,
        busy: BusyState,
    ): Boolean = modality == Modality.PHOTO && shutterSoundOn && busy == BusyState.Idle
}

sealed class StagedItem {
    abstract val id: String
    abstract val localFile: File
    abstract val thumbnail: ImageBitmap
    abstract val capturedAt: Long

    data class Photo(
        override val id: String,
        override val localFile: File,
        override val thumbnail: ImageBitmap,
        override val capturedAt: Long,
        val rotationDegrees: Int,
    ) : StagedItem()

    data class Video(
        override val id: String,
        override val localFile: File,
        override val thumbnail: ImageBitmap,
        override val capturedAt: Long,
        val rotationDegrees: Int,
        val durationMs: Long,
    ) : StagedItem()

    data class Voice(
        override val id: String,
        override val localFile: File,
        override val thumbnail: ImageBitmap,
        override val capturedAt: Long,
        val durationMs: Long,
    ) : StagedItem()
}

data class PendingDiscard(
    val items: List<StagedItem>,
    val dividers: Set<Int> = emptySet(),
)

data class CaptureUiState(
    val queue: List<StagedItem> = emptyList(),
    val dividers: Set<Int> = emptySet(),
    val pendingDiscard: PendingDiscard? = null,
    val busy: BusyState = BusyState.Idle,
    val lastError: String? = null,
)

sealed class CaptureEvent {
    data class BundlesCommitted(val bundleIds: List<String>) : CaptureEvent()
}

class CaptureViewModel(
    app: Application,
    private val container: AppContainer,
) : AndroidViewModel(app) {

    val coordinator: CaptureTakeCoordinator = container.captureTakeCoordinator
    val cameraSession: CameraSessionContract = container.captureController

    // Retained property for source compatibility while CameraPreview migrates to session contract.
    val captureController: CaptureController get() = container.captureController

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    val settings: StateFlow<SettingsState> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsState())

    val cameraState: StateFlow<CameraSessionState> = coordinator.cameraState
    val coordinatorState: StateFlow<CoordinatorState> = coordinator.coordinatorState

    private val _lensFacing = MutableStateFlow(LensFacing.Back)
    val lensFacing: StateFlow<LensFacing> = _lensFacing.asStateFlow()

    private val _flashMode = MutableStateFlow(FlashMode.Off)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    private val _videoAudioEnabled = MutableStateFlow(true)
    val videoAudioEnabled: StateFlow<Boolean> = _videoAudioEnabled.asStateFlow()

    private val _modality = MutableStateFlow(Modality.PHOTO)
    val modality: StateFlow<Modality> = _modality.asStateFlow()

    private val _recordingStartedAtMs = MutableStateFlow<Long?>(null)
    val recordingStartedAtMs: StateFlow<Long?> = _recordingStartedAtMs.asStateFlow()

    val voiceAmplitudeFlow: StateFlow<Int> = container.voiceController.amplitudeFlow

    val videoAudioAmplitudeFlow: StateFlow<Float> = cameraSession.videoAudioAmplitude

    private val _micPermissionNeeded = MutableStateFlow(false)
    val micPermissionNeeded: StateFlow<Boolean> = _micPermissionNeeded.asStateFlow()

    private val _events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CaptureEvent> = _events.asSharedFlow()

    val zoomInfo: StateFlow<ZoomInfo?> = cameraSession.zoomInfo
    val deviceOrientation: StateFlow<Int> = cameraSession.deviceOrientation

    private var currentSession: StagingSession? = null
    private val discardSlot = TimedSlot<StagingSession>(viewModelScope)

    init {
        coordinator.onTakeOutcome = ::handleTakeOutcome

        coordinator.coordinatorState
            .onEach { coordState ->
                _recordingStartedAtMs.value = coordState.recordingStartedAtMs
                _micPermissionNeeded.value = coordState.micPermissionNeeded
                _uiState.update { state ->
                    // Preserve Rebinding only if coordinator is Idle and camera is binding.
                    val nextBusy = coordState.busyState
                    if (state.busy == nextBusy) state else state.copy(busy = nextBusy)
                }
            }
            .launchIn(viewModelScope)

        coordinator.cameraState
            .onEach { camState ->
                val cap = camState.capability
                if (cap is CameraCapability.PhotoOnly && _modality.value == Modality.VIDEO &&
                    _uiState.value.busy == BusyState.Idle
                ) {
                    _modality.value = Modality.PHOTO
                    setErrorWithUndoPause(cap.reason)
                }
            }
            .launchIn(viewModelScope)

        combine(settings, _lensFacing, _modality) { s, lens, mod ->
            val currentAspect = cameraState.value.bindPlan.aspectRatio
            val targetAspect = when (mod) {
                Modality.PHOTO -> androidx.camera.core.AspectRatio.RATIO_4_3
                Modality.VIDEO -> androidx.camera.core.AspectRatio.RATIO_16_9
                Modality.VOICE -> currentAspect
            }
            Triple(s.cameraMode, lens, targetAspect)
        }
            .onEach { (mode, lens, aspect) ->
                coordinator.updateCameraBindPlan(
                    mode = mode,
                    lens = lens,
                    aspectRatio = aspect,
                )
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            runCatching {
                val restored = container.orphanRecovery.recover()
                if (restored != null) {
                    currentSession = restored.session
                    val stagedItems = restored.items.map<RestoredItem, StagedItem> { r ->
                        when (r) {
                            is RestoredItem.Photo -> StagedItem.Photo(
                                id = UUID.randomUUID().toString(),
                                localFile = r.localFile,
                                thumbnail = r.thumbnail,
                                rotationDegrees = r.rotationDegrees,
                                capturedAt = r.capturedAt,
                            )
                            is RestoredItem.Video -> StagedItem.Video(
                                id = UUID.randomUUID().toString(),
                                localFile = r.localFile,
                                thumbnail = r.thumbnail,
                                rotationDegrees = r.rotationDegrees,
                                durationMs = r.durationMs,
                                capturedAt = r.capturedAt,
                            )
                            is RestoredItem.Voice -> StagedItem.Voice(
                                id = UUID.randomUUID().toString(),
                                localFile = r.localFile,
                                thumbnail = r.thumbnail,
                                durationMs = r.durationMs,
                                capturedAt = r.capturedAt,
                            )
                        }
                    }
                    _uiState.update { it.copy(queue = stagedItems) }
                    Log.i(TAG, "Restored ${stagedItems.size} items from orphan session")
                }
            }.onFailure { Log.w(TAG, "Orphan recovery failed", it) }
        }

        container.workScheduler.observeFailures()
            .onEach { failure ->
                Log.w(TAG, "Bundle ${failure.bundleId} failed: ${failure.message}")
                setErrorWithUndoPause("Bundle ${failure.bundleId} failed: ${failure.message}")
            }
            .launchIn(viewModelScope)

        _flashMode
            .onEach { coordinator.setFlashMode(it) }
            .launchIn(viewModelScope)
    }

    private fun handleTakeOutcome(outcome: FinalizeOutcome) {
        when (outcome) {
            is FinalizeOutcome.Staged -> {
                _uiState.update { state ->
                    val alreadyQueued = state.queue.any { it.id == outcome.item.id }
                    state.copy(
                        queue = if (alreadyQueued) state.queue else state.queue + outcome.item,
                        busy = BusyState.Idle,
                        lastError = null,
                    )
                }
                discardSlot.resume()
            }
            is FinalizeOutcome.Failed -> {
                _uiState.update { state ->
                    state.copy(
                        busy = BusyState.Idle,
                        lastError = outcome.errorMessage,
                    )
                }
                discardSlot.pause()
            }
            is FinalizeOutcome.Ignored -> {
                _uiState.update { state ->
                    if (state.busy != BusyState.Idle && coordinator.coordinatorState.value.busyState == BusyState.Idle) {
                        state.copy(busy = BusyState.Idle)
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun setErrorWithUndoPause(error: String) {
        discardSlot.pause()
        _uiState.update { it.copy(lastError = error) }
    }

    fun onShutter() {
        when (_modality.value) {
            Modality.PHOTO -> onShutterPhoto()
            Modality.VIDEO -> onShutterVideo()
            Modality.VOICE -> onShutterVoice()
        }
    }

    private fun onShutterPhoto() {
        if (_uiState.value.busy != BusyState.Idle) return
        confirmPendingDiscardIfAny()
        val session = currentSession ?: container.stagingStore.createSession().also {
            currentSession = it
        }
        viewModelScope.launch {
            coordinator.capturePhoto(session)
        }
    }

    private fun onShutterVideo() {
        when (_uiState.value.busy) {
            BusyState.Idle -> {
                val cap = cameraState.value.capability
                if (cap is CameraCapability.PhotoOnly) {
                    setErrorWithUndoPause(cap.reason)
                    return
                }
                confirmPendingDiscardIfAny()
                val session = currentSession ?: container.stagingStore.createSession().also {
                    currentSession = it
                }
                viewModelScope.launch {
                    coordinator.startRecordingTake(
                        modality = Modality.VIDEO,
                        session = session,
                        videoAudioEnabled = _videoAudioEnabled.value,
                    )
                }
            }
            BusyState.Recording -> {
                viewModelScope.launch {
                    coordinator.finalizeActiveTakeIfAny(FinalizeReason.UserStop)
                }
            }
            else -> Unit
        }
    }

    private fun onShutterVoice() {
        when (_uiState.value.busy) {
            BusyState.Idle -> {
                confirmPendingDiscardIfAny()
                val session = currentSession ?: container.stagingStore.createSession().also {
                    currentSession = it
                }
                viewModelScope.launch {
                    coordinator.startRecordingTake(
                        modality = Modality.VOICE,
                        session = session,
                        videoAudioEnabled = true,
                    )
                }
            }
            BusyState.Recording -> {
                viewModelScope.launch {
                    coordinator.finalizeActiveTakeIfAny(FinalizeReason.UserStop)
                }
            }
            else -> Unit
        }
    }

    fun onMicPermissionResult(granted: Boolean) {
        coordinator.clearMicPermissionRequest()
        _micPermissionNeeded.value = false
        if (!granted) {
            val message = when (_modality.value) {
                Modality.VIDEO -> "Video audio needs mic permission"
                else -> "Voice recording needs mic permission"
            }
            setErrorWithUndoPause(message)
            return
        }
        if (_uiState.value.busy != BusyState.Idle) return
        when (_modality.value) {
            Modality.VOICE -> onShutterVoice()
            Modality.VIDEO -> onShutterVideo()
            else -> Unit
        }
    }

    fun onCommitBundle() {
        if (_uiState.value.busy != BusyState.Idle) return
        val session = currentSession ?: return
        val items = _uiState.value.queue
        val dividers = _uiState.value.dividers
        if (items.isEmpty()) return

        val currentSettings = settings.value
        val rootUri = currentSettings.rootUri
        if (rootUri == null) {
            setErrorWithUndoPause("No output folder set")
            return
        }

        currentSession = null
        _uiState.update {
            it.copy(queue = emptyList(), dividers = emptySet(), lastError = null)
        }
        discardSlot.resume()

        val stitchQualityName = currentSettings.stitchQuality.name
        val saveIndividualPhotos = currentSettings.saveIndividualPhotos
        val saveStitchedImage = currentSettings.saveStitchedImage
        viewModelScope.launch {
            val segments = DividerOps.partitionByDividers(items, dividers)
            val manifests = mutableListOf<PendingBundle>()
            try {
                val capturedAt = System.currentTimeMillis()
                for (segment in segments) {
                    val bundleId = container.bundleCounterStore.allocate()
                    manifests.add(
                        PendingBundle(
                            bundleId = bundleId,
                            rootUriString = rootUri.toString(),
                            stitchQuality = stitchQualityName,
                            sessionId = session.id,
                            orderedItems = segment.map<StagedItem, PendingItem> { item ->
                                when (item) {
                                    is StagedItem.Photo -> PendingPhoto(
                                        localPath = item.localFile.absolutePath,
                                        rotationDegrees = item.rotationDegrees,
                                    )
                                    is StagedItem.Video -> PendingVideo(
                                        localPath = item.localFile.absolutePath,
                                        rotationDegrees = item.rotationDegrees,
                                        durationMs = item.durationMs,
                                    )
                                    is StagedItem.Voice -> PendingVoice(
                                        localPath = item.localFile.absolutePath,
                                        durationMs = item.durationMs,
                                    )
                                }
                            },
                            capturedAt = capturedAt,
                            saveIndividualPhotos = saveIndividualPhotos,
                            saveStitchedImage = saveStitchedImage,
                        )
                    )
                }
                for (m in manifests) container.manifestStore.save(m)
                for (m in manifests) container.workScheduler.enqueue(m.bundleId)

                val bundleIds = manifests.map { it.bundleId }
                _events.tryEmit(CaptureEvent.BundlesCommitted(bundleIds))
                Log.i(TAG, "Commit complete: ${bundleIds.size} bundle(s) from ${items.size} items (session=${session.id})")
            } catch (t: Throwable) {
                Log.e(TAG, "Commit failed", t)
                for (m in manifests) runCatching { container.manifestStore.delete(m.bundleId) }
                setErrorWithUndoPause("Commit failed: ${t.message}")
            }
        }
    }

    fun onInsertDivider(afterIndex: Int) {
        if (_uiState.value.busy != BusyState.Idle) return
        val size = _uiState.value.queue.size
        if (afterIndex < 0 || afterIndex >= size - 1) return
        _uiState.update { it.copy(dividers = it.dividers + afterIndex) }
    }

    fun onRemoveDivider(afterIndex: Int) {
        if (_uiState.value.busy != BusyState.Idle) return
        _uiState.update { it.copy(dividers = it.dividers - afterIndex) }
    }

    fun onDiscardQueue() {
        if (_uiState.value.busy != BusyState.Idle) return
        val session = currentSession ?: return
        val items = _uiState.value.queue
        val dividers = _uiState.value.dividers
        if (items.isEmpty()) return

        val errorCovering = _uiState.value.lastError != null
        _uiState.update {
            it.copy(
                queue = emptyList(),
                dividers = emptySet(),
                pendingDiscard = PendingDiscard(items, dividers),
            )
        }
        currentSession = null

        container.stagingStore.markDiscarded(session)

        discardSlot.stash(
            value = session,
            timeoutMs = UNDO_WINDOW_MS,
            pausedInitially = errorCovering,
        ) { taken ->
            _uiState.update { it.copy(pendingDiscard = null) }
            runCatching { container.stagingStore.deleteSession(taken) }
        }
    }

    fun onUndoDiscard() {
        if (_uiState.value.busy != BusyState.Idle) return
        val pending = _uiState.value.pendingDiscard ?: return
        val session = discardSlot.take() ?: return
        container.stagingStore.unmarkDiscarded(session)
        currentSession = session
        _uiState.update {
            it.copy(
                queue = pending.items,
                dividers = pending.dividers,
                pendingDiscard = null,
            )
        }
    }

    fun onDeleteOne(id: String) {
        if (_uiState.value.busy != BusyState.Idle) return
        val items = _uiState.value.queue
        val targetIndex = items.indexOfFirst { it.id == id }
        if (targetIndex < 0) return
        val target = items[targetIndex]
        val remaining = items.toMutableList().also { it.removeAt(targetIndex) }
        _uiState.update {
            it.copy(
                queue = remaining,
                dividers = DividerOps.remapDividersAfterDelete(it.dividers, targetIndex, remaining.size),
            )
        }

        val sessionToDelete = if (remaining.isEmpty()) {
            currentSession.also { currentSession = null }
        } else null

        viewModelScope.launch {
            runCatching { container.stagingStore.deleteFile(target.localFile) }
            sessionToDelete?.let { runCatching { container.stagingStore.deleteSession(it) } }
        }
    }

    fun onReorder(fromIndex: Int, toIndex: Int) {
        if (_uiState.value.busy != BusyState.Idle) return
        _uiState.update { state ->
            if (state.busy != BusyState.Idle) return@update state
            if (fromIndex !in state.queue.indices) return@update state
            val clampedTo = toIndex.coerceIn(0, state.queue.lastIndex)
            if (fromIndex == clampedTo) return@update state
            val list = state.queue.toMutableList()
            val item = list.removeAt(fromIndex)
            list.add(clampedTo, item)
            state.copy(queue = list)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(lastError = null) }
        discardSlot.resume()
    }

    fun hasLocationPermission(): Boolean = container.locationProvider.hasPermission()

    fun onDismissGestureTutorial(shownStepIds: Set<String>) {
        viewModelScope.launch {
            runCatching {
                val existing = settings.value.seenTutorialSteps
                container.settingsRepository.setSeenTutorialSteps(existing + shownStepIds)
            }.onFailure { Log.w(TAG, "Failed to persist gesture tutorial steps", it) }
        }
    }

    fun setModality(m: Modality) {
        if (_uiState.value.busy != BusyState.Idle) return
        if (m == Modality.VIDEO) {
            val cap = cameraState.value.capability
            if (cap is CameraCapability.PhotoOnly) {
                setErrorWithUndoPause(cap.reason)
                return
            }
        }
        _modality.value = m
    }

    /**
     * `ON_PAUSE` is routed through `HostEventClassifier` as a transient pause and must
     * NOT finalize an active take (e.g. during multi-window focus changes or permission
     * dialogs). Actual backgrounding is handled by `MainActivity.onStop` (`!isChangingConfigurations`).
     */
    fun onLifecyclePaused() {
        viewModelScope.launch {
            coordinator.onHostEvent(RawHostEvent.ActivityPaused)
        }
    }

    fun onToggleLens() {
        if (_uiState.value.busy != BusyState.Idle) return
        _lensFacing.update { if (it == LensFacing.Back) LensFacing.Front else LensFacing.Back }
    }

    fun onCycleFlash() {
        if (_uiState.value.busy != BusyState.Idle) return
        _flashMode.update {
            when (it) {
                FlashMode.Off -> FlashMode.Auto
                FlashMode.Auto -> FlashMode.On
                FlashMode.On -> FlashMode.Off
            }
        }
    }

    fun onToggleVideoAudio() {
        if (_uiState.value.busy != BusyState.Idle) return
        _videoAudioEnabled.update { !it }
    }

    fun setRebinding(rebinding: Boolean) {
        _uiState.update { state ->
            if (state.busy == BusyState.Recording || state.busy == BusyState.Capturing) {
                state
            } else {
                state.copy(busy = if (rebinding) BusyState.Rebinding else BusyState.Idle)
            }
        }
    }

    fun onZoomChange(ratio: Float) {
        coordinator.setZoomRatio(ratio)
    }

    private fun confirmPendingDiscardIfAny() {
        val session = discardSlot.take() ?: return
        _uiState.update { it.copy(pendingDiscard = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.stagingStore.deleteSession(session) }
        }
    }

    override fun onCleared() {
        if (coordinator.onTakeOutcome != null) {
            coordinator.onTakeOutcome = null
        }
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ReconApp
                CaptureViewModel(app, app.container)
            }
        }
    }
}
