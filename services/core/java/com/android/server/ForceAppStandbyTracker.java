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
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.server.ForceAppStandbyTrackerProto.RunAnyInBackgroundRestrictedPackages;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

/**
 * Class to keep track of the information related to "force app standby", which includes:
 * - OP_RUN_ANY_IN_BACKGROUND for each package
 * - UID foreground state
 * - User+system power save whitelist
 * - Temporary power save whitelist
 * - Global "force all apps standby" mode enforced by battery saver.
 *
 * TODO: In general, we can reduce the number of callbacks by checking all signals before sending
 * each callback. For example, even when an UID comes into the foreground, if it wasn't
 * originally restricted, then there's no need to send an event.
 * Doing this would be error-prone, so we punt it for now, but we should revisit it later.
 *
 * Test:
 * atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/ForceAppStandbyTrackerTest.java
 */
public class ForceAppStandbyTracker {
    private static final String TAG = "ForceAppStandbyTracker";
    private static final boolean DEBUG = false;

    @GuardedBy("ForceAppStandbyTracker.class")
    private static ForceAppStandbyTracker sInstance;

    private final Object mLock = new Object();
    private final Context mContext;

    @VisibleForTesting
    static final int TARGET_OP = AppOpsManager.OP_RUN_ANY_IN_BACKGROUND;

    IActivityManager mIActivityManager;
    AppOpsManager mAppOpsManager;
    IAppOpsService mAppOpsService;
    PowerManagerInternal mPowerManagerInternal;

    private final MyHandler mHandler;

    @VisibleForTesting
    FeatureFlagsObserver mFlagsObserver;

    /**
     * Pair of (uid (not user-id), packageName) with OP_RUN_ANY_IN_BACKGROUND *not* allowed.
     */
    @GuardedBy("mLock")
    final ArraySet<Pair<Integer, String>> mRunAnyRestrictedPackages = new ArraySet<>();

    @GuardedBy("mLock")
    final SparseBooleanArray mForegroundUids = new SparseBooleanArray();

    /**
     * System except-idle + user whitelist in the device idle controller.
     */
    @GuardedBy("mLock")
    private int[] mPowerWhitelistedAllAppIds = new int[0];

