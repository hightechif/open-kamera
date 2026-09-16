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
    val magneticAccuracyFlow: Flow<Int> get() = kotlinx.coroutines.flow.emptyFlow()
    val hasGyroSensors: Boolean get() = false
    val hasMagneticSensor: Boolean get() = false

    fun startListening()
    fun stopListening()
    fun isSupported(): Boolean
    fun setCalibratedLevelAngle(angle: Double) {}
    fun getCalibratedLevelAngle(): Double = 0.0
    fun setDeviceOrientation(orientation: Int) {}
}
