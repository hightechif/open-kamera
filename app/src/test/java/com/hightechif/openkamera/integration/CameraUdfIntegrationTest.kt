/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.integration

import android.net.Uri
import app.cash.turbine.test
import com.hightechif.openkamera.cameracontroller.PreviewSurfaceManager
import com.hightechif.openkamera.domain.engine.CaptureProgress
import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.model.CameraFacing
import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.FlashMode
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
import com.hightechif.openkamera.preferences.SettingsRepositoryImpl
import com.hightechif.openkamera.preferences.FakeSharedPreferences
import com.hightechif.openkamera.ui.CameraCommand
import com.hightechif.openkamera.ui.CameraUiEffect
import com.hightechif.openkamera.ui.CameraUiEvent
import com.hightechif.openkamera.ui.CameraViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
class CameraUdfIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var settingsRepository: ISettingsRepository
    private lateinit var fakeMediaRepository: IntegrationFakeMediaRepository
    private lateinit var fakeSensorRepository: IntegrationFakeSensorRepository
    private lateinit var mockCameraEngine: ICameraEngine

    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        fakePrefs = FakeSharedPreferences()
        settingsRepository = SettingsRepositoryImpl(fakePrefs, testDispatcher)
        fakeMediaRepository = IntegrationFakeMediaRepository()
        fakeSensorRepository = IntegrationFakeSensorRepository()
        mockCameraEngine = mockk(relaxed = true)

        val adjustExposureUseCase = AdjustExposureUseCase(mockCameraEngine)
        val toggleFlashUseCase = ToggleFlashUseCase(mockCameraEngine, settingsRepository)
        val setZoomUseCase = SetZoomUseCase(mockCameraEngine)
        val tapToFocusUseCase = TapToFocusUseCase(mockCameraEngine)
        val switchCameraFacingUseCase = SwitchCameraFacingUseCase(mockCameraEngine, settingsRepository)
        val getCameraCapabilitiesUseCase = GetCameraCapabilitiesUseCase(mockCameraEngine)

        viewModel = CameraViewModel(
            cameraEngine = mockCameraEngine,
            adjustExposureUseCase = adjustExposureUseCase,
            toggleFlashUseCase = toggleFlashUseCase,
            setZoomUseCase = setZoomUseCase,
            tapToFocusUseCase = tapToFocusUseCase,
            switchCameraFacingUseCase = switchCameraFacingUseCase,
            getCameraCapabilitiesUseCase = getCameraCapabilitiesUseCase,
            settingsRepository = settingsRepository,
            mediaRepository = fakeMediaRepository,
            sensorRepository = fakeSensorRepository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun shutterIntent_flowsToSingleCommand() = runTest(testDispatcher) {
        viewModel.cameraCommands.test {
            // Intent: shutter -> exactly one command for the legacy executor
            viewModel.onEvent(CameraUiEvent.OnShutterClicked)
            assertEquals(CameraCommand.TakePicture(), awaitItem())
            expectNoEvents()

            // Feedback: legacy callbacks drive state back into the ViewModel
            viewModel.onLegacyCaptureStarted()
            assertEquals(CaptureProgress.Starting, viewModel.captureState.value)
            assertTrue(viewModel.uiState.value.isCapturing)

            viewModel.onLegacyCaptureCompleted()
            assertEquals(CaptureProgress.Idle, viewModel.captureState.value)
            assertFalse(viewModel.uiState.value.isCapturing)
        }
    }

    @Test
    fun fullEndToEndUdfFlow_settingsMutationsAndStateObservation() = runTest(testDispatcher) {
        // Toggle flash
        viewModel.onEvent(CameraUiEvent.OnFlashModeToggleClicked)
        advanceUntilIdle()
        assertEquals(FlashMode.ON, viewModel.uiState.value.flashMode)

        // Select mode
        viewModel.onEvent(CameraUiEvent.OnCaptureModeSelected(CaptureMode.PANORAMA))
        advanceUntilIdle()
        assertEquals(CaptureMode.PANORAMA, viewModel.uiState.value.captureMode)

        // Toggle RAW
        viewModel.onEvent(CameraUiEvent.OnRawToggled(true))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isRawEnabled)
    }
}

class IntegrationFakeMediaRepository : IMediaRepository {
    private val _thumb = MutableStateFlow<Uri?>(null)
    override val latestMediaThumbnailFlow: Flow<Uri?> = _thumb

    override suspend fun getLatestMediaUri(): Uri? = _thumb.value
}

class IntegrationFakeSensorRepository : ISensorRepository {
    private val _orientation = MutableStateFlow(SensorOrientation())
    override val sensorOrientationFlow: Flow<SensorOrientation> = _orientation

    override fun isSupported(): Boolean = true
    override fun startListening() {}
    override fun stopListening() {}
}
