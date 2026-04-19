package com.example.bundlecam

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bundlecam.di.AppContainer
import com.example.bundlecam.ui.capture.CaptureScreen
import com.example.bundlecam.ui.preview.BundlePreviewScreen
import com.example.bundlecam.ui.settings.SettingsScreen
import com.example.bundlecam.ui.setup.FolderPickerScreen
import com.example.bundlecam.ui.theme.ReconTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ReconApp).container
        setContent {
            ReconTheme {
                AppRoot(container)
            }
        }
    }
}

@Composable
private fun AppRoot(container: AppContainer) {
    val context = LocalContext.current
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = null)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showBundlePreview by rememberSaveable { mutableStateOf(false) }

    val state = settings
    val configuredUri: Uri? = remember(context, state?.rootUri) {
        state?.rootUri?.takeIf { hasPersistedReadWrite(context, it) }
    }

    BackHandler(enabled = showSettings) { showSettings = false }
    // Settings takes precedence in the when-chain below, so this handler only fires when
    // preview is the visible screen — no need to also check !showSettings here.
    BackHandler(enabled = showBundlePreview && !showSettings) { showBundlePreview = false }

    when {
        state == null -> Box(modifier = Modifier.fillMaxSize())

        configuredUri == null -> FolderPickerScreen(
            onFolderPicked = { uri -> container.configureRoot(uri) },
        )

        showSettings -> SettingsScreen(
            container = container,
            onBack = { showSettings = false },
        )

        showBundlePreview -> BundlePreviewScreen(
            onBack = { showBundlePreview = false },
        )

        else -> CaptureScreen(
            onOpenSettings = { showSettings = true },
            onOpenBundles = { showBundlePreview = true },
        )
    }
}

private fun hasPersistedReadWrite(context: Context, uri: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission && it.isWritePermission
    }
