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

import static android.app.usage.UsageStatsManager.REASON_MAIN_DEFAULT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED;
import static android.app.usage.UsageStatsManager.REASON_MAIN_MASK;
import static android.app.usage.UsageStatsManager.REASON_MAIN_PREDICTED;
import static android.app.usage.UsageStatsManager.REASON_MAIN_TIMEOUT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_PREDICTED_RESTORED;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_ACTIVE_TIMEOUT;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_DOZE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_NON_DOZE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_EXEMPTED_SYNC_START;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_MOVE_TO_BACKGROUND;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_MOVE_TO_FOREGROUND;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_NOTIFICATION_SEEN;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SYNC_ADAPTER;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SYSTEM_INTERACTION;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SYSTEM_UPDATE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_USER_INTERACTION;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SLICE_PINNED;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SLICE_PINNED_PRIV;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_EXEMPTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_NEVER;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;
import static com.android.server.SystemService.PHASE_BOOT_COMPLETED;
import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.usage.AppStandbyInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager.StandbyBuckets;
import android.app.usage.UsageStatsManagerInternal.AppIdleStateChangeListener;
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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkScoreManager;
import android.os.BatteryManager;
import android.os.BatteryStats;
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
import android.provider.Settings.Global;
import android.telephony.TelephonyManager;
import android.util.ArraySet;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.usage.AppIdleHistory.AppUsageHistory;

import java.io.File;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Manages the standby state of an app, listening to various events.
 *
 * Unit test:
   atest ${ANDROID_BUILD_TOP}/frameworks/base/services/tests/servicestests/src/com/android/server/usage/AppStandbyControllerTests.java
 */
public class AppStandbyController {

    private static final String TAG = "AppStandbyController";
    static final boolean DEBUG = false;

    static final boolean COMPRESS_TIME = false;
    private static final long ONE_MINUTE = 60 * 1000;
    private static final long ONE_HOUR = ONE_MINUTE * 60;
    private static final long ONE_DAY = ONE_HOUR * 24;

    static final long[] SCREEN_TIME_THRESHOLDS = {
            0,
            0,
            COMPRESS_TIME ? 120 * 1000 : 1 * ONE_HOUR,
            COMPRESS_TIME ? 240 * 1000 : 2 * ONE_HOUR
    };

    static final long[] ELAPSED_TIME_THRESHOLDS = {
            0,
            COMPRESS_TIME ?  1 * ONE_MINUTE : 12 * ONE_HOUR,
            COMPRESS_TIME ?  4 * ONE_MINUTE : 24 * ONE_HOUR,
            COMPRESS_TIME ? 16 * ONE_MINUTE : 48 * ONE_HOUR
    };

    static final int[] THRESHOLD_BUCKETS = {
            STANDBY_BUCKET_ACTIVE,
            STANDBY_BUCKET_WORKING_SET,
            STANDBY_BUCKET_FREQUENT,
            STANDBY_BUCKET_RARE
    };

    /** Default expiration time for bucket prediction. After this, use thresholds to downgrade. */
    private static final long DEFAULT_PREDICTION_TIMEOUT = 12 * ONE_HOUR;

    /**
     * Indicates the maximum wait time for admin data to be available;
     */
    private static final long WAIT_FOR_ADMIN_DATA_TIMEOUT_MS = 10_000;

    // To name the lock for stack traces
    static class Lock {}

    /** Lock to protect the app's standby state. Required for calls into AppIdleHistory */
    private final Object mAppIdleLock = new Lock();

    /** Keeps the history and state for each app. */
    @GuardedBy("mAppIdleLock")
    private AppIdleHistory mAppIdleHistory;

    @GuardedBy("mPackageAccessListeners")
    private ArrayList<AppIdleStateChangeListener>
            mPackageAccessListeners = new ArrayList<>();

    /** Whether we've queried the list of carrier privileged apps. */
    @GuardedBy("mAppIdleLock")
    private boolean mHaveCarrierPrivilegedApps;

    /** List of carrier-privileged apps that should be excluded from standby */
    @GuardedBy("mAppIdleLock")
    private List<String> mCarrierPrivilegedApps;

    @GuardedBy("mActiveAdminApps")
    private final SparseArray<Set<String>> mActiveAdminApps = new SparseArray<>();

    private final CountDownLatch mAdminDataAvailableLatch = new CountDownLatch(1);

    // Messages for the handler
    static final int MSG_INFORM_LISTENERS = 3;
    static final int MSG_FORCE_IDLE_STATE = 4;
    static final int MSG_CHECK_IDLE_STATES = 5;
    static final int MSG_CHECK_PAROLE_TIMEOUT = 6;
    static final int MSG_PAROLE_END_TIMEOUT = 7;
    static final int MSG_REPORT_CONTENT_PROVIDER_USAGE = 8;
    static final int MSG_PAROLE_STATE_CHANGED = 9;
    static final int MSG_ONE_TIME_CHECK_IDLE_STATES = 10;
    /** Check the state of one app: arg1 = userId, arg2 = uid, obj = (String) packageName */
    static final int MSG_CHECK_PACKAGE_IDLE_STATE = 11;
    static final int MSG_REPORT_EXEMPTED_SYNC_SCHEDULED = 12;
    static final int MSG_REPORT_EXEMPTED_SYNC_START = 13;
    static final int MSG_UPDATE_STABLE_CHARGING= 14;

    long mCheckIdleIntervalMillis;
    long mAppIdleParoleIntervalMillis;
    long mAppIdleParoleWindowMillis;
    long mAppIdleParoleDurationMillis;
    long[] mAppStandbyScreenThresholds = SCREEN_TIME_THRESHOLDS;
    long[] mAppStandbyElapsedThresholds = ELAPSED_TIME_THRESHOLDS;
    /** Minimum time a strong usage event should keep the bucket elevated. */
    long mStrongUsageTimeoutMillis;
    /** Minimum time a notification seen event should keep the bucket elevated. */
    long mNotificationSeenTimeoutMillis;
    /** Minimum time a system update event should keep the buckets elevated. */
    long mSystemUpdateUsageTimeoutMillis;
    /** Maximum time to wait for a prediction before using simple timeouts to downgrade buckets. */
    long mPredictionTimeoutMillis;
    /** Maximum time a sync adapter associated with a CP should keep the buckets elevated. */
    long mSyncAdapterTimeoutMillis;
    /**
     * Maximum time an exempted sync should keep the buckets elevated, when sync is scheduled in
     * non-doze
     */
    long mExemptedSyncScheduledNonDozeTimeoutMillis;
    /**
     * Maximum time an exempted sync should keep the buckets elevated, when sync is scheduled in
     * doze
     */
    long mExemptedSyncScheduledDozeTimeoutMillis;
    /**
     * Maximum time an exempted sync should keep the buckets elevated, when sync is started.
     */
    long mExemptedSyncStartTimeoutMillis;
    /** Maximum time a system interaction should keep the buckets elevated. */
    long mSystemInteractionTimeoutMillis;
    /** The length of time phone must be charging before considered stable enough to run jobs  */
    long mStableChargingThresholdMillis;

    volatile boolean mAppIdleEnabled;
    boolean mAppIdleTempParoled;
    boolean mCharging;
    boolean mChargingStable;
    private long mLastAppIdleParoledTime;
    private boolean mSystemServicesReady = false;
    // There was a system update, defaults need to be initialized after services are ready
    private boolean mPendingInitializeDefaults;

    private final DeviceStateReceiver mDeviceStateReceiver;

    private volatile boolean mPendingOneTimeCheckIdleStates;

    private final AppStandbyHandler mHandler;
    private final Context mContext;

    // TODO: Provide a mechanism to set an external bucketing service

    private AppWidgetManager mAppWidgetManager;
    private ConnectivityManager mConnectivityManager;
    private PowerManager mPowerManager;
    private PackageManager mPackageManager;
    Injector mInjector;

