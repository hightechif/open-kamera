/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.processing

import android.graphics.Bitmap
import android.util.Log

/**
 * High-performance JNI Bridge to the OpenKamera native C++17 image processing engine.
 * Provides zero-copy Bitmap access with automatic fallback to Kotlin/Java routines if native
 * binaries cannot be loaded or on unsupported architectures.
 */
object NativeImageProcessorBridge {

    private const val TAG = "NativeImageProcessor"
    private const val LIBRARY_NAME = "openkamera_native"

    val isLoaded: Boolean

    init {
        var loaded = false
        try {
            System.loadLibrary(LIBRARY_NAME)
            loaded = true
            Log.i(TAG, "Successfully loaded native library '$LIBRARY_NAME'")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library '$LIBRARY_NAME', falling back to pure Kotlin", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception loading '$LIBRARY_NAME'", e)
        }
        isLoaded = loaded
    }

    /**
     * Checks if native routines are available and working.
     */
    fun isAvailable(): Boolean {
        return isLoaded && isNativeSupported()
    }

    /**
     * Computes a 256-bin histogram for a single mode (Luminance, Value, Intensity, Lightness).
     */
    fun computeHistogram(bitmap: Bitmap, mode: Int): IntArray? {
        if (!isAvailable()) return null
        return nativeComputeHistogram(bitmap, mode)
    }

    /**
     * Computes a 768-bin interleaved/concatenated RGB histogram (256 R, 256 G, 256 B).
     */
    fun computeHistogramRgb(bitmap: Bitmap): IntArray? {
        if (!isAvailable()) return null
        return nativeComputeHistogramRgb(bitmap)
    }

    /**
     * Applies focus peaking high-pass filter and median filtering directly into outBitmap.
     */
    fun computeFocusPeaking(srcBitmap: Bitmap, tempBitmap: Bitmap?, outBitmap: Bitmap): Boolean {
        if (!isAvailable()) return false
        return nativeComputeFocusPeaking(srcBitmap, tempBitmap, outBitmap)
    }

    /**
     * Generates diagonal zebra stripes overlay over pixels exceeding threshold.
     */
    fun computeZebraStripes(
        srcBitmap: Bitmap,
        outBitmap: Bitmap,
        threshold: Int,
        colorFg: Int,
        colorBg: Int,
        stripeWidth: Int
    ): Boolean {
        if (!isAvailable()) return false
        return nativeComputeZebraStripes(srcBitmap, outBitmap, threshold, colorFg, colorBg, stripeWidth)
    }

    /**
     * Computes the median luminance value within a cropped region of a Bitmap.
     */
    fun computeMedianValue(bitmap: Bitmap, startX: Int, startY: Int, cropW: Int, cropH: Int): Int {
        if (!isAvailable()) return 128
        return nativeComputeMedianValue(bitmap, startX, startY, cropW, cropH)
    }

    /**
     * Creates a Median Threshold Bitmap (1 byte per pixel: 0, 127 for noise mask, 255).
     */
    fun createMtb(bitmap: Bitmap, medianVal: Int, startX: Int, startY: Int, cropW: Int, cropH: Int): ByteArray? {
        if (!isAvailable()) return null
        return nativeCreateMtb(bitmap, medianVal, startX, startY, cropW, cropH)
    }

    /**
     * Computes error metric sums for 9 candidate shifts between mtb0 and mtb1.
     */
    fun computeMtbErrors(
        mtb0: ByteArray,
        mtb1: ByteArray,
        width: Int,
        height: Int,
        offX: Int,
        offY: Int,
        stepSize: Int
    ): IntArray? {
        if (!isAvailable()) return null
        return nativeComputeMtbErrors(mtb0, mtb1, width, height, offX, offY, stepSize)
    }

    /**
     * Blends a new burst frame into baseBitmap with temporal Wiener outlier rejection.
     */
    fun accumulateFrameAvg(
        baseBitmap: Bitmap,
        newBitmap: Bitmap,
        offsetX: Int,
        offsetY: Int,
        avgFactor: Float,
        wienerC: Float
    ): Boolean {
        if (!isAvailable()) return false
        return nativeAccumulateFrameAvg(baseBitmap, newBitmap, offsetX, offsetY, avgFactor, wienerC)
    }

    /**
     * Applies piecewise linear and gamma brightening to a bitmap in place.
     */
    fun applyBrighten(
        bitmap: Bitmap,
        gain: Float,
        gamma: Float,
        lowX: Float,
        midX: Float,
        maxX: Float
    ): Boolean {
        if (!isAvailable()) return false
        return nativeApplyBrighten(bitmap, gain, gamma, lowX, midX, maxX)
    }

    /**
     * Executes multi-frame HDR exposure fusion and tone-mapping directly into outBitmap.
     */
    fun processHdrFusion(
        frameBitmaps: Array<Bitmap>,
        offsetsX: IntArray?,
        offsetsY: IntArray?,
        paramsA: FloatArray?,
        paramsB: FloatArray?,
        outBitmap: Bitmap,
        tonemapAlgorithm: Int,
        tonemapScale: Float,
        linearScale: Float
    ): Boolean {
        if (!isAvailable()) return false
        return nativeProcessHdrFusion(
            frameBitmaps,
            offsetsX,
            offsetsY,
            paramsA,
            paramsB,
            outBitmap,
            tonemapAlgorithm,
            tonemapScale,
            linearScale
        )
    }

    /**
     * Applies global adaptive histogram equalization to bitmap in place.
     */
    fun applyHistogramEqualization(bitmap: Bitmap): Boolean {
        if (!isAvailable()) return false
        return nativeApplyHistogramEqualization(bitmap)
    }

    /**
     * Detects corner feature points in a bitmap using Harris Corner Detection.
     * Returns flat IntArray [x0, y0, x1, y1, ...]
     */
    fun detectHarrisFeatures(bitmap: Bitmap, cornerThreshold: Float): IntArray? {
        if (!isAvailable()) return null
        return nativeDetectHarrisFeatures(bitmap, cornerThreshold)
    }

    /**
     * Blends lhsBitmap and rhsBitmap across a vertical seam defined by bestPathMidX.
     */
    fun blendPyramidSeam(
        lhsBitmap: Bitmap,
        rhsBitmap: Bitmap,
        outBitmap: Bitmap,
        bestPathMidX: IntArray?,
        blendWidth: Int
    ): Boolean {
        if (!isAvailable()) return false
        return nativeBlendPyramidSeam(lhsBitmap, rhsBitmap, outBitmap, bestPathMidX, blendWidth)
    }

    /**
     * Computes sum of squared differences error between two overlapping frames.
     */
    fun computeFrameOverlapError(frame0: Bitmap, frame1: Bitmap): Long {
        if (!isAvailable()) return 0L
        return nativeComputeFrameOverlapError(frame0, frame1)
    }

    // Native detection methods
    external fun isNativeSupported(): Boolean
    external fun hasNeon(): Boolean

    // Native preview methods
    private external fun nativeComputeHistogram(bitmap: Bitmap, mode: Int): IntArray?
    private external fun nativeComputeHistogramRgb(bitmap: Bitmap): IntArray?
    private external fun nativeComputeFocusPeaking(srcBitmap: Bitmap, tempBitmap: Bitmap?, outBitmap: Bitmap): Boolean
    private external fun nativeComputeZebraStripes(
        srcBitmap: Bitmap,
        outBitmap: Bitmap,
        threshold: Int,
        colorFg: Int,
        colorBg: Int,
        stripeWidth: Int
    ): Boolean

    // Native MTB methods
    private external fun nativeComputeMedianValue(bitmap: Bitmap, startX: Int, startY: Int, cropW: Int, cropH: Int): Int
    private external fun nativeCreateMtb(bitmap: Bitmap, medianVal: Int, startX: Int, startY: Int, cropW: Int, cropH: Int): ByteArray?
    private external fun nativeComputeMtbErrors(
        mtb0: ByteArray,
        mtb1: ByteArray,
        width: Int,
        height: Int,
        offX: Int,
        offY: Int,
        stepSize: Int
    ): IntArray?
    private external fun nativeAccumulateFrameAvg(
        baseBitmap: Bitmap,
        newBitmap: Bitmap,
        offsetX: Int,
        offsetY: Int,
        avgFactor: Float,
        wienerC: Float
    ): Boolean
    private external fun nativeApplyBrighten(
        bitmap: Bitmap,
        gain: Float,
        gamma: Float,
        lowX: Float,
        midX: Float,
        maxX: Float
    ): Boolean

    // Native HDR methods
    private external fun nativeProcessHdrFusion(
        frameBitmaps: Array<Bitmap>,
        offsetsX: IntArray?,
        offsetsY: IntArray?,
        paramsA: FloatArray?,
        paramsB: FloatArray?,
        outBitmap: Bitmap,
        tonemapAlgorithm: Int,
        tonemapScale: Float,
        linearScale: Float
    ): Boolean
    private external fun nativeApplyHistogramEqualization(bitmap: Bitmap): Boolean

    // Native Panorama methods
    private external fun nativeDetectHarrisFeatures(bitmap: Bitmap, cornerThreshold: Float): IntArray?
    private external fun nativeBlendPyramidSeam(
        lhsBitmap: Bitmap,
        rhsBitmap: Bitmap,
        outBitmap: Bitmap,
        bestPathMidX: IntArray?,
        blendWidth: Int
    ): Boolean
    private external fun nativeComputeFrameOverlapError(frame0: Bitmap, frame1: Bitmap): Long
}
