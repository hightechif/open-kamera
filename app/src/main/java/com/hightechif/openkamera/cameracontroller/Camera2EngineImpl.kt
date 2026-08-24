/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.hightechif.openkamera.di.DefaultDispatcher
import com.hightechif.openkamera.di.IoDispatcher
import com.hightechif.openkamera.domain.engine.CameraEngineState
import com.hightechif.openkamera.domain.engine.CaptureProgress
import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.model.CameraFacing
import com.hightechif.openkamera.domain.model.CameraFrameMetadata
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.ExposureCompensation
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.FocusState
import com.hightechif.openkamera.domain.model.HistogramData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Camera2EngineImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val previewSurfaceManager: PreviewSurfaceManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ICameraEngine {

    private val cameraManager: CameraManager? =
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    private val _engineStateFlow = MutableStateFlow<CameraEngineState>(CameraEngineState.Uninitialized)
    override val engineStateFlow: StateFlow<CameraEngineState> = _engineStateFlow.asStateFlow()

    private val _frameMetadataFlow = MutableStateFlow(CameraFrameMetadata())
    override val frameMetadataFlow: StateFlow<CameraFrameMetadata> = _frameMetadataFlow.asStateFlow()

    private val _focusStateFlow = MutableStateFlow<FocusState>(FocusState.Idle)
    override val focusStateFlow: StateFlow<FocusState> = _focusStateFlow.asStateFlow()

    private val _histogramFlow = MutableSharedFlow<HistogramData>(replay = 1)
    override val histogramFlow: Flow<HistogramData> = _histogramFlow.asSharedFlow()

    private val _currentZoomRatio = MutableStateFlow(1.0f)
    override val currentZoomRatio: StateFlow<Float> = _currentZoomRatio.asStateFlow()

    private val _maxZoomRatio = MutableStateFlow(10.0f)
    override val maxZoomRatio: StateFlow<Float> = _maxZoomRatio.asStateFlow()

    private val _exposureCompensationFlow = MutableStateFlow(
        ExposureCompensation(currentStep = 0, minStep = -4, maxStep = 4, stepSize = 0.5f)
    )
    override val exposureCompensationFlow: StateFlow<ExposureCompensation> = _exposureCompensationFlow.asStateFlow()

    private var activeCameraDevice: CameraDevice? = null
    private var currentCameraFacing: CameraFacing = CameraFacing.BACK
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private fun startBackgroundThread() {
        if (cameraThread == null) {
            cameraThread = HandlerThread("Camera2Background").apply {
                start()
                cameraHandler = Handler(looper)
            }
        }
    }

    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (_: InterruptedException) {
            // Non-fatal
        }
    }

    override suspend fun attachPreviewSurface(surface: Surface) = withContext(ioDispatcher) {
        previewSurfaceManager.setSurface(surface)
    }

    override suspend fun detachPreviewSurface() = withContext(ioDispatcher) {
        previewSurfaceManager.clearSurface()
    }

    @SuppressLint("MissingPermission")
    override suspend fun openCamera(facing: CameraFacing): Result<Unit> = withContext(ioDispatcher) {
        if (cameraManager == null) {
            val error = "CameraManager is not available"
            _engineStateFlow.value = CameraEngineState.Error(error)
            return@withContext Result.failure(IllegalStateException(error))
        }

        try {
            _engineStateFlow.value = CameraEngineState.Opening
            startBackgroundThread()

            val targetLensFacing = when (facing) {
                CameraFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
                CameraFacing.BACK -> CameraCharacteristics.LENS_FACING_BACK
                CameraFacing.EXTERNAL -> CameraCharacteristics.LENS_FACING_EXTERNAL
            }

            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == targetLensFacing
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                val error = "No camera found for facing $facing"
                _engineStateFlow.value = CameraEngineState.Error(error)
                return@withContext Result.failure(IllegalStateException(error))
            }

            currentCameraFacing = facing
            _engineStateFlow.value = CameraEngineState.Ready
            Result.success(Unit)
        } catch (e: Exception) {
            _engineStateFlow.value = CameraEngineState.Error("Failed to open camera: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun closeCamera() = withContext(ioDispatcher) {
        try {
            activeCameraDevice?.close()
            activeCameraDevice = null
            stopBackgroundThread()
            _engineStateFlow.value = CameraEngineState.Uninitialized
        } catch (e: Exception) {
            _engineStateFlow.value = CameraEngineState.Error("Failed to close camera: ${e.message}", e)
        }
    }

    override suspend fun startPreview() = withContext(ioDispatcher) {
        if (_engineStateFlow.value == CameraEngineState.Ready) {
            // Streaming to preview surface
        }
    }

    override suspend fun stopPreview() = withContext(ioDispatcher) {
        // Stop active repeating request
    }

    override suspend fun captureStillImage(config: CaptureConfig): Flow<CaptureProgress> = flow {
        emit(CaptureProgress.Processing(10))
        _engineStateFlow.value = CameraEngineState.Capturing

        try {
            if (config.burstExposures.isNotEmpty()) {
                val total = config.burstExposures.size
                for (i in 1..total) {
                    emit(CaptureProgress.CapturingBurst(i, total))
                }
            }

            emit(CaptureProgress.Processing(80))

            val dummyJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val dummyDng = if (config.enableRaw) byteArrayOf(0x49, 0x49, 0x2A, 0x00) else null

            emit(CaptureProgress.Completed(jpegBytes = dummyJpeg, dngBytes = dummyDng))
            _engineStateFlow.value = CameraEngineState.Ready
        } catch (e: Exception) {
            emit(CaptureProgress.Failed(e))
            _engineStateFlow.value = CameraEngineState.Error("Capture failed: ${e.message}", e)
        }
    }.flowOn(defaultDispatcher)

    override suspend fun startVideoRecording(outputFile: File): Result<Unit> = withContext(ioDispatcher) {
        _engineStateFlow.value = CameraEngineState.Recording
        Result.success(Unit)
    }

    override suspend fun stopVideoRecording(): Result<Unit> = withContext(ioDispatcher) {
        _engineStateFlow.value = CameraEngineState.Ready
        Result.success(Unit)
    }

    override suspend fun setZoom(zoomRatio: Float) = withContext(defaultDispatcher) {
        val clamped = zoomRatio.coerceIn(1.0f, _maxZoomRatio.value)
        _currentZoomRatio.value = clamped
    }

    override suspend fun setManualFocus(point: PointF) = withContext(defaultDispatcher) {
        _focusStateFlow.value = FocusState.Focused(point.x, point.y)
    }

    override suspend fun unlockFocus() = withContext(defaultDispatcher) {
        _focusStateFlow.value = FocusState.Idle
    }

    override suspend fun setExposureCompensation(step: Int) = withContext(defaultDispatcher) {
        val current = _exposureCompensationFlow.value
        val clamped = step.coerceIn(current.minStep, current.maxStep)
        _exposureCompensationFlow.value = current.copy(currentStep = clamped)
    }

    override suspend fun setFlashMode(flashMode: FlashMode) = withContext(defaultDispatcher) {
        // Flash mode setting
    }

    fun updateFrameMetadata(metadata: CameraFrameMetadata) {
        _frameMetadataFlow.value = metadata
    }
}
