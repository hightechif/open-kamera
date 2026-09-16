/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import android.graphics.Bitmap
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EffectOverlayRendererTest {

    private lateinit var renderer: EffectOverlayRenderer
    private lateinit var mockBitmap: Bitmap

    @Before
    fun setUp() {
        renderer = EffectOverlayRenderer()
        mockBitmap = mockk<Bitmap>(relaxed = true)
    }

    @Test
    fun initialState_ghostImageDisabledAndNotActive() {
        assertFalse("Ghost image should be disabled by default", renderer.isGhostImageAllowed)
        assertEquals("Default pref should be off", "preference_ghost_image_off", renderer.currentGhostImagePref)
        assertFalse("Ghost last image should not be active", renderer.isGhostLastImageActive)
        assertFalse("Ghost selected image should not be active", renderer.isGhostSelectedImageActive)
        assertFalse("Show last image should be false by default", renderer.isShowingLastImage)
        assertFalse("Thumbnail overlay should not render", renderer.shouldRenderThumbnailOverlay)
    }

    @Test
    fun thumbnailPresent_withGhostImageOff_doesNotRenderOverlay() {
        // Given thumbnail is set (after taking a photo) and ghost image is allowed in UI callback
        renderer.setLastThumbnail(mockBitmap)
        renderer.allowGhostImage()
        renderer.setGhostImagePref("preference_ghost_image_off")

        // Then thumbnail overlay MUST NOT render because preference is off
        assertFalse(
            "Thumbnail overlay must NOT render when ghost image pref is off",
            renderer.shouldRenderThumbnailOverlay
        )
        assertFalse(renderer.isGhostLastImageActive)
    }

    @Test
    fun thumbnailPresent_withGhostImageLast_rendersOverlayWhenAllowed() {
        renderer.setLastThumbnail(mockBitmap)
        renderer.setGhostImagePref("preference_ghost_image_last")
        renderer.allowGhostImage()

        assertTrue("Ghost last image should be active", renderer.isGhostLastImageActive)
        assertTrue("Thumbnail overlay should render", renderer.shouldRenderThumbnailOverlay)
    }

    @Test
    fun thumbnailPresent_clearGhostImage_deactivatesOverlay() {
        renderer.setLastThumbnail(mockBitmap)
        renderer.setGhostImagePref("preference_ghost_image_last")
        renderer.allowGhostImage()
        assertTrue(renderer.shouldRenderThumbnailOverlay)

        // When ghost image is cleared
        renderer.clearGhostImage()

        assertFalse("Ghost image is no longer allowed", renderer.isGhostImageAllowed)
        assertFalse("Ghost last image is not active", renderer.isGhostLastImageActive)
        assertFalse("Thumbnail overlay must not render", renderer.shouldRenderThumbnailOverlay)
    }

    @Test
    fun showLastImage_pausePreview_rendersOverlayRegardlessOfGhostPref() {
        renderer.setLastThumbnail(mockBitmap)
        renderer.setGhostImagePref("preference_ghost_image_off")

        // Initially not rendered
        assertFalse(renderer.shouldRenderThumbnailOverlay)

        // Pause preview activated
        renderer.showLastImage()
        assertTrue("Showing last image", renderer.isShowingLastImage)
        assertTrue("Thumbnail overlay renders for pause preview", renderer.shouldRenderThumbnailOverlay)

        // Pause preview dismissed
        renderer.clearLastImage()
        assertFalse("No longer showing last image", renderer.isShowingLastImage)
        assertFalse("Thumbnail overlay stops rendering", renderer.shouldRenderThumbnailOverlay)
    }

    @Test
    fun ghostSelectedImage_activatesOnlyWhenPreferenceMatchesAndBitmapPresent() {
        renderer.setGhostImagePref("preference_ghost_image_off")
        renderer.setGhostSelectedImageBitmap(mockBitmap)
        assertFalse(
            "Selected image ghost should not be active when pref is off",
            renderer.isGhostSelectedImageActive
        )

        renderer.setGhostImagePref("preference_ghost_image_selected")
        assertTrue(
            "Selected image ghost should be active when pref is selected and bitmap present",
            renderer.isGhostSelectedImageActive
        )

        renderer.setGhostSelectedImageBitmap(null)
        assertFalse(
            "Selected image ghost should not be active when bitmap is null",
            renderer.isGhostSelectedImageActive
        )
    }
}
