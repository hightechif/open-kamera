/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPersistenceManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testWriteBytesToFile() {
        val manager = MediaPersistenceManager(RuntimeEnvironment.getApplication())
        val file = File(tempFolder.root, "test_image.jpg")
        val sampleBytes = "test_image_payload".toByteArray()

        val success = manager.writeBytes(sampleBytes, file, null)
        assertTrue(success)
        assertTrue(file.exists())
        assertArrayEquals(sampleBytes, file.readBytes())
    }

    @Test
    fun testFinalizePendingMediaStoreUriDoesNotThrow() {
        val manager = MediaPersistenceManager(RuntimeEnvironment.getApplication())
        // Should handle null or unresolvable URI gracefully without throwing
        manager.finalizePendingMediaStoreUri(null)
    }
}
