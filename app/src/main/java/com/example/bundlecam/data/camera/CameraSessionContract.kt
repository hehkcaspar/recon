package com.example.bundlecam.data.camera

import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.example.bundlecam.ui.capture.window.IntRectPx
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class CameraReadiness {
    Unbound,
    Binding,
    Ready,
    Error,
}

/**
 * Explicit camera capability state for the currently bound lens and mode.
 * - [Unavailable]: Camera provider or use cases could not be bound.
 * - [PhotoOnly]: Device rejected concurrent `Preview + ImageCapture + VideoCapture`
 *   and cleanly bound `Preview + ImageCapture`; `VIDEO` must be visibly disabled
 *   with [reason] rather than silently shown as available.
 * - [PhotoVideo]: Full triple-bind (`Preview + ImageCapture + VideoCapture`) is active.
 */
sealed interface CameraCapability {
    val bindToken: Long

    data class Unavailable(
        val reason: String,
        override val bindToken: Long = 0L,
    ) : CameraCapability

    data class PhotoOnly(
        val reason: String,
        override val bindToken: Long,
    ) : CameraCapability

    data class PhotoVideo(
        override val bindToken: Long,
    ) : CameraCapability

    val supportsPhoto: Boolean
        get() = this is PhotoOnly || this is PhotoVideo

    val supportsVideo: Boolean
        get() = this is PhotoVideo
}

/**
 * Delivered camera stream framing from CameraX `Preview.resolutionInfo`.
 * [cropRect] is in buffer coordinates before [rotationDegrees] is applied.
 * [uprightWidth] and [uprightHeight] swap dimensions for 90° / 270° so layout
 * calculation always sees the upright frame aspect ratio in display coordinates.
 */
data class StreamGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val cropRect: IntRectPx,
    val rotationDegrees: Int,
) {
    val isQuarterTurn: Boolean
        get() = rotationDegrees == 90 || rotationDegrees == 270

    val uprightWidth: Int
        get() = if (isQuarterTurn) cropRect.height else cropRect.width

    val uprightHeight: Int
        get() = if (isQuarterTurn) cropRect.width else cropRect.height

    val uprightAspectRatio: Float
        get() {
            val w = uprightWidth
            val h = uprightHeight
            return if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 3f / 4f
        }
}

data class CameraBindPlan(
    val mode: CameraMode = CameraMode.ZSL,
    val lens: LensFacing = LensFacing.Back,
    val aspectRatio: Int = AspectRatio.RATIO_4_3,
)

data class CameraSessionState(
    val readiness: CameraReadiness = CameraReadiness.Unbound,
    val capability: CameraCapability = CameraCapability.Unavailable("Not bound yet"),
    val bindPlan: CameraBindPlan = CameraBindPlan(),
    val flashMode: FlashMode = FlashMode.Off,
    val streamGeometry: StreamGeometry? = null,
    val zoomInfo: ZoomInfo? = null,
    val videoAudioAmplitude: Float = 0f,
    val activeSurfaceRotation: Int = Surface.ROTATION_0,
    val physicalDeviceDegrees: Int = 0,
    val isRecordingVideo: Boolean = false,
)

/**
 * Testable contract for the single CameraX session owner.
 * UI never calls `bindToLifecycle` or `unbindAll`; it attaches/detaches the host
 * surface and dispatches intents through `CaptureTakeCoordinator`.
 */
interface CameraSessionContract {
    val sessionState: StateFlow<CameraSessionState>
    val zoomInfo: StateFlow<ZoomInfo?>
    val videoAudioAmplitude: StateFlow<Float>
    val deviceOrientation: StateFlow<Int>

    suspend fun attachHost(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        initialPlan: CameraBindPlan = sessionState.value.bindPlan,
    )

    suspend fun detachHost(lifecycleOwner: LifecycleOwner? = null)

    suspend fun updateBindPlan(plan: CameraBindPlan)

    fun updateDisplayRotation(surfaceRotation: Int)

    fun setFlashMode(mode: FlashMode)

    fun setZoomRatio(ratio: Float)

    fun applyPinchZoom(zoomFactor: Float)

    fun focusAt(previewView: PreviewView, x: Float, y: Float)

    suspend fun takePicture(): CapturedPhoto

    fun startVideoRecording(
        takeToken: String,
        outputFile: File,
        audioEnabled: Boolean,
        onUnsolicitedFinalize: ((String, RecordingResult) -> Unit)? = null,
    ): Boolean

    suspend fun stopVideoRecording(): RecordingResult

    fun shutdown()
}
