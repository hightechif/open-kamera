/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.setup

import com.hightechif.openkamera.cameracontroller.CameraController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewCameraSetupHelperUnitTest {

    @Test
    fun testFind1xZoom() {
        val zoomRatios = listOf(50, 75, 100, 150, 200)
        val index = PreviewCameraSetupHelper.find1xZoom(zoomRatios)
        assertEquals(2, index)

        // When 100 is not in the list, default to 0
        val zoomRatiosNo1x = listOf(150, 200, 300)
        assertEquals(0, PreviewCameraSetupHelper.find1xZoom(zoomRatiosNo1x))

        // Null list
        assertEquals(0, PreviewCameraSetupHelper.find1xZoom(null))
    }

    @Test
    fun testFilterExtensionFlashModes() {
        val flashModes = listOf("flash_off", "flash_auto", "flash_on", "flash_torch", "flash_frontscreen_torch", "flash_red_eye")
        val filtered = PreviewCameraSetupHelper.filterExtensionFlashModes(flashModes)

        assertEquals(listOf("flash_off", "flash_frontscreen_torch"), filtered)
        assertNull(PreviewCameraSetupHelper.filterExtensionFlashModes(null))
    }

    @Test
    fun testFindBestSupportingPictureSizeIndex_CurrentAlreadySupports() {
        val size1 = CameraController.Size(1920, 1080).apply { supportedExtensions = mutableListOf(1) }
        val size2 = CameraController.Size(3840, 2160).apply { supportedExtensions = mutableListOf(1) }
        val sizes = listOf(size2, size1)

        // currentSize size1 already supports extension 1
        val result = PreviewCameraSetupHelper.findBestSupportingPictureSizeIndex(
            photoSizes = sizes,
            currentSize = size1,
            isBurst = false,
            isExtension = true,
            extension = 1
        )
        assertNull(result)
    }

    @Test
    fun testFindBestSupportingPictureSizeIndex_FindsSmallerOrEqualSupportingSize() {
        val sizeHigh = CameraController.Size(4000, 3000) // doesn't support extension 1
        val sizeMed = CameraController.Size(1920, 1080).apply { supportedExtensions = mutableListOf(1) } // supports extension 1
        val sizeLow = CameraController.Size(1280, 720).apply { supportedExtensions = mutableListOf(1) } // supports extension 1
        val sizes = listOf(sizeHigh, sizeMed, sizeLow)

        val result = PreviewCameraSetupHelper.findBestSupportingPictureSizeIndex(
            photoSizes = sizes,
            currentSize = sizeHigh,
            isBurst = false,
            isExtension = true,
            extension = 1
        )
        // sizeMed is index 1
        assertEquals(1, result)
    }

    @Test
    fun testFindBestSupportingPictureSizeIndex_FallbackToLargestSupportingSize() {
        val sizeLowCurrent = CameraController.Size(640, 480) // current doesn't support
        val sizeMed = CameraController.Size(1920, 1080).apply { supportedExtensions = mutableListOf(1) } // larger, supports
        val sizeHigh = CameraController.Size(3840, 2160).apply { supportedExtensions = mutableListOf(1) } // largest, supports
        val sizes = listOf(sizeHigh, sizeMed, sizeLowCurrent)

        val result = PreviewCameraSetupHelper.findBestSupportingPictureSizeIndex(
            photoSizes = sizes,
            currentSize = sizeLowCurrent,
            isBurst = false,
            isExtension = true,
            extension = 1
        )
        // Should fallback to largest overall supporting size (sizeHigh at index 0)
        assertEquals(0, result)
    }
}
