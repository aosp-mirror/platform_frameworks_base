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
                    // TODO(b/293167744): Check if it would be enough to just check for
                    //  isShowingOnLockscreen here as well, so we don't need to depend on the
                    //  keyguardTransitionInteractor when we don't actually care about _transitions_
                    //  specifically.
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
                    isShowingOnLockscreen,
                ) { hasNotifications, isQsFullScreen, isShowingOnLockscreen ->
                    when {
                        hasNotifications -> false
                        isQsFullScreen -> false
                        // Do not show the empty shade if the lockscreen is visible (including AOD
                        // b/228790482 and bouncer b/267060171), except if the shade is opened on
                        // top.
                        isShowingOnLockscreen -> false
                        else -> true
                    }
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
                    isShowingOnLockscreen,
                    shadeInteractor.qsExpansion,
                    shadeInteractor.isQsFullscreen,
                    remoteInputInteractor.isRemoteInputActive,
                    shadeInteractor.shadeExpansion.map { it == 0f }
                ) {
                    hasNotifications,
                    isUserSetUp,
                    isShowingOnLockscreen,
                    qsExpansion,
                    qsFullScreen,
                    isRemoteInputActive,
                    isShadeClosed ->
                    // A pair of (visible, canAnimate)
                    when {
                        !hasNotifications -> Pair(false, true)
                        // Hide the footer until the user setup is complete, to prevent access
                        // to settings (b/193149550).
                        !isUserSetUp -> Pair(false, true)
                        // Do not show the footer if the lockscreen is visible (incl. AOD),
                        // except if the shade is opened on top. See also b/219680200.
                        // Do not animate, as that makes the footer appear briefly when
                        // transitioning between the shade and keyguard.
                        isShowingOnLockscreen -> Pair(false, false)
                        // Do not show the footer if quick settings are fully expanded (except
                        // for the foldable split shade view). See b/201427195 && b/222699879.
                        qsExpansion == 1f && qsFullScreen -> Pair(false, true)
                        // Hide the footer if remote input is active (i.e. user is replying to a
                        // notification). See b/75984847.
                        isRemoteInputActive -> Pair(false, true)
                        // Never show the footer if the shade is collapsed (e.g. when HUNing).
                        isShadeClosed -> Pair(false, false)
                        else -> Pair(true, true)
                    }
                }
                .distinctUntilChanged(
                    // Equivalent unless visibility changes
                    areEquivalent = { a: Pair<Boolean, Boolean>, b: Pair<Boolean, Boolean> ->
                        a.first == b.first
                    }
                )
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
                ) { (visible, canAnimate), (isShadeFullyExpanded, animationsEnabled) ->
                    // Animate if the shade is interactive, but NOT on the lockscreen. Having
                    // animations enabled while on the lockscreen makes the footer appear briefly
                    // when transitioning between the shade and keyguard.
                    val shouldAnimate = isShadeFullyExpanded && animationsEnabled && canAnimate
                    AnimatableEvent(visible, shouldAnimate)
                }
                .toAnimatedValueFlow()
        }
    }

    private val isShowingOnLockscreen: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            combine(
                    // Non-notification UI elements of the notification list should not be visible
                    // on the lockscreen (incl. AOD and bouncer), except if the shade is opened on
                    // top. See b/219680200 for the footer and b/228790482, b/267060171 for the
                    // empty shade.
                    // TODO(b/323187006): There's a plan to eventually get rid of StatusBarState
                    //  entirely, so this will have to be replaced at some point.
                    keyguardInteractor.statusBarState.map { it == StatusBarState.KEYGUARD },
                    // The StatusBarState is unfortunately not updated quickly enough when the power
                    // button is pressed, so this is necessary in addition to the KEYGUARD check to
                    // cover the transition to AOD while going to sleep (b/190227875).
                    powerInteractor.isAsleep,
                ) { (isOnKeyguard, isAsleep) ->
                    isOnKeyguard || isAsleep
                }
                .distinctUntilChanged()
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

    val hasClearableAlertingNotifications: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            activeNotificationsInteractor.hasClearableAlertingNotifications
        }
    }

    val hasNonClearableSilentNotifications: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            activeNotificationsInteractor.hasNonClearableSilentNotifications
        }
    }
}
