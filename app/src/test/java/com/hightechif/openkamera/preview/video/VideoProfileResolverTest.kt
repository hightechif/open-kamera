/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.video

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoProfileResolverTest {

    @Test
    fun testFileExtensionResolution() {
        assertEquals("mp4", VideoProfileResolver.getFileExtension(MediaRecorder.OutputFormat.MPEG_4))
        assertEquals("webm", VideoProfileResolver.getFileExtension(MediaRecorder.OutputFormat.WEBM))
        assertEquals("3gp", VideoProfileResolver.getFileExtension(MediaRecorder.OutputFormat.THREE_GPP))
    }

    @Test
    fun testCustomBitrateAndFpsOverrides() {
        val resolver = VideoProfileResolver()
        val profile = resolver.resolveProfile(
            cameraId = 0,
            qualityString = "4_r3840x2160",
            customBitrate = 50000000,
            customFps = 60,
            captureRate = 60.0
        )

        assertEquals(3840, profile.videoFrameWidth)
        assertEquals(2160, profile.videoFrameHeight)
        assertEquals(50000000, profile.videoBitRate)
        assertEquals(60, profile.videoFrameRate)
        assertEquals(60.0, profile.videoCaptureRate, 0.001)
        assertEquals("mp4", profile.fileExtension)
    }

    @Test
    fun testAudioDisabledProfile() {
        val resolver = VideoProfileResolver()
        val profile = resolver.resolveProfile(
            cameraId = 0,
            qualityString = "4",
            recordAudio = false
        )

        assertEquals(false, profile.recordAudio)
    }
}
