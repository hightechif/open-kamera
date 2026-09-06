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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraPreferencesRepositoryTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var repository: CameraPreferencesRepository

    @Before
    fun setUp() {
        sharedPreferences = FakeSharedPreferences()
        repository = CameraPreferencesRepository(sharedPreferences)
    }

    @Test
    fun `test default camera preferences fallbacks`() {
        assertEquals(CameraPreferencesRepository.DEFAULT_FLASH_VALUE, repository.getFlashPref(0))
        assertEquals(CameraPreferencesRepository.DEFAULT_FOCUS_VALUE, repository.getFocusPref(0, isVideo = false))
        assertEquals(CameraPreferencesRepository.DEFAULT_SCENE_MODE, repository.getSceneModePref())
        assertEquals(CameraPreferencesRepository.DEFAULT_COLOR_EFFECT, repository.getColorEffectPref())
        assertEquals(CameraPreferencesRepository.DEFAULT_WHITE_BALANCE, repository.getWhiteBalancePref())
        assertEquals(CameraPreferencesRepository.DEFAULT_WHITE_BALANCE_TEMPERATURE, repository.getWhiteBalanceTemperaturePref())
        assertEquals(CameraPreferencesRepository.DEFAULT_ANTI_BANDING, repository.getAntiBandingPref())
        assertEquals(CameraPreferencesRepository.DEFAULT_EDGE_MODE, repository.getEdgeModePref())
        assertEquals(CameraPreferencesRepository.DEFAULT_NOISE_REDUCTION_MODE, repository.getCameraNoiseReductionModePref())
        assertEquals(CameraPreferencesRepository.DEFAULT_ISO, repository.getISOPref())
        assertEquals(CameraPreferencesRepository.DEFAULT_EXPOSURE, repository.getExposureCompensationPref())
        assertTrue(repository.getOptimiseFocusPref())
        assertNull(repository.getCameraResolutionPref(0, null))
    }

    @Test
    fun `test set and get flash and focus preference`() {
        repository.setFlashPref(0, "flash_torch")
        assertEquals("flash_torch", repository.getFlashPref(0))

        repository.setFocusPref(0, isVideo = true, value = "focus_mode_continuous_video")
        assertEquals("focus_mode_continuous_video", repository.getFocusPref(0, isVideo = true))
    }

    @Test
    fun `test set and get camera resolution preference`() {
        repository.setCameraResolutionPref(0, null, 1920, 1080)
        val res = repository.getCameraResolutionPref(0, null)
        assertNotNull(res)
        assertEquals(1920, res!!.first)
        assertEquals(1080, res.second)
    }

    @Test
    fun `test useCamera2 resolution`() {
        assertFalse(repository.useCamera2(supportsCamera2 = false))
        assertFalse(repository.useCamera2(supportsCamera2 = true))

        sharedPreferences.edit().putString("preference_camera_api", "preference_camera_api_camera2").commit()
        assertTrue(repository.useCamera2(supportsCamera2 = true))
    }
}
