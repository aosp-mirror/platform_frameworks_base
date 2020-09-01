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

package com.android.server.testing.shadows;

import static android.content.pm.PackageManager.NameNotFoundException;

import android.app.ApplicationPackageManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.util.ArrayMap;

import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extends {@link org.robolectric.shadows.ShadowApplicationPackageManager} to return the correct
 * package in user-specific invocations.
 */
@Implements(value = ApplicationPackageManager.class)
public class ShadowApplicationPackageManager
        extends org.robolectric.shadows.ShadowApplicationPackageManager {
    private static final Map<String, PackageInfo> sPackageInfos = new ArrayMap<>();
    private static final List<PackageInfo> sInstalledPackages = new ArrayList<>();
    private static final Map<String, Integer> sPackageUids = new ArrayMap<>();
    private static final Map<Integer, Map<String, Integer>> sUserPackageUids = new ArrayMap<>();

    /**
     * Registers the package {@code packageName} to be returned when invoking {@link
     * ApplicationPackageManager#getPackageInfoAsUser(String, int, int)} and {@link
     * ApplicationPackageManager#getInstalledPackagesAsUser(int, int)}.
     */
    public static void addInstalledPackage(String packageName, PackageInfo packageInfo) {
        sPackageInfos.put(packageName, packageInfo);
        sInstalledPackages.add(packageInfo);
    }

    /**
     * Sets the package uid {@code packageUid} for the package {@code packageName} to be returned
     * when invoking {@link ApplicationPackageManager#getPackageUidAsUser(String, int, int)}.
     */
    public static void setPackageUid(String packageName, int packageUid) {
        sPackageUids.put(packageName, packageUid);
    }

    /**
     * Sets the package uid {@code packageUid} for the package {@code packageName} to be returned
     * when invoking {@link ApplicationPackageManager#getPackageUidAsUser(String, int, int)}.
     */
    public static void setPackageUidAsUser(String packageName, int packageUid, int userId) {
        final Map<String, Integer> userPackageUids =
                sUserPackageUids.containsKey(userId)
                        ? sUserPackageUids.get(userId)
                        : new HashMap<>();
        userPackageUids.put(packageName, packageUid);
        sUserPackageUids.put(userId, userPackageUids);
    }

    @Override
    protected PackageInfo getPackageInfoAsUser(String packageName, int flags, int userId)
            throws NameNotFoundException {
        if (!sPackageInfos.containsKey(packageName)) {
            throw new NameNotFoundException(packageName);
        }
        return sPackageInfos.get(packageName);
    }

    @Override
    protected List<PackageInfo> getInstalledPackagesAsUser(int flags, int userId) {
        return sInstalledPackages;
    }

    @Override
    protected int getPackageUidAsUser(String packageName, int flags, int userId)
            throws NameNotFoundException {
        if (sUserPackageUids.containsKey(userId)
                && sUserPackageUids.get(userId).containsKey(packageName)) {
            return sUserPackageUids.get(userId).get(packageName);
        }
        if (!sPackageUids.containsKey(packageName)) {
            throw new NameNotFoundException(packageName);
        }
        return sPackageUids.get(packageName);
    }

    @Override
    protected List<ResolveInfo> queryBroadcastReceiversAsUser(
            Intent intent, int flags, UserHandle userHandle) {
        // Currently does not handle multi-user.
        return queryBroadcastReceivers(intent, flags);
    }

    /** Clear package state. */
    @Resetter
    public static void reset() {
        sPackageInfos.clear();
        sInstalledPackages.clear();
        org.robolectric.shadows.ShadowApplicationPackageManager.reset();
    }
}
