package com.example.bundlecam.data.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.example.bundlecam.data.exif.OrientationCodec
import com.example.bundlecam.ui.capture.window.IntRectPx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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

/**
 * Single owner of the CameraX lifecycle (`ProcessCameraProvider`, `ExtensionsManager`,
 * `Preview`, `ImageCapture`, `VideoCapture<Recorder>`, `ViewPort`, zoom, flash, and
 * rotation). Held by `AppContainer` and driven by `CaptureTakeCoordinator`.
 */
class CaptureController(context: Context) : CameraSessionContract {
    private val appContext: Context = context.applicationContext
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val bindMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bindTokenCounter = AtomicLong(0L)

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null

    // Main-thread-confined CameraX references.
    private var attachedLifecycleOwner: LifecycleOwner? = null
    private var attachedPreviewView: PreviewView? = null
    private var previewUseCase: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recorder: Recorder? = null
    private var activeRecording: Recording? = null
    private var activeTakeToken: String? = null
    private var recordingFinalized: CompletableDeferred<RecordingResult>? = null
    private var camera: Camera? = null
    private var zoomObserver: Observer<ZoomState>? = null
    private var observedZoomState: LiveData<ZoomState>? = null
    private var pendingBindPlan: CameraBindPlan? = null

    @Volatile
    var combinedBindSupported: Boolean = false
        private set

    @Volatile
    private var currentFlashMode: FlashMode = FlashMode.Off

    private val _sessionState = MutableStateFlow(CameraSessionState())
    override val sessionState: StateFlow<CameraSessionState> = _sessionState.asStateFlow()

    private val _zoomInfo = MutableStateFlow<ZoomInfo?>(null)
    override val zoomInfo: StateFlow<ZoomInfo?> = _zoomInfo.asStateFlow()

    private val _videoAudioAmplitude = MutableStateFlow(0f)
    override val videoAudioAmplitude: StateFlow<Float> = _videoAudioAmplitude.asStateFlow()

    private val _deviceOrientation = MutableStateFlow(0)
    override val deviceOrientation: StateFlow<Int> = _deviceOrientation.asStateFlow()

