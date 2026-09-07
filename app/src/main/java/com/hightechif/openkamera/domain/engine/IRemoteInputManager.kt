/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class RemoteInputType {
    SHUTTER_BUTTON,
    FOCUS_BUTTON,
    ZOOM_IN,
    ZOOM_OUT,
    SWITCH_CAMERA
}

sealed interface BleConnectionState {
    object Disconnected : BleConnectionState
    object Connecting : BleConnectionState
    object Connected : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

interface IRemoteInputManager {
    val remoteInputEventFlow: Flow<RemoteInputType>
    val connectionStateFlow: StateFlow<BleConnectionState>

    fun startListening()
    fun stopListening()
    fun onBleConnectionStateChanged(state: BleConnectionState)
    fun onRemoteCommandReceived(command: Int)
    fun dispatchInputEvent(type: RemoteInputType): Boolean
}
