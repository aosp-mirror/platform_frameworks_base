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

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.scene.domain.interactor.SceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.ui.model.MultipleOngoingActivityChipsModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModel
import com.android.systemui.statusbar.events.domain.interactor.SystemStatusEventAnimationInteractor
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState
import com.android.systemui.statusbar.events.shared.model.SystemEventAnimationState.Idle
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.phone.domain.interactor.LightsOutInteractor
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.CollapsedStatusBarInteractor
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel.VisibilityModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A view model that manages the visibility of the [CollapsedStatusBarFragment] based on the device
 * state.
 *
 * Right now, most of the status bar visibility management is actually in
 * [CollapsedStatusBarFragment.calculateInternalModel], which uses
 * [CollapsedStatusBarFragment.shouldHideNotificationIcons] and
 * [StatusBarHideIconsForBouncerManager]. We should move those pieces of logic to this class instead
 * so that it's all in one place and easily testable outside of the fragment.
 */
interface HomeStatusBarViewModel {
    /**
     * True if the device is currently transitioning from lockscreen to occluded and false
     * otherwise.
     */
    val isTransitioningFromLockscreenToOccluded: StateFlow<Boolean>

    /** Emits whenever a transition from lockscreen to dream has started. */
    val transitionFromLockscreenToDreamStartedEvent: Flow<Unit>

    /**
     * The ongoing activity chip that should be primarily shown on the left-hand side of the status
     * bar. If there are multiple ongoing activity chips, this one should take priority.
     */
    val primaryOngoingActivityChip: StateFlow<OngoingActivityChipModel>

    /**
     * The multiple ongoing activity chips that should be shown on the left-hand side of the status
     * bar.
     */
    val ongoingActivityChips: StateFlow<MultipleOngoingActivityChipsModel>

    /**
     * True if the current scene can show the home status bar (aka this status bar), and false if
     * the current scene should never show the home status bar.
     *
     * TODO(b/364360986): Once the is<SomeChildView>Visible flows are fully enabled, we shouldn't
     *   need this flow anymore.
     */
    val isHomeStatusBarAllowedByScene: StateFlow<Boolean>

    val isClockVisible: Flow<VisibilityModel>
    val isNotificationIconContainerVisible: Flow<VisibilityModel>
    /**
     * Pair of (system info visibility, event animation state). The animation state can be used to
     * respond to the system event chip animations. In all cases, system info visibility correctly
     * models the View.visibility for the system info area
     */
    val systemInfoCombinedVis: StateFlow<SystemInfoCombinedVisibilityModel>

    /**
     * Apps can request a low profile mode [android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE] where
     * status bar and navigation icons dim. In this mode, a notification dot appears where the
     * notification icons would appear if they would be shown outside of this mode.
     *
     * This flow tells when to show or hide the notification dot in the status bar to indicate
     * whether there are notifications when the device is in
     * [android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE].
     */
    fun areNotificationsLightsOut(displayId: Int): Flow<Boolean>

    /** Models the current visibility for a specific child view of status bar. */
    data class VisibilityModel(
        @View.Visibility val visibility: Int,
        /** True if a visibility change should be animated. */
        val shouldAnimateChange: Boolean,
    )

    /** The combined visibility + animation state for the system info status bar area */
    data class SystemInfoCombinedVisibilityModel(
        val baseVisibility: VisibilityModel,
        val animationState: SystemEventAnimationState,
    )
}

