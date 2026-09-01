/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusPeakingProcessorUnitTest {

    @Test
    fun testFocusPeakingExecution() {
        val width = 20
        val height = 20
        val srcBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val tempBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Create high-contrast edge in middle
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < width / 2) {
                    srcBitmap.setPixel(x, y, Color.WHITE)
                } else {
                    srcBitmap.setPixel(x, y, Color.BLACK)
                }
            }
        }

        val result = FocusPeakingProcessor.generateFocusPeaking(
            previewBitmap = srcBitmap,
            outputBuffer = outBitmap,
            tempBuffer = tempBitmap,
            rotationDegrees = 0
        )

        assertNotNull(result)
        assertEquals(width, result.width)
        assertEquals(height, result.height)
    }

    @Test
    fun testZebraStripesExecution() {
        val width = 40
        val height = 40
        val srcBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Set overexposed top-left quadrant
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < 20 && y < 20) {
                    srcBitmap.setPixel(x, y, Color.WHITE) // 255 >= threshold
                } else {
                    srcBitmap.setPixel(x, y, Color.rgb(100, 100, 100))
                }
            }
        }

        val result = ZebraStripesProcessor.generateZebraStripes(
            previewBitmap = srcBitmap,
            outputBuffer = outBitmap,
            threshold = 200,
            colorForeground = Color.RED,
            colorBackground = Color.BLUE,
            rotationDegrees = 0
        )

        assertNotNull(result)
        assertEquals(width, result.width)
        assertEquals(height, result.height)
    }
}
