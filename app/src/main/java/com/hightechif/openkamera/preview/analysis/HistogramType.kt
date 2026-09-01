/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

/**
 * Types of histograms that can be computed from live preview frames.
 */
enum class HistogramType {
    HISTOGRAM_TYPE_RGB,
    HISTOGRAM_TYPE_LUMINANCE,
    HISTOGRAM_TYPE_VALUE,
    HISTOGRAM_TYPE_INTENSITY,
    HISTOGRAM_TYPE_LIGHTNESS;

    companion object {
        val RGB = HISTOGRAM_TYPE_RGB
        val LUMINANCE = HISTOGRAM_TYPE_LUMINANCE
        val VALUE = HISTOGRAM_TYPE_VALUE
        val INTENSITY = HISTOGRAM_TYPE_INTENSITY
        val LIGHTNESS = HISTOGRAM_TYPE_LIGHTNESS
    }
}
