/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.usecase

import android.graphics.PointF
import com.hightechif.openkamera.domain.engine.ICameraEngine
import com.hightechif.openkamera.domain.model.CameraFacing
import com.hightechif.openkamera.domain.model.ExposureCompensation
import com.hightechif.openkamera.domain.model.FlashMode
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class AdjustExposureUseCase @Inject constructor(
    private val cameraEngine: ICameraEngine
) {
    val exposureCompensationFlow: StateFlow<ExposureCompensation> = cameraEngine.exposureCompensationFlow

    suspend operator fun invoke(step: Int) {
        cameraEngine.setExposureCompensation(step)
    }
}

class ToggleFlashUseCase @Inject constructor(
    private val cameraEngine: ICameraEngine,
    private val settingsRepository: ISettingsRepository
) {
    suspend operator fun invoke(targetMode: FlashMode? = null): FlashMode {
        val currentMode = settingsRepository.getFlashMode()
        val nextMode = targetMode ?: when (currentMode) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.AUTO
            FlashMode.RED_EYE -> FlashMode.AUTO
        }

        cameraEngine.setFlashMode(nextMode)
        settingsRepository.setFlashMode(nextMode)
        return nextMode
    }
}

class SetZoomUseCase @Inject constructor(
    private val cameraEngine: ICameraEngine
) {
    val currentZoomRatio: StateFlow<Float> = cameraEngine.currentZoomRatio
    val maxZoomRatio: StateFlow<Float> = cameraEngine.maxZoomRatio

    suspend operator fun invoke(zoomRatio: Float) {
        cameraEngine.setZoom(zoomRatio)
    }
}

class TapToFocusUseCase @Inject constructor(
    private val cameraEngine: ICameraEngine
) {
    suspend fun focusAtPoint(point: PointF) {
        cameraEngine.setManualFocus(point)
    }

    suspend fun unlockFocus() {
        cameraEngine.unlockFocus()
    }
}

class SwitchCameraFacingUseCase @Inject constructor(
    private val cameraEngine: ICameraEngine,
    private val settingsRepository: ISettingsRepository
) {
    suspend operator fun invoke(): Result<CameraFacing> {
        val currentFacingStr = settingsRepository.getStringPreference("preference_camera_facing", CameraFacing.BACK.name)
        val currentFacing = try {
            CameraFacing.valueOf(currentFacingStr)
        } catch (_: Exception) {
            CameraFacing.BACK
        }
        val newFacing = if (currentFacing == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK

        cameraEngine.closeCamera()
        val result = cameraEngine.openCamera(newFacing)

        return if (result.isSuccess) {
            settingsRepository.setStringPreference("preference_camera_facing", newFacing.name)
            Result.success(newFacing)
        } else {
            // Attempt to restore previous facing on error
            cameraEngine.openCamera(currentFacing)
            Result.failure(result.exceptionOrNull() ?: IllegalStateException("Failed to switch camera"))
        }
    }
}

class GetCameraCapabilitiesUseCase @Inject constructor(
    private val cameraEngine: ICameraEngine
) {
    val engineState = cameraEngine.engineStateFlow
    val frameMetadata = cameraEngine.frameMetadataFlow
    val maxZoomRatio = cameraEngine.maxZoomRatio
}
