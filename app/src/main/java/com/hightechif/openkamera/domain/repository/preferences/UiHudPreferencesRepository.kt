/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.repository.preferences

import android.content.SharedPreferences
import com.hightechif.openkamera.preferences.PreferenceKeys

/**
 * Domain repository managing viewfinder HUD overlays, orientation lock, gestures, and audio feedback.
 */
class UiHudPreferencesRepository(
    private val sharedPreferences: SharedPreferences
) {

    companion object {
        const val DEFAULT_PREVIEW_SIZE = "preference_preview_size_display"
        const val DEFAULT_LOCK_ORIENTATION = "none"
        const val DEFAULT_GRID = "preference_grid_none"
        const val DEFAULT_GHOST_IMAGE = "preference_ghost_image_off"
    }

    fun getPreviewSizePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.PREVIEW_SIZE_PREFERENCE_KEY,
            DEFAULT_PREVIEW_SIZE
        ) ?: DEFAULT_PREVIEW_SIZE
    }

    fun getLockOrientationPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.LOCK_ORIENTATION_PREFERENCE_KEY,
            DEFAULT_LOCK_ORIENTATION
        ) ?: DEFAULT_LOCK_ORIENTATION
    }

    fun getTouchCapturePref(): Boolean {
        val value = sharedPreferences.getString(
            PreferenceKeys.TOUCH_CAPTURE_PREFERENCE_KEY,
            "none"
        )
        return value == "single"
    }

    fun getDoubleTapCapturePref(): Boolean {
        val value = sharedPreferences.getString(
            PreferenceKeys.TOUCH_CAPTURE_PREFERENCE_KEY,
            "none"
        )
        return value == "double"
    }

    fun getPausePreviewPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.PAUSE_PREVIEW_PREFERENCE_KEY, true)
    }

    fun getShowToastsPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_TOASTS_PREFERENCE_KEY, true)
    }

    fun getShutterSoundPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.SHUTTER_SOUND_PREFERENCE_KEY, true)
    }

    fun getStartupFocusPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.STARTUP_FOCUS_PREFERENCE_KEY, true)
    }

    fun getTimerPref(): Long {
        val value = sharedPreferences.getString(
            PreferenceKeys.TIMER_PREFERENCE_KEY,
            "0"
        )
        return (value?.toLongOrNull() ?: 0L) * 1000L
    }

    fun getShowAnglePref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_ANGLE_PREFERENCE_KEY, false)
    }

    fun getShowAngleLinePref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_ANGLE_LINE_PREFERENCE_KEY, false)
    }

    fun getShowPitchLinesPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.SHOW_PITCH_LINES_PREFERENCE_KEY, false)
    }

    fun getShowGridPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.SHOW_GRID_PREFERENCE_KEY,
            DEFAULT_GRID
        ) ?: DEFAULT_GRID
    }

    fun getGhostImagePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.GHOST_IMAGE_PREFERENCE_KEY,
            DEFAULT_GHOST_IMAGE
        ) ?: DEFAULT_GHOST_IMAGE
    }
}
