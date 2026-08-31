/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewFrameAnalyzerUnitTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testPreShotsRingBuffer_CapacityAndFifo() {
        val ringBuffer = PreShotsRingBuffer(maxCapacity = 3)
        assertEquals(0, ringBuffer.size)
        assertEquals(false, ringBuffer.hasBitmaps())

        val bm1 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val bm2 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val bm3 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val bm4 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        ringBuffer.add(bm1)
        ringBuffer.add(bm2)
        ringBuffer.add(bm3)
        assertEquals(3, ringBuffer.size)

        // Adding 4th bitmap should evict bm1
        ringBuffer.add(bm4)
        assertEquals(3, ringBuffer.size)
        assertTrue(bm1.isRecycled)

        val polled = ringBuffer.poll()
        assertEquals(bm2, polled)
        assertEquals(2, ringBuffer.size)

        ringBuffer.flush()
        assertEquals(0, ringBuffer.size)
        assertTrue(bm3.isRecycled)
        assertTrue(bm4.isRecycled)
    }

    @Test
    fun testHistogramProcessor_ValueHistogram_SolidWhite() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)

        val histogram = HistogramProcessor.computeHistogram(bitmap, HistogramType.VALUE)
        assertNotNull(histogram)
        assertEquals(256, histogram.size)

        // White has value 255, so bin 255 should have all 100 pixels
        assertEquals(100, histogram[255])
        assertEquals(0, histogram[0])
    }

    @Test
    fun testHistogramProcessor_RgbHistogram_SolidRed() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)

        val histogram = HistogramProcessor.computeHistogram(bitmap, HistogramType.RGB)
        assertNotNull(histogram)
        assertEquals(768, histogram.size) // 256 * 3 channels

        // Red channel max (bin 255) has 100 pixels
        assertEquals(100, histogram[255])
        // Green channel at bin 0 has 100 pixels
        assertEquals(100, histogram[256 + 0])
        // Blue channel at bin 0 has 100 pixels
        assertEquals(100, histogram[512 + 0])
    }

    @Test
    fun testPreviewFrameAnalyzer_AnalyzeFrameDirect() = testScope.runTest {
        val analyzer = PreviewFrameAnalyzer(context, testDispatcher, this)
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)

        val config = FrameAnalysisConfig(
            wantHistogram = true,
            histogramType = HistogramType.LUMINANCE
        )

        val result = analyzer.analyzeFrameDirect(bitmap, config)
        assertNotNull(result)
        assertNotNull(result?.histogram)
        assertEquals(256, result?.histogram?.size)

        analyzer.destroy()
    }
}
