/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.usecase

import android.net.Uri
import app.cash.turbine.test
import com.hightechif.openkamera.domain.engine.CaptureProgress
import com.hightechif.openkamera.domain.engine.IAudioController
import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.engine.IImageProcessor
import com.hightechif.openkamera.domain.model.CameraFacing
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.PhotoResult
import com.hightechif.openkamera.domain.model.RecordedVideo
import com.hightechif.openkamera.domain.repository.ILocationRepository
import com.hightechif.openkamera.domain.repository.IMediaRepository
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DomainUseCasesUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private val mockCameraEngine = mockk<ICameraEngine>(relaxed = true)
    private val mockLocationRepository = mockk<ILocationRepository>(relaxed = true)
    private val mockSettingsRepository = mockk<ISettingsRepository>(relaxed = true)
    private val mockAudioController = mockk<IAudioController>(relaxed = true)
    private val fakeImageProcessor = FakeTestImageProcessor()
    private val fakeMediaRepository = FakeTestMediaRepository()

    @Before
    fun setUp() {
        every { mockSettingsRepository.getBooleanPreference(any(), any()) } returns false
        every { mockSettingsRepository.isRawEnabled() } returns false
    }

    @Test
    fun capturePhotoUseCase_playsShutter_andSavesMedia() = runTest(testDispatcher) {
        val useCase = CapturePhotoUseCase(
            cameraEngine = mockCameraEngine,
            imageProcessor = fakeImageProcessor,
            mediaRepository = fakeMediaRepository,
            locationRepository = mockLocationRepository,
            settingsRepository = mockSettingsRepository,
            audioController = mockAudioController
        )

        val sampleJpeg = byteArrayOf(1, 2, 3)
        coEvery { mockCameraEngine.captureStillImage(any()) } returns flowOf(
            CaptureProgress.Completed(jpegBytes = sampleJpeg)
        )

        useCase(CaptureConfig()).test {
            assertTrue(awaitItem() is CaptureProgress.Processing)
            val completed = awaitItem()
            assertTrue(completed is CaptureProgress.Completed)
            awaitComplete()
        }

        verify { mockAudioController.playShutterSound() }
        assertTrue(fakeMediaRepository.savePhotoCalled)
    }

    @Test
    fun recordVideoUseCase_startAndStopLifecycle() = runTest(testDispatcher) {
        val useCase = RecordVideoUseCase(
            cameraEngine = mockCameraEngine,
            mediaRepository = fakeMediaRepository
        )

        coEvery { mockCameraEngine.startVideoRecording(any()) } returns Result.success(Unit)
        coEvery { mockCameraEngine.stopVideoRecording() } returns Result.success(Unit)

        val startResult = useCase.startRecording()
        assertTrue(startResult.isSuccess)

        val stopResult = useCase.stopRecording(1920, 1080)
        assertTrue(stopResult.isSuccess)
        assertTrue(fakeMediaRepository.finalizeVideoCalled)
    }

    @Test
    fun processHdrUseCase_mergesFramesAndSaves() = runTest(testDispatcher) {
        val useCase = ProcessHdrUseCase(
            imageProcessor = fakeImageProcessor,
            mediaRepository = fakeMediaRepository
        )

        val frame1 = byteArrayOf(1, 2)
        val frame2 = byteArrayOf(3, 4)

        val result = useCase(listOf(frame1, frame2), CaptureConfig())
        assertTrue(result.isSuccess)
        assertTrue(fakeImageProcessor.hdrProcessed)
        assertTrue(fakeMediaRepository.savePhotoCalled)
    }

    @Test
    fun toggleFlashUseCase_cyclesFlashModes() = runTest(testDispatcher) {
        val useCase = ToggleFlashUseCase(
            cameraEngine = mockCameraEngine,
            settingsRepository = mockSettingsRepository
        )

        every { mockSettingsRepository.getFlashMode() } returns FlashMode.AUTO

        val nextMode = useCase()
        assertEquals(FlashMode.ON, nextMode)
        coVerify { mockCameraEngine.setFlashMode(FlashMode.ON) }
        verify { mockSettingsRepository.setFlashMode(FlashMode.ON) }
    }

    @Test
    fun switchCameraFacingUseCase_switchesFacingSuccessfully() = runTest(testDispatcher) {
        val useCase = SwitchCameraFacingUseCase(
            cameraEngine = mockCameraEngine,
            settingsRepository = mockSettingsRepository
        )

        every { mockSettingsRepository.getStringPreference("preference_camera_facing", any()) } returns CameraFacing.BACK.name
        coEvery { mockCameraEngine.openCamera(CameraFacing.FRONT) } returns Result.success(Unit)

        val result = useCase()
        assertTrue(result.isSuccess)
        assertEquals(CameraFacing.FRONT, result.getOrNull())
        verify { mockSettingsRepository.setStringPreference("preference_camera_facing", CameraFacing.FRONT.name) }
    }
}

class FakeTestImageProcessor : IImageProcessor {
    var hdrProcessed = false

    override suspend fun processHdr(frames: List<ByteArray>): Result<ByteArray> {
        hdrProcessed = true
        return Result.success(byteArrayOf(9, 9, 9))
    }

    override suspend fun processPanorama(frames: List<ByteArray>): Result<ByteArray> {
        return Result.success(byteArrayOf(8, 8, 8))
    }

    override suspend fun processNoiseReduction(frame: ByteArray): Result<ByteArray> {
        return Result.success(frame)
    }
}

class FakeTestMediaRepository : IMediaRepository {
    var savePhotoCalled = false
    var finalizeVideoCalled = false
    private val _thumbFlow = MutableStateFlow<Uri?>(null)
    override val latestMediaThumbnailFlow: Flow<Uri?> = _thumbFlow

    override suspend fun savePhoto(
        jpegBytes: ByteArray,
        config: CaptureConfig,
        customFilename: String?
    ): Result<PhotoResult> {
        savePhotoCalled = true
        val mockUri = mockk<Uri>()
        return Result.success(
            PhotoResult(
                uri = mockUri,
                filePath = customFilename ?: "TEST.jpg",
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
        return Result.success(
            PhotoResult(uri = mockUri, filePath = customFilename ?: "RAW.dng", isRaw = true)
        )
    }

    override suspend fun createVideoOutputFile(extension: String): Result<File> {
        return Result.success(withContext(Dispatchers.IO) {
            File.createTempFile("VID_FAKE", ".$extension")
        })
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

    override suspend fun getLatestMediaUri(): Uri? = _thumbFlow.value
}
