package com.example.bundlecam.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bundlecam.BuildConfig
import com.example.bundlecam.data.camera.CameraMode
import com.example.bundlecam.data.settings.MAX_DELETE_DELAY_SECONDS
import com.example.bundlecam.data.settings.MIN_DELETE_DELAY_SECONDS
import com.example.bundlecam.data.settings.StitchQuality
import com.example.bundlecam.di.AppContainer
import com.example.bundlecam.ui.common.rememberFolderPickerLauncher
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = null)

    val pickFolder = rememberFolderPickerLauncher { uri ->
        // Runs on the container's app-scoped coroutine so it survives the user popping
        // this screen before ensureBundleFolders finishes.
        container.configureRoot(uri)
    }

    val state = settings
    if (state == null) {
        Box(modifier = modifier.fillMaxSize())
        return
    }

    val folderName = remember(state.rootUri) {
        state.rootUri?.let { uri ->
            runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Done") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SettingBlock(label = "Output folder") {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folderName ?: state.rootUri?.toString() ?: "(none)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        state.rootUri?.takeIf { folderName != null }?.let { uri ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = uri.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = pickFolder) {
                        Text("Change")
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            SettingBlock(label = "Bundle output") {
                val individualLocked = state.saveIndividualPhotos && !state.saveStitchedImage
                val stitchedLocked = state.saveStitchedImage && !state.saveIndividualPhotos
                OutputToggleRow(
                    label = "Individual photos in subfolder",
                    checked = state.saveIndividualPhotos,
                    enabled = !individualLocked,
                    onCheckedChange = { on ->
                        scope.launch { container.settingsRepository.setSaveIndividualPhotos(on) }
                    },
                )
                Spacer(Modifier.height(4.dp))
                OutputToggleRow(
                    label = "Vertical stitched image",
                    checked = state.saveStitchedImage,
                    enabled = !stitchedLocked,
                    onCheckedChange = { on ->
                        scope.launch { container.settingsRepository.setSaveStitchedImage(on) }
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Stitch Quality",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        TextButton(
                            onClick = { menuOpen = true },
                            enabled = state.saveStitchedImage,
                        ) {
                            Text(state.stitchQuality.label)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            StitchQuality.entries.forEach { quality ->
                                DropdownMenuItem(
                                    text = { Text(quality.label) },
                                    onClick = {
                                        scope.launch {
                                            container.settingsRepository.setStitchQuality(quality)
                                        }
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (individualLocked || stitchedLocked) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "At least one output is required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            SettingsRow(label = "Shutter sound") {
                Switch(
                    checked = state.shutterSoundOn,
                    onCheckedChange = { on ->
                        scope.launch { container.settingsRepository.setShutterSoundOn(on) }
                    },
                )
            }

            Spacer(Modifier.height(12.dp))

            SettingsRow(label = "Camera mode") {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text(cameraModeLabel(state.cameraMode))
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        CameraMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(cameraModeLabel(mode)) },
                                onClick = {
                                    scope.launch {
                                        container.settingsRepository.setCameraMode(mode)
                                    }
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            SettingsRow(label = "Confirm before deleting a bundle") {
                Switch(
                    checked = state.deleteConfirmEnabled,
                    onCheckedChange = { on ->
                        scope.launch { container.settingsRepository.setDeleteConfirmEnabled(on) }
                    },
                )
            }

            Spacer(Modifier.height(12.dp))

            SettingsRow(label = "Undo window for bundle deletion") {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text(deleteDelayLabel(state.deleteDelaySeconds))
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        (MIN_DELETE_DELAY_SECONDS..MAX_DELETE_DELAY_SECONDS).forEach { secs ->
                            DropdownMenuItem(
                                text = { Text(deleteDelayLabel(secs)) },
                                onClick = {
                                    scope.launch {
                                        container.settingsRepository.setDeleteDelaySeconds(secs)
                                    }
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            SettingsRow(label = "Gesture tutorial") {
                TextButton(
                    onClick = {
                        // Write finishes before onBack so CaptureScreen's Flow has already
                        // seen the flip by the time it recomposes and the overlay appears
                        // without a visible lag.
                        scope.launch {
                            container.settingsRepository.setSeenGestureTutorial(false)
                            onBack()
                        }
                    },
                ) {
                    Text("Show")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            SettingBlock(label = "App version") {
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SettingBlock(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

private fun deleteDelayLabel(seconds: Int): String =
    if (seconds <= 0) "Off" else "${seconds}s"

private fun cameraModeLabel(mode: CameraMode): String = when (mode) {
    CameraMode.ZSL -> "ZSL (zero shutter lag)"
    CameraMode.Extensions -> "EXT (HDR/night auto)"
}

@Composable
private fun SettingsRow(
    label: String,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        control()
    }
}

@Composable
private fun OutputToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}
