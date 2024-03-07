package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.shade.ui.composable.Shade

fun TransitionBuilder.lockscreenToShadeTransition() {
    spec = tween(durationMillis = 500)

    translate(Shade.Elements.Scrim, Edge.Top, startsOutsideLayoutBounds = false)
    fractionRange(end = 0.5f) {
        fade(Shade.Elements.ScrimBackground)
        translate(
            QuickSettings.Elements.CollapsedGrid,
            Edge.Top,
            startsOutsideLayoutBounds = false,
        )
    }
    fractionRange(start = 0.5f) { fade(Notifications.Elements.NotificationScrim) }
}
