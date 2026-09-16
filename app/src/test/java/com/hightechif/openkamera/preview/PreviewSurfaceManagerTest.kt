/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import android.view.View
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preview.camerasurface.CameraSurface
import com.hightechif.openkamera.preview.camerasurface.PreviewSurfaceManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewSurfaceManagerTest {

    @Test
    fun testMatrixCalculations() {
        val context = RuntimeEnvironment.getApplication()
        val mockView = View(context)
        val mockCameraSurface = mockk<CameraSurface>(relaxed = true)
        every { mockCameraSurface.view } returns mockView

        val testDispatcher = StandardTestDispatcher()
        val surfaceManager = PreviewSurfaceManager(mockCameraSurface, testDispatcher)
        surfaceManager.previewWidth = 1920
        surfaceManager.previewHeight = 1080

        val mockController = mockk<CameraController>(relaxed = true)
        every { mockController.cameraOrientation } returns 90
        every { mockController.displayOrientation } returns 0
        every { mockController.facing } returns CameraController.Facing.FACING_BACK

        val previewToCamera = surfaceManager.calculatePreviewToCameraMatrix(mockController, 0, true)
        assertNotNull(previewToCamera)

        val cameraToPreview = surfaceManager.calculateCameraToPreviewMatrix(mockController, 0, true)
        assertNotNull(cameraToPreview)
    }

    @Test
    fun testRunOnBackgroundThread() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val mockView = View(context)
        val mockCameraSurface = mockk<CameraSurface>(relaxed = true)
        every { mockCameraSurface.view } returns mockView

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val surfaceManager = PreviewSurfaceManager(mockCameraSurface, testDispatcher)
        val result = surfaceManager.runOnBackgroundThread {
            42 * 2
        }
        assertEquals(84, result)
    }

    @Test
    fun testTextureTransform() {
        val context = RuntimeEnvironment.getApplication()
        val mockView = View(context)
        val mockCameraSurface = mockk<CameraSurface>(relaxed = true)
        every { mockCameraSurface.view } returns mockView

        val surfaceManager = PreviewSurfaceManager(mockCameraSurface)
        surfaceManager.previewWidth = 1920
        surfaceManager.previewHeight = 1080

        val matrix = surfaceManager.calculateTextureTransform(
            textureViewWidth = 1080,
            textureViewHeight = 1920,
            displayRotation = 0
        )
        assertNotNull(matrix)
    }
}

