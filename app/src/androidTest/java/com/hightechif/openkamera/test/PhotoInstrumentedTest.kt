package com.hightechif.openkamera.test

import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.endsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoInstrumentedTest : BaseInstrumentedTest() {

    private fun subTestTouchToFocus(
        waitAfterFocus: Boolean,
        singleTapPhoto: Boolean,
        doubleTapPhoto: Boolean,
        manualCanAutoFocus: Boolean,
        canFocusArea: Boolean,
        focusValue: String,
        focusValueUi: String?
    ) {
        Thread.sleep(2000)
        val savedCount = getActivityValue { it.preview.count_cameraAutoFocus }
        Log.d(TAG, "saved count_cameraAutoFocus: $savedCount")

        onView(
            anyOf(
                ViewMatchers.withClassName(endsWith("MySurfaceView")),
                ViewMatchers.withClassName(endsWith("MyTextureView"))
            )
        ).perform(click())

        onActivity { activity ->
            TestUtils.touchToFocusChecks(
                activity,
                singleTapPhoto,
                doubleTapPhoto,
                manualCanAutoFocus,
                canFocusArea,
                focusValue,
                focusValueUi,
                savedCount
            )
        }

        if (doubleTapPhoto) {
            Thread.sleep(100)
            onActivity { activity ->
                activity.preview.onDoubleTap()
            }
        }
        if (waitAfterFocus && !singleTapPhoto && !doubleTapPhoto) {
            Thread.sleep(3000)
        }
    }

    private fun waitForTakePhoto() {
        val timeS = System.currentTimeMillis()
        while (getActivityValue { it.preview.isTakingPhoto }) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Log.e(TAG, "InterruptedException from sleep", e)
            }
            assertTrue(System.currentTimeMillis() - timeS < 20000)
        }
    }

    fun subTestTakePhoto(
        immersiveMode: Boolean = false,
        touchToFocus: Boolean = false,
        waitAfterFocus: Boolean = false,
        singleTapPhoto: Boolean = false,
        doubleTapPhoto: Boolean = false,
        isRaw: Boolean = false,
        testWaitCaptureResult: Boolean = false
    ) {
        Thread.sleep(500)

        onActivity { activity ->
            activity.testLastSavedImage = null
            activity.testLastSavedImageuri = null
        }

        val info = getActivityValue { activity ->
            TestUtils.getSubTestTakePhotoInfo(
                activity,
                immersiveMode,
                singleTapPhoto,
                doubleTapPhoto
            )
        }

        val savedCountCameraTakePicture = getActivityValue { it.preview.count_cameraTakePicture }

        val files = getActivityValue { activity -> TestUtils.filesInSaveFolder(activity) }
        val nFiles = files?.size ?: 0
        Log.d(TAG, "n_files at start: $nFiles")

        val savedThumbnailCount =
            getActivityValue { it.applicationInterface.drawPreview.test_thumbnail_anim_count }

        if (touchToFocus) {
            subTestTouchToFocus(
                waitAfterFocus,
                singleTapPhoto,
                doubleTapPhoto,
                info.manualCanAutoFocus,
                info.canFocusArea,
                info.focusValue,
                info.focusValueUi
            )
        }

        if (!singleTapPhoto && !doubleTapPhoto) {
            onActivity { activity ->
                val takePhotoButton = activity.findViewById<View>(R.id.take_photo)
                assertFalse(activity.hasThumbnailAnimation())
                clickView(takePhotoButton)
            }
        }

        waitForTakePhoto()

        val newCountCameraTakePicture = getActivityValue { it.preview.count_cameraTakePicture }
        assertEquals(newCountCameraTakePicture, savedCountCameraTakePicture + 1)

        if (info.hasThumbnailAnim) {
            val timeS = System.currentTimeMillis()
            while (true) {
                val waiting =
                    getActivityValue { activity -> activity.applicationInterface.drawPreview.test_thumbnail_anim_count <= savedThumbnailCount }
                if (!waiting) break
                Thread.sleep(10)
                val allowedTimeMs =
                    if (info.isHdr || info.isNr || info.isExpo) 16000 else 10000
                assertTrue(System.currentTimeMillis() - timeS < allowedTimeMs)
            }
        }

        Thread.sleep(1500)

        TestUtils.waitUntil(
            "image saved in testLastSavedImage/uri",
            timeoutMs = if (info.isHdr || info.isNr || info.isExpo) 20000L else 10000L
        ) {
            getActivityValue { activity -> activity.testLastSavedImage != null || activity.testLastSavedImageuri != null }
        }

        onActivity { activity ->
            activity.waitUntilImageQueueEmpty()

            TestUtils.checkFocusAfterTakePhoto(activity, info.focusValue, info.focusValueUi)
            TestUtils.checkFilesAfterTakePhoto(
                activity,
                isRaw,
                testWaitCaptureResult,
                files,
                true
            )
            TestUtils.postTakePhotoChecks(
                activity,
                immersiveMode,
                info.exposureVisibility,
                info.exposureLockVisibility
            )

            assertFalse(activity.applicationInterface.imageSaver.testQueueBlocked)
        }
    }

    @Test
    fun testTakePhoto() {
        Log.d(TAG, "testTakePhoto")
        setToDefault()
        subTestTakePhoto(touchToFocus = true, waitAfterFocus = true)
    }

    @Test
    fun testTakePhotoTimer() {
        Log.d(TAG, "testTakePhotoTimer")
        setToDefault()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.TIMER_PREFERENCE_KEY, "3")
            editor.apply()
        }
        updateForSettings()

        val savedCount = getActivityValue { it.preview.count_cameraTakePicture }

        onActivity { activity ->
            val takePhotoButton = activity.findViewById<View>(R.id.take_photo)
            clickView(takePhotoButton)
        }

        assertTrue(getActivityValue { it.preview.isOnTimer })
        waitUntilTimer()

        waitForTakePhoto()
        assertEquals(savedCount + 1, getActivityValue { it.preview.count_cameraTakePicture })
    }

    @Test
    fun testTakePhotoLockedFocus() {
        Log.d(TAG, "testTakePhotoLockedFocus")
        setToDefault()
        switchToFocusValue("focus_mode_locked")
        subTestTakePhoto(touchToFocus = true, waitAfterFocus = true)
    }

    @Test
    fun testTakePhotoDRO() {
        Log.d(TAG, "testTakePhotoDRO")
        setToDefault()

        if (!getActivityValue { it.supportsDRO() }) {
            return
        }

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_dro")
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            assertSame(
                activity.applicationInterface.photoMode,
                MyApplicationInterface.PhotoMode.DRO
            )
        }

        subTestTakePhoto(touchToFocus = true, waitAfterFocus = true)
    }

    @Test
    fun testTakePhotoHDR() {
        Log.d(TAG, "testTakePhotoHDR")
        setToDefault()

        if (!getActivityValue { it.supportsHDR() }) {
            return
        }

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_hdr")
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            assertSame(
                activity.applicationInterface.photoMode,
                MyApplicationInterface.PhotoMode.HDR
            )
        }

        subTestTakePhoto(touchToFocus = true, waitAfterFocus = true)
    }

    @Test
    fun testExifTags() {
        Log.d(TAG, "testExifTags")
        setToDefault()
        subTestTakePhoto(touchToFocus = true, waitAfterFocus = true)

        onActivity { activity ->
            TestUtils.testExif(
                activity = activity,
                file = activity.testLastSavedImage,
                uri = activity.testLastSavedImageuri,
                expectDeviceTags = true,
                expectDatetime = true,
                expectGps = false
            )
        }
    }

    @Test
    fun testPhotoStamp() {
        Log.d(TAG, "testPhotoStamp")
        setToDefault()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.STAMP_PREFERENCE_KEY, "preference_stamp_yes")
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            assertTrue(activity.applicationInterface.drawPreview.getStoredHasStampPref())
        }

        subTestTakePhoto(touchToFocus = true, waitAfterFocus = true)
    }

    @Test
    fun testCustomTextStamp() {
        Log.d(TAG, "testCustomTextStamp")
        setToDefault()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.TEXT_STAMP_PREFERENCE_KEY, "OpenKamera Kotlin Test")
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            assertEquals(
                "OpenKamera Kotlin Test",
                settings.getString(PreferenceKeys.TEXT_STAMP_PREFERENCE_KEY, "")
            )
        }
    }

    @Test
    fun testPhotoBurst() {
        Log.d(TAG, "testPhotoBurst")
        setToDefault()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.REPEAT_MODE_PREFERENCE_KEY, "3")
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            assertEquals("3", activity.applicationInterface.getRepeatPref())
        }
    }

    @Test
    fun testAutoStabilise() {
        Log.d(TAG, "testAutoStabilise")
        setToDefault()

        if (!getActivityValue { it.supportsAutoStabilise() }) {
            return
        }

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putBoolean(PreferenceKeys.AUTO_STABILISE_PREFERENCE_KEY, true)
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            assertTrue(activity.applicationInterface.autoStabilisePref)
        }
    }

    @Test
    fun testFaceDetection() {
        Log.d(TAG, "testFaceDetection")
        setToDefault()

        if (!getActivityValue { it.preview.supportsFaceDetection() }) {
            return
        }

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putBoolean(PreferenceKeys.FACE_DETECTION_PREFERENCE_KEY, true)
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            assertTrue(activity.applicationInterface.getFaceDetectionPref())
        }
    }
}
