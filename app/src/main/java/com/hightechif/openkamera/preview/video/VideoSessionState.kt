/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.video

/**
 * State representing a video recording session.
 */
sealed interface VideoSessionState {
    data object Idle : VideoSessionState

    data class Recording(
        val output: VideoSessionOutput,
        val startTimeMs: Long,
        val isPaused: Boolean = false
    ) : VideoSessionState

    data class RestartingMaxFileSize(
        val previousOutput: VideoSessionOutput,
        val nextOutput: VideoSessionOutput?
    ) : VideoSessionState

    data object Stopped : VideoSessionState
}
