/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller.capabilities

import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SizeF
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.cameracontroller.CameraController.CameraFeatures
import com.hightechif.openkamera.cameracontroller.CameraController.CameraFeaturesCache
import com.hightechif.openkamera.cameracontroller.CameraController.Facing
import com.hightechif.openkamera.cameracontroller.CameraController.RangeSorter
import com.hightechif.openkamera.cameracontroller.CameraController.SizeSorter
import com.hightechif.openkamera.cameracontroller.CameraControllerException
import com.hightechif.openkamera.cameracontroller.extension.Camera2VendorTagsExtension
import com.hightechif.openkamera.cameracontroller.request.Camera2RequestBuilderHelper
import com.hightechif.openkamera.utils.MyDebug
import java.util.Collections
import java.util.Hashtable
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure resolver for CameraCharacteristics, hardware levels, physical camera metadata,
 * zoom ratios, focus modes, stream configurations, and CameraFeatures resolution.
 */
object Camera2CapabilitiesResolver {

    private const val TAG = "Camera2CapResolver"
    const val TONEMAP_LOG_MAX_CURVE_POINTS_C = 64
    const val MAX_EXPO_BRACKETING_N_IMAGES = 5

    data class ResolvedCameraFeatures(
        val cameraFeatures: CameraFeatures,
        val zoomValue1x: Int,
        val fullZoomRatios: List<Int>?,
        val zoomRatios: List<Int>?,
        val supportsFaceDetectModeSimple: Boolean,
        val supportsFaceDetectModeFull: Boolean,
        val rawSize: Size?,
        val wantRaw: Boolean,
        val aeFpsRanges: MutableList<IntArray>,
        val hsFpsRanges: MutableList<IntArray>?,
        val supportedExtensionsZoom: List<Int>?,
        val minimumFocusDistance: Float,
        val initialFocusMode: String?,
        val supportsOpticalStabilization: Boolean,
        val supportsPhotoVideoRecording: Boolean,
        val supportsWhiteBalanceTemperature: Boolean,
        val supportsExposureTime: Boolean,
        val minExposureTime: Long,
        val maxExposureTime: Long,
        val supportsTonemapPresetCurve: Boolean,
        val wantJpegR: Boolean,
        val createdCache: CameraFeaturesCache?
    )

    /**
     * Returns true if the device supports the required hardware level, or better.
     */
    fun isHardwareLevelSupported(c: CameraCharacteristics?, requiredLevel: Int): Boolean {
        if (c == null) return false
        var targetLevel = requiredLevel
        var deviceLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: return false

        // Legacy is a special case
        if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return targetLevel == deviceLevel
        }

        if (deviceLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) {
            deviceLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
        }
        if (targetLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) {
            targetLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
        }

