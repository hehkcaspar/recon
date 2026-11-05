package com.example.bundlecam

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowLayoutInfo
import com.example.bundlecam.di.AppContainer
import com.example.bundlecam.ui.capture.BusyState
import com.example.bundlecam.ui.capture.CaptureScreen
import com.example.bundlecam.ui.capture.window.InsetsPx
import com.example.bundlecam.ui.capture.window.RawHostEvent
import com.example.bundlecam.ui.capture.window.SurfaceRole
import com.example.bundlecam.ui.capture.window.WindowObservation
import com.example.bundlecam.ui.capture.window.WindowObservationAdapter
import com.example.bundlecam.ui.preview.BundlePreviewScreen
import com.example.bundlecam.ui.settings.SettingsScreen
import com.example.bundlecam.ui.setup.FolderPickerScreen
import com.example.bundlecam.ui.theme.ReconTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var latestWindowLayoutInfo: WindowLayoutInfo? = null
    private var latestSafeDrawingInsets: InsetsPx = InsetsPx.Zero
    private var latestSafeGestureInsets: InsetsPx = InsetsPx.Zero

    @Volatile
    var injectedSurfaceRole: SurfaceRole? = null

    @Volatile
    var allowOrientationOverrideForTest: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyAdaptiveOrientationPolicy(resources.configuration)
        val container = (application as ReconApp).container

        val initialObs = buildCurrentObservation()
        container.captureTakeCoordinator.seedWindowObservation(initialObs)

        lifecycleScope.launch {
            WindowObservationAdapter.observeWindowLayoutInfo(this@MainActivity).collect { info ->
                latestWindowLayoutInfo = info
                applyAdaptiveOrientationPolicy(resources.configuration)
                val obs = buildCurrentObservation(info)
                container.captureTakeCoordinator.onHostEvent(RawHostEvent.WindowChanged(obs))
            }
        }

        setContent {
            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val safeDrawing = WindowInsets.safeDrawing
            val safeGestures = WindowInsets.safeGestures
            val drawingPx = InsetsPx(
                left = safeDrawing.getLeft(density, layoutDirection),
                top = safeDrawing.getTop(density),
                right = safeDrawing.getRight(density, layoutDirection),
                bottom = safeDrawing.getBottom(density),
            )
            val gesturePx = InsetsPx(
                left = safeGestures.getLeft(density, layoutDirection),
                top = safeGestures.getTop(density),
                right = safeGestures.getRight(density, layoutDirection),
                bottom = safeGestures.getBottom(density),
            )
            LaunchedEffect(drawingPx, gesturePx) {
                if (drawingPx != latestSafeDrawingInsets || gesturePx != latestSafeGestureInsets) {
                    latestSafeDrawingInsets = drawingPx
                    latestSafeGestureInsets = gesturePx
                    val obs = buildCurrentObservation()
                    container.captureTakeCoordinator.onHostEvent(RawHostEvent.WindowChanged(obs))
                }
            }

            ReconTheme {
                AppRoot(container)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAdaptiveOrientationPolicy(newConfig)
        val container = (application as ReconApp).container
        val obs = buildCurrentObservation()
        lifecycleScope.launch {
            container.captureTakeCoordinator.onHostEvent(RawHostEvent.ConfigurationChanged(obs))
        }
    }

    /**
     * Traditional phones and folded cover screens (`smallestScreenWidthDp < 600`) keep a
     * fixed portrait window layout and rotate only their icons in place via
     * `OrientationEventListener`. Unfolded inner screens (`smallestScreenWidthDp >= 600`)
     * unlock orientation so Design C reflows between Upright (vertical queue) and CW90
     * (horizontal queue below preview).
     */
    private fun applyAdaptiveOrientationPolicy(config: Configuration) {
        if (allowOrientationOverrideForTest) return
        val isUnfoldedInner = config.smallestScreenWidthDp >= 600 &&
            injectedSurfaceRole != SurfaceRole.Cover &&
            injectedSurfaceRole != SurfaceRole.Regular
        val desired = if (isUnfoldedInner) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (requestedOrientation != desired) {
            requestedOrientation = desired
        }
    }

    override fun onPause() {
        super.onPause()
        val container = (application as ReconApp).container
        lifecycleScope.launch {
            container.captureTakeCoordinator.onHostEvent(RawHostEvent.ActivityPaused)
        }
    }

    override fun onStop() {
        super.onStop()
        val container = (application as ReconApp).container
        val changingConfig = isChangingConfigurations
        container.appScope.launch {
            container.captureTakeCoordinator.onHostEvent(
                RawHostEvent.ActivityStopped(isChangingConfigurations = changingConfig),
            )
        }
    }

    fun injectWindowObservationForTest(observation: WindowObservation) {
        val container = (application as ReconApp).container
        lifecycleScope.launch {
            container.captureTakeCoordinator.onHostEvent(RawHostEvent.WindowChanged(observation))
        }
    }

    private fun buildCurrentObservation(
        layoutInfo: WindowLayoutInfo? = latestWindowLayoutInfo,
    ): WindowObservation = WindowObservationAdapter.fromWindowLayoutInfo(
        activity = this,
        layoutInfo = layoutInfo,
        safeDrawingInsetsPx = latestSafeDrawingInsets,
        safeGestureInsetsPx = latestSafeGestureInsets,
        injectedSurfaceRole = injectedSurfaceRole,
    )
}

@Composable
private fun AppRoot(container: AppContainer) {
    val context = LocalContext.current
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = null)
    val coordinatorState by container.captureTakeCoordinator.coordinatorState
        .collectAsStateWithLifecycle()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showBundlePreview by rememberSaveable { mutableStateOf(false) }
    // Incremented on resume and after folder pick so persisted URI grants are rechecked
    // even when the URI string itself did not change.
    var permissionCheckEpoch by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionCheckEpoch++
    }

    val state = settings
    val rememberedUri: Uri? = state?.rootUri
    val configuredUri: Uri? = remember(context, rememberedUri, permissionCheckEpoch) {
        rememberedUri?.takeIf { hasPersistedReadWrite(context, it) }
    }
    val grantRevoked = rememberedUri != null && configuredUri == null
    val isRecordingLocked = coordinatorState.busyState == BusyState.Recording ||
        coordinatorState.busyState == BusyState.Capturing

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showBundlePreview && !showSettings) { showBundlePreview = false }

    val inCaptureRoute = state != null && configuredUri != null && !showSettings && !showBundlePreview
    DisposableEffect(inCaptureRoute) {
        onDispose {
            if (inCaptureRoute) {
                container.appScope.launch {
                    container.captureTakeCoordinator.onHostEvent(RawHostEvent.RouteExited)
                }
            }
        }
    }

    when {
        state == null -> Box(modifier = Modifier.fillMaxSize())

        configuredUri == null -> FolderPickerScreen(
            onFolderPicked = { uri ->
                container.configureRoot(uri)
                permissionCheckEpoch++
            },
            revokedUriLabel = if (grantRevoked) rememberedUri?.lastPathSegment ?: rememberedUri?.toString() else null,
        )

        showSettings -> SettingsScreen(
            container = container,
            onBack = { showSettings = false },
        )

        showBundlePreview -> BundlePreviewScreen(
            onBack = { showBundlePreview = false },
        )

        else -> CaptureScreen(
            onOpenSettings = {
                if (!isRecordingLocked) {
                    showSettings = true
                }
            },
            onOpenBundles = {
                if (!isRecordingLocked) {
                    showBundlePreview = true
                }
            },
        )
    }
}

private fun hasPersistedReadWrite(context: Context, uri: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission && it.isWritePermission
    }
