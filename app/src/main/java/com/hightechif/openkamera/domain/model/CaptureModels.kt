/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.model

import android.net.Uri

data class LocationCoordinates(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null
)

data class CaptureConfig(
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val flashMode: FlashMode = FlashMode.AUTO,
    val jpegQuality: Int = 90,
    val enableRaw: Boolean = false,
    val burstExposures: List<Int> = emptyList(), // e.g. [-2, 0, 2] for HDR
    val rotationDegrees: Int = 0,
    val location: LocationCoordinates? = null
)

data class PhotoResult(
    val uri: Uri,
    val filePath: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fileSizeBytes: Long = 0L,
    val mimeType: String = "image/jpeg",
    val dateTakenEpochMs: Long = System.currentTimeMillis(),
    val isRaw: Boolean = false
)

data class RecordedVideo(
    val uri: Uri,
    val filePath: String? = null,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val fileSizeBytes: Long = 0L,
    val dateTakenEpochMs: Long = System.currentTimeMillis()
)
