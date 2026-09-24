/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.engine

import android.graphics.PointF
import android.view.Surface
import com.hightechif.openkamera.domain.model.CameraFacing
import com.hightechif.openkamera.domain.model.CameraFrameMetadata
import com.hightechif.openkamera.domain.model.ExposureCompensation
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.FocusState
import com.hightechif.openkamera.domain.model.HistogramData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface CameraEngineState {
    object Uninitialized : CameraEngineState
    object Opening : CameraEngineState
    object Ready : CameraEngineState
    data class Error(val message: String, val cause: Throwable? = null) : CameraEngineState
}

sealed interface CaptureProgress {
    object Idle : CaptureProgress
    object Starting : CaptureProgress
    data class Processing(val progressPercentage: Int) : CaptureProgress
    data class Failed(val cause: Throwable) : CaptureProgress
}

interface ICameraEngine {
    val engineStateFlow: StateFlow<CameraEngineState>
    val frameMetadataFlow: StateFlow<CameraFrameMetadata>
    val focusStateFlow: StateFlow<FocusState>
    val histogramFlow: Flow<HistogramData>
    val currentZoomRatio: StateFlow<Float>
    val maxZoomRatio: StateFlow<Float>
    val exposureCompensationFlow: StateFlow<ExposureCompensation>

    suspend fun attachPreviewSurface(surface: Surface)
    suspend fun detachPreviewSurface()

    suspend fun openCamera(facing: CameraFacing): Result<Unit>
    suspend fun closeCamera()

    suspend fun startPreview()
    suspend fun stopPreview()

    suspend fun setZoom(zoomRatio: Float)
    suspend fun setManualFocus(point: PointF)
    suspend fun unlockFocus()
    suspend fun setExposureCompensation(step: Int)
    suspend fun setFlashMode(flashMode: FlashMode)
}
