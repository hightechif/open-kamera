/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.lifecycle

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.view.Surface
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2SessionManagerUnitTest {

    @Test
    fun testInitialState() {
        val manager = Camera2SessionManager()
        assertNull(manager.cameraDevice)
        assertNull(manager.captureSession)
        assertNull(manager.extensionSession)
        assertEquals(CameraDeviceState.Closed, manager.deviceState)
        assertEquals(CaptureSessionState.Closed, manager.sessionState)
    }

    @Test
    fun testOnCameraOpenedAndClosed() {
        val manager = Camera2SessionManager()
        val mockDevice = mockk<CameraDevice>(relaxed = true)

        manager.onCameraOpened(mockDevice)
        assertEquals(mockDevice, manager.cameraDevice)
        assertTrue(manager.deviceState is CameraDeviceState.Opened)

        manager.closeCamera()
        assertNull(manager.cameraDevice)
        assertEquals(CameraDeviceState.Closed, manager.deviceState)
        verify { mockDevice.close() }
    }

    @Test
    fun testOnSessionConfiguredAndClosed() {
        val manager = Camera2SessionManager()
        val mockSession = mockk<CameraCaptureSession>(relaxed = true)

        manager.onSessionConfigured(mockSession)
        assertEquals(mockSession, manager.captureSession)
        assertTrue(manager.sessionState is CaptureSessionState.Configured)

        manager.closeCaptureSession()
        assertNull(manager.captureSession)
        assertEquals(CaptureSessionState.Closed, manager.sessionState)
        verify { mockSession.close() }
    }

    @Test
    fun testCreateOutputConfigurations() {
        val manager = Camera2SessionManager()
        val mockSurface1 = mockk<Surface>(relaxed = true)
        val mockSurface2 = mockk<Surface>(relaxed = true)

        val configs = manager.createOutputConfigurations(
            surfaces = listOf(mockSurface1, mockSurface2),
            physicalCameraId = "2"
        )

        assertEquals(2, configs.size)
        assertNotNull(configs[0])
        assertNotNull(configs[1])
    }
}
