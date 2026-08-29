/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.usecase

import com.hightechif.openkamera.domain.engine.CaptureProgress
import com.hightechif.openkamera.domain.engine.IAudioController
import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.engine.IImageProcessor
import com.hightechif.openkamera.domain.model.CaptureConfig
import com.hightechif.openkamera.domain.repository.ILocationRepository
import com.hightechif.openkamera.domain.repository.IMediaRepository
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class CapturePhotoUseCase @Inject constructor(
    private val cameraEngine: ICameraEngine,
    private val imageProcessor: IImageProcessor,
    private val mediaRepository: IMediaRepository,
    private val locationRepository: ILocationRepository,
    private val settingsRepository: ISettingsRepository,
    private val audioController: IAudioController
) {
    operator fun invoke(config: CaptureConfig): Flow<CaptureProgress> = flow {
        audioController.playShutterSound()

        val isGeotaggingEnabled =
            settingsRepository.getBooleanPreference("preference_location", false)
        val location = if (isGeotaggingEnabled) {
            locationRepository.getLastKnownLocation()
        } else {
            null
        }

        val enrichedConfig = config.copy(
            enableRaw = settingsRepository.isRawEnabled(),
            location = location
        )

        cameraEngine.captureStillImage(enrichedConfig).collect { progress ->
            when (progress) {
                is CaptureProgress.Completed -> {
                    emit(CaptureProgress.Processing(90))
                    val saveResult = mediaRepository.savePhoto(progress.jpegBytes, enrichedConfig)
                    if (progress.dngBytes != null && enrichedConfig.enableRaw) {
                        mediaRepository.saveRawDng(progress.dngBytes, enrichedConfig)
                    }

                    if (saveResult.isSuccess) {
                        emit(progress)
                    } else {
                        emit(
                            CaptureProgress.Failed(
                                saveResult.exceptionOrNull()
                                    ?: IllegalStateException("Failed to save media")
                            )
                        )
                    }
                }

                else -> emit(progress)
            }
        }
    }
}
