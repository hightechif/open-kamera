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

class UiHudPreferencesRepositoryTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var repository: UiHudPreferencesRepository

    @Before
    fun setUp() {
        sharedPreferences = FakeSharedPreferences()
        repository = UiHudPreferencesRepository(sharedPreferences)
    }

    @Test
    fun `test default UI and HUD preferences fallbacks`() {
        assertEquals(UiHudPreferencesRepository.DEFAULT_PREVIEW_SIZE, repository.getPreviewSizePref())
        assertEquals(UiHudPreferencesRepository.DEFAULT_LOCK_ORIENTATION, repository.getLockOrientationPref())
        assertFalse(repository.getTouchCapturePref())
        assertFalse(repository.getDoubleTapCapturePref())
        assertTrue(repository.getPausePreviewPref())
        assertTrue(repository.getShowToastsPref())
        assertTrue(repository.getShutterSoundPref())
        assertTrue(repository.getStartupFocusPref())
        assertEquals(0L, repository.getTimerPref())
        assertFalse(repository.getShowAnglePref())
        assertFalse(repository.getShowAngleLinePref())
        assertFalse(repository.getShowPitchLinesPref())
        assertEquals(UiHudPreferencesRepository.DEFAULT_GRID, repository.getShowGridPref())
        assertEquals(UiHudPreferencesRepository.DEFAULT_GHOST_IMAGE, repository.getGhostImagePref())
    }

    @Test
    fun `test touch capture mode resolution`() {
        sharedPreferences.edit().putString("preference_touch_capture", "single").commit()
        assertTrue(repository.getTouchCapturePref())
        assertFalse(repository.getDoubleTapCapturePref())

        sharedPreferences.edit().putString("preference_touch_capture", "double").commit()
        assertFalse(repository.getTouchCapturePref())
        assertTrue(repository.getDoubleTapCapturePref())
    }
}
