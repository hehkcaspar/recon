package com.example.bundlecam.data.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.OrientationEventListener
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.example.bundlecam.data.exif.OrientationCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Recon/CaptureController"

enum class CameraMode { ZSL, Extensions }

enum class LensFacing { Back, Front }

enum class FlashMode { Off, Auto, On }

class CapturedPhoto(
    val jpegBytes: ByteArray,
    val rotationDegrees: Int,
)

data class ZoomInfo(
    val currentRatio: Float,
    val minRatio: Float,
    val maxRatio: Float,
)

sealed class RecordingResult {
    data class Success(val file: File, val durationMs: Long) : RecordingResult()
    data class Error(val code: Int, val cause: Throwable?) : RecordingResult()
}

class CaptureController(context: Context) {
    private val appContext: Context = context.applicationContext
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val bindMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null

    // Main-thread-confined: all reads/writes happen on Dispatchers.Main or mainHandler.post.
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recorder: Recorder? = null
    private var activeRecording: Recording? = null
    private var recordingFinalized: CompletableDeferred<RecordingResult>? = null
    private var camera: Camera? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var observedZoomState: LiveData<ZoomState>? = null

    // Set after the first bind: true if Preview + ImageCapture + VideoCapture bound
    // concurrently (the common case on API 26+ / LIMITED+ hardware). False means the
    // device refused the triple-bind and we've degraded to Preview + ImageCapture —
    // video recording is unavailable until we add per-modality rebind fallback.
    @Volatile
    var combinedBindSupported: Boolean = false
        private set

    // Flash mode survives rebinds: setFlashMode updates this and any live capture use case;
    // bind() re-applies it whenever a fresh ImageCapture is created.
    @Volatile
    private var currentFlashMode: FlashMode = FlashMode.Off

    private val _zoomInfo = MutableStateFlow<ZoomInfo?>(null)
    val zoomInfo: StateFlow<ZoomInfo?> = _zoomInfo.asStateFlow()

    private val _deviceOrientation = MutableStateFlow(0)
    val deviceOrientation: StateFlow<Int> = _deviceOrientation.asStateFlow()

    private val orientationListener = object : OrientationEventListener(appContext) {
        override fun onOrientationChanged(angle: Int) {
            if (angle == ORIENTATION_UNKNOWN) return
            val snapped = OrientationCodec.snapToCardinal(angle)
            if (_deviceOrientation.value != snapped) {
                _deviceOrientation.value = snapped
                val rotation = OrientationCodec.toSurfaceRotation(snapped)
                imageCapture?.targetRotation = rotation
                // VideoCapture.targetRotation is frozen at `start()`-time by CameraX, so
                // writes here while a recording is active are harmless no-ops; the live
                // clip keeps the rotation captured at prepareRecording().start(). The
                // next recording picks up the current value.
                videoCapture?.targetRotation = rotation
            }
        }
    }

    fun startOrientationListening() {
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
    }

