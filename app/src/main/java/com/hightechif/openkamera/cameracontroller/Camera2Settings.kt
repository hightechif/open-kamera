package com.hightechif.openkamera.cameracontroller

import android.graphics.Rect
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.TonemapCurve
import android.location.Location
import android.os.Build
import android.util.Log
import android.util.Range
import androidx.exifinterface.media.ExifInterface
import com.hightechif.openkamera.utils.MyDebug
import java.util.Locale
import kotlin.math.ln1p
import kotlin.math.pow

/** Keeps track of the settings (keys) that we wish to set for the various CaptureRequests, along
 *  with methods to actually set these keys.
 */
class Camera2Settings internal constructor(private val cameraController: CameraController2) {
    companion object {
        private const val TAG = "Camera2Settings"
    }

    // keys that we need to store, to pass to the stillBuilder, but doesn't need to be passed to previewBuilder (should set sensible defaults)
    var rotation: Int = 0
    var location: Location? = null
    var jpegQuality: Byte = 90

    // keys that we have passed to the previewBuilder, that we need to store to also pass to the stillBuilder (should set sensible defaults, or use a has_ boolean if we don't want to set a default)
    var sceneMode: Int = CameraMetadata.CONTROL_SCENE_MODE_DISABLED
    var colorEffect: Int = CameraMetadata.CONTROL_EFFECT_MODE_OFF
    var whiteBalance: Int = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var hasDefaultColorCorrection = false
    private var defaultColorCorrection: Int? = null
    var hasAntibanding = false
    var antibanding: Int = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
    var hasEdgeMode = false
    var edgeMode: Int = CameraMetadata.EDGE_MODE_FAST
    private var hasDefaultEdgeMode = false
    private var defaultEdgeMode: Int? = null
    var hasNoiseReductionMode = false
    var noiseReductionMode: Int = CameraMetadata.NOISE_REDUCTION_MODE_FAST
    private var hasDefaultNoiseReductionMode = false
    private var defaultNoiseReductionMode: Int? = null
    var whiteBalanceTemperature: Int = 5000 // used for white_balance == CONTROL_AWB_MODE_OFF
    var flashValue: String = "flash_off"
    var hasIso = false
    var iso: Int = 0
    var exposureTime: Long = CameraController.EXPOSURE_TIME_DEFAULT
    var hasAperture = false
    var aperture: Float = 0f
    var hasControlZoomRatio = false // zoom for Android 11+
    var controlZoomRatio: Float = 1.0f // zoom for Android 11+

    // zoom for older Android versions; no need for has_scalar_crop_region, as we can set to null instead
    var scalarCropRegion: Rect? = null
    var hasAeExposureCompensation = false
    var aeExposureCompensation: Int = 0
    var hasAfMode = false
    var afMode: Int = CaptureRequest.CONTROL_AF_MODE_AUTO

    // actual value passed to camera device (set to 0.0 if in infinity mode)
    var focusDistance: Float = 0f

    // saved setting when in manual mode (so if user switches to infinity mode and back, we'll still remember the manual focus distance)
    var focusDistanceManual: Float = 0f
    var aeLock = false
    var wbLock = false
    var afRegions: Array<MeteringRectangle>? =
        null // no need for has_af_regions, as we can set to null instead
    var aeRegions: Array<MeteringRectangle>? =
        null // no need for has_ae_regions, as we can set to null instead
    var hasFaceDetectMode = false
    var faceDetectMode: Int = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
    private var defaultOpticalStabilization: Int? = null
    var videoStabilization = false
    var tonemapProfile: CameraController.TonemapProfile =
        CameraController.TonemapProfile.TONEMAPPROFILE_OFF
    var logProfileStrength: Float = 0f // for TONEMAPPROFILE_LOG
    var gammaProfile: Float = 0f // for TONEMAPPROFILE_GAMMA
    private var defaultTonemapMode: Int? =
        null // since we don't know what a device's tonemap mode is, we save it so we can switch back to it
    var aeTargetFpsRange: Range<Int>? = null
    var sensorFrameDuration: Long = 0

    private val isSamsung: Boolean = Build.MANUFACTURER.lowercase(Locale.US).contains("samsung")
    private val isSamsungS7: Boolean = Build.MODEL.lowercase(Locale.US).contains("sm-g93")

    init {
        if (MyDebug.LOG) {
            Log.d(TAG, "isSamsung: $isSamsung")
            Log.d(TAG, "isSamsungS7: $isSamsungS7")
        }
    }

