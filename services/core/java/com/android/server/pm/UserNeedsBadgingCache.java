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
 */

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;

public class UserNeedsBadgingCache {

    private final Object mLock = new Object();

    // Cache of users who need badging.
    @GuardedBy("mLock")
    @NonNull
    private final SparseBooleanArray mUserCache = new SparseBooleanArray();

    @NonNull
    private final UserManagerService mUserManager;

    public UserNeedsBadgingCache(@NonNull UserManagerService userManager) {
        mUserManager = userManager;
    }

    public void delete(@UserIdInt int userId) {
        synchronized (mLock) {
            mUserCache.delete(userId);
        }
    }

    public boolean get(@UserIdInt int userId) {
        synchronized (mLock) {
            int index = mUserCache.indexOfKey(userId);
            if (index >= 0) {
                return mUserCache.valueAt(index);
            }
        }

        final UserInfo userInfo;
        final long token = Binder.clearCallingIdentity();
        try {
            userInfo = mUserManager.getUserInfo(userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        final boolean b;
        b = userInfo != null && userInfo.isManagedProfile();
        synchronized (mLock) {
            mUserCache.put(userId, b);
        }
        return b;
    }
}
