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

package com.android.systemui.sideloaded.car;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.UserHandle;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A class that detects unsafe apps.
 * An app is considered safe if is a system app or installed through whitelisted sources.
 */
@Singleton
public class CarSideLoadedAppDetector {
    private static final String TAG = "CarSideLoadedDetector";

    private final PackageManager mPackageManager;
    private final CarDeviceProvisionedController mCarDeviceProvisionedController;
    private final List<String> mAllowedAppInstallSources;

    @Inject
    public CarSideLoadedAppDetector(@Main Resources resources, PackageManager packageManager,
            CarDeviceProvisionedController deviceProvisionedController) {
        mAllowedAppInstallSources = Arrays.asList(
                resources.getStringArray(R.array.config_allowedAppInstallSources));
        mPackageManager = packageManager;
        mCarDeviceProvisionedController = deviceProvisionedController;
    }

    boolean hasUnsafeInstalledApps() {
        int userId = mCarDeviceProvisionedController.getCurrentUser();

        List<PackageInfo> packages = mPackageManager.getInstalledPackagesAsUser(
                PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                userId);
        for (PackageInfo info : packages) {
            if (info.applicationInfo == null) {
                Log.w(TAG, info.packageName + " does not have application info.");
                return true;
            }

            if (!isSafe(info.applicationInfo)) {
                return true;
            }
        }
        return false;
    }

    boolean isSafe(@NonNull ActivityManager.StackInfo stackInfo) {
        ComponentName componentName = stackInfo.topActivity;
        if (componentName == null) {
            Log.w(TAG, "Stack info does not have top activity: " + stackInfo.stackId);
            return false;
        }
        return isSafe(componentName.getPackageName());
    }

    private boolean isSafe(@NonNull String packageName) {
        if (packageName == null) {
            return false;
        }

        ApplicationInfo applicationInfo;
        try {
            int userId = mCarDeviceProvisionedController.getCurrentUser();
            applicationInfo = mPackageManager.getApplicationInfoAsUser(packageName,
                    PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                    UserHandle.of(userId));

            if (applicationInfo == null) {
                Log.e(TAG, packageName + " did not have an application info!");
                return false;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not get application info for package:" + packageName, e);
            return false;
        }

        return isSafe(applicationInfo);
    }

    private boolean isSafe(@NonNull ApplicationInfo applicationInfo) {
        String packageName = applicationInfo.packageName;

        if (applicationInfo.isSystemApp() || applicationInfo.isUpdatedSystemApp()) {
            return true;
        }

        String initiatingPackageName;
        try {
            InstallSourceInfo sourceInfo = mPackageManager.getInstallSourceInfo(packageName);
            initiatingPackageName = sourceInfo.getInitiatingPackageName();
            if (initiatingPackageName == null) {
                Log.w(TAG, packageName + " does not have an installer name.");
                return false;
            }

            return mAllowedAppInstallSources.contains(initiatingPackageName);
        } catch (IllegalArgumentException | PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
