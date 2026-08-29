/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Xml
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.R
import com.hightechif.openkamera.cameracontroller.RawImage
import com.hightechif.openkamera.processing.HDRProcessor
import com.hightechif.openkamera.processing.HDRProcessorException
import com.hightechif.openkamera.processing.PanoramaProcessor
import com.hightechif.openkamera.processing.PanoramaProcessorException
import com.hightechif.openkamera.sensors.GyroSensor
import com.hightechif.openkamera.utils.ExifHandler
import com.hightechif.openkamera.utils.ImageUtils
import com.hightechif.openkamera.utils.MyDebug
import com.hightechif.openkamera.utils.PostProcessing
import com.hightechif.openkamera.utils.Preshots
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import java.io.Writer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/** Handles the saving (and any required processing) of photos.
 */
class ImageSaver internal constructor(val mainActivity: MainActivity) : Thread("ImageSaver") {

    private val hdrProcessor: HDRProcessor
    private val panoramaProcessor: PanoramaProcessor
    private val postProcessing: PostProcessing

    @get:Synchronized
    var nImagesToSave: Int = 0
        private set

    @get:Synchronized
    var nRealImagesToSave: Int = 0
        private set

    val queueSize: Int
    private val queue: BlockingQueue<Request>

    private var appIsPaused = true

    @Volatile
    var testSlowSaving: Boolean = false

    @Volatile
    var testQueueBlocked: Boolean = false

    data class Request(
        val type: Type,
        val processType: ProcessType,
        val forceSuffix: Boolean,
        val suffixOffset: Int,
        val saveBase: SaveBase,
        val jpegImages: MutableList<ByteArray>,
        val preshotBitmaps: MutableList<Bitmap?>?,
        val rawImage: RawImage?,
        val imageCaptureIntent: Boolean,
        val imageCaptureIntentUri: Uri?,
        val usingCamera2: Boolean,
        val usingCameraExtensions: Boolean,
        var imageFormat: ImageFormat,
        var imageQuality: Int,
        var doAutoStabilise: Boolean,
        val levelAngle: Double,
        val gyroRotationMatrix: MutableList<FloatArray?>?,
        val isFrontFacing: Boolean,
        var mirror: Boolean,
        val currentDate: Date?,
        val preferenceHdrTonemappingAlgorithm: HDRProcessor.TonemappingAlgorithm,
        val preferenceHdrContrastEnhancement: String?,
        val iso: Int,
        val exposureTime: Long,
        val zoomFactor: Float,
        var preferenceStamp: String?,
        var preferenceTextstamp: String?,
        val fontSize: Int,
        val color: Int,
        val prefStyle: String?,
        val preferenceStampDateformat: String?,
        val preferenceStampTimeformat: String?,
        val preferenceStampGpsformat: String?,
        val preferenceUnitsDistance: String?,
        val panoramaCrop: Boolean,
        val removeDeviceExif: RemoveDeviceExif,
        val storeLocation: Boolean,
        val location: Location?,
        val storeGeoDirection: Boolean,
        val geoDirection: Double,
        val pitchAngle: Double,
        val storeYpr: Boolean,
        val customTagArtist: String?,
        val customTagCopyright: String?,
        val sampleFactor: Int
    ) {
        enum class Type {
            JPEG, RAW, DUMMY, ON_DESTROY
        }

        enum class ProcessType {
            NORMAL, HDR, AVERAGE, PANORAMA, X_NIGHT
        }

        enum class SaveBase {
            SAVEBASE_NONE, SAVEBASE_FIRST, SAVEBASE_ALL, SAVEBASE_ALL_PLUS_DEBUG
        }

        enum class ImageFormat {
            STD, WEBP, PNG
        }

        var panoramaDirLeftToRight: Boolean = false
        var cameraViewAngleX: Float = 0f
        var cameraViewAngleY: Float = 0f

        enum class RemoveDeviceExif {
            OFF, ON, KEEP_DATETIME
        }
    }

    init {
        if (MyDebug.LOG) Log.d(TAG, "ImageSaver")

        val activityManager =
            mainActivity.getSystemService(Activity.ACTIVITY_SERVICE) as ActivityManager
        this.queueSize = computeQueueSize(activityManager.largeMemoryClass)
        this.queue = ArrayBlockingQueue(queueSize)

        this.hdrProcessor = HDRProcessor(mainActivity, mainActivity.isTest)
        this.panoramaProcessor = PanoramaProcessor(mainActivity, hdrProcessor)
        this.postProcessing = PostProcessing(mainActivity)
    }

    fun computePhotoCost(nRaw: Int, nJpegs: Int): Int {
        if (MyDebug.LOG) {
            Log.d(TAG, "computePhotoCost")
            Log.d(TAG, "n_raw: $nRaw")
            Log.d(TAG, "n_jpegs: $nJpegs")
        }
        var cost = 0
        if (nRaw > 0) cost += computeRequestCost(true, nRaw)
        if (nJpegs > 0) cost += computeRequestCost(false, nJpegs)
        if (MyDebug.LOG) Log.d(TAG, "cost: $cost")
        return cost
    }

    fun queueWouldBlock(nRaw: Int, nJpegs: Int): Boolean {
        val photoCost = this.computePhotoCost(nRaw, nJpegs)
        return this.queueWouldBlock(photoCost)
    }

    @Synchronized
    fun queueWouldBlock(photoCost: Int): Boolean {
        if (MyDebug.LOG) {
            Log.d(TAG, "queueWouldBlock")
            Log.d(TAG, "photo_cost: $photoCost")
            Log.d(TAG, "n_images_to_save: $nImagesToSave")
            Log.d(TAG, "queue_capacity: $queueSize")
        }
        if (nImagesToSave == 0) {
            if (MyDebug.LOG) Log.d(TAG, "queue is empty")
            return false
        } else if (nImagesToSave + photoCost > queueSize + 1) {
            if (MyDebug.LOG) Log.d(TAG, "queue would block")
            return true
        }
        if (MyDebug.LOG) Log.d(TAG, "queue would not block")
        return false
    }

    val maxDNG: Int
        get() {
            var maxDng = (queueSize + 1) / QUEUE_COST_DNG_C
            maxDng++
            if (MyDebug.LOG) Log.d(TAG, "max_dng = $maxDng")
            return maxDng
        }

    fun onPause() {
        synchronized(this) {
            appIsPaused = true
        }
    }

    fun onResume() {
        synchronized(this) {
            appIsPaused = false
        }
    }

    fun onDestroy() {
        if (MyDebug.LOG) Log.d(TAG, "onDestroy")
        run {
            val request = Request(
                Request.Type.ON_DESTROY,
                Request.ProcessType.NORMAL,
                false,
                0,
                Request.SaveBase.SAVEBASE_NONE,
                mutableListOf(),
                null,
                null,
                false, null,
                usingCamera2 = false, usingCameraExtensions = false,
                Request.ImageFormat.STD, 0,
                false, 0.0, null,
                isFrontFacing = false,
                mirror = false,
                null,
                HDRProcessor.defaultTonemappingAlgorithmC,
                null,
                0,
                0,
                1.0f,
                null, null, 0, 0, null, null, null, null,
                null,
                false, Request.RemoveDeviceExif.OFF, false, null, false, 0.0,
                0.0, false,
                null, null,
                1
            )
            if (MyDebug.LOG) Log.d(TAG, "add on_destroy request")
            addRequest(request, 1)
        }
        panoramaProcessor.onDestroy()
        hdrProcessor.onDestroy()
    }

