/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.remotecontrol

import com.hightechif.openkamera.domain.engine.BleConnectionState
import com.hightechif.openkamera.domain.engine.IRemoteInputManager
import com.hightechif.openkamera.domain.engine.RemoteButton
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

    private val _remoteInputEventFlow = MutableSharedFlow<RemoteButton>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val remoteInputEventFlow: Flow<RemoteButton> = _remoteInputEventFlow.asSharedFlow()

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
        val button = when (command) {
            BluetoothLeService.COMMAND_SHUTTER -> RemoteButton.SHUTTER
            BluetoothLeService.COMMAND_MODE -> RemoteButton.MODE
            BluetoothLeService.COMMAND_MENU -> RemoteButton.MENU
            BluetoothLeService.COMMAND_UP -> RemoteButton.UP
            BluetoothLeService.COMMAND_DOWN -> RemoteButton.DOWN
            BluetoothLeService.COMMAND_AFMF -> RemoteButton.AFMF
            else -> null
        }
        if (button != null) {
            dispatchInputEvent(button)
        }
    }

    override fun dispatchInputEvent(button: RemoteButton): Boolean {
        if (!isListening) return false
        return _remoteInputEventFlow.tryEmit(button)
    }
}
