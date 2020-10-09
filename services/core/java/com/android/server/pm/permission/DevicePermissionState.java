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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.util.SparseArray;

/**
 * Permission state for this device.
 */
public final class DevicePermissionState {
    private final SparseArray<UserPermissionState> mUserStates = new SparseArray<>();

    @Nullable
    public UserPermissionState getUserState(@UserIdInt int userId) {
        return mUserStates.get(userId);
    }

    @NonNull
    public UserPermissionState getOrCreateUserState(@UserIdInt int userId) {
        UserPermissionState userState = mUserStates.get(userId);
        if (userState == null) {
            userState = new UserPermissionState();
            mUserStates.put(userId, userState);
        }
        return userState;
    }

    public void removeUserState(@UserIdInt int userId) {
        mUserStates.delete(userId);
    }

    public int[] getUserIds() {
        final int userStatesSize = mUserStates.size();
        final int[] userIds = new int[userStatesSize];
        for (int i = 0; i < userStatesSize; i++) {
            final int userId = mUserStates.keyAt(i);
            userIds[i] = userId;
        }
        return userIds;
    }
}
