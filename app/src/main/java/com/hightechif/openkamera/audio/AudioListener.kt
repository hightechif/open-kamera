package com.hightechif.openkamera.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.hightechif.openkamera.utils.MyDebug
import kotlin.math.abs
import kotlin.math.max

/** Sets up a listener to listen for noise level.
 */
internal class AudioListener @RequiresPermission(Manifest.permission.RECORD_AUDIO) constructor(
    private val cb: AudioListenerCallback
) {
    @Volatile
    private var isRunning = true // should be volatile, as used to communicate between threads
    private var bufferSize = -1
    private var ar: AudioRecord? =
        null // modification to ar should always be synchronized (on AudioListener.this), as the ar can be released in the AudioListener's own thread
    private var thread: Thread? = null

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
            //bufferSize = -1; // test
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
                    (this@AudioListener as Object).notifyAll() // probably not needed currently as no thread should be waiting for creation, but just for consistency
                }

                // check initialised
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
                        (this@AudioListener as Object).notifyAll() // again probably not needed, but just in case
                    }
                }

                if (initialized) {
                    val buffer = ShortArray(bufferSize)
                    ar?.startRecording()

                    this.thread = object : Thread() {
                        override fun run() {
                            /*int sample_delay = (1000 * bufferSize) / sampleRate;
                            if( MyDebug.LOG )
                                Log.e(TAG, "sample_delay: " + sample_delay);*/

                            while (isRunning) {
                                /*try{
                                    Thread.sleep(sample_delay);
                                }
                                catch(InterruptedException e) {
                                    MyDebug.logStackTrace(TAG, "InterruptedException from sleep", e);
                                }*/
                                try {
                                    val currentAr = ar
                                    val nRead = currentAr?.read(buffer, 0, bufferSize) ?: -1
                                    if (nRead > 0) {
                                        var averageNoise = 0
                                        var maxNoise = 0
                                        for (i in 0 until nRead) {
                                            val value = abs(buffer[i].toInt())
                                            averageNoise += value
                                            maxNoise = max(maxNoise, value)
                                        }
                                        averageNoise /= nRead
                                        /*if( MyDebug.LOG ) {
                                            Log.d(TAG, "n_read: " + nRead);
                                            Log.d(TAG, "average noise: " + averageNoise);
                                            Log.d(TAG, "max noise: " + maxNoise);
                                        }*/
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
                                (this@AudioListener as Object).notifyAll() // notify in case release() is waiting
                            }
                        }
                    }
                    // n.b., not good practice to start threads in constructors, so we require the caller to call start() instead
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
    fun start() {
        if (MyDebug.LOG)
            Log.d(TAG, "start")
        thread?.start()
    }

    /** Stop listening and release the resources.
     * @param waitUntilDone If true, this method will block until the resource is freed.
     */
    fun release(waitUntilDone: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "release")
            Log.d(TAG, "wait_until_done: $waitUntilDone")
        }
        isRunning = false
        thread = null
        if (waitUntilDone) {
            if (MyDebug.LOG)
                Log.d(TAG, "wait until audio listener is freed")
            synchronized(this@AudioListener) {
                while (ar != null) {
                    if (MyDebug.LOG)
                        Log.d(TAG, "ar still not freed, so wait")
                    try {
                        (this@AudioListener as Object).wait()
                    } catch (e: InterruptedException) {
                        MyDebug.logStackTrace(
                            TAG,
                            "interrupted while waiting for audio recorder to be freed",
                            e
                        )
                    }
                }
            }
            if (MyDebug.LOG)
                Log.d(TAG, "audio listener is now freed")
        }
    }

    companion object {
        private const val TAG = "AudioListener"
    }
}
