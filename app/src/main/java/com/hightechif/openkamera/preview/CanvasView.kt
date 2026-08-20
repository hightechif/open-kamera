/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.util.Log
import android.view.View
import com.hightechif.openkamera.utils.MyDebug

/** View for on top of the Preview - this just redirects to Preview.onDraw to do the
 * work. Only used if using a MyTextureView (if using MySurfaceView, then that
 * class can handle the onDraw()). TextureViews can't be used for both a
 * camera preview, and used for drawing on.
 */
class CanvasView internal constructor(context: Context?, private val preview: Preview) :
    View(context) {
    private val measureSpec = IntArray(2)
    private val handler = Handler()
    private val tick: Runnable

    init {
        if (MyDebug.LOG) {
            Log.d(TAG, "new CanvasView")
        }

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

    public override fun onDraw(canvas: Canvas) {
        /*if( MyDebug.LOG )
			Log.d(TAG, "onDraw()");*/
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

    fun onPause() {
        if (MyDebug.LOG) Log.d(TAG, "onPause()")
        handler.removeCallbacks(tick)
    }

    fun onResume() {
        if (MyDebug.LOG) Log.d(TAG, "onResume()")
        tick.run()
    }

    companion object {
        private const val TAG = "CanvasView"
    }
}
