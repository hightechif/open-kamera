/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewMatrixCalculatorUnitTest {

    @Test
    fun testCameraToPreviewMatrix_Camera2_BackCamera() {
        val dimensions = ViewportDimensions(
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            previewWidth = 1920,
            previewHeight = 1080,
            displayRotationDegrees = 0,
            cameraOrientation = 90,
            displayOrientation = 0, // In Camera2, displayOrientation is 0 / unused
            isCameraFacingFront = false,
            isUsingCamera2 = true
        )

        val matrix = PreviewMatrixCalculator.calculateCameraToPreviewMatrix(dimensions)
        assertNotNull(matrix)

        // Center coordinate (0, 0) in sensor space should map to center of surface (540, 960)
        val center = floatArrayOf(0f, 0f)
        matrix.mapPoints(center)
        assertEquals(540f, center[0], 0.1f)
        assertEquals(960f, center[1], 0.1f)
    }

    @Test
    fun testCameraToPreviewMatrix_Camera2_IgnoresDisplayOrientation() {
        // Dimensions with displayOrientation = 0
        val dim1 = ViewportDimensions(
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            previewWidth = 1920,
            previewHeight = 1080,
            displayRotationDegrees = 0,
            cameraOrientation = 90,
            displayOrientation = 0,
            isCameraFacingFront = false,
            isUsingCamera2 = true
        )
        // Dimensions with arbitrary displayOrientation
        val dim2 = dim1.copy(displayOrientation = 180)

        val matrix1 = PreviewMatrixCalculator.calculateCameraToPreviewMatrix(dim1)
        val matrix2 = PreviewMatrixCalculator.calculateCameraToPreviewMatrix(dim2)

        val pt1 = floatArrayOf(100f, 200f)
        val pt2 = floatArrayOf(100f, 200f)
        matrix1.mapPoints(pt1)
        matrix2.mapPoints(pt2)

        assertEquals(pt1[0], pt2[0], 0.001f)
        assertEquals(pt1[1], pt2[1], 0.001f)
    }

    @Test
    fun testCameraToPreviewMatrix_Camera1_UsesDisplayOrientation() {
        val dimensions = ViewportDimensions(
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            previewWidth = 1920,
            previewHeight = 1080,
            displayRotationDegrees = 0,
            cameraOrientation = 90,
            displayOrientation = 90,
            isCameraFacingFront = false,
            isUsingCamera2 = false
        )

        val matrix = PreviewMatrixCalculator.calculateCameraToPreviewMatrix(dimensions)
        assertNotNull(matrix)

        val center = floatArrayOf(0f, 0f)
        matrix.mapPoints(center)
        assertEquals(540f, center[0], 0.1f)
        assertEquals(960f, center[1], 0.1f)
    }

    @Test
    fun testCameraToPreviewMatrix_Camera2_FrontCamera_Mirroring() {
        val dimensions = ViewportDimensions(
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            previewWidth = 1920,
            previewHeight = 1080,
            displayRotationDegrees = 0,
            cameraOrientation = 270,
            displayOrientation = 0,
            isCameraFacingFront = true,
            isUsingCamera2 = true
        )

        val matrix = PreviewMatrixCalculator.calculateCameraToPreviewMatrix(dimensions)
        assertNotNull(matrix)

        // Center coordinate (0, 0) should still map to surface center
        val center = floatArrayOf(0f, 0f)
        matrix.mapPoints(center)
        assertEquals(540f, center[0], 0.1f)
        assertEquals(960f, center[1], 0.1f)
    }

    @Test
    fun testCalculatePreviewToCameraMatrix_Invertible() {
        val dimensions = ViewportDimensions(
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            previewWidth = 1920,
            previewHeight = 1080,
            displayRotationDegrees = 0,
            cameraOrientation = 90,
            isCameraFacingFront = false,
            isUsingCamera2 = true
        )

        val toCamera = PreviewMatrixCalculator.calculatePreviewToCameraMatrix(dimensions)
        val toPreview = PreviewMatrixCalculator.calculateCameraToPreviewMatrix(dimensions)

        // Map surface center (540, 960) -> sensor space -> surface space
        val point = floatArrayOf(540f, 960f)
        toCamera.mapPoints(point)
        assertEquals(0f, point[0], 0.1f)
        assertEquals(0f, point[1], 0.1f)

        toPreview.mapPoints(point)
        assertEquals(540f, point[0], 0.1f)
        assertEquals(960f, point[1], 0.1f)
    }

    @Test
    fun testCalculateFocusAreas_Normal() {
        val areas = PreviewMatrixCalculator.calculateFocusAreas(
            focusX = 100f,
            focusY = 200f,
            focusSize = 50
        )
        assertEquals(1, areas.size)

        val area = areas[0]
        assertEquals(1000, area.weight)
        assertEquals(50, area.rect.left)
        assertEquals(150, area.rect.right)
        assertEquals(150, area.rect.top)
        assertEquals(250, area.rect.bottom)
    }

    @Test
    fun testCalculateFocusAreas_ClampingEdge() {
        // Near top-left boundary
        val areasTopLeft = PreviewMatrixCalculator.calculateFocusAreas(
            focusX = -990f,
            focusY = -990f,
            focusSize = 50
        )
        val rectTopLeft = areasTopLeft[0].rect
        assertEquals(-1000, rectTopLeft.left)
        assertEquals(-900, rectTopLeft.right)
        assertEquals(-1000, rectTopLeft.top)
        assertEquals(-900, rectTopLeft.bottom)

        // Near bottom-right boundary
        val areasBottomRight = PreviewMatrixCalculator.calculateFocusAreas(
            focusX = 990f,
            focusY = 990f,
            focusSize = 50
        )
        val rectBottomRight = areasBottomRight[0].rect
        assertEquals(900, rectBottomRight.left)
        assertEquals(1000, rectBottomRight.right)
        assertEquals(900, rectBottomRight.top)
        assertEquals(1000, rectBottomRight.bottom)
    }

    @Test
    fun testMapScreenToSensor_and_MapSensorToScreen_RoundTrip() {
        val dimensions = ViewportDimensions(
            surfaceWidth = 1080,
            surfaceHeight = 1920,
            previewWidth = 1920,
            previewHeight = 1080,
            displayRotationDegrees = 0,
            cameraOrientation = 90,
            isCameraFacingFront = false,
            isUsingCamera2 = true
        )

        // Center tap (540, 960) -> sensor space should be (0, 0)
        val sensorCenter = PreviewMatrixCalculator.mapScreenToSensor(540f, 960f, dimensions)
        assertEquals(0f, sensorCenter[0], 0.1f)
        assertEquals(0f, sensorCenter[1], 0.1f)

        // Sensor center (0, 0) -> screen space should be (540, 960)
        val screenCenter = PreviewMatrixCalculator.mapSensorToScreen(0f, 0f, dimensions)
        assertEquals(540f, screenCenter[0], 0.1f)
        assertEquals(960f, screenCenter[1], 0.1f)

        // Out-of-bounds screen points clamp properly in sensor space [-1000, 1000]
        val extremePoint = PreviewMatrixCalculator.mapScreenToSensor(-5000f, 5000f, dimensions)
        assertEquals(true, extremePoint[0] >= -1000f && extremePoint[0] <= 1000f)
        assertEquals(true, extremePoint[1] >= -1000f && extremePoint[1] <= 1000f)
    }

    @Test
    fun testCalculateCropRect_AspectFittingAndZoom() {
        // Full sensor 4000x3000 (4:3) with 1x zoom
        val fullCrop = ViewportTransformHelper.calculateCropRect(
            sensorWidth = 4000,
            sensorHeight = 3000,
            aspectRatio = 4.0 / 3.0,
            zoomRatio = 1.0f
        )
        assertEquals(0, fullCrop.left)
        assertEquals(0, fullCrop.top)
        assertEquals(4000, fullCrop.right)
        assertEquals(3000, fullCrop.bottom)

        // 2x zoom on 4000x3000
        val zoom2x = ViewportTransformHelper.calculateCropRect(
            sensorWidth = 4000,
            sensorHeight = 3000,
            aspectRatio = 4.0 / 3.0,
            zoomRatio = 2.0f
        )
        assertEquals(1000, zoom2x.left)
        assertEquals(750, zoom2x.top)
        assertEquals(3000, zoom2x.right)
        assertEquals(2250, zoom2x.bottom)
        assertEquals(2000, zoom2x.width())
        assertEquals(1500, zoom2x.height())
    }
}
