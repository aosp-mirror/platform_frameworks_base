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
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;

import static java.lang.Integer.min;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserHandle;

/**
 * The behavior of soft restricted permissions is different for each permission. This class collects
 * the policies in one place.
 *
 * This is the twin of
 * {@link com.android.packageinstaller.permission.utils.SoftRestrictedPermissionPolicy}
 */
public abstract class SoftRestrictedPermissionPolicy {
    private static final int FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT =
            FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT
                    | FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT
                    | FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;

    private static final SoftRestrictedPermissionPolicy DUMMY_POLICY =
            new SoftRestrictedPermissionPolicy() {
                @Override
                public int resolveAppOp() {
                    return OP_NONE;
                }

                @Override
                public int getDesiredOpMode() {
                    return MODE_DEFAULT;
                }

                @Override
                public boolean shouldSetAppOpIfNotDefault() {
                    return false;
                }

                @Override
                public boolean canBeGranted() {
                    return true;
                }
            };

    /**
     * TargetSDK is per package. To make sure two apps int the same shared UID do not fight over
     * what to set, always compute the combined targetSDK.
     *
     * @param context A context
     * @param appInfo The app that is changed
     * @param user The user the app belongs to
     *
     * @return The minimum targetSDK of all apps sharing the uid of the app
     */
    private static int getMinimumTargetSDK(@NonNull Context context,
            @NonNull ApplicationInfo appInfo, @NonNull UserHandle user) {
        PackageManager pm = context.getPackageManager();

        int minimumTargetSDK = appInfo.targetSdkVersion;

        String[] uidPkgs = pm.getPackagesForUid(appInfo.uid);
        if (uidPkgs != null) {
            for (String uidPkg : uidPkgs) {
                if (!uidPkg.equals(appInfo.packageName)) {
                    ApplicationInfo uidPkgInfo;
                    try {
                        uidPkgInfo = pm.getApplicationInfoAsUser(uidPkg, 0, user);
                    } catch (PackageManager.NameNotFoundException e) {
                        continue;
                    }

                    minimumTargetSDK = min(minimumTargetSDK, uidPkgInfo.targetSdkVersion);
                }
            }
        }

        return minimumTargetSDK;
    }

    /**
     * Get the policy for a soft restricted permission.
     *
     * @param context A context to use
     * @param appInfo The application the permission belongs to. Can be {@code null}, but then
     *                only {@link #resolveAppOp} will work.
     * @param user The user the app belongs to. Can be {@code null}, but then only
     *             {@link #resolveAppOp} will work.
     * @param permission The name of the permission
     *
     * @return The policy for this permission
     */
    public static @NonNull SoftRestrictedPermissionPolicy forPermission(@NonNull Context context,
            @Nullable ApplicationInfo appInfo, @Nullable UserHandle user,
            @NonNull String permission) {
        switch (permission) {
            // Storage uses a special app op to decide the mount state and supports soft restriction
            // where the restricted state allows the permission but only for accessing the medial
            // collections.
            case READ_EXTERNAL_STORAGE: {
                final int flags;
                final boolean applyRestriction;
                final boolean isWhiteListed;
                final boolean hasRequestedLegacyExternalStorage;
                final int targetSDK;

                if (appInfo != null) {
                    PackageManager pm = context.getPackageManager();
                    flags = pm.getPermissionFlags(permission, appInfo.packageName, user);
                    applyRestriction = (flags & FLAG_PERMISSION_APPLY_RESTRICTION) != 0;
                    isWhiteListed = (flags & FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) != 0;
                    targetSDK = getMinimumTargetSDK(context, appInfo, user);

                    boolean hasAnyRequestedLegacyExternalStorage =
                            appInfo.hasRequestedLegacyExternalStorage();

                    // hasRequestedLegacyExternalStorage is per package. To make sure two apps in
                    // the same shared UID do not fight over what to set, always compute the
                    // combined hasRequestedLegacyExternalStorage
                    String[] uidPkgs = pm.getPackagesForUid(appInfo.uid);
                    if (uidPkgs != null) {
                        for (String uidPkg : uidPkgs) {
                            if (!uidPkg.equals(appInfo.packageName)) {
                                ApplicationInfo uidPkgInfo;
                                try {
                                    uidPkgInfo = pm.getApplicationInfoAsUser(uidPkg, 0, user);
                                } catch (PackageManager.NameNotFoundException e) {
                                    continue;
                                }

                                hasAnyRequestedLegacyExternalStorage |=
                                        uidPkgInfo.hasRequestedLegacyExternalStorage();
                            }
                        }
                    }

                    hasRequestedLegacyExternalStorage = hasAnyRequestedLegacyExternalStorage;
                } else {
                    flags = 0;
                    applyRestriction = false;
                    isWhiteListed = false;
                    hasRequestedLegacyExternalStorage = false;
                    targetSDK = 0;
                }

                return new SoftRestrictedPermissionPolicy() {
                    @Override
                    public int resolveAppOp() {
                        return OP_LEGACY_STORAGE;
                    }

                    @Override
                    public int getDesiredOpMode() {
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
                        return getDesiredOpMode() != MODE_IGNORED;
                    }

                    @Override
                    public boolean canBeGranted() {
                        if (isWhiteListed || targetSDK >= Build.VERSION_CODES.Q) {
                            return true;
                        } else {
                            return false;
                        }
                    }
                };
            }
            case WRITE_EXTERNAL_STORAGE: {
                final boolean isWhiteListed;
                final int targetSDK;

                if (appInfo != null) {
                    final int flags = context.getPackageManager().getPermissionFlags(permission,
                            appInfo.packageName, user);
                    isWhiteListed = (flags & FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) != 0;
                    targetSDK = getMinimumTargetSDK(context, appInfo, user);
                } else {
                    isWhiteListed = false;
                    targetSDK = 0;
                }

                return new SoftRestrictedPermissionPolicy() {
                    @Override
                    public int resolveAppOp() {
                        return OP_NONE;
                    }

                    @Override
                    public int getDesiredOpMode() {
                        return MODE_DEFAULT;
                    }

                    @Override
                    public boolean shouldSetAppOpIfNotDefault() {
                        return false;
                    }

                    @Override
                    public boolean canBeGranted() {
                        return isWhiteListed || targetSDK >= Build.VERSION_CODES.Q;
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
    public abstract int resolveAppOp();

    /**
     * @return The mode the {@link #resolveAppOp() app op} should be in.
     */
    public abstract @AppOpsManager.Mode int getDesiredOpMode();

    /**
     * @return If the {@link #resolveAppOp() app op} should be set even if the app-op is currently
     * not {@link AppOpsManager#MODE_DEFAULT}.
     */
    public abstract boolean shouldSetAppOpIfNotDefault();

    /**
     * @return If the permission can be granted
     */
    public abstract boolean canBeGranted();
}
