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

package com.android.server.companion.utils;

import static android.app.role.RoleManager.MANAGE_HOLDERS_FLAG_DONT_KILL_APP;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.role.RoleManager;
import android.companion.AssociationInfo;
import android.content.Context;
import android.os.Binder;
import android.os.UserHandle;
import android.util.Slog;

import java.util.List;
import java.util.function.Consumer;

/** Utility methods for accessing {@link RoleManager} APIs. */
@SuppressLint("LongLogTag")
public final class RolesUtils {

    private static final String TAG = "CDM_RolesUtils";

    /**
     * Check if the package holds the role.
     */
    public static boolean isRoleHolder(@NonNull Context context, @UserIdInt int userId,
            @NonNull String packageName, @NonNull String role) {
        final RoleManager roleManager = context.getSystemService(RoleManager.class);
        final List<String> roleHolders = roleManager.getRoleHoldersAsUser(
                role, UserHandle.of(userId));
        return roleHolders.contains(packageName);
    }

    /**
     * Attempt to add the association's companion app as the role holder for the device profile
     * specified in the association. If the association does not have any device profile specified,
     * then the operation will always be successful as a no-op.
     *
     * @param context
     * @param associationInfo the association for which the role should be granted to the app
     * @param roleGrantResult the result callback for adding role holder. True if successful, and
     *                        false if failed. If the association does not have any device profile
     *                        specified, then the operation will always be successful as a no-op.
     */
    public static void addRoleHolderForAssociation(
            @NonNull Context context, @NonNull AssociationInfo associationInfo,
            @NonNull Consumer<Boolean> roleGrantResult) {
        final String deviceProfile = associationInfo.getDeviceProfile();
        if (deviceProfile == null) {
            // If no device profile is specified, then no-op and resolve callback with success.
            roleGrantResult.accept(true);
            return;
        }

        final RoleManager roleManager = context.getSystemService(RoleManager.class);

        final String packageName = associationInfo.getPackageName();
        final int userId = associationInfo.getUserId();
        final UserHandle userHandle = UserHandle.of(userId);

        roleManager.addRoleHolderAsUser(deviceProfile, packageName,
                MANAGE_HOLDERS_FLAG_DONT_KILL_APP, userHandle, context.getMainExecutor(),
                roleGrantResult);
    }

    /**
     * Remove the role for the package association.
     */
    public static void removeRoleHolderForAssociation(
            @NonNull Context context, int userId, String packageName, String deviceProfile) {
        if (deviceProfile == null) return;

        final RoleManager roleManager = context.getSystemService(RoleManager.class);

        final UserHandle userHandle = UserHandle.of(userId);

        Slog.i(TAG, "Removing CDM role=" + deviceProfile
                + " for userId=" + userId + ", packageName=" + packageName);

        Binder.withCleanCallingIdentity(() ->
            roleManager.removeRoleHolderAsUser(deviceProfile, packageName,
                    MANAGE_HOLDERS_FLAG_DONT_KILL_APP, userHandle, context.getMainExecutor(),
                    success -> {
                        if (!success) {
                            Slog.e(TAG, "Failed to remove userId=" + userId + ", packageName="
                                    + packageName + " from the list of " + deviceProfile
                                    + " holders.");
                        }
                    })
        );
    }

    private RolesUtils() {}
}
