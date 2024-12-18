/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.domain.interactor.RemoteInputInteractor
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimRounding
import com.android.systemui.util.kotlin.ActivatableFlowDumper
import com.android.systemui.util.kotlin.ActivatableFlowDumperImpl
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * ViewModel used by the Notification placeholders inside the scene container to update the
 * [NotificationStackAppearanceInteractor], and by extension control the NSSL.
 */
class NotificationsPlaceholderViewModel
@AssistedInject
constructor(
    private val interactor: NotificationStackAppearanceInteractor,
    private val sceneInteractor: SceneInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val headsUpNotificationInteractor: HeadsUpNotificationInteractor,
    remoteInputInteractor: RemoteInputInteractor,
    featureFlags: FeatureFlagsClassic,
    dumpManager: DumpManager,
) :
    ExclusiveActivatable(),
    ActivatableFlowDumper by ActivatableFlowDumperImpl(
        dumpManager = dumpManager,
        tag = "NotificationsPlaceholderViewModel",
    ) {

    /** DEBUG: whether the placeholder should be made slightly visible for positional debugging. */
    val isVisualDebuggingEnabled: Boolean = featureFlags.isEnabled(Flags.NSSL_DEBUG_LINES)

    /** DEBUG: whether the debug logging should be output. */
    val isDebugLoggingEnabled: Boolean = SceneContainerFlag.isEnabled

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch {
                shadeInteractor.isAnyExpanded
                    .filter { it }
                    .collect { headsUpNotificationInteractor.unpinAll(true) }
            }

            launch {
                sceneInteractor.transitionState
                    .map { state -> state is ObservableTransitionState.Idle }
                    .filter { it }
                    .collect { headsUpNotificationInteractor.onTransitionIdle() }
            }
        }
        activateFlowDumper()
    }

    /** Notifies that the bounds of the notification scrim have changed. */
    fun onScrimBoundsChanged(bounds: ShadeScrimBounds?) {
        interactor.setShadeScrimBounds(bounds)
    }

    /** Sets the available space */
    fun onConstrainedAvailableSpaceChanged(height: Int) {
        interactor.setConstrainedAvailableSpace(height)
    }

    /** Sets the content alpha for the current state of the brightness mirror */
    fun setAlphaForBrightnessMirror(alpha: Float) {
        interactor.setAlphaForBrightnessMirror(alpha)
    }

    /** True when a HUN is pinned or animating away. */
    val isHeadsUpOrAnimatingAway: Flow<Boolean> =
        headsUpNotificationInteractor.isHeadsUpOrAnimatingAway

    /** Corner rounding of the stack */
    val shadeScrimRounding: Flow<ShadeScrimRounding> =
        interactor.shadeScrimRounding.dumpWhileCollecting("shadeScrimRounding")

    /**
     * The amount [0-1] that the shade or quick settings has been opened. At 0, the shade is closed;
     * at 1, either the shade or quick settings is open.
     */
    val expandFraction: Flow<Float> = shadeInteractor.anyExpansion.dumpValue("expandFraction")

    /**
     * The amount [0-1] that quick settings has been opened. At 0, the shade may be open or closed;
     * at 1, the quick settings are open.
     */
    val shadeToQsFraction: Flow<Float> = shadeInteractor.qsExpansion.dumpValue("shadeToQsFraction")

    /**
     * The amount in px that the notification stack should scroll due to internal expansion. This
     * should only happen when a notification expansion hits the bottom of the screen, so it is
     * necessary to scroll up to keep expanding the notification.
     */
    val syntheticScroll: Flow<Float> =
        interactor.syntheticScroll.dumpWhileCollecting("syntheticScroll")

    /**
     * Whether the current touch gesture is overscroll. If true, it means the NSSL has already
     * consumed part of the gesture.
     */
    val isCurrentGestureOverscroll: Flow<Boolean> =
        interactor.isCurrentGestureOverscroll.dumpWhileCollecting("isCurrentGestureOverScroll")

    /** Whether remote input is currently active for any notification. */
    val isRemoteInputActive = remoteInputInteractor.isRemoteInputActive

    /** The bottom bound of the currently focused remote input notification row. */
    val remoteInputRowBottomBound = remoteInputInteractor.remoteInputRowBottomBound

    /** Sets whether the notification stack is scrolled to the top. */
    fun setScrolledToTop(scrolledToTop: Boolean) {
        interactor.setScrolledToTop(scrolledToTop)
    }

    /** Sets whether the heads up notification is animating away. */
    fun setHeadsUpAnimatingAway(animatingAway: Boolean) {
        headsUpNotificationInteractor.setHeadsUpAnimatingAway(animatingAway)
    }

    /** Snooze the currently pinned HUN. */
    fun snoozeHun() {
        headsUpNotificationInteractor.snooze()
    }

    @AssistedFactory
    interface Factory {
        fun create(): NotificationsPlaceholderViewModel
    }
}

// Expansion fraction thresholds (between 0-1f) at which the corresponding value should be
// at its maximum, given they are at their minimum value at expansion = 0f.
object NotificationTransitionThresholds {
    const val EXPANSION_FOR_MAX_CORNER_RADIUS = 0.1f
    const val EXPANSION_FOR_MAX_SCRIM_ALPHA = 0.3f
    const val EXPANSION_FOR_DELAYED_STACK_FADE_IN = 0.5f
}
