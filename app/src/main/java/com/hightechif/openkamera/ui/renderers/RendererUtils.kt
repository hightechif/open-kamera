/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Shared mathematical, string formatting, and metric conversion utilities for HUD renderers.
 */
object RendererUtils {

    private val decimalFormat = DecimalFormat("#0.0")

    const val CLOSE_LEVEL_ANGLE = 1.0
    const val HISTOGRAM_WIDTH_DP = 100
    const val HISTOGRAM_HEIGHT_DP = 60
    const val CROP_SHADING_ALPHA = 160

    /**
     * Formats the level angle value with one decimal place precision, cleanly handling "-0.0".
     */
    fun formatLevelAngle(levelAngle: Double): String {
        var numberString = decimalFormat.format(levelAngle)
        if (abs(levelAngle) < 0.1) {
            numberString = numberString.replace("^-(?=0(.0*)?$)".toRegex(), "")
        }
        return numberString
    }

    /**
     * Converts a duration in seconds to "MM:SS" or "HH:MM:SS" format.
     */
    fun getTimeStringFromSeconds(time: Long): String {
        val secs = time % 60
        val mins = (time / 60) % 60
        val hours = time / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }
}
