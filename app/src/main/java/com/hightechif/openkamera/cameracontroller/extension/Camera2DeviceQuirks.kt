/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.extension

import android.os.Build

/**
 * Encapsulates device- and manufacturer-specific quirks, workarounds, and vendor characteristics
 * for the Camera2 capture pipeline.
 *
 * All per-device decisions made in the capture path MUST be routed through this class.
 * Call sites should never inspect [Build.MANUFACTURER] or [Build.MODEL] directly.
 *
 * @param manufacturer The device manufacturer string, defaults to [Build.MANUFACTURER].
 * @param model The device model string, defaults to [Build.MODEL].
 */
data class Camera2DeviceQuirks(
    val manufacturer: String = Build.MANUFACTURER,
    val model: String = Build.MODEL
) {
    val isSamsung: Boolean = manufacturer.lowercase().contains("samsung")
    val isSamsungS7: Boolean = model.lowercase().contains("sm-g93")
    val isSamsungGalaxyS: Boolean =
        isSamsung && (model.lowercase().contains("sm-g") || model.lowercase().contains("sm-s"))
    val isSamsungGalaxyF: Boolean =
        isSamsung && model.lowercase().contains("sm-f")
    val isNexus6: Boolean =
        model.lowercase().contains("nexus 6")

    /**
     * Determines minimum tonemap curve points required for this hardware.
     *
     * Samsung devices (e.g. S7 and S10e) glitch if more than 32 control points are used.
     *
     * @return 32 for Samsung devices, 64 for all others.
     */
    fun getMinTonemapPoints(): Int {
        return if (isSamsung) 32 else 64
    }

    /**
     * Returns true if the device requires fake-precapture (torch-based AE convergence) instead
     * of the standard Camera2 AE precapture trigger.
     *
     * Samsung devices require fake precapture for any burst mode (expo/focus/normal/continuous)
     * because the standard precapture path produces incorrect exposures on their hardware.
     *
     * @param isBurstMode true when any burst type other than BURSTTYPE_NONE is active.
     * @return true if fake precapture should be used for this device and mode.
     */
    fun requiresFakePrecapture(isBurstMode: Boolean): Boolean {
        return isSamsung && isBurstMode
    }

    /**
     * Returns true if the device benefits from setting [android.hardware.camera2.CaptureRequest.CONTROL_ENABLE_ZSL]
     * to true on still-capture requests (enables HDR+ on Pixel devices, etc.).
     *
     * Callers must also check that API level ≥ O and the session is not an extension session,
     * as those constraints are orthogonal to the device quirk.
     *
     * @return true for all currently known devices (placeholder for future device-specific exclusions).
     */
    fun supportsZslHint(): Boolean {
        // Currently no known devices where ZSL causes problems — always return true.
        // Add device-specific exclusions here if needed in the future.
        return true
    }

    /**
     * Checks if post-capture trigger is required for auto-exposure convergence after a still shot.
     *
     * Samsung devices in non-video mode require an explicit AE precapture trigger after capture
     * to restore the preview exposure correctly.
     *
     * @param previewIsVideoMode true when the preview is in video-recording mode.
     * @param testForceRunPostCapture when true, forces the trigger regardless of device (for testing).
     * @return true if the post-capture AE trigger should be issued.
     */
    fun requiresPostCaptureTrigger(previewIsVideoMode: Boolean, testForceRunPostCapture: Boolean = false): Boolean {
        return (isSamsung || testForceRunPostCapture) && !previewIsVideoMode
    }

    /**
     * Checks if burst noise reduction is supported on this hardware configuration.
     *
     * Samsung devices do not support burst noise reduction; enabling it causes capture failures.
     *
     * @return false for Samsung devices, true for all others.
     */
    fun allowsBurstNoiseReduction(): Boolean {
        return !isSamsung
    }

    /**
     * Returns true if the shutter sound should use the video-recording sound on this device.
     *
     * Samsung devices produce better user feedback with the video-recording sound for shutter clicks.
     *
     * @return true for Samsung devices, false for all others.
     */
    fun usesAlternativeShutterSound(): Boolean {
        return isSamsung
    }
}
