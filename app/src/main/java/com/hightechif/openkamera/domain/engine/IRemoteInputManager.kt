/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.engine

import kotlinx.coroutines.flow.Flow

enum class RemoteInputType {
    SHUTTER_BUTTON,
    FOCUS_BUTTON,
    ZOOM_IN,
    ZOOM_OUT,
    SWITCH_CAMERA
}

interface IRemoteInputManager {
    val remoteInputEventFlow: Flow<RemoteInputType>

    fun startListening()
    fun stopListening()
}
