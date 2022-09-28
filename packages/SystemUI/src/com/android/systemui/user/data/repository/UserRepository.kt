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
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.appcompat.content.res.AppCompatResources
import com.android.internal.util.UserIcons
import com.android.systemui.R
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.legacyhelper.ui.LegacyUserUiHelper
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Acts as source of truth for user related data.
 *
 * Abstracts-away data sources and their schemas so the rest of the app doesn't need to worry about
 * upstream changes.
 */
interface UserRepository {
    /** List of all users on the device. */
    val users: Flow<List<UserModel>>

    /** The currently-selected user. */
    val selectedUser: Flow<UserModel>

    /** List of available user-related actions. */
    val actions: Flow<List<UserActionModel>>

    /** User switcher related settings. */
    val userSwitcherSettings: Flow<UserSwitcherSettingsModel>

    /** List of all users on the device. */
    val userInfos: Flow<List<UserInfo>>

    /** [UserInfo] of the currently-selected user. */
    val selectedUserInfo: Flow<UserInfo>

    /** User ID of the last non-guest selected user. */
    val lastSelectedNonGuestUserId: Int

    /** Whether actions are available even when locked. */
    val isActionableWhenLocked: Flow<Boolean>

    /** Whether the device is configured to always have a guest user available. */
    val isGuestUserAutoCreated: Boolean

    /** Whether the guest user is currently being reset. */
    var isGuestUserResetting: Boolean

    /** Whether we've scheduled the creation of a guest user. */
    val isGuestUserCreationScheduled: AtomicBoolean

    /** The user of the secondary service. */
    var secondaryUserId: Int

    /** Whether refresh users should be paused. */
    var isRefreshUsersPaused: Boolean

    /** Asynchronously refresh the list of users. This will cause [userInfos] to be updated. */
    fun refreshUsers()

    fun getSelectedUserInfo(): UserInfo

    fun isSimpleUserSwitcher(): Boolean
}

