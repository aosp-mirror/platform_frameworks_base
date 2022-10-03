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
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserManager
import androidx.appcompat.content.res.AppCompatResources
import com.android.internal.util.UserIcons
import com.android.systemui.R
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.legacyhelper.ui.LegacyUserUiHelper
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    /** Whether actions are available even when locked. */
    val isActionableWhenLocked: Flow<Boolean>

    /** Whether the device is configured to always have a guest user available. */
    val isGuestUserAutoCreated: Boolean

    /** Whether the guest user is currently being reset. */
    val isGuestUserResetting: Boolean
}

@SysUISingleton
class UserRepositoryImpl
@Inject
constructor(
    @Application private val appContext: Context,
    private val manager: UserManager,
    controller: UserSwitcherController,
) : UserRepository {

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

    override val isActionableWhenLocked: Flow<Boolean> = controller.isAddUsersFromLockScreenEnabled

    override val isGuestUserAutoCreated: Boolean = controller.isGuestUserAutoCreated

    override val isGuestUserResetting: Boolean = controller.isGuestUserResetting

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
    }
}
