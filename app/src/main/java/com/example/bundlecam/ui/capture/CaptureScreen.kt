package com.example.bundlecam.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bundlecam.data.camera.FlashMode
import com.example.bundlecam.ui.common.ActionBanner

@Composable
fun CaptureScreen(
    onOpenSettings: () -> Unit,
    onOpenBundles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        PermissionRequiredScreen(
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
        return
    }

    CaptureScreenContent(
        onOpenSettings = onOpenSettings,
        onOpenBundles = onOpenBundles,
        modifier = modifier,
    )
}

@Composable
private fun CaptureScreenContent(
    onOpenSettings: () -> Unit,
    onOpenBundles: () -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val vm: CaptureViewModel = viewModel(factory = CaptureViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cameraMode by vm.cameraMode.collectAsStateWithLifecycle()
    val lensFacing by vm.lensFacing.collectAsStateWithLifecycle()
    val flashMode by vm.flashMode.collectAsStateWithLifecycle()
    val modality by vm.modality.collectAsStateWithLifecycle()
    val zoomInfo by vm.zoomInfo.collectAsStateWithLifecycle()
    val deviceOrientation by vm.deviceOrientation.collectAsStateWithLifecycle()
    val contentRotation = rememberContentRotation(deviceOrientation)

    val mediaSound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(mediaSound) { onDispose { mediaSound.release() } }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* no-op; capture proceeds regardless */ }
    var locationAsked by rememberSaveable { mutableStateOf(false) }

    val voicePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.onVoicePermissionResult(granted) }
    val voicePermissionNeeded by vm.voicePermissionNeeded.collectAsStateWithLifecycle()
    LaunchedEffect(voicePermissionNeeded) {
        if (voicePermissionNeeded) {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Ask for GPS permission the first time the camera UI becomes available (after camera
    // permission is granted and the user is past the folder picker). We don't wait for the
    // first shutter press — getting consent up-front means the first capture can already
    // carry a GPS fix. If the user already granted, already permanently declined, or has
    // been asked this session, this is a no-op.
    LaunchedEffect(Unit) {
        if (!locationAsked && !vm.hasLocationPermission()) {
            locationAsked = true
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    // Stop any in-flight video/voice recording when the Activity pauses. The recorder's
    // Finalize event fires asynchronously and the awaiting coroutine resumes cleanly.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        vm.onLifecyclePaused()
    }

    val handleShutter = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (settings.shutterSoundOn) {
            mediaSound.play(MediaActionSound.SHUTTER_CLICK)
        }
        vm.onShutter()
    }

    val handleCommit = {
        // CONFIRM was added in API 30; fall back to CONTEXT_CLICK on 26–29.
        val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(haptic)
        vm.onCommitBundle()
    }

    val handleDiscard = {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        vm.onDiscardQueue()
    }

    var deleteProgress by remember { mutableFloatStateOf(0f) }
    var deleteHotspotXInRoot by remember { mutableFloatStateOf(0f) }
    val animatedDeleteProgress by animateFloatAsState(
        targetValue = deleteProgress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "delete-glow",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = Color.White,
                        modifier = Modifier.rotate(contentRotation),
                    )
                }
                Spacer(Modifier.weight(1f))
                ModalityPill(
                    current = modality,
                    enabledModalities = setOf(Modality.PHOTO, Modality.VIDEO, Modality.VOICE),
                    onChange = vm::setModality,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onOpenBundles,
                    enabled = settings.rootUri != null,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = "Bundles",
                        tint = if (settings.rootUri != null) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.rotate(contentRotation),
                    )
                }
            }

            val density = androidx.compose.ui.platform.LocalDensity.current
            val swipeThresholdDp = 64f
            val swipeVelocityThresholdDpPerSec = 300f
            // Track cumulative drag in pixels. onDragStopped reports release velocity
            // in px/s which we convert to dp/s for the pure ModalitySwipeMath.
            var accumulatedDragPx by remember { mutableFloatStateOf(0f) }
            // Modifier.draggable cooperates with nested tap/pinch detectors out of the
            // box: it waits for horizontal touch slop before claiming the pointer, and
            // during that window the child CameraPreview's detectTapGestures can still
            // fire a tap-to-focus (short press without drag). An earlier
            // `detectHorizontalDragGestures` setup inside a raw pointerInput block
            // never received events on device — the child's tap/pinch detectors
            // claimed the down event first.
            val dragState = rememberDraggableState { delta ->
                accumulatedDragPx += delta
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        enabled = state.busy == BusyState.Idle,
                        onDragStarted = {
                            accumulatedDragPx = 0f
                        },
                        onDragStopped = { velocityPx ->
                            val dragDp = with(density) { accumulatedDragPx.toDp().value }
                            val velocityDpPerSec = with(density) { velocityPx.toDp().value }
                            val target = ModalitySwipeMath.resolveTarget(
                                current = modality,
                                dragDp = dragDp,
                                velocityDpPerSecond = velocityDpPerSec,
                                thresholdDp = swipeThresholdDp,
                                velocityThresholdDpPerSecond = swipeVelocityThresholdDpPerSec,
                            )
                            if (target != modality) vm.setModality(target)
                            accumulatedDragPx = 0f
                        },
                    ),
            ) {
                CameraPreview(
                    controller = vm.captureController,
                    mode = cameraMode,
                    lens = lensFacing,
                    onRebindingChange = vm::setRebinding,
                    modifier = Modifier.fillMaxSize(),
                )
                // Voice modality overlay: covers the camera preview (which stays bound
                // so switching back is instant) with a dark scrim + mic glyph. Replaces
                // the waveform visualizer from the plan — MVP scope.
                if (modality == Modality.VOICE) {
                    VoiceOverlay(
                        recording = state.busy == BusyState.Recording,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ZoomControl(
                    zoomInfo = zoomInfo,
                    onZoomChange = vm::onZoomChange,
                    contentRotation = contentRotation,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = vm::onCycleFlash,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = flashIconFor(flashMode),
                            contentDescription = flashContentDescription(flashMode),
                            tint = if (flashMode == FlashMode.Off) Color.White.copy(alpha = 0.65f) else Color.White,
                            modifier = Modifier
                                .size(26.dp)
                                .rotate(contentRotation),
                        )
                    }
                    Spacer(Modifier.width(56.dp))
                    when (modality) {
                        Modality.PHOTO -> ShutterButton(
                            onClick = handleShutter,
                            enabled = state.busy == BusyState.Idle,
                        )
                        Modality.VIDEO -> VideoShutterButton(
                            onClick = handleShutter,
                            recording = state.busy == BusyState.Recording,
                            progressFraction = null,
                            enabled = state.busy == BusyState.Idle || state.busy == BusyState.Recording,
                        )
                        Modality.VOICE -> VoiceShutterButton(
                            onClick = handleShutter,
                            recording = state.busy == BusyState.Recording,
                            enabled = state.busy == BusyState.Idle || state.busy == BusyState.Recording,
                        )
                    }
                    Spacer(Modifier.width(56.dp))
                    val flipEnabled = state.busy == BusyState.Idle
                    IconButton(
                        onClick = vm::onToggleLens,
                        enabled = flipEnabled,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cameraswitch,
                            contentDescription = "Flip camera",
                            tint = if (flipEnabled) Color.White.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier
                                .size(26.dp)
                                .rotate(contentRotation),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    QueueStrip(
                        queue = state.queue,
                        dividers = state.dividers,
                        onCommit = handleCommit,
                        onDiscard = handleDiscard,
                        onDelete = vm::onDeleteOne,
                        onReorder = vm::onReorder,
                        onInsertDivider = vm::onInsertDivider,
                        onRemoveDivider = vm::onRemoveDivider,
                        onDeleteProgress = { progress, xInRoot ->
                            deleteProgress = progress
                            if (progress > 0f) deleteHotspotXInRoot = xInRoot
                        },
                    )
                    UndoToast(
                        pendingDiscard = state.pendingDiscard,
                        onUndo = vm::onUndoDiscard,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    BundleSavedShimmer(
                        events = vm.events,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    // Errors overlay the queue (taking the same slot as UndoToast/Shimmer)
                    // so they don't push the shutter + queue down when they appear.
                    // Declared last so they draw on top of any concurrent overlay.
                    state.lastError?.let { message ->
                        ActionBanner(
                            message = message,
                            actionLabel = "Dismiss",
                            onAction = vm::clearError,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }

        if (animatedDeleteProgress > 0.01f) {
            DeleteGlow(
                progress = animatedDeleteProgress,
                hotspotXInRoot = deleteHotspotXInRoot,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .height(18.dp),
            )
        }

        // First-run gesture tutorial overlay. Shown only until the user dismisses it;
        // declared last so it draws on top of the entire capture UI (including status +
        // navigation bar regions) and consumes all touches.
        GestureTutorial(
            seenStepIds = settings.seenTutorialSteps,
            onDismiss = vm::onDismissGestureTutorial,
        )
    }
}

@Composable
private fun rememberContentRotation(deviceOrientation: Int): Float {
    var cumulativeTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(deviceOrientation) {
        val newTarget = -deviceOrientation.toFloat()
        var delta = newTarget - cumulativeTarget
        delta = ((delta % 360f) + 540f) % 360f - 180f
        cumulativeTarget += delta
    }
    val animated by animateFloatAsState(
        targetValue = cumulativeTarget,
        animationSpec = tween(durationMillis = 300),
        label = "content-rotation",
    )
    return animated
}

private fun flashIconFor(mode: FlashMode) = when (mode) {
    FlashMode.Off -> Icons.Filled.FlashOff
    FlashMode.Auto -> Icons.Filled.FlashAuto
    FlashMode.On -> Icons.Filled.FlashOn
}

private fun flashContentDescription(mode: FlashMode) = when (mode) {
    FlashMode.Off -> "Flash off, tap for auto"
    FlashMode.Auto -> "Flash auto, tap for on"
    FlashMode.On -> "Flash on, tap for off"
}

@Composable
private fun PermissionRequiredScreen(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            Text("Settings", color = Color.White)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Camera access is required",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Recon needs the camera to capture photos. Grant permission to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) {
                Text("Grant camera permission")
            }
        }
    }
}
