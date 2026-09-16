/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview.video

import android.media.MediaRecorder
import com.hightechif.openkamera.preview.VideoProfile
import android.os.Build
import io.mockk.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class VideoRecorderEngineTest {

    private lateinit var mockMediaRecorder: MediaRecorder
    private lateinit var engine: VideoRecorderEngine

    @Before
    fun setUp() {
        mockMediaRecorder = mockk(relaxed = true)
        engine = VideoRecorderEngine(recorderFactory = { mockMediaRecorder })
    }

    @Test
    fun `test initial state is not recording and not prepared`() {
        assertFalse(engine.isPrepared)
        assertFalse(engine.isRecording)
        assertFalse(engine.isPaused)
        assertEquals(0, engine.maxAmplitude)
    }

    @Test
    fun `test prepare configures MediaRecorder and sets isPrepared`() {
        val profile = VideoProfile().apply {
            recordAudio = false
            videoSource = MediaRecorder.VideoSource.SURFACE
            fileFormat = MediaRecorder.OutputFormat.MPEG_4
            videoFrameRate = 30
            videoFrameWidth = 1920
            videoFrameHeight = 1080
            videoBitRate = 10000000
            videoCodec = MediaRecorder.VideoEncoder.H264
        }
        val tempFile = File.createTempFile("test_video", ".mp4")
        tempFile.deleteOnExit()

        var preCalled = false
        var postCalled = false

        engine.prepare(
            profile = profile,
            maxFileSize = 1000000L,
            maxDurationMs = 60000L,
            outputFile = tempFile,
            orientationHint = 90,
            prePrepareCallback = { preCalled = true },
            postPrepareCallback = { postCalled = true }
        )

        assertTrue(preCalled)
        assertTrue(postCalled)
        assertTrue(engine.isPrepared)
        verify { mockMediaRecorder.setOutputFile(tempFile.absolutePath) }
        verify { mockMediaRecorder.setOrientationHint(90) }
        verify { mockMediaRecorder.setMaxFileSize(1000000L) }
        verify { mockMediaRecorder.setMaxDuration(60000) }
        verify { mockMediaRecorder.prepare() }
    }

    @Test
    fun `test start sets isRecording to true`() {
        engine.prepare(
            profile = VideoProfile(),
            maxFileSize = 0L,
            maxDurationMs = 0L
        )
        engine.start()

        assertTrue(engine.isRecording)
        assertFalse(engine.isPaused)
        verify { mockMediaRecorder.start() }
    }

    @Test
    fun `test pause and resume state transitions`() {
        engine.prepare(
            profile = VideoProfile(),
            maxFileSize = 0L,
            maxDurationMs = 0L
        )
        engine.start()

        val pauseResult = engine.pause()
        assertTrue(pauseResult)
        assertTrue(engine.isPaused)
        verify { mockMediaRecorder.pause() }

        val resumeResult = engine.resume()
        assertTrue(resumeResult)
        assertFalse(engine.isPaused)
        verify { mockMediaRecorder.resume() }
    }

    @Test
    fun `test stop clears listeners and updates state`() {
        engine.prepare(
            profile = VideoProfile(),
            maxFileSize = 0L,
            maxDurationMs = 0L
        )
        engine.start()

        val stopSuccess = engine.stop()
        assertTrue(stopSuccess)
        assertFalse(engine.isRecording)
        assertFalse(engine.isPaused)
        verify { mockMediaRecorder.setOnErrorListener(null) }
        verify { mockMediaRecorder.setOnInfoListener(null) }
        verify { mockMediaRecorder.stop() }
    }

    @Test
    fun `test stop catches runtime exception and returns false`() {
        every { mockMediaRecorder.stop() } throws RuntimeException("stop failed")

        engine.prepare(
            profile = VideoProfile(),
            maxFileSize = 0L,
            maxDurationMs = 0L
        )
        engine.start()

        val stopSuccess = engine.stop()
        assertFalse(stopSuccess)
        assertFalse(engine.isRecording)
    }

    @Test
    fun `test maxAmplitude returns recorder amplitude when recording`() {
        every { mockMediaRecorder.maxAmplitude } returns 15000

        engine.prepare(
            profile = VideoProfile(),
            maxFileSize = 0L,
            maxDurationMs = 0L
        )
        engine.start()

        assertEquals(15000, engine.maxAmplitude)
    }

    @Test
    fun `test release clears mediaRecorder instance`() {
        engine.prepare(
            profile = VideoProfile(),
            maxFileSize = 0L,
            maxDurationMs = 0L
        )
        engine.release()

        assertNull(engine.mediaRecorder)
        assertFalse(engine.isPrepared)
        assertFalse(engine.isRecording)
        verify { mockMediaRecorder.release() }
    }
}
