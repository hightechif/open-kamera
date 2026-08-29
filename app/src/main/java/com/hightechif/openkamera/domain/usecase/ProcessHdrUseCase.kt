/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.usecase

import com.hightechif.openkamera.domain.engine.IImageProcessor
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.model.PhotoResult
import com.hightechif.openkamera.domain.repository.IMediaRepository
import javax.inject.Inject

class ProcessHdrUseCase @Inject constructor(
    private val imageProcessor: IImageProcessor,
    private val mediaRepository: IMediaRepository
) {
    suspend operator fun invoke(
        frames: List<ByteArray>,
        config: CaptureConfig,
        customFilename: String? = null
    ): Result<PhotoResult> {
        val processResult = imageProcessor.processHdr(frames)
        if (processResult.isFailure) {
            return Result.failure(
                processResult.exceptionOrNull() ?: IllegalStateException("HDR processing failed")
            )
        }

        val mergedBytes = processResult.getOrThrow()
        return mediaRepository.savePhoto(mergedBytes, config, customFilename)
    }
}
