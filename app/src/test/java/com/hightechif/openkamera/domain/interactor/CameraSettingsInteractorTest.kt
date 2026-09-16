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
import com.hightechif.openkamera.preferences.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CameraSettingsInteractorTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var interactor: CameraSettingsInteractor

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        val cameraRepo = CameraPreferencesRepository(fakePrefs)
        val videoRepo = VideoPreferencesRepository(fakePrefs)
        val photoRepo = PhotoPreferencesRepository(fakePrefs)
        val locationRepo = LocationPreferencesRepository(fakePrefs)
        val uiHudRepo = UiHudPreferencesRepository(fakePrefs)

        interactor = CameraSettingsInteractor(
            cameraPrefs = cameraRepo,
            videoPrefs = videoRepo,
            photoPrefs = photoRepo,
            locationPrefs = locationRepo,
            uiHudPrefs = uiHudRepo
        )
    }

    @Test
    fun `test raw DNG capture coordination`() {
        assertFalse(interactor.isRawDngCaptureActive())

        fakePrefs.edit().putString("preference_raw", "preference_raw_yes").apply()
        assertTrue(interactor.isRawDngCaptureActive())
    }

    @Test
    fun `test photo stamping coordination`() {
        assertFalse(interactor.isPhotoStampingActive())

        fakePrefs.edit().putString("preference_stamp", "preference_stamp_yes").apply()
        assertTrue(interactor.isPhotoStampingActive())
    }

    @Test
    fun `test geotagging with direction coordination`() {
        assertFalse(interactor.isGeotaggingWithDirectionActive())

        fakePrefs.edit()
            .putBoolean("preference_location", true)
            .putBoolean("preference_gps_direction", true)
            .apply()
        assertTrue(interactor.isGeotaggingWithDirectionActive())
    }

    @Test
    fun `test camera2 coordination`() {
        assertFalse(interactor.shouldUseCamera2(supportsCamera2 = false))
        assertTrue(interactor.shouldUseCamera2(supportsCamera2 = true))
    }
}
