/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.test

import android.preference.PreferenceManager
import android.util.Log
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.ApplicationInterface.RawPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2InstrumentedTest : BaseInstrumentedTest() {

    @Test
    fun testCamera2ManualISO() {
        Log.d(TAG, "testCamera2ManualISO")
        setToDefault()

        if (!getActivityValue { it.preview.supportsISORange() }) {
            Log.d(TAG, "manual ISO not supported")
            return
        }

        val minISO = getActivityValue { it.preview.minimumISO }
        val maxISO = getActivityValue { it.preview.maximumISO }
        assertTrue(maxISO > minISO)

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.ISO_PREFERENCE_KEY, minISO.toString())
            editor.apply()
        }
        updateForSettings()

        val activeISO = getActivityValue { it.preview.cameraController?.iSO }
        assertNotNull(activeISO)
    }

    @Test
    fun testCamera2ManualFocus() {
        Log.d(TAG, "testCamera2ManualFocus")
        setToDefault()

        val minDistance = 0f
        val maxDistance = getActivityValue { it.preview.minimumFocusDistance }
        if (maxDistance <= 0f) {
            Log.d(TAG, "manual focus distance not supported")
            return
        }

        switchToFocusValue("focus_mode_manual2")
        assertEquals("focus_mode_manual2", getActivityValue { it.preview.currentFocusValue })
        assertTrue(maxDistance >= minDistance)
    }

    @Test
    fun testCamera2Raw() {
        Log.d(TAG, "testCamera2Raw")
        setToDefault()

        if (!getActivityValue { it.preview.supportsRaw() }) {
            Log.d(TAG, "RAW not supported")
            return
        }

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.RAW_PREFERENCE_KEY, "preference_raw_yes")
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            assertNotEquals(RawPref.RAWPREF_JPEG_ONLY, activity.applicationInterface.getRawPref())
        }
    }

    @Test
    fun testCamera2RawOnly() {
        Log.d(TAG, "testCamera2RawOnly")
        setToDefault()

        if (!getActivityValue { it.preview.supportsRaw() }) {
            Log.d(TAG, "RAW not supported")
            return
        }

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.RAW_PREFERENCE_KEY, "preference_raw_only")
            editor.apply()
        }
        updateForSettings()

        assertTrue(getActivityValue { it.applicationInterface.isRawOnly })
    }

    @Test
    fun testCamera2ManualWhiteBalance() {
        Log.d(TAG, "testCamera2ManualWhiteBalance")
        setToDefault()

        if (!getActivityValue { it.preview.supportsWhiteBalanceTemperature() }) {
            Log.d(TAG, "manual white balance not supported")
            return
        }

        val minTemp = getActivityValue { it.preview.minimumWhiteBalanceTemperature }
        val maxTemp = getActivityValue { it.preview.maximumWhiteBalanceTemperature }
        assertTrue(maxTemp > minTemp)

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.WHITE_BALANCE_PREFERENCE_KEY, "manual")
            editor.putInt(
                PreferenceKeys.WHITE_BALANCE_TEMPERATURE_PREFERENCE_KEY,
                (minTemp + maxTemp) / 2
            )
            editor.apply()
        }
        updateForSettings()

        assertEquals("manual", getActivityValue { it.applicationInterface.getWhiteBalancePref() })
    }

    @Test
    fun testCamera2ManualExposureTime() {
        Log.d(TAG, "testCamera2ManualExposureTime")
        setToDefault()

        if (!getActivityValue { it.preview.supportsISORange() }) {
            Log.d(TAG, "manual exposure time not supported")
            return
        }

        val minExp = getActivityValue { it.preview.minimumExposureTime }
        val maxExp = getActivityValue { it.preview.maximumExposureTime }
        assertTrue(maxExp > minExp)

        val targetExp = (minExp + maxExp) / 2
        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.ISO_PREFERENCE_KEY, "manual")
            editor.putLong(PreferenceKeys.EXPOSURE_TIME_PREFERENCE_KEY, targetExp)
            editor.apply()
        }
        updateForSettings()

        assertEquals("manual", getActivityValue { it.applicationInterface.getISOPref() })
    }
}
