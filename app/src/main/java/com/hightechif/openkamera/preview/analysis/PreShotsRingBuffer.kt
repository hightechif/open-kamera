/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

import android.graphics.Bitmap
import android.util.Log
import com.hightechif.openkamera.utils.MyDebug
import java.util.Collections

/**
 * Thread-safe fixed-capacity ring buffer for storing pre-capture preview frames with automatic recycling.
 */
class PreShotsRingBuffer(val maxCapacity: Int = 12) {

    companion object {
        private const val TAG = "PreShotsRingBuffer"
    }

    private val bitmaps: MutableList<Bitmap> = Collections.synchronizedList(ArrayList())

    /**
     * Flushes and recycles all stored bitmaps in the buffer.
     */
    fun flush() {
        if (MyDebug.LOG) Log.d(TAG, "PreShotsRingBuffer.flush()")
        synchronized(bitmaps) {
            while (bitmaps.isNotEmpty()) {
                val bm = bitmaps.removeAt(0)
                if (!bm.isRecycled) {
                    bm.recycle()
                }
            }
        }
    }

    /**
     * Adds a new preview bitmap to the buffer, recycling the oldest bitmap if capacity is exceeded.
     */
    fun add(bitmap: Bitmap?) {
        if (bitmap == null) return
        synchronized(bitmaps) {
            while (bitmaps.size >= maxCapacity) {
                val bm = bitmaps.removeAt(0)
                if (!bm.isRecycled) {
                    bm.recycle()
                }
            }
            bitmaps.add(bitmap)
        }
    }

    fun hasBitmaps(): Boolean {
        return bitmaps.isNotEmpty()
    }

    val size: Int
        get() = bitmaps.size

    val nBitmaps: Int
        get() = size

    /**
     * Pops and returns the oldest bitmap in the buffer. Caller takes ownership of recycling.
     */
    fun poll(): Bitmap? {
        synchronized(bitmaps) {
            return if (bitmaps.isNotEmpty()) bitmaps.removeAt(0) else null
        }
    }

    fun get(): Bitmap? = poll()
}
