package com.example.bundlecam.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bundlecam.data.camera.CameraCapability
import com.example.bundlecam.data.camera.CameraMode
import com.example.bundlecam.data.camera.CameraSessionContract
import com.example.bundlecam.data.camera.CameraSessionState
import com.example.bundlecam.data.camera.FlashMode
import com.example.bundlecam.data.camera.LensFacing
import com.example.bundlecam.data.camera.ZoomInfo
import com.example.bundlecam.data.settings.SettingsState
import com.example.bundlecam.ui.capture.window.InsetsPx
import com.example.bundlecam.ui.capture.window.SurfaceRole
import com.example.bundlecam.ui.capture.window.WindowObservation
import com.example.bundlecam.ui.common.ActionBanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sign

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

    // Recheck camera permission on resume (e.g. after returning from Android Settings).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

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
    val vm: CaptureViewModel = viewModel(factory = CaptureViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cameraState by vm.cameraState.collectAsStateWithLifecycle()
    val coordinatorState by vm.coordinatorState.collectAsStateWithLifecycle()
    val lensFacing by vm.lensFacing.collectAsStateWithLifecycle()
    val flashMode by vm.flashMode.collectAsStateWithLifecycle()
    val videoAudioEnabled by vm.videoAudioEnabled.collectAsStateWithLifecycle()
    val modality by vm.modality.collectAsStateWithLifecycle()
    val recordingStartedAtMs by vm.recordingStartedAtMs.collectAsStateWithLifecycle()
    val zoomInfo by vm.zoomInfo.collectAsStateWithLifecycle()
    val deviceOrientation by vm.deviceOrientation.collectAsStateWithLifecycle()
    val micPermissionNeeded by vm.micPermissionNeeded.collectAsStateWithLifecycle()

    val mediaSound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(mediaSound) { onDispose { mediaSound.release() } }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* no-op; capture proceeds regardless */ }
    var locationAsked by rememberSaveable { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.onMicPermissionResult(granted) }
    LaunchedEffect(micPermissionNeeded) {
        if (micPermissionNeeded) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

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

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        vm.onLifecyclePaused()
    }

    CaptureScreenShell(
        uiState = state,
        settings = settings,
        cameraState = cameraState,
        windowObservation = coordinatorState.lastObservation,
        lensFacing = lensFacing,
        flashMode = flashMode,
        videoAudioEnabled = videoAudioEnabled,
        modality = modality,
        recordingStartedAtMs = recordingStartedAtMs,
        zoomInfo = zoomInfo,
        deviceOrientation = deviceOrientation,
        voiceAmplitudeFlow = vm.voiceAmplitudeFlow,
        videoAudioAmplitudeFlow = vm.videoAudioAmplitudeFlow,
        events = vm.events,
        cameraSession = vm.cameraSession,
        onPlayPhotoShutterSound = { mediaSound.play(MediaActionSound.SHUTTER_CLICK) },
        onShutter = vm::onShutter,
        onSetModality = vm::setModality,
        onToggleLens = vm::onToggleLens,
        onCycleFlash = vm::onCycleFlash,
        onToggleVideoAudio = vm::onToggleVideoAudio,
        onZoomChange = vm::onZoomChange,
        onRebindingChange = vm::setRebinding,
        onCommitBundle = vm::onCommitBundle,
        onDiscardQueue = vm::onDiscardQueue,
        onUndoDiscard = vm::onUndoDiscard,
        onDeleteOne = vm::onDeleteOne,
        onReorder = vm::onReorder,
        onInsertDivider = vm::onInsertDivider,
        onRemoveDivider = vm::onRemoveDivider,
        onClearError = vm::clearError,
        onDismissTutorial = vm::onDismissGestureTutorial,
        onOpenSettings = onOpenSettings,
        onOpenBundles = onOpenBundles,
        modifier = modifier,
    )
}

/**
 * Pure coordinate-driven Compose capture shell consuming [CaptureGeometry].
 * Can be composed directly in instrumentation/Compose tests with an injected
 * [windowObservation] and fake [cameraSession] (or `renderCameraPreview = false`).
 */
