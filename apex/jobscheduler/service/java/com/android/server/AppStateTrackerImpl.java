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
package com.android.server;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.AppOpsManager.PackageOps;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseSetArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.StatLogger;
import com.android.server.AppStateTrackerProto.ExemptedPackage;
import com.android.server.AppStateTrackerProto.RunAnyInBackgroundRestrictedPackages;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Class to keep track of the information related to "force app standby", which includes:
 * - OP_RUN_ANY_IN_BACKGROUND for each package
 * - UID foreground/active state
 * - User+system power save exemption list
 * - Temporary power save exemption list
 * - Global "force all apps standby" mode enforced by battery saver.
 *
 * Test: atest com.android.server.AppStateTrackerTest
 */
public class AppStateTrackerImpl implements AppStateTracker {
    private static final boolean DEBUG = false;

    private final Object mLock = new Object();
    private final Context mContext;

    @VisibleForTesting
    static final int TARGET_OP = AppOpsManager.OP_RUN_ANY_IN_BACKGROUND;

    IActivityManager mIActivityManager;
    ActivityManagerInternal mActivityManagerInternal;
    AppOpsManager mAppOpsManager;
    IAppOpsService mAppOpsService;
    PowerManagerInternal mPowerManagerInternal;
    StandbyTracker mStandbyTracker;
    AppStandbyInternal mAppStandbyInternal;

    private final MyHandler mHandler;

    @VisibleForTesting
    FeatureFlagsObserver mFlagsObserver;

    /**
     * Pair of (uid (not user-id), packageName) with OP_RUN_ANY_IN_BACKGROUND *not* allowed.
     */
    @GuardedBy("mLock")
    final ArraySet<Pair<Integer, String>> mRunAnyRestrictedPackages = new ArraySet<>();

    /** UIDs that are active. */
    @GuardedBy("mLock")
    final SparseBooleanArray mActiveUids = new SparseBooleanArray();

    /**
     * System except-idle + user exemption list in the device idle controller.
     */
    @GuardedBy("mLock")
    private int[] mPowerExemptAllAppIds = new int[0];

    /**
     * User exempted apps in the device idle controller.
     */
    @GuardedBy("mLock")
    private int[] mPowerExemptUserAppIds = new int[0];

    @GuardedBy("mLock")
    private int[] mTempExemptAppIds = mPowerExemptAllAppIds;

    /**
     * Per-user packages that are in the EXEMPTED bucket.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    final SparseSetArray<String> mExemptedBucketPackages = new SparseSetArray<>();

    @GuardedBy("mLock")
    final ArraySet<Listener> mListeners = new ArraySet<>();

    @GuardedBy("mLock")
    boolean mStarted;

    /**
     * Only used for small battery use-case.
     */
    @GuardedBy("mLock")
    boolean mIsPluggedIn;

    @GuardedBy("mLock")
    boolean mBatterySaverEnabled;

    /**
     * True if the forced app standby is currently enabled
     */
    @GuardedBy("mLock")
    boolean mForceAllAppsStandby;

    /**
     * True if the forced app standby for small battery devices feature is enabled in settings
     */
    @GuardedBy("mLock")
    boolean mForceAllAppStandbyForSmallBattery;

    /**
     * True if the forced app standby feature is enabled in settings
     */
    @GuardedBy("mLock")
    boolean mForcedAppStandbyEnabled;

    @Override
    public void addServiceStateListener(@NonNull ServiceStateListener listener) {
        addListener(new Listener() {
            @Override
            public void stopForegroundServicesForUidPackage(int uid, String packageName) {
                listener.stopForegroundServicesForUidPackage(uid, packageName);
            }
        });
    }

    interface Stats {
        int UID_FG_STATE_CHANGED = 0;
        int UID_ACTIVE_STATE_CHANGED = 1;
        int RUN_ANY_CHANGED = 2;
        int ALL_UNEXEMPTED = 3;
        int ALL_EXEMPTION_LIST_CHANGED = 4;
        int TEMP_EXEMPTION_LIST_CHANGED = 5;
        int EXEMPTED_BUCKET_CHANGED = 6;
        int FORCE_ALL_CHANGED = 7;
        int FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED = 8;

        int IS_UID_ACTIVE_CACHED = 9;
        int IS_UID_ACTIVE_RAW = 10;
    }

    private final StatLogger mStatLogger = new StatLogger(new String[] {
            "UID_FG_STATE_CHANGED",
            "UID_ACTIVE_STATE_CHANGED",
            "RUN_ANY_CHANGED",
            "ALL_UNEXEMPTED",
            "ALL_EXEMPTION_LIST_CHANGED",
            "TEMP_EXEMPTION_LIST_CHANGED",
            "EXEMPTED_BUCKET_CHANGED",
            "FORCE_ALL_CHANGED",
            "FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED",

            "IS_UID_ACTIVE_CACHED",
            "IS_UID_ACTIVE_RAW",
    });

    @VisibleForTesting
    class FeatureFlagsObserver extends ContentObserver {
        FeatureFlagsObserver() {
            super(null);
        }

