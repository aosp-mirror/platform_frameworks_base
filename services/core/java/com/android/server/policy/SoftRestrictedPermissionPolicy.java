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

package com.android.server.policy;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_LEGACY_STORAGE;
import static android.app.AppOpsManager.OP_NONE;
import static android.content.pm.PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;

/**
 * The behavior of soft restricted permissions is different for each permission. This class collects
 * the policies in one place.
 */
public abstract class SoftRestrictedPermissionPolicy {
    private static final SoftRestrictedPermissionPolicy DUMMY_POLICY =
            new SoftRestrictedPermissionPolicy() {
                @Override
                public int getAppOp() {
                    return OP_NONE;
                }

                @Override
                public int getAppOpMode() {
                    return MODE_DEFAULT;
                }

                @Override
                public boolean shouldSetAppOpIfNotDefault() {
                    return false;
                }
            };

    /**
     * Get the policy for a soft restricted permission.
     *
     * @param context A context to use
     * @param appInfo The application the permission belongs to
     * @param permission The name of the permission
     *
     * @return The policy for this permission
     */
    public static @NonNull SoftRestrictedPermissionPolicy forPermission(@NonNull Context context,
            @NonNull ApplicationInfo appInfo, @NonNull String permission) {
        switch (permission) {
            // Storage uses a special app op to decide the mount state and supports soft restriction
            // where the restricted state allows the permission but only for accessing the medial
            // collections.
            case READ_EXTERNAL_STORAGE:
            case WRITE_EXTERNAL_STORAGE: {
                boolean applyRestriction = (context.getPackageManager().getPermissionFlags(
                        permission, appInfo.packageName, context.getUser())
                        & FLAG_PERMISSION_APPLY_RESTRICTION) != 0;
                boolean hasRequestedLegacyExternalStorage =
                        appInfo.hasRequestedLegacyExternalStorage();

                return new SoftRestrictedPermissionPolicy() {
                    @Override
                    public int getAppOp() {
                        return OP_LEGACY_STORAGE;
                    }

                    @Override
                    public int getAppOpMode() {
                        if (applyRestriction) {
                            return MODE_DEFAULT;
                        } else if (hasRequestedLegacyExternalStorage) {
                            return MODE_ALLOWED;
                        } else {
                            return MODE_IGNORED;
                        }
                    }

                    @Override
                    public boolean shouldSetAppOpIfNotDefault() {
                        // Do not switch from allowed -> ignored as this would mean to retroactively
                        // turn on isolated storage. This will make the app loose all its files.
                        return getAppOpMode() != MODE_IGNORED;
                    }
                };
            }
            default:
                return DUMMY_POLICY;
        }
    }

    /**
     * @return An app op to be changed based on the state of the permission or
     * {@link AppOpsManager#OP_NONE} if not app-op should be set.
     */
    public abstract int getAppOp();

    /**
     * @return The mode the {@link #getAppOp() app op} should be in.
     */
    public abstract @AppOpsManager.Mode int getAppOpMode();

    /**
     * @return If the {@link #getAppOp() app op} should be set even if the app-op is currently not
     * {@link AppOpsManager#MODE_DEFAULT}.
     */
    public abstract boolean shouldSetAppOpIfNotDefault();
}
