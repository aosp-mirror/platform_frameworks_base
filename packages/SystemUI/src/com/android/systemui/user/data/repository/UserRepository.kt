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

import android.annotation.SuppressLint
import android.annotation.UserIdInt
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.IntentFilter
import android.content.pm.UserInfo
import android.content.res.Resources
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.broadcast.BroadcastDispatcher
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
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
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

    /** Whether logout for secondary users is enabled by admin device policy. */
    val isSecondaryUserLogoutEnabled: StateFlow<Boolean>

    /** Whether logout into system user is enabled. */
    val isLogoutToSystemUserEnabled: StateFlow<Boolean>

    /** Asynchronously refresh the list of users. This will cause [userInfos] to be updated. */
    fun refreshUsers()

    fun getSelectedUserInfo(): UserInfo

    fun isSimpleUserSwitcher(): Boolean

    fun isUserSwitcherEnabled(): Boolean

    /** Performs logout logout for secondary users. */
    suspend fun logOutSecondaryUser()

    /** Performs logout into the system user. */
    suspend fun logOutToSystemUser()

    /**
     * Returns the user ID of the "main user" of the device. This user may have access to certain
     * features which are limited to at most one user. There will never be more than one main user
     * on a device.
     *
     * <p>Currently, on most form factors the first human user on the device will be the main user;
     * in the future, the concept may be transferable, so a different user (or even no user at all)
     * may be designated the main user instead. On other form factors there might not be a main
     * user.
     *
     * <p> When the device doesn't have a main user, this will return {@code null}.
     *
     * @see [UserManager.getMainUser]
     */
    @UserIdInt suspend fun getMainUserId(): Int?
}

@SysUISingleton
class UserRepositoryImpl
@Inject
constructor(
    @Application private val appContext: Context,
    @Main private val resources: Resources,
    private val manager: UserManager,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val globalSettings: GlobalSettings,
    private val tracker: UserTracker,
    private val devicePolicyManager: DevicePolicyManager,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val statusBarService: IStatusBarService,
) : UserRepository {

    private val _userSwitcherSettings: StateFlow<UserSwitcherSettingsModel> =
        globalSettings
            .observerFlow(
                names =
                    arrayOf(
                        SETTING_SIMPLE_USER_SWITCHER,
                        Settings.Global.ADD_USERS_WHEN_LOCKED,
                        Settings.Global.USER_SWITCHER_ENABLED,
                    )
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
                        override fun onBeforeUserSwitching(newUser: Int) {
                            send(SelectionStatus.SELECTION_IN_PROGRESS)
                        }

                        override fun onUserChanged(newUser: Int, userContext: Context) {
                            send(SelectionStatus.SELECTION_COMPLETE)
                        }

                        override fun onProfilesChanged(profiles: List<UserInfo>) {
                            send(currentSelectionStatus)
                        }
                    }

                tracker.addCallback(callback, mainDispatcher.asExecutor())
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
                initialValue = SelectedUserModel(tracker.userInfo, currentSelectionStatus),
            )
    }

    override val selectedUserInfo: Flow<UserInfo> = selectedUser.map { it.userInfo }

    /** Whether the secondary user logout is enabled by the admin device policy. */
    private val isSecondaryUserLogoutSupported: Flow<Boolean> =
        broadcastDispatcher
            .broadcastFlow(
                filter =
                    IntentFilter(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED)
            ) { intent, _ ->
                if (
                    DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED == intent.action
                ) {
                    Unit
                } else {
                    null
                }
            }
            .filterNotNull()
            .onStart { emit(Unit) }
            .map { _ -> devicePolicyManager.isLogoutEnabled() }
            .flowOn(backgroundDispatcher)

    @SuppressLint("MissingPermission")
    override val isSecondaryUserLogoutEnabled: StateFlow<Boolean> =
        selectedUser
            .flatMapLatestConflated { selectedUser ->
                if (selectedUser.isEligibleForLogout()) {
                    isSecondaryUserLogoutSupported
                } else {
                    flowOf(false)
                }
            }
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    @SuppressLint("MissingPermission")
    override val isLogoutToSystemUserEnabled: StateFlow<Boolean> =
        selectedUser
            .flatMapLatestConflated { selectedUser ->
                if (selectedUser.isEligibleForLogout()) {
                    flowOf(
                        resources.getBoolean(R.bool.config_userSwitchingMustGoThroughLoginScreen)
                    )
                } else {
                    flowOf(false)
                }
            }
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    @SuppressLint("MissingPermission")
    override suspend fun logOutSecondaryUser() {
        if (isSecondaryUserLogoutEnabled.value) {
            withContext(backgroundDispatcher) { devicePolicyManager.logoutUser() }
        }
    }

    override suspend fun logOutToSystemUser() {
        // TODO(b/377493351) : start using proper logout API once it is available.
        // Using reboot is a temporary solution.
        if (isLogoutToSystemUserEnabled.value) {
            withContext(backgroundDispatcher) { statusBarService.reboot(false) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun refreshUsers() {
        applicationScope.launch {
            _userInfos.value =
                withContext(backgroundDispatcher) { manager.aliveUsers }
                    // Users should be sorted by ascending creation time.
                    .sortedBy { it.creationTime }
                    // The guest user is always last, regardless of creation time.
                    .sortedBy { it.isGuest }

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

    override suspend fun getMainUserId(): Int? {
        return withContext(backgroundDispatcher) { manager.mainUser?.identifier }
    }

    private suspend fun getSettings(): UserSwitcherSettingsModel {
        return withContext(backgroundDispatcher) {
            if (
                // TODO(b/378068979): remove once login screen-specific logic
                // is implemented at framework level.
                appContext.resources.getBoolean(R.bool.config_userSwitchingMustGoThroughLoginScreen)
            ) {
                UserSwitcherSettingsModel(
                    isSimpleUserSwitcher = false,
                    isAddUsersFromLockscreen = false,
                    isUserSwitcherEnabled = false,
                )
            } else {
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
                    globalSettings.getInt(Settings.Global.ADD_USERS_WHEN_LOCKED, 0) != 0

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
    }

    companion object {
        private const val TAG = "UserRepository"
        @VisibleForTesting const val SETTING_SIMPLE_USER_SWITCHER = "lockscreenSimpleUserSwitcher"
    }
}

fun SelectedUserModel.isEligibleForLogout(): Boolean {
    // TODO(b/206032495): should call mDevicePolicyManager.getLogoutUserId() instead of
    // hardcode it to USER_SYSTEM so it properly supports headless system user mode
    // (and then call mDevicePolicyManager.clearLogoutUser() after switched)
    return selectionStatus == SelectionStatus.SELECTION_COMPLETE &&
        userInfo.id != android.os.UserHandle.USER_SYSTEM
}
