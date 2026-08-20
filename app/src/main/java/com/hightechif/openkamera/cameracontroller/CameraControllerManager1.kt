/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.content.Context
import android.hardware.Camera
import android.util.Log
import com.hightechif.openkamera.R

/** Provides support using Android's original camera API
 * android.hardware.Camera.
 */
class CameraControllerManager1 : CameraControllerManager() {
    override val numberOfCameras: Int
        get() = Camera.getNumberOfCameras()

    override fun getFacing(cameraId: Int): CameraController.Facing {
        try {
            val cameraInfo = Camera.CameraInfo()
            Camera.getCameraInfo(cameraId, cameraInfo)
            when (cameraInfo.facing) {
                Camera.CameraInfo.CAMERA_FACING_FRONT -> return CameraController.Facing.FACING_FRONT
                Camera.CameraInfo.CAMERA_FACING_BACK -> return CameraController.Facing.FACING_BACK
            }
            Log.e(TAG, "unknown camera_facing: " + cameraInfo.facing)
        } catch (e: RuntimeException) {
            // Had a report of this crashing on Galaxy Nexus - may be device specific issue, see http://stackoverflow.com/questions/22383708/java-lang-runtimeexception-fail-to-get-camera-info
            // but good to catch it anyway
            Log.e(TAG, "failed to get facing")
            e.printStackTrace()
        }
        return CameraController.Facing.FACING_UNKNOWN
    }

    override fun getDescription(context: Context?, cameraId: Int): String? {
        if (context == null) return null
        return when (getFacing(cameraId)) {
            CameraController.Facing.FACING_FRONT -> context.resources.getString(R.string.front_camera)
            CameraController.Facing.FACING_BACK -> context.resources.getString(R.string.back_camera)
            else -> null
        }
    }

    override fun getDescription(
        info: CameraInfo?,
        context: Context?,
        cameraIdS: String,
        includeType: Boolean,
        includeAngles: Boolean
    ): String {
        throw RuntimeException("getDescription() not supported for old Camera API")
    }

    companion object {
        private const val TAG = "CControllerManager1"
    }
}
