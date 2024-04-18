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

import static android.provider.DeviceConfig.NAMESPACE_ACTIVITY_MANAGER;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.IDeviceIdleController;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.DeviceConfig;
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

    private static final String SYSTEM_EXEMPT_POWER_RESTRICTIONS_ENABLED =
            "system_exempt_power_restrictions_enabled";
    private static final boolean DEFAULT_SYSTEM_EXEMPT_POWER_RESTRICTIONS_ENABLED = true;

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
    public boolean isAllowlisted(String pkg, int uid) {
        if (mAllowlistedApps.contains(pkg)) {
            return true;
        }

        if (isDefaultActiveApp(pkg, uid)) {
            return true;
        }

        return false;
    }

    /**
     * Check if it is default active app in multiple area(i.e. SMS, Dialer, Device admin..)
     */
    public boolean isDefaultActiveApp(String pkg, int uid) {
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

        final AppOpsManager appOpsManager = mAppContext.getSystemService(AppOpsManager.class);
        if (isSystemExemptFlagEnabled() && appOpsManager.checkOpNoThrow(
                AppOpsManager.OP_SYSTEM_EXEMPT_FROM_POWER_RESTRICTIONS, uid, pkg)
                == AppOpsManager.MODE_ALLOWED) {
            return true;
        }

        if (android.app.admin.flags.Flags.disallowUserControlBgUsageFix()) {
            // App is subject to DevicePolicyManager.setUserControlDisabledPackages() policy.
            final int userId = UserHandle.getUserId(uid);
            if (mAppContext.getPackageManager().isPackageStateProtected(pkg, userId)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isSystemExemptFlagEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_ACTIVITY_MANAGER,
                SYSTEM_EXEMPT_POWER_RESTRICTIONS_ENABLED,
                DEFAULT_SYSTEM_EXEMPT_POWER_RESTRICTIONS_ENABLED);
    }

    /**
     * Check if target package is in allow list except idle app
     */
    public boolean isAllowlistedExceptIdle(String pkg) {
        try {
            return mDeviceIdleService.isPowerSaveWhitelistExceptIdleApp(pkg);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
            return true;
        }
    }

    /**
     *
     * @param pkgs a list of packageName
     * @return true when one of package is in allow list
     */
    public boolean isAllowlisted(String[] pkgs, int uid) {
        if (ArrayUtils.isEmpty(pkgs)) {
            return false;
        }
        for (String pkg : pkgs) {
            if (isAllowlisted(pkg, uid)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Add app into power save allow list.
     * @param pkg packageName of the app
     */
    // TODO: Fix all callers to pass in UID
    public void addApp(String pkg) {
        addApp(pkg, Process.INVALID_UID);
    }

    /**
     * Add app into power save allow list.
     * @param pkg packageName of the app
     * @param uid uid of the app
     */
    public void addApp(String pkg, int uid) {
        try {
            if (android.app.Flags.appRestrictionsApi()) {
                if (uid == Process.INVALID_UID) {
                    uid = mAppContext.getSystemService(PackageManager.class).getPackageUid(pkg, 0);
                }
                final boolean wasInList = isAllowlisted(pkg, uid);

                if (!wasInList) {
                    mAppContext.getSystemService(ActivityManager.class).noteAppRestrictionEnabled(
                            pkg, uid, ActivityManager.RESTRICTION_LEVEL_EXEMPTED,
                            true, ActivityManager.RESTRICTION_REASON_USER,
                            "settings", 0);
                }
            }

            mDeviceIdleService.addPowerSaveWhitelistApp(pkg);
            mAllowlistedApps.add(pkg);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Unable to find package", e);
        }
    }

    /**
     * Remove package from power save allow list.
     * @param pkg packageName of the app
     */
    public void removeApp(String pkg) {
        removeApp(pkg, Process.INVALID_UID);
    }

    /**
     * Remove package from power save allow list.
     * @param pkg packageName of the app
     * @param uid uid of the app
     */
    public void removeApp(String pkg, int uid) {
        try {
            if (android.app.Flags.appRestrictionsApi()) {
                if (uid == Process.INVALID_UID) {
                    uid = mAppContext.getSystemService(PackageManager.class).getPackageUid(pkg, 0);
                }
                final boolean wasInList = isAllowlisted(pkg, uid);
                if (wasInList) {
                    mAppContext.getSystemService(ActivityManager.class).noteAppRestrictionEnabled(
                            pkg, uid, ActivityManager.RESTRICTION_LEVEL_EXEMPTED,
                            false, ActivityManager.RESTRICTION_REASON_USER,
                            "settings", 0);
                }
            }

            mDeviceIdleService.removePowerSaveWhitelistApp(pkg);
            mAllowlistedApps.remove(pkg);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Unable to find package", e);
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
