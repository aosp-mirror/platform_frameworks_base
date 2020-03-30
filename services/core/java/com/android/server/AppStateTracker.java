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
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.AppIdleStateChangeListener;
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
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.StatLogger;
import com.android.server.ForceAppStandbyTrackerProto.ExemptedPackage;
import com.android.server.ForceAppStandbyTrackerProto.RunAnyInBackgroundRestrictedPackages;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Class to keep track of the information related to "force app standby", which includes:
 * - OP_RUN_ANY_IN_BACKGROUND for each package
 * - UID foreground/active state
 * - User+system power save whitelist
 * - Temporary power save whitelist
 * - Global "force all apps standby" mode enforced by battery saver.
 *
 * Test:
  atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/AppStateTrackerTest.java
 */
public class AppStateTracker {
    private static final String TAG = "AppStateTracker";
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
    UsageStatsManagerInternal mUsageStatsManagerInternal;

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

    /** UIDs that are in the foreground. */
    @GuardedBy("mLock")
    final SparseBooleanArray mForegroundUids = new SparseBooleanArray();

    /**
     * System except-idle + user whitelist in the device idle controller.
     */
    @GuardedBy("mLock")
    private int[] mPowerWhitelistedAllAppIds = new int[0];

    /**
     * User whitelisted apps in the device idle controller.
     */
    @GuardedBy("mLock")
    private int[] mPowerWhitelistedUserAppIds = new int[0];

    @GuardedBy("mLock")
    private int[] mTempWhitelistedAppIds = mPowerWhitelistedAllAppIds;

    /**
     * Per-user packages that are in the EXEMPT bucket.
     */
    @GuardedBy("mLock")
    private final SparseSetArray<String> mExemptedPackages = new SparseSetArray<>();

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

    interface Stats {
        int UID_FG_STATE_CHANGED = 0;
        int UID_ACTIVE_STATE_CHANGED = 1;
        int RUN_ANY_CHANGED = 2;
        int ALL_UNWHITELISTED = 3;
        int ALL_WHITELIST_CHANGED = 4;
        int TEMP_WHITELIST_CHANGED = 5;
        int EXEMPT_CHANGED = 6;
        int FORCE_ALL_CHANGED = 7;
        int FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED = 8;

        int IS_UID_ACTIVE_CACHED = 9;
        int IS_UID_ACTIVE_RAW = 10;
    }

