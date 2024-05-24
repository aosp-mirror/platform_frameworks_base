package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.scene.shared.model.Scenes

fun TransitionBuilder.lockscreenToQuickSettingsTransition() {
    spec = tween(durationMillis = 500)

    translate(Scenes.QuickSettings.rootElementKey, Edge.Top, true)
}
