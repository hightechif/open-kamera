/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.test

import android.annotation.TargetApi
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraExtensionCharacteristics
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.preference.PreferenceManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.exifinterface.media.ExifInterface
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.R
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.ApplicationInterface
import com.hightechif.openkamera.processing.HDRProcessor
import com.hightechif.openkamera.processing.HDRProcessorException
import com.hightechif.openkamera.processing.PanoramaProcessorException
import com.hightechif.openkamera.storage.ImageSaver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Helper class for testing OpenKamera.
 */
object TestUtils {
    private const val TAG = "TestUtils"

    const val TEST_CAMERA2 = false

    private val images_base_path =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath

    fun setDefaultIntent(intent: Intent) {
        intent.putExtra("test_project", true)
    }

    /** Helper to wait until condition returns true or timeout expires.
     */
    fun waitUntil(description: String, timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                fail("Timed out waiting for $description after ${timeoutMs}ms")
            }
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    /** Code to call before running each test.
     */
    fun initTest(context: Context) {
        Log.d(TAG, "initTest: $TEST_CAMERA2")
        ImageSaver.testSmallQueueSize = false

        val settings: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = settings.edit()
        editor.clear()
        if (TEST_CAMERA2) {
            editor.putString(
                PreferenceKeys.CAMERA_API_PREFERENCE_KEY, "preference_camera_api_camera2"
            )
        }
        editor.apply()

        Log.d(TAG, "initTest: done")
    }

    fun isEmulator(): Boolean {
        return Build.MODEL.contains("Android SDK built for x86")
    }

