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
import com.hightechif.openkamera.domain.engine.CameraEngineState
import com.hightechif.openkamera.domain.engine.CaptureProgress
import com.hightechif.openkamera.domain.model.CameraFacing
import com.hightechif.openkamera.domain.model.CameraFrameMetadata
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.FlashMode
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
import java.io.File

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
    fun setFlashMode_updatesWithoutError() = runTest(testDispatcher) {
        cameraEngine.setFlashMode(FlashMode.ON)
        cameraEngine.setFlashMode(FlashMode.TORCH)
        cameraEngine.setFlashMode(FlashMode.AUTO)
        cameraEngine.setFlashMode(FlashMode.OFF)
    }

    @Test
    fun updateFrameMetadata_emitsOnFlow() = runTest(testDispatcher) {
        val metadata = CameraFrameMetadata(
            iso = 400,
            exposureTimeNs = 20_000_000L,
            aperture = 1.8f,
            focalLengthMm = 26.0f,
            focusDistanceMeters = 1.2f
        )
        cameraEngine.updateFrameMetadata(metadata)
        assertEquals(400, cameraEngine.frameMetadataFlow.value.iso)
        assertEquals(20_000_000L, cameraEngine.frameMetadataFlow.value.exposureTimeNs)
        assertEquals(1.8f, cameraEngine.frameMetadataFlow.value.aperture ?: 0f, 0.01f)
    }

    @Test
    fun videoRecording_transitionsState() = runTest(testDispatcher) {
        val dummyFile = File("/tmp/test_video.mp4")
        val startResult = cameraEngine.startVideoRecording(dummyFile)
        assertTrue(startResult.isSuccess)
        assertEquals(CameraEngineState.Recording, cameraEngine.engineStateFlow.value)

        val stopResult = cameraEngine.stopVideoRecording()
        assertTrue(stopResult.isSuccess)
        assertEquals(CameraEngineState.Ready, cameraEngine.engineStateFlow.value)
    }

    @Test
    fun closeCamera_resetsStateToUninitialized() = runTest(testDispatcher) {
        cameraEngine.closeCamera()
        assertEquals(CameraEngineState.Uninitialized, cameraEngine.engineStateFlow.value)
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
