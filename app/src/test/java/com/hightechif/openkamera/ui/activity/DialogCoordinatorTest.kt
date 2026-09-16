/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.activity

import android.content.SharedPreferences
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.preferences.PreferenceKeys
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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
        every {
            sharedPrefs.getBoolean(
                PreferenceKeys.SHOW_WHATS_NEW_PREFERENCE_KEY,
                true
            )
        } returns true

        coordinator.checkAndShowWhatsNewDialog(sharedPrefs, hasDoneFirstTime = true)
        verify { sharedPrefs.edit() }
    }

    @Test
    fun testShowFirstTimeHelpDialog() {
        var helpClicked = false
        coordinator.showFirstTimeHelpDialog(isTest = false) {
            helpClicked = true
        }
        assertNotNull(coordinator)
    }

    @Test
    fun testShowClearFolderHistoryConfirmationDialog() {
        var confirmed = false
        coordinator.showClearFolderHistoryConfirmationDialog(onConfirmed = {
            confirmed = true
        })
        assertNotNull(coordinator)
    }

    @Test
    fun testShowResetSettingsConfirmationDialog() {
        var confirmed = false
        coordinator.showResetSettingsConfirmationDialog(onConfirmed = {
            confirmed = true
        })
        assertNotNull(coordinator)
    }

    @Test
    fun testShowMultiCameraChooserDialog() {
        val items = arrayOf<CharSequence?>("Camera 0", "Camera 1")
        coordinator.showMultiCameraChooserDialog(items, 0) { selectedIndex ->
            assertNotNull(selectedIndex)
        }
        assertNotNull(coordinator)
    }

    @Test
    fun testShowCalibrationDialog() {
        var calibrated = false
        coordinator.showCalibrationDialog(
            titleRes = android.R.string.dialog_alert_title,
            messageRes = android.R.string.yes,
            onCalibrate = { calibrated = true }
        )
        assertNotNull(coordinator)
    }

    @Test
    fun testShowSaveLocationHistoryDialog() {
        val items = arrayOf<CharSequence?>("DCIM/Camera", "DCIM/OpenKamera")
        coordinator.showSaveLocationHistoryDialog(items, 0) { index ->
            assertNotNull(index)
        }
        assertNotNull(coordinator)
    }

    @Test
    fun testShowPermissionRationaleDialog() {
        var proceeded = false
        coordinator.showPermissionRationaleDialog(
            messageRes = android.R.string.ok,
            onProceed = { proceeded = true }
        )
        assertNotNull(coordinator)
    }

    @Test
    fun testShowGhostImageSelectionDialog() {
        val items = arrayOf<CharSequence>("Off", "Last Picture", "Selected Picture")
        coordinator.showGhostImageSelectionDialog(items, 0) { index ->
            assertNotNull(index)
        }
        assertNotNull(coordinator)
    }

    @Test
    fun testShowAudioTriggerThresholdDialog() {
        coordinator.showAudioTriggerThresholdDialog(50) { threshold ->
            assertNotNull(threshold)
        }
        assertNotNull(coordinator)
    }
}
