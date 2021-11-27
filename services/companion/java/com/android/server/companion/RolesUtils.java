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

package com.android.server.companion;

import static android.app.role.RoleManager.MANAGE_HOLDERS_FLAG_DONT_KILL_APP;

import static com.android.server.companion.CompanionDeviceManagerService.DEBUG;
import static com.android.server.companion.CompanionDeviceManagerService.LOG_TAG;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.role.RoleManager;
import android.companion.AssociationInfo;
import android.content.Context;
import android.os.UserHandle;
import android.util.Slog;

import java.util.List;

/** Utility methods for accessing {@link RoleManager} APIs. */
final class RolesUtils {

    static boolean isRoleHolder(@NonNull Context context, @UserIdInt int userId,
            @NonNull String packageName, @NonNull String role) {
        final RoleManager roleManager = context.getSystemService(RoleManager.class);
        final List<String> roleHolders = roleManager.getRoleHoldersAsUser(
                role, UserHandle.of(userId));
        return roleHolders.contains(packageName);
    }

    static void addRoleHolderForAssociation(
            @NonNull Context context, @NonNull AssociationInfo associationInfo) {
        if (DEBUG) {
            Slog.d(LOG_TAG, "addRoleHolderForAssociation() associationInfo=" + associationInfo);
        }

        final String deviceProfile = associationInfo.getDeviceProfile();
        if (deviceProfile == null) return;

        final RoleManager roleManager = context.getSystemService(RoleManager.class);

        final String packageName = associationInfo.getPackageName();
        final int userId = associationInfo.getUserId();
        final UserHandle userHandle = UserHandle.of(userId);

        roleManager.addRoleHolderAsUser(deviceProfile, packageName,
                MANAGE_HOLDERS_FLAG_DONT_KILL_APP, userHandle, context.getMainExecutor(),
                success -> {
                    if (!success) {
                        Slog.e(LOG_TAG, "Failed to add u" + userId + "\\" + packageName
                                + " to the list of " + deviceProfile + " holders.");
                    }
                });
    }

    static void removeRoleHolderForAssociation(
            @NonNull Context context, @NonNull AssociationInfo associationInfo) {
        if (DEBUG) {
            Slog.d(LOG_TAG, "removeRoleHolderForAssociation() associationInfo=" + associationInfo);
        }

        final String deviceProfile = associationInfo.getDeviceProfile();
        if (deviceProfile == null) return;

        final RoleManager roleManager = context.getSystemService(RoleManager.class);

        final String packageName = associationInfo.getPackageName();
        final int userId = associationInfo.getUserId();
        final UserHandle userHandle = UserHandle.of(userId);

        roleManager.removeRoleHolderAsUser(deviceProfile, packageName,
                MANAGE_HOLDERS_FLAG_DONT_KILL_APP, userHandle, context.getMainExecutor(),
                success -> {
                    if (!success) {
                        Slog.e(LOG_TAG, "Failed to remove u" + userId + "\\" + packageName
                                + " from the list of " + deviceProfile + " holders.");
                    }
                });
    }

    private RolesUtils() {};
}
