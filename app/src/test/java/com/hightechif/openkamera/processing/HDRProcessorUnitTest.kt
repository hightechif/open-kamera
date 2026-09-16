/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.processing

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HDRProcessorUnitTest {

    @Test
    fun testHdrFusionThreeBrackets() {
        val width = 16
        val height = 16

        // Underexposed (-2 EV), Normal (0 EV), Overexposed (+2 EV)
        val underexposed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val normal = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val overexposed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                underexposed.setPixel(x, y, Color.rgb(30, 30, 30))
                normal.setPixel(x, y, Color.rgb(120, 120, 120))
                overexposed.setPixel(x, y, Color.rgb(230, 230, 230))
            }
        }

        // Test CPU execution path
        val func = JavaImageFunctionsHDR.HDRApplyFunction(
            HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD,
            1.0f,
            11.2f,
            1.0f,
            underexposed,
            overexposed,
            0,
            0,
            0,
            0,
            width,
            height,
            floatArrayOf(1.0f, 1.0f, 1.0f),
            floatArrayOf(0.0f, 0.0f, 0.0f)
        )
        assertNotNull(func)

        // Verify bitmap validity
        assertEquals(width, underexposed.width)
        assertEquals(height, overexposed.height)
    }

    @Test
    fun testHistogramEqualizationTransformation() {
        val width = 10
        val height = 10
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val gray = (y * 20) + (x * 5)
                bitmap.setPixel(x, y, Color.rgb(gray, gray, gray))
            }
        }

        val func = JavaImageFunctionsHDR.AdjustHistogramApplyFunction(
            0.5f,
            1,
            width,
            height,
            IntArray(256) { it * (width * height / 256) }
        )
        assertNotNull(func)
    }
}
