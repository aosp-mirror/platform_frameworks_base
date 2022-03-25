/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.devicepolicy;

import static android.os.UserHandle.USER_NULL;

import android.annotation.UserIdInt;
import android.content.ComponentName;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Data-transfer object used by {@link DevicePolicyManagerServiceShellCommand}.
 */
final class OwnerShellData {

    public final @UserIdInt int userId;
    public final @UserIdInt int parentUserId;
    public final ComponentName admin;
    public final boolean isDeviceOwner;
    public final boolean isProfileOwner;
    public final boolean isManagedProfileOwner;
    public boolean isAffiliated;

    // NOTE: class is too simple to require a Builder (not to mention isAffiliated is mutable)
    private OwnerShellData(@UserIdInt int userId, @UserIdInt int parentUserId, ComponentName admin,
            boolean isDeviceOwner, boolean isProfileOwner, boolean isManagedProfileOwner) {
        Preconditions.checkArgument(userId != USER_NULL, "userId cannot be USER_NULL");
        this.userId = userId;
        this.parentUserId = parentUserId;
        this.admin = Objects.requireNonNull(admin, "admin must not be null");
        this.isDeviceOwner = isDeviceOwner;
        this.isProfileOwner = isProfileOwner;
        this.isManagedProfileOwner = isManagedProfileOwner;
        if (isManagedProfileOwner) {
            Preconditions.checkArgument(parentUserId != USER_NULL,
                    "parentUserId cannot be USER_NULL for managed profile owner");
            Preconditions.checkArgument(parentUserId != userId,
                    "cannot be parent of itself (%d)", userId);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getClass().getSimpleName())
                .append("[userId=").append(userId)
                .append(",admin=").append(admin.flattenToShortString());
        if (isDeviceOwner) {
            sb.append(",deviceOwner");
        }
        if (isProfileOwner) {
            sb.append(",isProfileOwner");
        }
        if (isManagedProfileOwner) {
            sb.append(",isManagedProfileOwner");
        }
        if (parentUserId != USER_NULL) {
            sb.append(",parentUserId=").append(parentUserId);
        }
        if (isAffiliated) {
            sb.append(",isAffiliated");
        }
        return sb.append(']').toString();
    }

    static OwnerShellData forDeviceOwner(@UserIdInt int userId, ComponentName admin) {
        return new OwnerShellData(userId, /* parentUserId= */ USER_NULL, admin,
                /* isDeviceOwner= */ true, /* isProfileOwner= */ false,
                /* isManagedProfileOwner= */ false);
    }

    static OwnerShellData forUserProfileOwner(@UserIdInt int userId, ComponentName admin) {
        return new OwnerShellData(userId, /* parentUserId= */ USER_NULL, admin,
                /* isDeviceOwner= */ false, /* isProfileOwner= */ true,
                /* isManagedProfileOwner= */ false);
    }

    static OwnerShellData forManagedProfileOwner(@UserIdInt int userId, @UserIdInt int parentUserId,
            ComponentName admin) {
        return new OwnerShellData(userId, parentUserId, admin, /* isDeviceOwner= */ false,
                /* isProfileOwner= */ false, /* isManagedProfileOwner= */ true);
    }
}
