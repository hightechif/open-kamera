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
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.SystemOrientation
import com.hightechif.openkamera.MyApplicationInterface
import com.hightechif.openkamera.MyApplicationInterface.Alignment
import com.hightechif.openkamera.MyApplicationInterface.PhotoMode
import com.hightechif.openkamera.MyApplicationInterface.Shadow
import com.hightechif.openkamera.R
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.ApplicationInterface
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.preview.analysis.HistogramType
import com.hightechif.openkamera.sensors.LocationSupplier
import com.hightechif.openkamera.ui.renderers.CropGuideOverlayRenderer
import com.hightechif.openkamera.ui.renderers.DrawPreviewContext
import com.hightechif.openkamera.ui.renderers.EffectOverlayRenderer
import com.hightechif.openkamera.ui.renderers.FocusFaceOverlayRenderer
import com.hightechif.openkamera.ui.renderers.GridOverlayRenderer
import com.hightechif.openkamera.ui.renderers.GyroTargetOverlayRenderer
import com.hightechif.openkamera.ui.renderers.HistogramOverlayRenderer
import com.hightechif.openkamera.ui.renderers.HorizonAngleOverlayRenderer
import com.hightechif.openkamera.ui.renderers.TelemetryHudOverlayRenderer
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
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import com.hightechif.openkamera.LOCK_TO_LANDSCAPE
import com.hightechif.openkamera.getRotationFromSystemOrientation

class DrawPreview(mainActivity: MainActivity, applicationInterface: MyApplicationInterface) {
    private val mainActivity: MainActivity
    private val applicationInterface: MyApplicationInterface

    // Modular sub-renderers
    val gridOverlayRenderer: GridOverlayRenderer
    val cropGuideOverlayRenderer: CropGuideOverlayRenderer
    val horizonAngleOverlayRenderer: HorizonAngleOverlayRenderer
    val gyroTargetOverlayRenderer: GyroTargetOverlayRenderer
    val telemetryHudRenderer: TelemetryHudOverlayRenderer
    val focusFaceOverlayRenderer: FocusFaceOverlayRenderer
    val effectOverlayRenderer: EffectOverlayRenderer
    val histogramOverlayRenderer: HistogramOverlayRenderer
    val drawPreviewContext: DrawPreviewContext

    var hudOverlayState: HudOverlayState = HudOverlayState()
        private set

    fun updateHudOverlayState(state: HudOverlayState) {
        this.hudOverlayState = state
        drawPreviewContext.hudOverlayState = state
    }

    fun updateFromUiState(uiState: CameraUiState) {
        val overlay = HudOverlayState(
            gridType = uiState.gridType,
            isImmersiveMode = mainActivity.mainUI.inImmersiveMode(),
            showAngle = showAnglePref,
            showAngleLine = showAngleLinePref,
            showPitchLines = showPitchLinesPref,
            showGeoDirection = showGeoDirectionPref,
            showGeoDirectionLines = showGeoDirectionLinesPref,
            horizonAngle = uiState.horizonAngle?.angleDegrees ?: (if (mainActivity.preview.hasLevelAngle()) mainActivity.preview.levelAngle else 0.0),
            pitchAngle = if (mainActivity.preview.hasPitchAngle()) mainActivity.preview.pitchAngle else 0.0,
            compassDegrees = uiState.compassDegrees.toDouble(),
            isLevel = uiState.horizonAngle?.isLevel ?: (abs(mainActivity.preview.levelAngle) <= CLOSE_LEVEL_ANGLE),
            angleHighlightColor = angleHighlightColorPref,
            showIso = showIsoPref,
            iso = uiState.frameMetadata?.iso ?: 0,
            exposureTimeNs = uiState.frameMetadata?.exposureTimeNs ?: 0L,
            showBattery = showBatteryPref,
            showFreeMemory = showFreeMemoryPref,
            showTime = showTimePref,
            showCameraId = showCameraIdPref,
            isRecordingVideo = uiState.isRecording,
            flashMode = uiState.flashMode,
            isRawEnabled = uiState.isRawEnabled,
            focusState = uiState.focusState,
            timerCountdownSeconds = uiState.timerSecondsRemaining
        )
        updateHudOverlayState(overlay)
    }

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
    private val yboundsText: String
    private val autoStabiliseCrop = IntArray(2)
    private var hasAutoStabiliseCrop = false

    //private final DecimalFormat decimalFormat1dpForce0 = new DecimalFormat("0.0");
    // cached Rects for drawTextWithBackground() calls
    private var textBoundsAngleSingle: Rect? = null
    private var textBoundsAngleDouble: Rect? = null

