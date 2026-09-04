/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.video

import android.location.Location
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.hightechif.openkamera.preview.VideoProfile
import com.hightechif.openkamera.utils.MyDebug
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * High-level coordinator that integrates [VideoRecorderEngine], [VideoSessionManager],
 * and handles lifecycle callbacks, max duration/filesize limits, and seamless continuation.
 */
class VideoRecordingCoordinator(
    val recorderEngine: VideoRecorderEngine = VideoRecorderEngine(),
    val sessionManager: VideoSessionManager = VideoSessionManager()
) {

    companion object {
        private const val TAG = "VideoRecordingCoord"
        const val MIN_SAFE_RESTART_VIDEO_TIME = 1000L
    }

    val sessionState: StateFlow<VideoSessionState> = sessionManager.sessionState

    val isRecording: Boolean
        get() = sessionManager.isRecording

    val isPaused: Boolean
        get() = sessionManager.isPaused

    val maxAmplitude: Int
        get() = recorderEngine.maxAmplitude

    var activeProfile: VideoProfile? = null
        private set

    /**
     * Listener for high-level video recording lifecycle events.
     */
    interface VideoEventListener {
        fun onStartingVideo()
        fun onStartedVideo()
        fun onStoppedVideo(output: VideoSessionOutput)
        fun onRestartedVideo(output: VideoSessionOutput)
        fun onRecordStartError(profile: VideoProfile)
        fun onRecordStopError(profile: VideoProfile)
        fun onFailedCreateVideoFile()
        fun onInfo(what: Int, extra: Int)
        fun onError(what: Int, extra: Int)
        fun onNoFreeSpace()
        fun onRequestManualRestart(isMaxFileSize: Boolean)
        fun onCreateNextVideoFile(fileExtension: String): VideoSessionOutput?
        fun onDeleteUnusedVideo(output: VideoSessionOutput)
    }

    var listener: VideoEventListener? = null

    /**
     * Initiates and starts a video recording session.
     */
    fun startRecording(
        profile: VideoProfile,
        output: VideoSessionOutput,
        maxFileSize: Long,
        maxDurationMs: Long,
        orientationHint: Int = 0,
        location: Location? = null,
        prePrepareCallback: ((MediaRecorder) -> Unit)? = null,
        postPrepareCallback: ((MediaRecorder) -> Unit)? = null
    ): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "startRecording: $output")
        activeProfile = profile
        listener?.onStartingVideo()

        return try {
            val outputFile = if (!output.videoFilename.isNullOrEmpty()) File(output.videoFilename) else null
            val outputFd = output.videoPfdSaf?.fileDescriptor

            recorderEngine.prepare(
                profile = profile,
                maxFileSize = maxFileSize,
                maxDurationMs = maxDurationMs,
                outputFile = outputFile,
                outputFd = outputFd,
                orientationHint = orientationHint,
                location = location,
                onInfoListener = { _, what, extra -> handleVideoInfo(what, extra) },
                onErrorListener = { _, what, extra -> handleVideoError(what, extra) },
                prePrepareCallback = prePrepareCallback,
                postPrepareCallback = postPrepareCallback
            )

            recorderEngine.start()
            sessionManager.startSession(output)
            listener?.onStartedVideo()
            true
        } catch (e: Exception) {
            if (MyDebug.LOG) Log.e(TAG, "failed to start recording", e)
            recorderEngine.reset()
            recorderEngine.release()
            sessionManager.stopSession(cleanupOutputs = true)
            listener?.onDeleteUnusedVideo(output)
            listener?.onRecordStartError(profile)
            false
        }
    }

    /**
     * Pauses the active recording session.
     */
    fun pauseRecording(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "pauseRecording")
        if (!isRecording || isPaused) return false
        val paused = recorderEngine.pause()
        if (paused) {
            sessionManager.pauseSession()
        }
        return paused
    }

    /**
     * Resumes the paused recording session.
     */
    fun resumeRecording(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "resumeRecording")
        if (!isRecording || !isPaused) return false
        val resumed = recorderEngine.resume()
        if (resumed) {
            sessionManager.resumeSession()
        }
        return resumed
    }

    /**
     * Stops the active video recording session.
     */
    fun stopRecording(isIntentional: Boolean = true): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "stopRecording: intentional=$isIntentional")
        val currentOutput = sessionManager.activeOutput
        val profile = activeProfile ?: VideoProfile()

        val success = recorderEngine.stop()
        recorderEngine.reset()
        recorderEngine.release()

        val recordedDuration = sessionManager.calculateCurrentDurationMs()
        sessionManager.stopSession()

        if (!success) {
            currentOutput?.let {
                listener?.onDeleteUnusedVideo(it)
            }
            if (recordedDuration > 2000L) {
                listener?.onRecordStopError(profile)
            }
            return false
        }

        if (currentOutput != null) {
            listener?.onStoppedVideo(currentOutput)
        }
        return true
    }

    /**
     * Handles MediaRecorder onInfo events including max file size and max duration.
     */
    fun handleVideoInfo(
        what: Int,
        extra: Int,
        autoRestartOnMaxFileSize: Boolean = true,
        maxDurationPref: Long = 0L,
        hasFreeSpaceCheck: () -> Boolean = { true }
    ) {
        if (MyDebug.LOG) Log.d(TAG, "handleVideoInfo: what=$what extra=$extra")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING &&
            autoRestartOnMaxFileSize
        ) {
            handleMaxFileSizeApproaching(maxDurationPref, hasFreeSpaceCheck)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            what == MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED &&
            autoRestartOnMaxFileSize
        ) {
            handleNextOutputFileStarted()
        } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED && autoRestartOnMaxFileSize) {
            listener?.onRequestManualRestart(isMaxFileSize = true)
        } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
            listener?.onRequestManualRestart(isMaxFileSize = false)
        } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
            stopRecording()
        }

        listener?.onInfo(what, extra)
    }

    private fun handleMaxFileSizeApproaching(
        maxDurationPref: Long,
        hasFreeSpaceCheck: () -> Boolean
    ) {
        if (maxDurationPref > 0) {
            if (MyDebug.LOG) Log.d(TAG, "skip setNextOutputFile because setMaxDuration is active")
            return
        }
        val profile = activeProfile ?: return
        if (profile.fileExtension.equals("3gp", ignoreCase = true)) {
            if (MyDebug.LOG) Log.d(TAG, "seamless restart not supported for 3gp")
            return
        }
        if (!hasFreeSpaceCheck()) {
            if (MyDebug.LOG) Log.d(TAG, "not enough free space for next output file")
            listener?.onNoFreeSpace()
            return
        }

        val nextOutput = listener?.onCreateNextVideoFile(profile.fileExtension) ?: return
        try {
            if (!nextOutput.videoFilename.isNullOrEmpty()) {
                recorderEngine.setNextOutputFile(File(nextOutput.videoFilename))
            } else if (nextOutput.videoPfdSaf != null) {
                recorderEngine.setNextOutputFile(nextOutput.videoPfdSaf.fileDescriptor)
            }
            sessionManager.prepareSeamlessRestart(nextOutput)
        } catch (e: Exception) {
            if (MyDebug.LOG) Log.e(TAG, "failed to setNextOutputFile", e)
            nextOutput.close()
            listener?.onDeleteUnusedVideo(nextOutput)
        }
    }

    private fun handleNextOutputFileStarted() {
        val committed = sessionManager.commitSeamlessRestart()
        if (committed != null) {
            listener?.onRestartedVideo(committed)
        }
    }

    /**
     * Handles MediaRecorder onError events.
     */
    fun handleVideoError(what: Int, extra: Int) {
        if (MyDebug.LOG) Log.e(TAG, "handleVideoError: what=$what extra=$extra")
        stopRecording(isIntentional = false)
        listener?.onError(what, extra)
    }

    /**
     * Total elapsed duration in milliseconds considering pauses.
     */
    fun getRecordingDurationMs(): Long {
        return sessionManager.calculateCurrentDurationMs()
    }

    /**
     * Destroys resources and releases active media recorders.
     */
    fun destroy() {
        recorderEngine.release()
        sessionManager.destroy()
        activeProfile = null
        listener = null
    }
}
