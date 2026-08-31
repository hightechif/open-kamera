package com.hightechif.openkamera.cameracontroller.request

import com.hightechif.openkamera.cameracontroller.CameraController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2RequestBuilderHelperUnitTest {

    @Test
    fun testComputeTonemapCurveValuesOff() {
        val curve = Camera2RequestBuilderHelper.computeTonemapCurveValues(
            CameraController.TonemapProfile.TONEMAPPROFILE_OFF
        )
        assertNull(curve)
    }

    @Test
    fun testComputeTonemapCurveValuesRec709() {
        val curve = Camera2RequestBuilderHelper.computeTonemapCurveValues(
            CameraController.TonemapProfile.TONEMAPPROFILE_REC709
        )
        assertNotNull(curve)
        assertTrue(curve!!.isNotEmpty())
        assertEquals(32, curve.size)
        assertEquals(0.0f, curve[0], 0.0001f)
        assertEquals(1.0f, curve[curve.size - 2], 0.0001f)
    }

    @Test
    fun testComputeTonemapCurveValuesSrgb() {
        val curve = Camera2RequestBuilderHelper.computeTonemapCurveValues(
            CameraController.TonemapProfile.TONEMAPPROFILE_SRGB
        )
        assertNotNull(curve)
        assertEquals(32, curve!!.size)
    }

    @Test
    fun testComputeTonemapCurveValuesLogAndGamma() {
        val logCurve = Camera2RequestBuilderHelper.computeTonemapCurveValues(
            profile = CameraController.TonemapProfile.TONEMAPPROFILE_LOG,
            logStrength = 10f,
            maxPoints = 64
        )
        assertNotNull(logCurve)
        assertEquals(128, logCurve!!.size)

        val gammaCurve = Camera2RequestBuilderHelper.computeTonemapCurveValues(
            profile = CameraController.TonemapProfile.TONEMAPPROFILE_GAMMA,
            gamma = 2.2f,
            maxPoints = 64
        )
        assertNotNull(gammaCurve)
        assertEquals(128, gammaCurve!!.size)
    }

    @Test
    fun testComputeTonemapCurveValuesCustom() {
        val custom = floatArrayOf(0f, 0f, 0.5f, 0.6f, 1f, 1f)
        val curve = Camera2RequestBuilderHelper.computeTonemapCurveValues(
            profile = CameraController.TonemapProfile.TONEMAPPROFILE_JTVIDEO,
            customCurve = custom
        )
        assertNotNull(curve)
        assertEquals(custom, curve)
    }

    @Test
    fun testTemperatureToRggbVector() {
        val warmVector = Camera2RequestBuilderHelper.convertTemperatureToRggbVector(2800)
        assertTrue("Blue gain should be higher to balance warm scene illumination", warmVector.blue > warmVector.red)

        val coolVector = Camera2RequestBuilderHelper.convertTemperatureToRggbVector(8000)
        assertTrue("Red gain should be higher to balance cool scene illumination", coolVector.red > coolVector.blue)
    }

    @Test
    fun testTemperatureConversionRoundtrip() {
        val rggb = Camera2RequestBuilderHelper.convertTemperatureToRggb(5000)
        val recoveredTemp = Camera2RequestBuilderHelper.convertRggbToTemperature(rggb)
        assertTrue(recoveredTemp in 4500..5500)
    }

    @Test
    fun testTemperatureClamping() {
        val minRggb = Camera2RequestBuilderHelper.convertTemperatureToRggb(500)
        val minTemp = Camera2RequestBuilderHelper.convertRggbToTemperature(minRggb)
        assertTrue(minTemp >= Camera2RequestBuilderHelper.MIN_WHITE_BALANCE_TEMPERATURE_C)

        val maxRggb = Camera2RequestBuilderHelper.convertTemperatureToRggb(20000)
        val maxTemp = Camera2RequestBuilderHelper.convertRggbToTemperature(maxRggb)
        assertTrue(maxTemp <= Camera2RequestBuilderHelper.MAX_WHITE_BALANCE_TEMPERATURE_C)
    }
}
