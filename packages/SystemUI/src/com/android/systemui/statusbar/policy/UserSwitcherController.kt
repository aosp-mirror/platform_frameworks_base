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

import android.annotation.UserIdInt
import android.content.Intent
import android.view.View
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin
import com.android.systemui.Dumpable
import com.android.systemui.qs.user.UserSwitchDialogController.DialogShower
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.legacyhelper.ui.LegacyUserUiHelper
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.Flow

/** Defines interface for a class that provides user switching functionality and state. */
interface UserSwitcherController : Dumpable {

    /** The current list of [UserRecord]. */
    val users: ArrayList<UserRecord>

    /** Whether the user switcher experience should use the simple experience. */
    val isSimpleUserSwitcher: Boolean

    /** Require a view for jank detection */
    fun init(view: View)

    /** The [UserRecord] of the current user or `null` when none. */
    val currentUserRecord: UserRecord?

    /** The name of the current user of the device or `null`, when none is selected. */
    val currentUserName: String?

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
    fun onUserSelected(userId: Int, dialogShower: DialogShower?)

    /** Whether it is allowed to add users while the device is locked. */
    val isAddUsersFromLockScreenEnabled: Flow<Boolean>

    /** Whether the guest user is configured to always be present on the device. */
    val isGuestUserAutoCreated: Boolean

    /** Whether the guest user is currently being reset. */
    val isGuestUserResetting: Boolean

    /** Creates and switches to the guest user. */
    fun createAndSwitchToGuestUser(dialogShower: DialogShower?)

    /** Shows the add user dialog. */
    fun showAddUserDialog(dialogShower: DialogShower?)

    /** Starts an activity to add a supervised user to the device. */
    fun startSupervisedUserActivity()

    /** Notifies when the display density or font scale has changed. */
    fun onDensityOrFontScaleChanged()

    /** Registers an adapter to notify when the users change. */
    fun addAdapter(adapter: WeakReference<BaseUserSwitcherAdapter>)

    /** Notifies the item for a user has been clicked. */
    fun onUserListItemClicked(record: UserRecord, dialogShower: DialogShower?)

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
     * `UserHandle.USER_NULL`, then switch immediately to the newly created guest user.
     */
    fun removeGuestUser(@UserIdInt guestUserId: Int, @UserIdInt targetUserId: Int)

    /**
     * Exits guest user and switches to previous non-guest user. The guest must be the current user.
     *
     * @param guestUserId user id of the guest user to exit
     * @param targetUserId user id of the guest user to exit, set to UserHandle#USER_NULL when
     * target user id is not known
     * @param forceRemoveGuestOnExit true: remove guest before switching user, false: remove guest
     * only if its ephemeral, else keep guest
     */
    fun exitGuestUser(
        @UserIdInt guestUserId: Int,
        @UserIdInt targetUserId: Int,
        forceRemoveGuestOnExit: Boolean
    )

    /**
     * Guarantee guest is present only if the device is provisioned. Otherwise, create a content
     * observer to wait until the device is provisioned, then schedule the guest creation.
     */
    fun schedulePostBootGuestCreation()

    /** Whether keyguard is showing. */
    val isKeyguardShowing: Boolean

    /** Returns the [EnforcedAdmin] for the given record, or `null` if there isn't one. */
    fun getEnforcedAdmin(record: UserRecord): EnforcedAdmin?

    /** Returns `true` if the given record is disabled by the admin; `false` otherwise. */
    fun isDisabledByAdmin(record: UserRecord): Boolean

    /** Starts an activity with the given [Intent]. */
    fun startActivity(intent: Intent)

    /**
     * Refreshes users from UserManager.
     *
     * The pictures are only loaded if they have not been loaded yet.
     *
     * @param forcePictureLoadForId forces the picture of the given user to be reloaded.
     */
    fun refreshUsers(forcePictureLoadForId: Int)

    /** Adds a subscriber to when user switches. */
    fun addUserSwitchCallback(callback: UserSwitchCallback)

    /** Removes a previously-added subscriber. */
    fun removeUserSwitchCallback(callback: UserSwitchCallback)

    /** Defines interface for classes that can be called back when the user is switched. */
    fun interface UserSwitchCallback {
        /** Notifies that the user has switched. */
        fun onUserSwitched()
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
