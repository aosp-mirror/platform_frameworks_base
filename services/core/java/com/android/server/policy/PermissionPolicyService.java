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
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageManagerInternal.PackageListObserver;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.permission.PermissionControllerManager;
import android.permission.PermissionManagerInternal;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.util.function.QuadConsumer;
import com.android.internal.util.function.TriConsumer;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

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

    private static void onRestrictedPermissionEnabledChange(@NonNull Context context) {
        final PermissionManagerInternal permissionManagerInternal = LocalServices
                .getService(PermissionManagerInternal.class);
        final UserManagerInternal userManagerInternal = LocalServices.getService(
                UserManagerInternal.class);
        for (int userId : userManagerInternal.getUserIds()) {
            synchronizePermissionsAndAppOpsForUser(context, userId);
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

    private static void synchronizePackagePermissionsAndAppOpsForUser(@NonNull Context context,
            @NonNull String packageName, @UserIdInt int userId) {
        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final PackageParser.Package pkg = packageManagerInternal
                .getPackage(packageName);
        if (pkg != null) {
            PermissionToOpSynchronizer.syncPackage(context, pkg, userId);
        }
    }

    private static void synchronizePermissionsAndAppOpsForUser(@NonNull Context context,
            @UserIdInt int userId) {
        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final PermissionToOpSynchronizer synchronizer = new PermissionToOpSynchronizer(context);
        packageManagerInternal.forEachPackage((pkg) ->
                synchronizer.addPackage(context, pkg, userId));
        synchronizer.syncPackages();
    }

    private static class PermissionToOpSynchronizer {
        private final @NonNull Context mContext;

        private final @NonNull SparseArray<String> mPackageNames = new SparseArray<>();
        private final @NonNull SparseIntArray mAllowedUidOps = new SparseIntArray();
        private final @NonNull SparseIntArray mDefaultUidOps = new SparseIntArray();

        PermissionToOpSynchronizer(@NonNull Context context) {
            mContext = context;
        }

        private void addPackage(@NonNull Context context,
                @NonNull PackageParser.Package pkg, @UserIdInt int userId) {
            addPackage(context, pkg, userId, this::addAllowedEntry, this::addIgnoredEntry);
        }

        void syncPackages() {
            final AppOpsManager appOpsManager = mContext.getSystemService(AppOpsManager.class);
            final int allowedCount = mAllowedUidOps.size();
            for (int i = 0; i < allowedCount; i++) {
                final int opCode = mAllowedUidOps.keyAt(i);
                final int uid = mAllowedUidOps.valueAt(i);
                final String packageName = mPackageNames.valueAt(i);
                setUidModeAllowed(appOpsManager, opCode, uid, packageName);
            }
            final int defaultCount = mDefaultUidOps.size();
            for (int i = 0; i < defaultCount; i++) {
                final int opCode = mDefaultUidOps.keyAt(i);
                final int uid = mDefaultUidOps.valueAt(i);
                setUidModeDefault(appOpsManager, opCode, uid);
            }
        }

        static void syncPackage(@NonNull Context context, @NonNull PackageParser.Package pkg,
                @UserIdInt int userId) {
            addPackage(context, pkg, userId, PermissionToOpSynchronizer::setUidModeAllowed,
                    PermissionToOpSynchronizer::setUidModeDefault);
        }

        private static void addPackage(@NonNull Context context,
                @NonNull PackageParser.Package pkg, @UserIdInt int userId,
                @NonNull QuadConsumer<AppOpsManager, Integer, Integer, String> allowedConsumer,
                @NonNull TriConsumer<AppOpsManager, Integer, Integer> defaultConsumer) {
            final PackageManager packageManager = context.getPackageManager();
            final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);

            final int uid = UserHandle.getUid(userId, UserHandle.getAppId(pkg.applicationInfo.uid));
            final UserHandle userHandle = UserHandle.of(userId);

            final int permissionCount = pkg.requestedPermissions.size();
            for (int i = 0; i < permissionCount; i++) {
                final String permission = pkg.requestedPermissions.get(i);

                final int opCode = AppOpsManager.permissionToOpCode(permission);
                if (opCode == AppOpsManager.OP_NONE) {
                    continue;
                }

                final PermissionInfo permissionInfo;
                try {
                    permissionInfo = packageManager.getPermissionInfo(permission, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }

                if (!permissionInfo.isRestricted()) {
                    continue;
                }

                final boolean applyRestriction = PackageManager.RESTRICTED_PERMISSIONS_ENABLED
                        && (packageManager.getPermissionFlags(permission, pkg.packageName,
                                userHandle) & FLAG_PERMISSION_APPLY_RESTRICTION) != 0;

                if (permissionInfo.isHardRestricted()) {
                    if (applyRestriction) {
                        defaultConsumer.accept(appOpsManager, opCode, uid);
                    } else {
                        allowedConsumer.accept(appOpsManager, opCode, uid, pkg.packageName);
                    }
                } else if (permissionInfo.isSoftRestricted()) {
                    //TODO: Implement soft restrictions like storage here.
                }
            }
        }

        @SuppressWarnings("unused")
        private void addAllowedEntry(@NonNull AppOpsManager appOpsManager, int opCode,
                int uid, @NonNull String packageName) {
            mPackageNames.put(opCode, packageName);
            mAllowedUidOps.put(opCode, uid);
        }

        @SuppressWarnings("unused")
        private void addIgnoredEntry(@NonNull AppOpsManager appOpsManager,
                int opCode, int uid) {
            mDefaultUidOps.put(opCode, uid);
        }

        private static void setUidModeAllowed(@NonNull AppOpsManager appOpsManager,
                int opCode, int uid, @NonNull String packageName) {
            final int currentMode = appOpsManager.unsafeCheckOpRaw(AppOpsManager
                    .opToPublicName(opCode), uid, packageName);
            if (currentMode == AppOpsManager.MODE_DEFAULT) {
                appOpsManager.setUidMode(opCode, uid, AppOpsManager.MODE_ALLOWED);
            }
        }

        private static void setUidModeDefault(@NonNull AppOpsManager appOpsManager,
                int opCode, int uid) {
            appOpsManager.setUidMode(opCode, uid, AppOpsManager.MODE_DEFAULT);
        }
    }
}
