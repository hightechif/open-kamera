/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.interactor

import com.hightechif.openkamera.domain.repository.preferences.CameraPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.LocationPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.PhotoPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.UiHudPreferencesRepository
import com.hightechif.openkamera.domain.repository.preferences.VideoPreferencesRepository
import com.hightechif.openkamera.preview.ApplicationInterface.RawPref
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain interactor coordinating settings across camera, video, photo, location, and UI HUD preference repositories.
 */
@Singleton
class CameraSettingsInteractor @Inject constructor(
    val cameraPrefs: CameraPreferencesRepository,
    val videoPrefs: VideoPreferencesRepository,
    val photoPrefs: PhotoPreferencesRepository,
    val locationPrefs: LocationPreferencesRepository,
    val uiHudPrefs: UiHudPreferencesRepository
) {

    /**
     * Checks if RAW/DNG capture is enabled and supported for the current configuration.
     */
    fun isRawDngCaptureActive(): Boolean {
        val rawPref = photoPrefs.getRawPref()
        return rawPref == RawPref.RAWPREF_JPEG_DNG
    }

    /**
     * Checks if image stamping/watermarking is enabled.
     */
    fun isPhotoStampingActive(): Boolean {
        return photoPrefs.getStampPref() != "preference_stamp_no"
    }

    /**
     * Checks if video audio recording should be enabled.
     */
    fun isVideoAudioRecordingActive(): Boolean {
        return videoPrefs.getRecordAudioPref()
    }

    /**
     * Checks if geotagging with GPS direction is enabled.
     */
    fun isGeotaggingWithDirectionActive(): Boolean {
        return locationPrefs.getGeotaggingPref() && locationPrefs.getGeodirectionPref()
    }

    /**
     * Evaluates if Camera2 API should be utilized.
     */
    fun shouldUseCamera2(supportsCamera2: Boolean): Boolean {
        return cameraPrefs.useCamera2(supportsCamera2)
    }
}
