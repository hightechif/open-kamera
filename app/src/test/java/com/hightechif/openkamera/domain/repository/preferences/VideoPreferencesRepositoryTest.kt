/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.repository.preferences

import android.content.SharedPreferences
import com.hightechif.openkamera.preferences.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VideoPreferencesRepositoryTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var repository: VideoPreferencesRepository

    @Before
    fun setUp() {
        sharedPreferences = FakeSharedPreferences()
        repository = VideoPreferencesRepository(sharedPreferences)
    }

    @Test
    fun `test default video preferences fallbacks`() {
        assertEquals("", repository.getVideoQualityPref(0, null, false))
        assertFalse(repository.getVideoStabilizationPref())
        assertFalse(repository.getForce4KPref(0))
        assertEquals(VideoPreferencesRepository.DEFAULT_VIDEO_OUTPUT_FORMAT, repository.getRecordVideoOutputFormatPref())
        assertEquals(VideoPreferencesRepository.DEFAULT_VIDEO_BITRATE, repository.getVideoBitratePref())
        assertEquals(VideoPreferencesRepository.DEFAULT_VIDEO_FPS, repository.getVideoFPSPref())
        assertEquals(0L, repository.getVideoMaxDurationPref())
        assertEquals(0, repository.getVideoRestartTimesPref())
        assertEquals(VideoPreferencesRepository.DEFAULT_AUDIO_SOURCE, repository.getRecordAudioSourcePref())
        assertEquals(VideoPreferencesRepository.DEFAULT_AUDIO_CHANNELS, repository.getRecordAudioChannelsPref())
        assertTrue(repository.getRecordAudioPref())
        assertFalse(repository.getVideoFlashPref())
        assertTrue(repository.getVideoLowPowerCheckPref())
    }

    @Test
    fun `test set and get video quality`() {
        repository.setVideoQualityPref(0, null, false, "4k_uhd")
        assertEquals("4k_uhd", repository.getVideoQualityPref(0, null, false))
    }

    @Test
    fun `test max file size parsing and auto restart`() {
        sharedPreferences.edit()
            .putString("preference_video_max_filesize", "104857600")
            .putBoolean("preference_video_restart_max_filesize", true)
            .commit()

        val maxFileSize = repository.getVideoMaxFileSizePref()
        assertEquals(104857600L, maxFileSize.maxFilesize)
        assertTrue(maxFileSize.autoRestart)
    }

    @Test
    fun `test force 4K only applies to back camera 0`() {
        sharedPreferences.edit()
            .putBoolean("preference_force_video_4k", true)
            .commit()

        assertTrue(repository.getForce4KPref(0))
        assertFalse(repository.getForce4KPref(1))
    }
}
