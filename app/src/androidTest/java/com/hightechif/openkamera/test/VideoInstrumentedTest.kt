package com.hightechif.openkamera.test

import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoInstrumentedTest : BaseInstrumentedTest() {

    private fun switchToVideo() {
        if (!getActivityValue { it.preview.isVideo }) {
            onActivity { activity ->
                val switchVideoButton = activity.findViewById<View>(R.id.switch_video)
                clickView(switchVideoButton)
            }
            waitUntilCameraOpened()
        }
        assertTrue(getActivityValue { it.preview.isVideo })
    }

    @Test
    fun testVideo() {
        Log.d(TAG, "testVideo")
        setToDefault()
        switchToVideo()

        val savedVideoCount = getActivityValue { it.applicationInterface.testNVideosScanned }

        onActivity { activity ->
            val takePhotoButton = activity.findViewById<View>(R.id.take_photo)
            clickView(takePhotoButton)
        }

        val timeS = System.currentTimeMillis()
        while (!getActivityValue { it.preview.isVideoRecording }) {
            Thread.sleep(100)
            assertTrue(System.currentTimeMillis() - timeS < 20000)
        }

        Thread.sleep(3000)

        onActivity { activity ->
            val takePhotoButton = activity.findViewById<View>(R.id.take_photo)
            clickView(takePhotoButton)
        }

        val timeS2 = System.currentTimeMillis()
        while (getActivityValue { it.preview.isVideoRecording }) {
            Thread.sleep(100)
            assertTrue(System.currentTimeMillis() - timeS2 < 20000)
        }

        Thread.sleep(1000)

        val newVideoCount = getActivityValue { it.applicationInterface.testNVideosScanned }
        assertEquals(savedVideoCount + 1, newVideoCount)

        onActivity { activity ->
            TestUtils.checkFilesAfterTakeVideo(
                activity,
                allowFailure = false,
                hasCb = false,
                timeMs = 3000L,
                nNonVideoFiles = 0,
                failedToStart = false,
                expNNewFiles = 1,
                nNewFiles = 1
            )
        }
    }

    @Test
    fun testVideoFlashTorch() {
        Log.d(TAG, "testVideoFlashTorch")
        setToDefault()
        switchToVideo()

        if (!getActivityValue { it.preview.supportsFlash() }) {
            return
        }

        switchToFlashValue("flash_torch")
        assertEquals("flash_torch", getActivityValue { it.preview.currentFlashValue })
    }

    @Test
    fun testVideoAudioSource() {
        Log.d(TAG, "testVideoAudioSource")
        setToDefault()
        switchToVideo()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(
                PreferenceKeys.RECORD_AUDIO_SOURCE_PREFERENCE_KEY,
                "audio_src_camcorder"
            )
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            assertEquals(
                "audio_src_camcorder",
                settings.getString(PreferenceKeys.RECORD_AUDIO_SOURCE_PREFERENCE_KEY, "")
            )
        }
    }

    @Test
    fun testVideoAudioChannels() {
        Log.d(TAG, "testVideoAudioChannels")
        setToDefault()
        switchToVideo()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.RECORD_AUDIO_CHANNELS_PREFERENCE_KEY, "audio_stereo")
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            assertEquals(
                "audio_stereo",
                settings.getString(PreferenceKeys.RECORD_AUDIO_CHANNELS_PREFERENCE_KEY, "")
            )
        }
    }

    @Test
    fun testVideoMaxDuration() {
        Log.d(TAG, "testVideoMaxDuration")
        setToDefault()
        switchToVideo()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(PreferenceKeys.VIDEO_MAX_DURATION_PREFERENCE_KEY, "3")
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            assertEquals(
                "3",
                settings.getString(PreferenceKeys.VIDEO_MAX_DURATION_PREFERENCE_KEY, "")
            )
        }
    }

    @Test
    fun testVideoResolutions() {
        Log.d(TAG, "testVideoResolutions")
        setToDefault()
        switchToVideo()

        val qualities = getActivityValue { it.preview.videoQualityHander.supportedVideoQuality }
        if (qualities.isNullOrEmpty()) {
            return
        }
        assertTrue(qualities.isNotEmpty())

        val currentQuality = getActivityValue { it.preview.videoQualityHander.currentVideoQuality }
        assertNotNull(currentQuality)
    }

    @Test
    fun testVideoFPS() {
        Log.d(TAG, "testVideoFPS")
        setToDefault()
        switchToVideo()

        val cameraId = getActivityValue { it.preview.cameraId }
        val fpsKey = PreferenceKeys.getVideoFPSPreferenceKey(cameraId, null)

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            val editor = settings.edit()
            editor.putString(fpsKey, "60")
            editor.apply()
        }
        updateForSettings()

        onActivity { activity ->
            val settings = PreferenceManager.getDefaultSharedPreferences(activity)
            assertEquals("60", settings.getString(fpsKey, ""))
        }
    }
}
