/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.utils

import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.cameracontroller.CameraControllerManager
import com.hightechif.openkamera.preferences.PreferenceKeys
import java.util.ArrayList

/** Handling of multiple cameras. */
class MultiCamHandler(cameraControllerManager: CameraControllerManager) {
    
    // whether this is a multi-camera device (note, this isn't simply having more than 1 camera, but also having more than one with the same facing)
    // note that in most cases, code should check the MULTI_CAM_BUTTON_PREFERENCE_KEY preference as well as the is_multi_cam flag,
    // this can be done via isMultiCamEnabled().
    private var is_multi_cam = false
    // These lists are lists of camera IDs with the same "facing" (front, back or external).
    // Only initialized if is_multi_cam==true.
    private var back_camera_ids: MutableList<Int>? = null
    private var front_camera_ids: MutableList<Int>? = null
    private var other_camera_ids: MutableList<Int>? = null

    init {
        if (MyDebug.LOG) Log.d(TAG, "MultiCamHandler")

        // We only allow the separate icon for switching cameras if:
        // - there are at least 2 types of "facing" camera, and
        // - there are at least 2 cameras with the same "facing".
        // If there are multiple cameras but all with different "facing", then the switch camera
        // icon is used to iterate over all cameras.
        // If there are more than two cameras, but all cameras have the same "facing, we still stick
        // with using the switch camera icon to iterate over all cameras.
        val n_cameras = cameraControllerManager.numberOfCameras
        if (n_cameras > 2) {
            this.back_camera_ids = ArrayList()
            this.front_camera_ids = ArrayList()
            this.other_camera_ids = ArrayList()
            for (i in 0 until n_cameras) {
                when (cameraControllerManager.getFacing(i)) {
                    CameraController.Facing.FACING_BACK -> back_camera_ids!!.add(i)
                    CameraController.Facing.FACING_FRONT -> front_camera_ids!!.add(i)
                    else -> // we assume any unknown cameras are also external
                        other_camera_ids!!.add(i)
                }
            }
            val multi_same_facing = back_camera_ids!!.size >= 2 || front_camera_ids!!.size >= 2 || other_camera_ids!!.size >= 2
            var n_facing = 0
            if (back_camera_ids!!.isNotEmpty()) n_facing++
            if (front_camera_ids!!.isNotEmpty()) n_facing++
            if (other_camera_ids!!.isNotEmpty()) n_facing++
            
            this.is_multi_cam = multi_same_facing && n_facing >= 2
            
            if (MyDebug.LOG) {
                Log.d(TAG, "multi_same_facing: $multi_same_facing")
                Log.d(TAG, "n_facing: $n_facing")
                Log.d(TAG, "is_multi_cam: $is_multi_cam")
            }

            if (!is_multi_cam) {
                this.back_camera_ids = null
                this.front_camera_ids = null
                this.other_camera_ids = null
            }
        }
    }

    /** Whether this is a multi camera device, and the user preference is set to enable the multi-camera button. */
    fun isMultiCamEnabled(context: Context): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return is_multi_cam && sharedPreferences.getBoolean(PreferenceKeys.MULTI_CAM_BUTTON_PREFERENCE_KEY, true)
    }

    /** Whether this is a multi camera device, whether the user preference is set to enable
     * the multi-camera button.
     */
    fun isMultiCam(): Boolean {
        return is_multi_cam
    }

    /** Whether the device is a multi cam device, and has more than 1 camera for a particular facing. */
    fun hasMultiCameras(facing: CameraController.Facing): Boolean {
        if (is_multi_cam) {
            when (facing) {
                CameraController.Facing.FACING_BACK -> if (back_camera_ids!!.size > 1) return true
                CameraController.Facing.FACING_FRONT -> if (front_camera_ids!!.size > 1) return true
                else -> if (other_camera_ids!!.size > 1) return true
            }
        }
        return false
    }

    /* Returns the cameraId that the "Switch camera" button will switch to.
     * Note that this may not necessarily be the next camera ID, on multi camera devices (if
     * isMultiCamEnabled() returns true).
     */
    fun getNextCameraId(context: Context, cameraControllerManager: CameraControllerManager, cameraId: Int): Int {
        var newCameraId = cameraId
        if (isMultiCamEnabled(context)) {
            // don't use preview.getCameraController(), as it may be null if user quickly switches between cameras
            when (cameraControllerManager.getFacing(newCameraId)) {
                CameraController.Facing.FACING_BACK -> if (front_camera_ids!!.isNotEmpty()) newCameraId = front_camera_ids!![0] else if (other_camera_ids!!.isNotEmpty()) newCameraId = other_camera_ids!![0]
                CameraController.Facing.FACING_FRONT -> if (other_camera_ids!!.isNotEmpty()) newCameraId = other_camera_ids!![0] else if (back_camera_ids!!.isNotEmpty()) newCameraId = back_camera_ids!![0]
                else -> if (back_camera_ids!!.isNotEmpty()) newCameraId = back_camera_ids!![0] else if (front_camera_ids!!.isNotEmpty()) newCameraId = front_camera_ids!![0]
            }
        } else {
            val n_cameras = cameraControllerManager.numberOfCameras
            newCameraId = (newCameraId + 1) % n_cameras
        }
        return newCameraId
    }

    /** Returns list of logical cameras with same facing as this_facing. */
    fun getSameFacingLogicalCameras(cameraControllerManager: CameraControllerManager, this_facing: CameraController.Facing): List<Int> {
        val logical_camera_ids: MutableList<Int> = ArrayList()
        for (i in 0 until cameraControllerManager.numberOfCameras) {
            if (cameraControllerManager.getFacing(i) != this_facing) {
                // only show cameras with same facing
                continue
            }
            logical_camera_ids.add(i)
        }
        return logical_camera_ids
    }

    companion object {
        private const val TAG = "MultiCamHandler"
    }
}
