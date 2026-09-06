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

class LocationPreferencesRepositoryTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var repository: LocationPreferencesRepository

    @Before
    fun setUp() {
        sharedPreferences = FakeSharedPreferences()
        repository = LocationPreferencesRepository(sharedPreferences)
    }

    @Test
    fun `test default location preferences fallbacks`() {
        assertFalse(repository.getGeotaggingPref())
        assertFalse(repository.getRequireLocationPref())
        assertFalse(repository.getGeodirectionPref())
    }

    @Test
    fun `test location preferences values`() {
        sharedPreferences.edit()
            .putBoolean("preference_location", true)
            .putBoolean("preference_require_location", true)
            .putBoolean("preference_gps_direction", true)
            .commit()

        assertTrue(repository.getGeotaggingPref())
        assertTrue(repository.getRequireLocationPref())
        assertTrue(repository.getGeodirectionPref())
    }
}
