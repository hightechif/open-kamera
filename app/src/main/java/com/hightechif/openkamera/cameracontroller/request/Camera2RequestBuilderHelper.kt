/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.request

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import com.hightechif.openkamera.cameracontroller.CameraController
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure helper methods for configuring and applying parameters to Camera2 [CaptureRequest.Builder] instances.
 */
object Camera2RequestBuilderHelper {

    const val MIN_WHITE_BALANCE_TEMPERATURE_C = 1000
    const val MAX_WHITE_BALANCE_TEMPERATURE_C = 15000

    fun getLogProfile(inVal: Float, logStrength: Float): Float {
        return ln1p(logStrength * inVal) / ln1p(logStrength)
    }

    fun getGammaProfile(inVal: Float, gamma: Float): Float {
        return inVal.toDouble().pow(1.0 / gamma.toDouble()).toFloat()
    }

    fun computeTonemapCurveValues(
        profile: CameraController.TonemapProfile,
        logStrength: Float = 0f,
        gamma: Float = 0f,
        isSamsung: Boolean = false,
        maxPoints: Int = 64,
        customCurve: FloatArray? = null
    ): FloatArray? {
        return when (profile) {
            CameraController.TonemapProfile.TONEMAPPROFILE_REC709 -> {
                val xValues = floatArrayOf(
                    0.0000f, 0.0667f, 0.1333f, 0.2000f,
                    0.2667f, 0.3333f, 0.4000f, 0.4667f,
                    0.5333f, 0.6000f, 0.6667f, 0.7333f,
                    0.8000f, 0.8667f, 0.9333f, 1.0000f
                )
                val values = FloatArray(2 * xValues.size)
                var c = 0
                for (xValue in xValues) {
                    val out = if (xValue < 0.018f) {
                        4.5f * xValue
                    } else {
                        (1.099 * xValue.toDouble().pow(0.45) - 0.099).toFloat()
                    }
                    values[c++] = xValue
                    values[c++] = out
                }
                values
            }

            CameraController.TonemapProfile.TONEMAPPROFILE_SRGB -> {
                floatArrayOf(
                    0.0000f, 0.0000f, 0.0667f, 0.2864f, 0.1333f, 0.4007f, 0.2000f, 0.4845f,
                    0.2667f, 0.5532f, 0.3333f, 0.6125f, 0.4000f, 0.6652f, 0.4667f, 0.7130f,
                    0.5333f, 0.7569f, 0.6000f, 0.7977f, 0.6667f, 0.8360f, 0.7333f, 0.8721f,
                    0.8000f, 0.9063f, 0.8667f, 0.9389f, 0.9333f, 0.9701f, 1.0000f, 1.0000f
                )
            }

            CameraController.TonemapProfile.TONEMAPPROFILE_LOG,
            CameraController.TonemapProfile.TONEMAPPROFILE_GAMMA -> {
                val nValues = if (isSamsung) 32 else maxPoints
                val values = FloatArray(2 * nValues)
                for (i in 0 until nValues) {
                    val inVal = i.toFloat() / (nValues - 1.0f)
                    val outVal = if (profile == CameraController.TonemapProfile.TONEMAPPROFILE_LOG) {
                        getLogProfile(inVal, logStrength)
                    } else {
                        getGammaProfile(inVal, gamma)
                    }
                    values[2 * i] = inVal
                    values[2 * i + 1] = outVal
                }
                values
            }

            CameraController.TonemapProfile.TONEMAPPROFILE_JTVIDEO,
            CameraController.TonemapProfile.TONEMAPPROFILE_JTLOG,
            CameraController.TonemapProfile.TONEMAPPROFILE_JTLOG2 -> {
                customCurve
            }

            else -> null
        }
    }

    /**
     * Converts a white balance temperature (in Kelvin) to red, green even, green odd and blue RGGB vector.
     */
    fun convertTemperatureToRggbVector(temperatureKelvin: Int): RggbChannelVector {
        val rggb = convertTemperatureToRggb(temperatureKelvin)
        return RggbChannelVector(rggb[0], rggb[1], rggb[2], rggb[3])
    }

