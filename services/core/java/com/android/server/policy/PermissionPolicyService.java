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

import static android.content.pm.PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION;
import static android.content.pm.PackageManager.GET_PERMISSIONS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageManagerInternal.PackageListObserver;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.permission.PermissionControllerManager;
import android.permission.PermissionManagerInternal;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * This is a permission policy that governs over all permission mechanism
 * such as permissions, app ops, etc. For example, the policy ensures that
 * permission state and app ops is synchronized for cases where there is a
 * dependency between permission state (permissions or permission flags)
 * and app ops - and vise versa.
 */
public final class PermissionPolicyService extends SystemService {
    private static final String LOG_TAG = PermissionPolicyService.class.getSimpleName();

    public PermissionPolicyService(@NonNull Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        packageManagerInternal.getPackageList(new PackageListObserver() {
            @Override
            public void onPackageAdded(String packageName, int uid) {
                synchronizePackagePermissionsAndAppOpsForUser(getContext(), packageName,
                        UserHandle.getUserId(uid));
            }

            @Override
            public void onPackageChanged(String packageName, int uid) {
                synchronizePackagePermissionsAndAppOpsForUser(getContext(), packageName,
                        UserHandle.getUserId(uid));
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                /* do nothing */
            }
        });
    }

    @Override
    public void onStartUser(@UserIdInt int userId) {
        grantOrUpgradeDefaultRuntimePermissionsInNeeded(getContext(), userId);
        synchronizePermissionsAndAppOpsForUser(getContext(), userId);
        startWatchingRuntimePermissionChanges(getContext(), userId);
    }

    private static void grantOrUpgradeDefaultRuntimePermissionsInNeeded(@NonNull Context context,
            @UserIdInt int userId) {
        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        if (packageManagerInternal.wereDefaultPermissionsGrantedSinceBoot(userId)) {
            // Now call into the permission controller to apply policy around permissions
            final CountDownLatch latch = new CountDownLatch(1);

            // We need to create a local manager that does not schedule work on the main
            // there as we are on the main thread and want to block until the work is
            // completed or we time out.
            final PermissionControllerManager permissionControllerManager =
                    new PermissionControllerManager(getUserContext(context, UserHandle.of(userId)),
                            FgThread.getHandler());
            permissionControllerManager.grantOrUpgradeDefaultRuntimePermissions(
                    FgThread.getExecutor(),
                    (Boolean success) -> {
                        if (!success) {
                            // We are in an undefined state now, let us crash and have
                            // rescue party suggest a wipe to recover to a good one.
                            final String message = "Error granting/upgrading runtime permissions";
                            Slog.wtf(LOG_TAG, message);
                            throw new IllegalStateException(message);
                        }
                        latch.countDown();
                    }
            );
            try {
                latch.await();
            } catch (InterruptedException e) {
                /* ignore */
            }
        }
    }

    private static void startWatchingRuntimePermissionChanges(@NonNull Context context,
            int userId) {
        final PermissionManagerInternal permissionManagerInternal = LocalServices.getService(
                PermissionManagerInternal.class);
        permissionManagerInternal.addOnRuntimePermissionStateChangedListener(
                (packageName, changedUserId) -> {
                    if (userId == changedUserId) {
                        synchronizePackagePermissionsAndAppOpsForUser(context, packageName, userId);
                    }
                });
    }

    private static @Nullable Context getUserContext(@NonNull Context context,
            @Nullable UserHandle user) {
        if (context.getUser().equals(user)) {
            return context;
        } else {
            try {
                return context.createPackageContextAsUser(context.getPackageName(), 0, user);
            } catch (NameNotFoundException e) {
                Slog.e(LOG_TAG, "Cannot create context for user " + user, e);
                return null;
            }
        }
    }

    /**
     * Synchronize a single package.
     */
    private static void synchronizePackagePermissionsAndAppOpsForUser(@NonNull Context context,
            @NonNull String packageName, @UserIdInt int userId) {
        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final PackageInfo pkg = packageManagerInternal.getPackageInfo(packageName, 0,
                Process.SYSTEM_UID, userId);
        if (pkg == null) {
            return;
        }
        final PermissionToOpSynchroniser synchroniser = new PermissionToOpSynchroniser(
                getUserContext(context, UserHandle.of(userId)));
        synchroniser.addPackage(pkg.packageName);
        final String[] sharedPkgNames = packageManagerInternal.getPackagesForSharedUserId(
                pkg.sharedUserId, userId);
        if (sharedPkgNames != null) {
            for (String sharedPkgName : sharedPkgNames) {
                final PackageParser.Package sharedPkg = packageManagerInternal
                        .getPackage(sharedPkgName);
                if (sharedPkg != null) {
                    synchroniser.addPackage(sharedPkg.packageName);
                }
            }
        }
        synchroniser.syncPackages();
    }

