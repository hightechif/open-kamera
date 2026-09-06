package com.hightechif.openkamera.cameracontroller.focus

import android.hardware.camera2.CaptureResult
import com.hightechif.openkamera.cameracontroller.CameraController
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Camera2FocusMeteringCoordinatorTest {

    private lateinit var focusCoordinator: Camera2FocusMeteringCoordinator

    @Before
    fun setUp() {
        focusCoordinator = Camera2FocusMeteringCoordinator(autofocusTimeoutMs = 1000L)
    }

    @Test
    fun autofocusTracking_startAndReset() {
        val mockCb = mockk<CameraController.AutoFocusCallback>(relaxed = true)
        focusCoordinator.startAutofocusTracking(
            mockCb,
            captureFollowsAutofocusHint = true,
            currentTimeMs = 1000L
        )

        assertEquals(mockCb, focusCoordinator.getAutofocusCallback())
        assertTrue(focusCoordinator.captureFollowsAutofocusHint)
        assertEquals(1000L, focusCoordinator.autofocusTimeMs)

        assertFalse(focusCoordinator.isAutofocusTimedOut(currentTimeMs = 1500L))
        assertTrue(focusCoordinator.isAutofocusTimedOut(currentTimeMs = 2100L))

        val popped = focusCoordinator.popAutofocusCallback()
        assertEquals(mockCb, popped)
        assertNull(focusCoordinator.getAutofocusCallback())
    }

    @Test
    fun continuousFocusMove_notifiesCallback() {
        val mockMoveCb = mockk<CameraController.ContinuousFocusMoveCallback>(relaxed = true)
        focusCoordinator.setContinuousFocusMoveCallback(mockMoveCb)

        focusCoordinator.evaluateContinuousFocusMove(CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN)
        verify { mockMoveCb.onContinuousFocusMove(true) }
    }
}
