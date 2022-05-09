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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Set of pending broadcasts for aggregating enable/disable of components.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class PendingPackageBroadcasts {

    private final Object mLock = new PackageManagerTracedLock();

    // for each user id, a map of <package name -> components within that package>
    @GuardedBy("mLock")
    final SparseArray<ArrayMap<String, ArrayList<String>>> mUidMap;

    public PendingPackageBroadcasts() {
        mUidMap = new SparseArray<>(2);
    }

    public boolean hasPackage(@UserIdInt int userId, @NonNull String packageName) {
        synchronized (mLock) {
            final ArrayMap<String, ArrayList<String>> packages = mUidMap.get(userId);
            return packages != null && packages.containsKey(packageName);
        }
    }

    public void put(int userId, String packageName, ArrayList<String> components) {
        synchronized (mLock) {
            ArrayMap<String, ArrayList<String>> packages = getOrAllocate(userId);
            packages.put(packageName, components);
        }
    }

    public void addComponent(@UserIdInt int userId, @NonNull String packageName,
            @NonNull String componentClassName) {
        synchronized (mLock) {
            ArrayList<String> components = getOrAllocate(userId, packageName);
            if (!components.contains(componentClassName)) {
                components.add(componentClassName);
            }
        }
    }

    public void addComponents(@UserIdInt int userId, @NonNull String packageName,
            @NonNull List<String> componentClassNames) {
        synchronized (mLock) {
            ArrayList<String> components = getOrAllocate(userId, packageName);
            for (int index = 0; index < componentClassNames.size(); index++) {
                String componentClassName = componentClassNames.get(index);
                if (!components.contains(componentClassName)) {
                    components.add(componentClassName);
                }
            }
        }
    }

    public void remove(int userId, String packageName) {
        synchronized (mLock) {
            ArrayMap<String, ArrayList<String>> packages = mUidMap.get(userId);
            if (packages != null) {
                packages.remove(packageName);
            }
        }
    }

    public void remove(int userId) {
        synchronized (mLock) {
            mUidMap.remove(userId);
        }
    }

    @Nullable
    public SparseArray<ArrayMap<String, ArrayList<String>>> copiedMap() {
        synchronized (mLock) {
            SparseArray<ArrayMap<String, ArrayList<String>>> copy = new SparseArray<>();
            for (int userIdIndex = 0; userIdIndex < mUidMap.size(); userIdIndex++) {
                final ArrayMap<String, ArrayList<String>> packages = mUidMap.valueAt(userIdIndex);
                ArrayMap<String, ArrayList<String>> packagesCopy = new ArrayMap<>();
                for (int packagesIndex = 0; packagesIndex < packages.size(); packagesIndex++) {
                    packagesCopy.put(packages.keyAt(packagesIndex),
                            new ArrayList<>(packages.valueAt(packagesIndex)));
                }
                copy.put(mUidMap.keyAt(userIdIndex), packagesCopy);
            }
            return copy;
        }
    }

    public void clear() {
        synchronized (mLock) {
            mUidMap.clear();
        }
    }

    private ArrayMap<String, ArrayList<String>> getOrAllocate(int userId) {
        synchronized (mLock) {
            ArrayMap<String, ArrayList<String>> map = mUidMap.get(userId);
            if (map == null) {
                map = new ArrayMap<>();
                mUidMap.put(userId, map);
            }
            return map;
        }
    }

    private ArrayList<String> getOrAllocate(int userId, @NonNull String packageName) {
        synchronized (mLock) {
            ArrayMap<String, ArrayList<String>> map = mUidMap.get(userId);
            if (map == null) {
                map = new ArrayMap<>();
                mUidMap.put(userId, map);
            }

            return map.computeIfAbsent(packageName, k -> new ArrayList<>());
        }
    }
}
