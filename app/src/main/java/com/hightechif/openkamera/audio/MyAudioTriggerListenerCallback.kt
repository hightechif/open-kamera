package com.hightechif.openkamera.audio

import android.preference.PreferenceManager
import android.util.Log
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.utils.MyDebug

/** Handles the audio "noise" trigger option.
 */
class MyAudioTriggerListenerCallback internal constructor(private val mainActivity: MainActivity) :
    AudioListener.AudioListenerCallback {
    private var lastLevel = -1
    private var timeQuietLoud: Long = -1
    private var timeLastAudioTriggerPhoto: Long = -1
    private var audioNoiseSensitivity = -1

    fun setAudioNoiseSensitivity(audioNoiseSensitivity: Int) {
        this.audioNoiseSensitivity = audioNoiseSensitivity
    }

    /** Listens to audio noise and decides when there's been a "loud" noise to trigger taking a photo.
     */
    override fun onAudio(level: Int) {
        var audioTrigger = false

        /*if( level > 150 ) {
            if( MyDebug.LOG )
                Log.d(TAG, "loud noise!: " + level);
            audioTrigger = true;
        }*/
        if (lastLevel == -1) {
            lastLevel = level
            return
        }
        val diff = level - lastLevel

        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "noise_sensitivity: $audioNoiseSensitivity"
            )
            Log.d(TAG, "diff: $diff")
        }

        if (diff > audioNoiseSensitivity) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "got louder!: $lastLevel to $level , diff: $diff"
            )
            timeQuietLoud = System.currentTimeMillis()
            if (MyDebug.LOG) Log.d(
                TAG,
                "    time: $timeQuietLoud"
            )
        } else if (diff < -audioNoiseSensitivity && timeQuietLoud != -1L) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "got quieter!: $lastLevel to $level , diff: $diff"
            )
            val timeNow = System.currentTimeMillis()
            val duration = timeNow - timeQuietLoud
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "stopped being loud - was loud since: $timeQuietLoud"
                )
                Log.d(
                    TAG,
                    "    time_now: $timeNow"
                )
                Log.d(
                    TAG,
                    "    duration: $duration"
                )
            }
            if (duration < 1500) {
                if (MyDebug.LOG) Log.d(TAG, "audio_trigger set")
                audioTrigger = true
            }
            timeQuietLoud = -1
        } else {
            if (MyDebug.LOG) Log.d(
                TAG,
                "audio level: $lastLevel to $level , diff: $diff"
            )
        }

        lastLevel = level

        if (audioTrigger) {
            if (MyDebug.LOG) Log.d(TAG, "audio trigger")
            // need to run on UI thread so that this function returns quickly (otherwise we'll have lag in processing the audio)
            // but also need to check we're not currently taking a photo or on timer, so we don't repeatedly queue up takePicture() calls, or cancel a timer
            val timeNow = System.currentTimeMillis()
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
            val wantAudioListener = sharedPreferences.getString(
                PreferenceKeys.AUDIO_CONTROL_PREFERENCE_KEY,
                "none"
            ) == "noise"
            if (timeLastAudioTriggerPhoto != -1L && timeNow - timeLastAudioTriggerPhoto < 5000) {
                // avoid risk of repeatedly being triggered - as well as problem of being triggered again by the camera's own "beep"!
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "ignore loud noise due to too soon since last audio triggered photo: " + (timeNow - timeLastAudioTriggerPhoto)
                )
            } else if (!wantAudioListener) {
                // just in case this is a callback from an AudioListener before it's been freed (e.g., if there's a loud noise when exiting settings after turning the option off
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "ignore loud noise due to audio listener option turned off"
                )
            } else {
                if (MyDebug.LOG) Log.d(TAG, "audio trigger from loud noise")
                timeLastAudioTriggerPhoto = timeNow
                mainActivity.audioTrigger()
            }
        }
    }

    companion object {
        private const val TAG = "MyAudioTriggerLstnrCb"
    }
}