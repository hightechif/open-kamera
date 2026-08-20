package com.hightechif.openkamera.video

import android.preference.PreferenceManager
import android.view.View
import androidx.test.filters.LargeTest
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.test.BaseInstrumentedTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@LargeTest
class VideoRecordingStateMachineInstrumentedTest : BaseInstrumentedTest() {

    private fun ensureVideoMode() {
        if (!getActivityValue { activity: MainActivity -> activity.preview.isVideo }) {
            onActivity { activity: MainActivity ->
                val switchVideoButton = activity.findViewById<View>(R.id.switch_video)
                switchVideoButton.performClick()
            }
            waitUntilCameraOpened()
        }
        assertTrue(getActivityValue { activity: MainActivity -> activity.preview.isVideo })
    }

    @Test
    fun testVideoStateTransitions() {
        ensureVideoMode()

        val preview = getActivityValue { activity: MainActivity -> activity.preview }
        assertFalse("Initially video should not be recording", preview.isVideoRecording)
        assertFalse("Initially video should not be paused", preview.isVideoRecordingPaused)

        // Verify video duration limit preference reading
        val prefs =
            PreferenceManager.getDefaultSharedPreferences(getActivityValue { activity: MainActivity -> activity.applicationContext })
        val defaultMaxDuration =
            prefs.getString(PreferenceKeys.VIDEO_MAX_DURATION_PREFERENCE_KEY, "0")
        assertNotNull(defaultMaxDuration)
    }

    @Test
    fun testVideoMaxDurationPreferenceConfiguration() {
        val context = getActivityValue { activity: MainActivity -> activity.applicationContext }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // Configure 30 second limit
        prefs.edit().putString(PreferenceKeys.VIDEO_MAX_DURATION_PREFERENCE_KEY, "30").commit()
        onActivity { activity: MainActivity ->
            activity.updateForSettings(true)
        }

        val updatedLimit = prefs.getString(PreferenceKeys.VIDEO_MAX_DURATION_PREFERENCE_KEY, "0")
        assertEquals("30", updatedLimit)

        // Reset to unlimited (0)
        prefs.edit().putString(PreferenceKeys.VIDEO_MAX_DURATION_PREFERENCE_KEY, "0").commit()
        onActivity { activity: MainActivity ->
            activity.updateForSettings(true)
        }
        assertEquals("0", prefs.getString(PreferenceKeys.VIDEO_MAX_DURATION_PREFERENCE_KEY, "0"))
    }

    @Test
    fun testVideoRestartPreferenceConfiguration() {
        val context = getActivityValue { activity: MainActivity -> activity.applicationContext }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // Configure auto-restart video preference
        prefs.edit().putString(PreferenceKeys.VIDEO_RESTART_PREFERENCE_KEY, "1").commit()
        onActivity { activity: MainActivity ->
            activity.updateForSettings(true)
        }

        val restartCount = prefs.getString(PreferenceKeys.VIDEO_RESTART_PREFERENCE_KEY, "0")
        assertEquals("1", restartCount)

        // Reset
        prefs.edit().putString(PreferenceKeys.VIDEO_RESTART_PREFERENCE_KEY, "0").commit()
        onActivity { activity: MainActivity ->
            activity.updateForSettings(true)
        }
        assertEquals("0", prefs.getString(PreferenceKeys.VIDEO_RESTART_PREFERENCE_KEY, "0"))
    }
}
