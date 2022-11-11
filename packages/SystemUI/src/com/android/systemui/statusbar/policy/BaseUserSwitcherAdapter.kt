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
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.Drawable
import android.widget.BaseAdapter
import com.android.systemui.qs.user.UserSwitchDialogController.DialogShower
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.legacyhelper.ui.LegacyUserUiHelper.getUserRecordName
import com.android.systemui.user.legacyhelper.ui.LegacyUserUiHelper.getUserSwitcherActionIconResourceId
import java.lang.ref.WeakReference

/** Provides views for user switcher experiences. */
abstract class BaseUserSwitcherAdapter
protected constructor(
    protected val controller: UserSwitcherController,
) : BaseAdapter() {

    protected open val users: List<UserRecord>
        get() = controller.users.filter { !controller.isKeyguardShowing || !it.isRestricted }

    init {
        controller.addAdapter(WeakReference(this))
    }

    override fun getCount(): Int {
        return users.size
    }

    override fun getItem(position: Int): UserRecord {
        return users[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    /**
     * Notifies that a user item in the UI has been clicked.
     *
     * If the user switcher is hosted in a dialog, passing a non-null [dialogShower] will allow
     * animation to and from the parent dialog.
     */
    @JvmOverloads
    open fun onUserListItemClicked(
        record: UserRecord,
        dialogShower: DialogShower? = null,
    ) {
        controller.onUserListItemClicked(record, dialogShower)
    }

    open fun getName(context: Context, item: UserRecord): String {
        return getName(context, item, false)
    }

    /** Returns the name for the given {@link UserRecord}. */
    open fun getName(context: Context, item: UserRecord, isTablet: Boolean): String {
        return getUserRecordName(
            context = context,
            record = item,
            isGuestUserAutoCreated = controller.isGuestUserAutoCreated,
            isGuestUserResetting = controller.isGuestUserResetting,
            isTablet = isTablet,
        )
    }

    fun refresh() {
        controller.refreshUsers()
    }

    companion object {
        @JvmStatic
        protected val disabledUserAvatarColorFilter: ColorFilter by lazy {
            val matrix = ColorMatrix()
            matrix.setSaturation(0f) // 0 - grayscale
            ColorMatrixColorFilter(matrix)
        }

        @JvmStatic
        @JvmOverloads
        protected fun getIconDrawable(
            context: Context,
            item: UserRecord,
            isTablet: Boolean = false,
        ): Drawable {
            val iconRes =
                getUserSwitcherActionIconResourceId(
                    item.isAddUser,
                    item.isGuest,
                    item.isAddSupervisedUser,
                    isTablet,
                    item.isManageUsers,
                )
            return checkNotNull(context.getDrawable(iconRes))
        }
    }
}
