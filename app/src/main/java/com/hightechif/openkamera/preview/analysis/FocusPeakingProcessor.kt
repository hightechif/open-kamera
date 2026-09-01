/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

import android.graphics.Bitmap
import android.graphics.Matrix
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.Type
import com.hightechif.openkamera.ScriptC_histogram_compute
import com.hightechif.openkamera.processing.HDRProcessor
import com.hightechif.openkamera.processing.JavaImageFunctionsPreview
import com.hightechif.openkamera.processing.JavaImageProcessing

/**
 * Encapsulates focus peaking edge detection and filtering bitmap generation.
 */
object FocusPeakingProcessor {

    fun generateFocusPeaking(
        previewBitmap: Bitmap,
        outputBuffer: Bitmap,
        tempBuffer: Bitmap,
        rotationDegrees: Int,
        rs: RenderScript? = null,
        histogramScript: ScriptC_histogram_compute? = null,
        allocationIn: Allocation? = null
    ): Bitmap {
        if (!HDRProcessor.USE_RENDERSCRIPT || rs == null || histogramScript == null || allocationIn == null) {
            val function = JavaImageFunctionsPreview.FocusPeakingApplyFunction(previewBitmap)
            JavaImageProcessing.applyFunction(
                function,
                previewBitmap,
                tempBuffer,
                0,
                0,
                previewBitmap.width,
                previewBitmap.height
            )

            val functionFiltered = JavaImageFunctionsPreview.FocusPeakingFilteredApplyFunction(tempBuffer)
            JavaImageProcessing.applyFunction(
                functionFiltered,
                tempBuffer,
                outputBuffer,
                0,
                0,
                previewBitmap.width,
                previewBitmap.height
            )
        } else {
            var outputAllocation = Allocation.createFromBitmap(rs, outputBuffer)
            histogramScript.set_bitmap(allocationIn)
            histogramScript.forEach_generate_focus_peaking(allocationIn, outputAllocation)

            // Median filter
            val filteredAllocation = Allocation.createTyped(
                rs,
                Type.createXY(
                    rs,
                    Element.RGBA_8888(rs),
                    outputBuffer.width,
                    outputBuffer.height
                )
            )
            histogramScript.set_bitmap(outputAllocation)
            histogramScript.forEach_generate_focus_peaking_filtered(outputAllocation, filteredAllocation)
            outputAllocation.destroy()
            outputAllocation = filteredAllocation

            outputAllocation.copyTo(outputBuffer)
            outputAllocation.destroy()
        }

        val matrix = Matrix()
        matrix.postRotate(-rotationDegrees.toFloat())
        return Bitmap.createBitmap(
            outputBuffer,
            0,
            0,
            outputBuffer.width,
            outputBuffer.height,
            matrix,
            false
        )
    }
}
