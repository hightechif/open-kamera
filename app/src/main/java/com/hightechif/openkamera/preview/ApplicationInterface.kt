/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.location.Location
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Pair
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.cameracontroller.RawImage
import com.hightechif.openkamera.utils.MyDebug
import java.io.File
import java.io.IOException
import java.util.Date

/** Provides communication between the Preview and the rest of the application
 * - so in theory one can drop the Preview/ (and CameraController/) classes
 * into a new application, by providing an appropriate implementation of this
 * ApplicationInterface.
 */
interface ApplicationInterface {
    class NoFreeStorageException : Exception() {
        private fun readResolve(): Any = NoFreeStorageException()
        private val serialVersionUID = -2021932609486148748L
    }

    data class VideoMaxFileSize(
        // maximum file size in bytes for video (return 0 for device default - typically this is ~2GB)
        var maxFilesize: Long = 0,
        // whether to automatically restart on hitting max filesize (this setting is still relevant for maxFilesize==0, as typically there will still be a device max filesize)
        var autoRestart: Boolean = false
    )

    enum class VideoMethod {
        FILE,  // video will be saved to a file
        SAF,  // video will be saved using Android 5's Storage Access Framework
        MEDIASTORE,  // video will be saved to the supplied MediaStore Uri
        URI; // video will be written to the supplied Uri
    }

    // methods that request information
    // get the application context
    val context: Context
        // methods that request information
        get // get the application context

    fun useCamera2(): Boolean // should Android 5's Camera 2 API be used?
    fun getLocation(): Location? // get current location - null if not available (or you don't care about geotagging)
    fun createOutputVideoMethod(): VideoMethod // return a VideoMethod value to specify how to create a video file

    @Throws(IOException::class)
    fun createOutputVideoFile(extension: String): File // will be called if createOutputVideoUsingSAF() returns VideoMethod.FILE; extension is the recommended filename extension for the chosen video type

    @Throws(IOException::class)
    fun createOutputVideoSAF(extension: String): Uri // will be called if createOutputVideoUsingSAF() returns VideoMethod.SAF; extension is the recommended filename extension for the chosen video type

    @Throws(IOException::class)
    fun createOutputVideoMediaStore(extension: String?): Uri // will be called if createOutputVideoUsingSAF() returns VideoMethod.MEDIASTORE; extension is the recommended filename extension for the chosen video type
    fun createOutputVideoUri(): Uri // will be called if createOutputVideoUsingSAF() returns VideoMethod.URI

    // for all of the get*Pref() methods, you can use Preview methods to get the supported values (e.g., getSupportedSceneModes())
    // if you just want a default or don't really care, see the comments for each method for a default or possible options
    // if Preview doesn't support the requested setting, it will check this, and choose its own
    fun getCameraIdPref(): Int // camera to use, from 0 to getCameraControllerManager().getNumberOfCameras()
    fun getCameraIdSPhysicalPref(): String? // if non-null, the Camera2 physical camera ID (must be one of Preview.getPhysicalCameras())
    fun getFlashPref(): String // flashOff, flashAuto, flashOn, flashTorch, flashRedEye
    fun getFocusPref(isVideo: Boolean): String // focusModeAuto, focusModeInfinity, focusModeMacro, focusModeLocked, focusModeFixed, focusModeManual2, focusModeEdof, focusModeContinuousPicture, focusModeContinuousVideo
    fun isVideoPref(): Boolean // start up in video mode?
    fun getSceneModePref(): String // "auto" for default (strings correspond to Android's scene mode constants in android.hardware.Camera.Parameters)
    fun getColorEffectPref(): String // "node" for default (strings correspond to Android's color effect constants in android.hardware.Camera.Parameters)
    fun getWhiteBalancePref(): String // "auto" for default (strings correspond to Android's white balance constants in android.hardware.Camera.Parameters)
    fun getWhiteBalanceTemperaturePref(): Int
    fun getAntiBandingPref(): String // "auto" for default (strings correspond to Android's antibanding constants in android.hardware.Camera.Parameters)
    fun getEdgeModePref(): String // CameraController.EDGE_MODE_DEFAULT for device default, or "off", "fast", "high_quality"
    fun getCameraNoiseReductionModePref(): String // CameraController.NOISE_REDUCTION_MODE_DEFAULT for device default, or "off", "minimal", "fast", "high_quality"
    fun getISOPref(): String // "auto" for auto-ISO, otherwise a numerical value; see documentation for Preview.supportsISORange().
    fun getExposureCompensationPref(): Int // 0 for default

