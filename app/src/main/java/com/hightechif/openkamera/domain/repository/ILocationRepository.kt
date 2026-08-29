/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.repository

import com.hightechif.openkamera.domain.model.LocationCoordinates
import kotlinx.coroutines.flow.Flow

interface ILocationRepository {
    val currentLocationFlow: Flow<LocationCoordinates?>

    fun getLastKnownLocation(): LocationCoordinates?
    fun isLocationPermissionGranted(): Boolean
}
