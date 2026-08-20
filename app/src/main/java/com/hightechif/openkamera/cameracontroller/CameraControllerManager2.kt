/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import android.util.SizeF
import com.hightechif.openkamera.R
import com.hightechif.openkamera.utils.MyDebug
import kotlin.math.atan2

/** Provides support using Android 5's Camera 2 API
 * android.hardware.camera2.*.
 */
class CameraControllerManager2(private val context: Context) : CameraControllerManager() {
    override val numberOfCameras: Int
        get() {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                return manager.cameraIdList.size
            } catch (e: Throwable) {
                // in theory, we should only get CameraAccessException, but Google Play shows we can get a variety of exceptions
                // from some devices, e.g., AssertionError, IllegalArgumentException, RuntimeException, so just catch everything!
                // We don't want users to experience a crash just because of buggy camera2 drivers - instead the user can switch
                // back to old camera API.
                if (MyDebug.LOG) Log.e(TAG, "exception trying to get camera ids")
                e.printStackTrace()
            }
            return 0
        }

    override fun getFacing(cameraId: Int): CameraController.Facing {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIdS = manager.cameraIdList[cameraId]
            val characteristics = manager.getCameraCharacteristics(cameraIdS)
            when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_FRONT -> return CameraController.Facing.FACING_FRONT
                CameraMetadata.LENS_FACING_BACK -> return CameraController.Facing.FACING_BACK
                CameraMetadata.LENS_FACING_EXTERNAL -> return CameraController.Facing.FACING_EXTERNAL
            }
            Log.e(
                TAG,
                "unknown camera_facing: " + characteristics.get(CameraCharacteristics.LENS_FACING)
            )
        } catch (e: Throwable) {
            // in theory, we should only get CameraAccessException, but Google Play shows we can get a variety of exceptions
            // from some devices, e.g., AssertionError, IllegalArgumentException, RuntimeException, so just catch everything!
            // We don't want users to experience a crash just because of buggy camera2 drivers - instead the user can switch
            // back to old camera API.
            if (MyDebug.LOG) Log.e(TAG, "exception trying to get camera characteristics")
            e.printStackTrace()
        }
        return CameraController.Facing.FACING_UNKNOWN
    }

    override fun getDescription(context: Context?, cameraId: Int): String? {
        if (context == null) return null
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var description: String? = null
        try {
            val cameraIdS = manager.cameraIdList[cameraId]
            description = getDescription(
                null, context, cameraIdS,
                includeType = true,
                includeAngles = false
            )
        } catch (e: Throwable) {
            // see note under isFrontFacing() why we catch anything, not just CameraAccessException
            if (MyDebug.LOG) Log.e(TAG, "exception trying to get camera characteristics")
            e.printStackTrace()
        }
        return description
    }

    override fun getDescription(
        info: CameraInfo?,
        context: Context?,
        cameraIdS: String,
        includeType: Boolean,
        includeAngles: Boolean
    ): String? {
        if (context == null) return null
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            debugTime = System.currentTimeMillis()
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var description = ""
        try {
            val characteristics = manager.getCameraCharacteristics(cameraIdS)
            if (MyDebug.LOG) Log.d(
                TAG,
                "getDescription: time after getCameraCharacteristics: " + (System.currentTimeMillis() - debugTime)
            )

            if (includeType) {
                description =
                    when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                        CameraMetadata.LENS_FACING_FRONT -> context.resources.getString(R.string.front_camera)
                        CameraMetadata.LENS_FACING_BACK -> context.resources.getString(R.string.back_camera)
                        CameraMetadata.LENS_FACING_EXTERNAL -> context.resources.getString(R.string.external_camera)
                        else -> {
                            Log.e(TAG, "unknown camera type")
                            return null
                        }
                    }
            }

            val viewAngle = computeViewAngles(characteristics)
            if (info != null) info.viewAngle = viewAngle
            if (MyDebug.LOG) Log.d(
                TAG,
                "getDescription: time after computeViewAngles: " + (System.currentTimeMillis() - debugTime)
            )
            if (viewAngle.width > 90.5f) {
                // count as ultra-wide
                if (description.isNotEmpty()) description += ", "
                description += context.resources.getString(R.string.ultrawide)
            } else if (viewAngle.width < 29.5f) {
                // count as telephoto
                // Galaxy S24+ telephoto is 29x22 degrees
                if (description.isNotEmpty()) description += ", "
                description += context.resources.getString(R.string.telephoto)
            }

            if (includeAngles) {
                if (description.isNotEmpty()) description += ", "
                description += ((viewAngle.width + 0.5f).toInt()).toString() + 0x00B0.toChar()
                    .toString() + " x " + ((viewAngle.height + 0.5f).toInt()) + 0x00B0.toChar()
            }
        } catch (e: Throwable) {
            // see note under isFrontFacing() why we catch anything, not just CameraAccessException
            if (MyDebug.LOG) Log.e(TAG, "exception trying to get camera characteristics")
            e.printStackTrace()
        }
        return description
    }

    /* Rather than allowing Camera2 API on all Android 5+ devices, we restrict it to certain cases.
     * This returns whether the specified camera has at least LIMITED support.
     */
    fun allowCamera2Support(cameraId: Int): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraIdS = manager.cameraIdList[cameraId]
            val characteristics = manager.getCameraCharacteristics(cameraIdS)
            //return isHardwareLevelSupported(characteristics, CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY);
            return isHardwareLevelSupported(
                characteristics,
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
            )
        } catch (e: Throwable) {
            // in theory, we should only get CameraAccessException, but Google Play shows we can get a variety of exceptions
            // from some devices, e.g., AssertionError, IllegalArgumentException, RuntimeException, so just catch everything!
            // We don't want users to experience a crash just because of buggy camera2 drivers - instead the user can switch
            // back to old camera API.
            if (MyDebug.LOG) Log.e(TAG, "exception trying to get camera characteristics")
            e.printStackTrace()
        }
        return false
    }

    companion object {
        private const val TAG = "CControllerManager2"

        /** Helper class to compute view angles from the CameraCharacteristics.
         * @return The width and height of the returned size represent the x and y view angles in
         * degrees.
         */
        fun computeViewAngles(characteristics: CameraCharacteristics): SizeF {
            // Note this is an approximation (see http://stackoverflow.com/questions/39965408/what-is-the-android-camera2-api-equivalent-of-camera-parameters-gethorizontalvie ).
            // This does not take into account the aspect ratio of the preview or camera, it's up to the caller to do this (e.g., see Preview.getViewAngleX(), getViewAngleY()).
            val activeSize =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val focalLengths =
                characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            if (activeSize == null || physicalSize == null || pixelSize == null || focalLengths == null || focalLengths.isEmpty()) {
                // in theory this should never happen according to the documentation, but I've had a report of physicalSize (SENSOR_INFO_PHYSICAL_SIZE)
                // being null on an EXTERNAL Camera2 device, see https://sourceforge.net/p/OpenKamera/tickets/754/
                if (MyDebug.LOG) {
                    Log.e(TAG, "can't get camera view angles")
                }
                // fall back to a default
                return SizeF(55.0f, 43.0f)
            }
            //camera_features.viewAngleX = (float)Math.toDegrees(2.0 * Math.atan2(physical_size.getWidth(), (2.0 * focalLengths[0])));
            //camera_features.viewAngleY = (float)Math.toDegrees(2.0 * Math.atan2(physical_size.getHeight(), (2.0 * focalLengths[0])));
            val fracX = (activeSize.width().toFloat()) / pixelSize.width.toFloat()
            val fracY = (activeSize.height().toFloat()) / pixelSize.height.toFloat()
            val viewAngleX = Math.toDegrees(
                2.0 * atan2(
                    (physicalSize.width * fracX).toDouble(),
                    (2.0 * focalLengths[0])
                )
            ).toFloat()
            val viewAngleY = Math.toDegrees(
                2.0 * atan2(
                    (physicalSize.height * fracY).toDouble(),
                    (2.0 * focalLengths[0])
                )
            ).toFloat()
            if (MyDebug.LOG) {
                Log.d(TAG, "frac_x: $fracX")
                Log.d(TAG, "frac_y: $fracY")
                Log.d(
                    TAG,
                    "view_angle_x: $viewAngleX"
                )
                Log.d(
                    TAG,
                    "view_angle_y: $viewAngleY"
                )
            }
            return SizeF(viewAngleX, viewAngleY)
        }

        /* Returns true if the device supports the required hardware level, or better.
     * See https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL .
     * From Android N, higher levels than "FULL" are possible, that will have higher integer values.
     * Also see https://sourceforge.net/p/OpenKamera/tickets/141/ .
     */
        fun isHardwareLevelSupported(c: CameraCharacteristics?, rl: Int): Boolean {
            if (c == null) return false
            var requiredLevel = rl
            var deviceLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            if (deviceLevel == null) {
                if (MyDebug.LOG) Log.e(TAG, "INFO_SUPPORTED_HARDWARE_LEVEL is null")
                return false
            }
            if (MyDebug.LOG) {
                when (deviceLevel) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> Log.d(
                        TAG, "Camera has LEGACY Camera2 support"
                    )

                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> Log.d(
                        TAG,
                        "Camera has EXTERNAL Camera2 support"
                    )

                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> Log.d(
                        TAG,
                        "Camera has LIMITED Camera2 support"
                    )

                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> Log.d(
                        TAG,
                        "Camera has FULL Camera2 support"
                    )

                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> Log.d(
                        TAG,
                        "Camera has Level 3 Camera2 support"
                    )

                    else -> Log.d(
                        TAG,
                        "Camera has unknown Camera2 support: $deviceLevel"
                    )
                }
            }

            // need to treat legacy and external as special cases; otherwise can then use numerical comparison
            if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                return requiredLevel == deviceLevel
            }

            if (deviceLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) {
                deviceLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
            }
            if (requiredLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) {
                requiredLevel = CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
            }

            return requiredLevel <= deviceLevel
        }
    }
}
