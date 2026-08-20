package com.hightechif.openkamera.cameracontroller

import android.graphics.Rect
import android.location.Location
import android.media.MediaRecorder
import android.util.Log
import android.view.SurfaceHolder
import android.view.TextureView
import com.hightechif.openkamera.utils.MyDebug
import java.io.Serializable
import java.util.Collections
import kotlin.concurrent.Volatile
import kotlin.math.abs

/** CameraController is an abstract class that wraps up the access/control to
 * the Android camera, so that the rest of the application doesn't have to
 * deal directly with the Android camera API. It also allows us to support
 * more than one camera API through the same API (this is used to support both
 * the original camera API, and Android 5's Camera2 API).
 * The class is fairly low level wrapper about the APIs - there is some
 * additional logical/workarounds where such things are API-specific, but
 * otherwise the calling application still controls the behaviour of the
 * camera.
 */
abstract class CameraController internal constructor(val cameraId: Int) {
    // for testing:
    @kotlin.jvm.Volatile
    var countCameraParametersException: Int = 0

    @Volatile
    var countPrecaptureTimeout: Int = 0

    @Volatile
    var testWaitCaptureResult: Boolean =
        false // whether to test delayed capture result in Camera2 API

    @Volatile
    var testReleaseDuringPhoto: Boolean =
        false // for Camera2 API, will force takePictureAfterPrecapture() to call release() on UI thread

    @Volatile
    var testCaptureResults: Int =
        0 // for Camera2 API, how many capture requests completed with RequestTagType.CAPTURE

    @Volatile
    var testFakeFlashFocus: Int =
        0 // for Camera2 API, records torch turning on for fake flash during autofocus

    @Volatile
    var testFakeFlashPrecapture: Int =
        0 // for Camera2 API, records torch turning on for fake flash during precapture

    @Volatile
    var testFakeFlashPhoto: Int =
        0 // for Camera2 API, records torch turning on for fake flash for photo capture

    @Volatile
    var testAfStateNullFocus: Int =
        0 // for Camera2 API, records afState being null even when we've requested autofocus

    @Volatile
    var testUsedTonemapCurve: Boolean = false

    @Volatile
    var testTextureViewBufferW: Int = 0 // for TextureView, keep track of buffer size

    @Volatile
    var testTextureViewBufferH: Int = 0

    @Volatile
    var testForceRunPostCapture: Boolean =
        false // for Camera2 API, test using adjustPreview() / RequestTagType.RUN_POST_CAPTURE

    /** Class for caching a subset of CameraFeatures, that are slow to read.
     * For now only used for vendor extensions which are slow to read.
     */
    class CameraFeaturesCache internal constructor(
        cameraFeatures: CameraFeatures,
        extensionPictureSizesMap: Map<Int, List<android.util.Size>>,
        extensionPreviewSizesMap: Map<Int, List<android.util.Size>>
    ) {
        var supportedExtensions: List<Int>? = null
        var supportedExtensionsZoom: List<Int>? = null

        val extensionPictureSizesMap: Map<Int, List<android.util.Size>> // key is extension
        val extensionPreviewSizesMap: Map<Int, List<android.util.Size>> // key is extension

        init {
            if (cameraFeatures.supportedExtensions != null) this.supportedExtensions =
                cameraFeatures.supportedExtensions?.let { ArrayList(it) }
            if (cameraFeatures.supportedExtensionsZoom != null) this.supportedExtensionsZoom =
                cameraFeatures.supportedExtensionsZoom?.let { ArrayList(it) }
            this.extensionPictureSizesMap = extensionPictureSizesMap
            this.extensionPreviewSizesMap = extensionPreviewSizesMap
        }
    }

    class CameraFeatures {
        var physicalCameraIds: Set<String>? =
            null // if non-null, this camera is part of a logical camera that exposes these physical camera IDs
        var isZoomSupported: Boolean = false
        var maxZoom: Int = 0
        var zoomRatios: List<Int>? =
            null // list of supported zoom ratios; each value is the zoom multiplied by 100
        var supportsFaceDetection: Boolean = false
        var pictureSizes = mutableListOf<Size>()
        var videoSizes = mutableListOf<Size>()
        var videoSizesHighSpeed: MutableList<Size>? =
            null // may be null if high speed not supported
        var previewSizes = mutableListOf<Size>()

