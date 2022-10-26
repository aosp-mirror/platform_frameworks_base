package com.android.systemui.ripple

import android.graphics.Color

/**
 * A struct that holds the ripple animation configurations.
 *
 * <p>This is designed to be used only once. Create a new instance when the animation needs to
 * change, instead of modifying each parameter. This data class is pulled out to make the
 * [RippleAnimation] constructor succinct.
 */
data class RippleAnimationConfig(
    val rippleShape: RippleShader.RippleShape = RippleShader.RippleShape.CIRCLE,
    val duration: Long = 0L,
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    val maxWidth: Float = 0f,
    val maxHeight: Float = 0f,
    val pixelDensity: Float = 1f,
    val color: Int = Color.WHITE,
    val opacity: Int = RIPPLE_DEFAULT_ALPHA,
    val shouldFillRipple: Boolean = false,
    val sparkleStrength: Float = RIPPLE_SPARKLE_STRENGTH
) {
    companion object {
        const val RIPPLE_SPARKLE_STRENGTH: Float = 0.3f
        const val RIPPLE_DEFAULT_COLOR: Int = 0xffffffff.toInt()
        const val RIPPLE_DEFAULT_ALPHA: Int = 45 // full opacity is 255.
    }
}
