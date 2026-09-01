/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.extension

import com.hightechif.openkamera.cameracontroller.CameraController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2ExtensionArchitectureUnitTest {

    @Test
    fun testSamsungDeviceQuirks() {
        val samsungS7 = Camera2DeviceQuirks(manufacturer = "Samsung", model = "SM-G930F")
        assertTrue(samsungS7.isSamsung)
        assertTrue(samsungS7.isSamsungS7)
        assertTrue(samsungS7.isSamsungGalaxyS)
        assertFalse(samsungS7.isSamsungGalaxyF)
        assertEquals(32, samsungS7.getMinTonemapPoints())
        assertFalse(samsungS7.allowsBurstNoiseReduction())
        assertTrue(samsungS7.usesAlternativeShutterSound())
        assertTrue(samsungS7.requiresPostCaptureTrigger(previewIsVideoMode = false))
        assertFalse(samsungS7.requiresPostCaptureTrigger(previewIsVideoMode = true))
    }

    @Test
    fun testPixelDeviceQuirks() {
        val pixel = Camera2DeviceQuirks(manufacturer = "Google", model = "Pixel 8 Pro")
        assertFalse(pixel.isSamsung)
        assertFalse(pixel.isSamsungS7)
        assertFalse(pixel.isSamsungGalaxyS)
        assertFalse(pixel.isSamsungGalaxyF)
        assertEquals(64, pixel.getMinTonemapPoints())
        assertTrue(pixel.allowsBurstNoiseReduction())
        assertFalse(pixel.usesAlternativeShutterSound())
        assertFalse(pixel.requiresPostCaptureTrigger(previewIsVideoMode = false))
        assertTrue(pixel.requiresPostCaptureTrigger(previewIsVideoMode = false, testForceRunPostCapture = true))
    }

    @Test
    fun testUpdatePictureSizesForExtension() {
        val pictureSizes = listOf(
            CameraController.Size(4000, 3000),
            CameraController.Size(1920, 1080),
            CameraController.Size(1280, 720)
        )
        val supportedExtensionSizes = listOf(
            android.util.Size(1920, 1080),
            android.util.Size(1280, 720)
        )

        val result = Camera2VendorTagsExtension.updatePictureSizesForExtension(
            pictureSizes = pictureSizes,
            extensionPictureSizes = supportedExtensionSizes,
            extension = 1 // e.g. EXTENSION_BOKEH
        )

        assertTrue(result)
        assertFalse(pictureSizes[0].supportsExtension(1))
        assertTrue(pictureSizes[1].supportsExtension(1))
        assertTrue(pictureSizes[2].supportsExtension(1))
    }

    @Test
    fun testUpdatePreviewSizesForExtensionNoMatch() {
        val previewSizes = listOf(
            CameraController.Size(1920, 1080)
        )
        val supportedExtensionSizes = listOf(
            android.util.Size(1280, 720)
        )

        val result = Camera2VendorTagsExtension.updatePreviewSizesForExtension(
            previewSizes = previewSizes,
            extensionPreviewSizes = supportedExtensionSizes,
            extension = 2 // e.g. EXTENSION_HDR
        )

        assertFalse(result)
        assertFalse(previewSizes[0].supportsExtension(2))
    }
}
