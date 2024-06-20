package com.android.systemui.scene.ui.composable.transitions

import com.android.compose.animation.scene.TransitionBuilder

fun TransitionBuilder.lockscreenToShadeTransition(
    durationScale: Double = 1.0,
) {
    toShadeTransition(durationScale = durationScale)
}
