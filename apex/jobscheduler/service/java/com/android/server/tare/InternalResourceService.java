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

import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManagerInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseSetArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.tare.EconomyManagerInternal.BalanceChangeListener;

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

    /** Global local for all resource economy state. */
    private final Object mLock = new Object();

    private final Handler mHandler;
    private final BatteryManagerInternal mBatteryManagerInternal;
    private final PackageManager mPackageManager;

    private final CompleteEconomicPolicy mCompleteEconomicPolicy;
    private final Agent mAgent;

    private final CopyOnWriteArraySet<BalanceChangeListener> mBalanceChangeListeners =
            new CopyOnWriteArraySet<>();

    @NonNull
    @GuardedBy("mLock")
    private List<PackageInfo> mPkgCache = new ArrayList<>();

    /** Cached mapping of UIDs (for all users) to a list of packages in the UID. */
    @GuardedBy("mLock")
    private final SparseSetArray<String> mUidToPackageCache = new SparseSetArray<>();

    @GuardedBy("mLock")
    private boolean mIsSetup;
    // In the range [0,100] to represent 0% to 100% battery.
    @GuardedBy("mLock")
    private int mCurrentBatteryLevel;
    // TODO: load from disk
    @GuardedBy("mLock")
    private long mLastUnusedReclamationTime;

    @SuppressWarnings("FieldCanBeLocal")
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

    private final AlarmManager.OnAlarmListener mUnusedWealthReclamationListener =
            new AlarmManager.OnAlarmListener() {
                @Override
                public void onAlarm() {
                    synchronized (mLock) {
                        mAgent.reclaimUnusedAssetsLocked(DEFAULT_UNUSED_RECLAMATION_PERCENTAGE);
                        mLastUnusedReclamationTime = System.currentTimeMillis();
                        scheduleUnusedWealthReclamationLocked();
                    }
                }
            };

    private static final int MSG_NOTIFY_BALANCE_CHANGE_LISTENERS = 0;
    private static final int MSG_SCHEDULE_UNUSED_WEALTH_RECLAMATION_EVENT = 1;
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
        mCompleteEconomicPolicy = new CompleteEconomicPolicy(this);
        mAgent = new Agent(this, mCompleteEconomicPolicy);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_LEVEL_CHANGED);
        context.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);
        final IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        pkgFilter.addDataScheme("package");
        context.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, pkgFilter, null, null);
        final IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        context.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, userFilter, null, null);

        publishLocalService(EconomyManagerInternal.class, new LocalService());
    }

    @Override
    public void onStart() {

    }

    @Override
    public void onBootPhase(int phase) {
        if (PHASE_SYSTEM_SERVICES_READY == phase) {
            synchronized (mLock) {
                mCurrentBatteryLevel = getCurrentBatteryLevel();
                // TODO: base on if we have anything persisted
                final boolean isFirstSetup = true;
                if (isFirstSetup) {
                    mHandler.post(this::setupEconomy);
                } else {
                    mIsSetup = true;
                }
                scheduleUnusedWealthReclamationLocked();
                mCompleteEconomicPolicy.onSystemServicesReady();
            }
        }
    }

    @NonNull
    Object getLock() {
        return mLock;
    }

    @NonNull
    List<PackageInfo> getInstalledPackages() {
        synchronized (mLock) {
            return mPkgCache;
        }
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

    @Nullable
    @GuardedBy("mLock")
    ArraySet<String> getPackagesForUidLocked(final int uid) {
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

    void onBatteryLevelChanged() {
        synchronized (mLock) {
            final int newBatteryLevel = getCurrentBatteryLevel();
            if (newBatteryLevel > mCurrentBatteryLevel) {
                mAgent.distributeBasicIncomeLocked(newBatteryLevel);
            }
            mCurrentBatteryLevel = newBatteryLevel;
        }
    }

    void onDeviceStateChanged() {
    }

    void onPackageAdded(final int uid, @NonNull final String pkgName) {
        final int userId = UserHandle.getUserId(uid);
        final PackageInfo packageInfo;
        try {
            packageInfo = mPackageManager.getPackageInfoAsUser(pkgName, 0, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.wtf(TAG, "PM couldn't find newly added package: " + pkgName);
            return;
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
    }

    void onUserAdded(final int userId) {
        synchronized (mLock) {
            loadInstalledPackageListLocked();
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
            loadInstalledPackageListLocked();
            mAgent.onUserRemovedLocked(userId, removedPkgs);
        }
    }

    void postSolvencyChanged(final int userId, @NonNull final String pkgName, boolean nowSolvent) {
        mHandler.obtainMessage(
                MSG_NOTIFY_BALANCE_CHANGE_LISTENERS, userId, nowSolvent ? 1 : 0, pkgName)
                .sendToTarget();
    }

    @GuardedBy("mLock")
    private void scheduleUnusedWealthReclamationLocked() {
        final long now = System.currentTimeMillis();
        final long nextReclamationTime =
                Math.max(mLastUnusedReclamationTime + UNUSED_RECLAMATION_PERIOD_MS, now + 30_000);
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
                        MSG_SCHEDULE_UNUSED_WEALTH_RECLAMATION_EVENT, 30_000);
            }
        });
    }

    private int getCurrentBatteryLevel() {
        return mBatteryManagerInternal.getBatteryLevel();
    }

    @GuardedBy("mLock")
    private void loadInstalledPackageListLocked() {
        mPkgCache = mPackageManager.getInstalledPackages(0);
    }

    private void setupEconomy() {
        synchronized (mLock) {
            loadInstalledPackageListLocked();
            mAgent.grantBirthrightsLocked();
            mIsSetup = true;
        }
    }

    private final class IrsHandler extends Handler {
        IrsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_NOTIFY_BALANCE_CHANGE_LISTENERS:
                    final int userId = msg.arg1;
                    final String pkgName = (String) msg.obj;
                    final boolean nowSolvent = msg.arg2 == 1;
                    for (BalanceChangeListener listener : mBalanceChangeListeners) {
                        if (nowSolvent) {
                            listener.onSolvent(userId, pkgName);
                        } else {
                            listener.onBankruptcy(userId, pkgName);
                        }
                    }
                    break;

                case MSG_SCHEDULE_UNUSED_WEALTH_RECLAMATION_EVENT:
                    removeMessages(MSG_SCHEDULE_UNUSED_WEALTH_RECLAMATION_EVENT);
                    synchronized (mLock) {
                        scheduleUnusedWealthReclamationLocked();
                    }
                    break;
            }
        }
    }

    // TODO: implement
    private final class LocalService implements EconomyManagerInternal {

        @Override
        public void registerBalanceChangeListener(@NonNull BalanceChangeListener listener) {
            mBalanceChangeListeners.add(listener);
        }

        @Override
        public void unregisterBalanceChangeListener(@NonNull BalanceChangeListener listener) {
            mBalanceChangeListeners.remove(listener);
        }

        @Override
        public boolean hasBalanceAtLeast(int userId, @NonNull String pkgName, int minBalance) {
            return false;
        }

        @Override
        public void noteInstantaneousEvent(int userId, @NonNull String pkgName, int eventId,
                @Nullable String tag) {
            synchronized (mLock) {
                mAgent.noteInstantaneousEventLocked(userId, pkgName, eventId, tag);
            }
        }

        @Override
        public void noteOngoingEventStarted(int userId, @NonNull String pkgName, int eventId,
                @Nullable String tag) {
        }

        @Override
        public void noteOngoingEventStopped(int userId, @NonNull String pkgName, int eventId,
                @Nullable String tag) {
        }
    }
}
