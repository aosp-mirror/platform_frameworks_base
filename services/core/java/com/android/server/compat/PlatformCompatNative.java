/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.compat;

import android.annotation.UserIdInt;

import com.android.internal.compat.IPlatformCompatNative;

/**
 * @hide
 */
public class PlatformCompatNative extends IPlatformCompatNative.Stub {
    private final PlatformCompat mPlatformCompat;

    public PlatformCompatNative(PlatformCompat platformCompat) {
        mPlatformCompat = platformCompat;
    }

    @Override
    public void reportChangeByPackageName(long changeId, String packageName, int userId) {
        mPlatformCompat.reportChangeByPackageName(changeId, packageName, userId);
    }

    @Override
    public void reportChangeByUid(long changeId, int uid) {
        mPlatformCompat.reportChangeByUid(changeId, uid);
    }

    @Override
    public boolean isChangeEnabledByPackageName(long changeId, String packageName,
            @UserIdInt int userId) {
        return mPlatformCompat.isChangeEnabledByPackageName(changeId, packageName, userId);
    }

    @Override
    public boolean isChangeEnabledByUid(long changeId, int uid) {
        return mPlatformCompat.isChangeEnabledByUid(changeId, uid);
    }
}
