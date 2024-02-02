package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.bouncer.ui.composable.Bouncer

fun TransitionBuilder.lockscreenToBouncerTransition() {
    spec = tween(durationMillis = 500)

    translate(Bouncer.Elements.Content, y = 300.dp)
    fractionRange(end = 0.5f) { fade(Bouncer.Elements.Background) }
    fractionRange(start = 0.5f) { fade(Bouncer.Elements.Content) }
}
