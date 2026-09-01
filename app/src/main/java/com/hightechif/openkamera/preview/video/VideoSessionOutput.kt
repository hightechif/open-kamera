/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.video

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.hightechif.openkamera.preview.ApplicationInterface.VideoMethod
import java.io.Closeable
import java.io.IOException

/**
 * Domain model representing a video output target and file descriptor.
 */
data class VideoSessionOutput(
    val videoMethod: VideoMethod = VideoMethod.FILE,
    val videoUri: Uri? = null,
    val videoFilename: String? = null,
    val videoPfdSaf: ParcelFileDescriptor? = null
) : Closeable, AutoCloseable {

    override fun close() {
        if (videoPfdSaf != null) {
            try {
                videoPfdSaf.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
