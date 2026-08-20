/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import com.hightechif.openkamera.cameracontroller.CameraController

/**
 * Immutable state representation for the Camera UI, adhering to MVVM / Unidirectional Data Flow.
 */
data class CameraUiState(
    val isPreviewActive: Boolean = false,
    val isRecordingVideo: Boolean = false,
    val isTakingPhoto: Boolean = false,
    val zoomRatio: Int = 0,
    val maxZoom: Int = 0,
    val flashValue: String = "",
    val focusValue: String = "",
    val isoValue: String = "auto",
    val exposureCompensation: Int = 0,
    val cameraFeatures: CameraController.CameraFeatures? = null,
    val statusText: String? = null,
    val errorMessage: String? = null
)
