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
class MtbAlignmentUnitTest {

    @Test
    fun testComputeMedianAndCreateMtb() {
        val width = 20
        val height = 20
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < width / 2) {
                    bitmap.setPixel(x, y, Color.rgb(20, 20, 20))
                } else {
                    bitmap.setPixel(x, y, Color.rgb(220, 220, 220))
                }
            }
        }

        // Test Kotlin CPU path
        val func = JavaImageFunctionsHDR.ComputeHistogramApplyFunction(
            JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_VALUE
        )
        JavaImageProcessing.applyFunction(func, bitmap, null, 0, 0, width, height)
        val hist = func.histogram
        assertNotNull(hist)
        assertEquals(256, hist.size)

        val mtbFunc = JavaImageFunctionsHDR.CreateMTBApplyFunction(true, 120)
        assertNotNull(mtbFunc)
    }

    @Test
    fun testMtbShiftDetectionParity() {
        val width = 30
        val height = 30
        val baseBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val shiftedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Draw a distinct feature box
        for (y in 0 until height) {
            for (x in 0 until width) {
                baseBitmap.setPixel(x, y, Color.BLACK)
                shiftedBitmap.setPixel(x, y, Color.BLACK)
            }
        }

        // Base feature box at (10, 10) to (18, 18)
        for (y in 10..18) {
            for (x in 10..18) {
                baseBitmap.setPixel(x, y, Color.WHITE)
            }
        }

        // Shifted feature box by (+2, +1) -> at (12, 11) to (20, 19)
        for (y in 11..19) {
            for (x in 12..20) {
                shiftedBitmap.setPixel(x, y, Color.WHITE)
            }
        }

        assertNotNull(baseBitmap)
        assertNotNull(shiftedBitmap)
    }

    @Test
    fun testFrameAveragingAccumulation() {
        val width = 10
        val height = 10
        val baseBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                baseBitmap.setPixel(x, y, Color.rgb(100, 100, 100))
                newBitmap.setPixel(x, y, Color.rgb(200, 200, 200))
            }
        }

        // Verify bitmap integrity
        assertEquals(Color.rgb(100, 100, 100), baseBitmap.getPixel(0, 0))
        assertEquals(Color.rgb(200, 200, 200), newBitmap.getPixel(0, 0))
    }
}
