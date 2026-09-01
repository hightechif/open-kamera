/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.geometry

import android.graphics.Matrix
import android.graphics.Rect
import com.hightechif.openkamera.cameracontroller.CameraController

/**
 * Pure mathematical coordinate transformation engine for converting between camera sensor coordinates
 * `[-1000, 1000]` and UI preview coordinates `[0, width] x [0, height]`.
 */
object PreviewMatrixCalculator {

    /**
     * Calculates the matrix to map coordinates from camera sensor coordinate system `[-1000, 1000]`
     * to UI preview view coordinates `[0, width] x [0, height]`.
     */
    fun calculateCameraToPreviewMatrix(dimensions: ViewportDimensions): Matrix {
        val matrix = Matrix()
        matrix.reset()

        if (!dimensions.isUsingCamera2) {
            // Camera1 API transformation
            val mirror = dimensions.isCameraFacingFront
            matrix.setScale((if (mirror) -1 else 1).toFloat(), 1f)
            matrix.postRotate(dimensions.displayOrientation.toFloat())
        } else {
            // Camera2 API transformation
            val mirror = dimensions.isCameraFacingFront
            matrix.setScale(1f, (if (mirror) -1 else 1).toFloat())
            val result = (dimensions.cameraOrientation - dimensions.displayRotationDegrees + 360) % 360
            matrix.postRotate(result.toFloat())
        }

        // Camera driver coordinates range from (-1000, -1000) to (1000, 1000).
        // UI coordinates range from (0, 0) to (surfaceWidth, surfaceHeight).
        val surfaceW = dimensions.surfaceWidth.toFloat()
        val surfaceH = dimensions.surfaceHeight.toFloat()
        if (surfaceW > 0f && surfaceH > 0f) {
            matrix.postScale(surfaceW / 2000f, surfaceH / 2000f)
            matrix.postTranslate(surfaceW / 2f, surfaceH / 2f)
        }

        return matrix
    }

    /**
     * Calculates the inverse matrix to map coordinates from UI preview view coordinates `[0, width] x [0, height]`
     * to camera sensor coordinate system `[-1000, 1000]`.
     */
    fun calculatePreviewToCameraMatrix(dimensions: ViewportDimensions): Matrix {
        val cameraToPreview = calculateCameraToPreviewMatrix(dimensions)
        val previewToCamera = Matrix()
        cameraToPreview.invert(previewToCamera)
        return previewToCamera
    }

    /**
     * Constructs a clamped focus and metering area around the specified focus coordinates.
     * Coordinate bounds are strictly clamped within `[-1000, 1000]`.
     */
    fun calculateFocusAreas(
        focusX: Float,
        focusY: Float,
        focusSize: Int = 50,
        weight: Int = 1000
    ): ArrayList<CameraController.Area> {
        val rect = Rect()
        rect.left = focusX.toInt() - focusSize
        rect.right = focusX.toInt() + focusSize
        rect.top = focusY.toInt() - focusSize
        rect.bottom = focusY.toInt() + focusSize

        if (rect.left < -1000) {
            rect.left = -1000
            rect.right = rect.left + 2 * focusSize
        } else if (rect.right > 1000) {
            rect.right = 1000
            rect.left = rect.right - 2 * focusSize
        }

        if (rect.top < -1000) {
            rect.top = -1000
            rect.bottom = rect.top + 2 * focusSize
        } else if (rect.bottom > 1000) {
            rect.bottom = 1000
            rect.top = rect.bottom - 2 * focusSize
        }

        val areas = ArrayList<CameraController.Area>()
        areas.add(CameraController.Area(rect, weight))
        return areas
    }
}
