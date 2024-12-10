/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.phone.ongoingcall.domain.interactor

import com.android.systemui.activity.data.repository.ActivityManagerRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallLog
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Interactor for determining whether to show a chip in the status bar for ongoing phone calls.
 *
 * This class monitors call notifications and the visibility of call apps to determine the appropriate
 * chip state. It emits:
 *  * - [OngoingCallModel.NoCall] when there is no call notification
 *  * - [OngoingCallModel.InCallWithVisibleApp] when there is a call notification but the call app is visible
 *  * - [OngoingCallModel.InCall] when there is a call notification and the call app is not visible
 *  */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class OngoingCallInteractor @Inject constructor(
    @Application private val scope: CoroutineScope,
    private val activityManagerRepository: ActivityManagerRepository,
    private val statusBarModeRepositoryStore: StatusBarModeRepositoryStore,
    private val statusBarWindowControllerStore: StatusBarWindowControllerStore,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    @OngoingCallLog private val logBuffer: LogBuffer,
) {
    private val logger = Logger(logBuffer, TAG)

    /**
     * The current state of ongoing calls.
     */
    val ongoingCallState: StateFlow<OngoingCallModel> =
        activeNotificationsInteractor.ongoingCallNotification
            .flatMapLatest { notification ->
                createOngoingCallStateFlow(
                    notification = notification
                )
            }
            .onEach { state ->
                setStatusBarRequiredForOngoingCall(state)
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = OngoingCallModel.NoCall
            )

    private fun createOngoingCallStateFlow(
        notification: ActiveNotificationModel?
    ): Flow<OngoingCallModel> {
        if (notification == null) {
            logger.d("No active call notification - hiding chip")
            return flowOf(OngoingCallModel.NoCall)
        }

        return combine(
            flowOf(notification),
            activityManagerRepository.createIsAppVisibleFlow(
                creationUid = notification.uid,
                logger = logger,
                identifyingLogTag = TAG,
            )
        ) { model, isVisible ->
            deriveOngoingCallState(model, isVisible)
        }
    }

    private fun deriveOngoingCallState(
        model: ActiveNotificationModel,
        isVisible: Boolean
    ): OngoingCallModel {
        return when {
            isVisible -> {
                logger.d({ "Call app is visible: uid=$int1" }) {
                    int1 = model.uid
                }
                OngoingCallModel.InCallWithVisibleApp
            }

            else -> {
                logger.d({ "Active call detected: startTime=$long1 hasIcon=$bool1" }) {
                    long1 = model.whenTime
                    bool1 = model.statusBarChipIconView != null
                }
                OngoingCallModel.InCall(
                    startTimeMs = model.whenTime,
                    notificationIconView = model.statusBarChipIconView,
                    intent = model.contentIntent,
                    notificationKey = model.key
                )
            }
        }
    }

    private fun setStatusBarRequiredForOngoingCall(state: OngoingCallModel) {
        val statusBarRequired = state is OngoingCallModel.InCall
        // TODO(b/382808183): Create a single repository that can be utilized in
        //  `statusBarModeRepositoryStore` and `statusBarWindowControllerStore` so we do not need
        //  two separate calls to force the status bar to stay visible.
        statusBarModeRepositoryStore.defaultDisplay.setOngoingProcessRequiresStatusBarVisible(
            statusBarRequired
        )
        statusBarWindowControllerStore.defaultDisplay
            .setOngoingProcessRequiresStatusBarVisible(statusBarRequired)
    }

    companion object {
        private val TAG = "OngoingCall"
    }
}
