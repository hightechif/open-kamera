/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BitmapPostProcessorTest {

    @Test
    fun testCompressFormatResolution() {
        assertEquals(
            Bitmap.CompressFormat.JPEG,
            BitmapPostProcessor.getBitmapCompressFormat(ImageSaver.Request.ImageFormat.STD)
        )
        assertEquals(
            Bitmap.CompressFormat.PNG,
            BitmapPostProcessor.getBitmapCompressFormat(ImageSaver.Request.ImageFormat.PNG)
        )
    }

    @Test
    fun testBitmapTransformationNoOp() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val processor = BitmapPostProcessor(org.robolectric.RuntimeEnvironment.getApplication())
        val transformed = processor.transformBitmap(bitmap, 0f, false)
        assertEquals(bitmap, transformed)
    }
}
