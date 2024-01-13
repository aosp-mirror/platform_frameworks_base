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

import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterViewModel
import com.android.systemui.statusbar.notification.shelf.ui.viewmodel.NotificationShelfViewModel
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
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
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    seenNotificationsInteractor: SeenNotificationsInteractor,
    shadeInteractor: ShadeInteractor,
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
