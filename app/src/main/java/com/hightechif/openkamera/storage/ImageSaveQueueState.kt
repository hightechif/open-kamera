/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

/**
 * Immutable state model representing the real-time status and load of the image saving pipeline.
 *
 * @property pendingCount Total count of all requests currently queued or being processed.
 * @property realImageCount Count of actual user-visible image requests (excluding dummy/lifecycle markers).
 * @property isProcessing True if at least one saving or post-processing coroutine is actively executing.
 * @property isBlocked True if the pipeline queue capacity is full and incoming capture requests would block.
 * @property queueCapacity The maximum dynamic capacity configured for the pipeline channel.
 */
data class ImageSaveQueueState(
    val pendingCount: Int = 0,
    val realImageCount: Int = 0,
    val isProcessing: Boolean = false,
    val isBlocked: Boolean = false,
    val queueCapacity: Int = 0
) {
    val isIdle: Boolean
        get() = pendingCount == 0 && !isProcessing
}
