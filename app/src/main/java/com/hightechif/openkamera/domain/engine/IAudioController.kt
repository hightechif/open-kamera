/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.domain.engine

interface IAudioController {
    fun playShutterSound()
    fun playTimerBeep()
    fun startAudioTriggerListener(onAudioLevelThresholdMet: () -> Unit)
    fun stopAudioTriggerListener()
    fun release()
}
