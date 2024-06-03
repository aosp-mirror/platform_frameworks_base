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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.statusbar.chips.domain.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.viewmodel.OngoingActivityChipsViewModel
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.NotificationsLiveDataStoreRefactor
import com.android.systemui.statusbar.phone.domain.interactor.LightsOutInteractor
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
interface CollapsedStatusBarViewModel {
    /**
     * True if the device is currently transitioning from lockscreen to occluded and false
     * otherwise.
     */
    val isTransitioningFromLockscreenToOccluded: StateFlow<Boolean>

    /** Emits whenever a transition from lockscreen to dream has started. */
    val transitionFromLockscreenToDreamStartedEvent: Flow<Unit>

    /** The ongoing activity chip that should be shown on the left-hand side of the status bar. */
    val ongoingActivityChip: StateFlow<OngoingActivityChipModel>

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
}

@SysUISingleton
class CollapsedStatusBarViewModelImpl
@Inject
constructor(
    private val lightsOutInteractor: LightsOutInteractor,
    private val notificationsInteractor: ActiveNotificationsInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    ongoingActivityChipsViewModel: OngoingActivityChipsViewModel,
    @Application coroutineScope: CoroutineScope,
) : CollapsedStatusBarViewModel {
    override val isTransitioningFromLockscreenToOccluded: StateFlow<Boolean> =
        keyguardTransitionInteractor
            .isInTransition(Edge.create(from = LOCKSCREEN, to = OCCLUDED))
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), initialValue = false)

    override val transitionFromLockscreenToDreamStartedEvent: Flow<Unit> =
        keyguardTransitionInteractor
            .transition(Edge.create(from = LOCKSCREEN, to = DREAMING))
            .filter { it.transitionState == TransitionState.STARTED }
            .map {}

    override val ongoingActivityChip = ongoingActivityChipsViewModel.chip

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
}
