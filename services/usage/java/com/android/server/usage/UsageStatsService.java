/**
 * Copyright (C) 2014 The Android Open Source Project
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

import android.Manifest;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.app.usage.ConfigurationStats;
import android.app.usage.IUsageStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.AppIdleStateChangeListener;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.NetworkScoreManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Environment;
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
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A service that collects, aggregates, and persists application usage data.
 * This data can be queried by apps that have been granted permission by AppOps.
 */
public class UsageStatsService extends SystemService implements
        UserUsageStatsService.StatsUpdatedListener {

    static final String TAG = "UsageStatsService";

    static final boolean DEBUG = false;
    static final boolean COMPRESS_TIME = false;

    private static final long TEN_SECONDS = 10 * 1000;
    private static final long ONE_MINUTE = 60 * 1000;
    private static final long TWENTY_MINUTES = 20 * 60 * 1000;
    private static final long FLUSH_INTERVAL = COMPRESS_TIME ? TEN_SECONDS : TWENTY_MINUTES;
    private static final long TIME_CHANGE_THRESHOLD_MILLIS = 2 * 1000; // Two seconds.

    long mAppIdleScreenThresholdMillis;
    long mCheckIdleIntervalMillis;
    long mAppIdleWallclockThresholdMillis;
    long mAppIdleParoleIntervalMillis;
    long mAppIdleParoleDurationMillis;

    // Handler message types.
    static final int MSG_REPORT_EVENT = 0;
    static final int MSG_FLUSH_TO_DISK = 1;
    static final int MSG_REMOVE_USER = 2;
    static final int MSG_INFORM_LISTENERS = 3;
    static final int MSG_FORCE_IDLE_STATE = 4;
    static final int MSG_CHECK_IDLE_STATES = 5;
    static final int MSG_CHECK_PAROLE_TIMEOUT = 6;
    static final int MSG_PAROLE_END_TIMEOUT = 7;
    static final int MSG_REPORT_CONTENT_PROVIDER_USAGE = 8;
    static final int MSG_PAROLE_STATE_CHANGED = 9;
    static final int MSG_ONE_TIME_CHECK_IDLE_STATES = 10;

    private final Object mLock = new Object();
    Handler mHandler;
    AppOpsManager mAppOps;
    UserManager mUserManager;
    PackageManager mPackageManager;
    AppWidgetManager mAppWidgetManager;
    IDeviceIdleController mDeviceIdleController;
    private DisplayManager mDisplayManager;
    private PowerManager mPowerManager;
    private IBatteryStats mBatteryStats;

    private final SparseArray<UserUsageStatsService> mUserState = new SparseArray<>();
    private File mUsageStatsDir;
    long mRealTimeSnapshot;
    long mSystemTimeSnapshot;

    boolean mAppIdleEnabled;
    boolean mAppIdleParoled;
    private boolean mScreenOn;
    private long mLastAppIdleParoledTime;

    private volatile boolean mPendingOneTimeCheckIdleStates;
    private boolean mSystemServicesReady = false;

    @GuardedBy("mLock")
    private AppIdleHistory mAppIdleHistory;

    private ArrayList<UsageStatsManagerInternal.AppIdleStateChangeListener>
            mPackageAccessListeners = new ArrayList<>();

    private boolean mHaveCarrierPrivilegedApps;
    private List<String> mCarrierPrivilegedApps;

    public UsageStatsService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mUserManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
        mPackageManager = getContext().getPackageManager();
        mHandler = new H(BackgroundThread.get().getLooper());

        File systemDataDir = new File(Environment.getDataDirectory(), "system");
        mUsageStatsDir = new File(systemDataDir, "usagestats");
        mUsageStatsDir.mkdirs();
        if (!mUsageStatsDir.exists()) {
            throw new IllegalStateException("Usage stats directory does not exist: "
                    + mUsageStatsDir.getAbsolutePath());
        }

        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_STARTED);
        getContext().registerReceiverAsUser(new UserActionsReceiver(), UserHandle.ALL, filter,
                null, mHandler);

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");

        getContext().registerReceiverAsUser(new PackageReceiver(), UserHandle.ALL, packageFilter,
                null, mHandler);

        mAppIdleEnabled = getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_enableAutoPowerModes);
        if (mAppIdleEnabled) {
            IntentFilter deviceStates = new IntentFilter(BatteryManager.ACTION_CHARGING);
            deviceStates.addAction(BatteryManager.ACTION_DISCHARGING);
            deviceStates.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            getContext().registerReceiver(new DeviceStateReceiver(), deviceStates);
        }

        synchronized (mLock) {
            cleanUpRemovedUsersLocked();
            mAppIdleHistory = new AppIdleHistory(SystemClock.elapsedRealtime());
        }

        mRealTimeSnapshot = SystemClock.elapsedRealtime();
        mSystemTimeSnapshot = System.currentTimeMillis();

        publishLocalService(UsageStatsManagerInternal.class, new LocalService());
        publishBinderService(Context.USAGE_STATS_SERVICE, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            // Observe changes to the threshold
            SettingsObserver settingsObserver = new SettingsObserver(mHandler);
            settingsObserver.registerObserver();
            settingsObserver.updateSettings();

            mAppWidgetManager = getContext().getSystemService(AppWidgetManager.class);
            mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                    ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
            mBatteryStats = IBatteryStats.Stub.asInterface(
                    ServiceManager.getService(BatteryStats.SERVICE_NAME));
            mDisplayManager = (DisplayManager) getContext().getSystemService(
                    Context.DISPLAY_SERVICE);
            mPowerManager = getContext().getSystemService(PowerManager.class);

            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
            synchronized (mLock) {
                mAppIdleHistory.updateDisplayLocked(isDisplayOn(), SystemClock.elapsedRealtime());
            }

            if (mPendingOneTimeCheckIdleStates) {
                postOneTimeCheckIdleStates();
            }

            mSystemServicesReady = true;
        } else if (phase == PHASE_BOOT_COMPLETED) {
            setAppIdleParoled(getContext().getSystemService(BatteryManager.class).isCharging());
        }
    }

    private boolean isDisplayOn() {
        return mDisplayManager
                .getDisplay(Display.DEFAULT_DISPLAY).getState() == Display.STATE_ON;
    }

    private class UserActionsReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            final String action = intent.getAction();
            if (Intent.ACTION_USER_REMOVED.equals(action)) {
                if (userId >= 0) {
                    mHandler.obtainMessage(MSG_REMOVE_USER, userId, 0).sendToTarget();
                }
            } else if (Intent.ACTION_USER_STARTED.equals(action)) {
                if (userId >=0) {
                    postCheckIdleStates(userId);
                }
            }
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

    private class DeviceStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BatteryManager.ACTION_CHARGING.equals(action)
                    || BatteryManager.ACTION_DISCHARGING.equals(action)) {
                setAppIdleParoled(BatteryManager.ACTION_CHARGING.equals(action));
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
                synchronized (UsageStatsService.this.mLock) {
                    mAppIdleHistory.updateDisplayLocked(isDisplayOn(),
                            SystemClock.elapsedRealtime());
                }
            }
        }
    };

    @Override
    public void onStatsUpdated() {
        mHandler.sendEmptyMessageDelayed(MSG_FLUSH_TO_DISK, FLUSH_INTERVAL);
    }

    @Override
    public void onStatsReloaded() {
        postOneTimeCheckIdleStates();
    }

    @Override
    public void onNewUpdate(int userId) {
        initializeDefaultsForSystemApps(userId);
    }

    private void initializeDefaultsForSystemApps(int userId) {
        Slog.d(TAG, "Initializing defaults for system apps on user " + userId);
        final long elapsedRealtime = SystemClock.elapsedRealtime();
        List<PackageInfo> packages = mPackageManager.getInstalledPackagesAsUser(
                PackageManager.MATCH_DISABLED_COMPONENTS,
                userId);
        final int packageCount = packages.size();
        for (int i = 0; i < packageCount; i++) {
            final PackageInfo pi = packages.get(i);
            String packageName = pi.packageName;
            if (pi.applicationInfo != null && pi.applicationInfo.isSystemApp()) {
                mAppIdleHistory.reportUsageLocked(packageName, userId, elapsedRealtime);
            }
        }
    }

    void clearAppIdleForPackage(String packageName, int userId) {
        synchronized (mLock) {
            mAppIdleHistory.clearUsageLocked(packageName, userId);
        }
    }

    private void cleanUpRemovedUsersLocked() {
        final List<UserInfo> users = mUserManager.getUsers(true);
        if (users == null || users.size() == 0) {
            throw new IllegalStateException("There can't be no users");
        }

        ArraySet<String> toDelete = new ArraySet<>();
        String[] fileNames = mUsageStatsDir.list();
        if (fileNames == null) {
            // No users to delete.
            return;
        }

        toDelete.addAll(Arrays.asList(fileNames));

        final int userCount = users.size();
        for (int i = 0; i < userCount; i++) {
            final UserInfo userInfo = users.get(i);
            toDelete.remove(Integer.toString(userInfo.id));
        }

        final int deleteCount = toDelete.size();
        for (int i = 0; i < deleteCount; i++) {
            deleteRecursively(new File(mUsageStatsDir, toDelete.valueAt(i)));
        }
    }

    /** Paroled here means temporary pardon from being inactive */
    void setAppIdleParoled(boolean paroled) {
        synchronized (mLock) {
            if (mAppIdleParoled != paroled) {
                mAppIdleParoled = paroled;
                if (DEBUG) Slog.d(TAG, "Changing paroled to " + mAppIdleParoled);
                if (paroled) {
                    postParoleEndTimeout();
                } else {
                    mLastAppIdleParoledTime = checkAndGetTimeLocked();
                    postNextParoleTimeout();
                }
                postParoleStateChanged();
            }
        }
    }

    private void postNextParoleTimeout() {
        if (DEBUG) Slog.d(TAG, "Posting MSG_CHECK_PAROLE_TIMEOUT");
        mHandler.removeMessages(MSG_CHECK_PAROLE_TIMEOUT);
        // Compute when the next parole needs to happen. We check more frequently than necessary
        // since the message handler delays are based on elapsedRealTime and not wallclock time.
        // The comparison is done in wallclock time.
        long timeLeft = (mLastAppIdleParoledTime + mAppIdleParoleIntervalMillis)
                - checkAndGetTimeLocked();
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
            runningUserIds = ActivityManagerNative.getDefault().getRunningUserIds();
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
                    synchronized (mLock) {
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
        synchronized (mLock) {
            if (!mAppIdleParoled) {
                final long timeSinceLastParole = checkAndGetTimeLocked() - mLastAppIdleParoledTime;
                if (timeSinceLastParole > mAppIdleParoleIntervalMillis) {
                    if (DEBUG) Slog.d(TAG, "Crossed default parole interval");
                    setAppIdleParoled(true);
                } else {
                    if (DEBUG) Slog.d(TAG, "Not long enough to go to parole");
                    postNextParoleTimeout();
                }
            }
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
        } catch (NameNotFoundException | RemoteException e) {
        }
    }

    void onDeviceIdleModeChanged() {
        final boolean deviceIdle = mPowerManager.isDeviceIdleMode();
        if (DEBUG) Slog.i(TAG, "DeviceIdleMode changed to " + deviceIdle);
        synchronized (mLock) {
            final long timeSinceLastParole = checkAndGetTimeLocked() - mLastAppIdleParoledTime;
            if (!deviceIdle
                    && timeSinceLastParole >= mAppIdleParoleIntervalMillis) {
                if (DEBUG) Slog.i(TAG, "Bringing idle apps out of inactive state due to deviceIdleMode=false");
                setAppIdleParoled(true);
            } else if (deviceIdle) {
                if (DEBUG) Slog.i(TAG, "Device idle, back to prison");
                setAppIdleParoled(false);
            }
        }
    }

    private static void deleteRecursively(File f) {
        File[] files = f.listFiles();
        if (files != null) {
            for (File subFile : files) {
                deleteRecursively(subFile);
            }
        }

        if (!f.delete()) {
            Slog.e(TAG, "Failed to delete " + f);
        }
    }

    private UserUsageStatsService getUserDataAndInitializeIfNeededLocked(int userId,
            long currentTimeMillis) {
        UserUsageStatsService service = mUserState.get(userId);
        if (service == null) {
            service = new UserUsageStatsService(getContext(), userId,
                    new File(mUsageStatsDir, Integer.toString(userId)), this);
            service.init(currentTimeMillis);
            mUserState.put(userId, service);
        }
        return service;
    }

    /**
     * This should be the only way to get the time from the system.
     */
    private long checkAndGetTimeLocked() {
        final long actualSystemTime = System.currentTimeMillis();
        final long actualRealtime = SystemClock.elapsedRealtime();
        final long expectedSystemTime = (actualRealtime - mRealTimeSnapshot) + mSystemTimeSnapshot;
        final long diffSystemTime = actualSystemTime - expectedSystemTime;
        if (Math.abs(diffSystemTime) > TIME_CHANGE_THRESHOLD_MILLIS) {
            // The time has changed.
            Slog.i(TAG, "Time changed in UsageStats by " + (diffSystemTime / 1000) + " seconds");
            final int userCount = mUserState.size();
            for (int i = 0; i < userCount; i++) {
                final UserUsageStatsService service = mUserState.valueAt(i);
                service.onTimeChanged(expectedSystemTime, actualSystemTime);
            }
            mRealTimeSnapshot = actualRealtime;
            mSystemTimeSnapshot = actualSystemTime;
        }
        return actualSystemTime;
    }

    /**
     * Assuming the event's timestamp is measured in milliseconds since boot,
     * convert it to a system wall time.
     */
    private void convertToSystemTimeLocked(UsageEvents.Event event) {
        event.mTimeStamp = Math.max(0, event.mTimeStamp - mRealTimeSnapshot) + mSystemTimeSnapshot;
    }

    /**
     * Called by the Binder stub
     */
    void shutdown() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_REPORT_EVENT);
            flushToDiskLocked();
        }
    }

    /**
     * Called by the Binder stub.
     */
    void reportEvent(UsageEvents.Event event, int userId) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            convertToSystemTimeLocked(event);

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            // TODO: Ideally this should call isAppIdleFiltered() to avoid calling back
            // about apps that are on some kind of whitelist anyway.
            final boolean previouslyIdle = mAppIdleHistory.isIdleLocked(
                    event.mPackage, userId, elapsedRealtime);
            service.reportEvent(event);
            // Inform listeners if necessary
            if ((event.mEventType == Event.MOVE_TO_FOREGROUND
                    || event.mEventType == Event.MOVE_TO_BACKGROUND
                    || event.mEventType == Event.SYSTEM_INTERACTION
                    || event.mEventType == Event.USER_INTERACTION)) {
                mAppIdleHistory.reportUsageLocked(event.mPackage, userId, elapsedRealtime);
                if (previouslyIdle) {
                    mHandler.sendMessage(mHandler.obtainMessage(MSG_INFORM_LISTENERS, userId,
                            /* idle = */ 0, event.mPackage));
                    notifyBatteryStats(event.mPackage, userId, false);
                }
            }
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
                    forceIdleState(packageName, userId, false);
                }
            } catch (NameNotFoundException e) {
                // Shouldn't happen
            }
        }
    }

    /**
     * Forces the app's beginIdleTime and lastUsedTime to reflect idle or active. If idle,
     * then it rolls back the beginIdleTime and lastUsedTime to a point in time that's behind
     * the threshold for idle.
     */
    void forceIdleState(String packageName, int userId, boolean idle) {
        final int appId = getAppId(packageName);
        if (appId < 0) return;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();

            final boolean previouslyIdle = isAppIdleFiltered(packageName, appId,
                    userId, elapsedRealtime);
            mAppIdleHistory.setIdleLocked(packageName, userId, idle, elapsedRealtime);
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
    }

    /**
     * Called by the Binder stub.
     */
    void flushToDisk() {
        synchronized (mLock) {
            flushToDiskLocked();
        }
    }

    /**
     * Called by the Binder stub.
     */
    void onUserRemoved(int userId) {
        synchronized (mLock) {
            Slog.i(TAG, "Removing user " + userId + " and all data.");
            mUserState.remove(userId);
            mAppIdleHistory.onUserRemoved(userId);
            cleanUpRemovedUsersLocked();
        }
    }

    /**
     * Called by the Binder stub.
     */
    List<UsageStats> queryUsageStats(int userId, int bucketType, long beginTime, long endTime) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryUsageStats(bucketType, beginTime, endTime);
        }
    }

    /**
     * Called by the Binder stub.
     */
    List<ConfigurationStats> queryConfigurationStats(int userId, int bucketType, long beginTime,
            long endTime) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryConfigurationStats(bucketType, beginTime, endTime);
        }
    }

    /**
     * Called by the Binder stub.
     */
    UsageEvents queryEvents(int userId, long beginTime, long endTime) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryEvents(beginTime, endTime);
        }
    }

    private boolean isAppIdleUnfiltered(String packageName, int userId, long elapsedRealtime) {
        synchronized (mLock) {
            return mAppIdleHistory.isIdleLocked(packageName, userId, elapsedRealtime);
        }
    }

    void addListener(AppIdleStateChangeListener listener) {
        synchronized (mLock) {
            if (!mPackageAccessListeners.contains(listener)) {
                mPackageAccessListeners.add(listener);
            }
        }
    }

    void removeListener(AppIdleStateChangeListener listener) {
        synchronized (mLock) {
            mPackageAccessListeners.remove(listener);
        }
    }

    int getAppId(String packageName) {
        try {
            ApplicationInfo ai = mPackageManager.getApplicationInfo(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS);
            return ai.uid;
        } catch (NameNotFoundException re) {
            return -1;
        }
    }

    boolean isAppIdleFilteredOrParoled(String packageName, int userId, long elapsedRealtime) {
        if (mAppIdleParoled) {
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
    private boolean isAppIdleFiltered(String packageName, int appId, int userId,
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

    void setAppIdle(String packageName, boolean idle, int userId) {
        if (packageName == null) return;

        mHandler.obtainMessage(MSG_FORCE_IDLE_STATE, userId, idle ? 1 : 0, packageName)
                .sendToTarget();
    }

    private boolean isActiveDeviceAdmin(String packageName, int userId) {
        DevicePolicyManager dpm = getContext().getSystemService(DevicePolicyManager.class);
        if (dpm == null) return false;
        return dpm.packageHasActiveAdmins(packageName, userId);
    }

    private boolean isCarrierApp(String packageName) {
        synchronized (mLock) {
            if (!mHaveCarrierPrivilegedApps) {
                fetchCarrierPrivilegedAppsLocked();
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
        synchronized (mLock) {
            mHaveCarrierPrivilegedApps = false;
            mCarrierPrivilegedApps = null; // Need to be refetched.
        }
    }

    private void fetchCarrierPrivilegedAppsLocked() {
        TelephonyManager telephonyManager =
                getContext().getSystemService(TelephonyManager.class);
        mCarrierPrivilegedApps = telephonyManager.getPackagesWithCarrierPrivileges();
        mHaveCarrierPrivilegedApps = true;
        if (DEBUG) {
            Slog.d(TAG, "apps with carrier privilege " + mCarrierPrivilegedApps);
        }
    }

    private boolean isActiveNetworkScorer(String packageName) {
        NetworkScoreManager nsm = (NetworkScoreManager) getContext().getSystemService(
                Context.NETWORK_SCORE_SERVICE);
        return packageName != null && packageName.equals(nsm.getActiveScorerPackage());
    }

    void informListeners(String packageName, int userId, boolean isIdle) {
        for (AppIdleStateChangeListener listener : mPackageAccessListeners) {
            listener.onAppIdleStateChanged(packageName, userId, isIdle);
        }
    }

    void informParoleStateChanged() {
        for (AppIdleStateChangeListener listener : mPackageAccessListeners) {
            listener.onParoleStateChanged(mAppIdleParoled);
        }
    }

    private static boolean validRange(long currentTime, long beginTime, long endTime) {
        return beginTime <= currentTime && beginTime < endTime;
    }

    private void flushToDiskLocked() {
        final int userCount = mUserState.size();
        for (int i = 0; i < userCount; i++) {
            UserUsageStatsService service = mUserState.valueAt(i);
            service.persistActiveStats();
            mAppIdleHistory.writeAppIdleTimesLocked(mUserState.keyAt(i));
        }
        // Persist elapsed time periodically, in case screen doesn't get toggled
        // until the next boot
        mAppIdleHistory.writeElapsedTimeLocked();
        mHandler.removeMessages(MSG_FLUSH_TO_DISK);
    }

    /**
     * Called by the Binder stub.
     */
    void dump(String[] args, PrintWriter pw) {
        synchronized (mLock) {
            IndentingPrintWriter idpw = new IndentingPrintWriter(pw, "  ");
            ArraySet<String> argSet = new ArraySet<>();
            argSet.addAll(Arrays.asList(args));

            final int userCount = mUserState.size();
            for (int i = 0; i < userCount; i++) {
                idpw.printPair("user", mUserState.keyAt(i));
                idpw.println();
                idpw.increaseIndent();
                if (argSet.contains("--checkin")) {
                    mUserState.valueAt(i).checkin(idpw);
                } else {
                    mUserState.valueAt(i).dump(idpw);
                    idpw.println();
                    if (args.length > 0) {
                        if ("history".equals(args[0])) {
                            mAppIdleHistory.dumpHistory(idpw, mUserState.keyAt(i));
                        } else if ("flush".equals(args[0])) {
                            UsageStatsService.this.flushToDiskLocked();
                            pw.println("Flushed stats to disk");
                        }
                    }
                }
                mAppIdleHistory.dump(idpw, mUserState.keyAt(i));
                idpw.decreaseIndent();
            }

            pw.println();
            pw.println("Carrier privileged apps (have=" + mHaveCarrierPrivilegedApps
                    + "): " + mCarrierPrivilegedApps);

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
            pw.print(" mAppIdleParoled="); pw.print(mAppIdleParoled);
            pw.print(" mScreenOn="); pw.println(mScreenOn);
            pw.print("mLastAppIdleParoledTime=");
            TimeUtils.formatDuration(mLastAppIdleParoledTime, pw);
            pw.println();
        }
    }

    class H extends Handler {
        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REPORT_EVENT:
                    reportEvent((UsageEvents.Event) msg.obj, msg.arg1);
                    break;

                case MSG_FLUSH_TO_DISK:
                    flushToDisk();
                    break;

                case MSG_REMOVE_USER:
                    onUserRemoved(msg.arg1);
                    break;

                case MSG_INFORM_LISTENERS:
                    informListeners((String) msg.obj, msg.arg1, msg.arg2 == 1);
                    break;

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
                    if (DEBUG) Slog.d(TAG, "Parole state changed: " + mAppIdleParoled);
                    informParoleStateChanged();
                    break;

                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

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
            getContext().getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.APP_IDLE_CONSTANTS), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
            postOneTimeCheckIdleStates();
        }

        void updateSettings() {
            synchronized (mLock) {
                // Look at global settings for this.
                // TODO: Maybe apply different thresholds for different users.
                try {
                    mParser.setString(Settings.Global.getString(getContext().getContentResolver(),
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

    private final class BinderService extends IUsageStatsManager.Stub {

        private boolean hasPermission(String callingPackage) {
            final int callingUid = Binder.getCallingUid();
            if (callingUid == Process.SYSTEM_UID) {
                return true;
            }
            final int mode = mAppOps.checkOp(AppOpsManager.OP_GET_USAGE_STATS,
                    callingUid, callingPackage);
            if (mode == AppOpsManager.MODE_DEFAULT) {
                // The default behavior here is to check if PackageManager has given the app
                // permission.
                return getContext().checkCallingPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        }

        @Override
        public ParceledListSlice<UsageStats> queryUsageStats(int bucketType, long beginTime,
                long endTime, String callingPackage) {
            if (!hasPermission(callingPackage)) {
                return null;
            }

            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                final List<UsageStats> results = UsageStatsService.this.queryUsageStats(
                        userId, bucketType, beginTime, endTime);
                if (results != null) {
                    return new ParceledListSlice<>(results);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return null;
        }

        @Override
        public ParceledListSlice<ConfigurationStats> queryConfigurationStats(int bucketType,
                long beginTime, long endTime, String callingPackage) throws RemoteException {
            if (!hasPermission(callingPackage)) {
                return null;
            }

            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                final List<ConfigurationStats> results =
                        UsageStatsService.this.queryConfigurationStats(userId, bucketType,
                                beginTime, endTime);
                if (results != null) {
                    return new ParceledListSlice<>(results);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return null;
        }

        @Override
        public UsageEvents queryEvents(long beginTime, long endTime, String callingPackage) {
            if (!hasPermission(callingPackage)) {
                return null;
            }

            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.queryEvents(userId, beginTime, endTime);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isAppInactive(String packageName, int userId) {
            try {
                userId = ActivityManagerNative.getDefault().handleIncomingUser(Binder.getCallingPid(),
                        Binder.getCallingUid(), userId, false, true, "isAppInactive", null);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.isAppIdleFilteredOrParoled(packageName, userId,
                        SystemClock.elapsedRealtime());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setAppInactive(String packageName, boolean idle, int userId) {
            final int callingUid = Binder.getCallingUid();
            try {
                userId = ActivityManagerNative.getDefault().handleIncomingUser(
                        Binder.getCallingPid(), callingUid, userId, false, true,
                        "setAppIdle", null);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
            getContext().enforceCallingPermission(Manifest.permission.CHANGE_APP_IDLE_STATE,
                    "No permission to change app idle state");
            final long token = Binder.clearCallingIdentity();
            try {
                final int appId = getAppId(packageName);
                if (appId < 0) return;
                UsageStatsService.this.setAppIdle(packageName, idle, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void whitelistAppTemporarily(String packageName, long duration, int userId)
                throws RemoteException {
            StringBuilder reason = new StringBuilder(32);
            reason.append("from:");
            UserHandle.formatUid(reason, Binder.getCallingUid());
            mDeviceIdleController.addPowerSaveTempWhitelistApp(packageName, duration, userId,
                    reason.toString());
        }

        @Override
        public void onCarrierPrivilegedAppsChanged() {
            if (DEBUG) {
                Slog.i(TAG, "Carrier privileged apps changed");
            }
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.BIND_CARRIER_SERVICES,
                    "onCarrierPrivilegedAppsChanged can only be called by privileged apps.");
            UsageStatsService.this.clearCarrierPrivilegedApps();
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump UsageStats from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " without permission " + android.Manifest.permission.DUMP);
                return;
            }
            UsageStatsService.this.dump(args, pw);
        }
    }

    /**
     * This local service implementation is primarily used by ActivityManagerService.
     * ActivityManagerService will call these methods holding the 'am' lock, which means we
     * shouldn't be doing any IO work or other long running tasks in these methods.
     */
    private final class LocalService extends UsageStatsManagerInternal {

        @Override
        public void reportEvent(ComponentName component, int userId, int eventType) {
            if (component == null) {
                Slog.w(TAG, "Event reported without a component name");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = component.getPackageName();
            event.mClass = component.getClassName();

            // This will later be converted to system time.
            event.mTimeStamp = SystemClock.elapsedRealtime();

            event.mEventType = eventType;
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
        }

        @Override
        public void reportEvent(String packageName, int userId, int eventType) {
            if (packageName == null) {
                Slog.w(TAG, "Event reported without a package name");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = packageName;

            // This will later be converted to system time.
            event.mTimeStamp = SystemClock.elapsedRealtime();

            event.mEventType = eventType;
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
        }

        @Override
        public void reportConfigurationChange(Configuration config, int userId) {
            if (config == null) {
                Slog.w(TAG, "Configuration event reported with a null config");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = "android";

            // This will later be converted to system time.
            event.mTimeStamp = SystemClock.elapsedRealtime();

            event.mEventType = UsageEvents.Event.CONFIGURATION_CHANGE;
            event.mConfiguration = new Configuration(config);
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
        }

        @Override
        public void reportContentProviderUsage(String name, String packageName, int userId) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = name;
            args.arg2 = packageName;
            args.arg3 = userId;
            mHandler.obtainMessage(MSG_REPORT_CONTENT_PROVIDER_USAGE, args)
                    .sendToTarget();
        }

        @Override
        public boolean isAppIdle(String packageName, int uidForAppId, int userId) {
            return UsageStatsService.this.isAppIdleFiltered(packageName, uidForAppId, userId,
                    SystemClock.elapsedRealtime());
        }

        @Override
        public int[] getIdleUidsForUser(int userId) {
            return UsageStatsService.this.getIdleUidsForUser(userId);
        }

        @Override
        public boolean isAppIdleParoleOn() {
            return mAppIdleParoled;
        }

        @Override
        public void prepareShutdown() {
            // This method *WILL* do IO work, but we must block until it is finished or else
            // we might not shutdown cleanly. This is ok to do with the 'am' lock held, because
            // we are shutting down.
            shutdown();
        }

        @Override
        public void addAppIdleStateChangeListener(AppIdleStateChangeListener listener) {
            UsageStatsService.this.addListener(listener);
            listener.onParoleStateChanged(isAppIdleParoleOn());
        }

        @Override
        public void removeAppIdleStateChangeListener(
                AppIdleStateChangeListener listener) {
            UsageStatsService.this.removeListener(listener);
        }

        @Override
        public byte[] getBackupPayload(int user, String key) {
            // Check to ensure that only user 0's data is b/r for now
            if (user == UserHandle.USER_SYSTEM) {
                final UserUsageStatsService userStats =
                        getUserDataAndInitializeIfNeededLocked(user, checkAndGetTimeLocked());
                return userStats.getBackupPayload(key);
            } else {
                return null;
            }
        }

        @Override
        public void applyRestoredPayload(int user, String key, byte[] payload) {
            if (user == UserHandle.USER_SYSTEM) {
                final UserUsageStatsService userStats =
                        getUserDataAndInitializeIfNeededLocked(user, checkAndGetTimeLocked());
                userStats.applyRestoredPayload(key, payload);
            }
        }
    }
}
