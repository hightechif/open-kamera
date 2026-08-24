/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.hightechif.openkamera.di.IoDispatcher
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.PhotoResult
import com.hightechif.openkamera.domain.model.RecordedVideo
import com.hightechif.openkamera.domain.repository.IMediaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStorageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IMediaRepository {

    private val _latestMediaThumbnailFlow = MutableStateFlow<Uri?>(null)
    override val latestMediaThumbnailFlow: Flow<Uri?> = _latestMediaThumbnailFlow.asStateFlow()

    private val filenameDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun generateFilename(
        prefix: String,
        extension: String,
        customFilename: String?
    ): String {
        return if (!customFilename.isNullOrBlank()) {
            if (customFilename.endsWith(
                    ".$extension",
                    ignoreCase = true
                )
            ) customFilename else "$customFilename.$extension"
        } else {
            val timestamp = filenameDateFormat.format(Date())
            "${prefix}_$timestamp.$extension"
        }
    }

    override suspend fun savePhoto(
        jpegBytes: ByteArray,
        config: CaptureConfig,
        customFilename: String?
    ): Result<PhotoResult> = withContext(ioDispatcher) {
        try {
            val filename = generateFilename("IMG", "jpg", customFilename)
            val mimeType = "image/jpeg"
            val dateEpoch = System.currentTimeMillis()

            val contentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.DATE_ADDED, dateEpoch / 1000)
                put(MediaStore.Images.Media.DATE_TAKEN, dateEpoch)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/OpenKamera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = contentResolver.insert(collectionUri, contentValues)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create MediaStore entry for $filename"))

            contentResolver.openOutputStream(itemUri)?.use { outputStream ->
                outputStream.write(jpegBytes)
                outputStream.flush()
            }
                ?: return@withContext Result.failure(IllegalStateException("Failed to open output stream for $itemUri"))

            // Apply EXIF attributes
            try {
                contentResolver.openFileDescriptor(itemUri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    ExifUtils.writeMetadataToExif(exif, config)
                    exif.saveAttributes()
                }
            } catch (_: Exception) {
                // Non-fatal if EXIF tagging fails on specific devices
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(itemUri, contentValues, null, null)
            }

            // Extract dimensions
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)

            _latestMediaThumbnailFlow.value = itemUri

            Result.success(
                PhotoResult(
                    uri = itemUri,
                    filePath = filename,
                    width = options.outWidth,
                    height = options.outHeight,
                    fileSizeBytes = jpegBytes.size.toLong(),
                    mimeType = mimeType,
                    dateTakenEpochMs = dateEpoch,
                    isRaw = false
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveRawDng(
        dngBytes: ByteArray,
        config: CaptureConfig,
        customFilename: String?
    ): Result<PhotoResult> = withContext(ioDispatcher) {
        try {
            val filename = generateFilename("RAW", "dng", customFilename)
            val mimeType = "image/x-adobe-dng"
            val dateEpoch = System.currentTimeMillis()

            val contentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.DATE_ADDED, dateEpoch / 1000)
                put(MediaStore.Images.Media.DATE_TAKEN, dateEpoch)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/OpenKamera")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = contentResolver.insert(collectionUri, contentValues)
                ?: return@withContext Result.failure(IllegalStateException("Failed to create MediaStore entry for $filename"))

            contentResolver.openOutputStream(itemUri)?.use { outputStream ->
                outputStream.write(dngBytes)
                outputStream.flush()
            }
                ?: return@withContext Result.failure(IllegalStateException("Failed to open output stream for $itemUri"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(itemUri, contentValues, null, null)
            }

            _latestMediaThumbnailFlow.value = itemUri

            Result.success(
                PhotoResult(
                    uri = itemUri,
                    filePath = filename,
                    width = 0,
                    height = 0,
                    fileSizeBytes = dngBytes.size.toLong(),
                    mimeType = mimeType,
                    dateTakenEpochMs = dateEpoch,
                    isRaw = true
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createVideoOutputFile(extension: String): Result<File> =
        withContext(ioDispatcher) {
            try {
                val filename = generateFilename("VID", extension, null)
                val parentDir = File(context.cacheDir, "videos").apply { mkdirs() }
                val file = File(parentDir, filename)
                Result.success(file)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun finalizeVideoFile(
        file: File,
        durationMs: Long,
        width: Int,
        height: Int
    ): Result<RecordedVideo> = withContext(ioDispatcher) {
        try {
            val mimeType = "video/mp4"
            val dateEpoch = System.currentTimeMillis()
            val filename = file.name

            val contentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.DATE_ADDED, dateEpoch / 1000)
                put(MediaStore.Video.Media.DATE_TAKEN, dateEpoch)
                put(MediaStore.Video.Media.DURATION, durationMs)
                put(MediaStore.Video.Media.WIDTH, width)
                put(MediaStore.Video.Media.HEIGHT, height)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/OpenKamera")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = contentResolver.insert(collectionUri, contentValues)
                ?: return@withContext Result.failure(IllegalStateException("Failed to insert video into MediaStore"))

            contentResolver.openOutputStream(itemUri)?.use { outputStream ->
                file.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                outputStream.flush()
            }
                ?: return@withContext Result.failure(IllegalStateException("Failed to write video data to MediaStore"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(itemUri, contentValues, null, null)
            }

            // Cleanup temp file
            file.delete()

            _latestMediaThumbnailFlow.value = itemUri

            Result.success(
                RecordedVideo(
                    uri = itemUri,
                    filePath = filename,
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    fileSizeBytes = file.length(),
                    dateTakenEpochMs = dateEpoch
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getLatestMediaUri(): Uri? = withContext(ioDispatcher) {
        _latestMediaThumbnailFlow.value ?: queryLatestMediaStoreMediaUri()
    }

    private fun queryLatestMediaStoreMediaUri(): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        val collectionUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        try {
            context.contentResolver.query(collectionUri, projection, null, null, sortOrder)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val id = cursor.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(collectionUri, id)
                        _latestMediaThumbnailFlow.value = uri
                        return uri
                    }
                }
        } catch (_: Exception) {
            // Permission or querying failure
        }
        return null
    }
}
