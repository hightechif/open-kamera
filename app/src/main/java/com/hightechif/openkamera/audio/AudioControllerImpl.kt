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
import com.hightechif.openkamera.di.IoDispatcher
import com.hightechif.openkamera.domain.engine.IAudioController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioControllerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IAudioController {

    private val audioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val mediaActionSound by lazy {
        try {
            MediaActionSound().apply {
                load(MediaActionSound.SHUTTER_CLICK)
                load(MediaActionSound.START_VIDEO_RECORDING)
                load(MediaActionSound.STOP_VIDEO_RECORDING)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal var soundPoolManager: SoundPoolManager? = try {
        SoundPoolManager(context).apply {
            initSound()
            try {
                loadSound(R.raw.mybeep)
                loadSound(R.raw.mybeep_hi)
            } catch (_: Exception) {
                // Sound resource may be missing or failed to load
            }
        }
    } catch (_: Exception) {
        null
    }

    private var audioListener: AudioListener? = null

    override fun playShutterSound() {
        try {
            mediaActionSound?.play(MediaActionSound.SHUTTER_CLICK)
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

    override fun playTimerAlert() {
        try {
            soundPoolManager?.playSound(R.raw.mybeep_hi)
        } catch (_: Exception) {
            // Non-fatal
        }
    }

    @SuppressLint("MissingPermission")
    override fun startAudioTriggerListener(threshold: Int, onAudioLevelThresholdMet: () -> Unit) {
        stopAudioTriggerListener()
        try {
            audioListener = AudioListener(object : AudioListener.AudioListenerCallback {
                override fun onAudio(level: Int) {
                    if (AudioListener.isThresholdMet(level, threshold)) {
                        onAudioLevelThresholdMet()
                    }
                }
            })
            audioListener?.start(audioScope)
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
            mediaActionSound?.release()
            soundPoolManager?.releaseSound()
            soundPoolManager = null
            stopAudioTriggerListener()
            audioScope.cancel()
        } catch (_: Exception) {
            // Non-fatal
        }
    }
}
