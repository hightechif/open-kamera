/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.GridType
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPreferenceUnitTest {

    private class FakeSettingsRepository : ISettingsRepository {
        private val prefs = mutableMapOf<String, Any>()

        private val _flashModeFlow = MutableStateFlow(FlashMode.AUTO)
        override val flashModeFlow: Flow<FlashMode> = _flashModeFlow.asStateFlow()

        private val _gridTypeFlow = MutableStateFlow(GridType.NONE)
        override val gridTypeFlow: Flow<GridType> = _gridTypeFlow.asStateFlow()

        private val _captureModeFlow = MutableStateFlow(CaptureMode.PHOTO)
        override val captureModeFlow: Flow<CaptureMode> = _captureModeFlow.asStateFlow()

        private val _isRawEnabledFlow = MutableStateFlow(false)
        override val isRawEnabledFlow: Flow<Boolean> = _isRawEnabledFlow.asStateFlow()

        private val _timerSecondsFlow = MutableStateFlow(0)
        override val timerSecondsFlow: Flow<Int> = _timerSecondsFlow.asStateFlow()

        private val _showHorizonLevelFlow = MutableStateFlow(false)
        override val showHorizonLevelFlow: Flow<Boolean> = _showHorizonLevelFlow.asStateFlow()

        override fun getFlashMode(): FlashMode = _flashModeFlow.value
        override fun setFlashMode(flashMode: FlashMode) { _flashModeFlow.value = flashMode }

        override fun getGridType(): GridType = _gridTypeFlow.value
        override fun setGridType(gridType: GridType) { _gridTypeFlow.value = gridType }

        override fun getCaptureMode(): CaptureMode = _captureModeFlow.value
        override fun setCaptureMode(mode: CaptureMode) { _captureModeFlow.value = mode }

        override fun isRawEnabled(): Boolean = _isRawEnabledFlow.value
        override fun setRawEnabled(enabled: Boolean) { _isRawEnabledFlow.value = enabled }

        override fun getTimerSeconds(): Int = _timerSecondsFlow.value
        override fun setTimerSeconds(seconds: Int) { _timerSecondsFlow.value = seconds }

        override fun isHorizonLevelEnabled(): Boolean = _showHorizonLevelFlow.value
        override fun setHorizonLevelEnabled(enabled: Boolean) { _showHorizonLevelFlow.value = enabled }

        override fun getStringPreference(key: String, defaultValue: String): String =
            prefs[key] as? String ?: defaultValue

        override fun setStringPreference(key: String, value: String) {
            prefs[key] = value
        }

        override fun getBooleanPreference(key: String, defaultValue: Boolean): Boolean =
            prefs[key] as? Boolean ?: defaultValue

        override fun setBooleanPreference(key: String, value: Boolean) {
            prefs[key] = value
        }

        override fun getIntPreference(key: String, defaultValue: Int): Int =
            prefs[key] as? Int ?: defaultValue

        override fun setIntPreference(key: String, value: Int) {
            prefs[key] = value
        }
    }

    @Test
    fun preferenceKeys_standardConsistency() {
        assertEquals("preference_show_time", PreferenceKeys.SHOW_TIME_PREFERENCE_KEY)
        assertEquals("preference_show_angle", PreferenceKeys.SHOW_ANGLE_PREFERENCE_KEY)
        assertEquals("preference_grid", PreferenceKeys.SHOW_GRID_PREFERENCE_KEY)
        assertEquals("preference_timer", PreferenceKeys.TIMER_PREFERENCE_KEY)
        assertEquals("preference_raw", PreferenceKeys.RAW_PREFERENCE_KEY)
    }

    @Test
    fun settingsRepository_preferenceBinding() {
        val repo = FakeSettingsRepository()

        // Test boolean preference
        assertTrue(repo.getBooleanPreference("test_bool", true))
        repo.setBooleanPreference("test_bool", false)
        assertFalse(repo.getBooleanPreference("test_bool", true))

        // Test string preference
        assertEquals("default_val", repo.getStringPreference("test_str", "default_val"))
        repo.setStringPreference("test_str", "new_val")
        assertEquals("new_val", repo.getStringPreference("test_str", "default_val"))

        // Test int preference
        assertEquals(10, repo.getIntPreference("test_int", 10))
        repo.setIntPreference("test_int", 42)
        assertEquals(42, repo.getIntPreference("test_int", 10))
    }

    @Test
    fun gridType_keyMapping() {
        assertEquals("preference_grid_none", GridType.NONE.key)
        assertEquals("preference_grid_3x3", GridType.RULE_OF_THIRDS.key)
        assertEquals("preference_grid_phi_3x3", GridType.PHI_GRID.key)
        assertEquals("preference_grid_4x2", GridType.GRID_4X2.key)
        assertEquals("preference_grid_golden_spiral", GridType.GOLDEN_SPIRAL.key)
        assertEquals("preference_grid_crosshair", GridType.CROSSHAIR.key)
    }

    @Test
    fun flashMode_keyMapping() {
        assertEquals("flash_auto", FlashMode.AUTO.key)
        assertEquals("flash_off", FlashMode.OFF.key)
        assertEquals("flash_on", FlashMode.ON.key)
        assertEquals("flash_torch", FlashMode.TORCH.key)
    }
}
