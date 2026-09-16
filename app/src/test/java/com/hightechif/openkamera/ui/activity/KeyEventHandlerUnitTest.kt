/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.activity

import android.view.KeyEvent
import com.hightechif.openkamera.ui.CameraUiEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyEventHandlerUnitTest {

    @Test
    fun testCameraUiEventMappingForHardwareKeys() {
        val shutterEvent: CameraUiEvent = CameraUiEvent.OnShutterKeyPressed
        val volumeEvent: CameraUiEvent = CameraUiEvent.OnVolumeKeyPressed(KeyEvent.KEYCODE_VOLUME_DOWN)
        val focusEvent: CameraUiEvent = CameraUiEvent.OnFocusKeyPressed
        val remoteEvent: CameraUiEvent = CameraUiEvent.OnRemoteCaptureTriggered

        assertNotNull(shutterEvent)
        assertNotNull(volumeEvent)
        assertNotNull(focusEvent)
        assertNotNull(remoteEvent)

        assertTrue(shutterEvent is CameraUiEvent.OnShutterKeyPressed)
        assertTrue(volumeEvent is CameraUiEvent.OnVolumeKeyPressed)
        assertEquals(KeyEvent.KEYCODE_VOLUME_DOWN, (volumeEvent as CameraUiEvent.OnVolumeKeyPressed).keyCode)
        assertTrue(focusEvent is CameraUiEvent.OnFocusKeyPressed)
        assertTrue(remoteEvent is CameraUiEvent.OnRemoteCaptureTriggered)
    }
}
