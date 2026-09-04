/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.cameracontroller.RawImage
import com.hightechif.openkamera.utils.ExifHandler
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Manages media persistence, MediaStore Scoped Storage insertion with IS_PENDING lifecycle,
 * RAW DNG formatting, and EXIF metadata injection.
 */
class MediaPersistenceManager(private val context: Context) {

    companion object {
        private const val TAG = "MediaPersistenceManager"
    }

    private val activity: MainActivity?
        get() = context as? MainActivity

    /**
     * Writes raw bytes to an existing File or Uri destination.
     */
    fun writeBytes(
        data: ByteArray,
        destinationFile: File?,
        destinationUri: Uri?
    ): Boolean {
        var outputStream: OutputStream? = null
        return try {
            outputStream = if (destinationFile != null) {
                FileOutputStream(destinationFile)
            } else if (destinationUri != null) {
                context.contentResolver.openOutputStream(destinationUri)
            } else {
                null
            }

            if (outputStream == null) {
                Log.e(TAG, "No valid output stream for destination")
                return false
            }

            outputStream.write(data)
            outputStream.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write bytes to destination", e)
            false
        } finally {
            try {
                outputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close output stream", e)
            }
        }
    }

    /**
     * Injects or updates EXIF tags on the persisted file.
     */
    fun updateExif(
        request: ImageSaver.Request,
        data: ByteArray?,
        destinationFile: File?,
        destinationUri: Uri?
    ) {
        try {
            if (destinationFile != null) {
                if (data != null) {
                    ExifHandler.setExifFromData(request, data, destinationFile)
                } else {
                    activity?.let { ExifHandler.updateExif(it, request, destinationFile, null) }
                }
            } else if (destinationUri != null) {
                if (data != null) {
                    val pfd = context.contentResolver.openFileDescriptor(destinationUri, "rw")
                    pfd?.use {
                        ExifHandler.setExifFromData(request, data, it.fileDescriptor)
                    }
                } else {
                    activity?.let { ExifHandler.updateExif(it, request, null, destinationUri) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update EXIF tags", e)
        }
    }

    /**
     * Writes a RAW image using DngCreator to the destination stream.
     */
    fun writeRawDng(
        rawImage: RawImage,
        outputStream: OutputStream
    ): Boolean {
        return try {
            rawImage.writeImage(outputStream)
            outputStream.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write DNG raw image", e)
            false
        }
    }

    /**
     * Clears the IS_PENDING flag on Android 10+ MediaStore records once writing is complete.
     */
    fun finalizePendingMediaStoreUri(uri: Uri?) {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, contentValues, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear IS_PENDING flag for $uri", e)
        }
    }
}
