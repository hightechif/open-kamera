/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.remotecontrol

import com.hightechif.openkamera.domain.engine.IRemoteInputManager
import com.hightechif.openkamera.domain.engine.RemoteInputType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private var isListening = false

    override fun startListening() {
        isListening = true
    }

    override fun stopListening() {
        isListening = false
    }

    fun dispatchInputEvent(type: RemoteInputType): Boolean {
        if (!isListening) return false
        return _remoteInputEventFlow.tryEmit(type)
    }
}
