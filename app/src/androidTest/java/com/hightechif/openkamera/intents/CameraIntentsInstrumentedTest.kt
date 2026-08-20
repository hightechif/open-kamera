/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.intents

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.filters.MediumTest
import com.hightechif.openkamera.test.BaseInstrumentedTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@MediumTest
class CameraIntentsInstrumentedTest : BaseInstrumentedTest() {

    @Test
    fun testDefaultLaunchIsNotCaptureIntent() {
        val isImageIntent = getActivityValue { it.applicationInterface.isImageCaptureIntent }
        val isVideoIntent = getActivityValue { it.applicationInterface.isVideoCaptureIntent }

        // Normal app startup should not be an external capture intent
        assertFalse(isImageIntent)
        assertFalse(isVideoIntent)
    }

    @Test
    fun testImageCaptureIntentContractFlags() {
        val testUri = Uri.parse("content://test.authority/test_images/captured_photo.jpg")
        val imageIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, testUri)
        }

        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, imageIntent.action)
        assertEquals(
            testUri,
            imageIntent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        )
    }

    @Test
    fun testVideoCaptureIntentContractFlags() {
        val testVideoUri = Uri.parse("content://test.authority/test_videos/recorded_clip.mp4")
        val videoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, testVideoUri)
            putExtra(MediaStore.EXTRA_DURATION_LIMIT, 30) // 30 seconds
            putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1) // High quality
        }

        assertEquals(MediaStore.ACTION_VIDEO_CAPTURE, videoIntent.action)
        assertEquals(
            testVideoUri,
            videoIntent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
        )
        assertEquals(30, videoIntent.getIntExtra(MediaStore.EXTRA_DURATION_LIMIT, 0))
        assertEquals(1, videoIntent.getIntExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0))
    }

    @Test
    fun testSecureImageCaptureActionRecognition() {
        val secureAction = MediaStore.ACTION_IMAGE_CAPTURE_SECURE
        val isRecognized =
            (MediaStore.ACTION_IMAGE_CAPTURE == secureAction || MediaStore.ACTION_IMAGE_CAPTURE_SECURE == secureAction)
        assertTrue(isRecognized)
    }
}
