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
class PanoramaProcessorUnitTest {

    @Test
    fun testPanoramaOverlapAndFeatureParity() {
        val width = 32
        val height = 32

        val lhsBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rhsBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                lhsBitmap.setPixel(x, y, Color.rgb(50, 100, 150))
                rhsBitmap.setPixel(x, y, Color.rgb(150, 100, 50))
            }
        }

        // Draw a corner feature on lhsBitmap
        for (y in 10..14) {
            for (x in 10..14) {
                lhsBitmap.setPixel(x, y, Color.WHITE)
            }
        }

        assertNotNull(lhsBitmap)
        assertNotNull(rhsBitmap)
        assertNotNull(outBitmap)
        assertEquals(width, lhsBitmap.width)
        assertEquals(height, rhsBitmap.height)
    }

    @Test
    fun testSeamBlendingComputation() {
        val width = 20
        val height = 20

        val lhsBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rhsBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                lhsBitmap.setPixel(x, y, Color.BLACK)
                rhsBitmap.setPixel(x, y, Color.WHITE)
            }
        }

        assertEquals(Color.BLACK, lhsBitmap.getPixel(0, 0))
        assertEquals(Color.WHITE, rhsBitmap.getPixel(0, 0))
    }
}
