/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.permission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.permission.ILegacyPermissionManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.pm.PackageManagerServiceUtils;

/**
 * Legacy permission manager service.
 */
public class LegacyPermissionManagerService extends ILegacyPermissionManager.Stub {
    private static final String TAG = "PackageManager";

    /** Injector that can be used to facilitate testing. */
    private final Injector mInjector;

    @NonNull
    private final DefaultPermissionGrantPolicy mDefaultPermissionGrantPolicy;

    /**
     * Get or create an instance of this class for use by other components.
     * <p>
     * This method is not thread-safe.
     *
     * @param context the {@link Context}
     * @return the internal instance
     */
    @NonNull
    public static LegacyPermissionManagerInternal create(@NonNull Context context) {
        LegacyPermissionManagerInternal legacyPermissionManagerInternal = LocalServices.getService(
                LegacyPermissionManagerInternal.class);
        if (legacyPermissionManagerInternal == null) {
            new LegacyPermissionManagerService(context);
            legacyPermissionManagerInternal = LocalServices.getService(
                    LegacyPermissionManagerInternal.class);
        }
        return legacyPermissionManagerInternal;
    }

    private LegacyPermissionManagerService(@NonNull Context context) {
        this(context, new Injector(context));

        LocalServices.addService(LegacyPermissionManagerInternal.class, new Internal());
        ServiceManager.addService("legacy_permission", this);
    }

    @VisibleForTesting
    LegacyPermissionManagerService(@NonNull Context context, @NonNull Injector injector) {
        mInjector = injector;
        mDefaultPermissionGrantPolicy = new DefaultPermissionGrantPolicy(context);
    }

    @Override
    public int checkDeviceIdentifierAccess(@Nullable String packageName, @Nullable String message,
            @Nullable String callingFeatureId, int pid, int uid) {
        // If the check is being requested by an app then only allow the app to query its own
        // access status.
        int callingUid = mInjector.getCallingUid();
        int callingPid = mInjector.getCallingPid();
        if (UserHandle.getAppId(callingUid) >= Process.FIRST_APPLICATION_UID && (callingUid != uid
                || callingPid != pid)) {
            String response = String.format(
                    "Calling uid %d, pid %d cannot check device identifier access for package %s "
                            + "(uid=%d, pid=%d)",
                    callingUid, callingPid, packageName, uid, pid);
            Log.w(TAG, response);
            throw new SecurityException(response);
        }
        // Allow system and root access to the device identifiers.
        final int appId = UserHandle.getAppId(uid);
        if (appId == Process.SYSTEM_UID || appId == Process.ROOT_UID) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // Allow access to packages that have the READ_PRIVILEGED_PHONE_STATE permission.
        if (mInjector.checkPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE, pid,
                uid) == PackageManager.PERMISSION_GRANTED) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // If the calling package is not null then perform the appop and device / profile owner
        // check.
        if (packageName != null) {
            // Allow access to a package that has been granted the READ_DEVICE_IDENTIFIERS appop.
            final long token = mInjector.clearCallingIdentity();
            AppOpsManager appOpsManager = (AppOpsManager) mInjector.getSystemService(
                    Context.APP_OPS_SERVICE);
            try {
                if (appOpsManager.noteOpNoThrow(AppOpsManager.OPSTR_READ_DEVICE_IDENTIFIERS, uid,
                        packageName, callingFeatureId, message) == AppOpsManager.MODE_ALLOWED) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            } finally {
                mInjector.restoreCallingIdentity(token);
            }
            // Check if the calling packages meets the device / profile owner requirements for
            // identifier access.
            DevicePolicyManager devicePolicyManager =
                    (DevicePolicyManager) mInjector.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (devicePolicyManager != null && devicePolicyManager.hasDeviceIdentifierAccess(
                    packageName, pid, uid)) {
                return PackageManager.PERMISSION_GRANTED;
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public void grantDefaultPermissionsToActiveLuiApp(String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils.enforceSystemOrPhoneCaller(
                "grantDefaultPermissionsToActiveLuiApp", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .grantDefaultPermissionsToActiveLuiApp(packageName, userId));
    }

    @Override
    public void revokeDefaultPermissionsFromLuiApps(String[] packageNames, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils.enforceSystemOrPhoneCaller(
                "revokeDefaultPermissionsFromLuiApps", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .revokeDefaultPermissionsFromLuiApps(packageNames, userId));
    }

    @Override
    public void grantDefaultPermissionsToEnabledImsServices(String[] packageNames, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils.enforceSystemOrPhoneCaller(
                "grantDefaultPermissionsToEnabledImsServices", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .grantDefaultPermissionsToEnabledImsServices(packageNames, userId));
    }

    @Override
    public void grantDefaultPermissionsToEnabledTelephonyDataServices(
            String[] packageNames, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils.enforceSystemOrPhoneCaller(
                "grantDefaultPermissionsToEnabledTelephonyDataServices", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .grantDefaultPermissionsToEnabledTelephonyDataServices(packageNames, userId));
    }

    @Override
    public void revokeDefaultPermissionsFromDisabledTelephonyDataServices(
            String[] packageNames, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils.enforceSystemOrPhoneCaller(
                "revokeDefaultPermissionsFromDisabledTelephonyDataServices", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .revokeDefaultPermissionsFromDisabledTelephonyDataServices(packageNames,
                        userId));
    }

    @Override
    public void grantDefaultPermissionsToEnabledCarrierApps(String[] packageNames, int userId) {
        final int callingUid = Binder.getCallingUid();
        PackageManagerServiceUtils.enforceSystemOrPhoneCaller(
                "grantPermissionsToEnabledCarrierApps", callingUid);
        Binder.withCleanCallingIdentity(() -> mDefaultPermissionGrantPolicy
                .grantDefaultPermissionsToEnabledCarrierApps(packageNames, userId));
    }

    private class Internal implements LegacyPermissionManagerInternal {
        @Override
        public void setDialerAppPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setDialerAppPackagesProvider(provider);
        }

        @Override
        public void setLocationExtraPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setLocationExtraPackagesProvider(provider);
        }

        @Override
        public void setLocationPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setLocationPackagesProvider(provider);
        }

