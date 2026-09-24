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
import com.hightechif.openkamera.domain.repository.IMediaRepository
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

import androidx.exifinterface.media.ExifInterface
import io.mockk.verify

@OptIn(ExperimentalCoroutinesApi::class)
class MediaStorageRepositoryUnitTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun getLatestMediaUri_emitsOnThumbnailFlow() = runTest(testDispatcher) {
        val fakeMediaRepository = FakeMediaRepository()
        fakeMediaRepository.latestMediaThumbnailFlow.test {
            assertEquals(null, awaitItem())
            assertNull(fakeMediaRepository.getLatestMediaUri())

            val mockUri = mockk<Uri>()
            fakeMediaRepository.setLatest(mockUri)

            assertEquals(mockUri, awaitItem())
            assertEquals(mockUri, fakeMediaRepository.getLatestMediaUri())
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

    fun setLatest(uri: Uri?) {
        _latestThumbnail.value = uri
    }

    override suspend fun getLatestMediaUri(): Uri? = _latestThumbnail.value
}
