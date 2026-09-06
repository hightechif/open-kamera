/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.repository.preferences

import android.content.SharedPreferences
import androidx.core.content.edit
import com.hightechif.openkamera.preferences.PreferenceKeys

/**
 * Domain repository managing GPS geotagging and location metadata preferences.
 */
class LocationPreferencesRepository(
    private val sharedPreferences: SharedPreferences
) {

    fun getGeotaggingPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.LOCATION_PREFERENCE_KEY, false)
    }

    fun setGeotaggingPref(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean(PreferenceKeys.LOCATION_PREFERENCE_KEY, enabled)
        }
    }

    fun getRequireLocationPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.REQUIRE_LOCATION_PREFERENCE_KEY, false)
    }

    fun setRequireLocationPref(required: Boolean) {
        sharedPreferences.edit {
            putBoolean(PreferenceKeys.REQUIRE_LOCATION_PREFERENCE_KEY, required)
        }
    }

    fun getGeodirectionPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.GPS_DIRECTION_PREFERENCE_KEY, false)
    }

    fun setGeodirectionPref(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean(PreferenceKeys.GPS_DIRECTION_PREFERENCE_KEY, enabled)
        }
    }
}
