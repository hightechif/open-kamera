/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.CameraExtensionSession.ExtensionCaptureCallback
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.camera2.params.TonemapCurve
import android.location.Location
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Pair
import android.util.Range
import android.util.SizeF
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import androidx.annotation.IntRange
import androidx.annotation.RequiresApi
import com.hightechif.openkamera.cameracontroller.burst.Camera2CaptureCoordinator
import com.hightechif.openkamera.cameracontroller.burst.FocusBracketingCalculator
import com.hightechif.openkamera.cameracontroller.focus.Camera2FocusMeteringCoordinator
import com.hightechif.openkamera.cameracontroller.focus.MeteringAreaConverter
import com.hightechif.openkamera.cameracontroller.pipeline.Camera2ImageReaderPipeline
import com.hightechif.openkamera.cameracontroller.pipeline.ImageReaderConfig
import com.hightechif.openkamera.processing.HDRProcessor
import com.hightechif.openkamera.utils.MyDebug
import java.util.Collections
import java.util.Hashtable
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Provides support using Android 5's Camera 2 API
 * android.hardware.camera2.*.
 */
class CameraController2(
    context: Context,
    cameraId: Int,
    cameraIdSPhysical: String?,
    cameraFeaturesCaches: Map<String, CameraFeaturesCache>,
    previewErrorCb: ErrorCallback,
    cameraErrorCb: ErrorCallback
) : CameraController(cameraId) {

    private val context: Context

    // used to improve performance for subsequent CameraController2 objects; key is the cameraIdS string, value is a CameraFeaturesCache object
    private val cameraFeaturesCaches: MutableMap<String, CameraFeaturesCache>
    private var camera: CameraDevice? = null
    private var cameraIdS: String // ID string of logical camera
    private val cameraIdSPhysical: String? // if non-null, ID string of underlying physical camera

    private val isSamsung: Boolean
    private val isSamsungS7: Boolean // Galaxy S7 or Galaxy S7 Edge
    private val isSamsungGalaxyS: Boolean
    private val isSamsungGalaxyF: Boolean // Galaxy fold or flip series

    // characteristics of camera - if a specific physical camera is being used, these are characteristics for the physical camera
    private var characteristics: CameraCharacteristics? = null
    private var extensionCharacteristics: CameraExtensionCharacteristics? = null

    // if non-null, this is the cache obtained from cameraFeaturesCaches
    private var cameraFeaturesCache: CameraFeaturesCache? = null

    // cached for performance, as this method is frequently called from Preview.onOrientationChanged
    // cached characteristics (use this for values that need to be frequently accessed, e.g., per frame, to improve performance);
    override var cameraOrientation: Int = 0
        private set

    // cached for performance, as this method is frequently called from Preview.onOrientationChanged
    override var facing: Facing? = null
        private set

    // camera features that we save (either to avoid repeatedly accessing, or we do our own modification)
    private var fullZoomRatios: List<Int>? = null
    private var zoomRatios: List<Int>? = null
    private var currentZoomValue = 0
    private var zoomValue1x = 0 // index into zoomRatios list that is for zoom 1x

    // if non-null, list of camera vendor extensions that support zoom
    private var supportedExtensionsZoom: List<Int>? = null
    private var supportsFaceDetectModeSimple = false
    private var supportsFaceDetectModeFull = false
    private var supportsOpticalStabilization = false
    private var supportsPhotoVideoRecording = false
    private var supportsWhiteBalanceTemperature = false

    // if non-null, focus mode to use if not set by Preview (rather than relying on the Builder template's default, which can be one that isn't supported, at least on Android emulator with its LIMITED camera!)
    private var initialFocusMode: String? = null
    private var supportsExposureTime = false
    private var minExposureTime: Long = 0
    private var maxExposureTime: Long = 0
    private var minimumFocusDistance = 0f // for manual focus

    private var supportsTonemapPresetCurve = false
    val jtvideoValues: FloatArray
    val jtlogValues: FloatArray
    val jtlog2Values: FloatArray

    private val previewErrorCb: ErrorCallback
    private val cameraErrorCb: ErrorCallback

    private enum class SessionType {
        SESSIONTYPE_NORMAL,  // standard use of Camera2 API, via CameraCaptureSession
        SESSIONTYPE_EXTENSION,  // use of vendor extension, via CameraExtensionSession
    }

    private var sessionType: SessionType = SessionType.SESSIONTYPE_NORMAL

    //private SessionType sessionType = SessionType.SESSIONTYPE_EXTENSION; // test
    // used if sessionType == SESSIONTYPE_NORMAL
    private var captureSession: CameraCaptureSession? = null

    // used if sessionType == SESSIONTYPE_EXTENSION
    private var extensionSession: CameraExtensionSession? = null
    private var cameraExtension = 0 // used if sessionType == SESSIONTYPE_EXTENSION

    private var previewBuilder: CaptureRequest.Builder? = null
    var previewIsVideoMode = false
    val focusMeteringCoordinator = Camera2FocusMeteringCoordinator()
    private var autofocusCb: AutoFocusCallback?
        get() = focusMeteringCoordinator.getAutofocusCallback()
        set(value) {
            if (value != null) {
                focusMeteringCoordinator.startAutofocusTracking(value, captureFollowsAutofocusHint)
            } else {
                focusMeteringCoordinator.resetAutofocusTracking()
            }
        }
    private var autofocusTimeMs: Long
        get() = focusMeteringCoordinator.autofocusTimeMs
        set(value) {
            // Managed via focusMeteringCoordinator
        }
    private var captureFollowsAutofocusHint: Boolean
        get() = focusMeteringCoordinator.captureFollowsAutofocusHint
        set(value) {
            focusMeteringCoordinator.setCaptureFollowsAutofocusHint(value)
        }
    private var readyForCapture = false
    private var faceDetectionListener: FaceDetectionListener? = null
    private var lastFacesDetected = -1

    // lock to wait for camera to be opened from CameraDevice.StateCallback
    private val openCameraLock = Any()

    // lock to synchronize between UI thread and the background "CameraBackground" thread/handler
    private val backgroundCameraLock = Any()

    val imageReaderPipeline = Camera2ImageReaderPipeline()
    private val imageReader: ImageReader?
        get() = imageReaderPipeline.imageReaderJpeg
    private val imageReaderRaw: ImageReader?
        get() = imageReaderPipeline.imageReaderRaw
    private var onImageAvailableListener: OnImageAvailableListener? = null

    val captureCoordinator = Camera2CaptureCoordinator(MAX_EXPO_BRACKETING_N_IMAGES)

    private val expoBracketingNImages: Int
        get() = captureCoordinator.expoBracketingNImages

    private val expoBracketingStops: Double
        get() = captureCoordinator.expoBracketingStops

    private val useExpoFastBurst: Boolean
        get() = captureCoordinator.useExpoFastBurst

    // for BURSTTYPE_FOCUS:
    // whether focus bracketing in progress; set back to 'false' to cancel
    private var focusBracketingInProgress: Boolean
        get() = captureCoordinator.focusBracketingInProgress
        set(value) {
            captureCoordinator.focusBracketingInProgress = value
        }

    private val focusBracketingNImages: Int
        get() = captureCoordinator.focusBracketingNImages

    private val focusBracketingAddInfinity: Boolean
        get() = captureCoordinator.focusBracketingAddInfinity

    // for BURSTTYPE_NORMAL:
    // chooses number of burst images and other settings for Open Kamera's noise reduction (NR) photo mode
    private val burstForNoiseReduction: Boolean
        get() = captureCoordinator.burstForNoiseReduction

    // if burstForNoiseReduction==true, whether to optimize for low light scenes
    private val noiseReductionLowLight: Boolean
        get() = captureCoordinator.noiseReductionLowLight

    // if burstForNoiseReduction==false, this gives the number of images for the burst
    private var burstRequestedNImages: Int
        get() = captureCoordinator.burstRequestedNImages
        set(value) {
            captureCoordinator.burstRequestedNImages = value
        }

    //for BURSTTYPE_CONTINUOUS:
    // whether we're currently taking a continuous burst
    override var isContinuousBurstInProgress: Boolean
        get() = captureCoordinator.isContinuousBurstInProgress
        private set(value) {
            captureCoordinator.isContinuousBurstInProgress = value
        }

    // whether we've requested the last capture
    private var continuousBurstRequestedLastCapture: Boolean
        get() = captureCoordinator.continuousBurstRequestedLastCapture
        set(value) {
            captureCoordinator.continuousBurstRequestedLastCapture = value
        }

    // Whether to enable a workaround hack for some Galaxy devices - take an additional dummy photo
    // when taking an expo/HDR burst, to avoid problem where manual exposure is ignored for the
    // first image.
    private val dummyCaptureHack: Boolean
        get() = captureCoordinator.dummyCaptureHack

    //private boolean dummyCaptureHack = true; // test
    private var wantJpegR = false
    private var wantRaw = false
    val isWantRaw: Boolean
        get() = wantRaw

    //private boolean wantRaw = true;
    private var maxRawImages = 0
    private var rawSize: android.util.Size? = null
    private var onRawImageAvailableListener: OnRawImageAvailableListener? = null
    private var pictureCb: PictureCallback? = null
    private var jpegTodo = false // whether we are still waiting for JPEG images
    private var rawTodo = false // whether we are still waiting for RAW images

    // whether we've received the capture for the image (or all images if a burst)
    private var doneAllCaptures = false

    //private CaptureRequest pendingRequestWhenReady;
    private var nBurst = 0 // number of expected (remaining) burst JPEG images in this capture
    override var nBurstTaken: Int = 0 // number of burst JPEG images taken so far in this capture
        private set

    // total number of expected burst images in this capture (if known) (same for JPEG and RAW)
    private var nBurstTotal = 0
    private var nBurstRaw = 0 // number of expected (remaining) burst RAW images in this capture

    // if true then the burst images are returned in a single call to onBurstPictureTaken(), if false, then multiple calls to onPictureTaken() are made as soon as the image is available
    private var burstSingleRequest = false

    // burst images that have been captured so far, but not yet sent to the application
    private val pendingBurstImages: MutableList<ByteArray?> = ArrayList()
    private val pendingBurstImagesRaw: MutableList<RawImage> = ArrayList()

    // the set of burst capture requests - used when not using captureBurst() (e.g., when useExpoFastBurst==false, or for focus bracketing)
    private var slowBurstCaptureRequests: MutableList<CaptureRequest> = mutableListOf()

    // time when burst started (used for measuring performance of captures when not using captureBurst())
    private var slowBurstStartMs: Long = 0

    // used to ensure that when taking JPEG+RAW, the JPEG picture callback is called first (only used for non-burst cases)
    private var pendingRawImage: RawImage? = null
    private var takePictureErrorCb: ErrorCallback? = null
    private var wantVideoHighSpeed = false
    private var isVideoHighSpeed = false // whether we're actually recording in high speed
    private var aeFpsRanges = mutableListOf<IntArray>()
    private var hsFpsRanges = mutableListOf<IntArray>()

    //private ImageReader previewImageReader;
    private var texture: SurfaceTexture? = null

    // should synchronize calls to this method using backgroundCameraLock
    private lateinit var _surfaceTexture: Surface
    private val _previewSurface: Surface
        get() = _surfaceTexture
    private var thread: HandlerThread?
    private var handler: Handler?
    private var executor: Executor?
    private var videoRecorderSurface: Surface? = null

    private var previewWidth = 0
    private var previewHeight = 0

    private var pictureWidth = 0
    private var pictureHeight = 0

    private var state = STATE_NORMAL

    // time we changed state for precapture modes
    private var precaptureStateChangeTimeMs: Long = -1

    // see CameraController.setUseCamera2FakeFlash() for details - this is the user/application setting, see useFakePrecaptureMode for whether fake precapture is enabled (as we may do this for other purposes, e.g., front screen flash)
    private var useFakePrecapture = false

    // true if either useFakePrecapture is true, or we're temporarily using fake precapture mode (e.g., for front screen flash or exposure bracketing)
    private var useFakePrecaptureMode = false

    // whether we turned on torch to do a fake precapture
    private var fakePrecaptureTorchPerformed = false

    // whether we turned on torch to do an autofocus, in fake precapture mode
    private var fakePrecaptureTorchFocusPerformed = false

    // whether we decide to use flash in auto mode (if fakePrecaptureUseAutoflashTimeMs != -1)
    private var fakePrecaptureUseFlash = false

    // when we last checked to use flash in auto mode
    private var fakePrecaptureUseFlashTimeMs: Long = -1

    private val mediaActionSound = MediaActionSound()
    private val shutterClickSound: Int // which sound to use for shutter click
    private var soundsEnabled = true

    private var hasReceivedFrame = false
    private var captureResultIsAeScanning = false
    private var captureResultAe: Int? = null // latest aeState, null if not available

    // whether captureResultAe suggests FLASH_REQUIRED? Or in neither FLASH_REQUIRED nor CONVERGED, this stores the last known result
    private var isFlashRequired = false
    private var modifiedFromCameraSettings = false

    // if modifiedFromCameraSettings set to true, then we've temporarily requested captures with settings such as
    // exposure modified from the normal ones in cameraSettings
    private var captureResultHasWhiteBalanceRggb = false
    private var captureResultWhiteBalanceRggb: RggbChannelVector? = null
    private var captureResultHasIso = false
    private var captureResultIso = 0
    private var captureResultHasExposureTime = false
    private var captureResultExposureTime: Long = 0
    private var captureResultHasFrameDuration = false
    private var captureResultFrameDuration: Long = 0
    private var captureResultHasFocusDistance = false
    private var captureResultFocusDistance = 0f
    private var captureResultHasAperture = false
    private var captureResultAperture = 0f

    private fun resetCaptureResultInfo() {
        captureResultIsAeScanning = false
        captureResultAe = null
        isFlashRequired = false
        captureResultHasWhiteBalanceRggb = false
        captureResultHasIso = false
        captureResultHasExposureTime = false
        captureResultHasFrameDuration = false
        captureResultHasFocusDistance = false
        captureResultHasAperture = false
    }

    /* Callback to be called when we receive a capture with tag RUN_POST_CAPTURE.
     */
    private abstract class PostCapture {
        @Throws(CameraAccessException::class)
        abstract fun call()
    }

    private var runPostCapture: PostCapture? = null

    enum class RequestTagType {
        RUN_POST_CAPTURE,  // calls run_post_capture.call(), , if runPostCapture!=null
        CAPTURE,  // request is either for a regular non-burst capture, or the last of a burst capture sequence
        CAPTURE_BURST_IN_PROGRESS // request is for a burst capture, but isn't the last of the burst capture sequence
        //NONE // should be treated the same as if no tag had been set on the request - but allows the request tag type to be changed later
    }

    /* The class that we use for setTag() and getTag() for capture requests.
       We use this class instead of assigning the RequestTagType directly, so we can modify it
       (even though CaptureRequest only has a getTag() method).
     */
    private class RequestTagObject(type: RequestTagType) {
        private var type: RequestTagType

        init {
            this.type = type
        }

        fun getType(): RequestTagType {
            return type
        }

        fun setType(type: RequestTagType) {
            this.type = type
        }
    }


    private fun hasCaptureSession(): Boolean {
        if (sessionType == SessionType.SESSIONTYPE_EXTENSION) return extensionSession != null
        return captureSession != null
    }

    private fun blockForExtensions() {
        if (sessionType == SessionType.SESSIONTYPE_EXTENSION) {
            throw RuntimeException("not supported for extension session")
        }
    }

    /** Issues the next slow burst capture, on a post delayed on the handler.
     */
    private fun postNextSlowBurst() {
        if (MyDebug.LOG) Log.d(TAG, "postNextSlowBurst")
        handler?.postDelayed(object : Runnable {
            override fun run() {
                if (MyDebug.LOG) Log.d(TAG, "take picture after delay for next slow burst")
                if (camera != null && hasCaptureSession()) { // make sure camera wasn't released in the meantime
                    // check for imageQueueWouldBlock needed for focus bracketing
                    if (pictureCb?.imageQueueWouldBlock(
                            if (imageReaderRaw != null) 1 else 0,
                            1
                        ) == true
                    ) {
                        if (MyDebug.LOG) {
                            Log.d(TAG, "...but wait for next bracket, as image queue would block")
                        }
                        handler?.postDelayed(this, 100)
                        //throw new RuntimeException(); // test
                    } else {
                        if (burstType === BurstType.BURSTTYPE_FOCUS) {
                            // For focus bracketing mode, we play the shutter sound per shot (so the user can tell when the sequence is complete).
                            // From a user mode, the gap between shots in focus bracketing mode makes this more analogous to the auto-repeat mode
                            // (at the Preview level), which makes the shutter sound per shot.

                            playSound(shutterClickSound)
                        }
                        try {
                            captureSession?.capture(
                                slowBurstCaptureRequests[nBurstTaken],
                                previewCaptureCallback,
                                handler
                            )
                        } catch (e: CameraAccessException) {
                            if (MyDebug.LOG) {
                                Log.e(TAG, "failed to take next focus bracket")
                                Log.e(TAG, "reason: " + e.reason)
                                Log.e(TAG, "message: " + e.message)
                            }
                            e.printStackTrace()
                            jpegTodo = false
                            rawTodo = false
                            pictureCb = null
                            if (takePictureErrorCb != null) {
                                takePictureErrorCb?.onError()
                                takePictureErrorCb = null
                            }
                        }
                    }
                }
            }
        }, 500)
    }

    private inner class OnImageAvailableListener : ImageReader.OnImageAvailableListener {
        var skipNextImage: Boolean =
            false // whether to ignore the next image (used for dummyCaptureHack)

        override fun onImageAvailable(reader: ImageReader) {
            if (MyDebug.LOG) Log.d(TAG, "new still image available")
            if (pictureCb == null || !jpegTodo) {
                // in theory this shouldn't happen - but if this happens, still free the image to avoid risk of memory leak,
                // or strange behavior where an old image appears when the user next takes a photo
                Log.e(TAG, "no picture callback available")
                val image = reader.acquireNextImage()
                image?.close()
                return
            }
            if (skipNextImage) {
                if (MyDebug.LOG) Log.d(TAG, "skipping image")
                skipNextImage = false
                val image = reader.acquireNextImage()
                image?.close()
                return
            }

            var singleBurstCompleteImages: List<ByteArray?>? = null
            var callTakePhotoPartial = false
            var callTakePhotoCompleted = false

            val image = reader.acquireNextImage()
            if (image == null) {
                // can happen if camera closed whilst taking photo - this happens in testTakePhotoAutoFocusReleaseDuringPhoto() on Pixel 6 Pro
                Log.e(TAG, "onImageAvailable: image is null")
                return
            }
            if (MyDebug.LOG) Log.d(TAG, "image timestamp: " + image.timestamp)
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            if (MyDebug.LOG) Log.d(TAG, "read " + bytes.size + " bytes")
            buffer[bytes]
            image.close()

            synchronized(backgroundCameraLock) {
                this@CameraController2.nBurstTaken++
                if (MyDebug.LOG) {
                    Log.d(TAG, "n_burst_taken is now: " + this@CameraController2.nBurstTaken)
                    Log.d(TAG, "n_burst: $nBurst")
                    Log.d(
                        TAG,
                        "burst_single_request: $burstSingleRequest"
                    )
                }
                if (burstSingleRequest) {
                    pendingBurstImages.add(bytes)
                    if (MyDebug.LOG) {
                        Log.d(TAG, "pending_burst_images size is now: " + pendingBurstImages.size)
                    }
                    if (pendingBurstImages.size >= nBurst) { // shouldn't ever be greater, but just in case
                        if (MyDebug.LOG) Log.d(TAG, "all burst images available")
                        if (pendingBurstImages.size > nBurst) {
                            Log.e(
                                TAG,
                                "pending_burst_images size " + pendingBurstImages.size + " is greater than n_burst " + nBurst
                            )
                        }
                        // take a copy, so that we can clear pendingBurstImages
                        singleBurstCompleteImages = ArrayList(pendingBurstImages)
                        // continued below after lock...
                    } else {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "number of burst images is now: " + pendingBurstImages.size
                        )
                        callTakePhotoPartial = true
                    }
                }
            }

            // need to call without a lock
            if (singleBurstCompleteImages != null) {
                pictureCb?.onBurstPictureTaken(
                    singleBurstCompleteImages.filterNotNull()
                )
            } else if (!burstSingleRequest) {
                pictureCb?.onPictureTaken(bytes)
            }

            synchronized(backgroundCameraLock) {
                if (singleBurstCompleteImages != null) {
                    pendingBurstImages.clear()

                    callTakePhotoCompleted = true
                } else if (!burstSingleRequest) {
                    nBurst--
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "n_burst is now $nBurst"
                    )
                    if (burstType === BurstType.BURSTTYPE_CONTINUOUS && !continuousBurstRequestedLastCapture) {
                        // even if nBurst is 0, we don't want to give up if we're still in continuous burst mode
                        // also note if we do have continuousBurstRequestedLastCapture==true, we still check for
                        // nBurst==0 below (as there may have been more than one image still to be received)
                        if (MyDebug.LOG) Log.d(TAG, "continuous burst mode still in progress")
                        callTakePhotoPartial = true
                    } else if (nBurst == 0) {
                        callTakePhotoCompleted = true
                    } else {
                        callTakePhotoPartial = true
                    }
                }
            }

            // need to call outside of lock (because they can lead to calls to external callbacks)
            if (callTakePhotoPartial) {
                takePhotoPartial()
            } else if (callTakePhotoCompleted) {
                takePhotoCompleted()
            }

            if (MyDebug.LOG) Log.d(TAG, "done onImageAvailable")
        }

        /** Called when an image has been received, but we're in a burst mode, and not all images have
         * been received.
         */
        fun takePhotoPartial() {
            if (MyDebug.LOG) Log.d(TAG, "takePhotoPartial")
            blockForExtensions() // not supported for extension sessions

            var pushTakePictureErrorCb: ErrorCallback? = null

            synchronized(backgroundCameraLock) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "need to execute the next capture")
                    Log.d(
                        TAG,
                        "time since start: " + (System.currentTimeMillis() - slowBurstStartMs)
                    )
                }
                if (burstType !== BurstType.BURSTTYPE_FOCUS) {
                    /*try {
                        if( camera != null && hasCaptureSession() ) { // make sure camera wasn't released in the meantime
                            captureSession.capture(slow_burst_capture_requests.get(nBurstTaken), previewCaptureCallback, handler);
                        }
                    }
                    catch(CameraAccessException e) {
                        if( MyDebug.LOG ) {
                            Log.e(TAG, "failed to take next burst");
                            Log.e(TAG, "reason: " + e.getReason());
                            Log.e(TAG, "message: " + e.getMessage());
                        }
                        e.printStackTrace();
                        jpegTodo = false;
                        rawTodo = false;
                        pictureCb = null;
                        pushTakePictureErrorCb = takePictureErrorCb;
                    }*/
                    // see note in takePictureBurstBracketing() for why we also set preview for slow burst with expo bracketing -
                    // helps Samsung Galaxy devices
                    if (previewBuilder != null && nBurstTaken >= 0 && nBurstTaken < slowBurstCaptureRequests.size) { // make sure camera wasn't released in the meantime
                        try {
                            val exposureTime =
                                slowBurstCaptureRequests[nBurstTaken].get(
                                    CaptureRequest.SENSOR_EXPOSURE_TIME
                                )
                            if (MyDebug.LOG) {
                                Log.d(
                                    TAG,
                                    "prepare preview for next exposure: $exposureTime"
                                )
                            }
                            previewBuilder?.set(
                                CaptureRequest.SENSOR_EXPOSURE_TIME,
                                exposureTime
                            )

                            setRepeatingRequest(previewBuilder?.build())
                        } catch (e: CameraAccessException) {
                            if (MyDebug.LOG) {
                                Log.e(
                                    TAG,
                                    "failed to take set exposure for next expo bracketing burst"
                                )
                                Log.e(TAG, "reason: " + e.reason)
                                Log.e(TAG, "message: " + e.message)
                            }
                            e.printStackTrace()
                            jpegTodo = false
                            rawTodo = false
                            pictureCb = null
                            pushTakePictureErrorCb = takePictureErrorCb
                        }
                        postNextSlowBurst()
                    }
                } else if (previewBuilder != null) { // make sure camera wasn't released in the meantime
                    if (MyDebug.LOG) Log.d(TAG, "focus bracketing")

                    if (!focusBracketingInProgress) {
                        if (MyDebug.LOG) Log.d(TAG, "focus bracketing was cancelled")
                        // ideally we'd stop altogether, but instead we take one last shot, so that we can mark it with the
                        // RequestTagType.CAPTURE tag, so onCaptureCompleted() is called knowing it's for the last image
                        if (MyDebug.LOG) {
                            Log.d(
                                TAG,
                                "slow_burst_capture_requests size was: " + slowBurstCaptureRequests.size
                            )
                            Log.d(
                                TAG,
                                "n_burst size was: $nBurst"
                            )
                            Log.d(TAG, "n_burst_taken: " + this@CameraController2.nBurstTaken)
                        }
                        slowBurstCaptureRequests.subList(
                            this@CameraController2.nBurstTaken + 1,
                            slowBurstCaptureRequests.size
                        ).clear() // resize to nBurstTaken
                        // if burstSingleRequest==true, nBurst is constant, and we stop when pending_burst_images.size() >= nBurst
                        // if burstSingleRequest==false, nBurst counts down, and we stop when nBurst==0
                        if (burstSingleRequest) {
                            nBurst = slowBurstCaptureRequests.size
                            if (nBurstRaw > 0) {
                                nBurstRaw = slowBurstCaptureRequests.size
                            }
                        } else {
                            nBurst = 1
                            if (nBurstRaw > 0) {
                                nBurstRaw = 1
                            }
                        }
                        if (MyDebug.LOG) {
                            Log.d(TAG, "size is now: " + slowBurstCaptureRequests.size)
                            Log.d(
                                TAG,
                                "n_burst is now: $nBurst"
                            )
                            Log.d(
                                TAG,
                                "n_burst_raw is now: $nBurstRaw"
                            )
                        }
                        val requestTag: RequestTagObject? =
                            slowBurstCaptureRequests[slowBurstCaptureRequests.size - 1].tag as? RequestTagObject?
                        requestTag?.setType(RequestTagType.CAPTURE)
                    }

                    // code for focus bracketing
                    if (nBurstTaken >= 0 && nBurstTaken < slowBurstCaptureRequests.size) {
                        try {
                            val focusDistance =
                                slowBurstCaptureRequests[nBurstTaken].get(
                                    CaptureRequest.LENS_FOCUS_DISTANCE
                                )
                            if (MyDebug.LOG) {
                                Log.d(
                                    TAG,
                                    "prepare preview for next focus_distance: $focusDistance"
                                )
                            }
                            previewBuilder?.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CameraMetadata.CONTROL_AF_MODE_OFF
                            )
                            previewBuilder?.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)

                            setRepeatingRequest(previewBuilder?.build())
                        } catch (e: CameraAccessException) {
                            if (MyDebug.LOG) {
                                Log.e(
                                    TAG,
                                    "failed to take set focus distance for next focus bracketing burst"
                                )
                                Log.e(TAG, "reason: " + e.reason)
                                Log.e(TAG, "message: " + e.message)
                            }
                            e.printStackTrace()
                            jpegTodo = false
                            rawTodo = false
                            pictureCb = null
                            pushTakePictureErrorCb = takePictureErrorCb
                        }
                    }
                    postNextSlowBurst()
                }
            }

            // need to call callbacks without a lock
            pushTakePictureErrorCb?.onError()
        }

        /** Called when an image has been received, but either we're not in a burst mode, or we are
         * but all images have been received.
         */
        fun takePhotoCompleted() {
            if (MyDebug.LOG) Log.d(TAG, "takePhotoCompleted")
            // need to set jpegTodo to false before calling onCompleted, as that may reenter CameraController to take another photo (if in auto-repeat burst mode) - see testTakePhotoRepeat()
            synchronized(backgroundCameraLock) {
                jpegTodo = false
            }
            checkImagesCompleted()
        }
    }

    inner class OnRawImageAvailableListener : ImageReader.OnImageAvailableListener {
        private val captureResults: Queue<CaptureResult> = LinkedList()
        private val images: Queue<Image> = LinkedList()

        // whether to ignore the next image (used for dummyCaptureHack)
        var skipNextImage = false

        fun setCaptureResult(captureResult: CaptureResult) {
            if (MyDebug.LOG) Log.d(TAG, "setCaptureResult()")
            synchronized(backgroundCameraLock) {
                /* synchronize, as we don't want to set the captureResult, at the same time that onImageAvailable() is called, as
                                * we'll end up calling processImage() both in onImageAvailable() and here.
                                */
                captureResults.add(captureResult)
                if (images.isNotEmpty()) {
                    if (MyDebug.LOG) Log.d(TAG, "can now process the image")
                    // should call processImage() on UI thread, to be consistent with onImageAvailable()->processImage()
                    // important to avoid crash when pause preview is option, tested in testTakePhotoRawWaitCaptureResult()
                    val activity = context as Activity
                    activity.runOnUiThread {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "setCaptureResult UI thread call processImage()"
                        )
                        // n.b., intentionally don't set the lock again
                        processImage()
                    }
                }
            }
        }

        fun clear() {
            if (MyDebug.LOG) Log.d(TAG, "clear()")
            synchronized(backgroundCameraLock) {
                // synchronize just to be safe?
                captureResults.clear()
                images.clear()
            }
        }

        fun processImage() {
            if (MyDebug.LOG) Log.d(TAG, "processImage()")

            var singleBurstCompleteImages: List<RawImage?>? = null
            var callTakePhotoCompleted = false
            val dngCreator: DngCreator
            val captureResult: CaptureResult
            val image: Image

            synchronized(backgroundCameraLock) {
                if (captureResults.isEmpty()) {
                    if (MyDebug.LOG) Log.d(TAG, "don't yet have still_capture_result")
                    return
                }
                if (images.isEmpty()) {
                    if (MyDebug.LOG) Log.d(TAG, "don't have image?!")
                    return
                }
                captureResult = captureResults.remove()
                image = images.remove()
                if (MyDebug.LOG) {
                    Log.d(TAG, "now have all info to process raw image")
                    Log.d(TAG, "image timestamp: " + image.timestamp)
                }
                dngCreator = DngCreator(characteristics!!, captureResult)
                // set fields
                dngCreator.setOrientation(cameraSettings.getExifOrientation())
                if (cameraSettings.location != null) {
                    dngCreator.setLocation(cameraSettings.location!!)
                }
                if (nBurstTotal == 1 && burstType !== BurstType.BURSTTYPE_CONTINUOUS) {
                    // Rather than call onRawPictureTaken straight away, we set pendingRawImage so that
                    // it's called in checkImagesCompleted, to ensure the RAW callback is taken after the JPEG callback.
                    // This isn't required, but can give an appearance of better performance to the user, as the thumbnail
                    // animation for a photo having been taken comes from the JPEG.
                    // We don't do this for burst mode, as it would get too complicated trying to enforce an ordering...
                    pendingRawImage = RawImage(dngCreator, image)
                } else if (burstSingleRequest) {
                    pendingBurstImagesRaw.add(RawImage(dngCreator, image))
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "pending_burst_images_raw size is now: " + pendingBurstImagesRaw.size
                        )
                    }
                    if (pendingBurstImagesRaw.size >= nBurstRaw) { // shouldn't ever be greater, but just in case
                        if (MyDebug.LOG) Log.d(TAG, "all raw burst images available")
                        if (pendingBurstImagesRaw.size > nBurstRaw) {
                            Log.e(
                                TAG,
                                "pending_burst_images_raw size " + pendingBurstImagesRaw.size + " is greater than n_burst_raw " + nBurstRaw
                            )
                        }
                        // take a copy, so that we can clear pendingBurstImagesRaw
                        singleBurstCompleteImages = ArrayList<RawImage>(pendingBurstImagesRaw)
                        // continued below after lock...
                    } else {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "number of raw burst images is now: " + pendingBurstImagesRaw.size
                        )
                    }
                }
            }

            if (pendingRawImage != null) {
                //takePendingRaw(); // test not waiting for JPEG callback

                checkImagesCompleted()
            } else {
                // burst-only code
                // need to call without a lock
                if (singleBurstCompleteImages != null) {
                    pictureCb?.onRawBurstPictureTaken(
                        singleBurstCompleteImages.filterNotNull()
                    )
                } else if (!burstSingleRequest) {
                    pictureCb?.onRawPictureTaken(RawImage(dngCreator, image))
                }

                synchronized(backgroundCameraLock) {
                    if (singleBurstCompleteImages != null) {
                        pendingBurstImagesRaw.clear()

                        callTakePhotoCompleted = true
                    } else if (!burstSingleRequest) {
                        nBurstRaw--
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "n_burst_raw is now $nBurstRaw"
                        )
                        if (burstType === BurstType.BURSTTYPE_CONTINUOUS && !continuousBurstRequestedLastCapture) {
                            // even if nBurstRaw is 0, we don't want to give up if we're still in continuous burst mode
                            // also note if we do have continuousBurstRequestedLastCapture==true, we still check for
                            // nBurstRaw==0 below (as there may have been more than one image still to be received)
                            if (MyDebug.LOG) Log.d(TAG, "continuous burst mode still in progress")
                        } else if (nBurstRaw == 0) {
                            callTakePhotoCompleted = true
                        }
                    }
                }

                // need to call outside of lock (because they can lead to calls to external callbacks)
                if (callTakePhotoCompleted) {
                    synchronized(backgroundCameraLock) {
                        rawTodo = false
                    }
                    checkImagesCompleted()
                }
            }

            if (MyDebug.LOG) Log.d(TAG, "done processImage")
        }

        override fun onImageAvailable(reader: ImageReader) {
            if (MyDebug.LOG) Log.d(TAG, "new still raw image available")
            if (pictureCb == null || !rawTodo) {
                // in theory this shouldn't happen - but if this happens, still free the image to avoid risk of memory leak,
                // or strange behavior where an old image appears when the user next takes a photo
                Log.e(TAG, "no picture callback available")
                val thisImage = reader.acquireNextImage()
                thisImage?.close()
                return
            }
            if (skipNextImage) {
                if (MyDebug.LOG) Log.d(TAG, "skipping image")
                skipNextImage = false
                val image = reader.acquireNextImage()
                image?.close()
                return
            }
            synchronized(backgroundCameraLock) {
                // see comment above in setCaptureResult() for why we synchronize
                val image = reader.acquireNextImage()
                if (image == null) {
                    Log.e(TAG, "RAW onImageAvailable: image is null")
                    return
                }
                images.add(image)
            }
            processImage()
            if (MyDebug.LOG) Log.d(TAG, "done (RAW) onImageAvailable")
        }
    }

    private val cameraSettings: Camera2Settings = Camera2Settings(this)
    private var pushRepeatingRequestWhenTorchOff = false
    private var pushRepeatingRequestWhenTorchOffId: CaptureRequest? = null

    /*private boolean pushSetAeLock = false;
    private CaptureRequest pushSetAeLockId = null;*/
    private var fakePrecaptureTurnOnTorchId: CaptureRequest? =
        null // the CaptureRequest used to turn on torch when starting the "fake" precapture

    override fun onError() {
        Log.e(TAG, "onError")
        if (camera != null) {
            onError(camera!!)
        }
    }

    private fun onError(cam: CameraDevice) {
        Log.e(TAG, "onError")
        val cameraAlreadyOpened = this.camera != null
        // need to set the camera to null first, as closing the camera may take some time, and we don't want any other operations to continue (if called from main thread)
        this.camera = null
        if (MyDebug.LOG) Log.d(TAG, "onError: camera is now set to null")
        cam.close()
        if (MyDebug.LOG) Log.d(TAG, "onError: camera is now closed")

        if (cameraAlreadyOpened) {
            // need to communicate the problem to the application
            // n.b., as this is potentially serious error, we always log even if MyDebug.LOG is false
            Log.e(TAG, "error occurred after camera was opened")
            // important to run on UI thread to avoid synchronization issues in the Preview
            val activity = context as Activity
            activity.runOnUiThread {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "onError: call camera_error_cb.onError() on UI thread"
                )
                cameraErrorCb.onError()
            }
        }
    }

    /** Closes the captureSession, if it exists.
     */
    private fun closeCaptureSession() {
        synchronized(backgroundCameraLock) {
            if (captureSession != null) {
                if (MyDebug.LOG) Log.d(TAG, "close capture session")
                captureSession?.close()
                captureSession = null
            }
            if (extensionSession != null) {
                if (MyDebug.LOG) Log.d(TAG, "close extension session")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        extensionSession!!.close()
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }
                extensionSession = null
            }
        }
    }

    override fun release() {
        if (MyDebug.LOG) Log.d(TAG, "release: $this")
        closeCaptureSession()
        previewBuilder = null
        previewIsVideoMode = false
        if (camera != null) {
            camera?.close()
            camera = null
        }
        closePictureImageReader()
        /*if( previewImageReader != null ) {
            previewImageReader.close();
            previewImageReader = null;
        }*/
        if (thread != null) {
            // should only close thread after closing the camera, otherwise we get messages "sending message to a Handler on a dead thread"
            // see https://sourceforge.net/p/OpenKamera/discussion/general/thread/32c2b01b/?limit=25
            thread!!.quitSafely()
            try {
                thread!!.join()
                thread = null
                handler = null
                executor = null
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    /** Enforce a minimum number of points in tonemap curves - needed due to Galaxy S10e having wrong behavior if fewer
     * than 16 or in some cases 32 points?! OnePlus 3T meanwhile has more gradual behavior where it gets better at 64 points.
     */
    private fun enforceMinTonemapCurvePoints(inValues: FloatArray): FloatArray {
        if (MyDebug.LOG) {
            Log.d(TAG, "enforceMinTonemapCurvePoints: " + inValues.contentToString())
            Log.d(TAG, "length: " + inValues.size / 2)
        }
        var minPointsC = 64
        if (isSamsung) {
            // Unfortunately odd bug on Samsung devices (at least S7 and S10e) where if more than 32 control points,
            // the maximum brightness value is reduced (can best be seen with 64 points, and using gamma==1.0).
            // Also note that Samsung devices also need at least 16 control points, or in some cases 32, due to problem
            // where things come out almost all black with some white. So choose 32!
            //minPointsC = 16;
            minPointsC = 32
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "min_points_c: $minPointsC"
        )
        if (inValues.size >= 2 * minPointsC) {
            if (MyDebug.LOG) Log.d(TAG, "already enough points")
            return inValues // fine
        }
        val points: MutableList<Pair<Float, Float>> = ArrayList()
        for (i in 0..<inValues.size / 2) {
            val point = Pair(
                inValues[2 * i],
                inValues[2 * i + 1]
            )
            points.add(point)
        }
        if (points.size < 2) {
            Log.e(TAG, "less than 2 points?!")
            return inValues
        }

        while (points.size < minPointsC) {
            // find largest interval, and subdivide
            var largestIndx = 0
            var largestDist = 0.0f
            for (i in 0..<points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val dist = p1.first - p0.first
                if (dist > largestDist) {
                    largestIndx = i
                    largestDist = dist
                }
            }
            /*if( MyDebug.LOG )
                Log.d(TAG, "largest indx " + largestIndx + " dist: " + largestDist);*/
            val p0 = points[largestIndx]
            val p1 = points[largestIndx + 1]
            val midX = 0.5f * (p0.first + p1.first)
            val midY = 0.5f * (p0.second + p1.second)
            /*if( MyDebug.LOG )
                Log.d(TAG, "    insert: " + midX + " , " + midY);*/
            points.add(largestIndx + 1, Pair(midX, midY))
        }

        val outValues = FloatArray(2 * points.size)
        for (i in points.indices) {
            val point = points[i]
            outValues[2 * i] = point.first
            outValues[2 * i + 1] = point.second
            /*if( MyDebug.LOG )
                Log.d(TAG, "out point[" + i + "]: " + point.first + " , " + point.second);*/
        }
        return outValues
    }

    private fun closePictureImageReader() {
        if (MyDebug.LOG) Log.d(TAG, "closePictureImageReader()")
        imageReaderPipeline.closePipeline()
        onImageAvailableListener = null
        onRawImageAvailableListener = null
    }

    private fun convertFocusModesToValues(supportedFocusModesArr: IntArray): MutableList<String>? {
        if (MyDebug.LOG) {
            Log.d(TAG, "convertFocusModesToValues()")
            Log.d(TAG, "supported_focus_modes_arr: " + supportedFocusModesArr.contentToString())
        }
        if (supportedFocusModesArr.isEmpty()) {
            if (MyDebug.LOG) Log.d(TAG, "no supported focus modes")
            return null
        }
        val supportedFocusModes: MutableList<Int> = ArrayList()
        for (supportedFocusMode in supportedFocusModesArr) supportedFocusModes.add(
            supportedFocusMode
        )
        val outputModes: MutableList<String> = ArrayList()
        // also resort as well as converting
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            outputModes.add("focus_mode_auto")
            if (MyDebug.LOG) {
                Log.d(TAG, " supports focus_mode_auto")
            }
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO)) {
            outputModes.add("focus_mode_macro")
            if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_macro")
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            outputModes.add("focus_mode_locked")
            if (MyDebug.LOG) {
                Log.d(TAG, " supports focus_mode_locked")
            }
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
            outputModes.add("focus_mode_infinity")
            if (MyDebug.LOG) {
                Log.d(TAG, " supports focus_mode_infinity")
            }
            if (minimumFocusDistance > 0.0f) {
                outputModes.add("focus_mode_manual2")
                if (MyDebug.LOG) {
                    Log.d(TAG, " supports focus_mode_manual2")
                }
            }
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_EDOF)) {
            outputModes.add("focus_mode_edof")
            if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_edof")
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            outputModes.add("focus_mode_continuous_picture")
            if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_continuous_picture")
        }
        if (supportedFocusModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            outputModes.add("focus_mode_continuous_video")
            if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_continuous_video")
        }
        return outputModes
    }

    override val api: String
        get() = "Camera2 (Android L)"

    @get:Throws(CameraControllerException::class)
    override val cameraFeatures: CameraFeatures
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getCameraFeatures()")
            val cameraFeatures = CameraFeatures()
            /*if( true )
                 throw new CameraControllerException();*/
            if (MyDebug.LOG) {
                val hardwareLevel =
                    characteristics?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                when (hardwareLevel) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> Log.d(
                        TAG, "Hardware Level: LEGACY"
                    )

                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> Log.d(
                        TAG, "Hardware Level: LIMITED"
                    )

                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> Log.d(
                        TAG, "Hardware Level: FULL"
                    )

                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> Log.d(
                        TAG,
                        "Hardware Level: Level 3"
                    )

                    else -> Log.e(
                        TAG,
                        "Unknown Hardware Level: $hardwareLevel"
                    )
                }

                val nrModes =
                    characteristics?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                Log.d(TAG, "nr_modes:")
                if (nrModes == null) {
                    Log.d(TAG, "    none")
                } else {
                    for (i in nrModes.indices) {
                        Log.d(TAG, "    " + i + ": " + nrModes[i])
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val capabilities =
                        characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES)
                    Log.d(TAG, "capabilities:")
                    if (capabilities == null) {
                        Log.d(TAG, "    none")
                    } else {
                        for (i in capabilities.indices) {
                            Log.d(TAG, "    " + i + ": " + capabilities[i].mode)
                        }
                    }
                }
            }

            var minZoom = 0.0f
            var maxZoom = 0.0f
            if (cameraIdSPhysical != null) {
                // don't support zoom for physical lenses - problem on Galaxy S24+ that zooming on physical lense gives random colors!
                // but in general, the exposed zoom ranges don't seem correct for physical lenses
                // both the above are true for CONTROL_ZOOM_RATIO_RANGE and SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // use CONTROL_ZOOM_RATIO_RANGE on Android 11+, to support multiple cameras with zoom ratios
                // less than 1
                try {
                    val zoomRatioRange =
                        characteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    if (zoomRatioRange != null) {
                        minZoom = zoomRatioRange.lower
                        maxZoom = zoomRatioRange.upper
                    } else {
                        if (MyDebug.LOG) Log.d(TAG, "zoom_ratio_range not supported")
                    }
                } catch (e: Throwable) {
                    // have had this crash from characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) on Google Play for some older Samsung Galaxy A* and Nokia devices
                    if (MyDebug.LOG) Log.e(TAG, "failed to get CONTROL_ZOOM_RATIO_RANGE", e)
                }
            }
            if (cameraIdSPhysical == null && (minZoom == 0.0f || maxZoom == 0.0f)) {
                minZoom = 1.0f
                maxZoom =
                    characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                        ?: 0.0f
            }
            cameraFeatures.isZoomSupported = maxZoom > 0.0f && minZoom > 0.0f
            if (MyDebug.LOG) {
                Log.d(TAG, "min_zoom: $minZoom")
                Log.d(TAG, "max_zoom: $maxZoom")
            }
            if (cameraFeatures.isZoomSupported) {
                val ratios: MutableList<Int> = ArrayList()
                this.zoomValue1x = computeZoomRatios(ratios, minZoom, maxZoom)

                cameraFeatures.zoomRatios = ratios
                cameraFeatures.maxZoom = (cameraFeatures.zoomRatios?.size ?: 0) - 1
                if (cameraFeatures.maxZoom == 0) {
                    // e.g. if max_zoom == 1.0f and min_zoom == 1.0f
                    cameraFeatures.isZoomSupported = false
                }
                this.fullZoomRatios = cameraFeatures.zoomRatios
                this.zoomRatios = cameraFeatures.zoomRatios
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "zoom_ratios: $zoomRatios"
                    )
                }
            } else {
                this.zoomRatios = null
            }

            val faceModes =
                characteristics?.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
            cameraFeatures.supportsFaceDetection = false
            supportsFaceDetectModeSimple = false
            supportsFaceDetectModeFull = false
            for (faceMode in faceModes!!) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "face detection mode: $faceMode"
                )
                // we currently only make use of the "SIMPLE" features, documented as:
                // "Return face rectangle and confidence values only."
                // note that devices that support STATISTICS_FACE_DETECT_MODE_FULL (e.g., Nexus 6) don't return
                // STATISTICS_FACE_DETECT_MODE_SIMPLE in the list, so we have checked for either
                if (faceMode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE) {
                    cameraFeatures.supportsFaceDetection = true
                    supportsFaceDetectModeSimple = true
                    if (MyDebug.LOG) Log.d(TAG, "supports simple face detection mode")
                } else if (faceMode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL) {
                    cameraFeatures.supportsFaceDetection = true
                    supportsFaceDetectModeFull = true
                    if (MyDebug.LOG) Log.d(TAG, "supports full face detection mode")
                }
            }
            if (cameraFeatures.supportsFaceDetection) {
                val faceCount =
                    characteristics?.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT) ?: 0
                if (faceCount <= 0) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "can't support face detection, as zero max face count"
                    )
                    cameraFeatures.supportsFaceDetection = false
                    supportsFaceDetectModeSimple = false
                    supportsFaceDetectModeFull = false
                }
            }
            if (cameraFeatures.supportsFaceDetection) {
                // check we have scene mode CONTROL_SCENE_MODE_FACE_PRIORITY
                val values2 =
                    characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                var hasFacePriority = false
                for (value2 in values2!!) {
                    if (value2 == CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY) {
                        hasFacePriority = true
                        break
                    }
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "has_face_priority: $hasFacePriority"
                )
                if (!hasFacePriority) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "can't support face detection, as no CONTROL_SCENE_MODE_FACE_PRIORITY"
                    )
                    cameraFeatures.supportsFaceDetection = false
                    supportsFaceDetectModeSimple = false
                    supportsFaceDetectModeFull = false
                }
            }

            val capabilities =
                characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

            val logicalCharacteristics: CameraCharacteristics?
            val logicalCapabilities: IntArray?
            if (cameraIdSPhysical != null) {
                // If we have a physical camera ID, characteristics refer to the physical camera ID. But for some things,
                // we want to query the characteristics of the logical camera.
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                try {
                    logicalCharacteristics = manager.getCameraCharacteristics(cameraIdS)
                    logicalCapabilities =
                        logicalCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                } catch (e: CameraAccessException) {
                    Log.e(
                        TAG,
                        "failed to get logical_characteristics for: $cameraIdS"
                    )
                    e.printStackTrace()
                    throw CameraControllerException()
                }
                if (MyDebug.LOG) Log.d(TAG, "successfully obtained logical camera characteristics")
            } else {
                logicalCharacteristics = characteristics
                logicalCapabilities = capabilities
            }

            //boolean capabilitiesManualSensor = false;
            var capabilitiesManualPostProcessing = false
            var capabilitiesRaw = false
            var capabilitiesHighSpeedVideo = false
            var capabilities10bit = false
            for (capability in capabilities!!) {
                /*if( capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR ) {
                     // At least some Huawei devices (at least, the Huawei device model FIG-LX3, device code-name hi6250) don't
                     // have REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR, but I had a user complain that HDR mode and manual ISO
                     // had previously worked for them. Note that we still check below for SENSOR_INFO_SENSITIVITY_RANGE and
                     // SENSOR_INFO_EXPOSURE_TIME_RANGE, so not checking REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR shouldn't
                     // enable manual ISO/exposure on devices that don't support it.
                     // Also, may affect Samsung Galaxy A8(2018).
                     // Instead we just block LEGACY devices (probably don't need to, again because we check
                     // SENSOR_INFO_SENSITIVITY_RANGE and SENSOR_INFO_EXPOSURE_TIME_RANGE, but just in case).
                     capabilitiesManualSensor = true;
                 }
                 else*/
                if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) {
                    capabilitiesManualPostProcessing = true
                } else if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                    capabilitiesRaw = true
                } else if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // we test for at least Android M just to be safe (this is needed for createConstrainedHighSpeedCaptureSession())
                    capabilitiesHighSpeedVideo = true
                } else if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) {
                    capabilities10bit = true
                } else if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (MyDebug.LOG) Log.d(TAG, "camera supports ultra high resolution")
                }
            }
            var capabilitiesLogicalMultiCamera = false
            for (capability in logicalCapabilities!!) {
                // to be safe, we check the REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA from the logical camera
                if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // we test for at least Android 9 just to be safe (this is needed for getPhysicalCameraIds())
                    if (MyDebug.LOG) Log.d(TAG, "camera is a logical multi-camera")
                    capabilitiesLogicalMultiCamera = true
                }
            }
            // At least some Huawei devices (at least, the Huawei device model FIG-LX3, device code-name hi6250) don't have
            // REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE, but I had a user complain that NR mode at least had previously
            // (before 1.45) worked for them. It might be that this can still work, just not at 20fps.
            // So instead set to true for all LIMITED devices. Still keep block for LEGACY devices (which definitely shouldn't
            // support fast burst - and which Open Kamera never allowed with Camera2 before 1.45).
            // Also, may affect Samsung Galaxy A8(2018).
            cameraFeatures.supportsBurst = CameraControllerManager2.isHardwareLevelSupported(
                characteristics!!,
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
            )

            if (MyDebug.LOG) {
                //Log.d(TAG, "capabilitiesManualSensor?: " + capabilitiesManualSensor);
                Log.d(
                    TAG,
                    "capabilities_manual_post_processing?: $capabilitiesManualPostProcessing"
                )
                Log.d(
                    TAG,
                    "capabilities_raw?: $capabilitiesRaw"
                )
                Log.d(TAG, "supports_burst?: " + cameraFeatures.supportsBurst)
                Log.d(
                    TAG,
                    "capabilities_high_speed_video?: $capabilitiesHighSpeedVideo"
                )
                Log.d(
                    TAG,
                    "capabilities_10bit?: $capabilities10bit"
                )
            }

            val configs: StreamConfigurationMap?
            try {
                configs =
                    characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            } catch (e: IllegalArgumentException) {
                // have had IllegalArgumentException crashes from Google Play - unclear what the cause is, but at least fail gracefully
                // similarly for NullPointerException - note, these aren't from characteristics being null, but from
                // com.android.internal.util.Preconditions.checkArrayElementsNotNull (Preconditions.java:395) - all are from
                // Nexus 7 (2013)s running Android 8.1, but again better to fail gracefully
                e.printStackTrace()
                throw CameraControllerException()
            } catch (e: NullPointerException) {
                e.printStackTrace()
                throw CameraControllerException()
            }

            val cameraPictureSizes = configs!!.getOutputSizes(ImageFormat.JPEG)

            cameraFeatures.supportsJpegR = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && capabilities10bit) {
                var debugTime: Long = 0
                if (MyDebug.LOG) {
                    debugTime = System.currentTimeMillis()
                }

                val jpegRCameraPictureSizes = configs.getOutputSizes(ImageFormat.JPEG_R)
                if (jpegRCameraPictureSizes != null) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "JPEG_R sizes: " + jpegRCameraPictureSizes.contentToString()
                    )
                    cameraFeatures.supportsJpegR = true
                    // For simplicity, we only support JPEG_R if it has the same support as for JPEG.
                    // Further checks are done below for getHighResolutionOutputSizes.
                    // Note that extensions don't support JPEG_R (extension_characteristics.getExtensionSupportedSizes
                    // is documented that it throws IllegalArgumentException if not JPEG or YUV_420_888).
                    if (!sizeSubset(cameraPictureSizes, jpegRCameraPictureSizes)) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "don't support JPEG_R: some picture sizes not supported"
                        )
                        cameraFeatures.supportsJpegR = false
                    }

                    if (cameraFeatures.supportsJpegR) {
                        // documentation says HLG10 must be supported by all devices with REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT,
                        // but check just to be safe
                        val profiles =
                            characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                        if (profiles == null) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "don't support JPEG_R: no DynamicRangeProfiles"
                            )
                            cameraFeatures.supportsJpegR = false
                        } else if (!profiles.supportedProfiles.contains(DynamicRangeProfiles.HLG10)) {
                            if (MyDebug.LOG) Log.d(TAG, "don't support JPEG_R: no HLG10")
                            cameraFeatures.supportsJpegR = false
                        }
                    }
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "JPEG_R not supported")
                }

                if (MyDebug.LOG) Log.d(
                    TAG,
                    "time for jpeg_r testing: " + (System.currentTimeMillis() - debugTime)
                )
            }

            cameraFeatures.pictureSizes = ArrayList()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cameraPictureSizesHires =
                    configs.getHighResolutionOutputSizes(ImageFormat.JPEG)
                if (cameraPictureSizesHires != null) {
                    for (cameraSize in cameraPictureSizesHires) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "high resolution picture size: " + cameraSize.width + " x " + cameraSize.height
                        )
                        // Check not already listed? If it's listed in both, we'll add it later on when scanning cameraPictureSizes
                        // (and we don't want to set supportsBurst to false for such a resolution).
                        var found = false
                        for (sz in cameraPictureSizes!!) {
                            if (sz == cameraSize) {
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "high resolution [non-burst] picture size: " + cameraSize.width + " x " + cameraSize.height
                            )
                            val size = Size(cameraSize.width, cameraSize.height)
                            size.supportsBurst = false
                            cameraFeatures.pictureSizes.add(size)
                        }
                    }

                    if (cameraFeatures.supportsJpegR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val cameraPictureSizesHiresJpegR =
                            configs.getHighResolutionOutputSizes(ImageFormat.JPEG_R)
                        if (!sizeSubset(
                                cameraPictureSizesHires,
                                cameraPictureSizesHiresJpegR
                            )
                        ) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "don't support JPEG_R: some high resolution (non-burst) picture sizes not supported"
                            )
                            cameraFeatures.supportsJpegR = false
                        }
                    }
                }
            }
            if (cameraPictureSizes == null) {
                // cameraPictureSizes is null on Samsung Galaxy Note 10+ and S20 for camera ID 4!
                Log.e(TAG, "no picture sizes returned by getOutputSizes")
                throw CameraControllerException()
            } else {
                for (cameraSize in cameraPictureSizes) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "picture size: " + cameraSize.width + " x " + cameraSize.height
                    )
                    cameraFeatures.pictureSizes.add(Size(cameraSize.width, cameraSize.height))
                }
            }
            // sizes are usually already sorted from high to low, but sort just in case
            // note some devices do have sizes in a not fully sorted order (e.g., Nokia 8)
            Collections.sort(cameraFeatures.pictureSizes, SizeSorter())

            // test high resolution modes not supporting burst:
            //camera_features.picture_sizes.get(0).supportsBurst = false;
            rawSize = null
            if (capabilitiesRaw) {
                val rawCameraPictureSizes = configs.getOutputSizes(ImageFormat.RAW_SENSOR)
                if (rawCameraPictureSizes == null) {
                    if (MyDebug.LOG) Log.d(TAG, "RAW not supported, failed to get RAW_SENSOR sizes")
                    wantRaw = false // just in case it got set to true somehow
                } else {
                    for (size in rawCameraPictureSizes) {
                        if (rawSize == null || (rawSize != null && (size.width * size.height > rawSize!!.width * rawSize!!.height))) {
                            rawSize = size
                        }
                    }
                    if (rawSize == null) {
                        if (MyDebug.LOG) Log.d(TAG, "RAW not supported, failed to find a raw size")
                        wantRaw = false // just in case it got set to true somehow
                    } else {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "raw supported, raw size: " + rawSize!!.width + " x " + rawSize!!.height
                        )
                        cameraFeatures.supportsRaw = true
                    }
                }
            } else {
                if (MyDebug.LOG) Log.d(TAG, "RAW capability not supported")
                wantRaw = false // just in case it got set to true somehow
            }

            if (MyDebug.LOG) {
                Log.d(TAG, "output_formats: " + configs.outputFormats.contentToString())
            }

            aeFpsRanges = ArrayList()
            for (r in (characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: emptyArray<Range<Int>>())) {
                aeFpsRanges.add(intArrayOf(r.lower ?: 0, r.upper ?: 0))
            }
            Collections.sort(aeFpsRanges, RangeSorter())
            if (MyDebug.LOG) {
                Log.d(TAG, "Supported AE video fps ranges: ")
                for (f in aeFpsRanges) {
                    Log.d(TAG, "   ae range: [" + f[0] + "-" + f[1] + "]")
                }
            }

            val cameraVideoSizes = configs.getOutputSizes(
                MediaRecorder::class.java
            )
            cameraFeatures.videoSizes = ArrayList()
            var minFps = 9999
            for (r in this.aeFpsRanges) {
                minFps = min(minFps.toDouble(), r[0].toDouble()).toInt()
            }
            if (cameraVideoSizes == null) {
                // cameraVideoSizes is null on Samsung Galaxy Note 10+ and S20 for camera ID 4!
                Log.e(TAG, "no video sizes returned by getOutputSizes")
                throw CameraControllerException()
            } else {
                for (cameraSize in cameraVideoSizes) {
                    if (cameraSize.width > 4096 || cameraSize.height > 2160) continue  // Nexus 6 returns these, even though not supported?!

                    val mfd = configs.getOutputMinFrameDuration(
                        MediaRecorder::class.java, cameraSize
                    )
                    val maxFps = ((1.0 / mfd) * 1000000000L).toInt()
                    val fr = ArrayList<IntArray>()
                    fr.add(intArrayOf(minFps, maxFps))
                    val normalVideoSize = Size(cameraSize.width, cameraSize.height, fr, false)
                    cameraFeatures.videoSizes.add(normalVideoSize)
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "normal video size: $normalVideoSize"
                        )
                    }
                }
            }
            Collections.sort(cameraFeatures.videoSizes, SizeSorter())

            // don't support high speed if physical camera specified - seems unreliable on Pixel 6 Pro and Galaxy S24+
            if (capabilitiesHighSpeedVideo && cameraIdSPhysical == null) {
                hsFpsRanges = ArrayList()
                cameraFeatures.videoSizesHighSpeed = ArrayList()

                for (r in configs.highSpeedVideoFpsRanges) {
                    // Some devices e.g. Pixel 6 Pro have high-speed fps ranges like [30-120]. We skip these because:
                    // Firstly we'd risk choosing this for 60fps, when 60fps shouldn't require high-speed.
                    // Secondly captureSessionHighSpeed.createHighSpeedRequestList() documentation says fps range
                    // should have min==max, so we don't want to include high speed ranges where this isn't true.
                    // Without this fix, Slow motion 0.5x (which uses 60fps) fails to start recording on Pixel 6 Pro.
                    if (r.lower != r.upper) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "skip high speed video fps range: $r"
                        )
                        continue
                    }
                    hsFpsRanges.add(intArrayOf(r.lower, r.upper))
                }
                Collections.sort(hsFpsRanges, RangeSorter())
                if (MyDebug.LOG) {
                    Log.d(TAG, "Supported high speed video fps ranges: ")
                    for (f in hsFpsRanges) {
                        Log.d(TAG, "   hs range: [" + f[0] + "-" + f[1] + "]")
                    }
                }

                val cameraVideoSizesHighSpeed = configs.highSpeedVideoSizes
                for (cameraSize in cameraVideoSizesHighSpeed) {
                    val fr = ArrayList<IntArray>()
                    for (r in configs.getHighSpeedVideoFpsRangesFor(cameraSize)) {
                        // see comment above for why we require min==max
                        if (r.lower != r.upper) {
                            continue
                        }
                        val thisFpsRange = intArrayOf(r.lower, r.upper)
                        // In theory, all fps ranges returned by getHighSpeedVideoFpsRangesFor() should surely be
                        // a subset of fps ranges returned by getHighSpeedVideoFpsRanges(), but we check just in case
                        // (when deciding whether slow motion or high speed frame rates are supported, this means we
                        // only need to check the frame rates of particular video sizes, as done in
                        // MyApplicationInterface.getSupportedVideoCaptureRates()).
                        var found = false
                        for (hsFpsRange in hsFpsRanges) {
                            if (hsFpsRange.contentEquals(thisFpsRange)) {
                                found = true
                                break
                            }
                        }
                        if (!found) {
                            if (MyDebug.LOG) Log.e(
                                TAG,
                                "video size " + cameraSize + " has high speed frame rate " + thisFpsRange.contentToString() + " that wasn't returned by configs.getHighSpeedVideoFpsRanges()"
                            )
                            continue
                        }
                        fr.add(thisFpsRange)
                    }
                    if (cameraSize.width > 4096 || cameraSize.height > 2160) continue  // just in case? see above

                    val hsVideoSize = Size(cameraSize.width, cameraSize.height, fr, true)
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "high speed video size: $hsVideoSize"
                        )
                    }
                    cameraFeatures.videoSizesHighSpeed?.add(hsVideoSize)
                }
                cameraFeatures.videoSizesHighSpeed?.let { Collections.sort(it, SizeSorter()) }
            }

            val cameraPreviewSizes = configs.getOutputSizes(
                SurfaceTexture::class.java
            )
            cameraFeatures.previewSizes = ArrayList()
            val displaySize = Point()
            val activity = context as Activity
            run {
                val display = activity.windowManager.defaultDisplay
                display.getRealSize(displaySize)
                // getRealSize() is adjusted based on the current rotation, so should already be landscape format, but it
                // would be good to not assume Open Kamera runs in landscape mode (if we ever ran in portrait mode,
                // we'd still want display_size.x > display_size.y as preview resolutions also have width > height)
                if (displaySize.x < displaySize.y) {
                    displaySize[displaySize.y] = displaySize.x
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "display_size: " + displaySize.x + " x " + displaySize.y
                )
            }
            if (cameraPreviewSizes == null) {
                // cameraPreviewSizes is null on Samsung Galaxy Note 10+ and S20 for camera ID 4!
                Log.e(TAG, "no preview sizes returned by getOutputSizes")
                throw CameraControllerException()
            } else {
                for (cameraSize in cameraPreviewSizes) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "preview size: " + cameraSize.width + " x " + cameraSize.height
                    )
                    if (cameraSize.width > displaySize.x || cameraSize.height > displaySize.y) {
                        // Nexus 6 returns these, even though not supported?! (get green corruption lines if we allow these)
                        // Google Camera filters anything larger than height 1080, with a todo saying to use device's measurements
                        continue
                    }
                    cameraFeatures.previewSizes.add(Size(cameraSize.width, cameraSize.height))
                }
            }

            val useCache = true
            //final boolean useCache = false;
            if (extensionCharacteristics == null) {
                // no extension characteristics
            } else if (useCache && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cameraFeaturesCache != null) {
                // read extensions info from cache for performance
                if (MyDebug.LOG) Log.d(TAG, "read vendor extensions info from cache")
                if (cameraFeaturesCache!!.supportedExtensions != null) cameraFeatures.supportedExtensions =
                    ArrayList(
                        cameraFeaturesCache!!.supportedExtensions!!
                    )
                if (cameraFeaturesCache!!.supportedExtensionsZoom != null) cameraFeatures.supportedExtensionsZoom =
                    ArrayList(
                        cameraFeaturesCache!!.supportedExtensionsZoom!!
                    )

                if (cameraFeatures.supportedExtensions != null) {
                    for (extension in cameraFeatures.supportedExtensions!!) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "vendor extension: $extension"
                        )
                        val extensionPictureSizes =
                            cameraFeaturesCache!!.extensionPictureSizesMap[extension]!!
                        val extensionPreviewSizes =
                            cameraFeaturesCache!!.extensionPreviewSizesMap[extension]!!
                        val hasPictureResolution = updatePictureSizesForExtension(
                            cameraFeatures.pictureSizes, extensionPictureSizes, extension
                        )
                        val hasPreviewResolution = updatePreviewSizesForExtension(
                            cameraFeatures.previewSizes, extensionPreviewSizes, extension
                        )
                        if (hasPictureResolution && hasPreviewResolution) {
                            // fine
                        } else {
                            if (MyDebug.LOG) Log.e(
                                TAG,
                                "cached extension not actually supported?!: $extension"
                            )
                            cameraFeatures.supportedExtensions?.remove(extension)
                            cameraFeatures.supportedExtensionsZoom!!.remove(extension)
                        }
                    }
                }
                if (MyDebug.LOG) Log.d(TAG, "done read vendor extensions info from cache")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (MyDebug.LOG) Log.d(TAG, "check for vendor extensions")
                val extensionPictureSizesMap: MutableMap<Int, List<android.util.Size>> =
                    Hashtable()
                val extensionPreviewSizesMap: MutableMap<Int, List<android.util.Size>> =
                    Hashtable()

                var extensions: List<Int>? = null
                try {
                    extensions = extensionCharacteristics!!.supportedExtensions
                } catch (_: Exception) {
                    // have IllegalArgumentException at least from Google Play crashes
                    if (MyDebug.LOG) Log.e(TAG, "exception from getSupportedExtensions")
                }
                if (extensions != null) {
                    cameraFeatures.supportedExtensions = ArrayList()
                    cameraFeatures.supportedExtensionsZoom = ArrayList()
                    for (extension in extensions) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "vendor extension: $extension"
                        )

                        try {
                            // we assume that the allowed extension sizes are a subset of the full sizes - makes things easier to manage

                            val extensionPictureSizes =
                                extensionCharacteristics!!.getExtensionSupportedSizes(
                                    extension,
                                    ImageFormat.JPEG
                                )
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "    extension_picture_sizes: $extensionPictureSizes"
                            )
                            val hasPictureResolution = updatePictureSizesForExtension(
                                cameraFeatures.pictureSizes, extensionPictureSizes, extension
                            )

                            val extensionPreviewSizes =
                                extensionCharacteristics!!.getExtensionSupportedSizes(
                                    extension,
                                    SurfaceTexture::class.java
                                )
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "    extension_preview_sizes: $extensionPreviewSizes"
                            )
                            val hasPreviewResolution = updatePreviewSizesForExtension(
                                cameraFeatures.previewSizes, extensionPreviewSizes, extension
                            )

                            if (hasPictureResolution && hasPreviewResolution) {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "    extension is supported: $extension"
                                )
                                cameraFeatures.supportedExtensions?.add(extension)
                                extensionPictureSizesMap[extension] = extensionPictureSizes
                                extensionPreviewSizesMap[extension] = extensionPreviewSizes

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val extensionSupportedRequestKeys =
                                        extensionCharacteristics!!.getAvailableCaptureRequestKeys(
                                            extension
                                        )
                                    for (key in extensionSupportedRequestKeys) {
                                        if (MyDebug.LOG) Log.d(
                                            TAG,
                                            "    supported capture request key: " + key.name
                                        )
                                        if (key === CaptureRequest.CONTROL_ZOOM_RATIO) {
                                            cameraFeatures.supportedExtensionsZoom?.add(extension)
                                        }
                                    }
                                    val extensionSupportedResultKeys =
                                        extensionCharacteristics!!.getAvailableCaptureResultKeys(
                                            extension
                                        )
                                    for (key in extensionSupportedResultKeys) {
                                        if (MyDebug.LOG) Log.d(
                                            TAG,
                                            "    supported capture result key: " + key.name
                                        )
                                    }
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                    if (MyDebug.LOG) {
                                        Log.d(
                                            TAG,
                                            "    isCaptureProcessProgressAvailable: " + extensionCharacteristics!!.isCaptureProcessProgressAvailable(
                                                extension
                                            )
                                        )
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // have IllegalArgumentException from getExtensionSupportedSizes() and getAvailableCaptureRequestKeys() at least from Google Play crashes
                            if (MyDebug.LOG) Log.e(
                                TAG,
                                "exception trying to query extension: $extension"
                            )
                            cameraFeatures.supportedExtensions?.remove(extension)
                            cameraFeatures.supportedExtensionsZoom?.remove(extension)
                            extensionPictureSizesMap.remove(extension)
                            extensionPreviewSizesMap.remove(extension)
                        }
                    }
                }

                // add to cache
                val cache = CameraFeaturesCache(
                    cameraFeatures,
                    extensionPictureSizesMap,
                    extensionPreviewSizesMap
                )
                cameraFeaturesCaches[cameraIdS] = cache
                if (MyDebug.LOG) Log.d(TAG, "done check for vendor extensions")
            }
            // save to local fields:
            this.supportedExtensionsZoom = cameraFeatures.supportedExtensionsZoom

            if (characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                val supportedFlashModesArr =
                    characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) // Android format
                if (supportedFlashModesArr != null) {
                    val supportedFlashModes: MutableList<Int> = ArrayList()
                    for (supportedFlashMode in supportedFlashModesArr) supportedFlashModes.add(
                        supportedFlashMode
                    )

                    cameraFeatures.supportedFlashValues = ArrayList()

                    // also resort as well as converting

                    // documentation for CONTROL_AE_AVAILABLE_MODES says the following modes are always supported:
                    cameraFeatures.supportedFlashValues!!.add("flash_off")
                    cameraFeatures.supportedFlashValues!!.add("flash_auto")
                    cameraFeatures.supportedFlashValues!!.add("flash_on")
                    cameraFeatures.supportedFlashValues!!.add("flash_torch")

                    if (!useFakePrecapture) {
                        if (supportedFlashModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE)) {
                            cameraFeatures.supportedFlashValues!!.add("flash_red_eye")
                            if (MyDebug.LOG) {
                                Log.d(TAG, " supports flash_red_eye")
                            }
                        }
                    }
                }
            } else if ((facing === Facing.FACING_FRONT)) {
                cameraFeatures.supportedFlashValues = ArrayList()
                cameraFeatures.supportedFlashValues!!.add("flash_off")
                cameraFeatures.supportedFlashValues!!.add("flash_frontscreen_auto")
                cameraFeatures.supportedFlashValues!!.add("flash_frontscreen_on")
                cameraFeatures.supportedFlashValues!!.add("flash_frontscreen_torch")
            }

            val minimumFocusDistanceF =
                characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) // may be null on some devices
            if (minimumFocusDistanceF != null) {
                cameraFeatures.minimumFocusDistance = minimumFocusDistanceF
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "minimum_focus_distance: " + cameraFeatures.minimumFocusDistance
                )
            } else {
                cameraFeatures.minimumFocusDistance = 0.0f
            }
            // save to local fields:
            this.minimumFocusDistance = cameraFeatures.minimumFocusDistance

            val supportedFocusModes: IntArray? =
                characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) // Android format
            if (supportedFocusModes != null) {
                // convert to our format (also resorts)
                cameraFeatures.supportedFocusValues =
                    convertFocusModesToValues(supportedFocusModes)
            }
            if (cameraFeatures.supportedFocusValues != null && cameraFeatures.supportedFocusValues!!.contains(
                    "focus_mode_manual2"
                )
            ) {
                cameraFeatures.supportsFocusBracketing = true
            }
            if (cameraFeatures.supportedFocusValues != null) {
                // prefer continuous focus mode
                initialFocusMode =
                    if (cameraFeatures.supportedFocusValues!!.contains("focus_mode_continuous_picture")) {
                        "focus_mode_continuous_picture"
                    } else {
                        // just go with the first one
                        cameraFeatures.supportedFocusValues!![0]
                    }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "initial_focus_mode: $initialFocusMode"
                )
            } else {
                initialFocusMode = null
            }

            cameraFeatures.maxNumFocusAreas =
                characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0

            cameraFeatures.isExposureLockSupported = true

            cameraFeatures.isWhiteBalanceLockSupported = true

            cameraFeatures.isOpticalStabilizationSupported = false
            val supportedOpticalStabilizationModes =
                characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            if (supportedOpticalStabilizationModes != null) {
                for (supportedOpticalStabilizationMode in supportedOpticalStabilizationModes) {
                    if (supportedOpticalStabilizationMode == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                        cameraFeatures.isOpticalStabilizationSupported = true
                        break
                    }
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "is_optical_stabilization_supported: " + cameraFeatures.isOpticalStabilizationSupported
            )
            supportsOpticalStabilization = cameraFeatures.isOpticalStabilizationSupported

            cameraFeatures.isVideoStabilizationSupported = false
            val supportedVideoStabilizationModes =
                characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            if (supportedVideoStabilizationModes != null) {
                for (supportedVideoStabilizationMode in supportedVideoStabilizationModes) {
                    if (supportedVideoStabilizationMode == CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                        cameraFeatures.isVideoStabilizationSupported = true
                        break
                    }
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "is_video_stabilization_supported: " + cameraFeatures.isVideoStabilizationSupported
            )

            cameraFeatures.isPhotoVideoRecordingSupported =
                CameraControllerManager2.isHardwareLevelSupported(
                    characteristics,
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
                )
            supportsPhotoVideoRecording = cameraFeatures.isPhotoVideoRecordingSupported

            val whiteBalanceModes =
                characteristics?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            if (whiteBalanceModes != null) {
                for (value in whiteBalanceModes) {
                    // n.b., Galaxy S10e for front and ultra-wide cameras offers CONTROL_AWB_MODE_OFF despite
                    // capabilitiesManualPostProcessing==false; if we don't check for capabilitiesManualPostProcessing,
                    // adjusting white balance temperature seems to work, but seems safest to require
                    // capabilitiesManualPostProcessing anyway
                    if (value == CameraMetadata.CONTROL_AWB_MODE_OFF && capabilitiesManualPostProcessing && allowManualWB()) {
                        cameraFeatures.supportsWhiteBalanceTemperature = true
                        cameraFeatures.minTemperature = MIN_WHITE_BALANCE_TEMPERATURE_C
                        cameraFeatures.maxTemperature = MAX_WHITE_BALANCE_TEMPERATURE_C
                    }
                }
            }
            supportsWhiteBalanceTemperature = cameraFeatures.supportsWhiteBalanceTemperature

            // see note above
            //if( capabilitiesManualSensor )
            if (CameraControllerManager2.isHardwareLevelSupported(
                    characteristics,
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
                )
            ) {
                // may be null on some devices
                val isoRange =
                    characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                if (isoRange != null) {
                    cameraFeatures.supportsIsoRange = true
                    cameraFeatures.minIso = isoRange.lower
                    cameraFeatures.maxIso = isoRange.upper
                    // we only expose exposureTime if isoRange is supported
                    val exposureTimeRange =
                        characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) // may be null on some devices
                    if (exposureTimeRange != null) {
                        cameraFeatures.supportsExposureTime = true
                        cameraFeatures.supportsExpoBracketing = true
                        cameraFeatures.maxExpoBracketingNImages = MAX_EXPO_BRACKETING_N_IMAGES
                        cameraFeatures.minExposureTime = exposureTimeRange.lower
                        cameraFeatures.maxExposureTime = exposureTimeRange.upper
                        if ((isSamsungGalaxyS || isSamsungGalaxyF) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // seems we can get away with longer exposure on some devices (e.g., Galaxy S10e claims only max of 0.1s, but works with 1/3s)
                            // but Android 11 on Samsung devices also introduces a bug where manual exposure gets ignored if different to the preview,
                            // and since the max preview rate is limited to 1/5s (see maxPreviewExposureTimeC), there's no point
                            // going above this!
                            // update: as of 1.54, we now can go above the maxPreviewExposureTimeC, by using RequestTagType.RUN_POST_CAPTURE
                            // (see adjustPreviewToStill())
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "boost max_exposure_time, was: $maxExposureTime"
                            )
                            cameraFeatures.maxExposureTime =
                                cameraFeatures.maxExposureTime.coerceAtLeast(1_000_000_000L / 2)
                        }
                    }
                }
            }
            // save to local fields:
            this.supportsExposureTime = cameraFeatures.supportsExposureTime
            this.minExposureTime = cameraFeatures.minExposureTime
            this.maxExposureTime = cameraFeatures.maxExposureTime

            val exposureRange =
                characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            cameraFeatures.minExposure = exposureRange!!.lower
            cameraFeatures.maxExposure = exposureRange.upper
            cameraFeatures.exposureStep =
                characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat()
                    ?: 0f

            cameraFeatures.canDisableShutterSound = true

            if (capabilitiesManualPostProcessing) {
                val tonemapMaxCurvePoints =
                    characteristics?.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS)
                if (tonemapMaxCurvePoints != null) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "tonemap_max_curve_points: $tonemapMaxCurvePoints"
                    )

                    val tonemapModes =
                        characteristics?.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                    if (tonemapModes == null) {
                        // if no tonemap modes, can't support tonemapping
                        if (MyDebug.LOG) Log.d(TAG, "tonemap_modes is null")
                    } else {
                        var supportsTonemapContrastCurve = false
                        for (tonemapMode in tonemapModes) {
                            if (tonemapMode == CaptureRequest.TONEMAP_MODE_PRESET_CURVE) {
                                supportsTonemapPresetCurve = true
                            } else if (tonemapMode == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE) {
                                supportsTonemapContrastCurve = true
                            }
                        }
                        if (MyDebug.LOG) {
                            Log.d(
                                TAG,
                                "supports_tonemap_preset_curve: $supportsTonemapPresetCurve"
                            )
                            Log.d(
                                TAG,
                                "supports_tonemap_contrast_curve: $supportsTonemapContrastCurve"
                            )
                        }

                        // if supportsTonemapContrastCurve==false, don't bother supporting tonemapping (in theory we could support the preset curves alone, but not supported for simplicity)
                        // if supportsTonemapContrastCurve==true but supportsTonemapPresetCurve==false, we'll still support tonemapping, but always use contrast curves
                        if (supportsTonemapContrastCurve) {
                            cameraFeatures.tonemapMaxCurvePoints = tonemapMaxCurvePoints
                            // for now, we only expose supporting of custom tonemap curves if there are enough curve points for all the
                            // profiles we support
                            // remember to divide by 2 if we're comparing against the raw array length!
                            cameraFeatures.supportsTonemapCurve =
                                tonemapMaxCurvePoints >= TONEMAP_LOG_MAX_CURVE_POINTS_C && tonemapMaxCurvePoints >= jtvideoValues.size / 2 && tonemapMaxCurvePoints >= jtlogValues.size / 2 && tonemapMaxCurvePoints >= jtlog2Values.size / 2
                        }
                    }
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "tonemap_max_curve_points is null")
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "supports_tonemap_curve?: " + cameraFeatures.supportsTonemapCurve
            )

            val apertures =
                characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            //float [] apertures = new float[]{1.5f, 1.9f, 2.0f, 2.2f, 2.4f, 4.0f, 8.0f, 16.0f}; // test
            if (MyDebug.LOG) Log.d(TAG, "apertures: " + apertures.contentToString())
            // no point supporting if only a single aperture
            if (apertures != null && apertures.size > 1) {
                cameraFeatures.apertures = apertures
            }

            val viewAngle: SizeF = CameraControllerManager2.computeViewAngles(characteristics!!)
            cameraFeatures.viewAngleX = viewAngle.width
            cameraFeatures.viewAngleY = viewAngle.height

            if (capabilitiesLogicalMultiCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // to be safe, read from the logical camera characteristics
                cameraFeatures.physicalCameraIds = logicalCharacteristics!!.physicalCameraIds
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "physical_camera_ids: " + cameraFeatures.physicalCameraIds
                )
                if (cameraFeatures.physicalCameraIds!!.size <= 1) {
                    // no point supporting
                    cameraFeatures.physicalCameraIds = null
                }
            }

            if (!cameraFeatures.supportsJpegR) {
                wantJpegR = false // just in case it got set to true somehow
            }

            return cameraFeatures
        }

    /** For each of the pictureSizes, update the CameraController.Size.supportedExtensions field to record if that resolution
     * supports the supplied extension.
     * @param pictureSizes           Picture sizes to update.
     * @param extensionPictureSizes Picture sizes supported by the extension.
     * @param extension               Extension to test.
     * @return                        If false, then none of the pictureSizes are supported by this extension.
     */
    private fun updatePictureSizesForExtension(
        pictureSizes: List<Size>,
        extensionPictureSizes: List<android.util.Size>,
        extension: Int
    ): Boolean {
        var hasPictureResolution = false
        for (size in pictureSizes) {
            if (extensionPictureSizes.contains(android.util.Size(size.width, size.height))) {
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "    picture size supports extension: " + size.width + " , " + size.height
                    )
                }
                hasPictureResolution = true
                if (size.supportedExtensions == null) {
                    size.supportedExtensions = ArrayList()
                }
                size.supportedExtensions?.add(extension)
            } else {
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "    picture size does NOT support extension: " + size.width + " , " + size.height
                    )
                }
            }
        }
        return hasPictureResolution
    }

    /** For each of the previewSizes, update the CameraController.Size.supportedExtensions field to record if that resolution
     * supports the supplied extension.
     * @param previewSizes           Preview sizes to update.
     * @param extensionPreviewSizes Preview sizes supported by the extension.
     * @param extension               Extension to test.
     * @return                        If false, then none of the previewSizes are supported by this extension.
     */
    private fun updatePreviewSizesForExtension(
        previewSizes: List<Size>,
        extensionPreviewSizes: List<android.util.Size>,
        extension: Int
    ): Boolean {
        var hasPreviewResolution = false
        for (size in previewSizes) {
            if (extensionPreviewSizes.contains(android.util.Size(size.width, size.height))) {
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "    preview size supports extension: " + size.width + " , " + size.height
                    )
                }
                hasPreviewResolution = true
                if (size.supportedExtensions == null) {
                    size.supportedExtensions = ArrayList()
                }
                size.supportedExtensions?.add(extension)
            } else {
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "    preview size does NOT support extension: " + size.width + " , " + size.height
                    )
                }
            }
        }
        return hasPreviewResolution
    }

    override fun shouldCoverPreview(): Boolean {
        return !hasReceivedFrame
    }

    override fun resetCoverPreview() {
        this.hasReceivedFrame = false
    }

    private fun convertSceneMode(value2: Int): String? {
        val value: String?
        when (value2) {
            CameraMetadata.CONTROL_SCENE_MODE_ACTION -> value = "action"
            CameraMetadata.CONTROL_SCENE_MODE_BARCODE -> value = "barcode"
            CameraMetadata.CONTROL_SCENE_MODE_BEACH -> value = "beach"
            CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT -> value = "candlelight"
            CameraMetadata.CONTROL_SCENE_MODE_DISABLED -> value = SCENE_MODE_DEFAULT
            CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS -> value = "fireworks"
            CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE -> value = "landscape"
            CameraMetadata.CONTROL_SCENE_MODE_NIGHT -> value = "night"
            CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT -> value = "night-portrait"
            CameraMetadata.CONTROL_SCENE_MODE_PARTY -> value = "party"
            CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT -> value = "portrait"
            CameraMetadata.CONTROL_SCENE_MODE_SNOW -> value = "snow"
            CameraMetadata.CONTROL_SCENE_MODE_SPORTS -> value = "sports"
            CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO -> value = "steadyphoto"
            CameraMetadata.CONTROL_SCENE_MODE_SUNSET -> value = "sunset"
            CameraMetadata.CONTROL_SCENE_MODE_THEATRE -> value = "theatre"
            else -> {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "unknown scene mode: $value2"
                )
                value = null
            }
        }
        return value
    }

    override fun setSceneMode(value: String): SupportedValues? {
        if (MyDebug.LOG) Log.d(TAG, "setSceneMode: $value")
        // we convert to/from strings to be compatible with original Android Camera API
        val values2 = characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
        var hasDisabled = false
        val values: MutableList<String> = ArrayList()
        if (values2 != null) {
            // CONTROL_AVAILABLE_SCENE_MODES is supposed to always be available, but have had some (rare) crashes from Google Play due to being null
            for (value2 in values2) {
                if (value2 == CameraMetadata.CONTROL_SCENE_MODE_DISABLED) hasDisabled = true
                val thisValue = convertSceneMode(value2)
                if (thisValue != null) {
                    values.add(thisValue)
                }
            }
        }
        if (!hasDisabled) {
            values.add(0, SCENE_MODE_DEFAULT)
        }
        val supportedValues = checkModeIsSupported(values, value, SCENE_MODE_DEFAULT)
        if (supportedValues != null) {
            var selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_DISABLED
            when (supportedValues.selectedValue) {
                "action" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_ACTION
                "barcode" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_BARCODE
                "beach" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_BEACH
                "candlelight" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_CANDLELIGHT
                SCENE_MODE_DEFAULT -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_DISABLED
                "fireworks" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_FIREWORKS
                "landscape" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_LANDSCAPE
                "night" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_NIGHT
                "night-portrait" -> selectedValue2 =
                    CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT

                "party" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_PARTY
                "portrait" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_PORTRAIT
                "snow" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_SNOW
                "sports" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_SPORTS
                "steadyphoto" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_STEADYPHOTO
                "sunset" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_SUNSET
                "theatre" -> selectedValue2 = CameraMetadata.CONTROL_SCENE_MODE_THEATRE
                else -> if (MyDebug.LOG) Log.d(
                    TAG,
                    "unknown selected_value: " + supportedValues.selectedValue
                )
            }

            cameraSettings.sceneMode = selectedValue2
            if (cameraSettings.setSceneMode(previewBuilder)) {
                try {
                    setRepeatingRequest()
                } catch (e: CameraAccessException) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "failed to set scene mode")
                        Log.e(TAG, "reason: " + e.reason)
                        Log.e(TAG, "message: " + e.message)
                    }
                    e.printStackTrace()
                }
            }
        }
        return supportedValues
    }

    override val sceneMode: String?
        get() {
            if (previewBuilder?.get(CaptureRequest.CONTROL_SCENE_MODE) == null) return null
            val value2 = previewBuilder?.get(CaptureRequest.CONTROL_SCENE_MODE)!!
            return convertSceneMode(value2)
        }

    override fun sceneModeAffectsFunctionality(): Boolean {
        // Camera2 API doesn't seem to have any warnings that changing scene mode can affect available functionality
        return false
    }

    private fun convertColorEffect(value2: Int): String? {
        val value: String?
        when (value2) {
            CameraMetadata.CONTROL_EFFECT_MODE_AQUA -> value = "aqua"
            CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD -> value = "blackboard"
            CameraMetadata.CONTROL_EFFECT_MODE_MONO -> value = "mono"
            CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE -> value = "negative"
            CameraMetadata.CONTROL_EFFECT_MODE_OFF -> value = COLOR_EFFECT_DEFAULT
            CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE -> value = "posterize"
            CameraMetadata.CONTROL_EFFECT_MODE_SEPIA -> value = "sepia"
            CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE -> value = "solarize"
            CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD -> value = "whiteboard"
            else -> {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "unknown effect mode: $value2"
                )
                value = null
            }
        }
        return value
    }

    override fun setColorEffect(value: String): SupportedValues? {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setColorEffect: $value"
        )
        // we convert to/from strings to be compatible with original Android Camera API
        val values2 = characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)
            ?: return null
        val values: MutableList<String> = ArrayList()
        for (value2 in values2) {
            val thisValue = convertColorEffect(value2)
            if (thisValue != null) {
                values.add(thisValue)
            }
        }
        val supportedValues = checkModeIsSupported(values, value, COLOR_EFFECT_DEFAULT)
        if (supportedValues != null) {
            var selectedValue2 = CameraMetadata.CONTROL_EFFECT_MODE_OFF
            when (supportedValues.selectedValue) {
                "aqua" -> selectedValue2 = CameraMetadata.CONTROL_EFFECT_MODE_AQUA
                "blackboard" -> selectedValue2 = CameraMetadata.CONTROL_EFFECT_MODE_BLACKBOARD
                "mono" -> selectedValue2 = CameraMetadata.CONTROL_EFFECT_MODE_MONO
                "negative" -> selectedValue2 = CameraMetadata.CONTROL_EFFECT_MODE_NEGATIVE
                COLOR_EFFECT_DEFAULT -> selectedValue2 = CameraMetadata.CONTROL_EFFECT_MODE_OFF
                "posterize" -> selectedValue2 = CameraMetadata.CONTROL_EFFECT_MODE_POSTERIZE
                "sepia" -> selectedValue2 = CameraMetadata.CONTROL_EFFECT_MODE_SEPIA
                "solarize" -> selectedValue2 = CameraMetadata.CONTROL_EFFECT_MODE_SOLARIZE
                "whiteboard" -> selectedValue2 = CameraMetadata.CONTROL_EFFECT_MODE_WHITEBOARD
                else -> if (MyDebug.LOG) Log.d(
                    TAG,
                    "unknown selected_value: " + supportedValues.selectedValue
                )
            }

            cameraSettings.colorEffect = selectedValue2
            if (cameraSettings.setColorEffect(previewBuilder)) {
                try {
                    setRepeatingRequest()
                } catch (e: CameraAccessException) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "failed to set color effect")
                        Log.e(TAG, "reason: " + e.reason)
                        Log.e(TAG, "message: " + e.message)
                    }
                    e.printStackTrace()
                }
            }
        }
        return supportedValues
    }

    override val colorEffect: String?
        get() {
            if (previewBuilder?.get(CaptureRequest.CONTROL_EFFECT_MODE) == null) return null
            val value2 = previewBuilder?.get(CaptureRequest.CONTROL_EFFECT_MODE)!!
            return convertColorEffect(value2)
        }

    private fun convertWhiteBalance(value2: Int): String? {
        val value: String?
        when (value2) {
            CameraMetadata.CONTROL_AWB_MODE_AUTO -> value = WHITE_BALANCE_DEFAULT
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> value = "cloudy-daylight"
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> value = "daylight"
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> value = "fluorescent"
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> value = "incandescent"
            CameraMetadata.CONTROL_AWB_MODE_SHADE -> value = "shade"
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> value = "twilight"
            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> value = "warm-fluorescent"
            CameraMetadata.CONTROL_AWB_MODE_OFF -> value = "manual"
            else -> {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "unknown white balance: $value2"
                )
                value = null
            }
        }
        return value
    }

    /** Whether we should allow manual white balance, even if the device supports CONTROL_AWB_MODE_OFF.
     */
    private fun allowManualWB(): Boolean {
        val isNexus6 = Build.MODEL.lowercase().contains("nexus 6")
        // manual white balance doesn't seem to work on Nexus 6!
        return !isNexus6
    }

    override fun setWhiteBalance(value: String): SupportedValues? {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setWhiteBalance: $value"
        )
        // we convert to/from strings to be compatible with original Android Camera API
        val values2 = characteristics?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: return null
        val values: MutableList<String> = ArrayList()
        for (value2 in values2) {
            val thisValue = convertWhiteBalance(value2)
            if (thisValue != null) {
                if (value2 == CameraMetadata.CONTROL_AWB_MODE_OFF && !supportsWhiteBalanceTemperature) {
                    // filter
                } else {
                    values.add(thisValue)
                }
            }
        }
        run {
            // re-order so that auto is first, manual is second
            val hasAuto = values.remove(WHITE_BALANCE_DEFAULT)
            val hasManual = values.remove("manual")
            if (hasManual) values.add(0, "manual")
            if (hasAuto) values.add(0, WHITE_BALANCE_DEFAULT)
        }
        val supportedValues = checkModeIsSupported(values, value, WHITE_BALANCE_DEFAULT)
        if (supportedValues != null) {
            var selectedValue2 = CameraMetadata.CONTROL_AWB_MODE_AUTO
            when (supportedValues.selectedValue) {
                WHITE_BALANCE_DEFAULT -> selectedValue2 = CameraMetadata.CONTROL_AWB_MODE_AUTO
                "cloudy-daylight" -> selectedValue2 =
                    CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT

                "daylight" -> selectedValue2 = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                "fluorescent" -> selectedValue2 = CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                "incandescent" -> selectedValue2 = CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                "shade" -> selectedValue2 = CameraMetadata.CONTROL_AWB_MODE_SHADE
                "twilight" -> selectedValue2 = CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
                "warm-fluorescent" -> selectedValue2 =
                    CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT

                "manual" -> selectedValue2 = CameraMetadata.CONTROL_AWB_MODE_OFF
                else -> if (MyDebug.LOG) Log.d(
                    TAG,
                    "unknown selected_value: " + supportedValues.selectedValue
                )
            }

            cameraSettings.whiteBalance = selectedValue2
            if (cameraSettings.setWhiteBalance(previewBuilder)) {
                try {
                    setRepeatingRequest()
                } catch (e: CameraAccessException) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "failed to set white balance")
                        Log.e(TAG, "reason: " + e.reason)
                        Log.e(TAG, "message: " + e.message)
                    }
                    e.printStackTrace()
                }
            }
        }
        return supportedValues
    }

    override val whiteBalance: String?
        get() {
            if (previewBuilder?.get(CaptureRequest.CONTROL_AWB_MODE) == null) return null
            val value2 = previewBuilder?.get(CaptureRequest.CONTROL_AWB_MODE)!!
            return convertWhiteBalance(value2)
        }

    // Returns whether white balance temperature was modified
    override fun setWhiteBalanceTemperature(temperature: Int): Boolean {
        var newTemperature = temperature
        if (MyDebug.LOG) Log.d(
            TAG,
            "setWhiteBalanceTemperature: $newTemperature)"
        )
        if (cameraSettings.whiteBalance == newTemperature) {
            if (MyDebug.LOG) Log.d(TAG, "already set")
            return false
        }
        try {
            newTemperature =
                max(newTemperature.toDouble(), MIN_WHITE_BALANCE_TEMPERATURE_C.toDouble()).toInt()
            newTemperature =
                min(newTemperature.toDouble(), MAX_WHITE_BALANCE_TEMPERATURE_C.toDouble()).toInt()
            cameraSettings.whiteBalanceTemperature = newTemperature
            if (cameraSettings.setWhiteBalance(previewBuilder)) {
                setRepeatingRequest()
            }
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to set white balance temperature")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
        }
        return true
    }

    override val whiteBalanceTemperature: Int
        get() = cameraSettings.whiteBalanceTemperature

    private fun convertAntiBanding(value2: Int): String? {
        val value: String?
        when (value2) {
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO -> value = ANTIBANDING_DEFAULT
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ -> value = "50hz"
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ -> value = "60hz"
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF -> value = "off"
            else -> {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "unknown antibanding: $value2"
                )
                value = null
            }
        }
        return value
    }

    override fun setAntiBanding(value: String): SupportedValues? {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setAntiBanding: $value"
        )
        // we convert to/from strings to be compatible with original Android Camera API
        val values2 =
            characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
                ?: return null
        val values: MutableList<String> = ArrayList()
        for (value2 in values2) {
            val thisValue = convertAntiBanding(value2)
            if (thisValue != null) {
                values.add(thisValue)
            }
        }
        val supportedValues = checkModeIsSupported(values, value, ANTIBANDING_DEFAULT)
        if (supportedValues != null) {
            // for antibanding, if the requested value isn't available, we don't modify it at all
            // (so we stick with the device's default setting)
            if (supportedValues.selectedValue == value) {
                var selectedValue2 = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
                when (supportedValues.selectedValue) {
                    ANTIBANDING_DEFAULT -> selectedValue2 =
                        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO

                    "50hz" -> selectedValue2 = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
                    "60hz" -> selectedValue2 = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ
                    "off" -> selectedValue2 = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF
                    else -> if (MyDebug.LOG) Log.d(
                        TAG,
                        "unknown selected_value: " + supportedValues.selectedValue
                    )
                }

                cameraSettings.hasAntibanding = true
                cameraSettings.antibanding = selectedValue2
                if (cameraSettings.setAntiBanding(previewBuilder)) {
                    try {
                        setRepeatingRequest()
                    } catch (e: CameraAccessException) {
                        if (MyDebug.LOG) {
                            Log.e(TAG, "failed to set antibanding")
                            Log.e(TAG, "reason: " + e.reason)
                            Log.e(TAG, "message: " + e.message)
                        }
                        e.printStackTrace()
                    }
                }
            }
        }
        return supportedValues
    }

    override val antiBanding: String?
        get() {
            if (previewBuilder?.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE) == null) return null
            val value2 = previewBuilder?.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE)!!
            return convertAntiBanding(value2)
        }

    private fun convertEdgeMode(value2: Int): String? {
        val value: String?
        when (value2) {
            CameraMetadata.EDGE_MODE_FAST -> value = "fast"
            CameraMetadata.EDGE_MODE_HIGH_QUALITY -> value = "high_quality"
            CameraMetadata.EDGE_MODE_OFF -> value = "off"
            CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG ->             // we don't make use of zero shutter lag
                value = null

            else -> {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "unknown edge_mode: $value2"
                )
                value = null
            }
        }
        return value
    }

    override fun setEdgeMode(value: String): SupportedValues? {
        if (MyDebug.LOG) Log.d(TAG, "setEdgeMode: $value")
        val values2 = characteristics?.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?: return null
        val values: MutableList<String> = ArrayList()
        values.add(EDGE_MODE_DEFAULT)
        for (value2 in values2) {
            val thisValue = convertEdgeMode(value2)
            if (thisValue != null) {
                values.add(thisValue)
            }
        }
        val supportedValues = checkModeIsSupported(values, value, EDGE_MODE_DEFAULT)
        if (supportedValues != null) {
            // for edge mode, if the requested value isn't available, we don't modify it at all
            if (supportedValues.selectedValue == value) {
                var hasEdgeMode = false
                var selectedValue2 = CameraMetadata.EDGE_MODE_FAST
                // if EDGE_MODE_DEFAULT, this means to stick with the device default
                if (value != EDGE_MODE_DEFAULT) {
                    when (supportedValues.selectedValue) {
                        "fast" -> {
                            hasEdgeMode = true
                            selectedValue2 = CameraMetadata.EDGE_MODE_FAST
                        }

                        "high_quality" -> {
                            hasEdgeMode = true
                            selectedValue2 = CameraMetadata.EDGE_MODE_HIGH_QUALITY
                        }

                        "off" -> {
                            hasEdgeMode = true
                            selectedValue2 = CameraMetadata.EDGE_MODE_OFF
                        }

                        else -> if (MyDebug.LOG) Log.d(
                            TAG,
                            "unknown selected_value: " + supportedValues.selectedValue
                        )
                    }
                }

                if (cameraSettings.hasEdgeMode != hasEdgeMode || cameraSettings.edgeMode != selectedValue2) {
                    cameraSettings.hasEdgeMode = hasEdgeMode
                    cameraSettings.edgeMode = selectedValue2
                    if (cameraSettings.setEdgeMode(previewBuilder)) {
                        try {
                            setRepeatingRequest()
                        } catch (e: CameraAccessException) {
                            if (MyDebug.LOG) {
                                Log.e(TAG, "failed to set edge_mode")
                                Log.e(TAG, "reason: " + e.reason)
                                Log.e(TAG, "message: " + e.message)
                            }
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        return supportedValues
    }

    override val edgeMode: String?
        get() {
            if (previewBuilder?.get(CaptureRequest.EDGE_MODE) == null) return null
            val value2 = previewBuilder?.get(CaptureRequest.EDGE_MODE)!!
            return convertEdgeMode(value2)
        }

    private fun convertNoiseReductionMode(value2: Int): String? {
        val value: String?
        when (value2) {
            CameraMetadata.NOISE_REDUCTION_MODE_FAST -> value = "fast"
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> value = "high_quality"
            CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL -> value = "minimal"
            CameraMetadata.NOISE_REDUCTION_MODE_OFF -> value = "off"
            CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG ->             // we don't make use of zero shutter lag
                value = null

            else -> {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "unknown noise_reduction_mode: $value2"
                )
                value = null
            }
        }
        return value
    }

    override fun setNoiseReductionMode(value: String): SupportedValues? {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setNoiseReductionMode: $value"
        )
        val values2 =
            characteristics?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                ?: return null
        val values: MutableList<String> = ArrayList()
        values.add(NOISE_REDUCTION_MODE_DEFAULT)
        for (value2 in values2) {
            val thisValue = convertNoiseReductionMode(value2)
            if (thisValue != null) {
                values.add(thisValue)
            }
        }
        val supportedValues = checkModeIsSupported(values, value, NOISE_REDUCTION_MODE_DEFAULT)
        if (supportedValues != null) {
            // for noise reduction, if the requested value isn't available, we don't modify it at all
            if (supportedValues.selectedValue == value) {
                var hasNoiseReductionMode = false
                var selectedValue2 = CameraMetadata.NOISE_REDUCTION_MODE_FAST
                // if NOISE_REDUCTION_MODE_DEFAULT, this means to stick with the device default
                if (value != NOISE_REDUCTION_MODE_DEFAULT) {
                    when (supportedValues.selectedValue) {
                        "fast" -> {
                            hasNoiseReductionMode = true
                            selectedValue2 = CameraMetadata.NOISE_REDUCTION_MODE_FAST
                        }

                        "high_quality" -> {
                            hasNoiseReductionMode = true
                            selectedValue2 = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                        }

                        "minimal" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            hasNoiseReductionMode = true
                            selectedValue2 = CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
                        } else {
                            // shouldn't ever be here, as NOISE_REDUCTION_MODE_MINIMAL shouldn't be a supported value!
                            // treat as fast instead
                            Log.e(TAG, "noise reduction minimal, but pre-Android M!")
                            hasNoiseReductionMode = true
                            selectedValue2 = CameraMetadata.NOISE_REDUCTION_MODE_FAST
                        }

                        "off" -> {
                            hasNoiseReductionMode = true
                            selectedValue2 = CameraMetadata.NOISE_REDUCTION_MODE_OFF
                        }

                        else -> if (MyDebug.LOG) Log.d(
                            TAG,
                            "unknown selected_value: " + supportedValues.selectedValue
                        )
                    }
                }

                if (cameraSettings.hasNoiseReductionMode != hasNoiseReductionMode || cameraSettings.noiseReductionMode != selectedValue2) {
                    cameraSettings.hasNoiseReductionMode = hasNoiseReductionMode
                    cameraSettings.noiseReductionMode = selectedValue2
                    if (cameraSettings.setNoiseReductionMode(previewBuilder)) {
                        try {
                            setRepeatingRequest()
                        } catch (e: CameraAccessException) {
                            if (MyDebug.LOG) {
                                Log.e(TAG, "failed to set noise_reduction_mode")
                                Log.e(TAG, "reason: " + e.reason)
                                Log.e(TAG, "message: " + e.message)
                            }
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        return supportedValues
    }

    override val noiseReductionMode: String?
        get() {
            if (previewBuilder?.get(CaptureRequest.NOISE_REDUCTION_MODE) == null) return null
            val value2 = previewBuilder?.get(CaptureRequest.NOISE_REDUCTION_MODE)!!
            return convertNoiseReductionMode(value2)
        }

    override fun setISO(value: String?): SupportedValues? {
        // not supported for CameraController2 - but Camera2 devices that don't support manual ISO can call this,
        // so assume this is for auto ISO
        this.setManualISO(false, 0)
        return null
    }

    override val isoKey: String
        get() = ""

    override fun setManualISO(manualIso: Boolean, iso: Int) {
        var newIso = iso
        if (MyDebug.LOG) Log.d(
            TAG,
            "setManualISO: $manualIso"
        )
        try {
            if (manualIso) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "switch to iso: $newIso"
                )
                val isoRange =
                    characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) // may be null on some devices
                if (isoRange == null) {
                    if (MyDebug.LOG) Log.d(TAG, "iso not supported")
                    return
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "iso range from " + isoRange.lower + " to " + isoRange.upper
                )

                cameraSettings.hasIso = true
                newIso = max(newIso.toDouble(), isoRange.lower.toDouble()).toInt()
                newIso = min(newIso.toDouble(), isoRange.upper.toDouble()).toInt()
                cameraSettings.iso = newIso
            } else {
                cameraSettings.hasIso = false
                cameraSettings.iso = 0
            }
            updateUseFakePrecaptureMode(cameraSettings.flashValue)

            if (cameraSettings.setAEMode(previewBuilder, false)) {
                setRepeatingRequest()
            }
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to set ISO")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
        }
    }

    override val isManualISO: Boolean
        get() = cameraSettings.hasIso

    // Returns whether ISO was modified
    // N.B., use setManualISO() to switch between auto and manual mode
    override fun setISO(iso: Int): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "setISO: $iso")
        if (cameraSettings.iso == iso) {
            if (MyDebug.LOG) Log.d(TAG, "already set")
            return false
        }
        try {
            cameraSettings.iso = iso
            if (cameraSettings.setAEMode(previewBuilder, false)) {
                setRepeatingRequest()
            }
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to set ISO")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
        }
        return true
    }

    override val iSO: Int
        get() = cameraSettings.iso

    override val exposureTime: Long
        get() = cameraSettings.exposureTime

    // Returns whether exposure time was modified
    // N.B., use setISO(String) to switch between auto and manual mode
    override fun setExposureTime(exposureTime: Long): Boolean {
        if (MyDebug.LOG) {
            Log.d(TAG, "setExposureTime: $exposureTime")
            Log.d(TAG, "current exposure time: " + cameraSettings.exposureTime)
        }
        if (cameraSettings.exposureTime == exposureTime) {
            if (MyDebug.LOG) Log.d(TAG, "already set")
            return false
        }
        try {
            cameraSettings.exposureTime = exposureTime
            if (cameraSettings.setAEMode(previewBuilder, false)) {
                setRepeatingRequest()
            }
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to set exposure time")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
        }
        return true
    }

    override fun setAperture(aperture: Float) {
        if (MyDebug.LOG) {
            Log.d(TAG, "setAperture: $aperture")
            Log.d(TAG, "current aperture: " + cameraSettings.aperture)
        }
        if (cameraSettings.hasAperture && cameraSettings.aperture == aperture) {
            if (MyDebug.LOG) Log.d(TAG, "already set")
        }
        try {
            cameraSettings.hasAperture = true
            cameraSettings.aperture = aperture
            if (cameraSettings.setAperture(previewBuilder)) {
                setRepeatingRequest()
            }
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to set aperture")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
        }
    }

    override val pictureSize: Size
        get() = Size(pictureWidth, pictureHeight)

    override fun setPictureSize(width: Int, height: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setPictureSize: $width x $height"
        )
        if (camera == null) {
            if (MyDebug.LOG) Log.e(TAG, "no camera")
            return
        }
        if (hasCaptureSession()) {
            // can only call this when captureSession not created - as the surface of the imageReader we create has to match the surface we pass to the captureSession
            if (MyDebug.LOG) Log.e(TAG, "can't set picture size when captureSession running!")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        this.pictureWidth = width
        this.pictureHeight = height
    }

    override fun setJpegR(wantJpegR: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "setJpegR: $wantJpegR")
        }
        if (camera == null) {
            if (MyDebug.LOG) Log.e(TAG, "no camera")
            return
        }
        if (this.wantJpegR == wantJpegR) {
            return
        }
        if (hasCaptureSession()) {
            // can only call this when captureSession not created - as it affects how we create the imageReader
            if (MyDebug.LOG) Log.e(TAG, "can't set jpeg_r when captureSession running!")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        this.wantJpegR = wantJpegR
    }

    override fun setRaw(wantRaw: Boolean, maxRawImages: Int) {
        if (MyDebug.LOG) {
            Log.d(TAG, "setRaw: $wantRaw")
            Log.d(TAG, "max_raw_images: $maxRawImages")
        }
        if (camera == null) {
            if (MyDebug.LOG) Log.e(TAG, "no camera")
            return
        }
        if (this.wantRaw == wantRaw && this.maxRawImages == maxRawImages) {
            return
        }
        if (wantRaw && this.rawSize == null) {
            if (MyDebug.LOG) Log.e(TAG, "can't set raw when raw not supported")
            return
        }
        if (hasCaptureSession()) {
            // can only call this when captureSession not created - as it affects how we create the imageReader
            if (MyDebug.LOG) Log.e(TAG, "can't set raw when captureSession running!")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        this.wantRaw = wantRaw
        this.maxRawImages = maxRawImages
    }

    override fun setVideoHighSpeed(setVideoHighSpeed: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setVideoHighSpeed: $setVideoHighSpeed"
        )
        if (camera == null) {
            if (MyDebug.LOG) Log.e(TAG, "no camera")
            return
        }
        if (this.wantVideoHighSpeed == setVideoHighSpeed) {
            return
        }
        if (hasCaptureSession()) {
            // can only call this when captureSession not created - as it affects how we create the session
            if (MyDebug.LOG) Log.e(TAG, "can't set high speed when captureSession running!")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        this.wantVideoHighSpeed = setVideoHighSpeed
        this.isVideoHighSpeed = false // reset just to be safe
    }

    override fun setCameraExtension(enabled: Boolean, extension: Int) {
        if (MyDebug.LOG) {
            Log.d(TAG, "setCameraExtension?: $enabled")
            Log.d(TAG, "extension: $extension")
        }

        if (camera == null) {
            if (MyDebug.LOG) Log.e(TAG, "no camera")
            return
        }
        if (sessionType == (if (enabled) SessionType.SESSIONTYPE_EXTENSION else SessionType.SESSIONTYPE_NORMAL) && this.cameraExtension == (if (enabled) extension else 0)) {
            // quick exit
            if (MyDebug.LOG) Log.d(TAG, "    no change")
            return
        }
        if (hasCaptureSession()) {
            // can only call this when captureSession not created - as it affects how we create the imageReader
            if (MyDebug.LOG) Log.e(TAG, "can't set extension when captureSession running!")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }

        if (enabled != (sessionType == SessionType.SESSIONTYPE_EXTENSION)) {
            if (MyDebug.LOG) Log.d(TAG, "turning extension session on or off")
            // Ideally we'd probably only create the previewBuilder when starting the preview (so we
            // start off with a "fresh" one), but for now at least ensure we start off with a fresh
            // previewBuilder when enabling extensions (and might as well do so when disabling
            // extensions too).
            // This saves us having to set capture request parameters back to their defaults, and is
            // also useful for modes like CONTROL_AE_ANTIBANDING_MODE where there isn't an obvious
            // "default" to set. (In theory extensions mode should just ignore such keys, but it'd be
            // nicer to never set them.)
            previewBuilder = null
            createPreviewRequest()
        }

        if (enabled) {
            this.sessionType =
                SessionType.SESSIONTYPE_EXTENSION
            this.cameraExtension = extension
        } else {
            this.sessionType =
                SessionType.SESSIONTYPE_NORMAL
            this.cameraExtension = 0
        }
    }

    val isExtensionSession: Boolean
        get() = this.sessionType == SessionType.SESSIONTYPE_EXTENSION

    override val isCameraExtension: Boolean
        get() = this.sessionType == SessionType.SESSIONTYPE_EXTENSION

    override fun getCameraExtension(): Int {
        if (isCameraExtension) return cameraExtension
        return -1
    }

    override var burstType: BurstType
        get() = captureCoordinator.burstType
        set(burstType) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "setBurstType: $burstType"
            )
            if (camera == null) {
                if (MyDebug.LOG) Log.e(TAG, "no camera")
                return
            }
            if (captureCoordinator.burstType === burstType) {
                return
            }
            captureCoordinator.burstType = burstType
            updateUseFakePrecaptureMode(cameraSettings.flashValue)
            cameraSettings.setAEMode(
                previewBuilder,
                false
            ) // may need to set the ae mode, as flash is disabled for burst modes
        }

    override fun setExpoBracketingNImages(nImages: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setExpoBracketingNImages: $nImages"
        )
        try {
            captureCoordinator.setExpoBracketingNImages(nImages)
        } catch (e: IllegalArgumentException) {
            if (MyDebug.LOG) Log.e(TAG, e.message ?: "Invalid nImages")
            throw RuntimeException(e.message) // throw as RuntimeException, as this is a programming error
        }
    }

    override fun setExpoBracketingStops(stops: Double) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setExpoBracketingStops: $stops"
        )
        try {
            captureCoordinator.setExpoBracketingStops(stops)
        } catch (e: IllegalArgumentException) {
            if (MyDebug.LOG) Log.e(TAG, "stops should be positive")
            throw RuntimeException(e) // throw as RuntimeException, as this is a programming error
        }
    }

    override fun setDummyCaptureHack(dummyCaptureHack: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setDummyCaptureHack: $dummyCaptureHack"
        )
        captureCoordinator.dummyCaptureHack = dummyCaptureHack
    }

    override fun setUseExpoFastBurst(useExpoFastBurst: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setUseExpoFastBurst: $useExpoFastBurst"
        )
        captureCoordinator.useExpoFastBurst = useExpoFastBurst
    }

    override val isCaptureFastBurst: Boolean
        get() = captureCoordinator.isCaptureFastBurst

    override val isCapturingBurst: Boolean
        get() = captureCoordinator.isCapturingBurst(nBurstTaken, nBurstTotal, nBurst, nBurstRaw)

    override val burstTotal: Int
        get() = captureCoordinator.calculateBurstTotal(nBurstTotal)

    override fun setBurstNImages(burstRequestedNImages: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setBurstNImages: $burstRequestedNImages"
        )
        captureCoordinator.burstRequestedNImages = burstRequestedNImages
    }

    override fun setBurstForNoiseReduction(
        burstForNoiseReduction: Boolean,
        noiseReductionLowLight: Boolean
    ) {
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setBurstForNoiseReduction: $burstForNoiseReduction"
            )
            Log.d(
                TAG,
                "noise_reduction_low_light: $noiseReductionLowLight"
            )
        }
        captureCoordinator.setBurstForNoiseReduction(burstForNoiseReduction, noiseReductionLowLight)
    }

    override fun stopContinuousBurst() {
        if (MyDebug.LOG) Log.d(TAG, "stopContinuousBurst")
        captureCoordinator.stopContinuousBurst()
    }

    override fun stopFocusBracketingBurst() {
        if (MyDebug.LOG) Log.d(TAG, "stopFocusBracketingBurst")
        if (burstType === BurstType.BURSTTYPE_FOCUS) {
            captureCoordinator.stopFocusBracketing()
        } else {
            Log.e(
                TAG,
                "stopFocusBracketingBurst burst_type is: $burstType"
            )
        }
    }

    override var useCamera2FakeFlash: Boolean
        get() = this.useFakePrecapture
        set(useFakePrecapture) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "setUseCamera2FakeFlash: $useFakePrecapture"
            )
            if (camera == null) {
                if (MyDebug.LOG) Log.e(TAG, "no camera")
                return
            }
            if (this.useFakePrecapture == useFakePrecapture) {
                return
            }
            this.useFakePrecapture = useFakePrecapture
            this.useFakePrecaptureMode = useFakePrecapture
            // no need to call updateUseFakePrecaptureMode(), as this method should only be called after first creating camera controller
        }

    private fun createPictureImageReader() {
        if (MyDebug.LOG) Log.d(TAG, "createPictureImageReader")
        if (hasCaptureSession()) {
            // can only call this when captureSession not created - as the surface of the imageReader we create has to match the surface we pass to the captureSession
            if (MyDebug.LOG) Log.e(
                TAG,
                "can't create picture image reader when captureSession running!"
            )
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        closePictureImageReader()
        if (pictureWidth == 0 || pictureHeight == 0) {
            if (MyDebug.LOG) Log.e(TAG, "application needs to call setPictureSize()")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        val config = ImageReaderConfig(
            pictureWidth = pictureWidth,
            pictureHeight = pictureHeight,
            isJpegR = wantJpegR,
            wantRaw = wantRaw,
            rawSize = rawSize,
            maxRawImages = maxRawImages,
            isVideoMode = previewIsVideoMode
        )
        val jpegListener = OnImageAvailableListener().also {
            onImageAvailableListener = it
        }
        val rawListener = if (wantRaw && rawSize != null && !previewIsVideoMode) {
            OnRawImageAvailableListener().also {
                onRawImageAvailableListener = it
            }
        } else {
            null
        }
        imageReaderPipeline.createPipeline(config, jpegListener, rawListener, null)
        if (MyDebug.LOG) {
            Log.d(TAG, "created new imageReader: $imageReader")
            Log.d(TAG, "imageReader surface: " + imageReader?.surface.toString())
            if (imageReaderRaw != null) {
                Log.d(TAG, "created new imageReaderRaw: $imageReaderRaw")
                Log.d(TAG, "imageReaderRaw surface: " + imageReaderRaw?.surface.toString())
            }
        }
    }

    private fun clearPending() {
        if (MyDebug.LOG) Log.d(TAG, "clearPending")
        pendingBurstImages.clear()
        pendingBurstImagesRaw.clear()
        pendingRawImage = null
        if (onImageAvailableListener != null) {
            onImageAvailableListener!!.skipNextImage = false
        }
        if (onRawImageAvailableListener != null) {
            onRawImageAvailableListener!!.clear()
            onRawImageAvailableListener!!.skipNextImage = false
        }
        slowBurstCaptureRequests = mutableListOf()
        nBurst = 0
        nBurstTaken = 0
        nBurstTotal = 0
        nBurstRaw = 0
        burstSingleRequest = false
        slowBurstStartMs = 0
    }

    private fun takePendingRaw() {
        if (MyDebug.LOG) Log.d(TAG, "takePendingRaw")
        // takePendingRaw() always called on UI thread, and pendingRawImage only used on UI thread, so shouldn't need to
        // synchronize for that
        if (pendingRawImage != null) {
            synchronized(backgroundCameraLock) {
                rawTodo = false
            }
            // don't call callback with lock
            pictureCb?.onRawPictureTaken(pendingRawImage)
            // pendingRawImage should be closed by the application (we don't do it here, so that applications can keep hold of the data, e.g., in a queue for background processing)
            pendingRawImage = null
            if (onRawImageAvailableListener != null) {
                onRawImageAvailableListener!!.clear()
            }
        }
    }

    private fun checkImagesCompleted() {
        if (MyDebug.LOG) Log.d(TAG, "checkImagesCompleted")
        var completed = false
        var takePendingRaw = false
        synchronized(backgroundCameraLock) {
            if (!doneAllCaptures) {
                if (MyDebug.LOG) Log.d(TAG, "still waiting for captures")
            } else if (pictureCb == null) {
                // just in case?
                if (MyDebug.LOG) Log.d(TAG, "no picture_cb")
            } else if (!jpegTodo && !rawTodo) {
                if (MyDebug.LOG) Log.d(TAG, "all image callbacks now completed")
                completed = true
            } else if (!jpegTodo && pendingRawImage != null) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "jpeg callback already done, can now call pending raw callback"
                )
                takePendingRaw = true
                completed = true
            } else {
                if (MyDebug.LOG) Log.d(TAG, "need to wait for jpeg and/or raw callback")
            }
        }

        // need to call callbacks without a lock
        if (takePendingRaw) {
            takePendingRaw()
            if (MyDebug.LOG) Log.d(TAG, "all image callbacks now completed")
        }
        if (completed) {
            // need to set pictureCb to null before calling onCompleted, as that may reenter CameraController to take another photo (if in auto-repeat burst mode) - see testTakePhotoRepeat()
            val cb = pictureCb
            pictureCb = null
            cb!!.onCompleted()
            synchronized(backgroundCameraLock) {
                if (burstType === BurstType.BURSTTYPE_FOCUS) focusBracketingInProgress = false
            }
        }
    }

    override val previewSize: Size
        get() = Size(previewWidth, previewHeight)

    override fun setPreviewSize(width: Int, height: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setPreviewSize: $width , $height"
        )
        previewWidth = width
        previewHeight = height
        /*if( previewImageReader != null ) {
            previewImageReader.close();
        }
        previewImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
        */
    }

    override val opticalStabilization: Boolean
        get() {
            val oisMode = previewBuilder?.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
                ?: return false
            return (oisMode == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }

    override var videoStabilization: Boolean
        get() = cameraSettings.videoStabilization
        set(enabled) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "setVideoStabilization: $enabled"
            )
            cameraSettings.videoStabilization = enabled
            previewBuilder?.let { cameraSettings.setStabilization(it) }
            try {
                setRepeatingRequest()
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to set video stabilization")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            }
        }

    override fun setTonemapProfile(
        tonemapProfile: TonemapProfile,
        logProfileStrength: Float,
        gamma: Float
    ) {
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setTonemapProfile: $tonemapProfile"
            )
            Log.d(
                TAG,
                "log_profile_strength: $logProfileStrength"
            )
            Log.d(TAG, "gamma: $gamma")
        }
        if (cameraSettings.tonemapProfile === tonemapProfile && cameraSettings.logProfileStrength == logProfileStrength && cameraSettings.gammaProfile == gamma) return  // no change


        cameraSettings.tonemapProfile = tonemapProfile

        if (tonemapProfile === TonemapProfile.TONEMAPPROFILE_LOG) cameraSettings.logProfileStrength =
            logProfileStrength
        else cameraSettings.logProfileStrength = 0.0f

        if (tonemapProfile === TonemapProfile.TONEMAPPROFILE_GAMMA) cameraSettings.gammaProfile =
            gamma
        else cameraSettings.gammaProfile = 0.0f

        previewBuilder?.let { cameraSettings.setTonemapProfile(it) }
        try {
            setRepeatingRequest()
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to set log profile")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
        }
    }

    override val tonemapProfile: TonemapProfile
        get() = cameraSettings.tonemapProfile

    /** For testing.
     */
    fun testGetPreviewBuilder(): CaptureRequest.Builder? {
        return previewBuilder
    }

    fun testGetTonemapCurve(): TonemapCurve? {
        return previewBuilder?.get(CaptureRequest.TONEMAP_CURVE)
    }

    override var jpegQuality: Int
        get() = cameraSettings.jpegQuality.toInt()
        set(quality) {
            if (quality !in 0..100) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "invalid jpeg quality$quality"
                )
                throw RuntimeException() // throw as RuntimeException, as this is a programming error
            }
            cameraSettings.jpegQuality = quality.toByte()
        }

    override var zoom: Int
        get() = this.currentZoomValue
        set(value) {
            setZoom(value, -1.0f)
        }

    override fun setZoom(value: Int, smoothZoom: Float) {
        if (zoomRatios == null) {
            if (MyDebug.LOG) Log.d(TAG, "zoom not supported")
            return
        }
        if (sessionType == SessionType.SESSIONTYPE_EXTENSION) {
            if (this.supportedExtensionsZoom != null && supportedExtensionsZoom!!.contains(
                    cameraExtension
                )
            ) {
                // fine, camera extension supports zoom
            } else {
                if (MyDebug.LOG) Log.d(TAG, "zoom not supported for camera extension")
                return
            }
        }
        if (value < 0 || value >= zoomRatios!!.size) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "invalid zoom value$value"
            )
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        if (smoothZoom > 0.0f) {
            if (smoothZoom < zoomRatios!![0] / 100.0f) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "invalid smooth_zoom: $smoothZoom"
                )
                throw RuntimeException("smooth_zoom too small")
            } else if (smoothZoom > zoomRatios!![zoomRatios!!.size - 1] / 100.0f) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "invalid smooth_zoom: $smoothZoom"
                )
                throw RuntimeException("smooth_zoom too large")
            }
        }
        val zoom = if (smoothZoom > 0.0f) smoothZoom else zoomRatios!![value] / 100.0f
        if (MyDebug.LOG) Log.d(TAG, "zoom to: $zoom")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cameraSettings.hasControlZoomRatio = true
            cameraSettings.controlZoomRatio = zoom
            previewBuilder?.let { cameraSettings.setControlZoomRatio(it) }
        } else {
            val sensorRect =
                characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            var left = sensorRect!!.width() / 2
            var right = left
            var top = sensorRect.height() / 2
            var bottom = top
            val hwidth = (sensorRect.width() / (2.0 * zoom)).toInt()
            val hheight = (sensorRect.height() / (2.0 * zoom)).toInt()
            left -= hwidth
            right += hwidth
            top -= hheight
            bottom += hheight
            if (MyDebug.LOG) {
                Log.d(TAG, "zoom: $zoom")
                Log.d(TAG, "hwidth: $hwidth")
                Log.d(TAG, "hheight: $hheight")
                Log.d(TAG, "sensor_rect left: " + sensorRect.left)
                Log.d(TAG, "sensor_rect top: " + sensorRect.top)
                Log.d(TAG, "sensor_rect right: " + sensorRect.right)
                Log.d(TAG, "sensor_rect bottom: " + sensorRect.bottom)
                Log.d(TAG, "left: $left")
                Log.d(TAG, "top: $top")
                Log.d(TAG, "right: $right")
                Log.d(TAG, "bottom: $bottom")
                /*Rect currentRect = previewBuilder.get(CaptureRequest.SCALER_CROP_REGION);
            Log.d(TAG, "currentRect left: " + current_rect.left);
            Log.d(TAG, "currentRect top: " + current_rect.top);
            Log.d(TAG, "currentRect right: " + current_rect.right);
            Log.d(TAG, "currentRect bottom: " + current_rect.bottom);*/
            }
            cameraSettings.scalarCropRegion = Rect(left, top, right, bottom)
            previewBuilder?.let { cameraSettings.setCropRegion(it) }
        }
        this.currentZoomValue = value
        try {
            setRepeatingRequest()
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to set zoom")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
        }
    }

    override fun setZoomSticky(sticky: Boolean): List<Int>? {
        if (this.zoomRatios != null) {
            val currentZoomRatio = this.zoomRatios!![currentZoomValue]

            if (sticky) {
                // reset
                this.zoomRatios = this.fullZoomRatios
            } else {
                val newZoomRatios: MutableList<Int> = ArrayList()
                var oldRatio = -1
                if (fullZoomRatios != null) {
                    for (ratio in fullZoomRatios!!) {
                        if (ratio != oldRatio) {
                            newZoomRatios.add(ratio)
                            oldRatio = ratio
                        }
                    }
                }
                this.zoomRatios = newZoomRatios
            }

            // adjust currentZoomValue to new value
            currentZoomValue = 0
            if (zoomRatios != null) {
                for (i in zoomRatios!!.indices) {
                    if (currentZoomRatio == zoomRatios!![i]) {
                        currentZoomValue = i
                    }
                }
            }
        }

        return this.zoomRatios
    }

    override fun resetZoom() {
        zoom = zoomValue1x
    }

    override val exposureCompensation: Int
        get() = previewBuilder?.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) ?: 0

    // Returns whether exposure was modified
    override fun setExposureCompensation(newExposure: Int): Boolean {
        cameraSettings.hasAeExposureCompensation = true
        cameraSettings.aeExposureCompensation = newExposure
        if (cameraSettings.setExposureCompensation(previewBuilder)) {
            try {
                setRepeatingRequest()
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to set exposure compensation")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            }
            return true
        }
        return false
    }

    override fun setPreviewFpsRange(min: Int, max: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setPreviewFpsRange: $min-$max"
        )
        cameraSettings.aeTargetFpsRange = Range(min / 1000, max / 1000)
        //      Frame duration is in nanoseconds.  Using min to be safe.
        cameraSettings.sensorFrameDuration = (1.0 / (min / 1000.0) * 1000000000L).toLong()

        try {
            if (cameraSettings.setAEMode(previewBuilder, false)) {
                setRepeatingRequest()
            }
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(
                    TAG,
                    "failed to set preview fps range to $min-$max"
                )
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
        }
    }

    override fun clearPreviewFpsRange() {
        if (MyDebug.LOG) Log.d(TAG, "clearPreviewFpsRange")
        // needed e.g. on Nokia 8 when switching back from slow motion to regular speed, in order to reset to the regular
        // frame rate
        if (cameraSettings.aeTargetFpsRange != null || cameraSettings.sensorFrameDuration != 0L) {
            // set back to default
            cameraSettings.aeTargetFpsRange = null
            cameraSettings.sensorFrameDuration = 0
            createPreviewRequest()

            // createPreviewRequest() needed so that the values in the previewBuilder reset to default values, for
            // CONTROL_AE_TARGET_FPS_RANGE and SENSOR_FRAME_DURATION
            try {
                if (cameraSettings.setAEMode(previewBuilder, false)) {
                    setRepeatingRequest()
                }
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to clear preview fps range")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            }
        }
    }

    override val supportedPreviewFpsRange: List<IntArray>
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedPreviewFpsRange")
            val l: MutableList<IntArray> = ArrayList()

            val rr: List<IntArray> = if (wantVideoHighSpeed) hsFpsRanges else aeFpsRanges
            for (r in rr) {
                val ir = intArrayOf(r[0] * 1000, r[1] * 1000)
                if (MyDebug.LOG) Log.d(TAG, "    : " + ir.contentToString())
                l.add(ir)
            }
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "   using " + (if (wantVideoHighSpeed) "high speed" else "ae") + " preview fps ranges"
                )
            }

            return l
        }

    private fun convertFocusModeToValue(focusMode: Int): String {
        if (MyDebug.LOG) Log.d(
            TAG,
            "convertFocusModeToValue: $focusMode"
        )
        var focusValue = ""
        when (focusMode) {
            CaptureRequest.CONTROL_AF_MODE_AUTO -> focusValue = "focus_mode_auto"
            CaptureRequest.CONTROL_AF_MODE_MACRO -> focusValue = "focus_mode_macro"
            CaptureRequest.CONTROL_AF_MODE_EDOF -> focusValue = "focus_mode_edof"
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> focusValue =
                "focus_mode_continuous_picture"

            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> focusValue =
                "focus_mode_continuous_video"

            CaptureRequest.CONTROL_AF_MODE_OFF -> focusValue =
                "focus_mode_manual2" // n.b., could be infinity
        }
        return focusValue
    }

    override var focusValue: String?
        get() {
            var focusMode = previewBuilder?.get(CaptureRequest.CONTROL_AF_MODE)
            if (focusMode == null) focusMode = CaptureRequest.CONTROL_AF_MODE_AUTO
            return convertFocusModeToValue(focusMode)
        }
        set(focusValue) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "setFocusValue: $focusValue"
            )
            blockForExtensions()
            val focusMode: Int
            when (focusValue) {
                "focus_mode_auto", "focus_mode_locked" -> focusMode =
                    CaptureRequest.CONTROL_AF_MODE_AUTO

                "focus_mode_infinity" -> {
                    focusMode = CaptureRequest.CONTROL_AF_MODE_OFF
                    cameraSettings.focusDistance = 0.0f
                }

                "focus_mode_manual2" -> {
                    focusMode = CaptureRequest.CONTROL_AF_MODE_OFF
                    cameraSettings.focusDistance = cameraSettings.focusDistanceManual
                }

                "focus_mode_macro" -> focusMode = CaptureRequest.CONTROL_AF_MODE_MACRO
                "focus_mode_edof" -> focusMode = CaptureRequest.CONTROL_AF_MODE_EDOF
                "focus_mode_continuous_picture" -> focusMode =
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE

                "focus_mode_continuous_video" -> focusMode =
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO

                else -> {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "setFocusValue() received unknown focus value $focusValue"
                    )
                    return
                }
            }
            cameraSettings.hasAfMode = true
            cameraSettings.afMode = focusMode
            previewBuilder?.let { cameraSettings.setFocusMode(it) }
            // also need to set distance, in case changed between infinity, manual or other modes
            previewBuilder?.let { cameraSettings.setFocusDistance(it) }
            //camera_settings.setTonemapProfile(previewBuilder); // testing - if using focus mode to test video profiles, see testNew flag
            try {
                setRepeatingRequest()
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to set focus mode")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            }
        }

    override val focusDistance: Float
        get() = cameraSettings.focusDistance

    override fun setFocusDistance(focusDistance: Float): Boolean {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setFocusDistance: $focusDistance"
        )
        if (cameraSettings.focusDistance == focusDistance) {
            if (MyDebug.LOG) Log.d(TAG, "already set")
            return false
        }
        cameraSettings.focusDistance = focusDistance
        cameraSettings.focusDistanceManual = focusDistance
        previewBuilder?.let { cameraSettings.setFocusDistance(it) }
        try {
            setRepeatingRequest()
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to set focus distance")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
        }
        return true
    }

    override fun setFocusBracketingNImages(nImages: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setFocusBracketingNImages: $nImages"
        )
        captureCoordinator.focusBracketingNImages = nImages
    }

    override fun setFocusBracketingAddInfinity(focusBracketingAddInfinity: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setFocusBracketingAddInfinity: $focusBracketingAddInfinity"
        )
        captureCoordinator.focusBracketingAddInfinity = focusBracketingAddInfinity
    }

    override var focusBracketingSourceDistance: Float
        get() = captureCoordinator.focusBracketingSourceDistance
        set(focusBracketingSourceDistance) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "setFocusBracketingSourceDistance: $focusBracketingSourceDistance"
            )
            captureCoordinator.focusBracketingSourceDistance = focusBracketingSourceDistance
        }

    override fun setFocusBracketingSourceDistanceFromCurrent() {
        if (captureResultHasFocusDistance) {
            captureCoordinator.focusBracketingSourceDistance = captureResultFocusDistance
        }
    }

    override var focusBracketingTargetDistance: Float
        get() = captureCoordinator.focusBracketingTargetDistance
        set(focusBracketingTargetDistance) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "setFocusBracketingTargetDistance: $focusBracketingTargetDistance"
            )
            captureCoordinator.focusBracketingTargetDistance = focusBracketingTargetDistance
        }

    /** Decides whether we should be using fake precapture mode.
     */
    private fun updateUseFakePrecaptureMode(flashValue: String) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "useFakePrecaptureMode: $flashValue"
        )
        val frontscreenFlash =
            flashValue == "flash_frontscreen_auto" || flashValue == "flash_frontscreen_on"
        useFakePrecaptureMode = if (frontscreenFlash) {
            true
        } else if (burstType !== BurstType.BURSTTYPE_NONE) true
        else if (cameraSettings.hasIso) true
        else {
            useFakePrecapture
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "use_fake_precapture_mode set to: $useFakePrecaptureMode"
        )
    }

    override var flashValue: String
        get() {
            // returns "" if flash isn't supported
            if (characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) {
                return ""
            }
            return cameraSettings.flashValue
        }
        set(flashValue) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "setFlashValue: $flashValue"
            )
            if (cameraSettings.flashValue == flashValue) {
                if (MyDebug.LOG) Log.d(TAG, "flash value already set")
                return
            }

            try {
                updateUseFakePrecaptureMode(flashValue)

                if (cameraSettings.flashValue == "flash_torch" && flashValue != "flash_off") {
                    // hack - if switching to something other than flashOff, we first need to turn torch off, otherwise torch remains on (at least on Nexus 6 and Nokia 8)
                    cameraSettings.flashValue = "flash_off"
                    cameraSettings.setAEMode(previewBuilder, false)
                    val request = previewBuilder?.build()


                    // need to wait until torch actually turned off
                    cameraSettings.flashValue = flashValue
                    cameraSettings.setAEMode(previewBuilder, false)
                    pushRepeatingRequestWhenTorchOff = true
                    pushRepeatingRequestWhenTorchOffId = request

                    setRepeatingRequest(request)
                } else {
                    cameraSettings.flashValue = flashValue
                    if (cameraSettings.setAEMode(previewBuilder, false)) {
                        setRepeatingRequest()
                    }
                }
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to set flash mode")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            }
        }

    override fun setRecordingHint(hint: Boolean) {
        // not relevant for CameraController2
    }

    override var autoExposureLock: Boolean
        get() = previewBuilder?.get(CaptureRequest.CONTROL_AE_LOCK) ?: false
        set(enabled) {
            if (enabled) {
                blockForExtensions()
            }
            cameraSettings.aeLock = enabled
            previewBuilder?.let { cameraSettings.setAutoExposureLock(it) }
            try {
                setRepeatingRequest()
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to set auto exposure lock")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            }
        }

    override var autoWhiteBalanceLock: Boolean
        get() = previewBuilder?.get(CaptureRequest.CONTROL_AWB_LOCK) ?: false
        set(enabled) {
            if (enabled) {
                blockForExtensions()
            }
            cameraSettings.wbLock = enabled
            previewBuilder?.let { cameraSettings.setAutoWhiteBalanceLock(it) }
            try {
                setRepeatingRequest()
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to set auto white balance lock")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            }
        }

    override fun setRotation(rotation: Int) {
        cameraSettings.rotation = rotation
    }

    override fun setLocationInfo(location: Location) {
        // don't log location, in case of privacy!
        if (MyDebug.LOG) Log.d(TAG, "setLocationInfo")
        cameraSettings.location = location
    }

    override fun removeLocationInfo() {
        cameraSettings.location = null
    }

    override fun enableShutterSound(enabled: Boolean) {
        this.soundsEnabled = enabled
    }

    private fun playSound(soundName: Int) {
        if (soundsEnabled) {
            // on some devices (e.g., Samsung Galaxy S10e), need to check whether phone on silent!
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                mediaActionSound.play(soundName)
            }
        }
    }

    private val viewableRect: Rect
        /** Returns the viewable rect - this is crop region if available.
         * We need this as callers will pass in (or expect returned) CameraController.Area values that
         * are relative to the current view (i.e., taking Zoom into account) (the old Camera API in
         * CameraController1 always works in terms of the current view, whilst Camera2 works in terms
         * of the full view always). Similarly, for the rect field in CameraController.Face.
         */
        get() {
            if (previewBuilder != null) {
                val cropRect = previewBuilder?.get(CaptureRequest.SCALER_CROP_REGION)
                if (cropRect != null) {
                    return cropRect
                }
            }
            val sensorRect =
                characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            sensorRect!!.right -= sensorRect.left
            sensorRect.left = 0
            sensorRect.bottom -= sensorRect.top
            sensorRect.top = 0
            return sensorRect
        }

    private fun convertRectToCamera2(cropRect: Rect, rect: Rect): Rect {
        // CameraController.Area is always [-1000, -1000] to [1000, 1000] for the viewable region
        // but for CameraController2, we must convert to be relative to the crop region
        val leftF = (rect.left + 1000) / 2000.0
        val topF = (rect.top + 1000) / 2000.0
        val rightF = (rect.right + 1000) / 2000.0
        val bottomF = (rect.bottom + 1000) / 2000.0
        var left = (cropRect.left + leftF * (cropRect.width() - 1)).toInt()
        var right = (cropRect.left + rightF * (cropRect.width() - 1)).toInt()
        var top = (cropRect.top + topF * (cropRect.height() - 1)).toInt()
        var bottom = (cropRect.top + bottomF * (cropRect.height() - 1)).toInt()
        left = max(left.toDouble(), cropRect.left.toDouble()).toInt()
        right = max(right.toDouble(), cropRect.left.toDouble()).toInt()
        top = max(top.toDouble(), cropRect.top.toDouble()).toInt()
        bottom = max(bottom.toDouble(), cropRect.top.toDouble()).toInt()
        left = min(left.toDouble(), cropRect.right.toDouble()).toInt()
        right = min(right.toDouble(), cropRect.right.toDouble()).toInt()
        top = min(top.toDouble(), cropRect.bottom.toDouble()).toInt()
        bottom = min(bottom.toDouble(), cropRect.bottom.toDouble()).toInt()

        return Rect(left, top, right, bottom)
    }

    private fun convertAreaToMeteringRectangle(sensorRect: Rect, area: Area): MeteringRectangle {
        return MeteringAreaConverter.convertAreaToMeteringRectangle(sensorRect, area)
    }

    private fun convertRectFromCamera2(cropRect: Rect, camera2Rect: Rect): Rect {
        return MeteringAreaConverter.convertRectFromCamera2(cropRect, camera2Rect)
    }

    private fun convertMeteringRectangleToArea(
        sensorRect: Rect,
        meteringRectangle: MeteringRectangle
    ): Area {
        return MeteringAreaConverter.convertMeteringRectangleToArea(sensorRect, meteringRectangle)
    }

    private fun convertFromCameraFace(
        sensorRect: Rect,
        camera2Face: android.hardware.camera2.params.Face
    ): Face {
        return MeteringAreaConverter.convertFromCameraFace(sensorRect, camera2Face)
    }

    override fun setFocusAndMeteringArea(areas: List<Area>): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "setFocusAndMeteringArea")
        blockForExtensions()
        val sensorRect = viewableRect
        if (MyDebug.LOG) Log.d(
            TAG,
            "sensor_rect: " + sensorRect.left + " , " + sensorRect.top + " x " + sensorRect.right + " , " + sensorRect.bottom
        )
        val maxAf = characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val regions =
            focusMeteringCoordinator.calculateFocusAndMeteringAreas(areas, sensorRect, maxAf, maxAe)
        cameraSettings.afRegions = regions.first
        if (regions.first != null) {
            cameraSettings.setAFRegions(previewBuilder)
        } else {
            cameraSettings.afRegions = null
        }
        cameraSettings.aeRegions = regions.second
        if (regions.second != null) {
            cameraSettings.setAERegions(previewBuilder)
        } else {
            cameraSettings.aeRegions = null
        }
        val hasFocus = regions.first != null
        val hasMetering = regions.second != null
        if (hasFocus || hasMetering) {
            try {
                setRepeatingRequest()
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to set focus and/or metering regions")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            }
        }
        return hasFocus
    }

    override fun clearFocusAndMetering() {
        if (MyDebug.LOG) Log.d(TAG, "clearFocusAndMetering")
        blockForExtensions()
        val sensorRect = viewableRect
        val maxAf = characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val regions =
            focusMeteringCoordinator.calculateClearFocusAndMeteringAreas(sensorRect, maxAf, maxAe)
        cameraSettings.afRegions = regions.first
        if (regions.first != null) {
            cameraSettings.setAFRegions(previewBuilder)
        } else {
            cameraSettings.afRegions = null
        }
        cameraSettings.aeRegions = regions.second
        if (regions.second != null) {
            cameraSettings.setAERegions(previewBuilder)
        } else {
            cameraSettings.aeRegions = null
        }
        val hasFocus = regions.first != null
        val hasMetering = regions.second != null
        if (hasFocus || hasMetering) {
            try {
                setRepeatingRequest()
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to clear focus and metering regions")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            }
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "af_regions: " + cameraSettings.afRegions.contentToString())
            Log.d(TAG, "ae_regions: " + cameraSettings.aeRegions.contentToString())
        }
    }

    override val focusAreas: List<Area>?
        get() {
            val maxAf = characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            if (maxAf == 0 || cameraSettings.afRegions == null) return null
            val meteringRectangles =
                previewBuilder?.get(CaptureRequest.CONTROL_AF_REGIONS) ?: return null
            return focusMeteringCoordinator.extractAreas(meteringRectangles, viewableRect, maxAf)
        }

    override val meteringAreas: List<Area>?
        get() {
            val maxAe = characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            if (maxAe == 0 || cameraSettings.aeRegions == null) return null
            val meteringRectangles =
                previewBuilder?.get(CaptureRequest.CONTROL_AE_REGIONS) ?: return null
            return focusMeteringCoordinator.extractAreas(meteringRectangles, viewableRect, maxAe)
        }

    override fun supportsAutoFocus(): Boolean {
        if (previewBuilder == null) return false
        if (sessionType == SessionType.SESSIONTYPE_EXTENSION) return false
        val focusMode = previewBuilder?.get(CaptureRequest.CONTROL_AF_MODE) ?: return false
        return focusMode == CaptureRequest.CONTROL_AF_MODE_AUTO || focusMode == CaptureRequest.CONTROL_AF_MODE_MACRO
    }

    fun supportsFocusRegions(): Boolean {
        return (characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
    }

    override fun supportsMetering(): Boolean {
        return (characteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0
    }

    fun supportsOpticalStabilization(): Boolean = supportsOpticalStabilization

    fun supportsTonemapPresetCurve(): Boolean = supportsTonemapPresetCurve

    override fun focusIsContinuous(): Boolean {
        if (previewBuilder == null) return false
        if (sessionType == SessionType.SESSIONTYPE_EXTENSION) return false
        val focusMode = previewBuilder?.get(CaptureRequest.CONTROL_AF_MODE) ?: return false
        return focusMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE || focusMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
    }

    override fun focusIsVideo(): Boolean {
        if (previewBuilder == null) return false
        if (sessionType == SessionType.SESSIONTYPE_EXTENSION) return false
        val focusMode = previewBuilder?.get(CaptureRequest.CONTROL_AF_MODE) ?: return false
        return focusMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
    }

    override fun setPreviewDisplay(holder: SurfaceHolder?) {
        if (MyDebug.LOG) {
            Log.d(TAG, "setPreviewDisplay")
            Log.e(TAG, "SurfaceHolder not supported for CameraController2!")
            Log.e(TAG, "Should use setPreviewTexture() instead")
        }
        throw RuntimeException() // throw as RuntimeException, as this is a programming error
    }

    override fun setPreviewTexture(texture: TextureView) {
        if (MyDebug.LOG) {
            Log.d(TAG, "setPreviewTexture: $texture")
            Log.d(TAG, "surface: " + texture.surfaceTexture)
        }
        if (this.texture != null) {
            if (MyDebug.LOG) Log.d(TAG, "preview texture already set")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        this.texture = texture.surfaceTexture
    }

    @Throws(CameraAccessException::class)
    private fun setRepeatingRequest() {
        setRepeatingRequest(previewBuilder?.build())
    }

    @Throws(CameraAccessException::class)
    private fun setRepeatingRequest(request: CaptureRequest?) {
        if (MyDebug.LOG) Log.d(TAG, "setRepeatingRequest")
        if (request == null) return
        synchronized(backgroundCameraLock) {
            if (camera == null || !hasCaptureSession()) {
                if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                return
            }
            try {
                if (sessionType == SessionType.SESSIONTYPE_EXTENSION) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        extensionSession!!.setRepeatingRequest(
                            request,
                            executor!!, previewExtensionCaptureCallback!!
                        )
                    }
                } else if (isVideoHighSpeed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val captureSessionHighSpeed =
                        captureSession as CameraConstrainedHighSpeedCaptureSession?
                    val mPreviewBuilderBurst =
                        captureSessionHighSpeed!!.createHighSpeedRequestList(request)
                    captureSessionHighSpeed.setRepeatingBurst(
                        mPreviewBuilderBurst,
                        previewCaptureCallback,
                        handler
                    )
                } else {
                    captureSession?.setRepeatingRequest(request, previewCaptureCallback, handler)
                }
                if (MyDebug.LOG) Log.d(TAG, "setRepeatingRequest done")
            } catch (e: IllegalStateException) {
                if (MyDebug.LOG) Log.d(TAG, "captureSession already closed!")
                e.printStackTrace()
                // got this as a Google Play exception (from onCaptureCompleted->processCompleted) - this means the capture session is already closed
            }
        }
    }

    /** Performs a "capture" - note that in practice this isn't used for taking photos, but for
     * one-off captures for the preview stream (e.g., to trigger focus).
     */
    @Throws(CameraAccessException::class)
    private fun capture(request: CaptureRequest? = previewBuilder?.build()) {
        if (MyDebug.LOG) Log.d(TAG, "capture: $request")
        if (request == null) return
        synchronized(backgroundCameraLock) {
            if (camera == null || !hasCaptureSession()) {
                if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                return
            }
            blockForExtensions() // not yet supported for extension sessions
            captureSession?.capture(request, previewCaptureCallback, handler)
        }
    }

    private fun createPreviewRequest() {
        if (MyDebug.LOG) Log.d(TAG, "createPreviewRequest")
        if (camera == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not available!")
            return
        }
        if (MyDebug.LOG) Log.d(TAG, "camera: $camera")
        try {
            previewBuilder = camera?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewBuilder?.set(
                CaptureRequest.CONTROL_CAPTURE_INTENT,
                CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
            )
            previewIsVideoMode = false
            previewBuilder?.let { cameraSettings.setupBuilder(it, false) }
            if (MyDebug.LOG) Log.d(TAG, "successfully created preview request")
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to create capture request")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
        }
    }

    override fun updatePreviewTexture() {
        if (MyDebug.LOG) Log.d(TAG, "updatePreviewTexture")
        if (texture != null) {
            if (previewWidth == 0 || previewHeight == 0) {
                if (MyDebug.LOG) Log.d(TAG, "preview size not yet set")
            } else {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "preview size: $previewWidth x $previewHeight"
                )
                this.testTextureViewBufferW = previewWidth
                this.testTextureViewBufferH = previewHeight
                texture!!.setDefaultBufferSize(previewWidth, previewHeight)
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    private fun createOutputConfigurationList(
        surfaces: List<Surface>,
        previewSurface: Surface?
    ): List<OutputConfiguration> {
        val outputs: MutableList<OutputConfiguration> = ArrayList()
        for (surface in surfaces) {
            val config = OutputConfiguration(surface)
            if (cameraIdSPhysical != null) {
                config.setPhysicalCameraId(cameraIdSPhysical)
            }
            // On Galaxy S24+ at least, we seem to get UltraHDR photos even without setting DynamicRangeProfiles.HLG10
            // furthermore, calling setDynamicRangeProfile with HLG10 gives photos with much lower saturation, so have
            // disabled this
            /*if( wantJpegR && surface == previewSurface && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ) {
                config.setDynamicRangeProfile(DynamicRangeProfiles.HLG10);
            }*/
            outputs.add(config)
        }
        return outputs
    }

    @Throws(CameraControllerException::class)
    private fun createCaptureSession(
        videoRecorder: MediaRecorder?,
        wantPhotoVideoRecording: Boolean
    ) {
        if (MyDebug.LOG) Log.d(TAG, "create capture session")

        if (previewBuilder == null) {
            if (MyDebug.LOG) Log.d(TAG, "previewBuilder not present!")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        if (camera == null) {
            if (MyDebug.LOG) Log.e(TAG, "no camera")
            return
        }

        closeCaptureSession()

        if (sessionType == SessionType.SESSIONTYPE_EXTENSION) {
            // check parameters are compatible with extension sessions
            // we check here rather than when setting those parameters, to avoid problems with
            // ordering (e.g., the caller sets those parameters, and then switches to an
            // extension session)
            if (wantVideoHighSpeed) {
                throw RuntimeException("want_video_high_speed not supported for extension session")
            } else if (burstType !== BurstType.BURSTTYPE_NONE) {
                throw RuntimeException("burst_type not supported for extension session")
            } else if (wantJpegR) {
                throw RuntimeException("want_jpeg_r not supported for extension session")
            } else if (wantRaw) {
                throw RuntimeException("want_raw not supported for extension session")
            } else if (cameraSettings.hasIso) {
                throw RuntimeException("has_iso not supported for extension session")
            } else if (cameraSettings.aeTargetFpsRange != null) {
                throw RuntimeException("ae_target_fps_range not supported for extension session")
            } else if (cameraSettings.sensorFrameDuration > 0) {
                throw RuntimeException("sensor_frame_duration not supported for extension session")
            } else if (cameraSettings.aeLock) {
                throw RuntimeException("ae_lock not supported for extension session")
            } else if (cameraSettings.wbLock) {
                throw RuntimeException("wb_lock not supported for extension session")
            } else if (cameraSettings.hasFaceDetectMode) {
                throw RuntimeException("has_face_detect_mode not supported for extension session")
            } else if (faceDetectionListener != null) {
                throw RuntimeException("face_detection_listener not supported for extension session")
            }
        }

        try {
            if (videoRecorder != null) {
                if (supportsPhotoVideoRecording && !wantVideoHighSpeed && wantPhotoVideoRecording) {
                    createPictureImageReader()
                } else {
                    closePictureImageReader()
                }
            } else {
                // in some cases need to recreate picture imageReader and the texture default buffer size (e.g., see test testTakePhotoPreviewPaused())
                createPictureImageReader()
            }
            if (texture != null) {
                // need to set the texture size
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "set size of preview texture: $previewWidth x $previewHeight"
                )
                if (previewWidth == 0 || previewHeight == 0) {
                    if (MyDebug.LOG) Log.e(TAG, "application needs to call setPreviewSize()")
                    throw RuntimeException() // throw as RuntimeException, as this is a programming error
                }
                updatePreviewTexture()
                // also need to create a new surface for the texture, in case the size has changed - but make sure we remove the old one first!
                synchronized(backgroundCameraLock) {
                    if (::_surfaceTexture.isInitialized) {
                        if (MyDebug.LOG) Log.d(TAG, "remove old target: $_surfaceTexture")
                        previewBuilder?.removeTarget(_surfaceTexture)
                    }
                    this._surfaceTexture = Surface(texture)
                    if (MyDebug.LOG) Log.d(TAG, "created new target: $_surfaceTexture")
                }
            }
            if (videoRecorder != null) {
                if (MyDebug.LOG) Log.d(TAG, "creating capture session for video recording")
            } else {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "picture size: " + imageReader!!.width + " x " + imageReader!!.height
                )
            }
            /*if( MyDebug.LOG )
            Log.d(TAG, "preview size: " + previewImageReader.getWidth() + " x " + previewImageReader.getHeight());*/
            if (MyDebug.LOG) Log.d(
                TAG,
                "set preview size: " + this.previewWidth + " x " + this.previewHeight
            )

            synchronized(backgroundCameraLock) {
                videoRecorderSurface = videoRecorder?.surface
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "video_recorder_surface: $videoRecorderSurface"
                )
            }

            class MyStateCallback : CameraCaptureSession.StateCallback() {
                var callbackDone: Boolean =
                    false // must synchronize on this and notifyAll when setting to true

                fun onConfigured(
                    session: CameraCaptureSession?,
                    eSession: CameraExtensionSession?
                ) {
                    if (camera == null) {
                        if (MyDebug.LOG) {
                            Log.d(TAG, "camera is closed")
                        }
                        synchronized(backgroundCameraLock) {
                            callbackDone = true
                            (backgroundCameraLock as Object).notifyAll()
                        }
                        return
                    }
                    synchronized(backgroundCameraLock) {
                        captureSession = session
                        extensionSession = eSession
                        previewBuilder?.addTarget(this@CameraController2._surfaceTexture)
                        if (videoRecorder != null) {
                            if (MyDebug.LOG) {
                                Log.d(
                                    TAG,
                                    "add video recorder surface to previewBuilder: $videoRecorderSurface"
                                )
                            }
                            previewBuilder?.addTarget(videoRecorderSurface!!)
                        }
                        try {
                            setRepeatingRequest()
                        } catch (e: CameraAccessException) {
                            if (MyDebug.LOG) {
                                Log.e(TAG, "failed to start preview")
                                Log.e(TAG, "reason: " + e.reason)
                                Log.e(TAG, "message: " + e.message)
                            }
                            e.printStackTrace()
                            // we indicate that we failed to start the preview by setting captureSession back to null
                            // this will cause a CameraControllerException to be thrown below
                            captureSession = null
                            extensionSession = null
                        }
                    }
                    synchronized(backgroundCameraLock) {
                        callbackDone = true
                        (backgroundCameraLock as Object).notifyAll()
                    }
                }

                override fun onConfigured(session: CameraCaptureSession) {
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "onConfigured: $session"
                        )
                    }
                    onConfigured(session, null)
                }

                fun onConfigureFailed() {
                    synchronized(backgroundCameraLock) {
                        callbackDone = true
                        (backgroundCameraLock as Object).notifyAll()
                    }
                    // don't throw CameraControllerException here, as won't be caught - instead we throw CameraControllerException below
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "onConfigureFailed: $session"
                        )
                    }
                    onConfigureFailed()
                } /*@Override
                public void onReady(CameraCaptureSession session) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "onReady: " + session);
                    if( pendingRequestWhenReady != null ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "have pendingRequestWhenReady: " + pendingRequestWhenReady);
                        CaptureRequest request = pendingRequestWhenReady;
                        pendingRequestWhenReady = null;
                        try {
                            captureSession.capture(request, previewCaptureCallback, handler);
                        }
                        catch(CameraAccessException e) {
                            if( MyDebug.LOG ) {
                                Log.e(TAG, "failed to take picture");
                                Log.e(TAG, "reason: " + e.getReason());
                                Log.e(TAG, "message: " + e.getMessage());
                            }
                            e.printStackTrace();
                            jpegTodo = false;
                            rawTodo = false;
                            pictureCb = null;
                            if( takePictureErrorCb != null ) {
                                take_picture_error_cb.onError();
                                takePictureErrorCb = null;
                            }
                        }
                    }
                }*/
            }

            val myStateCallback = MyStateCallback()

            val previewSurface: Surface
            val surfaces: List<Surface>
            synchronized(backgroundCameraLock) {
                previewSurface = _previewSurface
                surfaces = if (videoRecorder != null) {
                    if (supportsPhotoVideoRecording && !wantVideoHighSpeed && wantPhotoVideoRecording) {
                        listOf(
                            previewSurface,
                            videoRecorderSurface!!,
                            imageReader!!.surface
                        )
                    } else {
                        listOf(previewSurface, videoRecorderSurface!!)
                    }
                    // n.b., raw not supported for photo snapshots while video recording
                } else if (wantVideoHighSpeed) {
                    // future proofing - at the time of writing wantVideoHighSpeed is only set when recording video,
                    // but if ever this is changed, can only support the previewSurfaceField as a target
                    listOf(previewSurface)
                } else if (imageReaderRaw != null) {
                    listOf(previewSurface, imageReader!!.surface, imageReaderRaw!!.surface)
                } else {
                    listOf(previewSurface, imageReader!!.surface)
                }
                if (MyDebug.LOG) {
                    Log.d(TAG, "texture: $texture")
                    Log.d(
                        TAG,
                        "previewSurface: $previewSurface"
                    )
                    Log.d(TAG, "handler: $handler")
                    Log.d(TAG, "surfaces: $surfaces")
                }
            }
            if (MyDebug.LOG) {
                if (videoRecorder == null) {
                    if (imageReaderRaw != null) {
                        Log.d(
                            TAG,
                            "imageReaderRaw: $imageReaderRaw"
                        )
                        Log.d(TAG, "imageReaderRaw: " + imageReaderRaw!!.width)
                        Log.d(TAG, "imageReaderRaw: " + imageReaderRaw!!.height)
                        Log.d(TAG, "imageReaderRaw: " + imageReaderRaw!!.imageFormat)
                    } else {
                        Log.d(
                            TAG,
                            "imageReader: $imageReader"
                        )
                        Log.d(TAG, "imageReader width: " + imageReader!!.width)
                        Log.d(TAG, "imageReader height: " + imageReader!!.height)
                        Log.d(TAG, "imageReader format: " + imageReader!!.imageFormat)
                    }
                }
            }
            if (sessionType == SessionType.SESSIONTYPE_EXTENSION) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (MyDebug.LOG) Log.d(TAG, "create extension capture session")
                    //int extension = CameraExtensionCharacteristics.EXTENSION_AUTOMATIC;
                    //int extension = CameraExtensionCharacteristics.EXTENSION_BOKEH;
                    val extension = cameraExtension
                    val outputs = createOutputConfigurationList(surfaces, _surfaceTexture)
                    val extensionConfiguration = ExtensionSessionConfiguration(
                        extension,
                        outputs,
                        executor!!,
                        object : CameraExtensionSession.StateCallback() {
                            override fun onConfigured(session: CameraExtensionSession) {
                                if (MyDebug.LOG) {
                                    Log.d(
                                        TAG,
                                        "onConfigured: $session"
                                    )
                                }
                                myStateCallback.onConfigured(null, session)
                            }

                            override fun onConfigureFailed(session: CameraExtensionSession) {
                                if (MyDebug.LOG) {
                                    Log.d(
                                        TAG,
                                        "onConfigureFailed: $session"
                                    )
                                }
                                myStateCallback.onConfigureFailed()
                            }

                            override fun onClosed(session: CameraExtensionSession) {
                                if (MyDebug.LOG) {
                                    Log.d(
                                        TAG,
                                        "onClosed: $session"
                                    )
                                }
                            }
                        }
                    )
                    camera?.createExtensionSession(extensionConfiguration)
                }
                isVideoHighSpeed = false
            } else if (videoRecorder != null && wantVideoHighSpeed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                //if( wantVideoHighSpeed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
                if (MyDebug.LOG) Log.d(TAG, "create high speed capture session")
                if ((cameraIdSPhysical != null || wantJpegR) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val outputs = createOutputConfigurationList(surfaces, _surfaceTexture)
                    val sessionConfiguration = SessionConfiguration(
                        SessionConfiguration.SESSION_HIGH_SPEED, outputs,
                        executor!!, myStateCallback
                    )
                    camera?.createCaptureSession(sessionConfiguration)
                } else {
                    camera?.createConstrainedHighSpeedCaptureSession(
                        surfaces,
                        myStateCallback,
                        handler
                    )
                }
                isVideoHighSpeed = true
            } else {
                if (MyDebug.LOG) Log.d(TAG, "create capture session")
                try {
                    if ((cameraIdSPhysical != null || wantJpegR) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val outputs = createOutputConfigurationList(surfaces, _surfaceTexture)
                        /*camera.createCaptureSessionByOutputConfigurations(outputs,
                                myStateCallback,
                                handler);*/
                        val sessionConfiguration = SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR, outputs,
                            executor!!, myStateCallback
                        )
                        camera?.createCaptureSession(sessionConfiguration)
                    } else {
                        camera?.createCaptureSession(
                            surfaces,
                            myStateCallback,
                            handler
                        )
                    }
                    isVideoHighSpeed = false
                } catch (e: NullPointerException) {
                    // have had this from some devices on Google Play, from deep within createCaptureSession
                    // note, we put the catch here rather than below, to not mask nullpointerexceptions
                    // from my code
                    if (MyDebug.LOG) {
                        Log.e(TAG, "NullPointerException trying to create capture session")
                        Log.e(TAG, "message: " + e.message)
                    }
                    e.printStackTrace()
                    throw CameraControllerException()
                }
            }
            if (MyDebug.LOG) Log.d(TAG, "wait until session created...")
            // n.b., we use the backgroundCameraLock lock instead of a separate lock, so that it's safe to call this
            // method under the backgroundCameraLock (if we did so but used a separate lock, we'd hang here, because
            // MyStateCallback.onConfigured() needs to lock on backgroundCameraLock, before it completes and sets
            // myStateCallback.callbackDone to true.
            synchronized(backgroundCameraLock) {
                while (!myStateCallback.callbackDone) {
                    try {
                        // release the lock, and wait until myStateCallback calls notifyAll()
                        (backgroundCameraLock as Object).wait()
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
            }
            if (MyDebug.LOG) {
                if (captureSession != null) Log.d(
                    TAG,
                    "created captureSession: $captureSession"
                )
                if (extensionSession != null) Log.d(
                    TAG,
                    "created extensionSession: $extensionSession"
                )
            }
            if (sessionType == SessionType.SESSIONTYPE_EXTENSION) {
                resetCaptureResultInfo() // important as extension modes don't receive capture result info
            }
            synchronized(backgroundCameraLock) {
                if (!hasCaptureSession()) {
                    if (MyDebug.LOG) Log.e(TAG, "failed to create capture session")
                    throw CameraControllerException()
                }
            }
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "CameraAccessException trying to create capture session")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
            throw CameraControllerException()
        } catch (e: IllegalArgumentException) {
            // have had crashes from Google Play, from both createConstrainedHighSpeedCaptureSession and
            // createCaptureSession
            if (MyDebug.LOG) {
                Log.e(TAG, "IllegalArgumentException trying to create capture session")
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
            throw CameraControllerException()
        }
    }

    @Throws(CameraControllerException::class)
    override fun startPreview() {
        if (MyDebug.LOG) Log.d(TAG, "startPreview")

        if (!cameraSettings.hasAfMode && initialFocusMode != null && sessionType != SessionType.SESSIONTYPE_EXTENSION) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "user didn't specify focus, so set to: $initialFocusMode"
            )
            // If the caller hasn't set a focus mode, but focus modes are supported, it's still better to explicitly set one rather than leaving to the
            // builder's default - e.g., problem on Android emulator with LIMITED camera where it only supported infinity focus (CONTROL_AF_MODE_OFF), but
            // the preview builder defaults to CONTROL_AF_MODE_CONTINUOUS_PICTURE! This meant we froze when trying to take a photo, because we thought
            // we were in continuous picture mode and so waited in state STATE_WAITING_AUTOFOCUS, but the focus never occurred.
            // Ideally the caller to CameraController2 (Preview) should always explicitly set a focus mode if at least 1 focus mode is supported. At the
            // time of writing, Preview only sets a focus if at least 2 focus modes are supported. But even if we fix that in the future, still good to have
            // well-defined behavior at the CameraController level.
            focusValue = initialFocusMode
        }

        synchronized(backgroundCameraLock) {
            if (hasCaptureSession()) {
                try {
                    setRepeatingRequest()
                } catch (e: CameraAccessException) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "failed to start preview")
                        Log.e(TAG, "reason: " + e.reason)
                        Log.e(TAG, "message: " + e.message)
                    }
                    e.printStackTrace()
                    // do via CameraControllerException instead of previewErrorCb, so caller immediately knows preview has failed
                    throw CameraControllerException()
                }
                return
            }
        }
        createCaptureSession(null, false)
    }

    override fun stopRepeating() {
        if (MyDebug.LOG) Log.d(TAG, "stopRepeating: $this")
        stopPreview(false)
    }

    override fun stopPreview() {
        if (MyDebug.LOG) Log.d(TAG, "stopPreview: $this")
        stopPreview(true)
    }

    fun stopPreview(closeCaptureSession: Boolean) {
        synchronized(backgroundCameraLock) {
            if (camera == null || !hasCaptureSession()) {
                if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                return
            }
            try {
                //pendingRequestWhenReady = null;

                try {
                    if (sessionType == SessionType.SESSIONTYPE_EXTENSION) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            extensionSession!!.stopRepeating()
                        }
                    } else {
                        captureSession?.stopRepeating()
                    }
                } catch (e: IllegalStateException) {
                    if (MyDebug.LOG) Log.d(TAG, "captureSession already closed!")
                    e.printStackTrace()
                    // got this as a Google Play exception
                    // we still call close() below, as it has no effect if captureSession is already closed
                }
                if (closeCaptureSession) {
                    // although stopRepeating() alone will pause the preview, seems better to close captureSession altogether - this allows the app to make changes such as changing the picture size
                    closeCaptureSession()
                }
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to stop repeating")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            }
            // simulate CameraController1 behavior where face detection is stopped when we stop preview
            if (cameraSettings.hasFaceDetectMode && closeCaptureSession) {
                if (MyDebug.LOG) Log.d(TAG, "cancel face detection")
                cameraSettings.hasFaceDetectMode = false
                previewBuilder?.let { cameraSettings.setFaceDetectMode(it) }
                // no need to call setRepeatingRequest(), we're just setting the cameraSettings for when we restart the preview
            }
        }
    }

    override fun startFaceDetection(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "startFaceDetection")
        blockForExtensions()
        if (previewBuilder?.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != null && previewBuilder?.get(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE
            ) != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
        ) {
            if (MyDebug.LOG) Log.d(TAG, "face detection already enabled")
            return false
        }
        if (supportsFaceDetectModeFull) {
            if (MyDebug.LOG) Log.d(TAG, "use full face detection")
            cameraSettings.hasFaceDetectMode = true
            cameraSettings.faceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
        } else if (supportsFaceDetectModeSimple) {
            if (MyDebug.LOG) Log.d(TAG, "use simple face detection")
            cameraSettings.hasFaceDetectMode = true
            cameraSettings.faceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
        } else {
            Log.e(TAG, "startFaceDetection() called but face detection not available")
            return false
        }
        previewBuilder?.let {
            cameraSettings.setFaceDetectMode(it)
            cameraSettings.setSceneMode(it) // also need to set the scene mode
        }
        try {
            setRepeatingRequest()
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to start face detection")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
            return false
        }
        return true
    }

    override fun setFaceDetectionListener(listener: FaceDetectionListener?) {
        if (listener != null) {
            blockForExtensions()
        }
        this.faceDetectionListener = listener
        this.lastFacesDetected = -1
    }

    override fun autoFocus(cb: AutoFocusCallback, captureFollowsAutofocusHint: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "autoFocus")
            Log.d(
                TAG,
                "capture_follows_autofocus_hint? $captureFollowsAutofocusHint"
            )
        }
        var pushAutofocusCb: AutoFocusCallback? = null
        synchronized(backgroundCameraLock) {
            fakePrecaptureTorchFocusPerformed = false
            if (camera == null || !hasCaptureSession()) {
                if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                // should call the callback, so the application isn't left waiting (e.g., when we autofocus before trying to take a photo)
                cb.onAutoFocus(false)
                return
            }
            val focusMode = previewBuilder?.get(CaptureRequest.CONTROL_AF_MODE)
            if (MyDebug.LOG) Log.d(
                TAG, "focus mode: " + (focusMode
                    ?: "null")
            )
            if (focusMode == null) {
                // we preserve the old Camera API where calling autoFocus() on a device without autofocus immediately calls the callback
                // (unclear if Open Kamera needs this, but just to be safe and consistent between camera APIs)
                if (MyDebug.LOG) Log.d(TAG, "no focus mode")
                cb.onAutoFocus(true)
                return
            } else if (sessionType == SessionType.SESSIONTYPE_EXTENSION) {
                if (MyDebug.LOG) Log.d(TAG, "no auto focus for extensions")
                cb.onAutoFocus(true)
                return
            } else if ((!DO_AF_TRIGGER_FOR_CONTINUOUS || useFakePrecaptureMode) && focusMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                // See note above for doAfTriggerForContinuous
                if (MyDebug.LOG) Log.d(TAG, "skip af trigger due to continuous mode")
                this.captureFollowsAutofocusHint = captureFollowsAutofocusHint
                this.autofocusCb = cb
                this.autofocusTimeMs = System.currentTimeMillis()
                return
            } else if (isVideoHighSpeed) {
                // CONTROL_AF_TRIGGER_IDLE/CONTROL_AF_TRIGGER_START not supported for high speed video
                cb.onAutoFocus(true)
                return
            }
            /*if( state == STATE_WAITING_AUTOFOCUS ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "already waiting for an autofocus");
                // need to update the callback!
                this.captureFollowsAutofocusHint = captureFollowsAutofocusHint;
                this.autofocusCb = cb;
                this.autofocusTimeMs = System.currentTimeMillis();
                return;
            }*/
            val afBuilder = previewBuilder
            if (MyDebug.LOG) {
                run {
                    val areas = afBuilder!!.get(CaptureRequest.CONTROL_AF_REGIONS)
                    var i = 0
                    while (areas != null && i < areas.size) {
                        Log.d(
                            TAG,
                            i.toString() + " focus area: " + areas[i].x + " , " + areas[i].y + " : " + areas[i].width + " x " + areas[i].height + " weight " + areas[i].meteringWeight
                        )
                        i++
                    }
                }
                run {
                    val areas = afBuilder!!.get(CaptureRequest.CONTROL_AE_REGIONS)
                    var i = 0
                    while (areas != null && i < areas.size) {
                        Log.d(
                            TAG,
                            i.toString() + " metering area: " + areas[i].x + " , " + areas[i].y + " : " + areas[i].width + " x " + areas[i].height + " weight " + areas[i].meteringWeight
                        )
                        i++
                    }
                }
            }
            if (MyDebug.LOG) Log.d(TAG, "state is now STATE_WAITING_AUTOFOCUS")
            state = STATE_WAITING_AUTOFOCUS
            precaptureStateChangeTimeMs = -1
            this.captureFollowsAutofocusHint = captureFollowsAutofocusHint
            this.autofocusCb = cb
            this.autofocusTimeMs = System.currentTimeMillis()
            try {
                if (useFakePrecaptureMode) {
                    var wantFlash = false
                    if (cameraSettings.flashValue == "flash_auto" || cameraSettings.flashValue == "flash_frontscreen_auto") {
                        // calling fireAutoFlash() also caches the decision on whether to flash - otherwise if the flash fires now, we'll then think the scene is bright enough to not need the flash!
                        if (fireAutoFlash()) wantFlash = true
                    } else if (cameraSettings.flashValue == "flash_on") {
                        wantFlash = true
                    }
                    if (wantFlash) {
                        if (MyDebug.LOG) Log.d(TAG, "turn on torch for fake flash")
                        if (!cameraSettings.hasIso) {
                            // in auto-mode, need to ensure CONTROL_AE_MODE isn't est to flash auto/on for torch to work
                            // in manual-mode, fine as CONTROL_AE_MODE will be off
                            afBuilder!!.set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CameraMetadata.CONTROL_AE_MODE_ON
                            )
                        }
                        afBuilder!!.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                        testFakeFlashFocus++
                        fakePrecaptureTorchFocusPerformed = true
                        setRepeatingRequest(afBuilder.build())
                        // We sleep for a short time as on some devices (e.g., OnePlus 3T), the torch will turn off when autofocus
                        // completes even if we don't want that (because we'll be taking a photo).
                        // Note that on other devices such as Nexus 6, this problem doesn't occur even if we don't have a separate
                        // setRepeatingRequest.
                        // Update for 1.37: now we do need this for Nexus 6 too, after switching to setting CONTROL_AE_MODE_ON_AUTO_FLASH
                        // or CONTROL_AE_MODE_ON_ALWAYS_FLASH even for fake flash (see note in CameraSettings.setAEMode()) - and we
                        // needed to increase to 200ms! Otherwise, photos come out too dark for flash on if doing touch to focus then
                        // quickly taking a photo. (It also work to previously switch to CONTROL_AE_MODE_ON/FLASH_MODE_OFF first,
                        // but then the same problem shows up on OnePlus 3T again!)
                        try {
                            Thread.sleep(200)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                    }
                }

                // Camera2Basic sets a trigger with capture
                // Google Camera sets to idle with a repeating request, then sets af trigger to start with a capture
                afBuilder!!.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE
                )
                setRepeatingRequest(afBuilder.build())
                afBuilder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START
                )
                capture(afBuilder.build())
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to autofocus")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
                state = STATE_NORMAL
                precaptureStateChangeTimeMs = -1
                pushAutofocusCb = autofocusCb
                autofocusCb = null
                this.autofocusTimeMs = -1
                this.captureFollowsAutofocusHint = false
            }
            afBuilder!!.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_IDLE
            ) // ensure set back to idle
        }

        // should call callbacks without a lock
        pushAutofocusCb?.onAutoFocus(false)
    }

    override fun setCaptureFollowAutofocusHint(captureFollowsAutofocusHint: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "setCaptureFollowAutofocusHint")
            Log.d(
                TAG,
                "capture_follows_autofocus_hint? $captureFollowsAutofocusHint"
            )
        }
        blockForExtensions()
        synchronized(backgroundCameraLock) {
            this.captureFollowsAutofocusHint = captureFollowsAutofocusHint
        }
    }

    override fun cancelAutoFocus() {
        if (MyDebug.LOG) Log.d(TAG, "cancelAutoFocus")
        synchronized(backgroundCameraLock) {
            if (camera == null || !hasCaptureSession()) {
                if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                return
            }
            if (isVideoHighSpeed) {
                if (MyDebug.LOG) Log.d(TAG, "video is high speed")
                return
            }

            if (sessionType == SessionType.SESSIONTYPE_EXTENSION) {
                if (MyDebug.LOG) Log.d(TAG, "session type extension")
                return
            }

            previewBuilder?.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
            )
            // Camera2Basic does a capture then sets a repeating request - do the same here just to be safe
            try {
                capture()
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to cancel autofocus [capture]")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            } catch (e: IllegalStateException) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "failed to cancel autofocus [captureSession already closed!]"
                )
                e.printStackTrace()
                // got this as a Google Play exception - this means the capture session is already closed
            }
            previewBuilder?.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_IDLE
            )
            this.autofocusCb = null
            this.autofocusTimeMs = -1
            this.captureFollowsAutofocusHint = false
            state = STATE_NORMAL
            precaptureStateChangeTimeMs = -1
            try {
                setRepeatingRequest()
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to set repeating request after cancelling autofocus")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
            }
        }
    }

    override fun setContinuousFocusMoveCallback(cb: ContinuousFocusMoveCallback?) {
        if (MyDebug.LOG) Log.d(TAG, "setContinuousFocusMoveCallback")
        if (cb != null) {
            blockForExtensions()
        }
        focusMeteringCoordinator.setContinuousFocusMoveCallback(cb)
    }

    /** Whether the stillRequest has a manual exposure time different to the preview, and if so,
     * whether we first need to set the preview exposure to match. (Needed for Samsung Galaxy devices,
     * which don't honor a manual exposure that's different to the current preview exposure.)
     */
    private fun adjustPreview(stillRequest: CaptureRequest): Boolean {
        var adjustPreview = false
        if ((isSamsung || testForceRunPostCapture) && !previewIsVideoMode) {
            // don't do this if in video snapshot mode
            val aeMode = stillRequest.get(CaptureRequest.CONTROL_AE_MODE)
            val exposureTime = stillRequest.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
            if (aeMode != null && aeMode == CameraMetadata.CONTROL_AE_MODE_OFF && exposureTime != null) {
                val actualAeMode = previewBuilder?.get(CaptureRequest.CONTROL_AE_MODE)
                if (actualAeMode != null && actualAeMode == CameraMetadata.CONTROL_AE_MODE_OFF) {
                    // both preview and still are manual, so see if exposure times are the same
                    val actualExposureTime =
                        previewBuilder?.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
                    if (actualExposureTime != null && exposureTime > actualExposureTime) {
                        adjustPreview = true
                    }
                } else {
                    // preview is auto but still is manual
                    adjustPreview = true
                }
            }
        }
        return adjustPreview
    }

    /** Adjusts the preview's manual exposure to match the stillRequest's manual exposure. Should only
     * be called if adjustPreview() returns true.
     * We use RUN_POST_CAPTURE, so we can be sure that the request to adjust the preview's exposure has
     * completed.
     */
    @Throws(CameraAccessException::class)
    private fun adjustPreviewToStill(
        stillRequest: CaptureRequest,
        postCapture: PostCapture
    ) {
        if (MyDebug.LOG) Log.d(TAG, "adjustPreviewToStill")
        previewBuilder?.set(
            CaptureRequest.CONTROL_AE_MODE,
            stillRequest.get(CaptureRequest.CONTROL_AE_MODE)
        )
        previewBuilder?.set(
            CaptureRequest.SENSOR_EXPOSURE_TIME,
            stillRequest.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
        )
        this.runPostCapture = postCapture
        previewBuilder?.setTag(
            RequestTagObject(
                RequestTagType.RUN_POST_CAPTURE
            )
        )
        previewBuilder?.build()?.let { pb ->
            captureSession?.capture(pb, previewCaptureCallback, handler)
        }
        previewBuilder?.setTag(null)
        setRepeatingRequest()
    }

    private fun takePictureAfterPrecapture() {
        if (MyDebug.LOG) Log.d(TAG, "takePictureAfterPrecapture")
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            debugTime = System.currentTimeMillis()
        }

        if (!previewIsVideoMode) {
            // special burst modes not supported for photo snapshots when recording video
            if (burstType === BurstType.BURSTTYPE_EXPO || burstType === BurstType.BURSTTYPE_FOCUS) {
                takePictureBurstBracketing()
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "takePictureAfterPrecapture() took: " + (System.currentTimeMillis() - debugTime)
                    )
                }
                return
            } else if (burstType === BurstType.BURSTTYPE_NORMAL || burstType === BurstType.BURSTTYPE_CONTINUOUS) {
                takePictureBurst(false)
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "takePictureAfterPrecapture() took: " + (System.currentTimeMillis() - debugTime)
                    )
                }
                return
            }
        }

        var stillBuilder: CaptureRequest.Builder? = null
        var ok = true
        var pushTakePictureErrorCb: ErrorCallback? = null

        synchronized(backgroundCameraLock) {
            if (camera == null || !hasCaptureSession()) {
                if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                return
            }
            try {
                if (MyDebug.LOG) {
                    if (imageReaderRaw != null) {
                        Log.d(
                            TAG,
                            "imageReaderRaw: $imageReaderRaw"
                        )
                        Log.d(TAG, "imageReaderRaw surface: " + imageReaderRaw!!.surface.toString())
                    } else {
                        Log.d(TAG, "imageReader: $imageReader")
                        Log.d(TAG, "imageReader surface: " + imageReader!!.surface.toString())
                    }
                }
                // important to use TEMPLATE_MANUAL for manual exposure: this fixes bug on Pixel 6 Pro where manual exposure is ignored when longer than the
                // preview exposure time (oddly Galaxy S10e has the same bug since Android 11, but that isn't fixed with using TEMPLATE_MANUAL)
                stillBuilder =
                    camera?.createCaptureRequest(if (previewIsVideoMode) CameraDevice.TEMPLATE_VIDEO_SNAPSHOT else if (cameraSettings.hasIso) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_STILL_CAPTURE)
                stillBuilder!!.setTag(
                    RequestTagObject(
                        RequestTagType.CAPTURE
                    )
                )
                cameraSettings.setupBuilder(stillBuilder, true)
                if (useFakePrecaptureMode && fakePrecaptureTorchPerformed) {
                    if (MyDebug.LOG) Log.d(TAG, "setting torch for capture")
                    if (!cameraSettings.hasIso) stillBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CameraMetadata.CONTROL_AE_MODE_ON
                    )
                    stillBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                    testFakeFlashPhoto++
                }
                // Versions previous to 1.51 would switch to manual mode and underexpose in bright scenes; however on more modern devices such as Samsung and
                // Pixels, this means that we lose the benefit of manufacturer algorithms creating a worse result. So we're better off staying in auto mode.
                // (Even on old versions, we didn't do this on OnePlus devices due to OnePlus 3T having preview corruption / camera freezing problems when
                // using manual shutter speeds.)
                //stillBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                //stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && sessionType != SessionType.SESSIONTYPE_EXTENSION) {
                    // unclear why we wouldn't want to request ZSL
                    // this is also required to enable HDR+ on Google Pixel devices when using Camera2: https://opensource.google.com/projects/pixelvisualcorecamera
                    // but don't set for extension sessions (in theory it should be ignored, but just in case)
                    stillBuilder.set(CaptureRequest.CONTROL_ENABLE_ZSL, true)
                    if (MyDebug.LOG) {
                        val zsl = stillBuilder.get(CaptureRequest.CONTROL_ENABLE_ZSL)
                        Log.d(TAG, "CONTROL_ENABLE_ZSL: " + (zsl ?: "null"))
                    }
                }
                clearPending()
                // shouldn't add preview surface as a target - no known benefit to doing so
                stillBuilder.addTarget(imageReader!!.surface)
                if (imageReaderRaw != null) stillBuilder.addTarget(imageReaderRaw!!.surface)

                nBurst = 1
                nBurstTaken = 0
                nBurstTotal = nBurst
                nBurstRaw = if (rawTodo) nBurst else 0
                burstSingleRequest = false
                if (!previewIsVideoMode) {
                    // need to stop preview before capture (as done in Camera2Basic; otherwise we get bugs such as flash remaining on after taking a photo with flash)
                    // but don't do this in video mode - if we're taking photo snapshots while video recording, we don't want to pause video!
                    // update: bug with flash may have been device specific (things are fine with Nokia 8)
                    if (sessionType != SessionType.SESSIONTYPE_EXTENSION) captureSession?.stopRepeating()
                }
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to take picture")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
                ok = false
                jpegTodo = false
                rawTodo = false
                pictureCb = null
                pushTakePictureErrorCb = takePictureErrorCb
                takePictureErrorCb = null
            } catch (e: IllegalStateException) {
                if (MyDebug.LOG) Log.d(TAG, "captureSession already closed!")
                e.printStackTrace()
                ok = false
                jpegTodo = false
                rawTodo = false
                pictureCb = null
                // don't report error, as camera is closed or closing
            }
        }

        // need to call callbacks without a lock
        if (ok && pictureCb != null) {
            if (MyDebug.LOG) Log.d(TAG, "call onStarted() in callback")
            pictureCb?.onStarted()
        }

        if (ok) {
            synchronized(backgroundCameraLock) {
                if (camera == null || !hasCaptureSession()) {
                    if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                    return
                }
                if (testReleaseDuringPhoto) {
                    val activity = context as Activity
                    activity.runOnUiThread {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "test UI thread call release()"
                        )
                        release()
                    }
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
                try {
                    if (MyDebug.LOG) Log.d(TAG, "capture with stillBuilder")

                    //pendingRequestWhenReady = stillBuilder.build();
                    if (sessionType == SessionType.SESSIONTYPE_EXTENSION) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            extensionSession!!.capture(
                                stillBuilder!!.build(),
                                executor!!, previewExtensionCaptureCallback!!
                            )
                        }
                    } else {
                        val capture = stillBuilder!!.build()
                        val adjustPreview = adjustPreview(capture)
                        if (adjustPreview) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "long manual exposure workaround: adjust preview first"
                            )

                            val postCapture: PostCapture =
                                object :
                                    PostCapture() {
                                    @Throws(CameraAccessException::class)
                                    override fun call() {
                                        captureSession?.capture(
                                            capture,
                                            previewCaptureCallback,
                                            handler
                                        )
                                    }
                                }

                            adjustPreviewToStill(capture, postCapture)
                        } else {
                            captureSession?.capture(capture, previewCaptureCallback, handler)
                        }
                        //captureSession.capture(stillBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                        //}, handler);
                    }
                    playSound(shutterClickSound) // play shutter sound asap, otherwise user has the illusion of being slow to take photos
                } catch (e: CameraAccessException) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "failed to take picture")
                        Log.e(TAG, "reason: " + e.reason)
                        Log.e(TAG, "message: " + e.message)
                    }
                    e.printStackTrace()
                    ok = false
                    jpegTodo = false
                    rawTodo = false
                    pictureCb = null
                    pushTakePictureErrorCb = takePictureErrorCb
                } catch (e: IllegalStateException) {
                    if (MyDebug.LOG) Log.d(TAG, "captureSession already closed!")
                    e.printStackTrace()
                    ok = false
                    jpegTodo = false
                    rawTodo = false
                    pictureCb = null
                    // don't report error, as camera is closed or closing
                }
            }
        }

        // need to call callbacks without a lock
        pushTakePictureErrorCb?.onError()
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "takePictureAfterPrecapture() took: " + (System.currentTimeMillis() - debugTime)
            )
        }
    }

    private fun takePictureBurstBracketing() {
        if (MyDebug.LOG) Log.d(TAG, "takePictureBurstBracketing")
        if (burstType !== BurstType.BURSTTYPE_EXPO && burstType !== BurstType.BURSTTYPE_FOCUS) {
            Log.e(
                TAG,
                "takePictureBurstBracketing called but unexpected burst_type: $burstType"
            )
        }
        blockForExtensions() // not supported for extension sessions

        val requests: MutableList<CaptureRequest> = ArrayList()
        var ok = true
        var pushTakePictureErrorCb: ErrorCallback? = null

        synchronized(backgroundCameraLock) {
            if (camera == null || !hasCaptureSession()) {
                if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                return
            }
            try {
                if (MyDebug.LOG) {
                    Log.d(TAG, "imageReader: $imageReader")
                    Log.d(TAG, "imageReader surface: " + imageReader!!.surface.toString())
                }
                var nDummyRequests = 0

                val stillBuilder =
                    camera!!.createCaptureRequest(if (burstType === BurstType.BURSTTYPE_EXPO || cameraSettings.hasIso) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_STILL_CAPTURE)
                // Needs to be TEMPLATE_MANUAL! Otherwise, first image in burst may come out incorrectly (on Pixel 6 Pro,
                // the first image incorrectly had HDR+ applied, which we don't want here). Also, problem on Pixel 6 Pro
                // where manual exposure is ignored when longer than the preview exposure.
                // Update: but only when doing burst for expo bracketing, not focus bracketing (unless actually doing that
                // in manual mode)! (Only manual exposure should use TEMPLATE_MANUAL, otherwise focus bracketing images
                // come out underexposed on Pixel 6 Pro).
                // n.b., don't set RequestTagType.CAPTURE here - we only do it for the last of the burst captures (see below)
                cameraSettings.setupBuilder(stillBuilder, true)

                if (MyDebug.LOG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val zsl = stillBuilder.get(CaptureRequest.CONTROL_ENABLE_ZSL)
                    Log.d(
                        TAG, "CONTROL_ENABLE_ZSL: " + (zsl
                            ?: "null")
                    )
                }

                clearPending()
                // shouldn't add preview surface as a target - see note in takePictureAfterPrecapture()
                // but also, adding the preview surface causes the dark/light exposures to be visible, which we don't want
                stillBuilder.addTarget(imageReader!!.surface)
                if (rawTodo) stillBuilder.addTarget(imageReaderRaw!!.surface)

                if (burstType === BurstType.BURSTTYPE_EXPO) {
                    if (MyDebug.LOG) Log.d(TAG, "expo bracketing")

                    /*stillBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                stillBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);

                stillBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, -6);
                requests.add( stillBuilder.build() );
                stillBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
                requests.add( stillBuilder.build() );
                stillBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 6);
                requests.add( stillBuilder.build() );*/
                    stillBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CameraMetadata.CONTROL_AE_MODE_OFF
                    )
                    if (useFakePrecaptureMode && fakePrecaptureTorchPerformed) {
                        if (MyDebug.LOG) Log.d(TAG, "setting torch for capture")
                        stillBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                        testFakeFlashPhoto++
                    }

                    // else don't turn torch off, as user may be in torch on mode
                    val isoRange =
                        characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) // may be null on some devices
                    if (isoRange == null) {
                        Log.e(TAG, "takePictureBurstBracketing called but null iso_range")
                    } else {
                        // set ISO
                        var iso = 800
                        // obtain current ISO/etc settings from the capture result - but if we're in manual ISO mode,
                        // might as well use the settings the user has actually requested (also useful for workaround for
                        // OnePlus 3T bug where the reported ISO and exposureTime are wrong in dark scenes)
                        if (cameraSettings.hasIso) iso = cameraSettings.iso
                        else if (captureResultHasIso) iso = captureResultIso
                        // see https://sourceforge.net/p/OpenKamera/tickets/321/ - some devices may have auto ISO that's
                        // outside the allowed manual iso range!
                        iso = max(iso.toDouble(), isoRange.lower.toDouble()).toInt()
                        iso = min(iso.toDouble(), isoRange.upper.toDouble()).toInt()
                        stillBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    }
                    if (captureResultHasFrameDuration) stillBuilder.set(
                        CaptureRequest.SENSOR_FRAME_DURATION,
                        captureResultFrameDuration
                    )
                    else stillBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1000000000L / 30)

                    var baseExposureTime = 1000000000L / 30
                    if (cameraSettings.hasIso) baseExposureTime = cameraSettings.exposureTime
                    else if (captureResultHasExposureTime) baseExposureTime =
                        captureResultExposureTime

                    val nHalfImages = expoBracketingNImages / 2
                    val scale = 2.0.pow(expoBracketingStops / nHalfImages.toDouble())

                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "taking expo bracketing with n_images: $expoBracketingNImages"
                        )
                        Log.d(TAG, "ISO: " + stillBuilder.get(CaptureRequest.SENSOR_SENSITIVITY))
                        Log.d(
                            TAG,
                            "Frame duration: " + stillBuilder.get(CaptureRequest.SENSOR_FRAME_DURATION)
                        )
                        Log.d(
                            TAG,
                            "Base exposure time: $baseExposureTime"
                        )
                        Log.d(
                            TAG,
                            "Min exposure time: $minExposureTime"
                        )
                        Log.d(
                            TAG,
                            "Max exposure time: $maxExposureTime"
                        )
                    }

                    if (dummyCaptureHack && useExpoFastBurst) {
                        if (MyDebug.LOG) Log.d(TAG, "add dummy capture")
                        // dummyCaptureHack only supported for useExpoFastBurst==true -
                        // supporting for useExpoFastBurst==false would complicate the code, and
                        // these are only special case hacks anyway
                        stillBuilder.setTag(null)
                        requests.add(stillBuilder.build())
                        nDummyRequests++
                        if (onImageAvailableListener != null) onImageAvailableListener!!.skipNextImage =
                            true
                        if (onRawImageAvailableListener != null) onRawImageAvailableListener!!.skipNextImage =
                            true
                    }

                    // darker images
                    for (i in 0..<nHalfImages) {
                        var exposureTime = baseExposureTime
                        if (supportsExposureTime) {
                            var thisScale = scale
                            repeat((nHalfImages - 1) - i) {
                                thisScale *= scale
                            }
                            exposureTime = (exposureTime / thisScale).toLong()
                            if (exposureTime < minExposureTime) exposureTime = minExposureTime
                            if (MyDebug.LOG) {
                                Log.d(TAG, "add burst request for " + i + "th dark image:")
                                Log.d(
                                    TAG,
                                    "    this_scale: $thisScale"
                                )
                                Log.d(
                                    TAG,
                                    "    exposure_time: $exposureTime"
                                )
                            }
                            stillBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
                            stillBuilder.setTag(
                                RequestTagObject(
                                    RequestTagType.CAPTURE_BURST_IN_PROGRESS
                                )
                            )
                            requests.add(stillBuilder.build())
                        }
                    }

                    // base image
                    if (MyDebug.LOG) Log.d(TAG, "add burst request for base image")
                    stillBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, baseExposureTime)
                    stillBuilder.setTag(
                        RequestTagObject(
                            RequestTagType.CAPTURE_BURST_IN_PROGRESS
                        )
                    )
                    requests.add(stillBuilder.build())

                    // lighter images
                    for (i in 0..<nHalfImages) {
                        var exposureTime = baseExposureTime
                        if (supportsExposureTime) {
                            var thisScale = scale
                            for (j in 0..<i) thisScale *= scale
                            exposureTime = (exposureTime * thisScale).toLong()
                            if (exposureTime > maxExposureTime) exposureTime = maxExposureTime
                            if (MyDebug.LOG) {
                                Log.d(TAG, "add burst request for " + i + "th light image:")
                                Log.d(
                                    TAG,
                                    "    this_scale: $thisScale"
                                )
                                Log.d(
                                    TAG,
                                    "    exposure_time: $exposureTime"
                                )
                            }
                            stillBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime)
                            if (i == nHalfImages - 1) {
                                // RequestTagType.CAPTURE should only be set for the last request, otherwise we'll may do things like turning
                                // off torch (for fake flash) before all images are received
                                // More generally, doesn't seem a good idea to be doing the post-capture commands (resetting ae state etc.)
                                // multiple times, and before all captures are complete!
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "set RequestTagType.CAPTURE for last burst request"
                                )
                                stillBuilder.setTag(
                                    RequestTagObject(
                                        RequestTagType.CAPTURE
                                    )
                                )
                            } else {
                                stillBuilder.setTag(
                                    RequestTagObject(
                                        RequestTagType.CAPTURE_BURST_IN_PROGRESS
                                    )
                                )
                            }
                            requests.add(stillBuilder.build())
                        }
                    }

                    burstSingleRequest = true
                } else {
                    // BURSTTYPE_FOCUS
                    if (MyDebug.LOG) Log.d(TAG, "focus bracketing")

                    if (useFakePrecaptureMode && fakePrecaptureTorchPerformed) {
                        if (MyDebug.LOG) Log.d(TAG, "setting torch for capture")
                        if (!cameraSettings.hasIso) stillBuilder.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CameraMetadata.CONTROL_AE_MODE_ON
                        )
                        stillBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                        testFakeFlashPhoto++
                    }

                    stillBuilder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CameraMetadata.CONTROL_AF_MODE_OFF
                    ) // just in case

                    if (abs((cameraSettings.focusDistance - focusBracketingSourceDistance).toDouble()) < 1.0e-5) {
                        if (MyDebug.LOG) Log.d(TAG, "current focus matches source")
                    } else if (abs((cameraSettings.focusDistance - focusBracketingTargetDistance).toDouble()) < 1.0e-5) {
                        if (MyDebug.LOG) Log.d(TAG, "current focus matches target")
                    } else {
                        Log.d(TAG, "current focus matches neither source nor target")
                    }

                    val focusDistances = setupFocusBracketingDistances(
                        focusBracketingSourceDistance,
                        focusBracketingTargetDistance,
                        focusBracketingNImages
                    )
                    if (focusBracketingAddInfinity) {
                        focusDistances.add(0.0f)
                    }
                    for (i in focusDistances.indices) {
                        stillBuilder.set(
                            CaptureRequest.LENS_FOCUS_DISTANCE,
                            focusDistances[i]
                        )
                        //stillBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus_distances.get(focus_distances.size()-1));
                        if (i == focusDistances.size - 1) {
                            stillBuilder.setTag(
                                RequestTagObject(
                                    RequestTagType.CAPTURE
                                )
                            ) // set capture tag for last only
                        } else {
                            // note, even if we didn't need to set CAPTURE_BURST_IN_PROGRESS, we'd still want
                            // to set a RequestTagObject (e.g., type NONE) so that it can be changed later,
                            // so that cancelling focus bracketing works
                            //stillBuilder.setTag(new RequestTagObject(RequestTagType.NONE));
                            stillBuilder.setTag(
                                RequestTagObject(
                                    RequestTagType.CAPTURE_BURST_IN_PROGRESS
                                )
                            )
                        }
                        requests.add(stillBuilder.build())

                        focusBracketingInProgress = true
                    }

                    burstSingleRequest =
                        false // we set to false for focus bracketing, as we support bracketing with large numbers of images in this mode
                    //burstSingleRequest = true; // test
                }

                /*
                // testing:
                stillBuilder.setTag(new RequestTagObject(RequestTagType.CAPTURE_BURST_IN_PROGRESS));
                requests.add( stillBuilder.build() );
                stillBuilder.setTag(new RequestTagObject(RequestTagType.CAPTURE_BURST_IN_PROGRESS));
                requests.add( stillBuilder.build() );
                stillBuilder.setTag(new RequestTagObject(RequestTagType.CAPTURE_BURST_IN_PROGRESS));
                requests.add( stillBuilder.build() );
                stillBuilder.setTag(new RequestTagObject(RequestTagType.CAPTURE_BURST_IN_PROGRESS));
                requests.add( stillBuilder.build() );
                if( MyDebug.LOG )
                    Log.d(TAG, "set RequestTagType.CAPTURE for last burst request");
                stillBuilder.setTag(new RequestTagObject(RequestTagType.CAPTURE));
                requests.add( stillBuilder.build() );
                */
                nBurst = requests.size - nDummyRequests
                nBurstTotal = nBurst
                nBurstTaken = 0
                nBurstRaw = if (rawTodo) nBurst else 0
                if (MyDebug.LOG) {
                    Log.d(TAG, "n_burst: $nBurst")
                    Log.d(
                        TAG,
                        "burst_single_request: $burstSingleRequest"
                    )
                }

                if (!previewIsVideoMode) {
                    captureSession?.stopRepeating() // see note under takePictureAfterPrecapture()
                }
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to take picture expo burst")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
                ok = false
                jpegTodo = false
                rawTodo = false
                pictureCb = null
                pushTakePictureErrorCb = takePictureErrorCb
            } catch (e: IllegalStateException) {
                if (MyDebug.LOG) Log.d(TAG, "captureSession already closed!")
                e.printStackTrace()
                ok = false
                jpegTodo = false
                rawTodo = false
                pictureCb = null
                // don't report error, as camera is closed or closing
            }
        }

        // need to call callbacks without a lock
        if (ok && pictureCb != null) {
            if (MyDebug.LOG) Log.d(TAG, "call onStarted() in callback")
            pictureCb?.onStarted()
        }

        if (ok) {
            synchronized(backgroundCameraLock) {
                if (camera == null || !hasCaptureSession()) {
                    if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                    return
                }
                try {
                    modifiedFromCameraSettings = true
                    //setRepeatingRequest(requests.get(0));
                    if (useExpoFastBurst && burstType === BurstType.BURSTTYPE_EXPO) { // always use slow burst for focus bracketing
                        if (MyDebug.LOG) Log.d(TAG, "using fast burst")
                        val sequenceId =
                            captureSession?.captureBurst(requests, previewCaptureCallback, handler)
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "sequenceId: $sequenceId"
                        )
                    } else {
                        if (MyDebug.LOG) Log.d(TAG, "using slow burst")
                        slowBurstCaptureRequests = requests
                        slowBurstStartMs = System.currentTimeMillis()
                        if (burstType === BurstType.BURSTTYPE_EXPO) {
                            // Set preview to match - some devices (e.g. Samsung Galaxy) don't produce photos with correct exposure if
                            // the exposure is set to a different value than the preview.
                            // Although we don't/can't do this for fast burst, doing so here means such devices can use HDR/expo when
                            // using useExpoFastBurst==false.
                            try {
                                previewBuilder?.set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CameraMetadata.CONTROL_AE_MODE_OFF
                                )
                                previewBuilder?.set(
                                    CaptureRequest.SENSOR_SENSITIVITY,
                                    slowBurstCaptureRequests[nBurstTaken].get(CaptureRequest.SENSOR_SENSITIVITY)
                                )
                                previewBuilder?.set(
                                    CaptureRequest.SENSOR_FRAME_DURATION,
                                    slowBurstCaptureRequests[nBurstTaken].get(CaptureRequest.SENSOR_FRAME_DURATION)
                                )

                                val exposureTime =
                                    slowBurstCaptureRequests[nBurstTaken]
                                        .get(CaptureRequest.SENSOR_EXPOSURE_TIME)!!
                                if (MyDebug.LOG) {
                                    Log.d(
                                        TAG,
                                        "prepare preview for next exposure: $exposureTime"
                                    )
                                }
                                previewBuilder?.set(
                                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                                    exposureTime
                                )

                                setRepeatingRequest(previewBuilder?.build())
                            } catch (e: CameraAccessException) {
                                if (MyDebug.LOG) {
                                    Log.e(
                                        TAG,
                                        "failed to take set exposure for next expo bracketing burst"
                                    )
                                    Log.e(TAG, "reason: " + e.reason)
                                    Log.e(TAG, "message: " + e.message)
                                }
                                e.printStackTrace()
                                jpegTodo = false
                                rawTodo = false
                                pictureCb = null
                                pushTakePictureErrorCb = takePictureErrorCb
                            }

                            postNextSlowBurst()
                        } else {
                            // no need to set preview for first focus bracketing shot, the first focus bracketing always
                            // has same focus distance as preview
                            captureSession?.capture(requests[0], previewCaptureCallback, handler)
                        }
                    }

                    playSound(shutterClickSound) // play shutter sound asap, otherwise user has the illusion of being slow to take photos
                } catch (e: CameraAccessException) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "failed to take picture expo burst")
                        Log.e(TAG, "reason: " + e.reason)
                        Log.e(TAG, "message: " + e.message)
                    }
                    e.printStackTrace()
                    ok = false
                    jpegTodo = false
                    rawTodo = false
                    pictureCb = null
                    pushTakePictureErrorCb = takePictureErrorCb
                } catch (e: IllegalStateException) {
                    if (MyDebug.LOG) Log.d(TAG, "captureSession already closed!")
                    e.printStackTrace()
                    ok = false
                    jpegTodo = false
                    rawTodo = false
                    pictureCb = null
                    // don't report error, as camera is closed or closing
                }
            }
        }

        // need to call callbacks without a lock
        pushTakePictureErrorCb?.onError()
    }

    @Throws(CameraAccessException::class)
    private fun doTakePhotoBurst(request: CaptureRequest?, lastRequest: CaptureRequest?) {
        if (burstType === BurstType.BURSTTYPE_CONTINUOUS) {
            if (MyDebug.LOG) {
                Log.d(TAG, "continuous capture")
                if (!isContinuousBurstInProgress) Log.d(TAG, "    last continuous capture")
            }
            continuousBurstRequestedLastCapture = !isContinuousBurstInProgress
            captureSession?.capture(
                (if (isContinuousBurstInProgress) request else lastRequest)!!,
                previewCaptureCallback,
                handler
            )

            if (isContinuousBurstInProgress) {
                val continuousBurstRateMs = 100
                // also take the next burst after a delay
                handler?.postDelayed(object : Runnable {
                    override fun run() {
                        // note, even if continuousBurstInProgress has become false by this point, still take one last
                        // photo, as need to ensure that we have a request with RequestTagType.CAPTURE, as well as ensuring
                        // we call the onCompleted() method of the callback
                        if (MyDebug.LOG) {
                            Log.d(TAG, "take next continuous burst")
                            Log.d(
                                TAG,
                                "continuous_burst_in_progress: " + this@CameraController2.isContinuousBurstInProgress
                            )
                            Log.d(TAG, "n_burst: $nBurst")
                        }
                        if (nBurst >= 10 || nBurstRaw >= 10) {
                            // Nokia 8 in std mode without post-processing options doesn't hit this limit (we only hit this
                            // if it's set to "nBurst >= 5")
                            if (MyDebug.LOG) {
                                Log.d(
                                    TAG,
                                    "...but wait for continuous burst, as waiting for too many photos"
                                )
                            }
                            //throw new RuntimeException(); // test
                            handler?.postDelayed(this, continuousBurstRateMs.toLong())
                        } else if (pictureCb?.imageQueueWouldBlock(nBurstRaw, nBurst + 1) == true) {
                            if (MyDebug.LOG) {
                                Log.d(
                                    TAG,
                                    "...but wait for continuous burst, as image queue would block"
                                )
                            }
                            //throw new RuntimeException(); // test
                            handler?.postDelayed(this, continuousBurstRateMs.toLong())
                        } else {
                            takePictureBurst(true)
                        }
                    }
                }, continuousBurstRateMs.toLong())
            }
        } else {
            val requests: MutableList<CaptureRequest?> = ArrayList()
            for (i in 0..<nBurst - 1) requests.add(request)
            requests.add(lastRequest)
            if (MyDebug.LOG) Log.d(TAG, "captureBurst")
            val sequenceId =
                captureSession?.captureBurst(requests, previewCaptureCallback, handler)
            if (MyDebug.LOG) Log.d(
                TAG,
                "sequenceId: $sequenceId"
            )
        }
    }

    private fun takePictureBurst(continuingFastBurst: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "takePictureBurst")
        if (burstType !== BurstType.BURSTTYPE_NORMAL && burstType !== BurstType.BURSTTYPE_CONTINUOUS) {
            Log.e(
                TAG,
                "takePictureBurstBracketing called but unexpected burst_type: $burstType"
            )
        }
        blockForExtensions() // not supported for extension sessions

        var isNewBurst = true
        var request: CaptureRequest? = null
        var lastRequest: CaptureRequest? = null
        var ok = true
        var pushTakePictureErrorCb: ErrorCallback? = null

        synchronized(backgroundCameraLock) {
            if (camera == null || !hasCaptureSession()) {
                if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                return
            }
            try {
                if (MyDebug.LOG) {
                    Log.d(TAG, "imageReader: $imageReader")
                    Log.d(TAG, "imageReader surface: " + imageReader!!.surface.toString())
                }

                val stillBuilder =
                    camera!!.createCaptureRequest(if (previewIsVideoMode) CameraDevice.TEMPLATE_VIDEO_SNAPSHOT else if (cameraSettings.hasIso) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_STILL_CAPTURE)
                // N.B., takePictureBurst() not currently called if previewIsVideoMode==true, but have put this code here for possible future use.
                // Important to use TEMPLATE_MANUAL for manual exposure: this fixes bug on Pixel 6 Pro where manual exposure is ignored when longer than the
                // preview exposure time (e.g. for fast burst).
                // n.b., don't set RequestTagType.CAPTURE here - we only do it for the last of the burst captures (see below)
                cameraSettings.setupBuilder(stillBuilder, true)
                if (useFakePrecaptureMode && fakePrecaptureTorchPerformed) {
                    if (MyDebug.LOG) Log.d(TAG, "setting torch for capture")
                    if (!cameraSettings.hasIso) stillBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CameraMetadata.CONTROL_AE_MODE_ON
                    )
                    stillBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                    testFakeFlashPhoto++
                }

                if (!isSamsung && burstType === BurstType.BURSTTYPE_NORMAL && burstForNoiseReduction) {
                    // Must be done after calling setupBuilder(), so we override the default EDGE_MODE and NOISE_REDUCTION_MODE.
                    // We disable noise-reduction etc. for photo mode NR because on many devices this smears out detail that we actually
                    // aim to recover by averaging a stack of multiple images.
                    // Disabled for Samsung - firstly at least on Galaxy S24+ this has no effect except for unstable situations (e.g.,
                    // if UltraHDR/JPEG_R is enabled then switching from STD to NR mode means this works for some reason, even though we
                    // don't enable JPEG_R for NR mode...). We could fix it by also changing for the preview, although this makes the
                    // code more complicated (we'd need to save the old values, and also avoid interactions with setNoiseReductionMode() and
                    // setEdgeMode()). But Galaxy S24+ at least seems to have better noise reduction such that detail is less likely to be
                    // smeared out, and overall quality of photos in NR mode seems better if run noise reduction etc. as normal.
                    if (MyDebug.LOG) Log.d(TAG, "optimise settings for burst_for_noise_reduction")
                    stillBuilder.set(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    )
                    stillBuilder.set(
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                        CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
                    )
                    stillBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                }

                if (!continuingFastBurst) {
                    clearPending()
                }
                // shouldn't add preview surface as a target - see note in takePictureAfterPrecapture()
                stillBuilder.addTarget(imageReader!!.surface)

                // RAW target added below
                if (useFakePrecaptureMode && fakePrecaptureTorchPerformed) {
                    stillBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                    testFakeFlashPhoto++
                }

                // else don't turn torch off, as user may be in torch on mode
                if (burstType === BurstType.BURSTTYPE_CONTINUOUS) {
                    if (MyDebug.LOG) Log.d(TAG, "continuous burst mode")
                    rawTodo =
                        false // RAW works in continuous burst mode, but makes things very slow...
                    if (continuingFastBurst) {
                        if (MyDebug.LOG) Log.d(TAG, "continuing fast burst")
                        nBurst++
                        isNewBurst = false
                        /*if( !continuousBurstInProgress ) // test bug where we call callback onCompleted() before all burst images are received
                            nBurst = 1;*/
                    } else {
                        if (MyDebug.LOG) Log.d(TAG, "start continuous burst")
                        isContinuousBurstInProgress = true
                        nBurst = 1
                        nBurstTaken = 0
                    }
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "n_burst is now $nBurst"
                    )
                } else if (burstForNoiseReduction) {
                    if (MyDebug.LOG) Log.d(TAG, "choose n_burst for burst_for_noise_reduction")
                    nBurst = 4
                    nBurstTaken = 0

                    if (captureResultHasIso && captureResultHasExposureTime) {
                        if (HDRProcessor.sceneIsLowLight(
                                captureResultIso,
                                captureResultExposureTime
                            )
                        ) {
                            if (MyDebug.LOG) Log.d(TAG, "optimise for dark scene")
                            nBurst =
                                if (noiseReductionLowLight) N_IMAGES_NR_DARK_LOW_LIGHT else N_IMAGES_NR_DARK
                            // Versions previous to 1.51 would switch to manual mode and overexpose in bright scenes; however on more modern devices such as Samsung and
                            // Pixels, this means that we lose the benefit of manufacturer algorithms creating a worse result. So we're better off staying in auto mode.
                            // (Even on old versions, we didn't do this for OnePlus devices, due to bug on OnePlus 3T where manual mode can't be set above 800, so this
                            // would cause images to come out too dark.)
                        } else if (captureResultHasExposureTime) {
                            val fixedExposureTime = 1000000000L / 60
                            val exposureTime = captureResultExposureTime
                            if (exposureTime <= fixedExposureTime) {
                                if (MyDebug.LOG) Log.d(TAG, "optimise for bright scene")
                                //nBurst = 2;
                                nBurst = 3
                                // Versions previous to 1.51 would switch to manual mode and underexpose in bright scenes; however on more modern devices such as
                                // Samsung and Pixels, this means that we lose the benefit of manufacturer algorithms creating a worse result. So we're better off
                                // staying in auto mode.
                            }
                        }
                    }
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "user requested n_burst")
                    nBurst = burstRequestedNImages
                    nBurstTaken = 0
                }
                if (rawTodo) stillBuilder.addTarget(imageReaderRaw!!.surface)
                nBurstTotal = nBurst
                nBurstRaw = if (rawTodo) nBurst else 0
                burstSingleRequest = false

                if (MyDebug.LOG) Log.d(
                    TAG,
                    "n_burst: $nBurst"
                )

                stillBuilder.setTag(
                    RequestTagObject(
                        RequestTagType.CAPTURE_BURST_IN_PROGRESS
                    )
                )
                request = stillBuilder.build()
                stillBuilder.setTag(
                    RequestTagObject(
                        RequestTagType.CAPTURE
                    )
                )
                lastRequest = stillBuilder.build()

                // n.b., don't stop the preview with stop.Repeating when capturing a burst
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to take picture burst")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
                ok = false
                jpegTodo = false
                rawTodo = false
                pictureCb = null
                pushTakePictureErrorCb = takePictureErrorCb
            }
        }

        // need to call callbacks without a lock
        if (ok && pictureCb != null && isNewBurst) {
            if (MyDebug.LOG) Log.d(TAG, "call onStarted() in callback")
            pictureCb?.onStarted()
        }

        if (ok) {
            synchronized(backgroundCameraLock) {
                if (camera == null || !hasCaptureSession()) {
                    if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                    return
                }
                try {
                    // if continuingFastBurst==true, there is no need to adjust the preview again
                    val adjustPreview = !continuingFastBurst && adjustPreview(request!!)
                    if (adjustPreview) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "long manual exposure workaround: adjust preview first"
                        )

                        val requestF = request
                        val lastRequestF = lastRequest!!
                        val postCapture: PostCapture =
                            object :
                                PostCapture() {
                                @Throws(CameraAccessException::class)
                                override fun call() {
                                    doTakePhotoBurst(requestF, lastRequestF)
                                }
                            }

                        adjustPreviewToStill(request, postCapture)
                    } else {
                        doTakePhotoBurst(request, lastRequest)
                    }

                    if (!continuingFastBurst) {
                        playSound(shutterClickSound) // play shutter sound asap, otherwise user has the illusion of being slow to take photos
                    }
                } catch (e: CameraAccessException) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "failed to take picture burst")
                        Log.e(TAG, "reason: " + e.reason)
                        Log.e(TAG, "message: " + e.message)
                    }
                    e.printStackTrace()
                    ok = false
                    jpegTodo = false
                    rawTodo = false
                    pictureCb = null
                    pushTakePictureErrorCb = takePictureErrorCb
                }
            }
        }

        // need to call callbacks without a lock
        pushTakePictureErrorCb?.onError()
    }

    private fun runPrecapture() {
        if (MyDebug.LOG) Log.d(TAG, "runPrecapture")

        blockForExtensions() // not supported for extension sessions

        var debugTime: Long = 0
        if (MyDebug.LOG) {
            debugTime = System.currentTimeMillis()
        }

        // first run precapture sequence
        var pushTakePictureErrorCb: ErrorCallback? = null

        synchronized(backgroundCameraLock) {
            if (MyDebug.LOG) {
                if (useFakePrecaptureMode) Log.e(
                    TAG,
                    "shouldn't be doing standard precapture when use_fake_precapture_mode is true!"
                )
                else if (burstType !== BurstType.BURSTTYPE_NONE) Log.e(
                    TAG,
                    "shouldn't be doing precapture for burst - should be using fake precapture!"
                )
            }
            try {
                // Use a separate builder for precapture - otherwise have problem that if we take photo with flash auto/on of dark scene, then point to a bright scene, the autoexposure isn't running until we autofocus again.
                // Important that this is TEMPLATE_PREVIEW not TEMPLATE_STILL_CAPTURE, otherwise we have various problems with flash:
                // * Flash won't fire on Galaxy devices.
                // * End up with blue tinge on OnePlus 3T.
                // * Flash auto produces blue tinge and leaves torch on for Pixel 6 Pro.
                val precaptureBuilder = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

                cameraSettings.setupBuilder(precaptureBuilder, false)
                precaptureBuilder.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE
                )
                precaptureBuilder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )

                precaptureBuilder.addTarget(_previewSurface)

                state = STATE_WAITING_PRECAPTURE_START
                precaptureStateChangeTimeMs = System.currentTimeMillis()

                // first set precapture to idle - this is needed, otherwise we hang in state STATE_WAITING_PRECAPTURE_START, because precapture already occurred whilst autofocusing, and it doesn't occur again unless we first set the precapture trigger to idle
                if (MyDebug.LOG) Log.d(TAG, "capture with precaptureBuilder")
                captureSession?.capture(precaptureBuilder.build(), previewCaptureCallback, handler)
                captureSession?.setRepeatingRequest(
                    precaptureBuilder.build(),
                    previewCaptureCallback,
                    handler
                )

                // now set precapture
                precaptureBuilder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
                )
                captureSession?.capture(precaptureBuilder.build(), previewCaptureCallback, handler)
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to precapture")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
                jpegTodo = false
                rawTodo = false
                pictureCb = null
                pushTakePictureErrorCb = takePictureErrorCb
            }
        }

        // need to call callbacks without a lock
        pushTakePictureErrorCb?.onError()
        if (MyDebug.LOG) {
            Log.d(TAG, "runPrecapture() took: " + (System.currentTimeMillis() - debugTime))
        }
    }

    private fun runFakePrecapture() {
        if (MyDebug.LOG) Log.d(TAG, "runFakePrecapture")

        blockForExtensions() // not supported for extension sessions

        var debugTime: Long = 0
        if (MyDebug.LOG) {
            debugTime = System.currentTimeMillis()
        }

        var turnFrontscreenOn = false
        var pushTakePictureErrorCb: ErrorCallback? = null

        synchronized(backgroundCameraLock) {
            when (cameraSettings.flashValue) {
                "flash_auto", "flash_on" -> {
                    if (MyDebug.LOG) Log.d(TAG, "turn on torch")
                    if (!cameraSettings.hasIso) {
                        // in auto-mode, need to ensure CONTROL_AE_MODE isn't est to flash auto/on for torch to work
                        // in manual-mode, fine as CONTROL_AE_MODE will be off
                        previewBuilder?.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CameraMetadata.CONTROL_AE_MODE_ON
                        )
                    }
                    previewBuilder?.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
                    testFakeFlashPrecapture++
                    fakePrecaptureTorchPerformed = true
                }

                "flash_frontscreen_auto", "flash_frontscreen_on" -> turnFrontscreenOn = true
                else -> if (MyDebug.LOG) Log.e(
                    TAG,
                    "runFakePrecapture called with unexpected flash value: " + cameraSettings.flashValue
                )
            }
        }

        // need to call callbacks without a lock
        if (turnFrontscreenOn) {
            if (pictureCb != null) {
                if (MyDebug.LOG) Log.d(TAG, "request screen turn on for frontscreen flash")
                pictureCb?.onFrontScreenTurnOn()
            } else {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "can't request screen turn on for frontscreen flash, as no picture_cb"
                )
            }
        }

        synchronized(backgroundCameraLock) {
            state = STATE_WAITING_FAKE_PRECAPTURE_START
            precaptureStateChangeTimeMs = System.currentTimeMillis()
            fakePrecaptureTurnOnTorchId = null
            try {
                val request = previewBuilder?.build()
                if (fakePrecaptureTorchPerformed) {
                    fakePrecaptureTurnOnTorchId = request
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "fake_precapture_turn_on_torch_id: $request"
                    )
                }
                request?.let { setRepeatingRequest(it) }
            } catch (e: CameraAccessException) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "failed to start fake precapture")
                    Log.e(TAG, "reason: " + e.reason)
                    Log.e(TAG, "message: " + e.message)
                }
                e.printStackTrace()
                jpegTodo = false
                rawTodo = false
                pictureCb = null
                pushTakePictureErrorCb = takePictureErrorCb
            }
        }

        // need to call callbacks without a lock
        pushTakePictureErrorCb?.onError()
        if (MyDebug.LOG) {
            Log.d(TAG, "runFakePrecapture() took: " + (System.currentTimeMillis() - debugTime))
        }
    }

    private fun fireAutoFlashFrontScreen(): Boolean {
        // isoThreshold fine-tuned for Nexus 6 - front camera ISO never goes above 805, but a threshold of 700 is too low
        val isoThreshold = 750
        return captureResultHasIso && captureResultIso >= isoThreshold
    }

    /** Used in useFakePrecapture mode when flash is auto, this returns whether we fire the flash.
     * If the decision was recently calculated, we return that same decision - used to fix problem that if
     * we fire flash during autofocus (for autofocus mode), we don't then want to decide the scene is too
     * bright to not need flash for taking photo!
     */
    private fun fireAutoFlash(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "fireAutoFlash")
        val timeNow = System.currentTimeMillis()
        if (MyDebug.LOG && fakePrecaptureUseFlashTimeMs != -1L) {
            Log.d(
                TAG,
                "fake_precapture_use_flash_time_ms: $fakePrecaptureUseFlashTimeMs"
            )
            Log.d(TAG, "time_now: $timeNow")
            Log.d(
                TAG,
                "time since last flash auto decision: " + (timeNow - fakePrecaptureUseFlashTimeMs)
            )
        }
        val cacheTimeMs: Long =
            3000 // needs to be at least the time of a typical autoflash, see comment for this function above
        if (fakePrecaptureUseFlashTimeMs != -1L && timeNow - fakePrecaptureUseFlashTimeMs < cacheTimeMs) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "use recent decision: $fakePrecaptureUseFlash"
            )
            fakePrecaptureUseFlashTimeMs = timeNow
            return fakePrecaptureUseFlash
        }
        when (cameraSettings.flashValue) {
            "flash_auto" -> fakePrecaptureUseFlash = isFlashRequired
            "flash_frontscreen_auto" -> {
                fakePrecaptureUseFlash = fireAutoFlashFrontScreen()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "    ISO was: $captureResultIso"
                )
            }

            else ->                 // shouldn't really be calling this function if not flash auto...
                fakePrecaptureUseFlash = false
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "fake_precapture_use_flash: $fakePrecaptureUseFlash"
        )
        // We only cache the result if we decide to turn on torch, as that mucks up our ability to tell if we need the flash (since once the torch
        // is on, the aeState thinks it's bright enough to not need flash!)
        // But if we don't turn on torch, this problem doesn't occur, so no need to cache - and good that the next time we should make an up-to-date
        // decision.
        fakePrecaptureUseFlashTimeMs = if (fakePrecaptureUseFlash) {
            timeNow
        } else {
            -1
        }
        return fakePrecaptureUseFlash
    }

    override fun takePicture(picture: PictureCallback, error: ErrorCallback) {
        if (MyDebug.LOG) Log.d(TAG, "takePicture")
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            debugTime = System.currentTimeMillis()
        }

        var callTakePictureAfterPrecapture = false
        var callRunFakePrecapture = false
        var callRunPrecapture = false

        synchronized(backgroundCameraLock) {
            if (camera == null || !hasCaptureSession()) {
                if (MyDebug.LOG) Log.d(TAG, "no camera or capture session")
                error.onError()
                return
            }
            this.pictureCb = picture
            this.jpegTodo = true
            this.rawTodo = imageReaderRaw != null
            this.doneAllCaptures = false
            this.takePictureErrorCb = error
            this.fakePrecaptureTorchPerformed = false // just in case still on?
            if (sessionType == SessionType.SESSIONTYPE_NORMAL && !readyForCapture) {
                if (MyDebug.LOG) Log.e(TAG, "takePicture: not ready for capture!")
                //throw new RuntimeException(); // debugging
            }
            run {
                if (MyDebug.LOG) {
                    Log.d(TAG, "current flash value: " + cameraSettings.flashValue)
                    Log.d(
                        TAG,
                        "use_fake_precapture_mode: $useFakePrecaptureMode"
                    )
                }
                if (sessionType == SessionType.SESSIONTYPE_EXTENSION) {
                    // precapture not supported for extensions
                    callTakePictureAfterPrecapture = true
                } else if (cameraSettings.flashValue == "flash_off" || cameraSettings.flashValue == "flash_torch" || cameraSettings.flashValue == "flash_frontscreen_torch") {
                    // Don't need precapture if flash off or torch
                    callTakePictureAfterPrecapture = true
                } else if (useFakePrecaptureMode) {
                    // fake flash auto/on mode
                    // fake precapture works by turning on torch (or using a "front screen flash"), so we can't use the camera's own decision for flash auto
                    // instead we check the current ISO value
                    val autoFlash =
                        cameraSettings.flashValue == "flash_auto" || cameraSettings.flashValue == "flash_frontscreen_auto"
                    val flashMode = previewBuilder?.get(CaptureRequest.FLASH_MODE)
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "flash_mode: $flashMode"
                    )
                    if (autoFlash && !fireAutoFlash()) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "fake precapture flash auto: seems bright enough to not need flash"
                        )
                        callTakePictureAfterPrecapture = true
                    } else if (flashMode != null && flashMode == CameraMetadata.FLASH_MODE_TORCH) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "fake precapture flash: torch already on (presumably from autofocus)"
                        )
                        // On some devices (e.g., OnePlus 3T), if we've already turned on torch for an autofocus immediately before
                        // taking the photo, ae convergence may have already occurred - so if we called runFakePrecapture(), we'd just get
                        // stuck waiting for CONTROL_AE_STATE_SEARCHING which will never happen, until we hit the timeout - it works,
                        // but it means taking photos is slower as we have to wait until the timeout
                        // Instead we assume that ae scanning has already started, so go straight to STATE_WAITING_FAKE_PRECAPTURE_DONE,
                        // which means wait until we're no longer CONTROL_AE_STATE_SEARCHING.
                        // (Note, we don't want to go straight to takePictureAfterPrecapture(), as it might be that ae scanning is still
                        // taking place.)
                        // An alternative solution would be to switch torch off and back on again to cause ae scanning to start - but
                        // at worst this is tricky to get working, and at best, taking photos would be slower.
                        fakePrecaptureTorchPerformed =
                            true // so we know to fire the torch when capturing
                        testFakeFlashPrecapture++ // for testing, should treat this same as if we did do the precapture
                        state = STATE_WAITING_FAKE_PRECAPTURE_DONE
                        precaptureStateChangeTimeMs = System.currentTimeMillis()
                    } else {
                        callRunFakePrecapture = true
                    }
                } else {
                    // standard flash, flash auto or on
                    // note that we don't call needsFlash() (or use isFlashRequired) - as if ae state is neither CONVERGED nor FLASH_REQUIRED, we err on the side
                    // of caution and don't skip the precapture
                    //boolean needsFlash = captureResultAe != null && captureResultAe == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
                    val needsFlash =
                        captureResultAe != null && captureResultAe != CaptureResult.CONTROL_AE_STATE_CONVERGED
                    if (cameraSettings.flashValue == "flash_auto" && !needsFlash) {
                        // if we call precapture anyway, flash wouldn't fire - but we tend to have a pause
                        // so skipping the precapture if flash isn't going to fire makes this faster
                        if (MyDebug.LOG) Log.d(TAG, "flash auto, but we don't need flash")
                        callTakePictureAfterPrecapture = true
                    } else {
                        callRunPrecapture = true
                    }
                }
            }
        }

        // important to call functions outside of locks, so that they can in turn call callbacks without a lock
        if (callTakePictureAfterPrecapture) {
            takePictureAfterPrecapture()
        } else if (callRunFakePrecapture) {
            runFakePrecapture()
        } else if (callRunPrecapture) {
            runPrecapture()
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "takePicture() took: " + (System.currentTimeMillis() - debugTime))
        }
    }

    override var displayOrientation: Int
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getDisplayOrientation not supported by this API")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        set(degrees) {
            // for CameraController2, the preview display orientation is handled via the TextureView's transform
            if (MyDebug.LOG) Log.d(TAG, "setDisplayOrientation not supported by this API")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }

    override fun unlock() {
        // do nothing at this stage
    }

    override fun initVideoRecorderPrePrepare(videoRecorder: MediaRecorder?) {
        // if we change where we play the START_VIDEO_RECORDING sound, make sure it can't be heard in resultant video
        blockForExtensions() // not supported for extension sessions
        playSound(MediaActionSound.START_VIDEO_RECORDING)
    }

    @Throws(CameraControllerException::class)
    override fun initVideoRecorderPostPrepare(
        videoRecorder: MediaRecorder?,
        wantPhotoVideoRecording: Boolean
    ) {
        if (MyDebug.LOG) Log.d(TAG, "initVideoRecorderPostPrepare")
        if (camera == null) {
            Log.e(TAG, "no camera")
            throw CameraControllerException()
        }
        blockForExtensions() // not supported for extension sessions
        try {
            if (MyDebug.LOG) Log.d(TAG, "obtain video_recorder surface")
            previewBuilder = camera?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            if (MyDebug.LOG) Log.d(TAG, "done")
            previewIsVideoMode = true
            previewBuilder?.set(
                CaptureRequest.CONTROL_CAPTURE_INTENT,
                CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
            )
            previewBuilder?.let { cameraSettings.setupBuilder(it, false) }
            createCaptureSession(videoRecorder, wantPhotoVideoRecording)
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to create capture request for video")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
            throw CameraControllerException()
        }
    }

    @Throws(CameraControllerException::class)
    override fun reconnect() {
        if (MyDebug.LOG) Log.d(TAG, "reconnect")
        // if we change where we play the STOP_VIDEO_RECORDING sound, make sure it can't be heard in resultant video
        playSound(MediaActionSound.STOP_VIDEO_RECORDING)
        createPreviewRequest()
        createCaptureSession(null, false)
        /*if( MyDebug.LOG )
            Log.d(TAG, "add preview surface to previewBuilder");
        Surface surface = getPreviewSurface();
        previewBuilder.addTarget(surface);*/
        //setRepeatingRequest();
    }

    override val parametersString: String?
        get() = null

    override fun captureResultIsAEScanning(): Boolean {
        return captureResultIsAeScanning
    }

    override fun needsFlash(): Boolean {
        //boolean needsFlash = captureResultAe != null && captureResultAe == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED;
        //return needsFlash;
        return isFlashRequired
    }

    override fun needsFrontScreenFlash(): Boolean {
        return cameraSettings.flashValue == "flash_frontscreen_on" ||
                (cameraSettings.flashValue == "flash_frontscreen_auto" && fireAutoFlashFrontScreen())
    }

    override fun captureResultHasWhiteBalanceTemperature(): Boolean {
        return captureResultHasWhiteBalanceRggb
    }

    override fun captureResultWhiteBalanceTemperature(): Int {
        // for performance reasons, we don't convert from rggb to temperature in every frame, rather only when requested
        return convertRggbVectorToTemperature(
            captureResultWhiteBalanceRggb!!
        )
    }

    override fun captureResultHasIso(): Boolean {
        return captureResultHasIso
    }

    override fun captureResultIso(): Int {
        return captureResultIso
    }

    override fun captureResultHasExposureTime(): Boolean {
        return captureResultHasExposureTime
    }

    override fun captureResultExposureTime(): Long {
        return captureResultExposureTime
    }

    override fun captureResultHasFrameDuration(): Boolean {
        return captureResultHasFrameDuration
    }

    override fun captureResultFrameDuration(): Long {
        return captureResultFrameDuration
    }

    override fun captureResultHasFocusDistance(): Boolean {
        return captureResultHasFocusDistance
    }

    override fun captureResultFocusDistance(): Float {
        return captureResultFocusDistance
    }

    override fun captureResultHasAperture(): Boolean {
        return captureResultHasAperture
    }

    override fun captureResultAperture(): Float {
        return captureResultAperture
    }

    /*
    @Override
    public boolean captureResultHasFocusDistance() {
        return captureResultHasFocusDistance;
    }

    @Override
    public float captureResultFocusDistanceMin() {
        return captureResultFocusDistanceMin;
    }

    @Override
    public float captureResultFocusDistanceMax() {
        return captureResultFocusDistanceMax;
    }
    */
    private var previewExtensionCaptureCallback: ExtensionCaptureCallback? = null

    @RequiresApi(api = Build.VERSION_CODES.S)
    inner class MyExtensionCaptureCallback : ExtensionCaptureCallback() {
        override fun onCaptureStarted(
            session: CameraExtensionSession,
            request: CaptureRequest, timestamp: Long
        ) {
            /*if( MyDebug.LOG )
                Log.d(TAG, "onCaptureStarted");*/
            if (MyDebug.LOG) {
                if (previewCaptureCallback.getRequestTagType(request) == RequestTagType.CAPTURE) {
                    Log.d(TAG, "onCaptureStarted: capture")
                } else if (previewCaptureCallback.getRequestTagType(request) == RequestTagType.CAPTURE_BURST_IN_PROGRESS) {
                    Log.d(TAG, "onCaptureStarted: capture burst in progress")
                }
            }

            // for previewCaptureCallback, we set hasReceivedFrame in onCaptureCompleted(), but
            // that method doesn't exist for ExtensionCaptureCallback, and the other methods such as
            // onCaptureSequenceCompleted aren't called for the preview captures;
            // onCaptureResultAvailable meanwhile is only called if
            // CameraExtensionCharacteristics.getAvailableCaptureResultKeys() returns a non-empty
            // list
            if (!hasReceivedFrame) {
                hasReceivedFrame = true
                if (MyDebug.LOG) Log.d(TAG, "has_received_frame now set to true")
            }

            super.onCaptureStarted(session, request, timestamp)
        }

        override fun onCaptureFailed(
            session: CameraExtensionSession,
            request: CaptureRequest
        ) {
            if (MyDebug.LOG) {
                Log.e(TAG, "onCaptureFailed")
            }
            super.onCaptureFailed(session, request)
        }

        override fun onCaptureSequenceCompleted(
            session: CameraExtensionSession,
            sequenceId: Int
        ) {
            if (MyDebug.LOG) {
                Log.d(TAG, "onCaptureSequenceCompleted")
                Log.d(TAG, "sequenceId: $sequenceId")
            }

            // since we don't receive the request, we can't check for a request tag type of
            // RequestTagType.CAPTURE - but this method should only be called for photo captures
            // anyway
            testCaptureResults++
            modifiedFromCameraSettings = false

            previewCaptureCallback.callCheckImagesCompleted()

            super.onCaptureSequenceCompleted(session, sequenceId)
        }

        override fun onCaptureSequenceAborted(
            session: CameraExtensionSession,
            sequenceId: Int
        ) {
            if (MyDebug.LOG) {
                Log.d(TAG, "onCaptureSequenceAborted")
                Log.d(TAG, "sequenceId: $sequenceId")
            }
            super.onCaptureSequenceAborted(session, sequenceId)
        }

        override fun onCaptureResultAvailable(
            session: CameraExtensionSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            previewCaptureCallback.updateCachedCaptureResult(result)
        }

        override fun onCaptureProcessProgressed(
            session: CameraExtensionSession,
            request: CaptureRequest, @IntRange(from = 0, to = 100) progress: Int
        ) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "onCaptureProcessProgressed: $progress"
            )

            val activity = context as Activity
            activity.runOnUiThread {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "onCaptureProcessProgressed UI thread: $progress"
                )
                if (pictureCb != null) {
                    pictureCb?.onExtensionProgress(progress)
                }
            }
        }
    }

    private val previewCaptureCallback = MyCaptureCallback()

    /** Opens the camera device.
     * @param context Application context.
     * @param cameraId Which camera to open (must be between 0 and CameraControllerManager2.getNumberOfCameras()-1).
     * @param cameraIdSPhysical If non-null, specifies a physical camera to use (must be a member of CameraFeatures.physicalCameraIds for this camera or the corresponding logical camera)
     * @param cameraFeaturesCaches This should be supplied as an initially empty map, which CameraController2 can use to improve performance on subsequent creations of CameraController2.
     * The same cameraFeaturesCaches should be supplied to future new CameraController2 objects in order to benefit.
     * @param previewErrorCb onError() will be called if the preview stops due to error.
     * @param cameraErrorCb onError() will be called if the camera closes due to serious error. No more calls to the CameraController2 object should be made (though a new one can be created, to try reopening the camera).
     * @throws CameraControllerException if the camera device fails to open.
     */
    init {
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "create new CameraController2: $cameraId / $cameraIdSPhysical"
            )
            Log.d(TAG, "this: $this")
        }

        this.cameraFeaturesCaches = cameraFeaturesCaches.toMutableMap()
        this.cameraIdSPhysical = cameraIdSPhysical

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            this.previewExtensionCaptureCallback = MyExtensionCaptureCallback()
        } else {
            this.previewExtensionCaptureCallback = null
        }

        this.context = context
        this.previewErrorCb = previewErrorCb
        this.cameraErrorCb = cameraErrorCb

        //this.isOneplus = Build.MANUFACTURER.toLowerCase(Locale.US).contains("oneplus");
        this.isSamsung = Build.MANUFACTURER.lowercase().contains("samsung")
        this.isSamsungS7 = Build.MODEL.lowercase().contains("sm-g93")
        this.isSamsungGalaxyS =
            isSamsung && (Build.MODEL.lowercase().contains("sm-g") || Build.MODEL.lowercase()
                .contains("sm-s"))
        this.isSamsungGalaxyF = isSamsung && Build.MODEL.lowercase().contains("sm-f")
        if (MyDebug.LOG) {
            Log.d(TAG, "is_samsung: $isSamsung")
            Log.d(TAG, "is_samsung_s7: $isSamsungS7")
            Log.d(
                TAG,
                "is_samsung_galaxy_s: $isSamsungGalaxyS"
            )
            Log.d(
                TAG,
                "is_samsung_galaxy_f: $isSamsungGalaxyF"
            )
        }

        thread = HandlerThread("CameraBackground")
        thread!!.start()
        handler = Handler(thread!!.looper)
        executor = Executor { command -> handler?.post(command) }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        class MyStateCallback : CameraDevice.StateCallback() {
            var callbackDone: Boolean =
                false // must synchronize on this and notifyAll when setting to true
            var firstCallback: Boolean =
                true // Google Camera says we may get multiple callbacks, but only the first indicates the status of the camera opening operation

            override fun onOpened(cam: CameraDevice) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "camera opened, first_callback? $firstCallback"
                )
                /*if( true ) // uncomment to test timeout code
                    return;*/
                if (firstCallback) {
                    firstCallback = false

                    try {
                        // we should be able to get characteristics at any time, but Google Camera only does so when camera opened - so do similarly to be safe
                        if (MyDebug.LOG) Log.d(TAG, "try to get camera characteristics")
                        characteristics =
                            manager.getCameraCharacteristics((cameraIdSPhysical ?: cameraIdS))
                        if (MyDebug.LOG) Log.d(TAG, "successfully obtained camera characteristics")
                        // now read cached values
                        this@CameraController2.cameraOrientation =
                            characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) as Int

                        when (characteristics?.get(CameraCharacteristics.LENS_FACING)) {
                            CameraMetadata.LENS_FACING_FRONT -> this@CameraController2.facing =
                                Facing.FACING_FRONT

                            CameraMetadata.LENS_FACING_BACK -> this@CameraController2.facing =
                                Facing.FACING_BACK

                            CameraMetadata.LENS_FACING_EXTERNAL -> this@CameraController2.facing =
                                Facing.FACING_EXTERNAL

                            else -> {
                                Log.e(
                                    TAG,
                                    "unknown camera_facing: " + characteristics?.get(
                                        CameraCharacteristics.LENS_FACING
                                    )
                                )
                                this@CameraController2.facing = Facing.FACING_UNKNOWN
                            }
                        }

                        if (MyDebug.LOG) {
                            Log.d(
                                TAG,
                                "characteristics_sensor_orientation: " + this@CameraController2.cameraOrientation
                            )
                            Log.d(TAG, "characteristics_facing: " + this@CameraController2.facing)
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cameraIdSPhysical == null) {
                            // n.b., getCameraExtensionCharacteristics is documented as saying this must be the standalone cameraID that can be directly opened with OpenKamera()
                            // however on Pixel 6 Pro at least, night mode extension only ever uses the wide camera, even if telephoto or ultrawide is set as a physical camera,
                            // so don't support for now
                            extensionCharacteristics =
                                manager.getCameraExtensionCharacteristics(cameraIdS)
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "successfully obtained camera characteristics"
                            )

                            // if we update the key used for cameraFeaturesCaches, remember to also update the code
                            // for adding to the cameraFeaturesCaches
                            cameraFeaturesCache = cameraFeaturesCaches[cameraIdS]
                        }

                        this@CameraController2.camera = cam

                        // note, this won't start the preview yet, but we create the previewBuilder in order to start setting camera parameters
                        createPreviewRequest()
                    } catch (e: CameraAccessException) {
                        if (MyDebug.LOG) {
                            Log.e(TAG, "failed to get camera characteristics")
                            Log.e(TAG, "reason: " + e.reason)
                            Log.e(TAG, "message: " + e.message)
                        }
                        e.printStackTrace()
                        // don't throw CameraControllerException here - instead error is handled by setting callbackDone to callbackDone, and the fact that camera will still be null
                    }

                    if (MyDebug.LOG) Log.d(TAG, "about to synchronize to say callback done")
                    synchronized(openCameraLock) {
                        callbackDone = true
                        if (MyDebug.LOG) Log.d(TAG, "callback done, about to notify")
                        (openCameraLock as Object).notifyAll()
                        if (MyDebug.LOG) Log.d(TAG, "callback done, notify done")
                    }
                }
            }

            override fun onClosed(cam: CameraDevice) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "camera closed, first_callback? $firstCallback"
                )
                // caller should ensure camera variables are set to null
                if (firstCallback) {
                    firstCallback = false
                }
            }

            override fun onDisconnected(cam: CameraDevice) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "camera disconnected, first_callback? $firstCallback"
                )
                if (firstCallback) {
                    firstCallback = false
                    // must call close() if disconnected before camera was opened
                    // need to set the camera to null first, as closing the camera may take some time, and we don't want any other operations to continue (if called from main thread)
                    this@CameraController2.camera = null
                    if (MyDebug.LOG) Log.d(TAG, "onDisconnected: camera is now set to null")
                    cam.close()
                    if (MyDebug.LOG) Log.d(TAG, "onDisconnected: camera is now closed")
                    if (MyDebug.LOG) Log.d(TAG, "about to synchronize to say callback done")
                    synchronized(openCameraLock) {
                        callbackDone = true
                        if (MyDebug.LOG) Log.d(TAG, "callback done, about to notify")
                        (openCameraLock as Object).notifyAll()
                        if (MyDebug.LOG) Log.d(TAG, "callback done, notify done")
                    }
                }
            }

            override fun onError(cam: CameraDevice, error: Int) {
                // n.b., as this is potentially serious error, we always log even if MyDebug.LOG is false
                Log.e(TAG, "camera error: $error")
                if (MyDebug.LOG) {
                    Log.d(TAG, "received camera: $cam")
                    Log.d(TAG, "actual camera: " + this@CameraController2.camera)
                    Log.d(
                        TAG,
                        "first_callback? $firstCallback"
                    )
                }
                if (firstCallback) {
                    firstCallback = false
                }
                this@CameraController2.onError(cam)
                if (MyDebug.LOG) Log.d(TAG, "about to synchronize to say callback done")
                synchronized(openCameraLock) {
                    callbackDone = true
                    if (MyDebug.LOG) Log.d(TAG, "callback done, about to notify")
                    (openCameraLock as Object).notifyAll()
                    if (MyDebug.LOG) Log.d(TAG, "callback done, notify done")
                }
            }
        }

        val myStateCallback = MyStateCallback()

        try {
            if (MyDebug.LOG) Log.d(TAG, "get camera id list")
            this.cameraIdS = manager.cameraIdList[cameraId]
            if (MyDebug.LOG) Log.d(
                TAG,
                "about to Open Kamera: $cameraIdS"
            )
            manager.openCamera(cameraIdS, myStateCallback, handler)
            if (MyDebug.LOG) Log.d(TAG, "Open Kamera request complete")
        } catch (e: CameraAccessException) {
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to Open Kamera: CameraAccessException")
                Log.e(TAG, "reason: " + e.reason)
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
            throw CameraControllerException()
        } catch (e: UnsupportedOperationException) {
            // Google Camera catches UnsupportedOperationException
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to Open Kamera: UnsupportedOperationException")
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
            throw CameraControllerException()
        } catch (e: SecurityException) {
            // Google Camera catches SecurityException
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to Open Kamera: SecurityException")
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
            throw CameraControllerException()
        } catch (e: IllegalArgumentException) {
            // have seen this from Google Play
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to Open Kamera: IllegalArgumentException")
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
            throw CameraControllerException()
        } catch (e: ArrayIndexOutOfBoundsException) {
            // Have seen this from Google Play - even though the Preview should have checked the
            // cameraId is within the valid range! Although potentially this could happen if
            // getCameraIdList() returns an empty list.
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to Open Kamera: ArrayIndexOutOfBoundsException")
                Log.e(TAG, "message: " + e.message)
            }
            e.printStackTrace()
            throw CameraControllerException()
        }

        // set up a timeout - sometimes if the camera has got in a state where it can't be opened until after a reboot, we'll never even get a myStateCallback callback called
        handler?.postDelayed(object : Runnable {
            override fun run() {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "check if camera has opened in reasonable time: $this"
                )
                synchronized(openCameraLock) {
                    if (MyDebug.LOG) {
                        Log.d(TAG, "synchronized on open_camera_lock")
                        Log.d(TAG, "callback_done: " + myStateCallback.callbackDone)
                    }
                    if (!myStateCallback.callbackDone) {
                        // n.b., as this is potentially serious error, we always log even if MyDebug.LOG is false
                        Log.e(TAG, "timeout waiting for camera callback")
                        myStateCallback.firstCallback = true
                        myStateCallback.callbackDone = true
                        (openCameraLock as Object).notifyAll()
                    }
                }
            }
        }, 10000)

        if (MyDebug.LOG) Log.d(TAG, "wait until camera opened...")
        // need to wait until camera is opened
        // whilst this blocks, this should be running on a background thread anyway (see Preview.OpenKamera()) - due to maintaining
        // compatibility with the way the old camera API works, it's easier to handle running on a background thread at a higher level,
        // rather than exiting here
        synchronized(openCameraLock) {
            while (!myStateCallback.callbackDone) {
                try {
                    // release the lock, and wait until myStateCallback calls notifyAll()
                    (openCameraLock as Object).wait()
                } catch (e: InterruptedException) {
                    if (MyDebug.LOG) Log.d(TAG, "interrupted while waiting until camera opened")
                    e.printStackTrace()
                }
            }
        }
        if (camera == null) {
            // n.b., as this is potentially serious error, we always log even if MyDebug.LOG is false
            Log.e(TAG, "camera failed to open")
            throw CameraControllerException()
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "camera now opened: $camera"
        )

        /*{
            // test error handling on background thread
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if( MyDebug.LOG )
                        Log.d(TAG, "test camera error");
                    myStateCallback.onError(camera, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
                }
            }, 5000);
        }*/

        /*CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraIdSPhysical != null ? cameraIdSPhysical : cameraIdS);
        StreamConfigurationMap configs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size [] cameraPictureSizes = configs.getOutputSizes(ImageFormat.JPEG);
        imageReader = ImageReader.newInstance(cameraPictureSizes[0].getWidth(), , ImageFormat.JPEG, 2);*/

        // preload sounds to reduce latency - important so that START_VIDEO_RECORDING sound doesn't play after video has started (which means it'll be heard in the resultant video)
        mediaActionSound.load(MediaActionSound.START_VIDEO_RECORDING)
        mediaActionSound.load(MediaActionSound.STOP_VIDEO_RECORDING)
        mediaActionSound.load(MediaActionSound.SHUTTER_CLICK)
        // Samsung Galaxy devices have bug where MediaActionSound always plays at 100% volume - the SHUTTER_CLICK sounds
        // really harsh/loud, so the video recording beep reduces this problem
        shutterClickSound =
            if (isSamsung) MediaActionSound.START_VIDEO_RECORDING else MediaActionSound.SHUTTER_CLICK

        // expand tonemap curves
        jtvideoValues = enforceMinTonemapCurvePoints(jtvideoValuesBase)
        jtlogValues = enforceMinTonemapCurvePoints(jtlogValuesBase)
        jtlog2Values = enforceMinTonemapCurvePoints(jtlog2ValuesBase)
    }

    inner class MyCaptureCallback : CaptureCallback() {
        private var lastProcessFrameNumber: Long = 0
        private var lastAfState = -1

        fun getRequestTagType(request: CaptureRequest): RequestTagType? {
            val tag = request.tag ?: return null
            val requestTag: RequestTagObject =
                tag as RequestTagObject
            return requestTag.getType()
        }

        override fun onCaptureBufferLost(
            session: CameraCaptureSession,
            request: CaptureRequest,
            target: Surface,
            frameNumber: Long
        ) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "onCaptureBufferLost: $frameNumber"
            )
            super.onCaptureBufferLost(session, request, target, frameNumber)
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            if (MyDebug.LOG) {
                Log.e(TAG, "onCaptureFailed: $failure")
                Log.d(TAG, "reason: " + failure.reason)
                Log.d(TAG, "was image captured?: " + failure.wasImageCaptured())
                Log.d(TAG, "sequenceId: " + failure.sequenceId)
            }
            super.onCaptureFailed(
                session,
                request,
                failure
            ) // API docs say this does nothing, but call it just to be safe
        }

        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            if (MyDebug.LOG) {
                Log.d(TAG, "onCaptureSequenceAborted")
                Log.d(TAG, "sequenceId: $sequenceId")
            }
            super.onCaptureSequenceAborted(
                session,
                sequenceId
            ) // API docs say this does nothing, but call it just to be safe
        }

        override fun onCaptureSequenceCompleted(
            session: CameraCaptureSession,
            sequenceId: Int,
            frameNumber: Long
        ) {
            if (MyDebug.LOG) {
                Log.d(TAG, "onCaptureSequenceCompleted")
                Log.d(TAG, "sequenceId: $sequenceId")
                Log.d(TAG, "frameNumber: $frameNumber")
            }
            super.onCaptureSequenceCompleted(
                session,
                sequenceId,
                frameNumber
            ) // API docs say this does nothing, but call it just to be safe
        }

        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {
            if (MyDebug.LOG) {
                if (getRequestTagType(request) == RequestTagType.CAPTURE) {
                    Log.d(TAG, "onCaptureStarted: capture")
                    Log.d(TAG, "frameNumber: $frameNumber")
                    Log.d(TAG, "exposure time: " + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME))
                } else if (getRequestTagType(request) == RequestTagType.CAPTURE_BURST_IN_PROGRESS) {
                    Log.d(TAG, "onCaptureStarted: capture burst in progress")
                    Log.d(TAG, "frameNumber: $frameNumber")
                    Log.d(TAG, "exposure time: " + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME))
                }
            }
            // n.b., we don't play the shutter sound here for RequestTagType.CAPTURE, as it typically sounds "too late"
            // (if ever we changed this, would also need to fix for burst, where we only set the RequestTagType.CAPTURE for the last image)
            super.onCaptureStarted(session, request, timestamp, frameNumber)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            /*if( MyDebug.LOG )
                Log.d(TAG, "onCaptureCompleted");*/
            if (MyDebug.LOG) {
                if (getRequestTagType(request) == RequestTagType.CAPTURE) {
                    Log.d(TAG, "onCaptureCompleted: capture")
                    Log.d(TAG, "sequenceId: " + result.sequenceId)
                    Log.d(TAG, "frameNumber: " + result.frameNumber)
                    Log.d(TAG, "exposure time: " + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME))
                    Log.d(
                        TAG,
                        "frame duration: " + request.get(CaptureRequest.SENSOR_FRAME_DURATION)
                    )
                } else if (getRequestTagType(request) == RequestTagType.CAPTURE_BURST_IN_PROGRESS) {
                    Log.d(TAG, "onCaptureCompleted: capture burst in progress")
                    Log.d(TAG, "sequenceId: " + result.sequenceId)
                    Log.d(TAG, "frameNumber: " + result.frameNumber)
                    Log.d(TAG, "exposure time: " + request.get(CaptureRequest.SENSOR_EXPOSURE_TIME))
                    Log.d(
                        TAG,
                        "frame duration: " + request.get(CaptureRequest.SENSOR_FRAME_DURATION)
                    )
                }
            }
            process(request, result)
            processCompleted(request, result)
            super.onCaptureCompleted(
                session,
                request,
                result
            ) // API docs say this does nothing, but call it just to be safe (as with Google Camera)
        }

        /** Updates cached information regarding the capture result status related to auto-exposure.
         */
        fun updateCachedAECaptureStatus(result: CaptureResult) {
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            /*if( MyDebug.LOG ) {
                if( aeState == null )
                    Log.d(TAG, "CONTROL_AE_STATE is null");
                else if( aeState == CaptureResult.CONTROL_AE_STATE_INACTIVE )
                    Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_INACTIVE");
                else if( aeState == CaptureResult.CONTROL_AE_STATE_SEARCHING )
                    Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_SEARCHING");
                else if( aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED )
                    Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_CONVERGED");
                else if( aeState == CaptureResult.CONTROL_AE_STATE_LOCKED )
                    Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_LOCKED");
                else if( aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED )
                    Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_FLASH_REQUIRED");
                else if( aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE )
                    Log.d(TAG, "CONTROL_AE_STATE = CONTROL_AE_STATE_PRECAPTURE");
                else
                    Log.d(TAG, "CONTROL_AE_STATE = " + aeState);
            }*/
            val flashMode = result.get(CaptureResult.FLASH_MODE)

            /*if( MyDebug.LOG ) {
                if( flashMode == null )
                    Log.d(TAG, "FLASH_MODE is null");
                else if( flashMode == CaptureResult.FLASH_MODE_OFF )
                    Log.d(TAG, "FLASH_MODE = FLASH_MODE_OFF");
                else if( flashMode == CaptureResult.FLASH_MODE_SINGLE )
                    Log.d(TAG, "FLASH_MODE = FLASH_MODE_SINGLE");
                else if( flashMode == CaptureResult.FLASH_MODE_TORCH )
                    Log.d(TAG, "FLASH_MODE = FLASH_MODE_TORCH");
                else
                    Log.d(TAG, "FLASH_MODE = " + flashMode);
            }*/
            if (useFakePrecaptureMode && (fakePrecaptureTorchFocusPerformed || fakePrecaptureTorchPerformed) && flashMode != null && flashMode == CameraMetadata.FLASH_MODE_TORCH) {
                // don't change ae state while torch is on for fake flash
            } else if (aeState == null) {
                captureResultAe = null
                isFlashRequired = false
            } else if (aeState != captureResultAe) {
                // need to store this before calling the autofocus callbacks below
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "CONTROL_AE_STATE changed from $captureResultAe to $aeState"
                )
                captureResultAe = aeState
                // captureResultAe should always be non-null here, as we've already handled aeState separately
                if (captureResultAe == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED && !isFlashRequired) {
                    isFlashRequired = true
                    if (MyDebug.LOG) Log.d(TAG, "flash now required")
                } else if (captureResultAe == CaptureResult.CONTROL_AE_STATE_CONVERGED && isFlashRequired) {
                    isFlashRequired = false
                    if (MyDebug.LOG) Log.d(TAG, "flash no longer required")
                }
            }

            captureResultIsAeScanning =
                if (aeState != null && aeState == CaptureResult.CONTROL_AE_STATE_SEARCHING) {
                    /*if( MyDebug.LOG && !captureResultIsAeScanning )
                             Log.d(TAG, "aeState now searching");*/
                    true
                } else {
                    /*if( MyDebug.LOG && captureResultIsAeScanning )
                             Log.d(TAG, "aeState stopped searching");*/
                    false
                }
        }

        fun handleStateChange(request: CaptureRequest, result: CaptureResult) {
            // use Integer instead of int, so can compare to null: Google Play crashes confirmed that this can happen; Google Camera also ignores cases with null af state
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            /*if( MyDebug.LOG ) {
                if( afState == null )
                    Log.d(TAG, "CONTROL_AF_STATE is null");
                else if( afState == CaptureResult.CONTROL_AF_STATE_INACTIVE )
                    Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_INACTIVE");
                else if( afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN )
                    Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_PASSIVE_SCAN");
                else if( afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED )
                    Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_PASSIVE_FOCUSED");
                else if( afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN )
                    Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_ACTIVE_SCAN");
                else if( afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED )
                    Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_FOCUSED_LOCKED");
                else if( afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED )
                    Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_NOT_FOCUSED_LOCKED");
                else if( afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED )
                    Log.d(TAG, "CONTROL_AF_STATE = CONTROL_AF_STATE_PASSIVE_UNFOCUSED");
                else
                    Log.d(TAG, "CONTROL_AF_STATE = " + afState);
            }*/
            // CONTROL_AE_STATE can be null on some devices, so as with afState, use Integer
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)

            /*Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
            if( MyDebug.LOG ) {
                if( awbState == null )
                    Log.d(TAG, "CONTROL_AWB_STATE is null");
                else if( awbState == CaptureResult.CONTROL_AWB_STATE_INACTIVE )
                    Log.d(TAG, "CONTROL_AWB_STATE = CONTROL_AWB_STATE_INACTIVE");
                else if( awbState == CaptureResult.CONTROL_AWB_STATE_SEARCHING )
                    Log.d(TAG, "CONTROL_AWB_STATE = CONTROL_AWB_STATE_SEARCHING");
                else if( awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED )
                    Log.d(TAG, "CONTROL_AWB_STATE = CONTROL_AWB_STATE_CONVERGED");
                else if( awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED )
                    Log.d(TAG, "CONTROL_AWB_STATE = CONTROL_AWB_STATE_LOCKED");
                else
                    Log.d(TAG, "CONTROL_AWB_STATE = " + awbState);
            }*/
            val autofocusTimeout =
                autofocusTimeMs != -1L && System.currentTimeMillis() > autofocusTimeMs + AUTOFOCUS_TIMEOUT_C
            if (MyDebug.LOG && autofocusTimeout) Log.d(TAG, "autofocus timeout!")
            if (afState != null && afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN && !autofocusTimeout) {
                /*if( MyDebug.LOG )
                    Log.d(TAG, "not ready for capture: " + afState);*/
                readyForCapture = false
            } else {
                /*if( MyDebug.LOG )
                    Log.d(TAG, "ready for capture: " + afState);*/
                readyForCapture = true
                if (autofocusCb != null && (!DO_AF_TRIGGER_FOR_CONTINUOUS || useFakePrecaptureMode) && focusIsContinuous()) {
                    val focusMode = previewBuilder?.get(CaptureRequest.CONTROL_AF_MODE)
                    if (focusMode != null && focusMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "call autofocus callback, as continuous mode and not focusing: $afState"
                        )
                        // need to check afState != null, I received Google Play crash in 1.33 where it was null
                        val focusSuccess =
                            afState != null && (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)
                        if (MyDebug.LOG) {
                            if (focusSuccess) Log.d(TAG, "autofocus success")
                            else Log.d(TAG, "autofocus failed")
                            if (afState == null) Log.e(
                                TAG,
                                "continuous focus mode but af_state is null"
                            )
                            else Log.d(
                                TAG,
                                "af_state: $afState"
                            )
                        }
                        if (afState == null) {
                            testAfStateNullFocus++
                        }
                        autofocusCb!!.onAutoFocus(focusSuccess)
                        autofocusCb = null
                        autofocusTimeMs = -1
                        captureFollowsAutofocusHint = false
                    }
                }
            }

            /*if( MyDebug.LOG ) {
                if( autofocusCb == null ) {
                    if( afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED )
                        Log.d(TAG, "processAF: autofocus success but no callback set");
                    else if( afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED )
                        Log.d(TAG, "processAF: autofocus failed but no callback set");
                }
            }*/
            if (fakePrecaptureTurnOnTorchId != null && fakePrecaptureTurnOnTorchId === request) {
                if (MyDebug.LOG) Log.d(TAG, "torch turned on for fake precapture")
                fakePrecaptureTurnOnTorchId = null
            }

            if (state == STATE_NORMAL) {
                // do nothing
            } else if (state == STATE_WAITING_AUTOFOCUS) {
                if (afState == null) {
                    // autofocus shouldn't really be requested if af not available, but still allow this rather than getting stuck waiting for autofocus to complete
                    if (MyDebug.LOG) Log.e(TAG, "waiting for autofocus but af_state is null")
                    testAfStateNullFocus++
                    state = STATE_NORMAL
                    precaptureStateChangeTimeMs = -1
                    if (autofocusCb != null) {
                        autofocusCb!!.onAutoFocus(false)
                        autofocusCb = null
                    }
                    autofocusTimeMs = -1
                    captureFollowsAutofocusHint = false
                } else if (afState != lastAfState || autofocusTimeout) {
                    // check for autofocus completing
                    if (autofocusTimeout || afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED /*||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED*/
                    ) {
                        val focusSuccess =
                            afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                        if (MyDebug.LOG) {
                            if (focusSuccess) Log.d(TAG, "onCaptureCompleted: autofocus success")
                            else Log.d(TAG, "onCaptureCompleted: autofocus failed")
                            Log.d(
                                TAG,
                                "af_state: $afState"
                            )
                        }
                        state = STATE_NORMAL
                        precaptureStateChangeTimeMs = -1
                        if (useFakePrecaptureMode && fakePrecaptureTorchFocusPerformed) {
                            fakePrecaptureTorchFocusPerformed = false
                            if (!captureFollowsAutofocusHint) {
                                // If we're going to be taking a photo immediately after the autofocus, it's better for the fake flash
                                // mode to leave the torch on. If we don't do this, one of the following issues can happen:
                                // - On OnePlus 3T, the torch doesn't get turned off, but because we've switched off the torch flag
                                //   in previewBuilder, we go ahead with the precapture routine instead of
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "turn off torch after focus (fake precapture code)"
                                )

                                // same hack as in setFlashValue() - for fake precapture we need to turn off the torch mode that was set, but
                                // at least on Nexus 6, we need to turn to flashOff to turn off the torch!
                                val savedFlashValue: String = cameraSettings.flashValue
                                cameraSettings.flashValue = "flash_off"
                                cameraSettings.setAEMode(previewBuilder, false)
                                try {
                                    capture()
                                } catch (e: CameraAccessException) {
                                    if (MyDebug.LOG) {
                                        Log.e(
                                            TAG,
                                            "failed to do capture to turn off torch after autofocus"
                                        )
                                        Log.e(TAG, "reason: " + e.reason)
                                        Log.e(TAG, "message: " + e.message)
                                    }
                                    e.printStackTrace()
                                }

                                // now set the actual (should be flash auto or flash on) mode
                                cameraSettings.flashValue = savedFlashValue
                                cameraSettings.setAEMode(previewBuilder, false)
                                try {
                                    setRepeatingRequest()
                                } catch (e: CameraAccessException) {
                                    if (MyDebug.LOG) {
                                        Log.e(
                                            TAG,
                                            "failed to set repeating request to turn off torch after autofocus"
                                        )
                                        Log.e(TAG, "reason: " + e.reason)
                                        Log.e(TAG, "message: " + e.message)
                                    }
                                    e.printStackTrace()
                                }
                            } else {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "torch was enabled for autofocus, leave it on for capture (fake precapture code)"
                                )
                            }
                        }
                        if (autofocusCb != null) {
                            autofocusCb!!.onAutoFocus(focusSuccess)
                            autofocusCb = null
                        }
                        autofocusTimeMs = -1
                        captureFollowsAutofocusHint = false
                    }
                }
            } else if (state == STATE_WAITING_PRECAPTURE_START) {
                if (MyDebug.LOG) Log.d(TAG, "waiting for precapture start...")
                if (MyDebug.LOG) {
                    if (aeState != null) Log.d(
                        TAG,
                        "CONTROL_AE_STATE = $aeState"
                    )
                    else Log.d(TAG, "CONTROL_AE_STATE is null")
                }
                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE /*|| aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED*/) {
                    // we have to wait for CONTROL_AE_STATE_PRECAPTURE; if we allow CONTROL_AE_STATE_FLASH_REQUIRED, then on Nexus 6 at least we get poor quality results with flash:
                    // varying levels of brightness, sometimes too bright or too dark, sometimes with blue tinge, sometimes even with green corruption
                    // similarly photos with flash come out too dark on OnePlus 3T
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "precapture started after: " + (System.currentTimeMillis() - precaptureStateChangeTimeMs)
                        )
                    }
                    state = STATE_WAITING_PRECAPTURE_DONE
                    precaptureStateChangeTimeMs = System.currentTimeMillis()
                } else if (precaptureStateChangeTimeMs != -1L && System.currentTimeMillis() - precaptureStateChangeTimeMs > PRECAPTURE_START_TIMEOUT_C) {
                    // hack - give up waiting - sometimes we never get a CONTROL_AE_STATE_PRECAPTURE so would end up stuck
                    // always log error, so we can look for it when manually testing with logging disabled
                    Log.e(TAG, "precapture start timeout")
                    countPrecaptureTimeout++
                    state = STATE_WAITING_PRECAPTURE_DONE
                    precaptureStateChangeTimeMs = System.currentTimeMillis()
                }
            } else if (state == STATE_WAITING_PRECAPTURE_DONE) {
                if (MyDebug.LOG) Log.d(TAG, "waiting for precapture done...")
                if (MyDebug.LOG) {
                    if (aeState != null) Log.d(
                        TAG,
                        "CONTROL_AE_STATE = $aeState"
                    )
                    else Log.d(TAG, "CONTROL_AE_STATE is null")
                }
                if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "precapture completed after: " + (System.currentTimeMillis() - precaptureStateChangeTimeMs)
                        )
                    }
                    state = STATE_NORMAL
                    precaptureStateChangeTimeMs = -1
                    takePictureAfterPrecapture()
                } else if (precaptureStateChangeTimeMs != -1L && System.currentTimeMillis() - precaptureStateChangeTimeMs > PRECAPTURE_DONE_TIMEOUT_C) {
                    // just in case
                    // always log error, so we can look for it when manually testing with logging disabled
                    Log.e(TAG, "precapture done timeout")
                    countPrecaptureTimeout++
                    state = STATE_NORMAL
                    precaptureStateChangeTimeMs = -1
                    takePictureAfterPrecapture()
                }
            } else if (state == STATE_WAITING_FAKE_PRECAPTURE_START) {
                if (MyDebug.LOG) Log.d(TAG, "waiting for fake precapture start...")
                if (MyDebug.LOG) {
                    if (aeState != null) Log.d(
                        TAG,
                        "CONTROL_AE_STATE = $aeState"
                    )
                    else Log.d(TAG, "CONTROL_AE_STATE is null")
                }
                if (fakePrecaptureTurnOnTorchId != null) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "still waiting for torch to come on for fake precapture"
                    )
                }

                if (fakePrecaptureTurnOnTorchId == null && (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_SEARCHING)) {
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "fake precapture started after: " + (System.currentTimeMillis() - precaptureStateChangeTimeMs)
                        )
                    }
                    state = STATE_WAITING_FAKE_PRECAPTURE_DONE
                    precaptureStateChangeTimeMs = System.currentTimeMillis()
                } else if (fakePrecaptureTurnOnTorchId == null && cameraSettings.hasIso && precaptureStateChangeTimeMs != -1L && System.currentTimeMillis() - precaptureStateChangeTimeMs > 100) {
                    // When using manual ISO, we can't make use of changes to the aeState - but at the same time, we don't
                    // need ISO/exposure to re-adjust anyway.
                    // If fakePrecaptureTurnOnTorchId != null, we still wait for the physical torch to turn on.
                    // But if fakePrecaptureTurnOnTorchId==null (i.e., for flashFrontscreenTorch), just wait a short
                    // period to ensure the frontscreen flash has enabled.
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "fake precapture started after: " + (System.currentTimeMillis() - precaptureStateChangeTimeMs)
                        )
                    }
                    state = STATE_WAITING_FAKE_PRECAPTURE_DONE
                    precaptureStateChangeTimeMs = System.currentTimeMillis()
                } else if (precaptureStateChangeTimeMs != -1L && System.currentTimeMillis() - precaptureStateChangeTimeMs > PRECAPTURE_START_TIMEOUT_C) {
                    // just in case
                    // always log error, so we can look for it when manually testing with logging disabled
                    Log.e(TAG, "fake precapture start timeout")
                    countPrecaptureTimeout++
                    state = STATE_WAITING_FAKE_PRECAPTURE_DONE
                    precaptureStateChangeTimeMs = System.currentTimeMillis()
                    fakePrecaptureTurnOnTorchId = null
                }
            } else if (state == STATE_WAITING_FAKE_PRECAPTURE_DONE) {
                if (MyDebug.LOG) Log.d(TAG, "waiting for fake precapture done...")
                if (MyDebug.LOG) {
                    if (aeState != null) Log.d(
                        TAG,
                        "CONTROL_AE_STATE = $aeState"
                    )
                    else Log.d(TAG, "CONTROL_AE_STATE is null")
                    Log.d(
                        TAG,
                        "ready_for_capture? $readyForCapture"
                    )
                }
                // wait for af and ae scanning to end (need to check af too, as in continuous focus mode, a focus may start again after switching torch on for the fake precapture)
                if (readyForCapture && (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_SEARCHING)) {
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "fake precapture completed after: " + (System.currentTimeMillis() - precaptureStateChangeTimeMs)
                        )
                    }
                    state = STATE_NORMAL
                    precaptureStateChangeTimeMs = -1
                    takePictureAfterPrecapture()
                } else if (precaptureStateChangeTimeMs != -1L && System.currentTimeMillis() - precaptureStateChangeTimeMs > PRECAPTURE_DONE_TIMEOUT_C) {
                    // sometimes camera can take a while to stop ae/af scanning, better to just go ahead and take photo
                    // always log error, so we can look for it when manually testing with logging disabled
                    Log.e(TAG, "fake precapture done timeout")
                    countPrecaptureTimeout++
                    state = STATE_NORMAL
                    precaptureStateChangeTimeMs = -1
                    takePictureAfterPrecapture()
                }
            }
        }

        fun handleContinuousFocusMove(result: CaptureResult) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            focusMeteringCoordinator.evaluateContinuousFocusMove(afState)
        }

        /** Processes either a partial or total result.
         */
        fun process(request: CaptureRequest, result: CaptureResult) {
            /*if( MyDebug.LOG )
            Log.d(TAG, "process, state: " + state);*/
            if (result.frameNumber < lastProcessFrameNumber) {
                /*if( MyDebug.LOG )
                    Log.d(TAG, "processAF discarded outdated frame " + result.getFrameNumber() + " vs " + lastProcessFrameNumber);*/
                return
            }
            /*long debugTime = 0;
            if( MyDebug.LOG ) {
                debugTime = System.currentTimeMillis();
            }*/
            lastProcessFrameNumber = result.frameNumber

            updateCachedAECaptureStatus(result)

            handleStateChange(request, result)

            handleContinuousFocusMove(result)

            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (afState != null && afState != lastAfState) {
                /*if( MyDebug.LOG )
                    Log.d(TAG, "CONTROL_AF_STATE changed from " + lastAfState + " to " + afState);*/
                lastAfState = afState
            }

            /*if( MyDebug.LOG ) {
                Log.d(TAG, "process() took: " + (System.currentTimeMillis() - debugTime));
            }*/
        }

        /** Updates cached information regarding the capture result.
         */
        fun updateCachedCaptureResult(result: CaptureResult) {
            if (modifiedFromCameraSettings) {
                // don't update capture results!
                // otherwise have problem taking HDR photos twice in a row, the second one will pick up the exposure time as
                // being from the long exposure of the previous HDR/expo burst!
            } else if (result.get(CaptureResult.SENSOR_SENSITIVITY) != null) {
                captureResultHasIso = true
                captureResultIso = result.get(CaptureResult.SENSOR_SENSITIVITY)!!
                /*if( MyDebug.LOG )
                    Log.d(TAG, "captureResultIso: " + captureResultIso);*/
                /*if( camera_settings.hasIso && Math.abs(camera_settings.iso - captureResultIso) > 10 && previewBuilder != null ) {
                    // ugly hack: problem (on Nexus 6 at least) that when we start recording video (video_recorder.start() call), this often causes the ISO setting to reset to the wrong value!
                    // seems to happen more often with shorter exposure time
                    // seems to happen on other camera apps with Camera2 API to
                    // update: allow some tolerance, as on OnePlus 3T it's normal to have some slight difference between requested and actual
                    // this workaround still means a brief flash with incorrect ISO, but is best we can do for now!
                    // check previewBuilder != null as we have had Google Play crashes from the setRepeatingRequest() call via here
                    // Update 20180326: can no longer reproduce original problem on Nexus 6 (at FullHD or 4K); no evidence of
                    // problems on OnePlus 3T or Nokia 8.
                    // Also note that this code was being activated whenever manual ISO is changed (since we don't immediately
                    // update to the new ISO). At the least, this should be restricted to when recording video, but best to
                    // disable completely now that we don't seem to need it.
                    if( MyDebug.LOG ) {
                        Log.d(TAG, "ISO " + captureResultIso + " different to requested ISO " + camera_settings.iso);
                        Log.d(TAG, "    requested ISO was: " + request.get(CaptureRequest.SENSOR_SENSITIVITY));
                        Log.d(TAG, "    requested AE mode was: " + request.get(CaptureRequest.CONTROL_AE_MODE));
                    }
                    try {
                        setRepeatingRequest();
                    }
                    catch(CameraAccessException e) {
                        if( MyDebug.LOG ) {
                            Log.e(TAG, "failed to set repeating request after ISO hack");
                            Log.e(TAG, "reason: " + e.getReason());
                            Log.e(TAG, "message: " + e.getMessage());
                        }
                        e.printStackTrace();
                    }
                }*/
            } else {
                captureResultHasIso = false
            }

            if (modifiedFromCameraSettings) {
                // see note above
            } else if (result.get(CaptureResult.SENSOR_EXPOSURE_TIME) != null) {
                captureResultHasExposureTime = true
                captureResultExposureTime =
                    result.get(CaptureResult.SENSOR_EXPOSURE_TIME)!!

                // If using manual exposure time longer than maxPreviewExposureTimeC, the preview will be fixed to
                // maxPreviewExposureTimeC, so we should just use the requested manual exposure time.
                // (This affects the exposure time shown on on-screen preview - whilst showing the preview exposure time
                // isn't necessarily wrong, it tended to confuse people, thinking that manual exposure time wasn't working
                // when set above maxPreviewExposureTimeC.)
                // Update: but on some devices (e.g., Galaxy S10e) the reported exposure time can become inaccurate when
                // we set longer preview exposure times (fine at 1/15s, 1/10s, but wrong at 0.2s and 0.3s), possibly this is
                // by design if the preview along supports certain rates(?), but best to fall back to the requested exposure
                // time in manual mode if requested exposure is longer than 1/12s OR the maxPreviewExposureTimeC.
                if (cameraSettings.hasIso && cameraSettings.exposureTime > min(
                        MAX_PREVIEW_EXPOSURE_TIME_C.toDouble(), (1000000000L / 12).toDouble()
                    )
                ) captureResultExposureTime = cameraSettings.exposureTime

                if (captureResultExposureTime <= 0) {
                    // wierd bug seen on Nokia 8
                    captureResultHasExposureTime = false
                }
            } else {
                captureResultHasExposureTime = false
            }

            if (modifiedFromCameraSettings) {
                // see note above
            } else if (result.get(CaptureResult.SENSOR_FRAME_DURATION) != null) {
                captureResultHasFrameDuration = true
                captureResultFrameDuration =
                    result.get(CaptureResult.SENSOR_FRAME_DURATION)!!
            } else {
                captureResultHasFrameDuration = false
            }

            /*if( MyDebug.LOG ) {
                if( result.get(CaptureResult.SENSOR_EXPOSURE_TIME) != null ) {
                    long captureResultExposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Log.d(TAG, "captureResultExposureTime: " + captureResultExposureTime);
                }
                if( result.get(CaptureResult.SENSOR_FRAME_DURATION) != null ) {
                    long captureResultFrameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
                    Log.d(TAG, "captureResultFrameDuration: " + captureResultFrameDuration);
                }
            }*/
            /*if( modifiedFromCameraSettings ) {
                // see note above
            }
            else if( result.get(CaptureResult.LENS_FOCUS_RANGE) != null ) {
                Pair<Float, Float> focusRange = result.get(CaptureResult.LENS_FOCUS_RANGE);
                captureResultHasFocusDistance = true;
                captureResultFocusDistanceMin = focus_range.first;
                captureResultFocusDistanceMax = focus_range.second;
            }
            else {
                captureResultHasFocusDistance = false;
            }*/
            if (modifiedFromCameraSettings) {
                // see note above
            } else if (result.get(CaptureResult.LENS_FOCUS_DISTANCE) != null) {
                captureResultHasFocusDistance = true
                captureResultFocusDistance =
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)!!
                /*if( MyDebug.LOG ) {
                    Log.d(TAG, "captureResultFocusDistance: " + captureResultFocusDistance);
                    if( captureResultFocusDistance > 0.0f ) {
                        float realFocusDistance = 1.0f / captureResultFocusDistance;
                        Log.d(TAG, "realFocusDistance: " + realFocusDistance);
                    }
                }*/
                // ensure within the valid range for manual focus, just in case
                if (captureResultFocusDistance < 0.0f) captureResultFocusDistance = 0.0f
                else if (captureResultFocusDistance > minimumFocusDistance) captureResultFocusDistance =
                    minimumFocusDistance
            } else {
                captureResultHasFocusDistance = false
            }

            if (modifiedFromCameraSettings) {
                // see note above
            } else if (result.get(CaptureResult.LENS_APERTURE) != null) {
                captureResultHasAperture = true
                captureResultAperture = result.get(CaptureResult.LENS_APERTURE)!!
                /*if( MyDebug.LOG ) {
                    Log.d(TAG, "captureResultAperture: " + captureResultAperture);
                }*/
            } else {
                captureResultHasAperture = false
            }
            run {
                val vector = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                if (modifiedFromCameraSettings) {
                    // see note above
                } else if (vector != null) {
                    captureResultHasWhiteBalanceRggb = true
                    captureResultWhiteBalanceRggb = vector
                }
            }

            /*if( MyDebug.LOG ) {
                RggbChannelVector vector = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
                if( vector != null ) {
                    convertRggbVectorToTemperature(vector); // logging will occur in this function
                }
            }*/
        }

        fun handleFaceDetection(result: CaptureResult) {
            if (faceDetectionListener != null && previewBuilder != null) {
                val faceDetectMode =
                    previewBuilder?.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE)
                if (faceDetectMode != null && faceDetectMode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                    val sensorRect: Rect = this@CameraController2.viewableRect
                    val cameraFaces = result.get(CaptureResult.STATISTICS_FACES)
                    if (cameraFaces != null) {
                        if (cameraFaces.size == 0 && lastFacesDetected == 0) {
                            // no point continually calling the callback if 0 faces detected (same behavior as CameraController1)
                        } else {
                            lastFacesDetected = cameraFaces.size
                            val faces = arrayOfNulls<Face>(cameraFaces.size)
                            for (i in cameraFaces.indices) {
                                faces[i] = convertFromCameraFace(sensorRect, cameraFaces[i])
                            }
                            faceDetectionListener!!.onFaceDetection(faces)
                        }
                    }
                }
            }
        }

        /** Passes the capture result to the RAW onImageAvailableListener, if it exists.
         */
        fun handleRawCaptureResult(result: CaptureResult) {
            //testWaitCaptureResult = true;
            if (testWaitCaptureResult) {
                // For RAW capture, we require the capture result before creating DngCreator
                // but for testing purposes, we need to test the possibility where onImageAvailable() for
                // the RAW image is called before we receive the capture result here.
                // Also with JPEG only capture, there are problems with repeat mode and continuous focus if
                // onImageAvailable() is called before this code is called, because it means here we cancel the
                // focus and lose the focus callback that was going to trigger the next repeat photo! This shows
                // up on testContinuousPictureFocusRepeat() on Nexus 7, but can be autotest on other devices
                // with the flag, see testContinuousPictureFocusRepeatWaitCaptureResult().
                try {
                    if (MyDebug.LOG) Log.d(TAG, "test_wait_capture_result: waiting...")
                    // 200ms is enough to test the problem with testTakePhotoRawWaitCaptureResult() on Nexus 6, but use 500ms to be sure
                    // 200ms is enough to test the problem with testContinuousPictureFocusRepeatWaitCaptureResult() on Nokia 8, but use 500ms to be sure
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }

            if (onRawImageAvailableListener != null) {
                onRawImageAvailableListener!!.setCaptureResult(result)
            }
        }

        /** This should be called when a capture result corresponds to a capture that is for a burst
         * sequence, that isn't the last capture (for the last capture, handleCaptureCompleted() is
         * instead called).
         */
        fun handleCaptureBurstInProgress(result: CaptureResult) {
            if (MyDebug.LOG) Log.d(TAG, "handleCaptureBurstInProgress")

            handleRawCaptureResult(result)
        }

        /** This should be called when a capture result corresponds to a capture that has completed.
         */
        fun handleCaptureCompleted(result: CaptureResult) {
            if (MyDebug.LOG) Log.d(TAG, "capture request completed")
            testCaptureResults++
            modifiedFromCameraSettings = false

            handleRawCaptureResult(result)

            // actual parsing of image data is done in the imageReader's OnImageAvailableListener()
            // need to cancel the autofocus, and restart the preview after taking the photo
            // Camera2Basic does a capture then sets a repeating request - do the same here just to be safe
            // update: this is also important when we do an expo burst (BURSTTYPE_EXPO) with option
            // useExpoFastBurst==false, since that changes the exposure of the preview, so we need to
            // reset it here
            if (previewBuilder != null) {
                previewBuilder?.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
                )
                if (MyDebug.LOG) Log.d(TAG, "### reset ae mode")
                val savedFlashValue: String = cameraSettings.flashValue
                if (useFakePrecaptureMode && fakePrecaptureTorchPerformed) {
                    // same hack as in setFlashValue() - for fake precapture we need to turn off the torch mode that was set, but
                    // at least on Nexus 6, we need to turn to flashOff to turn off the torch!
                    cameraSettings.flashValue = "flash_off"
                }
                // if not using fake precapture, not sure if we need to set the ae mode, but the AE mode is set again in Camera2Basic
                cameraSettings.setAEMode(previewBuilder, false)
                // n.b., if capture/setRepeatingRequest throw exception, we don't call the take_picture_error_cb.onError() callback, as the photo should have been taken by this point
                try {
                    capture()
                } catch (e: CameraAccessException) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "failed to cancel autofocus after taking photo")
                        Log.e(TAG, "reason: " + e.reason)
                        Log.e(TAG, "message: " + e.message)
                    }
                    e.printStackTrace()
                }
                if (useFakePrecaptureMode && fakePrecaptureTorchPerformed) {
                    // now set up the request to switch to the correct flash value
                    cameraSettings.flashValue = savedFlashValue
                    cameraSettings.setAEMode(previewBuilder, false)
                }
                previewBuilder?.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE
                ) // ensure set back to idle
                try {
                    setRepeatingRequest()
                } catch (e: CameraAccessException) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "failed to start preview after taking photo")
                        Log.e(TAG, "reason: " + e.reason)
                        Log.e(TAG, "message: " + e.message)
                    }
                    e.printStackTrace()
                    previewErrorCb.onError()
                }
            }
            fakePrecaptureTorchPerformed = false

            if (burstType === BurstType.BURSTTYPE_FOCUS && previewBuilder != null) { // make sure camera wasn't released in the meantime
                if (MyDebug.LOG) Log.d(TAG, "focus bracketing complete, reset manual focus")
                previewBuilder?.let {
                    cameraSettings.setFocusMode(it) // needed if the preview was running in a non-manual mode
                    cameraSettings.setFocusDistance(it)
                }
                try {
                    setRepeatingRequest()
                } catch (e: CameraAccessException) {
                    if (MyDebug.LOG) {
                        Log.e(TAG, "failed to set focus distance")
                        Log.e(TAG, "reason: " + e.reason)
                        Log.e(TAG, "message: " + e.message)
                    }
                    e.printStackTrace()
                }
            }

            callCheckImagesCompleted()
        }

        fun callCheckImagesCompleted() {
            // Important that we only call the picture onCompleted callback after we've received the capture request, so
            // we need to check if we already received all the images.
            // Also needs to be run on UI thread.
            // Needed for testContinuousPictureFocusRepeat on Nexus 7; also testable on other devices via
            // testContinuousPictureFocusRepeatWaitCaptureResult.
            val activity = context as Activity
            activity.runOnUiThread {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "processCompleted UI thread call checkImagesCompleted()"
                )
                synchronized(backgroundCameraLock) {
                    doneAllCaptures = true
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "done all captures"
                    )
                }
                checkImagesCompleted()
            }
        }

        /** Processes a total result.
         */
        fun processCompleted(request: CaptureRequest, result: CaptureResult) {
            /*if( MyDebug.LOG )
                Log.d(TAG, "processCompleted");*/

            if (!hasReceivedFrame) {
                hasReceivedFrame = true
                if (MyDebug.LOG) Log.d(TAG, "has_received_frame now set to true")
            }

            updateCachedCaptureResult(result)
            handleFaceDetection(result)

            if (pushRepeatingRequestWhenTorchOff && pushRepeatingRequestWhenTorchOffId === request && previewBuilder != null) {
                if (MyDebug.LOG) Log.d(TAG, "received push_repeating_request_when_torch_off")
                val flashState = result.get(CaptureResult.FLASH_STATE)
                if (MyDebug.LOG) {
                    if (flashState != null) Log.d(
                        TAG,
                        "flash_state: $flashState"
                    )
                    else Log.d(TAG, "flash_state is null")
                }
                if (flashState != null && flashState == CaptureResult.FLASH_STATE_READY) {
                    pushRepeatingRequestWhenTorchOff = false
                    pushRepeatingRequestWhenTorchOffId = null
                    try {
                        setRepeatingRequest()
                    } catch (e: CameraAccessException) {
                        if (MyDebug.LOG) {
                            Log.e(TAG, "failed to set flash [from torch/flash off hack]")
                            Log.e(TAG, "reason: " + e.reason)
                            Log.e(TAG, "message: " + e.message)
                        }
                        e.printStackTrace()
                    }
                }
            }

            /*if( pushSetAeLock && pushSetAeLockId == request && previewBuilder != null ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "received pushSetAeLock");
                // hack - needed to fix bug on Nexus 6 where auto-exposure sometimes locks when taking a photo of bright scene with flash on!
                // this doesn't completely resolve the issue, but seems to make it far less common; also when it does happen, taking another photo usually fixes it
                pushSetAeLock = false;
                pushSetAeLockId = null;
                camera_settings.setAutoExposureLock(previewBuilder);
                try {
                    setRepeatingRequest();
                }
                catch(CameraAccessException e) {
                    if( MyDebug.LOG ) {
                        Log.e(TAG, "failed to set ae lock [from ae lock hack]");
                        Log.e(TAG, "reason: " + e.getReason());
                        Log.e(TAG, "message: " + e.getMessage());
                    }
                    e.printStackTrace();
                }
            }*/
            val tagType: RequestTagType? =
                getRequestTagType(request)
            if (tagType == RequestTagType.CAPTURE) {
                handleCaptureCompleted(result)
            } else if (tagType == RequestTagType.CAPTURE_BURST_IN_PROGRESS) {
                handleCaptureBurstInProgress(result)
            } else if (tagType == RequestTagType.RUN_POST_CAPTURE) {
                if (this@CameraController2.runPostCapture != null) {
                    if (MyDebug.LOG) Log.d(TAG, "take picture after delay for long manual exposure")
                    if (camera != null && hasCaptureSession()) { // make sure camera wasn't released in the meantime
                        // need to wait a further ~500ms for Galaxy S10e at least (although on Galaxy S24+, it's fine if we don't do via a postDelayed at all)
                        handler?.postDelayed({
                            try {
                                runPostCapture!!.call()
                                runPostCapture = null
                                // now put preview back to normal
                                if (cameraSettings.setAEMode(previewBuilder, false)) {
                                    setRepeatingRequest()
                                }
                            } catch (e: CameraAccessException) {
                                if (MyDebug.LOG) {
                                    Log.e(
                                        TAG,
                                        "failed to take picture after delay for long manual exposure"
                                    )
                                    Log.e(TAG, "reason: " + e.reason)
                                    Log.e(TAG, "message: " + e.message)
                                }
                                e.printStackTrace()
                                jpegTodo = false
                                rawTodo = false
                                pictureCb = null
                                if (takePictureErrorCb != null) {
                                    takePictureErrorCb?.onError()
                                    takePictureErrorCb = null
                                }
                            }
                        }, 500)

                        /*try {
                            run_post_capture.call();
                            runPostCapture = null;
                            // now put preview back to normal
                            if( camera_settings.setAEMode(previewBuilder, false) ) {
                                setRepeatingRequest();
                            }
                        }
                        catch(CameraAccessException e) {
                            if( MyDebug.LOG ) {
                                Log.e(TAG, "failed to take picture after delay for long manual exposure");
                                Log.e(TAG, "reason: " + e.getReason());
                                Log.e(TAG, "message: " + e.getMessage());
                            }
                            e.printStackTrace();
                            jpegTodo = false;
                            rawTodo = false;
                            pictureCb = null;
                            if( takePictureErrorCb != null ) {
                                take_picture_error_cb.onError();
                                takePictureErrorCb = null;
                            }
                        }*/
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "CameraController2"

        const val TONEMAP_LOG_MAX_CURVE_POINTS_C = 64
        private val jtvideoValuesBase = floatArrayOf(
            0.00f, 0.00f,
            0.01f, 0.055f,
            0.02f, 0.1f,
            0.05f, 0.21f,
            0.09f, 0.31f,
            0.13f, 0.38f,
            0.18f, 0.45f,
            0.28f, 0.57f,
            0.35f, 0.64f,
            0.45f, 0.72f,
            0.51f, 0.76f,
            0.60f, 0.82f,
            0.67f, 0.86f,
            0.77f, 0.91f,
            0.88f, 0.96f,
            0.97f, 0.99f,
            1.00f, 1.00f
        )
        private val jtlogValuesBase = floatArrayOf(
            0.00f, 0.00f,
            0.01f, 0.07f,
            0.03f, 0.17f,
            0.05f, 0.25f,
            0.07f, 0.31f,
            0.09f, 0.36f,
            0.13f, 0.44f,
            0.18f, 0.51f,
            0.24f, 0.57f,
            0.31f, 0.64f,
            0.38f, 0.70f,
            0.46f, 0.76f,
            0.58f, 0.83f,
            0.70f, 0.89f,
            0.86f, 0.95f,
            0.99f, 0.99f,
            1.00f, 1.00f
        )
        private val jtlog2ValuesBase = floatArrayOf(
            0.00f, 0.00f,
            0.01f, 0.09f,
            0.03f, 0.23f,
            0.07f, 0.37f,
            0.12f, 0.48f,
            0.17f, 0.56f,
            0.25f, 0.64f,
            0.32f, 0.70f,
            0.39f, 0.75f,
            0.50f, 0.81f,
            0.59f, 0.85f,
            0.66f, 0.88f,
            0.72f, 0.9f,
            0.78f, 0.92f,
            0.88f, 0.95f,
            0.92f, 0.96f,
            0.99f, 0.98f,
            1.00f, 1.00f
        )

        // timeout for calling autofocusCb (applies for both auto and continuous focus)
        private const val AUTOFOCUS_TIMEOUT_C: Long = 1000

        // for BURSTTYPE_EXPO:
        // could be more, but limit to 5 for now
        private const val MAX_EXPO_BRACKETING_N_IMAGES = 5
        private const val STATE_NORMAL = 0
        private const val STATE_WAITING_AUTOFOCUS = 1
        private const val STATE_WAITING_PRECAPTURE_START = 2
        private const val STATE_WAITING_PRECAPTURE_DONE = 3
        private const val STATE_WAITING_FAKE_PRECAPTURE_START = 4
        private const val STATE_WAITING_FAKE_PRECAPTURE_DONE = 5
        private const val PRECAPTURE_START_TIMEOUT_C: Long = 2000
        private const val PRECAPTURE_DONE_TIMEOUT_C: Long = 3000

        /*private boolean captureResultHasFocusDistance;
    private float captureResultFocusDistanceMin;
    private float captureResultFocusDistanceMax;*/
        /** Even if using long exposure, we want to set a maximum for the preview to avoid very low
         * frame rates.
         * Originally this was 1/12s, but I think we can get away with 1/5s - for this range, having
         * a WYSIWYG preview is probably still better than the reduced framerate. Also as a side-benefit,
         * it reduces the impact of the Samsung Galaxy Android 11 bug where manual exposure is ignored if
         * different to the preview.
         */
        const val MAX_PREVIEW_EXPOSURE_TIME_C = 1000000000L / 5

        private const val MIN_WHITE_BALANCE_TEMPERATURE_C = 1000
        private const val MAX_WHITE_BALANCE_TEMPERATURE_C = 15000

        fun convertTemperatureToRggbVector(temperatureKelvin: Int): RggbChannelVector {
            val rggb = convertTemperatureToRggb(temperatureKelvin)
            return RggbChannelVector(rggb[0], rggb[1], rggb[2], rggb[3])
        }

        /** Converts a white balance temperature to red, green even, green odd and blue components.
         */
        fun convertTemperatureToRggb(temperatureKelvin: Int): FloatArray {
            val temperature = temperatureKelvin / 100.0f
            var red: Float
            var green: Float
            var blue: Float

            if (temperature <= 66) {
                red = 255f
            } else {
                red = temperature - 60
                red = (329.698727446 * (red.toDouble().pow(-0.1332047592))).toFloat()
                if (red < 0) red = 0f
                if (red > 255) red = 255f
            }

            if (temperature <= 66) {
                green = temperature
                green = (99.4708025861 * ln(green.toDouble()) - 161.1195681661).toFloat()
                if (green < 0) green = 0f
                if (green > 255) green = 255f
            } else {
                green = temperature - 60
                green = (288.1221695283 * (green.toDouble().pow(-0.0755148492))).toFloat()
                if (green < 0) green = 0f
                if (green > 255) green = 255f
            }

            if (temperature >= 66) blue = 255f
            else if (temperature <= 19) blue = 0f
            else {
                blue = temperature - 10
                blue = (138.5177312231 * ln(blue.toDouble()) - 305.0447927307).toFloat()
                if (blue < 0) blue = 0f
                if (blue > 255) blue = 255f
            }

            if (MyDebug.LOG) {
                Log.d(TAG, "red: $red")
                Log.d(TAG, "green: $green")
                Log.d(TAG, "blue: $blue")
            }

            red = (red / 255.0f)
            green = (green / 255.0f)
            blue = (blue / 255.0f)

            red = convertRGBtoGain(red)
            green = convertRGBtoGain(green)
            blue = convertRGBtoGain(blue)
            if (MyDebug.LOG) {
                Log.d(TAG, "red gain: $red")
                Log.d(TAG, "green gain: $green")
                Log.d(TAG, "blue gain: $blue")
            }

            return floatArrayOf(red, green / 2, green / 2, blue)
        }

        private fun convertRGBtoGain(value: Float): Float {
            var value = value
            val maxGainC = 10.0f
            if (value < 1.0e-5f) {
                return maxGainC
            }
            value = 1.0f / value
            value = min(maxGainC.toDouble(), value.toDouble()).toFloat()
            return value
        }

        fun convertRggbVectorToTemperature(rggbChannelVector: RggbChannelVector): Int {
            return convertRggbToTemperature(
                floatArrayOf(
                    rggbChannelVector.red,
                    rggbChannelVector.greenEven,
                    rggbChannelVector.greenOdd,
                    rggbChannelVector.blue
                )
            )
        }

        /** Converts a red, green even, green odd and blue components to a white balance temperature.
         * Note that this is not necessarily an inverse of convertTemperatureToRggb, since many rggb
         * values can map to the same temperature.
         */
        fun convertRggbToTemperature(rggb: FloatArray): Int {
            if (MyDebug.LOG) {
                Log.d(TAG, "temperature:")
                Log.d(TAG, "    red: " + rggb[0])
                Log.d(TAG, "    green even: " + rggb[1])
                Log.d(TAG, "    green odd: " + rggb[2])
                Log.d(TAG, "    blue: " + rggb[3])
            }
            var red = rggb[0]
            val greenEven = rggb[1]
            val greenOdd = rggb[2]
            var blue = rggb[3]
            var green = (greenEven + greenOdd)

            red = convertGaintoRGB(red)
            green = convertGaintoRGB(green)
            blue = convertGaintoRGB(blue)

            red *= 255.0f
            green *= 255.0f
            blue *= 255.0f

            val redI = (red + 0.5f).toInt()
            val greenI = (green + 0.5f).toInt()
            val blueI = (blue + 0.5f).toInt()
            var temperature: Int
            if (redI == blueI) {
                temperature = 6600
            } else if (redI > blueI) {
                // temperature <= 6600
                val tG = (100 * exp((green + 161.1195681661) / 99.4708025861)).toFloat()
                if (blueI == 0) {
                    temperature = (tG + 0.5f).toInt()
                } else {
                    val tB = (100 * (exp((blue + 305.0447927307) / 138.5177312231) + 10)).toFloat()
                    temperature = ((tG + tB) / 2 + 0.5f).toInt()
                }
            } else {
                // temperature >= 6600
                if (redI <= 1 || greenI <= 1) {
                    temperature = MAX_WHITE_BALANCE_TEMPERATURE_C
                } else {
                    val tR =
                        (100 * ((red / 329.698727446).pow(1.0 / -0.1332047592) + 60.0)).toFloat()
                    val tG =
                        (100 * ((green / 288.1221695283).pow(1.0 / -0.0755148492) + 60.0)).toFloat()
                    temperature = ((tR + tG) / 2 + 0.5f).toInt()
                }
            }
            temperature =
                max(temperature.toDouble(), MIN_WHITE_BALANCE_TEMPERATURE_C.toDouble()).toInt()
            temperature =
                min(temperature.toDouble(), MAX_WHITE_BALANCE_TEMPERATURE_C.toDouble()).toInt()
            if (MyDebug.LOG) {
                Log.d(TAG, "    temperature: $temperature")
            }
            return temperature
        }

        private fun convertGaintoRGB(value: Float): Float {
            var value = value
            if (value <= 1.0f) {
                return 1.0f
            }
            value = 1.0f / value
            return value
        }

        /** Computes the zoom ratios to use, for devices that support zoom.
         * @param ratios   List to be filled with zoom ratios.
         * @param minZoom Minimum zoom supported.
         * @param maxZoom Maximum zoom supported.
         * @return         Index of ratios list that is for 1x zoom.
         */
        fun computeZoomRatios(ratios: MutableList<Int>, minZoom: Float, maxZoom: Float): Int {
            val zoomValue1x: Int

            // prepare zoom rations > 1x
            // set 40 steps per 2x factor
            val scaleFactorC = 1.0174796921026863936352862847966
            val zoomRatiosAboveOne: MutableList<Int> = ArrayList()
            var zoom = scaleFactorC
            while (zoom < maxZoom - 1.0e-5f) {
                val zoomRatio = (zoom * 100 + 1.0e-5).toInt()
                zoomRatiosAboveOne.add(zoomRatio)
                zoom *= scaleFactorC
            }
            val maxZoomRatio = (maxZoom * 100).toInt()
            if (zoomRatiosAboveOne.isEmpty() || zoomRatiosAboveOne[zoomRatiosAboveOne.size - 1] != maxZoomRatio) {
                zoomRatiosAboveOne.add(maxZoomRatio)
            }
            val nStepsAboveOne = zoomRatiosAboveOne.size
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "n_steps_above_one: $nStepsAboveOne"
                )
            }

            // now populate full zoom ratios

            // add minimum zoom
            ratios.add((minZoom * 100).toInt())
            if (ratios[0] / 100.0f < minZoom) {
                // fix for rounding down to less than the minZoom
                // e.g. if minZoom = 0.666, we'd have stored a zoom ratio of 66 which then would
                // convert back to 0.66
                ratios[0] = ratios[0] + 1
            }

            if (ratios[0] < 100) {
                val nStepsBelowOne = max(1.0, (nStepsAboveOne / 5).toDouble()).toInt()
                // if the min zoom is < 1.0, we add multiple entries for 1x zoom, when using the zoom
                // seekbar it's easy for the user to zoom to exactly 1x
                val nStepsOne = max(1.0, (nStepsAboveOne / 10).toDouble()).toInt()
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "n_steps_below_one: $nStepsBelowOne"
                    )
                    Log.d(TAG, "n_steps_one: $nStepsOne")
                }

                // add rest of zoom values < 1.0f
                zoom = minZoom.toDouble()
                val scaleFactor =
                    (1.0f / minZoom).toDouble().pow(1.0 / nStepsBelowOne.toDouble())
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "scale_factor for below 1.0x: $scaleFactor"
                    )
                }
                for (i in 0..<nStepsBelowOne - 1) {
                    zoom *= scaleFactor
                    val zoomRatio = (zoom * 100).toInt()
                    if (zoomRatio > ratios[0]) {
                        // on some devices (e.g., Pixel 6 Pro), the second entry would equal the first entry, due to the rounding fix above
                        ratios.add(zoomRatio)
                    }
                }

                // add values for 1.0f (we add repeated values so for cameras with minZoom < 1x, the zoom seekbar will snap to 1x)
                zoomValue1x = ratios.size
                for (i in 0..<nStepsOne) ratios.add(100)
            } else {
                zoomValue1x = 0
            }

            // add zoom values > 1.0f
            val nStepsPowerTwo =
                max(1.0, (0.5f + nStepsAboveOne / 15.0f).toInt().toDouble()).toInt()
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "n_steps_power_two: $nStepsPowerTwo"
                )
            }
            for (zoomRatio in zoomRatiosAboveOne) {
                ratios.add(zoomRatio)

                if (zoomRatio != zoomRatiosAboveOne[zoomRatiosAboveOne.size - 1] && zoomRatio % 100 == 0) {
                    val zoomRatioInt = zoomRatio / 100
                    if (zoomRatioInt != 0 && (zoomRatioInt and (zoomRatioInt - 1)) == 0) {
                        // is power of 2 that isn't the max zoom
                        for (i in 0..<nStepsPowerTwo - 1) ratios.add(zoomRatio)
                    }
                }
            }

            return zoomValue1x
        }

        /** Returns true iff every entry in cameraSizes is also a member of altCameraSizes (order
         * doesn't matter).
         */
        fun sizeSubset(
            cameraWidths: IntArray?,
            cameraHeights: IntArray?,
            altCameraWidths: IntArray?,
            altCameraHeights: IntArray?
        ): Boolean {
            if (cameraWidths == null && cameraHeights == null) return true
            if (altCameraWidths == null && altCameraHeights == null) return false
            for (i in cameraWidths!!.indices) {
                var found = false
                for (j in altCameraWidths!!.indices) {
                    if (cameraWidths[i] == altCameraWidths[j] && cameraHeights!![i] == altCameraHeights!![j]) {
                        found = true
                        break
                    }
                }
                if (!found) return false
            }
            return true
        }

        private fun sizeSubset(
            cameraSizes: Array<android.util.Size>?,
            altCameraSizes: Array<android.util.Size>?
        ): Boolean {
            var cameraWidths: IntArray? = null
            var cameraHeights: IntArray? = null
            var altCameraWidths: IntArray? = null
            var altCameraHeights: IntArray? = null
            if (cameraSizes != null) {
                cameraWidths = IntArray(cameraSizes.size)
                cameraHeights = IntArray(cameraSizes.size)
                for (i in cameraSizes.indices) {
                    cameraWidths[i] = cameraSizes[i].width
                    cameraHeights[i] = cameraSizes[i].height
                }
            }
            if (altCameraSizes != null) {
                altCameraWidths = IntArray(altCameraSizes.size)
                altCameraHeights = IntArray(altCameraSizes.size)
                for (i in altCameraSizes.indices) {
                    altCameraWidths[i] = altCameraSizes[i].width
                    altCameraHeights[i] = altCameraSizes[i].height
                }
            }
            return sizeSubset(cameraWidths, cameraHeights, altCameraWidths, altCameraHeights)
        }

        /* If doAfTriggerForContinuous is false, doing an autoFocus() in continuous focus mode just
       means we call the autofocus callback the moment focus is not scanning (as with old Camera API).
       If doAfTriggerForContinuous is true, we set CONTROL_AF_TRIGGER_START, and wait for
       CONTROL_AF_STATE_FOCUSED_LOCKED or CONTROL_AF_STATE_NOT_FOCUSED_LOCKED, similar to other focus
       methods.
       doAfTriggerForContinuous==true used to have advantages:
         - On Nexus 6 for flash auto, it means ae state is set to FLASH_REQUIRED if it is required
           when it comes to taking the photo. If doAfTriggerForContinuous==false, sometimes
           it's set to CONTROL_AE_STATE_CONVERGED even for dark scenes, so we think we can skip
           the precapture, causing photos to come out dark (or we can force always doing precapture,
           but that makes things slower when flash isn't needed)
           Update: this now seems hard to reproduce.
         - On OnePlus 3T, with doAfTriggerForContinuous==false photos come out with blue tinge
           if the scene is not dark (but still dark enough that you'd want flash).
           doAfTriggerForContinuous==true fixes this for cases where the flash fires for autofocus.
           Note that the problem is still not fixed for flash on where the scene is bright enough to
           not need flash (and so we don't fire flash for autofocus).
           Update: now fixed by setting TEMPLATE_PREVIEW for the precaptureBuilder.
       doAfTriggerForContinuous==true has disadvantage:
         - On both Nexus 6 and OnePlus 3T, taking photos with flash is longer, as we have flash firing
           for autofocus and precapture. Though note this is the case with autofocus mode anyway.
       Note for fake flash mode, we still can use doAfTriggerForContinuous==false (and doing the
       af trigger for fake flash mode can sometimes mean flash fires for too long, and we get a worse
       result).
     */
        private const val DO_AF_TRIGGER_FOR_CONTINUOUS = false

        fun setupFocusBracketingDistances(
            source: Float,
            target: Float,
            count: Int
        ): MutableList<Float> {
            return FocusBracketingCalculator.setupFocusBracketingDistances(source, target, count)
        }
    }
}