    data class CameraResolutionConstraints(
        var hasMaxMp: Boolean = false,
        var maxMp: Int = 0
    ) {

        fun hasConstraints(): Boolean {
            return hasMaxMp
        }

        fun satisfies(size: CameraController.Size): Boolean {
            if (this.hasMaxMp && size.width * size.height > this.maxMp) {
                if (MyDebug.LOG) Log.d(TAG, "size index larger than max_mp: " + this.maxMp)
                return false
            }
            return true
        }

        companion object {
            private const val TAG = "CameraResConstraints"
        }
    }

    /** The resolution to use for photo mode.
     * If the returned resolution is not supported by the device, or this method returns null, then
     * the preview will choose a size, and then call setCameraResolutionPref() with the chosen
     * size.
     * If the returned resolution is supported by the device, setCameraResolutionPref() will be
     * called with the returned resolution.
     * Note that even if the device supports the resolution in general, the Preview may choose a
     * different resolution in some circumstances:
     * * A burst mode as been requested, but the resolution does not support burst.
     * * A constraint has been set via constraints.
     * In such cases, the resolution actually in use should be found by calling
     * Preview.getCurrentPictureSize() rather than relying on the setCameraResolutionPref(). (The
     * logic behind this is that if a resolution is not supported by the device at all, it's good
     * practice to correct the preference stored in user settings; but this shouldn't be done if
     * the resolution is changed for something more temporary such as enabling burst mode.)
     * @param constraints Optional constraints that may be set. If the returned resolution does not
     * satisfy these constraints, then the preview will choose the closest
     * resolution that does.
     */
    fun getCameraResolutionPref(constraints: CameraResolutionConstraints): Pair<Int, Int>? // return null to let Preview choose size
    fun getImageQualityPref(): Int // jpeg quality for taking photos; "90" is a recommended default
    fun getFaceDetectionPref(): Boolean // whether to use face detection mode
    fun getVideoQualityPref(): String // should be one of Preview.getSupportedVideoQuality() (use Preview.getCamcorderProfile() or Preview.getCamcorderProfileDescription() for details); or return "" to let Preview choose quality
    fun getVideoStabilizationPref(): Boolean // whether to use video stabilization for video
    fun getForce4KPref(): Boolean // whether to force 4K mode - experimental, only really available for some devices that allow 4K recording but don't return it as an available resolution - not recommended for most uses
    fun getRecordVideoOutputFormatPref(): String // preferenceVideoOutputFormatDefault, preferenceVideoOutputFormatMpeg4H264, preferenceVideoOutputFormatMpeg4Hevc, preferenceVideoOutputFormat3gpp, preferenceVideoOutputFormatWebm
    fun getVideoBitratePref(): String // return "default" to let Preview choose
    fun getVideoFPSPref(): String // return "default" to let Preview choose; if getVideoCaptureRateFactor() returns a value other than 1.0, this is the capture fps; the resultant video's fps will be getVideoFPSPref()*getVideoCaptureRateFactor()
    fun getVideoCaptureRateFactor(): Float // return 1.0f for standard operation, less than 1.0 for slow motion, more than 1.0 for timelapse; consider using a higher fps for slow motion, see getVideoFPSPref()
    fun getVideoTonemapProfile(): CameraController.TonemapProfile // tonemap profile to use for video mode
    fun getVideoLogProfileStrength(): Float // strength of the log profile for video mode, if getVideoTonemapProfile() returns TONEMAPPROFILE_LOG
    fun getVideoProfileGamma(): Float // gamma for video mode, if getVideoTonemapProfile() returns TONEMAPPROFILE_GAMMA
    fun getVideoMaxDurationPref(): Long // time in ms after which to automatically stop video recording (return 0 for off)
    fun getVideoRestartTimesPref(): Int // number of times to restart video recording after hitting max duration (return 0 for never auto-restarting)

