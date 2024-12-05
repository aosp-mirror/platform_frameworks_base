/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.security.advancedprotection.features;

import static android.security.advancedprotection.AdvancedProtectionManager.ADVANCED_PROTECTION_SYSTEM_ENTITY;
import static android.security.advancedprotection.AdvancedProtectionManager.FEATURE_ID_DISALLOW_INSTALL_UNKNOWN_SOURCES;

import android.Manifest;
import android.annotation.NonNull;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserManager;
import android.security.advancedprotection.AdvancedProtectionFeature;
import android.util.Slog;

import com.android.server.LocalServices;

/** @hide */
public final class DisallowInstallUnknownSourcesAdvancedProtectionHook
        extends AdvancedProtectionHook {
    private static final String TAG = "AdvancedProtectionDisallowInstallUnknown";

    private final AdvancedProtectionFeature mFeature = new AdvancedProtectionFeature(
            FEATURE_ID_DISALLOW_INSTALL_UNKNOWN_SOURCES);

    private final ActivityManagerInternal mActivityManagerInternal;
    private final AppOpsManager mAppOpsManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final IPackageManager mIPackageManager;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;

    public DisallowInstallUnknownSourcesAdvancedProtectionHook(@NonNull Context context,
            boolean enabled) {
        super(context, enabled);
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
        mDevicePolicyManager = context.getSystemService(DevicePolicyManager.class);
        mIPackageManager = AppGlobals.getPackageManager();
        mUserManager = context.getSystemService(UserManager.class);
        mPackageManager = context.getPackageManager();

        setRestriction(enabled);
    }

    @NonNull
    @Override
    public AdvancedProtectionFeature getFeature() {
        return mFeature;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private void setRestriction(boolean enabled) {
        if (enabled) {
            Slog.d(TAG, "Setting DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY restriction");
            mDevicePolicyManager.addUserRestrictionGlobally(ADVANCED_PROTECTION_SYSTEM_ENTITY,
                    UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY);
        } else {
            Slog.d(TAG, "Clearing DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY restriction");
            mDevicePolicyManager.clearUserRestrictionGlobally(ADVANCED_PROTECTION_SYSTEM_ENTITY,
                    UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY);
        }
    }

    @Override
    public void onAdvancedProtectionChanged(boolean enabled) {
        setRestriction(enabled);
        if (enabled) return;

        // Leave OP_REQUEST_INSTALL_PACKAGES disabled when APM is disabled.
        Slog.d(TAG, "Setting all OP_REQUEST_INSTALL_PACKAGES to MODE_ERRORED");
        for (UserInfo userInfo : mUserManager.getAliveUsers()) {
            try {
                final String[] packagesWithRequestInstallPermission = mIPackageManager
                        .getAppOpPermissionPackages(
                                Manifest.permission.REQUEST_INSTALL_PACKAGES, userInfo.id);
                for (String packageName : packagesWithRequestInstallPermission) {
                    try {
                        int uid = mPackageManager.getPackageUidAsUser(packageName, userInfo.id);
                        boolean isCallerInstrumented = mActivityManagerInternal
                                .getInstrumentationSourceUid(uid) != Process.INVALID_UID;
                        if (!isCallerInstrumented) {
                            mAppOpsManager.setMode(AppOpsManager.OP_REQUEST_INSTALL_PACKAGES, uid,
                                    packageName, AppOpsManager.MODE_ERRORED);
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.e(TAG, "Couldn't retrieve uid for a package: " + e);
                    }
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Couldn't retrieve packages with REQUEST_INSTALL_PACKAGES."
                        + " getAppOpPermissionPackages() threw the following exception: " + e);
            }
        }
    }
}