@Composable
fun CaptureScreenShell(
    uiState: CaptureUiState,
    settings: SettingsState,
    cameraState: CameraSessionState,
    windowObservation: WindowObservation?,
    lensFacing: LensFacing,
    flashMode: FlashMode,
    videoAudioEnabled: Boolean,
    modality: Modality,
    recordingStartedAtMs: Long?,
    zoomInfo: ZoomInfo?,
    deviceOrientation: Int,
    voiceAmplitudeFlow: StateFlow<Int> = MutableStateFlow(0),
    videoAudioAmplitudeFlow: StateFlow<Float> = MutableStateFlow(0f),
    events: Flow<CaptureEvent> = emptyFlow(),
    cameraSession: CameraSessionContract? = null,
    renderCameraPreview: Boolean = cameraSession != null,
    onPlayPhotoShutterSound: () -> Unit = {},
    onShutter: () -> Unit,
    onSetModality: (Modality) -> Unit,
    onToggleLens: () -> Unit,
    onCycleFlash: () -> Unit,
    onToggleVideoAudio: () -> Unit,
    onZoomChange: (Float) -> Unit,
    onRebindingChange: (Boolean) -> Unit = {},
    onCommitBundle: () -> Unit,
    onDiscardQueue: () -> Unit,
    onUndoDiscard: () -> Unit,
    onDeleteOne: (String) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onInsertDivider: (Int) -> Unit,
    onRemoveDivider: (Int) -> Unit,
    onClearError: () -> Unit,
    onDismissTutorial: (Set<String>) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBundles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeDrawing = WindowInsets.safeDrawing
    val safeGestures = WindowInsets.safeGestures

    val isRecording = uiState.busy == BusyState.Recording
    val isRecordingOrCapturing = uiState.busy != BusyState.Idle
    val contentRotation = rememberContentRotation(
        deviceOrientation = deviceOrientation,
        activeSurfaceRotation = windowObservation?.displayRotation ?: cameraState.activeSurfaceRotation,
    )

    DisposableEffect(isRecording) {
        view.keepScreenOn = isRecording
        onDispose { view.keepScreenOn = false }
    }

    val handleShutter = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (ShutterSoundPolicy.shouldPlayPhotoShutterClick(
                modality = modality,
                shutterSoundOn = settings.shutterSoundOn,
                busy = uiState.busy,
            )
        ) {
            onPlayPhotoShutterSound()
        }
        onShutter()
    }

    val handleCommit = {
        val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(haptic)
        onCommitBundle()
    }

    val handleDiscard = {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onDiscardQueue()
    }

    var deleteProgress by remember { mutableFloatStateOf(0f) }
    var deleteHotspotInRoot by remember { mutableFloatStateOf(0f) }
    val animatedDeleteProgress by animateFloatAsState(
        targetValue = deleteProgress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "delete-glow",
    )

    var settledDockSide by remember { mutableStateOf<DockSide?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF080808)),
    ) {
        val measuredW = constraints.maxWidth.coerceAtLeast(1)
        val measuredH = constraints.maxHeight.coerceAtLeast(1)
        val drawingInsetsPx = InsetsPx(
            left = safeDrawing.getLeft(density, layoutDirection),
            top = safeDrawing.getTop(density),
            right = safeDrawing.getRight(density, layoutDirection),
            bottom = safeDrawing.getBottom(density),
        )
        val gestureInsetsPx = InsetsPx(
            left = safeGestures.getLeft(density, layoutDirection),
            top = safeGestures.getTop(density),
            right = safeGestures.getRight(density, layoutDirection),
            bottom = safeGestures.getBottom(density),
        )

        val effectiveObservation = remember(
            windowObservation,
            measuredW,
            measuredH,
            density.density,
            density.fontScale,
            drawingInsetsPx,
            gestureInsetsPx,
        ) {
            if (windowObservation != null) {
                windowObservation
            } else {
                WindowObservation(
                    windowWidthPx = measuredW,
                    windowHeightPx = measuredH,
                    density = density.density,
                    fontScale = density.fontScale,
                    safeDrawingInsetsPx = drawingInsetsPx,
                    safeGestureInsetsPx = gestureInsetsPx,
                    displayRotation = Surface.ROTATION_0,
                    surfaceRole = SurfaceRole.Unknown,
                )
            }
        }

        val geometry = remember(
            effectiveObservation,
            modality,
            lensFacing,
            cameraState.streamGeometry,
            settledDockSide,
        ) {
            CaptureGeometryCalculator.compute(
                CaptureGeometryInput(
                    observation = effectiveObservation,
                    modality = modality,
                    lensFacing = lensFacing,
                    streamGeometry = cameraState.streamGeometry,
                    settledDockSide = settledDockSide,
                ),
            )
        }

        LaunchedEffect(geometry.dockSide) {
            settledDockSide = geometry.dockSide
        }

        val swipeThresholdDp = 64f
        val swipeVelocityThresholdDpPerSec = 300f
        val slabOffset = remember { Animatable(0f) }
        val swipeScope = rememberCoroutineScope()
        val slabWidthPx = geometry.previewBounds.width.coerceAtLeast(1f)

        val dragState = rememberDraggableState { delta ->
            swipeScope.launch { slabOffset.snapTo(slabOffset.value + delta) }
        }

        // 1. Preview & Voice Overlay Region
        Box(
            modifier = Modifier
                .placeInWindow(geometry.previewBounds, density)
                .clipToBounds()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = uiState.busy == BusyState.Idle,
                    onDragStarted = {
                        swipeScope.launch { slabOffset.snapTo(0f) }
                    },
                    onDragStopped = { velocityPx ->
                        val dragDp = with(density) { slabOffset.value.toDp().value }
                        val velocityDpPerSec = with(density) { velocityPx.toDp().value }
                        val target = ModalitySwipeMath.resolveTarget(
                            current = modality,
                            dragDp = dragDp,
                            velocityDpPerSecond = velocityDpPerSec,
                            thresholdDp = swipeThresholdDp,
                            velocityThresholdDpPerSecond = swipeVelocityThresholdDpPerSec,
                        )
                        val committed = target != modality
                        if (committed) onSetModality(target)
                        swipeScope.launch {
                            if (committed) {
                                val targetOffset = slabWidthPx * sign(slabOffset.value)
                                slabOffset.animateTo(
                                    targetValue = targetOffset,
                                    animationSpec = tween(
                                        durationMillis = 180,
                                        easing = FastOutLinearInEasing,
                                    ),
                                    initialVelocity = velocityPx,
                                )
                                slabOffset.snapTo(0f)
                            } else {
                                slabOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                    ),
                                    initialVelocity = velocityPx,
                                )
                            }
                        }
                    },
                )
                .graphicsLayer { translationX = slabOffset.value },
        ) {
            if (renderCameraPreview && cameraSession != null) {
                CameraPreview(
                    session = cameraSession,
                    mode = settings.cameraMode,
                    lens = lensFacing,
                    onRebindingChange = onRebindingChange,
                    gesturesEnabled = modality != Modality.VOICE && !isRecordingOrCapturing,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = if (modality == Modality.VOICE) 0f else 1f
                        },
                )
            }
            if (modality == Modality.VOICE) {
                VoiceOverlay(
                    recording = isRecording,
                    amplitudeFlow = voiceAmplitudeFlow,
                    recordingStartedAtMs = recordingStartedAtMs,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 2. Contrast Scrims when primary controls intrude over the preview edge
        if (geometry.controlsOverlayPreview && modality != Modality.VOICE) {
            if (geometry.dockSide == DockSide.Right) {
                val scrimLeftPx = (minOf(geometry.controlGroup.bounds.x, geometry.modeBarBounds.x) -
                    with(density) { 32.dp.toPx() }).coerceAtLeast(geometry.previewBounds.x)
                val scrimRect = RectPx(
                    x = scrimLeftPx,
                    y = geometry.usablePaneBounds.y,
                    width = (geometry.usablePaneBounds.right - scrimLeftPx).coerceAtLeast(0f),
                    height = geometry.usablePaneBounds.height,
                )
                Box(
                    modifier = Modifier
                        .placeInWindow(scrimRect, density)
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                0.35f to Color.Black.copy(alpha = 0.68f),
                                1f to Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                )
            } else {
                val scrimTopPx = (geometry.controlGroup.zoomBounds.y - with(density) { 48.dp.toPx() })
                    .coerceAtLeast(0f)
                val scrimRect = RectPx(
                    x = geometry.usablePaneBounds.x,
                    y = scrimTopPx,
                    width = geometry.usablePaneBounds.width,
                    height = (geometry.usablePaneBounds.bottom - scrimTopPx).coerceAtLeast(0f),
                )
                Box(
                    modifier = Modifier
                        .placeInWindow(scrimRect, density)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.32f to Color.Black.copy(alpha = 0.72f),
                                1f to Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                )
            }
        }
        if (geometry.showTopHeaderScrim && modality != Modality.VOICE) {
            val headerScrimHeightPx = maxOf(
                with(density) { 80.dp.toPx() },
                (geometry.settingsButtonBounds.bottom - geometry.usablePaneBounds.y) +
                    with(density) { 12.dp.toPx() },
            )
            val headerScrimRect = RectPx(
                x = geometry.usablePaneBounds.x,
                y = geometry.usablePaneBounds.y,
                width = geometry.usablePaneBounds.width,
                height = headerScrimHeightPx,
            )
            Box(
                modifier = Modifier
                    .placeInWindow(headerScrimRect, density)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.72f),
                            0.75f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }
        if (geometry.showLeftUtilityScrim && modality != Modality.VOICE) {
            val leftScrimRect = RectPx(
                x = geometry.usablePaneBounds.x,
                y = geometry.usablePaneBounds.y,
                width = with(density) { 80.dp.toPx() },
                height = geometry.usablePaneBounds.height,
            )
            Box(
                modifier = Modifier
                    .placeInWindow(leftScrimRect, density)
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.72f),
                            0.75f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }

        // 3. Utility Buttons: Settings & Bundles (48 × 48dp targets, 24dp glyphs, locked while recording)
        val navEnabled = !isRecordingOrCapturing
        val bundlesEnabled = navEnabled && settings.rootUri != null
        IconButton(
            onClick = onOpenSettings,
            enabled = navEnabled,
            modifier = Modifier
                .placeInWindow(geometry.settingsButtonBounds, density)
                .semantics {
                    contentDescription = "Settings"
                    if (!navEnabled) disabled()
                },
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                tint = if (navEnabled) Color.White else Color.White.copy(alpha = 0.38f),
                modifier = Modifier
                    .size(24.dp)
                    .rotate(contentRotation),
            )
        }

        IconButton(
            onClick = onOpenBundles,
            enabled = bundlesEnabled,
            modifier = Modifier
                .placeInWindow(geometry.bundlesButtonBounds, density)
                .semantics {
                    contentDescription = "Bundles"
                    if (!bundlesEnabled) disabled()
                },
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoLibrary,
                contentDescription = null,
                tint = if (bundlesEnabled) Color.White else Color.White.copy(alpha = 0.38f),
                modifier = Modifier
                    .size(24.dp)
                    .rotate(contentRotation),
            )
        }

        // 4. Mode Strip (`VIDEO · PHOTO · VOICE`)
        val capability = cameraState.capability
        val enabledModalities = remember(isRecordingOrCapturing, capability) {
            if (isRecordingOrCapturing) {
                emptySet()
            } else {
                buildSet {
                    add(Modality.PHOTO)
                    if (capability !is CameraCapability.PhotoOnly) {
                        add(Modality.VIDEO)
                    }
                    add(Modality.VOICE)
                }
            }
        }
        val unavailableReasons = remember(capability) {
            if (capability is CameraCapability.PhotoOnly) {
                mapOf(Modality.VIDEO to capability.reason)
            } else {
                emptyMap()
            }
        }
        val modeStripWidthDp = (geometry.modeBarBounds.width / geometry.density).dp
        Box(
            modifier = Modifier.placeInWindow(
                geometry.modeBarBounds.copy(height = 48f * geometry.density),
                density,
            ),
            contentAlignment = Alignment.Center,
        ) {
            ModalityPill(
                current = modality,
                enabledModalities = enabledModalities,
                unavailableReasons = unavailableReasons,
                stripWidth = modeStripWidthDp,
                onChange = onSetModality,
            )
        }

        // 5. Zoom Control (omitted completely in VOICE so no hit or semantic targets remain)
        if (modality != Modality.VOICE) {
            Box(
                modifier = Modifier.placeInWindow(
                    geometry.controlGroup.zoomBounds.copy(
                        x = geometry.controlGroup.zoomBounds.x - 4f * geometry.density,
                        y = geometry.controlGroup.zoomBounds.y - 4f * geometry.density,
                        width = geometry.controlGroup.zoomBounds.width + 8f * geometry.density,
                        height = 48f * geometry.density,
                    ),
                    density,
                ),
                contentAlignment = Alignment.Center,
            ) {
                ZoomControl(
                    zoomInfo = zoomInfo,
                    onZoomChange = onZoomChange,
                    contentRotation = contentRotation,
                    singlePresetMode = geometry.controlGroup.isSingleZoom,
                )
            }
        }

        // 6. Auxiliary Control (Flash in PHOTO, Mic in VIDEO, absent in VOICE)
        val sideControlsEnabled = uiState.busy == BusyState.Idle
        if (modality == Modality.PHOTO) {
            val frontFlashDisabled = lensFacing == LensFacing.Front
            val flashEnabled = sideControlsEnabled && !frontFlashDisabled
            IconButton(
                onClick = onCycleFlash,
                enabled = flashEnabled,
                modifier = Modifier
                    .placeInWindow(geometry.controlGroup.auxBounds, density)
                    .graphicsLayer { translationX = slabOffset.value }
                    .semantics {
                        contentDescription = flashContentDescription(flashMode)
                        if (!flashEnabled) disabled()
                    },
            ) {
                Icon(
                    imageVector = flashIconFor(if (frontFlashDisabled) FlashMode.Off else flashMode),
                    contentDescription = null,
                    tint = when {
                        !flashEnabled -> Color.White.copy(alpha = 0.38f)
                        flashMode == FlashMode.Off -> Color.White.copy(alpha = 0.65f)
                        else -> Color.White
                    },
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(contentRotation),
                )
            }
        } else if (modality == Modality.VIDEO) {
            val micDesc = if (videoAudioEnabled) "Mic on, tap to mute" else "Mic off, tap to unmute"
            IconButton(
                onClick = onToggleVideoAudio,
                enabled = sideControlsEnabled,
                modifier = Modifier
                    .placeInWindow(geometry.controlGroup.auxBounds, density)
                    .graphicsLayer { translationX = slabOffset.value }
                    .semantics {
                        contentDescription = micDesc
                        if (!sideControlsEnabled) disabled()
                    },
            ) {
                Icon(
                    imageVector = if (videoAudioEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    contentDescription = null,
                    tint = when {
                        !sideControlsEnabled -> Color.White.copy(alpha = 0.38f)
                        !videoAudioEnabled -> Color.White.copy(alpha = 0.65f)
                        else -> Color.White
                    },
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(contentRotation),
                )
            }
        }

        // 7. Shutter / Stop Button (Standard56 80dp / Compact48 64dp)
        Box(
            modifier = Modifier
                .placeInWindow(geometry.controlGroup.shutterBounds, density)
                .graphicsLayer { translationX = slabOffset.value },
            contentAlignment = Alignment.Center,
        ) {
            when (modality) {
                Modality.PHOTO -> ShutterButton(
                    onClick = handleShutter,
                    enabled = uiState.busy == BusyState.Idle,
                    profile = geometry.profile,
                )
                Modality.VIDEO -> VideoShutterButton(
                    onClick = handleShutter,
                    recording = isRecording,
                    progressFraction = null,
                    enabled = uiState.busy == BusyState.Idle || isRecording,
                    profile = geometry.profile,
                )
                Modality.VOICE -> VoiceShutterButton(
                    onClick = handleShutter,
                    recording = isRecording,
                    enabled = uiState.busy == BusyState.Idle || isRecording,
                    profile = geometry.profile,
                )
            }
        }

        // 8. Lens Switch Button (PHOTO & VIDEO only)
        if (modality != Modality.VOICE) {
            IconButton(
                onClick = onToggleLens,
                enabled = sideControlsEnabled,
                modifier = Modifier
                    .placeInWindow(geometry.controlGroup.lensBounds, density)
                    .graphicsLayer { translationX = slabOffset.value }
                    .semantics {
                        contentDescription = "Flip camera"
                        if (!sideControlsEnabled) disabled()
                    },
            ) {
                Icon(
                    imageVector = Icons.Filled.Cameraswitch,
                    contentDescription = null,
                    tint = if (sideControlsEnabled) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.38f),
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(contentRotation),
                )
            }
        }

        // 9. Video Recording Timer Pill (centered on fitted camera frame)
        if (modality == Modality.VIDEO && recordingStartedAtMs != null) {
            Box(
                modifier = Modifier.placeInWindow(geometry.videoTimerBounds, density),
                contentAlignment = Alignment.Center,
            ) {
                VideoRecordingPill(
                    startedAtMs = recordingStartedAtMs,
                    contentRotation = contentRotation,
                    audioEnabled = videoAudioEnabled,
                    audioAmplitudeFlow = videoAudioAmplitudeFlow,
                )
            }
        }

        // 10. Media Queue Strip (Vertical in C Upright, Horizontal in C CW90 & Phone/Cover)
        val queueHostRect = if (geometry.queueAxis == QueueAxis.Vertical) {
            geometry.queueBounds
        } else {
            geometry.queueStripBounds
        }
        Box(
            modifier = Modifier.placeInWindow(queueHostRect, density),
            contentAlignment = Alignment.Center,
        ) {
            QueueStrip(
                queue = uiState.queue,
                dividers = uiState.dividers,
                axis = geometry.queueAxis,
                profile = geometry.profile,
                locked = isRecordingOrCapturing,
                onCommit = handleCommit,
                onDiscard = handleDiscard,
                onDelete = onDeleteOne,
                onReorder = onReorder,
                onInsertDivider = onInsertDivider,
                onRemoveDivider = onRemoveDivider,
                onDeleteProgress = { progress, hotspot ->
                    deleteProgress = progress
                    if (progress > 0f) deleteHotspotInRoot = hotspot
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 11. Single Feedback Host (`Error > Undo > Saved` priority)
        Box(
            modifier = Modifier.placeInWindow(
                geometry.feedbackBounds.copy(height = 160f * geometry.density),
                density,
            ),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                uiState.lastError != null -> {
                    ActionBanner(
                        message = uiState.lastError,
                        actionLabel = "Dismiss",
                        onAction = onClearError,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                uiState.pendingDiscard != null -> {
                    UndoToast(
                        pendingDiscard = uiState.pendingDiscard,
                        onUndo = onUndoDiscard,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    BundleSavedShimmer(
                        events = events,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // 12. Delete Glow inside the safe corridor
        if (animatedDeleteProgress > 0.01f) {
            DeleteGlow(
                progress = animatedDeleteProgress,
                hotspotXInRoot = deleteHotspotInRoot,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(12.dp),
            )
        }

        // 13. Top-level Gesture Tutorial Overlay (anchored to CaptureGeometry)
        GestureTutorial(
            seenStepIds = settings.seenTutorialSteps,
            onDismiss = onDismissTutorial,
            geometry = geometry,
        )
    }
}

private fun Modifier.placeInWindow(
    rect: RectPx,
    density: Density,
): Modifier = with(density) {
    this@placeInWindow
        .offset { IntOffset(rect.x.roundToInt(), rect.y.roundToInt()) }
        .size(
            width = rect.width.toDp(),
            height = rect.height.toDp(),
        )
}

@Composable
private fun rememberContentRotation(
    deviceOrientation: Int,
    activeSurfaceRotation: Int,
): Float {
    val displayDegrees = when (activeSurfaceRotation) {
        Surface.ROTATION_90 -> 270
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 90
        else -> 0
    }
    val relativeDegrees = ((deviceOrientation - displayDegrees) % 360 + 360) % 360
    var cumulativeTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(relativeDegrees) {
        val newTarget = -relativeDegrees.toFloat()
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
            Text("Recon settings", color = Color.White)
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
                text = "Recon needs the camera to capture photos and video. Grant permission to continue, or enable Camera in Android Settings if previously blocked.",
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
