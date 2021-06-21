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

package com.android.server.devicepolicy;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.UserHandle;

/**
 * Caller identity containing the caller's UID, package name and component name.
 * All parameters are verified on object creation unless the component name is null and the
 * caller is a delegate.
 */
final class CallerIdentity {

    private final int mUid;
    @Nullable
    private final String mPackageName;
    @Nullable
    private final ComponentName mComponentName;

    CallerIdentity(int uid, @Nullable String packageName, @Nullable ComponentName componentName) {
        mUid = uid;
        mPackageName = packageName;
        mComponentName = componentName;
    }

    public int getUid() {
        return mUid;
    }

    public int getUserId() {
        return UserHandle.getUserId(mUid);
    }

    public UserHandle getUserHandle() {
        return UserHandle.getUserHandleForUid(mUid);
    }

    @Nullable public String getPackageName() {
        return mPackageName;
    }

    @Nullable public ComponentName getComponentName() {
        return mComponentName;
    }

    public boolean hasAdminComponent() {
        return mComponentName != null;
    }

    public boolean hasPackage() {
        return mPackageName != null;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("CallerIdentity[uid=").append(mUid);
        if (mPackageName != null) {
            builder.append(", pkg=").append(mPackageName);
        }
        if (mComponentName != null) {
            builder.append(", cmp=").append(mComponentName.flattenToShortString());
        }
        return builder.append("]").toString();
    }
}
