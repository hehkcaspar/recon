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
import com.example.bundlecam.BundleCamApp
import com.example.bundlecam.data.camera.CameraMode
import com.example.bundlecam.data.camera.CaptureController
import com.example.bundlecam.data.camera.FlashMode
import com.example.bundlecam.data.camera.LensFacing
import com.example.bundlecam.data.camera.ZoomInfo
import com.example.bundlecam.data.camera.decodeThumbnail
import com.example.bundlecam.data.settings.SettingsState
import com.example.bundlecam.data.storage.StagingSession
import com.example.bundlecam.di.AppContainer
import com.example.bundlecam.pipeline.PendingBundle
import com.example.bundlecam.pipeline.PendingPhoto
import com.example.bundlecam.ui.common.TimedSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "BundleCam/CaptureVM"
private const val UNDO_WINDOW_MS = 3000L

enum class BusyState { Idle, Capturing }

data class StagedPhoto(
    val id: String,
    val localFile: File,
    val thumbnail: ImageBitmap,
    val rotationDegrees: Int,
    val capturedAt: Long,
)

data class PendingDiscard(
    val items: List<StagedPhoto>,
    val dividers: Set<Int> = emptySet(),
)

data class CaptureUiState(
    val queue: List<StagedPhoto> = emptyList(),
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

    val captureController: CaptureController = CaptureController(app)

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    val settings: StateFlow<SettingsState> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsState())

    private val _cameraMode = MutableStateFlow(CameraMode.ZSL)
    val cameraMode: StateFlow<CameraMode> = _cameraMode.asStateFlow()

    private val _lensFacing = MutableStateFlow(LensFacing.Back)
    val lensFacing: StateFlow<LensFacing> = _lensFacing.asStateFlow()

    private val _flashMode = MutableStateFlow(FlashMode.Off)
    val flashMode: StateFlow<FlashMode> = _flashMode.asStateFlow()

    private val _isRebinding = MutableStateFlow(false)
    val isRebinding: StateFlow<Boolean> = _isRebinding.asStateFlow()

    private val _events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CaptureEvent> = _events.asSharedFlow()

    val zoomInfo: StateFlow<ZoomInfo?> = captureController.zoomInfo
    val deviceOrientation: StateFlow<Int> = captureController.deviceOrientation

    private var currentSession: StagingSession? = null
    private val discardSlot = TimedSlot<StagingSession>(viewModelScope)

    init {
        viewModelScope.launch {
            runCatching {
                val restored = container.orphanRecovery.recover()
                if (restored != null) {
                    currentSession = restored.session
                    val stagedPhotos = restored.items.map { r ->
                        StagedPhoto(
                            id = UUID.randomUUID().toString(),
                            localFile = r.localFile,
                            thumbnail = r.thumbnail,
                            rotationDegrees = r.rotationDegrees,
                            capturedAt = r.capturedAt,
                        )
                    }
                    _uiState.update { it.copy(queue = stagedPhotos) }
                    Log.i(TAG, "Restored ${stagedPhotos.size} photos from orphan session")
                }
            }.onFailure { Log.w(TAG, "Orphan recovery failed", it) }
        }

        container.workScheduler.observeFailures()
            .onEach { failure ->
                Log.w(TAG, "Bundle ${failure.bundleId} failed: ${failure.message}")
                _uiState.update {
                    it.copy(lastError = "Bundle ${failure.bundleId} failed: ${failure.message}")
                }
            }
            .launchIn(viewModelScope)

        _flashMode
            .onEach { captureController.setFlashMode(it) }
            .launchIn(viewModelScope)
    }

    fun onShutter() {
        if (_uiState.value.busy != BusyState.Idle) return
        _uiState.update { it.copy(busy = BusyState.Capturing) }
        confirmPendingDiscardIfAny()

        viewModelScope.launch {
            launch { container.locationProvider.refresh() }

            val session = currentSession ?: container.stagingStore.createSession().also {
                currentSession = it
            }
            try {
                val photo = captureController.takePicture()
                val capturedAt = System.currentTimeMillis()
                val file = container.stagingStore.writePhoto(session, photo.jpegBytes)

                withContext(Dispatchers.IO) {
                    container.exifWriter.stamp(
                        file = file,
                        capturedAt = capturedAt,
                        rotationDegrees = photo.rotationDegrees,
                        location = container.locationProvider.getCachedOrNull(),
                    )
                }

                val thumbnail = withContext(Dispatchers.Default) {
                    decodeThumbnail(photo.jpegBytes, photo.rotationDegrees)
                }
                val staged = StagedPhoto(
                    id = UUID.randomUUID().toString(),
                    localFile = file,
                    thumbnail = thumbnail,
                    rotationDegrees = photo.rotationDegrees,
                    capturedAt = capturedAt,
                )
                _uiState.update {
                    it.copy(
                        queue = it.queue + staged,
                        busy = BusyState.Idle,
                        lastError = null,
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Capture failed", t)
                _uiState.update {
                    it.copy(busy = BusyState.Idle, lastError = "Capture failed: ${t.message}")
                }
            }
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
            _uiState.update { it.copy(lastError = "No output folder set") }
            return
        }

        // Pivot UI synchronously — shutter is ready for the next shot before any bookkeeping
        // runs. Photos are already durable on staging (written at capture time). If the
        // background save dies, OrphanRecovery will restore this session as an uncommitted
        // queue on next launch, and the user can re-swipe.
        currentSession = null
        _uiState.update {
            it.copy(queue = emptyList(), dividers = emptySet(), lastError = null)
        }

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
                            orderedPhotos = segment.map { photo ->
                                PendingPhoto(
                                    localPath = photo.localFile.absolutePath,
                                    rotationDegrees = photo.rotationDegrees,
                                )
                            },
                            capturedAt = capturedAt,
                            saveIndividualPhotos = saveIndividualPhotos,
                            saveStitchedImage = saveStitchedImage,
                        )
                    )
                }
                // Save all manifests first so the commit is atomic from the worker's view:
                // a mid-loop crash leaves no enqueued workers to misbehave on partial state.
                for (m in manifests) container.manifestStore.save(m)
                for (m in manifests) container.workScheduler.enqueue(m.bundleId)

                val bundleIds = manifests.map { it.bundleId }
                _events.tryEmit(CaptureEvent.BundlesCommitted(bundleIds))
                Log.i(TAG, "Commit complete: ${bundleIds.size} bundle(s) from ${items.size} photos (session=${session.id})")
            } catch (t: Throwable) {
                Log.e(TAG, "Commit failed", t)
                // Delete any manifests we saved before the failure. Pure orphan = the session
                // has no manifest references, so OrphanRecovery restores it as a queue on next
                // launch rather than partially processing it (which would delete photos that
                // belonged to a not-yet-saved later bundle in a multi-segment commit).
                for (m in manifests) runCatching { container.manifestStore.delete(m.bundleId) }
                _uiState.update { it.copy(lastError = "Commit failed: ${t.message}") }
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

        _uiState.update {
            it.copy(
                queue = emptyList(),
                dividers = emptySet(),
                pendingDiscard = PendingDiscard(items, dividers),
            )
        }
        currentSession = null

        // Persist the discard intent BEFORE starting the undo timer. If the process
        // dies during the 3s window, OrphanRecovery sees the marker and cleans up
        // instead of restoring the queue on next launch.
        container.stagingStore.markDiscarded(session)

        discardSlot.stash(session, UNDO_WINDOW_MS) { taken ->
            _uiState.update { it.copy(pendingDiscard = null) }
            runCatching { container.stagingStore.deleteSession(taken) }
        }
    }

    fun onUndoDiscard() {
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

        // Decide the session's fate up front (not inside the launched coroutine). Keeps the
        // mutation visible to any racing onShutter immediately — so it can't latch onto a
        // session we're about to delete and then write into it.
        val sessionToDelete = if (remaining.isEmpty()) {
            currentSession.also { currentSession = null }
        } else null

        viewModelScope.launch {
            runCatching { container.stagingStore.deleteFile(target.localFile) }
            sessionToDelete?.let { runCatching { container.stagingStore.deleteSession(it) } }
        }
    }

    fun onReorder(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
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
    }

    fun hasLocationPermission(): Boolean = container.locationProvider.hasPermission()

    fun onCameraModeChange(mode: CameraMode) {
        _cameraMode.value = mode
    }

    fun onToggleLens() {
        if (_isRebinding.value) return
        // Mid-capture lens flip would unbind ImageCapture while takePicture() is still
        // awaiting, surfacing a spurious "Capture failed" error to the user.
        if (_uiState.value.busy != BusyState.Idle) return
        _lensFacing.update { if (it == LensFacing.Back) LensFacing.Front else LensFacing.Back }
    }

    fun onCycleFlash() {
        _flashMode.update {
            when (it) {
                FlashMode.Off -> FlashMode.Auto
                FlashMode.Auto -> FlashMode.On
                FlashMode.On -> FlashMode.Off
            }
        }
    }

    fun setRebinding(rebinding: Boolean) {
        _isRebinding.value = rebinding
    }

    fun onZoomChange(ratio: Float) {
        captureController.setZoomRatio(ratio)
    }

    private fun confirmPendingDiscardIfAny() {
        val session = discardSlot.take() ?: return
        _uiState.update { it.copy(pendingDiscard = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.stagingStore.deleteSession(session) }
        }
    }

    override fun onCleared() {
        captureController.shutdown()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as BundleCamApp
                CaptureViewModel(app, app.container)
            }
        }
    }
}