    /** Converts a path to a Uri for com.android.providers.media.documents.
     */
    @Throws(FileNotFoundException::class)
    private fun getDocumentUri(filename: String): Uri {
        Log.d(TAG, "getDocumentUri: $filename")

        val treeUri =
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FtestOpenKamera")
        Log.d(TAG, "treeUri: $treeUri")
        if (!filename.startsWith(images_base_path)) {
            Log.e(TAG, "unknown base for: $filename")
            throw FileNotFoundException()
        }
        val stem = filename.substring(images_base_path.length)
        val stemUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM" + stem.replace(
                "/", "%2F"
            )
        )
        Log.d(TAG, "stem: $stem")
        Log.d(TAG, "stemUri: $stemUri")
        val docID = DocumentsContract.getTreeDocumentId(stemUri)
        Log.d(TAG, "docID: $docID")
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docID)
            ?: throw FileNotFoundException()
    }

    fun getBitmapFromFile(activity: MainActivity, filename: String): Bitmap? {
        return getBitmapFromFile(activity, filename, 1)
    }

    fun getBitmapFromFile(activity: MainActivity, filename: String, inSampleSize: Int): Bitmap? {
        return try {
            getBitmapFromFileCore(activity, filename, inSampleSize)
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "FileNotFoundException loading: $filename", e)
            fail("FileNotFoundException loading: $filename")
            null
        }
    }

    @Throws(FileNotFoundException::class)
    private fun getBitmapFromFileCore(
        activity: MainActivity, filename: String, inSampleSize: Int
    ): Bitmap {
        Log.d(TAG, "getBitmapFromFileCore: $filename")
        val options = BitmapFactory.Options()
        options.inMutable = true
        if (inSampleSize > 1) {
            options.inDensity = inSampleSize
            options.inTargetDensity = 1
        }

        var uri: Uri? = null
        var bitmap: Bitmap?

        if (MainActivity.useScopedStorage()) {
            uri = getDocumentUri(filename)
            Log.d(TAG, "uri: $uri")
            val inputStream: InputStream? = activity.contentResolver.openInputStream(uri)
            bitmap = BitmapFactory.decodeStream(inputStream, null, options)
            try {
                inputStream?.close()
            } catch (e: IOException) {
                Log.e(TAG, "failed to close input stream", e)
            }
        } else {
            bitmap = BitmapFactory.decodeFile(filename, options)
        }
        if (bitmap == null) throw FileNotFoundException()
        Log.d(TAG, "    done: $bitmap")

        var parcelFileDescriptor: ParcelFileDescriptor? = null
        try {
            val exif: ExifInterface?
            if (uri != null) {
                parcelFileDescriptor = activity.contentResolver.openFileDescriptor(uri, "r")
                if (parcelFileDescriptor != null) {
                    val fileDescriptor: FileDescriptor = parcelFileDescriptor.fileDescriptor
                    exif = ExifInterface(fileDescriptor)
                } else {
                    exif = null
                }
            } else {
                exif = ExifInterface(filename)
            }
            if (exif != null) {
                val exifOrientationS = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED
                )
                var needsTf = false
                var exifOrientation = 0
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
                        Log.e(TAG, "    unsupported exif orientation: $exifOrientationS")
                    }
                }
                Log.d(TAG, "    exif orientation: $exifOrientation")

                if (needsTf) {
                    Log.d(TAG, "    need to rotate bitmap due to exif orientation tag")
                    val m = Matrix()
                    m.setRotate(
                        exifOrientation.toFloat(), bitmap.width * 0.5f, bitmap.height * 0.5f
                    )
                    val rotatedBitmap =
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                    if (rotatedBitmap != bitmap) {
                        bitmap.recycle()
                        bitmap = rotatedBitmap
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "failed to load bitmap", e)
        } finally {
            if (parcelFileDescriptor != null) {
                try {
                    parcelFileDescriptor.close()
                } catch (e: IOException) {
                    Log.e(TAG, "failed to close parcelFileDescriptor", e)
                }
            }
        }
        return bitmap
    }

    private fun getUriFromName(activity: MainActivity, baseUri: Uri, name: String): Uri? {
        var uri: Uri? = null
        val projection = arrayOf(MediaStore.Images.ImageColumns._ID)
        var cursor: Cursor? = null
        try {
            cursor = activity.contentResolver.query(
                baseUri,
                projection,
                MediaStore.Images.ImageColumns.DISPLAY_NAME + " LIKE ?",
                arrayOf(name),
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                Log.d(TAG, "found: " + cursor.count)
                val id = cursor.getLong(0)
                uri = ContentUris.withAppendedId(baseUri, id)
                Log.d(TAG, "id: $id")
                Log.d(TAG, "uri: $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "exception trying to find uri from filename", e)
        } finally {
            cursor?.close()
        }
        return uri
    }

    fun saveBitmap(activity: MainActivity, bitmap: Bitmap, name: String) {
        try {
            saveBitmapCore(activity, bitmap, name)
        } catch (e: IOException) {
            Log.e(TAG, "IOException saving: $name", e)
            fail("IOException saving: $name")
        }
    }

    @Throws(IOException::class)
    private fun saveBitmapCore(activity: MainActivity, bitmap: Bitmap, name: String) {
        Log.d(TAG, "saveBitmapCore: $name")

        var file: File? = null
        var contentValues: ContentValues? = null
        var uri: Uri? = null
        var outputStream: OutputStream?
        if (MainActivity.useScopedStorage()) {
            val folder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                )
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val oldUri = getUriFromName(activity, folder, name)
            if (oldUri != null) {
                Log.d(TAG, "delete: $oldUri")
                activity.contentResolver.delete(oldUri, null, null)
            }

            contentValues = ContentValues()
            contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            val extension = name.substring(name.lastIndexOf("."))
            val mimeType = activity.storageUtils.getImageMimeType(extension)
            Log.d(TAG, "mime_type: $mimeType")
            contentValues.put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = Environment.DIRECTORY_DCIM + File.separator
                Log.d(TAG, "relative_path: $relativePath")
                contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            uri = activity.contentResolver.insert(folder, contentValues)
            Log.d(TAG, "saveUri: $uri")
            if (uri == null) {
                throw IOException()
            }
            outputStream = activity.contentResolver.openOutputStream(uri)
        } else {
            file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                    .toString() + File.separator + name
            )
            outputStream = FileOutputStream(file)
        }

        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream!!)
        outputStream.close()

        if (MainActivity.useScopedStorage()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues!!.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                activity.contentResolver.update(uri!!, contentValues, null, null)
            }
        } else {
            activity.storageUtils.broadcastFile(
                file = file!!,
                isNewPicture = true,
                isNewVideo = false,
                setLastScanned = true,
                hasnoexifdatetime = false,
                safUri = null
            )
        }
    }

    class HistogramDetails(val minValue: Int, val medianValue: Int, val maxValue: Int)

    fun checkHistogram(activity: MainActivity, bitmap: Bitmap): HistogramDetails {
        val histogram = activity.applicationInterface.hDRProcessor.computeHistogram(
            bitmap, HDRProcessor.HistogramType.HISTOGRAM_TYPE_INTENSITY
        )
        assertEquals(256, histogram.size)
        var total = 0
        for (i in histogram.indices) {
            Log.d(TAG, "histogram[" + i + "]: " + histogram[i])
            total += histogram[i]
        }
        Log.d(TAG, "total: $total")
        var started = false
        var minValue = -1
        var medianValue = -1
        var maxValue = -1
        var count = 0
        val middle = total / 2
        for (i in histogram.indices) {
            val value = histogram[i]
            if (!started) {
                started = value != 0
            }
            if (value != 0) {
                if (minValue == -1) minValue = i
                maxValue = i
                count += value
                if (count >= middle && medianValue == -1) medianValue = i
            }
        }
        Log.d(TAG, "min_value: $minValue")
        Log.d(TAG, "median_value: $medianValue")
        Log.d(TAG, "max_value: $maxValue")
        return HistogramDetails(minValue, medianValue, maxValue)
    }

    fun interface AvgCallback {
        fun onAvgStep(index: Int)
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    fun subTestAvg(
        activity: MainActivity,
        inputs: MutableList<String>,
        outputName: String,
        iso: Int,
        exposureTime: Long,
        zoomFactor: Float,
        cb: AvgCallback?
    ): HistogramDetails {
        Log.d(TAG, "subTestAvg")

        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "InterruptedException from sleep", e)
        }

        val nrBitmap: Bitmap
        try {
            val hdrProcessor = activity.applicationInterface.hDRProcessor
            val inSampleSize = hdrProcessor.getAvgSampleSize(iso, exposureTime)
            val bitmap0 = getBitmapFromFile(activity, inputs[0], inSampleSize)!!
            val bitmap1 = getBitmapFromFile(activity, inputs[1], inSampleSize)!!
            val width = bitmap0.width
            val height = bitmap0.height

            var avgFactor = 1.0f
            val times = ArrayList<Long>()
            var timeS = System.currentTimeMillis()
            val avgData = hdrProcessor.processAvg(
                bitmap0, bitmap1, avgFactor, iso, exposureTime, zoomFactor
            )
            times.add(System.currentTimeMillis() - timeS)
            cb?.onAvgStep(1)

            for (i in 2 until inputs.size) {
                Log.d(TAG, "processAvg for image: $i")

                val newBitmap = getBitmapFromFile(activity, inputs[i], inSampleSize)!!
                avgFactor = i.toFloat()
                timeS = System.currentTimeMillis()
                hdrProcessor.updateAvg(
                    avgData, width, height, newBitmap, avgFactor, iso, exposureTime, zoomFactor
                )
                times.add(System.currentTimeMillis() - timeS)
                cb?.onAvgStep(i)
            }

            timeS = System.currentTimeMillis()
            nrBitmap = hdrProcessor.avgBrighten(avgData, width, height, iso, exposureTime)
            avgData.destroy()
            times.add(System.currentTimeMillis() - timeS)

            var totalTime: Long = 0
            Log.d(TAG, "*** times are:")
            for (time in times) {
                totalTime += time
                Log.d(TAG, "    $time")
            }
            Log.d(TAG, "    total: $totalTime")
        } catch (e: HDRProcessorException) {
            Log.e(TAG, "HDRProcessorException", e)
            throw RuntimeException(e)
        }

        saveBitmap(activity, nrBitmap, outputName)
        val hdrHistogramDetails = checkHistogram(activity, nrBitmap)
        nrBitmap.recycle()
        System.gc()
        inputs.clear()

        try {
            Thread.sleep(500)
        } catch (e: InterruptedException) {
            Log.e(TAG, "InterruptedException from sleep", e)
        }

        return hdrHistogramDetails
    }

    fun subTestPanorama(
        activity: MainActivity,
        inputs: List<String>,
        outputName: String,
        gyroDebugInfoFilename: String?,
        panoramaPicsPerScreen: Float,
        cameraAngleX: Float,
        cameraAngleY: Float,
        gyroTolDegrees: Float
    ) {
        Log.d(TAG, "subTestPanorama")

        var first = true
        var scaleMatrix: Matrix? = null
        var bitmapWidth = 0
        var bitmapHeight = 0
        val bitmaps = ArrayList<Bitmap>()
        for (input in inputs) {
            var bitmap = getBitmapFromFile(activity, input)!!

            if (first) {
                bitmapWidth = bitmap.width
                bitmapHeight = bitmap.height
                Log.d(TAG, "bitmap_width: $bitmapWidth")
                Log.d(TAG, "bitmap_height: $bitmapHeight")

                val maxHeight = 2080
                if (bitmapHeight > maxHeight) {
                    val scale = maxHeight.toFloat() / bitmapHeight.toFloat()
                    Log.d(TAG, "scale: $scale")
                    scaleMatrix = Matrix()
                    scaleMatrix.postScale(scale, scale)
                }

                first = false
            }

            if (scaleMatrix != null) {
                val newBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmapWidth, bitmapHeight, scaleMatrix, true
                )
                bitmap.recycle()
                bitmap = newBitmap
            }

            bitmaps.add(bitmap)
        }

        bitmapWidth = bitmaps[0].width
        bitmapHeight = bitmaps[0].height
        Log.d(TAG, "bitmap_width is now: $bitmapWidth")
        Log.d(TAG, "bitmap_height is now: $bitmapHeight")

        val panorama: Bitmap
        try {
            val crop = true
            panorama = activity.applicationInterface.panoramaProcessor.panorama(
                bitmaps, panoramaPicsPerScreen, cameraAngleY, crop
            )
        } catch (e: PanoramaProcessorException) {
            Log.e(TAG, "panorama failed", e)
            fail()
            return
        }

        saveBitmap(activity, panorama, outputName)
        try {
            Thread.sleep(500)
        } catch (e: InterruptedException) {
            Log.e(TAG, "InterruptedException from sleep", e)
        }

        val blackFactor = 0.9f
        var nBlack = 0
        for (i in 0 until panorama.width) {
            val color = panorama.getPixel(i, 0)
            if (((color shr 16) and 0xff) == 0 && ((color shr 8) and 0xff) == 0 && (color and 0xff) == 0) {
                nBlack++
            }
        }
        if (nBlack >= panorama.width * blackFactor) {
            Log.e(TAG, "too many black pixels on top border: $nBlack")
            fail()
        }

        nBlack = 0
        for (i in 0 until panorama.width) {
            val color = panorama.getPixel(i, panorama.height - 1)
            if (((color shr 16) and 0xff) == 0 && ((color shr 8) and 0xff) == 0 && (color and 0xff) == 0) {
                nBlack++
            }
        }
        if (nBlack >= panorama.width * blackFactor) {
            Log.e(TAG, "too many black pixels on bottom border: $nBlack")
            fail()
        }

        nBlack = 0
        for (i in 0 until panorama.height) {
            val color = panorama.getPixel(0, i)
            if (((color shr 16) and 0xff) == 0 && ((color shr 8) and 0xff) == 0 && (color and 0xff) == 0) {
                nBlack++
            }
        }
        if (nBlack >= panorama.height * blackFactor) {
            Log.e(TAG, "too many black pixels on left border: $nBlack")
            fail()
        }

        nBlack = 0
        for (i in 0 until panorama.height) {
            val color = panorama.getPixel(panorama.width - 1, i)
            if (((color shr 16) and 0xff) == 0 && ((color shr 8) and 0xff) == 0 && (color and 0xff) == 0) {
                nBlack++
            }
        }
        if (nBlack >= panorama.height * blackFactor) {
            Log.e(TAG, "too many black pixels on right border: $nBlack")
            fail()
        }
    }

    fun waitForTakePhotoChecks(activity: MainActivity, timeS: Long) {
        val preview = activity.preview
        val switchCameraButton = activity.findViewById<View>(R.id.switch_camera)
        val switchMultiCameraButton = activity.findViewById<View>(R.id.switch_multi_camera)
        val switchVideoButton = activity.findViewById<View>(R.id.switch_video)
        val exposureButton = activity.findViewById<View>(R.id.exposure)
        val exposureLockButton = activity.findViewById<View>(R.id.exposure_lock)
        val audioControlButton = activity.findViewById<View>(R.id.audio_control)
        val popupButton = activity.findViewById<View>(R.id.popup)
        val trashButton = activity.findViewById<View>(R.id.trash)
        val shareButton = activity.findViewById<View>(R.id.share)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)
        val isFocusBracketing = activity.supportsFocusBracketing() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_focus_bracketing"
        val isPanorama = activity.supportsPanorama() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_panorama"

        if (!isFocusBracketing) {
            assertTrue(System.currentTimeMillis() - timeS < if (isPanorama) 50000 else 20000)
        }
        assertTrue(!preview.isTakingPhoto || switchCameraButton.visibility == View.GONE)
        assertTrue(!preview.isTakingPhoto || switchMultiCameraButton.visibility == View.GONE)
        assertTrue(!preview.isTakingPhoto || switchVideoButton.visibility == View.GONE)
        assertTrue(!preview.isTakingPhoto || exposureButton.visibility == View.GONE)
        assertTrue(!preview.isTakingPhoto || exposureLockButton.visibility == View.GONE)
        assertTrue(!preview.isTakingPhoto || audioControlButton.visibility == View.GONE)
        assertTrue(!preview.isTakingPhoto || popupButton.visibility == View.GONE)
        assertTrue(!preview.isTakingPhoto || trashButton.visibility == View.GONE)
        assertTrue(!preview.isTakingPhoto || shareButton.visibility == View.GONE)
    }

    private fun checkFocusInitial(
        activity: MainActivity, focusValue: String, focusValueUi: String?
    ) {
        val newFocusValueUi = activity.preview.currentFocusValue
        assertTrue(newFocusValueUi == focusValueUi || (newFocusValueUi != null && newFocusValueUi == focusValueUi))
        assertEquals(activity.preview.cameraController?.focusValue, focusValue)
    }

    class SubTestTakePhotoInfo {
        var hasThumbnailAnim: Boolean = false
        var isHdr: Boolean = false
        var isNr: Boolean = false
        var isExpo: Boolean = false
        var exposureVisibility: Int = View.VISIBLE
        var exposureLockVisibility: Int = View.VISIBLE
        var focusValue: String = ""
        var focusValueUi: String? = null
        var canAutoFocus: Boolean = false
        var manualCanAutoFocus: Boolean = false
        var canFocusArea: Boolean = false
    }

    fun getSubTestTakePhotoInfo(
        activity: MainActivity,
        immersiveMode: Boolean,
        singleTapPhoto: Boolean,
        doubleTapPhoto: Boolean
    ): SubTestTakePhotoInfo {
        assertTrue(activity.preview.isPreviewStarted)
        assertFalse(activity.applicationInterface.imageSaver.testQueueBlocked)

        val info = SubTestTakePhotoInfo()
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)

        info.hasThumbnailAnim =
            sharedPreferences.getBoolean(PreferenceKeys.THUMBNAIL_ANIMATION_PREFERENCE_KEY, true)
        info.isHdr = activity.supportsHDR() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_hdr"
        info.isNr = activity.supportsNoiseReduction() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_noise_reduction"
        info.isExpo = activity.supportsExpoBracketing() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_expo_bracketing"

        val hasAudioControlButton = sharedPreferences.getString(
            PreferenceKeys.AUDIO_CONTROL_PREFERENCE_KEY, "none"
        ) != "none"

        val switchCameraButton = activity.findViewById<View>(R.id.switch_camera)
        val switchMultiCameraButton = activity.findViewById<View>(R.id.switch_multi_camera)
        val switchVideoButton = activity.findViewById<View>(R.id.switch_video)
        val exposureButton = activity.findViewById<View>(R.id.exposure)
        val exposureLockButton = activity.findViewById<View>(R.id.exposure_lock)
        val audioControlButton = activity.findViewById<View>(R.id.audio_control)
        val popupButton = activity.findViewById<View>(R.id.popup)
        val trashButton = activity.findViewById<View>(R.id.trash)
        val shareButton = activity.findViewById<View>(R.id.share)

        assertEquals(
            switchCameraButton.visibility,
            if (immersiveMode) View.GONE else (if (activity.preview.cameraControllerManager.numberOfCameras > 1) View.VISIBLE else View.GONE)
        )
        assertEquals(
            switchMultiCameraButton.visibility,
            if (immersiveMode) View.GONE else (if (activity.showSwitchMultiCamIcon()) View.VISIBLE else View.GONE)
        )
        assertEquals(switchVideoButton.visibility, if (immersiveMode) View.GONE else View.VISIBLE)
        info.exposureVisibility = exposureButton.visibility
        info.exposureLockVisibility = exposureLockButton.visibility
        assertEquals(
            audioControlButton.visibility,
            if (hasAudioControlButton && !immersiveMode) View.VISIBLE else View.GONE
        )
        assertEquals(popupButton.visibility, if (immersiveMode) View.GONE else View.VISIBLE)
        assertEquals(trashButton.visibility, View.GONE)
        assertEquals(shareButton.visibility, View.GONE)

        info.focusValue = activity.preview.cameraController?.focusValue ?: ""
        info.focusValueUi = activity.preview.currentFocusValue
        info.canAutoFocus = false
        info.manualCanAutoFocus = false
        info.canFocusArea = false
        if (info.focusValue == "focus_mode_auto" || info.focusValue == "focus_mode_macro") {
            info.canAutoFocus = true
            info.manualCanAutoFocus = true
        } else if (info.focusValue == "focus_mode_continuous_picture" && !singleTapPhoto) {
            info.manualCanAutoFocus = true
        }

        if (activity.preview.maxNumFocusAreas != 0 && (info.focusValue == "focus_mode_auto" || info.focusValue == "focus_mode_macro" || info.focusValue == "focus_mode_continuous_picture" || info.focusValue == "focus_mode_continuous_video" || info.focusValue == "focus_mode_manual2")) {
            info.canFocusArea = true
        }

        checkFocusInitial(activity, info.focusValue, info.focusValueUi)
        return info
    }

    fun touchToFocusChecks(
        activity: MainActivity,
        singleTapPhoto: Boolean,
        doubleTapPhoto: Boolean,
        manualCanAutoFocus: Boolean,
        canFocusArea: Boolean,
        focusValue: String,
        focusValueUi: String?,
        savedCount: Int
    ) {
        val preview = activity.preview
        assertEquals(
            if (manualCanAutoFocus) savedCount + 1 else savedCount, preview.countCameraAutoFocus
        )
        if (singleTapPhoto) {
            assertFalse(preview.hasFocusArea())
            assertNull(preview.cameraController?.focusAreas)
            assertNull(preview.cameraController?.meteringAreas)
        } else if (canFocusArea) {
            assertTrue(preview.hasFocusArea())
            assertNotNull(preview.cameraController?.focusAreas)
            assertEquals(1, preview.cameraController?.focusAreas?.size ?: 0)
            if (preview.cameraController?.supportsMetering() == true) {
                assertNotNull(preview.cameraController?.meteringAreas)
                assertEquals(1, preview.cameraController?.meteringAreas?.size ?: 0)
            } else {
                assertNull(preview.cameraController?.meteringAreas)
            }
        } else {
            assertFalse(preview.hasFocusArea())
            assertNull(preview.cameraController?.focusAreas)
            if (preview.cameraController?.supportsMetering() == true) {
                assertNotNull(preview.cameraController?.meteringAreas)
                assertEquals(1, preview.cameraController?.meteringAreas?.size ?: 0)
            } else {
                assertNull(preview.cameraController?.meteringAreas)
            }
        }
        val newFocusValueUi = preview.currentFocusValue
        assertTrue(newFocusValueUi == focusValueUi || (newFocusValueUi != null && newFocusValueUi == focusValueUi))
        if (focusValue == "focus_mode_continuous_picture" && !singleTapPhoto && preview.supportsFocus() && preview.supportedFocusValues?.contains(
                "focus_mode_auto"
            ) == true
        ) {
            assertEquals("focus_mode_auto", preview.cameraController?.focusValue)
        } else {
            assertEquals(preview.cameraController?.focusValue, focusValue)
        }
    }

    fun postTakePhotoChecks(
        activity: MainActivity,
        immersiveMode: Boolean,
        exposureVisibility: Int,
        exposureLockVisibility: Int
    ) {
        val preview = activity.preview
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)
        val hasAudioControlButton = sharedPreferences.getString(
            PreferenceKeys.AUDIO_CONTROL_PREFERENCE_KEY, "none"
        ) != "none"

        val switchCameraButton = activity.findViewById<View>(R.id.switch_camera)
        val switchMultiCameraButton = activity.findViewById<View>(R.id.switch_multi_camera)
        val switchVideoButton = activity.findViewById<View>(R.id.switch_video)
        val exposureButton = activity.findViewById<View>(R.id.exposure)
        val exposureLockButton = activity.findViewById<View>(R.id.exposure_lock)
        val audioControlButton = activity.findViewById<View>(R.id.audio_control)
        val popupButton = activity.findViewById<View>(R.id.popup)
        val trashButton = activity.findViewById<View>(R.id.trash)
        val shareButton = activity.findViewById<View>(R.id.share)

        val pausePreview =
            sharedPreferences.getBoolean(PreferenceKeys.PAUSE_PREVIEW_PREFERENCE_KEY, false)
        if (pausePreview) {
            assertFalse(preview.isPreviewStarted)
            assertEquals(switchCameraButton.visibility, View.GONE)
            assertEquals(switchMultiCameraButton.visibility, View.GONE)
            assertEquals(switchVideoButton.visibility, View.GONE)
            assertEquals(exposureButton.visibility, View.GONE)
            assertEquals(exposureLockButton.visibility, View.GONE)
            assertEquals(audioControlButton.visibility, View.GONE)
            assertEquals(popupButton.visibility, View.GONE)
            assertEquals(trashButton.visibility, View.VISIBLE)
            assertEquals(shareButton.visibility, View.VISIBLE)
        } else {
            assertTrue(preview.isPreviewStarted)
            assertEquals(
                switchCameraButton.visibility,
                if (preview.cameraControllerManager.numberOfCameras > 1) View.VISIBLE else View.GONE
            )
            assertEquals(
                switchMultiCameraButton.visibility,
                if (activity.showSwitchMultiCamIcon()) View.VISIBLE else View.GONE
            )
            assertEquals(switchVideoButton.visibility, View.VISIBLE)
            if (!immersiveMode) {
                assertEquals(exposureButton.visibility, exposureVisibility)
                assertEquals(exposureLockButton.visibility, exposureLockVisibility)
            }
            assertEquals(
                audioControlButton.visibility,
                if (hasAudioControlButton) View.VISIBLE else View.GONE
            )
            assertEquals(popupButton.visibility, View.VISIBLE)
            assertEquals(trashButton.visibility, View.GONE)
            assertEquals(shareButton.visibility, View.GONE)
        }
    }

    fun checkFocusAfterTakePhoto(
        activity: MainActivity, focusValue: String, focusValueUi: String?
    ) {
        val newFocusValueUi = activity.preview.currentFocusValue
        Log.d(TAG, "focus_value_ui: $focusValueUi")
        Log.d(TAG, "new new_focus_value_ui: $newFocusValueUi")
        assertTrue(newFocusValueUi == focusValueUi || (newFocusValueUi != null && newFocusValueUi == focusValueUi))
        val newFocusValue = activity.preview.cameraController?.focusValue
        Log.d(TAG, "focus_value: $focusValue")
        Log.d(TAG, "new focus_value: $newFocusValue")
        if (newFocusValueUi != null && newFocusValueUi == "focus_mode_continuous_picture" && focusValue == "focus_mode_auto" && newFocusValue == "focus_mode_continuous_picture") {
            // this is fine
        } else {
            assertEquals(newFocusValue, focusValue)
        }
    }

    private fun getExpNNewFiles(activity: MainActivity, isRaw: Boolean): Int {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)
        val hdrSaveExpo =
            sharedPreferences.getBoolean(PreferenceKeys.HDR_SAVE_EXPO_PREFERENCE_KEY, false)
        val isHdr = activity.supportsHDR() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_hdr"
        val isExpo = activity.supportsExpoBracketing() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_expo_bracketing"
        val isFocusBracketing = activity.supportsFocusBracketing() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_focus_bracketing"
        val isFastBurst = activity.supportsFastBurst() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_fast_burst"
        val nExpoImagesS = sharedPreferences.getString(
            PreferenceKeys.EXPO_BRACKETING_N_IMAGES_PREFERENCE_KEY, "3"
        )!!
        val nExpoImages = nExpoImagesS.toInt()
        val nFocusBracketingImagesS = sharedPreferences.getString(
            PreferenceKeys.FOCUS_BRACKETING_N_IMAGES_PREFERENCE_KEY, "3"
        )!!
        val nFocusBracketingImages = nFocusBracketingImagesS.toInt()
        val nFastBurstImagesS =
            sharedPreferences.getString(PreferenceKeys.FAST_BURST_N_IMAGES_PREFERENCE_KEY, "5")!!
        val nFastBurstImages = nFastBurstImagesS.toInt()
        val isPreshot =
            activity.applicationInterface.getPreShotsPref(activity.applicationInterface.photoMode)

        val isRawPref =
            activity.applicationInterface.getRawPref() != ApplicationInterface.RawPref.RAWPREF_JPEG_ONLY
        val actualIsRaw = (isRaw || isRawPref) && activity.preview.supportsRaw()
        var expNNewFiles: Int
        if (isHdr && hdrSaveExpo) {
            expNNewFiles = 4
            if (actualIsRaw && !activity.applicationInterface.isRawOnly) {
                expNNewFiles += 3
            }
        } else if (isExpo) {
            expNNewFiles = nExpoImages
            if (actualIsRaw && !activity.applicationInterface.isRawOnly) {
                expNNewFiles *= 2
            }
        } else if (isFocusBracketing) {
            expNNewFiles = nFocusBracketingImages
            if (actualIsRaw && !activity.applicationInterface.isRawOnly) {
                expNNewFiles *= 2
            }
        } else if (isFastBurst) {
            expNNewFiles = nFastBurstImages
        } else {
            expNNewFiles = 1
            if (actualIsRaw && !activity.applicationInterface.isRawOnly) {
                expNNewFiles *= 2
            }
        }

        if (isPreshot) expNNewFiles++

        Log.d(TAG, "expNNewFiles: $expNNewFiles")
        return expNNewFiles
    }

    private enum class UriType {
        MEDIASTORE_IMAGES, MEDIASTORE_VIDEOS, STORAGE_ACCESS_FRAMEWORK
    }

    private fun mediaFilesInSaveFolder(
        activity: MainActivity, baseUri: Uri, bucketId: String?, uriType: UriType
    ): List<String> {
        val files = ArrayList<String>()
        val columnNameC = 0

        val projection = when (uriType) {
            UriType.MEDIASTORE_IMAGES -> arrayOf(MediaStore.Images.ImageColumns.DISPLAY_NAME)
            UriType.MEDIASTORE_VIDEOS -> arrayOf(MediaStore.Video.VideoColumns.DISPLAY_NAME)
            UriType.STORAGE_ACCESS_FRAMEWORK -> arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        }

        val selection = when (uriType) {
            UriType.MEDIASTORE_IMAGES -> MediaStore.Images.ImageColumns.BUCKET_ID + " = " + bucketId
            UriType.MEDIASTORE_VIDEOS -> MediaStore.Video.VideoColumns.BUCKET_ID + " = " + bucketId
            UriType.STORAGE_ACCESS_FRAMEWORK -> null
        }
        Log.d(TAG, "selection: $selection")

        val cursor = activity.contentResolver.query(baseUri, projection, selection, null, null)
        if (cursor != null && cursor.moveToFirst()) {
            Log.d(TAG, "found: " + cursor.count)
            do {
                val name = cursor.getString(columnNameC)
                files.add(name)
            } while (cursor.moveToNext())
        }
        cursor?.close()

        return files
    }

    fun filesInSaveFolder(activity: MainActivity): Array<String>? {
        Log.d(TAG, "filesInSaveFolder")
        if (MainActivity.useScopedStorage()) {
            val files = ArrayList<String>()
            if (activity.storageUtils.isUsingSAF) {
                val treeUri = activity.storageUtils.treeUriSAF!!
                val baseUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                files.addAll(
                    mediaFilesInSaveFolder(
                        activity, baseUri, null, UriType.STORAGE_ACCESS_FRAMEWORK
                    )
                )
            } else {
                val saveFolder = activity.storageUtils.imageFolderPath
                val bucketId = saveFolder?.lowercase(Locale.getDefault())?.hashCode()?.toString()
                files.addAll(
                    mediaFilesInSaveFolder(
                        activity,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        bucketId,
                        UriType.MEDIASTORE_IMAGES
                    )
                )
                files.addAll(
                    mediaFilesInSaveFolder(
                        activity,
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        bucketId,
                        UriType.MEDIASTORE_VIDEOS
                    )
                )
            }

            return if (files.isEmpty()) null else files.toTypedArray()
        } else {
            val folder = activity.imageFolder
            val files = folder.listFiles() ?: return null
            val filenames = Array(files.size) { "" }
            for (i in files.indices) {
                filenames[i] = files[i].name
            }
            return filenames
        }
    }

    @Throws(InterruptedException::class)
    fun checkFilesAfterTakePhoto(
        activity: MainActivity,
        isRaw: Boolean,
        testWaitCaptureResult: Boolean,
        files: Array<String>?,
        isInstrumentedTest: Boolean
    ) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity)
        val isDro = activity.supportsDRO() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_dro"
        val isHdr = activity.supportsHDR() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_hdr"
        val isNr = activity.supportsNoiseReduction() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_noise_reduction"
        val isExpo = activity.supportsExpoBracketing() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_expo_bracketing"
        val isFocusBracketing = activity.supportsFocusBracketing() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_focus_bracketing"
        val isFastBurst = activity.supportsFastBurst() && sharedPreferences.getString(
            PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
        ) == "preference_photo_mode_fast_burst"
        val isXNight =
            activity.supportsCameraExtension(CameraExtensionCharacteristics.EXTENSION_NIGHT) && sharedPreferences.getString(
                PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY, "preference_photo_mode_std"
            ) == "preference_photo_mode_x_night"
        val nExpoImagesS = sharedPreferences.getString(
            PreferenceKeys.EXPO_BRACKETING_N_IMAGES_PREFERENCE_KEY, "3"
        )!!
        val nExpoImages = nExpoImagesS.toInt()
        val nFocusBracketingImagesS = sharedPreferences.getString(
            PreferenceKeys.FOCUS_BRACKETING_N_IMAGES_PREFERENCE_KEY, "3"
        )!!
        val nFocusBracketingImages = nFocusBracketingImagesS.toInt()
        val nFastBurstImagesS =
            sharedPreferences.getString(PreferenceKeys.FAST_BURST_N_IMAGES_PREFERENCE_KEY, "5")!!
        val nFastBurstImages = nFastBurstImagesS.toInt()

        val date = Date()
        var suffix = ""
        var maxTimeS = 3
        if (isDro) {
            suffix = "_DRO"
        } else if (isHdr) {
            suffix = "_HDR"
        } else if (isNr) {
            suffix = "_NR"
            maxTimeS += 10
        } else if (isExpo) {
            suffix = "_" + (nExpoImages - 1)
        } else if (isFocusBracketing) {
            suffix = "_" + (nFocusBracketingImages - 1)
            maxTimeS = 60
        } else if (isFastBurst) {
            suffix = "_" + (nFastBurstImages - 1)
            maxTimeS = 6
        } else if (isXNight) {
            suffix = "_Night"
        }

        if (isRaw) {
            maxTimeS += 6
        }

        val pausePreview =
            sharedPreferences.getBoolean(PreferenceKeys.PAUSE_PREVIEW_PREFERENCE_KEY, false)
        if (pausePreview) {
            maxTimeS += 3
        }

        val nFiles = files?.size ?: 0
        val isRawPref =
            activity.applicationInterface.getRawPref() != ApplicationInterface.RawPref.RAWPREF_JPEG_ONLY
        val actualIsRaw = (isRaw || isRawPref) && activity.preview.supportsRaw()
        val expNNewFiles = getExpNNewFiles(activity, actualIsRaw)
        var files2 = filesInSaveFolder(activity)
        var nNewFiles = (files2?.size ?: 0) - nFiles
        val waitStartTime = System.currentTimeMillis()
        while (nNewFiles < expNNewFiles && System.currentTimeMillis() - waitStartTime < 5000) {
            Thread.sleep(200)
            files2 = filesInSaveFolder(activity)
            nNewFiles = (files2?.size ?: 0) - nFiles
        }
        Log.d(TAG, "n_new_files: $nNewFiles, exp: $expNNewFiles")
        assertEquals(expNNewFiles, nNewFiles)

        if (!activity.applicationInterface.isRawOnly) {
            val savedImageFilename: String? =
                if (MainActivity.useScopedStorage() || activity.storageUtils.isUsingSAF) {
                    assertNotNull(activity.testLastSavedImageuri)
                    activity.storageUtils.getFileName(activity.testLastSavedImageuri!!)
                } else {
                    assertNotNull(activity.testLastSavedImage)
                    val savedImageFile = File(activity.testLastSavedImage!!)
                    savedImageFile.name
                }
            var matched = false
            val possibleSuffixes = if (isHdr) listOf("_HDR", "_DRO") else listOf(suffix)
            val searchWindow = maxTimeS.coerceAtLeast(60)
            for (candSuffix in possibleSuffixes) {
                if (matched) break
                for (i in -2..searchWindow) {
                    if (matched) break
                    val testDate = Date(date.time - 1000L * i)
                    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(testDate)
                    val expectedFilename = "IMG_$timeStamp$candSuffix.jpg"
                    if (expectedFilename == savedImageFilename) matched = true
                }
                if (!matched && savedImageFilename != null) {
                    val expectedPattern = Regex(
                        "^IMG_\\d{8}_\\d{6}" + Regex.escape(candSuffix) + ".*\\.(jpg|jpeg)$",
                        RegexOption.IGNORE_CASE
                    )
                    matched = expectedPattern.matches(savedImageFilename)
                }
            }
            assertTrue(
                "Expected filename matching pattern for saved name: $savedImageFilename", matched
            )
        }
    }

    @Throws(IOException::class)
    fun testExif(
        activity: MainActivity,
        file: String?,
        uri: Uri?,
        expectDeviceTags: Boolean,
        expectDatetime: Boolean,
        expectGps: Boolean
    ) {
        var inputStream: InputStream? = null
        val exif: ExifInterface
        if (file != null) {
            assertNull(uri)
            exif = ExifInterface(file)
        } else {
            assertNotNull(uri)
            inputStream = activity.contentResolver.openInputStream(uri!!)
            exif = ExifInterface(inputStream!!)
        }

        assertNotNull(exif.getAttribute(ExifInterface.TAG_ORIENTATION))
        if (!(isEmulator() && Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1)) {
            if (expectDeviceTags) {
                assertNotNull(exif.getAttribute(ExifInterface.TAG_MAKE))
                assertNotNull(exif.getAttribute(ExifInterface.TAG_MODEL))
            } else {
                assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
                assertNull(exif.getAttribute(ExifInterface.TAG_MODEL))
            }

            if (expectDatetime) {
                assertNotNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
                assertNotNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertNotNull(exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
            } else {
                assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
            }

            if (expectGps) {
                assertNotNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                assertNotNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
            }
        }

        inputStream?.close()
    }

    fun preTakeVideoChecks(activity: MainActivity, immersiveMode: Boolean) {
        val preview = activity.preview
        assertTrue(preview.isPreviewStarted)

        val takePhotoButton = activity.findViewById<View>(R.id.take_photo)
        val pauseVideoButton = activity.findViewById<View>(R.id.pause_video)
        val switchVideoButton = activity.findViewById<View>(R.id.switch_video)
        val switchCameraButton = activity.findViewById<View>(R.id.switch_camera)
        val popupButton = activity.findViewById<View>(R.id.popup)

        if (preview.isVideo) {
            assertEquals(
                takePhotoButton.contentDescription,
                activity.resources.getString(R.string.start_video)
            )
            assertEquals(
                switchVideoButton.contentDescription,
                activity.resources.getString(R.string.switch_to_photo)
            )
        } else {
            assertEquals(
                takePhotoButton.contentDescription,
                activity.resources.getString(R.string.take_photo)
            )
            assertEquals(
                switchVideoButton.contentDescription,
                activity.resources.getString(R.string.switch_to_video)
            )
        }
        assertEquals(pauseVideoButton.visibility, View.GONE)
        assertEquals(
            switchCameraButton.visibility,
            if (immersiveMode) View.GONE else (if (preview.cameraControllerManager.numberOfCameras > 1) View.VISIBLE else View.GONE)
        )
        assertEquals(switchVideoButton.visibility, if (immersiveMode) View.GONE else View.VISIBLE)
        assertEquals(popupButton.visibility, if (immersiveMode) View.GONE else View.VISIBLE)
    }

    fun takeVideoRecordingChecks(
        activity: MainActivity,
        immersiveMode: Boolean,
        exposureVisibility: Int,
        exposureLockVisibility: Int
    ) {
        val preview = activity.preview
        val takePhotoButton = activity.findViewById<View>(R.id.take_photo)
        val pauseVideoButton = activity.findViewById<View>(R.id.pause_video)
        val switchVideoButton = activity.findViewById<View>(R.id.switch_video)
        val switchCameraButton = activity.findViewById<View>(R.id.switch_camera)
        val exposureButton = activity.findViewById<View>(R.id.exposure)
        val exposureLockButton = activity.findViewById<View>(R.id.exposure_lock)
        val popupButton = activity.findViewById<View>(R.id.popup)

        assertEquals(
            takePhotoButton.contentDescription, activity.resources.getString(R.string.stop_video)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            assertEquals(pauseVideoButton.visibility, View.VISIBLE)
        }
        assertEquals(switchCameraButton.visibility, View.GONE)
        assertEquals(switchVideoButton.visibility, View.GONE)
        assertEquals(
            popupButton.visibility,
            if (!immersiveMode && preview.supportsFlash()) View.VISIBLE else View.GONE
        )
        assertEquals(exposureButton.visibility, exposureVisibility)
        assertEquals(exposureLockButton.visibility, exposureLockVisibility)
    }

    fun checkFilesAfterTakeVideo(
        activity: MainActivity,
        allowFailure: Boolean,
        hasCb: Boolean,
        timeMs: Long,
        nNonVideoFiles: Int,
        failedToStart: Boolean,
        expNNewFiles: Int,
        nNewFiles: Int
    ) {
        if (!hasCb) {
            if (timeMs <= 500) {
                assertTrue(nNewFiles == 0 || nNewFiles == 1)
            } else if (failedToStart) {
                assertEquals(0, nNewFiles)
            } else {
                assertEquals(nNonVideoFiles + 1, nNewFiles)
            }
        } else {
            if (expNNewFiles >= 0) {
                assertEquals(expNNewFiles, nNewFiles)
            }
        }

        if (!allowFailure) {
            assertEquals(
                nNewFiles - nNonVideoFiles, activity.applicationInterface.testNVideosScanned
            )
        }
    }

    fun postTakeVideoChecks(
        activity: MainActivity,
        immersiveMode: Boolean,
        maxFilesize: Boolean,
        exposureVisibility: Int,
        exposureLockVisibility: Int
    ) {
        val preview = activity.preview
        assertTrue(preview.isPreviewStarted)

        val takePhotoButton = activity.findViewById<View>(R.id.take_photo)
        val pauseVideoButton = activity.findViewById<View>(R.id.pause_video)
        val switchVideoButton = activity.findViewById<View>(R.id.switch_video)
        val switchCameraButton = activity.findViewById<View>(R.id.switch_camera)
        val exposureButton = activity.findViewById<View>(R.id.exposure)
        val exposureLockButton = activity.findViewById<View>(R.id.exposure_lock)
        val popupButton = activity.findViewById<View>(R.id.popup)

        if (!maxFilesize) {
            assertEquals(
                switchCameraButton.visibility,
                if (immersiveMode) View.GONE else (if (preview.cameraControllerManager.numberOfCameras > 1) View.VISIBLE else View.GONE)
            )
        }
        assertEquals(switchVideoButton.visibility, if (immersiveMode) View.GONE else View.VISIBLE)
        assertEquals(exposureButton.visibility, exposureVisibility)
        assertEquals(exposureLockButton.visibility, exposureLockVisibility)
        assertEquals(popupButton.visibility, if (immersiveMode) View.GONE else View.VISIBLE)

        assertFalse(preview.isVideoRecording)
        assertEquals(
            takePhotoButton.contentDescription, activity.resources.getString(R.string.start_video)
        )
        assertEquals(pauseVideoButton.visibility, View.GONE)
    }
}
