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
import com.hightechif.openkamera.cameracontroller.capabilities.Camera2CapabilitiesResolver
import com.hightechif.openkamera.utils.MyDebug

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

        fun computeViewAngles(characteristics: CameraCharacteristics): SizeF {
            return Camera2CapabilitiesResolver.computeViewAngles(characteristics)
        }

        fun isHardwareLevelSupported(c: CameraCharacteristics?, rl: Int): Boolean {
            return Camera2CapabilitiesResolver.isHardwareLevelSupported(c, rl)
        }
    }
}
