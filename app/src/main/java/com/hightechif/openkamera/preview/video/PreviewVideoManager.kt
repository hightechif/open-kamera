/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.video

import android.location.Location
import android.media.MediaRecorder
import com.hightechif.openkamera.preview.ApplicationInterface
import com.hightechif.openkamera.preview.VideoProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages video recording operations for the preview subsystem, coordinating with
 * [VideoRecordingCoordinator], managing audio channels, bitrates, file size splitting,
 * and emitting state updates.
 */
class PreviewVideoManager(
    private val applicationInterface: ApplicationInterface,
    val coordinator: VideoRecordingCoordinator = VideoRecordingCoordinator()
) {

    val sessionState: StateFlow<VideoSessionState> = coordinator.sessionState

    val isRecording: Boolean
        get() = coordinator.isRecording

    val isPaused: Boolean
        get() = coordinator.isPaused

    val maxAmplitude: Int
        get() = coordinator.maxAmplitude

    fun setEventListener(listener: VideoRecordingCoordinator.VideoEventListener) {
        coordinator.listener = listener
    }

    fun startRecording(
        profile: VideoProfile,
        output: VideoSessionOutput,
        maxFileSize: Long,
        maxDurationMs: Long,
        orientationHint: Int = 0,
        location: Location? = null,
        prePrepareCallback: ((MediaRecorder) -> Unit)? = null,
        postPrepareCallback: ((MediaRecorder) -> Unit)? = null
    ): Boolean {
        return coordinator.startRecording(
            profile = profile,
            output = output,
            maxFileSize = maxFileSize,
            maxDurationMs = maxDurationMs,
            orientationHint = orientationHint,
            location = location,
            prePrepareCallback = prePrepareCallback,
            postPrepareCallback = postPrepareCallback
        )
    }

    fun pauseRecording(): Boolean = coordinator.pauseRecording()

    fun resumeRecording(): Boolean = coordinator.resumeRecording()

    fun stopRecording(): Boolean = coordinator.stopRecording()
}
