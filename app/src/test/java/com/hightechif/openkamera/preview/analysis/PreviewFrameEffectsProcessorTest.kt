/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewFrameEffectsProcessorTest {

    @Test
    fun testInitialState() {
        val context = RuntimeEnvironment.getApplication()
        val processor = PreviewFrameEffectsProcessor(context)

        assertNull(processor.previewBitmap)
        assertNull(processor.histogram)
        assertNull(processor.zebraStripesBitmap)
        assertNull(processor.focusPeakingBitmap)
    }

    @Test
    fun testSetupBuffers() {
        val context = RuntimeEnvironment.getApplication()
        val processor = PreviewFrameEffectsProcessor(context).apply {
            usePreviewBitmapSmall = true
            wantZebraStripes = true
            wantFocusPeaking = true
        }

        processor.setupBuffers(800, 600)
        assertNotNull(processor.previewBitmap)
        assertEquals(200, processor.previewBitmap!!.width)
        assertEquals(150, processor.previewBitmap!!.height)

        processor.releaseBuffers()
        assertNull(processor.previewBitmap)
    }

    @Test
    fun testUpdateResults() {
        val context = RuntimeEnvironment.getApplication()
        val processor = PreviewFrameEffectsProcessor(context)

        val hist = IntArray(256) { it }
        val zebraBm = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val peakingBm = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        val result = FrameAnalysisResult(
            histogram = hist,
            zebraStripesBitmap = zebraBm,
            focusPeakingBitmap = peakingBm,
            timestampMs = 12345L
        )

        processor.updateResults(result)

        assertEquals(hist, processor.histogram)
        assertEquals(12345L, processor.lastHistogramTimeMs)
        assertEquals(zebraBm, processor.zebraStripesBitmap)
        assertEquals(peakingBm, processor.focusPeakingBitmap)
    }
}
