package com.hightechif.openkamera.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Environment
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RSInvalidStateException
import android.renderscript.RenderScript
import android.renderscript.Script.LaunchOptions
import android.renderscript.Type
import android.util.Log
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.ScriptC_feature_detector
import com.hightechif.openkamera.ScriptC_pyramid_blending
import com.hightechif.openkamera.processing.HDRProcessor.HistogramInfo
import com.hightechif.openkamera.processing.JavaImageFunctionsPanorama.AddBitmapFunction
import com.hightechif.openkamera.processing.JavaImageFunctionsPanorama.Blur1dXFullFunction
import com.hightechif.openkamera.processing.JavaImageFunctionsPanorama.Blur1dYFullFunction
import com.hightechif.openkamera.processing.JavaImageFunctionsPanorama.ExpandBitmapFullFunction
import com.hightechif.openkamera.processing.JavaImageFunctionsPanorama.MergeFunction
import com.hightechif.openkamera.processing.JavaImageFunctionsPanorama.MergefFunction
import com.hightechif.openkamera.processing.JavaImageFunctionsPanorama.PyramidBlendingComputeErrorFunction
import com.hightechif.openkamera.processing.JavaImageFunctionsPanorama.ReduceBitmapFunction
import com.hightechif.openkamera.processing.JavaImageFunctionsPanorama.ReduceBitmapXFullFunction
import com.hightechif.openkamera.processing.JavaImageFunctionsPanorama.ReduceBitmapYFullFunction
import com.hightechif.openkamera.processing.JavaImageFunctionsPanorama.SubtractBitmapFunction
import com.hightechif.openkamera.utils.MyDebug
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

//import android.renderscript.ScriptIntrinsicResize;

class PanoramaProcessor(private val context: Context, private val hdrProcessor: HDRProcessor) {
    private var rs: RenderScript? =
        null // lazily created, so we don't take up resources if application isn't using panorama

    // we lazily create and cache scripts that would otherwise have to be repeatedly created in a single
    // panorama photo
    // these should be set to null in freeScript(), to help garbage collection
    private var pyramidBlendingScript: ScriptC_pyramid_blending? = null
    private var featureDetectorScript: ScriptC_feature_detector? = null

    private fun freeScripts() {
        if (MyDebug.LOG) Log.d(TAG, "freeScripts")

        pyramidBlendingScript = null
        featureDetectorScript = null
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

    private fun initRenderscript() {
        if (MyDebug.LOG) Log.d(TAG, "initRenderscript")
        if (!HDRProcessor.useRenderscript) {
            throw RuntimeException("shouldn't be using renderscript")
        }
        if (rs == null) {
            // initialise renderscript
            this.rs = RenderScript.create(context)
            if (MyDebug.LOG) Log.d(TAG, "create renderscript object")
        }
    }

    private fun reduceBitmapRS(
        script: ScriptC_pyramid_blending,
        allocation: Allocation
    ): Allocation {
        if (MyDebug.LOG) Log.d(TAG, "reduceBitmapRS")
        val width = allocation.type.x
        val height = allocation.type.y

        val reducedAllocation = Allocation.createTyped(
            rs,
            Type.createXY(rs, Element.RGBA_8888(rs), width / 2, height / 2)
        )

        script.set_bitmap(allocation)
        script.forEach_reduce(reducedAllocation, reducedAllocation)

        return reducedAllocation
    }

    private fun reduceBitmap(bitmap: Bitmap): Bitmap {
        if (MyDebug.LOG) Log.d(TAG, "reduceBitmap")
        var timeS: Long = 0
        if (MyDebug.LOG) timeS = System.currentTimeMillis()

        val width = bitmap.width
        val height = bitmap.height

        val reducedBitmap = Bitmap.createBitmap(width / 2, height / 2, Bitmap.Config.ARGB_8888)

        //final boolean useReduce2d = true;
        val useReduce2d =
            false // faster to do reduce as two 1D passes (note this gives minor differences in resultant images due to numerical wobble)
        if (useReduce2d) {
            val function: ReduceBitmapFunction = ReduceBitmapFunction(bitmap)
            JavaImageProcessing.applyFunction(
                function,
                null,
                reducedBitmap,
                0,
                0,
                reducedBitmap.width,
                reducedBitmap.height
            )
        } else {
            /*
                       // work on bitmap directly:

                       Bitmap reducedBitmapX = Bitmap.createBitmap(width/2, height, Bitmap.Config.ARGB_8888);
                       JavaImageFunctionsPanorama.ReduceBitmapXFunction functionX = new JavaImageFunctionsPanorama.ReduceBitmapXFunction(bitmap);
                       JavaImageProcessing.applyFunction(functionX, null, reducedBitmapX, 0, 0, reduced_bitmap_x.getWidth(), reduced_bitmap_x.getHeight());
                       if( MyDebug.LOG )
                           Log.d(TAG, "### time for reduceBitmapX: " + (System.currentTimeMillis() - timeS));

                       JavaImageFunctionsPanorama.ReduceBitmapYFunction functionY = new JavaImageFunctionsPanorama.ReduceBitmapYFunction(reducedBitmapX);
                       JavaImageProcessing.applyFunction(functionY, null, reducedBitmap, 0, 0, reduced_bitmap.getWidth(), reduced_bitmap.getHeight());

                       reduced_bitmap_x.recycle();
                       */

            // work with temp arrays instead of bitmaps

            var bitmapArgb: ByteArray
            run {
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### reduceBitmap: time after getPixels: " + (System.currentTimeMillis() - timeS)
                )
                // convert int[] array to byte[] array
                val byteBuffer = ByteBuffer.allocate(4 * width * height)
                val intBuffer = byteBuffer.asIntBuffer()
                intBuffer.put(pixels)
                bitmapArgb = byteBuffer.array()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### reduceBitmap: time after converting int array to byte array: " + (System.currentTimeMillis() - timeS)
                )
            }

            var reducedBitmapXArgb: ByteArray = ByteArray(4 * (width / 2) * (height))
            val functionX =
                ReduceBitmapXFullFunction(bitmapArgb, reducedBitmapXArgb, width / 2)
            JavaImageProcessing.applyFunction(functionX, null, null, 0, 0, width / 2, height)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time for reduceBitmapX: " + (System.currentTimeMillis() - timeS)
            )

            // noinspection UnusedAssignment
            // bitmapArgb = null // help garbage collection

