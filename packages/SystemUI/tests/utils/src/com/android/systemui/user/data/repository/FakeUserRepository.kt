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

import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class FakeUserRepository : UserRepository {

    private val _users = MutableStateFlow<List<UserModel>>(emptyList())
    override val users: Flow<List<UserModel>> = _users.asStateFlow()
    override val selectedUser: Flow<UserModel> =
        users.map { models -> models.first { model -> model.isSelected } }

    private val _actions = MutableStateFlow<List<UserActionModel>>(emptyList())
    override val actions: Flow<List<UserActionModel>> = _actions.asStateFlow()

    private val _isActionableWhenLocked = MutableStateFlow(false)
    override val isActionableWhenLocked: Flow<Boolean> = _isActionableWhenLocked.asStateFlow()

    private var _isGuestUserAutoCreated: Boolean = false
    override val isGuestUserAutoCreated: Boolean
        get() = _isGuestUserAutoCreated
    private var _isGuestUserResetting: Boolean = false
    override val isGuestUserResetting: Boolean
        get() = _isGuestUserResetting

    fun setUsers(models: List<UserModel>) {
        _users.value = models
    }

    fun setSelectedUser(userId: Int) {
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

    fun setGuestUserResetting(value: Boolean) {
        _isGuestUserResetting = value
    }
}
