/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.utils.MyDebug
import com.hightechif.openkamera.utils.PostProcessing
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Encapsulates bitmap transformation, auto-stabilization, text/GPS watermarking,
 * and format compression for captured photos.
 */
class BitmapPostProcessor(
    private val context: Context,
    private val postProcessing: PostProcessing? = null
) {
    companion object {
        private const val TAG = "BitmapPostProcessor"

        /**
         * Resolves the Android [CompressFormat] corresponding to the requested [ImageSaver.Request.ImageFormat].
         */
        fun getBitmapCompressFormat(imageFormat: ImageSaver.Request.ImageFormat): CompressFormat {
            return when (imageFormat) {
                ImageSaver.Request.ImageFormat.WEBP -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        CompressFormat.WEBP
                    }
                }
                ImageSaver.Request.ImageFormat.PNG -> CompressFormat.PNG
                else -> CompressFormat.JPEG
            }
        }
    }

    private val internalPostProcessing: PostProcessing by lazy {
        postProcessing ?: PostProcessing(context as? MainActivity ?: error("Context must be MainActivity for UI watermarks"))
    }

    /**
     * Applies auto-stabilization, mirroring, date/time/GPS watermarks, and custom text stamps to the input bitmap.
     *
     * @param request The capture request specifying watermarks, angles, and leveling options.
     * @param data Optional raw byte array.
     * @param inputBitmap The in-memory bitmap to process.
     * @param isPreshot True if this is a continuous pre-shot frame.
     * @return The resulting transformed [Bitmap] or null on error.
     */
    fun postProcess(
        request: ImageSaver.Request,
        data: ByteArray?,
        inputBitmap: Bitmap?,
        isPreshot: Boolean = false
    ): Bitmap? {
        if (MyDebug.LOG) Log.d(TAG, "postProcess bitmap: doAutoStabilise=${request.doAutoStabilise}, mirror=${request.mirror}")
        val result = internalPostProcessing.postProcessBitmap(request, data, inputBitmap, isPreshot)
        return result.bitmap
    }

    /**
     * Compresses the provided bitmap into the specified output stream.
     *
     * @param bitmap The bitmap to encode.
     * @param format The target compression format.
     * @param quality Quality level (0-100).
     * @param outputStream Stream to write the compressed bytes into.
     * @return True if compression succeeded.
     */
    fun compress(
        bitmap: Bitmap,
        format: CompressFormat,
        quality: Int,
        outputStream: OutputStream
    ): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "compress bitmap: format=$format, quality=$quality")
        return bitmap.compress(format, quality.coerceIn(1, 100), outputStream)
    }

    /**
     * Helper to encode bitmap directly to a byte array.
     */
    fun compressToByteArray(
        bitmap: Bitmap,
        format: CompressFormat,
        quality: Int
    ): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(bitmap, format, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Rotates or mirrors a bitmap.
     */
    fun transformBitmap(
        bitmap: Bitmap,
        degrees: Float,
        mirror: Boolean
    ): Bitmap {
        if (degrees == 0f && !mirror) return bitmap

        val matrix = Matrix()
        if (mirror) {
            matrix.preScale(-1f, 1f)
        }
        if (degrees != 0f) {
            matrix.postRotate(degrees)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
