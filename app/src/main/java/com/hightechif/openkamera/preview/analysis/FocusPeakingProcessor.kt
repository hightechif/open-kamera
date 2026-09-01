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
 * Encapsulates focus peaking edge detection and filtering bitmap generation.
 */
object FocusPeakingProcessor {

    fun generateFocusPeaking(
        previewBitmap: Bitmap,
        outputBuffer: Bitmap,
        tempBuffer: Bitmap,
        rotationDegrees: Int
    ): Bitmap {
        // 1. Try Native C++17 NEON Engine
        var processed = false
        if (NativeImageProcessorBridge.isAvailable()) {
            processed = NativeImageProcessorBridge.computeFocusPeaking(previewBitmap, tempBuffer, outputBuffer)
        }

        if (!processed) {
            // 2. Fallback to CPU Kotlin/Java
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
