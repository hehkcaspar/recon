package com.example.bundlecam.ui.capture

import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bundlecam.data.camera.CameraMode
import com.example.bundlecam.data.camera.CaptureController
import kotlinx.coroutines.delay

private const val TAG = "BundleCam/CameraPreview"

@Composable
fun CameraPreview(
    controller: CaptureController,
    mode: CameraMode,
    onRebindingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView },
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(controller) {
                    detectTapGestures { offset ->
                        focusPoint = offset
                        controller.focusAt(previewView, offset.x, offset.y)
                    }
                }
                .pointerInput(controller) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) {
                            controller.applyPinchZoom(zoom)
                        }
                    }
                },
        )

        focusPoint?.let { point ->
            FocusIndicator(
                point = point,
                onComplete = { focusPoint = null },
            )
        }
    }

    DisposableEffect(controller) {
        controller.startOrientationListening()
        onDispose { controller.stopOrientationListening() }
    }

    LaunchedEffect(mode, lifecycleOwner) {
        onRebindingChange(true)
        try {
            controller.bind(lifecycleOwner, mode, previewView)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to bind camera mode=$mode", t)
        } finally {
            onRebindingChange(false)
        }
    }
}

@Composable
private fun FocusIndicator(
    point: Offset,
    onComplete: () -> Unit,
) {
    val scale = remember { Animatable(1.5f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(point) {
        scale.snapTo(1.5f)
        alpha.snapTo(1f)
        scale.animateTo(1f, tween(durationMillis = 200))
        delay(500)
        alpha.animateTo(0f, tween(durationMillis = 200))
        onComplete()
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = 30.dp.toPx() * scale.value
        drawCircle(
            color = Color.White.copy(alpha = alpha.value),
            radius = radius,
            center = point,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
