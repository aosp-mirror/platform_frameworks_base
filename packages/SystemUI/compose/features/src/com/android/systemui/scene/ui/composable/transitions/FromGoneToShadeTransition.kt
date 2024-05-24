package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.shade.ui.composable.ShadeHeader
import kotlin.time.Duration.Companion.milliseconds

fun TransitionBuilder.goneToShadeTransition(
    durationScale: Double = 1.0,
) {
    spec = tween(durationMillis = DefaultDuration.times(durationScale).inWholeMilliseconds.toInt())

    fractionRange(start = .58f) { fade(ShadeHeader.Elements.Clock) }
    fractionRange(start = .58f) { fade(ShadeHeader.Elements.CollapsedContentStart) }
    fractionRange(start = .58f) { fade(ShadeHeader.Elements.CollapsedContentEnd) }
    fractionRange(start = .58f) { fade(ShadeHeader.Elements.PrivacyChip) }
    translate(QuickSettings.Elements.Content, y = -ShadeHeader.Dimensions.CollapsedHeight * .66f)
    translate(Notifications.Elements.NotificationScrim, Edge.Top, false)
}

private val DefaultDuration = 500.milliseconds