    private val orientationListener = object : OrientationEventListener(appContext) {
        override fun onOrientationChanged(angle: Int) {
            if (angle == ORIENTATION_UNKNOWN) return
            val snapped = OrientationCodec.snapToCardinal(angle)
            if (_deviceOrientation.value != snapped) {
                _deviceOrientation.value = snapped
                _sessionState.update { it.copy(physicalDeviceDegrees = snapped) }
                val targetRotation = computeTargetCaptureRotation(
                    physicalDegrees = snapped,
                    activeSurfaceRotation = _sessionState.value.activeSurfaceRotation,
                )
                imageCapture?.targetRotation = targetRotation
                videoCapture?.targetRotation = targetRotation
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

    override suspend fun attachHost(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        initialPlan: CameraBindPlan,
    ) {
        startOrientationListening()
        val shouldRebind = bindMutex.withLock {
            withContext(Dispatchers.Main) {
                val ownerChanged = attachedLifecycleOwner !== lifecycleOwner
                val viewChanged = attachedPreviewView !== previewView
                val planChanged = _sessionState.value.bindPlan != initialPlan
                val unbound = _sessionState.value.readiness == CameraReadiness.Unbound ||
                    _sessionState.value.readiness == CameraReadiness.Error
                attachedLifecycleOwner = lifecycleOwner
                attachedPreviewView = previewView
                if (previewView != null) {
                    previewUseCase?.surfaceProvider = previewView.surfaceProvider
                }
                // Do not interrupt an active recording if the same LifecycleOwner
                // merely reattached a PreviewView during recomposition.
                if (activeRecording != null && !ownerChanged) {
                    if (planChanged) pendingBindPlan = initialPlan
                    false
                } else {
                    ownerChanged || viewChanged || planChanged || unbound
                }
            }
        }
        if (shouldRebind) {
            bindInternal(lifecycleOwner, previewView, initialPlan)
        }
    }

    override suspend fun detachHost(lifecycleOwner: LifecycleOwner?) {
        bindMutex.withLock {
            withContext(Dispatchers.Main) {
                if (lifecycleOwner != null && attachedLifecycleOwner !== lifecycleOwner) {
                    // Stale detach from an already-superseded host; ignore.
                    return@withContext
                }
                stopOrientationListening()
                detachZoomObserver()
                activeRecording?.stop()
                previewUseCase?.surfaceProvider = null
                cameraProvider?.unbindAll()
                attachedLifecycleOwner = null
                attachedPreviewView = null
                previewUseCase = null
                imageCapture = null
                videoCapture = null
                recorder = null
                camera = null
                _sessionState.update {
                    it.copy(
                        readiness = CameraReadiness.Unbound,
                        streamGeometry = null,
                        zoomInfo = null,
                        isRecordingVideo = false,
                    )
                }
            }
        }
    }

    override suspend fun updateBindPlan(plan: CameraBindPlan) {
        val owner: LifecycleOwner?
        val view: PreviewView?
        val deferBind: Boolean
        bindMutex.withLock {
            withContext(Dispatchers.Main) {
                if (_sessionState.value.bindPlan == plan &&
                    _sessionState.value.readiness == CameraReadiness.Ready
                ) {
                    owner = null
                    view = null
                    deferBind = false
                    return@withContext
                }
                if (activeRecording != null) {
                    // Forbidden to rebind during an active recording; defer until Finalize.
                    pendingBindPlan = plan
                    owner = null
                    view = null
                    deferBind = true
                    return@withContext
                }
                pendingBindPlan = null
                owner = attachedLifecycleOwner
                view = attachedPreviewView
                deferBind = false
            }
        }
        if (!deferBind && owner != null) {
            bindInternal(owner, view, plan)
        } else if (!deferBind) {
            _sessionState.update { it.copy(bindPlan = plan) }
        }
    }

    /**
     * Compatibility entry point delegating to [attachHost].
     */
    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        mode: CameraMode,
        lens: LensFacing,
        previewView: PreviewView,
    ) {
        attachHost(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            initialPlan = CameraBindPlan(mode = mode, lens = lens),
        )
    }

    private suspend fun bindInternal(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        plan: CameraBindPlan,
    ) {
        val token = bindTokenCounter.incrementAndGet()
        _sessionState.update {
            it.copy(
                readiness = CameraReadiness.Binding,
                bindPlan = plan,
            )
        }

        val provider = try {
            getOrInitProvider()
        } catch (t: Throwable) {
            Log.e(TAG, "CameraProvider initialization failed", t)
            _sessionState.update {
                it.copy(
                    readiness = CameraReadiness.Error,
                    capability = CameraCapability.Unavailable(
                        reason = t.message ?: "Camera provider unavailable",
                        bindToken = token,
                    ),
                )
            }
            return
        }

        val selector = try {
            buildSelector(provider, plan.mode, plan.lens)
        } catch (t: Throwable) {
            Log.e(TAG, "CameraSelector build failed", t)
            _sessionState.update {
                it.copy(
                    readiness = CameraReadiness.Error,
                    capability = CameraCapability.Unavailable(
                        reason = t.message ?: "Camera selector unavailable",
                        bindToken = token,
                    ),
                )
            }
            return
        }

        val aspectStrategy = AspectRatioStrategy(
            plan.aspectRatio,
            AspectRatioStrategy.FALLBACK_RULE_AUTO,
        )
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectStrategy)
            .build()

        val activeRotation = _sessionState.value.activeSurfaceRotation
        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(activeRotation)
            .build()

        val captureMode = when (plan.mode) {
            CameraMode.ZSL -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
            CameraMode.Extensions -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
        val captureRotation = computeTargetCaptureRotation(
            physicalDegrees = _deviceOrientation.value,
            activeSurfaceRotation = activeRotation,
        )
        val capture = ImageCapture.Builder()
            .setCaptureMode(captureMode)
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(captureRotation)
            .build()

        val newRecorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.HD, Quality.SD, Quality.LOWEST),
                ),
            )
            .setAspectRatio(plan.aspectRatio)
            .build()
        val newVideoCapture = VideoCapture.withOutput(newRecorder).apply {
            targetRotation = captureRotation
        }

