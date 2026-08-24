/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModelsTest {

    @Test
    fun flashMode_fromKey_resolvesCorrectly() {
        assertEquals(FlashMode.OFF, FlashMode.fromKey("flash_off"))
        assertEquals(FlashMode.AUTO, FlashMode.fromKey("flash_auto"))
        assertEquals(FlashMode.ON, FlashMode.fromKey("flash_on"))
        assertEquals(FlashMode.TORCH, FlashMode.fromKey("flash_torch"))
        assertEquals(FlashMode.RED_EYE, FlashMode.fromKey("flash_red_eye"))
        assertEquals(FlashMode.AUTO, FlashMode.fromKey("unknown_key"))
    }

    @Test
    fun gridType_fromKey_resolvesCorrectly() {
        assertEquals(GridType.NONE, GridType.fromKey("preference_grid_none"))
        assertEquals(GridType.RULE_OF_THIRDS, GridType.fromKey("preference_grid_3x3"))
        assertEquals(GridType.PHI_GRID, GridType.fromKey("preference_grid_phi_3x3"))
        assertEquals(GridType.GRID_4X2, GridType.fromKey("preference_grid_4x2"))
        assertEquals(GridType.CROSSHAIR, GridType.fromKey("preference_grid_crosshair"))
        assertEquals(GridType.GOLDEN_SPIRAL, GridType.fromKey("preference_grid_golden_spiral"))
        assertEquals(GridType.NONE, GridType.fromKey("invalid_grid"))
    }

    @Test
    fun exposureCompensation_evValue_calculatesCorrectly() {
        val exposure = ExposureCompensation(
            currentStep = 2,
            minStep = -4,
            maxStep = 4,
            stepSize = 0.5f
        )
        assertEquals(1.0f, exposure.evValue, 0.001f)

        val zeroExposure = ExposureCompensation(
            currentStep = 0,
            minStep = -4,
            maxStep = 4,
            stepSize = 0.333f
        )
        assertEquals(0.0f, zeroExposure.evValue, 0.001f)
    }

    @Test
    fun focusState_sealedVariants_instantiateProperly() {
        val idle: FocusState = FocusState.Idle
        assertEquals(FocusState.Idle, idle)

        val scanning = FocusState.Scanning(0.5f, 0.5f)
        assertEquals(0.5f, scanning.pointX)
        assertEquals(0.5f, scanning.pointY)

        val focused = FocusState.Focused()
        assertNull(focused.pointX)

        val failed = FocusState.Failed(0.2f, 0.8f)
        assertEquals(0.2f, failed.pointX)

        val locked: FocusState = FocusState.Locked
        assertEquals(FocusState.Locked, locked)
    }
}
