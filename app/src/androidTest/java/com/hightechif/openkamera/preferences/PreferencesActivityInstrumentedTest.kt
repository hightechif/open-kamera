/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import android.preference.PreferenceManager
import androidx.test.filters.MediumTest
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.test.BaseInstrumentedTest
import com.hightechif.openkamera.test.TestUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@MediumTest
class PreferencesActivityInstrumentedTest : BaseInstrumentedTest() {

    @Test
    fun testOpenAndCloseSettingsFragment() {
        // Verify settings is closed initially
        assertFalse(getActivityValue { activity: MainActivity -> activity.preferenceFragment != null })

        // Open settings
        onActivity { activity: MainActivity ->
            activity.openSettings()
        }
        TestUtils.waitUntil("settings open") {
            getActivityValue { activity: MainActivity -> activity.preferenceFragment != null }
        }

        assertTrue(getActivityValue { activity: MainActivity -> activity.preferenceFragment != null })
        assertNotNull(getActivityValue { activity: MainActivity -> activity.preferenceFragment })

        // Close settings
        onActivity { activity: MainActivity ->
            activity.onBackPressed()
        }
        TestUtils.waitUntil("settings closed") {
            getActivityValue { activity: MainActivity -> activity.preferenceFragment == null }
        }

        assertFalse(getActivityValue { activity: MainActivity -> activity.preferenceFragment != null })
    }

    @Test
    fun testPreferenceDefaultsInContext() {
        val context = getActivityValue { activity: MainActivity -> activity.applicationContext }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertNotNull(prefs)

        // Verify camera API default
        val apiPref = prefs.getString(
            PreferenceKeys.CAMERA_API_PREFERENCE_KEY,
            PreferenceKeys.CAMERA_API_PREFERENCE_DEFAULT
        )
        assertNotNull(apiPref)
    }
}
