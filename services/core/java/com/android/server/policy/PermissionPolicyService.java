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

import android.annotation.NonNull;
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
import android.os.Process;
import android.os.UserHandle;
import android.permission.PermissionControllerManager;
import android.permission.PermissionManagerInternal;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * This is a permission policy that governs over all permission mechanism
 * such as permissions, app ops, etc. For example, the policy ensures that
 * permission state and app ops is synchronized for cases where there is a
 * dependency between permission state (permissions or permission flags)
 * and app ops - and vise versa.
 */
public final class PermissionPolicyService extends SystemService {
    private static final String PLATFORM_PACKAGE = "android";

    private static final String LOG_TAG = PermissionPolicyService.class.getSimpleName();

    // No need to lock as this is populated on boot when the OS is
    // single threaded and is never mutated until a reboot.
    private static final ArraySet<String> sAllRestrictedPermissions = new ArraySet<>();

    public PermissionPolicyService(@NonNull Context context) {
        super(context);
        cacheAllRestrictedPermissions(context);
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

    private static void cacheAllRestrictedPermissions(@NonNull Context context) {
        try {
            final PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(PLATFORM_PACKAGE, PackageManager.GET_PERMISSIONS);
            for (PermissionInfo permissionInfo : packageInfo.permissions) {
                if (permissionInfo.isRestricted()) {
                    sAllRestrictedPermissions.add(permissionInfo.name);
                }
            }
        } catch (NameNotFoundException impossible) {
            /* cannot happen */
        }
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
                    new PermissionControllerManager(context, FgThread.getHandler());
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

    /**
     * Synchronize a single package.
     */
    private static void synchronizePackagePermissionsAndAppOpsForUser(@NonNull Context context,
            @NonNull String packageName, @UserIdInt int userId) {
        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final PackageParser.Package pkg = packageManagerInternal.getPackage(packageName);
        if (pkg == null) {
            return;
        }
        final PermissionToOpSynchroniser synchroniser = new PermissionToOpSynchroniser(context);
        synchroniser.addPackage(pkg, userId);
        final String[] sharedPkgNames = packageManagerInternal.getPackagesForSharedUserId(
                pkg.mSharedUserId, userId);
        if (sharedPkgNames != null) {
            for (String sharedPkgName : sharedPkgNames) {
                final PackageParser.Package sharedPkg = packageManagerInternal
                        .getPackage(sharedPkgName);
                if (sharedPkg != null) {
                    synchroniser.addPackage(sharedPkg, userId);
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
        final PermissionToOpSynchroniser synchronizer = new PermissionToOpSynchroniser(context);
        packageManagerInternal.forEachPackage((pkg) ->
                synchronizer.addPackage(pkg, userId));
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
         * All ops that need to be restricted
         *
         * @see #syncRestrictedOps
         */
        private final @NonNull ArrayList<OpToRestrict> mOpsToRestrict = new ArrayList<>();
        /**
         * All ops that need to be unrestricted
         *
         * @see #syncRestrictedOps
         */
        private final @NonNull ArrayList<OpToUnrestrict> mOpsToUnrestrict = new ArrayList<>();

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
            final SparseIntArray unprocessedUids = mAllUids.clone();

            // TRICKY: we set the app op for a restricted permission to allow if the app
            // requesting the permission is whitelisted and to deny if the app requesting
            // the permission is not whitelisted. However, there is another case where an
            // app in a shared user can access a component in another app in the same shared
            // user due to being in the same shared user and not by having the permission
            // that guards the component form the rest of the world. We need to handle this.
            // The way we do this is by setting app ops corresponding to non requested
            // restricted permissions to allow as this would allow the shared uid access
            // case and be okay for other apps as they would not have the permission and
            // would fail on the permission checks before reaching the app op check.
            final SparseArray<List<String>> unrequestedRestrictedPermissionsForUid =
                    new SparseArray<>();

            final int unrestrictCount = mOpsToUnrestrict.size();
            for (int i = 0; i < unrestrictCount; i++) {
                final OpToUnrestrict op = mOpsToUnrestrict.get(i);
                setUidModeAllowed(op.code, op.uid, op.packageName);

                // Keep track this permission was requested by the UID.
                List<String> unrequestedRestrictedPermissions =
                        unrequestedRestrictedPermissionsForUid.get(op.uid);
                if (unrequestedRestrictedPermissions == null) {
                    unrequestedRestrictedPermissions = new ArrayList<>(sAllRestrictedPermissions);
                    unrequestedRestrictedPermissionsForUid.put(op.uid,
                            unrequestedRestrictedPermissions);
                }
                unrequestedRestrictedPermissions.remove(AppOpsManager.opToPermission(op.code));

                unprocessedUids.delete(op.uid);
            }
            final int restrictCount = mOpsToRestrict.size();
            for (int i = 0; i < restrictCount; i++) {
                final OpToRestrict op = mOpsToRestrict.get(i);
                setUidModeDefault(op.code, op.uid);

                // Keep track this permission was requested by the UID.
                List<String> unrequestedRestrictedPermissions =
                        unrequestedRestrictedPermissionsForUid.get(op.uid);
                if (unrequestedRestrictedPermissions == null) {
                    unrequestedRestrictedPermissions = new ArrayList<>(sAllRestrictedPermissions);
                    unrequestedRestrictedPermissionsForUid.put(op.uid,
                            unrequestedRestrictedPermissions);
                }
                unrequestedRestrictedPermissions.remove(AppOpsManager.opToPermission(op.code));

                unprocessedUids.delete(op.uid);
            }

            // Give root access
            unprocessedUids.put(Process.ROOT_UID, Process.ROOT_UID);

            // Add records for UIDs that don't use any restricted permissions.
            final int uidCount = unprocessedUids.size();
            for (int i = 0; i < uidCount; i++) {
                final int uid = unprocessedUids.keyAt(i);
                unrequestedRestrictedPermissionsForUid.put(uid,
                        new ArrayList<>(sAllRestrictedPermissions));
            }

            // Flip ops for all unrequested restricted permission for the UIDs.
            final int unrequestedUidCount = unrequestedRestrictedPermissionsForUid.size();
            for (int i = 0; i < unrequestedUidCount; i++) {
                final List<String> unrequestedRestrictedPermissions =
                        unrequestedRestrictedPermissionsForUid.valueAt(i);
                if (unrequestedRestrictedPermissions != null) {
                    final int uid = unrequestedRestrictedPermissionsForUid.keyAt(i);
                    final String[] packageNames = (uid != Process.ROOT_UID)
                            ? mContext.getPackageManager().getPackagesForUid(uid)
                            : new String[] {"root"};
                    if (packageNames == null) {
                        continue;
                    }
                    final int permissionCount = unrequestedRestrictedPermissions.size();
                    for (int j = 0; j < permissionCount; j++) {
                        final String permission = unrequestedRestrictedPermissions.get(j);
                        for (String packageName : packageNames) {
                            setUidModeAllowed(AppOpsManager.permissionToOpCode(permission), uid,
                                    packageName);
                        }
                    }
                }
            }
        }

        /**
         * Synchronize all previously {@link #addPackage added} packages.
         */
        void syncPackages() {
            syncRestrictedOps();
        }

        /**
         * Add op that belong to a restricted permission for later processing in
         * {@link #syncRestrictedOps}.
         *
         * <p>Note: Called with the package lock held. Do <u>not</u> call into app-op manager.
         *
         * @param permissionInfo The permission that is currently looked at
         * @param pkg The package looked at
         * @param userId The user the package belongs to
         */
        private void addOpIfRestricted(@NonNull PermissionInfo permissionInfo,
                @NonNull PackageParser.Package pkg, @UserIdInt int userId) {
            final String permission = permissionInfo.name;
            final int opCode = AppOpsManager.permissionToOpCode(permission);
            final int uid = UserHandle.getUid(userId, UserHandle.getAppId(pkg.applicationInfo.uid));
            final UserHandle userHandle = UserHandle.of(userId);

            if (!permissionInfo.isRestricted()) {
                return;
            }

            final boolean applyRestriction = PackageManager.RESTRICTED_PERMISSIONS_ENABLED
                    && (mPackageManager.getPermissionFlags(permission, pkg.packageName,
                    userHandle) & FLAG_PERMISSION_APPLY_RESTRICTION) != 0;

            if (permissionInfo.isHardRestricted()) {
                if (applyRestriction) {
                    mOpsToRestrict.add(new OpToRestrict(uid, opCode));
                } else {
                    mOpsToUnrestrict.add(new OpToUnrestrict(uid, pkg.packageName, opCode));
                }
            } else if (permissionInfo.isSoftRestricted()) {
                //TODO: Implement soft restrictions like storage here.
            }
        }

        /**
         * Add a package for {@link #syncPackages() processing} later.
         *
         * <p>Note: Called with the package lock held. Do <u>not</u> call into app-op manager.
         *
         * @param pkg The package to add for later processing
         * @param userId The user the package belongs to
         */
        void addPackage(@NonNull PackageParser.Package pkg, @UserIdInt int userId) {
            final int uid = UserHandle.getUid(userId, UserHandle.getAppId(pkg.applicationInfo.uid));

            mAllUids.put(uid, uid);

            final int permissionCount = pkg.requestedPermissions.size();
            for (int i = 0; i < permissionCount; i++) {
                final String permission = pkg.requestedPermissions.get(i);

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

                addOpIfRestricted(permissionInfo, pkg, userId);
            }
        }

        private void setUidModeAllowed(int opCode, int uid, @NonNull String packageName) {
            final int currentMode = mAppOpsManager.unsafeCheckOpRaw(AppOpsManager
                    .opToPublicName(opCode), uid, packageName);
            if (currentMode == AppOpsManager.MODE_DEFAULT) {
                mAppOpsManager.setUidMode(opCode, uid, AppOpsManager.MODE_ALLOWED);
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
    }
}
