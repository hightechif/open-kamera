/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.graphics.PointF
import android.view.Surface
import app.cash.turbine.test
import com.hightechif.openkamera.domain.engine.CameraEngineState
import com.hightechif.openkamera.domain.engine.CaptureProgress
import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.ExposureCompensation
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.GridType
import com.hightechif.openkamera.domain.model.SensorOrientation
import com.hightechif.openkamera.domain.repository.IMediaRepository
import com.hightechif.openkamera.domain.repository.ISensorRepository
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import com.hightechif.openkamera.domain.usecase.AdjustExposureUseCase
import com.hightechif.openkamera.domain.usecase.CapturePhotoUseCase
import com.hightechif.openkamera.domain.usecase.GetCameraCapabilitiesUseCase
import com.hightechif.openkamera.domain.usecase.RecordVideoUseCase
import com.hightechif.openkamera.domain.usecase.SetZoomUseCase
import com.hightechif.openkamera.domain.usecase.SwitchCameraFacingUseCase
import com.hightechif.openkamera.domain.usecase.TapToFocusUseCase
import com.hightechif.openkamera.domain.usecase.ToggleFlashUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelUnitTest {

    private val testDispatcher = StandardTestDispatcher()

    private val mockCameraEngine = mockk<ICameraEngine>(relaxed = true)
    private val mockCapturePhotoUseCase = mockk<CapturePhotoUseCase>(relaxed = true)
    private val mockRecordVideoUseCase = mockk<RecordVideoUseCase>(relaxed = true)
    private val mockAdjustExposureUseCase = mockk<AdjustExposureUseCase>(relaxed = true)
    private val mockToggleFlashUseCase = mockk<ToggleFlashUseCase>(relaxed = true)
    private val mockSetZoomUseCase = mockk<SetZoomUseCase>(relaxed = true)
    private val mockTapToFocusUseCase = mockk<TapToFocusUseCase>(relaxed = true)
    private val mockSwitchCameraFacingUseCase = mockk<SwitchCameraFacingUseCase>(relaxed = true)
    private val mockGetCameraCapabilitiesUseCase = mockk<GetCameraCapabilitiesUseCase>(relaxed = true)
    private val mockSettingsRepository = mockk<ISettingsRepository>(relaxed = true)
    private val mockMediaRepository = mockk<IMediaRepository>(relaxed = true)
    private val mockSensorRepository = mockk<ISensorRepository>(relaxed = true)

    private val flashModeFlow = MutableStateFlow(FlashMode.AUTO)
    private val gridTypeFlow = MutableStateFlow(GridType.NONE)
    private val captureModeFlow = MutableStateFlow(CaptureMode.PHOTO)
    private val isRawEnabledFlow = MutableStateFlow(false)
    private val latestThumbnailFlow = MutableStateFlow<android.net.Uri?>(null)
    private val sensorOrientationFlow = MutableStateFlow(SensorOrientation())
    private val exposureCompensationFlow = MutableStateFlow(ExposureCompensation())
    private val currentZoomRatioFlow = MutableStateFlow(1.0f)
    private val maxZoomRatioFlow = MutableStateFlow(10.0f)

    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { mockCameraEngine.engineStateFlow } returns MutableStateFlow(CameraEngineState.Ready)
        every { mockSettingsRepository.flashModeFlow } returns flashModeFlow
        every { mockSettingsRepository.gridTypeFlow } returns gridTypeFlow
        every { mockSettingsRepository.captureModeFlow } returns captureModeFlow
        every { mockSettingsRepository.isRawEnabledFlow } returns isRawEnabledFlow
        every { mockMediaRepository.latestMediaThumbnailFlow } returns latestThumbnailFlow
        every { mockSensorRepository.sensorOrientationFlow } returns sensorOrientationFlow
        every { mockAdjustExposureUseCase.exposureCompensationFlow } returns exposureCompensationFlow
        every { mockSetZoomUseCase.currentZoomRatio } returns currentZoomRatioFlow
        every { mockSetZoomUseCase.maxZoomRatio } returns maxZoomRatioFlow

        viewModel = CameraViewModel(
            cameraEngine = mockCameraEngine,
            capturePhotoUseCase = mockCapturePhotoUseCase,
            recordVideoUseCase = mockRecordVideoUseCase,
            adjustExposureUseCase = mockAdjustExposureUseCase,
            toggleFlashUseCase = mockToggleFlashUseCase,
            setZoomUseCase = mockSetZoomUseCase,
            tapToFocusUseCase = mockTapToFocusUseCase,
            switchCameraFacingUseCase = mockSwitchCameraFacingUseCase,
            getCameraCapabilitiesUseCase = mockGetCameraCapabilitiesUseCase,
            settingsRepository = mockSettingsRepository,
            mediaRepository = mockMediaRepository,
            sensorRepository = mockSensorRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun onCaptureModeSelected_updatesStateAndRepository() = runTest(testDispatcher) {
        viewModel.onEvent(CameraUiEvent.OnCaptureModeSelected(CaptureMode.HDR))
        advanceUntilIdle()

        assertEquals(CaptureMode.HDR, viewModel.uiState.value.captureMode)
        verify { mockSettingsRepository.setCaptureMode(CaptureMode.HDR) }
    }

    @Test
    fun onGridTypeChanged_updatesStateAndRepository() = runTest(testDispatcher) {
        viewModel.onEvent(CameraUiEvent.OnGridTypeChanged(GridType.RULE_OF_THIRDS))
        advanceUntilIdle()

        assertEquals(GridType.RULE_OF_THIRDS, viewModel.uiState.value.gridType)
        verify { mockSettingsRepository.setGridType(GridType.RULE_OF_THIRDS) }
    }

    @Test
    fun onSettingsClicked_emitsOpenSettingsEffect() = runTest(testDispatcher) {
        viewModel.uiEffect.test {
            viewModel.onEvent(CameraUiEvent.OnSettingsClicked)
            assertEquals(CameraUiEffect.OpenSettings, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onShutterClicked_executesCaptureAndEmitsVibrate() = runTest(testDispatcher) {
        coEvery { mockCapturePhotoUseCase(any()) } returns flowOf(
            CaptureProgress.Processing(10),
            CaptureProgress.Completed(jpegBytes = byteArrayOf(1, 2))
        )

        viewModel.uiEffect.test {
            viewModel.capturePhoto()
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is CameraUiEffect.Vibrate)
            assertFalse(viewModel.uiState.value.isCapturing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun surfaceLifecycle_attachesAndDetachesCleanly() = runTest(testDispatcher) {
        val mockSurface = mockk<Surface>(relaxed = true)
        viewModel.attachSurface(mockSurface)
        advanceUntilIdle()
        coVerify { mockCameraEngine.attachPreviewSurface(mockSurface) }

        viewModel.detachSurface()
        advanceUntilIdle()
        coVerify { mockCameraEngine.detachPreviewSurface() }
    }

    @Test
    fun touchFocusAndZoom_delegatesToUseCases() = runTest(testDispatcher) {
        val focusPoint = PointF(0.5f, 0.5f)
        viewModel.tapToFocus(focusPoint)
        advanceUntilIdle()
        coVerify { mockTapToFocusUseCase.focusAtPoint(focusPoint) }

        viewModel.setZoom(2.5f)
        advanceUntilIdle()
        coVerify { mockSetZoomUseCase(2.5f) }

        viewModel.adjustExposure(2)
        advanceUntilIdle()
        coVerify { mockAdjustExposureUseCase(2) }
    }
}
