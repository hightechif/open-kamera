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
object JavaImageFunctionsPanorama {
    private const val TAG = "JavaImageFunctionsPanorama"
    private val pyramidBlendingWeights = floatArrayOf(0.05f, 0.25f, 0.4f, 0.25f, 0.05f)
    internal class ConvertToGreyscaleFunction : JavaImageProcessing.ApplyFunctionInterface {
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
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF

                    val value =
                        (0.3 * r.toFloat() + 0.59 * g.toFloat() + 0.11 * b.toFloat()).toInt()

                    // this code is performance critical; note it's faster to avoid calls to Color.argb()
                    pixelsOut?.set(c, value shl 24)
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
    }

    internal class ComputeDerivativesFunction(// output for x derivatives
        private val bitmap_Ix: Bitmap, // output for y derivatives
        private val bitmap_Iy: Bitmap,
        private val bitmapIn: Bitmap
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val width = bitmapIn.width
        private val height = bitmapIn.height
        private lateinit var fastBitmapIn: Array<FastAccessBitmap?>

        override fun init(nThreads: Int) {
            fastBitmapIn = arrayOfNulls<FastAccessBitmap>(nThreads)

            for (i in 0..<nThreads) {
                fastBitmapIn[i] = FastAccessBitmap(bitmapIn)
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
            // we could move these to class members for performance, remember we'd have to have a version per-thread
            val cache_bitmap_Ix = IntArray(thisWidth * thisHeight)
            val cache_bitmap_Iy = IntArray(thisWidth * thisHeight)

            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                fastBitmapIn[threadIndex]?.ensureCache(
                    y - 1,
                    y + 1
                ) // force cache to cover rows needed by this row
                val bitmapInCacheY: Int = fastBitmapIn[threadIndex]!!.cacheY
                val yRelBitmapInCache = y - bitmapInCacheY
                val bitmapInCachePixels: IntArray =
                    fastBitmapIn[threadIndex]!!.cachedPixelsI

                var x = offX
                while (x < offX + thisWidth) {
                    var Ix = 0
                    var Iy = 0
                    if (x >= 1 && x < width - 1 && y >= 1 && y < height - 1) {
                        // use Sobel operator

                        //int pixel0 = fastBitmapIn[threadIndex].getPixel(x-1, y-1) >>> 24;
                        //int pixel0 = bitmapInCachePixels[(yRelBitmapInCache-1)*width+(x-1)] >>> 24;

                        val pixel1 =
                            bitmapInCachePixels[(yRelBitmapInCache - 1) * width + (x)] ushr 24
                        //int pixel2 = bitmapInCachePixels[(yRelBitmapInCache-1)*width+(x+1)] >>> 24;
                        val pixel3 =
                            bitmapInCachePixels[(yRelBitmapInCache) * width + (x - 1)] ushr 24
                        val pixel5 =
                            bitmapInCachePixels[(yRelBitmapInCache) * width + (x + 1)] ushr 24
                        //int pixel6 = bitmapInCachePixels[(yRelBitmapInCache+1)*width+(x-1)] >>> 24;
                        val pixel7 =
                            bitmapInCachePixels[(yRelBitmapInCache + 1) * width + (x)] ushr 24

                        //int pixel8 = bitmapInCachePixels[(yRelBitmapInCache+1)*width+(x+1)] >>> 24;

                        //int iIx = (pixel2 + 2*pixel5 + pixel8) - (pixel0 + 2*pixel3 + pixel6);
                        //int iIy = (pixel6 + 2*pixel7 + pixel8) - (pixel0 + 2*pixel1 + pixel2);
                        //iIx /= 8;
                        //iIy /= 8;
                        var iIx = (pixel5 - pixel3) / 2
                        var iIy = (pixel7 - pixel1) / 2

                        // convert so we can store in range 0-255
                        iIx = max(iIx.toDouble(), -127.0).toInt()
                        iIx = min(iIx.toDouble(), 128.0).toInt()
                        iIx += 127 // iIx now runs from 0 to 255

                        iIy = max(iIy.toDouble(), -127.0).toInt()
                        iIy = min(iIy.toDouble(), 128.0).toInt()
                        iIy += 127 // iIy now runs from 0 to 255

                        Ix = iIx
                        Iy = iIy
                    }

                    //bitmap_Ix.setPixel(x, y, Ix << 24);
                    //bitmap_Iy.setPixel(x, y, Iy << 24);
                    cache_bitmap_Ix[c] = Ix shl 24
                    cache_bitmap_Iy[c] = Iy shl 24
                    x++
                    c++
                }
                y++
            }

            bitmap_Ix.setPixels(
                cache_bitmap_Ix,
                0,
                thisWidth,
                offX,
                offY,
                thisWidth,
                thisHeight
            )
            bitmap_Iy.setPixels(
                cache_bitmap_Iy,
                0,
                thisWidth,
                offX,
                offY,
                thisWidth,
                thisHeight
            )
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
    }

    internal class CornerDetectorFunction(// output
        private val pixelsF: FloatArray,
        private val bitmap_Ix: Bitmap,
        private val bitmap_Iy: Bitmap
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val width = bitmap_Ix.width
        private val height = bitmap_Ix.height
        private lateinit var fast_bitmap_Ix: Array<FastAccessBitmap?>
        private lateinit var fast_bitmap_Iy: Array<FastAccessBitmap?>

        override fun init(nThreads: Int) {
            fast_bitmap_Ix = arrayOfNulls<FastAccessBitmap>(nThreads)
            fast_bitmap_Iy = arrayOfNulls<FastAccessBitmap>(nThreads)

            for (i in 0..<nThreads) {
                fast_bitmap_Ix[i] = FastAccessBitmap(bitmap_Ix)
                fast_bitmap_Iy[i] = FastAccessBitmap(bitmap_Iy)
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
            val radius = 2 // radius for corner detector
            val weights = floatArrayOf(1f, 4f, 6f, 4f, 1f)

            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                fast_bitmap_Ix[threadIndex]?.ensureCache(
                    y - radius,
                    y + radius
                ) // force cache to cover rows needed by this row
                val bitmap_Ix_cache_y: Int = fast_bitmap_Ix[threadIndex]!!.cacheY
                val y_rel_bitmap_Ix_cache = y - bitmap_Ix_cache_y
                val bitmap_Ix_cache_pixels: IntArray =
                    fast_bitmap_Ix[threadIndex]!!.cachedPixelsI

                fast_bitmap_Iy[threadIndex]?.ensureCache(
                    y - radius,
                    y + radius
                ) // force cache to cover rows needed by this row
                val bitmap_Iy_cache_y: Int = fast_bitmap_Iy[threadIndex]!!.cacheY
                val y_rel_bitmap_Iy_cache = y - bitmap_Iy_cache_y
                val bitmap_Iy_cache_pixels: IntArray =
                    fast_bitmap_Iy[threadIndex]!!.cachedPixelsI

                var x = offX
                while (x < offX + thisWidth) {
                    var out = 0f

                    // extra +1 as we won't have derivative info for the outermost pixels (see computeDerivatives)
                    if (x >= radius + 1 && x < width - radius - 1 && y >= radius + 1 && y < height - radius - 1) {
                        var h00 = 0.0f
                        var h01 = 0.0f
                        var h11 = 0.0f
                        for (cy in y - radius..y + radius) {
                            for (cx in x - radius..x + radius) {
                                val dx = cx - x
                                val dy = cy - y

                                var Ix =
                                    bitmap_Ix_cache_pixels[(y_rel_bitmap_Ix_cache + dy) * width + (cx)] ushr 24
                                var Iy =
                                    bitmap_Iy_cache_pixels[(y_rel_bitmap_Iy_cache + dy) * width + (cx)] ushr 24

                                // convert from 0-255 to -127 - +128:
                                Ix -= 127
                                Iy -= 127

                                /*float dist2 = dx*dx + dy*dy;
                                const float sigma2 = 0.25f;
                                float weight = exp(-dist2/(2.0f*sigma2)) / (6.28318530718f*sigma2);
                                //float weight = 1.0;
                                weight /= 65025.0f; // scale from (0, 255) to (0, 1)
                                */
                                val weight = weights[2 + dx] * weights[2 + dy]

                                //weight = 36;
                                h00 += weight * Ix * Ix
                                h01 += weight * Ix * Iy
                                h11 += weight * Iy * Iy
                            }
                        }

                        val det_H = h00 * h11 - h01 * h01
                        val tr_H = h00 + h11
                        //out = det_H - 0.1f*tr_H*tr_H;
                        out = det_H - 0.06f * tr_H * tr_H
                    }

                    pixelsF[y * width + x] = out
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

    internal class LocalMaximumFunction(// input
        private val pixelsF: FloatArray, // output
        private val bytes: ByteArray,
        private val width: Int,
        private val height: Int, private val cornerThreshold: Float
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
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
            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                var x = offX
                while (x < offX + thisWidth) {
                    var out = 0
                    val `in` = pixelsF[y * width + x]
                    bytes[y * width + x] = out.toByte()

                    if (`in` >= cornerThreshold) {
                        //out = 255;
                        // best of 3x3:
                        /*if( x >= 1 && x < width-1 && y >= 1 && y < height-1 ) {
                            if( in > rsGetElementAt_float(bitmap, x-1, y-1) &&
                                in > rsGetElementAt_float(bitmap, x, y-1) &&
                                in > rsGetElementAt_float(bitmap, x+1, y-1) &&

                                in > rsGetElementAt_float(bitmap, x-1, y) &&
                                in > rsGetElementAt_float(bitmap, x+1, y) &&

                                in > rsGetElementAt_float(bitmap, x-1, y+1) &&
                                in > rsGetElementAt_float(bitmap, x, y+1) &&
                                in > rsGetElementAt_float(bitmap, x+1, y+1)
                                ) {
                                out = 255;
                            }
                        }*/
                        // best of 5x5:
                        if (x >= 2 && x < width - 2 && y >= 2 && y < height - 2) {
                            if (`in` > pixelsF[(y - 2) * width + (x - 2)] && `in` > pixelsF[(y - 2) * width + (x - 1)] && `in` > pixelsF[(y - 2) * width + (x)] && `in` > pixelsF[(y - 2) * width + (x + 1)] && `in` > pixelsF[(y - 2) * width + (x + 2)] && `in` > pixelsF[(y - 1) * width + (x - 2)] && `in` > pixelsF[(y - 1) * width + (x - 1)] && `in` > pixelsF[(y - 1) * width + (x)] && `in` > pixelsF[(y - 1) * width + (x + 1)] && `in` > pixelsF[(y - 1) * width + (x + 2)] && `in` > pixelsF[(y) * width + (x - 2)] && `in` > pixelsF[(y) * width + (x - 1)] && `in` > pixelsF[(y) * width + (x + 1)] && `in` > pixelsF[(y) * width + (x + 2)] && `in` > pixelsF[(y + 1) * width + (x - 2)] && `in` > pixelsF[(y + 1) * width + (x - 1)] && `in` > pixelsF[(y + 1) * width + (x)] && `in` > pixelsF[(y + 1) * width + (x + 1)] && `in` > pixelsF[(y + 1) * width + (x + 2)] && `in` > pixelsF[(y + 2) * width + (x - 2)] && `in` > pixelsF[(y + 2) * width + (x - 1)] && `in` > pixelsF[(y + 2) * width + (x)] && `in` > pixelsF[(y + 2) * width + (x + 1)] && `in` > pixelsF[(y + 2) * width + (x + 2)]
                            ) {
                                out = 255
                            }
                        }
                    }

                    bytes[y * width + x] = out.toByte()
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

    class PyramidBlendingComputeErrorFunction(private val bitmap: Bitmap) :
        JavaImageProcessing.ApplyFunctionInterface {
        private lateinit var errors: IntArray // error per thread
        private lateinit var fastBitmap: Array<FastAccessBitmap?>
        private val width = bitmap.width

        override fun init(nThreads: Int) {
            errors = IntArray(nThreads)
            fastBitmap = arrayOfNulls<FastAccessBitmap>(nThreads)

            for (i in 0..<nThreads) {
                fastBitmap[i] = FastAccessBitmap(bitmap)
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

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: IntArray,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                fastBitmap[threadIndex]?.ensureCache(
                    y,
                    y
                ) // force cache to cover rows needed by this row
                val bitmapCacheY: Int = fastBitmap[threadIndex]!!.cacheY
                val yRelBitmapCache = y - bitmapCacheY
                val bitmapCachePixels: IntArray = fastBitmap[threadIndex]!!.cachedPixelsI

                var x = offX
                while (x < offX + thisWidth) {
                    // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                    val color0 = pixels[c]
                    val r0 = (color0 shr 16) and 0xFF
                    val g0 = (color0 shr 8) and 0xFF
                    val b0 = color0 and 0xFF

                    val color1 = bitmapCachePixels[(yRelBitmapCache) * width + (x)]
                    val r1 = (color1 shr 16) and 0xFF
                    val g1 = (color1 shr 8) and 0xFF
                    val b1 = color1 and 0xFF

                    val dr = r0 - r1
                    val dg = g0 - g1
                    val db = b0 - b1
                    val diff2 = dr * dr + dg * dg + db * db
                    if (errors[threadIndex] < 2000000000) { // avoid risk of overflow
                        errors[threadIndex] += diff2
                    }
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

        fun getError(): Int {
            var totalError = 0
            for (error in errors) {
                totalError += error
            }
            return totalError
        }
    }

    internal class ReduceBitmapFunction(private val bitmapIn: Bitmap) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val width = bitmapIn.width
        private val height = bitmapIn.height
        private lateinit var fastBitmapIn: Array<FastAccessBitmap?>

        override fun init(nThreads: Int) {
            fastBitmapIn = arrayOfNulls<FastAccessBitmap>(nThreads)

            for (i in 0..<nThreads) {
                fastBitmapIn[i] = FastAccessBitmap(bitmapIn)
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
            val pixelsOut = output?.cachedPixelsI

            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                val sy = 2 * y

                fastBitmapIn[threadIndex]?.ensureCache(
                    sy - 2,
                    sy + 2
                ) // force cache to cover rows needed by this row
                val bitmapInCacheY: Int = fastBitmapIn[threadIndex]!!.cacheY
                val yRelBitmapInCache = sy - bitmapInCacheY
                val bitmapInCachePixels: IntArray =
                    fastBitmapIn[threadIndex]!!.cachedPixelsI

                var x = offX
                while (x < offX + thisWidth) {
                    val sx = 2 * x

                    if (sx >= 2 && sx < width - 2 && (sy >= 2) and (sy < height - 2)) {
                        var sumFr = 0.0f
                        var sumFg = 0.0f
                        var sumFb = 0.0f

                        for (dy in -2..2) {
                            for (dx in -2..2) {
                                val color =
                                    bitmapInCachePixels[(yRelBitmapInCache + dy) * width + (sx + dx)]
                                //int color = bitmap_in.getPixel(sx+dx, sy+dy);
                                val r = (color shr 16) and 0xFF
                                val g = (color shr 8) and 0xFF
                                val b = color and 0xFF

                                // commented out version might be faster, but needs to be tested as gives slightly different results due to numerical wobble
                                /*float fr = r, fg = g, fb = b;
                                float weight = pyramidBlendingWeights[2+dx] * pyramidBlendingWeights[2+dy];
                                fr *= weight;
                                fg *= weight;
                                fb *= weight;*/
                                val fr =
                                    (r.toFloat()) * pyramidBlendingWeights[2 + dx] * pyramidBlendingWeights[2 + dy]
                                val fg =
                                    (g.toFloat()) * pyramidBlendingWeights[2 + dx] * pyramidBlendingWeights[2 + dy]
                                val fb =
                                    (b.toFloat()) * pyramidBlendingWeights[2 + dx] * pyramidBlendingWeights[2 + dy]
                                sumFr += fr
                                sumFg += fg
                                sumFb += fb
                            }
                        }

                        var r = (sumFr + 0.5f).toInt()
                        var g = (sumFg + 0.5f).toInt()
                        var b = (sumFb + 0.5f).toInt()

                        r = max(0.0, min(255.0, r.toDouble())).toInt()
                        g = max(0.0, min(255.0, g.toDouble())).toInt()
                        b = max(0.0, min(255.0, b.toDouble())).toInt()

                        // this code is performance critical; note it's faster to avoid calls to Color.argb()
                        pixelsOut?.set(c, (255 shl 24) or (r shl 16) or (g shl 8) or b)
                    } else {
                        val color = bitmapInCachePixels[(yRelBitmapInCache) * width + (sx)]
                        //int color = bitmap_in.getPixel(sx, sy);
                        pixelsOut?.set(c, color)
                    }
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

    internal class ReduceBitmapXFunction(private val bitmapIn: Bitmap) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val width = bitmapIn.width
        private lateinit var fastBitmapIn: Array<FastAccessBitmap?>

        override fun init(nThreads: Int) {
            fastBitmapIn = arrayOfNulls<FastAccessBitmap>(nThreads)

            for (i in 0..<nThreads) {
                fastBitmapIn[i] = FastAccessBitmap(bitmapIn)
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
            val pixelsOut = output?.cachedPixelsI

            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                fastBitmapIn[threadIndex]?.ensureCache(
                    y,
                    y
                ) // force cache to cover rows needed by this row
                val bitmapInCacheY: Int = fastBitmapIn[threadIndex]!!.cacheY
                val yRelBitmapInCache = y - bitmapInCacheY
                val bitmapInCachePixels: IntArray =
                    fastBitmapIn[threadIndex]!!.cachedPixelsI

                var x = offX
                while (x < offX + thisWidth) {
                    val sx = 2 * x

                    if (sx >= 2 && sx < width - 2) {
                        var sumFr = 0.0f
                        var sumFg = 0.0f
                        var sumFb = 0.0f

                        /*for(int dx=-2;dx<=2;dx++) {
                            int color = bitmapInCachePixels[(yRelBitmapInCache)*width+(sx+dx)];
                            sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[2+dx];
                            sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[2+dx];
                            sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[2+dx];
                        }*/

                        // unroll loops
                        val offset = (yRelBitmapInCache) * width + (sx)

                        var color = bitmapInCachePixels[offset - 2]
                        sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                        sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                        sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[0]

                        color = bitmapInCachePixels[offset - 1]
                        sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                        sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                        sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[1]

                        color = bitmapInCachePixels[offset]
                        sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                        sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                        sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[2]

                        color = bitmapInCachePixels[offset + 1]
                        sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                        sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                        sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[3]

                        color = bitmapInCachePixels[offset + 2]
                        sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                        sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                        sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[4]

                        // end unroll loops
                        val r = (sumFr + 0.5f).toInt()
                        val g = (sumFg + 0.5f).toInt()
                        val b = (sumFb + 0.5f).toInt()

                        /*r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));*/

                        // this code is performance critical; note it's faster to avoid calls to Color.argb()
                        pixelsOut?.set(c, (255 shl 24) or (r shl 16) or (g shl 8) or b)
                    } else {
                        val color = bitmapInCachePixels[(yRelBitmapInCache) * width + (sx)]
                        //int color = bitmap_in.getPixel(sx, y);
                        pixelsOut?.set(c, color)
                    }
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

    internal class ReduceBitmapYFunction(private val bitmapIn: Bitmap) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val width = bitmapIn.width
        private val height = bitmapIn.height
        private lateinit var fastBitmapIn: Array<FastAccessBitmap?>

        override fun init(nThreads: Int) {
            fastBitmapIn = arrayOfNulls<FastAccessBitmap>(nThreads)

            for (i in 0..<nThreads) {
                fastBitmapIn[i] = FastAccessBitmap(bitmapIn)
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
            val pixelsOut = output?.cachedPixelsI

            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                val sy = 2 * y

                fastBitmapIn[threadIndex]?.ensureCache(
                    sy - 2,
                    sy + 2
                ) // force cache to cover rows needed by this row
                val bitmapInCacheY: Int = fastBitmapIn[threadIndex]!!.cacheY
                val yRelBitmapInCache = sy - bitmapInCacheY
                val bitmapInCachePixels: IntArray =
                    fastBitmapIn[threadIndex]!!.cachedPixelsI

                if ((sy >= 2) and (sy < height - 2)) {
                    var x = offX
                    while (x < offX + thisWidth) {
                        var sumFr = 0.0f
                        var sumFg = 0.0f
                        var sumFb = 0.0f

                        /*for(int dy=-2;dy<=2;dy++) {
    int color = bitmapInCachePixels[(yRelBitmapInCache+dy)*width+(x)];
    sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[2+dy];
    sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[2+dy];
    sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[2+dy];
}*/

                        // unroll loops
                        var color =
                            bitmapInCachePixels[(yRelBitmapInCache - 2) * width + (x)]
                        sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                        sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                        sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[0]

                        color = bitmapInCachePixels[(yRelBitmapInCache - 1) * width + (x)]
                        sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                        sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                        sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[1]

                        color = bitmapInCachePixels[(yRelBitmapInCache) * width + (x)]
                        sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                        sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                        sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[2]

                        color = bitmapInCachePixels[(yRelBitmapInCache + 1) * width + (x)]
                        sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                        sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                        sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[3]

                        color = bitmapInCachePixels[(yRelBitmapInCache + 2) * width + (x)]
                        sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                        sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                        sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[4]

                        // end unroll loops
                        val r = (sumFr + 0.5f).toInt()
                        val g = (sumFg + 0.5f).toInt()
                        val b = (sumFb + 0.5f).toInt()

                        /*r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));*/

                        // this code is performance critical; note it's faster to avoid calls to Color.argb()
                        pixelsOut?.set(c, (255 shl 24) or (r shl 16) or (g shl 8) or b)
                        x++
                        c++
                    }
                } else {
                    var x = offX
                    while (x < offX + thisWidth) {
                        val color = bitmapInCachePixels[(yRelBitmapInCache) * width + (x)]
                        //int color = bitmap_in.getPixel(x, sy);
                        pixelsOut?.set(c, color)
                        x++
                        c++
                    }
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

    internal class ReduceBitmapXFullFunction(// bitmaps in ARGB format
        private val bitmapIn: ByteArray,
        private val bitmapOut: ByteArray,
        private val width: Int // width of bitmapOut (bitmapIn should be twice the width)
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
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
            for (y in offY..<offY + thisHeight) {
                var c = 4 * (y * width + offX) // index into bitmapOut array
                var x = offX
                while (x < offX + thisWidth) {
                    val sx = 2 * x
                    val pixelIndex = 4 * ((y) * (2 * width) + (sx))

                    if (sx >= 2 && sx < (2 * width) - 2) {
                        var sumFr = 0.0f
                        var sumFg = 0.0f
                        var sumFb = 0.0f

                        /*for(int dx=-2;dx<=2;dx++) {
    int color = bitmapInCachePixels[(yRelBitmapInCache)*width+(sx+dx)];
    sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[2+dx];
    sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[2+dx];
    sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[2+dx];
}*/

                        // unroll loops
                        var offset = pixelIndex - 8
                        sumFr += ((bitmapIn[offset + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                        sumFg += ((bitmapIn[offset + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                        sumFb += ((bitmapIn[offset + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]

                        offset = pixelIndex - 4
                        sumFr += ((bitmapIn[offset + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                        sumFg += ((bitmapIn[offset + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                        sumFb += ((bitmapIn[offset + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]

                        offset = pixelIndex
                        sumFr += ((bitmapIn[offset + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                        sumFg += ((bitmapIn[offset + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                        sumFb += ((bitmapIn[offset + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]

                        offset = pixelIndex + 4
                        sumFr += ((bitmapIn[offset + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                        sumFg += ((bitmapIn[offset + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                        sumFb += ((bitmapIn[offset + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]

                        offset = pixelIndex + 8
                        sumFr += ((bitmapIn[offset + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                        sumFg += ((bitmapIn[offset + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                        sumFb += ((bitmapIn[offset + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]

                        // end unroll loops
                        val r = (sumFr + 0.5f).toInt()
                        val g = (sumFg + 0.5f).toInt()
                        val b = (sumFb + 0.5f).toInt()

                        /*r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));*/
                        bitmapOut[c] = 255.toByte()
                        bitmapOut[c + 1] = r.toByte()
                        bitmapOut[c + 2] = g.toByte()
                        bitmapOut[c + 3] = b.toByte()
                    } else {
                        bitmapOut[c] = 255.toByte()
                        bitmapOut[c + 1] = bitmapIn[pixelIndex + 1]
                        bitmapOut[c + 2] = bitmapIn[pixelIndex + 2]
                        bitmapOut[c + 3] = bitmapIn[pixelIndex + 3]
                    }
                    x++
                    c += 4
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
    }

    internal class ReduceBitmapYFullFunction(// bitmaps in ARGB format
        private val bitmapIn: ByteArray,
        private val bitmapOut: ByteArray,
        private val width: Int, // width of bitmapOut (bitmapIn should be the same width)
        // width of bitmapOut (bitmapIn should be twice the height)
        private val height: Int
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
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
            for (y in offY..<offY + thisHeight) {
                var c = 4 * (y * width + offX) // index into bitmapOut array

                val sy = 2 * y

                if ((sy >= 2) and (sy < (2 * height) - 2)) {
                    var x = offX
                    while (x < offX + thisWidth) {
                        var sumFr = 0.0f
                        var sumFg = 0.0f
                        var sumFb = 0.0f

                        /*for(int dy=-2;dy<=2;dy++) {
    int color = bitmapInCachePixels[(yRelBitmapInCache+dy)*width+(x)];
    sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[2+dy];
    sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[2+dy];
    sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[2+dy];
}*/

                        // unroll loops
                        var offset = 4 * ((sy - 2) * (width) + (x))
                        sumFr += ((bitmapIn[offset + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                        sumFg += ((bitmapIn[offset + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                        sumFb += ((bitmapIn[offset + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]

                        offset = 4 * ((sy - 1) * (width) + (x))
                        sumFr += ((bitmapIn[offset + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                        sumFg += ((bitmapIn[offset + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                        sumFb += ((bitmapIn[offset + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]

                        offset = 4 * ((sy) * (width) + (x))
                        sumFr += ((bitmapIn[offset + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                        sumFg += ((bitmapIn[offset + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                        sumFb += ((bitmapIn[offset + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]

                        offset = 4 * ((sy + 1) * (width) + (x))
                        sumFr += ((bitmapIn[offset + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                        sumFg += ((bitmapIn[offset + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                        sumFb += ((bitmapIn[offset + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]

                        offset = 4 * ((sy + 2) * (width) + (x))
                        sumFr += ((bitmapIn[offset + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                        sumFg += ((bitmapIn[offset + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                        sumFb += ((bitmapIn[offset + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]

                        // end unroll loops
                        val r = (sumFr + 0.5f).toInt()
                        val g = (sumFg + 0.5f).toInt()
                        val b = (sumFb + 0.5f).toInt()

                        /*r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));*/
                        bitmapOut[c] = 255.toByte()
                        bitmapOut[c + 1] = r.toByte()
                        bitmapOut[c + 2] = g.toByte()
                        bitmapOut[c + 3] = b.toByte()
                        x++
                        c += 4
                    }
                } else {
                    var x = offX
                    while (x < offX + thisWidth) {
                        val pixelIndex = 4 * ((sy) * (width) + (x))
                        bitmapOut[c] = 255.toByte()
                        bitmapOut[c + 1] = bitmapIn[pixelIndex + 1]
                        bitmapOut[c + 2] = bitmapIn[pixelIndex + 2]
                        bitmapOut[c + 3] = bitmapIn[pixelIndex + 3]
                        x++
                        c += 4
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
    }

    internal class ExpandBitmapFunction(private val bitmapIn: Bitmap) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val width = bitmapIn.width
        private lateinit var fastBitmapIn: Array<FastAccessBitmap?>

        override fun init(nThreads: Int) {
            fastBitmapIn = arrayOfNulls<FastAccessBitmap>(nThreads)

            for (i in 0..<nThreads) {
                fastBitmapIn[i] = FastAccessBitmap(bitmapIn)
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
            val pixelsOut = output?.cachedPixelsI

            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                if (y % 2 == 0) {
                    val sy = y / 2

                    fastBitmapIn[threadIndex]?.ensureCache(
                        sy,
                        sy
                    ) // force cache to cover rows needed by this row
                    val bitmapInCacheY: Int = fastBitmapIn[threadIndex]!!.cacheY
                    val yRelBitmapInCache = sy - bitmapInCacheY
                    val bitmapInCachePixels: IntArray =
                        fastBitmapIn[threadIndex]!!.cachedPixelsI

                    var x = offX
                    while (x < offX + thisWidth) {
                        if (x % 2 == 0) {
                            val sx = x / 2
                            pixelsOut?.set(
                                c,
                                bitmapInCachePixels[(yRelBitmapInCache) * width + (sx)]
                            )
                        } else {
                            pixelsOut?.set(c, (255 shl 24))
                        }
                        x++
                        c++
                    }
                } else {
                    var x = offX
                    while (x < offX + thisWidth) {
                        pixelsOut?.set(c, (255 shl 24))
                        x++
                        c++
                    }
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

    /** Note that this is optimised for being called on a result of ExpandBitmapFunction (where only
     * the top-left pixel in each group of 2x2 will be non-zero), rather than being a general blur
     * function.
     */
    internal class Blur1dXFunction(private val bitmapIn: Bitmap) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val width = bitmapIn.width
        private lateinit var fastBitmapIn: Array<FastAccessBitmap?>

        override fun init(nThreads: Int) {
            fastBitmapIn = arrayOfNulls<FastAccessBitmap>(nThreads)

            for (i in 0..<nThreads) {
                fastBitmapIn[i] = FastAccessBitmap(bitmapIn)
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
            val pixelsOut = output?.cachedPixelsI

            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                if (y % 2 == 1) {
                    // can skip odd y lines, as will be all zeroes (due to the result of ExpandBitmapFunction)
                    var x = offX
                    while (x < offX + thisWidth) {
                        pixelsOut?.set(c, (255 shl 24))
                        x++
                        c++
                    }
                    y++
                    continue
                }

                fastBitmapIn[threadIndex]?.ensureCache(
                    y,
                    y
                ) // force cache to cover rows needed by this row
                val bitmapInCacheY: Int = fastBitmapIn[threadIndex]!!.cacheY
                val yRelBitmapInCache = y - bitmapInCacheY
                val bitmapInCachePixels: IntArray =
                    fastBitmapIn[threadIndex]!!.cachedPixelsI

                val sx = max(offX.toDouble(), 2.0).toInt()
                val ex = min((offX + thisWidth).toDouble(), (width - 2).toDouble()).toInt()

                run {
                    var x = offX
                    while (x < sx) {
                        // x values < 2
                        pixelsOut?.set(
                            c,
                            bitmapInCachePixels[(yRelBitmapInCache) * width + (x)]
                        )
                        x++
                        c++
                    }
                }

                //for(int x=offX;x<offX+thisWidth;x++,c++) {
                run {
                    var x = sx
                    while (x < ex) {
                        //if( x >= 2 && x < width-2 )
                        run {
                            var sumFr = 0.0f
                            var sumFg = 0.0f
                            var sumFb = 0.0f

                            /*for(int dx=-2;dx<=2;dx++) {
                            int color = bitmapInCachePixels[(yRelBitmapInCache)*width+(x+dx)];
                            int r = (color >> 16) & 0xFF;
                            int g = (color >> 8) & 0xFF;
                            int b = color & 0xFF;

                            float fr = ((float)r) * pyramidBlendingWeights[2+dx];
                            float fg = ((float)g) * pyramidBlendingWeights[2+dx];
                            float fb = ((float)b) * pyramidBlendingWeights[2+dx];
                            sumFr += fr;
                            sumFg += fg;
                            sumFb += fb;
                        }*/

                            // unroll loop
                            var color: Int
                            val pixelIndex = (yRelBitmapInCache) * width + x

                            // when blending, we can take advantage of the fact that pixels will be 0 at odd x coordinates (due to the result of ExpandBitmapFunction)
                            if (x % 2 == 1) {
                                // odd coordinate: so only immediately adjacent coordinates will be non-0

                                // pixelIndex-2 is zero

                                color = bitmapInCachePixels[pixelIndex - 1]
                                sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                                sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                                sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[1]

                                // pixelIndex is zero
                                color = bitmapInCachePixels[pixelIndex + 1]
                                sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                                sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                                sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[3]

                                // pixelIndex+2 is zero
                            } else {
                                // even coordinate: so adjacent coordinates will be 0
                                color = bitmapInCachePixels[pixelIndex - 2]
                                sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                                sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                                sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[0]

                                // pixelIndex-1 is zero
                                color = bitmapInCachePixels[pixelIndex]
                                sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                                sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                                sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[2]

                                // pixelIndex+1 is zero
                                color = bitmapInCachePixels[pixelIndex + 2]
                                sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                                sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                                sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                            }

                            /*
                        color = bitmapInCachePixels[pixelIndex-2];
                        sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[0];
                        sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[0];
                        sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[0];

                        color = bitmapInCachePixels[pixelIndex-1];
                        sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[1];
                        sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[1];
                        sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[1];

                        color = bitmapInCachePixels[pixelIndex];
                        sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[2];
                        sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[2];
                        sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[2];

                        color = bitmapInCachePixels[pixelIndex+1];
                        sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[3];
                        sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[3];
                        sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[3];

                        color = bitmapInCachePixels[pixelIndex+2];
                        sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[4];
                        sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[4];
                        sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[4];
                        */

                            // end unrolled loop
                            sumFr *= 2.0.toFloat()
                            sumFg *= 2.0.toFloat()
                            sumFb *= 2.0.toFloat()

                            var r = (sumFr + 0.5f).toInt()
                            var g = (sumFg + 0.5f).toInt()
                            var b = (sumFb + 0.5f).toInt()

                            r = max(0.0, min(255.0, r.toDouble())).toInt()
                            g = max(0.0, min(255.0, g.toDouble())).toInt()
                            b = max(0.0, min(255.0, b.toDouble())).toInt()

                            // this code is performance critical; note it's faster to avoid calls to Color.argb()
                            pixelsOut?.set(c, (255 shl 24) or (r shl 16) or (g shl 8) or b)
                        }
                        x++
                        c++
                    }
                }

                var x = ex
                while (x < offX + thisWidth) {
                    // x values >= width-2
                    pixelsOut?.set(
                        c,
                        bitmapInCachePixels[(yRelBitmapInCache) * width + (x)]
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
    }

    /** Note that this is optimised for being called on a result of ExpandBitmapFunction (where only
     * the top-left pixel in each group of 2x2 will be non-zero), that was then processed with
     * Blur1dXFunction, rather than being a general blur function.
     */
    internal class Blur1dYFunction(private val bitmapIn: Bitmap) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val width = bitmapIn.width
        private val height = bitmapIn.height
        private lateinit var fastBitmapIn: Array<FastAccessBitmap?>

        override fun init(nThreads: Int) {
            fastBitmapIn = arrayOfNulls<FastAccessBitmap>(nThreads)

            for (i in 0..<nThreads) {
                fastBitmapIn[i] = FastAccessBitmap(bitmapIn)
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
            val pixelsOut = output?.cachedPixelsI

            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                fastBitmapIn[threadIndex]?.ensureCache(
                    y - 2,
                    y + 2
                ) // force cache to cover rows needed by this row
                val bitmapInCacheY: Int = fastBitmapIn[threadIndex]!!.cacheY
                val yRelBitmapInCache = y - bitmapInCacheY
                val bitmapInCachePixels: IntArray =
                    fastBitmapIn[threadIndex]!!.cachedPixelsI

                if (y >= 2 && y < height - 2) {
                    var x = offX
                    while (x < offX + thisWidth) {
                        var sumFr = 0.0f
                        var sumFg = 0.0f
                        var sumFb = 0.0f

                        /*for(int dy=-2;dy<=2;dy++) {
                            int color = bitmapInCachePixels[(yRelBitmapInCache+dy)*width+(x)];
                            int r = (color >> 16) & 0xFF;
                            int g = (color >> 8) & 0xFF;
                            int b = color & 0xFF;

                            float fr = ((float)r) * pyramidBlendingWeights[2+dy];
                            float fg = ((float)g) * pyramidBlendingWeights[2+dy];
                            float fb = ((float)b) * pyramidBlendingWeights[2+dy];
                            sumFr += fr;
                            sumFg += fg;
                            sumFb += fb;
                        }*/

                        // unroll loop:
                        var color: Int

                        // when blending, due to having blurred X the result of ExpandBitmapFunction, we will now have odd-y lines being zero, even-y lines being non-zero
                        if (y % 2 == 1) {
                            // odd coordinate: so only immediately adjacent coordinates will be non-0

                            // pixelIndex-2 is zero

                            color =
                                bitmapInCachePixels[(yRelBitmapInCache - 1) * width + (x)]
                            sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                            sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                            sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[1]

                            // pixelIndex is zero
                            color =
                                bitmapInCachePixels[(yRelBitmapInCache + 1) * width + (x)]
                            sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                            sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                            sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[3]

                            // pixelIndex+2 is zero
                        } else {
                            // even coordinate: so adjacent coordinates will be 0
                            color =
                                bitmapInCachePixels[(yRelBitmapInCache - 2) * width + (x)]
                            sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                            sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                            sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[0]

                            // pixelIndex-1 is zero
                            color = bitmapInCachePixels[(yRelBitmapInCache) * width + (x)]
                            sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                            sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                            sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[2]

                            // pixelIndex+1 is zero
                            color =
                                bitmapInCachePixels[(yRelBitmapInCache + 2) * width + (x)]
                            sumFr += (((color shr 16) and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                            sumFg += (((color shr 8) and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                            sumFb += ((color and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                        }

                        /*
                        color = bitmapInCachePixels[(yRelBitmapInCache-2)*width+(x)];
                        sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[0];
                        sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[0];
                        sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[0];

                        color = bitmapInCachePixels[(yRelBitmapInCache-1)*width+(x)];
                        sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[1];
                        sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[1];
                        sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[1];

                        color = bitmapInCachePixels[(yRelBitmapInCache)*width+(x)];
                        sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[2];
                        sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[2];
                        sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[2];

                        color = bitmapInCachePixels[(yRelBitmapInCache+1)*width+(x)];
                        sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[3];
                        sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[3];
                        sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[3];

                        color = bitmapInCachePixels[(yRelBitmapInCache+2)*width+(x)];
                        sumFr += ((float)((color >> 16) & 0xFF)) * pyramidBlendingWeights[4];
                        sumFg += ((float)((color >> 8) & 0xFF)) * pyramidBlendingWeights[4];
                        sumFb += ((float)(color & 0xFF)) * pyramidBlendingWeights[4];
                        */

                        // end unrolled loop
                        sumFr *= 2.0.toFloat()
                        sumFg *= 2.0.toFloat()
                        sumFb *= 2.0.toFloat()

                        var r = (sumFr + 0.5f).toInt()
                        var g = (sumFg + 0.5f).toInt()
                        var b = (sumFb + 0.5f).toInt()

                        r = max(0.0, min(255.0, r.toDouble())).toInt()
                        g = max(0.0, min(255.0, g.toDouble())).toInt()
                        b = max(0.0, min(255.0, b.toDouble())).toInt()

                        // this code is performance critical; note it's faster to avoid calls to Color.argb()
                        pixelsOut?.set(c, (255 shl 24) or (r shl 16) or (g shl 8) or b)
                        x++
                        c++
                    }
                } else {
                    var x = offX
                    while (x < offX + thisWidth) {
                        pixelsOut?.set(
                            c,
                            bitmapInCachePixels[(yRelBitmapInCache) * width + (x)]
                        )
                        x++
                        c++
                    }
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

    /** Alpha isn't written on result for performance.
     */
    internal class ExpandBitmapFullFunction(// bitmaps in ARGB format
        private val bitmapIn: ByteArray,
        private val bitmapOut: ByteArray,
        private val width: Int,
        /** @noinspection FieldCanBeLocal
         */
        private val height: Int // dimensions of bitmapOut (bitmapIn should be half the width and half the height)
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
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
            for (y in offY..<offY + thisHeight) {
                var c = 4 * (y * width + offX) // index into bitmapOut array

                if (y % 2 == 0) {
                    val sy = y / 2

                    /*for(int x=offX;x<offX+thisWidth;x++,c+=4) {
                        if( x % 2 == 0 ) {
                            int sx = x/2;
                            int sc = 4*(sy*(width/2)+sx); // index into bitmapIn array (n.b., width/2 as bitmapIn is half the size)
                            bitmapOut[c] = bitmapIn[sc];
                            bitmapOut[c+1] = bitmapIn[sc+1];
                            bitmapOut[c+2] = bitmapIn[sc+2];
                            bitmapOut[c+3] = bitmapIn[sc+3];
                        }
                        else {
                            bitmapOut[c] = (byte)255;
                        }
                    }*/
                    // copy even x (assumes offX is even)
                    //int savedC = c;
                    var sx = offX / 2
                    while (sx < (offX + thisWidth) / 2) {
                        val sc =
                            4 * (sy * (width / 2) + sx) // index into bitmapIn array (n.b., width/2 as bitmapIn is half the size)
                        //bitmapOut[c] = bitmapIn[sc];
                        bitmapOut[c + 1] = bitmapIn[sc + 1]
                        bitmapOut[c + 2] = bitmapIn[sc + 2]
                        bitmapOut[c + 3] = bitmapIn[sc + 3]
                        sx++
                        c += 8
                    }
                    // skip writing odd x
                    /*
                    // copy odd x
                    c = savedC+4;
                    for(int x=offX+1;x<offX+thisWidth;x+=2,c+=8) {
                        bitmapOut[c] = (byte)255;
                    }
                   */
                }
                /*else {
                    // skip writing odd y
                    for(int x=offX;x<offX+thisWidth;x++,c+=4) {
                        bitmapOut[c] = (byte)255;
                    }
                }*/
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
    }

    /** Note that this is optimised for being called on a result of ExpandBitmapFunction (where only
     * the top-left pixel in each group of 2x2 will be non-zero), rather than being a general blur
     * function.
     * Alpha isn't written on result for performance.
     */
    internal class Blur1dXFullFunction(// bitmaps in ARGB format
        private val bitmapIn: ByteArray,
        private val bitmapOut: ByteArray,
        private val width: Int,
        /** @noinspection FieldCanBeLocal
         */
        private val height: Int
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
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
            for (y in offY..<offY + thisHeight) {
                var c = 4 * (y * width + offX) // index into bitmapOut array
                if (y % 2 == 1) {
                    // can skip odd y lines, as will be all zeroes (due to the result of ExpandBitmapFunction)
                    /*for(int x=offX;x<offX+thisWidth;x++,c+=4) {
                        bitmapOut[c] = (byte)255;
                    }*/
                    continue
                }

                val sx = max(offX.toDouble(), 2.0).toInt()
                val ex = min((offX + thisWidth).toDouble(), (width - 2).toDouble()).toInt()

                run {
                    var x = offX
                    while (x < sx) {
                        // x values < 2
                        //bitmapOut[c] = bitmapIn[c];
                        bitmapOut[c + 1] = bitmapIn[c + 1]
                        bitmapOut[c + 2] = bitmapIn[c + 2]
                        bitmapOut[c + 3] = bitmapIn[c + 3]
                        x++
                        c += 4
                    }
                }

                //for(int x=offX;x<offX+thisWidth;x++,c+=4) {
                run {
                    var x = sx
                    while (x < ex) {
                        //if( x >= 2 && x < width-2 )
                        run {
                            var sumFr = 0.0f
                            var sumFg = 0.0f
                            var sumFb = 0.0f

                            /*for(int dx=-2;dx<=2;dx++) {
                            int index = 4*((y)*width+(x+dx));
                            sumFr += ((float)(bitmapIn[index+1] & 0xFF)) * pyramidBlendingWeights[2+dx];
                            sumFg += ((float)(bitmapIn[index+2] & 0xFF)) * pyramidBlendingWeights[2+dx];
                            sumFb += ((float)(bitmapIn[index+3] & 0xFF)) * pyramidBlendingWeights[2+dx];
                        }*/

                            // unroll loop
                            val pixelIndex = 4 * ((y) * width + (x))
                            var index: Int

                            // when blending, we can take advantage of the fact that pixels will be 0 at odd x coordinates (due to the result of ExpandBitmapFunction)
                            if (x % 2 == 1) {
                                // odd coordinate: so only immediately adjacent coordinates will be non-0

                                // pixelIndex-2 is zero

                                index = pixelIndex - 4
                                sumFr += ((bitmapIn[index + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                                sumFg += ((bitmapIn[index + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                                sumFb += ((bitmapIn[index + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]

                                // pixelIndex is zero
                                index = pixelIndex + 4
                                sumFr += ((bitmapIn[index + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                                sumFg += ((bitmapIn[index + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                                sumFb += ((bitmapIn[index + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]

                                // pixelIndex+2 is zero
                            } else {
                                // even coordinate: so adjacent coordinates will be 0
                                index = pixelIndex - 8
                                sumFr += ((bitmapIn[index + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                                sumFg += ((bitmapIn[index + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                                sumFb += ((bitmapIn[index + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]

                                // pixelIndex-1 is zero
                                index = pixelIndex
                                sumFr += ((bitmapIn[index + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                                sumFg += ((bitmapIn[index + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                                sumFb += ((bitmapIn[index + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]

                                // pixelIndex+1 is zero
                                index = pixelIndex + 8
                                sumFr += ((bitmapIn[index + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                                sumFg += ((bitmapIn[index + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                                sumFb += ((bitmapIn[index + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                            }

                            // end unrolled loop
                            sumFr *= 2.0.toFloat()
                            sumFg *= 2.0.toFloat()
                            sumFb *= 2.0.toFloat()

                            val r = (sumFr + 0.5f).toInt()
                            val g = (sumFg + 0.5f).toInt()
                            val b = (sumFb + 0.5f).toInt()

                            //r = Math.max(0, Math.min(255, r));
                            //g = Math.max(0, Math.min(255, g));
                            //b = Math.max(0, Math.min(255, b));

                            //bitmapOut[c] = (byte)255;
                            bitmapOut[c + 1] = r.toByte()
                            bitmapOut[c + 2] = g.toByte()
                            bitmapOut[c + 3] = b.toByte()
                        }
                        x++
                        c += 4
                    }
                }

                var x = ex
                while (x < offX + thisWidth) {
                    // x values >= width-2
                    //bitmapOut[c] = bitmapIn[c];
                    bitmapOut[c + 1] = bitmapIn[c + 1]
                    bitmapOut[c + 2] = bitmapIn[c + 2]
                    bitmapOut[c + 3] = bitmapIn[c + 3]
                    x++
                    c += 4
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
    }

    /** Note that this is optimised for being called on a result of ExpandBitmapFunction (where only
     * the top-left pixel in each group of 2x2 will be non-zero), that was then processed with
     * Blur1dXFunction, rather than being a general blur function.
     * Alpha isn't written as 255, rather than being based on input alpha.
     */
    internal class Blur1dYFullFunction(// bitmaps in ARGB format
        private val bitmapIn: ByteArray,
        private val bitmapOut: ByteArray,
        private val width: Int, private val height: Int
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
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
            for (y in offY..<offY + thisHeight) {
                var c = 4 * (y * width + offX) // index into bitmapOut array
                if (y >= 2 && y < height - 2) {
                    var x = offX
                    while (x < offX + thisWidth) {
                        var sumFr = 0.0f
                        var sumFg = 0.0f
                        var sumFb = 0.0f

                        /*for(int dy=-2;dy<=2;dy++) {
                            int index = 4*((y+dy)*width+(x));
                            sumFr += ((float)(bitmapIn[index+1] & 0xFF)) * pyramidBlendingWeights[2+dy];
                            sumFg += ((float)(bitmapIn[index+2] & 0xFF)) * pyramidBlendingWeights[2+dy];
                            sumFb += ((float)(bitmapIn[index+3] & 0xFF)) * pyramidBlendingWeights[2+dy];
                        }*/

                        // unroll loop:
                        var index: Int

                        // when blending, due to having blurred X the result of ExpandBitmapFunction, we will now have odd-y lines being zero, even-y lines being non-zero
                        if (y % 2 == 1) {
                            // odd coordinate: so only immediately adjacent coordinates will be non-0

                            // pixelIndex-2 is zero

                            index = 4 * ((y - 1) * width + (x))
                            sumFr += ((bitmapIn[index + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                            sumFg += ((bitmapIn[index + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]
                            sumFb += ((bitmapIn[index + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[1]

                            // pixelIndex is zero
                            index = 4 * ((y + 1) * width + (x))
                            sumFr += ((bitmapIn[index + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                            sumFg += ((bitmapIn[index + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]
                            sumFb += ((bitmapIn[index + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[3]

                            // pixelIndex+2 is zero
                        } else {
                            // even coordinate: so adjacent coordinates will be 0
                            index = 4 * ((y - 2) * width + (x))
                            sumFr += ((bitmapIn[index + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                            sumFg += ((bitmapIn[index + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]
                            sumFb += ((bitmapIn[index + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[0]

                            // pixelIndex-1 is zero
                            index = 4 * ((y) * width + (x))
                            sumFr += ((bitmapIn[index + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                            sumFg += ((bitmapIn[index + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]
                            sumFb += ((bitmapIn[index + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[2]

                            // pixelIndex+1 is zero
                            index = 4 * ((y + 2) * width + (x))
                            sumFr += ((bitmapIn[index + 1].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                            sumFg += ((bitmapIn[index + 2].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                            sumFb += ((bitmapIn[index + 3].toInt() and 0xFF).toFloat()) * pyramidBlendingWeights[4]
                        }

                        // end unrolled loop
                        sumFr *= 2.0.toFloat()
                        sumFg *= 2.0.toFloat()
                        sumFb *= 2.0.toFloat()

                        val r = (sumFr + 0.5f).toInt()
                        val g = (sumFg + 0.5f).toInt()
                        val b = (sumFb + 0.5f).toInt()

                        //r = Math.max(0, Math.min(255, r));
                        //g = Math.max(0, Math.min(255, g));
                        //b = Math.max(0, Math.min(255, b));
                        bitmapOut[c] = 255.toByte()
                        bitmapOut[c + 1] = r.toByte()
                        bitmapOut[c + 2] = g.toByte()
                        bitmapOut[c + 3] = b.toByte()
                        x++
                        c += 4
                    }
                } else {
                    var x = offX
                    while (x < offX + thisWidth) {
                        bitmapOut[c] = 255.toByte()
                        bitmapOut[c + 1] = bitmapIn[c + 1]
                        bitmapOut[c + 2] = bitmapIn[c + 2]
                        bitmapOut[c + 3] = bitmapIn[c + 3]
                        x++
                        c += 4
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
    }

    internal class SubtractBitmapFunction(// output
        private val pixelsRgbf: FloatArray, private val bitmap1: Bitmap
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
        private lateinit var fastBitmap1: Array<FastAccessBitmap?>
        private val width = bitmap1.width

        override fun init(nThreads: Int) {
            fastBitmap1 = arrayOfNulls<FastAccessBitmap>(nThreads)

            for (i in 0..<nThreads) {
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
            var y = offY
            var c = 0
            while (y < offY + thisHeight) {
                var pixelsRgbfIndx = 3 * y * width

                fastBitmap1[threadIndex]?.getPixel(0, y) // force cache to cover row y
                val bitmap1CacheY: Int = fastBitmap1[threadIndex]!!.cacheY
                val yRelBitmap1Cache = y - bitmap1CacheY
                val bitmap1CachePixels: IntArray = fastBitmap1[threadIndex]!!.cachedPixelsI

                var x = offX
                while (x < offX + thisWidth) {
                    // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                    val color0 = pixels[c]
                    val pixel0Fr = ((color0 shr 16) and 0xFF).toFloat()
                    val pixel0Fg = ((color0 shr 8) and 0xFF).toFloat()
                    val pixel0Fb = (color0 and 0xFF).toFloat()

                    //int color1 = fastBitmap1[threadIndex].getPixel(x, y);
                    val color1 = bitmap1CachePixels[(yRelBitmap1Cache) * width + (x)]
                    val pixel1Fr = ((color1 shr 16) and 0xFF).toFloat()
                    val pixel1Fg = ((color1 shr 8) and 0xFF).toFloat()
                    val pixel1Fb = (color1 and 0xFF).toFloat()

                    val fr = pixel0Fr - pixel1Fr
                    val fg = pixel0Fg - pixel1Fg
                    val fb = pixel0Fb - pixel1Fb

                    pixelsRgbf[pixelsRgbfIndx] = fr
                    pixelsRgbf[pixelsRgbfIndx + 1] = fg
                    pixelsRgbf[pixelsRgbfIndx + 2] = fb
                    x++
                    pixelsRgbfIndx += 3
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
    }

    internal class MergefFunction(// input
        private val pixelsRgbf0: FloatArray, // input
        private val pixelsRgbf1: FloatArray, blendWidth: Int,
        private val width: Int,
        private val interpolatedBestPath: IntArray
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val mergeBlendWidth = blendWidth

        //private final int startBlendX;
        init {
            //startBlendX = (fullWidth - mergeBlendWidth)/2;
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
            for (y in offY..<offY + thisHeight) {
                var pixelsRgbfIndx = 3 * y * width
                val midX = interpolatedBestPath[y]

                var x = offX
                while (x < offX + thisWidth) {
                    val pixel0Fr = pixelsRgbf0[pixelsRgbfIndx]
                    val pixel0Fg = pixelsRgbf0[pixelsRgbfIndx + 1]
                    val pixel0Fb = pixelsRgbf0[pixelsRgbfIndx + 2]
                    val pixel1Fr = pixelsRgbf1[pixelsRgbfIndx]
                    val pixel1Fg = pixelsRgbf1[pixelsRgbfIndx + 1]
                    val pixel1Fb = pixelsRgbf1[pixelsRgbfIndx + 2]

                    var alpha =
                        ((x - (midX - mergeBlendWidth / 2)).toFloat()) / mergeBlendWidth.toFloat()
                    alpha = max(alpha.toDouble(), 0.0).toFloat()
                    alpha = min(alpha.toDouble(), 1.0).toFloat()

                    pixelsRgbf0[pixelsRgbfIndx] = (1.0f - alpha) * pixel0Fr + alpha * pixel1Fr
                    pixelsRgbf0[pixelsRgbfIndx + 1] =
                        (1.0f - alpha) * pixel0Fg + alpha * pixel1Fg
                    pixelsRgbf0[pixelsRgbfIndx + 2] =
                        (1.0f - alpha) * pixel0Fb + alpha * pixel1Fb
                    x++
                    pixelsRgbfIndx += 3
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
    }

    internal class MergeFunction(
        private val bitmap1: Bitmap, blendWidth: Int,
        private val interpolatedBestPath: IntArray
    ) : JavaImageProcessing.ApplyFunctionInterface {
        private val width = bitmap1.width
        private lateinit var fastBitmap1: MutableList<FastAccessBitmap>
        private val mergeBlendWidth = blendWidth

        //private final int startBlendX;
        init {
            //startBlendX = (fullWidth - mergeBlendWidth)/2;
        }

        override fun init(nThreads: Int) {
            fastBitmap1 = mutableListOf()

            for (i in 0..<nThreads) {
                fastBitmap1.add(FastAccessBitmap(bitmap1))
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
                val midX = interpolatedBestPath[y]

                fastBitmap1[threadIndex]?.getPixel(0, y) // force cache to cover row y
                val bitmap1CacheY: Int = fastBitmap1[threadIndex]!!.cacheY
                val yRelBitmap1Cache = y - bitmap1CacheY
                val bitmap1CachePixels: IntArray = fastBitmap1[threadIndex]!!.cachedPixelsI

                var x = offX
                while (x < offX + thisWidth) {
                    // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                    val color0 = pixels[c]
                    val pixel0Fr = ((color0 shr 16) and 0xFF).toFloat()
                    val pixel0Fg = ((color0 shr 8) and 0xFF).toFloat()
                    val pixel0Fb = (color0 and 0xFF).toFloat()

                    //int color1 = fastBitmap1[threadIndex].getPixel(x, y);
                    val color1 = bitmap1CachePixels[(yRelBitmap1Cache) * width + (x)]
                    val pixel1Fr = ((color1 shr 16) and 0xFF).toFloat()
                    val pixel1Fg = ((color1 shr 8) and 0xFF).toFloat()
                    val pixel1Fb = (color1 and 0xFF).toFloat()

                    var alpha =
                        ((x - (midX - mergeBlendWidth / 2)).toFloat()) / mergeBlendWidth.toFloat()
                    alpha = max(alpha.toDouble(), 0.0).toFloat()
                    alpha = min(alpha.toDouble(), 1.0).toFloat()

                    val fr = (1.0f - alpha) * pixel0Fr + alpha * pixel1Fr
                    val fg = (1.0f - alpha) * pixel0Fg + alpha * pixel1Fg
                    val fb = (1.0f - alpha) * pixel0Fb + alpha * pixel1Fb

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

    internal class AddBitmapFunction(
        private val pixelsRgbf1: FloatArray,
        private val width: Int
    ) :
        JavaImageProcessing.ApplyFunctionInterface {
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
                var pixelsRgbfIndx = 3 * y * width

                var x = offX
                while (x < offX + thisWidth) {
                    // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                    val color0 = pixels[c]
                    val pixel0Fr = ((color0 shr 16) and 0xFF).toFloat()
                    val pixel0Fg = ((color0 shr 8) and 0xFF).toFloat()
                    val pixel0Fb = (color0 and 0xFF).toFloat()

                    val pixel1Fr = pixelsRgbf1[pixelsRgbfIndx]
                    val pixel1Fg = pixelsRgbf1[pixelsRgbfIndx + 1]
                    val pixel1Fb = pixelsRgbf1[pixelsRgbfIndx + 2]

                    val fr = pixel0Fr + pixel1Fr
                    val fg = pixel0Fg + pixel1Fg
                    val fb = pixel0Fb + pixel1Fb

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
}
