/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import com.hightechif.openkamera.audio.AudioListener
import com.hightechif.openkamera.domain.model.VideoCodecPreset
import com.hightechif.openkamera.domain.model.VideoProfileConfig
import com.hightechif.openkamera.domain.model.VideoResolutionPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProfileUnitTest {

    @Test
    fun videoProfileConfig_defaultValues() {
        val config = VideoProfileConfig()

        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
        assertEquals(30, config.frameRate)
        assertEquals(20_000_000, config.bitRate)
        assertEquals(44100, config.audioSampleRate)
        assertEquals(128000, config.audioBitRate)
        assertEquals(2, config.audioChannels)
        assertFalse(config.isHighSpeed)
    }

    @Test
    fun videoResolutionPreset_fromDimensions() {
        assertEquals(VideoResolutionPreset.RES_4K_UHD, VideoResolutionPreset.fromDimensions(3840, 2160))
        assertEquals(VideoResolutionPreset.RES_1080P_FHD, VideoResolutionPreset.fromDimensions(1920, 1080))
        assertEquals(VideoResolutionPreset.RES_720P_HD, VideoResolutionPreset.fromDimensions(1280, 720))
        assertEquals(VideoResolutionPreset.RES_480P_SD, VideoResolutionPreset.fromDimensions(720, 480))
        assertEquals(VideoResolutionPreset.RES_CUSTOM, VideoResolutionPreset.fromDimensions(1000, 500))
    }

    @Test
    fun videoProfile_conversionToAndFromConfig() {
        val originalConfig = VideoProfileConfig(
            width = 3840,
            height = 2160,
            frameRate = 60,
            bitRate = 40_000_000,
            audioSampleRate = 48000,
            audioBitRate = 256000,
            audioChannels = 2,
            isHighSpeed = true,
            captureRateFactor = 2.0f
        )

        val profile = VideoProfile.fromConfig(originalConfig)
        assertEquals(3840, profile.videoFrameWidth)
        assertEquals(2160, profile.videoFrameHeight)
        assertEquals(60, profile.videoFrameRate)
        assertEquals(40_000_000, profile.videoBitRate)
        assertEquals(48000, profile.audioSampleRate)

        val convertedConfig = profile.toVideoProfileConfig()
        assertEquals(3840, convertedConfig.width)
        assertEquals(2160, convertedConfig.height)
        assertEquals(60, convertedConfig.frameRate)
        assertEquals(40_000_000, convertedConfig.bitRate)
        assertTrue(convertedConfig.isHighSpeed)
    }

    @Test
    fun audioListener_calculateAmplitudeData_silentBuffer() {
        val silentBuffer = ShortArray(100) { 0 }
        val amplitude = AudioListener.calculateAmplitudeData(silentBuffer, 100)

        assertEquals(0.0, amplitude.currentRms, 0.001)
        assertEquals(-90.0f, amplitude.peakDecibels, 0.001f)
        assertFalse(amplitude.isClipped)
    }

    @Test
    fun audioListener_calculateAmplitudeData_fullScaleClippedBuffer() {
        val fullScaleBuffer = ShortArray(100) { 32767 }
        val amplitude = AudioListener.calculateAmplitudeData(fullScaleBuffer, 100)

        assertEquals(32767.0, amplitude.currentRms, 1.0)
        assertEquals(0.0f, amplitude.peakDecibels, 0.1f)
        assertTrue(amplitude.isClipped)
    }

    @Test
    fun videoCodecPreset_mimeTypeMapping() {
        assertEquals("video/avc", VideoCodecPreset.H264.mimeType)
        assertEquals("video/hevc", VideoCodecPreset.HEVC_H265.mimeType)
    }
}