    fun stopOrientationListening() {
        orientationListener.disable()
    }

    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        mode: CameraMode,
        lens: LensFacing,
        previewView: PreviewView,
    ) {
        // Init provider + extensions outside the mutex — idempotent, cached.
        val provider = getOrInitProvider()
        val selector = buildSelector(provider, mode, lens)

        val aspectStrategy = AspectRatioStrategy(
            AspectRatio.RATIO_4_3,
            AspectRatioStrategy.FALLBACK_RULE_AUTO,
        )
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectStrategy)
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()

        val captureMode = when (mode) {
            CameraMode.ZSL -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
            CameraMode.Extensions -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(captureMode)
            .setResolutionSelector(resolutionSelector)
            .build()

        val newRecorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.HD, Quality.SD, Quality.LOWEST),
                ),
            )
            .build()
        val newVideoCapture = VideoCapture.withOutput(newRecorder)

        // Only the actual unbind+rebind needs serialization.
        bindMutex.withLock {
            withContext(Dispatchers.Main) {
                detachZoomObserver()
                provider.unbindAll()

                // Try the combined bind first (CameraX 1.5.1+ guarantees it on LIMITED+
                // hardware). If the device rejects it, degrade to Preview + ImageCapture
                // so the photo path keeps working — the user will see "Video unavailable"
                // if they try VIDEO modality on a degraded device.
                val cam = try {
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, capture, newVideoCapture)
                        .also {
                            combinedBindSupported = true
                            videoCapture = newVideoCapture
                            recorder = newRecorder
                        }
                } catch (iae: IllegalArgumentException) {
                    Log.w(TAG, "Concurrent Preview+ImageCapture+VideoCapture unsupported; binding photo-only", iae)
                    combinedBindSupported = false
                    videoCapture = null
                    recorder = null
                    provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                }

                preview.surfaceProvider = previewView.surfaceProvider
                imageCapture = capture
                camera = cam
                // Apply flash *after* installing so we see any setFlashMode() call that
                // landed between Builder.build() above and here. Those calls post to
                // mainHandler targeting whatever imageCapture was current at post time,
                // which would be the now-discarded previous capture — so we re-read
                // currentFlashMode and write the live one here.
                capture.flashMode = toCxFlashMode(currentFlashMode)
                // Seed targetRotation on the fresh use cases from the latest orientation.
                val rotation = OrientationCodec.toSurfaceRotation(_deviceOrientation.value)
                capture.targetRotation = rotation
                videoCapture?.targetRotation = rotation
                attachZoomObserver(cam)
            }
        }
        Log.i(TAG, "Bound camera in mode=$mode lens=$lens video=$combinedBindSupported")
    }

    /**
     * Start a video recording into [outputFile]. Silent-only (`withAudioEnabled` is
     * omitted so we skip the `RECORD_AUDIO` requirement and leave the mic free for the
     * voice modality's `MediaRecorder` to own).
     *
     * Returns true if the recording started. False if VideoCapture isn't bound (the
     * device degraded to photo-only) or another recording is already live.
     */
    fun startVideoRecording(outputFile: File): Boolean {
        val rec = recorder ?: run {
            Log.w(TAG, "startVideoRecording: no Recorder bound; video unsupported on this device")
            return false
        }
        if (activeRecording != null) {
            Log.w(TAG, "startVideoRecording: another recording is already active")
            return false
        }
        val deferred = CompletableDeferred<RecordingResult>()
        val options = FileOutputOptions.Builder(outputFile).build()
        val pendingRecording = rec.prepareRecording(appContext, options)
        val recording = pendingRecording.start(ContextCompat.getMainExecutor(appContext)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                val result = if (event.hasError()) {
                    Log.w(TAG, "Video finalized with error ${event.error}", event.cause)
                    RecordingResult.Error(event.error, event.cause)
                } else {
                    val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                    RecordingResult.Success(outputFile, durationMs)
                }
                deferred.complete(result)
            }
        }
        activeRecording = recording
        recordingFinalized = deferred
        Log.i(TAG, "Video recording started → ${outputFile.absolutePath}")
        return true
    }

    /**
     * Stop the current recording and suspend until `Finalize` fires. Returns the finalized
     * result. Idempotent-ish: if nothing is recording, returns an Error with a sentinel
     * state, so callers don't have to guard.
     */
    suspend fun stopVideoRecording(): RecordingResult {
        val rec = activeRecording
        val deferred = recordingFinalized
        if (rec == null || deferred == null) {
            return RecordingResult.Error(-1, IllegalStateException("not recording"))
        }
        rec.stop()
        val result = deferred.await()
        activeRecording = null
        recordingFinalized = null
        return result
    }


    fun setFlashMode(mode: FlashMode) {
        currentFlashMode = mode
        mainHandler.post { imageCapture?.flashMode = toCxFlashMode(mode) }
    }

    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        runCatching { cam.cameraControl.startFocusAndMetering(action) }
            .onFailure { Log.w(TAG, "Focus-and-metering failed at ($x, $y)", it) }
    }

    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        runCatching { cam.cameraControl.setZoomRatio(ratio) }
            .onFailure { Log.w(TAG, "setZoomRatio($ratio) failed", it) }
    }

    fun applyPinchZoom(zoomFactor: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        val newRatio = zs.zoomRatio * zoomFactor
        runCatching { cam.cameraControl.setZoomRatio(newRatio) }
            .onFailure { Log.w(TAG, "applyPinchZoom($zoomFactor) failed", it) }
    }

    suspend fun takePicture(): CapturedPhoto {
        // Rebinding is gated at the UI layer via isRebinding; here we rely on the
        // imageCapture null check below + the CameraX onError callback to surface any
        // in-flight failure (e.g. the capture use case being unbound mid-flight).
        return suspendCancellableCoroutine { cont ->
            val capture = imageCapture ?: run {
                cont.resumeWithException(IllegalStateException("ImageCapture not bound"))
                return@suspendCancellableCoroutine
            }
            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            cont.resume(CapturedPhoto(bytes, image.imageInfo.rotationDegrees))
                        } catch (t: Throwable) {
                            cont.resumeWithException(t)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }
    }

    fun shutdown() {
        captureExecutor.shutdown()
        orientationListener.disable()
        mainHandler.post { detachZoomObserver() }
    }

    private fun attachZoomObserver(cam: Camera) {
        val zoomStateLive = cam.cameraInfo.zoomState
        val obs = Observer<ZoomState> { zs ->
            _zoomInfo.value = ZoomInfo(
                currentRatio = zs.zoomRatio,
                minRatio = zs.minZoomRatio,
                maxRatio = zs.maxZoomRatio,
            )
        }
        zoomStateLive.observeForever(obs)
        zoomObserver = obs
        observedZoomState = zoomStateLive
    }

    private fun detachZoomObserver() {
        val obs = zoomObserver
        val zs = observedZoomState
        if (obs != null && zs != null) {
            zs.removeObserver(obs)
        }
        zoomObserver = null
        observedZoomState = null
    }

    private suspend fun getOrInitProvider(): ProcessCameraProvider {
        cameraProvider?.let { return it }
        val provider = awaitListenableFuture(ProcessCameraProvider.getInstance(appContext))
        cameraProvider = provider
        return provider
    }

    private suspend fun buildSelector(
        provider: ProcessCameraProvider,
        mode: CameraMode,
        lens: LensFacing,
    ): CameraSelector {
        val base = when (lens) {
            LensFacing.Back -> CameraSelector.DEFAULT_BACK_CAMERA
            LensFacing.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
        if (mode != CameraMode.Extensions) return base
        val manager = getOrInitExtensionsManager(provider)
        return if (manager.isExtensionAvailable(base, ExtensionMode.AUTO)) {
            Log.i(TAG, "Extensions AUTO available for lens=$lens")
            manager.getExtensionEnabledCameraSelector(base, ExtensionMode.AUTO)
        } else {
            Log.w(TAG, "Extensions AUTO not available for lens=$lens; using default selector")
            base
        }
    }

    private fun toCxFlashMode(mode: FlashMode): Int = when (mode) {
        FlashMode.Off -> ImageCapture.FLASH_MODE_OFF
        FlashMode.Auto -> ImageCapture.FLASH_MODE_AUTO
        FlashMode.On -> ImageCapture.FLASH_MODE_ON
    }

    private suspend fun getOrInitExtensionsManager(
        provider: ProcessCameraProvider,
    ): ExtensionsManager {
        extensionsManager?.let { return it }
        val manager = awaitListenableFuture(
            ExtensionsManager.getInstanceAsync(appContext, provider),
        )
        extensionsManager = manager
        return manager
    }

    private suspend fun <T> awaitListenableFuture(
        future: com.google.common.util.concurrent.ListenableFuture<T>,
    ): T = suspendCancellableCoroutine { cont ->
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(appContext),
        )
        cont.invokeOnCancellation { future.cancel(false) }
    }
}
