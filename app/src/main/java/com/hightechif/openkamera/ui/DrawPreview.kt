/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import android.util.Pair
import android.view.Surface
import android.view.View
import android.widget.RelativeLayout
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withSave
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.MainActivity.SystemOrientation
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.preview.analysis.HistogramType
import com.hightechif.openkamera.MyApplicationInterface.Alignment
import com.hightechif.openkamera.MyApplicationInterface.PhotoMode
import com.hightechif.openkamera.MyApplicationInterface.Shadow
import com.hightechif.openkamera.R
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.ApplicationInterface
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.sensors.GyroSensor
import com.hightechif.openkamera.sensors.LocationSupplier
import com.hightechif.openkamera.utils.MyDebug
import com.hightechif.openkamera.utils.PostProcessing
import java.io.IOException
import java.io.InputStream
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class DrawPreview(mainActivity: MainActivity, applicationInterface: MyApplicationInterface) {
    private val mainActivity: MainActivity
    private val applicationInterface: MyApplicationInterface

    // In some cases when reopening the camera or pausing preview, we apply a dimming effect (only
    // supported when using Camera2 API, since we need to know when frames have been received).
    internal enum class DimPreview {
        DIM_PREVIEW_OFF,  // don't dim the preview
        DIM_PREVIEW_ON,  // do dim the preview
        DIM_PREVIEW_UNTIL // dim the preview until the cameraController is non-null and has received frames, then switch to DIM_PREVIEW_OFF
    }

    private var dimPreview = DimPreview.DIM_PREVIEW_OFF

    private var coverPreview = false // whether to cover the preview for Camera2 API

    // if != -1, the time when the camera became inactive
    private var cameraInactiveTimeMs: Long = -1

    // store to avoid calling PreferenceManager.getDefaultSharedPreferences() repeatedly
    private val sharedPreferences: SharedPreferences

    // cached preferences (need to call updateSettings() to refresh):
    private var hasSettings = false
    private lateinit var photoMode: PhotoMode
    private var showTimePref = false
    private var showCameraIdPref = false
    private var showFreeMemoryPref = false
    private var showIsoPref = false
    private var showVideoMaxAmpPref = false
    private var showZoomPref = false
    private var showBatteryPref = false
    private var showAnglePref = false
    private var angleHighlightColorPref = 0
    private var showGeoDirectionPref = false
    private var takePhotoBorderPref = false
    private var previewSizeWysiwygPref = false
    private var storeLocationPref = false
    private var showAngleLinePref = false
    private var showPitchLinesPref = false
    private var showGeoDirectionLinesPref = false
    private var immersiveModeEverythingPref = false

    // for testing:
    private var storedHasStampPref: Boolean = false
    private var isRawPref = false // whether in RAW+JPEG or RAW only mode
    private var isRawOnlyPref = false // whether in RAW only mode
    private var isFaceDetectionPref = false
    private var isAudioEnabledPref = false
    private var isHighSpeed = false
    private var captureRateFactor = 0f
    private var storedAutoStabilisePref: Boolean = false
    private var preferenceGridPref: String? = null
    private var ghostImagePref: String? = null
    private var ghostSelectedImagePref = ""
    private var ghostSelectedImageBitmap: Bitmap? = null
    private var ghostImageAlpha = 0
    private var wantHistogram = false
    private lateinit var histogramType: HistogramType
    private var wantZebraStripes = false
    private var zebraStripesThreshold = 0
    private var zebraStripesColorForeground = 0
    private var zebraStripesColorBackground = 0
    private var wantFocusPeaking = false
    private var focusPeakingColorPref = 0
    private var wantPreShots = false

    // avoid doing things that allocate memory every frame!
    private val p = Paint()
    private val drawRect = RectF()
    private val guiLocation = IntArray(2)
    private val scaleFont: Float // SP scaling
    private val scaleDp: Float // DP scaling
    private val strokeWidth: Float // strokeWidth used for various UI elements
    private var calendar: Calendar? = null
    private var dateFormatTimeInstance: DateFormat? = null
    private val yboundsText: String
    private val tempHistogramChannel = IntArray(256)
    private val locationInfo: LocationSupplier.LocationInfo = LocationSupplier.LocationInfo()
    private val autoStabiliseCrop = IntArray(2)
    private var hasAutoStabiliseCrop = false

    //private final DecimalFormat decimalFormat1dpForce0 = new DecimalFormat("0.0");
    // cached Rects for drawTextWithBackground() calls
    private var textBoundsTime: Rect? = null
    private var textBoundsCameraId: Rect? = null
    private var textBoundsFreeMemory: Rect? = null
    private var textBoundsAngleSingle: Rect? = null
    private var textBoundsAngleDouble: Rect? = null

    private lateinit var angleString: String // cached for UI performance
    private var cachedAngle = 0.0 // the angle that we used for the cached angleString
    private var lastAngleStringTime: Long = 0

    private var freeMemoryGb = -1.0f
    private lateinit var freeMemoryGbString: String
    private var lastFreeMemoryTime: Long = 0
    private var freeMemoryFuture: Future<*>? = null

    // Important to call StorageUtils.freeMemory() on background thread: we've had ANRs reported
    // from StorageUtils.freeMemory()->freeMemorySAF()->ContentResolver.openFileDescriptor(); also
    // pauses can be seen if running on UI thread if there are a large number of files in the save
    // folder.
    private val freeMemoryExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val freeMemoryRunnable: Runnable = object : Runnable {
        val handler: Handler = Handler(Looper.getMainLooper())

        override fun run() {
            if (MyDebug.LOG) Log.d(TAG, "free_memory_runnable: run")
            val freeMb: Long = mainActivity.storageUtils.freeMemory()
            if (freeMb >= 0) {
                val newFreeMemoryGb = freeMb / 1024.0f
                handler.post { onPostExecute(true, newFreeMemoryGb) }
            } else {
                handler.post { onPostExecute(false, -1.0f) }
            }
        }

        /** Runs on UI thread, after background work is complete.
         */
        private fun onPostExecute(hasNewFreeMemory: Boolean, newFreeMemoryGb: Float) {
            if (MyDebug.LOG) Log.d(TAG, "free_memory_runnable: onPostExecute")
            if (freeMemoryFuture != null && freeMemoryFuture!!.isCancelled) {
                if (MyDebug.LOG) Log.d(TAG, "was cancelled")
                freeMemoryFuture = null
                return
            }

            if (MyDebug.LOG) {
                Log.d(
                    TAG,
                    "has_new_free_memory: $hasNewFreeMemory"
                )
                Log.d(TAG, "free_memory_gb: $freeMemoryGb")
                Log.d(
                    TAG,
                    "new_free_memory_gb: $newFreeMemoryGb"
                )
            }
            if (hasNewFreeMemory && abs((newFreeMemoryGb - freeMemoryGb).toDouble()) > 0.001f) {
                freeMemoryGb = newFreeMemoryGb
                freeMemoryGbString =
                    decimalFormat.format(freeMemoryGb.toDouble()) + this@DrawPreview.context.resources.getString(
                        R.string.gb_abbreviation
                    )
            }

            freeMemoryFuture = null
        }
    }

    private var currentTimeString: String? = null
    private var lastCurrentTimeTime: Long = 0

    private lateinit var cameraIdString: String
    private var lastCameraIdTime: Long = 0

    private lateinit var isoExposureString: String
    private var isScanning = false
    private var lastIsoExposureTime: Long = 0

    private var needFlashIndicator = false
    private var lastNeedFlashIndicatorTime: Long = 0

    private val batteryIfilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    private var hasBatteryFrac = false
    private var batteryFrac = 0f
    private var lastBatteryTime: Long = 0

    private var hasVideoMaxAmp = false
    private var videoMaxAmp = 0
    private var lastVideoMaxAmpTime: Long = 0
    private var videoMaxAmpPrev2 = 0
    private var videoMaxAmpPeak = 0

    private var locationBitmap: Bitmap?
    private var locationOffBitmap: Bitmap?
    private var rawJpegBitmap: Bitmap?
    private var rawOnlyBitmap: Bitmap?
    private var autoStabiliseBitmap: Bitmap?
    private var droBitmap: Bitmap?
    private var hdrBitmap: Bitmap?
    private var panoramaBitmap: Bitmap?
    private var expoBitmap: Bitmap?

    //private Bitmap focusBracketBitmap;
    // no longer bother with a focus bracketing icon - hard to come up with a clear icon, and should be obvious from the two on-screen seekbars
    private var burstBitmap: Bitmap?
    private var nrBitmap: Bitmap?
    private var xNightBitmap: Bitmap?
    private var xBokehBitmap: Bitmap?
    private var xBeautyBitmap: Bitmap?
    private var photostampBitmap: Bitmap?
    private var flashBitmap: Bitmap?
    private var faceDetectionBitmap: Bitmap?
    private var audioDisabledBitmap: Bitmap?
    private var highSpeedFpsBitmap: Bitmap?
    private var slowMotionBitmap: Bitmap?
    private var timeLapseBitmap: Bitmap?
    private var rotateLeftBitmap: Bitmap?
    private var rotateRightBitmap: Bitmap?

    private val iconDest = Rect()
    private var needsFlashTime: Long =
        -1 // time when flash symbol comes on (used for fade-in effect)
    private val path = Path()

    private var lastThumbnail: Bitmap? = null // thumbnail of last picture taken

    @Volatile
    private var thumbnailAnim =
        false // whether we are displaying the thumbnail animation; must be volatile for test project reading the state
    private var thumbnailAnimStartMs: Long = -1 // time that the thumbnail animation started

    @JvmField
    @Volatile
    var testThumbnailAnimCount: Int = 0
    private val thumbnailAnimSrcRect = RectF()
    private val thumbnailAnimDstRect = RectF()
    private val thumbnailAnimMatrix = Matrix()
    private var lastThumbnailIsVideo = false // whether thumbnail is for video

    private var showLastImage = false // whether to show the last image as part of "pause preview"
    private val lastImageSrcRect = RectF()
    private val lastImageDstRect = RectF()
    private val lastImageMatrix = Matrix()
    private var allowGhostLastImage = false // whether to allow ghosting the last image

    private var aeStartedScanningMs: Long = -1 // time when ae started scanning

    private var takingPicture =
        false // true iff camera is in process of capturing a picture (including any necessary prior steps such as autofocus, flash/precapture)
    private var captureStarted = false // true iff the camera is capturing
    private var frontScreenFlash =
        false // true iff the front screen display should maximize to simulate flash
    private var imageQueueFull =
        false // whether we can no longer take new photos due to image queue being full (or rather, would become full if a new photo taken)

    private var continuousFocusMoving = false
    private var continuousFocusMovingMs: Long = 0

    private var enableGyroTargetSpot = false
    private val gyroDirections: MutableList<FloatArray> = ArrayList()
    private val transformedGyroDirection = FloatArray(3)
    private val gyroDirectionUp = FloatArray(3)
    private val transformedGyroDirectionUp = FloatArray(3)

    // call updateCachedViewAngles() before reading these values
    private var viewAngleXPreview = 0f
    private var viewAngleYPreview = 0f
    private var lastViewAnglesTime: Long = 0

    private var takePhotoTop =
        0 // coordinate (in canvas x coordinates, or y coords if systemOrientationPortrait==true) of top of the take photo icon
    private var lastTakePhotoTopTime: Long = 0

    private var topIconShift =
        0 // shift that may be needed for on-screen text to avoid clashing with icons (when arranged "along top")
    private var lastTopIconShiftTime: Long = 0

    private var focusSeekbarsMarginLeft =
        -1 // margin left that's been set for the focus seekbars

    private var lastUpdateFocusSeekbarAutoTime: Long = 0

    // OSD extra lines
    private lateinit var varOSDLine1: String
    private lateinit var varOSDLine2: String

    init {
        if (MyDebug.LOG) Log.d(TAG, "DrawPreview")
        this.mainActivity = mainActivity
        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        this.applicationInterface = applicationInterface

        // n.b., don't call updateSettings() here, as it may rely on things that aren't yet initialize (e.g., the preview)
        // see testHDRRestart
        p.isAntiAlias = true
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.strokeCap = Paint.Cap.ROUND
        scaleDp = context.resources.displayMetrics.density
        scaleFont = context.resources.displayMetrics.scaledDensity
        this.strokeWidth = (1.0f * scaleDp + 0.5f) // convert dps to pixels
        p.strokeWidth = strokeWidth

        locationBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_gps_fixed_white_48dp)
        locationOffBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_gps_off_white_48dp)
        rawJpegBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.raw_icon)
        rawOnlyBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.raw_only_icon)
        autoStabiliseBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.auto_stabilise_icon)
        droBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.dro_icon)
        hdrBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_hdr_on_white_48dp)
        panoramaBitmap = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.baseline_panorama_horizontal_white_48
        )
        expoBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.expo_icon)
        //focusBracketBitmap = BitmapFactory.decodeResource(getContext().getResources(), R.drawable.focus_bracket_icon);
        burstBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_burst_mode_white_48dp)
        nrBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.nr_icon)
        xNightBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.baseline_bedtime_white_48)
        xBokehBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.baseline_portrait_white_48)
        xBeautyBitmap = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.baseline_face_retouching_natural_white_48
        )
        photostampBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_text_format_white_48dp)
        flashBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.flash_on)
        faceDetectionBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_face_white_48dp)
        audioDisabledBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_mic_off_white_48dp)
        highSpeedFpsBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_fast_forward_white_48dp)
        slowMotionBitmap = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.ic_slow_motion_video_white_48dp
        )
        timeLapseBitmap =
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_timelapse_white_48dp)
        rotateLeftBitmap = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.baseline_rotate_left_white_48
        )
        rotateRightBitmap = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.baseline_rotate_right_white_48
        )

        yboundsText =
            context.resources.getString(R.string.zoom) + context.resources.getString(R.string.angle) + context.resources.getString(
                R.string.direction
            )
    }

    fun onDestroy() {
        if (MyDebug.LOG) Log.d(TAG, "on_destroy")
        if (freeMemoryFuture != null) {
            if (MyDebug.LOG) Log.d(TAG, "cancel free_memory_future")
            freeMemoryFuture!!.cancel(true)
        }
        // clean up just in case
        if (locationBitmap != null) {
            locationBitmap!!.recycle()
            locationBitmap = null
        }
        if (locationOffBitmap != null) {
            locationOffBitmap!!.recycle()
            locationOffBitmap = null
        }
        if (rawJpegBitmap != null) {
            rawJpegBitmap!!.recycle()
            rawJpegBitmap = null
        }
        if (rawOnlyBitmap != null) {
            rawOnlyBitmap!!.recycle()
            rawOnlyBitmap = null
        }
        if (autoStabiliseBitmap != null) {
            autoStabiliseBitmap!!.recycle()
            autoStabiliseBitmap = null
        }
        if (droBitmap != null) {
            droBitmap!!.recycle()
            droBitmap = null
        }
        if (hdrBitmap != null) {
            hdrBitmap!!.recycle()
            hdrBitmap = null
        }
        if (panoramaBitmap != null) {
            panoramaBitmap!!.recycle()
            panoramaBitmap = null
        }
        if (expoBitmap != null) {
            expoBitmap!!.recycle()
            expoBitmap = null
        }
        /*if( focusBracketBitmap != null ) {
            focus_bracket_bitmap.recycle();
            focusBracketBitmap = null;
        }*/
        if (burstBitmap != null) {
            burstBitmap!!.recycle()
            burstBitmap = null
        }
        if (nrBitmap != null) {
            nrBitmap!!.recycle()
            nrBitmap = null
        }
        if (xNightBitmap != null) {
            xNightBitmap!!.recycle()
            xNightBitmap = null
        }
        if (xBokehBitmap != null) {
            xBokehBitmap!!.recycle()
            xBokehBitmap = null
        }
        if (xBeautyBitmap != null) {
            xBeautyBitmap!!.recycle()
            xBeautyBitmap = null
        }
        if (photostampBitmap != null) {
            photostampBitmap!!.recycle()
            photostampBitmap = null
        }
        if (flashBitmap != null) {
            flashBitmap!!.recycle()
            flashBitmap = null
        }
        if (faceDetectionBitmap != null) {
            faceDetectionBitmap!!.recycle()
            faceDetectionBitmap = null
        }
        if (audioDisabledBitmap != null) {
            audioDisabledBitmap!!.recycle()
            audioDisabledBitmap = null
        }
        if (highSpeedFpsBitmap != null) {
            highSpeedFpsBitmap!!.recycle()
            highSpeedFpsBitmap = null
        }
        if (slowMotionBitmap != null) {
            slowMotionBitmap!!.recycle()
            slowMotionBitmap = null
        }
        if (timeLapseBitmap != null) {
            timeLapseBitmap!!.recycle()
            timeLapseBitmap = null
        }
        if (rotateLeftBitmap != null) {
            rotateLeftBitmap!!.recycle()
            rotateLeftBitmap = null
        }
        if (rotateRightBitmap != null) {
            rotateRightBitmap!!.recycle()
            rotateRightBitmap = null
        }

        if (ghostSelectedImageBitmap != null) {
            ghostSelectedImageBitmap!!.recycle()
            ghostSelectedImageBitmap = null
        }
        ghostSelectedImagePref = ""
    }

    private val context: Context
        get() = mainActivity

    /** Computes the x coordinate on screen of left side of the view, equivalent to
     * view.getLocationOnScreen(), but we undo the effect of the view's rotation.
     * This is because getLocationOnScreen() will return the coordinates of the view's top-left
     * *after* applying the rotation, when we want the top left of the icon as shown on screen.
     * This should not be called every frame but instead should be cached, due to cost of calling
     * view.getLocationOnScreen().
     * Update: For supporting landscape and portrait (if MainActivity.lockToLandscape==false),
     * instead this returns the top side if in portrait. Note though we still need to take rotation
     * into account, as we still apply rotation to the icons when changing orienations (e.g., this
     * is needed when rotating from reverse landscape to portrait, for on-screen text like level
     * angle to be offset correctly above the shutter button (see takePhotoTop) when the preview
     * has a wide aspect ratio.
     */
    private fun getViewOnScreenX(view: View): Int {
        view.getLocationOnScreen(guiLocation)

        val systemOrientation: SystemOrientation = mainActivity.systemOrientation
        val systemOrientationPortrait =
            systemOrientation === SystemOrientation.PORTRAIT
        var xpos = guiLocation[if (systemOrientationPortrait) 1 else 0]
        var rotation = view.rotation.roundToInt()
        // rotation can be outside [0, 359] if the user repeatedly rotates in same direction!
        rotation =
            (rotation % 360 + 360) % 360 // version of (rotation % 360) that work if rotation is -ve
        /*if( MyDebug.LOG )
            Log.d(TAG, "    mod rotation: " + rotation);*/
        // undo annoying behavior that getLocationOnScreen takes the rotation into account
        if (systemOrientationPortrait) {
            if (rotation == 180 || rotation == 270) {
                xpos -= view.height
            }
        } else {
            if (rotation == 90 || rotation == 180) {
                xpos -= view.width
            }
        }
        return xpos
    }

    /** Sets a current thumbnail for a photo or video just taken. Used for thumbnail animation,
     * and when ghosting the last image.
     */
    fun updateThumbnail(thumbnail: Bitmap?, isVideo: Boolean, wantThumbnailAnimation: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "updateThumbnail")
        if (wantThumbnailAnimation && applicationInterface.thumbnailAnimationPref) {
            if (MyDebug.LOG) Log.d(TAG, "thumbnail_anim started")
            thumbnailAnim = true
            thumbnailAnimStartMs = System.currentTimeMillis()
            testThumbnailAnimCount++
            if (MyDebug.LOG) Log.d(
                TAG,
                "test_thumbnail_anim_count is now: $testThumbnailAnimCount"
            )
        }
        val oldThumbnail = this.lastThumbnail
        this.lastThumbnail = thumbnail
        this.lastThumbnailIsVideo = isVideo
        this.allowGhostLastImage = true
        oldThumbnail?.recycle()
    }

    fun hasThumbnailAnimation(): Boolean {
        return this.thumbnailAnim
    }

    /** Displays the thumbnail as a fullscreen image (used for pause preview option).
     */
    fun showLastImage() {
        if (MyDebug.LOG) Log.d(TAG, "showLastImage")
        this.showLastImage = true
    }

    fun clearLastImage() {
        if (MyDebug.LOG) Log.d(TAG, "clearLastImage")
        this.showLastImage = false
    }

    fun allowGhostImage() {
        if (MyDebug.LOG) Log.d(TAG, "allowGhostImage")
        if (lastThumbnail != null) this.allowGhostLastImage = true
    }

    fun clearGhostImage() {
        if (MyDebug.LOG) Log.d(TAG, "clearGhostImage")
        this.allowGhostLastImage = false
    }

    fun cameraInOperation(inOperation: Boolean) {
        if (inOperation && !mainActivity.preview.isVideo) {
            takingPicture = true
        } else {
            takingPicture = false
            frontScreenFlash = false
            captureStarted = false
        }
    }

    fun setImageQueueFull(imageQueueFull: Boolean) {
        this.imageQueueFull = imageQueueFull
    }

    fun turnFrontScreenFlashOn() {
        if (MyDebug.LOG) Log.d(TAG, "turnFrontScreenFlashOn")
        frontScreenFlash = true
    }

    fun onCaptureStarted() {
        if (MyDebug.LOG) Log.d(TAG, "onCaptureStarted")
        captureStarted = true
    }

    fun onContinuousFocusMove(start: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onContinuousFocusMove: $start"
        )
        if (start) {
            if (!continuousFocusMoving) { // don't restart the animation if already in motion
                continuousFocusMoving = true
                continuousFocusMovingMs = System.currentTimeMillis()
            }
        }
        // if we receive start==false, we don't stop the animation - let it continue
    }

    fun clearContinuousFocusMove() {
        if (MyDebug.LOG) Log.d(TAG, "clearContinuousFocusMove")
        if (continuousFocusMoving) {
            continuousFocusMoving = false
            continuousFocusMovingMs = 0
        }
    }

    fun setGyroDirectionMarker(x: Float, y: Float, z: Float) {
        enableGyroTargetSpot = true
        gyroDirections.clear()
        addGyroDirectionMarker(x, y, z)
        gyroDirectionUp[0] = 0f
        gyroDirectionUp[1] = 1f
        gyroDirectionUp[2] = 0f
    }

    fun addGyroDirectionMarker(x: Float, y: Float, z: Float) {
        val vector = floatArrayOf(x, y, z)
        gyroDirections.add(vector)
    }

    fun clearGyroDirectionMarker() {
        enableGyroTargetSpot = false
    }

    /** For performance reasons, some of the SharedPreferences settings are cached. This method
     * should be used when the settings may have changed.
     */
    fun updateSettings() {
        if (MyDebug.LOG) Log.d(TAG, "updateSettings")

        photoMode = applicationInterface.photoMode
        if (MyDebug.LOG) Log.d(TAG, "photoMode: $photoMode")

        val settingsRepo = applicationInterface.settingsRepository
        showTimePref =
            settingsRepo?.getBooleanPreference(PreferenceKeys.SHOW_TIME_PREFERENCE_KEY, true)
                ?: sharedPreferences.getBoolean(PreferenceKeys.SHOW_TIME_PREFERENCE_KEY, true)
        // reset in case user changes the preference:
        dateFormatTimeInstance = DateFormat.getTimeInstance()
        currentTimeString = null
        lastCurrentTimeTime = 0
        textBoundsTime = null

        showCameraIdPref = mainActivity.isMultiCam && (settingsRepo?.getBooleanPreference(
            PreferenceKeys.SHOW_CAMERA_ID_PREFERENCE_KEY,
            true
        ) ?: sharedPreferences.getBoolean(
            PreferenceKeys.SHOW_CAMERA_ID_PREFERENCE_KEY,
            true
        ))
        //showCameraIdPref = true; // test
        showFreeMemoryPref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.SHOW_FREE_MEMORY_PREFERENCE_KEY,
            true
        ) ?: sharedPreferences.getBoolean(PreferenceKeys.SHOW_FREE_MEMORY_PREFERENCE_KEY, true)
        showIsoPref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.SHOW_ISO_PREFERENCE_KEY,
            true
        ) ?: sharedPreferences.getBoolean(PreferenceKeys.SHOW_ISO_PREFERENCE_KEY, true)
        showVideoMaxAmpPref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.SHOW_VIDEO_MAX_AMP_PREFERENCE_KEY,
            false
        ) ?: sharedPreferences.getBoolean(PreferenceKeys.SHOW_VIDEO_MAX_AMP_PREFERENCE_KEY, false)
        showZoomPref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.SHOW_ZOOM_PREFERENCE_KEY,
            true
        ) ?: sharedPreferences.getBoolean(PreferenceKeys.SHOW_ZOOM_PREFERENCE_KEY, true)
        showBatteryPref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.SHOW_BATTERY_PREFERENCE_KEY,
            true
        ) ?: sharedPreferences.getBoolean(PreferenceKeys.SHOW_BATTERY_PREFERENCE_KEY, true)

        showAnglePref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.SHOW_ANGLE_PREFERENCE_KEY,
            false
        ) ?: sharedPreferences.getBoolean(PreferenceKeys.SHOW_ANGLE_PREFERENCE_KEY, false)
        val angleHighlightColor = settingsRepo?.getStringPreference(
            PreferenceKeys.SHOW_ANGLE_HIGHLIGHT_COLOR_PREFERENCE_KEY,
            "#14e715"
        ) ?: sharedPreferences.getString(
            PreferenceKeys.SHOW_ANGLE_HIGHLIGHT_COLOR_PREFERENCE_KEY,
            "#14e715"
        )!!
        angleHighlightColorPref = angleHighlightColor.toColorInt()
        showGeoDirectionPref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.SHOW_GEO_DIRECTION_PREFERENCE_KEY,
            false
        ) ?: sharedPreferences.getBoolean(PreferenceKeys.SHOW_GEO_DIRECTION_PREFERENCE_KEY, false)

        takePhotoBorderPref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.TAKE_PHOTO_BORDER_PREFERENCE_KEY,
            true
        ) ?: sharedPreferences.getBoolean(PreferenceKeys.TAKE_PHOTO_BORDER_PREFERENCE_KEY, true)
        val previewSizePrefVal = settingsRepo?.getStringPreference(
            PreferenceKeys.PREVIEW_SIZE_PREFERENCE_KEY,
            "preference_preview_size_wysiwyg"
        ) ?: sharedPreferences.getString(
            PreferenceKeys.PREVIEW_SIZE_PREFERENCE_KEY,
            "preference_preview_size_wysiwyg"
        )
        previewSizeWysiwygPref = previewSizePrefVal == "preference_preview_size_wysiwyg"
        storeLocationPref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.LOCATION_PREFERENCE_KEY,
            false
        ) ?: sharedPreferences.getBoolean(PreferenceKeys.LOCATION_PREFERENCE_KEY, false)

        showAngleLinePref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.SHOW_ANGLE_LINE_PREFERENCE_KEY,
            false
        ) ?: sharedPreferences.getBoolean(PreferenceKeys.SHOW_ANGLE_LINE_PREFERENCE_KEY, false)
        showPitchLinesPref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.SHOW_PITCH_LINES_PREFERENCE_KEY,
            false
        ) ?: sharedPreferences.getBoolean(PreferenceKeys.SHOW_PITCH_LINES_PREFERENCE_KEY, false)
        showGeoDirectionLinesPref = settingsRepo?.getBooleanPreference(
            PreferenceKeys.SHOW_GEO_DIRECTION_LINES_PREFERENCE_KEY,
            false
        ) ?: sharedPreferences.getBoolean(
            PreferenceKeys.SHOW_GEO_DIRECTION_LINES_PREFERENCE_KEY,
            false
        )

        val immersiveMode = settingsRepo?.getStringPreference(
            PreferenceKeys.IMMERSIVE_MODE_PREFERENCE_KEY,
            "immersive_mode_off"
        ) ?: sharedPreferences.getString(
            PreferenceKeys.IMMERSIVE_MODE_PREFERENCE_KEY,
            "immersive_mode_off"
        )!!
        immersiveModeEverythingPref = immersiveMode == "immersive_mode_everything"

        storedHasStampPref = applicationInterface.stampPref == "preference_stamp_yes"
        isRawPref =
            applicationInterface.getRawPref() !== ApplicationInterface.RawPref.RAWPREF_JPEG_ONLY
        isRawOnlyPref = applicationInterface.isRawOnly
        isFaceDetectionPref = applicationInterface.getFaceDetectionPref()
        isAudioEnabledPref = applicationInterface.getRecordAudioPref()

        isHighSpeed = applicationInterface.fpsIsHighSpeed()
        captureRateFactor = applicationInterface.getVideoCaptureRateFactor()

        storedAutoStabilisePref = applicationInterface.autoStabilisePref

        preferenceGridPref = sharedPreferences.getString(
            PreferenceKeys.SHOW_GRID_PREFERENCE_KEY,
            "preference_grid_none"
        )

        ghostImagePref = sharedPreferences.getString(
            PreferenceKeys.GHOST_IMAGE_PREFERENCE_KEY,
            "preference_ghost_image_off"
        )
        if (ghostImagePref == "preference_ghost_image_selected") {
            val newGhostSelectedImagePref =
                sharedPreferences.getString(
                    PreferenceKeys.GHOST_SELECTED_IMAGE_SAF_PREFERENCE_KEY,
                    ""
                )!!
            if (MyDebug.LOG) Log.d(
                TAG,
                "new_ghost_selected_image_pref: $newGhostSelectedImagePref"
            )

            val keyguardManager =
                mainActivity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val isLocked =
                keyguardManager != null && keyguardManager.inKeyguardRestrictedInputMode()
            if (MyDebug.LOG) Log.d(TAG, "is_locked?: $isLocked")

            if (isLocked) {
                // don't show selected image when device locked, as this could be a security flaw
                if (ghostSelectedImageBitmap != null) {
                    ghostSelectedImageBitmap!!.recycle()
                    ghostSelectedImageBitmap = null
                    ghostSelectedImagePref = "" // so we'll load the bitmap again when unlocked
                }
            } else if (newGhostSelectedImagePref != ghostSelectedImagePref) {
                if (MyDebug.LOG) Log.d(TAG, "ghost_selected_image_pref has changed")
                ghostSelectedImagePref = newGhostSelectedImagePref
                if (ghostSelectedImageBitmap != null) {
                    ghostSelectedImageBitmap!!.recycle()
                    ghostSelectedImageBitmap = null
                }
                val uri = ghostSelectedImagePref.toUri()
                try {
                    ghostSelectedImageBitmap = loadBitmap(uri)
                } catch (e: IOException) {
                    Log.e(
                        TAG,
                        "failed to load ghost_selected_image uri: $uri"
                    )
                    e.printStackTrace()
                    ghostSelectedImageBitmap = null
                    // don't set ghostSelectedImagePref to null, as we don't want to repeatedly try loading the invalid uri
                }
            }
        } else {
            if (ghostSelectedImageBitmap != null) {
                ghostSelectedImageBitmap!!.recycle()
                ghostSelectedImageBitmap = null
            }
            ghostSelectedImagePref = ""
        }
        ghostImageAlpha = applicationInterface.ghostImageAlpha

        val histogramPref =
            sharedPreferences.getString(
                PreferenceKeys.HISTOGRAM_PREFERENCE_KEY,
                "preference_histogram_off"
            )!!
        wantHistogram =
            histogramPref != "preference_histogram_off" && mainActivity.supportsPreviewBitmaps()
        histogramType = HistogramType.HISTOGRAM_TYPE_VALUE
        if (wantHistogram) {
            when (histogramPref) {
                "preference_histogram_rgb" -> histogramType =
                    HistogramType.HISTOGRAM_TYPE_RGB

                "preference_histogram_luminance" -> histogramType =
                    HistogramType.HISTOGRAM_TYPE_LUMINANCE

                "preference_histogram_value" -> histogramType =
                    HistogramType.HISTOGRAM_TYPE_VALUE

                "preference_histogram_intensity" -> histogramType =
                    HistogramType.HISTOGRAM_TYPE_INTENSITY

                "preference_histogram_lightness" -> histogramType =
                    HistogramType.HISTOGRAM_TYPE_LIGHTNESS
            }
        }

        val zebraStripesValue =
            sharedPreferences.getString(PreferenceKeys.ZEBRA_STRIPES_PREFERENCE_KEY, "0")!!
        try {
            zebraStripesThreshold = zebraStripesValue.toInt()
        } catch (e: NumberFormatException) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "failed to parse zebra_stripes_value: $zebraStripesValue"
            )
            e.printStackTrace()
            zebraStripesThreshold = 0
        }
        wantZebraStripes =
            (zebraStripesThreshold != 0) and mainActivity.supportsPreviewBitmaps()

        val zebraStripesColorForegroundValue =
            sharedPreferences.getString(
                PreferenceKeys.ZEBRA_STRIPES_FOREGROUND_COLOR_PREFERENCE_KEY,
                "#ff000000"
            )!!
        zebraStripesColorForeground = zebraStripesColorForegroundValue.toColorInt()
        val zebraStripesColorBackgroundValue =
            sharedPreferences.getString(
                PreferenceKeys.ZEBRA_STRIPES_BACKGROUND_COLOR_PREFERENCE_KEY,
                "#ffffffff"
            )!!
        zebraStripesColorBackground = zebraStripesColorBackgroundValue.toColorInt()

        wantFocusPeaking = applicationInterface.focusPeakingPref
        val focusPeakingColor =
            sharedPreferences.getString(
                PreferenceKeys.FOCUS_PEAKING_COLOR_PREFERENCE_KEY,
                "#ffffff"
            )!!
        focusPeakingColorPref = focusPeakingColor.toColorInt()

        wantPreShots = applicationInterface.getPreShotsPref(photoMode)

        lastCameraIdTime = 0 // in case camera id changed
        lastViewAnglesTime = 0 // force view angles to be recomputed
        lastTakePhotoTopTime = 0 // force takePhotoTop to be recomputed
        lastTopIconShiftTime = 0 // for topIconShift to be recomputed

        focusSeekbarsMarginLeft =
            -1 // needed as the focus seekbars can only be updated when visible

        hasSettings = true
    }

    /** Indicates that navigation gaps have changed, as a hint to avoid cached data.
     */
    fun onNavigationGapChanged() {
        // needed for OnePlus Pad when rotating, to avoid delay in updating lastTakePhotoTopTime (affects placement of on-screen text e.g. zoom)
        this.lastTakePhotoTopTime = 0
    }

    private fun updateCachedViewAngles(timeMs: Long) {
        if (lastViewAnglesTime == 0L || timeMs > lastViewAnglesTime + 10000) {
            if (MyDebug.LOG) Log.d(TAG, "update cached view angles")
            // don't call this too often, for UI performance
            // note that updateSettings will force the time to reset anyway, but we check every so often
            // again just in case...
            val preview: Preview = mainActivity.preview
            viewAngleXPreview = preview.getViewAngleX(true)
            viewAngleYPreview = preview.getViewAngleY(true)
            lastViewAnglesTime = timeMs
        }
    }

    /** Loads the bitmap from the uri.
     * The image will be downscaled if required to be comparable to the preview width.
     */
    @Throws(IOException::class)
    private fun loadBitmap(uri: Uri): Bitmap {
        if (MyDebug.LOG) Log.d(TAG, "loadBitmap: $uri")
        var bitmap: Bitmap?
        try {
            //bitmap = MediaStore.Images.Media.getBitmap(main_activity.getContentResolver(), uri);

            var sampleSize = 1
            run {
                // attempt to compute appropriate scaling
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                val input: InputStream? = mainActivity.contentResolver.openInputStream(uri)
                BitmapFactory.decodeStream(input, null, bounds)
                input?.close()
                if (bounds.outWidth != -1 && bounds.outHeight != -1) {
                    // compute appropriate scaling
                    val imageSize =
                        max(bounds.outWidth.toDouble(), bounds.outHeight.toDouble()).toInt()

                    val point = Point()
                    applicationInterface.getDisplaySize(point, true)
                    val displaySize = max(point.x.toDouble(), point.y.toDouble()).toInt()

                    val ratio = ceil(imageSize.toDouble() / displaySize).toInt()
                    sampleSize = Integer.highestOneBit(ratio)
                    if (MyDebug.LOG) {
                        Log.d(TAG, "display_size: $displaySize")
                        Log.d(TAG, "image_size: $imageSize")
                        Log.d(TAG, "ratio: $ratio")
                        Log.d(TAG, "sample_size: $sampleSize")
                    }
                } else {
                    if (MyDebug.LOG) Log.e(TAG, "failed to obtain width/height of bitmap")
                }
            }

            val options = BitmapFactory.Options()
            options.inMutable = false
            options.inSampleSize = sampleSize
            val input: InputStream? = mainActivity.contentResolver.openInputStream(uri)
            bitmap = BitmapFactory.decodeStream(input, null, options)
            input?.close()
            if (MyDebug.LOG && bitmap != null) {
                Log.d(TAG, "bitmap width: " + bitmap.width)
                Log.d(TAG, "bitmap height: " + bitmap.height)
            }
        } catch (e: Exception) {
            // Although Media.getBitmap() is documented as only throwing FileNotFoundException, IOException
            // (with the former being a subset of IOException anyway), I've had SecurityException from
            // Google Play - best to catch everything just in case.
            Log.e(TAG, "MediaStore.Images.Media.getBitmap exception")
            e.printStackTrace()
            throw IOException()
        }
        if (bitmap == null) {
            // just in case!
            Log.e(TAG, "MediaStore.Images.Media.getBitmap returned null")
            throw IOException()
        }

        // now need to take exif orientation into account, as some devices or camera apps store the orientation in the exif tag,
        // which getBitmap() doesn't account for
        bitmap = mainActivity.rotateForExif(bitmap, uri)

        return bitmap
    }

    private fun getTimeStringFromSeconds(time: Long): String {
        var time = time
        val secs = (time % 60).toInt()
        time /= 60
        val mins = (time % 60).toInt()
        time /= 60
        val hours = time
        return "$hours:" + String.format(
            Locale.getDefault(),
            "%02d",
            mins
        ) + ":" + String.format(
            Locale.getDefault(), "%02d", secs
        )
    }

    private fun drawGrids(canvas: Canvas) {
        val preview: Preview = mainActivity.preview
        val cameraController: CameraController = preview.cameraController ?: return
        val gridKey = try {
            mainActivity.cameraViewModel.uiState.value.gridType.key
        } catch (_: Exception) {
            preferenceGridPref
        }
        if (gridKey == "preference_grid_none") {
            return
        }
        if (preview.isPreviewPaused) {
            return
        }

        var canvasRotated = false
        var w2 = canvas.width
        var h2 = canvas.height
        if (hasAutoStabiliseCrop) {
            // rotate the grid
            w2 = autoStabiliseCrop[0]
            h2 = autoStabiliseCrop[1]
            var levelAngle = preview.origLevelAngle
            val rotation = mainActivity.getDisplayRotation(false)
            when (rotation) {
                Surface.ROTATION_90 -> {
                    levelAngle += 90.0
                    w2 = autoStabiliseCrop[1]
                    h2 = autoStabiliseCrop[0]
                }

                Surface.ROTATION_270 -> {
                    levelAngle -= 90.0
                    w2 = autoStabiliseCrop[1]
                    h2 = autoStabiliseCrop[0]
                }

                Surface.ROTATION_180 -> {
                    levelAngle += 180.0
                }

                Surface.ROTATION_0 -> {}
            }
            canvas.save()
            canvas.rotate(-levelAngle.toFloat(), canvas.width / 2.0f, canvas.height / 2.0f)
            canvas.translate((canvas.width - w2) / 2.0f, (canvas.height - h2) / 2.0f)
            canvasRotated = true
        }

        p.strokeWidth = strokeWidth

        when (gridKey) {
            "preference_grid_3x3" -> {
                p.color = Color.WHITE
                canvas.drawLine(
                    w2 / 3.0f,
                    0.0f,
                    w2 / 3.0f,
                    h2 - 1.0f,
                    p
                )
                canvas.drawLine(
                    2.0f * w2 / 3.0f,
                    0.0f,
                    2.0f * w2 / 3.0f,
                    h2 - 1.0f,
                    p
                )
                canvas.drawLine(
                    0.0f,
                    h2 / 3.0f,
                    w2 - 1.0f,
                    h2 / 3.0f,
                    p
                )
                canvas.drawLine(
                    0.0f,
                    2.0f * h2 / 3.0f,
                    w2 - 1.0f,
                    2.0f * h2 / 3.0f,
                    p
                )
            }

            "preference_grid_phi_3x3" -> {
                p.color = Color.WHITE
                canvas.drawLine(
                    w2 / 2.618f,
                    0.0f,
                    w2 / 2.618f,
                    h2 - 1.0f,
                    p
                )
                canvas.drawLine(
                    1.618f * w2 / 2.618f,
                    0.0f,
                    1.618f * w2 / 2.618f,
                    h2 - 1.0f,
                    p
                )
                canvas.drawLine(
                    0.0f,
                    h2 / 2.618f,
                    w2 - 1.0f,
                    h2 / 2.618f,
                    p
                )
                canvas.drawLine(
                    0.0f,
                    1.618f * h2 / 2.618f,
                    w2 - 1.0f,
                    1.618f * h2 / 2.618f,
                    p
                )
            }

            "preference_grid_4x2" -> {
                p.color = Color.GRAY
                canvas.drawLine(
                    w2 / 4.0f,
                    0.0f,
                    w2 / 4.0f,
                    h2 - 1.0f,
                    p
                )
                canvas.drawLine(
                    w2 / 2.0f,
                    0.0f,
                    w2 / 2.0f,
                    h2 - 1.0f,
                    p
                )
                canvas.drawLine(
                    3.0f * w2 / 4.0f,
                    0.0f,
                    3.0f * w2 / 4.0f,
                    h2 - 1.0f,
                    p
                )
                canvas.drawLine(
                    0.0f,
                    h2 / 2.0f,
                    w2 - 1.0f,
                    h2 / 2.0f,
                    p
                )
                p.color = Color.WHITE
                val crosshairsRadius = (20 * scaleDp + 0.5f).toInt() // convert dps to pixels

                canvas.drawLine(
                    w2 / 2.0f,
                    h2 / 2.0f - crosshairsRadius,
                    w2 / 2.0f,
                    h2 / 2.0f + crosshairsRadius,
                    p
                )
                canvas.drawLine(
                    w2 / 2.0f - crosshairsRadius,
                    h2 / 2.0f,
                    w2 / 2.0f + crosshairsRadius,
                    h2 / 2.0f,
                    p
                )
            }

            "preference_grid_crosshair" -> {
                p.color = Color.WHITE
                canvas.drawLine(
                    canvas.width / 2.0f,
                    0.0f,
                    canvas.width / 2.0f,
                    canvas.height - 1.0f,
                    p
                )
                canvas.drawLine(
                    0.0f,
                    canvas.height / 2.0f,
                    canvas.width - 1.0f,
                    canvas.height / 2.0f,
                    p
                )
            }

            "preference_grid_golden_spiral_right", "preference_grid_golden_spiral_left", "preference_grid_golden_spiral_upside_down_right", "preference_grid_golden_spiral_upside_down_left" -> {
                canvas.save()
                when (preferenceGridPref) {
                    "preference_grid_golden_spiral_left" -> canvas.scale(
                        -1.0f,
                        1.0f,
                        canvas.width * 0.5f,
                        canvas.height * 0.5f
                    )

                    "preference_grid_golden_spiral_right" -> {}
                    "preference_grid_golden_spiral_upside_down_left" -> canvas.rotate(
                        180.0f,
                        canvas.width * 0.5f,
                        canvas.height * 0.5f
                    )

                    "preference_grid_golden_spiral_upside_down_right" -> canvas.scale(
                        1.0f,
                        -1.0f,
                        canvas.width * 0.5f,
                        canvas.height * 0.5f
                    )
                }
                p.color = Color.WHITE
                p.style = Paint.Style.STROKE
                p.strokeWidth = strokeWidth
                var fibb = 34
                var fibbN = 21
                var left = 0
                var top = 0
                var fullWidth = canvas.width
                var fullHeight = canvas.height
                var width = (fullWidth * (fibbN.toDouble()) / (fibb).toDouble()).toInt()
                var height = fullHeight

                var count = 0
                while (count < 2) {
                    canvas.withSave {
                        drawRect[left.toFloat(), top.toFloat(), (left + width).toFloat()] =
                            (top + height).toFloat()
                        clipRect(drawRect)
                        drawRect(drawRect, p)
                        drawRect[left.toFloat(), top.toFloat(), (left + 2 * width).toFloat()] =
                            (top + 2 * height).toFloat()
                        drawOval(drawRect, p)
                    }

                    var oldFibb = fibb
                    fibb = fibbN
                    fibbN = oldFibb - fibb

                    left += width
                    fullWidth -= width
                    width = fullWidth
                    height = (height * (fibbN.toDouble()) / (fibb).toDouble()).toInt()

                    canvas.withSave {
                        drawRect[left.toFloat(), top.toFloat(), (left + width).toFloat()] =
                            (top + height).toFloat()
                        clipRect(drawRect)
                        drawRect(drawRect, p)
                        drawRect[(left - width).toFloat(), top.toFloat(), (left + width).toFloat()] =
                            (top + 2 * height).toFloat()
                        drawOval(drawRect, p)
                    }

                    oldFibb = fibb
                    fibb = fibbN
                    fibbN = oldFibb - fibb

                    top += height
                    fullHeight -= height
                    height = fullHeight
                    width = (width * (fibbN.toDouble()) / (fibb).toDouble()).toInt()
                    left += fullWidth - width

                    canvas.withSave {
                        drawRect[left.toFloat(), top.toFloat(), (left + width).toFloat()] =
                            (top + height).toFloat()
                        clipRect(drawRect)
                        drawRect(drawRect, p)
                        drawRect[(left - width).toFloat(), (top - height).toFloat(), (left + width).toFloat()] =
                            (top + height).toFloat()
                        drawOval(drawRect, p)
                    }

                    oldFibb = fibb
                    fibb = fibbN
                    fibbN = oldFibb - fibb

                    fullWidth -= width
                    width = fullWidth
                    left -= width
                    height = (height * (fibbN.toDouble()) / (fibb).toDouble()).toInt()
                    top += fullHeight - height

                    canvas.withSave {
                        drawRect[left.toFloat(), top.toFloat(), (left + width).toFloat()] =
                            (top + height).toFloat()
                        clipRect(drawRect)
                        drawRect(drawRect, p)
                        drawRect[left.toFloat(), (top - height).toFloat(), (left + 2 * width).toFloat()] =
                            (top + height).toFloat()
                        drawOval(drawRect, p)
                    }

                    oldFibb = fibb
                    fibb = fibbN
                    fibbN = oldFibb - fibb

                    fullHeight -= height
                    height = fullHeight
                    top -= height
                    width = (width * (fibbN.toDouble()) / (fibb).toDouble()).toInt()
                    count++
                }

                canvas.restore()
                p.style = Paint.Style.FILL // reset
            }

            "preference_grid_golden_triangle_1", "preference_grid_golden_triangle_2" -> {
                p.color = Color.WHITE
                val theta = atan2(canvas.width.toDouble(), canvas.height.toDouble())
                val dist = canvas.height * cos(theta)
                val distX = (dist * sin(theta)).toFloat()
                val distY = (dist * cos(theta)).toFloat()
                if (preferenceGridPref == "preference_grid_golden_triangle_1") {
                    canvas.drawLine(0.0f, canvas.height - 1.0f, canvas.width - 1.0f, 0.0f, p)
                    canvas.drawLine(0.0f, 0.0f, distX, canvas.height - distY, p)
                    canvas.drawLine(
                        canvas.width - 1.0f - distX,
                        distY - 1.0f,
                        canvas.width - 1.0f,
                        canvas.height - 1.0f,
                        p
                    )
                } else {
                    canvas.drawLine(0.0f, 0.0f, canvas.width - 1.0f, canvas.height - 1.0f, p)
                    canvas.drawLine(
                        canvas.width - 1.0f,
                        0.0f,
                        canvas.width - 1.0f - distX,
                        canvas.height - distY,
                        p
                    )
                    canvas.drawLine(distX, distY - 1.0f, 0.0f, canvas.height - 1.0f, p)
                }
            }

            "preference_grid_diagonals" -> {
                p.color = Color.WHITE
                canvas.drawLine(0.0f, 0.0f, canvas.height - 1.0f, canvas.height - 1.0f, p)
                canvas.drawLine(canvas.height - 1.0f, 0.0f, 0.0f, canvas.height - 1.0f, p)
                val diff = canvas.width - canvas.height
                // n.b., diff is -ve in portrait orientation
                canvas.drawLine(
                    diff.toFloat(),
                    0.0f,
                    diff + canvas.height - 1.0f,
                    canvas.height - 1.0f,
                    p
                )
                canvas.drawLine(
                    diff + canvas.height - 1.0f,
                    0.0f,
                    diff.toFloat(),
                    canvas.height - 1.0f,
                    p
                )
            }
        }

        if (canvasRotated) {
            canvas.restore()
        }
    }

    private fun drawCropGuides(canvas: Canvas) {
        val preview: Preview = mainActivity.preview
        val cameraController: CameraController? = preview.cameraController
        if (preview.isVideo || previewSizeWysiwygPref) {
            val preferenceCropGuide =
                sharedPreferences.getString(
                    PreferenceKeys.SHOW_CROP_GUIDE_PREFERENCE_KEY,
                    "crop_guide_none"
                )!!
            if (cameraController != null && preview.targetRatio > 0.0 && (preferenceCropGuide != "crop_guide_none")) {
                var cropRatio = -1.0
                when (preferenceCropGuide) {
                    "crop_guide_1" -> cropRatio = 1.0
                    "crop_guide_1.25" -> cropRatio = 1.25
                    "crop_guide_1.33" -> cropRatio = 1.33333333
                    "crop_guide_1.4" -> cropRatio = 1.4
                    "crop_guide_1.5" -> cropRatio = 1.5
                    "crop_guide_1.78" -> cropRatio = 1.77777778
                    "crop_guide_1.85" -> cropRatio = 1.85
                    "crop_guide_2" -> cropRatio = 2.0
                    "crop_guide_2.33" -> cropRatio = 2.33333333
                    "crop_guide_2.35" -> cropRatio = 2.35006120 // actually 1920:817
                    "crop_guide_2.4" -> cropRatio = 2.4
                }
                if (cropRatio > 0.0) {
                    // we should compare to currentPreviewAspectRatio not getTargetRatio(), as the actual preview
                    // aspect ratio may differ to the requested photo/video resolution's aspect ratio, in which case it's still useful
                    // to display the crop guide
                    var previewAspectRatio: Double = preview.currentPreviewAspectRatio
                    val systemOrientation: SystemOrientation = mainActivity.systemOrientation
                    val systemOrientationPortrait =
                        systemOrientation === SystemOrientation.PORTRAIT
                    if (systemOrientationPortrait) {
                        // crop ratios are always drawn as if in landscape
                        cropRatio = 1.0 / cropRatio
                        previewAspectRatio = 1.0 / previewAspectRatio
                    }
                    if (abs(previewAspectRatio - cropRatio) > 1.0e-5) {
                        /*if( MyDebug.LOG ) {
                            Log.d(TAG, "cropRatio: " + cropRatio);
                            Log.d(TAG, "previewAspectRatio: " + previewAspectRatio);
                            Log.d(TAG, "canvas width: " + canvas.getWidth());
                            Log.d(TAG, "canvas height: " + canvas.getHeight());
                        }*/
                        p.style = Paint.Style.FILL
                        p.color = Color.rgb(0, 0, 0)
                        p.alpha = cropShadingAlphaC
                        var left = 1
                        var top = 1
                        var right = canvas.width - 1
                        var bottom = canvas.height - 1
                        if (cropRatio > previewAspectRatio) {
                            // crop ratio is wider, so we have to crop top/bottom
                            val newHheight = (canvas.width.toDouble()) / (2.0f * cropRatio)
                            top = (canvas.height / 2 - newHheight.toInt())
                            bottom = (canvas.height / 2 + newHheight.toInt())
                            // draw shaded area
                            canvas.drawRect(0f, 0f, canvas.width.toFloat(), top.toFloat(), p)
                            canvas.drawRect(
                                0f,
                                bottom.toFloat(),
                                canvas.width.toFloat(),
                                canvas.height.toFloat(),
                                p
                            )
                        } else {
                            // crop ratio is taller, so we have to crop left/right
                            val newHwidth = ((canvas.height.toDouble()) * cropRatio) / 2.0f
                            left = (canvas.width / 2 - newHwidth.toInt())
                            right = (canvas.width / 2 + newHwidth.toInt())
                            // draw shaded area
                            canvas.drawRect(0f, 0f, left.toFloat(), canvas.height.toFloat(), p)
                            canvas.drawRect(
                                right.toFloat(),
                                0f,
                                canvas.width.toFloat(),
                                canvas.height.toFloat(),
                                p
                            )
                        }
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = strokeWidth
                        p.color = Color.rgb(255, 235, 59) // Yellow 500
                        canvas.drawRect(
                            left.toFloat(),
                            top.toFloat(),
                            right.toFloat(),
                            bottom.toFloat(),
                            p
                        )
                        p.style = Paint.Style.FILL // reset
                        p.alpha = 255 // reset
                    }
                }
            }
        }
    }

    private fun onDrawInfoLines(
        canvas: Canvas,
        topX: Int,
        topY: Int,
        bottomY: Int,
        deviceUiRotation: Int,
        timeMs: Long
    ) {
        val preview: Preview = mainActivity.preview
        val cameraController: CameraController? = preview.cameraController
        val uiRotation: Int = preview.uIRotation

        // set up text etc. for the multiple lines of "info" (time, free mem, etc.)
        p.textSize = 16 * scaleFont + 0.5f // convert dps to pixels
        p.textAlign = Paint.Align.LEFT
        var locationX = topX
        var locationY = topY
        val gapX = (8 * scaleFont + 0.5f).toInt() // convert dps to pixels
        val gapY = (0 * scaleFont + 0.5f).toInt() // convert dps to pixels
        val iconGapY = (2 * scaleDp + 0.5f).toInt() // convert dps to pixels
        if (uiRotation == 90 || uiRotation == 270) {
            // n.b., this is only for when lockToLandscape==true, so we don't look at deviceUiRotation
            val diff = canvas.width - canvas.height
            locationX += diff / 2
            locationY -= diff / 2
        }
        if (deviceUiRotation == 90) {
            locationY = canvas.height - locationY - (20 * scaleFont + 0.5f).toInt()
        }
        var alignRight = false
        if (deviceUiRotation == 180) {
            locationX = canvas.width - locationX
            p.textAlign = Paint.Align.RIGHT
            alignRight = true
        }

        var firstLineHeight = 0
        var firstLineXshift = 0
        if (showTimePref) {
            if (currentTimeString == null || timeMs / 1000 > lastCurrentTimeTime / 1000) {
                // avoid creating a new calendar object every time
                if (calendar == null) calendar = Calendar.getInstance()
                else calendar!!.timeInMillis = timeMs

                currentTimeString = dateFormatTimeInstance!!.format(calendar!!.time)
                //currentTimeString = DateUtils.formatDateTime(getContext(), c.getTimeInMillis(), DateUtils.FORMAT_SHOW_TIME);
                lastCurrentTimeTime = timeMs
            }
            // n.b., DateFormat.getTimeInstance() ignores user preferences such as 12/24 hour or date format, but this is an Android bug.
            // Whilst DateUtils.formatDateTime doesn't have that problem, it doesn't print out seconds! See:
            // http://stackoverflow.com/questions/15981516/simpledateformat-gettimeinstance-ignores-24-hour-format
            // http://daniel-codes.blogspot.co.uk/2013/06/how-to-correctly-format-datetime.html
            // http://code.google.com/p/android/issues/detail?id=42104
            // update: now seems to be fixed
            // also possibly related https://code.google.com/p/android/issues/detail?id=181201
            //int height = applicationInterface.drawTextWithBackground(canvas, p, currentTimeString, Color.WHITE, Color.BLACK, locationX, locationY, MyApplicationInterface.Alignment.ALIGNMENT_TOP);
            if (textBoundsTime == null) {
                if (MyDebug.LOG) Log.d(TAG, "compute text_bounds_time")
                textBoundsTime = Rect()
                // better to not use a fixed string like "00:00:00" as don't want to make assumptions - e.g., in 12-hour format we'll have the appended am/pm to account for!
                val calendar = Calendar.getInstance()
                calendar[100, 0, 1, 10, 59] = 59
                val boundsTimeString = dateFormatTimeInstance!!.format(calendar.time)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "bounds_time_string:$boundsTimeString"
                )
                p.getTextBounds(boundsTimeString, 0, boundsTimeString.length, textBoundsTime)
            }
            firstLineXshift += textBoundsTime!!.width() + gapX
            var height: Int = applicationInterface.drawTextWithBackground(
                canvas,
                p,
                currentTimeString!!,
                Color.WHITE,
                Color.BLACK,
                locationX,
                locationY,
                Alignment.ALIGNMENT_TOP,
                null,
                Shadow.SHADOW_OUTLINE,
                textBoundsTime
            )
            height += gapY
            // don't update locationY yet, as we have time and cameraid shown on the same line
            firstLineHeight = max(firstLineHeight.toDouble(), height.toDouble()).toInt()
        }
        if (showCameraIdPref && cameraController != null) {
            if (!::cameraIdString.isInitialized || timeMs > lastCameraIdTime + 10000) {
                // cache string for performance

                cameraIdString =
                    context.resources.getString(R.string.camera_id) + ":" + preview.cameraId // intentionally don't put a space
                lastCameraIdTime = timeMs
            }
            if (textBoundsCameraId == null) {
                if (MyDebug.LOG) Log.d(TAG, "compute text_bounds_camera_id")
                textBoundsCameraId = Rect()
                p.getTextBounds(
                    cameraIdString,
                    0,
                    cameraIdString.length,
                    textBoundsCameraId
                )
            }
            val xpos =
                if (alignRight) locationX - firstLineXshift else locationX + firstLineXshift
            var height: Int = applicationInterface.drawTextWithBackground(
                canvas,
                p,
                cameraIdString,
                Color.WHITE,
                Color.BLACK,
                xpos,
                locationY,
                Alignment.ALIGNMENT_TOP,
                null,
                Shadow.SHADOW_OUTLINE,
                textBoundsCameraId
            )
            height += gapY
            // don't update locationY yet, as we have time and cameraid shown on the same line
            firstLineHeight = max(firstLineHeight.toDouble(), height.toDouble()).toInt()
        }
        // update locationY for first line (time and camera id)
        if (deviceUiRotation == 90) {
            // upside-down portrait
            locationY -= firstLineHeight
        } else {
            locationY += firstLineHeight
        }

        if (cameraController != null && showFreeMemoryPref) {
            if ((lastFreeMemoryTime == 0L || timeMs > lastFreeMemoryTime + 10000) && freeMemoryFuture == null) {
                // don't call this too often, for UI performance

                freeMemoryFuture = freeMemoryExecutor.submit(freeMemoryRunnable)

                lastFreeMemoryTime =
                    timeMs // always set this, so that in case of free memory not being available, we aren't calling freeMemory() every frame
            }
            if (freeMemoryGb >= 0.0f && ::freeMemoryGbString.isInitialized) {
                //int height = applicationInterface.drawTextWithBackground(canvas, p, freeMemoryGbString, Color.WHITE, Color.BLACK, locationX, locationY, MyApplicationInterface.Alignment.ALIGNMENT_TOP);
                if (textBoundsFreeMemory == null) {
                    if (MyDebug.LOG) Log.d(TAG, "compute text_bounds_free_memory")
                    textBoundsFreeMemory = Rect()
                    p.getTextBounds(
                        freeMemoryGbString,
                        0,
                        freeMemoryGbString.length,
                        textBoundsFreeMemory
                    )
                }
                var height: Int = applicationInterface.drawTextWithBackground(
                    canvas,
                    p,
                    freeMemoryGbString,
                    Color.WHITE,
                    Color.BLACK,
                    locationX,
                    locationY,
                    Alignment.ALIGNMENT_TOP,
                    null,
                    Shadow.SHADOW_OUTLINE,
                    textBoundsFreeMemory
                )
                height += gapY
                if (deviceUiRotation == 90) {
                    locationY -= height
                } else {
                    locationY += height
                }
            }
        }

        // Now draw additional info in the lower left corner if needed
        val yOffset = (27 * scaleFont + 0.5f).toInt()
        p.textSize = 24 * scaleFont + 0.5f // convert dps to pixels
        if (::varOSDLine1.isInitialized && varOSDLine1.isNotEmpty()) {
            applicationInterface.drawTextWithBackground(
                canvas,
                p,
                varOSDLine1,
                Color.WHITE,
                Color.BLACK,
                locationX,
                bottomY - yOffset,
                Alignment.ALIGNMENT_BOTTOM,
                null,
                Shadow.SHADOW_OUTLINE
            )
        }
        if (::varOSDLine2.isInitialized && varOSDLine2.isNotEmpty()) {
            applicationInterface.drawTextWithBackground(
                canvas,
                p,
                varOSDLine2,
                Color.WHITE,
                Color.BLACK,
                locationX,
                bottomY,
                Alignment.ALIGNMENT_BOTTOM,
                null,
                Shadow.SHADOW_OUTLINE
            )
        }
        p.textSize = 16 * scaleFont + 0.5f // Restore text size

        if (cameraController != null && showIsoPref) {
            if (!::isoExposureString.isInitialized || timeMs > lastIsoExposureTime + 500) {
                isoExposureString = ""
                if (cameraController.captureResultHasIso()) {
                    val iso: Int = cameraController.captureResultIso()
                    if (isoExposureString.isNotEmpty()) isoExposureString += " "
                    isoExposureString += preview.getISOString(iso)
                }
                if (cameraController.captureResultHasExposureTime()) {
                    val exposureTime: Long = cameraController.captureResultExposureTime()
                    if (isoExposureString.isNotEmpty()) isoExposureString += " "
                    isoExposureString += preview.getExposureTimeString(exposureTime)
                }
                if (preview.isVideoRecording && cameraController.captureResultHasFrameDuration()) {
                    val frameDuration: Long = cameraController.captureResultFrameDuration()
                    if (isoExposureString.isNotEmpty()) isoExposureString += " "
                    isoExposureString += preview.getFrameDurationString(frameDuration)
                }

                /*if( camera_controller.captureResultHasAperture() ) {
                    float aperture = camera_controller.captureResultAperture();
                    if( iso_exposure_string.length() > 0 )
                        isoExposureString += " F";
                    isoExposureString += decimal_format_1dp_force0.format(aperture);
                }*/
                isScanning = false
                if (cameraController.captureResultIsAEScanning()) {
                    // only show as scanning if in auto ISO mode (problem on Nexus 6 at least that if we're in manual ISO mode, after pausing and
                    // resuming, the camera driver continually reports CONTROL_AE_STATE_SEARCHING)
                    val value = sharedPreferences.getString(
                        PreferenceKeys.ISO_PREFERENCE_KEY,
                        CameraController.ISO_DEFAULT
                    )
                    if (value == "auto") {
                        isScanning = true
                    }
                }

                lastIsoExposureTime = timeMs
            }

            if (isoExposureString.isNotEmpty()) {
                var textColor = Color.rgb(255, 235, 59) // Yellow 500
                if (isScanning) {
                    // we only change the color if ae scanning is at least a certain time, otherwise we get a lot of flickering of the color
                    if (aeStartedScanningMs == -1L) {
                        aeStartedScanningMs = timeMs
                    } else if (timeMs - aeStartedScanningMs > 500) {
                        textColor = Color.rgb(244, 67, 54) // Red 500
                    }
                } else {
                    aeStartedScanningMs = -1
                }
                // can't cache the bounds rect, as the width may change significantly as the ISO or exposure values change
                var height: Int = applicationInterface.drawTextWithBackground(
                    canvas,
                    p,
                    isoExposureString,
                    textColor,
                    Color.BLACK,
                    locationX,
                    locationY,
                    Alignment.ALIGNMENT_TOP,
                    yboundsText,
                    Shadow.SHADOW_OUTLINE
                )
                height += gapY
                // only move locationY if we actually print something (because on old camera API, even if the ISO option has
                // been enabled, we'll never be able to display the on-screen ISO)
                if (deviceUiRotation == 90) {
                    locationY -= height
                } else {
                    locationY += height
                }
            }
        }

        // padding to align with earlier text
        val flashPadding = (1 * scaleFont + 0.5f).toInt() // convert dps to pixels

        if (cameraController != null) {
            // draw info icons

            var locationX2 = locationX - flashPadding
            val iconSize = (16 * scaleDp + 0.5f).toInt() // convert dps to pixels
            if (deviceUiRotation == 180) {
                locationX2 = locationX - iconSize + flashPadding
            }

            if (storeLocationPref) {
                iconDest[locationX2, locationY, locationX2 + iconSize] = locationY + iconSize
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                p.alpha = 64
                canvas.drawRect(iconDest, p)
                p.alpha = 255

                val location: Location? = applicationInterface.getLocation(locationInfo)
                if (location != null) {
                    canvas.drawBitmap(locationBitmap!!, null, iconDest, p)
                    val locationRadius = iconSize / 10
                    val indicatorX = locationX2 + iconSize - (locationRadius * 1.5).toInt()
                    val indicatorY = locationY + (locationRadius * 1.5).toInt()
                    p.color = if (locationInfo.locationWasCached()) Color.rgb(
                        127,
                        127,
                        127
                    ) else if (location.accuracy < 25.01f) Color.rgb(
                        37,
                        155,
                        36
                    ) else Color.rgb(255, 235, 59) // Green 500 or Yellow 500
                    canvas.drawCircle(
                        indicatorX.toFloat(),
                        indicatorY.toFloat(),
                        locationRadius.toFloat(),
                        p
                    )
                } else {
                    canvas.drawBitmap(locationOffBitmap!!, null, iconDest, p)
                }

                if (deviceUiRotation == 180) {
                    locationX2 -= iconSize + flashPadding
                } else {
                    locationX2 += iconSize + flashPadding
                }
            }

            if (isRawPref &&
                preview.supportsRaw() // RAW can be enabled, even if it isn't available for this camera (e.g., user enables RAW for back camera, but then
            // switches to front camera which doesn't support it)
            ) {
                iconDest[locationX2, locationY, locationX2 + iconSize] = locationY + iconSize
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                p.alpha = 64
                canvas.drawRect(iconDest, p)
                p.alpha = 255
                canvas.drawBitmap(
                    (if (isRawOnlyPref) rawOnlyBitmap else rawJpegBitmap)!!,
                    null,
                    iconDest,
                    p
                )

                if (deviceUiRotation == 180) {
                    locationX2 -= iconSize + flashPadding
                } else {
                    locationX2 += iconSize + flashPadding
                }
            }

            if (isFaceDetectionPref && preview.supportsFaceDetection()) {
                iconDest[locationX2, locationY, locationX2 + iconSize] = locationY + iconSize
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                p.alpha = 64
                canvas.drawRect(iconDest, p)
                p.alpha = 255
                canvas.drawBitmap(faceDetectionBitmap!!, null, iconDest, p)

                if (deviceUiRotation == 180) {
                    locationX2 -= iconSize + flashPadding
                } else {
                    locationX2 += iconSize + flashPadding
                }
            }

            if (storedAutoStabilisePref && preview.hasLevelAngleStable()) { // auto-level is supported for photos taken in video mode
                iconDest[locationX2, locationY, locationX2 + iconSize] = locationY + iconSize
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                p.alpha = 64
                canvas.drawRect(iconDest, p)
                p.alpha = 255
                canvas.drawBitmap(autoStabiliseBitmap!!, null, iconDest, p)

                if (deviceUiRotation == 180) {
                    locationX2 -= iconSize + flashPadding
                } else {
                    locationX2 += iconSize + flashPadding
                }
            }

            if ((photoMode === PhotoMode.DRO || photoMode === PhotoMode.HDR || photoMode === PhotoMode.Panorama || photoMode === PhotoMode.ExpoBracketing ||  //photoMode == MyApplicationInterface.PhotoMode.FocusBracketing ||
                        photoMode === PhotoMode.FastBurst || photoMode === PhotoMode.NoiseReduction || photoMode === PhotoMode.XNight || photoMode === PhotoMode.XBokeh || photoMode === PhotoMode.XBeauty
                        ) &&
                !applicationInterface.isVideoPref()
            ) { // these photo modes not supported for video mode
                iconDest[locationX2, locationY, locationX2 + iconSize] = locationY + iconSize
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                p.alpha = 64
                canvas.drawRect(iconDest, p)
                p.alpha = 255
                val bitmap =
                    if (photoMode === PhotoMode.DRO) droBitmap else if (photoMode === PhotoMode.HDR) hdrBitmap else if (photoMode === PhotoMode.Panorama) panoramaBitmap else if (photoMode === PhotoMode.ExpoBracketing) expoBitmap else  //photoMode == MyApplicationInterface.PhotoMode.FocusBracketing ? focusBracketBitmap :
                        if (photoMode === PhotoMode.FastBurst) burstBitmap else if (photoMode === PhotoMode.NoiseReduction) nrBitmap else if (photoMode === PhotoMode.XNight) xNightBitmap else if (photoMode === PhotoMode.XBokeh) xBokehBitmap else if (photoMode === PhotoMode.XBeauty) xBeautyBitmap else null
                if (bitmap != null) {
                    if (photoMode === PhotoMode.NoiseReduction && applicationInterface.getNRModePref() === ApplicationInterface.NRModePref.NRMODE_LOW_LIGHT) {
                        p.colorFilter = PorterDuffColorFilter(
                            Color.rgb(255, 235, 59),
                            PorterDuff.Mode.SRC_IN
                        ) // Yellow 500
                    }
                    canvas.drawBitmap(bitmap, null, iconDest, p)
                    p.colorFilter = null

                    if (deviceUiRotation == 180) {
                        locationX2 -= iconSize + flashPadding
                    } else {
                        locationX2 += iconSize + flashPadding
                    }
                }
            }


            // photo-stamp is supported for photos taken in video mode
            // but, it isn't supported in RAW-only mode
            if (storedHasStampPref && !(isRawOnlyPref && preview.supportsRaw())) {
                iconDest[locationX2, locationY, locationX2 + iconSize] = locationY + iconSize
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                p.alpha = 64
                canvas.drawRect(iconDest, p)
                p.alpha = 255
                canvas.drawBitmap(photostampBitmap!!, null, iconDest, p)

                if (deviceUiRotation == 180) {
                    locationX2 -= iconSize + flashPadding
                } else {
                    locationX2 += iconSize + flashPadding
                }
            }

            if (!isAudioEnabledPref && applicationInterface.isVideoPref()) {
                iconDest[locationX2, locationY, locationX2 + iconSize] = locationY + iconSize
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                p.alpha = 64
                canvas.drawRect(iconDest, p)
                p.alpha = 255
                canvas.drawBitmap(audioDisabledBitmap!!, null, iconDest, p)

                if (deviceUiRotation == 180) {
                    locationX2 -= iconSize + flashPadding
                } else {
                    locationX2 += iconSize + flashPadding
                }
            }

            // icons for slow motion, time-lapse or high speed video
            if (abs((captureRateFactor - 1.0f).toDouble()) > 1.0e-5 && applicationInterface.isVideoPref()) {
                iconDest[locationX2, locationY, locationX2 + iconSize] = locationY + iconSize
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                p.alpha = 64
                canvas.drawRect(iconDest, p)
                p.alpha = 255
                canvas.drawBitmap(
                    (if (captureRateFactor < 1.0f) slowMotionBitmap else timeLapseBitmap)!!,
                    null,
                    iconDest,
                    p
                )

                if (deviceUiRotation == 180) {
                    locationX2 -= iconSize + flashPadding
                } else {
                    locationX2 += iconSize + flashPadding
                }
            } else if (isHighSpeed && applicationInterface.isVideoPref()) {
                iconDest[locationX2, locationY, locationX2 + iconSize] = locationY + iconSize
                p.style = Paint.Style.FILL
                p.color = Color.BLACK
                p.alpha = 64
                canvas.drawRect(iconDest, p)
                p.alpha = 255
                canvas.drawBitmap(highSpeedFpsBitmap!!, null, iconDest, p)

                if (deviceUiRotation == 180) {
                    locationX2 -= iconSize + flashPadding
                } else {
                    locationX2 += iconSize + flashPadding
                }
            }

            if (timeMs > lastNeedFlashIndicatorTime + 100) {
                needFlashIndicator = false
                val flashValue: String? = preview.currentFlashValue
                // note, flashFrontscreenAuto not yet support for the flash symbol (as camera_controller.needsFlash() only returns info on the built-in actual flash, not frontscreen flash)
                if (flashValue != null &&
                    (flashValue == "flash_on"
                            || ((flashValue == "flash_auto" || flashValue == "flash_red_eye") && cameraController.needsFlash())
                            || cameraController.needsFrontScreenFlash()) && !applicationInterface.isVideoPref()
                ) { // flash-indicator not supported for photos taken in video mode
                    needFlashIndicator = true
                }

                lastNeedFlashIndicatorTime = timeMs
            }
            if (needFlashIndicator) {
                if (needsFlashTime != -1L) {
                    val fadeMs: Long = 500
                    var alpha = (timeMs - needsFlashTime) / fadeMs.toFloat()
                    if (timeMs - needsFlashTime >= fadeMs) alpha = 1.0f
                    iconDest[locationX2, locationY, locationX2 + iconSize] =
                        locationY + iconSize

                    /*if( MyDebug.LOG )
						Log.d(TAG, "alpha: " + alpha);*/
                    p.style = Paint.Style.FILL
                    p.color = Color.BLACK
                    p.alpha = (64 * alpha).toInt()
                    canvas.drawRect(iconDest, p)
                    p.alpha = (255 * alpha).toInt()
                    canvas.drawBitmap(flashBitmap!!, null, iconDest, p)
                    p.alpha = 255
                } else {
                    needsFlashTime = timeMs
                }
            } else {
                needsFlashTime = -1
            }

            if (deviceUiRotation == 90) {
                locationY -= iconGapY
            } else {
                locationY += (iconSize + iconGapY)
            }
        }

        if (cameraController != null && !showLastImage) {
            // draw histogram
            if (preview.isPreviewBitmapEnabled) {
                val histogram: IntArray? = preview.histogram
                if (histogram != null) {
                    /*if( MyDebug.LOG )
						Log.d(TAG, "histogram length: " + histogram.length);*/
                    val histogramWidth =
                        (histogramWidthDp * scaleDp + 0.5f).toInt() // convert dps to pixels
                    val histogramHeight =
                        (histogramHeightDp * scaleDp + 0.5f).toInt() // convert dps to pixels
                    // n.b., if changing the histogramHeight, remember to update focusSeekbar and
                    // focusBracketingTargetSeekbar margins in activity_main.xml
                    var locationX2 = locationX - flashPadding
                    if (deviceUiRotation == 180) {
                        locationX2 = locationX - histogramWidth + flashPadding
                    }
                    iconDest[locationX2 - flashPadding, locationY, locationX2 - flashPadding + histogramWidth] =
                        locationY + histogramHeight
                    if (deviceUiRotation == 90) {
                        iconDest.top -= histogramHeight
                        iconDest.bottom -= histogramHeight
                    }

                    p.style = Paint.Style.FILL
                    p.color = Color.argb(64, 0, 0, 0)
                    canvas.drawRect(iconDest, p)

                    var max = 0
                    for (value in histogram) {
                        max = max(max.toDouble(), value.toDouble()).toInt()
                    }

                    if (histogram.size == 256 * 3) {
                        var c = 0

                        /* For overlapping rgb, we'll have:
							(1, (1-a2).(1-a1).a0.r, (1-a2).a1.g, a2.b)
						   If we wanted to have the alpha scaling the same (i.e., same r, g, b values
						   if r=g=b, then this gives:
						       a2 = 1/[2+1/a0]
                               a1 = 1 - a2/[a0.(1-a2)]
                           However this then means that for non-overlapping colors, red is too
                           strong whilst blue is too weak, so we instead adjust to:
                               a0' = (a0+a1)/2
                               a1' = a1
                               a2' = (a1+a2)/2
						 */
                        /*final int a0 = 255;
						final int a1 = 128;
						final int a2 = 85;*/
                        //final int a0 = 191;
                        val a0 = 151
                        val a1 = 110
                        //final int a2 = 77;
                        val a2 = 94
                        /*final int a0 = 128;
						final int a1 = 85;
						final int a2 = 64;*/
                        val r = 255
                        val g = 255
                        val b = 255

                        for (i in 0..255) tempHistogramChannel[i] = histogram[c++]
                        p.color = Color.argb(a0, r, 0, 0)
                        drawHistogramChannel(canvas, tempHistogramChannel, max)

                        for (i in 0..255) tempHistogramChannel[i] = histogram[c++]
                        p.color = Color.argb(a1, 0, g, 0)
                        drawHistogramChannel(canvas, tempHistogramChannel, max)

                        for (i in 0..255) tempHistogramChannel[i] = histogram[c++]
                        p.color = Color.argb(a2, 0, 0, b)
                        drawHistogramChannel(canvas, tempHistogramChannel, max)
                    } else {
                        p.color = Color.argb(192, 255, 255, 255)
                        drawHistogramChannel(canvas, histogram, max)
                    }
                }
            }
        }
    }

    /** Draws histogram for a single color channel.
     * @param canvas Canvas to draw onto.
     * @param histogramChannel The histogram for this color.
     * @param max The maximum value of histogramChannel, or if drawing multiple channels, this
     * should be the maximum value of all histogram channels.
     */
    private fun drawHistogramChannel(canvas: Canvas, histogramChannel: IntArray, max: Int) {
        /*long debugTime = 0;
        if( MyDebug.LOG ) {
            debugTime = System.currentTimeMillis();
        }*/

        /*if( MyDebug.LOG )
			Log.d(TAG, "drawHistogramChannel, time before creating path: " + (System.currentTimeMillis() - debugTime));*/

        path.reset()
        path.moveTo(iconDest.left.toFloat(), iconDest.bottom.toFloat())
        for (c in histogramChannel.indices) {
            val cAlpha = c / histogramChannel.size.toDouble()
            val x = (cAlpha * iconDest.width()).toInt()
            val h = (histogramChannel[c] * iconDest.height()) / max
            path.lineTo((iconDest.left + x).toFloat(), (iconDest.bottom - h).toFloat())
        }
        path.lineTo(iconDest.right.toFloat(), iconDest.bottom.toFloat())
        path.close()
        /*if( MyDebug.LOG )
			Log.d(TAG, "drawHistogramChannel, time after creating path: " + (System.currentTimeMillis() - debugTime));*/
        canvas.drawPath(path, p)
        /*if( MyDebug.LOG )
			Log.d(TAG, "drawHistogramChannel, time before drawing path: " + (System.currentTimeMillis() - debugTime));*/
    }

    /** This includes drawing of the UI that requires the canvas to be rotated according to the preview's
     * current UI rotation.
     */
    private fun drawUI(canvas: Canvas, deviceUiRotation: Int, timeMs: Long) {
        val preview: Preview = mainActivity.preview
        val cameraController: CameraController? = preview.cameraController
        val uiRotation: Int = preview.uIRotation
        val uiPlacement: MainUI.UIPlacement = mainActivity.mainUI.uIPlacement
        val hasLevelAngle: Boolean = preview.hasLevelAngle()
        val levelAngle: Double = preview.levelAngle
        val hasGeoDirection: Boolean = preview.hasGeoDirection()
        val geoDirection: Double = preview.geoDirection
        val systemOrientation: SystemOrientation = mainActivity.systemOrientation
        val systemOrientationPortrait =
            systemOrientation === SystemOrientation.PORTRAIT
        var textBaseY = 0

        canvas.save()
        canvas.rotate(uiRotation.toFloat(), canvas.width / 2.0f, canvas.height / 2.0f)

        if (cameraController != null && !preview.isPreviewPaused) {
            /*canvas.drawText("PREVIEW", canvas.getWidth() / 2,
					canvas.getHeight() / 2, p);*/

            val gapY = (20 * scaleFont + 0.5f).toInt() // convert dps to pixels
            val textY = (16 * scaleFont + 0.5f).toInt() // convert dps to pixels
            var avoidUi = false
            // fine-tuning to adjust placement of text with respect to the GUI, depending on orientation
            if (uiPlacement === MainUI.UIPlacement.UIPLACEMENT_TOP && (deviceUiRotation == 0 || deviceUiRotation == 180)) {
                textBaseY = canvas.height - (0.1 * gapY).toInt()
                if (deviceUiRotation == 0) avoidUi = true
            } else if (deviceUiRotation == (if (uiPlacement === MainUI.UIPlacement.UIPLACEMENT_RIGHT) 0 else 180)) {
                textBaseY = canvas.height - (0.1 * gapY).toInt()
                avoidUi = true
            } else if (deviceUiRotation == (if (uiPlacement === MainUI.UIPlacement.UIPLACEMENT_RIGHT) 180 else 0)) {
                textBaseY = canvas.height - (2.5 * gapY).toInt() // leave room for GUI icons
            } else if (deviceUiRotation == 90 || deviceUiRotation == 270) {
                // 90 is upside down portrait
                // 270 is portrait

                if (lastTakePhotoTopTime == 0L || timeMs > lastTakePhotoTopTime + 1000) {
                    /*if( MyDebug.LOG )
                        Log.d(TAG, "update cached takePhotoTop");*/
                    // don't call this too often, for UI performance (due to calling View.getLocationOnScreen())
                    val view: View = mainActivity.findViewById(R.id.take_photo)
                    // align with "top" of the takePhoto button, but remember to take the rotation into account!
                    val viewLeft = getViewOnScreenX(view)
                    preview.view.getLocationOnScreen(guiLocation)
                    val thisLeft = guiLocation[if (systemOrientationPortrait) 1 else 0]
                    takePhotoTop = viewLeft - thisLeft

                    lastTakePhotoTopTime = timeMs
                    /*if( MyDebug.LOG ) {
                        Log.d(TAG, "deviceUiRotation: " + deviceUiRotation);
                        Log.d(TAG, "viewLeft: " + viewLeft);
                        Log.d(TAG, "thisLeft: " + thisLeft);
                        Log.d(TAG, "takePhotoTop: " + takePhotoTop);
                    }*/
                }

                // diffX is the difference from the center of the canvas to the position we want
                var maxX = if (systemOrientationPortrait) canvas.height else canvas.width
                val midX = maxX / 2
                var diffX = takePhotoTop - midX

                /*if( MyDebug.LOG ) {
					Log.d(TAG, "view left: " + viewLeft);
					Log.d(TAG, "this left: " + thisLeft);
					Log.d(TAG, "canvas is " + canvas.getWidth() + " x " + canvas.getHeight());
                    Log.d(TAG, "compare offsetX: " + (preview.view.getRootView().getRight()/2 - diffX)/scale);
				}*/

                // diffX is the difference from the center of the canvas to the position we want
                // assumes canvas is centered
                // avoids calling getLocationOnScreen for performance
                /*int offsetX = (int) (124 * scale + 0.5f); // convert dps to pixels
                // offsetX should be enough such that on-screen level angle (this is the lowest display on-screen text) does not
                // interfere with take photo icon when using at least a 16:9 preview aspect ratio
                // should correspond to the logged "compare offsetX" above
                int diffX = preview.view.getRootView().getRight()/2 - offsetX;
                */
                if (deviceUiRotation == 90) {
                    // so we don't interfere with the top bar info (datetime, free memory, ISO) when upside down
                    maxX -= (2.5 * gapY).toInt()
                }
                /*if( MyDebug.LOG ) {
					Log.d(TAG, "root view right: " + preview.view.getRootView().getRight());
					Log.d(TAG, "diffX: " + diffX);
					Log.d(TAG, "canvas.getWidth()/2 + diffX: " + (canvas.getWidth()/2+diffX));
					Log.d(TAG, "maxX: " + maxX);
				}*/
                if (midX + diffX > maxX) {
                    // in case goes off the size of the canvas, for "black bar" cases (when preview aspect ratio < screen aspect ratio)
                    diffX = maxX - midX
                }
                textBaseY = canvas.height / 2 + diffX - (0.5 * gapY).toInt()
            }

            if (deviceUiRotation == 0 || deviceUiRotation == 180) {
                // also avoid navigation bar in (reverse) landscape (for e.g. OnePlus Pad which has a landscape navigation bar when in landscape orientation)
                val navigationGap: Int =
                    if (deviceUiRotation == 0) mainActivity.navigationGapLandscape else mainActivity.navigationGapReverseLandscape
                textBaseY -= navigationGap
            }

            if (avoidUi) {
                // avoid parts of the UI
                var view: View = mainActivity.findViewById(R.id.focus_seekbar)
                if (view.isVisible) {
                    textBaseY -= view.height
                }
                view = mainActivity.findViewById(R.id.focus_bracketing_target_seekbar)
                if (view.isVisible) {
                    textBaseY -= view.height
                }
                /*view = main_activity.findViewById(R.id.sliders_container);
                if(view.getVisibility() == View.VISIBLE ) {
                    textBaseY -= view.getHeight();
                }*/
            }

            val drawAngle = hasLevelAngle && showAnglePref
            val drawGeoDirection = hasGeoDirection && showGeoDirectionPref
            if (drawAngle) {
                var color = Color.WHITE
                p.textSize = 14 * scaleFont + 0.5f // convert dps to pixels
                val pixelsOffsetX: Int
                if (drawGeoDirection) {
                    pixelsOffsetX = -(35 * scaleFont + 0.5f).toInt() // convert dps to pixels
                    p.textAlign = Paint.Align.LEFT
                } else {
                    //p.setTextAlign(Paint.Align.CENTER);
                    // slightly better for performance to use Align.LEFT, due to avoid measureText() call in drawTextWithBackground()
                    pixelsOffsetX =
                        -((if (levelAngle < 0) 16 else 14) * scaleFont + 0.5f).toInt() // convert dps to pixels
                    p.textAlign = Paint.Align.LEFT
                }
                if (abs(levelAngle) <= closeLevelAngle) {
                    color = angleHighlightColorPref
                    p.isUnderlineText = true
                }
                if (!::angleString.isInitialized || timeMs > this.lastAngleStringTime + 500) {
                    // update cached string
                    /*if( MyDebug.LOG )
						Log.d(TAG, "update angleString: " + angleString);*/
                    lastAngleStringTime = timeMs
                    val numberString = formatLevelAngle(levelAngle)
                    //String numberString = "" + levelAngle;
                    angleString = numberString + 0x00B0.toChar()
                    cachedAngle = levelAngle
                    //String angleString = "" + levelAngle;
                }
                //applicationInterface.drawTextWithBackground(canvas, p, angleString, color, Color.BLACK, canvas.getWidth() / 2 + pixelsOffsetX, textBaseY, MyApplicationInterface.Alignment.ALIGNMENT_BOTTOM, yboundsText, true);
                if (textBoundsAngleSingle == null) {
                    if (MyDebug.LOG) Log.d(TAG, "compute text_bounds_angle_single")
                    textBoundsAngleSingle = Rect()
                    val boundsAngleString = "-9.0" + 0x00B0.toChar()
                    p.getTextBounds(
                        boundsAngleString,
                        0,
                        boundsAngleString.length,
                        textBoundsAngleSingle
                    )
                }
                if (textBoundsAngleDouble == null) {
                    if (MyDebug.LOG) Log.d(TAG, "compute text_bounds_angle_double")
                    textBoundsAngleDouble = Rect()
                    val boundsAngleString = "-45.0" + 0x00B0.toChar()
                    p.getTextBounds(
                        boundsAngleString,
                        0,
                        boundsAngleString.length,
                        textBoundsAngleDouble
                    )
                }
                applicationInterface.drawTextWithBackground(
                    canvas,
                    p,
                    angleString,
                    color,
                    Color.BLACK,
                    canvas.width / 2 + pixelsOffsetX,
                    textBaseY,
                    Alignment.ALIGNMENT_BOTTOM,
                    null,
                    Shadow.SHADOW_OUTLINE,
                    if (abs(cachedAngle) < 10.0) textBoundsAngleSingle else textBoundsAngleDouble
                )
                p.isUnderlineText = false
            }
            if (drawGeoDirection) {
                val color = Color.WHITE
                p.textSize = 14 * scaleFont + 0.5f // convert dps to pixels
                val pixelsOffsetX: Int
                if (drawAngle) {
                    pixelsOffsetX = (10 * scaleFont + 0.5f).toInt() // convert dps to pixels
                    p.textAlign = Paint.Align.LEFT
                } else {
                    //p.setTextAlign(Paint.Align.CENTER);
                    // slightly better for performance to use Align.LEFT, due to avoid measureText() call in drawTextWithBackground()
                    pixelsOffsetX = -(14 * scaleFont + 0.5f).toInt() // convert dps to pixels
                    p.textAlign = Paint.Align.LEFT
                }
                var geoAngle = Math.toDegrees(geoDirection).toFloat()
                if (geoAngle < 0.0f) {
                    geoAngle += 360.0f
                }
                val string = geoAngle.roundToInt().toString() + 0x00B0.toChar()
                applicationInterface.drawTextWithBackground(
                    canvas,
                    p,
                    string,
                    color,
                    Color.BLACK,
                    canvas.width / 2 + pixelsOffsetX,
                    textBaseY,
                    Alignment.ALIGNMENT_BOTTOM,
                    yboundsText,
                    Shadow.SHADOW_OUTLINE
                )
            }
            if (preview.isOnTimer) {
                val remainingTime: Long = (preview.timerEndTime - timeMs + 999) / 1000
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "remaining_time: $remainingTime"
                )
                if (remainingTime > 0) {
                    p.textSize = 42 * scaleFont + 0.5f // convert dps to pixels
                    p.textAlign = Paint.Align.CENTER
                    val timeS = if (remainingTime < 60) {
                        // simpler to just show seconds when less than a minute
                        remainingTime.toString()
                    } else {
                        getTimeStringFromSeconds(remainingTime)
                    }
                    applicationInterface.drawTextWithBackground(
                        canvas,
                        p,
                        timeS,
                        Color.rgb(244, 67, 54),
                        Color.BLACK,
                        canvas.width / 2,
                        canvas.height / 2
                    ) // Red 500
                }
            } else if (preview.isVideoRecording) {
                val videoTime: Long = preview.getVideoTime(false)
                val timeS = getTimeStringFromSeconds(videoTime / 1000)
                /*if( MyDebug.LOG )
					Log.d(TAG, "videoTime: " + videoTime + " " + timeS);*/
                p.textSize = 14 * scaleFont + 0.5f // convert dps to pixels
                p.textAlign = Paint.Align.CENTER
                var pixelsOffsetY = 2 * textY // avoid overwriting the zoom
                val color = Color.rgb(244, 67, 54) // Red 500
                if (mainActivity.isScreenLocked) {
                    // writing in reverse order, bottom to top
                    applicationInterface.drawTextWithBackground(
                        canvas,
                        p,
                        context.resources.getString(R.string.screen_lock_message_2),
                        color,
                        Color.BLACK,
                        canvas.width / 2,
                        textBaseY - pixelsOffsetY
                    )
                    pixelsOffsetY += textY
                    applicationInterface.drawTextWithBackground(
                        canvas,
                        p,
                        context.resources.getString(R.string.screen_lock_message_1),
                        color,
                        Color.BLACK,
                        canvas.width / 2,
                        textBaseY - pixelsOffsetY
                    )
                    pixelsOffsetY += textY
                }
                if (!preview.isVideoRecordingPaused || ((timeMs / 500).toInt()) % 2 == 0) { // if video is paused, then flash the video time
                    applicationInterface.drawTextWithBackground(
                        canvas,
                        p,
                        timeS,
                        color,
                        Color.BLACK,
                        canvas.width / 2,
                        textBaseY - pixelsOffsetY
                    )
                    pixelsOffsetY += textY
                }
                if (showVideoMaxAmpPref && !preview.isVideoRecordingPaused) {
                    // audio amplitude
                    if (!this.hasVideoMaxAmp || timeMs > this.lastVideoMaxAmpTime + 50) {
                        hasVideoMaxAmp = true
                        val videoMaxAmpPrev1 = videoMaxAmpPrev2
                        videoMaxAmpPrev2 = videoMaxAmp
                        videoMaxAmp = preview.maxAmplitude
                        lastVideoMaxAmpTime = timeMs
                        if (MyDebug.LOG) {
                            if (videoMaxAmp > 30000) {
                                Log.d(
                                    TAG,
                                    "max_amp: $videoMaxAmp"
                                )
                            }
                            if (videoMaxAmp > 32767) {
                                Log.e(
                                    TAG,
                                    "video_max_amp greater than max: $videoMaxAmp"
                                )
                            }
                        }
                        if (videoMaxAmpPrev2 > videoMaxAmpPrev1 && videoMaxAmpPrev2 > videoMaxAmp) {
                            // new peak
                            videoMaxAmpPeak = videoMaxAmpPrev2
                        }
                        //videoMaxAmpPeak = Math.max(videoMaxAmpPeak, videoMaxAmp);
                    }
                    var ampFrac = videoMaxAmp / 32767.0f
                    ampFrac = max(ampFrac.toDouble(), 0.0).toFloat()
                    ampFrac = min(ampFrac.toDouble(), 1.0).toFloat()

                    //applicationInterface.drawTextWithBackground(canvas, p, "" + maxAmp, color, Color.BLACK, canvas.getWidth() / 2, textBaseY - pixelsOffsetY);
                    pixelsOffsetY += textY // allow extra space
                    val ampWidth = (160 * scaleDp + 0.5f).toInt() // convert dps to pixels
                    val ampHeight = (10 * scaleDp + 0.5f).toInt() // convert dps to pixels
                    val ampX = (canvas.width - ampWidth) / 2
                    p.color = Color.WHITE
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = strokeWidth
                    canvas.drawRect(
                        ampX.toFloat(),
                        (textBaseY - pixelsOffsetY).toFloat(),
                        (ampX + ampWidth).toFloat(),
                        (textBaseY - pixelsOffsetY + ampHeight).toFloat(),
                        p
                    )
                    p.style = Paint.Style.FILL
                    canvas.drawRect(
                        ampX.toFloat(),
                        (textBaseY - pixelsOffsetY).toFloat(),
                        ampX + ampFrac * ampWidth,
                        (textBaseY - pixelsOffsetY + ampHeight).toFloat(),
                        p
                    )
                    if (ampFrac < 1.0f) {
                        p.color = Color.BLACK
                        p.alpha = 64
                        canvas.drawRect(
                            ampX + ampFrac * ampWidth + 1,
                            (textBaseY - pixelsOffsetY).toFloat(),
                            (ampX + ampWidth).toFloat(),
                            (textBaseY - pixelsOffsetY + ampHeight).toFloat(),
                            p
                        )
                        p.alpha = 255
                    }
                    if (videoMaxAmpPeak > videoMaxAmp) {
                        var peakFrac = videoMaxAmpPeak / 32767.0f
                        peakFrac = max(peakFrac.toDouble(), 0.0).toFloat()
                        peakFrac = min(peakFrac.toDouble(), 1.0).toFloat()
                        p.color = Color.YELLOW
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = strokeWidth
                        canvas.drawLine(
                            ampX + peakFrac * ampWidth,
                            (textBaseY - pixelsOffsetY).toFloat(),
                            ampX + peakFrac * ampWidth,
                            (textBaseY - pixelsOffsetY + ampHeight).toFloat(),
                            p
                        )
                        p.color = Color.WHITE
                    }
                }
            } else if (takingPicture && captureStarted) {
                if (cameraController.isCapturingBurst) {
                    val nBurstTaken: Int = cameraController.nBurstTaken + 1
                    val nBurstTotal: Int = cameraController.burstTotal
                    p.textSize = 14 * scaleFont + 0.5f // convert dps to pixels
                    p.textAlign = Paint.Align.CENTER
                    var pixelsOffsetY = 2 * textY // avoid overwriting the zoom
                    if (deviceUiRotation == 0 && applicationInterface.photoMode === PhotoMode.FocusBracketing) {
                        // avoid clashing with the target focus bracketing seekbar in landscape orientation
                        pixelsOffsetY = 5 * gapY
                    }
                    var text = context.resources.getString(R.string.capturing) + " " + nBurstTaken
                    if (nBurstTotal > 0) {
                        text += " / $nBurstTotal"
                    }
                    applicationInterface.drawTextWithBackground(
                        canvas,
                        p,
                        text,
                        Color.WHITE,
                        Color.BLACK,
                        canvas.width / 2,
                        textBaseY - pixelsOffsetY
                    )
                } else if (cameraController.isManualISO) {
                    // only show "capturing" text with time for manual exposure time >= 0.5s
                    val exposureTime: Long = cameraController.exposureTime
                    if (exposureTime >= 500000000L) {
                        if (((timeMs / 500).toInt()) % 2 == 0) {
                            p.textSize = 14 * scaleFont + 0.5f // convert dps to pixels
                            p.textAlign = Paint.Align.CENTER
                            val pixelsOffsetY = 2 * textY // avoid overwriting the zoom
                            val color = Color.rgb(244, 67, 54) // Red 500
                            applicationInterface.drawTextWithBackground(
                                canvas,
                                p,
                                context.resources.getString(R.string.capturing),
                                color,
                                Color.BLACK,
                                canvas.width / 2,
                                textBaseY - pixelsOffsetY
                            )
                        }
                    }
                }
            } else if (imageQueueFull) {
                if (((timeMs / 500).toInt()) % 2 == 0) {
                    p.textSize = 14 * scaleFont + 0.5f // convert dps to pixels
                    p.textAlign = Paint.Align.CENTER
                    val pixelsOffsetY = 2 * textY // avoid overwriting the zoom
                    val nImagesToSave: Int =
                        applicationInterface.imageSaver.nRealImagesToSave
                    val string =
                        context.resources.getString(R.string.processing) + " (" + nImagesToSave + " " + context.resources.getString(
                            R.string.remaining
                        ) + ")"
                    applicationInterface.drawTextWithBackground(
                        canvas,
                        p,
                        string,
                        Color.LTGRAY,
                        Color.BLACK,
                        canvas.width / 2,
                        textBaseY - pixelsOffsetY
                    )
                }
            }

            if (preview.supportsZoom() && showZoomPref) {
                val zoomRatio: Float = preview.zoomRatio
                // only show when actually zoomed in - or out!
                // but only show if zoomed in by at least 1.1x, to avoid showing when only very slightly
                // zoomed in - otherwise on devices that support zooming out to ultrawide, it's hard to
                // zoom back to exactly 1.0x
                //if( zoomRatio < 1.0f - 1.0e-5f || zoomRatio > 1.0f + 1.0e-5f ) {
                if (zoomRatio < 1.0f - 1.0e-5f || zoomRatio > 1.1f - 1.0e-5f) {
                    // Convert the dps to pixels, based on density scale
                    p.textSize = 14 * scaleFont + 0.5f // convert dps to pixels
                    p.textAlign = Paint.Align.CENTER
                    applicationInterface.drawTextWithBackground(
                        canvas,
                        p,
                        context.resources.getString(R.string.zoom) + ": " + zoomRatio + "x",
                        Color.WHITE,
                        Color.BLACK,
                        canvas.width / 2,
                        textBaseY - textY,
                        Alignment.ALIGNMENT_BOTTOM,
                        yboundsText,
                        Shadow.SHADOW_OUTLINE
                    )
                }
            }
        } else if (cameraController == null) {
            /*if( MyDebug.LOG ) {
				Log.d(TAG, "no camera!");
				Log.d(TAG, "width " + canvas.getWidth() + " height " + canvas.getHeight());
			}*/
            p.color = Color.WHITE
            p.textSize = 14 * scaleFont + 0.5f // convert dps to pixels
            p.textAlign = Paint.Align.CENTER
            val pixelsOffset = (20 * scaleFont + 0.5f).toInt() // convert dps to pixels
            if (preview.hasPermissions()) {
                if (preview.openCameraFailed()) {
                    canvas.drawText(
                        context.resources.getString(R.string.failed_to_open_camera_1),
                        canvas.width / 2.0f,
                        canvas.height / 2.0f,
                        p
                    )
                    canvas.drawText(
                        context.resources.getString(R.string.failed_to_open_camera_2),
                        canvas.width / 2.0f,
                        canvas.height / 2.0f + pixelsOffset,
                        p
                    )
                    canvas.drawText(
                        context.resources.getString(R.string.failed_to_open_camera_3),
                        canvas.width / 2.0f,
                        canvas.height / 2.0f + 2 * pixelsOffset,
                        p
                    )
                    // n.b., use applicationInterface.getCameraIdPref(), as preview.cameraId returns 0 if cameraController==null
                    canvas.drawText(
                        context.resources.getString(R.string.camera_id) + ":" + applicationInterface.getCameraIdPref(),
                        canvas.width / 2.0f,
                        canvas.height / 2.0f + 3 * pixelsOffset,
                        p
                    )
                }
            } else {
                canvas.drawText(
                    context.resources.getString(R.string.no_permission),
                    canvas.width / 2.0f,
                    canvas.height / 2.0f,
                    p
                )
            }
            //canvas.drawRect(0.0f, 0.0f, 100.0f, 100.0f, p);
            //canvas.drawRGB(255, 0, 0);
            //canvas.drawRect(0.0f, 0.0f, canvas.getWidth(), canvas.getHeight(), p);
        }

        var topX = (5 * scaleDp + 0.5f).toInt() // convert dps to pixels
        var topY = (5 * scaleDp + 0.5f).toInt() // convert dps to pixels
        val topIcon: View? = mainActivity.mainUI.topIcon
        if (topIcon != null) {
            if (lastTopIconShiftTime == 0L || timeMs > lastTopIconShiftTime + 1000) {
                // avoid computing every time, due to cost of calling View.getLocationOnScreen()
                /*if( MyDebug.LOG )
                    Log.d(TAG, "update cached topIconShift");*/
                var topMargin = getViewOnScreenX(topIcon)
                if (systemOrientation === SystemOrientation.LANDSCAPE) topMargin += topIcon.width
                else if (systemOrientation === SystemOrientation.PORTRAIT) topMargin += topIcon.height
                // n.b., don't adjust topMargin for icon width/height for a reverse orientation
                preview.view.getLocationOnScreen(guiLocation)
                var previewLeft = guiLocation[if (systemOrientationPortrait) 1 else 0]
                if (systemOrientation === SystemOrientation.REVERSE_LANDSCAPE) previewLeft += preview.view
                    .width // actually want preview-right for reverse landscape

                this.topIconShift = topMargin - previewLeft
                if (systemOrientation === SystemOrientation.REVERSE_LANDSCAPE) this.topIconShift =
                    -this.topIconShift

                /*if( MyDebug.LOG ) {
                    Log.d(TAG, "top_icon.getRotation(): " + top_icon.getRotation());
                    Log.d(TAG, "previewLeft: " + previewLeft);
                    Log.d(TAG, "topMargin: " + topMargin);
                    Log.d(TAG, "topIconShift: " + topIconShift);
                }*/
                lastTopIconShiftTime = timeMs
            }

            if (this.topIconShift > 0) {
                if (deviceUiRotation == 90 || deviceUiRotation == 270) {
                    // portrait
                    topY += topIconShift
                } else {
                    // landscape
                    topX += topIconShift
                }
            }
        }

        run {
            /*int focusSeekbarsMarginLeftDp = 85;
                       if( wantHistogram )
                           focusSeekbarsMarginLeftDp += DrawPreview.histogramHeightDp;*/
            // 135 needed to make room for on-screen info lines in DrawPreview.onDrawInfoLines(), including the histogram
            // but, we also need to take the topIconShift into account, for widescreen aspect ratios and "icons along top" UI placement
            val focusSeekbarsMarginLeftDp = 135
            var newFocusSeekbarsMarginLeft =
                (focusSeekbarsMarginLeftDp * scaleDp + 0.5f).toInt() // convert dps to pixels
            if (topIconShift > 0) {
                newFocusSeekbarsMarginLeft += topIconShift
            }
            if (focusSeekbarsMarginLeft == -1 || newFocusSeekbarsMarginLeft != focusSeekbarsMarginLeft) {
                // we check whether focusSeekbarsMarginLeft has changed, in case there is a performance cost for setting layoutparams
                this.focusSeekbarsMarginLeft = newFocusSeekbarsMarginLeft
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "set focus_seekbars_margin_left to $focusSeekbarsMarginLeft"
                )

                // "left" and "right" here are written assuming we're in landscape system orientation
                var view: View = mainActivity.findViewById(R.id.focus_seekbar)
                var layoutParams = view.layoutParams as RelativeLayout.LayoutParams
                preview.view.getLocationOnScreen(guiLocation)
                var previewLeft = guiLocation[if (systemOrientationPortrait) 1 else 0]
                if (systemOrientation === SystemOrientation.REVERSE_LANDSCAPE) previewLeft += preview.view
                    .width // actually want preview-right for reverse landscape


                view.getLocationOnScreen(guiLocation)
                var seekbarRight = guiLocation[if (systemOrientationPortrait) 1 else 0]
                if (systemOrientation === SystemOrientation.LANDSCAPE || systemOrientation === SystemOrientation.PORTRAIT) {
                    // n.b., we read view.getWidth() even if systemOrientation is portrait, because the seekbar is rotated in portrait orientation
                    seekbarRight += view.width
                } else {
                    // and for reversed landscape, the seekbar is rotated 180 degrees, and getLocationOnScreen() returns the location after the rotation
                    seekbarRight -= view.width
                }

                val minSeekbarWidth = (150 * scaleDp + 0.5f).toInt() // convert dps to pixels
                var newSeekbarWidth =
                    if (systemOrientation === SystemOrientation.LANDSCAPE || systemOrientation === SystemOrientation.PORTRAIT) {
                        seekbarRight - (previewLeft + focusSeekbarsMarginLeft)
                    } else {
                        // reversed landscape
                        previewLeft - focusSeekbarsMarginLeft - seekbarRight
                    }
                newSeekbarWidth =
                    max(newSeekbarWidth.toDouble(), minSeekbarWidth.toDouble()).toInt()
                /*if( MyDebug.LOG ) {
                    Log.d(TAG, "previewLeft: " + previewLeft);
                    Log.d(TAG, "seekbarRight: " + seekbarRight);
                    Log.d(TAG, "newSeekbarWidth: " + newSeekbarWidth);
                }*/
                layoutParams.width = newSeekbarWidth
                view.layoutParams = layoutParams

                view = mainActivity.findViewById(R.id.focus_bracketing_target_seekbar)
                layoutParams = view.layoutParams as RelativeLayout.LayoutParams
                layoutParams.width = newSeekbarWidth
                view.layoutParams = layoutParams

                // need to update due to changing width of focus seekbars
                mainActivity.mainUI.setFocusSeekbarsRotation()
            }
        }

        var batteryX = topX
        var batteryY = topY + (5 * scaleDp + 0.5f).toInt()
        val batteryWidth = (5 * scaleDp + 0.5f).toInt() // convert dps to pixels
        val batteryHeight = 4 * batteryWidth
        if (uiRotation == 90 || uiRotation == 270) {
            // n.b., this is only for when lockToLandscape==true, so we don't look at deviceUiRotation
            val diff = canvas.width - canvas.height
            batteryX += diff / 2
            batteryY -= diff / 2
        }
        if (deviceUiRotation == 90) {
            batteryY = canvas.height - batteryY - batteryHeight
        }
        if (deviceUiRotation == 180) {
            batteryX = canvas.width - batteryX - batteryWidth
        }
        if (showBatteryPref) {
            if (!this.hasBatteryFrac || timeMs > this.lastBatteryTime + 60000) {
                // only check periodically - unclear if checking is costly in any way
                // note that it's fine to call registerReceiver repeatedly - we pass a null receiver, so this is fine as a "one shot" use
                val batteryStatus: Intent =
                    mainActivity.registerReceiver(null, batteryIfilter) as Intent
                val batteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val batteryScale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                hasBatteryFrac = true
                batteryFrac = batteryLevel / batteryScale.toFloat()
                lastBatteryTime = timeMs
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "Battery status is $batteryLevel / $batteryScale : $batteryFrac"
                )
            }
            //batteryFrac = 0.2999f; // test
            var drawBattery = true
            if (batteryFrac <= 0.05f) {
                // flash icon at this low level
                drawBattery = (((timeMs / 1000)) % 2) == 0L
            }
            if (drawBattery) {
                p.color = if (batteryFrac > 0.15f) Color.rgb(37, 155, 36) else Color.rgb(
                    244,
                    67,
                    54
                ) // Green 500 or Red 500
                p.style = Paint.Style.FILL
                canvas.drawRect(
                    batteryX.toFloat(),
                    batteryY + (1.0f - batteryFrac) * (batteryHeight - 2),
                    (batteryX + batteryWidth).toFloat(),
                    (batteryY + batteryHeight).toFloat(),
                    p
                )
                if (batteryFrac < 1.0f) {
                    p.color = Color.BLACK
                    p.alpha = 64
                    canvas.drawRect(
                        batteryX.toFloat(),
                        batteryY.toFloat(),
                        (batteryX + batteryWidth).toFloat(),
                        batteryY + (1.0f - batteryFrac) * (batteryHeight - 2),
                        p
                    )
                    p.alpha = 255
                }
            }
            topX += (10 * scaleDp + 0.5f).toInt() // convert dps to pixels
        }

        onDrawInfoLines(canvas, topX, topY, textBaseY, deviceUiRotation, timeMs)

        canvas.restore()
    }

    private val angleStep: Int
        get() {
            val preview: Preview = mainActivity.preview
            var angleStep = 10
            val zoomRatio: Float = preview.zoomRatio
            if (zoomRatio >= 10.0f) angleStep = 1
            else if (zoomRatio >= 5.0f) angleStep = 2
            else if (zoomRatio >= 2.0f) angleStep = 5
            return angleStep
        }

    private fun drawAngleLines(canvas: Canvas, deviceUiRotation: Int, timeMs: Long) {
        val preview: Preview = mainActivity.preview
        val cameraController: CameraController? = preview.cameraController
        val systemOrientation: SystemOrientation = mainActivity.systemOrientation
        val systemOrientationPortrait =
            systemOrientation === SystemOrientation.PORTRAIT
        val uiState = try {
            mainActivity.cameraViewModel.uiState.value
        } catch (_: Exception) {
            null
        }
        val horizonAngleState = uiState?.horizonAngle
        val hasLevelAngle: Boolean = preview.hasLevelAngle() || (horizonAngleState != null)
        val actualShowAngleLinePref =
            if (photoMode === PhotoMode.Panorama) {
                // in panorama mode, we should the level iff we aren't taking the panorama photos
                !mainActivity.applicationInterface.gyroSensor.isRecording
            } else showAngleLinePref

        val allowAngleLines = cameraController != null && !preview.isPreviewPaused

        if (allowAngleLines && hasLevelAngle && (actualShowAngleLinePref || showPitchLinesPref || showGeoDirectionLinesPref)) {
            val levelAngle: Double = horizonAngleState?.angleDegrees ?: preview.levelAngle
            val hasPitchAngle: Boolean = preview.hasPitchAngle()
            val pitchAngle: Double = preview.pitchAngle
            val hasGeoDirection: Boolean =
                preview.hasGeoDirection() || ((uiState?.compassDegrees ?: 0.0f) != 0.0f)
            val geoDirection: Double = if ((uiState?.compassDegrees
                    ?: 0.0f) != 0.0f
            ) uiState!!.compassDegrees.toDouble() else preview.geoDirection
            // n.b., must draw this without the standard canvas rotation
            // lines should be shorter in portrait
            val radiusDps = if (deviceUiRotation == 90 || deviceUiRotation == 270) 60 else 80
            val radius = (radiusDps * scaleDp + 0.5f).toInt() // convert dps to pixels
            val oRadius = (10 * scaleDp + 0.5f).toInt() // convert dps to pixels
            var angle: Double = -preview.origLevelAngle
            // see http://android-developers.blogspot.co.uk/2010/09/one-screen-turn-deserves-another.html
            val rotation: Int = mainActivity.getDisplayRotation(false)
            when (rotation) {
                Surface.ROTATION_90 -> angle -= 90.0
                Surface.ROTATION_270 -> angle += 90.0
                Surface.ROTATION_180 -> angle += 180.0
                Surface.ROTATION_0 -> {}
                else -> {}
            }
            /*if( MyDebug.LOG ) {
                Log.d(TAG, "systemOrientation: " + systemOrientation);
                Log.d(TAG, "rotation: " + rotation);
            }*/
            /*if( MyDebug.LOG ) {
				Log.d(TAG, "origLevelAngle: " + preview.origLevelAngle);
				Log.d(TAG, "angle: " + angle);
			}*/
            val cx = canvas.width / 2
            val cy = canvas.height / 2

            var isLevel = false
            if (hasLevelAngle && abs(levelAngle) <= closeLevelAngle) { // n.b., use levelAngle, not angle or origLevelAngle
                isLevel = true
            }

            val lineAlpha = 160
            val hthickness = (0.5f * scaleDp + 0.5f) // convert dps to pixels
            var shadowRadius = hthickness
            shadowRadius = max(shadowRadius.toDouble(), 1.0).toFloat()
            p.style = Paint.Style.FILL

            if (actualShowAngleLinePref && preview.hasLevelAngleStable()) {
                // draw the non-rotated part of the level
                // only show the angle line if level angle "stable" (i.e., not pointing near vertically up or down)

                p.setShadowLayer(shadowRadius, 0.0f, 0.0f, Color.BLACK)

                if (isLevel) {
                    p.color = angleHighlightColorPref
                } else {
                    p.color = Color.WHITE
                }
                p.alpha = lineAlpha
                drawRect[(cx - radius - oRadius).toFloat(), cy - hthickness, (cx - radius).toFloat()] =
                    cy + hthickness
                canvas.drawRoundRect(drawRect, hthickness, hthickness, p)
                drawRect[(cx + radius).toFloat(), cy - hthickness, (cx + radius + oRadius).toFloat()] =
                    cy + hthickness
                canvas.drawRoundRect(drawRect, hthickness, hthickness, p)

                p.clearShadowLayer()
            }

            canvas.save()
            canvas.rotate(angle.toFloat(), cx.toFloat(), cy.toFloat())

            if (actualShowAngleLinePref && preview.hasLevelAngleStable()) {
                // only show the angle line if level angle "stable" (i.e., not pointing near vertically up or down)

                p.setShadowLayer(shadowRadius, 0.0f, 0.0f, Color.BLACK)

                if (isLevel) {
                    p.color = angleHighlightColorPref
                } else {
                    p.color = Color.WHITE
                }
                p.alpha = lineAlpha
                drawRect[(cx - radius).toFloat(), cy - hthickness, (cx + radius).toFloat()] =
                    cy + hthickness
                canvas.drawRoundRect(drawRect, hthickness, hthickness, p)

                // draw the vertical crossbar
                drawRect[cx - hthickness, cy - radius / 2.0f, cx + hthickness] = cy + radius / 2.0f
                canvas.drawRoundRect(drawRect, hthickness, hthickness, p)

                if (isLevel) {
                    // draw a second line

                    p.color = angleHighlightColorPref
                    p.alpha = lineAlpha
                    drawRect[(cx - radius).toFloat(), cy - 6 * hthickness, (cx + radius).toFloat()] =
                        cy - 4 * hthickness
                    canvas.drawRoundRect(drawRect, hthickness, hthickness, p)
                }

                p.clearShadowLayer()
            }
            updateCachedViewAngles(timeMs) // ensure viewAngleXPreview, viewAngleYPreview are computed and up to date
            val cameraAngleX: Float
            val cameraAngleY: Float
            if (systemOrientationPortrait) {
                cameraAngleX = this.viewAngleYPreview
                cameraAngleY = this.viewAngleXPreview
            } else {
                cameraAngleX = this.viewAngleXPreview
                cameraAngleY = this.viewAngleYPreview
            }
            val angleScaleX =
                (canvas.width / (2.0 * tan(Math.toRadians((cameraAngleX / 2.0))))).toFloat()
            val angleScaleY =
                (canvas.height / (2.0 * tan(Math.toRadians((cameraAngleY / 2.0))))).toFloat()
            /*if( MyDebug.LOG ) {
				Log.d(TAG, "cameraAngleX: " + cameraAngleX);
				Log.d(TAG, "cameraAngleY: " + cameraAngleY);
				Log.d(TAG, "angleScaleX: " + angleScaleX);
				Log.d(TAG, "angleScaleY: " + angleScaleY);
				Log.d(TAG, "angleScaleX/scale: " + angleScaleX/scale);
				Log.d(TAG, "angleScaleY/scale: " + angleScaleY/scale);
			}*/
            /*if( MyDebug.LOG ) {
				Log.d(TAG, "hasPitchAngle?: " + hasPitchAngle);
				Log.d(TAG, "showPitchLines?: " + showPitchLines);
			}*/
            var angleScale =
                sqrt((angleScaleX * angleScaleX + angleScaleY * angleScaleY).toDouble()).toFloat()
            angleScale *= preview.zoomRatio
            if (hasPitchAngle && showPitchLinesPref) {
                // lines should be shorter in portrait
                val pitchRadiusDps =
                    if (deviceUiRotation == 90 || deviceUiRotation == 270) 80 else 100
                val pitchRadius =
                    (pitchRadiusDps * scaleDp + 0.5f).toInt() // convert dps to pixels
                val angleStep = angleStep
                var latitudeAngle = -90
                while (latitudeAngle <= 90) {
                    val thisAngle = pitchAngle - latitudeAngle
                    if (abs(thisAngle) < 90.0) {
                        val pitchDistance =
                            angleScale * tan(Math.toRadians(thisAngle)).toFloat() // angleScale is already in pixels rather than dps
                        /*if( MyDebug.LOG ) {
							Log.d(TAG, "pitchAngle: " + pitchAngle);
							Log.d(TAG, "pitchDistanceDp: " + pitchDistanceDp);
						}*/
                        p.color = Color.WHITE
                        p.textAlign = Paint.Align.LEFT
                        if (latitudeAngle == 0 && abs(pitchAngle) < 1.0) {
                            p.alpha = 255
                        } else if (latitudeAngle == 90 && abs(pitchAngle - 90) < 3.0) {
                            p.alpha = 255
                        } else if (latitudeAngle == -90 && abs(pitchAngle + 90) < 3.0) {
                            p.alpha = 255
                        } else {
                            p.alpha = lineAlpha
                        }
                        p.setShadowLayer(shadowRadius, 0.0f, 0.0f, Color.BLACK)
                        // can't use drawRoundRect(left, top, right, bottom, ...) as that requires API 21
                        drawRect[(cx - pitchRadius).toFloat(), cy + pitchDistance - hthickness, (cx + pitchRadius).toFloat()] =
                            cy + pitchDistance + hthickness
                        canvas.drawRoundRect(drawRect, hthickness, hthickness, p)
                        p.clearShadowLayer()
                        // draw pitch angle indicator
                        applicationInterface.drawTextWithBackground(
                            canvas,
                            p,
                            latitudeAngle.toString() + "\u00B0",
                            p.color,
                            Color.BLACK,
                            (cx + pitchRadius + 4 * hthickness).toInt(),
                            (cy + pitchDistance - 2 * hthickness).toInt(),
                            Alignment.ALIGNMENT_CENTRE
                        )
                    }
                    latitudeAngle += angleStep
                }
            }
            if (hasGeoDirection && hasPitchAngle && showGeoDirectionLinesPref) {
                // lines should be longer in portrait - n.b., this is opposite to behavior of pitch lines, as
                // geo lines are drawn perpendicularly
                val geoRadiusDps =
                    if (deviceUiRotation == 90 || deviceUiRotation == 270) 100 else 80
                val geoRadius = (geoRadiusDps * scaleDp + 0.5f).toInt() // convert dps to pixels
                val geoAngle = Math.toDegrees(geoDirection).toFloat()
                val angleStep = angleStep
                var longitudeAngle = 0
                while (longitudeAngle < 360) {
                    var thisAngle = (longitudeAngle - geoAngle).toDouble()
                    /*if( MyDebug.LOG ) {
						Log.d(TAG, "longitudeAngle: " + longitudeAngle);
						Log.d(TAG, "geoAngle: " + geoAngle);
						Log.d(TAG, "thisAngle: " + thisAngle);
					}*/
                    // normalize to be in interval [0, 360)
                    while (thisAngle >= 360.0) thisAngle -= 360.0
                    while (thisAngle < -360.0) thisAngle += 360.0
                    // pick shortest angle
                    if (thisAngle > 180.0) thisAngle = -(360.0 - thisAngle)
                    if (abs(thisAngle) < 90.0) {
                        /*if( MyDebug.LOG ) {
							Log.d(TAG, "thisAngle is now: " + thisAngle);
						}*/
                        val geoDistance =
                            angleScale * tan(Math.toRadians(thisAngle)).toFloat() // angleScale is already in pixels rather than dps
                        p.color = Color.WHITE
                        p.textAlign = Paint.Align.CENTER
                        p.alpha = lineAlpha
                        p.setShadowLayer(shadowRadius, 0.0f, 0.0f, Color.BLACK)
                        // can't use drawRoundRect(left, top, right, bottom, ...) as that requires API 21
                        drawRect[cx + geoDistance - hthickness, (cy - geoRadius).toFloat(), cx + geoDistance + hthickness] =
                            (cy + geoRadius).toFloat()
                        canvas.drawRoundRect(drawRect, hthickness, hthickness, p)
                        p.clearShadowLayer()
                        // draw geo direction angle indicator
                        applicationInterface.drawTextWithBackground(
                            canvas,
                            p,
                            longitudeAngle.toString() + "\u00B0",
                            p.color,
                            Color.BLACK,
                            (cx + geoDistance).toInt(),
                            (cy - geoRadius - 4 * hthickness).toInt(),
                            Alignment.ALIGNMENT_BOTTOM
                        )
                    }
                    longitudeAngle += angleStep
                }
            }

            p.alpha = 255
            p.style = Paint.Style.FILL // reset

            canvas.restore()
        }

        if (hasAutoStabiliseCrop) {
            val w2 = autoStabiliseCrop[0]
            val h2 = autoStabiliseCrop[1]
            val cx = canvas.width / 2
            val cy = canvas.height / 2

            val left = (canvas.width - w2) / 2.0f
            val top = (canvas.height - h2) / 2.0f
            val right = (canvas.width + w2) / 2.0f
            val bottom = (canvas.height + h2) / 2.0f

            val levelAngle = preview.origLevelAngle

            canvas.save()
            canvas.rotate(-levelAngle.toFloat(), cx.toFloat(), cy.toFloat())

            // draw shaded area
            val oDist =
                sqrt((canvas.width * canvas.width + canvas.height * canvas.height).toDouble()).toFloat()
            val oLeft = (canvas.width - oDist) / 2.0f
            val oTop = (canvas.height - oDist) / 2.0f
            val oRight = (canvas.width + oDist) / 2.0f
            val oBottom = (canvas.height + oDist) / 2.0f
            p.style = Paint.Style.FILL
            p.color = Color.rgb(0, 0, 0)
            p.alpha = cropShadingAlphaC
            canvas.drawRect(oLeft, oTop, left, oBottom, p)
            canvas.drawRect(right, oTop, oRight, oBottom, p)
            canvas.drawRect(left, oTop, right, top, p) // top
            canvas.drawRect(left, bottom, right, oBottom, p) // bottom

            if (hasLevelAngle && abs(levelAngle) <= closeLevelAngle) { // n.b., use levelAngle, not angle or origLevelAngle
                p.color = angleHighlightColorPref
            } else {
                p.color = Color.WHITE
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokeWidth

            canvas.drawRect(left, top, right, bottom, p)

            canvas.restore()

            p.style = Paint.Style.FILL // reset
            p.alpha = 255 // reset
        }
    }

    private fun doThumbnailAnimation(canvas: Canvas, timeMs: Long) {
        val preview: Preview = mainActivity.preview
        val cameraController: CameraController? = preview.cameraController
        // note, no need to check preferences here, as we do that when setting thumbnailAnim
        if (cameraController != null && this.thumbnailAnim && lastThumbnail != null) {
            val uiRotation: Int = preview.uIRotation
            val time = timeMs - this.thumbnailAnimStartMs
            val duration: Long = 500
            if (time > duration) {
                if (MyDebug.LOG) Log.d(TAG, "thumbnail_anim finished")
                this.thumbnailAnim = false
            } else {
                thumbnailAnimSrcRect.left = 0f
                thumbnailAnimSrcRect.top = 0f
                thumbnailAnimSrcRect.right = lastThumbnail!!.width.toFloat()
                thumbnailAnimSrcRect.bottom = lastThumbnail!!.height.toFloat()
                val galleryButton: View = mainActivity.findViewById(R.id.gallery)
                val alpha = (time.toFloat()) / duration.toFloat()

                val stX = canvas.width / 2
                val stY = canvas.height / 2
                val ndX = galleryButton.left + galleryButton.width / 2
                val ndY = galleryButton.top + galleryButton.height / 2
                val thumbnailX = ((1.0f - alpha) * stX + alpha * ndX).toInt()
                val thumbnailY = ((1.0f - alpha) * stY + alpha * ndY).toInt()

                val stW = canvas.width.toFloat()
                val stH = canvas.height.toFloat()
                val ndW = galleryButton.width.toFloat()
                val ndH = galleryButton.height.toFloat()
                //int thumbnailW = (int)( (1.0f-alpha)*stW + alpha*ndW );
                //int thumbnailH = (int)( (1.0f-alpha)*stH + alpha*ndH );
                val correctionW = stW / ndW - 1.0f
                val correctionH = stH / ndH - 1.0f
                val thumbnailW = (stW / (1.0f + alpha * correctionW)).toInt()
                val thumbnailH = (stH / (1.0f + alpha * correctionH)).toInt()
                thumbnailAnimDstRect.left = thumbnailX - thumbnailW / 2.0f
                thumbnailAnimDstRect.top = thumbnailY - thumbnailH / 2.0f
                thumbnailAnimDstRect.right = thumbnailX + thumbnailW / 2.0f
                thumbnailAnimDstRect.bottom = thumbnailY + thumbnailH / 2.0f
                //canvas.drawBitmap(this.thumbnail, thumbnailAnimSrcRect, thumbnailAnimDstRect, p);
                thumbnailAnimMatrix.setRectToRect(
                    thumbnailAnimSrcRect,
                    thumbnailAnimDstRect,
                    Matrix.ScaleToFit.FILL
                )
                //thumbnail_anim_matrix.reset();
                if (uiRotation == 90 || uiRotation == 270) {
                    val ratio =
                        (lastThumbnail!!.width.toFloat()) / lastThumbnail!!.height.toFloat()
                    thumbnailAnimMatrix.preScale(
                        ratio,
                        1.0f / ratio,
                        lastThumbnail!!.width / 2.0f,
                        lastThumbnail!!.height / 2.0f
                    )
                }
                thumbnailAnimMatrix.preRotate(
                    uiRotation.toFloat(),
                    lastThumbnail!!.width / 2.0f,
                    lastThumbnail!!.height / 2.0f
                )
                canvas.drawBitmap(lastThumbnail!!, thumbnailAnimMatrix, p)
            }
        }
    }

    private fun doFocusAnimation(canvas: Canvas, timeMs: Long) {
        val preview: Preview = mainActivity.preview
        val cameraController: CameraController? = preview.cameraController
        if (cameraController != null && continuousFocusMoving && !takingPicture) {
            // we don't display the continuous focusing animation when taking a photo - and can also give the impression of having
            // frozen if we pause because the image saver queue is full
            val dt = timeMs - continuousFocusMovingMs
            val length: Long = 1000
            /*if( MyDebug.LOG )
				Log.d(TAG, "continuous focus moving, dt: " + dt);*/
            if (dt <= length) {
                val frac = (dt.toFloat()) / length.toFloat()
                val posX = canvas.width / 2.0f
                val posY = canvas.height / 2.0f
                val minRadius = (40 * scaleDp + 0.5f) // convert dps to pixels
                val maxRadius = (60 * scaleDp + 0.5f) // convert dps to pixels
                val radius: Float
                if (frac < 0.5f) {
                    val alpha = frac * 2.0f
                    radius = (1.0f - alpha) * minRadius + alpha * maxRadius
                } else {
                    val alpha = (frac - 0.5f) * 2.0f
                    radius = (1.0f - alpha) * maxRadius + alpha * minRadius
                }
                /*if( MyDebug.LOG ) {
					Log.d(TAG, "dt: " + dt);
					Log.d(TAG, "radius: " + radius);
				}*/
                p.color = Color.WHITE
                p.style = Paint.Style.STROKE
                p.strokeWidth = strokeWidth
                canvas.drawCircle(posX, posY, radius, p)
                p.style = Paint.Style.FILL // reset
            } else {
                clearContinuousFocusMove()
            }
        }

        if (preview.isFocusWaiting || preview.isFocusRecentSuccess || preview.isFocusRecentFailure) {
            val timeSinceFocusStarted: Long = preview.timeSinceStartedAutoFocus()
            val minRadius = (40 * scaleDp + 0.5f) // convert dps to pixels
            val maxRadius = (45 * scaleDp + 0.5f) // convert dps to pixels
            var radius = minRadius
            if (timeSinceFocusStarted > 0) {
                val length: Long = 500
                var frac = (timeSinceFocusStarted.toFloat()) / length.toFloat()
                if (frac > 1.0f) frac = 1.0f
                if (frac < 0.5f) {
                    val alpha = frac * 2.0f
                    radius = (1.0f - alpha) * minRadius + alpha * maxRadius
                } else {
                    val alpha = (frac - 0.5f) * 2.0f
                    radius = (1.0f - alpha) * maxRadius + alpha * minRadius
                }
            }
            val size = radius.toInt()

            if (preview.isFocusRecentSuccess) p.color = Color.rgb(20, 231, 21) // Green A400
            else if (preview.isFocusRecentFailure) p.color =
                Color.rgb(244, 67, 54) // Red 500
            else p.color = Color.WHITE
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokeWidth
            val posX: Int
            val posY: Int
            if (preview.hasFocusArea()) {
                val focusPos: Pair<Int, Int> = preview.focusPos
                posX = focusPos.first
                posY = focusPos.second
            } else {
                posX = canvas.width / 2
                posY = canvas.height / 2
            }
            val frac = 0.5f
            // horizontal strokes
            canvas.drawLine(
                (posX - size).toFloat(),
                (posY - size).toFloat(),
                posX - frac * size,
                (posY - size).toFloat(),
                p
            )
            canvas.drawLine(
                posX + frac * size,
                (posY - size).toFloat(),
                (posX + size).toFloat(),
                (posY - size).toFloat(),
                p
            )
            canvas.drawLine(
                (posX - size).toFloat(),
                (posY + size).toFloat(),
                posX - frac * size,
                (posY + size).toFloat(),
                p
            )
            canvas.drawLine(
                posX + frac * size,
                (posY + size).toFloat(),
                (posX + size).toFloat(),
                (posY + size).toFloat(),
                p
            )
            // vertical strokes
            canvas.drawLine(
                (posX - size).toFloat(),
                (posY - size).toFloat(),
                (posX - size).toFloat(),
                posY - frac * size,
                p
            )
            canvas.drawLine(
                (posX - size).toFloat(),
                posY + frac * size,
                (posX - size).toFloat(),
                (posY + size).toFloat(),
                p
            )
            canvas.drawLine(
                (posX + size).toFloat(),
                (posY - size).toFloat(),
                (posX + size).toFloat(),
                posY - frac * size,
                p
            )
            canvas.drawLine(
                (posX + size).toFloat(),
                posY + frac * size,
                (posX + size).toFloat(),
                (posY + size).toFloat(),
                p
            )
            p.style = Paint.Style.FILL // reset
        }
    }

    fun setCoverPreview(coverPreview: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setCoverPreview: $coverPreview"
        )
        this.coverPreview = coverPreview
    }

    fun setDimPreview(on: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "setDimPreview: $on")
        if (on) {
            this.dimPreview = DimPreview.DIM_PREVIEW_ON
        } else if (this.dimPreview == DimPreview.DIM_PREVIEW_ON) {
            this.dimPreview = DimPreview.DIM_PREVIEW_UNTIL
        }
    }

    fun clearDimPreview() {
        this.dimPreview = DimPreview.DIM_PREVIEW_OFF
    }

    fun onDrawPreview(canvas: Canvas) {
        /*if( MyDebug.LOG )
			Log.d(TAG, "onDrawPreview");*/
        /*if( MyDebug.LOG )
			Log.d(TAG, "onDrawPreview hardware accelerated: " + canvas.isHardwareAccelerated());*/

        val timeMs = System.currentTimeMillis()

        if (!hasSettings) {
            if (MyDebug.LOG) Log.d(TAG, "onDrawPreview: need to update settings")
            updateSettings()
        }
        val preview: Preview = mainActivity.preview
        val cameraController: CameraController? = preview.cameraController
        val uiRotation: Int = preview.uIRotation

        // set up preview bitmaps (histogram etc.)
        val wantPreviewBitmap =
            wantHistogram || wantZebraStripes || wantFocusPeaking || wantPreShots
        val usePreviewBitmapSmall = wantHistogram || wantZebraStripes || wantFocusPeaking
        val usePreviewBitmapFull = wantPreShots
        if (wantPreviewBitmap != preview.isPreviewBitmapEnabled || usePreviewBitmapSmall != preview.usePreviewBitmapSmall() || usePreviewBitmapFull != preview.usePreviewBitmapFull()) {
            if (wantPreviewBitmap) {
                preview.enablePreviewBitmap(usePreviewBitmapSmall, usePreviewBitmapFull)
            } else preview.disablePreviewBitmap()
        }
        if (wantPreviewBitmap) {
            if (wantHistogram) preview.enableHistogram(histogramType)
            else preview.disableHistogram()

            if (wantZebraStripes) preview.enableZebraStripes(
                zebraStripesThreshold,
                zebraStripesColorForeground,
                zebraStripesColorBackground
            )
            else preview.disableZebraStripes()

            if (wantFocusPeaking) preview.enableFocusPeaking()
            else preview.disableFocusPeaking()

            if (wantPreShots) preview.enablePreShots()
            else preview.disablePreShots()
        }

        // See documentation for CameraController.shouldCoverPreview().
        // Note, originally we checked camera_controller.shouldCoverPreview() every frame, but this
        // has the problem that we blank whenever the camera is being reopened, e.g., when switching
        // cameras or changing photo modes that require a reopen. The intent however is to only
        // cover up the camera when the application is pausing, and to keep it covered up until
        // after we've resumed, and the camera has been reopened, and we've received frames.
        if (preview.usingCamera2API()) {
            val cameraIsActive =
                cameraController != null && !cameraController.shouldCoverPreview()
            if (coverPreview) {
                // see if we have received a frame yet
                if (cameraIsActive) {
                    if (MyDebug.LOG) Log.d(TAG, "no longer need to cover preview")
                    coverPreview = false
                }
            }
            if (coverPreview) {
                // camera has never been active since last resuming
                p.color = Color.BLACK
                //p.setColor(Color.RED); // test
                canvas.drawRect(0.0f, 0.0f, canvas.width.toFloat(), canvas.height.toFloat(), p)
            } else if (dimPreview == DimPreview.DIM_PREVIEW_ON || (!cameraIsActive && dimPreview == DimPreview.DIM_PREVIEW_UNTIL)) {
                val timeNow = System.currentTimeMillis()
                if (cameraInactiveTimeMs == -1L) {
                    cameraInactiveTimeMs = timeNow
                }
                var frac = ((timeNow - cameraInactiveTimeMs) / dimEffectTimeC.toFloat())
                frac = min(frac.toDouble(), 1.0).toFloat()
                val alpha = (frac * 127).toInt()
                /*if( MyDebug.LOG ) {
                    Log.d(TAG, "time diff: " + (timeNow - cameraInactiveTimeMs));
                    Log.d(TAG, "    frac: " + frac);
                    Log.d(TAG, "    alpha: " + alpha);
                }*/
                p.color = Color.BLACK
                p.alpha = alpha
                canvas.drawRect(0.0f, 0.0f, canvas.width.toFloat(), canvas.height.toFloat(), p)
                p.alpha = 255
            } else {
                cameraInactiveTimeMs = -1
                if (dimPreview == DimPreview.DIM_PREVIEW_UNTIL && cameraIsActive) {
                    dimPreview = DimPreview.DIM_PREVIEW_OFF
                }
            }
        }

        if (cameraController != null && frontScreenFlash) {
            p.color = Color.WHITE
            canvas.drawRect(0.0f, 0.0f, canvas.width.toFloat(), canvas.height.toFloat(), p)
        } else if ("flash_frontscreen_torch" == preview.currentFlashValue) { // getCurrentFlashValue() may return null
            p.color = Color.WHITE
            p.alpha = 200 // set alpha so user can still see some of the preview
            canvas.drawRect(0.0f, 0.0f, canvas.width.toFloat(), canvas.height.toFloat(), p)
            p.alpha = 255
        }

        if (mainActivity.mainUI.inImmersiveMode()) {
            if (immersiveModeEverythingPref) {
                // exit, to ensure we don't display anything!
                // though note we still should do the front screen flash (since the user can take photos via volume keys when
                // in immersiveModeEverything mode)
                return
            }
        }

        // If MainActivity.lockToLandscape==true, then the uiRotation represents the orientation of the
        // device; if MainActivity.lockToLandscape==false then uiRotation is always 0 as we don't need to
        // apply any orientation ourselves. However, we're we do want to know the true rotation of the
        // device, as it affects how certain elements of the UI are layed out.
        val deviceUiRotation: Int
        if (MainActivity.LOCK_TO_LANDSCAPE) {
            deviceUiRotation = uiRotation
        } else {
            val systemOrientation: SystemOrientation = mainActivity.systemOrientation
            deviceUiRotation = MainActivity.getRotationFromSystemOrientation(systemOrientation)
        }

        if (cameraController != null && takingPicture && !frontScreenFlash && takePhotoBorderPref) {
            p.color = Color.WHITE
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokeWidth
            val thisStrokeWidth = (5.0f * scaleDp + 0.5f) // convert dps to pixels
            p.strokeWidth = thisStrokeWidth
            canvas.drawRect(0.0f, 0.0f, canvas.width.toFloat(), canvas.height.toFloat(), p)
            p.style = Paint.Style.FILL // reset
            p.strokeWidth = strokeWidth // reset
        }
        hasAutoStabiliseCrop = false
        if (cameraController != null && !preview.isPreviewPaused && storedAutoStabilisePref && preview.hasLevelAngleStable() && !preview.isVideo) {
            var autoStabiliseLevelAngle = preview.origLevelAngle
            while (autoStabiliseLevelAngle < -90) autoStabiliseLevelAngle += 180.0
            while (autoStabiliseLevelAngle > 90) autoStabiliseLevelAngle -= 180.0
            val levelAngleRadAbs = abs(Math.toRadians(autoStabiliseLevelAngle))

            val w1 = canvas.width
            val h1 = canvas.height
            val w0 = (w1 * cos(levelAngleRadAbs) + h1 * sin(levelAngleRadAbs))
            val h0 = (w1 * sin(levelAngleRadAbs) + h1 * cos(levelAngleRadAbs))

            if (PostProcessing.autoStabiliseCrop(
                    autoStabiliseCrop,
                    levelAngleRadAbs,
                    w0,
                    h0,
                    w1,
                    h1,
                    canvas.width,
                    canvas.height
                )
            ) {
                hasAutoStabiliseCrop = true
            }
        }

        drawGrids(canvas)

        drawCropGuides(canvas)

        // n.b., don't display ghost image if frontScreenFlash==true (i.e., frontscreen flash is in operation), otherwise
        // the effectiveness of the "flash" is reduced
        if (lastThumbnail != null && !lastThumbnailIsVideo && cameraController != null && (showLastImage || (allowGhostLastImage && !frontScreenFlash && ghostImagePref == "preference_ghost_image_last"))) {
            // If changing this code, ensure that pause preview still works when:
            // - Taking a photo in portrait or landscape - and check rotating the device while preview paused
            // - Taking a photo with lock to portrait/landscape options still shows the thumbnail with aspect ratio preserved
            // Also check ghost last image works okay!
            if (showLastImage) {
                p.color = Color.rgb(
                    0,
                    0,
                    0
                ) // in case image doesn't cover the canvas (due to different aspect ratios)
                canvas.drawRect(
                    0.0f,
                    0.0f,
                    canvas.width.toFloat(),
                    canvas.height.toFloat(),
                    p
                ) // in case
            }
            setLastImageMatrix(canvas, lastThumbnail!!, uiRotation, !showLastImage)
            if (!showLastImage) p.alpha = ghostImageAlpha
            canvas.drawBitmap(lastThumbnail!!, lastImageMatrix, p)
            if (!showLastImage) p.alpha = 255
        } else if (cameraController != null && !frontScreenFlash && ghostSelectedImageBitmap != null) {
            setLastImageMatrix(canvas, ghostSelectedImageBitmap!!, uiRotation, true)
            p.alpha = ghostImageAlpha
            canvas.drawBitmap(ghostSelectedImageBitmap!!, lastImageMatrix, p)
            p.alpha = 255
        }

        if (preview.isPreviewBitmapEnabled && !showLastImage) {
            // draw additional real-time effects

            // draw zebra stripes

            val zebraStripesBitmap: Bitmap? = preview.zebraStripesBitmap
            if (zebraStripesBitmap != null) {
                setLastImageMatrix(canvas, zebraStripesBitmap, 0, false)
                p.alpha = 255
                canvas.drawBitmap(zebraStripesBitmap, lastImageMatrix, p)
            }

            // draw focus peaking
            val focusPeakingBitmap: Bitmap? = preview.focusPeakingBitmap
            if (focusPeakingBitmap != null) {
                setLastImageMatrix(canvas, focusPeakingBitmap, 0, false)
                p.alpha = 127
                if (focusPeakingColorPref != Color.WHITE) {
                    p.colorFilter = PorterDuffColorFilter(
                        focusPeakingColorPref,
                        PorterDuff.Mode.SRC_IN
                    )
                }
                canvas.drawBitmap(focusPeakingBitmap, lastImageMatrix, p)
                if (focusPeakingColorPref != Color.WHITE) {
                    p.colorFilter = null
                }
                p.alpha = 255
            }
        }

        doThumbnailAnimation(canvas, timeMs)

        drawUI(canvas, deviceUiRotation, timeMs)

        drawAngleLines(canvas, deviceUiRotation, timeMs)

        doFocusAnimation(canvas, timeMs)

        val facesDetected: Array<CameraController.Face>? = preview.facesDetected
        if (facesDetected != null) {
            p.color = Color.rgb(255, 235, 59) // Yellow 500
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokeWidth
            for (face in facesDetected) {
                // Android doc recommends filtering out faces with score less than 50 (same for both Camera and Camera2 APIs)
                if (face.score >= 50) {
                    canvas.drawRect(face.temp, p)
                }
            }
            p.style = Paint.Style.FILL // reset
        }

        if (enableGyroTargetSpot && cameraController != null) {
            val gyroSensor: GyroSensor = mainActivity.applicationInterface.gyroSensor
            if (gyroSensor.isRecording) {
                val systemOrientation: SystemOrientation = mainActivity.systemOrientation
                val systemOrientationPortrait =
                    systemOrientation === SystemOrientation.PORTRAIT
                for (gyroDirection in gyroDirections) {
                    gyroSensor.getRelativeInverseVector(transformedGyroDirection, gyroDirection)
                    gyroSensor.getRelativeInverseVector(
                        transformedGyroDirectionUp,
                        gyroDirectionUp
                    )
                    // note that although X of gyroDirection represents left to right on the device, because we're in landscape mode,
                    // this is y coordinates on the screen
                    val angleX: Float
                    val angleY: Float
                    if (systemOrientationPortrait) {
                        angleX = asin(transformedGyroDirection[0].toDouble()).toFloat()
                        angleY = -asin(transformedGyroDirection[1].toDouble()).toFloat()
                    } else {
                        angleX = -asin(transformedGyroDirection[1].toDouble()).toFloat()
                        angleY = -asin(transformedGyroDirection[0].toDouble()).toFloat()
                    }
                    if (abs(angleX.toDouble()) < 0.5f * Math.PI && abs(angleY.toDouble()) < 0.5f * Math.PI) {
                        updateCachedViewAngles(timeMs) // ensure viewAngleXPreview, viewAngleYPreview are computed and up to date
                        val cameraAngleX: Float
                        val cameraAngleY: Float
                        if (systemOrientationPortrait) {
                            cameraAngleX = this.viewAngleYPreview
                            cameraAngleY = this.viewAngleXPreview
                        } else {
                            cameraAngleX = this.viewAngleXPreview
                            cameraAngleY = this.viewAngleYPreview
                        }
                        var angleScaleX =
                            (canvas.width / (2.0 * tan(Math.toRadians((cameraAngleX / 2.0))))).toFloat()
                        var angleScaleY =
                            (canvas.height / (2.0 * tan(Math.toRadians((cameraAngleY / 2.0))))).toFloat()
                        angleScaleX *= preview.zoomRatio
                        angleScaleY *= preview.zoomRatio
                        val distanceX =
                            angleScaleX * tan(angleX.toDouble()).toFloat() // angleScale is already in pixels rather than dps
                        val distanceY =
                            angleScaleY * tan(angleY.toDouble()).toFloat() // angleScale is already in pixels rather than dps
                        p.color = Color.WHITE
                        drawGyroSpot(
                            canvas,
                            0.0f,
                            0.0f,
                            -1.0f,
                            0.0f,
                            48,
                            true
                        ) // draw spot for the center of the screen, to help the user orient the device
                        p.color = Color.BLUE
                        val dirX = -transformedGyroDirectionUp[1]
                        val dirY = -transformedGyroDirectionUp[0]
                        drawGyroSpot(canvas, distanceX, distanceY, dirX, dirY, 45, false)
                        /*{
						// for debug only, draw the gyro spot that isn't calibrated with the accelerometer
						gyroSensor.getRelativeInverseVectorGyroOnly(transformedGyroDirection, gyroDirection);
						gyroSensor.getRelativeInverseVectorGyroOnly(transformedGyroDirectionUp, gyroDirectionUp);
						p.setColor(Color.YELLOW);
						angleX = - (float)Math.asin(transformedGyroDirection[1]);
						angleY = - (float)Math.asin(transformedGyroDirection[0]);
						distanceX = angleScaleX * (float) Math.tan(angleX); // angleScale is already in pixels rather than dps
						distanceY = angleScaleY * (float) Math.tan(angleY); // angleScale is already in pixels rather than dps
						dirX = -transformedGyroDirectionUp[1];
						dirY = -transformedGyroDirectionUp[0];
						drawGyroSpot(canvas, distanceX, distanceY, dirX, dirY, 45);
					}*/
                    }

                    // show indicator for not being "upright", but only if tilt angle is within 20 degrees
                    if (gyroSensor.isUpright != 0 && abs(angleX.toDouble()) <= 20.0f * 0.0174532925199f) {
                        //applicationInterface.drawTextWithBackground(canvas, p, "not upright", Color.WHITE, Color.BLACK, canvas.getWidth()/2, canvas.getHeight()/2, MyApplicationInterface.Alignment.ALIGNMENT_CENTRE, null, true);
                        canvas.save()
                        canvas.rotate(
                            uiRotation.toFloat(),
                            canvas.width / 2.0f,
                            canvas.height / 2.0f
                        )
                        val iconSize = (64 * scaleDp + 0.5f).toInt() // convert dps to pixels
                        val cyOffset = (80 * scaleDp + 0.5f).toInt() // convert dps to pixels
                        val cx = canvas.width / 2
                        val cy = canvas.height / 2 - cyOffset
                        iconDest[cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2] =
                            cy + iconSize / 2
                        /*p.setStyle(Paint.Style.FILL);
					p.setColor(Color.BLACK);
					p.setAlpha(64);
					canvas.drawRect(iconDest, p);
					p.setAlpha(255);*/
                        canvas.drawBitmap(
                            (if (gyroSensor.isUpright > 0) rotateLeftBitmap else rotateRightBitmap)!!,
                            null,
                            iconDest,
                            p
                        )
                        canvas.restore()
                    }
                }
            }
        }

        if (timeMs > lastUpdateFocusSeekbarAutoTime + 100) {
            lastUpdateFocusSeekbarAutoTime = timeMs

            if (cameraController != null && photoMode === PhotoMode.FocusBracketing && applicationInterface.isFocusBracketingSourceAutoPref()) {
                // not strictly related to drawing on the preview, but a convenient place to do this
                // also need to wait some time after getSettingTargetFocusDistanceTime(), as when user stops changing target seekbar, it takes time to return to
                // continuous focus
                if (!mainActivity.preview
                        .isSettingTargetFocusDistance && timeMs > mainActivity.preview
                        .settingTargetFocusDistanceTime + 500 &&
                    cameraController.captureResultHasFocusDistance()
                ) {
                    mainActivity.setManualFocusSeekbarProgress(
                        false,
                        cameraController.captureResultFocusDistance()
                    )
                }
            }
        }

        /*if( MyDebug.LOG ) {
            long timeTaken = System.currentTimeMillis() - timeMs;
            Log.d(TAG, "onDrawPreview time: " + timeTaken);
        }*/
    }

    private fun setLastImageMatrix(
        canvas: Canvas,
        bitmap: Bitmap,
        thisUiRotation: Int,
        flipFront: Boolean
    ) {
        val preview: Preview = mainActivity.preview
        val cameraController: CameraController? = preview.cameraController
        lastImageSrcRect.left = 0f
        lastImageSrcRect.top = 0f
        lastImageSrcRect.right = bitmap.width.toFloat()
        lastImageSrcRect.bottom = bitmap.height.toFloat()
        if (thisUiRotation == 90 || thisUiRotation == 270) {
            lastImageSrcRect.right = bitmap.height.toFloat()
            lastImageSrcRect.bottom = bitmap.width.toFloat()
        }
        lastImageDstRect.left = 0f
        lastImageDstRect.top = 0f
        lastImageDstRect.right = canvas.width.toFloat()
        lastImageDstRect.bottom = canvas.height.toFloat()
        /*if( MyDebug.LOG ) {
			Log.d(TAG, "thumbnail: " + bitmap.getWidth() + " x " + bitmap.getHeight());
			Log.d(TAG, "canvas: " + canvas.getWidth() + " x " + canvas.getHeight());
		}*/
        lastImageMatrix.setRectToRect(
            lastImageSrcRect,
            lastImageDstRect,
            Matrix.ScaleToFit.CENTER
        ) // use CENTER to preserve aspect ratio
        if (thisUiRotation == 90 || thisUiRotation == 270) {
            // the rotation maps (0, 0) to (tw/2 - th/2, th/2 - tw/2), so we translate to undo this
            val diff = (bitmap.height - bitmap.width).toFloat()
            lastImageMatrix.preTranslate(diff / 2.0f, -diff / 2.0f)
        }
        lastImageMatrix.preRotate(
            thisUiRotation.toFloat(),
            bitmap.width / 2.0f,
            bitmap.height / 2.0f
        )
        if (flipFront) {
            val isFrontFacing =
                cameraController != null && (cameraController.facing === CameraController.Facing.FACING_FRONT)
            if (isFrontFacing && sharedPreferences.getString(
                    PreferenceKeys.FRONT_CAMERA_MIRROR_KEY,
                    "preference_front_camera_mirror_no"
                ) != "preference_front_camera_mirror_photo"
            ) {
                lastImageMatrix.preScale(-1.0f, 1.0f, bitmap.width / 2.0f, 0.0f)
            }
        }
    }

    private fun drawGyroSpot(
        canvas: Canvas,
        distanceX: Float,
        distanceY: Float,
        dirX: Float,
        dirY: Float,
        radiusDp: Int,
        outline: Boolean
    ) {
        if (outline) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = strokeWidth
            p.alpha = 255
        } else {
            p.alpha = 127
        }
        val radius = (radiusDp * scaleDp + 0.5f) // convert dps to pixels
        var cx = canvas.width / 2.0f + distanceX
        var cy = canvas.height / 2.0f + distanceY

        // if gyro spots would be outside the field of view, it's still better to show them on the
        // border of the canvas, so the user knows which direction to move the device
        cx = max(cx.toDouble(), 0.0).toFloat()
        cx = min(cx.toDouble(), canvas.width.toDouble()).toFloat()
        cy = max(cy.toDouble(), 0.0).toFloat()
        cy = min(cy.toDouble(), canvas.height.toDouble()).toFloat()

        canvas.drawCircle(cx, cy, radius, p)
        p.alpha = 255
        p.style = Paint.Style.FILL // reset

        // draw crosshairs
        //p.setColor(Color.WHITE);
        /*p.setStrokeWidth(strokeWidth);
        canvas.drawLine(cx - radius*dirX, cy - radius*dirY, cx + radius*dirX, cy + radius*dirY, p);
        canvas.drawLine(cx - radius*dirY, cy + radius*dirX, cx + radius*dirY, cy - radius*dirX, p);*/
    }

    /**
     * A generic method to display up to two lines on the preview.
     * Currently used by the Kraken underwater housing sensor to display
     * temperature and depth.
     *
     * The two lines are displayed in the lower left corner of the screen.
     *
     * @param line1 First line to display
     * @param line2 Second line to display
     */
    fun onExtraOSDValuesChanged(line1: String, line2: String) {
        varOSDLine1 = line1
        varOSDLine2 = line2
    }

    // for testing:

    fun getStoredHasStampPref(): Boolean {
        return this.storedHasStampPref
    }

    fun getStoredAutoStabilisePref(): Boolean {
        return this.storedAutoStabilisePref
    }

    companion object {
        private const val TAG = "DrawPreview"

        // Time for the dimming effect. This should be quick, because we call Preview.setupCamera() on
        // the UI thread, which will block redraws:
        // - When reopening the camera, we want the dimming to have occurred whilst reopening the
        //   camera, before we call setupCamera() on the UI thread.
        // - When pausing the preview in MainActivity.updateForSettings(), we call setupCamera() after
        //   this delay - so we don't want to keep the user waiting too long.
        const val dimEffectTimeC: Long = 50

        private val decimalFormat = DecimalFormat("#0.0")
        private const val closeLevelAngle = 1.0
        private const val histogramWidthDp = 100
        private const val histogramHeightDp = 60

        private const val cropShadingAlphaC =
            160 // alpha to use for shading areas not of interest

        /** Formats the levelAngle double into a string.
         * Beware of calling this too often - shouldn't be every frame due to performance of DecimalFormat
         * (see http://stackoverflow.com/questions/8553672/a-faster-alternative-to-decimalformat-format ).
         */
        fun formatLevelAngle(levelAngle: Double): String {
            var numberString = decimalFormat.format(levelAngle)
            if (abs(levelAngle) < 0.1) {
                // avoids displaying "-0.0", see http://stackoverflow.com/questions/11929096/negative-sign-in-case-of-zero-in-java
                // only do this when levelAngle is small, to help performance
                numberString = numberString.replace("^-(?=0(.0*)?$)".toRegex(), "")
            }
            return numberString
        }
    }
}