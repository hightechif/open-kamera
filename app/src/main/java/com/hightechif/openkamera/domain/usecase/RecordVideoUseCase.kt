/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.usecase

import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.model.RecordedVideo
import com.hightechif.openkamera.domain.repository.IMediaRepository
import java.io.File
import javax.inject.Inject

class RecordVideoUseCase @Inject constructor(
    private val cameraEngine: ICameraEngine,
    private val mediaRepository: IMediaRepository
) {
    private var activeVideoFile: File? = null
    private var recordingStartTimeMs: Long = 0L

    suspend fun startRecording(): Result<File> {
        val fileResult = mediaRepository.createVideoOutputFile("mp4")
        if (fileResult.isFailure) return fileResult

        val file = fileResult.getOrThrow()
        val engineResult = cameraEngine.startVideoRecording(file)
        return if (engineResult.isSuccess) {
            activeVideoFile = file
            recordingStartTimeMs = System.currentTimeMillis()
            Result.success(file)
        } else {
            Result.failure(
                engineResult.exceptionOrNull()
                    ?: IllegalStateException("Failed to start video recording")
            )
        }
    }

    suspend fun stopRecording(width: Int = 1920, height: Int = 1080): Result<RecordedVideo> {
        val file = activeVideoFile
            ?: return Result.failure(IllegalStateException("No active recording in progress"))
        val durationMs = (System.currentTimeMillis() - recordingStartTimeMs).coerceAtLeast(0L)

        val engineResult = cameraEngine.stopVideoRecording()
        if (engineResult.isFailure) {
            return Result.failure(
                engineResult.exceptionOrNull()
                    ?: IllegalStateException("Failed to stop video recording")
            )
        }

        activeVideoFile = null
        return mediaRepository.finalizeVideoFile(file, durationMs, width, height)
    }
}
