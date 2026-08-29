/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.engine

interface IImageProcessor {
    suspend fun processHdr(frames: List<ByteArray>): Result<ByteArray>
    suspend fun processPanorama(frames: List<ByteArray>): Result<ByteArray>
    suspend fun processNoiseReduction(frame: ByteArray): Result<ByteArray>
}
