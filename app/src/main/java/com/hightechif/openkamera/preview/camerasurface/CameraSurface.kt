/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.camerasurface

import android.graphics.Matrix
import android.media.MediaRecorder
import android.view.View
import com.hightechif.openkamera.cameracontroller.CameraController

/** Provides support for the surface used for the preview - this can either be
 * a SurfaceView or a TextureView.
 */
interface CameraSurface {
    val view: View
    fun setPreviewDisplay(cameraController: CameraController?) // n.b., uses double-dispatch similar to Visitor pattern - behavior depends on type of CameraSurface and CameraController
    fun setVideoRecorder(videoRecorder: MediaRecorder?)
    fun setTransform(matrix: Matrix?)
    fun onPause()
    fun onResume()
}
