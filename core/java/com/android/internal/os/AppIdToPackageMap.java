/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.os;

import android.app.AppGlobals;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/** Maps AppIds to their package names. */
public final class AppIdToPackageMap {
    private final SparseArray<String> mAppIdToPackageMap;

    @VisibleForTesting
    public AppIdToPackageMap(SparseArray<String> appIdToPackageMap) {
        mAppIdToPackageMap = appIdToPackageMap;
    }

    /** Creates a new {@link AppIdToPackageMap} for currently installed packages. */
    public static AppIdToPackageMap getSnapshot() {
        List<PackageInfo> packages;
        try {
            packages = AppGlobals.getPackageManager()
                    .getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_AWARE,
                            UserHandle.USER_SYSTEM).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        final SparseArray<String> map = new SparseArray<>();
        for (PackageInfo pkg : packages) {
            final int uid = pkg.applicationInfo.uid;
            if (pkg.sharedUserId != null && map.indexOfKey(uid) >= 0) {
                // Use sharedUserId string as package name if there are collisions
                map.put(uid, "shared:" + pkg.sharedUserId);
            } else {
                map.put(uid, pkg.packageName);
            }
        }
        return new AppIdToPackageMap(map);
    }

    /** Maps the AppId to a package name. */
    public String mapAppId(int appId) {
        String pkgName = mAppIdToPackageMap.get(appId);
        return pkgName == null ? String.valueOf(appId) : pkgName;
    }

    /** Maps the UID to a package name. */
    public String mapUid(int uid) {
        final int appId = UserHandle.getAppId(uid);
        final String pkgName = mAppIdToPackageMap.get(appId);
        final String uidStr = UserHandle.formatUid(uid);
        return pkgName == null ? uidStr : pkgName + '/' + uidStr;
    }
}
