/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.hardware.Camera
import android.hardware.Camera.ShutterCallback
import android.location.Location
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.SurfaceHolder
import android.view.TextureView
import com.hightechif.openkamera.utils.MyDebug
import java.io.IOException
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

/** Provides support using Android's original camera API
 * android.hardware.Camera.
 */
@Deprecated(
    message = "Legacy Camera1 HAL. Use Camera2EngineImpl / ICameraEngine.",
    level = DeprecationLevel.WARNING
)
class CameraController1 private constructor(cameraId: Int) : CameraController(cameraId) {

    private var camera: Camera? = null
    private val cameraInfo = Camera.CameraInfo()
    private var _isoKey: String? = null
    private var frontscreenFlash = false
    var cameraErrorCb: ErrorCallback? = null
    private var soundsEnabled = true
    private var nBurst = 0 // number of expected burst images in this capture

    override var burstTotal: Int = 0
        get() = nBurst
        private set
    private val pendingBurstImages: MutableList<ByteArray> =
        ArrayList() // burst images that have been captured so far, but not yet sent to the application
    private var burstExposures: List<Int>? = null
    private var wantExpoBracketing = false
    private var expoBracketingNImages = 3
    private var expoBracketingStops = 2.0

    private var autofocusTimeoutHandler: Handler? = null // handler for tracking autofocus timeout
    private var autofocusTimeoutRunnable: Runnable? =
        null // runnable set for tracking autofocus timeout

    // we keep track of some camera settings rather than reading from Camera.getParameters() every time. Firstly this is important
    // for performance (affects UI rendering times, e.g., see profiling of GPU rendering). Secondly runtimeexceptions from
    // Camera.getParameters() seem to be common in Google Play, particularly for getZoom().
    private var currentZoomValue = 0

    /*Camera.Parameters parameters = this.getParameters();
         return parameters.getExposureCompensation();*/
    override var exposureCompensation: Int = 0
        private set
    private var pictureWidth = 0
    private var pictureHeight = 0

    override fun onError() {
        Log.e(TAG, "onError")
        if (this.camera != null) { // I got Google Play crash reports due to camera being null in v1.36
            camera?.release()
            this.camera = null
        }
        if (this.cameraErrorCb != null) {
            // need to communicate the problem to the application
            cameraErrorCb?.onError()
        }
    }

    private inner class CameraErrorCallback : Camera.ErrorCallback {
        override fun onError(error: Int, cam: Camera) {
            // n.b., as this is potentially serious error, we always log even if MyDebug.LOG is false
            Log.e(TAG, "camera onError: $error")
            if (error == Camera.CAMERA_ERROR_SERVER_DIED) {
                Log.e(TAG, "    CAMERA_ERROR_SERVER_DIED")
                this@CameraController1.onError()
            } else if (error == Camera.CAMERA_ERROR_UNKNOWN) {
                Log.e(TAG, "    CAMERA_ERROR_UNKNOWN ")
            }
        }
    }

    override fun release() {
        if (camera != null) {
            // have had crashes when this is called from Preview/CloseCameraTask.
            camera?.release()
            camera = null
        }
    }

