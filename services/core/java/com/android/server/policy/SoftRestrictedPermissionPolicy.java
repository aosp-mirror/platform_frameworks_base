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
import static android.Manifest.permission.WRITE_MEDIA_STORAGE;
import static android.app.AppOpsManager.OP_LEGACY_STORAGE;
import static android.app.AppOpsManager.OP_NONE;
import static android.content.pm.PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.storage.StorageManager.PROP_LEGACY_OP_STICKY;

import static java.lang.Integer.min;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManagerInternal;

import com.android.server.LocalServices;
import com.android.server.pm.parsing.pkg.AndroidPackage;

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
                public boolean mayGrantPermission() {
                    return true;
                }
            };

    private static final boolean isLegacyStorageAppOpStickyGlobal = SystemProperties.getBoolean(
            PROP_LEGACY_OP_STICKY, /*defaultValue*/true);

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
     * @param appInfo The application the permission belongs to.
     * @param user The user the app belongs to.
     * @param permission The name of the permission
     *
     * @return The policy for this permission
     */
    public static @NonNull SoftRestrictedPermissionPolicy forPermission(@NonNull Context context,
            @Nullable ApplicationInfo appInfo, @Nullable AndroidPackage pkg,
            @Nullable UserHandle user, @NonNull String permission) {
        switch (permission) {
            // Storage uses a special app op to decide the mount state and supports soft restriction
            // where the restricted state allows the permission but only for accessing the medial
            // collections.
            case READ_EXTERNAL_STORAGE: {
                final boolean isWhiteListed;
                boolean shouldApplyRestriction;
                final int targetSDK;
                final boolean hasRequestedLegacyExternalStorage;
                final boolean shouldPreserveLegacyExternalStorage;
                final boolean hasWriteMediaStorageGrantedForUid;

                if (appInfo != null) {
                    PackageManager pm = context.getPackageManager();
                    StorageManagerInternal smInternal =
                            LocalServices.getService(StorageManagerInternal.class);
                    int flags = pm.getPermissionFlags(permission, appInfo.packageName, user);
                    isWhiteListed = (flags & FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) != 0;
                    hasRequestedLegacyExternalStorage = hasUidRequestedLegacyExternalStorage(
                            appInfo.uid, context);
                    hasWriteMediaStorageGrantedForUid = hasWriteMediaStorageGrantedForUid(
                            appInfo.uid, context);
                    shouldPreserveLegacyExternalStorage = pkg.hasPreserveLegacyExternalStorage()
                            && smInternal.hasLegacyExternalStorage(appInfo.uid);
                    targetSDK = getMinimumTargetSDK(context, appInfo, user);
                    // LEGACY_STORAGE op is normally sticky for apps targetig <= Q.
                    // However, this device can be configured to make it non-sticky.
                    boolean isLegacyAppOpSticky = isLegacyStorageAppOpStickyGlobal
                            && targetSDK <= Build.VERSION_CODES.Q;
                    shouldApplyRestriction = (flags & FLAG_PERMISSION_APPLY_RESTRICTION) != 0
                            || (!isLegacyAppOpSticky && !shouldPreserveLegacyExternalStorage);
                } else {
                    isWhiteListed = false;
                    shouldApplyRestriction = false;
                    targetSDK = 0;
                    hasRequestedLegacyExternalStorage = false;
                    shouldPreserveLegacyExternalStorage = false;
                    hasWriteMediaStorageGrantedForUid = false;
                }

                // We have a check in PermissionPolicyService.PermissionToOpSynchroniser.setUidMode
                // to prevent apps losing files in legacy storage, because we are holding the
                // package manager lock here. If we ever remove this policy that check should be
                // removed as well.
                return new SoftRestrictedPermissionPolicy() {
                    @Override
                    public boolean mayGrantPermission() {
                        return isWhiteListed || targetSDK >= Build.VERSION_CODES.Q;
                    }
                    @Override
                    public int getExtraAppOpCode() {
                        return OP_LEGACY_STORAGE;
                    }
                    @Override
                    public boolean mayAllowExtraAppOp() {
                        return !shouldApplyRestriction && targetSDK <= Build.VERSION_CODES.Q
                                && (hasRequestedLegacyExternalStorage
                                        || hasWriteMediaStorageGrantedForUid
                                        || shouldPreserveLegacyExternalStorage);
                    }
                    @Override
                    public boolean mayDenyExtraAppOpIfGranted() {
                        return shouldApplyRestriction;
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
                    public boolean mayGrantPermission() {
                        return isWhiteListed || targetSDK >= Build.VERSION_CODES.Q;
                    }
                };
            }
            default:
                return DUMMY_POLICY;
        }
    }

    private static boolean hasUidRequestedLegacyExternalStorage(int uid, @NonNull Context context) {
        PackageManager packageManager = context.getPackageManager();
        String[] packageNames = packageManager.getPackagesForUid(uid);
        if (packageNames == null) {
            return false;
        }
        UserHandle user = UserHandle.getUserHandleForUid(uid);
        for (String packageName : packageNames) {
            ApplicationInfo applicationInfo;
            try {
                applicationInfo = packageManager.getApplicationInfoAsUser(packageName, 0, user);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            if (applicationInfo.hasRequestedLegacyExternalStorage()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasWriteMediaStorageGrantedForUid(int uid, @NonNull Context context) {
        PackageManager packageManager = context.getPackageManager();
        String[] packageNames = packageManager.getPackagesForUid(uid);
        if (packageNames == null) {
            return false;
        }

        for (String packageName : packageNames) {
            if (packageManager.checkPermission(WRITE_MEDIA_STORAGE, packageName)
                    == PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return If the permission can be granted
     */
    public abstract boolean mayGrantPermission();

    /**
     * @return An app op to be changed based on the state of the permission or
     * {@link AppOpsManager#OP_NONE} if not app-op should be set.
     */
    public int getExtraAppOpCode() {
        return OP_NONE;
    }

    /**
     * @return Whether the {@link #getExtraAppOpCode() app op} may be granted.
     */
    public boolean mayAllowExtraAppOp() {
        return false;
    }

    /**
     * @return Whether the {@link #getExtraAppOpCode() app op} may be denied if was granted.
     */
    public boolean mayDenyExtraAppOpIfGranted() {
        return false;
    }
}