    private final StatLogger mStatLogger = new StatLogger(new String[] {
            "UID_FG_STATE_CHANGED",
            "UID_ACTIVE_STATE_CHANGED",
            "RUN_ANY_CHANGED",
            "ALL_UNWHITELISTED",
            "ALL_WHITELIST_CHANGED",
            "TEMP_WHITELIST_CHANGED",
            "EXEMPT_CHANGED",
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
                        Slog.d(TAG,"Forced app standby feature flag changed: "
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

    public static abstract class Listener {
        /**
         * This is called when the OP_RUN_ANY_IN_BACKGROUND appops changed for a package.
         */
        private void onRunAnyAppOpsChanged(AppStateTracker sender,
                int uid, @NonNull String packageName) {
            updateJobsForUidPackage(uid, packageName, sender.isUidActive(uid));

            if (!sender.areAlarmsRestricted(uid, packageName, /*allowWhileIdle=*/ false)) {
                unblockAlarmsForUidPackage(uid, packageName);
            } else if (!sender.areAlarmsRestricted(uid, packageName, /*allowWhileIdle=*/ true)){
                // we need to deliver the allow-while-idle alarms for this uid, package
                unblockAllUnrestrictedAlarms();
            }

            if (!sender.isRunAnyInBackgroundAppOpsAllowed(uid, packageName)) {
                Slog.v(TAG, "Package " + packageName + "/" + uid
                        + " toggled into fg service restriction");
                stopForegroundServicesForUidPackage(uid, packageName);
            }
        }

        /**
         * This is called when the foreground state changed for a UID.
         */
        private void onUidForegroundStateChanged(AppStateTracker sender, int uid) {
            onUidForeground(uid, sender.isUidInForeground(uid));
        }

        /**
         * This is called when the active/idle state changed for a UID.
         */
        private void onUidActiveStateChanged(AppStateTracker sender, int uid) {
            final boolean isActive = sender.isUidActive(uid);

            updateJobsForUid(uid, isActive);

            if (isActive) {
                unblockAlarmsForUid(uid);
            }
        }

        /**
         * This is called when an app-id(s) is removed from the power save whitelist.
         */
        private void onPowerSaveUnwhitelisted(AppStateTracker sender) {
            updateAllJobs();
            unblockAllUnrestrictedAlarms();
        }

        /**
         * This is called when the power save whitelist changes, excluding the
         * {@link #onPowerSaveUnwhitelisted} case.
         */
        private void onPowerSaveWhitelistedChanged(AppStateTracker sender) {
            updateAllJobs();
        }

        /**
         * This is called when the temp whitelist changes.
         */
        private void onTempPowerSaveWhitelistChanged(AppStateTracker sender) {

            // TODO This case happens rather frequently; consider optimizing and update jobs
            // only for affected app-ids.

            updateAllJobs();

            // Note when an app is just put in the temp whitelist, we do *not* drain pending alarms.
        }

        /**
         * This is called when the EXEMPT bucket is updated.
         */
        private void onExemptChanged(AppStateTracker sender) {
            // This doesn't happen very often, so just re-evaluate all jobs / alarms.
            updateAllJobs();
            unblockAllUnrestrictedAlarms();
        }

        /**
         * This is called when the global "force all apps standby" flag changes.
         */
        private void onForceAllAppsStandbyChanged(AppStateTracker sender) {
            updateAllJobs();

            if (!sender.isForceAllAppsStandbyEnabled()) {
                unblockAllUnrestrictedAlarms();
            }
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
         * Called when a UID comes into the foreground or the background.
         *
         * @see #isUidInForeground(int)
         */
        public void onUidForeground(int uid, boolean foreground) {
        }
    }

    public AppStateTracker(Context context, Looper looper) {
        mContext = context;
        mHandler = new MyHandler(looper);
    }

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
            mUsageStatsManagerInternal = Objects.requireNonNull(
                    injectUsageStatsManagerInternal());

            mFlagsObserver = new FeatureFlagsObserver();
            mFlagsObserver.register();
            mForcedAppStandbyEnabled = mFlagsObserver.isForcedAppStandbyEnabled();
            mForceAllAppStandbyForSmallBattery =
                    mFlagsObserver.isForcedAppStandbyForSmallBatteryEnabled();
            mStandbyTracker = new StandbyTracker();
            mUsageStatsManagerInternal.addAppIdleStateChangeListener(mStandbyTracker);

            try {
                mIActivityManager.registerUidObserver(new UidObserver(),
                        ActivityManager.UID_OBSERVER_GONE
                                | ActivityManager.UID_OBSERVER_IDLE
                                | ActivityManager.UID_OBSERVER_ACTIVE
                                | ActivityManager.UID_OBSERVER_PROCSTATE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, null);
                mAppOpsService.startWatchingMode(TARGET_OP, null,
                        new AppOpsWatcher());
            } catch (RemoteException e) {
                // shouldn't happen.
            }

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_REMOVED);
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            mContext.registerReceiver(new MyReceiver(), filter);

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
    UsageStatsManagerInternal injectUsageStatsManagerInternal() {
        return LocalServices.getService(UsageStatsManagerInternal.class);
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
        public void onUidStateChanged(int uid, int procState, long procStateSeq) {
            mHandler.onUidStateChanged(uid, procState);
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

    private final class MyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userId > 0) {
                    mHandler.doUserRemoved(userId);
                }
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                synchronized (mLock) {
                    mIsPluggedIn = (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
                }
                updateForceAllAppStandbyState();
            }
        }
    }

    final class StandbyTracker extends AppIdleStateChangeListener {
        @Override
        public void onAppIdleStateChanged(String packageName, int userId, boolean idle,
                int bucket, int reason) {
            if (DEBUG) {
                Slog.d(TAG,"onAppIdleStateChanged: " + packageName + " u" + userId
                        + (idle ? " idle" : " active") + " " + bucket);
            }
            synchronized (mLock) {
                final boolean changed;
                if (bucket == UsageStatsManager.STANDBY_BUCKET_EXEMPTED) {
                    changed = mExemptedPackages.add(userId, packageName);
                } else {
                    changed = mExemptedPackages.remove(userId, packageName);
                }
                if (changed) {
                    mHandler.notifyExemptChanged();
                }
            }
        }

        @Override
        public void onParoleStateChanged(boolean isParoleOn) {
        }
    }

    private Listener[] cloneListeners() {
        synchronized (mLock) {
            return mListeners.toArray(new Listener[mListeners.size()]);
        }
    }

    private class MyHandler extends Handler {
        private static final int MSG_UID_ACTIVE_STATE_CHANGED = 0;
        private static final int MSG_UID_FG_STATE_CHANGED = 1;
        private static final int MSG_RUN_ANY_CHANGED = 3;
        private static final int MSG_ALL_UNWHITELISTED = 4;
        private static final int MSG_ALL_WHITELIST_CHANGED = 5;
        private static final int MSG_TEMP_WHITELIST_CHANGED = 6;
        private static final int MSG_FORCE_ALL_CHANGED = 7;
        private static final int MSG_USER_REMOVED = 8;
        private static final int MSG_FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED = 9;
        private static final int MSG_EXEMPT_CHANGED = 10;

        private static final int MSG_ON_UID_STATE_CHANGED = 11;
        private static final int MSG_ON_UID_ACTIVE = 12;
        private static final int MSG_ON_UID_GONE = 13;
        private static final int MSG_ON_UID_IDLE = 14;

        public MyHandler(Looper looper) {
            super(looper);
        }

        public void notifyUidActiveStateChanged(int uid) {
            obtainMessage(MSG_UID_ACTIVE_STATE_CHANGED, uid, 0).sendToTarget();
        }

        public void notifyUidForegroundStateChanged(int uid) {
            obtainMessage(MSG_UID_FG_STATE_CHANGED, uid, 0).sendToTarget();
        }

        public void notifyRunAnyAppOpsChanged(int uid, @NonNull String packageName) {
            obtainMessage(MSG_RUN_ANY_CHANGED, uid, 0, packageName).sendToTarget();
        }

        public void notifyAllUnwhitelisted() {
            removeMessages(MSG_ALL_UNWHITELISTED);
            obtainMessage(MSG_ALL_UNWHITELISTED).sendToTarget();
        }

        public void notifyAllWhitelistChanged() {
            removeMessages(MSG_ALL_WHITELIST_CHANGED);
            obtainMessage(MSG_ALL_WHITELIST_CHANGED).sendToTarget();
        }

        public void notifyTempWhitelistChanged() {
            removeMessages(MSG_TEMP_WHITELIST_CHANGED);
            obtainMessage(MSG_TEMP_WHITELIST_CHANGED).sendToTarget();
        }

        public void notifyForceAllAppsStandbyChanged() {
            removeMessages(MSG_FORCE_ALL_CHANGED);
            obtainMessage(MSG_FORCE_ALL_CHANGED).sendToTarget();
        }

        public void notifyForcedAppStandbyFeatureFlagChanged() {
            removeMessages(MSG_FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED);
            obtainMessage(MSG_FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED).sendToTarget();
        }

        public void notifyExemptChanged() {
            removeMessages(MSG_EXEMPT_CHANGED);
            obtainMessage(MSG_EXEMPT_CHANGED).sendToTarget();
        }

        public void doUserRemoved(int userId) {
            obtainMessage(MSG_USER_REMOVED, userId, 0).sendToTarget();
        }

        public void onUidStateChanged(int uid, int procState) {
            obtainMessage(MSG_ON_UID_STATE_CHANGED, uid, procState).sendToTarget();
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
            final AppStateTracker sender = AppStateTracker.this;

            long start = mStatLogger.getTime();
            switch (msg.what) {
                case MSG_UID_ACTIVE_STATE_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onUidActiveStateChanged(sender, msg.arg1);
                    }
                    mStatLogger.logDurationStat(Stats.UID_ACTIVE_STATE_CHANGED, start);
                    return;

                case MSG_UID_FG_STATE_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onUidForegroundStateChanged(sender, msg.arg1);
                    }
                    mStatLogger.logDurationStat(Stats.UID_FG_STATE_CHANGED, start);
                    return;

                case MSG_RUN_ANY_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onRunAnyAppOpsChanged(sender, msg.arg1, (String) msg.obj);
                    }
                    mStatLogger.logDurationStat(Stats.RUN_ANY_CHANGED, start);
                    return;

                case MSG_ALL_UNWHITELISTED:
                    for (Listener l : cloneListeners()) {
                        l.onPowerSaveUnwhitelisted(sender);
                    }
                    mStatLogger.logDurationStat(Stats.ALL_UNWHITELISTED, start);
                    return;

                case MSG_ALL_WHITELIST_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onPowerSaveWhitelistedChanged(sender);
                    }
                    mStatLogger.logDurationStat(Stats.ALL_WHITELIST_CHANGED, start);
                    return;

                case MSG_TEMP_WHITELIST_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onTempPowerSaveWhitelistChanged(sender);
                    }
                    mStatLogger.logDurationStat(Stats.TEMP_WHITELIST_CHANGED, start);
                    return;

