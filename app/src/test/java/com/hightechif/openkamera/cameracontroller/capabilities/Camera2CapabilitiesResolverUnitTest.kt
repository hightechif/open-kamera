/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.capabilities

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Size
import android.util.SizeF
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
class Camera2CapabilitiesResolverUnitTest {

    @Test
    fun testHardwareLevelDescription() {
        assertEquals("LEGACY", Camera2CapabilitiesResolver.getHardwareLevelDescription(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY))
        assertEquals("LIMITED", Camera2CapabilitiesResolver.getHardwareLevelDescription(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED))
        assertEquals("FULL", Camera2CapabilitiesResolver.getHardwareLevelDescription(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))
        assertEquals("Level 3", Camera2CapabilitiesResolver.getHardwareLevelDescription(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3))
        assertEquals("EXTERNAL", Camera2CapabilitiesResolver.getHardwareLevelDescription(CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL))
        assertEquals("Unknown: null", Camera2CapabilitiesResolver.getHardwareLevelDescription(null))
    }

    @Test
    fun testIsHardwareLevelSupported() {
        val mockCharacteristics = mockk<CameraCharacteristics>()

        // Null characteristics
        assertFalse(Camera2CapabilitiesResolver.isHardwareLevelSupported(null, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))

        // Device is FULL
        every { mockCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) } returns CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL

        assertTrue(Camera2CapabilitiesResolver.isHardwareLevelSupported(mockCharacteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED))
        assertTrue(Camera2CapabilitiesResolver.isHardwareLevelSupported(mockCharacteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL))
        assertFalse(Camera2CapabilitiesResolver.isHardwareLevelSupported(mockCharacteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3))

        // Device is LEGACY
        every { mockCharacteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) } returns CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

        assertTrue(Camera2CapabilitiesResolver.isHardwareLevelSupported(mockCharacteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY))
        assertFalse(Camera2CapabilitiesResolver.isHardwareLevelSupported(mockCharacteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED))
    }

    @Test
    fun testGetFacing() {
        val mockCharacteristics = mockk<CameraCharacteristics>()

        assertEquals(CameraController.Facing.FACING_UNKNOWN, Camera2CapabilitiesResolver.getFacing(null))

        every { mockCharacteristics.get(CameraCharacteristics.LENS_FACING) } returns CameraCharacteristics.LENS_FACING_BACK
        assertEquals(CameraController.Facing.FACING_BACK, Camera2CapabilitiesResolver.getFacing(mockCharacteristics))

        every { mockCharacteristics.get(CameraCharacteristics.LENS_FACING) } returns CameraCharacteristics.LENS_FACING_FRONT
        assertEquals(CameraController.Facing.FACING_FRONT, Camera2CapabilitiesResolver.getFacing(mockCharacteristics))

        every { mockCharacteristics.get(CameraCharacteristics.LENS_FACING) } returns CameraCharacteristics.LENS_FACING_EXTERNAL
        assertEquals(CameraController.Facing.FACING_EXTERNAL, Camera2CapabilitiesResolver.getFacing(mockCharacteristics))
    }

    @Test
    fun testComputeViewAngles() {
        val mockCharacteristics = mockk<CameraCharacteristics>()

        every { mockCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) } returns Rect(0, 0, 4000, 3000)
        every { mockCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) } returns SizeF(6.4f, 4.8f)
        every { mockCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) } returns Size(4000, 3000)
        every { mockCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) } returns floatArrayOf(4.5f)

        val angles = Camera2CapabilitiesResolver.computeViewAngles(mockCharacteristics)
        assertTrue(angles.width > 0f)
        assertTrue(angles.height > 0f)
    }

    @Test
    fun testComputeZoomRatiosStandard() {
        val ratios = mutableListOf<Int>()
        val zoom1xIndex = Camera2CapabilitiesResolver.computeZoomRatios(ratios, 1.0f, 8.0f)

        assertEquals(0, zoom1xIndex)
        assertTrue(ratios.isNotEmpty())
        assertEquals(100, ratios[0])
        assertEquals(800, ratios.last())
        assertTrue(ratios.size > 20)
    }

    @Test
    fun testComputeZoomRatiosUltraWide() {
        val ratios = mutableListOf<Int>()
        val zoom1xIndex = Camera2CapabilitiesResolver.computeZoomRatios(ratios, 0.5f, 10.0f)

        assertTrue(zoom1xIndex > 0)
        assertTrue(ratios[0] >= 50)
        assertEquals(100, ratios[zoom1xIndex])
        assertEquals(1000, ratios.last())
    }

    @Test
    fun testSizeSubset() {
        val sizesA = arrayOf(Size(1920, 1080), Size(1280, 720))
        val sizesB = arrayOf(Size(3840, 2160), Size(1920, 1080), Size(1280, 720), Size(640, 480))
        val sizesC = arrayOf(Size(1920, 1080))

        assertTrue(Camera2CapabilitiesResolver.sizeSubset(sizesA, sizesB))
        assertFalse(Camera2CapabilitiesResolver.sizeSubset(sizesB, sizesA))
        assertTrue(Camera2CapabilitiesResolver.sizeSubset(sizesC, sizesA))
        assertTrue(Camera2CapabilitiesResolver.sizeSubset(null, sizesA))
        assertFalse(Camera2CapabilitiesResolver.sizeSubset(sizesA, null))
    }

    @Test
    fun testSizeSubsetIntArrays() {
        val widthsA = intArrayOf(1920, 1280)
        val heightsA = intArrayOf(1080, 720)
        val widthsB = intArrayOf(3840, 1920, 1280, 640)
        val heightsB = intArrayOf(2160, 1080, 720, 480)

        assertTrue(Camera2CapabilitiesResolver.sizeSubset(widthsA, heightsA, widthsB, heightsB))
        assertFalse(Camera2CapabilitiesResolver.sizeSubset(widthsB, heightsB, widthsA, heightsA))
    }
}
