/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.lifecycle

import android.content.pm.ActivityInfo
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import androidx.test.filters.MediumTest
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.test.BaseInstrumentedTest
import com.hightechif.openkamera.test.TestUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@MediumTest
class OrientationLifecycleInstrumentedTest : BaseInstrumentedTest() {

    @Test
    fun testDisplayRotationQuery() {
        val displayRotation = getActivityValue { activity: MainActivity ->
            activity.windowManager.defaultDisplay.rotation
        }

        assertTrue(
            displayRotation == Surface.ROTATION_0 ||
                    displayRotation == Surface.ROTATION_90 ||
                    displayRotation == Surface.ROTATION_180 ||
                    displayRotation == Surface.ROTATION_270
        )
    }

    @Test
    fun testOrientationToExifTagMapping() {
        fun degreesToExifOrientation(degrees: Int): Int {
            return when ((degrees + 360) % 360) {
                0 -> ExifInterface.ORIENTATION_NORMAL
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_UNDEFINED
            }
        }

        assertEquals(ExifInterface.ORIENTATION_NORMAL, degreesToExifOrientation(0))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, degreesToExifOrientation(90))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_180, degreesToExifOrientation(180))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, degreesToExifOrientation(270))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, degreesToExifOrientation(450))
    }

    @Test
    fun testActivityOrientationLocks() {
        onActivity { activity: MainActivity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        TestUtils.waitUntil("landscape orientation applied") {
            getActivityValue { activity: MainActivity ->
                activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            getActivityValue { activity: MainActivity -> activity.requestedOrientation })

        // Restore to unspecified
        onActivity { activity: MainActivity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        TestUtils.waitUntil("unspecified orientation applied") {
            getActivityValue { activity: MainActivity ->
                activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            getActivityValue { activity: MainActivity -> activity.requestedOrientation })
    }
}
