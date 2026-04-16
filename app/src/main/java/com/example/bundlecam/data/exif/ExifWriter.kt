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

    fun stampUserComment(file: File, comment: String) {
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
        exif.saveAttributes()
    }
}
