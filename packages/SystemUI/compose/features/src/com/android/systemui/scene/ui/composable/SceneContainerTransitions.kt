package com.android.systemui.scene.ui.composable

import com.android.compose.animation.scene.transitions
import com.android.systemui.scene.ui.composable.transitions.bouncerToGoneTransition
import com.android.systemui.scene.ui.composable.transitions.goneToQuickSettingsTransition
import com.android.systemui.scene.ui.composable.transitions.goneToShadeTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToBouncerTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToCommunalTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToGoneTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToQuickSettingsTransition
import com.android.systemui.scene.ui.composable.transitions.lockscreenToShadeTransition
import com.android.systemui.scene.ui.composable.transitions.shadeToQuickSettingsTransition

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
    from(Bouncer, to = Gone) { bouncerToGoneTransition() }
    from(Gone, to = Shade) { goneToShadeTransition() }
    from(Gone, to = QuickSettings) { goneToQuickSettingsTransition() }
    from(Lockscreen, to = Bouncer) { lockscreenToBouncerTransition() }
    from(Lockscreen, to = Communal) { lockscreenToCommunalTransition() }
    from(Lockscreen, to = Shade) { lockscreenToShadeTransition() }
    from(Lockscreen, to = QuickSettings) { lockscreenToQuickSettingsTransition() }
    from(Lockscreen, to = Gone) { lockscreenToGoneTransition() }
    from(Shade, to = QuickSettings) { shadeToQuickSettingsTransition() }
}
