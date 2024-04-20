package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.qs.ui.composable.QuickSettings
import com.android.systemui.shade.ui.composable.ShadeHeader
import kotlin.time.Duration.Companion.milliseconds

fun TransitionBuilder.shadeToQuickSettingsTransition(
    durationScale: Double = 1.0,
) {
    spec = tween(durationMillis = (DefaultDuration * durationScale).inWholeMilliseconds.toInt())

    translate(Notifications.Elements.NotificationScrim, Edge.Bottom)
    timestampRange(endMillis = 83) { fade(QuickSettings.Elements.FooterActions) }

    translate(
        ShadeHeader.Elements.CollapsedContentStart,
        y = ShadeHeader.Dimensions.CollapsedHeight
    )
    translate(ShadeHeader.Elements.CollapsedContentEnd, y = ShadeHeader.Dimensions.CollapsedHeight)
    translate(
        ShadeHeader.Elements.ExpandedContent,
        y = -(ShadeHeader.Dimensions.ExpandedHeight - ShadeHeader.Dimensions.CollapsedHeight)
    )
    translate(ShadeHeader.Elements.ShadeCarrierGroup, y = -ShadeHeader.Dimensions.CollapsedHeight)

    fractionRange(end = .14f) {
        fade(ShadeHeader.Elements.CollapsedContentStart)
        fade(ShadeHeader.Elements.CollapsedContentEnd)
    }

    fractionRange(start = .58f) {
        fade(ShadeHeader.Elements.ExpandedContent)
        fade(ShadeHeader.Elements.ShadeCarrierGroup)
    }
}

private val DefaultDuration = 500.milliseconds
