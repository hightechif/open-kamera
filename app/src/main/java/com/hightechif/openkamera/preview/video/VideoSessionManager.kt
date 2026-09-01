/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.video

import android.util.Log
import com.hightechif.openkamera.utils.MyDebug
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages video recording outputs, duration calculation, and Android 8+ seamless restart files.
 */
class VideoSessionManager {

    companion object {
        private const val TAG = "VideoSessionManager"
    }

    private val _sessionState = MutableStateFlow<VideoSessionState>(VideoSessionState.Idle)
    val sessionState: StateFlow<VideoSessionState> = _sessionState.asStateFlow()

    var activeOutput: VideoSessionOutput? = null
        private set

    var nextOutput: VideoSessionOutput? = null
        private set

    var videoStartTime: Long = 0
        private set

    var videoAccumulatedTime: Long = 0
        private set

    var isPaused: Boolean = false
        private set

    val isRecording: Boolean
        get() = _sessionState.value is VideoSessionState.Recording || _sessionState.value is VideoSessionState.RestartingMaxFileSize

    /**
     * Initializes a new video recording session with the given output file/descriptor.
     */
    fun startSession(output: VideoSessionOutput, startTimeMs: Long = System.currentTimeMillis()) {
        if (MyDebug.LOG) Log.d(TAG, "startSession: $output at $startTimeMs")
        activeOutput?.close()
        activeOutput = output
        nextOutput?.close()
        nextOutput = null
        videoStartTime = startTimeMs
        videoAccumulatedTime = 0L
        isPaused = false
        _sessionState.value = VideoSessionState.Recording(output, startTimeMs, isPaused = false)
    }

    /**
     * Pauses the video recording session and accumulates elapsed time.
     */
    fun pauseSession(pauseTimeMs: Long = System.currentTimeMillis()) {
        if (MyDebug.LOG) Log.d(TAG, "pauseSession at $pauseTimeMs")
        if (videoStartTime > 0L) {
            videoAccumulatedTime += (pauseTimeMs - videoStartTime)
            videoStartTime = 0L
        }
        isPaused = true
        activeOutput?.let {
            _sessionState.value = VideoSessionState.Recording(it, 0L, isPaused = true)
        }
    }

    /**
     * Resumes the video recording session from pause.
     */
    fun resumeSession(resumeTimeMs: Long = System.currentTimeMillis()) {
        if (MyDebug.LOG) Log.d(TAG, "resumeSession at $resumeTimeMs")
        videoStartTime = resumeTimeMs
        isPaused = false
        activeOutput?.let {
            _sessionState.value = VideoSessionState.Recording(it, resumeTimeMs, isPaused = false)
        }
    }

    /**
     * Sets the next output file for seamless restart on Android 8+ (setNextOutputFile).
     */
    fun prepareSeamlessRestart(next: VideoSessionOutput) {
        if (MyDebug.LOG) Log.d(TAG, "prepareSeamlessRestart: $next")
        nextOutput?.close()
        nextOutput = next
        _sessionState.value = VideoSessionState.RestartingMaxFileSize(
            previousOutput = activeOutput ?: VideoSessionOutput(),
            nextOutput = next
        )
    }

    /**
     * Commits the seamless restart when MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED is received.
     */
    fun commitSeamlessRestart(): VideoSessionOutput? {
        val next = nextOutput
        if (next != null) {
            if (MyDebug.LOG) Log.d(TAG, "commitSeamlessRestart to: $next")
            activeOutput = next
            nextOutput = null
            _sessionState.value = VideoSessionState.Recording(next, System.currentTimeMillis(), isPaused = false)
        }
        return next
    }

    /**
     * Clears any unused next output file descriptor.
     */
    fun discardNextOutput() {
        if (nextOutput != null) {
            if (MyDebug.LOG) Log.d(TAG, "discardNextOutput")
            nextOutput?.close()
            nextOutput = null
        }
    }

    /**
     * Calculates the total recording duration in milliseconds taking pauses into account.
     */
    fun calculateCurrentDurationMs(
        currentTimeMs: Long = System.currentTimeMillis(),
        forcePausedState: Boolean = this.isPaused
    ): Long {
        if (videoStartTime == 0L || forcePausedState) {
            return videoAccumulatedTime
        }
        return videoAccumulatedTime + (currentTimeMs - videoStartTime)
    }

    /**
     * Stops the video session and cleans up active file descriptors.
     */
    fun stopSession(cleanupOutputs: Boolean = false) {
        if (MyDebug.LOG) Log.d(TAG, "stopSession")
        if (cleanupOutputs) {
            activeOutput?.close()
            nextOutput?.close()
        }
        activeOutput = null
        nextOutput = null
        videoStartTime = 0L
        videoAccumulatedTime = 0L
        isPaused = false
        _sessionState.value = VideoSessionState.Stopped
    }

    fun destroy() {
        stopSession(cleanupOutputs = true)
        _sessionState.value = VideoSessionState.Idle
    }
}
