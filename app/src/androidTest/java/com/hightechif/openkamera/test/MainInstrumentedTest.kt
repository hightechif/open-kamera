/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.test

import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.widget.SeekBar
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MainInstrumentedTest : BaseInstrumentedTest() {

    @Test
    fun testWindowInsets() {
        Log.d(TAG, "testWindowInsets")
        setToDefault()

        if (!getActivityValue { it.edgeToEdgeMode }) {
            Log.d(TAG, "test requires edge-to-edge mode")
            return
        }

        restart()
        onActivity { activity ->
            assertTrue(activity.navigationGap >= 0)
        }
    }

    @Test
    fun testSwitchVideo() {
        Log.d(TAG, "testSwitchVideo")
        setToDefault()

        onActivity { activity ->
            assertFalse(activity.preview.isVideo)
            val switchVideoButton = activity.findViewById<View>(R.id.switch_video)
            clickView(switchVideoButton)
        }

        waitUntilCameraOpened()
        assertTrue(getActivityValue { it.preview.isVideo })

        onActivity { activity ->
            val switchVideoButton = activity.findViewById<View>(R.id.switch_video)
            clickView(switchVideoButton)
        }

        waitUntilCameraOpened()
        assertFalse(getActivityValue { it.preview.isVideo })
    }

    @Test
    fun testLocationSettings() {
        Log.d(TAG, "testLocationSettings")
        setToDefault()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putBoolean(PreferenceKeys.LOCATION_PREFERENCE_KEY, true)
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            assertTrue(settings.getBoolean(PreferenceKeys.LOCATION_PREFERENCE_KEY, false))
        }
    }

    @Test
    fun testPause() {
        Log.d(TAG, "testPause")
        setToDefault()
        pauseAndResume(true)
        assertTrue(getActivityValue { it.preview.isPreviewStarted })
    }

    @Test
    fun testImmediatelyQuit() {
        Log.d(TAG, "testImmediatelyQuit")
        setToDefault()
        onActivity { activity ->
            activity.finish()
        }
    }

    @Test
    fun testStartCameraPreviewCount() {
        Log.d(TAG, "testStartCameraPreviewCount")
        setToDefault()
        val count = getActivityValue { it.preview.countCameraStartPreview }
        assertTrue(count >= 1)
    }

    @Test
    fun testFlashStartup() {
        Log.d(TAG, "testFlashStartup")
        setToDefault()

        if (!getActivityValue { it.preview.supportsFlash() }) {
            return
        }

        switchToFlashValue("flash_auto")
        restart()
        assertEquals("flash_auto", getActivityValue { it.preview.currentFlashValue })
    }

    @Test
    fun testFlashStartup2() {
        Log.d(TAG, "testFlashStartup2")
        setToDefault()

        if (!getActivityValue { it.preview.supportsFlash() }) {
            return
        }

        switchToFlashValue("flash_on")
        restart()
        assertEquals("flash_on", getActivityValue { it.preview.currentFlashValue })
    }

    @Test
    fun testPreviewSize() {
        Log.d(TAG, "testPreviewSize")
        setToDefault()

        val size = getActivityValue { it.preview.currentPreviewSize }
        assertNotNull(size)
        assertTrue(size!!.width > 0 && size.height > 0)
    }

    @Test
    fun testPreviewSizeWYSIWYG() {
        Log.d(TAG, "testPreviewSizeWYSIWYG")
        setToDefault()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(
                PreferenceKeys.PREVIEW_SIZE_PREFERENCE_KEY,
                "preference_preview_size_wysiwyg"
            )
            editor.apply()
        }
        updateForSettings()

        val size = getActivityValue { it.preview.currentPreviewSize }
        assertNotNull(size)
        assertTrue(size!!.width > 0 && size.height > 0)
    }

    @Test
    fun testHDRRestart() {
        Log.d(TAG, "testHDRRestart")
        setToDefault()
        onActivity { activity ->
            assertSame(
                activity.applicationInterface.photoMode,
                MyApplicationInterface.PhotoMode.Standard
            )
        }

        if (!getActivityValue { it.supportsHDR() }) {
            return
        }

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_hdr")
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            assertSame(
                activity.applicationInterface.photoMode,
                MyApplicationInterface.PhotoMode.HDR
            )
        }

        restart()

        onActivity { activity ->
            assertSame(
                activity.applicationInterface.photoMode,
                MyApplicationInterface.PhotoMode.HDR
            )
        }
    }

    @Test
    fun testPopup() {
        Log.d(TAG, "testPopup")
        setToDefault()

        onView(withId(R.id.popup)).perform(click())
        assertTrue(getActivityValue { it.mainUI.popupIsOpen() })

        onActivity { activity ->
            activity.onBackPressed()
        }
        assertFalse(getActivityValue { it.mainUI.popupIsOpen() })
    }

    @Test
    fun testZoom() {
        Log.d(TAG, "testZoom")
        setToDefault()

        if (!getActivityValue { it.preview.supportsZoom() }) {
            Log.d(TAG, "zoom not supported")
            return
        }

        val maxZoom = getActivityValue { it.preview.maxZoom }
        assertTrue(maxZoom > 0)

        onActivity { activity ->
            val zoomSeekBar = activity.findViewById<SeekBar>(R.id.zoom_seekbar)
            assertEquals(View.VISIBLE, zoomSeekBar.visibility)
            assertEquals(maxZoom, zoomSeekBar.max)

            activity.preview.scaleZoom(2.0f)
        }
        Thread.sleep(500)

        val zoom = getActivityValue { it.preview.cameraController?.zoom ?: 0 }
        assertTrue(zoom > 0)

        onActivity { activity ->
            activity.preview.scaleZoom(0.5f)
        }
        Thread.sleep(500)
    }

    @Test
    fun testPreviewRotation() {
        Log.d(TAG, "testPreviewRotation")
        setToDefault()

        val displayOrientation =
            getActivityValue { it.applicationInterface.getDisplayRotation(true) }

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.ROTATE_PREVIEW_PREFERENCE_KEY, "180")
            editor.apply()
        }
        updateForSettings()

        val newDisplayOrientation =
            getActivityValue { it.applicationInterface.getDisplayRotation(true) }
        assertEquals((displayOrientation + 2) % 4, newDisplayOrientation)
    }

    @Test
    fun testExposureCompensation() {
        Log.d(TAG, "testExposureCompensation")
        setToDefault()

        if (!getActivityValue { it.preview.supportsExposures() }) {
            Log.d(TAG, "exposure compensation not supported")
            return
        }

        val minExp = getActivityValue { it.preview.minimumExposure }
        val maxExp = getActivityValue { it.preview.maximumExposure }
        assertTrue(maxExp >= minExp)

        onActivity { activity ->
            val exposureButton = activity.findViewById<View>(R.id.exposure)
            if (exposureButton.visibility == View.VISIBLE) {
                clickView(exposureButton)
            }
        }
        Thread.sleep(500)
    }

    @Test
    fun testScreenLock() {
        Log.d(TAG, "testScreenLock")
        setToDefault()

        onActivity { activity ->
            assertFalse(activity.isScreenLocked)
            activity.lockScreen()
            assertTrue(activity.isScreenLocked)
            activity.unlockScreen()
            assertFalse(activity.isScreenLocked)
        }
    }

    @Test
    fun testSaveFolderHistory() {
        Log.d(TAG, "testSaveFolderHistory")
        setToDefault()

        onActivity { activity ->
            val history = activity.saveLocationHistory
            assertNotNull(history)
            val size = history.size()
            assertTrue(size >= 0)
        }
    }

    @Test
    fun testSaveFolderHistorySAF() {
        Log.d(TAG, "testSaveFolderHistorySAF")
        setToDefault()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }

        onActivity { activity ->
            val saveFolder =
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM/OpenKamera"
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putBoolean(PreferenceKeys.USING_SAF_PREFERENCE_KEY, true)
            editor.putString(PreferenceKeys.SAVE_LOCATION_SAF_PREFERENCE_KEY, saveFolder)
            editor.apply()
            activity.saveLocationHandler.updateFolderHistorySAF(saveFolder)
        }
        updateForSettings()

        onActivity { activity ->
            val history = activity.saveLocationHistorySaf
            assertNotNull(history)
        }
    }
}
