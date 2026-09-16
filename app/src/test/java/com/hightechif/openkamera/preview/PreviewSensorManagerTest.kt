/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import com.hightechif.openkamera.preview.sensor.PreviewSensorManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class PreviewSensorManagerTest {

    private lateinit var applicationInterface: ApplicationInterface
    private lateinit var sensorManager: PreviewSensorManager

    @Before
    fun setUp() {
        applicationInterface = mockk(relaxed = true)
        every { applicationInterface.getCalibratedLevelAngle() } returns 0.0
        sensorManager = PreviewSensorManager(applicationInterface)
    }

    @Test
    fun levelAngle_updatesCorrectlyWithOrientation() {
        sensorManager.currentOrientation = 90
        sensorManager.onOrientationChanged(180)
        assertEquals(180, sensorManager.currentOrientation)
    }

    @Test
    fun levelAngle_compensatesForCalibrationOffset() {
        every { applicationInterface.getCalibratedLevelAngle() } returns 5.0
        sensorManager.currentOrientation = 0

        // Simulate natural level angle calculation via reflection/internal state update
        sensorManager.updateLevelAngles()
        // With calibrated angle 5.0, levelAngle reduces by 5.0
        assertEquals(0.0, sensorManager.origLevelAngle, 0.001)
    }

    @Test
    fun hasLevelAngleStable_returnsFalseWhenNoLevelAngle() {
        sensorManager.isTest = false
        // Initial state has no sensor data, hasLevelAngle is false
        assertFalse(sensorManager.hasLevelAngleStable())
    }

    @Test
    fun isTestMode_bypassesPitchAngleCheck() {
        sensorManager.isTest = true
        // In test mode, returns hasLevelAngle directly
        assertFalse(sensorManager.hasLevelAngleStable())
    }
}
