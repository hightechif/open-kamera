/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.camerasurface

import android.graphics.Matrix
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preview.geometry.PreviewMatrixCalculator
import com.hightechif.openkamera.preview.geometry.ViewportDimensions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages CameraSurface (SurfaceView or TextureView), coordinate matrix transformations,
 * and coroutine-based asynchronous camera lifecycle operations on `Dispatchers.IO`.
 */
class PreviewSurfaceManager(
    val cameraSurface: CameraSurface,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val TAG = "PreviewSurfaceManager"
    }

    private val _cameraToPreviewMatrix = Matrix()
    private val _previewToCameraMatrix = Matrix()

    var previewWidth: Int = 0
    var previewHeight: Int = 0
    var targetRatio: Double = 0.0

    /**
     * Calculates the transformation matrix from camera sensor coordinates [-1000, 1000] to preview viewport coordinates.
     */
    fun calculateCameraToPreviewMatrix(
        controller: CameraController?,
        displayRotationDegrees: Int,
        usingAndroidL: Boolean
    ): Matrix {
        if (controller == null) return _cameraToPreviewMatrix

        val dimensions = ViewportDimensions(
            surfaceWidth = cameraSurface.view.width,
            surfaceHeight = cameraSurface.view.height,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            displayRotationDegrees = displayRotationDegrees,
            cameraOrientation = controller.cameraOrientation,
            displayOrientation = if (usingAndroidL) 0 else controller.displayOrientation,
            isCameraFacingFront = (controller.facing === CameraController.Facing.FACING_FRONT),
            isUsingCamera2 = usingAndroidL
        )
        _cameraToPreviewMatrix.set(PreviewMatrixCalculator.calculateCameraToPreviewMatrix(dimensions))
        return _cameraToPreviewMatrix
    }

    /**
     * Calculates the transformation matrix from preview viewport coordinates to camera sensor coordinates [-1000, 1000].
     */
    fun calculatePreviewToCameraMatrix(
        controller: CameraController?,
        displayRotationDegrees: Int,
        usingAndroidL: Boolean
    ): Matrix {
        if (controller == null) return _previewToCameraMatrix

        val dimensions = ViewportDimensions(
            surfaceWidth = cameraSurface.view.width,
            surfaceHeight = cameraSurface.view.height,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            displayRotationDegrees = displayRotationDegrees,
            cameraOrientation = controller.cameraOrientation,
            displayOrientation = if (usingAndroidL) 0 else controller.displayOrientation,
            isCameraFacingFront = (controller.facing === CameraController.Facing.FACING_FRONT),
            isUsingCamera2 = usingAndroidL
        )
        _previewToCameraMatrix.set(PreviewMatrixCalculator.calculatePreviewToCameraMatrix(dimensions))
        _cameraToPreviewMatrix.set(PreviewMatrixCalculator.calculateCameraToPreviewMatrix(dimensions))
        return _previewToCameraMatrix
    }

    /**
     * Calculates the texture transform matrix for TextureView.
     */
    fun calculateTextureTransform(
        textureViewWidth: Int,
        textureViewHeight: Int,
        displayRotation: Int
    ): Matrix {
        return com.hightechif.openkamera.preview.geometry.ViewportTransformHelper.calculateTextureTransform(
            textureViewWidth = textureViewWidth,
            textureViewHeight = textureViewHeight,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
            displayRotation = displayRotation
        )
    }

    /**
     * Asynchronously executes a background task on Dispatchers.IO.
     */
    suspend fun <T> runOnBackgroundThread(block: suspend () -> T): T = withContext(ioDispatcher) {
        block()
    }
}

