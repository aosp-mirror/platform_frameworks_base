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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.app.admin.PolicyUpdatesReceiver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.permission.AdminPermissionControlParams;
import android.permission.PermissionControllerManager;
import android.provider.Settings;

import com.android.server.LocalServices;
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
            @NonNull Bundle args) {
        return Boolean.TRUE.equals(Binder.withCleanCallingIdentity(() -> {
            if (args == null
                    || !args.containsKey(PolicyUpdatesReceiver.EXTRA_PACKAGE_NAME)
                    || !args.containsKey(PolicyUpdatesReceiver.EXTRA_PERMISSION_NAME)) {
                throw new IllegalArgumentException("Package name and permission name must be "
                        + "provided as arguments.");
            }

            String packageName = args.getString(PolicyUpdatesReceiver.EXTRA_PACKAGE_NAME);
            String permissionName = args.getString(PolicyUpdatesReceiver.EXTRA_PERMISSION_NAME);
            Objects.requireNonNull(packageName);
            Objects.requireNonNull(permissionName);
            Objects.requireNonNull(context);

            int value = grantState == null
                    ? DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                    : grantState;

            BlockingCallback callback = new BlockingCallback();
            // TODO: remove canAdminGrantSensorPermissions once we expose a new method in
            //  permissionController that doesn't need it.
            AdminPermissionControlParams permissionParams = new AdminPermissionControlParams(
                    packageName, permissionName, value,
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

    static boolean setUserControlDisabledPackages(
            @Nullable Set<String> packages, int userId) {
        Binder.withCleanCallingIdentity(() ->
                LocalServices.getService(PackageManagerInternal.class)
                        .setOwnerProtectedPackages(
                                userId,
                                packages == null ? null : packages.stream().toList()));
        return true;
    }
}
