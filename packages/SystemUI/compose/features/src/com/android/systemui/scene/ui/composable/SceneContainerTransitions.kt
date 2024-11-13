package com.android.systemui.scene.ui.composable

import androidx.compose.foundation.gestures.Orientation
import com.android.compose.animation.scene.ProgressConverter
import com.android.compose.animation.scene.TransitionKey
import com.android.compose.animation.scene.transitions
import com.android.systemui.bouncer.ui.composable.Bouncer
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.SlightlyFasterShadeCollapse
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.scene.ui.composable.transitions.bouncerToGoneTransition
import com.android.systemui.scene.ui.composable.transitions.bouncerToLockscreenPreview
import com.android.systemui.scene.ui.composable.transitions.goneToQuickSettingsTransition
import com.android.systemui.scene.ui.composable.transitions.goneToShadeTransition
import com.android.systemui.scene.ui.composable.transitions.goneToSplitShadeTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToBouncerTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToCommunalTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToGoneTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToQuickSettingsTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToShadeTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToSplitShadeTransition
import com.android.systemui.scene.ui.composable.transitions.notificationsShadeToQuickSettingsShadeTransition
import com.android.systemui.scene.ui.composable.transitions.shadeToQuickSettingsTransition
import com.android.systemui.scene.ui.composable.transitions.toNotificationsShadeTransition
import com.android.systemui.scene.ui.composable.transitions.toQuickSettingsShadeTransition
import com.android.systemui.shade.ui.composable.OverlayShade
import com.android.systemui.shade.ui.composable.Shade

/**
 * Comprehensive definition of all transitions between scenes in [SceneContainer].
 *
 * Transitions are automatically reversible, so define only one transition per scene pair. By\
 * convention, use the more common transition direction when defining the pair order, e.g.
 * Lockscreen to Bouncer rather than Bouncer to Lockscreen.
 *
 * The actual transition DSL must be placed in a separate file under the package
 * [com.android.systemui.scene.ui.composable.transitions].
 *
 * Please keep the list sorted alphabetically.
 */
val SceneContainerTransitions = transitions {

    // Overscroll progress starts linearly with some resistance (3f) and slowly approaches 0.2f
    defaultOverscrollProgressConverter = ProgressConverter.tanh(maxProgress = 0.2f, tilt = 3f)

    // Scene transitions

    from(Scenes.Bouncer, to = Scenes.Gone) { bouncerToGoneTransition() }
    from(Scenes.Gone, to = Scenes.Shade) { goneToShadeTransition() }
    from(Scenes.Gone, to = Scenes.Shade, key = ToSplitShade) { goneToSplitShadeTransition() }
    from(Scenes.Gone, to = Scenes.Shade, key = SlightlyFasterShadeCollapse) {
        goneToShadeTransition(durationScale = 0.9)
    }
    from(Scenes.Gone, to = Scenes.QuickSettings) { goneToQuickSettingsTransition() }
    from(Scenes.Gone, to = Scenes.QuickSettings, key = SlightlyFasterShadeCollapse) {
        goneToQuickSettingsTransition(durationScale = 0.9)
    }

    from(Scenes.Lockscreen, to = Scenes.Bouncer) { lockscreenToBouncerTransition() }
    from(
        Scenes.Lockscreen,
        to = Scenes.Bouncer,
        key = TransitionKey.PredictiveBack,
        reversePreview = { bouncerToLockscreenPreview() },
    ) {
        lockscreenToBouncerTransition()
    }
    from(Scenes.Lockscreen, to = Scenes.Communal) { lockscreenToCommunalTransition() }
    from(Scenes.Lockscreen, to = Scenes.Shade) { lockscreenToShadeTransition() }
    from(Scenes.Lockscreen, to = Scenes.Shade, key = ToSplitShade) {
        lockscreenToSplitShadeTransition()
    }
    from(Scenes.Lockscreen, to = Scenes.Shade, key = SlightlyFasterShadeCollapse) {
        lockscreenToShadeTransition(durationScale = 0.9)
    }
    from(Scenes.Lockscreen, to = Scenes.QuickSettings) { lockscreenToQuickSettingsTransition() }
    from(Scenes.Lockscreen, to = Scenes.Gone) { lockscreenToGoneTransition() }
    from(Scenes.QuickSettings, to = Scenes.Shade) {
        reversed { shadeToQuickSettingsTransition() }
        sharedElement(Notifications.Elements.HeadsUpNotificationPlaceholder, enabled = false)
    }
    from(Scenes.Shade, to = Scenes.QuickSettings) { shadeToQuickSettingsTransition() }

    // Overlay transitions

    to(Overlays.NotificationsShade) { toNotificationsShadeTransition() }
    to(Overlays.QuickSettingsShade) { toQuickSettingsShadeTransition() }
    from(Overlays.NotificationsShade, Overlays.QuickSettingsShade) {
        notificationsShadeToQuickSettingsShadeTransition()
    }

    // Scene overscroll

    overscrollDisabled(Scenes.Gone, Orientation.Vertical)
    overscroll(Scenes.Bouncer, Orientation.Vertical) {
        translate(Bouncer.Elements.Content, y = { absoluteDistance })
    }
    overscroll(Scenes.Shade, Orientation.Vertical) {
        translate(
            Notifications.Elements.NotificationScrim,
            y = Shade.Dimensions.ScrimOverscrollLimit,
        )
        translate(Shade.Elements.SplitShadeStartColumn, y = Shade.Dimensions.ScrimOverscrollLimit)
        translate(
            Notifications.Elements.NotificationStackPlaceholder,
            y = Shade.Dimensions.ScrimOverscrollLimit,
        )
    }
    overscroll(Overlays.NotificationsShade, Orientation.Vertical) {
        translate(OverlayShade.Elements.Panel, y = OverlayShade.Dimensions.OverscrollLimit)
    }
    overscroll(Overlays.QuickSettingsShade, Orientation.Vertical) {
        translate(OverlayShade.Elements.Panel, y = OverlayShade.Dimensions.OverscrollLimit)
    }
}
