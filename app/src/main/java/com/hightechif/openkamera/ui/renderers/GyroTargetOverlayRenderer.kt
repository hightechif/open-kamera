/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.withRotation
import com.hightechif.openkamera.SystemOrientation
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.sensors.GyroSensor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Renders 3D gyro target direction dots and upright tilt warning icons during panorama capture.
 */
class GyroTargetOverlayRenderer(context: Context) : OverlayRenderer {

    private val p = Paint()
    private val iconDest = Rect()

    private var enableGyroTargetSpot = false
    private val gyroDirections: MutableList<FloatArray> = ArrayList()
    private val transformedGyroDirection = FloatArray(3)
    private val gyroDirectionUp = FloatArray(3)
    private val transformedGyroDirectionUp = FloatArray(3)

    private var rotateLeftBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.baseline_rotate_left_white_48)
    private var rotateRightBitmap: Bitmap? =
        BitmapFactory.decodeResource(context.resources, R.drawable.baseline_rotate_right_white_48)

    private var viewAngleXPreview = 55.0f
    private var viewAngleYPreview = 43.0f
    private var lastViewAnglesTime: Long = 0

    fun setGyroDirectionMarker(x: Float, y: Float, z: Float) {
        enableGyroTargetSpot = true
        gyroDirections.clear()
        addGyroDirectionMarker(x, y, z)
        gyroDirectionUp[0] = 0f
        gyroDirectionUp[1] = 1f
        gyroDirectionUp[2] = 0f
    }

    fun addGyroDirectionMarker(x: Float, y: Float, z: Float) {
        val vector = floatArrayOf(x, y, z)
        gyroDirections.add(vector)
    }

    fun clearGyroDirectionMarker() {
        enableGyroTargetSpot = false
    }

    private fun updateCachedViewAngles(preview: Preview, timeMs: Long) {
        if (lastViewAnglesTime == 0L || timeMs > lastViewAnglesTime + 10000) {
            viewAngleXPreview = preview.getViewAngleX(true)
            viewAngleYPreview = preview.getViewAngleY(true)
            lastViewAnglesTime = timeMs
        }
    }

    private fun drawGyroSpot(
        canvas: Canvas,
        context: DrawPreviewContext,
        distanceX: Float,
        distanceY: Float,
        dirX: Float,
        dirY: Float,
        radiusDp: Int,
        outline: Boolean
    ) {
        if (outline) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = context.strokeWidth
            p.alpha = 255
        } else {
            p.alpha = 127
        }
        val radius = (radiusDp * context.scaleDp + 0.5f)
        var cx = canvas.width / 2.0f + distanceX
        var cy = canvas.height / 2.0f + distanceY

        cx = max(cx.toDouble(), 0.0).toFloat()
        cx = min(cx.toDouble(), canvas.width.toDouble()).toFloat()
        cy = max(cy.toDouble(), 0.0).toFloat()
        cy = min(cy.toDouble(), canvas.height.toDouble()).toFloat()

        canvas.drawCircle(cx, cy, radius, p)
        p.alpha = 255
        p.style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas, context: DrawPreviewContext, timeMs: Long) {
        val preview: Preview = context.preview
        val cameraController = preview.cameraController

        if (enableGyroTargetSpot && cameraController != null) {
            val gyroSensor: GyroSensor = context.applicationInterface.gyroSensor
            if (gyroSensor.isRecording) {
                val systemOrientation: SystemOrientation = context.mainActivity.systemOrientation
                val systemOrientationPortrait = systemOrientation === SystemOrientation.PORTRAIT

                for (gyroDirection in gyroDirections) {
                    gyroSensor.getRelativeInverseVector(transformedGyroDirection, gyroDirection)
                    gyroSensor.getRelativeInverseVector(transformedGyroDirectionUp, gyroDirectionUp)

                    val angleX: Float
                    val angleY: Float
                    if (systemOrientationPortrait) {
                        angleX = asin(transformedGyroDirection[0].toDouble()).toFloat()
                        angleY = -asin(transformedGyroDirection[1].toDouble()).toFloat()
                    } else {
                        angleX = -asin(transformedGyroDirection[1].toDouble()).toFloat()
                        angleY = -asin(transformedGyroDirection[0].toDouble()).toFloat()
                    }

                    if (abs(angleX.toDouble()) < (0.5 * PI).toFloat() && abs(angleY.toDouble()) < (0.5 * PI).toFloat()) {
                        updateCachedViewAngles(preview, timeMs)
                        val cameraAngleX =
                            if (systemOrientationPortrait) this.viewAngleYPreview else this.viewAngleXPreview
                        val cameraAngleY =
                            if (systemOrientationPortrait) this.viewAngleXPreview else this.viewAngleYPreview

                        var angleScaleX =
                            (canvas.width / (2.0 * tan(Math.toRadians(cameraAngleX / 2.0)))).toFloat()
                        var angleScaleY =
                            (canvas.height / (2.0 * tan(Math.toRadians(cameraAngleY / 2.0)))).toFloat()
                        angleScaleX *= preview.zoomRatio
                        angleScaleY *= preview.zoomRatio

                        val distanceX = angleScaleX * tan(angleX.toDouble()).toFloat()
                        val distanceY = angleScaleY * tan(angleY.toDouble()).toFloat()

                        p.color = Color.WHITE
                        drawGyroSpot(canvas, context, 0.0f, 0.0f, -1.0f, 0.0f, 48, true)

                        p.color = Color.BLUE
                        val dirX = -transformedGyroDirectionUp[1]
                        val dirY = -transformedGyroDirectionUp[0]
                        drawGyroSpot(canvas, context, distanceX, distanceY, dirX, dirY, 45, false)
                    }

                    if (gyroSensor.isUpright != 0 && abs(angleX.toDouble()) <= (20.0 * PI / 180.0).toFloat()) {
                        canvas.withRotation(
                            context.deviceUiRotation.toFloat(),
                            canvas.width / 2.0f,
                            canvas.height / 2.0f
                        ) {
                            val iconSize = (64 * context.scaleDp + 0.5f).toInt()
                            val cyOffset = (80 * context.scaleDp + 0.5f).toInt()
                            val cx = canvas.width / 2
                            val cy = canvas.height / 2 - cyOffset
                            iconDest[cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2] =
                                cy + iconSize / 2

                            val bmp =
                                if (gyroSensor.isUpright > 0) rotateLeftBitmap else rotateRightBitmap
                            if (bmp != null) {
                                canvas.drawBitmap(bmp, null, iconDest, p)
                            }
                            canvas.restore()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        rotateLeftBitmap?.recycle()
        rotateLeftBitmap = null
        rotateRightBitmap?.recycle()
        rotateRightBitmap = null
    }
}
