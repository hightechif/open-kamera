/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.timer

/**
 * State representing the capture countdown timer.
 */
sealed interface CountdownState {
    data object Idle : CountdownState
    data class InProgress(val remainingTimeMs: Long, val totalTimeMs: Long) : CountdownState
    data object Finished : CountdownState
    data object Cancelled : CountdownState
}
