/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.dispatcher

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.view.Surface
import com.hightechif.openkamera.utils.MyDebug
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Interface for listening to capture session events.
 */
interface CaptureEventListener {
    fun onStarted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        timestamp: Long,
        frameNumber: Long
    ) {}

    fun onProgressed(
        session: CameraCaptureSession,
        request: CaptureRequest,
        partialResult: CaptureResult
    ) {}

    fun onCompleted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult
    ) {}

    fun onFailed(
        session: CameraCaptureSession,
        request: CaptureRequest,
        failure: CaptureFailure
    ) {}

    fun onSequenceCompleted(
        session: CameraCaptureSession,
        sequenceId: Int,
        frameNumber: Long
    ) {}

    fun onSequenceAborted(
        session: CameraCaptureSession,
        sequenceId: Int
    ) {}

    fun onBufferLost(
        session: CameraCaptureSession,
        request: CaptureRequest,
        target: Surface,
        frameNumber: Long
    ) {}
}

/**
 * Multi-subscriber dispatcher for CameraCaptureSession.CaptureCallback events.
 * Enables concurrent observation by preview stream monitors, autofocus controllers,
 * histogram/analytics consumers, and capture trackers without tight coupling.
 */
class Camera2StateCallbackDispatcher : CameraCaptureSession.CaptureCallback() {

    companion object {
        private const val TAG = "Camera2CallbackDispatch"
    }

    private val listeners = CopyOnWriteArraySet<CaptureEventListener>()

    /**
     * Registers a listener to receive capture callbacks.
     */
    fun addListener(listener: CaptureEventListener): Boolean {
        return listeners.add(listener)
    }

    /**
     * Unregisters a listener.
     */
    fun removeListener(listener: CaptureEventListener): Boolean {
        return listeners.remove(listener)
    }

    /**
     * Removes all registered listeners.
     */
    fun clearListeners() {
        listeners.clear()
    }

    val listenerCount: Int
        get() = listeners.size

    override fun onCaptureStarted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        timestamp: Long,
        frameNumber: Long
    ) {
        super.onCaptureStarted(session, request, timestamp, frameNumber)
        for (listener in listeners) {
            try {
                listener.onStarted(session, request, timestamp, frameNumber)
            } catch (e: Throwable) {
                if (MyDebug.LOG) Log.e(TAG, "Error in onCaptureStarted listener", e)
            }
        }
    }

    override fun onCaptureProgressed(
        session: CameraCaptureSession,
        request: CaptureRequest,
        partialResult: CaptureResult
    ) {
        super.onCaptureProgressed(session, request, partialResult)
        for (listener in listeners) {
            try {
                listener.onProgressed(session, request, partialResult)
            } catch (e: Throwable) {
                if (MyDebug.LOG) Log.e(TAG, "Error in onCaptureProgressed listener", e)
            }
        }
    }

    override fun onCaptureCompleted(
        session: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult
    ) {
        super.onCaptureCompleted(session, request, result)
        for (listener in listeners) {
            try {
                listener.onCompleted(session, request, result)
            } catch (e: Throwable) {
                if (MyDebug.LOG) Log.e(TAG, "Error in onCaptureCompleted listener", e)
            }
        }
    }

    override fun onCaptureFailed(
        session: CameraCaptureSession,
        request: CaptureRequest,
        failure: CaptureFailure
    ) {
        super.onCaptureFailed(session, request, failure)
        for (listener in listeners) {
            try {
                listener.onFailed(session, request, failure)
            } catch (e: Throwable) {
                if (MyDebug.LOG) Log.e(TAG, "Error in onCaptureFailed listener", e)
            }
        }
    }

    override fun onCaptureSequenceCompleted(
        session: CameraCaptureSession,
        sequenceId: Int,
        frameNumber: Long
    ) {
        super.onCaptureSequenceCompleted(session, sequenceId, frameNumber)
        for (listener in listeners) {
            try {
                listener.onSequenceCompleted(session, sequenceId, frameNumber)
            } catch (e: Throwable) {
                if (MyDebug.LOG) Log.e(TAG, "Error in onCaptureSequenceCompleted listener", e)
            }
        }
    }

    override fun onCaptureSequenceAborted(
        session: CameraCaptureSession,
        sequenceId: Int
    ) {
        super.onCaptureSequenceAborted(session, sequenceId)
        for (listener in listeners) {
            try {
                listener.onSequenceAborted(session, sequenceId)
            } catch (e: Throwable) {
                if (MyDebug.LOG) Log.e(TAG, "Error in onCaptureSequenceAborted listener", e)
            }
        }
    }

    override fun onCaptureBufferLost(
        session: CameraCaptureSession,
        request: CaptureRequest,
        target: Surface,
        frameNumber: Long
    ) {
        super.onCaptureBufferLost(session, request, target, frameNumber)
        for (listener in listeners) {
            try {
                listener.onBufferLost(session, request, target, frameNumber)
            } catch (e: Throwable) {
                if (MyDebug.LOG) Log.e(TAG, "Error in onCaptureBufferLost listener", e)
            }
        }
    }
}
