package com.example.bundlecam.ui.capture

import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bundlecam.data.camera.CameraMode
import com.example.bundlecam.data.camera.CaptureController
import com.example.bundlecam.data.camera.LensFacing
import kotlinx.coroutines.delay

private const val TAG = "Recon/CameraPreview"

@Composable
fun CameraPreview(
    controller: CaptureController,
    mode: CameraMode,
    lens: LensFacing,
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
                // Tap-to-focus without consuming the down event. Compose's built-in
                // `detectTapGestures` calls `down.consume()` immediately after
                // `awaitFirstDown`, which starves any ancestor gesture detectors (like
                // the viewfinder swipe `Modifier.draggable` in CaptureScreen) of the
                // down they need to start tracking. Rewritten here to observe the
                // gesture without consuming: if the pointer goes up without having
                // been consumed by someone else (our draggable when horizontal slop
                // is crossed), treat it as a focus tap.
                .pointerInput(controller) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Main,
                        )
                        val up = waitForUpOrCancellation()
                        if (up != null && !up.isConsumed) {
                            focusPoint = up.position
                            controller.focusAt(previewView, up.position.x, up.position.y)
                            up.consume()
                        }
                    }
                }
                .pointerInput(controller) {
                    // Pinch-to-zoom — rewritten to not consume the first down so the
                    // viewfinder swipe in CaptureScreen can still claim a 1-finger
                    // horizontal drag via Modifier.draggable. Only acts on genuine
                    // multi-touch: the inner loop no-ops until a second pointer lands.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var previousDistance = -1f
                        while (true) {
                            val event = awaitPointerEvent()
                            val active = event.changes.filter { it.pressed }
                            if (active.isEmpty()) break
                            if (active.size >= 2) {
                                val distance = (active[0].position - active[1].position).getDistance()
                                if (previousDistance > 0f && distance > 0f) {
                                    val zoom = distance / previousDistance
                                    if (zoom != 1f) controller.applyPinchZoom(zoom)
                                }
                                previousDistance = distance
                            } else {
                                previousDistance = -1f
                            }
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

    LaunchedEffect(mode, lens, lifecycleOwner) {
        onRebindingChange(true)
        try {
            controller.bind(lifecycleOwner, mode, lens, previewView)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to bind camera mode=$mode lens=$lens", t)
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
