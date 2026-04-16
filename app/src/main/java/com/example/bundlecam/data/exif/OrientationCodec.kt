package com.example.bundlecam.data.exif

import android.view.Surface
import androidx.exifinterface.media.ExifInterface

object OrientationCodec {
    fun toExif(rotationDegrees: Int): Int = when (rotationDegrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    fun fromExif(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    fun snapToCardinal(angle: Int): Int = when (angle) {
        in 316..360, in 0..44 -> 0
        in 45..134 -> 90
        in 135..224 -> 180
        in 225..315 -> 270
        else -> 0
    }

    fun toSurfaceRotation(degrees: Int): Int = when (degrees) {
        90 -> Surface.ROTATION_270
        180 -> Surface.ROTATION_180
        270 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }
}
