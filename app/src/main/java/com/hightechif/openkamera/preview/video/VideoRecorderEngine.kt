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
import java.io.File
import java.io.FileDescriptor

/**
 * Low-level engine wrapping Android [MediaRecorder] hardware lifecycle,
 * audio routing, error boundaries, and seamless output continuation.
 */
class VideoRecorderEngine(
    private val recorderFactory: () -> MediaRecorder = { MediaRecorder() }
) {

    companion object {
        private const val TAG = "VideoRecorderEngine"
    }

    var mediaRecorder: MediaRecorder? = null
        private set

    var isPrepared: Boolean = false
        private set

    var isRecording: Boolean = false
        private set

    var isPaused: Boolean = false
        private set

    val maxAmplitude: Int
        get() {
            return try {
                if (isRecording && !isPaused) {
                    mediaRecorder?.maxAmplitude ?: 0
                } else {
                    0
                }
            } catch (e: Exception) {
                if (MyDebug.LOG) Log.e(TAG, "failed to get maxAmplitude: ${e.message}")
                0
            }
        }

    /**
     * Prepares the [MediaRecorder] with the specified configuration, listeners, and callbacks.
     */
    fun prepare(
        profile: VideoProfile,
        maxFileSize: Long,
        maxDurationMs: Long,
        outputFile: File? = null,
        outputFd: FileDescriptor? = null,
        orientationHint: Int = 0,
        location: Location? = null,
        onInfoListener: ((MediaRecorder, Int, Int) -> Unit)? = null,
        onErrorListener: ((MediaRecorder, Int, Int) -> Unit)? = null,
        prePrepareCallback: ((MediaRecorder) -> Unit)? = null,
        postPrepareCallback: ((MediaRecorder) -> Unit)? = null
    ) {
        if (MyDebug.LOG) Log.d(TAG, "prepare recorder")
        release()

        val recorder = recorderFactory()
        mediaRecorder = recorder

        if (onInfoListener != null) {
            recorder.setOnInfoListener { mr, what, extra ->
                onInfoListener(mr, what, extra)
            }
        }
        if (onErrorListener != null) {
            recorder.setOnErrorListener { mr, what, extra ->
                onErrorListener(mr, what, extra)
            }
        }

        prePrepareCallback?.invoke(recorder)

        if (location != null) {
            recorder.setLocation(location.latitude.toFloat(), location.longitude.toFloat())
        }

        if (MyDebug.LOG) Log.d(TAG, "copying profile to media recorder")
        profile.copyToMediaRecorder(recorder)

        if (maxFileSize > 0) {
            try {
                recorder.setMaxFileSize(maxFileSize)
            } catch (e: RuntimeException) {
                if (MyDebug.LOG) Log.e(TAG, "failed to setMaxFileSize: $maxFileSize", e)
            }
        }

        if (maxDurationMs > 0) {
            recorder.setMaxDuration(maxDurationMs.toInt())
        }

        if (outputFile != null) {
            recorder.setOutputFile(outputFile.absolutePath)
        } else if (outputFd != null) {
            recorder.setOutputFile(outputFd)
        }

        recorder.setOrientationHint(orientationHint)

        if (MyDebug.LOG) Log.d(TAG, "calling mediaRecorder.prepare()")
        recorder.prepare()
        isPrepared = true

        postPrepareCallback?.invoke(recorder)
    }

    /**
     * Starts video recording.
     */
    fun start() {
        if (MyDebug.LOG) Log.d(TAG, "start")
        val recorder = mediaRecorder ?: throw IllegalStateException("MediaRecorder is null")
        recorder.start()
        isRecording = true
        isPaused = false
    }

    /**
     * Pauses video recording on Android 7.0+ (API 24+).
     */
    fun pause(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "pause")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "pause requires Android N (API 24+)")
            return false
        }
        val recorder = mediaRecorder ?: return false
        if (isRecording && !isPaused) {
            recorder.pause()
            isPaused = true
            return true
        }
        return false
    }

    /**
     * Resumes video recording on Android 7.0+ (API 24+).
     */
    fun resume(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "resume")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "resume requires Android N (API 24+)")
            return false
        }
        val recorder = mediaRecorder ?: return false
        if (isRecording && isPaused) {
            recorder.resume()
            isPaused = false
            return true
        }
        return false
    }

    /**
     * Stops video recording with defensive error recovery.
     * Returns true if stop succeeded without error, false if a RuntimeException was caught.
     */
    fun stop(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "stop")
        val recorder = mediaRecorder ?: return false
        recorder.setOnErrorListener(null)
        recorder.setOnInfoListener(null)
        var success = true
        try {
            recorder.stop()
        } catch (e: RuntimeException) {
            if (MyDebug.LOG) Log.e(TAG, "runtime exception when stopping video recorder", e)
            success = false
        } finally {
            isRecording = false
            isPaused = false
        }
        return success
    }

    /**
     * Sets the next output file for seamless file splitting on Android 8.0+ (API 26+).
     */
    fun setNextOutputFile(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val recorder = mediaRecorder ?: throw IllegalStateException("MediaRecorder is null")
            recorder.setNextOutputFile(file)
        } else {
            throw UnsupportedOperationException("setNextOutputFile requires Android O+")
        }
    }

    /**
     * Sets the next output file descriptor for seamless file splitting on Android 8.0+ (API 26+).
     */
    fun setNextOutputFile(fd: FileDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val recorder = mediaRecorder ?: throw IllegalStateException("MediaRecorder is null")
            recorder.setNextOutputFile(fd)
        } else {
            throw UnsupportedOperationException("setNextOutputFile requires Android O+")
        }
    }

    /**
     * Resets the active MediaRecorder.
     */
    fun reset() {
        if (MyDebug.LOG) Log.d(TAG, "reset")
        try {
            mediaRecorder?.reset()
        } catch (e: Exception) {
            if (MyDebug.LOG) Log.e(TAG, "exception during mediaRecorder.reset()", e)
        } finally {
            isPrepared = false
            isRecording = false
            isPaused = false
        }
    }

    /**
     * Releases the active MediaRecorder and clears references.
     */
    fun release() {
        if (MyDebug.LOG) Log.d(TAG, "release")
        val recorder = mediaRecorder
        if (recorder != null) {
            try {
                recorder.reset()
            } catch (e: Exception) {
                if (MyDebug.LOG) Log.e(TAG, "exception during reset before release", e)
            }
            try {
                recorder.release()
            } catch (e: Exception) {
                if (MyDebug.LOG) Log.e(TAG, "exception during mediaRecorder.release()", e)
            }
            mediaRecorder = null
        }
        isPrepared = false
        isRecording = false
        isPaused = false
    }
}
