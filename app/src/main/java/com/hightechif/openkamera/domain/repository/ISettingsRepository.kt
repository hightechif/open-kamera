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

    fun getFlashMode(): FlashMode
    fun setFlashMode(flashMode: FlashMode)

    fun getGridType(): GridType
    fun setGridType(gridType: GridType)

    fun getCaptureMode(): CaptureMode
    fun setCaptureMode(mode: CaptureMode)

    fun isRawEnabled(): Boolean
    fun setRawEnabled(enabled: Boolean)

    fun getTimerSeconds(): Int
    fun setTimerSeconds(seconds: Int)

    fun isHorizonLevelEnabled(): Boolean
    fun setHorizonLevelEnabled(enabled: Boolean)

    fun getStringPreference(key: String, defaultValue: String): String
    fun setStringPreference(key: String, value: String)

    fun getBooleanPreference(key: String, defaultValue: Boolean): Boolean
    fun setBooleanPreference(key: String, value: Boolean)

    fun getIntPreference(key: String, defaultValue: Int): Int
    fun setIntPreference(key: String, value: Int)
}
