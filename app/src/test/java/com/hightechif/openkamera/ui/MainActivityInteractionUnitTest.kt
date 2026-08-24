/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import app.cash.turbine.test
import com.hightechif.openkamera.domain.engine.CaptureProgress
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
import io.mockk.every
import io.mockk.mockk
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
class MainActivityInteractionUnitTest {

    private val testDispatcher = StandardTestDispatcher()

    private val mockCapturePhotoUseCase = mockk<CapturePhotoUseCase>(relaxed = true)
    private val mockRecordVideoUseCase = mockk<RecordVideoUseCase>(relaxed = true)
    private val mockAdjustExposureUseCase = mockk<AdjustExposureUseCase>(relaxed = true)
    private val mockToggleFlashUseCase = mockk<ToggleFlashUseCase>(relaxed = true)
    private val mockSetZoomUseCase = mockk<SetZoomUseCase>(relaxed = true)
    private val mockTapToFocusUseCase = mockk<TapToFocusUseCase>(relaxed = true)
    private val mockCameraEngine = mockk<com.hightechif.openkamera.domain.engine.ICameraEngine>(relaxed = true)
    private val mockGetCameraCapabilitiesUseCase = mockk<GetCameraCapabilitiesUseCase>(relaxed = true)
    private val mockSettingsRepository = mockk<ISettingsRepository>(relaxed = true)
    private val mockMediaRepository = mockk<IMediaRepository>(relaxed = true)
    private val mockSensorRepository = mockk<ISensorRepository>(relaxed = true)
    private val testMediaThumbnailFlow = MutableStateFlow<android.net.Uri?>(null)

    private lateinit var switchCameraFacingUseCase: SwitchCameraFacingUseCase
    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { mockSettingsRepository.flashModeFlow } returns MutableStateFlow(FlashMode.AUTO)
        every { mockSettingsRepository.gridTypeFlow } returns MutableStateFlow(GridType.NONE)
        every { mockSettingsRepository.captureModeFlow } returns MutableStateFlow(CaptureMode.PHOTO)
        every { mockSettingsRepository.isRawEnabledFlow } returns MutableStateFlow(false)
        every { mockMediaRepository.latestMediaThumbnailFlow } returns testMediaThumbnailFlow
        every { mockSensorRepository.sensorOrientationFlow } returns MutableStateFlow(SensorOrientation())
        every { mockAdjustExposureUseCase.exposureCompensationFlow } returns MutableStateFlow(ExposureCompensation())
        every { mockSetZoomUseCase.currentZoomRatio } returns MutableStateFlow(1.0f)
        every { mockSetZoomUseCase.maxZoomRatio } returns MutableStateFlow(10.0f)
        coEvery { mockCameraEngine.openCamera(any()) } returns Result.success(Unit)
        coEvery { mockSettingsRepository.getStringPreference("preference_camera_facing", any()) } returns com.hightechif.openkamera.domain.model.CameraFacing.BACK.name

        switchCameraFacingUseCase = SwitchCameraFacingUseCase(mockCameraEngine, mockSettingsRepository)

