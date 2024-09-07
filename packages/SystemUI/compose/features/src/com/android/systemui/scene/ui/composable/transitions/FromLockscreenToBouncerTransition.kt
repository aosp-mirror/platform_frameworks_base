package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.bouncer.ui.composable.Bouncer

const val FROM_LOCK_SCREEN_TO_BOUNCER_FADE_FRACTION = 0.5f

fun TransitionBuilder.lockscreenToBouncerTransition() {
    spec = tween(durationMillis = 500)

    translate(Bouncer.Elements.Content, y = 300.dp)
    fractionRange(end = FROM_LOCK_SCREEN_TO_BOUNCER_FADE_FRACTION) {
        fade(Bouncer.Elements.Background)
    }
    fractionRange(start = FROM_LOCK_SCREEN_TO_BOUNCER_FADE_FRACTION) {
        fade(Bouncer.Elements.Content)
    }
}
