/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.sensors

import com.hightechif.openkamera.domain.model.LocationCoordinates
import com.hightechif.openkamera.domain.repository.ILocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRepositoryUnitTest {

    private class FakeLocationRepository(
        var hasPermission: Boolean = true,
        var gpsLocation: LocationCoordinates? = null,
        var networkLocation: LocationCoordinates? = null
    ) : ILocationRepository {

        private val _flow = MutableStateFlow<LocationCoordinates?>(null)
        override val currentLocationFlow: Flow<LocationCoordinates?> = _flow.asStateFlow()

        override fun getLastKnownLocation(): LocationCoordinates? {
            if (!hasPermission) return null
            val best = gpsLocation ?: networkLocation
            _flow.value = best
            return best
        }

        override fun isLocationPermissionGranted(): Boolean = hasPermission

        fun emitLocation(coords: LocationCoordinates?) {
            _flow.value = coords
        }
    }

    @Test
    fun isLocationPermissionGranted_returnsConfiguredState() {
        val repo = FakeLocationRepository(hasPermission = false)
        assertFalse(repo.isLocationPermissionGranted())
        assertNull(repo.getLastKnownLocation())

        repo.hasPermission = true
        assertTrue(repo.isLocationPermissionGranted())
    }

    @Test
    fun getLastKnownLocation_fallsBackToNetworkWhenGpsUnavailable() = runBlocking {
        val networkCoords = LocationCoordinates(latitude = -6.2088, longitude = 106.8456, altitude = 15.0)
        val repo = FakeLocationRepository(
            hasPermission = true,
            gpsLocation = null,
            networkLocation = networkCoords
        )

        val lastKnown = repo.getLastKnownLocation()
        assertEquals(networkCoords, lastKnown)
        assertEquals(networkCoords, repo.currentLocationFlow.first())
    }

    @Test
    fun getLastKnownLocation_prefersGpsWhenAvailable() = runBlocking {
        val gpsCoords = LocationCoordinates(latitude = -6.2000, longitude = 106.8000, altitude = 20.0)
        val netCoords = LocationCoordinates(latitude = -6.2100, longitude = 106.8100, altitude = 10.0)
        val repo = FakeLocationRepository(
            hasPermission = true,
            gpsLocation = gpsCoords,
            networkLocation = netCoords
        )

        val lastKnown = repo.getLastKnownLocation()
        assertEquals(gpsCoords, lastKnown)
        assertEquals(gpsCoords, repo.currentLocationFlow.first())
    }

    @Test
    fun emitLocation_updatesCurrentLocationFlow() = runBlocking {
        val repo = FakeLocationRepository(hasPermission = true)
        assertNull(repo.currentLocationFlow.first())

        val newCoords = LocationCoordinates(latitude = 37.7749, longitude = -122.4194)
        repo.emitLocation(newCoords)

        assertEquals(newCoords, repo.currentLocationFlow.first())
    }
}
