/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.audio;

import android.annotation.Nullable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.media.permission.INativePermissionController;
import com.android.media.permission.UidPackageState;
import com.android.server.pm.pkg.PackageState;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/** Responsible for synchronizing system server permission state to the native audioserver. */
public class AudioServerPermissionProvider {

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private INativePermissionController mDest;

    @GuardedBy("mLock")
    private final Map<Integer, Set<String>> mPackageMap;

    /**
     * @param appInfos - PackageState for all apps on the device, used to populate init state
     */
    public AudioServerPermissionProvider(Collection<PackageState> appInfos) {
        // Initialize the package state
        mPackageMap = generatePackageMappings(appInfos);
    }

    /**
     * Called whenever audioserver starts (or started before us)
     *
     * @param pc - The permission controller interface from audioserver, which we push updates to
     */
    public void onServiceStart(@Nullable INativePermissionController pc) {
        if (pc == null) return;
        synchronized (mLock) {
            mDest = pc;
            resetNativePackageState();
        }
    }

    /**
     * Called when a package is added or removed
     *
     * @param uid - uid of modified package (only app-id matters)
     * @param packageName - the (new) packageName
     * @param isRemove - true if the package is being removed, false if it is being added
     */
    public void onModifyPackageState(int uid, String packageName, boolean isRemove) {
        // No point in maintaining package mappings for uids of different users
        uid = UserHandle.getAppId(uid);
        synchronized (mLock) {
            // Update state
            Set<String> packages;
            if (!isRemove) {
                packages = mPackageMap.computeIfAbsent(uid, unused -> new ArraySet(1));
                packages.add(packageName);
            } else {
                packages = mPackageMap.get(uid);
                if (packages != null) {
                    packages.remove(packageName);
                    if (packages.isEmpty()) mPackageMap.remove(uid);
                }
            }
            // Push state to destination
            if (mDest == null) {
                return;
            }
            var state = new UidPackageState();
            state.uid = uid;
            state.packageNames = packages != null ? List.copyOf(packages) : Collections.emptyList();
            try {
                mDest.updatePackagesForUid(state);
            } catch (RemoteException e) {
                // We will re-init the state when the service comes back up
                mDest = null;
            }
        }
    }

    /** Called when full syncing package state to audioserver. */
    @GuardedBy("mLock")
    private void resetNativePackageState() {
        if (mDest == null) return;
        List<UidPackageState> states =
                mPackageMap.entrySet().stream()
                        .map(
                                entry -> {
                                    UidPackageState state = new UidPackageState();
                                    state.uid = entry.getKey();
                                    state.packageNames = List.copyOf(entry.getValue());
                                    return state;
                                })
                        .toList();
        try {
            mDest.populatePackagesForUids(states);
        } catch (RemoteException e) {
            // We will re-init the state when the service comes back up
            mDest = null;
        }
    }

    /**
     * Aggregation operation on all package states list: groups by states by app-id and merges the
     * packageName for each state into an ArraySet.
     */
    private static Map<Integer, Set<String>> generatePackageMappings(
            Collection<PackageState> appInfos) {
        Collector<PackageState, Object, Set<String>> reducer =
                Collectors.mapping(
                        (PackageState p) -> p.getPackageName(),
                        Collectors.toCollection(() -> new ArraySet(1)));

        return appInfos.stream()
                .collect(
                        Collectors.groupingBy(
                                /* predicate */ (PackageState p) -> p.getAppId(),
                                /* factory */ HashMap::new,
                                /* downstream collector */ reducer));
    }
}
