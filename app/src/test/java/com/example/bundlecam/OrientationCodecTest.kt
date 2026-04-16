package com.example.bundlecam

import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import com.example.bundlecam.data.exif.OrientationCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationCodecTest {

    @Test
    fun toExif_coversAllCardinals() {
        assertEquals(ExifInterface.ORIENTATION_NORMAL, OrientationCodec.toExif(0))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, OrientationCodec.toExif(90))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_180, OrientationCodec.toExif(180))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, OrientationCodec.toExif(270))
    }

    @Test
    fun toExif_unknownValueFallsBackToNormal() {
        assertEquals(ExifInterface.ORIENTATION_NORMAL, OrientationCodec.toExif(45))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, OrientationCodec.toExif(-1))
    }

    @Test
    fun fromExif_roundTripsCardinals() {
        listOf(0, 90, 180, 270).forEach { deg ->
            assertEquals(deg, OrientationCodec.fromExif(OrientationCodec.toExif(deg)))
        }
    }

    @Test
    fun fromExif_unknownFallsBackToZero() {
        assertEquals(0, OrientationCodec.fromExif(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
        assertEquals(0, OrientationCodec.fromExif(999))
    }

    @Test
    fun snapToCardinal_boundaryAngles() {
        // Around 0
        assertEquals(0, OrientationCodec.snapToCardinal(0))
        assertEquals(0, OrientationCodec.snapToCardinal(44))
        assertEquals(0, OrientationCodec.snapToCardinal(316))
        assertEquals(0, OrientationCodec.snapToCardinal(359))
        assertEquals(0, OrientationCodec.snapToCardinal(360))

        // Around 90
        assertEquals(90, OrientationCodec.snapToCardinal(45))
        assertEquals(90, OrientationCodec.snapToCardinal(90))
        assertEquals(90, OrientationCodec.snapToCardinal(134))

        // Around 180
        assertEquals(180, OrientationCodec.snapToCardinal(135))
        assertEquals(180, OrientationCodec.snapToCardinal(180))
        assertEquals(180, OrientationCodec.snapToCardinal(224))

        // Around 270
        assertEquals(270, OrientationCodec.snapToCardinal(225))
        assertEquals(270, OrientationCodec.snapToCardinal(270))
        assertEquals(270, OrientationCodec.snapToCardinal(315))
    }

    @Test
    fun toSurfaceRotation_isInverseMapping() {
        // Device degrees are CW device rotation; Surface.ROTATION_* is the CCW display rotation
        // that would undo it — 90° and 270° swap. See Display.getRotation() docs.
        assertEquals(Surface.ROTATION_0, OrientationCodec.toSurfaceRotation(0))
        assertEquals(Surface.ROTATION_270, OrientationCodec.toSurfaceRotation(90))
        assertEquals(Surface.ROTATION_180, OrientationCodec.toSurfaceRotation(180))
        assertEquals(Surface.ROTATION_90, OrientationCodec.toSurfaceRotation(270))
    }
}
