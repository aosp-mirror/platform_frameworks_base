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

import android.content.Intent
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Encapsulates business logic to interact with user data and systems. */
@SysUISingleton
class UserInteractor
@Inject
constructor(
    repository: UserRepository,
    private val controller: UserSwitcherController,
    private val activityStarter: ActivityStarter,
    keyguardInteractor: KeyguardInteractor,
) {
    /** List of current on-device users to select from. */
    val users: Flow<List<UserModel>> = repository.users

    /** The currently-selected user. */
    val selectedUser: Flow<UserModel> = repository.selectedUser

    /** List of user-switcher related actions that are available. */
    val actions: Flow<List<UserActionModel>> =
        combine(
                repository.isActionableWhenLocked,
                keyguardInteractor.isKeyguardShowing,
            ) { isActionableWhenLocked, isLocked ->
                isActionableWhenLocked || !isLocked
            }
            .flatMapLatest { isActionable ->
                if (isActionable) {
                    repository.actions.map { actions ->
                        actions +
                            if (actions.isNotEmpty()) {
                                // If we have actions, we add NAVIGATE_TO_USER_MANAGEMENT because
                                // that's a user
                                // switcher specific action that is not known to the our data source
                                // or other
                                // features.
                                listOf(UserActionModel.NAVIGATE_TO_USER_MANAGEMENT)
                            } else {
                                // If no actions, don't add the navigate action.
                                emptyList()
                            }
                    }
                } else {
                    // If not actionable it means that we're not allowed to show actions when locked
                    // and we
                    // are locked. Therefore, we should show no actions.
                    flowOf(emptyList())
                }
            }

    /** Whether the device is configured to always have a guest user available. */
    val isGuestUserAutoCreated: Boolean = repository.isGuestUserAutoCreated

    /** Whether the guest user is currently being reset. */
    val isGuestUserResetting: Boolean = repository.isGuestUserResetting

    /** Switches to the user with the given user ID. */
    fun selectUser(
        userId: Int,
    ) {
        controller.onUserSelected(userId, /* dialogShower= */ null)
    }

    /** Executes the given action. */
    fun executeAction(action: UserActionModel) {
        when (action) {
            UserActionModel.ENTER_GUEST_MODE -> controller.createAndSwitchToGuestUser(null)
            UserActionModel.ADD_USER -> controller.showAddUserDialog(null)
            UserActionModel.ADD_SUPERVISED_USER -> controller.startSupervisedUserActivity()
            UserActionModel.NAVIGATE_TO_USER_MANAGEMENT ->
                activityStarter.startActivity(
                    Intent(Settings.ACTION_USER_SETTINGS),
                    /* dismissShade= */ false,
                )
        }
    }
}
