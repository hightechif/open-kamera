/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.location.Location
import androidx.exifinterface.media.ExifInterface
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.LocationCoordinates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ExifUtils {

    private val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    suspend fun applyExifMetadata(
        file: File,
        config: CaptureConfig,
        dispatcher: CoroutineDispatcher
    ) = withContext(dispatcher) {
        try {
            val exif = ExifInterface(file.absolutePath)
            writeMetadataToExif(exif, config)
            exif.saveAttributes()
        } catch (_: Exception) {
            // Non-fatal if EXIF writing fails
        }
    }

    suspend fun applyExifMetadata(
        inputStream: InputStream,
        outputStream: OutputStream,
        config: CaptureConfig,
        dispatcher: CoroutineDispatcher
    ) = withContext(dispatcher) {
        // Direct stream copying if needed
        inputStream.copyTo(outputStream)
    }

    fun writeMetadataToExif(exif: ExifInterface, config: CaptureConfig) {
        val now = Date()
        val dateString = exifDateFormat.format(now)
        exif.setAttribute(ExifInterface.TAG_DATETIME, dateString)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateString)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateString)

        val orientationTag = when (config.rotationDegrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientationTag.toString())

        config.location?.let { loc ->
            setLocationOnExif(exif, loc)
        }
    }

    fun setLocationOnExif(exif: ExifInterface, loc: LocationCoordinates) {
        val androidLoc = Location("camera").apply {
            latitude = loc.latitude
            longitude = loc.longitude
            loc.altitude?.let { altitude = it }
        }
        exif.setGpsInfo(androidLoc)
    }
}
