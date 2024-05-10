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

package com.android.server.devicepolicy;

import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyCache;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.IntentFilterPolicyKey;
import android.app.admin.LockTaskPolicy;
import android.app.admin.PackagePermissionPolicyKey;
import android.app.admin.PackagePolicyKey;
import android.app.admin.PolicyKey;
import android.app.admin.UserRestrictionPolicyKey;
import android.app.admin.flags.Flags;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.permission.AdminPermissionControlParams;
import android.permission.PermissionControllerManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;
import android.view.IWindowManager;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.utils.Slogf;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class PolicyEnforcerCallbacks {

    private static final String LOG_TAG = "PolicyEnforcerCallbacks";

    static boolean setAutoTimezoneEnabled(@Nullable Boolean enabled, @NonNull Context context) {
        if (!DevicePolicyManagerService.isUnicornFlagEnabled()) {
            Slogf.w(LOG_TAG, "Trying to enforce setAutoTimezoneEnabled while flag is off.");
            return true;
        }
        return Binder.withCleanCallingIdentity(() -> {
            Objects.requireNonNull(context);

            int value = enabled != null && enabled ? 1 : 0;
            return Settings.Global.putInt(
                    context.getContentResolver(), Settings.Global.AUTO_TIME_ZONE,
                    value);
        });
    }

    static boolean setPermissionGrantState(
            @Nullable Integer grantState, @NonNull Context context, int userId,
            @NonNull PolicyKey policyKey) {
        if (!DevicePolicyManagerService.isUnicornFlagEnabled()) {
            Slogf.w(LOG_TAG, "Trying to enforce setPermissionGrantState while flag is off.");
            return true;
        }
        return Boolean.TRUE.equals(Binder.withCleanCallingIdentity(() -> {
            if (!(policyKey instanceof PackagePermissionPolicyKey)) {
                throw new IllegalArgumentException("policyKey is not of type "
                        + "PermissionGrantStatePolicyKey, passed in policyKey is: " + policyKey);
            }
            PackagePermissionPolicyKey parsedKey = (PackagePermissionPolicyKey) policyKey;
            Objects.requireNonNull(parsedKey.getPermissionName());
            Objects.requireNonNull(parsedKey.getPackageName());
            Objects.requireNonNull(context);

            int value = grantState == null
                    ? DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                    : grantState;

            // TODO(b/278710449): stop blocking in the main thread
            BlockingCallback callback = new BlockingCallback();
            // TODO: remove canAdminGrantSensorPermissions once we expose a new method in
            //  permissionController that doesn't need it.
            AdminPermissionControlParams permissionParams = new AdminPermissionControlParams(
                    parsedKey.getPackageName(), parsedKey.getPermissionName(), value,
                    /* canAdminGrantSensorPermissions= */ true);
            getPermissionControllerManager(context, UserHandle.of(userId))
                    // TODO: remove callingPackage param and stop passing context.getPackageName()
                    .setRuntimePermissionGrantStateByDeviceAdmin(context.getPackageName(),
                            permissionParams, context.getMainExecutor(), callback::trigger);
            try {
                return callback.await(20_000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // TODO: add logging
                return false;
            }
        }));
    }

    @NonNull
    private static PermissionControllerManager getPermissionControllerManager(
            Context context, UserHandle user) {
        if (user.equals(context.getUser())) {
            return context.getSystemService(PermissionControllerManager.class);
        } else {
            try {
                return context.createPackageContextAsUser(context.getPackageName(), /* flags= */ 0,
                        user).getSystemService(PermissionControllerManager.class);
            } catch (PackageManager.NameNotFoundException notPossible) {
                // not possible
                throw new IllegalStateException(notPossible);
            }
        }
    }

    static boolean enforceSecurityLogging(
            @Nullable Boolean value, @NonNull Context context, int userId,
            @NonNull PolicyKey policyKey) {
        final var dpmi = LocalServices.getService(DevicePolicyManagerInternal.class);
        dpmi.enforceSecurityLoggingPolicy(Boolean.TRUE.equals(value));
        return true;
    }

    static boolean enforceAuditLogging(
            @Nullable Boolean value, @NonNull Context context, int userId,
            @NonNull PolicyKey policyKey) {
        final var dpmi = LocalServices.getService(DevicePolicyManagerInternal.class);
        dpmi.enforceAuditLoggingPolicy(Boolean.TRUE.equals(value));
        return true;
    }

    static boolean setLockTask(
            @Nullable LockTaskPolicy policy, @NonNull Context context, int userId) {
        List<String> packages = Collections.emptyList();
        int flags = LockTaskPolicy.DEFAULT_LOCK_TASK_FLAG;
        if (policy != null) {
            packages = List.copyOf(policy.getPackages());
            flags = policy.getFlags();
        }
        DevicePolicyManagerService.updateLockTaskPackagesLocked(context, packages, userId);
        DevicePolicyManagerService.updateLockTaskFeaturesLocked(flags, userId);
        return true;
    }

    private static class BlockingCallback {
        private final CountDownLatch mLatch = new CountDownLatch(1);
        private final AtomicReference<Boolean> mValue = new AtomicReference<>();
        public void trigger(Boolean value) {
            mValue.set(value);
            mLatch.countDown();
        }

        public Boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            if (!mLatch.await(timeout, unit)) {
                Slogf.e(LOG_TAG, "Callback was not received");
            }
            return mValue.get();
        }
    }

    // TODO: when a local policy exists for a user, this callback will be invoked for this user
    // individually as well as for USER_ALL. This can be optimized by separating local and global
    // enforcement in the policy engine.
    static boolean setUserControlDisabledPackages(
            @Nullable Set<String> packages, Context context, int userId, PolicyKey policyKey) {
        Binder.withCleanCallingIdentity(() -> {
            PackageManagerInternal pmi =
                    LocalServices.getService(PackageManagerInternal.class);
            AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);

            pmi.setOwnerProtectedPackages(userId,
                    packages == null ? null : packages.stream().toList());
            LocalServices.getService(UsageStatsManagerInternal.class)
                    .setAdminProtectedPackages(
                            packages == null ? null : new ArraySet<>(packages), userId);

            if (packages == null || packages.isEmpty()) {
                return;
            }

            for (int user : resolveUsers(userId)) {
                if (Flags.disallowUserControlBgUsageFix()) {
                    setBgUsageAppOp(packages, pmi, user, appOpsManager);
                }
                if (Flags.disallowUserControlStoppedStateFix()) {
                    for (String packageName : packages) {
                        pmi.setPackageStoppedState(packageName, false, user);
                    }
                }
            }
        });
        return true;
    }

    /** Handles USER_ALL expanding it into the list of all intact users. */
    private static List<Integer> resolveUsers(int userId) {
        if (userId == UserHandle.USER_ALL) {
            UserManagerInternal userManager = LocalServices.getService(UserManagerInternal.class);
            return userManager.getUsers(/* excludeDying= */ true)
                    .stream().map(ui -> ui.id).toList();
        } else {
            return List.of(userId);
        }
    }

    private static void setBgUsageAppOp(Set<String> packages, PackageManagerInternal pmi,
            int userId, AppOpsManager appOpsManager) {
        for (var pkg : packages) {
            int packageFlags = MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
            final var appInfo = pmi.getApplicationInfo(pkg, packageFlags, Process.myUid(), userId);
            if (appInfo != null) {
                DevicePolicyManagerService.setBgUsageAppOp(appOpsManager, appInfo);
            }
        }
    }

    static boolean addPersistentPreferredActivity(
            @Nullable ComponentName preferredActivity, @NonNull Context context, int userId,
            @NonNull PolicyKey policyKey) {
        Binder.withCleanCallingIdentity(() -> {
            try {
                if (!(policyKey instanceof IntentFilterPolicyKey)) {
                    throw new IllegalArgumentException("policyKey is not of type "
                            + "IntentFilterPolicyKey, passed in policyKey is: " + policyKey);
                }
                IntentFilterPolicyKey parsedKey =
                        (IntentFilterPolicyKey) policyKey;
                IntentFilter filter = Objects.requireNonNull(parsedKey.getIntentFilter());

                IPackageManager packageManager = AppGlobals.getPackageManager();
                if (preferredActivity != null) {
                    packageManager.addPersistentPreferredActivity(
                            filter, preferredActivity, userId);
                } else {
                    packageManager.clearPersistentPreferredActivity(filter, userId);
                }
                packageManager.flushPackageRestrictionsAsUser(userId);
            } catch (RemoteException re) {
                // Shouldn't happen
                Slog.wtf(LOG_TAG, "Error adding/removing persistent preferred activity", re);
            }
        });
        return true;
    }

    static boolean setUninstallBlocked(
            @Nullable Boolean uninstallBlocked, @NonNull Context context, int userId,
            @NonNull PolicyKey policyKey) {
        return Boolean.TRUE.equals(Binder.withCleanCallingIdentity(() -> {
            if (!(policyKey instanceof PackagePolicyKey)) {
                throw new IllegalArgumentException("policyKey is not of type "
                        + "PackagePolicyKey, passed in policyKey is: " + policyKey);
            }
            PackagePolicyKey parsedKey = (PackagePolicyKey) policyKey;
            String packageName = Objects.requireNonNull(parsedKey.getPackageName());
            DevicePolicyManagerService.setUninstallBlockedUnchecked(
                    packageName,
                    uninstallBlocked != null && uninstallBlocked,
                    userId);
            return true;
        }));
    }

    static boolean setUserRestriction(
            @Nullable Boolean enabled, @NonNull Context context, int userId,
            @NonNull PolicyKey policyKey) {
        return Boolean.TRUE.equals(Binder.withCleanCallingIdentity(() -> {
            if (!(policyKey instanceof UserRestrictionPolicyKey)) {
                throw new IllegalArgumentException("policyKey is not of type "
                        + "UserRestrictionPolicyKey, passed in policyKey is: " + policyKey);
            }
            UserRestrictionPolicyKey parsedKey =
                    (UserRestrictionPolicyKey) policyKey;
            UserManagerInternal userManager = LocalServices.getService(UserManagerInternal.class);
            userManager.setUserRestriction(
                    userId, parsedKey.getRestriction(), enabled != null && enabled);
            return true;
        }));
    }

    static boolean setApplicationHidden(
            @Nullable Boolean hide, @NonNull Context context, int userId,
            @NonNull PolicyKey policyKey) {
        return Boolean.TRUE.equals(Binder.withCleanCallingIdentity(() -> {
            if (!(policyKey instanceof PackagePolicyKey)) {
                throw new IllegalArgumentException("policyKey is not of type "
                        + "PackagePolicyKey, passed in policyKey is: " + policyKey);
            }
            PackagePolicyKey parsedKey = (PackagePolicyKey) policyKey;
            String packageName = Objects.requireNonNull(parsedKey.getPackageName());
            IPackageManager packageManager = AppGlobals.getPackageManager();
            return packageManager.setApplicationHiddenSettingAsUser(
                    packageName, hide != null && hide, userId);
        }));
    }

    static boolean setScreenCaptureDisabled(
            @Nullable Boolean disabled, @NonNull Context context, int userId,
            @NonNull PolicyKey policyKey) {
        Binder.withCleanCallingIdentity(() -> {
            DevicePolicyCache cache = DevicePolicyCache.getInstance();
            if (cache instanceof DevicePolicyCacheImpl) {
                DevicePolicyCacheImpl parsedCache = (DevicePolicyCacheImpl) cache;
                parsedCache.setScreenCaptureDisallowedUser(
                        userId, disabled != null && disabled);
                updateScreenCaptureDisabled();
            }
        });
        return true;
    }

    static boolean setContentProtectionPolicy(
            @Nullable Integer value,
            @NonNull Context context,
            @UserIdInt Integer userId,
            @NonNull PolicyKey policyKey) {
        Binder.withCleanCallingIdentity(
                () -> {
                    DevicePolicyCache cache = DevicePolicyCache.getInstance();
                    if (cache instanceof DevicePolicyCacheImpl cacheImpl) {
                        cacheImpl.setContentProtectionPolicy(userId, value);
                    }
                });
        return true;
    }

    private static void updateScreenCaptureDisabled() {
        BackgroundThread.getHandler().post(() -> {
            try {
                IWindowManager.Stub
                        .asInterface(ServiceManager.getService(Context.WINDOW_SERVICE))
                        .refreshScreenCaptureDisabled();
            } catch (RemoteException e) {
                Slogf.w(LOG_TAG, "Unable to notify WindowManager.", e);
            }
        });
    }

    static boolean setPersonalAppsSuspended(
            @Nullable Boolean suspended, @NonNull Context context, int userId,
            @NonNull PolicyKey policyKey) {
        Binder.withCleanCallingIdentity(() -> {
            if (suspended != null && suspended) {
                suspendPersonalAppsInPackageManager(context, userId);
            } else {
                LocalServices.getService(PackageManagerInternal.class)
                        .unsuspendAdminSuspendedPackages(userId);
            }
        });
        return true;
    }

    private static void suspendPersonalAppsInPackageManager(Context context, int userId) {
        final String[] appsToSuspend = PersonalAppsSuspensionHelper.forUser(context, userId)
                .getPersonalAppsForSuspension();
        Slogf.i(LOG_TAG, "Suspending personal apps: %s", String.join(",", appsToSuspend));
        final String[] failedApps = LocalServices.getService(PackageManagerInternal.class)
                .setPackagesSuspendedByAdmin(userId, appsToSuspend, true);
        if (!ArrayUtils.isEmpty(failedApps)) {
            Slogf.wtf(LOG_TAG, "Failed to suspend apps: " + String.join(",", failedApps));
        }
    }

    static boolean setUsbDataSignalingEnabled(@Nullable Boolean value, @NonNull Context context) {
        return Binder.withCleanCallingIdentity(() -> {
            Objects.requireNonNull(context);

            boolean enabled = value == null || value;
            DevicePolicyManagerService.updateUsbDataSignal(context, enabled);
            return true;
        });
    }
}
