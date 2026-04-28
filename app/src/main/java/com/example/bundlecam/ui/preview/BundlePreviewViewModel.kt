package com.example.bundlecam.ui.preview

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bundlecam.ReconApp
import com.example.bundlecam.data.camera.decodeThumbnail
import com.example.bundlecam.data.settings.SettingsState
import com.example.bundlecam.data.storage.CompletedBundle
import com.example.bundlecam.data.storage.DeleteResult
import com.example.bundlecam.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "Recon/BundlePreviewVM"

enum class LoadState { Idle, Loading, Loaded, Error }

/**
 * Wall-clock deadline for a pending-delete timer. The bundle itself lives in
 * [BundlePreviewUiState.bundles] until the timer fires (the row keeps rendering in
 * place during the undo window), so we don't duplicate the bundle here.
 */
data class PendingDelete(val expiresAtMillis: Long)

data class BundlePreviewUiState(
    val bundles: List<CompletedBundle> = emptyList(),
    val loadState: LoadState = LoadState.Idle,
    val errorMessage: String? = null,
    /** Keyed by bundle id — multiple bundles can be mid-countdown at the same time. */
    val pendingDeletes: Map<String, PendingDelete> = emptyMap(),
    /** Keyed by URI, not bundle id — each bundle can contribute up to 3 URIs. */
    val thumbnails: Map<Uri, ImageBitmap> = emptyMap(),
    /** Bundle IDs whose worker is ENQUEUED/RUNNING/BLOCKED, sorted newest-first. */
    val processingBundleIds: List<String> = emptyList(),
    /** Bundle IDs the user has selected via swipe-right or long-press. */
    val selectedBundleIds: Set<String> = emptySet(),
) {
    val selectionMode: Boolean get() = selectedBundleIds.isNotEmpty()
}

