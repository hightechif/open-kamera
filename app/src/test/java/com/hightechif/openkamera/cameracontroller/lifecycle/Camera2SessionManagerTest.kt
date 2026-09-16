package com.hightechif.openkamera.cameracontroller.lifecycle

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class Camera2SessionManagerTest {

    private lateinit var sessionManager: Camera2SessionManager

    @Before
    fun setUp() {
        sessionManager = Camera2SessionManager()
    }

    @Test
    fun initial_stateIsClosed() {
        assertEquals(CameraDeviceState.Closed, sessionManager.deviceState)
        assertEquals(CaptureSessionState.Closed, sessionManager.sessionState)
        assertNull(sessionManager.cameraDevice)
        assertNull(sessionManager.captureSession)
    }

    @Test
    fun onCameraOpened_updatesDeviceState() {
        val mockDevice = mockk<CameraDevice>(relaxed = true)
        sessionManager.onCameraOpened(mockDevice)

        assertEquals(mockDevice, sessionManager.cameraDevice)
        assertEquals(CameraDeviceState.Opened(mockDevice), sessionManager.deviceState)
    }

    @Test
    fun onSessionConfigured_updatesSessionState() {
        val mockSession = mockk<CameraCaptureSession>(relaxed = true)
        sessionManager.onSessionConfigured(mockSession)

        assertEquals(mockSession, sessionManager.captureSession)
        assertEquals(CaptureSessionState.Configured(mockSession), sessionManager.sessionState)
    }

    @Test
    fun closeCaptureSession_closesAndResets() {
        val mockSession = mockk<CameraCaptureSession>(relaxed = true)
        sessionManager.onSessionConfigured(mockSession)

        sessionManager.closeCaptureSession()

        verify { mockSession.close() }
        assertNull(sessionManager.captureSession)
        assertEquals(CaptureSessionState.Closed, sessionManager.sessionState)
    }

    @Test
    fun clearCaptureSession_resetsWithoutClosing() {
        val mockSession = mockk<CameraCaptureSession>(relaxed = true)
        sessionManager.onSessionConfigured(mockSession)

        sessionManager.clearCaptureSession()

        verify(exactly = 0) { mockSession.close() }
        assertNull(sessionManager.captureSession)
        assertEquals(CaptureSessionState.Closed, sessionManager.sessionState)
    }

    @Test
    fun clearExtensionSession_resetsWithoutAffectingCaptureSession() {
        val mockSession = mockk<CameraCaptureSession>(relaxed = true)
        sessionManager.onSessionConfigured(mockSession)
        sessionManager.onExtensionSessionConfigured(Any())

        sessionManager.clearExtensionSession()

        assertNull(sessionManager.extensionSession)
        assertEquals(mockSession, sessionManager.captureSession)
        verify(exactly = 0) { mockSession.close() }
    }
}
