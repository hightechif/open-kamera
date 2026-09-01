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
 * Encapsulates device- and manufacturer-specific quirks, workarounds, and vendor characteristics.
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
     * Samsung devices (e.g. S7 and S10e) glitch if more than 32 control points are used.
     */
    fun getMinTonemapPoints(): Int {
        return if (isSamsung) 32 else 64
    }

    /**
     * Checks if post-capture trigger is required for auto-exposure convergence.
     */
    fun requiresPostCaptureTrigger(previewIsVideoMode: Boolean, testForceRunPostCapture: Boolean = false): Boolean {
        return (isSamsung || testForceRunPostCapture) && !previewIsVideoMode
    }

    /**
     * Checks if burst noise reduction is supported on this hardware configuration.
     */
    fun allowsBurstNoiseReduction(): Boolean {
        return !isSamsung
    }

    /**
     * Returns true if shutter sound should use video recording sound on Samsung devices.
     */
    fun usesAlternativeShutterSound(): Boolean {
        return isSamsung
    }
}
