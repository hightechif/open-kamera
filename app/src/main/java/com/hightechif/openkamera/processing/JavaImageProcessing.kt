/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.processing

import android.graphics.Bitmap
import android.util.Log
import com.hightechif.openkamera.utils.MyDebug
import kotlin.math.max
import kotlin.math.min

object JavaImageProcessing {
    private const val TAG = "JavaImageProcessing"

    /** Applies a function to the specified pixels of the supplied bitmap.
     */
    fun applyFunction(
        function: ApplyFunctionInterface,
        bitmap: Bitmap?,
        output: Bitmap?,
        startX: Int,
        startY: Int,
        stopX: Int,
        stopY: Int
    ) {
        applyFunction(function, bitmap, output, startX, startY, stopX, stopY, startX, startY)
    }

    /** Applies a function to the specified pixels of the supplied bitmap.
     */
    fun applyFunction(
        function: ApplyFunctionInterface,
        bitmap: Bitmap?,
        output: Bitmap?,
        startX: Int,
        startY: Int,
        stopX: Int,
        stopY: Int,
        outputStartX: Int,
        outputStartY: Int
    ) {
        if (MyDebug.LOG) Log.d(TAG, "applyFunction [bitmap]")
        val timeS = System.currentTimeMillis()

        val height = stopY - startY
        if (MyDebug.LOG) Log.d(TAG, "height: $height")
        //final int nThreads = 1;
        val nThreads = if (height >= 16) 4 else 1
        //final int nThreads = height >= 16 ? 8 : 1;
        function.init(nThreads)
        val threads = arrayOfNulls<ApplyFunctionThread>(nThreads)
        var stIndx = 0
        for (i in 0..<nThreads) {
            val ndIndx = (((i + 1) * height) / nThreads)
            /*if( MyDebug.LOG )
                Log.d(TAG, "thread " + i + " from " + stIndx + " to " + ndIndx);*/
            threads[i] = ApplyFunctionThread(
                i,
                function,
                bitmap,
                startX,
                startY + stIndx,
                stopX,
                startY + ndIndx
            )
            val t = threads[i]
            if (output != null && t != null) {
                t.setOutput(
                    output,
                    outputStartX,
                    outputStartY + stIndx
                )
            }
            stIndx = ndIndx
        }
        if (MyDebug.LOG) Log.d(TAG, "start threads")
        for (i in 0..<nThreads) {
            threads[i]?.start()
        }
        if (MyDebug.LOG) Log.d(TAG, "wait for threads to complete")
        try {
            for (i in 0..<nThreads) {
                threads[i]?.join()
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "applyFunction threads interrupted")
            throw RuntimeException(e)
        }

        //function.init(1);
        //ApplyFunctionThread thread = new ApplyFunctionThread(0, function, bitmap, startX, startY, stopX, stopY);
        //thread.run();
        if (MyDebug.LOG) Log.d(TAG, "applyFunction time: " + (System.currentTimeMillis() - timeS))
    }

    interface ApplyFunctionInterface {
        fun init(nThreads: Int)
        fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        ) // version with no input

