/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.hardware.camera2.CameraExtensionCharacteristics
import android.location.Location
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.net.Uri
import android.os.AsyncTask
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RSInvalidStateException
import android.renderscript.RenderScript
import android.renderscript.Type
import android.util.Log
import android.util.Pair
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.hightechif.openkamera.R
import com.hightechif.openkamera.ScriptC_histogram_compute
import com.hightechif.openkamera.TakePhoto
import com.hightechif.openkamera.preview.analysis.FrameAnalysisConfig
import com.hightechif.openkamera.preview.analysis.FrameAnalysisResult
import com.hightechif.openkamera.preview.analysis.HistogramType
import com.hightechif.openkamera.preview.analysis.PreShotsRingBuffer
import com.hightechif.openkamera.preview.analysis.PreviewFrameAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.cameracontroller.CameraController.CameraFeatures
import com.hightechif.openkamera.cameracontroller.CameraController.CameraFeaturesCache
import com.hightechif.openkamera.cameracontroller.CameraController.Facing
import com.hightechif.openkamera.cameracontroller.CameraController.SupportedValues
import com.hightechif.openkamera.cameracontroller.CameraController.TonemapProfile
import com.hightechif.openkamera.cameracontroller.CameraController1
import com.hightechif.openkamera.cameracontroller.CameraController2
import com.hightechif.openkamera.cameracontroller.CameraControllerException
import com.hightechif.openkamera.cameracontroller.CameraControllerManager
import com.hightechif.openkamera.cameracontroller.CameraControllerManager1
import com.hightechif.openkamera.cameracontroller.CameraControllerManager2
import com.hightechif.openkamera.cameracontroller.RawImage
import com.hightechif.openkamera.preview.ApplicationInterface.CameraResolutionConstraints
import com.hightechif.openkamera.preview.ApplicationInterface.NoFreeStorageException
import com.hightechif.openkamera.preview.camerasurface.CameraSurface
import com.hightechif.openkamera.preview.camerasurface.MySurfaceView
import com.hightechif.openkamera.preview.camerasurface.MyTextureView
import com.hightechif.openkamera.preview.geometry.PreviewMatrixCalculator
import com.hightechif.openkamera.preview.geometry.ViewportDimensions
import com.hightechif.openkamera.preview.geometry.ViewportTransformHelper
import com.hightechif.openkamera.preview.gesture.PreviewTouchCallback
import com.hightechif.openkamera.preview.gesture.PreviewTouchGestureCoordinator
import com.hightechif.openkamera.processing.HDRProcessor
import com.hightechif.openkamera.processing.JavaImageFunctionsHDR
import com.hightechif.openkamera.processing.JavaImageFunctionsPreview
import com.hightechif.openkamera.processing.JavaImageProcessing
import com.hightechif.openkamera.utils.MyDebug
import com.hightechif.openkamera.utils.ToastBoxer
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.DecimalFormat
import java.util.Date
import java.util.Hashtable
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/** This class was originally named due to encapsulating the camera preview,
 * but in practice it's grown to more than this, and includes most of the
 * operation of the camera. It exists at a higher level than CameraController
 * (i.e., this isn't merely a low level wrapper to the camera API, but
 * supports much of the Open Kamera logic and functionality). Communication to
 * the rest of the application is available through ApplicationInterface.
 * We could probably do with decoupling this class into separate components!
 *
 * This class also keeps track of various camera parameters, obtained from the
 * CameraController class. One decision is when certain parameters depend on
 * others (e.g., some resolutions don't support burst; lots of things don't
 * support vendor camera extensions). In general, we shouldn't do that restriction
 * at this level, as that can cause problems since at the Application level we
 * may need to know what features are possible in any mode. E.g., if we said
 * burst mode isn't supported because we're in a camera extension mode, the user
 * wouldn't be able to switch to Fast Burst mode because the application thinks
 * burst isn't available! And also for changing preferences in Settings, we
 * typically want to show all available settings (e.g., showing RAW if it's
 * available for the current camera, even if not available in the current mode).
 * There are some exceptions where we need to restrict at the Preview level, e.g.:
 * - Resolutions (for burst mode, camera extensions) - though the application
 * can choose to obtain the full list by calling getSupportedPictureSizes()
 * with checkSupported==false.
 * - Flash modes (for manual ISO or camera extensions).
 * - Focus modes (for camera extensions).
 * Similarly, we shouldn't restrict available features at the CameraController
 * class, except where this is unavoidable due to the Android camera API
 * behavior (e.g., for scene modes, it may be that some camera features are
 * affected).
 */
class Preview(applicationInterface: ApplicationInterface, parent: ViewGroup) :
    SurfaceHolder.Callback,
    SurfaceTextureListener {
    private val usingAndroidL: Boolean

    private val applicationInterface: ApplicationInterface
    private var cameraSurface: CameraSurface
    private var canvasView: CanvasView? = null
    private var setPreviewSize = false
    private var previewW = 0
    private var previewH = 0
    private var setTextureViewSize = false
    private var textureViewW = 0
    private var textureViewH = 0

    private var rs: RenderScript? =
        null // lazily created, so we don't take up resources if application isn't using renderscript
    private var histogramScript: ScriptC_histogram_compute? = null // lazily create for performance
    var isPreviewBitmapEnabled: Boolean =
        false // whether application has requested we generate bitmap for the preview
        private set
    private var usePreviewBitmapSmall = false
    private var usePreviewBitmapFull =
        false // whether we want downsized and/or full preview bitmaps
    private var previewBitmap: Bitmap? = null // downsided bitmap from preview
    private var previewBitmapFullW = -1
    private var previewBitmapFullH =
        -1 // for full bitmaps, we generate copies on the fly (as these need to be saved for preshots feature)
    private var lastPreviewBitmapTimeMs: Long = 0 // time the last previewBitmap was updated
    private val frameAnalyzer: PreviewFrameAnalyzer by lazy { PreviewFrameAnalyzer(context) }
    private var isAnalyzingFrame = false
    private var analysisJob: Job? = null

    private var wantHistogram =
        false // whether to generate a histogram, requires wantPreviewBitmap==true and usePreviewBitmapSmall==true

    private var histogramType = HistogramType.HISTOGRAM_TYPE_VALUE
    var histogram: IntArray? = null
        private set
    private var lastHistogramTimeMs: Long = 0 // time the last histogram was updated

    private var wantZebraStripes =
        false // whether to generate zebra stripes bitmap, requires wantPreviewBitmap==true and usePreviewBitmapSmall==true
    private var zebraStripesThreshold =
        0 // pixels with max rgb value equal to or greater than this threshold are marked with zebra stripes
    private var zebraStripesColorForeground = 0
    private var zebraStripesColorBackground = 0
    private var zebraStripesBitmapBuffer: Bitmap? = null
    var zebraStripesBitmap: Bitmap? = null
        private set

    private var wantFocusPeaking =
        false // whether to generate focus peaking bitmap, requires wantPreviewBitmap==true and usePreviewBitmapSmall==true
    private var focusPeakingBitmapBuffer: Bitmap? = null
    private var focusPeakingBitmapBufferTemp: Bitmap? = null
    var focusPeakingBitmap: Bitmap? = null
        private set

    private var wantPreShots =
        false // whether to store pre-shots from preview bitmap, requires wantPreviewBitmap==true and usePreviewBitmapFull==true

    private val _cameraToPreviewMatrix = Matrix()
    private val _previewToCameraMatrix = Matrix()
    var targetRatio: Double = 0.0
        private set

    //private boolean uiPlacementRight = true;
    private var appIsPaused = true // whether activity is paused
    private var isPaused =
        true // whether Preview.onPause() is called - note this could include the application pausing the preview, even if appIsPaused==false
    private var hasSurface = false
    private var hasAspectRatio = false
    var cameraController: CameraController? = null
    var cameraControllerManager: CameraControllerManager

    // cache for CameraController2
    private val cameraFeaturesCaches: Map<String, CameraFeaturesCache> = Hashtable()

    internal enum class CameraOpenState {
        CAMERAOPENSTATE_CLOSED,  // have yet to attempt to open the camera (either at all, or since the camera was closed)
        CAMERAOPENSTATE_OPENING,  // the camera is currently being opened (on a background thread)
        CAMERAOPENSTATE_OPENED,  // either the camera is open (if cameraController!=null) or we failed to open the camera (if cameraController==null)
        CAMERAOPENSTATE_CLOSING // the camera is currently being closed (on a background thread)
    }

    private var cameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSED

    // background task used for opening camera
    private var openCameraTask: AsyncTask<Void?, Void?, CameraController?>? = null

    // background task used for closing camera
    private var closeCameraTask: CloseCameraTask? = null

    // whether we have permissions necessary to operate the camera (camera, storage); assume true until we've been denied one of them
    private var hasPermissions = true

    /** Whether we are in video mode, or photo mode.
     */
    var isVideo: Boolean = false
        private set

    @Volatile
    private var videoRecorder: MediaRecorder? =
        null // must be volatile for test project reading the state

    @Volatile
    private var videoStartTimeSet = false // must be volatile for test project reading the state
    private var videoStartTime: Long =
        0 // system time when the video recording was started, or last resumed if it was paused
    var videoAccumulatedTime: Long =
        0 // this time should be added to (System.currentTimeMillis() - videoStartTime) to find the true video duration, that takes into account pausing/resuming, as well as any auto-restarts from max filesize
        private set
    private var videoTimeLastMaxfilesizeRestart: Long =
        0 // when the video last restarted due to maxfilesize (or otherwise 0) - note this is time in ms relative to the recorded video, and not system time
    private var videoRecorderIsPaused = false // whether videoRecorder is running but has paused
    private var videoRestartOnMaxFilesize = false

    /** Stores the file (or similar) to record a video.
     * Important to call close() when the video recording is finished, to free up any resources
     * (e.g., supplied ParcelFileDescriptor).
     */
    private data class VideoFileInfo(
        val videoMethod: ApplicationInterface.VideoMethod = ApplicationInterface.VideoMethod.FILE,
        val videoUri: Uri? = null,
        val videoFilename: String? = null,
        val videoPfdSaf: ParcelFileDescriptor? = null
    ) {
        fun close() {
            if (this.videoPfdSaf != null) {
                try {
                    videoPfdSaf.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private var videoFileInfo = VideoFileInfo()

    // used for Android 8+ to handle seamless restart (see MediaRecorder.setNextOutputFile())
    private var nextVideoFileInfo: VideoFileInfo? = null

    @Volatile
    private var phase = PHASE_NORMAL // must be volatile for test project reading the state
    private val takePictureTimer = Timer()
    private var takePictureTimerTask: TimerTask? = null
    private val beepTimer = Timer()
    private var beepTimerTask: TimerTask? = null
    private val flashVideoTimer = Timer()
    private var flashVideoTimerTask: TimerTask? = null
    private val batteryIfilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    private val batteryCheckVideoTimer = Timer()
    private var batteryCheckVideoTimerTask: TimerTask? = null
    var timerEndTime: Long = 0
        private set
    private var remainingRepeatPhotos = 0
    private var remainingRestartVideo = 0

    private var previewStartedState = PREVIEW_NOT_STARTED

    val isPreviewStarted: Boolean
        get() = previewStartedState == PREVIEW_STARTED

    fun isPreviewStarting(): Boolean {
        return previewStartedState == PREVIEW_IS_STARTING
    }

    private var orientationEventListener: OrientationEventListener? = null
    private var currentOrientation = 0 // orientation received by onOrientationChanged
    private var currentRotation =
        0 // orientation relative to camera's orientation (used for parameters.setRotation())
    private var hasLevelAngle = false
    private var naturalLevelAngle =
        0.0 // "level" angle of device in degrees, before applying any calibration and without accounting for screen orientation

    /** Returns the level angle in degrees.
     */
    var levelAngle: Double = 0.0 // "level" angle of device in degrees, including calibration
        private set

    /** Returns the original level angle in degrees.
     */
    var origLevelAngle: Double =
        0.0 // "level" angle of device in degrees, including calibration, but without accounting for screen orientation
        private set
    private var hasPitchAngle = false

    /** Returns the pitch angle in degrees.
     */
    var pitchAngle: Double = 0.0 // pitch angle of device in degrees
        private set

    // if applicationInterface.allowZoom() returns false, then hasZoom will be false, but cameraControllerSupportsZoom
    // supports whether the camera controller supported zoom
    private var cameraControllerSupportsZoom = false
    private var cameraControllerMaxZoomFactor = 0
    private var cameraControllerZoomRatios: List<Int>? = null
    private var hasZoom = false

    private fun initZoom() {
        this.hasZoom = cameraControllerSupportsZoom && applicationInterface.allowZoom()
        if (this.hasZoom) {
            this.maxZoom = cameraControllerMaxZoomFactor
            this.zoomRatios = cameraControllerZoomRatios
        } else {
            this.maxZoom = 0
            this.zoomRatios = null
        }
    }

    fun setZoomSticky(sticky: Boolean) {
        if (!usingAndroidL) {
            // making zoom sticking or not only supported for Camera2 API
            return
        }
        if (cameraController == null) {
            // just in case - have seen rare NullPointerException crashes from Google Play
            return
        }
        this.cameraControllerZoomRatios = this.cameraController!!.setZoomSticky(sticky)
        val ratios = this.cameraControllerZoomRatios
        this.cameraControllerMaxZoomFactor = if (ratios != null) ratios.size - 1 else 0
        if (this.hasZoom) {
            this.maxZoom = cameraControllerMaxZoomFactor
            this.zoomRatios = cameraControllerZoomRatios
        } else {
            this.maxZoom = 0
            this.zoomRatios = null
        }
    }

    var maxZoom: Int = 0
        private set
    private var zoomRatios: List<Int>? = null
    var minimumFocusDistance: Float = 0f
        private set

    var supportedFlashValues: List<String>? = null // our "values" format
        private set
    private var currentFlashIndex =
        -1 // this is an index into the supportedFlashValues array, or -1 if no flash modes available

    var supportedFocusValues: List<String>? = null // our "values" format
        private set
    private var currentFocusIndex =
        -1 // this is an index into the supportedFocusValues array, or -1 if no focus modes available
    var maxNumFocusAreas: Int = 0
        private set
    private var continuousFocusMoveIsStarted = false

    private var isExposureLockSupported = false
    var isExposureLocked: Boolean = false
        private set

    private var isWhiteBalanceLockSupported = false
    var isWhiteBalanceLocked: Boolean = false
        private set

    private var colorEffects: List<String> = emptyList()
    private var sceneModes: List<String> = emptyList()
    private var whiteBalances: List<String> = emptyList()
    private var antibanding: List<String>? = null
    private var edgeModes: List<String>? = null
    private var noiseReductionModes: List<String>? =
        null // n.b., this is for the Camera2 API setting, not for Open Kamera's Noise Reduction photo mode
    private var isos: List<String>? = null
    private var supportsWhiteBalanceTemperature = false
    private var minTemperature = 0
    private var maxTemperature = 0
    private var supportsIsoRange = false
    private var minIso = 0
    private var maxIso = 0
    private var supportsExposureTime = false
    private var minExposureTime: Long = 0
    private var maxExposureTime: Long = 0
    private var exposures: MutableList<String>? = null
    private var minExposure = 0
    private var maxExposure = 0
    private var exposureStep = 0f
    private var supportsExpoBracketing = false
    private var maxExpoBracketingNImages = 0
    private var supportsFocusBracketing = false
    private var supportsBurst = false
    private var supportsJpegR = false
    private var supportsRaw = false
    private var viewAngleX = 0f
    private var viewAngleY = 0f
    var physicalCameras: Set<String>? =
        null // if non-null, this camera is part of a logical camera that exposes these physical camera IDs
        private set

    private var _supportedPreviewSizes: List<CameraController.Size>? = null

    private var photoSizes: List<CameraController.Size>? = null
    private var photoSizeConstraints: CameraResolutionConstraints? = null
    private var currentSizeIndex =
        -1 // this is an index into the sizes array, or -1 if sizes not yet set

    var supportedExtensions: List<Int>? =
        null // if non-null, list of supported camera vendor extensions, see https://developer.android.com/reference/android/hardware/camera2/CameraExtensionCharacteristics
    var supportedExtensionsZoom: List<Int>? =
        null // if non-null, list of camera vendor extensions that support zoom

    private var supportsVideo = false
    private var hasCaptureRateFactor =
        false // whether we have a capture rate for faster (timelapse) or slow motion
    private var captureRateFactor =
        1.0f // should be 1.0f if hasCaptureRateFactor is false; set lower than 1 for slow motion, higher than 1 for timelapse
    private var videoHighSpeed =
        false // whether the current video mode requires high speed frame rate (note this may still be true even if isVideo==false, so potentially we could switch photo/video modes without setting up the flag)
    private var supportsVideoHighSpeed = false
    private val videoQualityHandler: VideoQualityHandler = VideoQualityHandler()

    private var lastToast: Toast? = null
    private var lastToastTimeMs: Long = 0
    private val focusFlashToast: ToastBoxer = ToastBoxer()
    private val takePhotoToast: ToastBoxer = ToastBoxer()
    private val pauseVideoToast: ToastBoxer = ToastBoxer()

    private var uiRotation = 0

    private var supportsFaceDetection = false
    private var usingFaceDetection = false
    private var _facesDetected: Array<CameraController.Face?> = emptyArray()
    private val faceRect = RectF()
    private var supportsOpticalStabilization = false
    private var supportsVideoStabilization = false
    private var supportsPhotoVideoRecording = false
    private var canDisableShutterSound = false
    private var _tonemapMaxCurvePoints = 0
    private var _supportsTonemapCurve = false
    private var _supportedApertures: FloatArray? = null
    private var hasFocusArea = false
    private var focusAreaTime: Long = -1 // time when hasFocusArea last set to true
    private var focusCameraX = 0f
    private var focusCameraY = 0f
    private var focusCompleteTime: Long = -1
    private var focusStartedTime: Long = -1
    private var focusSuccess = FOCUS_DONE
    private var setFlashValueAfterAutofocus = ""
    private var takePhotoAfterAutofocus =
        false // set to take a photo when the in-progress autofocus has completed; if setting, remember to call camera_controller.setCaptureFollowAutofocusHint()
    private var successfullyFocused = false
    private var successfullyFocusedTime: Long = -1

    private var hasGravity = false
    private val gravity = FloatArray(3)
    private var hasGeomagnetic = false
    private val geomagnetic = FloatArray(3)
    private val deviceRotation = FloatArray(9)
    private val cameraRotation = FloatArray(9)
    private val deviceInclination = FloatArray(9)
    private var hasGeoDirection = false
    private val _geoDirection = FloatArray(3) // geo direction in radians
    private var _aspectRatio: Double = 0.0
        set(value) {
            require(value > 0.0)

            hasAspectRatio = true
            if (field != value) {
                field = value
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "new aspect ratio: $field"
                )
                cameraSurface.view.requestLayout()
                canvasView?.requestLayout()
            }
        }
    private val newGeoDirection = FloatArray(3)

    private val decimalFormat1dp = DecimalFormat("#.#")

    // use '0' instead of '#' to display e.g. 1.20 instead of 1.2, so that text lengths are consistent (e.g., for the
    // toasts shown when changing sliders for manual focus distance or exposure compensation).
    private val decimalFormat2dpForce0 = DecimalFormat("0.00")

    /* If the user touches to focus in continuous mode, and in photo mode, we switch the cameraController to autofocus mode.
     * autofocusInContinuousMode is set to true when this happens; the runnable resetContinuousFocusRunnable
     * switches back to continuous mode.
     */
    private val resetContinuousFocusHandler = Handler()
    private var resetContinuousFocusRunnable: Runnable? = null
    private var autofocusInContinuousMode = false

    /** Returns whether the target focus distance is currently being set.
     */
    var isSettingTargetFocusDistance: Boolean =
        false // if true, then the focus has been set to manual focus distance for the target (for focus bracketing)
        private set
    var settingTargetFocusDistanceTime: Long =
        0 // time when focusSetForTargetDistance last changed
        private set

    internal enum class FaceLocation {
        FACELOCATION_UNSET,
        FACELOCATION_UNKNOWN,
        FACELOCATION_LEFT,
        FACELOCATION_RIGHT,
        FACELOCATION_TOP,
        FACELOCATION_BOTTOM,
        FACELOCATION_CENTRE
    }

    // for testing; must be volatile for test project reading the state
    private var isTest = false // whether called from OpenKamera.test testing
    private var isTestJunit4 = false

    @Volatile
    var countCameraStartPreview: Int = 0

    @Volatile
    var countCameraAutoFocus: Int = 0

    @Volatile
    var countCameraTakePicture: Int = 0

    @Volatile
    var countCameraContinuousFocusMoving: Int = 0

    @Volatile
    var testFailOpenCamera: Boolean = false

    @Volatile
    var testVideoFailure: Boolean = false

    @Volatile
    var testVideoIoexception: Boolean = false

    @Volatile
    var testVideoCameraControllerException: Boolean = false

    // set from MySurfaceView or CanvasView
    @Volatile
    var testTickerCalled: Boolean = false

    @Volatile
    var testCalledNextOutputFile: Boolean = false

    @Volatile
    var testStartedNextOutputFile: Boolean = false

    @Volatile
    var testRuntimeOnVideoStop: Boolean =
        false // force throwing a RuntimeException when stopping video (this usually happens naturally when stopping video too soon)

    @Volatile
    var testBurstResolution: Boolean = false

    /*private fun previewToCamera(coords: MutableList<Float>) {
        val alpha = coords[0] / cameraSurface.view.width.toFloat()
        val beta = coords[1] / cameraSurface.view.height.toFloat()
        coords[0] = 2000.0f * alpha - 1000.0f
        coords[1] = 2000.0f * beta - 1000.0f
    }*/

    private val resources: Resources
        get() = cameraSurface.view.resources

    val view: View
        get() = cameraSurface.view

    // If this code is changed, important to test that face detection and touch to focus still works as expected, for front and back
    // cameras, for old and new API, including with zoom. Also test with MainActivity.setWindowFlagsForCamera() setting orientation as SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
    // and/or set "Rotate preview" option to 180 degrees.
    private fun calculateCameraToPreviewMatrix() {
        if (MyDebug.LOG) Log.d(TAG, "calculateCameraToPreviewMatrix")
        if (cameraController == null) return
        val dimensions = ViewportDimensions(
            surfaceWidth = cameraSurface.view.width,
            surfaceHeight = cameraSurface.view.height,
            previewWidth = previewW,
            previewHeight = previewH,
            displayRotationDegrees = getDisplayRotationDegrees(false),
            cameraOrientation = cameraController!!.cameraOrientation,
            displayOrientation = cameraController!!.displayOrientation,
            isCameraFacingFront = (cameraController!!.facing === Facing.FACING_FRONT),
            isUsingCamera2 = usingAndroidL
        )
        _cameraToPreviewMatrix.set(PreviewMatrixCalculator.calculateCameraToPreviewMatrix(dimensions))
    }

    private fun calculatePreviewToCameraMatrix() {
        if (cameraController == null) return
        val dimensions = ViewportDimensions(
            surfaceWidth = cameraSurface.view.width,
            surfaceHeight = cameraSurface.view.height,
            previewWidth = previewW,
            previewHeight = previewH,
            displayRotationDegrees = getDisplayRotationDegrees(false),
            cameraOrientation = cameraController!!.cameraOrientation,
            displayOrientation = cameraController!!.displayOrientation,
            isCameraFacingFront = (cameraController!!.facing === Facing.FACING_FRONT),
            isUsingCamera2 = usingAndroidL
        )
        _previewToCameraMatrix.set(PreviewMatrixCalculator.calculatePreviewToCameraMatrix(dimensions))
        calculateCameraToPreviewMatrix()
    }

    private fun getCameraToPreviewMatrix(): Matrix {
        calculateCameraToPreviewMatrix()
        return _cameraToPreviewMatrix
    }

    /** Return a focus area from supplied point. Supplied coordinates should be in camera coordinates. */
    private fun getAreas(focusX: Float, focusY: Float): ArrayList<CameraController.Area> {
        return PreviewMatrixCalculator.calculateFocusAreas(
            focusX,
            focusY,
            focusSize = 50,
            weight = 1000
        )
    }

    private var hasMultitouchStartZoomFactor = false
    private var multitouchStartZoomFactor = 0

    private val touchGestureCoordinator: PreviewTouchGestureCoordinator by lazy {
        PreviewTouchGestureCoordinator(context, object : PreviewTouchCallback {
            override fun onSingleTouch(event: MotionEvent, wasPaused: Boolean): Boolean {
                return handleSingleTouch(event, wasPaused)
            }

            override fun onScaleZoom(scaleFactor: Float) {
                if (cameraController != null && hasZoom) {
                    (applicationInterface.context as? com.hightechif.openkamera.MainActivity)?.cameraViewModel?.setZoom(
                        scaleFactor
                    )
                    scaleZoom(scaleFactor)
                }
            }

            override fun onScaleBegin() {
                if (hasZoom && cameraController != null) {
                    hasMultitouchStartZoomFactor = true
                    multitouchStartZoomFactor = cameraController!!.zoom
                    hasSmoothZoom = true
                    smoothZoom = zoomRatios!![multitouchStartZoomFactor] / 100.0f
                } else {
                    hasMultitouchStartZoomFactor = false
                    multitouchStartZoomFactor = 0
                    hasSmoothZoom = false
                    smoothZoom = 1.0f
                }
            }

            override fun onScaleEnd() {
                if (hasMultitouchStartZoomFactor && hasZoom && cameraController != null && zoomRatios != null && zoomRatios!!.isNotEmpty() && zoomRatios!![0] < 100) {
                    val startZoom = zoomRatios!![multitouchStartZoomFactor]
                    val endZoomFactor: Int = cameraController!!.zoom
                    val endZoom = zoomRatios!![endZoomFactor]
                    if (endZoom in 90..110 && startZoom != 100 && endZoom != 100) {
                        val startDiff = startZoom - 100
                        val endDiff = endZoom - 100
                        if (!(kotlin.math.sign(startDiff.toDouble()) == kotlin.math.sign(endDiff.toDouble()) && abs(
                                endDiff.toDouble()
                            ) >= abs(startDiff.toDouble()))
                        ) {
                            val snappedZoom = find1xZoom()
                            zoomTo(snappedZoom, false)
                        }
                    }
                }
                hasMultitouchStartZoomFactor = false
                multitouchStartZoomFactor = 0
                hasSmoothZoom = false
                smoothZoom = 1.0f
            }

            override fun onDoubleTap(): Boolean {
                return this@Preview.onDoubleTap()
            }

            override fun shouldTakePhotoOnDoubleTap(): Boolean {
                return takePhotoOnDoubleTap()
            }

            override fun isTouchCaptureEnabled(): Boolean {
                return applicationInterface.getTouchCapturePref()
            }

            override fun onClearFakeToast() {
                clearActiveFakeToast()
            }
        })
    }

    fun touchEvent(event: MotionEvent): Boolean {
        if (MyDebug.LOG) Log.d(
            TAG,
            "touch event at : " + event.x + " , " + event.y + " at time " + event.eventTime
        )
        val wasPaused = this.previewStartedState != PREVIEW_STARTED
        if (MyDebug.LOG) Log.d(TAG, "was_paused: $wasPaused")
        applicationInterface.touchEvent(event)
        return touchGestureCoordinator.onTouchEvent(
            event,
            wasPaused,
            isCameraAvailable = (cameraController != null)
        )
    }

    private fun handleSingleTouch(event: MotionEvent, wasPaused: Boolean): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "handleSingleTouch")

        if (!this.isVideo && this.isTakingPhotoOrOnTimer) {
            // if video, okay to refocus when recording
            return true
        }

        // note, we always try to force start the preview (in case isPreviewPaused has become false)
        // except if recording video (firstly, the preview should be running; secondly, we don't want to reset the phase!)
        if (!this.isVideo) {
            startCameraPreview()
        }

        // whether to clear focus area instead of setting new one
        // we don't rely purely on isFocusWaiting(), as sometimes the focus can be really quick
        if (MyDebug.LOG) Log.d(
            TAG,
            "focus_started_time: $focusStartedTime"
        )
        val clearFocusAreas =
            hasFocusArea && focusAreaTime != -1L && (System.currentTimeMillis() - focusAreaTime) < ViewConfiguration.getDoubleTapTimeout()
        cancelAutoFocus()

        val touchCapture: Boolean = applicationInterface.getTouchCapturePref()

        // don't set focus areas on touch if the user is touching to unpause!
        // similarly if doing single touch to capture (we go straight to taking a photo)
        // and not supported for camera extensions
        if (cameraController != null && !this.usingFaceDetection && !wasPaused && !touchCapture && !cameraController!!.isCameraExtension) {
            if (clearFocusAreas) {
                // double tap to clear focus areas
                // also if we were in autofocusInContinuousMode mode, reset back to continuous mode
                if (MyDebug.LOG) Log.d(TAG, "remove focus areas due to touch")
                clearFocusAreas()
                continuousFocusReset()
            } else {
                this.hasFocusArea = false
                this.focusAreaTime = -1

                if (MyDebug.LOG) {
                    Log.d(TAG, "x, y: " + event.x + ", " + event.y)
                }
                val viewW = cameraSurface.view.width.toFloat()
                val viewH = cameraSurface.view.height.toFloat()
                if (viewW > 0 && viewH > 0) {
                    val normX = (event.x / viewW).coerceIn(0.0f, 1.0f)
                    val normY = (event.y / viewH).coerceIn(0.0f, 1.0f)
                    (applicationInterface.context as? com.hightechif.openkamera.MainActivity)?.cameraViewModel?.tapToFocus(
                        android.graphics.PointF(normX, normY)
                    )
                }
                val coords = floatArrayOf(event.x, event.y)
                calculatePreviewToCameraMatrix()
                _previewToCameraMatrix.mapPoints(coords)
                val focusX = coords[0]
                val focusY = coords[1]
                val areas: ArrayList<CameraController.Area> = getAreas(focusX, focusY)

                if (cameraController!!.setFocusAndMeteringArea(areas)) {
                    if (MyDebug.LOG) Log.d(TAG, "set focus (and metering?) area")
                    this.hasFocusArea = true
                    this.focusAreaTime = System.currentTimeMillis()
                    this.focusCameraX = focusX
                    this.focusCameraY = focusY
                } else {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "didn't set focus area in this mode, may have set metering"
                    )
                    // don't set hasFocusArea in this mode
                }
            }
        }

        // don't take a photo on touch if the user is touching to unpause!
        if (!wasPaused && touchCapture) {
            if (MyDebug.LOG) Log.d(TAG, "touch to capture")
            // Interpret as if user had clicked take photo/video button, except that we set the focus/metering areas.
            // We go via ApplicationInterface instead of going direct to Preview.takePicturePressed(), so that
            // the application can handle same as if user had pressed shutter button (needed so that this works
            // correctly in Panorama mode).
            applicationInterface.requestTakePhoto()
            return true
        }

        // don't autofocus on touch if the user is touching to unpause!
        if (!wasPaused) {
            // if clearFocusAreas==true, don't want to reenter autofocusInContinuousMode mode
            tryAutoFocus(false, !clearFocusAreas)
        }
        return true
    }

    //@SuppressLint("ClickableViewAccessibility") @Override
    // When pinch zooming, we'd normally have the problem that zooming is too fast, because we can
    // only zoom to the limited set of values in the zoomRatios array. So when pinch zooming, we
    // keep track of the fractional scaled zoom.
    private var hasSmoothZoom = false
    private var smoothZoom = 1.0f

    /** Returns true if the user is currently pinch zooming, and the Preview has already handled setting
     * the zoom via Preview.zoomTo().
     */
    fun hasSmoothZoom(): Boolean {
        return this.hasSmoothZoom
    }

    /** Returns whether we will take a photo on a double tap. */
    private fun takePhotoOnDoubleTap(): Boolean {
        return applicationInterface.getDoubleTapCapturePref()
    }

    fun onDoubleTap(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onDoubleTap()")
        if (takePhotoOnDoubleTap()) {
            if (MyDebug.LOG) Log.d(TAG, "double-tap to capture")
            applicationInterface.requestTakePhoto()
            return true
        }
        if (applicationInterface.getTouchCapturePref()) {
            return true
        }
        return false
    }

    fun clearFocusAreas() {
        if (MyDebug.LOG) Log.d(TAG, "clearFocusAreas()")
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
        // don't cancelAutoFocus() here, otherwise we get sluggish zoom behavior on Camera2 API
        if (!cameraController!!.isCameraExtension) {
            // if using camera extensions, we could never have set focus and metering in the first place
            cameraController!!.clearFocusAndMetering()
        }
        hasFocusArea = false
        focusAreaTime = -1
        focusSuccess = FOCUS_DONE
        successfullyFocused = false
    }

    fun getMeasureSpec(spec: IntArray, widthSpec: Int, heightSpec: Int) {
        if (MyDebug.LOG) Log.d(TAG, "getMeasureSpec")
        if (!this.hasAspectRatio()) {
            if (MyDebug.LOG) Log.d(TAG, "doesn't have aspect ratio")
            spec[0] = widthSpec
            spec[1] = heightSpec
            return
        }
        val hPadding: Int = cameraSurface.view.paddingLeft + cameraSurface.view.paddingRight
        val vPadding: Int = cameraSurface.view.paddingTop + cameraSurface.view.paddingBottom
        val previewWidth = MeasureSpec.getSize(widthSpec) - hPadding
        val previewHeight = MeasureSpec.getSize(heightSpec) - vPadding

        val result: Int = if (cameraController != null) {
            val degrees = getDisplayRotationDegrees(true)
            (cameraController!!.cameraOrientation - degrees + 360) % 360
        } else {
            if (previewWidth > previewHeight) 0 else 90
        }

        val pair = ViewportTransformHelper.calculateMeasureSpec(
            widthSpec = widthSpec,
            heightSpec = heightSpec,
            aspectRatio = this.aspectRatio,
            hPadding = hPadding,
            vPadding = vPadding,
            relativeRotation = result
        )
        spec[0] = pair.first
        spec[1] = pair.second
        if (MyDebug.LOG) Log.d(TAG, "return: " + spec[0] + " x " + spec[1])
    }

    private fun mySurfaceCreated() {
        if (MyDebug.LOG) Log.d(TAG, "mySurfaceCreated")
        this.hasSurface = true
        this.openCamera()
    }

    private fun mySurfaceDestroyed() {
        if (MyDebug.LOG) Log.d(TAG, "mySurfaceDestroyed")
        this.hasSurface = false
        this.closeCamera(false, null)
    }

    private fun mySurfaceChanged() {
        // surface size is now changed to match the aspect ratio of camera preview - so we shouldn't change the preview to match the surface size, so no need to restart preview here
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (MyDebug.LOG) Log.d(TAG, "surfaceCreated()")
        // The Surface has been created, acquire the camera and tell it where
        // to draw.
        if (holder.surface != null && holder.surface.isValid) {
            (applicationInterface.context as? com.hightechif.openkamera.MainActivity)?.cameraViewModel?.attachSurface(
                holder.surface
            )
        }
        mySurfaceCreated()
        cameraSurface.view
            .setWillNotDraw(false) // see http://stackoverflow.com/questions/2687015/extended-surfaceviews-ondraw-method-never-called
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (MyDebug.LOG) Log.d(TAG, "surfaceDestroyed()")
        (applicationInterface.context as? com.hightechif.openkamera.MainActivity)?.cameraViewModel?.detachSurface()
        // Surface will be destroyed when we return, so stop the preview.
        // Because the CameraDevice object is not a shared resource, it's very
        // important to release it when the activity is paused.
        mySurfaceDestroyed()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (MyDebug.LOG) Log.d(TAG, "surfaceChanged $w, $h")
        if (holder.surface == null) {
            // preview surface does not exist
            return
        }
        if (holder.surface.isValid) {
            (applicationInterface.context as? com.hightechif.openkamera.MainActivity)?.cameraViewModel?.attachSurface(
                holder.surface
            )
        }
        mySurfaceChanged()
    }

    override fun onSurfaceTextureAvailable(arg0: SurfaceTexture, width: Int, height: Int) {
        if (MyDebug.LOG) Log.d(TAG, "onSurfaceTextureAvailable()")
        this.setTextureViewSize = true
        this.textureViewW = width
        this.textureViewH = height
        mySurfaceCreated()
    }

    override fun onSurfaceTextureDestroyed(arg0: SurfaceTexture): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onSurfaceTextureDestroyed()")
        (applicationInterface.context as? com.hightechif.openkamera.MainActivity)?.cameraViewModel?.detachSurface()
        this.setTextureViewSize = false
        this.textureViewW = 0
        this.textureViewH = 0
        mySurfaceDestroyed()
        return true
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        if (MyDebug.LOG) {
            Log.d(TAG, "onSurfaceTextureSizeChanged $width, $height")
            //Log.d(TAG, "surface texture is now: " + ((TextureView)cameraSurface).getSurfaceTexture());
        }

        if (cameraController != null) {
            cameraController!!.testTextureViewBufferW = width
            cameraController!!.testTextureViewBufferH = height

            if (setPreviewSize && (width != previewW || height != previewH)) {
                if (MyDebug.LOG) Log.d(TAG, "updatePreviewTexture")
                // Needed to fix problem if Open Kamera is already running, and the aspect ratio changes (e.g.,
                // change of resolution, or switching between photo and video mode). When starting up in a "default",
                // aspect ratio, the camera is opened via onSurfaceTextureAvailable(), and although we then call setAspectRatio(),
                // there are no calls to onSurfaceTextureSizeChanged(). But when already running, or if
                // an aspect ratio change for the view is required, changing the aspect ratio causes a call to
                // onSurfaceTextureSizeChanged(), which results in the texture view's surface texture's buffer size being reset!
                // (This can be seen in the source code of TextureView: onSizeChanged() calls setDefaultBufferSize() before
                // calling onSurfaceTextureSizeChanged()!) So we need to call setDefaultBufferSize() again to reset
                // to the desired preview buffer size that we already chose!
                cameraController!!.updatePreviewTexture()
            }
        }

        this.setTextureViewSize = true
        this.textureViewW = width
        this.textureViewH = height
        mySurfaceChanged()
        configureTransform()
        recreatePreviewBitmap()
    }

    override fun onSurfaceTextureUpdated(arg0: SurfaceTexture) {
        refreshPreviewBitmap()
    }

    private fun configureTransform() {
        if (MyDebug.LOG) Log.d(TAG, "configureTransform")
        if (cameraController == null || !this.setPreviewSize || !this.setTextureViewSize) {
            if (MyDebug.LOG) Log.d(TAG, "nothing to do")
            return
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "textureview size: $textureViewW, $textureViewH"
            )
            Log.d(TAG, "preview size: $previewW, $previewH")
        }
        val rotation: Int = applicationInterface.getDisplayRotation(true)
        if (MyDebug.LOG) Log.d(
            TAG,
            "configureTransform rotation: $rotation"
        )
        val matrix = ViewportTransformHelper.calculateTextureTransform(
            textureViewWidth = textureViewW,
            textureViewHeight = textureViewH,
            previewWidth = previewW,
            previewHeight = previewH,
            displayRotation = rotation
        )
        cameraSurface.setTransform(matrix)
    }

    fun stopVideo(fromRestart: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "stopVideo()")
        if (videoRecorder == null) {
            // no need to do anything if not recording
            // (important to exit, otherwise we'll momentarily switch the take photo icon to video mode in MyApplicationInterface.stoppingVideo() when opening the settings in landscape mode
            if (MyDebug.LOG) Log.d(TAG, "video wasn't recording anyway")
            return
        }
        applicationInterface.stoppingVideo()
        if (flashVideoTimerTask != null) {
            flashVideoTimerTask!!.cancel()
            flashVideoTimerTask = null
        }
        if (batteryCheckVideoTimerTask != null) {
            batteryCheckVideoTimerTask!!.cancel()
            batteryCheckVideoTimerTask = null
        }
        if (!fromRestart) {
            remainingRestartVideo = 0
        }
        if (videoRecorder != null) { // check again, just to be safe
            if (MyDebug.LOG) Log.d(TAG, "stop video recording")
            //this.phase = PHASE_NORMAL;
            videoRecorder!!.setOnErrorListener(null)
            videoRecorder!!.setOnInfoListener(null)

            try {
                if (usingAndroidL && videoHighSpeed) {
                    // Needed to fix problems with 0.125x and 0.25x slow motion on Pixel 6 Pro - otherwise although
                    // the video is recorded, we are unable to restart the preview after stopping video.
                    // Beware of enabling this for non-high-speed - would need careful testing to ensure this doesn't cause unstable
                    // behavior.
                    if (MyDebug.LOG) Log.d(TAG, "about to call stopRepeating()")
                    cameraController?.stopRepeating()
                }
                if (MyDebug.LOG) Log.d(TAG, "about to call video_recorder.stop()")
                if (testRuntimeOnVideoStop) throw RuntimeException()
                videoRecorder!!.stop()
                if (MyDebug.LOG) Log.d(TAG, "done video_recorder.stop()")
            } catch (_: RuntimeException) {
                // stop() can throw a RuntimeException if stop is called too soon after start - this indicates the video file is corrupt, and should be deleted
                if (MyDebug.LOG) Log.d(TAG, "runtime exception when stopping video")
                videoFileInfo.close()
                applicationInterface.deleteUnusedVideo(
                    videoFileInfo.videoMethod,
                    videoFileInfo.videoUri,
                    videoFileInfo.videoFilename
                )

                videoFileInfo = VideoFileInfo()
                if (nextVideoFileInfo != null) nextVideoFileInfo!!.close()
                nextVideoFileInfo = null
                // if video recording is stopped quickly after starting, it's normal that we might not have saved a valid file, so no need to display a message
                if (!videoStartTimeSet || System.currentTimeMillis() - videoStartTime > 2000) {
                    val profile: VideoProfile = videoProfile
                    applicationInterface.onVideoRecordStopError(profile)
                }
            }
            videoRecordingStopped()
        }
    }

    private fun videoRecordingStopped() {
        if (MyDebug.LOG) Log.d(TAG, "reset video_recorder")
        videoRecorder!!.reset()
        if (MyDebug.LOG) Log.d(TAG, "release video_recorder")
        videoRecorder!!.release()
        videoRecorder = null
        videoRecorderIsPaused = false
        applicationInterface.cameraInOperation(inOperation = false, isVideo = true)
        reconnectCamera(false) // n.b., if something went wrong with video, then we reopen the camera - which may fail (or simply not reopen, e.g., if app is now paused)
        val validVideoToBroadcast =
            videoFileInfo.videoUri != null || !videoFileInfo.videoFilename.isNullOrEmpty()
        videoFileInfo.close()
        if (validVideoToBroadcast) {
            applicationInterface.stoppedVideo(
                videoFileInfo.videoMethod,
                videoFileInfo.videoUri,
                videoFileInfo.videoFilename
            )
            videoFileInfo = VideoFileInfo()
        }
        if (nextVideoFileInfo != null) {
            // if nextVideoFileInfo is not-null, it means we received MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING but not
            // MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED, so it is the application responsibility to create the zero-size
            // video file that will have been created
            if (MyDebug.LOG) Log.d(TAG, "delete unused next video file")
            nextVideoFileInfo!!.close()
            applicationInterface.deleteUnusedVideo(
                nextVideoFileInfo!!.videoMethod,
                nextVideoFileInfo!!.videoUri,
                nextVideoFileInfo!!.videoFilename
            )
        }
        videoFileInfo = VideoFileInfo()
        nextVideoFileInfo = null
    }

    private val context: Context
        get() = applicationInterface.context

    /** Restart video - either due to hitting maximum filesize (for pre-Android 8 when not able to restart seamlessly), or maximum duration.
     */
    private fun restartVideo(dueToMaxFilesize: Boolean) {
        var dueToMaxFilesize = dueToMaxFilesize
        if (MyDebug.LOG) Log.d(TAG, "restartVideo()")
        if (videoRecorder != null) {
            if (dueToMaxFilesize) {
                val lastTime = System.currentTimeMillis() - videoStartTime
                videoAccumulatedTime += lastTime
                if (MyDebug.LOG) {
                    Log.d(TAG, "last_time: $lastTime")
                    Log.d(TAG, "video_accumulated_time is now: $videoAccumulatedTime")
                }
            } else {
                videoAccumulatedTime = 0
            }
            stopVideo(true) // this will also stop the timertask

            // handle restart
            if (MyDebug.LOG) {
                if (dueToMaxFilesize) Log.d(TAG, "restarting due to maximum filesize")
                else Log.d(
                    TAG,
                    "remaining_restart_video is: $remainingRestartVideo"
                )
            }
            if (dueToMaxFilesize) {
                var videoMaxDuration: Long = applicationInterface.getVideoMaxDurationPref()
                if (videoMaxDuration > 0) {
                    videoMaxDuration -= videoAccumulatedTime
                    if (videoMaxDuration < MIN_SAFE_RESTART_VIDEO_TIME) {
                        // if there's less than 1s to go, ignore it - don't want to risk the resultant video being corrupt or throwing error, due to stopping too soon
                        // so instead just pretend we hit the max duration instead
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "hit max filesize, but max time duration is also set, with remaining time less than 1s: $videoMaxDuration"
                        )
                        dueToMaxFilesize = false
                    }
                }
            }
            if (dueToMaxFilesize || remainingRestartVideo > 0) {
                if (isVideo) {
                    var toast: String? = null
                    if (!dueToMaxFilesize) toast =
                        remainingRestartVideo.toString() + " " + context.resources.getString(R.string.repeats_to_go)
                    takePicture(
                        maxFilesizeRestart = dueToMaxFilesize,
                        photoSnapshot = false,
                        continuousFastBurst = false
                    )
                    if (!dueToMaxFilesize) {
                        showToast(
                            clearToast = null,
                            toast,
                            true
                        ) // show the toast afterward, as we're hogging the UI thread here, and media recorder takes time to start up
                        // must decrement after calling takePicture(), so that takePicture() doesn't reset the value of remainingRestartVideo
                        remainingRestartVideo--
                    }
                } else {
                    remainingRestartVideo = 0
                }
            }
        }
    }

    private fun reconnectCamera(quiet: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "reconnectCamera()")
        if (cameraController != null) { // just to be safe
            try {
                cameraController!!.reconnect()
                this.isPreviewPaused = false
            } catch (e: CameraControllerException) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "failed to reconnect to camera, attempting recovery by reopening"
                )
                e.printStackTrace()
                this.previewStartedState = PREVIEW_NOT_STARTED
                cameraController?.release()
                cameraController = null
                cameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSED
                try {
                    openCamera()
                } catch (reopenException: Exception) {
                    if (MyDebug.LOG) Log.e(
                        TAG,
                        "failed to recover camera: ${reopenException.message}"
                    )
                    if (!quiet) {
                        applicationInterface.onFailedReconnectError()
                    }
                }
            }
            try {
                tryAutoFocus(startup = false, manual = false)
            } catch (e: RuntimeException) {
                if (MyDebug.LOG) Log.e(TAG, "tryAutoFocus() threw exception: " + e.message)
                e.printStackTrace()
                // this happens on Nexus 7 if trying to record video at bitrate 50Mbits or higher - it's fair enough that it fails, but we need to recover without a crash!
                // not safe to call closeCamera, as any call to getParameters may cause a RuntimeException
                // update: can no longer reproduce failures on Nexus 7?!
                this.previewStartedState = PREVIEW_NOT_STARTED
                if (!quiet) {
                    val profile: VideoProfile = videoProfile
                    applicationInterface.onVideoRecordStopError(profile)
                }
                cameraController!!.release()
                cameraController = null
                cameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSED
                openCamera()
            }
        }
    }

    private interface CloseCameraCallback {
        fun onClosed()
    }

    private inner class CloseCameraTask(
        val cameraControllerLocal: CameraController,
        val closeCameraCallback: CloseCameraCallback?
    ) : AsyncTask<Void?, Void?, Void?>() {
        var reopen: Boolean = false // if set to true, reopen the camera once closed

        private val tag = "CloseCameraTask"

        override fun doInBackground(vararg voids: Void?): Void? {
            var debugTime: Long = 0
            if (MyDebug.LOG) {
                Log.d(
                    tag,
                    "doInBackground, async task: $this"
                )
                debugTime = System.currentTimeMillis()
            }
            cameraControllerLocal.stopPreview()
            if (MyDebug.LOG) {
                Log.d(
                    tag,
                    "time to stop preview: " + (System.currentTimeMillis() - debugTime)
                )
            }
            cameraControllerLocal.release()
            if (MyDebug.LOG) {
                Log.d(
                    tag,
                    "time to release camera controller: " + (System.currentTimeMillis() - debugTime)
                )
            }
            return null
        }

        /** The system calls this to perform work in the UI thread and delivers
         * the result from doInBackground()  */
        override fun onPostExecute(result: Void?) {
            if (MyDebug.LOG) Log.d(
                tag,
                "onPostExecute, async task: $this"
            )
            cameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSED
            closeCameraTask = null // just to be safe
            if (closeCameraCallback != null) {
                if (MyDebug.LOG) Log.d(
                    tag,
                    "onPostExecute, calling closeCameraCallback.onClosed"
                )
                closeCameraCallback.onClosed()
            }
            if (reopen) {
                if (MyDebug.LOG) Log.d(tag, "onPostExecute, reOpen Kamera")
                openCamera()
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "onPostExecute done, async task: $this"
            )
        }

    }

    /** Closes the camera.
     * @param async Whether to close the camera on a background thread.
     * @param closeCameraCallback If async is true, closeCameraCallback.onClosed() will be called,
     * from the UI thread, once the camera is closed. If async is false,
     * this field is ignored.
     */
    private fun closeCamera(async: Boolean, closeCameraCallback: CloseCameraCallback?) {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "closeCamera()")
            Log.d(TAG, "async: $async")
            debugTime = System.currentTimeMillis()
        }
        removePendingContinuousFocusReset()
        preShotsRingBuffer.flush() // so we flush e.g. when switching cameras
        hasFocusArea = false
        focusAreaTime = -1
        focusSuccess = FOCUS_DONE
        focusStartedTime = -1
        synchronized(this) {
            // synchronize for consistency (keep FindBugs happy)
            takePhotoAfterAutofocus = false
        }
        setFlashValueAfterAutofocus = ""
        successfullyFocused = false
        targetRatio = 0.0
        // n.b., don't reset hasSetLocation, as we can remember the location when switching camera
        if (continuousFocusMoveIsStarted) {
            continuousFocusMoveIsStarted = false
            applicationInterface.onContinuousFocusMove(false)
        }
        applicationInterface.cameraClosed()
        cancelTimer()
        cancelRepeat()
        if (cameraController != null) {
            if (MyDebug.LOG) {
                Log.d(TAG, "close camera_controller")
            }
            if (videoRecorder != null) {
                stopVideo(false)
            }
            // make sure we're into continuous video mode for closing
            // workaround for bug on Samsung Galaxy S5 with UHD, where if the user switches to another (non-continuous-video) focus mode, then goes to Settings, then returns and records video, the preview freezes and the video is corrupted
            // so to be safe, we always reset to continuous video mode
            this.updateFocusForVideo()
            // need to check for camera being non-null again - if an error occurred stopping the video, we will have closed the camera, and may not be able to reopen
            if (cameraController != null) {
                //camera.setPreviewCallback(null);
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "closeCamera: about to pause preview: " + (System.currentTimeMillis() - debugTime)
                    )
                }
                pausePreview(false)
                // we set cameraController to null before starting background thread, so that other callers won't try
                // to use it
                val cameraControllerLocal: CameraController = cameraController!!
                cameraController = null
                if (async) {
                    if (MyDebug.LOG) Log.d(TAG, "close camera on background async")
                    cameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSING
                    closeCameraTask =
                        CloseCameraTask(cameraControllerLocal, closeCameraCallback)
                    closeCameraTask!!.execute()
                } else {
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "closeCamera: about to release camera controller: " + (System.currentTimeMillis() - debugTime)
                        )
                    }
                    cameraControllerLocal.stopPreview()
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "time to stop preview: " + (System.currentTimeMillis() - debugTime)
                        )
                    }
                    cameraControllerLocal.release()
                    cameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSED
                }
            }
        } else {
            if (MyDebug.LOG) {
                Log.d(TAG, "camera_controller isn't open")
            }
            if (closeCameraCallback != null) {
                // still need to call the callback though! (otherwise if camera fails to open, switch camera button won't work!)
                if (MyDebug.LOG) Log.d(TAG, "calling closeCameraCallback.onClosed")
                closeCameraCallback.onClosed()
            }
        }

        if (orientationEventListener != null) {
            if (MyDebug.LOG) Log.d(TAG, "free orientationEventListener")
            orientationEventListener!!.disable()
            orientationEventListener = null
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "closeCamera: total time: " + (System.currentTimeMillis() - debugTime))
        }
    }

    fun cancelTimer() {
        if (MyDebug.LOG) Log.d(TAG, "cancelTimer()")
        if (this.isOnTimer) {
            takePictureTimerTask!!.cancel()
            takePictureTimerTask = null
            if (beepTimerTask != null) {
                beepTimerTask!!.cancel()
                beepTimerTask = null
            }
            this.phase = PHASE_NORMAL
            if (MyDebug.LOG) Log.d(TAG, "cancelled camera timer")
        }
    }

    fun cancelRepeat() {
        if (MyDebug.LOG) Log.d(TAG, "cancelRepeat()")
        remainingRepeatPhotos = 0
    }

    /**
     * @param stopPreview Whether to call camera_controller.stopPreview(). Normally this should be
     * true, but can be set to false if the callers is going to handle calling
     * that (e.g., on a background thread).
     */
    fun pausePreview(stopPreview: Boolean) {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "pausePreview()")
            debugTime = System.currentTimeMillis()
        }
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
        // make sure we're into continuous video mode
        // workaround for bug on Samsung Galaxy S5 with UHD, where if the user switches to another (non-continuous-video) focus mode, then goes to Settings, then returns and records video, the preview freezes and the video is corrupted
        // so to be safe, we always reset to continuous video mode,
        // although I've now fixed this at the level where we close the settings, I've put this guard here, just in case the problem occurs from elsewhere
        this.updateFocusForVideo()
        this.isPreviewPaused = false
        if (stopPreview) {
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "pausePreview: about to stop preview: " + (System.currentTimeMillis() - debugTime)
                )
            }
            cameraController!!.stopPreview()
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "pausePreview: time to stop preview: " + (System.currentTimeMillis() - debugTime)
                )
            }
        }
        this.phase = PHASE_NORMAL
        this.previewStartedState = PREVIEW_NOT_STARTED
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "pausePreview: about to call cameraInOperation: " + (System.currentTimeMillis() - debugTime)
            )
        }
        /*applicationInterface.cameraInOperation(false, false);
		if( isVideo )
			applicationInterface.cameraInOperation(false, true);*/
        if (MyDebug.LOG) {
            Log.d(TAG, "pausePreview: total time: " + (System.currentTimeMillis() - debugTime))
        }
    }

    //private int debugCountOpenCamera = 0; // see usage below
    /** Try to open the camera. Should only be called if cameraController==null.
     * The camera will be opened on a background thread, so won't be available upon
     * exit of this function.
     * If cameraOpenState is already CAMERAOPENSTATE_OPENING, this method does nothing.
     */
    private fun openCamera() {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "OpenKamera()")
            debugTime = System.currentTimeMillis()
        }
        if (applicationInterface.isPreviewInBackground()) {
            if (MyDebug.LOG) Log.d(TAG, "don't Open Kamera as preview in background")
            // note, even if the application never tries to reopen the camera in the background, we still need this check to avoid the camera
            // opening from mySurfaceCreated()
            // for example, this is needed when the application is recreated when settings are open (a new Preview and surface is created, but
            // we don't want the camera to be opened) - to test this, go to settings then turn screen off and on (and unlock)
            return
        } else if (cameraOpenState == CameraOpenState.CAMERAOPENSTATE_OPENING) {
            if (MyDebug.LOG) Log.d(TAG, "already opening camera in background thread")
            return
        } else if (cameraOpenState == CameraOpenState.CAMERAOPENSTATE_CLOSING) {
            Log.d(TAG, "tried to Open Kamera while camera is still closing in background thread")
            return
        }
        // need to init everything now, in case we don't open the camera (but these may already be initialized from an earlier call - e.g., if we are now switching to another camera)
        // n.b., don't reset hasSetLocation, as we can remember the location when switching camera
        previewStartedState =
            PREVIEW_NOT_STARTED // theoretically should be PREVIEW_NOT_STARTED anyway, but I had one RuntimeException from surfaceCreated()->OpenKamera()->setupCamera()->setPreviewSize() because previewStartedState was PREVIEW_STARTED, even though the preview couldn't have been started
        setPreviewSize = false
        previewW = 0
        previewH = 0
        hasFocusArea = false
        focusAreaTime = -1
        focusSuccess = FOCUS_DONE
        focusStartedTime = -1
        synchronized(this) {
            // synchronize for consistency (keep FindBugs happy)
            takePhotoAfterAutofocus = false
        }
        setFlashValueAfterAutofocus = ""
        successfullyFocused = false
        targetRatio = 0.0
        sceneModes = emptyList()
        cameraControllerSupportsZoom = false
        hasZoom = false
        maxZoom = 0
        minimumFocusDistance = 0.0f
        zoomRatios = null
        _facesDetected = emptyArray()
        supportsFaceDetection = false
        usingFaceDetection = false
        supportsOpticalStabilization = false
        supportsVideoStabilization = false
        supportsPhotoVideoRecording = false
        canDisableShutterSound = false
        _tonemapMaxCurvePoints = 0
        _supportsTonemapCurve = false
        colorEffects = emptyList()
        whiteBalances = emptyList()
        antibanding = null
        edgeModes = null
        noiseReductionModes = null
        isos = null
        supportsWhiteBalanceTemperature = false
        minTemperature = 0
        maxTemperature = 0
        supportsIsoRange = false
        minIso = 0
        maxIso = 0
        supportsExposureTime = false
        minExposureTime = 0L
        maxExposureTime = 0L
        exposures = null
        minExposure = 0
        maxExposure = 0
        exposureStep = 0.0f
        supportsExpoBracketing = false
        maxExpoBracketingNImages = 0
        supportsFocusBracketing = false
        supportsBurst = false
        supportsJpegR = false
        supportsRaw = false
        viewAngleX = 55.0f // set a sensible default
        viewAngleY = 43.0f // set a sensible default
        photoSizes = null
        currentSizeIndex = -1
        photoSizeConstraints = null
        hasCaptureRateFactor = false
        captureRateFactor = 1.0f
        videoHighSpeed = false
        supportsVideo = true
        supportsVideoHighSpeed = false
        videoQualityHandler.resetCurrentQuality()
        supportedFlashValues = null
        currentFlashIndex = -1
        supportedFocusValues = null
        currentFocusIndex = -1
        maxNumFocusAreas = 0
        applicationInterface.cameraInOperation(inOperation = false, isVideo = false)
        if (isVideo) applicationInterface.cameraInOperation(inOperation = false, isVideo = true)
        if (!this.hasSurface) {
            if (MyDebug.LOG) {
                Log.d(TAG, "preview surface not yet available")
            }
            return
        }
        if (this.isPaused) {
            if (MyDebug.LOG) {
                Log.d(TAG, "don't Open Kamera as paused")
            }
            return
        }

        // we restrict the checks to Android 6 or later just in case, see note in LocationSupplier.setupLocationListener()
        if (MyDebug.LOG) Log.d(TAG, "check for permissions")
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (MyDebug.LOG) Log.d(TAG, "camera permission not available")
            hasPermissions = false
            applicationInterface.requestCameraPermission()
            // return for now - the application should try to reopen the camera if permission is granted
            return
        }
        if (applicationInterface.needsStoragePermission() && ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (MyDebug.LOG) Log.d(TAG, "storage permission not available")
            hasPermissions = false
            applicationInterface.requestStoragePermission()
            // return for now - the application should try to reopen the camera if permission is granted
            return
        }
        if (MyDebug.LOG) Log.d(TAG, "permissions available")
        // set in case this was previously set to false
        hasPermissions = true

        /*{
			// debug
			if( debugCountOpenCamera++ == 0 ) {
				if( MyDebug.LOG )
					Log.d(TAG, "debug: don't Open Kamera yet");
				return;
			}
		}*/
        cameraOpenState = CameraOpenState.CAMERAOPENSTATE_OPENING
        var cameraId: Int = applicationInterface.getCameraIdPref()
        var cameraIdSPhysical: String? = applicationInterface.getCameraIdSPhysicalPref()
        if (cameraId < 0 || cameraId >= cameraControllerManager.numberOfCameras) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "invalid cameraId: $cameraId"
            )
            cameraId = 0
            cameraIdSPhysical = null
            applicationInterface.setCameraIdPref(cameraId, cameraIdSPhysical)
        }

        if (!usingAndroidL && cameraIdSPhysical != null) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "physical camera not supported for old camera API: $cameraIdSPhysical"
            )
            cameraIdSPhysical = null
            applicationInterface.setCameraIdPref(cameraId, cameraIdSPhysical)
        }

        //final boolean useBackgroundThread = false;
        //final boolean useBackgroundThread = true;
        val useBackgroundThread = true
        /* Opening camera on background thread is important so that we don't block the UI thread:
		 *   - For old Camera API, this is recommended behavior by Google for Camera.open().
		     - For Camera2, the manager.OpenKamera() call is asynchronous, but CameraController2
		       waits for it to open, so it's still important that we run that in a background thread.
		 * In theory this works for all Android versions, but this caused problems of Galaxy Nexus
		 * with tests testTakePhotoAutoLevel(), testTakePhotoAutoLevelAngles() (various camera
		 * errors/exceptions, failing to taking photos). Since this is a significant change, this is
		 * for now limited to modern devices.
		 * Initially this was Android 7, but for 1.44, I enabled for Android 6.
		 */
        if (useBackgroundThread) {
            val cameraIdF = cameraId
            val cameraIdSPhysicalF = cameraIdSPhysical

            openCameraTask = object : AsyncTask<Void?, Void?, CameraController?>() {
                private val TAG = "Preview/OpenKamera"

                override fun doInBackground(vararg voids: Void?): CameraController? {
                    if (MyDebug.LOG) Log.d(TAG, "doInBackground, async task: $this")
                    return openCameraCore(cameraIdF, cameraIdSPhysicalF)
                }

                /** The system calls this to perform work in the UI thread and delivers
                 * the result from doInBackground()  */
                override fun onPostExecute(cameraController: CameraController?) {
                    if (MyDebug.LOG) Log.d(TAG, "onPostExecute, async task: $this")
                    // see note in OpenKameraCore() for why we set cameraController here
                    this@Preview.cameraController = cameraController
                    cameraOpened()
                    // set cameraOpenState after cameraOpened, just in case a non-UI thread is listening for this - also
                    // important for test code waitUntilCameraOpened(), as test code runs on a different thread
                    cameraOpenState = CameraOpenState.CAMERAOPENSTATE_OPENED
                    openCameraTask = null // just to be safe
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "onPostExecute done, async task: $this"
                    )
                }

                override fun onCancelled(cameraController: CameraController?) {
                    if (MyDebug.LOG) {
                        Log.d(TAG, "onCancelled, async task: $this")
                        Log.d(TAG, "camera_controller: $cameraController")
                    }
                    // this typically means the application has paused whilst we were opening camera in background - so should just
                    // dispose of the camera controller
                    // this is the local cameraController, not Preview.this.cameraController!
                    cameraController?.release()
                    cameraOpenState =
                        CameraOpenState.CAMERAOPENSTATE_OPENED // n.b., still set OPENED state - important for test thread to know that this callback is complete
                    openCameraTask = null // just to be safe
                    if (MyDebug.LOG) Log.d(TAG, "onCancelled done, async task: $this")
                }
            }.execute()
        } else {
            this.cameraController = openCameraCore(cameraId, cameraIdSPhysical)
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "OpenKamera: time after opening camera: " + (System.currentTimeMillis() - debugTime)
                )
            }

            cameraOpened()
            cameraOpenState = CameraOpenState.CAMERAOPENSTATE_OPENED
        }

        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "OpenKamera: total time to Open Kamera: " + (System.currentTimeMillis() - debugTime)
            )
        }
    }

    /** Open the camera - this should be called from background thread, to avoid hogging the UI thread.
     */
    @Suppress("DEPRECATION")
    private fun openCameraCore(cameraId: Int, cameraIdSPhysical: String?): CameraController? {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "openCameraCore()")
            debugTime = System.currentTimeMillis()
        }
        // We pass a camera controller back to the UI thread rather than assigning to cameraController here, because:
        // * If we set cameraController directly, we'd need to synchronize, otherwise risk of memory barrier issues
        // * Risk of race conditions if UI thread accesses cameraController before we have called cameraOpened().
        var cameraControllerLocal: CameraController?
        try {
            if (MyDebug.LOG) {
                Log.d(TAG, "try to Open Camera: $cameraId")
                Log.d(
                    TAG,
                    "OpenKamera: time before opening camera: " + (System.currentTimeMillis() - debugTime)
                )
            }
            if (testFailOpenCamera) {
                if (MyDebug.LOG) Log.d(TAG, "test failing to Open Camera")
                throw CameraControllerException()
            }
            val cameraErrorCallback: CameraController.ErrorCallback =
                object : CameraController.ErrorCallback {
                    override fun onError() {
                        if (MyDebug.LOG) Log.e(
                            TAG,
                            "error from CameraController: camera device failed"
                        )
                        if (cameraController != null) {
                            if (MyDebug.LOG) Log.e(TAG, "set camera_controller to null")
                            cameraController = null
                            cameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSED
                            applicationInterface.onCameraError()
                        }
                    }
                }
            if (usingAndroidL) {
                val previewErrorCallback: CameraController.ErrorCallback =
                    object : CameraController.ErrorCallback {
                        override fun onError() {
                            if (MyDebug.LOG) Log.e(
                                TAG,
                                "error from CameraController: preview failed to start"
                            )
                            applicationInterface.onFailedStartPreview()
                        }
                    }
                cameraControllerLocal = CameraController2(
                    this@Preview.context,
                    cameraId,
                    cameraIdSPhysical,
                    cameraFeaturesCaches,
                    previewErrorCallback,
                    cameraErrorCallback
                )
                if (applicationInterface.useCamera2FakeFlash()) {
                    cameraControllerLocal.useCamera2FakeFlash = true
                }
            } else {
                // Isolated legacy Camera1 fallback for legacy hardware/devices
                cameraControllerLocal =
                    CameraController1.createInstance(cameraId, cameraErrorCallback)
            }
            //throw new CameraControllerException; // uncomment to test camera not opening
        } catch (e: CameraControllerException) {
            if (MyDebug.LOG) Log.e(TAG, "Failed to Open Kamera: " + e.message)
            e.printStackTrace()
            cameraControllerLocal = null
        }

        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "OpenKamera: total time for OpenKameraCore: " + (System.currentTimeMillis() - debugTime)
            )
        }
        return cameraControllerLocal
    }

    /** Called from UI thread after OpenKameraCore() completes on the background thread.
     */
    private fun cameraOpened() {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "cameraOpened()")
            debugTime = System.currentTimeMillis()
        }
        if (cameraController != null) {
            val activity = context as Activity
            /*if( MyDebug.LOG )
                Log.d(TAG, "intent: " + activity.getIntent());
            boolean takePhoto = false;
            if( activity.getIntent() != null && activity.getIntent().getExtras() != null ) {
                takePhoto = activity.getIntent().getExtras().getBoolean(TakePhoto.TAKE_PHOTO);
                activity.getIntent().removeExtra(TakePhoto.TAKE_PHOTO);
            }
            else {
                if( MyDebug.LOG )
                    Log.d(TAG, "no intent data");
            }*/
            val takePhoto: Boolean = TakePhoto.TAKE_PHOTO
            if (takePhoto) TakePhoto.TAKE_PHOTO = false
            if (MyDebug.LOG) Log.d(TAG, "take_photo?: $takePhoto")

            setCameraDisplayOrientation()
            if (orientationEventListener == null) {
                if (MyDebug.LOG) Log.d(TAG, "create orientationEventListener")
                orientationEventListener = object : OrientationEventListener(activity) {
                    override fun onOrientationChanged(orientation: Int) {
                        this@Preview.onOrientationChanged(orientation)
                    }
                }
                orientationEventListener!!.enable()
            }
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "OpenKamera: time after setting orientation: " + (System.currentTimeMillis() - debugTime)
                )
            }

            if (MyDebug.LOG) Log.d(TAG, "call setPreviewDisplay")
            cameraSurface.setPreviewDisplay(cameraController)
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "OpenKamera: time after setting preview display: " + (System.currentTimeMillis() - debugTime)
                )
            }

            setupCamera(takePhoto)
            if (this.usingAndroidL) {
                configureTransform()
            }
        }

        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "OpenKamera: total time for cameraOpened: " + (System.currentTimeMillis() - debugTime)
            )
        }
    }


    /** Try to reopen the camera, if not currently open (e.g., permission wasn't granted, but now it is).
     * The camera will be opened on a background thread, so won't be available upon
     * exit of this function.
     * If cameraOpenState is already CAMERAOPENSTATE_OPENING, or the camera is already open,
     * this method does nothing.
     */
    fun retryOpenKamera() {
        if (MyDebug.LOG) Log.d(TAG, "retryOpenKamera()")
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "try to reOpen Kamera")
            this.openCamera()
        } else {
            if (MyDebug.LOG) Log.d(TAG, "camera already open")
        }
    }

    /** Closes and reopens the camera.
     * The camera will be closed and opened on a background thread, so won't be available upon
     * exit of this function.
     */
    fun reOpenKamera() {
        if (MyDebug.LOG) Log.d(TAG, "reOpenKamera()")
        //this.closeCamera(false, null);
        //this.OpenKamera();
        closeCamera(true, object : CloseCameraCallback {
            override fun onClosed() {
                if (MyDebug.LOG) Log.d(TAG, "CloseCameraCallback.onClosed")
                openCamera()
            }
        })
    }

    /** Returns false if we failed to open the camera because camera or storage permission wasn't available.
     */
    fun hasPermissions(): Boolean {
        return hasPermissions
    }

    val isOpeningCamera: Boolean
        /** Returns true iff the camera is currently being opened on background thread (OpenKamera() called, but
         * camera not yet available).
         */
        get() = cameraOpenState == CameraOpenState.CAMERAOPENSTATE_OPENING

    /** Returns true iff we've tried to open the camera (whether it was successful).
     */
    fun openCameraAttempted(): Boolean {
        return cameraOpenState == CameraOpenState.CAMERAOPENSTATE_OPENED
    }

    /** Returns true iff we've tried to open the camera, and were unable to do so.
     */
    fun openCameraFailed(): Boolean {
        return cameraOpenState == CameraOpenState.CAMERAOPENSTATE_OPENED && cameraController == null
    }

    /* Should only be called after camera first opened, or after preview is paused.
     * takePhoto is true if we have been called from the TakePhoto widget (which means
     * we'll take a photo immediately after startup).
     * Important to call this when switching between photo and video mode, as ApplicationInterface
     * preferences/parameters may be different (since we can support taking photos in video snapshot
     * mode, but this may have different parameters).
     */
    fun setupCamera(takePhoto: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "setupCamera()")
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            debugTime = System.currentTimeMillis()
        }
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
        val doStartupFocus = !takePhoto && applicationInterface.getStartupFocusPref()
        if (MyDebug.LOG) {
            Log.d(TAG, "take_photo? $takePhoto")
            Log.d(TAG, "do_startup_focus? $doStartupFocus")
        }
        this.isSettingTargetFocusDistance = false // reset
        this.settingTargetFocusDistanceTime = System.currentTimeMillis()
        // make sure we're into continuous video mode for reopening
        // workaround for bug on Samsung Galaxy S5 with UHD, where if the user switches to another (non-continuous-video) focus mode, then goes to Settings, then returns and records video, the preview freezes and the video is corrupted
        // so to be safe, we always reset to continuous video mode,
        // although I've now fixed this at the level where we close the settings, I've put this guard here, just in case the problem occurs from elsewhere
        // we'll switch to the user-requested focus by calling setFocusPref() from setupCameraParameters() below
        this.updateFocusForVideo()

        try {
            initCameraParameters()
        } catch (e: CameraControllerException) {
            e.printStackTrace()
            applicationInterface.onCameraError()
            closeCamera(false, null)
            return
        }

        // now switch to video if saved
        var savedIsVideo: Boolean = applicationInterface.isVideoPref()
        if (MyDebug.LOG) {
            Log.d(TAG, "saved_is_video: $savedIsVideo")
        }
        if (savedIsVideo && !supportsVideo) {
            if (MyDebug.LOG) Log.d(TAG, "but video not supported")
            savedIsVideo = false
        }
        // must switch video before setupCameraParameters(), and starting preview
        if (savedIsVideo != this.isVideo) {
            if (MyDebug.LOG) Log.d(TAG, "switch video mode as not in correct mode")
            this.switchVideo(duringStartup = true, changeUserPref = false)
        }

        // seems sensible to set extension mode (or not) first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && this.supportedExtensions != null && applicationInterface.isCameraExtensionPref()) {
            val extension: Int = applicationInterface.getCameraExtensionPref()
            if (supportedExtensions!!.contains(extension)) {
                cameraController!!.setCameraExtension(true, extension)

                // also filter unsupported flash modes
                if (supportedFlashValues != null) {
                    if (MyDebug.LOG) Log.d(TAG, "restrict flash modes for extension session")
                    val newSupportedFlashValues: MutableList<String> = ArrayList()
                    for (supportedFlashValue in supportedFlashValues!!) {
                        when (supportedFlashValue) {
                            "flash_off", "flash_frontscreen_torch" -> newSupportedFlashValues.add(
                                supportedFlashValue
                            )
                        }
                    }
                    supportedFlashValues = newSupportedFlashValues
                }

                // also disallow focus modes
                if (supportedFocusValues != null) {
                    if (MyDebug.LOG) Log.d(TAG, "restrict focus modes for extension session")
                    supportedFocusValues = null
                }

                // and disable ae and awb lock (as normally we don't set this when stopping/starting preview)
                cameraController!!.autoExposureLock = false
                cameraController!!.autoWhiteBalanceLock = false
            } else {
                cameraController!!.setCameraExtension(false, 0)
            }
        } else {
            cameraController!!.setCameraExtension(false, 0)
        }

        setupCameraParameters()

        updateFlashForVideo()
        if (takePhoto) {
            if (this.isVideo) {
                if (MyDebug.LOG) Log.d(TAG, "switch to video for take_photo widget")
                this.switchVideo(duringStartup = true, changeUserPref = true)
            }
        }

        // must be done after switching to video mode (so isVideo is set correctly)
        if (MyDebug.LOG) Log.d(TAG, "is_video?: $isVideo")
        if (this.isVideo) {
            var tonemapProfile: TonemapProfile = TonemapProfile.TONEMAPPROFILE_OFF
            if (_supportsTonemapCurve) {
                tonemapProfile = applicationInterface.getVideoTonemapProfile()
            }
            val videoLogProfileStrength =
                if (tonemapProfile === TonemapProfile.TONEMAPPROFILE_LOG) applicationInterface.getVideoLogProfileStrength() else 0.0f
            val videoGamma =
                if (tonemapProfile === TonemapProfile.TONEMAPPROFILE_GAMMA) applicationInterface.getVideoProfileGamma() else 0.0f
            if (MyDebug.LOG) {
                Log.d(TAG, "tonemap_profile: $tonemapProfile")
                Log.d(
                    TAG,
                    "video_log_profile_strength: $videoLogProfileStrength"
                )
                Log.d(TAG, "video_gamma: $videoGamma")
            }
            cameraController!!.setTonemapProfile(
                tonemapProfile,
                videoLogProfileStrength,
                videoGamma
            )
        }

        // Setup for high speed - must be done after setupCameraParameters() and switching to video mode, but before setPreviewSize() and startCameraPreview().
        // In theory it shouldn't matter if we call setVideoHighSpeed(true) if isVideo==false, as it should only have an effect
        // when recording video; but don't set high speed mode in photo mode just to be safe.
        cameraController!!.setVideoHighSpeed(isVideo && videoHighSpeed)

        if (doStartupFocus && usingAndroidL && cameraController!!.supportsAutoFocus()) {
            // need to switch flash off for autofocus - and for Android L, need to do this before starting preview (otherwise it won't work in time); for old camera API, need to do this after starting preview!
            setFlashValueAfterAutofocus = ""
            val oldFlashValue: String = cameraController!!.flashValue
            // getFlashValue() may return "" if flash not supported!
            // also set flashTorch - otherwise we get bug where torch doesn't turn on when starting up in video mode (and it's not like we want to turn torch off for startup focus, anyway)
            if (oldFlashValue.isNotEmpty() && (oldFlashValue != "flash_off") && (oldFlashValue != "flash_torch")) {
                setFlashValueAfterAutofocus = oldFlashValue
                cameraController!!.flashValue = "flash_off"
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "set_flash_value_after_autofocus is now: $setFlashValueAfterAutofocus"
            )
        }

        val isExtension: Boolean = cameraController!!.isCameraExtension
        if (this.supportsJpegR && !isExtension && applicationInterface.getJpegRPref()) {
            cameraController!!.setJpegR(true)
        } else {
            cameraController!!.setJpegR(false)
        }

        if (this.supportsRaw && applicationInterface.getRawPref() !== ApplicationInterface.RawPref.RAWPREF_JPEG_ONLY) {
            cameraController!!.setRaw(true, applicationInterface.getMaxRawImages())
        } else {
            cameraController!!.setRaw(false, 0)
        }

        setupBurstMode()

        run {
            val isBurst: Boolean = cameraController!!.isCaptureFastBurst
            val extension = if (isExtension) cameraController!!.getCameraExtension() else -1
            if (isBurst || isExtension) {
                if (MyDebug.LOG) {
                    if (isBurst) Log.d(TAG, "check photo resolution supports burst")
                    if (isExtension) Log.d(
                        TAG,
                        "check photo resolution supports extension: $extension"
                    )
                }
                val currentSize: CameraController.Size? = currentPictureSize
                if (currentSize != null) {
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "current_size: " + currentSize.width + " x " + currentSize.height + " supports_burst? " + currentSize.supportsBurst
                        )
                    }
                    if (!currentSize.supportsRequirements(isBurst, isExtension, extension)) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "current picture size doesn't support required burst and/or extension"
                        )
                        // set to next largest that supports what we need
                        var newSize: CameraController.Size? = null
                        for (i in photoSizes!!.indices) {
                            val size: CameraController.Size = photoSizes!![i]
                            if (size.supportsRequirements(
                                    isBurst,
                                    isExtension,
                                    extension
                                ) && size.width * size.height <= currentSize.width * currentSize.height
                            ) {
                                if (newSize == null || size.width * size.height > newSize.width * newSize.height) {
                                    currentSizeIndex = i
                                    newSize = size
                                }
                            }
                        }
                        if (newSize == null) {
                            Log.e(
                                TAG,
                                "can't find supporting picture size smaller than the current picture size"
                            )
                            // just find largest that supports requirements
                            for (i in photoSizes!!.indices) {
                                val size: CameraController.Size = photoSizes!![i]
                                if (size.supportsRequirements(isBurst, isExtension, extension)) {
                                    if (newSize == null || size.width * size.height > newSize.width * newSize.height) {
                                        currentSizeIndex = i
                                        newSize = size
                                    }
                                }
                            }
                            if (newSize == null) {
                                Log.e(TAG, "can't find supporting picture size")
                            }
                        }
                        // if we set a new size, we don't save this to applicationinterface (so that if user switches to a burst mode or extension mode and back
                        // when the original resolution doesn't support burst/extension we revert to the original resolution)
                    }
                }
            }
        }

        // Must set preview size before starting camera preview
        // and must do it after setting photo vs video mode
        // and after setting what camera extension we're using (if any)
        setPreviewSize() // need to call this when we switch cameras, not just when we run for the first time
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCamera: time after setting preview size: " + (System.currentTimeMillis() - debugTime)
            )
        }
        // Must call startCameraPreview after checking if face detection is present - probably best to call it after setting all parameters that we want
        startCameraPreview()
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCamera: time after starting camera preview: " + (System.currentTimeMillis() - debugTime)
            )
        }

        // must be done after setting parameters, as this function may set parameters
        // also needs to be done after starting preview for some devices (e.g., Nexus 7)
        if (this.hasZoom) {
            var zoomPref: Int = applicationInterface.getZoomPref()
            if (zoomPref == -1) {
                zoomPref = find1xZoom()
            }
            zoomTo(zoomPref, false)
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "setupCamera: total time after zoomTo: " + (System.currentTimeMillis() - debugTime)
                )
            }
        } else if (cameraControllerSupportsZoom && !hasZoom) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "camera supports zoom but application disabled zoom, so reset zoom to default"
            )
            // if the application switches zoom off via ApplicationInterface.allowZoom(), we need to support
            // resetting the zoom (in case the application called setupCamera() rather than reopening the camera).
            cameraController!!.resetZoom()
        }

        /*if( takePhoto ) {
			if( this.isVideo ) {
				if( MyDebug.LOG )
					Log.d(TAG, "switch to video for takePhoto widget");
				this.switchVideo(false); // set duringStartup to false, as we now need to reset the preview
			}
		}*/
        applicationInterface.cameraSetup() // must call this after the above takePhoto code for calling switchVideo
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCamera: total time after cameraSetup: " + (System.currentTimeMillis() - debugTime)
            )
        }

        if (takePhoto) {
            // take photo after a delay - otherwise we sometimes get a black image?!
            // also need a longer delay for continuous picture focus, to allow a chance to focus - 1000ms seems to work okay for Nexus 6, put 1500ms to be safe
            val focusValue = currentFocusValue
            val delay =
                if (focusValue != null && focusValue == "focus_mode_continuous_picture") 1500 else 500
            if (MyDebug.LOG) Log.d(
                TAG,
                "delay for take photo: $delay"
            )
            val handler = Handler()
            handler.postDelayed({
                if (MyDebug.LOG) Log.d(TAG, "do automatic take picture")
                takePicture(
                    maxFilesizeRestart = false,
                    photoSnapshot = false,
                    continuousFastBurst = false
                )
            }, delay.toLong())
        }

        if (doStartupFocus) {
            val handler = Handler()
            handler.postDelayed({
                if (MyDebug.LOG) Log.d(TAG, "do startup autofocus")
                tryAutoFocus(
                    startup = true,
                    manual = false
                ) // so we get the autofocus when starting up - we do this on a delay, as calling it immediately means the autofocus doesn't seem to work properly sometimes (at least on Galaxy Nexus)
            }, 500)
        }

        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCamera: total time after setupCamera: " + (System.currentTimeMillis() - debugTime)
            )
        }
    }

    private fun find1xZoom(): Int {
        for (i in zoomRatios!!.indices) {
            if (zoomRatios!![i] == 100) {
                return i
            }
        }
        return 0 // shouldn't happen but just in case, choose smallest zoom value
    }

    fun setupBurstMode() {
        if (MyDebug.LOG) Log.d(TAG, "setupBurstMode()")
        if (this.supportsExpoBracketing && applicationInterface.isExpoBracketingPref()) {
            cameraController!!.burstType = CameraController.BurstType.BURSTTYPE_EXPO
            cameraController!!.setExpoBracketingNImages(applicationInterface.getExpoBracketingNImagesPref())
            cameraController!!.setExpoBracketingStops(applicationInterface.getExpoBracketingStopsPref())
            // setUseExpoFastBurst called when taking a photo
        } else if (this.supportsFocusBracketing && applicationInterface.isFocusBracketingPref()) {
            cameraController!!.burstType = CameraController.BurstType.BURSTTYPE_FOCUS
            cameraController!!.setFocusBracketingNImages(applicationInterface.getFocusBracketingNImagesPref())
            cameraController!!.setFocusBracketingAddInfinity(applicationInterface.getFocusBracketingAddInfinityPref())
        } else if (this.supportsBurst && applicationInterface.isCameraBurstPref()) {
            if (applicationInterface.getBurstForNoiseReduction()) {
                if (this.supportsExposureTime) { // noise reduction mode also needs manual exposure
                    val nrMode: ApplicationInterface.NRModePref =
                        applicationInterface.getNRModePref()
                    cameraController!!.burstType = CameraController.BurstType.BURSTTYPE_NORMAL
                    cameraController!!.setBurstForNoiseReduction(
                        true,
                        nrMode === ApplicationInterface.NRModePref.NRMODE_LOW_LIGHT
                    )
                } else {
                    cameraController!!.burstType = CameraController.BurstType.BURSTTYPE_NONE
                }
            } else {
                cameraController!!.burstType = CameraController.BurstType.BURSTTYPE_NORMAL
                cameraController!!.setBurstForNoiseReduction(
                    burstForNoiseReduction = false,
                    noiseReductionLowLight = false
                )
                cameraController!!.setBurstNImages(applicationInterface.getBurstNImages())
            }
        } else {
            cameraController!!.burstType = CameraController.BurstType.BURSTTYPE_NONE
        }
    }

    @Throws(CameraControllerException::class)
    private fun initCameraParameters() {
        if (MyDebug.LOG) Log.d(TAG, "initCameraParameters()")
        run {
            // get available scene modes
            // important, from old Camera API docs:
            // "Changing scene mode may override other parameters (such as flash mode, focus mode, white balance).
            // For example, suppose originally flash mode is on and supported flash modes are on/off. In night
            // scene mode, both flash mode and supported flash mode may be changed to off. After setting scene
            // mode, applications should call getParameters to know if some parameters are changed."
            // this doesn't appear to apply to Camera2 API, but we still might as well set scene mode first
            if (MyDebug.LOG) Log.d(TAG, "set up scene mode")
            val value: String = applicationInterface.getSceneModePref()
            if (MyDebug.LOG) Log.d(TAG, "saved scene mode: $value")

            val supportedValues: SupportedValues? = cameraController?.setSceneMode(value)
            if (supportedValues != null) {
                sceneModes = supportedValues.values
                // now save, so it's available for PreferenceActivity
                applicationInterface.setSceneModePref(supportedValues.selectedValue)
            } else {
                // delete key in case it's present (e.g., if feature no longer available due to change in OS, or switching APIs)
                applicationInterface.clearSceneModePref()
            }
        }

        run {
            // grab all read-only info from parameters
            if (MyDebug.LOG) Log.d(TAG, "grab info from parameters")
            val cameraFeatures: CameraFeatures = cameraController!!.cameraFeatures

            this.minimumFocusDistance = cameraFeatures.minimumFocusDistance
            this.supportsFaceDetection = cameraFeatures.supportsFaceDetection
            this.photoSizes = cameraFeatures.pictureSizes
            if (testBurstResolution) {
                // this flag means we pretend the largest resolution doesn't support burst
                var currentSize: CameraController.Size? = null
                for (i in photoSizes!!.indices) {
                    val size: CameraController.Size = photoSizes!![i]
                    if (currentSize == null || size.width * size.height > currentSize.width * currentSize.height) {
                        currentSize = size
                    }
                }
                if (currentSize != null) currentSize.supportsBurst = false
            }
            supportedFlashValues = cameraFeatures.supportedFlashValues
            supportedFocusValues = cameraFeatures.supportedFocusValues
            this.maxNumFocusAreas = cameraFeatures.maxNumFocusAreas
            this.isExposureLockSupported = cameraFeatures.isExposureLockSupported
            this.isWhiteBalanceLockSupported = cameraFeatures.isWhiteBalanceLockSupported
            this.supportsOpticalStabilization = cameraFeatures.isOpticalStabilizationSupported
            this.supportsVideoStabilization = cameraFeatures.isVideoStabilizationSupported
            this.supportsPhotoVideoRecording = cameraFeatures.isPhotoVideoRecordingSupported
            this.canDisableShutterSound = cameraFeatures.canDisableShutterSound
            this._tonemapMaxCurvePoints = cameraFeatures.tonemapMaxCurvePoints
            this._supportsTonemapCurve = cameraFeatures.supportsTonemapCurve
            this._supportedApertures = cameraFeatures.apertures
            this.supportsWhiteBalanceTemperature =
                cameraFeatures.supportsWhiteBalanceTemperature
            this.minTemperature = cameraFeatures.minTemperature
            this.maxTemperature = cameraFeatures.maxTemperature
            this.supportsIsoRange = cameraFeatures.supportsIsoRange
            this.minIso = cameraFeatures.minIso
            this.maxIso = cameraFeatures.maxIso
            this.supportsExposureTime = cameraFeatures.supportsExposureTime
            this.minExposureTime = cameraFeatures.minExposureTime
            this.maxExposureTime = cameraFeatures.maxExposureTime
            this.minExposure = cameraFeatures.minExposure
            this.maxExposure = cameraFeatures.maxExposure
            this.exposureStep = cameraFeatures.exposureStep
            this.supportsExpoBracketing = cameraFeatures.supportsExpoBracketing
            this.maxExpoBracketingNImages = cameraFeatures.maxExpoBracketingNImages
            this.supportsFocusBracketing = cameraFeatures.supportsFocusBracketing
            this.supportsBurst = cameraFeatures.supportsBurst
            this.supportsJpegR = cameraFeatures.supportsJpegR
            this.supportsRaw = cameraFeatures.supportsRaw
            this.viewAngleX = cameraFeatures.viewAngleX
            this.viewAngleY = cameraFeatures.viewAngleY
            this.supportsVideoHighSpeed =
                cameraFeatures.videoSizesHighSpeed != null && cameraFeatures.videoSizesHighSpeed!!.isNotEmpty()
            videoQualityHandler.setVideoSizes(cameraFeatures.videoSizes)
            videoQualityHandler.setVideoSizesHighSpeed(cameraFeatures.videoSizesHighSpeed)
            this._supportedPreviewSizes = cameraFeatures.previewSizes
            this.supportedExtensions = cameraFeatures.supportedExtensions
            this.supportedExtensionsZoom = cameraFeatures.supportedExtensionsZoom
            this.physicalCameras = cameraFeatures.physicalCameraIds

            // need to do zoom last, as applicationInterface.allowZoom() may depend on the supported
            // camera features (e.g., zoom not necessarily supported with camera extensions, so we need to have first
            // stored supportedExtensions - otherwise starting up in an extension photo mode will still
            // show zoom controls even if zoom not supported)
            this.cameraControllerSupportsZoom = cameraFeatures.isZoomSupported
            this.hasZoom = cameraFeatures.isZoomSupported && applicationInterface.allowZoom()
            if (this.hasZoom) {
                this.maxZoom = cameraFeatures.maxZoom
                this.zoomRatios = cameraFeatures.zoomRatios
            } else {
                this.maxZoom = 0
                this.zoomRatios = null
            }
        }
    }

    private fun setupCameraParameters() {
        if (MyDebug.LOG) Log.d(TAG, "setupCameraParameters()")
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            debugTime = System.currentTimeMillis()
        }

        run {
            if (MyDebug.LOG) Log.d(TAG, "set up face detection")
            // get face detection supported
            this._facesDetected = emptyArray()
            if (this.supportsFaceDetection) {
                this.usingFaceDetection = applicationInterface.getFaceDetectionPref()
            } else {
                this.usingFaceDetection = false
            }
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "supports_face_detection?: $supportsFaceDetection"
                )
                Log.d(
                    TAG,
                    "using_face_detection?: $usingFaceDetection"
                )
            }
            if (this.usingFaceDetection) {
                class MyFaceDetectionListener : CameraController.FaceDetectionListener {
                    private val handler = Handler()
                    private var lastNFaces = -1
                    private var lastFaceLocation = FaceLocation.FACELOCATION_UNSET

                    /** Note, at least for Camera2 API, onFaceDetection() isn't called on UI thread.
                     */
                    override fun onFaceDetection(faces: Array<CameraController.Face?>) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "onFaceDetection: " + faces.size + " : " + faces.contentToString()
                        )
                        if (cameraController == null) {
                            // can get a crash in some cases when switching camera when face detection is on (at least for Camera2)
                            val activity = this@Preview.context as Activity
                            activity.runOnUiThread { _facesDetected = emptyArray() }
                            return
                        }

                        // don't assign to facesDetected yet, as that has to be done on the UI thread

                        // We don't synchronize on facesDetected, as the array may be passed to other
                        // classes via getFacesDetected(). Although that function could copy instead,
                        // that would mean an allocation in every frame in DrawPreview.
                        // Easier to just do the assignment on the UI thread.
                        val activity = this@Preview.context as Activity
                        activity.runOnUiThread {
                            reportFaces(faces)
                            if (_facesDetected.isEmpty() || _facesDetected.size != faces.size) {
                                // avoid unnecessary reallocations
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "allocate new faces_detected"
                                )
                                _facesDetected = arrayOfNulls(faces.size)
                            }
                            System.arraycopy(faces, 0, _facesDetected, 0, faces.size)
                        }
                    }

                    /** Accessibility: report number of faces for talkback etc.
                     */
                    fun reportFaces(localFaces: Array<CameraController.Face?>) {
                        run {
                            val nFaces = localFaces.size
                            var faceLocation = FaceLocation.FACELOCATION_UNKNOWN
                            if (nFaces > 0) {
                                // set faceLocation
                                var avgX = 0f
                                var avgY = 0f
                                val bdryFracC = 0.35f
                                var allCentre = true
                                val matrix = getCameraToPreviewMatrix()
                                for (face in localFaces) {
                                    if (face != null) {
                                        //float faceX = face.rect.centerX();
                                        //float faceY = face.rect.centerY();
                                        // convert to screen space coordinates
                                        faceRect.set(face.rect)
                                        matrix.mapRect(faceRect)
                                        var faceX = faceRect.centerX()
                                        var faceY = faceRect.centerY()

                                        faceX /= cameraSurface.view.width.toFloat()
                                        faceY /= cameraSurface.view.height.toFloat()
                                        if (allCentre) {
                                            if (faceX < bdryFracC || faceX > 1.0f - bdryFracC || faceY < bdryFracC || faceY > 1.0f - bdryFracC) allCentre =
                                                false
                                        }
                                        avgX += faceX
                                        avgY += faceY
                                    }
                                }
                                avgX /= nFaces.toFloat()
                                avgY /= nFaces.toFloat()
                                if (MyDebug.LOG) {
                                    Log.d(TAG, "    avg_x: $avgX")
                                    Log.d(TAG, "    avg_y: $avgY")
                                    Log.d(
                                        TAG,
                                        "    ui_rotation: $uiRotation"
                                    )
                                }
                                if (allCentre) {
                                    faceLocation = FaceLocation.FACELOCATION_CENTRE
                                } else {
                                    when (uiRotation) {
                                        0 -> {}
                                        90 -> {
                                            val temp = avgX
                                            avgX = avgY
                                            avgY = 1.0f - temp
                                        }

                                        180 -> {
                                            avgX = 1.0f - avgX
                                            avgY = 1.0f - avgY
                                        }

                                        270 -> {
                                            val temp = avgX
                                            avgX = 1.0f - avgY
                                            avgY = temp
                                        }
                                    }
                                    if (MyDebug.LOG) {
                                        Log.d(TAG, "    avg_x: $avgX")
                                        Log.d(TAG, "    avg_y: $avgY")
                                    }
                                    if (avgX < bdryFracC) faceLocation =
                                        FaceLocation.FACELOCATION_LEFT
                                    else if (avgX > 1.0f - bdryFracC) faceLocation =
                                        FaceLocation.FACELOCATION_RIGHT
                                    else if (avgY < bdryFracC) faceLocation =
                                        FaceLocation.FACELOCATION_TOP
                                    else if (avgY > 1.0f - bdryFracC) faceLocation =
                                        FaceLocation.FACELOCATION_BOTTOM
                                }
                            }
                            if (nFaces != lastNFaces || faceLocation != lastFaceLocation) {
                                if (nFaces == 0 && lastNFaces == -1) {
                                    // only say 0 faces detected if previously the number was non-zero
                                } else {
                                    var string = "$nFaces " + this@Preview.context.resources
                                        .getString(if (nFaces == 1) R.string.face_detected else R.string.faces_detected)
                                    if (nFaces > 0 && faceLocation != FaceLocation.FACELOCATION_UNKNOWN) {
                                        when (faceLocation) {
                                            FaceLocation.FACELOCATION_CENTRE -> string += " " + this@Preview.context.resources
                                                .getString(R.string.centre_of_screen)

                                            FaceLocation.FACELOCATION_LEFT -> string += " " + this@Preview.context.resources
                                                .getString(R.string.left_of_screen)

                                            FaceLocation.FACELOCATION_RIGHT -> string += " " + this@Preview.context.resources
                                                .getString(R.string.right_of_screen)

                                            FaceLocation.FACELOCATION_TOP -> string += " " + this@Preview.context.resources
                                                .getString(R.string.top_of_screen)

                                            FaceLocation.FACELOCATION_BOTTOM -> string += " " + this@Preview.context.resources
                                                .getString(R.string.bottom_of_screen)

                                            else -> {}
                                        }
                                    }
                                    val stringF = string
                                    if (MyDebug.LOG) Log.d(TAG, string)
                                    // to avoid having a big queue of saying "one face detected, two faces detected" etc., we only report
                                    // after a delay, cancelling any that were previously queued
                                    handler.removeCallbacksAndMessages(null)
                                    handler.postDelayed({
                                        if (MyDebug.LOG) Log.d(
                                            TAG,
                                            "announceForAccessibility: $stringF"
                                        )
                                        this@Preview.view.announceForAccessibility(stringF)
                                    }, 500)
                                }

                                lastNFaces = nFaces
                                lastFaceLocation = faceLocation
                            }
                        }
                    }
                }
                cameraController!!.setFaceDetectionListener(MyFaceDetectionListener())
            } else {
                cameraController!!.setFaceDetectionListener(null)
            }
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after setting face detection: " + (System.currentTimeMillis() - debugTime)
            )
        }

        run {
            if (MyDebug.LOG) {
                Log.d(TAG, "set up video stabilization")
                Log.d(TAG, "is_video?: $isVideo")
            }
            if (this.supportsVideoStabilization) {
                val usingVideoStabilization =
                    isVideo && applicationInterface.getVideoStabilizationPref()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "using_video_stabilization?: $usingVideoStabilization"
                )
                cameraController!!.videoStabilization = usingVideoStabilization
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "supports_video_stabilization?: $supportsVideoStabilization"
            )
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after video stabilization: " + (System.currentTimeMillis() - debugTime)
            )
        }

        run {
            if (MyDebug.LOG) Log.d(TAG, "set up color effect")
            val value: String = applicationInterface.getColorEffectPref()
            if (MyDebug.LOG) Log.d(TAG, "saved color effect: $value")

            val supportedValues: SupportedValues? = cameraController!!.setColorEffect(value)
            if (supportedValues != null) {
                colorEffects = supportedValues.values
                // now save, so it's available for PreferenceActivity
                applicationInterface.setColorEffectPref(supportedValues.selectedValue)
            } else {
                // delete key in case it's present (e.g., if feature no longer available due to change in OS, or switching APIs)
                applicationInterface.clearColorEffectPref()
            }
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after color effect: " + (System.currentTimeMillis() - debugTime)
            )
        }

        run {
            if (MyDebug.LOG) Log.d(TAG, "set up white balance")
            val value: String = applicationInterface.getWhiteBalancePref()
            if (MyDebug.LOG) Log.d(TAG, "saved white balance: $value")

            val supportedValues: SupportedValues? = cameraController!!.setWhiteBalance(value)
            if (supportedValues != null) {
                whiteBalances = supportedValues.values
                // now save, so it's available for PreferenceActivity
                applicationInterface.setWhiteBalancePref(supportedValues.selectedValue)

                if (supportedValues.selectedValue == "manual" && this.supportsWhiteBalanceTemperature) {
                    val temperature: Int = applicationInterface.getWhiteBalanceTemperaturePref()
                    cameraController!!.setWhiteBalanceTemperature(temperature)
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "saved white balance: $value"
                    )
                }
            } else {
                // delete key in case it's present (e.g., if feature no longer available due to change in OS, or switching APIs)
                applicationInterface.clearWhiteBalancePref()
            }
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after white balance: " + (System.currentTimeMillis() - debugTime)
            )
        }

        run {
            if (MyDebug.LOG) Log.d(TAG, "set up antibanding")
            val value: String = applicationInterface.getAntiBandingPref()
            if (MyDebug.LOG) Log.d(TAG, "saved antibanding: $value")

            val supportedValues: SupportedValues? = cameraController!!.setAntiBanding(value)
            // for anti-banding, if the stored preference wasn't supported, we stick with the device default - but don't
            // write it back to the user preference
            if (supportedValues != null) {
                antibanding = supportedValues.values
            }
        }

        run {
            if (MyDebug.LOG) Log.d(TAG, "set up edge_mode")
            val value: String = applicationInterface.getEdgeModePref()
            if (MyDebug.LOG) Log.d(TAG, "saved edge_mode: $value")

            val supportedValues: SupportedValues? = cameraController!!.setEdgeMode(value)
            // for edge mode, if the stored preference wasn't supported, we stick with the device default - but don't
            // write it back to the user preference
            if (supportedValues != null) {
                edgeModes = supportedValues.values
            }
        }

        run {
            if (MyDebug.LOG) Log.d(TAG, "set up noise_reduction_mode")
            val value: String = applicationInterface.getCameraNoiseReductionModePref()
            if (MyDebug.LOG) Log.d(
                TAG,
                "saved noise_reduction_mode: $value"
            )

            val supportedValues: SupportedValues? =
                cameraController!!.setNoiseReductionMode(value)
            // for noise reduction mode, if the stored preference wasn't supported, we stick with the device default - but don't
            // write it back to the user preference
            if (supportedValues != null) {
                noiseReductionModes = supportedValues.values
            }
        }

        // must be done before setting flash modes, as we may remove flash modes if in manual mode (update: we now support flash for manual ISO anyway)
        if (MyDebug.LOG) Log.d(TAG, "set up iso")
        var value: String = applicationInterface.getISOPref()
        if (MyDebug.LOG) Log.d(TAG, "saved iso: $value")
        var isManualIso = false
        val isExtension: Boolean = cameraController!!.isCameraExtension
        if (isExtension) {
            // manual ISO not supported for camera extensions
            cameraController!!.setManualISO(false, 0)
        } else if (supportsIsoRange) {
            // in this mode, we can set any ISO value from min to max
            this.isos =
                null // if supportsIsoRange==true, caller shouldn't be using getSupportedISOs()

            // now set the desired ISO mode/value
            if (value == CameraController.ISO_DEFAULT) {
                if (MyDebug.LOG) Log.d(TAG, "setting auto iso")
                cameraController!!.setManualISO(false, 0)
            } else {
                val iso = parseManualISOValue(value)
                if (iso >= 0) {
                    isManualIso = true
                    if (MyDebug.LOG) Log.d(TAG, "iso: $iso")
                    cameraController!!.setManualISO(true, iso)
                } else {
                    // failed to parse
                    cameraController!!.setManualISO(false, 0)
                    value =
                        CameraController.ISO_DEFAULT // so we switch the preferences back to auto mode, rather than the invalid value
                }

                // now save, so it's available for PreferenceActivity
                applicationInterface.setISOPref(value)
            }
        } else {
            // in this mode, any support for ISO is only the specific ISOs offered by the CameraController
            val supportedValues: SupportedValues? = cameraController!!.setISO(value)
            if (supportedValues != null) {
                isos = supportedValues.values
                if (supportedValues.selectedValue != CameraController.ISO_DEFAULT) {
                    if (MyDebug.LOG) Log.d(TAG, "has manual iso")
                    isManualIso = true
                }
                // now save, so it's available for PreferenceActivity
                applicationInterface.setISOPref(supportedValues.selectedValue)
            } else {
                // delete key in case it's present (e.g., if feature no longer available due to change in OS, or switching APIs)
                applicationInterface.clearISOPref()
            }
        }

        if (isManualIso) {
            if (supportsExposureTime) {
                var exposureTimeValue: Long = applicationInterface.getExposureTimePref()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "saved exposure_time: $exposureTimeValue"
                )
                if (exposureTimeValue < minimumExposureTime) exposureTimeValue =
                    minimumExposureTime
                else if (exposureTimeValue > maximumExposureTime) exposureTimeValue =
                    maximumExposureTime
                cameraController!!.setExposureTime(exposureTimeValue)
                // now save
                applicationInterface.setExposureTimePref(exposureTimeValue)
            } else {
                // delete key in case it's present (e.g., if feature no longer available due to change in OS, or switching APIs)
                applicationInterface.clearExposureTimePref()
            }

            if (supportedFlashValues != null) {
                if (MyDebug.LOG) Log.d(TAG, "restrict flash modes for manual mode")
                val newSupportedFlashValues: MutableList<String> = ArrayList()
                for (supportedFlashValue in supportedFlashValues!!) {
                    when (supportedFlashValue) {
                        "flash_off", "flash_on", "flash_torch", "flash_frontscreen_on", "flash_frontscreen_torch" -> newSupportedFlashValues.add(
                            supportedFlashValue
                        )
                    }
                }
                supportedFlashValues = newSupportedFlashValues
                /*
                // flash modes not supported when using Camera2 and manual ISO
                // (it's unclear flash is useful - ideally we'd at least offer torch, but ISO seems to reset to 100 when flash/torch is on!)
                supportedFlashValues = null;
                if( MyDebug.LOG )
                    Log.d(TAG, "flash not supported in Camera2 manual mode");
                */
            }
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after manual iso: " + (System.currentTimeMillis() - debugTime)
            )
        }

        run {
            if (MyDebug.LOG) {
                Log.d(TAG, "set up exposure compensation")
                Log.d(TAG, "min_exposure: $minExposure")
                Log.d(TAG, "max_exposure: $maxExposure")
            }
            // get min/max exposure
            exposures = null
            if (minExposure != 0 || maxExposure != 0) {
                exposures = ArrayList()
                for (i in minExposure..maxExposure) {
                    exposures!!.add(i.toString())
                }
                // if in manual ISO mode, we still want to get the valid exposure compensations, but shouldn't set exposure compensation
                if (!isManualIso) {
                    var exposure: Int = applicationInterface.getExposureCompensationPref()
                    if (exposure !in minExposure..maxExposure) {
                        exposure = 0
                        if (MyDebug.LOG) Log.d(TAG, "saved exposure not supported, reset to 0")
                        if (exposure !in minExposure..maxExposure) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "zero isn't an allowed exposure?! reset to min $minExposure"
                            )
                            exposure = minExposure
                        }
                    }
                    cameraController!!.setExposureCompensation(exposure)
                    // now save, so it's available for PreferenceActivity
                    applicationInterface.setExposureCompensationPref(exposure)
                }
            } else {
                // delete key in case it's present (e.g., if feature no longer available due to change in OS, or switching APIs)
                applicationInterface.clearExposureCompensationPref()
            }
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after exposures: " + (System.currentTimeMillis() - debugTime)
            )
        }

        if (supportedApertures != null) {
            // set up aperture
            val aperture: Float = applicationInterface.getAperturePref()
            if (aperture > 0.0f) {
                // check supported
                for (thisAperture in supportedApertures) {
                    if (thisAperture == aperture) {
                        cameraController!!.setAperture(aperture)
                    }
                }
                // else don't set any aperture (leave as the device default)
            }
        }

        run {
            if (MyDebug.LOG) Log.d(TAG, "set up picture sizes")
            if (MyDebug.LOG) {
                for (i in photoSizes!!.indices) {
                    val size: CameraController.Size = photoSizes!![i]
                    Log.d(TAG, "supported picture size: " + size.width + " , " + size.height)
                }
            }
            currentSizeIndex = -1
            photoSizeConstraints = CameraResolutionConstraints()
            val resolution: Pair<Int, Int>? =
                applicationInterface.getCameraResolutionPref(photoSizeConstraints!!)
            if (resolution != null) {
                val resolutionW = resolution.first
                val resolutionH = resolution.second
                // now find size in valid list
                var i = 0
                while (i < photoSizes!!.size && currentSizeIndex == -1) {
                    val size: CameraController.Size = photoSizes!![i]
                    if (size.width === resolutionW && size.height === resolutionH) {
                        currentSizeIndex = i
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "set current_size_index to: $currentSizeIndex"
                        )
                    }
                    i++
                }
                if (currentSizeIndex == -1) {
                    if (MyDebug.LOG) Log.e(TAG, "failed to find valid size")
                }
            }

            if (currentSizeIndex == -1) {
                // set to largest
                var currentSize: CameraController.Size? = null
                for (i in photoSizes!!.indices) {
                    val size: CameraController.Size = photoSizes!![i]
                    if (currentSize == null || size.width * size.height > currentSize.width * currentSize.height) {
                        currentSizeIndex = i
                        currentSize = size
                    }
                }
            }
            run {
                val currentSize: CameraController.Size? = currentPictureSize
                if (currentSize != null) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "Current size index " + currentSizeIndex + ": " + currentSize.width + ", " + currentSize.height
                    )

                    // now save, so it's available for PreferenceActivity
                    applicationInterface.setCameraResolutionPref(
                        currentSize.width,
                        currentSize.height
                    )

                    // check against constraints
                    // we intentionally do this after calling applicationInterface.setCameraResolutionPref() (as the constraints are
                    // used to just temporarily change resolution, e.g., if a maximum resolution has been enforced for HDR or NR photo
                    // mode, but we don't want to update the saved resolution preference in such cases
                    if (photoSizeConstraints?.satisfies(currentSize) != true) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "current size index fail to satisfy constraints"
                        )
                        var newSize: CameraController.Size? = null
                        // find the largest size that satisfies the constraint
                        for (i in photoSizes!!.indices) {
                            val size: CameraController.Size = photoSizes!![i]
                            if (photoSizeConstraints?.satisfies(size) == true) {
                                if (newSize == null || size.width * size.height > newSize.width * newSize.height) {
                                    currentSizeIndex = i
                                    newSize = size
                                }
                            }
                        }
                        if (newSize == null) {
                            Log.e(TAG, "can't find picture size that satisfies the constraints!")
                            // so just choose the smallest
                            for (i in photoSizes!!.indices) {
                                val size: CameraController.Size = photoSizes!![i]
                                if (newSize == null || size.width * size.height < newSize.width * newSize.height) {
                                    currentSizeIndex = i
                                    newSize = size
                                }
                            }
                        }

                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "Updated size index " + currentSizeIndex + ": " + currentSize.width + ", " + currentSize.height
                        )
                    }
                }
            }
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after picture sizes: " + (System.currentTimeMillis() - debugTime)
            )
        }

        run {
            val imageQuality: Int = applicationInterface.getImageQualityPref()
            if (MyDebug.LOG) Log.d(
                TAG,
                "set up jpeg quality: $imageQuality"
            )
            cameraController!!.jpegQuality = imageQuality
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after jpeg quality: " + (System.currentTimeMillis() - debugTime)
            )
        }

        // get available sizes
        initialiseVideoSizes()
        initialiseVideoQuality()
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after video sizes: " + (System.currentTimeMillis() - debugTime)
            )
        }

        val videoQualityValueS: String = applicationInterface.getVideoQualityPref()
        if (MyDebug.LOG) Log.d(
            TAG,
            "video_quality_value: $videoQualityValueS"
        )
        videoQualityHandler.currentVideoQualityIndex = -1
        if (videoQualityValueS.isNotEmpty()) {
            // parse the saved video quality, and make sure it is still valid
            // now find value in valid list
            var i = 0
            while (i < videoQualityHandler.supportedVideoQuality.size && videoQualityHandler.currentVideoQualityIndex == -1) {
                if (videoQualityHandler.supportedVideoQuality[i] == videoQualityValueS) {
                    videoQualityHandler.currentVideoQualityIndex = i
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "set current_video_quality to: " + videoQualityHandler.currentVideoQualityIndex
                    )
                }
                i++
            }
            if (videoQualityHandler.currentVideoQualityIndex == -1) {
                if (MyDebug.LOG) Log.e(TAG, "failed to find valid video_quality")
            }
        }
        if (videoQualityHandler.currentVideoQualityIndex == -1 && videoQualityHandler.supportedVideoQuality.isNotEmpty()) {
            // default to FullHD if available, else pick highest quality
            // (FullHD will give smaller file sizes and generally give better performance than 4K so probably better for most users; also seems to suffer from fewer problems when using manual ISO in Camera2 API)
            videoQualityHandler.currentVideoQualityIndex = 0 // start with highest quality
            for ((i, element) in videoQualityHandler.supportedVideoQuality.withIndex()) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "check video quality: $element"
                )
                val profile =
                    getCamcorderProfile(element)
                if (profile.videoFrameWidth == 1920 && profile.videoFrameHeight == 1080) {
                    videoQualityHandler.currentVideoQualityIndex = i
                    break
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "set video_quality value to " + videoQualityHandler.currentVideoQuality
            )
        }

        if (videoQualityHandler.currentVideoQualityIndex != -1) {
            // now save, so it's available for PreferenceActivity
            applicationInterface.setVideoQualityPref(videoQualityHandler.currentVideoQuality)
        } else {
            // This means video_quality_handler.supportedVideoQuality.size() is 0 - this could happen if the camera driver
            // supports no camcorderprofiles? In this case, we shouldn't support video.
            Log.e(TAG, "no video qualities found")
            supportsVideo = false
        }

        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after handling video quality: " + (System.currentTimeMillis() - debugTime)
            )
        }

        if (supportsVideo) {
            captureRateFactor = applicationInterface.getVideoCaptureRateFactor()
            hasCaptureRateFactor = abs((captureRateFactor - 1.0f).toDouble()) > 1.0e-5f
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "has_capture_rate_factor: $hasCaptureRateFactor"
                )
                Log.d(
                    TAG,
                    "capture_rate_factor: $captureRateFactor"
                )
            }

            // set up high speed frame rates
            // should be done after checking the requested video size is available, and after reading the requested capture rate
            videoHighSpeed = false
            if (this.supportsVideoHighSpeed) {
                val profile: VideoProfile = videoProfile
                val captureRate = (profile.videoCaptureRate + 1.0e-5f).toInt()
                // We round to an int (a) to avoid risk of numerical wobble when comparing to the integer supported fps ranges, and (b) due to the
                // "Nokia 8" hack in getVideoProfile().
                // Note that when using timelapse (captureRateFactor > 1.0), it may be that the capture rate is genuinely fractional, although these
                // should always be non-high-speed, and this code is just for high speed cases, and if so for determining if the video resolution supports high speed
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "check if we need high speed video for " + profile.videoFrameWidth + " x " + profile.videoFrameHeight + " at fps capture rate " + captureRate
                )
                var bestVideoSize: CameraController.Size? =
                    videoQualityHandler.findVideoSizeForFrameRate(
                        profile.videoFrameWidth,
                        profile.videoFrameHeight,
                        captureRate.toDouble(),
                        false
                    )

                // n.b., we should pass videoCaptureRate (captureRate) and not videoFrameRate (as for slow motion, it's videoCaptureRate that will be high, not videoFrameRate)
                if (bestVideoSize == null && fpsIsHighSpeed(captureRate.toString()) && videoQualityHandler.supportedVideoSizesHighSpeed != null) {
                    Log.e(
                        TAG,
                        "can't find match for capture rate: " + captureRate + " and video size: " + profile.videoFrameWidth + " x " + profile.videoFrameHeight + " at fps " + profile.videoFrameRate
                    )
                    // If fpsIsHighSpeed() returns true for captureRate, then it means a fps is one that isn't
                    // supported by any standard video sizes, but it is supported by a high speed video size. If
                    // bestVideoSize==null, then we must have an incompatible size for this fps.
                    // So try falling back to one of the supported high speed resolutions.
                    val requestedSize: CameraController.Size =
                        videoQualityHandler.maxSupportedVideoSizeHighSpeed
                    profile.videoFrameWidth = requestedSize.width
                    profile.videoFrameHeight = requestedSize.height
                    // now try again
                    bestVideoSize = CameraFeatures.findSize(
                        videoQualityHandler.supportedVideoSizesHighSpeed!!,
                        requestedSize,
                        captureRate.toDouble(),
                        false
                    )
                    if (bestVideoSize != null) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "fall back to a supported video size for high speed fps"
                        )
                        // need to write back to the application
                        // so find the corresponding quality value
                        videoQualityHandler.currentVideoQualityIndex = -1
                        for ((i, element) in videoQualityHandler.supportedVideoQuality.withIndex()) {
                            if (MyDebug.LOG) Log.d(TAG, "check video quality: $element")
                            val camcorderProfile = getCamcorderProfile(
                                element
                            )
                            if (camcorderProfile.videoFrameWidth == profile.videoFrameWidth && camcorderProfile.videoFrameHeight == profile.videoFrameHeight) {
                                videoQualityHandler.currentVideoQualityIndex = i
                                break
                            }
                        }
                        if (videoQualityHandler.currentVideoQualityIndex != -1) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "reset to video quality: " + videoQualityHandler.currentVideoQuality
                            )
                            // MyApplicationInterface stores preferences separately for high speed fps and non-high speed, so fine to save the preference
                            applicationInterface.setVideoQualityPref(videoQualityHandler.currentVideoQuality)
                        } else {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "but couldn't find a corresponding video quality"
                            )
                            bestVideoSize = null
                        }
                    }
                }

                if (bestVideoSize == null) {
                    Log.e(
                        TAG,
                        "fps not supported for this video size: " + profile.videoFrameWidth + " x " + profile.videoFrameHeight + " at fps capture rate " + captureRate
                    )
                    // we'll end up trying to record at the requested resolution and fps even though these seem incompatible;
                    // the camera driver will either ignore the requested fps, or fail
                } else if (bestVideoSize.highSpeed) {
                    videoHighSpeed = true
                }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "video_high_speed?: $videoHighSpeed"
            )
        }

        if (isVideo && videoHighSpeed && supportsIsoRange && isManualIso) {
            if (MyDebug.LOG) Log.d(TAG, "manual mode not supported for video_high_speed")
            cameraController!!.setManualISO(false, 0)
            isManualIso = false
        }

        run {
            if (MyDebug.LOG) {
                Log.d(TAG, "set up flash")
                Log.d(TAG, "flash values: $supportedFlashValues")
            }
            currentFlashIndex = -1
            if (supportedFlashValues != null && supportedFlashValues!!.size > 1) {
                val flashValue: String = applicationInterface.getFlashPref()
                if (flashValue.isNotEmpty()) {
                    if (MyDebug.LOG) Log.d(TAG, "found existing flash_value: $flashValue")
                    // don't need to save, as this is the value that's already saved
                    if (updateFlash(flashValue, false)) {
                        // do nothing
                    } else {
                        if (MyDebug.LOG) Log.d(TAG, "flash value no longer supported!")
                        // if in manual ISO mode, we'll have restricted the available flash modes - so although we want to
                        // communicate this to the application, we don't want to save the new value we've chosen (otherwise
                        // if user goes to manual ISO and back, we might switch saved flash say from auto to off)
                        // similarly for camera extension modes, and specific physical cameras
                        updateFlash(0, false)
                    }
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "found no existing flash_value")
                    // whilst devices with flash should support flashAuto, we'll also be in this codepath for front cameras with
                    // no flash, as instead the available options will be flashOff, flashFrontscreenAuto, flashFrontscreenOn
                    // see testTakePhotoFrontCameraScreenFlash
                    /*if( supported_flash_values.contains("flash_auto") )
                        updateFlash("flash_auto", true);
                    else
                        updateFlash("flash_off", true);*/
                    // update, we now default to flash off - flash is increasingly less useful on modern cameras,
                    // plus reduces problems from risk of buggy flash on Camera2 API...
                    updateFlash("flash_off", true)
                }
            } else {
                if (MyDebug.LOG) Log.d(TAG, "flash not supported")
                supportedFlashValues = null
            }
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after setting up flash: " + (System.currentTimeMillis() - debugTime)
            )
        }

        run {
            if (MyDebug.LOG) Log.d(TAG, "set up focus")
            currentFocusIndex = -1
            if (supportedFocusValues != null && supportedFocusValues!!.size > 1) {
                if (MyDebug.LOG) Log.d(TAG, "focus values: $supportedFocusValues")

                setFocusPref(true)
            } else {
                if (MyDebug.LOG) Log.d(TAG, "focus not supported")
                supportedFocusValues = null
            }
        }

        run {
            var focusDistanceValue: Float = applicationInterface.getFocusDistancePref(false)
            if (MyDebug.LOG) Log.d(
                TAG,
                "saved focus_distance: $focusDistanceValue"
            )
            if (focusDistanceValue < 0.0f) focusDistanceValue = 0.0f
            else if (focusDistanceValue > minimumFocusDistance) focusDistanceValue =
                minimumFocusDistance
            cameraController!!.setFocusDistance(focusDistanceValue)
            cameraController!!.focusBracketingSourceDistance = focusDistanceValue
            // now save
            applicationInterface.setFocusDistancePref(focusDistanceValue, false)
        }
        run {
            var focusDistanceValue: Float = applicationInterface.getFocusDistancePref(true)
            if (MyDebug.LOG) Log.d(
                TAG,
                "saved focus_bracketing_target_distance: $focusDistanceValue"
            )
            if (focusDistanceValue < 0.0f) focusDistanceValue = 0.0f
            else if (focusDistanceValue > minimumFocusDistance) focusDistanceValue =
                minimumFocusDistance
            cameraController!!.focusBracketingTargetDistance = focusDistanceValue
            // now save
            applicationInterface.setFocusDistancePref(focusDistanceValue, true)
        }
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: time after setting up focus: " + (System.currentTimeMillis() - debugTime)
            )
        }

        run {
            if (MyDebug.LOG) Log.d(TAG, "set up exposure lock")
            // exposure lock should always default to false, as doesn't make sense to save it - we can't really preserve a "lock" after the camera is reopened
            // also note that it isn't safe to lock the exposure before starting the preview
            isExposureLocked = false
        }

        run {
            if (MyDebug.LOG) Log.d(TAG, "set up white balance lock")
            // same reasoning as exposure lock
            isWhiteBalanceLocked = false
        }

        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "setupCameraParameters: total time for setting up camera parameters: " + (System.currentTimeMillis() - debugTime)
            )
        }
    }

    private fun setPreviewSize() {
        if (MyDebug.LOG) Log.d(TAG, "setPreviewSize()")
        // also now sets picture size
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
        if (isPreviewStarted) {
            Log.e(TAG, "setPreviewSize() shouldn't be called when preview is running")
            //throw new RuntimeException(); // throw as RuntimeException, as this is a programming error
            // Bizarrely I have seen the above crash reported from Google Play devices, but inspection of the code leaves it unclear
            // why this can happen. So have disabled the exception since this evidently can happen.
            return
        }
        if (!usingAndroidL) {
            // don't do for Android L, else this means we get flash on startup autofocus if flash is on
            this.cancelAutoFocus()
        }
        // first set picture size (for photo mode, must be done now so we can set the picture size from this; for video, doesn't really matter when we set it)
        val newSize: CameraController.Size?
        if (this.isVideo) {
            // see comments for getOptimalVideoPictureSize()
            val profile: VideoProfile = videoProfile
            if (MyDebug.LOG) Log.d(
                TAG,
                "video size: " + profile.videoFrameWidth + " x " + profile.videoFrameHeight
            )
            if (videoHighSpeed) {
                // It's unclear it matters what size we set here given that high speed is only for Camera2 API, and that
                // take photo whilst recording video isn't supported for high speed video - so for Camera2 API, setting
                // picture size should have no effect. But set to a sensible value just in case.
                newSize = CameraController.Size(profile.videoFrameWidth, profile.videoFrameHeight)
            } else {
                val targetRatio = profile.videoFrameWidth.toDouble() / profile.videoFrameHeight
                newSize = getOptimalVideoPictureSize(photoSizes, targetRatio)
            }
        } else {
            newSize = currentPictureSize
        }
        if (newSize != null) {
            cameraController!!.setPictureSize(newSize.width, newSize.height)
        }
        // set optimal preview size
        if (supportedPreviewSizes != null && supportedPreviewSizes!!.isNotEmpty()) {
            val bestSize: CameraController.Size? = getOptimalPreviewSize(supportedPreviewSizes)
            if (bestSize != null) {
                cameraController!!.setPreviewSize(bestSize.width, bestSize.height)
                this.setPreviewSize = true
                this.previewW = bestSize.width
                this.previewH = bestSize.height
                this._aspectRatio = bestSize.width.toDouble() / bestSize.height
            }
        }
    }

    private fun initialiseVideoSizes() {
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
        videoQualityHandler.sortVideoSizes()
    }

    private fun initialiseVideoQuality() {
        val cameraId: Int = cameraController!!.cameraId
        val profiles: MutableList<Int> = ArrayList()
        val dimensions: MutableList<VideoQualityHandler.Dimension2D> = ArrayList()
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH)) {
            val profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH)
            profiles.add(CamcorderProfile.QUALITY_HIGH)
            dimensions.add(
                VideoQualityHandler.Dimension2D(
                    profile.videoFrameWidth,
                    profile.videoFrameHeight
                )
            )
        }
        run {
            if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_2160P)) {
                val profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_2160P)
                profiles.add(CamcorderProfile.QUALITY_2160P)
                dimensions.add(
                    VideoQualityHandler.Dimension2D(
                        profile.videoFrameWidth,
                        profile.videoFrameHeight
                    )
                )
            }
        }
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_1080P)) {
            val profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_1080P)
            profiles.add(CamcorderProfile.QUALITY_1080P)
            dimensions.add(
                VideoQualityHandler.Dimension2D(
                    profile.videoFrameWidth,
                    profile.videoFrameHeight
                )
            )
        }
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_720P)) {
            val profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_720P)
            profiles.add(CamcorderProfile.QUALITY_720P)
            dimensions.add(
                VideoQualityHandler.Dimension2D(
                    profile.videoFrameWidth,
                    profile.videoFrameHeight
                )
            )
        }
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_480P)) {
            val profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_480P)
            profiles.add(CamcorderProfile.QUALITY_480P)
            dimensions.add(
                VideoQualityHandler.Dimension2D(
                    profile.videoFrameWidth,
                    profile.videoFrameHeight
                )
            )
        }
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_CIF)) {
            val profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_CIF)
            profiles.add(CamcorderProfile.QUALITY_CIF)
            dimensions.add(
                VideoQualityHandler.Dimension2D(
                    profile.videoFrameWidth,
                    profile.videoFrameHeight
                )
            )
        }
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_QVGA)) {
            val profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_QVGA)
            profiles.add(CamcorderProfile.QUALITY_QVGA)
            dimensions.add(
                VideoQualityHandler.Dimension2D(
                    profile.videoFrameWidth,
                    profile.videoFrameHeight
                )
            )
        }
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_QCIF)) {
            val profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_QCIF)
            profiles.add(CamcorderProfile.QUALITY_QCIF)
            dimensions.add(
                VideoQualityHandler.Dimension2D(
                    profile.videoFrameWidth,
                    profile.videoFrameHeight
                )
            )
        }
        if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_LOW)) {
            val profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_LOW)
            profiles.add(CamcorderProfile.QUALITY_LOW)
            dimensions.add(
                VideoQualityHandler.Dimension2D(
                    profile.videoFrameWidth,
                    profile.videoFrameHeight
                )
            )
        }
        videoQualityHandler.initialiseVideoQualityFromProfiles(profiles, dimensions)
    }

    /** Gets a CamcorderProfile associated with the supplied quality, for non-slow motion modes. Note
     * that the supplied quality doesn't have to match whatever the current video mode is (or indeed,
     * this might be called even in slow motion mode), since we use this for things like setting up
     * available preferences.
     */
    fun getCamcorderProfile(quality: String): CamcorderProfile {
        if (MyDebug.LOG) Log.d(
            TAG,
            "getCamcorderProfile(): $quality"
        )
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return CamcorderProfile.get(0, CamcorderProfile.QUALITY_HIGH)
        }
        val cameraId: Int = cameraController!!.cameraId
        // default with safe fallback
        var camcorderProfile = try {
            if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH)) {
                CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH)
            } else if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_LOW)) {
                CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_LOW)
            } else {
                CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH)
            }
        } catch (_: Exception) {
            try {
                CamcorderProfile.get(0, CamcorderProfile.QUALITY_HIGH)
            } catch (_: Exception) {
                null
            }
        }
        if (camcorderProfile == null) {
            return CamcorderProfile.get(0, CamcorderProfile.QUALITY_LOW)
        }
        try {
            var profileString = quality
            var index = profileString.indexOf('_')
            if (index != -1) {
                profileString = quality.substring(0, index)
            }
            val profile = profileString.toInt()
            camcorderProfile = CamcorderProfile.get(cameraId, profile)
            if (index != -1 && index + 1 < quality.length) {
                val overrideString = quality.substring(index + 1)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "    overrideString: $overrideString"
                )
                if (overrideString[0] == 'r' && overrideString.length >= 4) {
                    index = overrideString.indexOf('x')
                    if (index == -1) {
                        if (MyDebug.LOG) Log.d(TAG, "overrideString invalid format, can't find x")
                    } else {
                        val resolutionWS = overrideString.substring(1, index) // skip first 'r'
                        val resolutionHS = overrideString.substring(index + 1)
                        if (MyDebug.LOG) {
                            Log.d(
                                TAG,
                                "resolutionWS: $resolutionWS"
                            )
                            Log.d(
                                TAG,
                                "resolutionHS: $resolutionHS"
                            )
                        }
                        // copy to local variable first, so that if we fail to parse height, we don't set the width either
                        val resolutionW = resolutionWS.toInt()
                        val resolutionH = resolutionHS.toInt()
                        camcorderProfile.videoFrameWidth = resolutionW
                        camcorderProfile.videoFrameHeight = resolutionH
                    }
                } else {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "unknown override_string initial code, or otherwise invalid format"
                    )
                }
            }
        } catch (e: NumberFormatException) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "failed to parse video quality: $quality"
            )
            e.printStackTrace()
        }
        return camcorderProfile
    }

    val videoProfile: VideoProfile
        /** Returns a profile describing the currently selected video quality. The returned VideoProfile
         * will usually encapsulate a CamcorderProfile (VideoProfile.getCamcorderProfile() will return
         * non-null), but not always (e.g., for slow motion mode).
         */
        get() {
            val videoProfile: VideoProfile

            // 4K UHD video is not yet supported by Android API (at least testing on Samsung S5 and Note 3, they do not return it via getSupportedVideoSizes(), nor via a CamcorderProfile (either QUALITY_HIGH, or anything else)
            // but it does work if we explicitly set the resolution (at least tested on an S5)
            if (cameraController == null) {
                videoProfile = VideoProfile()
                Log.e(TAG, "camera not opened! returning default video profile for QUALITY_HIGH")
                return videoProfile
            }

            /*if( videoHighSpeed ) {
                // return a video profile for a high speed frame rate - note that if we have a capture rate factor of say 0.25x,
                // the actual fps and bitrate of the resultant video would also be scaled by a factor of 0.25x
                //return new VideoProfile(MediaRecorder.AudioEncoder.AAC, MediaRecorder.OutputFormat.WEBM, 20000000,
                //		MediaRecorder.VideoEncoder.VP8, this.video_high_speed_size.height, 120,
                //		this.video_high_speed_size.width);
                return new VideoProfile(MediaRecorder.AudioEncoder.AAC, MediaRecorder.OutputFormat.MPEG_4, 4*14000000,
                        MediaRecorder.VideoEncoder.H264, this.video_high_speed_size.height, 120,
                        this.video_high_speed_size.width);
            }*/

            // Get user settings
            var recordAudio: Boolean = applicationInterface.getRecordAudioPref()
            val channelsValue: String = applicationInterface.getRecordAudioChannelsPref()
            val fpsValue: String = applicationInterface.getVideoFPSPref()
            val bitrateValue: String = applicationInterface.getVideoBitratePref()
            val force4k: Boolean = applicationInterface.getForce4KPref()
            // Use CamcorderProfile just to get the current sizes and defaults.
            run {
                val camProfile: CamcorderProfile?
                val cameraId: Int = cameraController!!.cameraId

                // videoHighSpeed should only be for Camera2, where we don't support force 4k option, but
                // put the check here just in case - don't want to be forcing 4K resolution if high speed
                // frame rate!
                val isFront = cameraController?.facing === Facing.FACING_FRONT
                if (force4k && !videoHighSpeed && !isFront) {
                    if (MyDebug.LOG) Log.d(TAG, "force 4K UHD video")
                    camProfile = try {
                        val profile = CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH)
                        profile.videoFrameWidth = 3840
                        profile.videoFrameHeight = 2160
                        profile.videoBitRate = (profile.videoBitRate * 2.8).toInt()
                        profile
                    } catch (_: Exception) {
                        null
                    }
                } else if (videoQualityHandler.currentVideoQualityIndex != -1) {
                    camProfile = getCamcorderProfile(videoQualityHandler.currentVideoQuality!!)
                } else {
                    camProfile = null
                }
                videoProfile =
                    if (camProfile != null) VideoProfile(camProfile) else VideoProfile()
            }

            //video_profile.fileFormat = MediaRecorder.OutputFormat.MPEG_4;
            //video_profile.videoCodec = MediaRecorder.VideoEncoder.H264;
            if (fpsValue == "default") {
                if (supportsVideoHighSpeed && videoProfile.videoFrameWidth != 0 && videoProfile.videoFrameHeight != 0) {
                    // Check videoFrameRate is actually supported by requested video resolution.
                    // We need this as sometimes the CamcorderProfile we use may store a frame rate not actually
                    // supported for the resolution (e.g., on Pixel 6 Pro, 1920x1080 and 3840x2160 support 60fps,
                    // and the CamcorderProfiles set 60fps, but the intermediate resolutions such as 1920x1440 only
                    // support 30fps).
                    // Limited to supportsVideoHighSpeed - at the least, we don't want this code for old camera API where
                    // supported frame rates aren't available.
                    // N.B., we should pass videoCaptureRate and not videoFrameRate (as for slow motion, it's videoCaptureRate
                    // that will be high, not videoFrameRate).
                    val bestVideoSize: CameraController.Size? =
                        videoQualityHandler.findVideoSizeForFrameRate(
                            videoProfile.videoFrameWidth,
                            videoProfile.videoFrameHeight,
                            videoProfile.videoCaptureRate,
                            true
                        )
                    if (bestVideoSize != null && !bestVideoSize.supportsFrameRate(videoProfile.videoCaptureRate)) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "video resolution " + videoProfile.videoFrameWidth + " x " + videoProfile.videoFrameHeight + " doesn't support requested fps " + videoProfile.videoFrameRate
                        )
                        val closestFps: Int =
                            bestVideoSize.closestFrameRate(videoProfile.videoFrameRate.toDouble())
                        if (MyDebug.LOG) Log.d(TAG, "    instead choose valid fps: $closestFps")
                        if (closestFps != -1) { // just in case?
                            videoProfile.videoFrameRate = closestFps
                            videoProfile.videoCaptureRate = closestFps.toDouble()
                        }
                    }
                }
            } else {
                try {
                    val fps = fpsValue.toInt()
                    if (MyDebug.LOG) Log.d(TAG, "fps: $fps")
                    videoProfile.videoFrameRate = fps
                    videoProfile.videoCaptureRate = fps.toDouble()
                } catch (_: NumberFormatException) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "fps invalid format, can't parse to int: $fpsValue"
                    )
                }
            }

            if (bitrateValue != "default") {
                try {
                    val bitrate = bitrateValue.toInt()
                    if (MyDebug.LOG) Log.d(TAG, "bitrate: $bitrate")
                    videoProfile.videoBitRate = bitrate
                } catch (_: NumberFormatException) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "bitrate invalid format, can't parse to int: $bitrateValue"
                    )
                }
            }
            val minHighSpeedBitrateC = 4 * 14000000
            if (videoHighSpeed && videoProfile.videoBitRate < minHighSpeedBitrateC) {
                videoProfile.videoBitRate = minHighSpeedBitrateC
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "set minimum bitrate for high speed: " + videoProfile.videoBitRate
                )
            }

            if (hasCaptureRateFactor) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "set video profile frame rate for slow motion or timelapse, capture rate: $captureRateFactor"
                )
                if (captureRateFactor < 1.0) {
                    // capture rate remains the same, and we adjust the frame rate of video
                    videoProfile.videoFrameRate =
                        ((videoProfile.videoFrameRate * captureRateFactor) + 0.5f).toInt()
                    videoProfile.videoBitRate =
                        ((videoProfile.videoBitRate * captureRateFactor) + 0.5f).toInt()
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "scaled frame rate to: " + videoProfile.videoFrameRate
                    )
                    if (abs((captureRateFactor - 0.5f).toDouble()) < 1.0e-5f) {
                        // hack - on Nokia 8 at least, captureRateFactor of 0.5x still gives a normal speed video, but a
                        // workaround is to increase the capture rate - even increasing by just 1.0e-5 works
                        // unclear if this is needed in general, or is a Nokia specific bug
                        videoProfile.videoCaptureRate += 1.0e-3
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "fudged videoCaptureRate to: " + videoProfile.videoCaptureRate
                        )
                    }
                } else if (captureRateFactor > 1.0) {
                    // resultant framerate remains the same, instead adjust the capture rate
                    videoProfile.videoCaptureRate /= captureRateFactor.toDouble()
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "scaled capture rate to: " + videoProfile.videoCaptureRate
                    )
                    if (abs((captureRateFactor - 2.0f).toDouble()) < 1.0e-5f) {
                        // hack - similar idea to the hack above for 2x slow motion
                        // again, even decreasing by 1.0e-5 works
                        // again, unclear if this is needed in general, or is a Nokia specific bug
                        videoProfile.videoCaptureRate -= 1.0e-3f
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "fudged videoCaptureRate to: " + videoProfile.videoCaptureRate
                        )
                    }
                }
                // audio not recorded with slow motion or timelapse video
                recordAudio = false
            }

            // we repeat the Build.VERSION check to avoid Android Lint warning; also needs to be an "if" statement rather than using the
            // "?" operator, otherwise we still get the Android Lint warning
            if (usingAndroidL) {
                videoProfile.videoSource = MediaRecorder.VideoSource.SURFACE
            } else {
                videoProfile.videoSource = MediaRecorder.VideoSource.CAMERA
            }

            // Done with video
            if (recordAudio && ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // needed for Android 6, in case users deny storage permission, otherwise we'll crash
                // see https://developer.android.com/training/permissions/requesting.html
                // we request permission when switching to video mode - if it wasn't granted, here we just switch it off
                // we restrict check to Android 6 or later just in case, see note in LocationSupplier.setupLocationListener()
                if (MyDebug.LOG) Log.e(TAG, "don't have RECORD_AUDIO permission")
                // don't show a toast here, otherwise we'll keep showing toasts whenever getVideoProfile() is called; we only
                // should show a toast when user starts recording video; so we indicate this via the noAudioPermission flag
                recordAudio = false
                videoProfile.noAudioPermission = true
            }

            videoProfile.recordAudio = recordAudio
            if (recordAudio) {
                val prefAudioSrc: String = applicationInterface.getRecordAudioSourcePref()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "pref_audio_src: $prefAudioSrc"
                )
                when (prefAudioSrc) {
                    "audio_src_mic" -> videoProfile.audioSource = MediaRecorder.AudioSource.MIC
                    "audio_src_default" -> videoProfile.audioSource =
                        MediaRecorder.AudioSource.DEFAULT

                    "audio_src_voice_communication" -> videoProfile.audioSource =
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION

                    "audio_src_voice_recognition" -> videoProfile.audioSource =
                        MediaRecorder.AudioSource.VOICE_RECOGNITION

                    "audio_src_unprocessed" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        videoProfile.audioSource = MediaRecorder.AudioSource.UNPROCESSED
                    } else {
                        Log.e(TAG, "audio_src_voice_unprocessed requires Android 7")
                        videoProfile.audioSource = MediaRecorder.AudioSource.CAMCORDER
                    }

                    "audio_src_camcorder" -> videoProfile.audioSource =
                        MediaRecorder.AudioSource.CAMCORDER

                    else -> videoProfile.audioSource = MediaRecorder.AudioSource.CAMCORDER
                }
                if (MyDebug.LOG) Log.d(TAG, "audio_source: " + videoProfile.audioSource)

                if (MyDebug.LOG) Log.d(
                    TAG,
                    "pref_audio_channels: $channelsValue"
                )
                if (channelsValue == "audio_mono") {
                    videoProfile.audioChannels = 1
                } else if (channelsValue == "audio_stereo") {
                    videoProfile.audioChannels = 2
                }
                // else keep with the value already stored in VideoProfile (set from the CamcorderProfile)
            }

            val prefVideoOutputFormat: String =
                applicationInterface.getRecordVideoOutputFormatPref()
            if (MyDebug.LOG) Log.d(
                TAG,
                "pref_video_output_format: $prefVideoOutputFormat"
            )
            when (prefVideoOutputFormat) {
                "preference_video_output_format_default" -> {}
                "preference_video_output_format_mpeg4_h264" -> {
                    videoProfile.fileFormat = MediaRecorder.OutputFormat.MPEG_4
                    videoProfile.videoCodec = MediaRecorder.VideoEncoder.H264
                    videoProfile.audioCodec = MediaRecorder.AudioEncoder.AAC
                }

                "preference_video_output_format_mpeg4_hevc" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    videoProfile.fileFormat = MediaRecorder.OutputFormat.MPEG_4
                    videoProfile.videoCodec = MediaRecorder.VideoEncoder.HEVC
                    videoProfile.audioCodec = MediaRecorder.AudioEncoder.AAC
                }

                "preference_video_output_format_3gpp" -> {
                    videoProfile.fileFormat = MediaRecorder.OutputFormat.THREE_GPP
                    videoProfile.fileExtension = "3gp"
                }

                "preference_video_output_format_webm" -> {
                    // n.b., audio isn't recorded on any device I've tested with WEBM, seems this may
                    // not be supported yet, see:
                    // https://developer.android.com/guide/topics/media/media-formats#audio-formats
                    // https://stackoverflow.com/questions/42857584/recording-webm-with-android-mediarecorder
                    videoProfile.fileFormat = MediaRecorder.OutputFormat.WEBM
                    videoProfile.videoCodec = MediaRecorder.VideoEncoder.VP8
                    videoProfile.audioCodec = MediaRecorder.AudioEncoder.VORBIS
                    videoProfile.fileExtension = "webm"
                }

                else ->                 // treat as default
                    Log.e(
                        TAG,
                        "unknown pref_video_output_format: $prefVideoOutputFormat"
                    )
            }

            if (MyDebug.LOG) Log.d(
                TAG,
                "returning video_profile: $videoProfile"
            )
            return videoProfile
        }

    private fun getCamcorderProfileDescriptionType(profile: CamcorderProfile): String {
        var type = ""
        // keep strings short, as displayed on the PopupView
        if (profile.videoFrameWidth == 3840 && profile.videoFrameHeight == 2160) {
            type = "4K"
        } else if (profile.videoFrameWidth == 1920 && profile.videoFrameHeight == 1080) {
            type = "FullHD"
        } else if (profile.videoFrameWidth == 1280 && profile.videoFrameHeight == 720) {
            type = "HD"
        } else if (profile.videoFrameWidth == 720 && profile.videoFrameHeight == 480) {
            type = "SD"
        } else if (profile.videoFrameWidth == 640 && profile.videoFrameHeight == 480) {
            type = "VGA"
        } else if (profile.videoFrameWidth == 352 && profile.videoFrameHeight == 288) {
            type = "CIF"
        } else if (profile.videoFrameWidth == 320 && profile.videoFrameHeight == 240) {
            type = "QVGA"
        } else if (profile.videoFrameWidth == 176 && profile.videoFrameHeight == 144) {
            type = "QCIF"
        }
        return type
    }

    fun getCamcorderProfileDescriptionShort(quality: String): String {
        if (cameraController == null) return ""
        val profile = getCamcorderProfile(quality)
        val type = getCamcorderProfileDescriptionType(profile)
        val space = if (type.isEmpty()) "" else " "
        return profile.videoFrameWidth.toString() + "x" + profile.videoFrameHeight + space + type
    }

    fun getCamcorderProfileDescription(quality: String): String {
        if (cameraController == null) return ""
        val profile = getCamcorderProfile(quality)
        val type = getCamcorderProfileDescriptionType(profile)
        val space = if (type.isEmpty()) "" else " "
        return type + space + profile.videoFrameWidth + "x" + profile.videoFrameHeight + " " + getAspectRatioMPString(
            resources, profile.videoFrameWidth, profile.videoFrameHeight, true
        )
    }

    private fun calculateTargetRatioForPreview(displaySize: Point): Double {
        val targetRatio: Double
        val previewSize: String = applicationInterface.getPreviewSizePref()
        // should always use wysiwig for video mode, otherwise we get incorrect aspect ratio shown when recording video (at least on Galaxy Nexus, e.g., at 640x480)
        // also not using wysiwyg mode with video caused corruption on Samsung cameras (tested with Samsung S3, Android 4.3, front camera, infinity focus)
        if (previewSize == "preference_preview_size_wysiwyg" || this.isVideo) {
            if (this.isVideo) {
                if (MyDebug.LOG) Log.d(TAG, "set preview aspect ratio from video size (wysiwyg)")
                val profile: VideoProfile = videoProfile
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "video size: " + profile.videoFrameWidth + " x " + profile.videoFrameHeight
                )
                targetRatio = profile.videoFrameWidth.toDouble() / profile.videoFrameHeight
            } else {
                if (MyDebug.LOG) Log.d(TAG, "set preview aspect ratio from photo size (wysiwyg)")
                val pictureSize: CameraController.Size = cameraController!!.pictureSize
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "picture_size: " + pictureSize.width + " x " + pictureSize.height
                )
                targetRatio = pictureSize.width.toDouble() / pictureSize.height
            }
        } else {
            if (MyDebug.LOG) Log.d(TAG, "set preview aspect ratio from display size")
            // base target ratio from display size - means preview will fill the device's display as much as possible
            // but if the preview's aspect ratio differs from the actual photo/video size, the preview will show a cropped version of what is actually taken
            targetRatio = displaySize.x.toDouble() / displaySize.y
        }
        this.targetRatio = targetRatio
        if (MyDebug.LOG) Log.d(TAG, "targetRatio: $targetRatio")
        return targetRatio
    }

    fun getOptimalPreviewSize(sizes: List<CameraController.Size>?): CameraController.Size? {
        if (MyDebug.LOG) Log.d(TAG, "getOptimalPreviewSize()")
        val aspectTolerance = 0.05
        if (sizes == null) return null
        if (isVideo && videoHighSpeed) {
            val profile: VideoProfile = videoProfile
            if (MyDebug.LOG) Log.d(
                TAG,
                "video size: " + profile.videoFrameWidth + " x " + profile.videoFrameHeight
            )
            // preview size must match video resolution for high speed, see doc for CameraDevice.createConstrainedHighSpeedCaptureSession()
            return CameraController.Size(profile.videoFrameWidth, profile.videoFrameHeight)
        }
        var optimalSize: CameraController.Size? = null
        var minDiff = Double.MAX_VALUE
        val displaySize = Point()
        run {
            applicationInterface.getDisplaySize(
                displaySize,
                false
            ) // don't exclude insets, as preview runs under insets in edge-to-edge mode
            // getSize() is adjusted based on the current rotation, so should already be landscape format, but:
            // (a) it would be good to not assume Open Kamera runs in landscape mode (if we ever ran in portrait mode,
            // we'd still want display_size.x > display_size.y as preview resolutions also have width > height,
            // (b) on some devices (e.g., Nokia 8), when coming back from the Settings when device is held in Preview,
            // display size is returned in portrait format! (To reproduce, enable "Maximize preview size"; or if that's
            // already enabled, change the setting off and on.)
            if (MyDebug.LOG) Log.d(TAG, "display_size: " + displaySize.x + " x " + displaySize.y)
            if (displaySize.x < displaySize.y) {
                displaySize[displaySize.y] = displaySize.x
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "swapped display_size to: " + displaySize.x + " x " + displaySize.y
                )
            }
        }
        val targetRatio = calculateTargetRatioForPreview(displaySize)
        var targetHeight = min(displaySize.y.toDouble(), displaySize.x.toDouble()).toInt()
        if (targetHeight <= 0) {
            targetHeight = displaySize.y
        }
        // Try to find the size which matches the aspect ratio, and is closest match to display height
        for (size in sizes) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "    supported preview size: " + size.width + ", " + size.height
            )
            if (cameraController!!.isCameraExtension) {
                val extension: Int = cameraController!!.getCameraExtension()
                if (!size.supportsExtension(extension)) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "        not supported by current extension: $extension"
                    )
                    continue
                }
            }
            val ratio: Double = size.width.toDouble() / size.height
            if (abs(ratio - targetRatio) > aspectTolerance) continue
            if (abs(size.height - targetHeight) < minDiff) {
                optimalSize = size
                minDiff = abs(size.height - targetHeight).toDouble()
            }
        }
        if (optimalSize == null) {
            // can't find match for aspect ratio, so find closest one
            if (MyDebug.LOG) Log.d(TAG, "no preview size matches the aspect ratio")
            optimalSize = getClosestSize(sizes, targetRatio, null)
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "chose optimalSize: " + optimalSize!!.width + " x " + optimalSize.height)
            Log.d(
                TAG,
                "optimalSize ratio: " + (optimalSize.width.toDouble() / optimalSize.height)
            )
        }
        return optimalSize
    }

    fun getOptimalVideoPictureSize(
        sizes: List<CameraController.Size>?,
        targetRatio: Double
    ): CameraController.Size? {
        if (MyDebug.LOG) Log.d(TAG, "getOptimalVideoPictureSize()")
        val maxVideoSize: CameraController.Size = videoQualityHandler.maxSupportedVideoSize
        return getOptimalVideoPictureSize(sizes, targetRatio, maxVideoSize)
    }

    private fun hasAspectRatio(): Boolean {
        return hasAspectRatio
    }

    private val aspectRatio: Double
        get() = _aspectRatio

    /** Returns the rotation in degrees of the display relative to the natural device orientation.
     */
    fun getDisplayRotationDegrees(preferLater: Boolean): Int {
        val rotation: Int = applicationInterface.getDisplayRotation(preferLater)
        var degrees = 0
        when (rotation) {
            Surface.ROTATION_0 -> degrees = 0
            Surface.ROTATION_90 -> degrees = 90
            Surface.ROTATION_180 -> degrees = 180
            Surface.ROTATION_270 -> degrees = 270
            else -> {}
        }
        if (MyDebug.LOG) Log.d(TAG, "    degrees = $degrees")
        return degrees
    }

    // note, if orientation is locked to landscape this is only called when setting up the activity, and will always have the same orientation
    fun setCameraDisplayOrientation() {
        if (MyDebug.LOG) Log.d(TAG, "setCameraDisplayOrientation()")
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
        if (usingAndroidL) {
            // need to configure the textureview
            configureTransform()
        } else {
            val degrees = getDisplayRotationDegrees(true)
            if (MyDebug.LOG) Log.d(TAG, "    degrees = $degrees")
            // note the code to make the rotation relative to the camera sensor is done in camera_controller.setDisplayOrientation()
            cameraController!!.displayOrientation = degrees
        }
    }

    // for taking photos - see http://developer.android.com/reference/android/hardware/Camera.Parameters.html#setRotation(int)
    private fun onOrientationChanged(orientation: Int) {
        /*if( MyDebug.LOG ) {
			Log.d(TAG, "onOrientationChanged()");
			Log.d(TAG, "orientation: " + orientation);
		}*/
        var orientation = orientation
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
        if (cameraController == null) {
            /*if( MyDebug.LOG )
				Log.d(TAG, "camera not opened!");*/
            return
        }
        orientation = (orientation + 45) / 90 * 90
        this.currentOrientation = orientation % 360
        val newRotation: Int
        val cameraOrientation: Int = cameraController!!.cameraOrientation
        newRotation =
            if ((cameraController!!.facing === Facing.FACING_FRONT)) {
                (cameraOrientation - orientation + 360) % 360
            } else {
                (cameraOrientation + orientation) % 360
            }
        if (newRotation != currentRotation) {
            if (MyDebug.LOG) {
                Log.d(TAG, "    current_orientation is $currentOrientation")
                Log.d(TAG, "    info orientation is $cameraOrientation")
                Log.d(TAG, "    set Camera rotation from $currentRotation to $newRotation")
            }
            this.currentRotation = newRotation
        }
    }

    private val deviceDefaultOrientation: Int
        get() {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val config = resources.configuration
            val rotation = windowManager.defaultDisplay.rotation
            return if (((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) &&
                        config.orientation == Configuration.ORIENTATION_LANDSCAPE)
                || ((rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) &&
                        config.orientation == Configuration.ORIENTATION_PORTRAIT)
            ) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
        }

    private val imageVideoRotation: Int
        /* Returns the rotation (in degrees) to use for images/videos, taking the preferenceLockOrientation into account.
                */
        get() {
            if (MyDebug.LOG) Log.d(
                TAG,
                "getImageVideoRotation() from current_rotation $currentRotation"
            )
            val lockOrientation: String = applicationInterface.getLockOrientationPref()
            if (lockOrientation == "landscape") {
                val cameraOrientation: Int = cameraController!!.cameraOrientation
                val deviceOrientation = deviceDefaultOrientation
                val result = if (deviceOrientation == Configuration.ORIENTATION_PORTRAIT) {
                    // should be equivalent to onOrientationChanged(270)
                    if ((cameraController!!.facing === Facing.FACING_FRONT)) {
                        (cameraOrientation + 90) % 360
                    } else {
                        (cameraOrientation + 270) % 360
                    }
                } else {
                    // should be equivalent to onOrientationChanged(0)
                    cameraOrientation
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "getImageVideoRotation() lock to landscape, returns $result"
                )
                return result
            } else if (lockOrientation == "portrait") {
                val cameraOrientation: Int = cameraController!!.cameraOrientation
                val result: Int
                val deviceOrientation = deviceDefaultOrientation
                result = if (deviceOrientation == Configuration.ORIENTATION_PORTRAIT) {
                    // should be equivalent to onOrientationChanged(0)
                    cameraOrientation
                } else {
                    // should be equivalent to onOrientationChanged(90)
                    if ((cameraController!!.facing === Facing.FACING_FRONT)) {
                        (cameraOrientation + 270) % 360
                    } else {
                        (cameraOrientation + 90) % 360
                    }
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "getImageVideoRotation() lock to portrait, returns $result"
                )
                return result
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "getImageVideoRotation() returns current_rotation $currentRotation"
            )
            return this.currentRotation
        }

    fun draw(canvas: Canvas) {
        /*if( MyDebug.LOG )
			Log.d(TAG, "draw()");*/
        if (this.isPaused) {
            /*if( MyDebug.LOG )
    			Log.d(TAG, "draw(): paused");*/
            return
        }

        /*if( true ) // test
			return;*/
        /*if( MyDebug.LOG )
			Log.d(TAG, "uiRotation: " + uiRotation);*/
        /*if( MyDebug.LOG )
			Log.d(TAG, "canvas size " + canvas.getWidth() + " x " + canvas.getHeight());*/
        /*if( MyDebug.LOG )
			Log.d(TAG, "surface frame " + mHolder.getSurfaceFrame().width() + ", " + mHolder.getSurfaceFrame().height());*/
        if (this.focusSuccess != FOCUS_DONE) {
            if (focusCompleteTime != -1L && System.currentTimeMillis() > focusCompleteTime + 1000) {
                focusSuccess = FOCUS_DONE
            }
        }
        applicationInterface.onDrawPreview(canvas)
    }

    fun getScaledZoomFactor(scaleFactor: Float): Int {
        if (MyDebug.LOG) Log.d(
            TAG,
            "getScaledZoomFactor() $scaleFactor"
        )

        var newZoomFactor = 0
        if (this.cameraController != null && this.hasZoom) {
            val zoomFactor: Int = cameraController!!.zoom
            var zoomRatio: Float
            if (hasSmoothZoom) {
                zoomRatio = smoothZoom
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "    use smooth_zoom: " + smoothZoom + " instead of: " + zoomRatios!![zoomFactor] / 100.0f
                )
            } else {
                zoomRatio = zoomRatios!![zoomFactor] / 100.0f
            }
            zoomRatio *= scaleFactor
            if (MyDebug.LOG) Log.d(
                TAG,
                "    zoom_ratio: $zoomRatio"
            )

            newZoomFactor = zoomFactor
            if (zoomRatio <= zoomRatios!![0] / 100.0f) {
                newZoomFactor = 0
                if (hasSmoothZoom) smoothZoom = zoomRatios!![0] / 100.0f
            } else if (zoomRatio >= zoomRatios!![maxZoom] / 100.0f) {
                newZoomFactor = maxZoom
                if (hasSmoothZoom) smoothZoom = zoomRatios!![maxZoom] / 100.0f
            } else if (hasSmoothZoom) {
                // Find the closest zoom level by rounding to nearest.
                // Important to have same behavior whether zooming in or out, otherwise problem when touching with two fingers and not
                // moving - we'll get very small scale factors alternately between zooming in and out.
                // The only reason we have separate codepath for zooming in or out is for performance (since we know to only look at
                // higher or lower zoom ratios).
                var dist =
                    abs((zoomRatio - zoomRatios!![zoomFactor] / 100.0f).toDouble()).toFloat()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "    current dist: $dist"
                )

                if (scaleFactor > 1.0f) {
                    // zooming in
                    for (i in zoomFactor + 1..<zoomRatios!!.size) {
                        val thisDist =
                            abs((zoomRatio - zoomRatios!![i] / 100.0f).toDouble()).toFloat()
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "    this_dist: $thisDist"
                        )
                        if (thisDist < dist) {
                            newZoomFactor = i
                            dist = thisDist
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "zoom in, found new zoom by comparing " + zoomRatios!![i] / 100.0f + " to " + zoomRatio + " , dist " + dist
                            )
                        } else if (thisDist > dist + 1.0e-5f) {
                            break
                        }
                    }
                } else {
                    // zooming out
                    for (i in zoomFactor - 1 downTo 0) {
                        val thisDist =
                            abs((zoomRatio - zoomRatios!![i] / 100.0f).toDouble()).toFloat()
                        if (thisDist < dist) {
                            newZoomFactor = i
                            dist = thisDist
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "zoom out, found new zoom by comparing " + zoomRatios!![i] / 100.0f + " to " + zoomRatio + " , dist " + dist
                            )
                        } else if (thisDist > dist + 1.0e-5f) {
                            break
                        }
                    }
                }

                smoothZoom = zoomRatio
            } else {
                // find the closest zoom level
                // unclear if we need this code anymore (smoothZoom should always be true?)

                if (scaleFactor > 1.0f) {
                    // zooming in
                    for (i in zoomFactor..<zoomRatios!!.size) {
                        if (zoomRatios!![i] / 100.0f >= zoomRatio) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "zoom in, found new zoom by comparing " + zoomRatios!![i] / 100.0f + " >= " + zoomRatio
                            )
                            newZoomFactor = i
                            break
                        }
                    }
                } else {
                    // zooming out
                    for (i in zoomFactor downTo 0) {
                        if (zoomRatios!![i] / 100.0f <= zoomRatio) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "zoom out, found new zoom by comparing " + zoomRatios!![i] / 100.0f + " <= " + zoomRatio
                            )
                            newZoomFactor = i
                            break
                        }
                    }
                }
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "zoom_ratio is now $zoomRatio")
                Log.d(
                    TAG,
                    "    old zoom_factor " + zoomFactor + " ratio " + zoomRatios!![zoomFactor] / 100.0f
                )
                Log.d(
                    TAG,
                    "    chosen new zoom_factor " + newZoomFactor + " ratio " + zoomRatios!![newZoomFactor] / 100.0f
                )
            }
        }

        return newZoomFactor
    }

    fun scaleZoom(scaleFactor: Float) {
        if (MyDebug.LOG) Log.d(TAG, "scaleZoom() $scaleFactor")
        if (this.cameraController != null && this.hasZoom) {
            val newZoomFactor = getScaledZoomFactor(scaleFactor)
            if (hasSmoothZoom) zoomTo(newZoomFactor, true)
            // else don't call zoomTo; this should be called indirectly by applicationInterface.multitouchZoom()
            applicationInterface.multitouchZoom(newZoomFactor)
        }
    }

    private val zoomTransitionHandler = Handler(Looper.getMainLooper())
    private var zoomTransitionRunnable: Runnable? = null

    private fun zoomTo(newZoomFactor: Int, allowSmoothZoom: Boolean) {
        zoomTo(newZoomFactor, allowSmoothZoom, false)
    }

    /** Zooms to the supplied index (within the zoom_ratios array).
     * @param newZoomFactor The index to zoom to.
     * @param allowSmoothZoom Whether zooming as part of pinch zooming.
     * @param allowZoomTransition If true, then change zoom gradually towards the requested zoom,
     *                              rather than zooming immediately to the requested zoom. Only
     *                              supported if allow_smooth_zoom==false.
     */
    fun zoomTo(newZoomFactor: Int, allowSmoothZoom: Boolean, allowZoomTransition: Boolean) {
        var mutNewZoomFactor = newZoomFactor
        var mutAllowZoomTransition = allowZoomTransition
        if (MyDebug.LOG)
            Log.d(TAG, "ZoomTo(): $mutNewZoomFactor")
        if (mutNewZoomFactor < 0)
            mutNewZoomFactor = 0
        else if (mutNewZoomFactor > maxZoom)
            mutNewZoomFactor = maxZoom
        if (zoomTransitionRunnable != null) {
            // cancel an existing runnable
            zoomTransitionHandler.removeCallbacks(zoomTransitionRunnable!!)
            zoomTransitionRunnable = null
        }
        // problem where we crashed due to calling this function with null camera should be fixed now, but check again just to be safe
        if (cameraController != null) {
            if (this.hasZoom) {
                // don't cancelAutoFocus() here, otherwise we get sluggish zoom behavior on Camera2 API
                mutAllowZoomTransition = mutAllowZoomTransition && usingAndroidL // only for Camera2
                mutAllowZoomTransition =
                    mutAllowZoomTransition && !allowSmoothZoom // only if not smooth zooming
                if (mutAllowZoomTransition && abs(cameraController!!.zoom - mutNewZoomFactor) < 6) {
                    // don't bother with transition if only changing a small amount
                    mutAllowZoomTransition = false
                }
                if (mutAllowZoomTransition) {
                    val startZoomValue = cameraController!!.zoom
                    val targetZoomValue = mutNewZoomFactor
                    //val startZoom = zoom_ratios.get(startZoomValue)/100.0f
                    val startTime = System.currentTimeMillis()
                    val delay = 16L

                    zoomTransitionRunnable = object : Runnable {
                        override fun run() {
                            // check just in case camera is closed or changed to a state where has_zoom==false,
                            // without cancelling the zoom_transition_runnable
                            if (cameraController == null || !hasZoom) {
                                return
                            }
                            val thisZoomValue: Int
                            var time = System.currentTimeMillis() - startTime
                            time += delay // so we have a quicker transition
                            val duration = 200L
                            if (time >= duration) {
                                thisZoomValue = targetZoomValue
                            } else {
                                var alpha = time / duration.toFloat()
                                alpha = alpha.coerceAtMost(1.0f)
                                thisZoomValue =
                                    ((1.0f - alpha) * startZoomValue + alpha * targetZoomValue + 0.5f).toInt()
                            }
                            if (MyDebug.LOG)
                                Log.d(TAG, "ZoomTo runnable, this_zoom_value: $thisZoomValue")
                            cameraController!!.setZoom(thisZoomValue, -1.0f)
                            if (time < duration) {
                                zoomTransitionHandler.postDelayed(this, delay)
                            }
                        }
                    }
                    zoomTransitionRunnable!!.run()
                } else {
                    // if pinch zooming, pass through the "smooth" zoom factor so for Camera2 API we get perfectly smooth zoom, rather than it
                    // being snapped to the discrete zoom values
                    cameraController!!.setZoom(
                        mutNewZoomFactor,
                        if (allowSmoothZoom && hasSmoothZoom) smoothZoom else -1.0f
                    )
                }
                applicationInterface.setZoomPref(mutNewZoomFactor)
                clearFocusAreas()
            }
        }
    }

    fun setFocusDistance(
        newFocusDistance: Float,
        isTargetDistance: Boolean,
        showToast: Boolean
    ) {
        var newFocusDistance = newFocusDistance
        if (MyDebug.LOG) {
            Log.d(TAG, "setFocusDistance: $newFocusDistance")
            Log.d(TAG, "is_target_distance: $isTargetDistance")
        }
        if (cameraController != null) {
            if (newFocusDistance < 0.0f) newFocusDistance = 0.0f
            else if (newFocusDistance > minimumFocusDistance) newFocusDistance =
                minimumFocusDistance
            var focusChanged = false
            if (isTargetDistance) {
                focusChanged = true
                cameraController!!.focusBracketingTargetDistance = newFocusDistance
                // also set the focus distance, so the user can see what the target distance looks like
                cameraController!!.setFocusDistance(newFocusDistance)
                this.isSettingTargetFocusDistance = true
                this.settingTargetFocusDistanceTime = System.currentTimeMillis()
                if (applicationInterface.isFocusBracketingSourceAutoPref()) {
                    // first record the current focus distance, in case needed for taking a photo whilst adjusting the target focus distance
                    cameraController!!.setFocusBracketingSourceDistanceFromCurrent()
                    cameraController!!.focusValue = "focus_mode_manual2"
                }
            } else if (cameraController!!.setFocusDistance(newFocusDistance)) {
                focusChanged = true
                cameraController!!.focusBracketingSourceDistance = newFocusDistance
            }

            if (focusChanged) {
                // now save
                applicationInterface.setFocusDistancePref(newFocusDistance, isTargetDistance)
                if (showToast) {
                    val focusDistanceS: String
                    if (newFocusDistance > 0.0f) {
                        val realFocusDistance = 1.0f / newFocusDistance
                        focusDistanceS =
                            decimalFormat2dpForce0.format(realFocusDistance.toDouble()) + resources.getString(
                                R.string.metres_abbreviation
                            )
                    } else {
                        focusDistanceS = resources.getString(R.string.infinite)
                    }
                    var id: Int = R.string.focus_distance
                    if (this.supportsFocusBracketing && applicationInterface.isFocusBracketingPref()) id =
                        if (isTargetDistance) R.string.focus_bracketing_target_distance else R.string.focus_bracketing_source_distance
                    showToast(resources.getString(id) + " " + focusDistanceS, true)
                }
            }
        }
    }

    fun stoppedSettingFocusDistance(isTargetDistance: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "stoppedSettingFocusDistance")
            Log.d(TAG, "is_target_distance: $isTargetDistance")
        }
        if (isTargetDistance && cameraController != null) {
            if (MyDebug.LOG) Log.d(TAG, "set manual focus distance back to start")
            cameraController!!.setFocusDistance(cameraController!!.focusBracketingSourceDistance)
            this.isSettingTargetFocusDistance = false
            this.settingTargetFocusDistanceTime = System.currentTimeMillis()
            if (applicationInterface.isFocusBracketingSourceAutoPref()) {
                val focusValue: String = applicationInterface.getFocusPref(isVideo)
                if (focusValue.isNotEmpty()) {
                    cameraController!!.focusValue =
                        focusValue // in case using focus bracketing in autofocus mode
                }
            }
        }
    }

    fun setExposure(newExposure: Int) {
        var newExposure = newExposure
        if (MyDebug.LOG) Log.d(TAG, "setExposure(): $newExposure")
        if (cameraController != null && (minExposure != 0 || maxExposure != 0)) {
            cancelAutoFocus()
            if (newExposure < minExposure) newExposure = minExposure
            else if (newExposure > maxExposure) newExposure = maxExposure
            if (cameraController!!.setExposureCompensation(newExposure)) {
                // now save
                applicationInterface.setExposureCompensationPref(newExposure)
                showToast(null, getExposureCompensationString(newExposure), 0, true)
            }
        }
    }

    /** Set a manual white balance temperature. The white balance mode must be set to "manual" for
     * this to have an effect.
     */
    fun setWhiteBalanceTemperature(newTemperature: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "seWhiteBalanceTemperature(): $newTemperature"
        )
        if (cameraController != null) {
            if (cameraController!!.setWhiteBalanceTemperature(newTemperature)) {
                // now save
                applicationInterface.setWhiteBalanceTemperaturePref(newTemperature)
                showToast(
                    null,
                    resources.getString(R.string.white_balance) + " " + newTemperature,
                    0,
                    true
                )
            }
        }
    }

    /** Try to parse the supplied manual ISO value
     * @return The manual ISO value, or -1 if not recognized as a number.
     */
    fun parseManualISOValue(value: String): Int {
        var iso: Int
        try {
            if (MyDebug.LOG) Log.d(TAG, "setting manual iso")
            iso = value.toInt()
            if (MyDebug.LOG) Log.d(TAG, "iso: $iso")
        } catch (_: NumberFormatException) {
            if (MyDebug.LOG) Log.d(TAG, "iso invalid format, can't parse to int")
            iso = -1
        }
        return iso
    }

    fun setISO(newIso: Int) {
        var newIso = newIso
        if (MyDebug.LOG) Log.d(TAG, "setISO(): $newIso")
        if (cameraController != null && supportsIsoRange) {
            if (newIso < minIso) newIso = minIso
            else if (newIso > maxIso) newIso = maxIso
            if (cameraController!!.setISO(newIso)) {
                // now save
                applicationInterface.setISOPref(newIso.toString())
                showToast(null, getISOString(newIso), 0, true)
            }
        }
    }

    fun setExposureTime(newExposureTime: Long) {
        var newExposureTime = newExposureTime
        if (MyDebug.LOG) Log.d(
            TAG,
            "setExposureTime(): $newExposureTime"
        )
        if (cameraController != null && supportsExposureTime) {
            if (newExposureTime < minimumExposureTime) newExposureTime =
                minimumExposureTime
            else if (newExposureTime > maximumExposureTime) newExposureTime =
                maximumExposureTime
            if (cameraController!!.setExposureTime(newExposureTime)) {
                // now save
                applicationInterface.setExposureTimePref(newExposureTime)
                showToast(null, getExposureTimeString(newExposureTime), 0, true)
            }
        }
    }

    fun getExposureCompensationString(exposure: Int): String {
        val exposureEv = exposure * exposureStep
        // show a "+" even for exactly 0, so that we have a consistent text length (useful for the toast when adjusting the exposure compensation slider)
        return resources.getString(R.string.exposure_compensation) + " " + (if (exposure >= 0) "+" else "") + decimalFormat2dpForce0.format(
            exposureEv.toDouble()
        ) + " EV"
    }

    fun getISOString(iso: Int): String {
        return resources.getString(R.string.iso) + " " + iso
    }

    fun getExposureTimeString(exposureTime: Long): String {
        /*if( MyDebug.LOG )
            Log.d(TAG, "getExposureTimeString(): " + exposureTime);*/
        val exposureTimeS = exposureTime / 1000000000.0
        val string: String
        if (exposureTime > 100000000) {
            // show exposure times of more than 0.1s directly
            string =
                decimalFormat1dp.format(exposureTimeS) + resources.getString(R.string.seconds_abbreviation)
        } else {
            val exposureTimeR = 1.0 / exposureTimeS
            string =
                " 1/" + (exposureTimeR + 0.5).toInt() + resources.getString(R.string.seconds_abbreviation)
        }
        /*if( MyDebug.LOG )
            Log.d(TAG, "getExposureTimeString() return: " + string);*/
        return string
    }

    fun getFrameDurationString(frameDuration: Long): String {
        val frameDurationS = frameDuration / 1000000000.0
        val frameDurationR = 1.0 / frameDurationS
        return resources.getString(R.string.fps) + " " + decimalFormat1dp.format(frameDurationR)
    }

    /*private String getFocusOneDistanceString(float dist) {
		if( dist == 0.0f )
			return "inf.";
		float realDist = 1.0f/dist;
		return decimal_format_2dp.format(realDist) + getResources().getString(R.string.metres_abbreviation);
	}

	public String getFocusDistanceString(float distMin, float distMax) {
		String fS = "f ";
		//if( distMin == distMax )
		//	return fS + getFocusOneDistanceString(distMin);
		//return fS + getFocusOneDistanceString(distMin) + "-" + getFocusOneDistanceString(distMax);
		// just always show max for now
		return fS + getFocusOneDistanceString(distMax);
	}*/
    fun canSwitchCamera(): Boolean {
        if (this.phase == PHASE_TAKING_PHOTO || this.isVideoRecording) {
            // just to be safe - risk of cancelling the autofocus before taking a photo, or otherwise messing things up
            if (MyDebug.LOG) Log.d(TAG, "currently taking a photo")
            return false
        }
        val nCameras: Int = cameraControllerManager.numberOfCameras
        if (MyDebug.LOG) Log.d(TAG, "found $nCameras cameras")
        if (nCameras == 0) return false
        return true
    }

    fun setCamera(cameraId: Int, cameraIdSPhysical: String?) {
        var cameraId = cameraId
        if (MyDebug.LOG) Log.d(
            TAG,
            "setCamera(): $cameraId / $cameraIdSPhysical"
        )
        if (cameraId < 0 || cameraId >= cameraControllerManager.numberOfCameras) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "invalid cameraId: $cameraId"
            )
            cameraId = 0
        }
        if (cameraOpenState == CameraOpenState.CAMERAOPENSTATE_OPENING) {
            if (MyDebug.LOG) Log.d(TAG, "already opening camera in background thread")
            return
        }
        if (canSwitchCamera()) {
            /*closeCamera(false, null);
			applicationInterface.setCameraIdPref(cameraId);
			this.OpenKamera();*/
            closeCamera(true, object : CloseCameraCallback {
                override fun onClosed() {
                    if (MyDebug.LOG) Log.d(TAG, "CloseCameraCallback.onClosed")
                    applicationInterface.setCameraIdPref(cameraId, cameraIdSPhysical)
                    openCamera()
                }
            })
        }
    }

    /* It's important to set a preview FPS using chooseBestPreviewFps() rather than just leaving it to the default, as some devices
     * have a poor choice of default - e.g., Nexus 5 and Nexus 6 on original Camera API default to (15000, 15000), which means very dark
     * preview and photos in low light, as well as a less smooth framerate in good light.
     * See http://stackoverflow.com/questions/18882461/why-is-the-default-android-camera-preview-smoother-than-my-own-camera-preview .
     */
    private fun setPreviewFps() {
        if (MyDebug.LOG) Log.d(TAG, "setPreviewFps()")
        val profile: VideoProfile = videoProfile
        val fpsRanges: List<IntArray>? = cameraController!!.supportedPreviewFpsRange
        if (fpsRanges.isNullOrEmpty()) {
            if (MyDebug.LOG) Log.d(TAG, "fps_ranges not available")
            return
        }
        var selectedFps: IntArray? = null
        if (cameraController!!.isCameraExtension) {
            // don't set preview fps if using camera extension
            // (important not to return here however - still want to call
            // camera_controller.clearPreviewFpsRange() to clear a previously set fps)
        } else if (this.isVideo) {
            // For Nexus 5 and Nexus 6, we need to set the preview fps using matchPreviewFpsToVideo to avoid problem of dark preview in low light, as described above.
            // When the video recording starts, the preview automatically adjusts, but still good to avoid too-dark preview before the user starts recording.
            // However, I'm wary of changing the behavior for all devices at the moment, since some devices can be
            // very picky about what works when it comes to recording video - e.g., corruption in preview or resultant video.
            // So for now, I'm just fixing the Nexus 5/6 behavior without changing behavior for other devices. Later we can test on other devices, to see if we can
            // use chooseBestPreviewFps() more widely.
            // Update for v1.31: we no longer seem to need this - I no longer get a dark preview in photo or video mode if we don't set the fps range;
            // but leaving the code as it is, to be safe.
            // Update for v1.43: implementing setPreviewFpsRange() for CameraController2 caused the dark preview problem on
            // OnePlus 3T. So enable the previewTooDark for all devices on Camera2.
            // Update for v1.43.3: had reports of problems (e.g., setting manual mode with video on camera2) since 1.43. It's unclear
            // if there is any benefit to setting the preview fps when we aren't requesting a specific fps value, so seems safest to
            // revert to the old behavior (where CameraController2.setPreviewFpsRange() did nothing).
            val previewTooDark =
                usingAndroidL || Build.MODEL == "Nexus 5" || Build.MODEL == "Nexus 6"
            val fpsValue: String = applicationInterface.getVideoFPSPref()
            if (MyDebug.LOG) {
                Log.d(TAG, "preview_too_dark? $previewTooDark")
                Log.d(TAG, "fps_value: $fpsValue")
            }
            if (fpsValue == "default" && usingAndroidL) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "don't set preview fps for camera2 and default fps video"
                )
            } else if (fpsValue == "default" && previewTooDark) {
                selectedFps = chooseBestPreviewFps(fpsRanges)
            } else {
                selectedFps =
                    matchPreviewFpsToVideo(fpsRanges, (profile.videoCaptureRate * 1000).toInt())
            }
        } else {
            // note that setting a fps here in continuous video focus mode causes preview to not restart after taking a photo on Galaxy Nexus
            // but, we need to do this, to get good light for Nexus 5 or 6
            // we could hard-code behavior like we do for video, but this is the same way that Google Camera chooses preview fps for photos,
            // or I could hard-code behavior for Galaxy Nexus, but since it's an old device (and an obscure bug anyway - most users don't really need continuous focus in photo mode), better to live with the bug rather than complicating the code
            // Update for v1.29: this doesn't seem to happen on Galaxy Nexus with continuous picture focus mode, which is what we now use
            // Update for v1.31: we no longer seem to need this for old API - I no longer get a dark preview in photo or video mode if we don't set the fps range;
            // but leaving the code as it is, to be safe.
            // Update for v1.43.3: as noted above, setPreviewFpsRange() was implemented for CameraController2 in v1.43, but no evidence this
            // is needed for anything, so thinking about it, best to keep things as they were before for Camera2
            if (usingAndroidL) {
                if (MyDebug.LOG) Log.d(TAG, "don't set preview fps for camera2 and photo")
            } else {
                selectedFps = chooseBestPreviewFps(fpsRanges)
            }
        }
        if (selectedFps != null) {
            if (MyDebug.LOG) Log.d(TAG, "set preview fps range: " + selectedFps.contentToString())
            cameraController!!.setPreviewFpsRange(selectedFps[0], selectedFps[1])
        } else if (usingAndroidL) {
            cameraController!!.clearPreviewFpsRange()
        }
    }

    fun switchVideo(duringStartup: Boolean, changeUserPref: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "switchVideo()")
        if (cameraController == null && duringStartup) {
            // if duringStartup==false at least, we should allow switching to/from video mode if
            // camera failed to open (it may be that the failure to open is specific to video mode
            // for example, so should allow user to switch back to photo mode - e.g., setting
            // video profile to sRGB on Pixel 6 Pro)
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
        if (!isVideo && !supportsVideo) {
            if (MyDebug.LOG) Log.d(TAG, "video not supported")
            return
        }
        val oldIsVideo = isVideo
        if (this.isVideo) {
            if (videoRecorder != null) {
                stopVideo(false)
            }
            this.isVideo = false
        } else {
            if (this.isOnTimer) {
                cancelTimer()
                this.isVideo = true
            } else if (this.phase == PHASE_TAKING_PHOTO) {
                // wait until photo taken
                if (MyDebug.LOG) Log.d(TAG, "wait until photo taken")
            } else {
                this.isVideo = true
            }
        }

        if (isVideo != oldIsVideo) {
            setFocusPref(false) // first restore the saved focus for the new photo/video mode; don't do autofocus, as it'll be canceled when restarting preview

            /*if( !isVideo ) {
				// changing from video to photo mode
				setFocusPref(false); // first restore the saved focus for the new photo/video mode; don't do autofocus, as it'll be canceled when restarting preview
			}*/
            if (changeUserPref) {
                // now save
                applicationInterface.setVideoPref(isVideo)
            }
            if (!duringStartup) {
                // if during startup, updateFlashForVideo() needs to always be explicitly called anyway
                updateFlashForVideo()
            }

            if (!duringStartup) {
                if (MyDebug.LOG) {
                    val focusValue =
                        if (currentFocusIndex != -1) supportedFocusValues!![currentFocusIndex] else null
                    Log.d(TAG, "focus_value is $focusValue")
                }
                // Although in theory we only need to stop and start preview, which should be faster, reopening the camera allows that to
                // run on the background thread, thus not freezing the UI
                // Also workaround for bug on Nexus 6 at least where switching to video and back to photo mode causes continuous picture mode to stop -
                // at the least, we need to reOpen Kamera when: ( !isVideo && focusValue != null && focus_value.equals("focus_mode_continuous_picture") ).
                // Lastly, note that it's important to still call setupCamera() when switching between photo and video modes (see comment for setupCamera()).
                // So if we ever allow stopping/starting the preview again, we still need to call setupCamera() again.
                // Update: and even if we want to go back to just stopping/starting the preview, it's likely still a good idea to reopen the camera when
                // switching from/to vendor camera extensions, otherwise risk of hangs/crashes on at least some devices (see note in MainActivity.updateForSettings)
                this.reOpenKamera()
            }

            /*if( isVideo ) {
				// changing from photo to video mode
				setFocusPref(false);
			}*/
            if (isVideo) {
                if (applicationInterface.getRecordAudioPref()) {
                    // check for audio permission now, rather than when user starts video recording
                    // we restrict the checks to Android 6 or later just in case, see note in LocationSupplier.setupLocationListener()
                    // only request permission if record audio preference is enabled
                    if (MyDebug.LOG) Log.d(TAG, "check for record audio permission")
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        if (MyDebug.LOG) Log.d(TAG, "record audio permission not available")
                        applicationInterface.requestRecordAudioPermission()
                        // we can now carry on - if the user starts recording video, we'll check then if the permission was granted
                    }
                }
            }
        }
    }

    private fun focusIsVideo(): Boolean {
        if (cameraController != null) {
            return cameraController!!.focusIsVideo()
        }
        return false
    }

    fun setFocusPref(autoFocus: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "setFocusPref()")
        val focusValue: String = applicationInterface.getFocusPref(isVideo)
        if (focusValue.isNotEmpty()) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "found existing focus_value: $focusValue"
            )
            if (!updateFocus(
                    focusValue = focusValue,
                    quiet = true,
                    save = false,
                    autoFocus = autoFocus
                )
            ) { // don't need to save, as this is the value that's already saved
                if (MyDebug.LOG) Log.d(TAG, "focus value no longer supported!")
                // don't save, as we may be in a temporary mode where the saved focus isn't supported - e.g., this could happen if switching to a specific physical camera
                updateFocus(newFocusIndex = 0, quiet = true, save = false, autoFocus = autoFocus)
            }
        } else {
            if (MyDebug.LOG) Log.d(TAG, "found no existing focus_value")
            // here we set the default values for focus mode
            // note if updating default focus value for photo mode, also update MainActivityTest.setToDefault()
            if (!updateFocus(
                    focusValue = if (isVideo) "focus_mode_continuous_video" else "focus_mode_continuous_picture",
                    quiet = true,
                    save = true,
                    autoFocus = autoFocus
                )
            ) {
                if (MyDebug.LOG) Log.d(TAG, "continuous focus not supported, so fall back to first")
                updateFocus(newFocusIndex = 0, quiet = true, save = true, autoFocus = autoFocus)
            }
        }
    }

    /** If in video mode, update the focus mode if necessary to be continuous video focus mode (if that mode is available).
     * Normally we remember the user-specified focus value. And even setting the default is done in setFocusPref().
     * This method is used as a workaround for a bug on Samsung Galaxy S5 with UHD, where if the user switches to another
     * (non-continuous-video) focus mode, then goes to Settings, then returns and records video, the preview freezes and the
     * video is corrupted.
     * @return If the focus mode is changed, this returns the previous focus mode; else it returns null.
     */
    private fun updateFocusForVideo(): String? {
        if (MyDebug.LOG) Log.d(TAG, "updateFocusForVideo()")
        var oldFocusMode: String? = null
        if (this.supportedFocusValues != null && cameraController != null && this.isVideo) {
            val focusIsVideo = focusIsVideo()
            if (MyDebug.LOG) {
                Log.d(TAG, "focus_is_video: $focusIsVideo , is_video: $isVideo")
            }
            if (focusIsVideo != isVideo) {
                if (MyDebug.LOG) Log.d(TAG, "need to change focus mode")
                oldFocusMode = this.currentFocusValue
                updateFocus(
                    focusValue = "focus_mode_continuous_video",
                    quiet = true,
                    save = false,
                    autoFocus = false
                ) // don't save, as we're just changing focus mode temporarily for the Samsung S5 video hack
            }
        }
        return oldFocusMode
    }

    /** If we've switched to video mode, ensures that we're not in a flash mode other than torch.
     * This only changes the internal user setting, we don't tell the application interface to change
     * the flash mode.
     */
    private fun updateFlashForVideo() {
        if (MyDebug.LOG) Log.d(TAG, "updateFlashForVideo()")
        if (isVideo) {
            // check flash is not auto or on
            val currentFlash = currentFlashValue
            if (currentFlash != null && !isFlashSupportedForVideo(currentFlash)) {
                if (MyDebug.LOG) Log.d(TAG, "disable flash for video mode")
                currentFlashIndex = -1 // reset to initial, to prevent toast from showing
                updateFlash("flash_off", false)
            }
        }
    }

    fun getErrorFeatures(profile: VideoProfile): String {
        var was4k = false
        var wasBitrate = false
        var wasFps = false
        var wasSlowMotion = false
        if (profile.videoFrameWidth === 3840 && profile.videoFrameHeight === 2160 && applicationInterface.getForce4KPref()) {
            was4k = true
        }
        val bitrateValue: String = applicationInterface.getVideoBitratePref()
        if (bitrateValue != "default") {
            wasBitrate = true
        }
        val fpsValue: String = applicationInterface.getVideoFPSPref()
        if (applicationInterface.getVideoCaptureRateFactor() < 1.0f - 1.0e-5f) {
            wasSlowMotion = true
        } else if (fpsValue != "default") {
            wasFps = true
        }
        var features = ""
        if (was4k || wasBitrate || wasFps || wasSlowMotion) {
            if (was4k) {
                features = context.resources.getString(R.string.error_features_4k)
            }
            if (wasBitrate) {
                if (features.isEmpty()) features =
                    context.resources.getString(R.string.error_features_bitrate)
                else features += "/" + context.resources.getString(R.string.error_features_bitrate)
            }
            if (wasFps) {
                if (features.isEmpty()) features =
                    context.resources.getString(R.string.error_features_frame_rate)
                else features += "/" + context.resources.getString(R.string.error_features_frame_rate)
            }
            if (wasSlowMotion) {
                if (features.isEmpty()) features =
                    context.resources.getString(R.string.error_features_slow_motion)
                else features += "/" + context.resources.getString(R.string.error_features_slow_motion)
            }
        }
        return features
    }

    fun updateFlash(flashValue: String) {
        if (MyDebug.LOG) Log.d(TAG, "updateFlash(): $flashValue")
        if (this.phase == PHASE_TAKING_PHOTO && !isVideo) {
            // just to be safe - risk of cancelling the autofocus before taking a photo, or otherwise messing things up
            if (MyDebug.LOG) Log.d(TAG, "currently taking a photo")
            return
        }
        updateFlash(flashValue, true)
    }

    private fun updateFlash(flashValue: String, save: Boolean): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "updateFlash(): $flashValue")
        if (supportedFlashValues != null) {
            val newFlashIndex = supportedFlashValues!!.indexOf(flashValue)
            if (MyDebug.LOG) Log.d(
                TAG,
                "new_flash_index: $newFlashIndex"
            )
            if (newFlashIndex != -1) {
                updateFlash(newFlashIndex, save)
                return true
            }
        }
        return false
    }

    fun cycleFlash(skipTorch: Boolean, save: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "cycleFlash()")
        if (supportedFlashValues != null) {
            var newFlashIndex = (currentFlashIndex + 1) % supportedFlashValues!!.size
            val startIndex = newFlashIndex
            var done = false
            while (!done) {
                done = true

                if (skipTorch && supportedFlashValues!![newFlashIndex] == "flash_torch") {
                    if (MyDebug.LOG) Log.d(TAG, "cycle past torch")
                    newFlashIndex = (newFlashIndex + 1) % supportedFlashValues!!.size
                    // don't bother setting done to false as we shouldn't have two torches in a row...
                }

                if (isVideo) {
                    // check supported for video
                    val newFlashValue = supportedFlashValues!![newFlashIndex]
                    if (!isFlashSupportedForVideo(newFlashValue)) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "cycle past flash mode not supported for video: $newFlashValue"
                        )
                        newFlashIndex = (newFlashIndex + 1) % supportedFlashValues!!.size
                        done = false
                    }
                }

                if (!done && newFlashIndex == startIndex) {
                    // just in case, prevent infinite loop
                    Log.e(TAG, "flash looped to start - couldn't find valid flash!")
                    break
                }
            }

            if (done) {
                updateFlash(newFlashIndex, save)
            }
        }
    }

    private fun updateFlash(newFlashIndex: Int, save: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "updateFlash(): $newFlashIndex"
        )
        // updates the Flash button, and Flash camera mode
        if (supportedFlashValues != null && newFlashIndex != currentFlashIndex) {
            val initial = currentFlashIndex == -1
            currentFlashIndex = newFlashIndex
            if (MyDebug.LOG) Log.d(
                TAG,
                "    current_flash_index is now $currentFlashIndex (initial $initial)"
            )

            //Activity activity = (Activity)this@Preview.context;
            val flashEntries = resources.getStringArray(R.array.flash_entries)
            //String [] flashIcons = getResources().getStringArray(R.array.flash_icons);
            val flashValue = supportedFlashValues!![currentFlashIndex]
            if (MyDebug.LOG) Log.d(
                TAG,
                "    flash_value: $flashValue"
            )
            val flashValues = resources.getStringArray(R.array.flash_values)
            for (i in flashValues.indices) {
                /*if( MyDebug.LOG )
					Log.d(TAG, "    compare to: " + flashValues[i]);*/
                if (flashValue == flashValues[i]) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "    found entry: $i"
                    )
                    if (!initial) {
                        showToast(focusFlashToast, flashEntries[i], true)
                    }
                    break
                }
            }
            this.setFlash(flashValue)
            if (save) {
                // now save
                applicationInterface.setFlashPref(flashValue)
            }
        }
    }

    private fun setFlash(flashValue: String) {
        if (MyDebug.LOG) Log.d(TAG, "setFlash() $flashValue")
        setFlashValueAfterAutofocus =
            "" // this overrides any previously saved setting, for during the startup autofocus
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
        cancelAutoFocus()
        cameraController!!.flashValue = flashValue
    }

    val currentFlashValue: String?
        // this returns the flash value indicated by the UI, rather than from the camera parameters (maybe different, e.g., in startup autofocus!)
        get() {
            if (this.currentFlashIndex == -1) return null
            return supportedFlashValues!![currentFlashIndex]
        }

    // this returns the flash mode indicated by the UI, rather than from the camera parameters (maybe different, e.g., in startup autofocus!)
    /*public String getCurrentFlashMode() {
		if( currentFlashIndex == -1 )
			return null;
		String flashValue = supported_flash_values.get(currentFlashIndex);
		String flashMode = convertFlashValueToMode(flashValue);
		return flashMode;
	}*/
    fun updateFocus(focusValue: String, quiet: Boolean, autoFocus: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "updateFocus(): $focusValue")
        if (this.phase == PHASE_TAKING_PHOTO) {
            // just to be safe - otherwise problem that changing the focus mode will cancel the autofocus before taking a photo, so we never take a photo, but isTakingPhoto remains true!
            if (MyDebug.LOG) Log.d(TAG, "currently taking a photo")
            return
        }
        updateFocus(focusValue, quiet, true, autoFocus)
    }

    private fun supportedFocusValue(focusValue: String): Boolean {
        if (MyDebug.LOG) Log.d(
            TAG,
            "supportedFocusValue(): $focusValue"
        )
        if (this.supportedFocusValues != null) {
            val newFocusIndex = supportedFocusValues!!.indexOf(focusValue)
            if (MyDebug.LOG) Log.d(
                TAG,
                "new_focus_index: $newFocusIndex"
            )
            return newFocusIndex != -1
        }
        return false
    }

    private fun updateFocus(
        focusValue: String,
        quiet: Boolean,
        save: Boolean,
        autoFocus: Boolean
    ): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "updateFocus(): $focusValue")
        if (this.supportedFocusValues != null) {
            val newFocusIndex = supportedFocusValues!!.indexOf(focusValue)
            if (MyDebug.LOG) Log.d(
                TAG,
                "new_focus_index: $newFocusIndex"
            )
            if (newFocusIndex != -1) {
                updateFocus(newFocusIndex, quiet, save, autoFocus)
                return true
            }
        }
        return false
    }

    private fun findEntryForValue(value: String, entriesId: Int, valuesId: Int): String? {
        val entries = resources.getStringArray(entriesId)
        val values = resources.getStringArray(valuesId)
        for (i in values.indices) {
            if (MyDebug.LOG) Log.d(TAG, "    compare to value: " + values[i])
            if (value == values[i]) {
                if (MyDebug.LOG) Log.d(TAG, "    found entry: $i")
                return entries[i]
            }
        }
        return null
    }

    fun findFocusEntryForValue(focusValue: String): String? {
        return findEntryForValue(focusValue, R.array.focus_mode_entries, R.array.focus_mode_values)
    }

    private fun updateFocus(
        newFocusIndex: Int,
        quiet: Boolean,
        save: Boolean,
        autoFocus: Boolean
    ) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "updateFocus(): $newFocusIndex current_focus_index: $currentFocusIndex"
        )
        // updates the Focus button, and Focus camera mode
        if (this.supportedFocusValues != null && newFocusIndex != currentFocusIndex) {
            currentFocusIndex = newFocusIndex
            if (MyDebug.LOG) Log.d(
                TAG,
                "    current_focus_index is now $currentFocusIndex"
            )

            val focusValue = supportedFocusValues!![currentFocusIndex]
            if (MyDebug.LOG) Log.d(
                TAG,
                "    focus_value: $focusValue"
            )
            if (!quiet) {
                val focusEntry = findFocusEntryForValue(focusValue)
                if (focusEntry != null) {
                    showToast(focusFlashToast, focusEntry, true)
                }
            }
            this.setFocusValue(focusValue, autoFocus)

            if (save) {
                // now save
                applicationInterface.setFocusPref(focusValue, isVideo)
            }
        }
    }

    val currentFocusValue: String?
        /** This returns the flash mode indicated by the UI, rather than from the camera parameters.
         */
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getCurrentFocusValue()")
            if (cameraController == null) {
                if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
                return null
            }
            if (this.supportedFocusValues != null && this.currentFocusIndex != -1) return supportedFocusValues!![currentFocusIndex]
            return null
        }

    private fun setFocusValue(focusValue: String, autoFocus: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "setFocusValue() $focusValue")
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
        cancelAutoFocus()
        removePendingContinuousFocusReset() // this isn't strictly needed as the resetContinuousFocusRunnable will check the ui focus mode when it runs, but good to remove it anyway
        autofocusInContinuousMode = false
        cameraController!!.focusValue = focusValue
        setupContinuousFocusMove()
        clearFocusAreas()
        if (autoFocus && focusValue != "focus_mode_locked") {
            tryAutoFocus(startup = false, manual = false)
        }
    }

    private fun setupContinuousFocusMove() {
        if (MyDebug.LOG) Log.d(TAG, "setupContinuousFocusMove()")
        if (continuousFocusMoveIsStarted) {
            continuousFocusMoveIsStarted = false
            applicationInterface.onContinuousFocusMove(false)
        }
        val focusValue =
            if (currentFocusIndex != -1) supportedFocusValues!![currentFocusIndex] else null
        if (MyDebug.LOG) Log.d(TAG, "focus_value is $focusValue")
        if (cameraController != null && focusValue != null && focusValue == "focus_mode_continuous_picture" && !this.isVideo) {
            if (MyDebug.LOG) Log.d(TAG, "set continuous picture focus move callback")
            cameraController!!.setContinuousFocusMoveCallback(object :
                CameraController.ContinuousFocusMoveCallback {
                override fun onContinuousFocusMove(start: Boolean) {
                    if (start != continuousFocusMoveIsStarted) { // filter out repeated calls with same start value
                        continuousFocusMoveIsStarted = start
                        countCameraContinuousFocusMoving++
                        applicationInterface.onContinuousFocusMove(start)
                    }
                }
            })
        } else if (cameraController != null) {
            if (MyDebug.LOG) Log.d(TAG, "remove continuous picture focus move callback")
            cameraController!!.setContinuousFocusMoveCallback(null)
        }
    }

    fun toggleWhiteBalanceLock() {
        if (MyDebug.LOG) Log.d(TAG, "toggleWhiteBalanceLock()")
        if (this.phase == PHASE_TAKING_PHOTO) {
            // just to be safe
            if (MyDebug.LOG) Log.d(TAG, "currently taking a photo")
            return
        }
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
        if (isWhiteBalanceLockSupported) {
            isWhiteBalanceLocked = !isWhiteBalanceLocked
            cancelAutoFocus()
            cameraController!!.autoWhiteBalanceLock = isWhiteBalanceLocked
        }
    }

    fun toggleExposureLock() {
        if (MyDebug.LOG) Log.d(TAG, "toggleExposureLock()")
        if (this.phase == PHASE_TAKING_PHOTO) {
            // just to be safe
            if (MyDebug.LOG) Log.d(TAG, "currently taking a photo")
            return
        }
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            return
        }
        if (isExposureLockSupported) {
            isExposureLocked = !isExposureLocked
            cancelAutoFocus()
            cameraController!!.autoExposureLock = isExposureLocked
        }
    }

    /** User has clicked the "take picture" button (or equivalent GUI operation).
     * @param photoSnapshot If true, then the user has requested taking a photo whilst video
     * recording. If false, either take a photo or start/stop video depending
     * on the current mode.
     * @param continuousFastBurst If true, then start a continuous fast burst.
     */
    fun takePicturePressed(photoSnapshot: Boolean, continuousFastBurst: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "takePicturePressed")
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            this.phase = PHASE_NORMAL
            return
        }
        if (!this.hasSurface) {
            if (MyDebug.LOG) Log.d(TAG, "preview surface not yet available")
            this.phase = PHASE_NORMAL
            return
        }
        if (isVideo && continuousFastBurst) {
            Log.e(TAG, "continuous_fast_burst not supported for video mode")
            this.phase = PHASE_NORMAL
            return
        }
        if (this.isOnTimer) {
            cancelTimer()
            showToast(takePhotoToast, R.string.cancelled_timer, true)
            return
        }
        //if( !photoSnapshot && this.phase == PHASE_TAKING_PHOTO ) {
        //if( (isVideo && isVideoRecording && !photoSnapshot) || this.phase == PHASE_TAKING_PHOTO ) {
        if (isVideo && isVideoRecording && !photoSnapshot) {
            // user requested stop video
            if (!videoStartTimeSet || System.currentTimeMillis() - videoStartTime < 500) {
                // if user presses to stop too quickly, we ignore
                // firstly to reduce risk of corrupt video files when stopping too quickly (see RuntimeException we have to catch in stopVideo),
                // secondly, to reduce a backlog of events which slows things down, if user presses start/stop repeatedly too quickly
                if (MyDebug.LOG) Log.d(TAG, "ignore pressing stop video too quickly after start")
            } else {
                stopVideo(false)
            }
            return
        } else if ((!isVideo || photoSnapshot) && this.phase == PHASE_TAKING_PHOTO) {
            // user requested take photo while already taking photo
            if (MyDebug.LOG) Log.d(TAG, "already taking a photo")
            if (remainingRepeatPhotos != 0) {
                cancelRepeat()
                showToast(takePhotoToast, R.string.cancelled_repeat_mode, true)
            } else if (!isVideo && cameraController?.burstType === CameraController.BurstType.BURSTTYPE_FOCUS && cameraController?.isCapturingBurst == true) {
                cameraController?.stopFocusBracketingBurst()
                showToast(takePhotoToast, R.string.cancelled_focus_bracketing, true)
            }
            return
        }

        if (!isVideo || photoSnapshot) {
            // check it's okay to take a photo
            if (!applicationInterface.canTakeNewPhoto()) {
                if (MyDebug.LOG) Log.d(TAG, "don't take another photo, queue is full")
                //showToast(takePhotoToast, "Still processing...");
                return
            }
        }

        // make sure that preview running (also needed to hide trash/share icons)
        this.startCameraPreview()

        if (photoSnapshot || continuousFastBurst) {
            // go straight to taking a photo, ignore timer or repeat options
            takePicture(false, photoSnapshot, continuousFastBurst)
            return
        }

        val timerDelay: Long = applicationInterface.getTimerPref()

        val repeatModeValue: String = applicationInterface.getRepeatPref()
        if (repeatModeValue == "unlimited") {
            if (MyDebug.LOG) Log.d(TAG, "unlimited repeat")
            remainingRepeatPhotos = -1
        } else {
            var nRepeat: Int
            try {
                nRepeat = repeatModeValue.toInt()
                if (MyDebug.LOG) Log.d(TAG, "n_repeat: $nRepeat")
            } catch (e: NumberFormatException) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "failed to parse repeat_mode value: $repeatModeValue"
                )
                e.printStackTrace()
                nRepeat = 1
            }
            remainingRepeatPhotos = nRepeat - 1
        }

        if (timerDelay == 0L) {
            takePicture(false, photoSnapshot, continuousFastBurst)
        } else {
            takePictureOnTimer(timerDelay, false)
        }
        if (MyDebug.LOG) Log.d(TAG, "takePicturePressed exit")
    }

    private fun takePictureOnTimer(timerDelay: Long, repeated: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "takePictureOnTimer")
            Log.d(TAG, "timer_delay: $timerDelay")
        }
        this.phase = PHASE_TIMER
        class TakePictureTimerTask : TimerTask() {
            override fun run() {
                if (beepTimerTask != null) {
                    beepTimerTask!!.cancel()
                    beepTimerTask = null
                }
                val activity = context as Activity
                activity.runOnUiThread { // we run on main thread to avoid problem of camera closing at the same time
                    // but still need to check that the camera hasn't closed or the task halted, since TimerTask.run() started
                    if (cameraController != null && takePictureTimerTask != null) takePicture(
                        maxFilesizeRestart = false,
                        photoSnapshot = false,
                        continuousFastBurst = false
                    )
                    else {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "takePictureTimerTask: don't take picture, as already cancelled"
                        )
                    }
                }
            }
        }
        timerEndTime = System.currentTimeMillis() + timerDelay
        if (MyDebug.LOG) Log.d(TAG, "take photo at: $timerEndTime")
        /*if( !repeated ) {
			showToast(takePhotoToast, R.string.started_timer);
		}*/
        takePictureTimer.schedule(
            TakePictureTimerTask().also { takePictureTimerTask = it },
            timerDelay
        )

        class BeepTimerTask : TimerTask() {
            private var remainingTime = timerDelay
            override fun run() {
                if (remainingTime > 0) { // check in case this isn't canceled by time we take the photo
                    applicationInterface.timerBeep(remainingTime)
                }
                remainingTime -= 1000
            }
        }
        beepTimer.schedule(BeepTimerTask().also { beepTimerTask = it }, 0, 1000)
    }

    private fun flashVideo() {
        if (MyDebug.LOG) Log.d(TAG, "flashVideo")
        // getFlashValue() may return "" if flash not supported!
        val flashValue: String = cameraController!!.flashValue
        if (flashValue.isEmpty()) return
        val flashValueUi = currentFlashValue ?: return
        if (flashValueUi == "flash_torch") return
        if (flashValue == "flash_torch") {
            // shouldn't happen? but set to what the UI is
            cancelAutoFocus()
            cameraController!!.flashValue = flashValueUi
            return
        }
        // turn on torch
        cancelAutoFocus()
        cameraController!!.flashValue = "flash_torch"
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        // turn off torch
        cancelAutoFocus()
        cameraController!!.flashValue = flashValueUi
    }

    private fun onVideoInfo(what: Int, extra: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onVideoInfo: $what extra: $extra"
        )
        // n.b., we shouldn't refactor "Build.VERSION.SDK_INT >= Build.VERSION_CODES.O" to a single variable, as it means we'll then get the Android
        // warnings of "Call requires API level 26"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING && videoRestartOnMaxFilesize) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "seamless restart due to max filesize approaching - try setNextOutputFile"
            )
            if (videoRecorder == null) {
                // just in case?
                if (MyDebug.LOG) Log.d(TAG, "video_recorder is null!")
            } else if (applicationInterface.getVideoMaxDurationPref() > 0) {
                if (MyDebug.LOG) Log.d(TAG, "don't use setNextOutputFile with setMaxDuration")
                // using setNextOutputFile with setMaxDuration seems to be buggy:
                // OnePlus3T: setMaxDuration is ignored if we hit max filesize and call setNextOutputFile before
                // this would cause testTakeVideoMaxFileSize3 to fail
                // Nokia 8: the camera server dies when restarting with setNextOutputFile, if setMaxDuration has been set!
            } else {
                // First we need to see if there's enough free storage left - it might be that we hit the max filesize that was
                // set in MyApplicationInterface.getVideoMaxFileSizePref() due to the remaining disk space.
                // Potentially we could just modify getVideoMaxFileSizePref() to not set VideoMaxFileSize.autoRestart if the
                // max file size was set due to remaining disk space rather than user preference, but worth rechecking in case
                // disk space has been freed up; also we might encounter a device limit on max filesize that's less than the
                // remaining disk space (in which case, we do want to restart).
                // See testTakeVideoAvailableMemory().
                var hasFreeSpace = false
                try {
                    // don't care about the return, we're just looking for NoFreeStorageException
                    applicationInterface.getVideoMaxFileSizePref()
                    hasFreeSpace = true
                } catch (_: NoFreeStorageException) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "don't call setNextOutputFile, not enough space remaining"
                    )
                }

                val profile: VideoProfile = videoProfile
                if (profile.fileExtension == "3gp") {
                    // at least on Nokia 8 with Camera2, 3gpp format crashes with IllegalStateException in setNextOutputFile below
                    // if we try to do seamless restart
                    if (MyDebug.LOG) Log.d(TAG, "seamless restart not supported for 3gpp")
                } else if (hasFreeSpace) {
                    val info = createVideoFile(profile.fileExtension)
                    // only assign to videoFileInfo after setNextOutputFile in case it throws an exception (in which case,
                    // we don't want to overwrite the current videoFileInfo).
                    if (info != null) {
                        try {
                            //if( true )
                            //	throw new IOException(); // test
                            if (info.videoMethod === ApplicationInterface.VideoMethod.FILE) {
                                videoRecorder!!.setNextOutputFile(File(info.videoFilename))
                            } else {
                                videoRecorder!!.setNextOutputFile(info.videoPfdSaf!!.fileDescriptor)
                            }
                            if (MyDebug.LOG) Log.d(TAG, "setNextOutputFile succeeded")
                            testCalledNextOutputFile = true
                            nextVideoFileInfo = info
                        } catch (e: IOException) {
                            Log.e(TAG, "failed to setNextOutputFile")
                            e.printStackTrace()
                            info.close()
                        }
                    }
                }
            }
            // no need to explicitly stop if createVideoFile() or setNextOutputFile() fails - just let video reach max filesize
            // normally
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && what == MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED && videoRestartOnMaxFilesize) {
            if (MyDebug.LOG) Log.d(TAG, "seamless restart with setNextOutputFile has now occurred")
            if (nextVideoFileInfo == null) {
                Log.e(
                    TAG,
                    "received MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED but nextVideoFileInfo is null"
                )
            } else {
                videoFileInfo.close()
                videoTimeLastMaxfilesizeRestart = getVideoTime(false)
                applicationInterface.restartedVideo(
                    videoFileInfo.videoMethod,
                    videoFileInfo.videoUri,
                    videoFileInfo.videoFilename
                )
                videoFileInfo = nextVideoFileInfo!!
                nextVideoFileInfo = null
                testStartedNextOutputFile = true
            }
        } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED && videoRestartOnMaxFilesize) {
            // note, if the restart was handled via MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING, then we shouldn't ever
            // receive MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
            if (MyDebug.LOG) Log.d(TAG, "restart due to max filesize reached - do manual restart")
            val activity = context as Activity
            activity.runOnUiThread { // we run on main thread to avoid problem of camera closing at the same time
                // but still need to check that the camera hasn't closed
                if (cameraController != null) restartVideo(true)
                else {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "don't restart video, as already cancelled"
                    )
                }
            }
        } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
            if (MyDebug.LOG) Log.d(TAG, "reached max duration - see if we need to restart?")
            val activity = context as Activity
            activity.runOnUiThread {
                // we run on main thread to avoid problem of camera closing at the same time
                // but still need to check that the camera hasn't closed
                if (cameraController != null) restartVideo(false) // n.b., this will only restart if remainingRestartVideo > 0
                else {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "don't restart video, as already cancelled"
                    )
                }
            }
        } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
            stopVideo(false)
        }
        applicationInterface.onVideoInfo(
            what,
            extra
        ) // call this last, so that toasts show up properly (as we're hogging the UI thread here, and mediarecorder takes time to stop)
    }

    private fun onVideoError(what: Int, extra: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onVideoError: $what extra: $extra"
        )
        stopVideo(false)
        applicationInterface.onVideoError(
            what,
            extra
        ) // call this last, so that toasts show up properly (as we're hogging the UI thread here, and mediarecorder takes time to stop)
    }

    /** Initiate "take picture" command. In video mode this means starting video command. In photo mode this may involve first
     * autofocusing.
     * @param photoSnapshot If true, then the user has requested taking a photo whilst video
     * recording. If false, either take a photo or start/stop video depending
     * on the current mode.
     * @param continuousFastBurst If true, then start a continuous fast burst.
     */
    private fun takePicture(
        maxFilesizeRestart: Boolean,
        photoSnapshot: Boolean,
        continuousFastBurst: Boolean
    ) {
        if (MyDebug.LOG) Log.d(TAG, "takePicture")
        //this.thumbnailAnim = false;
        if (!isVideo || photoSnapshot) this.phase = PHASE_TAKING_PHOTO
        else {
            if (phase == PHASE_TIMER) this.phase =
                PHASE_NORMAL // in case we were previously on timer for starting the video
        }
        synchronized(this) {
            // synchronize for consistency (keep FindBugs happy)
            takePhotoAfterAutofocus = false
        }
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            this.phase = PHASE_NORMAL
            applicationInterface.cameraInOperation(inOperation = false, isVideo = false)
            if (isVideo) applicationInterface.cameraInOperation(inOperation = false, isVideo = true)
            return
        }
        if (!this.hasSurface) {
            if (MyDebug.LOG) Log.d(TAG, "preview surface not yet available")
            this.phase = PHASE_NORMAL
            applicationInterface.cameraInOperation(inOperation = false, isVideo = false)
            if (isVideo) applicationInterface.cameraInOperation(inOperation = false, isVideo = true)
            return
        }

        val storeLocation: Boolean = applicationInterface.getGeotaggingPref()
        if (storeLocation) {
            val requireLocation: Boolean = applicationInterface.getRequireLocationPref()
            if (requireLocation) {
                if (applicationInterface.getLocation() != null) {
                    // fine, we have location
                } else {
                    if (MyDebug.LOG) Log.d(TAG, "location data required, but not available")
                    showToast(null, R.string.location_not_available, true)
                    if (!isVideo || photoSnapshot) this.phase = PHASE_NORMAL
                    applicationInterface.cameraInOperation(inOperation = false, isVideo = false)
                    if (isVideo) applicationInterface.cameraInOperation(
                        inOperation = false,
                        isVideo = true
                    )
                    return
                }
            }
        }

        if (isVideo && !photoSnapshot) {
            if (MyDebug.LOG) Log.d(TAG, "start video recording")
            startVideoRecording(maxFilesizeRestart)
            return
        }

        takePhoto(false, continuousFastBurst)
        if (MyDebug.LOG) Log.d(TAG, "takePicture exit")
    }

    private fun createVideoFile(extension: String): VideoFileInfo? {
        if (MyDebug.LOG) Log.d(TAG, "createVideoFile")
        var videoFileInfo: VideoFileInfo? = null
        var videoPfdSaf: ParcelFileDescriptor? = null
        try {
            val method: ApplicationInterface.VideoMethod =
                applicationInterface.createOutputVideoMethod()
            var videoUri: Uri? = null
            var videoFilename: String? = null
            if (MyDebug.LOG) Log.d(TAG, "method? $method")
            if (method === ApplicationInterface.VideoMethod.FILE) {
                /*if( true )
    				throw new IOException(); // test*/
                val videoFile: File = applicationInterface.createOutputVideoFile(extension)
                videoFilename = videoFile.absolutePath
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "save to: $videoFilename"
                )
            } else {
                val uri: Uri = if (method === ApplicationInterface.VideoMethod.SAF) {
                    applicationInterface.createOutputVideoSAF(extension)
                } else if (method === ApplicationInterface.VideoMethod.MEDIASTORE) {
                    applicationInterface.createOutputVideoMediaStore(extension)
                } else {
                    applicationInterface.createOutputVideoUri()
                }
                if (MyDebug.LOG) Log.d(TAG, "save to: $uri")
                videoPfdSaf = context.contentResolver.openFileDescriptor(uri, "rw")
                videoUri = uri
            }

            videoFileInfo = VideoFileInfo(method, videoUri, videoFilename, videoPfdSaf)
        } catch (e: IOException) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "Couldn't create media video file; check storage permissions?"
            )
            e.printStackTrace()
        } finally {
            if (videoFileInfo == null && videoPfdSaf != null) {
                if (MyDebug.LOG) Log.d(TAG, "failed, so clean up video_pfd_saf")
                try {
                    videoPfdSaf.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        return videoFileInfo
    }

    /** Start video recording.
     */
    private fun startVideoRecording(maxFilesizeRestart: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "startVideoRecording")
        focusSuccess = FOCUS_DONE // clear focus rectangle (don't do for taking photos yet)
        testCalledNextOutputFile = false
        testStartedNextOutputFile = false
        nextVideoFileInfo = null
        val profile: VideoProfile = videoProfile
        val info = createVideoFile(profile.fileExtension)
        if (info == null) {
            videoFileInfo = VideoFileInfo()
            applicationInterface.onFailedCreateVideoFileError()
            applicationInterface.cameraInOperation(inOperation = false, isVideo = true)
        } else {
            videoFileInfo = info
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "current_video_quality: " + videoQualityHandler.currentVideoQualityIndex
                )
                if (videoQualityHandler.currentVideoQualityIndex != -1) Log.d(
                    TAG,
                    "current_video_quality value: " + videoQualityHandler.currentVideoQuality
                )
                Log.d(
                    TAG,
                    "resolution " + profile.videoFrameWidth + " x " + profile.videoFrameHeight
                )
                Log.d(TAG, "bit rate " + profile.videoBitRate)
            }

            val enableSound: Boolean = applicationInterface.getShutterSoundPref()
            if (MyDebug.LOG) Log.d(
                TAG,
                "enable_sound? $enableSound"
            )
            cameraController!!.enableShutterSound(enableSound) // Camera2 API can disable video sound too

            val localVideoRecorder = MediaRecorder()
            cameraController!!.unlock()
            if (MyDebug.LOG) Log.d(TAG, "set video listeners")

            localVideoRecorder.setOnInfoListener { _, what, extra -> // mr, what, extra
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "MediaRecorder info: $what extra: $extra"
                )
                val activity = context as Activity
                activity.runOnUiThread { // we run on main thread to avoid problem of camera closing at the same time
                    onVideoInfo(what, extra)
                }
            }
            localVideoRecorder.setOnErrorListener { _, what, extra -> // mr, what, extra
                val activity = context as Activity
                activity.runOnUiThread { // we run on main thread to avoid problem of camera closing at the same time
                    onVideoError(what, extra)
                }
            }

            cameraController!!.initVideoRecorderPrePrepare(localVideoRecorder)
            if (profile.noAudioPermission) {
                showToast(null, R.string.permission_record_audio_not_available, true)
            }

            val storeLocation: Boolean = applicationInterface.getGeotaggingPref()
            if (storeLocation && applicationInterface.getLocation() != null) {
                val location: Location = applicationInterface.getLocation()!!
                // don't log location, in case of privacy!
                localVideoRecorder.setLocation(
                    location.latitude.toFloat(),
                    location.longitude.toFloat()
                )
            }

            if (MyDebug.LOG) Log.d(TAG, "copy video profile to media recorder")

            profile.copyToMediaRecorder(localVideoRecorder)

            var toldAppStarting = false // true if we called applicationInterface.startingVideo()
            try {
                val videoMaxFilesize: ApplicationInterface.VideoMaxFileSize =
                    applicationInterface.getVideoMaxFileSizePref()
                val maxFilesize: Long = videoMaxFilesize.maxFilesize
                //maxFilesize = 15*1024*1024; // test
                if (maxFilesize > 0) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "set max file size of: $maxFilesize"
                    )
                    try {
                        localVideoRecorder.setMaxFileSize(maxFilesize)
                    } catch (e: RuntimeException) {
                        // Google Camera warns this can happen - for example, if 64-bit filesizes not supported
                        if (MyDebug.LOG) Log.e(
                            TAG,
                            "failed to set max filesize of: $maxFilesize"
                        )
                        e.printStackTrace()
                    }
                }
                videoRestartOnMaxFilesize =
                    videoMaxFilesize.autoRestart // note, we set this even if maxFilesize==0, as it will still apply when hitting device max filesize limit

                // handle restart timer
                var videoMaxDuration: Long = applicationInterface.getVideoMaxDurationPref()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "user preference video_max_duration: $videoMaxDuration"
                )
                if (maxFilesizeRestart) {
                    if (videoMaxDuration > 0) {
                        videoMaxDuration -= videoAccumulatedTime
                        // this should be greater or equal to minSafeRestartVideoTime, as too short remaining time should have been caught in restartVideo()
                        if (videoMaxDuration < MIN_SAFE_RESTART_VIDEO_TIME) {
                            if (MyDebug.LOG) Log.e(
                                TAG,
                                "trying to restart video with too short a time: $videoMaxDuration"
                            )
                            videoMaxDuration = MIN_SAFE_RESTART_VIDEO_TIME
                        }
                    }
                } else {
                    videoAccumulatedTime = 0
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "actual video_max_duration: $videoMaxDuration"
                )
                localVideoRecorder.setMaxDuration(videoMaxDuration.toInt())

                if (videoFileInfo.videoMethod === ApplicationInterface.VideoMethod.FILE) {
                    localVideoRecorder.setOutputFile(videoFileInfo.videoFilename)
                } else {
                    localVideoRecorder.setOutputFile(videoFileInfo.videoPfdSaf!!.fileDescriptor)
                }
                applicationInterface.cameraInOperation(inOperation = true, isVideo = true)
                toldAppStarting = true
                applicationInterface.startingVideo()
                /*if( true ) // test
        			throw new IOException();*/
                cameraSurface.setVideoRecorder(localVideoRecorder)

                localVideoRecorder.setOrientationHint(imageVideoRotation)
                if (MyDebug.LOG) Log.d(TAG, "about to prepare video recorder")

                localVideoRecorder.prepare()
                if (testVideoIoexception) {
                    if (MyDebug.LOG) Log.d(TAG, "test_video_ioexception is true")
                    throw IOException()
                }

                val wantPhotoVideoRecording =
                    supportsPhotoVideoRecording() && applicationInterface.usePhotoVideoRecording()

                cameraController!!.initVideoRecorderPostPrepare(
                    localVideoRecorder,
                    wantPhotoVideoRecording
                )
                if (testVideoCameraControllerException) {
                    if (MyDebug.LOG) Log.d(TAG, "test_video_cameracontrollerexception is true")
                    throw CameraControllerException()
                }

                if (MyDebug.LOG) Log.d(TAG, "about to start video recorder")

                try {
                    localVideoRecorder.start()
                    if (testVideoFailure) {
                        if (MyDebug.LOG) Log.d(TAG, "test_video_failure is true")
                        throw RuntimeException()
                    }
                    this.videoRecorder = localVideoRecorder
                    videoRecordingStarted(maxFilesizeRestart)
                } catch (e: RuntimeException) {
                    // needed for emulator at least - although MediaRecorder not meant to work with emulator, it's good to fail gracefully
                    Log.e(TAG, "runtime exception starting video recorder")
                    e.printStackTrace()
                    this.videoRecorder =
                        localVideoRecorder // still assign, so failedToStartVideoRecorder() will release the videoRecorder
                    // toldAppStarting must be true if we're here
                    applicationInterface.stoppingVideo()
                    failedToStartVideoRecorder(profile)
                }

                /*final MediaRecorder localVideoRecorderF = localVideoRecorder;
				new AsyncTask<Void, Void, Boolean>() {
					private static final String TAG = "video_recorder.start";

					@Override
					protected Boolean doInBackground(Void... voids) {
						if( MyDebug.LOG )
							Log.d(TAG, "doInBackground, async task: " + this);
						try {
							local_video_recorder_f.start();
						}
						catch(RuntimeException e) {
							// needed for emulator at least - although MediaRecorder not meant to work with emulator, it's good to fail gracefully
							Log.e(TAG, "runtime exception starting video recorder");
							e.printStackTrace();
							return false;
						}
						return true;
					}

					@Override
					protected void onPostExecute(Boolean success) {
						if( MyDebug.LOG ) {
							Log.d(TAG, "onPostExecute, async task: " + this);
							Log.d(TAG, "success: " + success);
						}
						 // still assign even if success==false, so failedToStartVideoRecorder() will release the videoRecorder
						Preview.this.videoRecorder = localVideoRecorderF;
						if( success ) {
							videoRecordingStarted(maxFilesizeRestart);
						}
						else {
							// toldAppStarting must be true if we're here
							applicationInterface.stoppingVideo();
							failedToStartVideoRecorder(profile);
						}
					}
				}.execute();*/
            } catch (e: IOException) {
                if (MyDebug.LOG) Log.e(TAG, "failed to save video")
                e.printStackTrace()
                this.videoRecorder = localVideoRecorder
                if (toldAppStarting) {
                    applicationInterface.stoppingVideo()
                }
                applicationInterface.onFailedCreateVideoFileError()
                videoRecorder!!.reset()
                videoRecorder!!.release()
                videoRecorder = null
                videoRecorderIsPaused = false
                applicationInterface.deleteUnusedVideo(
                    videoFileInfo.videoMethod,
                    videoFileInfo.videoUri,
                    videoFileInfo.videoFilename
                )
                videoFileInfo = VideoFileInfo()
                applicationInterface.cameraInOperation(inOperation = false, isVideo = true)
                this.reconnectCamera(true)
            } catch (e: CameraControllerException) {
                if (MyDebug.LOG) Log.e(TAG, "camera exception starting video recorder")
                e.printStackTrace()
                this.videoRecorder =
                    localVideoRecorder // still assign, so failedToStartVideoRecorder() will release the videoRecorder
                if (toldAppStarting) {
                    applicationInterface.stoppingVideo()
                }
                failedToStartVideoRecorder(profile)
            } catch (e: NoFreeStorageException) {
                if (MyDebug.LOG) Log.e(TAG, "nofreestorageexception starting video recorder")
                e.printStackTrace()
                this.videoRecorder = localVideoRecorder
                if (toldAppStarting) {
                    applicationInterface.stoppingVideo()
                }
                videoRecorder!!.reset()
                videoRecorder!!.release()
                videoRecorder = null
                videoRecorderIsPaused = false
                applicationInterface.deleteUnusedVideo(
                    videoFileInfo.videoMethod,
                    videoFileInfo.videoUri,
                    videoFileInfo.videoFilename
                )
                videoFileInfo = VideoFileInfo()
                applicationInterface.cameraInOperation(inOperation = false, isVideo = true)
                this.reconnectCamera(true)
                this.showToast(null, R.string.video_no_free_space)
            }
        }
    }

    private fun videoRecordingStarted(maxFilesizeRestart: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "video recorder started")
        videoRecorderIsPaused = false

        if (this.usingFaceDetection && !this.usingAndroidL) {
            if (MyDebug.LOG) Log.d(TAG, "restart face detection")
            // doing MediaRecorder.start() seems to stop face detection on old Camera API
            cameraController!!.startFaceDetection()
            _facesDetected = emptyArray()
        }

        videoStartTime = System.currentTimeMillis()
        videoStartTimeSet = true
        videoTimeLastMaxfilesizeRestart = if (maxFilesizeRestart) videoAccumulatedTime else 0
        applicationInterface.startedVideo()

        // Don't send intent for ACTION_MEDIA_SCANNER_SCAN_FILE yet - wait until finished, so we get completed file.
        // Don't do any further calls after applicationInterface.startedVideo() that might throw an error - instead video error
        // should be handled by including a call to stopVideo() (since the videoRecorder has started).

        // handle restarts
        if (remainingRestartVideo == 0 && !maxFilesizeRestart) {
            remainingRestartVideo = applicationInterface.getVideoRestartTimesPref()
            if (MyDebug.LOG) Log.d(
                TAG,
                "initialised remaining_restart_video to: $remainingRestartVideo"
            )
        }

        if (applicationInterface.getVideoFlashPref() && supportsFlash()) {
            class FlashVideoTimerTask : TimerTask() {
                override fun run() {
                    if (MyDebug.LOG) Log.e(TAG, "FlashVideoTimerTask")
                    val activity = context as Activity
                    activity.runOnUiThread { // we run on main thread to avoid problem of camera closing at the same time
                        // but still need to check that the camera hasn't closed or the task halted, since TimerTask.run() started
                        if (cameraController != null && flashVideoTimerTask != null) flashVideo()
                        else {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "flashVideoTimerTask: don't flash video, as already cancelled"
                            )
                        }
                    }
                }
            }
            flashVideoTimer.schedule(
                FlashVideoTimerTask().also { flashVideoTimerTask = it },
                0,
                1000
            )
        }

        if (applicationInterface.getVideoLowPowerCheckPref()) {
            /* When a device shuts down due to power off, the application will receive shutdown signals, and normally the video
             * should stop and be valid. However, it can happen that the video ends up corrupted (I've had people telling me this
             * can happen; Googling finds plenty of stories of this happening on Android devices). I think the issue is that for
             * very large videos, a lot of time is spent processing during the MediaRecorder.stop() call - if that doesn't complete
             * by the time the device switches off, the video may be corrupt.
             * So we add an extra safety net - devices typically turn off abou 1%, but we stop video at 3% to be safe. The user
             * can try recording more videos after that if the want, but this reduces the risk that really long videos are entirely
             * lost.
             */
            class BatteryCheckVideoTimerTask : TimerTask() {
                override fun run() {
                    if (MyDebug.LOG) Log.d(TAG, "BatteryCheckVideoTimerTask")

                    // only check periodically - unclear if checking is costly in any way
                    // note that it's fine to call registerReceiver repeatedly - we pass a null receiver, so this is fine as a "one shot" use
                    val batteryStatus: Intent =
                        this@Preview.context.registerReceiver(null, batteryIfilter) as Intent
                    val batteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val batteryScale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val batteryFrac = batteryLevel / batteryScale.toDouble()
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "batteryCheckVideoTimerTask: battery level at: $batteryFrac"
                    )

                    if (batteryFrac <= 0.03) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "batteryCheckVideoTimerTask: battery at critical level, switching off video"
                        )
                        val activity = context as Activity
                        activity.runOnUiThread {
                            // we run on main thread to avoid problem of camera closing at the same time
                            // but still need to check that the camera hasn't closed or the task halted, since TimerTask.run() started
                            if (cameraController != null && batteryCheckVideoTimerTask != null) {
                                stopVideo(false)
                                val toast: String = this@Preview.context.resources
                                    .getString(R.string.video_power_critical)
                                showToast(
                                    null,
                                    toast
                                ) // show the toast afterward, as we're hogging the UI thread here, and media recorder takes time to stop
                            } else {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "batteryCheckVideoTimerTask: don't stop video, as already cancelled"
                                )
                            }
                        }
                    }
                }
            }

            val batteryCheckIntervalMs = (60 * 1000).toLong()
            // Since we only first check after batteryCheckIntervalMs, this means users will get some video recorded even if the battery is already too low.
            // But this is fine, as typically short videos won't be corrupted if the device shuts off, and good to allow users to try to record a bit more if they want.
            batteryCheckVideoTimer.schedule(BatteryCheckVideoTimerTask().also {
                batteryCheckVideoTimerTask = it
            }, batteryCheckIntervalMs, batteryCheckIntervalMs)
        }
    }

    private fun failedToStartVideoRecorder(profile: VideoProfile) {
        applicationInterface.onVideoRecordStartError(profile)
        videoRecorder!!.reset()
        videoRecorder!!.release()
        videoRecorder = null
        videoRecorderIsPaused = false
        applicationInterface.deleteUnusedVideo(
            videoFileInfo.videoMethod,
            videoFileInfo.videoUri,
            videoFileInfo.videoFilename
        )
        videoFileInfo = VideoFileInfo()
        applicationInterface.cameraInOperation(inOperation = false, isVideo = true)
        this.reconnectCamera(true)
    }

    /** Pauses the video recording - or unpauses if already paused.
     * This does nothing if isVideoRecording() returns false, or not on Android 7 or higher.
     */
    fun pauseVideo() {
        if (MyDebug.LOG) Log.d(TAG, "pauseVideo")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "pauseVideo called but requires Android N")
        } else if (this.isVideoRecording) {
            if (videoRecorderIsPaused) {
                if (MyDebug.LOG) Log.d(TAG, "resuming...")
                videoRecorder!!.resume()
                videoRecorderIsPaused = false
                videoStartTime = System.currentTimeMillis()
                this.showToast(pauseVideoToast, R.string.video_resume, true)
            } else {
                if (MyDebug.LOG) Log.d(TAG, "pausing...")
                videoRecorder!!.pause()
                videoRecorderIsPaused = true
                val lastTime = System.currentTimeMillis() - videoStartTime
                videoAccumulatedTime += lastTime
                if (MyDebug.LOG) {
                    Log.d(TAG, "last_time: $lastTime")
                    Log.d(TAG, "video_accumulated_time is now: $videoAccumulatedTime")
                }
                this.showToast(pauseVideoToast, R.string.video_pause, true)
            }
        } else {
            Log.e(TAG, "pauseVideo called but not video recording")
        }
    }

    /** Take photo. The caller should already have set the phase to PHASE_TAKING_PHOTO.
     */
    private fun takePhoto(skipAutofocus: Boolean, continuousFastBurst: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "takePhoto")
        if (cameraController == null) {
            Log.e(TAG, "camera not opened in takePhoto!")
            return
        }
        applicationInterface.cameraInOperation(inOperation = true, isVideo = false)
        val currentUiFocusValue = currentFocusValue
        if (MyDebug.LOG) Log.d(
            TAG,
            "current_ui_focus_value is $currentUiFocusValue"
        )

        if (autofocusInContinuousMode) {
            if (MyDebug.LOG) Log.d(TAG, "continuous mode where user touched to focus")

            val waitForFocus: Boolean

            synchronized(this) {
                // as below, if an autofocus is in progress, then take photo when it's completed
                if (focusSuccess == FOCUS_WAITING) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "autofocus_in_continuous_mode: take photo after current focus"
                    )
                    waitForFocus = true
                    takePhotoAfterAutofocus = true
                } else {
                    // when autofocusInContinuousMode==true, it means the user recently touched to focus in continuous focus mode, so don't do another focus
                    if (MyDebug.LOG) Log.d(TAG, "autofocus_in_continuous_mode: no need to refocus")
                    waitForFocus = false
                }
            }

            // call CameraController outside the lock
            if (waitForFocus) {
                cameraController!!.setCaptureFollowAutofocusHint(true)
            } else {
                takePhotoWhenFocused(continuousFastBurst)
            }
        } else if (cameraController!!.focusIsContinuous()) {
            val optimiseForLatency: Boolean = applicationInterface.optimiseFocusForLatency()
            if (optimiseForLatency) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "take photo under continuous focus mode [optimise for latency]"
                )
                takePhotoWhenFocused(continuousFastBurst)
            } else {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "call autofocus for continuous focus mode [optimise for quality]"
                )
                // we call via autoFocus(), to avoid risk of taking photo while the continuous focus is focusing - risk of blurred photo, also sometimes get bug in such situations where we end of repeatedly focusing
                // this is the case even if skipAutofocus is true (as we still can't guarantee that continuous focusing might be occurring)
                // note: if the user touches to focus in continuous mode, the camera controller may be in autofocus mode, so we should only enter this codepath if the cameraController is in continuous focus mode
                val autoFocusCallback: CameraController.AutoFocusCallback =
                    object : CameraController.AutoFocusCallback {
                        override fun onAutoFocus(success: Boolean) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "continuous mode autofocus complete: $success"
                            )
                            takePhotoWhenFocused(continuousFastBurst)
                        }
                    }
                cameraController!!.autoFocus(autoFocusCallback, true)
            }
        } else if (skipAutofocus || this.recentlyFocused()) {
            if (MyDebug.LOG) {
                if (skipAutofocus) {
                    Log.d(TAG, "skip_autofocus flag set")
                } else {
                    Log.d(TAG, "recently focused successfully, so no need to refocus")
                }
            }
            takePhotoWhenFocused(continuousFastBurst)
        } else if (currentUiFocusValue != null && (currentUiFocusValue == "focus_mode_auto" || currentUiFocusValue == "focus_mode_macro")) {
            val waitForFocus: Boolean
            // n.b., we check focusValue rather than camera_controller.supportsAutoFocus(), as we want to discount focusModeLocked
            synchronized(this) {
                if (focusSuccess == FOCUS_WAITING) {
                    // Needed to fix bug (on Nexus 6, old camera API): if flash was on, pointing at a dark scene, and we take photo when already autofocusing, the autofocus never returned so we got stuck!
                    // In general, probably a good idea to not redo a focus - just use the one that's already in progress
                    if (MyDebug.LOG) Log.d(TAG, "take photo after current focus")
                    waitForFocus = true
                    takePhotoAfterAutofocus = true
                } else {
                    waitForFocus = false
                    focusSuccess = FOCUS_DONE // clear focus rectangle for new refocus
                }
            }

            // call CameraController outside the lock
            if (waitForFocus) {
                cameraController!!.setCaptureFollowAutofocusHint(true)
            } else {
                val autoFocusCallback: CameraController.AutoFocusCallback =
                    object : CameraController.AutoFocusCallback {
                        override fun onAutoFocus(success: Boolean) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "autofocus complete: $success"
                            )
                            ensureFlashCorrect() // need to call this in case user takes picture before startup focus completes!
                            prepareAutoFocusPhoto()
                            takePhotoWhenFocused(continuousFastBurst)
                        }
                    }
                if (MyDebug.LOG) Log.d(TAG, "start autofocus to take picture")
                cameraController!!.autoFocus(autoFocusCallback, true)
                countCameraAutoFocus++
            }
        } else {
            takePhotoWhenFocused(continuousFastBurst)
        }
    }

    /** Should be called when taking a photo immediately after an autofocus.
     * This is needed for a workaround for Camera2 bug (at least on Nexus 6) where photos sometimes come out dark when using flash
     * auto, when the flash fires. This happens when taking a photo in autofocus mode (including when continuous mode has
     * transitioned to autofocus mode due to touching to focus). Seems to happen with scenes that have bright and dark regions,
     * i.e., on verge of flash firing.
     * Seems to be fixed if we have a short delay...
     */
    private fun prepareAutoFocusPhoto() {
        if (MyDebug.LOG) Log.d(TAG, "prepareAutoFocusPhoto")
        if (usingAndroidL) {
            val flashValue: String = cameraController!!.flashValue
            // getFlashValue() may return "" if flash not supported!
            if (flashValue.isNotEmpty() && (flashValue == "flash_auto" || flashValue == "flash_red_eye")) {
                if (MyDebug.LOG) Log.d(TAG, "wait for a bit...")
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
        }
    }

    /** Take photo, assumes any autofocus has already been taken care of, and that applicationInterface.cameraInOperation(true, false) has
     * already been called.
     * Note that even if a caller wants to take a photo without focusing, you probably want to call takePhoto() with skipAutofocus
     * set to true (so that things work okay in continuous picture focus mode).
     */
    private fun takePhotoWhenFocused(continuousFastBurst: Boolean) {
        // should be called when autofocused
        if (MyDebug.LOG) Log.d(TAG, "takePhotoWhenFocused")
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
            this.phase = PHASE_NORMAL
            applicationInterface.cameraInOperation(inOperation = false, isVideo = false)
            return
        }
        if (!this.hasSurface) {
            if (MyDebug.LOG) Log.d(TAG, "preview surface not yet available")
            this.phase = PHASE_NORMAL
            applicationInterface.cameraInOperation(inOperation = false, isVideo = false)
            return
        }

        val focusValue =
            if (currentFocusIndex != -1) supportedFocusValues!![currentFocusIndex] else null
        if (MyDebug.LOG) {
            Log.d(TAG, "focus_value is $focusValue")
            Log.d(TAG, "focus_success is $focusSuccess")
        }

        if (focusValue != null && focusValue == "focus_mode_locked" && focusSuccess == FOCUS_WAITING) {
            // make sure there isn't an autofocus in progress - can happen if in locked mode we take a photo while autofocusing - see testTakePhotoLockedFocus() (although that test doesn't always properly test the bug...)
            // we only cancel when in locked mode and if still focusing, as I had 2 bug reports for v1.16 that the photo was being taken out of focus; both reports said it worked fine in 1.15, and one confirmed that it was due to the cancelAutoFocus() line, and that it's now fixed with this fix
            // they said this happened in every focus mode, including locked - so possible that on some devices, cancelAutoFocus() actually pulls the camera out of focus, or reverts to preview focus?
            cancelAutoFocus()
        }
        removePendingContinuousFocusReset() // to avoid switching back to continuous focus mode while taking a photo - instead we'll always make sure we switch back after taking a photo
        updateParametersFromLocation() // do this now, not before, so we don't set location parameters during focus (sometimes get RuntimeException)

        focusSuccess = FOCUS_DONE // clear focus rectangle if not already done
        successfullyFocused = false // so next photo taken will require an autofocus
        if (MyDebug.LOG) Log.d(
            TAG,
            "remaining_repeat_photos: $remainingRepeatPhotos"
        )

        // if focusSetForTargetDistance==true, then we stick with the last set focus bracketing source distance, as the current focus distance will
        // be set to the target
        if (applicationInterface.isFocusBracketingPref() && applicationInterface.isFocusBracketingSourceAutoPref() && !isSettingTargetFocusDistance) {
            cameraController!!.setFocusBracketingSourceDistanceFromCurrent()
        }

        val pictureCallback: CameraController.PictureCallback =
            object : CameraController.PictureCallback {
                private var success = false // whether jpeg callback succeeded
                private var hasDate = false
                private var currentDate: Date? = null

                override fun onStarted() {
                    if (MyDebug.LOG) Log.d(TAG, "onStarted")
                    applicationInterface.onCaptureStarted()
                    if (applicationInterface.getBurstForNoiseReduction() && applicationInterface.getNRModePref() === ApplicationInterface.NRModePref.NRMODE_LOW_LIGHT) {
                        if (cameraController!!.burstTotal >= CameraController.N_IMAGES_NR_DARK_LOW_LIGHT) {
                            showToast(null, R.string.preference_nr_mode_low_light_message, true)
                        }
                    }
                }

                override fun onCompleted() {
                    if (MyDebug.LOG) Log.d(TAG, "onCompleted")
                    applicationInterface.onPictureCompleted()
                    if (!usingAndroidL) {
                        // preview automatically stopped due to taking photo on original Camera API
                        this@Preview.previewStartedState = PREVIEW_NOT_STARTED
                    }
                    phase =
                        PHASE_NORMAL // need to set this even if remaining repeat photos, so we can restart the preview
                    if (remainingRepeatPhotos == -1 || remainingRepeatPhotos > 0) {
                        if (this@Preview.previewStartedState == PREVIEW_NOT_STARTED) {
                            // we need to restart the preview; and we do this in the callback, as we need to restart after saving the image
                            // (otherwise this can fail, at least on Nexus 7)
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "repeat mode photos remaining: onPictureTaken about to start preview: $remainingRepeatPhotos"
                            )
                            startCameraPreview()
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "repeat mode photos remaining: onPictureTaken started preview: $remainingRepeatPhotos"
                            )
                        }
                        applicationInterface.cameraInOperation(inOperation = false, isVideo = false)
                    } else {
                        phase = PHASE_NORMAL
                        val pausePreview: Boolean = applicationInterface.getPausePreviewPref()
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "pause_preview? $pausePreview"
                        )
                        if (pausePreview && success) {
                            if (this@Preview.isPreviewStarted) {
                                // need to manually stop preview on Android L Camera2
                                // also note: even though we now draw the last image on top of the screen instead of relying on the
                                // camera preview being paused, it's still good practice to pause the preview/camera for privacy reasons
                                if (cameraController != null) {
                                    cameraController!!.stopPreview()
                                }
                                this@Preview.previewStartedState = PREVIEW_NOT_STARTED
                            }
                            this@Preview.isPreviewPaused = true
                        } else {
                            if (this@Preview.previewStartedState == PREVIEW_NOT_STARTED) {
                                // we need to restart the preview; and we do this in the callback, as we need to restart after saving the image
                                // (otherwise this can fail, at least on Nexus 7)
                                startCameraPreview()
                            }
                            applicationInterface.cameraInOperation(
                                inOperation = false,
                                isVideo = false
                            )
                            if (MyDebug.LOG) Log.d(TAG, "onPictureTaken started preview")
                        }
                    }
                    continuousFocusReset() // in case we took a photo after user had touched to focus (causing us to switch from continuous to autofocus mode)
                    if (cameraController != null && focusValue != null && (focusValue == "focus_mode_continuous_picture" || focusValue == "focus_mode_continuous_video")) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "cancelAutoFocus to restart continuous focusing"
                        )
                        cameraController!!.cancelAutoFocus() // needed to restart continuous focusing
                    }

                    if (cameraController != null && cameraController!!.burstType === CameraController.BurstType.BURSTTYPE_CONTINUOUS) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "continuous burst mode ended, so revert to standard mode"
                        )
                        setupBurstMode()
                    }

                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "do we need to take another photo? remaining_repeat_photos: $remainingRepeatPhotos"
                    )
                    if (remainingRepeatPhotos == -1 || remainingRepeatPhotos > 0) {
                        takeRemainingRepeatPhotos()
                    }
                }

                /** Ensures we get the same date for both JPEG and RAW; and that we set the date ASAP so that it corresponds to actual
                 * photo time.
                 */
                fun initDate() {
                    if (!hasDate) {
                        hasDate = true
                        currentDate = Date()
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "picture taken on date: $currentDate"
                        )
                    }
                }

                override fun onPictureTaken(data: ByteArray) {
                    if (MyDebug.LOG) Log.d(TAG, "onPictureTaken")
                    initDate()
                    if (!applicationInterface.onPictureTaken(data, currentDate!!)) {
                        if (MyDebug.LOG) Log.e(TAG, "applicationInterface.onPictureTaken failed")
                        success = false
                    } else {
                        success = true
                    }
                }

                override fun onRawPictureTaken(rawImage: RawImage?) {
                    if (MyDebug.LOG) Log.d(TAG, "onRawPictureTaken")
                    initDate()
                    if (!applicationInterface.onRawPictureTaken(rawImage, currentDate!!)) {
                        if (MyDebug.LOG) Log.e(TAG, "applicationInterface.onRawPictureTaken failed")
                    }
                }

                override fun onBurstPictureTaken(images: List<ByteArray>) {
                    if (MyDebug.LOG) Log.d(TAG, "onBurstPictureTaken")
                    initDate()

                    success = true
                    if (!applicationInterface.onBurstPictureTaken(images, currentDate!!)) {
                        if (MyDebug.LOG) Log.e(
                            TAG,
                            "applicationInterface.onBurstPictureTaken failed"
                        )
                        success = false
                    }
                }

                override fun onRawBurstPictureTaken(rawImages: List<RawImage>) {
                    if (MyDebug.LOG) Log.d(TAG, "onRawBurstPictureTaken")
                    initDate()

                    if (!applicationInterface.onRawBurstPictureTaken(rawImages, currentDate!!)) {
                        if (MyDebug.LOG) Log.e(
                            TAG,
                            "applicationInterface.onRawBurstPictureTaken failed"
                        )
                    }
                }

                override fun onExtensionProgress(progress: Int) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "onExtensionProgress: $progress"
                    )
                    applicationInterface.onExtensionProgress(progress)
                }

                override fun imageQueueWouldBlock(nRaw: Int, nJpegs: Int): Boolean {
                    if (MyDebug.LOG) Log.d(TAG, "imageQueueWouldBlock")
                    return applicationInterface.imageQueueWouldBlock(nRaw, nJpegs)
                }

                override fun onFrontScreenTurnOn() {
                    if (MyDebug.LOG) Log.d(TAG, "onFrontScreenTurnOn")
                    applicationInterface.turnFrontScreenFlashOn()
                }
            }
        val errorCallback: CameraController.ErrorCallback =
            object : CameraController.ErrorCallback {
                override fun onError() {
                    if (MyDebug.LOG) Log.e(TAG, "error from takePicture")
                    countCameraTakePicture-- // cancel out the increment from after the takePicture() call
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "count_cameraTakePicture is now: $countCameraTakePicture"
                        )
                    }
                    applicationInterface.onPhotoError()
                    phase = PHASE_NORMAL
                    startCameraPreview()
                    applicationInterface.cameraInOperation(inOperation = false, isVideo = false)
                }
            }
        run {
            cameraController!!.setRotation(imageVideoRotation)
            var enableSound: Boolean = applicationInterface.getShutterSoundPref()
            if (isVideo && isVideoRecording) enableSound =
                false // always disable shutter sound if we're taking a photo while recording video

            if (MyDebug.LOG) Log.d(TAG, "enable_sound? $enableSound")
            cameraController!!.enableShutterSound(enableSound)
            if (usingAndroidL) {
                val camera2DummyCaptureHack: Boolean =
                    applicationInterface.useCamera2DummyCaptureHack()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "camera2_dummy_capture_hack? $camera2DummyCaptureHack"
                )
                cameraController!!.setDummyCaptureHack(camera2DummyCaptureHack)

                val useCamera2FastBurst: Boolean = applicationInterface.useCamera2FastBurst()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "use_camera2_fast_burst? $useCamera2FastBurst"
                )
                cameraController!!.setUseExpoFastBurst(useCamera2FastBurst)
            }
            if (continuousFastBurst) {
                cameraController!!.burstType = CameraController.BurstType.BURSTTYPE_CONTINUOUS
            }

            if (MyDebug.LOG) Log.d(TAG, "about to call takePicture")
            cameraController!!.takePicture(pictureCallback, errorCallback)
            countCameraTakePicture++
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "count_cameraTakePicture is now: $countCameraTakePicture"
                )
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "takePhotoWhenFocused exit")
    }

    private fun takeRemainingRepeatPhotos() {
        if (MyDebug.LOG) Log.d(TAG, "takeRemainingRepeatPhotos")
        if (remainingRepeatPhotos == -1 || remainingRepeatPhotos > 0) {
            if (cameraController == null) {
                Log.e(
                    TAG,
                    "remaining_repeat_photos still set, but camera is closed!: $remainingRepeatPhotos"
                )
                cancelRepeat()
            } else {
                // check it's okay to take a photo
                if (!applicationInterface.canTakeNewPhoto()) {
                    if (MyDebug.LOG) Log.d(TAG, "takeRemainingRepeatPhotos: still processing...")
                    // wait a bit then check again
                    val handler = Handler()
                    handler.postDelayed({
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "takeRemainingRepeatPhotos: check again from post delayed runnable"
                        )
                        takeRemainingRepeatPhotos()
                    }, 500)
                    return
                }

                if (remainingRepeatPhotos > 0) remainingRepeatPhotos--
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "takeRemainingRepeatPhotos: remaining_repeat_photos is now: $remainingRepeatPhotos"
                )

                val timerDelay: Long = applicationInterface.getRepeatIntervalPref()
                if (timerDelay == 0L) {
                    // we set skipAutofocus to go straight to taking a photo rather than refocusing, for speed
                    // need to manually set the phase
                    phase = PHASE_TAKING_PHOTO
                    takePhoto(skipAutofocus = true, continuousFastBurst = false)
                } else {
                    takePictureOnTimer(timerDelay, true)
                }
            }
        }
    }

    fun requestAutoFocus() {
        if (MyDebug.LOG) Log.d(TAG, "requestAutoFocus")
        cancelAutoFocus()
        tryAutoFocus(startup = false, manual = true)
    }

    private fun tryAutoFocus(startup: Boolean, manual: Boolean) {
        // manual: whether user has requested autofocus (e.g., by touching screen, or volume focus, or hardware focus button)
        // consider whether you want to call requestAutoFocus() instead (which properly cancels any in-progress autofocus first)
        if (MyDebug.LOG) {
            Log.d(TAG, "tryAutoFocus")
            Log.d(TAG, "startup? $startup")
            Log.d(TAG, "manual? $manual")
        }
        if (cameraController == null) {
            if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
        } else if (!this.hasSurface) {
            if (MyDebug.LOG) Log.d(TAG, "preview surface not yet available")
        } else if (previewStartedState == PREVIEW_NOT_STARTED) {
            if (MyDebug.LOG) Log.d(TAG, "preview not yet started")
        } else if (!(manual && this.isVideo) && (this.isVideoRecording || this.isTakingPhotoOrOnTimer)) {
            // if taking a video, we allow manual autofocuses
            // autofocus may cause problem if there is a video corruption problem, see testTakeVideoBitrate() on Nexus 7 at 30Mbs or 50Mbs, where the startup autofocus would cause a problem here
            if (MyDebug.LOG) Log.d(TAG, "currently taking a photo")
        } else {
            if (manual) {
                // remove any previous request to switch back to continuous
                removePendingContinuousFocusReset()
            }
            if (manual && !isVideo && cameraController!!.focusIsContinuous() && supportedFocusValue(
                    "focus_mode_auto"
                )
            ) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "switch from continuous to autofocus mode for touch focus"
                )
                cameraController!!.focusValue = "focus_mode_auto" // switch to autofocus
                autofocusInContinuousMode = true
                // we switch back to continuous via a new resetContinuousFocusRunnable in autoFocusCompleted()
            }
            // it's only worth doing autofocus when autofocus has an effect (i.e., auto or macro mode)
            // but also for continuous focus mode, triggering an autofocus is still important to fire flash when touching the screen
            if (cameraController!!.supportsAutoFocus()) {
                if (MyDebug.LOG) Log.d(TAG, "try to start autofocus")
                if (!usingAndroidL) {
                    setFlashValueAfterAutofocus = ""
                    val oldFlashValue: String = cameraController!!.flashValue
                    // getFlashValue() may return "" if flash not supported!
                    if (startup && oldFlashValue.isNotEmpty() && (oldFlashValue != "flash_off") && (oldFlashValue != "flash_torch")) {
                        setFlashValueAfterAutofocus = oldFlashValue
                        cameraController!!.flashValue = "flash_off"
                    }
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "set_flash_value_after_autofocus is now: $setFlashValueAfterAutofocus"
                    )
                }
                val autoFocusCallback: CameraController.AutoFocusCallback =
                    object : CameraController.AutoFocusCallback {
                        override fun onAutoFocus(success: Boolean) {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "autofocus complete: $success"
                            )
                            autoFocusCompleted(manual, success, false)
                        }
                    }

                this.focusSuccess = FOCUS_WAITING
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "set focus_success to $focusSuccess"
                )
                this.focusCompleteTime = -1
                this.successfullyFocused = false
                cameraController!!.autoFocus(autoFocusCallback, false)
                countCameraAutoFocus++
                this.focusStartedTime = System.currentTimeMillis()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "autofocus started, count now: $countCameraAutoFocus"
                )
            } else if (hasFocusArea) {
                // do this so we get the focus box, for focus modes that support focus area, but don't support autofocus
                focusSuccess = FOCUS_SUCCESS
                focusCompleteTime = System.currentTimeMillis()
                // n.b., don't set focusStartedTime as that may be used for application to show autofocus animation
            }
        }
    }

    /** If the user touches the screen in continuous focus mode, we switch the cameraController to autofocus mode.
     * After the autofocus completes, we set a resetContinuousFocusRunnable to switch back to the cameraController
     * back to continuous focus after a short delay.
     * This function removes any pending resetContinuousFocusRunnable.
     */
    private fun removePendingContinuousFocusReset() {
        if (MyDebug.LOG) Log.d(TAG, "removePendingContinuousFocusReset")
        if (resetContinuousFocusRunnable != null) {
            if (MyDebug.LOG) Log.d(TAG, "remove pending reset_continuous_focus_runnable")
            resetContinuousFocusHandler.removeCallbacks(resetContinuousFocusRunnable!!)
            resetContinuousFocusRunnable = null
        }
    }

    /** If the user touches the screen in continuous focus mode, we switch the cameraController to autofocus mode.
     * This function is called to see if we should switch from autofocus mode back to continuous focus mode.
     * If this isn't required, calling this function does nothing.
     */
    private fun continuousFocusReset() {
        if (MyDebug.LOG) Log.d(TAG, "switch back to continuous focus after autofocus?")
        if (cameraController != null && autofocusInContinuousMode) {
            autofocusInContinuousMode = false
            // check again
            val currentUiFocusValue = currentFocusValue
            if (currentUiFocusValue != null && cameraController!!.flashValue != currentUiFocusValue && cameraController!!.flashValue == "focus_mode_auto"
            ) {
                cameraController!!.cancelAutoFocus()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "switch back to: $currentUiFocusValue"
                )
                cameraController!!.flashValue = currentUiFocusValue
            } else {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "no need to switch back to continuous focus after autofocus, mode already changed"
                )
            }
        }
    }

    private fun cancelAutoFocus() {
        if (MyDebug.LOG) Log.d(TAG, "cancelAutoFocus")
        if (cameraController != null) {
            cameraController!!.cancelAutoFocus()
            autoFocusCompleted(manual = false, success = false, cancelled = true)
        }
    }

    private fun ensureFlashCorrect() {
        // ensures flash is in correct mode, in case where we had to turn flash temporarily off for startup autofocus
        if (setFlashValueAfterAutofocus.isNotEmpty() && cameraController != null) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "set flash back to: $setFlashValueAfterAutofocus"
            )
            cameraController!!.flashValue = setFlashValueAfterAutofocus
            setFlashValueAfterAutofocus = ""
        }
    }

    private fun autoFocusCompleted(manual: Boolean, success: Boolean, cancelled: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "autoFocusCompleted")
            Log.d(TAG, "    manual? $manual")
            Log.d(TAG, "    success? $success")
            Log.d(TAG, "    cancelled? $cancelled")
        }
        if (cancelled) {
            focusSuccess = FOCUS_DONE
        } else {
            focusSuccess = if (success) FOCUS_SUCCESS else FOCUS_FAILED
            focusCompleteTime = System.currentTimeMillis()
        }
        if (manual && !cancelled && (success || applicationInterface.isTestAlwaysFocus())) {
            successfullyFocused = true
            successfullyFocusedTime = focusCompleteTime
        }
        if (manual && cameraController != null && autofocusInContinuousMode) {
            val currentUiFocusValue = currentFocusValue
            if (MyDebug.LOG) Log.d(
                TAG,
                "current_ui_focus_value: $currentUiFocusValue"
            )
            if (currentUiFocusValue != null && cameraController!!.flashValue != currentUiFocusValue && cameraController!!.flashValue == "focus_mode_auto") {
                resetContinuousFocusRunnable = Runnable {
                    if (MyDebug.LOG) Log.d(TAG, "reset_continuous_focus_runnable running...")
                    resetContinuousFocusRunnable = null
                    continuousFocusReset()
                }
                resetContinuousFocusHandler.postDelayed(resetContinuousFocusRunnable!!, 3000)
            }
        }
        ensureFlashCorrect()
        if (this.usingFaceDetection && !cancelled) {
            // On some devices such as mtk6589, face detection does not resume as written in documentation so we have
            // to cancelfocus when focus is finished
            if (cameraController != null) {
                cameraController!!.cancelAutoFocus()
            }
        }

        val localTakePhotoAfterAutofocus: Boolean
        synchronized(this) {
            localTakePhotoAfterAutofocus = takePhotoAfterAutofocus
            takePhotoAfterAutofocus = false
        }
        // call CameraController outside the lock
        if (localTakePhotoAfterAutofocus) {
            if (MyDebug.LOG) Log.d(TAG, "take_photo_after_autofocus is set")
            prepareAutoFocusPhoto()
            takePhotoWhenFocused(false)
        }
        if (MyDebug.LOG) Log.d(TAG, "autoFocusCompleted exit")
    }

    fun startCameraPreview() {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "startCameraPreview")
            debugTime = System.currentTimeMillis()
        }
        if (cameraController != null && !this.isTakingPhotoOrOnTimer && previewStartedState == PREVIEW_NOT_STARTED) {
            if (MyDebug.LOG) Log.d(TAG, "starting the camera preview")
            run {
                if (MyDebug.LOG) Log.d(TAG, "setRecordingHint: $isVideo")
                cameraController!!.setRecordingHint(this.isVideo)
            }
            setPreviewFps()
            try {
                cameraController!!.startPreview()
                countCameraStartPreview++
            } catch (e: CameraControllerException) {
                if (MyDebug.LOG) Log.d(TAG, "CameraControllerException trying to startPreview")
                e.printStackTrace()
                applicationInterface.onFailedStartPreview()
                return
            }
            this.previewStartedState = PREVIEW_STARTED
            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "startCameraPreview: time after starting camera preview: " + (System.currentTimeMillis() - debugTime)
                )
            }
            if (this.usingFaceDetection) {
                if (MyDebug.LOG) Log.d(TAG, "start face detection")
                cameraController!!.startFaceDetection()
                _facesDetected = emptyArray()
            }
        }
        this.isPreviewPaused = false
        this.setupContinuousFocusMove()
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "startCameraPreview: total time for startCameraPreview: " + (System.currentTimeMillis() - debugTime)
            )
        }
    }

    fun onAccelerometerSensorChanged(event: SensorEvent) {
        /*if( MyDebug.LOG )
    		Log.d(TAG, "onAccelerometerSensorChanged: " + event.values[0] + ", " + event.values[1] + ", " + event.values[2]);*/

        this.hasGravity = true
        for (i in 0..2) {
            //this.gravity[i] = event.values[i];
            gravity[i] = SENSOR_ALPHA * gravity[i] + (1.0f - SENSOR_ALPHA) * event.values[i]
        }
        calculateGeoDirection()

        val x = gravity[0].toDouble()
        val y = gravity[1].toDouble()
        val z = gravity[2].toDouble()
        val mag = sqrt(x * x + y * y + z * z)

        /*if( MyDebug.LOG )
			Log.d(TAG, "xyz: " + x + ", " + y + ", " + z);*/
        this.hasPitchAngle = false
        if (mag > 1.0e-8) {
            this.hasPitchAngle = true
            this.pitchAngle = asin(-z / mag) * 180.0 / Math.PI

            /*if( MyDebug.LOG )
				Log.d(TAG, "pitch: " + pitchAngle);*/
            this.hasLevelAngle = true
            this.naturalLevelAngle = atan2(-x, y) * 180.0 / Math.PI
            if (this.naturalLevelAngle < -0.0) {
                this.naturalLevelAngle += 360.0
            }

            //naturalLevelAngle = 0.0f; // test zero angle
            updateLevelAngles()
        } else {
            Log.e(TAG, "accel sensor has zero mag: $mag")
            this.hasLevelAngle = false
        }
    }

    /** This method should be called when the natural level angle, or the calibration angle, has been updated, to update the other level angle variables.
     *
     */
    fun updateLevelAngles() {
        if (hasLevelAngle) {
            this.levelAngle = this.naturalLevelAngle
            val calibratedLevelAngle: Double = applicationInterface.getCalibratedLevelAngle()
            this.levelAngle -= calibratedLevelAngle
            this.origLevelAngle = this.levelAngle
            this.levelAngle -= currentOrientation.toFloat().toDouble()
            if (this.levelAngle < -180.0) {
                this.levelAngle += 360.0
            } else if (this.levelAngle > 180.0) {
                this.levelAngle -= 360.0
            }
            /*if( MyDebug.LOG )
				Log.d(TAG, "levelAngle is now: " + levelAngle);*/
        }
    }

    fun hasLevelAngle(): Boolean {
        return this.hasLevelAngle
    }

    /* Returns true if we have the level angle ("roll"), but the pitch is not near vertically up or down (70 degrees to level).
     * This is useful as the level angle becomes unstable when device is near vertical
     */
    fun hasLevelAngleStable(): Boolean {
        if (!isTest && hasPitchAngle && abs(pitchAngle) > 70.0) {
            // note that if isTest, we always set the level angle - since the device typically lies face down when running tests...
            return false
        }
        return this.hasLevelAngle
    }

    val levelAngleUncalibrated: Double
        /** Returns the uncalibrated level angle in degrees.
         */
        get() = this.naturalLevelAngle - this.currentOrientation

    fun hasPitchAngle(): Boolean {
        return this.hasPitchAngle
    }

    fun onMagneticSensorChanged(event: SensorEvent) {
        this.hasGeomagnetic = true
        for (i in 0..2) {
            //this.geomagnetic[i] = event.values[i];
            geomagnetic[i] = SENSOR_ALPHA * geomagnetic[i] + (1.0f - SENSOR_ALPHA) * event.values[i]
        }
        calculateGeoDirection()
    }

    private fun calculateGeoDirection() {
        if (!this.hasGravity || !this.hasGeomagnetic) {
            return
        }
        if (!SensorManager.getRotationMatrix(
                this.deviceRotation,
                this.deviceInclination,
                this.gravity,
                this.geomagnetic
            )
        ) {
            return
        }
        SensorManager.remapCoordinateSystem(
            this.deviceRotation, SensorManager.AXIS_X, SensorManager.AXIS_Z,
            this.cameraRotation
        )
        val hasOldGeoDirection = hasGeoDirection
        this.hasGeoDirection = true
        //SensorManager.getOrientation(cameraRotation, geoDirection);
        SensorManager.getOrientation(cameraRotation, newGeoDirection)
        /*if( MyDebug.LOG ) {
			Log.d(TAG, "###");
			Log.d(TAG, "old geoDirection: " + (_geoDirection[0]*180/Math.PI) + ", " + (_geoDirection[1]*180/Math.PI) + ", " + (_geoDirection[2]*180/Math.PI));
		}*/
        for (i in 0..2) {
            var oldCompass = Math.toDegrees(_geoDirection[i].toDouble()).toFloat()
            val newCompass = Math.toDegrees(newGeoDirection[i].toDouble()).toFloat()
            oldCompass = if (hasOldGeoDirection) {
                lowPassFilter(oldCompass, newCompass, 0.1f, 10.0f)
            } else {
                newCompass
            }
            _geoDirection[i] = Math.toRadians(oldCompass.toDouble()).toFloat()
        }
        /*if( MyDebug.LOG ) {
			Log.d(TAG, "newGeoDirection: " + (newGeoDirection[0]*180/Math.PI) + ", " + (newGeoDirection[1]*180/Math.PI) + ", " + (newGeoDirection[2]*180/Math.PI));
			Log.d(TAG, "geoDirection: " + (_geoDirection[0]*180/Math.PI) + ", " + (_geoDirection[1]*180/Math.PI) + ", " + (_geoDirection[2]*180/Math.PI));
		}*/
    }

    /** Low pass filter, for geommagnetic angles.
     * @param oldValue Old value in degrees.
     * @param newValue New value in degrees.
     */
    private fun lowPassFilter(
        oldValue: Float,
        newValue: Float,
        smooth: Float,
        threshold: Float
    ): Float {
        // see http://stackoverflow.com/questions/4699417/android-compass-orientation-on-unreliable-low-pass-filter
        var oldValue = oldValue
        val diff = abs((newValue - oldValue).toDouble()).toFloat()
        /*if( MyDebug.LOG )
			Log.d(TAG, "diff: " + diff);*/
        oldValue = if (diff < 180.0f) {
            if (diff > threshold) {
                /*if( MyDebug.LOG )
                                 Log.d(TAG, "jump to new value");*/
                newValue
            } else {
                oldValue + smooth * (newValue - oldValue)
            }
        } else {
            if (360.0f - diff > threshold) {
                /*if( MyDebug.LOG )
                                 Log.d(TAG, "jump to new value");*/
                newValue
            } else {
                if (oldValue > newValue) {
                    (oldValue + smooth * ((360 + newValue - oldValue) % 360) + 360) % 360
                } else {
                    (oldValue - smooth * ((360 - newValue + oldValue) % 360) + 360) % 360
                }
            }
        }
        return oldValue
    }

    fun hasGeoDirection(): Boolean {
        return hasGeoDirection
    }

    val geoDirection: Double
        /** Returns the geo direction in radians.
         */
        get() = _geoDirection[0].toDouble()

    fun supportsFaceDetection(): Boolean {
        // don't log this, as we call from DrawPreview!
        return supportsFaceDetection
    }

    /** Whether optical image stabilization (OIS) is supported by the device.
     */
    fun supportsOpticalStabilization(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "supports_optical_stabilization")
        return supportsOpticalStabilization
    }

    val opticalStabilization: Boolean
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getOpticalStabilization")
            if (cameraController == null) {
                if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
                return false
            }
            return cameraController!!.opticalStabilization
        }

    /** Whether video digital stabilization is supported by the device.
     */
    fun supportsVideoStabilization(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "supports_video_stabilization")
        return supportsVideoStabilization
    }

    val videoStabilization: Boolean
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getVideoStabilization")
            if (cameraController == null) {
                if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
                return false
            }
            return cameraController!!.videoStabilization
        }

    fun supportsPhotoVideoRecording(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "supports_photo_video_recording")
        return supportsPhotoVideoRecording && !videoHighSpeed
    }

    val isVideoHighSpeed: Boolean
        /** Returns true iff we're in video mode, and a high speed fps video mode is selected.
         */
        get() {
            if (MyDebug.LOG) Log.d(TAG, "isVideoHighSpeed")
            return isVideo && videoHighSpeed
        }

    fun canDisableShutterSound(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "can_disable_shutter_sound")
        return canDisableShutterSound
    }

    val tonemapMaxCurvePoints: Int
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getTonemapMaxCurvePoints")
            return _tonemapMaxCurvePoints
        }

    val supportsTonemapCurve: Boolean
        get() {
            if (MyDebug.LOG) Log.d(TAG, "supports_tonemap_curve")
            return _supportsTonemapCurve
        }

    val supportedApertures: FloatArray?
        /** Return the supported apertures for this camera.
         */
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedApertures")
            return _supportedApertures
        }

    val supportedColorEffects: List<String>
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedColorEffects")
            return this.colorEffects
        }

    val supportedSceneModes: List<String>
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedSceneModes")
            return this.sceneModes
        }

    val supportedWhiteBalances: List<String>
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedWhiteBalances")
            return this.whiteBalances
        }

    val supportedAntiBanding: List<String>?
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedAntiBanding")
            return this.antibanding
        }

    val supportedEdgeModes: List<String>?
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedEdgeModes")
            return this.edgeModes
        }

    val supportedNoiseReductionModes: List<String>?
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedNoiseReductionModes")
            return this.noiseReductionModes
        }

    val isoKey: String
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getISOKey")
            return if (cameraController == null) "" else cameraController!!.isoKey
        }

    /** Whether manual white balance temperatures can be specified via setWhiteBalanceTemperature().
     */
    fun supportsWhiteBalanceTemperature(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "supports_white_balance_temperature")
        return this.supportsWhiteBalanceTemperature
    }

    val minimumWhiteBalanceTemperature: Int
        /** Minimum allowed white balance temperature.
         */
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getMinimumWhiteBalanceTemperature")
            return this.minTemperature
        }

    val maximumWhiteBalanceTemperature: Int
        /** Maximum allowed white balance temperature.
         */
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getMaximumWhiteBalanceTemperature")
            return this.maxTemperature
        }

    /** Returns whether a range of manual ISO values can be set. If this returns true, use
     * getMinimumISO() and getMaximumISO() to return the valid range of values. If this returns
     * false, getSupportedISOs() to find allowed ISO values.
     */
    fun supportsISORange(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "supportsISORange")
        return this.supportsIsoRange
    }

    val supportedISOs: List<String>?
        /** If supportsISORange() returns false, use this method to return a list of supported ISO values:
         * - If this is null, then manual ISO isn't supported.
         * - If non-null, this will include "auto" to indicate auto-ISO, and one or more numerical ISO
         * values.
         * If supportsISORange() returns true, then this method should not be used (and it will return
         * null). Instead, use getMinimumISO() and getMaximumISO().
         */
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedISOs")
            return this.isos
        }

    val minimumISO: Int
        /** Returns minimum ISO value. Only relevant if supportsISORange() returns true.
         */
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getMinimumISO")
            return this.minIso
        }

    val maximumISO: Int
        /** Returns maximum ISO value. Only relevant if supportsISORange() returns true.
         */
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getMaximumISO")
            return this.maxIso
        }

    fun supportsExposureTime(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "supports_exposure_time")
        return this.supportsExposureTime
    }

    val minimumExposureTime: Long
        get() {
            if (MyDebug.LOG) Log.d(
                TAG,
                "getMinimumExposureTime: $minExposureTime"
            )
            return this.minExposureTime
        }

    val maximumExposureTime: Long
        get() {
            if (MyDebug.LOG) Log.d(
                TAG,
                "getMaximumExposureTime: $maxExposureTime"
            )
            var max = maxExposureTime
            if (applicationInterface.isExpoBracketingPref() || applicationInterface.isFocusBracketingPref() || applicationInterface.isCameraBurstPref()) {
                // doesn't make sense to allow long exposure times in these modes
                max = if (applicationInterface.getBurstForNoiseReduction()) min(
                    maxExposureTime.toDouble(),
                    (1000000000L * 2).toDouble()
                ).toLong() // limit to 2s
                else min(
                    maxExposureTime.toDouble(),
                    (1000000000L / 2).toDouble()
                ).toLong() // limit to 0.5s
            }
            if (MyDebug.LOG) Log.d(TAG, "max: $max")
            return max
        }

    fun supportsExposures(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "supportsExposures")
        return this.exposures != null
    }

    val minimumExposure: Int
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getMinimumExposure")
            return this.minExposure
        }

    val maximumExposure: Int
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getMaximumExposure")
            return this.maxExposure
        }

    val currentExposure: Int
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getCurrentExposure")
            if (cameraController == null) {
                if (MyDebug.LOG) Log.d(TAG, "camera not opened!")
                return 0
            }
            return cameraController!!.exposureCompensation
        }

    /*List<String> getSupportedExposures() {
       if( MyDebug.LOG )
           Log.d(TAG, "getSupportedExposures");
       return this.exposures;
   }*/
    fun supportsExpoBracketing(): Boolean {
        /*if( MyDebug.LOG )
			Log.d(TAG, "supports_expo_bracketing");*/
        return this.supportsExpoBracketing
    }

    fun maxExpoBracketingNImages(): Int {
        if (MyDebug.LOG) Log.d(TAG, "max_expo_bracketing_n_images")
        return this.maxExpoBracketingNImages
    }

    fun supportsFocusBracketing(): Boolean {
        return this.supportsFocusBracketing
    }

    fun supportsBurst(): Boolean {
        return this.supportsBurst
    }

    /** Whether the Camera vendor extension is supported (see
     * https://developer.android.com/reference/android/hardware/camera2/CameraExtensionCharacteristics ).
     */
    fun supportsCameraExtension(extension: Int): Boolean {
        if (extension == CameraExtensionCharacteristics.EXTENSION_HDR) {
            // blocked for now, as have yet to be able to test this (seems to have no effect on Galaxy S10e;
            // not available on Pixel 6 Pro or Galaxy S24+)
            return false
        }
        return this.supportedExtensions != null && supportedExtensions!!.contains(extension)
    }

    /** Whether the camera vendor extensions supports zoom.
     */
    fun supportsZoomForCameraExtension(extension: Int): Boolean {
        return this.supportedExtensionsZoom != null && supportedExtensionsZoom!!.contains(
            extension
        )
    }

    fun supportsJpegR(): Boolean {
        return this.supportsJpegR
    }

    fun supportsRaw(): Boolean {
        return this.supportsRaw
    }

    /** Returns the horizontal angle of view in degrees (when unzoomed).
     */
    /*public float getViewAngleX() {
		return this.viewAngleX;
	}*/
    /** Returns the vertical angle of view in degrees (when unzoomed).
     */
    /*public float getViewAngleY() {
		return this.viewAngleY;
	}*/
    /** Returns the horizontal angle of view in degrees (when unzoomed).
     */
    fun getViewAngleX(forPreview: Boolean): Float {
        if (MyDebug.LOG) Log.d(TAG, "getViewAngleX: $forPreview")
        val size: CameraController.Size? =
            if (forPreview) currentPreviewSize else currentPictureSize
        if (size == null) {
            Log.e(TAG, "can't find view angle x size")
            return this.viewAngleX
        }
        val viewAspectRatio = viewAngleX / viewAngleY
        val actualAspectRatio = size.width.toFloat() / size.height.toFloat()
        /*if( MyDebug.LOG ) {
			Log.d(TAG, "viewAngleX: " + viewAngleX);
			Log.d(TAG, "viewAngleY: " + viewAngleY);
			Log.d(TAG, "viewAspectRatio: " + viewAspectRatio);
			Log.d(TAG, "actualAspectRatio: " + actualAspectRatio);
		}*/
        if (abs((actualAspectRatio - viewAspectRatio).toDouble()) < 1.0e-5f) {
            return this.viewAngleX
        } else if (actualAspectRatio > viewAspectRatio) {
            return this.viewAngleX
        } else {
            val aspectRatioScale = actualAspectRatio / viewAspectRatio
            //float actualViewAngleX = viewAngleX*aspectRatioScale;
            val actualViewAngleX = Math.toDegrees(
                2.0 * atan(
                    aspectRatioScale * tan(
                        Math.toRadians(viewAngleX.toDouble()) / 2.0
                    )
                )
            ).toFloat()
            /*if( MyDebug.LOG )
				Log.d(TAG, "actualViewAngleX: " + actualViewAngleX);*/
            return actualViewAngleX
        }
    }

    /** Returns the vertical angle of view in degrees (when unzoomed).
     */
    fun getViewAngleY(forPreview: Boolean): Float {
        if (MyDebug.LOG) Log.d(TAG, "getViewAngleY: $forPreview")
        val size: CameraController.Size? =
            if (forPreview) currentPreviewSize else currentPictureSize
        if (size == null) {
            Log.e(TAG, "can't find view angle y size")
            return this.viewAngleY
        }
        val viewAspectRatio = viewAngleX / viewAngleY
        val actualAspectRatio = size.width.toFloat() / size.height.toFloat()
        /*if( MyDebug.LOG ) {
			Log.d(TAG, "viewAngleX: " + viewAngleX);
			Log.d(TAG, "viewAngleY: " + viewAngleY);
			Log.d(TAG, "viewAspectRatio: " + viewAspectRatio);
			Log.d(TAG, "actualAspectRatio: " + actualAspectRatio);
		}*/
        if (abs((actualAspectRatio - viewAspectRatio).toDouble()) < 1.0e-5f) {
            return this.viewAngleY
        } else if (actualAspectRatio > viewAspectRatio) {
            val aspectRatioScale = viewAspectRatio / actualAspectRatio
            //float actualViewAngleY = viewAngleY*aspectRatioScale;
            val actualViewAngleY = Math.toDegrees(
                2.0 * atan(
                    aspectRatioScale * tan(
                        Math.toRadians(viewAngleY.toDouble()) / 2.0
                    )
                )
            ).toFloat()
            /*if( MyDebug.LOG )
				Log.d(TAG, "actualViewAngleY: " + actualViewAngleY);*/
            return actualViewAngleY
        } else {
            return this.viewAngleY
        }
    }

    val supportedPreviewSizes: List<CameraController.Size>?
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedPreviewSizes")
            return this._supportedPreviewSizes
        }

    val currentPreviewSize: CameraController.Size
        get() = CameraController.Size(previewW, previewH)

    val currentPreviewAspectRatio: Double
        get() = previewW.toDouble() / previewH

    /**
     * @param checkSupported If true, and a burst mode is in use (fast burst, expo, HDR), or
     * a camera vendor extension mode, and/o a constraint was set via
     * getCameraResolutionPref(), then the returned list will be filtered to
     * remove sizes that don't support burst and/or these constraints.
     */
    fun getSupportedPictureSizes(checkSupported: Boolean): List<CameraController.Size> {
        if (MyDebug.LOG) Log.d(TAG, "getSupportedPictureSizes")
        val isBurst = (cameraController != null && cameraController!!.isCaptureFastBurst)
        val isExtension = (cameraController != null && cameraController!!.isCameraExtension)
        val extension = if (isExtension) cameraController!!.getCameraExtension() else -1
        val hasConstraints =
            photoSizeConstraints != null && photoSizeConstraints!!.hasConstraints()
        if (checkSupported && (isBurst || isExtension || hasConstraints)) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "need to filter picture sizes for burst mode and/or extension mode and/or constraints"
            )
            val filteredSizes: MutableList<CameraController.Size> = ArrayList()
            for (size in photoSizes!!) {
                if (!size.supportsRequirements(isBurst, isExtension, extension)) {
                    // burst or extension mode not supported
                } else if (!photoSizeConstraints!!.satisfies(size)) {
                    // doesn't satisfy imposed constraints
                } else {
                    filteredSizes.add(size)
                }
            }
            return filteredSizes
        }
        return this.photoSizes ?: emptyList()
    }

    val currentPictureSize: CameraController.Size?
        /*public int getCurrentPictureSizeIndex() {
                   if( MyDebug.LOG )
                       Log.d(TAG, "getCurrentPictureSizeIndex");
                   return this.currentSizeIndex;
               }*/
        get() {
            if (currentSizeIndex == -1 || photoSizes.isNullOrEmpty()) return null
            return photoSizes!![currentSizeIndex]
        }

    val videoQualityHander: VideoQualityHandler
        get() = this.videoQualityHandler

    /** Returns the supported video "qualities", but unlike
     * getVideoQualityHander().supportedVideoQuality, allows filtering to the supplied
     * fpsValue.
     * @param fpsValue If not "default", the returned video qualities will be filtered to those that supported the requested
     * frame rate.
     */
    fun getSupportedVideoQuality(fpsValue: String): List<String> {
        if (MyDebug.LOG) Log.d(
            TAG,
            "getSupportedVideoQuality: $fpsValue"
        )
        if (fpsValue != "default" && supportsVideoHighSpeed) {
            try {
                val fps = fpsValue.toInt()
                if (MyDebug.LOG) Log.d(TAG, "fps: $fps")
                val filteredVideoQuality: MutableList<String> = ArrayList()
                for (quality in videoQualityHandler.supportedVideoQuality) {
                    if (MyDebug.LOG) Log.d(TAG, "quality: $quality")
                    val profile = getCamcorderProfile(quality)
                    if (MyDebug.LOG) {
                        Log.d(TAG, "    width: " + profile.videoFrameWidth)
                        Log.d(TAG, "    height: " + profile.videoFrameHeight)
                    }
                    val bestVideoSize: CameraController.Size? =
                        videoQualityHandler.findVideoSizeForFrameRate(
                            profile.videoFrameWidth,
                            profile.videoFrameHeight,
                            fps.toDouble(),
                            false
                        )
                    if (bestVideoSize != null) {
                        if (MyDebug.LOG) Log.d(TAG, "    requested frame rate is supported")
                        filteredVideoQuality.add(quality)
                    } else {
                        if (MyDebug.LOG) Log.d(TAG, "    requested frame rate is NOT supported")
                    }
                }
                return filteredVideoQuality
            } catch (_: NumberFormatException) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "fps invalid format, can't parse to int: $fpsValue"
                )
            }
        }
        return videoQualityHandler.supportedVideoQuality
    }

    /** Returns whether the user's fps preference is both non-default, and is considered a
     * "high-speed" frame rate, but not a normal frame rate. (Note, we go by the supplied
     * fpsValue, and not what the user's preference necessarily is; so this doesn't say whether
     * the Preview is currently set to normal or high speed video mode.)
     */
    fun fpsIsHighSpeed(fpsValue: String): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "fpsIsHighSpeed: $fpsValue")
        if (fpsValue != "default" && supportsVideoHighSpeed) {
            try {
                val fps = fpsValue.toInt()
                if (MyDebug.LOG) Log.d(TAG, "fps: $fps")
                // need to check both, e.g., 30fps on Nokia 8 is in fps ranges of both normal and high speed video sizes
                if (videoQualityHandler.videoSupportsFrameRate(fps)) {
                    if (MyDebug.LOG) Log.d(TAG, "fps is normal")
                    return false
                } else if (videoQualityHandler.videoSupportsFrameRateHighSpeed(fps)) {
                    if (MyDebug.LOG) Log.d(TAG, "fps is high speed")
                    return true
                } else {
                    // shouldn't be here?!
                    Log.e(TAG, "fps is neither normal nor high speed")
                    return false
                }
            } catch (_: NumberFormatException) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "fps invalid format, can't parse to int: $fpsValue"
                )
            }
        }
        if (MyDebug.LOG) Log.d(TAG, "fps is not high speed")
        return false
    }

    fun supportsVideoHighSpeed(): Boolean {
        return this.supportsVideoHighSpeed
    }

    val cameraId: Int
        /** Returns the current camera ID, or 0 if the camera isn't opened.
         */
        get() {
            if (cameraController == null) return 0
            return cameraController!!.cameraId
        }

    val cameraAPI: String
        get() {
            if (cameraController == null) return "None"
            return cameraController!!.api
        }

    /** Call when activity is resumed.
     */
    fun onResume() {
        if (MyDebug.LOG) Log.d(TAG, "onResume")
        recreatePreviewBitmap()
        this.appIsPaused = false
        this.isPaused = false
        cameraSurface.onResume()
        if (canvasView != null) canvasView!!.onResume()

        if (cameraOpenState == CameraOpenState.CAMERAOPENSTATE_CLOSING) {
            // when pausing, we close the camera on a background thread - so if this is still happening when we resume,
            // we won't be able to open the camera, so need to Open Kamera when it's closed
            if (MyDebug.LOG) Log.d(TAG, "camera still closing")
            if (closeCameraTask != null) { // just to be safe
                closeCameraTask!!.reopen = true
            } else {
                Log.e(
                    TAG,
                    "onResume: state is CAMERAOPENSTATE_CLOSING, but close_camera_task is null"
                )
            }
        } else {
            this.openCamera()
        }
    }

    /** Call when activity is paused, or the application wants to put the Preview into a paused
     * state (closing the camera etc.).
     * @param activityIsPausing Set to true if this is called because the activity is being paused;
     * set to false if the activity is not pausing.
     */
    /** Call when activity is paused.
     */
    @JvmOverloads
    fun onPause(activityIsPausing: Boolean = true) {
        if (MyDebug.LOG) Log.d(TAG, "onPause")
        this.isPaused = true
        if (activityIsPausing) this.appIsPaused =
            true // note, if activityIsPaused==false, we don't change appIsPaused, in case app was paused indicated via a separate call to onPause

        if (cameraOpenState == CameraOpenState.CAMERAOPENSTATE_OPENING) {
            if (MyDebug.LOG) Log.d(TAG, "cancel open_camera_task")
            if (openCameraTask != null) { // just to be safe
                openCameraTask!!.cancel(true)
            } else {
                Log.e(
                    TAG,
                    "onPause: state is CAMERAOPENSTATE_OPENING, but open_camera_task is null"
                )
            }
        }
        //final boolean useBackgroundThread = false;
        val useBackgroundThread = true
        this.closeCamera(useBackgroundThread, null)
        cameraSurface.onPause()
        if (canvasView != null) canvasView!!.onPause()
        freePreviewBitmap()
    }

    fun onDestroy() {
        if (MyDebug.LOG) Log.d(TAG, "on_destroy")

        cancelRefreshPreviewBitmap()
        frameAnalyzer.destroy()
        freePreviewBitmap() // in case onDestroy() called directly without onPause()

        if (rs != null) {
            try {
                rs!!.destroy() // on Android M onwards this is a NOP - instead we call RenderScript.releaseAllContexts(); in MainActivity.onDestroy()
            } catch (e: RSInvalidStateException) {
                e.printStackTrace()
            }
            rs = null
        }

        if (cameraOpenState == CameraOpenState.CAMERAOPENSTATE_CLOSING) {
            // If the camera is currently closing on a background thread, then wait until the camera has closed to be safe
            if (MyDebug.LOG) {
                Log.d(TAG, "wait for close_camera_task")
            }
            if (closeCameraTask != null) { // just to be safe
                val timeS = System.currentTimeMillis()
                try {
                    closeCameraTask!![3000, TimeUnit.MILLISECONDS] // set timeout to avoid ANR (camera resource should be freed by the OS when destroyed anyway)
                } catch (e: ExecutionException) {
                    Log.e(TAG, "exception while waiting for close_camera_task to finish")
                    e.printStackTrace()
                } catch (e: InterruptedException) {
                    Log.e(TAG, "exception while waiting for close_camera_task to finish")
                    e.printStackTrace()
                } catch (e: TimeoutException) {
                    Log.e(TAG, "exception while waiting for close_camera_task to finish")
                    e.printStackTrace()
                }
                if (MyDebug.LOG) {
                    Log.d(TAG, "done waiting for close_camera_task")
                    Log.d(
                        TAG,
                        "### time after waiting for close_camera_task: " + (System.currentTimeMillis() - timeS)
                    )
                }
            } else {
                Log.e(
                    TAG,
                    "onResume: state is CAMERAOPENSTATE_CLOSING, but close_camera_task is null"
                )
            }
        }
    }

    /*void updateUIPlacement() {
    	// we cache the preferenceUiPlacement to save having to check it in the draw() method
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@Preview.context);
		String uiPlacement = sharedPreferences.getString(MainActivity.getUIPlacementPreferenceKey(), "ui_right");
		this.uiPlacementRight = ui_placement.equals("ui_right");
    }*/
    fun onSaveInstanceState(state: Bundle?) {
        if (MyDebug.LOG) Log.d(TAG, "onSaveInstanceState")
    }

    private val fakeToastHandler = Handler()
    private var activeFakeToast: TextView? = null

    fun clearActiveFakeToast() {
        clearActiveFakeToast(false)
    }

    /** Removes any fake toast, if it exists.
     * @param calledFromHandler Should be false, unless called from the fakeToastHandler.
     */
    private fun clearActiveFakeToast(calledFromHandler: Boolean) {
        if (!calledFromHandler) {
            // important to remove the callback, otherwise when it runs, it may end up deleting a
            // new fake toast that is created after this method call, but before the callback runs
            fakeToastHandler.removeCallbacksAndMessages(null)
        }
        // run on UI thread, to avoid threading issues
        val activity = context as Activity
        activity.runOnUiThread {
            if (activeFakeToast != null) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "remove fake toast: $activeFakeToast"
                )
                val parent = activeFakeToast!!.parent
                if (parent != null) {
                    (parent as ViewGroup).removeView(activeFakeToast)
                }
                activeFakeToast = null
            }
        }
    }

    fun showToast(clearToast: ToastBoxer?, messageId: Int) {
        showToast(clearToast, resources.getString(messageId), false)
    }

    fun showToast(clearToast: ToastBoxer?, messageId: Int, useFakeToast: Boolean) {
        showToast(clearToast, resources.getString(messageId), useFakeToast)
    }

    fun showToast(clearToast: ToastBoxer?, message: String?) {
        showToast(clearToast, message, false)
    }

    fun showToast(message: String?, useFakeToast: Boolean) {
        showToast(null, message, useFakeToast)
    }

    fun showToast(clearToast: ToastBoxer?, message: String?, useFakeToast: Boolean) {
        showToast(clearToast, message, 32, useFakeToast)
    }

    /*public void showToast(final String message, final int offsetYDp, final boolean useFakeToast) {
        showToast(null, message, offsetYDp, useFakeToast);
    }*/
    /** Displays a "toast", but has several advantages over calling Android's Toast API directly.
     * We use a custom view, to rotate the toast to account for the device orientation (since
     * Open Kamera always runs in landscape).
     * @param clearToast    Only relevant if useFakeToast is false. If non-null, calls to this method
     * with the same clearToast value will overwrite the previous ones rather than
     * being queued. Note that toasts no longer seem to be queued anyway on
     * Android 9+.
     * (N.B., some callers with useFakeToast==true still supply a useFakeToast
     * for historical reasons, from when previously those calls weren't using a fake
     * toast.)
     * @param message        The message to display.
     * @param offsetYDp    The y-offset from the center of the screen. Only relevant if useFakeToast is
     * true.
     * @param useFakeToast If true, don't use Android's Toast system at all, and instead display a message
     * on the Preview.
     * This is due to problems on Android 9+ where rapidly displaying toasts (e.g., to
     * display values from a seekbar being modified) cause problems where toast sometimes
     * disappear (this happens whether using clearToast or not). Note that using
     * useFakeToast means that the toasts don't have the fade out effect.
     * Update: Toasts with custom views (Toast.setView()) are now deprecated. So
     * useFakeToast==false no longer uses a custom view. So we should now only set
     * useFakeToast==false for when we really want to use the system toast (e.g.,
     * anything that isn't when the Preview is showing such as from Settings, or when
     * we want the Android toast look such as for an error message).
     * Usages where we want to display info on the Preview should always set
     * useFakeToast==true for a consistent look.
     */
    fun showToast(
        clearToast: ToastBoxer?,
        message: String?,
        offsetYDp: Int,
        useFakeToast: Boolean
    ) {
        //final boolean useFakeToast = true;
        //final boolean useFakeToast = oldUseFakeToast;
        if (!applicationInterface.getShowToastsPref()) {
            return
        }

        if (MyDebug.LOG) {
            Log.d(TAG, "showToast: $message")
            Log.d(TAG, "use_fake_toast: $useFakeToast")
        }

        if (this.appIsPaused && useFakeToast) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "don't show fake toast as application is paused: $message"
            )
            // When targeting Android 11+, toasts with custom views won't be shown in background anyway - in theory we
            // shouldn't be making toasts when in background, but check just in case.
            // However, we no longer use custom views when useFakeToast==false, so fine to allow those - and indeed this
            // is useful for cases where the toast is created shortly before Open Kamera resumes, e.g., cancelling SAF
            // (see toast in MainActivity.onActivityResult()), or denying location permission (see toast from
            // PermissionHandler.onRequestPermissionsResult()).
            return
        }

        val activity = context as Activity
        // We get a crash on emulator at least if Toast constructor isn't run on main thread (e.g., the toast for taking a photo when on timer).
        // Also see http://stackoverflow.com/questions/13267239/toast-from-a-non-ui-thread
        // Also for the useFakeToast code, running the creation code, and the postDelayed code (and the code in clearActiveFakeToast()), on the UI thread avoids threading issues
        activity.runOnUiThread(object : Runnable {
            override fun run() {
                if (this@Preview.appIsPaused && useFakeToast) {
                    if (MyDebug.LOG) Log.e(
                        TAG,
                        "don't show fake toast as application is paused: $message"
                    )
                    // see note above
                    return
                }

                val scale = resources.displayMetrics.density
                val offsetY = (offsetYDp * scale + 0.5f).toInt() // convert dps to pixels
                var shadowRadius = (2.0f * scale + 0.5f) // convert pt to pixels
                shadowRadius = max(shadowRadius.toDouble(), 1.0).toFloat()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "shadow_radius: $shadowRadius"
                )

                if (useFakeToast) {
                    if (activeFakeToast != null) {
                        // re-use existing fake toast
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "re-use fake toast: $activeFakeToast"
                        )
                        activeFakeToast!!.text = message
                        activeFakeToast!!.setPadding(0, offsetY, 0, 0)
                        activeFakeToast!!.invalidate() // make sure the view is redrawn
                    } else {
                        val activity = context as Activity

                        @SuppressLint("InflateParams") // we add the view a few lines below
                        val view: View =
                            LayoutInflater.from(activity).inflate(R.layout.toast_textview, null)
                        activeFakeToast = view.findViewById(R.id.text_view)
                        activeFakeToast!!.setShadowLayer(shadowRadius, 0.0f, 0.0f, Color.BLACK)
                        activeFakeToast!!.setPadding(0, offsetY, 0, 0)
                        activeFakeToast!!.text = message
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "create new fake toast: $activeFakeToast"
                        )
                        val rootLayout = activity.findViewById<FrameLayout>(android.R.id.content)
                        rootLayout.addView(activeFakeToast)
                    }

                    // in theory the fakeToastHandler should only have a callback on it if re-using an existing fake toast,
                    // but we remove callbacks always just in case
                    fakeToastHandler.removeCallbacksAndMessages(null)

                    fakeToastHandler.postDelayed({
                        if (MyDebug.LOG) Log.d(TAG, "destroy fake toast due to time expired")
                        clearActiveFakeToast(true)
                    }, 2000) // supposedly matches Toast.LENGTH_SHORT

                    return
                }

                /*if( clearToast != null && clear_toast.toast != null )
					clear_toast.toast.cancel();

				Toast toast = new Toast(activity);
				if( clearToast != null )
					clear_toast.toast = toast;*/
                if (MyDebug.LOG) {
                    Log.d(TAG, "clear_toast: $clearToast")
                    if (clearToast != null) Log.d(TAG, "clear_toast.toast: " + clearToast.toast)
                    Log.d(TAG, "last_toast: $lastToast")
                    Log.d(
                        TAG,
                        "last_toast_time_ms: $lastToastTimeMs"
                    )
                }
                // This method is better, as otherwise a previous toast (with different or no clearToast) never seems to clear if we repeatedly issue new toasts - this doesn't happen if we reuse existing toasts if possible
                // However should only do this if the previous toast was the most recent toast (to avoid messing up ordering)
                val toast: Toast
                val timeNow = System.currentTimeMillis()
                /*
                // We recreate a toast every 2s, to workaround Android toast bug that calling show() no longer seems to extend the toast duration!
                // (E.g., see bug where toasts for sliders disappear after a while if continually moving the slider.)
                if( clearToast != null && clear_toast.toast != null && clear_toast.toast == lastToast && timeNow < lastToastTimeMs+2000) {
                    if( MyDebug.LOG )
                        Log.d(TAG, "reuse last toast: " + lastToast);
                    toast = clear_toast.toast;
                    // for performance, important to reuse the same view, instead of creating a new one (otherwise we get jerky preview update e.g. for changing manual focus slider)
                    TextView view = (TextView)toast.view;
                    view.setText(message);
                    view.setPadding(0, offsetY, 0, 0);
                    view.invalidate(); // make sure the toast is redrawn
                    toast.setView(view);
                }
                else*/
                run {
                    if (clearToast?.toast != null) {
                        if (MyDebug.LOG) Log.d(TAG, "cancel last toast: " + clearToast.toast)
                        clearToast.toast!!.cancel()
                    }
                    //toast = new Toast(activity);
                    toast = Toast.makeText(activity, message, Toast.LENGTH_SHORT)
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "created new toast: $toast"
                    )
                    if (clearToast != null) clearToast.toast = toast
                    /*@SuppressLint("InflateParams") // we add the view to the toast
                    final View view = LayoutInflater.from(activity).inflate(R.layout.toast_textview, null);
                    TextView text = view.findViewById(R.id.text_view);
                    text.setShadowLayer(shadowRadius, 0.0f, 0.0f, Color.BLACK);
                    text.setText(message);
                    view.setPadding(0, offsetY, 0, 0);
                    toast.setView(text);
                    toast.setGravity(Gravity.CENTER, 0, 0);*/
                    lastToastTimeMs = timeNow
                }
                //toast.setDuration(Toast.LENGTH_SHORT);
                if (!(this@Preview.context as Activity).isFinishing) {
                    // Workaround for crash due to bug in Android 7.1 when activity is closing whilst toast shows.
                    // This was fixed in Android 8, but still good to fix the crash on Android 7.1! See
                    // https://stackoverflow.com/questions/47548317/what-belong-is-badtokenexception-at-classes-of-project and
                    // https://github.com/drakeet/ToastCompat#why .
                    toast.show()
                }
                lastToast = toast
            }
        })
    }

    var uIRotation: Int
        get() = this.uiRotation
        set(uiRotation) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "setUIRotation: $uiRotation"
            )
            this.uiRotation = uiRotation
        }

    /** If geotagging is enabled, pass the location info to the camera controller (for photos).
     */
    private fun updateParametersFromLocation() {
        if (MyDebug.LOG) Log.d(TAG, "updateParametersFromLocation")
        if (cameraController != null) {
            val storeLocation: Boolean = applicationInterface.getGeotaggingPref()
            if (storeLocation && applicationInterface.getLocation() != null) {
                val location: Location = applicationInterface.getLocation()!!
                if (MyDebug.LOG) {
                    Log.d(TAG, "updating parameters from location...")
                    // don't log location, in case of privacy!
                }
                cameraController!!.setLocationInfo(location)
            } else {
                if (MyDebug.LOG) Log.d(TAG, "removing location data from parameters...")
                cameraController!!.removeLocationInfo()
            }
        }
    }

    fun enablePreviewBitmap(usePreviewBitmapSmall: Boolean, usePreviewBitmapFull: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "enablePreviewBitmap")
        if (cameraSurface is TextureView) {
            isPreviewBitmapEnabled = true
            this.usePreviewBitmapSmall = usePreviewBitmapSmall
            this.usePreviewBitmapFull = usePreviewBitmapFull
            recreatePreviewBitmap()
        }
    }

    fun disablePreviewBitmap() {
        if (MyDebug.LOG) Log.d(TAG, "disablePreviewBitmap")
        freePreviewBitmap()
        isPreviewBitmapEnabled = false
        usePreviewBitmapSmall = false
        usePreviewBitmapFull = false
        histogramScript = null // to help garbage collection
    }

    fun usePreviewBitmapSmall(): Boolean {
        return this.isPreviewBitmapEnabled && this.usePreviewBitmapSmall
    }

    fun usePreviewBitmapFull(): Boolean {
        return this.isPreviewBitmapEnabled && this.usePreviewBitmapFull
    }

    fun refreshPreviewBitmapTaskIsRunning(): Boolean {
        return isAnalyzingFrame
    }

    /** Runs the supplied runnable, but waits until the refreshPreviewBitmapTask is no longer running.
     */
    private fun runForPreviewTask(runnable: Runnable) {
        if (MyDebug.LOG) Log.d(TAG, "runForPreviewTask")
        if (!refreshPreviewBitmapTaskIsRunning()) {
            if (MyDebug.LOG) Log.d(TAG, "refreshPreviewBitmapTask not running, can run runnable")
            runnable.run()
        } else {
            if (MyDebug.LOG) Log.d(
                TAG,
                "refreshPreviewBitmapTask still running, wait before running runnable"
            )
            val handler = Handler()
            val delay: Long = 500
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (!refreshPreviewBitmapTaskIsRunning()) {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "refreshPreviewBitmapTask not running now, can run runnable"
                        )
                        runnable.run()
                    } else {
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "refreshPreviewBitmapTask still running, wait again before running runnable"
                        )
                        handler.postDelayed(this, delay)
                    }
                }
            }, delay)
        }
    }

    /* Recycles the supplied bitmap, but if the refreshPreviewBitmapTask is running, waits until
	   it isn't running.
	 */
    private fun recycleBitmapForPreviewTask(bitmap: Bitmap) {
        if (MyDebug.LOG) Log.d(TAG, "recycleBitmapForPreviewTask")
        // Don't want to recycle bitmap whilst thread is running!
        // See test testPreviewBitmap().
        runForPreviewTask { bitmap.recycle() }
    }

    private fun freePreviewBitmap() {
        if (MyDebug.LOG) Log.d(TAG, "freePreviewBitmap")
        cancelRefreshPreviewBitmap()
        histogram = null
        if (previewBitmap != null) {
            recycleBitmapForPreviewTask(previewBitmap!!)
            // It's okay to set previewBitmap to null even if refreshPreviewBitmapTask is currently running in the background
            // as it takes its own reference. But we shouldn't recycle until the background thread is complete.
            previewBitmap = null
        }

        // It's okay to set these to -1 even if refreshPreviewBitmapTask is currently running in the background
        // as it takes its own reference. But we shouldn't recycle until the background thread is complete.
        previewBitmapFullW = -1
        previewBitmapFullH = -1
        preShotsRingBuffer.flush() // even if we're recreating the previewBitmapFull, it might be at a different resolution, so safest to flush the previous pre-shots
        if (usePreviewBitmapFull) {
            runForPreviewTask {
                preShotsRingBuffer.flush() // important to flush again, in case the refreshPreviewBitmapTask already running in the background added a new image
            }
        }

        freeZebraStripesBitmap()
        freeFocusPeakingBitmap()
    }

    private fun recreatePreviewBitmap() {
        if (MyDebug.LOG) {
            Log.d(TAG, "recreatePreviewBitmap")
            Log.d(TAG, "textureview_w: $textureViewW")
            Log.d(TAG, "textureview_h: $textureViewH")
            Log.d(TAG, "want_preview_bitmap: $isPreviewBitmapEnabled")
            Log.d(
                TAG,
                "use_preview_bitmap_small: $usePreviewBitmapSmall"
            )
            Log.d(
                TAG,
                "use_preview_bitmap_full: $usePreviewBitmapFull"
            )
        }
        freePreviewBitmap()

        // Note we need to take into account getDisplayRotationDegrees(), as TextureView.getBitmap()
        // returns the texture in the "natural" orientation of the device - it doesn't take the transform
        // we've applied in configureTransform() into account.
        if (isPreviewBitmapEnabled && usePreviewBitmapSmall) {
            if (MyDebug.LOG) Log.d(TAG, "create preview_bitmap")
            val downscale = 4
            var bitmapWidth = textureViewW / downscale
            var bitmapHeight = textureViewH / downscale
            val rotation = getDisplayRotationDegrees(false)
            if (rotation == 90 || rotation == 270) {
                val dummy = bitmapWidth
                bitmapWidth = bitmapHeight
                bitmapHeight = dummy
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "bitmap_width: $bitmapWidth")
                Log.d(TAG, "bitmap_height: $bitmapHeight")
                Log.d(TAG, "rotation: $rotation")
            }
            try {
                /*if( true )
					throw new IllegalArgumentException(); // test*/
                previewBitmap = createBitmap(bitmapWidth, bitmapHeight)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "failed to create preview_bitmap")
                e.printStackTrace()
                // Note if we failed to create the previewBitmap, we don't call disablePreviewBitmap() or set wantPreviewBitmap to false,
                // otherwise DrawPreview will keep trying.
            }
            createZebraStripesBitmap()
            createFocusPeakingBitmap()
        }
        if (isPreviewBitmapEnabled && usePreviewBitmapFull) {
            if (MyDebug.LOG) Log.d(TAG, "set up preview_bitmap_full")
            var bitmapWidth = textureViewW
            var bitmapHeight = textureViewH
            val rotation = getDisplayRotationDegrees(false)
            if (rotation == 90 || rotation == 270) {
                val dummy = bitmapWidth
                bitmapWidth = bitmapHeight
                bitmapHeight = dummy
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "bitmap_width: $bitmapWidth")
                Log.d(TAG, "bitmap_height: $bitmapHeight")
                Log.d(TAG, "rotation: $rotation")
            }
            this.previewBitmapFullW = bitmapWidth
            this.previewBitmapFullH = bitmapHeight
        }
    }

    private fun freeZebraStripesBitmap() {
        if (MyDebug.LOG) Log.d(TAG, "freeZebraStripesBitmap")
        if (zebraStripesBitmapBuffer != null) {
            recycleBitmapForPreviewTask(zebraStripesBitmapBuffer!!)
            zebraStripesBitmapBuffer = null
        }
        if (zebraStripesBitmap != null) {
            zebraStripesBitmap!!.recycle()
            zebraStripesBitmap = null
        }
    }

    private fun createZebraStripesBitmap() {
        if (MyDebug.LOG) Log.d(TAG, "createZebraStripesBitmap")
        // n.b., previewBitmap might be null if we failed to create the bitmap
        if (wantZebraStripes && previewBitmap != null) {
            try {
                /*if( true )
					throw new IllegalArgumentException(); // test*/
                zebraStripesBitmapBuffer =
                    createBitmap(previewBitmap!!.width, previewBitmap!!.height)
                // zebraStripesBitmap itself is created dynamically when generating the zebra stripes
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "failed to create zebra_stripes_bitmap_buffer")
                e.printStackTrace()
            }
        }
    }

    private fun freeFocusPeakingBitmap() {
        if (MyDebug.LOG) Log.d(TAG, "freeFocusPeakingBitmap")
        if (focusPeakingBitmapBuffer != null) {
            recycleBitmapForPreviewTask(focusPeakingBitmapBuffer!!)
            focusPeakingBitmapBuffer = null
        }
        if (focusPeakingBitmapBufferTemp != null) {
            recycleBitmapForPreviewTask(focusPeakingBitmapBufferTemp!!)
            focusPeakingBitmapBufferTemp = null
        }
        if (focusPeakingBitmap != null) {
            focusPeakingBitmap!!.recycle()
            focusPeakingBitmap = null
        }
    }

    private fun createFocusPeakingBitmap() {
        if (MyDebug.LOG) Log.d(TAG, "createFocusPeakingBitmap")
        // n.b., previewBitmap might be null if we failed to create the bitmap
        if (wantFocusPeaking and (previewBitmap != null)) {
            try {
                /*if( true )
					throw new IllegalArgumentException(); // test*/
                focusPeakingBitmapBuffer =
                    createBitmap(previewBitmap!!.width, previewBitmap!!.height)
                focusPeakingBitmapBufferTemp =
                    createBitmap(previewBitmap!!.width, previewBitmap!!.height)
                // focusPeakingBitmap itself is created dynamically when generating
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "failed to create focus_peaking_bitmap_buffers")
                e.printStackTrace()
            }
        }
    }

    fun enableHistogram(histogramType: HistogramType) {
        this.wantHistogram = true
        this.histogramType = histogramType
    }

    fun disableHistogram() {
        this.wantHistogram = false
    }

    fun enableZebraStripes(
        zebraStripesThreshold: Int,
        zebraStripesColorForeground: Int,
        zebraStripesColorBackground: Int
    ) {
        this.wantZebraStripes = true
        this.zebraStripesThreshold = zebraStripesThreshold
        this.zebraStripesColorForeground = zebraStripesColorForeground
        this.zebraStripesColorBackground = zebraStripesColorBackground
        if (this.zebraStripesBitmapBuffer == null) {
            createZebraStripesBitmap()
        }
    }

    fun disableZebraStripes() {
        if (this.wantZebraStripes) {
            this.wantZebraStripes = false
            freeZebraStripesBitmap()
        }
    }

    fun enableFocusPeaking() {
        this.wantFocusPeaking = true
        if (this.focusPeakingBitmapBuffer == null) {
            createFocusPeakingBitmap()
        }
    }

    fun disableFocusPeaking() {
        if (this.wantFocusPeaking) {
            this.wantFocusPeaking = false
            freeFocusPeakingBitmap()
        }
    }

    fun enablePreShots() {
        this.wantPreShots = true
    }

    fun disablePreShots() {
        if (wantPreShots) {
            this.wantPreShots = false
            preShotsRingBuffer.flush() // so we don't have old pre-shots hanging around if it's later enabled
        }
    }

    val preShotsRingBuffer: PreShotsRingBuffer = PreShotsRingBuffer()

    init {
        if (MyDebug.LOG) {
            Log.d(TAG, "new Preview")
        }

        this.applicationInterface = applicationInterface

        val activity = context as Activity
        if (activity.intent != null && activity.intent.extras != null) {
            // whether called from testing
            isTest = activity.intent.extras!!.getBoolean("test_project")
            isTestJunit4 = activity.intent.extras!!.getBoolean("test_project_junit4")
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "is_test: $isTest")
            Log.d(TAG, "is_test_junit4: $isTestJunit4")
        }

        this.usingAndroidL = applicationInterface.useCamera2()
        if (MyDebug.LOG) {
            Log.d(TAG, "using_android_l?: $usingAndroidL")
        }

        var usingTextureView = false
        if (usingAndroidL) {
            // use a TextureView for Android L - had bugs with SurfaceView not resizing properly on Nexus 7; and good to use a TextureView anyway
            // ideally we'd use a TextureView for older camera API too, but sticking with SurfaceView to avoid risk of breaking behavior
            usingTextureView = true
        }

        if (usingTextureView) {
            this.cameraSurface = MyTextureView.createInstance(context, this)
            // a TextureView can't be used both as a camera preview, and used for drawing on, so we use a separate CanvasView
            this.canvasView = CanvasView(context, this)
            cameraControllerManager = CameraControllerManager2(context)
        } else {
            this.cameraSurface = MySurfaceView(context, this)
            cameraControllerManager = CameraControllerManager1()
        }

        /*{
			FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
			layoutParams.gravity = Gravity.CENTER;
			cameraSurface.view.setLayoutParams(layoutParams);
		}*/
        parent.addView(cameraSurface.view)
        if (canvasView != null) {
            parent.addView(canvasView)
        }
    }

    private fun refreshPreviewBitmap() {
        val refreshHistogramRateMs = 200
        // if wantPreShots==true, this should take priority over other options as it affects the interval between the pre-shots
        // but the value shouldn't be too long, as then zebra stripes or focus peaking (if they are enabled) would be too jerky
        val refreshTime =
            (if (wantPreShots) PRESHOT_INTERVAL_MS else if (wantZebraStripes || wantFocusPeaking) 83 else refreshHistogramRateMs).toLong()
        val timeNow = System.currentTimeMillis()
        if (isPreviewBitmapEnabled &&
            ((usePreviewBitmapSmall && previewBitmap != null) || (usePreviewBitmapFull && previewBitmapFullW != -1 && previewBitmapFullH != -1))
            && !isPaused && !applicationInterface.isPreviewInBackground() && !refreshPreviewBitmapTaskIsRunning() && timeNow > lastPreviewBitmapTimeMs + refreshTime
        ) {
            if (MyDebug.LOG) Log.d(TAG, "refreshPreviewBitmap")
            // even if we're running the background task at a faster rate (due to zebra stripes etc.), we still update the histogram
            // at the standard rate
            val updateHistogram =
                wantHistogram && timeNow > lastHistogramTimeMs + refreshHistogramRateMs
            if (MyDebug.LOG) {
                Log.d(TAG, "update_histogram: $updateHistogram")
                Log.d(TAG, "want_histogram: $wantHistogram")
                Log.d(TAG, "time_now: $timeNow")
                Log.d(
                    TAG,
                    "last_preview_bitmap_time_ms: $lastPreviewBitmapTimeMs"
                )
                Log.d(
                    TAG,
                    "last_histogram_time_ms: $lastHistogramTimeMs"
                )
            }

            this.lastPreviewBitmapTimeMs = timeNow
            if (updateHistogram) {
                this.lastHistogramTimeMs = timeNow
            }

            var updatePreshot = false
            if (cameraController == null || cameraController?.shouldCoverPreview() == true) {
                // don't take preshot - instead flush
                preShotsRingBuffer.flush()
            } else if (wantPreShots) {
                updatePreshot = true
            }

            val textureView = cameraSurface as? TextureView
            if (previewBitmap != null && textureView != null) {
                try {
                    textureView.getBitmap(previewBitmap!!)
                } catch (e: Exception) {
                    if (MyDebug.LOG) Log.e(TAG, "failed to getBitmap: ${e.message}")
                }
            }

            if (previewBitmapFullW != -1 && previewBitmapFullH != -1 && updatePreshot && textureView != null) {
                try {
                    val fullCopy = createBitmap(previewBitmapFullW, previewBitmapFullH)
                    textureView.getBitmap(fullCopy)
                    if (isTakingPhoto) {
                        fullCopy.recycle()
                    } else {
                        preShotsRingBuffer.add(fullCopy)
                    }
                } catch (e: Exception) {
                    if (MyDebug.LOG) Log.e(TAG, "failed to create preview full bitmap: ${e.message}")
                }
            }

            if (previewBitmap != null) {
                val rotationDeg = getDisplayRotationDegrees(false)
                val config = FrameAnalysisConfig(
                    wantHistogram = updateHistogram,
                    histogramType = this.histogramType,
                    wantZebraStripes = this.wantZebraStripes,
                    zebraStripesThreshold = this.zebraStripesThreshold,
                    zebraStripesColorForeground = this.zebraStripesColorForeground,
                    zebraStripesColorBackground = this.zebraStripesColorBackground,
                    wantFocusPeaking = this.wantFocusPeaking,
                    rotationDegrees = rotationDeg
                )

                isAnalyzingFrame = true
                analysisJob = CoroutineScope(Dispatchers.Default).launch {
                    val result = frameAnalyzer.analyzeFrameDirect(
                        previewBitmap = previewBitmap!!,
                        config = config,
                        zebraStripesBuffer = zebraStripesBitmapBuffer,
                        focusPeakingBuffer = focusPeakingBitmapBuffer,
                        focusPeakingBufferTemp = focusPeakingBitmapBufferTemp
                    )
                    withContext(Dispatchers.Main) {
                        isAnalyzingFrame = false
                        val activity = context as? Activity
                        if (activity != null && !activity.isFinishing && result != null) {
                            if (result.histogram != null) {
                                histogram = result.histogram
                            }
                            if (zebraStripesBitmap != null) {
                                zebraStripesBitmap?.recycle()
                            }
                            zebraStripesBitmap = result.zebraStripesBitmap

                            if (focusPeakingBitmap != null) {
                                focusPeakingBitmap?.recycle()
                            }
                            focusPeakingBitmap = result.focusPeakingBitmap
                        }
                    }
                }
            }
        }
    }

    private fun cancelRefreshPreviewBitmap() {
        if (MyDebug.LOG) Log.d(TAG, "cancelRefreshPreviewBitmap")
        analysisJob?.cancel()
        isAnalyzingFrame = false
    }

    val isVideoRecording: Boolean
        get() = videoRecorder != null && videoStartTimeSet

    val isVideoRecordingPaused: Boolean
        get() = isVideoRecording && videoRecorderIsPaused

    /** Returns the time of the current video.
     * In case of restarting due to max filesize (whether on Android 8+ or not), this includes the
     * total time of all the previous video files too, unless thisFileOnly==true;
     */
    fun getVideoTime(thisFileOnly: Boolean): Long {
        val offset = if (thisFileOnly) videoTimeLastMaxfilesizeRestart else 0
        if (this.isVideoRecordingPaused) {
            return videoAccumulatedTime - offset
        }
        val timeNow = System.currentTimeMillis()
        return timeNow - videoStartTime + videoAccumulatedTime - offset
    }

    val maxAmplitude: Int
        get() = if (videoRecorder != null) videoRecorder!!.maxAmplitude else 0

    val frameRate: Long
        /** Returns the frame rate that the preview's surface or canvas view should be updated.
         */
        get() {
            /* See https://stackoverflow.com/questions/44594711/slow-rendering-when-updating-textview ,
               https://stackoverflow.com/questions/44233870/how-to-fix-slow-rendering-android-vitals -
               there is evidence that using an infrequent update actually results in poorer performance,
               due to devices running in a lower power state, but Google Play analytics do not take this
               into consideration. Thus, we are forced to request updates at 60fps whether we need them
               or not. I can reproduce this giving improved performance on OnePlus 3T for old and
               Camera2 API. Testing suggests this does not seem to adversely affect battery life.
               This is limited to Android 7+, to avoid causing problems on older devices (which don't
               contribute to Google Analytics anyway).
               If we ever are able to use lower frame rates in the future, remember we'll still need a high
               frame rate when applying the dimming effect when reopening or updating the camera (see
               DrawPreview.setDimPreview()) (especially for MainActivity.updateForSettings() when we
               pause/unpause the preview instead of reopening the camera).
               Update: On more recent Android versions, this effect no longer seems to happen, and on
               Android 13 (at least Pixel 6 Pro), we see the reverse (but more reasonable) behavior
               where we have fewer janky frames with a longer frame rate. Behavior is much better at
               32ms compared to 16ms; and we shouldn't go any slower (firstly so that UI still runs
               smoothly; secondly for dimming effect as noted above).
             */
            //
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return 32
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (isTestJunit4) {
                    // see https://stackoverflow.com/questions/29550508/espresso-freezing-on-view-with-looping-animation
                    return 32
                }
                return 16
            }
            // old behavior: avoid overloading ui thread when taking photo
            return (if (this.isTakingPhoto) 500 else 100).toLong()
        }

    val isTakingPhoto: Boolean
        get() = this.phase == PHASE_TAKING_PHOTO

    fun usingCamera2API(): Boolean {
        return this.usingAndroidL
    }

    fun supportsFocus(): Boolean {
        return this.supportedFocusValues != null
    }

    /** Whether flash is supported by the camera.
     */
    fun supportsFlash(): Boolean {
        return this.supportedFlashValues != null
    }

    fun supportsExposureLock(): Boolean {
        return this.isExposureLockSupported
    }

    fun supportsWhiteBalanceLock(): Boolean {
        return this.isWhiteBalanceLockSupported
    }

    fun supportsZoom(): Boolean {
        return this.hasZoom
    }

    fun hasFocusArea(): Boolean {
        return this.hasFocusArea
    }

    val focusPos: Pair<Int, Int>
        get() {
            // note, we don't store the screen coordinates, as they may become out of date in the
            // screen orientation changes (if MainActivity.lockToLandscape==false)
            val coords = floatArrayOf(focusCameraX, focusCameraY)
            val matrix = getCameraToPreviewMatrix()
            matrix.mapPoints(coords)
            return Pair(coords[0].toInt(), coords[1].toInt())
        }

    val isTakingPhotoOrOnTimer: Boolean
        get() = this.phase == PHASE_TAKING_PHOTO || this.phase == PHASE_TIMER

    val isOnTimer: Boolean
        get() = this.phase == PHASE_TIMER

    var isPreviewPaused: Boolean
        get() = this.phase == PHASE_PREVIEW_PAUSED
        private set(paused) {
            if (MyDebug.LOG) Log.d(TAG, "setPreviewPaused: $paused")
            applicationInterface.hasPausedPreview(paused)
            if (paused) {
                this.phase = PHASE_PREVIEW_PAUSED
                // shouldn't call applicationInterface.cameraInOperation(true, ...), as should already have done when we started to take a photo (or above when exiting immersive mode)
            } else {
                this.phase = PHASE_NORMAL
                /*applicationInterface.cameraInOperation(false, false);
                if( isVideo )
                    applicationInterface.cameraInOperation(false, true);*/
                // Need to call camerainOperation for when taking photo with pause preview option;
                // also needed so that the GUI is set up correctly (via MainUI.showGUI()), for things like on-screen icons that are
                // only shown depending on user options and device support.
                applicationInterface.cameraInOperation(inOperation = false, isVideo = false)
            }
        }

    val isFocusWaiting: Boolean
        get() = focusSuccess == FOCUS_WAITING

    val isFocusRecentSuccess: Boolean
        get() = focusSuccess == FOCUS_SUCCESS

    fun timeSinceStartedAutoFocus(): Long {
        if (focusStartedTime != -1L) return System.currentTimeMillis() - focusStartedTime
        return 0
    }

    val isFocusRecentFailure: Boolean
        get() = focusSuccess == FOCUS_FAILED

    /** Whether we can skip the autofocus before taking a photo.
     */
    private fun recentlyFocused(): Boolean {
        return this.successfullyFocused && System.currentTimeMillis() < this.successfullyFocusedTime + 5000
    }

    val facesDetected: Array<CameraController.Face>?
        /** If non-null, this returned array will store the currently detected faces (if face recognition
         * is enabled). The face.temp rect will store the face rectangle in screen coordinates.
         */
        get() {
            if (_facesDetected.isNotEmpty()) {
                // note, we don't store the screen coordinates, as they may become out of date in the
                // screen orientation changes (if MainActivity.lockToLandscape==false)
                val matrix = getCameraToPreviewMatrix()
                for (face in _facesDetected) {
                    if (face != null) {
                        faceRect.set(face.rect)
                        matrix.mapRect(faceRect)
                        faceRect.round(face.temp)
                    }
                }
            }
            // FindBugs warns about returning the array directly, but in fact we need to return direct access rather than copying, so that the on-screen display of faces rectangles updates
            return _facesDetected.filterNotNull().toTypedArray()
        }

    val zoomRatio: Float
        /** Returns the current zoom factor of the camera. Always returns 1.0f if Zoom isn't supported.
         */
        get() {
            if (zoomRatios == null) return 1.0f
            val zoomFactor: Int = cameraController!!.zoom
            return zoomRatios!![zoomFactor] / 100.0f
        }

    fun getZoomRatio(index: Int): Float {
        if (zoomRatios == null) return 1.0f
        return zoomRatios!![index] / 100.0f
    }

    val minZoomRatio: Float
        get() {
            if (zoomRatios == null) return 1.0f
            return zoomRatios!![0] / 100.0f
        }

    val maxZoomRatio: Float
        get() {
            if (zoomRatios == null) return 1.0f
            return zoomRatios!![maxZoom] / 100.0f
        }

    fun hasPhysicalCameras(): Boolean {
        return this.physicalCameras != null
    }

    companion object {
        private const val TAG = "Preview"

        // if the remaining max time after restart is less than this, don't restart
        private const val MIN_SAFE_RESTART_VIDEO_TIME = 1000L
        private const val PREVIEW_NOT_STARTED = 0
        private const val PREVIEW_IS_STARTING = 1
        private const val PREVIEW_STARTED = 2
        private const val PHASE_NORMAL = 0
        private const val PHASE_TIMER = 1
        private const val PHASE_TAKING_PHOTO = 2
        private const val PHASE_PREVIEW_PAUSED = 3 // the paused state after taking a photo
        private const val FOCUS_WAITING = 0
        private const val FOCUS_SUCCESS = 1
        private const val FOCUS_FAILED = 2
        private const val FOCUS_DONE = 3

        // accelerometer and geomagnetic sensor info
        private const val SENSOR_ALPHA = 0.8f // for filter
        private fun formatFloatToString(f: Float): String {
            val i = f.toInt()
            if (f == i.toFloat()) return i.toString()
            return String.format(Locale.getDefault(), "%.2f", f)
        }

        private fun greatestCommonFactor(a: Int, b: Int): Int {
            var a = a
            var b = b
            while (b > 0) {
                val temp = b
                b = a % b
                a = temp
            }
            return a
        }

        private fun getAspectRatio(width: Int, height: Int): String {
            var width = width
            var height = height
            val gcf = greatestCommonFactor(width, height)
            if (gcf > 0) {
                // had a Google Play crash due to gcf being 0!? Implies width must be zero
                width /= gcf
                height /= gcf
            }
            return "$width:$height"
        }

        fun getMPString(width: Int, height: Int): String {
            val mp = (width * height) / 1000000.0f
            return formatFloatToString(mp) + "MP"
        }

        private fun getBurstString(resources: Resources, supportsBurst: Boolean): String {
            // should return empty string if supportsBurst==true, as this is also used for video resolution strings
            return if (supportsBurst) "" else ", " + resources.getString(R.string.no_burst)
        }

        fun getAspectRatioMPString(
            resources: Resources,
            width: Int,
            height: Int,
            supportsBurst: Boolean
        ): String {
            return "(" + getAspectRatio(width, height) + ", " + getMPString(
                width,
                height
            ) + getBurstString(resources, supportsBurst) + ")"
        }

        /** Returns the size in sizes that is the closest aspect ratio match to targetRatio, but (if maxSize is non-null) is not
         * larger than maxSize (in either width or height).
         */
        private fun getClosestSize(
            sizes: List<CameraController.Size>,
            targetRatio: Double,
            maxSize: CameraController.Size?
        ): CameraController.Size? {
            if (MyDebug.LOG) Log.d(TAG, "getClosestSize()")
            var optimalSize: CameraController.Size? = null
            var minDiff = Double.MAX_VALUE
            for (size in sizes) {
                val ratio: Double = size.width.toDouble() / size.height
                if (maxSize != null) {
                    if (size.width > maxSize.width || size.height > maxSize.height) continue
                }
                if (abs(ratio - targetRatio) < minDiff) {
                    optimalSize = size
                    minDiff = abs(ratio - targetRatio)
                }
            }
            return optimalSize
        }

        /** Returns a picture size to set during video mode.
         * In theory, the picture size shouldn't matter in video mode, but the stock Android camera sets a picture size
         * which is the largest that matches the video's aspect ratio.
         * This seems necessary to work around an aspect ratio bug introduced in Android 4.4.3 (on Nexus 7 at least): http://code.google.com/p/android/issues/detail?id=70830
         * which results in distorted aspect ratio on preview and recorded video!
         * Setting the picture size in video mode is also needed for taking photos when recording video. We need to make sure we
         * set photo resolutions that are supported by Android when recording video. For old camera API, this doesn't matter so much
         * (if we request too high, it'll automatically reduce the photo resolution), but still good to match the aspect ratio. For
         * Camera2 API, see notes at "https://developer.android.com/reference/android/hardware/camera2/CameraDevice.html#createCaptureSession(java.util.List<android.view.Surface>, android.hardware.camera2.CameraCaptureSession.StateCallback, android.os.Handler)" .
        </android.view.Surface> */
        fun getOptimalVideoPictureSize(
            sizes: List<CameraController.Size>?,
            targetRatio: Double,
            maxVideoSize: CameraController.Size
        ): CameraController.Size? {
            if (MyDebug.LOG) Log.d(TAG, "getOptimalVideoPictureSize()")
            val aspectTolerance = 0.05
            if (sizes == null) return null
            if (MyDebug.LOG) Log.d(
                TAG,
                "max_video_size: " + maxVideoSize.width + ", " + maxVideoSize.height
            )
            var optimalSize: CameraController.Size? = null
            // Try to find the largest size that matches aspect ratio.
            // But don't choose a size that's larger than the max video size (as this isn't supported for taking photos when
            // recording video for devices with LIMITED support in Camera2 mode).
            // In theory, for devices FULL Camera2 support, if the current video resolution is smaller than the max preview resolution,
            // we should be able to support larger photo resolutions, but this is left to future.
            for (size in sizes) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "    supported preview size: " + size.width + ", " + size.height
                )
                val ratio: Double = size.width.toDouble() / size.height
                if (abs(ratio - targetRatio) > aspectTolerance) continue
                if (size.width > maxVideoSize.width || size.height > maxVideoSize.height) continue
                if (optimalSize == null || size.width > optimalSize.width) {
                    optimalSize = size
                }
            }
            if (optimalSize == null) {
                // can't find match for aspect ratio, so find closest one
                if (MyDebug.LOG) Log.d(TAG, "no picture size matches the aspect ratio")
                optimalSize = getClosestSize(sizes, targetRatio, maxVideoSize)
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "chose optimalSize: " + optimalSize!!.width + " x " + optimalSize.height)
                Log.d(
                    TAG,
                    "optimalSize ratio: " + (optimalSize.width.toDouble() / optimalSize.height)
                )
            }
            return optimalSize
        }

        fun matchPreviewFpsToVideo(fpsRanges: List<IntArray>, videoFrameRate: Int): IntArray {
            if (MyDebug.LOG) Log.d(TAG, "matchPreviewFpsToVideo()")
            var selectedMinFps = -1
            var selectedMaxFps = -1
            var selectedDiff = -1
            for (fpsRange in fpsRanges) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "    supported fps range: " + fpsRange[0] + " to " + fpsRange[1])
                }
                val minFps = fpsRange[0]
                val maxFps = fpsRange[1]
                if (videoFrameRate in minFps..maxFps) {
                    val diff = maxFps - minFps
                    if (selectedDiff == -1 || diff < selectedDiff) {
                        selectedMinFps = minFps
                        selectedMaxFps = maxFps
                        selectedDiff = diff
                    }
                }
            }
            if (selectedMinFps != -1) {
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "    chosen fps range: $selectedMinFps to $selectedMaxFps"
                    )
                }
            } else {
                selectedDiff = -1
                var selectedDist = -1
                for (fpsRange in fpsRanges) {
                    val minFps = fpsRange[0]
                    val maxFps = fpsRange[1]
                    val diff = maxFps - minFps
                    val dist = if (maxFps < videoFrameRate) videoFrameRate - maxFps
                    else minFps - videoFrameRate
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "    supported fps range: $minFps to $maxFps has dist $dist and diff $diff"
                        )
                    }
                    if (selectedDist == -1 || dist < selectedDist || (dist == selectedDist && diff < selectedDiff)) {
                        selectedMinFps = minFps
                        selectedMaxFps = maxFps
                        selectedDist = dist
                        selectedDiff = diff
                    }
                }
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "    can't find match for fps range, so choose closest: $selectedMinFps to $selectedMaxFps"
                )
            }
            return intArrayOf(selectedMinFps, selectedMaxFps)
        }

        fun chooseBestPreviewFps(fpsRanges: List<IntArray>): IntArray {
            if (MyDebug.LOG) Log.d(TAG, "chooseBestPreviewFps()")

            // find value with lowest min that has max >= 30; if more than one of these, pick the one with highest max
            var selectedMinFps = -1
            var selectedMaxFps = -1
            for (fpsRange in fpsRanges) {
                if (MyDebug.LOG) {
                    Log.d(TAG, "    supported fps range: " + fpsRange[0] + " to " + fpsRange[1])
                }
                val minFps = fpsRange[0]
                val maxFps = fpsRange[1]
                if (maxFps >= 30000) {
                    if (selectedMinFps == -1 || minFps < selectedMinFps) {
                        selectedMinFps = minFps
                        selectedMaxFps = maxFps
                    } else if (minFps == selectedMinFps && maxFps > selectedMaxFps) {
                        selectedMinFps = minFps
                        selectedMaxFps = maxFps
                    }
                }
            }

            if (selectedMinFps != -1) {
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "    chosen fps range: $selectedMinFps to $selectedMaxFps"
                    )
                }
            } else {
                // just pick the widest range; if more than one, pick the one with highest max
                var selectedDiff = -1
                for (fpsRange in fpsRanges) {
                    val minFps = fpsRange[0]
                    val maxFps = fpsRange[1]
                    val diff = maxFps - minFps
                    if (selectedDiff == -1 || diff > selectedDiff) {
                        selectedMinFps = minFps
                        selectedMaxFps = maxFps
                        selectedDiff = diff
                    } else if (diff == selectedDiff && maxFps > selectedMaxFps) {
                        selectedMinFps = minFps
                        selectedMaxFps = maxFps
                        selectedDiff = diff
                    }
                }
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "    can't find fps range 30fps or better, so picked widest range: $selectedMinFps to $selectedMaxFps"
                )
            }
            return intArrayOf(selectedMinFps, selectedMaxFps)
        }

        /** Whether the flash mode is supported in video mode.
         */
        fun isFlashSupportedForVideo(flashMode: String?): Boolean {
            return flashMode != null && (flashMode == "flash_off" || flashMode == "flash_torch" || flashMode == "flash_frontscreen_torch")
        }

        const val PRESHOT_INTERVAL_MS: Int = 100 // interval in ms between preshot frames
    }
}