    @GuardedBy("mLock")
    private int[] mTempWhitelistedAppIds = mPowerWhitelistedAllAppIds;

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
            return Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.FORCED_APP_STANDBY_ENABLED, 1) == 1;
        }

        boolean isForcedAppStandbyForSmallBatteryEnabled() {
            return Settings.Global.getInt(mContext.getContentResolver(),
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
        private void onRunAnyAppOpsChanged(ForceAppStandbyTracker sender,
                int uid, @NonNull String packageName) {
            updateJobsForUidPackage(uid, packageName);

            if (!sender.areAlarmsRestricted(uid, packageName)) {
                unblockAlarmsForUidPackage(uid, packageName);
            }
        }

        /**
         * This is called when the foreground state changed for a UID.
         */
        private void onUidForegroundStateChanged(ForceAppStandbyTracker sender, int uid) {
            updateJobsForUid(uid);

            if (sender.isInForeground(uid)) {
                unblockAlarmsForUid(uid);
            }
        }

        /**
         * This is called when an app-id(s) is removed from the power save whitelist.
         */
        private void onPowerSaveUnwhitelisted(ForceAppStandbyTracker sender) {
            updateAllJobs();
            unblockAllUnrestrictedAlarms();
        }

        /**
         * This is called when the power save whitelist changes, excluding the
         * {@link #onPowerSaveUnwhitelisted} case.
         */
        private void onPowerSaveWhitelistedChanged(ForceAppStandbyTracker sender) {
            updateAllJobs();
        }

        /**
         * This is called when the temp whitelist changes.
         */
        private void onTempPowerSaveWhitelistChanged(ForceAppStandbyTracker sender) {

            // TODO This case happens rather frequently; consider optimizing and update jobs
            // only for affected app-ids.

            updateAllJobs();
        }

        /**
         * This is called when the global "force all apps standby" flag changes.
         */
        private void onForceAllAppsStandbyChanged(ForceAppStandbyTracker sender) {
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
        public void updateJobsForUid(int uid) {
        }

        /**
         * Called when the job restrictions for a UID - package might have changed, so the job
         * scheduler should re-evaluate all restrictions for all jobs.
         */
        public void updateJobsForUidPackage(int uid, String packageName) {
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
    }

    @VisibleForTesting
    ForceAppStandbyTracker(Context context, Looper looper) {
        mContext = context;
        mHandler = new MyHandler(looper);
    }

    private ForceAppStandbyTracker(Context context) {
        this(context, FgThread.get().getLooper());
    }

    /**
     * Get the singleton instance.
     */
    public static synchronized ForceAppStandbyTracker getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ForceAppStandbyTracker(context);
        }
        return sInstance;
    }

    /**
     * Call it when the system is ready.
     */
    public void start() {
        synchronized (mLock) {
            if (mStarted) {
                return;
            }
            mStarted = true;

            mIActivityManager = Preconditions.checkNotNull(injectIActivityManager());
            mAppOpsManager = Preconditions.checkNotNull(injectAppOpsManager());
            mAppOpsService = Preconditions.checkNotNull(injectIAppOpsService());
            mPowerManagerInternal = Preconditions.checkNotNull(injectPowerManagerInternal());
            mFlagsObserver = new FeatureFlagsObserver();
            mFlagsObserver.register();
            mForcedAppStandbyEnabled = mFlagsObserver.isForcedAppStandbyEnabled();
            mForceAllAppStandbyForSmallBattery =
                    mFlagsObserver.isForcedAppStandbyForSmallBatteryEnabled();

            try {
                mIActivityManager.registerUidObserver(new UidObserver(),
                        ActivityManager.UID_OBSERVER_GONE | ActivityManager.UID_OBSERVER_IDLE
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
    PowerManagerInternal injectPowerManagerInternal() {
        return LocalServices.getService(PowerManagerInternal.class);
    }

    @VisibleForTesting
    boolean isSmallBatteryDevice() {
        return ActivityManager.isSmallBatteryDevice();
    }

    /**
     * Update {@link #mRunAnyRestrictedPackages} with the current app ops state.
     */
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
    private void toggleForceAllAppsStandbyLocked(boolean enable) {
        if (enable == mForceAllAppsStandby) {
            return;
        }
        mForceAllAppsStandby = enable;

        mHandler.notifyForceAllAppsStandbyChanged();
    }

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
    boolean isRunAnyRestrictedLocked(int uid, @NonNull String packageName) {
        return findForcedAppStandbyUidPackageIndexLocked(uid, packageName) >= 0;
    }

    /**
     * Add to / remove from {@link #mRunAnyRestrictedPackages}.
     */
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

    /**
     * Puts a UID to {@link #mForegroundUids}.
     */
    void uidToForeground(int uid) {
        synchronized (mLock) {
            if (!UserHandle.isApp(uid)) {
                return;
            }
            // TODO This can be optimized by calling indexOfKey and sharing the index for get and
            // put.
            if (mForegroundUids.get(uid)) {
                return;
            }
            mForegroundUids.put(uid, true);
            mHandler.notifyUidForegroundStateChanged(uid);
        }
    }

    /**
     * Sets false for a UID {@link #mForegroundUids}, or remove it when {@code remove} is true.
     */
    void uidToBackground(int uid, boolean remove) {
        synchronized (mLock) {
            if (!UserHandle.isApp(uid)) {
                return;
            }
            // TODO This can be optimized by calling indexOfKey and sharing the index for get and
            // put.
            if (!mForegroundUids.get(uid)) {
                return;
            }
            if (remove) {
                mForegroundUids.delete(uid);
            } else {
                mForegroundUids.put(uid, false);
            }
            mHandler.notifyUidForegroundStateChanged(uid);
        }
    }

    private final class UidObserver extends IUidObserver.Stub {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq) {
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            uidToBackground(uid, /*remove=*/ true);
        }

        @Override
        public void onUidActive(int uid) {
            uidToForeground(uid);
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) {
            // Just to avoid excessive memcpy, don't remove from the array in this case.
            uidToBackground(uid, /*remove=*/ false);
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

    private Listener[] cloneListeners() {
        synchronized (mLock) {
            return mListeners.toArray(new Listener[mListeners.size()]);
        }
    }

    private class MyHandler extends Handler {
        private static final int MSG_UID_STATE_CHANGED = 1;
        private static final int MSG_RUN_ANY_CHANGED = 2;
        private static final int MSG_ALL_UNWHITELISTED = 3;
        private static final int MSG_ALL_WHITELIST_CHANGED = 4;
        private static final int MSG_TEMP_WHITELIST_CHANGED = 5;
        private static final int MSG_FORCE_ALL_CHANGED = 6;
        private static final int MSG_USER_REMOVED = 7;
        private static final int MSG_FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED = 8;

        public MyHandler(Looper looper) {
            super(looper);
        }

        public void notifyUidForegroundStateChanged(int uid) {
            obtainMessage(MSG_UID_STATE_CHANGED, uid, 0).sendToTarget();
        }

        public void notifyRunAnyAppOpsChanged(int uid, @NonNull String packageName) {
            obtainMessage(MSG_RUN_ANY_CHANGED, uid, 0, packageName).sendToTarget();
        }

        public void notifyAllUnwhitelisted() {
            obtainMessage(MSG_ALL_UNWHITELISTED).sendToTarget();
        }

        public void notifyAllWhitelistChanged() {
            obtainMessage(MSG_ALL_WHITELIST_CHANGED).sendToTarget();
        }

        public void notifyTempWhitelistChanged() {
            obtainMessage(MSG_TEMP_WHITELIST_CHANGED).sendToTarget();
        }

        public void notifyForceAllAppsStandbyChanged() {
            obtainMessage(MSG_FORCE_ALL_CHANGED).sendToTarget();
        }

        public void notifyForcedAppStandbyFeatureFlagChanged() {
            obtainMessage(MSG_FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED).sendToTarget();
        }

        public void doUserRemoved(int userId) {
            obtainMessage(MSG_USER_REMOVED, userId, 0).sendToTarget();
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
            final ForceAppStandbyTracker sender = ForceAppStandbyTracker.this;

            switch (msg.what) {
                case MSG_UID_STATE_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onUidForegroundStateChanged(sender, msg.arg1);
                    }
                    return;
                case MSG_RUN_ANY_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onRunAnyAppOpsChanged(sender, msg.arg1, (String) msg.obj);
                    }
                    return;
                case MSG_ALL_UNWHITELISTED:
                    for (Listener l : cloneListeners()) {
                        l.onPowerSaveUnwhitelisted(sender);
                    }
                    return;
                case MSG_ALL_WHITELIST_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onPowerSaveWhitelistedChanged(sender);
                    }
                    return;
                case MSG_TEMP_WHITELIST_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onTempPowerSaveWhitelistChanged(sender);
                    }
                    return;
                case MSG_FORCE_ALL_CHANGED:
                    for (Listener l : cloneListeners()) {
                        l.onForceAllAppsStandbyChanged(sender);
                    }
                    return;
                case MSG_FORCE_APP_STANDBY_FEATURE_FLAG_CHANGED:
                    // Feature flag for forced app standby changed.
                    final boolean unblockAlarms;
                    synchronized (mLock) {
                        unblockAlarms = !mForcedAppStandbyEnabled && !mForceAllAppsStandby;
                    }
                    for (Listener l: cloneListeners()) {
                        l.updateAllJobs();
                        if (unblockAlarms) {
                            l.unblockAllUnrestrictedAlarms();
                        }
                    }
                    return;
                case MSG_USER_REMOVED:
                    handleUserRemoved(msg.arg1);
                    return;
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
            for (int i = mForegroundUids.size() - 1; i >= 0; i--) {
                final int uid = mForegroundUids.keyAt(i);
                final int userId = UserHandle.getUserId(uid);

                if (userId == removedUserId) {
                    mForegroundUids.removeAt(i);
                }
            }
        }
    }

    /**
     * Called by device idle controller to update the power save whitelists.
     */
    public void setPowerSaveWhitelistAppIds(
            int[] powerSaveWhitelistAllAppIdArray, int[] tempWhitelistAppIdArray) {
        synchronized (mLock) {
            final int[] previousWhitelist = mPowerWhitelistedAllAppIds;
            final int[] previousTempWhitelist = mTempWhitelistedAppIds;

            mPowerWhitelistedAllAppIds = powerSaveWhitelistAllAppIdArray;
            mTempWhitelistedAppIds = tempWhitelistAppIdArray;

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
    public boolean areAlarmsRestricted(int uid, @NonNull String packageName) {
        return isRestricted(uid, packageName, /*useTempWhitelistToo=*/ false,
                /* exemptOnBatterySaver =*/ false);
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
     * @return whether force-app-standby is effective for a UID package-name.
     */
    private boolean isRestricted(int uid, @NonNull String packageName,
            boolean useTempWhitelistToo, boolean exemptOnBatterySaver) {
        if (isInForeground(uid)) {
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
            return mForceAllAppsStandby;
        }
    }

    /**
     * @return whether a UID is in the foreground or not.
     *
     * Note this information is based on the UID proc state callback, meaning it's updated
     * asynchronously and may subtly be stale. If the fresh data is needed, use
     * {@link ActivityManagerInternal#getUidProcessState} instead.
     */
    public boolean isInForeground(int uid) {
        if (!UserHandle.isApp(uid)) {
            return true;
        }
        synchronized (mLock) {
            return mForegroundUids.get(uid);
        }
    }

    /**
     * @return whether force all apps standby is enabled or not.
     *
     * Note clients normally shouldn't need to access it.
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
     * @return whether a UID is in the temp power-save whitelist or not.
     *
     * Note clients normally shouldn't need to access it. It's only for dumpsys.
     */
    public boolean isUidTempPowerSaveWhitelisted(int uid) {
        synchronized (mLock) {
            return ArrayUtils.contains(mTempWhitelistedAppIds, UserHandle.getAppId(uid));
        }
    }

    public void dump(PrintWriter pw, String indent) {
        synchronized (mLock) {
            pw.print(indent);
            pw.println("Forced App Standby Feature enabled: " + mForcedAppStandbyEnabled);

            pw.print(indent);
            pw.print("Force all apps standby: ");
            pw.println(isForceAllAppsStandbyEnabled());

            pw.print(indent);
            pw.print("Small Battery Device: ");
            pw.println(isSmallBatteryDevice());

            pw.print(indent);
            pw.print("Force all apps standby for small battery device: ");
            pw.println(mForceAllAppStandbyForSmallBattery);

            pw.print(indent);
            pw.print("Plugged In: ");
            pw.println(mIsPluggedIn);

            pw.print(indent);
            pw.print("Foreground uids: [");

            String sep = "";
            for (int i = 0; i < mForegroundUids.size(); i++) {
                if (mForegroundUids.valueAt(i)) {
                    pw.print(sep);
                    pw.print(UserHandle.formatUid(mForegroundUids.keyAt(i)));
                    sep = " ";
                }
            }
            pw.println("]");

            pw.print(indent);
            pw.print("Whitelist appids: ");
            pw.println(Arrays.toString(mPowerWhitelistedAllAppIds));

            pw.print(indent);
            pw.print("Temp whitelist appids: ");
            pw.println(Arrays.toString(mTempWhitelistedAppIds));

            pw.print(indent);
            pw.println("Restricted packages:");
            for (Pair<Integer, String> uidAndPackage : mRunAnyRestrictedPackages) {
                pw.print(indent);
                pw.print("  ");
                pw.print(UserHandle.formatUid(uidAndPackage.first));
                pw.print(" ");
                pw.print(uidAndPackage.second);
                pw.println();
            }
        }
    }

    public void dumpProto(ProtoOutputStream proto, long fieldId) {
        synchronized (mLock) {
            final long token = proto.start(fieldId);

            proto.write(ForceAppStandbyTrackerProto.FORCE_ALL_APPS_STANDBY, mForceAllAppsStandby);
            proto.write(ForceAppStandbyTrackerProto.IS_SMALL_BATTERY_DEVICE,
                    isSmallBatteryDevice());
            proto.write(ForceAppStandbyTrackerProto.FORCE_ALL_APPS_STANDBY_FOR_SMALL_BATTERY,
                    mForceAllAppStandbyForSmallBattery);
            proto.write(ForceAppStandbyTrackerProto.IS_CHARGING, mIsPluggedIn);

            for (int i = 0; i < mForegroundUids.size(); i++) {
                if (mForegroundUids.valueAt(i)) {
                    proto.write(ForceAppStandbyTrackerProto.FOREGROUND_UIDS,
                            mForegroundUids.keyAt(i));
                }
            }

            for (int appId : mPowerWhitelistedAllAppIds) {
                proto.write(ForceAppStandbyTrackerProto.POWER_SAVE_WHITELIST_APP_IDS, appId);
            }

            for (int appId : mTempWhitelistedAppIds) {
                proto.write(ForceAppStandbyTrackerProto.TEMP_POWER_SAVE_WHITELIST_APP_IDS, appId);
            }

            for (Pair<Integer, String> uidAndPackage : mRunAnyRestrictedPackages) {
                final long token2 = proto.start(
                        ForceAppStandbyTrackerProto.RUN_ANY_IN_BACKGROUND_RESTRICTED_PACKAGES);
                proto.write(RunAnyInBackgroundRestrictedPackages.UID, uidAndPackage.first);
                proto.write(RunAnyInBackgroundRestrictedPackages.PACKAGE_NAME,
                        uidAndPackage.second);
                proto.end(token2);
            }
            proto.end(token);
        }
    }
}
