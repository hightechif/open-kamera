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
}
