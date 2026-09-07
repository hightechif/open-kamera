/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.audio

import android.content.Context
import com.hightechif.openkamera.R
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AudioControllerUnitTest {

    private lateinit var mockContext: Context
    private lateinit var mockSoundPoolManager: SoundPoolManager
    private lateinit var audioController: AudioControllerImpl

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockSoundPoolManager = mockk(relaxed = true)
        audioController = AudioControllerImpl(
            context = mockContext,
            ioDispatcher = Dispatchers.Unconfined
        )
        audioController.soundPoolManager = mockSoundPoolManager
    }

    @Test
    fun playTimerBeep_playsBeepSoundResource() {
        audioController.playTimerBeep()
        verify(exactly = 1) { mockSoundPoolManager.playSound(R.raw.mybeep) }
    }

    @Test
    fun playTimerAlert_playsHighBeepSoundResource() {
        audioController.playTimerAlert()
        verify(exactly = 1) { mockSoundPoolManager.playSound(R.raw.mybeep_hi) }
    }

    @Test
    fun playShutterSound_doesNotCrashWhenUninitialized() {
        audioController.playShutterSound()
    }

    @Test
    fun stopAudioTriggerListener_runsSafelyWhenNoListenerActive() {
        audioController.stopAudioTriggerListener()
    }

    @Test
    fun release_releasesSoundPoolManagerAndCleansUp() {
        assertNotNull(audioController.soundPoolManager)
        audioController.release()
        verify(exactly = 1) { mockSoundPoolManager.releaseSound() }
        assertNull(audioController.soundPoolManager)
    }
}
