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

/**
 * Domain repository managing video resolution, bitrates, frame rates, and recording constraints.
 */
class VideoPreferencesRepository(
    private val sharedPreferences: SharedPreferences
) {

    companion object {
        const val DEFAULT_VIDEO_OUTPUT_FORMAT = "preference_video_output_format_default"
        const val DEFAULT_VIDEO_BITRATE = "default"
        const val DEFAULT_VIDEO_FPS = "default"
        const val DEFAULT_AUDIO_SOURCE = "audio_src_camcorder"
        const val DEFAULT_AUDIO_CHANNELS = "audio_default"
    }

    fun getVideoQualityPref(
        cameraId: Int,
        cameraIdSPhysical: String?,
        highSpeed: Boolean
    ): String {
        return sharedPreferences.getString(
            PreferenceKeys.getVideoQualityPreferenceKey(cameraId, cameraIdSPhysical, highSpeed),
            ""
        ) ?: ""
    }

    fun setVideoQualityPref(
        cameraId: Int,
        cameraIdSPhysical: String?,
        highSpeed: Boolean,
        value: String
    ) {
        sharedPreferences.edit {
            putString(
                PreferenceKeys.getVideoQualityPreferenceKey(cameraId, cameraIdSPhysical, highSpeed),
                value
            )
        }
    }

    fun getVideoStabilizationPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.VIDEO_STABILIZATION_PREFERENCE_KEY, false)
    }

    fun getForce4KPref(cameraId: Int): Boolean {
        return cameraId == 0 && sharedPreferences.getBoolean(
            PreferenceKeys.FORCE_VIDEO_4_K_PREFERENCE_KEY,
            false
        )
    }

    fun getRecordVideoOutputFormatPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.VIDEO_FORMAT_PREFERENCE_KEY,
            DEFAULT_VIDEO_OUTPUT_FORMAT
        ) ?: DEFAULT_VIDEO_OUTPUT_FORMAT
    }

    fun getVideoBitratePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.VIDEO_BITRATE_PREFERENCE_KEY,
            DEFAULT_VIDEO_BITRATE
        ) ?: DEFAULT_VIDEO_BITRATE
    }

    fun getVideoFPSPref(cameraId: Int = 0, cameraIdSPhysical: String? = null): String {
        val key = PreferenceKeys.getVideoFPSPreferenceKey(cameraId, cameraIdSPhysical)
        return sharedPreferences.getString(key, DEFAULT_VIDEO_FPS) ?: DEFAULT_VIDEO_FPS
    }

    fun getVideoMaxDurationPref(): Long {
        val value = sharedPreferences.getString(
            PreferenceKeys.VIDEO_MAX_DURATION_PREFERENCE_KEY,
            "0"
        )
        return (value?.toLongOrNull() ?: 0L) * 1000L
    }

    fun getVideoRestartTimesPref(): Int {
        val value = sharedPreferences.getString(
            PreferenceKeys.VIDEO_RESTART_PREFERENCE_KEY,
            "0"
        )
        return value?.toIntOrNull() ?: 0
    }

    fun getVideoMaxFileSizePref(): ApplicationInterface.VideoMaxFileSize {
        val videoMaxFilesize = ApplicationInterface.VideoMaxFileSize()
        val value = sharedPreferences.getString(
            PreferenceKeys.VIDEO_MAX_FILE_SIZE_PREFERENCE_KEY,
            "0"
        )
        try {
            val valueLong = value?.toLongOrNull() ?: 0L
            videoMaxFilesize.maxFilesize = valueLong
        } catch (e: Exception) {
            e.printStackTrace()
        }
        videoMaxFilesize.autoRestart = sharedPreferences.getBoolean(
            PreferenceKeys.VIDEO_RESTART_MAX_FILE_SIZE_PREFERENCE_KEY,
            true
        )
        return videoMaxFilesize
    }

    fun getVideoFlashPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.VIDEO_FLASH_PREFERENCE_KEY, false)
    }

    fun getVideoLowPowerCheckPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.VIDEO_LOW_POWER_CHECK_PREFERENCE_KEY, true)
    }

    fun getRecordAudioPref(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.RECORD_AUDIO_PREFERENCE_KEY, true)
    }

    fun getRecordAudioChannelsPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.RECORD_AUDIO_CHANNELS_PREFERENCE_KEY,
            DEFAULT_AUDIO_CHANNELS
        ) ?: DEFAULT_AUDIO_CHANNELS
    }

    fun getRecordAudioSourcePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.RECORD_AUDIO_SOURCE_PREFERENCE_KEY,
            DEFAULT_AUDIO_SOURCE
        ) ?: DEFAULT_AUDIO_SOURCE
    }

    fun getVideoSubtitlePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.VIDEO_SUBTITLE_PREF,
            "preference_video_subtitle_no"
        ) ?: "preference_video_subtitle_no"
    }

    fun getVideoProfileGamma(): Float {
        val value = sharedPreferences.getString(
            PreferenceKeys.VIDEO_LOG_PREFERENCE_KEY,
            "0"
        )
        return value?.toFloatOrNull() ?: 0.0f
    }
}
