/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import android.content.SharedPreferences
import com.hightechif.openkamera.di.IoDispatcher
import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.GridType
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val sharedPreferences: SharedPreferences,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ISettingsRepository {

    companion object {
        const val KEY_FLASH_MODE = "preference_flash_mode_global"
        const val KEY_GRID_TYPE = PreferenceKeys.SHOW_GRID_PREFERENCE_KEY
        const val KEY_CAPTURE_MODE = "preference_capture_mode_global"
        const val KEY_RAW = PreferenceKeys.RAW_PREFERENCE_KEY
        const val KEY_TIMER = PreferenceKeys.TIMER_PREFERENCE_KEY
        const val KEY_SHOW_HORIZON = PreferenceKeys.SHOW_ANGLE_LINE_PREFERENCE_KEY
    }

    private fun <T> preferenceFlow(key: String, getValue: () -> T): Flow<T> = callbackFlow {
        // Emit initial value
        trySend(getValue())

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key || changedKey == null) {
                trySend(getValue())
            }
        }

        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose {
            sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.flowOn(ioDispatcher)

    override val flashModeFlow: Flow<FlashMode> = preferenceFlow(KEY_FLASH_MODE) {
        val raw =
            sharedPreferences.getString(KEY_FLASH_MODE, FlashMode.AUTO.key) ?: FlashMode.AUTO.key
        FlashMode.fromKey(raw)
    }

    override val gridTypeFlow: Flow<GridType> = preferenceFlow(KEY_GRID_TYPE) {
        val raw = sharedPreferences.getString(KEY_GRID_TYPE, GridType.NONE.key) ?: GridType.NONE.key
        GridType.fromKey(raw)
    }

    override val captureModeFlow: Flow<CaptureMode> = preferenceFlow(KEY_CAPTURE_MODE) {
        val raw = sharedPreferences.getString(KEY_CAPTURE_MODE, CaptureMode.PHOTO.name)
            ?: CaptureMode.PHOTO.name
        try {
            CaptureMode.valueOf(raw)
        } catch (_: Exception) {
            CaptureMode.PHOTO
        }
    }

    override val isRawEnabledFlow: Flow<Boolean> = preferenceFlow(KEY_RAW) {
        val raw = sharedPreferences.getString(KEY_RAW, "preference_raw_no") ?: "preference_raw_no"
        raw != "preference_raw_no"
    }

    override val timerSecondsFlow: Flow<Int> = preferenceFlow(KEY_TIMER) {
        val raw = sharedPreferences.getString(KEY_TIMER, "0") ?: "0"
        raw.toIntOrNull() ?: 0
    }

    override val showHorizonLevelFlow: Flow<Boolean> = preferenceFlow(KEY_SHOW_HORIZON) {
        sharedPreferences.getBoolean(KEY_SHOW_HORIZON, false)
    }

    override fun getFlashMode(): FlashMode {
        val raw =
            sharedPreferences.getString(KEY_FLASH_MODE, FlashMode.AUTO.key) ?: FlashMode.AUTO.key
        return FlashMode.fromKey(raw)
    }

    override fun setFlashMode(flashMode: FlashMode) {
        sharedPreferences.edit().putString(KEY_FLASH_MODE, flashMode.key).apply()
    }

    override fun getGridType(): GridType {
        val raw = sharedPreferences.getString(KEY_GRID_TYPE, GridType.NONE.key) ?: GridType.NONE.key
        return GridType.fromKey(raw)
    }

    override fun setGridType(gridType: GridType) {
        sharedPreferences.edit().putString(KEY_GRID_TYPE, gridType.key).apply()
    }

    override fun getCaptureMode(): CaptureMode {
        val raw = sharedPreferences.getString(KEY_CAPTURE_MODE, CaptureMode.PHOTO.name)
            ?: CaptureMode.PHOTO.name
        return try {
            CaptureMode.valueOf(raw)
        } catch (_: Exception) {
            CaptureMode.PHOTO
        }
    }

    override fun setCaptureMode(mode: CaptureMode) {
        sharedPreferences.edit().putString(KEY_CAPTURE_MODE, mode.name).apply()
    }

    override fun isRawEnabled(): Boolean {
        val raw = sharedPreferences.getString(KEY_RAW, "preference_raw_no") ?: "preference_raw_no"
        return raw != "preference_raw_no"
    }

    override fun setRawEnabled(enabled: Boolean) {
        val value = if (enabled) "preference_raw_yes" else "preference_raw_no"
        sharedPreferences.edit().putString(KEY_RAW, value).apply()
    }

    override fun getTimerSeconds(): Int {
        val raw = sharedPreferences.getString(KEY_TIMER, "0") ?: "0"
        return raw.toIntOrNull() ?: 0
    }

    override fun setTimerSeconds(seconds: Int) {
        val safeSeconds = seconds.coerceAtLeast(0)
        sharedPreferences.edit().putString(KEY_TIMER, safeSeconds.toString()).apply()
    }

    override fun isHorizonLevelEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_SHOW_HORIZON, false)
    }

    override fun setHorizonLevelEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_SHOW_HORIZON, enabled).apply()
    }

    override fun getStringPreference(key: String, defaultValue: String): String {
        return sharedPreferences.getString(key, defaultValue) ?: defaultValue
    }

    override fun setStringPreference(key: String, value: String) {
        sharedPreferences.edit().putString(key, value).apply()
    }

    override fun getBooleanPreference(key: String, defaultValue: Boolean): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }

    override fun setBooleanPreference(key: String, value: Boolean) {
        sharedPreferences.edit().putBoolean(key, value).apply()
    }

    override fun getIntPreference(key: String, defaultValue: Int): Int {
        return sharedPreferences.getInt(key, defaultValue)
    }

    override fun setIntPreference(key: String, value: Int) {
        sharedPreferences.edit().putInt(key, value).apply()
    }
}
