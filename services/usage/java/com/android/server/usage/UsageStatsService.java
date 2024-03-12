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

import static android.app.usage.UsageEvents.Event.CHOOSER_ACTION;
import static android.app.usage.UsageEvents.Event.CONFIGURATION_CHANGE;
import static android.app.usage.UsageEvents.Event.DEVICE_EVENT_PACKAGE_NAME;
import static android.app.usage.UsageEvents.Event.DEVICE_SHUTDOWN;
import static android.app.usage.UsageEvents.Event.FLUSH_TO_DISK;
import static android.app.usage.UsageEvents.Event.LOCUS_ID_SET;
import static android.app.usage.UsageEvents.Event.NOTIFICATION_INTERRUPTION;
import static android.app.usage.UsageEvents.Event.SHORTCUT_INVOCATION;
import static android.app.usage.UsageEvents.Event.USER_INTERACTION;
import static android.app.usage.UsageEvents.Event.USER_STOPPED;
import static android.app.usage.UsageEvents.Event.USER_UNLOCKED;
import static android.app.usage.UsageStatsManager.USAGE_SOURCE_CURRENT_ACTIVITY;
import static android.app.usage.UsageStatsManager.USAGE_SOURCE_TASK_ROOT_ACTIVITY;
import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;

import android.Manifest;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManager.ProcessState;
import android.app.AppOpsManager;
import android.app.IUidObserver;
import android.app.PendingIntent;
import android.app.UidObserver;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.usage.AppLaunchEstimateInfo;
import android.app.usage.AppStandbyInfo;
import android.app.usage.BroadcastResponseStatsList;
import android.app.usage.ConfigurationStats;
import android.app.usage.EventStats;
import android.app.usage.Flags;
import android.app.usage.IUsageStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageEventsQuery;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStatsManager.StandbyBuckets;
import android.app.usage.UsageStatsManager.UsageSource;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.LocusId;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutServiceInternal;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseSetArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;
import com.android.server.utils.AlarmQueue;

