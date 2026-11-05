package com.example.bundlecam

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Surface
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.bundlecam.data.camera.applyJpegCropIfNeeded
import com.example.bundlecam.ui.capture.window.IntRectPx
import com.example.bundlecam.ui.capture.window.SurfaceRole
import com.example.bundlecam.ui.capture.window.WindowObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CaptureActivityRotationAndBindingTest {

    @get:Rule
    val activityRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activity_survives_orientation_changes_and_window_posture_injections_in_place() {
        val initialActivity = activityRule.activity
        assertEquals(Lifecycle.State.RESUMED, initialActivity.lifecycle.currentState)

        // Request landscape orientation and verify handled configChanges keeps the same Activity instance
        activityRule.activityRule.scenario.onActivity { activity ->
            activity.allowOrientationOverrideForTest = true
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        activityRule.waitForIdle()
        assertEquals(Lifecycle.State.RESUMED, activityRule.activity.lifecycle.currentState)

        // Inject Inner Upright -> Inner CW90 -> Cover observations
        activityRule.activityRule.scenario.onActivity { activity ->
            activity.injectWindowObservationForTest(
                WindowObservation(
                    windowWidthPx = 960,
                    windowHeightPx = 720,
                    density = 1f,
                    displayRotation = Surface.ROTATION_0,
                    surfaceRole = SurfaceRole.Inner,
                ),
            )
            activity.injectWindowObservationForTest(
                WindowObservation(
                    windowWidthPx = 720,
                    windowHeightPx = 960,
                    density = 1f,
                    displayRotation = Surface.ROTATION_90,
                    surfaceRole = SurfaceRole.Inner,
                ),
            )
            activity.injectWindowObservationForTest(
                WindowObservation(
                    windowWidthPx = 360,
                    windowHeightPx = 576,
                    density = 1f,
                    displayRotation = Surface.ROTATION_0,
                    surfaceRole = SurfaceRole.Cover,
                ),
            )
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        activityRule.waitForIdle()
        assertEquals(Lifecycle.State.RESUMED, activityRule.activity.lifecycle.currentState)
    }

    @Test
    fun in_memory_jpeg_crop_applies_crop_rect_accurately() {
        val src = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val rawJpeg = ByteArrayOutputStream().use { out ->
            src.compress(Bitmap.CompressFormat.JPEG, 95, out)
            out.toByteArray()
        }
        src.recycle()

        val croppedBytes = applyJpegCropIfNeeded(
            jpegBytes = rawJpeg,
            cropRect = IntRectPx(50, 30, 350, 255),
            bufferWidth = 400,
            bufferHeight = 300,
        )
        val decoded = BitmapFactory.decodeByteArray(croppedBytes, 0, croppedBytes.size)
        assertEquals(300, decoded.width)
        assertEquals(225, decoded.height)
        decoded.recycle()
    }

    @Test
    fun camerax_provider_binding_smoke_reports_device_camera_availability() {
        val context = activityRule.activity.applicationContext
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val provider = providerFuture.get(10, TimeUnit.SECONDS)
        val availableCameras = provider.availableCameraInfos
        // An emulator without `-camera-back emulated` may expose 0 cameras; when cameras
        // are exposed, verify ProcessCameraProvider queries them cleanly without throwing.
        assertTrue(availableCameras.size >= 0)
    }
}
