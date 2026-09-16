/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HorizonAngleOverlayRendererTest {

    @Test
    fun levelAngle_toleranceEvaluation() {
        val tolerance = RendererUtils.CLOSE_LEVEL_ANGLE

        fun isLevel(angle: Double): Boolean = abs(angle) <= tolerance

        assertTrue(isLevel(0.0))
        assertTrue(isLevel(0.5))
        assertTrue(isLevel(-0.99))
        assertTrue(isLevel(1.0))
        assertFalse(isLevel(1.01))
        assertFalse(isLevel(-1.5))
    }

    @Test
    fun pitchAngle_stepLinesComputation() {
        val minAngle = -15
        val maxAngle = 15
        val step = 5

        val angles = (minAngle..maxAngle step step).toList()
        assertEquals(listOf(-15, -10, -5, 0, 5, 10, 15), angles)
    }

    @Test
    fun pitchAnglePixelOffset_scalesWithFov() {
        val previewHeight = 1080
        val viewAngleY = 50.0 // Degrees vertical FOV
        val pitchAngle = 5.0 // Degrees

        val pixelOffset = (pitchAngle / viewAngleY) * previewHeight
        assertEquals(108.0, pixelOffset, 0.01)
    }
}
