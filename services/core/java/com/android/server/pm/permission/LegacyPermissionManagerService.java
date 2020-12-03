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
import android.content.Context;
import android.os.Binder;

import com.android.server.LocalServices;
import com.android.server.pm.PackageManagerServiceUtils;

/**
 * Legacy permission manager service.
 */
public class LegacyPermissionManagerService {
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
        mDefaultPermissionGrantPolicy = new DefaultPermissionGrantPolicy(context);
        LocalServices.addService(LegacyPermissionManagerInternal.class, new Internal());
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

        // TODO(zhanghai): The following methods should be moved to a new AIDL to support
        //  the legacy PermissionManager directly in a later CL.

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
    }
}
