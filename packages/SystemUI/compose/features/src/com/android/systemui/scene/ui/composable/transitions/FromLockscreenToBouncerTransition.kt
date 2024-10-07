package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.CubicBezierEasing
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.bouncer.ui.composable.Bouncer

fun TransitionBuilder.lockscreenToBouncerTransition() {
    toBouncerTransition()
}

fun TransitionBuilder.bouncerToLockscreenPreview() {
    fractionRange(easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)) {
        scaleDraw(Bouncer.Elements.Content, scaleY = 0.8f, scaleX = 0.8f)
    }
}
