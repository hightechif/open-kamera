/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.gesture

import android.content.Context
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.hightechif.openkamera.utils.MyDebug

/**
 * Callback interface implemented by Preview to react to touch and scale gesture events.
 */
interface PreviewTouchCallback {
    fun onSingleTouch(event: MotionEvent, wasPaused: Boolean): Boolean
    fun onScaleZoom(scaleFactor: Float)
    fun onScaleBegin()
    fun onScaleEnd()
    fun onDoubleTap(): Boolean
    fun shouldTakePhotoOnDoubleTap(): Boolean
    fun isTouchCaptureEnabled(): Boolean
    fun onClearFakeToast()
}

/**
 * Encapsulates touch event interception, swipe filtering, double-tap routing, and pinch-to-zoom gestures.
 */
class PreviewTouchGestureCoordinator(
    private val context: Context,
    private val callback: PreviewTouchCallback
) {
    companion object {
        private const val TAG = "PreviewTouchGesture"
    }

    private var touchWasMultitouch = false
    private var touchOrigX = 0f
    private var touchOrigY = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (MyDebug.LOG) Log.d(TAG, "onSingleTapConfirmed")
            if (callback.shouldTakePhotoOnDoubleTap()) {
                return callback.onSingleTouch(e, false)
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (MyDebug.LOG) Log.d(TAG, "onDoubleTap")
            return callback.onDoubleTap()
        }
    })

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var scaleFactor = detector.scaleFactor
            if (MyDebug.LOG) Log.d(TAG, "onScale: $scaleFactor")
            if (touchWasMultitouch) {
                scaleFactor = 1.0f + 2.0f * (scaleFactor - 1.0f)
            }
            callback.onScaleZoom(scaleFactor)
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            callback.onScaleBegin()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (MyDebug.LOG) Log.d(TAG, "onScaleEnd")
            callback.onScaleEnd()
        }
    })

    /**
     * Dispatches a motion event through gesture detectors and swipe filters.
     */
    fun onTouchEvent(event: MotionEvent, wasPaused: Boolean, isCameraAvailable: Boolean): Boolean {
        callback.onClearFakeToast()

        if (gestureDetector.onTouchEvent(event)) {
            if (MyDebug.LOG) Log.d(TAG, "touch event handled by gestureDetector")
            return true
        }

        scaleGestureDetector.onTouchEvent(event)
        if (!isCameraAvailable) {
            if (MyDebug.LOG) Log.d(TAG, "received touch event, but camera not available")
            return true
        }

        if (event.pointerCount != 1) {
            touchWasMultitouch = true
            return true
        }

        if (event.action != MotionEvent.ACTION_UP) {
            if (event.action == MotionEvent.ACTION_DOWN && event.pointerCount == 1) {
                touchWasMultitouch = false
                touchOrigX = event.x
                touchOrigY = event.y
            }
            return true
        }

        if (touchWasMultitouch) {
            return true
        }

        // Swipe tolerance filter (~31dp)
        val diffX = event.x - touchOrigX
        val diffY = event.y - touchOrigY
        val dist2 = diffX * diffX + diffY * diffY
        val scale = context.resources.displayMetrics.density
        val tol = 31 * scale + 0.5f

        if (dist2 > tol * tol) {
            if (MyDebug.LOG) Log.d(TAG, "touch was a swipe")
            return true
        }

        if (callback.shouldTakePhotoOnDoubleTap()) {
            // wait for onSingleTapConfirmed
            return true
        }

        return callback.onSingleTouch(event, wasPaused)
    }
}
