package com.example.bundlecam.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bundlecam.BuildConfig
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
        scope.launch { container.configureRoot(uri) }
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
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = pickFolder) {
                    Text("Change")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            SettingBlock(label = "Stitch quality") {
                StitchQuality.entries.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch { container.settingsRepository.setStitchQuality(quality) }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.stitchQuality == quality,
                            onClick = null,
                        )
                        Text(quality.label)
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Shutter sound",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.shutterSoundOn,
                    onCheckedChange = { on ->
                        scope.launch { container.settingsRepository.setShutterSoundOn(on) }
                    },
                )
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
