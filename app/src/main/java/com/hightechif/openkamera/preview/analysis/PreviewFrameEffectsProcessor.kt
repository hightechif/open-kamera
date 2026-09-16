/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.analysis

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow
import androidx.core.graphics.createBitmap

/**
 * Manages frame analysis bitmap buffers (small downsized preview bitmap, zebra stripes buffer,
 * focus peaking buffer), coordinates background frame analysis, and manages histogram / visual effect state.
 */
class PreviewFrameEffectsProcessor(
    private val context: Context,
    private val frameAnalyzer: PreviewFrameAnalyzer = PreviewFrameAnalyzer(context)
) {

    companion object {
        private const val TAG = "PreviewEffectsProcessor"
    }

    var isPreviewBitmapEnabled: Boolean = false
    var usePreviewBitmapSmall: Boolean = false
    var usePreviewBitmapFull: Boolean = false

    var previewBitmap: Bitmap? = null
        private set
    var previewBitmapFullW: Int = -1
        private set
    var previewBitmapFullH: Int = -1
        private set
    var lastPreviewBitmapTimeMs: Long = 0

    var wantHistogram: Boolean = false
    var histogramType: HistogramType = HistogramType.HISTOGRAM_TYPE_VALUE
    var histogram: IntArray? = null
        private set
    var lastHistogramTimeMs: Long = 0

    var wantZebraStripes: Boolean = false
    var zebraStripesThreshold: Int = 0
    var zebraStripesColorForeground: Int = 0
    var zebraStripesColorBackground: Int = 0
    private var zebraStripesBitmapBuffer: Bitmap? = null
    var zebraStripesBitmap: Bitmap? = null
        private set

    var wantFocusPeaking: Boolean = false
    private var focusPeakingBitmapBuffer: Bitmap? = null
    private var focusPeakingBitmapBufferTemp: Bitmap? = null
    var focusPeakingBitmap: Bitmap? = null
        private set

    var wantPreShots: Boolean = false
    private val preShotsRingBuffer = PreShotsRingBuffer()

    val analysisResultFlow: StateFlow<FrameAnalysisResult?> = frameAnalyzer.analysisResultFlow

    /**
     * Allocates or resizes bitmap buffers when viewport dimensions or effect settings change.
     */
    fun setupBuffers(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        if (usePreviewBitmapSmall) {
            val smallW = width / 4
            val smallH = height / 4
            if (previewBitmap == null || previewBitmap!!.width != smallW || previewBitmap!!.height != smallH) {
                previewBitmap?.recycle()
                previewBitmap = createBitmap(smallW, smallH)
            }

            if (wantZebraStripes) {
                if (zebraStripesBitmapBuffer == null || zebraStripesBitmapBuffer!!.width != smallW || zebraStripesBitmapBuffer!!.height != smallH) {
                    zebraStripesBitmapBuffer?.recycle()
                    zebraStripesBitmapBuffer = createBitmap(smallW, smallH)
                }
            }

            if (wantFocusPeaking) {
                if (focusPeakingBitmapBuffer == null || focusPeakingBitmapBuffer!!.width != smallW || focusPeakingBitmapBuffer!!.height != smallH) {
                    focusPeakingBitmapBuffer?.recycle()
                    focusPeakingBitmapBuffer = createBitmap(smallW, smallH)
                }
                if (focusPeakingBitmapBufferTemp == null || focusPeakingBitmapBufferTemp!!.width != smallW || focusPeakingBitmapBufferTemp!!.height != smallH) {
                    focusPeakingBitmapBufferTemp?.recycle()
                    focusPeakingBitmapBufferTemp = createBitmap(smallW, smallH)
                }
            }
        }
    }

    /**
     * Dispatches a new frame for analysis to the coroutine worker.
     */
    fun onNewFrame(rotationDegrees: Int) {
        val bitmap = previewBitmap ?: return
        if (bitmap.isRecycled) return

        val config = FrameAnalysisConfig(
            wantHistogram = wantHistogram,
            histogramType = histogramType,
            wantZebraStripes = wantZebraStripes,
            zebraStripesThreshold = zebraStripesThreshold,
            zebraStripesColorForeground = zebraStripesColorForeground,
            zebraStripesColorBackground = zebraStripesColorBackground,
            wantFocusPeaking = wantFocusPeaking,
            wantPreShots = wantPreShots,
            rotationDegrees = rotationDegrees
        )

        frameAnalyzer.postFrame(
            previewBitmap = bitmap,
            config = config,
            zebraStripesBuffer = zebraStripesBitmapBuffer,
            focusPeakingBuffer = focusPeakingBitmapBuffer,
            focusPeakingBufferTemp = focusPeakingBitmapBufferTemp
        )
    }

    /**
     * Updates effect bitmaps from the latest completed analysis result.
     */
    fun updateResults(result: FrameAnalysisResult) {
        if (result.histogram != null) {
            histogram = result.histogram
            lastHistogramTimeMs = result.timestampMs
        }
        if (result.zebraStripesBitmap != null) {
            zebraStripesBitmap = result.zebraStripesBitmap
        }
        if (result.focusPeakingBitmap != null) {
            focusPeakingBitmap = result.focusPeakingBitmap
        }
    }

    fun releaseBuffers() {
        previewBitmap?.recycle()
        previewBitmap = null
        zebraStripesBitmapBuffer?.recycle()
        zebraStripesBitmapBuffer = null
        focusPeakingBitmapBuffer?.recycle()
        focusPeakingBitmapBuffer = null
        focusPeakingBitmapBufferTemp?.recycle()
        focusPeakingBitmapBufferTemp = null
        preShotsRingBuffer.flush()
        frameAnalyzer.destroy()
    }
}
