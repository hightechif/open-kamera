/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.video

import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.util.Log
import com.hightechif.openkamera.preview.VideoProfile
import com.hightechif.openkamera.utils.MyDebug

/**
 * Resolves video recording profiles, custom bitrates, frame rates, and container formats.
 */
class VideoProfileResolver {

    companion object {
        private const val TAG = "VideoProfileResolver"

        /**
         * Resolves the container file extension corresponding to a MediaRecorder output format.
         */
        fun getFileExtension(fileFormat: Int): String {
            return when (fileFormat) {
                MediaRecorder.OutputFormat.THREE_GPP -> "3gp"
                MediaRecorder.OutputFormat.WEBM -> "webm"
                else -> "mp4"
            }
        }
    }

    /**
     * Resolves a [VideoProfile] based on camera ID, quality key string, and user preference overrides.
     */
    fun resolveProfile(
        cameraId: Int,
        qualityString: String,
        customBitrate: Int = 0,
        customFps: Int = 0,
        captureRate: Double = 0.0,
        recordAudio: Boolean = true,
        audioSource: Int = MediaRecorder.AudioSource.CAMCORDER
    ): VideoProfile {
        if (MyDebug.LOG) Log.d(TAG, "resolveProfile: cameraId=$cameraId, quality=$qualityString, customBitrate=$customBitrate, customFps=$customFps")

        val profile: VideoProfile
        val rIndex = qualityString.indexOf("_r")
        if (rIndex != -1) {
            // Format: [baseProfile]_r[width]x[height]
            val profileString = qualityString.substring(0, rIndex)
            val baseProfile = profileString.toIntOrNull() ?: CamcorderProfile.QUALITY_HIGH
            val dimensions = qualityString.substring(rIndex + 2)
            val xIndex = dimensions.indexOf("x")
            val width = dimensions.substring(0, xIndex).toIntOrNull() ?: 1920
            val height = dimensions.substring(xIndex + 1).toIntOrNull() ?: 1080

            profile = if (CamcorderProfile.hasProfile(cameraId, baseProfile)) {
                val base = CamcorderProfile.get(cameraId, baseProfile)
                VideoProfile(base).apply {
                    videoFrameWidth = width
                    videoFrameHeight = height
                }
            } else {
                createDefaultProfile(width, height)
            }
        } else {
            val qualityInt = qualityString.toIntOrNull() ?: CamcorderProfile.QUALITY_HIGH
            profile = if (CamcorderProfile.hasProfile(cameraId, qualityInt)) {
                VideoProfile(CamcorderProfile.get(cameraId, qualityInt))
            } else if (CamcorderProfile.hasProfile(cameraId, CamcorderProfile.QUALITY_HIGH)) {
                VideoProfile(CamcorderProfile.get(cameraId, CamcorderProfile.QUALITY_HIGH))
            } else {
                createDefaultProfile(1920, 1080)
            }
        }

        // Apply audio preferences
        profile.recordAudio = recordAudio
        profile.audioSource = audioSource

        // Apply custom bitrate if specified
        if (customBitrate > 0) {
            profile.videoBitRate = customBitrate
        }

        // Apply custom frame rate if specified
        if (customFps > 0) {
            profile.videoFrameRate = customFps
        }

        // Apply slow-motion / time-lapse capture rate
        if (captureRate > 0.0) {
            profile.videoCaptureRate = captureRate
        }

        profile.fileExtension = getFileExtension(profile.fileFormat)
        return profile
    }

    private fun createDefaultProfile(width: Int, height: Int): VideoProfile {
        return VideoProfile().apply {
            recordAudio = true
            audioSource = MediaRecorder.AudioSource.CAMCORDER
            audioCodec = MediaRecorder.AudioEncoder.AAC
            audioChannels = 2
            audioBitRate = 128000
            audioSampleRate = 44100
            fileFormat = MediaRecorder.OutputFormat.MPEG_4
            videoSource = MediaRecorder.VideoSource.CAMERA
            videoCodec = MediaRecorder.VideoEncoder.H264
            videoFrameRate = 30
            videoCaptureRate = 30.0
            videoBitRate = 10000000
            videoFrameWidth = width
            videoFrameHeight = height
            fileExtension = "mp4"
        }
    }
}
