/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import com.hightechif.openkamera.cameracontroller.dispatcher.Camera2StateCallbackDispatcher
import com.hightechif.openkamera.cameracontroller.lifecycle.Camera2SessionManager
import com.hightechif.openkamera.utils.MyDebug

/**
 * Hooks into the legacy video recording flow for [CameraController2].
 *
 * `Preview` owns the [MediaRecorder] lifecycle (configure, start, pause, stop) and calls
 * these hooks around it. This class does not start or stop recording itself.
 *
 * Responsibilities:
 * - Pre-prepare hook: audio cue for video start and recorder hand-off
 * - Post-prepare hook: TEMPLATE_RECORD request and video capture session creation
 * - Video snapshot (still picture capture during active recording) capture request creation
 * - Tracking the high-speed video flags read by [CameraController2]
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
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to create capture request for video: ${e.message}")
            }
            e.printStackTrace()
            throw CameraControllerException()
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
}
