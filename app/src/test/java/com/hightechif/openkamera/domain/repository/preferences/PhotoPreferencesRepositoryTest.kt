/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.repository.preferences

import android.content.SharedPreferences
import com.hightechif.openkamera.preferences.FakeSharedPreferences
import com.hightechif.openkamera.preview.ApplicationInterface
import com.hightechif.openkamera.preview.ApplicationInterface.RawPref
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PhotoPreferencesRepositoryTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var repository: PhotoPreferencesRepository

    @Before
    fun setUp() {
        sharedPreferences = FakeSharedPreferences()
        repository = PhotoPreferencesRepository(sharedPreferences)
    }

    @Test
    fun `test default photo preferences fallbacks`() {
        assertEquals(PhotoPreferencesRepository.DEFAULT_IMAGE_QUALITY, repository.getImageQualityPref())
        assertFalse(repository.getFaceDetectionPref())
        assertEquals(RawPref.RAWPREF_JPEG_ONLY, repository.getRawPref())
        assertEquals(PhotoPreferencesRepository.DEFAULT_EXPO_BRACKETING_N_IMAGES, repository.getExpoBracketingNImagesPref())
        assertEquals(PhotoPreferencesRepository.DEFAULT_EXPO_BRACKETING_STOPS, repository.getExpoBracketingStopsPref(), 0.001)
        assertEquals(PhotoPreferencesRepository.DEFAULT_FOCUS_BRACKETING_N_IMAGES, repository.getFocusBracketingNImagesPref())
        assertEquals(PhotoPreferencesRepository.DEFAULT_FAST_BURST_N_IMAGES, repository.getFastBurstNImagesPref())
        assertEquals("preference_stamp_no", repository.getStampPref())
        assertEquals("1", repository.getRepeatPref())
        assertEquals(0L, repository.getRepeatIntervalPref())
        assertEquals(ApplicationInterface.NRModePref.NRMODE_NORMAL, repository.getNRModePref("default"))
    }

    @Test
    fun `test raw preference resolution`() {
        sharedPreferences.edit().putString("preference_raw", "preference_raw_yes").commit()
        assertEquals(RawPref.RAWPREF_JPEG_DNG, repository.getRawPref())

        sharedPreferences.edit().putString("preference_raw", "preference_raw_only").commit()
        assertEquals(RawPref.RAWPREF_JPEG_DNG, repository.getRawPref())
    }

    @Test
    fun `test stamp text and formatting preferences`() {
        sharedPreferences.edit()
            .putString("preference_textstamp", "OpenKamera Pro")
            .putString("preference_stamp_font_color", "#ff0000")
            .commit()

        assertEquals("OpenKamera Pro", repository.getStampCustomText())
        assertEquals("#ff0000", repository.getStampFontColor())
    }
}
