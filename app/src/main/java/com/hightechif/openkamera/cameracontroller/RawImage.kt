/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.hardware.camera2.DngCreator
import android.media.Image
import android.util.Log
import com.hightechif.openkamera.utils.MyDebug
import java.io.IOException
import java.io.OutputStream

/** Wrapper class to store DngCreator and Image.
 */
class RawImage(private val dngCreator: DngCreator, private val image: Image) {
    /** Writes the dng file to the supplied output.
     */
    @Throws(IOException::class)
    fun writeImage(dngOutput: OutputStream) {
        if (MyDebug.LOG) Log.d(TAG, "writeImage")
        try {
            dngCreator.writeImage(dngOutput, image)
        } catch (e: AssertionError) {
            // have had AssertionError from OnePlus 5 on Google Play; rethrow as an IOException so it's handled
            // in the same way
            MyDebug.logStackTrace(TAG, "failed to write SNG image", e)
            throw IOException()
        } catch (e: IllegalStateException) {
            // have had IllegalStateException from Galaxy Note 8 on Google Play; rethrow as an IOException so it's handled
            // in the same way
            MyDebug.logStackTrace(TAG, "failed to write SNG image", e)
            throw IOException()
        }
    }

    /** Closes the image. Must be called to free up resources when no longer needed. After calling
     * this method, this object should not be used.
     */
    fun close() {
        if (MyDebug.LOG) Log.d(TAG, "close")
        image.close()
        dngCreator.close()
    }

    companion object {
        private const val TAG = "RawImage"
    }
}
