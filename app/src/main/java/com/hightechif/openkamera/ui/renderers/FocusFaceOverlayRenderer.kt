/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Pair
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preview.Preview

/**
 * Renders touch focus brackets, continuous AF pulsing circles, and yellow face detection boxes.
 */
class FocusFaceOverlayRenderer : OverlayRenderer {

    private val p = Paint()
    private var continuousFocusMoving = false
    private var continuousFocusMovingMs: Long = 0
    private var takingPicture = false

    fun onContinuousFocusMove(start: Boolean) {
        if (start) {
            continuousFocusMoving = true
            continuousFocusMovingMs = System.currentTimeMillis()
        }
    }

    fun clearContinuousFocusMove() {
        if (continuousFocusMoving) {
            continuousFocusMoving = false
            continuousFocusMovingMs = 0
        }
    }

    fun setTakingPicture(takingPicture: Boolean) {
        this.takingPicture = takingPicture
    }

    override fun draw(canvas: Canvas, context: DrawPreviewContext, timeMs: Long) {
        val preview: Preview = context.preview
        val cameraController: CameraController? = preview.cameraController

        // Continuous focus movement animation
        if (cameraController != null && continuousFocusMoving && !takingPicture) {
            val dt = timeMs - continuousFocusMovingMs
            val length: Long = 1000
            if (dt <= length) {
                val frac = dt.toFloat() / length.toFloat()
                val posX = canvas.width / 2.0f
                val posY = canvas.height / 2.0f
                val minRadius = (40 * context.scaleDp + 0.5f)
                val maxRadius = (60 * context.scaleDp + 0.5f)
                val radius = if (frac < 0.5f) {
                    val alpha = frac * 2.0f
                    (1.0f - alpha) * minRadius + alpha * maxRadius
                } else {
                    val alpha = (frac - 0.5f) * 2.0f
                    (1.0f - alpha) * maxRadius + alpha * minRadius
                }
                p.color = Color.WHITE
                p.style = Paint.Style.STROKE
                p.strokeWidth = context.strokeWidth
                canvas.drawCircle(posX, posY, radius, p)
                p.style = Paint.Style.FILL
            } else {
                clearContinuousFocusMove()
            }
        }

        // Autofocus bracket animation
        if (preview.isFocusWaiting || preview.isFocusRecentSuccess || preview.isFocusRecentFailure) {
            val timeSinceFocusStarted: Long = preview.timeSinceStartedAutoFocus()
            val minRadius = (40 * context.scaleDp + 0.5f)
            val maxRadius = (45 * context.scaleDp + 0.5f)
            var radius = minRadius
            if (timeSinceFocusStarted > 0) {
                val length: Long = 500
                var frac = timeSinceFocusStarted.toFloat() / length.toFloat()
                if (frac > 1.0f) frac = 1.0f
                radius = if (frac < 0.5f) {
                    val alpha = frac * 2.0f
                    (1.0f - alpha) * minRadius + alpha * maxRadius
                } else {
                    val alpha = (frac - 0.5f) * 2.0f
                    (1.0f - alpha) * maxRadius + alpha * minRadius
                }
            }
            val size = radius.toInt()

            p.color = when {
                preview.isFocusRecentSuccess -> Color.rgb(20, 231, 21) // Green A400
                preview.isFocusRecentFailure -> Color.rgb(244, 67, 54) // Red 500
                else -> Color.WHITE
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = context.strokeWidth

            val posX: Int
            val posY: Int
            if (preview.hasFocusArea()) {
                val focusPos: Pair<Int, Int> = preview.focusPos
                posX = focusPos.first
                posY = focusPos.second
            } else {
                posX = canvas.width / 2
                posY = canvas.height / 2
            }
            val frac = 0.5f

            // Horizontal bracket strokes
            canvas.drawLine((posX - size).toFloat(), (posY - size).toFloat(), posX - frac * size, (posY - size).toFloat(), p)
            canvas.drawLine(posX + frac * size, (posY - size).toFloat(), (posX + size).toFloat(), (posY - size).toFloat(), p)
            canvas.drawLine((posX - size).toFloat(), (posY + size).toFloat(), posX - frac * size, (posY + size).toFloat(), p)
            canvas.drawLine(posX + frac * size, (posY + size).toFloat(), (posX + size).toFloat(), (posY + size).toFloat(), p)

            // Vertical bracket strokes
            canvas.drawLine((posX - size).toFloat(), (posY - size).toFloat(), (posX - size).toFloat(), posY - frac * size, p)
            canvas.drawLine((posX - size).toFloat(), posY + frac * size, (posX - size).toFloat(), (posY + size).toFloat(), p)
            canvas.drawLine((posX + size).toFloat(), (posY - size).toFloat(), (posX + size).toFloat(), posY - frac * size, p)
            canvas.drawLine((posX + size).toFloat(), posY + frac * size, (posX + size).toFloat(), (posY + size).toFloat(), p)
            p.style = Paint.Style.FILL
        }

        // Face detection boxes
        val facesDetected: Array<CameraController.Face> = preview.facesDetected
        if (facesDetected.isNotEmpty()) {
            p.color = Color.rgb(255, 235, 59) // Yellow 500
            p.style = Paint.Style.STROKE
            p.strokeWidth = context.strokeWidth
            for (face in facesDetected) {
                if (face.score >= 50) {
                    canvas.drawRect(face.temp, p)
                }
            }
            p.style = Paint.Style.FILL
        }
    }
}
