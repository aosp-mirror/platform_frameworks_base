package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.scene.shared.model.Scenes

fun TransitionBuilder.bouncerToGoneTransition() {
    spec = tween(durationMillis = 500)

    fade(Scenes.Bouncer.rootElementKey)
}
