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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.util.IntArray;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A helper class that contains information about package names and uids that share the same allow
 * list for sending broadcasts. Used by various package helpers.
 */
final class BroadcastParams {
    private final @NonNull List<String> mPackageNames;
    private final @NonNull IntArray mUids;
    private final @NonNull SparseArray<int[]> mAllowList;

    BroadcastParams(@NonNull String packageName, @IntRange(from = 0) int uid,
            @NonNull int[] allowList, @UserIdInt int userId) {
        mPackageNames = new ArrayList<>(Arrays.asList(packageName));
        mUids = IntArray.wrap(new int[]{uid});
        mAllowList = new SparseArray<>(1);
        mAllowList.put(userId, allowList);
    }

    public void addPackage(@NonNull String packageName, @IntRange(from = 0) int uid) {
        mPackageNames.add(packageName);
        mUids.add(uid);
    }

    public @NonNull String[] getPackageNames() {
        return mPackageNames.toArray(new String[0]);
    }

    public @NonNull int[] getUids() {
        return mUids.toArray();
    }

    public @NonNull SparseArray<int[]> getAllowList() {
        return mAllowList;
    }
}