        return targetLevel <= deviceLevel
    }

    /**
     * Resolves human-readable description for a supported hardware level.
     */
    fun getHardwareLevelDescription(level: Int?): String {
        return when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "Unknown: $level"
        }
    }

    /**
     * Resolves the CameraController.Facing direction from characteristics.
     */
    fun getFacing(characteristics: CameraCharacteristics?): CameraController.Facing {
        if (characteristics == null) return CameraController.Facing.FACING_UNKNOWN
        return when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> CameraController.Facing.FACING_FRONT
            CameraCharacteristics.LENS_FACING_BACK -> CameraController.Facing.FACING_BACK
            CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraController.Facing.FACING_EXTERNAL
            else -> CameraController.Facing.FACING_UNKNOWN
        }
    }

    /**
     * Computes horizontal and vertical view angles from CameraCharacteristics.
     */
    fun computeViewAngles(characteristics: CameraCharacteristics): SizeF {
        return try {
            val activeSize =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val focalLengths =
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)

            if (activeSize == null || physicalSize == null || pixelSize == null || focalLengths == null || focalLengths.isEmpty()) {
                return SizeF(55.0f, 43.0f)
            }

            val fracX = activeSize.width().toFloat() / pixelSize.width.toFloat()
            val fracY = activeSize.height().toFloat() / pixelSize.height.toFloat()
            val viewAngleX = Math.toDegrees(
                2.0 * atan2((physicalSize.width * fracX).toDouble(), (2.0 * focalLengths[0]))
            ).toFloat()
            val viewAngleY = Math.toDegrees(
                2.0 * atan2((physicalSize.height * fracY).toDouble(), (2.0 * focalLengths[0]))
            ).toFloat()

            SizeF(viewAngleX, viewAngleY)
        } catch (_: Throwable) {
            SizeF(55.0f, 43.0f)
        }
    }

    /**
     * Resolves supported min and max zoom range for the camera.
     */
    fun resolveZoomRange(
        characteristics: CameraCharacteristics?,
        isPhysicalCamera: Boolean
    ): Pair<Float, Float> {
        if (characteristics == null || isPhysicalCamera) {
            return Pair(0.0f, 0.0f)
        }

        var minZoom = 0.0f
        var maxZoom = 0.0f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val zoomRatioRange =
                    characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoomRatioRange != null) {
                    minZoom = zoomRatioRange.lower
                    maxZoom = zoomRatioRange.upper
                }
            } catch (e: Throwable) {
                if (MyDebug.LOG) Log.e(TAG, "failed to get CONTROL_ZOOM_RATIO_RANGE", e)
            }
        }

        if (minZoom == 0.0f || maxZoom == 0.0f) {
            minZoom = 1.0f
            maxZoom =
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 0.0f
        }

        return Pair(minZoom, maxZoom)
    }

    /**
     * Computes the list of zoom ratios to use, and returns the 0-based index corresponding to 1x zoom.
     */
    fun computeZoomRatios(ratios: MutableList<Int>, minZoom: Float, maxZoom: Float): Int {
        val zoomValue1x: Int
        val scaleFactorC = 2.0.pow(1.0 / 40.0)
        val zoomRatiosAboveOne: MutableList<Int> = ArrayList()
        var zoom = scaleFactorC
        while (zoom < maxZoom - 1.0e-5) {
            val zoomRatio = (zoom * 100 + 1.0e-5).toInt()
            zoomRatiosAboveOne.add(zoomRatio)
            zoom *= scaleFactorC
        }
        val maxZoomRatio = (maxZoom * 100).toInt()
        if (zoomRatiosAboveOne.isEmpty() || zoomRatiosAboveOne[zoomRatiosAboveOne.size - 1] != maxZoomRatio) {
            zoomRatiosAboveOne.add(maxZoomRatio)
        }
        val nStepsAboveOne = zoomRatiosAboveOne.size

        ratios.add((minZoom * 100).toInt())
        if (ratios[0] / 100.0f < minZoom) {
            ratios[0] = ratios[0] + 1
        }

        if (ratios[0] < 100) {
            val nStepsBelowOne = max(1.0, (nStepsAboveOne / 5).toDouble()).toInt()
            val nStepsOne = max(1.0, (nStepsAboveOne / 10).toDouble()).toInt()

            zoom = minZoom.toDouble()
            val scaleFactor = (1.0f / minZoom).toDouble().pow(1.0 / nStepsBelowOne.toDouble())
            repeat(nStepsBelowOne - 1) {
                zoom *= scaleFactor
                val zoomRatio = (zoom * 100).toInt()
                if (zoomRatio > ratios[0]) {
                    ratios.add(zoomRatio)
                }
            }

            zoomValue1x = ratios.size
            repeat(nStepsOne) {
                ratios.add(100)
            }
        } else {
            zoomValue1x = 0
        }

        val nStepsPowerTwo = max(1.0, (0.5f + nStepsAboveOne / 15.0f).toInt().toDouble()).toInt()
        for (zoomRatio in zoomRatiosAboveOne) {
            ratios.add(zoomRatio)
            if (zoomRatio != zoomRatiosAboveOne[zoomRatiosAboveOne.size - 1] && zoomRatio % 100 == 0) {
                val zoomRatioInt = zoomRatio / 100
                if (zoomRatioInt != 0 && (zoomRatioInt and (zoomRatioInt - 1)) == 0) {
                    repeat(nStepsPowerTwo - 1) {
                        ratios.add(zoomRatio)
                    }
                }
            }
        }

        return zoomValue1x
    }

    /**
     * Converts camera2 AF modes to Open Camera focus mode string values.
     */
    fun convertFocusModesToValues(
        supportedFocusModesArr: IntArray,
        minimumFocusDistance: Float
    ): MutableList<String>? {
        if (MyDebug.LOG) {
            Log.d(TAG, "convertFocusModesToValues()")
            Log.d(TAG, "supported_focus_modes_arr: " + supportedFocusModesArr.contentToString())
        }
        if (supportedFocusModesArr.isEmpty()) {
            if (MyDebug.LOG) Log.d(TAG, "no supported focus modes")
            return null
        }
        val supportedFocusModes: MutableList<Int> = ArrayList()
        for (supportedFocusMode in supportedFocusModesArr) {
            supportedFocusModes.add(supportedFocusMode)
        }
        val outputModes: MutableList<String> = ArrayList()
        // also resort as well as converting
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            outputModes.add("focus_mode_auto")
            if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_auto")
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO)) {
            outputModes.add("focus_mode_macro")
            if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_macro")
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            outputModes.add("focus_mode_locked")
            if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_locked")
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
            outputModes.add("focus_mode_infinity")
            if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_infinity")
            if (minimumFocusDistance > 0.0f) {
                outputModes.add("focus_mode_manual2")
                if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_manual2")
            }
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_EDOF)) {
            outputModes.add("focus_mode_edof")
            if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_edof")
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            outputModes.add("focus_mode_continuous_picture")
            if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_continuous_picture")
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            outputModes.add("focus_mode_continuous_video")
            if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_continuous_video")
        }
        return outputModes
    }

    /**
     * Resolves the full CameraFeatures structure and all associated camera state from characteristics.
     */
    @Throws(CameraControllerException::class)
    fun resolveCameraFeatures(
        context: Context,
        characteristics: CameraCharacteristics?,
        cameraIdS: String,
        cameraIdSPhysical: String?,
        facing: Facing?,
        cameraFeaturesCache: CameraFeaturesCache?,
        extensionCharacteristics: CameraExtensionCharacteristics?,
        useFakePrecapture: Boolean,
        allowManualWB: Boolean,
        isSamsungGalaxyS: Boolean,
        isSamsungGalaxyF: Boolean,
        jtvideoValuesSize: Int,
        jtlogValuesSize: Int,
        jtlog2ValuesSize: Int
    ): ResolvedCameraFeatures {
        if (MyDebug.LOG) Log.d(TAG, "resolveCameraFeatures()")
        val cameraFeatures = CameraFeatures()

        if (MyDebug.LOG) {
            val hardwareLevel =
                characteristics?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            Log.d(
                TAG,
                "Hardware Level: " + getHardwareLevelDescription(hardwareLevel)
            )

            val nrModes =
                characteristics?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            Log.d(TAG, "nr_modes:")
            if (nrModes == null) {
                Log.d(TAG, "    none")
            } else {
                for (i in nrModes.indices) {
                    Log.d(TAG, "    " + i + ": " + nrModes[i])
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val capabilities =
                    characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES)
                Log.d(TAG, "capabilities:")
                if (capabilities == null) {
                    Log.d(TAG, "    none")
                } else {
                    for (i in capabilities.indices) {
                        Log.d(TAG, "    " + i + ": " + capabilities[i].mode)
                    }
                }
            }
        }

        val zoomRange = resolveZoomRange(characteristics, cameraIdSPhysical != null)
        val minZoom = zoomRange.first
        val maxZoom = zoomRange.second
        cameraFeatures.isZoomSupported = maxZoom > 0.0f && minZoom > 0.0f
        if (MyDebug.LOG) {
            Log.d(TAG, "min_zoom: $minZoom")
            Log.d(TAG, "max_zoom: $maxZoom")
        }

        var zoomValue1x = 0
        var fullZoomRatios: List<Int>? = null
        var zoomRatios: List<Int>? = null
        if (cameraFeatures.isZoomSupported) {
            val ratios: MutableList<Int> = ArrayList()
            zoomValue1x = computeZoomRatios(ratios, minZoom, maxZoom)

            cameraFeatures.zoomRatios = ratios
            cameraFeatures.maxZoom = (cameraFeatures.zoomRatios?.size ?: 0) - 1
            if (cameraFeatures.maxZoom == 0) {
                cameraFeatures.isZoomSupported = false
            }
            fullZoomRatios = cameraFeatures.zoomRatios
            zoomRatios = cameraFeatures.zoomRatios
            if (MyDebug.LOG) {
                Log.d(TAG, "zoom_ratios: $zoomRatios")
            }
        }

        val faceModes =
            characteristics?.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
        cameraFeatures.supportsFaceDetection = false
        var supportsFaceDetectModeSimple = false
        var supportsFaceDetectModeFull = false
        if (faceModes != null) {
            for (faceMode in faceModes) {
                if (MyDebug.LOG) Log.d(TAG, "face detection mode: $faceMode")
                if (faceMode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE) {
                    cameraFeatures.supportsFaceDetection = true
                    supportsFaceDetectModeSimple = true
                    if (MyDebug.LOG) Log.d(TAG, "supports simple face detection mode")
                } else if (faceMode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL) {
                    cameraFeatures.supportsFaceDetection = true
                    supportsFaceDetectModeFull = true
                    if (MyDebug.LOG) Log.d(TAG, "supports full face detection mode")
                }
            }
        }
        if (cameraFeatures.supportsFaceDetection) {
            val faceCount =
                characteristics?.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0
            if (faceCount <= 0) {
                if (MyDebug.LOG) Log.d(TAG, "can't support face detection, as zero max face count")
                cameraFeatures.supportsFaceDetection = false
                supportsFaceDetectModeSimple = false
                supportsFaceDetectModeFull = false
            }
        }
        if (cameraFeatures.supportsFaceDetection) {
            val values2 =
                characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            var hasFacePriority = false
            if (values2 != null) {
                for (value2 in values2) {
                    if (value2 == CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY) {
                        hasFacePriority = true
                        break
                    }
                }
            }
            if (MyDebug.LOG) Log.d(TAG, "has_face_priority: $hasFacePriority")
            if (!hasFacePriority) {
                if (MyDebug.LOG) Log.d(TAG, "can't support face detection, as no CONTROL_SCENE_MODE_FACE_PRIORITY")
                cameraFeatures.supportsFaceDetection = false
                supportsFaceDetectModeSimple = false
                supportsFaceDetectModeFull = false
            }
        }

        val capabilities =
            characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

        val logicalCharacteristics: CameraCharacteristics?
        val logicalCapabilities: IntArray?
        if (cameraIdSPhysical != null) {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                logicalCharacteristics = manager.getCameraCharacteristics(cameraIdS)
                logicalCapabilities =
                    logicalCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            } catch (e: CameraAccessException) {
                Log.e(TAG, "failed to get logical_characteristics for: $cameraIdS")
                e.printStackTrace()
                throw CameraControllerException()
            }
            if (MyDebug.LOG) Log.d(TAG, "successfully obtained logical camera characteristics")
        } else {
            logicalCharacteristics = characteristics
            logicalCapabilities = capabilities
        }

        var capabilitiesManualPostProcessing = false
        var capabilitiesRaw = false
        var capabilitiesHighSpeedVideo = false
        var capabilities10bit = false
        if (capabilities != null) {
            for (capability in capabilities) {
                if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) {
                    capabilitiesManualPostProcessing = true
                } else if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                    capabilitiesRaw = true
                } else if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    capabilitiesHighSpeedVideo = true
                } else if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) {
                    capabilities10bit = true
                } else if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (MyDebug.LOG) Log.d(TAG, "camera supports ultra high resolution")
                }
            }
        }
        var capabilitiesLogicalMultiCamera = false
        if (logicalCapabilities != null) {
            for (capability in logicalCapabilities) {
                if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    if (MyDebug.LOG) Log.d(TAG, "camera is a logical multi-camera")
                    capabilitiesLogicalMultiCamera = true
                }
            }
        }
        cameraFeatures.supportsBurst = isHardwareLevelSupported(
            characteristics,
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
        )

        if (MyDebug.LOG) {
            Log.d(TAG, "capabilities_manual_post_processing?: $capabilitiesManualPostProcessing")
            Log.d(TAG, "capabilities_raw?: $capabilitiesRaw")
            Log.d(TAG, "supports_burst?: " + cameraFeatures.supportsBurst)
            Log.d(TAG, "capabilities_high_speed_video?: $capabilitiesHighSpeedVideo")
            Log.d(TAG, "capabilities_10bit?: $capabilities10bit")
        }

        val configs: StreamConfigurationMap?
        try {
            configs = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            throw CameraControllerException()
        } catch (e: NullPointerException) {
            e.printStackTrace()
            throw CameraControllerException()
        }

        if (configs == null) {
            throw CameraControllerException()
        }

        val cameraPictureSizes = configs.getOutputSizes(ImageFormat.JPEG)

        cameraFeatures.supportsJpegR = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && capabilities10bit) {
            var debugTime: Long = 0
            if (MyDebug.LOG) {
                debugTime = System.currentTimeMillis()
            }

            val jpegRCameraPictureSizes = configs.getOutputSizes(ImageFormat.JPEG_R)
            if (jpegRCameraPictureSizes != null) {
                if (MyDebug.LOG) Log.d(TAG, "JPEG_R sizes: " + jpegRCameraPictureSizes.contentToString())
                cameraFeatures.supportsJpegR = true
                if (!sizeSubset(cameraPictureSizes, jpegRCameraPictureSizes)) {
                    if (MyDebug.LOG) Log.d(TAG, "don't support JPEG_R: some picture sizes not supported")
                    cameraFeatures.supportsJpegR = false
                }

                if (cameraFeatures.supportsJpegR) {
                    val profiles =
                        characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                    if (profiles == null) {
                        if (MyDebug.LOG) Log.d(TAG, "don't support JPEG_R: no DynamicRangeProfiles")
                        cameraFeatures.supportsJpegR = false
                    } else if (!profiles.supportedProfiles.contains(DynamicRangeProfiles.HLG10)) {
                        if (MyDebug.LOG) Log.d(TAG, "don't support JPEG_R: no HLG10")
                        cameraFeatures.supportsJpegR = false
                    }
                }
            } else {
                if (MyDebug.LOG) Log.d(TAG, "JPEG_R not supported")
            }

            if (MyDebug.LOG) Log.d(TAG, "time for jpeg_r testing: " + (System.currentTimeMillis() - debugTime))
        }

        cameraFeatures.pictureSizes = ArrayList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cameraPictureSizesHires = configs.getHighResolutionOutputSizes(ImageFormat.JPEG)
            if (cameraPictureSizesHires != null) {
                for (cameraSize in cameraPictureSizesHires) {
                    if (MyDebug.LOG) Log.d(TAG, "high resolution picture size: " + cameraSize.width + " x " + cameraSize.height)
                    var found = false
                    if (cameraPictureSizes != null) {
                        for (sz in cameraPictureSizes) {
                            if (sz == cameraSize) {
                                found = true
                                break
                            }
                        }
                    }
                    if (!found) {
                        if (MyDebug.LOG) Log.d(TAG, "high resolution [non-burst] picture size: " + cameraSize.width + " x " + cameraSize.height)
                        val size = CameraController.Size(cameraSize.width, cameraSize.height)
                        size.supportsBurst = false
                        cameraFeatures.pictureSizes.add(size)
                    }
                }

                if (cameraFeatures.supportsJpegR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val cameraPictureSizesHiresJpegR = configs.getHighResolutionOutputSizes(ImageFormat.JPEG_R)
                    if (!sizeSubset(cameraPictureSizesHires, cameraPictureSizesHiresJpegR)) {
                        if (MyDebug.LOG) Log.d(TAG, "don't support JPEG_R: some high resolution (non-burst) picture sizes not supported")
                        cameraFeatures.supportsJpegR = false
                    }
                }
            }
        }
        if (cameraPictureSizes == null) {
            Log.e(TAG, "no picture sizes returned by getOutputSizes")
            throw CameraControllerException()
        } else {
            for (cameraSize in cameraPictureSizes) {
                if (MyDebug.LOG) Log.d(TAG, "picture size: " + cameraSize.width + " x " + cameraSize.height)
                cameraFeatures.pictureSizes.add(CameraController.Size(cameraSize.width, cameraSize.height))
            }
        }
        Collections.sort(cameraFeatures.pictureSizes, SizeSorter())

        var rawSize: Size? = null
        var wantRaw = true
        if (capabilitiesRaw) {
            val rawCameraPictureSizes = configs.getOutputSizes(ImageFormat.RAW_SENSOR)
            if (rawCameraPictureSizes == null) {
                if (MyDebug.LOG) Log.d(TAG, "RAW not supported, failed to get RAW_SENSOR sizes")
                wantRaw = false
            } else {
                for (size in rawCameraPictureSizes) {
                    if (rawSize == null || (size.width * size.height > rawSize.width * rawSize.height)) {
                        rawSize = size
                    }
                }
                if (rawSize == null) {
                    if (MyDebug.LOG) Log.d(TAG, "RAW not supported, failed to find a raw size")
                    wantRaw = false
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "raw supported, raw size: " + rawSize.width + " x " + rawSize.height)
                    cameraFeatures.supportsRaw = true
                }
            }
        } else {
            if (MyDebug.LOG) Log.d(TAG, "RAW capability not supported")
            wantRaw = false
        }

        if (MyDebug.LOG) {
            Log.d(TAG, "output_formats: " + configs.outputFormats.contentToString())
        }

        val aeFpsRanges: MutableList<IntArray> = ArrayList()
        for (r in (characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray<Range<Int>>())) {
            aeFpsRanges.add(intArrayOf(r.lower ?: 0, r.upper ?: 0))
        }
        Collections.sort(aeFpsRanges, RangeSorter())
        if (MyDebug.LOG) {
            Log.d(TAG, "Supported AE video fps ranges: ")
            for (f in aeFpsRanges) {
                Log.d(TAG, "   ae range: [" + f[0] + "-" + f[1] + "]")
            }
        }

        val cameraVideoSizes = configs.getOutputSizes(MediaRecorder::class.java)
        cameraFeatures.videoSizes = ArrayList()
        var minFps = 9999
        for (r in aeFpsRanges) {
            minFps = min(minFps.toDouble(), r[0].toDouble()).toInt()
        }
        if (cameraVideoSizes == null) {
            Log.e(TAG, "no video sizes returned by getOutputSizes")
            throw CameraControllerException()
        } else {
            for (cameraSize in cameraVideoSizes) {
                if (cameraSize.width > 4096 || cameraSize.height > 2160) continue

                val mfd = configs.getOutputMinFrameDuration(
                    MediaRecorder::class.java, cameraSize
                )
                val maxFps = ((1.0 / mfd) * 1000000000L).toInt()
                val fr = ArrayList<IntArray>()
                fr.add(intArrayOf(minFps, maxFps))
                val normalVideoSize = CameraController.Size(cameraSize.width, cameraSize.height, fr, false)
                cameraFeatures.videoSizes.add(normalVideoSize)
                if (MyDebug.LOG) {
                    Log.d(TAG, "normal video size: $normalVideoSize")
                }
            }
        }
        Collections.sort(cameraFeatures.videoSizes, SizeSorter())

        var hsFpsRanges: MutableList<IntArray>? = null
        if (capabilitiesHighSpeedVideo && cameraIdSPhysical == null) {
            hsFpsRanges = ArrayList()
            cameraFeatures.videoSizesHighSpeed = ArrayList()

            for (r in configs.highSpeedVideoFpsRanges) {
                if (r.lower != r.upper) {
                    if (MyDebug.LOG) Log.d(TAG, "skip high speed video fps range: $r")
                    continue
                }
                hsFpsRanges.add(intArrayOf(r.lower, r.upper))
            }
            Collections.sort(hsFpsRanges, RangeSorter())
            if (MyDebug.LOG) {
                Log.d(TAG, "Supported high speed video fps ranges: ")
                for (f in hsFpsRanges) {
                    Log.d(TAG, "   hs range: [" + f[0] + "-" + f[1] + "]")
                }
            }

            val cameraVideoSizesHighSpeed = configs.highSpeedVideoSizes
            for (cameraSize in cameraVideoSizesHighSpeed) {
                val fr = ArrayList<IntArray>()
                for (r in configs.getHighSpeedVideoFpsRangesFor(cameraSize)) {
                    if (r.lower != r.upper) {
                        continue
                    }
                    val thisFpsRange = intArrayOf(r.lower, r.upper)
                    var found = false
                    for (hsFpsRange in hsFpsRanges) {
                        if (hsFpsRange.contentEquals(thisFpsRange)) {
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        if (MyDebug.LOG) Log.e(
                            TAG,
                            "video size " + cameraSize + " has high speed frame rate " + thisFpsRange.contentToString() + " that wasn't returned by configs.getHighSpeedVideoFpsRanges()"
                        )
                        continue
                    }
                    fr.add(thisFpsRange)
                }
                if (cameraSize.width > 4096 || cameraSize.height > 2160) continue

                val hsVideoSize = CameraController.Size(cameraSize.width, cameraSize.height, fr, true)
                if (MyDebug.LOG) {
                    Log.d(TAG, "high speed video size: $hsVideoSize")
                }
                cameraFeatures.videoSizesHighSpeed?.add(hsVideoSize)
            }
            cameraFeatures.videoSizesHighSpeed?.let { Collections.sort(it, SizeSorter()) }
        }

        val cameraPreviewSizes = configs.getOutputSizes(SurfaceTexture::class.java)
        cameraFeatures.previewSizes = ArrayList()
        val displaySize = Point()
        val activity = context as? Activity
        if (activity != null) {
            val display = activity.windowManager.defaultDisplay
            display.getRealSize(displaySize)
            if (displaySize.x < displaySize.y) {
                displaySize[displaySize.y] = displaySize.x
            }
            if (MyDebug.LOG) Log.d(TAG, "display_size: " + displaySize.x + " x " + displaySize.y)
        }
        if (cameraPreviewSizes == null) {
            Log.e(TAG, "no preview sizes returned by getOutputSizes")
            throw CameraControllerException()
        } else {
            for (cameraSize in cameraPreviewSizes) {
                if (MyDebug.LOG) Log.d(TAG, "preview size: " + cameraSize.width + " x " + cameraSize.height)
                if (activity != null && (cameraSize.width > displaySize.x || cameraSize.height > displaySize.y)) {
                    continue
                }
                cameraFeatures.previewSizes.add(CameraController.Size(cameraSize.width, cameraSize.height))
            }
        }

        var createdCache: CameraFeaturesCache? = null
        val useCache = true
        if (extensionCharacteristics == null) {
            // no extension characteristics
        } else if (useCache && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cameraFeaturesCache != null) {
            if (MyDebug.LOG) Log.d(TAG, "read vendor extensions info from cache")
            if (cameraFeaturesCache.supportedExtensions != null) {
                cameraFeatures.supportedExtensions = ArrayList(cameraFeaturesCache.supportedExtensions!!)
            }
            if (cameraFeaturesCache.supportedExtensionsZoom != null) {
                cameraFeatures.supportedExtensionsZoom = ArrayList(cameraFeaturesCache.supportedExtensionsZoom!!)
            }

            if (cameraFeatures.supportedExtensions != null) {
                for (extension in cameraFeatures.supportedExtensions!!) {
                    if (MyDebug.LOG) Log.d(TAG, "vendor extension: $extension")
                    val extensionPictureSizes = cameraFeaturesCache.extensionPictureSizesMap[extension]!!
                    val extensionPreviewSizes = cameraFeaturesCache.extensionPreviewSizesMap[extension]!!
                    val hasPictureResolution = Camera2VendorTagsExtension.updatePictureSizesForExtension(
                        cameraFeatures.pictureSizes, extensionPictureSizes, extension
                    )
                    val hasPreviewResolution = Camera2VendorTagsExtension.updatePreviewSizesForExtension(
                        cameraFeatures.previewSizes, extensionPreviewSizes, extension
                    )
                    if (hasPictureResolution && hasPreviewResolution) {
                        // fine
                    } else {
                        if (MyDebug.LOG) Log.e(TAG, "cached extension not actually supported?!: $extension")
                        cameraFeatures.supportedExtensions?.remove(extension)
                        cameraFeatures.supportedExtensionsZoom?.remove(extension)
                    }
                }
            }
            if (MyDebug.LOG) Log.d(TAG, "done read vendor extensions info from cache")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (MyDebug.LOG) Log.d(TAG, "check for vendor extensions")
            val extensionPictureSizesMap: MutableMap<Int, List<android.util.Size>> = Hashtable()
            val extensionPreviewSizesMap: MutableMap<Int, List<android.util.Size>> = Hashtable()

            var extensions: List<Int>? = null
            try {
                extensions = extensionCharacteristics.supportedExtensions
            } catch (_: Exception) {
                if (MyDebug.LOG) Log.e(TAG, "exception from getSupportedExtensions")
            }
            if (extensions != null) {
                cameraFeatures.supportedExtensions = ArrayList()
                cameraFeatures.supportedExtensionsZoom = ArrayList()
                for (extension in extensions) {
                    if (MyDebug.LOG) Log.d(TAG, "vendor extension: $extension")

                    try {
                        val extensionPictureSizes = extensionCharacteristics.getExtensionSupportedSizes(
                            extension,
                            ImageFormat.JPEG
                        )
                        if (MyDebug.LOG) Log.d(TAG, "    extension_picture_sizes: $extensionPictureSizes")
                        val hasPictureResolution = Camera2VendorTagsExtension.updatePictureSizesForExtension(
                            cameraFeatures.pictureSizes, extensionPictureSizes, extension
                        )

                        val extensionPreviewSizes = extensionCharacteristics.getExtensionSupportedSizes(
                            extension,
                            SurfaceTexture::class.java
                        )
                        if (MyDebug.LOG) Log.d(TAG, "    extension_preview_sizes: $extensionPreviewSizes")
                        val hasPreviewResolution = Camera2VendorTagsExtension.updatePreviewSizesForExtension(
                            cameraFeatures.previewSizes, extensionPreviewSizes, extension
                        )

                        if (hasPictureResolution && hasPreviewResolution) {
                            if (MyDebug.LOG) Log.d(TAG, "    extension is supported: $extension")
                            cameraFeatures.supportedExtensions?.add(extension)
                            extensionPictureSizesMap[extension] = extensionPictureSizes
                            extensionPreviewSizesMap[extension] = extensionPreviewSizes

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val extensionSupportedRequestKeys =
                                    extensionCharacteristics.getAvailableCaptureRequestKeys(extension)
                                for (key in extensionSupportedRequestKeys) {
                                    if (MyDebug.LOG) Log.d(TAG, "    supported capture request key: " + key.name)
                                    if (key === CaptureRequest.CONTROL_ZOOM_RATIO) {
                                        cameraFeatures.supportedExtensionsZoom?.add(extension)
                                    }
                                }
                                val extensionSupportedResultKeys =
                                    extensionCharacteristics.getAvailableCaptureResultKeys(extension)
                                for (key in extensionSupportedResultKeys) {
                                    if (MyDebug.LOG) Log.d(TAG, "    supported capture result key: " + key.name)
                                }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                if (MyDebug.LOG) {
                                    Log.d(
                                        TAG,
                                        "    isCaptureProcessProgressAvailable: " + extensionCharacteristics.isCaptureProcessProgressAvailable(
                                            extension
                                        )
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {
                        if (MyDebug.LOG) Log.e(TAG, "exception trying to query extension: $extension")
                        cameraFeatures.supportedExtensions?.remove(extension)
                        cameraFeatures.supportedExtensionsZoom?.remove(extension)
                        extensionPictureSizesMap.remove(extension)
                        extensionPreviewSizesMap.remove(extension)
                    }
                }
            }

            createdCache = CameraFeaturesCache(
                cameraFeatures,
                extensionPictureSizesMap,
                extensionPreviewSizesMap
            )
            if (MyDebug.LOG) Log.d(TAG, "done check for vendor extensions")
        }

        val supportedExtensionsZoom = cameraFeatures.supportedExtensionsZoom

        if (characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
            val supportedFlashModesArr =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            if (supportedFlashModesArr != null) {
                val supportedFlashModes: MutableList<Int> = ArrayList()
                for (supportedFlashMode in supportedFlashModesArr) {
                    supportedFlashModes.add(supportedFlashMode)
                }

                cameraFeatures.supportedFlashValues = ArrayList()
                cameraFeatures.supportedFlashValues!!.add("flash_off")
                cameraFeatures.supportedFlashValues!!.add("flash_auto")
                cameraFeatures.supportedFlashValues!!.add("flash_on")
                cameraFeatures.supportedFlashValues!!.add("flash_torch")

                if (!useFakePrecapture) {
                    if (supportedFlashModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE)) {
                        cameraFeatures.supportedFlashValues!!.add("flash_red_eye")
                        if (MyDebug.LOG) Log.d(TAG, " supports flash_red_eye")
                    }
                }
            }
        } else if (facing === Facing.FACING_FRONT) {
            cameraFeatures.supportedFlashValues = ArrayList()
            cameraFeatures.supportedFlashValues!!.add("flash_off")
            cameraFeatures.supportedFlashValues!!.add("flash_frontscreen_auto")
            cameraFeatures.supportedFlashValues!!.add("flash_frontscreen_on")
            cameraFeatures.supportedFlashValues!!.add("flash_frontscreen_torch")
        }

        val minimumFocusDistanceF =
            characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val minimumFocusDistance = minimumFocusDistanceF ?: 0.0f
        cameraFeatures.minimumFocusDistance = minimumFocusDistance
        if (MyDebug.LOG) Log.d(TAG, "minimum_focus_distance: $minimumFocusDistance")

        val supportedFocusModes: IntArray? =
            characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (supportedFocusModes != null) {
            cameraFeatures.supportedFocusValues =
                convertFocusModesToValues(supportedFocusModes, minimumFocusDistance)
        }
        if (cameraFeatures.supportedFocusValues != null && cameraFeatures.supportedFocusValues!!.contains("focus_mode_manual2")) {
            cameraFeatures.supportsFocusBracketing = true
        }
        val initialFocusMode = if (cameraFeatures.supportedFocusValues != null) {
            if (cameraFeatures.supportedFocusValues!!.contains("focus_mode_continuous_picture")) {
                "focus_mode_continuous_picture"
            } else {
                cameraFeatures.supportedFocusValues!![0]
            }
        } else {
            null
        }
        if (MyDebug.LOG) Log.d(TAG, "initial_focus_mode: $initialFocusMode")

        cameraFeatures.maxNumFocusAreas =
            characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0

        cameraFeatures.isExposureLockSupported = true
        cameraFeatures.isWhiteBalanceLockSupported = true

        cameraFeatures.isOpticalStabilizationSupported = false
        val supportedOpticalStabilizationModes =
            characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (supportedOpticalStabilizationModes != null) {
            for (supportedOpticalStabilizationMode in supportedOpticalStabilizationModes) {
                if (supportedOpticalStabilizationMode == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                    cameraFeatures.isOpticalStabilizationSupported = true
                    break
                }
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "is_optical_stabilization_supported: " + cameraFeatures.isOpticalStabilizationSupported)
        val supportsOpticalStabilization = cameraFeatures.isOpticalStabilizationSupported

        cameraFeatures.isVideoStabilizationSupported = false
        val supportedVideoStabilizationModes =
            characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        if (supportedVideoStabilizationModes != null) {
            for (supportedVideoStabilizationMode in supportedVideoStabilizationModes) {
                if (supportedVideoStabilizationMode == CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                    cameraFeatures.isVideoStabilizationSupported = true
                    break
                }
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "is_video_stabilization_supported: " + cameraFeatures.isVideoStabilizationSupported)

        cameraFeatures.isPhotoVideoRecordingSupported = isHardwareLevelSupported(
            characteristics,
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
        )
        val supportsPhotoVideoRecording = cameraFeatures.isPhotoVideoRecordingSupported

        val whiteBalanceModes =
            characteristics?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        if (whiteBalanceModes != null) {
            for (value in whiteBalanceModes) {
                if (value == CameraMetadata.CONTROL_AWB_MODE_OFF && capabilitiesManualPostProcessing && allowManualWB) {
                    cameraFeatures.supportsWhiteBalanceTemperature = true
                    cameraFeatures.minTemperature = Camera2RequestBuilderHelper.MIN_WHITE_BALANCE_TEMPERATURE_C
                    cameraFeatures.maxTemperature = Camera2RequestBuilderHelper.MAX_WHITE_BALANCE_TEMPERATURE_C
                }
            }
        }
        val supportsWhiteBalanceTemperature = cameraFeatures.supportsWhiteBalanceTemperature

        var supportsExposureTime = false
        var minExposureTime: Long = 0
        var maxExposureTime: Long = 0
        if (isHardwareLevelSupported(characteristics, CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED)) {
            val isoRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (isoRange != null) {
                cameraFeatures.supportsIsoRange = true
                cameraFeatures.minIso = isoRange.lower
                cameraFeatures.maxIso = isoRange.upper
                val exposureTimeRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                if (exposureTimeRange != null) {
                    cameraFeatures.supportsExposureTime = true
                    cameraFeatures.supportsExpoBracketing = true
                    cameraFeatures.maxExpoBracketingNImages = MAX_EXPO_BRACKETING_N_IMAGES
                    cameraFeatures.minExposureTime = exposureTimeRange.lower
                    cameraFeatures.maxExposureTime = exposureTimeRange.upper
                    if ((isSamsungGalaxyS || isSamsungGalaxyF) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (MyDebug.LOG) Log.d(TAG, "boost max_exposure_time, was: " + cameraFeatures.maxExposureTime)
                        cameraFeatures.maxExposureTime =
                            cameraFeatures.maxExposureTime.coerceAtLeast(1_000_000_000L / 2)
                    }
                    supportsExposureTime = true
                    minExposureTime = cameraFeatures.minExposureTime
                    maxExposureTime = cameraFeatures.maxExposureTime
                }
            }
        }

        val exposureRange = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (exposureRange != null) {
            cameraFeatures.minExposure = exposureRange.lower
            cameraFeatures.maxExposure = exposureRange.upper
        }
        cameraFeatures.exposureStep =
            characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

        cameraFeatures.canDisableShutterSound = true

        var supportsTonemapPresetCurve = false
        if (capabilitiesManualPostProcessing) {
            val tonemapMaxCurvePoints =
                characteristics?.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS)
            if (tonemapMaxCurvePoints != null) {
                if (MyDebug.LOG) Log.d(TAG, "tonemap_max_curve_points: $tonemapMaxCurvePoints")

                val tonemapModes =
                    characteristics?.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                if (tonemapModes == null) {
                    if (MyDebug.LOG) Log.d(TAG, "tonemap_modes is null")
                } else {
                    var supportsTonemapContrastCurve = false
                    for (tonemapMode in tonemapModes) {
                        if (tonemapMode == CaptureRequest.TONEMAP_MODE_PRESET_CURVE) {
                            supportsTonemapPresetCurve = true
                        } else if (tonemapMode == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) {
                            supportsTonemapContrastCurve = true
                        }
                    }
                    if (MyDebug.LOG) {
                        Log.d(TAG, "supports_tonemap_preset_curve: $supportsTonemapPresetCurve")
                        Log.d(TAG, "supports_tonemap_contrast_curve: $supportsTonemapContrastCurve")
                    }

                    if (supportsTonemapContrastCurve) {
                        cameraFeatures.tonemapMaxCurvePoints = tonemapMaxCurvePoints
                        cameraFeatures.supportsTonemapCurve =
                            tonemapMaxCurvePoints >= TONEMAP_LOG_MAX_CURVE_POINTS_C &&
                                tonemapMaxCurvePoints >= jtvideoValuesSize / 2 &&
                                tonemapMaxCurvePoints >= jtlogValuesSize / 2 &&
                                tonemapMaxCurvePoints >= jtlog2ValuesSize / 2
                    }
                }
            } else {
                if (MyDebug.LOG) Log.d(TAG, "tonemap_max_curve_points is null")
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "supports_tonemap_curve?: " + cameraFeatures.supportsTonemapCurve)

        val apertures = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        if (MyDebug.LOG) Log.d(TAG, "apertures: " + apertures?.contentToString())
        if (apertures != null && apertures.size > 1) {
            cameraFeatures.apertures = apertures
        }

        val viewAngle: SizeF = computeViewAngles(characteristics!!)
        cameraFeatures.viewAngleX = viewAngle.width
        cameraFeatures.viewAngleY = viewAngle.height

        if (capabilitiesLogicalMultiCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && logicalCharacteristics != null) {
            cameraFeatures.physicalCameraIds = logicalCharacteristics.physicalCameraIds
            if (MyDebug.LOG) Log.d(TAG, "physical_camera_ids: " + cameraFeatures.physicalCameraIds)
            if (cameraFeatures.physicalCameraIds != null && cameraFeatures.physicalCameraIds!!.size <= 1) {
                cameraFeatures.physicalCameraIds = null
            }
        }

        val wantJpegR = cameraFeatures.supportsJpegR

        return ResolvedCameraFeatures(
            cameraFeatures = cameraFeatures,
            zoomValue1x = zoomValue1x,
            fullZoomRatios = fullZoomRatios,
            zoomRatios = zoomRatios,
            supportsFaceDetectModeSimple = supportsFaceDetectModeSimple,
            supportsFaceDetectModeFull = supportsFaceDetectModeFull,
            rawSize = rawSize,
            wantRaw = wantRaw,
            aeFpsRanges = aeFpsRanges,
            hsFpsRanges = hsFpsRanges,
            supportedExtensionsZoom = supportedExtensionsZoom,
            minimumFocusDistance = minimumFocusDistance,
            initialFocusMode = initialFocusMode,
            supportsOpticalStabilization = supportsOpticalStabilization,
            supportsPhotoVideoRecording = supportsPhotoVideoRecording,
            supportsWhiteBalanceTemperature = supportsWhiteBalanceTemperature,
            supportsExposureTime = supportsExposureTime,
            minExposureTime = minExposureTime,
            maxExposureTime = maxExposureTime,
            supportsTonemapPresetCurve = supportsTonemapPresetCurve,
            wantJpegR = wantJpegR,
            createdCache = createdCache
        )
    }

    /**
     * Returns true if every entry in cameraWidths/cameraHeights is also a member of altCameraWidths/altCameraHeights.
     */
    fun sizeSubset(
        cameraWidths: IntArray?,
        cameraHeights: IntArray?,
        altCameraWidths: IntArray?,
        altCameraHeights: IntArray?
    ): Boolean {
        if (cameraWidths == null && cameraHeights == null) return true
        if (altCameraWidths == null && altCameraHeights == null) return false
        for (i in cameraWidths!!.indices) {
            var found = false
            for (j in altCameraWidths!!.indices) {
                if (cameraWidths[i] == altCameraWidths[j] && cameraHeights!![i] == altCameraHeights!![j]) {
                    found = true
                    break
                }
            }
            if (!found) return false
        }
        return true
    }

    /**
     * Returns true if every entry in cameraSizes is also a member of altCameraSizes.
     */
    fun sizeSubset(
        cameraSizes: Array<Size>?,
        altCameraSizes: Array<Size>?
    ): Boolean {
        var cameraWidths: IntArray? = null
        var cameraHeights: IntArray? = null
        var altCameraWidths: IntArray? = null
        var altCameraHeights: IntArray? = null
        if (cameraSizes != null) {
            cameraWidths = IntArray(cameraSizes.size)
            cameraHeights = IntArray(cameraSizes.size)
            for (i in cameraSizes.indices) {
                cameraWidths[i] = cameraSizes[i].width
                cameraHeights[i] = cameraSizes[i].height
            }
        }
        if (altCameraSizes != null) {
            altCameraWidths = IntArray(altCameraSizes.size)
            altCameraHeights = IntArray(altCameraSizes.size)
            for (i in altCameraSizes.indices) {
                altCameraWidths[i] = altCameraSizes[i].width
                altCameraHeights[i] = altCameraSizes[i].height
            }
        }
        return sizeSubset(cameraWidths, cameraHeights, altCameraWidths, altCameraHeights)
    }

    /**
     * Resolves physical camera IDs if the device supports logical multi-camera.
     */
    fun getPhysicalCameraIds(characteristics: CameraCharacteristics?): Set<String> {
        if (characteristics == null) return emptySet()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val capabilities =
                characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (capabilities != null && capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                return characteristics.physicalCameraIds
            }
        }
        return emptySet()
    }
}
