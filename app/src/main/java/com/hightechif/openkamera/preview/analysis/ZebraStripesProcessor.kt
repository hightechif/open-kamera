/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.renderscript.Allocation
import android.renderscript.RenderScript
import com.hightechif.openkamera.ScriptC_histogram_compute
import com.hightechif.openkamera.processing.HDRProcessor
import com.hightechif.openkamera.processing.JavaImageFunctionsPreview
import com.hightechif.openkamera.processing.JavaImageProcessing

/**
 * Encapsulates overexposure zebra stripes highlight overlay generation.
 */
object ZebraStripesProcessor {

    fun generateZebraStripes(
        previewBitmap: Bitmap,
        outputBuffer: Bitmap,
        threshold: Int,
        colorForeground: Int,
        colorBackground: Int,
        rotationDegrees: Int,
        rs: RenderScript? = null,
        histogramScript: ScriptC_histogram_compute? = null,
        allocationIn: Allocation? = null
    ): Bitmap {
        val zebraStripesWidth = outputBuffer.width / 20

        if (!HDRProcessor.USE_RENDERSCRIPT || rs == null || histogramScript == null || allocationIn == null) {
            val function = JavaImageFunctionsPreview.ZebraStripesApplyFunction(
                threshold,
                colorForeground,
                colorBackground,
                zebraStripesWidth
            )
            JavaImageProcessing.applyFunction(
                function,
                previewBitmap,
                outputBuffer,
                0,
                0,
                previewBitmap.width,
                previewBitmap.height
            )
        } else {
            val outputAllocation = Allocation.createFromBitmap(rs, outputBuffer)
            histogramScript.set_zebra_stripes_threshold(threshold)
            histogramScript.set_zebra_stripes_foreground_r(Color.red(colorForeground))
            histogramScript.set_zebra_stripes_foreground_g(Color.green(colorForeground))
            histogramScript.set_zebra_stripes_foreground_b(Color.blue(colorForeground))
            histogramScript.set_zebra_stripes_foreground_a(Color.alpha(colorForeground))
            histogramScript.set_zebra_stripes_background_r(Color.red(colorBackground))
            histogramScript.set_zebra_stripes_background_g(Color.green(colorBackground))
            histogramScript.set_zebra_stripes_background_b(Color.blue(colorBackground))
            histogramScript.set_zebra_stripes_background_a(Color.alpha(colorBackground))
            histogramScript.set_zebra_stripes_width(zebraStripesWidth)

            histogramScript.forEach_generate_zebra_stripes(allocationIn, outputAllocation)
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
