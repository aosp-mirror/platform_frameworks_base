package com.android.systemui.biometrics

import android.graphics.Rect
import android.view.Surface
import android.view.Surface.Rotation

/**
 * Collection of parameters that define an under-display fingerprint sensor (UDFPS) overlay.
 *
 * [sensorBounds] coordinates of the bounding box around the sensor in natural orientation, in
 * pixels, for the current resolution.
 *
 * [overlayBounds] coordinates of the UI overlay in natural orientation, in pixels, for the current
 * resolution.
 *
 * [naturalDisplayWidth] width of the physical display in natural orientation, in pixels, for the
 * current resolution.
 *
 * [naturalDisplayHeight] height of the physical display in natural orientation, in pixels, for the
 * current resolution.
 *
 * [scaleFactor] ratio of a dimension in the current resolution to the corresponding dimension in
 * the native resolution.
 *
 * [rotation] current rotation of the display.
 */
data class UdfpsOverlayParams(
    val sensorBounds: Rect = Rect(),
    val overlayBounds: Rect = Rect(),
    val naturalDisplayWidth: Int = 0,
    val naturalDisplayHeight: Int = 0,
    val scaleFactor: Float = 1f,
    @Rotation val rotation: Int = Surface.ROTATION_0
) {

    /** Same as [sensorBounds], but in native resolution. */
    val nativeSensorBounds = Rect(sensorBounds).apply { scale(1f / scaleFactor) }

    /** See [android.view.DisplayInfo.logicalWidth] */
    val logicalDisplayWidth =
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            naturalDisplayHeight
        } else {
            naturalDisplayWidth
        }

    /** See [android.view.DisplayInfo.logicalHeight] */
    val logicalDisplayHeight =
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            naturalDisplayWidth
        } else {
            naturalDisplayHeight
        }
}