            val reducedBitmapArgb = ByteArray(4 * (width / 2) * (height / 2))
            val functionY: ReduceBitmapYFullFunction = ReduceBitmapYFullFunction(
                reducedBitmapXArgb,
                reducedBitmapArgb,
                width / 2,
                height / 2
            )
            JavaImageProcessing.applyFunction(functionY, null, null, 0, 0, width / 2, height / 2)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time for reduceBitmapY: " + (System.currentTimeMillis() - timeS)
            )

            // noinspection UnusedAssignment
            // reducedBitmapXArgb = null // help garbage collection

            run {
                val pixels = IntArray((width / 2) * (height / 2))
                val intBuffer = ByteBuffer.wrap(reducedBitmapArgb).asIntBuffer()
                intBuffer[pixels]
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### reduceBitmap: time after converting byte array to int array: " + (System.currentTimeMillis() - timeS)
                )
                reducedBitmap.setPixels(pixels, 0, width / 2, 0, 0, width / 2, height / 2)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### reduceBitmap: time after setPixels: " + (System.currentTimeMillis() - timeS)
                )
            }
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time for reduceBitmap: " + (System.currentTimeMillis() - timeS)
        )

        return reducedBitmap
    }

    private fun expandBitmapRS(
        script: ScriptC_pyramid_blending,
        allocation: Allocation
    ): Allocation {
        if (MyDebug.LOG) Log.d(TAG, "expandBitmapRS")
        var timeS: Long = 0
        if (MyDebug.LOG) timeS = System.currentTimeMillis()

        val width = allocation.type.x
        val height = allocation.type.y
        val resultAllocation: Allocation

        val expandedAllocation = Allocation.createTyped(
            rs,
            Type.createXY(rs, Element.RGBA_8888(rs), 2 * width, 2 * height)
        )
        if (MyDebug.LOG) Log.d(
            TAG,
            "### expandBitmap: time after creating expanded_allocation: " + (System.currentTimeMillis() - timeS)
        )

        script.set_bitmap(allocation)
        script.forEach_expand(expandedAllocation, expandedAllocation)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### expandBitmap: time after expand: " + (System.currentTimeMillis() - timeS)
        )

        val useBlur2d = false // faster to do blur as two 1D passes
        if (useBlur2d) {
            resultAllocation = Allocation.createTyped(
                rs,
                Type.createXY(rs, Element.RGBA_8888(rs), 2 * width, 2 * height)
            )
            if (MyDebug.LOG) Log.d(
                TAG,
                "### expandBitmap: time after creating result_allocation: " + (System.currentTimeMillis() - timeS)
            )
            script.set_bitmap(expandedAllocation)
            script.forEach_blur(expandedAllocation, resultAllocation)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### expandBitmap: time after blur: " + (System.currentTimeMillis() - timeS)
            )
            expandedAllocation.destroy()
            //resultAllocation = expandedAllocation;
        } else {
            val tempAllocation = Allocation.createTyped(
                rs,
                Type.createXY(rs, Element.RGBA_8888(rs), 2 * width, 2 * height)
            )
            if (MyDebug.LOG) Log.d(
                TAG,
                "### expandBitmap: time after creating temp_allocation: " + (System.currentTimeMillis() - timeS)
            )
            script.set_bitmap(expandedAllocation)
            script.forEach_blur1dX(expandedAllocation, tempAllocation)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### expandBitmap: time after blur1dX: " + (System.currentTimeMillis() - timeS)
            )

            // now re-use expandedAllocation for the resultAllocation
            resultAllocation = expandedAllocation
            script.set_bitmap(tempAllocation)
            script.forEach_blur1dY(tempAllocation, resultAllocation)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### expandBitmap: time after blur1dY: " + (System.currentTimeMillis() - timeS)
            )

            tempAllocation.destroy()
        }

        return resultAllocation
    }

    private fun expandBitmap(bitmap: Bitmap): Bitmap {
        if (MyDebug.LOG) Log.d(TAG, "expandBitmap")
        var timeS: Long = 0
        if (MyDebug.LOG) timeS = System.currentTimeMillis()

        val width = bitmap.width
        val height = bitmap.height

        /*
        // work on bitmap directly:

        Bitmap expandedBitmap = Bitmap.createBitmap(2*width, 2*height, Bitmap.Config.ARGB_8888);
        if( MyDebug.LOG )
            Log.d(TAG, "### expandBitmap: time after create expandedBitmap: " + (System.currentTimeMillis() - timeS));
        JavaImageFunctionsPanorama.ExpandBitmapFunction function = new JavaImageFunctionsPanorama.ExpandBitmapFunction(bitmap);
        JavaImageProcessing.applyFunction(function, null, expandedBitmap, 0, 0, expanded_bitmap.getWidth(), expanded_bitmap.getHeight());
        if( MyDebug.LOG )
            Log.d(TAG, "### expandBitmap: time after expand: " + (System.currentTimeMillis() - timeS));

        Bitmap tempBitmap = Bitmap.createBitmap(2*width, 2*height, Bitmap.Config.ARGB_8888);
        if( MyDebug.LOG )
            Log.d(TAG, "### expandBitmap: time after create tempBitmap: " + (System.currentTimeMillis() - timeS));
        JavaImageFunctionsPanorama.Blur1dXFunction function_blur1dX = new JavaImageFunctionsPanorama.Blur1dXFunction(expandedBitmap);
        JavaImageProcessing.applyFunction(function_blur1dX, null, tempBitmap, 0, 0, temp_bitmap.getWidth(), temp_bitmap.getHeight());
        if( MyDebug.LOG )
            Log.d(TAG, "### expandBitmap: time after blur1dX: " + (System.currentTimeMillis() - timeS));

        // now re-use expandedBitmap for the resultBitmap
        @SuppressWarnings("UnnecessaryLocalVariable")
        Bitmap resultBitmap = expandedBitmap;
        JavaImageFunctionsPanorama.Blur1dYFunction function_blur1dY = new JavaImageFunctionsPanorama.Blur1dYFunction(tempBitmap);
        JavaImageProcessing.applyFunction(function_blur1dY, null, resultBitmap, 0, 0, result_bitmap.getWidth(), result_bitmap.getHeight());
        if( MyDebug.LOG )
            Log.d(TAG, "### expandBitmap: time after blur1dY: " + (System.currentTimeMillis() - timeS));

        temp_bitmap.recycle();
        */

        // work with temp arrays instead of bitmaps
        var bitmapArgb: ByteArray
        run {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### expandBitmap: time after getPixels: " + (System.currentTimeMillis() - timeS)
            )
            /*bitmapArgb = new byte[4*width*height];
            for(int byteI=0,intI=0;intI<width*height;byteI+=4,intI++) {
                int color = pixels[intI];
                bitmapArgb[byteI] = (byte)((color >> 24) & 0xFF);
                bitmapArgb[byteI+1] = (byte)((color >> 16) & 0xFF);
                bitmapArgb[byteI+2] = (byte)((color >> 8) & 0xFF);
                bitmapArgb[byteI+3] = (byte)(color & 0xFF);
            }*/
            // convert int[] array to byte[] array
            val byteBuffer = ByteBuffer.allocate(4 * width * height)
            val intBuffer = byteBuffer.asIntBuffer()
            intBuffer.put(pixels)
            bitmapArgb = byteBuffer.array()
            if (MyDebug.LOG) Log.d(
                TAG,
                "### expandBitmap: time after converting int array to byte array: " + (System.currentTimeMillis() - timeS)
            )
        }

        val expandedBitmapArgb = ByteArray(4 * (2 * width) * (2 * height))
        val function: ExpandBitmapFullFunction =
            ExpandBitmapFullFunction(bitmapArgb, expandedBitmapArgb, 2 * width, 2 * height)
        JavaImageProcessing.applyFunction(function, null, null, 0, 0, 2 * width, 2 * height)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### expandBitmap: time after expand: " + (System.currentTimeMillis() - timeS)
        )

        // noinspection UnusedAssignment
        // bitmapArgb = null // help garbage collection

        /*Bitmap expandedBitmap = Bitmap.createBitmap(2*width, 2*height, Bitmap.Config.ARGB_8888);
        if( MyDebug.LOG )
            Log.d(TAG, "### expandBitmap: time after create expandedBitmap: " + (System.currentTimeMillis() - timeS));
        JavaImageFunctionsPanorama.ExpandBitmapFunction function = new JavaImageFunctionsPanorama.ExpandBitmapFunction(bitmap);
        JavaImageProcessing.applyFunction(function, null, expandedBitmap, 0, 0, expanded_bitmap.getWidth(), expanded_bitmap.getHeight());
        if( MyDebug.LOG )
            Log.d(TAG, "### expandBitmap: time after expand: " + (System.currentTimeMillis() - timeS));

        byte [] expandedBitmapArgb = new byte[4*(2*width)*(2*height)];
        {
            int [] pixels = new int[(2*width)*(2*height)];
            expanded_bitmap.getPixels(pixels, 0, 2*width, 0, 0, 2*width, 2*height);
            for(int byteI=0,intI=0;intI<(2*width)*(2*height);byteI+=4,intI++) {
                int color = pixels[intI];
                expandedBitmapArgb[byteI] = (byte)((color >> 24) & 0xFF);
                expandedBitmapArgb[byteI+1] = (byte)((color >> 16) & 0xFF);
                expandedBitmapArgb[byteI+2] = (byte)((color >> 8) & 0xFF);
                expandedBitmapArgb[byteI+3] = (byte)(color & 0xFF);
            }
            expanded_bitmap.recycle();
        }*/
        var tempBitmapArgb: ByteArray = ByteArray(4 * (2 * width) * (2 * height))
        val function_blur1dX: Blur1dXFullFunction =
            Blur1dXFullFunction(expandedBitmapArgb, tempBitmapArgb, 2 * width, 2 * height)
        JavaImageProcessing.applyFunction(function_blur1dX, null, null, 0, 0, 2 * width, 2 * height)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### expandBitmap: time after blur1dX: " + (System.currentTimeMillis() - timeS)
        )

        /*Bitmap tempBitmap = Bitmap.createBitmap(2*width, 2*height, Bitmap.Config.ARGB_8888);
        if( MyDebug.LOG )
            Log.d(TAG, "### expandBitmap: time after create tempBitmap: " + (System.currentTimeMillis() - timeS));
        JavaImageFunctionsPanorama.Blur1dXFunction function_blur1dX = new JavaImageFunctionsPanorama.Blur1dXFunction(expandedBitmap);
        JavaImageProcessing.applyFunction(function_blur1dX, null, tempBitmap, 0, 0, temp_bitmap.getWidth(), temp_bitmap.getHeight());
        if( MyDebug.LOG )
            Log.d(TAG, "### expandBitmap: time after blur1dX: " + (System.currentTimeMillis() - timeS));
        expanded_bitmap.recycle();

        byte [] tempBitmapArgb = new byte[4*(2*width)*(2*height)];
        {
            int [] pixels = new int[(2*width)*(2*height)];
            temp_bitmap.getPixels(pixels, 0, 2*width, 0, 0, 2*width, 2*height);
            for(int byteI=0,intI=0;intI<(2*width)*(2*height);byteI+=4,intI++) {
                int color = pixels[intI];
                tempBitmapArgb[byteI] = (byte)((color >> 24) & 0xFF);
                tempBitmapArgb[byteI+1] = (byte)((color >> 16) & 0xFF);
                tempBitmapArgb[byteI+2] = (byte)((color >> 8) & 0xFF);
                tempBitmapArgb[byteI+3] = (byte)(color & 0xFF);
            }
            temp_bitmap.recycle();
        }*/

        //byte [] resultBitmapArgb = new byte[4*(2*width)*(2*height)];
        // now re-use expandedBitmap for the resultBitmap
        val resultBitmapArgb = expandedBitmapArgb

        val function_blur1dY: Blur1dYFullFunction =
            Blur1dYFullFunction(tempBitmapArgb, resultBitmapArgb, 2 * width, 2 * height)
        JavaImageProcessing.applyFunction(function_blur1dY, null, null, 0, 0, 2 * width, 2 * height)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### expandBitmap: time after blur1dY: " + (System.currentTimeMillis() - timeS)
        )

        // noinspection UnusedAssignment
        // tempBitmapArgb = null // help garbage collection

        val resultBitmap = Bitmap.createBitmap(2 * width, 2 * height, Bitmap.Config.ARGB_8888)
        run {
            val pixels = IntArray((2 * width) * (2 * height))
            /*for(int byteI=0,intI=0;intI<(2*width)*(2*height);byteI+=4,intI++) {
                int a = resultBitmapArgb[byteI] & 0xFF;
                int r = resultBitmapArgb[byteI+1] & 0xFF;
                int g = resultBitmapArgb[byteI+2] & 0xFF;
                int b = resultBitmapArgb[byteI+3] & 0xFF;
                pixels[intI] = (a << 24) | (r << 16) | (g << 8) | b;
            }*/
            val intBuffer = ByteBuffer.wrap(resultBitmapArgb).asIntBuffer()
            intBuffer[pixels]
            if (MyDebug.LOG) Log.d(
                TAG,
                "### expandBitmap: time after converting byte array to int array: " + (System.currentTimeMillis() - timeS)
            )
            resultBitmap.setPixels(pixels, 0, 2 * width, 0, 0, 2 * width, 2 * height)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### expandBitmap: time after setPixels: " + (System.currentTimeMillis() - timeS)
            )
        }

        return resultBitmap
    }

    /** Creates an allocation where each pixel equals the pixel from allocation0 minus the corresponding
     * pixel from allocation1.
     */
    private fun subtractBitmapRS(
        script: ScriptC_pyramid_blending,
        allocation0: Allocation,
        allocation1: Allocation
    ): Allocation {
        if (MyDebug.LOG) Log.d(TAG, "subtractBitmapRS")
        val width = allocation0.type.x
        val height = allocation0.type.y
        if (allocation1.type.x != width || allocation1.type.y != height) {
            Log.e(TAG, "allocations of different dimensions")
            throw RuntimeException()
        }
        val resultAllocation =
            Allocation.createTyped(rs, Type.createXY(rs, Element.F32_3(rs), width, height))
        script.set_bitmap(allocation1)
        script.forEach_subtract(allocation0, resultAllocation)

        return resultAllocation
    }

    /** Creates a floating point array represending a bitmap where each pixel equals the pixel from
     * bitmap0 minus the corresponding pixel from bitmap1.
     */
    private fun subtractBitmap(bitmap0: Bitmap, bitmap1: Bitmap): FloatArray {
        if (MyDebug.LOG) Log.d(TAG, "subtractBitmap")
        val width = bitmap0.width
        val height = bitmap0.height
        if (bitmap1.width != width || bitmap1.height != height) {
            Log.e(TAG, "bitmaps of different dimensions")
            throw RuntimeException()
        }
        val resultRgbf = FloatArray(3 * width * height)

        val function: SubtractBitmapFunction = SubtractBitmapFunction(resultRgbf, bitmap1)
        JavaImageProcessing.applyFunction(
            function,
            bitmap0,
            null,
            0,
            0,
            bitmap0.width,
            bitmap0.height
        )

        return resultRgbf
    }

    /** Updates allocation0 such that each pixel equals the pixel from allocation0 plus the
     * corresponding pixel from allocation1.
     * allocation0 should be of type RGBA_8888, allocation1 should be of type F32_3.
     */
    private fun addBitmapRS(
        script: ScriptC_pyramid_blending,
        allocation0: Allocation,
        allocation1: Allocation
    ) {
        if (MyDebug.LOG) Log.d(TAG, "addBitmapRS")
        val width = allocation0.type.x
        val height = allocation0.type.y
        if (allocation1.type.x != width || allocation1.type.y != height) {
            Log.e(TAG, "allocations of different dimensions")
            throw RuntimeException()
        }
        script.set_bitmap(allocation1)
        script.forEach_add(allocation0, allocation0)
    }

    /** Updates bitmap0 such that each pixel equals the pixel from bitmap0 plus the
     * corresponding pixel from bitmap1.
     * bitmap0 should be of type RGBA_8888, bitmap1 should be of type RGBf.
     */
    private fun addBitmap(bitmap0: Bitmap, bitmap1: FloatArray) {
        if (MyDebug.LOG) Log.d(TAG, "addBitmap")
        val width = bitmap0.width
        val height = bitmap0.height
        if (bitmap1.size != 3 * width * height) {
            Log.e(TAG, "bitmaps of different dimensions")
            throw RuntimeException()
        }
        val function: AddBitmapFunction = AddBitmapFunction(bitmap1, width)
        JavaImageProcessing.applyFunction(
            function,
            bitmap0,
            bitmap0,
            0,
            0,
            bitmap0.width,
            bitmap0.height
        )
    }

    private fun createGaussianPyramidRS(
        script: ScriptC_pyramid_blending,
        bitmap: Bitmap,
        nLevels: Int
    ): MutableList<Allocation?> {
        if (MyDebug.LOG) Log.d(TAG, "createGaussianPyramidRS")
        val pyramid: MutableList<Allocation?> = ArrayList()

        var allocation = Allocation.createFromBitmap(rs, bitmap)
        pyramid.add(allocation)
        for (i in 0..<nLevels) {
            allocation = reduceBitmapRS(script, allocation)
            pyramid.add(allocation)
        }

        return pyramid
    }

    private fun createGaussianPyramid(bitmap: Bitmap, nLevels: Int): MutableList<Bitmap?> {
        var bitmap = bitmap
        if (MyDebug.LOG) Log.d(TAG, "createGaussianPyramid")
        val pyramid: MutableList<Bitmap?> = ArrayList()

        pyramid.add(bitmap)
        for (i in 0..<nLevels) {
            bitmap = reduceBitmap(bitmap)
            pyramid.add(bitmap)
        }

        return pyramid
    }

    /** Creates a laplacian pyramid of the supplied bitmap, ordered from bottom to top. The i-th
     * entry is equal to [G(i) - G'(i+1)], where G(i) is the i-th level of the gaussian pyramid,
     * and G' is created by expanding a level of the gaussian pyramid; except the last entry
     * is simply equal to the last (i.e., top) level of the gaussian pyramid.
     * The allocations are of type floating point (F32_3), except the last which is of type
     * RGBA_8888.
     */
    private fun createLaplacianPyramidRS(
        script: ScriptC_pyramid_blending,
        bitmap: Bitmap,
        nLevels: Int,
        name: String
    ): List<Allocation> {
        if (MyDebug.LOG) Log.d(TAG, "createLaplacianPyramidRS")
        var timeS: Long = 0
        if (MyDebug.LOG) timeS = System.currentTimeMillis()

        val gaussianPyramid = createGaussianPyramidRS(script, bitmap, nLevels)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### createLaplacianPyramid: time after createGaussianPyramid: " + (System.currentTimeMillis() - timeS)
        )
        /*if( MyDebug.LOG )
        {
            // debug
            savePyramid("gaussian", gaussianPyramid);
        }*/
        val pyramid: MutableList<Allocation> = ArrayList()

        for (i in 0..<gaussianPyramid.size - 1) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "createLaplacianPyramid: i = $i"
            )
            val thisGauss = gaussianPyramid[i]!!
            val nextGauss = gaussianPyramid[i + 1]!!
            val nextGaussExpanded = expandBitmapRS(script, nextGauss)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### createLaplacianPyramid: time after expandBitmap for level " + i + ": " + (System.currentTimeMillis() - timeS)
            )
            if (MyDebug.LOG) {
                Log.d(TAG, "this_gauss: " + thisGauss.type.x + " , " + thisGauss.type.y)
                Log.d(TAG, "next_gauss: " + nextGauss.type.x + " , " + nextGauss.type.y)
                Log.d(
                    TAG,
                    "next_gauss_expanded: " + nextGaussExpanded.type.x + " , " + nextGaussExpanded.type.y
                )
            }
            /*if( MyDebug.LOG )
            {
                // debug
                saveAllocation(name + "_this_gauss_" + i + ".jpg", thisGauss);
                saveAllocation(name + "_next_gauss_expanded_" + i + ".jpg", nextGaussExpanded);
            }*/
            val difference = subtractBitmapRS(script, thisGauss, nextGaussExpanded)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### createLaplacianPyramid: time after subtractBitmap for level " + i + ": " + (System.currentTimeMillis() - timeS)
            )
            /*if( MyDebug.LOG )
            {
                // debug
                saveAllocation(name + "_difference_" + i + ".jpg", difference);
            }*/
            pyramid.add(difference)

            //pyramid.add(thisGauss);
            thisGauss.destroy()
            gaussianPyramid[i] = null // to help garbage collection
            nextGaussExpanded.destroy()
            if (MyDebug.LOG) Log.d(
                TAG,
                "### createLaplacianPyramid: time after level " + i + ": " + (System.currentTimeMillis() - timeS)
            )
        }
        pyramid.add(gaussianPyramid[gaussianPyramid.size - 1]!!)

        return pyramid
    }

    private class LaplacianPyramid {
        val diffs: MutableList<FloatArray> =
            ArrayList() // floating point diffs, i-th entry equal to [G(i) - G'(i+1)], where G(i) is the i-th level of the gaussian pyramid
        val widths: MutableList<Int> =
            ArrayList() // width of each floating point bitmap in diffs
        val heights: MutableList<Int> =
            ArrayList() // width of each floating point bitmap in diffs
        var topLevel: Bitmap? = null

        fun addDiff(diff: FloatArray, width: Int, height: Int) {
            diffs.add(diff)
            widths.add(width)
            heights.add(height)
        }

    }

    /** Creates a laplacian pyramid of the supplied bitmap, ordered from bottom to top. The i-th
     * entry of the diffs array is equal to [G(i) - G'(i+1)], where G(i) is the i-th level of the gaussian pyramid,
     * and G' is created by expanding a level of the gaussian pyramid. The last
     * (i.e., top) level of the gaussian pyramid is stored as topLevel.
     * The diffs are of type floating point (RGB); the topLevel is of type
     * RGBA_8888.
     */
    private fun createLaplacianPyramid(
        bitmap: Bitmap,
        nLevels: Int,
        name: String
    ): LaplacianPyramid {
        if (MyDebug.LOG) Log.d(TAG, "createLaplacianPyramid")
        var timeS: Long = 0
        if (MyDebug.LOG) timeS = System.currentTimeMillis()

        val gaussianPyramid = createGaussianPyramid(bitmap, nLevels)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### createLaplacianPyramid: time after createGaussianPyramid: " + (System.currentTimeMillis() - timeS)
        )

        /*if( MyDebug.LOG )
        {
            // debug
            savePyramid("gaussian", gaussianPyramid);
        }*/

        /*List<Allocation> gaussianPyramid_rs = new ArrayList<>();
        for(Bitmap bm : gaussianPyramid) {
            Allocation allocation = Allocation.createFromBitmap(rs, bm);
            gaussianPyramid_rs.add(allocation);
        }*/

        //List<Allocation> pyramid = new ArrayList<>();
        val pyramid = LaplacianPyramid()

        for (i in 0..<gaussianPyramid.size - 1) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "createLaplacianPyramid: i = $i"
            )
            //Allocation thisGaussRs = gaussianPyramid_rs.get(i);
            val thisGauss = gaussianPyramid[i]!!
            //Allocation nextGaussRs = gaussianPyramid_rs.get(i+1);
            val nextGauss = gaussianPyramid[i + 1]!!
            //Allocation nextGaussExpandedRs = expandBitmap(script, nextGauss);
            val nextGaussExpanded = expandBitmap(nextGauss)

            if (MyDebug.LOG) Log.d(
                TAG,
                "### createLaplacianPyramid: time after expandBitmap for level " + i + ": " + (System.currentTimeMillis() - timeS)
            )
            /*if( MyDebug.LOG )
            {
                // debug
                saveAllocation(name + "_this_gauss_" + i + ".jpg", thisGauss);
                saveAllocation(name + "_next_gauss_expanded_" + i + ".jpg", nextGaussExpanded);
            }*/
            //Allocation nextGaussExpandedRs = Allocation.createFromBitmap(rs, nextGaussExpanded);
            //Allocation difference = subtractBitmapRS(script, thisGaussRs, nextGaussExpandedRs);
            val differenceRgbf = subtractBitmap(thisGauss, nextGaussExpanded)
            /*Allocation difference = Allocation.createTyped(rs, Type.createXY(rs, Element.F32_3(rs), this_gauss.getWidth(), this_gauss.getHeight()));
            HDRProcessor.RGBfToAllocation(differenceRgbf, difference, this_gauss.getWidth(), this_gauss.getHeight());
            pyramid.add(difference);
            //pyramid.add(thisGauss);
            */
            pyramid.addDiff(differenceRgbf, thisGauss.width, thisGauss.height)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### createLaplacianPyramid: time after subtractBitmap for level " + i + ": " + (System.currentTimeMillis() - timeS)
            )

            /*this_gauss_rs.destroy();
            gaussianPyramid_rs.set(i, null); // to help garbage collection*/
            thisGauss.recycle()
            gaussianPyramid[i] = null // to help garbage collection
            nextGaussExpanded.recycle()
            //next_gauss_expanded_rs.destroy();
            if (MyDebug.LOG) Log.d(
                TAG,
                "### createLaplacianPyramid: time after level " + i + ": " + (System.currentTimeMillis() - timeS)
            )
        }
        //pyramid.add(gaussianPyramid_rs.get(gaussianPyramid.size()-1));
        pyramid.topLevel = gaussianPyramid[gaussianPyramid.size - 1]

        return pyramid
    }

    private fun collapseLaplacianPyramidRS(
        script: ScriptC_pyramid_blending,
        pyramid: List<Allocation>
    ): Bitmap {
        if (MyDebug.LOG) Log.d(TAG, "collapseLaplacianPyramidRS")

        var allocation = pyramid[pyramid.size - 1]
        var first = true
        for (i in pyramid.size - 2 downTo 0) {
            val expandedAllocation = expandBitmapRS(script, allocation)
            if (!first) {
                allocation.destroy()
            }
            addBitmapRS(script, expandedAllocation, pyramid[i])
            allocation = expandedAllocation
            first = false
        }

        val width = allocation.type.x
        val height = allocation.type.y
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        allocation.copyTo(bitmap)
        if (!first) {
            allocation.destroy()
        }
        return bitmap
    }

    private fun collapseLaplacianPyramid(pyramid: LaplacianPyramid): Bitmap? {
        if (MyDebug.LOG) Log.d(TAG, "collapseLaplacianPyramid")

        var bitmap = pyramid.topLevel
        for (i in pyramid.diffs.indices.reversed()) {
            val expandedBitmap = expandBitmap(bitmap!!)
            bitmap.recycle()
            addBitmap(expandedBitmap, pyramid.diffs[i])
            bitmap = expandedBitmap
        }

        return bitmap
    }

    private fun computeInterpolatedBestPath(
        interpolatedBestPath: IntArray,
        width: Int,
        height: Int,
        blendWidth: Int,
        bestPath: IntArray,
        bestPathNX: Int
    ) {
        val bestPathYScale = bestPath.size / height.toFloat()
        for (y in 0..<height) {
            if (false) {
                // no interpolation:
                val bestPathYIndex = ((y + 0.5f) * bestPathYScale).toInt()
                val bestPathValue = bestPath[bestPathYIndex]
                //interpolatedBestPath[y] = (int)((bestPathValue+1) * bestPathXWidth + 0.5f);
                val alpha = bestPathValue / (bestPathNX - 1.0f)
                val frac = (1.0f - alpha) * 0.25f + alpha * 0.75f
                interpolatedBestPath[y] = (frac * width + 0.5f).toInt()
                /*if( MyDebug.LOG ) {
                        Log.d(TAG, "    interpolatedBestPath[" + y + "]: " + interpolatedBestPath[y] + " (bestPathValue " + bestPathValue + ")");
                    }*/
            }
            //if( false )
            run {
                // linear interpolation
                var bestPathYIndex = ((y + 0.5f) * bestPathYScale)
                val bestPathValue: Float
                if (bestPathYIndex <= 0.5f) {
                    bestPathValue = bestPath[0].toFloat()
                } else if (bestPathYIndex >= bestPath.size - 1 + 0.5f) {
                    bestPathValue = bestPath[bestPath.size - 1].toFloat()
                } else {
                    bestPathYIndex -= 0.5f
                    val bestPathYIndexI = bestPathYIndex.toInt()
                    val linearAlpha = bestPathYIndex - bestPathYIndexI
                    //float alpha = linearAlpha;
                    //final float edgeLength = 0.25f;
                    val edgeLength = 0.1f
                    val alpha = if (linearAlpha < edgeLength) 0.0f
                    else if (linearAlpha > 1.0f - edgeLength) 1.0f
                    else (linearAlpha - edgeLength) / (1.0f - 2.0f * edgeLength)
                    val prevBestPath = bestPath[bestPathYIndexI]
                    val nextBestPath = bestPath[bestPathYIndexI + 1]
                    bestPathValue = (1.0f - alpha) * prevBestPath + alpha * nextBestPath
                    /*if( MyDebug.LOG ) {
                            Log.d(TAG, "    alpha: " + alpha);
                            Log.d(TAG, "    prevBestPath: " + prevBestPath);
                            Log.d(TAG, "    nextBestPath: " + nextBestPath);
                        }*/
                }
                //interpolatedBestPath[y] = (int)((bestPathValue+1) * bestPathXWidth + 0.5f);
                val alpha = bestPathValue / (bestPathNX - 1.0f)
                val frac = (1.0f - alpha) * 0.25f + alpha * 0.75f
                interpolatedBestPath[y] = (frac * width + 0.5f).toInt()
            }
            if (interpolatedBestPath[y] - blendWidth / 2 < 0) {
                Log.e(TAG, "    interpolated_best_path[" + y + "]: " + interpolatedBestPath[y])
                Log.e(TAG, "    blend_width: $blendWidth")
                Log.e(TAG, "    width: $width")
                throw RuntimeException("blend window runs off left hand size")
            } else if (interpolatedBestPath[y] + blendWidth / 2 > width) {
                Log.e(TAG, "    interpolated_best_path[" + y + "]: " + interpolatedBestPath[y])
                Log.e(TAG, "    blend_width: $blendWidth")
                Log.e(TAG, "    width: $width")
                throw RuntimeException("blend window runs off right hand size")
            }
        }
    }

    /** Updates every allocation in pyramid0 to be a blend from the left hand of pyramid0 to the
     * right hand of pyramid1.
     * Note that the width of the blend region will be half of the width of each image.
     * @param bestPath If non-null, the blend region will follow the supplied best path.
     */
    private fun mergePyramidsRS(
        script: ScriptC_pyramid_blending,
        pyramid0: List<Allocation>,
        pyramid1: List<Allocation>,
        bestPath: IntArray?,
        bestPathNX: Int
    ) {
        var bestPath = bestPath
        var bestPathNX = bestPathNX
        if (MyDebug.LOG) Log.d(TAG, "mergePyramidsRS")

        if (bestPath == null) {
            bestPath = IntArray(1)
            bestPathNX = 3
            bestPath[0] = 1
            //bestPath[0] = 2; // test
        }
        if (MyDebug.LOG) {
            for (i in bestPath.indices) Log.d(TAG, "best_path[" + i + "]: " + bestPath[i])
        }

        //Allocation bestPathAllocation = Allocation.createSized(rs, Element.I32(rs), best_path.length);
        //script.bindBestPath(bestPathAllocation);
        //bestPathAllocation.copyFrom(bestPath);
        var maxHeight = 0
        for (i in pyramid0.indices) {
            val allocation0 = pyramid0[i]
            val height = allocation0.type.y
            maxHeight = max(maxHeight.toDouble(), height.toDouble()).toInt()
        }

        val interpolatedbestPathAllocation = Allocation.createSized(rs, Element.I32(rs), maxHeight)
        script.bind_interpolated_best_path(interpolatedbestPathAllocation)
        val interpolatedBestPath = IntArray(maxHeight)

        for (i in pyramid0.indices) {
            val allocation0 = pyramid0[i]
            val allocation1 = pyramid1[i]

            val width = allocation0.type.x
            val height = allocation0.type.y
            if (allocation1.type.x != width || allocation1.type.y != height) {
                Log.e(TAG, "allocations of different dimensions")
                throw RuntimeException()
            } else if (allocation0.type.element.dataType != allocation1.type.element.dataType) {
                Log.e(TAG, "allocations of different data types")
                throw RuntimeException()
            }

            script.set_bitmap(allocation1)

            // when using bestPath, we have a narrower region to blend across
            //int blendWindowWidth = width;
            val blendWindowWidth = width / 2
            //int blendWidth = (i==pyramid0.size()-1) ? blendWindowWidth : 2;
            var blendWidth: Int
            if (i == pyramid0.size - 1) {
                blendWidth = blendWindowWidth
            } else {
                blendWidth = 2
                for (j in 0..<i) {
                    blendWidth *= 2
                }
                blendWidth = min(blendWidth.toDouble(), blendWindowWidth.toDouble()).toInt()
            }

            /*int blendWidth = blendWindowWidth;
            for(int j=i;j<pyramid0.size()-1;j++) {
                blendWidth /= 2;
            }
            blendWidth = Math.max(blendWidth, 2);*/
            //blendWidth = 1; // test

            //float bestPathXWidth = width / (bestPathNX+1.0f); // width of each "bucket" for the best paths
            //blendWidth = Math.min(blendWidth, (int)(2.0f*bestPathXWidth+0.5f));
            /*if( MyDebug.LOG ) {
                Log.d(TAG, "i = " + i);
                Log.d(TAG, "    width: " + width);
                Log.d(TAG, "    blendWidth: " + blendWidth);
                Log.d(TAG, "    height: " + height);
                //Log.d(TAG, "    bestPathXWidth: " + bestPathXWidth);
                Log.d(TAG, "    bestPathYScale: " + bestPathYScale);
            }*/

            // compute interpolatedBestPath
            computeInterpolatedBestPath(
                interpolatedBestPath,
                width,
                height,
                blendWidth,
                bestPath,
                bestPathNX
            )
            interpolatedbestPathAllocation.copyFrom(interpolatedBestPath)

            script.invoke_setBlendWidth(blendWidth, width)

            //script.setBestPathXWidth(bestPathXWidth);
            //script.setBestPathYScale(best_path.length/(float)height);
            if (allocation0.type.element.dataType == Element.DataType.FLOAT_32) {
                script.forEach_merge_f(allocation0, allocation0)
            } else {
                script.forEach_merge(allocation0, allocation0)
            }
        }

        //bestPathAllocation.destroy();
        interpolatedbestPathAllocation.destroy()
    }

    /** Updates every entry in pyramid0 to be a blend from the left hand of pyramid0 to the
     * right hand of pyramid1.
     * Note that the width of the blend region will be half of the width of each image.
     * @param bestPath If non-null, the blend region will follow the supplied best path.
     */
    private fun mergePyramids(
        pyramid0: LaplacianPyramid,
        pyramid1: LaplacianPyramid,
        bestPath: IntArray?,
        bestPathNX: Int
    ) {
        var bestPath = bestPath
        var bestPathNX = bestPathNX
        if (MyDebug.LOG) Log.d(TAG, "mergePyramids")

        if (bestPath == null) {
            bestPath = IntArray(1)
            bestPathNX = 3
            bestPath[0] = 1
            //bestPath[0] = 2; // test
        }
        if (MyDebug.LOG) {
            for (i in bestPath.indices) Log.d(TAG, "best_path[" + i + "]: " + bestPath[i])
        }

        var maxHeight = 0
        for (i in pyramid0.heights.indices) {
            val height = pyramid0.heights[i]
            maxHeight = max(maxHeight.toDouble(), height.toDouble()).toInt()
        }
        run {
            val height = pyramid0.topLevel!!.height
            maxHeight = max(maxHeight.toDouble(), height.toDouble()).toInt()
        }

        val interpolatedBestPath = IntArray(maxHeight)

        for (i in pyramid0.diffs.indices) {
            val width = pyramid0.widths[i]
            val height = pyramid0.heights[i]
            if (pyramid1.widths[i] != width || pyramid1.heights[i] != height) {
                Log.e(TAG, "pyramids of different dimensions")
                throw RuntimeException()
            }

            // when using bestPath, we have a narrower region to blend across
            val blendWindowWidth = width / 2
            var blendWidth: Int
            run {
                blendWidth = 2
                for (j in 0..<i) {
                    blendWidth *= 2
                }
                blendWidth = min(blendWidth.toDouble(), blendWindowWidth.toDouble()).toInt()
            }

            // compute interpolatedBestPath
            computeInterpolatedBestPath(
                interpolatedBestPath,
                width,
                height,
                blendWidth,
                bestPath,
                bestPathNX
            )

            val function: MergefFunction = MergefFunction(
                pyramid0.diffs[i],
                pyramid1.diffs[i], blendWidth, width, interpolatedBestPath
            )
            JavaImageProcessing.applyFunction(function, null, null, 0, 0, width, height)
        }
        // now do topLevel
        run {
            val width = pyramid0.topLevel!!.width
            val height = pyramid0.topLevel!!.height
            if (pyramid1.topLevel!!.width != width || pyramid1.topLevel!!.height != height) {
                Log.e(TAG, "pyramids of different dimensions")
                throw RuntimeException()
            }

            // when using bestPath, we have a narrower region to blend across
            val blendWindowWidth = width / 2
            val blendWidth = blendWindowWidth

            // compute interpolatedBestPath
            computeInterpolatedBestPath(
                interpolatedBestPath,
                width,
                height,
                blendWidth,
                bestPath,
                bestPathNX
            )

            val function = MergeFunction(pyramid1.topLevel!!, blendWidth, interpolatedBestPath)
            JavaImageProcessing.applyFunction(
                function,
                pyramid0.topLevel,
                pyramid0.topLevel,
                0,
                0,
                width,
                height
            )
        }
    }

    /** For testing.
     */
    private fun saveBitmap(bitmap: Bitmap, name: String) {
        try {
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                    .toString() + "/" + name
            )
            val outputStream: OutputStream = FileOutputStream(file)
            if (name.lowercase(Locale.getDefault())
                    .endsWith(".png")
            ) bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            else bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.close()
            val mActivity = context as MainActivity
            mActivity.storageUtils.broadcastFile(file, true, false, true, false, null)
        } catch (e: IOException) {
            e.printStackTrace()
            throw RuntimeException()
        }
    }

    private fun saveAllocation(name: String, allocation: Allocation) {
        val bitmap: Bitmap
        val width = allocation.type.x
        val height = allocation.type.y
        Log.d(TAG, "count: " + allocation.type.count)
        Log.d(TAG, "byte size: " + allocation.type.element.bytesSize)
        if (allocation.type.element.dataType == Element.DataType.FLOAT_32) {
            val bytes = FloatArray(width * height * 4)
            allocation.copyTo(bytes)
            val pixels = IntArray(width * height)
            for (j in 0..<width * height) {
                val r = bytes[4 * j]
                val g = bytes[4 * j + 1]
                val b = bytes[4 * j + 2]
                // each value should be from -255 to +255, we compress to be in the range [0, 255]
                var ir = (255.0f * ((r / 510.0f) + 0.5f) + 0.5f).toInt()
                var ig = (255.0f * ((g / 510.0f) + 0.5f) + 0.5f).toInt()
                var ib = (255.0f * ((b / 510.0f) + 0.5f) + 0.5f).toInt()
                ir = max(min(ir.toDouble(), 255.0), 0.0).toInt()
                ig = max(min(ig.toDouble(), 255.0), 0.0).toInt()
                ib = max(min(ib.toDouble(), 255.0), 0.0).toInt()
                pixels[j] = Color.argb(255, ir, ig, ib)
            }
            bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } else if (allocation.type.element.dataType == Element.DataType.UNSIGNED_8) {
            val bytes = ByteArray(width * height)
            allocation.copyTo(bytes)
            val pixels = IntArray(width * height)
            for (j in 0..<width * height) {
                var b = bytes[j].toInt()
                if (b < 0) b += 255
                pixels[j] = Color.argb(255, b, b, b)
            }
            bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } else {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            allocation.copyTo(bitmap)
        }
        saveBitmap(bitmap, name)
        bitmap.recycle()
    }

    /** Returns a bitmap that blends between lhs and rhs, using Laplacian pyramid blending.
     * Note that the width of the blend region will be half of the width of the image. The blend
     * region will follow a path in order to minimise the transition between the images.
     */
    private fun blendPyramids(lhs: Bitmap, rhs: Bitmap): Bitmap? {
        var timeS: Long = 0
        if (MyDebug.LOG) timeS = System.currentTimeMillis()

        if (!HDRProcessor.useRenderscript) {
        } else {
            if (pyramidBlendingScript == null) {
                pyramidBlendingScript = ScriptC_pyramid_blending(rs)
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "### blendPyramids: time after creating ScriptC_pyramid_blending: " + (System.currentTimeMillis() - timeS)
            )
        }

        // debug
        /*if( MyDebug.LOG )
        {
            saveBitmap(lhs, "lhs.jpg");
            saveBitmap(rhs, "rhs.jpg");
        }*/
        // debug
        /*if( MyDebug.LOG )
        {
            List<Allocation> lhsPyramid = createGaussianPyramid(script, lhs, blendNLevels);
            List<Allocation> rhsPyramid = createGaussianPyramid(script, rhs, blendNLevels);
            savePyramid("lhs_gauss", lhsPyramid);
            savePyramid("rhs_gauss", rhsPyramid);
            for(Allocation allocation : lhsPyramid) {
                allocation.destroy();
            }
            for(Allocation allocation : rhsPyramid) {
                allocation.destroy();
            }
        }*/
        if (lhs.width != rhs.width || lhs.height != rhs.height) {
            Log.e(TAG, "lhs/rhs bitmaps of different dimensions")
            throw RuntimeException()
        }

        val blendDimension = blendDimension
        if (lhs.width % blendDimension != 0) {
            Log.e(TAG, "bitmap width " + lhs.width + " not a multiple of " + blendDimension)
            throw RuntimeException()
        } else if (lhs.height % blendDimension != 0) {
            Log.e(TAG, "bitmap height " + lhs.height + " not a multiple of " + blendDimension)
            throw RuntimeException()
        }

        //final boolean findBestPath = false;
        val findBestPath = true
        //final int bestPathNX = 3;
        val bestPathNX = 7
        val bestPathNY = 8
        //final int bestPathNY = 16;
        var bestPath: IntArray? = null
        if (findBestPath) {
            bestPath = IntArray(bestPathNY)

            //Bitmap bestPathLhs = lhs;
            //Bitmap bestPathRhs = rhs;
            val scaleFactor = 4
            val bestPathLhs = Bitmap.createScaledBitmap(
                lhs,
                lhs.width / scaleFactor,
                lhs.height / scaleFactor,
                true
            )
            val bestPathRhs = Bitmap.createScaledBitmap(
                rhs,
                rhs.width / scaleFactor,
                rhs.height / scaleFactor,
                true
            )

            // debug
            /*if( MyDebug.LOG )
            {
                saveBitmap(bestPathLhs, "best_path_lhs.jpg");
                saveBitmap(bestPathRhs, "best_path_rhs.jpg");
            }*/
            var computeErrorFunction: PyramidBlendingComputeErrorFunction? = null
            var lhsAllocation: Allocation? = null
            var rhsAllocation: Allocation? = null
            var errors: IntArray? = null
            var errorsAllocation: Allocation? = null
            var launchOptions: LaunchOptions? = null
            if (!HDRProcessor.useRenderscript) {
                computeErrorFunction = PyramidBlendingComputeErrorFunction(bestPathRhs)
            } else {
                lhsAllocation = Allocation.createFromBitmap(rs, bestPathLhs)
                rhsAllocation = Allocation.createFromBitmap(rs, bestPathRhs)

                errors = IntArray(1)
                errorsAllocation = Allocation.createSized(rs, Element.I32(rs), 1)
                pyramidBlendingScript?.bind_errors(errorsAllocation)

                launchOptions = LaunchOptions()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### blendPyramids: time after creating allocations for best path: " + (System.currentTimeMillis() - timeS)
                )

                pyramidBlendingScript?.set_bitmap(rhsAllocation)
            }

            val windowWidth = max(2.0, (bestPathLhs.width / 8).toDouble()).toInt()
            var startY = 0
            var stopY: Int
            for (y in 0..<bestPathNY) {
                bestPath[y] = -1
                var bestError = -1

                stopY = ((y + 1) * bestPathLhs.height) / bestPathNY
                if (!HDRProcessor.useRenderscript) {
                } else {
                    launchOptions!!.setY(startY, stopY)
                }

                //int startX = 0, stopX;
                for (x in 0..<bestPathNX) {
                    // windows for computing best path should be centred with the path centres we'll actually take
                    val alpha = (x.toFloat()) / (bestPathNX - 1.0f)
                    val frac = (1.0f - alpha) * 0.25f + alpha * 0.75f
                    val midX = (frac * bestPathLhs.width + 0.5f).toInt()
                    val startX = midX - windowWidth / 2
                    val stopX = midX + windowWidth / 2

                    //stopX = ((x+1) * best_path_lhs.getWidth()) / bestPathNX;
                    val thisError: Int
                    if (!HDRProcessor.useRenderscript) {
                        JavaImageProcessing.applyFunction(
                            computeErrorFunction!!,
                            bestPathLhs,
                            null,
                            startX,
                            startY,
                            stopX,
                            stopY
                        )
                        thisError = computeErrorFunction.getError()
                    } else {
                        launchOptions!!.setX(startX, stopX)
                        pyramidBlendingScript?.invoke_init_errors()
                        pyramidBlendingScript?.forEach_compute_error(lhsAllocation, launchOptions)
                        errorsAllocation!!.copyTo(errors)
                        thisError = errors!![0]
                    }

                    //startX = stopX; // set for next iteration
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "    best_path error[$x][$y]: $thisError"
                    )
                    if (bestPath[y] == -1 || thisError < bestError) {
                        bestPath[y] = x
                        bestError = thisError
                    }
                }

                startY = stopY // set for next iteration

                //bestPath[y] = 1; // test
                //bestPath[y] = y % bestPathNX; // test
                if (MyDebug.LOG) Log.d(TAG, "best_path [" + y + "]: " + bestPath[y])
            }

            if (!HDRProcessor.useRenderscript) {
            } else {
                lhsAllocation!!.destroy()
                rhsAllocation!!.destroy()
                errorsAllocation!!.destroy()
            }

            if (bestPathLhs != lhs) {
                bestPathLhs.recycle()
            }
            if (bestPathRhs != rhs) {
                bestPathRhs.recycle()
            }

            if (MyDebug.LOG) Log.d(
                TAG,
                "### blendPyramids: time after finding best path: " + (System.currentTimeMillis() - timeS)
            )
        }

        val mergedBitmap: Bitmap?
        if (!HDRProcessor.useRenderscript) {
            val lhsPyramid = createLaplacianPyramid(lhs, blendNLevels, "lhs")
            if (MyDebug.LOG) Log.d(
                TAG,
                "### blendPyramids: time after createLaplacianPyramid 1st call: " + (System.currentTimeMillis() - timeS)
            )
            val rhsPyramid = createLaplacianPyramid(rhs, blendNLevels, "rhs")
            if (MyDebug.LOG) Log.d(
                TAG,
                "### blendPyramids: time after createLaplacianPyramid 2nd call: " + (System.currentTimeMillis() - timeS)
            )

            /*{
                lhsPyramidRs = new ArrayList<>();
                for(int i=0;i<lhs_pyramid.diffs.size();i++) {
                    float [] differenceRgbf = lhs_pyramid.diffs.get(i);
                    int width = lhs_pyramid.widths.get(i);
                    int height = lhs_pyramid.heights.get(i);
                    Allocation allocation = Allocation.createTyped(rs, Type.createXY(rs, Element.F32_3(rs), width, height));
                    HDRProcessor.RGBfToAllocation(differenceRgbf, allocation, width, height);
                    lhs_pyramid_rs.add(allocation);
                }
                Allocation allocation = Allocation.createFromBitmap(rs, lhs_pyramid.topLevel);
                lhs_pyramid_rs.add(allocation);
            }
            {
                rhsPyramidRs = new ArrayList<>();
                for(int i=0;i<rhs_pyramid.diffs.size();i++) {
                    float [] differenceRgbf = rhs_pyramid.diffs.get(i);
                    int width = rhs_pyramid.widths.get(i);
                    int height = rhs_pyramid.heights.get(i);
                    Allocation allocation = Allocation.createTyped(rs, Type.createXY(rs, Element.F32_3(rs), width, height));
                    HDRProcessor.RGBfToAllocation(differenceRgbf, allocation, width, height);
                    rhs_pyramid_rs.add(allocation);
                }
                Allocation allocation = Allocation.createFromBitmap(rs, rhs_pyramid.topLevel);
                rhs_pyramid_rs.add(allocation);
            }*/
            mergePyramids(lhsPyramid, rhsPyramid, bestPath, bestPathNX)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### blendPyramids: time after mergePyramids: " + (System.currentTimeMillis() - timeS)
            )

            /*{
                lhsPyramidRs = new ArrayList<>();
                for(int i=0;i<lhs_pyramid.diffs.size();i++) {
                    float [] differenceRgbf = lhs_pyramid.diffs.get(i);
                    int width = lhs_pyramid.widths.get(i);
                    int height = lhs_pyramid.heights.get(i);
                    Allocation allocation = Allocation.createTyped(rs, Type.createXY(rs, Element.F32_3(rs), width, height));
                    HDRProcessor.RGBfToAllocation(differenceRgbf, allocation, width, height);
                    lhs_pyramid_rs.add(allocation);
                }
                Allocation allocation = Allocation.createFromBitmap(rs, lhs_pyramid.topLevel);
                lhs_pyramid_rs.add(allocation);
            }*/
            mergedBitmap = collapseLaplacianPyramid(lhsPyramid)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### blendPyramids: time after collapseLaplacianPyramid: " + (System.currentTimeMillis() - timeS)
            )

            lhsPyramid.topLevel!!.recycle()
            rhsPyramid.topLevel!!.recycle()
        } else {
            val lhsPyramidRs =
                createLaplacianPyramidRS(pyramidBlendingScript!!, lhs, blendNLevels, "lhs")
            if (MyDebug.LOG) Log.d(
                TAG,
                "### blendPyramids: time after createLaplacianPyramid 1st call: " + (System.currentTimeMillis() - timeS)
            )
            val rhsPyramidRs =
                createLaplacianPyramidRS(pyramidBlendingScript!!, rhs, blendNLevels, "rhs")
            if (MyDebug.LOG) Log.d(
                TAG,
                "### blendPyramids: time after createLaplacianPyramid 2nd call: " + (System.currentTimeMillis() - timeS)
            )

            // debug
            /*if( MyDebug.LOG )
            {
                savePyramid("lhs_laplacian", lhsPyramid);
                savePyramid("rhs_laplacian", rhsPyramid);
            }*/

            // debug
            /*if( MyDebug.LOG )
            {
                Bitmap lhsCollapsed = collapseLaplacianPyramid(script, lhsPyramid);
                saveBitmap(lhsCollapsed, "lhs_collapsed.jpg");
                Bitmap rhsCollapsed = collapseLaplacianPyramid(script, rhsPyramid);
                saveBitmap(rhsCollapsed, "rhs_collapsed.jpg");
                lhs_collapsed.recycle();
                rhs_collapsed.recycle();
            }*/
            mergePyramidsRS(
                pyramidBlendingScript!!,
                lhsPyramidRs,
                rhsPyramidRs,
                bestPath,
                bestPathNX
            )
            if (MyDebug.LOG) Log.d(
                TAG,
                "### blendPyramids: time after mergePyramids: " + (System.currentTimeMillis() - timeS)
            )

            mergedBitmap = collapseLaplacianPyramidRS(pyramidBlendingScript!!, lhsPyramidRs)
            if (MyDebug.LOG) Log.d(
                TAG,
                "### blendPyramids: time after collapseLaplacianPyramid: " + (System.currentTimeMillis() - timeS)
            )

            for (allocation in lhsPyramidRs) {
                allocation.destroy()
            }
            for (allocation in rhsPyramidRs) {
                allocation.destroy()
            }
        }

        // debug
        /*if( MyDebug.LOG )
        {
            savePyramid("merged_laplacian", lhsPyramid);
            saveBitmap(mergedBitmap, "merged_bitmap.jpg");
        }*/
        if (MyDebug.LOG) Log.d(
            TAG,
            "### blendPyramids: time taken: " + (System.currentTimeMillis() - timeS)
        )
        return mergedBitmap
    }

    private class FeatureMatch(val index0: Int, val index1: Int) : Comparable<FeatureMatch> {
        var distance: Float = 0f // from 0 to 1, higher means poorer match

        override fun compareTo(other: FeatureMatch): Int {
            //return (int)(this.distance - that.distance);
            /*if( this.distance > that.distance )
                    return 1;
                else if( this.distance < that.distance )
                    return -1;
                else
                    return 0;*/
            return this.distance.compareTo(other.distance)
        }

        override fun equals(that: Any?): Boolean {
            return (that is FeatureMatch) && compareTo(that) == 0
        }
    }

    private class ComputeDistancesBetweenMatchesThread(
        private val matches: List<FeatureMatch>,
        private val stIndx: Int,
        private val ndIndx: Int,
        private val featureDescriptorRadius: Int,
        private val bitmaps: List<Bitmap>,
        private val pixels0: IntArray,
        private val pixels1: IntArray
    ) :
        Thread("ComputeDistancesBetweenMatchesThread") {
        override fun run() {
            computeDistancesBetweenMatches(
                matches,
                stIndx,
                ndIndx,
                featureDescriptorRadius,
                bitmaps,
                pixels0,
                pixels1
            )
        }
    }

    internal data class AutoAlignmentByFeatureResult(
        val offsetX: Int,
        val offsetY: Int,
        val rotation: Float,
        val yScale: Float
    )

    @Throws(PanoramaProcessorException::class)
    private fun autoAlignmentByFeature(
        width: Int,
        height: Int,
        bitmaps: List<Bitmap>,
        debugIndex: Int
    ): AutoAlignmentByFeatureResult {
        if (MyDebug.LOG) {
            Log.d(TAG, "autoAlignmentByFeature")
            Log.d(TAG, "width: $width")
            Log.d(TAG, "height: $height")
        }

        val timeS = if (MyDebug.LOG) System.currentTimeMillis() else 0

        if (bitmaps.size != 2) {
            Log.e(TAG, "must have 2 bitmaps")
            throw PanoramaProcessorException(PanoramaProcessorException.INVALID_N_IMAGES)
        }

        var allocations: Array<Allocation?>? = null
        if (HDRProcessor.useRenderscript) {
            initRenderscript()
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "### autoAlignmentByFeature: time after initRenderscript: " + (System.currentTimeMillis() - timeS)
                )
            }
            allocations = Array(bitmaps.size) { i ->
                Allocation.createFromBitmap(rs, bitmaps[i])
            }
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "### autoAlignmentByFeature: time after creating allocations: " + (System.currentTimeMillis() - timeS)
                )
            }
            if (featureDetectorScript == null) {
                featureDetectorScript = ScriptC_feature_detector(rs)
            }
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "### autoAlignmentByFeature: time after create featureDetectorScript: " + (System.currentTimeMillis() - timeS)
                )
            }
        }


        //final int featureDescriptorRadius = 2; // radius of square used to compare features
        val featureDescriptorRadius = 3 // radius of square used to compare features
        //final int featureDescriptorRadius = 5; // radius of square used to compare features
        val pointsArrays = arrayOfNulls<Array<Point>>(2)

        for (i in bitmaps.indices) {
            if (MyDebug.LOG) Log.d(TAG, "detect features for image: $i")

            var strengthRgbf: FloatArray = floatArrayOf()
            var strengthAllocation: Allocation? = null
            var localMaxFeaturesAllocation: Allocation? = null

            if (!HDRProcessor.useRenderscript) {
                if (MyDebug.LOG) Log.d(TAG, "convert to greyscale")

                val gsBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                JavaImageProcessing.applyFunction(
                    JavaImageFunctionsPanorama.ConvertToGreyscaleFunction(),
                    bitmaps[i],
                    gsBitmap,
                    0,
                    0,
                    width,
                    height
                )

                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "### autoAlignmentByFeature: time after ConvertToGreyscaleFunction: " + (System.currentTimeMillis() - timeS)
                    )
                }

                if (MyDebug.LOG) Log.d(TAG, "compute derivatives")

                val ixBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                val iyBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                JavaImageProcessing.applyFunction(
                    JavaImageFunctionsPanorama.ComputeDerivativesFunction(
                        ixBitmap,
                        iyBitmap,
                        gsBitmap
                    ), null, null, 0, 0, width, height
                )
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "### autoAlignmentByFeature: time after ComputeDerivativesFunction: " + (System.currentTimeMillis() - timeS)
                    )
                }
                gsBitmap.recycle()

                if (MyDebug.LOG) Log.d(TAG, "call corner detector script for image: " + i)
                strengthRgbf = FloatArray(width * height)
                JavaImageProcessing.applyFunction(
                    JavaImageFunctionsPanorama.CornerDetectorFunction(
                        strengthRgbf,
                        ixBitmap,
                        iyBitmap
                    ), null, null, 0, 0, width, height
                )
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### autoAlignmentByFeature: time after CornerDetectorFunction: " + (System.currentTimeMillis() - timeS)
                )

                ixBitmap.recycle()
                iyBitmap.recycle()
            } else {
                if (MyDebug.LOG) Log.d(TAG, "convert to greyscale")
                val gsAllocation =
                    Allocation.createTyped(rs, Type.createXY(rs, Element.U8(rs), width, height))
                featureDetectorScript?.forEach_create_greyscale(allocations!![i], gsAllocation)

                if (MyDebug.LOG) Log.d(TAG, "compute derivatives")
                val ixAllocation =
                    Allocation.createTyped(rs, Type.createXY(rs, Element.U8(rs), width, height))
                val iyAllocation =
                    Allocation.createTyped(rs, Type.createXY(rs, Element.U8(rs), width, height))
                featureDetectorScript?.apply {
                    set_bitmap(gsAllocation)
                    set_bitmap_Ix(ixAllocation)
                    set_bitmap_Iy(iyAllocation)
                    forEach_compute_derivatives(gsAllocation)
                }

                if (MyDebug.LOG) Log.d(TAG, "call corner detector script for image: " + i)
                strengthAllocation =
                    Allocation.createTyped(rs, Type.createXY(rs, Element.F32(rs), width, height))
                featureDetectorScript?.apply {
                    set_bitmap(gsAllocation)
                    set_bitmap_Ix(ixAllocation)
                    set_bitmap_Iy(iyAllocation)
                    forEach_corner_detector(gsAllocation, strengthAllocation)
                }

                ixAllocation.destroy()
                iyAllocation.destroy()
                localMaxFeaturesAllocation = gsAllocation // Reuse U8 allocation
            }

            //Allocation gsAllocation = Allocation.createFromBitmap(rs, gsBitmaps[i]);

            /*if( MyDebug.LOG ) {
                // debugging
                byte [] bytesX = new byte[width*height];
                byte [] bytesY = new byte[width*height];
                ix_allocation.copyTo(bytesX);
                iy_allocation.copyTo(bytesY);
                int [] pixelsX = new int[width*height];
                int [] pixelsY = new int[width*height];
                for(int j=0;j<width*height;j++) {
                    int b = bytesX[j];
                    if( b < 0 )
                        b += 255;
                    pixelsX[j] = Color.argb(255, b, b, b);
                    b = bytesY[j];
                    if( b < 0 )
                        b += 255;
                    pixelsY[j] = Color.argb(255, b, b, b);
                }
                Bitmap bitmapX = Bitmap.createBitmap(pixelsX, width, height, Bitmap.Config.ARGB_8888);
                Bitmap bitmapY = Bitmap.createBitmap(pixelsY, width, height, Bitmap.Config.ARGB_8888);
                File fileX = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/ixBitmap" + debugIndex + "_" + i + ".png");
                File fileY = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/iyBitmap" + debugIndex + "_" + i + ".png");
                try {
                    MainActivity mActivity = (MainActivity) context;

                    OutputStream outputStream = new FileOutputStream(fileX);
                    bitmap_x.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                    outputStream.close();
                    mActivity.storageUtils.broadcastFile(fileX, true, false, true);

                    outputStream = new FileOutputStream(fileY);
                    bitmap_y.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                    outputStream.close();
                    mActivity.storageUtils.broadcastFile(fileY, true, false, true);
                }
                catch(IOException e) {
                    e.printStackTrace();
                }
                bitmap_x.recycle();
                bitmap_y.recycle();
            }*/

            /*featureDetectorScript.setCornerThreshold(100000000.0f);
            featureDetectorScript.set_bitmap(strengthAllocation);
            featureDetectorScript.forEach_local_maximum(strengthAllocation, localMaxFeaturesAllocation);
            // collect points
            byte [] bytes = new byte[width*height];
            local_max_features_allocation.copyTo(bytes);
            // find points
            List<Point> points = new ArrayList<>();
            for(int y=featureDescriptorRadius;y<height-featureDescriptorRadius;y++) {
                for(int x=featureDescriptorRadius;x<width-featureDescriptorRadius;x++) {
                    int j = y*width + x;
                    // remember, bytes are signed!
                    if( bytes[j] != 0 ) {
                        Point point = new Point(x, y);
                        points.add(point);
                    }
                }
            }
            pointsArrays[i] = points.toArray(new Point[0]);
            */
            if (MyDebug.LOG) Log.d(TAG, "find local maxima for image: $i")
            if (HDRProcessor.useRenderscript) {
                featureDetectorScript!!.set_bitmap(strengthAllocation)
            }
            // --- Local Maxima Search ---
            val nYChunks = 2
            val totalMaxCorners = 200
            val maxCorners = totalMaxCorners / nYChunks
            val minCorners = maxCorners / 2
            val bytes = ByteArray(width * height)
            val allPoints = ArrayList<Point>()

            for (cy in 0 until nYChunks) {
                if (MyDebug.LOG) Log.d(TAG, ">>> find corners, chunk $cy / $nYChunks")
                var threshold = 5000000.0f
                val minThreshold = 1250000.0f
                var lowThreshold = 0.0f
                var highThreshold = -1.0f
                val startY = (cy * height) / nYChunks
                val stopY = ((cy + 1) * height) / nYChunks
                if (MyDebug.LOG) {
                    Log.d(TAG, "    start_y: $startY")
                    Log.d(TAG, "    stop_y: $stopY")
                }

                for (count in 0 until 10) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "### attempt $count try threshold: $threshold [ $lowThreshold : $highThreshold ]"
                    )
                    if (!HDRProcessor.useRenderscript) {
                        val function = JavaImageFunctionsPanorama.LocalMaximumFunction(
                            strengthRgbf,
                            bytes,
                            width,
                            height,
                            threshold
                        )
                        JavaImageProcessing.applyFunction(function, null, null, 0, 0, width, height)
                    } else {
                        featureDetectorScript?.set_corner_threshold(threshold)
                        val launchOptions = LaunchOptions().setX(0, width).setY(startY, stopY)
                        featureDetectorScript?.forEach_local_maximum(
                            strengthAllocation,
                            localMaxFeaturesAllocation,
                            launchOptions
                        )
                        localMaxFeaturesAllocation?.copyTo(bytes)
                    }

                    // find points
                    val chunkPoints = ArrayList<Point>()
                    for (y in max(startY, featureDescriptorRadius) until min(
                        stopY,
                        height - featureDescriptorRadius
                    )) {
                        for (x in featureDescriptorRadius until width - featureDescriptorRadius) {
                            val j = y * width + x
                            // remember, bytes are signed!
                            if (bytes[j] != 0.toByte()) {
                                chunkPoints.add(Point(x, y))
                            }
                        }
                    }
                    if (MyDebug.LOG) Log.d(TAG, "    " + chunkPoints.size + " points")
                    if (chunkPoints.size in minCorners..maxCorners || threshold <= minThreshold || count == 9) {
                        if (chunkPoints.size > maxCorners) {
                            allPoints.addAll(chunkPoints.subList(0, maxCorners))
                        } else {
                            allPoints.addAll(chunkPoints)
                        }
                        break
                    } else if (chunkPoints.size < minCorners) {
                        highThreshold = threshold
                        threshold = 0.5f * (lowThreshold + threshold)
                    } else {
                        lowThreshold = threshold
                        threshold =
                            if (highThreshold < 0.0f) threshold * 10.0f else 0.5f * (threshold + highThreshold)
                    }
                }
            }
            pointsArrays[i] = allPoints.toTypedArray()
            if (MyDebug.LOG) Log.d(
                TAG,
                "### image: " + i + " has " + pointsArrays[i]?.size + " points"
            )

            strengthAllocation?.destroy()
            localMaxFeaturesAllocation?.destroy()
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "### autoAlignmentByFeature: time after feature detection: " + (System.currentTimeMillis() - timeS)
            )
        }

        // Validation
        // if we have too few good corners, risk of getting a poor match
        val minRequiredCorners = 10
        if ((pointsArrays[0]?.size ?: 0) < minRequiredCorners || (pointsArrays[1]?.size
                ?: 0) < minRequiredCorners
        ) {
            if (MyDebug.LOG) Log.d(TAG, "too few points!")
            allocations?.forEach { it?.destroy() }
            return AutoAlignmentByFeatureResult(0, 0, 0.0f, 1.0f)
        }

        // --- Candidate Matching ---
        val maxMatchDistX = width
        val maxMatchDistY = height / 16
        val maxMatchDist2 = maxMatchDistX * maxMatchDistX + maxMatchDistY * maxMatchDistY
        val matches = ArrayList<FeatureMatch>()
        if (MyDebug.LOG) {
            Log.d(TAG, "max_match_dist_x: $maxMatchDistX")
            Log.d(TAG, "max_match_dist_y: $maxMatchDistY")
            Log.d(TAG, "max_match_dist2: $maxMatchDist2")
        }

        val p0 = pointsArrays[0]!!
        val p1 = pointsArrays[1]!!

        for (i in p0.indices) {
            for (j in p1.indices) {
                val dx = p1[j].x - p0[i].x
                val dy = p1[j].y - p0[i].y
                if (dx * dx + dy * dy < maxMatchDist2) {
                    matches.add(FeatureMatch(i, j))
                }
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "### possible matches: " + matches.size)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### autoAlignmentByFeature: time after finding possible matches: " + (System.currentTimeMillis() - timeS)
        )

        // --- Compute Distances (Greyscale block) ---
        val wid = 2 * featureDescriptorRadius + 1
        val wid2 = wid * wid
        val pixels0 = IntArray(p0.size * wid2)
        val pixels1 = IntArray(p1.size * wid2)

        for (i in p0.indices) {
            bitmaps[0].getPixels(
                pixels0,
                i * wid2,
                wid,
                p0[i].x - featureDescriptorRadius,
                p0[i].y - featureDescriptorRadius,
                wid,
                wid
            )
        }
        for (i in p1.indices) {
            bitmaps[1].getPixels(
                pixels1,
                i * wid2,
                wid,
                p1[i].x - featureDescriptorRadius,
                p1[i].y - featureDescriptorRadius,
                wid,
                wid
            )
        }

        // Manual Greyscale conversion
        fun convertBlockToGrey(pixels: IntArray) {
            for (i in pixels.indices) {
                val p = pixels[i]
                pixels[i] =
                    (0.3 * Color.red(p) + 0.59 * Color.green(p) + 0.11 * Color.blue(p)).toInt()
            }
        }
        convertBlockToGrey(pixels0)
        convertBlockToGrey(pixels1)

        // Multi-threaded distance computation
        val useSmp = true
        if (useSmp) {
            val nThreads = min(matches.size, 2)
            val threads = List(nThreads) { i ->
                val start = (i * matches.size) / nThreads
                val end = ((i + 1) * matches.size) / nThreads
                ComputeDistancesBetweenMatchesThread(
                    matches,
                    start,
                    end,
                    featureDescriptorRadius,
                    bitmaps,
                    pixels0,
                    pixels1
                )
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        } else {
            val stIndx = 0
            val ndIndx = matches.size

            /*final int wid = 2*featureDescriptorRadius+1;
                final int wid2 = wid*wid;
                int [] pixels0 = new int[wid2];
                int [] pixels1 = new int[wid2];*/
            computeDistancesBetweenMatches(
                matches,
                stIndx,
                ndIndx,
                featureDescriptorRadius,
                bitmaps,
                pixels0,
                pixels1
            )
        }

        if (MyDebug.LOG) Log.d(
            TAG,
            "### autoAlignmentByFeature: time after computing match distances: " + (System.currentTimeMillis() - timeS)
        )
        // sort
        Collections.sort(matches)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### autoAlignmentByFeature: time after sorting matches: " + (System.currentTimeMillis() - timeS)
        )
        if (MyDebug.LOG) {
            val bestMatch = matches.get(0)
            val worstMatch = matches.get(matches.size - 1)
            Log.d(
                TAG,
                "best match between " + bestMatch.index0 + " and " + bestMatch.index1 + " distance: " + bestMatch.distance
            )
            Log.d(
                TAG,
                "worst match between " + worstMatch.index0 + " and " + worstMatch.index1 + " distance: " + worstMatch.distance
            )
        }

        // --- Lowe's Test & Initial Selection ---
        // choose matches
        val rejected0 = BooleanArray(p0.size)
        val hasMatched0 = BooleanArray(p0.size)
        val hasMatched1 = BooleanArray(p1.size)
        var actualMatches = ArrayList<FeatureMatch>()

        for (i in matches.indices) {
            val match = matches[i]
            if (hasMatched0[match.index0] || hasMatched1[match.index1]) continue
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "    match between " + match.index0 + " and " + match.index1 + " distance: " + match.distance
                )
            }

            // Lowe's test
            var found = false
            var reject = false

            var j = i + 1
            while (j < matches.size && !found) {
                val match2 = matches[j]
                if (match.index0 == match2.index0) {
                    found = true
                    val ratio = match.distance / match2.distance
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "        next best match for index0 " + match.index0 + " is with " + match2.index1 + " distance: " + match2.distance + " , ratio: " + ratio
                        )
                    }
                    // Need a threshold of 0.8 or less to help testPanorama15 images _5 to _6, otherwise we get too many incorrect
                    // matches in the grass region
                    if (ratio + 1.0e-5 > 0.8f) {
                        if (MyDebug.LOG) {
                            Log.d(TAG, "        reject due to Lowe's test, ratio: " + ratio)
                        }
                        reject = true
                    }
                }
                j++
            }
            if (reject) {
                hasMatched0[match.index0] = true
                rejected0[match.index0] = true
                continue
            }

            actualMatches.add(match)
            hasMatched0[match.index0] = true
            hasMatched1[match.index1] = true
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "### autoAlignmentByFeature: time after initial matching: " + (System.currentTimeMillis() - timeS)
            )
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "### found: " + actualMatches.size + " matches")
        }
        Log.d(
            TAG,
            "### autoAlignmentByFeature: time after finding possible matches: " + (System.currentTimeMillis() - timeS)
        )

        // Top 40% Selection
        var nMatchesCount = (actualMatches.size * 0.4f).toInt() + 1
        nMatchesCount = max(5, nMatchesCount)
        if (nMatchesCount < actualMatches.size) {
            actualMatches = ArrayList(actualMatches.subList(0, nMatchesCount))
        }

        if (actualMatches.isEmpty()) {
            allocations?.forEach { it?.destroy() }
            return AutoAlignmentByFeatureResult(0, 0, 0.0f, 1.0f)
        }

        // --- RANSAC ---
        val estimateRotation = true
        val estimateYScale = false
        var useRotation = false
        var useYScale = false
        val maxInlierDist = max(5.01f, max(width, height) / 100.0f)
        val maxInlierDist2 = maxInlierDist * maxInlierDist
        val minRotationDist2 = max(5.0f, max(width, height) / 4.0f).let { it * it }

        var bestInliers = ArrayList<FeatureMatch>()

        for (i in actualMatches.indices) {
            val match = actualMatches[i]
            val candOffsetX = p1[match.index1].x - p0[match.index0].x
            val candOffsetY = p1[match.index1].y - p0[match.index0].y

            val inliers = actualMatches.filter { m ->
                val dx = (p0[m.index0].x + candOffsetX) - p1[m.index1].x
                val dy = (p0[m.index0].y + candOffsetY) - p1[m.index1].y
                dx * dx + dy * dy <= maxInlierDist2
            }

            if (inliers.size > bestInliers.size) {
                bestInliers = ArrayList(inliers)
                useRotation = false
                useYScale = false
            }

            if (estimateRotation) {
                for (j in 0 until i) {
                    val match2 = actualMatches[j]
                    val dx0 = (p0[match.index0].x - p0[match2.index0].x).toFloat()
                    val dy0 = (p0[match.index0].y - p0[match2.index0].y).toFloat()
                    val dx1 = (p1[match.index1].x - p1[match2.index1].x).toFloat()
                    val dy1 = (p1[match.index1].y - p1[match2.index1].y).toFloat()

                    if (dx0 * dx0 + dy0 * dy0 < minRotationDist2) continue

                    val angle = (atan2(dy1, dx1) - atan2(dy0, dx0)).let {
                        if (it < -Math.PI) it + 2 * Math.PI else if (it > Math.PI) it - 2 * Math.PI else it
                    }.toFloat()

                    if (abs(angle) > 30.0f * Math.PI / 180.0f) continue

                    val c0x = (p0[match.index0].x + p0[match2.index0].x) / 2
                    val c0y = (p0[match.index0].y + p0[match2.index0].y) / 2
                    val c1x = (p1[match.index1].x + p1[match2.index1].x) / 2
                    val c1y = (p1[match.index1].y + p1[match2.index1].y) / 2

                    val rotInliers = actualMatches.filter { m ->
                        val lx = p0[m.index0].x - c0x
                        val ly = p0[m.index0].y - c0y
                        val tx = (lx * cos(angle) - ly * sin(angle)).toInt() + c1x
                        val ty = (lx * sin(angle) + ly * cos(angle)).toInt() + c1y
                        val ex = tx - p1[m.index1].x
                        val ey = ty - p1[m.index1].y
                        ex * ex + ey * ey <= maxInlierDist2
                    }

                    if (rotInliers.size > bestInliers.size && rotInliers.size >= 5) {
                        bestInliers = ArrayList(rotInliers)
                        useRotation = true
                    }
                }
            }
        }
        actualMatches = bestInliers

        // --- Final Transform Calculation ---
        val c0 = Point(actualMatches.sumOf { p0[it.index0].x } / actualMatches.size,
            actualMatches.sumOf { p0[it.index0].y } / actualMatches.size)
        val c1 = Point(actualMatches.sumOf { p1[it.index1].x } / actualMatches.size,
            actualMatches.sumOf { p1[it.index1].y } / actualMatches.size)

        var offsetX = c1.x - c0.x
        var offsetY = c1.y - c0.y
        var rotation = 0.0f
        val yScale = 1.0f

        if (estimateRotation && useRotation) {
            val angles = actualMatches.mapNotNull { m ->
                val dx0 = (p0[m.index0].x - c0.x).toDouble()
                val dy0 = (p0[m.index0].y - c0.y).toDouble()
                val dx1 = (p1[m.index1].x - c1.x).toDouble()
                val dy1 = (p1[m.index1].y - c1.y).toDouble()
                if (dx0 * dx0 + dy0 * dy0 < 1e-5 || dx1 * dx1 + dy1 * dy1 < 1e-5) null
                else {
                    val a = atan2(dy1, dx1) - atan2(dy0, dx0)
                    if (a < -Math.PI) a + 2 * Math.PI else if (a > Math.PI) a - 2 * Math.PI else a
                }
            }
            if (angles.isNotEmpty()) rotation = angles.average().toFloat()

            val rotC0x = (c0.x * cos(rotation) - c0.y * sin(rotation))
            val rotC0y = (c0.x * sin(rotation) + c0.y * cos(rotation))
            offsetX += (c0.x - rotC0x).toInt()
            offsetY += (c0.y - rotC0y).toInt()
        }

        allocations?.forEach { it?.destroy() }
        return AutoAlignmentByFeatureResult(offsetX, offsetY, rotation, yScale)
    }

    private fun blendPanoramaAlpha(lhs: Bitmap, rhs: Bitmap): Bitmap {
        val width = lhs.width
        val height = lhs.height
        if (width != rhs.width) {
            Log.e(TAG, "bitmaps have different widths")
            throw RuntimeException()
        } else if (height != rhs.height) {
            Log.e(TAG, "bitmaps have different heights")
            throw RuntimeException()
        }
        val p = Paint()
        val rect = Rect()
        val blendedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val blendedCanvas = Canvas(blendedBitmap)
        p.setXfermode(PorterDuffXfermode(PorterDuff.Mode.ADD))
        for (x in 0..<width) {
            rect[x, 0, x + 1] = height

            // left hand blend
            // if x=0: frac=1
            // if x=width-1: frac=0
            var frac = (width - 1.0f - x) / (width - 1.0f)
            p.alpha = (255.0f * frac).toInt()
            blendedCanvas.drawBitmap(lhs, rect, rect, p)

            // right hand blend
            // if x=0: frac=0
            // if x=width-1: frac=1
            frac = (x.toFloat()) / (width - 1.0f)
            p.alpha = (255.0f * frac).toInt()
            blendedCanvas.drawBitmap(rhs, rect, rect, p)
        }
        return blendedBitmap
    }

    private fun createProjectedBitmap(
        srcRectWorkspace: Rect,
        dstRectWorkspace: Rect,
        bitmap: Bitmap,
        p: Paint,
        bitmapWidth: Int,
        bitmapHeight: Int,
        cameraAngle: Double,
        centreShiftX: Int
    ): Bitmap {
        val projectedBitmap =
            Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        run {
            // project
            val projectedCanvas = Canvas(projectedBitmap)
            var prevX = 0
            var prevY0 = -1
            var prevY1 = -1
            for (x in 0..<bitmapWidth) {
                val dx = (x - (bitmapWidth / 2 + centreShiftX)).toFloat()
                // rectangular projection:
                //float newHeight = bitmapHeight * (float)(h / (h * Math.cos(alpha) - dx * Math.sin(alpha)));
                // cylindrical projection:
                val theta = (dx * cameraAngle).toFloat() / bitmapWidth.toFloat()
                val newHeight = bitmapHeight * cos(theta.toDouble()).toFloat()

                //float fixedYFrac = 0.5f;
                //int dstY0 = (int)(fixedYFrac*(bitmapHeight - newHeight) + 0.5f);
                //int dstY1 = (int)(fixedYFrac*bitmapHeight + (1.0f - fixedYFrac)*newHeight + 0.5f);
                val dstY0 = ((bitmapHeight - newHeight) / 2.0f + 0.5f).toInt()
                val dstY1 = ((bitmapHeight + newHeight) / 2.0f + 0.5f).toInt()

                // yTol: boost performance at the expense of accuracy (but only by up to 1 pixel)
                //final int yTol = 0;
                val yTol = 1
                if (x == 0) {
                    prevY0 = dstY0
                    prevY1 = dstY1
                } else if (abs((dstY0 - prevY0).toDouble()) > yTol || abs((dstY1 - prevY1).toDouble()) > yTol) {
                    srcRectWorkspace[prevX, 0, x] = bitmapHeight
                    dstRectWorkspace[prevX, dstY0, x] = dstY1
                    projectedCanvas.drawBitmap(bitmap, srcRectWorkspace, dstRectWorkspace, p)
                    prevX = x
                    prevY0 = dstY0
                    prevY1 = dstY1
                }

                if (x == bitmapWidth - 1) {
                    // draw last
                    srcRectWorkspace[prevX, 0, x + 1] = bitmapHeight
                    dstRectWorkspace[prevX, dstY0, x + 1] = dstY1
                    projectedCanvas.drawBitmap(bitmap, srcRectWorkspace, dstRectWorkspace, p)
                }

                /*src_rect.set(x, 0, x+1, bitmapHeight);
                dst_rect.set(x, dstY0, x+1, dstY1);

                projected_canvas.drawBitmap(bitmap, srcRect, dstRect, p);*/
            }
        }
        return projectedBitmap
    }

    private fun renderPanoramaImage(
        i: Int, nBitmaps: Int, srcRectWorkspace: Rect, dstRectWorkspace: Rect,
        bitmap: Bitmap, p: Paint, bitmapWidth: Int, bitmapHeight: Int,
        blendHwidth: Int, sliceWidth: Int, offsetX: Int,
        panorama: Bitmap, canvas: Canvas, cropX0: Int, cropY0: Int,
        alignX: Int, alignY: Int, dstOffsetX: Int, shiftStopX: Int, centreShiftX: Int,
        cameraAngle: Double, timeS: Long
    ) {
        //float alpha = (float)((cameraAngle * i)/panoramaPicsPerScreen);
        if (MyDebug.LOG) {
            //Log.d(TAG, "    alpha: " + alpha + " ( " + Math.toDegrees(alpha) + " degrees )");
            Log.d(TAG, "    align_x: $alignX")
            Log.d(TAG, "    align_y: $alignY")
            Log.d(TAG, "    dst_offset_x: $dstOffsetX")
            Log.d(TAG, "    shift_stop_x: $shiftStopX")
        }

        if (MyDebug.LOG) Log.d(
            TAG,
            "### time before projection for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
        )
        val projectedBitmap = createProjectedBitmap(
            srcRectWorkspace,
            dstRectWorkspace,
            bitmap,
            p,
            bitmapWidth,
            bitmapHeight,
            cameraAngle,
            centreShiftX
        )
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after projection for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
        )

        if (i > 0 && blendHwidth > 0) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time before blending for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
            )
            // first blend right hand side of previous image with left hand side of new image
            val blendDimension = blendDimension

            // ensure we blend images that are a multiple of blendDimension
            val blendWidth = nextMultiple(2 * blendHwidth, blendDimension)
            val blendHeight = nextMultiple(bitmapHeight, blendDimension)
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "blend_dimension: $blendDimension"
                )
                Log.d(TAG, "blend_hwidth: $blendHwidth")
                Log.d(TAG, "bitmap_height: $bitmapHeight")
                Log.d(TAG, "blend_width: $blendWidth")
                Log.d(TAG, "blend_height: $blendHeight")
            }

            // Note that we don't handle the cropX0 and cropY0 in the same way: for the x crop, it's
            // important to shift the x coordinate of the blend window to match what we'll blend if not
            // cropping. Otherwise we have problems in testPanorama6 and especially testPanorama28
            // (note, due to instability at the time of writing, testPanorama28 issue was reproduced on
            // Nokia 8, but not Samsung Galaxy S10e).
            // For the y crop, there isn't any advantage to shifting.

            //Bitmap lhs = Bitmap.createBitmap(panorama, offsetX + dstOffsetX - blendHwidth, 0, 2*blendHwidth, bitmapHeight);
            val lhs = Bitmap.createBitmap(blendWidth, blendHeight, Bitmap.Config.ARGB_8888)
            run {
                val lhsCanvas = Canvas(lhs)
                srcRectWorkspace[offsetX + dstOffsetX - blendHwidth, 0, offsetX + dstOffsetX + blendHwidth] =
                    bitmapHeight
                // n.b., shouldn't shift by alignX, alignY
                srcRectWorkspace.offset(-cropX0, 0)
                dstRectWorkspace[0, 0, blendWidth] = blendHeight
                lhsCanvas.drawBitmap(panorama, srcRectWorkspace, dstRectWorkspace, p)
            }

            //Bitmap rhs = Bitmap.createBitmap(projectedBitmap, offsetX - blendHwidth, 0, 2*blendHwidth, bitmapHeight);
            val rhs = Bitmap.createBitmap(blendWidth, blendHeight, Bitmap.Config.ARGB_8888)
            run {
                val rhsCanvas = Canvas(rhs)
                srcRectWorkspace[offsetX - blendHwidth, 0, offsetX + blendHwidth] =
                    bitmapHeight
                srcRectWorkspace.offset(alignX, alignY)
                dstRectWorkspace[0, -cropY0, blendWidth] = blendHeight - cropY0
                rhsCanvas.drawBitmap(projectedBitmap, srcRectWorkspace, dstRectWorkspace, p)
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "lhs dimensions: " + lhs.width + " x " + lhs.height)
                Log.d(TAG, "rhs dimensions: " + rhs.width + " x " + rhs.height)
            }
            //Bitmap blendedBitmap = blendPanoramaAlpha(lhs, rhs);
            val blendedBitmap = blendPyramids(lhs, rhs)

            /*Bitmap blendedBitmap = Bitmap.createBitmap(2*blendHwidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            Canvas blendedCanvas = new Canvas(blendedBitmap);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            for(int x=0;x<2*blendHwidth;x++) {
                src_rect_workspace.set(x, 0, x+1, bitmapHeight);

                // left hand blend
                // if x=0: frac=1
                // if x=2*blendWidth-1: frac=0
                float frac = (2.0f*blendHwidth-1.0f-x)/(2.0f*blendHwidth-1.0f);
                p.setAlpha((int)(255.0f*frac));
                blended_canvas.drawBitmap(lhs, srcRectWorkspace, srcRectWorkspace, p);

                // right hand blend
                // if x=0: frac=0
                // if x=2*blendWidth-1: frac=1
                frac = ((float)x)/(2.0f*blendHwidth-1.0f);
                p.setAlpha((int)(255.0f*frac));
                blended_canvas.drawBitmap(rhs, srcRectWorkspace, srcRectWorkspace, p);
            }
            p.setAlpha(255); // reset
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)); // reset
            */

            // now draw the blended region
            // note it's intentional that we don't shift for cropY0, see comment above
            canvas.drawBitmap(
                blendedBitmap!!,
                (offsetX + dstOffsetX - blendHwidth - cropX0).toFloat(),
                0f,
                p
            )

            lhs.recycle()
            rhs.recycle()
            blendedBitmap.recycle()
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after blending for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
            )
        }

        var startX = blendHwidth
        var stopX = sliceWidth + blendHwidth
        if (i == 0) startX = -offsetX
        if (i == nBitmaps - 1) {
            stopX = sliceWidth + offsetX
            stopX -= alignX // to undo the shift of srcRectWorkspace by alignX below
        }
        stopX -= shiftStopX
        if (MyDebug.LOG) {
            Log.d(TAG, "    offset_x: $offsetX")
            Log.d(TAG, "    dst_offset_x: $dstOffsetX")
            Log.d(TAG, "    start_x: $startX")
            Log.d(TAG, "    stop_x: $stopX")
        }

        // draw rest of this image
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time before drawing non-blended region for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
        )
        srcRectWorkspace[offsetX + startX, 0, offsetX + stopX] = bitmapHeight
        srcRectWorkspace.offset(alignX, alignY)
        dstRectWorkspace[offsetX + dstOffsetX + startX - cropX0, -cropY0, offsetX + dstOffsetX + stopX - cropX0] =
            bitmapHeight - cropY0
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "    src_rect_workspace: $srcRectWorkspace"
            )
            Log.d(
                TAG,
                "    dst_rect_workspace: $dstRectWorkspace"
            )
        }
        canvas.drawBitmap(projectedBitmap, srcRectWorkspace, dstRectWorkspace, p)
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after drawing non-blended region for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
        )

        /*
        int startX = -blendHwidth;
        int stopX = sliceWidth+blendHwidth;
        if( i == 0 )
            startX = -offsetX;
        if( i == bitmaps.size()-1 )
            stopX = sliceWidth+offsetX;
        stopX -= alignX;
        if( MyDebug.LOG ) {
            Log.d(TAG, "    startX: " + startX);
            Log.d(TAG, "    stopX: " + stopX);
        }

        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        for(int x=startX;x<stopX;x++) {
            src_rect_workspace.set(offsetX + x, 0, offsetX + x+1, bitmapHeight);
            src_rect_workspace.offset(alignX, alignY);
            dst_rect_workspace.set(offsetX + dstOffsetX + x, 0, offsetX + dstOffsetX + x+1, bitmapHeight);

            int blendAlpha = 255;
            if( i > 0 && x < blendHwidth ) {
                // left hand blend
                //blendAlpha = 127;
                // if x=-blendHwidth: frac=0
                // if x=blendHwidth-1: frac=1
                float frac = ((float)x+blendHwidth)/(2*blendHwidth-1.0f);
                blendAlpha = (int)(255.0f*frac);
                //if( MyDebug.LOG )
                //Log.d(TAG, "    left hand blendAlpha: " + blendAlpha);
            }
            else if( i < bitmaps.size()-1 && x > stopX-2*blendHwidth-1 ) {
                // right hand blend
                //blendAlpha = 127;
                // if x=stopX-2*blendHwidth: frac=1
                // if x=stopX-1: frac=0
                float frac = ((float)stopX-1-x)/(2*blendHwidth-1.0f);
                blendAlpha = (int)(255.0f*frac);
                //if( MyDebug.LOG )
                //Log.d(TAG, "    right hand blendAlpha: " + blendAlpha);
            }
            p.setAlpha(blendAlpha);

            //canvas.drawBitmap(bitmap, srcRectWorkspace, dstRectWorkspace, p);
            canvas.drawBitmap(projectedBitmap, srcRectWorkspace, dstRectWorkspace, p);
        }
        p.setAlpha(255); // reset
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)); // reset
        */
        projectedBitmap.recycle()

        /*if( rotatedBitmap != null ) {
            rotated_bitmap.recycle();
        }*/

        /*float x0 = -sliceWidth/2;
        float newHeight0 = bitmapHeight * (float)(h / (h * Math.cos(alpha) - x0 * Math.sin(alpha)));
        if( MyDebug.LOG )
            Log.d(TAG, "    newHeight0: " + newHeight0);

        float x1 = sliceWidth/2;
        float newHeight1 = bitmapHeight * (float)(h / (h * Math.cos(alpha) - x1 * Math.sin(alpha)));
        if( MyDebug.LOG )
            Log.d(TAG, "    newHeight1: " + newHeight1);

        float srcX0 = 0, srcY0 = 0.0f;
        float srcX1 = 0, srcY1 = bitmapHeight;
        float srcX2 = sliceWidth, srcY2 = 0.0f;
        float srcX3 = sliceWidth, srcY3 = bitmapHeight;

        float dstX0 = srcX0, dstY0 = (bitmapHeight - newHeight0)/2.0f;
        float dstX1 = srcX1, dstY1 = (bitmapHeight + newHeight0)/2.0f;
        float dstX2 = srcX2, dstY2 = (bitmapHeight - newHeight1)/2.0f;
        float dstX3 = srcX3, dstY3 = (bitmapHeight + newHeight1)/2.0f;

        float [] srcPoints = new float[]{srcX0, srcY0, srcX1, srcY1, srcX2, srcY2, srcX3, srcY3};
        float [] dstPoints = new float[]{dstX0, dstY0, dstX1, dstY1, dstX2, dstY2, dstX3, dstY3};
        if( MyDebug.LOG ) {
            Log.d(TAG, "    src top-left: " + srcX0 + " , " + srcY0);
            Log.d(TAG, "    src bottom-left: " + srcX1 + " , " + srcY1);
            Log.d(TAG, "    src top-right: " + srcX2 + " , " + srcY2);
            Log.d(TAG, "    src bottom-right: " + srcX3 + " , " + srcY3);
            Log.d(TAG, "    dst top-left: " + dstX0 + " , " + dstY0);
            Log.d(TAG, "    dst bottom-left: " + dstX1 + " , " + dstY1);
            Log.d(TAG, "    dst top-right: " + dstX2 + " , " + dstY2);
            Log.d(TAG, "    dst bottom-right: " + dstX3 + " , " + dstY3);
        }

        Matrix matrix = new Matrix();
        if( !matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4) ) {
            Log.e(TAG, "failed to create matrix");
            throw new RuntimeException();
        }
        if( MyDebug.LOG )
            Log.d(TAG, "matrix: " + matrix);

        matrix.postTranslate(i*sliceWidth, 0.0f);

        Bitmap bitmapSlice = Bitmap.createBitmap(bitmap, (bitmapWidth - sliceWidth)/2, 0, sliceWidth, bitmapHeight);
        canvas.drawBitmap(bitmapSlice, matrix, null);
        bitmap_slice.recycle();
        */
    }

    /**
     * @return Returns the ratio between maximum and minimum computed brightnesses.
     */
    private fun adjustExposuresLocal(
        bitmaps: List<Bitmap>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        sliceWidth: Int,
        timeS: Long
    ): Float {
        val exposureHwidth = bitmapWidth / 10
        val offsetX = (bitmapWidth - sliceWidth) / 2

        val relativeBrightness: MutableList<Float> = ArrayList()
        var currentRelativeBrightness = 1.0f
        relativeBrightness.add(currentRelativeBrightness)
        var minRelativeBrightness = currentRelativeBrightness
        var maxRelativeBrightness = currentRelativeBrightness

        if (MyDebug.LOG) Log.d(
            TAG,
            "### time before computing brightnesses: " + (System.currentTimeMillis() - timeS)
        )

        for (i in 0..<bitmaps.size - 1) {
            // compute brightness difference between i-th and (i+1)-th images
            var bitmapL = bitmaps[i]
            var bitmapR = bitmaps[i + 1]
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time before cropping bitmaps: " + (System.currentTimeMillis() - timeS)
            )

            // scale down for performance
            val scaleMatrix = Matrix()
            scaleMatrix.postScale(0.5f, 0.5f)

            //bitmapL = Bitmap.createBitmap(bitmapL, offsetX+sliceWidth-exposureHwidth, 0, 2*exposureHwidth, bitmapHeight);
            //bitmapR = Bitmap.createBitmap(bitmapR, offsetX-exposureHwidth, 0, 2*exposureHwidth, bitmapHeight);
            bitmapL = Bitmap.createBitmap(
                bitmapL,
                offsetX + sliceWidth - exposureHwidth,
                0,
                2 * exposureHwidth,
                bitmapHeight,
                scaleMatrix,
                true
            )
            bitmapR = Bitmap.createBitmap(
                bitmapR,
                offsetX - exposureHwidth,
                0,
                2 * exposureHwidth,
                bitmapHeight,
                scaleMatrix,
                true
            )
            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after cropping bitmaps: " + (System.currentTimeMillis() - timeS)
            )

            // debug
            /*if( MyDebug.LOG )
            {
                saveBitmap(bitmapL, "exposure_bitmap_l.jpg");
                saveBitmap(bitmapR, "exposure_bitmap_r.jpg");
            }*/
            val histoL = hdrProcessor.computeHistogram(
                bitmapL,
                HDRProcessor.HistogramType.HISTOGRAM_TYPE_VALUE
            )
            val histogramInfo_l: HistogramInfo = hdrProcessor.getHistogramInfo(histoL)
            val histoR = hdrProcessor.computeHistogram(
                bitmapR,
                HDRProcessor.HistogramType.HISTOGRAM_TYPE_VALUE
            )
            val histogramInfo_r: HistogramInfo = hdrProcessor.getHistogramInfo(histoR)

            val brightnessScale = Math.max(
                histogramInfo_r.medianBrightness,
                1
            ).toFloat() / Math.max(histogramInfo_l.medianBrightness, 1).toFloat()
            currentRelativeBrightness *= brightnessScale
            if (MyDebug.LOG) {
                Log.d(TAG, "compare brightnesses from images " + i + " to " + (i + 1) + ":")
                Log.d(TAG, "    left median: " + histogramInfo_l.medianBrightness)
                Log.d(TAG, "    right median: " + histogramInfo_r.medianBrightness)
                Log.d(
                    TAG,
                    "    brightness_scale: $brightnessScale"
                )
                Log.d(
                    TAG,
                    "    current_relative_brightness: $currentRelativeBrightness"
                )
            }
            relativeBrightness.add(currentRelativeBrightness)

            minRelativeBrightness = min(
                minRelativeBrightness.toDouble(),
                currentRelativeBrightness.toDouble()
            ).toFloat()
            maxRelativeBrightness = max(
                maxRelativeBrightness.toDouble(),
                currentRelativeBrightness.toDouble()
            ).toFloat()

            if (bitmapL != bitmaps[i]) bitmapL.recycle()
            if (bitmapR != bitmaps[i + 1]) bitmapR.recycle()
        }

        val ratioBrightnesses = (maxRelativeBrightness / minRelativeBrightness)
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "min_relative_brightness: $minRelativeBrightness"
            )
            Log.d(
                TAG,
                "max_relative_brightness: $maxRelativeBrightness"
            )
            Log.d(
                TAG,
                "ratio of max to min relative brightness: $ratioBrightnesses"
            )
        }

        /*
        float avgRelativeBrightness = 0.0f;
        int count = 0;
        for(float b : relativeBrightness) {
            avgRelativeBrightness += b;
            count++;
        }
        avgRelativeBrightness /= count;
        if( MyDebug.LOG )
            Log.d(TAG, "avgRelativeBrightness: " + avgRelativeBrightness);
        */
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after computing brightnesses: " + (System.currentTimeMillis() - timeS)
        )

        val histogramInfos: MutableList<HistogramInfo> = ArrayList<HistogramInfo>()
        var meanMedianBrightness = 0.0f // mean of the global median brightnesse
        var meanEqualisedBrightness =
            0.0f // mean of the brightnesses if all adjusted to match exposure of the first image
        for (i in bitmaps.indices) {
            val bitmap = bitmaps[i]
            val histo = hdrProcessor.computeHistogram(
                bitmap,
                HDRProcessor.HistogramType.HISTOGRAM_TYPE_VALUE
            )
            val histogramInfo: HistogramInfo = hdrProcessor.getHistogramInfo(histo)
            histogramInfos.add(histogramInfo)
            meanMedianBrightness += histogramInfo.medianBrightness
            val equalisedBrightness: Float =
                histogramInfo.medianBrightness / relativeBrightness[i]
            meanEqualisedBrightness += equalisedBrightness
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "image " + i + " has median brightness " + histogramInfo.medianBrightness
                )
                Log.d(
                    TAG,
                    "    and equalised_brightness $equalisedBrightness"
                )
            }
        }
        meanMedianBrightness /= bitmaps.size.toFloat()
        meanEqualisedBrightness /= bitmaps.size.toFloat()
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "mean_median_brightness: $meanMedianBrightness"
            )
            Log.d(
                TAG,
                "mean_equalised_brightness: $meanEqualisedBrightness"
            )
        }

        val avgRelativeBrightness =
            (meanMedianBrightness / max(meanEqualisedBrightness.toDouble(), 1.0)).toFloat()

        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after computing global histograms: " + (System.currentTimeMillis() - timeS)
        )

        var minPreferredScale = 1000.0f
        var maxPreferredScale = 0.0f
        for (i in bitmaps.indices) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "    adjust exposure for image: $i"
            )

            val bitmap = bitmaps[i]
            val histogramInfo: HistogramInfo = histogramInfos[i]

            var brightnessTarget =
                (histogramInfo.medianBrightness * avgRelativeBrightness / relativeBrightness[i] + 0.1f).toInt()
            brightnessTarget = min(255.0, brightnessTarget.toDouble()).toInt()
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "    image $i has initial brightness_target: $brightnessTarget"
                )
                Log.d(TAG, "    median_brightness: " + histogramInfo.medianBrightness)
                Log.d(TAG, "    relative_brightness: " + relativeBrightness[i])
                Log.d(
                    TAG,
                    "    avg_relative_brightness: $avgRelativeBrightness"
                )
            }

            minPreferredScale = min(
                minPreferredScale.toDouble(),
                (brightnessTarget / histogramInfo.medianBrightness.toFloat()).toDouble()
            ).toFloat()
            maxPreferredScale = max(
                maxPreferredScale.toDouble(),
                (brightnessTarget / histogramInfo.medianBrightness.toFloat()).toDouble()
            ).toFloat()
            val minBrightness = (histogramInfo.medianBrightness * 0.5f + 0.5f).toInt()
            val maxBrightness = (histogramInfo.medianBrightness * 2.0f + 0.5f).toInt()
            var thisBrightnessTarget = brightnessTarget
            thisBrightnessTarget =
                max(thisBrightnessTarget.toDouble(), minBrightness.toDouble()).toInt()
            thisBrightnessTarget =
                min(thisBrightnessTarget.toDouble(), maxBrightness.toDouble()).toInt()
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "    brightness_target: $brightnessTarget"
                )
                Log.d(
                    TAG,
                    "    preferred brightness scale: " + brightnessTarget / histogramInfo.medianBrightness.toFloat()
                )
                Log.d(
                    TAG,
                    "    this_brightness_target: $thisBrightnessTarget"
                )
                Log.d(
                    TAG,
                    "    actual brightness scale: " + thisBrightnessTarget / histogramInfo.medianBrightness.toFloat()
                )
            }

            hdrProcessor.brightenImage(
                bitmap,
                histogramInfo.medianBrightness,
                histogramInfo.maxBrightness,
                thisBrightnessTarget
            )
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "min_preferred_scale: $minPreferredScale"
            )
            Log.d(
                TAG,
                "max_preferred_scale: $maxPreferredScale"
            )
            Log.d(
                TAG,
                "### time after adjusting brightnesses: " + (System.currentTimeMillis() - timeS)
            )
        }

        /*if( minPreferredScale < 0.5f || maxPreferredScale > 2.0f ) {
            throw new RuntimeException("");
        }*/
        return ratioBrightnesses
    }

    /*private void adjustExposures(List<Bitmap> bitmaps, long timeS) {
        List<HDRProcessor.HistogramInfo> histogramInfos = new ArrayList<>();

        float meanMedianBrightness = 0.0f;
        List<Integer> medianBrightnesses = new ArrayList<>();
        for(int i=0;i<bitmaps.size();i++) {
            Bitmap bitmap = bitmaps.get(i);
            int [] histo = hdrProcessor.computeHistogram(bitmap, false);
            HDRProcessor.HistogramInfo histogramInfo = hdrProcessor.getHistogramInfo(histo);
            histogramInfos.add(histogramInfo);
            meanMedianBrightness += histogramInfo.medianBrightness;
            median_brightnesses.add(histogramInfo.medianBrightness);
            if( MyDebug.LOG ) {
                Log.d(TAG, "image " + i + " has median brightness " + histogramInfo.medianBrightness);
            }
        }
        meanMedianBrightness /= bitmaps.size();
        final int brightnessTarget = (int)(meanMedianBrightness + 0.1f);
        if( MyDebug.LOG )
            Log.d(TAG, "meanMedianBrightness: " + meanMedianBrightness);
        if( MyDebug.LOG )
            Log.d(TAG, "### time after computing brightnesses: " + (System.currentTimeMillis() - timeS));
        float minPreferredScale = 1000.0f, maxPreferredScale = 0.0f;
        for(int i=0;i<bitmaps.size();i++) {
            Bitmap bitmap = bitmaps.get(i);
            HDRProcessor.HistogramInfo histogramInfo = histogramInfos.get(i);
            if( MyDebug.LOG )
                Log.d(TAG, "    adjust exposure for image: " + i);

            // use local average
            //float localMeanBrightness = median_brightnesses.get(i);
            //int count = 1;
            //if( i > 0 ) {
            //    localMeanBrightness += median_brightnesses.get(i-1);
            //    count++;
            //}
            //if( i < bitmaps.size()-1 ) {
            //    localMeanBrightness += median_brightnesses.get(i+1);
            //    count++;
            //}
            //localMeanBrightness /= count;
            //if( MyDebug.LOG )
            //    Log.d(TAG, "    localMeanBrightness: " + localMeanBrightness);
            //final int brightnessTarget = (int)(localMeanBrightness + 0.1f);

            minPreferredScale = Math.min(minPreferredScale, brightnessTarget/(float)histogramInfo.medianBrightness);
            maxPreferredScale = Math.max(maxPreferredScale, brightnessTarget/(float)histogramInfo.medianBrightness);
            int minBrightness = (int)(histogramInfo.medianBrightness*2.0f/3.0f+0.5f);
            //int minBrightness = (int)(histogramInfo.medianBrightness*1.0f+0.5f);
            int maxBrightness = (int)(histogramInfo.medianBrightness*1.5f+0.5f);
            int thisBrightnessTarget = brightnessTarget;
            thisBrightnessTarget = Math.max(thisBrightnessTarget, minBrightness);
            thisBrightnessTarget = Math.min(thisBrightnessTarget, maxBrightness);
            if( MyDebug.LOG ) {
                Log.d(TAG, "    brightnessTarget: " + brightnessTarget);
                Log.d(TAG, "    preferred brightness scale: " + brightnessTarget / (float) histogramInfo.medianBrightness);
                Log.d(TAG, "    thisBrightnessTarget: " + thisBrightnessTarget);
                Log.d(TAG, "    actual brightness scale: " + thisBrightnessTarget / (float) histogramInfo.medianBrightness);
            }

            hdrProcessor.brightenImage(bitmap, histogramInfo.medianBrightness, histogramInfo.maxBrightness, thisBrightnessTarget);
        }
        if( MyDebug.LOG ) {
            Log.d(TAG, "minPreferredScale: " + minPreferredScale);
            Log.d(TAG, "maxPreferredScale: " + maxPreferredScale);
            Log.d(TAG, "### time after adjusting brightnesses: " + (System.currentTimeMillis() - timeS));
        }
    }*/
    @Throws(PanoramaProcessorException::class)
    private fun computePanoramaTransforms(
        cumulativeTransforms: MutableList<Matrix>,
        alignXValues: MutableList<Int>,
        dstOffsetXValues: MutableList<Int>,
        bitmaps: List<Bitmap>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        offsetX: Int,
        sliceWidth: Int,
        alignHwidth: Int,
        timeS: Long
    ) {
        val cumulativeTransform = Matrix()
        var alignX = 0
        var alignY = 0
        var dstOffsetX = 0

        //List<Integer> alignYValues = new ArrayList<>();
        val useAutoAlign = true

        //final boolean useAutoAlign = false;
        for (i in bitmaps.indices) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "process bitmap: $i"
            )

            var angleZ = 0.0

            if (useAutoAlign && i > 0) {
                // autoalignment
                val alignmentBitmaps: MutableList<Bitmap> = ArrayList()
                //alignment_bitmaps.add( Bitmap.createBitmap(bitmaps.get(i-1), offsetX+sliceWidth-alignHwidth, 0, 2*alignHwidth, bitmapHeight) );
                //alignment_bitmaps.add( Bitmap.createBitmap(bitmaps.get(i), offsetX-alignHwidth, 0, 2*alignHwidth, bitmapHeight) );
                // tall:
                if (MyDebug.LOG) {
                    Log.d(TAG, "    align_x: $alignX")
                    Log.d(TAG, "    offset_x: $offsetX")
                    Log.d(
                        TAG,
                        "    slice_width: $sliceWidth"
                    )
                    Log.d(
                        TAG,
                        "    align_x+offset_x+slice_width-align_hwidth: " + (alignX + offsetX + sliceWidth - alignHwidth)
                    )
                    Log.d(TAG, "    bitmap(i-1) width: " + bitmaps[i - 1].width)
                }

                //final boolean useAlignByFeature = false;
                val useAlignByFeature = true
                var alignDownsample = 1.0f
                if (useAlignByFeature) {
                    // scale height to 520
                    // although in theory the alignment algorithm should work on any size, it is best to standardise, as most testing
                    // was done where input images had height 2080 or 2048, and the alignment images were downscaled by a factor of 4
                    alignDownsample = bitmapHeight / 520.0f
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "downscale by: $alignDownsample"
                        )
                        Log.d(
                            TAG,
                            "### time before downscaling creating alignment bitmaps for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
                        )
                    }
                    // snap to power of 2
                    var k = 0
                    var power = 1
                    while (k <= 4) {
                        val ratio = (power / alignDownsample).toDouble()
                        if (ratio >= 0.95f && ratio <= 1.05f) {
                            alignDownsample = power.toFloat()
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "snapped downscale to: $alignDownsample"
                            )
                            break
                        }
                        k++
                        power *= 2
                    }
                }

                val alignBitmapHeight = (3 * bitmapHeight) / 4
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time before creating alignment bitmaps for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
                )
                // n.b., we add in reverse order, so we find the transformation to map the next image (i) onto the previous image (i-1)
                //alignment_bitmaps.add( Bitmap.createBitmap(bitmaps.get(i), alignX+offsetX-alignHwidth, (bitmapHeight-alignBitmapHeight)/2, 2*alignHwidth, alignBitmapHeight) );
                //alignment_bitmaps.add( Bitmap.createBitmap(bitmaps.get(i-1), alignX+offsetX+sliceWidth-alignHwidth, (bitmapHeight-alignBitmapHeight)/2, 2*alignHwidth, alignBitmapHeight) );
                val alignScaleMatrix = Matrix()
                alignScaleMatrix.postScale(1.0f / alignDownsample, 1.0f / alignDownsample)
                alignmentBitmaps.add(
                    Bitmap.createBitmap(
                        bitmaps[i],
                        alignX + offsetX - alignHwidth,
                        (bitmapHeight - alignBitmapHeight) / 2,
                        2 * alignHwidth,
                        alignBitmapHeight,
                        alignScaleMatrix,
                        true
                    )
                )
                alignmentBitmaps.add(
                    Bitmap.createBitmap(
                        bitmaps[i - 1],
                        alignX + offsetX + sliceWidth - alignHwidth,
                        (bitmapHeight - alignBitmapHeight) / 2,
                        2 * alignHwidth,
                        alignBitmapHeight,
                        alignScaleMatrix,
                        true
                    )
                )
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after creating alignment bitmaps for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
                )

                /*if( useAlignByFeature ) {
                    Matrix alignScaleMatrix = new Matrix();
                    align_scale_matrix.postScale(1.0f/alignDownsample, 1.0f/alignDownsample);
                    for(int j=0;j<alignment_bitmaps.size();j++) {
                        Bitmap newBitmap = Bitmap.createBitmap(alignment_bitmaps.get(j), 0, 0, alignment_bitmaps.get(j).getWidth(), alignment_bitmaps.get(j).getHeight(), alignScaleMatrix, true);
                        alignment_bitmaps.get(j).recycle();
                        alignment_bitmaps.set(j, newBitmap);
                    }
                    if( MyDebug.LOG )
                        Log.d(TAG, "### time after downscaling creating alignment bitmaps for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS));
                }*/

                // save bitmaps used for alignments
                /*if( MyDebug.LOG ) {
                    for(int j=0;j<alignment_bitmaps.size();j++) {
                        Bitmap alignmentBitmap = alignment_bitmaps.get(j);
                        saveBitmap(alignmentBitmap, "alignment_bitmap_" + i + "_" + j +".png");
                    }
                }*/
                var thisAlignX: Int
                var thisAlignY: Int
                var yScale = 1.0f
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time before auto-alignment for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
                )
                if (useAlignByFeature) {
                    val res = autoAlignmentByFeature(
                        alignmentBitmaps[0].width,
                        alignmentBitmaps[0].height,
                        alignmentBitmaps,
                        i
                    )
                    thisAlignX = res.offsetX
                    thisAlignY = res.offsetY
                    angleZ = res.rotation.toDouble()
                    yScale = res.yScale
                } else {
                    val useMtb = false
                    //final boolean useMtb = true;
                    val offsetsX = IntArray(alignmentBitmaps.size)
                    val offsetsY = IntArray(alignmentBitmaps.size)
                    hdrProcessor.autoAlignment(
                        offsetsX,
                        offsetsY,
                        alignmentBitmaps[0].width,
                        alignmentBitmaps[0].height,
                        alignmentBitmaps,
                        0,
                        useMtb,
                        8
                    )
                    thisAlignX = offsetsX[1]
                    thisAlignY = offsetsY[1]
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after auto-alignment for " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
                )
                thisAlignX = (thisAlignX * alignDownsample).toInt()
                thisAlignY = (thisAlignY * alignDownsample).toInt()
                for (alignmentBitmap in alignmentBitmaps) {
                    alignmentBitmap.recycle()
                }
                alignmentBitmaps.clear()
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "    this_align_x: $thisAlignX"
                    )
                    Log.d(
                        TAG,
                        "    this_align_y: $thisAlignY"
                    )
                }

                val thisTransform = Matrix()
                thisTransform.postRotate(
                    Math.toDegrees(angleZ).toFloat(),
                    (alignX + offsetX - alignHwidth).toFloat(),
                    0f
                )
                thisTransform.postScale(1.0f, yScale)
                thisTransform.postTranslate(thisAlignX.toFloat(), thisAlignY.toFloat())

                run {
                    // first need to shift cumulativeTransform so that it's about the origin of the new bitmap
                    cumulativeTransform.preTranslate(sliceWidth.toFloat(), 0.0f)
                    cumulativeTransform.postTranslate(-sliceWidth.toFloat(), 0.0f)
                    cumulativeTransform.preConcat(thisTransform)
                }

                run {
                    /*float [] values = new float[9];
                                       cumulative_transform.getValues(values);
                                       alignX = - (int)values[Matrix.MTRANS_X];*/
                    val points = FloatArray(2)
                    points[0] = bitmapWidth / 2.0f
                    points[1] = bitmapHeight / 2.0f
                    cumulativeTransform.mapPoints(points)
                    val transX = points[0] - bitmapWidth / 2.0f
                    alignX = -transX.toInt()
                }

                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "    align_x is now: $alignX"
                    )
                    Log.d(
                        TAG,
                        "    align_y is now: $alignY"
                    )
                }
            }

            alignXValues.add(alignX)
            //align_y_values.add(alignY);
            dstOffsetXValues.add(dstOffsetX)
            cumulativeTransforms.add(Matrix(cumulativeTransform))

            run {
                dstOffsetX += sliceWidth
                // set back to zero after we've saved them, so we don't use them in the later iterations of this loop
                alignX = 0
                alignY = 0
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "    dst_offset_x is now: $dstOffsetX"
            )

            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after processing " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
            )
        }
    }

    /** Typically images will have different rotations. Rather than assuming the first image is the
     * optimal transform (with no rotation), we rotate the transforms to the mean of the rotations.
     * This is effectively equivalent to rotating the final image to be hopefully more level.
     */
    private fun adjustPanoramaTransforms(
        bitmaps: List<Bitmap>, cumulativeTransforms: List<Matrix>,
        panoramaWidth: Int, sliceWidth: Int, bitmapWidth: Int, bitmapHeight: Int
    ) {
        val values = FloatArray(9)

        var minRotation = 1000f
        var maxRotation = -1000f
        var sumRotation = 0.0f
        for (i in bitmaps.indices) {
            cumulativeTransforms[i].getValues(values)
            // get rotation anticlockwise in degrees - https://stackoverflow.com/questions/12256854/get-the-rotate-value-from-matrix-in-android
            val rotation = Math.toDegrees(
                atan2(
                    values[Matrix.MSKEW_X].toDouble(),
                    values[Matrix.MSCALE_X].toDouble()
                )
            ).toFloat()
            if (MyDebug.LOG) Log.d(
                TAG,
                "bitmap $i has rotation $rotation degrees"
            )
            minRotation = min(minRotation.toDouble(), rotation.toDouble()).toFloat()
            maxRotation = max(maxRotation.toDouble(), rotation.toDouble()).toFloat()
            sumRotation += rotation
        }
        //float midRotation = 0.5f*(minRotation + maxRotation);
        //float midRotation = sumRotation/bitmaps.size();
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "min_rotation: $minRotation degrees"
            )
            Log.d(
                TAG,
                "max_rotation: $maxRotation degrees"
            )
            //Log.d(TAG, "midRotation: " + midRotation + " degrees");
        }

        // this method helps testPanorama29
        val points = FloatArray(2)
        points[0] = 0.0f
        points[1] = bitmapHeight / 2.0f
        cumulativeTransforms[0].mapPoints(points)
        val x0 = points[0]
        val y0 = points[1]
        points[0] = bitmapWidth - 1.0f
        points[1] = bitmapHeight / 2.0f
        cumulativeTransforms[cumulativeTransforms.size - 1].mapPoints(points)
        val x1 = points[0] + (cumulativeTransforms.size - 1) * sliceWidth
        val y1 = points[1]
        val dx = x1 - x0
        val dy = y1 - y0
        var midRotation = -Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (MyDebug.LOG) {
            Log.d(TAG, "x0: $x0")
            Log.d(TAG, "y0: $y0")
            Log.d(TAG, "x1: $x1")
            Log.d(TAG, "y1: $y1")
            Log.d(TAG, "dx: $dx")
            Log.d(TAG, "dy: $dy")
            Log.d(
                TAG,
                "mid_rotation: $midRotation degrees"
            )
        }
        // but don't rotate more than the input transforms - helps testPanorama22
        midRotation = max(midRotation.toDouble(), minRotation.toDouble()).toFloat()
        midRotation = min(midRotation.toDouble(), maxRotation.toDouble()).toFloat()
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "limited mid_rotation to: $midRotation degrees"
            )
        }

        // we now apply a rotation of -midRotation about what will be the centre of the resultant panoramic image, remembering
        // that each matrix in cumulativeTransforms is set up for each input images coordinate space
        for (i in bitmaps.indices) {
            val centreX = panoramaWidth / 2.0f - i * sliceWidth
            val centreY = bitmapHeight / 2.0f
            // apply a post rotate of midRotation clockwise about (centreX, centreY)
            cumulativeTransforms[i].postRotate(midRotation, centreX, centreY)
            run {
                cumulativeTransforms[i].getValues(values)
                val rotation = Math.toDegrees(
                    atan2(
                        values[Matrix.MSKEW_X].toDouble(),
                        values[Matrix.MSCALE_X].toDouble()
                    )
                ).toFloat()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "bitmap $i now has rotation $rotation degrees"
                )
            }
        }
    }

    private fun renderPanorama(
        bitmaps: List<Bitmap>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        cumulativeTransforms: List<Matrix>,
        alignXValues: List<Int>,
        dstOffsetXValues: List<Int>,
        blendHwidth: Int,
        sliceWidth: Int,
        offsetX: Int,
        panorama: Bitmap,
        cropX0: Int,
        cropY0: Int,
        cameraAngle: Double,
        timeS: Long
    ) {
        val srcRect = Rect()
        val dstRect = Rect()
        //Paint p = new Paint();
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val canvas = Canvas(panorama)

        for (i in bitmaps.indices) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "render bitmap: $i"
            )
            var bitmap = bitmaps[i]
            var alignX = alignXValues[i]
            //int alignY = align_y_values.get(i);
            val alignY = 0
            var dstOffsetX = dstOffsetXValues[i]

            var freeBitmap = false
            var shiftStopX = alignX
            var centreShiftX: Int

            run {
                val shiftTransition = true
                //final boolean shiftTransition = false;
                centreShiftX = -alignX
                alignX = 0
                //alignY = 0;
                if (!shiftTransition) {
                    shiftStopX = 0
                }

                if (i != 0 && shiftTransition) {
                    val shiftStartX = alignXValues[i - 1] // +ve means shift to the left
                    dstOffsetX -= shiftStartX
                    alignX = -shiftStartX
                    shiftStopX -= shiftStartX
                }

                if (alignX != 0) {
                    // Bake the alignment into the transform.
                    // Otherwise we have risk that we can transform the image too far off the bitmap, only to try to undo
                    // that translation via alignX in renderPanoramaImage(), which means we get black regions due to having
                    // lost the parts of the image that were translated too far!
                    // This can show up when the blendHwidth is sufficiently large, and means we get dark bands on the
                    // resultant image.

                    val points = FloatArray(2)
                    points[0] = bitmapWidth / 2.0f
                    points[1] = bitmapHeight / 2.0f
                    cumulativeTransforms[i].mapPoints(points)
                    val transX = (points[0] - bitmapWidth / 2.0f).toInt()

                    var bakeTransX = -alignX
                    // ...but on the last image, we don't want to shift too far off screen, as we'll then chop
                    // off part of the image.
                    // See testPanorama19, where without this fix we lose a bit along the right hand side
                    if (i == bitmaps.size - 1 && transX < 0 && bakeTransX + transX > 0) {
                        bakeTransX = -transX
                        //if( true )
                        //    throw new RuntimeException(); // test
                    }

                    cumulativeTransforms[i].postTranslate(bakeTransX.toFloat(), 0.0f)
                    //if( MyDebug.LOG )
                    //Log.d(TAG, "centreShiftX: " + centreShiftX);
                    //if( MyDebug.LOG )
                    //Log.d(TAG, "    alignX: " + alignX);
                    centreShiftX += bakeTransX
                    //if( MyDebug.LOG )
                    //Log.d(TAG, "new centreShiftX: " + centreShiftX);
                    alignX += bakeTransX
                }
                run {
                    val rotatedBitmap =
                        Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                    val rotatedCanvas = Canvas(rotatedBitmap)
                    rotatedCanvas.save()

                    rotatedCanvas.setMatrix(cumulativeTransforms[i])

                    rotatedCanvas.drawBitmap(bitmap, 0f, 0f, p)
                    rotatedCanvas.restore()

                    bitmap = rotatedBitmap
                    /*if( MyDebug.LOG ) {
                        saveBitmap(bitmap, "transformed_bitmap_" + i + ".jpg");
                    }*/
                    freeBitmap = true
                }
            }

            renderPanoramaImage(
                i, bitmaps.size, srcRect, dstRect,
                bitmap, p, bitmapWidth, bitmapHeight,
                blendHwidth, sliceWidth, offsetX,
                panorama, canvas, cropX0, cropY0,
                alignX, alignY, dstOffsetX, shiftStopX, centreShiftX,
                cameraAngle, timeS
            )

            if (freeBitmap) {
                bitmap.recycle()
            }

            if (MyDebug.LOG) Log.d(
                TAG,
                "### time after rendering " + i + "th bitmap: " + (System.currentTimeMillis() - timeS)
            )
        }
    }

    @Throws(PanoramaProcessorException::class)
    fun panorama(
        bitmaps: MutableList<Bitmap>,
        panoramaPicsPerScreen: Float,
        cameraAngleY: Float,
        crop: Boolean
    ): Bitmap {
        if (MyDebug.LOG) {
            Log.d(TAG, "panorama")
            Log.d(TAG, "camera_angle_y: $cameraAngleY")
        }

        var timeS: Long = 0
        if (MyDebug.LOG) timeS = System.currentTimeMillis()

        val bitmapWidth = bitmaps[0].width
        val bitmapHeight = bitmaps[0].height
        if (MyDebug.LOG) {
            Log.d(TAG, "bitmap_width: $bitmapWidth")
            Log.d(TAG, "bitmap_height: $bitmapHeight")
        }

        for (i in 1..<(bitmaps?.size ?: 0)) {
            val bitmap = bitmaps?.get(i)
            if (bitmap?.width != bitmapWidth || bitmap?.height != bitmapHeight) {
                Log.e(TAG, "bitmaps not of equal sizes")
                throw PanoramaProcessorException(PanoramaProcessorException.UNEQUAL_SIZES)
            }
        }

        /*{
            // test
            for(int i=0;i<bitmaps.size();i++) {
                Bitmap bitmap = bitmaps.get(i);
                saveBitmap(bitmap, "input_bitmap_" + i +".png");
            }
        }*/
        val sliceWidth = (bitmapWidth / panoramaPicsPerScreen).toInt()
        if (MyDebug.LOG) Log.d(
            TAG,
            "slice_width: $sliceWidth"
        )

        val cameraAngle = Math.toRadians(cameraAngleY.toDouble())
        if (MyDebug.LOG) {
            Log.d(TAG, "camera_angle_y: $cameraAngleY")
            Log.d(TAG, "camera_angle: $cameraAngle")
        }

        // max offset error of gyroTolDegrees - convert this to pixels
        //int maxOffsetErrorX = (int)(gyroTolDegrees * bitmapWidth / mActivity.preview.getViewAngleY() + 0.5f);
        //int maxOffsetErrorY = (int)(gyroTolDegrees * bitmapHeight / mActivity.preview.getViewAngleX() + 0.5f);
        //if we use the above code, remember not to use the camera view angles, but those that the test photos were taken with!
        //double h = ((double)bitmapWidth) / (2.0 * Math.tan(cameraAngle/2.0) );
        /*int maxOffsetErrorX = (int)(h * Math.tan(Math.toRadians(gyroTolDegrees)) + 0.5f);
        maxOffsetErrorX *= 2; // allow a fudge factor
        int maxOffsetErrorY = maxOffsetErrorX;
        if( MyDebug.LOG ) {
            Log.d(TAG, "h: " + h);
            Log.d(TAG, "maxOffsetErrorX: " + maxOffsetErrorX);
            Log.d(TAG, "maxOffsetErrorY: " + maxOffsetErrorY);
        }
        */
        val offsetX = (bitmapWidth - sliceWidth) / 2
        // blendHwidth is the half-width of the region that we blend between.
        // N.B., when using blendPyramids(), the region we actually have blending over is only half
        // of the width of the images it receives to blend receive (i.e., the blend region width
        // is equal to blendHwidth), because of the code to find a best path.
        // Reduced to bitmapWidth/10.0f to improve performance.
        //final int blendHwidth = 0;
        //final int blendHwidth = nextPowerOf2(bitmapWidth/20);
        //final int blendHwidth = nextPowerOf2(bitmapWidth/10);
        //final int blendHwidth = nextMultiple((int)(bitmapWidth/6.1f+0.5f), getBlendDimension()/2);
        val blendHwidth = nextMultiple((bitmapWidth / 10.0f + 0.5f).toInt(), blendDimension / 2)
        //final int blendHwidth = nextPowerOf2(bitmapWidth/5);
        val alignHwidth = bitmapWidth / 10
        //final int alignHwidth = bitmapWidth/5;
        if (MyDebug.LOG) {
            Log.d(TAG, "    blend_hwidth: $blendHwidth")
            Log.d(TAG, "    align_hwidth: $alignHwidth")
        }

        val cumulativeTransforms: MutableList<Matrix> =
            ArrayList() // i-th entry is the transform to apply to the i-th bitmap so that it's aligned to the same space as the 1st bitmap

        val alignXValues: MutableList<Int> = ArrayList()
        val dstOffsetXValues: MutableList<Int> = ArrayList()

        computePanoramaTransforms(
            cumulativeTransforms, alignXValues, dstOffsetXValues, bitmaps,
            bitmapWidth, bitmapHeight, offsetX, sliceWidth, alignHwidth, timeS
        )

        // note that we crop the panoramaWidth later on, but for now we still need an estimate, before finalising
        // the transforms
        var panoramaWidth = (bitmaps.size * sliceWidth + 2 * offsetX)
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "original panorama_width: $panoramaWidth"
            )
        }

        adjustPanoramaTransforms(
            bitmaps,
            cumulativeTransforms,
            panoramaWidth,
            sliceWidth,
            bitmapWidth,
            bitmapHeight
        )
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after adjusting transforms: " + (System.currentTimeMillis() - timeS)
        )

        //adjustExposures(bitmaps, timeS);
        val ratioBrightnesses =
            adjustExposuresLocal(bitmaps, bitmapWidth, bitmapHeight, sliceWidth, timeS)

        var panoramaHeight = bitmapHeight
        var cropX0 = 0
        var cropY0 = 0

        if (crop) {
            // compute crop regions
            var cropX1 = bitmapWidth - 1
            var cropY1 = bitmapHeight - 1
            for (i in bitmaps.indices) {
                val points = FloatArray(8)

                points[0] = 0.0f
                points[1] = 0.0f

                points[2] = bitmapWidth - 1.0f
                points[3] = 0.0f

                points[4] = 0.0f
                points[5] = bitmapHeight - 1.0f

                points[6] = bitmapWidth - 1.0f
                points[7] = bitmapHeight - 1.0f

                cumulativeTransforms[i].mapPoints(points)

                cropY0 = max(cropY0.toDouble(), points[1].toInt().toDouble()).toInt()
                cropY0 = max(cropY0.toDouble(), points[3].toInt().toDouble()).toInt()

                cropY1 = min(cropY1.toDouble(), points[5].toInt().toDouble()).toInt()
                cropY1 = min(cropY1.toDouble(), points[7].toInt().toDouble()).toInt()

                if (MyDebug.LOG) {
                    Log.d(TAG, "i: $i")
                    Log.d(TAG, "    points[0]: " + points[0])
                    Log.d(TAG, "    points[1]: " + points[1])
                    Log.d(TAG, "    points[2]: " + points[2])
                    Log.d(TAG, "    points[3]: " + points[3])
                    Log.d(TAG, "    points[4]: " + points[4])
                    Log.d(TAG, "    points[5]: " + points[5])
                    Log.d(TAG, "    points[6]: " + points[6])
                    Log.d(TAG, "    points[7]: " + points[7])
                }
                if (i == 0) {
                    cropX0 = max(cropX0.toDouble(), points[0].toInt().toDouble()).toInt()
                    cropX0 = max(cropX0.toDouble(), points[4].toInt().toDouble()).toInt()
                }
                if (i == bitmaps.size - 1) {
                    cropX1 = min(cropX1.toDouble(), points[2].toInt().toDouble()).toInt()
                    cropX1 = min(cropX1.toDouble(), points[6].toInt().toDouble()).toInt()
                }
            }

            panoramaWidth -= (bitmapWidth - 1) - cropX1
            panoramaWidth -= cropX0
            if (MyDebug.LOG) {
                Log.d(TAG, "crop_x0: $cropX0")
                Log.d(TAG, "crop_x1: $cropX1")
                Log.d(
                    TAG,
                    "panorama_width: $panoramaWidth"
                )
            }

            /*if( cropX0 > 0 ) {
                // need to shift transforms over
                for(int i=0;i<bitmaps.size();i++) {
                    cumulative_transforms.get(i).postTranslate(-cropX0, 0.0f);
                }
            }*/
            panoramaHeight = cropY1 - cropY0 + 1
            if (MyDebug.LOG) {
                Log.d(TAG, "crop_y0: $cropY0")
                Log.d(TAG, "crop_y1: $cropY1")
                Log.d(
                    TAG,
                    "panorama_height: $panoramaHeight"
                )
            }

            // take cylindrical projection into account
            val theta = ((bitmapWidth / 2) * cameraAngle).toFloat() / bitmapWidth.toFloat()
            val yscale = cos(theta.toDouble()).toFloat()
            if (MyDebug.LOG) {
                Log.d(TAG, "theta: $theta")
                Log.d(TAG, "yscale: $yscale")
            }
            //yscale = 1.0f;
            cropY0 =
                (bitmapHeight / 2.0f + yscale * (cropY0 - bitmapHeight / 2.0f) + 0.5f).toInt()
            cropY1 =
                (bitmapHeight / 2.0f + yscale * (cropY1 - bitmapHeight / 2.0f) + 0.5f).toInt()

            panoramaHeight = cropY1 - cropY0 + 1
            if (MyDebug.LOG) {
                Log.d(TAG, "crop_y0: $cropY0")
                Log.d(TAG, "crop_y1: $cropY1")
                Log.d(
                    TAG,
                    "panorama_height: $panoramaHeight"
                )
            }

            if (panoramaHeight <= 0) {
                // can happen if the transforms are such that we move off top or bottom of screen! Better to fail gracefully
                Log.e(
                    TAG,
                    "crop caused panorama height to become -ve: $panoramaHeight"
                )
                throw PanoramaProcessorException(PanoramaProcessorException.FAILED_TO_CROP)
            }
        }

        val panorama = Bitmap.createBitmap(panoramaWidth, panoramaHeight, Bitmap.Config.ARGB_8888)

        if (MyDebug.LOG) Log.d(
            TAG,
            "### time before rendering bitmaps: " + (System.currentTimeMillis() - timeS)
        )
        renderPanorama(
            bitmaps.toList(),
            bitmapWidth,
            bitmapHeight,
            cumulativeTransforms,
            alignXValues,
            dstOffsetXValues,
            blendHwidth,
            sliceWidth,
            offsetX,
            panorama,
            cropX0,
            cropY0,
            cameraAngle,
            timeS
        )
        if (MyDebug.LOG) Log.d(
            TAG,
            "### time after rendering bitmaps: " + (System.currentTimeMillis() - timeS)
        )

        for (bitmap in bitmaps) {
            bitmap?.recycle()
        }
        bitmaps.clear()

        if (ratioBrightnesses >= 3.0f) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "apply contrast enhancement, ratio_brightnesses: $ratioBrightnesses"
            )

            /*if( true )
                throw new RuntimeException("ratioBrightnesses: " + ratioBrightnesses);*/
            if (!HDRProcessor.useRenderscript) {
                hdrProcessor.adjustHistogram(
                    panorama,
                    panorama,
                    panorama.width,
                    panorama.height,
                    0.25f,
                    1,
                    true,
                    timeS
                )
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after adjustHistogram: " + (System.currentTimeMillis() - timeS)
                )
            } else {
                val allocation = Allocation.createFromBitmap(rs, panorama)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after creating allocation_out: " + (System.currentTimeMillis() - timeS)
                )
                hdrProcessor.adjustHistogramRS(
                    allocation,
                    allocation,
                    panorama.width,
                    panorama.height,
                    0.25f,
                    1,
                    true,
                    timeS
                )
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after adjustHistogram: " + (System.currentTimeMillis() - timeS)
                )
                allocation.copyTo(panorama)
                allocation.destroy()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "### time after copying to bitmap: " + (System.currentTimeMillis() - timeS)
                )
            }
        }

        if (MyDebug.LOG) Log.d(TAG, "panorama complete!")

        freeScripts()

        if (MyDebug.LOG) Log.d(
            TAG,
            "### time taken for panorama: " + (System.currentTimeMillis() - timeS)
        )

        return panorama
    }

    companion object {
        private const val TAG = "PanoramaProcessor"

        /*private void savePyramid(String name, List<Allocation> pyramid) {
        for(int i=0;i<pyramid.size();i++) {
            Allocation allocation = pyramid.get(i);
            saveAllocation(name + "_" + i + ".jpg", allocation);
        }
    }*/
        private const val blendNLevels = 4 // number of levels used for pyramid blending

        private val blendDimension: Int
            /** Bitmaps passed to blendPyramids must have width and height each a multiple of the value
             * returned by this function.
             */
            get() = (2.0.pow(blendNLevels.toDouble()) + 0.5).toInt()

        private fun computeDistancesBetweenMatches(
            matches: List<FeatureMatch>,
            stIndx: Int,
            ndIndx: Int,
            featureDescriptorRadius: Int,
            bitmaps: List<Bitmap>,
            pixels0: IntArray,
            pixels1: IntArray
        ) {
            val wid = 2 * featureDescriptorRadius + 1
            val wid2 = wid * wid
            for (indx in stIndx..<ndIndx) {
                val match = matches[indx]

                /*float distance = 0;
                for(int dy=-featureDescriptorRadius;dy<=featureDescriptorRadius;dy++) {
                    for(int dx=-featureDescriptorRadius;dx<=featureDescriptorRadius;dx++) {
                        int pixel0 = bitmaps.get(0).getPixel(point0.x + dx, point0.y + dy);
                        int pixel1 = bitmaps.get(1).getPixel(point1.x + dx, point1.y + dy);
                        //int value0 = (Color.red(pixel0) + Color.green(pixel0) + Color.blue(pixel0))/3;
                        //int value1 = (Color.red(pixel1) + Color.green(pixel1) + Color.blue(pixel1))/3;
                        int value0 = (int)(0.3*Color.red(pixel0) + 0.59*Color.green(pixel0) + 0.11*Color.blue(pixel0));
                        int value1 = (int)(0.3*Color.red(pixel1) + 0.59*Color.green(pixel1) + 0.11*Color.blue(pixel1));
                        int dist2 = value0*value0 + value1+value1;
                        distance += ((float)dist2)/65025.0f; // so distance for a given pixel is from 0 to 1
                    }
                }
                distance /= (float)wid2; // normalise from 0 to 1
                match.distance = distance;*/
                var fsum = 0f
                var gsum = 0f
                var f2sum = 0f
                var g2sum = 0f
                var fgsum = 0f

                // much faster to read via getPixels() rather than pixel by pixel
                //bitmaps.get(0).getPixels(pixels0, 0, wid, point0.x - featureDescriptorRadius, point0.y - featureDescriptorRadius, wid, wid);
                //bitmaps.get(1).getPixels(pixels1, 0, wid, point1.x - featureDescriptorRadius, point1.y - featureDescriptorRadius, wid, wid);
                //int pixelIdx = 0;
                var pixelIdx0 = match.index0 * wid2
                var pixelIdx1 = match.index1 * wid2

                for (dy in -featureDescriptorRadius..featureDescriptorRadius) {
                    for (dx in -featureDescriptorRadius..featureDescriptorRadius) {
                        //int pixel0 = bitmaps.get(0).getPixel(point0.x + dx, point0.y + dy);
                        //int pixel1 = bitmaps.get(1).getPixel(point1.x + dx, point1.y + dy);

                        //int pixel0 = pixels0[pixelIdx];
                        //int pixel1 = pixels1[pixelIdx];
                        //pixelIdx++;

                        //int value0 = (Color.red(pixel0) + Color.green(pixel0) + Color.blue(pixel0))/3;
                        //int value1 = (Color.red(pixel1) + Color.green(pixel1) + Color.blue(pixel1))/3;
                        //int value0 = (int)(0.3*Color.red(pixel0) + 0.59*Color.green(pixel0) + 0.11*Color.blue(pixel0));
                        //int value1 = (int)(0.3*Color.red(pixel1) + 0.59*Color.green(pixel1) + 0.11*Color.blue(pixel1));

                        val value0 = pixels0[pixelIdx0]
                        val value1 = pixels1[pixelIdx1]
                        pixelIdx0++
                        pixelIdx1++

                        fsum += value0.toFloat()
                        f2sum += (value0 * value0).toFloat()
                        gsum += value1.toFloat()
                        g2sum += (value1 * value1).toFloat()
                        fgsum += (value0 * value1).toFloat()
                    }
                }
                val fden = wid2 * f2sum - fsum * fsum
                val fRecip = if (fden == 0f) 0.0f else 1 / fden
                val gden = wid2 * g2sum - gsum * gsum
                val gRecip = if (gden == 0f) 0.0f else 1 / gden
                val fgCorr = wid2 * fgsum - fsum * gsum
                //if( MyDebug.LOG ) {
                //  Log.d(TAG, "match distance: ");
                //  Log.d(TAG, "    fgCorr: " + fgCorr);
                //  Log.d(TAG, "    fden: " + fden);
                //  Log.d(TAG, "    gden: " + gden);
                //  Log.d(TAG, "    fRecip: " + fRecip);
                //  Log.d(TAG, "    gRecip: " + gRecip);
                //}
                // negate, as we want it so that lower value means better match, and normalise to 0-1
                match.distance =
                    (1.0f - abs((fgCorr * fgCorr * fRecip * gRecip).toDouble())).toFloat()
            }
        }

        /*private static int nextPowerOf2(int value) {
        int power = 1;
        while( value > power )
            power *= 2;
        return power;
    }*/
        private fun nextMultiple(value: Int, multiple: Int): Int {
            var value = value
            val remainder = value % multiple
            if (remainder > 0) {
                value += multiple - remainder
            }
            return value
        }
    }
}
