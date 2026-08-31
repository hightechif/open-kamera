/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.timer

/**
 * Configuration for scheduled repeat photo bursts.
 */
data class BurstScheduleConfig(
    val totalPhotos: Int = 1,
    val burstIntervalMs: Long = 0L,
    val isBurstInfinite: Boolean = false
)