        // if non-null, list of supported camera vendor extensions, see https://developer.android.com/reference/android/hardware/camera2/CameraExtensionCharacteristics
        var supportedExtensions: MutableList<Int>? = null

        // if non-null, list of camera vendor extensions that support zoom
        var supportedExtensionsZoom: MutableList<Int>? = null
        var supportedFlashValues: MutableList<String>? = null
        var supportedFocusValues: MutableList<String>? = null
        var apertures: FloatArray? =
            null // may be null if not supported, else will have at least 2 values
        var maxNumFocusAreas: Int = 0
        var minimumFocusDistance: Float = 0f
        var isExposureLockSupported: Boolean = false
        var isWhiteBalanceLockSupported: Boolean = false
        var isOpticalStabilizationSupported: Boolean = false
        var isVideoStabilizationSupported: Boolean = false
        var isPhotoVideoRecordingSupported: Boolean = false
        var supportsWhiteBalanceTemperature: Boolean = false
        var minTemperature: Int = 0
        var maxTemperature: Int = 0
        var supportsIsoRange: Boolean = false
        var minIso: Int = 0
        var maxIso: Int = 0
        var supportsExposureTime: Boolean = false
        var minExposureTime: Long = 0
        var maxExposureTime: Long = 0
        var minExposure: Int = 0
        var maxExposure: Int = 0
        var exposureStep: Float = 0f
        var canDisableShutterSound: Boolean = false
        var tonemapMaxCurvePoints: Int = 0
        var supportsTonemapCurve: Boolean = false
        var supportsExpoBracketing: Boolean =
            false // whether setBurstTye(BURSTTYPE_EXPO) can be used
        var maxExpoBracketingNImages: Int = 0
        var supportsFocusBracketing: Boolean =
            false // whether setBurstTye(BURSTTYPE_FOCUS) can be used
        var supportsBurst: Boolean = false // whether setBurstTye(BURSTTYPE_NORMAL) can be used
        var supportsJpegR: Boolean = false // whether supports JPEG_R (UltraHDR)
        var supportsRaw: Boolean = false
        var viewAngleX: Float = 0f // horizontal angle of view in degrees (when unzoomed)
        var viewAngleY: Float = 0f // vertical angle of view in degrees (when unzoomed)

        companion object {
            /** Returns whether any of the supplied sizes support the requested fps.
             */
            fun supportsFrameRate(
                sizes: List<Size?>?,
                fps: Int
            ): Boolean {
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "supportsFrameRate: $fps"
                )
                if (sizes == null) return false
                for (size in sizes) {
                    if (size?.supportsFrameRate(fps.toDouble()) == true) {
                        if (MyDebug.LOG) Log.d(TAG, "fps is supported")
                        return true
                    }
                }
                if (MyDebug.LOG) Log.d(TAG, "fps is NOT supported")
                return false
            }

