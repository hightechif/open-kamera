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
