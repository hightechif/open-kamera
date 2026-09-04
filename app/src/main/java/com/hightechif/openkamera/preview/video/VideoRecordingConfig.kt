/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.video

import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Immutable configuration defining parameters for a video recording session.
 */
data class VideoRecordingConfig(
    val videoQuality: String = "",
    val videoBitrate: Int = 0,
    val videoFrameRate: Int = 0,
    val captureRate: Double = 0.0,
    val maxDurationMs: Long = 0L,
    val maxFileSize: Long = 0L,
    val restartOnMaxFileSize: Boolean = true,
    val recordAudio: Boolean = true,
    val audioSource: Int = MediaRecorder.AudioSource.CAMCORDER,
    val audioChannels: Int = 2,
    val audioSampleRate: Int = 44100,
    val audioBitRate: Int = 128000,
    val videoFormat: Int = MediaRecorder.OutputFormat.MPEG_4,
    val videoCodec: Int = MediaRecorder.VideoEncoder.H264,
    val audioCodec: Int = MediaRecorder.AudioEncoder.AAC,
    val outputFile: File? = null,
    val outputUri: Uri? = null,
    val pfdSaf: ParcelFileDescriptor? = null,
    val isHighSpeedSlowMotion: Boolean = false,
    val highSpeedFps: Int = 0,
    val flashTorch: Boolean = false
)
