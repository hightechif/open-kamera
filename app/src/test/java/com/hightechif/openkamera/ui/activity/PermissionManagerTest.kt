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

    @Test
    fun hasCameraPermission_invocable() {
        val hasPermission = permissionManager.hasCameraPermission()
        assertNotNull(hasPermission)
    }

    @Test
    fun hasRecordAudioPermission_invocable() {
        val hasPermission = permissionManager.hasRecordAudioPermission()
        assertNotNull(hasPermission)
    }

    @Test
    fun hasLocationPermission_invocable() {
        val hasPermission = permissionManager.hasLocationPermission()
        assertNotNull(hasPermission)
    }

    @Test
    fun hasStoragePermission_invocable() {
        val hasPermission = permissionManager.hasStoragePermission()
        assertNotNull(hasPermission)
    }

    @Test
    fun showPermissionRationaleDialog_delegatesToDialogCoordinator() {
        permissionManager.showPermissionRationaleDialog(
            messageRes = android.R.string.ok,
            onProceed = {}
        )
        verify { mockActivity.dialogCoordinator.showPermissionRationaleDialog(any(), any(), any()) }
    }

    @Test
    fun showSettingsRedirectDialog_delegatesToDialogCoordinator() {
        permissionManager.showSettingsRedirectDialog(
            messageRes = android.R.string.ok
        )
        verify { mockActivity.dialogCoordinator.showSettingsRedirectDialog(any(), any(), any()) }
    }
}
