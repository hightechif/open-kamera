/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.faces

import android.graphics.Matrix
import android.graphics.Rect
import com.hightechif.openkamera.cameracontroller.CameraController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewFaceDetectionEngineUnitTest {

    private lateinit var engine: PreviewFaceDetectionEngine

    @Before
    fun setUp() {
        engine = PreviewFaceDetectionEngine()
    }

    @Test
    fun testEmptyFacesReturnsUnknown() {
        val result = engine.calculateFaceLocation(
            faces = emptyArray(),
            matrix = Matrix(),
            viewWidth = 1080,
            viewHeight = 1920,
            uiRotation = 0
        )
        assertEquals(0, result.nFaces)
        assertEquals(PreviewFaceDetectionEngine.FaceLocation.FACELOCATION_UNKNOWN, result.location)
    }

    @Test
    fun testCenterFaceLocation() {
        val face = CameraController.Face(50, Rect(400, 800, 600, 1000))
        val faces: Array<CameraController.Face?> = arrayOf(face)

        val result = engine.calculateFaceLocation(
            faces = faces,
            matrix = Matrix(),
            viewWidth = 1000,
            viewHeight = 2000,
            uiRotation = 0
        )

        assertEquals(1, result.nFaces)
        assertEquals(PreviewFaceDetectionEngine.FaceLocation.FACELOCATION_CENTRE, result.location)
    }

    @Test
    fun testLeftFaceLocation() {
        val face = CameraController.Face(50, Rect(50, 800, 150, 1000)) // centerX = 100 / 1000 = 0.1 (< 0.35)
        val faces: Array<CameraController.Face?> = arrayOf(face)

        val result = engine.calculateFaceLocation(
            faces = faces,
            matrix = Matrix(),
            viewWidth = 1000,
            viewHeight = 2000,
            uiRotation = 0
        )

        assertEquals(1, result.nFaces)
        assertEquals(PreviewFaceDetectionEngine.FaceLocation.FACELOCATION_LEFT, result.location)
    }

    @Test
    fun testRightFaceLocationWithRotation() {
        val face = CameraController.Face(50, Rect(850, 800, 950, 1000)) // centerX = 0.9 (> 0.65)
        val faces: Array<CameraController.Face?> = arrayOf(face)

        val result0 = engine.calculateFaceLocation(
            faces = faces,
            matrix = Matrix(),
            viewWidth = 1000,
            viewHeight = 2000,
            uiRotation = 0
        )
        assertEquals(PreviewFaceDetectionEngine.FaceLocation.FACELOCATION_RIGHT, result0.location)

        val result180 = engine.calculateFaceLocation(
            faces = faces,
            matrix = Matrix(),
            viewWidth = 1000,
            viewHeight = 2000,
            uiRotation = 180
        )
        assertEquals(PreviewFaceDetectionEngine.FaceLocation.FACELOCATION_LEFT, result180.location)
    }

    @Test
    fun testMapFacesToScreenCoordinates() {
        val face = CameraController.Face(80, Rect(100, 100, 200, 200))
        val faces: Array<CameraController.Face?> = arrayOf(face)
        val matrix = Matrix().apply { setScale(2f, 2f) }

        engine.mapFacesToScreenCoordinates(faces, matrix)

        assertEquals(200, face.temp.left)
        assertEquals(200, face.temp.top)
        assertEquals(400, face.temp.right)
        assertEquals(400, face.temp.bottom)
    }
}
