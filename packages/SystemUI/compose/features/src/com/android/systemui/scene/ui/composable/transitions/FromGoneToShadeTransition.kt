package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.shade.ui.composable.ShadeHeader

fun TransitionBuilder.goneToShadeTransition() {
    spec = tween(durationMillis = 500)

    fractionRange(start = .58f) { fade(ShadeHeader.Elements.CollapsedContent) }
    translate(QuickSettings.Elements.Content, Edge.Top, true)
}
