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
import android.content.pm.ChangedPackages;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

class ChangedPackagesTracker {

    @NonNull
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    @NonNull
    private int mChangedPackagesSequenceNumber;
    /**
     * List of changed [installed, removed or updated] packages.
     * mapping from user id -> sequence number -> package name
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<SparseArray<String>> mUserIdToSequenceToPackage = new SparseArray<>();
    /**
     * The sequence number of the last change to a package.
     * mapping from user id -> package name -> sequence number
     */
    @GuardedBy("mLock")
    @NonNull
    private final SparseArray<Map<String, Integer>> mChangedPackagesSequenceNumbers =
            new SparseArray<>();

    @Nullable
    public ChangedPackages getChangedPackages(int sequenceNumber, @UserIdInt int userId) {
        synchronized (mLock) {
            if (sequenceNumber >= mChangedPackagesSequenceNumber) {
                return null;
            }
            final SparseArray<String> changedPackages = mUserIdToSequenceToPackage.get(userId);
            if (changedPackages == null) {
                return null;
            }
            final List<String> packageNames =
                    new ArrayList<>(mChangedPackagesSequenceNumber - sequenceNumber);
            for (int i = sequenceNumber; i < mChangedPackagesSequenceNumber; i++) {
                final String packageName = changedPackages.get(i);
                if (packageName != null) {
                    packageNames.add(packageName);
                }
            }
            return packageNames.isEmpty()
                    ? null : new ChangedPackages(mChangedPackagesSequenceNumber, packageNames);
        }
    }

    int getSequenceNumber() {
        return mChangedPackagesSequenceNumber;
    }

    void iterateAll(@NonNull BiConsumer<Integer, SparseArray<SparseArray<String>>>
            sequenceNumberAndValues) {
        synchronized (mLock) {
            sequenceNumberAndValues.accept(mChangedPackagesSequenceNumber,
                    mUserIdToSequenceToPackage);
        }
    }

    void updateSequenceNumber(@NonNull String packageName, int[] userList) {
        synchronized (mLock) {
            for (int i = userList.length - 1; i >= 0; --i) {
                final int userId = userList[i];
                SparseArray<String> changedPackages = mUserIdToSequenceToPackage.get(userId);
                if (changedPackages == null) {
                    changedPackages = new SparseArray<>();
                    mUserIdToSequenceToPackage.put(userId, changedPackages);
                }
                Map<String, Integer> sequenceNumbers = mChangedPackagesSequenceNumbers.get(userId);
                if (sequenceNumbers == null) {
                    sequenceNumbers = new HashMap<>();
                    mChangedPackagesSequenceNumbers.put(userId, sequenceNumbers);
                }
                final Integer sequenceNumber = sequenceNumbers.get(packageName);
                if (sequenceNumber != null) {
                    changedPackages.remove(sequenceNumber);
                }
                changedPackages.put(mChangedPackagesSequenceNumber, packageName);
                sequenceNumbers.put(packageName, mChangedPackagesSequenceNumber);
            }
            mChangedPackagesSequenceNumber++;
        }
    }
}
