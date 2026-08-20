package com.hightechif.openkamera.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Static methods for handling images.
 */
object ImageUtils {
    private const val TAG = "ImageUtils"

    private fun setBitmapOptionsSampleSize(options: BitmapFactory.Options, inSampleSize: Int) {
        if (MyDebug.LOG) Log.d(TAG, "setBitmapOptionsSampleSize: $inSampleSize")
        if (inSampleSize > 1) {
            // use inDensity for better quality, as inSampleSize uses nearest neighbour
            options.inDensity = inSampleSize
            options.inTargetDensity = 1
        }
    }

    /**
     * Loads a single jpeg as a Bitmaps.
     * @param mutable Whether the bitmap should be mutable. Note that when converting to bitmaps
     * for the image post-processing (auto-stabilise etc), in general we need the
     * bitmap to be mutable (for photostamp to work).
     */
    fun loadBitmap(jpegImage: ByteArray, mutable: Boolean, inSampleSize: Int): Bitmap? {
        if (MyDebug.LOG) {
            Log.d(TAG, "loadBitmap")
            Log.d(TAG, "mutable?: $mutable")
        }
        val options = BitmapFactory.Options()
        if (MyDebug.LOG) Log.d(TAG, "options.inMutable is: " + options.inMutable)
        options.inMutable = mutable
        setBitmapOptionsSampleSize(options, inSampleSize)
        val bitmap = BitmapFactory.decodeByteArray(jpegImage, 0, jpegImage.size, options)
        if (bitmap == null) {
            Log.e(TAG, "failed to decode bitmap")
        }
        return bitmap
    }

    /**
     * Helper class for loadBitmaps().
     */
    private class LoadBitmapThread(val options: BitmapFactory.Options, val jpeg: ByteArray) :
        Thread("LoadBitmapThread") {
        var bitmap: Bitmap? = null

        override fun run() {
            this.bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        }
    }

    /**
     * Converts the array of jpegs to Bitmaps. The bitmap with index mutable_id will be marked as mutable (or set to -1 to have no mutable bitmaps, or -2 to have all be mutable bitmaps).
     */
    fun loadBitmaps(
        jpegImages: List<ByteArray>,
        mutableId: Int,
        inSampleSize: Int
    ): List<Bitmap?>? {
        if (MyDebug.LOG) {
            Log.d(TAG, "loadBitmaps")
            Log.d(TAG, "mutable_id: $mutableId")
        }
        val mutableOptions = BitmapFactory.Options()
        mutableOptions.inMutable = true // bitmap that needs to be writable
        setBitmapOptionsSampleSize(mutableOptions, inSampleSize)

        val options = BitmapFactory.Options()
        options.inMutable = false // later bitmaps don't need to be writable
        setBitmapOptionsSampleSize(options, inSampleSize)

        val threads = Array(jpegImages.size) { i ->
            LoadBitmapThread(
                if (i == mutableId || mutableId == -2) mutableOptions else options,
                jpegImages[i]
            )
        }

        // start threads
        if (MyDebug.LOG) Log.d(TAG, "start threads")
        for (i in jpegImages.indices) {
            threads[i].start()
        }

        // wait for threads to complete
        var ok = true
        if (MyDebug.LOG) Log.d(TAG, "wait for threads to complete")
        try {
            for (i in jpegImages.indices) {
                threads[i].join()
            }
        } catch (e: InterruptedException) {
            MyDebug.logStackTrace(TAG, "threads interrupted", e)
            ok = false
        }
        if (MyDebug.LOG) Log.d(TAG, "threads completed")

        val bitmaps: MutableList<Bitmap> = ArrayList()
        for (i in jpegImages.indices) {
            if (!ok) break
            val bitmap = threads[i].bitmap
            if (bitmap == null) {
                Log.e(TAG, "failed to decode bitmap in thread: $i")
                ok = false
            } else {
                if (MyDebug.LOG) Log.d(TAG, "bitmap $i: $bitmap is mutable? ${bitmap.isMutable}")
            }
            if (bitmap != null) {
                bitmaps.add(bitmap)
            }
        }

        if (!ok) {
            if (MyDebug.LOG) Log.d(TAG, "cleanup from failure")
            for (i in jpegImages.indices) {
                threads[i].bitmap?.recycle()
                threads[i].bitmap = null
            }
            bitmaps.clear()
            System.gc()
            return null
        }

        return bitmaps
    }

