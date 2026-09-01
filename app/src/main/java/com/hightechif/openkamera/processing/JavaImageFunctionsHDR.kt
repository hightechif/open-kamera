/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.processing

import android.graphics.Bitmap
import com.hightechif.openkamera.processing.HDRProcessor.TonemappingAlgorithm
import com.hightechif.openkamera.processing.HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_ACES
import com.hightechif.openkamera.processing.HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_CLAMP
import com.hightechif.openkamera.processing.HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_EXPONENTIAL
import com.hightechif.openkamera.processing.HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_FU2
import com.hightechif.openkamera.processing.HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_REINHARD
import com.hightechif.openkamera.processing.JavaImageProcessing.CachedBitmap
import com.hightechif.openkamera.processing.JavaImageProcessing.FastAccessBitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

//import android.util.Log;
object JavaImageFunctionsHDR {
    private const val TAG = "JavaImageFunctionsHDR"

    internal class CreateMTBApplyFunction(
        private val useMtb: Boolean,
        private val medianValue: Int
    ) : JavaImageProcessing.ApplyFunctionInterface {
        override fun init(nThreads: Int) {
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // unused
            throw RuntimeException("not implemented")
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: IntArray,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            val pixelsOut: IntArray? = output?.cachedPixelsI
            if (useMtb) {
                var y = offY
                var c = 0
                while (y < offY + thisHeight) {
                    var x = offX
                    while (x < offX + thisWidth) {
                        // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                        val color = pixels[c]
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF

                        var value = max(r.toDouble(), g.toDouble()).toInt()
                        value = max(value.toDouble(), b.toDouble()).toInt()

                        // ignore small differences to reduce effect of noise - this helps testHDR22
                        val diff = if (value > medianValue) value - medianValue
                        else medianValue - value

                        if (diff <= 4)  // should be same value as minDiffC in HDRProcessor.autoAlignment()
                            pixelsOut?.set(c, 127 shl 24)
                        else if (value <= medianValue) pixelsOut?.set(c, 0)
                        else pixelsOut?.set(c, 255 shl 24)
                        x++
                        c++
                    }
                    y++
                }
            } else {
                var y = offY
                var c = 0
                while (y < offY + thisHeight) {
                    var x = offX
                    while (x < offX + thisWidth) {
                        // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                        val color = pixels[c]
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF

                        var value = max(r.toDouble(), g.toDouble()).toInt()
                        value = max(value.toDouble(), b.toDouble()).toInt()

                        pixelsOut?.set(c, value shl 24)
                        x++
                        c++
                    }
                    y++
                }
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: ByteArray?,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // unused
            throw RuntimeException("not implemented")
        }
    }

    internal class AlignMTBApplyFunction(
        private val useMtb: Boolean,
        private val bitmap0: Bitmap,
        private val bitmap1: Bitmap,
        private val offsetX: Int,
        private val offsetY: Int,
        private val stepSize: Int
    ) : JavaImageProcessing.ApplyFunctionInterface {
        private lateinit var errors: Array<IntArray?>
        private lateinit var fastBitmap0: Array<FastAccessBitmap?>
        private lateinit var fastBitmap1: Array<FastAccessBitmap?>

        override fun init(nThreads: Int) {
            errors = arrayOfNulls(nThreads)
            fastBitmap0 = arrayOfNulls(nThreads)
            fastBitmap1 = arrayOfNulls(nThreads)
            for (i in 0..<nThreads) {
                fastBitmap0[i] = FastAccessBitmap(bitmap0)
                fastBitmap1[i] = FastAccessBitmap(bitmap1)
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            if (errors[threadIndex] == null) errors[threadIndex] = IntArray(9)

            /* We want to sample every stepSize'th pixel. Because this is awkward to set up (and wasn't possible
               in renderscript version), instead we fake it by sampling over an input bitmap of size
               (width/stepSize, height/stepSize), and then scaling the coordinates by stepSize.

               The reason we want to sample every stepSize'th pixel is it's good enough for the algorithm to work,
               and is much faster.
               */
            val bitmap0Width = bitmap0.width
            val bitmap1Width = bitmap1.width
            val bitmap1Height = bitmap1.height
            if (useMtb) {
                var sy = offY
                var ey = offY + thisHeight
                while (sy * stepSize + offsetY < stepSize) sy++
                while ((ey - 1) * stepSize + offsetY >= bitmap1Height - stepSize) ey--
                for (cy in sy..<ey) {
                    val y = cy * stepSize
                    val yPlusOffset = y + offsetY

                    fastBitmap0[threadIndex]?.getPixel(
                        0,
                        y
                    ) // force cache to cover rows needed by this row
                    val bitmap0CacheY: Int = fastBitmap0[threadIndex]!!.cacheY
                    val yRelBitmap0Cache = y - bitmap0CacheY
                    val bitmap0CachePixels: IntArray = fastBitmap0[threadIndex]!!.cachedPixelsI

                    fastBitmap1[threadIndex]?.ensureCache(
                        yPlusOffset - stepSize,
                        yPlusOffset + stepSize
                    ) // force cache to cover rows needed by this row
                    val bitmap1CacheY: Int = fastBitmap1[threadIndex]!!.cacheY
                    val yRelBitmap1Cache = y - bitmap1CacheY
                    val bitmap1CachePixels: IntArray = fastBitmap1[threadIndex]!!.cachedPixelsI
                    val yRelBitmap1CachePlusOffset = yRelBitmap1Cache + offsetY

                    var sx = offX
                    var ex = offX + thisWidth
                    while (sx * stepSize + offsetX < stepSize) sx++
                    while ((ex - 1) * stepSize + offsetX >= bitmap1Width - stepSize) ex--
                    for (cx in sx..<ex) {
                        val x = cx * stepSize
                        val xPlusOffset = x + offsetX
                        //if( xPlusOffset >= stepSize && xPlusOffset < bitmap1Width-stepSize && yPlusOffset >= stepSize && yPlusOffset < bitmap1Height-stepSize )
                        run {
                            //int pixel0 = fastBitmap0[threadIndex].getPixel(x, y) >>> 24;
                            val pixel0 =
                                bitmap0CachePixels[yRelBitmap0Cache * bitmap0Width + x] ushr 24

                            //pixel1 = fastBitmap1[threadIndex].getPixel(xPlusOffset-stepSize, yPlusOffset-stepSize) >>> 24;

                            /*int c=0;
for(int dy=-1;dy<=1;dy++) {
for(int dx=-1;dx<=1;dx++) {
int pixel1 = fastBitmap1[threadIndex].getPixel(xPlusOffset+dx*stepSize, yPlusOffset+dy*stepSize) >>> 24;
if( pixel0 != pixel1 && pixel0 != 127 && pixel1 != 127 ) {
// check against 127 to ignore noise - see CreateMTBApplyFunction
errors[threadIndex][c]++;
}
c++;
}
}*/

                            // unroll loops
                            // check against 127 to ignore noise - see CreateMTBApplyFunction
                            var pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset - stepSize) * bitmap1Width + (xPlusOffset - stepSize)] ushr 24
                            if (pixel0 != pixel1 && pixel0 != 127 && pixel1 != 127) {
                                errors[threadIndex]?.get(0)?.let {
                                    errors[threadIndex]!![0] = it + 1
                                }
                            }

                            //pixel1 = fastBitmap1[threadIndex].getPixel(xPlusOffset, yPlusOffset-stepSize) >>> 24;
                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset - stepSize) * bitmap1Width + (xPlusOffset)] ushr 24
                            if (pixel0 != pixel1 && pixel0 != 127 && pixel1 != 127) {
                                errors[threadIndex]?.get(1)?.let {
                                    errors[threadIndex]!![1] = it + 1
                                }
                            }

                            //pixel1 = fastBitmap1[threadIndex].getPixel(xPlusOffset+stepSize, yPlusOffset-stepSize) >>> 24;
                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset - stepSize) * bitmap1Width + (xPlusOffset + stepSize)] ushr 24
                            if (pixel0 != pixel1 && pixel0 != 127 && pixel1 != 127) {
                                errors[threadIndex]?.get(2)?.let {
                                    errors[threadIndex]!![2] = it + 1
                                }
                            }

                            //pixel1 = fastBitmap1[threadIndex].getPixel(xPlusOffset-stepSize, yPlusOffset) >>> 24;
                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset) * bitmap1Width + (xPlusOffset - stepSize)] ushr 24
                            if (pixel0 != pixel1 && pixel0 != 127 && pixel1 != 127) {
                                errors[threadIndex]?.get(3)?.let {
                                    errors[threadIndex]!![3] = it + 1
                                }
                            }

