/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.remotecontrol

import app.cash.turbine.test
import com.hightechif.openkamera.domain.engine.RemoteInputType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteInputManagerTest {

    private lateinit var remoteInputManager: RemoteInputManagerImpl

    @Before
    fun setUp() {
        remoteInputManager = RemoteInputManagerImpl()
    }

    @Test
    fun dispatchEvent_whenNotListening_returnsFalse() {
        val result = remoteInputManager.dispatchInputEvent(RemoteInputType.SHUTTER_BUTTON)
        assertFalse(result)
    }

    @Test
    fun dispatchEvent_whenListening_emitsToFlow() = runTest {
        remoteInputManager.startListening()

        remoteInputManager.remoteInputEventFlow.test {
            val sent = remoteInputManager.dispatchInputEvent(RemoteInputType.SHUTTER_BUTTON)
            assertTrue(sent)
            assertEquals(RemoteInputType.SHUTTER_BUTTON, awaitItem())

            remoteInputManager.dispatchInputEvent(RemoteInputType.SWITCH_CAMERA)
            assertEquals(RemoteInputType.SWITCH_CAMERA, awaitItem())

            remoteInputManager.dispatchInputEvent(RemoteInputType.ZOOM_IN)
            assertEquals(RemoteInputType.ZOOM_IN, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun stopListening_stopsEventDispatching() {
        remoteInputManager.startListening()
        remoteInputManager.stopListening()

        val result = remoteInputManager.dispatchInputEvent(RemoteInputType.FOCUS_BUTTON)
        assertFalse(result)
    }

    @Test
    fun onBleConnectionStateChanged_updatesConnectionStateFlow() = runTest {
        remoteInputManager.connectionStateFlow.test {
            assertEquals(com.hightechif.openkamera.domain.engine.BleConnectionState.Disconnected, awaitItem())

            remoteInputManager.onBleConnectionStateChanged(com.hightechif.openkamera.domain.engine.BleConnectionState.Connecting)
            assertEquals(com.hightechif.openkamera.domain.engine.BleConnectionState.Connecting, awaitItem())

            remoteInputManager.onBleConnectionStateChanged(com.hightechif.openkamera.domain.engine.BleConnectionState.Connected)
            assertEquals(com.hightechif.openkamera.domain.engine.BleConnectionState.Connected, awaitItem())

            remoteInputManager.onBleConnectionStateChanged(com.hightechif.openkamera.domain.engine.BleConnectionState.Error("Timeout"))
            assertEquals(com.hightechif.openkamera.domain.engine.BleConnectionState.Error("Timeout"), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onRemoteCommandReceived_dispatchesMappedEvents() = runTest {
        remoteInputManager.startListening()

        remoteInputManager.remoteInputEventFlow.test {
            remoteInputManager.onRemoteCommandReceived(BluetoothLeService.COMMAND_SHUTTER)
            assertEquals(RemoteInputType.SHUTTER_BUTTON, awaitItem())

            remoteInputManager.onRemoteCommandReceived(BluetoothLeService.COMMAND_UP)
            assertEquals(RemoteInputType.ZOOM_IN, awaitItem())

            remoteInputManager.onRemoteCommandReceived(BluetoothLeService.COMMAND_DOWN)
            assertEquals(RemoteInputType.ZOOM_OUT, awaitItem())

            remoteInputManager.onRemoteCommandReceived(BluetoothLeService.COMMAND_AFMF)
            assertEquals(RemoteInputType.FOCUS_BUTTON, awaitItem())

            remoteInputManager.onRemoteCommandReceived(BluetoothLeService.COMMAND_MODE)
            assertEquals(RemoteInputType.SWITCH_CAMERA, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onRemoteCommandReceived_unknownCommand_ignored() = runTest {
        remoteInputManager.startListening()

        remoteInputManager.remoteInputEventFlow.test {
            remoteInputManager.onRemoteCommandReceived(9999)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
