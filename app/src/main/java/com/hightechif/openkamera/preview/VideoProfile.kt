/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.util.Log
import com.hightechif.openkamera.domain.model.VideoProfileConfig
import com.hightechif.openkamera.utils.MyDebug

/** This is essentially similar to CamcorderProfile in that it encapsulates a set of video settings
 * to be passed to MediaRecorder, but allows us to store additional fields.
 */
class VideoProfile {
    var recordAudio: Boolean = false
    var noAudioPermission: Boolean =
        false // set to true if recordAudio==false, but where the user had requested audio, and we don't have microphone permission
    var audioSource: Int = 0
    var audioCodec: Int = 0
    var audioChannels: Int = 0
    var audioBitRate: Int = 0
    var audioSampleRate: Int = 0
    var fileFormat: Int = 0
    var fileExtension: String = "mp4"
    var videoSource: Int = 0
    var videoCodec: Int = 0
    var videoFrameRate: Int = 0
    var videoCaptureRate: Double = 0.0
    var videoBitRate: Int = 0
    var videoFrameHeight: Int = 0
    var videoFrameWidth: Int = 0

    /** Returns a dummy video profile, used if video isn't supported.
     */
    internal constructor()

    internal constructor(camcorderProfile: CamcorderProfile) {
        this.recordAudio = true
        this.noAudioPermission = false
        this.audioSource = MediaRecorder.AudioSource.CAMCORDER
        this.audioCodec = camcorderProfile.audioCodec
        this.audioChannels = camcorderProfile.audioChannels
        this.audioBitRate = camcorderProfile.audioBitRate
        this.audioSampleRate = camcorderProfile.audioSampleRate
        this.fileFormat = camcorderProfile.fileFormat
        this.videoSource = MediaRecorder.VideoSource.CAMERA
        this.videoCodec = camcorderProfile.videoCodec
        this.videoFrameRate = camcorderProfile.videoFrameRate
        this.videoCaptureRate = camcorderProfile.videoFrameRate.toDouble()
        this.videoBitRate = camcorderProfile.videoBitRate
        this.videoFrameHeight = camcorderProfile.videoFrameHeight
        this.videoFrameWidth = camcorderProfile.videoFrameWidth
    }

    override fun toString(): String {
        return ("""
     
     AudioSource:        $audioSource
     VideoSource:        $videoSource
     FileFormat:         $fileFormat
     FileExtension:         $fileExtension
     AudioCodec:         $audioCodec
     AudioChannels:      $audioChannels
     AudioBitrate:       $audioBitRate
     AudioSampleRate:    $audioSampleRate
     VideoCodec:         $videoCodec
     VideoFrameRate:     $videoFrameRate
     VideoCaptureRate:   $videoCaptureRate
     VideoBitRate:       $videoBitRate
     VideoWidth:         $videoFrameWidth
     VideoHeight:        $videoFrameHeight
     """.trimIndent()
                )
    }

    /**
     * Copies the fields of this profile to a MediaRecorder instance.
     */
    fun copyToMediaRecorder(mediaRecorder: MediaRecorder) {
        if (MyDebug.LOG) Log.d(
            TAG,
            "copyToMediaRecorder: $mediaRecorder"
        )
        if (recordAudio) {
            if (MyDebug.LOG) Log.d(TAG, "record audio")
            mediaRecorder.setAudioSource(this.audioSource)
        }
        mediaRecorder.setVideoSource(this.videoSource)
        // n.b., order may be important - output format should be first, at least
        // also match order of MediaRecorder.setProfile() just to be safe, see https://stackoverflow.com/questions/5524672/is-it-possible-to-use-camcorderprofile-without-audio-source
        mediaRecorder.setOutputFormat(this.fileFormat)
        if (MyDebug.LOG) Log.d(TAG, "set frame rate: " + this.videoFrameRate)
        mediaRecorder.setVideoFrameRate(this.videoFrameRate)
        // it's probably safe to always call setCaptureRate, but to be safe (and keep compatibility with old Open Kamera versions), we only do so when needed
        if (this.videoCaptureRate != videoFrameRate.toDouble()) {
            if (MyDebug.LOG) Log.d(TAG, "set capture rate: " + this.videoCaptureRate)
            mediaRecorder.setCaptureRate(this.videoCaptureRate)
        }
        mediaRecorder.setVideoSize(this.videoFrameWidth, this.videoFrameHeight)
        mediaRecorder.setVideoEncodingBitRate(this.videoBitRate)
        mediaRecorder.setVideoEncoder(this.videoCodec)
        if (recordAudio) {
            mediaRecorder.setAudioEncodingBitRate(this.audioBitRate)
            mediaRecorder.setAudioChannels(this.audioChannels)
            mediaRecorder.setAudioSamplingRate(this.audioSampleRate)
            mediaRecorder.setAudioEncoder(this.audioCodec)
        }
        if (MyDebug.LOG) Log.d(TAG, "done: $mediaRecorder")
    }

    /**
     * Converts mutable [VideoProfile] to immutable [VideoProfileConfig].
     */
    fun toVideoProfileConfig(): VideoProfileConfig {
        return VideoProfileConfig(
            width = this.videoFrameWidth,
            height = this.videoFrameHeight,
            frameRate = this.videoFrameRate,
            bitRate = this.videoBitRate,
            videoCodec = this.videoCodec,
            videoFormat = this.fileFormat,
            audioCodec = this.audioCodec,
            audioSource = this.audioSource,
            audioSampleRate = this.audioSampleRate,
            audioBitRate = this.audioBitRate,
            audioChannels = this.audioChannels,
            isHighSpeed = this.videoCaptureRate > this.videoFrameRate,
            captureRateFactor = if (this.videoFrameRate > 0) (this.videoCaptureRate / this.videoFrameRate).toFloat() else 1.0f
        )
    }

    companion object {
        private const val TAG = "VideoProfile"

        fun fromConfig(config: VideoProfileConfig): VideoProfile {
            return VideoProfile().apply {
                this.recordAudio = true
                this.videoFrameWidth = config.width
                this.videoFrameHeight = config.height
                this.videoFrameRate = config.frameRate
                this.videoCaptureRate = (config.frameRate * config.captureRateFactor).toDouble()
                this.videoBitRate = config.bitRate
                this.videoCodec = config.videoCodec
                this.fileFormat = config.videoFormat
                this.audioCodec = config.audioCodec
                this.audioSource = config.audioSource
                this.audioSampleRate = config.audioSampleRate
                this.audioBitRate = config.audioBitRate
                this.audioChannels = config.audioChannels
                this.videoSource = MediaRecorder.VideoSource.CAMERA
            }
        }
    }
}