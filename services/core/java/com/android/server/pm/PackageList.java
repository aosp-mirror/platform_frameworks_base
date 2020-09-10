/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageManagerInternal.PackageListObserver;

import com.android.server.LocalServices;

import java.util.List;

/**
 * All of the package name installed on the system.
 * <p>A self observable list that automatically removes the listener when it goes out of scope.
 *
 * @hide Only for use within the system server.
 */
public class PackageList implements PackageListObserver, AutoCloseable {
    private final PackageListObserver mWrappedObserver;
    private final List<String> mPackageNames;

    /**
     * Create a new object.
     * <p>Ownership of the given {@link List} transfers to this object and should not
     * be modified by the caller.
     */
    public PackageList(@NonNull List<String> packageNames, @Nullable PackageListObserver observer) {
        mPackageNames = packageNames;
        mWrappedObserver = observer;
    }

    @Override
    public void onPackageAdded(String packageName, int uid) {
        if (mWrappedObserver != null) {
            mWrappedObserver.onPackageAdded(packageName, uid);
        }
    }

    @Override
    public void onPackageChanged(String packageName, int uid) {
        if (mWrappedObserver != null) {
            mWrappedObserver.onPackageChanged(packageName, uid);
        }
    }

    @Override
    public void onPackageRemoved(String packageName, int uid) {
        if (mWrappedObserver != null) {
            mWrappedObserver.onPackageRemoved(packageName, uid);
        }
    }

    @Override
    public void close() throws Exception {
        LocalServices.getService(PackageManagerInternal.class).removePackageListObserver(this);
    }

    /**
     * Returns the names of packages installed on the system.
     * <p>The list is a copy-in-time and the actual set of installed packages may differ. Real
     * time updates to the package list are sent via the {@link PackageListObserver} callback.
     */
    public @NonNull List<String> getPackageNames() {
        return mPackageNames;
    }
}
