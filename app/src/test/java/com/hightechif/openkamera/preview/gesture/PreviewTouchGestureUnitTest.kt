/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.gesture

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewTouchGestureUnitTest {

    private lateinit var context: Context
    private var singleTouchHandled = false
    private var scaleZoomFactor = 1.0f
    private var doubleTapHandled = false
    private var clearToastCalled = false

    private val callback = object : PreviewTouchCallback {
        override fun onSingleTouch(event: MotionEvent, wasPaused: Boolean): Boolean {
            singleTouchHandled = true
            return true
        }

        override fun onScaleZoom(scaleFactor: Float) {
            scaleZoomFactor = scaleFactor
        }

        override fun onScaleBegin() {}
        override fun onScaleEnd() {}

        override fun onDoubleTap(): Boolean {
            doubleTapHandled = true
            return true
        }

        override fun shouldTakePhotoOnDoubleTap(): Boolean = false
        override fun isTouchCaptureEnabled(): Boolean = false
        override fun onClearFakeToast() {
            clearToastCalled = true
        }
    }

    private lateinit var coordinator: PreviewTouchGestureCoordinator

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        coordinator = PreviewTouchGestureCoordinator(context, callback)
        singleTouchHandled = false
        scaleZoomFactor = 1.0f
        doubleTapHandled = false
        clearToastCalled = false
    }

    @Test
    fun testSingleTap_DispatchesClearToastAndSingleTouch() {
        val downTime = SystemClock.uptimeMillis()
        val eventDown = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 200f, 300f, 0)
        coordinator.onTouchEvent(eventDown, wasPaused = false, isCameraAvailable = true)

        val eventUp = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, 200f, 300f, 0)
        coordinator.onTouchEvent(eventUp, wasPaused = false, isCameraAvailable = true)

        assertTrue(clearToastCalled)
        assertTrue(singleTouchHandled)
    }

    @Test
    fun testSwipe_IgnoredBySingleTouch() {
        val downTime = SystemClock.uptimeMillis()
        val eventDown = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        coordinator.onTouchEvent(eventDown, wasPaused = false, isCameraAvailable = true)

        // Large distance movement (300px)
        val eventUp = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_UP, 400f, 400f, 0)
        val handled = coordinator.onTouchEvent(eventUp, wasPaused = false, isCameraAvailable = true)

        assertTrue(handled)
        // Should not trigger single touch tap because it's a swipe
        assertEquals(false, singleTouchHandled)
    }
}