                            //pixel1 = fastBitmap1[threadIndex].getPixel(xPlusOffset, yPlusOffset) >>> 24;
                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset) * bitmap1Width + (xPlusOffset)] ushr 24
                            if (pixel0 != pixel1 && pixel0 != 127 && pixel1 != 127) {
                                errors[threadIndex]?.get(4)?.let {
                                    errors[threadIndex]!![4] = it + 1
                                }
                            }

                            //pixel1 = fastBitmap1[threadIndex].getPixel(xPlusOffset+stepSize, yPlusOffset) >>> 24;
                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset) * bitmap1Width + (xPlusOffset + stepSize)] ushr 24
                            if (pixel0 != pixel1 && pixel0 != 127 && pixel1 != 127) {
                                errors[threadIndex]?.get(5)?.let {
                                    errors[threadIndex]!![5] = it + 1
                                }
                            }

                            //pixel1 = fastBitmap1[threadIndex].getPixel(xPlusOffset-stepSize, yPlusOffset+stepSize) >>> 24;
                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset + stepSize) * bitmap1Width + (xPlusOffset - stepSize)] ushr 24
                            if (pixel0 != pixel1 && pixel0 != 127 && pixel1 != 127) {
                                errors[threadIndex]?.get(6)?.let {
                                    errors[threadIndex]!![6] = it + 1
                                }
                            }

                            //pixel1 = fastBitmap1[threadIndex].getPixel(xPlusOffset, yPlusOffset+stepSize) >>> 24;
                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset + stepSize) * bitmap1Width + (xPlusOffset)] ushr 24
                            if (pixel0 != pixel1 && pixel0 != 127 && pixel1 != 127) {
                                errors[threadIndex]?.get(7)?.let {
                                    errors[threadIndex]!![7] = it + 1
                                }
                            }

                            //pixel1 = fastBitmap1[threadIndex].getPixel(xPlusOffset+stepSize, yPlusOffset+stepSize) >>> 24;
                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset + stepSize) * bitmap1Width + (xPlusOffset + stepSize)] ushr 24
                            if (pixel0 != pixel1 && pixel0 != 127 && pixel1 != 127) {
                                errors[threadIndex]?.get(8)?.let {
                                    errors[threadIndex]!![8] = it + 1
                                }
                            }
                        }
                    }
                }
            } else {
                var sy = offY
                var ey = offY + thisHeight
                while (sy * stepSize + offsetY < stepSize) sy++
                while ((ey - 1) * stepSize + offsetY >= bitmap1Height - stepSize) ey--
                for (cy in sy..<ey) {
                    //for(int cy=offY;cy<offY+thisHeight;cy++) {
                    val y = cy * stepSize
                    val yPlusOffset = y + offsetY

                    fastBitmap0[threadIndex]?.getPixel(
                        0,
                        y
                    ) // force cache to cover rows needed by this row
                    val bitmap0CacheY: Int = fastBitmap0[threadIndex]!!.cacheY
                    val yRelBitmap0Cache = y - bitmap0CacheY
                    val bitmap0CachePixels: IntArray =
                        fastBitmap0[threadIndex]!!.cachedPixelsI

                    fastBitmap1[threadIndex]?.ensureCache(
                        yPlusOffset - stepSize,
                        yPlusOffset + stepSize
                    ) // force cache to cover rows needed by this row
                    val bitmap1CacheY: Int = fastBitmap1[threadIndex]!!.cacheY
                    val yRelBitmap1Cache = y - bitmap1CacheY
                    val bitmap1CachePixels: IntArray =
                        fastBitmap1[threadIndex]!!.cachedPixelsI
                    val yRelBitmap1CachePlusOffset = yRelBitmap1Cache + offsetY

                    var sx = offX
                    var ex = offX + thisWidth
                    while (sx * stepSize + offsetX < stepSize) sx++
                    while ((ex - 1) * stepSize + offsetX >= bitmap1Width - stepSize) ex--
                    for (cx in sx..<ex) {
                        //for(int cx=offX;cx<offX+thisWidth;cx++) {
                        val x = cx * stepSize
                        val xPlusOffset = x + offsetX
                        //if( xPlusOffset >= stepSize && xPlusOffset < bitmap1Width-stepSize && yPlusOffset >= stepSize && yPlusOffset < bitmap1Height-stepSize )
                        run {
                            //int pixel0 = fastBitmap0[threadIndex].getPixel(x, y) >>> 24;
                            val pixel0 =
                                bitmap0CachePixels[yRelBitmap0Cache * bitmap0Width + x] ushr 24
                            var diff: Int
                            val overflowCheckC = 2000000000

                            /*if( MyDebug.LOG ) {
            Log.d(TAG, "int = " + fastBitmap0[threadIndex].getPixel(x, y));
            Log.d(TAG, "pixel0 = " + pixel0);
        }*/

                            /*int c=0;
                            for(int dy=-1;dy<=1;dy++) {
                                for(int dx=-1;dx<=1;dx++) {
                                    //int pixel1 = fastBitmap1[threadIndex].getPixel(xPlusOffset+dx*stepSize, yPlusOffset+dy*stepSize) >>> 24;
                                    int pixel1 = bitmap1CachePixels[(yRelBitmap1CachePlusOffset+dy*stepSize)*bitmap1Width+(xPlusOffset+dx*stepSize)] >>> 24;
                                    int diff = pixel1 - pixel0;
                                    //if( Math.abs(diff) > 255 )
                                    //    throw new RuntimeException("diff too high: " + diff);
                                    int diff2 = diff*diff;
                                    //diff2 = pixel0;
                                    //if( MyDebug.LOG )
                                    //    Log.d(TAG, "diff = " + diff);
                                    if( errors[threadIndex][c] < 2000000000 ) { // avoid risk of overflow
                                        errors[threadIndex][c] += diff2;
                                    }
                                    c++;
                                }
                            }*/

                            // unroll loops
                            var pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset - stepSize) * bitmap1Width + (xPlusOffset - stepSize)] ushr 24
                            diff = pixel1 - pixel0
                            //if( Math.abs(diff) > 255 )
                            //    throw new RuntimeException("diff too high: " + diff);
                            if (errors[threadIndex]?.get(0)!! < overflowCheckC) { // avoid risk of overflow
                                errors[threadIndex]!![0] += (diff * diff)
                            }

                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset - stepSize) * bitmap1Width + (xPlusOffset)] ushr 24
                            diff = pixel1 - pixel0
                            //if( Math.abs(diff) > 255 )
                            //    throw new RuntimeException("diff too high: " + diff);
                            if (errors[threadIndex]?.get(1)!! < overflowCheckC) { // avoid risk of overflow
                                errors[threadIndex]!![1] += diff * diff
                            }

                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset - stepSize) * bitmap1Width + (xPlusOffset + stepSize)] ushr 24
                            diff = pixel1 - pixel0
                            //if( Math.abs(diff) > 255 )
                            //    throw new RuntimeException("diff too high: " + diff);
                            if (errors[threadIndex]?.get(2)!! < overflowCheckC) { // avoid risk of overflow
                                errors[threadIndex]!![2] += diff * diff
                            }

                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset) * bitmap1Width + (xPlusOffset - stepSize)] ushr 24
                            diff = pixel1 - pixel0
                            //if( Math.abs(diff) > 255 )
                            //    throw new RuntimeException("diff too high: " + diff);
                            if (errors[threadIndex]?.get(3)!! < overflowCheckC) { // avoid risk of overflow
                                errors[threadIndex]!![3] += diff * diff
                            }

                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset) * bitmap1Width + (xPlusOffset)] ushr 24
                            diff = pixel1 - pixel0
                            //if( Math.abs(diff) > 255 )
                            //    throw new RuntimeException("diff too high: " + diff);
                            if (errors[threadIndex]?.get(4)!! < overflowCheckC) { // avoid risk of overflow
                                errors[threadIndex]!![4] += diff * diff
                            }

                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset) * bitmap1Width + (xPlusOffset + stepSize)] ushr 24
                            diff = pixel1 - pixel0
                            //if( Math.abs(diff) > 255 )
                            //    throw new RuntimeException("diff too high: " + diff);
                            if (errors[threadIndex]?.get(5)!! < overflowCheckC) { // avoid risk of overflow
                                errors[threadIndex]!![5] += diff * diff
                            }

                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset + stepSize) * bitmap1Width + (xPlusOffset - stepSize)] ushr 24
                            diff = pixel1 - pixel0
                            //if( Math.abs(diff) > 255 )
                            //    throw new RuntimeException("diff too high: " + diff);
                            if (errors[threadIndex]?.get(6)!! < overflowCheckC) { // avoid risk of overflow
                                errors[threadIndex]!![6] += diff * diff
                            }

                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset + stepSize) * bitmap1Width + (xPlusOffset)] ushr 24
                            diff = pixel1 - pixel0
                            //if( Math.abs(diff) > 255 )
                            //    throw new RuntimeException("diff too high: " + diff);
                            if (errors[threadIndex]?.get(7)!! < overflowCheckC) { // avoid risk of overflow
                                errors[threadIndex]!![7] += diff * diff
                            }

                            pixel1 =
                                bitmap1CachePixels[(yRelBitmap1CachePlusOffset + stepSize) * bitmap1Width + (xPlusOffset + stepSize)] ushr 24
                            diff = pixel1 - pixel0
                            //if( Math.abs(diff) > 255 )
                            //    throw new RuntimeException("diff too high: " + diff);
                            if (errors[threadIndex]?.get(8)!! < overflowCheckC) { // avoid risk of overflow
                                errors[threadIndex]!![8] += diff * diff
                            }
                        }
                    }
                }
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: IntArray,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // unused
            throw RuntimeException("not implemented")
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: ByteArray?,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // unused
            throw RuntimeException("not implemented")
        }

        fun getErrors(): IntArray {
            val totalErrors = IntArray(errors[0]?.size ?: 0)
            // for each errors, add its entries to the total errors
            for (error in errors) {
                if (error != null) {
                    for (j in error.indices) {
                        totalErrors[j] += error[j]
                    }
                }
            }
            return totalErrors
        }
    }

    /* Simplified brighten algorithm for gain/gamma only, used for DRO algorithm.
     */
    internal class DROBrightenApplyFunction(
        gain: Float,
        val gamma: Float,
        val lowX: Float,
        val midX: Float,
        internal val maxX: Float
    ) : JavaImageProcessing.ApplyFunctionInterface {
        var gainA: Float = 0f
        var gainB: Float = 0f // see comments below
        private val valueToGammaScaleLut = FloatArray(256) // look up table for performance

        init {
            /* We want A and B s.t.:
                float alpha = (value-lowX)/(midX-lowX);
                float newValue = (1.0-alpha)*lowX + alpha*gain*midX;
                We should be able to write this as newValue = A * value + B
                alpha = value/(midX-lowX) - lowX/(midX-lowX)
                newValue = lowX - value*lowX/(midX-lowX) + lowX^2/(midX-lowX) +
                    value*gain*midX/(midX-lowX) - gain*midX*lowX/(midX-lowX)
                So A = (gain*midX - lowX)/(midX-lowX)
                B = lowX + lowX^2/(midX-lowX) - gain*midX*lowX/(midX-lowX)
                = (lowX*midX - lowX^2 + lowX^2 - gain*midX*lowX)/(midX-lowX)
                = (lowX*midX - gain*midX*lowX)/(midX-lowX)
                = lowX*midX*(1-gain)/(midX-lowX)
             */

            if (midX > lowX) {
                this.gainA = (gain * midX - lowX) / (midX - lowX)
                this.gainB = lowX * midX * (1.0f - gain) / (midX - lowX)
            } else {
                this.gainA = 1.0f
                this.gainB = 0.0f
            }

            for (value in 0..255) {
                val newValue = (value / maxX).toDouble().pow(gamma.toDouble()).toFloat() * 255.0f
                valueToGammaScaleLut[value] = newValue / value
            }
        }

        override fun init(nThreads: Int) {
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // unused
            throw RuntimeException("not implemented")
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: IntArray,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            val pixelsOut = output?.cachedPixelsI
            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                var x = offX
                while (x < offX + thisWidth) {
                    // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                    val color = pixels[c]
                    var r = (color shr 16) and 0xFF
                    var g = (color shr 8) and 0xFF
                    var b = color and 0xFF

                    var fr = r.toFloat()
                    var fg = g.toFloat()
                    var fb = b.toFloat()
                    var value = max(fr.toDouble(), fg.toDouble()).toFloat()
                    value = max(value.toDouble(), fb.toDouble()).toFloat()

                    // apply piecewise function of gain vs gamma
                    if (value <= lowX) {
                        // don't scale
                    } else if (value <= midX) {
                        //float alpha = (value-lowX)/(midX-lowX);
                        //float newValue = (1.0-alpha)*lowX + alpha*gain*midX;
                        // gain_A and gain_B should be set so that newValue meets the commented out code above
                        // This code is critical for performance!

                        fr *= (gainA + gainB / value)
                        fg *= (gainA + gainB / value)
                        fb *= (gainA + gainB / value)
                    } else {
                        // use LUT for performance
                        /*float newValue =  (float)Math.pow(value/maxX, gamma) * 255.0f;
                        float gammaScale = newValue / value;*/
                        val gammaScale = valueToGammaScaleLut[(value + 0.5f).toInt()]

                        fr *= gammaScale
                        fg *= gammaScale
                        fb *= gammaScale
                    }

                    r = (fr + 0.5f).toInt()
                    g = (fg + 0.5f).toInt()
                    b = (fb + 0.5f).toInt()

                    r = max(0.0, min(255.0, r.toDouble())).toInt()
                    g = max(0.0, min(255.0, g.toDouble())).toInt()
                    b = max(0.0, min(255.0, b.toDouble())).toInt()

                    // this code is performance critical; note it's faster to avoid calls to Color.argb()
                    pixelsOut?.set(c, (255 shl 24) or (r shl 16) or (g shl 8) or b)
                    x++
                    c++
                }
                y++
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: ByteArray?,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            val pixelsOut = output?.cachedPixelsB
            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                var x = offX
                while (x < offX + thisWidth) {
                    var r = pixels?.get(c)?.toInt() ?: 0
                    var g = pixels?.get(c + 1)?.toInt() ?: 0
                    var b = pixels?.get(c + 2)?.toInt() ?: 0
                    // bytes are signed!
                    if (r < 0) r += 256
                    if (g < 0) g += 256
                    if (b < 0) b += 256

                    var fr = r.toFloat()
                    var fg = g.toFloat()
                    var fb = b.toFloat()
                    var value = max(fr.toDouble(), fg.toDouble()).toFloat()
                    value = max(value.toDouble(), fb.toDouble()).toFloat()

                    // apply piecewise function of gain vs gamma
                    if (value <= lowX) {
                        // don't scale
                    } else if (value <= midX) {
                        //float alpha = (value-lowX)/(midX-lowX);
                        //float newValue = (1.0-alpha)*lowX + alpha*gain*midX;
                        // gain_A and gain_B should be set so that newValue meets the commented out code above
                        // This code is critical for performance!

                        fr *= (gainA + gainB / value)
                        fg *= (gainA + gainB / value)
                        fb *= (gainA + gainB / value)
                    } else {
                        val newValue =
                            (value / maxX).toDouble().pow(gamma.toDouble()).toFloat() * 255.0f

                        val gammaScale = newValue / value
                        fr *= gammaScale
                        fg *= gammaScale
                        fb *= gammaScale
                    }

                    r = (fr + 0.5f).toInt()
                    g = (fg + 0.5f).toInt()
                    b = (fb + 0.5f).toInt()

                    r = max(0.0, min(255.0, r.toDouble())).toInt()
                    g = max(0.0, min(255.0, g.toDouble())).toInt()
                    b = max(0.0, min(255.0, b.toDouble())).toInt()

                    pixelsOut?.set(c, r.toByte())
                    pixelsOut?.set(c + 1, g.toByte())
                    pixelsOut?.set(c + 2, b.toByte())
                    pixelsOut?.set(c + 3, 255.toByte())
                    x++
                    c += 4
                }
                y++
            }
        }
    }

    /** Class to store floating point rgb values, along with luminance.
     */
    private class RGBfLuminance {
        var fr: Float = 0f
        var fg: Float = 0f
        var fb: Float = 0f
        var lum: Float = 0f

        // set from RGB101010 format
        /*void setRGB101010(int rgb) {
            this.fr = (float)((rgb) & 0x3FF) / 4.0f;
            this.fg = (float)((rgb >> 10) & 0x3FF) / 4.0f;
            this.fb = (float)((rgb >> 20) & 0x3FF) / 4.0f;
            this!!.lum = Math.max(Math.max(fr, fg), fb);
        }*/
        fun setRGB(fr: Float, fg: Float, fb: Float) {
            this.fr = fr
            this.fg = fg
            this.fb = fb
            this.lum = max(max(fr.toDouble(), fg.toDouble()), fb.toDouble()).toFloat()
        }

        fun setRGB(pixelsInRgbf: FloatArray, x: Int, y: Int, width: Int) {
            val indx = (y * width + x) * 3
            setRGB(pixelsInRgbf[indx], pixelsInRgbf[indx + 1], pixelsInRgbf[indx + 2])
        }
    }

    internal class AvgApplyFunction(// output
        private val pixelsRgbf: FloatArray, // new bitmap being added to the input
        private var bitmapNew: Bitmap,
        bitmapOrig: Bitmap,
        width: Int,
        height: Int,
        offsetXNew: Int,
        offsetYNew: Int,
        avgFactor: Float,
        wienerC: Float,
        wienerCCutoff: Float
    ) : JavaImageProcessing.ApplyFunctionInterface {
        private lateinit var fastBitmapNew: Array<FastAccessBitmap?>
        private val bitmapOrig: Bitmap // original bitmap (first image)
        private lateinit var fastBitmapOrig: Array<FastAccessBitmap?>
        private val width: Int
        private val height: Int
        private val offsetXNew: Int
        private val offsetYNew: Int
        private val avgFactor: Float
        private val wienerC: Float
        private val wienerCCutoff: Float

        val radius: Int = 2 // must be less than the radius we actually read from below

        //final int nPixelsC = 5; // number of pixels we read from
        //final int [] sampleX = new int[]{-2, 2, 0, -2, 2};
        //final int [] sampleY = new int[]{-2, -2, 0, 2, 2};
        /*final float [] pixelsAvgFr;
        final float [] pixelsAvgFg;
        final float [] pixelsAvgFb;*/
        init {
            this.bitmapNew = bitmapNew
            this.bitmapOrig = bitmapOrig
            this.width = width
            this.height = height
            this.offsetXNew = offsetXNew
            this.offsetYNew = offsetYNew
            this.avgFactor = avgFactor
            this.wienerC = wienerC
            this.wienerCCutoff = wienerCCutoff
            /*this.pixelsAvgFr = new float[width];
            this.pixelsAvgFg = new float[width];
            this.pixelsAvgFb = new float[width];*/
        }

        override fun init(nThreads: Int) {
            fastBitmapNew = arrayOfNulls(nThreads)
            fastBitmapOrig = arrayOfNulls(nThreads)

            for (i in 0..<nThreads) {
                fastBitmapNew[i] = FastAccessBitmap(bitmapNew)
                fastBitmapOrig[i] = FastAccessBitmap(bitmapOrig)
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            apply(output, threadIndex, IntArray(0), offX, offY, thisWidth, thisHeight)
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: IntArray,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            val avgFactorp1 = avgFactor + 1.0f
            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                /*if( MyDebug.LOG )
                                   Log.d(TAG, "y = " + y);*/
                var pixelsRgbfIndx = 3 * y * width
                if (y + offsetYNew !in 0..<height) {
                    if (pixels.isNotEmpty()) {
                        var x = offX
                        while (x < offX + thisWidth) {
                            // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                            val color = pixels[c]
                            /*this.pixelsRgbf[3*(y*width + x)] = (float)((color >> 16) & 0xFF);
                            this.pixelsRgbf[3*(y*width + x)+1] = (float)((color >> 8) & 0xFF);
                            this.pixelsRgbf[3*(y*width + x)+2] = (float)(color & 0xFF);*/
                            pixelsRgbf[pixelsRgbfIndx] = ((color shr 16) and 0xFF).toFloat()
                            pixelsRgbf[pixelsRgbfIndx + 1] = ((color shr 8) and 0xFF).toFloat()
                            pixelsRgbf[pixelsRgbfIndx + 2] = (color and 0xFF).toFloat()
                            x++
                            c++
                            pixelsRgbfIndx += 3
                        }
                    }
                    y++
                    // else leave pixelsRgbf unchanged for this row
                    continue
                }

                fastBitmapOrig[threadIndex]?.getPixel(
                    0,
                    min((y + 2), (height - 1))
                ) // force cache to cover rows needed by this row
                val bitmapOrigCacheY: Int = fastBitmapOrig[threadIndex]!!.cacheY
                val yRelBitmapOrigCache = y - bitmapOrigCacheY
                val bitmapOrigCachePixels: IntArray =
                    fastBitmapOrig[threadIndex]!!.cachedPixelsI

                val yNew = y + offsetYNew

                //fastBitmapNew[threadIndex].getPixel(0, y+offsetYNew); // force cache to cover row y
                fastBitmapNew[threadIndex]?.getPixel(
                    0,
                    min((yNew + 2), (height - 1))
                ) // force cache to cover rows needed by this row
                val bitmapNewCacheY: Int = fastBitmapNew[threadIndex]!!.cacheY
                val yRelBitmapNewCache = yNew - bitmapNewCacheY
                val bitmapNewCachePixels: IntArray =
                    fastBitmapNew[threadIndex]!!.cachedPixelsI

                /*int x = offX;
                for(;x+offsetXNew < 0;x++,c++) {
                    // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                    int color = pixels[c];
                    this.pixelsRgbf[3*(y*width + x)] = (float)((color >> 16) & 0xFF);
                    this.pixelsRgbf[3*(y*width + x)+1] = (float)((color >> 8) & 0xFF);
                    this.pixelsRgbf[3*(y*width + x)+2] = (float)(color & 0xFF);
                }
                for(;x<offX+thisWidth;x++,c++) {*/

                /*int savedC = c;
                int savedPixelsRgbfIndx = pixelsRgbfIndx;
                if( pixels != null ) {
                    for(int x=offX;x<offX+thisWidth;x++,pixelsRgbfIndx+=3) {
                        // read from integer format
                        int color = pixels[c++];
                        pixelsAvgFr[x] = (float)((color >> 16) & 0xFF);
                        pixelsAvgFg[x] = (float)((color >> 8) & 0xFF);
                        pixelsAvgFb[x] = (float)(color & 0xFF);
                    }
                }
                else {
                    for(int x=offX;x<offX+thisWidth;x++,pixelsRgbfIndx+=3) {
                        // read from floating point format
                        pixelsAvgFr[x] = this.pixelsRgbf[pixelsRgbfIndx];
                        pixelsAvgFg[x] = this.pixelsRgbf[pixelsRgbfIndx+1];
                        pixelsAvgFb[x] = this.pixelsRgbf[pixelsRgbfIndx+2];
                    }
                }
                pixelsRgbfIndx = savedPixelsRgbfIndx;
                c = savedC;*/
                var x = offX
                while (x < offX + thisWidth) {
                    // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                    /*int color = pixels[c];
                    float pixelAvgFr = (float)((color >> 16) & 0xFF);
                    float pixelAvgFg = (float)((color >> 8) & 0xFF);
                    float pixelAvgFb = (float)(color & 0xFF);*/
                    var pixelAvgFr: Float
                    var pixelAvgFg: Float
                    var pixelAvgFb: Float
                    if (pixels.isNotEmpty()) {
                        // read from integer format
                        val color = pixels[c++]
                        pixelAvgFr = ((color shr 16) and 0xFF).toFloat()
                        pixelAvgFg = ((color shr 8) and 0xFF).toFloat()
                        pixelAvgFb = (color and 0xFF).toFloat()
                    } else {
                        // read from floating point format
                        pixelAvgFr = this.pixelsRgbf[pixelsRgbfIndx]
                        pixelAvgFg = this.pixelsRgbf[pixelsRgbfIndx + 1]
                        pixelAvgFb = this.pixelsRgbf[pixelsRgbfIndx + 2]
                    }

                    /*float pixelAvgFr = pixelsAvgFr[x];
                    float pixelAvgFg = pixelsAvgFg[x];
                    float pixelAvgFb = pixelsAvgFb[x];*/
                    val xNew = x + offsetXNew
                    if (xNew in 0..<width) {
                        //if( xNew < width ) {
                        //{
                        //int pixelNew = bitmap_new.getPixel(x+offsetXNew, y+offsetYNew);
                        //int pixelNew = fastBitmapNew[threadIndex].getPixel(x+offsetXNew, y+offsetYNew);
                        //int pixelNew = bitmapNewCachePixels[(y+offsetYNew-bitmapNewCacheY)*width+(x+offsetXNew)];
                        val pixelNew =
                            bitmapNewCachePixels[yRelBitmapNewCache * width + xNew]

                        var pixelNewFr = ((pixelNew shr 16) and 0xFF).toFloat()
                        var pixelNewFg = ((pixelNew shr 8) and 0xFF).toFloat()
                        var pixelNewFb = (pixelNew and 0xFF).toFloat()

                        // temporal merging
                        // smaller value of wiener_C means stronger filter (i.e., less averaging)

                        // diff based on rgb
                        //float diffR = pixelAvgFr - pixelNewFr;
                        //float diffG = pixelAvgFg - pixelNewFg;
                        //float diffB = pixelAvgFb - pixelNewFb;
                        //float L = diffR*diffR + diffG*diffG + diffB*diffB;

                        // diff based on neighbourhood [sampling a subset of pixels]
                        // this helps testAvg24, testAvg28, testAvg31, testAvg33, testAvg39
                        var varL = 0.0f
                        if (x - radius >= 0 && x + radius < width && y - radius >= 0 && y + radius < height && xNew - radius >= 0 && xNew + radius < width && yNew - radius >= 0 && yNew + radius < height) {
                            val nPixelsC = 5 // number of pixels we read from
                            var pixelOrigFr: Float
                            var pixelOrigFg: Float
                            var pixelOrigFb: Float
                            var pixelNewSampleFr: Float
                            var pixelNewSampleFg: Float
                            var pixelNewSampleFb: Float

                            // average of diffs:
                            /*for(int i=0;i<nPixelsC;i++) {
                                int sx = sampleX[i];
                                int sy = sampleY[i];

                                //int pixelOrig = bitmap_orig.getPixel(x+sx, y+sy);
                                //int pixelOrig = fastBitmapOrig[threadIndex].getPixel(x+sx, y+sy);
                                //int pixelOrig = bitmapOrigCachePixels[(y+sy-bitmapOrigCacheY)*width+(x+sx)];
                                int pixelOrig = bitmapOrigCachePixels[(yRelBitmapOrigCache+sy)*width+(x+sx)];
                                float pixelOrigFr = (float)((pixelOrig >> 16) & 0xFF);
                                float pixelOrigFg = (float)((pixelOrig >> 8) & 0xFF);
                                float pixelOrigFb = (float)(pixelOrig & 0xFF);

                                float pixelNewSampleFr, pixelNewSampleFg, pixelNewSampleFb;
                                if( sx == 0 && sy == 0 ) {
                                    pixelNewSampleFr = pixelNewFr;
                                    pixelNewSampleFg = pixelNewFg;
                                    pixelNewSampleFb = pixelNewFb;
                                }
                                else {
                                    //int pixelNewSample = bitmap_new.getPixel(ox+sx, oy+sy);
                                    //int pixelNewSample = fastBitmapNew[threadIndex].getPixel(ox+sx, oy+sy);
                                    //int pixelNewSample = bitmapNewCachePixels[(oy+sy-bitmapNewCacheY)*width+(ox+sx)];
                                    //int pixelNewSample = bitmapNewCachePixels[(yNew+sy-bitmapNewCacheY)*width+(xNew+sx)];
                                    int pixelNewSample = bitmapNewCachePixels[(yRelBitmapNewCache+sy)*width+(xNew+sx)];
                                    pixelNewSampleFr = (float)((pixelNewSample >> 16) & 0xFF);
                                    pixelNewSampleFg = (float)((pixelNewSample >> 8) & 0xFF);
                                    pixelNewSampleFb = (float)(pixelNewSample & 0xFF);
                                }

                                float diffR = pixelOrigFr - pixelNewSampleFr;
                                float diffG = pixelOrigFg - pixelNewSampleFg;
                                float diffB = pixelOrigFb - pixelNewSampleFb;
                                L += diffR*diffR + diffG*diffG + diffB*diffB;
                            }*/

                            // unroll loop for performance:
                            var pixelOrig =
                                bitmapOrigCachePixels[(yRelBitmapOrigCache - 2) * width + (x - 2)]
                            pixelOrigFr = ((pixelOrig shr 16) and 0xFF).toFloat()
                            pixelOrigFg = ((pixelOrig shr 8) and 0xFF).toFloat()
                            pixelOrigFb = (pixelOrig and 0xFF).toFloat()
                            var pixelNewSample =
                                bitmapNewCachePixels[(yRelBitmapNewCache - 2) * width + (xNew - 2)]
                            pixelNewSampleFr = ((pixelNewSample shr 16) and 0xFF).toFloat()
                            pixelNewSampleFg = ((pixelNewSample shr 8) and 0xFF).toFloat()
                            pixelNewSampleFb = (pixelNewSample and 0xFF).toFloat()
                            var diffR = pixelOrigFr - pixelNewSampleFr
                            var diffG = pixelOrigFg - pixelNewSampleFg
                            var diffB = pixelOrigFb - pixelNewSampleFb
                            varL += diffR * diffR + diffG * diffG + diffB * diffB

                            pixelOrig =
                                bitmapOrigCachePixels[(yRelBitmapOrigCache - 2) * width + (x + 2)]
                            pixelOrigFr = ((pixelOrig shr 16) and 0xFF).toFloat()
                            pixelOrigFg = ((pixelOrig shr 8) and 0xFF).toFloat()
                            pixelOrigFb = (pixelOrig and 0xFF).toFloat()
                            pixelNewSample =
                                bitmapNewCachePixels[(yRelBitmapNewCache - 2) * width + (xNew + 2)]
                            pixelNewSampleFr = ((pixelNewSample shr 16) and 0xFF).toFloat()
                            pixelNewSampleFg = ((pixelNewSample shr 8) and 0xFF).toFloat()
                            pixelNewSampleFb = (pixelNewSample and 0xFF).toFloat()
                            diffR = pixelOrigFr - pixelNewSampleFr
                            diffG = pixelOrigFg - pixelNewSampleFg
                            diffB = pixelOrigFb - pixelNewSampleFb
                            varL += diffR * diffR + diffG * diffG + diffB * diffB

                            pixelOrig =
                                bitmapOrigCachePixels[(yRelBitmapOrigCache) * width + (x)]
                            pixelOrigFr = ((pixelOrig shr 16) and 0xFF).toFloat()
                            pixelOrigFg = ((pixelOrig shr 8) and 0xFF).toFloat()
                            pixelOrigFb = (pixelOrig and 0xFF).toFloat()
                            pixelNewSampleFr = pixelNewFr
                            pixelNewSampleFg = pixelNewFg
                            pixelNewSampleFb = pixelNewFb
                            diffR = pixelOrigFr - pixelNewSampleFr
                            diffG = pixelOrigFg - pixelNewSampleFg
                            diffB = pixelOrigFb - pixelNewSampleFb
                            varL += diffR * diffR + diffG * diffG + diffB * diffB

                            pixelOrig =
                                bitmapOrigCachePixels[(yRelBitmapOrigCache + 2) * width + (x - 2)]
                            pixelOrigFr = ((pixelOrig shr 16) and 0xFF).toFloat()
                            pixelOrigFg = ((pixelOrig shr 8) and 0xFF).toFloat()
                            pixelOrigFb = (pixelOrig and 0xFF).toFloat()
                            pixelNewSample =
                                bitmapNewCachePixels[(yRelBitmapNewCache + 2) * width + (xNew - 2)]
                            pixelNewSampleFr = ((pixelNewSample shr 16) and 0xFF).toFloat()
                            pixelNewSampleFg = ((pixelNewSample shr 8) and 0xFF).toFloat()
                            pixelNewSampleFb = (pixelNewSample and 0xFF).toFloat()
                            diffR = pixelOrigFr - pixelNewSampleFr
                            diffG = pixelOrigFg - pixelNewSampleFg
                            diffB = pixelOrigFb - pixelNewSampleFb
                            varL += diffR * diffR + diffG * diffG + diffB * diffB

                            pixelOrig =
                                bitmapOrigCachePixels[(yRelBitmapOrigCache + 2) * width + (x + 2)]
                            pixelOrigFr = ((pixelOrig shr 16) and 0xFF).toFloat()
                            pixelOrigFg = ((pixelOrig shr 8) and 0xFF).toFloat()
                            pixelOrigFb = (pixelOrig and 0xFF).toFloat()
                            pixelNewSample =
                                bitmapNewCachePixels[(yRelBitmapNewCache + 2) * width + (xNew + 2)]
                            pixelNewSampleFr = ((pixelNewSample shr 16) and 0xFF).toFloat()
                            pixelNewSampleFg = ((pixelNewSample shr 8) and 0xFF).toFloat()
                            pixelNewSampleFb = (pixelNewSample and 0xFF).toFloat()
                            diffR = pixelOrigFr - pixelNewSampleFr
                            diffG = pixelOrigFg - pixelNewSampleFg
                            diffB = pixelOrigFb - pixelNewSampleFb
                            varL += diffR * diffR + diffG * diffG + diffB * diffB

                            varL /= nPixelsC.toFloat()
                        } else {
                            val diffR = pixelAvgFr - pixelNewFr
                            val diffG = pixelAvgFg - pixelNewFg
                            val diffB = pixelAvgFb - pixelNewFb
                            varL = diffR * diffR + diffG * diffG + diffB * diffB
                        }

                        // diff based on computeDiff (separate pass on scaled down alignment bitmaps)
                        //int alignX = x/scaleAlignSize;
                        //int alignY = y/scaleAlignSize;
                        //float L = rsGetElementAt_float(allocationDiffs, alignX, alignY);

                        // debug mode: only works if limited to 2 images being merged
                        /*L = sqrt(L);
                        L = fmin(L, 255.0f);
                        pixel_new_f.r = L;
                        pixel_new_f.g = L;
                        pixel_new_f.b = L;
                        return pixelNewF;*/

                        // diff based on luminance
                        /*float valueAvg = fmax(pixel_avg_f.r, pixel_avg_f.g);
                        valueAvg = fmax(valueAvg, pixel_avg_f.b);
                        float valueNew = fmax(pixel_new_f.r, pixel_new_f.g);
                        valueNew = fmax(valueNew, pixel_new_f.b);
                        float diff = valueAvg - valueNew;
                        float L = 3.0f*diff*diff;*/
                        //L = 0.0f; // test no wiener filter

                        /*float valueAvg = fmax(pixel_avg_f.r, pixel_avg_f.g);
                        valueAvg = fmax(valueAvg, pixel_avg_f.b);
                        float valueNew = fmax(pixel_new_f.r, pixel_new_f.g);
                        valueNew = fmax(valueNew, pixel_new_f.b);
                        //float value = 0.5f*(valueAvg + valueNew)/127.5f;
                        float value = 0.5f*(valueAvg + valueNew);
                        value = fmax(value, 8.0f);
                        value = fmin(value, 32.0f);
                        value /= 32.0f;*/
                        //float value = 1.0f;

                        // relative scaling:
                        /*float valueAvg = fmax(pixel_avg_f.r, pixel_avg_f.g);
                        valueAvg = fmax(valueAvg, pixel_avg_f.b);
                        float valueNew = fmax(pixel_new_f.r, pixel_new_f.g);
                        valueNew = fmax(valueNew, pixel_new_f.b);
                        float value = 0.5*(valueAvg + valueNew);
                        //float value = fmax(valueAvg, valueNew);
                        value = fmax(value, 64.0f);
                        L *= 64.0f/value;
                        //float L_scale = 64.0f/value;
                        //L *= L_scale*L_scale;
                        */

                        //L = 0.0f; // test no deghosting
                        if (varL > wienerCCutoff) {
                            // error too large, so no contribution for new image pixel
                            // stick with pixelAvg
                            // reduces ghosting in: testAvg13, testAvg25, testAvg26, testAvg29, testAvg31
                        } else {
                            val weight =
                                varL / (varL + wienerC) // lower weight means more averaging
                            val weight1 = 1.0f - weight
                            pixelNewFr = weight * pixelAvgFr + weight1 * pixelNewFr
                            pixelNewFg = weight * pixelAvgFg + weight1 * pixelNewFg
                            pixelNewFb = weight * pixelAvgFb + weight1 * pixelNewFb

                            /*float weight = L/(L+wiener_C); // lower weight means more averaging
                            weight = fmin(weight, maxWeight);
                            if( L > wiener_C_cutoff ) {
                                // error too large, so no contribution for new image pixel
                                // reduces ghosting in: testAvg13, testAvg25, testAvg26, testAvg29, testAvg31
                                weight = maxWeight;
                            }
                            pixelNewF = weight * pixelAvgF + (1.0-weight) * pixelNewF;*/
                            pixelAvgFr = (avgFactor * pixelAvgFr + pixelNewFr) / avgFactorp1
                            pixelAvgFg = (avgFactor * pixelAvgFg + pixelNewFg) / avgFactorp1
                            pixelAvgFb = (avgFactor * pixelAvgFb + pixelNewFb) / avgFactorp1
                        }
                    }

                    /*this.pixelsRgbf[3*(y*width + x)] = pixelAvgFr;
                    this.pixelsRgbf[3*(y*width + x)+1] = pixelAvgFg;
                    this.pixelsRgbf[3*(y*width + x)+2] = pixelAvgFb;*/
                    pixelsRgbf[pixelsRgbfIndx] = pixelAvgFr
                    pixelsRgbf[pixelsRgbfIndx + 1] = pixelAvgFg
                    pixelsRgbf[pixelsRgbfIndx + 2] = pixelAvgFb
                    x++
                    pixelsRgbfIndx += 3
                }
                y++
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: ByteArray?,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // unused
            throw RuntimeException("not implemented")
        }
    }

    internal class AvgBrightenApplyFunction( /*int [] pixelsIn,*/
        //private final int [] pixelsIn;
        //this.pixelsIn = pixelsIn;
                                             private val pixelsInRgbf: FloatArray,
                                             private val width: Int,
                                             private val height: Int,
                                             gain: Float,
                                             gamma: Float,
                                             lowX: Float,
                                             midX: Float,
                                             maxX: Float,
                                             private val medianFilterStrength: Float,
                                             private val blackLevel: Float
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val brighten =
            DROBrightenApplyFunction(gain, gamma, lowX, midX, maxX)
        private val whiteLevel = 255.0f / (255.0f - blackLevel)
        private val valueToGammaScaleLut = FloatArray(256) // look up table for performance

        init {
            for (value in 0..255) {
                val newValue = (value / brighten.maxX).toDouble().pow(brighten.gamma.toDouble())
                    .toFloat() * 255.0f
                valueToGammaScaleLut[value] = newValue / value
            }
        }

        override fun init(nThreads: Int) {
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            val pixelsOut = output?.cachedPixelsI
            val rgbfLuminances: Array<RGBfLuminance?> = arrayOfNulls(5)
            for (i in rgbfLuminances.indices) {
                rgbfLuminances[i] = RGBfLuminance()
            }
            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                //int indx = y*width+offX;
                var indx = (y * width + offX) * 3
                var x = offX
                while (x < offX + thisWidth) {
                    /*int color = pixelsIn[indx++];
                                       float fr = (float)((color) & 0x3FF) / 4.0f;
                                       float fg = (float)((color >> 10) & 0x3FF) / 4.0f;
                                       float fb = (float)((color >> 20) & 0x3FF) / 4.0f;*/
                    var fr = pixelsInRgbf[indx++]
                    var fg = pixelsInRgbf[indx++]
                    var fb = pixelsInRgbf[indx++]

                    /*int r = (int)(fr+0.5f);
                    int g = (int)(fg+0.5f);
                    int b = (int)(fb+0.5f);

                    r = Math.max(0, Math.min(255, r));
                    g = Math.max(0, Math.min(255, g));
                    b = Math.max(0, Math.min(255, b));*/
                    if (x > 0 && x < width - 1 && y > 0 && y < height - 1) {
                        // median filter for noise reduction
                        // performs better than spatial filter; reduces black/white speckles in: testAvg23,
                        // testAvg28, testAvg31, testAvg33
                        // note that one has to typically zoom to 400% to see the improvement

                        /*int color0 = pixelsIn[(y-1)*width+(x)];
                        int color1 = pixelsIn[(y)*width+(x-1)];
                        int color2 = color;
                        int color3 = pixelsIn[(y)*width+(x+1)];
                        int color4 = pixelsIn[(y+1)*width+(x)];

                        rgbfLuminances[0]?.setRGB101010(color0);
                        rgbfLuminances[1]?.setRGB101010(color1);
                        rgbfLuminances[2]?.setRGB101010(color2);
                        rgbfLuminances[3]?.setRGB101010(color3);
                        rgbfLuminances[4]?.setRGB101010(color4);*/

                        rgbfLuminances[0]?.setRGB(pixelsInRgbf, x, y - 1, width)
                        rgbfLuminances[1]?.setRGB(pixelsInRgbf, x - 1, y, width)
                        rgbfLuminances[2]?.setRGB(fr, fg, fb)
                        rgbfLuminances[3]?.setRGB(pixelsInRgbf, x + 1, y, width)
                        rgbfLuminances[4]?.setRGB(pixelsInRgbf, x, y + 1, width)

                        // if changing this code, see if the test code in UnitTest.findMedian() should be updated

                        // new faster version:
                        if (rgbfLuminances[0]!!.lum > rgbfLuminances[1]!!.lum) {
                            val tempP = rgbfLuminances[0]
                            rgbfLuminances[0] = rgbfLuminances[1]
                            rgbfLuminances[1] = tempP
                        }
                        if (rgbfLuminances[3]!!.lum > rgbfLuminances[4]!!.lum) {
                            val tempP = rgbfLuminances[3]
                            rgbfLuminances[3] = rgbfLuminances[4]
                            rgbfLuminances[4] = tempP
                        }
                        if (rgbfLuminances[0]!!.lum > rgbfLuminances[3]!!.lum) {
                            var tempP = rgbfLuminances[0]
                            rgbfLuminances[0] = rgbfLuminances[3]
                            rgbfLuminances[3] = tempP

                            tempP = rgbfLuminances[1]
                            rgbfLuminances[1] = rgbfLuminances[4]
                            rgbfLuminances[4] = tempP
                        }
                        if (rgbfLuminances[1]!!.lum > rgbfLuminances[2]!!.lum) {
                            if (rgbfLuminances[2]!!.lum > rgbfLuminances[3]!!.lum) {
                                if (rgbfLuminances[2]!!.lum > rgbfLuminances[4]!!.lum) {
                                    val tempP = rgbfLuminances[2]
                                    rgbfLuminances[2] = rgbfLuminances[4]
                                    rgbfLuminances[4] = tempP
                                }
                                // else median is rgbfLuminances[2]
                            } else {
                                if (rgbfLuminances[1]!!.lum > rgbfLuminances[3]!!.lum) {
                                    val tempP = rgbfLuminances[2]
                                    rgbfLuminances[2] = rgbfLuminances[3]
                                    rgbfLuminances[3] = tempP
                                } else {
                                    val tempP = rgbfLuminances[2]
                                    rgbfLuminances[2] = rgbfLuminances[1]
                                    rgbfLuminances[1] = tempP
                                }
                            }
                        } else {
                            if (rgbfLuminances[1]!!.lum > rgbfLuminances[3]!!.lum) {
                                if (rgbfLuminances[1]!!.lum > rgbfLuminances[4]!!.lum) {
                                    val tempP = rgbfLuminances[2]
                                    rgbfLuminances[2] = rgbfLuminances[4]
                                    rgbfLuminances[4] = tempP
                                } else {
                                    val tempP = rgbfLuminances[2]
                                    rgbfLuminances[2] = rgbfLuminances[1]
                                    rgbfLuminances[1] = tempP
                                }
                            } else {
                                if (rgbfLuminances[2]!!.lum > rgbfLuminances[3]!!.lum) {
                                    val tempP = rgbfLuminances[2]
                                    rgbfLuminances[2] = rgbfLuminances[3]
                                    rgbfLuminances[3] = tempP
                                }
                                // else median is rgbfLuminances[2]
                            }
                        }

                        // original slower version:
                        /*if( rgbfLuminances[0]!!.lum > rgbfLuminances[1]!!.lum ) {
                            RGBf_luminance temp = rgbfLuminances[0];
                            rgbfLuminances[0] = rgbfLuminances[1];
                            rgbfLuminances[1] = temp;
                        }
                        if( rgbfLuminances[0]!!.lum > rgbfLuminances[2]!!.lum ) {
                            RGBf_luminance temp = rgbfLuminances[0];
                            rgbfLuminances[0] = rgbfLuminances[2];
                            rgbfLuminances[2] = temp;
                        }
                        if( rgbfLuminances[0]!!.lum > rgbfLuminances[3]!!.lum ) {
                            RGBf_luminance temp = rgbfLuminances[0];
                            rgbfLuminances[0] = rgbfLuminances[3];
                            rgbfLuminances[3] = temp;
                        }
                        if( rgbfLuminances[0]!!.lum > rgbfLuminances[4]!!.lum ) {
                            RGBf_luminance temp = rgbfLuminances[0];
                            rgbfLuminances[0] = rgbfLuminances[4];
                            rgbfLuminances[4] = temp;
                        }
                        //
                        if( rgbfLuminances[1]!!.lum > rgbfLuminances[2]!!.lum ) {
                            RGBf_luminance temp = rgbfLuminances[1];
                            rgbfLuminances[1] = rgbfLuminances[2];
                            rgbfLuminances[2] = temp;
                        }
                        if( rgbfLuminances[1]!!.lum > rgbfLuminances[3]!!.lum ) {
                            RGBf_luminance temp = rgbfLuminances[1];
                            rgbfLuminances[1] = rgbfLuminances[3];
                            rgbfLuminances[3] = temp;
                        }
                        if( rgbfLuminances[1]!!.lum > rgbfLuminances[4]!!.lum ) {
                            RGBf_luminance temp = rgbfLuminances[1];
                            rgbfLuminances[1] = rgbfLuminances[4];
                            rgbfLuminances[4] = temp;
                        }
                        //
                        if( rgbfLuminances[2]!!.lum > rgbfLuminances[3]!!.lum ) {
                            RGBf_luminance temp = rgbfLuminances[2];
                            rgbfLuminances[2] = rgbfLuminances[3];
                            rgbfLuminances[3] = temp;
                        }
                        if( rgbfLuminances[2]!!.lum > rgbfLuminances[4]!!.lum ) {
                            RGBf_luminance temp = rgbfLuminances[2];
                            rgbfLuminances[2] = rgbfLuminances[4];
                            rgbfLuminances[4] = temp;
                        }
                        // don't care about sorting p3 and p4
                        */
                        fr =
                            (1.0f - medianFilterStrength) * fr + medianFilterStrength * rgbfLuminances[2]!!.fr
                        fg =
                            (1.0f - medianFilterStrength) * fg + medianFilterStrength * rgbfLuminances[2]!!.fg
                        fb =
                            (1.0f - medianFilterStrength) * fb + medianFilterStrength * rgbfLuminances[2]!!.fb
                    }

                    run {
                        // spatial noise reduction filter, colour only
                        // if making changes to this (especially radius, C), run AvgTests - in particular, pay close
                        // attention to:
                        // testAvg6: don't want to make the postcard too blurry
                        // testAvg8: zoom in to 600%, ensure still appears reasonably sharp
                        // testAvg23: ensure we do reduce the noise, e.g., view around "vicks", without making the
                        // text blurry
                        // testAvg24: want to reduce the colour noise near the wall, but don't blur out detail, e.g.
                        // at the flowers
                        // testAvg31
                        // Also need to be careful of performance.
                        //float oldValue = Math.max(fr, fg);
                        //oldValue = Math.max(oldValue, fb);
                        val oldValue = fg // use only green component for performance
                        var sumFr = 0.0f
                        var sumFg = 0.0f
                        var sumFb = 0.0f
                        //int radius = 3;
                        val radius = 2
                        var count = 0
                        val sx = if (x >= radius) x - radius else 0
                        val ex = if (x < width - radius) x + radius else width - 1
                        val sy = if (y >= radius) y - radius else 0
                        val ey = if (y < height - radius) y + radius else height - 1
                        for (cy in sy..ey) {
                            var thisIndx = (cy * width + sx) * 3
                            for (cx in sx..ex) {
                                //if( cx >= 0 && cx < width && cy >= 0 && cy < height )
                                run {
                                    /*int thisPixel = pixelsIn[cy*width+cx];
                                                                   float thisFr = (float)((thisPixel) & 0x3FF) / 4.0f;
                                                                   float thisFg = (float)((thisPixel >> 10) & 0x3FF) / 4.0f;
                                                                   float thisFb = (float)((thisPixel >> 20) & 0x3FF) / 4.0f;*/
                                    var thisFr = pixelsInRgbf[thisIndx++]
                                    var thisFg = pixelsInRgbf[thisIndx++]
                                    var thisFb = pixelsInRgbf[thisIndx++]
                                    run {
                                        //float thisValue = Math.max(thisFr, thisFg);
                                        //thisValue = Math.max(thisValue, thisFb);
                                        val thisValue =
                                            thisFg // use only green component for performance
                                        if (thisValue > 0.5f) {
                                            val scale = oldValue / thisValue
                                            thisFr *= scale
                                            thisFg *= scale
                                            thisFb *= scale
                                        }
                                        /*if( thisFg > 0.5f ) {
                                            float scale = fg/thisFg;
                                            thisFr *= scale;
                                            thisFg *= scale;
                                            thisFb *= scale;
                                        }*/
                                        // use a wiener filter, so that more similar pixels have greater contribution
                                        // smaller value of C means stronger filter (i.e., less averaging)
                                        // for now set at same value as standard spatial filter above
                                        //final float C = 64.0f*64.0f/8.0f;
                                        //final float C = 512.0f;
                                        //final float C = 16.0f*16.0f/8.0f;
                                        val varC = 32.0f

                                        val diffR = fr - thisFr
                                        val diffG = fg - thisFg
                                        val diffB = fb - thisFb

                                        val varL = diffR * diffR + diffG * diffG + diffB * diffB
                                        //L = 0.0f; // test no wiener filter
                                        val weight = varL / (varL + varC)

                                        /*float weight1 = 1.0f-weight;
                                        thisFr = weight * fr + weight1 * thisFr;
                                        thisFg = weight * fg + weight1 * thisFg;
                                        thisFb = weight * fb + weight1 * thisFb;*/

                                        // faster version:
                                        thisFr += weight * diffR
                                        thisFg += weight * diffG
                                        thisFb += weight * diffB
                                    }
                                    sumFr += thisFr
                                    sumFg += thisFg
                                    sumFb += thisFb
                                    count++
                                }
                            }
                        }

                        fr = sumFr / count
                        fg = sumFg / count
                        fb = sumFb / count
                    }

                    run {
                        // sharpen
                        // helps: testAvg12, testAvg16, testAvg23, testAvg30, testAvg32
                        if (x >= 1 && x < width - 1 && y >= 1 && y < height - 1) {
                            /*int color00 = pixelsIn[(y-1)*width+(x-1)];
                            int color10 = pixelsIn[(y-1)*width+(x)];
                            int color20 = pixelsIn[(y-1)*width+(x+1)];

                            int color01 = pixelsIn[(y)*width+(x-1)];
                            int color21 = pixelsIn[(y)*width+(x+1)];

                            int color02 = pixelsIn[(y+1)*width+(x-1)];
                            int color12 = pixelsIn[(y+1)*width+(x)];
                            int color22 = pixelsIn[(y+1)*width+(x+1)];

                            float fr00 = (float)((color00) & 0x3FF) / 4.0f;
                            float fg00 = (float)((color00 >> 10) & 0x3FF) / 4.0f;
                            float fb00 = (float)((color00 >> 20) & 0x3FF) / 4.0f;
                            float fr10 = (float)((color10) & 0x3FF) / 4.0f;
                            float fg10 = (float)((color10 >> 10) & 0x3FF) / 4.0f;
                            float fb10 = (float)((color10 >> 20) & 0x3FF) / 4.0f;
                            float fr20 = (float)((color20) & 0x3FF) / 4.0f;
                            float fg20 = (float)((color20 >> 10) & 0x3FF) / 4.0f;
                            float fb20 = (float)((color20 >> 20) & 0x3FF) / 4.0f;

                            float fr01 = (float)((color01) & 0x3FF) / 4.0f;
                            float fg01 = (float)((color01 >> 10) & 0x3FF) / 4.0f;
                            float fb01 = (float)((color01 >> 20) & 0x3FF) / 4.0f;
                            float fr21 = (float)((color21) & 0x3FF) / 4.0f;
                            float fg21 = (float)((color21 >> 10) & 0x3FF) / 4.0f;
                            float fb21 = (float)((color21 >> 20) & 0x3FF) / 4.0f;

                            float fr02 = (float)((color02) & 0x3FF) / 4.0f;
                            float fg02 = (float)((color02 >> 10) & 0x3FF) / 4.0f;
                            float fb02 = (float)((color02 >> 20) & 0x3FF) / 4.0f;
                            float fr12 = (float)((color12) & 0x3FF) / 4.0f;
                            float fg12 = (float)((color12 >> 10) & 0x3FF) / 4.0f;
                            float fb12 = (float)((color12 >> 20) & 0x3FF) / 4.0f;
                            float fr22 = (float)((color22) & 0x3FF) / 4.0f;
                            float fg22 = (float)((color22 >> 10) & 0x3FF) / 4.0f;
                            float fb22 = (float)((color22 >> 20) & 0x3FF) / 4.0f;*/

                            val indx00 = ((y - 1) * width + (x - 1)) * 3
                            val indx10 = ((y - 1) * width + (x)) * 3
                            val indx20 = ((y - 1) * width + (x + 1)) * 3

                            val indx01 = ((y) * width + (x - 1)) * 3
                            val indx21 = ((y) * width + (x + 1)) * 3

                            val indx02 = ((y + 1) * width + (x - 1)) * 3
                            val indx12 = ((y + 1) * width + (x)) * 3
                            val indx22 = ((y + 1) * width + (x + 1)) * 3

                            val fr00 = pixelsInRgbf[indx00]
                            val fg00 = pixelsInRgbf[indx00 + 1]
                            val fb00 = pixelsInRgbf[indx00 + 2]
                            val fr10 = pixelsInRgbf[indx10]
                            val fg10 = pixelsInRgbf[indx10 + 1]
                            val fb10 = pixelsInRgbf[indx10 + 2]
                            val fr20 = pixelsInRgbf[indx20]
                            val fg20 = pixelsInRgbf[indx20 + 1]
                            val fb20 = pixelsInRgbf[indx20 + 2]

                            val fr01 = pixelsInRgbf[indx01]
                            val fg01 = pixelsInRgbf[indx01 + 1]
                            val fb01 = pixelsInRgbf[indx01 + 2]
                            val fr21 = pixelsInRgbf[indx21]
                            val fg21 = pixelsInRgbf[indx21 + 1]
                            val fb21 = pixelsInRgbf[indx21 + 2]

                            val fr02 = pixelsInRgbf[indx02]
                            val fg02 = pixelsInRgbf[indx02 + 1]
                            val fb02 = pixelsInRgbf[indx02 + 2]
                            val fr12 = pixelsInRgbf[indx12]
                            val fg12 = pixelsInRgbf[indx12 + 1]
                            val fb12 = pixelsInRgbf[indx12 + 2]
                            val fr22 = pixelsInRgbf[indx22]
                            val fg22 = pixelsInRgbf[indx22 + 1]
                            val fb22 = pixelsInRgbf[indx22 + 2]

                            val blurredFr =
                                (fr00 + fr10 + fr20 + fr01 + 8.0f * fr + fr21 + fr02 + fr12 + fr22) / 16.0f
                            val blurredFg =
                                (fg00 + fg10 + fg20 + fg01 + 8.0f * fg + fg21 + fg02 + fg12 + fg22) / 16.0f
                            val blurredFb =
                                (fb00 + fb10 + fb20 + fb01 + 8.0f * fb + fb21 + fb02 + fb12 + fb22) / 16.0f
                            val shiftFr = 1.5f * (fr - blurredFr)
                            val shiftFg = 1.5f * (fg - blurredFg)
                            val shiftFb = 1.5f * (fb - blurredFb)
                            val threshold2 = (8 * 8).toFloat()
                            if (shiftFr * shiftFr + shiftFg * shiftFg + shiftFb * shiftFb > threshold2) {
                                fr += shiftFr
                                fg += shiftFg
                                fb += shiftFb
                            }

                            fr = max(0.0, min(255.0, fr.toDouble())).toFloat()
                            fg = max(0.0, min(255.0, fg.toDouble())).toFloat()
                            fb = max(0.0, min(255.0, fb.toDouble())).toFloat()
                        }
                    }

                    fr -= blackLevel
                    fg -= blackLevel
                    fb -= blackLevel
                    fr *= whiteLevel
                    fg *= whiteLevel
                    fb *= whiteLevel
                    fr = max(0.0, min(255.0, fr.toDouble())).toFloat()
                    fg = max(0.0, min(255.0, fg.toDouble())).toFloat()
                    fb = max(0.0, min(255.0, fb.toDouble())).toFloat()

                    var value = max(fr.toDouble(), fg.toDouble()).toFloat()
                    value = max(value.toDouble(), fb.toDouble()).toFloat()

                    // apply piecewise function of gain vs gamma
                    if (value <= brighten.lowX) {
                        // don't scale
                    } else if (value <= brighten.midX) {
                        //float alpha = (value-lowX)/(midX-lowX);
                        //float newValue = (1.0-alpha)*lowX + alpha*gain*midX;
                        // gain_A and gain_B should be set so that newValue meets the commented out code above
                        // This code is critical for performance!

                        val scale = (brighten.gainA + brighten.gainB / value)
                        fr *= scale
                        fg *= scale
                        fb *= scale
                    } else {
                        // use LUT for performance
                        /*float newValue =  (float)Math.pow(value/brighten.maxX, brighten.gamma) * 255.0f;
                        float gammaScale = newValue / value;*/
                        val gammaScale = valueToGammaScaleLut[(value + 0.5f).toInt()]

                        fr *= gammaScale
                        fg *= gammaScale
                        fb *= gammaScale
                    }

                    var r = (fr + 0.5f).toInt()
                    var g = (fg + 0.5f).toInt()
                    var b = (fb + 0.5f).toInt()

                    r = max(0.0, min(255.0, r.toDouble())).toInt()
                    g = max(0.0, min(255.0, g.toDouble())).toInt()
                    b = max(0.0, min(255.0, b.toDouble())).toInt()

                    // this code is performance critical; note it's faster to avoid calls to Color.argb()
                    pixelsOut?.set(c, (255 shl 24) or (r shl 16) or (g shl 8) or b)
                    x++
                    c++
                }
                y++
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: IntArray,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // unused
            throw RuntimeException("not implemented")
            /*int [] pixelsOut = output?.cachedPixelsI;
            for(int y=offY,c=0;y<offY+thisHeight;y++) {
                for(int x=offX;x<offX+thisWidth;x++,c++) {
                    // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                    int color = pixels[c];
                    int r = (color >> 16) & 0xFF;
                    int g = (color >> 8) & 0xFF;
                    int b = color & 0xFF;

                    // this code is performance critical; note it's faster to avoid calls to Color.argb()
                    pixelsOut[c] = (255 << 24) | (r << 16) | (g << 8) | b;
                }
            }*/
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: ByteArray?,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // unused
            throw RuntimeException("not implemented")
        }
    }

    internal open class HDRApplyFunction(
        private val tonemapAlgorithm: TonemappingAlgorithm, // for Reinhard
        private val tonemapScale: Float, // for FU2
        private val valW: Float,
        private val linearScale: Float,
        private val bitmap0: Bitmap?,
        private val bitmap2: Bitmap?,
        val offsetX0: Int,
        val offsetY0: Int,
        val offsetX2: Int,
        val offsetY2: Int,
        val width: Int,
        val height: Int, parameterA: FloatArray, parameterB: FloatArray
    ) : JavaImageProcessing.ApplyFunctionInterface {
        lateinit var fastBitmap0: Array<FastAccessBitmap?>
        lateinit var fastBitmap2: Array<FastAccessBitmap?>
        var parameterA: FloatArray
        var parameterB: FloatArray

        init {
            if (parameterA.size != parameterB.size) {
                throw RuntimeException("unequal parameter lengths")
            }
            this.parameterA = FloatArray(parameterA.size)
            System.arraycopy(parameterA, 0, this.parameterA, 0, parameterA.size)
            this.parameterB = FloatArray(parameterB.size)
            System.arraycopy(parameterB, 0, this.parameterB, 0, parameterB.size)
        }

        override fun init(nThreads: Int) {
            fastBitmap0 = arrayOfNulls(nThreads)
            if (bitmap2 != null) fastBitmap2 =
                arrayOfNulls(nThreads)
            for (i in 0..<nThreads) {
                if (bitmap0 != null) fastBitmap0[i] = FastAccessBitmap(bitmap0)
                if (bitmap2 != null) fastBitmap2[i] = FastAccessBitmap(bitmap2)
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // unused
            throw RuntimeException("not implemented")
        }

        fun tonemap(out: IntArray, hdrR: Float, hdrG: Float, hdrB: Float) {
            // tonemap
            when (tonemapAlgorithm) {
                TONEMAPALGORITHM_CLAMP -> {
                    // Simple clamp
                    var r = (hdrR + 0.5f).toInt()
                    var g = (hdrG + 0.5f).toInt()
                    var b = (hdrB + 0.5f).toInt()
                    r = min(r.toDouble(), 255.0).toInt()
                    g = min(g.toDouble(), 255.0).toInt()
                    b = min(b.toDouble(), 255.0).toInt()
                    out[0] = r
                    out[1] = g
                    out[2] = b
                }

                TONEMAPALGORITHM_EXPONENTIAL -> {
                    // for Exponential; should match setting in HDRProcessor.java:
                    val exposure = 1.2f
                    val outFr =
                        (linearScale * 255.0f * (1.0 - exp((-exposure * hdrR / 255.0f).toDouble()))).toFloat()
                    val outFg =
                        (linearScale * 255.0f * (1.0 - exp((-exposure * hdrG / 255.0f).toDouble()))).toFloat()
                    val outFb =
                        (linearScale * 255.0f * (1.0 - exp((-exposure * hdrB / 255.0f).toDouble()))).toFloat()
                    out[0] = max(min((outFr + 0.5f).toDouble(), 255.0), 0.0).toInt()
                    out[1] = max(min((outFg + 0.5f).toDouble(), 255.0), 0.0).toInt()
                    out[2] = max(min((outFb + 0.5f).toDouble(), 255.0), 0.0).toInt()
                }

                TONEMAPALGORITHM_REINHARD -> {
                    var value = max(hdrR.toDouble(), hdrG.toDouble()).toFloat()
                    value = max(value.toDouble(), hdrB.toDouble()).toFloat()
                    var scale = 255.0f / (tonemapScale + value)
                    scale *= linearScale
                    // shouldn't need to clamp - linearScale should be such that values don't map to more than 255
                    out[0] = (scale * hdrR + 0.5f).toInt()
                    out[1] = (scale * hdrG + 0.5f).toInt()
                    out[2] = (scale * hdrB + 0.5f).toInt()
                }

                TONEMAPALGORITHM_FU2 -> {
                    // FU2 (Filmic)
                    // for FU2; should match setting in HDRProcessor.java:
                    val fu2ExposureBias = 2.0f / 255.0f
                    val whiteScale = 255.0f / convertFU2Tonemap(valW)
                    var currR = convertFU2Tonemap(fu2ExposureBias * hdrR)
                    var currG = convertFU2Tonemap(fu2ExposureBias * hdrG)
                    var currB = convertFU2Tonemap(fu2ExposureBias * hdrB)
                    currR *= whiteScale
                    currG *= whiteScale
                    currB *= whiteScale
                    out[0] = max(min((currR + 0.5f).toDouble(), 255.0), 0.0).toInt()
                    out[1] = max(min((currG + 0.5f).toDouble(), 255.0), 0.0).toInt()
                    out[2] = max(min((currB + 0.5f).toDouble(), 255.0), 0.0).toInt()
                }

                TONEMAPALGORITHM_ACES -> {
                    // https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/ (released under public domain cc0)
                    val a = 2.51f
                    val b = 0.03f
                    val c = 2.43f
                    val d = 0.59f
                    val e = 0.14f
                    val xr = hdrR / 255.0f
                    val xg = hdrG / 255.0f
                    val xb = hdrB / 255.0f
                    val outFr = 255.0f * (xr * (a * xr + b)) / (xr * (c * xr + d) + e)
                    val outFg = 255.0f * (xg * (a * xg + b)) / (xg * (c * xg + d) + e)
                    val outFb = 255.0f * (xb * (a * xb + b)) / (xb * (c * xb + d) + e)
                    out[0] = max(min((outFr + 0.5f).toDouble(), 255.0), 0.0).toInt()
                    out[1] = max(min((outFg + 0.5f).toDouble(), 255.0), 0.0).toInt()
                    out[2] = max(min((outFb + 0.5f).toDouble(), 255.0), 0.0).toInt()
                }
            }

            /*
            // test
            if( x+offsetX0 < 0 || y+offsetY0 < 0 || x+offsetX0 >= rsAllocationGetDimX(bitmap0) || y+offsetY0 >= rsAllocationGetDimY(bitmap0) ) {
                out.r = 255;
                out.g = 0;
                out.b = 255;
                out.a = 255;
            }
            else if( x+offsetX2 < 0 || y+offsetY2 < 0 || x+offsetX2 >= rsAllocationGetDimX(bitmap2) || y+offsetY2 >= rsAllocationGetDimY(bitmap2) ) {
                out.r = 255;
                out.g = 255;
                out.b = 0;
                out.a = 255;
            }
            */
            //return out;
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: IntArray,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            val pixelsOut = output?.cachedPixelsI

            // although we could move tempRgb to a class member for performance, remember we'd have to have a version per-thread
            val tempRgb = IntArray(3)

            //final int maxBitmapsC = 3;
            //int nBitmaps = 3;
            //final int midIndx = (nBitmaps-1)/2;
            //int pixelsR[maxBitmapsC];
            //int pixelsG[maxBitmapsC];
            //int pixelsB[maxBitmapsC];
            var pixel0R: Int
            var pixel0G: Int
            var pixel0B: Int
            var pixel1R: Int
            var pixel1G: Int
            var pixel1B: Int
            var pixel2R: Int
            var pixel2G: Int
            var pixel2B: Int

            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                fastBitmap0[threadIndex]?.ensureCache(
                    y + offsetY0,
                    y + offsetY0
                ) // force cache to cover rows needed by this row
                val bitmap0CacheY: Int = fastBitmap0[threadIndex]!!.cacheY
                val yRelBitmap0Cache = y - bitmap0CacheY
                val bitmap0CachePixels: IntArray = fastBitmap0[threadIndex]!!.cachedPixelsI

                fastBitmap2[threadIndex]?.ensureCache(
                    y + offsetY2,
                    y + offsetY2
                ) // force cache to cover rows needed by this row
                val bitmap2CacheY: Int = fastBitmap2[threadIndex]!!.cacheY
                val yRelBitmap2Cache = y - bitmap2CacheY
                val bitmap2CachePixels: IntArray = fastBitmap2[threadIndex]!!.cachedPixelsI

                var x = offX
                while (x < offX + thisWidth) {
                    var thisParameterA0 = parameterA[0]
                    var thisParameterB0 = parameterB[0]
                    val thisParameterA1 = parameterA[1]
                    val thisParameterB1 = parameterB[1]
                    var thisParameterA2 = parameterA[2]
                    var thisParameterB2 = parameterB[2]

                    // middle image is not offset
                    val pixel1 = pixels[c]
                    pixel1R = (pixel1 shr 16) and 0xFF
                    pixel1G = (pixel1 shr 8) and 0xFF
                    pixel1B = pixel1 and 0xFF

                    if (x + offsetX0 >= 0 && y + offsetY0 >= 0 && x + offsetX0 < width && y + offsetY0 < height) {
                        //int pixel0 = fastBitmap0[threadIndex].getPixel(x+offsetX0, y+offsetY0);
                        val pixel0 =
                            bitmap0CachePixels[(yRelBitmap0Cache + offsetY0) * width + (x + offsetX0)]
                        pixel0R = (pixel0 shr 16) and 0xFF
                        pixel0G = (pixel0 shr 8) and 0xFF
                        pixel0B = pixel0 and 0xFF
                    } else {
                        pixel0R = pixel1R
                        pixel0G = pixel1G
                        pixel0B = pixel1B
                        thisParameterA0 = thisParameterA1
                        thisParameterB0 = thisParameterB1
                    }

                    if (x + offsetX2 >= 0 && y + offsetY2 >= 0 && x + offsetX2 < width && y + offsetY2 < height) {
                        //int pixel2 = fastBitmap2[threadIndex].getPixel(x+offsetX2, y+offsetY2);
                        val pixel2 =
                            bitmap2CachePixels[(yRelBitmap2Cache + offsetY2) * width + (x + offsetX2)]
                        pixel2R = (pixel2 shr 16) and 0xFF
                        pixel2G = (pixel2 shr 8) and 0xFF
                        pixel2B = pixel2 and 0xFF
                    } else {
                        pixel2R = pixel1R
                        pixel2G = pixel1G
                        pixel2B = pixel1B
                        thisParameterA2 = thisParameterA1
                        thisParameterB2 = thisParameterB1
                    }

                    var hdrR = 0.0f
                    var hdrG = 0.0f
                    var hdrB = 0.0f
                    var sumWeight = 0.0f

                    // assumes 3 bitmaps, with middle bitmap being the "base" exposure, and first image being darker, third image being brighter
                    run {
                        val safeRangeC = 96.0f
                        var rgbR = pixel1R.toFloat()
                        var rgbG = pixel1G.toFloat()
                        var rgbB = pixel1B.toFloat()
                        val avg = (rgbR + rgbG + rgbB) / 3.0f
                        // avoid Math.abs as this line seems costly for performance:
                        //float diff = Math.abs( avg - 127.5f );
                        var weight = 1.0f
                        if (avg <= 127.5f) {
                            // We now intentionally have the weights be non-symmetric, and have the weight fall to 0
                            // faster for dark pixels than bright pixels. This fixes ghosting problems of testHDR62,
                            // where we have very dark regions where we get ghosting between the middle and bright
                            // images, and the image is too dark for the deghosting algorithm below to resolve this.
                            // We're better off using smaller weight, so that more of the pixel comes from the
                            // bright image.
                            // This also gives improved lighting/colour in: testHDR1, testHDR2, testHDR11,
                            // testHDR12, testHDR21, testHDR52.
                            val rangeLowC = 32.0f
                            val rangeHighC = 48.0f
                            if (avg <= rangeLowC) {
                                weight = 0.0f
                            } else if (avg <= rangeHighC) {
                                weight = (avg - rangeLowC) / (rangeHighC - rangeLowC)
                            }
                        } else if ((avg - 127.5f) > safeRangeC) {
                            // scaling chosen so that 0 and 255 map to a non-zero weight of 0.01
                            weight =
                                1.0f - 0.99f * ((avg - 127.5f) - safeRangeC) / (127.5f - safeRangeC)
                        }

                        // response function
                        rgbR = thisParameterA1 * rgbR + thisParameterB1
                        rgbG = thisParameterA1 * rgbG + thisParameterB1
                        rgbB = thisParameterA1 * rgbB + thisParameterB1

                        hdrR += weight * rgbR
                        hdrG += weight * rgbG
                        hdrB += weight * rgbB
                        sumWeight += weight
                        if (weight < 1.0) {
                            val baseRgbR = rgbR
                            val baseRgbG = rgbG
                            val baseRgbB = rgbB

                            // now look at a neighbour image
                            weight = 1.0f - weight

                            if (avg <= 127.5f) {
                                rgbR = pixel2R.toFloat()
                                rgbG = pixel2G.toFloat()
                                rgbB = pixel2B.toFloat()

                                /* In some cases it can be that even on the neighbour image, the brightness is too
                                   dark/bright - but it should still be a better choice than the base image.
                                   If we change this (including say for handling more than 3 images), need to be
                                   careful of unpredictable effects. In particular, consider a pixel that is brightness
                                   255 on the base image. As the brightness on the neighbour image increases, we
                                   should expect that the resultant image also increases (or at least, doesn't
                                   decrease). See testHDR36 for such an example.
                                   */
                                /*avg = (rgb.r+rgb.g+rgb.b) / 3.0f;
                                diff = fabs( avg - 127.5f );
                                if( diff > safeRangeC ) {
                                    // scaling chosen so that 0 and 255 map to a non-zero weight of 0.01
                                    weight *= 1.0f - 0.99f * (diff - safeRangeC) / (127.5f - safeRangeC);
                                }*/
                                rgbR = thisParameterA2 * rgbR + thisParameterB2
                                rgbG = thisParameterA2 * rgbG + thisParameterB2
                                rgbB = thisParameterA2 * rgbB + thisParameterB2
                            } else {
                                rgbR = pixel0R.toFloat()
                                rgbG = pixel0G.toFloat()
                                rgbB = pixel0B.toFloat()

                                // see note above for why this is commented out
                                /*avg = (rgb.r+rgb.g+rgb.b) / 3.0f;
                                diff = fabs( avg - 127.5f );
                                if( diff > safeRangeC ) {
                                    // scaling chosen so that 0 and 255 map to a non-zero weight of 0.01
                                    weight *= 1.0f - 0.99f * (diff - safeRangeC) / (127.5f - safeRangeC);
                                }*/
                                rgbR = thisParameterA0 * rgbR + thisParameterB0
                                rgbG = thisParameterA0 * rgbG + thisParameterB0
                                rgbB = thisParameterA0 * rgbB + thisParameterB0
                            }

                            var value = max(rgbR.toDouble(), rgbG.toDouble()).toFloat()
                            value = max(value.toDouble(), rgbB.toDouble()).toFloat()
                            if (value <= 250.0f) {
                                // deghosting
                                // for overexposed pixels, we don't have a reliable value for that pixel, so we can't distinguish between
                                // pixels that are overexposed, and those that need deghosting, so we limit to value <= 250.0f
                                // tests that benefit from deghosting for dark pixels: testHDR2, testHDR9, testHDR19, testHDR21, testHDR30,
                                // testHDR35, testHDR37, testHDR40, testHDR41, testHDR42, testHDR44
                                // tests that benefit from deghosting for bright pixels: testHDR2, testHDR41, testHDR42
                                // for 127.5-avg = 96.0, we want wiener_C = wiener_C_lo
                                // for 127.5-avg = 127.5f, we want wiener_C = wiener_C_hi
                                val wienerCLo = 2000.0f
                                val wienerCHi = 8000.0f
                                // higher value means more HDR but less ghosting
                                var wienerC = wienerCLo
                                val xx = (abs((value - 127.5f).toDouble()) - 96.0f).toFloat()
                                if (xx > 0.0f) {
                                    val scale = (wienerCHi - wienerCLo) / (127.5f - 96.0f)
                                    wienerC = wienerCLo + xx * scale
                                }
                                val diffR = baseRgbR - rgbR
                                val diffG = baseRgbG - rgbG
                                val diffB = baseRgbB - rgbB
                                val valL = (diffR * diffR) + (diffG * diffG) + (diffB * diffB)
                                val ghostWeight = valL / (valL + wienerC)
                                rgbR = ghostWeight * baseRgbR + (1.0f - ghostWeight) * rgbR
                                rgbG = ghostWeight * baseRgbG + (1.0f - ghostWeight) * rgbG
                                rgbB = ghostWeight * baseRgbB + (1.0f - ghostWeight) * rgbB
                            }

                            hdrR += weight * rgbR
                            hdrG += weight * rgbG
                            hdrB += weight * rgbB
                            sumWeight += weight

                            // testing: make all non-safe images purple:
                            //hdrR = 255;
                            //hdrG = 0;
                            //hdrB = 255;
                        }
                    }

                    hdrR /= sumWeight
                    hdrG /= sumWeight
                    hdrB /= sumWeight

                    tonemap(tempRgb, hdrR, hdrG, hdrB)

                    /*{
                        float value = Math.max(hdrR, hdrG);
                        value = Math.max(value, hdrB);
                        float scale = 255.0f / ( tonemapScale + value );
                        scale *= linearScale;
                        // shouldn't need to clamp - linearScale should be such that values don't map to more than 255
                        tempRgb[0] = (int)(scale * hdrR + 0.5f);
                        tempRgb[1] = (int)(scale * hdrG + 0.5f);
                        tempRgb[2] = (int)(scale * hdrB + 0.5f);
                    }*/

                    // this code is performance critical; note it's faster to avoid calls to Color.argb()
                    pixelsOut?.set(
                        c,
                        (255 shl 24) or (tempRgb[0] shl 16) or (tempRgb[1] shl 8) or tempRgb[2]
                    )
                    x++
                    c++
                }
                y++
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: ByteArray?,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // unused
            throw RuntimeException("not implemented")
        }

        companion object {
            private fun convertFU2Tonemap(x: Float): Float {
                val vA = 0.15f
                val vB = 0.50f
                val vC = 0.10f
                val vD = 0.20f
                val vE = 0.02f
                val vF = 0.30f
                return ((x * (vA * x + vC * vB) + vD * vE) / (x * (vA * x + vB) + vD * vF)) - vE / vF
            }
        }
    }

    internal class HDRNApplyFunction(
        tonemapAlgorithm: TonemappingAlgorithm,
        tonemapScale: Float,
        valW: Float,
        linearScale: Float,
        bitmaps: List<Bitmap>,
        offsetsX: IntArray,
        offsetsY: IntArray,
        width: Int,
        height: Int,
        parameterA: FloatArray,
        parameterB: FloatArray
    ) : HDRApplyFunction(
        tonemapAlgorithm,
        tonemapScale,
        valW,
        linearScale,
        bitmaps[0],
        if (bitmaps.size > 2) bitmaps[2] else null,
        offsetsX[0],
        offsetsY[0],
        if (offsetsX.size > 2) offsetsX[2] else 0,
        if (offsetsY.size > 2) offsetsY[2] else 0,
        width,
        height,
        parameterA,
        parameterB
    ) {
        private val nBitmaps = bitmaps.size
        private val bitmap1: Bitmap?
        lateinit var fastBitmap1: Array<FastAccessBitmap?>
        private val bitmap3: Bitmap?
        lateinit var fastBitmap3: Array<FastAccessBitmap?>
        private val bitmap4: Bitmap?
        lateinit var fastBitmap4: Array<FastAccessBitmap?>
        private val bitmap5: Bitmap?
        lateinit var fastBitmap5: Array<FastAccessBitmap?>
        private val bitmap6: Bitmap?
        lateinit var fastBitmap6: Array<FastAccessBitmap?>
        val offsetX1: Int
        val offsetY1: Int
        val offsetX3: Int
        val offsetY3: Int
        val offsetX4: Int
        val offsetY4: Int
        val offsetX5: Int
        val offsetY5: Int
        val offsetX6: Int
        val offsetY6: Int

        init {
            if (nBitmaps !in 2..7) {
                throw RuntimeException("n_bitmaps not supported: $nBitmaps")
            } else if (offsetsX.size != nBitmaps) {
                throw RuntimeException("offsets_x unexpected length: " + offsetsX.size)
            } else if (offsetsY.size != nBitmaps) {
                throw RuntimeException("offsets_y unexpected length: " + offsetsY.size)
            }

            this.bitmap1 = bitmaps[1]
            this.bitmap3 = if (nBitmaps > 3) bitmaps[3] else null
            this.bitmap4 = if (nBitmaps > 4) bitmaps[4] else null
            this.bitmap5 = if (nBitmaps > 5) bitmaps[5] else null
            this.bitmap6 = if (nBitmaps > 6) bitmaps[6] else null

            this.offsetX1 = offsetsX[1]
            this.offsetY1 = offsetsY[1]
            this.offsetX3 = if (nBitmaps > 3) offsetsX[3] else 0
            this.offsetY3 = if (nBitmaps > 3) offsetsY[3] else 0
            this.offsetX4 = if (nBitmaps > 4) offsetsX[4] else 0
            this.offsetY4 = if (nBitmaps > 4) offsetsY[4] else 0
            this.offsetX5 = if (nBitmaps > 5) offsetsX[5] else 0
            this.offsetY5 = if (nBitmaps > 5) offsetsY[5] else 0
            this.offsetX6 = if (nBitmaps > 6) offsetsX[6] else 0
            this.offsetY6 = if (nBitmaps > 6) offsetsY[6] else 0

            if (parameterA.size != nBitmaps || parameterB.size != nBitmaps) {
                throw RuntimeException("unexpected parameter lengths")
            }
        }

        override fun init(nThreads: Int) {
            super.init(nThreads)

            if (bitmap1 != null) fastBitmap1 =
                arrayOfNulls(nThreads)
            if (bitmap3 != null) fastBitmap3 =
                arrayOfNulls(nThreads)
            if (bitmap4 != null) fastBitmap4 =
                arrayOfNulls(nThreads)
            if (bitmap5 != null) fastBitmap5 =
                arrayOfNulls(nThreads)
            if (bitmap6 != null) fastBitmap6 =
                arrayOfNulls(nThreads)
            for (i in 0..<nThreads) {
                if (bitmap1 != null) fastBitmap1[i] = FastAccessBitmap(bitmap1)
                if (bitmap3 != null) fastBitmap3[i] = FastAccessBitmap(bitmap3)
                if (bitmap4 != null) fastBitmap4[i] = FastAccessBitmap(bitmap4)
                if (bitmap5 != null) fastBitmap5[i] = FastAccessBitmap(bitmap5)
                if (bitmap6 != null) fastBitmap6[i] = FastAccessBitmap(bitmap6)
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: IntArray,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            val pixelsOut = output?.cachedPixelsI

            val midIndx =
                (nBitmaps - 1) / 2 // round down to dark image for even number of bitmaps
            val even = nBitmaps % 2 == 0

            // although we could move these allocations to class members for performance, remember we'd have to have versions per-thread
            val pixelsR = IntArray(nBitmaps)
            val pixelsG = IntArray(nBitmaps)
            val pixelsB = IntArray(nBitmaps)
            val thisParameterA = FloatArray(nBitmaps)
            val thisParameterB = FloatArray(nBitmaps)
            val tempRgb = IntArray(3)

            var basePixelR: Int
            var basePixelG: Int
            var basePixelB: Int

            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                fastBitmap0[threadIndex]?.ensureCache(
                    y + offsetY0,
                    y + offsetY0
                ) // force cache to cover rows needed by this row
                val bitmap0CacheY: Int = fastBitmap0[threadIndex]!!.cacheY
                val yRelBitmap0Cache = y - bitmap0CacheY
                val bitmap0CachePixels: IntArray =
                    fastBitmap0[threadIndex]!!.cachedPixelsI

                fastBitmap1[threadIndex]?.ensureCache(
                    y + offsetY1,
                    y + offsetY1
                ) // force cache to cover rows needed by this row
                val bitmap1CacheY: Int = fastBitmap1[threadIndex]!!.cacheY
                val yRelBitmap1Cache = y - bitmap1CacheY
                val bitmap1CachePixels: IntArray = fastBitmap1[threadIndex]!!.cachedPixelsI

                var yRelBitmap2Cache = 0
                var yRelBitmap3Cache = 0
                var yRelBitmap4Cache = 0
                var yRelBitmap5Cache = 0
                var yRelBitmap6Cache = 0
                var bitmap2CachePixels: IntArray? = null
                var bitmap3CachePixels: IntArray? = null
                var bitmap4CachePixels: IntArray? = null
                var bitmap5CachePixels: IntArray? = null
                var bitmap6CachePixels: IntArray? = null

                if (nBitmaps > 2) {
                    fastBitmap2[threadIndex]?.ensureCache(
                        y + offsetY2,
                        y + offsetY2
                    ) // force cache to cover rows needed by this row
                    val bitmap2CacheY: Int = fastBitmap2[threadIndex]!!.cacheY
                    yRelBitmap2Cache = y - bitmap2CacheY
                    bitmap2CachePixels = fastBitmap2[threadIndex]!!.cachedPixelsI

                    if (nBitmaps > 3) {
                        fastBitmap3[threadIndex]?.ensureCache(
                            y + offsetY3,
                            y + offsetY3
                        ) // force cache to cover rows needed by this row
                        val bitmap3CacheY: Int = fastBitmap3[threadIndex]!!.cacheY
                        yRelBitmap3Cache = y - bitmap3CacheY
                        bitmap3CachePixels = fastBitmap3[threadIndex]!!.cachedPixelsI

                        if (nBitmaps > 4) {
                            fastBitmap4[threadIndex]?.ensureCache(
                                y + offsetY4,
                                y + offsetY4
                            ) // force cache to cover rows needed by this row
                            val bitmap4CacheY: Int = fastBitmap4[threadIndex]!!.cacheY
                            yRelBitmap4Cache = y - bitmap4CacheY
                            bitmap4CachePixels = fastBitmap4[threadIndex]!!.cachedPixelsI

                            if (nBitmaps > 5) {
                                fastBitmap5[threadIndex]?.ensureCache(
                                    y + offsetY5,
                                    y + offsetY5
                                ) // force cache to cover rows needed by this row
                                val bitmap5CacheY: Int = fastBitmap5[threadIndex]!!.cacheY
                                yRelBitmap5Cache = y - bitmap5CacheY
                                bitmap5CachePixels = fastBitmap5[threadIndex]!!.cachedPixelsI

                                if (nBitmaps > 6) {
                                    fastBitmap6[threadIndex]?.ensureCache(
                                        y + offsetY6,
                                        y + offsetY6
                                    ) // force cache to cover rows needed by this row
                                    val bitmap6CacheY: Int =
                                        fastBitmap6[threadIndex]!!.cacheY
                                    yRelBitmap6Cache = y - bitmap6CacheY
                                    bitmap6CachePixels =
                                        fastBitmap6[threadIndex]!!.cachedPixelsI
                                }
                            }
                        }
                    }
                }

                var x = offX
                while (x < offX + thisWidth) {
                    System.arraycopy(
                        this@HDRNApplyFunction.parameterA,
                        0,
                        thisParameterA,
                        0,
                        this@HDRNApplyFunction.parameterA.size
                    )
                    System.arraycopy(
                        this@HDRNApplyFunction.parameterB,
                        0,
                        thisParameterB,
                        0,
                        this@HDRNApplyFunction.parameterB.size
                    )

                    val basePixel = pixels[c]
                    basePixelR = (basePixel shr 16) and 0xFF
                    basePixelG = (basePixel shr 8) and 0xFF
                    basePixelB = basePixel and 0xFF

                    if (x + offsetX0 >= 0 && y + offsetY0 >= 0 && x + offsetX0 < width && y + offsetY0 < height) {
                        //int pixel = fastBitmap0[threadIndex].getPixel(x+offsetX0, y+offsetY0);
                        val pixel =
                            bitmap0CachePixels[(yRelBitmap0Cache + offsetY0) * width + (x + offsetX0)]
                        pixelsR[0] = (pixel shr 16) and 0xFF
                        pixelsG[0] = (pixel shr 8) and 0xFF
                        pixelsB[0] = pixel and 0xFF
                    } else {
                        pixelsR[0] = basePixelR
                        pixelsG[0] = basePixelG
                        pixelsB[0] = basePixelB
                        thisParameterA[0] = thisParameterA[midIndx]
                        thisParameterB[0] = thisParameterB[midIndx]
                    }

                    if (x + offsetX1 >= 0 && y + offsetY1 >= 0 && x + offsetX1 < width && y + offsetY1 < height) {
                        //int pixel = fastBitmap1[threadIndex].getPixel(x+offsetX1, y+offsetY1);
                        val pixel =
                            bitmap1CachePixels[(yRelBitmap1Cache + offsetY1) * width + (x + offsetX1)]
                        pixelsR[1] = (pixel shr 16) and 0xFF
                        pixelsG[1] = (pixel shr 8) and 0xFF
                        pixelsB[1] = pixel and 0xFF
                    } else {
                        pixelsR[1] = basePixelR
                        pixelsG[1] = basePixelG
                        pixelsB[1] = basePixelB
                        thisParameterA[1] = thisParameterA[midIndx]
                        thisParameterB[1] = thisParameterB[midIndx]
                    }

                    if (nBitmaps > 2) {
                        if (x + offsetX2 >= 0 && y + offsetY2 >= 0 && x + offsetX2 < width && y + offsetY2 < height) {
                            //int pixel = fastBitmap2[threadIndex].getPixel(x+offsetX2, y+offsetY2);
                            val pixel =
                                bitmap2CachePixels!![(yRelBitmap2Cache + offsetY2) * width + (x + offsetX2)]
                            pixelsR[2] = (pixel shr 16) and 0xFF
                            pixelsG[2] = (pixel shr 8) and 0xFF
                            pixelsB[2] = pixel and 0xFF
                        } else {
                            pixelsR[2] = basePixelR
                            pixelsG[2] = basePixelG
                            pixelsB[2] = basePixelB
                            thisParameterA[2] = thisParameterA[midIndx]
                            thisParameterB[2] = thisParameterB[midIndx]
                        }

                        if (nBitmaps > 3) {
                            if (x + offsetX3 >= 0 && y + offsetY3 >= 0 && x + offsetX3 < width && y + offsetY3 < height) {
                                //int pixel = fastBitmap3[threadIndex].getPixel(x+offsetX3, y+offsetY3);
                                val pixel =
                                    bitmap3CachePixels!![(yRelBitmap3Cache + offsetY3) * width + (x + offsetX3)]
                                pixelsR[3] = (pixel shr 16) and 0xFF
                                pixelsG[3] = (pixel shr 8) and 0xFF
                                pixelsB[3] = pixel and 0xFF
                            } else {
                                pixelsR[3] = basePixelR
                                pixelsG[3] = basePixelG
                                pixelsB[3] = basePixelB
                                thisParameterA[3] = thisParameterA[midIndx]
                                thisParameterB[3] = thisParameterB[midIndx]
                            }

                            if (nBitmaps > 4) {
                                if (x + offsetX4 >= 0 && y + offsetY4 >= 0 && x + offsetX4 < width && y + offsetY4 < height) {
                                    //int pixel = fastBitmap4[threadIndex].getPixel(x+offsetX4, y+offsetY4);
                                    val pixel =
                                        bitmap4CachePixels!![(yRelBitmap4Cache + offsetY4) * width + (x + offsetX4)]
                                    pixelsR[4] = (pixel shr 16) and 0xFF
                                    pixelsG[4] = (pixel shr 8) and 0xFF
                                    pixelsB[4] = pixel and 0xFF
                                } else {
                                    pixelsR[4] = basePixelR
                                    pixelsG[4] = basePixelG
                                    pixelsB[4] = basePixelB
                                    thisParameterA[4] = thisParameterA[midIndx]
                                    thisParameterB[4] = thisParameterB[midIndx]
                                }

                                if (nBitmaps > 5) {
                                    if (x + offsetX5 >= 0 && y + offsetY5 >= 0 && x + offsetX5 < width && y + offsetY5 < height) {
                                        //int pixel = fastBitmap5[threadIndex].getPixel(x+offsetX5, y+offsetY5);
                                        val pixel =
                                            bitmap5CachePixels!![(yRelBitmap5Cache + offsetY5) * width + (x + offsetX5)]
                                        pixelsR[5] = (pixel shr 16) and 0xFF
                                        pixelsG[5] = (pixel shr 8) and 0xFF
                                        pixelsB[5] = pixel and 0xFF
                                    } else {
                                        pixelsR[5] = basePixelR
                                        pixelsG[5] = basePixelG
                                        pixelsB[5] = basePixelB
                                        thisParameterA[5] = thisParameterA[midIndx]
                                        thisParameterB[5] = thisParameterB[midIndx]
                                    }

                                    if (nBitmaps > 6) {
                                        if (x + offsetX6 >= 0 && y + offsetY6 >= 0 && x + offsetX6 < width && y + offsetY6 < height) {
                                            //int pixel = fastBitmap6[threadIndex].getPixel(x+offsetX6, y+offsetY6);
                                            val pixel =
                                                bitmap6CachePixels!![(yRelBitmap6Cache + offsetY6) * width + (x + offsetX6)]
                                            pixelsR[6] = (pixel shr 16) and 0xFF
                                            pixelsG[6] = (pixel shr 8) and 0xFF
                                            pixelsB[6] = pixel and 0xFF
                                        } else {
                                            pixelsR[6] = basePixelR
                                            pixelsG[6] = basePixelG
                                            pixelsB[6] = basePixelB
                                            thisParameterA[6] = thisParameterA[midIndx]
                                            thisParameterB[6] = thisParameterB[midIndx]
                                        }
                                    }
                                }
                            }
                        }
                    }

                    var hdrR = 0.0f
                    var hdrG = 0.0f
                    var hdrB = 0.0f
                    var sumWeight = 0.0f

                    // assumes from 2 to 7 bitmaps, with middle bitmap being the "base" exposure, and first images being darker, last images being brighter
                    run {
                        val safeRangeC = 96.0f
                        var rgbR = pixelsR[midIndx].toFloat()
                        var rgbG = pixelsG[midIndx].toFloat()
                        var rgbB = pixelsB[midIndx].toFloat()
                        var avg = (rgbR + rgbG + rgbB) / 3.0f
                        // avoid Math.abs as this line seems costly for performance:
                        //float diff = Math.abs( avg - 127.5f );
                        var weight = 1.0f
                        if (avg <= 127.5f) {
                            // see comment for corresponding code in HDRApplyFunction
                            val rangeLowC = 32.0f
                            val rangeHighC = 48.0f
                            if (avg <= rangeLowC) {
                                weight = 0.0f
                            } else if (avg <= rangeHighC) {
                                weight = (avg - rangeLowC) / (rangeHighC - rangeLowC)
                            }
                        } else if ((avg - 127.5f) > safeRangeC) {
                            // scaling chosen so that 0 and 255 map to a non-zero weight of 0.01
                            weight =
                                1.0f - 0.99f * ((avg - 127.5f) - safeRangeC) / (127.5f - safeRangeC)
                        }

                        // response function
                        rgbR = thisParameterA[midIndx] * rgbR + thisParameterB[midIndx]
                        rgbG = thisParameterA[midIndx] * rgbG + thisParameterB[midIndx]
                        rgbB = thisParameterA[midIndx] * rgbB + thisParameterB[midIndx]

                        hdrR += weight * rgbR
                        hdrG += weight * rgbG
                        hdrB += weight * rgbB
                        sumWeight += weight

                        if (even) {
                            var rgb1R = pixelsR[midIndx + 1].toFloat()
                            var rgb1G = pixelsG[midIndx + 1].toFloat()
                            var rgb1B = pixelsB[midIndx + 1].toFloat()
                            val avg1 = (rgb1R + rgb1G + rgb1B) / 3.0f
                            val diff1 = abs((avg1 - 127.5f).toDouble()).toFloat()
                            var weight1 = 1.0f
                            if (diff1 > safeRangeC) {
                                // scaling chosen so that 0 and 255 map to a non-zero weight of 0.01
                                weight1 =
                                    1.0f - 0.99f * (diff1 - safeRangeC) / (127.5f - safeRangeC)
                            }
                            rgb1R =
                                thisParameterA[midIndx + 1] * rgb1R + thisParameterB[midIndx + 1]
                            rgb1G =
                                thisParameterA[midIndx + 1] * rgb1G + thisParameterB[midIndx + 1]
                            rgb1B =
                                thisParameterA[midIndx + 1] * rgb1B + thisParameterB[midIndx + 1]

                            hdrR += weight1 * rgb1R
                            hdrG += weight1 * rgb1G
                            hdrB += weight1 * rgb1B
                            sumWeight += weight1

                            avg = (avg + avg1) / 2.0f
                            weight = (weight + weight1) / 2.0f
                        }
                        if (weight < 1.0) {
                            val baseRgbR = rgbR
                            val baseRgbG = rgbG
                            val baseRgbB = rgbB

                            var adjIndx = midIndx
                            val stepDir = if (avg <= 127.5f) 1 else -1
                            if (even && stepDir == 1) {
                                adjIndx++ // so we move one beyond the middle pair of images (since midIndx will be the darker of the pair)
                            }

                            var diff = 0.0f
                            val nAdj = (nBitmaps - 1) / 2
                            for (k in 0..<nAdj) {
                                // now look at a neighbour image

                                weight = 1.0f - weight
                                adjIndx += stepDir

                                rgbR = pixelsR[adjIndx].toFloat()
                                rgbG = pixelsG[adjIndx].toFloat()
                                rgbB = pixelsB[adjIndx].toFloat()

                                if (k + 1 < nAdj) {
                                    // there will be at least one more adjacent image to look at
                                    avg = (rgbR + rgbG + rgbB) / 3.0f
                                    diff = abs((avg - 127.5f).toDouble()).toFloat()

                                    // n.b., we don't have the codepath here for "if( avg <= 127.5f )" - causes problems
                                    // for testHDR_exp5 (black blotches)
                                    if (diff > safeRangeC) {
                                        // scaling chosen so that 0 and 255 map to a non-zero weight of 0.01
                                        weight *= 1.0f - 0.99f * (diff - safeRangeC) / (127.5f - safeRangeC)
                                    }
                                }

                                rgbR =
                                    thisParameterA[adjIndx] * rgbR + thisParameterB[adjIndx]
                                rgbG =
                                    thisParameterA[adjIndx] * rgbG + thisParameterB[adjIndx]
                                rgbB =
                                    thisParameterA[adjIndx] * rgbB + thisParameterB[adjIndx]

                                var value = max(rgbR.toDouble(), rgbG.toDouble()).toFloat()
                                value = max(value.toDouble(), rgbB.toDouble()).toFloat()
                                if (value <= 250.0f) {
                                    // deghosting
                                    // for overexposed pixels, we don't have a reliable value for that pixel, so we can't distinguish between
                                    // pixels that are overexposed, and those that need deghosting, so we limit to value <= 250.0f
                                    // tests that benefit from deghosting for dark pixels: testHDR2, testHDR9, testHDR19, testHDR21, testHDR30,
                                    // testHDR35, testHDR37, testHDR40, testHDR41, testHDR42, testHDR44
                                    // tests that benefit from deghosting for bright pixels: testHDR2, testHDR41, testHDR42
                                    // for 127.5-avg = 96.0, we want wiener_C = wiener_C_lo
                                    // for 127.5-avg = 127.5f, we want wiener_C = wiener_C_hi
                                    val wienerCLo = 2000.0f
                                    val wienerCHi = 8000.0f
                                    // higher value means more HDR but less ghosting
                                    var wienerC = wienerCLo
                                    val xx = (abs((value - 127.5f).toDouble()) - 96.0f).toFloat()
                                    if (xx > 0.0f) {
                                        val scale = (wienerCHi - wienerCLo) / (127.5f - 96.0f)
                                        wienerC = wienerCLo + xx * scale
                                    }
                                    val diffR = baseRgbR - rgbR
                                    val diffG = baseRgbG - rgbG
                                    val diffB = baseRgbB - rgbB
                                    val valL = (diffR * diffR) + (diffG * diffG) + (diffB * diffB)
                                    val ghostWeight = valL / (valL + wienerC)
                                    rgbR =
                                        ghostWeight * baseRgbR + (1.0f - ghostWeight) * rgbR
                                    rgbG =
                                        ghostWeight * baseRgbG + (1.0f - ghostWeight) * rgbG
                                    rgbB =
                                        ghostWeight * baseRgbB + (1.0f - ghostWeight) * rgbB
                                }

                                hdrR += weight * rgbR
                                hdrG += weight * rgbG
                                hdrB += weight * rgbB
                                sumWeight += weight

                                if (diff <= safeRangeC) {
                                    break
                                }

                                // testing: make all non-safe images purple:
                                //hdrR = 255;
                                //hdrG = 0;
                                //hdrB = 255;
                            }
                        }
                    }

                    hdrR /= sumWeight
                    hdrG /= sumWeight
                    hdrB /= sumWeight

                    tonemap(tempRgb, hdrR, hdrG, hdrB)

                    // this code is performance critical; note it's faster to avoid calls to Color.argb()
                    pixelsOut?.set(
                        c,
                        (255 shl 24) or (tempRgb[0] shl 16) or (tempRgb[1] shl 8) or tempRgb[2]
                    )
                    x++
                    c++
                }
                y++
            }
        }
    }

    internal class AdjustHistogramApplyFunction(// 0.0 means no change, 1.0 means fully equalise
        private val hdrAlpha: Float,
        private val nTiles: Int,
        private val width: Int, private val height: Int,
        private val cHistogram: IntArray
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
        private fun getEqualValue(histogramOffset: Int, value: Int): Int {
            val cdfV = cHistogram[histogramOffset + value]
            val cdf0 = cHistogram[histogramOffset]
            val nPixels = cHistogram[histogramOffset + 255]
            val num = (cdfV - cdf0).toFloat()
            val den = (nPixels - cdf0).toFloat()
            val equalValue =
                (255.0f * (num / den)).toInt() // value that we should choose to fully equalise the histogram
            return equalValue
        }

        override fun init(nThreads: Int) {
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // unused
            throw RuntimeException("not implemented")
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: IntArray,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            val pixelsOut = output?.cachedPixelsI
            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                var x = offX
                while (x < offX + thisWidth) {
                    // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                    val color = pixels[c]
                    var r = (color shr 16) and 0xFF
                    var g = (color shr 8) and 0xFF
                    var b = color and 0xFF

                    var value = max(r.toDouble(), g.toDouble()).toInt()
                    value = max(value.toDouble(), b.toDouble()).toInt()

                    val tx = (x.toFloat() * nTiles) / width.toFloat() - 0.5f
                    val ty = (y.toFloat() * nTiles) / height.toFloat() - 0.5f

                    // inline floor for performance
                    //int ix = (int)Math.floor(tx);
                    //int iy = (int)Math.floor(ty);
                    val ix = if (tx >= 0.0) tx.toInt() else tx.toInt() - 1
                    val iy = if (ty >= 0.0) ty.toInt() else ty.toInt() - 1
                    /*if( ix != (int)Math.floor(tx) || iy != (int)Math.floor(ty) ) {
                        throw new RuntimeException("floor error");
                    }*/
                    val equalValue: Int
                    if (ix >= 0 && ix < nTiles - 1 && iy >= 0 && iy < nTiles - 1) {
                        val histogramOffset00 = 256 * (ix * nTiles + iy)
                        val histogramOffset10 = 256 * ((ix + 1) * nTiles + iy)
                        val histogramOffset01 = 256 * (ix * nTiles + iy + 1)
                        val histogramOffset11 = 256 * ((ix + 1) * nTiles + iy + 1)
                        val equalValue00 = getEqualValue(histogramOffset00, value)
                        val equalValue10 = getEqualValue(histogramOffset10, value)
                        val equalValue01 = getEqualValue(histogramOffset01, value)
                        val equalValue11 = getEqualValue(histogramOffset11, value)
                        val alpha = tx - ix
                        val beta = ty - iy

                        val equalValue0 = (1.0f - alpha) * equalValue00 + alpha * equalValue10
                        val equalValue1 = (1.0f - alpha) * equalValue01 + alpha * equalValue11
                        equalValue = ((1.0f - beta) * equalValue0 + beta * equalValue1).toInt()
                    } else if (ix >= 0 && ix < nTiles - 1) {
                        val thisY = if (iy < 0) iy + 1 else iy
                        val histogramOffset0 = 256 * (ix * nTiles + thisY)
                        val histogramOffset1 = 256 * ((ix + 1) * nTiles + thisY)
                        val equalValue0 = getEqualValue(histogramOffset0, value)
                        val equalValue1 = getEqualValue(histogramOffset1, value)
                        val alpha = tx - ix
                        equalValue = ((1.0f - alpha) * equalValue0 + alpha * equalValue1).toInt()
                    } else if (iy >= 0 && iy < nTiles - 1) {
                        val thisX = if (ix < 0) ix + 1 else ix
                        val histogramOffset0 = 256 * (thisX * nTiles + iy)
                        val histogramOffset1 = 256 * (thisX * nTiles + iy + 1)
                        val equalValue0 = getEqualValue(histogramOffset0, value)
                        val equalValue1 = getEqualValue(histogramOffset1, value)
                        val beta = ty - iy
                        equalValue = ((1.0f - beta) * equalValue0 + beta * equalValue1).toInt()
                    } else {
                        val thisX = if (ix < 0) ix + 1 else ix
                        val thisY = if (iy < 0) iy + 1 else iy
                        val histogramOffset = 256 * (thisX * nTiles + thisY)
                        equalValue = getEqualValue(histogramOffset, value)
                    }

                    val newValue = ((1.0f - hdrAlpha) * value + hdrAlpha * equalValue).toInt()

                    //float useHdrAlpha = smartContrastEnhancement ? hdrAlpha*((float)value/255.0f) : hdrAlpha;
                    //float useHdrAlpha = smartContrastEnhancement ? hdrAlpha*pow(((float)value/255.0f), 0.5f) : hdrAlpha;
                    //int newValue = (int)( (1.0f-useHdrAlpha) * value + useHdrAlpha * equalValue );
                    val scale = (newValue.toFloat()) / value.toFloat()

                    // need to add +0.5 so that we round to nearest - particularly important as due to floating point rounding, we
                    // can end up with incorrect behaviour even when newValue==value!
                    r = min(255.0, (r * scale + 0.5f).toInt().toDouble()).toInt()
                    g = min(255.0, (g * scale + 0.5f).toInt().toDouble()).toInt()
                    b = min(255.0, (b * scale + 0.5f).toInt().toDouble()).toInt()
                    // this code is performance critical; note it's faster to avoid calls to Color.argb()
                    pixelsOut?.set(c, (255 shl 24) or (r shl 16) or (g shl 8) or b)
                    x++
                    c++
                }
                y++
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: ByteArray?,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            val pixelsOut = output?.cachedPixelsB
            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                var x = offX
                while (x < offX + thisWidth) {
                    var r = pixels?.get(c)?.toInt() ?: 0
                    var g = pixels?.get(c + 1)?.toInt() ?: 0
                    var b = pixels?.get(c + 2)?.toInt() ?: 0
                    // bytes are signed!
                    if (r < 0) r += 256
                    if (g < 0) g += 256
                    if (b < 0) b += 256

                    var value = max(r.toDouble(), g.toDouble()).toInt()
                    value = max(value.toDouble(), b.toDouble()).toInt()

                    val tx = (x.toFloat() * nTiles) / width.toFloat() - 0.5f
                    val ty = (y.toFloat() * nTiles) / height.toFloat() - 0.5f

                    // inline floor for performance
                    //int ix = (int)Math.floor(tx);
                    //int iy = (int)Math.floor(ty);
                    val ix = if (tx >= 0.0) tx.toInt() else tx.toInt() - 1
                    val iy = if (ty >= 0.0) ty.toInt() else ty.toInt() - 1
                    /*if( ix != (int)Math.floor(tx) || iy != (int)Math.floor(ty) ) {
                        throw new RuntimeException("floor error");
                    }*/
                    val equalValue: Int
                    if (ix >= 0 && ix < nTiles - 1 && iy >= 0 && iy < nTiles - 1) {
                        val histogramOffset00 = 256 * (ix * nTiles + iy)
                        val histogramOffset10 = 256 * ((ix + 1) * nTiles + iy)
                        val histogramOffset01 = 256 * (ix * nTiles + iy + 1)
                        val histogramOffset11 = 256 * ((ix + 1) * nTiles + iy + 1)
                        val equalValue00 = getEqualValue(histogramOffset00, value)
                        val equalValue10 = getEqualValue(histogramOffset10, value)
                        val equalValue01 = getEqualValue(histogramOffset01, value)
                        val equalValue11 = getEqualValue(histogramOffset11, value)
                        val alpha = tx - ix
                        val beta = ty - iy

                        val equalValue0 = (1.0f - alpha) * equalValue00 + alpha * equalValue10
                        val equalValue1 = (1.0f - alpha) * equalValue01 + alpha * equalValue11
                        equalValue = ((1.0f - beta) * equalValue0 + beta * equalValue1).toInt()
                    } else if (ix >= 0 && ix < nTiles - 1) {
                        val thisY = if (iy < 0) iy + 1 else iy
                        val histogramOffset0 = 256 * (ix * nTiles + thisY)
                        val histogramOffset1 = 256 * ((ix + 1) * nTiles + thisY)
                        val equalValue0 = getEqualValue(histogramOffset0, value)
                        val equalValue1 = getEqualValue(histogramOffset1, value)
                        val alpha = tx - ix
                        equalValue = ((1.0f - alpha) * equalValue0 + alpha * equalValue1).toInt()
                    } else if (iy >= 0 && iy < nTiles - 1) {
                        val thisX = if (ix < 0) ix + 1 else ix
                        val histogramOffset0 = 256 * (thisX * nTiles + iy)
                        val histogramOffset1 = 256 * (thisX * nTiles + iy + 1)
                        val equalValue0 = getEqualValue(histogramOffset0, value)
                        val equalValue1 = getEqualValue(histogramOffset1, value)
                        val beta = ty - iy
                        equalValue = ((1.0f - beta) * equalValue0 + beta * equalValue1).toInt()
                    } else {
                        val thisX = if (ix < 0) ix + 1 else ix
                        val thisY = if (iy < 0) iy + 1 else iy
                        val histogramOffset = 256 * (thisX * nTiles + thisY)
                        equalValue = getEqualValue(histogramOffset, value)
                    }

                    val newValue = ((1.0f - hdrAlpha) * value + hdrAlpha * equalValue).toInt()

                    //float useHdrAlpha = smartContrastEnhancement ? hdrAlpha*((float)value/255.0f) : hdrAlpha;
                    //float useHdrAlpha = smartContrastEnhancement ? hdrAlpha*pow(((float)value/255.0f), 0.5f) : hdrAlpha;
                    //int newValue = (int)( (1.0f-useHdrAlpha) * value + useHdrAlpha * equalValue );
                    val scale = (newValue.toFloat()) / value.toFloat()

                    // need to add +0.5 so that we round to nearest - particularly important as due to floating point rounding, we
                    // can end up with incorrect behaviour even when newValue==value!
                    pixelsOut?.set(
                        c,
                        min(255.0, (r * scale + 0.5f).toInt().toDouble()).toInt().toByte()
                    )
                    pixelsOut?.set(
                        c + 1,
                        min(255.0, (g * scale + 0.5f).toInt().toDouble()).toInt().toByte()
                    )
                    pixelsOut?.set(
                        c + 2,
                        min(255.0, (b * scale + 0.5f).toInt().toDouble()).toInt().toByte()
                    )
                    pixelsOut?.set(c + 3, 255.toByte())
                    x++
                    c += 4
                }
                y++
            }
        }
    }

    class ComputeHistogramApplyFunction(private val type: Type) :
        JavaImageProcessing.ApplyFunctionInterface {
        private lateinit var histograms: Array<IntArray?>
        private lateinit var pixelsRgbF: FloatArray
        private var pixelsWidth = 0

        enum class Type {
            TYPE_RGB,  // returns array of length 3*256, containing the red histogram, followed by green, then blue
            TYPE_LUMINANCE,  // 0.299f*r + 0.587f*g + 0.114f*b
            TYPE_VALUE,  // max(r,g,b)
            TYPE_INTENSITY,  // mean(r, g, b)
            TYPE_LIGHTNESS // mean( min(r,g,b), max(r,g,b) )
        }

        /** For use when we want to operate over a full pixel array, instead of an input supplied to applyFunction().
         */
        fun setPixelsRGBf(pixelsRgbF: FloatArray, pixelsWidth: Int) {
            this.pixelsRgbF = pixelsRgbF
            this.pixelsWidth = pixelsWidth
        }

        override fun init(nThreads: Int) {
            histograms = arrayOfNulls(nThreads)
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            // version for operating on the supplied floating point array in rgb format
            if (type != Type.TYPE_VALUE) throw RuntimeException(
                "type not supported: $type"
            )
            if (histograms[threadIndex] == null) histograms[threadIndex] = IntArray(256)
            for (y in offY..<offY + thisHeight) {
                var indx = 3 * (y * pixelsWidth + offX)
                for (x in offX..<offX + thisWidth) {
                    val r = (pixelsRgbF[indx++] + 0.5f).toInt()
                    val g = (pixelsRgbF[indx++] + 0.5f).toInt()
                    val b = (pixelsRgbF[indx++] + 0.5f).toInt()
                    var value = max(r.toDouble(), g.toDouble()).toInt()
                    value = max(value.toDouble(), b.toDouble()).toInt()
                    value = min(value.toDouble(), 255.0).toInt()
                    value = max(value.toDouble(), 0.0).toInt()
                    histograms[threadIndex]?.get(value)?.let {
                        histograms[threadIndex]!![value] = it + 1
                    }
                }
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: IntArray,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            /*if( MyDebug.LOG )
                Log.d(TAG, "ComputeHistogramApplyFunction.apply [int array]");*/
            if (histograms[threadIndex] == null) histograms[threadIndex] =
                IntArray(if (type == Type.TYPE_RGB) 3 * 256 else 256)

            when (type) {
                Type.TYPE_RGB -> {
                    var c = 0
                    while (c < thisWidth * thisHeight) {
                        // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                        val color = pixels[c]
                        histograms[threadIndex]?.get(((color shr 16) and 0xFF))?.let {
                            histograms[threadIndex]!![((color shr 16) and 0xFF)] = it + 1 // red
                        }
                        histograms[threadIndex]?.get(256 + ((color shr 8) and 0xFF))?.let {
                            histograms[threadIndex]!![(256 + ((color shr 8) and 0xFF))] =
                                it + 1 // green
                        }
                        histograms[threadIndex]?.get(512 + (color and 0xFF))?.let {
                            histograms[threadIndex]!![(512 + (color and 0xFF))] = it + 1 // blue
                        }
                        c++
                    }
                }

                Type.TYPE_LUMINANCE -> {
                    var c = 0
                    while (c < thisWidth * thisHeight) {
                        // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                        val color = pixels[c]
                        val fr = ((color shr 16) and 0xFF).toFloat()
                        val fg = ((color shr 8) and 0xFF).toFloat()
                        val fb = (color and 0xFF).toFloat()
                        val avg = (0.299f * fr + 0.587f * fg + 0.114f * fb)
                        var value = (avg + 0.5).toInt() // round to nearest
                        value = min(value.toDouble(), 255.0).toInt() // just in case
                        histograms[threadIndex]?.get(value)?.let {
                            histograms[threadIndex]!![value] = it + 1
                        }
                        c++
                    }
                }

                Type.TYPE_VALUE -> {
                    var c = 0
                    while (c < thisWidth * thisHeight) {
                        // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                        val color = pixels[c]
                        var value = max(
                            ((color shr 16) and 0xFF).toDouble(),
                            ((color shr 8) and 0xFF).toDouble()
                        ).toInt()
                        value = max(value.toDouble(), (color and 0xFF).toDouble()).toInt()
                        histograms[threadIndex]?.get(value)?.let {
                            histograms[threadIndex]!![value] = it + 1
                        }
                        c++
                    }
                }

                Type.TYPE_INTENSITY -> {
                    var c = 0
                    while (c < thisWidth * thisHeight) {
                        // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                        val color = pixels[c]
                        val fr = ((color shr 16) and 0xFF).toFloat()
                        val fg = ((color shr 8) and 0xFF).toFloat()
                        val fb = (color and 0xFF).toFloat()
                        val avg = (fr + fg + fb) / 3.0f
                        var value = (avg + 0.5).toInt() // round to nearest
                        value = min(value.toDouble(), 255.0).toInt() // just in case
                        histograms[threadIndex]?.get(value)?.let {
                            histograms[threadIndex]!![value] = it + 1
                        }
                        c++
                    }
                }

                Type.TYPE_LIGHTNESS -> {
                    var c = 0
                    while (c < thisWidth * thisHeight) {
                        // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                        val color = pixels[c]
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        var maxValue = max(r.toDouble(), g.toDouble()).toInt()
                        maxValue = max(maxValue.toDouble(), b.toDouble()).toInt()
                        var minValue = min(r.toDouble(), g.toDouble()).toInt()
                        minValue = min(minValue.toDouble(), b.toDouble()).toInt()
                        val avg = (minValue + maxValue) / 2.0f
                        var value = (avg + 0.5).toInt() // round to nearest
                        value = min(value.toDouble(), 255.0).toInt() // just in case
                        histograms[threadIndex]?.get(value)?.let {
                            histograms[threadIndex]!![value] = it + 1
                        }
                        c++
                    }
                }

                else -> throw RuntimeException("unknown: $type")
            }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: ByteArray?,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            /*if( MyDebug.LOG )
                Log.d(TAG, "ComputeHistogramApplyFunction.apply [byte vector array]");*/
            if (histograms[threadIndex] == null) histograms[threadIndex] = IntArray(256)
            var c = 0
            while (c < 4 * thisWidth * thisHeight) {
                // n.b., we increment c inside the loop
                var r = pixels?.get(c++)?.toInt() ?: 0
                var g = pixels?.get(c++)?.toInt() ?: 0
                var b = pixels?.get(c++)?.toInt() ?: 0
                // bytes are signed!
                if (r < 0) r += 256
                if (g < 0) g += 256
                if (b < 0) b += 256
                c++ // skip padding
                var value = max(r.toDouble(), g.toDouble()).toInt()
                value = max(value.toDouble(), b.toDouble()).toInt()
                value = min(value.toDouble(), 255.0).toInt()
                value = max(value.toDouble(), 0.0).toInt()
                histograms[threadIndex]?.get(value)?.let {
                    histograms[threadIndex]!![value] = it + 1
                }
            }
        }

        val histogram: IntArray
            get() {
                val totalHistogram = IntArray(histograms[0]?.size ?: 0)
                // for each histogram, add its entries to the total histogram
                for (histogram in histograms) {
                    if (histogram != null) {
                        for (j in histogram.indices) {
                            totalHistogram[j] += histogram[j]
                        }
                    }
                }
                return totalHistogram
            }
    }

}