    /**
     * Synchronize all packages
     */
    private static void synchronizePermissionsAndAppOpsForUser(@NonNull Context context,
            @UserIdInt int userId) {
        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final PermissionToOpSynchroniser synchronizer = new PermissionToOpSynchroniser(
                getUserContext(context, UserHandle.of(userId)));
        packageManagerInternal.forEachPackage((pkg) -> synchronizer.addPackage(pkg.packageName));
        synchronizer.syncPackages();
    }

    /**
     * Synchronizes permission to app ops. You *must* always sync all packages
     * in a shared UID at the same time to ensure proper synchronization.
     */
    private static class PermissionToOpSynchroniser {
        private final @NonNull Context mContext;
        private final @NonNull PackageManager mPackageManager;
        private final @NonNull AppOpsManager mAppOpsManager;

        /** All uid that need to be synchronized */
        private final @NonNull SparseIntArray mAllUids = new SparseIntArray();

        /**
         * All ops that need to be set to default
         *
         * Currently, only used by the restricted permissions logic.
         *
         * @see #syncRestrictedOps
         */
        private final @NonNull ArrayList<OpToRestrict> mOpsToDefault = new ArrayList<>();

        /**
         * All ops that need to be flipped to allow if default.
         *
         * Currently, only used by the restricted permissions logic.
         *
         * @see #syncRestrictedOps
         */
        private final @NonNull ArrayList<OpToUnrestrict> mOpsToAllow = new ArrayList<>();

        /**
         * All ops that need to be flipped to ignore if default.
         *
         * Currently, only used by the restricted permissions logic.
         *
         * @see #syncRestrictedOps
         */
        private final @NonNull ArrayList<OpToUnrestrict> mOpsToIgnore = new ArrayList<>();

        /**
         * All foreground permissions
         *
         * @see #syncOpsOfFgPermissions()
         */
        private final @NonNull ArrayList<FgPermission> mFgPermOps = new ArrayList<>();

        PermissionToOpSynchroniser(@NonNull Context context) {
            mContext = context;
            mPackageManager = context.getPackageManager();
            mAppOpsManager = context.getSystemService(AppOpsManager.class);
        }

        /**
         * Set app ops that belong to restricted permissions.
         *
         * <p>This processes ops previously added by {@link #addOpIfRestricted}
         */
        private void syncRestrictedOps() {
            final int allowCount = mOpsToAllow.size();
            for (int i = 0; i < allowCount; i++) {
                final OpToUnrestrict op = mOpsToAllow.get(i);
                setUidModeAllowedIfDefault(op.code, op.uid, op.packageName);
            }
            final int ignoreCount = mOpsToIgnore.size();
            for (int i = 0; i < ignoreCount; i++) {
                final OpToUnrestrict op = mOpsToIgnore.get(i);
                setUidModeIgnoredIfDefault(op.code, op.uid, op.packageName);
            }
            final int defaultCount = mOpsToDefault.size();
            for (int i = 0; i < defaultCount; i++) {
                final OpToRestrict op = mOpsToDefault.get(i);
                setUidModeDefault(op.code, op.uid);
            }
        }

        /**
         * Set app ops that belong to restricted permissions.
         *
         * <p>This processed ops previously added by {@link #addOpIfRestricted}
         */
        private void syncOpsOfFgPermissions() {
            int numFgPermOps = mFgPermOps.size();
            for (int i = 0; i < numFgPermOps; i++) {
                FgPermission perm = mFgPermOps.get(i);

                if (mPackageManager.checkPermission(perm.fgPermissionName, perm.packageName)
                        == PackageManager.PERMISSION_GRANTED) {
                    if (mPackageManager.checkPermission(perm.bgPermissionName, perm.packageName)
                            == PackageManager.PERMISSION_GRANTED) {
                        mAppOpsManager.setUidMode(
                                AppOpsManager.permissionToOpCode(perm.fgPermissionName), perm.uid,
                                AppOpsManager.MODE_ALLOWED);
                    } else {
                        mAppOpsManager.setUidMode(
                                AppOpsManager.permissionToOpCode(perm.fgPermissionName), perm.uid,
                                AppOpsManager.MODE_FOREGROUND);
                    }
                } else {
                    mAppOpsManager.setUidMode(
                            AppOpsManager.permissionToOpCode(perm.fgPermissionName), perm.uid,
                            AppOpsManager.MODE_IGNORED);
                }
            }
        }

        /**
         * Synchronize all previously {@link #addPackage added} packages.
         */
        void syncPackages() {
            syncRestrictedOps();
            syncOpsOfFgPermissions();
        }