    @Throws(NoFreeStorageException::class)
    fun getVideoMaxFileSizePref(): VideoMaxFileSize // see VideoMaxFileSize class for details
    fun getVideoFlashPref(): Boolean // option to switch flash on/off while recording video (should be false in most cases!)
    fun getVideoLowPowerCheckPref(): Boolean // whether to stop video automatically on critically low battery
    fun getPreviewSizePref(): String // "preference_preview_size_wysiwyg" is recommended (preview matches aspect ratio of photo resolution as close as possible), but can also be "preference_preview_size_display" to maximize the preview size
    fun getLockOrientationPref(): String // return "none" for default; use "portrait" or "landscape" to lock photos/videos to that orientation
    fun getTouchCapturePref(): Boolean // whether to enable touch to capture
    fun getDoubleTapCapturePref(): Boolean // whether to enable double-tap to capture
    fun getPausePreviewPref(): Boolean // whether to pause the preview after taking a photo
    fun getShowToastsPref(): Boolean
    fun getShutterSoundPref(): Boolean // whether to play sound when taking photo
    fun getStartupFocusPref(): Boolean // whether to do autofocus on startup
    fun getTimerPref(): Long // time in ms for timer (so 0 for off)
    fun getRepeatPref(): String // return number of times to repeat photo in a row (as a string), so "1" for default; return "unlimited" for unlimited
    fun getRepeatIntervalPref(): Long // time in ms between repeat
    fun getGeotaggingPref(): Boolean // whether to geotag photos
    fun getRequireLocationPref(): Boolean // if getGeotaggingPref() returns true, and this method returns true, then phot/video will only be taken if location data is available
    fun getRecordAudioPref(): Boolean // whether to record audio when recording video
    fun getRecordAudioChannelsPref(): String // either "audio_default", "audio_mono" or "audio_stereo"
    fun getRecordAudioSourcePref(): String // "audio_src_camcorder" is recommended, but other options are: "audio_src_mic", "audio_src_default", "audio_src_voice_communication", "audio_src_unprocessed" (unprocessed required Android 7+); see corresponding values in android.media.MediaRecorder.AudioSource
    fun getZoomPref(): Int // index into Preview.getSupportedZoomRatios() array (each entry is the zoom factor, scaled by 100; array is sorted from min to max zoom); return -1 for default 1x zoom
    fun getCalibratedLevelAngle(): Double // set to a non-zero to calibrate the accelerometer used for the level angles
    fun canTakeNewPhoto(): Boolean // whether taking new photos is allowed (e.g., can return false if queue for processing images would become full)

    // called during some burst operations, whether we can allow taking the supplied number of extra photos
    fun imageQueueWouldBlock(nRaw: Int, nJpegs: Int): Boolean

    /** Same behavior as Activity.getWindowManager().getDefaultDisplay().getRotation() (including
     * returning a member of Surface.ROTATION_*), but allows application to modify e.g. for
     * upside-down preview.
     * @param preferLater When the device orientation changes, there can be some ambiguity if this
     * is called during this rotation, since getRotation() may update shortly
     * before the UI appears to rotate. If preferLater==false, then prefer the
     * previous rotation in such cases. This can be implemented by caching the
     * value. preferLater should be set to false when this is being called
     * frequently e.g. as part of a UI that should smoothly rotate as the device
     * rotates. preferLater should be set to true for "one-off" calls.
     */
    fun getDisplayRotation(preferLater: Boolean): Int

    // Camera2 only modes:
    fun getExposureTimePref(): Long // only called if getISOPref() is not "default"
    fun getFocusDistancePref(isTargetDistance: Boolean): Float // if isFocusBracketingPref()==true, returns the source or target focus distance
    fun isFocusBracketingSourceAutoPref(): Boolean // if isFocusBracketingPref()==true, returns whether the source focus distance should be set by calling CameraController.setFocusBracketingSourceDistanceFromCurrent()
    fun isExpoBracketingPref(): Boolean // whether to enable burst photos with expo bracketing
    fun getExpoBracketingNImagesPref(): Int // how many images to take for exposure bracketing
    fun getExpoBracketingStopsPref(): Double // stops per image for exposure bracketing
    fun getFocusBracketingNImagesPref(): Int // how many images to take for focus bracketing
    fun getFocusBracketingAddInfinityPref(): Boolean // whether to include an additional image at infinite focus distance, for focus bracketing
    fun isFocusBracketingPref(): Boolean // whether to enable burst photos with focus bracketing
    fun isCameraBurstPref(): Boolean // whether to shoot the camera in burst mode (n.b., not the same as the "auto-repeat" mode)
    fun getBurstNImages(): Int // only relevant if isCameraBurstPref() returns true; see CameraController doc for setBurstNImages().
    fun getBurstForNoiseReduction(): Boolean // only relevant if isCameraBurstPref() returns true; see CameraController doc for setBurstForNoiseReduction().
    enum class NRModePref {
        NRMODE_NORMAL,
        NRMODE_LOW_LIGHT
    }

