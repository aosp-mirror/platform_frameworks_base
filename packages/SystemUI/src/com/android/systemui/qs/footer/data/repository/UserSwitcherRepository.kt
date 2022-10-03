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
 */

package com.android.systemui.qs.footer.data.repository

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.UserManager
import android.provider.Settings.Global.USER_SWITCHER_ENABLED
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.SettingObserver
import com.android.systemui.qs.footer.data.model.UserSwitcherStatusModel
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.util.settings.GlobalSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface UserSwitcherRepository {
    /** The current [UserSwitcherStatusModel]. */
    val userSwitcherStatus: Flow<UserSwitcherStatusModel>
}

@SysUISingleton
class UserSwitcherRepositoryImpl
@Inject
constructor(
    @Application private val context: Context,
    @Background private val bgHandler: Handler,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val userManager: UserManager,
    private val userTracker: UserTracker,
    private val userSwitcherController: UserSwitcherController,
    private val userInfoController: UserInfoController,
    private val globalSetting: GlobalSettings,
) : UserSwitcherRepository {
    private val showUserSwitcherForSingleUser =
        context.resources.getBoolean(R.bool.qs_show_user_switcher_for_single_user)

    /** Whether the user switcher is currently enabled. */
    private val isEnabled: Flow<Boolean> = conflatedCallbackFlow {
        suspend fun updateState() {
            trySendWithFailureLogging(isUserSwitcherEnabled(), TAG)
        }

        val observer =
            object :
                SettingObserver(
                    globalSetting,
                    bgHandler,
                    USER_SWITCHER_ENABLED,
                    userTracker.userId,
                ) {
                override fun handleValueChanged(value: Int, observedChange: Boolean) {
                    if (observedChange) {
                        launch { updateState() }
                    }
                }
            }

        observer.isListening = true
        updateState()
        awaitClose { observer.isListening = false }
    }

    /** The current user name. */
    private val currentUserName: Flow<String?> = conflatedCallbackFlow {
        suspend fun updateState() {
            trySendWithFailureLogging(getCurrentUser(), TAG)
        }

        val callback = UserSwitcherController.UserSwitchCallback { launch { updateState() } }

        userSwitcherController.addUserSwitchCallback(callback)
        updateState()
        awaitClose { userSwitcherController.removeUserSwitchCallback(callback) }
    }

    /** The current (icon, isGuestUser) values. */
    // TODO(b/242040009): Could we only use this callback to get the user name and remove
    // currentUsername above?
    private val currentUserInfo: Flow<Pair<Drawable?, Boolean>> = conflatedCallbackFlow {
        val listener =
            UserInfoController.OnUserInfoChangedListener { _, picture, _ ->
                launch { trySendWithFailureLogging(picture to isGuestUser(), TAG) }
            }

        // This will automatically call the listener when attached, so no need to update the state
        // here.
        userInfoController.addCallback(listener)
        awaitClose { userInfoController.removeCallback(listener) }
    }

    override val userSwitcherStatus: Flow<UserSwitcherStatusModel> =
        isEnabled
            .flatMapLatest { enabled ->
                if (enabled) {
                    combine(currentUserName, currentUserInfo) { name, (icon, isGuest) ->
                        UserSwitcherStatusModel.Enabled(name, icon, isGuest)
                    }
                } else {
                    flowOf(UserSwitcherStatusModel.Disabled)
                }
            }
            .distinctUntilChanged()

    private suspend fun isUserSwitcherEnabled(): Boolean {
        return withContext(bgDispatcher) {
            userManager.isUserSwitcherEnabled(showUserSwitcherForSingleUser)
        }
    }

    private suspend fun getCurrentUser(): String? {
        return withContext(bgDispatcher) { userSwitcherController.currentUserName }
    }

    private suspend fun isGuestUser(): Boolean {
        return withContext(bgDispatcher) {
            userManager.isGuestUser(KeyguardUpdateMonitor.getCurrentUser())
        }
    }

    companion object {
        private const val TAG = "UserSwitcherRepositoryImpl"
    }
}
