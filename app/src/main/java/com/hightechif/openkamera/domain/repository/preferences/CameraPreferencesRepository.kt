/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.repository.preferences

import android.content.SharedPreferences
import android.util.Pair
import androidx.core.content.edit
import com.hightechif.openkamera.preferences.PreferenceKeys

/**
 * Domain repository managing hardware camera, exposure, focus, and shooting mode preferences.
 */
class CameraPreferencesRepository(
    private val sharedPreferences: SharedPreferences
) {

    companion object {
        const val CAMERA_ID_DEFAULT = 0
        const val APERTURE_DEFAULT = -1.0f
        const val DEFAULT_FLASH_VALUE = "flash_off"
        const val DEFAULT_FOCUS_VALUE = "focus_mode_auto"
        const val DEFAULT_SCENE_MODE = "auto"
        const val DEFAULT_COLOR_EFFECT = "none"
        const val DEFAULT_WHITE_BALANCE = "auto"
        const val DEFAULT_WHITE_BALANCE_TEMPERATURE = 5000
        const val DEFAULT_ANTI_BANDING = "auto"
        const val DEFAULT_EDGE_MODE = "default"
        const val DEFAULT_NOISE_REDUCTION_MODE = "default"
        const val DEFAULT_ISO = "auto"
        const val DEFAULT_EXPOSURE = 0
    }

    fun useCamera2(supportsCamera2: Boolean): Boolean {
        return supportsCamera2
    }

    fun getFlashPref(cameraId: Int): String {
        return sharedPreferences.getString(
            PreferenceKeys.getFlashPreferenceKey(cameraId),
            DEFAULT_FLASH_VALUE
        ) ?: DEFAULT_FLASH_VALUE
    }

    fun setFlashPref(cameraId: Int, value: String) {
        sharedPreferences.edit {
            putString(PreferenceKeys.getFlashPreferenceKey(cameraId), value)
        }
    }

    fun getFocusPref(cameraId: Int, isVideo: Boolean): String {
        return sharedPreferences.getString(
            PreferenceKeys.getFocusPreferenceKey(cameraId, isVideo),
            DEFAULT_FOCUS_VALUE
        ) ?: DEFAULT_FOCUS_VALUE
    }

    fun setFocusPref(cameraId: Int, isVideo: Boolean, value: String) {
        sharedPreferences.edit {
            putString(PreferenceKeys.getFocusPreferenceKey(cameraId, isVideo), value)
        }
    }

    fun getSceneModePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.SCENE_MODE_PREFERENCE_KEY,
            DEFAULT_SCENE_MODE
        ) ?: DEFAULT_SCENE_MODE
    }

    fun setSceneModePref(sceneMode: String?) {
        sharedPreferences.edit {
            putString(PreferenceKeys.SCENE_MODE_PREFERENCE_KEY, sceneMode)
        }
    }

    fun getColorEffectPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.COLOR_EFFECT_PREFERENCE_KEY,
            DEFAULT_COLOR_EFFECT
        ) ?: DEFAULT_COLOR_EFFECT
    }

    fun setColorEffectPref(colorEffect: String?) {
        sharedPreferences.edit {
            putString(PreferenceKeys.COLOR_EFFECT_PREFERENCE_KEY, colorEffect)
        }
    }

    fun getWhiteBalancePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.WHITE_BALANCE_PREFERENCE_KEY,
            DEFAULT_WHITE_BALANCE
        ) ?: DEFAULT_WHITE_BALANCE
    }

    fun setWhiteBalancePref(whiteBalance: String?) {
        sharedPreferences.edit {
            putString(PreferenceKeys.WHITE_BALANCE_PREFERENCE_KEY, whiteBalance)
        }
    }

    fun getWhiteBalanceTemperaturePref(): Int {
        return sharedPreferences.getInt(
            PreferenceKeys.WHITE_BALANCE_TEMPERATURE_PREFERENCE_KEY,
            DEFAULT_WHITE_BALANCE_TEMPERATURE
        )
    }

    fun setWhiteBalanceTemperaturePref(whiteBalanceTemperature: Int) {
        sharedPreferences.edit {
            putInt(
                PreferenceKeys.WHITE_BALANCE_TEMPERATURE_PREFERENCE_KEY,
                whiteBalanceTemperature
            )
        }
    }

    fun getAntiBandingPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.ANTI_BANDING_PREFERENCE_KEY,
            DEFAULT_ANTI_BANDING
        ) ?: DEFAULT_ANTI_BANDING
    }

    fun setAntiBandingPref(antiBanding: String?) {
        sharedPreferences.edit {
            putString(PreferenceKeys.ANTI_BANDING_PREFERENCE_KEY, antiBanding)
        }
    }

    fun getEdgeModePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.EDGE_MODE_PREFERENCE_KEY,
            DEFAULT_EDGE_MODE
        ) ?: DEFAULT_EDGE_MODE
    }

    fun setEdgeModePref(edgeMode: String?) {
        sharedPreferences.edit {
            putString(PreferenceKeys.EDGE_MODE_PREFERENCE_KEY, edgeMode)
        }
    }

    fun getCameraNoiseReductionModePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.CAMERA_NOISE_REDUCTION_MODE_PREFERENCE_KEY,
            DEFAULT_NOISE_REDUCTION_MODE
        ) ?: DEFAULT_NOISE_REDUCTION_MODE
    }

    fun setCameraNoiseReductionModePref(cameraNoiseReductionMode: String?) {
        sharedPreferences.edit {
            putString(
                PreferenceKeys.CAMERA_NOISE_REDUCTION_MODE_PREFERENCE_KEY,
                cameraNoiseReductionMode
            )
        }
    }

    fun getISOPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.ISO_PREFERENCE_KEY,
            DEFAULT_ISO
        ) ?: DEFAULT_ISO
    }

    fun setISOPref(iso: String?) {
        sharedPreferences.edit {
            putString(PreferenceKeys.ISO_PREFERENCE_KEY, iso)
        }
    }

    fun getExposureCompensationPref(): Int {
        val value = sharedPreferences.getString(
            PreferenceKeys.EXPOSURE_PREFERENCE_KEY,
            DEFAULT_EXPOSURE.toString()
        )
        return value?.toIntOrNull() ?: DEFAULT_EXPOSURE
    }

    fun setExposureCompensationPref(exposure: Int) {
        sharedPreferences.edit {
            putString(PreferenceKeys.EXPOSURE_PREFERENCE_KEY, exposure.toString())
        }
    }

    fun getCameraResolutionPref(cameraId: Int, cameraIdSPhysical: String?): Pair<Int, Int>? {
        val key = PreferenceKeys.getResolutionPreferenceKey(cameraId, cameraIdSPhysical)
        if (sharedPreferences.contains(key)) {
            val resolutionString = sharedPreferences.getString(key, "") ?: ""
            val index = resolutionString.indexOf(" ")
            if (index != -1) {
                val widthString = resolutionString.substring(0, index)
                val heightString = resolutionString.substring(index + 1)
                try {
                    val width = widthString.toInt()
                    val height = heightString.toInt()
                    return Pair(width, height)
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                }
            }
        }
        return null
    }

    fun setCameraResolutionPref(cameraId: Int, cameraIdSPhysical: String?, width: Int, height: Int) {
        val key = PreferenceKeys.getResolutionPreferenceKey(cameraId, cameraIdSPhysical)
        sharedPreferences.edit {
            putString(key, "$width $height")
        }
    }

    fun getOptimiseFocusPref(): Boolean {
        return sharedPreferences.getBoolean(
            PreferenceKeys.OPTIMISE_FOCUS_PREFERENCE_KEY,
            true
        )
    }

    fun setOptimiseFocusPref(optimiseFocus: Boolean) {
        sharedPreferences.edit {
            putBoolean(PreferenceKeys.OPTIMISE_FOCUS_PREFERENCE_KEY, optimiseFocus)
        }
    }

    fun getFocusAssistPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.FOCUS_ASSIST_PREFERENCE_KEY,
            "preference_focus_assist_nothing"
        ) ?: "preference_focus_assist_nothing"
    }

    fun getExposureTimePref(): Long {
        return sharedPreferences.getLong(
            PreferenceKeys.EXPOSURE_TIME_PREFERENCE_KEY,
            com.hightechif.openkamera.cameracontroller.CameraController.EXPOSURE_TIME_DEFAULT
        )
    }

    fun setExposureTimePref(exposureTime: Long) {
        sharedPreferences.edit {
            putLong(PreferenceKeys.EXPOSURE_TIME_PREFERENCE_KEY, exposureTime)
        }
    }

    fun getFocusDistancePref(isTargetDistance: Boolean): Float {
        return sharedPreferences.getFloat(
            if (isTargetDistance) PreferenceKeys.FOCUS_BRACKETING_TARGET_DISTANCE_PREFERENCE_KEY else PreferenceKeys.FOCUS_DISTANCE_PREFERENCE_KEY,
            0.0f
        )
    }

    fun isFocusBracketingSourceAutoPref(): Boolean {
        return sharedPreferences.getBoolean(
            PreferenceKeys.FOCUS_BRACKETING_AUTO_SOURCE_DISTANCE_PREFERENCE_KEY,
            false
        )
    }

    fun setFocusBracketingSourceAutoPref(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean(PreferenceKeys.FOCUS_BRACKETING_AUTO_SOURCE_DISTANCE_PREFERENCE_KEY, enabled)
        }
    }
}
