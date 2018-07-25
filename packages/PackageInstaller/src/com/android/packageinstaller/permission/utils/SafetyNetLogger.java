/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.packageinstaller.permission.utils;

import android.content.pm.PackageInfo;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;

import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.Permission;

import java.util.ArrayList;
import java.util.List;

public final class SafetyNetLogger {

    // The log tag used by SafetyNet to pick entries from the event log.
    private static final int SNET_NET_EVENT_LOG_TAG = 0x534e4554;

    // Log tag for the result of permissions request.
    private static final String PERMISSIONS_REQUESTED = "individual_permissions_requested";

    // Log tag for the result of permissions toggling.
    private static final String PERMISSIONS_TOGGLED = "individual_permissions_toggled";

    private SafetyNetLogger() {
        /* do nothing */
    }

    public static void logPermissionsRequested(PackageInfo packageInfo,
            List<AppPermissionGroup> groups) {
        EventLog.writeEvent(SNET_NET_EVENT_LOG_TAG, PERMISSIONS_REQUESTED,
                packageInfo.applicationInfo.uid, buildChangedPermissionForPackageMessage(
                        packageInfo.packageName, groups));
    }

    /**
     * Log that permission groups have been toggled for the purpose of safety net.
     *
     * <p>The groups might refer to different permission groups and different apps.
     *
     * @param groups The groups toggled
     */
    public static void logPermissionsToggled(ArraySet<AppPermissionGroup> groups) {
        ArrayMap<String, ArrayList<AppPermissionGroup>> groupsByPackage = new ArrayMap<>();

        int numGroups = groups.size();
        for (int i = 0; i < numGroups; i++) {
            AppPermissionGroup group = groups.valueAt(i);

            ArrayList<AppPermissionGroup> groupsForThisPackage = groupsByPackage.get(
                    group.getApp().packageName);
            if (groupsForThisPackage == null) {
                groupsForThisPackage = new ArrayList<>();
                groupsByPackage.put(group.getApp().packageName, groupsForThisPackage);
            }

            groupsForThisPackage.add(group);
            if (group.getBackgroundPermissions() != null) {
                groupsForThisPackage.add(group.getBackgroundPermissions());
            }
        }

        int numPackages = groupsByPackage.size();
        for (int i = 0; i < numPackages; i++) {
            EventLog.writeEvent(SNET_NET_EVENT_LOG_TAG, PERMISSIONS_TOGGLED,
                    android.os.Process.myUid(), buildChangedPermissionForPackageMessage(
                            groupsByPackage.keyAt(i), groupsByPackage.valueAt(i)));
        }
    }

    private static void buildChangedPermissionForGroup(AppPermissionGroup group,
            StringBuilder builder) {
        int permissionCount = group.getPermissions().size();
        for (int permissionNum = 0; permissionNum < permissionCount; permissionNum++) {
            Permission permission = group.getPermissions().get(permissionNum);

            if (builder.length() > 0) {
                builder.append(';');
            }

            builder.append(permission.getName()).append('|');

            if (group.doesSupportRuntimePermissions()) {
                builder.append(permission.isGranted()).append('|');
            } else {
                builder.append(permission.isGranted()
                        && (!permission.affectsAppOp() || permission.isAppOpAllowed())).append('|');
            }

            builder.append(permission.getFlags());
        }
    }

    private static String buildChangedPermissionForPackageMessage(String packageName,
            List<AppPermissionGroup> groups) {
        StringBuilder builder = new StringBuilder();

        builder.append(packageName).append(':');

        int groupCount = groups.size();
        for (int groupNum = 0; groupNum < groupCount; groupNum++) {
            AppPermissionGroup group = groups.get(groupNum);

            buildChangedPermissionForGroup(group, builder);
            if (group.getBackgroundPermissions() != null) {
                buildChangedPermissionForGroup(group.getBackgroundPermissions(), builder);
            }
        }

        return builder.toString();
    }
}
