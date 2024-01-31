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
package com.android.systemui.communal.data.repository

import android.provider.Settings
import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_COMPLETED
import android.provider.Settings.Secure.HUB_MODE_TUTORIAL_NOT_STARTED
import android.provider.Settings.Secure.HubModeTutorialState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.log.dagger.CommunalTableLog
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Repository for the current state of hub mode tutorial. Valid states are defined in
 * [HubModeTutorialState].
 */
interface CommunalTutorialRepository {
    /** Emits the tutorial state stored in Settings */
    val tutorialSettingState: StateFlow<Int>

    /** Update the tutorial state */
    suspend fun setTutorialState(@HubModeTutorialState state: Int)
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalTutorialRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val userRepository: UserRepository,
    private val secureSettings: SecureSettings,
    @CommunalLog logBuffer: LogBuffer,
    @CommunalTableLog tableLogBuffer: TableLogBuffer,
) : CommunalTutorialRepository {

    companion object {
        private const val TAG = "CommunalTutorialRepository"

        const val MIN_TUTORIAL_VERSION = HUB_MODE_TUTORIAL_COMPLETED

        // A version number which ensures that users, regardless of their completion of previous
        // versions, see the updated tutorial when this number is bumped.
        const val CURRENT_TUTORIAL_VERSION = MIN_TUTORIAL_VERSION + 1
    }

    private data class SettingsState(
        @HubModeTutorialState val hubModeTutorialState: Int? = null,
    )

    private val logger = Logger(logBuffer, TAG)

    private val settingsState: Flow<SettingsState> =
        userRepository.selectedUserInfo
            .flatMapLatest { userInfo -> observeSettings(userInfo.id) }
            .shareIn(scope = applicationScope, started = SharingStarted.WhileSubscribed())

    /** Emits the state of tutorial state in settings */
    override val tutorialSettingState: StateFlow<Int> =
        settingsState
            .map { it.hubModeTutorialState }
            .filterNotNull()
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                columnPrefix = "",
                columnName = "tutorialSettingState",
                initialValue = HUB_MODE_TUTORIAL_NOT_STARTED,
            )
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = HUB_MODE_TUTORIAL_NOT_STARTED,
            )

    private fun observeSettings(userId: Int): Flow<SettingsState> =
        secureSettings
            .observerFlow(
                userId = userId,
                names = arrayOf(Settings.Secure.HUB_MODE_TUTORIAL_STATE),
            )
            // Force an update
            .onStart { emit(Unit) }
            .map { readFromSettings(userId) }

    private suspend fun readFromSettings(userId: Int): SettingsState =
        withContext(backgroundDispatcher) {
            var hubModeTutorialState =
                secureSettings.getIntForUser(
                    Settings.Secure.HUB_MODE_TUTORIAL_STATE,
                    HUB_MODE_TUTORIAL_NOT_STARTED,
                    userId,
                )

            if (hubModeTutorialState >= CURRENT_TUTORIAL_VERSION) {
                // Tutorial is considered "completed" if the user has completed the current or a
                // newer version.
                hubModeTutorialState = HUB_MODE_TUTORIAL_COMPLETED
            } else if (hubModeTutorialState >= MIN_TUTORIAL_VERSION) {
                // Tutorial is considered "not started" if the user completed a version older than
                // the current.
                hubModeTutorialState = HUB_MODE_TUTORIAL_NOT_STARTED
            }
            val settingsState = SettingsState(hubModeTutorialState)
            logger.d({ "Communal tutorial state for user $int1 in settings: $str1" }) {
                int1 = userId
                str1 = settingsState.hubModeTutorialState.toString()
            }

            settingsState
        }

    override suspend fun setTutorialState(state: Int): Unit =
        withContext(backgroundDispatcher) {
            val userId = userRepository.getSelectedUserInfo().id
            if (tutorialSettingState.value == state) {
                return@withContext
            }
            val newState =
                if (state == HUB_MODE_TUTORIAL_COMPLETED) CURRENT_TUTORIAL_VERSION else state
            logger.d({ "Update communal tutorial state to $int1 for user $int2" }) {
                int1 = newState
                int2 = userId
            }
            secureSettings.putIntForUser(
                Settings.Secure.HUB_MODE_TUTORIAL_STATE,
                newState,
                userId,
            )
        }
}

// TODO(b/320769333): delete me and use the real repo above when tutorial is ready.
@SysUISingleton
class CommunalTutorialDisabledRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
) : CommunalTutorialRepository {
    override val tutorialSettingState: StateFlow<Int> =
        emptyFlow<Int>()
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = HUB_MODE_TUTORIAL_COMPLETED,
            )

    override suspend fun setTutorialState(state: Int) {
        // Do nothing
    }
}
