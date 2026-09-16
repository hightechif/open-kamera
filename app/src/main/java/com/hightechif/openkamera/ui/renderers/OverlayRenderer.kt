/*
 * OpenKamera - Modern Kotlin port of Open Camera
 *
 * Original Java implementation: Copyright (C) 2013–2026 Mark Harman
 * Kotlin conversion & development: Copyright (C) 2026 Ridhan Fadhilah
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */
package com.hightechif.openkamera.ui.renderers

import android.graphics.Canvas

/**
 * Common contract for all modular HUD and viewfinder overlay sub-renderers.
 *
 * Implementations are required to avoid runtime heap allocations inside [draw]
 * by pre-allocating Paint, Path, Matrix, and Rect objects.
 */
interface OverlayRenderer {

    /**
     * Refresh cached preferences, styles, or configuration states when settings change.
     */
    fun updateSettings() {}

    /**
     * Draw overlay graphics onto the viewfinder canvas.
     *
     * @param canvas The target viewfinder canvas.
     * @param context Shared execution context containing dimensions, device rotation, and metrics.
     * @param timeMs Current frame timestamp in milliseconds for animations.
     */
    fun draw(canvas: Canvas, context: DrawPreviewContext, timeMs: Long)

    /**
     * Release any retained hardware or native resources (bitmaps, executors, etc.).
     */
    fun onDestroy() {}
}
