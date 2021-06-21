/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.fuelgauge;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IDeviceIdleController;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telecom.DefaultDialerManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.internal.telephony.SmsApplication;
import com.android.internal.util.ArrayUtils;

/**
 * Handles getting/changing the allowlist for the exceptions to battery saving features.
 */
public class PowerAllowlistBackend {

    private static final String TAG = "PowerAllowlistBackend";

    private static final String DEVICE_IDLE_SERVICE = "deviceidle";

    private static PowerAllowlistBackend sInstance;

    private final Context mAppContext;
    private final IDeviceIdleController mDeviceIdleService;
    private final ArraySet<String> mAllowlistedApps = new ArraySet<>();
    private final ArraySet<String> mSysAllowlistedApps = new ArraySet<>();
    private final ArraySet<String> mDefaultActiveApps = new ArraySet<>();

    public PowerAllowlistBackend(Context context) {
        this(context, IDeviceIdleController.Stub.asInterface(
                ServiceManager.getService(DEVICE_IDLE_SERVICE)));
    }

    @VisibleForTesting
    PowerAllowlistBackend(Context context, IDeviceIdleController deviceIdleService) {
        mAppContext = context.getApplicationContext();
        mDeviceIdleService = deviceIdleService;
        refreshList();
    }

    public int getAllowlistSize() {
        return mAllowlistedApps.size();
    }

    /**
    * Check if target package is in System allow list
    */
    public boolean isSysAllowlisted(String pkg) {
        return mSysAllowlistedApps.contains(pkg);
    }

    /**
     * Check if target package is in allow list
     */
    public boolean isAllowlisted(String pkg) {
        if (mAllowlistedApps.contains(pkg)) {
            return true;
        }

        if (isDefaultActiveApp(pkg)) {
            return true;
        }

        return false;
    }

    /**
     * Check if it is default active app in multiple area(i.e. SMS, Dialer, Device admin..)
     */
    public boolean isDefaultActiveApp(String pkg) {
        // Additionally, check if pkg is default dialer/sms. They are considered essential apps and
        // should be automatically allowlisted (otherwise user may be able to set restriction on
        // them, leading to bad device behavior.)

        if (mDefaultActiveApps.contains(pkg)) {
            return true;
        }

        final DevicePolicyManager devicePolicyManager = mAppContext.getSystemService(
                DevicePolicyManager.class);
        if (devicePolicyManager.packageHasActiveAdmins(pkg)) {
            return true;
        }

        return false;
    }

    /**
     *
     * @param pkgs a list of packageName
     * @return true when one of package is in allow list
     */
    public boolean isAllowlisted(String[] pkgs) {
        if (ArrayUtils.isEmpty(pkgs)) {
            return false;
        }
        for (String pkg : pkgs) {
            if (isAllowlisted(pkg)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Add app into power save allow list.
     * @param pkg packageName
     */
    public void addApp(String pkg) {
        try {
            mDeviceIdleService.addPowerSaveWhitelistApp(pkg);
            mAllowlistedApps.add(pkg);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        }
    }

    /**
     * Remove package from power save allow list.
     * @param pkg
     */
    public void removeApp(String pkg) {
        try {
            mDeviceIdleService.removePowerSaveWhitelistApp(pkg);
            mAllowlistedApps.remove(pkg);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        }
    }

    /**
     * Refresh all of lists
     */
    @VisibleForTesting
    public void refreshList() {
        mSysAllowlistedApps.clear();
        mAllowlistedApps.clear();
        mDefaultActiveApps.clear();
        if (mDeviceIdleService == null) {
            return;
        }
        try {
            final String[] allowlistedApps = mDeviceIdleService.getFullPowerWhitelist();
            for (String app : allowlistedApps) {
                mAllowlistedApps.add(app);
            }
            final String[] sysAllowlistedApps = mDeviceIdleService.getSystemPowerWhitelist();
            for (String app : sysAllowlistedApps) {
                mSysAllowlistedApps.add(app);
            }
            final boolean hasTelephony = mAppContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_TELEPHONY);
            final ComponentName defaultSms = SmsApplication.getDefaultSmsApplication(mAppContext,
                    true /* updateIfNeeded */);
            final String defaultDialer = DefaultDialerManager.getDefaultDialerApplication(
                    mAppContext);

            if (hasTelephony) {
                if (defaultSms != null) {
                    mDefaultActiveApps.add(defaultSms.getPackageName());
                }
                if (!TextUtils.isEmpty(defaultDialer)) {
                    mDefaultActiveApps.add(defaultDialer);
                }
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        }
    }

    /**
     * @param context
     * @return a PowerAllowlistBackend object
     */
    public static PowerAllowlistBackend getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PowerAllowlistBackend(context);
        }
        return sInstance;
    }

}