import libcore.util.EmptyArray;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
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

    private static final boolean USE_DEDICATED_HANDLER_THREAD =
            SystemProperties.getBoolean("persist.debug.use_dedicated_handler_thread",
            Flags.useDedicatedHandlerThread());

    static final boolean DEBUG = false; // Never submit with true
    static final boolean DEBUG_RESPONSE_STATS = DEBUG || Log.isLoggable(TAG, Log.DEBUG);
    static final boolean COMPRESS_TIME = false;

    private static final long TEN_SECONDS = 10 * 1000;
    private static final long TWENTY_MINUTES = 20 * 60 * 1000;
    private static final long ONE_DAY = 24 * HOUR_IN_MILLIS;
    private static final long ONE_WEEK = 7 * ONE_DAY;
    private static final long FLUSH_INTERVAL = COMPRESS_TIME ? TEN_SECONDS : TWENTY_MINUTES;
    static final long TIME_CHANGE_THRESHOLD_MILLIS = 2 * 1000; // Two seconds.
    /**
     * Used when we can't determine the next app launch time. Assume the app will get launched
     * this amount of time in the future.
     */
    private static final long UNKNOWN_LAUNCH_TIME_DELAY_MS = 365 * ONE_DAY;

    private static final boolean ENABLE_KERNEL_UPDATES = true;
    private static final File KERNEL_COUNTER_FILE = new File("/proc/uid_procstat/set");

    // /data/system/usagestats.  Now only used for globalcomponentusage.  Previously per-user stats
    // were stored here too, but they've been moved to /data/system_ce/$userId/usagestats.
    private static final File COMMON_USAGE_STATS_DIR =
            new File(Environment.getDataSystemDirectory(), "usagestats");
    private static final File LEGACY_USER_USAGE_STATS_DIR = COMMON_USAGE_STATS_DIR;

    // /data/system_de/usagestats.  When the globalcomponentusage file was added, it was incorrectly
    // added here instead of in /data/system/usagestats where it should be.  We lazily migrate this
    // file by reading it from here if needed, and always writing it to the new path.  We don't
    // delete the old directory, as system_server no longer has permission to do so.
    //
    // Note, this migration is *not* related to the migration of the per-user stats from
    // /data/system/usagestats/$userId to /data/system_ce/$userId/usagestats mentioned above.  Both
    // of these just happen to involve /data/system/usagestats.  /data/system is the right place for
    // system data not tied to a user, but the wrong place for per-user data.  So due to two
    // separate mistakes, we've unfortunately ended up with one case where we need to move files out
    // of /data/system, and one case where we need to move a different file *into* /data/system.
    private static final File LEGACY_COMMON_USAGE_STATS_DIR =
            new File(Environment.getDataSystemDeDirectory(), "usagestats");

    private static final String GLOBAL_COMPONENT_USAGE_FILE_NAME = "globalcomponentusage";

    private static final char TOKEN_DELIMITER = '/';

    // The maximum length for extras {@link UsageStatsManager#EXTRA_EVENT_CATEGORY},
    // {@link UsageStatsManager#EXTRA_EVENT_ACTION} in a {@link UsageEvents.Event#mExtras}.
    // The value will be truncated at this limit.
    private static final int MAX_TEXT_LENGTH = 127;

    // Handler message types.
    static final int MSG_REPORT_EVENT = 0;
    static final int MSG_FLUSH_TO_DISK = 1;
    static final int MSG_REMOVE_USER = 2;
    static final int MSG_UID_STATE_CHANGED = 3;
    static final int MSG_REPORT_EVENT_TO_ALL_USERID = 4;
    static final int MSG_UNLOCKED_USER = 5;
    static final int MSG_PACKAGE_REMOVED = 6;
    static final int MSG_ON_START = 7;
    static final int MSG_HANDLE_LAUNCH_TIME_ON_USER_UNLOCK = 8;
    static final int MSG_NOTIFY_ESTIMATED_LAUNCH_TIMES_CHANGED = 9;
    static final int MSG_UID_REMOVED = 10;
    static final int MSG_USER_STARTED = 11;
    static final int MSG_NOTIFY_USAGE_EVENT_LISTENER = 12;

    private final Object mLock = new Object();
    private Handler mHandler;
    private Handler mIoHandler;
    AppOpsManager mAppOps;
    UserManager mUserManager;
    PackageManager mPackageManager;
    PackageManagerInternal mPackageManagerInternal;
    // Do not use directly. Call getDpmInternal() instead
    DevicePolicyManagerInternal mDpmInternal;
    // Do not use directly. Call getShortcutServiceInternal() instead
    ShortcutServiceInternal mShortcutServiceInternal;

    private final SparseArray<UserUsageStatsService> mUserState = new SparseArray<>();
    private final CopyOnWriteArraySet<Integer> mUserUnlockedStates = new CopyOnWriteArraySet<>();
    private final SparseIntArray mUidToKernelCounter = new SparseIntArray();
    int mUsageSource;

    private long mRealTimeSnapshot;
    private long mSystemTimeSnapshot;
    // A map storing last time global usage of packages, measured in milliseconds since the epoch.
    private final Map<String, Long> mLastTimeComponentUsedGlobal = new ArrayMap<>();

    /** Manages the standby state of apps. */
    AppStandbyInternal mAppStandby;

    /** Manages app time limit observers */
    AppTimeLimitController mAppTimeLimit;

    private final PackageMonitor mPackageMonitor = new MyPackageMonitor();

    // A map maintaining a queue of events to be reported per user.
    private final SparseArray<LinkedList<Event>> mReportedEvents = new SparseArray<>();
    final SparseArray<ArraySet<String>> mUsageReporters = new SparseArray();
    final SparseArray<ActivityData> mVisibleActivities = new SparseArray();
    @GuardedBy("mLaunchTimeAlarmQueues") // Don't hold the main lock
    private final SparseArray<LaunchTimeAlarmQueue> mLaunchTimeAlarmQueues = new SparseArray<>();
    @GuardedBy("mUsageEventListeners") // Don't hold the main lock when calling out
    private final ArraySet<UsageStatsManagerInternal.UsageEventListener> mUsageEventListeners =
            new ArraySet<>();
    private final CopyOnWriteArraySet<UsageStatsManagerInternal.EstimatedLaunchTimeChangedListener>
            mEstimatedLaunchTimeChangedListeners = new CopyOnWriteArraySet<>();
    @GuardedBy("mPendingLaunchTimeChangePackages")
    private final SparseSetArray<String> mPendingLaunchTimeChangePackages = new SparseSetArray<>();

    private BroadcastResponseStatsTracker mResponseStatsTracker;

    private static class ActivityData {
        private final String mTaskRootPackage;
        private final String mTaskRootClass;
        private final String mUsageSourcePackage;
        public int lastEvent = Event.NONE;

        private ActivityData(String taskRootPackage, String taskRootClass, String sourcePackage) {
            mTaskRootPackage = taskRootPackage;
            mTaskRootClass = taskRootClass;
            mUsageSourcePackage = sourcePackage;
        }
    }

    private AppIdleStateChangeListener mStandbyChangeListener =
            new AppIdleStateChangeListener() {
                @Override
                public void onAppIdleStateChanged(String packageName, int userId, boolean idle,
                        int bucket, int reason) {
                    Event event = new Event(Event.STANDBY_BUCKET_CHANGED,
                            SystemClock.elapsedRealtime());
                    event.mBucketAndReason = (bucket << 16) | (reason & 0xFFFF);
                    event.mPackage = packageName;
                    reportEventOrAddToQueue(userId, event);
                }
            };

    @VisibleForTesting
    static class Injector {
        AppStandbyInternal getAppStandbyController(Context context) {
            return AppStandbyInternal.newAppStandbyController(
                    UsageStatsService.class.getClassLoader(), context);
        }
    }

    private final Handler.Callback mIoHandlerCallback = (msg) -> {
        switch (msg.what) {
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
                return true;
            }
            case MSG_HANDLE_LAUNCH_TIME_ON_USER_UNLOCK: {
                final int userId = msg.arg1;
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER,
                        "usageStatsHandleEstimatedLaunchTimesOnUser(" + userId + ")");
                handleEstimatedLaunchTimesOnUserUnlock(userId);
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                return true;
            }
            case MSG_NOTIFY_USAGE_EVENT_LISTENER: {
                final int userId = msg.arg1;
                final Event event = (Event) msg.obj;
                synchronized (mUsageEventListeners) {
                    final int size = mUsageEventListeners.size();
                    for (int i = 0; i < size; ++i) {
                        mUsageEventListeners.valueAt(i).onUsageEvent(userId, event);
                    }
                }
                return true;
            }
        }
        return false;
    };

    private final Injector mInjector;

    public UsageStatsService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    UsageStatsService(Context context, Injector injector) {
        super(context);
        mInjector = injector;
    }

    @Override
    @SuppressLint("AndroidFrameworkRequiresPermission")
    public void onStart() {
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mUserManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
        mPackageManager = getContext().getPackageManager();
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mHandler = getUsageEventProcessingHandler();
        mIoHandler = new Handler(IoThread.get().getLooper(), mIoHandlerCallback);

        mAppStandby = mInjector.getAppStandbyController(getContext());
        mResponseStatsTracker = new BroadcastResponseStatsTracker(mAppStandby, getContext());

        mAppTimeLimit = new AppTimeLimitController(getContext(),
                new AppTimeLimitController.TimeLimitCallbackListener() {
                    @Override
                    public void onLimitReached(int observerId, int userId, long timeLimit,
                            long timeElapsed, PendingIntent callbackIntent) {
                        if (callbackIntent == null) return;
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
                    }

                    @Override
                    public void onSessionEnd(int observerId, int userId, long timeElapsed,
                            PendingIntent callbackIntent) {
                        if (callbackIntent == null) return;
                        Intent intent = new Intent();
                        intent.putExtra(UsageStatsManager.EXTRA_OBSERVER_ID, observerId);
                        intent.putExtra(UsageStatsManager.EXTRA_TIME_USED, timeElapsed);
                        try {
                            callbackIntent.send(getContext(), 0, intent);
                        } catch (PendingIntent.CanceledException e) {
                            Slog.w(TAG, "Couldn't deliver callback: "
                                    + callbackIntent);
                        }
                    }
                }, mHandler.getLooper());

        mAppStandby.addListener(mStandbyChangeListener);

        mPackageMonitor.register(getContext(), null, UserHandle.ALL, true);

        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_STARTED);
        getContext().registerReceiverAsUser(new UserActionsReceiver(), UserHandle.ALL, filter,
                null, /* scheduler= */ USE_DEDICATED_HANDLER_THREAD ? mHandler : null);

        getContext().registerReceiverAsUser(new UidRemovedReceiver(), UserHandle.ALL,
                new IntentFilter(ACTION_UID_REMOVED), null,
                /* scheduler= */ USE_DEDICATED_HANDLER_THREAD ? mHandler : null);

        mRealTimeSnapshot = SystemClock.elapsedRealtime();
        mSystemTimeSnapshot = System.currentTimeMillis();

        publishLocalService(UsageStatsManagerInternal.class, new LocalService());
        publishLocalService(AppStandbyInternal.class, mAppStandby);
        publishBinderServices();

        mHandler.obtainMessage(MSG_ON_START).sendToTarget();
    }

    @VisibleForTesting
    void publishBinderServices() {
        publishBinderService(Context.USAGE_STATS_SERVICE, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        mAppStandby.onBootPhase(phase);
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            // initialize mDpmInternal
            getDpmInternal();
            // initialize mShortcutServiceInternal
            getShortcutServiceInternal();
            mResponseStatsTracker.onSystemServicesReady(getContext());

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
            readUsageSourceSetting();
        }
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        // Create an entry in the user state map to indicate that the user has been started but
        // not necessarily unlocked. This will ensure that reported events are flushed to disk
        // event if the user is never unlocked (following the logic in #flushToDiskLocked)
        mUserState.put(user.getUserIdentifier(), null);
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        mHandler.obtainMessage(MSG_UNLOCKED_USER, user.getUserIdentifier(), 0).sendToTarget();
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        final int userId = user.getUserIdentifier();

        synchronized (mLock) {
            // User was started but never unlocked so no need to report a user stopped event
            if (!mUserUnlockedStates.contains(userId)) {
                persistPendingEventsLocked(userId);
                return;
            }

            // Report a user stopped event before persisting all stats to disk via the user service
            final Event event = new Event(USER_STOPPED, SystemClock.elapsedRealtime());
            event.mPackage = Event.DEVICE_EVENT_PACKAGE_NAME;
            reportEvent(event, userId);
            final UserUsageStatsService userService = mUserState.get(userId);
            if (userService != null) {
                userService.userStopped();
            }
            mUserUnlockedStates.remove(userId);
            mUserState.put(userId, null); // release the service (mainly for GC)
        }

        synchronized (mLaunchTimeAlarmQueues) {
            LaunchTimeAlarmQueue alarmQueue = mLaunchTimeAlarmQueues.get(userId);
            if (alarmQueue != null) {
                alarmQueue.removeAllAlarms();
                mLaunchTimeAlarmQueues.remove(userId);
            }
        }
    }

    private Handler getUsageEventProcessingHandler() {
        if (USE_DEDICATED_HANDLER_THREAD) {
            return new H(UsageStatsHandlerThread.get().getLooper());
        } else {
            return new H(BackgroundThread.get().getLooper());
        }
    }

    private void onUserUnlocked(int userId) {
        // fetch the installed packages outside the lock so it doesn't block package manager.
        final HashMap<String, Long> installedPackages = getInstalledPackages(userId);

        UsageStatsIdleService.scheduleUpdateMappingsJob(getContext(), userId);

        final boolean deleteObsoleteData = shouldDeleteObsoleteData(UserHandle.of(userId));
        synchronized (mLock) {
            // This should be safe to add this early. Other than reportEventOrAddToQueue and
            // getBackupPayload, every other user grabs the lock before accessing
            // mUserUnlockedStates. reportEventOrAddToQueue does not depend on anything other than
            // mUserUnlockedStates, and the lock will protect the handler.
            mUserUnlockedStates.add(userId);
            // Create a user unlocked event to report
            final Event unlockEvent = new Event(USER_UNLOCKED, SystemClock.elapsedRealtime());
            unlockEvent.mPackage = Event.DEVICE_EVENT_PACKAGE_NAME;

            migrateStatsToSystemCeIfNeededLocked(userId);

            // Read pending reported events from disk and merge them with those stored in memory
            final LinkedList<Event> pendingEvents = new LinkedList<>();
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "loadPendingEvents");
            loadPendingEventsLocked(userId, pendingEvents);
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            synchronized (mReportedEvents) {
                final LinkedList<Event> eventsInMem = mReportedEvents.get(userId);
                if (eventsInMem != null) {
                    pendingEvents.addAll(eventsInMem);
                    mReportedEvents.remove(userId);
                }
            }
            boolean needToFlush = !pendingEvents.isEmpty();

            initializeUserUsageStatsServiceLocked(userId, System.currentTimeMillis(),
                    installedPackages, deleteObsoleteData);
            final UserUsageStatsService userService = getUserUsageStatsServiceLocked(userId);
            if (userService == null) {
                Slog.i(TAG, "Attempted to unlock stopped or removed user " + userId);
                return;
            }

            // Process all the pending reported events
            while (pendingEvents.peek() != null) {
                reportEvent(pendingEvents.poll(), userId);
            }
            reportEvent(unlockEvent, userId);

            // Remove all the stats stored in system DE.
            deleteRecursively(new File(Environment.getDataSystemDeDirectory(userId), "usagestats"));

            // Force a flush to disk for the current user to ensure important events are persisted.
            // Note: there is a very very small chance that the system crashes between deleting
            // the stats above from DE and persisting them to CE here in which case we will lose
            // those events that were in memory and deleted from DE. (b/139836090)
            if (needToFlush) {
                userService.persistActiveStats();
            }
        }

        mIoHandler.obtainMessage(MSG_HANDLE_LAUNCH_TIME_ON_USER_UNLOCK, userId, 0).sendToTarget();
    }

    /**
     * Fetches a map (package_name:install_time) of installed packages for the given user. This
     * map contains all installed packages, including those packages which have been uninstalled
     * with the DELETE_KEEP_DATA flag.
     * This is a helper method which should only be called when the given user's usage stats service
     * is initialized; it performs a heavy query to package manager so do not call it otherwise.
     * <br/>
     * Note: DO NOT call this while holding the usage stats lock ({@code mLock}).
     */
    @Nullable
    private HashMap<String, Long> getInstalledPackages(int userId) {
        if (mPackageManager == null) {
            return null;
        }
        final List<PackageInfo> installedPackages = mPackageManager.getInstalledPackagesAsUser(
                PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
        final HashMap<String, Long> packagesMap = new HashMap<>();
        for (int i = installedPackages.size() - 1; i >= 0; i--) {
            final PackageInfo packageInfo = installedPackages.get(i);
            packagesMap.put(packageInfo.packageName, packageInfo.firstInstallTime);
        }
        return packagesMap;
    }

    private DevicePolicyManagerInternal getDpmInternal() {
        if (mDpmInternal == null) {
            mDpmInternal = LocalServices.getService(DevicePolicyManagerInternal.class);
        }
        return mDpmInternal;
    }

    private ShortcutServiceInternal getShortcutServiceInternal() {
        if (mShortcutServiceInternal == null) {
            mShortcutServiceInternal = LocalServices.getService(ShortcutServiceInternal.class);
        }
        return mShortcutServiceInternal;
    }

    private void readUsageSourceSetting() {
        synchronized (mLock) {
            mUsageSource = Settings.Global.getInt(getContext().getContentResolver(),
                    Settings.Global.APP_TIME_LIMIT_USAGE_SOURCE, USAGE_SOURCE_TASK_ROOT_ACTIVITY);
        }
    }

    private class LaunchTimeAlarmQueue extends AlarmQueue<String> {
        private final int mUserId;

        LaunchTimeAlarmQueue(int userId, @NonNull Context context, @NonNull Looper looper) {
            super(context, looper, "*usage.launchTime*", "Estimated launch times", true, 30_000L);
            mUserId = userId;
        }

        @Override
        protected boolean isForUser(@NonNull String key, int userId) {
            return mUserId == userId;
        }

        @Override
        protected void processExpiredAlarms(@NonNull ArraySet<String> expired) {
            if (DEBUG) {
                Slog.d(TAG, "Processing " + expired.size() + " expired alarms: "
                        + expired.toString());
            }
            if (expired.size() > 0) {
                synchronized (mPendingLaunchTimeChangePackages) {
                    mPendingLaunchTimeChangePackages.addAll(mUserId, expired);
                }
                mHandler.sendEmptyMessage(MSG_NOTIFY_ESTIMATED_LAUNCH_TIMES_CHANGED);
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
                if (userId >= 0) {
                    mHandler.obtainMessage(MSG_USER_STARTED, userId, 0).sendToTarget();
                }
            }
        }
    }

    private class UidRemovedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int uid = intent.getIntExtra(EXTRA_UID, -1);
            if (uid == -1) {
                return;
            }

            mHandler.obtainMessage(MSG_UID_REMOVED, uid, 0).sendToTarget();
        }
    }

    private final IUidObserver mUidObserver = new UidObserver() {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            mIoHandler.obtainMessage(MSG_UID_STATE_CHANGED, uid, procState).sendToTarget();
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            onUidStateChanged(uid, ActivityManager.PROCESS_STATE_NONEXISTENT, 0,
                    ActivityManager.PROCESS_CAPABILITY_NONE);
        }
    };

    @Override
    public void onStatsUpdated() {
        mHandler.sendEmptyMessageDelayed(MSG_FLUSH_TO_DISK, FLUSH_INTERVAL);
    }

    @Override
    public void onStatsReloaded() {
        // This method ends up being called with the lock held, so we need to be careful how we
        // call into other things.
        mAppStandby.postOneTimeCheckIdleStates();
    }

    @Override
    public void onNewUpdate(int userId) {
        mAppStandby.initializeDefaultsForSystemApps(userId);
    }

    private boolean sameApp(int callingUid, @UserIdInt int userId, String packageName) {
        return mPackageManagerInternal.getPackageUid(packageName, 0 /* flags */, userId)
                == callingUid;
    }

    private boolean isInstantApp(String packageName, int userId) {
        return mPackageManagerInternal.isPackageEphemeral(userId, packageName);
    }

    private boolean shouldObfuscateInstantAppsForCaller(int callingUid, int userId) {
        return !mPackageManagerInternal.canAccessInstantApps(callingUid, userId);
    }

    private boolean shouldHideShortcutInvocationEvents(int userId, String callingPackage,
            int callingPid, int callingUid) {
        final ShortcutServiceInternal shortcutServiceInternal = getShortcutServiceInternal();
        if (shortcutServiceInternal != null) {
            return !shortcutServiceInternal.hasShortcutHostPermission(userId, callingPackage,
                    callingPid, callingUid);
        }
        return true; // hide by default if we can't verify visibility
    }

    private boolean shouldHideLocusIdEvents(int callingPid, int callingUid) {
        if (callingUid == Process.SYSTEM_UID) {
            return false;
        }
        return !(getContext().checkPermission(
                android.Manifest.permission.ACCESS_LOCUS_ID_USAGE_STATS, callingPid, callingUid)
                == PackageManager.PERMISSION_GRANTED);
    }

    /**
     * Obfuscate both {@link UsageEvents.Event#NOTIFICATION_SEEN} and
     * {@link UsageEvents.Event#NOTIFICATION_INTERRUPTION} events if the provided calling uid does
     * not hold the {@link android.Manifest.permission#MANAGE_NOTIFICATIONS} permission.
     */
    private boolean shouldObfuscateNotificationEvents(int callingPid, int callingUid) {
        if (callingUid == Process.SYSTEM_UID) {
            return false;
        }
        return !(getContext().checkPermission(android.Manifest.permission.MANAGE_NOTIFICATIONS,
                callingPid, callingUid) == PackageManager.PERMISSION_GRANTED);
    }

    private static void deleteRecursively(final File path) {
        if (path.isDirectory()) {
            final File[] files = path.listFiles();
            if (files != null) {
                for (File subFile : files) {
                    deleteRecursively(subFile);
                }
            }
        }

        if (path.exists() && !path.delete()) {
            Slog.e(TAG, "Failed to delete " + path);
        }
    }

    /**
     * This should the be only way to fetch the usage stats service for a specific user.
     */
    private UserUsageStatsService getUserUsageStatsServiceLocked(int userId) {
        final UserUsageStatsService service = mUserState.get(userId);
        if (service == null) {
            Slog.wtf(TAG, "Failed to fetch usage stats service for user " + userId + ". "
                    + "The user might not have been initialized yet.");
        }
        return service;
    }

    /**
     * Initializes the given user's usage stats service - this should ideally only be called once,
     * when the user is initially unlocked.
     */
    private void initializeUserUsageStatsServiceLocked(int userId, long currentTimeMillis,
            HashMap<String, Long> installedPackages, boolean deleteObsoleteData) {
        final File usageStatsDir = new File(Environment.getDataSystemCeDirectory(userId),
                "usagestats");
        final UserUsageStatsService service = new UserUsageStatsService(getContext(), userId,
                usageStatsDir, this);
        try {
            service.init(currentTimeMillis, installedPackages, deleteObsoleteData);
            mUserState.put(userId, service);
        } catch (Exception e) {
            if (mUserManager.isUserUnlocked(userId)) {
                Slog.w(TAG, "Failed to initialized unlocked user " + userId);
                throw e; // rethrow the exception - user is unlocked
            } else {
                Slog.w(TAG, "Attempted to initialize service for stopped or removed user "
                        + userId);
            }
        }
    }

    private void migrateStatsToSystemCeIfNeededLocked(int userId) {
        final File usageStatsDir = new File(Environment.getDataSystemCeDirectory(userId),
                "usagestats");
        if (!usageStatsDir.mkdirs() && !usageStatsDir.exists()) {
            throw new IllegalStateException("Usage stats directory does not exist: "
                    + usageStatsDir.getAbsolutePath());
        }
        // Check if the migrated status file exists - if not, migrate usage stats.
        final File migrated = new File(usageStatsDir, "migrated");
        if (migrated.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(migrated))) {
                final int previousVersion = Integer.parseInt(reader.readLine());
                // UsageStatsDatabase.BACKUP_VERSION was 4 when usage stats were migrated to CE.
                if (previousVersion >= 4) {
                    deleteLegacyUserDir(userId);
                    return;
                }
                // If migration logic needs to be changed in a future version, do it here.
            } catch (NumberFormatException | IOException e) {
                Slog.e(TAG, "Failed to read migration status file, possibly corrupted.");
                deleteRecursively(usageStatsDir);
                if (usageStatsDir.exists()) {
                    Slog.e(TAG, "Unable to delete usage stats CE directory.");
                    throw new RuntimeException(e);
                } else {
                    // Make the directory again since previous migration was not complete
                    if (!usageStatsDir.mkdirs() && !usageStatsDir.exists()) {
                        throw new IllegalStateException("Usage stats directory does not exist: "
                                + usageStatsDir.getAbsolutePath());
                    }
                }
            }
        }

        Slog.i(TAG, "Starting migration to system CE for user " + userId);
        final File legacyUserDir = new File(LEGACY_USER_USAGE_STATS_DIR, Integer.toString(userId));
        if (legacyUserDir.exists()) {
            copyRecursively(usageStatsDir, legacyUserDir);
        }
        // Create a status file to indicate that the migration to CE has been completed.
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(migrated))) {
            writer.write(Integer.toString(UsageStatsDatabase.BACKUP_VERSION));
            writer.write("\n");
            writer.flush();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write migrated status file");
            throw new RuntimeException(e);
        }
        Slog.i(TAG, "Finished migration to system CE for user " + userId);

        // Migration was successful - delete the legacy user directory
        deleteLegacyUserDir(userId);
    }

    private static void copyRecursively(final File parent, File f) {
        final File[] files = f.listFiles();
        if (files == null) {
            try {
                Files.copy(f.toPath(), new File(parent, f.getName()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Slog.e(TAG, "Failed to move usage stats file : " + f.toString());
                throw new RuntimeException(e);
            }
            return;
        }

        for (int i = files.length - 1; i >= 0; i--) {
            File newParent = parent;
            if (files[i].isDirectory()) {
                newParent = new File(parent, files[i].getName());
                final boolean mkdirSuccess = newParent.mkdirs();
                if (!mkdirSuccess && !newParent.exists()) {
                    throw new IllegalStateException(
                            "Failed to create usage stats directory during migration: "
                            + newParent.getAbsolutePath());
                }
            }
            copyRecursively(newParent, files[i]);
        }
    }

    private void deleteLegacyUserDir(int userId) {
        final File legacyUserDir = new File(LEGACY_USER_USAGE_STATS_DIR, Integer.toString(userId));
        if (legacyUserDir.exists()) {
            deleteRecursively(legacyUserDir);
            if (legacyUserDir.exists()) {
                Slog.w(TAG, "Error occurred while attempting to delete legacy usage stats "
                        + "dir for user " + userId);
            }
        }
    }

    /**
     * Called by the Binder stub
     */
    void shutdown() {
        synchronized (mLock) {
            mHandler.removeMessages(MSG_REPORT_EVENT);
            Event event = new Event(DEVICE_SHUTDOWN, SystemClock.elapsedRealtime());
            event.mPackage = Event.DEVICE_EVENT_PACKAGE_NAME;
            // orderly shutdown, the last event is DEVICE_SHUTDOWN.
            reportEventToAllUserId(event);
            flushToDiskLocked();
            persistGlobalComponentUsageLocked();
        }

        mAppStandby.flushToDisk();
    }

    /**
     * After power button is pressed for 3.5 seconds
     * (as defined in {@link com.android.internal.R.integer#config_veryLongPressTimeout}),
     * report DEVICE_SHUTDOWN event and persist the database. If the power button is pressed for 10
     * seconds and the device is shutdown, the database is already persisted and we are not losing
     * data.
     * This method is called from PhoneWindowManager, do not synchronize on mLock otherwise
     * PhoneWindowManager may be blocked.
     */
    void prepareForPossibleShutdown() {
        Event event = new Event(DEVICE_SHUTDOWN, SystemClock.elapsedRealtime());
        event.mPackage = Event.DEVICE_EVENT_PACKAGE_NAME;
        mHandler.obtainMessage(MSG_REPORT_EVENT_TO_ALL_USERID, event).sendToTarget();
        mHandler.sendEmptyMessage(MSG_FLUSH_TO_DISK);
    }

    private void loadPendingEventsLocked(int userId, LinkedList<Event> pendingEvents) {
        final File usageStatsDeDir = new File(Environment.getDataSystemDeDirectory(userId),
                "usagestats");
        final File[] pendingEventsFiles = usageStatsDeDir.listFiles();
        if (pendingEventsFiles == null || pendingEventsFiles.length == 0) {
            return;
        }
        Arrays.sort(pendingEventsFiles);

        final int numFiles = pendingEventsFiles.length;
        for (int i = 0; i < numFiles; i++) {
            final AtomicFile af = new AtomicFile(pendingEventsFiles[i]);
            final LinkedList<Event> tmpEvents = new LinkedList<>();
            try {
                try (FileInputStream in = af.openRead()) {
                    UsageStatsProtoV2.readPendingEvents(in, tmpEvents);
                }
                // only add to the pending events if the read was successful
                pendingEvents.addAll(tmpEvents);
            } catch (Exception e) {
                // Most likely trying to read a corrupted file - log the failure and continue
                // reading the other pending event files.
                Slog.e(TAG, "Could not read " + pendingEventsFiles[i] + " for user " + userId);
            }
        }
    }

    @GuardedBy({"mLock", "mReportedEvents"})
    private void persistPendingEventsLocked(int userId) {
        final LinkedList<Event> pendingEvents = mReportedEvents.get(userId);
        if (pendingEvents == null || pendingEvents.isEmpty()) {
            return;
        }

        final File deDir = Environment.getDataSystemDeDirectory(userId);
        final File usageStatsDeDir = new File(deDir, "usagestats");
        if (!usageStatsDeDir.mkdir() && !usageStatsDeDir.exists()) {
            if (deDir.exists()) {
                Slog.e(TAG, "Failed to create " + usageStatsDeDir);
            } else {
                Slog.w(TAG, "User " + userId + " was already removed! Discarding pending events");
                pendingEvents.clear();
            }
            return;
        }
        final File pendingEventsFile = new File(usageStatsDeDir,
                "pendingevents_" + System.currentTimeMillis());
        final AtomicFile af = new AtomicFile(pendingEventsFile);
        FileOutputStream fos = null;
        try {
            fos = af.startWrite();
            UsageStatsProtoV2.writePendingEvents(fos, pendingEvents);
            af.finishWrite(fos);
            fos = null;
            pendingEvents.clear();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to write " + pendingEventsFile.getAbsolutePath()
                    + " for user " + userId);
        } finally {
            af.failWrite(fos); // when fos is null (successful write), this will no-op
        }
    }

    private void loadGlobalComponentUsageLocked() {
        AtomicFile af = new AtomicFile(new File(COMMON_USAGE_STATS_DIR,
                    GLOBAL_COMPONENT_USAGE_FILE_NAME));
        if (!af.exists()) {
            af = new AtomicFile(new File(LEGACY_COMMON_USAGE_STATS_DIR,
                        GLOBAL_COMPONENT_USAGE_FILE_NAME));
            if (!af.exists()) {
                return;
            }
            Slog.i(TAG, "Reading " + GLOBAL_COMPONENT_USAGE_FILE_NAME + " file from old location");
        }
        final Map<String, Long> tmpUsage = new ArrayMap<>();
        try {
            try (FileInputStream in = af.openRead()) {
                UsageStatsProtoV2.readGlobalComponentUsage(in, tmpUsage);
            }
            // only add to in memory map if the read was successful
            final Map.Entry<String, Long>[] entries =
                    (Map.Entry<String, Long>[]) tmpUsage.entrySet().toArray();
            final int size = entries.length;
            for (int i = 0; i < size; ++i) {
                // In memory data is usually the most up-to-date, so skip the packages which already
                // have usage data.
                mLastTimeComponentUsedGlobal.putIfAbsent(
                        entries[i].getKey(), entries[i].getValue());
            }
        } catch (Exception e) {
            // Most likely trying to read a corrupted file - log the failure
            Slog.e(TAG, "Could not read " + af.getBaseFile());
        }
    }

    private void persistGlobalComponentUsageLocked() {
        if (mLastTimeComponentUsedGlobal.isEmpty()) {
            return;
        }

        if (!COMMON_USAGE_STATS_DIR.mkdirs() && !COMMON_USAGE_STATS_DIR.exists()) {
            throw new IllegalStateException("Common usage stats directory does not exist: "
                    + COMMON_USAGE_STATS_DIR.getAbsolutePath());
        }
        final File lastTimePackageFile = new File(COMMON_USAGE_STATS_DIR,
                GLOBAL_COMPONENT_USAGE_FILE_NAME);
        final AtomicFile af = new AtomicFile(lastTimePackageFile);
        FileOutputStream fos = null;
        try {
            fos = af.startWrite();
            UsageStatsProtoV2.writeGlobalComponentUsage(fos, mLastTimeComponentUsedGlobal);
            af.finishWrite(fos);
            fos = null;
        } catch (Exception e) {
            Slog.e(TAG, "Failed to write " + lastTimePackageFile.getAbsolutePath());
        } finally {
            af.failWrite(fos); // when fos is null (successful write), this will no-op
        }
    }

    private void reportEventOrAddToQueue(int userId, Event event) {
        if (mUserUnlockedStates.contains(userId)) {
            mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
            return;
        }

        if (Trace.isTagEnabled(Trace.TRACE_TAG_SYSTEM_SERVER)) {
            final String traceTag = "usageStatsQueueEvent(" + userId + ") #"
                    + UserUsageStatsService.eventToString(event.mEventType);
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, traceTag);
        }
        synchronized (mReportedEvents) {
            LinkedList<Event> events = mReportedEvents.get(userId);
            if (events == null) {
                events = new LinkedList<>();
                mReportedEvents.put(userId, events);
            }
            events.add(event);
            if (events.size() == 1) {
                // Every time a file is persisted to disk, mReportedEvents is cleared for this user
                // so trigger a flush to disk every time the first event has been added.
                mHandler.sendEmptyMessageDelayed(MSG_FLUSH_TO_DISK, FLUSH_INTERVAL);
            }
        }
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    /**
     * Assuming the event's timestamp is measured in milliseconds since boot,
     * convert it to a system wall time. System and real time snapshots are updated before
     * conversion.
     */
    private void convertToSystemTimeLocked(Event event) {
        final long actualSystemTime = System.currentTimeMillis();
        if (ENABLE_TIME_CHANGE_CORRECTION) {
            final long actualRealtime = SystemClock.elapsedRealtime();
            final long expectedSystemTime =
                    (actualRealtime - mRealTimeSnapshot) + mSystemTimeSnapshot;
            final long diffSystemTime = actualSystemTime - expectedSystemTime;
            if (Math.abs(diffSystemTime) > TIME_CHANGE_THRESHOLD_MILLIS) {
                // The time has changed.
                Slog.i(TAG, "Time changed in by " + (diffSystemTime / 1000) + " seconds");
                mRealTimeSnapshot = actualRealtime;
                mSystemTimeSnapshot = actualSystemTime;
            }
        }
        event.mTimeStamp = Math.max(0, event.mTimeStamp - mRealTimeSnapshot) + mSystemTimeSnapshot;
    }

    /**
     * Called by the Binder stub.
     */
    void reportEvent(Event event, int userId) {
        final int uid;
        // Acquire uid outside of mLock for events that need it
        switch (event.mEventType) {
            case Event.ACTIVITY_RESUMED:
            case Event.ACTIVITY_PAUSED:
            case Event.ACTIVITY_STOPPED:
                uid = mPackageManagerInternal.getPackageUid(event.mPackage, 0, userId);
                break;
            default:
                uid = 0;
        }

        if (event.mPackage != null && isInstantApp(event.mPackage, userId)) {
            event.mFlags |= Event.FLAG_IS_PACKAGE_INSTANT_APP;
        }

        synchronized (mLock) {
            // This should never be called directly when the user is locked
            if (!mUserUnlockedStates.contains(userId)) {
                Slog.wtf(TAG, "Failed to report event for locked user " + userId
                        + " (" + event.mPackage + "/" + event.mClass
                        + " eventType:" + event.mEventType
                        + " instanceId:" + event.mInstanceId + ")");
                return;
            }

            switch (event.mEventType) {
                case Event.ACTIVITY_RESUMED:
                    logAppUsageEventReportedAtomLocked(Event.ACTIVITY_RESUMED, uid, event.mPackage);

                    // check if this activity has already been resumed
                    if (mVisibleActivities.get(event.mInstanceId) != null) break;
                    final String usageSourcePackage = getUsageSourcePackage(event);
                    try {
                        mAppTimeLimit.noteUsageStart(usageSourcePackage, userId);
                    } catch (IllegalArgumentException iae) {
                        Slog.e(TAG, "Failed to note usage start", iae);
                    }
                    final ActivityData resumedData = new ActivityData(event.mTaskRootPackage,
                            event.mTaskRootClass, usageSourcePackage);
                    resumedData.lastEvent = Event.ACTIVITY_RESUMED;
                    mVisibleActivities.put(event.mInstanceId, resumedData);
                    final long estimatedLaunchTime =
                            mAppStandby.getEstimatedLaunchTime(event.mPackage, userId);
                    final long now = System.currentTimeMillis();
                    if (estimatedLaunchTime < now || estimatedLaunchTime > now + ONE_WEEK) {
                        // If the estimated launch time is in the past or more than a week into
                        // the future, then we re-estimate a future launch time of less than a week
                        // from now, so notify listeners of an estimated launch time change.
                        // Clear the cached value.
                        if (DEBUG) {
                            Slog.d(TAG, event.getPackageName()
                                    + " app launch resetting future launch estimate");
                        }
                        mAppStandby.setEstimatedLaunchTime(event.mPackage, userId, 0);
                        if (stageChangedEstimatedLaunchTime(userId, event.mPackage)) {
                            mHandler.sendEmptyMessage(MSG_NOTIFY_ESTIMATED_LAUNCH_TIMES_CHANGED);
                        }
                    }
                    break;
                case Event.ACTIVITY_PAUSED:
                    ActivityData pausedData = mVisibleActivities.get(event.mInstanceId);
                    if (pausedData == null) {
                        // Must have transitioned from Stopped/Destroyed to Paused state.
                        final String usageSourcePackage2 = getUsageSourcePackage(event);
                        try {
                            mAppTimeLimit.noteUsageStart(usageSourcePackage2, userId);
                        } catch (IllegalArgumentException iae) {
                            Slog.e(TAG, "Failed to note usage start", iae);
                        }
                        pausedData = new ActivityData(event.mTaskRootPackage, event.mTaskRootClass,
                                usageSourcePackage2);
                        mVisibleActivities.put(event.mInstanceId, pausedData);
                    } else {
                        logAppUsageEventReportedAtomLocked(Event.ACTIVITY_PAUSED, uid,
                                event.mPackage);
                    }

                    pausedData.lastEvent = Event.ACTIVITY_PAUSED;
                    if (event.mTaskRootPackage == null) {
                        // Task Root info is missing. Repair the event based on previous data
                        event.mTaskRootPackage = pausedData.mTaskRootPackage;
                        event.mTaskRootClass = pausedData.mTaskRootClass;
                    }
                    break;
                case Event.ACTIVITY_DESTROYED:
                    // Treat activity destroys like activity stops.
                    event.mEventType = Event.ACTIVITY_STOPPED;
                    // Fallthrough
                case Event.ACTIVITY_STOPPED:
                    final ActivityData prevData =
                            mVisibleActivities.removeReturnOld(event.mInstanceId);
                    if (prevData == null) {
                        Slog.w(TAG, "Unexpected activity event reported! (" + event.mPackage
                                + "/" + event.mClass + " event : " + event.mEventType
                                + " instanceId : " + event.mInstanceId + ")");
                        return;
                    }

                    if (prevData.lastEvent != Event.ACTIVITY_PAUSED) {
                        logAppUsageEventReportedAtomLocked(Event.ACTIVITY_PAUSED, uid,
                                event.mPackage);
                    }

                    ArraySet<String> tokens;
                    synchronized (mUsageReporters) {
                        tokens = mUsageReporters.removeReturnOld(event.mInstanceId);
                    }
                    if (tokens != null) {
                        synchronized (tokens) {
                            final int size = tokens.size();
                            // Stop usage on behalf of a UsageReporter that stopped
                            for (int i = 0; i < size; i++) {
                                final String token = tokens.valueAt(i);
                                try {
                                    mAppTimeLimit.noteUsageStop(
                                            buildFullToken(event.mPackage, token), userId);
                                } catch (IllegalArgumentException iae) {
                                    Slog.w(TAG, "Failed to stop usage for during reporter death: "
                                            + iae);
                                }
                            }
                        }
                    }
                    if (event.mTaskRootPackage == null) {
                        // Task Root info is missing. Repair the event based on previous data
                        event.mTaskRootPackage = prevData.mTaskRootPackage;
                        event.mTaskRootClass = prevData.mTaskRootClass;
                    }
                    try {
                        mAppTimeLimit.noteUsageStop(prevData.mUsageSourcePackage, userId);
                    } catch (IllegalArgumentException iae) {
                        Slog.w(TAG, "Failed to note usage stop", iae);
                    }
                    break;
                case Event.USER_INTERACTION:
                    logAppUsageEventReportedAtomLocked(Event.USER_INTERACTION, uid, event.mPackage);
                    // Fall through.
                case Event.APP_COMPONENT_USED:
                    convertToSystemTimeLocked(event);
                    mLastTimeComponentUsedGlobal.put(event.mPackage, event.mTimeStamp);
                    break;
                case Event.SHORTCUT_INVOCATION:
                case Event.CHOOSER_ACTION:
                case Event.STANDBY_BUCKET_CHANGED:
                case Event.FOREGROUND_SERVICE_START:
                case Event.FOREGROUND_SERVICE_STOP:
                    logAppUsageEventReportedAtomLocked(event.mEventType, uid, event.mPackage);
                    break;
            }

            final UserUsageStatsService service = getUserUsageStatsServiceLocked(userId);
            if (service == null) {
                return; // user was stopped or removed
            }
            service.reportEvent(event);
        }

        mIoHandler.obtainMessage(MSG_NOTIFY_USAGE_EVENT_LISTENER, userId, 0, event).sendToTarget();
    }

    @GuardedBy("mLock")
    private void logAppUsageEventReportedAtomLocked(int eventType, int uid, String packageName) {
        FrameworkStatsLog.write(FrameworkStatsLog.APP_USAGE_EVENT_OCCURRED, uid, packageName,
                "", getAppUsageEventOccurredAtomEventType(eventType));
    }

    /** Make sure align with the EventType defined in the AppUsageEventOccurred atom. */
    private int getAppUsageEventOccurredAtomEventType(int eventType) {
        switch (eventType) {
            case Event.ACTIVITY_RESUMED:
                return FrameworkStatsLog
                        .APP_USAGE_EVENT_OCCURRED__EVENT_TYPE__MOVE_TO_FOREGROUND;
            case Event.ACTIVITY_PAUSED:
                return FrameworkStatsLog
                        .APP_USAGE_EVENT_OCCURRED__EVENT_TYPE__MOVE_TO_BACKGROUND;
            case Event.USER_INTERACTION:
                return FrameworkStatsLog
                        .APP_USAGE_EVENT_OCCURRED__EVENT_TYPE__USER_INTERACTION;
            case Event.SHORTCUT_INVOCATION:
                return FrameworkStatsLog
                        .APP_USAGE_EVENT_OCCURRED__EVENT_TYPE__SHORTCUT_INVOCATION;
            case Event.CHOOSER_ACTION:
                return FrameworkStatsLog
                        .APP_USAGE_EVENT_OCCURRED__EVENT_TYPE__CHOOSER_ACTION;
            case Event.STANDBY_BUCKET_CHANGED:
                return FrameworkStatsLog
                        .APP_USAGE_EVENT_OCCURRED__EVENT_TYPE__STANDBY_BUCKET_CHANGED;
            case Event.FOREGROUND_SERVICE_START:
                return FrameworkStatsLog
                        .APP_USAGE_EVENT_OCCURRED__EVENT_TYPE__FOREGROUND_SERVICE_START;
            case Event.FOREGROUND_SERVICE_STOP:
                return FrameworkStatsLog
                        .APP_USAGE_EVENT_OCCURRED__EVENT_TYPE__FOREGROUND_SERVICE_STOP;
            default:
                Slog.w(TAG, "Unsupported usage event logging: " + eventType);
                return -1;
        }
    }

    private String getUsageSourcePackage(Event event) {
        switch(mUsageSource) {
            case USAGE_SOURCE_CURRENT_ACTIVITY:
                return event.mPackage;
            case USAGE_SOURCE_TASK_ROOT_ACTIVITY:
            default:
                return event.mTaskRootPackage;
        }
    }

    /**
     * Some events like FLUSH_TO_DISK need to be sent to all userId.
     * @param event
     */
    void reportEventToAllUserId(Event event) {
        synchronized (mLock) {
            final int userCount = mUserState.size();
            for (int i = 0; i < userCount; i++) {
                Event copy = new Event(event);
                reportEventOrAddToQueue(mUserState.keyAt(i), copy);
            }
        }
    }

    /**
     * Called by the Handler for message MSG_FLUSH_TO_DISK.
     */
    void flushToDisk() {
        synchronized (mLock) {
            // Before flush to disk, report FLUSH_TO_DISK event to signal UsageStats to update app
            // usage. In case of abrupt power shutdown like battery drain or cold temperature,
            // all UsageStats has correct data up to last flush to disk.
            // The FLUSH_TO_DISK event is an internal event, it will not show up in IntervalStats'
            // EventList.
            Event event = new Event(FLUSH_TO_DISK, SystemClock.elapsedRealtime());
            event.mPackage = DEVICE_EVENT_PACKAGE_NAME;
            reportEventToAllUserId(event);
            flushToDiskLocked();
        }
        mAppStandby.flushToDisk();
    }

    /**
     * Called by the Handler for message MSG_USER_REMOVED.
     */
    void onUserRemoved(int userId) {
        synchronized (mLock) {
            Slog.i(TAG, "Removing user " + userId + " and all data.");
            mUserState.remove(userId);
            mAppTimeLimit.onUserRemoved(userId);
        }

        synchronized (mLaunchTimeAlarmQueues) {
            final LaunchTimeAlarmQueue alarmQueue = mLaunchTimeAlarmQueues.get(userId);
            if (alarmQueue != null) {
                alarmQueue.removeAllAlarms();
                mLaunchTimeAlarmQueues.remove(userId);
            }
        }
        // Since this is only called from the Handler, we don't have to worry about modifying the
        // pending change list while the handler is iterating to notify listeners.
        synchronized (mPendingLaunchTimeChangePackages) {
            mPendingLaunchTimeChangePackages.remove(userId);
        }
        mAppStandby.onUserRemoved(userId);
        mResponseStatsTracker.onUserRemoved(userId);

        // Cancel any scheduled jobs for this user since the user is being removed.
        UsageStatsIdleService.cancelPruneJob(getContext(), userId);
        UsageStatsIdleService.cancelUpdateMappingsJob(getContext(), userId);
    }

    /**
     * Called by the Handler for message MSG_PACKAGE_REMOVED.
     */
    private void onPackageRemoved(int userId, String packageName) {
        // Since this is only called from the Handler, we don't have to worry about modifying the
        // pending change list while the handler is iterating to notify listeners.
        synchronized (mPendingLaunchTimeChangePackages) {
            final ArraySet<String> pkgNames = mPendingLaunchTimeChangePackages.get(userId);
            if (pkgNames != null) {
                pkgNames.remove(packageName);
            }
        }

        synchronized (mLaunchTimeAlarmQueues) {
            final LaunchTimeAlarmQueue alarmQueue = mLaunchTimeAlarmQueues.get(userId);
            if (alarmQueue != null) {
                alarmQueue.removeAlarmForKey(packageName);
            }
        }

        final int tokenRemoved;
        synchronized (mLock) {
            final long timeRemoved = System.currentTimeMillis();
            if (!mUserUnlockedStates.contains(userId)) {
                // If user is not unlocked and a package is removed for them, we will handle it
                // when the user service is initialized and package manager is queried.
                return;
            }

            final UserUsageStatsService userService = mUserState.get(userId);
            if (userService == null) {
                return;
            }

            tokenRemoved = userService.onPackageRemoved(packageName, timeRemoved);
        }

        // Schedule a job to prune any data related to this package.
        if (tokenRemoved != PackagesTokenData.UNASSIGNED_TOKEN) {
            UsageStatsIdleService.schedulePruneJob(getContext(), userId);
        }
    }

    /**
     * Called by the Binder stub.
     */
    private boolean pruneUninstalledPackagesData(int userId) {
        synchronized (mLock) {
            if (!mUserUnlockedStates.contains(userId)) {
                return false; // user is no longer unlocked
            }

            final UserUsageStatsService userService = mUserState.get(userId);
            if (userService == null) {
                return false; // user was stopped or removed
            }

            return userService.pruneUninstalledPackagesData();
        }
    }

    /**
     * Called by the Binder stub.
     */
    private boolean updatePackageMappingsData(@UserIdInt int userId) {
        // don't update the mappings if a profile user is defined
        if (!shouldDeleteObsoleteData(UserHandle.of(userId))) {
            return true; // return true so job scheduler doesn't reschedule the job
        }
        // fetch the installed packages outside the lock so it doesn't block package manager.
        final HashMap<String, Long> installedPkgs = getInstalledPackages(userId);
        synchronized (mLock) {
            if (!mUserUnlockedStates.contains(userId)) {
                return false; // user is no longer unlocked
            }

            final UserUsageStatsService userService = mUserState.get(userId);
            if (userService == null) {
                return false; // user was stopped or removed
            }

            return userService.updatePackageMappingsLocked(installedPkgs);
        }
    }

    /**
     * Called by the Binder stub.
     */
    List<UsageStats> queryUsageStats(int userId, int bucketType, long beginTime, long endTime,
            boolean obfuscateInstantApps) {
        synchronized (mLock) {
            if (!mUserUnlockedStates.contains(userId)) {
                Slog.w(TAG, "Failed to query usage stats for locked user " + userId);
                return null;
            }

            final UserUsageStatsService service = getUserUsageStatsServiceLocked(userId);
            if (service == null) {
                return null; // user was stopped or removed
            }
            List<UsageStats> list = service.queryUsageStats(bucketType, beginTime, endTime);
            if (list == null) {
                return null;
            }

            // Mangle instant app names *using their current state (not whether they were ephemeral
            // when the data was recorded)*.
            if (obfuscateInstantApps) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    final UsageStats stats = list.get(i);
                    if (isInstantApp(stats.mPackageName, userId)) {
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
            if (!mUserUnlockedStates.contains(userId)) {
                Slog.w(TAG, "Failed to query configuration stats for locked user " + userId);
                return null;
            }

            final UserUsageStatsService service = getUserUsageStatsServiceLocked(userId);
            if (service == null) {
                return null; // user was stopped or removed
            }
            return service.queryConfigurationStats(bucketType, beginTime, endTime);
        }
    }

    /**
     * Called by the Binder stub.
     */
    List<EventStats> queryEventStats(int userId, int bucketType, long beginTime,
            long endTime) {
        synchronized (mLock) {
            if (!mUserUnlockedStates.contains(userId)) {
                Slog.w(TAG, "Failed to query event stats for locked user " + userId);
                return null;
            }

            final UserUsageStatsService service = getUserUsageStatsServiceLocked(userId);
            if (service == null) {
                return null; // user was stopped or removed
            }
            return service.queryEventStats(bucketType, beginTime, endTime);
        }
    }

    /**
     * Called by the Binder stub.
     */
    UsageEvents queryEvents(int userId, long beginTime, long endTime, int flags) {
        return queryEventsWithQueryFilters(userId, beginTime, endTime, flags,
                /* eventTypeFilter= */ EmptyArray.INT, /* pkgNameFilter= */ null);
    }

    /**
     * Called by the Binder stub.
     */
    UsageEvents queryEventsWithQueryFilters(int userId, long beginTime, long endTime, int flags,
            int[] eventTypeFilter, ArraySet<String> pkgNameFilter) {
        synchronized (mLock) {
            if (!mUserUnlockedStates.contains(userId)) {
                Slog.w(TAG, "Failed to query events for locked user " + userId);
                return null;
            }

            final UserUsageStatsService service = getUserUsageStatsServiceLocked(userId);
            if (service == null) {
                return null; // user was stopped or removed
            }
            return service.queryEvents(beginTime, endTime, flags, eventTypeFilter, pkgNameFilter);
        }
    }

    /**
     * Called by the Binder stub.
     */
    @Nullable
    UsageEvents queryEventsForPackage(int userId, long beginTime, long endTime,
            String packageName, boolean includeTaskRoot) {
        synchronized (mLock) {
            if (!mUserUnlockedStates.contains(userId)) {
                Slog.w(TAG, "Failed to query package events for locked user " + userId);
                return null;
            }

            final UserUsageStatsService service = getUserUsageStatsServiceLocked(userId);
            if (service == null) {
                return null; // user was stopped or removed
            }
            return service.queryEventsForPackage(beginTime, endTime, packageName, includeTaskRoot);
        }
    }

    @Nullable
    private UsageEvents queryEarliestAppEvents(int userId, long beginTime, long endTime,
            int eventType) {
        synchronized (mLock) {
            if (!mUserUnlockedStates.contains(userId)) {
                Slog.w(TAG, "Failed to query earliest events for locked user " + userId);
                return null;
            }

            final UserUsageStatsService service = getUserUsageStatsServiceLocked(userId);
            if (service == null) {
                return null; // user was stopped or removed
            }
            return service.queryEarliestAppEvents(beginTime, endTime, eventType);
        }
    }

    @Nullable
    private UsageEvents queryEarliestEventsForPackage(int userId, long beginTime, long endTime,
            @NonNull String packageName, int eventType) {
        synchronized (mLock) {
            if (!mUserUnlockedStates.contains(userId)) {
                Slog.w(TAG, "Failed to query earliest package events for locked user " + userId);
                return null;
            }

            final UserUsageStatsService service = getUserUsageStatsServiceLocked(userId);
            if (service == null) {
                return null; // user was stopped or removed
            }
            return service.queryEarliestEventsForPackage(
                    beginTime, endTime, packageName, eventType);
        }
    }

    @CurrentTimeMillisLong
    long getEstimatedPackageLaunchTime(int userId, String packageName) {
        long estimatedLaunchTime = mAppStandby.getEstimatedLaunchTime(packageName, userId);
        final long now = System.currentTimeMillis();
        if (estimatedLaunchTime < now || estimatedLaunchTime == Long.MAX_VALUE) {
            estimatedLaunchTime = calculateEstimatedPackageLaunchTime(userId, packageName);
            mAppStandby.setEstimatedLaunchTime(packageName, userId, estimatedLaunchTime);

            getOrCreateLaunchTimeAlarmQueue(userId).addAlarm(packageName,
                    SystemClock.elapsedRealtime() + (estimatedLaunchTime - now));
        }
        return estimatedLaunchTime;
    }

    private LaunchTimeAlarmQueue getOrCreateLaunchTimeAlarmQueue(int userId) {
        synchronized (mLaunchTimeAlarmQueues) {
            LaunchTimeAlarmQueue alarmQueue = mLaunchTimeAlarmQueues.get(userId);
            if (alarmQueue == null) {
                alarmQueue = new LaunchTimeAlarmQueue(userId, getContext(), mHandler.getLooper());
                mLaunchTimeAlarmQueues.put(userId, alarmQueue);
            }

            return alarmQueue;
        }
    }

    @CurrentTimeMillisLong
    private long calculateEstimatedPackageLaunchTime(int userId, String packageName) {
        final long endTime = System.currentTimeMillis();
        final long beginTime = endTime - ONE_WEEK;
        final long unknownTime = endTime + UNKNOWN_LAUNCH_TIME_DELAY_MS;
        final UsageEvents events = queryEarliestEventsForPackage(
                userId, beginTime, endTime, packageName, Event.ACTIVITY_RESUMED);
        if (events == null) {
            if (DEBUG) {
                Slog.d(TAG, "No events for " + userId + ":" + packageName);
            }
            return unknownTime;
        }
        final UsageEvents.Event event = new UsageEvents.Event();
        final boolean hasMoreThan24HoursOfHistory;
        if (events.getNextEvent(event)) {
            hasMoreThan24HoursOfHistory = endTime - event.getTimeStamp() > ONE_DAY;
            if (DEBUG) {
                Slog.d(TAG, userId + ":" + packageName + " history > 24 hours="
                        + hasMoreThan24HoursOfHistory);
            }
        } else {
            if (DEBUG) {
                Slog.d(TAG, userId + ":" + packageName + " has no events");
            }
            return unknownTime;
        }
        do {
            if (event.getEventType() == Event.ACTIVITY_RESUMED) {
                final long timestamp = event.getTimeStamp();
                final long nextLaunch =
                        calculateNextLaunchTime(hasMoreThan24HoursOfHistory, timestamp);
                if (nextLaunch > endTime) {
                    return nextLaunch;
                }
            }
        } while (events.getNextEvent(event));
        return unknownTime;
    }

    @CurrentTimeMillisLong
    private static long calculateNextLaunchTime(
            boolean hasMoreThan24HoursOfHistory, long eventTimestamp) {
        // For our estimates, we assume the user opens an app at consistent times
        // (ie. like clockwork).
        // If the app has more than 24 hours of history, then we assume the user will
        // reopen the app at the same time on a specific day.
        // If the app has less than 24 hours of history (meaning it was likely just
        // installed), then we assume the user will open it at exactly the same time
        // on the following day.
        if (hasMoreThan24HoursOfHistory) {
            return eventTimestamp + ONE_WEEK;
        } else {
            return eventTimestamp + ONE_DAY;
        }
    }

    private void handleEstimatedLaunchTimesOnUserUnlock(int userId) {
        final long nowElapsed = SystemClock.elapsedRealtime();
        final long now = System.currentTimeMillis();
        final long beginTime = now - ONE_WEEK;
        final UsageEvents events = queryEarliestAppEvents(
                userId, beginTime, now, Event.ACTIVITY_RESUMED);
        if (events == null) {
            return;
        }
        final ArrayMap<String, Boolean> hasMoreThan24HoursOfHistory = new ArrayMap<>();
        final UsageEvents.Event event = new UsageEvents.Event();
        boolean changedTimes = false;
        final LaunchTimeAlarmQueue alarmQueue = getOrCreateLaunchTimeAlarmQueue(userId);
        for (boolean unprocessedEvent = events.getNextEvent(event); unprocessedEvent;
                unprocessedEvent = events.getNextEvent(event)) {
            final String packageName = event.getPackageName();
            if (!hasMoreThan24HoursOfHistory.containsKey(packageName)) {
                boolean hasHistory = now - event.getTimeStamp() > ONE_DAY;
                if (DEBUG) {
                    Slog.d(TAG,
                            userId + ":" + packageName + " history > 24 hours=" + hasHistory);
                }
                hasMoreThan24HoursOfHistory.put(packageName, hasHistory);
            }
            if (event.getEventType() == Event.ACTIVITY_RESUMED) {
                long estimatedLaunchTime =
                        mAppStandby.getEstimatedLaunchTime(packageName, userId);
                if (estimatedLaunchTime < now || estimatedLaunchTime == Long.MAX_VALUE) {
                    //noinspection ConstantConditions
                    estimatedLaunchTime = calculateNextLaunchTime(
                            hasMoreThan24HoursOfHistory.get(packageName), event.getTimeStamp());
                    mAppStandby.setEstimatedLaunchTime(
                            packageName, userId, estimatedLaunchTime);
                }
                if (estimatedLaunchTime < now + ONE_WEEK) {
                    // Before a user is unlocked, we don't know when the app will be launched,
                    // so we give callers the UNKNOWN time. Now that we have a better estimate,
                    // we should notify them of the change.
                    if (DEBUG) {
                        Slog.d(TAG, "User " + userId + " unlock resulting in"
                                + " estimated launch time change for " + packageName);
                    }
                    changedTimes |= stageChangedEstimatedLaunchTime(userId, packageName);
                }
                alarmQueue.addAlarm(packageName, nowElapsed + (estimatedLaunchTime - now));
            }
        }
        if (changedTimes) {
            mHandler.sendEmptyMessage(MSG_NOTIFY_ESTIMATED_LAUNCH_TIMES_CHANGED);
        }
    }

    private void setEstimatedLaunchTime(int userId, String packageName,
            @CurrentTimeMillisLong long estimatedLaunchTime) {
        final long now = System.currentTimeMillis();
        if (estimatedLaunchTime <= now) {
            if (DEBUG) {
                Slog.w(TAG, "Ignoring new estimate for "
                        + userId + ":" + packageName + " because it's old");
            }
            return;
        }
        final long oldEstimatedLaunchTime = mAppStandby.getEstimatedLaunchTime(packageName, userId);
        if (estimatedLaunchTime != oldEstimatedLaunchTime) {
            mAppStandby.setEstimatedLaunchTime(packageName, userId, estimatedLaunchTime);
            if (stageChangedEstimatedLaunchTime(userId, packageName)) {
                mHandler.sendEmptyMessage(MSG_NOTIFY_ESTIMATED_LAUNCH_TIMES_CHANGED);
            }
        }
    }

    private void setEstimatedLaunchTimes(int userId, List<AppLaunchEstimateInfo> launchEstimates) {
        boolean changedTimes = false;
        final long now = System.currentTimeMillis();
        for (int i = launchEstimates.size() - 1; i >= 0; --i) {
            AppLaunchEstimateInfo estimate = launchEstimates.get(i);
            if (estimate.estimatedLaunchTime <= now) {
                if (DEBUG) {
                    Slog.w(TAG, "Ignoring new estimate for "
                            + userId + ":" + estimate.packageName + " because it's old");
                }
                continue;
            }
            final long oldEstimatedLaunchTime =
                    mAppStandby.getEstimatedLaunchTime(estimate.packageName, userId);
            if (estimate.estimatedLaunchTime != oldEstimatedLaunchTime) {
                mAppStandby.setEstimatedLaunchTime(
                        estimate.packageName, userId, estimate.estimatedLaunchTime);
                changedTimes |= stageChangedEstimatedLaunchTime(userId, estimate.packageName);
            }
        }
        if (changedTimes) {
            mHandler.sendEmptyMessage(MSG_NOTIFY_ESTIMATED_LAUNCH_TIMES_CHANGED);
        }
    }

    private boolean stageChangedEstimatedLaunchTime(int userId, String packageName) {
        synchronized (mPendingLaunchTimeChangePackages) {
            return mPendingLaunchTimeChangePackages.add(userId, packageName);
        }
    }

    /**
     * Called via the local interface.
     */
    private void registerListener(@NonNull UsageStatsManagerInternal.UsageEventListener listener) {
        synchronized (mUsageEventListeners) {
            mUsageEventListeners.add(listener);
        }
    }

    /**
     * Called via the local interface.
     */
    private void unregisterListener(
            @NonNull UsageStatsManagerInternal.UsageEventListener listener) {
        synchronized (mUsageEventListeners) {
            mUsageEventListeners.remove(listener);
        }
    }

    /**
     * Called via the local interface.
     */
    private void registerLaunchTimeChangedListener(
            @NonNull UsageStatsManagerInternal.EstimatedLaunchTimeChangedListener listener) {
        mEstimatedLaunchTimeChangedListeners.add(listener);
    }

    /**
     * Called via the local interface.
     */
    private void unregisterLaunchTimeChangedListener(
            @NonNull UsageStatsManagerInternal.EstimatedLaunchTimeChangedListener listener) {
        mEstimatedLaunchTimeChangedListeners.remove(listener);
    }

    private boolean shouldDeleteObsoleteData(UserHandle userHandle) {
        final DevicePolicyManagerInternal dpmInternal = getDpmInternal();
        // If a profile owner is not defined for the given user, obsolete data should be deleted
        return dpmInternal == null
                || dpmInternal.getProfileOwnerOrDeviceOwnerSupervisionComponent(userHandle) == null;
    }

    private String buildFullToken(String packageName, String token) {
        final StringBuilder sb = new StringBuilder(packageName.length() + token.length() + 1);
        sb.append(packageName);
        sb.append(TOKEN_DELIMITER);
        sb.append(token);
        return sb.toString();
    }

    private void flushToDiskLocked() {
        final int userCount = mUserState.size();
        for (int i = 0; i < userCount; i++) {
            final int userId = mUserState.keyAt(i);
            if (!mUserUnlockedStates.contains(userId)) {
                persistPendingEventsLocked(userId);
                continue;
            }
            UserUsageStatsService service = mUserState.get(userId);
            if (service != null) {
                service.persistActiveStats();
            }
        }
        mHandler.removeMessages(MSG_FLUSH_TO_DISK);
    }

    private String getTrimmedString(String input) {
        if (input != null && input.length() > MAX_TEXT_LENGTH) {
            return input.substring(0, MAX_TEXT_LENGTH);
        }
        return input;
    }

    /**
     * Called by the Binder stub.
     */
    void dump(String[] args, PrintWriter pw) {
        IndentingPrintWriter idpw = new IndentingPrintWriter(pw, "  ");

        boolean checkin = false;
        boolean compact = false;
        final ArrayList<String> pkgs = new ArrayList<>();

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--checkin".equals(arg)) {
                    checkin = true;
                } else if ("-c".equals(arg)) {
                    compact = true;
                } else if ("flush".equals(arg)) {
                    synchronized (mLock) {
                        flushToDiskLocked();
                    }
                    mAppStandby.flushToDisk();
                    pw.println("Flushed stats to disk");
                    return;
                } else if ("is-app-standby-enabled".equals(arg)) {
                    pw.println(mAppStandby.isAppIdleEnabled());
                    return;
                } else if ("apptimelimit".equals(arg)) {
                    synchronized (mLock) {
                        if (i + 1 >= args.length) {
                            mAppTimeLimit.dump(null, pw);
                        } else {
                            final String[] remainingArgs =
                                    Arrays.copyOfRange(args, i + 1, args.length);
                            mAppTimeLimit.dump(remainingArgs, pw);
                        }
                        return;
                    }
                } else if ("file".equals(arg)) {
                    final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
                    synchronized (mLock) {
                        if (i + 1 >= args.length) {
                            // dump everything for all users
                            final int numUsers = mUserState.size();
                            for (int user = 0; user < numUsers; user++) {
                                final int userId = mUserState.keyAt(user);
                                if (!mUserUnlockedStates.contains(userId)) {
                                    continue;
                                }
                                ipw.println("user=" + userId);
                                ipw.increaseIndent();
                                mUserState.valueAt(user).dumpFile(ipw, null);
                                ipw.decreaseIndent();
                            }
                        } else {
                            final int user = parseUserIdFromArgs(args, i, ipw);
                            if (user != UserHandle.USER_NULL) {
                                final String[] remainingArgs = Arrays.copyOfRange(
                                        args, i + 2, args.length);
                                // dump everything for the specified user
                                mUserState.get(user).dumpFile(ipw, remainingArgs);
                            }
                        }
                        return;
                    }
                } else if ("database-info".equals(arg)) {
                    final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
                    synchronized (mLock) {
                        if (i + 1 >= args.length) {
                            // dump info for all users
                            final int numUsers = mUserState.size();
                            for (int user = 0; user < numUsers; user++) {
                                final int userId = mUserState.keyAt(user);
                                if (!mUserUnlockedStates.contains(userId)) {
                                    continue;
                                }
                                ipw.println("user=" + userId);
                                ipw.increaseIndent();
                                mUserState.valueAt(user).dumpDatabaseInfo(ipw);
                                ipw.decreaseIndent();
                            }
                        } else {
                            final int user = parseUserIdFromArgs(args, i, ipw);
                            if (user != UserHandle.USER_NULL) {
                                // dump info only for the specified user
                                mUserState.get(user).dumpDatabaseInfo(ipw);
                            }
                        }
                        return;
                    }
                } else if ("appstandby".equals(arg)) {
                    mAppStandby.dumpState(args, pw);
                    return;
                } else if ("stats-directory".equals(arg)) {
                    final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
                    synchronized (mLock) {
                        final int userId = parseUserIdFromArgs(args, i, ipw);
                        if (userId != UserHandle.USER_NULL) {
                            ipw.println(new File(Environment.getDataSystemCeDirectory(userId),
                                    "usagestats").getAbsolutePath());
                        }
                        return;
                    }
                } else if ("mappings".equals(arg)) {
                    final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
                    synchronized (mLock) {
                        final int userId = parseUserIdFromArgs(args, i, ipw);
                        if (userId != UserHandle.USER_NULL) {
                            mUserState.get(userId).dumpMappings(ipw);
                        }
                        return;
                    }
                } else if ("broadcast-response-stats".equals(arg)) {
                    synchronized (mLock) {
                        mResponseStatsTracker.dump(idpw);
                    }
                    return;
                } else if ("app-component-usage".equals(arg)) {
                    final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
                    synchronized (mLock) {
                        if (!mLastTimeComponentUsedGlobal.isEmpty()) {
                            ipw.println("App Component Usages:");
                            ipw.increaseIndent();
                            for (String pkg : mLastTimeComponentUsedGlobal.keySet()) {
                                ipw.println("package=" + pkg
                                            + " lastUsed=" + UserUsageStatsService.formatDateTime(
                                                    mLastTimeComponentUsedGlobal.get(pkg), true));
                            }
                            ipw.decreaseIndent();
                        }
                    }
                    return;
                } else if (arg != null && !arg.startsWith("-")) {
                    // Anything else that doesn't start with '-' is a pkg to filter
                    pkgs.add(arg);
                }
            }
        }

        // Flags status.
        pw.println("Flags:");
        pw.println("    " + Flags.FLAG_USER_INTERACTION_TYPE_API
                + ": " + Flags.userInteractionTypeApi());
        pw.println("    " + Flags.FLAG_USE_PARCELED_LIST
                + ": " + Flags.useParceledList());
        pw.println("    " + Flags.FLAG_FILTER_BASED_EVENT_QUERY_API
                + ": " + Flags.filterBasedEventQueryApi());

        final int[] userIds;
        synchronized (mLock) {
            final int userCount = mUserState.size();
            userIds = new int[userCount];
            for (int i = 0; i < userCount; i++) {
                final int userId = mUserState.keyAt(i);
                userIds[i] = userId;
                idpw.printPair("user", userId);
                idpw.println();
                idpw.increaseIndent();
                if (mUserUnlockedStates.contains(userId)) {
                    if (checkin) {
                        mUserState.valueAt(i).checkin(idpw);
                    } else {
                        mUserState.valueAt(i).dump(idpw, pkgs, compact);
                        idpw.println();
                    }
                } else {
                    synchronized (mReportedEvents) {
                        final LinkedList<Event> pendingEvents = mReportedEvents.get(userId);
                        if (pendingEvents != null && !pendingEvents.isEmpty()) {
                            final int eventCount = pendingEvents.size();
                            idpw.println("Pending events: count=" + eventCount);
                            idpw.increaseIndent();
                            for (int idx = 0; idx < eventCount; idx++) {
                                UserUsageStatsService.printEvent(idpw, pendingEvents.get(idx),
                                        true);
                            }
                            idpw.decreaseIndent();
                            idpw.println();
                        }
                    }
                }
                idpw.decreaseIndent();
            }

            idpw.println();
            idpw.printPair("Usage Source", UsageStatsManager.usageSourceToString(mUsageSource));
            idpw.println();

            mAppTimeLimit.dump(null, pw);

            idpw.println();
            mResponseStatsTracker.dump(idpw);
        }

        mAppStandby.dumpUsers(idpw, userIds, pkgs);

        if (CollectionUtils.isEmpty(pkgs)) {
            pw.println();
            mAppStandby.dumpState(args, pw);
        }
    }

    private int parseUserIdFromArgs(String[] args, int index, IndentingPrintWriter ipw) {
        final int userId;
        try {
            userId = Integer.parseInt(args[index + 1]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            ipw.println("invalid user specified.");
            return UserHandle.USER_NULL;
        }
        if (mUserState.indexOfKey(userId) < 0) {
            ipw.println("the specified user does not exist.");
            return UserHandle.USER_NULL;
        }
        if (!mUserUnlockedStates.contains(userId)) {
            ipw.println("the specified user is currently in a locked state.");
            return UserHandle.USER_NULL;
        }
        return userId;
    }

    class H extends Handler {
        public H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REPORT_EVENT:
                    reportEvent((Event) msg.obj, msg.arg1);
                    break;
                case MSG_REPORT_EVENT_TO_ALL_USERID:
                    reportEventToAllUserId((Event) msg.obj);
                    break;
                case MSG_FLUSH_TO_DISK:
                    flushToDisk();
                    break;
                case MSG_UNLOCKED_USER: {
                    final int userId = msg.arg1;
                    try {
                        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER,
                                "usageStatsHandleUserUnlocked(" + userId + ")");
                        onUserUnlocked(userId);
                    } catch (Exception e) {
                        if (mUserManager.isUserUnlocked(userId)) {
                            throw e; // rethrow exception - user is unlocked
                        } else {
                            Slog.w(TAG, "Attempted to unlock stopped or removed user " + msg.arg1);
                        }
                    } finally {
                        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
                    }
                    break;
                }
                case MSG_REMOVE_USER:
                    onUserRemoved(msg.arg1);
                    break;
                case MSG_UID_REMOVED:
                    mResponseStatsTracker.onUidRemoved(msg.arg1);
                    break;
                case MSG_USER_STARTED:
                    mAppStandby.postCheckIdleStates(msg.arg1);
                    break;
                case MSG_PACKAGE_REMOVED:
                    onPackageRemoved(msg.arg1, (String) msg.obj);
                    break;
                case MSG_ON_START:
                    synchronized (mLock) {
                        loadGlobalComponentUsageLocked();
                    }
                    break;
                case MSG_NOTIFY_ESTIMATED_LAUNCH_TIMES_CHANGED: {
                    removeMessages(MSG_NOTIFY_ESTIMATED_LAUNCH_TIMES_CHANGED);

                    // Note that this method of getting the list's size outside and then using it
                    // for iteration outside of the lock implies possible issue if the set is
                    // modified during iteration. However, at the time of implementation, this is
                    // not an issue.
                    // For addition (increasing the size): if something is added after we get the
                    // size, then there will be a new MSG_NOTIFY_ESTIMATED_LAUNCH_TIMES_CHANGED
                    // message in the handler's queue, which means we will iterate over the list
                    // once again and process the addition
                    // For removal (decreasing the size): removals only ever happen via the handler,
                    // which means this iteration code cannot happen at the same time as a removal.
                    // We go through hoops to avoid holding locks when calling out to listeners.
                    final int numUsers;
                    final ArraySet<String> pkgNames = new ArraySet();
                    synchronized (mPendingLaunchTimeChangePackages) {
                        numUsers = mPendingLaunchTimeChangePackages.size();
                    }
                    for (int u = numUsers - 1; u >= 0; --u) {
                        pkgNames.clear();
                        final int userId;
                        synchronized (mPendingLaunchTimeChangePackages) {
                            userId = mPendingLaunchTimeChangePackages.keyAt(u);
                            pkgNames.addAll(mPendingLaunchTimeChangePackages.get(userId));
                            mPendingLaunchTimeChangePackages.remove(userId);
                        }
                        if (DEBUG) {
                            Slog.d(TAG, "Notifying listeners for " + userId + "-->" + pkgNames);
                        }
                        for (int p = pkgNames.size() - 1; p >= 0; --p) {
                            final String pkgName = pkgNames.valueAt(p);
                            final long nextEstimatedLaunchTime =
                                    getEstimatedPackageLaunchTime(userId, pkgName);
                            for (UsageStatsManagerInternal.EstimatedLaunchTimeChangedListener
                                    listener : mEstimatedLaunchTimeChangedListeners) {
                                listener.onEstimatedLaunchTimeChanged(
                                        userId, pkgName, nextEstimatedLaunchTime);
                            }
                        }
                    }
                }
                break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    void clearLastUsedTimestamps(@NonNull String packageName, @UserIdInt int userId) {
        mAppStandby.clearLastUsedTimestampsForTest(packageName, userId);
    }

    void deletePackageData(@NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            mUserState.get(userId).deleteDataFor(packageName);
        }
    }

    private final class BinderService extends IUsageStatsManager.Stub {

        private boolean hasQueryPermission(String callingPackage) {
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

        private boolean canReportUsageStats() {
            if (isCallingUidSystem()) {
                // System UID can always report UsageStats
                return true;
            }

            return getContext().checkCallingPermission(Manifest.permission.REPORT_USAGE_STATS)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private boolean hasObserverPermission() {
            final int callingUid = Binder.getCallingUid();
            DevicePolicyManagerInternal dpmInternal = getDpmInternal();
            //TODO(b/169395065) Figure out if this flow makes sense in Device Owner mode.
            if (callingUid == Process.SYSTEM_UID
                    || (dpmInternal != null
                        && (dpmInternal.isActiveProfileOwner(callingUid)
                        || dpmInternal.isActiveDeviceOwner(callingUid)))) {
                // Caller is the system or the profile owner, so proceed.
                return true;
            }
            return getContext().checkCallingPermission(Manifest.permission.OBSERVE_APP_USAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private boolean hasPermissions(String... permissions) {
            final int callingUid = Binder.getCallingUid();
            if (callingUid == Process.SYSTEM_UID) {
                // Caller is the system, so proceed.
                return true;
            }

            boolean hasPermissions = true;
            final Context context = getContext();
            for (int i = 0; i < permissions.length; i++) {
                hasPermissions = hasPermissions && (context.checkCallingPermission(permissions[i])
                        == PackageManager.PERMISSION_GRANTED);
            }
            return hasPermissions;
        }

        private void checkCallerIsSystemOrSameApp(String pkg) {
            if (isCallingUidSystem()) {
                return;
            }
            checkCallerIsSameApp(pkg);
        }

        private void checkCallerIsSameApp(String pkg) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);

            if (mPackageManagerInternal.getPackageUid(pkg, /*flags=*/ 0,
                    callingUserId) != callingUid) {
                throw new SecurityException("Calling uid " + callingUid + " cannot query events"
                        + "for package " + pkg);
            }
        }

        private boolean isCallingUidSystem() {
            final int uid = UserHandle.getAppId(Binder.getCallingUid()); // ignores user
            return uid == Process.SYSTEM_UID;
        }

        private UsageEvents queryEventsHelper(int userId, long beginTime, long endTime,
                String callingPackage, int[] eventTypeFilter, ArraySet<String> pkgNameFilter) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final boolean obfuscateInstantApps = shouldObfuscateInstantAppsForCaller(
                    callingUid, UserHandle.getCallingUserId());

            final long token = Binder.clearCallingIdentity();
            try {
                final boolean hideShortcutInvocationEvents = shouldHideShortcutInvocationEvents(
                        userId, callingPackage, callingPid, callingUid);
                final boolean hideLocusIdEvents = shouldHideLocusIdEvents(callingPid, callingUid);
                final boolean obfuscateNotificationEvents = shouldObfuscateNotificationEvents(
                        callingPid, callingUid);
                int flags = UsageEvents.SHOW_ALL_EVENT_DATA;
                if (obfuscateInstantApps) flags |= UsageEvents.OBFUSCATE_INSTANT_APPS;
                if (hideShortcutInvocationEvents) flags |= UsageEvents.HIDE_SHORTCUT_EVENTS;
                if (hideLocusIdEvents) flags |= UsageEvents.HIDE_LOCUS_EVENTS;
                if (obfuscateNotificationEvents) flags |= UsageEvents.OBFUSCATE_NOTIFICATION_EVENTS;

                return UsageStatsService.this.queryEventsWithQueryFilters(userId,
                        beginTime, endTime, flags, eventTypeFilter, pkgNameFilter);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void reportUserInteractionInnerHelper(String packageName, @UserIdInt int userId,
                PersistableBundle extras) {
            if (Flags.reportUsageStatsPermission()) {
                if (!canReportUsageStats()) {
                    throw new SecurityException(
                        "Only the system or holders of the REPORT_USAGE_STATS"
                            + " permission are allowed to call reportUserInteraction");
                }
                if (userId != UserHandle.getCallingUserId()) {
                    // Cross-user event reporting.
                    getContext().enforceCallingPermission(
                            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                            "Caller doesn't have INTERACT_ACROSS_USERS_FULL permission");
                }
            } else {
                if (!isCallingUidSystem()) {
                    throw new SecurityException("Only system is allowed to call"
                        + " reportUserInteraction");
                }
            }

            // Verify if this package exists before reporting an event for it.
            if (mPackageManagerInternal.getPackageUid(packageName, 0, userId) < 0) {
                throw new IllegalArgumentException("Package " + packageName
                        + " does not exist!");
            }

            final Event event = new Event(USER_INTERACTION, SystemClock.elapsedRealtime());
            event.mPackage = packageName;
            event.mExtras = extras;
            reportEventOrAddToQueue(userId, event);
        }

        @Override
        public ParceledListSlice<UsageStats> queryUsageStats(int bucketType, long beginTime,
                long endTime, String callingPackage, int userId) {
            if (!hasQueryPermission(callingPackage)) {
                return null;
            }

            final int callingUid = Binder.getCallingUid();
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), callingUid,
                    userId, false, true, "queryUsageStats", callingPackage);

            // Check the caller's userId for obfuscation decision, not the user being queried
            final boolean obfuscateInstantApps = shouldObfuscateInstantAppsForCaller(
                    callingUid, UserHandle.getCallingUserId());

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
            if (!hasQueryPermission(callingPackage)) {
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
            if (!hasQueryPermission(callingPackage)) {
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
            if (!hasQueryPermission(callingPackage)) {
                return null;
            }

            return queryEventsHelper(UserHandle.getCallingUserId(), beginTime, endTime,
                    callingPackage, /* eventTypeFilter= */ EmptyArray.INT,
                    /* pkgNameFilter= */ null);
        }

        @Override
        public UsageEvents queryEventsWithFilter(@NonNull UsageEventsQuery query,
                @NonNull String callingPackage) {
            Objects.requireNonNull(query);
            Objects.requireNonNull(callingPackage);

            if (!hasQueryPermission(callingPackage)) {
                return null;
            }

            final int callingUserId = UserHandle.getCallingUserId();
            int userId = query.getUserId();
            if (userId == UserHandle.USER_NULL) {
                // Convert userId to actual user Id if not specified in the query object.
                userId = callingUserId;
            }
            if (userId != callingUserId) {
                getContext().enforceCallingPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                        "No permission to query usage stats for user " + userId);
            }

            return queryEventsHelper(userId, query.getBeginTimeMillis(),
                    query.getEndTimeMillis(), callingPackage, query.getEventTypes(),
                    /* pkgNameFilter= */ new ArraySet<>(query.getPackageNames()));
        }

        @Override
        public UsageEvents queryEventsForPackage(long beginTime, long endTime,
                String callingPackage) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);

            checkCallerIsSameApp(callingPackage);
            final boolean includeTaskRoot = hasQueryPermission(callingPackage);

            final long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.queryEventsForPackage(callingUserId, beginTime,
                        endTime, callingPackage, includeTaskRoot);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public UsageEvents queryEventsForUser(long beginTime, long endTime, int userId,
                String callingPackage) {
            if (!hasQueryPermission(callingPackage)) {
                return null;
            }

            final int callingUserId = UserHandle.getCallingUserId();
            if (userId != callingUserId) {
                getContext().enforceCallingPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                        "No permission to query usage stats for this user");
            }

            return queryEventsHelper(userId, beginTime, endTime, callingPackage,
                    /* eventTypeFilter= */ EmptyArray.INT,
                    /* pkgNameFilter= */ null);
        }

        @Override
        public UsageEvents queryEventsForPackageForUser(long beginTime, long endTime,
                int userId, String pkg, String callingPackage) {
            if (!hasQueryPermission(callingPackage)) {
                return null;
            }
            if (userId != UserHandle.getCallingUserId()) {
                getContext().enforceCallingPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                        "No permission to query usage stats for this user");
            }
            checkCallerIsSystemOrSameApp(pkg);

            final long token = Binder.clearCallingIdentity();
            try {
                return UsageStatsService.this.queryEventsForPackage(userId, beginTime,
                        endTime, pkg, true);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isAppStandbyEnabled() {
            return mAppStandby.isAppIdleEnabled();
        }

        @Override
        public boolean isAppInactive(String packageName, int userId, String callingPackage) {
            final int callingUid = Binder.getCallingUid();
            try {
                userId = ActivityManager.getService().handleIncomingUser(Binder.getCallingPid(),
                        callingUid, userId, false, false, "isAppInactive", null);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }

            // If the calling app is asking about itself, continue, else check for permission.
            if (packageName.equals(callingPackage)) {
                final int actualCallingUid = mPackageManagerInternal.getPackageUid(
                        callingPackage, /* flags= */ 0, userId);
                if (actualCallingUid != callingUid) {
                    return false;
                }
            } else if (!hasQueryPermission(callingPackage)) {
                return false;
            }
            final boolean obfuscateInstantApps = shouldObfuscateInstantAppsForCaller(
                    callingUid, userId);
            final long token = Binder.clearCallingIdentity();
            try {
                return mAppStandby.isAppIdleFiltered(
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
            final int packageUid = mPackageManagerInternal.getPackageUid(packageName, 0, userId);
            // If the calling app is asking about itself, continue, else check for permission.
            final boolean sameApp = packageUid == callingUid;
            if (!sameApp && !hasQueryPermission(callingPackage)) {
                throw new SecurityException("Don't have permission to query app standby bucket");
            }

            final boolean isInstantApp = isInstantApp(packageName, userId);
            final boolean cannotAccessInstantApps = shouldObfuscateInstantAppsForCaller(callingUid,
                    userId);
            if (packageUid < 0 || (!sameApp && isInstantApp && cannotAccessInstantApps)) {
                throw new IllegalArgumentException(
                        "Cannot get standby bucket for non existent package (" + packageName + ")");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return mAppStandby.getAppStandbyBucket(packageName, userId,
                        SystemClock.elapsedRealtime(), false /* obfuscateInstantApps */);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CHANGE_APP_IDLE_STATE)
        @Override
        public void setAppStandbyBucket(String packageName, int bucket, int userId) {

            super.setAppStandbyBucket_enforcePermission();

            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final long token = Binder.clearCallingIdentity();
            try {
                mAppStandby.setAppStandbyBucket(packageName, bucket, userId,
                        callingUid, callingPid);
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
            if (!hasQueryPermission(callingPackageName)) {
                throw new SecurityException(
                        "Don't have permission to query app standby bucket");
            }
            final boolean cannotAccessInstantApps = shouldObfuscateInstantAppsForCaller(callingUid,
                    userId);
            final long token = Binder.clearCallingIdentity();
            try {
                final List<AppStandbyInfo> standbyBucketList =
                        mAppStandby.getAppStandbyBuckets(userId);
                if (standbyBucketList == null) {
                    return ParceledListSlice.emptyList();
                }
                final int targetUserId = userId;
                standbyBucketList.removeIf(
                        i -> !sameApp(callingUid, targetUserId, i.mPackageName)
                                && isInstantApp(i.mPackageName, targetUserId)
                                && cannotAccessInstantApps);
                return new ParceledListSlice<>(standbyBucketList);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CHANGE_APP_IDLE_STATE)
        @Override
        public void setAppStandbyBuckets(ParceledListSlice appBuckets, int userId) {

            super.setAppStandbyBuckets_enforcePermission();

            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final long token = Binder.clearCallingIdentity();
            try {
                mAppStandby.setAppStandbyBuckets(appBuckets.getList(), userId,
                        callingUid, callingPid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public int getAppMinStandbyBucket(String packageName, String callingPackage, int userId) {
            final int callingUid = Binder.getCallingUid();
            try {
                userId = ActivityManager.getService().handleIncomingUser(
                        Binder.getCallingPid(), callingUid, userId, false, false,
                        "getAppStandbyBucket", null);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
            final int packageUid = mPackageManagerInternal.getPackageUid(packageName, 0, userId);
            // If the calling app is asking about itself, continue, else check for permission.
            if (packageUid != callingUid) {
                if (!hasQueryPermission(callingPackage)) {
                    throw new SecurityException(
                            "Don't have permission to query min app standby bucket");
                }
            }
            final boolean isInstantApp = isInstantApp(packageName, userId);
            final boolean cannotAccessInstantApps = shouldObfuscateInstantAppsForCaller(callingUid,
                    userId);
            if (packageUid < 0 || (isInstantApp && cannotAccessInstantApps)) {
                throw new IllegalArgumentException(
                        "Cannot get min standby bucket for non existent package ("
                                + packageName + ")");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                return mAppStandby.getAppMinStandbyBucket(packageName,
                        UserHandle.getAppId(packageUid), userId, false /* obfuscateInstantApps */);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CHANGE_APP_LAUNCH_TIME_ESTIMATE)
        @Override
        public void setEstimatedLaunchTime(String packageName, long estimatedLaunchTime,
                int userId) {

            super.setEstimatedLaunchTime_enforcePermission();

            final long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this
                        .setEstimatedLaunchTime(userId, packageName, estimatedLaunchTime);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CHANGE_APP_LAUNCH_TIME_ESTIMATE)
        @Override
        public void setEstimatedLaunchTimes(ParceledListSlice estimatedLaunchTimes, int userId) {

            super.setEstimatedLaunchTimes_enforcePermission();

            final long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this
                        .setEstimatedLaunchTimes(userId, estimatedLaunchTimes.getList());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
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
        public void reportChooserSelection(@NonNull String packageName, int userId,
                @NonNull String contentType, String[] annotations, @NonNull String action) {
            if (packageName == null) {
                throw new IllegalArgumentException("Package selection must not be null.");
            }
            // A valid contentType and action must be provided for chooser selection events.
            if (contentType == null || contentType.isBlank()
                    || action == null || action.isBlank()) {
                return;
            }

            if (Flags.reportUsageStatsPermission()) {
                if (!canReportUsageStats()) {
                    throw new SecurityException(
                        "Only the system or holders of the REPORT_USAGE_STATS"
                            + " permission are allowed to call reportChooserSelection");
                }
            }

            // Verify if this package exists before reporting an event for it.
            if (mPackageManagerInternal.getPackageUid(packageName, 0, userId) < 0) {
                Slog.w(TAG, "Event report user selecting an invalid package");
                return;
            }

            Event event = new Event(CHOOSER_ACTION, SystemClock.elapsedRealtime());
            event.mPackage = packageName;
            event.mAction = action;
            event.mContentType = contentType;
            event.mContentAnnotations = annotations;
            reportEventOrAddToQueue(userId, event);
        }

        @Override
        public void reportUserInteraction(String packageName, int userId) {
            reportUserInteractionInnerHelper(packageName, userId, null);
        }

        @Override
        public void reportUserInteractionWithBundle(String packageName, @UserIdInt int userId,
                PersistableBundle extras) {
            Objects.requireNonNull(packageName);
            if (extras == null || extras.size() == 0) {
                throw new IllegalArgumentException("Emtry extras!");
            }

            // Only category/action are allowed now, other unknown keys will be trimmed.
            // Also, empty category/action is not meanful.
            String category = extras.getString(UsageStatsManager.EXTRA_EVENT_CATEGORY);
            if (TextUtils.isEmpty(category)) {
                throw new IllegalArgumentException("Empty "
                        + UsageStatsManager.EXTRA_EVENT_CATEGORY);
            }
            String action = extras.getString(UsageStatsManager.EXTRA_EVENT_ACTION);
            if (TextUtils.isEmpty(action)) {
                throw new IllegalArgumentException("Empty "
                        + UsageStatsManager.EXTRA_EVENT_ACTION);
            }

            PersistableBundle extrasCopy = new PersistableBundle();
            extrasCopy.putString(UsageStatsManager.EXTRA_EVENT_CATEGORY,
                    getTrimmedString(category));
            extrasCopy.putString(UsageStatsManager.EXTRA_EVENT_ACTION, getTrimmedString(action));

            reportUserInteractionInnerHelper(packageName, userId, extrasCopy);
        }

        @Override
        public void registerAppUsageObserver(int observerId,
                String[] packages, long timeLimitMs, PendingIntent
                callbackIntent, String callingPackage) {
            if (!hasObserverPermission()) {
                throw new SecurityException("Caller doesn't have OBSERVE_APP_USAGE permission");
            }

            if (packages == null || packages.length == 0) {
                throw new IllegalArgumentException("Must specify at least one package");
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
            if (!hasObserverPermission()) {
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

        @Override
        public void registerUsageSessionObserver(int sessionObserverId, String[] observed,
                long timeLimitMs, long sessionThresholdTimeMs,
                PendingIntent limitReachedCallbackIntent, PendingIntent sessionEndCallbackIntent,
                String callingPackage) {
            if (!hasObserverPermission()) {
                throw new SecurityException("Caller doesn't have OBSERVE_APP_USAGE permission");
            }

            if (observed == null || observed.length == 0) {
                throw new IllegalArgumentException("Must specify at least one observed entity");
            }
            if (limitReachedCallbackIntent == null) {
                throw new NullPointerException("limitReachedCallbackIntent can't be null");
            }
            final int callingUid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(callingUid);
            final long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.registerUsageSessionObserver(callingUid, sessionObserverId,
                        observed, timeLimitMs, sessionThresholdTimeMs, limitReachedCallbackIntent,
                        sessionEndCallbackIntent, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void unregisterUsageSessionObserver(int sessionObserverId, String callingPackage) {
            if (!hasObserverPermission()) {
                throw new SecurityException("Caller doesn't have OBSERVE_APP_USAGE permission");
            }

            final int callingUid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(callingUid);
            final long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.unregisterUsageSessionObserver(callingUid, sessionObserverId,
                        userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void registerAppUsageLimitObserver(int observerId, String[] packages,
                long timeLimitMs, long timeUsedMs, PendingIntent callbackIntent,
                String callingPackage) {
            final int callingUid = Binder.getCallingUid();
            final DevicePolicyManagerInternal dpmInternal = getDpmInternal();
            if (!hasPermissions(
                    Manifest.permission.SUSPEND_APPS, Manifest.permission.OBSERVE_APP_USAGE)
                    && (dpmInternal == null || !dpmInternal.isActiveSupervisionApp(callingUid))) {
                throw new SecurityException("Caller must be the active supervision app or "
                        + "it must have both SUSPEND_APPS and OBSERVE_APP_USAGE permissions");
            }

            if (packages == null || packages.length == 0) {
                throw new IllegalArgumentException("Must specify at least one package");
            }
            if (callbackIntent == null && timeUsedMs < timeLimitMs) {
                throw new NullPointerException("callbackIntent can't be null");
            }
            final int userId = UserHandle.getUserId(callingUid);
            final long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.registerAppUsageLimitObserver(callingUid, observerId,
                        packages, timeLimitMs, timeUsedMs, callbackIntent, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void unregisterAppUsageLimitObserver(int observerId, String callingPackage) {
            final int callingUid = Binder.getCallingUid();
            final DevicePolicyManagerInternal dpmInternal = getDpmInternal();
            if (!hasPermissions(
                    Manifest.permission.SUSPEND_APPS, Manifest.permission.OBSERVE_APP_USAGE)
                    && (dpmInternal == null || !dpmInternal.isActiveSupervisionApp(callingUid))) {
                throw new SecurityException("Caller must be the active supervision app or "
                        + "it must have both SUSPEND_APPS and OBSERVE_APP_USAGE permissions");
            }

            final int userId = UserHandle.getUserId(callingUid);
            final long token = Binder.clearCallingIdentity();
            try {
                UsageStatsService.this.unregisterAppUsageLimitObserver(
                        callingUid, observerId, userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void reportUsageStart(IBinder activity, String token, String callingPackage) {
            reportPastUsageStart(activity, token, 0, callingPackage);
        }

        @Override
        public void reportPastUsageStart(IBinder activity, String token, long timeAgoMs,
                String callingPackage) {

            final int callingUid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(callingUid);
            final long binderToken = Binder.clearCallingIdentity();
            try {
                ArraySet<String> tokens;
                synchronized (mUsageReporters) {
                    tokens = mUsageReporters.get(activity.hashCode());
                    if (tokens == null) {
                        tokens = new ArraySet();
                        mUsageReporters.put(activity.hashCode(), tokens);
                    }
                }

                synchronized (tokens) {
                    if (!tokens.add(token)) {
                        throw new IllegalArgumentException(token + " for " + callingPackage
                                + " is already reported as started for this activity");
                    }
                }

                mAppTimeLimit.noteUsageStart(buildFullToken(callingPackage, token),
                        userId, timeAgoMs);
            } finally {
                Binder.restoreCallingIdentity(binderToken);
            }
        }

        @Override
        public void reportUsageStop(IBinder activity, String token, String callingPackage) {
            final int callingUid = Binder.getCallingUid();
            final int userId = UserHandle.getUserId(callingUid);
            final long binderToken = Binder.clearCallingIdentity();
            try {
                ArraySet<String> tokens;
                synchronized (mUsageReporters) {
                    tokens = mUsageReporters.get(activity.hashCode());
                    if (tokens == null) {
                        throw new IllegalArgumentException(
                                "Unknown reporter trying to stop token " + token + " for "
                                        + callingPackage);
                    }
                }

                synchronized (tokens) {
                    if (!tokens.remove(token)) {
                        throw new IllegalArgumentException(token + " for " + callingPackage
                                + " is already reported as stopped for this activity");
                    }
                }
                mAppTimeLimit.noteUsageStop(buildFullToken(callingPackage, token), userId);
            } finally {
                Binder.restoreCallingIdentity(binderToken);
            }
        }

        @Override
        public @UsageSource int getUsageSource() {
            if (!hasObserverPermission()) {
                throw new SecurityException("Caller doesn't have OBSERVE_APP_USAGE permission");
            }
            synchronized (mLock) {
                return mUsageSource;
            }
        }

        @Override
        public void forceUsageSourceSettingRead() {
            readUsageSourceSetting();
        }

        @Override
        public long getLastTimeAnyComponentUsed(String packageName, String callingPackage) {
            if (!hasPermissions(android.Manifest.permission.INTERACT_ACROSS_USERS)) {
                throw new SecurityException("Caller doesn't have INTERACT_ACROSS_USERS permission");
            }
            if (!hasQueryPermission(callingPackage)) {
                throw new SecurityException("Don't have permission to query usage stats");
            }
            synchronized (mLock) {
                // Truncate the returned milliseconds to the boundary of the last day before exact
                // time for privacy reasons.
                return mLastTimeComponentUsedGlobal.getOrDefault(packageName, 0L)
                        / TimeUnit.DAYS.toMillis(1) * TimeUnit.DAYS.toMillis(1);
            }
        }

        @Override
        @NonNull
        public BroadcastResponseStatsList queryBroadcastResponseStats(
                @Nullable String packageName,
                @IntRange(from = 0) long id,
                @NonNull String callingPackage,
                @UserIdInt int userId) {
            Objects.requireNonNull(callingPackage);
            // TODO: Move to Preconditions utility class
            if (id < 0) {
                throw new IllegalArgumentException("id needs to be >=0");
            }

            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_BROADCAST_RESPONSE_STATS,
                    "queryBroadcastResponseStats");
            final int callingUid = Binder.getCallingUid();
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), callingUid,
                    userId, false /* allowAll */, false /* requireFull */,
                    "queryBroadcastResponseStats" /* name */, callingPackage);
            return new BroadcastResponseStatsList(
                    mResponseStatsTracker.queryBroadcastResponseStats(
                            callingUid, packageName, id, userId));
        }

        @Override
        public void clearBroadcastResponseStats(
                @NonNull String packageName,
                @IntRange(from = 1) long id,
                @NonNull String callingPackage,
                @UserIdInt int userId) {
            Objects.requireNonNull(callingPackage);
            if (id < 0) {
                throw new IllegalArgumentException("id needs to be >=0");
            }


            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_BROADCAST_RESPONSE_STATS,
                    "clearBroadcastResponseStats");
            final int callingUid = Binder.getCallingUid();
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), callingUid,
                    userId, false /* allowAll */, false /* requireFull */,
                    "clearBroadcastResponseStats" /* name */, callingPackage);
            mResponseStatsTracker.clearBroadcastResponseStats(callingUid,
                    packageName, id, userId);
        }

        @Override
        public void clearBroadcastEvents(@NonNull String callingPackage, @UserIdInt int userId) {
            Objects.requireNonNull(callingPackage);

            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_BROADCAST_RESPONSE_STATS,
                    "clearBroadcastEvents");
            final int callingUid = Binder.getCallingUid();
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), callingUid,
                    userId, false /* allowAll */, false /* requireFull */,
                    "clearBroadcastResponseStats" /* name */, callingPackage);
            mResponseStatsTracker.clearBroadcastEvents(callingUid, userId);
        }

        @Override
        public boolean isPackageExemptedFromBroadcastResponseStats(@NonNull String callingPackage,
                @UserIdInt int userId) {
            Objects.requireNonNull(callingPackage);

            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.DUMP,
                    "isPackageExemptedFromBroadcastResponseStats");
            return mResponseStatsTracker.isPackageExemptedFromBroadcastResponseStats(
                    callingPackage, UserHandle.of(userId));
        }

        @Override
        @Nullable
        public String getAppStandbyConstant(@NonNull String key) {
            Objects.requireNonNull(key);

            if (!hasPermissions(Manifest.permission.READ_DEVICE_CONFIG)) {
                throw new SecurityException("Caller doesn't have READ_DEVICE_CONFIG permission");
            }
            return mAppStandby.getAppStandbyConstant(key);
        }

        @Override
        public int handleShellCommand(@NonNull ParcelFileDescriptor in,
                @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
                @NonNull String[] args) {
            return new UsageStatsShellCommand(UsageStatsService.this).exec(this,
                    in.getFileDescriptor(), out.getFileDescriptor(), err.getFileDescriptor(), args);
        }
    }

    void registerAppUsageObserver(int callingUid, int observerId, String[] packages,
            long timeLimitMs, PendingIntent callbackIntent, int userId) {
        mAppTimeLimit.addAppUsageObserver(callingUid, observerId, packages, timeLimitMs,
                callbackIntent,
                userId);
    }

    void unregisterAppUsageObserver(int callingUid, int observerId, int userId) {
        mAppTimeLimit.removeAppUsageObserver(callingUid, observerId, userId);
    }

    void registerUsageSessionObserver(int callingUid, int observerId, String[] observed,
            long timeLimitMs, long sessionThresholdTime, PendingIntent limitReachedCallbackIntent,
            PendingIntent sessionEndCallbackIntent, int userId) {
        mAppTimeLimit.addUsageSessionObserver(callingUid, observerId, observed, timeLimitMs,
                sessionThresholdTime, limitReachedCallbackIntent, sessionEndCallbackIntent, userId);
    }

    void unregisterUsageSessionObserver(int callingUid, int sessionObserverId, int userId) {
        mAppTimeLimit.removeUsageSessionObserver(callingUid, sessionObserverId, userId);
    }

    void registerAppUsageLimitObserver(int callingUid, int observerId, String[] packages,
            long timeLimitMs, long timeUsedMs, PendingIntent callbackIntent, int userId) {
        mAppTimeLimit.addAppUsageLimitObserver(callingUid, observerId, packages,
                timeLimitMs, timeUsedMs, callbackIntent, userId);
    }

    void unregisterAppUsageLimitObserver(int callingUid, int observerId, int userId) {
        mAppTimeLimit.removeAppUsageLimitObserver(callingUid, observerId, userId);
    }

    /**
     * This local service implementation is primarily used by ActivityManagerService.
     * ActivityManagerService will call these methods holding the 'am' lock, which means we
     * shouldn't be doing any IO work or other long running tasks in these methods.
     */
    private final class LocalService extends UsageStatsManagerInternal {

        @Override
        public void reportEvent(ComponentName component, int userId, int eventType,
                int instanceId, ComponentName taskRoot) {
            if (component == null) {
                Slog.w(TAG, "Event reported without a component name");
                return;
            }

            Event event = new Event(eventType, SystemClock.elapsedRealtime());
            event.mPackage = component.getPackageName();
            event.mClass = component.getClassName();
            event.mInstanceId = instanceId;
            if (taskRoot == null) {
                event.mTaskRootPackage = null;
                event.mTaskRootClass = null;
            } else {
                event.mTaskRootPackage = taskRoot.getPackageName();
                event.mTaskRootClass = taskRoot.getClassName();
            }
            reportEventOrAddToQueue(userId, event);
        }

        @Override
        public void reportEvent(String packageName, int userId, int eventType) {
            if (packageName == null) {
                Slog.w(TAG, "Event reported without a package name, eventType:" + eventType);
                return;
            }

            Event event = new Event(eventType, SystemClock.elapsedRealtime());
            event.mPackage = packageName;
            reportEventOrAddToQueue(userId, event);
        }

        @Override
        public void reportConfigurationChange(Configuration config, int userId) {
            if (config == null) {
                Slog.w(TAG, "Configuration event reported with a null config");
                return;
            }

            Event event = new Event(CONFIGURATION_CHANGE, SystemClock.elapsedRealtime());
            event.mPackage = "android";
            event.mConfiguration = new Configuration(config);
            reportEventOrAddToQueue(userId, event);
        }

        @Override
        public void reportInterruptiveNotification(String packageName, String channelId,
                int userId) {
            if (packageName == null || channelId == null) {
                Slog.w(TAG, "Event reported without a package name or a channel ID");
                return;
            }

            Event event = new Event(NOTIFICATION_INTERRUPTION, SystemClock.elapsedRealtime());
            event.mPackage = packageName.intern();
            event.mNotificationChannelId = channelId.intern();
            reportEventOrAddToQueue(userId, event);
        }

        @Override
        public void reportShortcutUsage(String packageName, String shortcutId, int userId) {
            if (packageName == null || shortcutId == null) {
                Slog.w(TAG, "Event reported without a package name or a shortcut ID");
                return;
            }

            Event event = new Event(SHORTCUT_INVOCATION, SystemClock.elapsedRealtime());
            event.mPackage = packageName.intern();
            event.mShortcutId = shortcutId.intern();
            reportEventOrAddToQueue(userId, event);
        }

        @Override
        public void reportLocusUpdate(@NonNull ComponentName activity, @UserIdInt int userId,
                @Nullable LocusId locusId, @NonNull  IBinder appToken) {
            if (locusId == null) return;
            Event event = new Event(LOCUS_ID_SET, SystemClock.elapsedRealtime());
            event.mLocusId = locusId.getId();
            event.mPackage = activity.getPackageName();
            event.mClass = activity.getClassName();
            event.mInstanceId = appToken.hashCode();
            reportEventOrAddToQueue(userId, event);
        }

        @Override
        public void reportContentProviderUsage(String name, String packageName, int userId) {
            mAppStandby.postReportContentProviderUsage(name, packageName, userId);
        }

        @Override
        public void reportUserInteractionEvent(@NonNull String pkgName, @UserIdInt int userId,
                @NonNull PersistableBundle extras) {
            if (extras != null && extras.size() != 0) {
                // Truncate the value if necessary.
                String category = extras.getString(UsageStatsManager.EXTRA_EVENT_CATEGORY);
                String action = extras.getString(UsageStatsManager.EXTRA_EVENT_ACTION);
                extras.putString(UsageStatsManager.EXTRA_EVENT_CATEGORY,
                        getTrimmedString(category));
                extras.putString(UsageStatsManager.EXTRA_EVENT_ACTION, getTrimmedString(action));
            }

            Event event = new Event(USER_INTERACTION, SystemClock.elapsedRealtime());
            event.mPackage = pkgName;
            event.mExtras = extras;
            reportEventOrAddToQueue(userId, event);
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
        public void prepareShutdown() {
            // This method *WILL* do IO work, but we must block until it is finished or else
            // we might not shutdown cleanly. This is ok to do with the 'am' lock held, because
            // we are shutting down.
            UsageStatsService.this.shutdown();
        }

        @Override
        public void prepareForPossibleShutdown() {
            UsageStatsService.this.prepareForPossibleShutdown();
        }

        @Override
        public byte[] getBackupPayload(@UserIdInt int userId, String key) {
            if (!mUserUnlockedStates.contains(userId)) {
                Slog.w(TAG, "Failed to get backup payload for locked user " + userId);
                return null;
            }
            synchronized (mLock) {
                final UserUsageStatsService userStats = getUserUsageStatsServiceLocked(userId);
                if (userStats == null) {
                    return null; // user was stopped or removed
                }
                Slog.i(TAG, "Returning backup payload for u=" + userId);
                return userStats.getBackupPayload(key);
            }
        }

        @Override
        public void applyRestoredPayload(@UserIdInt int userId, String key, byte[] payload) {
            synchronized (mLock) {
                if (!mUserUnlockedStates.contains(userId)) {
                    Slog.w(TAG, "Failed to apply restored payload for locked user " + userId);
                    return;
                }

                final UserUsageStatsService userStats = getUserUsageStatsServiceLocked(userId);
                if (userStats == null) {
                    return; // user was stopped or removed
                }
                final Set<String> restoredApps = userStats.applyRestoredPayload(key, payload);
                mAppStandby.restoreAppsToRare(restoredApps, userId);
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
        public UsageEvents queryEventsForUser(int userId, long beginTime, long endTime, int flags) {
            return UsageStatsService.this.queryEvents(userId, beginTime, endTime, flags);
        }

        @Override
        public void setLastJobRunTime(String packageName, int userId, long elapsedRealtime) {
            mAppStandby.setLastJobRunTime(packageName, userId, elapsedRealtime);
        }

        @Override
        public long getEstimatedPackageLaunchTime(String packageName, int userId) {
            return UsageStatsService.this.getEstimatedPackageLaunchTime(userId, packageName);
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
        public void setAdminProtectedPackages(Set<String> packageNames, int userId) {
            mAppStandby.setAdminProtectedPackages(packageNames, userId);
        }

        @Override
        public void onAdminDataAvailable() {
            mAppStandby.onAdminDataAvailable();
        }

        @Override
        public void reportSyncScheduled(String packageName, int userId, boolean exempted) {
            mAppStandby.postReportSyncScheduled(packageName, userId, exempted);
        }

        @Override
        public void reportExemptedSyncStart(String packageName, int userId) {
            mAppStandby.postReportExemptedSyncStart(packageName, userId);
        }

        @Override
        public AppUsageLimitData getAppUsageLimit(String packageName, UserHandle user) {
            return mAppTimeLimit.getAppUsageLimit(packageName, user);
        }

        @Override
        public boolean pruneUninstalledPackagesData(int userId) {
            return UsageStatsService.this.pruneUninstalledPackagesData(userId);
        }

        @Override
        public boolean updatePackageMappingsData(@UserIdInt int userId) {
            return UsageStatsService.this.updatePackageMappingsData(userId);
        }

        /**
         * Register a listener that will be notified of every new usage event.
         */
        @Override
        public void registerListener(@NonNull UsageEventListener listener) {
            UsageStatsService.this.registerListener(listener);
        }

        /**
         * Unregister a listener from being notified of every new usage event.
         */
        @Override
        public void unregisterListener(@NonNull UsageEventListener listener) {
            UsageStatsService.this.unregisterListener(listener);
        }

        @Override
        public void registerLaunchTimeChangedListener(
                @NonNull EstimatedLaunchTimeChangedListener listener) {
            UsageStatsService.this.registerLaunchTimeChangedListener(listener);
        }

        @Override
        public void unregisterLaunchTimeChangedListener(
                @NonNull EstimatedLaunchTimeChangedListener listener) {
            UsageStatsService.this.unregisterLaunchTimeChangedListener(listener);
        }

        @Override
        public void reportBroadcastDispatched(int sourceUid, @NonNull String targetPackage,
                @NonNull UserHandle targetUser, long idForResponseEvent,
                @ElapsedRealtimeLong long timestampMs, @ProcessState int targetUidProcState) {
            mResponseStatsTracker.reportBroadcastDispatchEvent(sourceUid, targetPackage,
                    targetUser, idForResponseEvent, timestampMs, targetUidProcState);
        }

        @Override
        public void reportNotificationPosted(@NonNull String packageName,
                @NonNull UserHandle user, @ElapsedRealtimeLong long timestampMs) {
            mResponseStatsTracker.reportNotificationPosted(packageName, user, timestampMs);
        }

        @Override
        public void reportNotificationUpdated(@NonNull String packageName,
                @NonNull UserHandle user, @ElapsedRealtimeLong long timestampMs) {
            mResponseStatsTracker.reportNotificationUpdated(packageName, user, timestampMs);
        }

        @Override
        public void reportNotificationRemoved(@NonNull String packageName,
                @NonNull UserHandle user, @ElapsedRealtimeLong long timestampMs) {
            mResponseStatsTracker.reportNotificationCancelled(packageName, user, timestampMs);
        }
    }

    private class MyPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageRemoved(String packageName, int uid) {
            final int changingUserId = getChangingUserId();
            // Only remove the package's data if a profile owner is not defined for the user
            if (shouldDeleteObsoleteData(UserHandle.of(changingUserId))) {
                mHandler.obtainMessage(MSG_PACKAGE_REMOVED, changingUserId, 0, packageName)
                        .sendToTarget();
            }
            mResponseStatsTracker.onPackageRemoved(packageName, UserHandle.getUserId(uid));
            super.onPackageRemoved(packageName, uid);
        }
    }
}
