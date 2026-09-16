/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import org.junit.Assert.*
import org.junit.Test

class CameraCaptureStateMachineTest {

    @Test
    fun testInitialState() {
        val sm = CameraCaptureStateMachine()
        assertTrue(sm.isClosed)
        assertFalse(sm.isOpening)
        assertFalse(sm.isOpened)
        assertFalse(sm.isClosing)
        assertEquals(CameraCaptureStateMachine.CaptureState.STATE_IDLE, sm.captureState)
    }

    @Test
    fun testStateTransitions() {
        val sm = CameraCaptureStateMachine()

        sm.setOpenState(CameraCaptureStateMachine.CameraOpenState.CAMERAOPENSTATE_OPENING)
        assertTrue(sm.isOpening)
        assertFalse(sm.isClosed)

        sm.setOpenState(CameraCaptureStateMachine.CameraOpenState.CAMERAOPENSTATE_OPENED)
        assertTrue(sm.isOpened)
        assertFalse(sm.isOpening)

        sm.setCaptureState(CameraCaptureStateMachine.CaptureState.STATE_WAITING_AUTOFOCUS)
        assertEquals(CameraCaptureStateMachine.CaptureState.STATE_WAITING_AUTOFOCUS, sm.captureState)

        sm.setOpenState(CameraCaptureStateMachine.CameraOpenState.CAMERAOPENSTATE_CLOSING)
        assertTrue(sm.isClosing)

        sm.reset()
        assertTrue(sm.isClosed)
        assertEquals(CameraCaptureStateMachine.CaptureState.STATE_IDLE, sm.captureState)
    }
}
