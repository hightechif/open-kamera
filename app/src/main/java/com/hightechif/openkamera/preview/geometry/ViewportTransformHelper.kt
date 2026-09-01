/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.geometry

import android.graphics.Matrix
import android.graphics.RectF
import android.view.Surface
import android.view.View.MeasureSpec
import kotlin.math.max

/**
 * Encapsulates view layout measurement calculations and TextureView matrix transformations.
 */
object ViewportTransformHelper {

    /**
     * Calculates the measure specs (widthSpec, heightSpec) to maintain correct preview aspect ratio within available layout bounds.
     */
    fun calculateMeasureSpec(
        widthSpec: Int,
        heightSpec: Int,
        aspectRatio: Double,
        hPadding: Int,
        vPadding: Int,
        relativeRotation: Int
    ): Pair<Int, Int> {
        var previewWidth = MeasureSpec.getSize(widthSpec) - hPadding
        var previewHeight = MeasureSpec.getSize(heightSpec) - vPadding

        var effectiveAspectRatio = aspectRatio
        if (relativeRotation % 180 != 0) {
            effectiveAspectRatio = 1.0 / effectiveAspectRatio
        }

        if (previewWidth > previewHeight * effectiveAspectRatio) {
            previewWidth = (previewHeight.toDouble() * effectiveAspectRatio).toInt()
        } else {
            previewHeight = (previewWidth.toDouble() / effectiveAspectRatio).toInt()
        }

        previewWidth += hPadding
        previewHeight += vPadding

        val outWidthSpec = MeasureSpec.makeMeasureSpec(previewWidth, MeasureSpec.EXACTLY)
        val outHeightSpec = MeasureSpec.makeMeasureSpec(previewHeight, MeasureSpec.EXACTLY)

        return Pair(outWidthSpec, outHeightSpec)
    }

    /**
     * Calculates the transformation matrix for a TextureView to handle rotation and aspect-ratio scaling.
     */
    fun calculateTextureTransform(
        textureViewWidth: Int,
        textureViewHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
        displayRotation: Int
    ): Matrix {
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, textureViewWidth.toFloat(), textureViewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewHeight.toFloat(), previewWidth.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = max(
                (textureViewHeight.toFloat() / previewHeight).toDouble(),
                (textureViewWidth.toFloat() / previewWidth).toDouble()
            ).toFloat()
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (displayRotation - 2)).toFloat(), centerX, centerY)
        } else if (displayRotation == Surface.ROTATION_180) {
            matrix.postRotate(180f, centerX, centerY)
        }

        return matrix
    }
}
