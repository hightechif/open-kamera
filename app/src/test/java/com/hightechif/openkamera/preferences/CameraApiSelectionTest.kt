/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.preferences

import com.hightechif.openkamera.preferences.CameraApiSelection.Choice
import com.hightechif.openkamera.preferences.CameraApiSelection.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table test for the `camera-api-selection` rule:
 * supportsCamera2 (any camera LIMITED+) × allCamerasSupportCamera2 (every camera LIMITED+) × stored preference.
 */
class CameraApiSelectionTest {

    private val old = PreferenceKeys.CAMERA_API_PREFERENCE_OLD
    private val camera2 = PreferenceKeys.CAMERA_API_PREFERENCE_CAMERA2

    private data class Case(val any: Boolean, val all: Boolean, val preference: String?, val expected: Choice)

    // `all` implies `any`; the (any = false, all = true) combination cannot occur and is not listed.
    private val table = listOf(
        // every camera LIMITED+
        Case(any = true, all = true, preference = null, expected = Choice(true, Reason.DEFAULT)),
        Case(any = true, all = true, preference = camera2, expected = Choice(true, Reason.USER_PREFERENCE)),
        Case(any = true, all = true, preference = old, expected = Choice(false, Reason.USER_PREFERENCE)),
        // mixed: some LIMITED+, some LEGACY
        Case(any = true, all = false, preference = null, expected = Choice(false, Reason.LEGACY_CAMERA_PRESENT)),
        Case(any = true, all = false, preference = camera2, expected = Choice(true, Reason.USER_PREFERENCE)),
        Case(any = true, all = false, preference = old, expected = Choice(false, Reason.USER_PREFERENCE)),
        // every camera LEGACY (or no cameras): the preference is ignored
        Case(any = false, all = false, preference = null, expected = Choice(false, Reason.NO_LIMITED_CAMERA)),
        Case(any = false, all = false, preference = camera2, expected = Choice(false, Reason.NO_LIMITED_CAMERA)),
        Case(any = false, all = false, preference = old, expected = Choice(false, Reason.NO_LIMITED_CAMERA))
    )

    @Test
    fun selectionRule_matchesTheSpecTable() {
        table.forEach { case ->
            assertEquals(
                "any=${case.any} all=${case.all} preference=${case.preference}",
                case.expected,
                CameraApiSelection.choose(case.any, case.all, case.preference)
            )
        }
    }

    @Test
    fun mixedDevice_defaultsToCamera1_butUserCanOptIn() {
        assertFalse(CameraApiSelection.choose(true, false, null).useCamera2)
        assertTrue(CameraApiSelection.choose(true, false, camera2).useCamera2)
    }

    @Test
    fun unknownStoredValue_isTreatedAsNoChoice() {
        // e.g. a value from a future or corrupted settings file: fall back to the hardware-based default
        assertEquals(Choice(true, Reason.DEFAULT), CameraApiSelection.choose(true, true, "unexpected"))
        assertEquals(Choice(false, Reason.LEGACY_CAMERA_PRESENT), CameraApiSelection.choose(true, false, "unexpected"))
    }

    @Test
    fun describe_producesTheLoggedLine() {
        assertEquals("API=Camera2 reason=default", CameraApiSelection.describe(CameraApiSelection.choose(true, true, null)))
        assertEquals(
            "API=Camera1 reason=legacy-camera-present",
            CameraApiSelection.describe(CameraApiSelection.choose(true, false, null))
        )
        assertEquals(
            "API=Camera1 reason=no-LIMITED-camera",
            CameraApiSelection.describe(CameraApiSelection.choose(false, false, null))
        )
        assertEquals(
            "API=Camera1 reason=user-preference",
            CameraApiSelection.describe(CameraApiSelection.choose(true, true, old))
        )
    }

    @Test
    fun camera1Notice_shownOnceOnCamera1_neverOnCamera2() {
        val camera1 = CameraApiSelection.choose(true, false, null)
        val camera2Choice = CameraApiSelection.choose(true, true, null)

        assertTrue(CameraApiSelection.shouldShowCamera1Notice(camera1, alreadyShown = false))
        assertFalse(CameraApiSelection.shouldShowCamera1Notice(camera1, alreadyShown = true))
        assertFalse(CameraApiSelection.shouldShowCamera1Notice(camera2Choice, alreadyShown = false))
    }
}