        /**
         * Add op that belong to a restricted permission for later processing in
         * {@link #syncRestrictedOps}.
         *
         * <p>Note: Called with the package lock held. Do <u>not</u> call into app-op manager.
         *
         * @param permissionInfo The permission that is currently looked at
         * @param pkg The package looked at
         */
        private void addOpIfRestricted(@NonNull PermissionInfo permissionInfo,
                @NonNull PackageInfo pkg) {
            final String permission = permissionInfo.name;
            final int opCode = AppOpsManager.permissionToOpCode(permission);
            final int uid = pkg.applicationInfo.uid;

            if (!permissionInfo.isRestricted()) {
                return;
            }

            final boolean applyRestriction =
                    (mPackageManager.getPermissionFlags(permission, pkg.packageName,
                    mContext.getUser()) & FLAG_PERMISSION_APPLY_RESTRICTION) != 0;

            if (permissionInfo.isHardRestricted()) {
                if (applyRestriction) {
                    mOpsToDefault.add(new OpToRestrict(uid, opCode));
                } else {
                    mOpsToAllow.add(new OpToUnrestrict(uid, pkg.packageName, opCode));
                }
            } else if (permissionInfo.isSoftRestricted()) {
                // Storage uses a special app op to decide the mount state and
                // supports soft restriction where the restricted state allows
                // the permission but only for accessing the medial collections.
                if (Manifest.permission.READ_EXTERNAL_STORAGE.equals(permission)
                        || Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission)) {
                    if (applyRestriction) {
                        mOpsToDefault.add(new OpToRestrict(uid,
                                AppOpsManager.OP_LEGACY_STORAGE));
                    } else if (pkg.applicationInfo.hasRequestedLegacyExternalStorage()) {
                        mOpsToAllow.add(new OpToUnrestrict(uid, pkg.packageName,
                                AppOpsManager.OP_LEGACY_STORAGE));
                    } else {
                        mOpsToIgnore.add(new OpToUnrestrict(uid, pkg.packageName,
                                AppOpsManager.OP_LEGACY_STORAGE));
                    }
                }
            }
        }

        private void addOpIfFgPermissions(@NonNull PermissionInfo permissionInfo,
                @NonNull PackageInfo pkg) {
            if (pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M) {
                // Pre-M apps do not store their fg/bg state in the permissions
                return;
            }

            if (permissionInfo.backgroundPermission == null) {
                return;
            }

            mFgPermOps.add(new FgPermission(pkg.applicationInfo.uid, pkg.packageName,
                    permissionInfo.name, permissionInfo.backgroundPermission));
        }

        /**
         * Add a package for {@link #syncPackages() processing} later.
         *
         * <p>Note: Called with the package lock held. Do <u>not</u> call into app-op manager.
         *
         * @param pkgName The package to add for later processing.
         */
        void addPackage(@NonNull String pkgName) {
            final PackageInfo pkg;
            try {
                pkg = mPackageManager.getPackageInfo(pkgName, GET_PERMISSIONS);
            } catch (NameNotFoundException e) {
                return;
            }

            mAllUids.put(pkg.applicationInfo.uid, pkg.applicationInfo.uid);

            if (pkg.requestedPermissions == null) {
                return;
            }

            for (String permission : pkg.requestedPermissions) {
                final int opCode = AppOpsManager.permissionToOpCode(permission);
                if (opCode == AppOpsManager.OP_NONE) {
                    continue;
                }

                final PermissionInfo permissionInfo;
                try {
                    permissionInfo = mPackageManager.getPermissionInfo(permission, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }

                addOpIfRestricted(permissionInfo, pkg);
                addOpIfFgPermissions(permissionInfo, pkg);
            }
        }

        private void setUidModeAllowedIfDefault(int opCode, int uid, @NonNull String packageName) {
            setUidModeIfDefault(opCode, uid, AppOpsManager.MODE_ALLOWED, packageName);
        }

        private void setUidModeIgnoredIfDefault(int opCode, int uid, @NonNull String packageName) {
            setUidModeIfDefault(opCode, uid, AppOpsManager.MODE_IGNORED, packageName);
        }

        private void setUidModeIfDefault(int opCode, int uid, int mode,
                @NonNull String packageName) {
            final int currentMode = mAppOpsManager.unsafeCheckOpRaw(AppOpsManager
                    .opToPublicName(opCode), uid, packageName);
            if (currentMode == AppOpsManager.MODE_DEFAULT) {
                mAppOpsManager.setUidMode(opCode, uid, mode);
            }
        }

        private void setUidModeDefault(int opCode, int uid) {
            mAppOpsManager.setUidMode(opCode, uid, AppOpsManager.MODE_DEFAULT);
        }

        private class OpToRestrict {
            final int uid;
            final int code;

            OpToRestrict(int uid, int code) {
                this.uid = uid;
                this.code = code;
            }
        }

        private class OpToUnrestrict {
            final int uid;
            final @NonNull String packageName;
            final int code;

            OpToUnrestrict(int uid, @NonNull String packageName, int code) {
                this.uid = uid;
                this.packageName = packageName;
                this.code = code;
            }
        }

        private class FgPermission {
            final int uid;
            final @NonNull String packageName;
            final @NonNull String fgPermissionName;
            final @NonNull String bgPermissionName;

            private FgPermission(int uid, @NonNull String packageName,
                    @NonNull String fgPermissionName, @NonNull String bgPermissionName) {
                this.uid = uid;
                this.packageName = packageName;
                this.fgPermissionName = fgPermissionName;
                this.bgPermissionName = bgPermissionName;
            }
        }
    }
}
