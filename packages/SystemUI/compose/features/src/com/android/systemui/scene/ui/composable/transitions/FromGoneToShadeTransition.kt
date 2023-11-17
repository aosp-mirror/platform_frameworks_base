package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.scene.ui.composable.Shade

fun TransitionBuilder.goneToShadeTransition() {
    spec = tween(durationMillis = 500)

    translate(Shade.rootElementKey, Edge.Top, true)
    fade(Notifications.Elements.NotificationScrim)
}
