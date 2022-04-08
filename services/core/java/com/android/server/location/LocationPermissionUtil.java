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

package com.android.server.location;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Encapsulates utility functions and classes related to location permission checking.
 */
public final class LocationPermissionUtil {
    /**
     * Returns true if the calling process identified by {@code callerIdentity} is enabled to
     * report location to AppOps service before providing device location identifiable information
     * to its clients. Packages with these permissions must report any reporting of location
     * information to apps, via AppOps.
     *
     * <p>The calling package represented by {@code callerIdentity} is considered a part of the
     * extended Location Manager Service if it has all of the permissions below.
     * <ul>
     *     <li>{@link android.Manifest.permission#LOCATION_HARDWARE}
     *     <li>{@link android.Manifest.permission#UPDATE_APP_OPS_STATS}
     * </ul>
     *
     * <p>Any package with these permissions, that passes along location information from Android
     * framework to apps, must report to AppOps, similarly to Location Manager Service - i.e.
     * whenever it reports device location or location identifiable information such as
     * GNSS status, GNSS measurements, etc. to its clients.
     */
    public static boolean doesCallerReportToAppOps(Context context, CallerIdentity callerIdentity) {
        return hasPermissionLocationHardware(context, callerIdentity)
                && hasPermissionUpdateAppOpsStats(context, callerIdentity);
    }

    private static boolean hasPermissionLocationHardware(Context context,
            CallerIdentity callerIdentity) {
        return context.checkPermission(android.Manifest.permission.LOCATION_HARDWARE,
                callerIdentity.pid, callerIdentity.uid) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean hasPermissionUpdateAppOpsStats(Context context,
            CallerIdentity callerIdentity) {
        return context.checkPermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                callerIdentity.pid, callerIdentity.uid) == PackageManager.PERMISSION_GRANTED;
    }

    private LocationPermissionUtil() {}
}
