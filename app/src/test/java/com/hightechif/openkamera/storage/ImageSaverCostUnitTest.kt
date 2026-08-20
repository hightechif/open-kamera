package com.hightechif.openkamera.storage

import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.utils.Preshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSaverCostUnitTest {

    @Test
    fun testImageSaverQueueSize() {
        assertTrue(ImageSaver.computeQueueSize(64) >= 6)
        assertTrue(ImageSaver.computeQueueSize(128) >= ImageSaver.computeQueueSize(64))
        assertTrue(ImageSaver.computeQueueSize(256) >= ImageSaver.computeQueueSize(128))
        assertTrue(ImageSaver.computeQueueSize(256) <= 19)
        assertTrue(ImageSaver.computeQueueSize(512) >= ImageSaver.computeQueueSize(256))
        assertTrue(ImageSaver.computeQueueSize(512) >= 34)
        assertTrue(ImageSaver.computeQueueSize(512) <= 70)
    }

    @Test
    fun testImageSaverRequestCost() {
        assertTrue(
            ImageSaver.computeRequestCost(true, 1) > ImageSaver.computeRequestCost(
                false,
                1
            )
        )
        assertEquals(
            ImageSaver.computeRequestCost(false, 3),
            3 * ImageSaver.computeRequestCost(false, 1)
        )
    }

    private fun checkAdjustResolutionForVideoCapabilities(
        videoWidth: Int,
        videoHeight: Int,
        supportedWidths: ImageSaver.IntRange,
        supportedHeights: ImageSaver.IntRange,
        widthAlignment: Int,
        heightAlignment: Int,
        expectedWidth: Int,
        expectedHeight: Int
    ) {
        val size: CameraController.Size = Preshots.adjustResolutionForVideoCapabilities(
            videoWidth,
            videoHeight,
            supportedWidths,
            supportedHeights,
            widthAlignment,
            heightAlignment
        )
        assertEquals(expectedWidth, size.width)
        assertEquals(expectedHeight, size.height)
    }

    @Test
    fun testAdjustResolutionForVideoCapabilities() {
        checkAdjustResolutionForVideoCapabilities(
            1920, 1440,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            1920, 1440
        )
        checkAdjustResolutionForVideoCapabilities(
            1440, 1920,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            1440, 1920
        )

        checkAdjustResolutionForVideoCapabilities(
            2560, 1920,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            2560, 1920
        )
        checkAdjustResolutionForVideoCapabilities(
            1920, 2560,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            1632, 2176
        )

        checkAdjustResolutionForVideoCapabilities(
            2800, 2000,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            2800, 2000
        )
        checkAdjustResolutionForVideoCapabilities(
            2000, 2800,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            1560, 2176
        )

        checkAdjustResolutionForVideoCapabilities(
            3840, 2160,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            3840, 2160
        )
        checkAdjustResolutionForVideoCapabilities(
            2160, 3840,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            1224, 2176
        )

        checkAdjustResolutionForVideoCapabilities(
            2560, 1280,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            2560, 1280
        )
        checkAdjustResolutionForVideoCapabilities(
            1280, 2560,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            1088, 2176
        )

        checkAdjustResolutionForVideoCapabilities(
            176, 144,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            176, 144
        )
        checkAdjustResolutionForVideoCapabilities(
            144, 176,
            ImageSaver.IntRange(160, 3840), ImageSaver.IntRange(128, 2176),
            8, 8,
            160, 200
        )
    }
}
