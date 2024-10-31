package com.android.systemui.scene.ui.composable.transitions

import com.android.compose.animation.Easings
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.bouncer.ui.composable.Bouncer

fun TransitionBuilder.lockscreenToBouncerTransition() {
    toBouncerTransition()
}

fun TransitionBuilder.bouncerToLockscreenPreview() {
    fractionRange(easing = Easings.PredictiveBack) {
        scaleDraw(Bouncer.Elements.Content, scaleY = 0.8f, scaleX = 0.8f)
    }
}
