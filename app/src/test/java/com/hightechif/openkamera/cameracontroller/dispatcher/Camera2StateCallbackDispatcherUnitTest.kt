/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.dispatcher

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.view.Surface
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2StateCallbackDispatcherUnitTest {

    @Test
    fun testAddRemoveClearListeners() {
        val dispatcher = Camera2StateCallbackDispatcher()
        val listener1 = mockk<CaptureEventListener>(relaxed = true)
        val listener2 = mockk<CaptureEventListener>(relaxed = true)

        assertEquals(0, dispatcher.listenerCount)

        assertTrue(dispatcher.addListener(listener1))
        assertTrue(dispatcher.addListener(listener2))
        assertEquals(2, dispatcher.listenerCount)

        // Adding duplicate listener returns false
        assertFalse(dispatcher.addListener(listener1))

        assertTrue(dispatcher.removeListener(listener1))
        assertEquals(1, dispatcher.listenerCount)

        dispatcher.clearListeners()
        assertEquals(0, dispatcher.listenerCount)
    }

    @Test
    fun testDispatchCaptureStartedAndCompleted() {
        val dispatcher = Camera2StateCallbackDispatcher()
        val listener = mockk<CaptureEventListener>(relaxed = true)
        dispatcher.addListener(listener)

        val mockSession = mockk<CameraCaptureSession>(relaxed = true)
        val mockRequest = mockk<CaptureRequest>(relaxed = true)
        val mockResult = mockk<TotalCaptureResult>(relaxed = true)

        dispatcher.onCaptureStarted(mockSession, mockRequest, 1000L, 42L)
        verify { listener.onStarted(mockSession, mockRequest, 1000L, 42L) }

        dispatcher.onCaptureCompleted(mockSession, mockRequest, mockResult)
        verify { listener.onCompleted(mockSession, mockRequest, mockResult) }
    }

    @Test
    fun testDispatchCaptureFailedAndBufferLost() {
        val dispatcher = Camera2StateCallbackDispatcher()
        val listener = mockk<CaptureEventListener>(relaxed = true)
        dispatcher.addListener(listener)

        val mockSession = mockk<CameraCaptureSession>(relaxed = true)
        val mockRequest = mockk<CaptureRequest>(relaxed = true)
        val mockFailure = mockk<CaptureFailure>(relaxed = true)
        val mockSurface = mockk<Surface>(relaxed = true)

        dispatcher.onCaptureFailed(mockSession, mockRequest, mockFailure)
        verify { listener.onFailed(mockSession, mockRequest, mockFailure) }

        dispatcher.onCaptureBufferLost(mockSession, mockRequest, mockSurface, 55L)
        verify { listener.onBufferLost(mockSession, mockRequest, mockSurface, 55L) }
    }

    @Test
    fun testListenerExceptionIsolation() {
        val dispatcher = Camera2StateCallbackDispatcher()
        val faultyListener = object : CaptureEventListener {
            override fun onCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                throw RuntimeException("Faulty listener crash")
            }
        }
        val healthyListener = mockk<CaptureEventListener>(relaxed = true)

        dispatcher.addListener(faultyListener)
        dispatcher.addListener(healthyListener)

        val mockSession = mockk<CameraCaptureSession>(relaxed = true)
        val mockRequest = mockk<CaptureRequest>(relaxed = true)
        val mockResult = mockk<TotalCaptureResult>(relaxed = true)

        // Should not crash and should invoke healthyListener
        dispatcher.onCaptureCompleted(mockSession, mockRequest, mockResult)
        verify { healthyListener.onCompleted(mockSession, mockRequest, mockResult) }
    }
}
