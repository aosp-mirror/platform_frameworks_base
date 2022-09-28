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

import android.content.pm.UserInfo
import android.os.UserHandle
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield

class FakeUserRepository : UserRepository {

    private val _users = MutableStateFlow<List<UserModel>>(emptyList())
    override val users: Flow<List<UserModel>> = _users.asStateFlow()
    override val selectedUser: Flow<UserModel> =
        users.map { models -> models.first { model -> model.isSelected } }

    private val _actions = MutableStateFlow<List<UserActionModel>>(emptyList())
    override val actions: Flow<List<UserActionModel>> = _actions.asStateFlow()

    private val _userSwitcherSettings = MutableStateFlow(UserSwitcherSettingsModel())
    override val userSwitcherSettings: Flow<UserSwitcherSettingsModel> =
        _userSwitcherSettings.asStateFlow()

    private val _userInfos = MutableStateFlow<List<UserInfo>>(emptyList())
    override val userInfos: Flow<List<UserInfo>> = _userInfos.asStateFlow()

    private val _selectedUserInfo = MutableStateFlow<UserInfo?>(null)
    override val selectedUserInfo: Flow<UserInfo> = _selectedUserInfo.filterNotNull()

    override var lastSelectedNonGuestUserId: Int = UserHandle.USER_SYSTEM

    private val _isActionableWhenLocked = MutableStateFlow(false)
    override val isActionableWhenLocked: Flow<Boolean> = _isActionableWhenLocked.asStateFlow()

    private var _isGuestUserAutoCreated: Boolean = false
    override val isGuestUserAutoCreated: Boolean
        get() = _isGuestUserAutoCreated

    override var isGuestUserResetting: Boolean = false

    override val isGuestUserCreationScheduled = AtomicBoolean()

    override var secondaryUserId: Int = UserHandle.USER_NULL

    override var isRefreshUsersPaused: Boolean = false

    var refreshUsersCallCount: Int = 0
        private set

    override fun refreshUsers() {
        refreshUsersCallCount++
    }

    override fun getSelectedUserInfo(): UserInfo {
        return checkNotNull(_selectedUserInfo.value)
    }

    override fun isSimpleUserSwitcher(): Boolean {
        return _userSwitcherSettings.value.isSimpleUserSwitcher
    }

    fun setUserInfos(infos: List<UserInfo>) {
        _userInfos.value = infos
    }

    suspend fun setSelectedUserInfo(userInfo: UserInfo) {
        check(_userInfos.value.contains(userInfo)) {
            "Cannot select the following user, it is not in the list of user infos: $userInfo!"
        }

        _selectedUserInfo.value = userInfo
        yield()
    }

    suspend fun setSettings(settings: UserSwitcherSettingsModel) {
        _userSwitcherSettings.value = settings
        yield()
    }

    fun setUsers(models: List<UserModel>) {
        _users.value = models
    }

    suspend fun setSelectedUser(userId: Int) {
        check(_users.value.find { it.id == userId } != null) {
            "Cannot select a user with ID $userId - no user with that ID found!"
        }

        setUsers(
            _users.value.map { model ->
                when {
                    model.isSelected && model.id != userId -> model.copy(isSelected = false)
                    !model.isSelected && model.id == userId -> model.copy(isSelected = true)
                    else -> model
                }
            }
        )
        yield()
    }

    fun setActions(models: List<UserActionModel>) {
        _actions.value = models
    }

    fun setActionableWhenLocked(value: Boolean) {
        _isActionableWhenLocked.value = value
    }

    fun setGuestUserAutoCreated(value: Boolean) {
        _isGuestUserAutoCreated = value
    }
}
