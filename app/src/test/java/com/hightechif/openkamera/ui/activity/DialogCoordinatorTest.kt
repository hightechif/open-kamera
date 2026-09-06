/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.activity

import android.content.Context
import android.content.SharedPreferences
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.preferences.PreferenceKeys
import io.mockk.*
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DialogCoordinatorTest {

    private lateinit var activity: MainActivity
    private lateinit var coordinator: DialogCoordinator

    @Before
    fun setUp() {
        val activityController = Robolectric.buildActivity(MainActivity::class.java)
        activity = activityController.get()
        coordinator = DialogCoordinator(activity)
    }

    @Test
    fun testCreateSaveFolderDialog() {
        val builder = coordinator.createSaveFolderDialog("OpenCamera/Test") { folder ->
            assertNotNull(folder)
        }
        assertNotNull(builder)
    }

    @Test
    fun testCheckAndShowWhatsNewDialog_updatesPreferences() {
        val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sharedPrefs.edit() } returns editor
        every { sharedPrefs.getInt(PreferenceKeys.LATEST_VERSION_PREFERENCE_KEY, 0) } returns 0
        every { sharedPrefs.getBoolean(PreferenceKeys.SHOW_WHATS_NEW_PREFERENCE_KEY, true) } returns true

        coordinator.checkAndShowWhatsNewDialog(sharedPrefs, hasDoneFirstTime = true)
        verify { sharedPrefs.edit() }
    }
}
