/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.repository

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface IMediaRepository {
    val latestMediaThumbnailFlow: Flow<Uri?>

    suspend fun getLatestMediaUri(): Uri?
}
