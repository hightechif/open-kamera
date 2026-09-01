/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

import android.graphics.Bitmap

/**
 * Output model containing processed histogram data and visual overlay bitmaps from live frame analysis.
 */
data class FrameAnalysisResult(
    val histogram: IntArray? = null,
    val zebraStripesBitmap: Bitmap? = null,
    val focusPeakingBitmap: Bitmap? = null,
    val previewBitmapFullCopy: Bitmap? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FrameAnalysisResult

        if (timestampMs != other.timestampMs) return false
        if (!histogram.contentEquals(other.histogram)) return false
        if (zebraStripesBitmap != other.zebraStripesBitmap) return false
        if (focusPeakingBitmap != other.focusPeakingBitmap) return false
        if (previewBitmapFullCopy != other.previewBitmapFullCopy) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + (histogram?.contentHashCode() ?: 0)
        result = 31 * result + (zebraStripesBitmap?.hashCode() ?: 0)
        result = 31 * result + (focusPeakingBitmap?.hashCode() ?: 0)
        result = 31 * result + (previewBitmapFullCopy?.hashCode() ?: 0)
        return result
    }
}