    val parameters: Camera.Parameters
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getParameters")
            return camera?.parameters ?: throw RuntimeException("camera is null")
        }

    private fun setCameraParameters(parameters: Camera.Parameters) {
        if (MyDebug.LOG) Log.d(TAG, "setCameraParameters")
        try {
            camera?.parameters = parameters
            if (MyDebug.LOG) Log.d(TAG, "done")
        } catch (e: RuntimeException) {
            // just in case something has gone wrong
            if (MyDebug.LOG) Log.d(TAG, "failed to set parameters")
            e.printStackTrace()
            countCameraParametersException++
        }
    }

    private fun convertFlashModesToValues(supportedFlashModes: List<String>?): MutableList<String> {
        if (MyDebug.LOG) {
            Log.d(TAG, "convertFlashModesToValues()")
            Log.d(
                TAG,
                "supported_flash_modes: $supportedFlashModes"
            )
        }
        val outputModes: MutableList<String> = ArrayList()
        if (supportedFlashModes != null) {
            // also resort as well as converting
            if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_OFF)) {
                outputModes.add("flash_off")
                if (MyDebug.LOG) Log.d(TAG, " supports flash_off")
            }
            if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_AUTO)) {
                outputModes.add("flash_auto")
                if (MyDebug.LOG) Log.d(TAG, " supports flash_auto")
            }
            if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_ON)) {
                outputModes.add("flash_on")
                if (MyDebug.LOG) Log.d(TAG, " supports flash_on")
            }
            if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                outputModes.add("flash_torch")
                if (MyDebug.LOG) Log.d(TAG, " supports flash_torch")
            }
            if (supportedFlashModes.contains(Camera.Parameters.FLASH_MODE_RED_EYE)) {
                outputModes.add("flash_red_eye")
                if (MyDebug.LOG) Log.d(TAG, " supports flash_red_eye")
            }
        }

        // Samsung Galaxy S7 at least for front camera has supportedFlashModes: auto, beach, portrait?!
        // so rather than checking supportedFlashModes, we should check outputModes here
        // this is always why we check whether the size is greater than 1, rather than 0 (this also matches
        // the check we do in Preview.setupCameraParameters()).
        if (outputModes.size > 1) {
            if (MyDebug.LOG) Log.d(TAG, "flash supported")
        } else {
            if (facing === Facing.FACING_FRONT) {
                if (MyDebug.LOG) Log.d(TAG, "front-screen with no flash")
                outputModes.clear() // clear any pre-existing mode (see note above about Samsung Galaxy S7)
                outputModes.add("flash_off")
                outputModes.add("flash_frontscreen_on")
                outputModes.add("flash_frontscreen_torch")
            } else {
                if (MyDebug.LOG) Log.d(TAG, "no flash")
                // probably best to not return any modes, rather than one mode (see note about about Samsung Galaxy S7)
                outputModes.clear()
            }
        }

        return outputModes
    }

    private fun convertFocusModesToValues(supportedFocusModes: List<String>?): MutableList<String> {
        if (MyDebug.LOG) Log.d(TAG, "convertFocusModesToValues()")
        val outputModes: MutableList<String> = ArrayList()
        if (supportedFocusModes != null) {
            // also resort as well as converting
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                outputModes.add("focus_mode_auto")
                if (MyDebug.LOG) {
                    Log.d(TAG, " supports focus_mode_auto")
                }
            }
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                outputModes.add("focus_mode_infinity")
                if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_infinity")
            }
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_MACRO)) {
                outputModes.add("focus_mode_macro")
                if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_macro")
            }
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                outputModes.add("focus_mode_locked")
                if (MyDebug.LOG) {
                    Log.d(TAG, " supports focus_mode_locked")
                }
            }
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
                outputModes.add("focus_mode_fixed")
                if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_fixed")
            }
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_EDOF)) {
                outputModes.add("focus_mode_edof")
                if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_edof")
            }
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                outputModes.add("focus_mode_continuous_picture")
                if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_continuous_picture")
            }
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                outputModes.add("focus_mode_continuous_video")
                if (MyDebug.LOG) Log.d(TAG, " supports focus_mode_continuous_video")
            }
        }
        return outputModes
    }

    override val api: String
        get() = "Camera"

    @get:Throws(CameraControllerException::class)
    override val cameraFeatures: CameraFeatures
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getCameraFeatures()")
            val parameters: Camera.Parameters
            try {
                parameters = this.parameters
            } catch (e: RuntimeException) {
                Log.e(TAG, "failed to get camera parameters")
                e.printStackTrace()
                throw CameraControllerException()
            }
            val cameraFeatures = CameraFeatures()
            cameraFeatures.isZoomSupported = parameters.isZoomSupported
            if (cameraFeatures.isZoomSupported) {
                cameraFeatures.maxZoom = parameters.maxZoom
                try {
                    cameraFeatures.zoomRatios = parameters.zoomRatios
                } catch (e: Throwable) {
                    // crash java.lang.NumberFormatException: Invalid int: " 500" reported in v1.4 on device "es209ra", Android 4.1, 3 Jan 2014
                    // this is from java.lang.Integer.invalidInt(Integer.java:138) - unclear if this is a bug in Open Kamera, all we can do for now is catch it
                    if (MyDebug.LOG) Log.e(TAG, "Exception in getZoomRatios()")
                    e.printStackTrace()
                    cameraFeatures.isZoomSupported = false
                    cameraFeatures.maxZoom = 0
                    cameraFeatures.zoomRatios = null
                }
                if (cameraFeatures.zoomRatios == null || cameraFeatures.zoomRatios!!.isEmpty() || cameraFeatures.maxZoom == 0) {
                    cameraFeatures.isZoomSupported = false
                    cameraFeatures.maxZoom = 0
                    cameraFeatures.zoomRatios = null
                }
            }

            cameraFeatures.supportsFaceDetection = parameters.maxNumDetectedFaces > 0

            // get available sizes
            val cameraPictureSizes = parameters.supportedPictureSizes
            if (cameraPictureSizes == null) {
                // Google Play crashes suggest that getSupportedPictureSizes() can be null?! Better to fail gracefully
                // instead of crashing
                Log.e(TAG, "getSupportedPictureSizes() returned null!")
                throw CameraControllerException()
            }
            cameraFeatures.pictureSizes = ArrayList()
            //camera_features.picture_sizes.add(new CameraController.Size(1920, 1080)); // test
            for (cameraSize in cameraPictureSizes) {
                // we leave supportsBurst as true - strictly speaking it should be false, but we'll never use a fast burst mode
                // with CameraController1 anyway
                cameraFeatures.pictureSizes.add(Size(cameraSize.width, cameraSize.height))
            }
            // sizes are usually already sorted from high to low, but sort just in case
            // note some devices do have sizes in a not fully sorted order (e.g., Nokia 8)
            Collections.sort(cameraFeatures.pictureSizes, SizeSorter())

            //camera_features.supportedFlashModes = parameters.getSupportedFlashModes(); // Android format
            val supportedFlashModes = parameters.supportedFlashModes // Android format
            cameraFeatures.supportedFlashValues =
                convertFlashModesToValues(supportedFlashModes) // convert to our format (also resorts)

            val supportedFocusModes = parameters.supportedFocusModes // Android format
            cameraFeatures.supportedFocusValues =
                convertFocusModesToValues(supportedFocusModes) // convert to our format (also resorts)
            cameraFeatures.maxNumFocusAreas = parameters.maxNumFocusAreas

            cameraFeatures.isExposureLockSupported = parameters.isAutoExposureLockSupported

            cameraFeatures.isWhiteBalanceLockSupported =
                parameters.isAutoWhiteBalanceLockSupported

            cameraFeatures.isVideoStabilizationSupported =
                parameters.isVideoStabilizationSupported

            cameraFeatures.isPhotoVideoRecordingSupported = parameters.isVideoSnapshotSupported

            cameraFeatures.minExposure = parameters.minExposureCompensation
            cameraFeatures.maxExposure = parameters.maxExposureCompensation
            cameraFeatures.exposureStep = exposureCompensationStep
            cameraFeatures.supportsExpoBracketing =
                (cameraFeatures.minExposure !== 0 && cameraFeatures.maxExposure !== 0) // require both a darker and brighter exposure, in order to support expo bracketing
            cameraFeatures.maxExpoBracketingNImages = maxExpoBracketingNImages

            var cameraVideoSizes = parameters.supportedVideoSizes
            if (cameraVideoSizes == null) {
                // if null, we should use the preview sizes - see http://stackoverflow.com/questions/14263521/android-getsupportedvideosizes-allways-returns-null
                if (MyDebug.LOG) Log.d(TAG, "take video_sizes from preview sizes")
                cameraVideoSizes = parameters.supportedPreviewSizes
            }
            cameraFeatures.videoSizes = ArrayList()
            //camera_features.video_sizes.add(new CameraController.Size(1920, 1080)); // test
            if (cameraVideoSizes != null) {
                for (cameraSize in cameraVideoSizes) {
                    cameraFeatures.videoSizes.add(Size(cameraSize.width, cameraSize.height))
                }
                // sizes are usually already sorted from high to low, but sort just in case
                Collections.sort(cameraFeatures.videoSizes, SizeSorter())
            }

            val cameraPreviewSizes = parameters.supportedPreviewSizes
            cameraFeatures.previewSizes = ArrayList()
            if (cameraPreviewSizes != null) {
                for (cameraSize in cameraPreviewSizes) {
                    cameraFeatures.previewSizes.add(Size(cameraSize.width, cameraSize.height))
                }
            }

            if (MyDebug.LOG) Log.d(TAG, "camera parameters: " + parameters.flatten())

            cameraFeatures.canDisableShutterSound = cameraInfo.canDisableShutterSound

            // Determine view angles. Note that these can vary based on the resolution - and since we read these before the caller has
            // set the desired resolution, this isn't strictly correct. However these are presumably view angles for the photo anyway,
            // when some callers (e.g., DrawPreview) want view angles for the preview anyway - so these will only be an approximation for
            // what we want anyway.
            val defaultViewAngleX = 55.0f
            val defaultViewAngleY = 43.0f
            try {
                cameraFeatures.viewAngleX = parameters.horizontalViewAngle
                cameraFeatures.viewAngleY = parameters.verticalViewAngle
            } catch (e: Exception) {
                // apparently some devices throw exceptions...
                e.printStackTrace()
                Log.e(TAG, "exception reading horizontal or vertical view angles")
                cameraFeatures.viewAngleX = defaultViewAngleX
                cameraFeatures.viewAngleY = defaultViewAngleY
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "view_angle_x: " + cameraFeatures.viewAngleX)
                Log.d(TAG, "view_angle_y: " + cameraFeatures.viewAngleY)
            }
            // need to handle some devices reporting rubbish
            if (cameraFeatures.viewAngleX > 150.0f || cameraFeatures.viewAngleY > 150.0f) {
                Log.e(TAG, "camera API reporting stupid view angles, set to sensible defaults")
                cameraFeatures.viewAngleX = defaultViewAngleX
                cameraFeatures.viewAngleY = defaultViewAngleY
            }

            return cameraFeatures
        }

    /** Important, from docs:
     * "Changing scene mode may override other parameters (such as flash mode, focus mode, white balance).
     * For example, suppose originally flash mode is on and supported flash modes are on/off. In night
     * scene mode, both flash mode and supported flash mode may be changed to off. After setting scene
     * mode, applications should call getParameters to know if some parameters are changed."
     */
    override fun setSceneMode(value: String): SupportedValues? {
        val parameters: Camera.Parameters
        try {
            parameters = this.parameters
        } catch (e: RuntimeException) {
            Log.e(TAG, "exception from getParameters")
            e.printStackTrace()
            countCameraParametersException++
            return null
        }
        val values = parameters.supportedSceneModes
        /*{
			// test
			values = new ArrayList<>();
			values.add(ISO_DEFAULT);
		}*/
        val supportedValues = checkModeIsSupported(
            values,
            value, SCENE_MODE_DEFAULT
        )
        if (supportedValues != null) {
            val sceneMode = parameters.sceneMode
            // if scene mode is null, it should mean scene modes aren't supported anyway
            if (sceneMode != null && sceneMode != supportedValues.selectedValue) {
                parameters.sceneMode = supportedValues.selectedValue
                setCameraParameters(parameters)
            }
        }
        return supportedValues
    }

    override val sceneMode: String?
        get() {
            val parameters = this.parameters
            return parameters.sceneMode
        }

    override fun sceneModeAffectsFunctionality(): Boolean {
        // see https://developer.android.com/reference/android/hardware/Camera.Parameters.html#setSceneMode(java.lang.String)
        // "Changing scene mode may override other parameters ... After setting scene mode, applications should call
        // getParameters to know if some parameters are changed."
        return true
    }

    override fun setColorEffect(value: String): SupportedValues? {
        val parameters = this.parameters
        val values = parameters.supportedColorEffects
        val supportedValues = checkModeIsSupported(
            values,
            value, COLOR_EFFECT_DEFAULT
        )
        if (supportedValues != null) {
            val colorEffect = parameters.colorEffect
            // have got nullpointerexception from Google Play, so now check for null
            if (colorEffect == null || colorEffect != supportedValues.selectedValue) {
                parameters.colorEffect = supportedValues.selectedValue
                setCameraParameters(parameters)
            }
        }
        return supportedValues
    }

    override val colorEffect: String?
        get() {
            val parameters = this.parameters
            return parameters.colorEffect
        }

    override fun setWhiteBalance(value: String): SupportedValues? {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setWhiteBalance: $value"
        )
        val parameters = this.parameters
        val values = parameters.supportedWhiteBalance
        if (values != null) {
            // Some devices (e.g., OnePlus 3T) claim to support a "manual" mode, even though this
            // isn't one of the possible white balances defined in Camera.Parameters.
            // Since the old API doesn't support white balance temperatures, and this mode seems to
            // have no useful effect, we remove it to avoid confusion.
            while (values.contains("manual")) {
                values.remove("manual")
            }
        }
        val supportedValues = checkModeIsSupported(values, value, WHITE_BALANCE_DEFAULT)
        if (supportedValues != null) {
            val whiteBalance = parameters.whiteBalance
            // if white balance is null, it should mean white balances aren't supported anyway
            if (whiteBalance != null && whiteBalance != supportedValues.selectedValue) {
                parameters.whiteBalance = supportedValues.selectedValue
                setCameraParameters(parameters)
            }
        }
        return supportedValues
    }

    override val whiteBalance: String?
        get() {
            val parameters = this.parameters
            return parameters.whiteBalance
        }

    override fun setWhiteBalanceTemperature(temperature: Int): Boolean {
        // not supported for CameraController1
        return false
    }

    override val whiteBalanceTemperature: Int
        // not supported for CameraController1
        get() = 0

    override fun setAntiBanding(value: String): SupportedValues? {
        val parameters = this.parameters
        val values = parameters.supportedAntibanding
        val supportedValues = checkModeIsSupported(
            values,
            value, ANTIBANDING_DEFAULT
        )
        if (supportedValues != null) {
            // for antibanding, if the requested value isn't available, we don't modify it at all
            // (so we stick with the device's default setting)
            if (supportedValues.selectedValue.equals(value)) {
                val antibanding = parameters.antibanding
                if (antibanding == null || antibanding != supportedValues.selectedValue) {
                    parameters.antibanding = supportedValues.selectedValue
                    setCameraParameters(parameters)
                }
            }
        }
        return supportedValues
    }

    override val antiBanding: String?
        get() {
            val parameters = this.parameters
            return parameters.antibanding
        }

    override fun setEdgeMode(value: String): SupportedValues? {
        return null
    }

    override val edgeMode: String?
        get() = null

    override fun setNoiseReductionMode(value: String): SupportedValues? {
        return null
    }

    override val noiseReductionMode: String?
        get() = null

    override fun setISO(value: String?): SupportedValues? {
        val parameters = this.parameters
        // get available isos - no standard value for this, see http://stackoverflow.com/questions/2978095/android-camera-api-iso-setting
        var isoValues = parameters["iso-values"]
        if (isoValues == null) {
            isoValues = parameters["iso-mode-values"] // Galaxy Nexus
            if (isoValues == null) {
                isoValues = parameters["iso-speed-values"] // Micromax A101
                if (isoValues == null) isoValues =
                    parameters["nv-picture-iso-values"] // LG dual P990
            }
        }
        var values = mutableListOf<String>()
        if (isoValues != null && isoValues.length > 0) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "iso_values: $isoValues"
            )
            val isosArray =
                isoValues.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            // split shouldn't return null
            if (isosArray.size > 0) {
                // remove duplicates (OnePlus 3T has several duplicate "auto" entries)
                val hashSet = HashSet<String>()
                // use hashset for efficiency
                // make sure we alo preserve the order
                for (iso in isosArray) {
                    if (!hashSet.contains(iso)) {
                        values.add(iso)
                        hashSet.add(iso)
                    }
                }
            }
        }

        _isoKey = "iso"
        if (parameters[_isoKey] == null) {
            _isoKey = "iso-speed" // Micromax A101
            if (parameters[_isoKey] == null) {
                _isoKey = "nv-picture-iso" // LG dual P990
                if (parameters[_isoKey] == null) {
                    _isoKey =
                        if (Build.MODEL.contains("Z00")) "iso" // Asus Zenfone 2 Z00A and Z008: see https://sourceforge.net/p/OpenKamera/tickets/183/
                        else null // not supported
                }
            }
        }
        /*values = new ArrayList<>();
		//values.add(ISO_DEFAULT);
		//values.add("ISO_HJR");
		values.add("ISO50");
		values.add("ISO64");
		values.add("ISO80");
		values.add("ISO100");
		values.add("ISO125");
		values.add("ISO160");
		values.add("ISO200");
		values.add("ISO250");
		values.add("ISO320");
		values.add("ISO400");
		values.add("ISO500");
		values.add("ISO640");
		values.add("ISO800");
		values.add("ISO1000");
		values.add("ISO1250");
		values.add("ISO1600");
		values.add("ISO2000");
		values.add("ISO2500");
		values.add("ISO3200");
		values.add(ISO_DEFAULT);
		//values.add("400");
		//values.add("800");
		//values.add("1600");
		-isoKey = "iso";*/
        if (_isoKey != null) {
            if (values.isEmpty()) {
                // set a default for some devices which have an _isoKey, but don't give a list of supported ISOs
                values = mutableListOf()
                values.add(ISO_DEFAULT)
                values.add("50")
                values.add("100")
                values.add("200")
                values.add("400")
                values.add("800")
                values.add("1600")
            }
            val supportedValues = checkModeIsSupported(values, value!!, ISO_DEFAULT)
            if (supportedValues != null) {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "set: " + _isoKey + " to: " + supportedValues.selectedValue
                )
                parameters[_isoKey] = supportedValues.selectedValue
                setCameraParameters(parameters)
            }
            return supportedValues
        }
        return null
    }

    override val isoKey: String
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getISOKey")
            return this._isoKey ?: ""
        }

    override fun setManualISO(manualIso: Boolean, iso: Int) {
        // not supported for CameraController1
    }

    override val isManualISO: Boolean
        get() =// not supported for CameraController1
            false

    override fun setISO(iso: Int): Boolean {
        // not supported for CameraController1
        return false
    }

    override val iSO: Int
        get() =// not supported for CameraController1
            0

    override val exposureTime: Long
        get() =// not supported for CameraController1
            0L

    override fun setExposureTime(exposureTime: Long): Boolean {
        // not supported for CameraController1
        return false
    }

    override fun setAperture(aperture: Float) {
        // not supported for CameraController1
    }

    override val pictureSize: Size
        /*Camera.Parameters parameters = this.getParameters();
             Camera.Size cameraSize = parameters.getPictureSize();
             return new CameraController.Size(camera_size.width, camera_size.height);*/
        get() = Size(pictureWidth, pictureHeight)

    override fun setPictureSize(width: Int, height: Int) {
        val parameters = this.parameters
        this.pictureWidth = width
        this.pictureHeight = height
        parameters.setPictureSize(width, height)
        if (MyDebug.LOG) Log.d(
            TAG,
            "set picture size: " + parameters.pictureSize.width + ", " + parameters.pictureSize.height
        )
        setCameraParameters(parameters)
    }

    override val previewSize: Size
        get() {
            val parameters = this.parameters
            val cameraSize = parameters.previewSize
            return Size(cameraSize.width, cameraSize.height)
        }

    override fun setPreviewSize(width: Int, height: Int) {
        val parameters = this.parameters
        if (MyDebug.LOG) Log.d(
            TAG,
            "current preview size: " + parameters.previewSize.width + ", " + parameters.previewSize.height
        )
        parameters.setPreviewSize(width, height)
        if (MyDebug.LOG) Log.d(
            TAG,
            "new preview size: " + parameters.previewSize.width + ", " + parameters.previewSize.height
        )
        setCameraParameters(parameters)
    }

    override fun setCameraExtension(enabled: Boolean, extension: Int) {
        // not supported
    }

    override val isCameraExtension: Boolean
        get() = false

    override fun getCameraExtension(): Int {
        return -1
    }

    override var burstType: BurstType
        get() = if (wantExpoBracketing) BurstType.BURSTTYPE_EXPO else BurstType.BURSTTYPE_NONE
        set(burstType) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "setBurstType: $burstType"
            )
            if (camera == null) {
                if (MyDebug.LOG) Log.e(TAG, "no camera")
                return
            }
            if (burstType !== BurstType.BURSTTYPE_NONE && burstType !== BurstType.BURSTTYPE_EXPO) {
                Log.e(TAG, "burst type not supported")
                return
            }
            this.wantExpoBracketing = burstType === BurstType.BURSTTYPE_EXPO
        }

    override fun setBurstNImages(burstRequestedNImages: Int) {
        // not supported
    }

    override fun setBurstForNoiseReduction(
        burstForNoiseReduction: Boolean,
        noiseReductionLowLight: Boolean
    ) {
        // not supported
    }

    override val isContinuousBurstInProgress: Boolean
        get() =// not supported
            false

    override fun stopContinuousBurst() {
        // not supported
    }

    override fun stopFocusBracketingBurst() {
        // not supported
    }

    override fun setExpoBracketingNImages(nImages: Int) {
        var nImages = nImages
        if (MyDebug.LOG) Log.d(
            TAG,
            "setExpoBracketingNImages: $nImages"
        )
        if (nImages <= 1 || (nImages % 2) == 0) {
            if (MyDebug.LOG) Log.e(TAG, "n_images should be an odd number greater than 1")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        if (nImages > maxExpoBracketingNImages) {
            nImages = maxExpoBracketingNImages
            if (MyDebug.LOG) Log.e(
                TAG,
                "limiting n_images to max of $nImages"
            )
        }
        this.expoBracketingNImages = nImages
    }

    override fun setExpoBracketingStops(stops: Double) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setExpoBracketingStops: $stops"
        )
        if (stops <= 0.0) {
            if (MyDebug.LOG) Log.e(TAG, "stops should be positive")
            throw RuntimeException() // throw as RuntimeException, as this is a programming error
        }
        this.expoBracketingStops = stops
    }

    override fun setDummyCaptureHack(dummyCaptureHack: Boolean) {
        // not supported for CameraController1
    }

    override fun setUseExpoFastBurst(useExpoFastBurst: Boolean) {
        // not supported for CameraController1
    }

    override val isCaptureFastBurst: Boolean
        get() =// not supported for CameraController1
            false

    override val isCapturingBurst: Boolean
        get() = burstTotal > 1 && nBurstTaken < burstTotal

    override val nBurstTaken: Int
        get() = pendingBurstImages.size

    override fun setJpegR(wantJpegR: Boolean) {
        // not supported for CameraController1
    }

    override fun setRaw(wantRaw: Boolean, maxRawImages: Int) {
        // not supported for CameraController1
    }

    override fun setVideoHighSpeed(setVideoHighSpeed: Boolean) {
        // not supported for CameraController1
    }

    override val opticalStabilization: Boolean
        get() =// not supported for CameraController1
            false

    override var videoStabilization: Boolean
        get() {
            try {
                val parameters = this.parameters
                return parameters.videoStabilization
            } catch (e: RuntimeException) {
                // have had crashes from Google Play for getParameters - assume video stabilization not enabled
                Log.e(TAG, "failed to get parameters for video stabilization")
                e.printStackTrace()
                countCameraParametersException++
                return false
            }
        }
        set(enabled) {
            val parameters = this.parameters
            parameters.videoStabilization = enabled
            setCameraParameters(parameters)
        }

    override fun setTonemapProfile(
        tonemapProfile: TonemapProfile,
        logProfileStrength: Float,
        gamma: Float
    ) {
        // not supported for CameraController1!
    }

    override val tonemapProfile: TonemapProfile
        get() =// not supported for CameraController1!
            TonemapProfile.TONEMAPPROFILE_OFF

    override var jpegQuality: Int
        get() {
            val parameters = this.parameters
            return parameters.jpegQuality
        }
        set(quality) {
            val parameters = this.parameters
            parameters.jpegQuality = quality
            setCameraParameters(parameters)
        }

    /*Camera.Parameters parameters = this.getParameters();
         return parameters.getZoom();*/
    override var zoom: Int
        get() = this.currentZoomValue
        set(value) {
            try {
                val parameters = this.parameters
                if (MyDebug.LOG) Log.d(TAG, "zoom was: " + parameters.zoom)
                this.currentZoomValue = value
                parameters.zoom = value
                setCameraParameters(parameters)
            } catch (e: RuntimeException) {
                Log.e(TAG, "failed to set parameters for zoom")
                e.printStackTrace()
                countCameraParametersException++
            }
        }

    override fun setZoom(value: Int, smoothZoom: Float) {
        zoom = value
    }

    override fun setZoomSticky(sticky: Boolean): List<Int>? {
        return null
    }

    override fun resetZoom() {
        zoom = 0
    }

    private val exposureCompensationStep: Float
        get() {
            var exposureStep: Float
            val parameters = this.parameters
            try {
                exposureStep = parameters.exposureCompensationStep
            } catch (e: Exception) {
                // received a NullPointerException from StringToReal.parseFloat() beneath getExposureCompensationStep() on Google Play!
                if (MyDebug.LOG) Log.e(TAG, "exception from getExposureCompensationStep()")
                e.printStackTrace()
                exposureStep = 1.0f / 3.0f // make up a typical example
            }
            return exposureStep
        }

    // Returns whether exposure was modified
    override fun setExposureCompensation(newExposure: Int): Boolean {
        /*Camera.Parameters parameters = this.getParameters();
		int currentExposure = parameters.getExposureCompensation();
		if( newExposure != currentExposure ) {*/
        if (newExposure != exposureCompensation) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "change exposure from $exposureCompensation to $newExposure"
            )
            val parameters = this.parameters
            this.exposureCompensation = newExposure
            parameters.exposureCompensation = newExposure
            setCameraParameters(parameters)
            return true
        }
        return false
    }

    override fun setPreviewFpsRange(min: Int, max: Int) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setPreviewFpsRange: $min to $max"
        )
        try {
            val parameters = this.parameters
            parameters.setPreviewFpsRange(min, max)
            setCameraParameters(parameters)
        } catch (e: RuntimeException) {
            // can get RuntimeException from getParameters - we don't catch within that function because callers may not be able to recover,
            // but here it doesn't really matter if we fail to set the fps range
            Log.e(TAG, "setPreviewFpsRange failed to get parameters")
            e.printStackTrace()
            countCameraParametersException++
        }
    }

    override fun clearPreviewFpsRange() {
        if (MyDebug.LOG) Log.d(TAG, "clearPreviewFpsRange")
        // not supported for old API
    }

    override val supportedPreviewFpsRange: List<IntArray>?
        get() {
            try {
                val parameters = this.parameters
                return parameters.supportedPreviewFpsRange
            } catch (e: RuntimeException) {
                /* N.B, have had reports of StringIndexOutOfBoundsException on Google Play on Sony Xperia M devices
                     at android.hardware.Camera$Parameters.splitRange(Camera.java:4098)
                     at android.hardware.Camera$Parameters.getSupportedPreviewFpsRange(Camera.java:2799)
                   But that's a subclass of RuntimeException which we now catch anyway.
                   */
                e.printStackTrace()
                countCameraParametersException++
            }
            return null
        }

    private fun convertFocusModeToValue(focusMode: String?): String {
        // focusMode may be null on some devices; we return ""
        if (MyDebug.LOG) Log.d(
            TAG,
            "convertFocusModeToValue: $focusMode"
        )
        var focusValue = ""
        if (focusMode == null) {
            // ignore, leave focusValue at ""
        } else if (focusMode == Camera.Parameters.FOCUS_MODE_AUTO) {
            focusValue = "focus_mode_auto"
        } else if (focusMode == Camera.Parameters.FOCUS_MODE_INFINITY) {
            focusValue = "focus_mode_infinity"
        } else if (focusMode == Camera.Parameters.FOCUS_MODE_MACRO) {
            focusValue = "focus_mode_macro"
        } else if (focusMode == Camera.Parameters.FOCUS_MODE_FIXED) {
            focusValue = "focus_mode_fixed"
        } else if (focusMode == Camera.Parameters.FOCUS_MODE_EDOF) {
            focusValue = "focus_mode_edof"
        } else if (focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) {
            focusValue = "focus_mode_continuous_picture"
        } else if (focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) {
            focusValue = "focus_mode_continuous_video"
        }
        return focusValue
    }

    override var focusValue: String?
        get() {
            // returns "" if Parameters.getFocusMode() returns null
            val parameters = this.parameters
            val focusMode = parameters.focusMode
            // getFocusMode() is documented as never returning null, however I've had null pointer exceptions reported in Google Play
            return convertFocusModeToValue(focusMode)
        }
        set(focusValue) {
            val parameters = this.parameters
            when (focusValue) {
                "focus_mode_auto", "focus_mode_locked" -> parameters.focusMode =
                    Camera.Parameters.FOCUS_MODE_AUTO

                "focus_mode_infinity" -> parameters.focusMode =
                    Camera.Parameters.FOCUS_MODE_INFINITY

                "focus_mode_macro" -> parameters.focusMode = Camera.Parameters.FOCUS_MODE_MACRO
                "focus_mode_fixed" -> parameters.focusMode = Camera.Parameters.FOCUS_MODE_FIXED
                "focus_mode_edof" -> parameters.focusMode = Camera.Parameters.FOCUS_MODE_EDOF
                "focus_mode_continuous_picture" -> parameters.focusMode =
                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE

                "focus_mode_continuous_video" -> parameters.focusMode =
                    Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO

                else -> if (MyDebug.LOG) Log.d(
                    TAG,
                    "setFocusValue() received unknown focus value $focusValue"
                )
            }
            setCameraParameters(parameters)
        }

    override val focusDistance: Float
        get() =// not supported for CameraController1!
            0.0f

    override fun setFocusDistance(focusDistance: Float): Boolean {
        // not supported for CameraController1!
        return false
    }

    override fun setFocusBracketingNImages(nImages: Int) {
        // not supported for CameraController1
    }

    override fun setFocusBracketingAddInfinity(focusBracketingAddInfinity: Boolean) {
        // not supported for CameraController1
    }

    override var focusBracketingSourceDistance: Float
        get() =// not supported for CameraController1!
            0.0f
        set(focusBracketingSourceDistance) {
            // not supported for CameraController1!
        }

    override fun setFocusBracketingSourceDistanceFromCurrent() {
        // not supported for CameraController1!
    }

    override var focusBracketingTargetDistance: Float
        get() =// not supported for CameraController1!
            0.0f
        set(focusBracketingTargetDistance) {
            // not supported for CameraController1!
        }

    private fun convertFlashValueToMode(flashValue: String?): String {
        var flashMode = ""
        when (flashValue) {
            "flash_off", "flash_frontscreen_on", "flash_frontscreen_torch" -> flashMode =
                Camera.Parameters.FLASH_MODE_OFF

            "flash_auto" -> flashMode = Camera.Parameters.FLASH_MODE_AUTO
            "flash_on" -> flashMode = Camera.Parameters.FLASH_MODE_ON
            "flash_torch" -> flashMode = Camera.Parameters.FLASH_MODE_TORCH
            "flash_red_eye" -> flashMode = Camera.Parameters.FLASH_MODE_RED_EYE
        }
        return flashMode
    }

    private fun convertFlashModeToValue(flashMode: String?): String {
        // flashMode may be null, meaning flash isn't supported; we return ""
        if (MyDebug.LOG) Log.d(
            TAG,
            "convertFlashModeToValue: $flashMode"
        )
        var flashValue = ""
        if (flashMode == null) {
            // ignore, leave focusValue at ""
        } else if (flashMode == Camera.Parameters.FLASH_MODE_OFF) {
            flashValue = "flash_off"
        } else if (flashMode == Camera.Parameters.FLASH_MODE_AUTO) {
            flashValue = "flash_auto"
        } else if (flashMode == Camera.Parameters.FLASH_MODE_ON) {
            flashValue = "flash_on"
        } else if (flashMode == Camera.Parameters.FLASH_MODE_TORCH) {
            flashValue = "flash_torch"
        } else if (flashMode == Camera.Parameters.FLASH_MODE_RED_EYE) {
            flashValue = "flash_red_eye"
        }
        return flashValue
    }

    override var flashValue: String
        get() {
            // returns "" if flash isn't supported
            val parameters = this.parameters
            val flashMode = parameters.flashMode // will be null if flash mode not supported
            return convertFlashModeToValue(flashMode)
        }
        set(flashValue) {
            val parameters = this.parameters
            if (MyDebug.LOG) Log.d(
                TAG,
                "setFlashValue: $flashValue"
            )

            this.frontscreenFlash = false
            if (flashValue == "flash_frontscreen_on") {
                // we do this check first due to weird behaviour on Samsung Galaxy S7 front camera where parameters.getFlashMode() returns values (auto, beach, portrait)
                this.frontscreenFlash = true
                return
            }

            if (parameters.flashMode == null) {
                if (MyDebug.LOG) Log.d(TAG, "flash mode not supported")
                return
            }

            val flashMode = convertFlashValueToMode(flashValue)
            if (flashMode.length > 0 && flashMode != parameters.flashMode) {
                if (parameters.flashMode == Camera.Parameters.FLASH_MODE_TORCH && flashMode != Camera.Parameters.FLASH_MODE_OFF) {
                    // workaround for bug on Nexus 5 and Nexus 6 where torch doesn't switch off until we set FLASH_MODE_OFF
                    if (MyDebug.LOG) Log.d(TAG, "first turn torch off")
                    parameters.flashMode = Camera.Parameters.FLASH_MODE_OFF
                    setCameraParameters(parameters)
                    // need to set the correct flash mode after a delay
                    val handler = Handler()
                    handler.postDelayed(object : Runnable {
                        override fun run() {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "now set actual flash mode after turning torch off"
                            )
                            if (camera != null) { // make sure camera wasn't released in the meantime (has a Google Play crash as a result of this)
                                parameters.flashMode = flashMode
                                setCameraParameters(parameters)
                            }
                        }
                    }, 100)
                } else {
                    parameters.flashMode = flashMode
                    setCameraParameters(parameters)
                }
            }
        }

    override fun setRecordingHint(hint: Boolean) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "setRecordingHint: $hint"
        )
        try {
            val parameters = this.parameters
            // Calling setParameters here with continuous video focus mode causes preview to not restart after taking a photo on Galaxy Nexus?! (fine on my Nexus 7).
            // The issue seems to specifically be with setParameters (i.e., the problem occurs even if we don't setRecordingHint).
            // In addition, I had a report of a bug on HTC Desire X, Android 4.0.4 where the saved video was corrupted.
            // This worked fine in 1.7, then not in 1.8 and 1.9, then was fixed again in 1.10
            // The only thing in common to 1.7->1.8 and 1.9-1.10, that seems relevant, was adding this code to setRecordingHint() and setParameters() (unclear which would have been the problem),
            // so we should be very careful about enabling this code again!
            // Update for v1.23: the bug with Galaxy Nexus has come back (see comments in Preview.setPreviewFps()) and is now unavoidable,
            // but I've still kept this check here - if nothing else, because it apparently caused video recording problems on other devices too.
            // Update for v1.29: this doesn't seem to happen on Galaxy Nexus with continuous picture focus mode, which is what we now use; but again, still keepin the check here due to possible problems on other devices
            val focusMode = parameters.focusMode
            // getFocusMode() is documented as never returning null, however I've had null pointer exceptions reported in Google Play
            if (focusMode != null && focusMode != Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) {
                parameters.setRecordingHint(hint)
                setCameraParameters(parameters)
            }
        } catch (e: RuntimeException) {
            // can get RuntimeException from getParameters - we don't catch within that function because callers may not be able to recover,
            // but here it doesn't really matter if we fail to set the recording hint
            Log.e(TAG, "setRecordingHint failed to get parameters")
            e.printStackTrace()
            countCameraParametersException++
        }
    }

    override var autoExposureLock: Boolean
        get() {
            val parameters = this.parameters
            if (!parameters.isAutoExposureLockSupported) return false
            return parameters.autoExposureLock
        }
        set(enabled) {
            val parameters = this.parameters
            parameters.autoExposureLock = enabled
            setCameraParameters(parameters)
        }

    override var autoWhiteBalanceLock: Boolean
        get() {
            val parameters = this.parameters
            if (!parameters.isAutoWhiteBalanceLockSupported) return false
            return parameters.autoWhiteBalanceLock
        }
        set(enabled) {
            val parameters = this.parameters
            parameters.autoWhiteBalanceLock = enabled
            setCameraParameters(parameters)
        }

    override fun setRotation(rotation: Int) {
        val parameters = this.parameters
        parameters.setRotation(rotation)
        setCameraParameters(parameters)
    }

    override fun setLocationInfo(location: Location) {
        // don't log location, in case of privacy!
        if (MyDebug.LOG) Log.d(TAG, "setLocationInfo")
        val parameters = this.parameters
        parameters.removeGpsData()
        parameters.setGpsTimestamp(System.currentTimeMillis() / 1000) // initialise to a value (from Android camera source)
        parameters.setGpsLatitude(location.latitude)
        parameters.setGpsLongitude(location.longitude)
        parameters.setGpsProcessingMethod(location.provider) // from http://boundarydevices.com/how-to-write-an-android-camera-app/
        if (location.hasAltitude()) {
            parameters.setGpsAltitude(location.altitude)
        } else {
            // Android camera source claims we need to fake one if not present
            // and indeed, this is needed to fix crash on Nexus 7
            parameters.setGpsAltitude(0.0)
        }
        if (location.time != 0L) { // from Android camera source
            parameters.setGpsTimestamp(location.time / 1000)
        }
        setCameraParameters(parameters)
    }

    override fun removeLocationInfo() {
        val parameters = this.parameters
        parameters.removeGpsData()
        setCameraParameters(parameters)
    }

    override fun enableShutterSound(enabled: Boolean) {
        camera?.enableShutterSound(enabled)
        soundsEnabled = enabled
    }

    override fun setFocusAndMeteringArea(areas: List<Area>): Boolean {
        val cameraAreas: MutableList<Camera.Area> = ArrayList()
        for (area in areas) {
            cameraAreas.add(Camera.Area(area.rect, area.weight))
        }
        try {
            val parameters = this.parameters
            val focusMode = parameters.focusMode
            // getFocusMode() is documented as never returning null, however I've had null pointer exceptions reported in Google Play
            if (parameters.maxNumFocusAreas != 0 && focusMode != null && (focusMode == Camera.Parameters.FOCUS_MODE_AUTO || focusMode == Camera.Parameters.FOCUS_MODE_MACRO || focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE || focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                parameters.focusAreas = cameraAreas

                // also set metering areas
                if (parameters.maxNumMeteringAreas == 0) {
                    if (MyDebug.LOG) Log.d(TAG, "metering areas not supported")
                } else {
                    parameters.meteringAreas = cameraAreas
                }

                setCameraParameters(parameters)

                return true
            } else if (parameters.maxNumMeteringAreas != 0) {
                parameters.meteringAreas = cameraAreas

                setCameraParameters(parameters)
            } else {
                if (MyDebug.LOG) Log.d(TAG, "metering areas not supported")
            }
        } catch (e: RuntimeException) {
            e.printStackTrace()
            countCameraParametersException++
        }
        return false
    }

    override fun clearFocusAndMetering() {
        try {
            val parameters = this.parameters
            var updateParameters = false
            if (parameters.maxNumFocusAreas > 0) {
                parameters.focusAreas = null
                updateParameters = true
            }
            if (parameters.maxNumMeteringAreas > 0) {
                parameters.meteringAreas = null
                updateParameters = true
            }
            if (updateParameters) {
                setCameraParameters(parameters)
            }
        } catch (e: RuntimeException) {
            e.printStackTrace()
            countCameraParametersException++
        }
    }

    override val focusAreas: List<Area>?
        get() {
            val parameters = this.parameters
            val cameraAreas = parameters.focusAreas ?: return null
            val areas: MutableList<Area> = ArrayList()
            for (cameraArea in cameraAreas) {
                areas.add(Area(cameraArea.rect, cameraArea.weight))
            }
            return areas
        }

    override val meteringAreas: List<Area>?
        get() {
            val parameters = this.parameters
            val cameraAreas = parameters.meteringAreas ?: return null
            val areas: MutableList<Area> = ArrayList()
            for (cameraArea in cameraAreas) {
                areas.add(Area(cameraArea.rect, cameraArea.weight))
            }
            return areas
        }

    override fun supportsAutoFocus(): Boolean {
        try {
            val parameters = this.parameters
            val focusMode = parameters.focusMode
            // getFocusMode() is documented as never returning null, however I've had null pointer exceptions reported in Google Play from the below line (v1.7),
            // on Galaxy Tab 10.1 (GT-P7500), Android 4.0.3 - 4.0.4; HTC EVO 3D X515m (shooteru), Android 4.0.3 - 4.0.4
            if (focusMode != null && (focusMode == Camera.Parameters.FOCUS_MODE_AUTO || focusMode == Camera.Parameters.FOCUS_MODE_MACRO)) {
                return true
            }
        } catch (e: RuntimeException) {
            e.printStackTrace()
            countCameraParametersException++
        }
        return false
    }

    override fun supportsMetering(): Boolean {
        try {
            val parameters = this.parameters
            return parameters.maxNumMeteringAreas > 0
        } catch (e: RuntimeException) {
            e.printStackTrace()
            countCameraParametersException++
        }
        return false
    }

    override fun focusIsContinuous(): Boolean {
        try {
            val parameters = this.parameters
            val focusMode = parameters.focusMode
            // getFocusMode() is documented as never returning null, however I've had null pointer exceptions reported in Google Play from the below line (v1.7),
            // on Galaxy Tab 10.1 (GT-P7500), Android 4.0.3 - 4.0.4; HTC EVO 3D X515m (shooteru), Android 4.0.3 - 4.0.4
            if (focusMode != null && (focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE || focusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                return true
            }
        } catch (e: RuntimeException) {
            e.printStackTrace()
            countCameraParametersException++
        }
        return false
    }

    override fun focusIsVideo(): Boolean {
        val parameters = this.parameters
        val currentFocusMode = parameters.focusMode
        // getFocusMode() is documented as never returning null, however I've had null pointer exceptions reported in Google Play
        val focusIsVideo =
            currentFocusMode != null && currentFocusMode == Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        if (MyDebug.LOG) {
            Log.d(
                TAG,
                "current_focus_mode: $currentFocusMode"
            )
            Log.d(TAG, "focus_is_video: $focusIsVideo")
        }
        return focusIsVideo
    }

    @Throws(CameraControllerException::class)
    override fun reconnect() {
        if (MyDebug.LOG) Log.d(TAG, "reconnect")
        try {
            camera?.reconnect()
        } catch (e: Exception) {
            if (MyDebug.LOG) Log.e(TAG, "reconnect threw exception: ${e.message}")
            try {
                camera?.lock()
            } catch (e2: Exception) {
                if (MyDebug.LOG) Log.e(TAG, "lock threw exception: ${e2.message}")
            }
        }
    }

    @Throws(CameraControllerException::class)
    override fun setPreviewDisplay(holder: SurfaceHolder?) {
        if (MyDebug.LOG) Log.d(TAG, "setPreviewDisplay")
        try {
            camera?.setPreviewDisplay(holder)
        } catch (e: IOException) {
            e.printStackTrace()
            throw CameraControllerException()
        }
    }

    @Throws(CameraControllerException::class)
    override fun setPreviewTexture(texture: TextureView) {
        if (MyDebug.LOG) Log.d(TAG, "setPreviewTexture")
        try {
            camera?.setPreviewTexture(texture.surfaceTexture)
        } catch (e: IOException) {
            e.printStackTrace()
            throw CameraControllerException()
        }
    }

    @Throws(CameraControllerException::class)
    override fun startPreview() {
        if (MyDebug.LOG) Log.d(TAG, "startPreview")
        try {
            camera?.startPreview()
        } catch (e: RuntimeException) {
            if (MyDebug.LOG) Log.e(TAG, "failed to start preview")
            e.printStackTrace()
            throw CameraControllerException()
        }
    }

    override fun stopRepeating() {
        // not relevant for old camera API
    }

    override fun stopPreview() {
        if (camera != null) {
            // have had crashes when this is called from Preview/CloseCameraTask.
            camera?.stopPreview()
        }
    }

    // returns false if RuntimeException thrown (may include if face-detection already started)
    override fun startFaceDetection(): Boolean {
        if (MyDebug.LOG) Log.d(TAG, "startFaceDetection")
        try {
            camera?.startFaceDetection()
        } catch (e: RuntimeException) {
            if (MyDebug.LOG) Log.d(TAG, "face detection failed or already started")
            countCameraParametersException++
            return false
        }
        return true
    }

    override fun setFaceDetectionListener(listener: FaceDetectionListener?) {
        if (listener != null) {
            class CameraFaceDetectionListener : Camera.FaceDetectionListener {
                override fun onFaceDetection(cameraFaces: Array<Camera.Face>, camera: Camera) {
                    val faces = arrayOfNulls<Face>(cameraFaces.size)
                    for (i in cameraFaces.indices) {
                        faces[i] = Face(cameraFaces[i].score, cameraFaces[i].rect)
                    }
                    listener.onFaceDetection(faces)
                }
            }
            camera?.setFaceDetectionListener(CameraFaceDetectionListener())
        } else {
            camera?.setFaceDetectionListener(null)
        }
    }

    override fun autoFocus(cb: AutoFocusCallback, captureFollowsAutofocusHint: Boolean) {
        if (MyDebug.LOG) Log.d(TAG, "autoFocus")
        class MyAutoFocusCallback : Camera.AutoFocusCallback {
            var doneAutofocus: Boolean = false
            val handler: Handler = Handler()
            val runnable: Runnable = Runnable {
                if (MyDebug.LOG) Log.d(TAG, "autofocus timeout check")
                autofocusTimeoutRunnable = null
                autofocusTimeoutHandler = null
                if (!doneAutofocus) {
                    Log.e(TAG, "autofocus timeout!")
                    doneAutofocus = true
                    cb?.onAutoFocus(false)
                }
            }

            fun setTimeout() {
                handler.postDelayed(runnable, 2000) // set autofocus timeout
            }

            override fun onAutoFocus(success: Boolean, camera: Camera) {
                if (MyDebug.LOG) Log.d(TAG, "autoFocus.onAutoFocus")
                handler.removeCallbacks(runnable)
                autofocusTimeoutRunnable = null
                autofocusTimeoutHandler = null
                // in theory we should only ever get one call to onAutoFocus(), but some Samsung phones at least can call the callback multiple times
                // see http://stackoverflow.com/questions/36316195/take-picture-fails-on-samsung-phones
                // needed to fix problem on Samsung S7 with flash auto/on and continuous picture focus where it would claim failed to take picture even though it'd succeeded,
                // because we repeatedly call takePicture(), and the subsequent ones cause a runtime exception
                // update: also the doneAutofocus flag is needed in case we had an autofocus timeout, see above
                if (!doneAutofocus) {
                    doneAutofocus = true
                    cb?.onAutoFocus(success)
                } else {
                    if (MyDebug.LOG) Log.e(TAG, "ignore repeated autofocus")
                }
            }
        }

        val cameraCb = MyAutoFocusCallback()
        autofocusTimeoutHandler = cameraCb.handler
        autofocusTimeoutRunnable = cameraCb.runnable

        try {
            cameraCb.setTimeout()
            camera?.autoFocus(cameraCb)
        } catch (e: RuntimeException) {
            // just in case? We got a RuntimeException report here from 1 user on Google Play:
            // 21 Dec 2013, Xperia Go, Android 4.1
            if (MyDebug.LOG) Log.e(TAG, "runtime exception from autoFocus")
            e.printStackTrace()
            if (autofocusTimeoutHandler != null) {
                if (autofocusTimeoutRunnable != null) {
                    autofocusTimeoutHandler?.removeCallbacks(autofocusTimeoutRunnable!!)
                    autofocusTimeoutRunnable = null
                }
                autofocusTimeoutHandler = null
            }
            // should call the callback, so the application isn't left waiting (e.g., when we autofocus before trying to take a photo)
            cb?.onAutoFocus(false)
        }
    }

    override fun setCaptureFollowAutofocusHint(captureFollowsAutofocusHint: Boolean) {
        // unused by this API
    }

    override fun cancelAutoFocus() {
        try {
            camera?.cancelAutoFocus()
            if (autofocusTimeoutHandler != null) {
                if (autofocusTimeoutRunnable != null) {
                    // so we don't trigger autofocus timeout
                    autofocusTimeoutHandler?.removeCallbacks(autofocusTimeoutRunnable!!)
                    autofocusTimeoutRunnable = null
                }
                autofocusTimeoutHandler = null
            }
        } catch (e: RuntimeException) {
            // had a report of crash on some devices, see comment at https://sourceforge.net/p/OpenKamera/tickets/4/ made on 20140520
            if (MyDebug.LOG) Log.d(TAG, "cancelAutoFocus() failed")
            e.printStackTrace()
        }
    }

    override fun setContinuousFocusMoveCallback(cb: ContinuousFocusMoveCallback?) {
        if (MyDebug.LOG) Log.d(TAG, "setContinuousFocusMoveCallback")
        run {
            try {
                if (cb != null) {
                    camera?.setAutoFocusMoveCallback { start, camera ->
                        if (MyDebug.LOG) Log.d(
                            TAG,
                            "onAutoFocusMoving: $start"
                        )
                        cb.onContinuousFocusMove(start)
                    }
                } else {
                    camera?.setAutoFocusMoveCallback(null)
                }
            } catch (e: RuntimeException) {
                // received RuntimeException reports from some users on Google Play - seems to be older devices, but still important to catch!
                if (MyDebug.LOG) Log.e(TAG, "runtime exception from setAutoFocusMoveCallback")
                e.printStackTrace()
            }
        }
    }

    private class TakePictureShutterCallback : ShutterCallback {
        // don't do anything here, but we need to implement the callback to get the shutter sound (at least on Galaxy Nexus and Nexus 7)
        override fun onShutter() {
            if (MyDebug.LOG) Log.d(TAG, "shutterCallback.onShutter()")
        }
    }

    private fun clearPending() {
        if (MyDebug.LOG) Log.d(TAG, "clearPending")
        pendingBurstImages.clear()
        burstExposures = null
        burstTotal = 0
    }

    private fun takePictureNow(picture: PictureCallback?, error: ErrorCallback) {
        if (MyDebug.LOG) Log.d(TAG, "takePictureNow")

        // only set the shutter callback if sounds enabled
        val shutter: ShutterCallback? =
            if (soundsEnabled) CameraController1.TakePictureShutterCallback() else null
        val cameraJpeg: Camera.PictureCallback? =
            if (picture == null) null else object : Camera.PictureCallback {
                override fun onPictureTaken(data: ByteArray, cam: Camera) {
                    if (MyDebug.LOG) Log.d(TAG, "onPictureTaken")

                    // n.b., this is automatically run in a different thread
                    if (wantExpoBracketing && burstTotal > 1) {
                        pendingBurstImages.add(data)
                        if (pendingBurstImages.size >= burstTotal) { // shouldn't ever be greater, but just in case
                            if (MyDebug.LOG) Log.d(TAG, "all burst images available")
                            if (pendingBurstImages.size > burstTotal) {
                                Log.e(
                                    TAG,
                                    "pending_burst_images size " + pendingBurstImages.size + " is greater than n_burst " + burstTotal
                                )
                            }

                            // set exposure compensation back to original
                            burstExposures?.get(0)?.let { setExposureCompensation(it) }

                            // take a copy, so that we can clear pendingBurstImages
                            // also allows us to reorder from dark to light
                            // since we took the images with the base exposure being first
                            val nHalfImages = pendingBurstImages.size / 2
                            val images: MutableList<ByteArray?> = ArrayList()
                            // darker images
                            for (i in 0..<nHalfImages) {
                                images.add(pendingBurstImages[i + 1])
                            }
                            // base image
                            images.add(pendingBurstImages[0])
                            // lighter images
                            for (i in 0..<nHalfImages) {
                                images.add(pendingBurstImages[nHalfImages + 1])
                            }

                            picture.onBurstPictureTaken(images.filterNotNull())
                            pendingBurstImages.clear()
                            picture.onCompleted()
                        } else {
                            if (MyDebug.LOG) Log.d(
                                TAG,
                                "number of burst images is now: " + pendingBurstImages.size
                            )
                            // set exposure compensation for next image
                            burstExposures?.get(pendingBurstImages.size)
                                ?.let { setExposureCompensation(it) }

                            // need to start preview again: otherwise fail to take subsequent photos on Nexus 6
                            // and Nexus 7; on Galaxy Nexus we succeed, but exposure compensation has no effect
                            try {
                                startPreview()
                            } catch (e: CameraControllerException) {
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "CameraControllerException trying to startPreview"
                                )
                                e.printStackTrace()
                            }

                            val handler = Handler()
                            handler.postDelayed({
                                if (MyDebug.LOG) Log.d(
                                    TAG,
                                    "take picture after delay for next expo"
                                )
                                if (camera != null) { // make sure camera wasn't released in the meantime
                                    takePictureNow(picture, error)
                                }
                            }, 1000)
                        }
                    } else {
                        picture.onPictureTaken(data)
                        picture.onCompleted()
                    }
                }
            }

        if (picture != null) {
            if (MyDebug.LOG) Log.d(TAG, "call onStarted() in callback")
            picture.onStarted()
        }
        try {
            camera?.takePicture(shutter, null, cameraJpeg)
        } catch (e: RuntimeException) {
            // just in case? We got a RuntimeException report here from 1 user on Google Play; I also encountered it myself once of Galaxy Nexus when starting up
            if (MyDebug.LOG) Log.e(TAG, "runtime exception from takePicture")
            e.printStackTrace()
            error.onError()
        }
    }

    override fun takePicture(picture: PictureCallback, error: ErrorCallback) {
        if (MyDebug.LOG) Log.d(TAG, "takePicture")

        clearPending()
        if (wantExpoBracketing) {
            if (MyDebug.LOG) Log.d(TAG, "set up expo bracketing")
            val parameters = this.parameters
            val nHalfImages = expoBracketingNImages / 2
            val minExposure = parameters.minExposureCompensation
            val maxExposure = parameters.maxExposureCompensation
            var exposureStep = exposureCompensationStep
            if (exposureStep == 0.0f)  // just in case?
                exposureStep = 1.0f / 3.0f // make up a typical example

            val exposureCurrent = exposureCompensation
            val stopsPerImage = expoBracketingStops / nHalfImages.toDouble()
            var steps =
                ((stopsPerImage + 1.0e-5) / exposureStep).toInt() // need to add a small amount, otherwise we can round down
            steps = max(steps.toDouble(), 1.0).toInt()
            if (MyDebug.LOG) {
                Log.d(TAG, "steps: $steps")
                Log.d(
                    TAG,
                    "exposure_current: $exposureCurrent"
                )
            }

            val requests: MutableList<Int> = ArrayList()

            // do the current exposure first, so we can take the first shot immediately
            // if we change the order, remember to update the code that re-orders for passing resultant images back to picture.onBurstPictureTaken()
            requests.add(exposureCurrent)

            // darker images
            for (i in 0..<nHalfImages) {
                var exposure = exposureCurrent - (nHalfImages - i) * steps
                exposure = max(exposure.toDouble(), minExposure.toDouble()).toInt()
                requests.add(exposure)
                if (MyDebug.LOG) {
                    Log.d(TAG, "add burst request for " + i + "th dark image:")
                    Log.d(TAG, "exposure: $exposure")
                }
            }

            // lighter images
            for (i in 0..<nHalfImages) {
                var exposure = exposureCurrent + (i + 1) * steps
                exposure = min(exposure.toDouble(), maxExposure.toDouble()).toInt()
                requests.add(exposure)
                if (MyDebug.LOG) {
                    Log.d(TAG, "add burst request for " + i + "th light image:")
                    Log.d(TAG, "exposure: $exposure")
                }
            }

            burstExposures = requests
            burstTotal = requests.size
        }

        if (frontscreenFlash) {
            if (MyDebug.LOG) Log.d(TAG, "front screen flash")
            picture.onFrontScreenTurnOn()
            // take picture after a delay, to allow autoexposure and autofocus to update (unlike CameraController2, we can't tell when this happens, so we just wait for a fixed delay)
            val handler = Handler()
            handler.postDelayed({
                if (MyDebug.LOG) Log.d(TAG, "take picture after delay for front screen flash")
                if (camera != null) { // make sure camera wasn't released in the meantime
                    takePictureNow(picture, error)
                }
            }, 1000)
            return
        }
        takePictureNow(picture, error)
    }

    override var displayOrientation: Int = 0
        set(values) {
            // see http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
            var result: Int
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (cameraInfo.orientation + values) % 360
                result = (360 - result) % 360
            } else {
                result = (cameraInfo.orientation - values + 360) % 360
            }
            if (MyDebug.LOG) {
                Log.d(TAG, "    info orientation is " + cameraInfo.orientation)
                Log.d(
                    TAG,
                    "    setDisplayOrientation to $result"
                )
            }

            try {
                camera?.setDisplayOrientation(result)
            } catch (e: RuntimeException) {
                // unclear why this happens, but have had crashes from Google Play...
                Log.e(TAG, "failed to set display orientation")
                e.printStackTrace()
            }
            field = result
        }

    override val cameraOrientation: Int
        get() = cameraInfo.orientation

    override val facing: Facing
        get() {
            when (cameraInfo.facing) {
                Camera.CameraInfo.CAMERA_FACING_FRONT -> return Facing.FACING_FRONT
                Camera.CameraInfo.CAMERA_FACING_BACK -> return Facing.FACING_BACK
            }
            Log.e(TAG, "unknown camera_facing: " + cameraInfo.facing)
            return Facing.FACING_UNKNOWN
        }

    override fun unlock() {
        this.stopPreview() // although not documented, we need to stop preview to prevent device freeze or video errors shortly after video recording starts on some devices (e.g., device freeze on Samsung Galaxy S2 - I could reproduce this on Samsung RTL; also video recording fails and preview becomes corrupted on Galaxy S3 variant "SGH-I747-US2"); also see http://stackoverflow.com/questions/4244999/problem-with-video-recording-after-auto-focus-in-android
        camera?.unlock()
    }

    override fun initVideoRecorderPrePrepare(videoRecorder: MediaRecorder?) {
        videoRecorder?.setCamera(camera)
    }

    override fun initVideoRecorderPostPrepare(
        videoRecorder: MediaRecorder?,
        wantPhotoVideoRecording: Boolean
    ) {
        // no further actions necessary
    }

    override val parametersString: String
        get() {
            var string = ""
            try {
                string = parameters.flatten()
            } catch (e: Exception) {
                // received a StringIndexOutOfBoundsException from beneath getParameters().flatten() on Google Play!
                if (MyDebug.LOG) Log.e(TAG, "exception from getParameters().flatten()")
                e.printStackTrace()
            }
            return string
        }

    companion object {
        private const val TAG = "CameraController1"

        // seem to have problems with 5 images in some cases, e.g., images coming out same brightness on OnePlus 3T
        private const val maxExpoBracketingNImages = 3

        /** Opens the camera device.
         * @param cameraId Which camera to open (must be between 0 and CameraControllerManager1.getNumberOfCameras()-1).
         * @param cameraErrorCb onError() will be called if the camera closes due to serious error. No more calls to the CameraController1 object should be made (though a new one can be created, to try reopening the camera).
         * @throws CameraControllerException if the camera device fails to open.
         */
        @JvmStatic
        @Throws(CameraControllerException::class)
        fun createInstance(cameraId: Int, cameraErrorCb: ErrorCallback): CameraController1 {
            val instance = CameraController1(cameraId)
            if (MyDebug.LOG) Log.d(
                TAG,
                "create new CameraController1: $cameraId"
            )
            instance.cameraErrorCb = cameraErrorCb
            try {
                instance.camera = Camera.open(cameraId)
            } catch (e: RuntimeException) {
                if (MyDebug.LOG) Log.e(TAG, "failed to Open Kamera")
                e.printStackTrace()
                throw CameraControllerException()
            }
            if (instance.camera == null) {
                // Although the documentation says Camera.open() should throw a RuntimeException, it seems that it some cases it can return null
                // I've seen this in some crashes reported in Google Play; also see:
                // http://stackoverflow.com/questions/12054022/camera-open-returns-null
                if (MyDebug.LOG) Log.e(TAG, "camera.open returned null")
                throw CameraControllerException()
            }
            try {
                Camera.getCameraInfo(cameraId, instance.cameraInfo)
            } catch (e: RuntimeException) {
                // Had reported RuntimeExceptions from Google Play
                // also see http://stackoverflow.com/questions/22383708/java-lang-runtimeexception-fail-to-get-camera-info
                if (MyDebug.LOG) Log.e(TAG, "failed to get camera info")
                e.printStackTrace()
                instance.release()
                throw CameraControllerException()
            }
            instance.camera?.setErrorCallback(instance.CameraErrorCallback())

            return instance
        }

    }

}