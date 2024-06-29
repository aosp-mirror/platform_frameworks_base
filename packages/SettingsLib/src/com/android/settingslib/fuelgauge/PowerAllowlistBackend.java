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

import androidx.annotation.GuardedBy;
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

    private final Object mAllowlistedAppsLock = new Object();
    private final Object mSysAllowlistedAppsLock = new Object();
    private final Object mDefaultActiveAppsLock = new Object();

    private final Context mAppContext;
    private final IDeviceIdleController mDeviceIdleService;

    @GuardedBy("mAllowlistedAppsLock")
    private final ArraySet<String> mAllowlistedApps = new ArraySet<>();
    @GuardedBy("mSysAllowlistedAppsLock")
    private final ArraySet<String> mSysAllowlistedApps = new ArraySet<>();
    @GuardedBy("mDefaultActiveAppsLock")
    private final ArraySet<String> mDefaultActiveApps = new ArraySet<>();

    @VisibleForTesting
    PowerAllowlistBackend(Context context) {
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
        synchronized (mAllowlistedAppsLock) {
            return mAllowlistedApps.size();
        }
    }

    /** Check if target package is in System allow list */
    public boolean isSysAllowlisted(String pkg) {
        synchronized (mSysAllowlistedAppsLock) {
            return mSysAllowlistedApps.contains(pkg);
        }
    }

    /** Check if target package is in allow list */
    public boolean isAllowlisted(String pkg, int uid) {
        synchronized (mAllowlistedAppsLock) {
            if (mAllowlistedApps.contains(pkg)) {
                return true;
            }
        }
        if (isDefaultActiveApp(pkg, uid)) {
            return true;
        }

        return false;
    }

    /** Check if it is default active app in multiple area */
    public boolean isDefaultActiveApp(String pkg, int uid) {
        // Additionally, check if pkg is default dialer/sms. They are considered essential apps and
        // should be automatically allowlisted (otherwise user may be able to set restriction on
        // them, leading to bad device behavior.)

        synchronized (mDefaultActiveAppsLock) {
            if (mDefaultActiveApps.contains(pkg)) {
                return true;
            }
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

    /** Check if target package is in allow list except idle app */
    public boolean isAllowlistedExceptIdle(String pkg) {
        try {
            return mDeviceIdleService.isPowerSaveWhitelistExceptIdleApp(pkg);
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
            return true;
        }
    }

    /**
     * Check if target package is in allow list except idle app
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
     * Add app into power save allow list
     *
     * @param pkg packageName of the app
     */
    public void addApp(String pkg) {
        addApp(pkg, Process.INVALID_UID);
    }

    /**
     * Add app into power save allow list
     *
     * @param pkg packageName of the app
     * @param uid uid of the app
     */
    public synchronized void addApp(String pkg, int uid) {
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
                            "settings", ActivityManager.RESTRICTION_SOURCE_USER, 0);
                }
            }

            mDeviceIdleService.addPowerSaveWhitelistApp(pkg);
            synchronized (mAllowlistedAppsLock) {
                mAllowlistedApps.add(pkg);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Unable to find package", e);
        }
    }

    /**
     * Remove package from power save allow list
     *
     * @param pkg packageName of the app
     */
    public void removeApp(String pkg) {
        removeApp(pkg, Process.INVALID_UID);
    }

    /**
     * Remove package from power save allow list.
     *
     * @param pkg packageName of the app
     * @param uid uid of the app
     */
    public synchronized void removeApp(String pkg, int uid) {
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
                            "settings", ActivityManager.RESTRICTION_SOURCE_USER, 0L);
                }
            }

            mDeviceIdleService.removePowerSaveWhitelistApp(pkg);
            synchronized (mAllowlistedAppsLock) {
                mAllowlistedApps.remove(pkg);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Unable to reach IDeviceIdleController", e);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Unable to find package", e);
        }
    }

    /** Refresh all of lists */
    @VisibleForTesting
    public synchronized void refreshList() {
        synchronized (mSysAllowlistedAppsLock) {
            mSysAllowlistedApps.clear();
        }
        synchronized (mAllowlistedAppsLock) {
            mAllowlistedApps.clear();
        }
        synchronized (mDefaultActiveAppsLock) {
            mDefaultActiveApps.clear();
        }
        if (mDeviceIdleService == null) {
            return;
        }
        try {
            final String[] allowlistedApps = mDeviceIdleService.getFullPowerWhitelist();
            synchronized (mAllowlistedAppsLock) {
                for (String app : allowlistedApps) {
                    mAllowlistedApps.add(app);
                }
            }
            final String[] sysAllowlistedApps = mDeviceIdleService.getSystemPowerWhitelist();
            synchronized (mSysAllowlistedAppsLock) {
                for (String app : sysAllowlistedApps) {
                    mSysAllowlistedApps.add(app);
                }
            }
            final boolean hasTelephony = mAppContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_TELEPHONY);
            final ComponentName defaultSms = SmsApplication.getDefaultSmsApplication(mAppContext,
                    true /* updateIfNeeded */);
            final String defaultDialer = DefaultDialerManager.getDefaultDialerApplication(
                    mAppContext);

            if (hasTelephony) {
                if (defaultSms != null) {
                    synchronized (mDefaultActiveAppsLock) {
                        mDefaultActiveApps.add(defaultSms.getPackageName());
                    }
                }
                if (!TextUtils.isEmpty(defaultDialer)) {
                    synchronized (mDefaultActiveAppsLock) {
                        mDefaultActiveApps.add(defaultDialer);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to invoke refreshList()", e);
        }
    }

    /** Get the {@link PowerAllowlistBackend} instance */
    public static PowerAllowlistBackend getInstance(Context context) {
        synchronized (PowerAllowlistBackend.class) {
            if (sInstance == null) {
                sInstance = new PowerAllowlistBackend(context);
            }
            return sInstance;
        }
    }

    /** Testing only. Reset the instance to avoid tests affecting each other. */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    public static void resetInstance() {
        synchronized (PowerAllowlistBackend.class) {
            sInstance = null;
        }
    }
}
