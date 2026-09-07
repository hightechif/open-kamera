/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.activity

import android.view.KeyEvent
import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.preview.Preview
import com.hightechif.openkamera.ui.MainUI
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyEventHandlerTest {

    private lateinit var mockActivity: MainActivity
    private lateinit var mockPreview: Preview
    private lateinit var mockMainUi: MainUI
    private lateinit var mockCameraViewModel: com.hightechif.openkamera.ui.CameraViewModel
    private lateinit var keyEventHandler: KeyEventHandler

    @Before
    fun setUp() {
        mockActivity = mockk(relaxed = true)
        mockPreview = mockk(relaxed = true)
        mockMainUi = mockk(relaxed = true)
        mockCameraViewModel = mockk(relaxed = true)

        every { mockActivity.preview } returns mockPreview
        every { mockActivity.mainUI } returns mockMainUi
        every { mockActivity.cameraViewModel } returns mockCameraViewModel
        every { mockActivity.isCameraInBackground } returns false

        keyEventHandler = KeyEventHandler(mockActivity)
    }

    @Test
    fun cameraInBackground_ignoresKeyDown() {
        every { mockActivity.isCameraInBackground } returns true
        val mockEvent = mockk<KeyEvent>(relaxed = true)

        val handled = keyEventHandler.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, mockEvent)
        assertFalse(handled)
    }

    @Test
    fun menuKey_triggersOpenSettings() {
        val mockEvent = mockk<KeyEvent>(relaxed = true)
        val handled = keyEventHandler.handleKeyEventInternal(KeyEvent.KEYCODE_MENU, mockEvent)

        assertTrue(handled)
        verify { mockActivity.openSettings() }
    }

    @Test
    fun cameraKey_repeatZero_triggersTakePicture() {
        val mockEvent = mockk<KeyEvent>(relaxed = true)
        every { mockEvent.repeatCount } returns 0

        val handled = keyEventHandler.handleKeyEventInternal(KeyEvent.KEYCODE_CAMERA, mockEvent)

        assertTrue(handled)
        verify { mockActivity.takePicture(false) }
    }

    @Test
    fun focusKey_initiatesAutoFocus() {
        val mockEvent = mockk<KeyEvent>(relaxed = true)
        every { mockEvent.downTime } returns 1000L
        every { mockEvent.eventTime } returns 1000L
        every { mockPreview.isFocusWaiting } returns false

        val handled = keyEventHandler.handleKeyEventInternal(KeyEvent.KEYCODE_FOCUS, mockEvent)

        assertTrue(handled)
        verify { mockPreview.requestAutoFocus() }
    }

    @Test
    fun headsetHook_triggersTakePhoto() {
        val mockEvent = mockk<KeyEvent>(relaxed = true)
        val handled = keyEventHandler.handleKeyEventInternal(KeyEvent.KEYCODE_HEADSETHOOK, mockEvent)

        assertTrue(handled)
        verify { mockActivity.takePicture(false) }
    }

    @Test
    fun mediaPlayPause_triggersTakePhoto() {
        val mockEvent = mockk<KeyEvent>(relaxed = true)
        val handled = keyEventHandler.handleKeyEventInternal(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, mockEvent)

        assertTrue(handled)
        verify { mockActivity.takePicture(false) }
    }

    @Test
    fun isMediaKey_correctlyIdentifiesMediaKeys() {
        assertTrue(keyEventHandler.isMediaKey(KeyEvent.KEYCODE_HEADSETHOOK))
        assertTrue(keyEventHandler.isMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(keyEventHandler.isMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertTrue(keyEventHandler.isMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(keyEventHandler.isMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertTrue(keyEventHandler.isMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(keyEventHandler.isMediaKey(KeyEvent.KEYCODE_MEDIA_STOP))
        assertFalse(keyEventHandler.isMediaKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(keyEventHandler.isMediaKey(KeyEvent.KEYCODE_CAMERA))
    }

    @Test
    fun isVolumeKey_correctlyIdentifiesVolumeKeys() {
        assertTrue(keyEventHandler.isVolumeKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(keyEventHandler.isVolumeKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse(keyEventHandler.isVolumeKey(KeyEvent.KEYCODE_CAMERA))
        assertFalse(keyEventHandler.isVolumeKey(KeyEvent.KEYCODE_HEADSETHOOK))
    }

    @Test
    fun onKeyUp_resetsVolumeState() {
        val mockEvent = mockk<KeyEvent>(relaxed = true)
        val handled = keyEventHandler.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, mockEvent)
        assertFalse(handled)
    }
}
