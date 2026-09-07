/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.repository.preferences

import android.content.SharedPreferences
import androidx.core.content.edit
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.ApplicationInterface
import com.hightechif.openkamera.preview.ApplicationInterface.RawPref

/**
 * Domain repository managing photo shooting modes, RAW capture, bracketing, and image stamping.
 */
class PhotoPreferencesRepository(
    private val sharedPreferences: SharedPreferences
) {

    companion object {
        const val DEFAULT_IMAGE_QUALITY = 90
        const val DEFAULT_EXPO_BRACKETING_N_IMAGES = 3
        const val DEFAULT_EXPO_BRACKETING_STOPS = 2.0
        const val DEFAULT_FOCUS_BRACKETING_N_IMAGES = 3
        const val DEFAULT_FAST_BURST_N_IMAGES = 5
        const val DEFAULT_STAMP_FONT_SIZE = 12
        const val DEFAULT_STAMP_FONT_COLOR = "#ffffff"
        const val DEFAULT_STAMP_STYLE = "preference_stamp_style_shadowed"
    }

    fun getResolutionPref(cameraId: Int, cameraIdSPhysical: String? = null): String {
        val key = PreferenceKeys.getResolutionPreferenceKey(cameraId, cameraIdSPhysical)
        return sharedPreferences.getString(key, "") ?: ""
    }

    fun setResolutionPref(cameraId: Int, cameraIdSPhysical: String? = null, resolution: String) {
        val key = PreferenceKeys.getResolutionPreferenceKey(cameraId, cameraIdSPhysical)
        sharedPreferences.edit {
            putString(key, resolution)
        }
    }

    fun getImageQualityPref(): Int {
        val imageQualityString = sharedPreferences.getString(
            PreferenceKeys.QUALITY_PREFERENCE_KEY,
            DEFAULT_IMAGE_QUALITY.toString()
        )
        return imageQualityString?.toIntOrNull() ?: DEFAULT_IMAGE_QUALITY
    }

    fun setImageQualityPref(quality: Int) {
        sharedPreferences.edit {
            putString(PreferenceKeys.QUALITY_PREFERENCE_KEY, quality.toString())
        }
    }

    fun getFaceDetectionPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.FACE_DETECTION_PREFERENCE_KEY, false)
    }

    fun setFaceDetectionPref(faceDetection: Boolean) {
        sharedPreferences.edit {
            putBoolean(PreferenceKeys.FACE_DETECTION_PREFERENCE_KEY, faceDetection)
        }
    }

    fun getRawPref(): RawPref {
        val rawValue = sharedPreferences.getString(
            PreferenceKeys.RAW_PREFERENCE_KEY,
            "preference_raw_no"
        )
        return when (rawValue) {
            "preference_raw_yes", "preference_raw_only" -> RawPref.RAWPREF_JPEG_DNG
            else -> RawPref.RAWPREF_JPEG_ONLY
        }
    }

    fun setRawPref(rawPref: RawPref) {
        val value = when (rawPref) {
            RawPref.RAWPREF_JPEG_DNG -> "preference_raw_yes"
            RawPref.RAWPREF_JPEG_ONLY -> "preference_raw_no"
        }
        sharedPreferences.edit {
            putString(PreferenceKeys.RAW_PREFERENCE_KEY, value)
        }
    }

    fun getExpoBracketingNImagesPref(): Int {
        val nImagesString = sharedPreferences.getString(
            PreferenceKeys.EXPO_BRACKETING_N_IMAGES_PREFERENCE_KEY,
            DEFAULT_EXPO_BRACKETING_N_IMAGES.toString()
        )
        return nImagesString?.toIntOrNull() ?: DEFAULT_EXPO_BRACKETING_N_IMAGES
    }

    fun getExpoBracketingStopsPref(): Double {
        val stopsString = sharedPreferences.getString(
            PreferenceKeys.EXPO_BRACKETING_STOPS_PREFERENCE_KEY,
            DEFAULT_EXPO_BRACKETING_STOPS.toString()
        )
        return stopsString?.toDoubleOrNull() ?: DEFAULT_EXPO_BRACKETING_STOPS
    }

    fun getFocusBracketingNImagesPref(): Int {
        val nImagesString = sharedPreferences.getString(
            PreferenceKeys.FOCUS_BRACKETING_N_IMAGES_PREFERENCE_KEY,
            DEFAULT_FOCUS_BRACKETING_N_IMAGES.toString()
        )
        return nImagesString?.toIntOrNull() ?: DEFAULT_FOCUS_BRACKETING_N_IMAGES
    }

    fun getFocusBracketingAddInfinityPref(): Boolean {
        return sharedPreferences.getBoolean(
            PreferenceKeys.FOCUS_BRACKETING_ADD_INFINITY_PREFERENCE_KEY,
            false
        )
    }

    fun getFastBurstNImagesPref(): Int {
        val nImagesString = sharedPreferences.getString(
            PreferenceKeys.FAST_BURST_N_IMAGES_PREFERENCE_KEY,
            DEFAULT_FAST_BURST_N_IMAGES.toString()
        )
        return nImagesString?.toIntOrNull() ?: DEFAULT_FAST_BURST_N_IMAGES
    }

    fun getNRModePref(nRMode: String): ApplicationInterface.NRModePref {
        return when (nRMode) {
            "preference_nr_mode_low_light" -> ApplicationInterface.NRModePref.NRMODE_LOW_LIGHT
            else -> ApplicationInterface.NRModePref.NRMODE_NORMAL
        }
    }

    fun getStampPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.STAMP_PREFERENCE_KEY,
            "preference_stamp_no"
        ) ?: "preference_stamp_no"
    }

    fun getStampDateFormatPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.STAMP_DATE_FORMAT_PREFERENCE_KEY,
            "preference_stamp_dateformat_default"
        ) ?: "preference_stamp_dateformat_default"
    }

    fun getStampTimeFormatPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.STAMP_TIME_FORMAT_PREFERENCE_KEY,
            "preference_stamp_timeformat_default"
        ) ?: "preference_stamp_timeformat_default"
    }

    fun getStampGPSFormatPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.STAMP_GPS_FORMAT_PREFERENCE_KEY,
            "preference_stamp_gpsformat_default"
        ) ?: "preference_stamp_gpsformat_default"
    }

    fun getStampCustomText(): String {
        return sharedPreferences.getString(PreferenceKeys.TEXT_STAMP_PREFERENCE_KEY, "") ?: ""
    }

    fun getStampFontSizePref(): Int {
        val fontSizeString = sharedPreferences.getString(
            PreferenceKeys.STAMP_FONT_SIZE_PREFERENCE_KEY,
            DEFAULT_STAMP_FONT_SIZE.toString()
        )
        return fontSizeString?.toIntOrNull() ?: DEFAULT_STAMP_FONT_SIZE
    }

    fun getStampFontColor(): String {
        return sharedPreferences.getString(
            PreferenceKeys.STAMP_FONT_COLOR_PREFERENCE_KEY,
            DEFAULT_STAMP_FONT_COLOR
        ) ?: DEFAULT_STAMP_FONT_COLOR
    }

    fun getStampStyle(): String {
        return sharedPreferences.getString(
            PreferenceKeys.STAMP_STYLE_KEY,
            DEFAULT_STAMP_STYLE
        ) ?: DEFAULT_STAMP_STYLE
    }

    fun getRepeatPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.REPEAT_MODE_PREFERENCE_KEY,
            "1"
        ) ?: "1"
    }

    fun getRepeatIntervalPref(): Long {
        val value = sharedPreferences.getString(
            PreferenceKeys.REPEAT_INTERVAL_PREFERENCE_KEY,
            "0"
        )
        return (value?.toLongOrNull() ?: 0L) * 1000L
    }
}
