/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.graphics.PointF
import android.net.Uri
import com.hightechif.openkamera.domain.model.CameraFacing
import com.hightechif.openkamera.domain.model.CameraFrameMetadata
import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.ExposureCompensation
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.FocusState
import com.hightechif.openkamera.domain.model.GridType
import com.hightechif.openkamera.domain.model.HorizonAngle

/**
 * Immutable state representation for the Camera UI, adhering to MVVM / Unidirectional Data Flow.
 */
data class CameraUiState(
    val facing: CameraFacing = CameraFacing.BACK,
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val flashMode: FlashMode = FlashMode.AUTO,
    val gridType: GridType = GridType.NONE,
    val zoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 10.0f,
    val exposureCompensation: ExposureCompensation = ExposureCompensation(),
    val focusState: FocusState = FocusState.Idle,
    val isCapturing: Boolean = false,
    val isRecording: Boolean = false,
    val recordingDurationSeconds: Long = 0L,
    val latestThumbnailUri: Uri? = null,
    val horizonAngle: HorizonAngle? = null,
    val compassDegrees: Float = 0.0f,
    val frameMetadata: CameraFrameMetadata? = null,
    val isRawEnabled: Boolean = false,
    val timerSecondsRemaining: Int = 0,
    val errorMessage: String? = null
)

sealed interface CameraUiEvent {
    object OnShutterClicked : CameraUiEvent
    object OnRecordVideoClicked : CameraUiEvent
    object OnSwitchCameraClicked : CameraUiEvent
    object OnFlashModeToggleClicked : CameraUiEvent
    data class OnZoomChanged(val ratio: Float) : CameraUiEvent
    data class OnTapToFocus(val point: PointF) : CameraUiEvent
    data class OnExposureStepChanged(val step: Int) : CameraUiEvent
    data class OnCaptureModeSelected(val mode: CaptureMode) : CameraUiEvent
    data class OnGridTypeChanged(val gridType: GridType) : CameraUiEvent
    data class OnRawToggled(val enabled: Boolean) : CameraUiEvent
    object OnGalleryThumbnailClicked : CameraUiEvent
    object OnSettingsClicked : CameraUiEvent
}

sealed interface CameraUiEffect {
    data class ShowToast(val message: String) : CameraUiEffect
    data class Vibrate(val durationMs: Long) : CameraUiEffect
    data class NavigateToGallery(val uri: Uri) : CameraUiEffect
    object OpenSettings : CameraUiEffect
    data class ShowErrorDialog(val title: String, val message: String) : CameraUiEffect
}