        bindMutex.withLock {
            withContext(Dispatchers.Main) {
                detachZoomObserver()
                provider.unbindAll()

                val viewPort = buildSharedViewPort(plan.aspectRatio, activeRotation)
                val (boundCamera, capabilityResult) = try {
                    val groupBuilder = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(capture)
                        .addUseCase(newVideoCapture)
                    viewPort?.let { groupBuilder.setViewPort(it) }
                    val cam = provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        groupBuilder.build(),
                    )
                    combinedBindSupported = true
                    videoCapture = newVideoCapture
                    recorder = newRecorder
                    cam to CameraCapability.PhotoVideo(bindToken = token)
                } catch (iae: IllegalArgumentException) {
                    Log.w(TAG, "Concurrent Preview+ImageCapture+VideoCapture unsupported; falling back to PhotoOnly", iae)
                    combinedBindSupported = false
                    videoCapture = null
                    recorder = null
                    provider.unbindAll()
                    try {
                        val photoGroup = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(capture)
                        viewPort?.let { photoGroup.setViewPort(it) }
                        val cam = provider.bindToLifecycle(
                            lifecycleOwner,
                            selector,
                            photoGroup.build(),
                        )
                        cam to CameraCapability.PhotoOnly(
                            reason = "Video recording is not supported simultaneously with photo on this camera",
                            bindToken = token,
                        )
                    } catch (fallbackError: Throwable) {
                        Log.e(TAG, "PhotoOnly fallback bind failed", fallbackError)
                        null to CameraCapability.Unavailable(
                            reason = fallbackError.message ?: "Camera unavailable",
                            bindToken = token,
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Camera bind failed", t)
                    combinedBindSupported = false
                    videoCapture = null
                    recorder = null
                    null to CameraCapability.Unavailable(
                        reason = t.message ?: "Camera unavailable",
                        bindToken = token,
                    )
                }

                if (boundCamera == null) {
                    previewUseCase = null
                    imageCapture = null
                    camera = null
                    _sessionState.update {
                        it.copy(
                            readiness = CameraReadiness.Error,
                            capability = capabilityResult,
                            streamGeometry = null,
                        )
                    }
                    return@withContext
                }

                if (previewView != null) {
                    preview.surfaceProvider = previewView.surfaceProvider
                }
                previewUseCase = preview
                imageCapture = capture
                camera = boundCamera
                capture.flashMode = toCxFlashMode(currentFlashMode)
                capture.targetRotation = captureRotation
                videoCapture?.targetRotation = captureRotation
                attachZoomObserver(boundCamera)
                val deliveredStream = extractStreamGeometry(preview)
                _sessionState.update {
                    it.copy(
                        readiness = CameraReadiness.Ready,
                        capability = capabilityResult,
                        bindPlan = plan,
                        flashMode = currentFlashMode,
                        streamGeometry = deliveredStream,
                    )
                }
            }
        }
        Log.i(TAG, "Bound camera plan=$plan capability=${_sessionState.value.capability}")
    }

