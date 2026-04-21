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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.example.bundlecam.data.exif.OrientationCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

class CaptureController(context: Context) {
    private val appContext: Context = context.applicationContext
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val bindMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null

    // Main-thread-confined: all reads/writes happen on Dispatchers.Main or mainHandler.post.
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var observedZoomState: LiveData<ZoomState>? = null

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
                imageCapture?.targetRotation = OrientationCodec.toSurfaceRotation(snapped)
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

        // Only the actual unbind+rebind needs serialization.
        bindMutex.withLock {
            withContext(Dispatchers.Main) {
                detachZoomObserver()
                provider.unbindAll()
                val cam = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                preview.surfaceProvider = previewView.surfaceProvider
                imageCapture = capture
                camera = cam
                // Apply flash *after* installing so we see any setFlashMode() call that
                // landed between Builder.build() above and here. Those calls post to
                // mainHandler targeting whatever imageCapture was current at post time,
                // which would be the now-discarded previous capture — so we re-read
                // currentFlashMode and write the live one here.
                capture.flashMode = toCxFlashMode(currentFlashMode)
                attachZoomObserver(cam)
            }
        }
        Log.i(TAG, "Bound camera in mode=$mode lens=$lens")
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
