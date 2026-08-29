/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.model

import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureModelsTest {

    @Test
    fun captureConfig_defaultValues_areValid() {
        val config = CaptureConfig()
        assertEquals(CaptureMode.PHOTO, config.captureMode)
        assertEquals(FlashMode.AUTO, config.flashMode)
        assertEquals(90, config.jpegQuality)
        assertFalse(config.enableRaw)
        assertTrue(config.burstExposures.isEmpty())
        assertEquals(0, config.rotationDegrees)
    }

    @Test
    fun photoResult_properties_holdValuesProperly() {
        val mockUri = mockk<Uri>()
        val result = PhotoResult(
            uri = mockUri,
            filePath = "/storage/emulated/0/DCIM/IMG_2026.jpg",
            width = 4000,
            height = 3000,
            fileSizeBytes = 4096000L,
            mimeType = "image/jpeg",
            isRaw = false
        )

        assertEquals(mockUri, result.uri)
        assertEquals(4000, result.width)
        assertEquals(3000, result.height)
        assertEquals(4096000L, result.fileSizeBytes)
        assertFalse(result.isRaw)
    }

    @Test
    fun recordedVideo_properties_holdValuesProperly() {
        val mockUri = mockk<Uri>()
        val video = RecordedVideo(
            uri = mockUri,
            durationMs = 15000L,
            width = 1920,
            height = 1080,
            fileSizeBytes = 20000000L
        )

        assertEquals(mockUri, video.uri)
        assertEquals(15000L, video.durationMs)
        assertEquals(1920, video.width)
        assertEquals(1080, video.height)
    }
}