class BundlePreviewViewModel(
    app: Application,
    private val container: AppContainer,
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(BundlePreviewUiState())
    val uiState: StateFlow<BundlePreviewUiState> = _uiState.asStateFlow()

    val settings: StateFlow<SettingsState> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsState())

    // One timer per pending bundle. Kept outside ui state because Jobs aren't state —
    // the expiry timestamp in `PendingDelete` is what the UI reads for the countdown.
    private val pendingJobs = mutableMapOf<String, Job>()
    private val thumbnailMutex = Mutex()
    private val inFlightThumbnails = mutableSetOf<Uri>()

    init {
        // Track in-flight bundle workers so the list can show a "Processing…" row for a
        // just-committed bundle that hasn't been written to SAF yet. When a bundle leaves
        // the active set (worker finished), we refresh SAF *before* dropping its
        // processing row, so the completed row slides in without a blank-gap flash.
        viewModelScope.launch {
            var previous = emptySet<String>()
            container.workScheduler.observeActiveBundleIds().collect { ids ->
                val sortedIds = ids.sortedDescending()
                val finished = previous - ids
                previous = ids
                if (finished.isEmpty()) {
                    _uiState.update { it.copy(processingBundleIds = sortedIds) }
                } else {
                    loadBundles()
                    _uiState.update { it.copy(processingBundleIds = sortedIds) }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { loadBundles() }
    }

    private suspend fun loadBundles() {
        val rootUri = settings.value.rootUri
        if (rootUri == null) {
            _uiState.update { it.copy(loadState = LoadState.Error, errorMessage = "No output folder set") }
            return
        }
        _uiState.update { it.copy(loadState = LoadState.Loading, errorMessage = null) }
        runCatching { container.bundleLibrary.listBundles(rootUri) }
            .onSuccess { bundles ->
                _uiState.update { state ->
                    // Drop thumbnails whose URIs are no longer present so the map
                    // doesn't grow unbounded across refreshes.
                    val presentUris = bundles.flatMap { it.thumbnailUris }.toSet()
                    state.copy(
                        bundles = bundles,
                        loadState = LoadState.Loaded,
                        errorMessage = null,
                        thumbnails = state.thumbnails.filterKeys { it in presentUris },
                    )
                }
            }
            .onFailure { t ->
                Log.e(TAG, "Failed to list bundles", t)
                _uiState.update {
                    it.copy(
                        loadState = LoadState.Error,
                        errorMessage = "Couldn't load bundles: ${t.message ?: "unknown error"}",
                    )
                }
            }
    }

    fun loadThumbnail(uri: Uri) {
        viewModelScope.launch {
            thumbnailMutex.withLock {
                if (_uiState.value.thumbnails.containsKey(uri)) return@launch
                if (!inFlightThumbnails.add(uri)) return@launch
            }
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    decodeThumbnail(getApplication<Application>().contentResolver, uri)
                }
                if (bitmap != null) {
                    _uiState.update { it.copy(thumbnails = it.thumbnails + (uri to bitmap)) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Thumbnail decode failed for $uri", t)
            } finally {
                thumbnailMutex.withLock { inFlightThumbnails.remove(uri) }
            }
        }
    }

    /**
     * Move a bundle into the pending-delete state with its own countdown. Multiple
     * bundles can be pending simultaneously — each tracks its expiry independently and
     * hard-deletes when its own timer fires. When the undo window is 0, the delete runs
     * immediately and the pending map is skipped entirely.
     */
    fun onConfirmDelete(bundleId: String) {
        val state = _uiState.value
        val bundle = state.bundles.firstOrNull { it.id == bundleId } ?: return
        val delaySeconds = settings.value.deleteDelaySeconds

        if (delaySeconds <= 0) {
            _uiState.update {
                it.copy(
                    bundles = it.bundles.filterNot { b -> b.id == bundleId },
                    selectedBundleIds = it.selectedBundleIds - bundleId,
                )
            }
            viewModelScope.launch(Dispatchers.IO) { hardDelete(bundle) }
            return
        }

        // UI never surfaces a "re-confirm" path on an already-pending row, but guard
        // anyway: overwriting a live Job reference without cancelling it leaves a
        // zombie timer that would fire a second hardDelete on the same bundle.
        pendingJobs.remove(bundleId)?.cancel()

        val windowMs = delaySeconds * 1000L
        val expiresAt = System.currentTimeMillis() + windowMs
        _uiState.update {
            it.copy(
                // Auto-deselect on swipe-to-delete — user thinks the bundle is gone, so
                // it shouldn't continue sitting in the selection set and being shippable
                // from the contextual app bar.
                selectedBundleIds = it.selectedBundleIds - bundleId,
                pendingDeletes = it.pendingDeletes + (bundleId to PendingDelete(expiresAt)),
            )
        }

        pendingJobs[bundleId] = viewModelScope.launch {
            delay(windowMs)
            _uiState.update {
                it.copy(
                    bundles = it.bundles.filterNot { b -> b.id == bundleId },
                    pendingDeletes = it.pendingDeletes - bundleId,
                )
            }
            pendingJobs.remove(bundleId)
            hardDelete(bundle)
        }
    }

    fun onUndo(bundleId: String) {
        pendingJobs.remove(bundleId)?.cancel() ?: return
        _uiState.update { it.copy(pendingDeletes = it.pendingDeletes - bundleId) }
    }

    /**
     * Toggle [bundleId] in the selection set. No-ops on bundles that aren't currently
     * shippable: pending-delete rows (the user thinks they've already deleted these),
     * processing rows (worker hasn't finished writing — `LocalSendController.send` would
     * find an empty file list), and bundles not in the completed list at all.
     */
    fun toggleSelection(bundleId: String) {
        val state = _uiState.value
        if (!isSelectable(bundleId, state)) return
        _uiState.update {
            val current = it.selectedBundleIds
            val next = if (bundleId in current) current - bundleId else current + bundleId
            it.copy(selectedBundleIds = next)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedBundleIds = emptySet()) }
    }

    /**
     * The set of currently-selected, still-completed [CompletedBundle]s — fed into the
     * LocalSend send sheet when the user taps the contextual app bar's send icon. Drops
     * any IDs that have since transitioned to pending-delete or processing (e.g. the
     * user selected a bundle and then swiped left to delete the same row before tapping
     * send).
     */
    fun selectedBundlesSnapshot(): List<CompletedBundle> {
        val state = _uiState.value
        val byId = state.bundles.associateBy { it.id }
        return state.selectedBundleIds.mapNotNull { id ->
            byId[id]?.takeIf { isSelectable(id, state) }
        }
    }

    private suspend fun hardDelete(bundle: CompletedBundle) {
        withContext(Dispatchers.IO) {
            when (val result = container.bundleLibrary.deleteBundle(bundle)) {
                is DeleteResult.Ok -> Log.i(TAG, "Deleted bundle ${bundle.id}")
                is DeleteResult.Partial -> {
                    Log.w(TAG, "Partial delete for ${bundle.id}: ${result.failedParts}")
                    _uiState.update {
                        it.copy(errorMessage = "Deleted ${bundle.id} partially: ${result.failedParts.joinToString(", ")} failed")
                    }
                }
                is DeleteResult.Failed -> {
                    Log.e(TAG, "Delete failed for ${bundle.id}: ${result.failedParts}")
                    _uiState.update {
                        it.copy(errorMessage = "Couldn't delete ${bundle.id}")
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        // Leaving the screen shouldn't abandon pending deletes — the user thinks they've
        // already deleted them. Flush on the application scope so the deletes survive
        // viewModelScope cancellation.
        val state = _uiState.value
        val bundlesById = state.bundles.associateBy { it.id }
        val toDelete = state.pendingDeletes.keys.mapNotNull { bundlesById[it] }
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        toDelete.forEach { bundle ->
            container.appScope.launch(Dispatchers.IO) {
                runCatching { container.bundleLibrary.deleteBundle(bundle) }
                    .onFailure { Log.w(TAG, "onCleared delete failed for ${bundle.id}", it) }
            }
        }
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ReconApp
                BundlePreviewViewModel(app, app.container)
            }
        }

        /**
         * Pure helper — testable. A bundle is selectable when it exists in the completed
         * list, isn't mid-countdown, and isn't still being processed by the worker.
         */
        fun isSelectable(bundleId: String, state: BundlePreviewUiState): Boolean =
            state.bundles.any { it.id == bundleId } &&
                bundleId !in state.pendingDeletes &&
                bundleId !in state.processingBundleIds
    }
}
