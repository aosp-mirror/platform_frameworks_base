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

import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

/**
 * Set of pending broadcasts for aggregating enable/disable of components.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class PendingPackageBroadcasts {

    // for each user id, a map of <package name -> components within that package>
    final SparseArray<ArrayMap<String, ArrayList<String>>> mUidMap;

    public PendingPackageBroadcasts() {
        mUidMap = new SparseArray<>(2);
    }

    public ArrayList<String> get(int userId, String packageName) {
        ArrayMap<String, ArrayList<String>> packages = getOrAllocate(userId);
        return packages.get(packageName);
    }

    public void put(int userId, String packageName, ArrayList<String> components) {
        ArrayMap<String, ArrayList<String>> packages = getOrAllocate(userId);
        packages.put(packageName, components);
    }

    public void remove(int userId, String packageName) {
        ArrayMap<String, ArrayList<String>> packages = mUidMap.get(userId);
        if (packages != null) {
            packages.remove(packageName);
        }
    }

    public void remove(int userId) {
        mUidMap.remove(userId);
    }

    public int userIdCount() {
        return mUidMap.size();
    }

    public int userIdAt(int n) {
        return mUidMap.keyAt(n);
    }

    public ArrayMap<String, ArrayList<String>> packagesForUserId(int userId) {
        return mUidMap.get(userId);
    }

    public int size() {
        // total number of pending broadcast entries across all userIds
        int num = 0;
        for (int i = 0; i < mUidMap.size(); i++) {
            num += mUidMap.valueAt(i).size();
        }
        return num;
    }

    public void clear() {
        mUidMap.clear();
    }

    private ArrayMap<String, ArrayList<String>> getOrAllocate(int userId) {
        ArrayMap<String, ArrayList<String>> map = mUidMap.get(userId);
        if (map == null) {
            map = new ArrayMap<>();
            mUidMap.put(userId, map);
        }
        return map;
    }
}
