package com.android.systemui.biometrics

import android.graphics.Rect
import android.view.Surface
import android.view.Surface.Rotation

/**
 * Collection of parameters that define an under-display fingerprint sensor (UDFPS) overlay.
 *
 * @property sensorBounds coordinates of the bounding box around the sensor, in natural orientation,
 *     in pixels, for the current resolution.
 * @property naturalDisplayWidth width of the physical display, in natural orientation, in pixels,
 *     for the current resolution.
 * @property naturalDisplayHeight height of the physical display, in natural orientation, in pixels,
 *     for the current resolution.
 * @property scaleFactor ratio of a dimension in the current resolution to the corresponding
 *     dimension in the native resolution.
 * @property rotation current rotation of the display.
 */

data class UdfpsOverlayParams(
    val sensorBounds: Rect = Rect(),
    val naturalDisplayWidth: Int = 0,
    val naturalDisplayHeight: Int = 0,
    val scaleFactor: Float = 1f,
    @Rotation val rotation: Int = Surface.ROTATION_0
) {
    /** See [android.view.DisplayInfo.logicalWidth] */
    val logicalDisplayWidth
        get() = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            naturalDisplayHeight
        } else {
            naturalDisplayWidth
        }

    /** See [android.view.DisplayInfo.logicalHeight] */
    val logicalDisplayHeight
        get() = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            naturalDisplayWidth
        } else {
            naturalDisplayHeight
        }
}