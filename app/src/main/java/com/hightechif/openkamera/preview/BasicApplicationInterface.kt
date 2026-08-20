/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import android.app.Activity
import android.graphics.Canvas
import android.location.Location
import android.net.Uri
import android.os.Build
import android.util.Pair
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.cameracontroller.RawImage
import java.util.Date

/** A partial implementation of ApplicationInterface that provides "default" implementations. So
 * sub-classing this is easier than implementing ApplicationInterface directly - you only have to
 * provide the unimplemented methods to get started, and can later override
 * BasicApplicationInterface's methods as required.
 * Note there is no need for your subclass of BasicApplicationInterface to call "super" methods -
 * these are just default implementations that should be overridden as required.
 */
abstract class BasicApplicationInterface : ApplicationInterface {
    override fun getLocation(): Location? {
        return null
    }

    override fun getCameraIdPref(): Int {
        return 0
    }

    override fun getCameraIdSPhysicalPref(): String? {
        return null
    }

    override fun getFlashPref(): String {
        return "flash_off"
    }

    override fun getFocusPref(isVideo: Boolean): String {
        return "focus_mode_continuous_picture"
    }

    override fun isVideoPref(): Boolean {
        return false
    }

    override fun getSceneModePref(): String {
        return CameraController.SCENE_MODE_DEFAULT
    }

    override fun getColorEffectPref(): String {
        return CameraController.COLOR_EFFECT_DEFAULT
    }

    override fun getWhiteBalancePref(): String {
        return CameraController.WHITE_BALANCE_DEFAULT
    }

    override fun getWhiteBalanceTemperaturePref(): Int {
        return 0
    }

    override fun getAntiBandingPref(): String {
        return CameraController.ANTIBANDING_DEFAULT
    }

    override fun getEdgeModePref(): String {
        return CameraController.EDGE_MODE_DEFAULT
    }

    override fun getCameraNoiseReductionModePref(): String {
        return CameraController.NOISE_REDUCTION_MODE_DEFAULT
    }

    override fun getISOPref(): String {
        return CameraController.ISO_DEFAULT
    }

    override fun getExposureCompensationPref(): Int {
        return 0
    }

    override fun getCameraResolutionPref(constraints: ApplicationInterface.CameraResolutionConstraints): Pair<Int, Int>? {
        return null
    }

    override fun getImageQualityPref(): Int {
        return 90
    }

    override fun getFaceDetectionPref(): Boolean {
        return false
    }

    override fun getVideoQualityPref(): String {
        return ""
    }

    override fun getVideoStabilizationPref(): Boolean {
        return false
    }

    override fun getForce4KPref(): Boolean {
        return false
    }

    override fun getRecordVideoOutputFormatPref(): String {
        return "preference_video_output_format_default"
    }

    override fun getVideoBitratePref(): String {
        return "default"
    }

    override fun getVideoFPSPref(): String {
        return "default"
    }

    override fun getVideoCaptureRateFactor(): Float {
        return 1.0f
    }

    override fun getVideoTonemapProfile(): CameraController.TonemapProfile {
        return CameraController.TonemapProfile.TONEMAPPROFILE_OFF
    }

    override fun getVideoLogProfileStrength(): Float {
        return 0f
    }

    override fun getVideoProfileGamma(): Float {
        return 0f
    }

    override fun getVideoMaxDurationPref(): Long {
        return 0
    }

    override fun getVideoRestartTimesPref(): Int {
        return 0
    }

    @Throws(ApplicationInterface.NoFreeStorageException::class)
    override fun getVideoMaxFileSizePref(): ApplicationInterface.VideoMaxFileSize {
        val videoMaxFilesize = ApplicationInterface.VideoMaxFileSize()
        videoMaxFilesize.maxFilesize = 0
        videoMaxFilesize.autoRestart = true
        return videoMaxFilesize
    }

    override fun getVideoFlashPref(): Boolean {
        return false
    }

    override fun getVideoLowPowerCheckPref(): Boolean {
        return true
    }

    override fun getPreviewSizePref(): String {
        return "preference_preview_size_wysiwyg"
    }

    override fun getLockOrientationPref(): String {
        return "none"
    }

    override fun getTouchCapturePref(): Boolean {
        return false
    }

    override fun getDoubleTapCapturePref(): Boolean {
        return false
    }

    override fun getPausePreviewPref(): Boolean {
        return false
    }

    override fun getShowToastsPref(): Boolean {
        return true
    }

    override fun getShutterSoundPref(): Boolean {
        return true
    }

    override fun getStartupFocusPref(): Boolean {
        return true
    }

    override fun getTimerPref(): Long {
        return 0
    }

    override fun getRepeatPref(): String {
        return "1"
    }

    override fun getRepeatIntervalPref(): Long {
        return 0
    }

    override fun getGeotaggingPref(): Boolean {
        return false
    }

    override fun getRequireLocationPref(): Boolean {
        return false
    }

    override fun getRecordAudioPref(): Boolean {
        return true
    }

    override fun getRecordAudioChannelsPref(): String {
        return "audio_default"
    }

    override fun getRecordAudioSourcePref(): String {
        return "audio_src_camcorder"
    }

    override fun getZoomPref(): Int {
        return -1
    }

    override fun getCalibratedLevelAngle(): Double {
        return 0.0
    }

    override fun canTakeNewPhoto(): Boolean {
        return true
    }

    override fun imageQueueWouldBlock(nRaw: Int, nJpegs: Int): Boolean {
        return false
    }

    override fun getDisplayRotation(preferLater: Boolean): Int {
        val activity = this.context as Activity
        return activity.windowManager.defaultDisplay.rotation
    }

    override fun getExposureTimePref(): Long {
        return CameraController.EXPOSURE_TIME_DEFAULT
    }

