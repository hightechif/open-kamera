/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

import android.graphics.Bitmap
import com.hightechif.openkamera.processing.JavaImageFunctionsHDR
import com.hightechif.openkamera.processing.JavaImageProcessing
import com.hightechif.openkamera.processing.NativeImageProcessorBridge

/**
 * Encapsulates histogram computation across RGB, Luminance, Value, Intensity, and Lightness modes.
 */
object HistogramProcessor {

    fun computeHistogram(
        bitmap: Bitmap,
        type: HistogramType
    ): IntArray {
        // 1. Try Native C++17 NEON Engine
        if (NativeImageProcessorBridge.isAvailable()) {
            val nativeResult = if (type == HistogramType.HISTOGRAM_TYPE_RGB) {
                NativeImageProcessorBridge.computeHistogramRgb(bitmap)
            } else {
                val mode = when (type) {
                    HistogramType.HISTOGRAM_TYPE_LUMINANCE -> 0
                    HistogramType.HISTOGRAM_TYPE_VALUE -> 1
                    HistogramType.HISTOGRAM_TYPE_INTENSITY -> 2
                    HistogramType.HISTOGRAM_TYPE_LIGHTNESS -> 3
                    else -> 0
                }
                NativeImageProcessorBridge.computeHistogram(bitmap, mode)
            }
            if (nativeResult != null) {
                return nativeResult
            }
        }

        // 2. Fallback to CPU Kotlin/Java
        return computeHistogramJava(bitmap, type)
    }

    private fun computeHistogramJava(bitmap: Bitmap, type: HistogramType): IntArray {
        val javaType = when (type) {
            HistogramType.HISTOGRAM_TYPE_RGB -> JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_RGB
            HistogramType.HISTOGRAM_TYPE_LUMINANCE -> JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_LUMINANCE
            HistogramType.HISTOGRAM_TYPE_VALUE -> JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_VALUE
            HistogramType.HISTOGRAM_TYPE_INTENSITY -> JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_INTENSITY
            HistogramType.HISTOGRAM_TYPE_LIGHTNESS -> JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_LIGHTNESS
        }
        val function = JavaImageFunctionsHDR.ComputeHistogramApplyFunction(javaType)
        JavaImageProcessing.applyFunction(
            function,
            bitmap,
            null,
            0,
            0,
            bitmap.width,
            bitmap.height
        )
        return function.histogram
    }
}
