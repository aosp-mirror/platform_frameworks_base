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

package com.android.systemui.user.domain.interactor

import android.os.UserHandle
import android.os.UserManager
import com.android.systemui.user.data.repository.UserRepository

/** Utilities related to user management actions. */
object UserActionsUtil {

    /** Returns `true` if it's possible to add a guest user to the device; `false` otherwise. */
    fun canCreateGuest(
        manager: UserManager,
        repository: UserRepository,
        isUserSwitcherEnabled: Boolean,
        isAddUsersFromLockScreenEnabled: Boolean,
    ): Boolean {
        if (!isUserSwitcherEnabled) {
            return false
        }

        return currentUserCanCreateUsers(manager, repository) ||
            anyoneCanCreateUsers(manager, isAddUsersFromLockScreenEnabled)
    }

    /** Returns `true` if it's possible to add a user to the device; `false` otherwise. */
    fun canCreateUser(
        manager: UserManager,
        repository: UserRepository,
        isUserSwitcherEnabled: Boolean,
        isAddUsersFromLockScreenEnabled: Boolean,
    ): Boolean {
        if (!isUserSwitcherEnabled) {
            return false
        }

        if (
            !currentUserCanCreateUsers(manager, repository) &&
                !anyoneCanCreateUsers(manager, isAddUsersFromLockScreenEnabled)
        ) {
            return false
        }

        return manager.canAddMoreUsers(UserManager.USER_TYPE_FULL_SECONDARY)
    }

    /**
     * Returns `true` if it's possible to add a supervised user to the device; `false` otherwise.
     */
    fun canCreateSupervisedUser(
        manager: UserManager,
        repository: UserRepository,
        isUserSwitcherEnabled: Boolean,
        isAddUsersFromLockScreenEnabled: Boolean,
        supervisedUserPackageName: String?
    ): Boolean {
        if (supervisedUserPackageName.isNullOrEmpty()) {
            return false
        }

        return canCreateUser(
            manager,
            repository,
            isUserSwitcherEnabled,
            isAddUsersFromLockScreenEnabled
        )
    }

    fun canManageUsers(
        repository: UserRepository,
        isUserSwitcherEnabled: Boolean,
        isAddUsersFromLockScreenEnabled: Boolean,
    ): Boolean {
        return isUserSwitcherEnabled &&
            (repository.getSelectedUserInfo().isAdmin || isAddUsersFromLockScreenEnabled)
    }

    /**
     * Returns `true` if the current user is allowed to add users to the device; `false` otherwise.
     */
    private fun currentUserCanCreateUsers(
        manager: UserManager,
        repository: UserRepository,
    ): Boolean {
        val currentUser = repository.getSelectedUserInfo()
        if (!currentUser.isAdmin && currentUser.id != UserHandle.USER_SYSTEM) {
            return false
        }

        return systemCanCreateUsers(manager)
    }

    /** Returns `true` if the system can add users to the device; `false` otherwise. */
    private fun systemCanCreateUsers(
        manager: UserManager,
    ): Boolean {
        return !manager.hasBaseUserRestriction(UserManager.DISALLOW_ADD_USER, UserHandle.SYSTEM)
    }

    /** Returns `true` if it's allowed to add users to the device at all; `false` otherwise. */
    private fun anyoneCanCreateUsers(
        manager: UserManager,
        isAddUsersFromLockScreenEnabled: Boolean,
    ): Boolean {
        return systemCanCreateUsers(manager) && isAddUsersFromLockScreenEnabled
    }
}
