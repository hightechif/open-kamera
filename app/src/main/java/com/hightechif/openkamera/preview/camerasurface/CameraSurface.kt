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
    fun setPreviewDisplay(cameraController: CameraController?) // n.b., uses double-dispatch similar to Visitor pattern - behaviour depends on type of CameraSurface and CameraController
    fun setVideoRecorder(videoRecorder: MediaRecorder?)
    fun setTransform(matrix: Matrix?)
    fun onPause()
    fun onResume()
}
