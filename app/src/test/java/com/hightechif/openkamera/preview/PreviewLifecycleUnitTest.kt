/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preview

import com.hightechif.openkamera.MainActivity
import kotlinx.coroutines.isActive
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewLifecycleUnitTest {

    @Test
    fun preview_lifecycleScopeIsActiveOnCreation() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val preview = activity.preview

        assertNotNull(preview.previewLifecycleScope)
        assertTrue(preview.previewLifecycleScope.isActive)
    }

    @Test
    fun preview_destroyCancelsLifecycleScope() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val preview = activity.preview

        assertTrue(preview.previewLifecycleScope.isActive)
        preview.onDestroy()
        org.junit.Assert.assertFalse(preview.previewLifecycleScope.isActive)
    }
}
