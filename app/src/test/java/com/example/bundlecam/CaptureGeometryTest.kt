package com.example.bundlecam

import android.view.Surface
import com.example.bundlecam.data.camera.LensFacing
import com.example.bundlecam.ui.capture.CaptureGeometryCalculator
import com.example.bundlecam.ui.capture.CaptureGeometryInput
import com.example.bundlecam.ui.capture.CapturePose
import com.example.bundlecam.ui.capture.Modality
import com.example.bundlecam.ui.capture.QueueAxis
import com.example.bundlecam.ui.capture.QueueProfileToken
import com.example.bundlecam.ui.capture.RectPx
import com.example.bundlecam.ui.capture.window.HingeFeature
import com.example.bundlecam.ui.capture.window.HingeOrientation
import com.example.bundlecam.ui.capture.window.InsetsPx
import com.example.bundlecam.ui.capture.window.IntRectPx
import com.example.bundlecam.ui.capture.window.Posture
import com.example.bundlecam.ui.capture.window.SurfaceRole
import com.example.bundlecam.ui.capture.window.WindowObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGeometryTest {

    private fun assertRect(expected: RectPx, actual: RectPx, label: String) {
        assertEquals("$label.x", expected.x, actual.x, 0.001f)
        assertEquals("$label.y", expected.y, actual.y, 0.001f)
        assertEquals("$label.width", expected.width, actual.width, 0.001f)
        assertEquals("$label.height", expected.height, actual.height, 0.001f)
    }

    private fun innerObs(w: Int, h: Int, rotation: Int = Surface.ROTATION_0) = WindowObservation(
        windowWidthPx = w,
        windowHeightPx = h,
        density = 1f,
        displayRotation = rotation,
        surfaceRole = SurfaceRole.Inner,
    )

    @Test
    fun fold8_upright_photo_and_video_match_c_geometry_source() {
        // Fold8 upright: 960 x 720
        val photoRear = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = innerObs(960, 720),
                modality = Modality.PHOTO,
                lensFacing = LensFacing.Back,
            ),
        )
        assertEquals(CapturePose.InnerUpright, photoRear.pose)
        assertEquals(QueueAxis.Vertical, photoRear.queueAxis)
        assertRect(RectPx(80f, 0f, 540f, 720f), photoRear.previewBounds, "fold8.upright.photo.preview")
        assertRect(RectPx(762f, 92f, 56f, 428f), photoRear.queueBounds, "fold8.upright.photo.queue")
        assertRect(RectPx(694f, 536f, 192f, 40f), photoRear.controlGroup.zoomBounds, "fold8.upright.photo.zoom.rear")
        assertRect(RectPx(694f, 604f, 48f, 48f), photoRear.controlGroup.auxBounds, "fold8.upright.photo.aux")
        assertRect(RectPx(750f, 588f, 80f, 80f), photoRear.controlGroup.shutterBounds, "fold8.upright.photo.shutter")
        assertRect(RectPx(838f, 604f, 48f, 48f), photoRear.controlGroup.lensBounds, "fold8.upright.photo.lens")
        assertEquals(photoRear.queueBounds.centerX, photoRear.controlGroup.shutterBounds.centerX, 0.001f)
        assertEquals(5, photoRear.queueCapacity.visibleCount)
        assertEquals(250f, photoRear.modeBarBounds.width, 0.001f)

        val photoFront = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = innerObs(960, 720),
                modality = Modality.PHOTO,
                lensFacing = LensFacing.Front,
            ),
        )
        assertRect(RectPx(770f, 536f, 40f, 40f), photoFront.controlGroup.zoomBounds, "fold8.upright.photo.zoom.front")

        val videoRear = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = innerObs(960, 720),
                modality = Modality.VIDEO,
                lensFacing = LensFacing.Back,
            ),
        )
        assertRect(RectPx(80f, 0f, 405f, 720f), videoRear.previewBounds, "fold8.upright.video.preview")
        assertRect(RectPx(694.5f, 92f, 56f, 428f), videoRear.queueBounds, "fold8.upright.video.queue")
        assertRect(RectPx(626.5f, 536f, 192f, 40f), videoRear.controlGroup.zoomBounds, "fold8.upright.video.zoom.rear")
        assertRect(RectPx(626.5f, 604f, 48f, 48f), videoRear.controlGroup.auxBounds, "fold8.upright.video.aux")
        assertRect(RectPx(682.5f, 588f, 80f, 80f), videoRear.controlGroup.shutterBounds, "fold8.upright.video.shutter")
        assertEquals(videoRear.queueBounds.centerX, videoRear.controlGroup.shutterBounds.centerX, 0.001f)

        val videoFront = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = innerObs(960, 720),
                modality = Modality.VIDEO,
                lensFacing = LensFacing.Front,
            ),
        )
        assertRect(RectPx(702.5f, 536f, 40f, 40f), videoFront.controlGroup.zoomBounds, "fold8.upright.video.zoom.front")
    }

    @Test
    fun fold8_cw90_photo_and_video_match_c_geometry_source() {
        // Fold8 CW90: 720 x 960
        val photoRear = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = innerObs(720, 960, Surface.ROTATION_90),
                modality = Modality.PHOTO,
                lensFacing = LensFacing.Back,
            ),
        )
        assertEquals(CapturePose.InnerCW90, photoRear.pose)
        assertEquals(QueueAxis.Horizontal, photoRear.queueAxis)
        assertRect(RectPx(0f, 80f, 720f, 540f), photoRear.previewBounds, "fold8.cw90.photo.preview")
        assertRect(RectPx(34f, 820f, 428f, 56f), photoRear.queueBounds, "fold8.cw90.photo.queue")
        assertRect(RectPx(494f, 756f, 192f, 40f), photoRear.controlGroup.zoomBounds, "fold8.cw90.photo.zoom.rear")
        assertRect(RectPx(494f, 824f, 48f, 48f), photoRear.controlGroup.auxBounds, "fold8.cw90.photo.aux")
        assertRect(RectPx(550f, 808f, 80f, 80f), photoRear.controlGroup.shutterBounds, "fold8.cw90.photo.shutter")
        assertEquals(photoRear.queueBounds.centerY, photoRear.controlGroup.shutterBounds.centerY, 0.001f)
        // Outer margins: 34 / 32 / 34
        assertEquals(34f, photoRear.queueBounds.x, 0.001f)
        assertEquals(32f, photoRear.controlGroup.bounds.x - photoRear.queueBounds.right, 0.001f)
        assertEquals(34f, 720f - photoRear.controlGroup.bounds.right, 0.001f)

        val videoFront = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = innerObs(720, 960, Surface.ROTATION_90),
                modality = Modality.VIDEO,
                lensFacing = LensFacing.Front,
            ),
        )
        assertRect(RectPx(0f, 80f, 720f, 405f), videoFront.previewBounds, "fold8.cw90.video.preview")
        assertRect(RectPx(34f, 820f, 428f, 56f), videoFront.queueBounds, "fold8.cw90.video.queue")
        assertRect(RectPx(570f, 756f, 40f, 40f), videoFront.controlGroup.zoomBounds, "fold8.cw90.video.zoom.front")
    }

    @Test
    fun fold8_ultra_upright_and_cw90_match_c_geometry_source() {
        // Fold8 Ultra upright: 960 x 864
        val ultraPhotoUpright = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = innerObs(960, 864),
                modality = Modality.PHOTO,
                lensFacing = LensFacing.Back,
            ),
        )
        assertEquals(QueueAxis.Vertical, ultraPhotoUpright.queueAxis)
        assertRect(RectPx(80f, 0f, 648f, 864f), ultraPhotoUpright.previewBounds, "ultra.upright.photo.preview")
        assertRect(RectPx(816f, 180f, 56f, 428f), ultraPhotoUpright.queueBounds, "ultra.upright.photo.queue")
        assertRect(RectPx(748f, 640f, 192f, 40f), ultraPhotoUpright.controlGroup.zoomBounds, "ultra.upright.photo.zoom.rear")
        assertRect(RectPx(748f, 708f, 48f, 48f), ultraPhotoUpright.controlGroup.auxBounds, "ultra.upright.photo.aux")
        assertRect(RectPx(804f, 692f, 80f, 80f), ultraPhotoUpright.controlGroup.shutterBounds, "ultra.upright.photo.shutter")
        // Ultra PHOTO upright has 232dp right rail -> 208dp mode strip
        assertEquals(208f, ultraPhotoUpright.modeBarBounds.width, 0.001f)

        val ultraVideoUpright = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = innerObs(960, 864),
                modality = Modality.VIDEO,
                lensFacing = LensFacing.Front,
            ),
        )
        assertRect(RectPx(80f, 0f, 486f, 864f), ultraVideoUpright.previewBounds, "ultra.upright.video.preview")
        assertRect(RectPx(735f, 180f, 56f, 428f), ultraVideoUpright.queueBounds, "ultra.upright.video.queue")
        assertRect(RectPx(743f, 640f, 40f, 40f), ultraVideoUpright.controlGroup.zoomBounds, "ultra.upright.video.zoom.front")
        assertRect(RectPx(667f, 708f, 48f, 48f), ultraVideoUpright.controlGroup.auxBounds, "ultra.upright.video.aux")
        assertRect(RectPx(723f, 692f, 80f, 80f), ultraVideoUpright.controlGroup.shutterBounds, "ultra.upright.video.shutter")
        assertEquals(250f, ultraVideoUpright.modeBarBounds.width, 0.001f)

        // Fold8 Ultra CW90: 864 x 960
        val ultraPhotoCw90 = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = innerObs(864, 960, Surface.ROTATION_90),
                modality = Modality.PHOTO,
                lensFacing = LensFacing.Back,
            ),
        )
        assertEquals(QueueAxis.Horizontal, ultraPhotoCw90.queueAxis)
        assertRect(RectPx(0f, 80f, 864f, 648f), ultraPhotoCw90.previewBounds, "ultra.cw90.photo.preview")
        assertRect(RectPx(84f, 820f, 428f, 56f), ultraPhotoCw90.queueBounds, "ultra.cw90.photo.queue")
        assertRect(RectPx(592f, 756f, 192f, 40f), ultraPhotoCw90.controlGroup.zoomBounds, "ultra.cw90.photo.zoom.rear")
        assertRect(RectPx(592f, 824f, 48f, 48f), ultraPhotoCw90.controlGroup.auxBounds, "ultra.cw90.photo.aux")
        assertRect(RectPx(648f, 808f, 80f, 80f), ultraPhotoCw90.controlGroup.shutterBounds, "ultra.cw90.photo.shutter")
        // Outer margins: 84 / 80 / 80
        assertEquals(84f, ultraPhotoCw90.queueBounds.x, 0.001f)
        assertEquals(80f, ultraPhotoCw90.controlGroup.bounds.x - ultraPhotoCw90.queueBounds.right, 0.001f)
        assertEquals(80f, 864f - ultraPhotoCw90.controlGroup.bounds.right, 0.001f)
    }

    @Test
    fun phone_360x800_and_cover_360x576_match_reference_geometry_and_profiles() {
        val phone = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = WindowObservation(
                    windowWidthPx = 360,
                    windowHeightPx = 800,
                    density = 1f,
                    safeDrawingInsetsPx = InsetsPx(24, 24, 24, 24),
                    surfaceRole = SurfaceRole.Regular,
                ),
                modality = Modality.PHOTO,
            ),
        )
        assertEquals(QueueProfileToken.Standard56, phone.profile)
        assertEquals(QueueAxis.Horizontal, phone.queueAxis)
        assertRect(RectPx(0f, 44f, 360f, 480f), phone.previewBounds, "phone.preview")
        assertTrue(phone.showTopHeaderScrim)
        assertFalse(phone.controlsOverlayPreview)
        assertEquals(588f, phone.controlGroup.shutterBounds.y, 0.001f)
        assertRect(RectPx(24f, 680f, 312f, 72f), phone.queueStripBounds, "phone.queueStrip")
        assertEquals(3, phone.queueCapacity.visibleCount)

        val cover = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = WindowObservation(
                    windowWidthPx = 360,
                    windowHeightPx = 576,
                    density = 1f,
                    safeDrawingInsetsPx = InsetsPx(24, 24, 24, 24),
                    surfaceRole = SurfaceRole.Cover,
                ),
                modality = Modality.PHOTO,
            ),
        )
        assertEquals(QueueProfileToken.Compact48, cover.profile)
        assertEquals(QueueAxis.Horizontal, cover.queueAxis)
        assertRect(RectPx(0f, 0f, 360f, 480f), cover.previewBounds, "cover.preview")
        assertEquals(392f, cover.controlGroup.shutterBounds.y, 0.001f)
        assertRect(RectPx(24f, 464f, 312f, 64f), cover.queueStripBounds, "cover.queueStrip")
        assertEquals(4, cover.queueCapacity.visibleCount)
        assertTrue(cover.showTopHeaderScrim)
        assertTrue(cover.controlsOverlayPreview)

        // Wide cover screen (> 420dp width, e.g. Pixel Fold Cover 1080×2424 @ 390dpi = 443×994dp):
        // must still span the full screen width (x = 0 .. windowWidthPx) without side letterboxing.
        val pixelFoldCover = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = WindowObservation(
                    windowWidthPx = 1080,
                    windowHeightPx = 2424,
                    density = 390f / 160f,
                    safeDrawingInsetsPx = InsetsPx(58, 58, 58, 58),
                    surfaceRole = SurfaceRole.Cover,
                ),
                modality = Modality.PHOTO,
            ),
        )
        val expectedMarginPx = 24f * (390f / 160f)
        assertEquals(0f, pixelFoldCover.previewBounds.x, 0.5f)
        assertEquals(1080f, pixelFoldCover.previewBounds.width, 0.5f)
        assertEquals(expectedMarginPx, pixelFoldCover.queueStripBounds.x, 0.5f)
        assertEquals(1080f - 2f * expectedMarginPx, pixelFoldCover.queueStripBounds.width, 0.5f)
        assertEquals(QueueAxis.Horizontal, pixelFoldCover.queueAxis)
    }

    @Test
    fun separating_vertical_hinge_keeps_entire_ui_inside_usable_pane() {
        val hingeRect = IntRectPx(360, 0, 380, 800)
        val hinged = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = WindowObservation(
                    windowWidthPx = 740,
                    windowHeightPx = 800,
                    density = 1f,
                    safeDrawingInsetsPx = InsetsPx(24, 24, 24, 24),
                    hinge = HingeFeature(
                        boundsPx = hingeRect,
                        isSeparating = true,
                        isOccluding = true,
                        orientation = HingeOrientation.Vertical,
                        posture = Posture.Book,
                    ),
                    surfaceRole = SurfaceRole.Inner,
                ),
            ),
        )
        val separatorRect = RectPx(360f, 0f, 20f, 800f)
        assertRect(RectPx(380f, 0f, 360f, 800f), hinged.usablePaneBounds, "hinged.usablePane")
        assertFalse(hinged.previewBounds.overlaps(separatorRect))
        assertFalse(hinged.controlGroup.bounds.overlaps(separatorRect))
        assertFalse(hinged.queueStripBounds.overlaps(separatorRect))
    }

    @Test
    fun pixel_fold_near_square_2076x2152_uses_upright_vertical_queue_at_rotation0_and_cw90_horizontal_at_rotation90() {
        val density = 390f / 160f
        val uprightObs = WindowObservation(
            windowWidthPx = 2076,
            windowHeightPx = 2152,
            density = density,
            displayRotation = Surface.ROTATION_0,
            hinge = HingeFeature(
                boundsPx = IntRectPx(1038, 0, 1038, 2152),
                isSeparating = false,
                isOccluding = false,
                orientation = HingeOrientation.Vertical,
                posture = Posture.Flat,
            ),
            surfaceRole = SurfaceRole.Inner,
        )
        val upright = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = uprightObs,
                modality = Modality.PHOTO,
                lensFacing = LensFacing.Back,
            ),
        )
        assertEquals(CapturePose.InnerUpright, upright.pose)
        assertEquals(QueueAxis.Vertical, upright.queueAxis)
        assertEquals(0f, upright.previewBounds.x, 0.5f)
        assertEquals(0f, upright.previewBounds.y, 0.5f)
        assertEquals(2152f, upright.previewBounds.height, 0.5f)
        assertEquals(upright.queueBounds.centerX, upright.controlGroup.shutterBounds.centerX, 0.5f)
        assertTrue(upright.showLeftUtilityScrim)
        assertFalse(upright.controlsOverlayPreview)

        val cw90Obs = WindowObservation(
            windowWidthPx = 2152,
            windowHeightPx = 2076,
            density = density,
            displayRotation = Surface.ROTATION_90,
            hinge = HingeFeature(
                boundsPx = IntRectPx(0, 1038, 2152, 1038),
                isSeparating = false,
                isOccluding = false,
                orientation = HingeOrientation.Horizontal,
                posture = Posture.Flat,
            ),
            surfaceRole = SurfaceRole.Inner,
        )
        val cw90 = CaptureGeometryCalculator.compute(
            CaptureGeometryInput(
                observation = cw90Obs,
                modality = Modality.PHOTO,
                lensFacing = LensFacing.Back,
            ),
        )
        assertEquals(CapturePose.InnerCW90, cw90.pose)
        assertEquals(QueueAxis.Horizontal, cw90.queueAxis)
        assertEquals(0f, cw90.previewBounds.x, 0.5f)
        assertEquals(0f, cw90.previewBounds.y, 0.5f)
        assertEquals(2152f, cw90.previewBounds.width, 0.5f)
        assertEquals(cw90.queueBounds.centerY, cw90.controlGroup.shutterBounds.centerY, 0.5f)
        assertTrue(cw90.showTopHeaderScrim)
        assertFalse(cw90.controlsOverlayPreview)
        // Physical preview invariance across 90° turn:
        assertEquals(upright.previewBounds.width, cw90.previewBounds.height, 0.5f)
        assertEquals(upright.previewBounds.height, cw90.previewBounds.width, 0.5f)
    }
}
