/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.capabilities

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import com.hightechif.openkamera.cameracontroller.CameraController
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2InfoCacheTest {

    @Test
    fun testNullCharacteristicsHandlesSafely() {
        val cache = Camera2InfoCache(null)
        assertEquals(CameraController.Facing.FACING_UNKNOWN, cache.facing)
        assertEquals(0, cache.sensorOrientation)
        assertFalse(cache.isFlashAvailable)
        assertFalse(cache.supportsRaw)
        assertFalse(cache.supportsLogicalMultiCamera)
        assertTrue(cache.physicalCameraIds.isEmpty())
    }

    @Test
    fun testCharacteristicsExtraction() {
        val mockCharacteristics = mockk<CameraCharacteristics>(relaxed = true)
        every { mockCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) } returns CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
        every { mockCharacteristics.get(CameraCharacteristics.LENS_FACING) } returns CameraCharacteristics.LENS_FACING_BACK
        every { mockCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) } returns 90
        every { mockCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) } returns true
        every { mockCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) } returns Range(100, 3200)
        every { mockCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) } returns intArrayOf(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
        )

        val cache = Camera2InfoCache(mockCharacteristics)
        assertEquals(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL, cache.hardwareLevel)
        assertEquals(CameraController.Facing.FACING_BACK, cache.facing)
        assertEquals(90, cache.sensorOrientation)
        assertTrue(cache.isFlashAvailable)
        assertTrue(cache.supportsRaw)
        assertEquals(Range(100, 3200), cache.isoRange)
    }
}
