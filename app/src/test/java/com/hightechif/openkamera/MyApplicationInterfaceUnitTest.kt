/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera

import com.hightechif.openkamera.domain.model.LocationCoordinates
import com.hightechif.openkamera.domain.repository.ILocationRepository
import com.hightechif.openkamera.domain.repository.IMediaRepository
import com.hightechif.openkamera.domain.repository.ISensorRepository
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import com.hightechif.openkamera.preferences.FakeSharedPreferences
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preferences.SettingsRepositoryImpl
import com.hightechif.openkamera.preview.ApplicationInterface.RawPref
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MyApplicationInterfaceUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var settingsRepository: ISettingsRepository
    private val mockMediaRepository = mockk<IMediaRepository>(relaxed = true)
    private val mockLocationRepository = mockk<ILocationRepository>(relaxed = true)
    private val mockSensorRepository = mockk<ISensorRepository>(relaxed = true)

    private lateinit var activity: MainActivity
    private lateinit var applicationInterface: MyApplicationInterface

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        settingsRepository = SettingsRepositoryImpl(fakePrefs, testDispatcher)

        activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        applicationInterface = MyApplicationInterface(
            mainActivity = activity,
            savedInstanceState = null,
            settingsRepository = settingsRepository,
            mediaRepository = mockMediaRepository,
            locationRepository = mockLocationRepository,
            sensorRepository = mockSensorRepository
        )
    }

    @Test
    fun constructor_injectsRepositoriesSuccessfully() {
        assertNotNull(applicationInterface.settingsRepository)
        assertNotNull(applicationInterface.mediaRepository)
        assertNotNull(applicationInterface.locationRepository)
        assertNotNull(applicationInterface.sensorRepository)
    }

    @Test
    fun getLocation_delegatesToLocationRepository() {
        every { mockLocationRepository.getLastKnownLocation() } returns LocationCoordinates(
            latitude = -6.2088,
            longitude = 106.8456,
            altitude = 15.0
        )

        val loc = applicationInterface.getLocation()
        assertNotNull(loc)
        assertEquals(-6.2088, loc!!.latitude, 0.0001)
        assertEquals(106.8456, loc.longitude, 0.0001)
        assertEquals(15.0, loc.altitude, 0.0001)
    }

    @Test
    fun flashPref_delegatesToSettingsRepository() {
        applicationInterface.setFlashPref("flash_torch")
        assertEquals("flash_torch", applicationInterface.getFlashPref())
    }

    @Test
    fun isoPref_delegatesToSettingsRepository() {
        applicationInterface.setISOPref("400")
        assertEquals("400", applicationInterface.getISOPref())
    }

    @Test
    fun rawPref_delegatesToSettingsRepository() {
        fakePrefs.edit().putBoolean(PreferenceKeys.RAW_PREFERENCE_KEY, true).apply()
        // When RAW is enabled in repository
        val rawPref = applicationInterface.getRawPref()
        assertTrue(rawPref == RawPref.RAWPREF_JPEG_DNG || rawPref == RawPref.RAWPREF_JPEG_ONLY)
    }
}
