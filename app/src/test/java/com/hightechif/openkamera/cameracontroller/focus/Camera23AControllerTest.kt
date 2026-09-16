/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.focus

import android.hardware.camera2.CaptureResult
import com.hightechif.openkamera.cameracontroller.CameraController
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera23AControllerTest {

    @Test
    fun testPrecaptureLifecycle() {
        val controller = Camera23AController()
        assertFalse(controller.isAePrecaptureRunning.value)

        controller.startPrecapture()
        assertTrue(controller.isAePrecaptureRunning.value)

        controller.finishPrecapture()
        assertFalse(controller.isAePrecaptureRunning.value)
    }

    @Test
    fun testAfScanTrackingAndCancel() {
        val controller = Camera23AController()
        val mockCb = mockk<CameraController.AutoFocusCallback>(relaxed = true)

        controller.triggerAfScan(mockCb, true)
        val popped = controller.cancelAfScan()
        assertEquals(mockCb, popped)
        assertNull(controller.cancelAfScan())
    }

    @Test
    fun testUpdate3AState() {
        val controller = Camera23AController()
        val mockResult = mockk<CaptureResult>(relaxed = true)
        every { mockResult.get(CaptureResult.CONTROL_AF_STATE) } returns CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN
        every { mockResult.get(CaptureResult.CONTROL_AE_STATE) } returns CaptureResult.CONTROL_AE_STATE_CONVERGED

        val evalResult = controller.update3AState(mockResult)
        assertNotNull(evalResult)
    }
}
