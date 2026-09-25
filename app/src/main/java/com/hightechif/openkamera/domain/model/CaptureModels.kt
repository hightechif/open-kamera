/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.model

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
    val location: LocationCoordinates? = null,
    val iso: Int? = null,
    val aperture: Float? = null
)
