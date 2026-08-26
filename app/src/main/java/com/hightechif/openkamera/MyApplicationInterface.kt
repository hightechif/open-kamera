/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera

//import android.location.Address; // don't use until we have info for data privacy!
//import android.location.Geocoder; // don't use until we have info for data privacy!
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.CameraExtensionCharacteristics
import android.location.Location
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.preference.PreferenceManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Pair
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.cameracontroller.CameraController.Facing
import com.hightechif.openkamera.cameracontroller.RawImage
import com.hightechif.openkamera.preferences.PreferenceKeys
import com.hightechif.openkamera.preview.ApplicationInterface
import com.hightechif.openkamera.preview.ApplicationInterface.CameraResolutionConstraints
import com.hightechif.openkamera.preview.ApplicationInterface.NoFreeStorageException
import com.hightechif.openkamera.preview.ApplicationInterface.RawPref
import com.hightechif.openkamera.preview.ApplicationInterface.VideoMethod
import com.hightechif.openkamera.preview.BasicApplicationInterface
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.preview.VideoProfile
import com.hightechif.openkamera.processing.HDRProcessor
import com.hightechif.openkamera.processing.PanoramaProcessor
import com.hightechif.openkamera.domain.repository.ILocationRepository
import com.hightechif.openkamera.domain.repository.IMediaRepository
import com.hightechif.openkamera.domain.repository.ISensorRepository
import com.hightechif.openkamera.domain.repository.ISettingsRepository
import com.hightechif.openkamera.sensors.GyroSensor
import com.hightechif.openkamera.sensors.LocationSupplier
import com.hightechif.openkamera.storage.ImageSaver
import com.hightechif.openkamera.storage.StorageUtils
import com.hightechif.openkamera.ui.DrawPreview
import com.hightechif.openkamera.utils.MyDebug
import com.hightechif.openkamera.utils.TextFormatter
import com.hightechif.openkamera.utils.ToastBoxer
import java.io.File
import java.io.FileNotFoundException
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.Calendar
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Our implementation of ApplicationInterface, see there for details.
 */
