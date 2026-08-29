/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hightechif.openkamera.di.DefaultDispatcher
import com.hightechif.openkamera.domain.engine.IImageProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageProcessorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : IImageProcessor {

    private val hdrProcessor: HDRProcessor by lazy {
        HDRProcessor(context, false)
    }

    private val panoramaProcessor: PanoramaProcessor by lazy {
        PanoramaProcessor(context, hdrProcessor)
    }

    override suspend fun processHdr(frames: List<ByteArray>): Result<ByteArray> =
        withContext(defaultDispatcher) {
            try {
                if (frames.isEmpty()) {
                    return@withContext Result.failure(IllegalArgumentException("Frames cannot be empty for HDR processing"))
                }

                if (frames.size == 1) {
                    return@withContext Result.success(frames.first())
                }

                val bitmaps = frames.mapNotNull { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }

                if (bitmaps.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("Failed to decode bitmaps for HDR"))
                }

                val outputStream = ByteArrayOutputStream()
                val resultBitmap = bitmaps.first()
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)

                bitmaps.drop(1).forEach { it.recycle() }

                Result.success(outputStream.toByteArray())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun processPanorama(frames: List<ByteArray>): Result<ByteArray> =
        withContext(defaultDispatcher) {
            try {
                if (frames.isEmpty()) {
                    return@withContext Result.failure(IllegalArgumentException("Frames cannot be empty for Panorama processing"))
                }

                if (frames.size == 1) {
                    return@withContext Result.success(frames.first())
                }

                val bitmaps = frames.mapNotNull { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }

                if (bitmaps.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("Failed to decode bitmaps for Panorama"))
                }

                val outputStream = ByteArrayOutputStream()
                val resultBitmap = bitmaps.first()
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)

                bitmaps.drop(1).forEach { it.recycle() }

                Result.success(outputStream.toByteArray())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun processNoiseReduction(frame: ByteArray): Result<ByteArray> =
        withContext(defaultDispatcher) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                    ?: return@withContext Result.failure(IllegalStateException("Failed to decode bitmap for Noise Reduction"))

                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                bitmap.recycle()

                Result.success(outputStream.toByteArray())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
