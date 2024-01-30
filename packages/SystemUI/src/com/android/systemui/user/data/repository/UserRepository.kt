/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.user.data.repository

import android.content.Context
import android.content.pm.UserInfo
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.user.data.model.SelectedUserModel
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Acts as source of truth for user related data.
 *
 * Abstracts-away data sources and their schemas so the rest of the app doesn't need to worry about
 * upstream changes.
 */
interface UserRepository {
    /** User switcher related settings. */
    val userSwitcherSettings: Flow<UserSwitcherSettingsModel>

    /** List of all users on the device. */
    val userInfos: Flow<List<UserInfo>>

    /** Information about the currently-selected user, including [UserInfo] and other details. */
    val selectedUser: StateFlow<SelectedUserModel>

    /** [UserInfo] of the currently-selected user. */
    val selectedUserInfo: Flow<UserInfo>

    /** User ID of the main user. */
    val mainUserId: Int

    /** User ID of the last non-guest selected user. */
    val lastSelectedNonGuestUserId: Int

    /** Whether the device is configured to always have a guest user available. */
    val isGuestUserAutoCreated: Boolean

    /** Whether the guest user is currently being reset. */
    var isGuestUserResetting: Boolean

    /** Whether we've scheduled the creation of a guest user. */
    val isGuestUserCreationScheduled: AtomicBoolean

    /** Whether to enable the status bar user chip (which launches the user switcher) */
    val isStatusBarUserChipEnabled: Boolean

    /** The user of the secondary service. */
    var secondaryUserId: Int

    /** Whether refresh users should be paused. */
    var isRefreshUsersPaused: Boolean

    /** Asynchronously refresh the list of users. This will cause [userInfos] to be updated. */
    fun refreshUsers()

    fun getSelectedUserInfo(): UserInfo

    fun isSimpleUserSwitcher(): Boolean

    fun isUserSwitcherEnabled(): Boolean
}

