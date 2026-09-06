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
import com.hightechif.openkamera.MainActivity.SystemOrientation
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.Preview
import kotlin.math.abs

/**
 * Renders aspect ratio crop guides (1:1, 4:3, 16:9, CinemaScope 2.35:1/2.40:1) and dark framing masks.
 */
class CropGuideOverlayRenderer : OverlayRenderer {

    private val p = Paint()

    override fun draw(canvas: Canvas, context: DrawPreviewContext, timeMs: Long) {
        val preview: Preview = context.preview
        val cameraController: CameraController? = preview.cameraController

        if (preview.isVideo || context.previewSizeWysiwygPref) {
            val preferenceCropGuide = context.sharedPreferences.getString(
                PreferenceKeys.SHOW_CROP_GUIDE_PREFERENCE_KEY,
                "crop_guide_none"
            ) ?: "crop_guide_none"

            if (cameraController != null && preview.targetRatio > 0.0 && preferenceCropGuide != "crop_guide_none") {
                var cropRatio = -1.0
                when (preferenceCropGuide) {
                    "crop_guide_1" -> cropRatio = 1.0
                    "crop_guide_1.25" -> cropRatio = 1.25
                    "crop_guide_1.33" -> cropRatio = 1.33333333
                    "crop_guide_1.4" -> cropRatio = 1.4
                    "crop_guide_1.5" -> cropRatio = 1.5
                    "crop_guide_1.78" -> cropRatio = 1.77777778
                    "crop_guide_1.85" -> cropRatio = 1.85
                    "crop_guide_2" -> cropRatio = 2.0
                    "crop_guide_2.33" -> cropRatio = 2.33333333
                    "crop_guide_2.35" -> cropRatio = 2.35006120 // actually 1920:817
                    "crop_guide_2.4" -> cropRatio = 2.4
                }

                if (cropRatio > 0.0) {
                    var previewAspectRatio: Double = preview.currentPreviewAspectRatio
                    val systemOrientation: SystemOrientation = context.mainActivity.systemOrientation
                    val systemOrientationPortrait = systemOrientation === SystemOrientation.PORTRAIT
                    if (systemOrientationPortrait) {
                        cropRatio = 1.0 / cropRatio
                        previewAspectRatio = 1.0 / previewAspectRatio
                    }

                    if (abs(previewAspectRatio - cropRatio) > 1.0e-5) {
                        p.reset()
                        p.style = Paint.Style.FILL
                        p.color = Color.rgb(0, 0, 0)
                        p.alpha = RendererUtils.CROP_SHADING_ALPHA
                        var left = 1
                        var top = 1
                        var right = canvas.width - 1
                        var bottom = canvas.height - 1

                        if (cropRatio > previewAspectRatio) {
                            val newHheight = (canvas.width.toDouble()) / (2.0f * cropRatio)
                            top = (canvas.height / 2 - newHheight.toInt())
                            bottom = (canvas.height / 2 + newHheight.toInt())

                            canvas.drawRect(0f, 0f, canvas.width.toFloat(), top.toFloat(), p)
                            canvas.drawRect(
                                0f,
                                bottom.toFloat(),
                                canvas.width.toFloat(),
                                canvas.height.toFloat(),
                                p
                            )
                        } else {
                            val newHwidth = ((canvas.height.toDouble()) * cropRatio) / 2.0f
                            left = (canvas.width / 2 - newHwidth.toInt())
                            right = (canvas.width / 2 + newHwidth.toInt())

                            canvas.drawRect(0f, 0f, left.toFloat(), canvas.height.toFloat(), p)
                            canvas.drawRect(
                                right.toFloat(),
                                0f,
                                canvas.width.toFloat(),
                                canvas.height.toFloat(),
                                p
                            )
                        }

                        p.style = Paint.Style.STROKE
                        p.strokeWidth = context.strokeWidth
                        p.color = Color.rgb(255, 235, 59) // Yellow 500
                        canvas.drawRect(
                            left.toFloat(),
                            top.toFloat(),
                            right.toFloat(),
                            bottom.toFloat(),
                            p
                        )
                        p.style = Paint.Style.FILL
                        p.alpha = 255
                    }
                }
            }
        }
    }
}
