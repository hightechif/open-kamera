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
import android.graphics.Path
import android.graphics.Rect
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.Preview
import kotlin.math.max

/**
 * Renders the real-time live preview histogram overlay (Luminance or RGB channels).
 */
class HistogramOverlayRenderer : OverlayRenderer {

    private val p = Paint()
    private val iconDest = Rect()
    private val path = Path()
    private val tempHistogramChannel = IntArray(256)

    override fun updateSettings() {
        // No cached setting state required for histogram
    }

    override fun draw(canvas: Canvas, context: DrawPreviewContext, timeMs: Long) {
        val preview: Preview = context.preview
        if (preview.cameraController == null || !preview.isPreviewBitmapEnabled) return

        val sharedPreferences = context.sharedPreferences
        val histogramPref = sharedPreferences.getString(PreferenceKeys.HISTOGRAM_PREFERENCE_KEY, "preference_histogram_off")
        if (histogramPref == null || histogramPref == "preference_histogram_off") return

        val histogram: IntArray = preview.histogram ?: return
        if (histogram.isEmpty()) return

        val scaleDp = context.scaleDp
        val histogramWidth = (RendererUtils.HISTOGRAM_WIDTH_DP * scaleDp + 0.5f).toInt()
        val histogramHeight = (RendererUtils.HISTOGRAM_HEIGHT_DP * scaleDp + 0.5f).toInt()

        val flashPadding = (1 * context.scaleFont + 0.5f).toInt()
        val topX = (context.dpToPx(16f)).toInt()
        val topY = (context.dpToPx(16f)).toInt()

        var locationX = topX - flashPadding
        var locationY = topY

        val uiRotation = preview.uIRotation
        if (uiRotation == 90 || uiRotation == 270) {
            val diff = canvas.width - canvas.height
            locationX += diff / 2
            locationY -= diff / 2
        }

        if (context.deviceUiRotation == 90) {
            locationY = canvas.height - locationY - (20 * context.scaleFont + 0.5f).toInt()
        }

        if (context.deviceUiRotation == 180) {
            locationX = canvas.width - locationX - histogramWidth + flashPadding
        }

        iconDest[locationX, locationY, locationX + histogramWidth] = locationY + histogramHeight
        if (context.deviceUiRotation == 90) {
            iconDest.top -= histogramHeight
            iconDest.bottom -= histogramHeight
        }

        // Draw dark background box
        p.style = Paint.Style.FILL
        p.color = Color.argb(64, 0, 0, 0)
        canvas.drawRect(iconDest, p)

        var maxVal = 0
        for (value in histogram) {
            maxVal = max(maxVal, value)
        }
        if (maxVal <= 0) return

        if (histogram.size == 256 * 3) {
            // RGB 3 channels
            var c = 0
            val a0 = 151
            val a1 = 110
            val a2 = 94

            // Red channel
            for (i in 0..255) tempHistogramChannel[i] = histogram[c++]
            p.color = Color.argb(a0, 255, 0, 0)
            drawChannel(canvas, tempHistogramChannel, maxVal)

            // Green channel
            for (i in 0..255) tempHistogramChannel[i] = histogram[c++]
            p.color = Color.argb(a1, 0, 255, 0)
            drawChannel(canvas, tempHistogramChannel, maxVal)

            // Blue channel
            for (i in 0..255) tempHistogramChannel[i] = histogram[c++]
            p.color = Color.argb(a2, 0, 0, 255)
            drawChannel(canvas, tempHistogramChannel, maxVal)
        } else {
            // Single channel (Luminance or single color)
            p.color = Color.argb(192, 255, 255, 255)
            drawChannel(canvas, histogram, maxVal)
        }
    }

    private fun drawChannel(canvas: Canvas, channel: IntArray, maxVal: Int) {
        if (maxVal <= 0) return
        path.reset()
        path.moveTo(iconDest.left.toFloat(), iconDest.bottom.toFloat())
        val width = iconDest.width()
        val height = iconDest.height()
        val channelSize = channel.size.toDouble()

        for (c in channel.indices) {
            val cAlpha = c / channelSize
            val x = (cAlpha * width).toInt()
            val h = (channel[c] * height) / maxVal
            path.lineTo((iconDest.left + x).toFloat(), (iconDest.bottom - h).toFloat())
        }
        path.lineTo(iconDest.right.toFloat(), iconDest.bottom.toFloat())
        path.close()
        canvas.drawPath(path, p)
    }

    override fun onDestroy() {
        path.reset()
    }
}