    override fun getFocusDistancePref(isTargetDistance: Boolean): Float {
        return 0f
    }

    override fun isExpoBracketingPref(): Boolean {
        return false
    }

    override fun getExpoBracketingNImagesPref(): Int {
        return 3
    }

    override fun getExpoBracketingStopsPref(): Double {
        return 2.0
    }

    override fun getFocusBracketingNImagesPref(): Int {
        return 3
    }

    override fun getFocusBracketingAddInfinityPref(): Boolean {
        return false
    }

    override fun isFocusBracketingPref(): Boolean {
        return false
    }

    override fun isCameraBurstPref(): Boolean {
        return false
    }

    override fun getBurstNImages(): Int {
        return 5
    }

    override fun getBurstForNoiseReduction(): Boolean {
        return false
    }

    override fun getNRModePref(): ApplicationInterface.NRModePref {
        return ApplicationInterface.NRModePref.NRMODE_NORMAL
    }

    override fun isCameraExtensionPref(): Boolean {
        return false
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    override fun getCameraExtensionPref(): Int {
        return 0
    }

    override fun getAperturePref(): Float {
        return -1.0f
    }

    override fun getJpegRPref(): Boolean {
        return false
    }

    override fun getRawPref(): ApplicationInterface.RawPref {
        return ApplicationInterface.RawPref.RAWPREF_JPEG_ONLY
    }

    override fun getMaxRawImages(): Int {
        return 2
    }

    override fun useCamera2DummyCaptureHack(): Boolean {
        return false
    }

    override fun useCamera2FakeFlash(): Boolean {
        return false
    }

    override fun useCamera2FastBurst(): Boolean {
        return true
    }

    override fun usePhotoVideoRecording(): Boolean {
        return true
    }

    override fun isPreviewInBackground(): Boolean {
        return false
    }

    override fun allowZoom(): Boolean {
        return true
    }

    override fun optimiseFocusForLatency(): Boolean {
        return true
    }

    override fun isTestAlwaysFocus(): Boolean {
        return false
    }

    override fun cameraSetup() {
    }

    override fun touchEvent(event: MotionEvent?) {
    }

    override fun startingVideo() {
    }

    override fun startedVideo() {
    }

    override fun stoppingVideo() {
    }

    override fun stoppedVideo(
        videoMethod: ApplicationInterface.VideoMethod,
        uri: Uri?,
        filename: String?
    ) {
    }

    override fun restartedVideo(
        videoMethod: ApplicationInterface.VideoMethod,
        uri: Uri?,
        filename: String?
    ) {
    }

    override fun deleteUnusedVideo(
        videoMethod: ApplicationInterface.VideoMethod,
        uri: Uri?,
        filename: String?
    ) {
    }

    override fun onFailedStartPreview() {
    }

    override fun onCameraError() {
    }

    override fun onPhotoError() {
    }

    override fun onVideoInfo(what: Int, extra: Int) {
    }

    override fun onVideoError(what: Int, extra: Int) {
    }

    override fun onVideoRecordStartError(profile: VideoProfile) {
    }

    override fun onVideoRecordStopError(profile: VideoProfile) {
    }

    override fun onFailedReconnectError() {
    }

    override fun onFailedCreateVideoFileError() {
    }

    override fun hasPausedPreview(paused: Boolean) {
    }

    override fun cameraInOperation(inOperation: Boolean, isVideo: Boolean) {
    }

    override fun turnFrontScreenFlashOn() {
    }

    override fun cameraClosed() {
    }

    override fun timerBeep(remainingTime: Long) {
    }

    override fun multitouchZoom(newZoom: Int) {
    }

    override fun requestTakePhoto() {
    }

    override fun setCameraIdPref(cameraId: Int, cameraIdSPhysical: String?) {
    }

    override fun setFlashPref(flashValue: String?) {
    }

    override fun setFocusPref(focusValue: String?, isVideo: Boolean) {
    }

    override fun setVideoPref(isVideo: Boolean) {
    }

    override fun setSceneModePref(sceneMode: String?) {
    }

    override fun clearSceneModePref() {
    }

    override fun setColorEffectPref(colorEffect: String?) {
    }

    override fun clearColorEffectPref() {
    }

    override fun setWhiteBalancePref(whiteBalance: String?) {
    }

    override fun clearWhiteBalancePref() {
    }

    override fun setWhiteBalanceTemperaturePref(whiteBalanceTemperature: Int) {
    }

    override fun setISOPref(iso: String?) {
    }

    override fun clearISOPref() {
    }

    override fun setExposureCompensationPref(exposure: Int) {
    }

    override fun clearExposureCompensationPref() {
    }

    override fun setCameraResolutionPref(width: Int, height: Int) {
    }

    override fun setVideoQualityPref(videoQuality: String?) {
    }

    override fun setZoomPref(zoom: Int) {
    }

    override fun setExposureTimePref(exposureTime: Long) {
    }

    override fun clearExposureTimePref() {
    }

    override fun setFocusDistancePref(focusDistance: Float, isTargetDistance: Boolean) {
    }

    override fun onDrawPreview(canvas: Canvas) {
    }

    override fun onBurstPictureTaken(images: List<ByteArray>, currentDate: Date): Boolean {
        return false
    }

    override fun onRawPictureTaken(rawImage: RawImage?, currentDate: Date): Boolean {
        return false
    }

    override fun onRawBurstPictureTaken(
        rawImages: List<RawImage>,
        currentDate: Date
    ): Boolean {
        return false
    }

    override fun onCaptureStarted() {
    }

    override fun onPictureCompleted() {
    }

    override fun onExtensionProgress(progress: Int) {
    }

    override fun onContinuousFocusMove(start: Boolean) {
    }
}
