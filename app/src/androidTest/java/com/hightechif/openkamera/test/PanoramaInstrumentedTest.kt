package com.hightechif.openkamera.test

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PanoramaInstrumentedTest : BaseInstrumentedTest() {

    private fun createTestBitmap(color: Int, width: Int = 300, height: Int = 200): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    @Test
    fun testPanorama() {
        Log.d(TAG, "testPanorama")
        setToDefault()

        onActivity { activity ->
            val panoramaProcessor = activity.applicationInterface.panoramaProcessor
            assertNotNull(panoramaProcessor)

            val bitmaps = mutableListOf(
                createTestBitmap(Color.BLUE),
                createTestBitmap(Color.GREEN)
            )
            assertEquals(2, bitmaps.size)
        }
    }

    @Test
    fun testPanoramaGyro() {
        Log.d(TAG, "testPanoramaGyro")
        setToDefault()

        onActivity { activity ->
            val gyroSensor = activity.applicationInterface.gyroSensor
            assertNotNull(gyroSensor)
        }
    }

    @Test
    fun testPanoramaCrop() {
        Log.d(TAG, "testPanoramaCrop")
        setToDefault()

        onActivity { _ ->
            val sample = createTestBitmap(Color.RED, 400, 300)
            assertEquals(400, sample.width)
            assertEquals(300, sample.height)
        }
    }

    @Test
    fun testPanoramaMultiPic() {
        Log.d(TAG, "testPanoramaMultiPic")
        setToDefault()

        onActivity { _ ->
            val bitmaps = mutableListOf(
                createTestBitmap(Color.RED),
                createTestBitmap(Color.GREEN),
                createTestBitmap(Color.BLUE)
            )
            assertEquals(3, bitmaps.size)
        }
    }
}
