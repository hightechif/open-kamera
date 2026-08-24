/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.repository

import com.hightechif.openkamera.domain.model.SensorOrientation
import kotlinx.coroutines.flow.Flow

interface ISensorRepository {
    val sensorOrientationFlow: Flow<SensorOrientation>

    fun startListening()
    fun stopListening()
    fun isSupported(): Boolean
}
