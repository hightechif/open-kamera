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
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withRotation
import com.hightechif.openkamera.MainActivity.SystemOrientation
import com.hightechif.openkamera.MyApplicationInterface.Alignment
import com.hightechif.openkamera.MyApplicationInterface.PhotoMode
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.Preview
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Renders the electronic level line, pitch lines, horizon snapping highlight, and geo-direction ticks.
 */
class HorizonAngleOverlayRenderer : OverlayRenderer {

    private val p = Paint()
    private val drawRect = RectF()

    private var showAngleLinePref = false
    private var showPitchLinesPref = false
    private var showGeoDirectionLinesPref = false
    private var angleHighlightColorPref = Color.GREEN

    private var viewAngleXPreview = 55.0f
    private var viewAngleYPreview = 43.0f
    private var lastViewAnglesTime: Long = 0

    override fun updateSettings() {
        lastViewAnglesTime = 0
    }

    private fun getAngleStep(zoomRatio: Float): Int {
        var angleStep = 10
        if (zoomRatio >= 10.0f) angleStep = 1
        else if (zoomRatio >= 5.0f) angleStep = 2
        else if (zoomRatio >= 2.0f) angleStep = 5
        return angleStep
    }

    private fun updateCachedViewAngles(preview: Preview, timeMs: Long) {
        if (lastViewAnglesTime == 0L || timeMs > lastViewAnglesTime + 10000) {
            viewAngleXPreview = preview.getViewAngleX(true)
            viewAngleYPreview = preview.getViewAngleY(true)
            lastViewAnglesTime = timeMs
        }
    }

