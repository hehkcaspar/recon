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
import com.example.bundlecam.data.camera.ZoomInfo
import com.example.bundlecam.data.camera.decodeThumbnail
import com.example.bundlecam.data.settings.SettingsState
import com.example.bundlecam.data.storage.StagingSession
import com.example.bundlecam.di.AppContainer
import com.example.bundlecam.pipeline.PendingBundle
import com.example.bundlecam.pipeline.PendingPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

enum class BusyState { Idle, Capturing, Committing }

data class StagedPhoto(
    val id: String,
    val localFile: File,
    val thumbnail: ImageBitmap,
    val rotationDegrees: Int,
    val capturedAt: Long,
)

data class PendingDiscard(
    val items: List<StagedPhoto>,
)

data class CaptureUiState(
    val queue: List<StagedPhoto> = emptyList(),
    val pendingDiscard: PendingDiscard? = null,
    val busy: BusyState = BusyState.Idle,
    val lastError: String? = null,
)

sealed class CaptureEvent {
    data class BundleCommitted(val bundleId: String) : CaptureEvent()
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

    private val _isRebinding = MutableStateFlow(false)
    val isRebinding: StateFlow<Boolean> = _isRebinding.asStateFlow()

    private val _events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CaptureEvent> = _events.asSharedFlow()

    val zoomInfo: StateFlow<ZoomInfo?> = captureController.zoomInfo
    val deviceOrientation: StateFlow<Int> = captureController.deviceOrientation

    private var currentSession: StagingSession? = null
    private var pendingDiscardSession: StagingSession? = null
    private var discardTimerJob: Job? = null

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
        if (items.isEmpty()) return

        _uiState.update { it.copy(busy = BusyState.Committing) }

        viewModelScope.launch {
            val currentSettings = settings.value
            val rootUri = currentSettings.rootUri
            if (rootUri == null) {
                _uiState.update {
                    it.copy(busy = BusyState.Idle, lastError = "No output folder set")
                }
                return@launch
            }

            try {
                val bundleId = container.bundleCounterStore.allocate()
                Log.i(TAG, "Commit allocated bundleId=$bundleId sessionId=${session.id} photos=${items.size}")
                val manifest = PendingBundle(
                    bundleId = bundleId,
                    rootUriString = rootUri.toString(),
                    stitchQuality = currentSettings.stitchQuality.name,
                    sessionId = session.id,
                    orderedPhotos = items.map { photo ->
                        PendingPhoto(
                            localPath = photo.localFile.absolutePath,
                            rotationDegrees = photo.rotationDegrees,
                        )
                    },
                    capturedAt = System.currentTimeMillis(),
                )
                container.manifestStore.save(manifest)
                container.workScheduler.enqueue(bundleId)

                currentSession = null
                _uiState.update {
                    it.copy(queue = emptyList(), busy = BusyState.Idle, lastError = null)
                }
                _events.tryEmit(CaptureEvent.BundleCommitted(bundleId))
                Log.i(TAG, "Enqueued bundle $bundleId (${items.size} photos)")
            } catch (t: Throwable) {
                Log.e(TAG, "Enqueue failed", t)
                _uiState.update {
                    it.copy(busy = BusyState.Idle, lastError = "Commit failed: ${t.message}")
                }
            }
        }
    }

    fun onDiscardQueue() {
        if (_uiState.value.busy != BusyState.Idle) return
        val session = currentSession ?: return
        val items = _uiState.value.queue
        if (items.isEmpty()) return

        pendingDiscardSession = session
        _uiState.update {
            it.copy(queue = emptyList(), pendingDiscard = PendingDiscard(items))
        }
        currentSession = null

        discardTimerJob?.cancel()
        discardTimerJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            if (pendingDiscardSession?.id != session.id) return@launch
            // Commit the discard synchronously on Main so a racing Undo tap sees cleared state
            // before the IO delete starts.
            pendingDiscardSession = null
            _uiState.update { it.copy(pendingDiscard = null) }
            runCatching { container.stagingStore.deleteSession(session) }
        }
    }

    fun onUndoDiscard() {
        val pending = _uiState.value.pendingDiscard ?: return
        val session = pendingDiscardSession ?: return
        discardTimerJob?.cancel()
        discardTimerJob = null
        currentSession = session
        pendingDiscardSession = null
        _uiState.update { it.copy(queue = pending.items, pendingDiscard = null) }
    }

    fun onDeleteOne(id: String) {
        if (_uiState.value.busy != BusyState.Idle) return
        val items = _uiState.value.queue
        val target = items.find { it.id == id } ?: return
        val remaining = items - target
        _uiState.update { it.copy(queue = remaining) }

        viewModelScope.launch {
            runCatching { container.stagingStore.deleteFile(target.localFile) }
            if (remaining.isEmpty()) {
                currentSession?.let { runCatching { container.stagingStore.deleteSession(it) } }
                currentSession = null
            }
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

    fun setRebinding(rebinding: Boolean) {
        _isRebinding.value = rebinding
    }

    fun onZoomChange(ratio: Float) {
        captureController.setZoomRatio(ratio)
    }

    private fun confirmPendingDiscardIfAny() {
        val session = pendingDiscardSession ?: return
        discardTimerJob?.cancel()
        discardTimerJob = null
        pendingDiscardSession = null
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
