/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.faces

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Handler
import android.util.Log
import android.view.View
import com.hightechif.openkamera.R
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.utils.MyDebug

/**
 * Engine responsible for processing detected faces, transforming face bounding
 * boxes from camera space to screen space, computing face locations relative
 * to the view and UI rotation, and delivering accessibility announcements.
 */
class PreviewFaceDetectionEngine {

    companion object {
        private const val TAG = "PreviewFaceDetEngine"
        const val BOUNDARY_FRACTION = 0.35f
    }

    enum class FaceLocation {
        FACELOCATION_UNSET,
        FACELOCATION_UNKNOWN,
        FACELOCATION_CENTRE,
        FACELOCATION_LEFT,
        FACELOCATION_RIGHT,
        FACELOCATION_TOP,
        FACELOCATION_BOTTOM
    }

    data class FaceLocationResult(
        val nFaces: Int,
        val location: FaceLocation,
        val avgX: Float,
        val avgY: Float
    )

    private val handler = Handler()
    var lastNFaces: Int = -1
        private set
    var lastFaceLocation: FaceLocation = FaceLocation.FACELOCATION_UNSET
        private set

    /**
     * Maps camera face coordinates to screen coordinates and updates face.temp rects.
     */
    fun mapFacesToScreenCoordinates(
        faces: Array<CameraController.Face?>,
        matrix: Matrix,
        tempRect: RectF = RectF()
    ) {
        for (face in faces) {
            if (face != null) {
                tempRect.set(face.rect)
                matrix.mapRect(tempRect)
                tempRect.round(face.temp)
            }
        }
    }

    /**
     * Calculates the aggregate face location (center, left, right, top, bottom)
     * normalized across the preview viewport and adjusted for UI rotation.
     */
    fun calculateFaceLocation(
        faces: Array<CameraController.Face?>,
        matrix: Matrix,
        viewWidth: Int,
        viewHeight: Int,
        uiRotation: Int,
        tempRect: RectF = RectF(),
        bdryFrac: Float = BOUNDARY_FRACTION
    ): FaceLocationResult {
        val nFaces = faces.size
        if (nFaces == 0 || viewWidth <= 0 || viewHeight <= 0) {
            return FaceLocationResult(nFaces, FaceLocation.FACELOCATION_UNKNOWN, 0f, 0f)
        }

        var avgX = 0f
        var avgY = 0f
        var allCentre = true

        for (face in faces) {
            if (face != null) {
                tempRect.set(face.rect)
                matrix.mapRect(tempRect)
                var faceX = tempRect.centerX() / viewWidth.toFloat()
                var faceY = tempRect.centerY() / viewHeight.toFloat()

                if (allCentre) {
                    if (faceX < bdryFrac || faceX > 1.0f - bdryFrac || faceY < bdryFrac || faceY > 1.0f - bdryFrac) {
                        allCentre = false
                    }
                }
                avgX += faceX
                avgY += faceY
            }
        }

        avgX /= nFaces.toFloat()
        avgY /= nFaces.toFloat()

        if (MyDebug.LOG) {
            Log.d(TAG, "avg_x: $avgX, avg_y: $avgY, ui_rotation: $uiRotation")
        }

        val location = if (allCentre) {
            FaceLocation.FACELOCATION_CENTRE
        } else {
            when (uiRotation) {
                0 -> {}
                90 -> {
                    val temp = avgX
                    avgX = avgY
                    avgY = 1.0f - temp
                }
                180 -> {
                    avgX = 1.0f - avgX
                    avgY = 1.0f - avgY
                }
                270 -> {
                    val temp = avgX
                    avgX = 1.0f - avgY
                    avgY = temp
                }
            }

            if (avgX < bdryFrac) {
                FaceLocation.FACELOCATION_LEFT
            } else if (avgX > 1.0f - bdryFrac) {
                FaceLocation.FACELOCATION_RIGHT
            } else if (avgY < bdryFrac) {
                FaceLocation.FACELOCATION_TOP
            } else if (avgY > 1.0f - bdryFrac) {
                FaceLocation.FACELOCATION_BOTTOM
            } else {
                FaceLocation.FACELOCATION_CENTRE
            }
        }

        return FaceLocationResult(nFaces, location, avgX, avgY)
    }

    /**
     * Builds the localized text announcement for face count and position.
     */
    fun formatAnnouncement(
        context: Context,
        nFaces: Int,
        location: FaceLocation
    ): String {
        var string = "$nFaces " + context.resources.getString(
            if (nFaces == 1) R.string.face_detected else R.string.faces_detected
        )
        if (nFaces > 0 && location != FaceLocation.FACELOCATION_UNKNOWN) {
            when (location) {
                FaceLocation.FACELOCATION_CENTRE -> string += " " + context.resources.getString(R.string.centre_of_screen)
                FaceLocation.FACELOCATION_LEFT -> string += " " + context.resources.getString(R.string.left_of_screen)
                FaceLocation.FACELOCATION_RIGHT -> string += " " + context.resources.getString(R.string.right_of_screen)
                FaceLocation.FACELOCATION_TOP -> string += " " + context.resources.getString(R.string.top_of_screen)
                FaceLocation.FACELOCATION_BOTTOM -> string += " " + context.resources.getString(R.string.bottom_of_screen)
                else -> {}
            }
        }
        return string
    }

    /**
     * Processes incoming detected faces, computes location changes, and schedules
     * debounced accessibility announcements if face count/location has changed.
     */
    fun reportFaces(
        faces: Array<CameraController.Face?>,
        context: Context,
        view: View,
        matrix: Matrix,
        viewWidth: Int,
        viewHeight: Int,
        uiRotation: Int,
        tempRect: RectF = RectF()
    ) {
        val result = calculateFaceLocation(
            faces = faces,
            matrix = matrix,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            uiRotation = uiRotation,
            tempRect = tempRect
        )

        val nFaces = result.nFaces
        val faceLocation = result.location

        if (nFaces != lastNFaces || faceLocation != lastFaceLocation) {
            if (nFaces != 0 || lastNFaces != -1) {
                val stringF = formatAnnouncement(context, nFaces, faceLocation)
                if (MyDebug.LOG) Log.d(TAG, stringF)
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    if (MyDebug.LOG) Log.d(TAG, "announceForAccessibility: $stringF")
                    view.announceForAccessibility(stringF)
                }, 500)
            }

            lastNFaces = nFaces
            lastFaceLocation = faceLocation
        }
    }

    fun reset() {
        handler.removeCallbacksAndMessages(null)
        lastNFaces = -1
        lastFaceLocation = FaceLocation.FACELOCATION_UNSET
    }
}