@SysUISingleton
class HomeStatusBarViewModelImpl
@Inject
constructor(
    collapsedStatusBarInteractor: CollapsedStatusBarInteractor,
    private val lightsOutInteractor: LightsOutInteractor,
    private val notificationsInteractor: ActiveNotificationsInteractor,
    headsUpNotificationInteractor: HeadsUpNotificationInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    keyguardInteractor: KeyguardInteractor,
    sceneInteractor: SceneInteractor,
    sceneContainerOcclusionInteractor: SceneContainerOcclusionInteractor,
    shadeInteractor: ShadeInteractor,
    ongoingActivityChipsViewModel: OngoingActivityChipsViewModel,
    animations: SystemStatusEventAnimationInteractor,
    @Application coroutineScope: CoroutineScope,
) : HomeStatusBarViewModel {
    override val isTransitioningFromLockscreenToOccluded: StateFlow<Boolean> =
        keyguardTransitionInteractor
            .isInTransition(Edge.create(from = LOCKSCREEN, to = OCCLUDED))
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), initialValue = false)

    override val transitionFromLockscreenToDreamStartedEvent: Flow<Unit> =
        keyguardTransitionInteractor
            .transition(Edge.create(from = LOCKSCREEN, to = DREAMING))
            .filter { it.transitionState == TransitionState.STARTED }
            .map {}

    override val primaryOngoingActivityChip = ongoingActivityChipsViewModel.primaryChip

    override val ongoingActivityChips = ongoingActivityChipsViewModel.chips

    override val isHomeStatusBarAllowedByScene: StateFlow<Boolean> =
        combine(
                sceneInteractor.currentScene,
                sceneContainerOcclusionInteractor.invisibleDueToOcclusion,
            ) { currentScene, isOccluded ->
                // All scenes have their own status bars, so we should only show the home status bar
                // if we're not in a scene. The one exception: If the scene is occluded, then the
                // occluding app needs to show the status bar. (Fullscreen apps actually won't show
                // the status bar but that's handled with the rest of our fullscreen app logic,
                // which lives elsewhere.)
                currentScene == Scenes.Gone || isOccluded
            }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), initialValue = false)

    override fun areNotificationsLightsOut(displayId: Int): Flow<Boolean> =
        if (NotificationsLiveDataStoreRefactor.isUnexpectedlyInLegacyMode()) {
            emptyFlow()
        } else {
            combine(
                    notificationsInteractor.areAnyNotificationsPresent,
                    lightsOutInteractor.isLowProfile(displayId),
                ) { hasNotifications, isLowProfile ->
                    hasNotifications && isLowProfile
                }
                .distinctUntilChanged()
        }

    /**
     * True if the current SysUI state can show the home status bar (aka this status bar), and false
     * if we shouldn't be showing any part of the home status bar.
     */
    private val isHomeScreenStatusBarAllowedLegacy: Flow<Boolean> =
        combine(
            keyguardTransitionInteractor.currentKeyguardState,
            shadeInteractor.isAnyFullyExpanded,
        ) { currentKeyguardState, isShadeExpanded ->
            (currentKeyguardState == GONE || currentKeyguardState == OCCLUDED) && !isShadeExpanded
            // TODO(b/364360986): Add edge cases, like secure camera launch.
        }

    private val isHomeStatusBarAllowed: Flow<Boolean> =
        if (SceneContainerFlag.isEnabled) {
            isHomeStatusBarAllowedByScene
        } else {
            isHomeScreenStatusBarAllowedLegacy
        }

    private val shouldHomeStatusBarBeVisible =
        combine(isHomeStatusBarAllowed, keyguardInteractor.isSecureCameraActive) {
            isHomeStatusBarAllowed,
            isSecureCameraActive ->
            // When launching the camera over the lockscreen, the status icons would typically
            // become visible momentarily before animating out, since we're not yet aware that the
            // launching camera activity is fullscreen. Even once the activity finishes launching,
            // it takes a short time before WM decides that the top app wants to hide the icons and
            // tells us to hide them.
            // To ensure that this high-visibility animation is smooth, keep the icons hidden during
            // a camera launch. See b/257292822.
            isHomeStatusBarAllowed && !isSecureCameraActive
        }

    private val isAnyChipVisible =
        if (StatusBarNotifChips.isEnabled) {
            ongoingActivityChips.map { it.primary is OngoingActivityChipModel.Shown }
        } else {
            primaryOngoingActivityChip.map { it is OngoingActivityChipModel.Shown }
        }

    override val isClockVisible: Flow<VisibilityModel> =
        combine(
            shouldHomeStatusBarBeVisible,
            headsUpNotificationInteractor.showHeadsUpStatusBar,
            collapsedStatusBarInteractor.visibilityViaDisableFlags,
        ) { shouldStatusBarBeVisible, showHeadsUp, visibilityViaDisableFlags ->
            val showClock =
                shouldStatusBarBeVisible && visibilityViaDisableFlags.isClockAllowed && !showHeadsUp
            // Always use View.INVISIBLE here, so that animations work
            VisibilityModel(showClock.toVisibleOrInvisible(), visibilityViaDisableFlags.animate)
        }
    override val isNotificationIconContainerVisible: Flow<VisibilityModel> =
        combine(
            shouldHomeStatusBarBeVisible,
            isAnyChipVisible,
            collapsedStatusBarInteractor.visibilityViaDisableFlags,
        ) { shouldStatusBarBeVisible, anyChipVisible, visibilityViaDisableFlags ->
            val showNotificationIconContainer =
                if (anyChipVisible) {
                    false
                } else {
                    shouldStatusBarBeVisible &&
                        visibilityViaDisableFlags.areNotificationIconsAllowed
                }
            VisibilityModel(
                showNotificationIconContainer.toVisibleOrGone(),
                visibilityViaDisableFlags.animate,
            )
        }

    private val isSystemInfoVisible =
        combine(
            shouldHomeStatusBarBeVisible,
            collapsedStatusBarInteractor.visibilityViaDisableFlags,
        ) { shouldStatusBarBeVisible, visibilityViaDisableFlags ->
            val showSystemInfo =
                shouldStatusBarBeVisible && visibilityViaDisableFlags.isSystemInfoAllowed
            VisibilityModel(showSystemInfo.toVisibleOrGone(), visibilityViaDisableFlags.animate)
        }

    override val systemInfoCombinedVis =
        combine(isSystemInfoVisible, animations.animationState) { sysInfoVisible, animationState ->
                HomeStatusBarViewModel.SystemInfoCombinedVisibilityModel(
                    sysInfoVisible,
                    animationState,
                )
            }
            .stateIn(
                coroutineScope,
                SharingStarted.WhileSubscribed(),
                HomeStatusBarViewModel.SystemInfoCombinedVisibilityModel(
                    VisibilityModel(View.VISIBLE, false),
                    Idle,
                ),
            )

    @View.Visibility
    private fun Boolean.toVisibleOrGone(): Int {
        return if (this) View.VISIBLE else View.GONE
    }

    // Similar to the above, but uses INVISIBLE in place of GONE
    @View.Visibility
    private fun Boolean.toVisibleOrInvisible(): Int = if (this) View.VISIBLE else View.INVISIBLE
}
