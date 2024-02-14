package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.shade.ui.composable.Shade
import kotlin.time.Duration.Companion.milliseconds

fun TransitionBuilder.lockscreenToShadeTransition(
    durationScale: Double = 1.0,
) {
    spec = tween(durationMillis = DefaultDuration.times(durationScale).inWholeMilliseconds.toInt())

    fractionRange(end = 0.5f) {
        fade(Shade.Elements.BackgroundScrim)
        translate(
            QuickSettings.Elements.CollapsedGrid,
            Edge.Top,
            startsOutsideLayoutBounds = false,
        )
    }
    fractionRange(start = 0.5f) { fade(Notifications.Elements.NotificationScrim) }
}

private val DefaultDuration = 500.milliseconds
