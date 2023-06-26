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
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.user.UserSwitchDialogController.DialogShower
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.domain.interactor.GuestUserInteractor
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.user.legacyhelper.ui.LegacyUserUiHelper
import dagger.Lazy
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject

/** Access point into multi-user switching logic. */
@Deprecated("Use UserInteractor or GuestUserInteractor instead.")
@SysUISingleton
class UserSwitcherController
@Inject
constructor(
    @Application private val applicationContext: Context,
    private val userInteractorLazy: Lazy<UserInteractor>,
    private val guestUserInteractorLazy: Lazy<GuestUserInteractor>,
    private val keyguardInteractorLazy: Lazy<KeyguardInteractor>,
    private val activityStarter: ActivityStarter,
) {

    /** Defines interface for classes that can be called back when the user is switched. */
    fun interface UserSwitchCallback {
        /** Notifies that the user has switched. */
        fun onUserSwitched()
    }

    private val userInteractor: UserInteractor by lazy { userInteractorLazy.get() }
    private val guestUserInteractor: GuestUserInteractor by lazy { guestUserInteractorLazy.get() }
    private val keyguardInteractor: KeyguardInteractor by lazy { keyguardInteractorLazy.get() }

    private val callbackCompatMap = mutableMapOf<UserSwitchCallback, UserInteractor.UserCallback>()

    /** The current list of [UserRecord]. */
    val users: ArrayList<UserRecord>
        get() = userInteractor.userRecords.value

    /** Whether the user switcher experience should use the simple experience. */
    val isSimpleUserSwitcher: Boolean
        get() = userInteractor.isSimpleUserSwitcher

    /** The [UserRecord] of the current user or `null` when none. */
    val currentUserRecord: UserRecord?
        get() = userInteractor.selectedUserRecord.value

    /** The name of the current user of the device or `null`, when none is selected. */
    val currentUserName: String?
        get() =
            currentUserRecord?.let {
                LegacyUserUiHelper.getUserRecordName(
                    context = applicationContext,
                    record = it,
                    isGuestUserAutoCreated = userInteractor.isGuestUserAutoCreated,
                    isGuestUserResetting = userInteractor.isGuestUserResetting,
                )
            }

    /**
     * Notifies that a user has been selected.
     *
     * This will trigger the right user journeys to create a guest user, switch users, and/or
     * navigate to the correct destination.
     *
     * If a user with the given ID is not found, this method is a no-op.
     *
     * @param userId The ID of the user to switch to.
     * @param dialogShower An optional [DialogShower] in case we need to show dialogs.
     */
    fun onUserSelected(userId: Int, dialogShower: DialogShower?) {
        userInteractor.selectUser(userId, dialogShower)
    }

    /** Whether the guest user is configured to always be present on the device. */
    val isGuestUserAutoCreated: Boolean
        get() = userInteractor.isGuestUserAutoCreated

    /** Whether the guest user is currently being reset. */
    val isGuestUserResetting: Boolean
        get() = userInteractor.isGuestUserResetting

    /** Registers an adapter to notify when the users change. */
    fun addAdapter(adapter: WeakReference<BaseUserSwitcherAdapter>) {
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
    }

    /** Notifies the item for a user has been clicked. */
    fun onUserListItemClicked(
        record: UserRecord,
        dialogShower: DialogShower?,
    ) {
        userInteractor.onRecordSelected(record, dialogShower)
    }

    /**
     * Removes guest user and switches to target user. The guest must be the current user and its id
     * must be `guestUserId`.
     *
     * If `targetUserId` is `UserHandle.USER_NULL`, then create a new guest user in the foreground,
     * and immediately switch to it. This is used for wiping the current guest and replacing it with
     * a new one.
     *
     * If `targetUserId` is specified, then remove the guest in the background while switching to
     * `targetUserId`.
     *
     * If device is configured with `config_guestUserAutoCreated`, then after guest user is removed,
     * a new one is created in the background. This has no effect if `targetUserId` is
     * `UserHandle.USER_NULL`.
     *
     * @param guestUserId id of the guest user to remove
     * @param targetUserId id of the user to switch to after guest is removed. If
     *   `UserHandle.USER_NULL`, then switch immediately to the newly created guest user.
     */
    fun removeGuestUser(guestUserId: Int, targetUserId: Int) {
        userInteractor.removeGuestUser(
            guestUserId = guestUserId,
            targetUserId = targetUserId,
        )
    }

    /**
     * Exits guest user and switches to previous non-guest user. The guest must be the current user.
     *
     * @param guestUserId user id of the guest user to exit
     * @param targetUserId user id of the guest user to exit, set to UserHandle#USER_NULL when
     *   target user id is not known
     * @param forceRemoveGuestOnExit true: remove guest before switching user, false: remove guest
     *   only if its ephemeral, else keep guest
     */
    fun exitGuestUser(guestUserId: Int, targetUserId: Int, forceRemoveGuestOnExit: Boolean) {
        userInteractor.exitGuestUser(guestUserId, targetUserId, forceRemoveGuestOnExit)
    }

    /**
     * Guarantee guest is present only if the device is provisioned. Otherwise, create a content
     * observer to wait until the device is provisioned, then schedule the guest creation.
     */
    fun schedulePostBootGuestCreation() {
        guestUserInteractor.onDeviceBootCompleted()
    }

    /** Whether keyguard is showing. */
    val isKeyguardShowing: Boolean
        get() = keyguardInteractor.isKeyguardShowing()

    /** Starts an activity with the given [Intent]. */
    fun startActivity(intent: Intent) {
        activityStarter.startActivity(intent, /* dismissShade= */ true)
    }

    /**
     * Refreshes users from UserManager.
     *
     * The pictures are only loaded if they have not been loaded yet.
     */
    fun refreshUsers() {
        userInteractor.refreshUsers()
    }

    /** Adds a subscriber to when user switches. */
    fun addUserSwitchCallback(callback: UserSwitchCallback) {
        val interactorCallback =
            object : UserInteractor.UserCallback {
                override fun onUserStateChanged() {
                    callback.onUserSwitched()
                }
            }
        callbackCompatMap[callback] = interactorCallback
        userInteractor.addCallback(interactorCallback)
    }

    /** Removes a previously-added subscriber. */
    fun removeUserSwitchCallback(callback: UserSwitchCallback) {
        val interactorCallback = callbackCompatMap.remove(callback)
        if (interactorCallback != null) {
            userInteractor.removeCallback(interactorCallback)
        }
    }

    fun dump(pw: PrintWriter, args: Array<out String>) {
        userInteractor.dump(pw)
    }

    companion object {
        /** Alpha value to apply to a user view in the user switcher when it's selectable. */
        private const val ENABLED_ALPHA =
            LegacyUserUiHelper.USER_SWITCHER_USER_VIEW_SELECTABLE_ALPHA

        /** Alpha value to apply to a user view in the user switcher when it's not selectable. */
        private const val DISABLED_ALPHA =
            LegacyUserUiHelper.USER_SWITCHER_USER_VIEW_NOT_SELECTABLE_ALPHA

        @JvmStatic
        fun setSelectableAlpha(view: View) {
            view.alpha =
                if (view.isEnabled) {
                    ENABLED_ALPHA
                } else {
                    DISABLED_ALPHA
                }
        }
    }
}
