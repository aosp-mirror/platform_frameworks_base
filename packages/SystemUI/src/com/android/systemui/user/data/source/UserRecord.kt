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
package com.android.systemui.user.data.source

import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.os.UserHandle

/** Encapsulates raw data for a user or an option item related to managing users on the device. */
data class UserRecord(
    /** Relevant user information. If `null`, this record is not a user but an option item. */
    @JvmField val info: UserInfo? = null,
    /** An image representing the user. */
    @JvmField val picture: Bitmap? = null,
    /** Whether this record represents an option to switch to a guest user. */
    @JvmField val isGuest: Boolean = false,
    /** Whether this record represents the currently-selected user. */
    @JvmField val isCurrent: Boolean = false,
    /** Whether this record represents an option to add another user to the device. */
    @JvmField val isAddUser: Boolean = false,
    /**
     * If true, the record is only available if unlocked or if the user has granted permission to
     * access this user action whilst on the device is locked.
     */
    @JvmField val isRestricted: Boolean = false,
    /** Whether it is possible to switch to this user. */
    @JvmField val isSwitchToEnabled: Boolean = false,
    /** Whether this record represents an option to add another supervised user to the device. */
    @JvmField val isAddSupervisedUser: Boolean = false,
) {
    /** Returns a new instance of [UserRecord] with its [isCurrent] set to the given value. */
    fun copyWithIsCurrent(isCurrent: Boolean): UserRecord {
        return copy(isCurrent = isCurrent)
    }

    /**
     * Returns the user ID for the user represented by this instance or [UserHandle.USER_NULL] if
     * this instance if a guest or does not represent a user (represents an option item).
     */
    fun resolveId(): Int {
        return if (isGuest || info == null) {
            UserHandle.USER_NULL
        } else {
            info.id
        }
    }

    companion object {
        @JvmStatic
        fun createForGuest(): UserRecord {
            return UserRecord(isGuest = true)
        }
    }
}