    override fun updateDisplayRotation(surfaceRotation: Int) {
        mainHandler.post {
            val prevRotation = _sessionState.value.activeSurfaceRotation
            _sessionState.update { it.copy(activeSurfaceRotation = surfaceRotation) }
            previewUseCase?.targetRotation = surfaceRotation
            val targetRotation = computeTargetCaptureRotation(
                physicalDegrees = _deviceOrientation.value,
                activeSurfaceRotation = surfaceRotation,
            )
            imageCapture?.targetRotation = targetRotation
            videoCapture?.targetRotation = targetRotation
            val updatedStream = previewUseCase?.let(::extractStreamGeometry)
            if (updatedStream != null || prevRotation != surfaceRotation) {
                _sessionState.update { state ->
                    state.copy(streamGeometry = updatedStream ?: state.streamGeometry)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun startVideoRecording(
        takeToken: String,
        outputFile: File,
        audioEnabled: Boolean,
        onUnsolicitedFinalize: ((String, RecordingResult) -> Unit)?,
    ): Boolean {
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
        val basePending = rec.prepareRecording(appContext, options)
        val audioGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val pendingRecording = when {
            !audioEnabled -> basePending
            audioGranted -> try {
                basePending.withAudioEnabled()
            } catch (se: SecurityException) {
                Log.w(TAG, "withAudioEnabled rejected despite granted RECORD_AUDIO; recording silently", se)
                basePending
            }
            else -> {
                Log.w(TAG, "RECORD_AUDIO not granted at startVideoRecording; recording silently")
                basePending
            }
        }
        _videoAudioAmplitude.value = 0f
        _sessionState.update { it.copy(videoAudioAmplitude = 0f, isRecordingVideo = true) }
        activeTakeToken = takeToken

        val recording = pendingRecording.start(ContextCompat.getMainExecutor(appContext)) { event ->
            when (event) {
                is VideoRecordEvent.Status -> {
                    val raw = event.recordingStats.audioStats.audioAmplitude
                    val amp = if (raw.isFinite()) raw.toFloat().coerceIn(0f, 1f) else 0f
                    _videoAudioAmplitude.value = amp
                    _sessionState.update { it.copy(videoAudioAmplitude = amp) }
                }
                is VideoRecordEvent.Finalize -> {
                    _videoAudioAmplitude.value = 0f
                    val result = if (event.hasError()) {
                        Log.w(TAG, "Video finalized with error ${event.error}", event.cause)
                        RecordingResult.Error(event.error, event.cause)
                    } else {
                        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                        RecordingResult.Success(outputFile, durationMs)
                    }
                    if (activeTakeToken == takeToken) {
                        activeRecording = null
                        activeTakeToken = null
                    }
                    _sessionState.update {
                        it.copy(videoAudioAmplitude = 0f, isRecordingVideo = false)
                    }
                    deferred.complete(result)
                    onUnsolicitedFinalize?.invoke(takeToken, result)

                    val queuedPlan = pendingBindPlan
                    val owner = attachedLifecycleOwner
                    if (queuedPlan != null && owner != null) {
                        pendingBindPlan = null
                        controllerScope.launch {
                            bindInternal(owner, attachedPreviewView, queuedPlan)
                        }
                    }
                }
                else -> Unit
            }
        }
        activeRecording = recording
        recordingFinalized = deferred
        Log.i(TAG, "Video recording started token=$takeToken → ${outputFile.absolutePath}")
        return true
    }

    /**
     * Convenience overload preserving existing call signature.
     */
    fun startVideoRecording(outputFile: File, audioEnabled: Boolean): Boolean =
        startVideoRecording(
            takeToken = outputFile.name,
            outputFile = outputFile,
            audioEnabled = audioEnabled,
            onUnsolicitedFinalize = null,
        )

    override suspend fun stopVideoRecording(): RecordingResult {
        val rec = activeRecording
        val deferred = recordingFinalized
        if (rec == null && deferred == null) {
            return RecordingResult.Error(-1, IllegalStateException("not recording"))
        }
        rec?.stop()
        val result = deferred?.await()
            ?: RecordingResult.Error(-1, IllegalStateException("missing finalize deferred"))
        activeRecording = null
        activeTakeToken = null
        recordingFinalized = null
        return result
    }

    override fun setFlashMode(mode: FlashMode) {
        if (currentFlashMode == mode) return
        currentFlashMode = mode
        _sessionState.update { it.copy(flashMode = mode) }
        mainHandler.post { imageCapture?.flashMode = toCxFlashMode(mode) }
    }

    override fun focusAt(previewView: PreviewView, x: Float, y: Float) {
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

    override fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        runCatching { cam.cameraControl.setZoomRatio(ratio) }
            .onFailure { Log.w(TAG, "setZoomRatio($ratio) failed", it) }
    }

    override fun applyPinchZoom(zoomFactor: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        val newRatio = zs.zoomRatio * zoomFactor
        runCatching { cam.cameraControl.setZoomRatio(newRatio) }
            .onFailure { Log.w(TAG, "applyPinchZoom($zoomFactor) failed", it) }
    }

    override suspend fun takePicture(): CapturedPhoto {
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
                            val rawBytes = ByteArray(buffer.remaining())
                            buffer.get(rawBytes)
                            val rect = image.cropRect
                            val cropRectPx = IntRectPx(rect.left, rect.top, rect.right, rect.bottom)
                            val finalBytes = applyJpegCropIfNeeded(
                                jpegBytes = rawBytes,
                                cropRect = cropRectPx,
                                bufferWidth = image.width,
                                bufferHeight = image.height,
                            )
                            cont.resume(CapturedPhoto(finalBytes, image.imageInfo.rotationDegrees))
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

    override fun shutdown() {
        captureExecutor.shutdown()
        orientationListener.disable()
        mainHandler.post { detachZoomObserver() }
    }

    private fun buildSharedViewPort(
        aspectRatio: Int,
        surfaceRotation: Int,
    ): ViewPort? {
        val isPortraitRotation = surfaceRotation == Surface.ROTATION_0 ||
            surfaceRotation == Surface.ROTATION_180
        val rational = when (aspectRatio) {
            AspectRatio.RATIO_16_9 -> if (isPortraitRotation) Rational(9, 16) else Rational(16, 9)
            else -> if (isPortraitRotation) Rational(3, 4) else Rational(4, 3)
        }
        return runCatching {
            ViewPort.Builder(rational, surfaceRotation)
                .setScaleType(ViewPort.FIT)
                .build()
        }.getOrNull()
    }

    private fun extractStreamGeometry(preview: Preview): StreamGeometry? {
        val info = preview.resolutionInfo ?: return null
        val res = info.resolution
        val crop = info.cropRect
        return StreamGeometry(
            bufferWidth = res.width,
            bufferHeight = res.height,
            cropRect = IntRectPx(crop.left, crop.top, crop.right, crop.bottom),
            rotationDegrees = info.rotationDegrees,
        )
    }

    private fun attachZoomObserver(cam: Camera) {
        val zoomStateLive = cam.cameraInfo.zoomState
        val obs = Observer<ZoomState> { zs ->
            val info = ZoomInfo(
                currentRatio = zs.zoomRatio,
                minRatio = zs.minZoomRatio,
                maxRatio = zs.maxZoomRatio,
            )
            _zoomInfo.value = info
            _sessionState.update { it.copy(zoomInfo = info) }
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

    companion object {
        /**
         * Combine physical device orientation degrees (0, 90, 180, 270) with active
         * display rotation so still capture and next video capture use the active
         * display's coordinate basis while honoring physical rotation (including 180°
         * and rotation lock).
         */
        fun computeTargetCaptureRotation(
            physicalDegrees: Int,
            activeSurfaceRotation: Int,
        ): Int {
            // OrientationCodec maps physical device degrees directly to Surface.ROTATION_*.
            // When the user holds the device in a physical cardinal pose, CameraX expects
            // targetRotation as the Surface.ROTATION_* constant for that physical orientation.
            return OrientationCodec.toSurfaceRotation(physicalDegrees)
        }
    }
}
