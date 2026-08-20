package com.hightechif.openkamera.preview.camerasurface

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaRecorder
import android.os.Handler
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.cameracontroller.CameraControllerException
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.utils.MyDebug

/** Provides support for the surface used for the preview, using a SurfaceView.
 */
class MySurfaceView(context: Context?, preview: Preview) : SurfaceView(context),
    CameraSurface {
    private val preview: Preview = preview
    private val measureSpec = IntArray(2)
    private val handler = Handler()
    private val tick: Runnable

    init {
        if (MyDebug.LOG) {
            Log.d(TAG, "new MySurfaceView")
        }

        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        holder.addCallback(preview)

        // deprecated setting, but required on Android versions prior to 3.0
        //getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); // deprecated
        tick = object : Runnable {
            override fun run() {
                /*if( MyDebug.LOG )
					Log.d(TAG, "invalidate()");*/
                preview.testTickerCalled = true
                invalidate()
                handler.postDelayed(this, preview.frameRate)
            }
        }
    }

    override val view: View
        get() = this

    override fun setPreviewDisplay(cameraController: CameraController?) {
        if (MyDebug.LOG) Log.d(TAG, "setPreviewDisplay")
        try {
            cameraController?.setPreviewDisplay(this.holder)
        } catch (e: CameraControllerException) {
            if (MyDebug.LOG) Log.e(TAG, "Failed to set preview display: " + e.message)
            e.printStackTrace()
        }
    }

    override fun setVideoRecorder(videoRecorder: MediaRecorder?) {
        videoRecorder?.setPreviewDisplay(this.holder.surface)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return preview.touchEvent(event)
    }

    public override fun onDraw(canvas: Canvas) {
        preview.draw(canvas)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onMeasure: $widthSpec x $heightSpec"
        )
        preview.getMeasureSpec(measureSpec, widthSpec, heightSpec)
        super.onMeasure(measureSpec[0], measureSpec[1])
    }

    override fun setTransform(matrix: Matrix?) {
        if (MyDebug.LOG) Log.d(TAG, "setting transforms not supported for MySurfaceView")
        throw RuntimeException()
    }

    override fun onPause() {
        if (MyDebug.LOG) Log.d(TAG, "onPause()")
        handler.removeCallbacks(tick)
    }

    override fun onResume() {
        if (MyDebug.LOG) Log.d(TAG, "onResume()")
        tick.run()
    }

    companion object {
        private const val TAG = "MySurfaceView"
    }
}
