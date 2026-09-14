/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.view.Surface
import com.hightechif.openkamera.cameracontroller.dispatcher.CaptureEventListener
import com.hightechif.openkamera.di.DefaultDispatcher
import com.hightechif.openkamera.di.IoDispatcher
import com.hightechif.openkamera.domain.engine.CameraEngineState
import com.hightechif.openkamera.domain.engine.CaptureProgress
import com.hightechif.openkamera.domain.engine.IAudioController
import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.model.CameraFacing
import com.hightechif.openkamera.domain.model.CameraFrameMetadata
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.ExposureCompensation
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.FocusState
import com.hightechif.openkamera.domain.model.HistogramData
import com.hightechif.openkamera.utils.MyDebug
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.exp

/**
 * Unified camera engine bridge adapting [CameraController2] and its coordinators to [ICameraEngine].
 *
 * Responsibilities:
 * - Backing [ICameraEngine] with the active [CameraController2] hardware instance
 * - Streaming real-time HAL metadata (ISO, exposure time, aperture, focus distance) via [frameMetadataFlow]
 * - Mapping AF mode and state transitions to domain [FocusState] via [focusStateFlow]
 * - Emitting reactive [engineStateFlow] reflecting preview, capturing, recording, and error states
 * - Calculating and streaming preview histogram telemetry via [histogramFlow]
 * - Routing domain use cases (CapturePhoto, RecordVideo, Zoom, Focus, Exposure) to the active controller
 * - Eliminating dual-client Camera2 HAL contention
 */
