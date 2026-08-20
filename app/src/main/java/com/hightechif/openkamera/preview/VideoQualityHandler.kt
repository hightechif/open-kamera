/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import android.media.CamcorderProfile
import android.util.Log
import com.hightechif.openkamera.cameracontroller.CameraController
import com.hightechif.openkamera.utils.MyDebug
import java.io.Serializable
import java.util.Collections

/** Handles video quality options.
 * Note that this class should avoid calls to the Android API, so we can perform local unit testing
 * on it.
 */
class VideoQualityHandler {
    class Dimension2D(val width: Int, val height: Int)

    // videoQuality can either be:
    // - an int, in which case it refers to a CamcorderProfile
    // - of the form [CamcorderProfile]_r[width]x[height] - we use the CamcorderProfile as a base, and override the video resolution - this is needed to support resolutions which don't have corresponding camcorder profiles
    private var videoQuality: MutableList<String> = mutableListOf()

    // this is an index into the videoQuality array, or -1 if not found (though this shouldn't happen?)
    private var _currentVideoQualityIndex = -1
    private var videoSizes: MutableList<CameraController.Size>? = null

    // may be null if high speed not supported
    private var videoSizesHighSpeed: MutableList<CameraController.Size>? = null

    fun resetCurrentQuality() {
        videoQuality = mutableListOf()
        _currentVideoQualityIndex = -1
    }

    /** Initialises the class with the available video profiles and resolutions. The user should first
     * set the video sizes via setVideoSizes().
     * @param profiles   A list of qualities (see CamcorderProfile.QUALITY_*). Should be supplied in
     * order from highest to lowest quality.
     * @param dimensions A corresponding list of the width/height for that quality (as given by
     * videoFrameWidth, videoFrameHeight in the profile returned by CamcorderProfile.get()).
     */
    fun initialiseVideoQualityFromProfiles(profiles: List<Int>, dimensions: List<Dimension2D>) {
        if (MyDebug.LOG) Log.d(TAG, "initialiseVideoQualityFromProfiles()")
        videoQuality = ArrayList()
        var doneVideoSize: BooleanArray? = null
        if (videoSizes != null) {
            doneVideoSize = BooleanArray(videoSizes!!.size)
            for (i in videoSizes!!.indices) doneVideoSize[i] = false
        }
        if (profiles.size != dimensions.size) {
            Log.e(TAG, "profiles and dimensions have unequal sizes")
            throw RuntimeException() // this is a programming error
        }
        for (i in profiles.indices) {
            val dim = dimensions[i]
            addVideoResolutions(doneVideoSize!!, profiles[i], dim.width, dim.height)
        }
        if (MyDebug.LOG) {
            for (i in videoQuality.indices) {
                Log.d(TAG, "supported video quality: " + videoQuality[i])
            }
        }
    }

    // Android docs and FindBugs recommend that Comparators also be Serializable
    private class SortVideoSizesComparator : Comparator<CameraController.Size>, Serializable {
        override fun compare(a: CameraController.Size, b: CameraController.Size): Int {
            return b.width * b.height - a.width * a.height
        }

        companion object {
            private const val serialVersionUID = 5802214721033718212L
        }
    }

    fun sortVideoSizes() {
        if (MyDebug.LOG) Log.d(TAG, "sortVideoSizes()")
        if (this.videoSizes?.isNotEmpty() == true) {
            Collections.sort(this.videoSizes!!, SortVideoSizesComparator())
            if (MyDebug.LOG) {
                for (size in videoSizes!!) {
                    Log.d(TAG, "    supported video size: " + size.width + ", " + size.height)
                }
            }
        }
    }

