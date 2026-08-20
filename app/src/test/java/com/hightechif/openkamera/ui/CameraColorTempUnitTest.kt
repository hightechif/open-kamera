package com.hightechif.openkamera.ui

import com.hightechif.openkamera.cameracontroller.CameraController2
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraColorTempUnitTest {

    private fun roundTripWhiteBalanceTemperature(temperature: Int) {
        val rggb: FloatArray = CameraController2.convertTemperatureToRggb(temperature)
        val newTemperature: Int = CameraController2.convertRggbToTemperature(rggb)
        assertEquals(temperature.toLong(), newTemperature.toLong())
    }

    @Test
    fun whiteBalanceTemperature() {
        // round trip won't work for very low temperatures due to hitting max gain
        roundTripWhiteBalanceTemperature(3000)
        roundTripWhiteBalanceTemperature(4000)
        roundTripWhiteBalanceTemperature(5000)
        roundTripWhiteBalanceTemperature(6000)
        roundTripWhiteBalanceTemperature(6600)
        roundTripWhiteBalanceTemperature(7000)
        roundTripWhiteBalanceTemperature(8000)
        roundTripWhiteBalanceTemperature(9000)
        roundTripWhiteBalanceTemperature(10000)
        roundTripWhiteBalanceTemperature(12000)
        roundTripWhiteBalanceTemperature(15000)
    }
}
