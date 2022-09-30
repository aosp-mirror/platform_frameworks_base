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

package com.android.systemui.statusbar.policy

import android.content.Context
import android.content.Intent
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.domain.interactor.GuestUserInteractor
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.user.legacyhelper.data.LegacyUserDataHelper
import com.android.systemui.user.legacyhelper.ui.LegacyUserUiHelper
import dagger.Lazy
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Implementation of [UserSwitcherController]. */
@SysUISingleton
class UserSwitcherControllerImpl
@Inject
constructor(
    @Application private val applicationContext: Context,
    flags: FeatureFlags,
    @Suppress("DEPRECATION") private val oldImpl: Lazy<UserSwitcherControllerOldImpl>,
    private val userInteractorLazy: Lazy<UserInteractor>,
    private val guestUserInteractorLazy: Lazy<GuestUserInteractor>,
    private val keyguardInteractorLazy: Lazy<KeyguardInteractor>,
    private val activityStarter: ActivityStarter,
) : UserSwitcherController {

    private val useInteractor: Boolean =
        flags.isEnabled(Flags.USER_CONTROLLER_USES_INTERACTOR) &&
            !flags.isEnabled(Flags.USER_INTERACTOR_AND_REPO_USE_CONTROLLER)
    private val _oldImpl: UserSwitcherControllerOldImpl
        get() = oldImpl.get()
    private val userInteractor: UserInteractor by lazy { userInteractorLazy.get() }
    private val guestUserInteractor: GuestUserInteractor by lazy { guestUserInteractorLazy.get() }
    private val keyguardInteractor: KeyguardInteractor by lazy { keyguardInteractorLazy.get() }

    private val callbackCompatMap =
        mutableMapOf<UserSwitcherController.UserSwitchCallback, UserInteractor.UserCallback>()

    private fun notSupported(): Nothing {
        error("Not supported in the new implementation!")
    }

    override val users: ArrayList<UserRecord>
        get() =
            if (useInteractor) {
                userInteractor.userRecords.value
            } else {
                _oldImpl.users
            }

    override val isSimpleUserSwitcher: Boolean
        get() =
            if (useInteractor) {
                userInteractor.isSimpleUserSwitcher
            } else {
                _oldImpl.isSimpleUserSwitcher
            }

    override fun init(view: View) {
        if (!useInteractor) {
            _oldImpl.init(view)
        }
    }

    override val currentUserRecord: UserRecord?
        get() =
            if (useInteractor) {
                userInteractor.selectedUserRecord.value
            } else {
                _oldImpl.currentUserRecord
            }

    override val currentUserName: String?
        get() =
            if (useInteractor) {
                currentUserRecord?.let {
                    LegacyUserUiHelper.getUserRecordName(
                        context = applicationContext,
                        record = it,
                        isGuestUserAutoCreated = userInteractor.isGuestUserAutoCreated,
                        isGuestUserResetting = userInteractor.isGuestUserResetting,
                    )
                }
            } else {
                _oldImpl.currentUserName
            }

    override fun onUserSelected(
        userId: Int,
        dialogShower: UserSwitchDialogController.DialogShower?
    ) {
        if (useInteractor) {
            userInteractor.selectUser(userId)
        } else {
            _oldImpl.onUserSelected(userId, dialogShower)
        }
    }

    override val isAddUsersFromLockScreenEnabled: Flow<Boolean>
        get() =
            if (useInteractor) {
                notSupported()
            } else {
                _oldImpl.isAddUsersFromLockScreenEnabled
            }

    override val isGuestUserAutoCreated: Boolean
        get() =
            if (useInteractor) {
                userInteractor.isGuestUserAutoCreated
            } else {
                _oldImpl.isGuestUserAutoCreated
            }

    override val isGuestUserResetting: Boolean
        get() =
            if (useInteractor) {
                userInteractor.isGuestUserResetting
            } else {
                _oldImpl.isGuestUserResetting
            }

    override fun createAndSwitchToGuestUser(
        dialogShower: UserSwitchDialogController.DialogShower?,
    ) {
        if (useInteractor) {
            notSupported()
        } else {
            _oldImpl.createAndSwitchToGuestUser(dialogShower)
        }
    }

    override fun showAddUserDialog(dialogShower: UserSwitchDialogController.DialogShower?) {
        if (useInteractor) {
            notSupported()
        } else {
            _oldImpl.showAddUserDialog(dialogShower)
        }
    }

    override fun startSupervisedUserActivity() {
        if (useInteractor) {
            notSupported()
        } else {
            _oldImpl.startSupervisedUserActivity()
        }
    }

    override fun onDensityOrFontScaleChanged() {
        if (!useInteractor) {
            _oldImpl.onDensityOrFontScaleChanged()
        }
    }

    override fun addAdapter(adapter: WeakReference<BaseUserSwitcherAdapter>) {
        if (useInteractor) {
            userInteractor.addCallback(
                object : UserInteractor.UserCallback {
                    override fun isEvictable(): Boolean {
                        return adapter.get() == null
                    }

                    override fun onUserStateChanged() {
                        adapter.get()?.notifyDataSetChanged()
                    }
                }
            )
        } else {
            _oldImpl.addAdapter(adapter)
        }
    }

    override fun onUserListItemClicked(
        record: UserRecord,
        dialogShower: UserSwitchDialogController.DialogShower?,
    ) {
        if (useInteractor) {
            if (LegacyUserDataHelper.isUser(record)) {
                userInteractor.selectUser(record.resolveId())
            } else {
                userInteractor.executeAction(LegacyUserDataHelper.toUserActionModel(record))
            }
        } else {
            _oldImpl.onUserListItemClicked(record, dialogShower)
        }
    }

    override fun removeGuestUser(guestUserId: Int, targetUserId: Int) {
        if (useInteractor) {
            userInteractor.removeGuestUser(
                guestUserId = guestUserId,
                targetUserId = targetUserId,
            )
        } else {
            _oldImpl.removeGuestUser(guestUserId, targetUserId)
        }
    }

    override fun exitGuestUser(
        guestUserId: Int,
        targetUserId: Int,
        forceRemoveGuestOnExit: Boolean
    ) {
        if (useInteractor) {
            userInteractor.exitGuestUser(guestUserId, targetUserId, forceRemoveGuestOnExit)
        } else {
            _oldImpl.exitGuestUser(guestUserId, targetUserId, forceRemoveGuestOnExit)
        }
    }

    override fun schedulePostBootGuestCreation() {
        if (useInteractor) {
            guestUserInteractor.onDeviceBootCompleted()
        } else {
            _oldImpl.schedulePostBootGuestCreation()
        }
    }

    override val isKeyguardShowing: Boolean
        get() =
            if (useInteractor) {
                keyguardInteractor.isKeyguardShowing()
            } else {
                _oldImpl.isKeyguardShowing
            }

    override fun startActivity(intent: Intent) {
        if (useInteractor) {
            activityStarter.startActivity(intent, /* dismissShade= */ false)
        } else {
            _oldImpl.startActivity(intent)
        }
    }

    override fun refreshUsers(forcePictureLoadForId: Int) {
        if (useInteractor) {
            userInteractor.refreshUsers()
        } else {
            _oldImpl.refreshUsers(forcePictureLoadForId)
        }
    }

    override fun addUserSwitchCallback(callback: UserSwitcherController.UserSwitchCallback) {
        if (useInteractor) {
            val interactorCallback =
                object : UserInteractor.UserCallback {
                    override fun onUserStateChanged() {
                        callback.onUserSwitched()
                    }
                }
            callbackCompatMap[callback] = interactorCallback
            userInteractor.addCallback(interactorCallback)
        } else {
            _oldImpl.addUserSwitchCallback(callback)
        }
    }

    override fun removeUserSwitchCallback(callback: UserSwitcherController.UserSwitchCallback) {
        if (useInteractor) {
            val interactorCallback = callbackCompatMap.remove(callback)
            if (interactorCallback != null) {
                userInteractor.removeCallback(interactorCallback)
            }
        } else {
            _oldImpl.removeUserSwitchCallback(callback)
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        if (useInteractor) {
            userInteractor.dump(pw)
        } else {
            _oldImpl.dump(pw, args)
        }
    }
}