        @Override
        public void setSimCallManagerPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setSimCallManagerPackagesProvider(provider);
        }

        @Override
        public void setSmsAppPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setSmsAppPackagesProvider(provider);
        }

        @Override
        public void setSyncAdapterPackagesProvider(SyncAdapterPackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setSyncAdapterPackagesProvider(provider);
        }

        @Override
        public void setUseOpenWifiAppPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setUseOpenWifiAppPackagesProvider(provider);
        }

        @Override
        public void setVoiceInteractionPackagesProvider(PackagesProvider provider) {
            mDefaultPermissionGrantPolicy.setVoiceInteractionPackagesProvider(provider);
        }

        @Override
        public void grantDefaultPermissionsToDefaultSimCallManager(String packageName, int userId) {
            mDefaultPermissionGrantPolicy.grantDefaultPermissionsToDefaultSimCallManager(
                    packageName, userId);
        }

        @Override
        public void grantDefaultPermissionsToDefaultUseOpenWifiApp(String packageName, int userId) {
            mDefaultPermissionGrantPolicy.grantDefaultPermissionsToDefaultUseOpenWifiApp(
                    packageName, userId);
        }

        @Override
        public void grantDefaultPermissions(int userId) {
            mDefaultPermissionGrantPolicy.grantDefaultPermissions(userId);
        }

        @Override
        public void scheduleReadDefaultPermissionExceptions() {
            mDefaultPermissionGrantPolicy.scheduleReadDefaultPermissionExceptions();
        }
    }

    /**
     * Allows injection of services and method responses to facilitate testing.
     *
     * <p>Test classes can create a mock of this class and pass it to the PermissionManagerService
     * constructor to control behavior of services and external methods during execution.
     * @hide
     */
    @VisibleForTesting
    public static class Injector {
        private final Context mContext;

        /**
         * Public constructor that accepts a {@code context} within which to operate.
         */
        public Injector(@NonNull Context context) {
            mContext = context;
        }

        /**
         * Returns the UID of the calling package.
         */
        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        /**
         * Returns the process ID of the calling package.
         */
        public int getCallingPid() {
            return Binder.getCallingPid();
        }

        /**
         * Checks if the package running under the specified {@code pid} and {@code uid} has been
         * granted the provided {@code permission}.
         *
         * @return {@link PackageManager#PERMISSION_GRANTED} if the package has been granted the
         * permission, {@link PackageManager#PERMISSION_DENIED} otherwise
         */
        public int checkPermission(@NonNull String permission, int pid, int uid) {
            return mContext.checkPermission(permission, pid, uid);
        }

        /**
         * Clears the calling identity to allow subsequent calls to be treated as coming from this
         * package.
         *
         * @return a token that can be used to restore the calling identity
         */
        public long clearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        /**
         * Restores the calling identity to that of the calling package based on the provided
         * {@code token}.
         */
        public void restoreCallingIdentity(long token) {
            Binder.restoreCallingIdentity(token);
        }

        /**
         * Returns the system service with the provided {@code name}.
         */
        public Object getSystemService(@NonNull String name) {
            return mContext.getSystemService(name);
        }
    }
}
