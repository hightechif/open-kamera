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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistogramProcessorUnitTest {

    @Test
    fun testHistogramComputationLuminance() {
        val width = 10
        val height = 10
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // Fill half with white and half with black
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (y < height / 2) {
                    bitmap.setPixel(x, y, Color.WHITE)
                } else {
                    bitmap.setPixel(x, y, Color.BLACK)
                }
            }
        }

        val hist = HistogramProcessor.computeHistogram(bitmap, HistogramType.HISTOGRAM_TYPE_LUMINANCE)
        assertNotNull(hist)
        assertEquals(256, hist.size)

        // 50 black pixels at bin 0, 50 white pixels at bin 255
        assertEquals(50, hist[0])
        assertEquals(50, hist[255])
    }

    @Test
    fun testHistogramComputationRgb() {
        val width = 4
        val height = 4
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, Color.RED)
            }
        }

        val hist = HistogramProcessor.computeHistogram(bitmap, HistogramType.HISTOGRAM_TYPE_RGB)
        assertNotNull(hist)
        assertEquals(256 * 3, hist.size)

        // R bin 255 should be 16, G bin 0 should be 16, B bin 0 should be 16
        assertEquals(16, hist[255]) // R
        assertEquals(16, hist[256 + 0]) // G
        assertEquals(16, hist[512 + 0]) // B
    }

    @Test
    fun testHistogramComputationModes() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.rgb(100, 150, 200))
        bitmap.setPixel(0, 1, Color.rgb(100, 150, 200))
        bitmap.setPixel(1, 0, Color.rgb(100, 150, 200))
        bitmap.setPixel(1, 1, Color.rgb(100, 150, 200))

        val valueHist = HistogramProcessor.computeHistogram(bitmap, HistogramType.HISTOGRAM_TYPE_VALUE)
        assertEquals(256, valueHist.size)
        assertEquals(4, valueHist[200]) // max(100, 150, 200) = 200

        val intensityHist = HistogramProcessor.computeHistogram(bitmap, HistogramType.HISTOGRAM_TYPE_INTENSITY)
        assertEquals(256, intensityHist.size)
        assertEquals(4, intensityHist[(100 + 150 + 200) / 3]) // 150

        val lightnessHist = HistogramProcessor.computeHistogram(bitmap, HistogramType.HISTOGRAM_TYPE_LIGHTNESS)
        assertEquals(256, lightnessHist.size)
        assertEquals(4, lightnessHist[(200 + 100) / 2]) // 150
    }
}
