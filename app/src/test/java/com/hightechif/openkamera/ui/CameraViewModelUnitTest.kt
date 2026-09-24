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
import com.hightechif.openkamera.domain.engine.RemoteButton
import com.hightechif.openkamera.remotecontrol.RemoteInputManagerImpl
import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.ExposureCompensation
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.GridType
import com.hightechif.openkamera.domain.model.SensorOrientation
import com.hightechif.openkamera.domain.repository.IMediaRepository
import com.hightechif.openkamera.domain.repository.ISensorRepository
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import com.hightechif.openkamera.domain.usecase.AdjustExposureUseCase
import com.hightechif.openkamera.domain.usecase.GetCameraCapabilitiesUseCase
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelUnitTest {

    private val testDispatcher = StandardTestDispatcher()

    private val mockCameraEngine = mockk<ICameraEngine>(relaxed = true)
    private val mockAdjustExposureUseCase = mockk<AdjustExposureUseCase>(relaxed = true)
    private val mockToggleFlashUseCase = mockk<ToggleFlashUseCase>(relaxed = true)
    private val mockSetZoomUseCase = mockk<SetZoomUseCase>(relaxed = true)
    private val mockTapToFocusUseCase = mockk<TapToFocusUseCase>(relaxed = true)
    private lateinit var mockSwitchCameraFacingUseCase: SwitchCameraFacingUseCase
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
    private val engineStateFlow = MutableStateFlow<CameraEngineState>(CameraEngineState.Ready)

    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { mockCameraEngine.engineStateFlow } returns engineStateFlow
        coEvery { mockCameraEngine.openCamera(any()) } returns Result.success(Unit)
        every { mockSettingsRepository.flashModeFlow } returns flashModeFlow
        every { mockSettingsRepository.gridTypeFlow } returns gridTypeFlow
        every { mockSettingsRepository.captureModeFlow } returns captureModeFlow
        every { mockSettingsRepository.isRawEnabledFlow } returns isRawEnabledFlow
        every { mockSettingsRepository.getStringPreference("preference_camera_facing", any()) } returns com.hightechif.openkamera.domain.model.CameraFacing.BACK.name
        every { mockMediaRepository.latestMediaThumbnailFlow } returns latestThumbnailFlow
        every { mockSensorRepository.sensorOrientationFlow } returns sensorOrientationFlow
        every { mockAdjustExposureUseCase.exposureCompensationFlow } returns exposureCompensationFlow
        every { mockSetZoomUseCase.currentZoomRatio } returns currentZoomRatioFlow
        every { mockSetZoomUseCase.maxZoomRatio } returns maxZoomRatioFlow

        mockSwitchCameraFacingUseCase = SwitchCameraFacingUseCase(mockCameraEngine, mockSettingsRepository)

        viewModel = CameraViewModel(
            cameraEngine = mockCameraEngine,
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
    fun onShutterClicked_emitsSingleTakePictureCommand() = runTest(testDispatcher) {
        viewModel.cameraCommands.test {
            viewModel.onEvent(CameraUiEvent.OnShutterClicked)
            assertEquals(CameraCommand.TakePicture(), awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun shutterKeyAndAudioTrigger_emitTakePictureCommand() = runTest(testDispatcher) {
        viewModel.cameraCommands.test {
            viewModel.onEvent(CameraUiEvent.OnShutterKeyPressed)
            assertEquals(CameraCommand.TakePicture(), awaitItem())
            viewModel.onEvent(CameraUiEvent.OnAudioTrigger)
            assertEquals(CameraCommand.TakePicture(), awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun shutterWhileBusy_stillEmitsCommand() = runTest(testDispatcher) {
        viewModel.onLegacyCaptureStarted()
        assertTrue(viewModel.uiState.value.isCapturing)

        viewModel.cameraCommands.test {
            viewModel.onEvent(CameraUiEvent.OnShutterClicked)
            assertEquals(CameraCommand.TakePicture(), awaitItem())
        }
    }

    @Test
    fun videoSnapshot_emitsSnapshotCommand() = runTest(testDispatcher) {
        viewModel.cameraCommands.test {
            viewModel.onEvent(CameraUiEvent.OnVideoSnapshotClicked)
            assertEquals(CameraCommand.TakePicture(photoSnapshot = true), awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun continuousBurst_emitsBurstCommand() = runTest(testDispatcher) {
        viewModel.cameraCommands.test {
            viewModel.onEvent(CameraUiEvent.OnContinuousBurstRequested)
            assertEquals(CameraCommand.TakePicture(continuousFastBurst = true), awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun commandsEmittedWithoutCollector_areNotReplayed() = runTest(testDispatcher) {
        viewModel.onEvent(CameraUiEvent.OnShutterClicked)

        viewModel.cameraCommands.test {
            expectNoEvents()
        }
    }

    @Test
    fun legacyCallbacks_driveCaptureState() = runTest(testDispatcher) {
        assertEquals(CaptureProgress.Idle, viewModel.captureState.value)

        viewModel.onLegacyCaptureStarted()
        assertEquals(CaptureProgress.Starting, viewModel.captureState.value)
        assertTrue(viewModel.uiState.value.isCapturing)

        viewModel.onLegacyCaptureCompleted()
        assertEquals(CaptureProgress.Idle, viewModel.captureState.value)
        assertFalse(viewModel.uiState.value.isCapturing)
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

    @Test
    fun videoRecording_pauseAndResume_emitsPauseResumeCommands() = runTest(testDispatcher) {
        viewModel.cameraCommands.test {
            viewModel.onEvent(CameraUiEvent.OnPauseVideoRecordingClicked)
            assertEquals(CameraCommand.PauseResumeVideo, awaitItem())
            viewModel.onEvent(CameraUiEvent.OnResumeVideoRecordingClicked)
            assertEquals(CameraCommand.PauseResumeVideo, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun videoRecording_flashToggle_cyclesTorchModeDuringRecording() = runTest(testDispatcher) {
        coEvery { mockToggleFlashUseCase(FlashMode.TORCH) } returns FlashMode.TORCH
        coEvery { mockToggleFlashUseCase(FlashMode.OFF) } returns FlashMode.OFF

        // The recording timer never goes idle, so use runCurrent() (not advanceUntilIdle())
        viewModel.onLegacyVideoStarted()
        testScheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isRecording)

        // Toggling flash during recording should target TORCH
        viewModel.onEvent(CameraUiEvent.OnFlashModeToggleClicked)
        testScheduler.runCurrent()
        assertEquals(FlashMode.TORCH, viewModel.uiState.value.flashMode)
        coVerify { mockToggleFlashUseCase(FlashMode.TORCH) }

        // Toggling flash again during recording should target OFF
        viewModel.onEvent(CameraUiEvent.OnFlashModeToggleClicked)
        testScheduler.runCurrent()
        assertEquals(FlashMode.OFF, viewModel.uiState.value.flashMode)
        coVerify { mockToggleFlashUseCase(FlashMode.OFF) }

        // Cancel the endless timer so the test can finish
        viewModel.onLegacyVideoStopped()
    }

    @Test
    fun legacyVideoCallbacks_driveRecordingState() = runTest(testDispatcher) {
        viewModel.onLegacyVideoStarted()
        testScheduler.runCurrent()

        assertTrue(viewModel.uiState.value.isRecording)
        assertEquals(0L, viewModel.uiState.value.recordingDurationSeconds)

        advanceTimeBy(3000.milliseconds)
        testScheduler.runCurrent()
        assertEquals(3L, viewModel.uiState.value.recordingDurationSeconds)

        viewModel.onLegacyVideoPaused(true)
        assertTrue(viewModel.uiState.value.isVideoPaused)
        advanceTimeBy(2000.milliseconds)
        testScheduler.runCurrent()
        assertEquals(3L, viewModel.uiState.value.recordingDurationSeconds)

        viewModel.onLegacyVideoStopped()
        assertFalse(viewModel.uiState.value.isRecording)
        assertFalse(viewModel.uiState.value.isVideoPaused)
        assertEquals(0L, viewModel.uiState.value.recordingDurationSeconds)
    }

    @Test
    fun remoteInputManager_eachButton_emitsExactlyOneExpectedCommand() = runTest(testDispatcher) {
        val remoteInputManager = RemoteInputManagerImpl()
        val customViewModel = CameraViewModel(
            cameraEngine = mockCameraEngine,
            adjustExposureUseCase = mockAdjustExposureUseCase,
            toggleFlashUseCase = mockToggleFlashUseCase,
            setZoomUseCase = mockSetZoomUseCase,
            tapToFocusUseCase = mockTapToFocusUseCase,
            switchCameraFacingUseCase = mockSwitchCameraFacingUseCase,
            getCameraCapabilitiesUseCase = mockGetCameraCapabilitiesUseCase,
            settingsRepository = mockSettingsRepository,
            mediaRepository = mockMediaRepository,
            sensorRepository = mockSensorRepository,
            remoteInputManager = remoteInputManager
        )
        testScheduler.runCurrent()
        val stateBefore = customViewModel.uiState.value

        val expected = mapOf(
            RemoteButton.SHUTTER to CameraCommand.RemoteShutter,
            RemoteButton.MODE to CameraCommand.RemoteButton(RemoteButton.MODE),
            RemoteButton.MENU to CameraCommand.RemoteButton(RemoteButton.MENU),
            RemoteButton.UP to CameraCommand.RemoteButton(RemoteButton.UP),
            RemoteButton.DOWN to CameraCommand.RemoteButton(RemoteButton.DOWN),
            RemoteButton.AFMF to CameraCommand.RemoteButton(RemoteButton.AFMF)
        )
        customViewModel.cameraCommands.test {
            expected.forEach { (button, command) ->
                remoteInputManager.dispatchInputEvent(button)
                testScheduler.runCurrent()
                assertEquals("button $button", command, awaitItem())
            }
            expectNoEvents()
        }

        assertEquals(stateBefore.zoomRatio, customViewModel.uiState.value.zoomRatio)
        assertEquals(stateBefore.facing, customViewModel.uiState.value.facing)
    }

    @Test
    fun recordVideoClickAndToggleRecording_emitLegacyCommand() = runTest(testDispatcher) {
        viewModel.cameraCommands.test {
            viewModel.onEvent(CameraUiEvent.OnRecordVideoClicked)
            assertEquals(CameraCommand.TakePicture(), awaitItem())
            viewModel.toggleRecording()
            assertEquals(CameraCommand.TakePicture(), awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun switchCameraFacing_invokesSwitchCameraFacingUseCase() = runTest(testDispatcher) {
        viewModel.switchCameraFacing()
        testScheduler.runCurrent()

        coVerify(atLeast = 1) { mockCameraEngine.openCamera(com.hightechif.openkamera.domain.model.CameraFacing.FRONT) }
    }
}
