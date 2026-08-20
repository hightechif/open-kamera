package com.hightechif.openkamera.storage

import android.preference.PreferenceManager
import androidx.test.filters.MediumTest
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.test.BaseInstrumentedTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@MediumTest
class StorageAccessFrameworkInstrumentedTest : BaseInstrumentedTest() {

    @Test
    fun testSafPreferenceDefaultState() {
        val prefs =
            PreferenceManager.getDefaultSharedPreferences(getActivityValue { activity: MainActivity -> activity.applicationContext })
        val usingSaf = prefs.getBoolean(PreferenceKeys.USING_SAF_PREFERENCE_KEY, false)
        val safTreeUri = prefs.getString(PreferenceKeys.SAVE_LOCATION_SAF_PREFERENCE_KEY, "")

        // By default, SAF should be disabled or empty unless explicitly configured
        assertNotNull(safTreeUri)
        if (!usingSaf) {
            assertEquals("", safTreeUri)
        }
    }

    @Test
    fun testSafUriPersistenceAndSaveLocationHandler() {
        val context = getActivityValue { activity: MainActivity -> activity.applicationContext }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val testTreeUri =
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FOpenKameraTest"

        prefs.edit()
            .putBoolean(PreferenceKeys.USING_SAF_PREFERENCE_KEY, true)
            .putString(PreferenceKeys.SAVE_LOCATION_SAF_PREFERENCE_KEY, testTreeUri)
            .commit()

        val saveLocationHandler =
            getActivityValue { activity: MainActivity -> activity.saveLocationHandler }
        assertNotNull(saveLocationHandler)

        val retrievedUri = prefs.getString(PreferenceKeys.SAVE_LOCATION_SAF_PREFERENCE_KEY, "")
        assertEquals(testTreeUri, retrievedUri)
        assertTrue(prefs.getBoolean(PreferenceKeys.USING_SAF_PREFERENCE_KEY, false))

        // Reset
        prefs.edit()
            .putBoolean(PreferenceKeys.USING_SAF_PREFERENCE_KEY, false)
            .putString(PreferenceKeys.SAVE_LOCATION_SAF_PREFERENCE_KEY, "")
            .commit()
    }

    @Test
    fun testSaveLocationHistoryList() {
        val context = getActivityValue { activity: MainActivity -> activity.applicationContext }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val baseKey = PreferenceKeys.SAVE_LOCATION_HISTORY_SAF_BASE_PREFERENCE_KEY

        val historyCount = 3
        for (i in 0 until historyCount) {
            prefs.edit().putString("${baseKey}_$i", "content://test/tree/location_$i").commit()
        }

        for (i in 0 until historyCount) {
            val location = prefs.getString("${baseKey}_$i", null)
            assertEquals("content://test/tree/location_$i", location)
        }

        // Clean up
        val editor = prefs.edit()
        for (i in 0 until historyCount) {
            editor.remove("${baseKey}_$i")
        }
        editor.commit()
    }
}
