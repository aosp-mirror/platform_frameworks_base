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

package com.android.systemui.communal.domain.interactor

import android.content.pm.UserInfo
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.communal.data.model.CommunalEnabledState
import com.android.systemui.communal.data.repository.CommunalSettingsRepository
import com.android.systemui.communal.shared.model.CommunalBackgroundType
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.dagger.CommunalTableLog
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.settings.UserTracker
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalSettingsInteractor
@Inject
constructor(
    @Background private val bgScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Background private val bgExecutor: Executor,
    private val repository: CommunalSettingsRepository,
    userInteractor: SelectedUserInteractor,
    private val userTracker: UserTracker,
    @CommunalTableLog tableLogBuffer: TableLogBuffer,
) {
    /** Whether or not communal is enabled for the currently selected user. */
    val isCommunalEnabled: StateFlow<Boolean> =
        userInteractor.selectedUserInfo
            .flatMapLatest { user -> repository.getEnabledState(user) }
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                columnPrefix = "disabledReason",
                initialValue = CommunalEnabledState()
            )
            .map { model -> model.enabled }
            // Start this eagerly since the value is accessed synchronously in many places.
            .stateIn(scope = bgScope, started = SharingStarted.Eagerly, initialValue = false)

    /**
     * Returns true if both the communal trunk-stable flag and resource flag are enabled.
     *
     * The trunk-stable flag is controlled by server rollout and is on all devices. The resource
     * flag is enabled via resource overlay only on products we want the hub to be present on.
     *
     * If this is false, then the hub is definitely not available on the device. If this is true,
     * refer to [isCommunalEnabled] which takes into account other factors that can change at
     * runtime.
     */
    fun isCommunalFlagEnabled(): Boolean = repository.getFlagEnabled()

    /** The type of background to use for the hub. Used to experiment with different backgrounds */
    val communalBackground: Flow<CommunalBackgroundType> =
        userInteractor.selectedUserInfo
            .flatMapLatest { user -> repository.getBackground(user) }
            .flowOn(bgDispatcher)

    private val workProfileUserInfoCallbackFlow: Flow<UserInfo?> = conflatedCallbackFlow {
        fun send(profiles: List<UserInfo>) {
            trySend(profiles.find { it.isManagedProfile })
        }

        val callback =
            object : UserTracker.Callback {
                override fun onProfilesChanged(profiles: List<UserInfo>) {
                    send(profiles)
                }
            }
        userTracker.addCallback(callback, bgExecutor)
        send(userTracker.userProfiles)

        awaitClose { userTracker.removeCallback(callback) }
    }

    /**
     * A user that device policy says shouldn't allow communal widgets, or null if there are no
     * restrictions.
     */
    val workProfileUserDisallowedByDevicePolicy: StateFlow<UserInfo?> =
        workProfileUserInfoCallbackFlow
            .flatMapLatest { workProfile ->
                workProfile?.let {
                    repository.getAllowedByDevicePolicy(it).map { allowed ->
                        if (!allowed) it else null
                    }
                } ?: flowOf(null)
            }
            .stateIn(
                scope = bgScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = null
            )
}