@SysUISingleton
class UserRepositoryImpl
@Inject
constructor(
    @Application private val appContext: Context,
    private val manager: UserManager,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val globalSettings: GlobalSettings,
    private val tracker: UserTracker,
) : UserRepository {

    private val _userSwitcherSettings: StateFlow<UserSwitcherSettingsModel> =
        globalSettings
            .observerFlow(
                names =
                    arrayOf(
                        SETTING_SIMPLE_USER_SWITCHER,
                        Settings.Global.ADD_USERS_WHEN_LOCKED,
                        Settings.Global.USER_SWITCHER_ENABLED,
                    ),
            )
            .onStart { emit(Unit) } // Forces an initial update.
            .map { getSettings() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = runBlocking { getSettings() },
            )
    override val userSwitcherSettings: Flow<UserSwitcherSettingsModel> = _userSwitcherSettings

    private val _userInfos = MutableStateFlow<List<UserInfo>?>(null)
    override val userInfos: Flow<List<UserInfo>> = _userInfos.filterNotNull()

    override var mainUserId: Int = UserHandle.USER_NULL
        private set
    override var lastSelectedNonGuestUserId: Int = UserHandle.USER_NULL
        private set

    override val isGuestUserAutoCreated: Boolean =
        appContext.resources.getBoolean(com.android.internal.R.bool.config_guestUserAutoCreated)

    private var _isGuestUserResetting: Boolean = false
    override var isGuestUserResetting: Boolean = _isGuestUserResetting

    override val isGuestUserCreationScheduled = AtomicBoolean()

    override val isStatusBarUserChipEnabled: Boolean =
        appContext.resources.getBoolean(R.bool.flag_user_switcher_chip)

    override var secondaryUserId: Int = UserHandle.USER_NULL

    override var isRefreshUsersPaused: Boolean = false

    override val selectedUser: StateFlow<SelectedUserModel> = run {
        // Some callbacks don't modify the selection status, so maintain the current value.
        var currentSelectionStatus = SelectionStatus.SELECTION_COMPLETE
        conflatedCallbackFlow {
                fun send(selectionStatus: SelectionStatus) {
                    currentSelectionStatus = selectionStatus
                    trySendWithFailureLogging(
                        SelectedUserModel(tracker.userInfo, selectionStatus),
                        TAG,
                    )
                }

                val callback =
                    object : UserTracker.Callback {
                        override fun onUserChanging(newUser: Int, userContext: Context) {
                            send(SelectionStatus.SELECTION_IN_PROGRESS)
                        }

                        override fun onUserChanged(newUser: Int, userContext: Context) {
                            send(SelectionStatus.SELECTION_COMPLETE)
                        }

                        override fun onProfilesChanged(profiles: List<UserInfo>) {
                            send(currentSelectionStatus)
                        }
                    }

                tracker.addCallback(callback, backgroundDispatcher.asExecutor())
                send(currentSelectionStatus)

                awaitClose { tracker.removeCallback(callback) }
            }
            .onEach {
                if (!it.userInfo.isGuest) {
                    lastSelectedNonGuestUserId = it.userInfo.id
                }
            }
            .stateIn(
                applicationScope,
                SharingStarted.Eagerly,
                initialValue = SelectedUserModel(tracker.userInfo, currentSelectionStatus)
            )
    }

    override val selectedUserInfo: Flow<UserInfo> = selectedUser.map { it.userInfo }

    override fun refreshUsers() {
        applicationScope.launch {
            val result = withContext(backgroundDispatcher) { manager.aliveUsers }

            if (result != null) {
                _userInfos.value =
                    result
                        // Users should be sorted by ascending creation time.
                        .sortedBy { it.creationTime }
                        // The guest user is always last, regardless of creation time.
                        .sortedBy { it.isGuest }
            }

            if (mainUserId == UserHandle.USER_NULL) {
                val mainUser = withContext(backgroundDispatcher) { manager.mainUser }
                mainUser?.let { mainUserId = it.identifier }
            }
        }
    }

    override fun getSelectedUserInfo(): UserInfo {
        return selectedUser.value.userInfo
    }

    override fun isSimpleUserSwitcher(): Boolean {
        return _userSwitcherSettings.value.isSimpleUserSwitcher
    }

    override fun isUserSwitcherEnabled(): Boolean {
        return _userSwitcherSettings.value.isUserSwitcherEnabled
    }

    private suspend fun getSettings(): UserSwitcherSettingsModel {
        return withContext(backgroundDispatcher) {
            val isSimpleUserSwitcher =
                globalSettings.getInt(
                    SETTING_SIMPLE_USER_SWITCHER,
                    if (
                        appContext.resources.getBoolean(
                            com.android.internal.R.bool.config_expandLockScreenUserSwitcher
                        )
                    ) {
                        1
                    } else {
                        0
                    },
                ) != 0

            val isAddUsersFromLockscreen =
                globalSettings.getInt(
                    Settings.Global.ADD_USERS_WHEN_LOCKED,
                    0,
                ) != 0

            val isUserSwitcherEnabled =
                globalSettings.getInt(
                    Settings.Global.USER_SWITCHER_ENABLED,
                    if (
                        appContext.resources.getBoolean(
                            com.android.internal.R.bool.config_showUserSwitcherByDefault
                        )
                    ) {
                        1
                    } else {
                        0
                    },
                ) != 0

            UserSwitcherSettingsModel(
                isSimpleUserSwitcher = isSimpleUserSwitcher,
                isAddUsersFromLockscreen = isAddUsersFromLockscreen,
                isUserSwitcherEnabled = isUserSwitcherEnabled,
            )
        }
    }

    companion object {
        private const val TAG = "UserRepository"
        @VisibleForTesting const val SETTING_SIMPLE_USER_SWITCHER = "lockscreenSimpleUserSwitcher"
    }
}