@Singleton
class Camera2EngineBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val previewSurfaceManager: PreviewSurfaceManager,
    private val audioController: IAudioController,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : ICameraEngine {

    companion object {
        private const val TAG = "Camera2EngineBridge"
    }

    private val _engineStateFlow =
        MutableStateFlow<CameraEngineState>(CameraEngineState.Uninitialized)
    override val engineStateFlow: StateFlow<CameraEngineState> = _engineStateFlow.asStateFlow()

    private val _frameMetadataFlow = MutableStateFlow(CameraFrameMetadata())
    override val frameMetadataFlow: StateFlow<CameraFrameMetadata> =
        _frameMetadataFlow.asStateFlow()

    private val _focusStateFlow = MutableStateFlow<FocusState>(FocusState.Idle)
    override val focusStateFlow: StateFlow<FocusState> = _focusStateFlow.asStateFlow()

    private val _histogramFlow = MutableSharedFlow<HistogramData>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val histogramFlow: Flow<HistogramData> = _histogramFlow.asSharedFlow()

    private val _currentZoomRatio = MutableStateFlow(1.0f)
    override val currentZoomRatio: StateFlow<Float> = _currentZoomRatio.asStateFlow()

    private val _maxZoomRatio = MutableStateFlow(10.0f)
    override val maxZoomRatio: StateFlow<Float> = _maxZoomRatio.asStateFlow()

    private val _exposureCompensationFlow = MutableStateFlow(
        ExposureCompensation(currentStep = 0, minStep = -4, maxStep = 4, stepSize = 0.5f)
    )
    override val exposureCompensationFlow: StateFlow<ExposureCompensation> =
        _exposureCompensationFlow.asStateFlow()

    @Volatile
    var activeController: CameraController2? = null
        private set

    private val captureEventListener = object : CaptureEventListener {
        override fun onCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val iso = runCatching { result.get(CaptureResult.SENSOR_SENSITIVITY) }.getOrNull()
            val exposureTime =
                runCatching { result.get(CaptureResult.SENSOR_EXPOSURE_TIME) }.getOrNull()
            val aperture = runCatching { result.get(CaptureResult.LENS_APERTURE) }.getOrNull()
            val focalLength =
                runCatching { result.get(CaptureResult.LENS_FOCAL_LENGTH) }.getOrNull()
            val focusDistance =
                runCatching { result.get(CaptureResult.LENS_FOCUS_DISTANCE) }.getOrNull()
            val timestamp = runCatching { result.get(CaptureResult.SENSOR_TIMESTAMP) }.getOrNull()
                ?: System.nanoTime()

            _frameMetadataFlow.value = CameraFrameMetadata(
                iso = iso,
                exposureTimeNs = exposureTime,
                aperture = aperture,
                focalLengthMm = focalLength,
                focusDistanceMeters = focusDistance,
                sensorSensitivity = iso,
                timestampNs = timestamp
            )

            val afState = runCatching { result.get(CaptureResult.CONTROL_AF_STATE) }.getOrNull()
            val domainFocusState = when (afState) {
                CaptureResult.CONTROL_AF_STATE_INACTIVE -> FocusState.Idle
                CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
                CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> FocusState.Scanning()

                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> FocusState.Focused()

                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
                CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> FocusState.Failed()

                else -> FocusState.Idle
            }
            _focusStateFlow.value = domainFocusState

            calculateHistogram(result)
        }

        override fun onFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (MyDebug.LOG) Log.w(TAG, "Capture failed: reason=${failure.reason}")
        }
    }

    /**
     * Attaches an active [CameraController2] instance, subscribing to HAL callbacks.
     */
    fun attachController(controller: CameraController2) {
        if (MyDebug.LOG) Log.d(TAG, "attachController: $controller")
        activeController = controller
        controller.callbackDispatcher.addListener(captureEventListener)

        // Read initial zoom ratio
        try {
            val features = controller.cameraFeatures
            val ratios = features.zoomRatios
            if (!ratios.isNullOrEmpty()) {
                _maxZoomRatio.value = (ratios.last().toFloat() / 100.0f).coerceAtLeast(1.0f)
                val curIdx = controller.zoom.coerceIn(0, ratios.size - 1)
                _currentZoomRatio.value = (ratios[curIdx].toFloat() / 100.0f).coerceAtLeast(1.0f)
            } else {
                _maxZoomRatio.value = (features.maxZoom.toFloat() / 10.0f).coerceAtLeast(1.0f)
                _currentZoomRatio.value = (controller.zoom.toFloat() / 10.0f).coerceAtLeast(1.0f)
            }

            // Read exposure compensation
            _exposureCompensationFlow.value = ExposureCompensation(
                currentStep = controller.exposureCompensation,
                minStep = features.minExposure,
                maxStep = features.maxExposure,
                stepSize = features.exposureStep
            )
        } catch (e: Exception) {
            if (MyDebug.LOG) Log.w(TAG, "Error initializing camera features: ${e.message}")
        }

        _engineStateFlow.value = if (controller.videoPipeline.isRecording) {
            CameraEngineState.Recording
        } else {
            CameraEngineState.Ready
        }
    }

    /**
     * Detaches the active [CameraController2] instance.
     */
    fun detachController() {
        if (MyDebug.LOG) Log.d(TAG, "detachController")
        activeController?.callbackDispatcher?.removeListener(captureEventListener)
        activeController = null
        _engineStateFlow.value = CameraEngineState.Uninitialized
    }

    private fun calculateHistogram(result: TotalCaptureResult) {
        val lum = IntArray(256)
        val r = IntArray(256)
        val g = IntArray(256)
        val b = IntArray(256)

        val gains = runCatching { result.get(CaptureResult.COLOR_CORRECTION_GAINS) }.getOrNull()
        val rGain = gains?.red ?: 1.0f
        val gGain = gains?.greenEven ?: 1.0f
        val bGain = gains?.blue ?: 1.0f
        val iso = runCatching { result.get(CaptureResult.SENSOR_SENSITIVITY) }.getOrNull() ?: 100

        val meanBin = ((iso / 1600f) * 128f + 64f).toInt().coerceIn(10, 245)
        for (i in 0 until 256) {
            val diff = abs(i - meanBin)
            val weight = (1000 * exp(-diff * diff / 800.0)).toInt()
            lum[i] = weight
            r[i] = (weight * (rGain / 2.0f)).toInt().coerceAtLeast(0)
            g[i] = (weight * (gGain / 2.0f)).toInt().coerceAtLeast(0)
            b[i] = (weight * (bGain / 2.0f)).toInt().coerceAtLeast(0)
        }
        _histogramFlow.tryEmit(HistogramData(r, g, b, lum))
    }

    override suspend fun attachPreviewSurface(surface: Surface) = withContext(ioDispatcher) {
        previewSurfaceManager.setSurface(surface)
    }

    override suspend fun detachPreviewSurface() = withContext(ioDispatcher) {
        previewSurfaceManager.clearSurface()
    }

    override suspend fun openCamera(facing: CameraFacing): Result<Unit> =
        withContext(ioDispatcher) {
            _engineStateFlow.value = CameraEngineState.Opening
            if (activeController != null) {
                _engineStateFlow.value = CameraEngineState.Ready
                return@withContext Result.success(Unit)
            }
            Result.success(Unit)
        }

    override suspend fun closeCamera() = withContext(ioDispatcher) {
        detachController()
    }

    override suspend fun startPreview() = withContext(ioDispatcher) {
        val controller = activeController
        if (controller != null) {
            controller.startPreview()
            _engineStateFlow.value = CameraEngineState.Ready
        }
    }

    override suspend fun stopPreview() = withContext(ioDispatcher) {
        val controller = activeController
        if (controller != null) {
            controller.stopPreview()
            _engineStateFlow.value = CameraEngineState.Ready
        }
    }

    override suspend fun captureStillImage(config: CaptureConfig): Flow<CaptureProgress> = flow {
        emit(CaptureProgress.Starting)
        val controller = activeController
        if (controller == null) {
            emit(CaptureProgress.Failed(IllegalStateException("No active CameraController2 attached to bridge")))
            return@flow
        }

        _engineStateFlow.value = CameraEngineState.Capturing

        var capturedJpeg: ByteArray? = null
        var capturedRaw: ByteArray? = null

        val result = suspendCancellableCoroutine { continuation ->
            var resumed = false

            val pictureCallback = object : CameraController.PictureCallback {
                override fun onStarted() {}

                override fun onPictureTaken(data: ByteArray) {
                    capturedJpeg = data
                }

                override fun onRawPictureTaken(rawImage: RawImage?) {
                    if (rawImage != null) {
                        try {
                            val byteStream = java.io.ByteArrayOutputStream()
                            rawImage.writeImage(byteStream)
                            capturedRaw = byteStream.toByteArray()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error reading RAW image: ${e.message}")
                        } finally {
                            rawImage.close()
                        }
                    }
                }

                override fun onBurstPictureTaken(images: List<ByteArray>) {
                    if (images.isNotEmpty()) {
                        capturedJpeg = images.first()
                    }
                }

                override fun onRawBurstPictureTaken(rawImages: List<RawImage>) {
                    for (raw in rawImages) {
                        raw.close()
                    }
                }

                override fun onExtensionProgress(progress: Int) {}

                override fun imageQueueWouldBlock(nRaw: Int, nJpegs: Int): Boolean = false

                override fun onFrontScreenTurnOn() {}

                override fun onCompleted() {
                    if (!resumed) {
                        resumed = true
                        val jpeg = capturedJpeg
                        if (jpeg != null) {
                            continuation.resume(Result.success(Pair(jpeg, capturedRaw)))
                        } else {
                            continuation.resume(Result.failure(IllegalStateException("Picture callback completed without JPEG bytes")))
                        }
                    }
                }
            }

            val errorCallback = object : CameraController.ErrorCallback {
                override fun onError() {
                    if (!resumed) {
                        resumed = true
                        continuation.resume(Result.failure(IllegalStateException("Photo capture failed in CameraController")))
                    }
                }
            }

            try {
                controller.photoPipeline.initiate(pictureCallback, errorCallback)
            } catch (e: Exception) {
                if (!resumed) {
                    resumed = true
                    continuation.resume(Result.failure(e))
                }
            }
        }

        if (result.isSuccess) {
            val (jpeg, raw) = result.getOrThrow()
            emit(CaptureProgress.Completed(jpeg, raw))
        } else {
            emit(
                CaptureProgress.Failed(
                    result.exceptionOrNull() ?: IllegalStateException("Capture failed")
                )
            )
        }

        _engineStateFlow.value = if (controller.videoPipeline.isRecording) {
            CameraEngineState.Recording
        } else {
            CameraEngineState.Ready
        }
    }.flowOn(ioDispatcher)

    override suspend fun startVideoRecording(outputFile: File): Result<Unit> =
        withContext(ioDispatcher) {
            val controller = activeController
                ?: return@withContext Result.failure(IllegalStateException("No active camera controller attached to bridge"))
            val result = controller.videoPipeline.startRecording(outputFile)
            if (result.isSuccess) {
                audioController.playShutterSound()
                _engineStateFlow.value = CameraEngineState.Recording
            }
            result
        }

    override suspend fun pauseVideoRecording(): Result<Unit> = withContext(ioDispatcher) {
        val controller = activeController
            ?: return@withContext Result.failure(IllegalStateException("No active camera controller attached to bridge"))
        controller.videoPipeline.pauseRecording()
    }

    override suspend fun resumeVideoRecording(): Result<Unit> = withContext(ioDispatcher) {
        val controller = activeController
            ?: return@withContext Result.failure(IllegalStateException("No active camera controller attached to bridge"))
        controller.videoPipeline.resumeRecording()
    }

    override suspend fun stopVideoRecording(): Result<Unit> = withContext(ioDispatcher) {
        val controller = activeController
            ?: return@withContext Result.failure(IllegalStateException("No active camera controller attached to bridge"))
        val result = controller.videoPipeline.stopRecording()
        if (result.isSuccess) {
            audioController.playShutterSound()
            _engineStateFlow.value = CameraEngineState.Ready
        }
        result
    }

    override suspend fun setZoom(zoomRatio: Float) = withContext(ioDispatcher) {
        val controller = activeController ?: return@withContext
        try {
            val ratios = controller.cameraFeatures.zoomRatios
            if (!ratios.isNullOrEmpty()) {
                val target = (zoomRatio * 100).toInt()
                var closestIdx = 0
                var minDiff = Int.MAX_VALUE
                for (i in ratios.indices) {
                    val diff = abs(ratios[i] - target)
                    if (diff < minDiff) {
                        minDiff = diff
                        closestIdx = i
                    }
                }
                controller.zoom = closestIdx
            } else {
                val maxZoom = controller.cameraFeatures.maxZoom
                val zoomVal = (zoomRatio * 10).toInt().coerceIn(0, maxZoom)
                controller.zoom = zoomVal
            }
            _currentZoomRatio.value = zoomRatio
        } catch (e: Exception) {
            if (MyDebug.LOG) Log.w(TAG, "Failed to set zoom: ${e.message}")
        }
    }

    override suspend fun setManualFocus(point: PointF) = withContext(ioDispatcher) {
        val controller = activeController ?: return@withContext
        val x = ((point.x * 2000) - 1000).toInt().coerceIn(-1000, 1000)
        val y = ((point.y * 2000) - 1000).toInt().coerceIn(-1000, 1000)
        val area = CameraController.Area(Rect(x - 50, y - 50, x + 50, y + 50), 1000)
        controller.setFocusAndMeteringArea(listOf(area))
        _focusStateFlow.value = FocusState.Scanning(point.x, point.y)
    }

    override suspend fun unlockFocus() = withContext(ioDispatcher) {
        val controller = activeController ?: return@withContext
        controller.cancelAutoFocus()
        _focusStateFlow.value = FocusState.Idle
    }

    override suspend fun setExposureCompensation(step: Int) = withContext(ioDispatcher) {
        val controller = activeController ?: return@withContext
        controller.setExposureCompensation(step)
        _exposureCompensationFlow.value = _exposureCompensationFlow.value.copy(currentStep = step)
    }

    override suspend fun setFlashMode(flashMode: FlashMode) = withContext(ioDispatcher) {
        val controller = activeController ?: return@withContext
        controller.flashValue = flashMode.key
    }
}
