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
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.ExposureCompensation
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.FocusState
import com.hightechif.openkamera.domain.model.HistogramData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface CameraEngineState {
    object Uninitialized : CameraEngineState
    object Opening : CameraEngineState
    object Ready : CameraEngineState
    object Capturing : CameraEngineState
    object Recording : CameraEngineState
    data class Error(val message: String, val cause: Throwable? = null) : CameraEngineState
}

sealed interface CaptureProgress {
    object Idle : CaptureProgress
    object Starting : CaptureProgress
    data class CapturingBurst(val frameIndex: Int, val totalFrames: Int) : CaptureProgress
    data class Processing(val progressPercentage: Int) : CaptureProgress
    data class Completed(val jpegBytes: ByteArray, val dngBytes: ByteArray? = null) : CaptureProgress {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Completed) return false
            return jpegBytes.contentEquals(other.jpegBytes)
        }
        override fun hashCode(): Int = jpegBytes.contentHashCode()
    }
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

    suspend fun captureStillImage(config: CaptureConfig): Flow<CaptureProgress>
    suspend fun startVideoRecording(outputFile: File): Result<Unit>
    suspend fun stopVideoRecording(): Result<Unit>

    suspend fun setZoom(zoomRatio: Float)
    suspend fun setManualFocus(point: PointF)
    suspend fun unlockFocus()
    suspend fun setExposureCompensation(step: Int)
    suspend fun setFlashMode(flashMode: FlashMode)
}
