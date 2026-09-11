/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import com.hightechif.openkamera.utils.MyDebug
import android.util.Log

/**
 * Owns the mutable state for an in-progress burst-capture sequence.
 *
 * This class is the single source of truth for burst counters and the single-request flag.
 * All mutations MUST be performed while holding [CameraController2.backgroundCameraLock].
 *
 * ### State lifecycle
 * 1. Call [initSingleShot] before issuing a single still-capture request.
 * 2. Call [initBurst] before issuing a burst-capture sequence.
 * 3. Call [onJpegReceived] / [onRawReceived] each time an image arrives.
 * 4. Query [isBurstJpegComplete] / [isBurstRawComplete] to determine when the sequence is done.
 * 5. Call [reset] to clear state after a completed or canceled capture.
 */
class Camera2BurstCoordinator {

    companion object {
        private const val TAG = "Camera2BurstCoordinator"
    }

    /** Number of expected (remaining) JPEG images in the current burst. Counts down if [burstSingleRequest] is false. */
    var nBurst: Int = 0
        private set

    /** Allows [CameraController2] delegated-property setters to write [nBurst]. */
    internal fun setNBurst(value: Int) { nBurst = value }

    /** Number of JPEG images taken so far in the current burst. */
    var nBurstTaken: Int = 0
        private set

    /** Allows [CameraController2] delegated-property setters to write [nBurstTaken]. */
    internal fun setNBurstTaken(value: Int) { nBurstTaken = value }

    /** Total number of expected burst images (JPEG and RAW, same count). */
    var nBurstTotal: Int = 0
        private set

    /** Allows [CameraController2] delegated-property setters to write [nBurstTotal]. */
    internal fun setNBurstTotal(value: Int) { nBurstTotal = value }

    /** Number of expected (remaining) RAW images in the current burst. Counts down if [burstSingleRequest] is false. */
    var nBurstRaw: Int = 0
        private set

    /** Allows [CameraController2] delegated-property setters to write [nBurstRaw]. */
    internal fun setNBurstRaw(value: Int) { nBurstRaw = value }

    /**
     * When true, all burst images are delivered together in a single [CameraController.PictureCallback.onBurstPictureTaken]
     * callback once all images arrive. When false, images are delivered via [CameraController.PictureCallback.onPictureTaken]
     * individually as each image becomes available.
     */
    var burstSingleRequest: Boolean = false
        private set

    /** Allows [CameraController2] delegated-property setters to write [burstSingleRequest]. */
    internal fun setBurstSingleRequest(value: Boolean) { burstSingleRequest = value }

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Initializes state for a standard single still-capture (not a burst).
     *
     * @param rawEnabled true when a RAW image reader is also configured.
     */
    fun initSingleShot(rawEnabled: Boolean) {
        nBurst = 1
        nBurstTaken = 0
        nBurstTotal = 1
        nBurstRaw = if (rawEnabled) 1 else 0
        burstSingleRequest = false
        if (MyDebug.LOG) Log.d(TAG, "initSingleShot: nBurst=$nBurst nBurstRaw=$nBurstRaw")
    }

    /**
     * Initializes state for a burst-capture sequence.
     *
     * @param burstCount  the number of JPEG images expected.
     * @param rawEnabled  true when a RAW image reader is also configured.
     * @param singleRequest true when all images are submitted in one [android.hardware.camera2.CameraCaptureSession.captureBurst] call.
     */
    fun initBurst(burstCount: Int, rawEnabled: Boolean, singleRequest: Boolean) {
        nBurst = burstCount
        nBurstTaken = 0
        nBurstTotal = burstCount
        nBurstRaw = if (rawEnabled) burstCount else 0
        burstSingleRequest = singleRequest
        if (MyDebug.LOG) {
            Log.d(TAG, "initBurst: nBurst=$nBurst nBurstRaw=$nBurstRaw singleRequest=$singleRequest")
        }
    }

    // ── Progress tracking ─────────────────────────────────────────────────────

    /**
     * Called when a JPEG image has been received from the image reader.
     * Increments [nBurstTaken] and, when [burstSingleRequest] is false, decrements [nBurst].
     */
    fun onJpegReceived() {
        nBurstTaken++
        if (!burstSingleRequest) {
            nBurst--
        }
        if (MyDebug.LOG) Log.d(TAG, "onJpegReceived: nBurstTaken=$nBurstTaken nBurst=$nBurst")
    }

    /**
     * Called when a RAW image has been received from the RAW image reader.
     * When [burstSingleRequest] is false, decrements [nBurstRaw].
     */
    fun onRawReceived() {
        if (!burstSingleRequest) {
            nBurstRaw--
        }
        if (MyDebug.LOG) Log.d(TAG, "onRawReceived: nBurstRaw=$nBurstRaw")
    }

    /**
     * Returns true when all expected JPEG images have been received in single-request burst mode.
     * Use [nBurst] == 0 as the completion check in countdown (non-single-request) mode.
     */
    fun isBurstJpegComplete(pendingCount: Int): Boolean {
        return burstSingleRequest && pendingCount >= nBurst
    }

    /**
     * Returns true when all expected RAW images have been received in single-request burst mode.
     */
    fun isBurstRawComplete(pendingCount: Int): Boolean {
        return burstSingleRequest && pendingCount >= nBurstRaw
    }

    // ── Focus-bracketing helpers ───────────────────────────────────────────────

    /**
     * Truncates the burst sequence to [newCount] images by adjusting [nBurst] / [nBurstRaw].
     * Called when focus-bracketing is canceled mid-sequence.
     *
     * @param newCount the revised number of captures.
     */
    fun truncateBurst(newCount: Int) {
        if (burstSingleRequest) {
            nBurst = newCount
            if (nBurstRaw > 0) nBurstRaw = newCount
        } else {
            nBurst = 1
            if (nBurstRaw > 0) nBurstRaw = 1
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "truncateBurst: nBurst=$nBurst nBurstRaw=$nBurstRaw")
        }
    }

    /**
     * Increments the expected burst count by one. Used in continuous-burst mode when another
     * frame is enqueued before the previous one has been delivered.
     */
    fun incrementForContinuousBurst() {
        nBurst++
        if (MyDebug.LOG) Log.d(TAG, "incrementForContinuousBurst: nBurst=$nBurst")
    }

    /**
     * Resets all state to defaults. Call after a completed or aborted capture sequence.
     */
    fun reset() {
        nBurst = 0
        nBurstTaken = 0
        nBurstTotal = 0
        nBurstRaw = 0
        burstSingleRequest = false
        if (MyDebug.LOG) Log.d(TAG, "reset")
    }
}
