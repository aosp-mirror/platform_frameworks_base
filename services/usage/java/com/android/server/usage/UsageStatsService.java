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
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.IUidObserver;
import android.app.PendingIntent;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.usage.AppStandbyInfo;
import android.app.usage.ConfigurationStats;
import android.app.usage.EventStats;
import android.app.usage.IUsageStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManager.StandbyBuckets;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A service that collects, aggregates, and persists application usage data.
 * This data can be queried by apps that have been granted permission by AppOps.
 */
public class UsageStatsService extends SystemService implements
        UserUsageStatsService.StatsUpdatedListener {

    static final String TAG = "UsageStatsService";
    public static final boolean ENABLE_TIME_CHANGE_CORRECTION
            = SystemProperties.getBoolean("persist.debug.time_correction", true);

    static final boolean DEBUG = false; // Never submit with true
    static final boolean COMPRESS_TIME = false;

    private static final long TEN_SECONDS = 10 * 1000;
    private static final long TWENTY_MINUTES = 20 * 60 * 1000;
    private static final long FLUSH_INTERVAL = COMPRESS_TIME ? TEN_SECONDS : TWENTY_MINUTES;
    private static final long TIME_CHANGE_THRESHOLD_MILLIS = 2 * 1000; // Two seconds.

    private static final boolean ENABLE_KERNEL_UPDATES = true;
    private static final File KERNEL_COUNTER_FILE = new File("/proc/uid_procstat/set");

    // Handler message types.
    static final int MSG_REPORT_EVENT = 0;
    static final int MSG_FLUSH_TO_DISK = 1;
    static final int MSG_REMOVE_USER = 2;
    static final int MSG_UID_STATE_CHANGED = 3;

    private final Object mLock = new Object();
    Handler mHandler;
    AppOpsManager mAppOps;
    UserManager mUserManager;
    PackageManager mPackageManager;
    PackageManagerInternal mPackageManagerInternal;
    PackageMonitor mPackageMonitor;
    IDeviceIdleController mDeviceIdleController;
    DevicePolicyManagerInternal mDpmInternal;

    private final SparseArray<UserUsageStatsService> mUserState = new SparseArray<>();
    private final SparseIntArray mUidToKernelCounter = new SparseIntArray();
    private File mUsageStatsDir;
    long mRealTimeSnapshot;
    long mSystemTimeSnapshot;

    /** Manages the standby state of apps. */
    AppStandbyController mAppStandby;

    /** Manages app time limit observers */
    AppTimeLimitController mAppTimeLimit;

    private UsageStatsManagerInternal.AppIdleStateChangeListener mStandbyChangeListener =
            new UsageStatsManagerInternal.AppIdleStateChangeListener() {
                @Override
                public void onAppIdleStateChanged(String packageName, int userId, boolean idle,
                        int bucket, int reason) {
                    Event event = new Event();
                    event.mEventType = Event.STANDBY_BUCKET_CHANGED;
                    event.mBucketAndReason = (bucket << 16) | (reason & 0xFFFF);
                    event.mPackage = packageName;
                    // This will later be converted to system time.
                    event.mTimeStamp = SystemClock.elapsedRealtime();
                    mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
                }

                @Override
                public void onParoleStateChanged(boolean isParoleOn) {

                }
            };

    public UsageStatsService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mUserManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
        mPackageManager = getContext().getPackageManager();
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mDpmInternal = LocalServices.getService(DevicePolicyManagerInternal.class);
        mHandler = new H(BackgroundThread.get().getLooper());

        mAppStandby = new AppStandbyController(getContext(), BackgroundThread.get().getLooper());

        mAppTimeLimit = new AppTimeLimitController(
                (observerId, userId, timeLimit, timeElapsed, callbackIntent) -> {
                    Intent intent = new Intent();
                    intent.putExtra(UsageStatsManager.EXTRA_OBSERVER_ID, observerId);
                    intent.putExtra(UsageStatsManager.EXTRA_TIME_LIMIT, timeLimit);
                    intent.putExtra(UsageStatsManager.EXTRA_TIME_USED, timeElapsed);
                    try {
                        callbackIntent.send(getContext(), 0, intent);
                    } catch (PendingIntent.CanceledException e) {
                        Slog.w(TAG, "Couldn't deliver callback: "
                                + callbackIntent);
                    }
                }, mHandler.getLooper());

        mAppStandby.addListener(mStandbyChangeListener);
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

        synchronized (mLock) {
            cleanUpRemovedUsersLocked();
        }

        mRealTimeSnapshot = SystemClock.elapsedRealtime();
        mSystemTimeSnapshot = System.currentTimeMillis();

        publishLocalService(UsageStatsManagerInternal.class, new LocalService());
        publishBinderService(Context.USAGE_STATS_SERVICE, new BinderService());
        // Make sure we initialize the data, in case job scheduler needs it early.
        getUserDataAndInitializeIfNeededLocked(UserHandle.USER_SYSTEM, mSystemTimeSnapshot);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            mAppStandby.onBootPhase(phase);

            mDeviceIdleController = IDeviceIdleController.Stub.asInterface(
                    ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));

            if (ENABLE_KERNEL_UPDATES && KERNEL_COUNTER_FILE.exists()) {
                try {
                    ActivityManager.getService().registerUidObserver(mUidObserver,
                            ActivityManager.UID_OBSERVER_PROCSTATE
                                    | ActivityManager.UID_OBSERVER_GONE,
                            ActivityManager.PROCESS_STATE_UNKNOWN, null);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            } else {
                Slog.w(TAG, "Missing procfs interface: " + KERNEL_COUNTER_FILE);
            }
        }
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
                    mAppStandby.postCheckIdleStates(userId);
                }
            }
        }
    }

    private final IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq) {
            mHandler.obtainMessage(MSG_UID_STATE_CHANGED, uid, procState).sendToTarget();
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) {
            // Ignored
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            onUidStateChanged(uid, ActivityManager.PROCESS_STATE_NONEXISTENT, 0);
        }

        @Override
        public void onUidActive(int uid) {
            // Ignored
        }

        @Override public void onUidCachedChanged(int uid, boolean cached) {
        }
    };

    @Override
    public void onStatsUpdated() {
        mHandler.sendEmptyMessageDelayed(MSG_FLUSH_TO_DISK, FLUSH_INTERVAL);
    }

    @Override
    public void onStatsReloaded() {
        mAppStandby.postOneTimeCheckIdleStates();
    }

    @Override
    public void onNewUpdate(int userId) {
        mAppStandby.initializeDefaultsForSystemApps(userId);
    }

    private boolean shouldObfuscateInstantAppsForCaller(int callingUid, int userId) {
        return !mPackageManagerInternal.canAccessInstantApps(callingUid, userId);
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
        if (Math.abs(diffSystemTime) > TIME_CHANGE_THRESHOLD_MILLIS
                && ENABLE_TIME_CHANGE_CORRECTION) {
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

            if (event.getPackageName() != null
                    && mPackageManagerInternal.isPackageEphemeral(userId, event.getPackageName())) {
                event.mFlags |= Event.FLAG_IS_PACKAGE_INSTANT_APP;
            }

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            service.reportEvent(event);

            mAppStandby.reportEvent(event, elapsedRealtime, userId);
            switch (event.mEventType) {
                case Event.MOVE_TO_FOREGROUND:
                    mAppTimeLimit.moveToForeground(event.getPackageName(), event.getClassName(),
                            userId);
                    break;
                case Event.MOVE_TO_BACKGROUND:
                    mAppTimeLimit.moveToBackground(event.getPackageName(), event.getClassName(),
                            userId);
                    break;
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
            mAppStandby.onUserRemoved(userId);
            mAppTimeLimit.onUserRemoved(userId);
            cleanUpRemovedUsersLocked();
        }
    }

    /**
     * Called by the Binder stub.
     */
    List<UsageStats> queryUsageStats(int userId, int bucketType, long beginTime, long endTime,
            boolean obfuscateInstantApps) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            List<UsageStats> list = service.queryUsageStats(bucketType, beginTime, endTime);
            if (list == null) {
                return null;
            }

            // Mangle instant app names *using their current state (not whether they were ephemeral
            // when the data was recorded)*.
            if (obfuscateInstantApps) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    final UsageStats stats = list.get(i);
                    if (mPackageManagerInternal.isPackageEphemeral(userId, stats.mPackageName)) {
                        list.set(i, stats.getObfuscatedForInstantApp());
                    }
                }
            }

            return list;
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
    List<EventStats> queryEventStats(int userId, int bucketType, long beginTime,
            long endTime) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryEventStats(bucketType, beginTime, endTime);
        }
    }

    /**
     * Called by the Binder stub.
     */
    UsageEvents queryEvents(int userId, long beginTime, long endTime,
            boolean shouldObfuscateInstantApps) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryEvents(beginTime, endTime, shouldObfuscateInstantApps);
        }
    }

    /**
     * Called by the Binder stub.
     */
    UsageEvents queryEventsForPackage(int userId, long beginTime, long endTime,
            String packageName) {
        synchronized (mLock) {
            final long timeNow = checkAndGetTimeLocked();
            if (!validRange(timeNow, beginTime, endTime)) {
                return null;
            }

            final UserUsageStatsService service =
                    getUserDataAndInitializeIfNeededLocked(userId, timeNow);
            return service.queryEventsForPackage(beginTime, endTime, packageName);
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
            mAppStandby.flushToDisk(mUserState.keyAt(i));
        }
        mAppStandby.flushDurationsToDisk();

        mHandler.removeMessages(MSG_FLUSH_TO_DISK);
    }

    /**
     * Called by the Binder stub.
     */
    void dump(String[] args, PrintWriter pw) {
        synchronized (mLock) {
            IndentingPrintWriter idpw = new IndentingPrintWriter(pw, "  ");

            boolean checkin = false;
            boolean compact = false;
            String pkg = null;

            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    if ("--checkin".equals(arg)) {
                        checkin = true;
                    } else
                    if ("-c".equals(arg)) {
                        compact = true;
                    } else if ("flush".equals(arg)) {
                        flushToDiskLocked();
                        pw.println("Flushed stats to disk");
                        return;
                    } else if (arg != null && !arg.startsWith("-")) {
                        // Anything else that doesn't start with '-' is a pkg to filter
                        pkg = arg;
                        break;
                    }
                }
            }

            final int userCount = mUserState.size();
            for (int i = 0; i < userCount; i++) {
                int userId = mUserState.keyAt(i);
                idpw.printPair("user", userId);
                idpw.println();
                idpw.increaseIndent();
                if (checkin) {
                    mUserState.valueAt(i).checkin(idpw);
                } else {
                    mUserState.valueAt(i).dump(idpw, pkg, compact);
                    idpw.println();
                }
                mAppStandby.dumpUser(idpw, userId, pkg);
                idpw.decreaseIndent();
            }

            if (pkg == null) {
                pw.println();
                mAppStandby.dumpState(args, pw);
            }

            mAppTimeLimit.dump(pw);
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

                case MSG_UID_STATE_CHANGED: {
                    final int uid = msg.arg1;
                    final int procState = msg.arg2;

                    final int newCounter = (procState <= ActivityManager.PROCESS_STATE_TOP) ? 0 : 1;
                    synchronized (mUidToKernelCounter) {
                        final int oldCounter = mUidToKernelCounter.get(uid, 0);
                        if (newCounter != oldCounter) {
                            mUidToKernelCounter.put(uid, newCounter);
                            try {
                                FileUtils.stringToFile(KERNEL_COUNTER_FILE, uid + " " + newCounter);
                            } catch (IOException e) {
                                Slog.w(TAG, "Failed to update counter set: " + e);
                            }
                        }
                    }
                    break;
                }

                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    private final class BinderService extends IUsageStatsManager.Stub {

        private boolean hasPermission(String callingPackage) {
            final int callingUid = Binder.getCallingUid();
            if (callingUid == Process.SYSTEM_UID) {
                return true;
            }
            final int mode = mAppOps.noteOp(AppOpsManager.OP_GET_USAGE_STATS,
                    callingUid, callingPackage);
            if (mode == AppOpsManager.MODE_DEFAULT) {
                // The default behavior here is to check if PackageManager has given the app
                // permission.
                return getContext().checkCallingPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        }

        private boolean hasObserverPermission(String callingPackage) {
            final int callingUid = Binder.getCallingUid();
            if (callingUid == Process.SYSTEM_UID
                    || (mDpmInternal != null
                        && mDpmInternal.isActiveAdminWithPolicy(callingUid,
                            DeviceAdminInfo.USES_POLICY_PROFILE_OWNER))) {
                // Caller is the system or the profile owner, so proceed.
                return true;
            }
            return getContext().checkCallingPermission(Manifest.permission.OBSERVE_APP_USAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public ParceledListSlice<UsageStats> queryUsageStats(int bucketType, long beginTime,
                long endTime, String callingPackage) {
            if (!hasPermission(callingPackage)) {
                return null;
            }

            final boolean obfuscateInstantApps = shouldObfuscateInstantAppsForCaller(
                    Binder.getCallingUid(), UserHandle.getCallingUserId());

            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                final List<UsageStats> results = UsageStatsService.this.queryUsageStats(
                        userId, bucketType, beginTime, endTime, obfuscateInstantApps);
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
        public ParceledListSlice<EventStats> queryEventStats(int bucketType,
                long beginTime, long endTime, String callingPackage) throws RemoteException {
            if (!hasPermission(callingPackage)) {
                return null;
            }

            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                final List<EventStats> results =
                        UsageStatsService.this.queryEventStats(userId, bucketType,
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

            final boolean obfuscateInstantApps = shouldObfuscateInstantAppsForCaller(
                    Binder.getCallingUid(), UserHandle.getCallingUserId());

            final int userId = UserHandle.getCallingUserId();
            final long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.queryEvents(userId, beginTime, endTime,
                        obfuscateInstantApps);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public UsageEvents queryEventsForPackage(long beginTime, long endTime,
                String callingPackage) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);

            if (mPackageManagerInternal.getPackageUid(callingPackage, PackageManager.MATCH_ANY_USER,
                    callingUserId) != callingUid) {
                throw new SecurityException("Calling uid " + callingPackage + " cannot query events"
                        + "for package " + callingPackage);
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.queryEventsForPackage(callingUserId, beginTime,
                        endTime, callingPackage);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isAppInactive(String packageName, int userId) {
            try {
                userId = ActivityManager.getService().handleIncomingUser(Binder.getCallingPid(),
                        Binder.getCallingUid(), userId, false, false, "isAppInactive", null);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
            final boolean obfuscateInstantApps = shouldObfuscateInstantAppsForCaller(
                    Binder.getCallingUid(), userId);
            final long token = Binder.clearCallingIdentity();
            try {
                return mAppStandby.isAppIdleFilteredOrParoled(
                        packageName, userId,
                        SystemClock.elapsedRealtime(), obfuscateInstantApps);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setAppInactive(String packageName, boolean idle, int userId) {
            final int callingUid = Binder.getCallingUid();
            try {
                userId = ActivityManager.getService().handleIncomingUser(
                        Binder.getCallingPid(), callingUid, userId, false, true,
                        "setAppInactive", null);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
            getContext().enforceCallingPermission(Manifest.permission.CHANGE_APP_IDLE_STATE,
                    "No permission to change app idle state");
            final long token = Binder.clearCallingIdentity();
            try {
                final int appId = mAppStandby.getAppId(packageName);
                if (appId < 0) return;
                mAppStandby.setAppIdleAsync(packageName, idle, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int getAppStandbyBucket(String packageName, String callingPackage, int userId) {
            final int callingUid = Binder.getCallingUid();
            try {
                userId = ActivityManager.getService().handleIncomingUser(
                        Binder.getCallingPid(), callingUid, userId, false, false,
                        "getAppStandbyBucket", null);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
            final int packageUid = mPackageManagerInternal.getPackageUid(packageName,
                    PackageManager.MATCH_ANY_USER, userId);
            // If the calling app is asking about itself, continue, else check for permission.
            if (packageUid != callingUid) {
                if (!hasPermission(callingPackage)) {
                    throw new SecurityException(
                            "Don't have permission to query app standby bucket");
                }
            }
            if (packageUid < 0) {
                throw new IllegalArgumentException(
                        "Cannot get standby bucket for non existent package (" + packageName + ")");
            }
            final boolean obfuscateInstantApps = shouldObfuscateInstantAppsForCaller(callingUid,
                    userId);
            final long token = Binder.clearCallingIdentity();
            try {
                return mAppStandby.getAppStandbyBucket(packageName, userId,
                        SystemClock.elapsedRealtime(), obfuscateInstantApps);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setAppStandbyBucket(String packageName,
                int bucket, int userId) {
            getContext().enforceCallingPermission(Manifest.permission.CHANGE_APP_IDLE_STATE,
                    "No permission to change app standby state");

            if (bucket < UsageStatsManager.STANDBY_BUCKET_ACTIVE
                    || bucket > UsageStatsManager.STANDBY_BUCKET_NEVER) {
                throw new IllegalArgumentException("Cannot set the standby bucket to " + bucket);
            }
            final int callingUid = Binder.getCallingUid();
            try {
                userId = ActivityManager.getService().handleIncomingUser(
                        Binder.getCallingPid(), callingUid, userId, false, true,
                        "setAppStandbyBucket", null);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
            final boolean systemCaller = UserHandle.isCore(callingUid);
            final int reason = systemCaller
                    ? UsageStatsManager.REASON_MAIN_FORCED
                    : UsageStatsManager.REASON_MAIN_PREDICTED;
            final long token = Binder.clearCallingIdentity();
            try {
                final int packageUid = mPackageManagerInternal.getPackageUid(packageName,
                        PackageManager.MATCH_ANY_USER, userId);
                // Caller cannot set their own standby state
                if (packageUid == callingUid) {
                    throw new IllegalArgumentException("Cannot set your own standby bucket");
                }
                if (packageUid < 0) {
                    throw new IllegalArgumentException(
                            "Cannot set standby bucket for non existent package (" + packageName
                                    + ")");
                }
                mAppStandby.setAppStandbyBucket(packageName, userId, bucket, reason,
                        SystemClock.elapsedRealtime());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public ParceledListSlice<AppStandbyInfo> getAppStandbyBuckets(String callingPackageName,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            try {
                userId = ActivityManager.getService().handleIncomingUser(
                        Binder.getCallingPid(), callingUid, userId, false, false,
                        "getAppStandbyBucket", null);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
            if (!hasPermission(callingPackageName)) {
                throw new SecurityException(
                        "Don't have permission to query app standby bucket");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                final List<AppStandbyInfo> standbyBucketList =
                        mAppStandby.getAppStandbyBuckets(userId);
                return (standbyBucketList == null) ? ParceledListSlice.emptyList()
                        : new ParceledListSlice<>(standbyBucketList);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setAppStandbyBuckets(ParceledListSlice appBuckets, int userId) {
            getContext().enforceCallingPermission(Manifest.permission.CHANGE_APP_IDLE_STATE,
                    "No permission to change app standby state");

            final int callingUid = Binder.getCallingUid();
            try {
                userId = ActivityManager.getService().handleIncomingUser(
                        Binder.getCallingPid(), callingUid, userId, false, true,
                        "setAppStandbyBucket", null);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
            final boolean shellCaller = callingUid == 0 || callingUid == Process.SHELL_UID;
            final int reason = shellCaller
                    ? UsageStatsManager.REASON_MAIN_FORCED
                    : UsageStatsManager.REASON_MAIN_PREDICTED;
            final long token = Binder.clearCallingIdentity();
            try {
                final long elapsedRealtime = SystemClock.elapsedRealtime();
                List<AppStandbyInfo> bucketList = appBuckets.getList();
                for (AppStandbyInfo bucketInfo : bucketList) {
                    final String packageName = bucketInfo.mPackageName;
                    final int bucket = bucketInfo.mStandbyBucket;
                    if (bucket < UsageStatsManager.STANDBY_BUCKET_ACTIVE
                            || bucket > UsageStatsManager.STANDBY_BUCKET_NEVER) {
                        throw new IllegalArgumentException(
                                "Cannot set the standby bucket to " + bucket);
                    }
                    // Caller cannot set their own standby state
                    if (mPackageManagerInternal.getPackageUid(packageName,
                            PackageManager.MATCH_ANY_USER, userId) == callingUid) {
                        throw new IllegalArgumentException("Cannot set your own standby bucket");
                    }
                    mAppStandby.setAppStandbyBucket(packageName, userId, bucket, reason,
                            elapsedRealtime);
                }
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
            mAppStandby.clearCarrierPrivilegedApps();
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, pw)) return;
            UsageStatsService.this.dump(args, pw);
        }

        @Override
        public void reportChooserSelection(String packageName, int userId, String contentType,
                                           String[] annotations, String action) {
            if (packageName == null) {
                Slog.w(TAG, "Event report user selecting a null package");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = packageName;

            // This will later be converted to system time.
            event.mTimeStamp = SystemClock.elapsedRealtime();

            event.mEventType = Event.CHOOSER_ACTION;

            event.mAction = action;

            event.mContentType = contentType;

            event.mContentAnnotations = annotations;

            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
        }

        @Override
        public void registerAppUsageObserver(int observerId,
                String[] packages, long timeLimitMs, PendingIntent
                callbackIntent, String callingPackage) {
            if (!hasObserverPermission(callingPackage)) {
                throw new SecurityException("Caller doesn't have OBSERVE_APP_USAGE permission");
            }

            if (packages == null || packages.length == 0) {
                throw new IllegalArgumentException("Must specify at least one package");
            }
            if (timeLimitMs <= 0) {
                throw new IllegalArgumentException("Time limit must be > 0");
            }
            if (callbackIntent == null) {
                throw new NullPointerException("callbackIntent can't be null");
            }
            final int callingUid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(callingUid);
            final long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.registerAppUsageObserver(callingUid, observerId,
                        packages, timeLimitMs, callbackIntent, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void unregisterAppUsageObserver(int observerId, String callingPackage) {
            if (!hasObserverPermission(callingPackage)) {
                throw new SecurityException("Caller doesn't have OBSERVE_APP_USAGE permission");
            }

            final int callingUid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(callingUid);
            final long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.unregisterAppUsageObserver(callingUid, observerId, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    void registerAppUsageObserver(int callingUid, int observerId, String[] packages,
            long timeLimitMs, PendingIntent callbackIntent, int userId) {
        mAppTimeLimit.addObserver(callingUid, observerId, packages, timeLimitMs, callbackIntent,
                userId);
    }

    void unregisterAppUsageObserver(int callingUid, int observerId, int userId) {
        mAppTimeLimit.removeObserver(callingUid, observerId, userId);
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
        public void reportInterruptiveNotification(String packageName, String channelId,
                int userId) {
            if (packageName == null || channelId == null) {
                Slog.w(TAG, "Event reported without a package name or a channel ID");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = packageName.intern();
            event.mNotificationChannelId = channelId.intern();

            // This will later be converted to system time.
            event.mTimeStamp = SystemClock.elapsedRealtime();

            event.mEventType = Event.NOTIFICATION_INTERRUPTION;
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
        }

        @Override
        public void reportShortcutUsage(String packageName, String shortcutId, int userId) {
            if (packageName == null || shortcutId == null) {
                Slog.w(TAG, "Event reported without a package name or a shortcut ID");
                return;
            }

            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = packageName.intern();
            event.mShortcutId = shortcutId.intern();

            // This will later be converted to system time.
            event.mTimeStamp = SystemClock.elapsedRealtime();

            event.mEventType = Event.SHORTCUT_INVOCATION;
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
        }

        @Override
        public void reportContentProviderUsage(String name, String packageName, int userId) {
            mAppStandby.postReportContentProviderUsage(name, packageName, userId);
        }

        @Override
        public boolean isAppIdle(String packageName, int uidForAppId, int userId) {
            return mAppStandby.isAppIdleFiltered(packageName, uidForAppId,
                    userId, SystemClock.elapsedRealtime());
        }

        @Override
        @StandbyBuckets public int getAppStandbyBucket(String packageName, int userId,
                long nowElapsed) {
            return mAppStandby.getAppStandbyBucket(packageName, userId, nowElapsed, false);
        }

        @Override
        public int[] getIdleUidsForUser(int userId) {
            return mAppStandby.getIdleUidsForUser(userId);
        }

        @Override
        public boolean isAppIdleParoleOn() {
            return mAppStandby.isParoledOrCharging();
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
            mAppStandby.addListener(listener);
            listener.onParoleStateChanged(isAppIdleParoleOn());
        }

        @Override
        public void removeAppIdleStateChangeListener(
                AppIdleStateChangeListener listener) {
            mAppStandby.removeListener(listener);
        }

        @Override
        public byte[] getBackupPayload(int user, String key) {
            // Check to ensure that only user 0's data is b/r for now
            synchronized (mLock) {
                if (user == UserHandle.USER_SYSTEM) {
                    final UserUsageStatsService userStats =
                            getUserDataAndInitializeIfNeededLocked(user, checkAndGetTimeLocked());
                    return userStats.getBackupPayload(key);
                } else {
                    return null;
                }
            }
        }

        @Override
        public void applyRestoredPayload(int user, String key, byte[] payload) {
            synchronized (mLock) {
                if (user == UserHandle.USER_SYSTEM) {
                    final UserUsageStatsService userStats =
                            getUserDataAndInitializeIfNeededLocked(user, checkAndGetTimeLocked());
                    userStats.applyRestoredPayload(key, payload);
                }
            }
        }

        @Override
        public List<UsageStats> queryUsageStatsForUser(
                int userId, int intervalType, long beginTime, long endTime,
                boolean obfuscateInstantApps) {
            return UsageStatsService.this.queryUsageStats(
                    userId, intervalType, beginTime, endTime, obfuscateInstantApps);
        }

        @Override
        public void setLastJobRunTime(String packageName, int userId, long elapsedRealtime) {
            mAppStandby.setLastJobRunTime(packageName, userId, elapsedRealtime);
        }

        @Override
        public long getTimeSinceLastJobRun(String packageName, int userId) {
            return mAppStandby.getTimeSinceLastJobRun(packageName, userId);
        }

        @Override
        public void reportAppJobState(String packageName, int userId,
                int numDeferredJobs, long timeSinceLastJobRun) {
        }

        @Override
        public void onActiveAdminAdded(String packageName, int userId) {
            mAppStandby.addActiveDeviceAdmin(packageName, userId);
        }

        @Override
        public void setActiveAdminApps(Set<String> packageNames, int userId) {
            mAppStandby.setActiveAdminApps(packageNames, userId);
        }

        @Override
        public void onAdminDataAvailable() {
            mAppStandby.onAdminDataAvailable();
        }

        @Override
        public void reportExemptedSyncStart(String packageName, int userId) {
            mAppStandby.postReportExemptedSyncStart(packageName, userId);
        }
    }
}
