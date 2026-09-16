/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.hightechif.openkamera.utils.MyDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Sets up a listener to listen for noise level.
 */
internal class AudioListener @RequiresPermission(Manifest.permission.RECORD_AUDIO) constructor(
    private val cb: AudioListenerCallback
) {
    @Volatile
    private var isRunning = true
    private var bufferSize = -1
    private var ar: AudioRecord? = null
    private var job: Job? = null
    private val defaultScope = CoroutineScope(Dispatchers.IO)

    interface AudioListenerCallback {
        fun onAudio(level: Int)
    }

    /** Create a new AudioListener. The caller should call the start() method to start listening.
     */
    init {
        if (MyDebug.LOG)
            Log.d(TAG, "new AudioListener")
        val sampleRate = 8000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        try {
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (MyDebug.LOG)
                Log.d(TAG, "buffer_size: $bufferSize")
            if (bufferSize <= 0) {
                if (MyDebug.LOG) {
                    if (bufferSize == AudioRecord.ERROR)
                        Log.e(TAG, "getMinBufferSize returned ERROR")
                    else if (bufferSize == AudioRecord.ERROR_BAD_VALUE)
                        Log.e(TAG, "getMinBufferSize returned ERROR_BAD_VALUE")
                }
            } else {
                synchronized(this@AudioListener) {
                    ar = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                }

                // check initialized
                var initialized = false
                synchronized(this@AudioListener) {
                    val localAr = ar
                    if (localAr != null && localAr.state == AudioRecord.STATE_INITIALIZED) {
                        if (MyDebug.LOG)
                            Log.d(TAG, "audiorecord is initialised")
                        initialized = true
                    } else {
                        Log.e(TAG, "audiorecord failed to initialise")
                        localAr?.release()
                        ar = null
                    }
                }

                if (initialized) {
                    ar?.startRecording()
                }
            }
        } catch (e: Exception) {
            MyDebug.logStackTrace(TAG, "failed to create audiorecord", e)
        }
    }

    /**
     * @return Whether the audio recorder was created successfully.
     */
    fun status(): Boolean {
        val ok: Boolean
        synchronized(this@AudioListener) {
            ok = ar != null
        }
        return ok
    }

    /** Start listening.
     */
    fun start(scope: CoroutineScope = defaultScope) {
        if (MyDebug.LOG)
            Log.d(TAG, "start")
        if (job?.isActive == true) return

        val localBufferSize = bufferSize
        if (localBufferSize <= 0) return
        val buffer = ShortArray(localBufferSize)

        job = scope.launch(Dispatchers.IO) {
            while (coroutineContext.isActive && isRunning) {
                try {
                    val currentAr = ar
                    val nRead = currentAr?.read(buffer, 0, localBufferSize) ?: -1
                    if (nRead > 0) {
                        val averageNoise = calculateAverageNoise(buffer, nRead)
                        cb.onAudio(averageNoise)
                    } else {
                        if (MyDebug.LOG) {
                            Log.d(TAG, "n_read: $nRead")
                            if (nRead == AudioRecord.ERROR_INVALID_OPERATION)
                                Log.e(TAG, "read returned ERROR_INVALID_OPERATION")
                            else if (nRead == AudioRecord.ERROR_BAD_VALUE)
                                Log.e(TAG, "read returned ERROR_BAD_VALUE")
                        }
                    }
                } catch (e: Exception) {
                    MyDebug.logStackTrace(TAG, "failed to read from audiorecord", e)
                }
            }
            if (MyDebug.LOG)
                Log.d(TAG, "stopped running")
            synchronized(this@AudioListener) {
                if (MyDebug.LOG)
                    Log.d(TAG, "release ar")
                ar?.release()
                ar = null
            }
        }
    }

    /** Stop listening and release the resources.
     * @param waitUntilDone If true, this method will block until the resource is freed.
     */
    fun release(waitUntilDone: Boolean = false) {
        if (MyDebug.LOG) {
            Log.d(TAG, "release")
            Log.d(TAG, "wait_until_done: $waitUntilDone")
        }
        isRunning = false
        job?.cancel()
        job = null
        synchronized(this@AudioListener) {
            try {
                ar?.release()
            } catch (_: Exception) {
            }
            ar = null
        }
    }

    companion object {
        private const val TAG = "AudioListener"

        fun calculateAverageNoise(buffer: ShortArray, readCount: Int): Int {
            if (readCount <= 0) return 0
            var sum = 0L
            for (i in 0 until readCount) {
                sum += abs(buffer[i].toInt())
            }
            return (sum / readCount).toInt()
        }

        fun isThresholdMet(noiseLevel: Int, threshold: Int): Boolean {
            return noiseLevel >= threshold
        }

        /**
         * Converts short sample buffer into [com.hightechif.openkamera.domain.model.AudioAmplitudeData] containing RMS and peak decibels.
         */
        fun calculateAmplitudeData(
            buffer: ShortArray,
            readCount: Int
        ): com.hightechif.openkamera.domain.model.AudioAmplitudeData {
            if (readCount <= 0) return com.hightechif.openkamera.domain.model.AudioAmplitudeData()

            var sumSquares = 0.0
            var maxVal = 0

            for (i in 0 until readCount) {
                val sample = buffer[i].toInt()
                sumSquares += (sample * sample).toDouble()
                val absVal = abs(sample)
                if (absVal > maxVal) {
                    maxVal = absVal
                }
            }

            val rms = kotlin.math.sqrt(sumSquares / readCount)
            val maxPossibleRms = 32767.0
            val peakDb = if (rms > 0) {
                (20.0 * kotlin.math.log10(rms / maxPossibleRms)).toFloat().coerceIn(-90.0f, 0.0f)
            } else {
                -90.0f
            }

            return com.hightechif.openkamera.domain.model.AudioAmplitudeData(
                currentRms = rms,
                peakDecibels = peakDb,
                isClipped = maxVal >= 32760,
                sampleTimestampNs = System.nanoTime()
            )
        }
    }
}
