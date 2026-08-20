package com.hightechif.openkamera.preview.camerasurface

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.media.MediaRecorder
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.cameracontroller.CameraControllerException
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.utils.MyDebug

/** Provides support for the surface used for the preview, using a TextureView.
 */
class MyTextureView private constructor(context: Context, preview: Preview) : TextureView(context),
    CameraSurface {
    private val preview: Preview = preview
    private val measureSpec = IntArray(2)

    override val view: View
        get() = this

    override fun setPreviewDisplay(cameraController: CameraController?) {
        if (MyDebug.LOG) Log.d(TAG, "setPreviewDisplay")
        try {
            cameraController?.setPreviewTexture(this)
        } catch (e: CameraControllerException) {
            if (MyDebug.LOG) Log.e(TAG, "Failed to set preview display: " + e.message)
            e.printStackTrace()
        }
    }

    override fun setVideoRecorder(videoRecorder: MediaRecorder?) {
        // should be no need to do anything (see documentation for MediaRecorder.setPreviewDisplay())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return preview.touchEvent(event)
    }

    /*@Override
	public void onDraw(Canvas canvas) {
		preview.draw(canvas);
	}*/
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onMeasure: $widthSpec x $heightSpec"
        )
        preview.getMeasureSpec(measureSpec, widthSpec, heightSpec)
        super.onMeasure(measureSpec[0], measureSpec[1])
    }

    override fun setTransform(matrix: Matrix?) {
        super.setTransform(matrix)
    }

    override fun onPause() {
    }

    override fun onResume() {
    }

    companion object {
        private const val TAG = "MyTextureView"

        private var INSTANCE: MyTextureView? = null

        fun createInstance(context: Context, preview: Preview): MyTextureView {
            return INSTANCE ?: synchronized(this) {
                val instance = MyTextureView(context, preview)
                if (MyDebug.LOG) {
                    Log.d(TAG, "new MyTextureView")
                }

                // Install a TextureView.SurfaceTextureListener so we get notified when the
                // underlying surface is created and destroyed.
                instance.surfaceTextureListener = preview
                return instance
            }
        }
    }

}
