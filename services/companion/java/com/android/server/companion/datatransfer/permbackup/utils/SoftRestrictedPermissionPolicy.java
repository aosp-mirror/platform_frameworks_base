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

package com.android.server.companion.datatransfer.permbackup.utils;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

import android.annotation.NonNull;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.android.server.companion.datatransfer.permbackup.model.Permission;

/**
 * The behavior of soft restricted permissions is different for each permission. This class collects
 * the policies in one place.
 *
 * This is the twin of {@link com.android.server.policy.SoftRestrictedPermissionPolicy}
 */
public abstract class SoftRestrictedPermissionPolicy {

    /**
     * Check if the permission should be shown in the UI.
     *
     * @param pkg the package the permission belongs to
     * @param permission the permission
     *
     * @return {@code true} iff the permission should be shown in the UI.
     */
    public static boolean shouldShow(@NonNull PackageInfo pkg, @NonNull Permission permission) {
        switch (permission.getName()) {
            case READ_EXTERNAL_STORAGE:
            case WRITE_EXTERNAL_STORAGE: {
                boolean isWhiteListed =
                        (permission.getFlags() & Utils.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT)
                                != 0;
                int targetSDK = pkg.applicationInfo.targetSdkVersion;

                return isWhiteListed || targetSDK >= Build.VERSION_CODES.Q;
            }
            default:
                return true;
        }
    }

    /**
     * Check if the permission should be shown in the UI.
     *
     * @param pkg the LightPackageInfo the permission belongs to
     * @param permissionName the name of the permission
     * @param permissionFlags the PermissionController flags (not the PermissionInfo flags) for
     * the permission
     *
     * @return {@code true} iff the permission should be shown in the UI.
     */
    public static boolean shouldShow(@NonNull PackageInfo pkg, @NonNull String permissionName,
            int permissionFlags) {
        switch (permissionName) {
            case READ_EXTERNAL_STORAGE:
            case WRITE_EXTERNAL_STORAGE: {
                boolean isWhiteListed =
                        (permissionFlags & Utils.FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) != 0;
                return isWhiteListed || pkg.applicationInfo.targetSdkVersion
                        >= Build.VERSION_CODES.Q;
            }
            default:
                return true;
        }
    }
}
