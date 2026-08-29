/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.repository

import android.net.Uri
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.PhotoResult
import com.hightechif.openkamera.domain.model.RecordedVideo
import kotlinx.coroutines.flow.Flow
import java.io.File

interface IMediaRepository {
    val latestMediaThumbnailFlow: Flow<Uri?>

    suspend fun savePhoto(
        jpegBytes: ByteArray,
        config: CaptureConfig,
        customFilename: String? = null
    ): Result<PhotoResult>

    suspend fun saveRawDng(
        dngBytes: ByteArray,
        config: CaptureConfig,
        customFilename: String? = null
    ): Result<PhotoResult>

    suspend fun createVideoOutputFile(extension: String = "mp4"): Result<File>

    suspend fun finalizeVideoFile(
        file: File,
        durationMs: Long,
        width: Int,
        height: Int
    ): Result<RecordedVideo>

    suspend fun getLatestMediaUri(): Uri?
}
