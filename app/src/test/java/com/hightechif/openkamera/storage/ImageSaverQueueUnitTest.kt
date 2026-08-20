/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import com.hightechif.openkamera.processing.HDRProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class ImageSaverQueueUnitTest {

    @Test
    fun testRequestTypeEnums() {
        assertEquals(4, ImageSaver.Request.Type.entries.size)
        assertTrue(ImageSaver.Request.Type.entries.contains(ImageSaver.Request.Type.JPEG))
        assertTrue(ImageSaver.Request.Type.entries.contains(ImageSaver.Request.Type.RAW))
        assertTrue(ImageSaver.Request.Type.entries.contains(ImageSaver.Request.Type.DUMMY))
        assertTrue(ImageSaver.Request.Type.entries.contains(ImageSaver.Request.Type.ON_DESTROY))

        assertEquals(5, ImageSaver.Request.ProcessType.entries.size)
        assertTrue(ImageSaver.Request.ProcessType.entries.contains(ImageSaver.Request.ProcessType.NORMAL))
        assertTrue(ImageSaver.Request.ProcessType.entries.contains(ImageSaver.Request.ProcessType.HDR))
        assertTrue(ImageSaver.Request.ProcessType.entries.contains(ImageSaver.Request.ProcessType.AVERAGE))
        assertTrue(ImageSaver.Request.ProcessType.entries.contains(ImageSaver.Request.ProcessType.PANORAMA))
        assertTrue(ImageSaver.Request.ProcessType.entries.contains(ImageSaver.Request.ProcessType.X_NIGHT))
    }

    @Test
    fun testRequestCostScaling() {
        val singleJpegCost = ImageSaver.computeRequestCost(false, 1)
        val threeJpegCost = ImageSaver.computeRequestCost(false, 3)
        val singleRawCost = ImageSaver.computeRequestCost(true, 1)

        assertEquals(1, singleJpegCost)
        assertEquals(3, threeJpegCost)
        assertTrue("RAW images should cost more memory queue units than single standard JPEG", singleRawCost > singleJpegCost)
    }

    @Test
    fun testQueueCapacityBounds() {
        val queue64 = ImageSaver.computeQueueSize(64)
        val queue128 = ImageSaver.computeQueueSize(128)
        val queue256 = ImageSaver.computeQueueSize(256)
        val queue512 = ImageSaver.computeQueueSize(512)

        assertTrue(queue64 >= 6)
        assertTrue(queue128 >= queue64)
        assertTrue(queue256 >= queue128)
        assertTrue(queue512 >= queue256)
        assertTrue("Queue at 512MB should be capped for memory safety", queue512 <= 70)
    }

    @Test
    fun testRequestCopy() {
        val dummyBytes = byteArrayOf(1, 2, 3, 4)
        val jpegList = mutableListOf(dummyBytes)
        val request = ImageSaver.Request(
            type =ImageSaver.Request.Type.JPEG,
            processType = ImageSaver.Request.ProcessType.NORMAL,
            forceSuffix = false,
            suffixOffset = 0,
            saveBase = ImageSaver.Request.SaveBase.SAVEBASE_NONE,
            jpegImages = jpegList,
            preshotBitmaps = null,
            rawImage = null,
            imageCaptureIntent = false,
            imageCaptureIntentUri = null,
            usingCamera2 = true,
            usingCameraExtensions = false,
            imageFormat = ImageSaver.Request.ImageFormat.STD,
            imageQuality = 90,
            doAutoStabilise = false,
            levelAngle = 0.0,
            gyroRotationMatrix = null,
            isFrontFacing = false,
            mirror = false,
            currentDate = Date(),
            preferenceHdrTonemappingAlgorithm = HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD,
            preferenceHdrContrastEnhancement = null,
            iso = 100,
            exposureTime = 1000000L,
            zoomFactor = 1.0f,
            preferenceStamp = null,
            preferenceTextstamp = null,
            fontSize = 12,
            color = 0,
            prefStyle = null,
            preferenceStampDateformat = null,
            preferenceStampTimeformat = null,
            preferenceStampGpsformat = null,
            preferenceUnitsDistance = null,
            panoramaCrop = false,
            removeDeviceExif = ImageSaver.Request.RemoveDeviceExif.OFF,
            storeLocation = false,
            location = null,
            storeGeoDirection = false,
            geoDirection = 0.0,
            pitchAngle = 0.0,
            storeYpr = false,
            customTagArtist = null,
            customTagCopyright = null,
            sampleFactor = 1
        )

        val copy = request.copy()
        assertNotNull(copy)
        assertEquals(request.type, copy.type)
        assertEquals(request.processType, copy.processType)
        assertEquals(request.imageQuality, copy.imageQuality)
        assertEquals(request.usingCamera2, copy.usingCamera2)
        assertEquals(request.iso, copy.iso)
        assertEquals(request.exposureTime, copy.exposureTime)
    }
}
