package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.UserActionDistance
import com.android.systemui.bouncer.ui.composable.Bouncer

const val FROM_LOCK_SCREEN_TO_BOUNCER_FADE_FRACTION = 0.5f
const val FROM_LOCK_SCREEN_TO_BOUNCER_SWIPE_DISTANCE_FRACTION = 0.5f

fun TransitionBuilder.lockscreenToBouncerTransition() {
    spec = tween(durationMillis = 500)

    distance = UserActionDistance { fromSceneSize, _ ->
        fromSceneSize.height * FROM_LOCK_SCREEN_TO_BOUNCER_SWIPE_DISTANCE_FRACTION
    }

    translate(Bouncer.Elements.Content, y = 300.dp)
    fractionRange(end = FROM_LOCK_SCREEN_TO_BOUNCER_FADE_FRACTION) {
        fade(Bouncer.Elements.Background)
    }
    fractionRange(start = FROM_LOCK_SCREEN_TO_BOUNCER_FADE_FRACTION) {
        fade(Bouncer.Elements.Content)
    }
}

fun TransitionBuilder.bouncerToLockscreenPreview() {
    fractionRange(easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)) {
        scaleDraw(Bouncer.Elements.Content, scaleY = 0.8f, scaleX = 0.8f)
    }
}
