/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.permission;

import android.annotation.AppIdInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.SparseArray;

/**
 * Permission state for a user.
 */
public final class UserPermissionState {
    /**
     * Whether the install permissions have been granted to a package, so that no install
     * permissions should be added to it unless the package is upgraded.
     */
    @NonNull
    private final ArraySet<String> mInstallPermissionsFixed = new ArraySet<>();

    /**
     * Maps from app ID to {@link UidPermissionState}.
     */
    @NonNull
    private final SparseArray<UidPermissionState> mUidStates = new SparseArray<>();

    public boolean areInstallPermissionsFixed(@NonNull String packageName) {
        return mInstallPermissionsFixed.contains(packageName);
    }

    public void setInstallPermissionsFixed(@NonNull String packageName, boolean fixed) {
        if (fixed) {
            mInstallPermissionsFixed.add(packageName);
        } else {
            mInstallPermissionsFixed.remove(packageName);
        }
    }

    @Nullable
    public UidPermissionState getUidState(@AppIdInt int appId) {
        checkAppId(appId);
        return mUidStates.get(appId);
    }

    @NonNull
    public UidPermissionState getOrCreateUidState(@AppIdInt int appId) {
        checkAppId(appId);
        UidPermissionState uidState = mUidStates.get(appId);
        if (uidState == null) {
            uidState = new UidPermissionState();
            mUidStates.put(appId, uidState);
        }
        return uidState;
    }

    public void removeUidState(@AppIdInt int appId) {
        checkAppId(appId);
        mUidStates.delete(appId);
    }

    private void checkAppId(@AppIdInt int appId) {
        if (UserHandle.getUserId(appId) != 0) {
            throw new IllegalArgumentException("Invalid app ID " + appId);
        }
    }
}
