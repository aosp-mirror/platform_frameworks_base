/**
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.android.server.usage;

import static com.android.server.SystemService.PHASE_BOOT_COMPLETED;
import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.usage.UsageStatsService.MSG_REPORT_EVENT;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.NetworkScoreManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the standby state of an app, listening to various events.
 */
public class AppStandbyController {

    private static final String TAG = "AppStandbyController";
    private static final boolean DEBUG = false;

    static final boolean COMPRESS_TIME = false;
    private static final long ONE_MINUTE = 60 * 1000;

    // To name the lock for stack traces
    static class Lock {}

    /** Lock to protect the app's standby state. Required for calls into AppIdleHistory */
    private final Object mAppIdleLock = new Lock();

    /** Keeps the history and state for each app. */
    @GuardedBy("mAppIdleLock")
    private AppIdleHistory mAppIdleHistory;

    @GuardedBy("mAppIdleLock")
    private ArrayList<UsageStatsManagerInternal.AppIdleStateChangeListener>
            mPackageAccessListeners = new ArrayList<>();

    /** Whether we've queried the list of carrier privileged apps. */
    @GuardedBy("mAppIdleLock")
    private boolean mHaveCarrierPrivilegedApps;

    /** List of carrier-privileged apps that should be excluded from standby */
    @GuardedBy("mAppIdleLock")
    private List<String> mCarrierPrivilegedApps;

    // Messages for the handler
    static final int MSG_INFORM_LISTENERS = 3;
    static final int MSG_FORCE_IDLE_STATE = 4;
    static final int MSG_CHECK_IDLE_STATES = 5;
    static final int MSG_CHECK_PAROLE_TIMEOUT = 6;
    static final int MSG_PAROLE_END_TIMEOUT = 7;
    static final int MSG_REPORT_CONTENT_PROVIDER_USAGE = 8;
    static final int MSG_PAROLE_STATE_CHANGED = 9;
    static final int MSG_ONE_TIME_CHECK_IDLE_STATES = 10;

    long mAppIdleScreenThresholdMillis;
    long mCheckIdleIntervalMillis;
    long mAppIdleWallclockThresholdMillis;
    long mAppIdleParoleIntervalMillis;
    long mAppIdleParoleDurationMillis;
    boolean mAppIdleEnabled;
    boolean mAppIdleTempParoled;
    boolean mCharging;
    private long mLastAppIdleParoledTime;
    private boolean mSystemServicesReady = false;

    private volatile boolean mPendingOneTimeCheckIdleStates;

    private final Handler mHandler;
    private final Context mContext;

    private DisplayManager mDisplayManager;
    private IDeviceIdleController mDeviceIdleController;
    private AppWidgetManager mAppWidgetManager;
    private IBatteryStats mBatteryStats;
    private PowerManager mPowerManager;
    private PackageManager mPackageManager;
    private PackageManagerInternal mPackageManagerInternal;

    AppStandbyController(Context context, Looper looper) {
        mContext = context;
        mHandler = new AppStandbyHandler(looper);
        mPackageManager = mContext.getPackageManager();
        mAppIdleEnabled = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableAutoPowerModes);
        if (mAppIdleEnabled) {
            IntentFilter deviceStates = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            deviceStates.addAction(BatteryManager.ACTION_DISCHARGING);
            deviceStates.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            mContext.registerReceiver(new DeviceStateReceiver(), deviceStates);
        }
        synchronized (mAppIdleLock) {
            mAppIdleHistory = new AppIdleHistory(SystemClock.elapsedRealtime());
        }

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");

