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
    fun playTimerAlert()
    fun startAudioTriggerListener(threshold: Int = 1500, onAudioLevelThresholdMet: () -> Unit)
    fun startAudioTriggerListener(onAudioLevelThresholdMet: () -> Unit) =
        startAudioTriggerListener(1500, onAudioLevelThresholdMet)
    fun stopAudioTriggerListener()
    fun release()
}
