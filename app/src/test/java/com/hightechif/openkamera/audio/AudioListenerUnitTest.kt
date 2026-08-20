/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.audio

import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class AudioListenerUnitTest {

    @Test
    fun testAudioListenerCallbackInvocation() {
        val callback = mockk<AudioListener.AudioListenerCallback>(relaxed = true)
        callback.onAudio(1500)
        verify(exactly = 1) { callback.onAudio(1500) }
    }

    @Test
    fun testPcmNoiseComputation() {
        val buffer = shortArrayOf(0, 100, -200, 300, -400, 500, -600, 700)
        val nRead = buffer.size

        var averageNoise = 0
        var maxNoise = 0
        for (i in 0 until nRead) {
            val value = abs(buffer[i].toInt())
            averageNoise += value
            maxNoise = max(maxNoise, value)
        }
        averageNoise /= nRead

        assertEquals(350, averageNoise)
        assertEquals(700, maxNoise)
    }

    @Test
    fun testSilentAudioNoiseLevel() {
        val silentBuffer = ShortArray(128) { 0 }
        var averageNoise = 0
        for (i in silentBuffer.indices) {
            averageNoise += abs(silentBuffer[i].toInt())
        }
        averageNoise /= silentBuffer.size

        assertEquals(0, averageNoise)
    }

    @Test
    fun testLoudAudioThresholdTrigger() {
        val loudThreshold = 5000
        val normalSpeechBuffer = ShortArray(64) { 1200 }
        val shoutBuffer = ShortArray(64) { 8500 }

        val normalLevel = normalSpeechBuffer.map { abs(it.toInt()) }.average().toInt()
        val shoutLevel = shoutBuffer.map { abs(it.toInt()) }.average().toInt()

        assertTrue(normalLevel < loudThreshold)
        assertTrue(shoutLevel > loudThreshold)
    }
}
