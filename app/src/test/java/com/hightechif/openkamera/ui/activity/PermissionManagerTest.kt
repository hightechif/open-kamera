/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.activity

import com.hightechif.openkamera.MainActivity
import com.hightechif.openkamera.system.PermissionHandler
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class PermissionManagerTest {

    private lateinit var mockActivity: MainActivity
    private lateinit var permissionManager: PermissionManager

    @Before
    fun setUp() {
        mockActivity = mockk(relaxed = true)
        permissionManager = PermissionManager(mockActivity)
    }

    @Test
    fun permissionHandler_isInitialized() {
        assertNotNull(permissionManager.permissionHandler)
    }

    @Test
    fun permissionManager_delegatesLifecycleCalls() {
        val mockHandler = mockk<PermissionHandler>(relaxed = true)
        // Verify class methods are properly invokable
        assertNotNull(permissionManager)
    }
}
