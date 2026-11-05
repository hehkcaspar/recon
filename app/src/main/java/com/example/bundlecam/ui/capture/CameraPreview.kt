package com.example.bundlecam.ui.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.bundlecam.data.camera.CameraBindPlan
import com.example.bundlecam.data.camera.CameraMode
import com.example.bundlecam.data.camera.CameraSessionContract
import com.example.bundlecam.data.camera.LensFacing
import kotlinx.coroutines.delay

private const val TAG = "Recon/CameraPreview"

@Composable
fun CameraPreview(
    session: CameraSessionContract,
    mode: CameraMode,
    lens: LensFacing,
    onRebindingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewConfig = LocalViewConfiguration.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            clipChildren = true
            clipToPadding = true
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
            clipToOutline = true
        }
    }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView },
        )

        if (gesturesEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Unified pointer arbitration on exposed preview:
                    // - Does not consume the initial down so ancestor horizontal mode-swipe
                    //   can claim 1-finger horizontal drags beyond touch slop.
                    // - Once a 2nd pointer lands (pinch-to-zoom), consumes the gesture so it
                    //   cannot also trigger tap-to-focus or mode-swipe.
                    // - Single-pointer up within touch slop that wasn't consumed by ancestor
                    //   mode-swipe triggers tap-to-focus at the tapped preview point.
                    .pointerInput(session, viewConfig) {
                        val slop = viewConfig.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Main,
                            )
                            val downPos = down.position
                            var multiTouchSeen = false
                            var exceededSlop = false
                            var previousDistance = -1f
                            var upPosition: Offset? = null
                            var upConsumedByOther = false

                            while (true) {
                                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                                val changes = event.changes
                                val active = changes.filter { it.pressed }
                                if (active.size >= 2) {
                                    multiTouchSeen = true
                                    val distance = (active[0].position - active[1].position).getDistance()
                                    if (previousDistance > 0f && distance > 0f) {
                                        val zoom = distance / previousDistance
                                        if (zoom != 1f) {
                                            session.applyPinchZoom(zoom)
                                        }
                                    }
                                    previousDistance = distance
                                    changes.forEach { it.consume() }
                                } else {
                                    previousDistance = -1f
                                    val primary = changes.firstOrNull { it.id == down.id } ?: changes.firstOrNull()
                                    if (primary != null) {
                                        if ((primary.position - downPos).getDistance() > slop) {
                                            exceededSlop = true
                                        }
                                        if (primary.isConsumed) {
                                            upConsumedByOther = true
                                        }
                                        if (primary.changedToUp()) {
                                            upPosition = primary.position
                                            if (primary.isConsumed) upConsumedByOther = true
                                            primary.consume()
                                        }
                                    }
                                }
                                if (active.isEmpty()) break
                            }

                            val finalUp = upPosition
                            if (!multiTouchSeen && !exceededSlop && !upConsumedByOther && finalUp != null) {
                                focusPoint = finalUp
                                session.focusAt(previewView, finalUp.x, finalUp.y)
                            }
                        }
                    },
            )
        }

        focusPoint?.let { point ->
            FocusIndicator(
                point = point,
                onComplete = { focusPoint = null },
            )
        }
    }

    // Observe DisplayManager changes (including 180° reverse turns that keep window bounds
    // identical and therefore wouldn't trigger a size-based relayout).
    DisposableEffect(context, session, previewView) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val viewDisplay = previewView.display ?: return
                if (viewDisplay.displayId == displayId) {
                    session.updateDisplayRotation(viewDisplay.rotation)
                }
            }
        }
        displayManager?.registerDisplayListener(listener, null)
        onDispose {
            displayManager?.unregisterDisplayListener(listener)
        }
    }

    LaunchedEffect(session, mode, lens, lifecycleOwner) {
        onRebindingChange(true)
        try {
            val currentAspect = session.sessionState.value.bindPlan.aspectRatio
            session.attachHost(
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                initialPlan = CameraBindPlan(
                    mode = mode,
                    lens = lens,
                    aspectRatio = currentAspect,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach camera host mode=$mode lens=$lens", t)
        } finally {
            onRebindingChange(false)
        }
    }
}

/**
 * Tap-to-focus feedback ring per `COMPONENT-CONTRACT.md`:
 * complete thin white ring with transparent center, radius 30dp and stroke 2dp (62dp outer
 * diameter), scale 1.5 -> 1.0 over 200ms, hold 500ms, fade over 200ms. No crosshair ticks,
 * padlock, or exposure slider.
 */
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
