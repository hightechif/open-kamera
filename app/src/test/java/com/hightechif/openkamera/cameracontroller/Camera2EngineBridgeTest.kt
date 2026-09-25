/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import com.hightechif.openkamera.cameracontroller.dispatcher.Camera2StateCallbackDispatcher
import com.hightechif.openkamera.domain.engine.CameraEngineState
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.FocusState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
class Camera2EngineBridgeTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockContext = mockk<Context>(relaxed = true)
    private lateinit var previewSurfaceManager: PreviewSurfaceManager
    private lateinit var bridge: Camera2EngineBridge

    private lateinit var mockController: CameraController2
    private lateinit var mockVideoPipeline: Camera2VideoPipeline
    private lateinit var callbackDispatcher: Camera2StateCallbackDispatcher

    @Before
    fun setUp() {
        previewSurfaceManager = PreviewSurfaceManager()
        bridge = Camera2EngineBridge(
            context = mockContext,
            previewSurfaceManager = previewSurfaceManager,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        callbackDispatcher = Camera2StateCallbackDispatcher()
        mockController = mockk(relaxed = true)
        mockVideoPipeline = mockk(relaxed = true)

        every { mockController.callbackDispatcher } returns callbackDispatcher
        every { mockController.videoPipeline } returns mockVideoPipeline
    }

    @Test
    fun testInitialState() {
        assertEquals(CameraEngineState.Uninitialized, bridge.engineStateFlow.value)
        assertTrue(bridge.focusStateFlow.value is FocusState.Idle)
        assertNull(bridge.frameMetadataFlow.value.iso)
        assertNull(bridge.activeController)
    }

    @Test
    fun testAttachAndDetachController_updatesLifecycleState() {
        bridge.attachController(mockController)
        assertEquals(mockController, bridge.activeController)
        assertEquals(CameraEngineState.Ready, bridge.engineStateFlow.value)
        assertEquals(1, callbackDispatcher.listenerCount)

        bridge.detachController()
        assertNull(bridge.activeController)
        assertEquals(CameraEngineState.Uninitialized, bridge.engineStateFlow.value)
        assertEquals(0, callbackDispatcher.listenerCount)
    }

    @Test
    fun testHalMetadataStreaming_updatesFrameMetadataAndFocusState() = runTest(testDispatcher) {
        bridge.attachController(mockController)

        val mockSession = mockk<CameraCaptureSession>(relaxed = true)
        val mockRequest = mockk<CaptureRequest>(relaxed = true)
        val mockResult = mockk<TotalCaptureResult>(relaxed = true)

        every { mockResult.get(CaptureResult.SENSOR_SENSITIVITY) } returns 800
        every { mockResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) } returns 33_333_333L
        every { mockResult.get(CaptureResult.LENS_APERTURE) } returns 1.9f
        every { mockResult.get(CaptureResult.LENS_FOCAL_LENGTH) } returns 24.0f
        every { mockResult.get(CaptureResult.LENS_FOCUS_DISTANCE) } returns 2.5f
        every { mockResult.get(CaptureResult.SENSOR_TIMESTAMP) } returns 100_000_000L
        every { mockResult.get(CaptureResult.CONTROL_AF_STATE) } returns CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN

        callbackDispatcher.onCaptureCompleted(mockSession, mockRequest, mockResult)

        assertEquals(800, bridge.frameMetadataFlow.value.iso)
        assertEquals(33_333_333L, bridge.frameMetadataFlow.value.exposureTimeNs)
        assertEquals(1.9f, bridge.frameMetadataFlow.value.aperture ?: 0f, 0.01f)
        assertEquals(24.0f, bridge.frameMetadataFlow.value.focalLengthMm ?: 0f, 0.01f)
        assertEquals(2.5f, bridge.frameMetadataFlow.value.focusDistanceMeters ?: 0f, 0.01f)
        assertEquals(100_000_000L, bridge.frameMetadataFlow.value.timestampNs)
        assertTrue(bridge.focusStateFlow.value is FocusState.Scanning)

        // Change AF state to focused locked
        every { mockResult.get(CaptureResult.CONTROL_AF_STATE) } returns CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        callbackDispatcher.onCaptureCompleted(mockSession, mockRequest, mockResult)
        assertTrue(bridge.focusStateFlow.value is FocusState.Focused)

        // Change AF state to failed
        every { mockResult.get(CaptureResult.CONTROL_AF_STATE) } returns CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        callbackDispatcher.onCaptureCompleted(mockSession, mockRequest, mockResult)
        assertTrue(bridge.focusStateFlow.value is FocusState.Failed)
    }

    @Test
    fun testSetZoom_forwardsToActiveController() = runTest(testDispatcher) {
        val mockFeatures = CameraController.SupportedValues(
            listOf("1.0", "2.0", "5.0"),
            "1.0"
        )
        every { mockController.cameraFeatures } returns CameraController.CameraFeatures().apply {
            zoomRatios = listOf(100, 200, 500)
            maxZoom = 500
        }
        bridge.attachController(mockController)

        bridge.setZoom(2.0f)
        assertEquals(2.0f, bridge.currentZoomRatio.value, 0.01f)
        verify { mockController.zoom = any() }
    }

    @Test
    fun testSetFlashMode_mapsToControllerModes() = runTest(testDispatcher) {
        bridge.attachController(mockController)

        bridge.setFlashMode(FlashMode.ON)
        verify { mockController.flashValue = FlashMode.ON.key }

        bridge.setFlashMode(FlashMode.AUTO)
        verify { mockController.flashValue = FlashMode.AUTO.key }

        bridge.setFlashMode(FlashMode.TORCH)
        verify { mockController.flashValue = FlashMode.TORCH.key }

        bridge.setFlashMode(FlashMode.OFF)
        verify { mockController.flashValue = FlashMode.OFF.key }
    }

    @Test
    fun testUnlockFocus_callsCancelAutoFocus() = runTest(testDispatcher) {
        bridge.attachController(mockController)
        bridge.unlockFocus()

        verify { mockController.cancelAutoFocus() }
        assertTrue(bridge.focusStateFlow.value is FocusState.Idle)
    }

    @Test
    fun controlsWithoutActiveController_areNoOps() = runTest(testDispatcher) {
        // Camera1 fallback: Preview never attaches a CameraController2, so the bridge has no active controller
        assertNull(bridge.activeController)
        val metadataBefore = bridge.frameMetadataFlow.value

        bridge.setZoom(2.0f)
        bridge.setManualFocus(android.graphics.PointF(0.5f, 0.5f))
        bridge.unlockFocus()
        bridge.setExposureCompensation(1)
        bridge.setFlashMode(FlashMode.ON)
        bridge.startPreview()
        bridge.stopPreview()

        assertNull(bridge.activeController)
        assertEquals(metadataBefore, bridge.frameMetadataFlow.value)
        assertEquals(CameraEngineState.Uninitialized, bridge.engineStateFlow.value)
    }
}
