/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log
import android.util.SparseIntArray
import com.hightechif.openkamera.utils.MyDebug


/** Manages loading and playing sounds, via SoundPool.
 */
class SoundPoolManager(private val context: Context) {
    private var soundPool: SoundPool? = null
    private var soundIds: SparseIntArray? = null

    fun initSound() {
        if (soundPool == null) {
            if (MyDebug.LOG) Log.d(TAG, "create new soundPool")
            run {
                val audioAttributes = AudioAttributes.Builder()
                    .setLegacyStreamType(AudioManager.STREAM_SYSTEM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                soundPool = SoundPool.Builder()
                    .setMaxStreams(1)
                    .setAudioAttributes(audioAttributes)
                    .build()
            }
            soundIds = SparseIntArray()
        }
    }

    fun releaseSound() {
        if (soundPool != null) {
            if (MyDebug.LOG) Log.d(TAG, "release sound_pool")
            soundPool!!.release()
            soundPool = null
            soundIds = null
        }
    }

    /* Must be called before playSound (allowing enough time to load the sound).
     */
    fun loadSound(resourceId: Int) {
        if (soundPool != null) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "loading sound resource: $resourceId"
            )
            val soundId = soundPool!!.load(context, resourceId, 1)
            if (MyDebug.LOG) Log.d(
                TAG,
                "    loaded sound: $soundId"
            )
            soundIds!!.put(resourceId, soundId)
        }
    }

    /* Must call loadSound first (allowing enough time to load the sound).
     */
    fun playSound(resourceId: Int) {
        if (soundPool != null) {
            if (soundIds!!.indexOfKey(resourceId) < 0) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "resource not loaded: $resourceId"
                )
            } else {
                val soundId = soundIds!![resourceId]
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "play sound: $soundId"
                )
                soundPool!!.play(soundId, 1.0f, 1.0f, 0, 0, 1f)
            }
        }
    }

    companion object {
        private const val TAG = "SoundPoolManager"
    }
}