    override fun run() {
        if (MyDebug.LOG) Log.d(TAG, "starting ImageSaver thread...")
        while (true) {
            try {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "ImageSaver thread reading from queue, size: " + queue.size
                )
                val request = queue.take()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "ImageSaver thread found new request from queue, size is now: " + queue.size
                )
                val success: Boolean
                var onDestroy = false
                when (request.type) {
                    Request.Type.RAW -> {
                        if (MyDebug.LOG) Log.d(TAG, "request is raw")
                        success = saveImageNowRaw(request)
                    }

                    Request.Type.JPEG -> {
                        if (MyDebug.LOG) Log.d(TAG, "request is jpeg")
                        success = saveImageNow(request)
                    }

                    Request.Type.DUMMY -> {
                        if (MyDebug.LOG) Log.d(TAG, "request is dummy")
                        success = true
                    }

                    Request.Type.ON_DESTROY -> {
                        if (MyDebug.LOG) Log.d(TAG, "request is on_destroy")
                        success = true
                        onDestroy = true
                    }
                }
                if (testSlowSaving) {
                    sleep(2000)
                }
                if (MyDebug.LOG) {
                    if (success) Log.d(TAG, "ImageSaver thread successfully saved image")
                    else Log.e(TAG, "ImageSaver thread failed to save image")
                }
                synchronized(this) {
                    nImagesToSave--
                    if (request.type != Request.Type.DUMMY && request.type != Request.Type.ON_DESTROY) nRealImagesToSave--
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "ImageSaver thread processed new request from queue, images to save is now: $nImagesToSave"
                    )
                    if (MyDebug.LOG && nImagesToSave < 0) {
                        Log.e(TAG, "images to save has become negative")
                        throw RuntimeException()
                    } else if (MyDebug.LOG && nRealImagesToSave < 0) {
                        Log.e(TAG, "real images to save has become negative")
                        throw RuntimeException()
                    }
                    (this as Object).notifyAll()
                    mainActivity.runOnUiThread { mainActivity.imageQueueChanged() }
                }
                if (onDestroy) {
                    break
                }
            } catch (e: InterruptedException) {
                MyDebug.logStackTrace(
                    TAG,
                    "interrupted while trying to read from ImageSaver queue",
                    e
                )
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "stopping ImageSaver thread...")
    }

    fun saveImageJpeg(
        doInBackground: Boolean,
        processType: Request.ProcessType,
        forceSuffix: Boolean,
        suffixOffset: Int,
        saveExpo: Boolean,
        images: MutableList<ByteArray>,
        preshotBitmaps: MutableList<Bitmap?>?,
        imageCaptureIntent: Boolean,
        imageCaptureIntentUri: Uri?,
        usingCamera2: Boolean,
        usingCameraExtensions: Boolean,
        imageFormat: Request.ImageFormat,
        imageQuality: Int,
        doAutoStabilise: Boolean,
        levelAngle: Double,
        isFrontFacing: Boolean,
        mirror: Boolean,
        currentDate: Date,
        preferenceHdrTonemappingAlgorithm: HDRProcessor.TonemappingAlgorithm,
        preferenceHdrContrastEnhancement: String?,
        iso: Int,
        exposureTime: Long,
        zoomFactor: Float,
        preferenceStamp: String?,
        preferenceTextstamp: String?,
        fontSize: Int,
        color: Int,
        prefStyle: String?,
        preferenceStampDateformat: String?,
        preferenceStampTimeformat: String?,
        preferenceStampGpsformat: String?,
        preferenceUnitsDistance: String?,
        panoramaCrop: Boolean,
        removeDeviceExif: Request.RemoveDeviceExif,
        storeLocation: Boolean,
        location: Location?,
        storeGeoDirection: Boolean,
        geoDirection: Double,
        pitchAngle: Double,
        storeYpr: Boolean,
        customTagArtist: String?,
        customTagCopyright: String?,
        sampleFactor: Int
    ): Boolean {
        if (MyDebug.LOG) {
            Log.d(TAG, "saveImageJpeg")
            Log.d(TAG, "do_in_background? $doInBackground")
            Log.d(TAG, "number of images: " + images.size)
        }
        return saveImage(
            doInBackground,
            false,
            processType,
            forceSuffix,
            suffixOffset,
            saveExpo,
            images,
            preshotBitmaps,
            null,
            imageCaptureIntent,
            imageCaptureIntentUri,
            usingCamera2,
            usingCameraExtensions,
            imageFormat,
            imageQuality,
            doAutoStabilise,
            levelAngle,
            isFrontFacing,
            mirror,
            currentDate,
            preferenceHdrTonemappingAlgorithm,
            preferenceHdrContrastEnhancement,
            iso,
            exposureTime,
            zoomFactor,
            preferenceStamp,
            preferenceTextstamp,
            fontSize,
            color,
            prefStyle,
            preferenceStampDateformat,
            preferenceStampTimeformat,
            preferenceStampGpsformat,
            preferenceUnitsDistance,
            panoramaCrop,
            removeDeviceExif,
            storeLocation,
            location,
            storeGeoDirection,
            geoDirection,
            pitchAngle,
            storeYpr,
            customTagArtist,
            customTagCopyright,
            sampleFactor
        )
    }

    fun saveImageRaw(
        doInBackground: Boolean,
        forceSuffix: Boolean,
        suffixOffset: Int,
        rawImage: RawImage?,
        currentDate: Date
    ): Boolean {
        if (MyDebug.LOG) {
            Log.d(TAG, "saveImageRaw")
            Log.d(TAG, "do_in_background? $doInBackground")
        }
        return saveImage(
            doInBackground,
            true,
            Request.ProcessType.NORMAL,
            forceSuffix,
            suffixOffset,
            false,
            mutableListOf(),
            null,
            rawImage,
            false, null,
            false, false,
            Request.ImageFormat.STD, 0,
            false, 0.0,
            false,
            false,
            currentDate,
            HDRProcessor.defaultTonemappingAlgorithmC,
            null,
            0,
            0,
            1.0f,
            null, null, 0, 0, null, null, null, null,
            null,
            false, Request.RemoveDeviceExif.OFF, false, null, false, 0.0,
            0.0, false,
            null, null,
            1
        )
    }

    var imageBatchRequest: Request? = null
        private set

    fun startImageBatch(
        doInBackground: Boolean,
        processType: Request.ProcessType,
        preshotBitmaps: MutableList<Bitmap?>?,
        saveBase: Request.SaveBase,
        imageCaptureIntent: Boolean,
        imageCaptureIntentUri: Uri?,
        usingCamera2: Boolean,
        usingCameraExtensions: Boolean,
        imageFormat: Request.ImageFormat,
        imageQuality: Int,
        doAutoStabilise: Boolean,
        levelAngle: Double,
        wantGyroMatrices: Boolean,
        isFrontFacing: Boolean,
        mirror: Boolean,
        currentDate: Date?,
        iso: Int,
        exposureTime: Long,
        zoomFactor: Float,
        preferenceStamp: String?,
        preferenceTextstamp: String?,
        fontSize: Int,
        color: Int,
        prefStyle: String?,
        preferenceStampDateformat: String?,
        preferenceStampTimeformat: String?,
        preferenceStampGpsformat: String?,
        preferenceUnitsDistance: String?,
        panoramaCrop: Boolean,
        removeDeviceExif: Request.RemoveDeviceExif,
        storeLocation: Boolean,
        location: Location?,
        storeGeoDirection: Boolean,
        geoDirection: Double,
        pitchAngle: Double,
        storeYpr: Boolean,
        customTagArtist: String?,
        customTagCopyright: String?,
        sampleFactor: Int
    ) {
        if (MyDebug.LOG) {
            Log.d(TAG, "startImageBatch")
            Log.d(TAG, "do_in_background? $doInBackground")
        }
        imageBatchRequest = Request(
            Request.Type.JPEG,
            processType,
            false,
            0,
            saveBase,
            ArrayList<ByteArray>(),
            preshotBitmaps,
            null,
            imageCaptureIntent,
            imageCaptureIntentUri,
            usingCamera2,
            usingCameraExtensions,
            imageFormat,
            imageQuality,
            doAutoStabilise,
            levelAngle,
            if (wantGyroMatrices) mutableListOf() else null,
            isFrontFacing,
            mirror,
            currentDate,
            HDRProcessor.defaultTonemappingAlgorithmC,
            null,
            iso,
            exposureTime,
            zoomFactor,
            preferenceStamp,
            preferenceTextstamp,
            fontSize,
            color,
            prefStyle,
            preferenceStampDateformat,
            preferenceStampTimeformat,
            preferenceStampGpsformat,
            preferenceUnitsDistance,
            panoramaCrop,
            removeDeviceExif,
            storeLocation,
            location,
            storeGeoDirection,
            geoDirection,
            pitchAngle,
            storeYpr,
            customTagArtist,
            customTagCopyright,
            sampleFactor
        )
    }

    fun addImageBatch(image: ByteArray, gyroRotationMatrix: FloatArray?) {
        if (MyDebug.LOG) Log.d(TAG, "addImageBatch")
        if (imageBatchRequest == null) {
            Log.e(TAG, "addImageBatch called but no pending_image_average_request")
            return
        }
        imageBatchRequest!!.jpegImages.add(image)
        if (gyroRotationMatrix != null) {
            val copy = FloatArray(gyroRotationMatrix.size)
            System.arraycopy(gyroRotationMatrix, 0, copy, 0, gyroRotationMatrix.size)
            imageBatchRequest!!.gyroRotationMatrix!!.add(copy)
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "image average request images: " + imageBatchRequest!!.jpegImages.size
        )
    }

    fun finishImageBatch(doInBackground: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "finishImageBatch")
        if (imageBatchRequest == null) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "finishImageBatch called but no pending_image_average_request"
            )
            return
        }
        if (doInBackground) {
            if (MyDebug.LOG) Log.d(TAG, "add background request")
            val cost = computeRequestCost(false, imageBatchRequest!!.jpegImages.size)
            addRequest(imageBatchRequest!!, cost)
        } else {
            waitUntilDone()
            saveImageNow(imageBatchRequest!!)
        }
        imageBatchRequest = null
    }

    fun flushImageBatch() {
        if (MyDebug.LOG) Log.d(TAG, "flushImageBatch")
        imageBatchRequest = null
    }

    private fun saveImage(
        doInBackground: Boolean,
        isRaw: Boolean,
        processType: Request.ProcessType,
        forceSuffix: Boolean,
        suffixOffset: Int,
        saveExpo: Boolean,
        jpegImages: MutableList<ByteArray>,
        preshotBitmaps: MutableList<Bitmap?>?,
        rawImage: RawImage?,
        imageCaptureIntent: Boolean,
        imageCaptureIntentUri: Uri?,
        usingCamera2: Boolean,
        usingCameraExtensions: Boolean,
        imageFormat: Request.ImageFormat,
        imageQuality: Int,
        doAutoStabilise: Boolean,
        levelAngle: Double,
        isFrontFacing: Boolean,
        mirror: Boolean,
        currentDate: Date,
        preferenceHdrTonemappingAlgorithm: HDRProcessor.TonemappingAlgorithm,
        preferenceHdrContrastEnhancement: String?,
        iso: Int,
        exposureTime: Long,
        zoomFactor: Float,
        preferenceStamp: String?,
        preferenceTextstamp: String?,
        fontSize: Int,
        color: Int,
        prefStyle: String?,
        preferenceStampDateformat: String?,
        preferenceStampTimeformat: String?,
        preferenceStampGpsformat: String?,
        preferenceUnitsDistance: String?,
        panoramaCrop: Boolean,
        removeDeviceExif: Request.RemoveDeviceExif,
        storeLocation: Boolean,
        location: Location?,
        storeGeoDirection: Boolean,
        geoDirection: Double,
        pitchAngle: Double,
        storeYpr: Boolean,
        customTagArtist: String?,
        customTagCopyright: String?,
        sampleFactor: Int
    ): Boolean {
        if (MyDebug.LOG) {
            Log.d(TAG, "saveImage")
            Log.d(TAG, "do_in_background? $doInBackground")
        }
        val success: Boolean

        val request = Request(
            if (isRaw) Request.Type.RAW else Request.Type.JPEG,
            processType,
            forceSuffix,
            suffixOffset,
            if (saveExpo) Request.SaveBase.SAVEBASE_ALL else Request.SaveBase.SAVEBASE_NONE,
            jpegImages,
            preshotBitmaps,
            rawImage,
            imageCaptureIntent,
            imageCaptureIntentUri,
            usingCamera2,
            usingCameraExtensions,
            imageFormat,
            imageQuality,
            doAutoStabilise,
            levelAngle,
            null,
            isFrontFacing,
            mirror,
            currentDate,
            preferenceHdrTonemappingAlgorithm,
            preferenceHdrContrastEnhancement,
            iso,
            exposureTime,
            zoomFactor,
            preferenceStamp,
            preferenceTextstamp,
            fontSize,
            color,
            prefStyle,
            preferenceStampDateformat,
            preferenceStampTimeformat,
            preferenceStampGpsformat,
            preferenceUnitsDistance,
            panoramaCrop,
            removeDeviceExif,
            storeLocation,
            location,
            storeGeoDirection,
            geoDirection,
            pitchAngle,
            storeYpr,
            customTagArtist,
            customTagCopyright,
            sampleFactor
        )

        if (doInBackground) {
            if (MyDebug.LOG) Log.d(TAG, "add background request")
            val cost = computeRequestCost(isRaw, if (isRaw) 1 else request.jpegImages.size)
            addRequest(request, cost)
            success = true
        } else {
            waitUntilDone()
            success = if (isRaw) {
                saveImageNowRaw(request)
            } else {
                saveImageNow(request)
            }
        }

        if (MyDebug.LOG) Log.d(TAG, "success: $success")
        return success
    }

    private fun addRequest(request: Request, cost: Int) {
        if (MyDebug.LOG) Log.d(TAG, "addRequest, cost: $cost")
        if (mainActivity.isDestroyed) {
            Log.e(TAG, "application is destroyed, image lost!")
            return
        }
        var done = false
        while (!done) {
            try {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "ImageSaver thread adding to queue, size: " + queue.size
                )
                synchronized(this) {
                    nImagesToSave++
                    if (request.type != Request.Type.DUMMY && request.type != Request.Type.ON_DESTROY) nRealImagesToSave++
                    mainActivity.runOnUiThread { mainActivity.imageQueueChanged() }
                }
                if (queue.size + 1 > queueSize) {
                    Log.e(
                        TAG,
                        "ImageSaver thread is going to block, queue already full: " + queue.size
                    )
                    testQueueBlocked = true
                }
                queue.put(request)
                if (MyDebug.LOG) {
                    synchronized(this) {
                        Log.d(TAG, "ImageSaver thread added to queue, size is now: " + queue.size)
                        Log.d(TAG, "images still to save is now: " + nImagesToSave)
                        Log.d(TAG, "real images still to save is now: " + nRealImagesToSave)
                    }
                }
                done = true
            } catch (e: InterruptedException) {
                MyDebug.logStackTrace(TAG, "interrupted while trying to add to ImageSaver queue", e)
            }
        }
        if (cost > 0) {
            repeat(cost - 1) {
                addDummyRequest()
            }
        }
    }

    private fun addDummyRequest() {
        val dummyRequest = Request(
            Request.Type.DUMMY,
            Request.ProcessType.NORMAL,
            false,
            0,
            Request.SaveBase.SAVEBASE_NONE,
            mutableListOf(),
            null,
            null,
            false, null,
            false, false,
            Request.ImageFormat.STD, 0,
            false, 0.0, null,
            false,
            false,
            null,
            HDRProcessor.defaultTonemappingAlgorithmC,
            null,
            0,
            0,
            1.0f,
            null, null, 0, 0, null, null, null, null,
            null,
            false, Request.RemoveDeviceExif.OFF, false, null, false, 0.0,
            0.0, false,
            null, null,
            1
        )
        if (MyDebug.LOG) Log.d(TAG, "add dummy request")
        addRequest(dummyRequest, 1)
    }

    fun waitUntilDone() {
        if (MyDebug.LOG) Log.d(TAG, "waitUntilDone")
        synchronized(this) {
            if (MyDebug.LOG) {
                Log.d(TAG, "waitUntilDone: queue is size " + queue.size)
                Log.d(TAG, "waitUntilDone: images still to save " + nImagesToSave)
            }
            while (nImagesToSave > 0) {
                if (MyDebug.LOG) Log.d(TAG, "wait until done...")
                try {
                    (this as Object).wait()
                } catch (e: InterruptedException) {
                    MyDebug.logStackTrace(
                        TAG,
                        "interrupted while waiting for ImageSaver queue to be empty",
                        e
                    )
                }
                if (MyDebug.LOG) {
                    Log.d(TAG, "waitUntilDone: queue is size " + queue.size)
                    Log.d(TAG, "waitUntilDone: images still to save " + nImagesToSave)
                }
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "waitUntilDone: images all saved")
    }

    private fun writeGyroDebugXml(writer: Writer, request: Request) {
        val xmlSerializer = Xml.newSerializer()

        xmlSerializer.setOutput(writer)
        xmlSerializer.startDocument("UTF-8", true)
        xmlSerializer.startTag(null, GYRO_INFO_DOC_TAG)
        xmlSerializer.attribute(
            null,
            GYRO_INFO_PANORAMA_PICS_PER_SCREEN_TAG,
            MyApplicationInterface.panoramaPicsPerScreen.toString()
        )
        xmlSerializer.attribute(
            null,
            GYRO_INFO_CAMERA_VIEW_ANGLE_X_TAG,
            request.cameraViewAngleX.toString()
        )
        xmlSerializer.attribute(
            null,
            GYRO_INFO_CAMERA_VIEW_ANGLE_Y_TAG,
            request.cameraViewAngleY.toString()
        )

        val inVector = FloatArray(3)
        val outVector = FloatArray(3)
        for (i in request.gyroRotationMatrix!!.indices) {
            xmlSerializer.startTag(null, GYRO_INFO_IMAGE_TAG)
            xmlSerializer.attribute(null, "index", i.toString())

            GyroSensor.setVector(inVector, 1.0f, 0.0f, 0.0f)
            GyroSensor.transformVector(outVector, request.gyroRotationMatrix[i]!!, inVector)
            xmlSerializer.startTag(null, GYRO_INFO_VECTOR_TAG)
            xmlSerializer.attribute(null, "type", GYRO_INFO_VECTOR_RIGHT_TYPE)
            xmlSerializer.attribute(null, "x", outVector[0].toString())
            xmlSerializer.attribute(null, "y", outVector[1].toString())
            xmlSerializer.attribute(null, "z", outVector[2].toString())
            xmlSerializer.endTag(null, GYRO_INFO_VECTOR_TAG)

            GyroSensor.setVector(inVector, 0.0f, 1.0f, 0.0f)
            GyroSensor.transformVector(outVector, request.gyroRotationMatrix[i]!!, inVector)
            xmlSerializer.startTag(null, GYRO_INFO_VECTOR_TAG)
            xmlSerializer.attribute(null, "type", GYRO_INFO_VECTOR_UP_TYPE)
            xmlSerializer.attribute(null, "x", outVector[0].toString())
            xmlSerializer.attribute(null, "y", outVector[1].toString())
            xmlSerializer.attribute(null, "z", outVector[2].toString())
            xmlSerializer.endTag(null, GYRO_INFO_VECTOR_TAG)

            GyroSensor.setVector(inVector, 0.0f, 0.0f, -1.0f)
            GyroSensor.transformVector(outVector, request.gyroRotationMatrix[i]!!, inVector)
            xmlSerializer.startTag(null, GYRO_INFO_VECTOR_TAG)
            xmlSerializer.attribute(null, "type", GYRO_INFO_VECTOR_SCREEN_TYPE)
            xmlSerializer.attribute(null, "x", outVector[0].toString())
            xmlSerializer.attribute(null, "y", outVector[1].toString())
            xmlSerializer.attribute(null, "z", outVector[2].toString())
            xmlSerializer.endTag(null, GYRO_INFO_VECTOR_TAG)

            xmlSerializer.endTag(null, GYRO_INFO_IMAGE_TAG)
        }

        xmlSerializer.endTag(null, GYRO_INFO_DOC_TAG)
        xmlSerializer.endDocument()
        xmlSerializer.flush()
    }

    class GyroDebugInfo {
        data class GyroImageDebugInfo(
            var vectorRight: FloatArray? = null,
            var vectorUp: FloatArray? = null,
            var vectorScreen: FloatArray? = null
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as GyroImageDebugInfo

                if (!vectorRight.contentEquals(other.vectorRight)) return false
                if (!vectorUp.contentEquals(other.vectorUp)) return false
                if (!vectorScreen.contentEquals(other.vectorScreen)) return false

                return true
            }

            override fun hashCode(): Int {
                var result = vectorRight?.contentHashCode() ?: 0
                result = 31 * result + (vectorUp?.contentHashCode() ?: 0)
                result = 31 * result + (vectorScreen?.contentHashCode() ?: 0)
                return result
            }
        }

        val imageInfo: MutableList<GyroImageDebugInfo> = ArrayList()
    }

    private fun processHDR(bitmaps: MutableList<Bitmap?>, request: Request, timeS: Long): Boolean {
        val hdrAlpha = getHDRAlpha(
            request.preferenceHdrContrastEnhancement,
            request.exposureTime,
            bitmaps.size
        )
        if (MyDebug.LOG) Log.d(
            TAG,
            "before HDR first bitmap: " + bitmaps[0] + " is mutable? " + bitmaps[0]!!.isMutable
        )
        try {
            hdrProcessor.processHDR(
                bitmaps,
                true,
                null,
                true,
                null,
                hdrAlpha,
                4,
                true,
                request.preferenceHdrTonemappingAlgorithm,
                HDRProcessor.DROTonemappingAlgorithm.DROALGORITHM_GAINGAMMA
            )
        } catch (e: HDRProcessorException) {
            MyDebug.logStackTrace(TAG, "HDRProcessorException from processHDR", e)
            if (e.code == HDRProcessorException.UNEQUAL_SIZES) {
                Log.e(TAG, "UNEQUAL_SIZES")
                bitmaps.clear()
                System.gc()
                return false
            } else {
                throw RuntimeException()
            }
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "HDR performance: time after creating HDR image: " + (System.currentTimeMillis() - timeS)
            )
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "after HDR first bitmap: " + bitmaps[0] + " is mutable? " + bitmaps[0]!!.isMutable
        )
        return true
    }

    private fun saveImageNow(request: Request): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "saveImageNow")

        if (request.type != Request.Type.JPEG) {
            if (MyDebug.LOG) Log.d(TAG, "saveImageNow called with non-jpeg request")
            throw RuntimeException()
        } else if (request.jpegImages.isEmpty()) {
            if (MyDebug.LOG) Log.d(TAG, "saveImageNow called with zero images")
            throw RuntimeException()
        }

        if (!request.preshotBitmaps.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Preshots.savePreshotBitmaps(mainActivity, this, request)
        }

        val success: Boolean
        if (request.processType == Request.ProcessType.AVERAGE) {
            if (MyDebug.LOG) Log.d(TAG, "average")

            saveBaseImages(request, "_")
            mainActivity.savingImage(true)

            val nrBitmap: Bitmap
            run {
                try {
                    val timeS = System.currentTimeMillis()
                    val inSampleSize: Int =
                        hdrProcessor.getAvgSampleSize(request.iso, request.exposureTime)
                    val useSmp = true
                    val nSmpImages = 4
                    var thisTimeS = System.currentTimeMillis()
                    var bitmaps: MutableList<Bitmap?>? = null
                    val bitmap0: Bitmap?
                    val bitmap1: Bitmap?
                    if (useSmp) {
                        val nRemaining = request.jpegImages.size
                        val nLoad = min(nSmpImages.toDouble(), nRemaining.toDouble()).toInt()
                        if (MyDebug.LOG) {
                            Log.d(TAG, "n_remaining: $nRemaining")
                            Log.d(TAG, "n_load: $nLoad")
                        }
                        val subJpegList: MutableList<ByteArray> = ArrayList()
                        for (j in 0..<nLoad) {
                            subJpegList.add(request.jpegImages[j])
                        }
                        bitmaps =
                            ImageUtils.loadBitmaps(subJpegList, -1, inSampleSize)?.toMutableList()
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "length of bitmaps list is now: " + bitmaps?.size
                        )
                        bitmap0 = bitmaps?.get(0)
                        bitmap1 = bitmaps?.get(1)
                    } else {
                        bitmap0 = ImageUtils.loadBitmap(request.jpegImages[0], false, inSampleSize)
                        bitmap1 = ImageUtils.loadBitmap(request.jpegImages[1], false, inSampleSize)
                    }
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "*** time for loading first bitmaps: " + (System.currentTimeMillis() - thisTimeS)
                        )
                    }
                    val width = bitmap0!!.width
                    val height = bitmap0.height
                    var avgFactor = 1.0f
                    thisTimeS = System.currentTimeMillis()
                    val avgData: HDRProcessor.AvgData = hdrProcessor.processAvg(
                        bitmap0,
                        bitmap1,
                        avgFactor,
                        request.iso,
                        request.exposureTime,
                        request.zoomFactor
                    )
                    if (bitmaps != null) {
                        bitmaps[0] = null
                        bitmaps[1] = null
                    }
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "*** time for processing first two bitmaps: " + (System.currentTimeMillis() - thisTimeS)
                        )
                    }

                    for (i in 2..<request.jpegImages.size) {
                        if (MyDebug.LOG) Log.d(TAG, "processAvg for image: $i")

                        thisTimeS = System.currentTimeMillis()
                        val newBitmap: Bitmap?
                        if (useSmp) {
                            if (MyDebug.LOG) Log.d(TAG, "length of bitmaps list: " + bitmaps!!.size)
                            if (i < bitmaps!!.size) {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "already loaded bitmap from previous iteration with SMP"
                                )
                                newBitmap = bitmaps[i]
                            } else {
                                val nRemaining = request.jpegImages.size - i
                                val nLoad =
                                    min(nSmpImages.toDouble(), nRemaining.toDouble()).toInt()
                                if (MyDebug.LOG) {
                                    Log.d(TAG, "n_remaining: $nRemaining")
                                    Log.d(TAG, "n_load: $nLoad")
                                }
                                val subJpegList: MutableList<ByteArray> = ArrayList()
                                for (j in i..<i + nLoad) {
                                    subJpegList.add(request.jpegImages[j])
                                }
                                val newBitmaps =
                                    ImageUtils.loadBitmaps(subJpegList, -1, inSampleSize)
                                if (newBitmaps != null) {
                                    bitmaps.addAll(newBitmaps)
                                }
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "length of bitmaps list is now: " + bitmaps.size
                                )
                                newBitmap = bitmaps[i]
                            }
                        } else {
                            newBitmap =
                                ImageUtils.loadBitmap(request.jpegImages[i], false, inSampleSize)
                        }
                        if (MyDebug.LOG) {
                            Log.d(
                                TAG,
                                "*** time for loading extra bitmap: " + (System.currentTimeMillis() - thisTimeS)
                            )
                        }
                        avgFactor = i.toFloat()
                        thisTimeS = System.currentTimeMillis()
                        hdrProcessor.updateAvg(
                            avgData,
                            width,
                            height,
                            newBitmap,
                            avgFactor,
                            request.iso,
                            request.exposureTime,
                            request.zoomFactor
                        )
                        bitmaps?.set(i, null)
                        if (MyDebug.LOG) {
                            Log.d(
                                TAG,
                                "*** time for updating extra bitmap: " + (System.currentTimeMillis() - thisTimeS)
                            )
                        }
                    }

                    thisTimeS = System.currentTimeMillis()
                    nrBitmap = hdrProcessor.avgBrighten(
                        avgData,
                        width,
                        height,
                        request.iso,
                        request.exposureTime
                    )
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "*** time for brighten: " + (System.currentTimeMillis() - thisTimeS)
                        )
                    }
                    avgData.destroy()
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "*** total time for saving NR image: " + (System.currentTimeMillis() - timeS)
                        )
                    }
                } catch (e: HDRProcessorException) {
                    MyDebug.logStackTrace(TAG, "HDRProcessorException", e)
                    throw RuntimeException()
                }
            }

            if (MyDebug.LOG) Log.d(
                TAG,
                "nr_bitmap: " + nrBitmap + " is mutable? " + nrBitmap.isMutable
            )
            System.gc()
            mainActivity.savingImage(false)

            if (MyDebug.LOG) Log.d(TAG, "save NR image")
            success = saveSingleImageNow(
                request,
                request.jpegImages[0], nrBitmap, NR_SUFFIX, true, true, true, false
            )
            if (MyDebug.LOG && !success) Log.e(TAG, "saveSingleImageNow failed for nr image")
            nrBitmap.recycle()
            System.gc()
        } else if (request.processType == Request.ProcessType.HDR) {
            if (MyDebug.LOG) Log.d(TAG, "hdr")
            if (request.jpegImages.size != 1 && request.jpegImages.size != 3) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "saveImageNow expected either 1 or 3 images for hdr, not " + request.jpegImages.size
                )
                throw RuntimeException()
            }

            val timeS = System.currentTimeMillis()
            if (request.jpegImages.size > 1) {
                saveBaseImages(request, "_")
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "HDR performance: time after saving base exposures: " + (System.currentTimeMillis() - timeS)
                    )
                }
            }

            if (MyDebug.LOG) Log.d(TAG, "create HDR image")
            mainActivity.savingImage(true)

            val baseBitmap = (request.jpegImages.size - 1) / 2
            if (MyDebug.LOG) Log.d(TAG, "base_bitmap: $baseBitmap")
            val bitmaps = ImageUtils.loadBitmaps(request.jpegImages, baseBitmap, 1)?.toMutableList()
            if (bitmaps == null) {
                if (MyDebug.LOG) Log.e(TAG, "failed to load bitmaps")
                mainActivity.savingImage(false)
                return false
            }
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "HDR performance: time after decompressing base exposures: " + (System.currentTimeMillis() - timeS)
                )
            }

            if (!processHDR(bitmaps, request, timeS)) {
                mainActivity.preview.showToast(null, R.string.failed_to_process_hdr)
                mainActivity.savingImage(false)
                return false
            }

            val hdrBitmap = bitmaps[0]
            if (MyDebug.LOG) Log.d(
                TAG,
                "hdr_bitmap: " + hdrBitmap + " is mutable? " + hdrBitmap?.isMutable
            )
            bitmaps.clear()
            System.gc()
            mainActivity.savingImage(false)

            if (MyDebug.LOG) Log.d(TAG, "save HDR image")
            val baseImageId = (request.jpegImages.size - 1) / 2
            if (MyDebug.LOG) Log.d(TAG, "base_image_id: $baseImageId")
            val suffix = if (request.jpegImages.size == 1) "_DRO" else HDR_SUFFIX
            success = saveSingleImageNow(
                request,
                request.jpegImages[baseImageId],
                hdrBitmap,
                suffix,
                true,
                true,
                true,
                false
            )
            if (MyDebug.LOG && !success) Log.e(TAG, "saveSingleImageNow failed for hdr image")
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "HDR performance: time after saving HDR image: " + (System.currentTimeMillis() - timeS)
                )
            }
            hdrBitmap?.recycle()
            System.gc()
        } else if (request.processType == Request.ProcessType.PANORAMA) {
            if (MyDebug.LOG) Log.d(TAG, "panorama")

            if (!request.imageCaptureIntent && request.saveBase == Request.SaveBase.SAVEBASE_ALL_PLUS_DEBUG) {
                try {
                    val writer = StringWriter()
                    writeGyroDebugXml(writer, request)
                    val storageUtils = mainActivity.storageUtils
                    val saveFile = mainActivity.getExternalFilesDir(null)?.let { file ->
                        storageUtils.createOutputMediaFile(
                            file,
                            StorageUtils.MEDIA_TYPE_GYRO_INFO,
                            "",
                            "xml",
                            request.currentDate
                        )
                    }
                    if (MyDebug.LOG) Log.d(TAG, "save to: " + saveFile?.absolutePath)
                    val saveUri: Uri? = null
                    val outputStream: OutputStream? =
                        if (saveFile != null) FileOutputStream(saveFile)
                        else mainActivity.contentResolver.openOutputStream(saveUri!!)
                    try {
                        outputStream?.write(writer.toString().toByteArray(Charset.forName("UTF-8")))
                    } catch (_: Exception) {
                        // do nothing
                    } finally {
                        outputStream?.close()
                    }

                    if (saveFile != null) {
                        storageUtils.broadcastFile(saveFile, false, false, false, false, null)
                    } else if (saveUri != null) {
                        broadcastSAFFile(saveUri, false, false, false)
                    }
                } catch (e: IOException) {
                    MyDebug.logStackTrace(TAG, "failed to write gyro text file", e)
                }
            }

            saveBaseImages(request, "_")
            mainActivity.savingImage(true)
            val timeS = System.currentTimeMillis()

            if (MyDebug.LOG) Log.d(
                TAG,
                "panorama_dir_left_to_right: " + request.panoramaDirLeftToRight
            )
            if (!request.panoramaDirLeftToRight) {
                Collections.reverse(request.jpegImages)
                if (request.gyroRotationMatrix != null) {
                    Collections.reverse(request.gyroRotationMatrix)
                }
            }

            val bitmaps = ImageUtils.loadBitmaps(request.jpegImages, -2, 1)?.toMutableList()
            if (bitmaps == null) {
                if (MyDebug.LOG) Log.e(TAG, "failed to load bitmaps")
                mainActivity.savingImage(false)
                return false
            }
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "panorama performance: time after decompressing base exposures: " + (System.currentTimeMillis() - timeS)
                )
            }

            for (i in bitmaps.indices) {
                var bitmap = bitmaps[i]
                bitmap = ImageUtils.rotateForExif(bitmap, request.jpegImages[0])
                bitmaps[i] = bitmap
            }
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "panorama performance: time after rotating for exif: " + (System.currentTimeMillis() - timeS)
                )
            }

            val panorama: Bitmap
            try {
                @Suppress("UNCHECKED_CAST")
                panorama = panoramaProcessor.panorama(
                    bitmaps as MutableList<Bitmap>,
                    MyApplicationInterface.panoramaPicsPerScreen,
                    request.cameraViewAngleY,
                    request.panoramaCrop
                )
            } catch (e: PanoramaProcessorException) {
                MyDebug.logStackTrace(TAG, "PanoramaProcessorException from panorama", e)
                if (e.code == PanoramaProcessorException.UNEQUAL_SIZES || e.code == PanoramaProcessorException.FAILED_TO_CROP) {
                    mainActivity.preview.showToast(null, R.string.failed_to_process_panorama)
                    Log.e(TAG, "panorama failed: " + e.code)
                    bitmaps.clear()
                    System.gc()
                    mainActivity.savingImage(false)
                    return false
                } else {
                    throw RuntimeException()
                }
            }
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "panorama performance: time after creating panorama image: " + (System.currentTimeMillis() - timeS)
                )
            }
            if (MyDebug.LOG) Log.d(TAG, "panorama: $panorama")
            bitmaps.clear()
            System.gc()

            mainActivity.savingImage(false)

            if (MyDebug.LOG) Log.d(TAG, "save panorama image")
            success = saveSingleImageNow(
                request,
                request.jpegImages[0],
                panorama,
                PANO_SUFFIX,
                true,
                true,
                true,
                true
            )
            if (MyDebug.LOG && !success) Log.e(TAG, "saveSingleImageNow failed for panorama image")
            panorama.recycle()
            System.gc()
        } else {
            val suffix = "_"
            success = saveImages(request, suffix, false, true, true)
        }

        return success
    }

    /** Saves all the JPEG images in request.jpegImages.
     */
    private fun saveImages(
        request: Request,
        suffix: String,
        firstOnly: Boolean,
        updateThumbnail: Boolean,
        share: Boolean
    ): Boolean {
        var success = true
        val midImage = request.jpegImages.size / 2
        for (i in request.jpegImages.indices) {
            val image = request.jpegImages[i]
            val multipleJpegs = request.jpegImages.size > 1 && !firstOnly
            var filenameSuffix =
                if (multipleJpegs || request.forceSuffix) suffix + (i + request.suffixOffset) else ""
            if (request.processType == Request.ProcessType.X_NIGHT) {
                filenameSuffix = "_Night$filenameSuffix"
            }
            val shareImage = share && (i == midImage)
            if (!saveSingleImageNow(
                    request,
                    image,
                    null,
                    filenameSuffix,
                    updateThumbnail,
                    shareImage,
                    false,
                    false
                )
            ) {
                if (MyDebug.LOG) Log.e(TAG, "saveSingleImageNow failed for image: $i")
                success = false
            }
            if (firstOnly) break
        }
        return success
    }

    /** Saves all the images in request.jpegImages, depending on the saveBase option.
     */
    private fun saveBaseImages(request: Request, suffix: String) {
        if (MyDebug.LOG) Log.d(TAG, "saveBaseImages")
        if (!request.imageCaptureIntent && request.saveBase != Request.SaveBase.SAVEBASE_NONE) {
            if (MyDebug.LOG) Log.d(TAG, "save base images")

            var baseRequest = request
            if (request.processType == Request.ProcessType.PANORAMA) {
                baseRequest = request.copy()
                baseRequest.imageFormat = Request.ImageFormat.PNG
                baseRequest.preferenceStamp = "preference_stamp_no"
                baseRequest.preferenceTextstamp = ""
                baseRequest.doAutoStabilise = false
                baseRequest.mirror = false
            } else if (request.processType == Request.ProcessType.AVERAGE) {
                baseRequest = request.copy()
                baseRequest.imageQuality = 100
            }
            saveImages(
                baseRequest,
                suffix,
                baseRequest.saveBase == Request.SaveBase.SAVEBASE_FIRST,
                false,
                false
            )
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun saveSingleImageNow(
        request: Request,
        data: ByteArray?,
        bitmap: Bitmap?,
        filenameSuffix: String,
        updateThumbnail: Boolean,
        shareImage: Boolean,
        ignoreRawOnly: Boolean,
        ignoreExifOrientation: Boolean
    ): Boolean {
        var bitmapVar = bitmap
        if (MyDebug.LOG) Log.d(TAG, "saveSingleImageNow")

        if (request.type != Request.Type.JPEG) {
            if (MyDebug.LOG) Log.d(TAG, "saveImageNow called with non-jpeg request")
            throw RuntimeException()
        } else if (data == null) {
            if (MyDebug.LOG) Log.d(TAG, "saveSingleImageNow called with no data")
            throw RuntimeException()
        }
        val timeS = System.currentTimeMillis()

        var success = false
        val applicationInterface = mainActivity.applicationInterface
        val rawOnly = !ignoreRawOnly && applicationInterface.isRawOnly
        if (MyDebug.LOG) Log.d(TAG, "raw_only: $rawOnly")
        val storageUtils = mainActivity.storageUtils

        val extension = when (request.imageFormat) {
            Request.ImageFormat.WEBP -> "webp"
            Request.ImageFormat.PNG -> "png"
            else -> "jpg"
        }
        if (MyDebug.LOG) Log.d(TAG, "extension: $extension")

        mainActivity.savingImage(true)

        var picFile: File? = null
        var saveUri: Uri? = null
        var useMediaStore = false
        var contentValues: ContentValues? = null
        try {
            if (!rawOnly) {
                val postProcessBitmapResult = postProcessing.postProcessBitmap(
                    request,
                    data,
                    bitmapVar,
                    ignoreExifOrientation
                )
                bitmapVar = postProcessBitmapResult.bitmap
            }

            if (rawOnly) {
                success = true
            } else if (request.imageCaptureIntent) {
                if (MyDebug.LOG) Log.d(TAG, "image_capture_intent")
                if (request.imageCaptureIntentUri != null) {
                    if (MyDebug.LOG) Log.d(TAG, "save to: " + request.imageCaptureIntentUri)
                    saveUri = request.imageCaptureIntentUri
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "sent to intent via parcel")
                    if (bitmapVar == null) {
                        if (MyDebug.LOG) Log.d(TAG, "create bitmap")
                        bitmapVar = ImageUtils.loadBitmapWithRotation(data, false)
                    }
                    if (bitmapVar != null) {
                        val width = bitmapVar.width
                        val height = bitmapVar.height
                        if (MyDebug.LOG) {
                            Log.d(TAG, "decoded bitmap size $width, $height")
                            Log.d(TAG, "bitmap size: ${width * height * 4}")
                        }
                        val smallSizeC = 128
                        if (width > smallSizeC) {
                            val scale = smallSizeC.toFloat() / width.toFloat()
                            if (MyDebug.LOG) Log.d(TAG, "scale to $scale")
                            val matrix = Matrix()
                            matrix.postScale(scale, scale)
                            val newBitmap =
                                Bitmap.createBitmap(bitmapVar, 0, 0, width, height, matrix, true)
                            if (newBitmap != bitmapVar) {
                                bitmapVar.recycle()
                                bitmapVar = newBitmap
                            }
                        }
                    }
                    if (MyDebug.LOG) {
                        if (bitmapVar != null) {
                            Log.d(
                                TAG,
                                "returned bitmap size " + bitmapVar.width + ", " + bitmapVar.height
                            )
                            Log.d(
                                TAG,
                                "returned bitmap size: " + bitmapVar.width * bitmapVar.height * 4
                            )
                        } else {
                            Log.e(TAG, "no bitmap created")
                        }
                    }
                    if (bitmapVar != null) {
                        mainActivity.setResult(
                            Activity.RESULT_OK,
                            Intent("inline-data").putExtra("data", bitmapVar)
                        )
                    }
                    mainActivity.finish()
                }
            } else if (storageUtils.isUsingSAF) {
                saveUri = storageUtils.createOutputMediaFileSAF(
                    StorageUtils.MEDIA_TYPE_IMAGE,
                    filenameSuffix,
                    extension,
                    request.currentDate
                )
            } else if (MainActivity.useScopedStorage()) {
                if (MyDebug.LOG) Log.d(TAG, "use media store")
                useMediaStore = true
                val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                contentValues = ContentValues()
                val picName = storageUtils.createMediaFilename(
                    StorageUtils.MEDIA_TYPE_IMAGE,
                    filenameSuffix,
                    0,
                    ".$extension",
                    request.currentDate
                )
                if (MyDebug.LOG) Log.d(TAG, "picName: $picName")
                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, picName)
                val mimeType = storageUtils.getImageMimeType(extension)
                if (MyDebug.LOG) Log.d(TAG, "mime_type: $mimeType")
                contentValues.put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relativePath = storageUtils.saveRelativeFolder
                    if (MyDebug.LOG) Log.d(TAG, "relative_path: $relativePath")
                    contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                saveUri = mainActivity.contentResolver.insert(folder, contentValues)
                if (MyDebug.LOG) Log.d(TAG, "saveUri: $saveUri")
            } else {
                picFile = storageUtils.createOutputMediaFile(
                    StorageUtils.MEDIA_TYPE_IMAGE,
                    filenameSuffix,
                    extension,
                    request.currentDate
                )
                if (MyDebug.LOG) Log.d(TAG, "save to: " + picFile.absolutePath)
            }

            if (saveUri != null || picFile != null) {
                val compressFormat = getBitmapCompressFormat(request.imageFormat)
                val outputStream =
                    if (picFile != null) FileOutputStream(picFile) else mainActivity.contentResolver.openOutputStream(
                        saveUri!!
                    )
                try {
                    if (bitmapVar != null) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "compress bitmap, quality " + request.imageQuality
                        )
                        if (request.processType == Request.ProcessType.PANORAMA && compressFormat == CompressFormat.JPEG) {
                            savePanoramaBitmap(
                                bitmapVar,
                                compressFormat,
                                request.imageQuality,
                                request.jpegImages.size,
                                outputStream!!
                            )
                        } else {
                            bitmapVar.compress(compressFormat, request.imageQuality, outputStream!!)
                        }
                    } else {
                        outputStream!!.write(data)
                    }
                } finally {
                    outputStream?.close()
                }
                if (MyDebug.LOG) Log.d(TAG, "saveImageNow saved photo")
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "Save single image performance: time after saving photo: ${System.currentTimeMillis() - timeS}"
                    )
                }

                if (saveUri == null) {
                    success = true
                }

                if (bitmapVar != null) {
                    if (MyDebug.LOG) Log.d(TAG, "set Exif tags from data")
                    if (picFile != null) {
                        ExifHandler.setExifFromData(request, data, picFile)
                    } else {
                        val parcelFileDescriptor =
                            mainActivity.contentResolver.openFileDescriptor(saveUri!!, "rw")
                        try {
                            if (parcelFileDescriptor != null) {
                                val fileDescriptor = parcelFileDescriptor.fileDescriptor
                                ExifHandler.setExifFromData(request, data, fileDescriptor)
                            } else {
                                Log.e(
                                    TAG,
                                    "failed to create ParcelFileDescriptor for saveUri: $saveUri"
                                )
                            }
                        } finally {
                            parcelFileDescriptor?.close()
                        }
                    }
                } else {
                    ExifHandler.updateExif(mainActivity, request, picFile, saveUri)
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "Save single image performance: time after updateExif: ${System.currentTimeMillis() - timeS}"
                        )
                    }
                }

                if (updateThumbnail) {
                    storageUtils.clearLastMediaScanned()
                }

                if (rawOnly || request.imageCaptureIntent) {
                    // no need to store as last image
                } else if (saveUri == null) {
                    applicationInterface.addLastImage(picFile!!, shareImage)
                } else if (storageUtils.isUsingSAF) {
                    applicationInterface.addLastImageSAF(saveUri, shareImage)
                } else if (useMediaStore) {
                    applicationInterface.addLastImageMediaStore(saveUri, shareImage)
                }

                val hasnoexifdatetime =
                    request.removeDeviceExif != Request.RemoveDeviceExif.OFF && request.removeDeviceExif != Request.RemoveDeviceExif.KEEP_DATETIME

                if (picFile != null && saveUri == null) {
                    storageUtils.broadcastFile(
                        picFile,
                        true,
                        false,
                        updateThumbnail,
                        hasnoexifdatetime,
                        null
                    )
                    mainActivity.testLastSavedImage = picFile.absolutePath
                }

                if (request.imageCaptureIntent) {
                    if (MyDebug.LOG) Log.d(TAG, "finish activity due to being called from intent")
                    mainActivity.setResult(Activity.RESULT_OK)
                    mainActivity.finish()
                }

                if (saveUri != null) {
                    success = true

                    if (useMediaStore) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentValues!!.clear()
                            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                            mainActivity.contentResolver.update(saveUri, contentValues, null, null)
                        }

                        if (!request.imageCaptureIntent) {
                            if (MyDebug.LOG) Log.d(TAG, "announce mediastore uri")
                            storageUtils.announceUri(saveUri, true, false)
                            if (updateThumbnail) {
                                storageUtils.setLastMediaScanned(
                                    saveUri,
                                    true,
                                    hasnoexifdatetime,
                                    saveUri
                                )
                            }
                        }
                    } else {
                        broadcastSAFFile(
                            saveUri,
                            updateThumbnail,
                            hasnoexifdatetime,
                            request.imageCaptureIntent
                        )
                    }

                    mainActivity.testLastSavedImageuri = saveUri
                }
            }
        } catch (e: FileNotFoundException) {
            MyDebug.logStackTrace(TAG, "file not found", e)
            mainActivity.preview.showToast(null, R.string.failed_to_save_photo)
        } catch (e: IOException) {
            MyDebug.logStackTrace(TAG, "I/O error writing file", e)
            mainActivity.preview.showToast(null, R.string.failed_to_save_photo)
        } catch (e: SecurityException) {
            MyDebug.logStackTrace(TAG, "security exception writing file", e)
            mainActivity.preview.showToast(null, R.string.failed_to_save_photo)
        }

        if (success && mainActivity.preview.cameraController != null && updateThumbnail) {
            mainActivity.preview.cameraController?.let { controller ->
                val size = controller.pictureSize
                val ratio =
                    Math.ceil(size.width.toDouble() / mainActivity.preview.view.width).toInt()
                var sampleSize = Integer.highestOneBit(ratio)
                sampleSize *= request.sampleFactor
                if (sampleSize < 1) sampleSize = 1
                if (MyDebug.LOG) {
                    Log.d(TAG, "    picture width: ${size.width}")
                    Log.d(TAG, "    preview width: ${mainActivity.preview.view.width}")
                    Log.d(TAG, "    ratio        : $ratio")
                    Log.d(TAG, "    sample_size  : $sampleSize")
                }
                var thumbnail: Bitmap?
                if (bitmapVar == null) {
                    val options = BitmapFactory.Options().apply {
                        inMutable = false
                        inSampleSize = sampleSize
                    }
                    thumbnail = BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    if (MyDebug.LOG) {
                        Log.d(TAG, "thumbnail width: ${thumbnail?.width}")
                        Log.d(TAG, "thumbnail height: ${thumbnail?.height}")
                    }
                    if (MyDebug.LOG) Log.d(TAG, "rotate thumbnail for exif tags?")
                    thumbnail = ImageUtils.rotateForExif(thumbnail, data)
                } else {
                    val width = bitmapVar.width
                    val height = bitmapVar.height
                    val matrix = Matrix()
                    val scale = 1.0f / sampleSize.toFloat()
                    matrix.postScale(scale, scale)
                    if (MyDebug.LOG) Log.d(TAG, "    scale: $scale")
                    try {
                        thumbnail =
                            Bitmap.createBitmap(bitmapVar, 0, 0, width, height, matrix, true)
                        if (MyDebug.LOG) {
                            Log.d(TAG, "thumbnail width: ${thumbnail.width}")
                            Log.d(TAG, "thumbnail height: ${thumbnail.height}")
                        }
                    } catch (e: IllegalArgumentException) {
                        MyDebug.logStackTrace(
                            TAG,
                            "can't create thumbnail bitmap due to IllegalArgumentException?!",
                            e
                        )
                        thumbnail = null
                    }
                }
                if (thumbnail == null) {
                    if (MyDebug.LOG) Log.e(TAG, "failed to create thumbnail bitmap")
                } else {
                    val thumbnailF = thumbnail
                    mainActivity.runOnUiThread {
                        applicationInterface.updateThumbnail(thumbnailF, false)
                    }
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "Save single image performance: time after creating thumbnail: ${System.currentTimeMillis() - timeS}"
                        )
                    }
                }
            }
        }

        mainActivity.savingImage(false)
        return success
    }

    private fun broadcastSAFFile(
        saveUri: Uri,
        updateThumbnail: Boolean,
        hasNoExifDateTime: Boolean,
        isImageCaptureIntent: Boolean
    ) {
        if (MyDebug.LOG) Log.d(TAG, "broadcastSAFFile: $saveUri")
        val storageUtils = mainActivity.storageUtils
        val file = storageUtils.getFileFromDocumentUriSAF(saveUri, false)
        if (MyDebug.LOG) Log.d(TAG, "file for SAF is: $file")
        if (file != null) {
            storageUtils.broadcastFile(
                file,
                true,
                false,
                updateThumbnail,
                hasNoExifDateTime,
                saveUri
            )
            mainActivity.testLastSavedImage = file.absolutePath
        } else {
            if (!isImageCaptureIntent) {
                storageUtils.announceUri(saveUri, true, false)
                if (updateThumbnail) {
                    storageUtils.setLastMediaScanned(saveUri, true, hasNoExifDateTime, saveUri)
                }
            }
        }
    }

    private fun saveImageNowRaw(request: Request): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "saveImageNowRaw")

        if (request.type != Request.Type.RAW) {
            if (MyDebug.LOG) Log.d(TAG, "saveImageNowRaw called with non-raw request")
            throw RuntimeException()
        }
        val rawImage = request.rawImage ?: run {
            if (MyDebug.LOG) Log.d(TAG, "saveImageNowRaw called with no raw_image")
            throw RuntimeException()
        }

        var success = false
        val applicationInterface = mainActivity.applicationInterface
        val rawOnly = applicationInterface.isRawOnly
        if (MyDebug.LOG) Log.d(TAG, "raw_only: $rawOnly")

        val storageUtils = mainActivity.storageUtils
        mainActivity.savingImage(true)

        var picFile: File? = null
        var saveUri: Uri? = null
        var useMediaStore = false
        var contentValues: ContentValues? = null
        var output: OutputStream? = null

        try {
            if (storageUtils.isUsingSAF) {
                saveUri = storageUtils.createOutputMediaFileSAF(
                    StorageUtils.MEDIA_TYPE_IMAGE,
                    "",
                    "dng",
                    request.currentDate
                )
            } else if (MainActivity.useScopedStorage()) {
                if (MyDebug.LOG) Log.d(TAG, "use media store for raw")
                useMediaStore = true
                val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                contentValues = ContentValues()
                val picName = storageUtils.createMediaFilename(
                    StorageUtils.MEDIA_TYPE_IMAGE,
                    "",
                    0,
                    ".dng",
                    request.currentDate
                )
                if (MyDebug.LOG) Log.d(TAG, "picName: $picName")
                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, picName)
                contentValues.put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relativePath = storageUtils.saveRelativeFolder
                    if (MyDebug.LOG) Log.d(TAG, "relative_path: $relativePath")
                    contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                saveUri = mainActivity.contentResolver.insert(folder, contentValues)
                if (MyDebug.LOG) Log.d(TAG, "saveUri: $saveUri")
            } else {
                picFile = storageUtils.createOutputMediaFile(
                    StorageUtils.MEDIA_TYPE_IMAGE,
                    "",
                    "dng",
                    request.currentDate
                )
                if (MyDebug.LOG) Log.d(TAG, "save to: " + picFile.absolutePath)
            }

            if (saveUri != null || picFile != null) {
                output =
                    if (picFile != null) FileOutputStream(picFile) else mainActivity.contentResolver.openOutputStream(
                        saveUri!!
                    )
                rawImage.writeImage(output!!)
                if (MyDebug.LOG) Log.d(TAG, "saveImageNowRaw saved raw photo")

                if (saveUri == null) {
                    success = true
                }

                val updateThumbnail = rawOnly
                val shareImage = rawOnly

                if (rawOnly) {
                    if (saveUri == null) {
                        applicationInterface.addLastImage(picFile!!, shareImage)
                    } else if (storageUtils.isUsingSAF) {
                        applicationInterface.addLastImageSAF(saveUri, shareImage)
                    } else if (useMediaStore) {
                        applicationInterface.addLastImageMediaStore(saveUri, shareImage)
                    }
                }

                val hasnoexifdatetime =
                    request.removeDeviceExif != Request.RemoveDeviceExif.OFF && request.removeDeviceExif != Request.RemoveDeviceExif.KEEP_DATETIME

                if (picFile != null && saveUri == null) {
                    storageUtils.broadcastFile(
                        picFile,
                        true,
                        true,
                        updateThumbnail,
                        hasnoexifdatetime,
                        null
                    )
                    if (rawOnly) {
                        mainActivity.testLastSavedImage = picFile.absolutePath
                    }
                }

                if (saveUri != null) {
                    success = true

                    if (useMediaStore) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentValues!!.clear()
                            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                            mainActivity.contentResolver.update(saveUri, contentValues, null, null)
                        }

                        if (MyDebug.LOG) Log.d(TAG, "announce mediastore uri")
                        storageUtils.announceUri(saveUri, true, true)
                        if (updateThumbnail) {
                            storageUtils.setLastMediaScanned(
                                saveUri,
                                true,
                                hasnoexifdatetime,
                                saveUri
                            )
                        }
                    } else {
                        storageUtils.broadcastUri(
                            saveUri,
                            true,
                            false,
                            rawOnly,
                            hasnoexifdatetime,
                            false
                        )
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            MyDebug.logStackTrace(TAG, "file not found", e)
            mainActivity.preview.showToast(null, R.string.failed_to_save_photo_raw)
        } catch (e: IOException) {
            MyDebug.logStackTrace(TAG, "ioexception writing raw image file", e)
            mainActivity.preview.showToast(null, R.string.failed_to_save_photo_raw)
        } finally {
            if (output != null) {
                try {
                    output.close()
                } catch (e: IOException) {
                    MyDebug.logStackTrace(TAG, "ioexception closing raw output", e)
                }
            }
            rawImage.close()
        }

        System.gc()
        mainActivity.savingImage(false)
        return success
    }

    fun getPostProcessing(): PostProcessing {
        return this.postProcessing
    }

    fun getHDRProcessor(): HDRProcessor {
        return hdrProcessor
    }

    fun getPanoramaProcessor(): PanoramaProcessor {
        return panoramaProcessor
    }

    /** Alternative to android.util.Range&lt;Integer&gt;, since that is not mocked so can't be used
     * in unit testing.
     */
    data class IntRange(val lower: Int, val upper: Int) {
        init {
            if (lower > upper) {
                throw IllegalArgumentException("lower must be <= upper")
            }
        }

        internal constructor(range: Range<Int>) : this(range.lower, range.upper)

        fun contains(value: Int): Boolean {
            return value in lower..upper
        }

        fun clamp(value: Int): Int {
            if (value <= lower) return lower
            else if (value >= upper) return upper
            return value
        }
    }

    companion object {
        private const val TAG = "ImageSaver"

        const val HDR_SUFFIX = "_HDR"
        const val NR_SUFFIX = "_NR"
        const val PANO_SUFFIX = "_PANO"

        private const val QUEUE_COST_JPEG_C = 1
        private const val QUEUE_COST_DNG_C = 6

        @JvmField
        @Volatile
        var testSmallQueueSize: Boolean = false

        @JvmStatic
        fun computeQueueSize(largeHeapMemoryInput: Int): Int {
            var largeHeapMemory = largeHeapMemoryInput
            if (MyDebug.LOG) Log.d(TAG, "large max memory = ${largeHeapMemory}MB")
            val maxQueueSize: Int
            if (MyDebug.LOG) Log.d(TAG, "test_small_queue_size?: $testSmallQueueSize")
            if (testSmallQueueSize) {
                largeHeapMemory = 0
            }

            if (largeHeapMemory >= 512) {
                maxQueueSize = 34
            } else if (largeHeapMemory >= 256) {
                maxQueueSize = 12
            } else if (largeHeapMemory >= 128) {
                maxQueueSize = 8
            } else {
                maxQueueSize = 6
            }
            if (MyDebug.LOG) Log.d(TAG, "max_queue_size = $maxQueueSize")
            return maxQueueSize
        }

        @JvmStatic
        fun computeRequestCost(isRaw: Boolean, nImages: Int): Int {
            if (MyDebug.LOG) {
                Log.d(TAG, "computeRequestCost")
                Log.d(TAG, "is_raw: $isRaw")
                Log.d(TAG, "n_images: $nImages")
            }
            val cost = if (isRaw) nImages * QUEUE_COST_DNG_C else nImages * QUEUE_COST_JPEG_C
            return cost
        }

        @JvmStatic
        fun getHDRAlpha(
            preferenceHdrContrastEnhancement: String?,
            exposureTime: Long,
            nBitmaps: Int
        ): Float {
            val useHdrAlpha: Boolean = if (nBitmaps == 1) {
                true
            } else {
                when (preferenceHdrContrastEnhancement) {
                    "preference_hdr_contrast_enhancement_off" -> false
                    "preference_hdr_contrast_enhancement_always" -> true
                    "preference_hdr_contrast_enhancement_smart" -> exposureTime < 1000000000L / 59
                    else -> exposureTime < 1000000000L / 59
                }
            }
            val hdrAlpha = if (useHdrAlpha) 0.5f else 0.0f
            if (MyDebug.LOG) {
                Log.d(TAG, "preference_hdr_contrast_enhancement: $preferenceHdrContrastEnhancement")
                Log.d(TAG, "exposure_time: $exposureTime")
                Log.d(TAG, "hdr_alpha: $hdrAlpha")
            }
            return hdrAlpha
        }

        private const val GYRO_INFO_DOC_TAG = "open_camera_gyro_info"
        private const val GYRO_INFO_PANORAMA_PICS_PER_SCREEN_TAG = "panorama_pics_per_screen"
        private const val GYRO_INFO_CAMERA_VIEW_ANGLE_X_TAG = "camera_view_angle_x"
        private const val GYRO_INFO_CAMERA_VIEW_ANGLE_Y_TAG = "camera_view_angle_y"
        private const val GYRO_INFO_IMAGE_TAG = "image"
        private const val GYRO_INFO_VECTOR_TAG = "vector"
        private const val GYRO_INFO_VECTOR_RIGHT_TYPE = "X"
        private const val GYRO_INFO_VECTOR_UP_TYPE = "Y"
        private const val GYRO_INFO_VECTOR_SCREEN_TYPE = "Z"

        @JvmStatic
        fun readGyroDebugXml(inputStream: InputStream, info: GyroDebugInfo): Boolean {
            try {
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(inputStream, null)
                parser.nextTag()

                parser.require(XmlPullParser.START_TAG, null, GYRO_INFO_DOC_TAG)
                var imageInfo: GyroDebugInfo.GyroImageDebugInfo? = null

                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> {
                            val name = parser.name
                            if (MyDebug.LOG) {
                                Log.d(TAG, "start tag, name: $name")
                            }

                            when (name) {
                                GYRO_INFO_IMAGE_TAG -> {
                                    imageInfo = GyroDebugInfo.GyroImageDebugInfo()
                                    info.imageInfo.add(imageInfo)
                                }

                                GYRO_INFO_VECTOR_TAG -> {
                                    if (imageInfo == null) {
                                        Log.e(TAG, "vector tag outside of image tag")
                                        return false
                                    }
                                    val type = parser.getAttributeValue(null, "type")
                                    val xS = parser.getAttributeValue(null, "x")
                                    val yS = parser.getAttributeValue(null, "y")
                                    val zS = parser.getAttributeValue(null, "z")
                                    val vector = FloatArray(3)
                                    vector[0] = xS.toFloat()
                                    vector[1] = yS.toFloat()
                                    vector[2] = zS.toFloat()
                                    when (type) {
                                        GYRO_INFO_VECTOR_RIGHT_TYPE -> imageInfo.vectorRight =
                                            vector

                                        GYRO_INFO_VECTOR_UP_TYPE -> imageInfo.vectorUp = vector
                                        GYRO_INFO_VECTOR_SCREEN_TYPE -> imageInfo.vectorScreen =
                                            vector

                                        else -> {
                                            Log.e(TAG, "unknown type in vector tag: $type")
                                            return false
                                        }
                                    }
                                }
                            }
                        }

                        XmlPullParser.END_TAG -> {
                            val name = parser.name
                            if (MyDebug.LOG) {
                                Log.d(TAG, "end tag, name: $name")
                            }

                            when (name) {
                                GYRO_INFO_IMAGE_TAG -> {
                                    imageInfo = null
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                MyDebug.logStackTrace(TAG, "failed to parse xml", e)
                return false
            } finally {
                try {
                    inputStream.close()
                } catch (e: IOException) {
                    MyDebug.logStackTrace(TAG, "failed to close inputStream", e)
                }
            }
            return true
        }

        private fun getBitmapCompressFormat(imageFormat: Request.ImageFormat): CompressFormat {
            return when (imageFormat) {
                Request.ImageFormat.WEBP -> CompressFormat.WEBP
                Request.ImageFormat.PNG -> CompressFormat.PNG
                else -> CompressFormat.JPEG
            }
        }
    }

    @Throws(IOException::class)
    private fun savePanoramaBitmap(
        bitmap: Bitmap,
        compressFormat: CompressFormat,
        quality: Int,
        nPics: Int,
        outputStream: OutputStream
    ) {
        val jpegStream = ByteArrayOutputStream()
        bitmap.compress(compressFormat, quality, jpegStream)
        val jpegData = jpegStream.toByteArray()

        if (jpegData[0] != 0xFF.toByte() || jpegData[1] != 0xD8.toByte()) {
            if (MyDebug.LOG) Log.d(TAG, "invalid jpeg header, skip adding panorama xmp")
            outputStream.write(jpegData, 0, jpegData.size)
            return
        }

        val width = bitmap.width
        val height = bitmap.height

        val cameraAngleY = mainActivity.preview.getViewAngleY(false)
        val anglePerPic = cameraAngleY / MyApplicationInterface.panoramaPicsPerScreen
        val totalAngle = cameraAngleY + anglePerPic * (nPics - 1)
        val nPicsFor360 = 360.0f / totalAngle
        val fullWidth = (width * nPicsFor360 + 0.5f).toInt()

        var fullHeight = fullWidth / 2
        fullHeight = max(fullHeight, height)

        val croppedLeft = (fullWidth - width) / 2
        val croppedTop = (fullHeight - height) / 2
        if (MyDebug.LOG) {
            Log.d(TAG, "camera_angle_y: $cameraAngleY")
            Log.d(TAG, "angle_per_pic: $anglePerPic")
            Log.d(TAG, "total_angle: $totalAngle")
            Log.d(TAG, "n_pics_for_360: $nPicsFor360")
            Log.d(TAG, "width: $width")
            Log.d(TAG, "full_width: $fullWidth")
            Log.d(TAG, "height: $height")
            Log.d(TAG, "full_height: $fullHeight")
            Log.d(TAG, "cropped_left: $croppedLeft")
            Log.d(TAG, "cropped_top: $croppedTop")
        }

        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
                " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "  <rdf:Description xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\"" +
                "    GPano:ProjectionType=\"equirectangular\"" +
                "    GPano:FullPanoWidthPixels=\"$fullWidth\"" +
                "    GPano:FullPanoHeightPixels=\"$fullHeight\"" +
                "    GPano:CroppedAreaImageWidthPixels=\"$width\"" +
                "    GPano:CroppedAreaImageHeightPixels=\"$height\"" +
                "    GPano:CroppedAreaLeftPixels=\"$croppedLeft\"" +
                "    GPano:CroppedAreaTopPixels=\"$croppedTop\"" +
                "/>" +
                " </rdf:RDF>" +
                "</x:xmpmeta>"

        val xmpPacket = "http://ns.adobe.com/xap/1.0/\u0000$xmp"
        val xmpBytes = xmpPacket.toByteArray(StandardCharsets.UTF_8)
        val segmentLength = xmpBytes.size + 2

        // jpeg header
        outputStream.write(0xFF)
        outputStream.write(0xD8)

        // XMP segment
        outputStream.write(0xFF)
        outputStream.write(0xE1) // APP1
        outputStream.write((segmentLength shr 8) and 0xFF)
        outputStream.write(segmentLength and 0xFF)
        outputStream.write(xmpBytes)

        // rest of JPEG data (skip original SOI)
        outputStream.write(jpegData, 2, jpegData.size - 2)
    }
}
