/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.net.Uri
import app.cash.turbine.test
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.LocationCoordinates
import com.hightechif.openkamera.domain.model.PhotoResult
import com.hightechif.openkamera.domain.model.RecordedVideo
import com.hightechif.openkamera.domain.repository.IMediaRepository
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

import androidx.exifinterface.media.ExifInterface
import com.hightechif.openkamera.domain.engine.IImageProcessor
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class MediaStorageRepositoryUnitTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun mediaProcessingWorker_processesJpegTaskAsynchronously() = runTest(testDispatcher) {
        val fakeMediaRepository = FakeMediaRepository()
        val worker = MediaProcessingWorker(
            mediaRepository = fakeMediaRepository,
            workerScope = this,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        val config = CaptureConfig(
            rotationDegrees = 90,
            location = LocationCoordinates(37.7749, -122.4194)
        )
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        var callbackInvoked = false

        val task = MediaSaveTask.SaveJpeg(
            id = "task_1",
            config = config,
            jpegBytes = jpegBytes,
            customFilename = "test_photo",
            onComplete = { result ->
                callbackInvoked = true
                assertTrue(result.isSuccess)
                assertEquals(3L, result.getOrNull()?.fileSizeBytes)
            }
        )

        val job = worker.submitTask(task)
        job.join()

        assertTrue(callbackInvoked)
        assertEquals(0, worker.currentQueueSize)
    }

    @Test
    fun mediaProcessingWorker_queueFlowEmitsCorrectCounts() = runTest(testDispatcher) {
        val fakeMediaRepository = FakeMediaRepository()
        val worker = MediaProcessingWorker(
            mediaRepository = fakeMediaRepository,
            workerScope = this,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher
        )

        worker.pendingTasksCountFlow.test {
            assertEquals(0, awaitItem())

            val task = MediaSaveTask.SaveJpeg(
                id = "task_2",
                config = CaptureConfig(),
                jpegBytes = byteArrayOf(1, 2, 3)
            )

            val job = worker.submitTask(task)
            assertEquals(1, awaitItem())

            job.join()
            assertEquals(0, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun mediaProcessingWorker_processesDeferredHdrTask() = runTest(testDispatcher) {
        val fakeMediaRepository = FakeMediaRepository()
        val fakeProcessor = FakeImageProcessor().apply {
            hdrResult = Result.success(byteArrayOf(10, 20, 30))
        }

        val worker = MediaProcessingWorker(
            mediaRepository = fakeMediaRepository,
            workerScope = this,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            imageProcessor = fakeProcessor
        )

        var completedResult: Result<PhotoResult>? = null
        val job = worker.enqueueDeferredHdr(
            frames = listOf(byteArrayOf(1), byteArrayOf(2)),
            config = CaptureConfig(),
            customFilename = "hdr_photo",
            onComplete = { completedResult = it }
        )
        job.join()

        assertTrue(completedResult?.isSuccess == true)
        assertEquals(3L, completedResult?.getOrNull()?.fileSizeBytes)
    }

    @Test
    fun mediaProcessingWorker_processesDeferredPanoramaTask() = runTest(testDispatcher) {
        val fakeMediaRepository = FakeMediaRepository()
        val fakeProcessor = FakeImageProcessor().apply {
            panoResult = Result.success(byteArrayOf(40, 50, 60, 70))
        }

        val worker = MediaProcessingWorker(
            mediaRepository = fakeMediaRepository,
            workerScope = this,
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            imageProcessor = fakeProcessor
        )

        var completedResult: Result<PhotoResult>? = null
        val job = worker.enqueueDeferredPanorama(
            frames = listOf(byteArrayOf(1), byteArrayOf(2)),
            config = CaptureConfig(),
            customFilename = "pano_photo",
            onComplete = { completedResult = it }
        )
        job.join()

        assertTrue(completedResult?.isSuccess == true)
        assertEquals(4L, completedResult?.getOrNull()?.fileSizeBytes)
    }

    @Test
    fun mediaStorageRepository_emitsLatestThumbnailUriOnSave() = runTest(testDispatcher) {
        val fakeMediaRepository = FakeMediaRepository()
        fakeMediaRepository.latestMediaThumbnailFlow.test {
            assertEquals(null, awaitItem())

            fakeMediaRepository.savePhoto(
                jpegBytes = byteArrayOf(1, 2, 3),
                config = CaptureConfig()
            )

            val updatedUri = awaitItem()
            org.junit.Assert.assertNotNull(updatedUri)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun exifUtils_metadataMapping_handlesRotationAndLocation() {
        val config = CaptureConfig(
            rotationDegrees = 270,
            location = LocationCoordinates(12.34, 56.78, 100.0),
            iso = 400,
            aperture = 1.8f
        )
        assertEquals(270, config.rotationDegrees)
        assertEquals(12.34, config.location?.latitude ?: 0.0, 0.001)
        assertEquals(56.78, config.location?.longitude ?: 0.0, 0.001)
        assertEquals(100.0, config.location?.altitude ?: 0.0, 0.001)
        assertEquals(400, config.iso)
        assertEquals(1.8f, config.aperture ?: 0.0f, 0.001f)

        val mockExif = mockk<ExifInterface>(relaxed = true)
        ExifUtils.writeMetadataToExif(mockExif, config)

        verify {
            mockExif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_270.toString()
            )
        }
        verify {
            mockExif.setAttribute(
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                "400"
            )
        }
        verify {
            mockExif.setAttribute(
                ExifInterface.TAG_F_NUMBER,
                "1.8"
            )
        }
        verify { mockExif.setGpsInfo(any()) }
    }
}

/**
 * In-memory test double for IMediaRepository
 */
class FakeMediaRepository : IMediaRepository {
    private val _latestThumbnail = MutableStateFlow<Uri?>(null)
    override val latestMediaThumbnailFlow: Flow<Uri?> = _latestThumbnail

    override suspend fun savePhoto(
        jpegBytes: ByteArray,
        config: CaptureConfig,
        customFilename: String?
    ): Result<PhotoResult> {
        val mockUri = mockk<Uri>()
        _latestThumbnail.value = mockUri
        return Result.success(
            PhotoResult(
                uri = mockUri,
                filePath = customFilename ?: "IMG_TEST.jpg",
                width = 1920,
                height = 1080,
                fileSizeBytes = jpegBytes.size.toLong(),
                mimeType = "image/jpeg"
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
            PhotoResult(
                uri = mockUri,
                filePath = customFilename ?: "RAW_TEST.dng",
                width = 0,
                height = 0,
                fileSizeBytes = dngBytes.size.toLong(),
                mimeType = "image/x-adobe-dng",
                isRaw = true
            )
        )
    }

    override suspend fun createVideoOutputFile(extension: String): Result<File> {
        return Result.success(withContext(Dispatchers.IO) {
            File.createTempFile("VID_TEST", ".$extension")
        })
    }

    override suspend fun finalizeVideoFile(
        file: File,
        durationMs: Long,
        width: Int,
        height: Int
    ): Result<RecordedVideo> {
        val mockUri = mockk<Uri>()
        return Result.success(
            RecordedVideo(
                uri = mockUri,
                filePath = file.name,
                durationMs = durationMs,
                width = width,
                height = height,
                fileSizeBytes = file.length()
            )
        )
    }

    override suspend fun getLatestMediaUri(): Uri? = _latestThumbnail.value
}

class FakeImageProcessor : IImageProcessor {
    var hdrResult: Result<ByteArray> = Result.success(byteArrayOf(10, 20, 30))
    var panoResult: Result<ByteArray> = Result.success(byteArrayOf(40, 50, 60, 70))

    override suspend fun processHdr(frames: List<ByteArray>): Result<ByteArray> = hdrResult
    override suspend fun processPanorama(frames: List<ByteArray>): Result<ByteArray> = panoResult
    override suspend fun processNoiseReduction(frame: ByteArray): Result<ByteArray> = Result.success(frame)
}
