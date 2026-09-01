/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.hightechif.openkamera.di.IoDispatcher
import com.hightechif.openkamera.domain.model.LocationCoordinates
import com.hightechif.openkamera.domain.repository.ILocationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ILocationRepository, LocationListener {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _currentLocationFlow = MutableStateFlow<LocationCoordinates?>(null)
    override val currentLocationFlow: Flow<LocationCoordinates?> =
        _currentLocationFlow.asStateFlow()

    override fun isLocationPermissionGranted(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    override fun getLastKnownLocation(): LocationCoordinates? {
        if (!isLocationPermissionGranted() || locationManager == null) return null

        try {
            val gpsLoc: Location? =
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc: Location? =
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            val bestLoc: Location? = when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time >= netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                else -> netLoc
            }

            return bestLoc?.let { loc ->
                val coords = LocationCoordinates(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitude = if (loc.hasAltitude()) loc.altitude else null
                )
                _currentLocationFlow.value = coords
                coords
            }
        } catch (_: SecurityException) {
            return null
        }
    }

    override fun onLocationChanged(location: Location) {
        _currentLocationFlow.value = LocationCoordinates(
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
