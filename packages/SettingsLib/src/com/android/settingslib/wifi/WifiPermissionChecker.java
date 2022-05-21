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

package com.android.settingslib.wifi;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_WIFI_STATE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

/**
 * Helper class to check Wi-Fi permissions.
 */
public class WifiPermissionChecker {

    private static final String TAG = "WifiPermChecker";

    private IActivityManager mActivityManager;
    private PackageManager mPackageManager;
    private String mLaunchedPackage;

    public WifiPermissionChecker(Activity activity) {
        this(activity, ActivityManager.getService());
    }

    public WifiPermissionChecker(Activity activity, IActivityManager activityManager) {
        mActivityManager = activityManager;
        mPackageManager = activity.getPackageManager();
        mLaunchedPackage = getLaunchedFromPackage(activity);
    }

    /**
     * Returns the launched package name
     */
    public String getLaunchedPackage() {
        return mLaunchedPackage;
    }

    /**
     * Returns whether the launched package can access Wi-Fi information
     */
    public boolean canAccessWifiState() {
        return checkPermission(ACCESS_WIFI_STATE);
    }

    /**
     * Returns whether the launched package can access precise location
     */
    public boolean canAccessFineLocation() {
        return checkPermission(ACCESS_FINE_LOCATION);
    }

    private boolean checkPermission(String permission) {
        if (mPackageManager == null || TextUtils.isEmpty(mLaunchedPackage)) {
            Log.e(TAG, "Failed to check package permission!"
                    + " {PackageManager:" + mPackageManager
                    + ", LaunchedPackage:" + mLaunchedPackage + "}");
            return false;
        }

        if (mPackageManager.checkPermission(permission, mLaunchedPackage) == PERMISSION_GRANTED) {
            return true;
        }

        Log.w(TAG, "The launched package does not have the required permission!"
                + " {LaunchedPackage:" + mLaunchedPackage + ", Permission:" + permission + "}");
        return false;
    }

    private String getLaunchedFromPackage(Activity activity) {
        try {
            return mActivityManager.getLaunchedFromPackage(activity.getActivityToken());
        } catch (RemoteException e) {
            Log.e(TAG, "Can not get the launched package from activity manager!");
            return null;
        }
    }
}