class MyApplicationInterface internal constructor(
    mainActivity: MainActivity,
    savedInstanceState: Bundle?,
    val settingsRepository: ISettingsRepository? = null,
    val mediaRepository: IMediaRepository? = null,
    val locationRepository: ILocationRepository? = null,
    val sensorRepository: ISensorRepository? = null
) : BasicApplicationInterface() {
    // note, okay to change the order of enums in future versions, as getPhotoMode() does not rely on the order for the saved photo mode
    enum class PhotoMode {
        Standard,
        DRO,  // single image "fake" HDR
        HDR,  // HDR created from multiple (expo bracketing) images
        ExpoBracketing,  // take multiple expo bracketed images, without combining to a single image
        FocusBracketing,  // take multiple focus bracketed images, without combining to a single image
        FastBurst,
        NoiseReduction,
        Panorama,

        // camera vendor extensions:
        X_Auto,
        X_HDR,
        X_Night,
        X_Bokeh,
        X_Beauty
    }

    val mainActivity: MainActivity
    val locationSupplier: LocationSupplier
    val gyroSensor: GyroSensor
    val storageUtils: StorageUtils
    val drawPreview: DrawPreview
    val imageSaver: ImageSaver

    private var nCaptureImages =
        0 // how many calls to onPictureTaken() since the last call to onCaptureStarted()
    private var nCaptureImagesRaw =
        0 // how many calls to onRawPictureTaken() since the last call to onCaptureStarted()
    private var nPanoramaPics = 0
    private var panoramaPicAccepted =
        false // whether the last panorama picture was accepted, or else needs to be retaken
    private var panoramaDirLeftToRight =
        true // direction of panorama (set after we've captured two images)

    private var lastVideoFile: File? = null
    private var lastVideoFileUri: Uri? = null

    private val subtitleVideoTimer = Timer()
    private var subtitleVideoTimerTask: TimerTask? = null

    private val textBounds = Rect()
    private var usedFrontScreenFlash = false

    // store to avoid calling PreferenceManager.getDefaultSharedPreferences() repeatedly
    private val sharedPreferences: SharedPreferences

    private enum class LastImagesType {
        FILE,
        SAF,
        MEDIASTORE
    }

    private var lastImagesType =
        LastImagesType.FILE // whether the last images array are using File API, SAF or MediaStore

    /** This class keeps track of the images saved in this batch, for use with Pause Preview option, so we can share or trash images.
     */
    private class LastImage {
        val share: Boolean // one of the images in the list should have share set to true, to indicate which image to share
        val name: String?
        var uri: Uri? = null

        constructor(uri: Uri?, share: Boolean) {
            this.name = null
            this.uri = uri
            this.share = share
        }

        constructor(filename: String?, share: Boolean) {
            this.name = filename
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // previous to Android 7, we could just use a "file://" uri, but this is no longer supported on Android 7, and
                // results in a android.os.FileUriExposedException when trying to share!
                // see https://stackoverflow.com/questions/38200282/android-os-fileuriexposedexception-file-storage-emulated-0-test-txt-exposed
                // so instead we leave null for now, and set it from MyApplicationInterface.scannedFile().
                this.uri = null
            } else {
                this.uri = Uri.parse("file://" + this.name)
            }
            this.share = share
        }
    }

    private val lastImages: MutableList<LastImage> = ArrayList()

    private val photoDeleteToast: ToastBoxer = ToastBoxer()

    private var has_set_cameraId = false
    private var _cameraIdPref: Int = cameraId_default
    override fun getCameraIdPref(): Int = _cameraIdPref

    private var _cameraIdSPhysicalPref: String? = null
    override fun getCameraIdSPhysicalPref(): String? = _cameraIdSPhysicalPref

    /*if( MyDebug.LOG )
			Log.d(TAG, "nrMode: " + nrMode);*/
    var nRMode: String = nrModeDefault
    private var _aperturePref: Float = apertureDefault
    override fun getAperturePref(): Float = _aperturePref

    // camera properties that aren't saved even in the bundle; these should be initialised/reset in reset()
    private var zoomFactor =
        -1 // don't save zoom, as doing so tends to confuse users; other camera applications don't seem to save zoom when pause/resuming

    // for testing:
    @Volatile
    var testNVideosScanned: Int = 0

    @Volatile
    var testMaxMp: Int = 0

    /** Here we save states which aren't saved in preferences (we don't want them to be saved if the
     * application is restarted from scratch), but we do want to preserve if Android has to recreate
     * the application (e.g., configuration change, or it's destroyed while in background).
     */
    fun onSaveInstanceState(state: Bundle) {
        if (MyDebug.LOG) Log.d(TAG, "onSaveInstanceState")
        if (MyDebug.LOG) Log.d(TAG, "save cameraId: " + getCameraIdPref())
        state.putInt("cameraId", getCameraIdPref())
        if (MyDebug.LOG) Log.d(TAG, "save cameraIdSPhysical: " + getCameraIdSPhysicalPref())
        state.putString("cameraIdSPhysical", getCameraIdSPhysicalPref())
        if (MyDebug.LOG) Log.d(TAG, "save nr_mode: " + nRMode)
        state.putString("nr_mode", nRMode)
        if (MyDebug.LOG) Log.d(TAG, "save aperture: " + getAperturePref())
        state.putFloat("aperture", getAperturePref())
    }

    fun onDestroy() {
        if (MyDebug.LOG) Log.d(TAG, "on_destroy")
        if (drawPreview != null) {
            drawPreview.onDestroy()
        }
        if (imageSaver != null) {
            imageSaver.onDestroy()
        }
    }



    override val context: Context
        get() = mainActivity

    override fun useCamera2(): Boolean {
        if (mainActivity.supportsCamera2()) {
            val cameraApi = settingsRepository?.getStringPreference(
                PreferenceKeys.CAMERA_API_PREFERENCE_KEY,
                PreferenceKeys.CAMERA_API_PREFERENCE_DEFAULT
            ) ?: sharedPreferences.getString(
                PreferenceKeys.CAMERA_API_PREFERENCE_KEY,
                PreferenceKeys.CAMERA_API_PREFERENCE_DEFAULT
            )
            if ("preference_camera_api_camera2" == cameraApi) {
                return true
            }
        }
        return false
    }

    override fun getLocation(): Location? {
        val repoLocation = locationRepository?.getLastKnownLocation()
        if (repoLocation != null) {
            return Location("LocationRepository").apply {
                latitude = repoLocation.latitude
                longitude = repoLocation.longitude
                repoLocation.altitude?.let { altitude = it }
            }
        }
        return locationSupplier.location
    }

    /** If adding extra calls to this, consider whether explicit user permission is required, and whether
     * privacy policy or data privacy section  needs updating.
     * Returns null if location not available.
     */
    fun getLocation(locationInfo: LocationSupplier.LocationInfo?): Location? {
        return locationSupplier.getLocation(locationInfo)
    }

    override fun createOutputVideoMethod(): VideoMethod {
        if (isVideoCaptureIntent) {
            if (MyDebug.LOG) Log.d(TAG, "from video capture intent")
            val myExtras = mainActivity.intent.extras
            if (myExtras != null) {
                val intentUri = myExtras.getParcelable<Uri>(MediaStore.EXTRA_OUTPUT)
                if (intentUri != null) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "save to: $intentUri"
                    )
                    return VideoMethod.URI
                }
            }
            // if no EXTRA_OUTPUT, we should save to standard location, and will pass back the Uri of that location
            if (MyDebug.LOG) Log.d(TAG, "intent uri not specified")
            return if (MainActivity.useScopedStorage()) {
                // can't use file method with scoped storage
                VideoMethod.MEDIASTORE
            } else {
                // note that SAF URIs don't seem to work for calling applications (tested with Grabilla and "Photo Grabber Image From Video" (FreezeFrame)), so we use standard folder with non-SAF method
                VideoMethod.FILE
            }
        } else if (storageUtils.isUsingSAF) {
            return VideoMethod.SAF
        } else if (MainActivity.useScopedStorage()) {
            return VideoMethod.MEDIASTORE
        } else {
            return VideoMethod.FILE
        }
    }

    @Throws(IOException::class)
    override fun createOutputVideoFile(extension: String): File {
        return createOutputVideoFile(false, extension, Date())
    }

    @Throws(IOException::class)
    override fun createOutputVideoSAF(extension: String): Uri {
        return createOutputVideoSAF(false, extension, Date())
    }

    @Throws(IOException::class)
    override fun createOutputVideoMediaStore(extension: String?): Uri {
        return createOutputVideoMediaStore(false, extension, Date())
    }

    @Throws(IOException::class)
    fun createOutputVideoFile(isPreshot: Boolean, extension: String, date: Date?): File {
        lastVideoFile = storageUtils.createOutputMediaFile(
            if (isPreshot) StorageUtils.MEDIA_TYPE_PRESHOT else StorageUtils.MEDIA_TYPE_VIDEO,
            "",
            extension,
            date
        )
        return lastVideoFile!!
    }

    @Throws(IOException::class)
    fun createOutputVideoSAF(isPreshot: Boolean, extension: String, date: Date?): Uri {
        lastVideoFileUri = storageUtils.createOutputMediaFileSAF(
            if (isPreshot) StorageUtils.MEDIA_TYPE_PRESHOT else StorageUtils.MEDIA_TYPE_VIDEO,
            "",
            extension,
            date
        )
        return lastVideoFileUri!!
    }

    @Throws(IOException::class)
    fun createOutputVideoMediaStore(isPreshot: Boolean, extension: String?, date: Date?): Uri {
        val folder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            ) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val contentValues = ContentValues()
        val filename: String = storageUtils.createMediaFilename(
            if (isPreshot) StorageUtils.MEDIA_TYPE_PRESHOT else StorageUtils.MEDIA_TYPE_VIDEO,
            "",
            0,
            ".$extension",
            date
        )
        if (MyDebug.LOG) Log.d(
            TAG,
            "filename: $filename"
        )
        contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, filename)
        val mimeType: String = storageUtils.getVideoMimeType(extension)
        if (MyDebug.LOG) Log.d(
            TAG,
            "mime_type: $mimeType"
        )
        contentValues.put(MediaStore.Video.Media.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativePath: String = storageUtils.saveRelativeFolder
            if (MyDebug.LOG) Log.d(
                TAG,
                "relative_path: $relativePath"
            )
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        try {
            lastVideoFileUri = mainActivity.contentResolver.insert(folder, contentValues)
            if (MyDebug.LOG) Log.d(
                TAG,
                "uri: $lastVideoFileUri"
            )
        } catch (e: IllegalArgumentException) {
            // can happen for mediastore method if invalid ContentResolver.insert() call
            if (MyDebug.LOG) Log.e(TAG, "IllegalArgumentException writing video file: " + e.message)
            e.printStackTrace()
            throw IOException()
        } catch (e: IllegalStateException) {
            // have received Google Play crashes from ContentResolver.insert() call for mediastore method
            if (MyDebug.LOG) Log.e(TAG, "IllegalStateException writing video file: " + e.message)
            e.printStackTrace()
            throw IOException()
        }
        if (lastVideoFileUri == null) {
            throw IOException()
        }

        return lastVideoFileUri!!
    }

    override fun createOutputVideoUri(): Uri {
        if (isVideoCaptureIntent) {
            if (MyDebug.LOG) Log.d(TAG, "from video capture intent")
            val myExtras = mainActivity.intent.extras
            if (myExtras != null) {
                val intentUri = myExtras.getParcelable<Uri>(MediaStore.EXTRA_OUTPUT)
                if (intentUri != null) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "save to: $intentUri"
                    )
                    return intentUri
                }
            }
        }
        throw RuntimeException() // programming error if we arrived here
    }

    override fun getFlashPref(): String {
        return settingsRepository?.getStringPreference(
            PreferenceKeys.getFlashPreferenceKey(getCameraIdPref()),
            ""
        ) ?: sharedPreferences.getString(
            PreferenceKeys.getFlashPreferenceKey(getCameraIdPref()),
            ""
        )!!
    }

    override fun setFlashPref(flashValue: String?) {
        if (flashValue != null) {
            settingsRepository?.setStringPreference(
                PreferenceKeys.getFlashPreferenceKey(getCameraIdPref()),
                flashValue
            )
        }
        val editor = sharedPreferences.edit()
        editor.putString(PreferenceKeys.getFlashPreferenceKey(getCameraIdPref()), flashValue)
        editor.apply()
    }

    override fun getFocusPref(isVideo: Boolean): String {
        if (photoMode == PhotoMode.FocusBracketing && !mainActivity.preview.isVideo) {
            return if (isFocusBracketingSourceAutoPref()) {
                "focus_mode_continuous_picture"
            } else {
                "focus_mode_manual2"
            }
        }
        return sharedPreferences.getString(
            PreferenceKeys.getFocusPreferenceKey(
                getCameraIdPref(),
                isVideo
            ), ""
        )!!
    }

    val focusAssistPref: Int
        get() {
            val focusAssistValue =
                sharedPreferences.getString(PreferenceKeys.FOCUS_ASSIST_PREFERENCE_KEY, "0")!!
            var focusAssist: Int
            try {
                focusAssist = focusAssistValue.toInt()
            } catch (e: NumberFormatException) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "failed to parse focus_assist_value: $focusAssistValue"
                )
                e.printStackTrace()
                focusAssist = 0
            }
            if (focusAssist > 0 && mainActivity.preview.isVideoRecording) {
                // focus assist not currently supported while recording video - don't want to zoom the resultant video!
                focusAssist = 0
            }
            return focusAssist
        }

    override fun isVideoPref(): Boolean =
        sharedPreferences.getBoolean(PreferenceKeys.IS_VIDEO_PREFERENCE_KEY, false)

    override fun setVideoPref(isVideo: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean(PreferenceKeys.IS_VIDEO_PREFERENCE_KEY, isVideo)
        editor.apply()
    }

    override fun getSceneModePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.SCENE_MODE_PREFERENCE_KEY,
            CameraController.SCENE_MODE_DEFAULT
        )!!
    }

    override fun setSceneModePref(sceneMode: String?) {
        val editor = sharedPreferences.edit()
        editor.putString(PreferenceKeys.SCENE_MODE_PREFERENCE_KEY, sceneMode)
        editor.apply()
    }

    override fun getColorEffectPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.COLOR_EFFECT_PREFERENCE_KEY,
            CameraController.COLOR_EFFECT_DEFAULT
        )!!
    }

    override fun setColorEffectPref(colorEffect: String?) {
        val editor = sharedPreferences.edit()
        editor.putString(PreferenceKeys.COLOR_EFFECT_PREFERENCE_KEY, colorEffect)
        editor.apply()
    }

    override fun getWhiteBalancePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.WHITE_BALANCE_PREFERENCE_KEY,
            CameraController.WHITE_BALANCE_DEFAULT
        )!!
    }

    override fun setWhiteBalancePref(whiteBalance: String?) {
        val editor = sharedPreferences.edit()
        editor.putString(PreferenceKeys.WHITE_BALANCE_PREFERENCE_KEY, whiteBalance)
        editor.apply()
    }

    override fun getWhiteBalanceTemperaturePref(): Int =
        sharedPreferences.getInt(PreferenceKeys.WHITE_BALANCE_TEMPERATURE_PREFERENCE_KEY, 5000)

    override fun setWhiteBalanceTemperaturePref(whiteBalanceTemperature: Int) {
        val editor = sharedPreferences.edit()
        editor.putInt(
            PreferenceKeys.WHITE_BALANCE_TEMPERATURE_PREFERENCE_KEY,
            whiteBalanceTemperature
        )
        editor.apply()
    }

    override fun getAntiBandingPref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.ANTI_BANDING_PREFERENCE_KEY,
            CameraController.ANTIBANDING_DEFAULT
        )!!
    }

    override fun getEdgeModePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.EDGE_MODE_PREFERENCE_KEY,
            CameraController.EDGE_MODE_DEFAULT
        )!!
    }

    override fun getCameraNoiseReductionModePref(): String {
        return sharedPreferences.getString(
            PreferenceKeys.CAMERA_NOISE_REDUCTION_MODE_PREFERENCE_KEY,
            CameraController.NOISE_REDUCTION_MODE_DEFAULT
        )!!
    }

    override fun getISOPref(): String {
        return settingsRepository?.getStringPreference(
            PreferenceKeys.ISO_PREFERENCE_KEY,
            CameraController.ISO_DEFAULT
        ) ?: sharedPreferences.getString(
            PreferenceKeys.ISO_PREFERENCE_KEY,
            CameraController.ISO_DEFAULT
        )!!
    }

    override fun setISOPref(iso: String?) {
        if (iso != null) {
            settingsRepository?.setStringPreference(
                PreferenceKeys.ISO_PREFERENCE_KEY,
                iso
            )
        }
        val editor = sharedPreferences.edit()
        editor.putString(PreferenceKeys.ISO_PREFERENCE_KEY, iso)
        editor.apply()
    }

    override fun getExposureCompensationPref(): Int {
        val value =
            sharedPreferences.getString(PreferenceKeys.EXPOSURE_PREFERENCE_KEY, "0")!!
        if (MyDebug.LOG) Log.d(
            TAG,
            "saved exposure value: $value"
        )
        var exposure = 0
        try {
            exposure = value.toInt()
            if (MyDebug.LOG) Log.d(
                TAG,
                "exposure: $exposure"
            )
        } catch (exception: NumberFormatException) {
            if (MyDebug.LOG) Log.d(TAG, "exposure invalid format, can't parse to int")
        }
        return exposure
    }

    override fun setExposureCompensationPref(exposure: Int) {
        val editor = sharedPreferences.edit()
        editor.putString(PreferenceKeys.EXPOSURE_PREFERENCE_KEY, exposure.toString())
        editor.apply()
    }

    override fun getCameraResolutionPref(constraints: CameraResolutionConstraints): Pair<Int, Int>? {
        val photoMode = photoMode
        if (photoMode == PhotoMode.Panorama) {
            val bestSize: CameraController.Size = choosePanoramaResolution(
                mainActivity.preview.getSupportedPictureSizes(false) ?: emptyList()
            )
            return Pair(bestSize.width, bestSize.height)
        }

        val resolutionValue = sharedPreferences.getString(
            PreferenceKeys.getResolutionPreferenceKey(
                getCameraIdPref(),
                getCameraIdSPhysicalPref()
            ), ""
        )!!
        if (MyDebug.LOG) Log.d(
            TAG,
            "resolution_value: $resolutionValue"
        )
        var result: Pair<Int, Int>? = null
        if (resolutionValue.length > 0) {
            // parse the saved size, and make sure it is still valid
            val index = resolutionValue.indexOf(' ')
            if (index == -1) {
                if (MyDebug.LOG) Log.d(TAG, "resolution_value invalid format, can't find space")
            } else {
                val resolutionWS = resolutionValue.substring(0, index)
                val resolutionHS = resolutionValue.substring(index + 1)
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "resolution_w_s: $resolutionWS"
                    )
                    Log.d(
                        TAG,
                        "resolution_h_s: $resolutionHS"
                    )
                }
                try {
                    val resolutionW = resolutionWS.toInt()
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "resolution_w: $resolutionW"
                    )
                    val resolutionH = resolutionHS.toInt()
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "resolution_h: $resolutionH"
                    )
                    result = Pair(resolutionW, resolutionH)
                } catch (exception: NumberFormatException) {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "resolution_value invalid format, can't parse w or h to int"
                    )
                }
            }
        }

        if (photoMode == PhotoMode.NoiseReduction || photoMode == PhotoMode.HDR) {
            // set a maximum resolution for modes that require decompressing multiple images for processing,
            // due to risk of running out of memory!
            constraints.hasMaxMp = true
            constraints.maxMp = 18000000 // max of 18MP
            //constraints.maxMp = 7800000; // test!
            if (mainActivity.isTest && testMaxMp != 0) {
                constraints.maxMp = testMaxMp
            }
        }

        return result
    }

    private val saveImageQualityPref: Int
        /** getImageQualityPref() returns the image quality used for the Camera Controller for taking a
         * photo - in some cases, we may set that to a higher value, then perform processing on the
         * resultant JPEG before resaving. This method returns the image quality setting to be used for
         * saving the final image (as specified by the user).
         */
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSaveImageQualityPref")
            val imageQualityS =
                sharedPreferences.getString(PreferenceKeys.QUALITY_PREFERENCE_KEY, "90")!!
            var imageQuality: Int
            try {
                imageQuality = imageQualityS.toInt()
            } catch (exception: NumberFormatException) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "image_quality_s invalid format: $imageQualityS"
                )
                imageQuality = 90
            }
            if (isRawOnly) {
                // if raw only mode, we can set a lower quality for the JPEG, as it isn't going to be saved - only used for
                // the thumbnail and pause preview option
                if (MyDebug.LOG) Log.d(TAG, "set lower quality for raw_only mode")
                imageQuality = min(imageQuality.toDouble(), 70.0).toInt()
            }
            return imageQuality
        }

    override fun getImageQualityPref(): Int {
        if (MyDebug.LOG) Log.d(TAG, "getImageQualityPref")
        // see documentation for getSaveImageQualityPref(): in DRO mode we want to take the photo
        // at 100% quality for post-processing, the final image will then be saved at the user requested
        // setting
        val photoMode = photoMode
        if (mainActivity.preview
                .isVideo
        ) ; else if (photoMode == PhotoMode.DRO) return 100
        else if (photoMode == PhotoMode.HDR) return 100
        else if (photoMode == PhotoMode.NoiseReduction) return 100

        if (imageFormatPref !== ImageSaver.Request.ImageFormat.STD) return 100

        return saveImageQualityPref
    }

    override fun getFaceDetectionPref(): Boolean {
        if (isCameraExtensionPref()) {
            // not supported for camera extensions
            return false
        }
        return sharedPreferences.getBoolean(PreferenceKeys.FACE_DETECTION_PREFERENCE_KEY, false)
    }

    /** Returns whether the current fps preference is one that requires a "high speed" video size/
     * frame rate.
     */
    fun fpsIsHighSpeed(): Boolean {
        return mainActivity.preview.fpsIsHighSpeed(getVideoFPSPref())
    }

    override fun getVideoQualityPref(): String {
        if (isVideoCaptureIntent) {
            if (MyDebug.LOG) Log.d(TAG, "from video capture intent")
            if (mainActivity.intent.hasExtra(MediaStore.EXTRA_VIDEO_QUALITY)) {
                val intentQuality =
                    mainActivity.intent.getIntExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "intent_quality: $intentQuality"
                )
                if (intentQuality == 0 || intentQuality == 1) {
                    val videoQuality: List<String> =
                        mainActivity.preview.videoQualityHander.supportedVideoQuality
                    if (intentQuality == 0) {
                        if (MyDebug.LOG) Log.d(TAG, "return lowest quality")
                        // return lowest quality, videoQuality is sorted high to low
                        return videoQuality[videoQuality.size - 1]
                    } else {
                        if (MyDebug.LOG) Log.d(TAG, "return highest quality")
                        // return highest quality, videoQuality is sorted high to low
                        return videoQuality[0]
                    }
                }
            }
        }

        // Conceivably, we might get in a state where the fps isn't supported at all (e.g., an upgrade changes the available
        // supported video resolutions/frame-rates).
        return sharedPreferences.getString(
            PreferenceKeys.getVideoQualityPreferenceKey(
                getCameraIdPref(),
                getCameraIdSPhysicalPref(), fpsIsHighSpeed()
            ), ""
        )!!
    }

    override fun setVideoQualityPref(videoQuality: String?) {
        val editor = sharedPreferences.edit()
        editor.putString(
            PreferenceKeys.getVideoQualityPreferenceKey(
                getCameraIdPref(),
                getCameraIdSPhysicalPref(), fpsIsHighSpeed()
            ), videoQuality
        )
        editor.apply()
    }

    override fun getVideoStabilizationPref(): Boolean =
        sharedPreferences.getBoolean(PreferenceKeys.VIDEO_STABILIZATION_PREFERENCE_KEY, false)

    override fun getForce4KPref(): Boolean = getCameraIdPref() == 0 && sharedPreferences.getBoolean(
        PreferenceKeys.FORCE_VIDEO_4_K_PREFERENCE_KEY,
        false
    ) && mainActivity.supportsForceVideo4K()

    override fun getRecordVideoOutputFormatPref(): String = sharedPreferences.getString(
        PreferenceKeys.VIDEO_FORMAT_PREFERENCE_KEY,
        "preference_video_output_format_default"
    )!!

    override fun getVideoBitratePref(): String =
        sharedPreferences.getString(PreferenceKeys.VIDEO_BITRATE_PREFERENCE_KEY, "default")!!

    override fun getVideoFPSPref(): String {
        // if check for EXTRA_VIDEO_QUALITY, if set, best to fall back to default FPS - see corresponding code in getVideoQualityPref
        if (isVideoCaptureIntent) {
            if (MyDebug.LOG) Log.d(TAG, "from video capture intent")
            if (mainActivity.intent.hasExtra(MediaStore.EXTRA_VIDEO_QUALITY)) {
                val intentQuality =
                    mainActivity.intent.getIntExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "intent_quality: $intentQuality"
                )
                if (intentQuality == 0 || intentQuality == 1) {
                    return "default"
                }
            }
        }

        val captureRateFactor = getVideoCaptureRateFactor()
        if (captureRateFactor < 1.0f - 1.0e-5f) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "set fps for slow motion, capture rate: $captureRateFactor"
            )
            var preferredFps = (30.0 / captureRateFactor + 0.5).toInt()
            if (MyDebug.LOG) Log.d(
                TAG,
                "preferred_fps: $preferredFps"
            )
            if (mainActivity.preview.videoQualityHander
                    .videoSupportsFrameRateHighSpeed(preferredFps) ||
                mainActivity.preview.videoQualityHander
                    .videoSupportsFrameRate(preferredFps)
            ) return preferredFps.toString()
            // just in case say we support 120fps but NOT 60fps, getSupportedSlowMotionRates() will have returned that 2x slow
            // motion is supported, but we need to set 120fps instead of 60fps
            while (preferredFps < 240) {
                preferredFps *= 2
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "preferred_fps not supported, try: $preferredFps"
                )
                if (mainActivity.preview.videoQualityHander
                        .videoSupportsFrameRateHighSpeed(preferredFps) ||
                    mainActivity.preview.videoQualityHander
                        .videoSupportsFrameRate(preferredFps)
                ) return preferredFps.toString()
            }
            // shouln't happen based on getSupportedSlowMotionRates()
            Log.e(TAG, "can't find valid fps for slow motion")
            return "default"
        }
        return sharedPreferences.getString(
            PreferenceKeys.getVideoFPSPreferenceKey(
                getCameraIdPref(),
                getCameraIdSPhysicalPref()
            ), "default"
        )!!
    }

    override fun getVideoCaptureRateFactor(): Float {
        var captureRateFactor = sharedPreferences.getFloat(
            PreferenceKeys.getVideoCaptureRatePreferenceKey(
                mainActivity.preview.cameraId,
                getCameraIdSPhysicalPref()
            ), 1.0f
        )
        if (MyDebug.LOG) Log.d(
            TAG,
            "capture_rate_factor: $captureRateFactor"
        )
        if (abs((captureRateFactor - 1.0f).toDouble()) > 1.0e-5) {
            // check stored capture rate is valid
            if (MyDebug.LOG) Log.d(TAG, "check stored capture rate is valid")
            val supportedCaptureRates =
                supportedVideoCaptureRates
            if (MyDebug.LOG) Log.d(
                TAG,
                "supported_capture_rates: $supportedCaptureRates"
            )
            var found = false
            for (thisCaptureRate in supportedCaptureRates) {
                if (abs((captureRateFactor - thisCaptureRate).toDouble()) < 1.0e-5) {
                    found = true
                    break
                }
            }
            if (!found) {
                Log.e(
                    TAG,
                    "stored capture_rate_factor: $captureRateFactor not supported"
                )
                captureRateFactor = 1.0f
            }
        }
        return captureRateFactor
    }

    val supportedVideoCaptureRates: List<Float>
        /** This will always return 1, even if slow motion isn't supported (i.e.,
         * slow motion should only be considered as supported if at least 2 entries
         * are returned. Entries are returned in increasing order.
         */
        get() {
            val rates: MutableList<Float> = ArrayList()
            if (mainActivity.preview.supportsVideoHighSpeed()) {
                // We consider a slow motion rate supported if we can get at least 30fps in slow motion.
                // If this code is updated, see if we also need to update how slow motion fps is chosen
                // in getVideoFPSPref().
                if (mainActivity.preview.videoQualityHander
                        .videoSupportsFrameRateHighSpeed(240) ||
                    mainActivity.preview.videoQualityHander.videoSupportsFrameRate(240)
                ) {
                    rates.add(1.0f / 8.0f)
                    rates.add(1.0f / 4.0f)
                    rates.add(1.0f / 2.0f)
                } else if (mainActivity.preview.videoQualityHander
                        .videoSupportsFrameRateHighSpeed(120) ||
                    mainActivity.preview.videoQualityHander.videoSupportsFrameRate(120)
                ) {
                    rates.add(1.0f / 4.0f)
                    rates.add(1.0f / 2.0f)
                } else if (mainActivity.preview.videoQualityHander
                        .videoSupportsFrameRateHighSpeed(60) ||
                    mainActivity.preview.videoQualityHander.videoSupportsFrameRate(60)
                ) {
                    rates.add(1.0f / 2.0f)
                }
            }
            rates.add(1.0f)
            run {
                // add timelapse options
                // in theory this should work on any Android version, though video fails to record in timelapse mode on Galaxy Nexus...
                rates.add(2.0f)
                rates.add(3.0f)
                rates.add(4.0f)
                rates.add(5.0f)
                rates.add(10.0f)
                rates.add(20.0f)
                rates.add(30.0f)
                rates.add(60.0f)
                rates.add(120.0f)
                rates.add(240.0f)
            }
            return rates
        }

    override fun getVideoTonemapProfile(): CameraController.TonemapProfile {
        val videoLog =
            sharedPreferences.getString(PreferenceKeys.VIDEO_LOG_PREFERENCE_KEY, "off")!!
        // only return TONEMAPPROFILE_LOG for values recognised by getVideoLogProfileStrength()
        when (videoLog) {
            "off" -> return CameraController.TonemapProfile.TONEMAPPROFILE_OFF
            "rec709" -> return CameraController.TonemapProfile.TONEMAPPROFILE_REC709
            "srgb" -> return CameraController.TonemapProfile.TONEMAPPROFILE_SRGB
            "fine", "low", "medium", "strong", "extra_strong" -> return CameraController.TonemapProfile.TONEMAPPROFILE_LOG
            "gamma" -> return CameraController.TonemapProfile.TONEMAPPROFILE_GAMMA
            "jtvideo" -> return CameraController.TonemapProfile.TONEMAPPROFILE_JTVIDEO
            "jtlog" -> return CameraController.TonemapProfile.TONEMAPPROFILE_JTLOG
            "jtlog2" -> return CameraController.TonemapProfile.TONEMAPPROFILE_JTLOG2
        }
        return CameraController.TonemapProfile.TONEMAPPROFILE_OFF
    }

    override fun getVideoLogProfileStrength(): Float {
        val videoLog =
            sharedPreferences.getString(PreferenceKeys.VIDEO_LOG_PREFERENCE_KEY, "off")!!
        // remember to update getVideoTonemapProfile() if adding/changing modes
        when (videoLog) {
            "off", "rec709", "srgb", "gamma", "jtvideo", "jtlog", "jtlog2" -> return 0.0f
            "fine" -> return 10.0f
            "low" -> return 32.0f
            "medium" -> return 100.0f
            "strong" -> return 224.0f
            "extra_strong" -> return 500.0f
        }
        return 0.0f
    }

    override fun getVideoProfileGamma(): Float {
        val gammaValue =
            sharedPreferences.getString(PreferenceKeys.VIDEO_PROFILE_GAMMA_PREFERENCE_KEY, "2.2")!!
        var gamma = 0.0f
        try {
            gamma = gammaValue.toFloat()
            if (MyDebug.LOG) Log.d(
                TAG,
                "gamma: $gamma"
            )
        } catch (e: NumberFormatException) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "failed to parse gamma value: $gammaValue"
            )
            e.printStackTrace()
        }
        return gamma
    }

    override fun getVideoMaxDurationPref(): Long {
        if (isVideoCaptureIntent) {
            if (MyDebug.LOG) Log.d(TAG, "from video capture intent")
            if (mainActivity.intent.hasExtra(MediaStore.EXTRA_DURATION_LIMIT)) {
                val intentDurationLimit =
                    mainActivity.intent.getIntExtra(MediaStore.EXTRA_DURATION_LIMIT, 0)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "intent_duration_limit: $intentDurationLimit"
                )
                return intentDurationLimit * 1000L
            }
        }

        val videoMaxDurationValue =
            sharedPreferences.getString(PreferenceKeys.VIDEO_MAX_DURATION_PREFERENCE_KEY, "0")!!
        var videoMaxDuration: Long
        try {
            videoMaxDuration = videoMaxDurationValue.toInt().toLong() * 1000
        } catch (e: NumberFormatException) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "failed to parse preference_video_max_duration value: $videoMaxDurationValue"
            )
            e.printStackTrace()
            videoMaxDuration = 0
        }
        return videoMaxDuration
    }

    override fun getVideoRestartTimesPref(): Int {
        val restartValue =
            sharedPreferences.getString(PreferenceKeys.VIDEO_RESTART_PREFERENCE_KEY, "0")!!
        var remainingRestartVideo: Int
        try {
            remainingRestartVideo = restartValue.toInt()
        } catch (e: NumberFormatException) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "failed to parse preference_video_restart value: $restartValue"
            )
            e.printStackTrace()
            remainingRestartVideo = 0
        }
        return remainingRestartVideo
    }

    val videoMaxFileSizeUserPref: Long
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getVideoMaxFileSizeUserPref")

            if (isVideoCaptureIntent) {
                if (MyDebug.LOG) Log.d(TAG, "from video capture intent")
                if (mainActivity.intent.hasExtra(MediaStore.EXTRA_SIZE_LIMIT)) {
                    val intentSizeLimit =
                        mainActivity.intent.getLongExtra(MediaStore.EXTRA_SIZE_LIMIT, 0)
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "intent_size_limit: $intentSizeLimit"
                    )
                    return intentSizeLimit
                }
            }

            val videoMaxFilesizeValue =
                sharedPreferences.getString(PreferenceKeys.VIDEO_MAX_FILE_SIZE_PREFERENCE_KEY, "0")!!
            var videoMaxFilesize: Long
            try {
                videoMaxFilesize = videoMaxFilesizeValue.toLong()
            } catch (e: NumberFormatException) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "failed to parse preference_video_max_filesize value: $videoMaxFilesizeValue"
                )
                e.printStackTrace()
                videoMaxFilesize = 0
            }
            //videoMaxFilesize = 1024*1024; // test
            if (MyDebug.LOG) Log.d(
                TAG,
                "video_max_filesize: $videoMaxFilesize"
            )
            return videoMaxFilesize
        }

    private val videoRestartMaxFileSizeUserPref: Boolean
        get() {
            if (isVideoCaptureIntent) {
                if (MyDebug.LOG) Log.d(TAG, "from video capture intent")
                if (mainActivity.intent.hasExtra(MediaStore.EXTRA_SIZE_LIMIT)) {
                    // if called from a video capture intent that set a max file size, this will be expecting a single file with that maximum size
                    return false
                }
            }

            return sharedPreferences.getBoolean(
                PreferenceKeys.VIDEO_RESTART_MAX_FILE_SIZE_PREFERENCE_KEY,
                true
            )
        }

    @Throws(NoFreeStorageException::class)
    override fun getVideoMaxFileSizePref(): ApplicationInterface.VideoMaxFileSize {
        if (MyDebug.LOG) Log.d(TAG, "getVideoMaxFileSizePref")
        val videoMaxFilesize: ApplicationInterface.VideoMaxFileSize =
            ApplicationInterface.VideoMaxFileSize()
        videoMaxFilesize.maxFilesize = videoMaxFileSizeUserPref
        videoMaxFilesize.autoRestart = videoRestartMaxFileSizeUserPref


        /* Try to set the max filesize so we don't run out of space.
           If using SD card without storage access framework, it's not reliable to get the free storage
           (see https://sourceforge.net/p/OpenKamera/tickets/153/ ).
           If using Storage Access Framework, getting the available space seems to be reliable for
           internal storage or external SD card.
           */
        val setMaxFilesize: Boolean
        if (storageUtils.isUsingSAF) {
            setMaxFilesize = true
        } else {
            val folderName: String = storageUtils.saveLocation
            if (MyDebug.LOG) Log.d(
                TAG,
                "saving to: $folderName"
            )
            var isInternal = false
            if (!StorageUtils.saveFolderIsFull(folderName)) {
                isInternal = true
            } else {
                // If save folder path is a full path, see if it matches the "external" storage (which actually means "primary", which typically isn't an SD card these days).
                val storage = Environment.getExternalStorageDirectory()
                if (MyDebug.LOG) Log.d(TAG, "compare to: " + storage.absolutePath)
                if (folderName.startsWith(storage.absolutePath)) isInternal = true
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "using internal storage?$isInternal"
            )
            setMaxFilesize = isInternal
        }
        if (setMaxFilesize) {
            if (MyDebug.LOG) Log.d(TAG, "try setting max filesize")
            var freeMemory: Long = storageUtils.freeMemory()
            if (freeMemory >= 0) {
                freeMemory = freeMemory * 1024 * 1024

                val minFreeMemory: Long = 50000000 // how much free space to leave after video
                // minFreeFilesize is the minimum value to set for max file size:
                //   - no point trying to create a really short video
                //   - too short videos can end up being corrupted
                //   - also with auto-restart, if this is too small we'll end up repeatedly restarting and creating shorter and shorter videos
                val minFreeFilesize: Long = 20000000
                var availableMemory = freeMemory - minFreeMemory
                if (testSetAvailableMemory) {
                    availableMemory = testAvailableMemory
                }
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "free_memory: $freeMemory"
                    )
                    Log.d(
                        TAG,
                        "available_memory: $availableMemory"
                    )
                }
                if (availableMemory > minFreeFilesize) {
                    if (videoMaxFilesize.maxFilesize == 0L || videoMaxFilesize.maxFilesize > availableMemory) {
                        videoMaxFilesize.maxFilesize = availableMemory
                        // still leave autoRestart set to true - because even if we set a max filesize for running out of storage, the video may still hit a maximum limit beforehand, if there's a device max limit set (typically ~2GB)
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "set video_max_filesize to avoid running out of space: $videoMaxFilesize"
                        )
                    }
                } else {
                    if (MyDebug.LOG) Log.e(TAG, "not enough free storage to record video")
                    throw NoFreeStorageException
                }
            } else {
                if (MyDebug.LOG) Log.d(TAG, "can't determine remaining free space")
            }
        }

        return videoMaxFilesize
    }

    override fun getVideoFlashPref(): Boolean =
        sharedPreferences.getBoolean(PreferenceKeys.VIDEO_FLASH_PREFERENCE_KEY, false)

    override fun getVideoLowPowerCheckPref(): Boolean =
        sharedPreferences.getBoolean(PreferenceKeys.VIDEO_LOW_POWER_CHECK_PREFERENCE_KEY, true)

    override fun getPreviewSizePref(): String = sharedPreferences.getString(
        PreferenceKeys.PREVIEW_SIZE_PREFERENCE_KEY,
        "preference_preview_size_wysiwyg"
    )!!

    override fun getLockOrientationPref(): String {
        if (photoMode == PhotoMode.Panorama) return "portrait" // for now panorama only supports portrait

        return sharedPreferences.getString(
            PreferenceKeys.LOCK_ORIENTATION_PREFERENCE_KEY,
            "none"
        )!!
    }

    override fun getTouchCapturePref(): Boolean {
        val value =
            sharedPreferences.getString(PreferenceKeys.TOUCH_CAPTURE_PREFERENCE_KEY, "none")!!
        return value == "single"
    }

    override fun getDoubleTapCapturePref(): Boolean {
        val value =
            sharedPreferences.getString(PreferenceKeys.TOUCH_CAPTURE_PREFERENCE_KEY, "none")!!
        return value == "double"
    }

    override fun getPausePreviewPref(): Boolean {
        if (mainActivity.preview.isVideoRecording) {
            // don't pause preview when taking photos while recording video!
            return false
        } else if (mainActivity.lastContinuousFastBurst()) {
            // Don't use pause preview mode when doing a continuous fast burst
            // Firstly due to not using background thread for pause preview mode, this will be
            // sluggish anyway, but even when this is fixed, I'm not sure it makes sense to use
            // pause preview in this mode.
            return false
        } else if (photoMode == PhotoMode.Panorama) {
            // don't pause preview when taking photos for panorama mode
            return false
        }
        return sharedPreferences.getBoolean(PreferenceKeys.PAUSE_PREVIEW_PREFERENCE_KEY, false)
    }

    override fun getShowToastsPref(): Boolean =
        sharedPreferences.getBoolean(PreferenceKeys.SHOW_TOASTS_PREFERENCE_KEY, true)

    val thumbnailAnimationPref: Boolean
        get() = sharedPreferences.getBoolean(PreferenceKeys.THUMBNAIL_ANIMATION_PREFERENCE_KEY, true)

    override fun getShutterSoundPref(): Boolean {
        if (photoMode == PhotoMode.Panorama) return false
        return sharedPreferences.getBoolean(PreferenceKeys.SHUTTER_SOUND_PREFERENCE_KEY, true)
    }

    override fun getStartupFocusPref(): Boolean =
        sharedPreferences.getBoolean(PreferenceKeys.STARTUP_FOCUS_PREFERENCE_KEY, true)

    override fun getTimerPref(): Long {
        if (photoMode == PhotoMode.Panorama) return 0 // don't support timer with panorama

        val timerValue =
            sharedPreferences.getString(PreferenceKeys.TIMER_PREFERENCE_KEY, "0")!!
        var timerDelay: Long
        try {
            timerDelay = timerValue.toInt().toLong() * 1000
        } catch (e: NumberFormatException) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "failed to parse preference_timer value: $timerValue"
            )
            e.printStackTrace()
            timerDelay = 0
        }
        return timerDelay
    }

    override fun getRepeatPref(): String {
        if (photoMode == PhotoMode.Panorama) return "1" // don't support repeat with panorama

        return sharedPreferences.getString(PreferenceKeys.REPEAT_MODE_PREFERENCE_KEY, "1")!!
    }

    override fun getRepeatIntervalPref(): Long {
        val timerValue =
            sharedPreferences.getString(PreferenceKeys.REPEAT_INTERVAL_PREFERENCE_KEY, "0")!!
        var timerDelay: Long
        try {
            val timerDelayS = timerValue.toFloat()
            if (MyDebug.LOG) Log.d(
                TAG,
                "timer_delay_s: $timerDelayS"
            )
            timerDelay = (timerDelayS * 1000).toLong()
        } catch (e: NumberFormatException) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "failed to parse repeat interval value: $timerValue"
            )
            e.printStackTrace()
            timerDelay = 0
        }
        return timerDelay
    }

    private val removeDeviceExifPref: ImageSaver.Request.RemoveDeviceExif
        get() {
            return when (sharedPreferences.getString(
                PreferenceKeys.REMOVE_DEVICE_EXIF_PREFERENCE_KEY,
                "preference_remove_device_exif_off"
            )) {
                "preference_remove_device_exif_on" -> ImageSaver.Request.RemoveDeviceExif.ON
                "preference_remove_device_exif_keep_datetime" -> ImageSaver.Request.RemoveDeviceExif.KEEP_DATETIME
                else -> ImageSaver.Request.RemoveDeviceExif.OFF
            }
        }

    override fun getGeotaggingPref(): Boolean =
        sharedPreferences.getBoolean(PreferenceKeys.LOCATION_PREFERENCE_KEY, false)

    override fun getRequireLocationPref(): Boolean =
        sharedPreferences.getBoolean(PreferenceKeys.REQUIRE_LOCATION_PREFERENCE_KEY, false)

    val geodirectionPref: Boolean
        get() = sharedPreferences.getBoolean(PreferenceKeys.GPS_DIRECTION_PREFERENCE_KEY, false)

    override fun getRecordAudioPref(): Boolean =
        sharedPreferences.getBoolean(PreferenceKeys.RECORD_AUDIO_PREFERENCE_KEY, true)

    override fun getRecordAudioChannelsPref(): String = sharedPreferences.getString(
        PreferenceKeys.RECORD_AUDIO_CHANNELS_PREFERENCE_KEY,
        "audio_default"
    )!!

    override fun getRecordAudioSourcePref(): String = sharedPreferences.getString(
        PreferenceKeys.RECORD_AUDIO_SOURCE_PREFERENCE_KEY,
        "audio_src_camcorder"
    )!!

    val focusPeakingPref: Boolean
        get() {
            val focusPeakingPref =
                sharedPreferences.getString(
                    PreferenceKeys.FOCUS_PEAKING_PREFERENCE_KEY,
                    "preference_focus_peaking_off"
                )!!
            return focusPeakingPref != "preference_focus_peaking_off" && mainActivity.supportsPreviewBitmaps()
        }

    fun getPreShotsPref(photoMode: PhotoMode): Boolean {
        if (mainActivity.preview.isVideo || photoMode == PhotoMode.ExpoBracketing || photoMode == PhotoMode.FocusBracketing || photoMode == PhotoMode.Panorama
        ) {
            // pre-shots not supported for these modes
            return false
        }
        val preShotsPref =
            sharedPreferences.getString(
                PreferenceKeys.PRE_SHOTS_PREFERENCE_KEY,
                "preference_save_preshots_off"
            )!!
        return preShotsPref != "preference_save_preshots_off" && mainActivity.supportsPreShots()
    }

    val autoStabilisePref: Boolean
        get() {
            val autoStabilise =
                sharedPreferences.getBoolean(PreferenceKeys.AUTO_STABILISE_PREFERENCE_KEY, false)
            return autoStabilise && mainActivity.supportsAutoStabilise()
        }

    val ghostImageAlpha: Int
        /** Returns the alpha value to use for ghost image, as a number from 0 to 255.
         * Note that we store the preference as a percentage from 0 to 100, but scale this to 0 to 255.
         */
        get() {
            val ghostImageAlphaValue =
                sharedPreferences.getString(PreferenceKeys.GHOST_IMAGE_ALPHA_PREFERENCE_KEY, "50")!!
            var ghostImageAlpha: Int
            try {
                ghostImageAlpha = ghostImageAlphaValue.toInt()
            } catch (e: NumberFormatException) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "failed to parse ghost_image_alpha_value: $ghostImageAlphaValue"
                )
                e.printStackTrace()
                ghostImageAlpha = 50
            }
            ghostImageAlpha = (ghostImageAlpha * 2.55f + 0.1f).toInt()
            return ghostImageAlpha
        }

    val stampPref: String
        get() = sharedPreferences.getString(
            PreferenceKeys.STAMP_PREFERENCE_KEY,
            "preference_stamp_no"
        )!!

    private val stampDateFormatPref: String
        get() = sharedPreferences.getString(
            PreferenceKeys.STAMP_DATE_FORMAT_PREFERENCE_KEY,
            "preference_stamp_dateformat_default"
        )!!

    private val stampTimeFormatPref: String
        get() = sharedPreferences.getString(
            PreferenceKeys.STAMP_TIME_FORMAT_PREFERENCE_KEY,
            "preference_stamp_timeformat_default"
        )!!

    private val stampGPSFormatPref: String
        get() = sharedPreferences.getString(
            PreferenceKeys.STAMP_GPS_FORMAT_PREFERENCE_KEY,
            "preference_stamp_gpsformat_default"
        )!!

    private val unitsDistancePref: String
        /*private String getStampGeoAddressPref() {
                   return sharedPreferences.getString(PreferenceKeys.StampGeoAddressPreferenceKey, "preference_stamp_geo_address_no");
               }*/
        get() = sharedPreferences.getString(
            PreferenceKeys.UNITS_DISTANCE_PREFERENCE_KEY,
            "preference_units_distance_m"
        )!!

    val textStampPref: String
        get() = sharedPreferences.getString(PreferenceKeys.TEXT_STAMP_PREFERENCE_KEY, "")!!

    private val textStampFontSizePref: Int
        get() {
            var fontSize = 12
            val value =
                sharedPreferences.getString(PreferenceKeys.STAMP_FONT_SIZE_PREFERENCE_KEY, "12")!!
            if (MyDebug.LOG) Log.d(
                TAG,
                "saved font size: $value"
            )
            try {
                fontSize = value.toInt()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "font_size: $fontSize"
                )
            } catch (exception: NumberFormatException) {
                if (MyDebug.LOG) Log.d(TAG, "font size invalid format, can't parse to int")
            }
            return fontSize
        }

    private fun getVideoSubtitlePref(videoMethod: VideoMethod): String {
        if (videoMethod === VideoMethod.MEDIASTORE && !mediastoreSupportsVideoSubtitles()) {
            return "preference_video_subtitle_no"
        }
        return sharedPreferences.getString(
            PreferenceKeys.VIDEO_SUBTITLE_PREF,
            "preference_video_subtitle_no"
        )!!
    }

    override fun getZoomPref(): Int {
        if (MyDebug.LOG) Log.d(
            TAG,
            "getZoomPref: $zoomFactor"
        )
        return zoomFactor
    }

    override fun setZoomPref(zoom: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setZoomPref: $zoom"
        )
        this.zoomFactor = zoom
    }

    override fun getCalibratedLevelAngle(): Double =
        sharedPreferences.getFloat(PreferenceKeys.CALIBRATED_LEVEL_ANGLE_PREFERENCE_KEY, 0.0f)
            .toDouble()

    override fun canTakeNewPhoto(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "canTakeNewPhoto")

        val nRaw: Int
        var nJpegs: Int
        if (mainActivity.preview.isVideo) {
            // video snapshot mode
            nRaw = 0
            nJpegs = 1
        } else {
            nJpegs = 1 // default

            if (mainActivity.preview
                    .supportsExpoBracketing() && this.isExpoBracketingPref()
            ) {
                nJpegs = this.getExpoBracketingNImagesPref()
            } else if (mainActivity.preview
                    .supportsFocusBracketing() && this.isFocusBracketingPref()
            ) {
                // focus bracketing mode always avoids blocking the image queue, no matter how many images are being taken
                // so all that matters is that we can take at least 1 photo (for the first shot)
                //nJpegs = this.getFocusBracketingNImagesPref();
                nJpegs = 1
            } else if (mainActivity.preview.supportsBurst() && this.isCameraBurstPref()) {
                nJpegs = if (this.getBurstForNoiseReduction()) {
                    if (this.getNRModePref() === ApplicationInterface.NRModePref.NRMODE_LOW_LIGHT) {
                        CameraController.N_IMAGES_NR_DARK_LOW_LIGHT
                    } else {
                        CameraController.N_IMAGES_NR_DARK
                    }
                } else {
                    getBurstNImages()
                }
            }

            nRaw = if (mainActivity.preview
                    .supportsRaw() && this.getRawPref() === RawPref.RAWPREF_JPEG_DNG
            ) {
                // note, even in RAW only mode, the CameraController will still take JPEG+RAW (we still need to JPEG to
                // generate a bitmap from for thumbnail and pause preview option), so this still generates a request in
                // the ImageSaver
                nJpegs
            } else {
                0
            }
        }

        val photoCost: Int = imageSaver.computePhotoCost(nRaw, nJpegs)
        if (imageSaver.queueWouldBlock(photoCost)) {
            if (MyDebug.LOG) Log.d(TAG, "canTakeNewPhoto: no, as queue would block")
            return false
        }

        // even if the queue isn't full, we may apply additional limits
        val nImagesToSave: Int = imageSaver.nImagesToSave
        val photoMode = photoMode
        if (photoMode == PhotoMode.FastBurst || photoMode == PhotoMode.Panorama) {
            // only allow one fast burst at a time, so require queue to be empty
            if (nImagesToSave > 0) {
                if (MyDebug.LOG) Log.d(TAG, "canTakeNewPhoto: no, as too many for fast burst")
                return false
            }
        }
        if (photoMode == PhotoMode.NoiseReduction) {
            // allow a max of 2 photos in memory when at max of 8 images
            if (nImagesToSave >= 2 * photoCost) {
                if (MyDebug.LOG) Log.d(TAG, "canTakeNewPhoto: no, as too many for nr")
                return false
            }
        }
        if (nJpegs > 1) {
            // if in any other kind of burst mode (e.g., expo burst, HDR), allow a max of 3 photos in memory
            if (nImagesToSave >= 3 * photoCost) {
                if (MyDebug.LOG) Log.d(TAG, "canTakeNewPhoto: no, as too many for burst")
                return false
            }
        }
        if (nRaw > 0) {
            // if RAW mode, allow a max of 3 photos
            if (nImagesToSave >= 3 * photoCost) {
                if (MyDebug.LOG) Log.d(TAG, "canTakeNewPhoto: no, as too many for raw")
                return false
            }
        }
        // otherwise, still have a max limit of 5 photos
        if (nImagesToSave >= 5 * photoCost) {
            if (mainActivity.supportsNoiseReduction() && nImagesToSave <= 8) {
                // if we take a photo in NR mode, then switch to std mode, it doesn't make sense to suddenly block!
                // so need to at least allow a new photo, if the number of photos is less than 1 NR photo
            } else {
                if (MyDebug.LOG) Log.d(TAG, "canTakeNewPhoto: no, as too many for regular")
                return false
            }
        }

        return true
    }

    override fun imageQueueWouldBlock(nRaw: Int, nJpegs: Int): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "imageQueueWouldBlock")
        return imageSaver.queueWouldBlock(nRaw, nJpegs)
    }

    /** Returns the ROTATION_* enum of the display relative to the natural device orientation, but
     * also checks for the preview being rotated due to user preference
     * ROTATE_PREVIEW_PREFERENCE_KEY.
     * See ApplicationInterface.getDisplayRotation() for more details, including for preferLater.
     */
    override fun getDisplayRotation(preferLater: Boolean): Int {
        // important to use cached rotation to reduce issues of incorrect focus square location when
        // rotating device, due to strange Android behaviour where rotation changes shortly before
        // the configuration actually changes
        var rotation = mainActivity.getDisplayRotation(preferLater)

        val rotatePreview =
            sharedPreferences.getString(PreferenceKeys.ROTATE_PREVIEW_PREFERENCE_KEY, "0")!!
        if (MyDebug.LOG) Log.d(
            TAG,
            "    rotate_preview = $rotatePreview"
        )
        if (rotatePreview == "180") {
            when (rotation) {
                Surface.ROTATION_0 -> rotation = Surface.ROTATION_180
                Surface.ROTATION_90 -> rotation = Surface.ROTATION_270
                Surface.ROTATION_180 -> rotation = Surface.ROTATION_0
                Surface.ROTATION_270 -> rotation = Surface.ROTATION_90
                else -> {}
            }
        }

        return rotation
    }

    override fun getExposureTimePref(): Long = sharedPreferences.getLong(
        PreferenceKeys.EXPOSURE_TIME_PREFERENCE_KEY,
        CameraController.EXPOSURE_TIME_DEFAULT
    )

    override fun setExposureTimePref(exposureTime: Long) {
        val editor = sharedPreferences.edit()
        editor.putLong(PreferenceKeys.EXPOSURE_TIME_PREFERENCE_KEY, exposureTime)
        editor.apply()
    }

    override fun getFocusDistancePref(isTargetDistance: Boolean): Float {
        return sharedPreferences.getFloat(
            if (isTargetDistance) PreferenceKeys.FOCUS_BRACKETING_TARGET_DISTANCE_PREFERENCE_KEY else PreferenceKeys.FOCUS_DISTANCE_PREFERENCE_KEY,
            0.0f
        )
    }

    override fun isFocusBracketingSourceAutoPref(): Boolean {
        if (!mainActivity.supportsFocusBracketingSourceAuto()) return false // not supported

        return sharedPreferences.getBoolean(
            PreferenceKeys.FOCUS_BRACKETING_AUTO_SOURCE_DISTANCE_PREFERENCE_KEY,
            false
        )
    }

    /** Sets whether in focus bracketing auto focusing mode for source focus distance.
     * If enabled==false (i.e. returning to manual mode), the caller should call Preview.setFocusDistance()
     * to set the new manual focus distance.
     */
    fun setFocusBracketingSourceAutoPref(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean(PreferenceKeys.FOCUS_BRACKETING_AUTO_SOURCE_DISTANCE_PREFERENCE_KEY, enabled)
        }
        if (mainActivity.preview.cameraController != null) {
            mainActivity.preview.setFocusPref(true)
        }
    }

    override fun isExpoBracketingPref(): Boolean {
        val photoMode = photoMode
        return photoMode == PhotoMode.HDR || photoMode == PhotoMode.ExpoBracketing
    }

    override fun isFocusBracketingPref(): Boolean {
        val photoMode = photoMode
        return photoMode == PhotoMode.FocusBracketing
    }

    override fun isCameraBurstPref(): Boolean {
        val photoMode = photoMode
        return photoMode == PhotoMode.FastBurst || photoMode == PhotoMode.NoiseReduction
    }

    override fun getBurstNImages(): Int {
        val photoMode = photoMode
        if (photoMode == PhotoMode.FastBurst) {
            val nImagesValue =
                sharedPreferences.getString(PreferenceKeys.FAST_BURST_N_IMAGES_PREFERENCE_KEY, "5")!!
            var nImages: Int
            try {
                nImages = nImagesValue.toInt()
            } catch (e: NumberFormatException) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "failed to parse FAST_BURST_N_IMAGES_PREFERENCE_KEY value: $nImagesValue"
                )
                e.printStackTrace()
                nImages = 5
            }
            return nImages
        }
        return 1
    }

    override fun getBurstForNoiseReduction(): Boolean {
        val photoMode = photoMode
        return photoMode == PhotoMode.NoiseReduction
    }

    override fun getNRModePref(): ApplicationInterface.NRModePref {
        /*if( MyDebug.LOG )
            Log.d(TAG, "nrMode: " + nrMode);*/
        when (nRMode) {
            "preference_nr_mode_low_light" -> return ApplicationInterface.NRModePref.NRMODE_LOW_LIGHT
        }
        return ApplicationInterface.NRModePref.NRMODE_NORMAL
    }

    override fun isCameraExtensionPref(): Boolean {
        val photoMode = photoMode
        return photoMode == PhotoMode.X_Auto || photoMode == PhotoMode.X_HDR || photoMode == PhotoMode.X_Night || photoMode == PhotoMode.X_Bokeh || photoMode == PhotoMode.X_Beauty
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    override fun getCameraExtensionPref(): Int {
        val photoMode = photoMode
        if (photoMode == PhotoMode.X_Auto) {
            return CameraExtensionCharacteristics.EXTENSION_AUTOMATIC
        } else if (photoMode == PhotoMode.X_HDR) {
            return CameraExtensionCharacteristics.EXTENSION_HDR
        } else if (photoMode == PhotoMode.X_Night) {
            return CameraExtensionCharacteristics.EXTENSION_NIGHT
        } else if (photoMode == PhotoMode.X_Bokeh) {
            return CameraExtensionCharacteristics.EXTENSION_BOKEH
        } else if (photoMode == PhotoMode.X_Beauty) {
            return CameraExtensionCharacteristics.EXTENSION_BEAUTY
        }
        return 0
    }

    fun setAperture(aperture: Float) {
        this._aperturePref = aperture
    }

    override fun getExpoBracketingNImagesPref(): Int {
        if (MyDebug.LOG) Log.d(TAG, "getExpoBracketingNImagesPref")
        var nImages: Int
        val photoMode = photoMode
        if (photoMode == PhotoMode.HDR) {
            // always set 3 images for HDR
            nImages = 3
        } else {
            val nImagesS =
                sharedPreferences.getString(
                    PreferenceKeys.EXPO_BRACKETING_N_IMAGES_PREFERENCE_KEY,
                    "3"
                )!!
            try {
                nImages = nImagesS.toInt()
            } catch (exception: NumberFormatException) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "n_images_s invalid format: $nImagesS"
                )
                nImages = 3
            }
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "n_images = $nImages"
        )
        return nImages
    }

    override fun getExpoBracketingStopsPref(): Double {
        if (MyDebug.LOG) Log.d(TAG, "getExpoBracketingStopsPref")
        var nStops: Double
        val photoMode = photoMode
        if (photoMode == PhotoMode.HDR) {
            // always set 2 stops for HDR
            nStops = 2.0
        } else {
            val nStopsS =
                sharedPreferences.getString(
                    PreferenceKeys.EXPO_BRACKETING_STOPS_PREFERENCE_KEY,
                    "2"
                )!!
            try {
                nStops = nStopsS.toDouble()
            } catch (exception: NumberFormatException) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "n_stops_s invalid format: $nStopsS"
                )
                nStops = 2.0
            }
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "n_stops = $nStops"
        )
        return nStops
    }

    override fun getFocusBracketingNImagesPref(): Int {
        if (MyDebug.LOG) Log.d(TAG, "getFocusBracketingNImagesPref")
        var nImages: Int
        val nImagesS =
            sharedPreferences.getString(
                PreferenceKeys.FOCUS_BRACKETING_N_IMAGES_PREFERENCE_KEY,
                "3"
            )!!
        try {
            nImages = nImagesS.toInt()
        } catch (exception: NumberFormatException) {
            if (MyDebug.LOG) Log.e(
                TAG,
                "n_images_s invalid format: $nImagesS"
            )
            nImages = 3
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "n_images = $nImages"
        )
        return nImages
    }

    override fun getFocusBracketingAddInfinityPref(): Boolean = sharedPreferences.getBoolean(
        PreferenceKeys.FOCUS_BRACKETING_ADD_INFINITY_PREFERENCE_KEY,
        false
    )

    val photoMode: PhotoMode
        /** Returns the current photo mode.
         * Note, this always should return the true photo mode - if we're in video mode and taking a photo snapshot while
         * video recording, the caller should override. We don't override here, as this preference may be used to affect how
         * the CameraController is set up, and we don't always re-setup the camera when switching between photo and video modes.
         */
        get() {
            val photoModePref =
                sharedPreferences.getString(
                    PreferenceKeys.PHOTO_MODE_PREFERENCE_KEY,
                    "preference_photo_mode_std"
                )!!
            /*if( MyDebug.LOG )
                Log.d(TAG, "photoModePref: " + photoModePref);*/
            val dro = photoModePref == "preference_photo_mode_dro"
            if (dro && mainActivity.supportsDRO()) return PhotoMode.DRO
            val hdr = photoModePref == "preference_photo_mode_hdr"
            if (hdr && mainActivity.supportsHDR()) return PhotoMode.HDR
            val expoBracketing = photoModePref == "preference_photo_mode_expo_bracketing"
            if (expoBracketing && mainActivity.supportsExpoBracketing()) return PhotoMode.ExpoBracketing
            val focusBracketing = photoModePref == "preference_photo_mode_focus_bracketing"
            if (focusBracketing && mainActivity.supportsFocusBracketing()) return PhotoMode.FocusBracketing
            val fastBurst = photoModePref == "preference_photo_mode_fast_burst"
            if (fastBurst && mainActivity.supportsFastBurst()) return PhotoMode.FastBurst
            val noiseReduction = photoModePref == "preference_photo_mode_noise_reduction"
            if (noiseReduction && mainActivity.supportsNoiseReduction()) return PhotoMode.NoiseReduction
            val panorama = photoModePref == "preference_photo_mode_panorama"
            if (panorama && !mainActivity.preview
                    .isVideo && mainActivity.supportsPanorama()
            ) return PhotoMode.Panorama
            val xAuto = photoModePref == "preference_photo_mode_x_auto"
            if (xAuto && !mainActivity.preview
                    .isVideo && mainActivity.supportsCameraExtension(
                    CameraExtensionCharacteristics.EXTENSION_AUTOMATIC
                )
            ) return PhotoMode.X_Auto
            val xHdr = photoModePref == "preference_photo_mode_x_hdr"
            if (xHdr && !mainActivity.preview
                    .isVideo && mainActivity.supportsCameraExtension(
                    CameraExtensionCharacteristics.EXTENSION_HDR
                )
            ) return PhotoMode.X_HDR
            val xNight = photoModePref == "preference_photo_mode_x_night"
            if (xNight && !mainActivity.preview
                    .isVideo && mainActivity.supportsCameraExtension(
                    CameraExtensionCharacteristics.EXTENSION_NIGHT
                )
            ) return PhotoMode.X_Night
            val xBokeh = photoModePref == "preference_photo_mode_x_bokeh"
            if (xBokeh && !mainActivity.preview
                    .isVideo && mainActivity.supportsCameraExtension(
                    CameraExtensionCharacteristics.EXTENSION_BOKEH
                )
            ) return PhotoMode.X_Bokeh
            val xBeauty = photoModePref == "preference_photo_mode_x_beauty"
            if (xBeauty && !mainActivity.preview
                    .isVideo && mainActivity.supportsCameraExtension(
                    CameraExtensionCharacteristics.EXTENSION_BEAUTY
                )
            ) return PhotoMode.X_Beauty
            return PhotoMode.Standard
        }

    override fun getJpegRPref(): Boolean {
        if (sharedPreferences.getString(
                PreferenceKeys.IMAGE_FORMAT_PREFERENCE_KEY,
                "preference_image_format_jpeg"
            ) == "preference_image_format_jpeg_r"
        ) {
            if (mainActivity.preview.isVideo) {
                // don't support JPEG R, either for video recording or video snapshot - problem that video recording fails
                // if CameraController2 sets "config.setDynamicRangeProfile(DynamicRangeProfiles.HLG10);" for the preview
                return false
            } else {
                val photoMode = photoMode
                if (photoMode == PhotoMode.NoiseReduction || photoMode == PhotoMode.HDR || photoMode == PhotoMode.Panorama) return false // not supported for these photo modes

                // n.b., JPEG R won't be supported by x- extension modes either, although this is automatically handled by Preview
                return true
            }
        }
        return false
    }

    private val imageFormatPref: ImageSaver.Request.ImageFormat
        get() {
            return when (sharedPreferences.getString(
                PreferenceKeys.IMAGE_FORMAT_PREFERENCE_KEY,
                "preference_image_format_jpeg"
            )) {
                "preference_image_format_webp" -> ImageSaver.Request.ImageFormat.WEBP
                "preference_image_format_png" -> ImageSaver.Request.ImageFormat.PNG
                else -> ImageSaver.Request.ImageFormat.STD
            }
        }

    /** Returns whether RAW is currently allowed, even if RAW is enabled in the preference (RAW
     * isn't allowed for some photo modes, or in video mode, or when called from an intent).
     * Note that this doesn't check whether RAW is supported by the camera.
     */
    fun isRawAllowed(photoMode: PhotoMode): Boolean {
        if (isImageCaptureIntent) return false
        if (mainActivity.preview.isVideo) return false // video snapshot mode

        //return photoMode == PhotoMode.Standard || photoMode == PhotoMode.DRO;
        if (photoMode == PhotoMode.Standard || photoMode == PhotoMode.DRO) {
            return true
        } else if (photoMode == PhotoMode.ExpoBracketing) {
            return sharedPreferences.getBoolean(
                PreferenceKeys.ALLOW_RAW_FOR_EXPO_BRACKETING_PREFERENCE_KEY,
                true
            ) &&
                    mainActivity.supportsBurstRaw()
        } else if (photoMode == PhotoMode.HDR) {
            // for HDR, RAW is only relevant if we're going to be saving the base expo images (otherwise there's nothing to save)
            return sharedPreferences.getBoolean(PreferenceKeys.HDR_SAVE_EXPO_PREFERENCE_KEY, false) &&
                    sharedPreferences.getBoolean(
                        PreferenceKeys.ALLOW_RAW_FOR_EXPO_BRACKETING_PREFERENCE_KEY,
                        true
                    ) &&
                    mainActivity.supportsBurstRaw()
        } else if (photoMode == PhotoMode.FocusBracketing) {
            return sharedPreferences.getBoolean(
                PreferenceKeys.ALLOW_RAW_FOR_FOCUS_BRACKETING_PREFERENCE_KEY,
                true
            ) &&
                    mainActivity.supportsBurstRaw()
        }
        // not supported for panorama mode
        // not supported for camera vendor extensions
        return false
    }

    override fun getRawPref(): RawPref {
        val photoMode = photoMode
        if (isRawAllowed(photoMode)) {
            val isRaw = settingsRepository?.isRawEnabled()
            if (isRaw == true) {
                return RawPref.RAWPREF_JPEG_DNG
            }
            when (sharedPreferences.getString(
                PreferenceKeys.RAW_PREFERENCE_KEY,
                "preference_raw_no"
            )) {
                "preference_raw_yes", "preference_raw_only" -> return RawPref.RAWPREF_JPEG_DNG
            }
        }
        return RawPref.RAWPREF_JPEG_ONLY
    }

    val isRawOnly: Boolean
        /** Whether RAW only mode is enabled.
         */
        get() {
            val photoMode = photoMode
            return isRawOnly(photoMode)
        }

    /** Use this instead of isRawOnly() if the photo mode is already known - useful to call e.g. from MainActivity.supportsDRO()
     * without causing an infinite loop!
     */
    fun isRawOnly(photoMode: PhotoMode): Boolean {
        if (isRawAllowed(photoMode)) {
            when (sharedPreferences.getString(
                PreferenceKeys.RAW_PREFERENCE_KEY,
                "preference_raw_no"
            )) {
                "preference_raw_only" -> return true
            }
        }
        return false
    }

    override fun getMaxRawImages(): Int = imageSaver.maxDNG

    override fun useCamera2FakeFlash(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.CAMERA2_FAKE_FLASH_PREFERENCE_KEY, false)
    }

    override fun useCamera2DummyCaptureHack(): Boolean {
        return sharedPreferences.getBoolean(
            PreferenceKeys.CAMERA2_DUMMY_CAPTURE_HACK_PREFERENCE_KEY,
            false
        )
    }

    override fun useCamera2FastBurst(): Boolean {
        return sharedPreferences.getBoolean(PreferenceKeys.CAMERA2_FAST_BURST_PREFERENCE_KEY, true)
    }

    override fun usePhotoVideoRecording(): Boolean {
        // we only show the preference for Camera2 API (since there's no point disabling the feature for old API)
        if (!useCamera2()) return true
        return sharedPreferences.getBoolean(
            PreferenceKeys.CAMERA2_PHOTO_VIDEO_RECORDING_PREFERENCE_KEY,
            true
        )
    }

    override fun isPreviewInBackground(): Boolean = mainActivity.isCameraInBackground

    override fun allowZoom(): Boolean {
        if (photoMode == PhotoMode.Panorama) {
            // don't allow zooming in panorama mode, the algorithm isn't set up to support this!
            return false
        } else if (isCameraExtensionPref() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !mainActivity.preview
                .supportsZoomForCameraExtension(getCameraExtensionPref())
        ) {
            // zoom not supported for camera extension
            return false
        }
        return true
    }

    override fun optimiseFocusForLatency(): Boolean {
        val pref =
            sharedPreferences.getString(
                PreferenceKeys.OPTIMISE_FOCUS_PREFERENCE_KEY,
                "preference_photo_optimise_focus_latency"
            )!!
        return pref == "preference_photo_optimise_focus_latency" && mainActivity.supportsOptimiseFocusLatency()
    }

    override fun getDisplaySize(displaySize: Point, excludeInsets: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = mainActivity.windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            if (!mainActivity.edgeToEdgeMode || excludeInsets) {
                // use non-deprecated equivalent of Display.getSize()
                val windowInsets = windowMetrics.windowInsets
                val insets =
                    windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
                val insetsWidth = insets.right + insets.left
                val insetsHeight = insets.top + insets.bottom
                displaySize.x = bounds.width() - insetsWidth
                displaySize.y = bounds.height() - insetsHeight
            } else {
                displaySize.x = bounds.width()
                displaySize.y = bounds.height()
            }
        } else {
            val display = mainActivity.windowManager.defaultDisplay
            display.getSize(displaySize)
        }
    }

    override fun isTestAlwaysFocus(): Boolean {
        if (MyDebug.LOG) {
            Log.d(TAG, "isTestAlwaysFocus: " + mainActivity.isTest)
        }
        return mainActivity.isTest
    }

    override fun cameraSetup() {
        mainActivity.cameraSetup()
        drawPreview.clearContinuousFocusMove()
        // Need to cause drawPreview.updateSettings(), otherwise icons like HDR won't show after force-restart, because we only
        // know that HDR is supported after the camera is opened
        // Also needed for settings which update when switching between photo and video mode.
        drawPreview.updateSettings()
    }

    override fun onContinuousFocusMove(start: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "onContinuousFocusMove: $start"
        )
        drawPreview.onContinuousFocusMove(start)
    }

    fun startPanorama() {
        if (MyDebug.LOG) Log.d(TAG, "startPanorama")
        gyroSensor.startRecording()
        nPanoramaPics = 0
        panoramaPicAccepted = false
        panoramaDirLeftToRight = true

        mainActivity.mainUI.setTakePhotoIcon()
        val cancelPanoramaButton: View = mainActivity.findViewById(R.id.cancel_panorama)
        cancelPanoramaButton.visibility = View.VISIBLE
        mainActivity.mainUI
            .closeExposureUI() // close seekbars if open (popup is already closed when taking a photo)
        // taking the photo will end up calling MainUI.showGUI(), which will hide the other on-screen icons
    }

    /** Ends panorama and submits the panoramic images to be processed.
     */
    fun finishPanorama() {
        if (MyDebug.LOG) Log.d(TAG, "finishPanorama")

        imageSaver.imageBatchRequest?.panoramaDirLeftToRight = this.panoramaDirLeftToRight

        stopPanorama(false)

        val imageCaptureIntent = isImageCaptureIntent
        val doInBackground = saveInBackground(imageCaptureIntent)
        imageSaver.finishImageBatch(doInBackground)
    }

    /** Stop the panorama recording. Does nothing if panorama isn't currently recording.
     * @param isCancelled Whether the panorama has been cancelled.
     */
    fun stopPanorama(isCancelled: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "stopPanorama")
        if (!gyroSensor.isRecording) {
            if (MyDebug.LOG) Log.d(TAG, "...nothing to stop")
            return
        }
        gyroSensor.stopRecording()
        clearPanoramaPoint()
        if (isCancelled) {
            imageSaver.flushImageBatch()
        }
        mainActivity.mainUI.setTakePhotoIcon()
        val cancelPanoramaButton: View = mainActivity.findViewById(R.id.cancel_panorama)
        cancelPanoramaButton.visibility = View.GONE
        mainActivity.mainUI.showGUI() // refresh UI icons now that we've stopped panorama
    }

    private fun setNextPanoramaPoint(repeat: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "setNextPanoramaPoint")
        val cameraAngleY = mainActivity.preview.getViewAngleY(false)
        if (!repeat) nPanoramaPics++
        if (MyDebug.LOG) Log.d(
            TAG,
            "n_panorama_pics is now: $nPanoramaPics"
        )
        if (nPanoramaPics == maxPanoramaPicsC) {
            if (MyDebug.LOG) Log.d(TAG, "reached max panorama limit")
            finishPanorama()
            return
        }
        var angle = Math.toRadians(cameraAngleY.toDouble()).toFloat() * nPanoramaPics
        if (nPanoramaPics > 1 && !panoramaDirLeftToRight) {
            angle = -angle // for right-to-left
        }
        var x = sin((angle / panoramaPicsPerScreen).toDouble()).toFloat()
        var z = -cos((angle / panoramaPicsPerScreen).toDouble()).toFloat()
        setNextPanoramaPoint(x, 0.0f, z)

        if (nPanoramaPics == 1) {
            // also set target for right-to-left
            angle = -angle
            x = sin((angle / panoramaPicsPerScreen).toDouble()).toFloat()
            z = -cos((angle / panoramaPicsPerScreen).toDouble()).toFloat()
            gyroSensor.addTarget(x, 0.0f, z)
            drawPreview.addGyroDirectionMarker(x, 0.0f, z)
        }
    }

    private fun setNextPanoramaPoint(x: Float, y: Float, z: Float) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setNextPanoramaPoint : $x , $y , $z"
        )

        val targetAngle = 1.0f * 0.01745329252f
        //final float targetAngle = 0.5f * 0.01745329252f;
        // good to not allow too small an angle for uprightAngleTol - as sometimes the device may
        // get in a state where what we think is upright isn't quite right, and frustrating for users
        // to be told they have to tilt to not be upright
        val uprightAngleTol = 3.0f * 0.017452406437f
        //final float uprightAngleTol = 2.0f * 0.017452406437f;
        val tooFarAngle = 45.0f * 0.01745329252f
        gyroSensor.setTarget(
            x,
            y,
            z,
            targetAngle,
            uprightAngleTol,
            tooFarAngle,
            object : GyroSensor.TargetCallback {
                override fun onAchieved(indx: Int) {
                    if (MyDebug.LOG) {
                        Log.d(
                            TAG,
                            "TargetCallback.onAchieved: $indx"
                        )
                        Log.d(
                            TAG,
                            "    n_panorama_pics: $nPanoramaPics"
                        )
                    }
                    // Disable the target callback so we avoid risk of multiple callbacks - but note we don't call
                    // clearPanoramaPoint(), as we don't want to call drawPreview.clearGyroDirectionMarker()
                    // at this stage (looks better to keep showing the target market on-screen whilst photo
                    // is being taken, user more likely to keep the device still).
                    // Also we still keep the target active (and don't call clearTarget() so we can monitor if
                    // the target is still achieved or not (for panoramaPicAccepted).
                    //gyroSensor.clearTarget();
                    gyroSensor.disableTargetCallback()
                    if (nPanoramaPics == 1) {
                        panoramaDirLeftToRight = indx == 0
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "set panorama_dir_left_to_right to $panoramaDirLeftToRight"
                        )
                    }
                    mainActivity.takePicturePressed(false, false)
                }

                override fun onTooFar() {
                    if (MyDebug.LOG) Log.d(TAG, "TargetCallback.onTooFar")

                    // it's better not to cancel the panorama if the user moves the device too far in wrong direction
                    /*if( !main_activity.isTest ) {
                    main_activity.preview.showToast(null, R.string.panorama_cancelled, true);
                    MyApplicationInterface.this.stopPanorama(true);
                }*/
                }
            })
        drawPreview.setGyroDirectionMarker(x, y, z)
    }

    private fun clearPanoramaPoint() {
        if (MyDebug.LOG) Log.d(TAG, "clearPanoramaPoint")
        gyroSensor.clearTarget()
        drawPreview.clearGyroDirectionMarker()
    }

    override fun touchEvent(event: MotionEvent?) {
        mainActivity.mainUI.closeExposureUI()
        mainActivity.mainUI.closePopup()
        if (mainActivity.usingKitKatImmersiveMode()) {
            mainActivity.setImmersiveMode(false)
        }
    }

    override fun startingVideo() {
        if (sharedPreferences.getBoolean(PreferenceKeys.LOCK_VIDEO_PREFERENCE_KEY, false)) {
            mainActivity.lockScreen()
        }
        mainActivity.stopAudioListeners() // important otherwise MediaRecorder will fail to start() if we have an audiolistener! Also don't want to have the speech recognizer going off
        val view: ImageButton = mainActivity.findViewById(R.id.take_photo)
        view.setImageResource(R.drawable.take_video_recording)
        view.contentDescription = context.resources.getString(R.string.stop_video)
        view.tag = R.drawable.take_video_recording // for testing
        mainActivity.mainUI
            .destroyPopup() // as the available popup options change while recording video
    }

    private fun startVideoSubtitlesTask(videoMethod: VideoMethod) {
        val preferenceStampDateformat = this.stampDateFormatPref
        val preferenceStampTimeformat = this.stampTimeFormatPref
        val preferenceStampGpsformat = this.stampGPSFormatPref
        val preferenceUnitsDistance = this.unitsDistancePref
        //final String preferenceStampGeoAddress = this.getStampGeoAddressPref();
        val storeLocation = getGeotaggingPref()
        val storeGeoDirection = geodirectionPref

        class SubtitleVideoTimerTask : TimerTask() {
            // need to keep a reference to pfdSaf for as long as writer, to avoid getting garbage collected - see https://sourceforge.net/p/OpenKamera/tickets/417/
            private var pfdSaf: ParcelFileDescriptor? = null
            private var writer: OutputStreamWriter? = null
            private var uri: Uri? = null
            private var count = 1
            private var minVideoTimeFrom: Long = 0

            fun getSubtitleFilename(videoFilename: String): String {
                var videoFilename = videoFilename
                if (MyDebug.LOG) Log.d(TAG, "getSubtitleFilename")
                val indx = videoFilename.indexOf('.')
                if (indx != -1) {
                    videoFilename = videoFilename.substring(0, indx)
                }
                videoFilename = "$videoFilename.srt"
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "return filename: $videoFilename"
                )
                return videoFilename
            }

            override fun run() {
                if (MyDebug.LOG) Log.d(TAG, "SubtitleVideoTimerTask run")
                val videoTime = mainActivity.preview
                    .getVideoTime(true) // n.b., in case of restarts due to max filesize, we only want the time for this video file!
                if (!mainActivity.preview.isVideoRecording) {
                    if (MyDebug.LOG) Log.d(TAG, "no longer video recording")
                    return
                }
                if (mainActivity.preview.isVideoRecordingPaused) {
                    if (MyDebug.LOG) Log.d(TAG, "video recording is paused")
                    return
                }
                val currentDate = Date()
                val currentCalendar = Calendar.getInstance()
                val offsetMs = currentCalendar[Calendar.MILLISECOND]
                // We subtract an offset, because if the current time is say 00:00:03.425 and the video has been recording for
                // 1s, we instead need to record the video time when it became 00:00:03.000. This does mean that the GPS
                // location is going to be off by up to 1s, but that should be less noticeable than the clock being off.
                if (MyDebug.LOG) {
                    Log.d(TAG, "count: $count")
                    Log.d(
                        TAG,
                        "offset_ms: $offsetMs"
                    )
                    Log.d(
                        TAG,
                        "video_time: $videoTime"
                    )
                }
                val dateStamp: String =
                    TextFormatter.getDateString(preferenceStampDateformat, currentDate)
                val timeStamp: String =
                    TextFormatter.getTimeString(preferenceStampTimeformat, currentDate)
                val location: Location? =
                    if (storeLocation) this@MyApplicationInterface.getLocation() else null
                val geoDirection =
                    if (storeGeoDirection && mainActivity.preview.hasGeoDirection()
                    ) mainActivity.preview.geoDirection else 0.0
                val gpsStamp: String = mainActivity.textFormatter.getGPSString(
                    preferenceStampGpsformat,
                    preferenceUnitsDistance,
                    storeLocation && location != null,
                    location,
                    storeGeoDirection && mainActivity.preview.hasGeoDirection(),
                    geoDirection
                )
                if (MyDebug.LOG) {
                    Log.d(
                        TAG,
                        "date_stamp: $dateStamp"
                    )
                    Log.d(
                        TAG,
                        "time_stamp: $timeStamp"
                    )
                    // don't log gpsStamp, in case of privacy!
                }

                var datetimeStamp = ""
                if (dateStamp.length > 0) datetimeStamp += dateStamp
                if (timeStamp.length > 0) {
                    if (datetimeStamp.length > 0) datetimeStamp += " "
                    datetimeStamp += timeStamp
                }

                // build subtitles
                val subtitles = StringBuilder()
                if (datetimeStamp.length > 0) subtitles.append(datetimeStamp).append("\n")

                if (gpsStamp.length > 0) {
                    /*Address address = null;
                    if( storeLocation && !preference_stamp_geo_address.equals("preference_stamp_geo_address_no") ) {
                        // try to find an address
                        if( main_activity.isAppPaused() ) {
                            // seems safer to not try to initiate potential network connections (via geocoder) if Open Kamera
                            // is paused - this shouldn't happen, since we stop video when paused, but just to be safe
                            if( MyDebug.LOG )
                                Log.d(TAG, "don't call geocoder for video subtitles  as app is paused?!");
                        }
                        else if( Geocoder.isPresent() ) {
                            if( MyDebug.LOG )
                                Log.d(TAG, "geocoder is present");
                            Geocoder geocoder = new Geocoder(mainActivity, Locale.getDefault());
                            try {
                                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                                if( addresses != null && addresses.size() > 0 ) {
                                    address = addresses.get(0);
                                    // don't log address, in case of privacy!
                                    if( MyDebug.LOG ) {
                                        Log.d(TAG, "max line index: " + address.getMaxAddressLineIndex());
                                    }
                                }
                            }
                            catch(Exception e) {
                                Log.e(TAG, "failed to read from geocoder");
                                e.printStackTrace();
                            }
                        }
                        else {
                            if( MyDebug.LOG )
                                Log.d(TAG, "geocoder not present");
                        }
                    }

                    if( address != null ) {
                        for(int i=0;i<=address.getMaxAddressLineIndex();i++) {
                            // write in forward order
                            String addressLine = address.getAddressLine(i);
                            subtitles.append(addressLine).append("\n");
                        }
                    }*/

                    //if( address == null || preference_stamp_geo_address.equals("preference_stamp_geo_address_both") )

                    run {
                        if (MyDebug.LOG) Log.d(TAG, "display gps coords")
                        subtitles.append(gpsStamp).append("\n")
                    }
                    /*else if( storeGeoDirection ) {
                        if( MyDebug.LOG )
                            Log.d(TAG, "not displaying gps coords, but need to display geo direction");
                        gpsStamp = main_activity.textFormatter.getGPSString(preferenceStampGpsformat, preferenceUnitsDistance, false, null, storeGeoDirection && main_activity.preview.hasGeoDirection(), geoDirection);
                        if( gps_stamp.length() > 0 ) {
                            // don't log gpsStamp, in case of privacy!
                            subtitles.append(gpsStamp).append("\n");
                        }
                    }*/
                }

                if (subtitles.length == 0) {
                    return
                }
                var videoTimeFrom = videoTime - offsetMs
                val videoTimeTo = videoTimeFrom + 999
                // don't want to start from before 0; also need to keep track of minVideoTimeFrom to avoid bug reported at
                // https://forum.xda-developers.com/showpost.php?p=74827802&postcount=345 for pause video where we ended up
                // with overlapping times when resuming
                if (videoTimeFrom < minVideoTimeFrom) videoTimeFrom = minVideoTimeFrom
                minVideoTimeFrom = videoTimeTo + 1
                val subtitleTimeFrom: String = TextFormatter.formatTimeMS(videoTimeFrom)
                val subtitleTimeTo: String = TextFormatter.formatTimeMS(videoTimeTo)
                try {
                    synchronized(this) {
                        if (writer == null) {
                            if (videoMethod === VideoMethod.FILE) {
                                var subtitleFilename = lastVideoFile!!.absolutePath
                                subtitleFilename = getSubtitleFilename(subtitleFilename)
                                writer = FileWriter(subtitleFilename)
                            } else if (videoMethod === VideoMethod.SAF || videoMethod === VideoMethod.MEDIASTORE) {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "last_video_file_uri: $lastVideoFileUri"
                                )
                                var subtitleFilename: String =
                                    storageUtils.getFileName(lastVideoFileUri!!)
                                subtitleFilename = getSubtitleFilename(subtitleFilename)
                                if (videoMethod === VideoMethod.SAF) {
                                    uri = storageUtils.createOutputFileSAF(
                                        subtitleFilename,
                                        ""
                                    ) // don't set a mimetype, as we don't want it to append a new extension
                                } else {
                                    val folder =
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(
                                            MediaStore.VOLUME_EXTERNAL_PRIMARY
                                        ) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                    val contentValues = ContentValues()
                                    contentValues.put(
                                        MediaStore.Video.Media.DISPLAY_NAME,
                                        subtitleFilename
                                    )
                                    // set mime type - it's unclear if .SRT files have an official mime type, but (a) we must set a mime type otherwise
                                    // resultant files are named "*.srt.mp4", and (b) the mime type must be video/*, otherwise we get exception:
                                    // "java.lang.IllegalArgumentException: MIME type text/plain cannot be inserted into content://media/externalPrimary/video/media; expected MIME type under video/*"
                                    // and we need the file to be saved in the same folder (in DCIM/ ) as the video
                                    contentValues.put(
                                        MediaStore.Images.Media.MIME_TYPE,
                                        "video/x-srt"
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        val relativePath: String =
                                            storageUtils.saveRelativeFolder
                                        if (MyDebug.LOG) Log.d(
                                            TAG,
                                            "relative_path: $relativePath"
                                        )
                                        contentValues.put(
                                            MediaStore.Video.Media.RELATIVE_PATH,
                                            relativePath
                                        )
                                        contentValues.put(MediaStore.Video.Media.IS_PENDING, 1)
                                    }

                                    // Note, we catch exceptions specific to insert() here and rethrow as IOException,
                                    // rather than catching below, to avoid catching things too broadly.
                                    // Catching too broadly could mean we miss genuine problems that should be fixed.
                                    try {
                                        uri = mainActivity.contentResolver.insert(
                                            folder,
                                            contentValues
                                        )
                                    } catch (e: IllegalArgumentException) {
                                        // can happen for mediastore method if invalid ContentResolver.insert() call
                                        if (MyDebug.LOG) Log.e(
                                            TAG,
                                            "IllegalArgumentException from SubtitleVideoTimerTask inserting to mediastore: " + e.message
                                        )
                                        e.printStackTrace()
                                        throw IOException()
                                    } catch (e: IllegalStateException) {
                                        if (MyDebug.LOG) Log.e(
                                            TAG,
                                            "IllegalStateException from SubtitleVideoTimerTask inserting to mediastore: " + e.message
                                        )
                                        e.printStackTrace()
                                        throw IOException()
                                    }
                                    if (uri == null) {
                                        throw IOException()
                                    }
                                }
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "uri: $uri"
                                )
                                pfdSaf =
                                    this@MyApplicationInterface.context.contentResolver.openFileDescriptor(
                                        uri!!,
                                        "w"
                                    )
                                writer = FileWriter(pfdSaf!!.fileDescriptor)
                            }
                        }
                        if (writer != null) {
                            writer!!.append(count.toString())
                            writer!!.append('\n')
                            writer!!.append(subtitleTimeFrom)
                            writer!!.append(" --> ")
                            writer!!.append(subtitleTimeTo)
                            writer!!.append('\n')
                            writer!!.append(subtitles.toString()) // subtitles should include the '\n' at the end
                            writer!!.append('\n') // additional newline to indicate end of this subtitle
                            writer!!.flush()
                            // n.b., we flush rather than closing/reopening the writer each time, as appending doesn't seem to work with storage access framework
                        }
                    }
                    count++
                } catch (e: IOException) {
                    if (MyDebug.LOG) Log.e(TAG, "SubtitleVideoTimerTask failed to create or write")
                    e.printStackTrace()
                }
                if (MyDebug.LOG) Log.d(TAG, "SubtitleVideoTimerTask exit")
            }

            override fun cancel(): Boolean {
                if (MyDebug.LOG) Log.d(TAG, "SubtitleVideoTimerTask cancel")
                synchronized(this) {
                    if (writer != null) {
                        if (MyDebug.LOG) Log.d(TAG, "close writer")
                        try {
                            writer!!.close()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                        writer = null
                    }
                    if (pfdSaf != null) {
                        try {
                            pfdSaf!!.close()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                        pfdSaf = null
                    }
                    if (videoMethod === VideoMethod.MEDIASTORE) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val contentValues = ContentValues()
                            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                            mainActivity.contentResolver.update(uri!!, contentValues, null, null)
                        }
                    }
                }
                return super.cancel()
            }
        }
        subtitleVideoTimer.schedule(
            SubtitleVideoTimerTask().also { subtitleVideoTimerTask = it },
            0,
            1000
        )
    }

    override fun startedVideo() {
        if (MyDebug.LOG) Log.d(TAG, "startedVideo()")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!(mainActivity.mainUI
                    .inImmersiveMode() && mainActivity.usingKitKatImmersiveModeEverything())
            ) {
                val pauseVideoButton: View = mainActivity.findViewById(R.id.pause_video)
                pauseVideoButton.visibility = View.VISIBLE
            }
            mainActivity.mainUI.setPauseVideoContentDescription()
        }
        if (mainActivity.preview
                .supportsPhotoVideoRecording() && this.usePhotoVideoRecording()
        ) {
            if (!(mainActivity.mainUI
                    .inImmersiveMode() && mainActivity.usingKitKatImmersiveModeEverything())
            ) {
                val takePhotoVideoButton: View =
                    mainActivity.findViewById(R.id.take_photo_when_video_recording)
                takePhotoVideoButton.visibility = View.VISIBLE
            }
        }
        if (mainActivity.mainUI.isExposureUIOpen) {
            if (MyDebug.LOG) Log.d(TAG, "need to update exposure UI for start video recording")
            // need to update the exposure UI when starting/stopping video recording, to remove/add
            // ability to switch between auto and manual
            mainActivity.mainUI.setupExposureUI()
        }
        val videoMethod: VideoMethod = this.createOutputVideoMethod()
        val dategeoSubtitles =
            getVideoSubtitlePref(videoMethod) == "preference_video_subtitle_yes"
        if (dategeoSubtitles && videoMethod !== VideoMethod.URI) {
            startVideoSubtitlesTask(videoMethod)
        }
    }

    override fun stoppingVideo() {
        if (MyDebug.LOG) Log.d(TAG, "stoppingVideo()")
        mainActivity.unlockScreen()
        val view: ImageButton = mainActivity.findViewById(R.id.take_photo)
        view.setImageResource(R.drawable.take_video_selector)
        view.contentDescription = context.resources.getString(R.string.start_video)
        view.tag = R.drawable.take_video_selector // for testing
    }

    override fun stoppedVideo(videoMethod: VideoMethod, uri: Uri?, filename: String?) {
        if (MyDebug.LOG) {
            Log.d(TAG, "stoppedVideo")
            Log.d(TAG, "video_method $videoMethod")
            Log.d(TAG, "uri $uri")
            Log.d(TAG, "filename $filename")
        }
        val pauseVideoButton: View = mainActivity.findViewById(R.id.pause_video)
        pauseVideoButton.visibility = View.GONE
        val takePhotoVideoButton: View =
            mainActivity.findViewById(R.id.take_photo_when_video_recording)
        takePhotoVideoButton.visibility = View.GONE
        mainActivity.mainUI.setPauseVideoContentDescription() // just to be safe
        mainActivity.mainUI
            .destroyPopup() // as the available popup options change while recording video
        if (mainActivity.mainUI.isExposureUIOpen) {
            if (MyDebug.LOG) Log.d(TAG, "need to update exposure UI for stop video recording")
            // need to update the exposure UI when starting/stopping video recording, to remove/add
            // ability to switch between auto and manual
            mainActivity.mainUI.setupExposureUI()
        }
        if (subtitleVideoTimerTask != null) {
            subtitleVideoTimerTask!!.cancel()
            subtitleVideoTimerTask = null
        }

        completeVideo(videoMethod, uri)
        val done = broadcastVideo(videoMethod, uri, filename)
        if (MyDebug.LOG) Log.d(TAG, "done? $done")

        if (isVideoCaptureIntent) {
            if (done && videoMethod === VideoMethod.FILE) {
                // do nothing here - we end the activity from storageUtils.broadcastFile after the file has been scanned, as it seems caller apps seem to prefer the content:// Uri rather than one based on a File
            } else {
                if (MyDebug.LOG) Log.d(TAG, "from video capture intent")
                var output: Intent? = null
                if (done) {
                    // may need to pass back the Uri we saved to, if the calling application didn't specify a Uri
                    // set note above for VideoMethod.FILE
                    // n.b., currently this code is not used, as we always switch to VideoMethod.FILE if the calling application didn't specify a Uri, but I've left this here for possible future behaviour
                    if (videoMethod === VideoMethod.SAF || videoMethod === VideoMethod.MEDIASTORE) {
                        output = Intent()
                        output.setData(uri)
                        if (MyDebug.LOG) Log.d(TAG, "pass back output uri [saf]: " + output.data)
                    }
                }
                mainActivity.setResult(
                    if (done) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                    output
                )
                mainActivity.finish()
            }
        } else if (done) {
            // create thumbnail
            val debugTime = System.currentTimeMillis()
            var thumbnail: Bitmap? = null
            var pfdSaf: ParcelFileDescriptor? =
                null // keep a reference to this as long as retriever, to avoid risk of pfdSaf being garbage collected
            val retriever = MediaMetadataRetriever()
            try {
                if (videoMethod === VideoMethod.FILE) {
                    val file = File(filename)
                    retriever.setDataSource(file.path)
                } else {
                    pfdSaf = context.contentResolver.openFileDescriptor(uri!!, "r")
                    retriever.setDataSource(pfdSaf!!.fileDescriptor)
                }
                thumbnail = retriever.getFrameAtTime(-1)
            } catch (e: FileNotFoundException) {
                // video file wasn't saved or corrupt video file?
                Log.d(TAG, "failed to find thumbnail")
                e.printStackTrace()
            } catch (e: RuntimeException) {
                Log.d(TAG, "failed to find thumbnail")
                e.printStackTrace()
            } finally {
                try {
                    retriever.release()
                } catch (ex: RuntimeException) {
                    // ignore
                } catch (ex: IOException) {
                }
                try {
                    pfdSaf?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            if (thumbnail != null) {
                val galleryButton: ImageButton = mainActivity.findViewById(R.id.gallery)
                val width = thumbnail.width
                val height = thumbnail.height
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "    video thumbnail size $width x $height"
                )
                if (width > galleryButton.width) {
                    val scale = galleryButton.width.toFloat() / width
                    val newWidth = Math.round(scale * width)
                    val newHeight = Math.round(scale * height)
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "    scale video thumbnail to $newWidth x $newHeight"
                    )
                    val scaledThumbnail =
                        Bitmap.createScaledBitmap(thumbnail, newWidth, newHeight, true)
                    // careful, as scaledThumbnail is sometimes not a copy!
                    if (scaledThumbnail != thumbnail) {
                        thumbnail.recycle()
                        thumbnail = scaledThumbnail
                    }
                }
                val thumbnailF: Bitmap = thumbnail
                mainActivity.runOnUiThread { updateThumbnail(thumbnailF, true) }
            }
            if (MyDebug.LOG) Log.d(
                TAG,
                "    time to create thumbnail: " + (System.currentTimeMillis() - debugTime)
            )
        }
    }

    override fun restartedVideo(videoMethod: VideoMethod, uri: Uri?, filename: String?) {
        if (MyDebug.LOG) {
            Log.d(TAG, "restartedVideo")
            Log.d(TAG, "video_method $videoMethod")
            Log.d(TAG, "uri $uri")
            Log.d(TAG, "filename $filename")
        }
        completeVideo(videoMethod, uri)
        broadcastVideo(videoMethod, uri, filename)

        // also need to restart subtitles file
        if (subtitleVideoTimerTask != null) {
            subtitleVideoTimerTask!!.cancel()
            subtitleVideoTimerTask = null

            // No need to check if option for subtitles is set, if we were already saving subtitles.
            // Assume that videoMethod is unchanged between old and new video file when restarting.
            startVideoSubtitlesTask(videoMethod)
        }
    }

    /** Called when we've finished recording to a video file, to do any necessary cleanup for the
     * file.
     */
    fun completeVideo(videoMethod: VideoMethod, uri: Uri?) {
        if (MyDebug.LOG) Log.d(TAG, "completeVideo")
        if (videoMethod === VideoMethod.MEDIASTORE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                mainActivity.contentResolver.update(uri!!, contentValues, null, null)
            }
        }
    }

    fun broadcastVideo(videoMethod: VideoMethod, uri: Uri?, filename: String?): Boolean {
        if (MyDebug.LOG) {
            Log.d(TAG, "broadcastVideo")
            Log.d(TAG, "video_method $videoMethod")
            Log.d(TAG, "uri $uri")
            Log.d(TAG, "filename $filename")
        }
        var done = false
        // clear just in case we're unable to update this - don't want an out of date cached uri
        storageUtils.clearLastMediaScanned()
        if (videoMethod === VideoMethod.MEDIASTORE) {
            // no need to broadcast when using mediastore

            if (uri != null) {
                // in theory this is pointless, as announceUri no longer does anything on Android 7+,
                // and mediastore method is only used on Android 10+, but keep this just in case
                // announceUri does something in future
                storageUtils.announceUri(uri, false, true)

                // we also want to save the uri - we can use the media uri directly, rather than having to scan it
                storageUtils.setLastMediaScanned(uri, false, false, null)

                done = true
            }
        } else if (videoMethod === VideoMethod.FILE) {
            if (filename != null) {
                val file = File(filename)
                storageUtils.broadcastFile(file, false, true, true, false, null)
                done = true
            }
        } else {
            if (uri != null) {
                // see note in onPictureTaken() for where we call broadcastFile for SAF photos
                storageUtils.broadcastUri(uri, false, true, true, false, false)
                done = true
            }
        }
        if (done) {
            testNVideosScanned++
            if (MyDebug.LOG) Log.d(
                TAG,
                "test_n_videos_scanned is now: $testNVideosScanned"
            )
        }

        if (videoMethod === VideoMethod.MEDIASTORE && isVideoCaptureIntent) {
            finishVideoIntent(uri)
        }
        return done
    }

    /** For use when called from a video capture intent. This returns the supplied uri to the
     * caller, and finishes the activity.
     */
    fun finishVideoIntent(uri: Uri?) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "finishVideoIntent:$uri"
        )
        val output = Intent()
        output.setData(uri)
        mainActivity.setResult(Activity.RESULT_OK, output)
        mainActivity.finish()
    }

    override fun deleteUnusedVideo(videoMethod: VideoMethod, uri: Uri?, filename: String?) {
        if (MyDebug.LOG) {
            Log.d(TAG, "deleteUnusedVideo")
            Log.d(TAG, "video_method $videoMethod")
            Log.d(TAG, "uri $uri")
            Log.d(TAG, "filename $filename")
        }
        if (videoMethod === VideoMethod.FILE) {
            trashImage(LastImagesType.FILE, uri, filename, false)
        } else if (videoMethod === VideoMethod.SAF) {
            trashImage(LastImagesType.SAF, uri, filename, false)
        } else if (videoMethod === VideoMethod.MEDIASTORE) {
            trashImage(LastImagesType.MEDIASTORE, uri, filename, false)
        }
        // else can't delete Uri
    }

    override fun onVideoInfo(what: Int, extra: Int) {
        // we don't show a toast for MEDIA_RECORDER_INFO_MAX_DURATION_REACHED - conflicts with "n repeats to go" toast from Preview
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && what == MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED) {
            if (MyDebug.LOG) Log.d(TAG, "next output file started")
            val messageId = R.string.video_max_filesize
            mainActivity.preview.showToast(null, messageId, true)
        } else if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
            if (MyDebug.LOG) Log.d(TAG, "max filesize reached")
            val messageId = R.string.video_max_filesize
            mainActivity.preview.showToast(null, messageId, true)
        }
        // in versions 1.24 and 1.24, there was a bug where we had "info_" for onVideoError and "error_" for onVideoInfo!
        // fixed in 1.25; also was correct for 1.23 and earlier
        val debugValue = "info_" + what + "_" + extra
        val editor = sharedPreferences.edit()
        editor.putString("last_video_error", debugValue)
        editor.apply()
    }

    override fun onFailedStartPreview() {
        mainActivity.preview.showToast(null, R.string.failed_to_start_camera_preview)
        mainActivity.enablePausePreviewOnBackPressedCallback(false) // reenable standard back button behaviour (in case preview was paused due to option to pause preview after taking a photo)
    }

    override fun onCameraError() {
        mainActivity.preview.showToast(null, R.string.camera_error)
    }

    override fun onPhotoError() {
        mainActivity.preview.showToast(null, R.string.failed_to_take_picture)
    }

    override fun onVideoError(what: Int, extra: Int) {
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "onVideoError: $what extra: $extra"
            )
        }
        var messageId = R.string.video_error_unknown
        if (what == MediaRecorder.MEDIA_ERROR_SERVER_DIED) {
            if (MyDebug.LOG) Log.d(TAG, "error: server died")
            messageId = R.string.video_error_server_died
        }
        mainActivity.preview.showToast(null, messageId)
        // in versions 1.24 and 1.24, there was a bug where we had "info_" for onVideoError and "error_" for onVideoInfo!
        // fixed in 1.25; also was correct for 1.23 and earlier
        val debugValue = "error_" + what + "_" + extra
        sharedPreferences.edit {
            putString("last_video_error", debugValue)
        }
    }

    override fun onVideoRecordStartError(profile: VideoProfile) {
        if (MyDebug.LOG) Log.d(TAG, "onVideoRecordStartError")
        val errorMessage: String
        val features = mainActivity.preview.getErrorFeatures(profile)
        errorMessage = if (features.isNotEmpty()) {
            context.resources.getString(R.string.sorry) + ", " + features + " " + context.resources.getString(
                R.string.not_supported
            )
        } else {
            context.resources.getString(R.string.failed_to_record_video)
        }
        mainActivity.preview.showToast(null, errorMessage)
    }

    override fun onVideoRecordStopError(profile: VideoProfile) {
        if (MyDebug.LOG) Log.d(TAG, "onVideoRecordStopError")
        //main_activity.preview.showToast(null, R.string.failed_to_record_video);
        val features = mainActivity.preview.getErrorFeatures(profile)
        var errorMessage = context.resources.getString(R.string.video_may_be_corrupted)
        if (features.isNotEmpty()) {
            errorMessage += ", " + features + " " + context.resources.getString(R.string.not_supported)
        }
        mainActivity.preview.showToast(null, errorMessage)
    }

    override fun onFailedReconnectError() {
        mainActivity.preview.showToast(null, R.string.failed_to_reconnect_camera)
    }

    override fun onFailedCreateVideoFileError() {
        if (MyDebug.LOG) Log.d(TAG, "onFailedCreateVideoFileError")
        mainActivity.preview.showToast(null, R.string.failed_to_save_video)
    }

    override fun hasPausedPreview(paused: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "hasPausedPreview: $paused"
        )
        val shareButton: View = mainActivity.findViewById(R.id.share)
        val trashButton: View = mainActivity.findViewById(R.id.trash)
        if (paused) {
            shareButton.visibility = View.VISIBLE
            trashButton.visibility = View.VISIBLE
            mainActivity.enablePausePreviewOnBackPressedCallback(true) // so that pressing back button instead unpauses the preview
        } else {
            shareButton.visibility = View.GONE
            trashButton.visibility = View.GONE
            this.clearLastImages()
            mainActivity.enablePausePreviewOnBackPressedCallback(false) // reenable standard back button behaviour
        }
    }

    override fun cameraInOperation(inOperation: Boolean, isVideo: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "cameraInOperation: $inOperation"
        )
        if (!inOperation && usedFrontScreenFlash) {
            mainActivity.setBrightnessForCamera(false) // ensure screen brightness matches user preference, after using front screen flash
            usedFrontScreenFlash = false
        }
        drawPreview.cameraInOperation(inOperation)
        mainActivity.mainUI.showGUI(!inOperation, isVideo)
    }

    override fun turnFrontScreenFlashOn() {
        if (MyDebug.LOG) Log.d(TAG, "turnFrontScreenFlashOn")
        usedFrontScreenFlash = true
        mainActivity.setBrightnessForCamera(true) // ensure we have max screen brightness, even if user preference not set for max brightness
        drawPreview.turnFrontScreenFlashOn()
    }

    override fun onCaptureStarted() {
        if (MyDebug.LOG) Log.d(TAG, "onCaptureStarted")
        nCaptureImages = 0
        nCaptureImagesRaw = 0
        drawPreview.onCaptureStarted()

        if (photoMode == PhotoMode.X_Night) {
            mainActivity.preview
                .showToast(null, R.string.preference_nr_mode_low_light_message, true)
        }
    }

    override fun onPictureCompleted() {
        if (MyDebug.LOG) Log.d(TAG, "onPictureCompleted")

        // clear any toasts displayed during progress (e.g., preferenceNrModeLowLightMessage, or onExtensionProgress())
        mainActivity.preview.clearActiveFakeToast()

        var photoMode = photoMode
        if (mainActivity.preview.isVideo) {
            if (MyDebug.LOG) Log.d(TAG, "snapshot mode")
            // must be in photo snapshot while recording video mode, only support standard photo mode
            photoMode = PhotoMode.Standard
        }
        if (photoMode == PhotoMode.NoiseReduction) {
            val imageCaptureIntent = isImageCaptureIntent
            val doInBackground = saveInBackground(imageCaptureIntent)
            imageSaver.finishImageBatch(doInBackground)
        } else if (photoMode == PhotoMode.Panorama && gyroSensor.isRecording) {
            if (panoramaPicAccepted) {
                if (MyDebug.LOG) Log.d(TAG, "set next panorama point")
                this.setNextPanoramaPoint(false)
            } else {
                if (MyDebug.LOG) Log.d(TAG, "panorama pic wasn't accepted")
                this.setNextPanoramaPoint(true)
            }
        } else if (photoMode == PhotoMode.FocusBracketing) {
            if (MyDebug.LOG) Log.d(TAG, "focus bracketing completed")
            if (getShutterSoundPref()) {
                if (MyDebug.LOG) Log.d(TAG, "play completion sound")
                val player = MediaPlayer.create(context, Settings.System.DEFAULT_NOTIFICATION_URI)
                player?.start()
            }
        }

        // call this, so that if pause-preview-after-taking-photo option is set, we remove the "taking photo" border indicator straight away
        // also even for normal (not pausing) behaviour, good to remove the border asap
        drawPreview.cameraInOperation(false)
    }

    override fun onExtensionProgress(progress: Int) {
        var message = ""
        if (photoMode == PhotoMode.X_Night) {
            message =
                context.resources.getString(R.string.preference_nr_mode_low_light_message) + "\n"
        }
        mainActivity.preview.showToast(null, "$message$progress%", true)
    }

    override fun cameraClosed() {
        if (MyDebug.LOG) Log.d(TAG, "cameraClosed")
        this.stopPanorama(true)
        mainActivity.mainUI.closeExposureUI()
        mainActivity.mainUI
            .destroyPopup() // need to close popup - and when camera reopened, it may have different settings
        drawPreview.clearContinuousFocusMove()
    }

    fun updateThumbnail(thumbnail: Bitmap?, isVideo: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "updateThumbnail")
        mainActivity.updateGalleryIcon(thumbnail!!)
        drawPreview.updateThumbnail(thumbnail, isVideo, true)
        if (!isVideo && this.getPausePreviewPref() && mainActivity.preview.isPreviewPaused) {
            drawPreview.showLastImage()
        }
    }

    override fun timerBeep(remainingTime: Long) {
        if (MyDebug.LOG) {
            Log.d(TAG, "timerBeep()")
            Log.d(
                TAG,
                "remaining_time: $remainingTime"
            )
        }
        if (sharedPreferences.getBoolean(PreferenceKeys.TIMER_BEEP_PREFERENCE_KEY, true)) {
            if (MyDebug.LOG) Log.d(TAG, "play beep!")
            val isLast = remainingTime <= 1000
            mainActivity.soundPoolManager
                .playSound(if (isLast) R.raw.mybeep_hi else R.raw.mybeep)
        }
        if (sharedPreferences.getBoolean(PreferenceKeys.TIMER_SPEAK_PREFERENCE_KEY, false)) {
            if (MyDebug.LOG) Log.d(TAG, "speak countdown!")
            val remainingTimeS = (remainingTime / 1000).toInt()
            if (remainingTimeS <= 60) mainActivity.speak(remainingTimeS.toString())
        }
    }

    override fun multitouchZoom(newZoom: Int) {
        mainActivity.mainUI.setSeekbarZoom(newZoom)
    }

    override fun requestTakePhoto() {
        if (MyDebug.LOG) Log.d(TAG, "requestTakePhoto")
        mainActivity.takePicture(false)
    }

    /** Switch to the first available camera that is front or back facing as desired.
     * @param frontFacing Whether to switch to a front or back facing camera.
     */
    fun switchToCamera(frontFacing: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "switchToCamera: $frontFacing"
        )
        val nCameras: Int =
            mainActivity.preview.cameraControllerManager?.numberOfCameras ?: 0
        val wantFacing: Facing =
            if (frontFacing) Facing.FACING_FRONT else Facing.FACING_BACK
        for (i in 0..<nCameras) {
            if (mainActivity.preview.cameraControllerManager?.getFacing(i) === wantFacing) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "found desired camera: $i"
                )
                this.setCameraIdPref(i, null)
                break
            }
        }
    }

    /* Note that the cameraId is still valid if this returns false, it just means that a cameraId hasn't be explicitly set yet.
     */
    fun hasSetCameraId(): Boolean {
        return has_set_cameraId
    }

    override fun setCameraIdPref(cameraId: Int, cameraIdSPhysical: String?) {
        this.has_set_cameraId = true
        this._cameraIdPref = cameraId
        this._cameraIdSPhysicalPref = cameraIdSPhysical
    }

    override fun setFocusPref(focusValue: String?, isVideo: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putString(
            PreferenceKeys.getFocusPreferenceKey(getCameraIdPref(), isVideo),
            focusValue
        )
        editor.apply()
        // focus may be updated by preview (e.g., when switching to/from video mode)
        mainActivity.setManualFocusSeekBarVisibility(false)
    }

    override fun clearSceneModePref() {
        val editor = sharedPreferences.edit()
        editor.remove(PreferenceKeys.SCENE_MODE_PREFERENCE_KEY)
        editor.apply()
    }

    override fun clearColorEffectPref() {
        val editor = sharedPreferences.edit()
        editor.remove(PreferenceKeys.COLOR_EFFECT_PREFERENCE_KEY)
        editor.apply()
    }

    override fun clearWhiteBalancePref() {
        val editor = sharedPreferences.edit()
        editor.remove(PreferenceKeys.WHITE_BALANCE_PREFERENCE_KEY)
        editor.apply()
    }

    override fun clearISOPref() {
        val editor = sharedPreferences.edit()
        editor.remove(PreferenceKeys.ISO_PREFERENCE_KEY)
        editor.apply()
    }

    override fun clearExposureCompensationPref() {
        val editor = sharedPreferences.edit()
        editor.remove(PreferenceKeys.EXPOSURE_PREFERENCE_KEY)
        editor.apply()
    }

    override fun setCameraResolutionPref(width: Int, height: Int) {
        if (photoMode == PhotoMode.Panorama) {
            // in Panorama mode we'll have set a different resolution to the user setting, so don't want that to then be saved!
            return
        }
        val resolutionValue = "$width $height"
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "save new resolution_value: $resolutionValue"
            )
        }
        val editor = sharedPreferences.edit()
        editor.putString(
            PreferenceKeys.getResolutionPreferenceKey(
                getCameraIdPref(),
                getCameraIdSPhysicalPref()
            ), resolutionValue
        )
        editor.apply()
    }

    override fun requestCameraPermission() {
        if (MyDebug.LOG) Log.d(TAG, "requestCameraPermission")
        mainActivity.permissionHandler.requestCameraPermission()
    }

    override fun needsStoragePermission(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "needsStoragePermission")
        if (MainActivity.useScopedStorage()) return false // no longer need storage permission with scoped storage - and shouldn't request it either

        return true
    }

    override fun requestStoragePermission() {
        if (MyDebug.LOG) Log.d(TAG, "requestStoragePermission")
        mainActivity.permissionHandler.requestStoragePermission()
    }

    override fun requestRecordAudioPermission() {
        if (MyDebug.LOG) Log.d(TAG, "requestRecordAudioPermission")
        mainActivity.permissionHandler.requestRecordAudioPermission()
    }

    override fun clearExposureTimePref() {
        val editor = sharedPreferences.edit()
        editor.remove(PreferenceKeys.EXPOSURE_TIME_PREFERENCE_KEY)
        editor.apply()
    }

    override fun setFocusDistancePref(focusDistance: Float, isTargetDistance: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putFloat(
            if (isTargetDistance) PreferenceKeys.FOCUS_BRACKETING_TARGET_DISTANCE_PREFERENCE_KEY else PreferenceKeys.FOCUS_DISTANCE_PREFERENCE_KEY,
            focusDistance
        )
        editor.apply()
    }

    private val stampFontColor: Int
        get() {
            val color =
                sharedPreferences.getString(PreferenceKeys.STAMP_FONT_COLOR_PREFERENCE_KEY, "#ffffff")!!
            return Color.parseColor(color)
        }

    /** Should be called to reset parameters which aren't expected to be saved (e.g., resetting zoom when application is paused,
     * when switching between photo/video modes, or switching cameras).
     */
    fun reset(switchedCamera: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "reset")
        if (switchedCamera) {
            // aperture is reset when switching camera, but not when application is paused or switching between photo/video etc
            this._aperturePref = apertureDefault
        }
        this.zoomFactor = -1
    }

    override fun onDrawPreview(canvas: Canvas) {
        if (!mainActivity.isCameraInBackground) {
            // no point drawing when in background (e.g., settings open)
            drawPreview.onDrawPreview(canvas)
        }
    }

    enum class Alignment {
        ALIGNMENT_TOP,
        ALIGNMENT_CENTRE,
        ALIGNMENT_BOTTOM
    }

    enum class Shadow {
        SHADOW_NONE,
        SHADOW_OUTLINE,
        SHADOW_BACKGROUND
    }

    @JvmOverloads
    fun drawTextWithBackground(
        canvas: Canvas,
        paint: Paint,
        text: String,
        foreground: Int,
        background: Int,
        locationX: Int,
        locationY: Int,
        alignmentY: Alignment = Alignment.ALIGNMENT_BOTTOM,
        yboundsText: String? = null,
        shadow: Shadow = Shadow.SHADOW_OUTLINE
    ): Int {
        return drawTextWithBackground(
            canvas,
            paint,
            text,
            foreground,
            background,
            locationX,
            locationY,
            alignmentY,
            yboundsText,
            shadow,
            null
        )
    }

    fun drawTextWithBackground(
        canvas: Canvas,
        paint: Paint,
        text: String,
        foreground: Int,
        background: Int,
        locationX: Int,
        locationY: Int,
        alignmentY: Alignment,
        yboundsText: String?,
        shadow: Shadow,
        bounds: Rect?
    ): Int {
        var locationY = locationY
        val scale =
            context.resources.displayMetrics.scaledDensity // important to use scaledDensity for scaling font sizes
        paint.style = Paint.Style.FILL
        paint.color = background
        paint.alpha = 64
        if (bounds != null) {
            textBounds.set(bounds)
        } else {
            var altHeight = 0
            if (yboundsText != null) {
                paint.getTextBounds(yboundsText, 0, yboundsText.length, textBounds)
                altHeight = textBounds.bottom - textBounds.top
            }
            paint.getTextBounds(text, 0, text.length, textBounds)
            if (yboundsText != null) {
                textBounds.bottom = textBounds.top + altHeight
            }
        }
        val padding = (2 * scale + 0.5f).toInt() // convert dps to pixels
        if (paint.textAlign == Paint.Align.RIGHT || paint.textAlign == Paint.Align.CENTER) {
            var width =
                paint.measureText(text) // n.b., need to use measureText rather than getTextBounds here
            /*if( MyDebug.LOG )
				Log.d(TAG, "width: " + width);*/
            if (paint.textAlign == Paint.Align.CENTER) width /= 2.0f
            textBounds.left = (textBounds.left - width).toInt()
            textBounds.right = (textBounds.right - width).toInt()
        }
        /*if( MyDebug.LOG )
			Log.d(TAG, "textBounds left-right: " + text_bounds.left + " , " + text_bounds.right);*/
        textBounds.left += locationX - padding
        textBounds.right += locationX + padding
        // unclear why we need the offset of -1, but need this to align properly on Galaxy Nexus at least
        val topYDiff = -textBounds.top + padding - 1
        if (alignmentY == Alignment.ALIGNMENT_TOP) {
            val height = textBounds.bottom - textBounds.top + 2 * padding
            textBounds.top = locationY - 1
            textBounds.bottom = textBounds.top + height
            locationY += topYDiff
        } else if (alignmentY == Alignment.ALIGNMENT_CENTRE) {
            val height = textBounds.bottom - textBounds.top + 2 * padding
            //int yDiff = - text_bounds.top + padding - 1;
            textBounds.top =
                (0.5 * ((locationY - 1) + (textBounds.top + locationY - padding))).toInt() // average of ALIGNMENT_TOP and ALIGNMENT_BOTTOM
            textBounds.bottom = textBounds.top + height
            locationY += (0.5 * topYDiff).toInt() // average of ALIGNMENT_TOP and ALIGNMENT_BOTTOM
        } else {
            textBounds.top += locationY - padding
            textBounds.bottom += locationY + padding
        }
        if (shadow == Shadow.SHADOW_BACKGROUND) {
            paint.color = background
            paint.alpha = 64
            canvas.drawRect(textBounds, paint)
            paint.alpha = 255
        }
        paint.color = foreground
        if (shadow == Shadow.SHADOW_OUTLINE) {
            var shadowRadius = (1.0f * scale + 0.5f) // convert pt to pixels
            shadowRadius = max(shadowRadius.toDouble(), 1.0).toFloat()
            paint.setShadowLayer(shadowRadius, 0.0f, 0.0f, background)
        }
        canvas.drawText(text, locationX.toFloat(), locationY.toFloat(), paint)
        if (shadow == Shadow.SHADOW_OUTLINE) {
            paint.clearShadowLayer() // set back to default
        }
        /*if( shadow == Shadow.SHADOW_OUTLINE ) {
            // old method (instead of setting shadow layer) - doesn't work correctly on Android 12!
            paint.setColor(background);
            paint.setStyle(Paint.Style.STROKE);
            float currentStrokeWidth = paint.getStrokeWidth();
            paint.setStrokeWidth(1);
            canvas.drawText(text, locationX, locationY, paint);
            paint.setStyle(Paint.Style.FILL); // set back to default
            paint.setStrokeWidth(currentStrokeWidth); // reset
        }*/
        return textBounds.bottom - textBounds.top
    }

    private fun saveInBackground(imageCaptureIntent: Boolean): Boolean {
        var doInBackground = true
        /*if( !sharedPreferences.getBoolean(PreferenceKeys.BackgroundPhotoSavingPreferenceKey, true) )
			doInBackground = false;
		else*/
        if (imageCaptureIntent) doInBackground = false
        else if (getPausePreviewPref()) doInBackground = false
        return doInBackground
    }

    val isImageCaptureIntent: Boolean
        get() {
            var imageCaptureIntent = false
            val action = mainActivity.intent.action
            if (MediaStore.ACTION_IMAGE_CAPTURE == action || MediaStore.ACTION_IMAGE_CAPTURE_SECURE == action) {
                if (MyDebug.LOG) Log.d(TAG, "from image capture intent")
                imageCaptureIntent = true
            }
            return imageCaptureIntent
        }

    val isVideoCaptureIntent: Boolean
        get() {
            var videoCaptureIntent = false
            val action = mainActivity.intent.action
            if (MediaStore.ACTION_VIDEO_CAPTURE == action) {
                if (MyDebug.LOG) Log.d(TAG, "from video capture intent")
                videoCaptureIntent = true
            }
            return videoCaptureIntent
        }

    /** Whether the photos will be part of a burst, even if we're receiving via the non-burst callbacks.
     */
    private fun forceSuffix(photoMode: PhotoMode): Boolean {
        // focus bracketing and fast burst shots come is as separate requests, so we need to make sure we get the filename suffixes right
        return photoMode == PhotoMode.FocusBracketing || photoMode == PhotoMode.FastBurst ||
                (mainActivity.preview.cameraController != null &&
                        mainActivity.preview.cameraController!!.isCapturingBurst)
    }

    /** Saves the supplied image(s)
     * @param saveExpo If the photo mode is one where multiple images are saved to a single
     * resultant image, this indicates if all the base images should also be saved
     * as separate images.
     * @param images The set of images.
     * @param currentDate The current date/time stamp for the images.
     * @return Whether saving was successful.
     */
    private fun saveImage(
        saveExpo: Boolean,
        images: List<ByteArray>,
        currentDate: Date
    ): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "saveImage")

        System.gc()

        val imageCaptureIntent = isImageCaptureIntent
        var imageCaptureIntentUri: Uri? = null
        if (imageCaptureIntent) {
            if (MyDebug.LOG) Log.d(TAG, "from image capture intent")
            val myExtras = mainActivity.intent.extras
            if (myExtras != null) {
                imageCaptureIntentUri = myExtras.getParcelable(MediaStore.EXTRA_OUTPUT)
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "save to: $imageCaptureIntentUri"
                )
            }
        }

        val usingCamera2 = mainActivity.preview.usingCamera2API()
        val usingCameraExtensions = isCameraExtensionPref()
        val imageFormat: ImageSaver.Request.ImageFormat = imageFormatPref
        val storeYpr = sharedPreferences.getBoolean(PreferenceKeys.ADD_YPR_TO_COMMENTS, false) &&
                mainActivity.preview.hasLevelAngle() &&
                mainActivity.preview.hasPitchAngle() &&
                mainActivity.preview.hasGeoDirection()
        if (MyDebug.LOG) {
            Log.d(TAG, "store_ypr: $storeYpr")
            Log.d(
                TAG, "has level angle: " + mainActivity.preview
                    .hasLevelAngle()
            )
            Log.d(
                TAG, "has pitch angle: " + mainActivity.preview
                    .hasPitchAngle()
            )
            Log.d(
                TAG, "has geo direction: " + mainActivity.preview
                    .hasGeoDirection()
            )
        }
        val imageQuality = saveImageQualityPref
        if (MyDebug.LOG) Log.d(
            TAG,
            "image_quality: $imageQuality"
        )
        val doAutoStabilise = autoStabilisePref && mainActivity.preview
            .hasLevelAngleStable()
        var levelAngle =
            if (mainActivity.preview.hasLevelAngle()) mainActivity.preview
                .levelAngle else 0.0
        val pitchAngle =
            if (mainActivity.preview.hasPitchAngle()) mainActivity.preview
                .pitchAngle else 0.0
        if (doAutoStabilise && mainActivity.testHaveAngle) levelAngle =
            mainActivity.testAngle.toDouble()
        if (doAutoStabilise && mainActivity.testLowMemory) levelAngle = 45.0
        // I have received crashes where cameraController was null - could perhaps happen if this thread was running just as the camera is closing?
        val isFrontFacing =
            mainActivity.preview.cameraController != null && (mainActivity.preview.cameraController!!.facing === Facing.FACING_FRONT)
        val mirror = isFrontFacing && sharedPreferences.getString(
            PreferenceKeys.FRONT_CAMERA_MIRROR_KEY,
            "preference_front_camera_mirror_no"
        ) == "preference_front_camera_mirror_photo"
        val preferenceStamp = this.stampPref
        val preferenceTextstamp = this.textStampPref
        val fontSize = textStampFontSizePref
        val color = stampFontColor
        val prefStyle = sharedPreferences.getString(
            PreferenceKeys.STAMP_STYLE_KEY,
            "preference_stamp_style_shadowed"
        )!!
        val preferenceStampDateformat = this.stampDateFormatPref
        val preferenceStampTimeformat = this.stampTimeFormatPref
        val preferenceStampGpsformat = this.stampGPSFormatPref
        //String preferenceStampGeoAddress = this.getStampGeoAddressPref();
        val preferenceUnitsDistance = this.unitsDistancePref
        val panoramaCrop = sharedPreferences.getString(
            PreferenceKeys.PANORAMA_CROP_PREFERENCE_KEY,
            "preference_panorama_crop_on"
        ) == "preference_panorama_crop_on"
        val removeDeviceExif: ImageSaver.Request.RemoveDeviceExif = removeDeviceExifPref
        val storeLocation = getGeotaggingPref() && getLocation() != null
        val location = if (storeLocation) getLocation() else null
        val storeGeoDirection = mainActivity.preview.hasGeoDirection() && geodirectionPref
        val geoDirection =
            if (mainActivity.preview.hasGeoDirection()) mainActivity.preview
                .geoDirection else 0.0
        val customTagArtist =
            sharedPreferences.getString(PreferenceKeys.EXIF_ARTIST_PREFERENCE_KEY, "")!!
        val customTagCopyright =
            sharedPreferences.getString(PreferenceKeys.EXIF_COPYRIGHT_PREFERENCE_KEY, "")!!

        var iso = 800 // default value if we can't get ISO
        var exposureTime = 1000000000L / 30 // default value if we can't get shutter speed
        var zoomFactor = 1.0f
        if (mainActivity.preview.cameraController != null) {
            if (mainActivity.preview.cameraController!!.captureResultHasIso()) {
                iso = mainActivity.preview.cameraController!!.captureResultIso()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "iso: $iso"
                )
            }
            if (mainActivity.preview.cameraController!!.captureResultHasExposureTime()) {
                exposureTime =
                    mainActivity.preview.cameraController!!.captureResultExposureTime()
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "exposure_time: $exposureTime"
                )
            }

            zoomFactor = mainActivity.preview.zoomRatio
        }

        val hasThumbnailAnimation = thumbnailAnimationPref

        val doInBackground = saveInBackground(imageCaptureIntent)

        val ghostImagePref =
            sharedPreferences.getString(
                PreferenceKeys.GHOST_IMAGE_PREFERENCE_KEY,
                "preference_ghost_image_off"
            )!!

        var sampleFactor = 1
        if (!this.getPausePreviewPref() && ghostImagePref != "preference_ghost_image_last") {
            // if pausing the preview, we use the thumbnail also for the preview, so don't downsample
            // similarly for ghosting last image
            // otherwise, we can downsample by 4 to increase performance, without noticeable loss in visual quality (even for the thumbnail animation)
            sampleFactor *= 4
            if (!hasThumbnailAnimation) {
                // can use even lower resolution if we don't have the thumbnail animation
                sampleFactor *= 4
            }
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "sample_factor: $sampleFactor"
        )

        val success: Boolean
        var photoMode = photoMode
        if (mainActivity.preview.isVideo) {
            if (MyDebug.LOG) Log.d(TAG, "snapshot mode")
            // must be in photo snapshot while recording video mode, only support standard photo mode
            photoMode = PhotoMode.Standard
        }

        var preshotBitmaps: MutableList<Bitmap?>? = null
        if (!imageCaptureIntent && nCaptureImages <= 1 && getPreShotsPref(photoMode)) {
            // n.b., nCaptureImages == 0 if using onBurstPictureTaken(), e.g., for photo mode HDR
            val ringBuffer: Preview.RingBuffer = mainActivity.preview.preShotsRingBuffer

            if (ringBuffer.nBitmaps >= 3) {
                if (MyDebug.LOG) Log.d(TAG, "save pre-shots")

                preshotBitmaps = ArrayList()
                while (ringBuffer.hasBitmaps()) {
                    val bitmap: Bitmap? = ringBuffer.get()
                    preshotBitmaps.add(bitmap)
                }
            }
        }

        if (!mainActivity.isTest && photoMode == PhotoMode.Panorama && gyroSensor.isRecording && gyroSensor.hasTarget() && !gyroSensor.isTargetAchieved()) {
            if (MyDebug.LOG) Log.d(TAG, "ignore panorama image as target no longer achieved!")
            // n.b., gyroSensor.hasTarget() will be false if this is the first picture in the panorama series
            panoramaPicAccepted = false
            success = true // still treat as success
        } else if (photoMode == PhotoMode.NoiseReduction || photoMode == PhotoMode.Panorama) {
            val firstImage: Boolean
            if (photoMode == PhotoMode.Panorama) {
                panoramaPicAccepted = true
                firstImage = nPanoramaPics == 0
            } else firstImage = nCaptureImages == 1
            if (firstImage) {
                var saveBase: ImageSaver.Request.SaveBase =
                    ImageSaver.Request.SaveBase.SAVEBASE_NONE
                if (photoMode == PhotoMode.NoiseReduction) {
                    val saveBasePreference =
                        sharedPreferences.getString(
                            PreferenceKeys.NR_SAVE_EXPO_PREFERENCE_KEY,
                            "preference_nr_save_no"
                        )!!
                    when (saveBasePreference) {
                        "preference_nr_save_single" -> saveBase =
                            ImageSaver.Request.SaveBase.SAVEBASE_FIRST

                        "preference_nr_save_all" -> saveBase =
                            ImageSaver.Request.SaveBase.SAVEBASE_ALL
                    }
                } else if (photoMode == PhotoMode.Panorama) {
                    val saveBasePreference =
                        sharedPreferences.getString(
                            PreferenceKeys.PANORAMA_SAVE_EXPO_PREFERENCE_KEY,
                            "preference_panorama_save_no"
                        )!!
                    when (saveBasePreference) {
                        "preference_panorama_save_all" -> saveBase =
                            ImageSaver.Request.SaveBase.SAVEBASE_ALL

                        "preference_panorama_save_all_plus_debug" -> saveBase =
                            ImageSaver.Request.SaveBase.SAVEBASE_ALL_PLUS_DEBUG
                    }
                }

                imageSaver.startImageBatch(
                    true,
                    if (photoMode == PhotoMode.NoiseReduction) ImageSaver.Request.ProcessType.AVERAGE else ImageSaver.Request.ProcessType.PANORAMA,
                    preshotBitmaps,
                    saveBase,
                    imageCaptureIntent,
                    imageCaptureIntentUri,
                    usingCamera2,
                    usingCameraExtensions,
                    imageFormat,
                    imageQuality,
                    doAutoStabilise,
                    levelAngle,
                    photoMode == PhotoMode.Panorama,
                    isFrontFacing,
                    mirror,
                    currentDate,
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
                    preferenceStampGpsformat,  //preferenceStampGeoAddress,
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

                if (photoMode == PhotoMode.Panorama) {
                    imageSaver.imageBatchRequest?.cameraViewAngleX =
                        mainActivity.preview.getViewAngleX(false)
                    imageSaver.imageBatchRequest?.cameraViewAngleY =
                        mainActivity.preview.getViewAngleY(false)
                }
            }

            var gyroRotationMatrix: FloatArray? = null
            if (photoMode == PhotoMode.Panorama) {
                gyroRotationMatrix = FloatArray(9)
                gyroSensor.getRotationMatrix(gyroRotationMatrix)
            }

            imageSaver.addImageBatch(images[0], gyroRotationMatrix)
            success = true
        } else {
            val processType: ImageSaver.Request.ProcessType =
                if (photoMode == PhotoMode.DRO || photoMode == PhotoMode.HDR) ImageSaver.Request.ProcessType.HDR
                else if (photoMode == PhotoMode.X_Night) ImageSaver.Request.ProcessType.X_NIGHT
                else ImageSaver.Request.ProcessType.NORMAL
            val forceSuffix = forceSuffix(photoMode)

            var preferenceHdrTonemappingAlgorithm: HDRProcessor.TonemappingAlgorithm =
                HDRProcessor.defaultTonemappingAlgorithmC
            run {
                val tonemappingAlgorithmPref =
                    sharedPreferences.getString(
                        PreferenceKeys.HDR_TONEMAPPING_PREFERENCE_KEY,
                        "preference_hdr_tonemapping_default"
                    )!!
                when (tonemappingAlgorithmPref) {
                    "preference_hdr_tonemapping_clamp" -> preferenceHdrTonemappingAlgorithm =
                        HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_CLAMP

                    "preference_hdr_tonemapping_exponential" -> preferenceHdrTonemappingAlgorithm =
                        HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_EXPONENTIAL

                    "preference_hdr_tonemapping_default" -> preferenceHdrTonemappingAlgorithm =
                        HDRProcessor.defaultTonemappingAlgorithmC

                    "preference_hdr_tonemapping_aces" -> preferenceHdrTonemappingAlgorithm =
                        HDRProcessor.TonemappingAlgorithm.TONEMAPALGORITHM_ACES

                    else -> Log.e(
                        TAG,
                        "unhandled case for tonemapping: $tonemappingAlgorithmPref"
                    )
                }
            }
            val preferenceHdrContrastEnhancement =
                sharedPreferences.getString(
                    PreferenceKeys.HDR_CONTRAST_ENHANCEMENT_PREFERENCE_KEY,
                    "preference_hdr_contrast_enhancement_smart"
                )!!

            success = imageSaver.saveImageJpeg(
                doInBackground,
                processType,
                forceSuffix,  // N.B., nCaptureImages will be 1 for first image, not 0, so subtract 1 so we start off from _0.
                // (It wouldn't be a huge problem if we did start from _1, but it would be inconsistent with the naming
                // of images where images.size() > 1 (e.g., expo bracketing mode) where we also start from _0.)
                if (forceSuffix) (nCaptureImages - 1) else 0,
                saveExpo,
                images.toMutableList(),
                preshotBitmaps,
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
                preferenceStampGpsformat,  //preferenceStampGeoAddress,
                preferenceUnitsDistance,
                false,  // panorama doesn't use this codepath
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

        if (MyDebug.LOG) Log.d(
            TAG,
            "saveImage complete, success: $success"
        )

        return success
    }

    override fun onPictureTaken(data: ByteArray, currentDate: Date): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onPictureTaken")

        nCaptureImages++
        if (MyDebug.LOG) Log.d(
            TAG,
            "n_capture_images is now $nCaptureImages"
        )

        val images: MutableList<ByteArray> = ArrayList()
        images.add(data)

        val success = saveImage(false, images, currentDate)

        if (MyDebug.LOG) Log.d(
            TAG,
            "onPictureTaken complete, success: $success"
        )

        return success
    }

    override fun onBurstPictureTaken(images: List<ByteArray>, currentDate: Date): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onBurstPictureTaken: received " + images.size + " images")

        val success: Boolean
        var photoMode = photoMode
        if (mainActivity.preview.isVideo) {
            if (MyDebug.LOG) Log.d(TAG, "snapshot mode")
            // must be in photo snapshot while recording video mode, only support standard photo mode
            photoMode = PhotoMode.Standard
        }
        if (photoMode == PhotoMode.HDR) {
            if (MyDebug.LOG) Log.d(TAG, "HDR mode")
            val saveExpo =
                sharedPreferences.getBoolean(PreferenceKeys.HDR_SAVE_EXPO_PREFERENCE_KEY, false)
            if (MyDebug.LOG) Log.d(
                TAG,
                "save_expo: $saveExpo"
            )

            success = saveImage(saveExpo, images, currentDate)
        } else {
            if (MyDebug.LOG) {
                Log.d(TAG, "exposure/focus bracketing mode mode")
                if (photoMode != PhotoMode.ExpoBracketing && photoMode != PhotoMode.FocusBracketing) Log.e(
                    TAG,
                    "onBurstPictureTaken called with unexpected photo mode?!: $photoMode"
                )
            }

            success = saveImage(true, images, currentDate)
        }
        return success
    }

    override fun onRawPictureTaken(rawImage: RawImage?, currentDate: Date): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onRawPictureTaken")
        System.gc()

        nCaptureImagesRaw++
        if (MyDebug.LOG) Log.d(
            TAG,
            "n_capture_images_raw is now $nCaptureImagesRaw"
        )

        val doInBackground = saveInBackground(false)

        var photoMode = photoMode
        if (mainActivity.preview.isVideo) {
            if (MyDebug.LOG) Log.d(TAG, "snapshot mode")
            // must be in photo snapshot while recording video mode, only support standard photo mode
            // (RAW not supported anyway for video snapshot mode, but have this code just to be safe)
            photoMode = PhotoMode.Standard
        }
        val forceSuffix = forceSuffix(photoMode)
        // N.B., nCaptureImagesRaw will be 1 for first image, not 0, so subtract 1 so we start off from _0.
        // (It wouldn't be a huge problem if we did start from _1, but it would be inconsistent with the naming
        // of images where images.size() > 1 (e.g., expo bracketing mode) where we also start from _0.)
        val suffixOffset = if (forceSuffix) (nCaptureImagesRaw - 1) else 0
        val success: Boolean = imageSaver.saveImageRaw(
            doInBackground,
            forceSuffix,
            suffixOffset,
            rawImage,
            currentDate
        )

        if (MyDebug.LOG) Log.d(TAG, "onRawPictureTaken complete")
        return success
    }

    override fun onRawBurstPictureTaken(rawImages: List<RawImage>, currentDate: Date): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "onRawBurstPictureTaken")
        System.gc()

        val doInBackground = saveInBackground(false)

        // currently we don't ever do post processing with RAW burst images, so just save them all
        var success = true
        var i = 0
        while (i < rawImages.size && success) {
            success =
                imageSaver.saveImageRaw(doInBackground, true, i, rawImages[i], currentDate)
            i++
        }

        if (MyDebug.LOG) Log.d(TAG, "onRawBurstPictureTaken complete")
        return success
    }

    fun addLastImage(file: File, share: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "addLastImage: $file")
            Log.d(TAG, "share?: $share")
        }
        lastImagesType = LastImagesType.FILE
        val lastImage = LastImage(file.absolutePath, share)
        lastImages.add(lastImage)
    }

    fun addLastImageSAF(uri: Uri, share: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "addLastImageSAF: $uri")
            Log.d(TAG, "share?: $share")
        }
        lastImagesType = LastImagesType.SAF
        val lastImage = LastImage(uri, share)
        lastImages.add(lastImage)
    }

    fun addLastImageMediaStore(uri: Uri, share: Boolean) {
        if (MyDebug.LOG) {
            Log.d(TAG, "addLastImageMediaStore: $uri")
            Log.d(TAG, "share?: $share")
        }
        lastImagesType = LastImagesType.MEDIASTORE
        val lastImage = LastImage(uri, share)
        lastImages.add(lastImage)
    }

    fun clearLastImages() {
        if (MyDebug.LOG) Log.d(TAG, "clearLastImages")
        lastImagesType = LastImagesType.FILE
        lastImages.clear()
        drawPreview.clearLastImage()
    }

    fun shareLastImage() {
        if (MyDebug.LOG) Log.d(TAG, "shareLastImage")
        val preview: Preview = mainActivity.preview
        if (preview.isPreviewPaused) {
            var shareImage: LastImage? = null
            var i = 0
            while (i < lastImages.size && shareImage == null) {
                val lastImage = lastImages[i]
                if (lastImage.share) {
                    shareImage = lastImage
                }
                i++
            }
            var done = true
            if (shareImage != null) {
                val lastImageUri = shareImage.uri
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "Share: $lastImageUri"
                )
                if (lastImageUri == null) {
                    // could happen with Android 7+ with non-SAF if the image hasn't been scanned yet,
                    // so we don't know the uri yet
                    Log.e(TAG, "can't share last image as don't yet have uri")
                    done = false
                } else {
                    val intent = Intent(Intent.ACTION_SEND)
                    intent.setType("image/jpeg")
                    intent.putExtra(Intent.EXTRA_STREAM, lastImageUri)
                    mainActivity.startActivity(Intent.createChooser(intent, "Photo"))
                }
            }
            if (done) {
                clearLastImages()
                preview.startCameraPreview()
            }
        }
    }

    private fun trashImage(
        imageType: LastImagesType,
        imageUri: Uri?,
        imageName: String?,
        fromUser: Boolean
    ) {
        if (MyDebug.LOG) Log.d(TAG, "trashImage")
        val preview: Preview = mainActivity.preview
        if (imageType == LastImagesType.SAF && imageUri != null) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "Delete SAF: $imageUri"
            )
            val file: File? = storageUtils.getFileFromDocumentUriSAF(
                imageUri,
                false
            ) // need to get file before deleting it, as fileFromDocumentUriSAF may depend on the file still existing
            try {
                if (!DocumentsContract.deleteDocument(mainActivity.contentResolver, imageUri)) {
                    if (MyDebug.LOG) Log.e(
                        TAG,
                        "failed to delete $imageUri"
                    )
                } else {
                    if (MyDebug.LOG) Log.d(
                        TAG,
                        "successfully deleted $imageUri"
                    )
                    if (fromUser) preview.showToast(null, R.string.photo_deleted, true)
                    if (file != null) {
                        // SAF doesn't broadcast when deleting them
                        storageUtils.broadcastFile(file, false, false, false, false, null)
                    }
                }
            } catch (e: FileNotFoundException) {
                // note, Android Studio reports a warning that FileNotFoundException isn't thrown, but it can be
                // thrown by DocumentsContract.deleteDocument - and we get an error if we try to remove the catch!
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "exception when deleting $imageUri"
                )
                e.printStackTrace()
            }
        } else if (imageType == LastImagesType.MEDIASTORE && imageUri != null) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "Delete MediaStore: $imageUri"
            )
            if (mainActivity.contentResolver.delete(imageUri, null, null) > 0) {
                if (fromUser) preview.showToast(photoDeleteToast, R.string.photo_deleted, true)
            }
        } else if (imageName != null) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "Delete: $imageName"
            )
            val file = File(imageName)
            if (!file.delete()) {
                if (MyDebug.LOG) Log.e(
                    TAG,
                    "failed to delete $imageName"
                )
            } else {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "successfully deleted $imageName"
                )
                if (fromUser) preview.showToast(photoDeleteToast, R.string.photo_deleted, true)
                storageUtils.broadcastFile(file, false, false, false, false, null)
            }
        }
    }

    fun trashLastImage() {
        if (MyDebug.LOG) Log.d(TAG, "trashLastImage")
        val preview: Preview = mainActivity.preview
        if (preview.isPreviewPaused) {
            for (i in lastImages.indices) {
                val lastImage = lastImages[i]
                trashImage(lastImagesType, lastImage.uri, lastImage.name, true)
            }
            clearLastImages()
            drawPreview.clearGhostImage() // doesn't make sense to show the last image as a ghost, if the user has trashed it!
            preview.startCameraPreview()
        }
        // Calling updateGalleryIcon() immediately has problem that it still returns the latest image that we've just deleted!
        // But works okay if we call after a delay. 100ms works fine on Nexus 7 and Galaxy Nexus, but set to 500 just to be safe.
        // Also note that if using option to strip all exif tags, we won't be able to find the previous most recent image - but not
        // much we can do here when the user is using that option.
        val handler = Handler()
        handler.postDelayed({ mainActivity.updateGalleryIcon() }, 500)
    }

    /** Called when StorageUtils scans a saved photo with MediaScannerConnection.scanFile.
     * @param file The file that was scanned.
     * @param uri  The file's corresponding uri.
     */
    fun scannedFile(file: File, uri: Uri) {
        if (MyDebug.LOG) {
            Log.d(TAG, "scannedFile")
            Log.d(TAG, "file: $file")
            Log.d(TAG, "uri: $uri")
        }
        // see note under LastImage constructor for why we need to update the Uris
        for (i in lastImages.indices) {
            val lastImage = lastImages[i]
            if (MyDebug.LOG) Log.d(TAG, "compare to last_image: " + lastImage.name)
            if (lastImage.uri == null && lastImage.name != null && lastImage.name == file.absolutePath) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "updated last_image : $i"
                )
                lastImage.uri = uri
            }
        }
    }

    // for testing
    fun hasThumbnailAnimation(): Boolean {
        return drawPreview.hasThumbnailAnimation()
    }

    val hDRProcessor: HDRProcessor
        get() = imageSaver.getHDRProcessor()

    val panoramaProcessor: PanoramaProcessor
        get() = imageSaver.getPanoramaProcessor()

    var testSetAvailableMemory: Boolean = false
    var testAvailableMemory: Long = 0

    init {
        var debugTime: Long = 0
        if (MyDebug.LOG) {
            Log.d(TAG, "MyApplicationInterface")
            debugTime = System.currentTimeMillis()
        }
        this.mainActivity = mainActivity
        this.sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity)
        this.locationSupplier = LocationSupplier(mainActivity)
        if (MyDebug.LOG) Log.d(
            TAG,
            "MyApplicationInterface: time after creating location supplier: " + (System.currentTimeMillis() - debugTime)
        )
        this.gyroSensor = GyroSensor(mainActivity)
        this.storageUtils = StorageUtils(mainActivity, this)
        if (MyDebug.LOG) Log.d(
            TAG,
            "MyApplicationInterface: time after creating storage utils: " + (System.currentTimeMillis() - debugTime)
        )
        this.drawPreview = DrawPreview(mainActivity, this)

        this.imageSaver = ImageSaver(mainActivity)
        imageSaver.start()

        this.reset(false)
        if (savedInstanceState != null) {
            // load the things we saved in onSaveInstanceState().
            if (MyDebug.LOG) Log.d(TAG, "read from savedInstanceState")
            has_set_cameraId = true
            _cameraIdPref = savedInstanceState.getInt("cameraId", cameraId_default)
            if (MyDebug.LOG) Log.d(TAG, "found cameraId: " + getCameraIdPref())
            _cameraIdSPhysicalPref = savedInstanceState.getString("cameraIdSPhysical", null)
            if (MyDebug.LOG) Log.d(TAG, "found cameraIdSPhysical: " + getCameraIdSPhysicalPref())
            nRMode = savedInstanceState.getString("nr_mode", nrModeDefault)
            if (MyDebug.LOG) Log.d(TAG, "found nr_mode: " + nRMode)
            _aperturePref = savedInstanceState.getFloat("aperture", apertureDefault)
            if (MyDebug.LOG) Log.d(TAG, "found aperture: " + getAperturePref())
        }

        if (MyDebug.LOG) Log.d(
            TAG,
            "MyApplicationInterface: total time to create MyApplicationInterface: " + (System.currentTimeMillis() - debugTime)
        )
    }

    companion object {
        private const val TAG = "MyApplicationInterface"

        const val panoramaPicsPerScreen: Float = 3.33333f
        const val maxPanoramaPicsC: Int =
            10 // if we increase this, review against memory requirements under MainActivity.supportsPanorama()

        // camera properties which are saved in bundle, but not stored in preferences (so will be remembered if the app goes into background, but not after restart)
        private const val cameraId_default = 0
        private const val nrModeDefault = "preference_nr_mode_normal"
        private const val apertureDefault = -1.0f
        fun choosePanoramaResolution(sizes: List<CameraController.Size>): CameraController.Size {
            // if we allow panorama with higher resolutions, review against memory requirements under MainActivity.supportsPanorama()
            // also may need to update the downscaling in the testing code
            val maxWidthC = 2080
            var found = false
            var bestSize: CameraController.Size? = null
            // find largest width <= maxWidthC with aspect ratio 4:3
            for (size in sizes) {
                if (size.width <= maxWidthC) {
                    val aspectRatio = (size.width.toDouble()) / size.height
                    if (abs(aspectRatio - 4.0 / 3.0) < 1.0e-5) {
                        if (!found || size.width > (bestSize?.width ?: 0)) {
                            found = true
                            bestSize = size
                        }
                    }
                }
            }
            if (found) {
                return bestSize!!
            }
            // else find largest width <= maxWidthC
            for (size in sizes) {
                if (size.width <= maxWidthC) {
                    if (!found || size.width > (bestSize?.width ?: 0)) {
                        found = true
                        bestSize = size
                    }
                }
            }
            if (found) {
                return bestSize!!
            }
            // else find smallest width
            for (size in sizes) {
                if (!found || size.width < (bestSize?.width ?: Int.MAX_VALUE)) {
                    found = true
                    bestSize = size
                }
            }
            return bestSize!!
        }

        /** Whether the Mediastore API supports saving subtitle files.
         */
        fun mediastoreSupportsVideoSubtitles(): Boolean {
            // Android 11+ no longer allows mediastore API to save types that Android doesn't support!
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        }
    }
}
