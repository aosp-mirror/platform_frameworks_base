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

import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.PackageDexUsage;

import static com.android.server.pm.PackageManagerService.DEBUG_DEXOPT;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.app.AppGlobals;
import android.content.Intent;
import android.content.pm.PackageParser;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.util.ArraySet;
import android.util.Log;
import dalvik.system.VMRuntime;
import libcore.io.Libcore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Class containing helper methods for the PackageManagerService.
 *
 * {@hide}
 */
public class PackageManagerServiceUtils {
    private final static long SEVEN_DAYS_IN_MILLISECONDS = 7 * 24 * 60 * 60 * 1000;

    private static ArraySet<String> getPackageNamesForIntent(Intent intent, int userId) {
        List<ResolveInfo> ris = null;
        try {
            ris = AppGlobals.getPackageManager().queryIntentReceivers(intent, null, 0, userId)
                    .getList();
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

    // Sort a list of apps by their last usage, most recently used apps first. The order of
    // packages without usage data is undefined (but they will be sorted after the packages
    // that do have usage data).
    public static void sortPackagesByUsageDate(List<PackageParser.Package> pkgs,
            PackageManagerService packageManagerService) {
        if (!packageManagerService.isHistoricalPackageUsageAvailable()) {
            return;
        }

        Collections.sort(pkgs, (pkg1, pkg2) ->
                Long.compare(pkg2.getLatestForegroundPackageUseTimeInMills(),
                        pkg1.getLatestForegroundPackageUseTimeInMills()));
    }

    // Apply the given {@code filter} to all packages in {@code packages}. If tested positive, the
    // package will be removed from {@code packages} and added to {@code result} with its
    // dependencies. If usage data is available, the positive packages will be sorted by usage
    // data (with {@code sortTemp} as temporary storage).
    private static void applyPackageFilter(Predicate<PackageParser.Package> filter,
            Collection<PackageParser.Package> result,
            Collection<PackageParser.Package> packages,
            @NonNull List<PackageParser.Package> sortTemp,
            PackageManagerService packageManagerService) {
        for (PackageParser.Package pkg : packages) {
            if (filter.test(pkg)) {
                sortTemp.add(pkg);
            }
        }

        sortPackagesByUsageDate(sortTemp, packageManagerService);
        packages.removeAll(sortTemp);

        for (PackageParser.Package pkg : sortTemp) {
            result.add(pkg);

            Collection<PackageParser.Package> deps =
                    packageManagerService.findSharedNonSystemLibraries(pkg);
            if (!deps.isEmpty()) {
                deps.removeAll(result);
                result.addAll(deps);
                packages.removeAll(deps);
            }
        }

        sortTemp.clear();
    }

    // Sort apps by importance for dexopt ordering. Important apps are given
    // more priority in case the device runs out of space.
    public static List<PackageParser.Package> getPackagesForDexopt(
            Collection<PackageParser.Package> packages,
            PackageManagerService packageManagerService) {
        ArrayList<PackageParser.Package> remainingPkgs = new ArrayList<>(packages);
        LinkedList<PackageParser.Package> result = new LinkedList<>();
        ArrayList<PackageParser.Package> sortTemp = new ArrayList<>(remainingPkgs.size());

        // Give priority to core apps.
        applyPackageFilter((pkg) -> pkg.coreApp, result, remainingPkgs, sortTemp,
                packageManagerService);

        // Give priority to system apps that listen for pre boot complete.
        Intent intent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
        final ArraySet<String> pkgNames = getPackageNamesForIntent(intent, UserHandle.USER_SYSTEM);
        applyPackageFilter((pkg) -> pkgNames.contains(pkg.packageName), result, remainingPkgs,
                sortTemp, packageManagerService);

        // Give priority to apps used by other apps.
        DexManager dexManager = packageManagerService.getDexManager();
        applyPackageFilter((pkg) ->
                dexManager.getPackageUseInfoOrDefault(pkg.packageName)
                        .isAnyCodePathUsedByOtherApps(),
                result, remainingPkgs, sortTemp, packageManagerService);

        // Filter out packages that aren't recently used, add all remaining apps.
        // TODO: add a property to control this?
        Predicate<PackageParser.Package> remainingPredicate;
        if (!remainingPkgs.isEmpty() && packageManagerService.isHistoricalPackageUsageAvailable()) {
            if (DEBUG_DEXOPT) {
                Log.i(TAG, "Looking at historical package use");
            }
            // Get the package that was used last.
            PackageParser.Package lastUsed = Collections.max(remainingPkgs, (pkg1, pkg2) ->
                    Long.compare(pkg1.getLatestForegroundPackageUseTimeInMills(),
                            pkg2.getLatestForegroundPackageUseTimeInMills()));
            if (DEBUG_DEXOPT) {
                Log.i(TAG, "Taking package " + lastUsed.packageName + " as reference in time use");
            }
            long estimatedPreviousSystemUseTime =
                    lastUsed.getLatestForegroundPackageUseTimeInMills();
            // Be defensive if for some reason package usage has bogus data.
            if (estimatedPreviousSystemUseTime != 0) {
                final long cutoffTime = estimatedPreviousSystemUseTime - SEVEN_DAYS_IN_MILLISECONDS;
                remainingPredicate =
                        (pkg) -> pkg.getLatestForegroundPackageUseTimeInMills() >= cutoffTime;
            } else {
                // No meaningful historical info. Take all.
                remainingPredicate = (pkg) -> true;
            }
            sortPackagesByUsageDate(remainingPkgs, packageManagerService);
        } else {
            // No historical info. Take all.
            remainingPredicate = (pkg) -> true;
        }
        applyPackageFilter(remainingPredicate, result, remainingPkgs, sortTemp,
                packageManagerService);

        if (DEBUG_DEXOPT) {
            Log.i(TAG, "Packages to be dexopted: " + packagesToString(result));
            Log.i(TAG, "Packages skipped from dexopt: " + packagesToString(remainingPkgs));
        }

        return result;
    }

    /**
     * Checks if the package was inactive during since <code>thresholdTimeinMillis</code>.
     * Package is considered active, if:
     * 1) It was active in foreground.
     * 2) It was active in background and also used by other apps.
     *
     * If it doesn't have sufficient information about the package, it return <code>false</code>.
     */
    static boolean isUnusedSinceTimeInMillis(long firstInstallTime, long currentTimeInMillis,
            long thresholdTimeinMillis, PackageDexUsage.PackageUseInfo packageUseInfo,
            long latestPackageUseTimeInMillis, long latestForegroundPackageUseTimeInMillis) {

        if (currentTimeInMillis - firstInstallTime < thresholdTimeinMillis) {
            return false;
        }

        // If the app was active in foreground during the threshold period.
        boolean isActiveInForeground = (currentTimeInMillis
                - latestForegroundPackageUseTimeInMillis)
                < thresholdTimeinMillis;

        if (isActiveInForeground) {
            return false;
        }

        // If the app was active in background during the threshold period and was used
        // by other packages.
        boolean isActiveInBackgroundAndUsedByOtherPackages = ((currentTimeInMillis
                - latestPackageUseTimeInMillis)
                < thresholdTimeinMillis)
                && packageUseInfo.isAnyCodePathUsedByOtherApps();

        return !isActiveInBackgroundAndUsedByOtherPackages;
    }

    /**
     * Returns the canonicalized path of {@code path} as per {@code realpath(3)}
     * semantics.
     */
    public static String realpath(File path) throws IOException {
        try {
            return Libcore.os.realpath(path.getAbsolutePath());
        } catch (ErrnoException ee) {
            throw ee.rethrowAsIOException();
        }
    }

    public static String packagesToString(Collection<PackageParser.Package> c) {
        StringBuilder sb = new StringBuilder();
        for (PackageParser.Package pkg : c) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(pkg.packageName);
        }
        return sb.toString();
    }

    /**
     * Verifies that the given string {@code isa} is a valid supported isa on
     * the running device.
     */
    public static boolean checkISA(String isa) {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (VMRuntime.getInstructionSet(abi).equals(isa)) {
                return true;
            }
        }
        return false;
    }
}
