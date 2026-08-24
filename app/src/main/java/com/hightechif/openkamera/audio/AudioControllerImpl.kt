/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaActionSound
import com.hightechif.openkamera.R
import com.hightechif.openkamera.domain.engine.IAudioController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : IAudioController {

    private val mediaActionSound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    private var soundPoolManager: SoundPoolManager? = SoundPoolManager(context).apply {
        initSound()
        try {
            loadSound(R.raw.mybeep)
            loadSound(R.raw.mybeep_hi)
        } catch (_: Exception) {
            // Sound resource may be missing or failed to load
        }
    }

    private var audioListener: AudioListener? = null

    override fun playShutterSound() {
        try {
            mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
        } catch (_: Exception) {
            // Non-fatal if audio service is muted or unavailable
        }
    }

    override fun playTimerBeep() {
        try {
            soundPoolManager?.playSound(R.raw.mybeep)
        } catch (_: Exception) {
            // Non-fatal
        }
    }

    @SuppressLint("MissingPermission")
    override fun startAudioTriggerListener(onAudioLevelThresholdMet: () -> Unit) {
        stopAudioTriggerListener()
        try {
            audioListener = AudioListener(object : AudioListener.AudioListenerCallback {
                override fun onAudio(level: Int) {
                    if (level > 0) {
                        onAudioLevelThresholdMet()
                    }
                }
            })
            audioListener?.start()
        } catch (_: Exception) {
            // Mic permission or recording error
        }
    }

    override fun stopAudioTriggerListener() {
        try {
            audioListener?.release(false)
            audioListener = null
        } catch (_: Exception) {
            // Non-fatal
        }
    }

    override fun release() {
        try {
            mediaActionSound.release()
            soundPoolManager?.releaseSound()
            soundPoolManager = null
            stopAudioTriggerListener()
        } catch (_: Exception) {
            // Non-fatal
        }
    }
}