    private fun addVideoResolutions(
        doneVideoSize: BooleanArray,
        baseProfile: Int,
        minResolutionW: Int,
        minResolutionH: Int
    ) {
        if (videoSizes == null) {
            return
        }
        if (MyDebug.LOG) Log.d(
            TAG,
            "profile $baseProfile is resolution $minResolutionW x $minResolutionH"
        )
        for (i in videoSizes!!.indices) {
            if (doneVideoSize[i]) continue
            val size: CameraController.Size = videoSizes!![i]
            if (size.width == minResolutionW && size.height == minResolutionH) {
                val str = baseProfile.toString()
                videoQuality.add(str)
                doneVideoSize[i] = true
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "added: " + i + ":" + str + " " + size.width + "x" + size.height
                )
            } else if (baseProfile == CamcorderProfile.QUALITY_LOW || size.width * size.height >= minResolutionW * minResolutionH) {
                val str = baseProfile.toString() + "_r" + size.width + "x" + size.height
                videoQuality.add(str)
                doneVideoSize[i] = true
                if (MyDebug.LOG) Log.d(
                    TAG,
                    "added: $i:$str"
                )
            }
        }
    }

    val supportedVideoQuality: List<String>
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedVideoQuality")
            return this.videoQuality
        }

    var currentVideoQualityIndex: Int
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getCurrentVideoQualityIndex")
            return this._currentVideoQualityIndex
        }
        set(currentVideoQuality) {
            if (MyDebug.LOG) Log.d(
                TAG,
                "setCurrentVideoQualityIndex: $currentVideoQuality"
            )
            this._currentVideoQualityIndex = currentVideoQuality
        }

    val currentVideoQuality: String?
        get() {
            if (_currentVideoQualityIndex == -1) return null
            return videoQuality[_currentVideoQualityIndex]
        }

    val supportedVideoSizes: List<CameraController.Size>
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedVideoSizes")
            return this.videoSizes ?: emptyList()
        }

    val supportedVideoSizesHighSpeed: MutableList<CameraController.Size>?
        get() {
            if (MyDebug.LOG) Log.d(TAG, "getSupportedVideoSizesHighSpeed")
            return this.videoSizesHighSpeed
        }

    /** Whether the requested fps is supported, without relying on high-speed mode.
     * Typically caller should first check videoSupportsFrameRateHighSpeed().
     */
    fun videoSupportsFrameRate(fps: Int): Boolean {
        return CameraController.CameraFeatures.supportsFrameRate(this.videoSizes, fps)
    }

    /** Whether the requested fps is supported as a high-speed mode.
     */
    fun videoSupportsFrameRateHighSpeed(fps: Int): Boolean {
        return CameraController.CameraFeatures.supportsFrameRate(this.videoSizesHighSpeed, fps)
    }

    fun findVideoSizeForFrameRate(
        width: Int,
        height: Int,
        fps: Double,
        returnClosest: Boolean
    ): CameraController.Size? {
        if (MyDebug.LOG) {
            Log.d(TAG, "findVideoSizeForFrameRate")
            Log.d(TAG, "width: $width")
            Log.d(TAG, "height: $height")
            Log.d(TAG, "fps: $fps")
        }
        val requestedSize: CameraController.Size = CameraController.Size(width, height)
        var bestVideoSize: CameraController.Size? = CameraController.CameraFeatures.findSize(
            supportedVideoSizes, requestedSize, fps, returnClosest
        )
        if (bestVideoSize == null && this.supportedVideoSizesHighSpeed != null) {
            if (MyDebug.LOG) Log.d(TAG, "need to check high speed sizes")
            // check high speed
            bestVideoSize = CameraController.CameraFeatures.findSize(
                this.supportedVideoSizesHighSpeed!!,
                requestedSize,
                fps,
                returnClosest
            )
        }
        return bestVideoSize
    }

    val maxSupportedVideoSize: CameraController.Size
        /** Returns the maximum supported (non-high-speed) video size.
         */
        get() = getMaxVideoSize(videoSizes!!)

    val maxSupportedVideoSizeHighSpeed: CameraController.Size
        /** Returns the maximum supported high speed video size.
         */
        get() = getMaxVideoSize(videoSizesHighSpeed!!)

    fun setVideoSizes(videoSizes: List<CameraController.Size>?) {
        this.videoSizes = videoSizes?.toMutableList()
        this.sortVideoSizes()
    }

    fun setVideoSizesHighSpeed(videoSizesHighSpeed: List<CameraController.Size>?) {
        this.videoSizesHighSpeed = videoSizesHighSpeed?.toMutableList()
    }

    companion object {
        private const val TAG = "VideoQualityHandler"

        private fun getMaxVideoSize(sizes: List<CameraController.Size>): CameraController.Size {
            var maxWidth = -1
            var maxHeight = -1
            for (size in sizes) {
                if (maxWidth == -1 || size.width * size.height > maxWidth * maxHeight) {
                    maxWidth = size.width
                    maxHeight = size.height
                }
            }
            return CameraController.Size(maxWidth, maxHeight)
        }
    }
}