        void register() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.FORCED_APP_STANDBY_ENABLED),
                    false, this);

            mContext.getContentResolver().registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED), false, this);
        }

        boolean isForcedAppStandbyEnabled() {
            return injectGetGlobalSettingInt(Settings.Global.FORCED_APP_STANDBY_ENABLED, 1) == 1;
        }

        boolean isForcedAppStandbyForSmallBatteryEnabled() {
            return injectGetGlobalSettingInt(
                    Settings.Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED, 0) == 1;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (Settings.Global.getUriFor(Settings.Global.FORCED_APP_STANDBY_ENABLED).equals(uri)) {
                final boolean enabled = isForcedAppStandbyEnabled();
                synchronized (mLock) {
                    if (mForcedAppStandbyEnabled == enabled) {
                        return;
                    }
                    mForcedAppStandbyEnabled = enabled;
                    if (DEBUG) {
                        Slog.d(TAG, "Forced app standby feature flag changed: "
                                + mForcedAppStandbyEnabled);
                    }
                }
                mHandler.notifyForcedAppStandbyFeatureFlagChanged();
            } else if (Settings.Global.getUriFor(
                    Settings.Global.FORCED_APP_STANDBY_FOR_SMALL_BATTERY_ENABLED).equals(uri)) {
                final boolean enabled = isForcedAppStandbyForSmallBatteryEnabled();
                synchronized (mLock) {
                    if (mForceAllAppStandbyForSmallBattery == enabled) {
                        return;
                    }
                    mForceAllAppStandbyForSmallBattery = enabled;
                    if (DEBUG) {
                        Slog.d(TAG, "Forced app standby for small battery feature flag changed: "
                                + mForceAllAppStandbyForSmallBattery);
                    }
                    updateForceAllAppStandbyState();
                }
            } else {
                Slog.w(TAG, "Unexpected feature flag uri encountered: " + uri);
            }
        }
    }

    /**
     * Listener for any state changes that affect any app's eligibility to run.
     */
    public abstract static class Listener {
        /**
         * This is called when the OP_RUN_ANY_IN_BACKGROUND appops changed for a package.
         */
        private void onRunAnyAppOpsChanged(AppStateTrackerImpl sender,
                int uid, @NonNull String packageName) {
            updateJobsForUidPackage(uid, packageName, sender.isUidActive(uid));

            if (!sender.areAlarmsRestricted(uid, packageName)) {
                unblockAlarmsForUidPackage(uid, packageName);
            }

            if (!sender.isRunAnyInBackgroundAppOpsAllowed(uid, packageName)) {
                Slog.v(TAG, "Package " + packageName + "/" + uid
                        + " toggled into fg service restriction");
                stopForegroundServicesForUidPackage(uid, packageName);
            }
        }

        /**
         * This is called when the active/idle state changed for a UID.
         */
        private void onUidActiveStateChanged(AppStateTrackerImpl sender, int uid) {
            final boolean isActive = sender.isUidActive(uid);

            updateJobsForUid(uid, isActive);
            updateAlarmsForUid(uid);

            if (isActive) {
                unblockAlarmsForUid(uid);
            }
        }

        /**
         * This is called when an app-id(s) is removed from the power save allow-list.
         */
        private void onPowerSaveUnexempted(AppStateTrackerImpl sender) {
            updateAllJobs();
            updateAllAlarms();
        }

        /**
         * This is called when the power save exemption list changes, excluding the
         * {@link #onPowerSaveUnexempted} case.
         */
        private void onPowerSaveExemptionListChanged(AppStateTrackerImpl sender) {
            updateAllJobs();
            updateAllAlarms();
            unblockAllUnrestrictedAlarms();
        }

        /**
         * This is called when the temp exemption list changes.
         */
        private void onTempPowerSaveExemptionListChanged(AppStateTrackerImpl sender) {

            // TODO This case happens rather frequently; consider optimizing and update jobs
            // only for affected app-ids.

            updateAllJobs();

            // Note when an app is just put in the temp exemption list, we do *not* drain pending
            // alarms.
        }

        /**
         * This is called when the EXEMPTED bucket is updated.
         */
        private void onExemptedBucketChanged(AppStateTrackerImpl sender) {
            // This doesn't happen very often, so just re-evaluate all jobs / alarms.
            updateAllJobs();
            updateAllAlarms();
        }

        /**
         * This is called when the global "force all apps standby" flag changes.
         */
        private void onForceAllAppsStandbyChanged(AppStateTrackerImpl sender) {
            updateAllJobs();
            updateAllAlarms();
        }

        /**
         * Called when the job restrictions for multiple UIDs might have changed, so the job
         * scheduler should re-evaluate all restrictions for all jobs.
         */
        public void updateAllJobs() {
        }

        /**
         * Called when the job restrictions for a UID might have changed, so the job
         * scheduler should re-evaluate all restrictions for all jobs.
         */
        public void updateJobsForUid(int uid, boolean isNowActive) {
        }

        /**
         * Called when the job restrictions for a UID - package might have changed, so the job
         * scheduler should re-evaluate all restrictions for all jobs.
         */
        public void updateJobsForUidPackage(int uid, String packageName, boolean isNowActive) {
        }

        /**
         * Called when an app goes into forced app standby and its foreground
         * services need to be removed from that state.
         */
        public void stopForegroundServicesForUidPackage(int uid, String packageName) {
        }

        /**
         * Called when all alarms need to be re-evaluated for eligibility based on
         * {@link #areAlarmsRestrictedByBatterySaver}.
         */
        public void updateAllAlarms() {
        }

        /**
         * Called when the given uid state changes to active / idle.
         */
        public void updateAlarmsForUid(int uid) {
        }

        /**
         * Called when the job restrictions for multiple UIDs might have changed, so the alarm
         * manager should re-evaluate all restrictions for all blocked jobs.
         */
        public void unblockAllUnrestrictedAlarms() {
        }

        /**
         * Called when all jobs for a specific UID are unblocked.
         */
        public void unblockAlarmsForUid(int uid) {
        }

        /**
         * Called when all alarms for a specific UID - package are unblocked.
         */
        public void unblockAlarmsForUidPackage(int uid, String packageName) {
        }

        /**
         * Called when an ephemeral uid goes to the background, so its alarms need to be removed.
         */
        public void removeAlarmsForUid(int uid) {
        }
    }

    public AppStateTrackerImpl(Context context, Looper looper) {
        mContext = context;
        mHandler = new MyHandler(looper);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            switch (intent.getAction()) {
                case Intent.ACTION_USER_REMOVED:
                    if (userId > 0) {
                        mHandler.doUserRemoved(userId);
                    }
                    break;
                case Intent.ACTION_BATTERY_CHANGED:
                    synchronized (mLock) {
                        mIsPluggedIn = (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
                    }
                    updateForceAllAppStandbyState();
                    break;
                case Intent.ACTION_PACKAGE_REMOVED:
                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        final String pkgName = intent.getData().getSchemeSpecificPart();
                        final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                        // No need to notify for state change as all the alarms and jobs should be
                        // removed too.
                        mExemptedBucketPackages.remove(userId, pkgName);
                        mRunAnyRestrictedPackages.remove(Pair.create(uid, pkgName));
                        mActiveUids.delete(uid);
                    }
                    break;
            }
        }
    };

    /**
     * Call it when the system is ready.
     */
    public void onSystemServicesReady() {
        synchronized (mLock) {
            if (mStarted) {
                return;
            }
            mStarted = true;

            mIActivityManager = Objects.requireNonNull(injectIActivityManager());
            mActivityManagerInternal = Objects.requireNonNull(injectActivityManagerInternal());
            mAppOpsManager = Objects.requireNonNull(injectAppOpsManager());
            mAppOpsService = Objects.requireNonNull(injectIAppOpsService());
            mPowerManagerInternal = Objects.requireNonNull(injectPowerManagerInternal());
            mAppStandbyInternal = Objects.requireNonNull(injectAppStandbyInternal());

            mFlagsObserver = new FeatureFlagsObserver();
            mFlagsObserver.register();
            mForcedAppStandbyEnabled = mFlagsObserver.isForcedAppStandbyEnabled();
            mForceAllAppStandbyForSmallBattery =
                    mFlagsObserver.isForcedAppStandbyForSmallBatteryEnabled();
            mStandbyTracker = new StandbyTracker();
            mAppStandbyInternal.addListener(mStandbyTracker);

            try {
                mIActivityManager.registerUidObserver(new UidObserver(),
                        ActivityManager.UID_OBSERVER_GONE
                                | ActivityManager.UID_OBSERVER_IDLE
                                | ActivityManager.UID_OBSERVER_ACTIVE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, null);
                mAppOpsService.startWatchingMode(TARGET_OP, null,
                        new AppOpsWatcher());
            } catch (RemoteException e) {
                // shouldn't happen.
            }

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_REMOVED);
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            mContext.registerReceiver(mReceiver, filter);

            filter = new IntentFilter(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme(IntentFilter.SCHEME_PACKAGE);
            mContext.registerReceiver(mReceiver, filter);

            refreshForcedAppStandbyUidPackagesLocked();

            mPowerManagerInternal.registerLowPowerModeObserver(
                    ServiceType.FORCE_ALL_APPS_STANDBY,
                    (state) -> {
                        synchronized (mLock) {
                            mBatterySaverEnabled = state.batterySaverEnabled;
                            updateForceAllAppStandbyState();
                        }
                    });

            mBatterySaverEnabled = mPowerManagerInternal.getLowPowerState(
                    ServiceType.FORCE_ALL_APPS_STANDBY).batterySaverEnabled;

            updateForceAllAppStandbyState();
        }
    }

    @VisibleForTesting
    AppOpsManager injectAppOpsManager() {
        return mContext.getSystemService(AppOpsManager.class);
    }

    @VisibleForTesting
    IAppOpsService injectIAppOpsService() {
        return IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
    }

    @VisibleForTesting
    IActivityManager injectIActivityManager() {
        return ActivityManager.getService();
    }

    @VisibleForTesting
    ActivityManagerInternal injectActivityManagerInternal() {
        return LocalServices.getService(ActivityManagerInternal.class);
    }

    @VisibleForTesting
    PowerManagerInternal injectPowerManagerInternal() {
        return LocalServices.getService(PowerManagerInternal.class);
    }

    @VisibleForTesting
    AppStandbyInternal injectAppStandbyInternal() {
        return LocalServices.getService(AppStandbyInternal.class);
    }

    @VisibleForTesting
    boolean isSmallBatteryDevice() {
        return ActivityManager.isSmallBatteryDevice();
    }

    @VisibleForTesting
    int injectGetGlobalSettingInt(String key, int def) {
        return Settings.Global.getInt(mContext.getContentResolver(), key, def);
    }

    /**
     * Update {@link #mRunAnyRestrictedPackages} with the current app ops state.
     */
    @GuardedBy("mLock")
    private void refreshForcedAppStandbyUidPackagesLocked() {
        mRunAnyRestrictedPackages.clear();
        final List<PackageOps> ops = mAppOpsManager.getPackagesForOps(
                new int[] {TARGET_OP});

        if (ops == null) {
            return;
        }
        final int size = ops.size();
        for (int i = 0; i < size; i++) {
            final AppOpsManager.PackageOps pkg = ops.get(i);
            final List<AppOpsManager.OpEntry> entries = ops.get(i).getOps();

            for (int j = 0; j < entries.size(); j++) {
                AppOpsManager.OpEntry ent = entries.get(j);
                if (ent.getOp() != TARGET_OP) {
                    continue;
                }
                if (ent.getMode() != AppOpsManager.MODE_ALLOWED) {
                    mRunAnyRestrictedPackages.add(Pair.create(
                            pkg.getUid(), pkg.getPackageName()));
                }
            }
        }
    }

    private void updateForceAllAppStandbyState() {
        synchronized (mLock) {
            if (mForceAllAppStandbyForSmallBattery && isSmallBatteryDevice()) {
                toggleForceAllAppsStandbyLocked(!mIsPluggedIn);
            } else {
                toggleForceAllAppsStandbyLocked(mBatterySaverEnabled);
            }
        }
    }

    /**
     * Update {@link #mForceAllAppsStandby} and notifies the listeners.
     */
    @GuardedBy("mLock")
    private void toggleForceAllAppsStandbyLocked(boolean enable) {
        if (enable == mForceAllAppsStandby) {
            return;
        }
        mForceAllAppsStandby = enable;

        mHandler.notifyForceAllAppsStandbyChanged();
    }

    @GuardedBy("mLock")
    private int findForcedAppStandbyUidPackageIndexLocked(int uid, @NonNull String packageName) {
        final int size = mRunAnyRestrictedPackages.size();
        if (size > 8) {
            return mRunAnyRestrictedPackages.indexOf(Pair.create(uid, packageName));
        }
        for (int i = 0; i < size; i++) {
            final Pair<Integer, String> pair = mRunAnyRestrictedPackages.valueAt(i);

            if ((pair.first == uid) && packageName.equals(pair.second)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return whether a uid package-name pair is in mRunAnyRestrictedPackages.
     */
    @GuardedBy("mLock")
    boolean isRunAnyRestrictedLocked(int uid, @NonNull String packageName) {
        return findForcedAppStandbyUidPackageIndexLocked(uid, packageName) >= 0;
    }

    /**
     * Add to / remove from {@link #mRunAnyRestrictedPackages}.
     */
    @GuardedBy("mLock")
    boolean updateForcedAppStandbyUidPackageLocked(int uid, @NonNull String packageName,
            boolean restricted) {
        final int index = findForcedAppStandbyUidPackageIndexLocked(uid, packageName);
        final boolean wasRestricted = index >= 0;
        if (wasRestricted == restricted) {
            return false;
        }
        if (restricted) {
            mRunAnyRestrictedPackages.add(Pair.create(uid, packageName));
        } else {
            mRunAnyRestrictedPackages.removeAt(index);
        }
        return true;
    }

    private static boolean addUidToArray(SparseBooleanArray array, int uid) {
        if (UserHandle.isCore(uid)) {
            return false;
        }
        if (array.get(uid)) {
            return false;
        }
        array.put(uid, true);
        return true;
    }

    private static boolean removeUidFromArray(SparseBooleanArray array, int uid, boolean remove) {
        if (UserHandle.isCore(uid)) {
            return false;
        }
        if (!array.get(uid)) {
            return false;
        }
        if (remove) {
            array.delete(uid);
        } else {
            array.put(uid, false);
        }
        return true;
    }

    private final class UidObserver extends IUidObserver.Stub {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
        }

        @Override
        public void onUidActive(int uid) {
            mHandler.onUidActive(uid);
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            mHandler.onUidGone(uid, disabled);
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) {
            mHandler.onUidIdle(uid, disabled);
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) {
        }
    }

    private final class AppOpsWatcher extends IAppOpsCallback.Stub {
        @Override
        public void opChanged(int op, int uid, String packageName) throws RemoteException {
            boolean restricted = false;
            try {
                restricted = mAppOpsService.checkOperation(TARGET_OP,
                        uid, packageName) != AppOpsManager.MODE_ALLOWED;
            } catch (RemoteException e) {
                // Shouldn't happen
            }
            synchronized (mLock) {
                if (updateForcedAppStandbyUidPackageLocked(uid, packageName, restricted)) {
                    mHandler.notifyRunAnyAppOpsChanged(uid, packageName);
                }
            }
        }
    }

    final class StandbyTracker extends AppIdleStateChangeListener {
        @Override
        public void onAppIdleStateChanged(String packageName, int userId, boolean idle,
                int bucket, int reason) {
            if (DEBUG) {
                Slog.d(TAG, "onAppIdleStateChanged: " + packageName + " u" + userId
                        + (idle ? " idle" : " active") + " " + bucket);
            }
            synchronized (mLock) {
                final boolean changed;
                if (bucket == UsageStatsManager.STANDBY_BUCKET_EXEMPTED) {
                    changed = mExemptedBucketPackages.add(userId, packageName);
                } else {
                    changed = mExemptedBucketPackages.remove(userId, packageName);
                }
                if (changed) {
                    mHandler.notifyExemptedBucketChanged();
                }
            }
        }
    }

    private Listener[] cloneListeners() {
        synchronized (mLock) {
            return mListeners.toArray(new Listener[mListeners.size()]);
        }
    }

    private class MyHandler extends Handler {
        private static final int MSG_UID_ACTIVE_STATE_CHANGED = 0;
        private static final int MSG_RUN_ANY_CHANGED = 3;
        private static final int MSG_ALL_UNEXEMPTED = 4;
        private static final int MSG_ALL_EXEMPTION_LIST_CHANGED = 5;
        private static final int MSG_TEMP_EXEMPTION_LIST_CHANGED = 6;
        private static final int MSG_FORCE_ALL_CHANGED = 7;
        private static final int MSG_USER_REMOVED = 8;
        private static final int MSG_FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED = 9;
        private static final int MSG_EXEMPTED_BUCKET_CHANGED = 10;

        private static final int MSG_ON_UID_ACTIVE = 12;
        private static final int MSG_ON_UID_GONE = 13;
        private static final int MSG_ON_UID_IDLE = 14;

        MyHandler(Looper looper) {
            super(looper);
        }

        public void notifyUidActiveStateChanged(int uid) {
            obtainMessage(MSG_UID_ACTIVE_STATE_CHANGED, uid, 0).sendToTarget();
        }

        public void notifyRunAnyAppOpsChanged(int uid, @NonNull String packageName) {
            obtainMessage(MSG_RUN_ANY_CHANGED, uid, 0, packageName).sendToTarget();
        }

        public void notifyAllUnexempted() {
            removeMessages(MSG_ALL_UNEXEMPTED);
            obtainMessage(MSG_ALL_UNEXEMPTED).sendToTarget();
        }

        public void notifyAllExemptionListChanged() {
            removeMessages(MSG_ALL_EXEMPTION_LIST_CHANGED);
            obtainMessage(MSG_ALL_EXEMPTION_LIST_CHANGED).sendToTarget();
        }

        public void notifyTempExemptionListChanged() {
            removeMessages(MSG_TEMP_EXEMPTION_LIST_CHANGED);
            obtainMessage(MSG_TEMP_EXEMPTION_LIST_CHANGED).sendToTarget();
        }

        public void notifyForceAllAppsStandbyChanged() {
            removeMessages(MSG_FORCE_ALL_CHANGED);
            obtainMessage(MSG_FORCE_ALL_CHANGED).sendToTarget();
        }

        public void notifyForcedAppStandbyFeatureFlagChanged() {
            removeMessages(MSG_FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED);
            obtainMessage(MSG_FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED).sendToTarget();
        }

        public void notifyExemptedBucketChanged() {
            removeMessages(MSG_EXEMPTED_BUCKET_CHANGED);
            obtainMessage(MSG_EXEMPTED_BUCKET_CHANGED).sendToTarget();
        }

        public void doUserRemoved(int userId) {
            obtainMessage(MSG_USER_REMOVED, userId, 0).sendToTarget();
        }

        public void onUidActive(int uid) {
            obtainMessage(MSG_ON_UID_ACTIVE, uid, 0).sendToTarget();
        }

        public void onUidGone(int uid, boolean disabled) {
            obtainMessage(MSG_ON_UID_GONE, uid, disabled ? 1 : 0).sendToTarget();
        }

        public void onUidIdle(int uid, boolean disabled) {
            obtainMessage(MSG_ON_UID_IDLE, uid, disabled ? 1 : 0).sendToTarget();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_REMOVED:
                    handleUserRemoved(msg.arg1);
                    return;
            }

            // Only notify the listeners when started.
            synchronized (mLock) {
                if (!mStarted) {
                    return;
                }
            }
            final AppStateTrackerImpl sender = AppStateTrackerImpl.this;

            long start = mStatLogger.getTime();
            switch (msg.what) {
                case MSG_UID_ACTIVE_STATE_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onUidActiveStateChanged(sender, msg.arg1);
                    }
                    mStatLogger.logDurationStat(Stats.UID_ACTIVE_STATE_CHANGED, start);
                    return;

                case MSG_RUN_ANY_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onRunAnyAppOpsChanged(sender, msg.arg1, (String) msg.obj);
                    }
                    mStatLogger.logDurationStat(Stats.RUN_ANY_CHANGED, start);
                    return;

                case MSG_ALL_UNEXEMPTED:
                    for (Listener l : cloneListeners()) {
                        l.onPowerSaveUnexempted(sender);
                    }
                    mStatLogger.logDurationStat(Stats.ALL_UNEXEMPTED, start);
                    return;

                case MSG_ALL_EXEMPTION_LIST_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onPowerSaveExemptionListChanged(sender);
                    }
                    mStatLogger.logDurationStat(Stats.ALL_EXEMPTION_LIST_CHANGED, start);
                    return;

                case MSG_TEMP_EXEMPTION_LIST_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onTempPowerSaveExemptionListChanged(sender);
                    }
                    mStatLogger.logDurationStat(Stats.TEMP_EXEMPTION_LIST_CHANGED, start);
                    return;

                case MSG_EXEMPTED_BUCKET_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onExemptedBucketChanged(sender);
                    }
                    mStatLogger.logDurationStat(Stats.EXEMPTED_BUCKET_CHANGED, start);
                    return;

                case MSG_FORCE_ALL_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onForceAllAppsStandbyChanged(sender);
                    }
                    mStatLogger.logDurationStat(Stats.FORCE_ALL_CHANGED, start);
                    return;

                case MSG_FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED:
                    // Feature flag for forced app standby changed.
                    final boolean unblockAlarms;
                    synchronized (mLock) {
                        unblockAlarms = !mForcedAppStandbyEnabled;
                    }
                    for (Listener l : cloneListeners()) {
                        l.updateAllJobs();
                        if (unblockAlarms) {
                            l.unblockAllUnrestrictedAlarms();
                        }
                    }
                    mStatLogger.logDurationStat(
                            Stats.FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED, start);
                    return;

                case MSG_USER_REMOVED:
                    handleUserRemoved(msg.arg1);
                    return;

                case MSG_ON_UID_ACTIVE:
                    handleUidActive(msg.arg1);
                    return;
                case MSG_ON_UID_GONE:
                    handleUidGone(msg.arg1);
                    if (msg.arg2 != 0) {
                        handleUidDisabled(msg.arg1);
                    }
                    return;
                case MSG_ON_UID_IDLE:
                    handleUidIdle(msg.arg1);
                    if (msg.arg2 != 0) {
                        handleUidDisabled(msg.arg1);
                    }
                    return;
            }
        }

        private void handleUidDisabled(int uid) {
            for (Listener l : cloneListeners()) {
                l.removeAlarmsForUid(uid);
            }
        }

        public void handleUidActive(int uid) {
            synchronized (mLock) {
                if (addUidToArray(mActiveUids, uid)) {
                    mHandler.notifyUidActiveStateChanged(uid);
                }
            }
        }

        public void handleUidGone(int uid) {
            removeUid(uid, true);
        }

        public void handleUidIdle(int uid) {
            // Just to avoid excessive memcpy, don't remove from the array in this case.
            removeUid(uid, false);
        }

        private void removeUid(int uid, boolean remove) {
            synchronized (mLock) {
                if (removeUidFromArray(mActiveUids, uid, remove)) {
                    mHandler.notifyUidActiveStateChanged(uid);
                }
            }
        }
    }

    void handleUserRemoved(int removedUserId) {
        synchronized (mLock) {
            for (int i = mRunAnyRestrictedPackages.size() - 1; i >= 0; i--) {
                final Pair<Integer, String> pair = mRunAnyRestrictedPackages.valueAt(i);
                final int uid = pair.first;
                final int userId = UserHandle.getUserId(uid);

                if (userId == removedUserId) {
                    mRunAnyRestrictedPackages.removeAt(i);
                }
            }
            cleanUpArrayForUser(mActiveUids, removedUserId);
            mExemptedBucketPackages.remove(removedUserId);
        }
    }

    private void cleanUpArrayForUser(SparseBooleanArray array, int removedUserId) {
        for (int i = array.size() - 1; i >= 0; i--) {
            final int uid = array.keyAt(i);
            final int userId = UserHandle.getUserId(uid);

            if (userId == removedUserId) {
                array.removeAt(i);
            }
        }
    }

    /**
     * Called by device idle controller to update the power save exemption lists.
     */
    public void setPowerSaveExemptionListAppIds(
            int[] powerSaveExemptionListExceptIdleAppIdArray,
            int[] powerSaveExemptionListUserAppIdArray,
            int[] tempExemptionListAppIdArray) {
        synchronized (mLock) {
            final int[] previousExemptionList = mPowerExemptAllAppIds;
            final int[] previousTempExemptionList = mTempExemptAppIds;

            mPowerExemptAllAppIds = powerSaveExemptionListExceptIdleAppIdArray;
            mTempExemptAppIds = tempExemptionListAppIdArray;
            mPowerExemptUserAppIds = powerSaveExemptionListUserAppIdArray;

            if (isAnyAppIdUnexempt(previousExemptionList, mPowerExemptAllAppIds)) {
                mHandler.notifyAllUnexempted();
            } else if (!Arrays.equals(previousExemptionList, mPowerExemptAllAppIds)) {
                mHandler.notifyAllExemptionListChanged();
            }

            if (!Arrays.equals(previousTempExemptionList, mTempExemptAppIds)) {
                mHandler.notifyTempExemptionListChanged();
            }

        }
    }

    /**
     * @return true if a sorted app-id array {@code prevArray} has at least one element
     * that's not in a sorted app-id array {@code newArray}.
     */
    @VisibleForTesting
    static boolean isAnyAppIdUnexempt(int[] prevArray, int[] newArray) {
        int i1 = 0;
        int i2 = 0;
        boolean prevFinished;
        boolean newFinished;

        for (;;) {
            prevFinished = i1 >= prevArray.length;
            newFinished = i2 >= newArray.length;
            if (prevFinished || newFinished) {
                break;
            }
            int a1 = prevArray[i1];
            int a2 = newArray[i2];

            if (a1 == a2) {
                i1++;
                i2++;
                continue;
            }
            if (a1 < a2) {
                // prevArray has an element that's not in a2.
                return true;
            }
            i2++;
        }
        if (prevFinished) {
            return false;
        }
        return newFinished;
    }

    // Public interface.

    /**
     * Register a listener to get callbacks when any state changes.
     */
    public void addListener(@NonNull Listener listener) {
        synchronized (mLock) {
            mListeners.add(listener);
        }
    }

    /**
     * @return whether alarms should be restricted for a UID package-name, due to explicit
     * user-forced app standby. Use {{@link #areAlarmsRestrictedByBatterySaver} to check for
     * restrictions induced by battery saver.
     */
    public boolean areAlarmsRestricted(int uid, @NonNull String packageName) {
        if (isUidActive(uid)) {
            return false;
        }
        synchronized (mLock) {
            final int appId = UserHandle.getAppId(uid);
            if (ArrayUtils.contains(mPowerExemptAllAppIds, appId)) {
                return false;
            }
            return (mForcedAppStandbyEnabled && isRunAnyRestrictedLocked(uid, packageName));
        }
    }

    /**
     * @return whether alarms should be restricted when due to battery saver.
     */
    public boolean areAlarmsRestrictedByBatterySaver(int uid, @NonNull String packageName) {
        if (isUidActive(uid)) {
            return false;
        }
        synchronized (mLock) {
            final int appId = UserHandle.getAppId(uid);
            if (ArrayUtils.contains(mPowerExemptAllAppIds, appId)) {
                return false;
            }
            final int userId = UserHandle.getUserId(uid);
            if (mAppStandbyInternal.isAppIdleEnabled() && !mAppStandbyInternal.isInParole()
                    && mExemptedBucketPackages.contains(userId, packageName)) {
                return false;
            }
            return mForceAllAppsStandby;
        }
    }


    /**
     * @return whether jobs should be restricted for a UID package-name. This could be due to
     * battery saver or user-forced app standby
     */
    public boolean areJobsRestricted(int uid, @NonNull String packageName,
            boolean hasForegroundExemption) {
        if (isUidActive(uid)) {
            return false;
        }
        synchronized (mLock) {
            final int appId = UserHandle.getAppId(uid);
            if (ArrayUtils.contains(mPowerExemptAllAppIds, appId)
                    || ArrayUtils.contains(mTempExemptAppIds, appId)) {
                return false;
            }
            if (mForcedAppStandbyEnabled && isRunAnyRestrictedLocked(uid, packageName)) {
                return true;
            }
            if (hasForegroundExemption) {
                return false;
            }
            final int userId = UserHandle.getUserId(uid);
            if (mAppStandbyInternal.isAppIdleEnabled() && !mAppStandbyInternal.isInParole()
                    && mExemptedBucketPackages.contains(userId, packageName)) {
                return false;
            }
            return mForceAllAppsStandby;
        }
    }

    /**
     * @return whether a UID is in active or not *based on cached information.*
     *
     * Note this information is based on the UID proc state callback, meaning it's updated
     * asynchronously and may subtly be stale. If the fresh data is needed, use
     * {@link #isUidActiveSynced} instead.
     */
    public boolean isUidActive(int uid) {
        if (UserHandle.isCore(uid)) {
            return true;
        }
        synchronized (mLock) {
            return mActiveUids.get(uid);
        }
    }

    /**
     * @return whether a UID is in active or not *right now.*
     *
     * This gives the fresh information, but may access the activity manager so is slower.
     */
    public boolean isUidActiveSynced(int uid) {
        if (isUidActive(uid)) { // Use the cached one first.
            return true;
        }
        final long start = mStatLogger.getTime();

        final boolean ret = mActivityManagerInternal.isUidActive(uid);
        mStatLogger.logDurationStat(Stats.IS_UID_ACTIVE_RAW, start);

        return ret;
    }

    /**
     * @return whether force all apps standby is enabled or not.
     */
    public boolean isForceAllAppsStandbyEnabled() {
        synchronized (mLock) {
            return mForceAllAppsStandby;
        }
    }

    /**
     * @return whether a UID/package has {@code OP_RUN_ANY_IN_BACKGROUND} allowed or not.
     *
     * Note clients normally shouldn't need to access it. It's only for dumpsys.
     */
    public boolean isRunAnyInBackgroundAppOpsAllowed(int uid, @NonNull String packageName) {
        synchronized (mLock) {
            return !isRunAnyRestrictedLocked(uid, packageName);
        }
    }

    /**
     * @return whether a UID is in the user / system defined power-save exemption list or not.
     *
     * Note clients normally shouldn't need to access it. It's only for dumpsys.
     */
    public boolean isUidPowerSaveExempt(int uid) {
        synchronized (mLock) {
            return ArrayUtils.contains(mPowerExemptAllAppIds, UserHandle.getAppId(uid));
        }
    }

    /**
     * @param uid the uid to check for
     * @return whether a UID is in the user defined power-save exemption list or not.
     */
    public boolean isUidPowerSaveUserExempt(int uid) {
        synchronized (mLock) {
            return ArrayUtils.contains(mPowerExemptUserAppIds, UserHandle.getAppId(uid));
        }
    }

    /**
     * @return whether a UID is in the temp power-save exemption list or not.
     *
     * Note clients normally shouldn't need to access it. It's only for dumpsys.
     */
    public boolean isUidTempPowerSaveExempt(int uid) {
        synchronized (mLock) {
            return ArrayUtils.contains(mTempExemptAppIds, UserHandle.getAppId(uid));
        }
    }

    /**
     * Dump the internal state to the given PrintWriter. Can be included in the dump
     * of a binder service to be output on the shell command "dumpsys".
     */
    public void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.println("Current AppStateTracker State:");

            pw.increaseIndent();
            pw.println("Forced App Standby Feature enabled: " + mForcedAppStandbyEnabled);

            pw.print("Force all apps standby: ");
            pw.println(isForceAllAppsStandbyEnabled());

            pw.print("Small Battery Device: ");
            pw.println(isSmallBatteryDevice());

            pw.print("Force all apps standby for small battery device: ");
            pw.println(mForceAllAppStandbyForSmallBattery);

            pw.print("Plugged In: ");
            pw.println(mIsPluggedIn);

            pw.print("Active uids: ");
            dumpUids(pw, mActiveUids);

            pw.print("Except-idle + user exemption list appids: ");
            pw.println(Arrays.toString(mPowerExemptAllAppIds));

            pw.print("User exemption list appids: ");
            pw.println(Arrays.toString(mPowerExemptUserAppIds));

            pw.print("Temp exemption list appids: ");
            pw.println(Arrays.toString(mTempExemptAppIds));

            pw.println("Exempted bucket packages:");
            pw.increaseIndent();
            for (int i = 0; i < mExemptedBucketPackages.size(); i++) {
                pw.print("User ");
                pw.print(mExemptedBucketPackages.keyAt(i));
                pw.println();

                pw.increaseIndent();
                for (int j = 0; j < mExemptedBucketPackages.sizeAt(i); j++) {
                    pw.print(mExemptedBucketPackages.valueAt(i, j));
                    pw.println();
                }
                pw.decreaseIndent();
            }
            pw.decreaseIndent();
            pw.println();

            pw.println("Restricted packages:");
            pw.increaseIndent();
            for (Pair<Integer, String> uidAndPackage : mRunAnyRestrictedPackages) {
                pw.print(UserHandle.formatUid(uidAndPackage.first));
                pw.print(" ");
                pw.print(uidAndPackage.second);
                pw.println();
            }
            pw.decreaseIndent();

            mStatLogger.dump(pw);
            pw.decreaseIndent();
        }
    }

    private void dumpUids(PrintWriter pw, SparseBooleanArray array) {
        pw.print("[");

        String sep = "";
        for (int i = 0; i < array.size(); i++) {
            if (array.valueAt(i)) {
                pw.print(sep);
                pw.print(UserHandle.formatUid(array.keyAt(i)));
                sep = " ";
            }
        }
        pw.println("]");
    }

    /**
     * Proto version of {@link #dump(IndentingPrintWriter)}
     */
    public void dumpProto(ProtoOutputStream proto, long fieldId) {
        synchronized (mLock) {
            final long token = proto.start(fieldId);

            proto.write(AppStateTrackerProto.FORCED_APP_STANDBY_FEATURE_ENABLED,
                    mForcedAppStandbyEnabled);
            proto.write(AppStateTrackerProto.FORCE_ALL_APPS_STANDBY,
                    isForceAllAppsStandbyEnabled());
            proto.write(AppStateTrackerProto.IS_SMALL_BATTERY_DEVICE, isSmallBatteryDevice());
            proto.write(AppStateTrackerProto.FORCE_ALL_APPS_STANDBY_FOR_SMALL_BATTERY,
                    mForceAllAppStandbyForSmallBattery);
            proto.write(AppStateTrackerProto.IS_PLUGGED_IN, mIsPluggedIn);

            for (int i = 0; i < mActiveUids.size(); i++) {
                if (mActiveUids.valueAt(i)) {
                    proto.write(AppStateTrackerProto.ACTIVE_UIDS, mActiveUids.keyAt(i));
                }
            }

            for (int appId : mPowerExemptAllAppIds) {
                proto.write(AppStateTrackerProto.POWER_SAVE_EXEMPT_APP_IDS, appId);
            }

            for (int appId : mPowerExemptUserAppIds) {
                proto.write(AppStateTrackerProto.POWER_SAVE_USER_EXEMPT_APP_IDS, appId);
            }

            for (int appId : mTempExemptAppIds) {
                proto.write(AppStateTrackerProto.TEMP_POWER_SAVE_EXEMPT_APP_IDS, appId);
            }

            for (int i = 0; i < mExemptedBucketPackages.size(); i++) {
                for (int j = 0; j < mExemptedBucketPackages.sizeAt(i); j++) {
                    final long token2 = proto.start(AppStateTrackerProto.EXEMPTED_BUCKET_PACKAGES);

                    proto.write(ExemptedPackage.USER_ID, mExemptedBucketPackages.keyAt(i));
                    proto.write(ExemptedPackage.PACKAGE_NAME,
                            mExemptedBucketPackages.valueAt(i, j));

                    proto.end(token2);
                }
            }

            for (Pair<Integer, String> uidAndPackage : mRunAnyRestrictedPackages) {
                final long token2 = proto.start(
                        AppStateTrackerProto.RUN_ANY_IN_BACKGROUND_RESTRICTED_PACKAGES);
                proto.write(RunAnyInBackgroundRestrictedPackages.UID, uidAndPackage.first);
                proto.write(RunAnyInBackgroundRestrictedPackages.PACKAGE_NAME,
                        uidAndPackage.second);
                proto.end(token2);
            }

            mStatLogger.dumpProto(proto, AppStateTrackerProto.STATS);

            proto.end(token);
        }
    }
}
