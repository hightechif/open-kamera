/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import com.hightechif.openkamera.domain.model.FocusState
import com.hightechif.openkamera.domain.model.GridType
import com.hightechif.openkamera.domain.model.HorizonAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DrawPreviewCalculationUnitTest {

    @Test
    fun gridType_ruleOfThirds_computesCorrectCoordinates() {
        val width = 1080f
        val height = 1920f

        val verticalLine1 = width / 3.0f
        val verticalLine2 = 2.0f * width / 3.0f
        val horizontalLine1 = height / 3.0f
        val horizontalLine2 = 2.0f * height / 3.0f

        assertEquals(360.0f, verticalLine1, 0.01f)
        assertEquals(720.0f, verticalLine2, 0.01f)
        assertEquals(640.0f, horizontalLine1, 0.01f)
        assertEquals(1280.0f, horizontalLine2, 0.01f)
    }

    @Test
    fun gridType_phiGrid_computesGoldenRatioCoordinates() {
        val width = 1000f
        val phi = 1.6180339887f

        val line1 = width / (1.0f + phi)
        val line2 = width - line1

        assertEquals(381.966f, line1, 0.01f)
        assertEquals(618.034f, line2, 0.01f)
    }

    @Test
    fun horizonAngle_levelAngleNormalization() {
        val horizon = HorizonAngle(angleDegrees = 0.5, isLevel = true)
        assertTrue(horizon.isLevel)
        assertTrue(abs(horizon.angleDegrees) <= 1.0)

        val tiltedHorizon = HorizonAngle(angleDegrees = 14.2, isLevel = false)
        assertTrue(!tiltedHorizon.isLevel)
    }

    @Test
    fun focusState_mappingVerification() {
        val scanningState = FocusState.Scanning(100f, 200f)
        assertEquals(100f, scanningState.pointX ?: 0f, 0.01f)
        assertEquals(200f, scanningState.pointY ?: 0f, 0.01f)

        val focusedState = FocusState.Focused(300f, 400f)
        assertEquals(300f, focusedState.pointX ?: 0f, 0.01f)
        assertEquals(400f, focusedState.pointY ?: 0f, 0.01f)

        val failedState = FocusState.Failed(500f, 600f)
        assertNotNull(failedState)
    }

    @Test
    fun gridType_enumKeys_matchPreferences() {
        assertEquals("preference_grid_none", GridType.NONE.key)
        assertEquals("preference_grid_3x3", GridType.RULE_OF_THIRDS.key)
        assertEquals("preference_grid_phi_3x3", GridType.PHI_GRID.key)
        assertEquals("preference_grid_4x2", GridType.GRID_4X2.key)
        assertEquals("preference_grid_crosshair", GridType.CROSSHAIR.key)
        assertEquals("preference_grid_golden_spiral", GridType.GOLDEN_SPIRAL.key)
    }
}
