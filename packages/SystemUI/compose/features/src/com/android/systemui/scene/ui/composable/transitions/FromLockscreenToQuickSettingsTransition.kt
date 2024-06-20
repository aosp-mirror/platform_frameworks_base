package com.android.systemui.scene.ui.composable.transitions

import com.android.compose.animation.scene.TransitionBuilder

fun TransitionBuilder.lockscreenToQuickSettingsTransition(
    durationScale: Double = 1.0,
) {
    toQuickSettingsTransition(durationScale = durationScale)
}