        mContext.registerReceiverAsUser(new PackageReceiver(), UserHandle.ALL, packageFilter,
                null, mHandler);
    }

    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            // Observe changes to the threshold
            SettingsObserver settingsObserver = new SettingsObserver(mHandler);
            settingsObserver.registerObserver();
            settingsObserver.updateSettings();

            mAppWidgetManager = mContext.getSystemService(AppWidgetManager.class);
            mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                    ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
            mBatteryStats = IBatteryStats.Stub.asInterface(
                    ServiceManager.getService(BatteryStats.SERVICE_NAME));
            mDisplayManager = (DisplayManager) mContext.getSystemService(
                    Context.DISPLAY_SERVICE);
            mPowerManager = mContext.getSystemService(PowerManager.class);
            mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);

            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
            synchronized (mAppIdleLock) {
                mAppIdleHistory.updateDisplay(isDisplayOn(), SystemClock.elapsedRealtime());
            }

            if (mPendingOneTimeCheckIdleStates) {
                postOneTimeCheckIdleStates();
            }

            mSystemServicesReady = true;
        } else if (phase == PHASE_BOOT_COMPLETED) {
            setChargingState(mContext.getSystemService(BatteryManager.class).isCharging());
        }
    }

    void reportContentProviderUsage(String authority, String providerPkgName, int userId) {
        // Get sync adapters for the authority
        String[] packages = ContentResolver.getSyncAdapterPackagesForAuthorityAsUser(
                authority, userId);
        for (String packageName: packages) {
            // Only force the sync adapters to active if the provider is not in the same package and
            // the sync adapter is a system package.
            try {
                PackageInfo pi = mPackageManager.getPackageInfoAsUser(
                        packageName, PackageManager.MATCH_SYSTEM_ONLY, userId);
                if (pi == null || pi.applicationInfo == null) {
                    continue;
                }
                if (!packageName.equals(providerPkgName)) {
                    setAppIdleAsync(packageName, false, userId);
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Shouldn't happen
            }
        }
    }

    void setChargingState(boolean charging) {
        synchronized (mAppIdleLock) {
            if (mCharging != charging) {
                mCharging = charging;
                postParoleStateChanged();
            }
        }
    }

    /** Paroled here means temporary pardon from being inactive */
    void setAppIdleParoled(boolean paroled) {
        synchronized (mAppIdleLock) {
            final long now = System.currentTimeMillis();
            if (mAppIdleTempParoled != paroled) {
                mAppIdleTempParoled = paroled;
                if (DEBUG) Slog.d(TAG, "Changing paroled to " + mAppIdleTempParoled);
                if (paroled) {
                    postParoleEndTimeout();
                } else {
                    mLastAppIdleParoledTime = now;
                    postNextParoleTimeout(now);
                }
                postParoleStateChanged();
            }
        }
    }

    boolean isParoledOrCharging() {
        synchronized (mAppIdleLock) {
            return mAppIdleTempParoled || mCharging;
        }
    }

    private void postNextParoleTimeout(long now) {
        if (DEBUG) Slog.d(TAG, "Posting MSG_CHECK_PAROLE_TIMEOUT");
        mHandler.removeMessages(MSG_CHECK_PAROLE_TIMEOUT);
        // Compute when the next parole needs to happen. We check more frequently than necessary
        // since the message handler delays are based on elapsedRealTime and not wallclock time.
        // The comparison is done in wallclock time.
        long timeLeft = (mLastAppIdleParoledTime + mAppIdleParoleIntervalMillis) - now;
        if (timeLeft < 0) {
            timeLeft = 0;
        }
        mHandler.sendEmptyMessageDelayed(MSG_CHECK_PAROLE_TIMEOUT, timeLeft);
    }

    private void postParoleEndTimeout() {
        if (DEBUG) Slog.d(TAG, "Posting MSG_PAROLE_END_TIMEOUT");
        mHandler.removeMessages(MSG_PAROLE_END_TIMEOUT);
        mHandler.sendEmptyMessageDelayed(MSG_PAROLE_END_TIMEOUT, mAppIdleParoleDurationMillis);
    }

    private void postParoleStateChanged() {
        if (DEBUG) Slog.d(TAG, "Posting MSG_PAROLE_STATE_CHANGED");
        mHandler.removeMessages(MSG_PAROLE_STATE_CHANGED);
        mHandler.sendEmptyMessage(MSG_PAROLE_STATE_CHANGED);
    }

    void postCheckIdleStates(int userId) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_CHECK_IDLE_STATES, userId, 0));
    }

    /**
     * We send a different message to check idle states once, otherwise we would end up
     * scheduling a series of repeating checkIdleStates each time we fired off one.
     */
    void postOneTimeCheckIdleStates() {
        if (mDeviceIdleController == null) {
            // Not booted yet; wait for it!
            mPendingOneTimeCheckIdleStates = true;
        } else {
            mHandler.sendEmptyMessage(MSG_ONE_TIME_CHECK_IDLE_STATES);
            mPendingOneTimeCheckIdleStates = false;
        }
    }

    /**
     * Check all running users' or specified user's apps to see if they enter an idle state.
     * @return Returns whether checking should continue periodically.
     */
    boolean checkIdleStates(int checkUserId) {
        if (!mAppIdleEnabled) {
            return false;
        }

        final int[] runningUserIds;
        try {
            runningUserIds = ActivityManager.getService().getRunningUserIds();
            if (checkUserId != UserHandle.USER_ALL
                    && !ArrayUtils.contains(runningUserIds, checkUserId)) {
                return false;
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }

        final long elapsedRealtime = SystemClock.elapsedRealtime();
        for (int i = 0; i < runningUserIds.length; i++) {
            final int userId = runningUserIds[i];
            if (checkUserId != UserHandle.USER_ALL && checkUserId != userId) {
                continue;
            }
            if (DEBUG) {
                Slog.d(TAG, "Checking idle state for user " + userId);
            }
            List<PackageInfo> packages = mPackageManager.getInstalledPackagesAsUser(
                    PackageManager.MATCH_DISABLED_COMPONENTS,
                    userId);
            final int packageCount = packages.size();
            for (int p = 0; p < packageCount; p++) {
                final PackageInfo pi = packages.get(p);
                final String packageName = pi.packageName;
                final boolean isIdle = isAppIdleFiltered(packageName,
                        UserHandle.getAppId(pi.applicationInfo.uid),
                        userId, elapsedRealtime);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_INFORM_LISTENERS,
                        userId, isIdle ? 1 : 0, packageName));
                if (isIdle) {
                    synchronized (mAppIdleLock) {
                        mAppIdleHistory.setIdle(packageName, userId, elapsedRealtime);
                    }
                }
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "checkIdleStates took "
                    + (SystemClock.elapsedRealtime() - elapsedRealtime));
        }
        return true;
    }

    /** Check if it's been a while since last parole and let idle apps do some work */
    void checkParoleTimeout() {
        boolean setParoled = false;
        synchronized (mAppIdleLock) {
            final long now = System.currentTimeMillis();
            if (!mAppIdleTempParoled) {
                final long timeSinceLastParole = now - mLastAppIdleParoledTime;
                if (timeSinceLastParole > mAppIdleParoleIntervalMillis) {
                    if (DEBUG) Slog.d(TAG, "Crossed default parole interval");
                    setParoled = true;
                } else {
                    if (DEBUG) Slog.d(TAG, "Not long enough to go to parole");
                    postNextParoleTimeout(now);
                }
            }
        }
        if (setParoled) {
            setAppIdleParoled(true);
        }
    }

    private void notifyBatteryStats(String packageName, int userId, boolean idle) {
        try {
            final int uid = mPackageManager.getPackageUidAsUser(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
            if (idle) {
                mBatteryStats.noteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_INACTIVE,
                        packageName, uid);
            } else {
                mBatteryStats.noteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_ACTIVE,
                        packageName, uid);
            }
        } catch (PackageManager.NameNotFoundException | RemoteException e) {
        }
    }

    void onDeviceIdleModeChanged() {
        final boolean deviceIdle = mPowerManager.isDeviceIdleMode();
        if (DEBUG) Slog.i(TAG, "DeviceIdleMode changed to " + deviceIdle);
        boolean paroled = false;
        synchronized (mAppIdleLock) {
            final long timeSinceLastParole = System.currentTimeMillis() - mLastAppIdleParoledTime;
            if (!deviceIdle
                    && timeSinceLastParole >= mAppIdleParoleIntervalMillis) {
                if (DEBUG) {
                    Slog.i(TAG, "Bringing idle apps out of inactive state due to deviceIdleMode=false");
                }
                paroled = true;
            } else if (deviceIdle) {
                if (DEBUG) Slog.i(TAG, "Device idle, back to prison");
                paroled = false;
            } else {
                return;
            }
        }
        setAppIdleParoled(paroled);
    }

    void reportEvent(UsageEvents.Event event, long elapsedRealtime, int userId) {
        synchronized (mAppIdleLock) {
            // TODO: Ideally this should call isAppIdleFiltered() to avoid calling back
            // about apps that are on some kind of whitelist anyway.
            final boolean previouslyIdle = mAppIdleHistory.isIdle(
                    event.mPackage, userId, elapsedRealtime);
            // Inform listeners if necessary
            if ((event.mEventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || event.mEventType == UsageEvents.Event.MOVE_TO_BACKGROUND
                    || event.mEventType == UsageEvents.Event.SYSTEM_INTERACTION
                    || event.mEventType == UsageEvents.Event.USER_INTERACTION)) {
                mAppIdleHistory.reportUsage(event.mPackage, userId, elapsedRealtime);
                if (previouslyIdle) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_INFORM_LISTENERS, userId,
                                /* idle = */ 0, event.mPackage));
                    notifyBatteryStats(event.mPackage, userId, false);
                }
            }
        }

    }

    /**
     * Forces the app's beginIdleTime and lastUsedTime to reflect idle or active. If idle,
     * then it rolls back the beginIdleTime and lastUsedTime to a point in time that's behind
     * the threshold for idle.
     *
     * This method is always called from the handler thread, so not much synchronization is
     * required.
     */
    void forceIdleState(String packageName, int userId, boolean idle) {
        final int appId = getAppId(packageName);
        if (appId < 0) return;
        final long elapsedRealtime = SystemClock.elapsedRealtime();

        final boolean previouslyIdle = isAppIdleFiltered(packageName, appId,
                userId, elapsedRealtime);
        synchronized (mAppIdleLock) {
            mAppIdleHistory.setIdle(packageName, userId, idle, elapsedRealtime);
        }
        final boolean stillIdle = isAppIdleFiltered(packageName, appId,
                userId, elapsedRealtime);
        // Inform listeners if necessary
        if (previouslyIdle != stillIdle) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_INFORM_LISTENERS, userId,
                    /* idle = */ stillIdle ? 1 : 0, packageName));
            if (!stillIdle) {
                notifyBatteryStats(packageName, userId, idle);
            }
        }
    }

    public void onUserRemoved(int userId) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.onUserRemoved(userId);
        }
    }

    private boolean isAppIdleUnfiltered(String packageName, int userId, long elapsedRealtime) {
        synchronized (mAppIdleLock) {
            return mAppIdleHistory.isIdle(packageName, userId, elapsedRealtime);
        }
    }

    void addListener(UsageStatsManagerInternal.AppIdleStateChangeListener listener) {
        synchronized (mAppIdleLock) {
            if (!mPackageAccessListeners.contains(listener)) {
                mPackageAccessListeners.add(listener);
            }
        }
    }

    void removeListener(UsageStatsManagerInternal.AppIdleStateChangeListener listener) {
        synchronized (mAppIdleLock) {
            mPackageAccessListeners.remove(listener);
        }
    }

    int getAppId(String packageName) {
        try {
            ApplicationInfo ai = mPackageManager.getApplicationInfo(packageName,
                    PackageManager.MATCH_ANY_USER
                            | PackageManager.MATCH_DISABLED_COMPONENTS);
            return ai.uid;
        } catch (PackageManager.NameNotFoundException re) {
            return -1;
        }
    }

    boolean isAppIdleFilteredOrParoled(String packageName, int userId, long elapsedRealtime,
            boolean shouldObfuscateInstantApps) {
        if (isParoledOrCharging()) {
            return false;
        }
        if (shouldObfuscateInstantApps &&
                mPackageManagerInternal.isPackageEphemeral(userId, packageName)) {
            return false;
        }
        return isAppIdleFiltered(packageName, getAppId(packageName), userId, elapsedRealtime);
    }

    /**
     * Checks if an app has been idle for a while and filters out apps that are excluded.
     * It returns false if the current system state allows all apps to be considered active.
     * This happens if the device is plugged in or temporarily allowed to make exceptions.
     * Called by interface impls.
     */
    boolean isAppIdleFiltered(String packageName, int appId, int userId,
            long elapsedRealtime) {
        if (packageName == null) return false;
        // If not enabled at all, of course nobody is ever idle.
        if (!mAppIdleEnabled) {
            return false;
        }
        if (appId < Process.FIRST_APPLICATION_UID) {
            // System uids never go idle.
            return false;
        }
        if (packageName.equals("android")) {
            // Nor does the framework (which should be redundant with the above, but for MR1 we will
            // retain this for safety).
            return false;
        }
        if (mSystemServicesReady) {
            try {
                // We allow all whitelisted apps, including those that don't want to be whitelisted
                // for idle mode, because app idle (aka app standby) is really not as big an issue
                // for controlling who participates vs. doze mode.
                if (mDeviceIdleController.isPowerSaveWhitelistExceptIdleApp(packageName)) {
                    return false;
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }

            if (isActiveDeviceAdmin(packageName, userId)) {
                return false;
            }

            if (isActiveNetworkScorer(packageName)) {
                return false;
            }

            if (mAppWidgetManager != null
                    && mAppWidgetManager.isBoundWidgetPackage(packageName, userId)) {
                return false;
            }

            if (isDeviceProvisioningPackage(packageName)) {
                return false;
            }
        }

        if (!isAppIdleUnfiltered(packageName, userId, elapsedRealtime)) {
            return false;
        }

        // Check this last, as it is the most expensive check
        // TODO: Optimize this by fetching the carrier privileged apps ahead of time
        if (isCarrierApp(packageName)) {
            return false;
        }

        return true;
    }

    int[] getIdleUidsForUser(int userId) {
        if (!mAppIdleEnabled) {
            return new int[0];
        }

        final long elapsedRealtime = SystemClock.elapsedRealtime();

        List<ApplicationInfo> apps;
        try {
            ParceledListSlice<ApplicationInfo> slice = AppGlobals.getPackageManager()
                    .getInstalledApplications(/* flags= */ 0, userId);
            if (slice == null) {
                return new int[0];
            }
            apps = slice.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // State of each uid.  Key is the uid.  Value lower 16 bits is the number of apps
        // associated with that uid, upper 16 bits is the number of those apps that is idle.
        SparseIntArray uidStates = new SparseIntArray();

        // Now resolve all app state.  Iterating over all apps, keeping track of how many
        // we find for each uid and how many of those are idle.
        for (int i = apps.size() - 1; i >= 0; i--) {
            ApplicationInfo ai = apps.get(i);

            // Check whether this app is idle.
            boolean idle = isAppIdleFiltered(ai.packageName, UserHandle.getAppId(ai.uid),
                    userId, elapsedRealtime);

            int index = uidStates.indexOfKey(ai.uid);
            if (index < 0) {
                uidStates.put(ai.uid, 1 + (idle ? 1<<16 : 0));
            } else {
                int value = uidStates.valueAt(index);
                uidStates.setValueAt(index, value + 1 + (idle ? 1<<16 : 0));
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "getIdleUids took " + (SystemClock.elapsedRealtime() - elapsedRealtime));
        }
        int numIdle = 0;
        for (int i = uidStates.size() - 1; i >= 0; i--) {
            int value = uidStates.valueAt(i);
            if ((value&0x7fff) == (value>>16)) {
                numIdle++;
            }
        }

        int[] res = new int[numIdle];
        numIdle = 0;
        for (int i = uidStates.size() - 1; i >= 0; i--) {
            int value = uidStates.valueAt(i);
            if ((value&0x7fff) == (value>>16)) {
                res[numIdle] = uidStates.keyAt(i);
                numIdle++;
            }
        }

        return res;
    }

    void setAppIdleAsync(String packageName, boolean idle, int userId) {
        if (packageName == null) return;

        mHandler.obtainMessage(MSG_FORCE_IDLE_STATE, userId, idle ? 1 : 0, packageName)
                .sendToTarget();
    }

    private boolean isActiveDeviceAdmin(String packageName, int userId) {
        DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        if (dpm == null) return false;
        return dpm.packageHasActiveAdmins(packageName, userId);
    }

    /**
     * Returns {@code true} if the supplied package is the device provisioning app. Otherwise,
     * returns {@code false}.
     */
    private boolean isDeviceProvisioningPackage(String packageName) {
        String deviceProvisioningPackage = mContext.getResources().getString(
                com.android.internal.R.string.config_deviceProvisioningPackage);
        return deviceProvisioningPackage != null && deviceProvisioningPackage.equals(packageName);
    }

    private boolean isCarrierApp(String packageName) {
        synchronized (mAppIdleLock) {
            if (!mHaveCarrierPrivilegedApps) {
                fetchCarrierPrivilegedAppsLA();
            }
            if (mCarrierPrivilegedApps != null) {
                return mCarrierPrivilegedApps.contains(packageName);
            }
            return false;
        }
    }

    void clearCarrierPrivilegedApps() {
        if (DEBUG) {
            Slog.i(TAG, "Clearing carrier privileged apps list");
        }
        synchronized (mAppIdleLock) {
            mHaveCarrierPrivilegedApps = false;
            mCarrierPrivilegedApps = null; // Need to be refetched.
        }
    }

    @GuardedBy("mAppIdleLock")
    private void fetchCarrierPrivilegedAppsLA() {
        TelephonyManager telephonyManager =
                mContext.getSystemService(TelephonyManager.class);
        mCarrierPrivilegedApps = telephonyManager.getPackagesWithCarrierPrivileges();
        mHaveCarrierPrivilegedApps = true;
        if (DEBUG) {
            Slog.d(TAG, "apps with carrier privilege " + mCarrierPrivilegedApps);
        }
    }

    private boolean isActiveNetworkScorer(String packageName) {
        NetworkScoreManager nsm = (NetworkScoreManager) mContext.getSystemService(
                Context.NETWORK_SCORE_SERVICE);
        return packageName != null && packageName.equals(nsm.getActiveScorerPackage());
    }

    void informListeners(String packageName, int userId, boolean isIdle) {
        for (UsageStatsManagerInternal.AppIdleStateChangeListener listener : mPackageAccessListeners) {
            listener.onAppIdleStateChanged(packageName, userId, isIdle);
        }
    }

    void informParoleStateChanged() {
        final boolean paroled = isParoledOrCharging();
        for (UsageStatsManagerInternal.AppIdleStateChangeListener listener : mPackageAccessListeners) {
            listener.onParoleStateChanged(paroled);
        }
    }

    void flushToDisk(int userId) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.writeAppIdleTimes(userId);
        }
    }

    void flushDurationsToDisk() {
        // Persist elapsed and screen on time. If this fails for whatever reason, the apps will be
        // considered not-idle, which is the safest outcome in such an event.
        synchronized (mAppIdleLock) {
            mAppIdleHistory.writeAppIdleDurations();
        }
    }

    boolean isDisplayOn() {
        return mDisplayManager
                .getDisplay(Display.DEFAULT_DISPLAY).getState() == Display.STATE_ON;
    }

    void clearAppIdleForPackage(String packageName, int userId) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.clearUsage(packageName, userId);
        }
    }

    private class PackageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                clearCarrierPrivilegedApps();
            }
            if ((Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
                    Intent.ACTION_PACKAGE_ADDED.equals(action))
                    && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                clearAppIdleForPackage(intent.getData().getSchemeSpecificPart(),
                        getSendingUserId());
            }
        }
    }

    void initializeDefaultsForSystemApps(int userId) {
        Slog.d(TAG, "Initializing defaults for system apps on user " + userId);
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        List<PackageInfo> packages = mPackageManager.getInstalledPackagesAsUser(
                PackageManager.MATCH_DISABLED_COMPONENTS,
                userId);
        final int packageCount = packages.size();
        synchronized (mAppIdleLock) {
            for (int i = 0; i < packageCount; i++) {
                final PackageInfo pi = packages.get(i);
                String packageName = pi.packageName;
                if (pi.applicationInfo != null && pi.applicationInfo.isSystemApp()) {
                    mAppIdleHistory.reportUsage(packageName, userId, elapsedRealtime);
                }
            }
        }
    }

    void postReportContentProviderUsage(String name, String packageName, int userId) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = name;
        args.arg2 = packageName;
        args.arg3 = userId;
        mHandler.obtainMessage(MSG_REPORT_CONTENT_PROVIDER_USAGE, args)
                .sendToTarget();
    }

    void dumpHistory(IndentingPrintWriter idpw, int userId) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.dumpHistory(idpw, userId);
        }
    }

    void dumpUser(IndentingPrintWriter idpw, int userId) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.dump(idpw, userId);
        }
    }

    void dumpState(String[] args, PrintWriter pw) {
        synchronized (mAppIdleLock) {
            pw.println("Carrier privileged apps (have=" + mHaveCarrierPrivilegedApps
                    + "): " + mCarrierPrivilegedApps);
        }

        pw.println();
        pw.println("Settings:");

        pw.print("  mAppIdleDurationMillis=");
        TimeUtils.formatDuration(mAppIdleScreenThresholdMillis, pw);
        pw.println();

        pw.print("  mAppIdleWallclockThresholdMillis=");
        TimeUtils.formatDuration(mAppIdleWallclockThresholdMillis, pw);
        pw.println();

        pw.print("  mCheckIdleIntervalMillis=");
        TimeUtils.formatDuration(mCheckIdleIntervalMillis, pw);
        pw.println();

        pw.print("  mAppIdleParoleIntervalMillis=");
        TimeUtils.formatDuration(mAppIdleParoleIntervalMillis, pw);
        pw.println();

        pw.print("  mAppIdleParoleDurationMillis=");
        TimeUtils.formatDuration(mAppIdleParoleDurationMillis, pw);
        pw.println();

        pw.println();
        pw.print("mAppIdleEnabled="); pw.print(mAppIdleEnabled);
        pw.print(" mAppIdleTempParoled="); pw.print(mAppIdleTempParoled);
        pw.print(" mCharging="); pw.print(mCharging);
        pw.print(" mLastAppIdleParoledTime=");
        TimeUtils.formatDuration(mLastAppIdleParoledTime, pw);
        pw.println();
    }

    class AppStandbyHandler extends Handler {

        AppStandbyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_FORCE_IDLE_STATE:
                    forceIdleState((String) msg.obj, msg.arg1, msg.arg2 == 1);
                    break;

                case MSG_CHECK_IDLE_STATES:
                    if (checkIdleStates(msg.arg1)) {
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MSG_CHECK_IDLE_STATES, msg.arg1, 0),
                                mCheckIdleIntervalMillis);
                    }
                    break;

                case MSG_ONE_TIME_CHECK_IDLE_STATES:
                    mHandler.removeMessages(MSG_ONE_TIME_CHECK_IDLE_STATES);
                    checkIdleStates(UserHandle.USER_ALL);
                    break;

                case MSG_CHECK_PAROLE_TIMEOUT:
                    checkParoleTimeout();
                    break;

                case MSG_PAROLE_END_TIMEOUT:
                    if (DEBUG) Slog.d(TAG, "Ending parole");
                    setAppIdleParoled(false);
                    break;

                case MSG_REPORT_CONTENT_PROVIDER_USAGE:
                    SomeArgs args = (SomeArgs) msg.obj;
                    reportContentProviderUsage((String) args.arg1, // authority name
                            (String) args.arg2, // package name
                            (int) args.arg3); // userId
                    args.recycle();
                    break;

                case MSG_PAROLE_STATE_CHANGED:
                    if (DEBUG) Slog.d(TAG, "Parole state: " + mAppIdleTempParoled
                            + ", Charging state:" + mCharging);
                    informParoleStateChanged();
                    break;
                default:
                    super.handleMessage(msg);
                    break;

            }
        }
    };

    private class DeviceStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                setChargingState(intent.getIntExtra("plugged", 0) != 0);
            } else if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(action)) {
                onDeviceIdleModeChanged();
            }
        }
    }

    private final DisplayManager.DisplayListener mDisplayListener
            = new DisplayManager.DisplayListener() {

        @Override public void onDisplayAdded(int displayId) {
        }

        @Override public void onDisplayRemoved(int displayId) {
        }

        @Override public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                final boolean displayOn = isDisplayOn();
                synchronized (mAppIdleLock) {
                    mAppIdleHistory.updateDisplay(displayOn, SystemClock.elapsedRealtime());
                }
            }
        }
    };

    /**
     * Observe settings changes for {@link Settings.Global#APP_IDLE_CONSTANTS}.
     */
    private class SettingsObserver extends ContentObserver {
        /**
         * This flag has been used to disable app idle on older builds with bug b/26355386.
         */
        @Deprecated
        private static final String KEY_IDLE_DURATION_OLD = "idle_duration";

        private static final String KEY_IDLE_DURATION = "idle_duration2";
        private static final String KEY_WALLCLOCK_THRESHOLD = "wallclock_threshold";
        private static final String KEY_PAROLE_INTERVAL = "parole_interval";
        private static final String KEY_PAROLE_DURATION = "parole_duration";

        private final KeyValueListParser mParser = new KeyValueListParser(',');

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void registerObserver() {
            mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.APP_IDLE_CONSTANTS), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
            postOneTimeCheckIdleStates();
        }

        void updateSettings() {
            synchronized (mAppIdleLock) {
                // Look at global settings for this.
                // TODO: Maybe apply different thresholds for different users.
                try {
                    mParser.setString(Settings.Global.getString(mContext.getContentResolver(),
                            Settings.Global.APP_IDLE_CONSTANTS));
                } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Bad value for app idle settings: " + e.getMessage());
                    // fallthrough, mParser is empty and all defaults will be returned.
                }

                // Default: 12 hours of screen-on time sans dream-time
                mAppIdleScreenThresholdMillis = mParser.getLong(KEY_IDLE_DURATION,
                        COMPRESS_TIME ? ONE_MINUTE * 4 : 12 * 60 * ONE_MINUTE);

                mAppIdleWallclockThresholdMillis = mParser.getLong(KEY_WALLCLOCK_THRESHOLD,
                        COMPRESS_TIME ? ONE_MINUTE * 8 : 2L * 24 * 60 * ONE_MINUTE); // 2 days

                mCheckIdleIntervalMillis = Math.min(mAppIdleScreenThresholdMillis / 4,
                        COMPRESS_TIME ? ONE_MINUTE : 8 * 60 * ONE_MINUTE); // 8 hours

                // Default: 24 hours between paroles
                mAppIdleParoleIntervalMillis = mParser.getLong(KEY_PAROLE_INTERVAL,
                        COMPRESS_TIME ? ONE_MINUTE * 10 : 24 * 60 * ONE_MINUTE);

                mAppIdleParoleDurationMillis = mParser.getLong(KEY_PAROLE_DURATION,
                        COMPRESS_TIME ? ONE_MINUTE : 10 * ONE_MINUTE); // 10 minutes
                mAppIdleHistory.setThresholds(mAppIdleWallclockThresholdMillis,
                        mAppIdleScreenThresholdMillis);
            }
        }
    }

}

