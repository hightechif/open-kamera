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
import kotlin.math.abs
import kotlin.math.atan2

class SensorCalculationTest {

    private fun calculateHorizonAngle(gravityX: Float, gravityY: Float): HorizonAngle {
        val angleRad = atan2(gravityX.toDouble(), gravityY.toDouble())
        val angleDeg = Math.toDegrees(angleRad)
        val isLevel =
            abs(angleDeg) <= 1.0 || abs(abs(angleDeg) - 90.0) <= 1.0 || abs(abs(angleDeg) - 180.0) <= 1.0
        return HorizonAngle(angleDegrees = angleDeg, isLevel = isLevel)
    }

    @Test
    fun horizonAngle_whenDeviceUpright_isLevel() {
        val horizon = calculateHorizonAngle(0.0f, 9.8f)
        assertEquals(0.0, horizon.angleDegrees, 0.001)
        assertTrue(horizon.isLevel)
    }

    @Test
    fun horizonAngle_whenDeviceLandscape_isLevelAt90Degrees() {
        val horizon = calculateHorizonAngle(9.8f, 0.0f)
        assertEquals(90.0, horizon.angleDegrees, 0.001)
        assertTrue(horizon.isLevel)
    }

    @Test
    fun horizonAngle_whenDeviceTiltedSlightly_isNotLevel() {
        val horizon = calculateHorizonAngle(1.0f, 9.7f)
        assertTrue(horizon.angleDegrees > 5.0)
        assertFalse(horizon.isLevel)
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
