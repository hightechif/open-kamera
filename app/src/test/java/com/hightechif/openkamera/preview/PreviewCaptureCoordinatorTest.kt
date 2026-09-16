/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PreviewCaptureCoordinatorTest {

    private lateinit var applicationInterface: ApplicationInterface
    private lateinit var captureCoordinator: PreviewCaptureCoordinator

    @Before
    fun setUp() {
        applicationInterface = mockk(relaxed = true)
        captureCoordinator = PreviewCaptureCoordinator(applicationInterface)
    }

    @Test
    fun computeExposureBracketingValues_generatesSymmetricalEVStops() {
        // 3 images, 1.0 stop spread -> [-1.0, 0.0, 1.0]
        val ev3 = captureCoordinator.computeExposureBracketingValues(3, 1.0)
        assertEquals(3, ev3.size)
        assertEquals(-1.0, ev3[0], 0.001)
        assertEquals(0.0, ev3[1], 0.001)
        assertEquals(1.0, ev3[2], 0.001)

        // 5 images, 2.0 stops spread -> [-2.0, -1.0, 0.0, 1.0, 2.0]
        val ev5 = captureCoordinator.computeExposureBracketingValues(5, 2.0)
        assertEquals(5, ev5.size)
        assertEquals(-2.0, ev5[0], 0.001)
        assertEquals(-1.0, ev5[1], 0.001)
        assertEquals(0.0, ev5[2], 0.001)
        assertEquals(1.0, ev5[3], 0.001)
        assertEquals(2.0, ev5[4], 0.001)
    }

    @Test
    fun computeFocusBracketingDistances_stepsDistancesLinearly() {
        // 3 images from 1.0m to 5.0m without infinity
        val distances = captureCoordinator.computeFocusBracketingDistances(
            sourceDistance = 1.0f,
            targetDistance = 5.0f,
            nImages = 3,
            addInfinity = false
        )
        assertEquals(3, distances.size)
        assertEquals(1.0f, distances[0], 0.001f)
        assertEquals(3.0f, distances[1], 0.001f)
        assertEquals(5.0f, distances[2], 0.001f)

        // With infinity (0.0f diopter added)
        val withInf = captureCoordinator.computeFocusBracketingDistances(
            sourceDistance = 1.0f,
            targetDistance = 5.0f,
            nImages = 3,
            addInfinity = true
        )
        assertEquals(4, withInf.size)
        assertEquals(0.0f, withInf[3], 0.001f)
    }

    @Test
    fun burstManagement_tracksProgressAndCompletes() {
        captureCoordinator.startBurst(3)
        assertTrue(captureCoordinator.isTakingPhoto)
        assertEquals(0, captureCoordinator.burstCount)
        assertEquals(3, captureCoordinator.burstTotal)

        assertTrue(captureCoordinator.onBurstPhotoTaken()) // Shot 1 (count=1, continue)
        assertTrue(captureCoordinator.onBurstPhotoTaken()) // Shot 2 (count=2, continue)
        assertFalse(captureCoordinator.onBurstPhotoTaken()) // Shot 3 (count=3, complete)

        assertFalse(captureCoordinator.isTakingPhoto)
    }
}
