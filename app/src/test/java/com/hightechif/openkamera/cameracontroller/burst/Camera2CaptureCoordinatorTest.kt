package com.hightechif.openkamera.cameracontroller.burst

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Camera2CaptureCoordinatorTest {

    private lateinit var coordinator: Camera2CaptureCoordinator

    @Before
    fun setUp() {
        coordinator = Camera2CaptureCoordinator(maxExpoBracketingNImages = 7)
    }

    @Test
    fun expoBracketing_configurationValid() {
        coordinator.setExpoBracketingNImages(5)
        assertEquals(5, coordinator.expoBracketingNImages)

        coordinator.setExpoBracketingStops(1.5)
        assertEquals(1.5, coordinator.expoBracketingStops, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun expoBracketing_evenImages_throws() {
        coordinator.setExpoBracketingNImages(4)
    }

    @Test
    fun continuousBurst_stateTransitions() {
        assertFalse(coordinator.isContinuousBurstInProgress)
        coordinator.startContinuousBurst()
        assertTrue(coordinator.isContinuousBurstInProgress)

        coordinator.stopContinuousBurst()
        assertFalse(coordinator.isContinuousBurstInProgress)
    }

    @Test
    fun focusBracketing_generatesCorrectDistances() {
        coordinator.focusBracketingSourceDistance = 1.0f
        coordinator.focusBracketingTargetDistance = 5.0f
        coordinator.focusBracketingNImages = 3
        coordinator.focusBracketingAddInfinity = true

        val distances = coordinator.generateFocusBracketingDistances()
        assertEquals(4, distances.size)
        assertEquals(0.0f, distances.last(), 0.001f)
    }
}
