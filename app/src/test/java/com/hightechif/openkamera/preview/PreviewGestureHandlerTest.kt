/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import com.hightechif.openkamera.preview.gesture.PreviewGestureHandler
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewGestureHandlerTest {

    @Test
    fun getFocusMeteringAreas_clampsToSensorBoundaries() {
        // Test center point
        val centerAreas = PreviewGestureHandler.getFocusMeteringAreas(0f, 0f)
        assertEquals(1, centerAreas.size)
        assertEquals(-100, centerAreas[0].rect.left)
        assertEquals(100, centerAreas[0].rect.right)
        assertEquals(-100, centerAreas[0].rect.top)
        assertEquals(100, centerAreas[0].rect.bottom)
        assertEquals(1000, centerAreas[0].weight)

        // Test extreme upper left corner point (-1000, -1000)
        val cornerAreas = PreviewGestureHandler.getFocusMeteringAreas(-1000f, -1000f)
        assertEquals(-1000, cornerAreas[0].rect.left)
        assertEquals(-900, cornerAreas[0].rect.right)
        assertEquals(-1000, cornerAreas[0].rect.top)
        assertEquals(-900, cornerAreas[0].rect.bottom)

        // Test extreme lower right corner point (+1000, +1000)
        val maxAreas = PreviewGestureHandler.getFocusMeteringAreas(1000f, 1000f)
        assertEquals(900, maxAreas[0].rect.left)
        assertEquals(1000, maxAreas[0].rect.right)
        assertEquals(900, maxAreas[0].rect.top)
        assertEquals(1000, maxAreas[0].rect.bottom)
    }

    @Test
    fun calculatePinchZoom_scalesAndClampsWithinLimits() {
        val currentZoom = 2.0f
        val zoomedIn = PreviewGestureHandler.calculatePinchZoom(currentZoom, 1.5f, 1.0f, 10.0f)
        assertEquals(3.0f, zoomedIn, 0.001f)

        val zoomedOut = PreviewGestureHandler.calculatePinchZoom(currentZoom, 0.25f, 1.0f, 10.0f)
        assertEquals(1.0f, zoomedOut, 0.001f) // Clamped to min 1.0

        val overZoomed = PreviewGestureHandler.calculatePinchZoom(currentZoom, 10.0f, 1.0f, 10.0f)
        assertEquals(10.0f, overZoomed, 0.001f) // Clamped to max 10.0
    }

    @Test
    fun getScaledZoomFactor_calculatesZoomStepsCorrectly() {
        val zoomRatios = listOf(100, 150, 200, 300, 400)
        val maxZoom = zoomRatios.size - 1

        // Zoom in with smooth zoom
        val (factorIn, smoothIn) = PreviewGestureHandler.getScaledZoomFactor(
            scaleFactor = 1.6f,
            zoomFactor = 0,
            zoomRatios = zoomRatios,
            hasSmoothZoom = true,
            currentSmoothZoom = 1.0f,
            maxZoom = maxZoom
        )
        assertEquals(1, factorIn)
        assertEquals(1.6f, smoothIn, 0.001f)

        // Zoom out with smooth zoom
        val (factorOut, smoothOut) = PreviewGestureHandler.getScaledZoomFactor(
            scaleFactor = 0.5f,
            zoomFactor = 3,
            zoomRatios = zoomRatios,
            hasSmoothZoom = true,
            currentSmoothZoom = 3.0f,
            maxZoom = maxZoom
        )
        assertEquals(1, factorOut)
        assertEquals(1.5f, smoothOut, 0.001f)
    }

    @Test
    fun calculateVerticalSwipeExposure_adjustsExposureWithinLimits() {
        val minExp = -4
        val maxExp = 4
        val currentExp = 0
        val viewHeight = 1000f

        // Swipe up (-250px) should increase exposure
        val swipeUpExp = PreviewGestureHandler.calculateVerticalSwipeExposure(
            deltaY = -250f,
            viewHeight = viewHeight,
            minExposure = minExp,
            maxExposure = maxExp,
            currentExposure = currentExp
        )
        assertEquals(2, swipeUpExp)

        // Swipe down (+500px) should decrease exposure
        val swipeDownExp = PreviewGestureHandler.calculateVerticalSwipeExposure(
            deltaY = 500f,
            viewHeight = viewHeight,
            minExposure = minExp,
            maxExposure = maxExp,
            currentExposure = currentExp
        )
        assertEquals(-4, swipeDownExp) // Clamped to min

        // Over-swipe up should clamp to max
        val overSwipeUpExp = PreviewGestureHandler.calculateVerticalSwipeExposure(
            deltaY = -1500f,
            viewHeight = viewHeight,
            minExposure = minExp,
            maxExposure = maxExp,
            currentExposure = currentExp
        )
        assertEquals(4, overSwipeUpExp)
    }

    @Test
    fun isUnlockSwipe_validatesDistanceAndVelocity() {
        val minDistance = 100f
        val minVelocity = 500f

        // Valid swipe: distance = 150, velocity = 600
        val isValid = PreviewGestureHandler.isUnlockSwipe(
            startX = 200f,
            startY = 300f,
            endX = 350f,
            endY = 300f,
            velocityX = 600f,
            velocityY = 0f,
            minDistance = minDistance,
            minVelocity = minVelocity
        )
        assertEquals(true, isValid)

        // Short swipe: distance = 50 < 100
        val isShort = PreviewGestureHandler.isUnlockSwipe(
            startX = 200f,
            startY = 300f,
            endX = 250f,
            endY = 300f,
            velocityX = 600f,
            velocityY = 0f,
            minDistance = minDistance,
            minVelocity = minVelocity
        )
        assertEquals(false, isShort)

        // Slow swipe: velocity = 200 < 500
        val isSlow = PreviewGestureHandler.isUnlockSwipe(
            startX = 200f,
            startY = 300f,
            endX = 350f,
            endY = 300f,
            velocityX = 200f,
            velocityY = 0f,
            minDistance = minDistance,
            minVelocity = minVelocity
        )
        assertEquals(false, isSlow)
    }

    @Test
    fun calculateTapNormalizedCoordinates_normalizesAndClamps() {
        val viewWidth = 1080f
        val viewHeight = 1920f

        // Center tap
        val center = PreviewGestureHandler.calculateTapNormalizedCoordinates(540f, 960f, viewWidth, viewHeight)
        assertEquals(0.5f, center.x, 0.001f)
        assertEquals(0.5f, center.y, 0.001f)

        // Top-left
        val topLeft = PreviewGestureHandler.calculateTapNormalizedCoordinates(0f, 0f, viewWidth, viewHeight)
        assertEquals(0.0f, topLeft.x, 0.001f)
        assertEquals(0.0f, topLeft.y, 0.001f)

        // Out of bounds clamp
        val clamped = PreviewGestureHandler.calculateTapNormalizedCoordinates(-50f, 2500f, viewWidth, viewHeight)
        assertEquals(0.0f, clamped.x, 0.001f)
        assertEquals(1.0f, clamped.y, 0.001f)
    }
}

