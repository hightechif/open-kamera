/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.request

import android.hardware.camera2.CameraMetadata
import android.util.Range
import com.hightechif.openkamera.cameracontroller.CameraController

/**
 * Isolated configuration data classes for Camera2 CaptureRequest building.
 */
data class TonemapConfiguration(
    val profile: CameraController.TonemapProfile = CameraController.TonemapProfile.TONEMAPPROFILE_OFF,
    val logProfileStrength: Float = 0f,
    val gammaProfile: Float = 0f,
    val defaultTonemapMode: Int? = null,
    val customCurveValues: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TonemapConfiguration
        if (profile != other.profile) return false
        if (logProfileStrength != other.logProfileStrength) return false
        if (gammaProfile != other.gammaProfile) return false
        if (defaultTonemapMode != other.defaultTonemapMode) return false
        if (customCurveValues != null) {
            if (other.customCurveValues == null) return false
            if (!customCurveValues.contentEquals(other.customCurveValues)) return false
        } else if (other.customCurveValues != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = profile.hashCode()
        result = 31 * result + logProfileStrength.hashCode()
        result = 31 * result + gammaProfile.hashCode()
        result = 31 * result + (defaultTonemapMode ?: 0)
        result = 31 * result + (customCurveValues?.contentHashCode() ?: 0)
        return result
    }
}

data class NoiseReductionConfiguration(
    val hasNoiseReductionMode: Boolean = false,
    val noiseReductionMode: Int = CameraMetadata.NOISE_REDUCTION_MODE_FAST,
    val defaultNoiseReductionMode: Int? = null,
    val isSamsungS7: Boolean = false
)

data class EdgeModeConfiguration(
    val hasEdgeMode: Boolean = false,
    val edgeMode: Int = CameraMetadata.EDGE_MODE_FAST,
    val defaultEdgeMode: Int? = null,
    val isSamsungS7: Boolean = false
)

data class StabilizationConfiguration(
    val videoStabilization: Boolean = false,
    val defaultOpticalStabilization: Int? = null,
    val supportsOpticalStabilization: Boolean = false
)

data class ColorCorrectionConfiguration(
    val whiteBalance: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO,
    val whiteBalanceTemperature: Int = 5000,
    val defaultColorCorrection: Int? = null
)

data class ManualExposureConfiguration(
    val hasIso: Boolean = false,
    val iso: Int = 0,
    val exposureTime: Long = CameraController.EXPOSURE_TIME_DEFAULT,
    val sensorFrameDuration: Long = 0,
    val flashValue: String = "flash_off",
    val aeTargetFpsRange: Range<Int>? = null,
    val isStill: Boolean = false
)
