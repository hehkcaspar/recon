package com.example.bundlecam.ui.capture

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bundlecam.ui.common.ActionBanner

private const val TAG = "BundleCam/CaptureScreen"

@Composable
fun CaptureScreen(
    onOpenSettings: () -> Unit,
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
        modifier = modifier,
    )
}

@Composable
private fun CaptureScreenContent(
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val vm: CaptureViewModel = viewModel(factory = CaptureViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cameraMode by vm.cameraMode.collectAsStateWithLifecycle()
    val isRebinding by vm.isRebinding.collectAsStateWithLifecycle()
    val zoomInfo by vm.zoomInfo.collectAsStateWithLifecycle()
    val deviceOrientation by vm.deviceOrientation.collectAsStateWithLifecycle()
    val contentRotation = rememberContentRotation(deviceOrientation)

    val mediaSound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(mediaSound) { onDispose { mediaSound.release() } }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* no-op; capture proceeds regardless */ }
    var locationAsked by rememberSaveable { mutableStateOf(false) }

    val handleShutter = {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        if (settings.shutterSoundOn) {
            mediaSound.play(MediaActionSound.SHUTTER_CLICK)
        }
        if (!locationAsked && !vm.hasLocationPermission()) {
            locationAsked = true
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
        vm.onShutter()
    }

    val handleCommit = {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        vm.onCommitBundle()
    }

    val handleDiscard = {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        vm.onDiscardQueue()
    }

    val handleOpenFolder = {
        settings.rootUri?.let { uri -> openFolderInSystemBrowser(context, uri) }
        Unit
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
                CameraModeToggle(
                    current = cameraMode,
                    onChange = vm::onCameraModeChange,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = handleOpenFolder,
                    enabled = settings.rootUri != null,
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = "Open output folder",
                        tint = if (settings.rootUri != null) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.rotate(contentRotation),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            ) {
                CameraPreview(
                    controller = vm.captureController,
                    mode = cameraMode,
                    onRebindingChange = vm::setRebinding,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                state.lastError?.let { message ->
                    ActionBanner(
                        message = message,
                        actionLabel = "Dismiss",
                        onAction = vm::clearError,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                ZoomControl(
                    zoomInfo = zoomInfo,
                    onZoomChange = vm::onZoomChange,
                    contentRotation = contentRotation,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                ShutterButton(
                    onClick = handleShutter,
                    enabled = state.busy == BusyState.Idle && !isRebinding,
                )

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

private fun openFolderInSystemBrowser(context: Context, treeUri: Uri) {
    try {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(docUri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity handles folder-view intent", e)
        Toast.makeText(
            context,
            "No file browser app available to open this folder",
            Toast.LENGTH_SHORT,
        ).show()
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to open folder $treeUri", e)
        Toast.makeText(
            context,
            "Couldn't open folder: ${e.message}",
            Toast.LENGTH_SHORT,
        ).show()
    }
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
                text = "BundleCam needs the camera to capture photos. Grant permission to continue.",
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
