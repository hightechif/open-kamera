/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.repository

import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.GridType
import kotlinx.coroutines.flow.Flow

interface ISettingsRepository {
    val flashModeFlow: Flow<FlashMode>
    val gridTypeFlow: Flow<GridType>
    val captureModeFlow: Flow<CaptureMode>
    val isRawEnabledFlow: Flow<Boolean>
    val timerSecondsFlow: Flow<Int>
    val showHorizonLevelFlow: Flow<Boolean>

    suspend fun getFlashMode(): FlashMode
    suspend fun setFlashMode(flashMode: FlashMode)

    suspend fun getGridType(): GridType
    suspend fun setGridType(gridType: GridType)

    suspend fun getCaptureMode(): CaptureMode
    suspend fun setCaptureMode(mode: CaptureMode)

    suspend fun isRawEnabled(): Boolean
    suspend fun setRawEnabled(enabled: Boolean)

    suspend fun getTimerSeconds(): Int
    suspend fun setTimerSeconds(seconds: Int)

    suspend fun isHorizonLevelEnabled(): Boolean
    suspend fun setHorizonLevelEnabled(enabled: Boolean)

    suspend fun getStringPreference(key: String, defaultValue: String): String
    suspend fun setStringPreference(key: String, value: String)

    suspend fun getBooleanPreference(key: String, defaultValue: Boolean): Boolean
    suspend fun setBooleanPreference(key: String, value: Boolean)

    suspend fun getIntPreference(key: String, defaultValue: Int): Int
    suspend fun setIntPreference(key: String, value: Int)
}
