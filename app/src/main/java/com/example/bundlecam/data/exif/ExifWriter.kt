package com.example.bundlecam.data.exif

import android.location.Location
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val EXIF_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withZone(ZoneId.systemDefault())

class ExifWriter {
    fun stamp(
        file: File,
        capturedAt: Long,
        rotationDegrees: Int,
        location: Location?,
    ) {
        val exif = ExifInterface(file.absolutePath)
        val dateStr = EXIF_DATE_FORMAT.format(Instant.ofEpochMilli(capturedAt))
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
        exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            OrientationCodec.toExif(rotationDegrees).toString(),
        )
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        location?.let { exif.setGpsInfo(it) }
        exif.saveAttributes()
    }

    /**
     * Single open+save pass used by the worker: writes the bundle-ID UserComment and, if
     * [backfillLocation] is provided AND the file doesn't already carry GPS, stamps GPS too.
     * Batching avoids re-parsing + re-writing the whole JPEG twice per photo.
     */
    fun stampFinalMetadata(file: File, comment: String, backfillLocation: Location? = null) {
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
        if (backfillLocation != null && exif.latLong == null) {
            exif.setGpsInfo(backfillLocation)
        }
        exif.saveAttributes()
    }
}
