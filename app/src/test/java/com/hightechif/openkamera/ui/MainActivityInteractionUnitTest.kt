/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.net.Uri
import app.cash.turbine.test
import com.hightechif.openkamera.domain.engine.CameraEngineState
import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.model.CameraFacing
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityInteractionUnitTest {

    private val testDispatcher = StandardTestDispatcher()

    private val mockCameraEngine = mockk<ICameraEngine>(relaxed = true)
    private val mockCapturePhotoUseCase = mockk<CapturePhotoUseCase>(relaxed = true)
    private val mockRecordVideoUseCase = mockk<RecordVideoUseCase>(relaxed = true)
    private val mockAdjustExposureUseCase = mockk<AdjustExposureUseCase>(relaxed = true)
    private val mockToggleFlashUseCase = mockk<ToggleFlashUseCase>(relaxed = true)
    private val mockSetZoomUseCase = mockk<SetZoomUseCase>(relaxed = true)
    private val mockTapToFocusUseCase = mockk<TapToFocusUseCase>(relaxed = true)
    private val mockGetCameraCapabilitiesUseCase =
        mockk<GetCameraCapabilitiesUseCase>(relaxed = true)
    private val mockSettingsRepository = mockk<ISettingsRepository>(relaxed = true)
    private val mockMediaRepository = mockk<IMediaRepository>(relaxed = true)
    private val mockSensorRepository = mockk<ISensorRepository>(relaxed = true)
    private val testMediaThumbnailFlow = MutableStateFlow<Uri?>(null)

    private lateinit var switchCameraFacingUseCase: SwitchCameraFacingUseCase
    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { mockCameraEngine.engineStateFlow } returns MutableStateFlow(CameraEngineState.Ready)
        every { mockSettingsRepository.flashModeFlow } returns MutableStateFlow(FlashMode.AUTO)
        every { mockSettingsRepository.gridTypeFlow } returns MutableStateFlow(GridType.NONE)
        every { mockSettingsRepository.captureModeFlow } returns MutableStateFlow(CaptureMode.PHOTO)
        every { mockSettingsRepository.isRawEnabledFlow } returns MutableStateFlow(false)
        every { mockMediaRepository.latestMediaThumbnailFlow } returns testMediaThumbnailFlow
        every { mockSensorRepository.sensorOrientationFlow } returns MutableStateFlow(
            SensorOrientation()
        )
        every { mockAdjustExposureUseCase.exposureCompensationFlow } returns MutableStateFlow(
            ExposureCompensation()
        )
        every { mockSetZoomUseCase.currentZoomRatio } returns MutableStateFlow(1.0f)
        every { mockSetZoomUseCase.maxZoomRatio } returns MutableStateFlow(10.0f)
        coEvery { mockCameraEngine.openCamera(any()) } returns Result.success(Unit)
        every {
            mockSettingsRepository.getStringPreference(
                "preference_camera_facing",
                any()
            )
        } returns CameraFacing.BACK.name

        switchCameraFacingUseCase =
            SwitchCameraFacingUseCase(mockCameraEngine, mockSettingsRepository)

        viewModel = CameraViewModel(
            cameraEngine = mockCameraEngine,
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
    fun galleryThumbnailState_updatesWhenMediaRepositoryEmits() = runTest(testDispatcher) {
        viewModel.uiState.test {
            val initialState = awaitItem()
            assertEquals(null, initialState.latestThumbnailUri)

            val mockUri = mockk<Uri>()
            testMediaThumbnailFlow.value = mockUri
            advanceUntilIdle()

            val updatedState = awaitItem()
            assertEquals(mockUri, updatedState.latestThumbnailUri)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun galleryThumbnailClicked_withUri_emitsNavigateEffect() = runTest(testDispatcher) {
        val mockUri = mockk<Uri>()
        testMediaThumbnailFlow.value = mockUri
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.onEvent(CameraUiEvent.OnGalleryThumbnailClicked)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is CameraUiEffect.NavigateToGallery)
            assertEquals(mockUri, (effect as CameraUiEffect.NavigateToGallery).uri)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun galleryThumbnailClicked_withoutUri_emitsToastEffect() = runTest(testDispatcher) {
        testMediaThumbnailFlow.value = null
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.onEvent(CameraUiEvent.OnGalleryThumbnailClicked)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is CameraUiEffect.ShowToast)
            assertEquals("No photos or videos yet", (effect as CameraUiEffect.ShowToast).message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun switchCameraClicked_updatesFacingInState() = runTest(testDispatcher) {
        viewModel.onEvent(CameraUiEvent.OnSwitchCameraClicked)
        advanceUntilIdle()

        assertEquals(
            CameraFacing.FRONT,
            viewModel.uiState.value.facing
        )
    }

    @Test
    fun recordVideoClicked_togglesRecordingState() = runTest(testDispatcher) {
        val mockFile = mockk<File>(relaxed = true)
        coEvery { mockRecordVideoUseCase.startRecording() } returns Result.success(mockFile)
        coEvery { mockRecordVideoUseCase.stopRecording(any(), any()) } returns Result.success(
            mockk(
                relaxed = true
            )
        )

        viewModel.toggleVideoRecording()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isRecording)

        viewModel.toggleVideoRecording()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRecording)
    }
}
