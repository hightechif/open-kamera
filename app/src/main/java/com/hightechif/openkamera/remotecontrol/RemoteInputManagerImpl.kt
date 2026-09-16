/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.remotecontrol

import com.hightechif.openkamera.domain.engine.BleConnectionState
import com.hightechif.openkamera.domain.engine.IRemoteInputManager
import com.hightechif.openkamera.domain.engine.RemoteInputType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteInputManagerImpl @Inject constructor() : IRemoteInputManager {

    private val _remoteInputEventFlow = MutableSharedFlow<RemoteInputType>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val remoteInputEventFlow: Flow<RemoteInputType> = _remoteInputEventFlow.asSharedFlow()

    private val _connectionStateFlow =
        MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    override val connectionStateFlow: StateFlow<BleConnectionState> =
        _connectionStateFlow.asStateFlow()

    private var isListening = false

    override fun startListening() {
        isListening = true
    }

    override fun stopListening() {
        isListening = false
    }

    override fun onBleConnectionStateChanged(state: BleConnectionState) {
        _connectionStateFlow.value = state
    }

    override fun onRemoteCommandReceived(command: Int) {
        val type = when (command) {
            BluetoothLeService.COMMAND_SHUTTER -> RemoteInputType.SHUTTER_BUTTON
            BluetoothLeService.COMMAND_UP -> RemoteInputType.ZOOM_IN
            BluetoothLeService.COMMAND_DOWN -> RemoteInputType.ZOOM_OUT
            BluetoothLeService.COMMAND_AFMF -> RemoteInputType.FOCUS_BUTTON
            BluetoothLeService.COMMAND_MODE -> RemoteInputType.SWITCH_CAMERA
            else -> null
        }
        if (type != null) {
            dispatchInputEvent(type)
        }
    }

    override fun dispatchInputEvent(type: RemoteInputType): Boolean {
        if (!isListening) return false
        return _remoteInputEventFlow.tryEmit(type)
    }
}