    private fun convertRGBtoGain(value: Float): Float {
        var v = value
        val maxGainC = 10.0f
        if (v < 1.0e-5f) {
            return maxGainC
        }
        v = 1.0f / v
        v = min(maxGainC.toDouble(), v.toDouble()).toFloat()
        return v
    }

    private fun convertGaintoRGB(value: Float): Float {
        var v = value
        if (v <= 1.0f) {
            return 1.0f
        }
        v = 1.0f / v
        return v
    }

    /**
     * Converts a white balance temperature to red, green even, green odd and blue components.
     */
    fun convertTemperatureToRggb(temperatureKelvin: Int): FloatArray {
        val temperature = temperatureKelvin / 100.0f
        var red: Float
        var green: Float
        var blue: Float

        if (temperature <= 66) {
            red = 255f
        } else {
            red = temperature - 60
            red = (329.698727446 * (red.toDouble().pow(-0.1332047592))).toFloat()
            if (red < 0) red = 0f
            if (red > 255) red = 255f
        }

        if (temperature <= 66) {
            green = temperature
            green = (99.4708025861 * ln(green.toDouble()) - 161.1195681661).toFloat()
            if (green < 0) green = 0f
            if (green > 255) green = 255f
        } else {
            green = temperature - 60
            green = (288.1221695283 * (green.toDouble().pow(-0.0755148492))).toFloat()
            if (green < 0) green = 0f
            if (green > 255) green = 255f
        }

        if (temperature >= 66) blue = 255f
        else if (temperature <= 19) blue = 0f
        else {
            blue = temperature - 10
            blue = (138.5177312231 * ln(blue.toDouble()) - 305.0447927307).toFloat()
            if (blue < 0) blue = 0f
            if (blue > 255) blue = 255f
        }

        red /= 255.0f
        green /= 255.0f
        blue /= 255.0f

        red = convertRGBtoGain(red)
        green = convertRGBtoGain(green)
        blue = convertRGBtoGain(blue)

        return floatArrayOf(red, green / 2, green / 2, blue)
    }

    /**
     * Converts RGGB channel components to a white balance temperature in Kelvin.
     */
    fun convertRggbToTemperature(rggb: FloatArray): Int {
        var red = rggb[0]
        val greenEven = rggb[1]
        val greenOdd = rggb[2]
        var blue = rggb[3]
        var green = (greenEven + greenOdd)

        red = convertGaintoRGB(red)
        green = convertGaintoRGB(green)
        blue = convertGaintoRGB(blue)

        red *= 255.0f
        green *= 255.0f
        blue *= 255.0f

        val redI = (red + 0.5f).toInt()
        val greenI = (green + 0.5f).toInt()
        val blueI = (blue + 0.5f).toInt()
        var temperature: Int
        if (redI == blueI) {
            temperature = 6600
        } else if (redI > blueI) {
            // temperature <= 6600
            val tG = (100 * exp((green + 161.1195681661) / 99.4708025861)).toFloat()
            if (blueI == 0) {
                temperature = (tG + 0.5f).toInt()
            } else {
                val tB = (100 * (exp((blue + 305.0447927307) / 138.5177312231) + 10)).toFloat()
                temperature = ((tG + tB) / 2 + 0.5f).toInt()
            }
        } else {
            // temperature >= 6600
            if (redI <= 1 || greenI <= 1) {
                temperature = MAX_WHITE_BALANCE_TEMPERATURE_C
            } else {
                val tR =
                    (100 * ((red / 329.698727446).pow(1.0 / -0.1332047592) + 60.0)).toFloat()
                val tG =
                    (100 * ((green / 288.1221695283).pow(1.0 / -0.0755148492) + 60.0)).toFloat()
                temperature = ((tR + tG) / 2 + 0.5f).toInt()
            }
        }
        temperature = max(temperature.toDouble(), MIN_WHITE_BALANCE_TEMPERATURE_C.toDouble()).toInt()
        temperature = min(temperature.toDouble(), MAX_WHITE_BALANCE_TEMPERATURE_C.toDouble()).toInt()
        return temperature
    }

