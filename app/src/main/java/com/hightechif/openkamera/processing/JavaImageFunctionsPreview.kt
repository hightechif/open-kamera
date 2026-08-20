/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.processing

import android.graphics.Bitmap
import com.hightechif.openkamera.processing.JavaImageProcessing.CachedBitmap
import com.hightechif.openkamera.processing.JavaImageProcessing.FastAccessBitmap
import kotlin.math.max

object JavaImageFunctionsPreview {
    private const val TAG = "JavaImageFunctionsPreview"

    class ZebraStripesApplyFunction(
        private val zebraStripesThreshold: Int,
        private val zebraStripesForeground: Int,
        private val zebraStripesBackground: Int,
        private val zebraStripesWidth: Int
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
            val pixelsOut = output!!.cachedPixelsI
            var c = 0
            for (y in offY until (offY + thisHeight)) {
                for (x in offX until (offX + thisWidth)) {
                    // this code is performance critical; note it's faster to avoid calls to Color.red/green/blue()
                    val color = pixels[c]

                    var value = max((color shr 16) and 0xFF, (color shr 8) and 0xFF)
                    value = max(value, color and 0xFF)

                    if (value >= zebraStripesThreshold) {
                        val stripe = (x + y) / zebraStripesWidth
                        if (stripe % 2 == 0) {
                            pixelsOut[c] = zebraStripesBackground
                        } else {
                            pixelsOut[c] = zebraStripesForeground
                        }
                    } else {
                        pixelsOut[c] = 0 // transparent (zero alpha)
                    }
                    c++
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
            throw RuntimeException("not implemented")
        }
    }

    class FocusPeakingApplyFunction(private val bitmap: Bitmap) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val width = bitmap.width
        private val height = bitmap.height
        private lateinit var fastBitmap: Array<FastAccessBitmap>

        override fun init(nThreads: Int) {
            fastBitmap = Array(nThreads) { FastAccessBitmap(bitmap) }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
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
            val pixelsOut = output!!.cachedPixelsI
            val fast = fastBitmap[threadIndex]
            var c = 0
            for (y in offY until (offY + thisHeight)) {
                for (x in offX until (offX + thisWidth)) {
                    var strength = 0
                    if (x >= 1 && x < width - 1 && y >= 1 && y < height - 1) {
                        fast.ensureCache(y - 1, y + 1) // force cache to cover rows needed by this row
                        val bitmapCacheY = fast.cacheY
                        val yRelBitmapCache = y - bitmapCacheY
                        val bitmapCachePixels = fast.cachedPixelsI

                        val pixel0c = bitmapCachePixels[(yRelBitmapCache - 1) * width + (x - 1)]
                        val pixel1c = bitmapCachePixels[(yRelBitmapCache - 1) * width + (x)]
                        val pixel2c = bitmapCachePixels[(yRelBitmapCache - 1) * width + (x + 1)]
                        val pixel3c = bitmapCachePixels[(yRelBitmapCache) * width + (x - 1)]
                        val pixel4c = pixels[c]
                        val pixel5c = bitmapCachePixels[(yRelBitmapCache) * width + (x + 1)]
                        val pixel6c = bitmapCachePixels[(yRelBitmapCache + 1) * width + (x - 1)]
                        val pixel7c = bitmapCachePixels[(yRelBitmapCache + 1) * width + (x)]
                        val pixel8c = bitmapCachePixels[(yRelBitmapCache + 1) * width + (x + 1)]

                        val pixel0r = (pixel0c shr 16) and 0xFF
                        val pixel0g = (pixel0c shr 8) and 0xFF
                        val pixel0b = pixel0c and 0xFF

                        val pixel1r = (pixel1c shr 16) and 0xFF
                        val pixel1g = (pixel1c shr 8) and 0xFF
                        val pixel1b = pixel1c and 0xFF

                        val pixel2r = (pixel2c shr 16) and 0xFF
                        val pixel2g = (pixel2c shr 8) and 0xFF
                        val pixel2b = pixel2c and 0xFF

                        val pixel3r = (pixel3c shr 16) and 0xFF
                        val pixel3g = (pixel3c shr 8) and 0xFF
                        val pixel3b = pixel3c and 0xFF

                        val pixel4r = (pixel4c shr 16) and 0xFF
                        val pixel4g = (pixel4c shr 8) and 0xFF
                        val pixel4b = pixel4c and 0xFF

                        val pixel5r = (pixel5c shr 16) and 0xFF
                        val pixel5g = (pixel5c shr 8) and 0xFF
                        val pixel5b = pixel5c and 0xFF

                        val pixel6r = (pixel6c shr 16) and 0xFF
                        val pixel6g = (pixel6c shr 8) and 0xFF
                        val pixel6b = pixel6c and 0xFF

                        val pixel7r = (pixel7c shr 16) and 0xFF
                        val pixel7g = (pixel7c shr 8) and 0xFF
                        val pixel7b = pixel7c and 0xFF

                        val pixel8r = (pixel8c shr 16) and 0xFF
                        val pixel8g = (pixel8c shr 8) and 0xFF
                        val pixel8b = pixel8c and 0xFF

                        val valueR = 8 * pixel4r - pixel0r - pixel1r - pixel2r - pixel3r - pixel5r - pixel6r - pixel7r - pixel8r
                        val valueG = 8 * pixel4g - pixel0g - pixel1g - pixel2g - pixel3g - pixel5g - pixel6g - pixel7g - pixel8g
                        val valueB = 8 * pixel4b - pixel0b - pixel1b - pixel2b - pixel3b - pixel5b - pixel6b - pixel7b - pixel8b
                        strength = valueR * valueR + valueG * valueG + valueB * valueB
                    }

                    if (strength > 256 * 256) {
                        pixelsOut[c] = (255 shl 24) or (255 shl 16) or (255 shl 8) or 255
                    } else {
                        pixelsOut[c] = 0 // transparent (zero alpha)
                    }
                    c++
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
            throw RuntimeException("not implemented")
        }
    }

    class FocusPeakingFilteredApplyFunction(private val bitmap: Bitmap) :
        JavaImageProcessing.ApplyFunctionInterface {
        private val width = bitmap.width
        private val height = bitmap.height
        private lateinit var fastBitmap: Array<FastAccessBitmap>

        override fun init(nThreads: Int) {
            fastBitmap = Array(nThreads) { FastAccessBitmap(bitmap) }
        }

        override fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) {
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
            val pixelsOut = output!!.cachedPixelsI
            val fast = fastBitmap[threadIndex]
            var c = 0
            for (y in offY until (offY + thisHeight)) {
                for (x in offX until (offX + thisWidth)) {
                    var count = 0
                    if (x >= 1 && x < width - 1 && y >= 1 && y < height - 1) {
                        fast.ensureCache(y - 1, y + 1) // force cache to cover rows needed by this row
                        val bitmapCacheY = fast.cacheY
                        val yRelBitmapCache = y - bitmapCacheY
                        val bitmapCachePixels = fast.cachedPixelsI

                        // only need to read one component, as input image is now greyscale
                        val pixel1 = bitmapCachePixels[(yRelBitmapCache - 1) * width + (x)] and 0xFF
                        val pixel3 = bitmapCachePixels[(yRelBitmapCache) * width + (x - 1)] and 0xFF
                        val pixel4 = pixels[c] and 0xFF
                        val pixel5 = bitmapCachePixels[(yRelBitmapCache) * width + (x + 1)] and 0xFF
                        val pixel7 = bitmapCachePixels[(yRelBitmapCache + 1) * width + (x)] and 0xFF

                        if (pixel1 == 255) count++
                        if (pixel3 == 255) count++
                        if (pixel4 == 255) count++
                        if (pixel5 == 255) count++
                        if (pixel7 == 255) count++
                    }

                    if (count >= 3) {
                        pixelsOut[c] = (255 shl 24) or (255 shl 16) or (255 shl 8) or 255
                    } else {
                        pixelsOut[c] = 0 // transparent (zero alpha)
                    }
                    c++
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
            throw RuntimeException("not implemented")
        }
    }
}
