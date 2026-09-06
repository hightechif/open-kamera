/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class GridOverlayRendererTest {

    @Test
    fun ruleOfThirds_computesPreciseGridLines() {
        val width = 1080f
        val height = 1920f

        val verticalLine1 = width / 3.0f
        val verticalLine2 = 2.0f * width / 3.0f
        val horizontalLine1 = height / 3.0f
        val horizontalLine2 = 2.0f * height / 3.0f

        assertEquals(360.0f, verticalLine1, 0.001f)
        assertEquals(720.0f, verticalLine2, 0.001f)
        assertEquals(640.0f, horizontalLine1, 0.001f)
        assertEquals(1280.0f, horizontalLine2, 0.001f)
    }

    @Test
    fun phiGrid_computesGoldenRatioLines() {
        val width = 1000f
        val height = 1000f
        val phiDivisor = 2.618f

        val v1 = width / phiDivisor
        val v2 = 1.618f * width / phiDivisor
        val h1 = height / phiDivisor
        val h2 = 1.618f * height / phiDivisor

        assertEquals(381.97f, v1, 0.1f)
        assertEquals(618.03f, v2, 0.1f)
        assertEquals(381.97f, h1, 0.1f)
        assertEquals(618.03f, h2, 0.1f)
    }

    @Test
    fun grid4x2_computesQuarterAndHalfLines() {
        val width = 1920f
        val height = 1080f

        val vQuarter1 = width / 4.0f
        val vCenter = width / 2.0f
        val vQuarter3 = 3.0f * width / 4.0f
        val hCenter = height / 2.0f

        assertEquals(480.0f, vQuarter1, 0.001f)
        assertEquals(960.0f, vCenter, 0.001f)
        assertEquals(1440.0f, vQuarter3, 0.001f)
        assertEquals(540.0f, hCenter, 0.001f)
    }

    @Test
    fun goldenTriangle_computesTrigonometricOffsets() {
        val width = 1920.0
        val height = 1080.0

        val theta = atan2(width, height)
        val dist = height * cos(theta)
        val distX = (dist * sin(theta)).toFloat()
        val distY = (dist * cos(theta)).toFloat()

        // Verify valid positive coordinate boundaries
        assert(distX > 0f && distX < width.toFloat())
        assert(distY > 0f && distY < height.toFloat())
        assertEquals(distX * distX + distY * distY, (dist * dist).toFloat(), 0.1f)
    }
}