    fun convertRggbVectorToTemperature(rggbChannelVector: RggbChannelVector): Int {
        return convertRggbToTemperature(
            floatArrayOf(
                rggbChannelVector.red,
                rggbChannelVector.greenEven,
                rggbChannelVector.greenOdd,
                rggbChannelVector.blue
            )
        )
    }

    /**
     * Applies tonemap profile settings to the builder.
     * Returns the resolved default tonemap mode.
     */
    fun applyTonemapProfile(
        builder: CaptureRequest.Builder,
        config: TonemapConfiguration,
        supportsPresetCurve: Boolean,
        isSamsung: Boolean,
        maxPoints: Int = 64,
        onCurveApplied: () -> Unit = {}
    ): Int? {
        var defaultMode = config.defaultTonemapMode
        var haveTonemapProfile = config.profile != CameraController.TonemapProfile.TONEMAPPROFILE_OFF
        if (config.profile == CameraController.TonemapProfile.TONEMAPPROFILE_LOG && config.logProfileStrength == 0.0f) {
            haveTonemapProfile = false
        } else if (config.profile == CameraController.TonemapProfile.TONEMAPPROFILE_GAMMA && config.gammaProfile == 0.0f) {
            haveTonemapProfile = false
        }

        if (haveTonemapProfile) {
            if (defaultMode == null) {
                defaultMode = builder.get(CaptureRequest.TONEMAP_MODE)
            }

            if (supportsPresetCurve && config.profile == CameraController.TonemapProfile.TONEMAPPROFILE_REC709) {
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
                builder.set(
                    CaptureRequest.TONEMAP_PRESET_CURVE,
                    CaptureRequest.TONEMAP_PRESET_CURVE_REC709
                )
            } else if (supportsPresetCurve && config.profile == CameraController.TonemapProfile.TONEMAPPROFILE_SRGB) {
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
                builder.set(
                    CaptureRequest.TONEMAP_PRESET_CURVE,
                    CaptureRequest.TONEMAP_PRESET_CURVE_SRGB
                )
            } else {
                val values = computeTonemapCurveValues(
                    config.profile,
                    config.logProfileStrength,
                    config.gammaProfile,
                    isSamsung,
                    maxPoints,
                    config.customCurveValues
                )
                if (values != null) {
                    builder.set(
                        CaptureRequest.TONEMAP_MODE,
                        CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE
                    )
                    builder.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(values, values, values))
                    onCurveApplied()
                }
            }
        } else if (defaultMode != null) {
            builder.set(CaptureRequest.TONEMAP_MODE, defaultMode)
        }
        return defaultMode
    }

    /**
     * Applies noise reduction mode to the builder.
     * Returns Pair(changed: Boolean, defaultMode: Int?)
     */
    fun applyNoiseReduction(
        builder: CaptureRequest.Builder,
        config: NoiseReductionConfiguration
    ): Pair<Boolean, Int?> {
        var changed = false
        var defaultMode = config.defaultNoiseReductionMode

        if (config.hasNoiseReductionMode) {
            if (defaultMode == null) {
                defaultMode = builder.get(CaptureRequest.NOISE_REDUCTION_MODE)
            }
            if (builder.get(CaptureRequest.NOISE_REDUCTION_MODE) != config.noiseReductionMode) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, config.noiseReductionMode)
                changed = true
            }
        } else if (config.isSamsungS7) {
            builder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF
            )
        } else if (defaultMode != null) {
            if (builder.get(CaptureRequest.NOISE_REDUCTION_MODE) != defaultMode) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, defaultMode)
                changed = true
            }
        }
        return Pair(changed, defaultMode)
    }

    /**
     * Applies edge mode to the builder.
     * Returns Pair(changed: Boolean, defaultMode: Int?)
     */
    fun applyEdgeMode(
        builder: CaptureRequest.Builder,
        config: EdgeModeConfiguration
    ): Pair<Boolean, Int?> {
        var changed = false
        var defaultMode = config.defaultEdgeMode

        if (config.hasEdgeMode) {
            if (defaultMode == null) {
                defaultMode = builder.get(CaptureRequest.EDGE_MODE)
            }
            if (builder.get(CaptureRequest.EDGE_MODE) != config.edgeMode) {
                builder.set(CaptureRequest.EDGE_MODE, config.edgeMode)
                changed = true
            }
        } else if (config.isSamsungS7) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        } else if (defaultMode != null) {
            if (builder.get(CaptureRequest.EDGE_MODE) != defaultMode) {
                builder.set(CaptureRequest.EDGE_MODE, defaultMode)
                changed = true
            }
        }
        return Pair(changed, defaultMode)
    }

    /**
     * Applies stabilization configuration to the builder.
     * Returns the updated default optical stabilization mode.
     */
    fun applyStabilization(
        builder: CaptureRequest.Builder,
        config: StabilizationConfiguration
    ): Int? {
        var defaultOis = config.defaultOpticalStabilization

        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (config.videoStabilization) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )

        if (config.supportsOpticalStabilization) {
            if (config.videoStabilization) {
                if (defaultOis == null) {
                    defaultOis = builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
                }
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
            } else if (defaultOis != null) {
                if (builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE) != defaultOis) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, defaultOis)
                }
            }
        }
        return defaultOis
    }

    /**
     * Applies color correction (White Balance temperature or standard AWB).
     * Returns Pair(changed: Boolean, defaultColorCorrection: Int?)
     */
    fun applyColorCorrection(
        builder: CaptureRequest.Builder,
        config: ColorCorrectionConfiguration
    ): Pair<Boolean, Int?> {
        var changed = false
        var defaultCc = config.defaultColorCorrection

        if (builder.get(CaptureRequest.CONTROL_AWB_MODE) != config.whiteBalance) {
            if (defaultCc != null) {
                if (builder.get(CaptureRequest.COLOR_CORRECTION_MODE) != defaultCc) {
                    builder.set(CaptureRequest.COLOR_CORRECTION_MODE, defaultCc)
                }
                defaultCc = null
            }
            builder.set(CaptureRequest.CONTROL_AWB_MODE, config.whiteBalance)
            changed = true
        }

        if (config.whiteBalance == CameraMetadata.CONTROL_AWB_MODE_OFF) {
            if (defaultCc == null) {
                defaultCc = builder.get(CaptureRequest.COLOR_CORRECTION_MODE)
            }
            val rggbChannelVector = convertTemperatureToRggbVector(config.whiteBalanceTemperature)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, rggbChannelVector)
            val colorSpaceTransform = ColorSpaceTransform(
                intArrayOf(
                    1, 1, 0, 1, 0, 1,
                    0, 1, 1, 1, 0, 1,
                    0, 1, 0, 1, 1, 1
                )
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, colorSpaceTransform)
            changed = true
        }
        return Pair(changed, defaultCc)
    }

    /**
     * Applies manual exposure (ISO, shutter speed, frame duration) or Auto AE parameters.
     */
    fun applyManualExposure(
        builder: CaptureRequest.Builder,
        config: ManualExposureConfiguration,
        maxPreviewExposureTime: Long = 1000000000L / 5
    ): Boolean {
        if (config.hasIso) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, config.iso)
            var actualExposureTime = config.exposureTime
            if (!config.isStill) {
                actualExposureTime = config.exposureTime.coerceAtMost(maxPreviewExposureTime)
            }
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, actualExposureTime)
            if (config.sensorFrameDuration > 0) {
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, config.sensorFrameDuration)
            }
            if (config.flashValue == "flash_torch") {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
        } else {
            if (config.aeTargetFpsRange != null) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.aeTargetFpsRange)
            }
            when (config.flashValue) {
                "flash_off" -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }
                "flash_auto" -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }
                "flash_on" -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }
                "flash_torch" -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                }
                "flash_red_eye" -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE)
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }
                "flash_frontscreen_auto", "flash_frontscreen_on", "flash_frontscreen_torch" -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }
            }
        }
        return true
    }
}
