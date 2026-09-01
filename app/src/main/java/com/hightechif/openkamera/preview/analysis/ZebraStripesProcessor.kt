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
import com.hightechif.openkamera.processing.JavaImageFunctionsPreview
import com.hightechif.openkamera.processing.JavaImageProcessing
import com.hightechif.openkamera.processing.NativeImageProcessorBridge

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
        rotationDegrees: Int
    ): Bitmap {
        val zebraStripesWidth = outputBuffer.width / 20

        // 1. Try Native C++17 NEON Engine
        var processed = false
        if (NativeImageProcessorBridge.isAvailable()) {
            processed = NativeImageProcessorBridge.computeZebraStripes(
                previewBitmap,
                outputBuffer,
                threshold,
                colorForeground,
                colorBackground,
                zebraStripesWidth
            )
        }

        if (!processed) {
            // 2. Fallback to CPU Kotlin/Java
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
