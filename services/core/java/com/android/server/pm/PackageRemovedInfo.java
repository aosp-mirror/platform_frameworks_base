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

package com.android.server.pm;

import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.util.ArrayUtils;

final class PackageRemovedInfo {
    String mRemovedPackage;
    String mInstallerPackageName;
    int mUid = -1;
    boolean mIsAppIdRemoved = false;
    int[] mOrigUsers;
    int[] mRemovedUsers = null;
    int[] mBroadcastUsers = null;
    int[] mInstantUserIds = null;
    SparseIntArray mInstallReasons;
    SparseIntArray mUninstallReasons;
    boolean mIsRemovedPackageSystemUpdate = false;
    boolean mIsUpdate;
    boolean mDataRemoved;
    boolean mRemovedForAllUsers;
    boolean mIsStaticSharedLib;
    boolean mIsExternal;
    long mRemovedPackageVersionCode;
    // a two dimensional array mapping userId to the set of appIds that can receive notice
    // of package changes
    SparseArray<int[]> mBroadcastAllowList;
    // Clean up resources deleted packages.
    InstallArgs mArgs = null;
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    public void populateBroadcastUsers(PackageSetting deletedPackageSetting) {
        if (mRemovedUsers == null) {
            mBroadcastUsers = null;
            return;
        }

        mBroadcastUsers = EMPTY_INT_ARRAY;
        mInstantUserIds = EMPTY_INT_ARRAY;
        for (int i = mRemovedUsers.length - 1; i >= 0; --i) {
            final int userId = mRemovedUsers[i];
            if (deletedPackageSetting.getInstantApp(userId)) {
                mInstantUserIds = ArrayUtils.appendInt(mInstantUserIds, userId);
            } else {
                mBroadcastUsers = ArrayUtils.appendInt(mBroadcastUsers, userId);
            }
        }
    }
}
