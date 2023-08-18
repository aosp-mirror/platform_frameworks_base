package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.tween
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.qs.footer.ui.compose.QuickSettings

fun TransitionBuilder.shadeToQuickSettingsTransition() {
    spec = tween(durationMillis = 500)

    translate(Notifications.Elements.Notifications, Edge.Bottom)
    timestampRange(endMillis = 83) { fade(QuickSettings.Elements.FooterActions) }
}
