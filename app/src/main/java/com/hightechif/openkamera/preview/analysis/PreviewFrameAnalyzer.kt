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
import android.util.Log
import com.hightechif.openkamera.utils.MyDebug
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modern Kotlin Coroutines frame analyzer replacing legacy AsyncTask.
 * Runs non-blocking frame analytics on `Dispatchers.Default` with `Channel.CONFLATED` backpressure dropping.
 */
class PreviewFrameAnalyzer(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
) {
    companion object {
        private const val TAG = "PreviewFrameAnalyzer"
    }

    private val _analysisResultFlow = MutableStateFlow<FrameAnalysisResult?>(null)
    val analysisResultFlow: StateFlow<FrameAnalysisResult?> = _analysisResultFlow.asStateFlow()

    private data class AnalysisFrameTask(
        val previewBitmap: Bitmap,
        val config: FrameAnalysisConfig,
        val zebraStripesBuffer: Bitmap?,
        val focusPeakingBuffer: Bitmap?,
        val focusPeakingBufferTemp: Bitmap?
    )

    private val frameChannel = Channel<AnalysisFrameTask>(Channel.CONFLATED)
    private var processingJob: Job? = null

    init {
        startWorker()
    }

    private fun startWorker() {
        processingJob = scope.launch {
            for (task in frameChannel) {
                val result = processFrameInternal(task)
                if (result != null) {
                    _analysisResultFlow.value = result
                }
            }
        }
    }

    /**
     * Posts a new frame for analysis. If the analyzer is currently busy, older unprocessed frames are dropped.
     */
    fun postFrame(
        previewBitmap: Bitmap,
        config: FrameAnalysisConfig,
        zebraStripesBuffer: Bitmap? = null,
        focusPeakingBuffer: Bitmap? = null,
        focusPeakingBufferTemp: Bitmap? = null
    ): Boolean {
        return frameChannel.trySend(
            AnalysisFrameTask(
                previewBitmap = previewBitmap,
                config = config,
                zebraStripesBuffer = zebraStripesBuffer,
                focusPeakingBuffer = focusPeakingBuffer,
                focusPeakingBufferTemp = focusPeakingBufferTemp
            )
        ).isSuccess
    }

    /**
     * Directly processes a frame in a suspending function, returning the analysis result.
     */
    suspend fun analyzeFrameDirect(
        previewBitmap: Bitmap,
        config: FrameAnalysisConfig,
        zebraStripesBuffer: Bitmap? = null,
        focusPeakingBuffer: Bitmap? = null,
        focusPeakingBufferTemp: Bitmap? = null
    ): FrameAnalysisResult? = withContext(dispatcher) {
        processFrameInternal(
            AnalysisFrameTask(
                previewBitmap = previewBitmap,
                config = config,
                zebraStripesBuffer = zebraStripesBuffer,
                focusPeakingBuffer = focusPeakingBuffer,
                focusPeakingBufferTemp = focusPeakingBufferTemp
            )
        )
    }

    private fun processFrameInternal(task: AnalysisFrameTask): FrameAnalysisResult? {
        val bitmap = task.previewBitmap
        if (bitmap.isRecycled) return null

        try {
            var histogram: IntArray? = null
            if (task.config.wantHistogram) {
                histogram = HistogramProcessor.computeHistogram(
                    bitmap = bitmap,
                    type = task.config.histogramType
                )
            }

            var zebraBitmap: Bitmap? = null
            if (task.config.wantZebraStripes && task.zebraStripesBuffer != null && !task.zebraStripesBuffer.isRecycled) {
                zebraBitmap = ZebraStripesProcessor.generateZebraStripes(
                    previewBitmap = bitmap,
                    outputBuffer = task.zebraStripesBuffer,
                    threshold = task.config.zebraStripesThreshold,
                    colorForeground = task.config.zebraStripesColorForeground,
                    colorBackground = task.config.zebraStripesColorBackground,
                    rotationDegrees = task.config.rotationDegrees
                )
            }

            var focusPeakingBitmap: Bitmap? = null
            if (task.config.wantFocusPeaking && task.focusPeakingBuffer != null && task.focusPeakingBufferTemp != null
                && !task.focusPeakingBuffer.isRecycled && !task.focusPeakingBufferTemp.isRecycled
            ) {
                focusPeakingBitmap = FocusPeakingProcessor.generateFocusPeaking(
                    previewBitmap = bitmap,
                    outputBuffer = task.focusPeakingBuffer,
                    tempBuffer = task.focusPeakingBufferTemp,
                    rotationDegrees = task.config.rotationDegrees
                )
            }

            return FrameAnalysisResult(
                histogram = histogram,
                zebraStripesBitmap = zebraBitmap,
                focusPeakingBitmap = focusPeakingBitmap,
                previewBitmapFullCopy = null,
                timestampMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            if (MyDebug.LOG) Log.e(TAG, "error during frame analysis: ${e.message}")
            return null
        }
    }

    /**
     * Releases resources and cancels background worker.
     */
    fun destroy() {
        processingJob?.cancel()
        frameChannel.close()
    }
}