        /**
         * @param pixels An array of pixels for the subset being operated on. I.e., pixels[0] represents the input pixel at (offX, offY), and
         * the pixels array is of size thisWidth*thisHeight.
         */
        fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: IntArray,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        )

        /**
         * @param pixels An array of pixels for the subset being operated on. I.e., pixels[0] represents the input pixel at (offX, offY), and
         * the pixels array is of size 4*thisWidth*thisHeight.
         */
        fun apply(
            output: CachedBitmap?,
            threadIndex: Int,
            pixels: ByteArray?,
            offX: Int,
            offY: Int,
            thisWidth: Int,
            thisHeight: Int
        )
    }

    /** Encapsulates a Bitmap, but optimized for reading individual pixels.
     * This differs to CachedBitmap in that FastAccessBitmap automatically decides which to cache,
     * based on the requested pixels.
     */
    internal class FastAccessBitmap(private val bitmap: Bitmap) {
        private val bitmapWidth = bitmap.width
        private val cacheHeight = min(128.0, bitmap.height.toDouble()).toInt()
        val cachedPixelsI: IntArray = IntArray(bitmapWidth * cacheHeight)
        var cacheY: Int = -1
            private set

        init {
            // better for performance to initialize the cache, rather than having to keep checking if it's initialized
            cache(0)
        }

        private fun cache(y: Int) {
            /*if( MyDebug.LOG )
                Log.d(TAG, ">>> cache: " + y + " [ " + this + " ]");*/
            val newY = max(0.0, (y - 4).toDouble()).toInt()
            this.cacheY = min(newY.toDouble(), (bitmap.height - cacheHeight).toDouble()).toInt()
            bitmap.getPixels(
                cachedPixelsI, 0, bitmapWidth, 0,
                cacheY, bitmapWidth, cacheHeight
            )
        }

        fun getPixel(x: Int, y: Int): Int {
            if (y < cacheY || y >= cacheY + cacheHeight) {
                // update cache
                cache(y)
            }
            // read from cache
            return cachedPixelsI[(y - cacheY) * bitmapWidth + x]
        }

        fun ensureCache(sy: Int, ey: Int) {
            if (ey - sy > cacheHeight) {
                throw RuntimeException("can't cache this many rows: $sy to $ey vs cache_height: $cacheHeight")
            }
            if (sy < cacheY || ey >= cacheY + cacheHeight) {
                cache(sy)
            }
        }
    }

    /** Encapsulates a Bitmap, together with caching of pixels.
     * This differs to FastAccessBitmap in that CachedBitmap requires the caller to actually do the
     * caching.
     */
    class CachedBitmap internal constructor(
        val bitmap: Bitmap?,
        cacheWidth: Int,
        cacheHeight: Int
    ) {
        val cachedPixelsI: IntArray = IntArray(cacheWidth * cacheHeight)
        val cachedPixelsB: ByteArray? = null
    }

    /** Generic thread to apply a Java function to a bunch of pixels.
     */
    private class ApplyFunctionThread(
        private val threadIndex: Int,
        private val function: ApplyFunctionInterface,
        bitmap: Bitmap?,
        private val startX: Int,
        private val startY: Int,
        private val stopX: Int,
        private val stopY: Int
    ) :
        Thread("ApplyFunctionThread") {
        private var input: CachedBitmap? = null
        private var chunkSize: Int // number of lines to process at a time
        private var output: CachedBitmap? = null // optional
        private var outputStartX = 0
        private var outputStartY = 0

        init {
            /*if( MyDebug.LOG ) {
                Log.d(TAG, "    threadIndex: " + threadIndex);
                Log.d(TAG, "    startX: " + startX);
                Log.d(TAG, "    startY: " + startY);
                Log.d(TAG, "    stopX: " + stopX);
                Log.d(TAG, "    stopY: " + stopY);
            }*/
            this.chunkSize = getChunkSize(
                startY,
                stopY
            )
            /*if( MyDebug.LOG )
                Log.d(TAG, "    chunkSize: " + chunkSize);*/
            if (bitmap != null) this.input = CachedBitmap(bitmap, stopX - startX, chunkSize)
            else this.input = null
        }

        fun setOutput(bitmap: Bitmap?, outputStartX: Int, outputStartY: Int) {
            /*if( MyDebug.LOG ) {
                Log.d(TAG, "    outputStartX: " + outputStartX);
                Log.d(TAG, "    outputStartY: " + outputStartY);
            }*/
            this.output = CachedBitmap(bitmap, stopX - startX, chunkSize)
            this.outputStartX = outputStartX
            this.outputStartY = outputStartY
        }

        override fun run() {
            /*if( MyDebug.LOG )
                Log.d(TAG, "ApplyFunctionThread.run");*/
            val width = stopX - startX
            var thisStartY = startY
            val outputShiftY = outputStartY - startY
            /*if( MyDebug.LOG ) {
                Log.d(TAG, "startY: " + startY);
                Log.d(TAG, "outputStartY: " + outputStartY);
                Log.d(TAG, "outputShiftY: " + outputShiftY);
            }*/
            if (input == null && output == null) {
                this.chunkSize = stopY - startY
                /*if( MyDebug.LOG )
                    Log.d(TAG, "reset chunkSize to: " + chunkSize);*/
            }

            val chunkSizeF = chunkSize
            while (thisStartY < stopY) {
                val thisStopY =
                    min((thisStartY + chunkSizeF).toDouble(), stopY.toDouble()).toInt()
                val thisHeight = thisStopY - thisStartY

                //if( MyDebug.LOG )
                //    Log.d(TAG, "chunks from " + thisStartY + " to " + thisStopY);

                //long timeS = System.currentTimeMillis();
                val inputBitmap = input?.bitmap
                val inputPixels = input?.cachedPixelsI
                if (input == null) {
                    // nothing to copy to cache
                    function.apply(output, threadIndex, startX, thisStartY, width, thisHeight)
                } else if (inputBitmap != null && inputPixels != null) {
                    inputBitmap.getPixels(
                        inputPixels,
                        0,
                        width,
                        startX,
                        thisStartY,
                        width,
                        thisHeight
                    )
                    function.apply(
                        output,
                        threadIndex,
                        inputPixels,
                        startX,
                        thisStartY,
                        width,
                        thisHeight
                    )
                }

                if (output != null) {
                    // write cached pixels back to output bitmap
                    val outputBitmap = output!!.bitmap
                    val outputPixels = output!!.cachedPixelsI
                    if (outputBitmap != null && outputPixels != null) {
                        outputBitmap.setPixels(
                            outputPixels,
                            0,
                            width,
                            outputStartX,
                            thisStartY + outputShiftY,
                            width,
                            thisHeight
                        )
                    }
                }

                thisStartY = thisStopY
            }
        }

        companion object {
            private fun getChunkSize(startY: Int, stopY: Int): Int {
                val height = stopY - startY
                //return height;
                //return (int)Math.ceil(height/4.0);
                //return Math.min(512, height);
                return min(64.0, height.toDouble()).toInt()
                //return Math.min(32, height);
            }
        }
    }
}
