/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.geometry

/**
 * Encapsulates viewport, display, and camera orientation parameters for matrix calculations.
 */
data class ViewportDimensions(
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val displayRotationDegrees: Int = 0,
    val cameraOrientation: Int = 0,
    val displayOrientation: Int = 0,
    val isCameraFacingFront: Boolean = false,
    val isUsingCamera2: Boolean = true
)