    static final ArrayList<StandbyUpdateRecord> sStandbyUpdatePool = new ArrayList<>(4);

    public static class StandbyUpdateRecord {
        // Identity of the app whose standby state has changed
        String packageName;
        int userId;

        // What the standby bucket the app is now in
        int bucket;

        // Whether the bucket change is because the user has started interacting with the app
        boolean isUserInteraction;

        // Reason for bucket change
        int reason;

        StandbyUpdateRecord(String pkgName, int userId, int bucket, int reason,
                boolean isInteraction) {
            this.packageName = pkgName;
            this.userId = userId;
            this.bucket = bucket;
            this.reason = reason;
            this.isUserInteraction = isInteraction;
        }

        public static StandbyUpdateRecord obtain(String pkgName, int userId,
                int bucket, int reason, boolean isInteraction) {
            synchronized (sStandbyUpdatePool) {
                final int size = sStandbyUpdatePool.size();
                if (size < 1) {
                    return new StandbyUpdateRecord(pkgName, userId, bucket, reason, isInteraction);
                }
                StandbyUpdateRecord r = sStandbyUpdatePool.remove(size - 1);
                r.packageName = pkgName;
                r.userId = userId;
                r.bucket = bucket;
                r.reason = reason;
                r.isUserInteraction = isInteraction;
                return r;
            }
        }

        public void recycle() {
            synchronized (sStandbyUpdatePool) {
                sStandbyUpdatePool.add(this);
            }
        }
    }

    AppStandbyController(Context context, Looper looper) {
        this(new Injector(context, looper));
    }

    AppStandbyController(Injector injector) {
        mInjector = injector;
        mContext = mInjector.getContext();
        mHandler = new AppStandbyHandler(mInjector.getLooper());
        mPackageManager = mContext.getPackageManager();
        mDeviceStateReceiver = new DeviceStateReceiver();

        IntentFilter deviceStates = new IntentFilter(BatteryManager.ACTION_CHARGING);
        deviceStates.addAction(BatteryManager.ACTION_DISCHARGING);
        deviceStates.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        mContext.registerReceiver(mDeviceStateReceiver, deviceStates);

        synchronized (mAppIdleLock) {
            mAppIdleHistory = new AppIdleHistory(mInjector.getDataSystemDirectory(),
                    mInjector.elapsedRealtime());
        }

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");

        mContext.registerReceiverAsUser(new PackageReceiver(), UserHandle.ALL, packageFilter,
                null, mHandler);
    }

    void setAppIdleEnabled(boolean enabled) {
        mAppIdleEnabled = enabled;
    }

