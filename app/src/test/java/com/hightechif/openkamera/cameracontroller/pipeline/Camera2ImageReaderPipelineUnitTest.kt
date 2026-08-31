package com.hightechif.openkamera.cameracontroller.pipeline

import android.graphics.ImageFormat
import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2ImageReaderPipelineUnitTest {

    private lateinit var pipeline: Camera2ImageReaderPipeline

    @Before
    fun setUp() {
        pipeline = Camera2ImageReaderPipeline()
    }

    @Test
    fun testInitialState() {
        assertNull(pipeline.imageReaderJpeg)
        assertNull(pipeline.imageReaderRaw)
        assertNull(pipeline.jpegSurface)
        assertNull(pipeline.rawSurface)
        assertFalse(pipeline.hasRawStream)
        assertFalse(pipeline.isConfigured)
        assertEquals(0, pipeline.jpegWidth)
        assertEquals(0, pipeline.jpegHeight)
    }

    @Test
    fun testInvalidPictureSizeThrows() {
        val config = ImageReaderConfig(pictureWidth = 0, pictureHeight = 1080)
        assertThrows(IllegalArgumentException::class.java) {
            pipeline.createPipeline(config, null, null)
        }
    }

    @Test
    fun testCreateJpegOnlyPipeline() {
        val config = ImageReaderConfig(
            pictureWidth = 1920,
            pictureHeight = 1080,
            wantRaw = false
        )
        pipeline.createPipeline(config, null, null)

        assertTrue(pipeline.isConfigured)
        assertFalse(pipeline.hasRawStream)
        assertNotNull(pipeline.imageReaderJpeg)
        assertNull(pipeline.imageReaderRaw)
        assertEquals(1920, pipeline.jpegWidth)
        assertEquals(1080, pipeline.jpegHeight)
        assertEquals(ImageFormat.JPEG, pipeline.jpegFormat)
        assertNotNull(pipeline.jpegSurface)
    }

    @Test
    fun testCreateDualStreamPipeline() {
        val config = ImageReaderConfig(
            pictureWidth = 4000,
            pictureHeight = 3000,
            wantRaw = true,
            rawSize = Size(4000, 3000),
            maxRawImages = 4
        )
        pipeline.createPipeline(config, null, null)

        assertTrue(pipeline.isConfigured)
        assertTrue(pipeline.hasRawStream)
        assertNotNull(pipeline.imageReaderJpeg)
        assertNotNull(pipeline.imageReaderRaw)
        assertEquals(4000, pipeline.rawWidth)
        assertEquals(3000, pipeline.rawHeight)
        assertEquals(ImageFormat.RAW_SENSOR, pipeline.rawFormat)
        assertNotNull(pipeline.rawSurface)
    }

    @Test
    fun testClosePipeline() {
        val config = ImageReaderConfig(
            pictureWidth = 1920,
            pictureHeight = 1080,
            wantRaw = true,
            rawSize = Size(1920, 1080)
        )
        pipeline.createPipeline(config, null, null)
        assertTrue(pipeline.isConfigured)

        pipeline.closePipeline()
        assertFalse(pipeline.isConfigured)
        assertFalse(pipeline.hasRawStream)
        assertNull(pipeline.imageReaderJpeg)
        assertNull(pipeline.imageReaderRaw)
    }
}
