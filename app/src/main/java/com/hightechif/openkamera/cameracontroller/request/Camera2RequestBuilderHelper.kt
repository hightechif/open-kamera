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

    /**
     * Enforces minimum points for tonemap curves to avoid hardware-specific pipeline bugs.
     */
    fun enforceMinTonemapCurvePoints(inValues: FloatArray, isSamsung: Boolean): FloatArray {
        val minPointsC = if (isSamsung) 32 else 64
        if (inValues.size >= 2 * minPointsC) {
            return inValues
        }
        val points: MutableList<Pair<Float, Float>> = ArrayList()
        for (i in 0 until inValues.size / 2) {
            points.add(Pair(inValues[2 * i], inValues[2 * i + 1]))
        }
        if (points.size < 2) {
            return inValues
        }

        while (points.size < minPointsC) {
            var largestIndx = 0
            var largestDist = 0.0f
            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val dist = p1.first - p0.first
                if (dist > largestDist) {
                    largestIndx = i
                    largestDist = dist
                }
            }
            val p0 = points[largestIndx]
            val p1 = points[largestIndx + 1]
            val midX = 0.5f * (p0.first + p1.first)
            val midY = 0.5f * (p0.second + p1.second)
            points.add(largestIndx + 1, Pair(midX, midY))
        }

        val outValues = FloatArray(2 * points.size)
        for (i in points.indices) {
            val point = points[i]
            outValues[2 * i] = point.first
            outValues[2 * i + 1] = point.second
        }
        return outValues
    }

    fun convertSceneModeToString(value2: Int): String? = when (value2) {
        CameraMetadata.CONTROL_SCENE_MODE_ACTION -> "action"
        CameraMetadata.CONTROL_SCENE_MODE_BARCODE -> "barcode"
        CameraMetadata.CONTROL_SCENE_MODE_BEACH -> "beach"
        CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT -> "candlelight"
        CameraMetadata.CONTROL_SCENE_MODE_DISABLED -> CameraController.SCENE_MODE_DEFAULT
        CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS -> "fireworks"
        CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE -> "landscape"
        CameraMetadata.CONTROL_SCENE_MODE_NIGHT -> "night"
        CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT -> "night-portrait"
        CameraMetadata.CONTROL_SCENE_MODE_PARTY -> "party"
        CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT -> "portrait"
        CameraMetadata.CONTROL_SCENE_MODE_SNOW -> "snow"
        CameraMetadata.CONTROL_SCENE_MODE_SPORTS -> "sports"
        CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO -> "steadyphoto"
        CameraMetadata.CONTROL_SCENE_MODE_SUNSET -> "sunset"
        CameraMetadata.CONTROL_SCENE_MODE_THEATRE -> "theatre"
        else -> null
    }

    fun convertSceneModeToInt(value: String): Int = when (value) {
        "action" -> CameraMetadata.CONTROL_SCENE_MODE_ACTION
        "barcode" -> CameraMetadata.CONTROL_SCENE_MODE_BARCODE
        "beach" -> CameraMetadata.CONTROL_SCENE_MODE_BEACH
        "candlelight" -> CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT
        CameraController.SCENE_MODE_DEFAULT -> CameraMetadata.CONTROL_SCENE_MODE_DISABLED
        "fireworks" -> CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS
        "landscape" -> CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE
        "night" -> CameraMetadata.CONTROL_SCENE_MODE_NIGHT
        "night-portrait" -> CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT
        "party" -> CameraMetadata.CONTROL_SCENE_MODE_PARTY
        "portrait" -> CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT
        "snow" -> CameraMetadata.CONTROL_SCENE_MODE_SNOW
        "sports" -> CameraMetadata.CONTROL_SCENE_MODE_SPORTS
        "steadyphoto" -> CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO
        "sunset" -> CameraMetadata.CONTROL_SCENE_MODE_SUNSET
        "theatre" -> CameraMetadata.CONTROL_SCENE_MODE_THEATRE
        else -> CameraMetadata.CONTROL_SCENE_MODE_DISABLED
    }

    fun convertColorEffectToString(value2: Int): String? = when (value2) {
        CameraMetadata.CONTROL_EFFECT_MODE_AQUA -> "aqua"
        CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD -> "blackboard"
        CameraMetadata.CONTROL_EFFECT_MODE_MONO -> "mono"
        CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE -> "negative"
        CameraMetadata.CONTROL_EFFECT_MODE_OFF -> CameraController.COLOR_EFFECT_DEFAULT
        CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE -> "posterize"
        CameraMetadata.CONTROL_EFFECT_MODE_SEPIA -> "sepia"
        CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE -> "solarize"
        CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD -> "whiteboard"
        else -> null
    }

    fun convertColorEffectToInt(value: String): Int = when (value) {
        "aqua" -> CameraMetadata.CONTROL_EFFECT_MODE_AQUA
        "blackboard" -> CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD
        "mono" -> CameraMetadata.CONTROL_EFFECT_MODE_MONO
        "negative" -> CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE
        CameraController.COLOR_EFFECT_DEFAULT -> CameraMetadata.CONTROL_EFFECT_MODE_OFF
        "posterize" -> CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE
        "sepia" -> CameraMetadata.CONTROL_EFFECT_MODE_SEPIA
        "solarize" -> CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE
        "whiteboard" -> CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD
        else -> CameraMetadata.CONTROL_EFFECT_MODE_OFF
    }

    fun convertWhiteBalanceToString(value2: Int): String? = when (value2) {
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> CameraController.WHITE_BALANCE_DEFAULT
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "cloudy-daylight"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "daylight"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "incandescent"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "shade"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "twilight"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "warm-fluorescent"
        CameraMetadata.CONTROL_AWB_MODE_OFF -> "manual"
        else -> null
    }

    fun convertWhiteBalanceToInt(value: String): Int = when (value) {
        CameraController.WHITE_BALANCE_DEFAULT -> CameraMetadata.CONTROL_AWB_MODE_AUTO
        "cloudy-daylight" -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        "daylight" -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        "fluorescent" -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        "incandescent" -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        "shade" -> CameraMetadata.CONTROL_AWB_MODE_SHADE
        "twilight" -> CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
        "warm-fluorescent" -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
        "manual" -> CameraMetadata.CONTROL_AWB_MODE_OFF
        else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
    }

    fun convertAntiBandingToString(value2: Int): String? = when (value2) {
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO -> CameraController.ANTIBANDING_DEFAULT
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ -> "50hz"
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ -> "60hz"
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF -> "off"
        else -> null
    }

    fun convertAntiBandingToInt(value: String): Int = when (value) {
        CameraController.ANTIBANDING_DEFAULT -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        "50hz" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
        "60hz" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
        "off" -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
        else -> CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
    }

    fun convertEdgeModeToString(value2: Int): String? = when (value2) {
        CameraMetadata.EDGE_MODE_FAST -> "edge_mode_fast"
        CameraMetadata.EDGE_MODE_HIGH_QUALITY -> "edge_mode_high_quality"
        CameraMetadata.EDGE_MODE_OFF -> "edge_mode_off"
        CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG -> "edge_mode_zero_shutter_lag"
        else -> null
    }

    fun convertEdgeModeToInt(value: String): Int = when (value) {
        "edge_mode_fast", CameraController.EDGE_MODE_DEFAULT -> CameraMetadata.EDGE_MODE_FAST
        "edge_mode_high_quality" -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
        "edge_mode_off" -> CameraMetadata.EDGE_MODE_OFF
        "edge_mode_zero_shutter_lag" -> CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG
        else -> CameraMetadata.EDGE_MODE_FAST
    }

    fun convertNoiseReductionModeToString(value2: Int): String? = when (value2) {
        CameraMetadata.NOISE_REDUCTION_MODE_FAST -> "noise_reduction_mode_fast"
        CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "noise_reduction_mode_high_quality"
        CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL -> "noise_reduction_mode_minimal"
        CameraMetadata.NOISE_REDUCTION_MODE_OFF -> "noise_reduction_mode_off"
        CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "noise_reduction_mode_zero_shutter_lag"
        else -> null
    }

    fun convertNoiseReductionModeToInt(value: String): Int = when (value) {
        "noise_reduction_mode_fast", CameraController.NOISE_REDUCTION_MODE_DEFAULT -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
        "noise_reduction_mode_high_quality" -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        "noise_reduction_mode_minimal" -> CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
        "noise_reduction_mode_off" -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
        "noise_reduction_mode_zero_shutter_lag" -> CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG
        else -> CameraMetadata.NOISE_REDUCTION_MODE_FAST
    }

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
