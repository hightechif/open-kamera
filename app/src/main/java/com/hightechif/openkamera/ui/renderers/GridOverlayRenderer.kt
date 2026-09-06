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
import android.graphics.RectF
import android.view.Surface
import androidx.core.graphics.withSave
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.Preview
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders composition framing grids (Rule of Thirds, Phi, Crosshair, Golden Spiral, Golden Triangle, Diagonals).
 */
class GridOverlayRenderer : OverlayRenderer {

    private val p = Paint()
    private val drawRect = RectF()
    private var preferenceGridPref: String? = null

    override fun updateSettings() {
        // Will be refreshed per-frame or on preference change
    }

    override fun draw(canvas: Canvas, context: DrawPreviewContext, timeMs: Long) {
        val preview: Preview = context.preview
        val cameraController: CameraController = preview.cameraController ?: return
        val gridKey = try {
            context.mainActivity.cameraViewModel.uiState.value.gridType.key
        } catch (_: Exception) {
            context.sharedPreferences.getString(
                PreferenceKeys.SHOW_GRID_PREFERENCE_KEY,
                "preference_grid_none"
            )
        } ?: "preference_grid_none"

        if (gridKey == "preference_grid_none") {
            return
        }
        if (preview.isPreviewPaused) {
            return
        }

        var canvasRotated = false
        var w2 = canvas.width
        var h2 = canvas.height
        if (context.hasAutoStabiliseCrop) {
            w2 = context.autoStabiliseCrop[0]
            h2 = context.autoStabiliseCrop[1]
            var levelAngle = preview.origLevelAngle
            val rotation = context.mainActivity.getDisplayRotation(false)
            when (rotation) {
                Surface.ROTATION_90 -> {
                    levelAngle += 90.0
                    w2 = context.autoStabiliseCrop[1]
                    h2 = context.autoStabiliseCrop[0]
                }
                Surface.ROTATION_270 -> {
                    levelAngle -= 90.0
                    w2 = context.autoStabiliseCrop[1]
                    h2 = context.autoStabiliseCrop[0]
                }
                Surface.ROTATION_180 -> {
                    levelAngle += 180.0
                }
                Surface.ROTATION_0 -> {}
            }
            canvas.save()
            canvas.rotate(-levelAngle.toFloat(), canvas.width / 2.0f, canvas.height / 2.0f)
            canvas.translate((canvas.width - w2) / 2.0f, (canvas.height - h2) / 2.0f)
            canvasRotated = true
        }

        p.reset()
        p.strokeWidth = context.strokeWidth

        when (gridKey) {
            "preference_grid_3x3" -> {
                p.color = Color.WHITE
                canvas.drawLine(w2 / 3.0f, 0.0f, w2 / 3.0f, h2 - 1.0f, p)
                canvas.drawLine(2.0f * w2 / 3.0f, 0.0f, 2.0f * w2 / 3.0f, h2 - 1.0f, p)
                canvas.drawLine(0.0f, h2 / 3.0f, w2 - 1.0f, h2 / 3.0f, p)
                canvas.drawLine(0.0f, 2.0f * h2 / 3.0f, w2 - 1.0f, 2.0f * h2 / 3.0f, p)
            }

            "preference_grid_phi_3x3" -> {
                p.color = Color.WHITE
                canvas.drawLine(w2 / 2.618f, 0.0f, w2 / 2.618f, h2 - 1.0f, p)
                canvas.drawLine(1.618f * w2 / 2.618f, 0.0f, 1.618f * w2 / 2.618f, h2 - 1.0f, p)
                canvas.drawLine(0.0f, h2 / 2.618f, w2 - 1.0f, h2 / 2.618f, p)
                canvas.drawLine(0.0f, 1.618f * h2 / 2.618f, w2 - 1.0f, 1.618f * h2 / 2.618f, p)
            }

            "preference_grid_4x2" -> {
                p.color = Color.GRAY
                canvas.drawLine(w2 / 4.0f, 0.0f, w2 / 4.0f, h2 - 1.0f, p)
                canvas.drawLine(w2 / 2.0f, 0.0f, w2 / 2.0f, h2 - 1.0f, p)
                canvas.drawLine(3.0f * w2 / 4.0f, 0.0f, 3.0f * w2 / 4.0f, h2 - 1.0f, p)
                canvas.drawLine(0.0f, h2 / 2.0f, w2 - 1.0f, h2 / 2.0f, p)
                p.color = Color.WHITE
                val crosshairsRadius = (20 * context.scaleDp + 0.5f).toInt()

                canvas.drawLine(
                    w2 / 2.0f,
                    h2 / 2.0f - crosshairsRadius,
                    w2 / 2.0f,
                    h2 / 2.0f + crosshairsRadius,
                    p
                )
                canvas.drawLine(
                    w2 / 2.0f - crosshairsRadius,
                    h2 / 2.0f,
                    w2 / 2.0f + crosshairsRadius,
                    h2 / 2.0f,
                    p
                )
            }

            "preference_grid_crosshair" -> {
                p.color = Color.WHITE
                canvas.drawLine(
                    canvas.width / 2.0f,
                    0.0f,
                    canvas.width / 2.0f,
                    canvas.height - 1.0f,
                    p
                )
                canvas.drawLine(
                    0.0f,
                    canvas.height / 2.0f,
                    canvas.width - 1.0f,
                    canvas.height / 2.0f,
                    p
                )
            }

            "preference_grid_golden_spiral_right", "preference_grid_golden_spiral_left", "preference_grid_golden_spiral_upside_down_right", "preference_grid_golden_spiral_upside_down_left" -> {
                canvas.save()
                when (gridKey) {
                    "preference_grid_golden_spiral_left" -> canvas.scale(
                        -1.0f,
                        1.0f,
                        canvas.width * 0.5f,
                        canvas.height * 0.5f
                    )
                    "preference_grid_golden_spiral_right" -> {}
                    "preference_grid_golden_spiral_upside_down_left" -> canvas.rotate(
                        180.0f,
                        canvas.width * 0.5f,
                        canvas.height * 0.5f
                    )
                    "preference_grid_golden_spiral_upside_down_right" -> canvas.scale(
                        1.0f,
                        -1.0f,
                        canvas.width * 0.5f,
                        canvas.height * 0.5f
                    )
                }
                p.color = Color.WHITE
                p.style = Paint.Style.STROKE
                p.strokeWidth = context.strokeWidth
                var fibb = 34
                var fibbN = 21
                var left = 0
                var top = 0
                var fullWidth = canvas.width
                var fullHeight = canvas.height
                var width = (fullWidth * (fibbN.toDouble()) / (fibb).toDouble()).toInt()
                var height = fullHeight

                var count = 0
                while (count < 2) {
                    canvas.withSave {
                        drawRect[left.toFloat(), top.toFloat(), (left + width).toFloat()] =
                            (top + height).toFloat()
                        clipRect(drawRect)
                        drawRect(drawRect, p)
                        drawRect[left.toFloat(), top.toFloat(), (left + 2 * width).toFloat()] =
                            (top + 2 * height).toFloat()
                        drawOval(drawRect, p)
                    }

                    var oldFibb = fibb
                    fibb = fibbN
                    fibbN = oldFibb - fibb

                    left += width
                    fullWidth -= width
                    width = fullWidth
                    height = (height * (fibbN.toDouble()) / (fibb).toDouble()).toInt()

                    canvas.withSave {
                        drawRect[left.toFloat(), top.toFloat(), (left + width).toFloat()] =
                            (top + height).toFloat()
                        clipRect(drawRect)
                        drawRect(drawRect, p)
                        drawRect[(left - width).toFloat(), top.toFloat(), (left + width).toFloat()] =
                            (top + 2 * height).toFloat()
                        drawOval(drawRect, p)
                    }

                    oldFibb = fibb
                    fibb = fibbN
                    fibbN = oldFibb - fibb

                    top += height
                    fullHeight -= height
                    height = fullHeight
                    width = (width * (fibbN.toDouble()) / (fibb).toDouble()).toInt()
                    left += fullWidth - width

                    canvas.withSave {
                        drawRect[left.toFloat(), top.toFloat(), (left + width).toFloat()] =
                            (top + height).toFloat()
                        clipRect(drawRect)
                        drawRect(drawRect, p)
                        drawRect[(left - width).toFloat(), (top - height).toFloat(), (left + width).toFloat()] =
                            (top + height).toFloat()
                        drawOval(drawRect, p)
                    }

                    oldFibb = fibb
                    fibb = fibbN
                    fibbN = oldFibb - fibb

                    fullWidth -= width
                    width = fullWidth
                    left -= width
                    height = (height * (fibbN.toDouble()) / (fibb).toDouble()).toInt()
                    top += fullHeight - height

                    canvas.withSave {
                        drawRect[left.toFloat(), top.toFloat(), (left + width).toFloat()] =
                            (top + height).toFloat()
                        clipRect(drawRect)
                        drawRect(drawRect, p)
                        drawRect[left.toFloat(), (top - height).toFloat(), (left + 2 * width).toFloat()] =
                            (top + height).toFloat()
                        drawOval(drawRect, p)
                    }

                    oldFibb = fibb
                    fibb = fibbN
                    fibbN = oldFibb - fibb

                    fullHeight -= height
                    height = fullHeight
                    top -= height
                    width = (width * (fibbN.toDouble()) / (fibb).toDouble()).toInt()
                    count++
                }

                canvas.restore()
                p.style = Paint.Style.FILL
            }

            "preference_grid_golden_triangle_1", "preference_grid_golden_triangle_2" -> {
                p.color = Color.WHITE
                val theta = atan2(canvas.width.toDouble(), canvas.height.toDouble())
                val dist = canvas.height * cos(theta)
                val distX = (dist * sin(theta)).toFloat()
                val distY = (dist * cos(theta)).toFloat()
                if (gridKey == "preference_grid_golden_triangle_1") {
                    canvas.drawLine(0.0f, canvas.height - 1.0f, canvas.width - 1.0f, 0.0f, p)
                    canvas.drawLine(0.0f, 0.0f, distX, canvas.height - distY, p)
                    canvas.drawLine(
                        canvas.width - 1.0f - distX,
                        distY - 1.0f,
                        canvas.width - 1.0f,
                        canvas.height - 1.0f,
                        p
                    )
                } else {
                    canvas.drawLine(0.0f, 0.0f, canvas.width - 1.0f, canvas.height - 1.0f, p)
                    canvas.drawLine(
                        canvas.width - 1.0f,
                        0.0f,
                        canvas.width - 1.0f - distX,
                        canvas.height - distY,
                        p
                    )
                    canvas.drawLine(distX, distY - 1.0f, 0.0f, canvas.height - 1.0f, p)
                }
            }

            "preference_grid_diagonals" -> {
                p.color = Color.WHITE
                canvas.drawLine(0.0f, 0.0f, canvas.height - 1.0f, canvas.height - 1.0f, p)
                canvas.drawLine(canvas.height - 1.0f, 0.0f, 0.0f, canvas.height - 1.0f, p)
                val diff = canvas.width - canvas.height
                canvas.drawLine(
                    diff.toFloat(),
                    0.0f,
                    diff + canvas.height - 1.0f,
                    canvas.height - 1.0f,
                    p
                )
                canvas.drawLine(
                    diff + canvas.height - 1.0f,
                    0.0f,
                    diff.toFloat(),
                    canvas.height - 1.0f,
                    p
                )
            }
        }

        if (canvasRotated) {
            canvas.restore()
        }
    }
}
