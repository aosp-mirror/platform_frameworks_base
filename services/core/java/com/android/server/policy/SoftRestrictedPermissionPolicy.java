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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.storage.StorageManagerInternal;
import android.util.Log;

import com.android.internal.compat.IPlatformCompat;
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
    /**
     * Enables scoped storage, with exceptions for apps that explicitly request legacy access, or
     * apps that hold the {@code android.Manifest.permission#WRITE_MEDIA_STORAGE} permission.
     * See https://developer.android.com/training/data-storage#scoped-storage for more information.
     */
    @ChangeId
    // This change is enabled for apps with targetSDK > {@link android.os.Build.VERSION_CODES.P}
    @EnabledAfter(targetSdkVersion = android.os.Build.VERSION_CODES.P)
    static final long ENABLE_SCOPED_STORAGE = 144914977L;

    /**
     * Enforces scoped storage for all apps, preventing individual apps from opting out. This change
     * has precedence over {@code ENABLE_SCOPED_STORAGE}.
     */
    @ChangeId
    // This change is enabled for apps with targetSDK > {@link android.os.Build.VERSION_CODES.Q}.
    @EnabledAfter(targetSdkVersion = android.os.Build.VERSION_CODES.Q)
    static final long REQUIRE_SCOPED_STORAGE = 131432978L;

    private static final String LOG_TAG = SoftRestrictedPermissionPolicy.class.getSimpleName();

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
                final boolean hasRequestedLegacyExternalStorage;
                final boolean shouldPreserveLegacyExternalStorage;
                final boolean hasWriteMediaStorageGrantedForUid;
                final boolean isScopedStorageEnabled;

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
                    final boolean isScopedStorageRequired =
                            isChangeEnabledForUid(context, appInfo, user, REQUIRE_SCOPED_STORAGE);
                    isScopedStorageEnabled =
                            isChangeEnabledForUid(context, appInfo, user, ENABLE_SCOPED_STORAGE)
                            || isScopedStorageRequired;
                    shouldPreserveLegacyExternalStorage = pkg.hasPreserveLegacyExternalStorage()
                            && smInternal.hasLegacyExternalStorage(appInfo.uid);
                    shouldApplyRestriction = (flags & FLAG_PERMISSION_APPLY_RESTRICTION) != 0
                            || (isScopedStorageRequired && !shouldPreserveLegacyExternalStorage);
                } else {
                    isWhiteListed = false;
                    shouldApplyRestriction = false;
                    hasRequestedLegacyExternalStorage = false;
                    shouldPreserveLegacyExternalStorage = false;
                    hasWriteMediaStorageGrantedForUid = false;
                    isScopedStorageEnabled = false;
                }

                // We have a check in PermissionPolicyService.PermissionToOpSynchroniser.setUidMode
                // to prevent apps losing files in legacy storage, because we are holding the
                // package manager lock here. If we ever remove this policy that check should be
                // removed as well.
                return new SoftRestrictedPermissionPolicy() {
                    @Override
                    public boolean mayGrantPermission() {
                        return isWhiteListed || isScopedStorageEnabled;
                    }
                    @Override
                    public int getExtraAppOpCode() {
                        return OP_LEGACY_STORAGE;
                    }
                    @Override
                    public boolean mayAllowExtraAppOp() {
                        return !shouldApplyRestriction
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
                final boolean isScopedStorageEnabled;

                if (appInfo != null) {
                    final int flags = context.getPackageManager().getPermissionFlags(permission,
                            appInfo.packageName, user);
                    isWhiteListed = (flags & FLAGS_PERMISSION_RESTRICTION_ANY_EXEMPT) != 0;
                    final boolean isScopedStorageRequired =
                            isChangeEnabledForUid(context, appInfo, user, REQUIRE_SCOPED_STORAGE);
                    isScopedStorageEnabled =
                            isChangeEnabledForUid(context, appInfo, user, ENABLE_SCOPED_STORAGE)
                            || isScopedStorageRequired;
                } else {
                    isWhiteListed = false;
                    isScopedStorageEnabled = false;
                }

                return new SoftRestrictedPermissionPolicy() {
                    @Override
                    public boolean mayGrantPermission() {
                        return isWhiteListed || isScopedStorageEnabled;
                    }
                };
            }
            default:
                return DUMMY_POLICY;
        }
    }

    /**
     * Checks whether an AppCompat change is enabled for all packages sharing a UID with the
     * provided application.
     *
     * @param context A context to use.
     * @param appInfo The application for which to check whether the compat change is enabled.
     * @param user The user the app belongs to.
     * @param changeId A {@link android.compat.annotation.ChangeId} corresponding to the change.
     *
     * @return true if this change is enabled for all apps sharing the UID of the provided app,
     *         false otherwise.
     */
    private static boolean isChangeEnabledForUid(@NonNull Context context,
            @NonNull ApplicationInfo appInfo, @NonNull UserHandle user, long changeId) {
        PackageManager pm = context.getPackageManager();

        String[] uidPackages = pm.getPackagesForUid(appInfo.uid);
        if (uidPackages != null) {
            for (String uidPackage : uidPackages) {
                ApplicationInfo uidPackageInfo;
                try {
                    uidPackageInfo = pm.getApplicationInfoAsUser(uidPackage, 0, user);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                if (!isChangeEnabled(uidPackageInfo, changeId)) {
                    // At least one package sharing this UID does not have this change enabled.
                    return false;
                }
            }
            // All packages sharing this UID returned true for {@link #isChangeEnabled()}.
            return true;
        } else {
            Log.w(LOG_TAG, "Check for change " + changeId + " for uid " + appInfo.uid
                    + " produced no packages. Defaulting to using the information for "
                    + appInfo.packageName + " only.");
            return isChangeEnabled(appInfo, changeId);
        }
    }

    private static boolean isChangeEnabled(@NonNull ApplicationInfo appInfo, long changeId) {
        IBinder binder = ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE);
        IPlatformCompat platformCompat = IPlatformCompat.Stub.asInterface(binder);

        final long callingId = Binder.clearCallingIdentity();

        try {
            return platformCompat.isChangeEnabled(changeId, appInfo);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Check for change " + changeId + " failed. Defaulting to enabled.", e);
            return true;
        } finally {
            Binder.restoreCallingIdentity(callingId);
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
