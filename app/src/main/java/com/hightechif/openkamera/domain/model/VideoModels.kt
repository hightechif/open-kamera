/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.model

import android.media.MediaRecorder

/**
 * Immutable configuration for video recording sessions and encoder surfaces.
 */
data class VideoProfileConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 30,
    val bitRate: Int = 20_000_000,
    val videoCodec: Int = MediaRecorder.VideoEncoder.H264,
    val videoFormat: Int = MediaRecorder.OutputFormat.MPEG_4,
    val audioCodec: Int = MediaRecorder.AudioEncoder.AAC,
    val audioSource: Int = MediaRecorder.AudioSource.CAMCORDER,
    val audioSampleRate: Int = 44100,
    val audioBitRate: Int = 128000,
    val audioChannels: Int = 2,
    val isHighSpeed: Boolean = false,
    val captureRateFactor: Float = 1.0f
)

/**
 * Preset resolution profiles for fast matching and UI display.
 */
enum class VideoResolutionPreset(val width: Int, val height: Int, val displayName: String) {
    RES_4K_UHD(3840, 2160, "4K UHD (3840x2160)"),
    RES_1080P_FHD(1920, 1080, "Full HD 1080p (1920x1080)"),
    RES_720P_HD(1280, 720, "HD 720p (1280x720)"),
    RES_480P_SD(720, 480, "SD 480p (720x480)"),
    RES_CUSTOM(0, 0, "Custom Resolution");

    companion object {
        fun fromDimensions(width: Int, height: Int): VideoResolutionPreset {
            return entries.firstOrNull { it.width == width && it.height == height } ?: RES_CUSTOM
        }
    }
}

/**
 * Supported video encoder codecs.
 */
enum class VideoCodecPreset(val encoder: Int, val format: Int, val mimeType: String) {
    H264(MediaRecorder.VideoEncoder.H264, MediaRecorder.OutputFormat.MPEG_4, "video/avc"),
    HEVC_H265(MediaRecorder.VideoEncoder.HEVC, MediaRecorder.OutputFormat.MPEG_4, "video/hevc")
}

/**
 * Real-time audio amplitude and volume telemetry during video capture.
 */
data class AudioAmplitudeData(
    val currentRms: Double = 0.0,
    val peakDecibels: Float = -60.0f,
    val isClipped: Boolean = false,
    val sampleTimestampNs: Long = 0L
)
