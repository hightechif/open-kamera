/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RSInvalidStateException
import android.renderscript.RenderScript
import android.renderscript.Script.LaunchOptions
import android.renderscript.ScriptIntrinsicHistogram
import android.renderscript.Type
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.hightechif.openkamera.ScriptC_align_mtb
import com.hightechif.openkamera.ScriptC_avg_brighten
import com.hightechif.openkamera.ScriptC_calculate_sharpness
import com.hightechif.openkamera.ScriptC_create_mtb
import com.hightechif.openkamera.ScriptC_histogram_adjust
import com.hightechif.openkamera.ScriptC_histogram_compute
import com.hightechif.openkamera.ScriptC_process_avg
import com.hightechif.openkamera.ScriptC_process_hdr
import com.hightechif.openkamera.audio.*
import com.hightechif.openkamera.preferences.*
import com.hightechif.openkamera.processing.*
import com.hightechif.openkamera.sensors.*
import com.hightechif.openkamera.storage.*
import com.hightechif.openkamera.system.*
import com.hightechif.openkamera.utils.*
import java.util.Collections
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt


//import android.media.MediaScannerConnection;
//import android.os.Environment;
//import android.renderscript.ScriptIntrinsicResize;

class HDRProcessor(private val context: Context, private val isTest: Boolean) {
    //public final static boolean useRenderscript = true;
    private var rs: RenderScript? =
        null // lazily created, so we don't take up resources if application isn't using HDR

    // we lazily create and cache scripts that would otherwise have to be repeatedly created in a single
    // HDR or NR photo
    // these should be set to null in freeScript(), to help garbage collection
    /*private ScriptC_process_hdr processHDRScript;*/
    private var processAvgScript: ScriptC_process_avg? = null
    private var createMTBScript: ScriptC_create_mtb? = null
    private var alignMTBScript: ScriptC_align_mtb? = null

    /*private ScriptC_histogram_adjust histogramAdjustScript;
    private ScriptC_histogram_compute histogramScript;
    private ScriptC_avg_brighten avgBrightenScript;
    private ScriptC_calculate_sharpness sharpnessScript;*/
    // public for access by testing
    var offsetsX: IntArray = IntArray(0)
    var offsetsY: IntArray = IntArray(0)
    var sharpIndex: Int = 0

    private enum class HDRAlgorithm {
        HDRALGORITHM_STANDARD,
        HDRALGORITHM_SINGLE_IMAGE
    }

    enum class TonemappingAlgorithm {
        TONEMAPALGORITHM_CLAMP,
        TONEMAPALGORITHM_EXPONENTIAL,
        TONEMAPALGORITHM_REINHARD,
        TONEMAPALGORITHM_FU2,
        TONEMAPALGORITHM_ACES
    }

    enum class DROTonemappingAlgorithm {
        DROALGORITHMNONE,
        DROALGORITHMGAINGAMMA
    }

    private fun freeScripts() {
        if (MyDebug.LOG) Log.d(TAG, "freeScripts")
        /*processHDRScript = null;*/
        processAvgScript = null
        createMTBScript = null
        alignMTBScript = null
        /*histogramAdjustScript = null;
        histogramScript = null;
        avgBrightenScript = null;
        sharpnessScript = null;*/
    }

    fun onDestroy() {
        if (MyDebug.LOG) Log.d(TAG, "on_destroy")

        freeScripts() // just in case

        if (rs != null) {
            // need to destroy context, otherwise this isn't necessarily garbage collected - we had tests failing with out of memory
            // problems e.g. when running MainTests as a full set with Camera2 API. Although we now reduce the problem by creating
            // the rs lazily, it's still good to explicitly clear.
            try {
                rs!!.destroy() // on Android M onwards this is a NOP - instead we call RenderScript.releaseAllContexts(); in MainActivity.onDestroy()
            } catch (e: RSInvalidStateException) {
                e.printStackTrace()
            }
            rs = null
        }
    }

    /** Given a set of data Xi and Yi, this function estimates a relation between X and Y
     * using linear least squares.
     * We use it to modify the pixels of images taken at the brighter or darker exposure
     * levels, to estimate what the pixel should be at the "base" exposure.
     * We estimate as y = parameter_A * x + parameter_B.
     */
    private class ResponseFunction {
        var parameterA: Float = 0f
        var parameterB: Float = 0f

        private constructor(parameterA: Float, parameterB: Float) {
            this.parameterA = parameterA
            this.parameterB = parameterB
        }

        /** Computes the response function.
         * We pass the context, so this inner class can be made static.
         * @param xSamples List of Xi samples. Must be at least 3 samples.
         * @param ySamples List of Yi samples. Must be same length as xSamples.
         * @param weights List of weights. Must be same length as xSamples.
         */
        constructor(
            context: Context?,
            id: Int,
            xSamples: List<Double>,
            ySamples: List<Double>,
            weights: List<Double>
        ) {
            if (MyDebug.LOG) Log.d(TAG, "ResponseFunction")

            if (xSamples.size != ySamples.size) {
                if (MyDebug.LOG) Log.e(TAG, "unequal number of samples")
                // throw RuntimeException, as this is a programming error
                throw RuntimeException()
            } else if (xSamples.size != weights.size) {
                if (MyDebug.LOG) Log.e(TAG, "unequal number of samples")
                // throw RuntimeException, as this is a programming error
                throw RuntimeException()
            } else if (xSamples.size <= 3) {
                if (MyDebug.LOG) Log.e(TAG, "not enough samples")
                // throw RuntimeException, as this is a programming error
                throw RuntimeException()
            }

            // linear Y = AX + B
            var done = false
            var sumWx = 0.0
            var sumWx2 = 0.0
            var sumWxy = 0.0
            var sumWy = 0.0
            var sumW = 0.0
            for (i in xSamples.indices) {
                val x = xSamples[i]
                val y = ySamples[i]
                val w = weights[i]
                sumWx += w * x
                sumWx2 += w * x * x
                sumWxy += w * x * y
                sumWy += w * y
                sumW += w
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "sum_wx = $sumWx")
                Log.d(TAG, "sum_wx2 = $sumWx2")
                Log.d(TAG, "sum_wxy = $sumWxy")
                Log.d(TAG, "sum_wy = $sumWy")
                Log.d(TAG, "sum_w = $sumW")
            }
            // need to solve:
            // A . sumWx + B . sumW - sumWy = 0
            // A . sumWx2 + B . sumWx - sumWxy = 0
            // =>
            // A . sumWx^2 + B . sumW . sumWx - sumWy . sumWx = 0
            // A . sumW . sumWx2 + B . sumW . sumWx - sumW . sumWxy = 0
            // A ( sumWx^2 - sumW . sumWx2 ) = sumWy . sumWx - sumW . sumWxy
            // then plug A into:
            // B . sumW = sumWy - A . sumWx
            val aNumer = sumWy * sumWx - sumW * sumWxy
            val aDenom = sumWx * sumWx - sumW * sumWx2
            if (MyDebug.LOG) {
                Log.d(TAG, "aNumer = $aNumer")
                Log.d(TAG, "aDenom = $aDenom")
            }
            if (abs(aDenom) < 1.0e-5) {
                if (MyDebug.LOG) Log.e(TAG, "denom too small")
                // will fall back to linear Y = AX
            } else {
                parameterA = (aNumer / aDenom).toFloat()
                parameterB = ((sumWy - parameterA * sumWx) / sumW).toFloat()
                if (MyDebug.LOG) {
                    Log.d(TAG, "parameter_A = $parameterA")
                    Log.d(TAG, "parameter_B = $parameterB")
                }
                // we don't want a function that is not monotonic, or can be negative!
                if (parameterA < 1.0e-5) {
                    if (MyDebug.LOG) Log.e(
                        TAG,
                        "parameter A too small or negative: $parameterA"
                    )
                } else if (parameterB < 1.0e-5) {
                    if (MyDebug.LOG) Log.e(
                        TAG,
                        "parameter B too small or negative: $parameterB"
                    )
                } else {
                    done = true
                }
            }

            if (!done) {
                if (MyDebug.LOG) Log.e(TAG, "falling back to linear Y = AX")
                // linear Y = AX
                var numer = 0.0
                var denom = 0.0
                for (i in xSamples.indices) {
                    val x = xSamples[i]
                    val y = ySamples[i]
                    val w = weights[i]
                    numer += w * x * y
                    denom += w * x * x
                }
                if (MyDebug.LOG) {
                    Log.d(TAG, "numer = $numer")
                    Log.d(TAG, "denom = $denom")
                }

                if (denom < 1.0e-5) {
                    if (MyDebug.LOG) Log.e(TAG, "denom too small")
                    parameterA = 1.0f
                } else {
                    parameterA = (numer / denom).toFloat()
                    // we don't want a function that is not monotonic!
                    if (parameterA < 1.0e-5) {
                        if (MyDebug.LOG) Log.e(
                            TAG,
                            "parameter A too small or negative: $parameterA"
                        )
                        parameterA = 1.0e-5f
                    }
                }
                parameterB = 0.0f
            }

            if (MyDebug.LOG) {
                Log.d(TAG, "parameter_A = $parameterA")
                Log.d(TAG, "parameter_B = $parameterB")
            }

            /*if( MyDebug.LOG ) {
                // log samples to a CSV file
                File file = new File(context.getExternalFilesDir(null).getPath() + "/com.hightechif.openkamera.hdr_samples_" + id + ".csv");
                if( file.exists() ) {
                    if( !file.delete() ) {
                        // keep FindBugs happy by checking return argument
                        Log.e(TAG, "failed to delete csv file");
                    }
                }
                FileWriter writer = null;
                try {
                    writer = new FileWriter(file);
                    //writer.append("Parameter," + parameter + "\n");
                    writer.append("Parameters,").append(String.valueOf(parameter_A)).append(",").append(String.valueOf(parameter_B)).append("\n");
                    writer.append("X,Y,Weight\n");
                    for(int i=0;i<x_samples.size();i++) {
                        //Log.d(TAG, "log: " + i + " / " + x_samples.size());
                        double x = x_samples.get(i);
                        double y = y_samples.get(i);
                        double w = weights.get(i);
                        writer.append(String.valueOf(x)).append(",").append(String.valueOf(y)).append(",").append(String.valueOf(w)).append("\n");
                    }
                }
                catch (IOException e) {
                    Log.e(TAG, "failed to open csv file");
                    e.printStackTrace();
                }
                finally {
                    try {
                        if( writer != null )
                            writer.close();
                    }
                    catch (IOException e) {
                        Log.e(TAG, "failed to close csv file");
                        e.printStackTrace();
                    }
                }
                MediaScannerConnection.scanFile(context, new String[] { file.getAbsolutePath() }, null, null);
            }*/
        }

