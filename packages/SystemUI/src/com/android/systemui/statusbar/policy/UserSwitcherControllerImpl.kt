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

import android.content.Intent
import android.view.View
import com.android.settingslib.RestrictedLockUtils
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.user.data.source.UserRecord
import dagger.Lazy
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Implementation of [UserSwitcherController]. */
class UserSwitcherControllerImpl
@Inject
constructor(
    private val flags: FeatureFlags,
    @Suppress("DEPRECATION") private val oldImpl: Lazy<UserSwitcherControllerOldImpl>,
) : UserSwitcherController {

    private val isNewImpl: Boolean
        get() = flags.isEnabled(Flags.REFACTORED_USER_SWITCHER_CONTROLLER)
    private val _oldImpl: UserSwitcherControllerOldImpl
        get() = oldImpl.get()

    private fun notYetImplemented(): Nothing {
        error("Not yet implemented!")
    }

    override val users: ArrayList<UserRecord>
        get() =
            if (isNewImpl) {
                notYetImplemented()
            } else {
                _oldImpl.users
            }

    override val isSimpleUserSwitcher: Boolean
        get() =
            if (isNewImpl) {
                notYetImplemented()
            } else {
                _oldImpl.isSimpleUserSwitcher
            }

    override fun init(view: View) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.init(view)
        }
    }

    override val currentUserRecord: UserRecord?
        get() =
            if (isNewImpl) {
                notYetImplemented()
            } else {
                _oldImpl.currentUserRecord
            }

    override val currentUserName: String?
        get() =
            if (isNewImpl) {
                notYetImplemented()
            } else {
                _oldImpl.currentUserName
            }

    override fun onUserSelected(
        userId: Int,
        dialogShower: UserSwitchDialogController.DialogShower?
    ) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.onUserSelected(userId, dialogShower)
        }
    }

    override val isAddUsersFromLockScreenEnabled: Flow<Boolean>
        get() =
            if (isNewImpl) {
                notYetImplemented()
            } else {
                _oldImpl.isAddUsersFromLockScreenEnabled
            }

    override val isGuestUserAutoCreated: Boolean
        get() =
            if (isNewImpl) {
                notYetImplemented()
            } else {
                _oldImpl.isGuestUserAutoCreated
            }

    override val isGuestUserResetting: Boolean
        get() =
            if (isNewImpl) {
                notYetImplemented()
            } else {
                _oldImpl.isGuestUserResetting
            }

    override fun createAndSwitchToGuestUser(
        dialogShower: UserSwitchDialogController.DialogShower?,
    ) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.createAndSwitchToGuestUser(dialogShower)
        }
    }

    override fun showAddUserDialog(dialogShower: UserSwitchDialogController.DialogShower?) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.showAddUserDialog(dialogShower)
        }
    }

    override fun startSupervisedUserActivity() {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.startSupervisedUserActivity()
        }
    }

    override fun onDensityOrFontScaleChanged() {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.onDensityOrFontScaleChanged()
        }
    }

    override fun addAdapter(adapter: WeakReference<BaseUserSwitcherAdapter>) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.addAdapter(adapter)
        }
    }

    override fun onUserListItemClicked(
        record: UserRecord,
        dialogShower: UserSwitchDialogController.DialogShower?,
    ) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.onUserListItemClicked(record, dialogShower)
        }
    }

    override fun removeGuestUser(guestUserId: Int, targetUserId: Int) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.removeGuestUser(guestUserId, targetUserId)
        }
    }

    override fun exitGuestUser(
        guestUserId: Int,
        targetUserId: Int,
        forceRemoveGuestOnExit: Boolean
    ) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.exitGuestUser(guestUserId, targetUserId, forceRemoveGuestOnExit)
        }
    }

    override fun schedulePostBootGuestCreation() {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.schedulePostBootGuestCreation()
        }
    }

    override val isKeyguardShowing: Boolean
        get() =
            if (isNewImpl) {
                notYetImplemented()
            } else {
                _oldImpl.isKeyguardShowing
            }

    override fun getEnforcedAdmin(record: UserRecord): RestrictedLockUtils.EnforcedAdmin? {
        return if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.getEnforcedAdmin(record)
        }
    }

    override fun isDisabledByAdmin(record: UserRecord): Boolean {
        return if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.isDisabledByAdmin(record)
        }
    }

    override fun startActivity(intent: Intent) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.startActivity(intent)
        }
    }

    override fun refreshUsers(forcePictureLoadForId: Int) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.refreshUsers(forcePictureLoadForId)
        }
    }

    override fun addUserSwitchCallback(callback: UserSwitcherController.UserSwitchCallback) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.addUserSwitchCallback(callback)
        }
    }

    override fun removeUserSwitchCallback(callback: UserSwitcherController.UserSwitchCallback) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.removeUserSwitchCallback(callback)
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        if (isNewImpl) {
            notYetImplemented()
        } else {
            _oldImpl.dump(pw, args)
        }
    }
}
