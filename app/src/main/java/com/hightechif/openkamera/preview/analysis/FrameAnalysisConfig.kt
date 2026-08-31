/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

/**
 * Immutable configuration for the live preview frame analysis pipeline.
 */
data class FrameAnalysisConfig(
    val wantHistogram: Boolean = false,
    val histogramType: HistogramType = HistogramType.VALUE,
    val wantZebraStripes: Boolean = false,
    val zebraStripesThreshold: Int = 0,
    val zebraStripesColorForeground: Int = 0,
    val zebraStripesColorBackground: Int = 0,
    val wantFocusPeaking: Boolean = false,
    val wantPreShots: Boolean = false,
    val previewBitmapFullW: Int = -1,
    val previewBitmapFullH: Int = -1,
    val rotationDegrees: Int = 0
)
