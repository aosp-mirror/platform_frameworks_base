/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.AppGlobals;
import android.content.Intent;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.Package;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static com.android.server.pm.PackageManagerService.DEBUG_DEXOPT;
import static com.android.server.pm.PackageManagerService.TAG;

/**
 * Class containing helper methods for the PackageManagerService.
 *
 * {@hide}
 */
public class PackageManagerServiceUtils {
    // Apps used in the last 7 days.
    private final static long DEXOPT_LRU_THRESHOLD_IN_MINUTES = 7 * 24 * 60;

    private static ArraySet<String> getPackageNamesForIntent(Intent intent, int userId) {
        List<ResolveInfo> ris = null;
        try {
            ris = AppGlobals.getPackageManager().queryIntentReceivers(intent, null, 0, userId);
        } catch (RemoteException e) {
        }
        ArraySet<String> pkgNames = new ArraySet<String>();
        if (ris != null) {
            for (ResolveInfo ri : ris) {
                pkgNames.add(ri.activityInfo.packageName);
            }
        }
        return pkgNames;
    }

    private static void filterRecentlyUsedApps(Collection<PackageParser.Package> pkgs,
            long dexOptLRUThresholdInMills) {
        // Filter out packages that aren't recently used.
        int total = pkgs.size();
        int skipped = 0;
        long now = System.currentTimeMillis();
        for (Iterator<PackageParser.Package> i = pkgs.iterator(); i.hasNext();) {
            PackageParser.Package pkg = i.next();
            long then = pkg.mLastPackageUsageTimeInMills;
            if (then + dexOptLRUThresholdInMills < now) {
                if (DEBUG_DEXOPT) {
                    Log.i(TAG, "Skipping dexopt of " + pkg.packageName + " last resumed: " +
                          ((then == 0) ? "never" : new Date(then)));
                }
                i.remove();
                skipped++;
            }
        }
        if (DEBUG_DEXOPT) {
            Log.i(TAG, "Skipped optimizing " + skipped + " of " + total);
        }
    }

    // Sort apps by importance for dexopt ordering. Important apps are given
    // more priority in case the device runs out of space.
    public static List<PackageParser.Package> getPackagesForDexopt(
            Collection<PackageParser.Package> packages,
            PackageManagerService packageManagerService) {
        ArrayList<PackageParser.Package> remainingPkgs = new ArrayList<>(packages);
        LinkedList<PackageParser.Package> result = new LinkedList<>();

        // Give priority to core apps.
        for (PackageParser.Package pkg : remainingPkgs) {
            if (pkg.coreApp) {
                if (DEBUG_DEXOPT) {
                    Log.i(TAG, "Adding core app " + result.size() + ": " + pkg.packageName);
                }
                result.add(pkg);
            }
        }
        remainingPkgs.removeAll(result);

        // Give priority to system apps that listen for pre boot complete.
        Intent intent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
        ArraySet<String> pkgNames = getPackageNamesForIntent(intent, UserHandle.USER_SYSTEM);
        for (PackageParser.Package pkg : remainingPkgs) {
            if (pkgNames.contains(pkg.packageName)) {
                if (DEBUG_DEXOPT) {
                    Log.i(TAG, "Adding pre boot system app " + result.size() + ": " +
                            pkg.packageName);
                }
                result.add(pkg);
            }
        }
        remainingPkgs.removeAll(result);

        // Filter out packages that aren't recently used, add all remaining apps.
        // TODO: add a property to control this?
        if (packageManagerService.isHistoricalPackageUsageAvailable()) {
            filterRecentlyUsedApps(remainingPkgs, DEXOPT_LRU_THRESHOLD_IN_MINUTES * 60 * 1000);
        }
        result.addAll(remainingPkgs);

        // Now go ahead and also add the libraries required for these packages.
        // TODO: Think about interleaving things.
        Set<PackageParser.Package> dependencies = new HashSet<>();
        for (PackageParser.Package p : result) {
            dependencies.addAll(packageManagerService.findSharedNonSystemLibraries(p));
        }
        if (!dependencies.isEmpty()) {
            dependencies.removeAll(result);
        }
        result.addAll(dependencies);

        if (DEBUG_DEXOPT) {
            StringBuilder sb = new StringBuilder();
            for (PackageParser.Package pkg : result) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(pkg.packageName);
            }
            Log.i(TAG, "Packages to be optimized: " + sb.toString());
        }

        return result;
    }
}