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
import static android.app.usage.UsageEvents.Event.USER_STOPPED;
import static android.app.usage.UsageEvents.Event.USER_UNLOCKED;
import static android.app.usage.UsageStatsManager.USAGE_SOURCE_CURRENT_ACTIVITY;
import static android.app.usage.UsageStatsManager.USAGE_SOURCE_TASK_ROOT_ACTIVITY;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
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
import android.app.usage.UsageEvents.Event;
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
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;

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
import java.util.Set;

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
    static final long TIME_CHANGE_THRESHOLD_MILLIS = 2 * 1000; // Two seconds.

    private static final boolean ENABLE_KERNEL_UPDATES = true;
    private static final File KERNEL_COUNTER_FILE = new File("/proc/uid_procstat/set");

    private static final File USAGE_STATS_LEGACY_DIR = new File(
            Environment.getDataSystemDirectory(), "usagestats");
    // For migration purposes, indicates whether to keep the legacy usage stats directory or not
    private static final boolean KEEP_LEGACY_DIR = false;

    private static final char TOKEN_DELIMITER = '/';

    // Handler message types.
    static final int MSG_REPORT_EVENT = 0;
    static final int MSG_FLUSH_TO_DISK = 1;
    static final int MSG_REMOVE_USER = 2;
    static final int MSG_UID_STATE_CHANGED = 3;
    static final int MSG_REPORT_EVENT_TO_ALL_USERID = 4;
    static final int MSG_UNLOCKED_USER = 5;
    static final int MSG_PACKAGE_REMOVED = 6;

    private final Object mLock = new Object();
    Handler mHandler;
    AppOpsManager mAppOps;
    UserManager mUserManager;
    PackageManager mPackageManager;
    PackageManagerInternal mPackageManagerInternal;
    // Do not use directly. Call getDpmInternal() instead
    DevicePolicyManagerInternal mDpmInternal;
    // Do not use directly. Call getShortcutServiceInternal() instead
    ShortcutServiceInternal mShortcutServiceInternal;

    private final SparseArray<UserUsageStatsService> mUserState = new SparseArray<>();
    private final SparseBooleanArray mUserUnlockedStates = new SparseBooleanArray();
    private final SparseIntArray mUidToKernelCounter = new SparseIntArray();
    int mUsageSource;

    /** Manages the standby state of apps. */
    AppStandbyInternal mAppStandby;

    /** Manages app time limit observers */
    AppTimeLimitController mAppTimeLimit;

    private final PackageMonitor mPackageMonitor = new MyPackageMonitor();

    // A map maintaining a queue of events to be reported per user.
    private final SparseArray<LinkedList<Event>> mReportedEvents = new SparseArray<>();
    final SparseArray<ArraySet<String>> mUsageReporters = new SparseArray();
    final SparseArray<ActivityData> mVisibleActivities = new SparseArray();

    private static class ActivityData {
        private final String mTaskRootPackage;
        private final String mTaskRootClass;
        public int lastEvent = Event.NONE;
        private ActivityData(String taskRootPackage, String taskRootClass) {
            mTaskRootPackage = taskRootPackage;
            mTaskRootClass = taskRootClass;
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

    public UsageStatsService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mUserManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
        mPackageManager = getContext().getPackageManager();
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mHandler = new H(BackgroundThread.get().getLooper());

        mAppStandby = AppStandbyInternal.newAppStandbyController(
                UsageStatsService.class.getClassLoader(), getContext(),
                BackgroundThread.get().getLooper());

        mAppTimeLimit = new AppTimeLimitController(
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
                null, mHandler);

        publishLocalService(UsageStatsManagerInternal.class, new LocalService());
        publishLocalService(AppStandbyInternal.class, mAppStandby);
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
    public void onStartUser(UserInfo userInfo) {
        // Create an entry in the user state map to indicate that the user has been started but
        // not necessarily unlocked. This will ensure that reported events are flushed to disk
        // event if the user is never unlocked (following the logic in #flushToDiskLocked)
        mUserState.put(userInfo.id, null);
        super.onStartUser(userInfo);
    }

    @Override
    public void onUnlockUser(@NonNull UserInfo userInfo) {
        mHandler.obtainMessage(MSG_UNLOCKED_USER, userInfo.id, 0).sendToTarget();
        super.onUnlockUser(userInfo);
    }

    @Override
    public void onStopUser(@NonNull UserInfo userInfo) {
        synchronized (mLock) {
            // User was started but never unlocked so no need to report a user stopped event
            if (!mUserUnlockedStates.get(userInfo.id)) {
                persistPendingEventsLocked(userInfo.id);
                super.onStopUser(userInfo);
                return;
            }

            // Report a user stopped event before persisting all stats to disk via the user service
            final Event event = new Event(USER_STOPPED, SystemClock.elapsedRealtime());
            event.mPackage = Event.DEVICE_EVENT_PACKAGE_NAME;
            reportEvent(event, userInfo.id);
            final UserUsageStatsService userService = mUserState.get(userInfo.id);
            if (userService != null) {
                userService.userStopped();
            }
            mUserUnlockedStates.put(userInfo.id, false);
            mUserState.put(userInfo.id, null); // release the service (mainly for GC)
        }
        super.onStopUser(userInfo);
    }

    private void onUserUnlocked(int userId) {
        // fetch the installed packages outside the lock so it doesn't block package manager.
        final HashMap<String, Long> installedPackages = getInstalledPackages(userId);
        // delay updating of package mappings for user 0 since their data is not likely to be stale.
        // this also makes it less likely for restored data to be erased on unexpected reboots.
        if (userId == UserHandle.USER_SYSTEM) {
            UsageStatsIdleService.scheduleUpdateMappingsJob(getContext());
        }
        synchronized (mLock) {
            // Create a user unlocked event to report
            final Event unlockEvent = new Event(USER_UNLOCKED, SystemClock.elapsedRealtime());
            unlockEvent.mPackage = Event.DEVICE_EVENT_PACKAGE_NAME;

            migrateStatsToSystemCeIfNeededLocked(userId);

            // Read pending reported events from disk and merge them with those stored in memory
            final LinkedList<Event> pendingEvents = new LinkedList<>();
            loadPendingEventsLocked(userId, pendingEvents);
            final LinkedList<Event> eventsInMem = mReportedEvents.get(userId);
            if (eventsInMem != null) {
                pendingEvents.addAll(eventsInMem);
            }
            boolean needToFlush = !pendingEvents.isEmpty();

            initializeUserUsageStatsServiceLocked(userId, System.currentTimeMillis(),
                    installedPackages);
            mUserUnlockedStates.put(userId, true);
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

            // Remove all the stats stored in memory and in system DE.
            mReportedEvents.remove(userId);
            deleteRecursively(new File(Environment.getDataSystemDeDirectory(userId), "usagestats"));
            // Force a flush to disk for the current user to ensure important events are persisted.
            // Note: there is a very very small chance that the system crashes between deleting
            // the stats above from DE and persisting them to CE here in which case we will lose
            // those events that were in memory and deleted from DE. (b/139836090)
            if (needToFlush) {
                userService.persistActiveStats();
            }
        }
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
                    mAppStandby.postCheckIdleStates(userId);
                }
            }
        }
    }

    private final IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            mHandler.obtainMessage(MSG_UID_STATE_CHANGED, uid, procState).sendToTarget();
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) {
            // Ignored
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            onUidStateChanged(uid, ActivityManager.PROCESS_STATE_NONEXISTENT, 0,
                    ActivityManager.PROCESS_CAPABILITY_NONE);
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
     * not hold the {@link android.Manifest.permission.MANAGE_NOTIFICATIONS} permission.
     */
    private boolean shouldObfuscateNotificationEvents(int callingPid, int callingUid) {
        if (callingUid == Process.SYSTEM_UID) {
            return false;
        }
        return !(getContext().checkPermission(android.Manifest.permission.MANAGE_NOTIFICATIONS,
                callingPid, callingUid) == PackageManager.PERMISSION_GRANTED);
    }

    private static void deleteRecursively(File f) {
        File[] files = f.listFiles();
        if (files != null) {
            for (File subFile : files) {
                deleteRecursively(subFile);
            }
        }

        if (f.exists() && !f.delete()) {
            Slog.e(TAG, "Failed to delete " + f);
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
            HashMap<String, Long> installedPackages) {
        final File usageStatsDir = new File(Environment.getDataSystemCeDirectory(userId),
                "usagestats");
        final UserUsageStatsService service = new UserUsageStatsService(getContext(), userId,
                usageStatsDir, this);
        try {
            service.init(currentTimeMillis, installedPackages);
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
                    deleteLegacyDir(userId);
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
        final File legacyUserDir = new File(USAGE_STATS_LEGACY_DIR, Integer.toString(userId));
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

        // Migration was successful - delete the legacy directory
        deleteLegacyDir(userId);
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

    private void deleteLegacyDir(int userId) {
        final File legacyUserDir = new File(USAGE_STATS_LEGACY_DIR, Integer.toString(userId));
        if (!KEEP_LEGACY_DIR && legacyUserDir.exists()) {
            deleteRecursively(legacyUserDir);
            if (legacyUserDir.exists()) {
                Slog.w(TAG, "Error occurred while attempting to delete legacy usage stats "
                        + "dir for user " + userId);
            }
            // If all users have been migrated, delete the parent legacy usage stats directory
            if (USAGE_STATS_LEGACY_DIR.list() != null
                    && USAGE_STATS_LEGACY_DIR.list().length == 0) {
                if (!USAGE_STATS_LEGACY_DIR.delete()) {
                    Slog.w(TAG, "Error occurred while attempting to delete legacy usage stats dir");
                }
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

    private void persistPendingEventsLocked(int userId) {
        final LinkedList<Event> pendingEvents = mReportedEvents.get(userId);
        if (pendingEvents == null || pendingEvents.isEmpty()) {
            return;
        }

        final File usageStatsDeDir = new File(Environment.getDataSystemDeDirectory(userId),
                "usagestats");
        if (!usageStatsDeDir.mkdirs() && !usageStatsDeDir.exists()) {
            throw new IllegalStateException("Usage stats DE directory does not exist: "
                    + usageStatsDeDir.getAbsolutePath());
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

    private void reportEventOrAddToQueue(int userId, Event event) {
        synchronized (mLock) {
            if (mUserUnlockedStates.get(userId)) {
                mHandler.obtainMessage(MSG_REPORT_EVENT, userId, 0, event).sendToTarget();
                return;
            }

            final LinkedList<Event> events = mReportedEvents.get(userId, new LinkedList<>());
            events.add(event);
            if (mReportedEvents.get(userId) == null) {
                mReportedEvents.put(userId, events);
            }
            if (events.size() == 1) {
                // Every time a file is persisted to disk, mReportedEvents is cleared for this user
                // so trigger a flush to disk every time the first event has been added.
                mHandler.sendEmptyMessageDelayed(MSG_FLUSH_TO_DISK, FLUSH_INTERVAL);
            }
        }
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

        if (event.mPackage != null
                && mPackageManagerInternal.isPackageEphemeral(userId, event.mPackage)) {
            event.mFlags |= Event.FLAG_IS_PACKAGE_INSTANT_APP;
        }

        synchronized (mLock) {
            // This should never be called directly when the user is locked
            if (!mUserUnlockedStates.get(userId)) {
                Slog.wtf(TAG, "Failed to report event for locked user " + userId
                        + " (" + event.mPackage + "/" + event.mClass
                        + " eventType:" + event.mEventType
                        + " instanceId:" + event.mInstanceId + ")");
                return;
            }

            switch (event.mEventType) {
                case Event.ACTIVITY_RESUMED:
                    FrameworkStatsLog.write(
                            FrameworkStatsLog.APP_USAGE_EVENT_OCCURRED,
                            uid,
                            event.mPackage,
                            event.mClass,
                            FrameworkStatsLog
                                    .APP_USAGE_EVENT_OCCURRED__EVENT_TYPE__MOVE_TO_FOREGROUND);
                    // check if this activity has already been resumed
                    if (mVisibleActivities.get(event.mInstanceId) != null) break;
                    final ActivityData resumedData = new ActivityData(event.mTaskRootPackage,
                            event.mTaskRootClass);
                    resumedData.lastEvent = Event.ACTIVITY_RESUMED;
                    mVisibleActivities.put(event.mInstanceId, resumedData);
                    try {
                        switch(mUsageSource) {
                            case USAGE_SOURCE_CURRENT_ACTIVITY:
                                mAppTimeLimit.noteUsageStart(event.mPackage, userId);
                                break;
                            case USAGE_SOURCE_TASK_ROOT_ACTIVITY:
                            default:
                                mAppTimeLimit.noteUsageStart(event.mTaskRootPackage, userId);
                                break;
                        }
                    } catch (IllegalArgumentException iae) {
                        Slog.e(TAG, "Failed to note usage start", iae);
                    }
                    break;
                case Event.ACTIVITY_PAUSED:
                    final ActivityData pausedData = mVisibleActivities.get(event.mInstanceId);
                    if (pausedData == null) {
                        Slog.w(TAG, "Unexpected activity event reported! (" + event.mPackage
                                + "/" + event.mClass + " event : " + event.mEventType
                                + " instanceId : " + event.mInstanceId + ")");
                    } else {
                        pausedData.lastEvent = Event.ACTIVITY_PAUSED;
                        if (event.mTaskRootPackage == null) {
                            // Task Root info is missing. Repair the event based on previous data
                            event.mTaskRootPackage = pausedData.mTaskRootPackage;
                            event.mTaskRootClass = pausedData.mTaskRootClass;
                        }
                    }
                    FrameworkStatsLog.write(
                            FrameworkStatsLog.APP_USAGE_EVENT_OCCURRED,
                            uid,
                            event.mPackage,
                            event.mClass,
                            FrameworkStatsLog
                                .APP_USAGE_EVENT_OCCURRED__EVENT_TYPE__MOVE_TO_BACKGROUND);
                    break;
                case Event.ACTIVITY_DESTROYED:
                    // Treat activity destroys like activity stops.
                    event.mEventType = Event.ACTIVITY_STOPPED;
                    // Fallthrough
                case Event.ACTIVITY_STOPPED:
                    final ActivityData prevData =
                            mVisibleActivities.removeReturnOld(event.mInstanceId);
                    if (prevData == null) {
                        // The activity stop was already handled.
                        return;
                    }

                    if (prevData.lastEvent != Event.ACTIVITY_PAUSED) {
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.APP_USAGE_EVENT_OCCURRED,
                                uid,
                                event.mPackage,
                                event.mClass,
                                FrameworkStatsLog
                                        .APP_USAGE_EVENT_OCCURRED__EVENT_TYPE__MOVE_TO_BACKGROUND);
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
                        switch(mUsageSource) {
                            case USAGE_SOURCE_CURRENT_ACTIVITY:
                                mAppTimeLimit.noteUsageStop(event.mPackage, userId);
                                break;
                            case USAGE_SOURCE_TASK_ROOT_ACTIVITY:
                            default:
                                mAppTimeLimit.noteUsageStop(event.mTaskRootPackage, userId);
                                break;
                        }
                    } catch (IllegalArgumentException iae) {
                        Slog.w(TAG, "Failed to note usage stop", iae);
                    }
                    break;
            }

            final UserUsageStatsService service = getUserUsageStatsServiceLocked(userId);
            if (service == null) {
                return; // user was stopped or removed
            }
            service.reportEvent(event);
        }

        mAppStandby.reportEvent(event, userId);
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
     * Called by the Binder stub.
     */
    void onUserRemoved(int userId) {
        synchronized (mLock) {
            Slog.i(TAG, "Removing user " + userId + " and all data.");
            mUserState.remove(userId);
            mAppTimeLimit.onUserRemoved(userId);
        }
        mAppStandby.onUserRemoved(userId);
        // Cancel any scheduled jobs for this user since the user is being removed.
        UsageStatsIdleService.cancelJob(getContext(), userId);
        UsageStatsIdleService.cancelUpdateMappingsJob(getContext());
    }

    /**
     * Called by the Handler for message MSG_PACKAGE_REMOVED.
     */
    private void onPackageRemoved(int userId, String packageName) {
        final int tokenRemoved;
        synchronized (mLock) {
            final long timeRemoved = System.currentTimeMillis();
            if (!mUserUnlockedStates.get(userId)) {
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
            UsageStatsIdleService.scheduleJob(getContext(), userId);
        }
    }

    /**
     * Called by the Binder stub.
     */
    private boolean pruneUninstalledPackagesData(int userId) {
        synchronized (mLock) {
            if (!mUserUnlockedStates.get(userId)) {
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
    private boolean updatePackageMappingsData() {
        // fetch the installed packages outside the lock so it doesn't block package manager.
        final HashMap<String, Long> installedPkgs = getInstalledPackages(UserHandle.USER_SYSTEM);
        synchronized (mLock) {
            if (!mUserUnlockedStates.get(UserHandle.USER_SYSTEM)) {
                return false; // user is no longer unlocked
            }

            final UserUsageStatsService userService = mUserState.get(UserHandle.USER_SYSTEM);
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
            if (!mUserUnlockedStates.get(userId)) {
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
            if (!mUserUnlockedStates.get(userId)) {
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
            if (!mUserUnlockedStates.get(userId)) {
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
        synchronized (mLock) {
            if (!mUserUnlockedStates.get(userId)) {
                Slog.w(TAG, "Failed to query events for locked user " + userId);
                return null;
            }

            final UserUsageStatsService service = getUserUsageStatsServiceLocked(userId);
            if (service == null) {
                return null; // user was stopped or removed
            }
            return service.queryEvents(beginTime, endTime, flags);
        }
    }

    /**
     * Called by the Binder stub.
     */
    UsageEvents queryEventsForPackage(int userId, long beginTime, long endTime,
            String packageName, boolean includeTaskRoot) {
        synchronized (mLock) {
            if (!mUserUnlockedStates.get(userId)) {
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
            if (!mUserUnlockedStates.get(userId)) {
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
                                if (!mUserUnlockedStates.get(userId)) {
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
                                if (!mUserUnlockedStates.get(userId)) {
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
                    final int userId = parseUserIdFromArgs(args, i, ipw);
                    synchronized (mLock) {
                        if (userId != UserHandle.USER_NULL) {
                            mUserState.get(userId).dumpMappings(ipw);
                        }
                        return;
                    }
                } else if (arg != null && !arg.startsWith("-")) {
                    // Anything else that doesn't start with '-' is a pkg to filter
                    pkgs.add(arg);
                }
            }
        }

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
                if (mUserUnlockedStates.get(userId)) {
                    if (checkin) {
                        mUserState.valueAt(i).checkin(idpw);
                    } else {
                        mUserState.valueAt(i).dump(idpw, pkgs, compact);
                        idpw.println();
                    }
                }
                idpw.decreaseIndent();
            }

            idpw.println();
            idpw.printPair("Usage Source", UsageStatsManager.usageSourceToString(mUsageSource));
            idpw.println();

            mAppTimeLimit.dump(null, pw);
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
        if (!mUserUnlockedStates.get(userId)) {
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
                case MSG_UNLOCKED_USER:
                    try {
                        onUserUnlocked(msg.arg1);
                    } catch (Exception e) {
                        if (mUserManager.isUserUnlocked(msg.arg1)) {
                            throw e; // rethrow exception - user is unlocked
                        } else {
                            Slog.w(TAG, "Attempted to unlock stopped or removed user " + msg.arg1);
                        }
                    }
                    break;
                case MSG_REMOVE_USER:
                    onUserRemoved(msg.arg1);
                    break;
                case MSG_PACKAGE_REMOVED:
                    onPackageRemoved(msg.arg1, (String) msg.obj);
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

        private boolean hasObserverPermission() {
            final int callingUid = Binder.getCallingUid();
            DevicePolicyManagerInternal dpmInternal = getDpmInternal();
            if (callingUid == Process.SYSTEM_UID
                    || (dpmInternal != null
                        && dpmInternal.isActiveAdminWithPolicy(callingUid,
                            DeviceAdminInfo.USES_POLICY_PROFILE_OWNER))) {
                // Caller is the system or the profile owner, so proceed.
                return true;
            }
            return getContext().checkCallingPermission(Manifest.permission.OBSERVE_APP_USAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }

        private boolean hasPermissions(String callingPackage, String... permissions) {
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

            final int userId = UserHandle.getCallingUserId();
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final boolean obfuscateInstantApps = shouldObfuscateInstantAppsForCaller(
                    callingUid, userId);

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
                return UsageStatsService.this.queryEvents(userId, beginTime, endTime, flags);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public UsageEvents queryEventsForPackage(long beginTime, long endTime,
                String callingPackage) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);

            checkCallerIsSameApp(callingPackage);
            final boolean includeTaskRoot = hasPermission(callingPackage);

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
            if (!hasPermission(callingPackage)) {
                return null;
            }

            final int callingUserId = UserHandle.getCallingUserId();
            if (userId != callingUserId) {
                getContext().enforceCallingPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                        "No permission to query usage stats for this user");
            }

            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final boolean obfuscateInstantApps = shouldObfuscateInstantAppsForCaller(
                    callingUid, callingUserId);

            final long token = Binder.clearCallingIdentity();
            try {
                final boolean hideShortcutInvocationEvents = shouldHideShortcutInvocationEvents(
                        userId, callingPackage, callingPid, callingUid);
                final boolean obfuscateNotificationEvents = shouldObfuscateNotificationEvents(
                        callingPid, callingUid);
                boolean hideLocusIdEvents = shouldHideLocusIdEvents(callingPid, callingUid);
                int flags = UsageEvents.SHOW_ALL_EVENT_DATA;
                if (obfuscateInstantApps) flags |= UsageEvents.OBFUSCATE_INSTANT_APPS;
                if (hideShortcutInvocationEvents) flags |= UsageEvents.HIDE_SHORTCUT_EVENTS;
                if (hideLocusIdEvents) flags |= UsageEvents.HIDE_LOCUS_EVENTS;
                if (obfuscateNotificationEvents) flags |= UsageEvents.OBFUSCATE_NOTIFICATION_EVENTS;
                return UsageStatsService.this.queryEvents(userId, beginTime, endTime, flags);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public UsageEvents queryEventsForPackageForUser(long beginTime, long endTime,
                int userId, String pkg, String callingPackage) {
            if (!hasPermission(callingPackage)) {
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
                final int actualCallingUid = mPackageManagerInternal.getPackageUidInternal(
                        callingPackage, 0, userId);
                if (actualCallingUid != callingUid) {
                    return false;
                }
            } else if (!hasPermission(callingPackage)) {
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
        public void setAppStandbyBucket(String packageName, int bucket, int userId) {
            getContext().enforceCallingPermission(Manifest.permission.CHANGE_APP_IDLE_STATE,
                    "No permission to change app standby state");

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

            Event event = new Event(CHOOSER_ACTION, SystemClock.elapsedRealtime());
            event.mPackage = packageName;
            event.mAction = action;
            event.mContentType = contentType;
            event.mContentAnnotations = annotations;
            reportEventOrAddToQueue(userId, event);
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
            if (!hasPermissions(callingPackage,
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
            if (!hasPermissions(callingPackage,
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
        public byte[] getBackupPayload(int user, String key) {
            synchronized (mLock) {
                if (!mUserUnlockedStates.get(user)) {
                    Slog.w(TAG, "Failed to get backup payload for locked user " + user);
                    return null;
                }

                // Check to ensure that only user 0's data is b/r for now
                // Note: if backup and restore is enabled for users other than the system user, the
                // #onUserUnlocked logic, specifically when the update mappings job is scheduled via
                // UsageStatsIdleService.scheduleUpdateMappingsJob, will have to be updated.
                if (user == UserHandle.USER_SYSTEM) {
                    final UserUsageStatsService userStats = getUserUsageStatsServiceLocked(user);
                    if (userStats == null) {
                        return null; // user was stopped or removed
                    }
                    return userStats.getBackupPayload(key);
                } else {
                    return null;
                }
            }
        }

        @Override
        public void applyRestoredPayload(int user, String key, byte[] payload) {
            synchronized (mLock) {
                if (!mUserUnlockedStates.get(user)) {
                    Slog.w(TAG, "Failed to apply restored payload for locked user " + user);
                    return;
                }

                if (user == UserHandle.USER_SYSTEM) {
                    final UserUsageStatsService userStats = getUserUsageStatsServiceLocked(user);
                    if (userStats == null) {
                        return; // user was stopped or removed
                    }
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
        public UsageEvents queryEventsForUser(int userId, long beginTime, long endTime, int flags) {
            return UsageStatsService.this.queryEvents(userId, beginTime, endTime, flags);
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
        public boolean updatePackageMappingsData() {
            return UsageStatsService.this.updatePackageMappingsData();
        }
    }

    private class MyPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageRemoved(String packageName, int uid) {
            mHandler.obtainMessage(MSG_PACKAGE_REMOVED, getChangingUserId(), 0, packageName)
                    .sendToTarget();
            super.onPackageRemoved(packageName, uid);
        }
    }
}
