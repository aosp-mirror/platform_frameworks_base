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

package com.android.server.crashrecovery;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.net.ConnectivityModuleConnector;
import android.sysprop.CrashRecoveryProperties;
import android.text.TextUtils;
import android.util.Slog;

import com.android.server.PackageWatchdog;
import com.android.server.pm.ApexManager;

import java.util.Collections;
import java.util.List;

/**
 * Provides helper methods for the CrashRecovery APEX
 *  TODO: b/354112511 Add tests for this class when it is finalized.
 * @hide
 */
public final class CrashRecoveryHelper {
    private static final String TAG = "CrashRecoveryHelper";

    private final ApexManager mApexManager;
    private final Context mContext;
    private final ConnectivityModuleConnector mConnectivityModuleConnector;


    /** @hide */
    public CrashRecoveryHelper(@NonNull Context context) {
        mContext = context;
        mApexManager = ApexManager.getInstance();
        mConnectivityModuleConnector = ConnectivityModuleConnector.getInstance();
    }

    /**
     * Returns true if the package name is the name of a module.
     * If the package is an APK inside an APEX then it will use the parent's APEX package name
     * do determine if it is a module or not.
     * @hide
     */
    @AnyThread
    public boolean isModule(@NonNull String packageName) {
        String apexPackageName =
                mApexManager.getActiveApexPackageNameContainingPackage(packageName);
        if (apexPackageName != null) {
            packageName = apexPackageName;
        }

        PackageManager pm = mContext.getPackageManager();
        try {
            return pm.getModuleInfo(packageName, 0) != null;
        } catch (PackageManager.NameNotFoundException ignore) {
            return false;
        }
    }

    /**
     * Register health listeners for Connectivity packages health.
     *
     * TODO: b/354112511 Have an internal method to trigger a rollback by reporting high severity errors,
     * and rely on ActivityManager to inform the watchdog of severe network stack crashes
     * instead of having this listener in parallel.
     */
    public void registerConnectivityModuleHealthListener() {
        // register listener for ConnectivityModule
        mConnectivityModuleConnector.registerHealthListener(
                packageName -> {
                final VersionedPackage pkg = getVersionedPackage(packageName);
                if (pkg == null) {
                    Slog.wtf(TAG, "NetworkStack failed but could not find its package");
                    return;
                }
                final List<VersionedPackage> pkgList = Collections.singletonList(pkg);
                PackageWatchdog.getInstance(mContext).onPackageFailure(pkgList,  PackageWatchdog.FAILURE_REASON_EXPLICIT_HEALTH_CHECK);
            });
    }

    @Nullable
    private VersionedPackage getVersionedPackage(String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        if (pm == null || TextUtils.isEmpty(packageName)) {
            return null;
        }
        try {
            final long versionCode = getPackageInfo(packageName).getLongVersionCode();
            return new VersionedPackage(packageName, versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Gets PackageInfo for the given package. Matches any user and apex.
     *
     * @throws PackageManager.NameNotFoundException if no such package is installed.
     */
    private PackageInfo getPackageInfo(String packageName)
            throws PackageManager.NameNotFoundException {
        PackageManager pm = mContext.getPackageManager();
        try {
            // The MATCH_ANY_USER flag doesn't mix well with the MATCH_APEX
            // flag, so make two separate attempts to get the package info.
            // We don't need both flags at the same time because we assume
            // apex files are always installed for all users.
            return pm.getPackageInfo(packageName, PackageManager.MATCH_ANY_USER);
        } catch (PackageManager.NameNotFoundException e) {
            return pm.getPackageInfo(packageName, PackageManager.MATCH_APEX);
        }
    }

    /**
     * Check if we're currently attempting to reboot for a factory reset. This method must
     * return true if RescueParty tries to reboot early during a boot loop, since the device
     * will not be fully booted at this time.
     */
    public static boolean isRecoveryTriggeredReboot() {
        return isFactoryResetPropertySet() || isRebootPropertySet();
    }

    static boolean isFactoryResetPropertySet() {
        return CrashRecoveryProperties.attemptingFactoryReset().orElse(false);
    }

    static boolean isRebootPropertySet() {
        return CrashRecoveryProperties.attemptingReboot().orElse(false);
    }
}