    override fun draw(canvas: Canvas, context: DrawPreviewContext, timeMs: Long) {
        val preview: Preview = context.preview
        val cameraController: CameraController? = preview.cameraController

        showAngleLinePref = context.sharedPreferences.getBoolean(
            PreferenceKeys.SHOW_ANGLE_LINE_PREFERENCE_KEY,
            false
        )
        showPitchLinesPref = context.sharedPreferences.getBoolean(
            PreferenceKeys.SHOW_PITCH_LINES_PREFERENCE_KEY,
            false
        )
        showGeoDirectionLinesPref = context.sharedPreferences.getBoolean(
            PreferenceKeys.SHOW_GEO_DIRECTION_LINES_PREFERENCE_KEY,
            false
        )
        val angleHighlightColorStr = context.sharedPreferences.getString(
            PreferenceKeys.SHOW_ANGLE_HIGHLIGHT_COLOR_PREFERENCE_KEY,
            "#14e715"
        ) ?: "#14e715"
        angleHighlightColorPref = try {
            angleHighlightColorStr.toColorInt()
        } catch (_: Exception) {
            Color.GREEN
        }

        val systemOrientation: SystemOrientation = context.mainActivity.systemOrientation
        val systemOrientationPortrait = systemOrientation === SystemOrientation.PORTRAIT

        val uiState = try {
            context.mainActivity.cameraViewModel.uiState.value
        } catch (_: Exception) {
            null
        }
        val horizonAngleState = uiState?.horizonAngle
        val hasLevelAngle: Boolean = preview.hasLevelAngle() || (horizonAngleState != null)
        val actualShowAngleLinePref =
            if (context.applicationInterface.photoMode === PhotoMode.Panorama) {
                !context.applicationInterface.gyroSensor.isRecording
            } else {
                showAngleLinePref
            }

        val allowAngleLines = cameraController != null && !preview.isPreviewPaused

        if (allowAngleLines && hasLevelAngle && (actualShowAngleLinePref || showPitchLinesPref || showGeoDirectionLinesPref)) {
            val levelAngle: Double = horizonAngleState?.angleDegrees ?: preview.levelAngle
            val hasPitchAngle: Boolean = preview.hasPitchAngle()
            val pitchAngle: Double = preview.pitchAngle
            val hasGeoDirection: Boolean =
                preview.hasGeoDirection() || ((uiState?.compassDegrees ?: 0.0f) != 0.0f)
            val geoDirection: Double = if ((uiState?.compassDegrees ?: 0.0f) != 0.0f) {
                uiState!!.compassDegrees.toDouble()
            } else {
                preview.geoDirection
            }

            val radiusDps =
                if (context.deviceUiRotation == 90 || context.deviceUiRotation == 270) 60 else 80
            val radius = (radiusDps * context.scaleDp + 0.5f).toInt()
            val oRadius = (10 * context.scaleDp + 0.5f).toInt()
            var angle: Double = -preview.origLevelAngle

            val rotation: Int = context.mainActivity.getDisplayRotation(false)
            when (rotation) {
                Surface.ROTATION_90 -> angle -= 90.0
                Surface.ROTATION_270 -> angle += 90.0
                Surface.ROTATION_180 -> angle += 180.0
                Surface.ROTATION_0 -> {}
            }

            val cx = canvas.width / 2
            val cy = canvas.height / 2

            val isLevel = hasLevelAngle && abs(levelAngle) <= RendererUtils.CLOSE_LEVEL_ANGLE

            val lineAlpha = 160
            val hthickness = (0.5f * context.scaleDp + 0.5f)
            val shadowRadius = max(hthickness.toDouble(), 1.0).toFloat()
            p.reset()
            p.style = Paint.Style.FILL

            if (actualShowAngleLinePref && preview.hasLevelAngleStable()) {
                p.setShadowLayer(shadowRadius, 0.0f, 0.0f, Color.BLACK)
                p.color = if (isLevel) angleHighlightColorPref else Color.WHITE
                p.alpha = lineAlpha
                drawRect[(cx - radius - oRadius).toFloat(), cy - hthickness, (cx - radius).toFloat()] =
                    cy + hthickness
                canvas.drawRoundRect(drawRect, hthickness, hthickness, p)
                drawRect[(cx + radius).toFloat(), cy - hthickness, (cx + radius + oRadius).toFloat()] =
                    cy + hthickness
                canvas.drawRoundRect(drawRect, hthickness, hthickness, p)
                p.clearShadowLayer()
            }

            canvas.withRotation(angle.toFloat(), cx.toFloat(), cy.toFloat()) {
                if (actualShowAngleLinePref && preview.hasLevelAngleStable()) {
                    p.setShadowLayer(shadowRadius, 0.0f, 0.0f, Color.BLACK)
                    p.color = if (isLevel) angleHighlightColorPref else Color.WHITE
                    p.alpha = lineAlpha
                    drawRect[(cx - radius).toFloat(), cy - hthickness, (cx + radius).toFloat()] =
                        cy + hthickness
                    drawRoundRect(drawRect, hthickness, hthickness, p)

                    // Vertical crossbar
                    drawRect[cx - hthickness, cy - radius / 2.0f, cx + hthickness] =
                        cy + radius / 2.0f
                    drawRoundRect(drawRect, hthickness, hthickness, p)

                    if (isLevel) {
                        p.color = angleHighlightColorPref
                        p.alpha = lineAlpha
                        drawRect[(cx - radius).toFloat(), cy - 6 * hthickness, (cx + radius).toFloat()] =
                            cy - 4 * hthickness
                        drawRoundRect(drawRect, hthickness, hthickness, p)
                    }

                    p.clearShadowLayer()
                }

                updateCachedViewAngles(preview, timeMs)
                val cameraAngleX =
                    if (systemOrientationPortrait) viewAngleYPreview else viewAngleXPreview
                val cameraAngleY =
                    if (systemOrientationPortrait) viewAngleXPreview else viewAngleYPreview

                val angleScaleX =
                    (width / (2.0 * tan(Math.toRadians(cameraAngleX / 2.0)))).toFloat()
                val angleScaleY =
                    (height / (2.0 * tan(Math.toRadians(cameraAngleY / 2.0)))).toFloat()

                var angleScale =
                    sqrt((angleScaleX * angleScaleX + angleScaleY * angleScaleY).toDouble()).toFloat()
                angleScale *= preview.zoomRatio

                val angleStep = getAngleStep(preview.zoomRatio)

                if (hasPitchAngle && showPitchLinesPref) {
                    val pitchRadiusDps =
                        if (context.deviceUiRotation == 90 || context.deviceUiRotation == 270) 80 else 100
                    val pitchRadius = (pitchRadiusDps * context.scaleDp + 0.5f).toInt()
                    var latitudeAngle = -90
                    while (latitudeAngle <= 90) {
                        val thisAngle = pitchAngle - latitudeAngle
                        if (abs(thisAngle) < 90.0) {
                            val pitchDistance =
                                angleScale * tan(Math.toRadians(thisAngle)).toFloat()
                            p.color = Color.WHITE
                            p.textAlign = Paint.Align.LEFT
                            if (latitudeAngle == 0 && abs(pitchAngle) < 1.0) {
                                p.alpha = 255
                            } else if (latitudeAngle == 90 && abs(pitchAngle - 90) < 3.0) {
                                p.alpha = 255
                            } else if (latitudeAngle == -90 && abs(pitchAngle + 90) < 3.0) {
                                p.alpha = 255
                            } else {
                                p.alpha = lineAlpha
                            }
                            p.setShadowLayer(shadowRadius, 0.0f, 0.0f, Color.BLACK)
                            drawRect[(cx - pitchRadius).toFloat(), cy + pitchDistance - hthickness, (cx + pitchRadius).toFloat()] =
                                cy + pitchDistance + hthickness
                            drawRoundRect(drawRect, hthickness, hthickness, p)
                            p.clearShadowLayer()

                            context.applicationInterface.drawTextWithBackground(
                                this,
                                p,
                                latitudeAngle.toString() + "\u00B0",
                                p.color,
                                Color.BLACK,
                                (cx + pitchRadius + 4 * hthickness).toInt(),
                                (cy + pitchDistance - 2 * hthickness).toInt(),
                                Alignment.ALIGNMENT_CENTRE
                            )
                        }
                        latitudeAngle += angleStep
                    }
                }

                if (hasGeoDirection && hasPitchAngle && showGeoDirectionLinesPref) {
                    val geoRadiusDps =
                        if (context.deviceUiRotation == 90 || context.deviceUiRotation == 270) 100 else 80
                    val geoRadius = (geoRadiusDps * context.scaleDp + 0.5f).toInt()
                    val geoAngle = Math.toDegrees(geoDirection).toFloat()
                    var longitudeAngle = 0
                    while (longitudeAngle < 360) {
                        var thisAngle = (longitudeAngle - geoAngle).toDouble()
                        while (thisAngle >= 360.0) thisAngle -= 360.0
                        while (thisAngle < -360.0) thisAngle += 360.0
                        if (thisAngle > 180.0) thisAngle = -(360.0 - thisAngle)
                        if (abs(thisAngle) < 90.0) {
                            val geoDistance = angleScale * tan(Math.toRadians(thisAngle)).toFloat()
                            p.color = Color.WHITE
                            p.textAlign = Paint.Align.CENTER
                            p.alpha = lineAlpha
                            p.setShadowLayer(shadowRadius, 0.0f, 0.0f, Color.BLACK)
                            drawRect[cx + geoDistance - hthickness, (cy - geoRadius).toFloat(), cx + geoDistance + hthickness] =
                                (cy + geoRadius).toFloat()
                            drawRoundRect(drawRect, hthickness, hthickness, p)
                            p.clearShadowLayer()

                            context.applicationInterface.drawTextWithBackground(
                                this,
                                p,
                                longitudeAngle.toString() + "\u00B0",
                                p.color,
                                Color.BLACK,
                                (cx + geoDistance).toInt(),
                                (cy - geoRadius - 4 * hthickness).toInt(),
                                Alignment.ALIGNMENT_BOTTOM
                            )
                        }
                        longitudeAngle += angleStep
                    }
                }

                p.alpha = 255
                p.style = Paint.Style.FILL
            }
        }

        if (context.hasAutoStabiliseCrop) {
            val w2 = context.autoStabiliseCrop[0]
            val h2 = context.autoStabiliseCrop[1]
            val cx = canvas.width / 2
            val cy = canvas.height / 2

            val left = (canvas.width - w2) / 2.0f
            val top = (canvas.height - h2) / 2.0f
            val right = (canvas.width + w2) / 2.0f
            val bottom = (canvas.height + h2) / 2.0f

            val levelAngle = preview.origLevelAngle

            canvas.withRotation(-levelAngle.toFloat(), cx.toFloat(), cy.toFloat()) {
                val oDist = sqrt((width * width + height * height).toDouble()).toFloat()
                val oLeft = (width - oDist) / 2.0f
                val oTop = (height - oDist) / 2.0f
                val oRight = (width + oDist) / 2.0f
                val oBottom = (height + oDist) / 2.0f
                p.style = Paint.Style.FILL
                p.color = Color.rgb(0, 0, 0)
                p.alpha = RendererUtils.CROP_SHADING_ALPHA
                drawRect(oLeft, oTop, left, oBottom, p)
                drawRect(right, oTop, oRight, oBottom, p)
                drawRect(left, oTop, right, top, p)
                drawRect(left, bottom, right, oBottom, p)

                val isLevel = hasLevelAngle && abs(levelAngle) <= RendererUtils.CLOSE_LEVEL_ANGLE
                p.color = if (isLevel) angleHighlightColorPref else Color.WHITE
                p.style = Paint.Style.STROKE
                p.strokeWidth = context.strokeWidth

                drawRect(left, top, right, bottom, p)
            }

            p.style = Paint.Style.FILL
            p.alpha = 255
        }
    }
}
