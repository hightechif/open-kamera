/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.content.Context
import android.graphics.PointF
import app.cash.turbine.test
import com.hightechif.openkamera.domain.engine.CaptureProgress
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.FocusState
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2EngineUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockContext = mockk<Context>(relaxed = true)
    private lateinit var previewSurfaceManager: PreviewSurfaceManager
    private lateinit var cameraEngine: Camera2EngineImpl

    @Before
    fun setUp() {
        previewSurfaceManager = PreviewSurfaceManager()
        cameraEngine = Camera2EngineImpl(
            context = mockContext,
            previewSurfaceManager = previewSurfaceManager,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )
    }

    @Test
    fun previewSurfaceManager_setAndClearSurface() {
        assertNull(previewSurfaceManager.currentSurface)

        previewSurfaceManager.clearSurface()
        assertNull(previewSurfaceManager.currentSurface)
    }

    @Test
    fun setZoom_clampsWithinMinAndMax() = runTest(testDispatcher) {
        cameraEngine.setZoom(5.0f)
        assertEquals(5.0f, cameraEngine.currentZoomRatio.value, 0.001f)

        cameraEngine.setZoom(50.0f)
        assertEquals(10.0f, cameraEngine.currentZoomRatio.value, 0.001f)

        cameraEngine.setZoom(0.5f)
        assertEquals(1.0f, cameraEngine.currentZoomRatio.value, 0.001f)
    }

    @Test
    fun setManualFocus_andUnlockFocus() = runTest(testDispatcher) {
        val point = PointF(0.5f, 0.5f)
        cameraEngine.setManualFocus(point)

        assertTrue(cameraEngine.focusStateFlow.value is FocusState.Focused)
        val focused = cameraEngine.focusStateFlow.value as FocusState.Focused
        assertEquals(0.5f, focused.pointX ?: 0.0f, 0.001f)
        assertEquals(0.5f, focused.pointY ?: 0.0f, 0.001f)

        cameraEngine.unlockFocus()
        assertTrue(cameraEngine.focusStateFlow.value is FocusState.Idle)
    }

    @Test
    fun setExposureCompensation_clampsWithinBounds() = runTest(testDispatcher) {
        cameraEngine.setExposureCompensation(2)
        assertEquals(2, cameraEngine.exposureCompensationFlow.value.currentStep)
    }

    @Test
    fun captureStillImage_emitsProgressAndCompleted() = runTest(testDispatcher) {
        val config = CaptureConfig(burstExposures = listOf(-2, 0, 2), enableRaw = true)

        cameraEngine.captureStillImage(config).test {
            assertTrue(awaitItem() is CaptureProgress.Processing)
            assertTrue(awaitItem() is CaptureProgress.CapturingBurst)
            assertTrue(awaitItem() is CaptureProgress.CapturingBurst)
            assertTrue(awaitItem() is CaptureProgress.CapturingBurst)
            assertTrue(awaitItem() is CaptureProgress.Processing)

            val completed = awaitItem()
            assertTrue(completed is CaptureProgress.Completed)
            val result = completed as CaptureProgress.Completed
            assertEquals(3, result.jpegBytes.size)
            assertEquals(4, result.dngBytes?.size)

            awaitComplete()
        }
    }
}
