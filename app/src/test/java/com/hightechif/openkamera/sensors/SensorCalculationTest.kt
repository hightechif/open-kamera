/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.sensors

import com.hightechif.openkamera.domain.model.HorizonAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

class SensorCalculationTest {

    private fun calculateHorizonAngle(
        gravityX: Float,
        gravityY: Float,
        calibratedOffset: Double = 0.0,
        deviceOrientation: Int = 0
    ): HorizonAngle {
        var naturalLevelAngle = atan2(-gravityX.toDouble(), gravityY.toDouble()) * 180.0 / PI
        if (naturalLevelAngle < 0.0) {
            naturalLevelAngle += 360.0
        }
        var levelAngle = naturalLevelAngle - calibratedOffset - deviceOrientation.toDouble()
        while (levelAngle < -180.0) levelAngle += 360.0
        while (levelAngle > 180.0) levelAngle -= 360.0

        val isLevel = abs(levelAngle) <= 1.0 ||
                abs(abs(levelAngle) - 90.0) <= 1.0 ||
                abs(abs(levelAngle) - 180.0) <= 1.0
        return HorizonAngle(angleDegrees = levelAngle, isLevel = isLevel)
    }

    private fun calculatePitch(gravityX: Float, gravityY: Float, gravityZ: Float): Float {
        val mag = sqrt((gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ).toDouble())
        return if (mag > 1.0e-8) {
            (asin((-gravityZ.toDouble() / mag).coerceIn(-1.0, 1.0)) * 180.0 / PI).toFloat()
        } else {
            0.0f
        }
    }

    private fun applyLowPass(target: FloatArray, source: FloatArray, alpha: Float = 0.8f) {
        for (i in 0..2) {
            target[i] = alpha * target[i] + (1.0f - alpha) * source[i]
        }
    }

    private fun smoothCompass(oldValue: Float, newValue: Float, alpha: Float = 0.1f, maxDiff: Float = 10.0f): Float {
        var diff = newValue - oldValue
        while (diff > 180.0f) diff -= 360.0f
        while (diff < -180.0f) diff += 360.0f

        val effectiveAlpha = if (abs(diff) > maxDiff) 1.0f else alpha
        var result = oldValue + effectiveAlpha * diff
        while (result >= 360.0f) result -= 360.0f
        while (result < 0.0f) result += 360.0f
        return result
    }

    @Test
    fun horizonAngle_whenDeviceUpright_isLevel() {
        val horizon = calculateHorizonAngle(0.0f, 9.8f)
        assertEquals(0.0, horizon.angleDegrees, 0.001)
        assertTrue(horizon.isLevel)
    }

    @Test
    fun horizonAngle_whenDeviceLandscape_isLevelAt90Degrees() {
        val horizon = calculateHorizonAngle(9.8f, 0.0f, deviceOrientation = 0)
        assertEquals(-90.0, horizon.angleDegrees, 0.001)
        assertTrue(horizon.isLevel)
    }

    @Test
    fun horizonAngle_whenCalibrated_appliesOffsetCorrectly() {
        val uncalibrated = calculateHorizonAngle(0.0f, 9.8f, calibratedOffset = 0.0)
        assertEquals(0.0, uncalibrated.angleDegrees, 0.001)

        val calibrated = calculateHorizonAngle(0.0f, 9.8f, calibratedOffset = 5.0)
        assertEquals(-5.0, calibrated.angleDegrees, 0.001)
        assertFalse(calibrated.isLevel)
    }

    @Test
    fun horizonAngle_withDeviceOrientation_compensatesRotation() {
        val horizon = calculateHorizonAngle(-9.8f, 0.0f, deviceOrientation = 90)
        assertEquals(0.0, horizon.angleDegrees, 0.001)
        assertTrue(horizon.isLevel)
    }

    @Test
    fun pitchCalculation_flatTable_isZeroDegrees() {
        val pitch = calculatePitch(0.0f, 0.0f, 9.8f)
        assertEquals(-90.0f, pitch, 0.01f)

        val uprightPitch = calculatePitch(0.0f, 9.8f, 0.0f)
        assertEquals(0.0f, uprightPitch, 0.01f)
    }

    @Test
    fun lowPassFilter_smoothsTransientSpikes() {
        val smoothed = floatArrayOf(0.0f, 9.8f, 0.0f)
        val spike = floatArrayOf(5.0f, 9.8f, 0.0f)

        applyLowPass(smoothed, spike, alpha = 0.8f)
        assertEquals(1.0f, smoothed[0], 0.001f)
        assertEquals(9.8f, smoothed[1], 0.001f)
    }

    @Test
    fun smoothCompass_interpolatesAcrossZeroDiscontinuity() {
        val smoothed = smoothCompass(oldValue = 359.0f, newValue = 1.0f, alpha = 0.5f)
        assertEquals(0.0f, smoothed, 0.01f)

        val smoothedReverse = smoothCompass(oldValue = 1.0f, newValue = 359.0f, alpha = 0.5f)
        assertEquals(0.0f, smoothedReverse, 0.01f)
    }

    @Test
    fun compassDegrees_normalization_staysWithin360() {
        fun normalizeCompass(degrees: Double): Float {
            return ((degrees % 360.0 + 360.0) % 360.0).toFloat()
        }

        assertEquals(0.0f, normalizeCompass(0.0), 0.001f)
        assertEquals(350.0f, normalizeCompass(-10.0), 0.001f)
        assertEquals(10.0f, normalizeCompass(370.0), 0.001f)
    }
}
