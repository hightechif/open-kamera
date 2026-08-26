/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.graphics.Color
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.FocusState
import com.hightechif.openkamera.domain.model.GridType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DrawPreviewUnitTest {

    @Test
    fun hudOverlayState_defaultInitialization() {
        val state = HudOverlayState()

        assertEquals(GridType.NONE, state.gridType)
        assertFalse(state.showAngleLine)
        assertEquals(0.0, state.horizonAngle, 0.001)
        assertFalse(state.isLevel)
        assertEquals(Color.GREEN, state.angleHighlightColor)
        assertEquals(FlashMode.AUTO, state.flashMode)
        assertEquals(FocusState.Idle, state.focusState)
    }

    @Test
    fun ruleOfThirds_coordinatesComputation() {
        val width = 1080
        val height = 1920

        val v1 = width / 3.0f
        val v2 = 2 * width / 3.0f
        val h1 = height / 3.0f
        val h2 = 2 * height / 3.0f

        assertEquals(360.0f, v1, 0.001f)
        assertEquals(720.0f, v2, 0.001f)
        assertEquals(640.0f, h1, 0.001f)
        assertEquals(1280.0f, h2, 0.001f)
    }

    @Test
    fun phiGrid_goldenRatioCoordinatesComputation() {
        val width = 1000
        val height = 1000

        val phi1 = 0.382f
        val phi2 = 0.618f

        val v1 = width * phi1
        val v2 = width * phi2

        assertEquals(382.0f, v1, 0.001f)
        assertEquals(618.0f, v2, 0.001f)
    }

    @Test
    fun horizonLevelTolerance_evaluation() {
        val closeLevelAngle = 1.0

        fun isDeviceLevel(angleDegrees: Double): Boolean {
            return abs(angleDegrees) <= closeLevelAngle
        }

        assertTrue(isDeviceLevel(0.0))
        assertTrue(isDeviceLevel(0.5))
        assertTrue(isDeviceLevel(-0.8))
        assertTrue(isDeviceLevel(1.0))
        assertFalse(isDeviceLevel(1.1))
        assertFalse(isDeviceLevel(-2.5))
        assertFalse(isDeviceLevel(45.0))
    }

    @Test
    fun telemetryFormatting_shutterSpeedAndIso() {
        fun formatIsoExposure(iso: Int, exposureNs: Long): String {
            val exposureSecs = exposureNs / 1_000_000_000.0
            val fraction = if (exposureSecs > 0 && exposureSecs < 1.0) {
                "1/" + (1.0 / exposureSecs).toInt() + "s"
            } else {
                "${exposureSecs}s"
            }
            return "ISO $iso $fraction"
        }

        val formatted = formatIsoExposure(400, 10_000_000L) // 1/100s
        assertEquals("ISO 400 1/100s", formatted)

        val formattedLong = formatIsoExposure(100, 2_000_000_000L) // 2s
        assertEquals("ISO 100 2.0s", formattedLong)
    }

    @Test
    fun freeMemoryFormatting_mbToGb() {
        fun formatFreeMemoryGb(freeMb: Long): Float {
            return if (freeMb >= 0) freeMb / 1024.0f else -1.0f
        }

        assertEquals(1.5f, formatFreeMemoryGb(1536L), 0.001f)
        assertEquals(4.0f, formatFreeMemoryGb(4096L), 0.001f)
        assertEquals(-1.0f, formatFreeMemoryGb(-1L), 0.001f)
    }
}
