package com.android.systemui.biometrics.shared.model

import android.view.Surface

/** Shadows [Surface.Rotation] for kotlin use within SysUI. */
enum class DisplayRotation {
    ROTATION_0,
    ROTATION_90,
    ROTATION_180,
    ROTATION_270,
}

fun DisplayRotation.isDefaultOrientation() =
    this == DisplayRotation.ROTATION_0 || this == DisplayRotation.ROTATION_180

/** Converts [Surface.Rotation] to corresponding [DisplayRotation] */
fun Int.toDisplayRotation(): DisplayRotation =
    when (this) {
        Surface.ROTATION_0 -> DisplayRotation.ROTATION_0
        Surface.ROTATION_90 -> DisplayRotation.ROTATION_90
        Surface.ROTATION_180 -> DisplayRotation.ROTATION_180
        Surface.ROTATION_270 -> DisplayRotation.ROTATION_270
        else -> throw IllegalArgumentException("Invalid DisplayRotation value: $this")
    }

/** Converts [DisplayRotation] to corresponding [Surface.Rotation] */
fun DisplayRotation.toRotation(): Int =
    when (this) {
        DisplayRotation.ROTATION_0 -> Surface.ROTATION_0
        DisplayRotation.ROTATION_90 -> Surface.ROTATION_90
        DisplayRotation.ROTATION_180 -> Surface.ROTATION_180
        DisplayRotation.ROTATION_270 -> Surface.ROTATION_270
    }
