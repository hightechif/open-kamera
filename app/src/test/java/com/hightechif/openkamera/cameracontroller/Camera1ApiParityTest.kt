/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.cameracontroller

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Camera1 fallback against silently drifting from [CameraController2].
 *
 * Adding an *abstract* member to [CameraController] already breaks the build until [CameraController1]
 * implements it. This test covers the other case: a *non-abstract* (open) [CameraController] member that
 * [CameraController2] overrides but [CameraController1] does not. Each such member must either be
 * overridden by Camera1 or be listed in [ALLOWED_CAMERA2_ONLY] with a reason.
 */
class Camera1ApiParityTest {

    private fun Method.signature() = name + parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }

    private fun overridable(cls: Class<*>): Set<String> = cls.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic && !it.isBridge }
        .map { it.signature() }
        .toSet()

    @Test
    fun cameraController1_overridesEveryOpenMemberThatCameraController2Overrides() {
        val baseOpen = CameraController::class.java.declaredMethods
            .filter {
                Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                    !Modifier.isAbstract(it.modifiers) && !it.isSynthetic && !it.isBridge
            }
            .map { it.signature() }
            .toSet()

        val camera2 = overridable(CameraController2::class.java)
        val camera1 = overridable(CameraController1::class.java)

        val missing = baseOpen
            .filter { it in camera2 && it !in camera1 }
            .filter { sig -> ALLOWED_CAMERA2_ONLY.keys.none { sig.startsWith("$it(") } }
            .sorted()

        assertTrue(
            "CameraController2 overrides these CameraController members but CameraController1 does not. " +
                "Override them in CameraController1, or add them to ALLOWED_CAMERA2_ONLY with a reason:\n" +
                missing.joinToString("\n"),
            missing.isEmpty()
        )
    }

    @Test
    fun allowList_hasNoStaleEntries() {
        val baseOpen = CameraController::class.java.declaredMethods.map { it.name }.toSet()
        val stale = ALLOWED_CAMERA2_ONLY.keys.filter { it !in baseOpen }
        assertTrue("ALLOWED_CAMERA2_ONLY lists members that no longer exist in CameraController: $stale", stale.isEmpty())
    }

    companion object {
        private const val NO_CAPTURE_RESULTS =
            "Camera1 has no per-frame CaptureResult; the base class default ('not available') is correct"

        /** Member name (any overload) → why Camera1 legitimately does not override it. */
        val ALLOWED_CAMERA2_ONLY: Map<String, String> = listOf(
            "captureResultIsAEScanning",
            "captureResultHasWhiteBalanceTemperature", "captureResultWhiteBalanceTemperature",
            "captureResultHasIso", "captureResultIso",
            "captureResultHasExposureTime", "captureResultExposureTime",
            "captureResultHasFrameDuration", "captureResultFrameDuration",
            "captureResultHasFocusDistance", "captureResultFocusDistance",
            "captureResultHasAperture", "captureResultAperture"
        ).associateWith { NO_CAPTURE_RESULTS } + mapOf(
            "getUseCamera2FakeFlash" to "Camera2-only concept (torch-based fake flash); Camera1 uses real flash modes",
            "setUseCamera2FakeFlash" to "Camera2-only concept (torch-based fake flash); Camera1 uses real flash modes",
            "needsFlash" to "Camera1 cannot predict whether the flash will fire; base class documents 'false if not known'",
            "needsFrontScreenFlash" to "Camera1 handles front-screen flash inside takePicture (fixed delay), and has no query for it",
            "shouldCoverPreview" to "Cover-preview mechanism is CameraController2-only (see CameraController KDoc)",
            "resetCoverPreview" to "Cover-preview mechanism is CameraController2-only (see CameraController KDoc)",
            "updatePreviewTexture" to "Camera1 previews on a SurfaceView (Preview selects MySurfaceView), not a TextureView"
        )
    }
}
