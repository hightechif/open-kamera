/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import com.hightechif.openkamera.cameracontroller.dispatcher.Camera2StateCallbackDispatcher
import com.hightechif.openkamera.cameracontroller.lifecycle.Camera2SessionManager
import com.hightechif.openkamera.utils.MyDebug
import java.io.File
import java.util.concurrent.Executor

/**
 * Encapsulates the video recording pipeline and session coordinator for [CameraController2].
 *
 * Responsibilities:
 * - Managing video recording state (idle, preparing, recording, paused, stopping)
 * - MediaRecorder setup, video profile resolution, and output file preparation
 * - Video capture session and constrained high-speed capture session management
 * - Video snapshot (still picture capture during active recording) capture request creation
 * - Audio cues for video start/stop
 *
 * 📖 Learn more: `docs/module-04-video/01-video-pipeline-overview.md`
 *
 * @param controller Reference to the backing [CameraController2]
 * @param sessionManager Lifecycle and session coordinator
 * @param cameraSettings Settings manager for capture request builders
 * @param callbackDispatcher Capture callback dispatcher for HAL event observation
 */
class Camera2VideoPipeline(
    private val controller: CameraController2,
    private val sessionManager: Camera2SessionManager,
    private val cameraSettings: Camera2Settings,
    private val callbackDispatcher: Camera2StateCallbackDispatcher
) {

    companion object {
        private const val TAG = "Camera2VideoPipeline"
    }

    var previewIsVideoMode: Boolean = false
    var wantVideoHighSpeed: Boolean = false
    var isVideoHighSpeed: Boolean = false
    var videoRecorderSurface: Surface? = null

    var activeVideoRecorder: MediaRecorder? = null
        private set
    var isRecording: Boolean = false
        private set
    var isPaused: Boolean = false
        private set
    var activeOutputFile: File? = null
        private set

    /**
     * Pre-prepare hook called before [MediaRecorder.prepare].
     * Plays the video recording start cue.
     */
    fun initVideoRecorderPrePrepare(videoRecorder: MediaRecorder?) {
        if (MyDebug.LOG) Log.d(TAG, "initVideoRecorderPrePrepare")
        controller.blockForExtensions()
        activeVideoRecorder = videoRecorder
        controller.playSound(MediaActionSound.START_VIDEO_RECORDING)
    }

    /**
     * Post-prepare hook called after [MediaRecorder.prepare] and before [MediaRecorder.start].
     * Prepares the TEMPLATE_RECORD capture request and initiates video capture session creation.
     */
    @Throws(CameraControllerException::class)
    fun initVideoRecorderPostPrepare(
        videoRecorder: MediaRecorder?,
        wantPhotoVideoRecording: Boolean
    ) {
        if (MyDebug.LOG) Log.d(TAG, "initVideoRecorderPostPrepare")
        val camera = sessionManager.cameraDevice
        if (camera == null) {
            Log.e(TAG, "no camera")
            throw CameraControllerException()
        }
        controller.blockForExtensions()
        try {
            if (MyDebug.LOG) Log.d(TAG, "obtain video_recorder surface")
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            controller.previewBuilder = builder
            previewIsVideoMode = true
            builder.set(
                CaptureRequest.CONTROL_CAPTURE_INTENT,
                CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
            )
            cameraSettings.setupBuilder(builder, false)
            createVideoSession(videoRecorder, wantPhotoVideoRecording)
            activeVideoRecorder = videoRecorder
            isRecording = true
            isPaused = false
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to create capture request for video: ${e.message}")
            }
            e.printStackTrace()
            throw CameraControllerException()
        }
    }

    /**
     * Configures a [MediaRecorder] with video resolution, fps, encoders, and output file.
     */
    fun setupMediaRecorder(
        outputFile: File,
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 30,
        bitRate: Int = 10_000_000
    ): MediaRecorder {
        val recorder = MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(fps)
            recorder.setVideoEncodingBitRate(bitRate)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.prepare()
            activeOutputFile = outputFile
            return recorder
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
    }

    /**
     * Delegates capture session creation with videoRecorder surfaces.
     */
    @Throws(CameraControllerException::class)
    fun createVideoSession(
        videoRecorder: MediaRecorder?,
        wantPhotoVideoRecording: Boolean
    ) {
        synchronized(controller.backgroundCameraLock) {
            videoRecorderSurface = videoRecorder?.surface
            if (MyDebug.LOG) Log.d(TAG, "videoRecorderSurface: $videoRecorderSurface")
        }
        controller.createCaptureSession(videoRecorder, wantPhotoVideoRecording)
    }

    /**
     * Creates a high speed capture session for constrained high-speed recording (slow-motion).
     */
    @Throws(CameraAccessException::class)
    fun createHighSpeedCaptureSession(
        camera: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        handler: Handler?,
        executor: Executor? = null,
        outputs: List<OutputConfiguration>? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && outputs != null && executor != null) {
            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_HIGH_SPEED,
                outputs,
                executor,
                callback
            )
            camera.createCaptureSession(sessionConfiguration)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            camera.createConstrainedHighSpeedCaptureSession(surfaces, callback, handler)
        } else {
            throw UnsupportedOperationException("High speed video requires Android M (API 23)+")
        }
        isVideoHighSpeed = true
    }

    /**
     * Creates a capture request for a still picture snapshot during active video recording.
     * Uses [CameraDevice.TEMPLATE_VIDEO_SNAPSHOT] and does not stop repeating preview/video recording.
     */
    @Throws(CameraAccessException::class)
    fun createVideoSnapshotRequest(
        camera: CameraDevice,
        imageReaderSurface: Surface,
        hasIso: Boolean
    ): CaptureRequest.Builder {
        if (MyDebug.LOG) Log.d(TAG, "createVideoSnapshotRequest")
        val stillBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT)
        stillBuilder.setTag(
            CameraController2.RequestTagObject(
                CameraController2.RequestTagType.CAPTURE
            )
        )
        cameraSettings.setupBuilder(stillBuilder, true)
        return stillBuilder
    }

    /**
     * High-level entry point to start video recording to a specific [outputFile].
     */
    fun startRecording(outputFile: File): Result<Unit> = runCatching {
        val recorder = setupMediaRecorder(outputFile)
        initVideoRecorderPrePrepare(recorder)
        initVideoRecorderPostPrepare(recorder, wantPhotoVideoRecording = true)
        recorder.start()
        isRecording = true
        isPaused = false
        activeOutputFile = outputFile
    }

    /**
     * Pauses active video recording (API 24+).
     */
    fun pauseRecording(): Result<Unit> = runCatching {
        if (!isRecording) error("No active video recording to pause")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activeVideoRecorder?.pause()
            isPaused = true
        } else {
            error("Pause video recording is only supported on Android N (API 24)+")
        }
    }

    /**
     * Resumes paused video recording (API 24+).
     */
    fun resumeRecording(): Result<Unit> = runCatching {
        if (!isRecording) error("No active video recording to resume")
        if (!isPaused) return@runCatching
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            activeVideoRecorder?.resume()
            isPaused = false
        } else {
            error("Resume video recording is only supported on Android N (API 24)+")
        }
    }

    /**
     * Stops and finalizes video recording.
     */
    fun stopRecording(): Result<Unit> = runCatching {
        if (!isRecording && activeVideoRecorder == null) {
            return@runCatching
        }
        val recorder = activeVideoRecorder
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder: ${e.message}", e)
        } finally {
            try {
                recorder?.reset()
                recorder?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaRecorder: ${e.message}", e)
            }
            activeVideoRecorder = null
            isRecording = false
            isPaused = false
            activeOutputFile = null
            previewIsVideoMode = false
            isVideoHighSpeed = false
            controller.reconnect()
        }
    }

    /**
     * Releases video pipeline resources.
     */
    fun release() {
        if (isRecording) {
            try {
                stopRecording()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording on release: ${e.message}")
            }
        }
        activeVideoRecorder = null
        videoRecorderSurface = null
        previewIsVideoMode = false
        isVideoHighSpeed = false
        wantVideoHighSpeed = false
    }
}