                case MSG_EXEMPT_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onExemptChanged(sender);
                    }
                    mStatLogger.logDurationStat(Stats.EXEMPT_CHANGED, start);
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
                        unblockAlarms = !mForcedAppStandbyEnabled && !mForceAllAppsStandby;
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

                case MSG_ON_UID_STATE_CHANGED:
                    handleUidStateChanged(msg.arg1, msg.arg2);
                    return;
                case MSG_ON_UID_ACTIVE:
                    handleUidActive(msg.arg1);
                    return;
                case MSG_ON_UID_GONE:
                    handleUidGone(msg.arg1, msg.arg1 != 0);
                    return;
                case MSG_ON_UID_IDLE:
                    handleUidIdle(msg.arg1, msg.arg1 != 0);
                    return;
            }
        }

        public void handleUidStateChanged(int uid, int procState) {
            synchronized (mLock) {
                if (procState > ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND) {
                    if (removeUidFromArray(mForegroundUids, uid, false)) {
                        mHandler.notifyUidForegroundStateChanged(uid);
                    }
                } else {
                    if (addUidToArray(mForegroundUids, uid)) {
                        mHandler.notifyUidForegroundStateChanged(uid);
                    }
                }
            }
        }

        public void handleUidActive(int uid) {
            synchronized (mLock) {
                if (addUidToArray(mActiveUids, uid)) {
                    mHandler.notifyUidActiveStateChanged(uid);
                }
            }
        }

        public void handleUidGone(int uid, boolean disabled) {
            removeUid(uid, true);
        }

        public void handleUidIdle(int uid, boolean disabled) {
            // Just to avoid excessive memcpy, don't remove from the array in this case.
            removeUid(uid, false);
        }

        private void removeUid(int uid, boolean remove) {
            synchronized (mLock) {
                if (removeUidFromArray(mActiveUids, uid, remove)) {
                    mHandler.notifyUidActiveStateChanged(uid);
                }
                if (removeUidFromArray(mForegroundUids, uid, remove)) {
                    mHandler.notifyUidForegroundStateChanged(uid);
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
            cleanUpArrayForUser(mForegroundUids, removedUserId);
            mExemptedPackages.remove(removedUserId);
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
     * Called by device idle controller to update the power save whitelists.
     */
    public void setPowerSaveWhitelistAppIds(
            int[] powerSaveWhitelistExceptIdleAppIdArray,
            int[] powerSaveWhitelistUserAppIdArray,
            int[] tempWhitelistAppIdArray) {
        synchronized (mLock) {
            final int[] previousWhitelist = mPowerWhitelistedAllAppIds;
            final int[] previousTempWhitelist = mTempWhitelistedAppIds;

            mPowerWhitelistedAllAppIds = powerSaveWhitelistExceptIdleAppIdArray;
            mTempWhitelistedAppIds = tempWhitelistAppIdArray;
            mPowerWhitelistedUserAppIds = powerSaveWhitelistUserAppIdArray;

            if (isAnyAppIdUnwhitelisted(previousWhitelist, mPowerWhitelistedAllAppIds)) {
                mHandler.notifyAllUnwhitelisted();
            } else if (!Arrays.equals(previousWhitelist, mPowerWhitelistedAllAppIds)) {
                mHandler.notifyAllWhitelistChanged();
            }

            if (!Arrays.equals(previousTempWhitelist, mTempWhitelistedAppIds)) {
                mHandler.notifyTempWhitelistChanged();
            }

        }
    }

    /**
     * @retunr true if a sorted app-id array {@code prevArray} has at least one element
     * that's not in a sorted app-id array {@code newArray}.
     */
    @VisibleForTesting
    static boolean isAnyAppIdUnwhitelisted(int[] prevArray, int[] newArray) {
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
     * Register a new listener.
     */
    public void addListener(@NonNull Listener listener) {
        synchronized (mLock) {
            mListeners.add(listener);
        }
    }

    /**
     * @return whether alarms should be restricted for a UID package-name.
     */
    public boolean areAlarmsRestricted(int uid, @NonNull String packageName,
            boolean isExemptOnBatterySaver) {
        return isRestricted(uid, packageName, /*useTempWhitelistToo=*/ false,
                isExemptOnBatterySaver);
    }

    /**
     * @return whether jobs should be restricted for a UID package-name.
     */
    public boolean areJobsRestricted(int uid, @NonNull String packageName,
            boolean hasForegroundExemption) {
        return isRestricted(uid, packageName, /*useTempWhitelistToo=*/ true,
                hasForegroundExemption);
    }

    /**
     * @return whether foreground services should be suppressed in the background
     * due to forced app standby for the given app
     */
    public boolean areForegroundServicesRestricted(int uid, @NonNull String packageName) {
        synchronized (mLock) {
            return isRunAnyRestrictedLocked(uid, packageName);
        }
    }

    /**
     * @return whether force-app-standby is effective for a UID package-name.
     */
    private boolean isRestricted(int uid, @NonNull String packageName,
            boolean useTempWhitelistToo, boolean exemptOnBatterySaver) {
        if (isUidActive(uid)) {
            return false;
        }
        synchronized (mLock) {
            // Whitelisted?
            final int appId = UserHandle.getAppId(uid);
            if (ArrayUtils.contains(mPowerWhitelistedAllAppIds, appId)) {
                return false;
            }
            if (useTempWhitelistToo &&
                    ArrayUtils.contains(mTempWhitelistedAppIds, appId)) {
                return false;
            }
            if (mForcedAppStandbyEnabled && isRunAnyRestrictedLocked(uid, packageName)) {
                return true;
            }
            if (exemptOnBatterySaver) {
                return false;
            }
            final int userId = UserHandle.getUserId(uid);
            if (mExemptedPackages.contains(userId, packageName)) {
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
     * @return whether a UID is in the foreground or not.
     *
     * Note this information is based on the UID proc state callback, meaning it's updated
     * asynchronously and may subtly be stale. If the fresh data is needed, use
     * {@link ActivityManagerInternal#getUidProcessState} instead.
     */
    public boolean isUidInForeground(int uid) {
        if (UserHandle.isCore(uid)) {
            return true;
        }
        synchronized (mLock) {
            return mForegroundUids.get(uid);
        }
    }

    /**
     * @return whether force all apps standby is enabled or not.
     *
     */
    boolean isForceAllAppsStandbyEnabled() {
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
     * @return whether a UID is in the user / system defined power-save whitelist or not.
     *
     * Note clients normally shouldn't need to access it. It's only for dumpsys.
     */
    public boolean isUidPowerSaveWhitelisted(int uid) {
        synchronized (mLock) {
            return ArrayUtils.contains(mPowerWhitelistedAllAppIds, UserHandle.getAppId(uid));
        }
    }

    /**
     * @param uid the uid to check for
     * @return whether a UID is in the user defined power-save whitelist or not.
     */
    public boolean isUidPowerSaveUserWhitelisted(int uid) {
        synchronized (mLock) {
            return ArrayUtils.contains(mPowerWhitelistedUserAppIds, UserHandle.getAppId(uid));
        }
    }

    /**
     * @return whether a UID is in the temp power-save whitelist or not.
     *
     * Note clients normally shouldn't need to access it. It's only for dumpsys.
     */
    public boolean isUidTempPowerSaveWhitelisted(int uid) {
        synchronized (mLock) {
            return ArrayUtils.contains(mTempWhitelistedAppIds, UserHandle.getAppId(uid));
        }
    }

    @Deprecated
    public void dump(PrintWriter pw, String prefix) {
        dump(new IndentingPrintWriter(pw, "  ").setIndent(prefix));
    }

    public void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
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

            pw.print("Foreground uids: ");
            dumpUids(pw, mForegroundUids);

            pw.print("Except-idle + user whitelist appids: ");
            pw.println(Arrays.toString(mPowerWhitelistedAllAppIds));

            pw.print("User whitelist appids: ");
            pw.println(Arrays.toString(mPowerWhitelistedUserAppIds));

            pw.print("Temp whitelist appids: ");
            pw.println(Arrays.toString(mTempWhitelistedAppIds));

            pw.println("Exempted packages:");
            pw.increaseIndent();
            for (int i = 0; i < mExemptedPackages.size(); i++) {
                pw.print("User ");
                pw.print(mExemptedPackages.keyAt(i));
                pw.println();

                pw.increaseIndent();
                for (int j = 0; j < mExemptedPackages.sizeAt(i); j++) {
                    pw.print(mExemptedPackages.valueAt(i, j));
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

    public void dumpProto(ProtoOutputStream proto, long fieldId) {
        synchronized (mLock) {
            final long token = proto.start(fieldId);

            proto.write(ForceAppStandbyTrackerProto.FORCE_ALL_APPS_STANDBY, mForceAllAppsStandby);
            proto.write(ForceAppStandbyTrackerProto.IS_SMALL_BATTERY_DEVICE,
                    isSmallBatteryDevice());
            proto.write(ForceAppStandbyTrackerProto.FORCE_ALL_APPS_STANDBY_FOR_SMALL_BATTERY,
                    mForceAllAppStandbyForSmallBattery);
            proto.write(ForceAppStandbyTrackerProto.IS_PLUGGED_IN, mIsPluggedIn);

            for (int i = 0; i < mActiveUids.size(); i++) {
                if (mActiveUids.valueAt(i)) {
                    proto.write(ForceAppStandbyTrackerProto.ACTIVE_UIDS,
                            mActiveUids.keyAt(i));
                }
            }

            for (int i = 0; i < mForegroundUids.size(); i++) {
                if (mForegroundUids.valueAt(i)) {
                    proto.write(ForceAppStandbyTrackerProto.FOREGROUND_UIDS,
                            mForegroundUids.keyAt(i));
                }
            }

            for (int appId : mPowerWhitelistedAllAppIds) {
                proto.write(ForceAppStandbyTrackerProto.POWER_SAVE_WHITELIST_APP_IDS, appId);
            }

            for (int appId : mPowerWhitelistedUserAppIds) {
                proto.write(ForceAppStandbyTrackerProto.POWER_SAVE_USER_WHITELIST_APP_IDS, appId);
            }

            for (int appId : mTempWhitelistedAppIds) {
                proto.write(ForceAppStandbyTrackerProto.TEMP_POWER_SAVE_WHITELIST_APP_IDS, appId);
            }

            for (int i = 0; i < mExemptedPackages.size(); i++) {
                for (int j = 0; j < mExemptedPackages.sizeAt(i); j++) {
                    final long token2 = proto.start(
                            ForceAppStandbyTrackerProto.EXEMPTED_PACKAGES);

                    proto.write(ExemptedPackage.USER_ID, mExemptedPackages.keyAt(i));
                    proto.write(ExemptedPackage.PACKAGE_NAME, mExemptedPackages.valueAt(i, j));

                    proto.end(token2);
                }
            }

            for (Pair<Integer, String> uidAndPackage : mRunAnyRestrictedPackages) {
                final long token2 = proto.start(
                        ForceAppStandbyTrackerProto.RUN_ANY_IN_BACKGROUND_RESTRICTED_PACKAGES);
                proto.write(RunAnyInBackgroundRestrictedPackages.UID, uidAndPackage.first);
                proto.write(RunAnyInBackgroundRestrictedPackages.PACKAGE_NAME,
                        uidAndPackage.second);
                proto.end(token2);
            }

            mStatLogger.dumpProto(proto, ForceAppStandbyTrackerProto.STATS);

            proto.end(token);
        }
    }
}
