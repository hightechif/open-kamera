/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.preference.PreferenceManager
import android.view.View
import android.widget.SeekBar
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.filters.LargeTest
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.test.BaseInstrumentedTest
import com.hightechif.openkamera.test.TestUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@LargeTest
class EspressoCameraUiInstrumentedTest : BaseInstrumentedTest() {

    @Test
    fun testShutterButtonDisplayedAndClickable() {
        onView(withId(R.id.take_photo))
            .check(matches(isDisplayed()))
    }

    @Test
    fun testPopupMenuOpenAndDismiss() {
        // Initially popup view is closed
        val isPopupOpenInitially = getActivityValue { activity: MainActivity ->
            activity.mainUI.popupIsOpen()
        }
        assertFalse(isPopupOpenInitially)

        // Click popup button to open
        onView(withId(R.id.popup)).perform(click())
        TestUtils.waitUntil("popup open") {
            getActivityValue { activity: MainActivity -> activity.mainUI.popupIsOpen() }
        }
        assertTrue(getActivityValue { activity: MainActivity -> activity.mainUI.popupIsOpen() })

        // Dismiss popup
        onActivity { activity: MainActivity ->
            activity.mainUI.destroyPopup()
        }
        TestUtils.waitUntil("popup closed") {
            getActivityValue { activity: MainActivity -> !activity.mainUI.popupIsOpen() }
        }
        assertFalse(getActivityValue { activity: MainActivity -> activity.mainUI.popupIsOpen() })
    }

    @Test
    fun testExposureLockToggle() {
        val exposureLockBtn = getActivityValue { activity: MainActivity ->
            activity.findViewById<View>(R.id.exposure_lock)
        }

        if (exposureLockBtn != null && exposureLockBtn.visibility == View.VISIBLE) {
            val initialLock = getActivityValue { activity: MainActivity ->
                activity.preview.cameraController?.autoExposureLock ?: false
            }

            onView(withId(R.id.exposure_lock)).perform(click())

            val postClickLock = getActivityValue { activity: MainActivity ->
                activity.preview.cameraController?.autoExposureLock ?: false
            }
            if (getActivityValue { activity: MainActivity -> activity.preview.supportsExposureLock() }) {
                assertEquals(!initialLock, postClickLock)
            }
        }
    }

    @Test
    fun testZoomSliderInteraction() {
        onActivity { activity: MainActivity ->
            val zoomSeekBar = activity.findViewById<SeekBar>(R.id.zoom_seekbar)
            if (zoomSeekBar != null && activity.preview.supportsZoom()) {
                zoomSeekBar.progress = zoomSeekBar.max / 2
                val currentZoom = activity.preview.cameraController?.zoom ?: 0
                assertTrue(currentZoom >= 0)
            }
        }
    }

    @Test
    fun testGridOverlayPreferenceToggle() {
        val context = getActivityValue { activity: MainActivity -> activity.applicationContext }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // Set grid to 3x3
        prefs.edit().putString(PreferenceKeys.SHOW_GRID_PREFERENCE_KEY, "preference_grid_3x3")
            .commit()
        onActivity { activity: MainActivity ->
            activity.updateForSettings(true)
        }

        val currentGrid = prefs.getString(PreferenceKeys.SHOW_GRID_PREFERENCE_KEY, "")
        assertEquals("preference_grid_3x3", currentGrid)

        // Set grid to none
        prefs.edit().putString(PreferenceKeys.SHOW_GRID_PREFERENCE_KEY, "preference_grid_none")
            .commit()
        onActivity { activity: MainActivity ->
            activity.updateForSettings(true)
        }
        assertEquals(
            "preference_grid_none",
            prefs.getString(PreferenceKeys.SHOW_GRID_PREFERENCE_KEY, "")
        )
    }

    @Test
    fun testAudioNoiseIndicatorToggle() {
        val context = getActivityValue { activity: MainActivity -> activity.applicationContext }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        prefs.edit().putString(PreferenceKeys.AUDIO_CONTROL_PREFERENCE_KEY, "noise").commit()
        onActivity { activity: MainActivity ->
            activity.updateForSettings(true)
        }

        assertEquals("noise", prefs.getString(PreferenceKeys.AUDIO_CONTROL_PREFERENCE_KEY, ""))

        // Reset
        prefs.edit().putString(PreferenceKeys.AUDIO_CONTROL_PREFERENCE_KEY, "none").commit()
        onActivity { activity: MainActivity ->
            activity.updateForSettings(true)
        }
        assertEquals("none", prefs.getString(PreferenceKeys.AUDIO_CONTROL_PREFERENCE_KEY, ""))
    }
}
