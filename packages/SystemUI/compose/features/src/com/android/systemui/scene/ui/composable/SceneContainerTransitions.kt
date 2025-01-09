package com.android.systemui.scene.ui.composable

import androidx.compose.animation.core.spring
import com.android.compose.animation.scene.TransitionKey
import com.android.compose.animation.scene.transitions
import com.android.systemui.notifications.ui.composable.Notifications
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.SlightlyFasterShadeCollapse
import com.android.systemui.scene.shared.model.TransitionKeys.ToSplitShade
import com.android.systemui.scene.ui.composable.transitions.bouncerToGoneTransition
import com.android.systemui.scene.ui.composable.transitions.bouncerToLockscreenPreview
import com.android.systemui.scene.ui.composable.transitions.communalToBouncerTransition
import com.android.systemui.scene.ui.composable.transitions.communalToShadeTransition
import com.android.systemui.scene.ui.composable.transitions.dreamToBouncerTransition
import com.android.systemui.scene.ui.composable.transitions.dreamToCommunalTransition
import com.android.systemui.scene.ui.composable.transitions.dreamToGoneTransition
import com.android.systemui.scene.ui.composable.transitions.dreamToShadeTransition
import com.android.systemui.scene.ui.composable.transitions.goneToQuickSettingsTransition
import com.android.systemui.scene.ui.composable.transitions.goneToShadeTransition
import com.android.systemui.scene.ui.composable.transitions.goneToSplitShadeTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToBouncerTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToCommunalTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToDreamTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToGoneTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToQuickSettingsTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToShadeTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToSplitShadeTransition
import com.android.systemui.scene.ui.composable.transitions.notificationsShadeToQuickSettingsShadeTransition
import com.android.systemui.scene.ui.composable.transitions.shadeToQuickSettingsTransition
import com.android.systemui.scene.ui.composable.transitions.toNotificationsShadeTransition
import com.android.systemui.scene.ui.composable.transitions.toQuickSettingsShadeTransition
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
    interruptionHandler = SceneContainerInterruptionHandler

    defaultMotionSpatialSpec =
        spring(stiffness = 300f, dampingRatio = 0.8f, visibilityThreshold = 0.5f)

    // Scene transitions

    from(Scenes.Bouncer, to = Scenes.Gone) { bouncerToGoneTransition() }
    from(Scenes.Dream, to = Scenes.Bouncer) { dreamToBouncerTransition() }
    from(Scenes.Dream, to = Scenes.Communal) { dreamToCommunalTransition() }
    from(Scenes.Dream, to = Scenes.Gone) { dreamToGoneTransition() }
    from(Scenes.Dream, to = Scenes.Shade) { dreamToShadeTransition() }
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
    from(Scenes.Lockscreen, to = Scenes.Dream) { lockscreenToDreamTransition() }
    from(Scenes.Lockscreen, to = Scenes.Shade) { lockscreenToShadeTransition() }
    from(Scenes.Lockscreen, to = Scenes.Shade, key = ToSplitShade) {
        lockscreenToSplitShadeTransition()
        sharedElement(Shade.Elements.BackgroundScrim, enabled = false)
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
    from(Scenes.Shade, to = Scenes.Lockscreen) {
        reversed { lockscreenToShadeTransition() }
        sharedElement(Notifications.Elements.NotificationStackPlaceholder, enabled = false)
        sharedElement(Notifications.Elements.HeadsUpNotificationPlaceholder, enabled = false)
    }
    from(Scenes.Shade, to = Scenes.Lockscreen, key = ToSplitShade) {
        reversed { lockscreenToSplitShadeTransition() }
    }
    from(Scenes.Communal, to = Scenes.Shade) { communalToShadeTransition() }
    from(Scenes.Communal, to = Scenes.Bouncer) { communalToBouncerTransition() }

    // Overlay transitions

    to(Overlays.NotificationsShade) { toNotificationsShadeTransition() }
    to(Overlays.QuickSettingsShade) { toQuickSettingsShadeTransition() }
    from(Overlays.NotificationsShade, to = Overlays.QuickSettingsShade) {
        notificationsShadeToQuickSettingsShadeTransition()
    }
    from(Scenes.Gone, to = Overlays.NotificationsShade, key = SlightlyFasterShadeCollapse) {
        toNotificationsShadeTransition(durationScale = 0.9)
    }
    from(Scenes.Gone, to = Overlays.QuickSettingsShade, key = SlightlyFasterShadeCollapse) {
        toQuickSettingsShadeTransition(durationScale = 0.9)
    }
    from(Scenes.Lockscreen, to = Overlays.NotificationsShade, key = SlightlyFasterShadeCollapse) {
        toNotificationsShadeTransition(durationScale = 0.9)
    }
    from(Scenes.Lockscreen, to = Overlays.QuickSettingsShade, key = SlightlyFasterShadeCollapse) {
        toQuickSettingsShadeTransition(durationScale = 0.9)
    }
}
