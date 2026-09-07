/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.gesture

import android.graphics.Matrix
import android.graphics.Rect
import com.hightechif.openkamera.cameracontroller.CameraController
import kotlin.math.roundToInt

/**
 * Handles touch focus and metering coordinate space transformations,
 * mapping screen pixels through preview-to-camera matrix to normalized sensor coordinates `[-1000, 1000]`.
 */
object PreviewGestureHandler {

    private const val FOCUS_AREA_SIZE = 100

    /**
     * Converts a touch point (in camera sensor coordinate space) into a list containing a single [CameraController.Area].
     */
    fun getFocusMeteringAreas(
        focusX: Float,
        focusY: Float
    ): ArrayList<CameraController.Area> {
        val rect = Rect()
        rect.left = (focusX - FOCUS_AREA_SIZE).roundToInt()
        rect.right = (focusX + FOCUS_AREA_SIZE).roundToInt()
        rect.top = (focusY - FOCUS_AREA_SIZE).roundToInt()
        rect.bottom = (focusY + FOCUS_AREA_SIZE).roundToInt()

        // Clamp to [-1000, 1000]
        rect.left = rect.left.coerceIn(-1000, 1000)
        rect.right = rect.right.coerceIn(-1000, 1000)
        rect.top = rect.top.coerceIn(-1000, 1000)
        rect.bottom = rect.bottom.coerceIn(-1000, 1000)

        val areas = ArrayList<CameraController.Area>()
        areas.add(CameraController.Area(rect, 1000))
        return areas
    }

    /**
     * Transforms screen touch coordinates (x, y) into camera sensor coordinates using the preview-to-camera matrix.
     */
    fun mapTouchToSensorCoords(
        touchX: Float,
        touchY: Float,
        previewToCameraMatrix: Matrix
    ): FloatArray {
        val coords = floatArrayOf(touchX, touchY)
        previewToCameraMatrix.mapPoints(coords)
        return coords
    }

    /**
     * Calculates smooth zoom ratio given the current zoom, min/max limits, and pinch scale factor.
     */
    fun calculatePinchZoom(
        currentZoom: Float,
        scaleFactor: Float,
        minZoom: Float = 1.0f,
        maxZoom: Float = 100.0f
    ): Float {
        val newZoom = currentZoom * scaleFactor
        return newZoom.coerceIn(minZoom, maxZoom)
    }

    /**
     * Calculates the new zoom index factor when pinch-zooming given zoom ratios and scale factor.
     * Returns a Pair of (newZoomFactor, newSmoothZoom).
     */
    fun getScaledZoomFactor(
        scaleFactor: Float,
        zoomFactor: Int,
        zoomRatios: List<Int>?,
        hasSmoothZoom: Boolean,
        currentSmoothZoom: Float,
        maxZoom: Int
    ): Pair<Int, Float> {
        if (zoomRatios.isNullOrEmpty()) {
            return Pair(zoomFactor, currentSmoothZoom)
        }

        var zoomRatio = if (hasSmoothZoom) {
            currentSmoothZoom
        } else {
            zoomRatios[zoomFactor] / 100.0f
        }
        zoomRatio *= scaleFactor

        var newZoomFactor = zoomFactor
        var newSmoothZoom = currentSmoothZoom

        if (zoomRatio <= zoomRatios[0] / 100.0f) {
            newZoomFactor = 0
            if (hasSmoothZoom) newSmoothZoom = zoomRatios[0] / 100.0f
        } else if (zoomRatio >= zoomRatios[maxZoom] / 100.0f) {
            newZoomFactor = maxZoom
            if (hasSmoothZoom) newSmoothZoom = zoomRatios[maxZoom] / 100.0f
        } else if (hasSmoothZoom) {
            var dist = kotlin.math.abs((zoomRatio - zoomRatios[zoomFactor] / 100.0f).toDouble()).toFloat()

            if (scaleFactor > 1.0f) {
                for (i in zoomFactor + 1 until zoomRatios.size) {
                    val thisDist = kotlin.math.abs((zoomRatio - zoomRatios[i] / 100.0f).toDouble()).toFloat()
                    if (thisDist < dist) {
                        newZoomFactor = i
                        dist = thisDist
                    } else if (thisDist > dist + 1.0e-5f) {
                        break
                    }
                }
            } else {
                for (i in zoomFactor - 1 downTo 0) {
                    val thisDist = kotlin.math.abs((zoomRatio - zoomRatios[i] / 100.0f).toDouble()).toFloat()
                    if (thisDist < dist) {
                        newZoomFactor = i
                        dist = thisDist
                    } else if (thisDist > dist + 1.0e-5f) {
                        break
                    }
                }
            }
            newSmoothZoom = zoomRatio
        } else {
            if (scaleFactor > 1.0f) {
                for (i in zoomFactor until zoomRatios.size) {
                    if (zoomRatios[i] / 100.0f >= zoomRatio) {
                        newZoomFactor = i
                        break
                    }
                }
            } else {
                for (i in zoomFactor downTo 0) {
                    if (zoomRatios[i] / 100.0f <= zoomRatio) {
                        newZoomFactor = i
                        break
                    }
                }
            }
        }

        return Pair(newZoomFactor, newSmoothZoom)
    }

    /**
     * Calculates vertical swipe exposure adjustment steps.
     * Swiping up (deltaY < 0) increases exposure, swiping down (deltaY > 0) decreases exposure.
     */
    fun calculateVerticalSwipeExposure(
        deltaY: Float,
        viewHeight: Float,
        minExposure: Int,
        maxExposure: Int,
        currentExposure: Int,
        sensitivity: Float = 1.0f
    ): Int {
        if (viewHeight <= 0f) return currentExposure
        val totalRange = (maxExposure - minExposure).coerceAtLeast(1)
        val normalizedDelta = -deltaY / viewHeight
        val stepChange = (normalizedDelta * totalRange * sensitivity).roundToInt()
        return (currentExposure + stepChange).coerceIn(minExposure, maxExposure)
    }

    /**
     * Determines whether a swipe gesture satisfies unlock fling requirements based on distance and velocity.
     */
    fun isUnlockSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        velocityX: Float,
        velocityY: Float,
        minDistance: Float,
        minVelocity: Float
    ): Boolean {
        val diffX = startX - endX
        val diffY = startY - endY
        val dist2 = diffX * diffX + diffY * diffY
        val vel2 = velocityX * velocityX + velocityY * velocityY
        return dist2 > minDistance * minDistance && vel2 > minVelocity * minVelocity
    }

    /**
     * Converts raw screen pixel touch coordinates to normalized [0.0, 1.0] viewport coordinates.
     */
    fun calculateTapNormalizedCoordinates(
        touchX: Float,
        touchY: Float,
        viewWidth: Float,
        viewHeight: Float
    ): android.graphics.PointF {
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return android.graphics.PointF(0.5f, 0.5f)
        }
        val normX = (touchX / viewWidth).coerceIn(0.0f, 1.0f)
        val normY = (touchY / viewHeight).coerceIn(0.0f, 1.0f)
        return android.graphics.PointF(normX, normY)
    }
}