@SysUISingleton
class UserRepositoryImpl
@Inject
constructor(
    @Application private val appContext: Context,
    private val manager: UserManager,
    private val controller: UserSwitcherController,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val globalSettings: GlobalSettings,
    private val tracker: UserTracker,
    private val featureFlags: FeatureFlags,
) : UserRepository {

    private val isNewImpl: Boolean
        get() = !featureFlags.isEnabled(Flags.USER_INTERACTOR_AND_REPO_USE_CONTROLLER)

    private val _userSwitcherSettings = MutableStateFlow<UserSwitcherSettingsModel?>(null)
    override val userSwitcherSettings: Flow<UserSwitcherSettingsModel> =
        _userSwitcherSettings.asStateFlow().filterNotNull()

    private val _userInfos = MutableStateFlow<List<UserInfo>?>(null)
    override val userInfos: Flow<List<UserInfo>> = _userInfos.filterNotNull()

    private val _selectedUserInfo = MutableStateFlow<UserInfo?>(null)
    override val selectedUserInfo: Flow<UserInfo> = _selectedUserInfo.filterNotNull()

    override var lastSelectedNonGuestUserId: Int = UserHandle.USER_SYSTEM
        private set

    private val userRecords: Flow<List<UserRecord>> = conflatedCallbackFlow {
        fun send() {
            trySendWithFailureLogging(
                controller.users,
                TAG,
            )
        }

        val callback = UserSwitcherController.UserSwitchCallback { send() }

        controller.addUserSwitchCallback(callback)
        send()

        awaitClose { controller.removeUserSwitchCallback(callback) }
    }

    override val users: Flow<List<UserModel>> =
        userRecords.map { records -> records.filter { it.isUser() }.map { it.toUserModel() } }

    override val selectedUser: Flow<UserModel> =
        users.map { users -> users.first { user -> user.isSelected } }

    override val actions: Flow<List<UserActionModel>> =
        userRecords.map { records -> records.filter { it.isNotUser() }.map { it.toActionModel() } }

    override val isActionableWhenLocked: Flow<Boolean> =
        if (isNewImpl) {
            emptyFlow()
        } else {
            controller.isAddUsersFromLockScreenEnabled
        }

    override val isGuestUserAutoCreated: Boolean =
        if (isNewImpl) {
            appContext.resources.getBoolean(com.android.internal.R.bool.config_guestUserAutoCreated)
        } else {
            controller.isGuestUserAutoCreated
        }

    private var _isGuestUserResetting: Boolean = false
    override var isGuestUserResetting: Boolean =
        if (isNewImpl) {
            _isGuestUserResetting
        } else {
            controller.isGuestUserResetting
        }
        set(value) =
            if (isNewImpl) {
                _isGuestUserResetting = value
            } else {
                error("Not supported in the old implementation!")
            }

    override val isGuestUserCreationScheduled = AtomicBoolean()

    override var secondaryUserId: Int = UserHandle.USER_NULL

    override var isRefreshUsersPaused: Boolean = false

    init {
        if (isNewImpl) {
            observeSelectedUser()
            observeUserSettings()
        }
    }

    override fun refreshUsers() {
        applicationScope.launch {
            val result = withContext(backgroundDispatcher) { manager.aliveUsers }

            if (result != null) {
                _userInfos.value = result
            }
        }
    }

    override fun getSelectedUserInfo(): UserInfo {
        return checkNotNull(_selectedUserInfo.value)
    }

    override fun isSimpleUserSwitcher(): Boolean {
        return checkNotNull(_userSwitcherSettings.value?.isSimpleUserSwitcher)
    }

    private fun observeSelectedUser() {
        conflatedCallbackFlow {
                fun send() {
                    trySendWithFailureLogging(tracker.userInfo, TAG)
                }

                val callback =
                    object : UserTracker.Callback {
                        override fun onUserChanged(newUser: Int, userContext: Context) {
                            send()
                        }
                    }

                tracker.addCallback(callback, mainDispatcher.asExecutor())
                send()

                awaitClose { tracker.removeCallback(callback) }
            }
            .onEach {
                if (!it.isGuest) {
                    lastSelectedNonGuestUserId = it.id
                }

                _selectedUserInfo.value = it
            }
            .launchIn(applicationScope)
    }

    private fun observeUserSettings() {
        globalSettings
            .observerFlow(
                names =
                    arrayOf(
                        SETTING_SIMPLE_USER_SWITCHER,
                        Settings.Global.ADD_USERS_WHEN_LOCKED,
                        Settings.Global.USER_SWITCHER_ENABLED,
                    ),
                userId = UserHandle.USER_SYSTEM,
            )
            .onStart { emit(Unit) } // Forces an initial update.
            .map { getSettings() }
            .onEach { _userSwitcherSettings.value = it }
            .launchIn(applicationScope)
    }

    private suspend fun getSettings(): UserSwitcherSettingsModel {
        return withContext(backgroundDispatcher) {
            val isSimpleUserSwitcher =
                globalSettings.getIntForUser(
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
                    UserHandle.USER_SYSTEM,
                ) != 0

            val isAddUsersFromLockscreen =
                globalSettings.getIntForUser(
                    Settings.Global.ADD_USERS_WHEN_LOCKED,
                    0,
                    UserHandle.USER_SYSTEM,
                ) != 0

            val isUserSwitcherEnabled =
                globalSettings.getIntForUser(
                    Settings.Global.USER_SWITCHER_ENABLED,
                    0,
                    UserHandle.USER_SYSTEM,
                ) != 0

            UserSwitcherSettingsModel(
                isSimpleUserSwitcher = isSimpleUserSwitcher,
                isAddUsersFromLockscreen = isAddUsersFromLockscreen,
                isUserSwitcherEnabled = isUserSwitcherEnabled,
            )
        }
    }

    private fun UserRecord.isUser(): Boolean {
        return when {
            isAddUser -> false
            isAddSupervisedUser -> false
            isGuest -> info != null
            else -> true
        }
    }

    private fun UserRecord.isNotUser(): Boolean {
        return !isUser()
    }

    private fun UserRecord.toUserModel(): UserModel {
        return UserModel(
            id = resolveId(),
            name = getUserName(this),
            image = getUserImage(this),
            isSelected = isCurrent,
            isSelectable = isSwitchToEnabled || isGuest,
            isGuest = isGuest,
        )
    }

    private fun UserRecord.toActionModel(): UserActionModel {
        return when {
            isAddUser -> UserActionModel.ADD_USER
            isAddSupervisedUser -> UserActionModel.ADD_SUPERVISED_USER
            isGuest -> UserActionModel.ENTER_GUEST_MODE
            else -> error("Don't know how to convert to UserActionModel: $this")
        }
    }

    private fun getUserName(record: UserRecord): Text {
        val resourceId: Int? = LegacyUserUiHelper.getGuestUserRecordNameResourceId(record)
        return if (resourceId != null) {
            Text.Resource(resourceId)
        } else {
            Text.Loaded(checkNotNull(record.info).name)
        }
    }

    private fun getUserImage(record: UserRecord): Drawable {
        if (record.isGuest) {
            return checkNotNull(
                AppCompatResources.getDrawable(appContext, R.drawable.ic_account_circle)
            )
        }

        val userId = checkNotNull(record.info?.id)
        return manager.getUserIcon(userId)?.let { userSelectedIcon ->
            BitmapDrawable(userSelectedIcon)
        }
            ?: UserIcons.getDefaultUserIcon(appContext.resources, userId, /* light= */ false)
    }

    companion object {
        private const val TAG = "UserRepository"
        @VisibleForTesting const val SETTING_SIMPLE_USER_SWITCHER = "lockscreenSimpleUserSwitcher"
    }
}