        companion object {
            fun createIdentity(): ResponseFunction {
                return ResponseFunction(1.0f, 0.0f)
            }
        }
    }

    interface SortCallback {
        /** This is called when the sort order for the input bitmaps is known, from darkest to brightest.
         * @param sortOrder A list of length equal to the supplied bitmaps.size(). sort_order.get(i)
         * returns the index in the bitmaps array of the i-th image after sorting,
         * where i==0 represents the darkest image, and i==bitmaps.size()-1 is the
         * brightest.
         */
        fun sortOrder(sortOrder: List<Int>?)
    }

    /** Converts a list of bitmaps into a HDR image, which is then tonemapped to a final RGB image.
     * @param bitmaps The list of bitmaps, which should be in order of increasing brightness (exposure).
     * Currently only supports a list of either 1 image, or 3 images (the 2nd should be
     * at the desired exposure level for the resultant image).
     * The bitmaps must all be the same resolution.
     * @param releaseBitmaps If true, the resultant image will be stored in one of the input bitmaps.
     * The bitmaps array will be updated so that the first entry will contain
     * the output bitmap. If assumeSorted is true, this will be equal to the
     * input bitmaps.get( (bitmaps.size()-1) / 2). The remainder bitmaps will have
     * recycle() called on them.
     * If false, the resultant image is copied to outputBitmap.
     * @param outputBitmap If releaseBitmaps is false, the resultant image is stored in this bitmap.
     * If releaseBitmaps is true, this parameter is ignored.
     * @param assumeSorted If true, the input bitmaps should be sorted in order from darkest to brightest
     * exposure. If false, the function will automatically resort.
     * @param sortCb       If assumeSorted is false and this is non-null, sort_cb.sortOrder() will be
     * called to indicate the sort order when this is known.
     * @param hdrAlpha     A value from 0.0f to 1.0f indicating the "strength" of the HDR effect. Specifically,
     * this controls the level of the local contrast enhancement done in adjustHistogram().
     * @param nTiles       A value of 1 or greater indicating how local the contrast enhancement algorithm should be.
     * @param cePreserveBlacks
     * If true (recommended), then we apply a modification to the contrast enhancement algorithm to avoid
     * making darker pixels too dark. A value of false gives more contrast on the darker regions of the
     * resultant image.
     * @param tonemappingAlgorithm
     * Algorithm to use for tonemapping (if multiple images are received).
     * @param droTonemappingAlgorithm
     * Algorithm to use for tonemapping (if single image is received).
     */
    @Throws(HDRProcessorException::class)
    fun processHDR(
        bitmaps: MutableList<Bitmap?>,
        releaseBitmaps: Boolean,
        outputBitmap: Bitmap?,
        assumeSorted: Boolean,
        sortCb: SortCallback?,
        hdrAlpha: Float,
        nTiles: Int,
        cePreserveBlacks: Boolean,
        tonemappingAlgorithm: TonemappingAlgorithm,
        droTonemappingAlgorithm: DROTonemappingAlgorithm
    ) {
        var mutBitmaps = bitmaps
        if (MyDebug.LOG) Log.d(TAG, "processHDR")
        if (!assumeSorted && !releaseBitmaps) {
            if (MyDebug.LOG) Log.d(TAG, "take a copy of bitmaps array")
            // if !releaseBitmaps, then we shouldn't be modifying the input bitmaps array - but if !assumeSorted, we need to sort them
            // so make sure we take a copy
            mutBitmaps = ArrayList(mutBitmaps)
        }
        val nBitmaps = mutBitmaps.size
        //if( nBitmaps != 1 && nBitmaps != 3 && nBitmaps != 5 && nBitmaps != 7 ) {
        if (nBitmaps !in 1..7) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "n_bitmaps not supported: $nBitmaps"
            )
            throw HDRProcessorException(HDRProcessorException.INVALID_N_IMAGES)
        }
        for (i in 1..<nBitmaps) {
            if (mutBitmaps[i]!!.width != mutBitmaps[0]!!.width ||
                mutBitmaps[i]!!.height != mutBitmaps[0]!!.height
            ) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "bitmaps not of same resolution")
                    for (j in 0..<nBitmaps) {
                        Log.e(
                            TAG, "bitmaps $j : " + mutBitmaps[j]!!
                                .width + " x " + mutBitmaps[j]!!.height
                        )
                    }
                }
                throw HDRProcessorException(HDRProcessorException.UNEQUAL_SIZES)
            }
        }

        val algorithm =
            if (nBitmaps == 1) HDRAlgorithm.HDRALGORITHM_SINGLE_IMAGE else HDRAlgorithm.HDRALGORITHM_STANDARD

        when (algorithm) {
            HDRAlgorithm.HDRALGORITHM_SINGLE_IMAGE -> {
                if (!assumeSorted && sortCb != null) {
                    val sortOrder: MutableList<Int> = ArrayList()
                    sortOrder.add(0)
                    sortCb.sortOrder(sortOrder)
                }
                if (!USE_RENDERSCRIPT) {
                    processSingleImage(
                        mutBitmaps.filterNotNull().toMutableList(),
                        releaseBitmaps,
                        outputBitmap,
                        hdrAlpha,
                        nTiles,
                        cePreserveBlacks,
                        droTonemappingAlgorithm
                    )
                } else {
                    processSingleImageRS(
                        mutBitmaps,
                        releaseBitmaps,
                        outputBitmap,
                        hdrAlpha,
                        nTiles,
                        cePreserveBlacks,
                        droTonemappingAlgorithm
                    )
                }
            }

            HDRAlgorithm.HDRALGORITHM_STANDARD -> processHDRCore(
                mutBitmaps,
                releaseBitmaps,
                outputBitmap,
                assumeSorted,
                sortCb,
                hdrAlpha,
                nTiles,
                cePreserveBlacks,
                tonemappingAlgorithm
            )

            else -> {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "unknown algorithm $algorithm"
                )
                // throw RuntimeException, as this is a programming error
                throw RuntimeException()
            }
        }
    }

    /** Creates a ResponseFunction to estimate how pixels from the inBitmap should be adjusted to
     * match the exposure level of outBitmap.
     * The supplied offsets offsetX, offsetY give the offset for inBitmap as computed by
     * autoAlignment().
     */
    private fun createFunctionFromBitmaps(
        id: Int,
        inBitmap: Bitmap,
        outBitmap: Bitmap,
        offsetX: Int,
        offsetY: Int
    ): ResponseFunction {
        if (MyDebug.LOG) Log.d(TAG, "createFunctionFromBitmaps")
        val xSamples: MutableList<Double> = ArrayList()
        val ySamples: MutableList<Double> = ArrayList()
        val weights: MutableList<Double> = ArrayList()

        val nSamplesC = 100
        val nWSamples = sqrt(nSamplesC.toDouble()).toInt()
        val nHSamples = nSamplesC / nWSamples

        var avgIn = 0.0
        var avgOut = 0.0
        for (y in 0..<nHSamples) {
            val alpha = (y.toDouble() + 1.0) / (nHSamples.toDouble() + 1.0)
            val yCoord = (alpha * inBitmap.height).toInt()
            for (x in 0..<nWSamples) {
                val beta = (x.toDouble() + 1.0) / (nWSamples.toDouble() + 1.0)
                val xCoord = (beta * inBitmap.width).toInt()
                /*if( MyDebug.LOG )
                    Log.d(TAG, "sample response from " + xCoord + " , " + yCoord);*/
                if (xCoord + offsetX < 0 || xCoord + offsetX >= inBitmap.width || yCoord + offsetY < 0 || yCoord + offsetY >= inBitmap.height) {
                    continue
                }
                val inCol = inBitmap[xCoord + offsetX, yCoord + offsetY]
                val outCol = outBitmap[xCoord, yCoord]
                val inValue = averageRGB(inCol)
                val outValue = averageRGB(outCol)
                avgIn += inValue
                avgOut += outValue
                xSamples.add(inValue)
                ySamples.add(outValue)
            }
        }
        if (xSamples.isEmpty()) {
            Log.e(TAG, "no samples for response function!")
            // shouldn't happen, but could do with a very large offset - just make up a dummy sample
            val inValue = 255.0
            val outValue = 255.0
            avgIn += inValue
            avgOut += outValue
            xSamples.add(inValue)
            ySamples.add(outValue)
        }
        avgIn /= xSamples.size.toDouble()
        avgOut /= xSamples.size.toDouble()
        val isDarkExposure = avgIn < avgOut
        if (MyDebug.LOG) {
            Log.d(TAG, "avg_in: $avgIn")
            Log.d(TAG, "avg_out: $avgOut")
            Log.d(TAG, "is_dark_exposure: $isDarkExposure")
        }
        run {
            // calculate weights
            var minValue = xSamples[0]
            var maxValue = xSamples[0]
            for (i in 1..<xSamples.size) {
                val value = xSamples[i]
                if (value < minValue) minValue = value
                if (value > maxValue) maxValue = value
            }
            val medValue = 0.5 * (minValue + maxValue)
            if (MyDebug.LOG) {
                Log.d(TAG, "min_value: $minValue")
                Log.d(TAG, "max_value: $maxValue")
                Log.d(TAG, "med_value: $medValue")
            }
            var minValueY = ySamples[0]
            var maxValueY = ySamples[0]
            for (i in 1..<ySamples.size) {
                val value = ySamples[i]
                if (value < minValueY) minValueY = value
                if (value > maxValueY) maxValueY = value
            }
            val medValueY = 0.5 * (minValueY + maxValueY)
            if (MyDebug.LOG) {
                Log.d(TAG, "min_value_y: $minValueY")
                Log.d(TAG, "max_value_y: $maxValueY")
                Log.d(TAG, "med_value_y: $medValueY")
            }
            for (i in xSamples.indices) {
                val value = xSamples[i]
                val valueY = ySamples[i]
                var weight = if (value <= medValue) value - minValue else maxValue - value
                if (isDarkExposure) {
                    // for dark exposure, also need to worry about the y values (which will be brighter than x) being overexposed
                    val weightY =
                        if (valueY <= medValueY) valueY - minValueY else maxValueY - valueY
                    if (weightY < weight) weight = weightY
                }
                weights.add(weight)
            }
        }

        return ResponseFunction(context, id, xSamples, ySamples, weights)
    }

    /** Calculates average of RGB values for the supplied color.
     */
    private fun averageRGB(color: Int): Double {
        val r = (color and 0xFF0000) shr 16
        val g = (color and 0xFF00) shr 8
        val b = (color and 0xFF)
        return (r + g + b) / 3.0
        //return 0.27*r + 0.67*g + 0.06*b;
    }

    /** Core implementation of HDR algorithm.
     * Requires Android 4.4 (API level 19, Kitkat), due to using Renderscript without the support libraries.
     * And we now need Android 5.0 (API level 21, Lollipop) for forEach_Dot with LaunchOptions.
     * Using the support libraries (set via project.properties renderscript.support.mode) would bloat the APK
     * by around 1799KB! We don't care about pre-Android 4.4 (HDR requires CameraController2 which requires
     * Android 5.0 anyway; even if we later added support for CameraController1, we can simply say HDR requires
     * Android 5.0).
     */
    private fun processHDRCore(
        bitmaps: MutableList<Bitmap?>,
        releaseBitmaps: Boolean,
        outputBitmap: Bitmap?,
        assumeSorted: Boolean,
        sortCb: SortCallback?,
        hdrAlpha: Float,
        nTiles: Int,
        cePreserveBlacks: Boolean,
        tonemappingAlgorithm: TonemappingAlgorithm
    ) {
        var outputBitmap: Bitmap? = outputBitmap
        if (MyDebug.LOG) Log.d(TAG, "processHDRCore")

        val timeS = System.currentTimeMillis()

        val nBitmaps = bitmaps.size
        val width = bitmaps[0]!!.width
        val height = bitmaps[0]!!.height
        val responseFunctions =
            arrayOfNulls<ResponseFunction>(nBitmaps) // ResponseFunction for each image (the ResponseFunction entry can be left null to indicate the Identity)
        offsetsX = IntArray(nBitmaps)
        offsetsY = IntArray(nBitmaps)

        /*int [][] buffers = new int[nBitmaps][];
        for(int i=0;i<nBitmaps;i++) {
            buffers[i] = new int[bm.getWidth()];
        }*/
        //float [] hdr = new float[3];
        //int [] rgb = new int[3];

        //final boolean useHdrN = true; // test always using hdrN
        val useHdrN = nBitmaps != 3

        var allocations: Array<Allocation?>? = null
        if (USE_RENDERSCRIPT) {
            initRenderscript()
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after creating renderscript: " + (System.currentTimeMillis() - timeS)
            )
            // create allocations
            allocations = arrayOfNulls(nBitmaps)
            for (i in 0..<nBitmaps) {
                allocations[i] = Allocation.createFromBitmap(rs, bitmaps[i])
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after creating allocations from bitmaps: " + (System.currentTimeMillis() - timeS)
            )
        }
        //final int baseBitmap = (nBitmaps - 1) / 2; // index of the bitmap with the base exposure and offsets
        val baseBitmap =
            if (nBitmaps % 2 == 0) nBitmaps / 2 else (nBitmaps - 1) / 2 // index of the bitmap with the base exposure and offsets

        // for even number of images, round up to brighter image

        // perform auto-alignment
        // if assumeSorted is false, this function will also sort the allocations and bitmaps from darkest to brightest.
        val brightnessDetails = autoAlignment(
            offsetsX,
            offsetsY,
            allocations,
            width,
            height,
            bitmaps,
            baseBitmap,
            assumeSorted,
            sortCb,
            true,
            1,
            true,
            1,
            width,
            height,
            timeS
        )
        val medianBrightness = brightnessDetails.medianBrightness
        if (MyDebug.LOG) {
            Log.d(TAG, "### time after autoAlignment: " + (System.currentTimeMillis() - timeS))
            Log.d(TAG, "median_brightness: $medianBrightness")
        }

        // compute responseFunctions
        for (i in 0..<nBitmaps) {
            var function: ResponseFunction? = null
            if (i != baseBitmap) {
                function = createFunctionFromBitmaps(
                    i,
                    bitmaps[i]!!, bitmaps[baseBitmap]!!, offsetsX[i], offsetsY[i]
                )
            } else if (useHdrN) {
                // for hdrN, need to still create the identity response function
                function = ResponseFunction.createIdentity()
            }
            responseFunctions[i] = function
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after creating response functions: " + (System.currentTimeMillis() - timeS)
        )

        if (nBitmaps % 2 == 0) {
            // need to remap so that we aim for a brightness between the middle two images
            var a = sqrt(responseFunctions[baseBitmap - 1]!!.parameterA.toDouble()).toFloat()
            val b = responseFunctions[baseBitmap - 1]!!.parameterB / (a + 1.0f)
            if (MyDebug.LOG) {
                Log.d(TAG, "remap for even number of images")
                Log.d(TAG, "    a: $a")
                Log.d(TAG, "    b: $b")
            }
            if (a < 1.0e-5f) {
                // avoid risk of division by 0
                a = 1.0e-5f
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "    clamp a to: $a"
                )
            }
            for (i in 0..<nBitmaps) {
                val thisA = responseFunctions[i]!!.parameterA
                val thisB = responseFunctions[i]!!.parameterB
                responseFunctions[i]!!.parameterA = thisA / a
                responseFunctions[i]!!.parameterB = thisB - thisA * b / a
                if (responseFunctions[i]!!.parameterB < 1.0e-5f) {
                    if (MyDebug.LOG) Log.e(
                        TAG,
                        "remapped parameter B too small or negative: " + responseFunctions[i]!!.parameterB
                    )
                    responseFunctions[i]!!.parameterB = 1.0e-5f
                }
                if (MyDebug.LOG) {
                    Log.d(TAG, "remapped: $i")
                    Log.d(TAG, "    A: " + thisA + " -> " + responseFunctions[i]!!.parameterA)
                    Log.d(TAG, "    B: " + thisB + " -> " + responseFunctions[i]!!.parameterB)
                }
            }
        }

        /*
        // calculate average luminance by sampling
        final int nSamplesC = 100;
        final int nWSamples = (int)Math.sqrt(nSamplesC);
        final int nHSamples = nSamplesC/nWSamples;

        double sumLogLuminance = 0.0;
        int count = 0;
        for(int y=0;y<nHSamples;y++) {
            double alpha = ((double)y+1.0) / ((double)nHSamples+1.0);
            int yCoord = (int)(alpha * bm.getHeight());
            for(int i=0;i<nBitmaps;i++) {
                bitmaps.get(i).getPixels(buffers[i], 0, bm.getWidth(), 0, yCoord, bm.getWidth(), 1);
            }
            for(int x=0;x<nWSamples;x++) {
                double beta = ((double)x+1.0) / ((double)nWSamples+1.0);
                int xCoord = (int)(beta * bm.getWidth());
                if( MyDebug.LOG )
                    Log.d(TAG, "sample luminance from " + xCoord + " , " + yCoord);
                calculateHDR(hdr, nBitmaps, buffers, xCoord, responseFunctions);
                double luminance = calculateLuminance(hdr[0], hdr[1], hdr[2]) + 1.0; // add 1 so we don't take log of 0..;
                sumLogLuminance += Math.log(luminance);
                count++;
            }
        }
        float avgLuminance = (float)(Math.exp( sumLogLuminance / count ));
        if( MyDebug.LOG )
            Log.d(TAG, "avgLuminance: " + avgLuminance);
        if( MyDebug.LOG )
            Log.d(TAG, "time after calculating average luminance: " + (System.currentTimeMillis() - timeS));
            */

        // write new hdr image
        var maxPossibleValue =
            responseFunctions[0]!!.parameterA * 255 + responseFunctions[0]!!.parameterB
        //float maxPossibleValue = responseFunctions[baseBitmap - 1].parameter_A * 255 + responseFunctions[baseBitmap - 1].parameter_B;
        if (MyDebug.LOG) Log.d(
            TAG,
            "max_possible_value: $maxPossibleValue"
        )
        if (maxPossibleValue < 255.0f) {
            maxPossibleValue =
                255.0f // don't make dark images too bright, see below about linearScale for more details
            if (MyDebug.LOG) Log.d(
                TAG,
                "clamp max_possible_value to: $maxPossibleValue"
            )
        }

        //hdrAlpha = 0.0f; // test
        //final float tonemapScaleC = avgLuminance / 0.8f; // lower values tend to result in too dark pictures; higher values risk over exposed bright areas
        //final float tonemapScaleC = 255.0f;
        //final float tonemapScaleC = 255.0f - medianBrightness;
        var tonemapScaleC = 255.0f

        val medianTarget = getBrightnessTarget(medianBrightness, 2f, 119)

        if (MyDebug.LOG) {
            Log.d(TAG, "median_target: $medianTarget")
            Log.d(TAG, "compare: " + 255.0f / maxPossibleValue)
            Log.d(
                TAG,
                "to: " + ((medianTarget.toFloat()) / medianBrightness.toFloat() + medianTarget / 255.0f - 1.0f)
            )
        }
        if (255.0f / maxPossibleValue < (medianTarget.toFloat()) / medianBrightness.toFloat() + medianTarget / 255.0f - 1.0f) {
            // For Reinhard tonemapping:
            // As noted below, we have f(V) = V.S / (V+C), where V is the HDR value, C is tonemapScaleC
            // and S = (Vmax + C)/Vmax (see below)
            // Ideally we try to choose C such that we map median value M to target T:
            // f(M) = T
            // => T = M . (Vmax + C) / (Vmax . (M + C))
            // => (T/M).(M + C) = (Vmax + C) / Vmax = 1 + C/Vmax
            // => C . ( T/M - 1/Vmax ) = 1 - T
            // => C = (1-T) / (T/M - 1/Vmax)
            // Since we want C <= 1, we must have:
            // 1-T <= T/M - 1/Vmax
            // => 1/Vmax <= T/M + T - 1
            // If this isn't the case, we set C to 1 (to preserve the median as close as possible).
            // Note that if we weren't doing the linear scaling below, this would reduce to choosing
            // C = M(1-T)/T. We also tend to that as maxPossibleValue tends to infinity. So even though
            // we only sometimes enter this case, it's important for cases where maxPossibleValue
            // might be estimated too large (also consider that if we ever support more than 3 images,
            // we'd risk having too large values).
            // If T=M, then this simplifies to C = 1-M.
            // I've tested that using "C = 1-M" always (and no linear scaling) also gives good results:
            // much better compared to Open Kamera 1.39, though not quite as good as doing both this
            // and linear scaling (testHDR18, testHDR26, testHDR32 look too grey and/or bright).
            val tonemapDenom =
                (medianTarget.toFloat()) / medianBrightness.toFloat() - (255.0f / maxPossibleValue)
            if (MyDebug.LOG) Log.d(
                TAG,
                "tonemap_denom: $tonemapDenom"
            )
            if (tonemapDenom != 0.0f) { // just in case
                tonemapScaleC = (255.0f - medianTarget) / tonemapDenom
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "tonemap_scale_c (before setting min): $tonemapScaleC"
                )
                /*if( tonemapScaleC < 0.5f*255.0f ) {
                    throw new RuntimeException("tonemapScaleC: " + tonemapScaleC);
                }*/
                // important to set a min value, see testHDR58, testHDR59, testHDR60 - at least 0.25, but 0.5 works better:
                //tonemapScaleC = Math.max(tonemapScaleC, 0.25f*255.0f);
                tonemapScaleC =
                    max(tonemapScaleC.toDouble(), (0.5f * 255.0f).toDouble()).toFloat()
            }
            //throw new RuntimeException(); // test
        }
        // Higher tonemapScaleC values means darker results from the Reinhard tonemapping.
        // Colours brighter than 255-tonemapScaleC will be made darker, colours darker than 255-tonemapScaleC will be made brighter
        // (tonemapScaleC==255 means therefore that colours will only be made darker).
        if (MyDebug.LOG) Log.d(
            TAG,
            "tonemap_scale_c: $tonemapScaleC"
        )

        // algorithm specific parameters
        var linearScale = 0.0f
        var w = 0.0f
        when (tonemappingAlgorithm) {
            TonemappingAlgorithm.TONEMAPALGORITHM_EXPONENTIAL -> {
                // The basic algorithm is f(V) = 1 - exp( - E * V ), where V is the HDR value, E is a
                // constant. This maps [0, infinity] to [0, 1]. However we have an estimate of the maximum
                // possible value, Vmax, so we can set a linear scaling S so that [0, Vmax] maps to [0, 1]
                // f(V) = S . (1 - exp( - E * V ))
                // so 1 = S . (1 - exp( - E * Vmax ))
                // => S = 1 / (1 - exp( - E * Vmax ))
                // Note that Vmax should be set to a minimum of 255, else we'll make darker images brighter.
                val exposure = 1.2f // should match setting in process_hdr.rs
                linearScale = (1.0 / (1.0 - exp(-exposure * maxPossibleValue / 255.0))).toFloat()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "linear_scale: $linearScale"
                )
            }

            TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD -> {
                // The basic algorithm is f(V) = V / (V+C), where V is the HDR value, C is tonemapScaleC
                // This was used until Open Kamera 1.39, but has the problem of making images too dark: it
                // maps [0, infinity] to [0, 1], but since in practice we never have very large V values, we
                // won't use the full [0, 1] range. So we apply a linear scale S:
                // f(V) = V.S / (V+C)
                // S is chosen such that the maximum possible value, Vmax, maps to 1. So:
                // 1 = Vmax . S / (Vmax + C)
                // => S = (Vmax + C)/Vmax
                // Note that we don't actually know the maximum HDR value, but instead we estimate it with
                // maxPossibleValue, which gives the maximum value we'd have if even the darkest image was
                // 255.0.
                // Note that if maxPossibleValue was less than 255, we'd end up scaling a max value less than
                // 1, to [0, 1], i.e., making dark images brighter, which we don't want, which is why above we
                // set maxPossibleValue to a minimum of 255. In practice, this is unlikely to ever happen
                // since maxPossibleValue is calculated as a maximum possible based on the response functions
                // (as opposed to the real brightest HDR value), so even for dark photos we'd expect to have
                // maxPossibleValue >= 255.
                // Note that the original Reinhard tonemapping paper describes a non-linear scaling by (1 + CV/Vmax^2),
                // though this is poorer performance (in terms of calculation time).
                linearScale = (maxPossibleValue + tonemapScaleC) / maxPossibleValue
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "linear_scale: $linearScale"
                )
            }

            TonemappingAlgorithm.TONEMAPALGORITHM_FU2 -> {
                // For FU2, we have f(V) = U(EV) / U(W), where V is the HDR value, U is a function.
                // We want f(Vmax) = 1, so EVmax = W
                val fu2ExposureBias = 2.0f / 255.0f // should match setting in process_hdr.rs
                w = fu2ExposureBias * maxPossibleValue
                if (MyDebug.LOG) Log.d(TAG, "fu2 W: $w")
            }

            else -> {}
        }

        if (!USE_RENDERSCRIPT) {
            if (releaseBitmaps) {
                outputBitmap = bitmaps[baseBitmap]
            }

            val parametersA = FloatArray(responseFunctions.size)
            val parametersB = FloatArray(responseFunctions.size)
            for (i in responseFunctions.indices) {
                if (responseFunctions[i] != null) {
                    parametersA[i] = responseFunctions[i]!!.parameterA
                    parametersB[i] = responseFunctions[i]!!.parameterB
                } else {
                    parametersA[i] = 1.0f
                    parametersB[i] = 0.0f
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time before HDRApplyFunction: " + (System.currentTimeMillis() - timeS)
            )

            val function: JavaImageFunctionsHDR.HDRApplyFunction
            if (useHdrN) {
                function = JavaImageFunctionsHDR.HDRNApplyFunction(
                    tonemappingAlgorithm,
                    tonemapScaleC,
                    w,
                    linearScale,
                    bitmaps.filterNotNull(),
                    offsetsX,
                    offsetsY,
                    width,
                    height,
                    parametersA,
                    parametersB
                )
            } else {
                function = JavaImageFunctionsHDR.HDRApplyFunction(
                    tonemappingAlgorithm, tonemapScaleC, w, linearScale,
                    bitmaps[0],
                    bitmaps[2],
                    offsetsX[0],
                    offsetsY[0],
                    offsetsX[2], offsetsY[2], width, height, parametersA, parametersB
                )
            }

            JavaImageProcessing.applyFunction(
                function,
                bitmaps[baseBitmap], outputBitmap, 0, 0, width, height
            )
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after HDRApplyFunction: " + (System.currentTimeMillis() - timeS)
            )

            if (hdrAlpha != 0.0f) {
                adjustHistogram(
                    outputBitmap,
                    outputBitmap,
                    width,
                    height,
                    hdrAlpha,
                    nTiles,
                    cePreserveBlacks,
                    timeS
                )
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after adjustHistogram: " + (System.currentTimeMillis() - timeS)
                )
            }
        } else {
            // create RenderScript
            /*if( processHDRScript == null ) {
            processHDRScript = new ScriptC_process_hdr(rs);
        }*/

            val processHDRScript = ScriptC_process_hdr(rs)

            // set allocations
            processHDRScript.set_bitmap0(allocations!![0])
            if (nBitmaps > 2) {
                processHDRScript.set_bitmap2(allocations[2])
            }

            // set offsets
            processHDRScript.set_offset_x0(offsetsX[0])
            processHDRScript.set_offset_y0(offsetsY[0])
            // no offset for middle image
            if (nBitmaps > 2) {
                processHDRScript.set_offset_x2(offsetsX[2])
                processHDRScript.set_offset_y2(offsetsY[2])
            }

            // set response functions
            processHDRScript.set_parameter_A0(responseFunctions[0]!!.parameterA)
            processHDRScript.set_parameter_B0(responseFunctions[0]!!.parameterB)
            // no response function for middle image
            if (nBitmaps > 2) {
                processHDRScript.set_parameter_A2(responseFunctions[2]!!.parameterA)
                processHDRScript.set_parameter_B2(responseFunctions[2]!!.parameterB)
            }

            if (useHdrN) {
                // now need to set values for image 1
                processHDRScript.set_bitmap1(allocations[1])
                processHDRScript.set_offset_x1(offsetsX[1])
                processHDRScript.set_offset_y1(offsetsY[1])
                processHDRScript.set_parameter_A1(responseFunctions[1]!!.parameterA)
                processHDRScript.set_parameter_B1(responseFunctions[1]!!.parameterB)
            }

            if (nBitmaps > 3) {
                processHDRScript.set_bitmap3(allocations[3])
                processHDRScript.set_offset_x3(offsetsX[3])
                processHDRScript.set_offset_y3(offsetsY[3])
                processHDRScript.set_parameter_A3(responseFunctions[3]!!.parameterA)
                processHDRScript.set_parameter_B3(responseFunctions[3]!!.parameterB)

                if (nBitmaps > 4) {
                    processHDRScript.set_bitmap4(allocations[4])
                    processHDRScript.set_offset_x4(offsetsX[4])
                    processHDRScript.set_offset_y4(offsetsY[4])
                    processHDRScript.set_parameter_A4(responseFunctions[4]!!.parameterA)
                    processHDRScript.set_parameter_B4(responseFunctions[4]!!.parameterB)

                    if (nBitmaps > 5) {
                        processHDRScript.set_bitmap5(allocations[5])
                        processHDRScript.set_offset_x5(offsetsX[5])
                        processHDRScript.set_offset_y5(offsetsY[5])
                        processHDRScript.set_parameter_A5(responseFunctions[5]!!.parameterA)
                        processHDRScript.set_parameter_B5(responseFunctions[5]!!.parameterB)

                        if (nBitmaps > 6) {
                            processHDRScript.set_bitmap6(allocations[6])
                            processHDRScript.set_offset_x6(offsetsX[6])
                            processHDRScript.set_offset_y6(offsetsY[6])
                            processHDRScript.set_parameter_A6(responseFunctions[6]!!.parameterA)
                            processHDRScript.set_parameter_B6(responseFunctions[6]!!.parameterB)
                        }
                    }
                }
            }

            // set globals

            // set tonemapping algorithm
            when (tonemappingAlgorithm) {
                TonemappingAlgorithm.TONEMAPALGORITHM_CLAMP -> {
                    if (MyDebug.LOG) Log.d(TAG, "tonemapping algorithm: clamp")
                    processHDRScript.set_tonemap_algorithm(processHDRScript._tonemap_algorithm_clamp_c)
                }

                TonemappingAlgorithm.TONEMAPALGORITHM_EXPONENTIAL -> {
                    if (MyDebug.LOG) Log.d(TAG, "tonemapping algorithm: exponential")
                    processHDRScript.set_tonemap_algorithm(processHDRScript._tonemap_algorithm_exponential_c)
                }

                TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD -> {
                    if (MyDebug.LOG) Log.d(TAG, "tonemapping algorithm: reinhard")
                    processHDRScript.set_tonemap_algorithm(processHDRScript._tonemap_algorithm_reinhard_c)
                }

                TonemappingAlgorithm.TONEMAPALGORITHM_FU2 -> {
                    if (MyDebug.LOG) Log.d(TAG, "tonemapping algorithm: fu2")
                    processHDRScript.set_tonemap_algorithm(processHDRScript._tonemap_algorithm_fu2_c)
                }

                TonemappingAlgorithm.TONEMAPALGORITHM_ACES -> {
                    if (MyDebug.LOG) Log.d(TAG, "tonemapping algorithm: aces")
                    processHDRScript.set_tonemap_algorithm(processHDRScript._tonemap_algorithm_aces_c)
                }
            }

            processHDRScript.set_tonemap_scale(tonemapScaleC)

            // set algorithm specific parameters
            when (tonemappingAlgorithm) {
                TonemappingAlgorithm.TONEMAPALGORITHM_EXPONENTIAL, TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD -> processHDRScript.set_linear_scale(
                    linearScale
                )

                TonemappingAlgorithm.TONEMAPALGORITHM_FU2 -> {
                    processHDRScript.set_W(w)
                }

                else -> {}
            }

            if (MyDebug.LOG) Log.d(TAG, "call processHDRScript")
            val outputAllocation: Allocation?
            var freeOutputAllocation = false
            if (releaseBitmaps) {
                // must use allocations[baseBitmap] as the output, as that's the image guaranteed to have no offset (otherwise we'll have
                // problems due to the output being equal to one of the inputs)
                outputAllocation = allocations[baseBitmap]
                // similarly must be the baseBitmap we copy to
                outputBitmap = bitmaps[baseBitmap]
            } else {
                outputAllocation = Allocation.createFromBitmap(rs, outputBitmap)
                freeOutputAllocation = true
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time before processHDRScript: " + (System.currentTimeMillis() - timeS)
            )
            if (useHdrN) {
                processHDRScript.set_n_bitmaps_g(nBitmaps)
                processHDRScript.forEach_hdr_n(allocations[baseBitmap], outputAllocation)
            } else {
                processHDRScript.forEach_hdr(allocations[baseBitmap], outputAllocation)
            }
            /*processHDRScript.setNBitmapsG(nBitmaps);
        processHDRScript.forEach_hdr_n(allocations[baseBitmap], outputAllocation);*/
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after processHDRScript: " + (System.currentTimeMillis() - timeS)
            )

            if (releaseBitmaps) {
                if (MyDebug.LOG) Log.d(TAG, "release bitmaps")
                // bitmaps.get(baseBitmap) will store HDR image, so free up the rest of the memory asap - we no longer need the remaining bitmaps
                for (i in bitmaps.indices) {
                    if (i != baseBitmap) {
                        val bitmap = bitmaps[i]
                        bitmap!!.recycle()
                    }
                }
            }

            if (hdrAlpha != 0.0f) {
                adjustHistogramRS(
                    outputAllocation,
                    outputAllocation,
                    width,
                    height,
                    hdrAlpha,
                    nTiles,
                    cePreserveBlacks,
                    timeS
                )
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after adjustHistogram: " + (System.currentTimeMillis() - timeS)
                )
            }

            outputAllocation!!.copyTo(outputBitmap)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after copying to bitmap: " + (System.currentTimeMillis() - timeS)
            )

            if (freeOutputAllocation) outputAllocation.destroy()
        }

        if (releaseBitmaps) {
            // make it so that we store the output bitmap as first in the list
            bitmaps[0] = outputBitmap
            for (i in 1..<bitmaps.size) {
                bitmaps[i] = null
            }
        }

        if (allocations != null) {
            for (i in 0..<nBitmaps) {
                allocations[i]!!.destroy()
                allocations[i] = null
            }
        }
        freeScripts()
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time for processHDRCore: " + (System.currentTimeMillis() - timeS)
        )
    }

    private fun processSingleImage(
        bitmaps: MutableList<Bitmap>,
        releaseBitmaps: Boolean,
        outputBitmap: Bitmap?,
        hdrAlpha: Float,
        nTiles: Int,
        cePreserveBlacks: Boolean,
        droTonemappingAlgorithm: DROTonemappingAlgorithm
    ) {
        var outputBitmap = outputBitmap
        if (MyDebug.LOG) Log.d(TAG, "processSingleImage")

        val timeS = System.currentTimeMillis()

        val width = bitmaps[0].width
        val height = bitmaps[0].height

        var inputBitmap = bitmaps[0]

        if (releaseBitmaps) {
            outputBitmap = inputBitmap
        }

        if (droTonemappingAlgorithm == DROTonemappingAlgorithm.DROALGORITHMGAINGAMMA) {
            // brighten?
            val histo = computeHistogram(inputBitmap, HistogramType.HISTOGRAM_TYPE_VALUE)
            val histogramInfo = getHistogramInfo(histo)
            val brightness = histogramInfo.medianBrightness
            val maxBrightness = histogramInfo.maxBrightness
            if (MyDebug.LOG) Log.d(
                TAG,
                "### processSingleImage: time after computeHistogram: " + (System.currentTimeMillis() - timeS)
            )
            if (MyDebug.LOG) {
                Log.d(TAG, "median brightness: $brightness")
                Log.d(TAG, "max brightness: $maxBrightness")
            }
            val brightenFactors = computeBrightenFactors(false, 0, 0, brightness, maxBrightness)
            val gain = brightenFactors.gain
            val gamma = brightenFactors.gamma
            val lowX = brightenFactors.lowX
            val midX = brightenFactors.midX
            if (MyDebug.LOG) {
                Log.d(TAG, "gain: $gain")
                Log.d(TAG, "gamma: $gamma")
                Log.d(TAG, "low_x: $lowX")
                Log.d(TAG, "mid_x: $midX")
            }

            if (abs(gain - 1.0) > 1.0e-5 || maxBrightness != 255 || abs(gamma - 1.0) > 1.0e-5) {
                if (MyDebug.LOG) Log.d(TAG, "apply gain/gamma")

                //if( true )
                //  throw new HDRProcessorException(HDRProcessorException.UNEQUAL_SIZES); // test
                val function: JavaImageFunctionsHDR.DROBrightenApplyFunction =
                    JavaImageFunctionsHDR.DROBrightenApplyFunction(
                        gain,
                        gamma,
                        lowX,
                        midX,
                        maxBrightness.toFloat()
                    )
                JavaImageProcessing.applyFunction(
                    function,
                    inputBitmap,
                    outputBitmap,
                    0,
                    0,
                    width,
                    height
                )

                // output is now the input for subsequent operations
                inputBitmap = outputBitmap!!
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### processSingleImage: time after dro_brighten: " + (System.currentTimeMillis() - timeS)
                )
            }
        }

        adjustHistogram(
            inputBitmap,
            outputBitmap,
            width,
            height,
            hdrAlpha,
            nTiles,
            cePreserveBlacks,
            timeS
        )

        if (MyDebug.LOG) Log.d(
            TAG,
            "### time for processSingleImage: " + (System.currentTimeMillis() - timeS)
        )
    }

    private fun processSingleImageRS(
        bitmaps: List<Bitmap?>,
        releaseBitmaps: Boolean,
        outputBitmap: Bitmap?,
        hdrAlpha: Float,
        nTiles: Int,
        cePreserveBlacks: Boolean,
        droTonemappingAlgorithm: DROTonemappingAlgorithm
    ) {
        var outputBitmap: Bitmap? = outputBitmap
        if (MyDebug.LOG) Log.d(TAG, "processSingleImage")

        val timeS = System.currentTimeMillis()

        val width = bitmaps[0]!!.width
        val height = bitmaps[0]!!.height

        initRenderscript()
        if (MyDebug.LOG) Log.d(
            TAG,
            "### processSingleImage: time after creating renderscript: " + (System.currentTimeMillis() - timeS)
        )

        // create allocation
        var allocation = Allocation.createFromBitmap(rs, bitmaps[0])

        val outputAllocation: Allocation
        var freeOutputAllocation = false
        if (releaseBitmaps) {
            outputAllocation = allocation
            outputBitmap = bitmaps[0]
        } else {
            freeOutputAllocation = true
            outputAllocation = Allocation.createFromBitmap(rs, outputBitmap)
        }

        if (droTonemappingAlgorithm == DROTonemappingAlgorithm.DROALGORITHMGAINGAMMA) {
            // brighten?
            val histo = computeHistogramRS(
                allocation, width, height,
                avg = false,
                floatingPoint = false
            )
            val histogramInfo = getHistogramInfo(histo)
            val brightness = histogramInfo.medianBrightness
            val maxBrightness = histogramInfo.maxBrightness
            if (MyDebug.LOG) Log.d(
                TAG,
                "### processSingleImage: time after computeHistogram: " + (System.currentTimeMillis() - timeS)
            )
            if (MyDebug.LOG) {
                Log.d(TAG, "median brightness: $brightness")
                Log.d(TAG, "max brightness: $maxBrightness")
            }
            val brightenFactors = computeBrightenFactors(false, 0, 0, brightness, maxBrightness)
            val gain = brightenFactors.gain
            val gamma = brightenFactors.gamma
            val lowX = brightenFactors.lowX
            val midX = brightenFactors.midX
            if (MyDebug.LOG) {
                Log.d(TAG, "gain: $gain")
                Log.d(TAG, "gamma: $gamma")
                Log.d(TAG, "low_x: $lowX")
                Log.d(TAG, "mid_x: $midX")
            }

            if (abs(gain - 1.0) > 1.0e-5 || maxBrightness != 255 || abs(gamma - 1.0) > 1.0e-5) {
                if (MyDebug.LOG) Log.d(TAG, "apply gain/gamma")

                //if( true )
                //  throw new HDRProcessorException(HDRProcessorException.UNEQUAL_SIZES); // test

                /*if( !useRenderscript ) {
                    JavaImageFunctionsHDR.DROBrightenApplyFunction function = new JavaImageFunctionsHDR.DROBrightenApplyFunction(gain, gamma, lowX, midX, maxBrightness);
                    JavaImageProcessing.applyFunction(function, allocation, false, outputAllocation, 0, 0, width, height);
                }
                else*/
                run {
                    val script: ScriptC_avg_brighten = ScriptC_avg_brighten(rs)
                    script.invoke_setBrightenParameters(
                        gain,
                        gamma,
                        lowX,
                        midX,
                        maxBrightness.toFloat()
                    )
                    script.forEach_dro_brighten(allocation, outputAllocation)
                }

                // output is now the input for subsequent operations
                if (freeOutputAllocation) {
                    allocation.destroy()
                    freeOutputAllocation = false
                }
                allocation = outputAllocation
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### processSingleImage: time after dro_brighten: " + (System.currentTimeMillis() - timeS)
                )
            }
        }

        adjustHistogramRS(
            allocation,
            outputAllocation,
            width,
            height,
            hdrAlpha,
            nTiles,
            cePreserveBlacks,
            timeS
        )

        outputAllocation.copyTo(outputBitmap)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### processSingleImage: time after copying to bitmap: " + (System.currentTimeMillis() - timeS)
        )

        if (freeOutputAllocation) allocation.destroy()
        outputAllocation.destroy()
        freeScripts()

        if (MyDebug.LOG) Log.d(
            TAG,
            "### time for processSingleImage: " + (System.currentTimeMillis() - timeS)
        )
    }

    fun brightenImage(
        bitmap: Bitmap,
        brightness: Int,
        maxBrightness: Int,
        brightnessTarget: Int
    ) {
        if (MyDebug.LOG) {
            Log.d(TAG, "brightenImage")
            Log.d(TAG, "brightness: $brightness")
            Log.d(TAG, "max_brightness: $maxBrightness")
            Log.d(TAG, "brightness_target: $brightnessTarget")
        }
        val brightenFactors = computeBrightenFactors(
            false,
            0,
            0,
            brightness,
            maxBrightness,
            brightnessTarget,
            false
        )
        val gain = brightenFactors.gain
        val gamma = brightenFactors.gamma
        val lowX = brightenFactors.lowX
        val midX = brightenFactors.midX
        if (MyDebug.LOG) {
            Log.d(TAG, "gain: $gain")
            Log.d(TAG, "gamma: $gamma")
            Log.d(TAG, "low_x: $lowX")
            Log.d(TAG, "mid_x: $midX")
        }

        if (abs(gain - 1.0) > 1.0e-5 || maxBrightness != 255 || abs(gamma - 1.0) > 1.0e-5) {
            if (MyDebug.LOG) Log.d(TAG, "apply gain/gamma")

            if (!USE_RENDERSCRIPT) {
                val function: JavaImageFunctionsHDR.DROBrightenApplyFunction =
                    JavaImageFunctionsHDR.DROBrightenApplyFunction(
                        gain,
                        gamma,
                        lowX,
                        midX,
                        maxBrightness.toFloat()
                    )
                JavaImageProcessing.applyFunction(
                    function,
                    bitmap,
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height
                )
            } else {
                initRenderscript()

                val allocation = Allocation.createFromBitmap(rs, bitmap)
                val script = ScriptC_avg_brighten(rs)
                script.invoke_setBrightenParameters(
                    gain,
                    gamma,
                    lowX,
                    midX,
                    maxBrightness.toFloat()
                )

                script.forEach_dro_brighten(allocation, allocation)

                allocation.copyTo(bitmap)
                allocation.destroy()

                freeScripts()
            }
        }
    }

    private fun initRenderscript() {
        if (MyDebug.LOG) Log.d(TAG, "initRenderscript")
        if (!USE_RENDERSCRIPT) {
            throw RuntimeException("shouldn't be using renderscript")
        }
        if (rs == null) {
            // initialise renderscript
            this.rs = RenderScript.create(context)
            if (MyDebug.LOG) Log.d(TAG, "create renderscript object")
        }
    }

    var avgSampleSize: Int = 1
        private set

    /** As part of the noise reduction process, the caller should scale the input images down by the factor returned
     * by this method. This both provides a spatial smoothing, as well as improving performance and memory usage.
     */
    fun getAvgSampleSize(captureResultIso: Int, captureResultExposureTim: Long): Int {
        // If changing this, may also want to change the radius of the spatial filter in avg_brighten.rs ?
        //this.cachedAvgSampleSize = (nImages>=8) ? 2 : 1;
        this.avgSampleSize =
            if (sceneIsLowLight(captureResultIso, captureResultExposureTim)) 2 else 1
        //this.cachedAvgSampleSize = 1;
        //this.cachedAvgSampleSize = 2;
        if (MyDebug.LOG) Log.d(TAG, "getAvgSampleSize: $avgSampleSize")
        return avgSampleSize
    }

    class AvgData internal constructor(// if useRenderscript==true
        var allocationOut: Allocation?, // if useRenderscript==false
        var pixelsRgbfOut: FloatArray,
        var bitmapAvgAlign: Bitmap?,
        var allocationAvgAlign: Allocation?, // first bitmap, need to keep until all images are processed, due to being used for allocationOrig
        var bitmapOrig: Bitmap?, // saved version of the first allocation
        var allocationOrig: Allocation?
    ) {
        fun destroy() {
            if (MyDebug.LOG) Log.d(TAG, "AvgData.destroy()")
            if (allocationOut != null) {
                allocationOut!!.destroy()
                allocationOut = null
            }
            pixelsRgbfOut = floatArrayOf()
            if (bitmapAvgAlign != null) {
                bitmapAvgAlign!!.recycle()
                bitmapAvgAlign = null
            }
            if (allocationAvgAlign != null) {
                allocationAvgAlign!!.destroy()
                allocationAvgAlign = null
            }
            if (bitmapOrig != null) {
                bitmapOrig!!.recycle()
                bitmapOrig = null
            }
            if (allocationOrig != null) {
                allocationOrig!!.destroy()
                allocationOrig = null
            }
        }
    }

    /** Combines two images by averaging them. Each pixel of bitmapAvg is modified to contain:
     * (avgFactor * bitmapAvg + bitmapNew)/(avgFactor+1)
     * A simple average is therefore obtained by calling this function with avgFactor = 1.0f.
     * For averaging multiple images, first call this function with avgFactor 1.0 for the first
     * two images, then call updateAvg() for subsequent images, increasing avgFactor by 1.0 each
     * time.
     * The reason we do it this way (rather than just receiving a list of bitmaps) is so that we
     * can average multiple images without having to keep them all in memory simultaneously.
     * @param bitmapAvg     One of the input images. The bitmap is recycled.
     * @param bitmapNew     The other input image. The bitmap is recycled.
     * @param avgFactor     The weighting factor for bitmapAvg.
     * @param iso            The ISO used to take the photos.
     * @param exposureTime  The exposure time used to take the photos.
     * @param zoomFactor    The digital zoom factor used to take the photos.
     */
    @Throws(HDRProcessorException::class)
    fun processAvg(
        bitmapAvg: Bitmap,
        bitmapNew: Bitmap?,
        avgFactor: Float,
        iso: Int,
        exposureTime: Long,
        zoomFactor: Float
    ): AvgData {
        if (MyDebug.LOG) {
            Log.d(TAG, "processAvg")
            Log.d(TAG, "avg_factor: $avgFactor")
        }
        if (bitmapAvg.width != bitmapNew?.width ||
            bitmapAvg.height != bitmapNew.height
        ) {
            if (MyDebug.LOG) {
                Log.e(TAG, "bitmaps not of same resolution")
            }
            throw HDRProcessorException(HDRProcessorException.UNEQUAL_SIZES)
        }

        val timeS = System.currentTimeMillis()

        val width = bitmapAvg.width
        val height = bitmapAvg.height

        if (USE_RENDERSCRIPT) {
            initRenderscript()
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after creating renderscript: " + (System.currentTimeMillis() - timeS)
            )
        }

        // create allocations
        /*Allocation allocationAvg = Allocation.createFromBitmap(rs, bitmapAvg);
        //Allocation allocationNew = Allocation.createFromBitmap(rs, bitmapNew);
        //Allocation allocationOut = Allocation.createTyped(rs, Type.createXY(rs, Element.F32_3(rs), width, height));
        if( MyDebug.LOG )
            Log.d(TAG, "### time after creating allocations from bitmaps: " + (System.currentTimeMillis() - timeS));
            */

        /*final boolean useSharpnessTest = false; // disabled for now - takes about 1s extra, and no evidence this helps quality
        if( useSharpnessTest ) {
            float sharpnessAvg = computeSharpness(allocationAvg, width, timeS);
            float sharpnessNew = computeSharpness(allocationNew, width, timeS);
            if( sharpnessNew > sharpnessAvg ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "use new image as reference");
                Allocation dummyAllocation = allocationAvg;
                allocationAvg = allocationNew;
                allocationNew = dummyAllocation;
                Bitmap dummyBitmap = bitmapAvg;
                bitmapAvg = bitmapNew;
                bitmapNew = dummyBitmap;
                sharpIndex = 1;
            }
            else {
                sharpIndex = 0;
            }
            if( MyDebug.LOG )
                Log.d(TAG, "sharpIndex: " + sharpIndex);
        }*/

        /*LuminanceInfo luminanceInfo = computeMedianLuminance(bitmapAvg, 0, 0, width, height);
        if( MyDebug.LOG )
            Log.d(TAG, "median: " + luminanceInfo.medianValue);*/
        val avgData = processAvgCore(
            null,
            bitmapAvg,
            bitmapNew,
            width,
            height,
            avgFactor,
            iso,
            exposureTime,
            zoomFactor,
            timeS
        )

        //allocation_avg.copyTo(bitmapAvg);
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time for processAvg: " + (System.currentTimeMillis() - timeS)
        )

        return avgData
    }

    /** Combines multiple images by averaging them. See processAvg() for more details.
     * @param avgData       The argument returned by processAvg().
     * @param width          The width of the images.
     * @param height         The height of the images.
     * @param bitmapNew     The new input image. The bitmap is recycled.
     * @param avgFactor     The weighting factor for bitmapAvg.
     * @param iso            The ISO used to take the photos.
     * @param exposureTime  The exposure time used to take the photos.
     * @param zoomFactor    The digital zoom factor used to take the photos.
     */
    @Throws(HDRProcessorException::class)
    fun updateAvg(
        avgData: AvgData?,
        width: Int,
        height: Int,
        bitmapNew: Bitmap?,
        avgFactor: Float,
        iso: Int,
        exposureTime: Long,
        zoomFactor: Float
    ) {
        if (MyDebug.LOG) {
            Log.d(TAG, "updateAvg")
            Log.d(TAG, "avg_factor: $avgFactor")
        }
        if (width != bitmapNew?.width ||
            height != bitmapNew.height
        ) {
            if (MyDebug.LOG) {
                Log.e(TAG, "bitmaps not of same resolution")
            }
            throw HDRProcessorException(HDRProcessorException.UNEQUAL_SIZES)
        }

        val timeS = System.currentTimeMillis()

        // create allocations
        /*Allocation allocationNew = Allocation.createFromBitmap(rs, bitmapNew);
        if( MyDebug.LOG )
            Log.d(TAG, "### time after creating allocations from bitmaps: " + (System.currentTimeMillis() - timeS));*/
        processAvgCore(
            avgData,
            null,
            bitmapNew,
            width,
            height,
            avgFactor,
            iso,
            exposureTime,
            zoomFactor,
            timeS
        )

        if (MyDebug.LOG) Log.d(
            TAG,
            "### time for updateAvg: " + (System.currentTimeMillis() - timeS)
        )
    }

    /** Core algorithm for Noise Reduction algorithm.
     * @param avgData       Should be null for first call, and non-null for subsequent calls. This should
     * be the AvgData returned by the first call.
     * @param bitmapAvg     If non-null, the first bitmap (which will be recycled when the returned
     * AvgData is destroyed). If null, an avgData should be supplied.
     * @param bitmapNew     The new bitmap to combined. The bitmap will be recycled.
     * @param width          The width of the bitmaps.
     * @param height         The height of the bitmaps.
     * @param avgFactor     The averaging factor.
     * @param iso            The ISO used for the photos.
     * @param zoomFactor    The digital zoom factor used to take the photos.
     * @param timeS         Time, for debugging.
     */
    private fun processAvgCore(
        avgData: AvgData?,
        bitmapAvg: Bitmap?,
        bitmapNew: Bitmap?,
        width: Int,
        height: Int,
        avgFactor: Float,
        iso: Int,
        exposureTime: Long,
        zoomFactor: Float,
        timeS: Long
    ): AvgData {
        var bitmapNew = bitmapNew
        if (MyDebug.LOG) {
            Log.d(TAG, "processAvgCore")
            Log.d(TAG, "iso: $iso")
            Log.d(TAG, "zoom_factor: $zoomFactor")
        }

        // If non-null, allocationOut is an allocation of the averaged image so far, and it will
        // also be used for the output allocation. If null, the first bitmap should be supplied as
        // bitmapAvg, and a new allocation will be created for the output.
        var allocationOut: Allocation? = null
        var pixelsRgbfOut: FloatArray = floatArrayOf()
        var bitmapAvgAlign: Bitmap? =
            null // if non-null, use this bitmap for alignment for averaged image.
        var allocationAvgAlign: Allocation? = null // allocation corresponding to bitmapAvgAlign
        var bitmapOrig: Bitmap? =
            null // if non-null, this is a bitmap representing the first image.
        var allocationOrig: Allocation? = null // allocation corresponding to bitmapOrig
        if (avgData != null) {
            allocationOut = avgData.allocationOut
            pixelsRgbfOut = avgData.pixelsRgbfOut
            bitmapAvgAlign = avgData.bitmapAvgAlign
            allocationAvgAlign = avgData.allocationAvgAlign
            bitmapOrig = avgData.bitmapOrig
            allocationOrig = avgData.allocationOrig
        }

        //Allocation allocationDiffs = null;
        offsetsX = IntArray(2)
        offsetsY = IntArray(2)
        val floatingPoint: Boolean
        if (bitmapAvg != null && allocationOut == null && pixelsRgbfOut.isEmpty()) {
            if (MyDebug.LOG) Log.d(TAG, "process first bitmap")
            floatingPoint = false
        } else if (bitmapAvg == null && (allocationOut != null || pixelsRgbfOut.isNotEmpty())) {
            floatingPoint = true
            if (MyDebug.LOG) Log.d(TAG, "processing existing result")
        } else {
            throw RuntimeException("only one of bitmap_avg or allocation_out/pixels_rgbf_out should be supplied")
        }

        run {
            // perform auto-alignment
            val alignBitmaps: MutableList<Bitmap?> = ArrayList()
            var alignAllocations: Array<Allocation?>? = null
            var bitmapNewAlign: Bitmap?
            var allocationNewAlign: Allocation? = null
            val alignmentWidth: Int
            val alignmentHeight: Int
            var fullAlignmentWidth = width
            var fullAlignmentHeight = height

            //final int scaleAlignSize = 2;
            //final int scaleAlignSize = 4;
            //final int scaleAlignSize = Math.max(4 / this.cachedAvgSampleSize, 1);
            val scaleAlignSize = if (zoomFactor > 3.9f) 1 else max(
                (4 / this.getAvgSampleSize(
                    iso,
                    exposureTime
                )).toDouble(), 1.0
            ).toInt()
            if (MyDebug.LOG) Log.d(
                TAG,
                "scale_align_size: $scaleAlignSize"
            )
            var cropToCentre = true
            run {
                // use scaled down and/or cropped bitmaps for alignment
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time before creating allocations for autoalignment: " + (System.currentTimeMillis() - timeS)
                )
                val alignScaleMatrix = Matrix()
                alignScaleMatrix.postScale(1.0f / scaleAlignSize, 1.0f / scaleAlignSize)
                fullAlignmentWidth /= scaleAlignSize
                fullAlignmentHeight /= scaleAlignSize

                val fullAlign =
                    false // whether alignment images should be created as being cropped to the centre
                //final boolean fullAlign = true; // whether alignment images should be created as being cropped to the centre
                var alignWidth = width
                var alignHeight = height
                var alignX = 0
                var alignY = 0
                if (!fullAlign) {
                    // need to use /2 rather than /4 to prevent misalignment in testAvg26
                    //alignWidth = width/4;
                    //alignHeight = height/4;
                    alignWidth = width / 2
                    alignHeight = height / 2
                    alignX = (width - alignWidth) / 2
                    alignY = (height - alignHeight) / 2
                    cropToCentre =
                        false // no need to crop in autoAlignment, as we're cropping here
                }

                val filterAlign = false
                //final boolean filterAlign = true;
                if (bitmapAvgAlign == null) {
                    bitmapAvgAlign = Bitmap.createBitmap(
                        bitmapAvg!!,
                        alignX,
                        alignY,
                        alignWidth,
                        alignHeight,
                        alignScaleMatrix,
                        filterAlign
                    )
                    if (USE_RENDERSCRIPT) allocationAvgAlign =
                        Allocation.createFromBitmap(rs, bitmapAvgAlign)
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "### time after creating avg bitmap for autoalignment: " + (System.currentTimeMillis() - timeS)
                    )
                }
                bitmapNewAlign = Bitmap.createBitmap(
                    bitmapNew!!,
                    alignX,
                    alignY,
                    alignWidth,
                    alignHeight,
                    alignScaleMatrix,
                    filterAlign
                )
                if (USE_RENDERSCRIPT) allocationNewAlign =
                    Allocation.createFromBitmap(rs, bitmapNewAlign)

                alignmentWidth = bitmapNewAlign!!.width
                alignmentHeight = bitmapNewAlign!!.height

                alignBitmaps.add(bitmapAvgAlign!!)
                alignBitmaps.add(bitmapNewAlign!!)
                if (USE_RENDERSCRIPT) {
                    alignAllocations = arrayOfNulls(2)
                    alignAllocations!![0] = allocationAvgAlign
                    alignAllocations!![1] = allocationNewAlign
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after creating allocations for autoalignment: " + (System.currentTimeMillis() - timeS)
                )
            }

            // misalignment more likely in "dark" images with more images and/or longer exposures
            // using maxAlignScale=2 needed to prevent misalignment in testAvg51; also helps testAvg14
            val wider = sceneIsLowLight(iso, exposureTime)
            autoAlignment(
                offsetsX!!,
                offsetsY!!,
                alignAllocations,
                alignmentWidth,
                alignmentHeight,
                alignBitmaps,
                0,
                true,
                null,
                false,
                1,
                cropToCentre,
                if (wider) 2 else 1,
                fullAlignmentWidth,
                fullAlignmentHeight,
                timeS
            )

            /*
            // compute allocationDiffs
            // if enabling this, should also:
            // - set fullAlign above to true
            // - set filterAlign above to true
            if( processAvgScript == null ) {
                processAvgScript = new ScriptC_process_avg(rs);
            }
            processAvgScript?.set_bitmapAlignNew(alignAllocations[1]);
            processAvgScript?.set_offset_XNew(offsetsX[1]);
            processAvgScript?.set_offset_YNew(offsetsY[1]);
            allocationDiffs = Allocation.createTyped(rs, Type.createXY(rs, Element.F32(rs), alignmentWidth, alignmentHeight));
            processAvgScript?.forEach_compute_diff(alignAllocations[0], allocationDiffs);
            processAvgScript?.setScaleAlignSize(scaleAlignSize);
            processAvgScript?.setAllocationDiffs(allocationDiffs);
            */
            run {
                for (i in offsetsX!!.indices) {
                    offsetsX!![i] *= scaleAlignSize
                }
                for (i in offsetsY!!.indices) {
                    offsetsY!![i] *= scaleAlignSize
                }
            }

            if (bitmapNewAlign != null) {
                bitmapNewAlign!!.recycle()
                bitmapNewAlign = null
            }
            if (allocationNewAlign != null) {
                allocationNewAlign!!.destroy()
                allocationNewAlign = null
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "### time after autoAlignment: " + (System.currentTimeMillis() - timeS))
            }
        }

        // write new avg image

        // higher wiener_C (and higher wienerCutoffFactor) means more averaging (but more risk of ghosting)
        // if changing this, pay close attention to tests testAvg6, testAvg8, testAvg17, testAvg23
        var limitedIso = min(iso.toDouble(), 400.0).toFloat()
        var wienerCutoffFactor = 1.0f
        if (iso >= 700) {
            // helps reduce speckles in testAvg17, testAvg23, testAvg33, testAvg36, testAvg38
            // using this level for testAvg31 (ISO 609) would increase ghosting
            //limitedIso = 500;
            limitedIso = 800f
            if (iso >= 1100) {
                // helps further reduce speckles in testAvg17, testAvg38
                // but don't do for iso >= 700 as makes "vicks" text in testAvg23 slightly more blurred
                wienerCutoffFactor = 8.0f
            }
        }
        limitedIso = max(limitedIso.toDouble(), 100.0).toFloat()
        var wienerC = 10.0f * limitedIso

        //float wiener_C = 1000.0f;
        //float wiener_C = 4000.0f;

        // Tapering the wiener scale means that we do more averaging for earlier images in the stack, the
        // logic being we'll have more chance of ghosting or misalignment with later images.
        // This helps: testAvg31, testAvg33.
        // Also slightly helps testAvg17, testAvg23 (slightly less white speckle on tv), testAvg28
        // (one less white speckle on face).
        // Note that too much tapering risks increasing ghosting in testAvg26, testAvg39.
        val taperedWienerScale = 1.0f - 0.5.pow(avgFactor.toDouble()).toFloat()
        if (MyDebug.LOG) {
            Log.d(TAG, "avg_factor: $avgFactor")
            Log.d(
                TAG,
                "tapered_wiener_scale: $taperedWienerScale"
            )
        }
        wienerC /= taperedWienerScale

        val wienerCCutoff = wienerCutoffFactor * wienerC
        if (MyDebug.LOG) {
            Log.d(TAG, "wiener_C: $wienerC")
            Log.d(
                TAG,
                "wiener_cutoff_factor: $wienerCutoffFactor"
            )
        }

        if (bitmapOrig == null) {
            // bitmapOrig should only be null for the first pair of images, which should be when we
            // have the avg image not in floating point format
            if (floatingPoint) {
                throw RuntimeException("is in floating point mode, but no bitmap_orig supplied")
            }
            bitmapOrig = bitmapAvg
        }

        if (!USE_RENDERSCRIPT /*&& !floatingPoint*/) {
            /*float [] pixelsRgbf;
            if( allocationAvg != null ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "### time before AllocationToRGBf: " + (System.currentTimeMillis() - timeS));
                pixelsRgbf = AllocationToRGBf(allocationAvg, width, height);
                if( MyDebug.LOG )
                    Log.d(TAG, "### time after AllocationToRGBf: " + (System.currentTimeMillis() - timeS));
            }
            else {
                pixelsRgbf = new float[3*width*height];
            }*/
            if (pixelsRgbfOut.isEmpty()) {
                if (MyDebug.LOG) Log.d(TAG, "need to create pixels_rgbf_out")
                pixelsRgbfOut = FloatArray(3 * width * height)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after create pixels_rgbf_out: " + (System.currentTimeMillis() - timeS)
                )
            }
            val function: JavaImageFunctionsHDR.AvgApplyFunction =
                JavaImageFunctionsHDR.AvgApplyFunction(
                    pixelsRgbfOut, bitmapNew!!, bitmapOrig!!, width, height,
                    offsetsX!![1],
                    offsetsY!![1], avgFactor, wienerC, wienerCCutoff
                )
            JavaImageProcessing.applyFunction(function, bitmapAvg, null, 0, 0, width, height)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after AvgApplyFunction: " + (System.currentTimeMillis() - timeS)
            )

            /*RGBfToAllocation(pixelsRgbf, allocationOut, width, height);
            if( MyDebug.LOG )
                Log.d(TAG, "### time after RGBfToAllocation: " + (System.currentTimeMillis() - timeS));*/

            /*if( allocationOrig == null ) {
                // allocationOrig should only be null for the first pair of images, which should be when we
                // have the avg image not in floating point format
                if( floatingPoint ) {
                    throw new RuntimeException("is in floating point mode, but no allocationOrig supplied");
                }
                if( MyDebug.LOG )
                    Log.d(TAG, "create allocationOrig");
                allocationOrig = Allocation.createFromBitmap(rs, bitmapAvg);
            }*/
        } else {
            var allocationAvg = allocationOut

            if (allocationOut == null) {
                if (MyDebug.LOG) Log.d(TAG, "need to create allocation_out")
                allocationOut =
                    Allocation.createTyped(rs, Type.createXY(rs, Element.F32_3(rs), width, height))
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after create allocation_out: " + (System.currentTimeMillis() - timeS)
                )
            }

            var freeAllocationAvg = false
            if (allocationAvg == null) {
                allocationAvg = Allocation.createFromBitmap(rs, bitmapAvg)
                freeAllocationAvg = true
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after creating allocation_avg from bitmap: " + (System.currentTimeMillis() - timeS)
                )
            }

            // create RenderScript
            if (processAvgScript == null) {
                processAvgScript = ScriptC_process_avg(rs)
            }

            //ScriptC_process_avg processAvgScript = new ScriptC_process_avg(rs);

            /*final boolean separateFirstPass = false; // whether to convert the first two images in separate passes (reduces memory)
        if( first && separateFirstPass ) {
            if( MyDebug.LOG )
                Log.d(TAG, "### time before convertToF: " + (System.currentTimeMillis() - timeS));
            processAvgScript?.forEach_convert_to_f(allocationAvg, allocationOut);
            if( MyDebug.LOG )
                Log.d(TAG, "### time after convertToF: " + (System.currentTimeMillis() - timeS));
            if( freeAllocationAvg ) {
                allocation_avg.destroy();
                freeAllocationAvg = false;
            }
            if( MyDebug.LOG )
                Log.d(TAG, "release bitmapAvg");
            //bitmap_avg.recycle();
            //bitmapAvg = null;
            allocationAvg = allocationOut;
            first = false;
        }*/
            val allocationNew = Allocation.createFromBitmap(rs, bitmapNew)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after creating allocation_new from bitmap: " + (System.currentTimeMillis() - timeS)
            )

            // set allocations
            if (allocationOrig == null) {
                // allocationOrig should only be null for the first pair of images, which should be when we
                // have the avg image not in floating point format
                if (floatingPoint) {
                    throw RuntimeException("is in floating point mode, but no allocation_orig supplied")
                }
                if (MyDebug.LOG) Log.d(TAG, "create allocation_avg")
                allocationOrig = Allocation.createFromBitmap(rs, bitmapAvg)
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "allocation_orig: $allocationOrig")
            }
            processAvgScript?.set_bitmap_orig(allocationOrig)
            processAvgScript?.set_bitmap_new(allocationNew)

            // set offsets
            processAvgScript?.set_offset_x_new(offsetsX!![1])
            processAvgScript?.set_offset_y_new(offsetsY!![1])

            // set globals
            processAvgScript?.set_avg_factor(avgFactor)
            processAvgScript?.set_wiener_C(wienerC)
            processAvgScript?.set_wiener_C_cutoff(wienerCCutoff)

            /*final float maxWeight = 0.9375f;
        if( MyDebug.LOG ) {
            Log.d(TAG, "maxWeight: " + maxWeight);
        }
        processAvgScript?.setMaxWeight(maxWeight);*/
            if (MyDebug.LOG) Log.d(TAG, "call processAvgScript")
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time before processAvgScript: " + (System.currentTimeMillis() - timeS)
            )
            if (floatingPoint) processAvgScript?.forEach_avg_f(allocationAvg, allocationOut)
            else processAvgScript?.forEach_avg(allocationAvg, allocationOut)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after processAvgScript: " + (System.currentTimeMillis() - timeS)
            )

            /*if( allocationDiffs != null ) {
            allocation_diffs.destroy();
            allocationDiffs = null;
        }*/
            allocationNew.destroy()
            if (freeAllocationAvg) {
                allocationAvg!!.destroy()
            }
        }

        // N.B., we don't recycle bitmapAvg (if non-null), as it shares memory with allocationOrig that
        // we need to use when processing later iterations. Instead the first bitmap is recycled in
        // AvgData.destroy(). Also note that if we did recycle bitmapAvg, we get a native crash in
        // process_avg.rs when reading from bitmapOrig, but only on some devices (crash can be
        // reproduced on Android 11 with Android emulator, e.g., running testTakePhotoNR).
        /*if( bitmapAvg != null ) {
            if( MyDebug.LOG )
                Log.d(TAG, "release bitmapAvg");
            bitmap_avg.recycle();
            bitmapAvg = null;
        }*/
        if (bitmapNew != null) {
            if (MyDebug.LOG) Log.d(TAG, "release bitmap_new")
            bitmapNew.recycle()
            bitmapNew = null
        }

        if (MyDebug.LOG) Log.d(
            TAG,
            "### time for processAvgCore: " + (System.currentTimeMillis() - timeS)
        )
        return AvgData(
            allocationOut,
            pixelsRgbfOut,
            bitmapAvgAlign,
            allocationAvgAlign,
            bitmapAvg,
            allocationOrig
        )
    }

    /** Combines multiple images by averaging them.
     * @param bitmaps Input bitmaps. The resultant bitmap will be stored as the first bitmap on exit,
     * the other input bitmaps will be recycled.
     */
    @Throws(HDRProcessorException::class)
    fun processAvgMulti(
        bitmaps: List<Bitmap>,
        hdrAlpha: Float,
        nTiles: Int,
        cePreserveBlacks: Boolean
    ) {
        if (MyDebug.LOG) {
            Log.d(TAG, "processAvgMulti")
            Log.d(TAG, "hdr_alpha: $hdrAlpha")
        }
        val nBitmaps = bitmaps.size
        if (nBitmaps != 8) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "n_bitmaps should be 8, not $nBitmaps"
            )
            throw HDRProcessorException(HDRProcessorException.INVALID_N_IMAGES)
        }
        for (i in 1..<nBitmaps) {
            if (bitmaps[i].width != bitmaps[0].width ||
                bitmaps[i].height != bitmaps[0].height
            ) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "bitmaps not of same resolution")
                    for (j in 0..<nBitmaps) {
                        Log.e(
                            TAG,
                            "bitmaps " + j + " : " + bitmaps[j].width + " x " + bitmaps[j].height
                        )
                    }
                }
                throw HDRProcessorException(HDRProcessorException.UNEQUAL_SIZES)
            }
        }

        val timeS = System.currentTimeMillis()

        val width = bitmaps[0].width
        val height = bitmaps[0].height

        initRenderscript()
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after creating renderscript: " + (System.currentTimeMillis() - timeS)
        )
        // create allocations
        val allocation0 = Allocation.createFromBitmap(rs, bitmaps[0])
        val allocation1 = Allocation.createFromBitmap(rs, bitmaps[1])
        val allocation2 = Allocation.createFromBitmap(rs, bitmaps[2])
        val allocation3 = Allocation.createFromBitmap(rs, bitmaps[3])
        val allocation4 = Allocation.createFromBitmap(rs, bitmaps[4])
        val allocation5 = Allocation.createFromBitmap(rs, bitmaps[5])
        val allocation6 = Allocation.createFromBitmap(rs, bitmaps[6])
        val allocation7 = Allocation.createFromBitmap(rs, bitmaps[7])
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after creating allocations from bitmaps: " + (System.currentTimeMillis() - timeS)
        )

        // perform auto-alignment
        /*for(int i=1;i<bitmaps.size();i++) {
        {
            List<Bitmap> bitmaps2 = new ArrayList<>();
            bitmaps2.add(bitmaps.get(0));
            bitmaps2.add(bitmap.get(i));
            Allocation [] allocations = new Allocation[2];
            allocations[0] = allocationAvg;
            allocations[1] = allocationNew;
            BrightnessDetails brightnessDetails = autoAlignment(offsetsX, offsetsY, allocations, width, height, bitmaps, 0, true, null, true, timeS);
            int medianBrightness = brightnessDetails.medianBrightness;
            if( MyDebug.LOG ) {
                Log.d(TAG, "### time after autoAlignment: " + (System.currentTimeMillis() - timeS));
                Log.d(TAG, "medianBrightness: " + medianBrightness);
            }
        }*/

        // write new avg image

        // create RenderScript
        /*if( processAvgScript == null ) {
            processAvgScript = new ScriptC_process_avg(rs);
        }*/
        val processAvgScript: ScriptC_process_avg = ScriptC_process_avg(rs)

        // set allocations
        processAvgScript?.set_bitmap1(allocation1)
        processAvgScript?.set_bitmap2(allocation2)
        processAvgScript?.set_bitmap3(allocation3)
        processAvgScript?.set_bitmap4(allocation4)
        processAvgScript?.set_bitmap5(allocation5)
        processAvgScript?.set_bitmap6(allocation6)
        processAvgScript?.set_bitmap7(allocation7)

        // set offsets
        //processAvgScript?.set_offset_XNew(offsetsX[1]);
        //processAvgScript?.set_offset_YNew(offsetsY[1]);

        //hdrAlpha = 0.0f; // test

        // set globals
        if (MyDebug.LOG) Log.d(TAG, "call processAvgScript")
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time before processAvgScript: " + (System.currentTimeMillis() - timeS)
        )
        processAvgScript?.forEach_avg_multi(allocation0, allocation0)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after processAvgScript: " + (System.currentTimeMillis() - timeS)
        )

        run {
            if (MyDebug.LOG) Log.d(TAG, "release bitmaps")
            for (i in 1..<bitmaps.size) {
                bitmaps[i].recycle()
            }
        }

        if (hdrAlpha != 0.0f) {
            adjustHistogramRS(
                allocation0,
                allocation0,
                width,
                height,
                hdrAlpha,
                nTiles,
                cePreserveBlacks,
                timeS
            )
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after adjustHistogram: " + (System.currentTimeMillis() - timeS)
            )
        }

        allocation0.copyTo(bitmaps[0])

        if (MyDebug.LOG) Log.d(
            TAG,
            "### time for processAvgMulti: " + (System.currentTimeMillis() - timeS)
        )
    }

    fun autoAlignment(
        offsetsX: IntArray,
        offsetsY: IntArray,
        width: Int,
        height: Int,
        bitmaps: MutableList<Bitmap>,
        baseBitmap: Int,
        useMtb: Boolean,
        maxAlignScale: Int
    ) {
        if (MyDebug.LOG) Log.d(TAG, "autoAlignment")
        initRenderscript()
        var allocations: Array<Allocation?>? = null
        if (USE_RENDERSCRIPT) {
            allocations = arrayOfNulls(bitmaps.size)
            for (i in bitmaps.indices) {
                allocations[i] = Allocation.createFromBitmap(rs, bitmaps[i])
            }
        }

        autoAlignment(
            offsetsX,
            offsetsY,
            allocations,
            width,
            height,
            bitmaps as MutableList<Bitmap?>,
            baseBitmap,
            true,
            null,
            useMtb,
            1,
            false,
            maxAlignScale,
            width,
            height,
            0
        )

        var i = 0
        while (allocations != null && i < allocations.size) {
            if (allocations[i] != null) {
                allocations[i]!!.destroy()
                allocations[i] = null
            }
            i++
        }
        freeScripts()
    }

    internal data class BrightnessDetails(// median brightness value of the median image
        val medianBrightness: Int
    )

    /**
     *
     * @param bitmaps       Bitmaps to align.
     * @param baseBitmap   Index of bitmap in bitmaps that should be kept fixed; the other bitmaps
     * will be aligned relative to this.
     * @param assumeSorted If assumeSorted if false, and useMtb is true, this function will also
     * sort the allocations and bitmaps from darkest to brightest.
     * @param useMtb       Whether to align based on the median threshold bitmaps or not.
     * @param maxAlignScale If larger than 1, start from a larger start area.
     */
    private fun autoAlignment(
        offsetsX: IntArray,
        offsetsY: IntArray,
        allocations: Array<Allocation?>?,
        width: Int,
        height: Int,
        bitmaps: MutableList<Bitmap?>,
        baseBitmap: Int,
        assumeSorted: Boolean,
        sortCb: SortCallback?,
        useMtb: Boolean,
        minStepSize: Int,
        cropToCentre: Boolean,
        maxAlignScale: Int,
        fullWidth: Int,
        fullHeight: Int,
        timeS: Long
    ): BrightnessDetails {
        if (MyDebug.LOG) {
            Log.d(TAG, "autoAlignment")
            Log.d(TAG, "width: $width")
            Log.d(TAG, "height: $height")
            Log.d(TAG, "use_mtb: $useMtb")
            Log.d(TAG, "max_align_scale: $maxAlignScale")
            Log.d(TAG, "bitmaps: " + bitmaps.size)
            if (allocations != null) {
                Log.d(TAG, "allocations: " + allocations.size)
                for (allocation in allocations) {
                    Log.d(TAG, "    allocation:")
                    Log.d(TAG, "    element: " + allocation?.element)
                    Log.d(TAG, "    type X: " + allocation?.type?.x)
                    Log.d(TAG, "    type Y: " + allocation?.type?.y)
                }
            }
        }

        val nImages = bitmaps.size
        if (allocations != null && bitmaps.size != allocations.size) {
            throw RuntimeException("unequal bitmaps and allocations lengths")
        } else if (bitmaps.size != offsetsX.size) {
            throw RuntimeException("unequal bitmaps and offsets_x lengths")
        } else if (bitmaps.size != offsetsY.size) {
            throw RuntimeException("unequal bitmaps and offsets_y lengths")
        }

        // initialise
        for (i in 0..<nImages) {
            offsetsX[i] = 0
            offsetsY[i] = 0
        }

        // Testing shows that in practice we get good results by only aligning the centre quarter of the images. This gives better
        // performance, and uses less memory.
        // If copyToCentre is false, this has already been done by the caller.
        var mtbWidth = width
        var mtbHeight = height
        var mtbX = 0
        var mtbY = 0
        if (cropToCentre) {
            mtbWidth = width / 2
            mtbHeight = height / 2
            mtbX = mtbWidth / 2
            mtbY = mtbHeight / 2
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "mtb_x: $mtbX")
            Log.d(TAG, "mtb_y: $mtbY")
            Log.d(TAG, "mtb_width: $mtbWidth")
            Log.d(TAG, "mtb_height: $mtbHeight")
        }

        var luminanceInfos: Array<LuminanceInfo?>? = null
        if (useMtb) {
            luminanceInfos = arrayOfNulls(nImages)
            for (i in 0..<nImages) {
                luminanceInfos[i] =
                    computeMedianLuminance(bitmaps[i]!!, mtbX, mtbY, mtbWidth, mtbHeight)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    i.toString() + ": median_value: " + luminanceInfos[i]!!.medianValue
                )
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "time after computeMedianLuminance: " + (System.currentTimeMillis() - timeS)
            )
        }

        if (!assumeSorted && useMtb) {
            if (MyDebug.LOG) Log.d(TAG, "sort bitmaps")
            class BitmapInfo(
                luminanceInfo: LuminanceInfo?,
                val bitmap: Bitmap,
                val allocation: Allocation, val index: Int
            ) {
                val luminanceInfo: LuminanceInfo = luminanceInfo!!
            }

            val bitmapInfos: MutableList<BitmapInfo> = ArrayList(bitmaps.size)
            for (i in bitmaps.indices) {
                val bitmapInfo =
                    BitmapInfo(luminanceInfos!![i], bitmaps[i]!!, allocations!![i]!!, i)
                bitmapInfos.add(bitmapInfo)
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "before sorting:")
                for (i in 0..<nImages) {
                    Log.d(TAG, "    " + i + ": " + luminanceInfos!![i])
                }
            }
            Collections.sort(
                bitmapInfos,
                Comparator<BitmapInfo> { o1, o2 -> // important to use the code in LuminanceInfo.compareTo(), as that's also tested via the unit test
                    // sortLuminanceInfo()
                    o1.luminanceInfo.compareTo(o2.luminanceInfo)
                })
            bitmaps.clear()
            for (i in bitmapInfos.indices) {
                bitmaps.add(bitmapInfos[i].bitmap)
                luminanceInfos!![i] = bitmapInfos[i].luminanceInfo
                allocations!![i] = bitmapInfos[i].allocation
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "after sorting:")
                for (i in 0..<nImages) {
                    Log.d(TAG, "    " + i + ": " + luminanceInfos!![i])
                }
            }
            if (sortCb != null) {
                val sortOrder: MutableList<Int> = ArrayList()
                for (i in bitmapInfos.indices) {
                    sortOrder.add(bitmapInfos[i].index)
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "sort_order: $sortOrder"
                )
                sortCb.sortOrder(sortOrder)
            }
        }

        /*{
            // test
            for(int i = 0; i < luminanceInfos.length-1; i++) {
                if( luminanceInfos[i].compareTo(luminanceInfos[i+1]) == 0 ) {
                    throw new RuntimeException("this: " + luminanceInfos[i] + " , that: " + luminanceInfos[i+1]);
                }
            }
        }*/
        var medianBrightness = -1
        if (useMtb) {
            medianBrightness = luminanceInfos!![baseBitmap]!!.medianValue
            if (MyDebug.LOG) Log.d(
                TAG,
                "median_brightness: $medianBrightness"
            )
        }

        var mtbBitmaps: Array<Bitmap?>? = null // when not using renderscript
        var mtbAllocations: Array<Allocation?>? = null // when using renderscript

        if (!USE_RENDERSCRIPT) {
            mtbBitmaps = arrayOfNulls(nImages)
        } else {
            mtbAllocations = arrayOfNulls(nImages) // when using renderscript
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after creating mtb_allocations: " + (System.currentTimeMillis() - timeS)
            )

            // create RenderScript
            if (createMTBScript == null) {
                createMTBScript = ScriptC_create_mtb(rs)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after creating createMTBScript: " + (System.currentTimeMillis() - timeS)
                )
            }
            //ScriptC_create_mtb createMTBScript = new ScriptC_create_mtb(rs);
        }

        for (i in 0..<nImages) {
            var medianValue = -1
            if (useMtb) {
                medianValue = luminanceInfos!![i]!!.medianValue
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "$i: median_value: $medianValue"
                )

                /*if( medianValue < 16 ) {
                    // needed for testHDR2, testHDR28
                    if( MyDebug.LOG )
                        Log.d(TAG, "image too dark to do alignment");
                    if( mtbBitmaps != null )
                        mtbBitmaps[i] = null;
                    if( mtbAllocations != null )
                        mtbAllocations[i] = null;

                    continue;
                }*/
            }

            if (useMtb && luminanceInfos!![i]!!.noisy) {
                if (MyDebug.LOG) Log.d(TAG, "unable to compute median luminance safely")
                if (mtbBitmaps != null) mtbBitmaps[i] = null
                if (mtbAllocations != null) mtbAllocations[i] = null
                continue
            }

            // avoid too low/high medianValues, otherwise we'll detect dark or light pixels as "noisy" - needed for testHDR61
            val minDiffC = 4 // should be same value as in create_mtb.rs/createMtb()
            /*if( medianValue < minDiffC+1 || medianValue > 255-(minDiffC+1) ) {
                throw new RuntimeException("image " + i + " has medianValue: " + medianValue); // test
            }*/
            medianValue = max(medianValue.toDouble(), (minDiffC + 1).toDouble()).toInt()
            medianValue = min(medianValue.toDouble(), (255 - (minDiffC + 1)).toDouble()).toInt()
            if (MyDebug.LOG) Log.d(
                TAG,
                "$i: median_value is now: $medianValue"
            )

            if (!USE_RENDERSCRIPT) {
                val outputMtbBitmap =
                    Bitmap.createBitmap(mtbWidth, mtbHeight, Bitmap.Config.ALPHA_8)
                val function: JavaImageFunctionsHDR.CreateMTBApplyFunction =
                    JavaImageFunctionsHDR.CreateMTBApplyFunction(
                        useMtb,
                        medianValue
                    )
                JavaImageProcessing.applyFunction(
                    function,
                    bitmaps[i],
                    outputMtbBitmap,
                    mtbX,
                    mtbY,
                    mtbX + mtbWidth,
                    mtbY + mtbHeight,
                    0,
                    0
                )
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after CreateMTBApplyFunction: " + (System.currentTimeMillis() - timeS)
                )
                /*mtbAllocations[i] = Allocation.createTyped(rs, Type.createXY(rs, Element.A_8(rs), mtbWidth, mtbHeight));
                mtbAllocations[i].copyFrom(outputMtbBitmap);
                output_mtb_bitmap.recycle();
                if( MyDebug.LOG )
                    Log.d(TAG, "### time after copying to mtbAllocations: " + (System.currentTimeMillis() - timeS));*/
                mtbBitmaps!![i] = outputMtbBitmap
            } else {
                mtbAllocations!![i] = Allocation.createTyped(
                    rs,
                    Type.createXY(rs, Element.U8(rs), mtbWidth, mtbHeight)
                )

                // set parameters
                if (useMtb) createMTBScript?.set_median_value(medianValue)
                createMTBScript?.set_start_x(mtbX)
                createMTBScript?.set_start_y(mtbY)
                createMTBScript?.set_out_bitmap(mtbAllocations[i])

                if (MyDebug.LOG) Log.d(TAG, "call createMTBScript")
                val launchOptions = LaunchOptions()
                //launch_options.setX((int)(width*0.25), (int)(width*0.75));
                //launch_options.setY((int)(height*0.25), (int)(height*0.75));
                //createMTBScript.forEach_create_mtb(allocations[i], mtbAllocations[i], launchOptions);
                launchOptions.setX(mtbX, mtbX + mtbWidth)
                launchOptions.setY(mtbY, mtbY + mtbHeight)
                if (useMtb) createMTBScript?.forEach_create_mtb(allocations!![i], launchOptions)
                else {
                    createMTBScript?.forEach_create_greyscale(allocations!![i], launchOptions)
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "time after createMTBScript: " + (System.currentTimeMillis() - timeS)
                )

                /*if( MyDebug.LOG ) {
                // debugging
                byte [] mtbBytes = new byte[mtbWidth*mtbHeight];
                mtbAllocations[i].copyTo(mtbBytes);
                int [] pixels = new int[mtbWidth*mtbHeight];
                for(int j=0;j<mtbWidth*mtbHeight;j++) {
                    int b = mtbBytes[j];
                    if( b < 0 )
                        b += 255;
                    pixels[j] = Color.argb(255, b, b, b);
                }
                Bitmap mtbBitmap = Bitmap.createBitmap(pixels, mtbWidth, mtbHeight, Bitmap.Config.ARGB_8888);
                File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/mtbBitmap" + i + ".jpg");
                try {
                    OutputStream outputStream = new FileOutputStream(file);
                    mtb_bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream);
                    outputStream.close();
                    MainActivity mActivity = (MainActivity) context;
                    mActivity.storageUtils.broadcastFile(file, true, false, true);
                }
                catch(IOException e) {
                    e.printStackTrace();
                }
                mtb_bitmap.recycle();
            }*/
            }
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after all createMTBScript: " + (System.currentTimeMillis() - timeS)
        )

        // The initial stepSize N should be a power of 2; the maximum offset we can achieve by the algorithm is N-1.
        // For pictures resolution 4160x3120, this gives maxIdealSize 27, and initialStepSize 32.
        // On tests testHDR1 to testHDR35, the max required offset was 24 pixels (for testHDR33) even when using
        // initalStepSize of 64.
        // Note, there isn't really a performance cost in allowing higher initial step sizes (as larger sizes have less
        // sampling - since we sample every stepSize pixels - though there might be some overhead for every extra call
        // to renderscript that we do). But high step sizes have a risk of producing really bad results if we were
        // to misidentify cases as needing a large offset.
        val maxDim = max(
            fullWidth.toDouble(),
            fullHeight.toDouble()
        ).toInt() // n.b., use the full width and height here, not the mtbWidth, height
        //int maxIdealSize = maxDim / (wider ? 75 : 150);
        val maxIdealSize = (maxAlignScale * maxDim) / 150
        var initialStepSize = 1
        while (initialStepSize < maxIdealSize) {
            initialStepSize *= 2
        }
        //initialStepSize = 64;
        if (MyDebug.LOG) {
            Log.d(TAG, "max_dim: $maxDim")
            Log.d(TAG, "max_ideal_size: $maxIdealSize")
            Log.d(TAG, "initial_step_size: $initialStepSize")
        }

        if (mtbBitmaps != null && mtbBitmaps[baseBitmap] == null) {
            if (MyDebug.LOG) Log.d(TAG, "base image not suitable for image alignment")
            for (i in mtbBitmaps.indices) {
                if (mtbBitmaps[i] != null) {
                    mtbBitmaps[i]?.recycle()
                    mtbBitmaps[i] = null
                }
            }
            return BrightnessDetails(medianBrightness)
        }
        if (mtbAllocations != null && mtbAllocations[baseBitmap] == null) {
            if (MyDebug.LOG) Log.d(TAG, "base image not suitable for image alignment")
            for (i in mtbAllocations.indices) {
                if (mtbAllocations[i] != null) {
                    mtbAllocations[i]?.destroy()
                    mtbAllocations[i] = null
                }
            }
            return BrightnessDetails(medianBrightness)
        }

        if (USE_RENDERSCRIPT) {
            // create RenderScript
            if (alignMTBScript == null) {
                alignMTBScript = ScriptC_align_mtb(rs)
            }

            //ScriptC_align_mtb alignMTBScript = new ScriptC_align_mtb(rs);

            // set parameters
            alignMTBScript?.set_bitmap0(mtbAllocations!![baseBitmap])
            // bitmap1 set below
        }

        for (i in 0..<nImages) {
            if (i == baseBitmap) {
                // don't need to align the "base" reference image
                continue
            }
            if (mtbBitmaps != null && mtbBitmaps[i] == null) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "image $i not suitable for image alignment"
                )
                continue
            }
            if (mtbAllocations != null && mtbAllocations[i] == null) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "image $i not suitable for image alignment"
                )
                continue
            }

            if (USE_RENDERSCRIPT) {
                alignMTBScript?.set_bitmap1(mtbAllocations!![i])
            }

            //final int pixelStep = useMtb ? 1 : 4;
            val pixelStep = 1
            var stepSize = initialStepSize
            while (stepSize > minStepSize) {
                stepSize /= 2
                var pixelStepSize = stepSize * pixelStep
                if (pixelStepSize > mtbWidth || pixelStepSize > mtbHeight) pixelStepSize =
                    stepSize

                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "call alignMTBScript for image: $i"
                    )
                    Log.d(
                        TAG,
                        "    versus base image: $baseBitmap"
                    )
                    Log.d(TAG, "step_size: $stepSize")
                    Log.d(
                        TAG,
                        "pixel_step_size: $pixelStepSize"
                    )
                }

                val usePyramid = false

                //final boolean usePyramid = true;
                val stopX: Int
                val stopY: Int
                if (usePyramid) {
                    stopX = mtbWidth
                    stopY = mtbHeight
                } else {
                    // see note inside align_mtb.rs/alignMtb() for why we sample over a subset of the image
                    stopX = mtbWidth / pixelStepSize
                    stopY = mtbHeight / pixelStepSize
                }
                if (MyDebug.LOG) {
                    Log.d(TAG, "stop_x: $stopX")
                    Log.d(TAG, "stop_y: $stopY")
                }

                val errors: IntArray

                if (!USE_RENDERSCRIPT) {
                    val function: JavaImageFunctionsHDR.AlignMTBApplyFunction =
                        JavaImageFunctionsHDR.AlignMTBApplyFunction(
                            useMtb,
                            mtbBitmaps!![baseBitmap]!!,
                            mtbBitmaps[i]!!, offsetsX[i], offsetsY[i], pixelStepSize
                        )
                    JavaImageProcessing.applyFunction(function, null, null, 0, 0, stopX, stopY)
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "### time after AlignMTBApplyFunction: " + (System.currentTimeMillis() - timeS)
                    )
                    errors = function.getErrors()
                } else {
                    /*if( usePyramid ) {
                                       // downscale by stepSize
                                       Allocation [] scaledAllocations = new Allocation[2];
                                       for(int j=0;j<2;j++) {
                                           int scaledWidth = mtbWidth/stepSize;
                                           int scaledHeight = mtbHeight/stepSize;
                                           if( MyDebug.LOG ) {
                                               Log.d(TAG, "create scaled image: " + j);
                                               Log.d(TAG, "    scaledWidth: " + scaledWidth);
                                               Log.d(TAG, "    scaledHeight: " + scaledHeight);
                                           }
                                           Allocation allocationToScale = mtbAllocations[(j==0) ? baseBitmap : i];
                                           Type type = Type.createXY(rs, allocation_to_scale.getElement(), scaledWidth, scaledHeight);
                                           scaledAllocations[j] = Allocation.createTyped(rs, type);
                                           ScriptIntrinsicResize theIntrinsic = ScriptIntrinsicResize.create(rs);
                                           theIntrinsic.setInput(allocationToScale);
                                           theIntrinsic.forEach_bicubic(scaledAllocations[j]);
                                       }
                                       alignMTBScript.set_bitmap0(scaledAllocations[0]);
                                       alignMTBScript.set_bitmap1(scaledAllocations[1]);
                                       int offX = offsetsX[i]/stepSize;
                                       int offY = offsetsY[i]/stepSize;
                                       if( MyDebug.LOG ) {
                                           Log.d(TAG, "offX: " + offX);
                                           Log.d(TAG, "offY: " + offY);
                                       }
                                       alignMTBScript.setOffX( offX );
                                       alignMTBScript.setOffY( offY );
                                       alignMTBScript.setStepSize( 1 );
                                   }
                                   else*/

                    run<Unit> {
                        alignMTBScript?.set_off_x(offsetsX[i])
                        alignMTBScript?.set_off_y(offsetsY[i])
                        alignMTBScript?.set_step_size(pixelStepSize)
                    }

                    val errorsAllocation = Allocation.createSized(rs, Element.I32(rs), 9)
                    alignMTBScript?.bind_errors(errorsAllocation)
                    alignMTBScript?.invoke_init_errors()

                    val launchOptions = LaunchOptions()
                    //launch_options.setX((int)(stopX*0.25), (int)(stopX*0.75));
                    //launch_options.setY((int)(stopY*0.25), (int)(stopY*0.75));
                    launchOptions.setX(0, stopX)
                    launchOptions.setY(0, stopY)

                    val thisTimeS = System.currentTimeMillis()
                    if (useMtb) alignMTBScript?.forEach_align_mtb(
                        mtbAllocations!![baseBitmap],
                        launchOptions
                    )
                    else alignMTBScript?.forEach_align(
                        mtbAllocations!![baseBitmap],
                        launchOptions
                    )
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "time for alignMTBScript: " + (System.currentTimeMillis() - thisTimeS)
                        )
                        Log.d(
                            TAG,
                            "### time after alignMTBScript: " + (System.currentTimeMillis() - timeS)
                        )
                    }
                    errors = IntArray(9)
                    errorsAllocation.copyTo(errors)
                    errorsAllocation.destroy()
                }

                var bestError = -1
                var bestId = -1
                for (j in 0..8) {
                    val thisError = errors[j]
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "    errors[$j]: $thisError"
                    )
                    if (bestId == -1 || thisError < bestError) {
                        bestError = thisError
                        bestId = j
                    }
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "    best_id $bestId error: $bestError"
                )
                if (bestError >= 2000000000) {
                    Log.e(TAG, "    auto-alignment failed due to overflow")
                    // hitting overflow means behaviour will be unstable under SMP, and auto-alignment won't be reliable anyway
                    bestId = 4 // default to centre
                    if (isTest) {
                        throw RuntimeException()
                    }
                }
                /*if( bestId != 4 ) {
                    int thisOffX = bestId % 3;
                    int thisOffY = bestId/3;
                    thisOffX--;
                    thisOffY--;
                    for(int j=0;j<9;j++) {
                        int thatOffX = j % 3;
                        int thatOffY = j/3;
                        thatOffX--;
                        thatOffY--;
                        if( thisOffX * thatOffX == -1 || thisOffY * thatOffY == -1 ) {
                            float diff = ((float)(bestError - errors[j]))/(float)errors[j];
                            if( MyDebug.LOG )
                                Log.d(TAG, "    opposite errors[" + j + "] diff: " + diff);
                            if( Math.abs(diff) <= 0.02f ) {
                                if( MyDebug.LOG )
                                    Log.d(TAG, "    reject auto-alignment");
                                bestId = 4;
                                break;
                            }
                        }
                    }
                }*/
                if (bestId != -1) {
                    var thisOffX = bestId % 3
                    var thisOffY = bestId / 3
                    thisOffX--
                    thisOffY--
                    if (MyDebug.LOG) {
                        Log.d(TAG, "this_off_x: $thisOffX")
                        Log.d(TAG, "this_off_y: $thisOffY")
                    }
                    offsetsX[i] += thisOffX * stepSize
                    offsetsY[i] += thisOffY * stepSize
                    if (MyDebug.LOG) {
                        Log.d(TAG, "offsets_x is now: " + offsetsX[i])
                        Log.d(TAG, "offsets_y is now: " + offsetsY[i])
                    }
                    /*if( wider && stepSize == initialStepSize/2 && (thisOffX != 0 || thisOffY != 0 ) ) {
                        throw new RuntimeException(); // test
                    }*/
                }
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "resultant offsets for image: $i")
                Log.d(TAG, "resultant offsets_x: " + offsetsX[i])
                Log.d(TAG, "resultant offsets_y: " + offsetsY[i])
            }
        }

        /*for(int i=0;i<nImages;i++) {
            offsetsX[i] = 0;
            offsetsY[i] = 0;
        }*/
        run {
            var i = 0
            while (mtbBitmaps != null && i < mtbBitmaps.size) {
                if (mtbBitmaps[i] != null) {
                    mtbBitmaps[i]!!.recycle()
                    mtbBitmaps[i] = null
                }
                i++
            }
        }
        var i = 0
        while (mtbAllocations != null && i < mtbAllocations.size) {
            if (mtbAllocations[i] != null) {
                mtbAllocations[i]!!.destroy()
                mtbAllocations[i] = null
            }
            i++
        }
        return BrightnessDetails(medianBrightness)
    }

    data class LuminanceInfo(
        val minValue: Int,
        val medianValue: Int,
        val hiValue: Int,
        val noisy: Boolean
    ) :
        Comparable<LuminanceInfo> {
        override fun toString(): String {
            return "min: $minValue , median: $medianValue , hi: $hiValue , noisy: $noisy"
        }

        override fun compareTo(other: LuminanceInfo): Int {
            var value = this.medianValue - other.medianValue
            if (value == 0) {
                // fall back to using minValue
                value = this.minValue - other.minValue
            }
            if (value == 0) {
                // fall back to using hiValue
                value = this.hiValue - other.hiValue
            }
            return value
        }
    }

    private fun computeMedianLuminance(
        bitmap: Bitmap,
        mtbX: Int,
        mtbY: Int,
        mtbWidth: Int,
        mtbHeight: Int
    ): LuminanceInfo {
        if (MyDebug.LOG) {
            Log.d(TAG, "computeMedianLuminance")
            Log.d(TAG, "mtb_x: $mtbX")
            Log.d(TAG, "mtb_y: $mtbY")
            Log.d(TAG, "mtb_width: $mtbWidth")
            Log.d(TAG, "mtb_height: $mtbHeight")
        }
        val nSamplesC = 100
        val nWSamples = sqrt(nSamplesC.toDouble()).toInt()
        val nHSamples = nSamplesC / nWSamples

        val histo = IntArray(256)
        for (i in 0..255) histo[i] = 0
        var total = 0
        //double sumLogLuminance = 0.0;
        for (y in 0..<nHSamples) {
            val alpha = (y.toDouble() + 1.0) / (nHSamples.toDouble() + 1.0)
            //int yCoord = (int) (alpha * bitmap.getHeight());
            val yCoord = mtbY + (alpha * mtbHeight).toInt()
            for (x in 0..<nWSamples) {
                val beta = (x.toDouble() + 1.0) / (nWSamples.toDouble() + 1.0)
                //int xCoord = (int) (beta * bitmap.getWidth());
                val xCoord = mtbX + (beta * mtbWidth).toInt()
                /*if( MyDebug.LOG )
                    Log.d(TAG, "sample value from " + xCoord + " , " + yCoord);*/
                val color = bitmap[xCoord, yCoord]
                val r = (color and 0xFF0000) shr 16
                val g = (color and 0xFF00) shr 8
                val b = (color and 0xFF)
                var luminance = max(r.toDouble(), g.toDouble()).toInt()
                luminance = max(luminance.toDouble(), b.toDouble()).toInt()
                histo[luminance]++
                //sumLogLuminance += Math.log(luminance+1.0); // add 1 so we don't take log of 0...;
                total++
            }
        }
        /*float avgLuminance = (float)(Math.exp( sumLogLuminance / total ));
        if( MyDebug.LOG )
            Log.d(TAG, "avgLuminance: " + avgLuminance);*/
        val middle = total / 2
        var count = 0
        var noisy = false
        var minValue = -1
        var hiValue = -1
        // first count backwards to get hiValue
        for (i in 255 downTo 0) {
            /*if( histo[i] > 0 ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "max luminance " + i);
                maxValue = i;
                break;
            }*/
            count += histo[i]
            if (count >= total / 10) {
                if (MyDebug.LOG) Log.d(TAG, "hi luminance $i")
                hiValue = i
                break
            }
        }

        // then count forwards to get min and median values
        count = 0
        for (i in 0..255) {
            count += histo[i]
            if (minValue == -1 && histo[i] > 0) {
                if (MyDebug.LOG) Log.d(TAG, "min luminance $i")
                minValue = i
            }
            if (count >= middle) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "median luminance $i"
                )
                val noiseThreshold = 4
                var nBelow = 0
                var nAbove = 0
                for (j in 0..i - noiseThreshold) {
                    nBelow += histo[j]
                }
                var j = 0
                while (j <= i + noiseThreshold && j < 256) {
                    nAbove += histo[j]
                    j++
                }
                val fracBelow = nBelow / total.toDouble()
                if (MyDebug.LOG) {
                    val fracAbove = 1.0 - nAbove / total.toDouble()
                    Log.d(TAG, "count: $count")
                    Log.d(TAG, "n_below: $nBelow")
                    Log.d(TAG, "n_above: $nAbove")
                    Log.d(TAG, "frac_below: $fracBelow")
                    Log.d(TAG, "frac_above: $fracAbove")
                }
                if (fracBelow < 0.2) {
                    // needed for testHDR2, testHDR28
                    // note that we don't exclude cases where fracAbove is too small, as this could be an overexposed image - see testHDR31
                    if (MyDebug.LOG) Log.d(TAG, "too dark/noisy")
                    noisy = true
                }
                return LuminanceInfo(minValue, i, hiValue, noisy)
            }
        }
        Log.e(TAG, "computeMedianLuminance failed")
        return LuminanceInfo(minValue, 127, hiValue, true)
    }

    /** Clips the histogram, for Contrast Limited AHE algorithm.
     * @param histogram Histogram to modify (length 256).
     * @param tempCHistogram Temporary workspace (length 256).
     * @param subWidth Width of the region being processed.
     * @param subHeight Height of the region being processed.
     */
    fun clipHistogram(
        histogram: IntArray,
        tempCHistogram: IntArray,
        subWidth: Int,
        subHeight: Int,
        cePreserveBlacks: Boolean
    ) {
        val nPixels = subWidth * subHeight
        var clipLimit = (5 * nPixels) / 256
        /*if( MyDebug.LOG ) {
                        Log.d(TAG, "clipLimit: " + clipLimit);
                        Log.d(TAG, "    relative clip limit: " + clipLimit*256.0f/nPixels);
                    }*/
        run {
            // find real clip limit
            var bottom = 0
            var top = clipLimit
            while (top - bottom > 1) {
                val middle = (top + bottom) / 2
                var sum = 0
                for (x in 0..255) {
                    if (histogram[x] > middle) {
                        sum += (histogram[x] - clipLimit)
                    }
                }
                if (sum > (clipLimit - middle) * 256) top = middle
                else bottom = middle
            }
            clipLimit = (top + bottom) / 2
        }
        var nClipped = 0
        for (x in 0..255) {
            if (histogram[x] > clipLimit) {
                /*if( MyDebug.LOG ) {
                                Log.d(TAG, "    " + x + " : " + histogram[x] + " : " + (histogram[x]*256.0f/nPixels));
                            }*/
                nClipped += (histogram[x] - clipLimit)
                histogram[x] = clipLimit
            }
        }
        val nClippedPerBucket = nClipped / 256
        /*if( MyDebug.LOG ) {
                            Log.d(TAG, "nClipped: " + nClipped);
                            Log.d(TAG, "nClippedPerBucket: " + nClippedPerBucket);
                        }*/
        for (x in 0..255) {
            histogram[x] += nClippedPerBucket
        }

        if (cePreserveBlacks) {
            // This helps tests such as testHDR52, testHDR57, testAvg26, testAvg30
            // The basic idea is that we want to avoid making darker pixels darker (by too
            // much). We do this by adjusting the histogram:
            // * We can set a minimum value of each histogram value. E.g., if we set all
            //   pixels up to a certain brightness to a value equal to nPixels/256, then
            //   we prevent those pixels from being made darker. In practice, we choose
            //   a tapered minimum, starting at (nPixels/256) for black pixels, linearly
            //   interpolating to no minimum at brightness 128 (darkThresholdC).
            // * For any adjusted value of the histogram, we redistribute, by reducing
            //   the histogram values of brighter pixels with values larger than (nPixels/256),
            //   reducing them to a minimum of (nPixels/256).
            // * Lastly, we only modify a given histogram value if pixels of that brightness
            //   would be made darker by the CLAHE algorithm. We can do this by looking at
            //   the cumulative histogram (as computed before modifying any values).
            /*if( MyDebug.LOG ) {
                            for(int x=0;x<256;x++) {
                                Log.d(TAG, "pre-brighten histogram[" + x + "] = " + histogram[x]);
                            }
                        }*/

            tempCHistogram[0] = histogram[0]
            for (x in 1..255) {
                tempCHistogram[x] = tempCHistogram[x - 1] + histogram[x]
            }

            // avoid making pixels too dark
            val equalLimit = nPixels / 256
            if (MyDebug.LOG) Log.d(
                TAG,
                "equal_limit: $equalLimit"
            )
            //final int darkThresholdC = 64;
            val darkThresholdC = 128
            //final int darkThresholdC = 256;
            for (x in 0..<darkThresholdC) {
                val cEqualLimit = equalLimit * (x + 1)
                if (tempCHistogram[x] >= cEqualLimit) {
                    continue
                }
                val alpha = 1.0f - (x.toFloat()) / (darkThresholdC.toFloat())
                //float alpha = 1.0f - ((float)x)/256.0f;
                val limit = (alpha * equalLimit).toInt()
                //int limit = equalLimit;
                /*if( MyDebug.LOG )
                    Log.d(TAG, "x: " + x + " ; limit: " + limit);*/
                /*histogram[x] = Math.max(histogram[x], limit);
                            if( MyDebug.LOG )
                                Log.d(TAG, "    histogram pulled up to: "  + histogram[x]);*/
                if (histogram[x] < limit) {
                    // top up by redistributing later values
                    var y = x + 1
                    while (y < 256 && histogram[x] < limit) {
                        if (histogram[y] > equalLimit) {
                            var move = histogram[y] - equalLimit
                            move = min(move.toDouble(), (limit - histogram[x]).toDouble()).toInt()
                            histogram[x] += move
                            histogram[y] -= move
                        }
                        y++
                    }
                    /*if( MyDebug.LOG )
                        Log.d(TAG, "    histogram pulled up to: "  + histogram[x]);*/
                    /*if( tempCHistogram[x] >= cEqualLimit )
                                    throw new RuntimeException(); // test*/
                }
            }
        }
    }

    fun adjustHistogram(
        bitmapIn: Bitmap?,
        bitmapOut: Bitmap?,
        width: Int,
        height: Int,
        hdrAlpha: Float,
        nTiles: Int,
        cePreserveBlacks: Boolean,
        timeS: Long
    ) {
        if (MyDebug.LOG) Log.d(TAG, "adjustHistogram [bitmap]")

        //final boolean adjustHistogramLocal = false;
        val adjustHistogramLocal = true

        if (adjustHistogramLocal) {
            // Contrast Limited Adaptive Histogram Equalisation (CLAHE)
            // Note we don't fully equalise the histogram, rather the resultant image is the mid-point of the non-equalised and fully-equalised images
            // See https://en.wikipedia.org/wiki/Adaptive_histogram_equalization#Contrast_Limited_AHE
            // Also see "Adaptive Histogram Equalization and its Variations" ( http://www.cs.unc.edu/Research/MIDAG/pubs/papers/Adaptive%20Histogram%20Equalization%20and%20Its%20Variations.pdf ),
            // Pizer, Amburn, Austin, Cromartie, Geselowitz, Greer, ter Haar Romeny, Zimmerman, Zuiderveld (1987).
            // Also note that if cePreserveBlacks is true, we apply a modification to this algorithm, see below.

            if (MyDebug.LOG) Log.d(
                TAG,
                "time before creating histograms: " + (System.currentTimeMillis() - timeS)
            )

            // create histograms

            //final int nTilesC = 8;
            //final int nTilesC = 4;
            //final int nTilesC = 1;
            val cHistogram = IntArray(nTiles * nTiles * 256)
            val tempCHistogram = IntArray(256)
            for (i in 0..<nTiles) {
                val a0 = (i.toDouble()) / nTiles.toDouble()
                val a1 = (i.toDouble() + 1.0) / nTiles.toDouble()
                val startX = (a0 * width).toInt()
                val stopX = (a1 * width).toInt()
                if (stopX == startX) continue
                for (j in 0..<nTiles) {
                    val b0 = (j.toDouble()) / nTiles.toDouble()
                    val b1 = (j.toDouble() + 1.0) / nTiles.toDouble()
                    val startY = (b0 * height).toInt()
                    val stopY = (b1 * height).toInt()
                    if (stopY == startY) continue

                    /*if( MyDebug.LOG )
                            Log.d(TAG, i + " , " + j + " : " + startX + " , " + startY + " to " + stopX + " , " + stopY);*/

                    // We compute a histogram based on the max RGB value, so this matches with the scaling we do in histogram_adjust.rs.
                    // This improves the look of the grass in testHDR24, testHDR27.
                    /*int [] pixels = new int[(stopX-startX)*(stopY-startY)];
                    bitmap_in.getPixels(pixels, 0, stopX-startX, startX, startY, stopX-startX, stopY-startY);
                    int [] histogram = computeHistogram(pixels);*/
                    val function: JavaImageFunctionsHDR.ComputeHistogramApplyFunction =
                        JavaImageFunctionsHDR.ComputeHistogramApplyFunction(
                            JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_VALUE
                        )
                    JavaImageProcessing.applyFunction(
                        function,
                        bitmapIn,
                        null,
                        startX,
                        startY,
                        stopX,
                        stopY
                    )
                    val histogram: IntArray = function.histogram

                    clipHistogram(
                        histogram,
                        tempCHistogram,
                        (stopX - startX),
                        (stopY - startY),
                        cePreserveBlacks
                    )

                    // compute cumulative histogram
                    val histogramOffset = 256 * (i * nTiles + j)
                    cHistogram[histogramOffset] = histogram[0]
                    for (x in 1..255) {
                        cHistogram[histogramOffset + x] =
                            cHistogram[histogramOffset + x - 1] + histogram[x]
                    }
                    /*if( MyDebug.LOG ) {
                        for(int x=0;x<256;x++) {
                            Log.d(TAG, "histogram[" + x + "] = " + histogram[x] + " cumulative: " + cHistogram[histogramOffset+x]);
                        }
                    }*/
                }
            }

            if (MyDebug.LOG) Log.d(
                TAG,
                "adjustHistogram: time after creating histograms: " + (System.currentTimeMillis() - timeS)
            )

            val function: JavaImageFunctionsHDR.AdjustHistogramApplyFunction =
                JavaImageFunctionsHDR.AdjustHistogramApplyFunction(
                    hdrAlpha,
                    nTiles,
                    width,
                    height,
                    cHistogram
                )
            JavaImageProcessing.applyFunction(function, bitmapIn, bitmapOut, 0, 0, width, height)
            if (MyDebug.LOG) Log.d(
                TAG,
                "time after adjusting histogram: " + (System.currentTimeMillis() - timeS)
            )
        }
    }

    fun adjustHistogramRS(
        allocationIn: Allocation?,
        allocationOut: Allocation?,
        width: Int,
        height: Int,
        hdrAlpha: Float,
        nTiles: Int,
        cePreserveBlacks: Boolean,
        timeS: Long
    ) {
        if (MyDebug.LOG) Log.d(TAG, "adjustHistogram [renderscript]")

        //final boolean adjustHistogramLocal = false;
        val adjustHistogramLocal = true

        if (adjustHistogramLocal) {
            // Contrast Limited Adaptive Histogram Equalisation (CLAHE)
            // Note we don't fully equalise the histogram, rather the resultant image is the mid-point of the non-equalised and fully-equalised images
            // See https://en.wikipedia.org/wiki/Adaptive_histogram_equalization#Contrast_Limited_AHE
            // Also see "Adaptive Histogram Equalization and its Variations" ( http://www.cs.unc.edu/Research/MIDAG/pubs/papers/Adaptive%20Histogram%20Equalization%20and%20Its%20Variations.pdf ),
            // Pizer, Amburn, Austin, Cromartie, Geselowitz, Greer, ter Haar Romeny, Zimmerman, Zuiderveld (1987).
            // Also note that if cePreserveBlacks is true, we apply a modification to this algorithm, see below.

            if (MyDebug.LOG) Log.d(
                TAG,
                "time before creating histograms: " + (System.currentTimeMillis() - timeS)
            )
            // create histograms
            /*if( histogramScript == null ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "create histogramScript");
                histogramScript = new ScriptC_histogram_compute(rs);
            }*/
            val histogramAllocation = Allocation.createSized(rs, Element.I32(rs), 256)
            if (MyDebug.LOG) Log.d(TAG, "create histogramScript")
            val histogramScript = ScriptC_histogram_compute(rs)
            if (MyDebug.LOG) Log.d(TAG, "bind histogram allocation")
            histogramScript.bind_histogram(histogramAllocation)

            //final int nTilesC = 8;
            //final int nTilesC = 4;
            //final int nTilesC = 1;
            val cHistogram = IntArray(nTiles * nTiles * 256)
            val tempCHistogram = IntArray(256)
            for (i in 0..<nTiles) {
                val a0 = (i.toDouble()) / nTiles.toDouble()
                val a1 = (i.toDouble() + 1.0) / nTiles.toDouble()
                val startX = (a0 * width).toInt()
                val stopX = (a1 * width).toInt()
                if (stopX == startX) continue
                for (j in 0..<nTiles) {
                    val b0 = (j.toDouble()) / nTiles.toDouble()
                    val b1 = (j.toDouble() + 1.0) / nTiles.toDouble()
                    val startY = (b0 * height).toInt()
                    val stopY = (b1 * height).toInt()
                    if (stopY == startY) continue

                    /*if( MyDebug.LOG )
                            Log.d(TAG, i + " , " + j + " : " + startX + " , " + startY + " to " + stopX + " , " + stopY);*/

                    // We compute a histogram based on the max RGB value, so this matches with the scaling we do in histogram_adjust.rs.
                    // This improves the look of the grass in testHDR24, testHDR27.
                    val launchOptions = LaunchOptions()
                    launchOptions.setX(startX, stopX)
                    launchOptions.setY(startY, stopY)

                    histogramScript.invoke_init_histogram()
                    histogramScript.forEach_histogram_compute_by_value(
                        allocationIn,
                        launchOptions
                    )

                    val histogram = IntArray(256)
                    histogramAllocation!!.copyTo(histogram)

                    /*if( MyDebug.LOG ) {
                            // compare/adjust
                            allocations[0].copyTo(bm);
                            int [] debugHistogram = new int[256];
                            for(int k=0;k<256;k++) {
                                debugHistogram[k] = 0;
                            }
                            int [] debugBuffer = new int[width];
                            for(int y=startY;y<stopY;y++) {
                                bm.getPixels(debugBuffer, 0, width, 0, y, width, 1);
                                for(int x=startX;x<stopX;x++) {
                                    int color = debugBuffer[x];
                                    float r = (float)((color & 0xFF0000) >> 16);
                                    float g = (float)((color & 0xFF00) >> 8);
                                    float b = (float)(color & 0xFF);
                                    //float value = 0.299f*r + 0.587f*g + 0.114f*b; // matches ScriptIntrinsicHistogram default behaviour
                                    float value = Math.max(r, g);
                                    value = Math.max(value, b);
                                    int iValue = (int)value;
                                    iValue = Math.min(255, iValue); // just in case
                                    debugHistogram[iValue]++;
                                }
                            }
                            for(int x=0;x<256;x++) {
                                Log.d(TAG, "histogram[" + x + "] = " + histogram[x] + " debugHistogram: " + debugHistogram[x]);
                                //histogram[x] = debugHistogram[x];
                            }
                        }*/
                    clipHistogram(
                        histogram,
                        tempCHistogram,
                        (stopX - startX),
                        (stopY - startY),
                        cePreserveBlacks
                    )

                    // compute cumulative histogram
                    val histogramOffset = 256 * (i * nTiles + j)
                    cHistogram[histogramOffset] = histogram[0]
                    for (x in 1..255) {
                        cHistogram[histogramOffset + x] =
                            cHistogram[histogramOffset + x - 1] + histogram[x]
                    }
                    /*if( MyDebug.LOG ) {
                        for(int x=0;x<256;x++) {
                            Log.d(TAG, "histogram[" + x + "] = " + histogram[x] + " cumulative: " + cHistogram[histogramOffset+x]);
                        }
                    }*/
                }
            }

            if (MyDebug.LOG) Log.d(
                TAG,
                "adjustHistogram: time after creating histograms: " + (System.currentTimeMillis() - timeS)
            )

            val cHistogramAllocation =
                Allocation.createSized(rs, Element.I32(rs), nTiles * nTiles * 256)
            cHistogramAllocation.copyFrom(cHistogram)
            /*if( histogramAdjustScript == null ) {
                histogramAdjustScript = new ScriptC_histogram_adjust(rs);
            }*/
            val histogramAdjustScript = ScriptC_histogram_adjust(rs)
            histogramAdjustScript.set_c_histogram(cHistogramAllocation)
            histogramAdjustScript.set_hdr_alpha(hdrAlpha)
            histogramAdjustScript.set_n_tiles(nTiles)
            histogramAdjustScript.set_width(width)
            histogramAdjustScript.set_height(height)

            if (MyDebug.LOG) Log.d(
                TAG,
                "time before histogramAdjustScript: " + (System.currentTimeMillis() - timeS)
            )
            histogramAdjustScript.forEach_histogram_adjust(allocationIn, allocationOut)
            if (MyDebug.LOG) Log.d(
                TAG,
                "time after histogramAdjustScript: " + (System.currentTimeMillis() - timeS)
            )

            cHistogramAllocation.destroy()
            if (MyDebug.LOG) Log.d(
                TAG,
                "time after adjusting histogram: " + (System.currentTimeMillis() - timeS)
            )

            histogramAllocation?.destroy()
        }
    }

    /** Only call this from computeHistogram()!
     * @param avg If true, compute the color value as the average of the rgb values. If false,
     * compute the color value as the maximum of the rgb values.
     * @param floatingPoint Whether the allocationIn is in floating point (F32_3) format, or
     * RGBA_8888 format.
     */
    private fun computeHistogramAllocation(
        allocationIn: Allocation?,
        avg: Boolean,
        floatingPoint: Boolean,
        timeS: Long
    ): Allocation {
        if (MyDebug.LOG) Log.d(TAG, "computeHistogramAllocation")
        val histogramAllocation = Allocation.createSized(rs, Element.I32(rs), 256)
        //final boolean useCustomHistogram = false;
        val useCustomHistogram = true
        if (useCustomHistogram) {
            /*if( histogramScript == null ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "create histogramScript");
                histogramScript = new ScriptC_histogram_compute(rs);
            }*/
            if (MyDebug.LOG) Log.d(TAG, "create histogramScript")
            val histogramScript = ScriptC_histogram_compute(rs)
            if (MyDebug.LOG) Log.d(TAG, "bind histogram allocation")
            histogramScript.bind_histogram(histogramAllocation)
            histogramScript.invoke_init_histogram()
            if (MyDebug.LOG) Log.d(TAG, "call histogramScript")
            if (MyDebug.LOG) Log.d(
                TAG,
                "time before histogramScript: " + (System.currentTimeMillis() - timeS)
            )
            if (avg) {
                if (floatingPoint) histogramScript.forEach_histogram_compute_by_intensity_f(
                    allocationIn
                )
                else histogramScript.forEach_histogram_compute_by_intensity(allocationIn)
            } else {
                if (floatingPoint) histogramScript.forEach_histogram_compute_by_value_f(
                    allocationIn
                )
                else histogramScript.forEach_histogram_compute_by_value(allocationIn)
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "time after histogramScript: " + (System.currentTimeMillis() - timeS)
            )
        } else {
            val histogramScriptIntrinsic = ScriptIntrinsicHistogram.create(rs, Element.U8_4(rs))
            histogramScriptIntrinsic.setOutput(histogramAllocation)
            if (MyDebug.LOG) Log.d(TAG, "call histogramScriptIntrinsic")
            histogramScriptIntrinsic.forEach_Dot(allocationIn) // use forEach_dot(); using forEach would simply compute a histogram for red values!
        }

        //histogramAllocation.setAutoPadding(true);
        return histogramAllocation
    }

    enum class HistogramType {
        HISTOGRAM_TYPE_RGB,
        HISTOGRAM_TYPE_LUMINANCE,
        HISTOGRAM_TYPE_VALUE,
        HISTOGRAM_TYPE_INTENSITY,
        HISTOGRAM_TYPE_LIGHTNESS
    }

    /**
     * @param type The type of histogram to compute.
     */
    fun computeHistogram(bitmap: Bitmap, type: HistogramType): IntArray {
        if (MyDebug.LOG) {
            Log.d(TAG, "computeHistogram [bitmap]")
            Log.d(TAG, "type: $type")
        }
        val timeS = System.currentTimeMillis()
        if (!USE_RENDERSCRIPT) {
            /*final int nPixels = bitmap.getWidth()*bitmap.getHeight();
                       int [] pixels = new int[nPixels];
                       bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                       int [] histogram = computeHistogram(pixels);*/
            val javaType: JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type =
                when (type) {
                    HistogramType.HISTOGRAM_TYPE_RGB -> JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_RGB
                    HistogramType.HISTOGRAM_TYPE_LUMINANCE -> JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_LUMINANCE
                    HistogramType.HISTOGRAM_TYPE_VALUE -> JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_VALUE
                    HistogramType.HISTOGRAM_TYPE_INTENSITY -> JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_INTENSITY
                    HistogramType.HISTOGRAM_TYPE_LIGHTNESS -> JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_LIGHTNESS
                    else -> throw RuntimeException("unknown histogram type: $type")
                }
            val function: JavaImageFunctionsHDR.ComputeHistogramApplyFunction =
                JavaImageFunctionsHDR.ComputeHistogramApplyFunction(javaType)
            JavaImageProcessing.applyFunction(
                function,
                bitmap,
                null,
                0,
                0,
                bitmap.width,
                bitmap.height
            )
            val histogram: IntArray = function.histogram

            if (MyDebug.LOG) {
                Log.d(TAG, "image size: " + bitmap.width + " x " + bitmap.height)
                Log.d(
                    TAG,
                    "### time to compute histogram: " + (System.currentTimeMillis() - timeS)
                )
            }
            return histogram
        }
        initRenderscript()
        val allocationIn = Allocation.createFromBitmap(rs, bitmap)
        if (MyDebug.LOG) Log.d(
            TAG,
            "time after createFromBitmap: " + (System.currentTimeMillis() - timeS)
        )
        val avg = when (type) {
            HistogramType.HISTOGRAM_TYPE_RGB, HistogramType.HISTOGRAM_TYPE_LUMINANCE, HistogramType.HISTOGRAM_TYPE_LIGHTNESS -> throw RuntimeException(
                "histogram type not supported by this function: $type"
            )

            HistogramType.HISTOGRAM_TYPE_VALUE -> false
            HistogramType.HISTOGRAM_TYPE_INTENSITY -> true
            else -> throw RuntimeException("unknown histogram type: $type")
        }
        val histogram = computeHistogramRS(allocationIn, bitmap.width, bitmap.height, avg, false)
        allocationIn.destroy()
        freeScripts()
        if (MyDebug.LOG) {
            Log.d(TAG, "image size: " + bitmap.width + " x " + bitmap.height)
            Log.d(TAG, "### time to compute histogram: " + (System.currentTimeMillis() - timeS))
        }
        return histogram
    }

    /**
     * @param pixels Pixels in floating point RGB format. Length of array should be 3*width*height.
     */
    private fun computeHistogram(
        pixels: FloatArray,
        width: Int,
        height: Int,
        avg: Boolean
    ): IntArray {
        if (avg) {
            throw RuntimeException("not implemented")
        }

        val timeS = System.currentTimeMillis()

        val function: JavaImageFunctionsHDR.ComputeHistogramApplyFunction =
            JavaImageFunctionsHDR.ComputeHistogramApplyFunction(
                JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_VALUE
            )
        function.setPixelsRGBf(pixels, width)
        JavaImageProcessing.applyFunction(function, null, null, 0, 0, width, height)
        val histogram: IntArray = function.histogram

        if (MyDebug.LOG) {
            Log.d(TAG, "image size: $width x $height")
            Log.d(TAG, "### time to compute histogram: " + (System.currentTimeMillis() - timeS))
        }
        return histogram
    }

    /**
     * @param width Width of the allocation.
     * @param height Height of the allocation.
     */
    private fun computeHistogramRS(
        allocation: Allocation?,
        width: Int,
        height: Int,
        avg: Boolean,
        floatingPoint: Boolean
    ): IntArray {
        if (MyDebug.LOG) {
            Log.d(TAG, "computeHistogram [renderscript/allocation]")
            Log.d(TAG, "avg: $avg")
            Log.d(TAG, "floating_point: $floatingPoint")
        }
        val timeS = System.currentTimeMillis()
        /*if( !useRenderscript && !avg ) {
            JavaImageFunctionsHDR.ComputeHistogramApplyFunction function = new JavaImageFunctionsHDR.ComputeHistogramApplyFunction(JavaImageFunctionsHDR.ComputeHistogramApplyFunction.Type.TYPE_VALUE);
            JavaImageProcessing.applyFunction(function, allocation, floatingPoint, null, 0, 0, width, height);
            int [] histogram = function.getHistogram();

            if( MyDebug.LOG ) {
                Log.d(TAG, "allocation size: " + width + " x " + height);
                Log.d(TAG, "### time to compute histogram: " + (System.currentTimeMillis() - timeS));
            }
            return histogram;
        }*/
        val histogram = IntArray(256)
        val histogramAllocation =
            computeHistogramAllocation(allocation, avg, floatingPoint, timeS)
        histogramAllocation.copyTo(histogram)
        histogramAllocation.destroy()
        if (MyDebug.LOG) {
            Log.d(TAG, "allocation size: $width x $height")
            Log.d(TAG, "### time to compute histogram: " + (System.currentTimeMillis() - timeS))
        }
        return histogram
    }

    data class HistogramInfo(
        val total: Int,
        val meanBrightness: Int,
        val medianBrightness: Int,
        val maxBrightness: Int
    )

    fun getHistogramInfo(histo: IntArray): HistogramInfo {
        var total = 0
        for (value in histo) total += value
        val middle = total / 2
        var count = 0
        var sumBrightness = 0.0
        var medianBrightness = -1
        var maxBrightness = 0
        for (i in histo.indices) {
            count += histo[i]
            sumBrightness += (histo[i] * i).toDouble()
            if (count >= middle && medianBrightness == -1) {
                medianBrightness = i
            }
            if (histo[i] > 0) {
                maxBrightness = i
            }
        }
        val meanBrightness = (sumBrightness / count + 0.1).toInt()

        return HistogramInfo(total, meanBrightness, medianBrightness, maxBrightness)
    }

    data class BrightenFactors internal constructor(
        val gain: Float,
        val lowX: Float,
        val midX: Float,
        val gamma: Float
    )

    private fun computeBlackLevel(histogramInfo: HistogramInfo, histo: IntArray, iso: Int): Float {
        var blackLevel = 0.0f
        run {
            // quick and dirty dehaze algorithm
            // helps (among others): testAvg1 to testAvg10, testAvg27, testAvg30, testAvg31, testAvg39, testAvg40
            val total = histogramInfo.total
            val percentile = (total * 0.001f).toInt()
            var count = 0
            var darkestBrightness = -1
            for (i in histo.indices) {
                count += histo[i]
                if (count >= percentile && darkestBrightness == -1) {
                    darkestBrightness = i
                }
            }
            blackLevel = max(blackLevel.toDouble(), darkestBrightness.toDouble()).toFloat()
            // don't allow blackLevel too high for "dark" images, as this can cause problems due to exaggerating noise (e.g.,
            // see testAvg38)
            blackLevel =
                min(blackLevel.toDouble(), (if (iso <= 700) 18 else 4).toDouble()).toFloat()
            if (MyDebug.LOG) {
                Log.d(TAG, "percentile: $percentile")
                Log.d(
                    TAG,
                    "darkest_brightness: $darkestBrightness"
                )
                Log.d(TAG, "black_level is now: $blackLevel")
            }
        }
        return blackLevel
    }

    /** Final stage of the noise reduction algorithm.
     * @param pixelsInRgbf The pixels in floating point RGB format.
     * @param width          Width of the input.
     * @param height         Height of the input.
     * @param iso            ISO used for the original images.
     * @param exposureTime  Exposure time used for the original images.
     * @return               Resultant bitmap.
     */
    private fun avgBrightenRGBf(
        pixelsInRgbf: FloatArray,
        width: Int,
        height: Int,
        iso: Int,
        exposureTime: Long
    ): Bitmap {
        if (MyDebug.LOG) {
            Log.d(TAG, "avgBrightenRGBf")
            Log.d(TAG, "iso: $iso")
            Log.d(TAG, "exposure_time: $exposureTime")
        }

        val timeS = System.currentTimeMillis()

        val histo = computeHistogram(pixelsInRgbf, width, height, false)

        val histogramInfo = getHistogramInfo(histo)
        val brightness = histogramInfo.medianBrightness
        val maxBrightness = histogramInfo.maxBrightness
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after computeHistogram: " + (System.currentTimeMillis() - timeS)
        )

        if (MyDebug.LOG) {
            Log.d(TAG, "median brightness: " + histogramInfo.medianBrightness)
            Log.d(TAG, "mean brightness: " + histogramInfo.meanBrightness)
            Log.d(TAG, "max brightness: $maxBrightness")
            /*for(int i=0;i<256;i++) {
                Log.d(TAG, "histogram[" + i + "]: " + histo[i]);
            }*/
        }

        val brightenFactors =
            computeBrightenFactors(true, iso, exposureTime, brightness, maxBrightness)
        val gain = brightenFactors.gain
        val lowX = brightenFactors.lowX
        val midX = brightenFactors.midX
        val gamma = brightenFactors.gamma

        //float gain = brightnessTarget / (float)brightness;
        /*float gamma = (float)(Math.log(maxTarget/(float)brightnessTarget) / Math.log(maxBrightness/(float)brightness));
        float gain = brightnessTarget / ((float)Math.pow(brightness/255.0f, gamma) * 255.0f);
        if( MyDebug.LOG ) {
            Log.d(TAG, "gamma " + gamma);
            Log.d(TAG, "gain " + gain);
            Log.d(TAG, "gain2 " + maxTarget / ((float)Math.pow(maxBrightness/255.0f, gamma) * 255.0f));
        }*/
        /*float gain = brightnessTarget / (float)brightness;
        if( MyDebug.LOG ) {
            Log.d(TAG, "gain: " + gain);
        }
        if( gain < 1.0f ) {
            gain = 1.0f;
            if( MyDebug.LOG ) {
                Log.d(TAG, "clamped gain to : " + gain);
            }
        }*/
        val blackLevel = computeBlackLevel(histogramInfo, histo, iso)

        // use a lower medial filter strength for pixel binned images, so that we don't blur testAvg46 so much (especially sign text)
        val medianFilterStrength = if (avgSampleSize >= 2) 0.5f else 1.0f
        if (MyDebug.LOG) Log.d(
            TAG,
            "median_filter_strength: $medianFilterStrength"
        )

        val outputBitmap = createBitmap(width, height)
        val function: JavaImageFunctionsHDR.AvgBrightenApplyFunction =
            JavaImageFunctionsHDR.AvgBrightenApplyFunction(
                pixelsInRgbf,
                width,
                height,
                gain,
                gamma,
                lowX,
                midX,
                maxBrightness.toFloat(),
                medianFilterStrength,
                blackLevel
            )
        //JavaImageProcessing.applyFunction(function, inputBitmap, outputBitmap, 0, 0, width, height);
        JavaImageProcessing.applyFunction(function, null, outputBitmap, 0, 0, width, height)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after AvgBrightenApplyFunction: " + (System.currentTimeMillis() - timeS)
        )

        //if( iso <= 150 ) {
        if (iso < 1100 && exposureTime < 1000000000L / 59) {
            // for bright scenes, contrast enhancement helps improve the quality of images (especially where we may have both
            // dark and bright regions, e.g., testAvg12); but for dark scenes, it just blows up the noise too much
            // keep nTiles==1 - get too much contrast enhancement with nTiles==4 e.g. for testAvg34
            // tests that are better at 25% (median brightness in brackets): testAvg16 (90), testAvg26 (117), testAvg30 (79),
            //     testAvg43 (55), testAvg44 (82)
            // tests that are better at 50%: testAvg12 (8), testAvg13 (38), testAvg15 (10), testAvg18 (39), testAvg19 (37)
            // other tests improved by doing contrast enhancement: testAvg32, testAvg40
            //adjustHistogram(allocationOut, allocationOut, width, height, 0.5f, 4, timeS);
            //adjustHistogram(allocationOut, allocationOut, width, height, 0.25f, 4, timeS);
            //adjustHistogram(allocationOut, allocationOut, width, height, 0.25f, 1, timeS);
            //adjustHistogram(allocationOut, allocationOut, width, height, 0.5f, 1, timeS);
            val medianLo = 60
            val medianHi = 35
            var alpha =
                (histogramInfo.medianBrightness - medianLo) / (medianHi - medianLo).toFloat()
            alpha = max(alpha.toDouble(), 0.0).toFloat()
            alpha = min(alpha.toDouble(), 1.0).toFloat()
            val amount = (1.0f - alpha) * 0.25f + alpha * 0.5f
            if (MyDebug.LOG) {
                Log.d(TAG, "dro alpha: $alpha")
                Log.d(TAG, "dro amount: $amount")
            }
            adjustHistogram(outputBitmap, outputBitmap, width, height, amount, 1, true, timeS)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after adjustHistogram: " + (System.currentTimeMillis() - timeS)
            )
        }

        if (MyDebug.LOG) Log.d(
            TAG,
            "### total time for avgBrighten: " + (System.currentTimeMillis() - timeS)
        )
        return outputBitmap
    }

    /** Final stage of the noise reduction algorithm.
     * @param input         The allocation in floating point format.
     * @param width         Width of the input.
     * @param height        Height of the input.
     * @param iso           ISO used for the original images.
     * @param exposureTime Exposure time used for the original images.
     * @return              Resultant bitmap.
     */
    private fun avgBrightenRS(
        input: Allocation?,
        width: Int,
        height: Int,
        iso: Int,
        exposureTime: Long
    ): Bitmap {
        if (MyDebug.LOG) {
            Log.d(TAG, "avgBrightenRS")
            Log.d(TAG, "iso: $iso")
            Log.d(TAG, "exposure_time: $exposureTime")
        }
        initRenderscript()

        val timeS = System.currentTimeMillis()

        val histo = computeHistogramRS(input, width, height, avg = false, floatingPoint = true)

        val histogramInfo = getHistogramInfo(histo)
        val brightness = histogramInfo.medianBrightness
        val maxBrightness = histogramInfo.maxBrightness
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after computeHistogram: " + (System.currentTimeMillis() - timeS)
        )

        if (MyDebug.LOG) {
            Log.d(TAG, "median brightness: " + histogramInfo.medianBrightness)
            Log.d(TAG, "mean brightness: " + histogramInfo.meanBrightness)
            Log.d(TAG, "max brightness: $maxBrightness")
            /*for(int i=0;i<256;i++) {
                Log.d(TAG, "histogram[" + i + "]: " + histo[i]);
            }*/
        }

        val brightenFactors =
            computeBrightenFactors(true, iso, exposureTime, brightness, maxBrightness)
        val gain = brightenFactors.gain
        val lowX = brightenFactors.lowX
        val midX = brightenFactors.midX
        val gamma = brightenFactors.gamma

        //float gain = brightnessTarget / (float)brightness;
        /*float gamma = (float)(Math.log(maxTarget/(float)brightnessTarget) / Math.log(maxBrightness/(float)brightness));
        float gain = brightnessTarget / ((float)Math.pow(brightness/255.0f, gamma) * 255.0f);
        if( MyDebug.LOG ) {
            Log.d(TAG, "gamma " + gamma);
            Log.d(TAG, "gain " + gain);
            Log.d(TAG, "gain2 " + maxTarget / ((float)Math.pow(maxBrightness/255.0f, gamma) * 255.0f));
        }*/
        /*float gain = brightnessTarget / (float)brightness;
        if( MyDebug.LOG ) {
            Log.d(TAG, "gain: " + gain);
        }
        if( gain < 1.0f ) {
            gain = 1.0f;
            if( MyDebug.LOG ) {
                Log.d(TAG, "clamped gain to : " + gain);
            }
        }*/
        val blackLevel = computeBlackLevel(histogramInfo, histo, iso)

        // use a lower medial filter strength for pixel binned images, so that we don't blur testAvg46 so much (especially sign text)
        val medianFilterStrength = if (avgSampleSize >= 2) 0.5f else 1.0f
        if (MyDebug.LOG) Log.d(
            TAG,
            "median_filter_strength: $medianFilterStrength"
        )

        /*if( avgBrightenScript == null ) {
            avgBrightenScript = new ScriptC_avg_brighten(rs);
        }*/
        val avgBrightenScript = ScriptC_avg_brighten(rs)
        avgBrightenScript.set_bitmap(input)
        avgBrightenScript.invoke_setBlackLevel(blackLevel)

        avgBrightenScript.set_median_filter_strength(medianFilterStrength)
        avgBrightenScript.invoke_setBrightenParameters(
            gain,
            gamma,
            lowX,
            midX,
            maxBrightness.toFloat()
        )

        val outputBitmap = createBitmap(width, height)
        val allocationOut = Allocation.createFromBitmap(rs, outputBitmap)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after creating allocation_out: " + (System.currentTimeMillis() - timeS)
        )

        avgBrightenScript.forEach_avg_brighten_f(input, allocationOut)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after avg_brighten: " + (System.currentTimeMillis() - timeS)
        )

        //if( iso <= 150 ) {
        if (iso < 1100 && exposureTime < 1000000000L / 59) {
            // for bright scenes, contrast enhancement helps improve the quality of images (especially where we may have both
            // dark and bright regions, e.g., testAvg12); but for dark scenes, it just blows up the noise too much
            // keep nTiles==1 - get too much contrast enhancement with nTiles==4 e.g. for testAvg34
            // tests that are better at 25% (median brightness in brackets): testAvg16 (90), testAvg26 (117), testAvg30 (79),
            //     testAvg43 (55), testAvg44 (82)
            // tests that are better at 50%: testAvg12 (8), testAvg13 (38), testAvg15 (10), testAvg18 (39), testAvg19 (37)
            // other tests improved by doing contrast enhancement: testAvg32, testAvg40
            //adjustHistogram(allocationOut, allocationOut, width, height, 0.5f, 4, timeS);
            //adjustHistogram(allocationOut, allocationOut, width, height, 0.25f, 4, timeS);
            //adjustHistogram(allocationOut, allocationOut, width, height, 0.25f, 1, timeS);
            //adjustHistogram(allocationOut, allocationOut, width, height, 0.5f, 1, timeS);
            val medianLo = 60
            val medianHi = 35
            var alpha =
                (histogramInfo.medianBrightness - medianLo) / (medianHi - medianLo).toFloat()
            alpha = max(alpha.toDouble(), 0.0).toFloat()
            alpha = min(alpha.toDouble(), 1.0).toFloat()
            val amount = (1.0f - alpha) * 0.25f + alpha * 0.5f
            if (MyDebug.LOG) {
                Log.d(TAG, "dro alpha: $alpha")
                Log.d(TAG, "dro amount: $amount")
            }
            adjustHistogramRS(
                allocationOut,
                allocationOut,
                width,
                height,
                amount,
                1,
                true,
                timeS
            )
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after adjustHistogram: " + (System.currentTimeMillis() - timeS)
            )
        }

        allocationOut.copyTo(outputBitmap)
        allocationOut.destroy()
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after copying to bitmap: " + (System.currentTimeMillis() - timeS)
        )

        freeScripts()
        if (MyDebug.LOG) Log.d(
            TAG,
            "### total time for avgBrighten: " + (System.currentTimeMillis() - timeS)
        )
        return outputBitmap
    }

    /** Final stage of the noise reduction algorithm.
     * @param avgData       AvgData returned from call to processAvg().
     * @param width          Width of the input.
     * @param height         Height of the input.
     * @param iso            ISO used for the original images.
     * @param exposureTime  Exposure time used for the original images.
     * @return               Resultant bitmap.
     */
    fun avgBrighten(
        avgData: AvgData,
        width: Int,
        height: Int,
        iso: Int,
        exposureTime: Long
    ): Bitmap {
        return if (!USE_RENDERSCRIPT) {
            //float [] pixelsRgbf = HDRProcessor.AllocationToRGBf(avg_data.allocationOut, width, height);
            //return avgBrightenRGBf(pixelsRgbf, width, height, iso, exposureTime);
            avgBrightenRGBf(avgData.pixelsRgbfOut, width, height, iso, exposureTime)
        } else {
            avgBrightenRS(avgData.allocationOut, width, height, iso, exposureTime)
        }
    }

    /**
     * Computes a value for how sharp the image is perceived to be. The higher the value, the
     * sharper the image.
     * @param allocationIn The input allocation.
     * @param width         The width of the allocation.
     */
    private fun computeSharpness(allocationIn: Allocation, width: Int, timeS: Long): Float {
        if (MyDebug.LOG) Log.d(TAG, "computeSharpness")
        if (MyDebug.LOG) Log.d(TAG, "### time: " + (System.currentTimeMillis() - timeS))
        val sumsAllocation = Allocation.createSized(rs, Element.I32(rs), width)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after createSized: " + (System.currentTimeMillis() - timeS)
        )
        /*if( sharpnessScript == null ) {
            sharpnessScript = new ScriptC_calculate_sharpness(rs);
            if( MyDebug.LOG )
                Log.d(TAG, "### time after create sharpnessScript: " + (System.currentTimeMillis() - timeS));
        }*/
        val sharpnessScript = ScriptC_calculate_sharpness(rs)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after create sharpnessScript: " + (System.currentTimeMillis() - timeS)
        )
        if (MyDebug.LOG) Log.d(TAG, "bind sums allocation")
        sharpnessScript.bind_sums(sumsAllocation)
        sharpnessScript.set_bitmap(allocationIn)
        sharpnessScript.set_width(width)
        sharpnessScript.invoke_init_sums()
        if (MyDebug.LOG) Log.d(TAG, "call sharpnessScript")
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time before sharpnessScript: " + (System.currentTimeMillis() - timeS)
        )
        sharpnessScript.forEach_calculate_sharpness(allocationIn)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after sharpnessScript: " + (System.currentTimeMillis() - timeS)
        )

        val sums = IntArray(width)
        sumsAllocation.copyTo(sums)
        sumsAllocation.destroy()
        var totalSum = 0.0f
        for (i in 0..<width) {
            /*if( MyDebug.LOG )
                Log.d(TAG, "sums[" + i + "] = " + sums[i]);*/
            totalSum += sums[i].toFloat()
        }
        if (MyDebug.LOG) Log.d(TAG, "total_sum: $totalSum")
        return totalSum
    }

    companion object {
        private const val TAG = "HDRProcessor"

        // flag to control migration away from renderscript!
        // if useRenderscript==false, then use Java instead of Renderscript
        const val USE_RENDERSCRIPT: Boolean = false

        val defaultTonemappingAlgorithmC: TonemappingAlgorithm =
            TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD

        fun sceneIsLowLight(iso: Int, exposureTime: Long): Boolean {
            val isoForDark = 1100
            // For Nexus 6, max reported ISO is 1196, so the limit for dark scenes shouldn't be more than this
            // Nokia 8's max reported ISO is 1551
            // Note that OnePlus 3T has max reported ISO of 800, but this is a device bug
            // The addition of the iso*exposureTime helps behaviour on Galaxy S10e which uses ISO >= 1600
            // far more often, even for non-dark scenes. Potentially we could drop the requirement for
            // "iso >= ISO_FOR_DARK" and instead have iso*exposureTime >= 91 to 115, but we need the
            // dedicated iso check for Nexus 6 (iso 1196 exposure time 1/12s should be dark) and
            // Nokia 8 testAvg23 (iso 1044 exposure time 0.1s shouldn't be dark).
            // We also assume dark for long exposure times (which in practice is probably set in
            // manual mode) - since long exposure times will give lower ISOs (e.g., on Galaxy S10e)
            // (also useful for cameras where max ISO isn't as high as ISO_FOR_DARK)
            //return iso >= ISO_FOR_DARK;
            return (iso >= isoForDark && iso * exposureTime >= 69 * 1000000000L) || exposureTime >= (1000000000L / 5 - 10000L)
        }

        private fun getBrightnessTarget(
            brightness: Int,
            maxGainFactor: Float,
            idealBrightness: Int
        ): Int {
            var brightness = brightness
            var maxGainFactor = maxGainFactor
            if (brightness > 0) {
                // At least try to achieve a minimum brightness.
                // Increasing maxGainFactor helps the following tests significantly: testAvg12, testAvg14, testAvg15,
                // testAvg28, testAvg31, testAvg32.
                // Other tests also helped to a lesser degree are: testAvg1, testAvg5, testAvg6, testAvg40, testAvg41,
                // testAvg42, testHDR1, testHDR1_exp5, testHDR11 (DRO example), testHDR20 (DRO example), testHDR28 (DRO example),
                // testHDR48, testHDR49, testHDR49_exp5, testHDR53.
                // We need to be careful of increasing maxGainFactor too high in some cases - for AvgTests, see comment in
                // computeBrightenFactors() for examples of tests that would be affected.

                val minBrightnessC = 42.0f
                val minMaxGainFactor = minBrightnessC / brightness
                maxGainFactor =
                    max(maxGainFactor.toDouble(), minMaxGainFactor.toDouble()).toFloat()

                // still set some maximum maxGainFactor - highest maxGainFactor in tests is
                // testAvg14 with maxGainFactor=14.0, which benefits from this, but some parts starting
                // to look overblown
                maxGainFactor = min(maxGainFactor.toDouble(), 15.0).toFloat()
            }

            if (brightness <= 0) brightness = 1
            if (MyDebug.LOG) {
                Log.d(TAG, "brightness: $brightness")
                Log.d(TAG, "max_gain_factor: $maxGainFactor")
                Log.d(
                    TAG,
                    "ideal_brightness: $idealBrightness"
                )
            }
            val medianTarget = min(
                idealBrightness.toDouble(),
                (maxGainFactor * brightness).toInt().toDouble()
            ).toInt()
            return max(brightness.toDouble(), medianTarget.toDouble()).toInt() // don't make darker
        }

        /** Computes various factors used in the avg_brighten.rs script.
         */
        fun computeBrightenFactors(
            hasIsoExposure: Boolean,
            iso: Int,
            exposureTime: Long,
            brightness: Int,
            maxBrightness: Int
        ): BrightenFactors {
            // For outdoor/bright images, don't want maxGainFactor 4, otherwise we lose variation in grass colour in testAvg42
            // and having maxGainFactor at 1.5 prevents testAvg43, testAvg44 being too bright and oversaturated
            // for other images, we also don't want maxGainFactor 4, as makes cases too bright and overblown if it would
            // take the maxPossibleValue over 255. Especially testAvg46, but also testAvg25, testAvg31, testAvg38,
            // testAvg39.
            // Note however that we now do allow increasing the maxGainFactor in getBrightnessTarget(), depending on
            // brightness levels.
            val maxGainFactor = 1.5f
            var idealBrightness = 119
            if (hasIsoExposure && iso < 1100 && exposureTime < 1000000000L / 59) {
                // this helps: testAvg12, testAvg21, testAvg35
                // but note we don't want to treat the following as "bright": testAvg17, testAvg23, testAvg36, testAvg37, testAvg50
                idealBrightness = 199
            }
            val brightnessTarget =
                getBrightnessTarget(brightness, maxGainFactor, idealBrightness)
            //int maxTarget = Math.min(255, (int)((maxBrightness*brightnessTarget)/(float)brightness + 0.5f) );
            if (MyDebug.LOG) {
                Log.d(TAG, "brightness: $brightness")
                Log.d(TAG, "max_brightness: $maxBrightness")
                Log.d(
                    TAG,
                    "ideal_brightness: $idealBrightness"
                )
                Log.d(
                    TAG,
                    "brightness target: $brightnessTarget"
                )
                //Log.d(TAG, "max target: " + maxTarget);
            }

            return computeBrightenFactors(
                hasIsoExposure,
                iso,
                exposureTime,
                brightness,
                maxBrightness,
                brightnessTarget,
                true
            )
        }

        /** Computes various factors used in the avg_brighten.rs script.
         */
        private fun computeBrightenFactors(
            hasIsoExposure: Boolean,
            iso: Int,
            exposureTime: Long,
            brightness: Int,
            maxBrightness: Int,
            brightnessTarget: Int,
            brightenOnly: Boolean
        ): BrightenFactors {
            /* We use a combination of gain and gamma to brighten images if required. Gain works best for
         * dark images (e.g., see testAvg8), gamma works better for bright images (e.g., testAvg12).
         */
            var brightness = brightness
            if (brightness <= 0) brightness = 1
            var gain = brightnessTarget / brightness.toFloat()
            if (MyDebug.LOG) Log.d(TAG, "gain $gain")
            if (gain < 1.0f && brightenOnly) {
                gain = 1.0f
                if (MyDebug.LOG) {
                    Log.d(TAG, "clamped gain to: $gain")
                }
            }
            var gamma = 1.0f
            val maxPossibleValue = gain * maxBrightness
            if (MyDebug.LOG) Log.d(
                TAG,
                "max_possible_value: $maxPossibleValue"
            )

            /*if( maxPossibleValue > 255.0f ) {
            gain = 255.0f / maxBrightness;
            if( MyDebug.LOG )
                Log.d(TAG, "limit gain to: " + gain);
            // use gamma correction for the remainder
            if( brightnessTarget > gain * brightness ) {
                gamma = (float) (Math.log(brightnessTarget / 255.0f) / Math.log(gain * brightness / 255.0f));
            }
        }

        //float gamma = (float)(Math.log(brightnessTarget/255.0f) / Math.log(brightness/255.0f));
        if( MyDebug.LOG )
            Log.d(TAG, "gamma " + gamma);
        final float minGammaNonBrightC = 0.75f;
        //final float minGammaNonBrightC = 0.5f;
        if( gamma > 1.0f ) {
            gamma = 1.0f;
            if( MyDebug.LOG ) {
                Log.d(TAG, "clamped gamma to : " + gamma);
            }
        }
        else if( hasIsoExposure && iso > 150 && gamma < minGammaNonBrightC ) {
            // too small gamma on non-bright reduces contrast too much (e.g., see testAvg9)
            // however we can't clamp too much, see testAvg28, testAvg32
            gamma = minGammaNonBrightC;
            if( MyDebug.LOG ) {
                Log.d(TAG, "clamped gamma to : " + gamma);
            }
        }*/
            var midX = 255.5f
            if (maxPossibleValue > 255.0f) {
                if (MyDebug.LOG) Log.d(TAG, "use piecewise gain/gamma")
                // use piecewise function with gain and gamma
                // changed from 0.5 to 0.6 to help grass colour variation in testAvg42; also helps testAvg6; using 0.8 helps testAvg46 and testAvg50 further
                //float midY = ( hasIsoExposure && iso <= 150 ) ? 0.6f*255.0f : 0.8f*255.0f;
                val midY =
                    if (hasIsoExposure && iso < 1100 && exposureTime < 1000000000L / 59) 0.6f * 255.0f else 0.8f * 255.0f
                midX = midY / gain
                gamma =
                    (ln((midY / 255.0f).toDouble()) / ln((midX / maxBrightness).toDouble())).toFloat()
            } else if (brightenOnly && maxPossibleValue < 255.0f && maxBrightness > 0) {
                // slightly brightens testAvg17; also brightens testAvg8 to be clearer
                var altGain = 255.0f / maxBrightness
                // okay to allow higher max than maxGainFactor, when it isn't going to take us over 255
                altGain = min(altGain.toDouble(), 4.0).toFloat()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "alt_gain: $altGain"
                )
                if (altGain > gain) {
                    gain = altGain
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "increased gain to: $gain"
                    )
                }
            }
            var lowX = 0.0f
            if (hasIsoExposure && iso >= 400) {
                // this helps: testAvg10, testAvg28, testAvg31, testAvg33
                //lowX = Math.min(8.0f, 0.125f*midX);
                // don't use midX directly, otherwise we get unstable behaviour depending on whether we
                // entered "use piecewise gain/gamma" above or not
                // see unit test testBrightenFactors().
                val piecewiseMidY = 0.5f * 255.0f
                val piecewiseMidX = piecewiseMidY / gain
                lowX = min(8.0, (0.125f * piecewiseMidX).toDouble()).toFloat()
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "low_x $lowX")
                Log.d(TAG, "mid_x $midX")
                Log.d(TAG, "gamma $gamma")
            }

            return BrightenFactors(gain, lowX, midX, gamma)
        }
    }
}