    public void onBootPhase(int phase) {
        mInjector.onBootPhase(phase);
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            Slog.d(TAG, "Setting app idle enabled state");
            setAppIdleEnabled(mInjector.isAppIdleEnabled());
            // Observe changes to the threshold
            SettingsObserver settingsObserver = new SettingsObserver(mHandler);
            settingsObserver.registerObserver();
            settingsObserver.updateSettings();

            mAppWidgetManager = mContext.getSystemService(AppWidgetManager.class);
            mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
            mPowerManager = mContext.getSystemService(PowerManager.class);

            mInjector.registerDisplayListener(mDisplayListener, mHandler);
            synchronized (mAppIdleLock) {
                mAppIdleHistory.updateDisplay(isDisplayOn(), mInjector.elapsedRealtime());
            }

            mSystemServicesReady = true;

            if (mPendingInitializeDefaults) {
                initializeDefaultsForSystemApps(UserHandle.USER_SYSTEM);
            }

            if (mPendingOneTimeCheckIdleStates) {
                postOneTimeCheckIdleStates();
            }
        } else if (phase == PHASE_BOOT_COMPLETED) {
            setChargingState(mInjector.isCharging());
        }
    }

    void reportContentProviderUsage(String authority, String providerPkgName, int userId) {
        if (!mAppIdleEnabled) return;

        // Get sync adapters for the authority
        String[] packages = ContentResolver.getSyncAdapterPackagesForAuthorityAsUser(
                authority, userId);
        final long elapsedRealtime = mInjector.elapsedRealtime();
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
                    synchronized (mAppIdleLock) {
                        AppUsageHistory appUsage = mAppIdleHistory.reportUsage(packageName, userId,
                                STANDBY_BUCKET_ACTIVE, REASON_SUB_USAGE_SYNC_ADAPTER,
                                0,
                                elapsedRealtime + mSyncAdapterTimeoutMillis);
                        maybeInformListeners(packageName, userId, elapsedRealtime,
                                appUsage.currentBucket, appUsage.bucketingReason, false);
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Shouldn't happen
            }
        }
    }

    void reportExemptedSyncScheduled(String packageName, int userId) {
        if (!mAppIdleEnabled) return;

        final int bucketToPromote;
        final int usageReason;
        final long durationMillis;

        if (!mInjector.isDeviceIdleMode()) {
            // Not dozing.
            bucketToPromote = STANDBY_BUCKET_ACTIVE;
            usageReason = REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_NON_DOZE;
            durationMillis = mExemptedSyncScheduledNonDozeTimeoutMillis;
        } else {
            // Dozing.
            bucketToPromote = STANDBY_BUCKET_WORKING_SET;
            usageReason = REASON_SUB_USAGE_EXEMPTED_SYNC_SCHEDULED_DOZE;
            durationMillis = mExemptedSyncScheduledDozeTimeoutMillis;
        }

        final long elapsedRealtime = mInjector.elapsedRealtime();

        synchronized (mAppIdleLock) {
            AppUsageHistory appUsage = mAppIdleHistory.reportUsage(packageName, userId,
                    bucketToPromote, usageReason,
                    0,
                    elapsedRealtime + durationMillis);
            maybeInformListeners(packageName, userId, elapsedRealtime,
                    appUsage.currentBucket, appUsage.bucketingReason, false);
        }
    }

    void reportExemptedSyncStart(String packageName, int userId) {
        if (!mAppIdleEnabled) return;

        final long elapsedRealtime = mInjector.elapsedRealtime();

        synchronized (mAppIdleLock) {
            AppUsageHistory appUsage = mAppIdleHistory.reportUsage(packageName, userId,
                    STANDBY_BUCKET_ACTIVE, REASON_SUB_USAGE_EXEMPTED_SYNC_START,
                    0,
                    elapsedRealtime + mExemptedSyncStartTimeoutMillis);
            maybeInformListeners(packageName, userId, elapsedRealtime,
                    appUsage.currentBucket, appUsage.bucketingReason, false);
        }
    }

    void setChargingState(boolean charging) {
        synchronized (mAppIdleLock) {
            if (mCharging != charging) {
                mCharging = charging;
                if (DEBUG) Slog.d(TAG, "Setting mCharging to " + charging);
                if (charging) {
                    if (DEBUG) {
                        Slog.d(TAG, "Scheduling MSG_UPDATE_STABLE_CHARGING  delay = "
                                + mStableChargingThresholdMillis);
                    }
                    mHandler.sendEmptyMessageDelayed(MSG_UPDATE_STABLE_CHARGING,
                            mStableChargingThresholdMillis);
                } else {
                    mHandler.removeMessages(MSG_UPDATE_STABLE_CHARGING);
                    updateChargingStableState();
                }
            }
        }
    }

    void updateChargingStableState() {
        synchronized (mAppIdleLock) {
            if (mChargingStable != mCharging) {
                if (DEBUG) Slog.d(TAG, "Setting mChargingStable to " + mCharging);
                mChargingStable = mCharging;
                postParoleStateChanged();
            }
        }
    }

    /** Paroled here means temporary pardon from being inactive */
    void setAppIdleParoled(boolean paroled) {
        synchronized (mAppIdleLock) {
            final long now = mInjector.currentTimeMillis();
            if (mAppIdleTempParoled != paroled) {
                mAppIdleTempParoled = paroled;
                if (DEBUG) Slog.d(TAG, "Changing paroled to " + mAppIdleTempParoled);
                if (paroled) {
                    postParoleEndTimeout();
                } else {
                    mLastAppIdleParoledTime = now;
                    postNextParoleTimeout(now, false);
                }
                postParoleStateChanged();
            }
        }
    }

    boolean isParoledOrCharging() {
        if (!mAppIdleEnabled) return true;
        synchronized (mAppIdleLock) {
            // Only consider stable charging when determining charge state.
            return mAppIdleTempParoled || mChargingStable;
        }
    }

    private void postNextParoleTimeout(long now, boolean forced) {
        if (DEBUG) Slog.d(TAG, "Posting MSG_CHECK_PAROLE_TIMEOUT");
        mHandler.removeMessages(MSG_CHECK_PAROLE_TIMEOUT);
        // Compute when the next parole needs to happen. We check more frequently than necessary
        // since the message handler delays are based on elapsedRealTime and not wallclock time.
        // The comparison is done in wallclock time.
        long timeLeft = (mLastAppIdleParoledTime + mAppIdleParoleIntervalMillis) - now;
        if (forced) {
            // Set next timeout for the end of the parole window
            // If parole is not set by the end of the window it will be forced
            timeLeft += mAppIdleParoleWindowMillis;
        }
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
        if (mInjector.getBootPhase() < PHASE_SYSTEM_SERVICES_READY) {
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
            runningUserIds = mInjector.getRunningUserIds();
            if (checkUserId != UserHandle.USER_ALL
                    && !ArrayUtils.contains(runningUserIds, checkUserId)) {
                return false;
            }
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }

        final long elapsedRealtime = mInjector.elapsedRealtime();
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
                checkAndUpdateStandbyState(packageName, userId, pi.applicationInfo.uid,
                        elapsedRealtime);
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "checkIdleStates took "
                    + (mInjector.elapsedRealtime() - elapsedRealtime));
        }
        return true;
    }

    /** Check if we need to update the standby state of a specific app. */
    private void checkAndUpdateStandbyState(String packageName, @UserIdInt int userId,
            int uid, long elapsedRealtime) {
        if (uid <= 0) {
            try {
                uid = mPackageManager.getPackageUidAsUser(packageName, userId);
            } catch (PackageManager.NameNotFoundException e) {
                // Not a valid package for this user, nothing to do
                // TODO: Remove any history of removed packages
                return;
            }
        }
        final boolean isSpecial = isAppSpecial(packageName,
                UserHandle.getAppId(uid),
                userId);
        if (DEBUG) {
            Slog.d(TAG, "   Checking idle state for " + packageName + " special=" +
                    isSpecial);
        }
        if (isSpecial) {
            synchronized (mAppIdleLock) {
                mAppIdleHistory.setAppStandbyBucket(packageName, userId, elapsedRealtime,
                        STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT);
            }
            maybeInformListeners(packageName, userId, elapsedRealtime,
                    STANDBY_BUCKET_EXEMPTED, REASON_MAIN_DEFAULT, false);
        } else {
            synchronized (mAppIdleLock) {
                final AppIdleHistory.AppUsageHistory app =
                        mAppIdleHistory.getAppUsageHistory(packageName,
                        userId, elapsedRealtime);
                int reason = app.bucketingReason;
                final int oldMainReason = reason & REASON_MAIN_MASK;

                // If the bucket was forced by the user/developer, leave it alone.
                // A usage event will be the only way to bring it out of this forced state
                if (oldMainReason == REASON_MAIN_FORCED) {
                    return;
                }
                final int oldBucket = app.currentBucket;
                int newBucket = Math.max(oldBucket, STANDBY_BUCKET_ACTIVE); // Undo EXEMPTED
                boolean predictionLate = predictionTimedOut(app, elapsedRealtime);
                // Compute age-based bucket
                if (oldMainReason == REASON_MAIN_DEFAULT
                        || oldMainReason == REASON_MAIN_USAGE
                        || oldMainReason == REASON_MAIN_TIMEOUT
                        || predictionLate) {

                    if (!predictionLate && app.lastPredictedBucket >= STANDBY_BUCKET_ACTIVE
                            && app.lastPredictedBucket <= STANDBY_BUCKET_RARE) {
                        newBucket = app.lastPredictedBucket;
                        reason = REASON_MAIN_PREDICTED | REASON_SUB_PREDICTED_RESTORED;
                        if (DEBUG) {
                            Slog.d(TAG, "Restored predicted newBucket = " + newBucket);
                        }
                    } else {
                        newBucket = getBucketForLocked(packageName, userId,
                                elapsedRealtime);
                        if (DEBUG) {
                            Slog.d(TAG, "Evaluated AOSP newBucket = " + newBucket);
                        }
                        reason = REASON_MAIN_TIMEOUT;
                    }
                }

                // Check if the app is within one of the timeouts for forced bucket elevation
                final long elapsedTimeAdjusted = mAppIdleHistory.getElapsedTime(elapsedRealtime);
                if (newBucket >= STANDBY_BUCKET_ACTIVE
                        && app.bucketActiveTimeoutTime > elapsedTimeAdjusted) {
                    newBucket = STANDBY_BUCKET_ACTIVE;
                    reason = app.bucketingReason;
                    if (DEBUG) {
                        Slog.d(TAG, "    Keeping at ACTIVE due to min timeout");
                    }
                } else if (newBucket >= STANDBY_BUCKET_WORKING_SET
                        && app.bucketWorkingSetTimeoutTime > elapsedTimeAdjusted) {
                    newBucket = STANDBY_BUCKET_WORKING_SET;
                    // If it was already there, keep the reason, else assume timeout to WS
                    reason = (newBucket == oldBucket)
                            ? app.bucketingReason
                            : REASON_MAIN_USAGE | REASON_SUB_USAGE_ACTIVE_TIMEOUT;
                    if (DEBUG) {
                        Slog.d(TAG, "    Keeping at WORKING_SET due to min timeout");
                    }
                }
                if (DEBUG) {
                    Slog.d(TAG, "     Old bucket=" + oldBucket
                            + ", newBucket=" + newBucket);
                }
                if (oldBucket < newBucket || predictionLate) {
                    mAppIdleHistory.setAppStandbyBucket(packageName, userId,
                            elapsedRealtime, newBucket, reason);
                    maybeInformListeners(packageName, userId, elapsedRealtime,
                            newBucket, reason, false);
                }
            }
        }
    }

    /** Returns true if there hasn't been a prediction for the app in a while. */
    private boolean predictionTimedOut(AppIdleHistory.AppUsageHistory app, long elapsedRealtime) {
        return app.lastPredictedTime > 0
                && mAppIdleHistory.getElapsedTime(elapsedRealtime)
                    - app.lastPredictedTime > mPredictionTimeoutMillis;
    }

    /** Inform listeners if the bucket has changed since it was last reported to listeners */
    private void maybeInformListeners(String packageName, int userId,
            long elapsedRealtime, int bucket, int reason, boolean userStartedInteracting) {
        synchronized (mAppIdleLock) {
            if (mAppIdleHistory.shouldInformListeners(packageName, userId,
                    elapsedRealtime, bucket)) {
                final StandbyUpdateRecord r = StandbyUpdateRecord.obtain(packageName, userId,
                        bucket, reason, userStartedInteracting);
                if (DEBUG) Slog.d(TAG, "Standby bucket for " + packageName + "=" + bucket);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_INFORM_LISTENERS, r));
            }
        }
    }

    /**
     * Evaluates next bucket based on time since last used and the bucketing thresholds.
     * @param packageName the app
     * @param userId the user
     * @param elapsedRealtime as the name suggests, current elapsed time
     * @return the bucket for the app, based on time since last used
     */
    @GuardedBy("mAppIdleLock")
    @StandbyBuckets int getBucketForLocked(String packageName, int userId,
            long elapsedRealtime) {
        int bucketIndex = mAppIdleHistory.getThresholdIndex(packageName, userId,
                elapsedRealtime, mAppStandbyScreenThresholds, mAppStandbyElapsedThresholds);
        return THRESHOLD_BUCKETS[bucketIndex];
    }

    /**
     * Check if it's been a while since last parole and let idle apps do some work.
     * If network is not available, delay parole until it is available up until the end of the
     * parole window. Force the parole to be set if end of the parole window is reached.
     */
    void checkParoleTimeout() {
        boolean setParoled = false;
        boolean waitForNetwork = false;
        NetworkInfo activeNetwork = mConnectivityManager.getActiveNetworkInfo();
        boolean networkActive = activeNetwork != null &&
                activeNetwork.isConnected();

        synchronized (mAppIdleLock) {
            final long now = mInjector.currentTimeMillis();
            if (!mAppIdleTempParoled) {
                final long timeSinceLastParole = now - mLastAppIdleParoledTime;
                if (timeSinceLastParole > mAppIdleParoleIntervalMillis) {
                    if (DEBUG) Slog.d(TAG, "Crossed default parole interval");
                    if (networkActive) {
                        // If network is active set parole
                        setParoled = true;
                    } else {
                        if (timeSinceLastParole
                                > mAppIdleParoleIntervalMillis + mAppIdleParoleWindowMillis) {
                            if (DEBUG) Slog.d(TAG, "Crossed end of parole window, force parole");
                            setParoled = true;
                        } else {
                            if (DEBUG) Slog.d(TAG, "Network unavailable, delaying parole");
                            waitForNetwork = true;
                            postNextParoleTimeout(now, true);
                        }
                    }
                } else {
                    if (DEBUG) Slog.d(TAG, "Not long enough to go to parole");
                    postNextParoleTimeout(now, false);
                }
            }
        }
        if (waitForNetwork) {
            mConnectivityManager.registerNetworkCallback(mNetworkRequest, mNetworkCallback);
        }
        if (setParoled) {
            // Set parole if network is available
            setAppIdleParoled(true);
        }
    }

    private void notifyBatteryStats(String packageName, int userId, boolean idle) {
        try {
            final int uid = mPackageManager.getPackageUidAsUser(packageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
            if (idle) {
                mInjector.noteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_INACTIVE,
                        packageName, uid);
            } else {
                mInjector.noteEvent(BatteryStats.HistoryItem.EVENT_PACKAGE_ACTIVE,
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
            final long timeSinceLastParole =
                    mInjector.currentTimeMillis() - mLastAppIdleParoledTime;
            if (!deviceIdle
                    && timeSinceLastParole >= mAppIdleParoleIntervalMillis) {
                if (DEBUG) {
                    Slog.i(TAG,
                            "Bringing idle apps out of inactive state due to deviceIdleMode=false");
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
        if (!mAppIdleEnabled) return;
        synchronized (mAppIdleLock) {
            // TODO: Ideally this should call isAppIdleFiltered() to avoid calling back
            // about apps that are on some kind of whitelist anyway.
            final boolean previouslyIdle = mAppIdleHistory.isIdle(
                    event.mPackage, userId, elapsedRealtime);
            // Inform listeners if necessary
            if ((event.mEventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || event.mEventType == UsageEvents.Event.MOVE_TO_BACKGROUND
                    || event.mEventType == UsageEvents.Event.SYSTEM_INTERACTION
                    || event.mEventType == UsageEvents.Event.USER_INTERACTION
                    || event.mEventType == UsageEvents.Event.NOTIFICATION_SEEN
                    || event.mEventType == UsageEvents.Event.SLICE_PINNED
                    || event.mEventType == UsageEvents.Event.SLICE_PINNED_PRIV)) {

                final AppUsageHistory appHistory = mAppIdleHistory.getAppUsageHistory(
                        event.mPackage, userId, elapsedRealtime);
                final int prevBucket = appHistory.currentBucket;
                final int prevBucketReason = appHistory.bucketingReason;
                final long nextCheckTime;
                final int subReason = usageEventToSubReason(event.mEventType);
                final int reason = REASON_MAIN_USAGE | subReason;
                if (event.mEventType == UsageEvents.Event.NOTIFICATION_SEEN
                        || event.mEventType == UsageEvents.Event.SLICE_PINNED) {
                    // Mild usage elevates to WORKING_SET but doesn't change usage time.
                    mAppIdleHistory.reportUsage(appHistory, event.mPackage,
                            STANDBY_BUCKET_WORKING_SET, subReason,
                            0, elapsedRealtime + mNotificationSeenTimeoutMillis);
                    nextCheckTime = mNotificationSeenTimeoutMillis;
                } else if (event.mEventType == UsageEvents.Event.SYSTEM_INTERACTION) {
                    mAppIdleHistory.reportUsage(appHistory, event.mPackage,
                            STANDBY_BUCKET_ACTIVE, subReason,
                            0, elapsedRealtime + mSystemInteractionTimeoutMillis);
                    nextCheckTime = mSystemInteractionTimeoutMillis;
                } else {
                    mAppIdleHistory.reportUsage(appHistory, event.mPackage,
                            STANDBY_BUCKET_ACTIVE, subReason,
                            elapsedRealtime, elapsedRealtime + mStrongUsageTimeoutMillis);
                    nextCheckTime = mStrongUsageTimeoutMillis;
                }
                mHandler.sendMessageDelayed(mHandler.obtainMessage
                        (MSG_CHECK_PACKAGE_IDLE_STATE, userId, -1, event.mPackage),
                        nextCheckTime);
                final boolean userStartedInteracting =
                        appHistory.currentBucket == STANDBY_BUCKET_ACTIVE &&
                        prevBucket != appHistory.currentBucket &&
                        (prevBucketReason & REASON_MAIN_MASK) != REASON_MAIN_USAGE;
                maybeInformListeners(event.mPackage, userId, elapsedRealtime,
                        appHistory.currentBucket, reason, userStartedInteracting);

                if (previouslyIdle) {
                    notifyBatteryStats(event.mPackage, userId, false);
                }
            }
        }
    }

    private int usageEventToSubReason(int eventType) {
        switch (eventType) {
            case UsageEvents.Event.MOVE_TO_FOREGROUND: return REASON_SUB_USAGE_MOVE_TO_FOREGROUND;
            case UsageEvents.Event.MOVE_TO_BACKGROUND: return REASON_SUB_USAGE_MOVE_TO_BACKGROUND;
            case UsageEvents.Event.SYSTEM_INTERACTION: return REASON_SUB_USAGE_SYSTEM_INTERACTION;
            case UsageEvents.Event.USER_INTERACTION: return REASON_SUB_USAGE_USER_INTERACTION;
            case UsageEvents.Event.NOTIFICATION_SEEN: return REASON_SUB_USAGE_NOTIFICATION_SEEN;
            case UsageEvents.Event.SLICE_PINNED: return REASON_SUB_USAGE_SLICE_PINNED;
            case UsageEvents.Event.SLICE_PINNED_PRIV: return REASON_SUB_USAGE_SLICE_PINNED_PRIV;
            default: return 0;
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
        if (!mAppIdleEnabled) return;

        final int appId = getAppId(packageName);
        if (appId < 0) return;
        final long elapsedRealtime = mInjector.elapsedRealtime();

        final boolean previouslyIdle = isAppIdleFiltered(packageName, appId,
                userId, elapsedRealtime);
        final int standbyBucket;
        synchronized (mAppIdleLock) {
            standbyBucket = mAppIdleHistory.setIdle(packageName, userId, idle, elapsedRealtime);
        }
        final boolean stillIdle = isAppIdleFiltered(packageName, appId,
                userId, elapsedRealtime);
        // Inform listeners if necessary
        if (previouslyIdle != stillIdle) {
            maybeInformListeners(packageName, userId, elapsedRealtime, standbyBucket,
                    REASON_MAIN_FORCED, false);
            if (!stillIdle) {
                notifyBatteryStats(packageName, userId, idle);
            }
        }
    }

    public void setLastJobRunTime(String packageName, int userId, long elapsedRealtime) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.setLastJobRunTime(packageName, userId, elapsedRealtime);
        }
    }

    public long getTimeSinceLastJobRun(String packageName, int userId) {
        final long elapsedRealtime = mInjector.elapsedRealtime();
        synchronized (mAppIdleLock) {
            return mAppIdleHistory.getTimeSinceLastJobRun(packageName, userId, elapsedRealtime);
        }
    }

    public void onUserRemoved(int userId) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.onUserRemoved(userId);
            synchronized (mActiveAdminApps) {
                mActiveAdminApps.remove(userId);
            }
        }
    }

    private boolean isAppIdleUnfiltered(String packageName, int userId, long elapsedRealtime) {
        synchronized (mAppIdleLock) {
            return mAppIdleHistory.isIdle(packageName, userId, elapsedRealtime);
        }
    }

    void addListener(AppIdleStateChangeListener listener) {
        synchronized (mPackageAccessListeners) {
            if (!mPackageAccessListeners.contains(listener)) {
                mPackageAccessListeners.add(listener);
            }
        }
    }

    void removeListener(AppIdleStateChangeListener listener) {
        synchronized (mPackageAccessListeners) {
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
                mInjector.isPackageEphemeral(userId, packageName)) {
            return false;
        }
        return isAppIdleFiltered(packageName, getAppId(packageName), userId, elapsedRealtime);
    }

    /** Returns true if this app should be whitelisted for some reason, to never go into standby */
    boolean isAppSpecial(String packageName, int appId, int userId) {
        if (packageName == null) return false;
        // If not enabled at all, of course nobody is ever idle.
        if (!mAppIdleEnabled) {
            return true;
        }
        if (appId < Process.FIRST_APPLICATION_UID) {
            // System uids never go idle.
            return true;
        }
        if (packageName.equals("android")) {
            // Nor does the framework (which should be redundant with the above, but for MR1 we will
            // retain this for safety).
            return true;
        }
        if (mSystemServicesReady) {
            try {
                // We allow all whitelisted apps, including those that don't want to be whitelisted
                // for idle mode, because app idle (aka app standby) is really not as big an issue
                // for controlling who participates vs. doze mode.
                if (mInjector.isPowerSaveWhitelistExceptIdleApp(packageName)) {
                    return true;
                }
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }

            if (isActiveDeviceAdmin(packageName, userId)) {
                return true;
            }

            if (isActiveNetworkScorer(packageName)) {
                return true;
            }

            if (mAppWidgetManager != null
                    && mInjector.isBoundWidgetPackage(mAppWidgetManager, packageName, userId)) {
                return true;
            }

            if (isDeviceProvisioningPackage(packageName)) {
                return true;
            }
        }

        // Check this last, as it can be the most expensive check
        if (isCarrierApp(packageName)) {
            return true;
        }

        return false;
    }

    /**
     * Checks if an app has been idle for a while and filters out apps that are excluded.
     * It returns false if the current system state allows all apps to be considered active.
     * This happens if the device is plugged in or temporarily allowed to make exceptions.
     * Called by interface impls.
     */
    boolean isAppIdleFiltered(String packageName, int appId, int userId,
            long elapsedRealtime) {
        if (isAppSpecial(packageName, appId, userId)) {
            return false;
        } else {
            return isAppIdleUnfiltered(packageName, userId, elapsedRealtime);
        }
    }

    int[] getIdleUidsForUser(int userId) {
        if (!mAppIdleEnabled) {
            return new int[0];
        }

        final long elapsedRealtime = mInjector.elapsedRealtime();

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
            Slog.d(TAG, "getIdleUids took " + (mInjector.elapsedRealtime() - elapsedRealtime));
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
        if (packageName == null || !mAppIdleEnabled) return;

        mHandler.obtainMessage(MSG_FORCE_IDLE_STATE, userId, idle ? 1 : 0, packageName)
                .sendToTarget();
    }

    @StandbyBuckets public int getAppStandbyBucket(String packageName, int userId,
            long elapsedRealtime, boolean shouldObfuscateInstantApps) {
        if (!mAppIdleEnabled || (shouldObfuscateInstantApps
                && mInjector.isPackageEphemeral(userId, packageName))) {
            return STANDBY_BUCKET_ACTIVE;
        }

        synchronized (mAppIdleLock) {
            return mAppIdleHistory.getAppStandbyBucket(packageName, userId, elapsedRealtime);
        }
    }

    public List<AppStandbyInfo> getAppStandbyBuckets(int userId) {
        synchronized (mAppIdleLock) {
            return mAppIdleHistory.getAppStandbyBuckets(userId, mAppIdleEnabled);
        }
    }

    void setAppStandbyBucket(String packageName, int userId, @StandbyBuckets int newBucket,
            int reason, long elapsedRealtime) {
        setAppStandbyBucket(packageName, userId, newBucket, reason, elapsedRealtime, false);
    }

    void setAppStandbyBucket(String packageName, int userId, @StandbyBuckets int newBucket,
            int reason, long elapsedRealtime, boolean resetTimeout) {
        synchronized (mAppIdleLock) {
            AppIdleHistory.AppUsageHistory app = mAppIdleHistory.getAppUsageHistory(packageName,
                    userId, elapsedRealtime);
            boolean predicted = (reason & REASON_MAIN_MASK) == REASON_MAIN_PREDICTED;

            // Don't allow changing bucket if higher than ACTIVE
            if (app.currentBucket < STANDBY_BUCKET_ACTIVE) return;

            // Don't allow prediction to change from/to NEVER
            if ((app.currentBucket == STANDBY_BUCKET_NEVER
                    || newBucket == STANDBY_BUCKET_NEVER)
                    && predicted) {
                return;
            }

            // If the bucket was forced, don't allow prediction to override
            if ((app.bucketingReason & REASON_MAIN_MASK) == REASON_MAIN_FORCED && predicted) return;

            // If the bucket is required to stay in a higher state for a specified duration, don't
            // override unless the duration has passed
            if (predicted) {
                // Check if the app is within one of the timeouts for forced bucket elevation
                final long elapsedTimeAdjusted = mAppIdleHistory.getElapsedTime(elapsedRealtime);
                // In case of not using the prediction, just keep track of it for applying after
                // ACTIVE or WORKING_SET timeout.
                mAppIdleHistory.updateLastPrediction(app, elapsedTimeAdjusted, newBucket);

                if (newBucket > STANDBY_BUCKET_ACTIVE
                        && app.bucketActiveTimeoutTime > elapsedTimeAdjusted) {
                    newBucket = STANDBY_BUCKET_ACTIVE;
                    reason = app.bucketingReason;
                    if (DEBUG) {
                        Slog.d(TAG, "    Keeping at ACTIVE due to min timeout");
                    }
                } else if (newBucket > STANDBY_BUCKET_WORKING_SET
                        && app.bucketWorkingSetTimeoutTime > elapsedTimeAdjusted) {
                    newBucket = STANDBY_BUCKET_WORKING_SET;
                    if (app.currentBucket != newBucket) {
                        reason = REASON_MAIN_USAGE | REASON_SUB_USAGE_ACTIVE_TIMEOUT;
                    } else {
                        reason = app.bucketingReason;
                    }
                    if (DEBUG) {
                        Slog.d(TAG, "    Keeping at WORKING_SET due to min timeout");
                    }
                }
            }

            mAppIdleHistory.setAppStandbyBucket(packageName, userId, elapsedRealtime, newBucket,
                    reason, resetTimeout);
        }
        maybeInformListeners(packageName, userId, elapsedRealtime, newBucket, reason, false);
    }

    @VisibleForTesting
    boolean isActiveDeviceAdmin(String packageName, int userId) {
        synchronized (mActiveAdminApps) {
            final Set<String> adminPkgs = mActiveAdminApps.get(userId);
            return adminPkgs != null && adminPkgs.contains(packageName);
        }
    }

    public void addActiveDeviceAdmin(String adminPkg, int userId) {
        synchronized (mActiveAdminApps) {
            Set<String> adminPkgs = mActiveAdminApps.get(userId);
            if (adminPkgs == null) {
                adminPkgs = new ArraySet<>();
                mActiveAdminApps.put(userId, adminPkgs);
            }
            adminPkgs.add(adminPkg);
        }
    }

    public void setActiveAdminApps(Set<String> adminPkgs, int userId) {
        synchronized (mActiveAdminApps) {
            if (adminPkgs == null) {
                mActiveAdminApps.remove(userId);
            } else {
                mActiveAdminApps.put(userId, adminPkgs);
            }
        }
    }

    public void onAdminDataAvailable() {
        mAdminDataAvailableLatch.countDown();
    }

    /**
     * This will only ever be called once - during device boot.
     */
    private void waitForAdminData() {
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)) {
            ConcurrentUtils.waitForCountDownNoInterrupt(mAdminDataAvailableLatch,
                    WAIT_FOR_ADMIN_DATA_TIMEOUT_MS, "Wait for admin data");
        }
    }

    Set<String> getActiveAdminAppsForTest(int userId) {
        synchronized (mActiveAdminApps) {
            return mActiveAdminApps.get(userId);
        }
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
        synchronized (mAppIdleLock) {
            mHaveCarrierPrivilegedApps = false;
            mCarrierPrivilegedApps = null; // Need to be refetched.
        }
    }

    @GuardedBy("mAppIdleLock")
    private void fetchCarrierPrivilegedAppsLocked() {
        TelephonyManager telephonyManager =
                mContext.getSystemService(TelephonyManager.class);
        mCarrierPrivilegedApps = telephonyManager.getPackagesWithCarrierPrivileges();
        mHaveCarrierPrivilegedApps = true;
        if (DEBUG) {
            Slog.d(TAG, "apps with carrier privilege " + mCarrierPrivilegedApps);
        }
    }

    private boolean isActiveNetworkScorer(String packageName) {
        String activeScorer = mInjector.getActiveNetworkScorer();
        return packageName != null && packageName.equals(activeScorer);
    }

    void informListeners(String packageName, int userId, int bucket, int reason,
            boolean userInteraction) {
        final boolean idle = bucket >= STANDBY_BUCKET_RARE;
        synchronized (mPackageAccessListeners) {
            for (AppIdleStateChangeListener listener : mPackageAccessListeners) {
                listener.onAppIdleStateChanged(packageName, userId, idle, bucket, reason);
                if (userInteraction) {
                    listener.onUserInteractionStarted(packageName, userId);
                }
            }
        }
    }

    void informParoleStateChanged() {
        final boolean paroled = isParoledOrCharging();
        synchronized (mPackageAccessListeners) {
            for (AppIdleStateChangeListener listener : mPackageAccessListeners) {
                listener.onParoleStateChanged(paroled);
            }
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
        return mInjector.isDefaultDisplayOn();
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
        if (!mSystemServicesReady) {
            // Do it later, since SettingsProvider wasn't queried yet for app_standby_enabled
            mPendingInitializeDefaults = true;
            return;
        }
        Slog.d(TAG, "Initializing defaults for system apps on user " + userId + ", "
                + "appIdleEnabled=" + mAppIdleEnabled);
        final long elapsedRealtime = mInjector.elapsedRealtime();
        List<PackageInfo> packages = mPackageManager.getInstalledPackagesAsUser(
                PackageManager.MATCH_DISABLED_COMPONENTS,
                userId);
        final int packageCount = packages.size();
        synchronized (mAppIdleLock) {
            for (int i = 0; i < packageCount; i++) {
                final PackageInfo pi = packages.get(i);
                String packageName = pi.packageName;
                if (pi.applicationInfo != null && pi.applicationInfo.isSystemApp()) {
                    // Mark app as used for 2 hours. After that it can timeout to whatever the
                    // past usage pattern was.
                    mAppIdleHistory.reportUsage(packageName, userId, STANDBY_BUCKET_ACTIVE,
                            REASON_SUB_USAGE_SYSTEM_UPDATE, 0,
                            elapsedRealtime + mSystemUpdateUsageTimeoutMillis);
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

    void postReportExemptedSyncScheduled(String packageName, int userId) {
        mHandler.obtainMessage(MSG_REPORT_EXEMPTED_SYNC_SCHEDULED, userId, 0, packageName)
                .sendToTarget();
    }

    void postReportExemptedSyncStart(String packageName, int userId) {
        mHandler.obtainMessage(MSG_REPORT_EXEMPTED_SYNC_START, userId, 0, packageName)
                .sendToTarget();
    }

    void dumpUser(IndentingPrintWriter idpw, int userId, String pkg) {
        synchronized (mAppIdleLock) {
            mAppIdleHistory.dump(idpw, userId, pkg);
        }
    }

    void dumpState(String[] args, PrintWriter pw) {
        synchronized (mAppIdleLock) {
            pw.println("Carrier privileged apps (have=" + mHaveCarrierPrivilegedApps
                    + "): " + mCarrierPrivilegedApps);
        }

        pw.println();
        pw.println("Settings:");

        pw.print("  mCheckIdleIntervalMillis=");
        TimeUtils.formatDuration(mCheckIdleIntervalMillis, pw);
        pw.println();

        pw.print("  mAppIdleParoleIntervalMillis=");
        TimeUtils.formatDuration(mAppIdleParoleIntervalMillis, pw);
        pw.println();

        pw.print("  mAppIdleParoleWindowMillis=");
        TimeUtils.formatDuration(mAppIdleParoleWindowMillis, pw);
        pw.println();

        pw.print("  mAppIdleParoleDurationMillis=");
        TimeUtils.formatDuration(mAppIdleParoleDurationMillis, pw);
        pw.println();

        pw.print("  mExemptedSyncScheduledNonDozeTimeoutMillis=");
        TimeUtils.formatDuration(mExemptedSyncScheduledNonDozeTimeoutMillis, pw);
        pw.println();
        pw.print("  mExemptedSyncScheduledDozeTimeoutMillis=");
        TimeUtils.formatDuration(mExemptedSyncScheduledDozeTimeoutMillis, pw);
        pw.println();
        pw.print("  mExemptedSyncStartTimeoutMillis=");
        TimeUtils.formatDuration(mExemptedSyncStartTimeoutMillis, pw);
        pw.println();

        pw.println();
        pw.print("mAppIdleEnabled="); pw.print(mAppIdleEnabled);
        pw.print(" mAppIdleTempParoled="); pw.print(mAppIdleTempParoled);
        pw.print(" mCharging="); pw.print(mCharging);
        pw.print(" mChargingStable="); pw.print(mChargingStable);
        pw.print(" mLastAppIdleParoledTime=");
        TimeUtils.formatDuration(mLastAppIdleParoledTime, pw);
        pw.println();
        pw.print("mScreenThresholds="); pw.println(Arrays.toString(mAppStandbyScreenThresholds));
        pw.print("mElapsedThresholds="); pw.println(Arrays.toString(mAppStandbyElapsedThresholds));
        pw.print("mStableChargingThresholdMillis=");
        TimeUtils.formatDuration(mStableChargingThresholdMillis, pw);
        pw.println();
    }

    /**
     * Injector for interaction with external code. Override methods to provide a mock
     * implementation for tests.
     * onBootPhase() must be called with at least the PHASE_SYSTEM_SERVICES_READY
     */
    static class Injector {

        private final Context mContext;
        private final Looper mLooper;
        private IDeviceIdleController mDeviceIdleController;
        private IBatteryStats mBatteryStats;
        private PackageManagerInternal mPackageManagerInternal;
        private DisplayManager mDisplayManager;
        private PowerManager mPowerManager;
        int mBootPhase;

        Injector(Context context, Looper looper) {
            mContext = context;
            mLooper = looper;
        }

        Context getContext() {
            return mContext;
        }

        Looper getLooper() {
            return mLooper;
        }

        void onBootPhase(int phase) {
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                        ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
                mBatteryStats = IBatteryStats.Stub.asInterface(
                        ServiceManager.getService(BatteryStats.SERVICE_NAME));
                mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
                mDisplayManager = (DisplayManager) mContext.getSystemService(
                        Context.DISPLAY_SERVICE);
                mPowerManager = mContext.getSystemService(PowerManager.class);
            }
            mBootPhase = phase;
        }

        int getBootPhase() {
            return mBootPhase;
        }

        /**
         * Returns the elapsed realtime since the device started. Override this
         * to control the clock.
         * @return elapsed realtime
         */
        long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }

        long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        boolean isAppIdleEnabled() {
            final boolean buildFlag = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_enableAutoPowerModes);
            final boolean runtimeFlag = Global.getInt(mContext.getContentResolver(),
                    Global.APP_STANDBY_ENABLED, 1) == 1
                    && Global.getInt(mContext.getContentResolver(),
                    Global.ADAPTIVE_BATTERY_MANAGEMENT_ENABLED, 1) == 1;
            return buildFlag && runtimeFlag;
        }

        boolean isCharging() {
            return mContext.getSystemService(BatteryManager.class).isCharging();
        }

        boolean isPowerSaveWhitelistExceptIdleApp(String packageName) throws RemoteException {
            return mDeviceIdleController.isPowerSaveWhitelistExceptIdleApp(packageName);
        }

        File getDataSystemDirectory() {
            return Environment.getDataSystemDirectory();
        }

        void noteEvent(int event, String packageName, int uid) throws RemoteException {
            mBatteryStats.noteEvent(event, packageName, uid);
        }

        boolean isPackageEphemeral(int userId, String packageName) {
            return mPackageManagerInternal.isPackageEphemeral(userId, packageName);
        }

        int[] getRunningUserIds() throws RemoteException {
            return ActivityManager.getService().getRunningUserIds();
        }

        boolean isDefaultDisplayOn() {
            return mDisplayManager
                    .getDisplay(Display.DEFAULT_DISPLAY).getState() == Display.STATE_ON;
        }

        void registerDisplayListener(DisplayManager.DisplayListener listener, Handler handler) {
            mDisplayManager.registerDisplayListener(listener, handler);
        }

        String getActiveNetworkScorer() {
            NetworkScoreManager nsm = (NetworkScoreManager) mContext.getSystemService(
                    Context.NETWORK_SCORE_SERVICE);
            return nsm.getActiveScorerPackage();
        }

        public boolean isBoundWidgetPackage(AppWidgetManager appWidgetManager, String packageName,
                int userId) {
            return appWidgetManager.isBoundWidgetPackage(packageName, userId);
        }

        String getAppIdleSettings() {
            return Global.getString(mContext.getContentResolver(),
                    Global.APP_IDLE_CONSTANTS);
        }

        /** Whether the device is in doze or not. */
        public boolean isDeviceIdleMode() {
            return mPowerManager.isDeviceIdleMode();
        }
    }

    class AppStandbyHandler extends Handler {

        AppStandbyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_INFORM_LISTENERS:
                    StandbyUpdateRecord r = (StandbyUpdateRecord) msg.obj;
                    informListeners(r.packageName, r.userId, r.bucket, r.reason,
                            r.isUserInteraction);
                    r.recycle();
                    break;

                case MSG_FORCE_IDLE_STATE:
                    forceIdleState((String) msg.obj, msg.arg1, msg.arg2 == 1);
                    break;

                case MSG_CHECK_IDLE_STATES:
                    if (checkIdleStates(msg.arg1) && mAppIdleEnabled) {
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MSG_CHECK_IDLE_STATES, msg.arg1, 0),
                                mCheckIdleIntervalMillis);
                    }
                    break;

                case MSG_ONE_TIME_CHECK_IDLE_STATES:
                    mHandler.removeMessages(MSG_ONE_TIME_CHECK_IDLE_STATES);
                    waitForAdminData();
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
                            + ", Charging state:" + mChargingStable);
                    informParoleStateChanged();
                    break;
                case MSG_CHECK_PACKAGE_IDLE_STATE:
                    checkAndUpdateStandbyState((String) msg.obj, msg.arg1, msg.arg2,
                            mInjector.elapsedRealtime());
                    break;

                case MSG_REPORT_EXEMPTED_SYNC_SCHEDULED:
                    reportExemptedSyncScheduled((String) msg.obj, msg.arg1);
                    break;

                case MSG_REPORT_EXEMPTED_SYNC_START:
                    reportExemptedSyncStart((String) msg.obj, msg.arg1);
                    break;

                case MSG_UPDATE_STABLE_CHARGING:
                    updateChargingStableState();
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
            switch (intent.getAction()) {
                case BatteryManager.ACTION_CHARGING:
                    setChargingState(true);
                    break;
                case BatteryManager.ACTION_DISCHARGING:
                    setChargingState(false);
                    break;
                case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                    onDeviceIdleModeChanged();
                    break;
            }
        }
    }

    private final NetworkRequest mNetworkRequest = new NetworkRequest.Builder().build();

    private final ConnectivityManager.NetworkCallback mNetworkCallback
            = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            mConnectivityManager.unregisterNetworkCallback(this);
            checkParoleTimeout();
        }
    };

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
                    mAppIdleHistory.updateDisplay(displayOn, mInjector.elapsedRealtime());
                }
            }
        }
    };

    /**
     * Observe settings changes for {@link Global#APP_IDLE_CONSTANTS}.
     */
    private class SettingsObserver extends ContentObserver {
        /**
         * This flag has been used to disable app idle on older builds with bug b/26355386.
         */
        @Deprecated
        private static final String KEY_IDLE_DURATION_OLD = "idle_duration";
        @Deprecated
        private static final String KEY_IDLE_DURATION = "idle_duration2";
        @Deprecated
        private static final String KEY_WALLCLOCK_THRESHOLD = "wallclock_threshold";

        private static final String KEY_PAROLE_INTERVAL = "parole_interval";
        private static final String KEY_PAROLE_WINDOW = "parole_window";
        private static final String KEY_PAROLE_DURATION = "parole_duration";
        private static final String KEY_SCREEN_TIME_THRESHOLDS = "screen_thresholds";
        private static final String KEY_ELAPSED_TIME_THRESHOLDS = "elapsed_thresholds";
        private static final String KEY_STRONG_USAGE_HOLD_DURATION = "strong_usage_duration";
        private static final String KEY_NOTIFICATION_SEEN_HOLD_DURATION =
                "notification_seen_duration";
        private static final String KEY_SYSTEM_UPDATE_HOLD_DURATION =
                "system_update_usage_duration";
        private static final String KEY_PREDICTION_TIMEOUT = "prediction_timeout";
        private static final String KEY_SYNC_ADAPTER_HOLD_DURATION = "sync_adapter_duration";
        private static final String KEY_EXEMPTED_SYNC_SCHEDULED_NON_DOZE_HOLD_DURATION
                = "exempted_sync_scheduled_nd_duration";
        private static final String KEY_EXEMPTED_SYNC_SCHEDULED_DOZE_HOLD_DURATION
                = "exempted_sync_scheduled_d_duration";
        private static final String KEY_EXEMPTED_SYNC_START_HOLD_DURATION
                = "exempted_sync_start_duration";
        private static final String KEY_SYSTEM_INTERACTION_HOLD_DURATION =
                "system_interaction_duration";
        private static final String KEY_STABLE_CHARGING_THRESHOLD = "stable_charging_threshold";
        public static final long DEFAULT_STRONG_USAGE_TIMEOUT = 1 * ONE_HOUR;
        public static final long DEFAULT_NOTIFICATION_TIMEOUT = 12 * ONE_HOUR;
        public static final long DEFAULT_SYSTEM_UPDATE_TIMEOUT = 2 * ONE_HOUR;
        public static final long DEFAULT_SYSTEM_INTERACTION_TIMEOUT = 10 * ONE_MINUTE;
        public static final long DEFAULT_SYNC_ADAPTER_TIMEOUT = 10 * ONE_MINUTE;
        public static final long DEFAULT_EXEMPTED_SYNC_SCHEDULED_NON_DOZE_TIMEOUT = 10 * ONE_MINUTE;
        public static final long DEFAULT_EXEMPTED_SYNC_SCHEDULED_DOZE_TIMEOUT = 4 * ONE_HOUR;
        public static final long DEFAULT_EXEMPTED_SYNC_START_TIMEOUT = 10 * ONE_MINUTE;
        public static final long DEFAULT_STABLE_CHARGING_THRESHOLD = 10 * ONE_MINUTE;

        private final KeyValueListParser mParser = new KeyValueListParser(',');

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void registerObserver() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Global.getUriFor(Global.APP_IDLE_CONSTANTS), false, this);
            cr.registerContentObserver(Global.getUriFor(Global.APP_STANDBY_ENABLED), false, this);
            cr.registerContentObserver(Global.getUriFor(Global.ADAPTIVE_BATTERY_MANAGEMENT_ENABLED),
                    false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
            postOneTimeCheckIdleStates();
        }

        void updateSettings() {
            if (DEBUG) {
                Slog.d(TAG,
                        "appidle=" + Global.getString(mContext.getContentResolver(),
                                Global.APP_STANDBY_ENABLED));
                Slog.d(TAG,
                        "adaptivebat=" + Global.getString(mContext.getContentResolver(),
                                Global.ADAPTIVE_BATTERY_MANAGEMENT_ENABLED));
                Slog.d(TAG, "appidleconstants=" + Global.getString(
                        mContext.getContentResolver(),
                        Global.APP_IDLE_CONSTANTS));
            }
            // Check if app_idle_enabled has changed
            setAppIdleEnabled(mInjector.isAppIdleEnabled());

            // Look at global settings for this.
            // TODO: Maybe apply different thresholds for different users.
            try {
                mParser.setString(mInjector.getAppIdleSettings());
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Bad value for app idle settings: " + e.getMessage());
                // fallthrough, mParser is empty and all defaults will be returned.
            }

            synchronized (mAppIdleLock) {

                // Default: 24 hours between paroles
                mAppIdleParoleIntervalMillis = mParser.getDurationMillis(KEY_PAROLE_INTERVAL,
                        COMPRESS_TIME ? ONE_MINUTE * 10 : 24 * 60 * ONE_MINUTE);

                // Default: 2 hours to wait on network
                mAppIdleParoleWindowMillis = mParser.getDurationMillis(KEY_PAROLE_WINDOW,
                        COMPRESS_TIME ? ONE_MINUTE * 2 : 2 * 60 * ONE_MINUTE);

                mAppIdleParoleDurationMillis = mParser.getDurationMillis(KEY_PAROLE_DURATION,
                        COMPRESS_TIME ? ONE_MINUTE : 10 * ONE_MINUTE); // 10 minutes

                String screenThresholdsValue = mParser.getString(KEY_SCREEN_TIME_THRESHOLDS, null);
                mAppStandbyScreenThresholds = parseLongArray(screenThresholdsValue,
                        SCREEN_TIME_THRESHOLDS);

                String elapsedThresholdsValue = mParser.getString(KEY_ELAPSED_TIME_THRESHOLDS,
                        null);
                mAppStandbyElapsedThresholds = parseLongArray(elapsedThresholdsValue,
                        ELAPSED_TIME_THRESHOLDS);
                mCheckIdleIntervalMillis = Math.min(mAppStandbyElapsedThresholds[1] / 4,
                        COMPRESS_TIME ? ONE_MINUTE : 4 * 60 * ONE_MINUTE); // 4 hours
                mStrongUsageTimeoutMillis = mParser.getDurationMillis
                        (KEY_STRONG_USAGE_HOLD_DURATION,
                                COMPRESS_TIME ? ONE_MINUTE : DEFAULT_STRONG_USAGE_TIMEOUT);
                mNotificationSeenTimeoutMillis = mParser.getDurationMillis
                        (KEY_NOTIFICATION_SEEN_HOLD_DURATION,
                                COMPRESS_TIME ? 12 * ONE_MINUTE : DEFAULT_NOTIFICATION_TIMEOUT);
                mSystemUpdateUsageTimeoutMillis = mParser.getDurationMillis
                        (KEY_SYSTEM_UPDATE_HOLD_DURATION,
                                COMPRESS_TIME ? 2 * ONE_MINUTE : DEFAULT_SYSTEM_UPDATE_TIMEOUT);
                mPredictionTimeoutMillis = mParser.getDurationMillis
                        (KEY_PREDICTION_TIMEOUT,
                                COMPRESS_TIME ? 10 * ONE_MINUTE : DEFAULT_PREDICTION_TIMEOUT);
                mSyncAdapterTimeoutMillis = mParser.getDurationMillis
                        (KEY_SYNC_ADAPTER_HOLD_DURATION,
                                COMPRESS_TIME ? ONE_MINUTE : DEFAULT_SYNC_ADAPTER_TIMEOUT);

                mExemptedSyncScheduledNonDozeTimeoutMillis = mParser.getDurationMillis
                        (KEY_EXEMPTED_SYNC_SCHEDULED_NON_DOZE_HOLD_DURATION,
                                COMPRESS_TIME ? (ONE_MINUTE / 2)
                                        : DEFAULT_EXEMPTED_SYNC_SCHEDULED_NON_DOZE_TIMEOUT);

                mExemptedSyncScheduledDozeTimeoutMillis = mParser.getDurationMillis
                        (KEY_EXEMPTED_SYNC_SCHEDULED_DOZE_HOLD_DURATION,
                                COMPRESS_TIME ? ONE_MINUTE
                                        : DEFAULT_EXEMPTED_SYNC_SCHEDULED_DOZE_TIMEOUT);

                mExemptedSyncStartTimeoutMillis = mParser.getDurationMillis
                        (KEY_EXEMPTED_SYNC_START_HOLD_DURATION,
                                COMPRESS_TIME ? ONE_MINUTE
                                        : DEFAULT_EXEMPTED_SYNC_START_TIMEOUT);

                mSystemInteractionTimeoutMillis = mParser.getDurationMillis
                        (KEY_SYSTEM_INTERACTION_HOLD_DURATION,
                                COMPRESS_TIME ? ONE_MINUTE : DEFAULT_SYSTEM_INTERACTION_TIMEOUT);
                mStableChargingThresholdMillis = mParser.getDurationMillis
                        (KEY_STABLE_CHARGING_THRESHOLD,
                                COMPRESS_TIME ? ONE_MINUTE : DEFAULT_STABLE_CHARGING_THRESHOLD);
            }
        }

        long[] parseLongArray(String values, long[] defaults) {
            if (values == null) return defaults;
            if (values.isEmpty()) {
                // Reset to defaults
                return defaults;
            } else {
                String[] thresholds = values.split("/");
                if (thresholds.length == THRESHOLD_BUCKETS.length) {
                    long[] array = new long[THRESHOLD_BUCKETS.length];
                    for (int i = 0; i < THRESHOLD_BUCKETS.length; i++) {
                        try {
                            if (thresholds[i].startsWith("P") || thresholds[i].startsWith("p")) {
                                array[i] = Duration.parse(thresholds[i]).toMillis();
                            } else {
                                array[i] = Long.parseLong(thresholds[i]);
                            }
                        } catch (NumberFormatException|DateTimeParseException e) {
                            return defaults;
                        }
                    }
                    return array;
                } else {
                    return defaults;
                }
            }
        }
    }
}