    fun getNRModePref(): NRModePref // only relevant if getBurstForNoiseReduction() returns true; if this changes without reopening the preview's camera, call Preview.setupBurstMode()
    fun isCameraExtensionPref(): Boolean // whether to use camera vendor extension (see https://developer.android.com/reference/android/hardware/camera2/CameraExtensionCharacteristics )

    @RequiresApi(api = Build.VERSION_CODES.S)
    fun getCameraExtensionPref(): Int // if isCameraExtensionPref() returns true, the camera extension mode to use
    fun getAperturePref(): Float // get desired aperture (called if Preview.getSupportedApertures() returns non-null); return -1.0f for no preference
    fun getJpegRPref(): Boolean // whether to request JPEG_R (UltraHDR) photos
    enum class RawPref {
        RAWPREF_JPEG_ONLY,  // JPEG only
        RAWPREF_JPEG_DNG // JPEG and RAW (DNG)
    }

    fun getRawPref(): RawPref // whether to enable RAW photos
    fun getMaxRawImages(): Int // see documentation of CameraController.setRaw(), corresponds to maxRawImages
    fun useCamera2DummyCaptureHack(): Boolean // whether to enable CameraController.setDummyCaptureHack() for Camera2 API
    fun useCamera2FakeFlash(): Boolean // whether to enable CameraController.setUseCamera2FakeFlash() for Camera2 API
    fun useCamera2FastBurst(): Boolean // whether to enable Camera2's captureBurst() for faster taking of expo-bracketing photos (generally should be true, but some devices have problems with captureBurst())
    fun usePhotoVideoRecording(): Boolean // whether to enable support for taking photos when recording video (if not supported, this won't be called)
    fun isPreviewInBackground(): Boolean // if true, then Preview can disable real-time effects (e.g., computing histogram); also it won't try to open the camera when in the background
    fun allowZoom(): Boolean // if false, don't allow zoom functionality even if the device supports it - Preview.supportsZoom() will also return false; if true, allow zoom if the device supports it
    fun optimiseFocusForLatency(): Boolean // behavior for taking photos with continuous focus mode: if true, optimize focus for latency (take photo asap); if false, optimize for quality (don't take photo until scene is focused)

    /** Return size of default display, e.g., Activity.getWindowManager().getDefaultDisplay().getSize().
     * @param displaySize The returned display size.
     * @param excludeInsets If the activity is running in edge-to-edge mode, then whether to exclude
     * insets. If the activity is not running in edge-to-edge mode, then this should
     * be ignored, and insets should always be excluded.
     */
    fun getDisplaySize(displaySize: Point, excludeInsets: Boolean)

    // for testing purposes:
    fun isTestAlwaysFocus(): Boolean // if true, pretend autofocus always successful

    // methods that transmit information/events (up to the Application whether to do anything or not)
    fun cameraSetup() // called when the camera is (re-)set up - should update UI elements/parameters that depend on camera settings
    fun touchEvent(event: MotionEvent?)
    fun startingVideo() // called just before video recording starts
    fun startedVideo() // called just after video recording starts

    // called just before video recording stops; note that if startingVideo() is called but then video recording fails to start, this method will still be called, but startedVideo() and stoppedVideo() won't be called
    fun stoppingVideo()

    // called after video recording stopped (uri/filename will be null if video is corrupt or not created); will be called iff startedVideo() was called
    fun stoppedVideo(videoMethod: VideoMethod, uri: Uri?, filename: String?)

    // called after a seamless restart (supported on Android 8+) has occurred - in this case stoppedVideo() is only called for the final video file; this method is instead called for all earlier video file segments
    fun restartedVideo(videoMethod: VideoMethod, uri: Uri?, filename: String?)

    // application should delete the requested video (which will correspond to a video file previously returned via the createOutputVideo*() methods), either because it is corrupt or unused
    fun deleteUnusedVideo(videoMethod: VideoMethod, uri: Uri?, filename: String?)

    fun onFailedStartPreview() // called if failed to start camera preview
    fun onCameraError() // called if the camera closes due to serious error.
    fun onPhotoError() // callback for failing to take a photo