            /**
             * @param returnClosest If true, return a match for the width/height, even if the fps doesn't
             * match.
             */
            fun findSize(
                sizes: List<Size>,
                size: Size,
                fps: Double,
                returnClosest: Boolean
            ): Size? {
                var lastS: Size? = null
                for (s in sizes) {
                    if (size == s) {
                        lastS = s
                        if (fps > 0) {
                            if (s.supportsFrameRate(fps)) {
                                return s
                            }
                        } else {
                            return s
                        }
                    }
                }
                return if (returnClosest) lastS else null
            }
        }
    }

    // Android docs and FindBugs recommend that Comparators also be Serializable
    internal class RangeSorter : Comparator<IntArray>, Serializable {
        override fun compare(o1: IntArray, o2: IntArray): Int {
            if (o1[0] == o2[0]) return o1[1] - o2[1]
            return o1[0] - o2[0]
        }

        companion object {
            private const val serialVersionUID = 5802214721073728212L
        }
    }

    /* Sorts resolutions from highest to lowest, by area.
     * Android docs and FindBugs recommend that Comparators also be Serializable
     */
    internal class SizeSorter : Comparator<Size>, Serializable {
        override fun compare(a: Size, b: Size): Int {
            return b.width * b.height - a.width * a.height
        }

        companion object {
            private const val serialVersionUID = 5802214721073718212L
        }
    }

    class Size internal constructor(
        val width: Int, val height: Int, // for video
        val fpsRanges: List<IntArray>, // for video
        val highSpeed: Boolean
    ) {
        var supportsBurst: Boolean = true // for photo

        // for photo and preview: if non-null, list of supported camera vendor extensions
        var supportedExtensions: MutableList<Int>? = null

        init {
            Collections.sort(this.fpsRanges, RangeSorter())
        }

        constructor(width: Int, height: Int) : this(width, height, ArrayList<IntArray>(), false)

        /** Whether this size supports the requested burst and/or extension
         */
        fun supportsRequirements(
            wantBurst: Boolean,
            wantExtension: Boolean,
            extension: Int
        ): Boolean {
            return (!wantBurst || this.supportsBurst) && (!wantExtension || this.supportsExtension(
                extension
            ))
        }

        fun supportsExtension(extension: Int): Boolean {
            return supportedExtensions != null && (supportedExtensions?.contains(extension) == true)
        }

        fun supportsFrameRate(fps: Double): Boolean {
            for (f in this.fpsRanges) {
                if (f[0] <= fps && fps <= f[1]) return true
            }
            return false
        }

        fun closestFrameRate(fps: Double): Int {
            var closestFps = -1
            var closestDist = -1
            for (f in this.fpsRanges) {
                if (f[0] <= fps && fps <= f[1]) return fps.toInt()
                val thisFps = if (fps < f[0]) f[0]
                else f[1]
                val dist = abs((thisFps - fps.toInt()).toDouble()).toInt()
                if (closestDist == -1 || dist < closestDist) {
                    closestFps = thisFps
                    closestDist = dist
                }
            }
            return closestFps
        }

        override fun equals(other: Any?): Boolean {
            if (other !is Size) return false
            val that: Size = other
            return this.width == that.width && this.height == that.height
        }

        override fun hashCode(): Int {
            // must override this, as we override equals()
            // can't use:
            //return Objects.hash(width, height);
            // as this requires API level 19
            // so use this from http://stackoverflow.com/questions/11742593/what-is-the-hashcode-for-a-custom-class-having-just-two-int-properties
            return width * 41 + height
        }

        override fun toString(): String {
            val s = StringBuilder()
            for (f in this.fpsRanges) {
                s.append(" [").append(f[0]).append("-").append(f[1]).append("]")
            }
            return width.toString() + "x" + this.height + " " + s + (if (this.highSpeed) "-hs" else "")
        }
    }

    /** An area has values from [-1000,-1000] (for top-left) to [1000,1000] (for bottom-right) for whatever is
     * the current field of view (i.e., taking zoom into account).
     */
    data class Area(val rect: Rect, val weight: Int)

    interface FaceDetectionListener {
        fun onFaceDetection(faces: Array<Face?>)
    }

    /** Interface to define callbacks related to taking photos. These callbacks are all called on the UI thread.
     */
    interface PictureCallback {
        fun onStarted() // called immediately before we start capturing the picture

        fun onCompleted() // called after all relevant on*PictureTaken() callbacks have been called and returned

        fun onPictureTaken(data: ByteArray)

        /** Only called if RAW is requested.
         * Caller should call raw_image.close() when done with the image.
         */
        fun onRawPictureTaken(rawImage: RawImage?)

        /** Only called if burst is requested.
         */
        fun onBurstPictureTaken(images: List<ByteArray>)

        /** Only called if burst is requested.
         */
        fun onRawBurstPictureTaken(rawImages: List<RawImage>)

        /** Reports percentage progress for vendor camera extensions. Note that not all devices support this being called.
         */
        fun onExtensionProgress(progress: Int)

        /* This is called for when burst mode is BURSTTYPE_FOCUS or BURSTTYPE_CONTINUOUS, to ask whether it's safe to take
         * nRaw extra RAW images and nJpegs extra JPEG images, or whether to wait.
         */
        fun imageQueueWouldBlock(nRaw: Int, nJpegs: Int): Boolean

        /* This is called for flashFrontscreenAuto or flashFrontscreenOn mode to indicate the caller should light up the screen
         * (for flashFrontscreenAuto it will only be called if the scene is considered dark enough to require the screen flash).
         * The screen flash can be removed when or after onCompleted() is called.
         */
        fun onFrontScreenTurnOn()
    }

    /** Interface to define callback for autofocus completing. This callback may be called on the UI thread (CameraController1)
     * or a background thread (CameraController2).
     */
    interface AutoFocusCallback {
        fun onAutoFocus(success: Boolean)
    }

    /** Interface to define callback for continuous focus starting/stopping. This callback may be called on the
     * UI thread (CameraController1) or a background thread (CameraController2).
     */
    interface ContinuousFocusMoveCallback {
        fun onContinuousFocusMove(start: Boolean)
    }

    interface ErrorCallback {
        fun onError()
    }

    data class Face internal constructor(
        val score: Int, /* The rect has values from [-1000,-1000] (for top-left) to [1000,1000] (for bottom-right) for whatever is
         * the current field of view (i.e., taking zoom into account).
         */val rect: Rect
    ) {
        /** The temp rect is temporary storage that can be used by callers.
         */
        val temp: Rect = Rect()
    }

    data class SupportedValues internal constructor(val values: List<String>, val selectedValue: String)

    abstract fun release()
    abstract fun onError() // triggers error mechanism - should only be called externally for testing purposes

    abstract val api: String

    @get:Throws(CameraControllerException::class)
    abstract val cameraFeatures: CameraFeatures

    /** For CameraController2 only. Applications should cover the preview textureview if since last resuming, cameraController
     * has never been non-null or this method has never returned false.
     * Otherwise there is a risk when opening the camera that the textureview still shows an image from when
     * the camera was previously opened (e.g., from pausing and resuming the application). This returns false (for CameraController2)
     * when the camera has received its first frame.
     * Update: on more recent Android versions this didn't work very well, possibly due to a screenshot being used for "recent apps"
     * view; on Android 13+, the activity can make use of shouldCoverPreview(false) for this.
     */
    open fun shouldCoverPreview(): Boolean {
        return false
    }

    /** For CameraController2 only. After calling this, shouldCoverPreview() will return true, until a new
     * frame from the camera has been received.
     */
    open fun resetCoverPreview() {}

    abstract fun setSceneMode(value: String): SupportedValues?

    /**
     * @return The current scene mode. Will be null if scene mode not supported.
     */
    abstract val sceneMode: String?

    /**
     * @return Returns true iff changing the scene mode can affect the available camera functionality
     * (e.g., changing to Night scene mode might mean flash modes are no longer available).
     */
    abstract fun sceneModeAffectsFunctionality(): Boolean
    abstract fun setColorEffect(value: String): SupportedValues?
    abstract val colorEffect: String?
    abstract fun setWhiteBalance(value: String): SupportedValues?
    abstract val whiteBalance: String?
    abstract fun setWhiteBalanceTemperature(temperature: Int): Boolean
    abstract val whiteBalanceTemperature: Int
    abstract fun setAntiBanding(value: String): SupportedValues?
    abstract val antiBanding: String?
    abstract fun setEdgeMode(value: String): SupportedValues?
    abstract val edgeMode: String?
    abstract fun setNoiseReductionMode(value: String): SupportedValues?
    abstract val noiseReductionMode: String?

    /** Set an ISO value. Only supported if supportsIsoRange is false.
     */
    abstract fun setISO(value: String?): SupportedValues?

    /** Switch between auto and manual ISO mode. Only supported if supportsIsoRange is true.
     * @param manualIso Whether to switch to manual mode or back to auto.
     * @param iso If manualIso is true, this specifies the desired ISO value. If this is outside
     * the minIso/maxIso, the value will be snapped so it does lie within that range.
     * If manualIso i false, this value is ignored.
     */
    abstract fun setManualISO(manualIso: Boolean, iso: Int)

    /**
     * @return Whether in manual ISO mode (as opposed to auto).
     */
    abstract val isManualISO: Boolean

    /** Specify a specific ISO value. Only supported if supportsIsoRange is true. Callers should
     * first switch to manual ISO mode using setManualISO().
     */
    abstract fun setISO(iso: Int): Boolean
    abstract val isoKey: String

    /** Returns the manual ISO value. Only supported if supportsIsoRange is true.
     */
    abstract val iSO: Int
    abstract val exposureTime: Long
    abstract fun setExposureTime(exposureTime: Long): Boolean
    abstract fun setAperture(aperture: Float)
    abstract val pictureSize: Size
    abstract fun setPictureSize(width: Int, height: Int)
    abstract val previewSize: Size
    abstract fun setPreviewSize(width: Int, height: Int)

    abstract fun setCameraExtension(enabled: Boolean, extension: Int)
    abstract val isCameraExtension: Boolean
    abstract fun getCameraExtension(): Int

    // whether to take a burst of images, and if so, what type
    enum class BurstType {
        BURSTTYPE_NONE,  // no burst
        BURSTTYPE_EXPO,  // enable expo bracketing mode
        BURSTTYPE_FOCUS,  // enable focus bracketing mode;
        BURSTTYPE_NORMAL,  // take a regular burst
        BURSTTYPE_CONTINUOUS // as BURSTTYPE_NORMAL, but bursts will fire continually until stopContinuousBurst() is called.
    }

    abstract var burstType: BurstType

    /** Only relevant if setBurstType() is also called with BURSTTYPE_NORMAL. Sets the number of
     * images to take in the burst.
     */
    abstract fun setBurstNImages(burstRequestedNImages: Int)

    /** Only relevant if setBurstType() is also called with BURSTTYPE_NORMAL. If this method is
     * called with burstForNoiseReduction, then the number of burst images, and other settings,
     * will be set for noise reduction mode (and setBurstNImages() is ignored).
     */
    abstract fun setBurstForNoiseReduction(
        burstForNoiseReduction: Boolean,
        noiseReductionLowLight: Boolean
    )

    abstract val isContinuousBurstInProgress: Boolean
    abstract fun stopContinuousBurst()
    abstract fun stopFocusBracketingBurst()

    /** Only relevant if setBurstType() is also called with BURSTTYPE_EXPO. Sets the number of
     * images to take in the expo burst.
     * @param nImages Must be an odd number greater than 1.
     */
    abstract fun setExpoBracketingNImages(nImages: Int)

    /** Only relevant if setBurstType() is also called with BURSTTYPE_EXPO.
     */
    abstract fun setExpoBracketingStops(stops: Double)
    abstract fun setUseExpoFastBurst(useExpoFastBurst: Boolean)

    /** Whether to enable a workaround hack for some Galaxy devices - take an additional dummy photo
     * when taking an expo/HDR burst, to avoid problem where manual exposure is ignored for the
     * first image.
     */
    abstract fun setDummyCaptureHack(dummyCaptureHack: Boolean)

    /** Whether the current BurstType is one that requires the camera driver to capture the images
     * as a burst at a fast rate. If true, we should not use high resolutions that don't support a
     * capture burst (for Camera2 API, see StreamConfigurationMap.getHighResolutionOutputSizes()).
     */
    abstract val isCaptureFastBurst: Boolean

    /** If true, then the camera controller is currently capturing a burst of images.
     */
    abstract val isCapturingBurst: Boolean

    /** If isCapturingBurst() is true, then this returns the number of images in the current burst
     * captured so far.
     */
    abstract val nBurstTaken: Int

    /** If isCapturingBurst() is true, then this returns the total number of images in the current
     * burst if known. If not known (e.g., for continuous burst mode), returns 0.
     */
    abstract val burstTotal: Int

    /**
     * @param wantJpegR Whether to enable taking photos in JPEG_R (UltraHDR) format.
     */
    abstract fun setJpegR(wantJpegR: Boolean)

    /**
     * @param wantRaw       Whether to enable taking photos in RAW (DNG) format.
     * @param maxRawImages The maximum number of unclosed DNG images that may be held in memory at any one
     * time. Trying to take a photo, when the number of unclosed DNG images is already
     * equal to this number, will result in an exception (java.lang.IllegalStateException
     * - note, the exception will come from a CameraController2 callback, so can't be
     * caught by the callera).
     */
    abstract fun setRaw(wantRaw: Boolean, maxRawImages: Int)

    /** Request a capture session compatible with high speed frame rates.
     * This should be called only when the preview is paused or not yet started.
     */
    abstract fun setVideoHighSpeed(setVideoHighSpeed: Boolean)

    /**
     * setUseCamera2FakeFlash() should be called after creating the CameraController, and before calling getCameraFeatures() or
     * starting the preview (as it changes the available flash modes).
     * "Fake flash" is an alternative mode for handling flash, for devices that have poor Camera2 support - typical symptoms
     * include precapture never starting, flash not firing, photos being over or under exposed.
     * Instead, we fake the precapture and flash simply by turning on the torch. After turning on torch, we wait for ae to stop
     * scanning (and af too, as it can start scanning in continuous mode) - this is effectively the equivalent of precapture -
     * before taking the photo.
     * In auto-focus mode, we make the decision ourselves based on the current ISO.
     * We also handle the flash firing for autofocus by turning the torch on and off too. Advantages are:
     * - The flash tends to be brighter, and the photo can end up overexposed as a result if capture follows the autofocus.
     * - Some devices also don't seem to fire flash for autofocus in Camera2 mode (e.g., Samsung S7)
     * - When capture follows autofocus, we need to make the same decision for firing flash for both the autofocus and the capture.
     */
    open var useCamera2FakeFlash: Boolean = false

    abstract val opticalStabilization: Boolean

    /** Whether to enable digital video stabilization. Should only be set to true when intending to
     * capture video.
     */
    abstract var videoStabilization: Boolean

    enum class TonemapProfile {
        TONEMAPPROFILE_OFF,
        TONEMAPPROFILE_REC709,
        TONEMAPPROFILE_SRGB,
        TONEMAPPROFILE_LOG,
        TONEMAPPROFILE_GAMMA,
        TONEMAPPROFILE_JTVIDEO,
        TONEMAPPROFILE_JTLOG,
        TONEMAPPROFILE_JTLOG2
    }

    /** Sets a tonemap profile.
     * @param tonemapProfile The type of the tonemap profile.
     * @param logProfileStrength Only relevant if tonemapProfile set to TONEMAPPROFILE_LOG.
     * @param gamma Only relevant if tonemapProfile set to TONEMAPPROFILE_GAMMA
     */
    abstract fun setTonemapProfile(
        tonemapProfile: TonemapProfile,
        logProfileStrength: Float,
        gamma: Float
    )

    abstract val tonemapProfile: TonemapProfile
    abstract var jpegQuality: Int
    /** Returns the current zoom. The returned value is an index into the CameraFeatures.zoomRatios
     * array.
     */
    /** Set the zoom.
     * @param value The index into the CameraFeatures.zoomRatios array.
     */
    abstract var zoom: Int

    /** Set the zoom. Unlike setZoom(value), this allows specifying any zoom level within the
     * supported range.
     * @param value The index into the CameraFeatures.zoomRatios array.
     * @param smoothZoom The desired zoom. With CameraController1 (old Camera API), this is ignored.
     * With CameraController2 (Camera2 API), this is used instead of the zoomRatios
     * value. Note that getZoom() will return the value passed to this method, so
     * passing an appropriate value (e.g., whatever zoomRatio is closest to the
     * smoothZoom) is still useful if you want to make use of getZoom().
     * smoothZoom must still be within the supported range of zoom values.
     */
    abstract fun setZoom(value: Int, smoothZoom: Float)
    abstract fun setZoomSticky(sticky: Boolean): List<Int>?
    abstract fun resetZoom() // resets to zoom 1x
    abstract val exposureCompensation: Int
    abstract fun setExposureCompensation(newExposure: Int): Boolean
    abstract fun setPreviewFpsRange(min: Int, max: Int)
    abstract fun clearPreviewFpsRange()

    // result depends on setting of setVideoHighSpeed()
    abstract val supportedPreviewFpsRange: List<IntArray>?

    abstract var focusValue: String?
    abstract val focusDistance: Float
    abstract fun setFocusDistance(focusDistance: Float): Boolean

    /** Only relevant if setBurstType() is also called with BURSTTYPE_FOCUS. Sets the number of
     * images to take in the focus burst.
     */
    abstract fun setFocusBracketingNImages(nImages: Int)

    /** Only relevant if setBurstType() is also called with BURSTTYPE_FOCUS. If set to true, an
     * additional image will be included at infinite distance.
     */
    abstract fun setFocusBracketingAddInfinity(focusBracketingAddInfinity: Boolean)

    /** Only relevant if setBurstType() is also called with BURSTTYPE_FOCUS. Sets the source focus
     * distance for focus bracketing.
     */
    abstract var focusBracketingSourceDistance: Float

    /** Only relevant if setBurstType() is also called with BURSTTYPE_FOCUS. Sets the source focus
     * distance to match the camera's current focus distance (typically useful if running in a
     * non-manual focus mode).
     */
    abstract fun setFocusBracketingSourceDistanceFromCurrent()

    /** Only relevant if setBurstType() is also called with BURSTTYPE_FOCUS. Sets the target focus
     * distance for focus bracketing.
     */
    abstract var focusBracketingTargetDistance: Float
    abstract var flashValue: String
    abstract fun setRecordingHint(hint: Boolean)
    abstract var autoExposureLock: Boolean
    abstract var autoWhiteBalanceLock: Boolean
    abstract fun setRotation(rotation: Int)
    abstract fun setLocationInfo(location: Location)
    abstract fun removeLocationInfo()
    abstract fun enableShutterSound(enabled: Boolean)
    abstract fun setFocusAndMeteringArea(areas: List<Area>): Boolean
    abstract fun clearFocusAndMetering()
    abstract val focusAreas: List<Any?>?
    abstract val meteringAreas: List<Any?>?
    abstract fun supportsAutoFocus(): Boolean
    abstract fun supportsMetering(): Boolean
    abstract fun focusIsContinuous(): Boolean
    abstract fun focusIsVideo(): Boolean

    @Throws(CameraControllerException::class)
    abstract fun reconnect()

    @Throws(CameraControllerException::class)
    abstract fun setPreviewDisplay(holder: SurfaceHolder?)

    @Throws(CameraControllerException::class)
    abstract fun setPreviewTexture(texture: TextureView)

    /** This should be called when using a TextureView, and the texture view has reported a change
     * in size via onSurfaceTextureSizeChanged.
     */
    open fun updatePreviewTexture() {
        // dummy implementation
    }

    /** Starts the camera preview.
     * @throws CameraControllerException if the camera preview fails to start.
     */
    @Throws(CameraControllerException::class)
    abstract fun startPreview()

    /** Only relevant for CameraController2: stops the repeating burst for the previous (so effectively
     * stops the preview), but does not close the capture session for the preview (for that, using
     * stopPreview() instead of stopRepeating()).
     */
    abstract fun stopRepeating()
    abstract fun stopPreview()
    abstract fun startFaceDetection(): Boolean
    abstract fun setFaceDetectionListener(listener: FaceDetectionListener?)

    /**
     * @param cb Callback to be called when autofocus completes.
     * @param captureFollowsAutofocusHint Set to true if you intend to take a photo immediately after autofocus. If the
     * decision changes after autofocus has started (e.g., user initiates autofocus,
     * then takes photo before autofocus has completed), use setCaptureFollowAutofocusHint().
     */
    abstract fun autoFocus(cb: AutoFocusCallback, captureFollowsAutofocusHint: Boolean)

    /** See autoFocus() for details - used to update the captureFollowsAutofocusHint setting.
     */
    abstract fun setCaptureFollowAutofocusHint(captureFollowsAutofocusHint: Boolean)
    abstract fun cancelAutoFocus()
    abstract fun setContinuousFocusMoveCallback(cb: ContinuousFocusMoveCallback?)
    abstract fun takePicture(picture: PictureCallback, error: ErrorCallback)

    abstract var displayOrientation: Int
    abstract val cameraOrientation: Int

    enum class Facing {
        FACING_BACK,
        FACING_FRONT,
        FACING_EXTERNAL,
        FACING_UNKNOWN // returned if the Camera API returned an error or an unknown type
    }

    /** Returns whether the camera is front, back or external.
     */
    abstract val facing: Facing?
    abstract fun unlock()

    /** Call to initialise video recording, should call before MediaRecorder.prepare().
     * @param videoRecorder The media recorder object.
     */
    abstract fun initVideoRecorderPrePrepare(videoRecorder: MediaRecorder?)

    /** Call to initialise video recording, should call after MediaRecorder.prepare(), but before MediaRecorder.start().
     * @param videoRecorder The media recorder object.
     * @param wantPhotoVideoRecording Whether support for taking photos whilst video recording is required. If this feature isn't supported, the option has no effect.
     */
    @Throws(CameraControllerException::class)
    abstract fun initVideoRecorderPostPrepare(
        videoRecorder: MediaRecorder?,
        wantPhotoVideoRecording: Boolean
    )

    abstract val parametersString: String?
    open fun captureResultIsAEScanning(): Boolean {
        return false
    }

    /**
     * @return whether flash will fire; returns false if not known
     */
    open fun needsFlash(): Boolean {
        return false
    }

    /**
     * @return whether front screen "flash" will fire; returns false if not known
     */
    open fun needsFrontScreenFlash(): Boolean {
        return false
    }

    open fun captureResultHasWhiteBalanceTemperature(): Boolean {
        return false
    }

    open fun captureResultWhiteBalanceTemperature(): Int {
        return 0
    }

    open fun captureResultHasIso(): Boolean {
        return false
    }

    open fun captureResultIso(): Int {
        return 0
    }

    open fun captureResultHasExposureTime(): Boolean {
        return false
    }

    open fun captureResultExposureTime(): Long {
        return 0
    }

    open fun captureResultHasFrameDuration(): Boolean {
        return false
    }

    open fun captureResultFrameDuration(): Long {
        return 0
    }

    open fun captureResultHasFocusDistance(): Boolean {
        return false
    }

    open fun captureResultFocusDistance(): Float {
        return 0.0f
    }

    open fun captureResultHasAperture(): Boolean {
        return false
    }

    open fun captureResultAperture(): Float {
        return 0.0f
    }

    /*public boolean captureResultHasFocusDistance() {
		return false;
	}*/
    /*public float captureResultFocusDistanceMin() {
		return 0.0f;
	}*/
    /*public float captureResultFocusDistanceMax() {
		return 0.0f;
	}*/
    // gets the available values of a generic mode, e.g., scene, color etc, and makes sure the requested mode is available
    fun checkModeIsSupported(
        values: MutableList<String>?,
        value: String,
        defaultValue: String
    ): SupportedValues? {
        var newValue = value
        if (values != null && values.size > 1) { // n.b., if there is only 1 supported value, we also return null, as no point offering the choice to the user (there are some devices, e.g., Samsung, that only have a scene mode of "auto")
            if (MyDebug.LOG) {
                for (i in values.indices) {
                    Log.d(TAG, "supported value: " + values[i])
                }
            }
            // make sure result is valid
            if (!values.contains(newValue)) {
                if (MyDebug.LOG) Log.d(TAG, "value not valid!")
                newValue = if (values.contains(defaultValue)) defaultValue
                else values[0]
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "value is now: $newValue"
                )
            }
            return SupportedValues(values, newValue)
        }
        return null
    }

    companion object {
        const val TAG = "CameraController"
        const val SCENE_MODE_DEFAULT: String =
            "auto" // chosen to match Camera.Parameters.SCENE_MODE_AUTO, but we also use compatible values for Camera2 API
        const val COLOR_EFFECT_DEFAULT: String =
            "none" // chosen to match Camera.Parameters.EFFECT_NONE, but we also use compatible values for Camera2 API
        const val WHITE_BALANCE_DEFAULT: String =
            "auto" // chosen to match Camera.Parameters.WHITE_BALANCE_AUTO, but we also use compatible values for Camera2 API
        const val ANTIBANDING_DEFAULT: String =
            "auto" // chosen to match Camera.Parameters.ANTIBANDING_AUTO, but we also use compatible values for Camera2 API
        const val EDGE_MODE_DEFAULT: String = "default"
        const val NOISE_REDUCTION_MODE_DEFAULT: String = "default"
        const val ISO_DEFAULT: String = "auto"
        const val EXPOSURE_TIME_DEFAULT: Long =
            1000000000L / 30 // note, responsibility of callers to check that this is within the valid min/max range

        const val N_IMAGES_NR_DARK: Int = 8
        const val N_IMAGES_NR_DARK_LOW_LIGHT: Int = 15
    }
}