/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.test

import android.content.Intent
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.ui.PopupView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
abstract class BaseInstrumentedTest {
    companion object {
        const val TAG = "BaseInstrumentedTest"

        val intent: Intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            TestUtils.setDefaultIntent(this)
            putExtra("test_project_junit4", true)
            TestUtils.initTest(ApplicationProvider.getApplicationContext())
        }
    }

    @get:Rule
    val activityRule = ActivityScenarioRule<MainActivity>(intent)

    @Before
    open fun before() {
        Log.d(TAG, "before")
    }

    @After
    open fun after() {
        Log.d(TAG, "after")
        activityRule.scenario.onActivity { activity ->
            Log.d(TAG, "after: init")
            TestUtils.initTest(activity)
        }
        Log.d(TAG, "after done")
    }

    fun <T> getActivityValue(callback: (MainActivity) -> T): T {
        val resultRef = AtomicReference<T>()
        activityRule.scenario.onActivity { activity ->
            resultRef.set(callback(activity))
        }
        return resultRef.get()
    }

    fun onActivity(action: (MainActivity) -> Unit) {
        activityRule.scenario.onActivity(action)
    }

    fun waitUntilCameraOpened(waitForPreview: Boolean = true) {
        Log.d(TAG, "wait until camera opened")
        val timeS = System.currentTimeMillis()

        var done = false
        while (!done) {
            assertTrue(System.currentTimeMillis() - timeS < 20000)
            done = getActivityValue { activity -> activity.preview.OpenKameraAttempted() }
        }

        Log.d(TAG, "camera is open!")

        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            Log.e(TAG, "InterruptedException from sleep", e)
        }

        if (waitForPreview) {
            waitUntilPreviewStarted()
        }
    }

    fun waitUntilPreviewStarted() {
        Log.d(TAG, "wait until preview started")
        val timeS = System.currentTimeMillis()

        var done = false
        while (!done) {
            assertTrue(System.currentTimeMillis() - timeS < 20000)
            done = getActivityValue { activity -> activity.preview.isPreviewStarted }
        }

        Log.d(TAG, "preview is started!")

        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            Log.e(TAG, "InterruptedException from sleep", e)
        }
    }

    fun waitUntilTimer() {
        Log.d(TAG, "wait until timer stopped")
        var done = false
        while (!done) {
            done = getActivityValue { activity -> !activity.preview.isOnTimer }
        }
    }

    fun restart(waitForPreview: Boolean = true) {
        Log.d(TAG, "restart")
        activityRule.scenario.recreate()
        waitUntilCameraOpened(waitForPreview)
        Log.d(TAG, "restart done")
    }

    fun pauseAndResume(waitUntilCameraOpened: Boolean = true) {
        Log.d(TAG, "pauseAndResume: $waitUntilCameraOpened")
        activityRule.scenario.onActivity { activity ->
            Log.d(TAG, "pause...")
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnPause(activity)
            Log.d(TAG, "resume...")
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnResume(activity)
        }
        if (waitUntilCameraOpened) {
            waitUntilCameraOpened()
        }
    }

    fun updateForSettings() {
        Log.d(TAG, "updateForSettings")
        activityRule.scenario.onActivity { activity ->
            assertEquals(Looper.getMainLooper().thread, Thread.currentThread())
            activity.initLocation()
            activity.applicationInterface.drawPreview.updateSettings()
            activity.updateForSettings(true)
        }

        waitUntilCameraOpened()
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            Log.e(TAG, "InterruptedException from sleep", e)
        }
    }

    fun clickView(view: View) {
        Log.d(TAG, "clickView: $view")
        assertEquals(Looper.getMainLooper().thread, Thread.currentThread())
        assertEquals(view.visibility, View.VISIBLE)
        assertTrue(view.performClick())
    }

    fun openPopupMenu() {
        Log.d(TAG, "openPopupMenu")
        assertFalse(getActivityValue { activity -> activity.popupIsOpen() })
        onView(withId(R.id.popup)).check(matches(isDisplayed()))
        onView(withId(R.id.popup)).perform(click())

        Log.d(TAG, "wait for popup to open")
        var done = false
        while (!done) {
            done = getActivityValue { activity -> activity.popupIsOpen() }
        }
        Log.d(TAG, "popup is now open")
    }

    fun switchToFlashValue(requiredFlashValue: String) {
        Log.d(TAG, "switchToFlashValue: $requiredFlashValue")
        val supportedFlashValues = getActivityValue { activity -> activity.preview.supportedFlashValues }
        if (supportedFlashValues != null && supportedFlashValues.contains(requiredFlashValue)) {
            var flashValue = getActivityValue { activity -> activity.preview.currentFlashValue }
            Log.d(TAG, "start flashValue: $flashValue")
            if (flashValue != requiredFlashValue) {
                openPopupMenu()

                val flashValueF = flashValue
                activityRule.scenario.onActivity { activity ->
                    val currentFlashButton = activity.getUIButton("TEST_FLASH_$flashValueF")
                    assertNotNull(currentFlashButton)
                    assertEquals(currentFlashButton!!.alpha, PopupView.ALPHA_BUTTON_SELECTED, 1.0e-5f)
                    val flashButton = activity.getUIButton("TEST_FLASH_$requiredFlashValue")
                    assertNotNull(flashButton)
                    assertEquals(flashButton!!.alpha, PopupView.ALPHA_BUTTON, 1.0e-5f)
                    clickView(flashButton)
                }

                flashValue = getActivityValue { activity -> activity.preview.currentFlashValue }
                Log.d(TAG, "changed flashValue to: $flashValue")
            }
            assertEquals(flashValue, requiredFlashValue)
            val controllerFlashValue = getActivityValue { activity -> activity.preview.cameraController?.flashValue ?: "" }
            Log.d(TAG, "controllerFlashValue: $controllerFlashValue")
            if (flashValue == "flash_frontscreen_auto" || flashValue == "flash_frontscreen_on") {
                assertTrue(controllerFlashValue.isEmpty() || controllerFlashValue == "flash_off")
            } else {
                assertEquals(flashValue, controllerFlashValue)
            }
        } else {
            Log.d(TAG, "flash mode $requiredFlashValue not supported by camera")
        }
    }

    fun switchToFocusValue(requiredFocusValue: String) {
        Log.d(TAG, "switchToFocusValue: $requiredFocusValue")
        val supportedFocusValues = getActivityValue { activity -> activity.preview.supportedFocusValues }
        if (supportedFocusValues != null && supportedFocusValues.contains(requiredFocusValue)) {
            var focusValue = getActivityValue { activity -> activity.preview.currentFocusValue }
            Log.d(TAG, "start focusValue: $focusValue")
            if (focusValue != requiredFocusValue) {
                openPopupMenu()

                activityRule.scenario.onActivity { activity ->
                    val focusButton = activity.getUIButton("TEST_FOCUS_$requiredFocusValue")
                    assertNotNull(focusButton)
                    clickView(focusButton!!)
                }

                focusValue = getActivityValue { activity -> activity.preview.currentFocusValue }
                Log.d(TAG, "changed focusValue to: $focusValue")
            }
            assertEquals(focusValue, requiredFocusValue)
            val controllerFocusValue = getActivityValue { activity -> activity.preview.cameraController?.focusValue ?: "" }
            Log.d(TAG, "controllerFocusValue: $controllerFocusValue")
            val usingCamera2 = getActivityValue { activity -> activity.preview.usingCamera2API() }
            var compareFocusValue = focusValue
            if (compareFocusValue == "focus_mode_locked") {
                compareFocusValue = "focus_mode_auto"
            } else if (compareFocusValue == "focus_mode_infinity" && usingCamera2) {
                compareFocusValue = "focus_mode_manual2"
            }
            assertEquals(compareFocusValue, controllerFocusValue)
        } else {
            Log.d(TAG, "focus mode $requiredFocusValue not supported by camera")
        }
    }

    fun switchToISO(requiredIso: Int) {
        Log.d(TAG, "switchToISO: $requiredIso")
        var iso = getActivityValue { activity -> activity.preview.cameraController?.iSO ?: 0 }
        Log.d(TAG, "start iso: $iso")
        if (iso != requiredIso) {
            activityRule.scenario.onActivity { activity ->
                val exposureButton = activity.findViewById<View>(R.id.exposure)
                val exposureContainer = activity.findViewById<View>(R.id.exposure_container)
                assertEquals(exposureContainer.visibility, View.GONE)
                clickView(exposureButton)
            }
            activityRule.scenario.onActivity { activity ->
                val exposureContainer = activity.findViewById<View>(R.id.exposure_container)
                assertEquals(exposureContainer.visibility, View.VISIBLE)
                val isoButton = activity.getUIButton("TEST_ISO_$requiredIso")
                assertNotNull(isoButton)
                clickView(isoButton!!)
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Log.e(TAG, "InterruptedException from sleep", e)
            }
            iso = getActivityValue { activity -> activity.preview.cameraController?.iSO ?: 0 }
            Log.d(TAG, "changed iso to: $iso")
            activityRule.scenario.onActivity { activity ->
                val exposureButton = activity.findViewById<View>(R.id.exposure)
                val exposureContainer = activity.findViewById<View>(R.id.exposure_container)
                clickView(exposureButton)
                assertEquals(exposureContainer.visibility, View.GONE)
            }
        }
        assertEquals(iso, requiredIso)
    }

    fun setToDefault() {
        waitUntilCameraOpened()
        assertFalse(getActivityValue { activity -> activity.preview.isVideo })

        onActivity { activity: MainActivity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std")
            editor.apply()
            activity.testLastSavedImage = null
            activity.testLastSavedImageuri = null
        }

        switchToFlashValue("flash_off")
        switchToFocusValue("focus_mode_continuous_picture")

        try {
            Thread.sleep(200)
        } catch (e: InterruptedException) {
            Log.e(TAG, "InterruptedException from sleep", e)
        }
    }
}