    fun getExifOrientation(): Int {
        var exifOrientation = ExifInterface.ORIENTATION_NORMAL
        when ((rotation + 360) % 360) {
            0 -> exifOrientation = ExifInterface.ORIENTATION_NORMAL
            90 -> exifOrientation =
                if (cameraController.facing == CameraController.Facing.FACING_FRONT)
                    ExifInterface.ORIENTATION_ROTATE_270
                else
                    ExifInterface.ORIENTATION_ROTATE_90

            180 -> exifOrientation = ExifInterface.ORIENTATION_ROTATE_180
            270 -> exifOrientation =
                if (cameraController.facing == CameraController.Facing.FACING_FRONT)
                    ExifInterface.ORIENTATION_ROTATE_90
                else
                    ExifInterface.ORIENTATION_ROTATE_270

            else -> {
                // leave exifOrientation unchanged
                if (MyDebug.LOG) Log.e(TAG, "unexpected rotation: $rotation")
            }
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "rotation: $rotation")
            Log.d(TAG, "exif_orientation: $exifOrientation")
        }
        return exifOrientation
    }

    fun setupBuilder(builder: CaptureRequest.Builder?, isStill: Boolean) {
        if (builder == null) return

        if (!cameraController.isExtensionSession) {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        }

        setSceneMode(builder)
        setColorEffect(builder)
        setWhiteBalance(builder)
        setAntiBanding(builder)
        setAEMode(builder, isStill)
        setControlZoomRatio(builder)
        setCropRegion(builder)
        setExposureCompensation(builder)
        setFocusMode(builder)
        setFocusDistance(builder)
        setAutoExposureLock(builder)
        setAutoWhiteBalanceLock(builder)
        setAFRegions(builder)
        setAERegions(builder)
        setFaceDetectMode(builder)
        setRawMode(builder)
        setStabilization(builder)
        setTonemapProfile(builder)

        if (isStill) {
            if (location != null && !cameraController.isExtensionSession) {
                // JPEG_GPS_LOCATION not supported for camera extensions
                builder.set(CaptureRequest.JPEG_GPS_LOCATION, location)
            }
            builder.set(CaptureRequest.JPEG_ORIENTATION, rotation)
            builder.set(CaptureRequest.JPEG_QUALITY, jpegQuality)
        }

        setEdgeMode(builder)
        setNoiseReductionMode(builder)

        if (MyDebug.LOG) {
            if (isStill) {
                val nrMode = builder.get(CaptureRequest.NOISE_REDUCTION_MODE)
                Log.d(TAG, "nr_mode: " + (nrMode ?: "null"))
                val edgeMode1 = builder.get(CaptureRequest.EDGE_MODE)
                Log.d(TAG, "edge_mode: " + (edgeMode1 ?: "null"))
                val controlMode = builder.get(CaptureRequest.CONTROL_MODE)
                Log.d(TAG, "control_mode: " + (controlMode ?: "null"))
                val sceneMode1 = builder.get(CaptureRequest.CONTROL_SCENE_MODE)
                Log.d(TAG, "scene_mode: " + (sceneMode1 ?: "null"))
                val ccMode = builder.get(CaptureRequest.COLOR_CORRECTION_MODE)
                Log.d(TAG, "cc_mode: " + (ccMode ?: "null"))
                val ccaMode = builder.get(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE)
                Log.d(TAG, "cca_mode: " + (ccaMode ?: "null"))
            }
        }
    }

    fun setSceneMode(builder: CaptureRequest.Builder?): Boolean {
        if (builder == null) return false
        if (MyDebug.LOG) {
            Log.d(TAG, "setSceneMode")
            Log.d(TAG, "builder: $builder")
            Log.d(TAG, "has_face_detect_mode: $hasFaceDetectMode")
        }

        if (cameraController.isExtensionSession) {
            return false
        }

        val currentMode = builder.get(CaptureRequest.CONTROL_MODE)
        val currentSceneMode = builder.get(CaptureRequest.CONTROL_SCENE_MODE)
        if (MyDebug.LOG) Log.d(TAG, "current_scene_mode: $currentSceneMode")

        if (hasFaceDetectMode) {
            // face detection mode overrides scene mode
            if (MyDebug.LOG) Log.d(TAG, "setting scene mode for face detection")
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
            builder.set(
                CaptureRequest.CONTROL_SCENE_MODE,
                CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY
            )
            if (currentMode == null || currentMode != CameraMetadata.CONTROL_MODE_USE_SCENE_MODE || currentSceneMode == null || currentSceneMode != CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY) {
                return true
            }
        } else {
            if (MyDebug.LOG) Log.d(TAG, "setting scene mode: $sceneMode")
            val newMode = if (sceneMode == CameraMetadata.CONTROL_SCENE_MODE_DISABLED) {
                CameraMetadata.CONTROL_MODE_AUTO
            } else {
                CameraMetadata.CONTROL_MODE_USE_SCENE_MODE
            }
            builder.set(CaptureRequest.CONTROL_MODE, newMode)
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, sceneMode)
            if (currentMode == null || currentMode != newMode || currentSceneMode == null || currentSceneMode != sceneMode) {
                return true
            }
        }
        return false
    }

    fun setColorEffect(builder: CaptureRequest.Builder?): Boolean {
        if (builder == null) return false
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (builder.get(CaptureRequest.CONTROL_EFFECT_MODE) == null || builder.get(
                CaptureRequest.CONTROL_EFFECT_MODE
            ) != colorEffect
        ) {
            if (MyDebug.LOG) Log.d(TAG, "setting color effect: $colorEffect")
            builder.set(CaptureRequest.CONTROL_EFFECT_MODE, colorEffect)
            return true
        }
        return false
    }

    fun setWhiteBalance(builder: CaptureRequest.Builder?): Boolean {
        if (builder == null) return false
        var changed = false
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (builder.get(CaptureRequest.CONTROL_AWB_MODE) == null || builder.get(
                CaptureRequest.CONTROL_AWB_MODE
            ) != whiteBalance
        ) {
            if (MyDebug.LOG) Log.d(TAG, "setting white balance: $whiteBalance")

            if (hasDefaultColorCorrection) {
                if (builder.get(CaptureRequest.COLOR_CORRECTION_MODE) != null && builder.get(
                        CaptureRequest.COLOR_CORRECTION_MODE
                    ) != defaultColorCorrection
                ) {
                    builder.set(CaptureRequest.COLOR_CORRECTION_MODE, defaultColorCorrection)
                }
                hasDefaultColorCorrection = false
            }

            builder.set(CaptureRequest.CONTROL_AWB_MODE, whiteBalance)
            changed = true
        }
        if (whiteBalance == CameraMetadata.CONTROL_AWB_MODE_OFF) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "setting white balance temperature: $whiteBalanceTemperature"
            )

            if (!hasDefaultColorCorrection) {
                hasDefaultColorCorrection = true
                defaultColorCorrection = builder.get(CaptureRequest.COLOR_CORRECTION_MODE)
                if (MyDebug.LOG) Log.d(TAG, "default_color_correction: $defaultColorCorrection")
            }

            val rggbChannelVector =
                CameraController2.convertTemperatureToRggbVector(whiteBalanceTemperature)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, rggbChannelVector)
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "original color_correction_transform: " + builder.get(CaptureRequest.COLOR_CORRECTION_TRANSFORM)
                )
            }
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
        return changed
    }

    fun setAntiBanding(builder: CaptureRequest.Builder?): Boolean {
        if (builder == null) return false
        var changed = false
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (hasAntibanding) {
            if (builder.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE) == null || builder.get(
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE
                ) != antibanding
            ) {
                if (MyDebug.LOG) Log.d(TAG, "setting antibanding: $antibanding")
                builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antibanding)
                changed = true
            }
        }
        return changed
    }

    fun setEdgeMode(builder: CaptureRequest.Builder?): Boolean {
        if (builder == null) return false
        if (MyDebug.LOG) {
            Log.d(TAG, "setEdgeMode")
            Log.d(TAG, "has_default_edge_mode: $hasDefaultEdgeMode")
            Log.d(TAG, "default_edge_mode: $defaultEdgeMode")
        }
        var changed = false
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (hasEdgeMode) {
            if (!hasDefaultEdgeMode) {
                hasDefaultEdgeMode = true
                defaultEdgeMode = builder.get(CaptureRequest.EDGE_MODE)
                if (MyDebug.LOG) Log.d(TAG, "default_edge_mode: $defaultEdgeMode")
            }
            if (builder.get(CaptureRequest.EDGE_MODE) == null || builder.get(CaptureRequest.EDGE_MODE) != edgeMode) {
                if (MyDebug.LOG) Log.d(TAG, "setting edge_mode: $edgeMode")
                builder.set(CaptureRequest.EDGE_MODE, edgeMode)
                changed = true
            } else {
                if (MyDebug.LOG) Log.d(TAG, "edge_mode was already set: $edgeMode")
            }
        } else if (isSamsungS7) {
            if (MyDebug.LOG) Log.d(TAG, "set EDGE_MODE_OFF")
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        } else if (hasDefaultEdgeMode) {
            if (builder.get(CaptureRequest.EDGE_MODE) != null && builder.get(CaptureRequest.EDGE_MODE) != defaultEdgeMode) {
                builder.set(CaptureRequest.EDGE_MODE, defaultEdgeMode)
                changed = true
            }
        }
        return changed
    }

    fun setNoiseReductionMode(builder: CaptureRequest.Builder?): Boolean {
        if (builder == null) return false
        if (MyDebug.LOG) {
            Log.d(TAG, "setNoiseReductionMode")
            Log.d(TAG, "has_default_noise_reduction_mode: $hasDefaultNoiseReductionMode")
            Log.d(TAG, "default_noise_reduction_mode: $defaultNoiseReductionMode")
        }
        var changed = false
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (hasNoiseReductionMode) {
            if (!hasDefaultNoiseReductionMode) {
                hasDefaultNoiseReductionMode = true
                defaultNoiseReductionMode = builder.get(CaptureRequest.NOISE_REDUCTION_MODE)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "default_noise_reduction_mode: $defaultNoiseReductionMode"
                )
            }
            if (builder.get(CaptureRequest.NOISE_REDUCTION_MODE) == null || builder.get(
                    CaptureRequest.NOISE_REDUCTION_MODE
                ) != noiseReductionMode
            ) {
                if (MyDebug.LOG) Log.d(TAG, "setting noise_reduction_mode: $noiseReductionMode")
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode)
                changed = true
            } else {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "noise_reduction_mode was already set: $noiseReductionMode"
                )
            }
        } else if (isSamsungS7) {
            if (MyDebug.LOG) Log.d(TAG, "set NOISE_REDUCTION_MODE_OFF")
            builder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF
            )
        } else if (hasDefaultNoiseReductionMode) {
            if (builder.get(CaptureRequest.NOISE_REDUCTION_MODE) != null && builder.get(
                    CaptureRequest.NOISE_REDUCTION_MODE
                ) != defaultNoiseReductionMode
            ) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, defaultNoiseReductionMode)
                changed = true
            }
        }
        return changed
    }

    fun setAperture(builder: CaptureRequest.Builder?): Boolean {
        if (builder == null) return false
        if (MyDebug.LOG) Log.d(TAG, "setAperture")
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (hasAperture) {
            if (MyDebug.LOG) Log.d(TAG, "    aperture: $aperture")
            builder.set(CaptureRequest.LENS_APERTURE, aperture)
            return true
        }
        return false
    }

    fun setAEMode(builder: CaptureRequest.Builder?, isStill: Boolean): Boolean {
        if (builder == null) return false
        if (MyDebug.LOG) Log.d(TAG, "setAEMode")

        if (cameraController.isExtensionSession) {
            return false
        }

        if (hasIso) {
            if (MyDebug.LOG) {
                Log.d(TAG, "manual mode")
                Log.d(TAG, "iso: $iso")
                Log.d(TAG, "exposure_time: $exposureTime")
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            var actualExposureTime = exposureTime
            if (!isStill) {
                actualExposureTime =
                    exposureTime.coerceAtMost(CameraController2.MAX_PREVIEW_EXPOSURE_TIME_C)
                if (MyDebug.LOG) Log.d(TAG, "actually using exposure_time of: $actualExposureTime")
            }
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, actualExposureTime)
            if (sensorFrameDuration > 0) {
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, sensorFrameDuration)
            }
            if (flashValue == "flash_torch") {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
        } else {
            if (MyDebug.LOG) {
                Log.d(TAG, "auto mode")
                Log.d(TAG, "flash_value: $flashValue")
            }
            if (aeTargetFpsRange != null) {
                if (MyDebug.LOG) Log.d(TAG, "set ae_target_fps_range: $aeTargetFpsRange")
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, aeTargetFpsRange)
            }

            when (flashValue) {
                "flash_off" -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }

                "flash_auto" -> {
                    builder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                    )
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }

                "flash_on" -> {
                    builder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                    )
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
                }

                "flash_torch" -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                }

                "flash_red_eye" -> {
                    if (cameraController.burstType != CameraController.BurstType.BURSTTYPE_NONE) {
                        builder.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CameraMetadata.CONTROL_AE_MODE_ON
                        )
                    } else {
                        builder.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE
                        )
                    }
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

    fun setControlZoomRatio(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasControlZoomRatio) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, controlZoomRatio)
        }
    }

    fun setCropRegion(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (scalarCropRegion != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, scalarCropRegion)
        }
    }

    fun setExposureCompensation(builder: CaptureRequest.Builder?): Boolean {
        if (builder == null) return false
        if (!hasAeExposureCompensation) return false
        if (hasIso) {
            if (MyDebug.LOG) Log.d(TAG, "don't set exposure compensation in manual iso mode")
            return false
        }
        if (cameraController.isExtensionSession) return false
        if (builder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) == null || aeExposureCompensation != builder.get(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION
            )
        ) {
            if (MyDebug.LOG) Log.d(TAG, "change exposure to $aeExposureCompensation")
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeExposureCompensation)
            return true
        }
        return false
    }

    fun setFocusMode(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (hasAfMode) {
            if (MyDebug.LOG) Log.d(TAG, "change af mode to $afMode")
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        } else {
            if (MyDebug.LOG) Log.d(
                TAG,
                "af mode left at " + builder.get(CaptureRequest.CONTROL_AF_MODE)
            )
        }
    }

    fun setFocusDistance(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (MyDebug.LOG) Log.d(TAG, "change focus distance to $focusDistance")
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else {
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
        }
    }

    fun setAutoExposureLock(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
        }
    }

    fun setAutoWhiteBalanceLock(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, wbLock)
        }
    }

    fun setAFRegions(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (afRegions != null && cameraController.supportsFocusRegions()) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, afRegions)
        }
    }

    fun setAERegions(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (aeRegions != null && cameraController.supportsMetering()) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, aeRegions)
        }
    }

    fun setFaceDetectMode(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (hasFaceDetectMode) {
            builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceDetectMode)
        } else {
            builder.set(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            )
        }
    }

    private fun setRawMode(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (cameraController.isWantRaw && !cameraController.previewIsVideoMode) {
            builder.set(
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON
            )
        }
    }

    fun setStabilization(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (MyDebug.LOG) Log.d(TAG, "setStabilization: $videoStabilization")

        if (cameraController.isExtensionSession) {
            return
        }

        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (videoStabilization) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        if (cameraController.supportsOpticalStabilization()) {
            if (videoStabilization) {
                if (defaultOpticalStabilization == null) {
                    defaultOpticalStabilization =
                        builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "default_optical_stabilization: $defaultOpticalStabilization"
                    )
                }
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
            } else if (defaultOpticalStabilization != null) {
                if (builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE) != null && builder.get(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE
                    ) != defaultOpticalStabilization
                ) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "set optical stabilization back to: $defaultOpticalStabilization"
                    )
                    builder.set(
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        defaultOpticalStabilization
                    )
                }
            }
        }
    }

    private fun getLogProfile(inVal: Float): Float {
        val logA = logProfileStrength
        return (ln1p(logA * inVal) / ln1p(logA))
    }

    private fun getGammaProfile(inVal: Float): Float {
        return inVal.toDouble().pow(1.0 / gammaProfile.toDouble()).toFloat()
    }

    fun setTonemapProfile(builder: CaptureRequest.Builder?) {
        if (builder == null) return
        if (MyDebug.LOG) {
            Log.d(TAG, "setTonemapProfile")
            Log.d(TAG, "tonemap_profile: $tonemapProfile")
            Log.d(TAG, "log_profile_strength: $logProfileStrength")
            Log.d(TAG, "gamma_profile: $gammaProfile")
            Log.d(TAG, "default_tonemap_mode: $defaultTonemapMode")
        }
        var haveTonemapProfile =
            tonemapProfile != CameraController.TonemapProfile.TONEMAPPROFILE_OFF
        if (tonemapProfile == CameraController.TonemapProfile.TONEMAPPROFILE_LOG && logProfileStrength == 0.0f) {
            haveTonemapProfile = false
        } else if (tonemapProfile == CameraController.TonemapProfile.TONEMAPPROFILE_GAMMA && gammaProfile == 0.0f) {
            haveTonemapProfile = false
        }

        if (cameraController.isExtensionSession) {
            // don't set for extensions
        } else if (haveTonemapProfile) {
            if (defaultTonemapMode == null) {
                defaultTonemapMode = builder.get(CaptureRequest.TONEMAP_MODE)
                if (MyDebug.LOG) Log.d(TAG, "default_tonemap_mode: $defaultTonemapMode")
            }

            val usePresetCurve = cameraController.supportsTonemapPresetCurve()
            if (usePresetCurve && tonemapProfile == CameraController.TonemapProfile.TONEMAPPROFILE_REC709) {
                if (MyDebug.LOG) Log.d(TAG, "set TONEMAP_PRESET_CURVE_REC709")
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
                builder.set(
                    CaptureRequest.TONEMAP_PRESET_CURVE,
                    CaptureRequest.TONEMAP_PRESET_CURVE_REC709
                )
            } else if (usePresetCurve && tonemapProfile == CameraController.TonemapProfile.TONEMAPPROFILE_SRGB) {
                if (MyDebug.LOG) Log.d(TAG, "set TONEMAP_PRESET_CURVE_SRGB")
                builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
                builder.set(
                    CaptureRequest.TONEMAP_PRESET_CURVE,
                    CaptureRequest.TONEMAP_PRESET_CURVE_SRGB
                )
            } else {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "handle via TONEMAP_MODE_CONTRAST_CURVE / TONEMAP_CURVE"
                )
                var values: FloatArray? = null
                when (tonemapProfile) {
                    CameraController.TonemapProfile.TONEMAPPROFILE_REC709 -> {
                        val xValues = floatArrayOf(
                            0.0000f, 0.0667f, 0.1333f, 0.2000f,
                            0.2667f, 0.3333f, 0.4000f, 0.4667f,
                            0.5333f, 0.6000f, 0.6667f, 0.7333f,
                            0.8000f, 0.8667f, 0.9333f, 1.0000f
                        )
                        values = FloatArray(2 * xValues.size)
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
                    }

                    CameraController.TonemapProfile.TONEMAPPROFILE_SRGB -> {
                        values = floatArrayOf(
                            0.0000f, 0.0000f, 0.0667f, 0.2864f, 0.1333f, 0.4007f, 0.2000f, 0.4845f,
                            0.2667f, 0.5532f, 0.3333f, 0.6125f, 0.4000f, 0.6652f, 0.4667f, 0.7130f,
                            0.5333f, 0.7569f, 0.6000f, 0.7977f, 0.6667f, 0.8360f, 0.7333f, 0.8721f,
                            0.8000f, 0.9063f, 0.8667f, 0.9389f, 0.9333f, 0.9701f, 1.0000f, 1.0000f
                        )
                    }

                    CameraController.TonemapProfile.TONEMAPPROFILE_LOG,
                    CameraController.TonemapProfile.TONEMAPPROFILE_GAMMA -> {
                        val nValues =
                            if (isSamsung) 32 else CameraController2.TONEMAP_LOG_MAX_CURVE_POINTS_C
                        if (MyDebug.LOG) Log.d(TAG, "n_values: $nValues")

                        values = FloatArray(2 * nValues)
                        for (i in 0 until nValues) {
                            val inVal = i.toFloat() / (nValues - 1.0f)
                            val outVal =
                                if (tonemapProfile == CameraController.TonemapProfile.TONEMAPPROFILE_LOG) getLogProfile(
                                    inVal
                                ) else getGammaProfile(inVal)
                            values[2 * i] = inVal
                            values[2 * i + 1] = outVal
                        }
                    }

                    CameraController.TonemapProfile.TONEMAPPROFILE_JTVIDEO -> {
                        values = cameraController.jtvideoValues
                        if (MyDebug.LOG) Log.d(TAG, "setting JTVideo profile")
                    }

                    CameraController.TonemapProfile.TONEMAPPROFILE_JTLOG -> {
                        values = cameraController.jtlogValues
                        if (MyDebug.LOG) Log.d(TAG, "setting JTLog profile")
                    }

                    CameraController.TonemapProfile.TONEMAPPROFILE_JTLOG2 -> {
                        values = cameraController.jtlog2Values
                        if (MyDebug.LOG) Log.d(TAG, "setting JTLog2 profile")
                    }

                    else -> {}
                }

                if (MyDebug.LOG) Log.d(TAG, "values: ${values?.contentToString()}")
                if (values != null) {
                    builder.set(
                        CaptureRequest.TONEMAP_MODE,
                        CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE
                    )
                    val tonemapCurve = TonemapCurve(values, values, values)
                    builder.set(CaptureRequest.TONEMAP_CURVE, tonemapCurve)
                    cameraController.testUsedTonemapCurve = true
                } else {
                    Log.e(TAG, "unknown log type: $tonemapProfile")
                }
            }
        } else if (defaultTonemapMode != null) {
            builder.set(CaptureRequest.TONEMAP_MODE, defaultTonemapMode)
        }
    }
}