    // callback for info when recording video (see MediaRecorder.OnInfoListener)
    fun onVideoInfo(what: Int, extra: Int)

    // callback for errors when recording video (see MediaRecorder.OnErrorListener)
    fun onVideoError(what: Int, extra: Int)

    fun onVideoRecordStartError(profile: VideoProfile) // callback for video recording failing to start
    fun onVideoRecordStopError(profile: VideoProfile) // callback for video recording being corrupted
    fun onFailedReconnectError() // failed to reconnect camera after stopping video recording
    fun onFailedCreateVideoFileError() // callback if unable to create file for recording video
    fun hasPausedPreview(paused: Boolean) // called when the preview is paused or unpaused (due to getPausePreviewPref())

    // called when the camera starts/stops being operation (taking photos or recording video, including if preview is paused after taking a photo), use to disable GUI elements during camera operation
    fun cameraInOperation(inOperation: Boolean, isVideo: Boolean)

    fun turnFrontScreenFlashOn() // called when front-screen "flash" required (for modes flashFrontscreenAuto, flashFrontscreenOn); the application should light up the screen, until cameraInOperation(false) is called
    fun cameraClosed()
    fun timerBeep(remainingTime: Long) // n.b., called once per second on timer countdown - so application can beep, or do whatever it likes

    // methods that request actions
    fun multitouchZoom(newZoom: Int) // indicates that the zoom has changed due to multitouch gesture on preview
    fun requestTakePhoto() // requesting taking a photo (due to single/double tap, if either getTouchCapturePref(), getDoubleTouchCapturePref() options are enabled)

    // the set/clear*Pref() methods are called if Preview decides to override the requested pref (because Camera device doesn't support requested pref) (clear*Pref() is called if the feature isn't supported at all)
    // the application can use this information to update its preferences
    fun setCameraIdPref(cameraId: Int, cameraIdSPhysical: String?)
    fun setFlashPref(flashValue: String?)
    fun setFocusPref(focusValue: String?, isVideo: Boolean)
    fun setVideoPref(isVideo: Boolean)
    fun setSceneModePref(sceneMode: String?)
    fun clearSceneModePref()
    fun setColorEffectPref(colorEffect: String?)
    fun clearColorEffectPref()
    fun setWhiteBalancePref(whiteBalance: String?)
    fun clearWhiteBalancePref()
    fun setWhiteBalanceTemperaturePref(whiteBalanceTemperature: Int)
    fun setISOPref(iso: String?)
    fun clearISOPref()
    fun setExposureCompensationPref(exposure: Int)
    fun clearExposureCompensationPref()
    fun setCameraResolutionPref(width: Int, height: Int)
    fun setVideoQualityPref(videoQuality: String?)
    fun setZoomPref(zoom: Int)
    fun requestCameraPermission() // for Android 6+: called when trying to Open Kamera, but CAMERA permission not available
    fun needsStoragePermission(): Boolean // return true if the preview should call requestStoragePermission() if WRITE_EXTERNAL_STORAGE not available (i.e., if the application needs storage permission, e.g., to save photos)
    fun requestStoragePermission() // for Android 6+: called when trying to Open Kamera, but WRITE_EXTERNAL_STORAGE permission not available
    fun requestRecordAudioPermission() // for Android 6+: called when switching to (or starting up in) video mode, but RECORD_AUDIO permission not available

    // Camera2 only modes:
    fun setExposureTimePref(exposureTime: Long)
    fun clearExposureTimePref()
    fun setFocusDistancePref(focusDistance: Float, isTargetDistance: Boolean)

    // callbacks
    fun onDrawPreview(canvas: Canvas)
    fun onPictureTaken(data: ByteArray, currentDate: Date): Boolean
    fun onBurstPictureTaken(images: List<ByteArray>, currentDate: Date): Boolean
    fun onRawPictureTaken(rawImage: RawImage?, currentDate: Date): Boolean
    fun onRawBurstPictureTaken(rawImages: List<RawImage>, currentDate: Date): Boolean
    fun onCaptureStarted() // called immediately before we start capturing the picture
    fun onPictureCompleted() // called after all picture callbacks have been called and returned
    fun onExtensionProgress(progress: Int) // Reports percentage progress for vendor camera extensions. Note that not all devices support this being called.
    fun onContinuousFocusMove(start: Boolean) // called when focusing starts/stop in continuous picture mode (in photo mode only)
}
