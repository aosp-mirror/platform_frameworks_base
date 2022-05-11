/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.os.Process;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.internal.util.function.QuadFunction;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.snapshot.PackageDataSnapshot;

import java.io.PrintWriter;

/**
 * Read-only interface used by computer and snapshots to query the visibility of packages
 */
public interface AppsFilterSnapshot {
    /**
     * Fetches all app Ids that a given setting is currently visible to, per provided user. This
     * only includes UIDs >= {@link Process#FIRST_APPLICATION_UID} as all other UIDs can already see
     * all applications.
     *
     * If the setting is visible to all UIDs, null is returned. If an app is not visible to any
     * applications, the int array will be empty.
     *
     * @param snapshot         the snapshot of the computer that contains all package information
     * @param users            the set of users that should be evaluated for this calculation
     * @param existingSettings the set of all package settings that currently exist on device
     * @return a SparseArray mapping userIds to a sorted int array of appIds that may view the
     * provided setting or null if the app is visible to all and no allow list should be
     * applied.
     */
    SparseArray<int[]> getVisibilityAllowList(PackageDataSnapshot snapshot,
            PackageStateInternal setting, int[] users,
            ArrayMap<String, ? extends PackageStateInternal> existingSettings);

    /**
     * Returns true if the calling package should not be able to see the target package, false if no
     * filtering should be done.
     *
     * @param snapshot         the snapshot of the computer that contains all package information
     * @param callingUid       the uid of the caller attempting to access a package
     * @param callingSetting   the setting attempting to access a package or null if it could not be
     *                         found
     * @param targetPkgSetting the package being accessed
     * @param userId           the user in which this access is being attempted
     */
    boolean shouldFilterApplication(PackageDataSnapshot snapshot, int callingUid,
            @Nullable Object callingSetting, PackageStateInternal targetPkgSetting, int userId);

    /**
     * Returns whether the querying package is allowed to see the target package.
     *
     * @param querying        the querying package
     * @param potentialTarget the package name of the target package
     */
    boolean canQueryPackage(@NonNull AndroidPackage querying, String potentialTarget);

    /**
     * Dump the packages that are queryable by the querying package.
     *
     * @param pw                the output print writer
     * @param filteringAppId    the querying package's app ID
     * @param dumpState         the state of the dumping
     * @param users             the users for which the packages are installed
     * @param getPackagesForUid the function that produces the package names for given uids
     */
    void dumpQueries(PrintWriter pw, @Nullable Integer filteringAppId, DumpState dumpState,
            int[] users,
            QuadFunction<Integer, Integer, Integer, Boolean, String[]> getPackagesForUid);

}
