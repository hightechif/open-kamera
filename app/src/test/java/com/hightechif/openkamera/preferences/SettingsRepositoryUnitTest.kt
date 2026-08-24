/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import android.content.SharedPreferences
import app.cash.turbine.test
import com.hightechif.openkamera.domain.model.CaptureMode
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.model.GridType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var repository: SettingsRepositoryImpl

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        repository = SettingsRepositoryImpl(fakePrefs, testDispatcher)
    }

    @Test
    fun flashMode_getAndSet_updatesCorrectly() = testScope.runTest {
        assertEquals(FlashMode.AUTO, repository.getFlashMode())

        repository.setFlashMode(FlashMode.TORCH)
        assertEquals(FlashMode.TORCH, repository.getFlashMode())

        repository.setFlashMode(FlashMode.OFF)
        assertEquals(FlashMode.OFF, repository.getFlashMode())
    }

    @Test
    fun flashModeFlow_emitsInitialAndUpdatedValues() = testScope.runTest {
        repository.flashModeFlow.test {
            assertEquals(FlashMode.AUTO, awaitItem())

            repository.setFlashMode(FlashMode.ON)
            assertEquals(FlashMode.ON, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun gridType_getAndSet_updatesCorrectly() = testScope.runTest {
        assertEquals(GridType.NONE, repository.getGridType())

        repository.setGridType(GridType.RULE_OF_THIRDS)
        assertEquals(GridType.RULE_OF_THIRDS, repository.getGridType())

        repository.setGridType(GridType.GOLDEN_SPIRAL)
        assertEquals(GridType.GOLDEN_SPIRAL, repository.getGridType())
    }

    @Test
    fun captureMode_getAndSet_updatesCorrectly() = testScope.runTest {
        assertEquals(CaptureMode.PHOTO, repository.getCaptureMode())

        repository.setCaptureMode(CaptureMode.HDR)
        assertEquals(CaptureMode.HDR, repository.getCaptureMode())

        repository.setCaptureMode(CaptureMode.VIDEO)
        assertEquals(CaptureMode.VIDEO, repository.getCaptureMode())
    }

    @Test
    fun rawMode_getAndSet_updatesCorrectly() = testScope.runTest {
        assertFalse(repository.isRawEnabled())

        repository.setRawEnabled(true)
        assertTrue(repository.isRawEnabled())

        repository.setRawEnabled(false)
        assertFalse(repository.isRawEnabled())
    }

    @Test
    fun timerSeconds_getAndSet_coercesPositiveValues() = testScope.runTest {
        assertEquals(0, repository.getTimerSeconds())

        repository.setTimerSeconds(5)
        assertEquals(5, repository.getTimerSeconds())

        repository.setTimerSeconds(-3)
        assertEquals(0, repository.getTimerSeconds())
    }

    @Test
    fun horizonLevel_getAndSet_updatesCorrectly() = testScope.runTest {
        assertFalse(repository.isHorizonLevelEnabled())

        repository.setHorizonLevelEnabled(true)
        assertTrue(repository.isHorizonLevelEnabled())
    }

    @Test
    fun genericPreferences_getAndSet_workCorrectly() = testScope.runTest {
        assertEquals("default_val", repository.getStringPreference("custom_str", "default_val"))
        repository.setStringPreference("custom_str", "saved_val")
        assertEquals("saved_val", repository.getStringPreference("custom_str", "default_val"))

        assertEquals(10, repository.getIntPreference("custom_int", 10))
        repository.setIntPreference("custom_int", 42)
        assertEquals(42, repository.getIntPreference("custom_int", 10))

        assertTrue(repository.getBooleanPreference("custom_bool", true))
        repository.setBooleanPreference("custom_bool", false)
        assertFalse(repository.getBooleanPreference("custom_bool", true))
    }
}

/**
 * In-memory test double for SharedPreferences.
 */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = data

    override fun getString(key: String?, defValue: String?): String? =
        data[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String> ?: defValues)

    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener?.let { listeners.add(it) }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listener?.let { listeners.remove(it) }
    }

    private fun notifyChanged(key: String?) {
        listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) }
    }

    class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) temp[key] = value
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?
        ): SharedPreferences.Editor = apply {
            if (key != null) temp[key] = values
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) temp[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) temp[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) temp[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) temp[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) temp[key] = null
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clear = true
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) prefs.data.clear()
            temp.forEach { (key, value) ->
                if (value == null) {
                    prefs.data.remove(key)
                } else {
                    prefs.data[key] = value
                }
                prefs.notifyChanged(key)
            }
            temp.clear()
        }
    }
}