    private lateinit var angleString: String // cached for UI performance
    private var cachedAngle = 0.0 // the angle that we used for the cached angleString
    private var lastAngleStringTime: Long = 0

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

        gridOverlayRenderer = GridOverlayRenderer()
        cropGuideOverlayRenderer = CropGuideOverlayRenderer()
        horizonAngleOverlayRenderer = HorizonAngleOverlayRenderer()
        gyroTargetOverlayRenderer = GyroTargetOverlayRenderer(mainActivity)
        telemetryHudRenderer = TelemetryHudOverlayRenderer(mainActivity, applicationInterface)
        focusFaceOverlayRenderer = FocusFaceOverlayRenderer()
        effectOverlayRenderer = EffectOverlayRenderer()
        histogramOverlayRenderer = HistogramOverlayRenderer()

        drawPreviewContext = DrawPreviewContext(
            mainActivity = mainActivity,
            applicationInterface = applicationInterface,
            sharedPreferences = sharedPreferences,
            scaleDp = scaleDp,
            scaleFont = scaleFont,
            strokeWidth = strokeWidth
        )

        yboundsText =
            context.resources.getString(R.string.zoom) + context.resources.getString(R.string.angle) + context.resources.getString(
                R.string.direction
            )
    }

    fun onDestroy() {
        if (MyDebug.LOG) Log.d(TAG, "on_destroy")
        if (ghostSelectedImageBitmap != null) {
            ghostSelectedImageBitmap!!.recycle()
            ghostSelectedImageBitmap = null
        }
        ghostSelectedImagePref = ""

        gridOverlayRenderer.onDestroy()
        cropGuideOverlayRenderer.onDestroy()
        horizonAngleOverlayRenderer.onDestroy()
        telemetryHudRenderer.onDestroy()
        focusFaceOverlayRenderer.onDestroy()
        effectOverlayRenderer.onDestroy()
        histogramOverlayRenderer.onDestroy()
        gyroTargetOverlayRenderer.onDestroy()
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
        effectOverlayRenderer.setLastThumbnail(thumbnail)
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
        effectOverlayRenderer.showLastImage()
    }

    fun clearLastImage() {
        if (MyDebug.LOG) Log.d(TAG, "clearLastImage")
        this.showLastImage = false
        effectOverlayRenderer.clearLastImage()
    }

    fun allowGhostImage() {
        if (MyDebug.LOG) Log.d(TAG, "allowGhostImage")
        if (lastThumbnail != null) this.allowGhostLastImage = true
        effectOverlayRenderer.allowGhostImage()
    }

    fun clearGhostImage() {
        if (MyDebug.LOG) Log.d(TAG, "clearGhostImage")
        this.allowGhostLastImage = false
        effectOverlayRenderer.clearGhostImage()
    }

    fun cameraInOperation(inOperation: Boolean) {
        if (inOperation && !mainActivity.preview.isVideo) {
            takingPicture = true
        } else {
            takingPicture = false
            frontScreenFlash = false
            captureStarted = false
        }
        focusFaceOverlayRenderer.setTakingPicture(takingPicture)
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
        focusFaceOverlayRenderer.onContinuousFocusMove(start)
    }

    fun clearContinuousFocusMove() {
        if (MyDebug.LOG) Log.d(TAG, "clearContinuousFocusMove")
        if (continuousFocusMoving) {
            continuousFocusMoving = false
            continuousFocusMovingMs = 0
        }
        focusFaceOverlayRenderer.clearContinuousFocusMove()
    }

    fun setGyroDirectionMarker(x: Float, y: Float, z: Float) {
        enableGyroTargetSpot = true
        gyroDirections.clear()
        addGyroDirectionMarker(x, y, z)
        gyroDirectionUp[0] = 0f
        gyroDirectionUp[1] = 1f
        gyroDirectionUp[2] = 0f
        gyroTargetOverlayRenderer.setGyroDirectionMarker(x, y, z)
    }

    fun addGyroDirectionMarker(x: Float, y: Float, z: Float) {
        val vector = floatArrayOf(x, y, z)
        gyroDirections.add(vector)
        gyroTargetOverlayRenderer.addGyroDirectionMarker(x, y, z)
    }

    fun clearGyroDirectionMarker() {
        enableGyroTargetSpot = false
        gyroTargetOverlayRenderer.clearGyroDirectionMarker()
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

        lastViewAnglesTime = 0 // force view angles to be recomputed
        lastTakePhotoTopTime = 0 // force takePhotoTop to be recomputed
        lastTopIconShiftTime = 0 // for topIconShift to be recomputed

        focusSeekbarsMarginLeft =
            -1 // needed as the focus seekbars can only be updated when visible

        gridOverlayRenderer.updateSettings()
        cropGuideOverlayRenderer.updateSettings()
        horizonAngleOverlayRenderer.updateSettings()
        telemetryHudRenderer.updateSettings()
        focusFaceOverlayRenderer.updateSettings()
        effectOverlayRenderer.updateSettings()
        effectOverlayRenderer.setFocusPeakingColor(focusPeakingColorPref)
        effectOverlayRenderer.setGhostImagePref(ghostImagePref ?: "preference_ghost_image_off")
        effectOverlayRenderer.setGhostImageAlpha(ghostImageAlpha)
        effectOverlayRenderer.setGhostSelectedImageBitmap(ghostSelectedImageBitmap)
        histogramOverlayRenderer.updateSettings()
        gyroTargetOverlayRenderer.updateSettings()

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

    /** This includes drawing of the UI that requires the canvas to be rotated according to the preview's
     * current UI rotation.
     */
    private fun drawCenterViewfinderText(canvas: Canvas, deviceUiRotation: Int, timeMs: Long) {
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
                if (abs(levelAngle) <= CLOSE_LEVEL_ANGLE) {
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

        canvas.restore()
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
                var frac = ((timeNow - cameraInactiveTimeMs) / DIM_EFFECT_TIME_C.toFloat())
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
        if (LOCK_TO_LANDSCAPE) {
            deviceUiRotation = uiRotation
        } else {
            val systemOrientation: SystemOrientation = mainActivity.systemOrientation
            deviceUiRotation = getRotationFromSystemOrientation(systemOrientation)
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

        drawPreviewContext.scaleDp = scaleDp
        drawPreviewContext.scaleFont = scaleFont
        drawPreviewContext.strokeWidth = strokeWidth
        drawPreviewContext.deviceUiRotation = deviceUiRotation
        drawPreviewContext.hasLevelAngle = preview.hasLevelAngle()
        drawPreviewContext.levelAngle = preview.levelAngle
        drawPreviewContext.naturalLevelAngle = preview.naturalLevelAngle
        drawPreviewContext.hasPitchAngle = preview.hasPitchAngle()
        drawPreviewContext.pitchAngle = preview.pitchAngle
        drawPreviewContext.hasGeoDirection = preview.hasGeoDirection()
        drawPreviewContext.geoDirection = preview.geoDirection
        drawPreviewContext.cameraInactiveTimeMs = cameraInactiveTimeMs
        drawPreviewContext.hasAutoStabiliseCrop = hasAutoStabiliseCrop
        drawPreviewContext.autoStabiliseCrop[0] = autoStabiliseCrop[0]
        drawPreviewContext.autoStabiliseCrop[1] = autoStabiliseCrop[1]
        drawPreviewContext.previewSizeWysiwygPref = previewSizeWysiwygPref

        gridOverlayRenderer.draw(canvas, drawPreviewContext, timeMs)

        cropGuideOverlayRenderer.draw(canvas, drawPreviewContext, timeMs)

        effectOverlayRenderer.draw(canvas, drawPreviewContext, timeMs)

        doThumbnailAnimation(canvas, timeMs)

        telemetryHudRenderer.draw(canvas, drawPreviewContext, timeMs)

        histogramOverlayRenderer.draw(canvas, drawPreviewContext, timeMs)

        drawCenterViewfinderText(canvas, deviceUiRotation, timeMs)

        horizonAngleOverlayRenderer.draw(canvas, drawPreviewContext, timeMs)

        focusFaceOverlayRenderer.draw(canvas, drawPreviewContext, timeMs)

        gyroTargetOverlayRenderer.draw(canvas, drawPreviewContext, timeMs)

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
        telemetryHudRenderer.setExtraOSDValues(line1, line2)
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
        const val DIM_EFFECT_TIME_C: Long = 50

        private val decimalFormat = DecimalFormat("#0.0")
        private const val CLOSE_LEVEL_ANGLE = 1.0
        private const val HISTOGRAM_WIDTH_DP = 100
        private const val HISTOGRAM_HEIGHT_DP = 60

        // alpha to use for shading areas not of interest
        private const val CROP_SHADING_ALPHA_C = 160

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