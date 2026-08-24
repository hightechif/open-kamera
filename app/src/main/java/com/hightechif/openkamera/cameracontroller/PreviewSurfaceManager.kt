/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreviewSurfaceManager @Inject constructor() {

    private val _previewSurfaceFlow = MutableStateFlow<Surface?>(null)
    val previewSurfaceFlow: StateFlow<Surface?> = _previewSurfaceFlow.asStateFlow()

    @Synchronized
    fun setSurface(surface: Surface?) {
        _previewSurfaceFlow.value = surface
    }

    @Synchronized
    fun clearSurface() {
        _previewSurfaceFlow.value = null
    }

    val currentSurface: Surface?
        get() = _previewSurfaceFlow.value

    val isSurfaceAvailable: Boolean
        get() = _previewSurfaceFlow.value != null && _previewSurfaceFlow.value?.isValid == true
}
