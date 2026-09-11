/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.os.Handler
import android.util.Log
import com.hightechif.openkamera.cameracontroller.CameraController.ErrorCallback
import com.hightechif.openkamera.cameracontroller.CameraController.PictureCallback
import com.hightechif.openkamera.cameracontroller.extension.Camera2DeviceQuirks
import com.hightechif.openkamera.utils.MyDebug

/**
 * Listener interface through which [MyCaptureCallback] inside [CameraController2] reports
 * capture-result events into [Camera2PhotoPipeline] without creating a direct dependency.
 *
 * All callbacks are invoked from the camera background thread, NOT the UI thread.
 * Implementations must not assume thread affinity.
 */
interface CaptureStateListener {
    /**
     * Called for every frame delivered to [MyCaptureCallback.onCaptureCompleted].
     * Used to advance the precapture AE/AF state machine.
     */
    fun onCaptureResultReceived(result: CaptureResult)

    /**
     * Called when a still-capture request fails in [MyCaptureCallback.onCaptureFailed].
     */
    fun onCaptureFailed()
}

/**
 * Encapsulates the photo-capture state machine for [CameraController2].
 *
 * Responsibilities:
 * - Deciding which precapture path to take (skip / fake-flash / standard AE trigger)
 * - Driving `runPrecapture()`, `runFakePrecapture()`, and `takePictureAfterPrecapture()`
 * - Building still-capture [CaptureRequest] objects (single shot; burst delegated to [CameraController2])
 * - Performing post-capture AE restore, delegating device quirk logic to [Camera2DeviceQuirks]
 *
 * This class holds a reference to a [CameraController2.PipelineContext] so it can access the
 * mutable camera state it needs while avoiding a dependency on the entire CC2 class.
 *
 * ### Thread safety
 * Methods that touch camera state must be called while holding [CameraController2.backgroundCameraLock].
 * The public entry point [initiate] follows the same lock-release-callback pattern as CC2.
 *
 * @param context Access to the live camera state managed by [CameraController2].
 * @param deviceQuirks Device-specific behavior flags.
 */
