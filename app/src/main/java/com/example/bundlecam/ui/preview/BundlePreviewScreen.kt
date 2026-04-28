package com.example.bundlecam.ui.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundlePreviewScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vm: BundlePreviewViewModel = viewModel(factory = BundlePreviewViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    // Refresh every time the screen is composed — cheap given we only list two SAF
    // directories, and this covers the "user saved a bundle then navigated back" case
    // without plumbing a cross-VM bundle-saved event.
    LaunchedEffect(settings.rootUri) { vm.refresh() }

    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var sendBundles by remember { mutableStateOf<List<CompletedBundle>>(emptyList()) }

    // Back press in selection mode is "exit selection mode", not "leave the screen" —
    // standard M3 multi-select behavior. Outside selection mode, the existing screen-
    // level BackHandler in MainActivity takes over.
    BackHandler(enabled = state.selectionMode && sendBundles.isEmpty()) { vm.clearSelection() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    selectedCount = state.selectedBundleIds.size,
                    canSend = vm.selectedBundlesSnapshot().isNotEmpty(),
                    onClose = { vm.clearSelection() },
                    onSend = { sendBundles = vm.selectedBundlesSnapshot() },
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
                            onClick = {
                                settings.rootUri?.let { openFolderInSystemBrowser(context, it) }
                            },
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
        ) {
            // A row is "processing" if its worker is in-flight AND no SAF files exist for
            // it yet — once the subfolder or stitch lands, the completed row takes over.
            val completedIds = state.bundles.mapTo(mutableSetOf()) { it.id }
            val processingOnly = state.processingBundleIds.filterNot { it in completedIds }
            val hasContent = state.bundles.isNotEmpty() || processingOnly.isNotEmpty()

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
                        processingIds = processingOnly,
                        bundles = state.bundles,
                        pendingDeletes = state.pendingDeletes,
                        selectedIds = state.selectedBundleIds,
                        thumbnails = state.thumbnails,
                        onRequestThumbnail = vm::loadThumbnail,
                        onRequestDelete = { id ->
                            if (settings.deleteConfirmEnabled) {
                                confirmDeleteId = id
                            } else {
                                vm.onConfirmDelete(id)
                            }
                        },
                        onUndo = vm::onUndo,
                        onToggleSelection = vm::toggleSelection,
                    )
                }
            }

            state.errorMessage?.takeIf { state.bundles.isNotEmpty() }?.let { msg ->
                ActionBanner(
                    message = msg,
                    actionLabel = "Dismiss",
                    onAction = vm::clearError,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
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
                vm.onConfirmDelete(id)
                confirmDeleteId = null
            },
            onDismiss = { confirmDeleteId = null },
        )
    }

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
                    contentDescription = "Exit selection",
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
    processingIds: List<String>,
    bundles: List<CompletedBundle>,
    pendingDeletes: Map<String, PendingDelete>,
    selectedIds: Set<String>,
    thumbnails: Map<android.net.Uri, androidx.compose.ui.graphics.ImageBitmap>,
    onRequestThumbnail: (android.net.Uri) -> Unit,
    onRequestDelete: (String) -> Unit,
    onUndo: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = processingIds,
            // Prefix the key so a processing ID can't collide with a completed bundle
            // that shares the same id during the brief SAF-write → refresh transition.
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
    // Build a human-readable target description from the per-modality counts. "Subfolder"
    // in BundleModality now covers photo/video/voice uniformly; the old "the photos
    // subfolder" phrasing was misleading for video- or voice-only bundles.
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
