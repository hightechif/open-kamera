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
import com.hightechif.openkamera.domain.engine.IAudioController
import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.engine.IImageProcessor
import com.hightechif.openkamera.domain.model.CameraFacing
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.PhotoResult
import com.hightechif.openkamera.domain.model.RecordedVideo
import com.hightechif.openkamera.domain.model.SensorOrientation
import com.hightechif.openkamera.domain.repository.ILocationRepository
import com.hightechif.openkamera.domain.repository.IMediaRepository
import com.hightechif.openkamera.domain.repository.ISensorRepository
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import com.hightechif.openkamera.domain.usecase.AdjustExposureUseCase
import com.hightechif.openkamera.domain.usecase.CapturePhotoUseCase
import com.hightechif.openkamera.domain.usecase.GetCameraCapabilitiesUseCase
import com.hightechif.openkamera.domain.usecase.ProcessHdrUseCase
import com.hightechif.openkamera.domain.usecase.ProcessPanoramaUseCase
import com.hightechif.openkamera.domain.usecase.RecordVideoUseCase
import com.hightechif.openkamera.domain.usecase.SetZoomUseCase
import com.hightechif.openkamera.domain.usecase.SwitchCameraFacingUseCase
import com.hightechif.openkamera.domain.usecase.TapToFocusUseCase
import com.hightechif.openkamera.domain.usecase.ToggleFlashUseCase
import com.hightechif.openkamera.preferences.SettingsRepositoryImpl
import com.hightechif.openkamera.preferences.FakeSharedPreferences
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CameraUdfIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var settingsRepository: ISettingsRepository
    private lateinit var fakeMediaRepository: IntegrationFakeMediaRepository
    private lateinit var fakeSensorRepository: IntegrationFakeSensorRepository
    private lateinit var mockCameraEngine: ICameraEngine
    private lateinit var mockLocationRepository: ILocationRepository
    private lateinit var mockAudioController: IAudioController
    private lateinit var fakeImageProcessor: IntegrationFakeImageProcessor

    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        fakePrefs = FakeSharedPreferences()
        settingsRepository = SettingsRepositoryImpl(fakePrefs, testDispatcher)
        fakeMediaRepository = IntegrationFakeMediaRepository()
        fakeSensorRepository = IntegrationFakeSensorRepository()
        fakeImageProcessor = IntegrationFakeImageProcessor()
        mockCameraEngine = mockk(relaxed = true)
        mockLocationRepository = mockk(relaxed = true)
        mockAudioController = mockk(relaxed = true)

        val capturePhotoUseCase = CapturePhotoUseCase(
            cameraEngine = mockCameraEngine,
            imageProcessor = fakeImageProcessor,
            mediaRepository = fakeMediaRepository,
            locationRepository = mockLocationRepository,
            settingsRepository = settingsRepository,
            audioController = mockAudioController
        )

        val recordVideoUseCase = RecordVideoUseCase(
            cameraEngine = mockCameraEngine,
            mediaRepository = fakeMediaRepository
        )

        val adjustExposureUseCase = AdjustExposureUseCase(mockCameraEngine)
        val toggleFlashUseCase = ToggleFlashUseCase(mockCameraEngine, settingsRepository)
        val setZoomUseCase = SetZoomUseCase(mockCameraEngine)
        val tapToFocusUseCase = TapToFocusUseCase(mockCameraEngine)
        val switchCameraFacingUseCase = SwitchCameraFacingUseCase(mockCameraEngine, settingsRepository)
        val getCameraCapabilitiesUseCase = GetCameraCapabilitiesUseCase(mockCameraEngine)

        viewModel = CameraViewModel(
            cameraEngine = mockCameraEngine,
            capturePhotoUseCase = capturePhotoUseCase,
            recordVideoUseCase = recordVideoUseCase,
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
    fun fullEndToEndUdfFlow_shutterCaptureAndMediaPersistence() = runTest(testDispatcher) {
        val sampleJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        coEvery { mockCameraEngine.captureStillImage(any()) } returns flow {
            emit(CaptureProgress.Processing(20))
            emit(CaptureProgress.Completed(jpegBytes = sampleJpeg))
        }

        viewModel.uiEffect.test {
            // Trigger shutter
            viewModel.onEvent(CameraUiEvent.OnShutterClicked)
            advanceUntilIdle()

            // Verify side-effect
            val effect = awaitItem()
            assertTrue(effect is CameraUiEffect.Vibrate)

            // Verify media saved
            assertTrue(fakeMediaRepository.savePhotoCalled)
            assertEquals(sampleJpeg.size.toLong(), fakeMediaRepository.savedPhotoBytes?.size?.toLong())

            // Verify state reset
            assertFalse(viewModel.uiState.value.isCapturing)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fullEndToEndUdfFlow_videoRecordingLifecycle() = runTest(testDispatcher) {
        coEvery { mockCameraEngine.startVideoRecording(any()) } returns Result.success(Unit)
        coEvery { mockCameraEngine.stopVideoRecording() } returns Result.success(Unit)

        viewModel.uiEffect.test {
            // Start recording
            viewModel.onEvent(CameraUiEvent.OnRecordVideoClicked)
            advanceUntilIdle()

            val vibrateEffect = awaitItem()
            assertTrue(vibrateEffect is CameraUiEffect.Vibrate)
            assertTrue(viewModel.uiState.value.isRecording)

            // Stop recording
            viewModel.onEvent(CameraUiEvent.OnRecordVideoClicked)
            advanceUntilIdle()

            val toastEffect = awaitItem()
            assertTrue(toastEffect is CameraUiEffect.ShowToast)
            assertEquals("Video saved", (toastEffect as CameraUiEffect.ShowToast).message)
            assertFalse(viewModel.uiState.value.isRecording)
            assertTrue(fakeMediaRepository.finalizeVideoCalled)

            cancelAndIgnoreRemainingEvents()
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
    var savePhotoCalled = false
    var savedPhotoBytes: ByteArray? = null
    var finalizeVideoCalled = false

    private val _thumb = MutableStateFlow<Uri?>(null)
    override val latestMediaThumbnailFlow: Flow<Uri?> = _thumb

    override suspend fun savePhoto(
        jpegBytes: ByteArray,
        config: CaptureConfig,
        customFilename: String?
    ): Result<PhotoResult> {
        savePhotoCalled = true
        savedPhotoBytes = jpegBytes
        val mockUri = mockk<Uri>()
        _thumb.value = mockUri
        return Result.success(
            PhotoResult(
                uri = mockUri,
                filePath = customFilename ?: "IMG_UDF.jpg",
                fileSizeBytes = jpegBytes.size.toLong()
            )
        )
    }

    override suspend fun saveRawDng(
        dngBytes: ByteArray,
        config: CaptureConfig,
        customFilename: String?
    ): Result<PhotoResult> {
        val mockUri = mockk<Uri>()
        return Result.success(PhotoResult(uri = mockUri, isRaw = true))
    }

    override suspend fun createVideoOutputFile(extension: String): Result<File> {
        return Result.success(File.createTempFile("VID_UDF", ".$extension"))
    }

    override suspend fun finalizeVideoFile(
        file: File,
        durationMs: Long,
        width: Int,
        height: Int
    ): Result<RecordedVideo> {
        finalizeVideoCalled = true
        val mockUri = mockk<Uri>()
        return Result.success(
            RecordedVideo(uri = mockUri, filePath = file.name, durationMs = durationMs)
        )
    }

    override suspend fun getLatestMediaUri(): Uri? = _thumb.value
}

class IntegrationFakeSensorRepository : ISensorRepository {
    private val _orientation = MutableStateFlow(SensorOrientation())
    override val sensorOrientationFlow: Flow<SensorOrientation> = _orientation

    override fun isSupported(): Boolean = true
    override fun startListening() {}
    override fun stopListening() {}
}

class IntegrationFakeImageProcessor : IImageProcessor {
    override suspend fun processHdr(frames: List<ByteArray>): Result<ByteArray> = Result.success(byteArrayOf(1, 2, 3))
    override suspend fun processPanorama(frames: List<ByteArray>): Result<ByteArray> = Result.success(byteArrayOf(4, 5, 6))
    override suspend fun processNoiseReduction(frame: ByteArray): Result<ByteArray> = Result.success(frame)
}
