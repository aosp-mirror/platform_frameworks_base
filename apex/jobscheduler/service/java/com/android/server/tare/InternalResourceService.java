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
import static com.android.server.tare.TareUtils.cakeToString;
import static com.android.server.tare.TareUtils.getCurrentTimeMillis;

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
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
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
import com.android.server.tare.EconomicPolicy.Cost;
import com.android.server.tare.EconomyManagerInternal.TareStateChangeListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

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
    /**
     * The battery level above which we may consider quantitative easing (increasing the consumption
     * limit).
     */
    private static final int QUANTITATIVE_EASING_BATTERY_THRESHOLD = 50;
    private static final int PACKAGE_QUERY_FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.MATCH_APEX;

    /** Global lock for all resource economy state. */
    private final Object mLock = new Object();

    private final Handler mHandler;
    private final BatteryManagerInternal mBatteryManagerInternal;
    private final PackageManager mPackageManager;
    private final PackageManagerInternal mPackageManagerInternal;

    private IDeviceIdleController mDeviceIdleController;

    private final Agent mAgent;
    private final Analyst mAnalyst;
    private final ConfigObserver mConfigObserver;
    private final EconomyManagerStub mEconomyManagerStub;
    private final Scribe mScribe;

    @GuardedBy("mLock")
    private CompleteEconomicPolicy mCompleteEconomicPolicy;

    @NonNull
    @GuardedBy("mLock")
    private final List<PackageInfo> mPkgCache = new ArrayList<>();

    /** Cached mapping of UIDs (for all users) to a list of packages in the UID. */
    @GuardedBy("mLock")
    private final SparseSetArray<String> mUidToPackageCache = new SparseSetArray<>();

    /** Cached mapping of userId+package to their UIDs (for all users) */
    @GuardedBy("mPackageToUidCache")
    private final SparseArrayMap<String, Integer> mPackageToUidCache = new SparseArrayMap<>();

    private final CopyOnWriteArraySet<TareStateChangeListener> mStateChangeListeners =
            new CopyOnWriteArraySet<>();

    /** List of packages that are "exempted" from battery restrictions. */
    // TODO(144864180): include userID
    @GuardedBy("mLock")
    private ArraySet<String> mExemptedApps = new ArraySet<>();

    private volatile boolean mIsEnabled;
    private volatile int mBootPhase;
    private volatile boolean mExemptListLoaded;
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
                case PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED:
                    onExemptionListChanged();
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
    private static final int MSG_NOTIFY_STATE_CHANGE_LISTENERS = 3;
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
        mAnalyst = new Analyst();
        mScribe = new Scribe(this, mAnalyst);
        mCompleteEconomicPolicy = new CompleteEconomicPolicy(this);
        mAgent = new Agent(this, mScribe, mAnalyst);

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
            mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                    ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
            setupEverything();
        } else if (PHASE_BOOT_COMPLETED == phase) {
            if (!mExemptListLoaded) {
                synchronized (mLock) {
                    try {
                        mExemptedApps =
                                new ArraySet<>(mDeviceIdleController.getFullPowerWhitelist());
                    } catch (RemoteException e) {
                        // Shouldn't happen.
                        Slog.wtf(TAG, e);
                    }
                    mExemptListLoaded = true;
                }
            }
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
    long getConsumptionLimitLocked() {
        return mCurrentBatteryLevel * mScribe.getSatiatedConsumptionLimitLocked() / 100;
    }

    @GuardedBy("mLock")
    long getMinBalanceLocked(final int userId, @NonNull final String pkgName) {
        return mCurrentBatteryLevel * mCompleteEconomicPolicy.getMinSatiatedBalance(userId, pkgName)
                / 100;
    }

    @GuardedBy("mLock")
    long getInitialSatiatedConsumptionLimitLocked() {
        return mCompleteEconomicPolicy.getInitialSatiatedConsumptionLimit();
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

    boolean isPackageExempted(final int userId, @NonNull String pkgName) {
        synchronized (mLock) {
            return mExemptedApps.contains(pkgName);
        }
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
            mAnalyst.noteBatteryLevelChange(newBatteryLevel);
            final boolean increased = newBatteryLevel > mCurrentBatteryLevel;
            if (increased) {
                mAgent.distributeBasicIncomeLocked(newBatteryLevel);
            } else if (newBatteryLevel == mCurrentBatteryLevel) {
                // The broadcast is also sent when the plug type changes...
                return;
            }
            mCurrentBatteryLevel = newBatteryLevel;
            adjustCreditSupplyLocked(increased);
        }
    }

    void onDeviceStateChanged() {
        synchronized (mLock) {
            mAgent.onDeviceStateChangedLocked();
        }
    }

    void onExemptionListChanged() {
        final int[] userIds = LocalServices.getService(UserManagerInternal.class).getUserIds();
        synchronized (mLock) {
            final ArraySet<String> removed = mExemptedApps;
            final ArraySet<String> added = new ArraySet<>();
            try {
                mExemptedApps = new ArraySet<>(mDeviceIdleController.getFullPowerWhitelist());
            } catch (RemoteException e) {
                // Shouldn't happen.
                Slog.wtf(TAG, e);
                return;
            }

            for (int i = mExemptedApps.size() - 1; i >= 0; --i) {
                final String pkg = mExemptedApps.valueAt(i);
                if (!removed.contains(pkg)) {
                    added.add(pkg);
                }
                removed.remove(pkg);
            }
            for (int a = added.size() - 1; a >= 0; --a) {
                final String pkgName = added.valueAt(a);
                for (int userId : userIds) {
                    // Since the exemption list doesn't specify user ID and we track by user ID,
                    // we need to see if the app exists on the user before talking to the agent.
                    // Otherwise, we may end up with invalid ledgers.
                    final boolean appExists = getUid(userId, pkgName) >= 0;
                    if (appExists) {
                        mAgent.onAppExemptedLocked(userId, pkgName);
                    }
                }
            }
            for (int r = removed.size() - 1; r >= 0; --r) {
                final String pkgName = removed.valueAt(r);
                for (int userId : userIds) {
                    // Since the exemption list doesn't specify user ID and we track by user ID,
                    // we need to see if the app exists on the user before talking to the agent.
                    // Otherwise, we may end up with invalid ledgers.
                    final boolean appExists = getUid(userId, pkgName) >= 0;
                    if (appExists) {
                        mAgent.onAppUnexemptedLocked(userId, pkgName);
                    }
                }
            }
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

    /**
     * Try to increase the consumption limit if apps are reaching the current limit too quickly.
     */
    @GuardedBy("mLock")
    void maybePerformQuantitativeEasingLocked() {
        // We don't need to increase the limit if the device runs out of consumable credits
        // when the battery is low.
        final long remainingConsumableCakes = mScribe.getRemainingConsumableCakesLocked();
        if (mCurrentBatteryLevel <= QUANTITATIVE_EASING_BATTERY_THRESHOLD
                || remainingConsumableCakes > 0) {
            return;
        }
        final long currentConsumptionLimit = mScribe.getSatiatedConsumptionLimitLocked();
        final long shortfall = (mCurrentBatteryLevel - QUANTITATIVE_EASING_BATTERY_THRESHOLD)
                * currentConsumptionLimit / 100;
        final long newConsumptionLimit = Math.min(currentConsumptionLimit + shortfall,
                mCompleteEconomicPolicy.getHardSatiatedConsumptionLimit());
        if (newConsumptionLimit != currentConsumptionLimit) {
            Slog.i(TAG, "Increasing consumption limit from " + cakeToString(currentConsumptionLimit)
                    + " to " + cakeToString(newConsumptionLimit));
            mScribe.setConsumptionLimitLocked(newConsumptionLimit);
            adjustCreditSupplyLocked(/* allowIncrease */ true);
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
    private void adjustCreditSupplyLocked(boolean allowIncrease) {
        final long newLimit = getConsumptionLimitLocked();
        final long remainingConsumableCakes = mScribe.getRemainingConsumableCakesLocked();
        if (remainingConsumableCakes == newLimit) {
            return;
        }
        if (remainingConsumableCakes > newLimit) {
            mScribe.adjustRemainingConsumableCakesLocked(newLimit - remainingConsumableCakes);
        } else if (allowIncrease) {
            final double perc = mCurrentBatteryLevel / 100d;
            final long shortfall = newLimit - remainingConsumableCakes;
            mScribe.adjustRemainingConsumableCakesLocked((long) (perc * shortfall));
        }
        mAgent.onCreditSupplyChanged();
    }

    @GuardedBy("mLock")
    private void processUsageEventLocked(final int userId, @NonNull UsageEvents.Event event) {
        if (!mIsEnabled) {
            return;
        }
        final String pkgName = event.getPackageName();
        if (DEBUG) {
            Slog.d(TAG, "Processing event " + event.getEventType()
                    + " (" + event.mInstanceId + ")"
                    + " for " + appToString(userId, pkgName));
        }
        final long nowElapsed = SystemClock.elapsedRealtime();
        switch (event.getEventType()) {
            case UsageEvents.Event.ACTIVITY_RESUMED:
                mAgent.noteOngoingEventLocked(userId, pkgName,
                        EconomicPolicy.REWARD_TOP_ACTIVITY, String.valueOf(event.mInstanceId),
                        nowElapsed);
                break;
            case UsageEvents.Event.ACTIVITY_PAUSED:
            case UsageEvents.Event.ACTIVITY_STOPPED:
            case UsageEvents.Event.ACTIVITY_DESTROYED:
                final long now = getCurrentTimeMillis();
                mAgent.stopOngoingActionLocked(userId, pkgName,
                        EconomicPolicy.REWARD_TOP_ACTIVITY, String.valueOf(event.mInstanceId),
                        nowElapsed, now);
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

    private void registerListeners() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_LEVEL_CHANGED);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
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
            if (mBootPhase >= PHASE_BOOT_COMPLETED && !mExemptListLoaded) {
                try {
                    mExemptedApps = new ArraySet<>(mDeviceIdleController.getFullPowerWhitelist());
                } catch (RemoteException e) {
                    // Shouldn't happen.
                    Slog.wtf(TAG, e);
                }
                mExemptListLoaded = true;
            }
            final boolean isFirstSetup = !mScribe.recordExists();
            if (isFirstSetup) {
                mAgent.grantBirthrightsLocked();
                mScribe.setConsumptionLimitLocked(
                        mCompleteEconomicPolicy.getInitialSatiatedConsumptionLimit());
            } else {
                mScribe.loadFromDiskLocked();
                if (mScribe.getSatiatedConsumptionLimitLocked()
                        < mCompleteEconomicPolicy.getInitialSatiatedConsumptionLimit()
                        || mScribe.getSatiatedConsumptionLimitLocked()
                        > mCompleteEconomicPolicy.getHardSatiatedConsumptionLimit()) {
                    // Reset the consumption limit since several factors may have changed.
                    mScribe.setConsumptionLimitLocked(
                            mCompleteEconomicPolicy.getInitialSatiatedConsumptionLimit());
                }
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
            mCompleteEconomicPolicy.setup(mConfigObserver.getAllDeviceConfigProperties());
        }
    }

    private void tearDownEverything() {
        if (mIsEnabled) {
            return;
        }
        synchronized (mLock) {
            mAgent.tearDownLocked();
            mAnalyst.tearDown();
            mCompleteEconomicPolicy.tearDown();
            mExemptedApps.clear();
            mExemptListLoaded = false;
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

                case MSG_NOTIFY_STATE_CHANGE_LISTENERS: {
                    for (TareStateChangeListener listener : mStateChangeListeners) {
                        listener.onTareEnabledStateChanged(mIsEnabled);
                    }
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

            boolean dumpAll = true;
            if (!ArrayUtils.isEmpty(args)) {
                String arg = args[0];
                if ("-h".equals(arg) || "--help".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    // -a is passed when dumping a bug report. Bug reports have a time limit for
                    // each service dump, so we can't dump everything.
                    dumpAll = false;
                } else if (arg.length() > 0 && arg.charAt(0) == '-') {
                    pw.println("Unknown option: " + arg);
                    return;
                }
            }

            final long identityToken = Binder.clearCallingIdentity();
            try {
                dumpInternal(new IndentingPrintWriter(pw, "  "), dumpAll);
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
        public void registerTareStateChangeListener(@NonNull TareStateChangeListener listener) {
            mStateChangeListeners.add(listener);
        }

        @Override
        public void unregisterTareStateChangeListener(@NonNull TareStateChangeListener listener) {
            mStateChangeListeners.remove(listener);
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
                    final Cost cost = mCompleteEconomicPolicy.getCostOfAction(
                            action.actionId, userId, pkgName);
                    requiredBalance += cost.price * action.numInstantaneousCalls
                            + cost.price * (action.ongoingDurationMs / 1000);
                }
                return mAgent.getBalanceLocked(userId, pkgName) >= requiredBalance
                        && mScribe.getRemainingConsumableCakesLocked() >= requiredBalance;
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
                    final Cost cost = mCompleteEconomicPolicy.getCostOfAction(
                            action.actionId, userId, pkgName);
                    totalCostPerSecond += cost.price;
                }
                if (totalCostPerSecond == 0) {
                    return FOREVER_MS;
                }
                final long minBalance = Math.min(
                        mAgent.getBalanceLocked(userId, pkgName),
                        mScribe.getRemainingConsumableCakesLocked());
                return minBalance * 1000 / totalCostPerSecond;
            }
        }

        @Override
        public boolean isEnabled() {
            return mIsEnabled;
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

    private class ConfigObserver extends ContentObserver
            implements DeviceConfig.OnPropertiesChangedListener {
        private static final String KEY_DC_ENABLE_TARE = "enable_tare";

        private final ContentResolver mContentResolver;

        ConfigObserver(Handler handler, Context context) {
            super(handler);
            mContentResolver = context.getContentResolver();
        }

        public void start() {
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_TARE,
                    TareHandlerThread.getExecutor(), this);
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ENABLE_TARE), false, this);
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(TARE_ALARM_MANAGER_CONSTANTS), false, this);
            mContentResolver.registerContentObserver(
                    Settings.Global.getUriFor(TARE_JOB_SCHEDULER_CONSTANTS), false, this);
            onPropertiesChanged(getAllDeviceConfigProperties());
            updateEnabledStatus();
        }

        @NonNull
        DeviceConfig.Properties getAllDeviceConfigProperties() {
            // Don't want to cache the Properties object locally in case it ends up being large,
            // especially since it'll only be used once/infrequently (during setup or on a change).
            return DeviceConfig.getProperties(DeviceConfig.NAMESPACE_TARE);
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

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            boolean economicPolicyUpdated = false;
            synchronized (mLock) {
                for (String name : properties.getKeyset()) {
                    if (name == null) {
                        continue;
                    }
                    switch (name) {
                        case KEY_DC_ENABLE_TARE:
                            updateEnabledStatus();
                            break;
                        default:
                            if (!economicPolicyUpdated
                                    && (name.startsWith("am") || name.startsWith("js"))) {
                                updateEconomicPolicy();
                                economicPolicyUpdated = true;
                            }
                    }
                }
            }
        }

        private void updateEnabledStatus() {
            // User setting should override DeviceConfig setting.
            // NOTE: There's currently no way for a user to reset the value (via UI), so if a user
            // manually toggles TARE via UI, we'll always defer to the user's current setting
            // TODO: add a "reset" value if the user toggle is an issue
            final boolean isTareEnabledDC = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_TARE,
                    KEY_DC_ENABLE_TARE, Settings.Global.DEFAULT_ENABLE_TARE == 1);
            final boolean isTareEnabled = Settings.Global.getInt(mContentResolver,
                    Settings.Global.ENABLE_TARE, isTareEnabledDC ? 1 : 0) == 1;
            if (mIsEnabled != isTareEnabled) {
                mIsEnabled = isTareEnabled;
                if (mIsEnabled) {
                    setupEverything();
                } else {
                    tearDownEverything();
                }
                mHandler.sendEmptyMessage(MSG_NOTIFY_STATE_CHANGE_LISTENERS);
            }
        }

        private void updateEconomicPolicy() {
            synchronized (mLock) {
                final long initialLimit =
                        mCompleteEconomicPolicy.getInitialSatiatedConsumptionLimit();
                final long hardLimit = mCompleteEconomicPolicy.getHardSatiatedConsumptionLimit();
                mCompleteEconomicPolicy.tearDown();
                mCompleteEconomicPolicy = new CompleteEconomicPolicy(InternalResourceService.this);
                if (mIsEnabled && mBootPhase >= PHASE_SYSTEM_SERVICES_READY) {
                    mCompleteEconomicPolicy.setup(getAllDeviceConfigProperties());
                    if (initialLimit != mCompleteEconomicPolicy.getInitialSatiatedConsumptionLimit()
                            || hardLimit
                            != mCompleteEconomicPolicy.getHardSatiatedConsumptionLimit()) {
                        // Reset the consumption limit since several factors may have changed.
                        mScribe.setConsumptionLimitLocked(
                                mCompleteEconomicPolicy.getInitialSatiatedConsumptionLimit());
                    }
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

    private void dumpInternal(final IndentingPrintWriter pw, final boolean dumpAll) {
        synchronized (mLock) {
            pw.print("Is enabled: ");
            pw.println(mIsEnabled);

            pw.print("Current battery level: ");
            pw.println(mCurrentBatteryLevel);

            final long consumptionLimit = getConsumptionLimitLocked();
            pw.print("Consumption limit (current/initial-satiated/current-satiated): ");
            pw.print(cakeToString(consumptionLimit));
            pw.print("/");
            pw.print(cakeToString(mCompleteEconomicPolicy.getInitialSatiatedConsumptionLimit()));
            pw.print("/");
            pw.println(cakeToString(mScribe.getSatiatedConsumptionLimitLocked()));

            final long remainingConsumable = mScribe.getRemainingConsumableCakesLocked();
            pw.print("Goods remaining: ");
            pw.print(cakeToString(remainingConsumable));
            pw.print(" (");
            pw.print(String.format("%.2f", 100f * remainingConsumable / consumptionLimit));
            pw.println("% of current limit)");

            pw.print("Device wealth: ");
            pw.println(cakeToString(mScribe.getCakesInCirculationForLoggingLocked()));

            pw.println();
            pw.print("Exempted apps", mExemptedApps);
            pw.println();

            pw.println();
            mCompleteEconomicPolicy.dump(pw);

            pw.println();
            mScribe.dumpLocked(pw, dumpAll);

            pw.println();
            mAgent.dumpLocked(pw);

            pw.println();
            mAnalyst.dump(pw);
        }
    }
}