    /**
     * Loads the bitmap from the supplied jpeg data, rotating if necessary according to the
     * supplied EXIF orientation tag.
     * @param data The jpeg data.
     * @param mutable Whether to create a mutable bitmap.
     * @return A bitmap representing the correctly rotated jpeg.
     */
    fun loadBitmapWithRotation(data: ByteArray, mutable: Boolean): Bitmap? {
        var bitmap = loadBitmap(data, mutable, 1)
        if (bitmap != null) {
            // rotate the bitmap if necessary for exif tags
            if (MyDebug.LOG) Log.d(TAG, "rotate bitmap for exif tags?")
            bitmap = rotateForExif(bitmap, data)
        }
        return bitmap
    }

    /**
     * Rotates the supplied bitmap according to the orientation tag stored in the exif data. If no
     * rotation is required, the input bitmap is returned. If rotation is required, the input
     * bitmap is recycled.
     * @param exif The Exif information to use.
     */
    private fun rotateForExif(bitmap: Bitmap, exif: ExifInterface): Bitmap {
        var mutableBitmap = bitmap
        val exifOrientationS =
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        if (MyDebug.LOG) Log.d(TAG, "    exif orientation string: $exifOrientationS")
        var needsTf = false
        var exifOrientation = 0
        // see http://jpegclub.org/exif_orientation.html
        // and http://stackoverflow.com/questions/20478765/how-to-get-the-correct-orientation-of-the-image-selected-from-the-default-image
        when (exifOrientationS) {
            ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL -> {
                // leave unchanged
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                needsTf = true
                exifOrientation = 180
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                needsTf = true
                exifOrientation = 90
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                needsTf = true
                exifOrientation = 270
            }

            else -> {
                // just leave unchanged for now
                if (MyDebug.LOG) Log.e(TAG, "    unsupported exif orientation: $exifOrientationS")
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "    exif orientation: $exifOrientation")

        if (needsTf) {
            if (MyDebug.LOG) Log.d(TAG, "    need to rotate bitmap due to exif orientation tag")
            val m = Matrix()
            m.setRotate(
                exifOrientation.toFloat(),
                mutableBitmap.width * 0.5f,
                mutableBitmap.height * 0.5f
            )
            val rotatedBitmap = Bitmap.createBitmap(
                mutableBitmap,
                0,
                0,
                mutableBitmap.width,
                mutableBitmap.height,
                m,
                true
            )
            if (rotatedBitmap != mutableBitmap) {
                mutableBitmap.recycle()
                mutableBitmap = rotatedBitmap
            }
        }
        return mutableBitmap
    }

    /**
     * Rotates the supplied bitmap according to the orientation tag stored in the exif data. If no
     * rotation is required, the input bitmap is returned. If rotation is required, the input
     * bitmap is recycled.
     * @param data Jpeg data containing the Exif information to use.
     */
    fun rotateForExif(bitmap: Bitmap?, data: ByteArray?): Bitmap? {
        if (MyDebug.LOG) Log.d(TAG, "rotateForExif")
        if (bitmap == null) {
            // support thumbnail being null - as this can happen according to Google Play crashes, see comment in saveSingleImageNow()
            return null
        }
        var mutableBitmap = bitmap
        var inputStream: InputStream? = null
        try {
            if (MyDebug.LOG) Log.d(TAG, "use data stream to read exif tags")
            inputStream = ByteArrayInputStream(data)
            val exif = ExifInterface(inputStream)
            mutableBitmap = rotateForExif(mutableBitmap, exif)
        } catch (e: IOException) {
            MyDebug.logStackTrace(TAG, "exif orientation ioexception", e)
        } catch (e: NoClassDefFoundError) {
            // have had Google Play crashes from new ExifInterface() for Galaxy Ace4 (vivalto3g), Galaxy S Duos3 (vivalto3gvn)
            MyDebug.logStackTrace(TAG, "exif orientation NoClassDefFoundError", e)
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close()
                } catch (e: IOException) {
                    MyDebug.logStackTrace(TAG, "failed to close inputStream", e)
                }
            }
        }
        return mutableBitmap
    }

    /**
     * Rotates the supplied bitmap according to the orientation tag stored in the exif data. If no
     * rotation is required, the input bitmap is returned. If rotation is required, the input
     * bitmap is recycled.
     * @param uri Uri containing the JPEG with Exif information to use.
     */
    @Throws(IOException::class)
    fun rotateForExif(context: Context, bitmap: Bitmap?, uri: Uri): Bitmap? {
        if (MyDebug.LOG) Log.d(TAG, "rotateForExif")
        if (bitmap == null) return null

        var mutableBitmap = bitmap
        var exif: ExifInterface? = null
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                exif = ExifInterface(inputStream)
            }
        } catch (_: Exception) {
            // do nothing
        } finally {
            inputStream?.close()
        }

        if (exif != null) {
            mutableBitmap = rotateForExif(mutableBitmap, exif)
        }
        return mutableBitmap
    }
}
