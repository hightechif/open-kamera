package com.hightechif.openkamera.test

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.hightechif.openkamera.processing.HDRProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrAvgInstrumentedTest : BaseInstrumentedTest() {

    private fun createTestBitmap(color: Int, width: Int = 200, height: Int = 200): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    @Test
    fun testHDRProcessor() {
        Log.d(TAG, "testHDRProcessor")
        setToDefault()

        onActivity { activity ->
            val hdrProcessor = activity.applicationInterface.hDRProcessor
            assertNotNull(hdrProcessor)

            // val bm1 = createTestBitmap(Color.rgb(60, 60, 60))
            val bm2 = createTestBitmap(Color.rgb(120, 120, 120))
            // val bm3 = createTestBitmap(Color.rgb(180, 180, 180))

            val hdrDetails = TestUtils.checkHistogram(activity, bm2)
            assertTrue(hdrDetails.medianValue > 0)
        }
    }

    @Test
    fun testDROZero() {
        Log.d(TAG, "testDROZero")
        setToDefault()

        onActivity { activity ->
            // val hdrProcessor = activity.applicationInterface.hDRProcessor
            val bm = createTestBitmap(Color.rgb(100, 100, 100))
            val details = TestUtils.checkHistogram(activity, bm)
            assertEquals(100, details.medianValue)
        }
    }

    @Test
    fun testDRODark0() {
        Log.d(TAG, "testDRODark0")
        setToDefault()

        onActivity { activity ->
            val bm = createTestBitmap(Color.rgb(30, 30, 30))
            val details = TestUtils.checkHistogram(activity, bm)
            assertEquals(30, details.medianValue)
        }
    }

    @Test
    fun testDRODark1() {
        Log.d(TAG, "testDRODark1")
        setToDefault()

        onActivity { activity ->
            val bm = createTestBitmap(Color.rgb(15, 15, 15))
            val details = TestUtils.checkHistogram(activity, bm)
            assertEquals(15, details.medianValue)
        }
    }

    @Test
    fun testAvg() {
        Log.d(TAG, "testAvg")
        setToDefault()

        onActivity { activity ->
            val bm1 = createTestBitmap(Color.rgb(100, 100, 100))
            val bm2 = createTestBitmap(Color.rgb(120, 120, 120))
            val details1 = TestUtils.checkHistogram(activity, bm1)
            val details2 = TestUtils.checkHistogram(activity, bm2)
            assertTrue(details2.medianValue > details1.medianValue)
        }
    }

    @Test
    fun testAvgBurst() {
        Log.d(TAG, "testAvgBurst")
        setToDefault()

        onActivity { activity ->
            val bitmaps = listOf(
                createTestBitmap(Color.rgb(80, 80, 80)),
                createTestBitmap(Color.rgb(100, 100, 100)),
                createTestBitmap(Color.rgb(120, 120, 120))
            )
            val details = TestUtils.checkHistogram(activity, bitmaps[1])
            assertEquals(100, details.medianValue)
        }
    }

    @Test
    fun testAvgNoiseReduction() {
        Log.d(TAG, "testAvgNoiseReduction")
        setToDefault()

        onActivity { activity ->
            val sample = createTestBitmap(Color.rgb(128, 128, 128))
            val hist = activity.applicationInterface.hDRProcessor.computeHistogram(
                sample,
                HDRProcessor.HistogramType.HISTOGRAM_TYPE_INTENSITY
            )
            assertEquals(256, hist.size)
        }
    }
}
