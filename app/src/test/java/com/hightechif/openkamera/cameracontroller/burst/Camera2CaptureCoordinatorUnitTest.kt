package com.hightechif.openkamera.cameracontroller.burst

import com.hightechif.openkamera.cameracontroller.CameraController.BurstType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2CaptureCoordinatorUnitTest {

    private lateinit var coordinator: Camera2CaptureCoordinator

    @Before
    fun setUp() {
        coordinator = Camera2CaptureCoordinator(maxExpoBracketingNImages = 63)
    }

    @Test
    fun testDefaultState() {
        assertEquals(BurstType.BURSTTYPE_NONE, coordinator.burstType)
        assertEquals(3, coordinator.expoBracketingNImages)
        assertEquals(2.0, coordinator.expoBracketingStops, 1e-6)
        assertTrue(coordinator.useExpoFastBurst)
        assertFalse(coordinator.dummyCaptureHack)
        assertFalse(coordinator.focusBracketingInProgress)
        assertFalse(coordinator.isContinuousBurstInProgress)
        assertFalse(coordinator.isCaptureFastBurst)
        assertEquals(Camera2CaptureState.Idle, coordinator.captureStateFlow.value)
    }

    @Test
    fun testExpoBracketingValidation() {
        coordinator.setExpoBracketingNImages(5)
        assertEquals(5, coordinator.expoBracketingNImages)

        // Limiting to max
        coordinator.setExpoBracketingNImages(99)
        assertEquals(63, coordinator.expoBracketingNImages)

        // Invalid even number throws
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.setExpoBracketingNImages(4)
        }

        // Invalid <= 1 throws
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.setExpoBracketingNImages(1)
        }
    }

    @Test
    fun testExpoBracketingStopsValidation() {
        coordinator.setExpoBracketingStops(1.5)
        assertEquals(1.5, coordinator.expoBracketingStops, 1e-6)

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.setExpoBracketingStops(0.0)
        }

        assertThrows(IllegalArgumentException::class.java) {
            coordinator.setExpoBracketingStops(-1.0)
        }
    }

    @Test
    fun testIsCaptureFastBurst() {
        coordinator.burstType = BurstType.BURSTTYPE_NONE
        assertFalse(coordinator.isCaptureFastBurst)

        coordinator.burstType = BurstType.BURSTTYPE_FOCUS
        assertFalse(coordinator.isCaptureFastBurst)

        coordinator.burstType = BurstType.BURSTTYPE_NORMAL
        assertTrue(coordinator.isCaptureFastBurst)

        coordinator.burstType = BurstType.BURSTTYPE_EXPO
        assertTrue(coordinator.isCaptureFastBurst)

        coordinator.burstType = BurstType.BURSTTYPE_CONTINUOUS
        assertTrue(coordinator.isCaptureFastBurst)
    }

    @Test
    fun testIsCapturingBurstCalculations() {
        coordinator.burstType = BurstType.BURSTTYPE_EXPO
        // BurstTotal is 3, taken 1 -> in progress
        assertTrue(coordinator.isCapturingBurst(nBurstTaken = 1, nBurstTotal = 3, nBurst = 3, nBurstRaw = 0))
        // BurstTotal is 3, taken 3 -> completed
        assertFalse(coordinator.isCapturingBurst(nBurstTaken = 3, nBurstTotal = 3, nBurst = 3, nBurstRaw = 0))

        coordinator.burstType = BurstType.BURSTTYPE_CONTINUOUS
        coordinator.startContinuousBurst()
        assertTrue(coordinator.isCapturingBurst(nBurstTaken = 0, nBurstTotal = 0, nBurst = 0, nBurstRaw = 0))
        coordinator.stopContinuousBurst()
        assertFalse(coordinator.isCapturingBurst(nBurstTaken = 0, nBurstTotal = 0, nBurst = 0, nBurstRaw = 0))
    }

    @Test
    fun testContinuousBurstStateTransitions() {
        coordinator.burstType = BurstType.BURSTTYPE_CONTINUOUS
        coordinator.startContinuousBurst()
        assertTrue(coordinator.isContinuousBurstInProgress)
        assertTrue(coordinator.captureStateFlow.value is Camera2CaptureState.Capturing)

        coordinator.stopContinuousBurst()
        assertFalse(coordinator.isContinuousBurstInProgress)
        assertTrue(coordinator.captureStateFlow.value is Camera2CaptureState.Completed)
    }

    @Test
    fun testFocusBracketingStateAndDistances() {
        coordinator.burstType = BurstType.BURSTTYPE_FOCUS
        coordinator.focusBracketingSourceDistance = 5.0f
        coordinator.focusBracketingTargetDistance = 0.5f
        coordinator.focusBracketingNImages = 5
        coordinator.focusBracketingAddInfinity = true

        coordinator.startFocusBracketing()
        assertTrue(coordinator.focusBracketingInProgress)
        val state = coordinator.captureStateFlow.value
        assertTrue(state is Camera2CaptureState.Preparing)
        assertEquals(6, (state as Camera2CaptureState.Preparing).targetCount)

        val distances = coordinator.generateFocusBracketingDistances()
        assertEquals(6, distances.size)
        assertEquals(5.0f, distances.first(), 1e-4f)
        assertEquals(0.5f, distances[4], 1e-4f)
        assertEquals(0.0f, distances.last(), 1e-4f) // Infinity

        coordinator.stopFocusBracketing()
        assertFalse(coordinator.focusBracketingInProgress)
        assertTrue(coordinator.captureStateFlow.value is Camera2CaptureState.Completed)
    }

    @Test
    fun testFocusBracketingCalculator() {
        val distances = FocusBracketingCalculator.setupFocusBracketingDistances(
            source = 10.0f,
            target = 1.0f,
            count = 4
        )
        assertEquals(4, distances.size)
        assertEquals(10.0f, distances[0], 1e-4f)
        assertEquals(1.0f, distances[3], 1e-4f)
        // Monotonically decreasing
        assertTrue(distances[0] > distances[1])
        assertTrue(distances[1] > distances[2])
        assertTrue(distances[2] > distances[3])
    }
}
