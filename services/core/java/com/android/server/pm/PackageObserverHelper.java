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
import android.content.pm.PackageManagerInternal.PackageListObserver;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;

class PackageObserverHelper {

    @NonNull
    private final Object mLock = new Object();

    // True set of observers, immutable, used to iterate without blocking the lock, since
    // callbacks can take a long time to return. The previous alternative used a bunch of
    // list copies on each notify call, which is suboptimal in cases of few mutations and
    // lots of notifications.
    @NonNull
    @GuardedBy("mLock")
    private ArraySet<PackageListObserver> mActiveSnapshot = new ArraySet<>();

    public void addObserver(@NonNull PackageListObserver observer) {
        synchronized (mLock) {
            ArraySet<PackageListObserver> set = new ArraySet<>(mActiveSnapshot);
            set.add(observer);
            mActiveSnapshot = set;
        }
    }

    public void removeObserver(@NonNull PackageListObserver observer) {
        synchronized (mLock) {
            ArraySet<PackageListObserver> set = new ArraySet<>(mActiveSnapshot);
            set.remove(observer);
            mActiveSnapshot = set;
        }
    }

    public void notifyAdded(@NonNull String packageName, int uid) {
        ArraySet<PackageListObserver> observers;
        synchronized (mLock) {
            observers = mActiveSnapshot;
        }
        final int size = observers.size();
        for (int index = 0; index < size; index++) {
            observers.valueAt(index).onPackageAdded(packageName, uid);
        }
    }

    public void notifyChanged(@NonNull String packageName, int uid) {
        ArraySet<PackageListObserver> observers;
        synchronized (mLock) {
            observers = mActiveSnapshot;
        }
        final int size = observers.size();
        for (int index = 0; index < size; index++) {
            observers.valueAt(index).onPackageChanged(packageName, uid);
        }
    }

    public void notifyRemoved(@NonNull String packageName, int uid) {
        ArraySet<PackageListObserver> observers;
        synchronized (mLock) {
            observers = mActiveSnapshot;
        }
        final int size = observers.size();
        for (int index = 0; index < size; index++) {
            observers.valueAt(index).onPackageRemoved(packageName, uid);
        }
    }
}