        viewModel = CameraViewModel(
            capturePhotoUseCase = mockCapturePhotoUseCase,
            recordVideoUseCase = mockRecordVideoUseCase,
            adjustExposureUseCase = mockAdjustExposureUseCase,
            toggleFlashUseCase = mockToggleFlashUseCase,
            setZoomUseCase = mockSetZoomUseCase,
            tapToFocusUseCase = mockTapToFocusUseCase,
            switchCameraFacingUseCase = switchCameraFacingUseCase,
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
    fun shutterClickEvent_triggersCaptureAndVibrates() = runTest(testDispatcher) {
        coEvery { mockCapturePhotoUseCase(any()) } returns flowOf(
            CaptureProgress.Completed(jpegBytes = byteArrayOf(1, 2, 3))
        )

        viewModel.uiEffect.test {
            viewModel.onEvent(CameraUiEvent.OnShutterClicked)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is CameraUiEffect.Vibrate)
            assertFalse(viewModel.uiState.value.isCapturing)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun recordVideoClickEvent_togglesRecordingStateAndEmitsVibration() = runTest(testDispatcher) {
        val tempFile = java.io.File.createTempFile("VID", ".mp4")
        val mockVideo = com.hightechif.openkamera.domain.model.RecordedVideo(
            uri = mockk(),
            filePath = tempFile.name,
            durationMs = 1000L
        )

        coEvery { mockRecordVideoUseCase.startRecording() } returns Result.success(tempFile)
        coEvery { mockRecordVideoUseCase.stopRecording() } returns Result.success(mockVideo)

        viewModel.uiEffect.test {
            // Start recording
            viewModel.onEvent(CameraUiEvent.OnRecordVideoClicked)
            advanceUntilIdle()

            val startEffect = awaitItem()
            assertTrue(startEffect is CameraUiEffect.Vibrate)
            assertTrue(viewModel.uiState.value.isRecording)

            // Stop recording
            viewModel.onEvent(CameraUiEvent.OnRecordVideoClicked)
            advanceUntilIdle()

            val stopEffect = awaitItem()
            assertTrue(stopEffect is CameraUiEffect.ShowToast)
            assertEquals("Video saved", (stopEffect as CameraUiEffect.ShowToast).message)
            assertFalse(viewModel.uiState.value.isRecording)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun switchCameraClickEvent_triggersUseCaseAndUpdatesFacing() = runTest(testDispatcher) {
        viewModel.onEvent(CameraUiEvent.OnSwitchCameraClicked)
        advanceUntilIdle()

        assertEquals(com.hightechif.openkamera.domain.model.CameraFacing.FRONT, viewModel.uiState.value.facing)
    }

    @Test
    fun flashToggleClickEvent_triggersUseCaseAndUpdatesFlashMode() = runTest(testDispatcher) {
        coEvery { mockToggleFlashUseCase() } returns FlashMode.TORCH

        viewModel.onEvent(CameraUiEvent.OnFlashModeToggleClicked)
        advanceUntilIdle()

        assertEquals(FlashMode.TORCH, viewModel.uiState.value.flashMode)
    }

    @Test
    fun zoomChangedEvent_triggersSetZoomUseCase() = runTest(testDispatcher) {
        viewModel.onEvent(CameraUiEvent.OnZoomChanged(3.5f))
        advanceUntilIdle()

        io.mockk.coVerify { mockSetZoomUseCase(3.5f) }
    }

    @Test
    fun tapToFocusEvent_triggersTapToFocusUseCase() = runTest(testDispatcher) {
        val point = android.graphics.PointF(100f, 200f)
        viewModel.onEvent(CameraUiEvent.OnTapToFocus(point))
        advanceUntilIdle()

        io.mockk.coVerify { mockTapToFocusUseCase.focusAtPoint(point) }
    }

    @Test
    fun exposureStepChangedEvent_triggersAdjustExposureUseCase() = runTest(testDispatcher) {
        viewModel.onEvent(CameraUiEvent.OnExposureStepChanged(2))
        advanceUntilIdle()

        io.mockk.coVerify { mockAdjustExposureUseCase(2) }
    }

    @Test
    fun captureModeSelectedEvent_updatesStateAndRepository() = runTest(testDispatcher) {
        viewModel.onEvent(CameraUiEvent.OnCaptureModeSelected(CaptureMode.PANORAMA))
        advanceUntilIdle()

        assertEquals(CaptureMode.PANORAMA, viewModel.uiState.value.captureMode)
        io.mockk.coVerify { mockSettingsRepository.setCaptureMode(CaptureMode.PANORAMA) }
    }

    @Test
    fun galleryThumbnailClicked_emitsNavigateToGalleryEffect() = runTest(testDispatcher) {
        val testUri = mockk<android.net.Uri>()
        testMediaThumbnailFlow.value = testUri
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.onEvent(CameraUiEvent.OnGalleryThumbnailClicked)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is CameraUiEffect.NavigateToGallery)
            assertEquals(testUri, (effect as CameraUiEffect.NavigateToGallery).uri)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun settingsClicked_emitsOpenSettingsEffect() = runTest(testDispatcher) {
        viewModel.uiEffect.test {
            viewModel.onEvent(CameraUiEvent.OnSettingsClicked)
            advanceUntilIdle()

            val effect = awaitItem()
            assertEquals(CameraUiEffect.OpenSettings, effect)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
