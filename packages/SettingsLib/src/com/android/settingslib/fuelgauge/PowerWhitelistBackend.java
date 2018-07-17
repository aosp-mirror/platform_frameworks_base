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
import android.support.annotation.VisibleForTesting;
import android.telecom.DefaultDialerManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.telephony.SmsApplication;
import com.android.internal.util.ArrayUtils;

/**
 * Handles getting/changing the whitelist for the exceptions to battery saving features.
 */
public class PowerWhitelistBackend {

    private static final String TAG = "PowerWhitelistBackend";

    private static final String DEVICE_IDLE_SERVICE = "deviceidle";

    private static PowerWhitelistBackend sInstance;

    private final Context mAppContext;
    private final IDeviceIdleController mDeviceIdleService;
    private final ArraySet<String> mWhitelistedApps = new ArraySet<>();
    private final ArraySet<String> mSysWhitelistedApps = new ArraySet<>();
    private final ArraySet<String> mSysWhitelistedAppsExceptIdle = new ArraySet<>();

    public PowerWhitelistBackend(Context context) {
        this(context, IDeviceIdleController.Stub.asInterface(
                ServiceManager.getService(DEVICE_IDLE_SERVICE)));
    }

    @VisibleForTesting
    PowerWhitelistBackend(Context context, IDeviceIdleController deviceIdleService) {
        mAppContext = context.getApplicationContext();
        mDeviceIdleService = deviceIdleService;
        refreshList();
    }

    public int getWhitelistSize() {
        return mWhitelistedApps.size();
    }

    public boolean isSysWhitelisted(String pkg) {
        return mSysWhitelistedApps.contains(pkg);
    }

    public boolean isWhitelisted(String pkg) {
        if (mWhitelistedApps.contains(pkg)) {
            return true;
        }

        // Additionally, check if pkg is default dialer/sms. They are considered essential apps and
        // should be automatically whitelisted (otherwise user may be able to set restriction on
        // them, leading to bad device behavior.)
        if (!mAppContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return false;
        }
        final ComponentName defaultSms = SmsApplication.getDefaultSmsApplication(mAppContext,
                true /* updateIfNeeded */);
        if (defaultSms != null && TextUtils.equals(pkg, defaultSms.getPackageName())) {
            return true;
        }

        final String defaultDialer = DefaultDialerManager.getDefaultDialerApplication(mAppContext);
        if (TextUtils.equals(pkg, defaultDialer)) {
            return true;
        }

        final DevicePolicyManager devicePolicyManager = mAppContext.getSystemService(
                DevicePolicyManager.class);
        if (devicePolicyManager.packageHasActiveAdmins(pkg)) {
            return true;
        }

        return false;
    }

    public boolean isWhitelisted(String[] pkgs) {
        if (ArrayUtils.isEmpty(pkgs)) {
            return false;
        }
        for (String pkg : pkgs) {
            if (isWhitelisted(pkg)) {
                return true;
            }
        }

        return false;
    }

    public boolean isSysWhitelistedExceptIdle(String pkg) {
        return mSysWhitelistedAppsExceptIdle.contains(pkg);
    }

    public boolean isSysWhitelistedExceptIdle(String[] pkgs) {
        if (ArrayUtils.isEmpty(pkgs)) {
            return false;
        }
        for (String pkg : pkgs) {
            if (isSysWhitelistedExceptIdle(pkg)) {
                return true;
            }
        }

        return false;
    }

    public void addApp(String pkg) {
        try {
            mDeviceIdleService.addPowerSaveWhitelistApp(pkg);
            mWhitelistedApps.add(pkg);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        }
    }

    public void removeApp(String pkg) {
        try {
            mDeviceIdleService.removePowerSaveWhitelistApp(pkg);
            mWhitelistedApps.remove(pkg);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        }
    }

    @VisibleForTesting
    public void refreshList() {
        mSysWhitelistedApps.clear();
        mSysWhitelistedAppsExceptIdle.clear();
        mWhitelistedApps.clear();
        if (mDeviceIdleService == null) {
            return;
        }
        try {
            final String[] whitelistedApps = mDeviceIdleService.getFullPowerWhitelist();
            for (String app : whitelistedApps) {
                mWhitelistedApps.add(app);
            }
            final String[] sysWhitelistedApps = mDeviceIdleService.getSystemPowerWhitelist();
            for (String app : sysWhitelistedApps) {
                mSysWhitelistedApps.add(app);
            }
            final String[] sysWhitelistedAppsExceptIdle =
                    mDeviceIdleService.getSystemPowerWhitelistExceptIdle();
            for (String app : sysWhitelistedAppsExceptIdle) {
                mSysWhitelistedAppsExceptIdle.add(app);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        }
    }

    public static PowerWhitelistBackend getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PowerWhitelistBackend(context);
        }
        return sInstance;
    }

}
