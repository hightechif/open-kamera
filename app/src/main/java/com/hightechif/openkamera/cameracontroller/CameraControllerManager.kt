/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.content.Context
import android.util.SizeF

/** Provides additional support related to the Android camera APIs. This is to
 * support functionality that doesn't require a camera to have been opened.
 */
abstract class CameraControllerManager {
    abstract val numberOfCameras: Int

    /** Returns whether the supplied cameraId is front, back or external.
     */
    abstract fun getFacing(cameraId: Int): CameraController.Facing

    /** Tries to return a textual description for the camera, such as front/back, along with extra
     * details if possible such as "ultra-wide". Will be null if no description can be determined.
     */
    abstract fun getDescription(context: Context?, cameraId: Int): String?

    class CameraInfo {
        var viewAngle: SizeF? = null
    }

    /** Version of getDescription() that supports Camera2 camera ID strings (used for physical cameras), also returns the
     * view angles in info, if info is non-null.
     */
    abstract fun getDescription(
        info: CameraInfo?,
        context: Context?,
        cameraIdS: String,
        includeType: Boolean,
        includeAngles: Boolean
    ): String?
}