class Camera2PhotoPipeline(
    private val context: PipelineContext,
    val deviceQuirks: Camera2DeviceQuirks = Camera2DeviceQuirks()
) : CaptureStateListener {

    companion object {
        private const val TAG = "Camera2PhotoPipeline"
    }

    /**
     * Minimal interface providing [Camera2PhotoPipeline] with access to the mutable camera state
     * it needs. Implemented by [CameraController2].
     *
     * All properties and methods on this interface are accessed while holding [backgroundCameraLock]
     * unless explicitly documented otherwise.
     */
    interface PipelineContext {
        val backgroundCameraLock: Any
        val sessionType: SessionTypeCompat

        /** The currently active flash mode string (e.g. "flash_off", "flash_auto", "flash_on", "flash_torch"). */
        val flashValue: String

        /** True if the capture is in manual ISO / exposure mode. */
        val hasIso: Boolean
        val previewBuilder: CaptureRequest.Builder?
        val imageReaderRaw: Any? // non-null → RAW requested
        val useFakePrecaptureMode: Boolean
        val captureResultAe: Int?
        val isFlashRequired: Boolean
        val fakePrecaptureUseFlash: Boolean
        val fakePrecaptureUseFlashTimeMs: Long
        val captureResultHasIso: Boolean
        val captureResultIso: Int
        val handler: Handler?

        // Mutable state the pipeline may write
        var pictureCb: PictureCallback?
        var jpegTodo: Boolean
        var rawTodo: Boolean
        var doneAllCaptures: Boolean
        var takePictureErrorCb: ErrorCallback?
        var fakePrecaptureTorchPerformed: Boolean
        var state: Int
        var precaptureStateChangeTimeMs: Long
        var testFakeFlashPrecapture: Int

        fun hasCaptureSession(): Boolean
        fun fireAutoFlash(): Boolean
        fun blockForExtensions()
        fun setRepeatingRequest(request: CaptureRequest)
        fun runPrecaptureImpl()
        fun runFakePrecaptureImpl()
        fun takePictureAfterPrecaptureImpl()

        // Session-type shim (avoids leaking the private enum)
        enum class SessionTypeCompat { NORMAL, EXTENSION }
    }

    // ── CaptureStateListener ──────────────────────────────────────────────────

    override fun onCaptureResultReceived(result: CaptureResult) {
        // Reserved: the MyCaptureCallback forward path. State-machine transitions
        // for precapture are currently driven by the existing process() method
        // inside CC2. This hook point is wired but not yet fully migrated.
        if (MyDebug.LOG) Log.d(TAG, "onCaptureResultReceived (delegated to CC2 process())")
    }

    override fun onCaptureFailed() {
        if (MyDebug.LOG) Log.d(TAG, "onCaptureFailed — pipeline reset")
        synchronized(context.backgroundCameraLock) {
            context.jpegTodo = false
            context.rawTodo = false
            context.pictureCb = null
        }
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Entry point for a capture request. Decides the precapture path and calls the appropriate
     * method. Must be called from the UI thread (or any thread that does NOT hold
     * [CameraController2.backgroundCameraLock]).
     *
     * Mirrors the logic previously in [CameraController2.takePicture].
     */
    fun initiate(picture: PictureCallback, error: ErrorCallback) {
        if (MyDebug.LOG) Log.d(TAG, "initiate")
        val debugTime = if (MyDebug.LOG) System.currentTimeMillis() else 0L

        var callTakePictureAfterPrecapture = false
        var callRunFakePrecapture = false
        var callRunPrecapture = false

        synchronized(context.backgroundCameraLock) {
            if (!context.hasCaptureSession()) {
                if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                error.onError()
                return
            }
            context.pictureCb = picture
            context.jpegTodo = true
            context.rawTodo = context.imageReaderRaw != null
            context.doneAllCaptures = false
            context.takePictureErrorCb = error
            context.fakePrecaptureTorchPerformed = false

            if (MyDebug.LOG) {
                Log.d(TAG, "current flash value: ${context.flashValue}")
                Log.d(TAG, "use_fake_precapture_mode: ${context.useFakePrecaptureMode}")
            }

            when {
                context.sessionType == PipelineContext.SessionTypeCompat.EXTENSION -> {
                    // Precapture not supported for extension sessions
                    callTakePictureAfterPrecapture = true
                }

                context.flashValue == "flash_off"
                        || context.flashValue == "flash_torch"
                        || context.flashValue == "flash_frontscreen_torch" -> {
                    // No precapture needed when flash is off or torch mode
                    callTakePictureAfterPrecapture = true
                }

                context.useFakePrecaptureMode -> {
                    // Fake-flash precapture path (torch-based AE convergence)
                    val autoFlash = context.flashValue == "flash_auto"
                            || context.flashValue == "flash_frontscreen_auto"
                    val flashMode = context.previewBuilder?.get(CaptureRequest.FLASH_MODE)
                    if (MyDebug.LOG) Log.d(TAG, "flash_mode: $flashMode")
                    when {
                        autoFlash && !context.fireAutoFlash() -> {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "fake precapture: bright enough, skipping flash"
                            )
                            callTakePictureAfterPrecapture = true
                        }

                        flashMode != null && flashMode == CameraMetadata.FLASH_MODE_TORCH -> {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "fake precapture: torch already on from autofocus"
                            )
                            // Torch was already turned on for autofocus; AE scanning may have already occurred.
                            // Skip directly to STATE_WAITING_FAKE_PRECAPTURE_DONE to wait for AE to settle.
                            context.fakePrecaptureTorchPerformed = true
                            context.testFakeFlashPrecapture++
                            context.state = CameraController2.STATE_WAITING_FAKE_PRECAPTURE_DONE
                            context.precaptureStateChangeTimeMs = System.currentTimeMillis()
                        }

                        else -> callRunFakePrecapture = true
                    }
                }

                else -> {
                    // Standard flash (flash_auto or flash_on): use Camera2 AE precapture trigger
                    val needsFlash = context.captureResultAe != null
                            && context.captureResultAe != CaptureResult.CONTROL_AE_STATE_CONVERGED
                    if (context.flashValue == "flash_auto" && !needsFlash) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "flash_auto but no flash needed, skipping precapture"
                        )
                        callTakePictureAfterPrecapture = true
                    } else {
                        callRunPrecapture = true
                    }
                }
            }
        }

        // Important: call methods outside of lock so they can invoke callbacks without a lock
        if (callTakePictureAfterPrecapture) {
            context.takePictureAfterPrecaptureImpl()
        } else if (callRunFakePrecapture) {
            context.runFakePrecaptureImpl()
        } else if (callRunPrecapture) {
            context.runPrecaptureImpl()
        }

        if (MyDebug.LOG) Log.d(TAG, "initiate() took: ${System.currentTimeMillis() - debugTime}ms")
    }
}
