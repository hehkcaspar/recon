package com.example.bundlecam.ui.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bundlecam.ReconApp
import com.example.bundlecam.data.settings.SettingsState
import com.example.bundlecam.data.storage.CompletedBundle
import com.example.bundlecam.ui.common.ActionBanner
import com.example.bundlecam.ui.common.openFolderInSystemBrowser

private const val PROCESSING_ROW_KEY_PREFIX = "processing:"

@Composable
fun BundlePreviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vm: BundlePreviewViewModel = viewModel(factory = BundlePreviewViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    LaunchedEffect(settings.rootUri) { vm.refresh() }

    var sendBundles by remember { mutableStateOf<List<CompletedBundle>>(emptyList()) }

    BundlePreviewContent(
        state = state,
        settings = settings,
        canSendSelected = vm.selectedBundlesSnapshot().isNotEmpty(),
        onBack = onBack,
        onOpenFolder = {
            settings.rootUri?.let { openFolderInSystemBrowser(context, it) }
        },
        onClearSelection = vm::clearSelection,
        onSendSelected = { sendBundles = vm.selectedBundlesSnapshot() },
        onRequestThumbnail = vm::loadThumbnail,
        onConfirmDelete = vm::onConfirmDelete,
        onUndo = vm::onUndo,
        onToggleSelection = vm::toggleSelection,
        onClearError = vm::clearError,
        isTransferSheetOpen = sendBundles.isNotEmpty(),
        modifier = modifier,
    )

    if (sendBundles.isNotEmpty()) {
        val app = context.applicationContext as ReconApp
        LocalSendSheet(
            container = app.container,
            bundles = sendBundles,
            onDismiss = {
                sendBundles = emptyList()
                vm.clearSelection()
            },
        )
    }
}

/**
 * Stateless, testable Bundle Library UI surface implementing `docs/prototypes-app-ui/INTERACTIONS.md`:
 * - `560dp` max column width centered in the usable pane.
 * - `ProcessingBundleRow`, ready `BundleRow`, and `PendingDeleteRow` with Undo.
 * - Right-swipe selection reveal, left-swipe delete, trailing `More` menu, and custom
 *   accessibility actions.
 * - Back handler clears active confirm dialog first, then clears selection before leaving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundlePreviewContent(
    state: BundlePreviewUiState,
    settings: SettingsState,
    canSendSelected: Boolean,
    onBack: () -> Unit,
    onOpenFolder: () -> Unit,
    onClearSelection: () -> Unit,
    onSendSelected: () -> Unit,
    onRequestThumbnail: (android.net.Uri) -> Unit,
    onConfirmDelete: (String) -> Unit,
    onUndo: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onClearError: () -> Unit,
    isTransferSheetOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var confirmDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = state.selectionMode && !isTransferSheetOpen && confirmDeleteId == null) {
        onClearSelection()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    selectedCount = state.selectedBundleIds.size,
                    canSend = canSendSelected,
                    onClose = onClearSelection,
                    onSend = onSendSelected,
                )
            } else {
                TopAppBar(
                    title = { Text("Bundles") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onOpenFolder,
                            enabled = settings.rootUri != null,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = "Open in system file browser",
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val completedIds = state.bundles.mapTo(mutableSetOf()) { it.id }
            val hasContent = state.bundles.isNotEmpty() ||
                state.processingBundleIds.any { it !in completedIds }

            when {
                state.loadState == LoadState.Loading && !hasContent -> {
                    CenteredProgress()
                }
                state.loadState == LoadState.Error && !hasContent -> {
                    EmptyMessage(
                        title = "Couldn't load bundles",
                        subtitle = state.errorMessage ?: "Try returning to capture and back.",
                    )
                }
                !hasContent -> {
                    EmptyMessage(
                        title = "No bundles yet",
                        subtitle = "Capture some photos and swipe to commit a bundle.",
                    )
                }
                else -> {
                    BundleList(
                        processingIds = state.processingBundleIds.toSet(),
                        bundles = state.bundles,
                        pendingDeletes = state.pendingDeletes,
                        selectedIds = state.selectedBundleIds,
                        thumbnails = state.thumbnails,
                        onRequestThumbnail = onRequestThumbnail,
                        onRequestDelete = { id ->
                            if (settings.deleteConfirmEnabled) {
                                confirmDeleteId = id
                            } else {
                                onConfirmDelete(id)
                            }
                        },
                        onUndo = onUndo,
                        onToggleSelection = onToggleSelection,
                        modifier = Modifier.widthIn(max = 560.dp),
                    )
                }
            }

            state.errorMessage?.takeIf { state.bundles.isNotEmpty() }?.let { msg ->
                ActionBanner(
                    message = msg,
                    actionLabel = "Dismiss",
                    onAction = onClearError,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = 560.dp)
                        .padding(16.dp),
                )
            }
        }
    }

    confirmDeleteId?.let { id ->
        val bundle = state.bundles.firstOrNull { it.id == id }
        if (bundle == null) {
            confirmDeleteId = null
            return@let
        }
        DeleteConfirmDialog(
            bundle = bundle,
            settings = settings,
            onConfirm = {
                onConfirmDelete(id)
                confirmDeleteId = null
            },
            onDismiss = { confirmDeleteId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    canSend: Boolean,
    onClose: () -> Unit,
    onSend: () -> Unit,
) {
    TopAppBar(
        title = { Text("$selectedCount selected") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear selection",
                )
            }
        },
        actions = {
            IconButton(onClick = onSend, enabled = canSend) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send via LocalSend",
                )
            }
        },
    )
}

@Composable
private fun BundleList(
    processingIds: Set<String>,
    bundles: List<CompletedBundle>,
    pendingDeletes: Map<String, PendingDelete>,
    selectedIds: Set<String>,
    thumbnails: Map<android.net.Uri, androidx.compose.ui.graphics.ImageBitmap>,
    onRequestThumbnail: (android.net.Uri) -> Unit,
    onRequestDelete: (String) -> Unit,
    onUndo: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val completedIds = bundles.mapTo(mutableSetOf()) { it.id }
    val processingOnly = processingIds.filterNot { it in completedIds }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxSize(),
    ) {
        items(
            items = processingOnly,
            key = { id -> "$PROCESSING_ROW_KEY_PREFIX$id" },
        ) { id ->
            ProcessingBundleRow(bundleId = id)
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
            )
        }
        items(items = bundles, key = { it.id }) { bundle ->
            BundleRow(
                bundle = bundle,
                thumbnails = thumbnails,
                pendingDelete = pendingDeletes[bundle.id],
                selected = bundle.id in selectedIds,
                processing = bundle.id in processingIds,
                onRequestThumbnail = onRequestThumbnail,
                onRequestDelete = { onRequestDelete(bundle.id) },
                onUndo = { onUndo(bundle.id) },
                onToggleSelection = { onToggleSelection(bundle.id) },
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
            )
        }
    }
}

@Composable
private fun CenteredProgress() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyMessage(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    bundle: CompletedBundle,
    settings: SettingsState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val parts = buildList {
        if (bundle.photoCount > 0) add("the photos")
        if (bundle.videoCount > 0) add("the videos")
        if (bundle.voiceCount > 0) add("the voice notes")
        if (bundle.stitchUri != null) add("the stitched image")
    }
    val target = when (parts.size) {
        0 -> "this bundle"
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    val suffix = if (settings.deleteDelaySeconds > 0) {
        " You'll have ${settings.deleteDelaySeconds} seconds to undo."
    } else {
        " This cannot be undone."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete bundle?") },
        text = { Text("This will delete $target for ${bundle.id}.$suffix") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
