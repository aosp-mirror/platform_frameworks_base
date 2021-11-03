/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import static android.provider.Settings.Global.TARE_ALARM_MANAGER_CONSTANTS;
import static android.provider.Settings.Global.TARE_JOB_SCHEDULER_CONSTANTS;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.server.tare.TareUtils.appToString;
import static com.android.server.tare.TareUtils.getCurrentTimeMillis;
import static com.android.server.tare.TareUtils.narcToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.tare.IEconomyManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManagerInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.SparseSetArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Responsible for handling app's ARC count based on events, ensuring ARCs are credited when
 * appropriate, and reclaiming ARCs at the right times. The IRS deals with the high level details
 * while the {@link Agent} deals with the nitty-gritty details.
 *
 * Note on locking: Any function with the suffix 'Locked' needs to lock on {@link #mLock}.
 *
 * @hide
 */
public class InternalResourceService extends SystemService {
    public static final String TAG = "TARE-IRS";
    public static final boolean DEBUG = Log.isLoggable("TARE", Log.DEBUG);

    static final long UNUSED_RECLAMATION_PERIOD_MS = 24 * HOUR_IN_MILLIS;
    /** How much of an app's unused wealth should be reclaimed periodically. */
    private static final float DEFAULT_UNUSED_RECLAMATION_PERCENTAGE = .1f;
    /**
     * The minimum amount of time an app must not have been used by the user before we start
     * periodically reclaiming ARCs from it.
     */
    private static final long MIN_UNUSED_TIME_MS = 3 * DAY_IN_MILLIS;
    /** The amount of time to delay reclamation by after boot. */
    private static final long RECLAMATION_STARTUP_DELAY_MS = 30_000L;
    private static final int PACKAGE_QUERY_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.MATCH_APEX;

    /** Global lock for all resource economy state. */
    private final Object mLock = new Object();

    private final Handler mHandler;
    private final BatteryManagerInternal mBatteryManagerInternal;
    private final PackageManager mPackageManager;
    private final PackageManagerInternal mPackageManagerInternal;

    private final Agent mAgent;
    private final ConfigObserver mConfigObserver;
    private final EconomyManagerStub mEconomyManagerStub;
    private final Scribe mScribe;

    @GuardedBy("mLock")
    private CompleteEconomicPolicy mCompleteEconomicPolicy;

    private static final class ReclamationConfig {
        /**
         * ARC circulation threshold (% circulating vs scaled maximum) above which this config
         * should come into play.
         */
        public final double circulationPercentageThreshold;
        /** @see Agent#reclaimUnusedAssetsLocked(double, long, boolean) */
        public final double reclamationPercentage;
        /** @see Agent#reclaimUnusedAssetsLocked(double, long, boolean) */
        public final long minUsedTimeMs;
        /** @see Agent#reclaimUnusedAssetsLocked(double, long, boolean) */
        public final boolean scaleMinBalance;

        ReclamationConfig(double circulationPercentageThreshold, double reclamationPercentage,
                long minUsedTimeMs, boolean scaleMinBalance) {
            this.circulationPercentageThreshold = circulationPercentageThreshold;
            this.reclamationPercentage = reclamationPercentage;
            this.minUsedTimeMs = minUsedTimeMs;
            this.scaleMinBalance = scaleMinBalance;
        }
    }

    /**
     * Sorted list of reclamation configs used to determine how many credits to force reclaim when
     * the circulation percentage is too high. The list should *always* be sorted in descending
     * order of {@link ReclamationConfig#circulationPercentageThreshold}.
     */
    @GuardedBy("mLock")
    private final List<ReclamationConfig> mReclamationConfigs = List.of(
            new ReclamationConfig(2, .75, 12 * HOUR_IN_MILLIS, true),
            new ReclamationConfig(1.6, .5, DAY_IN_MILLIS, true),
            new ReclamationConfig(1.4, .25, DAY_IN_MILLIS, true),
            new ReclamationConfig(1.2, .25, 2 * DAY_IN_MILLIS, true),
            new ReclamationConfig(1, .25, MIN_UNUSED_TIME_MS, false),
            new ReclamationConfig(
                    .9, DEFAULT_UNUSED_RECLAMATION_PERCENTAGE, MIN_UNUSED_TIME_MS, false)
    );

    @NonNull
    @GuardedBy("mLock")
    private final List<PackageInfo> mPkgCache = new ArrayList<>();

    /** Cached mapping of UIDs (for all users) to a list of packages in the UID. */
    @GuardedBy("mLock")
    private final SparseSetArray<String> mUidToPackageCache = new SparseSetArray<>();

    /** Cached mapping of userId+package to their UIDs (for all users) */
    @GuardedBy("mPackageToUidCache")
    private final SparseArrayMap<String, Integer> mPackageToUidCache = new SparseArrayMap<>();

    private volatile boolean mIsEnabled;
    private volatile int mBootPhase;
    // In the range [0,100] to represent 0% to 100% battery.
    @GuardedBy("mLock")
    private int mCurrentBatteryLevel;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Nullable
        private String getPackageName(Intent intent) {
            Uri uri = intent.getData();
            return uri != null ? uri.getSchemeSpecificPart() : null;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_BATTERY_LEVEL_CHANGED:
                    onBatteryLevelChanged();
                    break;
                case Intent.ACTION_PACKAGE_FULLY_REMOVED: {
                    final String pkgName = getPackageName(intent);
                    final int pkgUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    onPackageRemoved(pkgUid, pkgName);
                }
                break;
                case Intent.ACTION_PACKAGE_ADDED: {
                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        final String pkgName = getPackageName(intent);
                        final int pkgUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                        onPackageAdded(pkgUid, pkgName);
                    }
                }
                break;
                case Intent.ACTION_PACKAGE_RESTARTED: {
                    final String pkgName = getPackageName(intent);
                    final int pkgUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    final int userId = UserHandle.getUserId(pkgUid);
                    onPackageForceStopped(userId, pkgName);
                }
                break;
                case Intent.ACTION_USER_ADDED: {
                    final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                    onUserAdded(userId);
                }
                break;
                case Intent.ACTION_USER_REMOVED: {
                    final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                    onUserRemoved(userId);
                }
                break;
            }
        }
    };

    private final UsageStatsManagerInternal.UsageEventListener mSurveillanceAgent =
            new UsageStatsManagerInternal.UsageEventListener() {
                /**
                 * Callback to inform listeners of a new event.
                 */
                @Override
                public void onUsageEvent(int userId, @NonNull UsageEvents.Event event) {
                    mHandler.obtainMessage(MSG_PROCESS_USAGE_EVENT, userId, 0, event)
                            .sendToTarget();
                }
            };

    private final AlarmManager.OnAlarmListener mUnusedWealthReclamationListener =
            new AlarmManager.OnAlarmListener() {
                @Override
                public void onAlarm() {
                    synchronized (mLock) {
                        mAgent.reclaimUnusedAssetsLocked(
                                DEFAULT_UNUSED_RECLAMATION_PERCENTAGE, MIN_UNUSED_TIME_MS, false);
                        mScribe.setLastReclamationTimeLocked(getCurrentTimeMillis());
                        scheduleUnusedWealthReclamationLocked();
                    }
                }
            };

    private static final int MSG_NOTIFY_AFFORDABILITY_CHANGE_LISTENER = 0;
    private static final int MSG_SCHEDULE_UNUSED_WEALTH_RECLAMATION_EVENT = 1;
    private static final int MSG_PROCESS_USAGE_EVENT = 2;
    private static final int MSG_MAYBE_FORCE_RECLAIM = 3;
    private static final String ALARM_TAG_WEALTH_RECLAMATION = "*tare.reclamation*";

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public InternalResourceService(Context context) {
        super(context);

        mHandler = new IrsHandler(TareHandlerThread.get().getLooper());
        mBatteryManagerInternal = LocalServices.getService(BatteryManagerInternal.class);
        mPackageManager = context.getPackageManager();
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mEconomyManagerStub = new EconomyManagerStub();
        mScribe = new Scribe(this);
        mCompleteEconomicPolicy = new CompleteEconomicPolicy(this);
        mAgent = new Agent(this, mScribe);

        mConfigObserver = new ConfigObserver(mHandler, context);

        publishLocalService(EconomyManagerInternal.class, new LocalService());
    }

    @Override
    public void onStart() {
        publishBinderService(Context.RESOURCE_ECONOMY_SERVICE, mEconomyManagerStub);
    }

    @Override
    public void onBootPhase(int phase) {
        mBootPhase = phase;

        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            mConfigObserver.start();
            setupEverything();
        }
    }

    @NonNull
    Object getLock() {
        return mLock;
    }

    /** Returns the installed packages for all users. */
    @NonNull
    @GuardedBy("mLock")
    CompleteEconomicPolicy getCompleteEconomicPolicyLocked() {
        return mCompleteEconomicPolicy;
    }

    @NonNull
    List<PackageInfo> getInstalledPackages() {
        synchronized (mLock) {
            return mPkgCache;
        }
    }

    /** Returns the installed packages for the specified user. */
    @NonNull
    List<PackageInfo> getInstalledPackages(final int userId) {
        final List<PackageInfo> userPkgs = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mPkgCache.size(); ++i) {
                final PackageInfo packageInfo = mPkgCache.get(i);
                if (packageInfo.applicationInfo != null
                        && UserHandle.getUserId(packageInfo.applicationInfo.uid) == userId) {
                    userPkgs.add(packageInfo);
                }
            }
        }
        return userPkgs;
    }

    @GuardedBy("mLock")
    long getMaxCirculationLocked() {
        return mCurrentBatteryLevel * mCompleteEconomicPolicy.getMaxSatiatedCirculation() / 100;
    }

    @GuardedBy("mLock")
    long getMinBalanceLocked(final int userId, @NonNull final String pkgName) {
        return mCurrentBatteryLevel * mCompleteEconomicPolicy.getMinSatiatedBalance(userId, pkgName)
                / 100;
    }

    int getUid(final int userId, @NonNull final String pkgName) {
        synchronized (mPackageToUidCache) {
            Integer uid = mPackageToUidCache.get(userId, pkgName);
            if (uid == null) {
                uid = mPackageManagerInternal.getPackageUid(pkgName, 0, userId);
                mPackageToUidCache.add(userId, pkgName, uid);
            }
            return uid;
        }
    }

    boolean isEnabled() {
        return mIsEnabled;
    }

    boolean isSystem(final int userId, @NonNull String pkgName) {
        if ("android".equals(pkgName)) {
            return true;
        }
        return UserHandle.isCore(getUid(userId, pkgName));
    }

    void onBatteryLevelChanged() {
        synchronized (mLock) {
            final int newBatteryLevel = getCurrentBatteryLevel();
            if (newBatteryLevel > mCurrentBatteryLevel) {
                mAgent.distributeBasicIncomeLocked(newBatteryLevel);
            } else if (newBatteryLevel < mCurrentBatteryLevel) {
                mHandler.obtainMessage(MSG_MAYBE_FORCE_RECLAIM).sendToTarget();
            }
            mCurrentBatteryLevel = newBatteryLevel;
        }
    }

    void onDeviceStateChanged() {
        synchronized (mLock) {
            mAgent.onDeviceStateChangedLocked();
        }
    }

    void onPackageAdded(final int uid, @NonNull final String pkgName) {
        final int userId = UserHandle.getUserId(uid);
        final PackageInfo packageInfo;
        try {
            packageInfo =
                    mPackageManager.getPackageInfoAsUser(pkgName, PACKAGE_QUERY_FLAGS, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.wtf(TAG, "PM couldn't find newly added package: " + pkgName, e);
            return;
        }
        synchronized (mPackageToUidCache) {
            mPackageToUidCache.add(userId, pkgName, uid);
        }
        synchronized (mLock) {
            mPkgCache.add(packageInfo);
            mUidToPackageCache.add(uid, pkgName);
            // TODO: only do this when the user first launches the app (app leaves stopped state)
            mAgent.grantBirthrightLocked(userId, pkgName);
        }
    }

    void onPackageForceStopped(final int userId, @NonNull final String pkgName) {
        synchronized (mLock) {
            // TODO: reduce ARC count by some amount
        }
    }

    void onPackageRemoved(final int uid, @NonNull final String pkgName) {
        final int userId = UserHandle.getUserId(uid);
        synchronized (mPackageToUidCache) {
            mPackageToUidCache.delete(userId, pkgName);
        }
        synchronized (mLock) {
            mUidToPackageCache.remove(uid, pkgName);
            for (int i = 0; i < mPkgCache.size(); ++i) {
                PackageInfo pkgInfo = mPkgCache.get(i);
                if (UserHandle.getUserId(pkgInfo.applicationInfo.uid) == userId
                        && pkgName.equals(pkgInfo.packageName)) {
                    mPkgCache.remove(i);
                    break;
                }
            }
            mAgent.onPackageRemovedLocked(userId, pkgName);
        }
    }

    void onUidStateChanged(final int uid) {
        synchronized (mLock) {
            final ArraySet<String> pkgNames = getPackagesForUidLocked(uid);
            if (pkgNames == null) {
                Slog.e(TAG, "Don't have packages for uid " + uid);
            } else {
                mAgent.onAppStatesChangedLocked(UserHandle.getUserId(uid), pkgNames);
            }
        }
    }

    void onUserAdded(final int userId) {
        synchronized (mLock) {
            mPkgCache.addAll(
                    mPackageManager.getInstalledPackagesAsUser(PACKAGE_QUERY_FLAGS, userId));
            mAgent.grantBirthrightsLocked(userId);
        }
    }

    void onUserRemoved(final int userId) {
        synchronized (mLock) {
            ArrayList<String> removedPkgs = new ArrayList<>();
            for (int i = mPkgCache.size() - 1; i >= 0; --i) {
                PackageInfo pkgInfo = mPkgCache.get(i);
                if (UserHandle.getUserId(pkgInfo.applicationInfo.uid) == userId) {
                    removedPkgs.add(pkgInfo.packageName);
                    mUidToPackageCache.remove(pkgInfo.applicationInfo.uid);
                    mPkgCache.remove(i);
                    break;
                }
            }
            mAgent.onUserRemovedLocked(userId, removedPkgs);
        }
    }

    void postAffordabilityChanged(final int userId, @NonNull final String pkgName,
            @NonNull Agent.ActionAffordabilityNote affordabilityNote) {
        if (DEBUG) {
            Slog.d(TAG, userId + ":" + pkgName + " affordability changed to "
                    + affordabilityNote.isCurrentlyAffordable());
        }
        final SomeArgs args = SomeArgs.obtain();
        args.argi1 = userId;
        args.arg1 = pkgName;
        args.arg2 = affordabilityNote;
        mHandler.obtainMessage(MSG_NOTIFY_AFFORDABILITY_CHANGE_LISTENER, args).sendToTarget();
    }

    @GuardedBy("mLock")
    private void processUsageEventLocked(final int userId, @NonNull UsageEvents.Event event) {
        if (!mIsEnabled) {
            return;
        }
        final String pkgName = event.getPackageName();
        if (DEBUG) {
            Slog.d(TAG, "Processing event " + event.getEventType()
                    + " for " + appToString(userId, pkgName));
        }
        final long nowElapsed = SystemClock.elapsedRealtime();
        switch (event.getEventType()) {
            case UsageEvents.Event.ACTIVITY_RESUMED:
                mAgent.noteOngoingEventLocked(userId, pkgName,
                        EconomicPolicy.REWARD_TOP_ACTIVITY, null, nowElapsed);
                break;
            case UsageEvents.Event.ACTIVITY_PAUSED:
            case UsageEvents.Event.ACTIVITY_STOPPED:
            case UsageEvents.Event.ACTIVITY_DESTROYED:
                final long now = getCurrentTimeMillis();
                mAgent.stopOngoingActionLocked(userId, pkgName,
                        EconomicPolicy.REWARD_TOP_ACTIVITY, null, nowElapsed, now);
                break;
            case UsageEvents.Event.USER_INTERACTION:
            case UsageEvents.Event.CHOOSER_ACTION:
                mAgent.noteInstantaneousEventLocked(userId, pkgName,
                        EconomicPolicy.REWARD_OTHER_USER_INTERACTION, null);
                break;
            case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
            case UsageEvents.Event.NOTIFICATION_SEEN:
                mAgent.noteInstantaneousEventLocked(userId, pkgName,
                        EconomicPolicy.REWARD_NOTIFICATION_SEEN, null);
                break;
        }
    }

    @GuardedBy("mLock")
    private void scheduleUnusedWealthReclamationLocked() {
        final long now = getCurrentTimeMillis();
        final long nextReclamationTime = Math.max(now + RECLAMATION_STARTUP_DELAY_MS,
                mScribe.getLastReclamationTimeLocked() + UNUSED_RECLAMATION_PERIOD_MS);
        mHandler.post(() -> {
            // Never call out to AlarmManager with the lock held. This sits below AM.
            AlarmManager alarmManager = getContext().getSystemService(AlarmManager.class);
            if (alarmManager != null) {
                alarmManager.setWindow(AlarmManager.ELAPSED_REALTIME,
                        SystemClock.elapsedRealtime() + (nextReclamationTime - now),
                        30 * MINUTE_IN_MILLIS,
                        ALARM_TAG_WEALTH_RECLAMATION, mUnusedWealthReclamationListener, mHandler);
            } else {
                mHandler.sendEmptyMessageDelayed(
                        MSG_SCHEDULE_UNUSED_WEALTH_RECLAMATION_EVENT, RECLAMATION_STARTUP_DELAY_MS);
            }
        });
    }

    private int getCurrentBatteryLevel() {
        return mBatteryManagerInternal.getBatteryLevel();
    }

    @Nullable
    @GuardedBy("mLock")
    private ArraySet<String> getPackagesForUidLocked(final int uid) {
        ArraySet<String> packages = mUidToPackageCache.get(uid);
        if (packages == null) {
            final String[] pkgs = mPackageManager.getPackagesForUid(uid);
            if (pkgs != null) {
                for (String pkg : pkgs) {
                    mUidToPackageCache.add(uid, pkg);
                }
                packages = mUidToPackageCache.get(uid);
            }
        }
        return packages;
    }

    @GuardedBy("mLock")
    private void loadInstalledPackageListLocked() {
        mPkgCache.clear();
        final UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        final int[] userIds = userManagerInternal.getUserIds();
        for (int userId : userIds) {
            mPkgCache.addAll(
                    mPackageManager.getInstalledPackagesAsUser(PACKAGE_QUERY_FLAGS, userId));
        }
    }

    /**
     * Reclaim unused ARCs above apps' minimum balances if there are too many credits currently
     * in circulation.
     */
    @GuardedBy("mLock")
    private void maybeForceReclaimLocked() {
        final long maxCirculation = getMaxCirculationLocked();
        if (maxCirculation == 0) {
            Slog.wtf(TAG, "Max scaled circulation is 0...");
            mAgent.reclaimUnusedAssetsLocked(1, HOUR_IN_MILLIS, true);
            mScribe.setLastReclamationTimeLocked(getCurrentTimeMillis());
            scheduleUnusedWealthReclamationLocked();
            return;
        }
        final long curCirculation = mScribe.getNarcsInCirculationLocked();
        final double circulationPerc = 1.0 * curCirculation / maxCirculation;
        if (DEBUG) {
            Slog.d(TAG, "Circulation %: " + circulationPerc);
        }
        final int numConfigs = mReclamationConfigs.size();
        if (numConfigs == 0) {
            return;
        }
        // The configs are sorted in descending order of circulationPercentageThreshold, so we can
        // short-circuit if the current circulation is lower than the lowest threshold.
        if (circulationPerc
                < mReclamationConfigs.get(numConfigs - 1).circulationPercentageThreshold) {
            return;
        }
        // TODO: maybe exclude apps we think will be launched in the next few hours
        for (int i = 0; i < numConfigs; ++i) {
            final ReclamationConfig config = mReclamationConfigs.get(i);
            if (circulationPerc >= config.circulationPercentageThreshold) {
                mAgent.reclaimUnusedAssetsLocked(
                        config.reclamationPercentage, config.minUsedTimeMs, config.scaleMinBalance);
                mScribe.setLastReclamationTimeLocked(getCurrentTimeMillis());
                scheduleUnusedWealthReclamationLocked();
                break;
            }
        }
    }

    private void registerListeners() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_LEVEL_CHANGED);
        getContext().registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);

        final IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        pkgFilter.addDataScheme("package");
        getContext()
                .registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, pkgFilter, null, null);

        final IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        getContext()
                .registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, userFilter, null, null);

        UsageStatsManagerInternal usmi = LocalServices.getService(UsageStatsManagerInternal.class);
        usmi.registerListener(mSurveillanceAgent);
    }

    /** Perform long-running and/or heavy setup work. This should be called off the main thread. */
    private void setupHeavyWork() {
        synchronized (mLock) {
            loadInstalledPackageListLocked();
            final boolean isFirstSetup = !mScribe.recordExists();
            if (isFirstSetup) {
                mAgent.grantBirthrightsLocked();
            } else {
                mScribe.loadFromDiskLocked();
            }
            scheduleUnusedWealthReclamationLocked();
        }
    }

    private void setupEverything() {
        if (mBootPhase < PHASE_SYSTEM_SERVICES_READY || !mIsEnabled) {
            return;
        }
        synchronized (mLock) {
            registerListeners();
            mCurrentBatteryLevel = getCurrentBatteryLevel();
            mHandler.post(this::setupHeavyWork);
            mCompleteEconomicPolicy.setup();
        }
    }

    private void tearDownEverything() {
        if (mIsEnabled) {
            return;
        }
        synchronized (mLock) {
            mAgent.tearDownLocked();
            mCompleteEconomicPolicy.tearDown();
            mHandler.post(() -> {
                // Never call out to AlarmManager with the lock held. This sits below AM.
                AlarmManager alarmManager = getContext().getSystemService(AlarmManager.class);
                if (alarmManager != null) {
                    alarmManager.cancel(mUnusedWealthReclamationListener);
                }
            });
            mPkgCache.clear();
            mScribe.tearDownLocked();
            mUidToPackageCache.clear();
            getContext().unregisterReceiver(mBroadcastReceiver);
            UsageStatsManagerInternal usmi =
                    LocalServices.getService(UsageStatsManagerInternal.class);
            usmi.unregisterListener(mSurveillanceAgent);
        }
        synchronized (mPackageToUidCache) {
            mPackageToUidCache.clear();
        }
    }

    private final class IrsHandler extends Handler {
        IrsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_MAYBE_FORCE_RECLAIM: {
                    removeMessages(MSG_MAYBE_FORCE_RECLAIM);
                    synchronized (mLock) {
                        maybeForceReclaimLocked();
                    }
                }
                break;

                case MSG_NOTIFY_AFFORDABILITY_CHANGE_LISTENER: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final int userId = args.argi1;
                    final String pkgName = (String) args.arg1;
                    final Agent.ActionAffordabilityNote affordabilityNote =
                            (Agent.ActionAffordabilityNote) args.arg2;

                    final EconomyManagerInternal.AffordabilityChangeListener listener =
                            affordabilityNote.getListener();
                    listener.onAffordabilityChanged(userId, pkgName,
                            affordabilityNote.getActionBill(),
                            affordabilityNote.isCurrentlyAffordable());

                    args.recycle();
                }
                break;

                case MSG_PROCESS_USAGE_EVENT: {
                    final int userId = msg.arg1;
                    final UsageEvents.Event event = (UsageEvents.Event) msg.obj;
                    synchronized (mLock) {
                        processUsageEventLocked(userId, event);
                    }
                }
                break;

                case MSG_SCHEDULE_UNUSED_WEALTH_RECLAMATION_EVENT: {
                    removeMessages(MSG_SCHEDULE_UNUSED_WEALTH_RECLAMATION_EVENT);
                    synchronized (mLock) {
                        scheduleUnusedWealthReclamationLocked();
                    }
                }
                break;
            }
        }
    }

    /**
     * Binder stub trampoline implementation
     */
    final class EconomyManagerStub extends IEconomyManager.Stub {
        /**
         * "dumpsys" infrastructure
         */
        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, pw)) return;

            if (!ArrayUtils.isEmpty(args)) {
                String arg = args[0];
                if ("-h".equals(arg) || "--help".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    // -a is passed when dumping a bug report so we have to acknowledge the
                    // argument. However, we currently don't do anything differently for bug
                    // reports.
                } else if (arg.length() > 0 && arg.charAt(0) == '-') {
                    pw.println("Unknown option: " + arg);
                    return;
                }
            }

            final long identityToken = Binder.clearCallingIdentity();
            try {
                dumpInternal(new IndentingPrintWriter(pw, "  "));
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
        }
    }

    private final class LocalService implements EconomyManagerInternal {
        /**
         * Use an extremely large value to indicate that an app can pay for a bill indefinitely.
         * The value set here should be large/long enough that there's no reasonable expectation
         * of a device operating uninterrupted (or in the exact same state) for that period of time.
         * We intentionally don't use Long.MAX_VALUE to avoid potential overflow if a client
         * doesn't check the value and just immediately adds it to the current time.
         */
        private static final long FOREVER_MS = 27 * 365 * 24 * HOUR_IN_MILLIS;

        @Override
        public void registerAffordabilityChangeListener(int userId, @NonNull String pkgName,
                @NonNull AffordabilityChangeListener listener, @NonNull ActionBill bill) {
            if (isSystem(userId, pkgName)) {
                // The system's affordability never changes.
                return;
            }
            synchronized (mLock) {
                mAgent.registerAffordabilityChangeListenerLocked(userId, pkgName, listener, bill);
            }
        }

        @Override
        public void unregisterAffordabilityChangeListener(int userId, @NonNull String pkgName,
                @NonNull AffordabilityChangeListener listener, @NonNull ActionBill bill) {
            if (isSystem(userId, pkgName)) {
                // The system's affordability never changes.
                return;
            }
            synchronized (mLock) {
                mAgent.unregisterAffordabilityChangeListenerLocked(userId, pkgName, listener, bill);
            }
        }

        @Override
        public boolean canPayFor(int userId, @NonNull String pkgName, @NonNull ActionBill bill) {
            if (!mIsEnabled) {
                return true;
            }
            if (isSystem(userId, pkgName)) {
                // The government, I mean the system, can create ARCs as it needs to in order to
                // operate.
                return true;
            }
            // TODO: take temp-allowlist into consideration
            long requiredBalance = 0;
            final List<EconomyManagerInternal.AnticipatedAction> projectedActions =
                    bill.getAnticipatedActions();
            synchronized (mLock) {
                for (int i = 0; i < projectedActions.size(); ++i) {
                    AnticipatedAction action = projectedActions.get(i);
                    final long cost = mCompleteEconomicPolicy.getCostOfAction(
                            action.actionId, userId, pkgName);
                    requiredBalance += cost * action.numInstantaneousCalls
                            + cost * (action.ongoingDurationMs / 1000);
                }
                return mAgent.getBalanceLocked(userId, pkgName) >= requiredBalance;
            }
        }

        @Override
        public long getMaxDurationMs(int userId, @NonNull String pkgName,
                @NonNull ActionBill bill) {
            if (!mIsEnabled) {
                return FOREVER_MS;
            }
            if (isSystem(userId, pkgName)) {
                return FOREVER_MS;
            }
            long totalCostPerSecond = 0;
            final List<EconomyManagerInternal.AnticipatedAction> projectedActions =
                    bill.getAnticipatedActions();
            synchronized (mLock) {
                for (int i = 0; i < projectedActions.size(); ++i) {
                    AnticipatedAction action = projectedActions.get(i);
                    final long cost = mCompleteEconomicPolicy.getCostOfAction(
                            action.actionId, userId, pkgName);
                    totalCostPerSecond += cost;
                }
                if (totalCostPerSecond == 0) {
                    return FOREVER_MS;
                }
                return mAgent.getBalanceLocked(userId, pkgName) * 1000 / totalCostPerSecond;
            }
        }

        @Override
        public void noteInstantaneousEvent(int userId, @NonNull String pkgName, int eventId,
                @Nullable String tag) {
            if (!mIsEnabled) {
                return;
            }
            synchronized (mLock) {
                mAgent.noteInstantaneousEventLocked(userId, pkgName, eventId, tag);
            }
        }

        @Override
        public void noteOngoingEventStarted(int userId, @NonNull String pkgName, int eventId,
                @Nullable String tag) {
            if (!mIsEnabled) {
                return;
            }
            synchronized (mLock) {
                final long nowElapsed = SystemClock.elapsedRealtime();
                mAgent.noteOngoingEventLocked(userId, pkgName, eventId, tag, nowElapsed);
            }
        }

        @Override
        public void noteOngoingEventStopped(int userId, @NonNull String pkgName, int eventId,
                @Nullable String tag) {
            if (!mIsEnabled) {
                return;
            }
            final long nowElapsed = SystemClock.elapsedRealtime();
            final long now = getCurrentTimeMillis();
            synchronized (mLock) {
                mAgent.stopOngoingActionLocked(userId, pkgName, eventId, tag, nowElapsed, now);
            }
        }
    }

    private class ConfigObserver extends ContentObserver {
        private final ContentResolver mContentResolver;

        ConfigObserver(Handler handler, Context context) {
            super(handler);
            mContentResolver = context.getContentResolver();
        }

        public void start() {
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ENABLE_TARE), false, this);
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(TARE_ALARM_MANAGER_CONSTANTS), false, this);
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(TARE_JOB_SCHEDULER_CONSTANTS), false, this);
            updateEnabledStatus();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.Global.getUriFor(Settings.Global.ENABLE_TARE))) {
                updateEnabledStatus();
            } else if (uri.equals(Settings.Global.getUriFor(TARE_ALARM_MANAGER_CONSTANTS))
                    || uri.equals(Settings.Global.getUriFor(TARE_JOB_SCHEDULER_CONSTANTS))) {
                updateEconomicPolicy();
            }
        }

        private void updateEnabledStatus() {
            final boolean isTareEnabled = Settings.Global.getInt(mContentResolver,
                    Settings.Global.ENABLE_TARE, Settings.Global.DEFAULT_ENABLE_TARE) == 1;
            if (mIsEnabled != isTareEnabled) {
                mIsEnabled = isTareEnabled;
                if (mIsEnabled) {
                    setupEverything();
                } else {
                    tearDownEverything();
                }
            }
        }

        private void updateEconomicPolicy() {
            synchronized (mLock) {
                mCompleteEconomicPolicy.tearDown();
                mCompleteEconomicPolicy = new CompleteEconomicPolicy(InternalResourceService.this);
                if (mIsEnabled && mBootPhase >= PHASE_SYSTEM_SERVICES_READY) {
                    mCompleteEconomicPolicy.setup();
                    mAgent.onPricingChangedLocked();
                }
            }
        }
    }

    private static void dumpHelp(PrintWriter pw) {
        pw.println("Resource Economy (economy) dump options:");
        pw.println("  [-h|--help] [package] ...");
        pw.println("    -h | --help: print this help");
        pw.println("  [package] is an optional package name to limit the output to.");
    }

    private void dumpInternal(final IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.print("Is enabled: ");
            pw.println(mIsEnabled);

            pw.print("Current battery level: ");
            pw.println(mCurrentBatteryLevel);

            final long maxCircluation = getMaxCirculationLocked();
            pw.print("Max circulation (current/satiated): ");
            pw.print(narcToString(maxCircluation));
            pw.print("/");
            pw.println(narcToString(mCompleteEconomicPolicy.getMaxSatiatedCirculation()));

            final long currentCirculation = mScribe.getNarcsInCirculationLocked();
            pw.print("Current GDP: ");
            pw.print(narcToString(currentCirculation));
            pw.print(" (");
            pw.print(String.format("%.2f", 100f * currentCirculation / maxCircluation));
            pw.println("% of current max)");

            pw.println();
            mCompleteEconomicPolicy.dump(pw);

            pw.println();
            mScribe.dumpLocked(pw);

            pw.println();
            mAgent.dumpLocked(pw);
        }
    }
}
