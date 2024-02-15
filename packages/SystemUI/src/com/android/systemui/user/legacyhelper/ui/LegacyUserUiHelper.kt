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

package com.android.systemui.user.legacyhelper.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.android.systemui.res.R
import com.android.systemui.user.data.source.UserRecord

/**
 * Defines utility functions for helping with legacy UI code for users.
 *
 * We need these to avoid code duplication between logic inside the UserSwitcherController and in
 * modern architecture classes such as repositories, interactors, and view-models. If we ever
 * simplify UserSwitcherController (or delete it), the code here could be moved into its call-sites.
 */
object LegacyUserUiHelper {

    @JvmStatic
    @DrawableRes
    fun getUserSwitcherActionIconResourceId(
        isAddUser: Boolean,
        isGuest: Boolean,
        isAddSupervisedUser: Boolean,
        isTablet: Boolean = false,
        isManageUsers: Boolean,
    ): Int {
        return if (isAddUser && isTablet) {
            com.android.settingslib.R.drawable.ic_account_circle_filled
        } else if (isAddUser) {
            R.drawable.ic_add
        } else if (isGuest) {
            com.android.settingslib.R.drawable.ic_account_circle
        } else if (isAddSupervisedUser) {
            com.android.settingslib.R.drawable.ic_add_supervised_user
        } else if (isManageUsers) {
            R.drawable.ic_manage_users
        } else {
            R.drawable.ic_avatar_user
        }
    }

    @JvmStatic
    fun getUserRecordName(
        context: Context,
        record: UserRecord,
        isGuestUserAutoCreated: Boolean,
        isGuestUserResetting: Boolean,
        isTablet: Boolean = false,
    ): String {
        val resourceId: Int? = getGuestUserRecordNameResourceId(record)
        return when {
            resourceId != null -> context.getString(resourceId)
            record.info != null -> checkNotNull(record.info.name)
            else ->
                context.getString(
                    getUserSwitcherActionTextResourceId(
                        isGuest = record.isGuest,
                        isGuestUserAutoCreated = isGuestUserAutoCreated,
                        isGuestUserResetting = isGuestUserResetting,
                        isAddUser = record.isAddUser,
                        isAddSupervisedUser = record.isAddSupervisedUser,
                        isTablet = isTablet,
                        isManageUsers = record.isManageUsers,
                    )
                )
        }
    }

    /**
     * Returns the resource ID for a string for the name of the guest user.
     *
     * If the given record is not the guest user, returns `null`.
     */
    @StringRes
    fun getGuestUserRecordNameResourceId(record: UserRecord): Int? {
        return when {
            record.isGuest && record.isCurrent ->
                com.android.settingslib.R.string.guest_exit_quick_settings_button
            record.isGuest && record.info != null -> com.android.internal.R.string.guest_name
            else -> null
        }
    }

    @JvmStatic
    @StringRes
    fun getUserSwitcherActionTextResourceId(
        isGuest: Boolean,
        isGuestUserAutoCreated: Boolean,
        isGuestUserResetting: Boolean,
        isAddUser: Boolean,
        isAddSupervisedUser: Boolean,
        isTablet: Boolean = false,
        isManageUsers: Boolean,
    ): Int {
        check(isGuest || isAddUser || isAddSupervisedUser || isManageUsers)

        return when {
            isGuest && isGuestUserAutoCreated && isGuestUserResetting ->
                com.android.settingslib.R.string.guest_resetting
            isGuest && isTablet -> com.android.settingslib.R.string.guest_new_guest
            isGuest && isGuestUserAutoCreated -> com.android.internal.R.string.guest_name
            isGuest -> com.android.internal.R.string.guest_name
            isAddUser -> com.android.settingslib.R.string.user_add_user
            isAddSupervisedUser -> R.string.add_user_supervised
            isManageUsers -> R.string.manage_users
            else -> error("This should never happen!")
        }
    }

    /** Alpha value to apply to a user view in the user switcher when it's selectable. */
    const val USER_SWITCHER_USER_VIEW_SELECTABLE_ALPHA = 1.0f

    /** Alpha value to apply to a user view in the user switcher when it's not selectable. */
    const val USER_SWITCHER_USER_VIEW_NOT_SELECTABLE_ALPHA = 0.38f
}
