/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import com.hightechif.openkamera.ScriptC_histogram_compute
import com.hightechif.openkamera.processing.HDRProcessor
import com.hightechif.openkamera.processing.JavaImageFunctionsHDR
import com.hightechif.openkamera.processing.JavaImageProcessing

/**
 * Encapsulates histogram computation across RGB, Luminance, Value, Intensity, and Lightness modes.
 */
object HistogramProcessor {

    fun computeHistogram(
        bitmap: Bitmap,
        type: HistogramType,
        rs: RenderScript? = null,
        histogramScript: ScriptC_histogram_compute? = null,
        allocationIn: Allocation? = null
    ): IntArray {
        return if (!HDRProcessor.USE_RENDERSCRIPT || rs == null || histogramScript == null || allocationIn == null) {
            computeHistogramJava(bitmap, type)
        } else {
            computeHistogramRS(allocationIn, rs, histogramScript, type)
        }
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

    private fun computeHistogramRS(
        allocationIn: Allocation,
        rs: RenderScript,
        histogramScript: ScriptC_histogram_compute,
        type: HistogramType
    ): IntArray {
        val newHistogram: IntArray
        if (type == HistogramType.HISTOGRAM_TYPE_RGB) {
            val histogramAllocationR = Allocation.createSized(rs, Element.I32(rs), 256)
            val histogramAllocationG = Allocation.createSized(rs, Element.I32(rs), 256)
            val histogramAllocationB = Allocation.createSized(rs, Element.I32(rs), 256)

            histogramScript.bind_histogram_r(histogramAllocationR)
            histogramScript.bind_histogram_g(histogramAllocationG)
            histogramScript.bind_histogram_b(histogramAllocationB)
            histogramScript.invoke_init_histogram_rgb()
            histogramScript.forEach_histogram_compute_rgb(allocationIn)

            newHistogram = IntArray(256 * 3)
            var c = 0
            val temp = IntArray(256)
            histogramAllocationR.copyTo(temp)
            for (i in 0 until 256) newHistogram[c++] = temp[i]
            histogramAllocationG.copyTo(temp)
            for (i in 0 until 256) newHistogram[c++] = temp[i]
            histogramAllocationB.copyTo(temp)
            for (i in 0 until 256) newHistogram[c++] = temp[i]

            histogramAllocationR.destroy()
            histogramAllocationG.destroy()
            histogramAllocationB.destroy()
        } else {
            val histogramAllocation = Allocation.createSized(rs, Element.I32(rs), 256)
            histogramScript.bind_histogram(histogramAllocation)
            histogramScript.invoke_init_histogram()
            when (type) {
                HistogramType.HISTOGRAM_TYPE_LUMINANCE -> histogramScript.forEach_histogram_compute_by_luminance(allocationIn)
                HistogramType.HISTOGRAM_TYPE_VALUE -> histogramScript.forEach_histogram_compute_by_value(allocationIn)
                HistogramType.HISTOGRAM_TYPE_INTENSITY -> histogramScript.forEach_histogram_compute_by_intensity(allocationIn)
                HistogramType.HISTOGRAM_TYPE_LIGHTNESS -> histogramScript.forEach_histogram_compute_by_lightness(allocationIn)
                else -> throw RuntimeException("unknown histogram type: $type")
            }
            newHistogram = IntArray(256)
            histogramAllocation.copyTo(newHistogram)
            histogramAllocation.destroy()
        }
        return newHistogram
    }
}
