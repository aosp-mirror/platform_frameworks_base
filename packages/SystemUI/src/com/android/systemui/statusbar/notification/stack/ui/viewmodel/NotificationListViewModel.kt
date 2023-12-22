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

import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.domain.interactor.RemoteInputInteractor
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterViewModel
import com.android.systemui.statusbar.notification.shelf.ui.viewmodel.NotificationShelfViewModel
import com.android.systemui.statusbar.policy.domain.interactor.UserSetupInteractor
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.util.kotlin.combine
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.ui.AnimatableEvent
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.toAnimatedValueFlow
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** ViewModel for the list of notifications. */
class NotificationListViewModel
@Inject
constructor(
    val shelf: NotificationShelfViewModel,
    val hideListViewModel: HideListViewModel,
    val footer: Optional<FooterViewModel>,
    val logger: Optional<NotificationLoggerViewModel>,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    powerInteractor: PowerInteractor,
    remoteInputInteractor: RemoteInputInteractor,
    seenNotificationsInteractor: SeenNotificationsInteractor,
    shadeInteractor: ShadeInteractor,
    userSetupInteractor: UserSetupInteractor,
    zenModeInteractor: ZenModeInteractor,
) {
    /**
     * We want the NSSL to be unimportant for accessibility when there are no notifications in it
     * while the device is on lock screen, to avoid an unlabelled NSSL view in TalkBack. Otherwise,
     * we want it to be important for accessibility to enable accessibility auto-scrolling in NSSL.
     * See b/242235264 for more details.
     */
    val isImportantForAccessibility: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(true)
        } else {
            combine(
                    activeNotificationsInteractor.areAnyNotificationsPresent,
                    keyguardTransitionInteractor.isFinishedInStateWhere {
                        KeyguardState.lockscreenVisibleInState(it)
                    }
                ) { hasNotifications, isOnKeyguard ->
                    hasNotifications || !isOnKeyguard
                }
                .distinctUntilChanged()
        }
    }

    val shouldShowEmptyShadeView: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            combine(
                    activeNotificationsInteractor.areAnyNotificationsPresent,
                    shadeInteractor.isQsFullscreen,
                    // TODO(b/293167744): It looks like we're essentially trying to check the same
                    //  things for the empty shade visibility as we do for the footer, just in a
                    //  slightly different way. We should change this so we also check
                    //  statusBarState and isAwake instead of specific keyguard transitions.
                    keyguardTransitionInteractor.isInTransitionToState(KeyguardState.AOD).onStart {
                        emit(false)
                    },
                    keyguardTransitionInteractor
                        .isFinishedInState(KeyguardState.PRIMARY_BOUNCER)
                        .onStart { emit(false) }
                ) { hasNotifications, isQsFullScreen, transitioningToAOD, isBouncerShowing ->
                    !hasNotifications &&
                        !isQsFullScreen &&
                        // Hide empty shade view when in transition to AOD.
                        // That avoids "No Notifications" blinking when transitioning to AOD.
                        // For more details, see b/228790482.
                        !transitioningToAOD &&
                        // Don't show any notification content if the bouncer is showing. See
                        // b/267060171.
                        !isBouncerShowing
                }
                .distinctUntilChanged()
        }
    }

    val shouldShowFooterView: Flow<AnimatedValue<Boolean>> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(AnimatedValue.NotAnimating(false))
        } else {
            combine(
                    activeNotificationsInteractor.areAnyNotificationsPresent,
                    userSetupInteractor.isUserSetUp,
                    keyguardInteractor.statusBarState.map { it == StatusBarState.KEYGUARD },
                    shadeInteractor.qsExpansion,
                    shadeInteractor.isQsFullscreen,
                    powerInteractor.isAsleep,
                    remoteInputInteractor.isRemoteInputActive,
                    shadeInteractor.shadeExpansion.map { it == 0f }
                ) {
                    hasNotifications,
                    isUserSetUp,
                    isOnKeyguard,
                    qsExpansion,
                    qsFullScreen,
                    isAsleep,
                    isRemoteInputActive,
                    isShadeClosed ->
                    Pair(
                        // Should the footer be visible?
                        when {
                            !hasNotifications -> false
                            // Hide the footer until the user setup is complete, to prevent access
                            // to settings (b/193149550).
                            !isUserSetUp -> false
                            // Do not show the footer if the lockscreen is visible (incl. AOD),
                            // except if the shade is opened on top. See also b/219680200.
                            isOnKeyguard -> false
                            // Make sure we're not showing the footer in the transition to AOD while
                            // going to sleep (b/190227875). The StatusBarState is unfortunately not
                            // updated quickly enough when the power button is pressed, so this is
                            // necessary in addition to the isOnKeyguard check.
                            isAsleep -> false
                            // Do not show the footer if quick settings are fully expanded (except
                            // for the foldable split shade view). See b/201427195 && b/222699879.
                            qsExpansion == 1f && qsFullScreen -> false
                            // Hide the footer if remote input is active (i.e. user is replying to a
                            // notification). See b/75984847.
                            isRemoteInputActive -> false
                            // Never show the footer if the shade is collapsed (e.g. when HUNing).
                            isShadeClosed -> false
                            else -> true
                        },
                        // This could in theory be in the .sample below, but it tends to be
                        // inconsistent, so we're passing it on to make sure we have the same state.
                        isOnKeyguard
                    )
                }
                .distinctUntilChanged()
                // Should we animate the visibility change?
                .sample(
                    // TODO(b/322167853): This check is currently duplicated in FooterViewModel,
                    //  but instead it should be a field in ShadeAnimationInteractor.
                    combine(
                            shadeInteractor.isShadeFullyExpanded,
                            shadeInteractor.isShadeTouchable,
                            ::Pair
                        )
                        .onStart { emit(Pair(false, false)) }
                ) { (visible, isOnKeyguard), (isShadeFullyExpanded, animationsEnabled) ->
                    // Animate if the shade is interactive, but NOT on the lockscreen. Having
                    // animations enabled while on the lockscreen makes the footer appear briefly
                    // when transitioning between the shade and keyguard.
                    val shouldAnimate = isShadeFullyExpanded && animationsEnabled && !isOnKeyguard
                    AnimatableEvent(visible, shouldAnimate)
                }
                .toAnimatedValueFlow()
        }
    }

    // TODO(b/308591475): This should be tracked separately by the empty shade.
    val areNotificationsHiddenInShade: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            zenModeInteractor.areNotificationsHiddenInShade
        }
    }

    // TODO(b/308591475): This should be tracked separately by the empty shade.
    val hasFilteredOutSeenNotifications: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            seenNotificationsInteractor.hasFilteredOutSeenNotifications
        }
    }
}
