/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.content;

import static android.os.PowerWhitelistManager.REASON_SYNC_MANAGER;
import static android.os.PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED;

import static com.android.server.content.SyncLogger.logSafe;

import android.accounts.Account;
import android.accounts.AccountAndUser;
import android.accounts.AccountManager;
import android.accounts.AccountManagerInternal;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentResolver.SyncExemption;
import android.content.Context;
import android.content.ISyncAdapter;
import android.content.ISyncAdapterUnsyncableAccountCallback;
import android.content.ISyncContext;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PeriodicSync;
import android.content.ServiceConnection;
import android.content.SyncActivityTooManyDeletes;
import android.content.SyncAdapterType;
import android.content.SyncAdaptersCache;
import android.content.SyncInfo;
import android.content.SyncResult;
import android.content.SyncStatusInfo;
import android.content.SyncStatusInfo.Stats;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ProviderInfo;
import android.content.pm.RegisteredServicesCache;
import android.content.pm.RegisteredServicesCacheListener;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.TimeMigrationUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.config.appcloning.AppCloningDeviceConfigHelper;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.function.QuadConsumer;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.accounts.AccountManagerService;
import com.android.server.backup.AccountSyncSettingsBackupHelper;
import com.android.server.content.SyncStorageEngine.AuthorityInfo;
import com.android.server.content.SyncStorageEngine.EndPoint;
import com.android.server.content.SyncStorageEngine.OnSyncRequestListener;
import com.android.server.job.JobSchedulerInternal;

import dalvik.annotation.optimization.NeverCompile;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Implementation details:
 * All scheduled syncs will be passed on to JobScheduler as jobs
 * (See {@link #scheduleSyncOperationH(SyncOperation, long)}. This function schedules a job
 * with JobScheduler with appropriate delay and constraints (according to backoffs and extras).
 * The scheduleSyncOperationH function also assigns a unique jobId to each
 * SyncOperation.
 *
 * Periodic Syncs:
 * Each periodic sync is scheduled as a periodic job. If a periodic sync fails, we create a new
 * one off SyncOperation and set its {@link SyncOperation#sourcePeriodicId} field to the jobId of the
 * periodic sync. We don't allow the periodic job to run while any job initiated by it is pending.
 *
 * Backoffs:
 * Each {@link EndPoint} has a backoff associated with it. When a SyncOperation fails, we increase
 * the backoff on the authority. Then we reschedule all syncs associated with that authority to
 * run at a later time. Similarly, when a sync succeeds, backoff is cleared and all associated syncs
 * are rescheduled. A rescheduled sync will get a new jobId.
 *
 * See also {@code SyncManager.md} in the same directory for how app-standby affects sync adapters.
 *
 * @hide
 */
public class SyncManager {
    static final String TAG = "SyncManager";

    private static final boolean DEBUG_ACCOUNT_ACCESS = false;

    // Only do the check on a debuggable build.
    private static final boolean ENABLE_SUSPICIOUS_CHECK = Build.IS_DEBUGGABLE;

    /** Delay a sync due to local changes this long. In milliseconds */
    private static final long LOCAL_SYNC_DELAY;

    static {
        LOCAL_SYNC_DELAY =
                SystemProperties.getLong("sync.local_sync_delay", 30 * 1000 /* 30 seconds */);
    }

    /**
     * How long to wait before retrying a sync that failed due to one already being in progress.
     */
    private static final int DELAY_RETRY_SYNC_IN_PROGRESS_IN_SECONDS = 10;

    /**
     * How often to periodically poll network traffic for an adapter performing a sync to determine
     * whether progress is being made.
     */
    private static final long SYNC_MONITOR_WINDOW_LENGTH_MILLIS = 60 * 1000; // 60 seconds

    /**
     * How many bytes must be transferred (Tx + Rx) over the period of time defined by
     * {@link #SYNC_MONITOR_WINDOW_LENGTH_MILLIS} for the sync to be considered to be making
     * progress.
     */
    private static final int SYNC_MONITOR_PROGRESS_THRESHOLD_BYTES = 10; // 10 bytes

    /**
     * If a sync becomes ready and it conflicts with an already running sync, it gets
     * pushed back for this amount of time.
     */
    private static final long SYNC_DELAY_ON_CONFLICT = 10*1000; // 10 seconds

    private static final String SYNC_WAKE_LOCK_PREFIX = "*sync*/";
    private static final String HANDLE_SYNC_ALARM_WAKE_LOCK = "SyncManagerHandleSyncAlarm";
    private static final String SYNC_LOOP_WAKE_LOCK = "SyncLoopWakeLock";

    private static final boolean USE_WTF_FOR_ACCOUNT_ERROR = true;

    private static final int SYNC_OP_STATE_VALID = 0;
    // "1" used to include errors 3, 4 and 5 but now it's split up.
    private static final int SYNC_OP_STATE_INVALID_NO_ACCOUNT_ACCESS = 2;
    private static final int SYNC_OP_STATE_INVALID_NO_ACCOUNT = 3;
    private static final int SYNC_OP_STATE_INVALID_NOT_SYNCABLE = 4;
    private static final int SYNC_OP_STATE_INVALID_SYNC_DISABLED = 5;

    /** Flags used when connecting to a sync adapter service */
    private static final Context.BindServiceFlags SYNC_ADAPTER_CONNECTION_FLAGS =
            Context.BindServiceFlags.of(Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND
                    | Context.BIND_ALLOW_OOM_MANAGEMENT);

    /** Singleton instance. */
    @GuardedBy("SyncManager.class")
    private static SyncManager sInstance;

    private Context mContext;

    private static final AccountAndUser[] INITIAL_ACCOUNTS_ARRAY = new AccountAndUser[0];

    private final Object mAccountsLock = new Object();
    private volatile AccountAndUser[] mRunningAccounts = INITIAL_ACCOUNTS_ARRAY;

    volatile private PowerManager.WakeLock mSyncManagerWakeLock;
    volatile private boolean mDataConnectionIsConnected = false;
    private volatile int mNextJobId = 0;

    private final NotificationManager mNotificationMgr;
    private final IBatteryStats mBatteryStats;
    private JobScheduler mJobScheduler;

    private SyncStorageEngine mSyncStorageEngine;

    protected final ArrayList<ActiveSyncContext> mActiveSyncContexts = Lists.newArrayList();

    // Synchronized on "this". Instead of using this directly one should instead call
    // its accessor, getConnManager().
    private ConnectivityManager mConnManagerDoNotUseDirectly;

    /** Track whether the device has already been provisioned. */
    private volatile boolean mProvisioned;

    protected final SyncAdaptersCache mSyncAdapters;

    private final SyncLogger mLogger;

    private final AppCloningDeviceConfigHelper mAppCloningDeviceConfigHelper;

    private boolean isJobIdInUseLockedH(int jobId, List<JobInfo> pendingJobs) {
        for (int i = 0, size = pendingJobs.size(); i < size; i++) {
            JobInfo job = pendingJobs.get(i);
            if (job.getId() == jobId) {
                return true;
            }
        }
        for (int i = 0, size = mActiveSyncContexts.size(); i < size; i++) {
            ActiveSyncContext asc = mActiveSyncContexts.get(i);
            if (asc.mSyncOperation.jobId == jobId) {
                return true;
            }
        }
        return false;
    }

    private int getUnusedJobIdH() {
        final List<JobInfo> pendingJobs = mJobScheduler.getAllPendingJobs();
        while (isJobIdInUseLockedH(mNextJobId, pendingJobs)) {
            // SyncManager jobs are placed in their own namespace. Since there's no chance of
            // conflicting with other parts of the system, we can just keep incrementing until
            // we find an unused ID.
            mNextJobId++;
        }
        return mNextJobId;
    }

    private List<SyncOperation> getAllPendingSyncs() {
        verifyJobScheduler();
        List<JobInfo> pendingJobs = mJobScheduler.getAllPendingJobs();
        final int numJobs = pendingJobs.size();
        final List<SyncOperation> pendingSyncs = new ArrayList<>(numJobs);
        for (int i = 0; i < numJobs; ++i) {
            final JobInfo job = pendingJobs.get(i);
            SyncOperation op = SyncOperation.maybeCreateFromJobExtras(job.getExtras());
            if (op != null) {
                pendingSyncs.add(op);
            } else {
                Slog.wtf(TAG, "Non-sync job inside of SyncManager's namespace");
            }
        }
        return pendingSyncs;
    }

    private final BroadcastReceiver mAccountsUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            EndPoint target = new EndPoint(null, null, getSendingUserId());
            updateRunningAccounts(target /* sync targets for user */);
        }
    };

    private final PowerManager mPowerManager;

    private final UserManager mUserManager;

    private final AccountManager mAccountManager;

    private final AccountManagerInternal mAccountManagerInternal;

    private final PackageManagerInternal mPackageManagerInternal;

    private final ActivityManagerInternal mAmi;

    private List<UserInfo> getAllUsers() {
        return mUserManager.getUsers();
    }

    private boolean containsAccountAndUser(AccountAndUser[] accounts, Account account, int userId) {
        boolean found = false;
        for (int i = 0; i < accounts.length; i++) {
            if (accounts[i].userId == userId
                    && accounts[i].account.equals(account)) {
                found = true;
                break;
            }
        }
        return found;
    }

    /** target indicates endpoints that should be synced after account info is updated. */
    private void updateRunningAccounts(EndPoint target) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Slog.v(TAG, "sending MESSAGE_ACCOUNTS_UPDATED");
        // Update accounts in handler thread.
        Message m = mSyncHandler.obtainMessage(SyncHandler.MESSAGE_ACCOUNTS_UPDATED);
        m.obj = target;
        m.sendToTarget();
    }

    private void removeStaleAccounts() {
        for (UserInfo user : mUserManager.getAliveUsers()) {
            // Skip any partially created/removed users
            if (user.partial) continue;
            Account[] accountsForUser = AccountManagerService.getSingleton().getAccounts(
                    user.id, mContext.getOpPackageName());

            mSyncStorageEngine.removeStaleAccounts(accountsForUser, user.id);
        }
    }

    private BroadcastReceiver mConnectivityIntentReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final boolean wasConnected = mDataConnectionIsConnected;

                    // Don't use the intent to figure out if network is connected, just check
                    // ConnectivityManager directly.
                    mDataConnectionIsConnected = readDataConnectionState();
                    if (mDataConnectionIsConnected) {
                        if (!wasConnected) {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Slog.v(TAG, "Reconnection detected: clearing all backoffs");
                            }
                            // Note the location of this code was wrong from nyc to oc; fixed in DR.
                            clearAllBackoffs("network reconnect");
                        }
                    }
                }
            };

    private void clearAllBackoffs(String why) {
        mSyncStorageEngine.clearAllBackoffsLocked();
        rescheduleSyncs(EndPoint.USER_ALL_PROVIDER_ALL_ACCOUNTS_ALL, why);
    }

    private boolean readDataConnectionState() {
        NetworkInfo networkInfo = getConnectivityManager().getActiveNetworkInfo();
        return (networkInfo != null) && networkInfo.isConnected();
    }

    private String getJobStats() {
        JobSchedulerInternal js = LocalServices.getService(JobSchedulerInternal.class);
        return "JobStats: "
                + ((js == null) ? "(JobSchedulerInternal==null)"
                : js.getPersistStats().toString());
    }

    private BroadcastReceiver mShutdownIntentReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.w(TAG, "Writing sync state before shutdown...");
                    getSyncStorageEngine().writeAllState();

                    mLogger.log(getJobStats());
                    mLogger.log("Shutting down.");
                }
            };

    private final BroadcastReceiver mOtherIntentsReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_TIME_CHANGED.equals(intent.getAction())) {
                        mSyncStorageEngine.setClockValid();
                        return;
                    }
                }
            };

    private BroadcastReceiver mUserIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) return;

            if (Intent.ACTION_USER_REMOVED.equals(action)) {
                onUserRemoved(userId);
            } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                onUserUnlocked(userId);
            } else if (Intent.ACTION_USER_STOPPED.equals(action)) {
                onUserStopped(userId);
            }
        }
    };

    private final BroadcastReceiver mForceStoppedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final boolean isLoggable = Log.isLoggable(TAG, Log.DEBUG);
            // For now, just log when packages were force-stopped and unstopped for debugging.
            if (isLoggable) {
                if (Intent.ACTION_PACKAGE_RESTARTED.equals(intent.getAction())) {
                    Log.d(TAG, "Package force-stopped: "
                            + intent.getData().getSchemeSpecificPart());
                } else if (Intent.ACTION_PACKAGE_UNSTOPPED.equals(intent.getAction())) {
                    Log.d(TAG, "Package unstopped: "
                            + intent.getData().getSchemeSpecificPart());
                }
            }
        }
    };

    private final HandlerThread mThread;
    private final SyncHandler mSyncHandler;
    private final SyncManagerConstants mConstants;

    @GuardedBy("mUnlockedUsers")
    private final SparseBooleanArray mUnlockedUsers = new SparseBooleanArray();

    private ConnectivityManager getConnectivityManager() {
        synchronized (this) {
            if (mConnManagerDoNotUseDirectly == null) {
                mConnManagerDoNotUseDirectly = (ConnectivityManager)mContext.getSystemService(
                        Context.CONNECTIVITY_SERVICE);
            }
            return mConnManagerDoNotUseDirectly;
        }
    }

    /**
     * Cancel all unnecessary jobs. This function will be run once after every boot.
     */
    private void cleanupJobs() {
        // O(n^2) in number of jobs, so we run this on the background thread.
        mSyncHandler.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                List<SyncOperation> ops = getAllPendingSyncs();
                Set<String> cleanedKeys = new HashSet<String>();
                for (SyncOperation opx: ops) {
                    if (cleanedKeys.contains(opx.key)) {
                        continue;
                    }
                    cleanedKeys.add(opx.key);
                    for (SyncOperation opy: ops) {
                        if (opx == opy) {
                            continue;
                        }
                        if (opx.key.equals(opy.key)) {
                            mLogger.log("Removing duplicate sync: ", opy);
                            cancelJob(opy, "cleanupJobs() x=" + opx + " y=" + opy);
                        }
                    }
                }
            }
        });
    }

    /**
     * Migrate syncs from the default job namespace to SyncManager's namespace if they haven't been
     * migrated already.
     */
    private void migrateSyncJobNamespaceIfNeeded() {
        final boolean namespaceMigrated = mSyncStorageEngine.isJobNamespaceMigrated();
        final boolean attributionFixed = mSyncStorageEngine.isJobAttributionFixed();
        if (namespaceMigrated && attributionFixed) {
            return;
        }
        final JobScheduler jobSchedulerDefaultNamespace =
                mContext.getSystemService(JobScheduler.class);
        if (!namespaceMigrated) {
            final List<JobInfo> pendingJobs = jobSchedulerDefaultNamespace.getAllPendingJobs();
            // Wait until we've confirmed that all syncs have been migrated to the new namespace
            // before we persist successful migration to our status file. This is done to avoid
            // internal consistency issues if the devices reboots right after SyncManager has
            // done the migration on its side but before JobScheduler has finished persisting
            // the updated jobs to disk. If JobScheduler hasn't persisted the update to disk,
            // then nothing that happened afterwards should have been persisted either, so there's
            // no concern over activity happening after the migration causing issues.
            boolean allSyncsMigrated = true;
            for (int i = pendingJobs.size() - 1; i >= 0; --i) {
                final JobInfo job = pendingJobs.get(i);
                final SyncOperation op = SyncOperation.maybeCreateFromJobExtras(job.getExtras());
                if (op != null) {
                    // This is a sync. Move it over to SyncManager's namespace.
                    mJobScheduler.scheduleAsPackage(job,
                            op.owningPackage, op.target.userId, op.wakeLockName());
                    jobSchedulerDefaultNamespace.cancel(job.getId());
                    allSyncsMigrated = false;
                }
            }
            mSyncStorageEngine.setJobNamespaceMigrated(allSyncsMigrated);
        }

        // Fix attribution for any syncs that were previously scheduled using
        // JobScheduler.schedule() instead of JobScheduler.scheduleAsPackage().
        final List<JobInfo> namespacedJobs = LocalServices.getService(JobSchedulerInternal.class)
                .getSystemScheduledOwnJobs(mJobScheduler.getNamespace());
        // Wait until we've confirmed that all syncs have been proper attribution
        // before we persist attribution state to our status file. This is done to avoid
        // internal consistency issues if the devices reboots right after SyncManager has
        // rescheduled the job on its side but before JobScheduler has finished persisting
        // the updated jobs to disk. If JobScheduler hasn't persisted the update to disk,
        // then nothing that happened afterwards should have been persisted either, so there's
        // no concern over activity happening after the migration causing issues.
        // This case is done to fix issues for a subset of test devices.
        // TODO: remove this attribution check/fix code
        boolean allSyncsAttributed = true;
        for (int i = namespacedJobs.size() - 1; i >= 0; --i) {
            final JobInfo job = namespacedJobs.get(i);
            final SyncOperation op = SyncOperation.maybeCreateFromJobExtras(job.getExtras());
            if (op != null) {
                // This is a sync. Make sure it's properly attributed to the app
                // instead of the system.
                // Since the job ID stays the same, scheduleAsPackage will replace the scheduled
                // job, so we don't need to call cancel as well.
                mJobScheduler.scheduleAsPackage(job,
                        op.owningPackage, op.target.userId, op.wakeLockName());
                allSyncsAttributed = false;
            }
        }
        mSyncStorageEngine.setJobAttributionFixed(allSyncsAttributed);
    }

    private synchronized void verifyJobScheduler() {
        if (mJobScheduler != null) {
            return;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "initializing JobScheduler object.");
            }
            // Use a dedicated namespace to avoid conflicts with other jobs
            // scheduled by the system process.
            mJobScheduler = mContext.getSystemService(JobScheduler.class)
                    .forNamespace("SyncManager");
            migrateSyncJobNamespaceIfNeeded();
            // Get all persisted syncs from JobScheduler in the SyncManager namespace.
            List<JobInfo> pendingJobs = mJobScheduler.getAllPendingJobs();

            int numPersistedPeriodicSyncs = 0;
            int numPersistedOneshotSyncs = 0;
            for (JobInfo job : pendingJobs) {
                SyncOperation op = SyncOperation.maybeCreateFromJobExtras(job.getExtras());
                if (op != null) {
                    if (op.isPeriodic) {
                        numPersistedPeriodicSyncs++;
                    } else {
                        numPersistedOneshotSyncs++;
                        // Set the pending status of this EndPoint to true. Pending icon is
                        // shown on the settings activity.
                        mSyncStorageEngine.markPending(op.target, true);
                    }
                } else {
                    Slog.wtf(TAG, "Non-sync job inside of SyncManager namespace");
                }
            }
            final String summary = "Loaded persisted syncs: "
                    + numPersistedPeriodicSyncs + " periodic syncs, "
                    + numPersistedOneshotSyncs + " oneshot syncs, "
                    + (pendingJobs.size()) + " total system server jobs, "
                    + getJobStats();
            Slog.i(TAG, summary);
            mLogger.log(summary);

            cleanupJobs();

            if (ENABLE_SUSPICIOUS_CHECK &&
                    (numPersistedPeriodicSyncs == 0) && likelyHasPeriodicSyncs()) {
                Slog.wtf(TAG, "Device booted with no persisted periodic syncs: " + summary);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * @return whether the device most likely has some periodic syncs.
     */
    private boolean likelyHasPeriodicSyncs() {
        try {
            // Each sync adapter has a daily periodic sync by default, but sync adapters can remove
            // them by themselves. So here, we use an arbitrary threshold. If there are more than
            // this many sync endpoints, surely one of them should have a periodic sync...
            return mSyncStorageEngine.getAuthorityCount() >= 6;
        } catch (Throwable th) {
            // Just in case.
        }
        return false;
    }

    private JobScheduler getJobScheduler() {
        verifyJobScheduler();
        return mJobScheduler;
    }

    public SyncManager(Context context, boolean factoryTest) {
        synchronized (SyncManager.class) {
            if (sInstance == null) {
                sInstance = this;
            } else {
                Slog.wtf(TAG, "SyncManager instantiated multiple times");
            }
        }

        // Initialize the SyncStorageEngine first, before registering observers
        // and creating threads and so on; it may fail if the disk is full.
        mContext = context;

        mLogger = SyncLogger.getInstance();

        SyncStorageEngine.init(context, BackgroundThread.get().getLooper());
        mSyncStorageEngine = SyncStorageEngine.getSingleton();
        mSyncStorageEngine.setOnSyncRequestListener(new OnSyncRequestListener() {
            @Override
            public void onSyncRequest(SyncStorageEngine.EndPoint info, int reason, Bundle extras,
                    @SyncExemption int syncExemptionFlag, int callingUid, int callingPid) {
                scheduleSync(info.account, info.userId, reason, info.provider, extras,
                        AuthorityInfo.UNDEFINED, syncExemptionFlag, callingUid, callingPid, null);
            }
        });

        mSyncStorageEngine.setPeriodicSyncAddedListener(
                new SyncStorageEngine.PeriodicSyncAddedListener() {
                    @Override
                    public void onPeriodicSyncAdded(EndPoint target, Bundle extras, long pollFrequency,
                            long flex) {
                        updateOrAddPeriodicSync(target, pollFrequency, flex, extras);
                    }
                });

        mSyncStorageEngine.setOnAuthorityRemovedListener(new SyncStorageEngine.OnAuthorityRemovedListener() {
            @Override
            public void onAuthorityRemoved(EndPoint removedAuthority) {
                removeSyncsForAuthority(removedAuthority, "onAuthorityRemoved");
            }
        });

        mSyncAdapters = new SyncAdaptersCache(mContext);

        mThread = new HandlerThread("SyncManager", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mThread.start();
        mSyncHandler = new SyncHandler(mThread.getLooper());

        mSyncAdapters.setListener(new RegisteredServicesCacheListener<SyncAdapterType>() {
            @Override
            public void onServiceChanged(SyncAdapterType type, int userId, boolean removed) {
                if (!removed) {
                    scheduleSync(null, UserHandle.USER_ALL,
                            SyncOperation.REASON_SERVICE_CHANGED,
                            type.authority, null, AuthorityInfo.UNDEFINED,
                            ContentResolver.SYNC_EXEMPTION_NONE,
                            Process.myUid(), -1, null);
                }
            }
        }, mSyncHandler);

        mConstants = new SyncManagerConstants(context);
        mAppCloningDeviceConfigHelper = AppCloningDeviceConfigHelper.getInstance(context);

        IntentFilter intentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(mConnectivityIntentReceiver, intentFilter);

        intentFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(mShutdownIntentReceiver, intentFilter);

        intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        intentFilter.addAction(Intent.ACTION_USER_STOPPED);
        mContext.registerReceiverAsUser(
                mUserIntentReceiver, UserHandle.ALL, intentFilter, null, null);

        intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_UNSTOPPED);
        intentFilter.addDataScheme("package");
        context.registerReceiver(mForceStoppedReceiver, intentFilter);

        intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        context.registerReceiver(mOtherIntentsReceiver, intentFilter);

        if (!factoryTest) {
            mNotificationMgr = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
        } else {
            mNotificationMgr = null;
        }
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mAccountManager = (AccountManager) mContext.getSystemService(Context.ACCOUNT_SERVICE);
        mAccountManagerInternal = getAccountManagerInternal();
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mAmi = LocalServices.getService(ActivityManagerInternal.class);

        mAccountManagerInternal.addOnAppPermissionChangeListener((Account account, int uid) -> {
            // If the UID gained access to the account kick-off syncs lacking account access
            if (mAccountManagerInternal.hasAccountAccess(account, uid)) {
                scheduleSync(account, UserHandle.getUserId(uid),
                        SyncOperation.REASON_ACCOUNTS_UPDATED,
                        null, null, AuthorityInfo.SYNCABLE_NO_ACCOUNT_ACCESS,
                        ContentResolver.SYNC_EXEMPTION_NONE,
                        Process.myUid(), -2, null);
            }
        });

        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                BatteryStats.SERVICE_NAME));

        // This WakeLock is used to ensure that we stay awake while running the sync loop
        // message handler. Normally we will hold a sync adapter wake lock while it is being
        // synced but during the execution of the sync loop it might finish a sync for
        // one sync adapter before starting the sync for the other sync adapter and we
        // don't want the device to go to sleep during that window.
        mSyncManagerWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                SYNC_LOOP_WAKE_LOCK);
        mSyncManagerWakeLock.setReferenceCounted(false);

        mProvisioned = isDeviceProvisioned();
        if (!mProvisioned) {
            final ContentResolver resolver = context.getContentResolver();
            ContentObserver provisionedObserver =
                    new ContentObserver(null /* current thread */) {
                        public void onChange(boolean selfChange) {
                            mProvisioned |= isDeviceProvisioned();
                            if (mProvisioned) {
                                resolver.unregisterContentObserver(this);
                            }
                        }
                    };

            synchronized (mSyncHandler) {
                resolver.registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                        false /* notifyForDescendents */,
                        provisionedObserver);

                // The device *may* have been provisioned while we were registering above observer.
                // Check again to make sure.
                mProvisioned |= isDeviceProvisioned();
                if (mProvisioned) {
                    resolver.unregisterContentObserver(provisionedObserver);
                }
            }
        }

        if (!factoryTest) {
            // Register for account list updates for all users
            mContext.registerReceiverAsUser(mAccountsUpdatedReceiver,
                    UserHandle.ALL,
                    new IntentFilter(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION),
                    null, null);
        }

        // Sync adapters were able to access the synced account without the accounts
        // permission which circumvents our permission model. Therefore, we require
        // sync adapters that don't have access to the account to get user consent.
        // This can be noisy, therefore we will allowlist sync adapters installed
        // before we started checking for account access because they already know
        // the account (they run before) which is the genie is out of the bottle.
        allowListExistingSyncAdaptersIfNeeded();

        mLogger.log("Sync manager initialized: " + Build.FINGERPRINT);
    }

    @VisibleForTesting
    protected AccountManagerInternal getAccountManagerInternal() {
        return LocalServices.getService(AccountManagerInternal.class);
    }

    public void onStartUser(int userId) {
        // Log on the handler to avoid slowing down device boot.
        mSyncHandler.post(() -> mLogger.log("onStartUser: user=", userId));
    }

    public void onUnlockUser(int userId) {
        synchronized (mUnlockedUsers) {
            mUnlockedUsers.put(userId, true);
        }
        // Log on the handler to avoid slowing down device boot.
        mSyncHandler.post(() -> mLogger.log("onUnlockUser: user=", userId));
    }

    public void onStopUser(int userId) {
        synchronized (mUnlockedUsers) {
            mUnlockedUsers.put(userId, false);
        }
        // Log on the handler to avoid slowing down user switch.
        mSyncHandler.post(() -> mLogger.log("onStopUser: user=", userId));
    }

    private boolean isUserUnlocked(int userId) {
        synchronized (mUnlockedUsers) {
            return mUnlockedUsers.get(userId);
        }
    }

    public void onBootPhase(int phase) {
        // Note SyncManager only receives PHASE_ACTIVITY_MANAGER_READY and after.
        switch (phase) {
            case SystemService.PHASE_ACTIVITY_MANAGER_READY:
                mConstants.start();
                break;
        }
    }

    private void allowListExistingSyncAdaptersIfNeeded() {
        if (!mSyncStorageEngine.shouldGrantSyncAdaptersAccountAccess()) {
            return;
        }
        List<UserInfo> users = mUserManager.getAliveUsers();
        final int userCount = users.size();
        for (int i = 0; i < userCount; i++) {
            UserHandle userHandle = users.get(i).getUserHandle();
            final int userId = userHandle.getIdentifier();
            for (RegisteredServicesCache.ServiceInfo<SyncAdapterType> service
                    : mSyncAdapters.getAllServices(userId)) {
                String packageName = service.componentName.getPackageName();
                for (Account account : mAccountManager.getAccountsByTypeAsUser(
                        service.type.accountType, userHandle)) {
                    if (!canAccessAccount(account, packageName, userId)) {
                        mAccountManager.updateAppPermission(account,
                                AccountManager.ACCOUNT_ACCESS_TOKEN_TYPE, service.uid, true);
                    }
                }
            }
        }
    }

    private boolean isDeviceProvisioned() {
        final ContentResolver resolver = mContext.getContentResolver();
        return (Settings.Global.getInt(resolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0);
    }
    /**
     * Return a random value v that satisfies minValue <= v < maxValue. The difference between
     * maxValue and minValue must be less than Integer.MAX_VALUE.
     */
    private long jitterize(long minValue, long maxValue) {
        Random random = new Random(SystemClock.elapsedRealtime());
        long spread = maxValue - minValue;
        if (spread > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("the difference between the maxValue and the "
                    + "minValue must be less than " + Integer.MAX_VALUE);
        }
        return minValue + random.nextInt((int)spread);
    }

    public SyncStorageEngine getSyncStorageEngine() {
        return mSyncStorageEngine;
    }

    @SuppressLint("AndroidFrameworkRequiresPermission")
    private boolean areContactWritesEnabledForUser(UserInfo userInfo) {
        final UserManager um = UserManager.get(mContext);
        try {
            final UserProperties userProperties = um.getUserProperties(userInfo.getUserHandle());
            return !userProperties.getUseParentsContacts();
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Trying to fetch user properties for non-existing/partial user "
                    + userInfo.getUserHandle());
            return false;
        }
    }

    /**
     * Check whether the feature flag controlling contacts sharing for clone profile is set. If
     * true, the contact syncs for clone profile should be disabled.
     *
     * @return true/false if contact sharing is enabled/disabled
     */
    protected boolean isContactSharingAllowedForCloneProfile() {
        return mContext.getResources().getBoolean(R.bool.config_enableAppCloningBuildingBlocks)
                && mAppCloningDeviceConfigHelper.getEnableAppCloningBuildingBlocks();
    }

    /**
     * Check if account sync should be disabled for the given user and provider.
     * @param userInfo
     * @param providerName
     * @return true if sync for the account corresponding to the given user and provider should be
     * disabled, false otherwise. Also returns false if either of the inputs are null.
     */
    @VisibleForTesting
    protected boolean shouldDisableSyncForUser(UserInfo userInfo, String providerName) {
        if (userInfo == null || providerName == null || !isContactSharingAllowedForCloneProfile()) {
            return false;
        }
        return providerName.equals(ContactsContract.AUTHORITY)
                && !areContactWritesEnabledForUser(userInfo);
    }

    private int getIsSyncable(Account account, int userId, String providerName) {
        int isSyncable = mSyncStorageEngine.getIsSyncable(account, userId, providerName);
        final UserManager um = UserManager.get(mContext);
        UserInfo userInfo = um.getUserInfo(userId);

        // Check if the provider is allowed to sync data from linked accounts for the user
        if (shouldDisableSyncForUser(userInfo, providerName)) {
            Log.w(TAG, "Account sync is disabled for account: " + account
                    + " userId: " + userId + " provider: " + providerName);
            return AuthorityInfo.NOT_SYNCABLE;
        }

        // If it's not a restricted user, return isSyncable.
        if (userInfo == null || !userInfo.isRestricted()) return isSyncable;

        // Else check if the sync adapter has opted-in or not.
        RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo =
                mSyncAdapters.getServiceInfo(
                        SyncAdapterType.newKey(providerName, account.type), userId);
        if (syncAdapterInfo == null) return AuthorityInfo.NOT_SYNCABLE;

        PackageInfo pInfo = null;
        try {
            pInfo = AppGlobals.getPackageManager().getPackageInfo(
                    syncAdapterInfo.componentName.getPackageName(), 0, userId);
            if (pInfo == null) return AuthorityInfo.NOT_SYNCABLE;
        } catch (RemoteException re) {
            // Shouldn't happen.
            return AuthorityInfo.NOT_SYNCABLE;
        }
        if (pInfo.restrictedAccountType != null
                && pInfo.restrictedAccountType.equals(account.type)) {
            return isSyncable;
        } else {
            return AuthorityInfo.NOT_SYNCABLE;
        }
    }

    private void setAuthorityPendingState(EndPoint info) {
        List<SyncOperation> ops = getAllPendingSyncs();
        for (SyncOperation op: ops) {
            if (!op.isPeriodic && op.target.matchesSpec(info)) {
                getSyncStorageEngine().markPending(info, true);
                return;
            }
        }
        getSyncStorageEngine().markPending(info, false);
    }

    /**
     * Initiate a sync. This can start a sync for all providers
     * (pass null to url, set onlyTicklable to false), only those
     * providers that are marked as ticklable (pass null to url,
     * set onlyTicklable to true), or a specific provider (set url
     * to the content url of the provider).
     *
     * <p>If the ContentResolver.SYNC_EXTRAS_UPLOAD boolean in extras is
     * true then initiate a sync that just checks for local changes to send
     * to the server, otherwise initiate a sync that first gets any
     * changes from the server before sending local changes back to
     * the server.
     *
     * <p>If a specific provider is being synced (the url is non-null)
     * then the extras can contain SyncAdapter-specific information
     * to control what gets synced (e.g. which specific feed to sync).
     *
     * <p>You'll start getting callbacks after this.
     *
     * @param requestedAccount the account to sync, may be null to signify all accounts
     * @param userId the id of the user whose accounts are to be synced. If userId is USER_ALL,
     *          then all users' accounts are considered.
     * @param reason for sync request. If this is a positive integer, it is the Linux uid
     * assigned to the process that requested the sync. If it's negative, the sync was requested by
     * the SyncManager itself and could be one of the following:
     *      {@link SyncOperation#REASON_BACKGROUND_DATA_SETTINGS_CHANGED}
     *      {@link SyncOperation#REASON_ACCOUNTS_UPDATED}
     *      {@link SyncOperation#REASON_SERVICE_CHANGED}
     *      {@link SyncOperation#REASON_PERIODIC}
     *      {@link SyncOperation#REASON_IS_SYNCABLE}
     *      {@link SyncOperation#REASON_SYNC_AUTO}
     *      {@link SyncOperation#REASON_MASTER_SYNC_AUTO}
     *      {@link SyncOperation#REASON_USER_START}
     * @param requestedAuthority the authority to sync, may be null to indicate all authorities
     * @param extras a Map of SyncAdapter-specific information to control
     *          syncs of a specific provider. Can be null. Is ignored
     *          if the url is null.
     * @param targetSyncState Only sync authorities that have the specified sync state.
     *           Use {@link AuthorityInfo#UNDEFINED} to sync all authorities.
     */
    public void scheduleSync(Account requestedAccount, int userId, int reason,
            String requestedAuthority, Bundle extras, int targetSyncState,
            @SyncExemption int syncExemptionFlag, int callingUid, int callingPid,
            String callingPackage) {
        scheduleSync(requestedAccount, userId, reason, requestedAuthority, extras, targetSyncState,
                0 /* min delay */, true /* checkIfAccountReady */, syncExemptionFlag,
                callingUid, callingPid, callingPackage);
    }

    /**
     * @param minDelayMillis The sync can't land before this delay expires.
     */
    private void scheduleSync(Account requestedAccount, int userId, int reason,
            String requestedAuthority, Bundle extras, int targetSyncState,
            final long minDelayMillis, boolean checkIfAccountReady,
            @SyncExemption int syncExemptionFlag,
            int callingUid, int callingPid, String callingPackage) {
        if (extras == null) {
            extras = new Bundle();
        }
        extras.size(); // Force unpacel.
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            mLogger.log("scheduleSync: account=", requestedAccount,
                    " u", userId,
                    " authority=", requestedAuthority,
                    " reason=", reason,
                    " extras=", extras,
                    " cuid=", callingUid, " cpid=", callingPid, " cpkg=", callingPackage,
                    " mdm=", minDelayMillis,
                    " ciar=", checkIfAccountReady,
                    " sef=", syncExemptionFlag);
        }

        AccountAndUser[] accounts = null;
        synchronized (mAccountsLock) {
            if (requestedAccount != null) {
                if (userId != UserHandle.USER_ALL) {
                    accounts = new AccountAndUser[]{new AccountAndUser(requestedAccount, userId)};
                } else {
                    for (AccountAndUser runningAccount : mRunningAccounts) {
                        if (requestedAccount.equals(runningAccount.account)) {
                            accounts = ArrayUtils.appendElement(AccountAndUser.class,
                                    accounts, runningAccount);
                        }
                    }
                }
            } else {
                accounts = mRunningAccounts;
            }
        }

        if (ArrayUtils.isEmpty(accounts)) {
            return;
        }

        final boolean uploadOnly = extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false);
        final boolean manualSync = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
        if (manualSync) {
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true);
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true);
        }
        final boolean ignoreSettings =
                extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, false);

        int source;
        if (uploadOnly) {
            source = SyncStorageEngine.SOURCE_LOCAL;
        } else if (manualSync) {
            source = SyncStorageEngine.SOURCE_USER;
        } else if (requestedAuthority == null) {
            source = SyncStorageEngine.SOURCE_POLL;
        } else {
            if (extras.containsKey("feed")) {
                source = SyncStorageEngine.SOURCE_FEED;
            } else{
                // This isn't strictly server, since arbitrary callers can (and do) request
                // a non-forced two-way sync on a specific url.
                source = SyncStorageEngine.SOURCE_OTHER;
            }
        }

        for (AccountAndUser account : accounts) {
            // If userId is specified, do not sync accounts of other users
            if (userId >= UserHandle.USER_SYSTEM && account.userId >= UserHandle.USER_SYSTEM
                    && userId != account.userId) {
                continue;
            }
            // Compile a list of authorities that have sync adapters.
            // For each authority sync each account that matches a sync adapter.
            final HashSet<String> syncableAuthorities = new HashSet<String>();
            for (RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapter :
                    mSyncAdapters.getAllServices(account.userId)) {
                syncableAuthorities.add(syncAdapter.type.authority);
            }

            // If the url was specified then replace the list of authorities
            // with just this authority or clear it if this authority isn't
            // syncable.
            if (requestedAuthority != null) {
                final boolean hasSyncAdapter = syncableAuthorities.contains(requestedAuthority);
                syncableAuthorities.clear();
                if (hasSyncAdapter) syncableAuthorities.add(requestedAuthority);
            }

            for (String authority : syncableAuthorities) {
                int isSyncable = computeSyncable(account.account, account.userId, authority,
                        !checkIfAccountReady, /*checkStoppedState=*/ true);

                if (isSyncable == AuthorityInfo.NOT_SYNCABLE) {
                    continue;
                }

                final RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo =
                        mSyncAdapters.getServiceInfo(SyncAdapterType.newKey(authority,
                                account.account.type), account.userId);
                if (syncAdapterInfo == null) {
                    continue;
                }

                final int owningUid = syncAdapterInfo.uid;

                if (isSyncable == AuthorityInfo.SYNCABLE_NO_ACCOUNT_ACCESS) {
                    mLogger.log("scheduleSync: Not scheduling sync operation: "
                                + "isSyncable == SYNCABLE_NO_ACCOUNT_ACCESS");
                    Bundle finalExtras = new Bundle(extras);
                    String packageName = syncAdapterInfo.componentName.getPackageName();
                    // If the app did not run and has no account access, done
                    if (!wasPackageEverLaunched(packageName, userId)) {
                        continue;
                    }
                    mAccountManagerInternal.requestAccountAccess(account.account,
                            packageName, userId,
                            new RemoteCallback((Bundle result) -> {
                                if (result != null
                                        && result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT)) {
                                    scheduleSync(account.account, userId, reason, authority,
                                            finalExtras, targetSyncState, minDelayMillis,
                                            true /* checkIfAccountReady */,
                                            syncExemptionFlag, callingUid, callingPid,
                                            callingPackage);
                                }
                            }
                        ));
                    continue;
                }

                final boolean allowParallelSyncs = syncAdapterInfo.type.allowParallelSyncs();
                final boolean isAlwaysSyncable = syncAdapterInfo.type.isAlwaysSyncable();
                if (!checkIfAccountReady && isSyncable < 0 && isAlwaysSyncable) {
                    mSyncStorageEngine.setIsSyncable(
                            account.account, account.userId, authority, AuthorityInfo.SYNCABLE,
                            callingUid, callingPid);
                    isSyncable = AuthorityInfo.SYNCABLE;
                }

                if (targetSyncState != AuthorityInfo.UNDEFINED && targetSyncState != isSyncable) {
                    continue;
                }

                if (!syncAdapterInfo.type.supportsUploading() && uploadOnly) {
                    continue;
                }

                boolean syncAllowed =
                        (isSyncable < 0) // Always allow if the isSyncable state is unknown.
                                || ignoreSettings
                                || (mSyncStorageEngine.getMasterSyncAutomatically(account.userId)
                                && mSyncStorageEngine.getSyncAutomatically(account.account,
                                account.userId, authority));
                if (!syncAllowed) {
                    mLogger.log("scheduleSync: sync of ", account, " ", authority,
                            " is not allowed, dropping request");
                    continue;
                }
                SyncStorageEngine.EndPoint info =
                        new SyncStorageEngine.EndPoint(
                                account.account, authority, account.userId);
                long delayUntil =
                        mSyncStorageEngine.getDelayUntilTime(info);

                final String owningPackage = syncAdapterInfo.componentName.getPackageName();

                if (isSyncable == AuthorityInfo.NOT_INITIALIZED) {
                    if (checkIfAccountReady) {
                        Bundle finalExtras = new Bundle(extras);

                        sendOnUnsyncableAccount(mContext, syncAdapterInfo, account.userId,
                                () -> scheduleSync(account.account, account.userId, reason,
                                        authority, finalExtras, targetSyncState, minDelayMillis,
                                        false, syncExemptionFlag, callingUid, callingPid,
                                        callingPackage));
                    } else {
                        // Initialisation sync.
                        Bundle newExtras = new Bundle();
                        newExtras.putBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, true);

                        mLogger.log("scheduleSync: schedule initialisation sync ",
                                account, " ", authority);

                        postScheduleSyncMessage(
                                new SyncOperation(account.account, account.userId,
                                        owningUid, owningPackage, reason, source,
                                        authority, newExtras, allowParallelSyncs,
                                        syncExemptionFlag),
                                minDelayMillis
                        );
                    }
                } else if (targetSyncState == AuthorityInfo.UNDEFINED
                        || targetSyncState == isSyncable) {
                    mLogger.log("scheduleSync: scheduling sync ",
                            account, " ", authority);
                    postScheduleSyncMessage(
                            new SyncOperation(account.account, account.userId,
                                    owningUid, owningPackage, reason, source,
                                    authority, extras, allowParallelSyncs, syncExemptionFlag),
                            minDelayMillis
                    );
                } else {
                    mLogger.log("scheduleSync: not handling ",
                            account, " ", authority);
                }
            }
        }
    }

    public int computeSyncable(Account account, int userId, String authority,
            boolean checkAccountAccess, boolean checkStoppedState) {
        final int status = getIsSyncable(account, userId, authority);
        if (status == AuthorityInfo.NOT_SYNCABLE) {
            return AuthorityInfo.NOT_SYNCABLE;
        }
        final SyncAdapterType type = SyncAdapterType.newKey(authority, account.type);
        final RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo =
                mSyncAdapters.getServiceInfo(type, userId);
        if (syncAdapterInfo == null) {
            return AuthorityInfo.NOT_SYNCABLE;
        }
        final int owningUid = syncAdapterInfo.uid;
        final String owningPackage = syncAdapterInfo.componentName.getPackageName();
        if (checkStoppedState && isPackageStopped(owningPackage, userId)) {
            return AuthorityInfo.NOT_SYNCABLE;
        }
        if (mAmi.isAppStartModeDisabled(owningUid, owningPackage)) {
            Slog.w(TAG, "Not scheduling job " + syncAdapterInfo.uid + ":"
                    + syncAdapterInfo.componentName
                    + " -- package not allowed to start");
            return AuthorityInfo.NOT_SYNCABLE;
        }
        if (checkAccountAccess && !canAccessAccount(account, owningPackage, owningUid)) {
            Log.w(TAG, "Access to " + logSafe(account) + " denied for package "
                    + owningPackage + " in UID " + syncAdapterInfo.uid);
            return AuthorityInfo.SYNCABLE_NO_ACCOUNT_ACCESS;
        }

        return status;
    }

    /**
     * Returns whether the package is in a stopped state or not.
     * Always returns {@code false} if the {@code android.content.pm.stay_stopped} flag is not set.
     */
    private boolean isPackageStopped(String packageName, int userId) {
        if (android.content.pm.Flags.stayStopped()) {
            return mPackageManagerInternal.isPackageStopped(packageName, userId);
        }
        return false;
    }

    private boolean canAccessAccount(Account account, String packageName, int uid) {
        if (mAccountManager.hasAccountAccess(account, packageName,
                UserHandle.getUserHandleForUid(uid))) {
            return true;
        }
        // We relax the account access rule to also include the system apps as
        // they are trusted and we want to minimize the cases where the user
        // involvement is required to grant access to the synced account.
        try {
            mContext.getPackageManager().getApplicationInfoAsUser(packageName,
                    PackageManager.MATCH_SYSTEM_ONLY, UserHandle.getUserId(uid));
            return true;
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    private void removeSyncsForAuthority(EndPoint info, String why) {
        mLogger.log("removeSyncsForAuthority: ", info, why);
        verifyJobScheduler();
        List<SyncOperation> ops = getAllPendingSyncs();
        for (SyncOperation op: ops) {
            if (op.target.matchesSpec(info)) {
                mLogger.log("canceling: ", op);
                cancelJob(op, why);
            }
        }
    }

    /**
     * Remove a specific periodic sync identified by its target and extras.
     */
    public void removePeriodicSync(EndPoint target, Bundle extras, String why) {
        Message m = mSyncHandler.obtainMessage(mSyncHandler.MESSAGE_REMOVE_PERIODIC_SYNC,
                Pair.create(target, why));
        m.setData(extras);
        m.sendToTarget();
    }

    /**
     * Add a periodic sync. If a sync with same target and extras exists, its period and
     * flexMillis will be updated.
     */
    public void updateOrAddPeriodicSync(EndPoint target, long pollFrequency, long flex,
            Bundle extras) {
        UpdatePeriodicSyncMessagePayload payload = new UpdatePeriodicSyncMessagePayload(target,
                pollFrequency, flex, extras);
        mSyncHandler.obtainMessage(SyncHandler.MESSAGE_UPDATE_PERIODIC_SYNC, payload)
                .sendToTarget();
    }

    /**
     * Get a list of periodic syncs corresponding to the given target.
     */
    public List<PeriodicSync> getPeriodicSyncs(EndPoint target) {
        List<SyncOperation> ops = getAllPendingSyncs();
        List<PeriodicSync> periodicSyncs = new ArrayList<PeriodicSync>();

        for (SyncOperation op: ops) {
            if (op.isPeriodic && op.target.matchesSpec(target)) {
                periodicSyncs.add(new PeriodicSync(op.target.account, op.target.provider,
                        op.getClonedExtras(), op.periodMillis / 1000, op.flexMillis / 1000));
            }
        }

        return periodicSyncs;
    }

    /**
     * Schedule sync based on local changes to a provider. We wait for at least LOCAL_SYNC_DELAY
     * ms to batch syncs.
     */
    public void scheduleLocalSync(Account account, int userId, int reason, String authority,
            @SyncExemption int syncExemptionFlag,
            int callingUid, int callingPid, String callingPackage) {
        final Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true);
        scheduleSync(account, userId, reason, authority, extras,
                AuthorityInfo.UNDEFINED, LOCAL_SYNC_DELAY, true /* checkIfAccountReady */,
                syncExemptionFlag, callingUid, callingPid, callingPackage);
    }

    public SyncAdapterType[] getSyncAdapterTypes(int callingUid, int userId) {
        final Collection<RegisteredServicesCache.ServiceInfo<SyncAdapterType>> serviceInfos;
        serviceInfos = mSyncAdapters.getAllServices(userId);
        final List<SyncAdapterType> types = new ArrayList<>(serviceInfos.size());
        for (RegisteredServicesCache.ServiceInfo<SyncAdapterType> serviceInfo : serviceInfos) {
            final String packageName = serviceInfo.type.getPackageName();
            if (!TextUtils.isEmpty(packageName) && mPackageManagerInternal.filterAppAccess(
                    packageName, callingUid, userId)) {
                continue;
            }
            types.add(serviceInfo.type);
        }
        return types.toArray(new SyncAdapterType[] {});
    }

    public String[] getSyncAdapterPackagesForAuthorityAsUser(String authority, int callingUid,
            int userId) {
        final String[] syncAdapterPackages = mSyncAdapters.getSyncAdapterPackagesForAuthority(
                authority, userId);
        final List<String> filteredResult = new ArrayList<>(syncAdapterPackages.length);
        for (String packageName : syncAdapterPackages) {
            if (TextUtils.isEmpty(packageName) || mPackageManagerInternal.filterAppAccess(
                    packageName, callingUid, userId)) {
                continue;
            }
            filteredResult.add(packageName);
        }
        return filteredResult.toArray(new String[] {});
    }

    public String getSyncAdapterPackageAsUser(String accountType, String authority,
            int callingUid, int userId) {
        if (accountType == null || authority == null) {
            return null;
        }
        final RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo =
                mSyncAdapters.getServiceInfo(
                        SyncAdapterType.newKey(authority, accountType),
                        userId);
        if (syncAdapterInfo == null) {
            return null;
        }
        final String packageName = syncAdapterInfo.type.getPackageName();
        if (TextUtils.isEmpty(packageName) || mPackageManagerInternal.filterAppAccess(
                packageName, callingUid, userId)) {
            return null;
        }
        return packageName;
    }

    private void sendSyncFinishedOrCanceledMessage(ActiveSyncContext syncContext,
            SyncResult syncResult) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Slog.v(TAG, "sending MESSAGE_SYNC_FINISHED");
        Message msg = mSyncHandler.obtainMessage();
        msg.what = SyncHandler.MESSAGE_SYNC_FINISHED;
        msg.obj = new SyncFinishedOrCancelledMessagePayload(syncContext, syncResult);
        mSyncHandler.sendMessage(msg);
    }

    private void sendCancelSyncsMessage(final SyncStorageEngine.EndPoint info, Bundle extras,
            String why) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Slog.v(TAG, "sending MESSAGE_CANCEL");

        mLogger.log("sendCancelSyncsMessage() ep=", info, " why=", why);

        Message msg = mSyncHandler.obtainMessage();
        msg.what = SyncHandler.MESSAGE_CANCEL;
        msg.setData(extras);
        msg.obj = info;
        mSyncHandler.sendMessage(msg);
    }

    /**
     * Post a delayed message that will monitor the given sync context by periodically checking how
     * much network has been used by the uid.
     */
    private void postMonitorSyncProgressMessage(ActiveSyncContext activeSyncContext) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "posting MESSAGE_SYNC_MONITOR in " +
                    (SYNC_MONITOR_WINDOW_LENGTH_MILLIS/1000) + "s");
        }

        activeSyncContext.mBytesTransferredAtLastPoll =
                getTotalBytesTransferredByUid(activeSyncContext.mSyncAdapterUid);
        activeSyncContext.mLastPolledTimeElapsed = SystemClock.elapsedRealtime();
        Message monitorMessage =
                mSyncHandler.obtainMessage(
                        SyncHandler.MESSAGE_MONITOR_SYNC,
                        activeSyncContext);
        mSyncHandler.sendMessageDelayed(monitorMessage, SYNC_MONITOR_WINDOW_LENGTH_MILLIS);
    }

    private void postScheduleSyncMessage(SyncOperation syncOperation, long minDelayMillis) {
        ScheduleSyncMessagePayload payload =
                new ScheduleSyncMessagePayload(syncOperation, minDelayMillis);
        mSyncHandler.obtainMessage(mSyncHandler.MESSAGE_SCHEDULE_SYNC, payload).sendToTarget();
    }

    /**
     * Monitor sync progress by calculating how many bytes it is managing to send to and fro.
     */
    private long getTotalBytesTransferredByUid(int uid) {
        return (TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid));
    }

    /**
     * Convenience class for passing parameters for a finished or cancelled sync to the handler
     * to be processed.
     */
    private class SyncFinishedOrCancelledMessagePayload {
        public final ActiveSyncContext activeSyncContext;
        public final SyncResult syncResult;

        SyncFinishedOrCancelledMessagePayload(ActiveSyncContext syncContext,
                SyncResult syncResult) {
            this.activeSyncContext = syncContext;
            this.syncResult = syncResult;
        }
    }

    private class UpdatePeriodicSyncMessagePayload {
        public final EndPoint target;
        public final long pollFrequency;
        public final long flex;
        public final Bundle extras;

        UpdatePeriodicSyncMessagePayload(EndPoint target, long pollFrequency, long flex,
                Bundle extras) {
            this.target = target;
            this.pollFrequency = pollFrequency;
            this.flex = flex;
            this.extras = extras;
        }
    }

    private static class ScheduleSyncMessagePayload {
        final SyncOperation syncOperation;
        final long minDelayMillis;

        ScheduleSyncMessagePayload(SyncOperation syncOperation, long minDelayMillis) {
            this.syncOperation = syncOperation;
            this.minDelayMillis = minDelayMillis;
        }
    }

    private void clearBackoffSetting(EndPoint target, String why) {
        Pair<Long, Long> backoff = mSyncStorageEngine.getBackoff(target);
        if (backoff != null && backoff.first == SyncStorageEngine.NOT_IN_BACKOFF_MODE &&
                backoff.second == SyncStorageEngine.NOT_IN_BACKOFF_MODE) {
            return;
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "Clearing backoffs for " + target);
        }
        mSyncStorageEngine.setBackoff(target,
                SyncStorageEngine.NOT_IN_BACKOFF_MODE,
                SyncStorageEngine.NOT_IN_BACKOFF_MODE);

        rescheduleSyncs(target, why);
    }

    private void increaseBackoffSetting(EndPoint target) {
        final long now = SystemClock.elapsedRealtime();

        final Pair<Long, Long> previousSettings =
                mSyncStorageEngine.getBackoff(target);
        long newDelayInMs = -1;
        if (previousSettings != null) {
            // Don't increase backoff before current backoff is expired. This will happen for op's
            // with ignoreBackoff set.
            if (now < previousSettings.first) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.v(TAG, "Still in backoff, do not increase it. "
                            + "Remaining: " + ((previousSettings.first - now) / 1000) + " seconds.");
                }
                return;
            }
            // Subsequent delays are the double of the previous delay.
            newDelayInMs =
                    (long) (previousSettings.second * mConstants.getRetryTimeIncreaseFactor());
        }
        if (newDelayInMs <= 0) {
            // The initial delay is the jitterized INITIAL_SYNC_RETRY_TIME_IN_MS.
            final long initialRetryMs = mConstants.getInitialSyncRetryTimeInSeconds() * 1000;
            newDelayInMs = jitterize(initialRetryMs, (long)(initialRetryMs * 1.1));
        }

        // Cap the delay.
        final long maxSyncRetryTimeInSeconds = mConstants.getMaxSyncRetryTimeInSeconds();

        if (newDelayInMs > maxSyncRetryTimeInSeconds * 1000) {
            newDelayInMs = maxSyncRetryTimeInSeconds * 1000;
        }

        final long backoff = now + newDelayInMs;
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "Backoff until: " + backoff + ", delayTime: " + newDelayInMs);
        }
        mSyncStorageEngine.setBackoff(target, backoff, newDelayInMs);
        rescheduleSyncs(target, "increaseBackoffSetting");
    }

    /**
     * Reschedule all scheduled syncs for this EndPoint. The syncs will be scheduled according
     * to current backoff and delayUntil values of this EndPoint.
     */
    private void rescheduleSyncs(EndPoint target, String why) {
        mLogger.log("rescheduleSyncs() ep=", target, " why=", why);

        List<SyncOperation> ops = getAllPendingSyncs();
        int count = 0;
        for (SyncOperation op: ops) {
            if (!op.isPeriodic && op.target.matchesSpec(target)) {
                count++;
                cancelJob(op, why);
                postScheduleSyncMessage(op, 0 /* min delay */);
            }
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "Rescheduled " + count + " syncs for " + target);
        }
    }

    private void setDelayUntilTime(EndPoint target, long delayUntilSeconds) {
        final long delayUntil = delayUntilSeconds * 1000;
        final long absoluteNow = System.currentTimeMillis();
        long newDelayUntilTime;
        if (delayUntil > absoluteNow) {
            newDelayUntilTime = SystemClock.elapsedRealtime() + (delayUntil - absoluteNow);
        } else {
            newDelayUntilTime = 0;
        }
        mSyncStorageEngine.setDelayUntilTime(target, newDelayUntilTime);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "Delay Until time set to " + newDelayUntilTime + " for " + target);
        }
        rescheduleSyncs(target, "delayUntil newDelayUntilTime: " + newDelayUntilTime);
    }

    private boolean isAdapterDelayed(EndPoint target) {
        long now = SystemClock.elapsedRealtime();
        Pair<Long, Long> backoff = mSyncStorageEngine.getBackoff(target);
        if (backoff != null && backoff.first != SyncStorageEngine.NOT_IN_BACKOFF_MODE
                && backoff.first > now) {
            return true;
        }
        if (mSyncStorageEngine.getDelayUntilTime(target) > now) {
            return true;
        }
        return false;
    }

    /**
     * Cancel the active sync if it matches the target.
     * @param info object containing info about which syncs to cancel. The target can
     * have null account/provider info to specify all accounts/providers.
     * @param extras if non-null, specifies the exact sync to remove.
     */
    public void cancelActiveSync(SyncStorageEngine.EndPoint info, Bundle extras, String why) {
        sendCancelSyncsMessage(info, extras, why);
    }

    /**
     * Schedule a sync operation with JobScheduler.
     */
    private void scheduleSyncOperationH(SyncOperation syncOperation) {
        scheduleSyncOperationH(syncOperation, 0L);
    }

    private void scheduleSyncOperationH(SyncOperation syncOperation, long minDelay) {
        final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
        if (syncOperation == null) {
            Slog.e(TAG, "Can't schedule null sync operation.");
            return;
        }
        if (!syncOperation.hasIgnoreBackoff()) {
            Pair<Long, Long> backoff = mSyncStorageEngine.getBackoff(syncOperation.target);
            if (backoff == null) {
                Slog.e(TAG, "Couldn't find backoff values for "
                        + logSafe(syncOperation.target));
                backoff = new Pair<Long, Long>(SyncStorageEngine.NOT_IN_BACKOFF_MODE,
                        SyncStorageEngine.NOT_IN_BACKOFF_MODE);
            } else if (backoff.first != SyncStorageEngine.NOT_IN_BACKOFF_MODE) {
                // if an EJ is being backed-off but doesn't have SYNC_EXTRAS_IGNORE_BACKOFF set,
                // reschedule it as a regular job. Immediately downgrade here in case minDelay is
                // set to 0.
                syncOperation.scheduleEjAsRegularJob = true;
            }
            long now = SystemClock.elapsedRealtime();
            long backoffDelay = backoff.first == SyncStorageEngine.NOT_IN_BACKOFF_MODE ? 0
                    : backoff.first - now;
            long delayUntil = mSyncStorageEngine.getDelayUntilTime(syncOperation.target);
            long delayUntilDelay = delayUntil > now ? delayUntil - now : 0;
            if (isLoggable) {
                Slog.v(TAG, "backoff delay:" + backoffDelay
                        + " delayUntil delay:" + delayUntilDelay);
            }
            minDelay = Math.max(minDelay, Math.max(backoffDelay, delayUntilDelay));
        }

        if (minDelay < 0) {
            minDelay = 0;
        } else if (minDelay > 0) {
            // We can't apply a delay to an EJ. If we want to delay it, we must demote it to a
            // regular job.
            syncOperation.scheduleEjAsRegularJob = true;
        }

        // Check if duplicate syncs are pending. If found, keep one with least expected run time.

        // If any of the duplicate ones has exemption, then we inherit it.
        if (!syncOperation.isPeriodic) {
            int inheritedSyncExemptionFlag = ContentResolver.SYNC_EXEMPTION_NONE;

            // Check currently running syncs
            for (ActiveSyncContext asc: mActiveSyncContexts) {
                if (asc.mSyncOperation.key.equals(syncOperation.key)) {
                    if (isLoggable) {
                        Log.v(TAG, "Duplicate sync is already running. Not scheduling "
                                + syncOperation);
                    }
                    return;
                }
            }

            int duplicatesCount = 0;
            long now = SystemClock.elapsedRealtime();
            syncOperation.expectedRuntime = now + minDelay;
            List<SyncOperation> pending = getAllPendingSyncs();
            SyncOperation syncToRun = syncOperation;
            for (SyncOperation op : pending) {
                if (op.isPeriodic) {
                    continue;
                }
                if (op.key.equals(syncOperation.key)) {
                    if (syncToRun.expectedRuntime > op.expectedRuntime) {
                        syncToRun = op;
                    }
                    duplicatesCount++;
                }
            }
            if (duplicatesCount > 1) {
                Slog.wtf(TAG, "duplicates found when scheduling a sync operation: "
                        + "owningUid=" + syncOperation.owningUid
                        + "; owningPackage=" + syncOperation.owningPackage
                        + "; source=" + syncOperation.syncSource
                        + "; adapter=" + (syncOperation.target != null
                                            ? syncOperation.target.provider
                                            : "unknown"));
            }

            if (syncOperation != syncToRun) {
                // If there's a duplicate with an earlier run time that's not exempted,
                // and if the current operation is exempted with no minDelay,
                // cancel the duplicate one and keep the current one.
                //
                // This means the duplicate one has a negative expected run time, but it hasn't
                // been executed possibly because of app-standby.

                if ((minDelay == 0)
                        && (syncToRun.syncExemptionFlag < syncOperation.syncExemptionFlag)) {
                    syncToRun = syncOperation;
                    inheritedSyncExemptionFlag =
                            Math.max(inheritedSyncExemptionFlag, syncToRun.syncExemptionFlag);
                }
            }

            // Cancel all other duplicate syncs.
            for (SyncOperation op : pending) {
                if (op.isPeriodic) {
                    continue;
                }
                if (op.key.equals(syncOperation.key)) {
                    if (op != syncToRun) {
                        if (isLoggable) {
                            Slog.v(TAG, "Cancelling duplicate sync " + op);
                        }
                        inheritedSyncExemptionFlag =
                                Math.max(inheritedSyncExemptionFlag, op.syncExemptionFlag);
                        cancelJob(op, "scheduleSyncOperationH-duplicate");
                    }
                }
            }
            if (syncToRun != syncOperation) {
                // Don't schedule because a duplicate sync with earlier expected runtime exists.
                if (isLoggable) {
                    Slog.v(TAG, "Not scheduling because a duplicate exists.");
                }

                // TODO Should we give the winning one SYNC_EXTRAS_APP_STANDBY_EXEMPTED
                // if the current one has it?
                return;
            }

            // If any of the duplicates had exemption, we exempt the current one.
            //
            if (inheritedSyncExemptionFlag > ContentResolver.SYNC_EXEMPTION_NONE) {
                syncOperation.syncExemptionFlag = inheritedSyncExemptionFlag;
            }
        }

        // Syncs that are re-scheduled shouldn't get a new job id.
        if (syncOperation.jobId == SyncOperation.NO_JOB_ID) {
            syncOperation.jobId = getUnusedJobIdH();
        }

        if (isLoggable) {
            Slog.v(TAG, "scheduling sync operation " + syncOperation.toString());
        }

        int bias = syncOperation.getJobBias();

        final int networkType = syncOperation.isNotAllowedOnMetered() ?
                JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY;

        // Note this logic means when an exempted sync fails,
        // the back-off one will inherit it too, and will be exempted from app-standby.
        final int jobFlags = syncOperation.isAppStandbyExempted()
                ? JobInfo.FLAG_EXEMPT_FROM_APP_STANDBY : 0;

        JobInfo.Builder b = new JobInfo.Builder(syncOperation.jobId,
                new ComponentName(mContext, SyncJobService.class))
                .setExtras(syncOperation.toJobInfoExtras())
                .setRequiredNetworkType(networkType)
                .setRequiresStorageNotLow(true)
                .setPersisted(true)
                .setBias(bias)
                .setFlags(jobFlags);

        if (syncOperation.isPeriodic) {
            b.setPeriodic(syncOperation.periodMillis, syncOperation.flexMillis);
        } else {
            if (minDelay > 0) {
                b.setMinimumLatency(minDelay);
            }
            getSyncStorageEngine().markPending(syncOperation.target, true);
        }

        if (syncOperation.hasRequireCharging()) {
            b.setRequiresCharging(true);
        }

        if (syncOperation.isScheduledAsExpeditedJob() && !syncOperation.scheduleEjAsRegularJob) {
            b.setExpedited(true);
        }

        if (syncOperation.syncExemptionFlag
                == ContentResolver.SYNC_EXEMPTION_PROMOTE_BUCKET_WITH_TEMP) {
            DeviceIdleInternal dic =
                    LocalServices.getService(DeviceIdleInternal.class);
            if (dic != null) {
                dic.addPowerSaveTempWhitelistApp(Process.SYSTEM_UID,
                        syncOperation.owningPackage,
                        mConstants.getKeyExemptionTempWhitelistDurationInSeconds() * 1000,
                        TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED,
                        UserHandle.getUserId(syncOperation.owningUid),
                        /* sync=*/ false, REASON_SYNC_MANAGER, "sync by top app");
            }
        }

        final UsageStatsManagerInternal usmi =
                LocalServices.getService(UsageStatsManagerInternal.class);
        if (usmi != null) {
            // This method name is unfortunate. It elevates apps to a higher bucket, so it ideally
            // should be called before we attempt to schedule the job (especially as EJ).
            usmi.reportSyncScheduled(syncOperation.owningPackage,
                    UserHandle.getUserId(syncOperation.owningUid),
                    syncOperation.isAppStandbyExempted());
        }

        final JobInfo ji = b.build();
        int result = getJobScheduler().scheduleAsPackage(ji, syncOperation.owningPackage,
                syncOperation.target.userId, syncOperation.wakeLockName());
        if (result == JobScheduler.RESULT_FAILURE && ji.isExpedited()) {
            if (isLoggable) {
                Slog.i(TAG, "Failed to schedule EJ for " + syncOperation.owningPackage
                        + ". Downgrading to regular");
            }
            syncOperation.scheduleEjAsRegularJob = true;
            b.setExpedited(false).setExtras(syncOperation.toJobInfoExtras());
            result = getJobScheduler().scheduleAsPackage(b.build(), syncOperation.owningPackage,
                    syncOperation.target.userId, syncOperation.wakeLockName());
        }
        if (result == JobScheduler.RESULT_FAILURE) {
            Slog.e(TAG, "Failed to schedule job for " + syncOperation.owningPackage);
            // TODO: notify AppStandbyController that the sync isn't actually scheduled so the
            // bucket doesn't stay elevated
        }
    }

    /**
     * Remove scheduled sync operations.
     * @param info limit the removals to operations that match this target. The target can
     * have null account/provider info to specify all accounts/providers.
     */
    public void clearScheduledSyncOperations(SyncStorageEngine.EndPoint info) {
        List<SyncOperation> ops = getAllPendingSyncs();
        for (SyncOperation op: ops) {
            if (!op.isPeriodic && op.target.matchesSpec(info)) {
                cancelJob(op, "clearScheduledSyncOperations");
                getSyncStorageEngine().markPending(op.target, false);
            }
        }
        mSyncStorageEngine.setBackoff(info,
                SyncStorageEngine.NOT_IN_BACKOFF_MODE, SyncStorageEngine.NOT_IN_BACKOFF_MODE);
    }

    /**
     * Remove a specified sync, if it exists.
     * @param info Authority for which the sync is to be removed.
     * @param extras extras bundle to uniquely identify sync.
     */
    public void cancelScheduledSyncOperation(SyncStorageEngine.EndPoint info, Bundle extras) {
        List<SyncOperation> ops = getAllPendingSyncs();
        for (SyncOperation op: ops) {
            if (!op.isPeriodic && op.target.matchesSpec(info)
                    && op.areExtrasEqual(extras, /*includeSyncSettings=*/ false)) {
                cancelJob(op, "cancelScheduledSyncOperation");
            }
        }
        setAuthorityPendingState(info);
        // Reset the back-off if there are no more syncs pending.
        if (!mSyncStorageEngine.isSyncPending(info)) {
            mSyncStorageEngine.setBackoff(info,
                    SyncStorageEngine.NOT_IN_BACKOFF_MODE, SyncStorageEngine.NOT_IN_BACKOFF_MODE);
        }
    }

    private void maybeRescheduleSync(SyncResult syncResult, SyncOperation operation) {
        final boolean isLoggable = Log.isLoggable(TAG, Log.DEBUG);
        if (isLoggable) {
            Log.d(TAG, "encountered error(s) during the sync: " + syncResult + ", " + operation);
        }

        operation.enableBackoff();
        // Never run a rescheduled requested-EJ-sync as an EJ.
        operation.scheduleEjAsRegularJob = true;

        if (operation.hasDoNotRetry() && !syncResult.syncAlreadyInProgress) {
            // syncAlreadyInProgress flag is set by AbstractThreadedSyncAdapter. The sync adapter
            // has no way of knowing that a sync error occured. So we DO retry if the error is
            // syncAlreadyInProgress.
            if (isLoggable) {
                Log.d(TAG, "not retrying sync operation because SYNC_EXTRAS_DO_NOT_RETRY was specified "
                        + operation);
            }
        } else if (operation.isUpload() && !syncResult.syncAlreadyInProgress) {
            // If this was an upward sync then schedule a two-way sync immediately.
            operation.enableTwoWaySync();
            if (isLoggable) {
                Log.d(TAG, "retrying sync operation as a two-way sync because an upload-only sync "
                        + "encountered an error: " + operation);
            }
            scheduleSyncOperationH(operation);
        } else if (syncResult.tooManyRetries) {
            // If this sync aborted because the internal sync loop retried too many times then
            //   don't reschedule. Otherwise we risk getting into a retry loop.
            if (isLoggable) {
                Log.d(TAG, "not retrying sync operation because it retried too many times: "
                        + operation);
            }
        } else if (syncResult.madeSomeProgress()) {
            // If the operation succeeded to some extent then retry immediately.
            if (isLoggable) {
                Log.d(TAG, "retrying sync operation because even though it had an error "
                        + "it achieved some success");
            }
            scheduleSyncOperationH(operation);
        } else if (syncResult.syncAlreadyInProgress) {
            if (isLoggable) {
                Log.d(TAG, "retrying sync operation that failed because there was already a "
                        + "sync in progress: " + operation);
            }
            scheduleSyncOperationH(operation, DELAY_RETRY_SYNC_IN_PROGRESS_IN_SECONDS * 1000);
        } else if (syncResult.hasSoftError()) {
            // If this was a two-way sync then retry soft errors with an exponential backoff.
            if (isLoggable) {
                Log.d(TAG, "retrying sync operation because it encountered a soft error: "
                        + operation);
            }
            scheduleSyncOperationH(operation);
        } else {
            // Otherwise do not reschedule.
            Log.e(TAG, "not retrying sync operation because the error is a hard error: "
                    + logSafe(operation));
        }
    }

    private void onUserUnlocked(int userId) {
        // Make sure that accounts we're about to use are valid.
        AccountManagerService.getSingleton().validateAccounts(userId);

        mSyncAdapters.invalidateCache(userId);

        EndPoint target = new EndPoint(null, null, userId);
        updateRunningAccounts(target);

        // Schedule sync for any accounts under started user, but only the NOT_INITIALIZED adapters.
        final Account[] accounts = AccountManagerService.getSingleton().getAccounts(userId,
                mContext.getOpPackageName());
        for (Account account : accounts) {
            scheduleSync(account, userId, SyncOperation.REASON_USER_START, null, null,
                    AuthorityInfo.NOT_INITIALIZED, ContentResolver.SYNC_EXEMPTION_NONE,
                    Process.myUid(), -3, null);
        }
    }

    private void onUserStopped(int userId) {
        updateRunningAccounts(null /* Don't sync any target */);

        cancelActiveSync(
                new SyncStorageEngine.EndPoint(
                        null /* any account */,
                        null /* any authority */,
                        userId),
                null /* any sync. */,
                "onUserStopped"
        );
    }

    private void onUserRemoved(int userId) {
        mLogger.log("onUserRemoved: u", userId);
        updateRunningAccounts(null /* Don't sync any target */);

        // Clean up the storage engine database
        mSyncStorageEngine.removeStaleAccounts(null, userId);
        List<SyncOperation> ops = getAllPendingSyncs();
        for (SyncOperation op: ops) {
            if (op.target.userId == userId) {
                cancelJob(op, "user removed u" + userId);
            }
        }
    }

    /**
     * Construct intent used to bind to an adapter.
     *
     * @param context Context to create intent for
     * @param syncAdapterComponent The adapter description
     * @param userId The user the adapter belongs to
     *
     * @return The intent required to bind to the adapter
     */
    static @NonNull Intent getAdapterBindIntent(@NonNull Context context,
            @NonNull ComponentName syncAdapterComponent, @UserIdInt int userId) {
        final Intent intent = new Intent();
        intent.setAction("android.content.SyncAdapter");
        intent.setComponent(syncAdapterComponent);
        intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                com.android.internal.R.string.sync_binding_label);
        intent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivityAsUser(context, 0,
                new Intent(Settings.ACTION_SYNC_SETTINGS), PendingIntent.FLAG_IMMUTABLE, null,
                UserHandle.of(userId)));

        return intent;
    }

    /**
     * @hide
     */
    class ActiveSyncContext extends ISyncContext.Stub
            implements ServiceConnection, IBinder.DeathRecipient {
        final SyncOperation mSyncOperation;
        final long mHistoryRowId;
        ISyncAdapter mSyncAdapter;
        final long mStartTime;
        long mTimeoutStartTime;
        boolean mBound;
        final PowerManager.WakeLock mSyncWakeLock;
        final int mSyncAdapterUid;
        SyncInfo mSyncInfo;
        boolean mIsLinkedToDeath = false;
        String mEventName;

        /** Total bytes transferred, counted at {@link #mLastPolledTimeElapsed} */
        long mBytesTransferredAtLastPoll;
        /**
         * Last point in {@link SystemClock#elapsedRealtime()} at which we checked the # of bytes
         * transferred to/fro by this adapter.
         */
        long mLastPolledTimeElapsed;

        /**
         * Create an ActiveSyncContext for an impending sync and grab the wakelock for that
         * sync adapter. Since this grabs the wakelock you need to be sure to call
         * close() when you are done with this ActiveSyncContext, whether the sync succeeded
         * or not.
         * @param syncOperation the SyncOperation we are about to sync
         * @param historyRowId the row in which to record the history info for this sync
         * @param syncAdapterUid the UID of the application that contains the sync adapter
         * for this sync. This is used to attribute the wakelock hold to that application.
         */
        public ActiveSyncContext(SyncOperation syncOperation, long historyRowId,
                int syncAdapterUid) {
            super();
            mSyncAdapterUid = syncAdapterUid;
            mSyncOperation = syncOperation;
            mHistoryRowId = historyRowId;
            mSyncAdapter = null;
            mStartTime = SystemClock.elapsedRealtime();
            mTimeoutStartTime = mStartTime;
            mSyncWakeLock = mSyncHandler.getSyncWakeLock(mSyncOperation);
            mSyncWakeLock.setWorkSource(new WorkSource(syncAdapterUid));
            mSyncWakeLock.acquire();
        }

        public void sendHeartbeat() {
            // Heartbeats are no longer used.
        }

        public void onFinished(SyncResult result) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) Slog.v(TAG, "onFinished: " + this);
            // Include "this" in the message so that the handler can ignore it if this
            // ActiveSyncContext is no longer the mActiveSyncContext at message handling
            // time.
            mLogger.log("onFinished result=", result, " endpoint=",
                    (mSyncOperation == null ? "null" : mSyncOperation.target));
            sendSyncFinishedOrCanceledMessage(this, result);
        }

        public void toString(StringBuilder sb, boolean logSafe) {
            sb.append("startTime ").append(mStartTime)
                    .append(", mTimeoutStartTime ").append(mTimeoutStartTime)
                    .append(", mHistoryRowId ").append(mHistoryRowId)
                    .append(", syncOperation ").append(
                        logSafe ? logSafe(mSyncOperation) : mSyncOperation);
        }

        public void onServiceConnected(ComponentName name, IBinder service) {
            Message msg = mSyncHandler.obtainMessage();
            msg.what = SyncHandler.MESSAGE_SERVICE_CONNECTED;
            msg.obj = new ServiceConnectionData(this, service);
            mSyncHandler.sendMessage(msg);
        }

        public void onServiceDisconnected(ComponentName name) {
            Message msg = mSyncHandler.obtainMessage();
            msg.what = SyncHandler.MESSAGE_SERVICE_DISCONNECTED;
            msg.obj = new ServiceConnectionData(this, null);
            mSyncHandler.sendMessage(msg);
        }

        boolean bindToSyncAdapter(ComponentName serviceComponent, int userId) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "bindToSyncAdapter: " + serviceComponent + ", connection " + this);
            }
            Intent intent = getAdapterBindIntent(mContext, serviceComponent, userId);

            mBound = true;
            final boolean bindResult = mContext.bindServiceAsUser(intent, this,
                    SYNC_ADAPTER_CONNECTION_FLAGS, new UserHandle(mSyncOperation.target.userId));
            mLogger.log("bindService() returned=", mBound, " for ", this);
            if (!bindResult) {
                mBound = false;
            } else {
                try {
                    mEventName = mSyncOperation.wakeLockName();
                    mBatteryStats.noteSyncStart(mEventName, mSyncAdapterUid);
                } catch (RemoteException e) {
                }
            }
            return bindResult;
        }

        /**
         * Performs the required cleanup, which is the releasing of the wakelock and
         * unbinding from the sync adapter (if actually bound).
         */
        protected void close() {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "unBindFromSyncAdapter: connection " + this);
            }
            if (mBound) {
                mBound = false;
                mLogger.log("unbindService for ", this);
                mContext.unbindService(this);
                try {
                    mBatteryStats.noteSyncFinish(mEventName, mSyncAdapterUid);
                } catch (RemoteException e) {
                }
            }
            mSyncWakeLock.release();
            mSyncWakeLock.setWorkSource(null);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            toString(sb, false);
            return sb.toString();
        }

        public String toSafeString() {
            StringBuilder sb = new StringBuilder();
            toString(sb, true);
            return sb.toString();
        }

        @Override
        public void binderDied() {
            sendSyncFinishedOrCanceledMessage(this, null);
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, boolean dumpAll) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");

        final SyncAdapterStateFetcher buckets = new SyncAdapterStateFetcher();

        dumpSyncState(ipw, buckets);
        mConstants.dump(pw, "");
        dumpSyncAdapters(ipw);

        if (dumpAll) {
            ipw.println("Detailed Sync History");
            mLogger.dumpAll(pw);
        }
    }

    static String formatTime(long time) {
        if (time == 0) {
            return "N/A";
        }
        return TimeMigrationUtils.formatMillisWithFixedFormat(time);
    }

    private final static Comparator<SyncOperation> sOpDumpComparator = (op1, op2) -> {
        int res = Integer.compare(op1.target.userId, op2.target.userId);
        if (res != 0) return res;

        final Comparator<String> stringComparator = String.CASE_INSENSITIVE_ORDER;

        res = stringComparator.compare(op1.target.account.type, op2.target.account.type);
        if (res != 0) return res;

        res = stringComparator.compare(op1.target.account.name, op2.target.account.name);
        if (res != 0) return res;

        res = stringComparator.compare(op1.target.provider, op2.target.provider);
        if (res != 0) return res;

        res = Integer.compare(op1.reason, op2.reason);
        if (res != 0) return res;

        res = Long.compare(op1.periodMillis, op2.periodMillis);
        if (res != 0) return res;

        res = Long.compare(op1.expectedRuntime, op2.expectedRuntime);
        if (res != 0) return res;

        res = Long.compare(op1.jobId, op2.jobId);
        if (res != 0) return res;

        return 0;
    };

    private final static Comparator<SyncOperation> sOpRuntimeComparator = (op1, op2) -> {
        int res = Long.compare(op1.expectedRuntime, op2.expectedRuntime);
        if (res != 0) return res;

        return sOpDumpComparator.compare(op1, op2);
    };

    private static <T> int countIf(Collection<T> col, Predicate<T> p) {
        int ret = 0;
        for (T item : col) {
            if (p.test(item)) ret++;
        }
        return ret;
    }

    protected void dumpPendingSyncs(PrintWriter pw, SyncAdapterStateFetcher buckets) {
        List<SyncOperation> pendingSyncs = getAllPendingSyncs();

        pw.print("Pending Syncs: ");
        pw.println(countIf(pendingSyncs, op -> !op.isPeriodic));

        Collections.sort(pendingSyncs, sOpRuntimeComparator);
        int count = 0;
        for (SyncOperation op: pendingSyncs) {
            if (!op.isPeriodic) {
                pw.println(op.dump(null, false, buckets, /*logSafe=*/ false));
                count++;
            }
        }
        pw.println();
    }

    protected void dumpPeriodicSyncs(PrintWriter pw, SyncAdapterStateFetcher buckets) {
        List<SyncOperation> pendingSyncs = getAllPendingSyncs();

        pw.print("Periodic Syncs: ");
        pw.println(countIf(pendingSyncs, op -> op.isPeriodic));

        Collections.sort(pendingSyncs, sOpDumpComparator);
        int count = 0;
        for (SyncOperation op: pendingSyncs) {
            if (op.isPeriodic) {
                pw.println(op.dump(null, false, buckets, /*logSafe=*/ false));
                count++;
            }
        }
        pw.println();
    }

    /**
     * Similar to {@link android.util.TimeUtils#formatDuration}, but it's more suitable and concise
     * for the sync manager dumpsys.  (Don't add the leading + sign, don't show milliseconds.)
     */
    public static StringBuilder formatDurationHMS(StringBuilder sb, long duration) {
        duration /= 1000;
        if (duration < 0) {
            sb.append('-');
            duration = -duration;
        }
        final long seconds = duration % 60;
        duration /= 60;

        final long minutes = duration % 60;
        duration /= 60;

        final long hours = duration % 24;
        duration /= 24;

        final long days = duration;

        boolean print = false;
        if (days > 0) {
            sb.append(days);
            sb.append('d');
            print = true;
        }
        print = printTwoDigitNumber(sb, hours, 'h', print);
        print = printTwoDigitNumber(sb, minutes, 'm', print);
        print = printTwoDigitNumber(sb, seconds, 's', print);
        if (!print) {
            sb.append("0s");
        }

        return sb;
    }

    private static boolean printTwoDigitNumber(StringBuilder sb, long value, char unit,
            boolean always) {
        if (!always && (value == 0)) {
            return false;
        }
        if (always && (value < 10)) {
            sb.append('0');
        }
        sb.append(value);
        sb.append(unit);
        return true;
    }

    @NeverCompile // Avoid size overhead of debugging code.
    protected void dumpSyncState(PrintWriter pw, SyncAdapterStateFetcher buckets) {
        final StringBuilder sb = new StringBuilder();

        pw.print("Data connected: "); pw.println(mDataConnectionIsConnected);
        pw.print("Battery saver: ");
        pw.println((mPowerManager != null) && mPowerManager.isPowerSaveMode());

        pw.print("Background network restriction: ");
        {
            final ConnectivityManager cm = getConnectivityManager();
            final int status = (cm == null) ? -1 : cm.getRestrictBackgroundStatus();
            switch (status) {
                case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED:
                    pw.println(" disabled");
                    break;
                case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED:
                    pw.println(" whitelisted");
                    break;
                case ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED:
                    pw.println(" enabled");
                    break;
                default:
                    pw.print("Unknown(");
                    pw.print(status);
                    pw.println(")");
                    break;
            }
        }

        pw.print("Auto sync: ");
        List<UserInfo> users = getAllUsers();
        if (users != null) {
            for (UserInfo user : users) {
                pw.print("u" + user.id + "="
                        + mSyncStorageEngine.getMasterSyncAutomatically(user.id) + " ");
            }
            pw.println();
        }
        Intent storageLowIntent =
                mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW));
        pw.print("Storage low: "); pw.println(storageLowIntent != null);
        pw.print("Clock valid: "); pw.println(mSyncStorageEngine.isClockValid());

        final AccountAndUser[] accounts =
                AccountManagerService.getSingleton().getAllAccountsForSystemProcess();

        pw.print("Accounts: ");
        if (accounts != INITIAL_ACCOUNTS_ARRAY) {
            pw.println(accounts.length);
        } else {
            pw.println("not known yet");
        }
        final long now = SystemClock.elapsedRealtime();
        pw.print("Now: "); pw.print(now);
        pw.println(" (" + formatTime(System.currentTimeMillis()) + ")");

        sb.setLength(0);
        pw.print("Uptime: "); pw.print(formatDurationHMS(sb, now));
        pw.println();
        pw.print("Time spent syncing: ");

        sb.setLength(0);
        pw.print(formatDurationHMS(sb,
                mSyncHandler.mSyncTimeTracker.timeSpentSyncing()));
        pw.print(", sync ");
        pw.print(mSyncHandler.mSyncTimeTracker.mLastWasSyncing ? "" : "not ");
        pw.println("in progress");

        pw.println();
        pw.println("Active Syncs: " + mActiveSyncContexts.size());
        final PackageManager pm = mContext.getPackageManager();
        for (SyncManager.ActiveSyncContext activeSyncContext : mActiveSyncContexts) {
            final long durationInSeconds = (now - activeSyncContext.mStartTime);
            pw.print("  ");
            sb.setLength(0);
            pw.print(formatDurationHMS(sb, durationInSeconds));
            pw.print(" - ");
            pw.print(activeSyncContext.mSyncOperation.dump(pm, false, buckets, /*logSafe=*/ false));
            pw.println();
        }
        pw.println();

        dumpPendingSyncs(pw, buckets);
        dumpPeriodicSyncs(pw, buckets);

        // Join the installed sync adapter with the accounts list and emit for everything.
        pw.println("Sync Status");

        final ArrayList<Pair<EndPoint, SyncStatusInfo>> statuses = new ArrayList<>();

        mSyncStorageEngine.resetTodayStats(/* force=*/ false);

        for (AccountAndUser account : accounts) {
            final boolean unlocked;
            synchronized (mUnlockedUsers) {
                unlocked = mUnlockedUsers.get(account.userId);
            }
            pw.printf("Account %s u%d %s%s\n",
                    account.account.name, account.userId, account.account.type,
                    (unlocked ? "" : " (locked)"));

            pw.println("=======================================================================");
            final PrintTable table = new PrintTable(16);
            table.set(0, 0,
                    "Authority", // 0
                    "Syncable",  // 1
                    "Enabled",   // 2

                    "Stats",     // 3 "Total", "Today" or "Yesterday".

                    "Loc",       // 4 # of syncs with local sources. (including failures/cancels. )
                    "Poll",      // 5 "poll" syncs.
                    "Per",       // 6 Periodic syncs.
                    "Feed",      // 7 Syncs with a "feed" extra. (subscribedfeeds?)
                    "User",      // 8 User-initiated
                    "Othr",      // 9 Other sources.

                    "Tot",       // 10 Total syncs (including failures / cancels)
                    "Fail",      // 11 (Failure)
                    "Can",       // 12 (Cancel)

                    "Time",      // 13 Total time
                    "Last Sync", // 14
                    "Backoff"    // 15
            );

            final List<RegisteredServicesCache.ServiceInfo<SyncAdapterType>> sorted =
                    Lists.newArrayList();
            sorted.addAll(mSyncAdapters.getAllServices(account.userId));
            Collections.sort(sorted,
                    new Comparator<RegisteredServicesCache.ServiceInfo<SyncAdapterType>>() {
                        @Override
                        public int compare(RegisteredServicesCache.ServiceInfo<SyncAdapterType> lhs,
                                RegisteredServicesCache.ServiceInfo<SyncAdapterType> rhs) {
                            return lhs.type.authority.compareTo(rhs.type.authority);
                        }
                    });
            for (RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterType : sorted) {
                if (!syncAdapterType.type.accountType.equals(account.account.type)) {
                    continue;
                }
                int row = table.getNumRows();
                Pair<AuthorityInfo, SyncStatusInfo> syncAuthoritySyncStatus =
                        mSyncStorageEngine.getCopyOfAuthorityWithSyncStatus(
                                new SyncStorageEngine.EndPoint(
                                        account.account,
                                        syncAdapterType.type.authority,
                                        account.userId));
                SyncStorageEngine.AuthorityInfo settings = syncAuthoritySyncStatus.first;
                SyncStatusInfo status = syncAuthoritySyncStatus.second;
                statuses.add(Pair.create(settings.target, status));
                String authority = settings.target.provider;
                if (authority.length() > 50) {
                    authority = authority.substring(authority.length() - 50);
                }
                table.set(row, 0, authority, settings.syncable, settings.enabled);

                QuadConsumer<String, Stats, Function<Integer, String>, Integer> c =
                        (label, stats, filter, r) -> {
                    sb.setLength(0);
                    table.set(r, 3,
                            label,
                            filter.apply(stats.numSourceLocal),
                            filter.apply(stats.numSourcePoll),
                            filter.apply(stats.numSourcePeriodic),
                            filter.apply(stats.numSourceFeed),
                            filter.apply(stats.numSourceUser),
                            filter.apply(stats.numSourceOther),
                            filter.apply(stats.numSyncs),
                            filter.apply(stats.numFailures),
                            filter.apply(stats.numCancels),
                            formatDurationHMS(sb, stats.totalElapsedTime));
                };
                c.accept("Total", status.totalStats, (i) -> Integer.toString(i), row);
                c.accept("Today", status.todayStats, this::zeroToEmpty, row + 1);
                c.accept("Yestr", status.yesterdayStats, this::zeroToEmpty, row + 2);

                final int LAST_SYNC = 14;
                final int BACKOFF = LAST_SYNC + 1;

                int row1 = row;
                if (settings.delayUntil > now) {
                    table.set(row1++, BACKOFF, "D: " + (settings.delayUntil - now) / 1000);
                    if (settings.backoffTime > now) {
                        table.set(row1++, BACKOFF, "B: " + (settings.backoffTime - now) / 1000);
                        table.set(row1++, BACKOFF, settings.backoffDelay / 1000);
                    }
                }

                row1 = row;
                if (status.lastSuccessTime != 0) {
                    table.set(row1++, LAST_SYNC, SyncStorageEngine.SOURCES[status.lastSuccessSource]
                            + " " + "SUCCESS");
                    table.set(row1++, LAST_SYNC, formatTime(status.lastSuccessTime));
                }
                if (status.lastFailureTime != 0) {
                    table.set(row1++, LAST_SYNC, SyncStorageEngine.SOURCES[status.lastFailureSource]
                            + " " + "FAILURE");
                    table.set(row1++, LAST_SYNC, formatTime(status.lastFailureTime));
                    //noinspection UnusedAssignment
                    table.set(row1++, LAST_SYNC, status.lastFailureMesg);
                }
            }
            table.writeTo(pw);
        }

        dumpSyncHistory(pw);

        pw.println();
        pw.println("Per Adapter History");
        pw.println("(SERVER is now split up to FEED and OTHER)");

        for (int i = 0; i < statuses.size(); i++) {
            final Pair<EndPoint, SyncStatusInfo> event = statuses.get(i);

            pw.print("  ");
            pw.print(event.first.account.name);
            pw.print('/');
            pw.print(event.first.account.type);
            pw.print(" u");
            pw.print(event.first.userId);
            pw.print(" [");
            pw.print(event.first.provider);
            pw.print("]");
            pw.println();

            pw.println("    Per source last syncs:");
            for (int j = 0; j < SyncStorageEngine.SOURCES.length; j++) {
                pw.print("      ");
                pw.print(String.format("%8s", SyncStorageEngine.SOURCES[j]));
                pw.print("  Success: ");
                pw.print(formatTime(event.second.perSourceLastSuccessTimes[j]));

                pw.print("  Failure: ");
                pw.println(formatTime(event.second.perSourceLastFailureTimes[j]));
            }

            pw.println("    Last syncs:");
            for (int j = 0; j < event.second.getEventCount(); j++) {
                pw.print("      ");
                pw.print(formatTime(event.second.getEventTime(j)));
                pw.print(' ');
                pw.print(event.second.getEvent(j));
                pw.println();
            }
            if (event.second.getEventCount() == 0) {
                pw.println("      N/A");
            }
        }
    }

    private String zeroToEmpty(int value) {
        return (value != 0) ? Integer.toString(value) : "";
    }

    private void dumpTimeSec(PrintWriter pw, long time) {
        pw.print(time/1000); pw.print('.'); pw.print((time/100)%10);
        pw.print('s');
    }

    private void dumpDayStatistic(PrintWriter pw, SyncStorageEngine.DayStats ds) {
        pw.print("Success ("); pw.print(ds.successCount);
        if (ds.successCount > 0) {
            pw.print(" for "); dumpTimeSec(pw, ds.successTime);
            pw.print(" avg="); dumpTimeSec(pw, ds.successTime/ds.successCount);
        }
        pw.print(") Failure ("); pw.print(ds.failureCount);
        if (ds.failureCount > 0) {
            pw.print(" for "); dumpTimeSec(pw, ds.failureTime);
            pw.print(" avg="); dumpTimeSec(pw, ds.failureTime/ds.failureCount);
        }
        pw.println(")");
    }

    protected void dumpSyncHistory(PrintWriter pw) {
        dumpRecentHistory(pw);
        dumpDayStatistics(pw);
    }

    private void dumpRecentHistory(PrintWriter pw) {
        final ArrayList<SyncStorageEngine.SyncHistoryItem> items
                = mSyncStorageEngine.getSyncHistory();
        if (items != null && items.size() > 0) {
            final Map<String, AuthoritySyncStats> authorityMap = Maps.newHashMap();
            long totalElapsedTime = 0;
            long totalTimes = 0;
            final int N = items.size();

            int maxAuthority = 0;
            int maxAccount = 0;
            for (SyncStorageEngine.SyncHistoryItem item : items) {
                SyncStorageEngine.AuthorityInfo authorityInfo
                        = mSyncStorageEngine.getAuthority(item.authorityId);
                final String authorityName;
                final String accountKey;
                if (authorityInfo != null) {
                    authorityName = authorityInfo.target.provider;
                    accountKey = authorityInfo.target.account.name + "/"
                            + authorityInfo.target.account.type
                            + " u" + authorityInfo.target.userId;
                } else {
                    authorityName = "Unknown";
                    accountKey = "Unknown";
                }

                int length = authorityName.length();
                if (length > maxAuthority) {
                    maxAuthority = length;
                }
                length = accountKey.length();
                if (length > maxAccount) {
                    maxAccount = length;
                }

                final long elapsedTime = item.elapsedTime;
                totalElapsedTime += elapsedTime;
                totalTimes++;
                AuthoritySyncStats authoritySyncStats = authorityMap.get(authorityName);
                if (authoritySyncStats == null) {
                    authoritySyncStats = new AuthoritySyncStats(authorityName);
                    authorityMap.put(authorityName, authoritySyncStats);
                }
                authoritySyncStats.elapsedTime += elapsedTime;
                authoritySyncStats.times++;
                final Map<String, AccountSyncStats> accountMap = authoritySyncStats.accountMap;
                AccountSyncStats accountSyncStats = accountMap.get(accountKey);
                if (accountSyncStats == null) {
                    accountSyncStats = new AccountSyncStats(accountKey);
                    accountMap.put(accountKey, accountSyncStats);
                }
                accountSyncStats.elapsedTime += elapsedTime;
                accountSyncStats.times++;

            }

            if (totalElapsedTime > 0) {
                pw.println();
                pw.printf("Detailed Statistics (Recent history):  "
                                + "%d (# of times) %ds (sync time)\n",
                        totalTimes, totalElapsedTime / 1000);

                final List<AuthoritySyncStats> sortedAuthorities =
                        new ArrayList<AuthoritySyncStats>(authorityMap.values());
                Collections.sort(sortedAuthorities, new Comparator<AuthoritySyncStats>() {
                    @Override
                    public int compare(AuthoritySyncStats lhs, AuthoritySyncStats rhs) {
                        // reverse order
                        int compare = Integer.compare(rhs.times, lhs.times);
                        if (compare == 0) {
                            compare = Long.compare(rhs.elapsedTime, lhs.elapsedTime);
                        }
                        return compare;
                    }
                });

                final int maxLength = Math.max(maxAuthority, maxAccount + 3);
                final int padLength = 2 + 2 + maxLength + 2 + 10 + 11;
                final char chars[] = new char[padLength];
                Arrays.fill(chars, '-');
                final String separator = new String(chars);

                final String authorityFormat =
                        String.format("  %%-%ds: %%-9s  %%-11s\n", maxLength + 2);
                final String accountFormat =
                        String.format("    %%-%ds:   %%-9s  %%-11s\n", maxLength);

                pw.println(separator);
                for (AuthoritySyncStats authoritySyncStats : sortedAuthorities) {
                    String name = authoritySyncStats.name;
                    long elapsedTime;
                    int times;
                    String timeStr;
                    String timesStr;

                    elapsedTime = authoritySyncStats.elapsedTime;
                    times = authoritySyncStats.times;
                    timeStr = String.format("%ds/%d%%",
                            elapsedTime / 1000,
                            elapsedTime * 100 / totalElapsedTime);
                    timesStr = String.format("%d/%d%%",
                            times,
                            times * 100 / totalTimes);
                    pw.printf(authorityFormat, name, timesStr, timeStr);

                    final List<AccountSyncStats> sortedAccounts =
                            new ArrayList<AccountSyncStats>(
                                    authoritySyncStats.accountMap.values());
                    Collections.sort(sortedAccounts, new Comparator<AccountSyncStats>() {
                        @Override
                        public int compare(AccountSyncStats lhs, AccountSyncStats rhs) {
                            // reverse order
                            int compare = Integer.compare(rhs.times, lhs.times);
                            if (compare == 0) {
                                compare = Long.compare(rhs.elapsedTime, lhs.elapsedTime);
                            }
                            return compare;
                        }
                    });
                    for (AccountSyncStats stats: sortedAccounts) {
                        elapsedTime = stats.elapsedTime;
                        times = stats.times;
                        timeStr = String.format("%ds/%d%%",
                                elapsedTime / 1000,
                                elapsedTime * 100 / totalElapsedTime);
                        timesStr = String.format("%d/%d%%",
                                times,
                                times * 100 / totalTimes);
                        pw.printf(accountFormat, stats.name, timesStr, timeStr);
                    }
                    pw.println(separator);
                }
            }

            pw.println();
            pw.println("Recent Sync History");
            pw.println("(SERVER is now split up to FEED and OTHER)");
            final String format = "  %-" + maxAccount + "s  %-" + maxAuthority + "s %s\n";
            final Map<String, Long> lastTimeMap = Maps.newHashMap();
            final PackageManager pm = mContext.getPackageManager();
            for (int i = 0; i < N; i++) {
                SyncStorageEngine.SyncHistoryItem item = items.get(i);
                SyncStorageEngine.AuthorityInfo authorityInfo
                        = mSyncStorageEngine.getAuthority(item.authorityId);
                final String authorityName;
                final String accountKey;
                if (authorityInfo != null) {
                    authorityName = authorityInfo.target.provider;
                    accountKey = authorityInfo.target.account.name + "/"
                            + authorityInfo.target.account.type
                            + " u" + authorityInfo.target.userId;
                } else {
                    authorityName = "Unknown";
                    accountKey = "Unknown";
                }
                final long elapsedTime = item.elapsedTime;
                final long eventTime = item.eventTime;

                final String key = authorityName + "/" + accountKey;
                final Long lastEventTime = lastTimeMap.get(key);
                final String diffString;
                if (lastEventTime == null) {
                    diffString = "";
                } else {
                    final long diff = (lastEventTime - eventTime) / 1000;
                    if (diff < 60) {
                        diffString = String.valueOf(diff);
                    } else if (diff < 3600) {
                        diffString = String.format("%02d:%02d", diff / 60, diff % 60);
                    } else {
                        final long sec = diff % 3600;
                        diffString = String.format("%02d:%02d:%02d",
                                diff / 3600, sec / 60, sec % 60);
                    }
                }
                lastTimeMap.put(key, eventTime);

                pw.printf("  #%-3d: %s %8s  %5.1fs  %8s",
                        i + 1,
                        formatTime(eventTime),
                        SyncStorageEngine.SOURCES[item.source],
                        ((float) elapsedTime) / 1000,
                        diffString);
                pw.printf(format, accountKey, authorityName,
                        SyncOperation.reasonToString(pm, item.reason));

                if (item.event != SyncStorageEngine.EVENT_STOP
                        || item.upstreamActivity != 0
                        || item.downstreamActivity != 0) {
                    pw.printf("    event=%d upstreamActivity=%d downstreamActivity=%d\n",
                            item.event,
                            item.upstreamActivity,
                            item.downstreamActivity);
                }
                if (item.mesg != null
                        && !SyncStorageEngine.MESG_SUCCESS.equals(item.mesg)) {
                    pw.printf("    mesg=%s\n", item.mesg);
                }
            }
            pw.println();
            pw.println("Recent Sync History Extras");
            pw.println("(SERVER is now split up to FEED and OTHER)");
            for (int i = 0; i < N; i++) {
                final SyncStorageEngine.SyncHistoryItem item = items.get(i);
                final Bundle extras = item.extras;
                if (extras == null || extras.size() == 0) {
                    continue;
                }
                final SyncStorageEngine.AuthorityInfo authorityInfo
                        = mSyncStorageEngine.getAuthority(item.authorityId);
                final String authorityName;
                final String accountKey;
                if (authorityInfo != null) {
                    authorityName = authorityInfo.target.provider;
                    accountKey = authorityInfo.target.account.name + "/"
                            + authorityInfo.target.account.type
                            + " u" + authorityInfo.target.userId;
                } else {
                    authorityName = "Unknown";
                    accountKey = "Unknown";
                }
                final long eventTime = item.eventTime;

                pw.printf("  #%-3d: %s %8s ",
                        i + 1,
                        formatTime(eventTime),
                        SyncStorageEngine.SOURCES[item.source]);

                pw.printf(format, accountKey, authorityName, extras);
            }
        }
    }

    private void dumpDayStatistics(PrintWriter pw) {
        SyncStorageEngine.DayStats dses[] = mSyncStorageEngine.getDayStatistics();
        if (dses != null && dses[0] != null) {
            pw.println();
            pw.println("Sync Statistics");
            pw.print("  Today:  "); dumpDayStatistic(pw, dses[0]);
            int today = dses[0].day;
            int i;
            SyncStorageEngine.DayStats ds;

            // Print each day in the current week.
            for (i=1; i<=6 && i < dses.length; i++) {
                ds = dses[i];
                if (ds == null) break;
                int delta = today-ds.day;
                if (delta > 6) break;

                pw.print("  Day-"); pw.print(delta); pw.print(":  ");
                dumpDayStatistic(pw, ds);
            }

            // Aggregate all following days into weeks and print totals.
            int weekDay = today;
            while (i < dses.length) {
                SyncStorageEngine.DayStats aggr = null;
                weekDay -= 7;
                while (i < dses.length) {
                    ds = dses[i];
                    if (ds == null) {
                        i = dses.length;
                        break;
                    }
                    int delta = weekDay-ds.day;
                    if (delta > 6) break;
                    i++;

                    if (aggr == null) {
                        aggr = new SyncStorageEngine.DayStats(weekDay);
                    }
                    aggr.successCount += ds.successCount;
                    aggr.successTime += ds.successTime;
                    aggr.failureCount += ds.failureCount;
                    aggr.failureTime += ds.failureTime;
                }
                if (aggr != null) {
                    pw.print("  Week-"); pw.print((today-weekDay)/7); pw.print(": ");
                    dumpDayStatistic(pw, aggr);
                }
            }
        }
    }

    private void dumpSyncAdapters(IndentingPrintWriter pw) {
        pw.println();
        final List<UserInfo> users = getAllUsers();
        if (users != null) {
            for (UserInfo user : users) {
                pw.println("Sync adapters for " + user + ":");
                pw.increaseIndent();
                for (RegisteredServicesCache.ServiceInfo<?> info :
                        mSyncAdapters.getAllServices(user.id)) {
                    pw.println(info);
                }
                pw.decreaseIndent();
                pw.println();
            }
        }
    }

    private static class AuthoritySyncStats {
        String name;
        long elapsedTime;
        int times;
        Map<String, AccountSyncStats> accountMap = Maps.newHashMap();

        private AuthoritySyncStats(String name) {
            this.name = name;
        }
    }

    private static class AccountSyncStats {
        String name;
        long elapsedTime;
        int times;

        private AccountSyncStats(String name) {
            this.name = name;
        }
    }

    interface OnReadyCallback {
        void onReady();
    }

    static void sendOnUnsyncableAccount(@NonNull Context context,
            @NonNull RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo,
            @UserIdInt int userId, @NonNull OnReadyCallback onReadyCallback) {
        OnUnsyncableAccountCheck connection = new OnUnsyncableAccountCheck(syncAdapterInfo,
                onReadyCallback);

        boolean isBound = context.bindServiceAsUser(
                getAdapterBindIntent(context, syncAdapterInfo.componentName, userId),
                connection, SYNC_ADAPTER_CONNECTION_FLAGS, UserHandle.of(userId));

        if (isBound) {
            // Unbind after SERVICE_BOUND_TIME_MILLIS to not leak the connection.
            (new Handler(Looper.getMainLooper())).postDelayed(
                    () -> context.unbindService(connection),
                    OnUnsyncableAccountCheck.SERVICE_BOUND_TIME_MILLIS);
        } else {
                /*
                 * The default implementation of adapter.onUnsyncableAccount returns true. Hence if
                 * there the service cannot be bound, assume the default behavior.
                 */
            connection.onReady();
        }
    }


    /**
     * Helper class for calling ISyncAdapter.onUnsyncableAccountDone.
     *
     * If this returns {@code true} the onReadyCallback is called. Otherwise nothing happens.
     */
    private static class OnUnsyncableAccountCheck implements ServiceConnection {
        static final long SERVICE_BOUND_TIME_MILLIS = 5000;

        private final @NonNull OnReadyCallback mOnReadyCallback;
        private final @NonNull RegisteredServicesCache.ServiceInfo<SyncAdapterType>
                mSyncAdapterInfo;

        OnUnsyncableAccountCheck(
                @NonNull RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo,
                @NonNull OnReadyCallback onReadyCallback) {
            mSyncAdapterInfo = syncAdapterInfo;
            mOnReadyCallback = onReadyCallback;
        }

        private void onReady() {
            final long identity = Binder.clearCallingIdentity();
            try {
                mOnReadyCallback.onReady();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            final ISyncAdapter adapter = ISyncAdapter.Stub.asInterface(service);

            try {
                adapter.onUnsyncableAccount(new ISyncAdapterUnsyncableAccountCallback.Stub() {
                    @Override
                    public void onUnsyncableAccountDone(boolean isReady) {
                        if (isReady) {
                            onReady();
                        }
                    }
                });
            } catch (RemoteException e) {
                Slog.e(TAG, "Could not call onUnsyncableAccountDone " + mSyncAdapterInfo, e);
                /*
                 * The default implementation of adapter.onUnsyncableAccount returns true. Hence if
                 * there is a crash in the implementation, assume the default behavior.
                 */
                onReady();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Wait until the service connects again
        }
    }

    /**
     * A helper object to keep track of the time we have spent syncing since the last boot
     */
    private class SyncTimeTracker {
        /** True if a sync was in progress on the most recent call to update() */
        boolean mLastWasSyncing = false;
        /** Used to track when lastWasSyncing was last set */
        long mWhenSyncStarted = 0;
        /** The cumulative time we have spent syncing */
        private long mTimeSpentSyncing;

        /** Call to let the tracker know that the sync state may have changed */
        public synchronized void update() {
            final boolean isSyncInProgress = !mActiveSyncContexts.isEmpty();
            if (isSyncInProgress == mLastWasSyncing) return;
            final long now = SystemClock.elapsedRealtime();
            if (isSyncInProgress) {
                mWhenSyncStarted = now;
            } else {
                mTimeSpentSyncing += now - mWhenSyncStarted;
            }
            mLastWasSyncing = isSyncInProgress;
        }

        /** Get how long we have been syncing, in ms */
        public synchronized long timeSpentSyncing() {
            if (!mLastWasSyncing) return mTimeSpentSyncing;

            final long now = SystemClock.elapsedRealtime();
            return mTimeSpentSyncing + (now - mWhenSyncStarted);
        }
    }

    class ServiceConnectionData {
        public final ActiveSyncContext activeSyncContext;
        public final IBinder adapter;

        ServiceConnectionData(ActiveSyncContext activeSyncContext, IBinder adapter) {
            this.activeSyncContext = activeSyncContext;
            this.adapter = adapter;
        }
    }

    @Nullable
    private static SyncManager getInstance() {
        synchronized (SyncManager.class) {
            if (sInstance == null) {
                Slog.wtf(TAG, "sInstance == null"); // Maybe called too early?
            }
            return sInstance;
        }
    }

    /**
     * @return whether the device is ready to run sync jobs for a given user.
     */
    public static boolean readyToSync(int userId) {
        final SyncManager instance = getInstance();
        return (instance != null) && SyncJobService.isReady()
                && instance.mProvisioned && instance.isUserUnlocked(userId);
    }

    public static void sendMessage(Message message) {
        final SyncManager instance = getInstance();
        if (instance != null && instance.mSyncHandler != null) {
            instance.mSyncHandler.sendMessage(message);
        }
    }

    /**
     * Handles SyncOperation Messages that are posted to the associated
     * HandlerThread.
     */
    class SyncHandler extends Handler {
        // Messages that can be sent on mHandler.
        private static final int MESSAGE_SYNC_FINISHED = 1;
        private static final int MESSAGE_SERVICE_CONNECTED = 4;
        private static final int MESSAGE_SERVICE_DISCONNECTED = 5;
        private static final int MESSAGE_CANCEL = 6;
        static final int MESSAGE_START_SYNC = 10;
        static final int MESSAGE_STOP_SYNC = 11;
        static final int MESSAGE_SCHEDULE_SYNC = 12;
        static final int MESSAGE_UPDATE_PERIODIC_SYNC = 13;
        static final int MESSAGE_REMOVE_PERIODIC_SYNC = 14;

        /**
         * Posted periodically to monitor network process for long-running syncs.
         * obj: {@link com.android.server.content.SyncManager.ActiveSyncContext}
         */
        private static final int MESSAGE_MONITOR_SYNC = 8;
        private static final int MESSAGE_ACCOUNTS_UPDATED = 9;

        public final SyncTimeTracker mSyncTimeTracker = new SyncTimeTracker();
        private final HashMap<String, PowerManager.WakeLock> mWakeLocks = Maps.newHashMap();

        public SyncHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            // TODO Do we really need this wake lock?? If we actually needed it, this is probably
            // not the best place to acquire the lock -- it's probably too late, because the device
            // could have gone to sleep before we reach here.
            mSyncManagerWakeLock.acquire();
            try {
                handleSyncMessage(msg);
            } finally {
                mSyncManagerWakeLock.release();
            }
        }

        private void handleSyncMessage(Message msg) {
            final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);

            try {
                mDataConnectionIsConnected = readDataConnectionState();
                switch (msg.what) {
                    case MESSAGE_ACCOUNTS_UPDATED:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Slog.v(TAG, "handleSyncHandlerMessage: MESSAGE_ACCOUNTS_UPDATED");
                        }
                        EndPoint targets = (EndPoint) msg.obj;
                        updateRunningAccountsH(targets);
                        break;
                    case MESSAGE_SCHEDULE_SYNC:
                        ScheduleSyncMessagePayload syncPayload =
                                (ScheduleSyncMessagePayload) msg.obj;
                        SyncOperation op = syncPayload.syncOperation;
                        scheduleSyncOperationH(op, syncPayload.minDelayMillis);
                        break;

                    case MESSAGE_START_SYNC:
                        op = (SyncOperation) msg.obj;
                        startSyncH(op);
                        break;

                    case MESSAGE_STOP_SYNC:
                        op = (SyncOperation) msg.obj;
                        if (isLoggable) {
                            Slog.v(TAG, "Stop sync received.");
                        }
                        ActiveSyncContext asc = findActiveSyncContextH(op.jobId);
                        if (asc != null) {
                            runSyncFinishedOrCanceledH(null /* no result */, asc);
                            boolean reschedule = msg.arg1 != 0;
                            boolean applyBackoff = msg.arg2 != 0;
                            if (isLoggable) {
                                Slog.v(TAG, "Stopping sync. Reschedule: " + reschedule
                                        + "Backoff: " + applyBackoff);
                            }
                            if (applyBackoff) {
                                increaseBackoffSetting(op.target);
                            }
                            if (reschedule) {
                                deferStoppedSyncH(op, 0);
                            }
                        }
                        break;

                    case MESSAGE_UPDATE_PERIODIC_SYNC:
                        UpdatePeriodicSyncMessagePayload data =
                                (UpdatePeriodicSyncMessagePayload) msg.obj;
                        updateOrAddPeriodicSyncH(data.target, data.pollFrequency,
                                data.flex, data.extras);
                        break;
                    case MESSAGE_REMOVE_PERIODIC_SYNC:
                        Pair<EndPoint, String> args = (Pair<EndPoint, String>) (msg.obj);
                        removePeriodicSyncH(args.first, msg.getData(), args.second);
                        break;

                    case SyncHandler.MESSAGE_CANCEL:
                        SyncStorageEngine.EndPoint endpoint = (SyncStorageEngine.EndPoint) msg.obj;
                        Bundle extras = msg.peekData();
                        if (isLoggable) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_CANCEL: "
                                    + endpoint + " bundle: " + extras);
                        }
                        cancelActiveSyncH(endpoint, extras, "MESSAGE_CANCEL");
                        break;

                    case SyncHandler.MESSAGE_SYNC_FINISHED:
                        SyncFinishedOrCancelledMessagePayload payload =
                                (SyncFinishedOrCancelledMessagePayload) msg.obj;
                        if (!isSyncStillActiveH(payload.activeSyncContext)) {
                            if (isLoggable) {
                                Log.d(TAG, "handleSyncHandlerMessage: dropping since the "
                                        + "sync is no longer active: "
                                        + payload.activeSyncContext);
                            }
                            break;
                        }
                        if (isLoggable) {
                            Slog.v(TAG, "syncFinished" + payload.activeSyncContext.mSyncOperation);
                        }
                        SyncJobService.callJobFinished(
                                payload.activeSyncContext.mSyncOperation.jobId, false,
                                "sync finished");
                        runSyncFinishedOrCanceledH(payload.syncResult,
                                payload.activeSyncContext);
                        break;

                    case SyncHandler.MESSAGE_SERVICE_CONNECTED: {
                        ServiceConnectionData msgData = (ServiceConnectionData) msg.obj;
                        if (isLoggable) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_SERVICE_CONNECTED: "
                                    + msgData.activeSyncContext);
                        }
                        // Check that this isn't an old message.
                        if (isSyncStillActiveH(msgData.activeSyncContext)) {
                            runBoundToAdapterH(
                                    msgData.activeSyncContext,
                                    msgData.adapter);
                        }
                        break;
                    }

                    case SyncHandler.MESSAGE_SERVICE_DISCONNECTED: {
                        final ActiveSyncContext currentSyncContext =
                                ((ServiceConnectionData) msg.obj).activeSyncContext;
                        if (isLoggable) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_SERVICE_DISCONNECTED: "
                                    + currentSyncContext);
                        }
                        // Check that this isn't an old message.
                        if (isSyncStillActiveH(currentSyncContext)) {
                            // cancel the sync if we have a syncadapter, which means one is
                            // outstanding
                            try {
                                if (currentSyncContext.mSyncAdapter != null) {
                                    mLogger.log("Calling cancelSync for SERVICE_DISCONNECTED ",
                                            currentSyncContext,
                                            " adapter=", currentSyncContext.mSyncAdapter);
                                    currentSyncContext.mSyncAdapter.cancelSync(currentSyncContext);
                                    mLogger.log("Canceled");
                                }
                            } catch (RemoteException e) {
                                mLogger.log("RemoteException ", Log.getStackTraceString(e));
                                // We don't need to retry this in this case.
                            }

                            // Pretend that the sync failed with an IOException,
                            // which is a soft error.
                            SyncResult syncResult = new SyncResult();
                            syncResult.stats.numIoExceptions++;
                            SyncJobService.callJobFinished(
                                    currentSyncContext.mSyncOperation.jobId, false,
                                    "service disconnected");
                            runSyncFinishedOrCanceledH(syncResult, currentSyncContext);
                        }
                        break;
                    }

                    case SyncHandler.MESSAGE_MONITOR_SYNC:
                        ActiveSyncContext monitoredSyncContext = (ActiveSyncContext) msg.obj;
                        if (isLoggable) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_MONITOR_SYNC: " +
                                    monitoredSyncContext.mSyncOperation.target);
                        }

                        if (isSyncNotUsingNetworkH(monitoredSyncContext)) {
                            Log.w(TAG, String.format(
                                    "Detected sync making no progress for %s. cancelling.",
                                    logSafe(monitoredSyncContext)));
                            SyncJobService.callJobFinished(
                                    monitoredSyncContext.mSyncOperation.jobId, false,
                                    "no network activity");
                            runSyncFinishedOrCanceledH(
                                    null /* cancel => no result */, monitoredSyncContext);
                        } else {
                            // Repost message to check again.
                            postMonitorSyncProgressMessage(monitoredSyncContext);
                        }
                        break;

                }
            } finally {
                mSyncTimeTracker.update();
            }
        }

        private PowerManager.WakeLock getSyncWakeLock(SyncOperation operation) {
            final String wakeLockKey = operation.wakeLockName();
            PowerManager.WakeLock wakeLock = mWakeLocks.get(wakeLockKey);
            if (wakeLock == null) {
                final String name = SYNC_WAKE_LOCK_PREFIX + wakeLockKey;
                wakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, name);
                wakeLock.setReferenceCounted(false);
                mWakeLocks.put(wakeLockKey, wakeLock);
            }
            return wakeLock;
        }

        /**
         * Defer the specified SyncOperation by rescheduling it on the JobScheduler with some
         * delay. This is equivalent to a failure. If this is a periodic sync, a delayed one-off
         * sync will be scheduled.
         */
        private void deferSyncH(SyncOperation op, long delay, String why) {
            mLogger.log("deferSyncH() ", (op.isPeriodic ? "periodic " : ""),
                    "sync.  op=", op, " delay=", delay, " why=", why);
            SyncJobService.callJobFinished(op.jobId, false, why);
            if (op.isPeriodic) {
                scheduleSyncOperationH(op.createOneTimeSyncOperation(), delay);
            } else {
                // mSyncJobService.callJobFinished is async, so cancel the job to ensure we don't
                // find this job in the pending jobs list while looking for duplicates
                // before scheduling it at a later time.
                cancelJob(op, "deferSyncH()");
                scheduleSyncOperationH(op, delay);
            }
        }

        /* Same as deferSyncH, but assumes that job is no longer running on JobScheduler. */
        private void deferStoppedSyncH(SyncOperation op, long delay) {
            if (op.isPeriodic) {
                scheduleSyncOperationH(op.createOneTimeSyncOperation(), delay);
            } else {
                scheduleSyncOperationH(op, delay);
            }
        }

        /**
         * Cancel an active sync and reschedule it on the JobScheduler with some delay.
         */
        private void deferActiveSyncH(ActiveSyncContext asc, String why) {
            SyncOperation op = asc.mSyncOperation;
            runSyncFinishedOrCanceledH(null, asc);
            deferSyncH(op, SYNC_DELAY_ON_CONFLICT, why);
        }

        private void startSyncH(SyncOperation op) {
            final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
            if (isLoggable) Slog.v(TAG, op.toString());

            // At this point, we know the device has been connected to the server, so
            // assume the clock is correct.
            mSyncStorageEngine.setClockValid();

            SyncJobService.markSyncStarted(op.jobId);

            if (op.isPeriodic) {
                // Don't allow this periodic to run if a previous instance failed and is currently
                // scheduled according to some backoff criteria.
                List<SyncOperation> ops = getAllPendingSyncs();
                for (SyncOperation syncOperation: ops) {
                    if (syncOperation.sourcePeriodicId == op.jobId) {
                        SyncJobService.callJobFinished(op.jobId, false,
                                "periodic sync, pending");
                        return;
                    }
                }
                // Don't allow this periodic to run if a previous instance failed and is currently
                // executing according to some backoff criteria.
                for (ActiveSyncContext asc: mActiveSyncContexts) {
                    if (asc.mSyncOperation.sourcePeriodicId == op.jobId) {
                        SyncJobService.callJobFinished(op.jobId, false,
                                "periodic sync, already running");
                        return;
                    }
                }
                // Check for adapter delays.
                if (isAdapterDelayed(op.target)) {
                    deferSyncH(op, 0 /* No minimum delay */, "backing off");
                    return;
                }
            }

            // Check for conflicting syncs.
            for (ActiveSyncContext asc: mActiveSyncContexts) {
                if (asc.mSyncOperation.isConflict(op)) {
                    // If the provided SyncOperation conflicts with a running one, the lower
                    // priority sync is pre-empted.
                    if (asc.mSyncOperation.getJobBias() >= op.getJobBias()) {
                        if (isLoggable) {
                            Slog.v(TAG, "Rescheduling sync due to conflict " + op.toString());
                        }
                        deferSyncH(op, SYNC_DELAY_ON_CONFLICT, "delay on conflict");
                        return;
                    } else {
                        if (isLoggable) {
                            Slog.v(TAG, "Pushing back running sync due to a higher priority sync");
                        }
                        deferActiveSyncH(asc, "preempted");
                        break;
                    }
                }
            }

            final int syncOpState = computeSyncOpState(op);
            if (syncOpState != SYNC_OP_STATE_VALID) {
                SyncJobService.callJobFinished(op.jobId, false,
                        "invalid op state: " + syncOpState);
                return;
            }

            if (!dispatchSyncOperation(op)) {
                SyncJobService.callJobFinished(op.jobId, false, "dispatchSyncOperation() failed");
            }

            setAuthorityPendingState(op.target);
        }

        private ActiveSyncContext findActiveSyncContextH(int jobId) {
            for (ActiveSyncContext asc: mActiveSyncContexts) {
                SyncOperation op = asc.mSyncOperation;
                if (op != null && op.jobId == jobId) {
                    return asc;
                }
            }
            return null;
        }

        private void updateRunningAccountsH(EndPoint syncTargets) {
            synchronized (mAccountsLock) {
                AccountAndUser[] oldAccounts = mRunningAccounts;
                mRunningAccounts =
                        AccountManagerService.getSingleton().getRunningAccountsForSystem();
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.v(TAG, "Accounts list: ");
                    for (AccountAndUser acc : mRunningAccounts) {
                        Slog.v(TAG, acc.toString());
                    }
                }
                if (mLogger.enabled()) {
                    mLogger.log("updateRunningAccountsH: ", Arrays.toString(mRunningAccounts));
                }
                removeStaleAccounts();

                AccountAndUser[] accounts = mRunningAccounts;
                for (int i = 0, size = mActiveSyncContexts.size(); i < size; i++) {
                    ActiveSyncContext currentSyncContext = mActiveSyncContexts.get(i);
                    if (!containsAccountAndUser(accounts,
                            currentSyncContext.mSyncOperation.target.account,
                            currentSyncContext.mSyncOperation.target.userId)) {
                        Log.d(TAG, "canceling sync since the account is no longer running");
                        sendSyncFinishedOrCanceledMessage(currentSyncContext,
                                null /* no result since this is a cancel */);
                    }
                }

                if (syncTargets != null) {
                    // On account add, check if there are any settings to be restored.
                    for (int i = 0, length = mRunningAccounts.length; i < length; i++) {
                        AccountAndUser aau = mRunningAccounts[i];
                        if (!containsAccountAndUser(oldAccounts, aau.account, aau.userId)) {
                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                Log.d(TAG, "Account " + aau.account
                                        + " added, checking sync restore data");
                            }
                            AccountSyncSettingsBackupHelper.accountAdded(mContext,
                                    syncTargets.userId);
                            break;
                        }
                    }
                }
            }

            // Cancel all jobs from non-existent accounts.
            AccountAndUser[] allAccounts =
                    AccountManagerService.getSingleton().getAllAccountsForSystemProcess();
            List<SyncOperation> ops = getAllPendingSyncs();
            for (int i = 0, opsSize = ops.size(); i < opsSize; i++) {
                SyncOperation op = ops.get(i);
                if (!containsAccountAndUser(allAccounts, op.target.account, op.target.userId)) {
                    mLogger.log("canceling: ", op);
                    cancelJob(op, "updateRunningAccountsH()");
                }
            }

            if (syncTargets != null) {
                scheduleSync(syncTargets.account, syncTargets.userId,
                        SyncOperation.REASON_ACCOUNTS_UPDATED, syncTargets.provider,
                        null, AuthorityInfo.NOT_INITIALIZED,
                        ContentResolver.SYNC_EXEMPTION_NONE, Process.myUid(), -4, null);
            }
        }

        /**
         * The given SyncOperation will be removed and a new one scheduled in its place if
         * an updated period or flex is specified.
         * @param syncOperation SyncOperation whose period and flex is to be updated.
         * @param pollFrequencyMillis new period in milliseconds.
         * @param flexMillis new flex time in milliseconds.
         */
        private void maybeUpdateSyncPeriodH(SyncOperation syncOperation, long pollFrequencyMillis,
                long flexMillis) {
            if (!(pollFrequencyMillis == syncOperation.periodMillis
                    && flexMillis == syncOperation.flexMillis)) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.v(TAG, "updating period " + syncOperation + " to " + pollFrequencyMillis
                            + " and flex to " + flexMillis);
                }
                SyncOperation newOp = new SyncOperation(syncOperation, pollFrequencyMillis,
                        flexMillis);
                newOp.jobId = syncOperation.jobId;
                scheduleSyncOperationH(newOp);
            }
        }

        private void updateOrAddPeriodicSyncH(EndPoint target, long pollFrequency, long flex,
                Bundle extras) {
            final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
            verifyJobScheduler();  // Will fill in mScheduledSyncs cache if it is not already filled.
            final long pollFrequencyMillis = pollFrequency * 1000L;
            final long flexMillis = flex * 1000L;
            if (isLoggable) {
                Slog.v(TAG, "Addition to periodic syncs requested: " + target
                        + " period: " + pollFrequency
                        + " flexMillis: " + flex
                        + " extras: " + extras.toString());
            }
            List<SyncOperation> ops = getAllPendingSyncs();
            for (SyncOperation op: ops) {
                if (op.isPeriodic && op.target.matchesSpec(target)
                        && op.areExtrasEqual(extras, /*includeSyncSettings=*/ true)) {
                    if (isPackageStopped(op.owningPackage, target.userId)) {
                        continue; // skip stopped package
                    }
                    maybeUpdateSyncPeriodH(op, pollFrequencyMillis, flexMillis);
                    return;
                }
            }

            if (isLoggable) {
                Slog.v(TAG, "Adding new periodic sync: " + target
                        + " period: " + pollFrequency
                        + " flexMillis: " + flex
                        + " extras: " + extras.toString());
            }

            final RegisteredServicesCache.ServiceInfo<SyncAdapterType>
                    syncAdapterInfo = mSyncAdapters.getServiceInfo(
                    SyncAdapterType.newKey(
                            target.provider, target.account.type),
                    target.userId);
            if (syncAdapterInfo == null) {
                return;
            }

            SyncOperation op = new SyncOperation(target, syncAdapterInfo.uid,
                    syncAdapterInfo.componentName.getPackageName(), SyncOperation.REASON_PERIODIC,
                    SyncStorageEngine.SOURCE_PERIODIC, extras,
                    syncAdapterInfo.type.allowParallelSyncs(), true, SyncOperation.NO_JOB_ID,
                    pollFrequencyMillis, flexMillis, ContentResolver.SYNC_EXEMPTION_NONE);

            final int syncOpState = computeSyncOpState(op);
            if (syncOpState == SYNC_OP_STATE_INVALID_NO_ACCOUNT_ACCESS) {
                String packageName = op.owningPackage;
                final int userId = UserHandle.getUserId(op.owningUid);
                // If the app did not run and has no account access, done
                if (!wasPackageEverLaunched(packageName, userId)) {
                    return;
                }
                mLogger.log("requestAccountAccess for SYNC_OP_STATE_INVALID_NO_ACCOUNT_ACCESS");
                mAccountManagerInternal.requestAccountAccess(op.target.account,
                        packageName, userId, new RemoteCallback((Bundle result) -> {
                            if (result != null
                                    && result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT)) {
                                updateOrAddPeriodicSync(target, pollFrequency, flex, extras);
                            }
                        }
                        ));
                return;
            }
            if (syncOpState != SYNC_OP_STATE_VALID) {
                mLogger.log("syncOpState=", syncOpState);
                return;
            }

            scheduleSyncOperationH(op);
            mSyncStorageEngine.reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS,
                    op.owningPackage, target.userId);
        }

        /**
         * Remove this periodic sync operation and all one-off operations initiated by it.
         */
        private void removePeriodicSyncInternalH(SyncOperation syncOperation, String why) {
            // Remove this periodic sync and all one-off syncs initiated by it.
            List<SyncOperation> ops = getAllPendingSyncs();
            for (SyncOperation op: ops) {
                if (op.sourcePeriodicId == syncOperation.jobId || op.jobId == syncOperation.jobId) {
                    ActiveSyncContext asc = findActiveSyncContextH(syncOperation.jobId);
                    if (asc != null) {
                        SyncJobService.callJobFinished(syncOperation.jobId, false,
                                "removePeriodicSyncInternalH");
                        runSyncFinishedOrCanceledH(null, asc);
                    }
                    mLogger.log("removePeriodicSyncInternalH-canceling: ", op);
                    cancelJob(op, why);
                }
            }
        }

        private void removePeriodicSyncH(EndPoint target, Bundle extras, String why) {
            verifyJobScheduler();
            List<SyncOperation> ops = getAllPendingSyncs();
            for (SyncOperation op: ops) {
                if (op.isPeriodic && op.target.matchesSpec(target)
                        && op.areExtrasEqual(extras, /*includeSyncSettings=*/ true)) {
                    removePeriodicSyncInternalH(op, why);
                }
            }
        }

        private boolean isSyncNotUsingNetworkH(ActiveSyncContext activeSyncContext) {
            final long bytesTransferredCurrent =
                    getTotalBytesTransferredByUid(activeSyncContext.mSyncAdapterUid);
            final long deltaBytesTransferred =
                    bytesTransferredCurrent - activeSyncContext.mBytesTransferredAtLastPoll;

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                // Bytes transferred
                long remainder = deltaBytesTransferred;
                long mb = remainder / (1024 * 1024);
                remainder %= 1024 * 1024;
                long kb = remainder / 1024;
                remainder %= 1024;
                long b = remainder;
                Log.d(TAG, String.format(
                        "Time since last update: %ds. Delta transferred: %dMBs,%dKBs,%dBs",
                        (SystemClock.elapsedRealtime()
                                - activeSyncContext.mLastPolledTimeElapsed)/1000,
                        mb, kb, b)
                );
            }
            return (deltaBytesTransferred <= SYNC_MONITOR_PROGRESS_THRESHOLD_BYTES);
        }

        /**
         * Determine if a sync is no longer valid and should be dropped.
         */
        private int computeSyncOpState(SyncOperation op) {
            final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
            int state;
            final EndPoint target = op.target;

            // Drop the sync if the account of this operation no longer exists.
            synchronized (mAccountsLock) {
                AccountAndUser[] accounts = mRunningAccounts;
                if (!containsAccountAndUser(accounts, target.account, target.userId)) {
                    if (isLoggable) {
                        Slog.v(TAG, "    Dropping sync operation: account doesn't exist.");
                    }
                    logAccountError("SYNC_OP_STATE_INVALID: account doesn't exist.");
                    return SYNC_OP_STATE_INVALID_NO_ACCOUNT;
                }
            }
            // Drop this sync request if it isn't syncable.
            state = computeSyncable(target.account, target.userId, target.provider, true,
                    /*checkStoppedState=*/ true);
            if (state == AuthorityInfo.SYNCABLE_NO_ACCOUNT_ACCESS) {
                if (isLoggable) {
                    Slog.v(TAG, "    Dropping sync operation: "
                            + "isSyncable == SYNCABLE_NO_ACCOUNT_ACCESS");
                }
                logAccountError("SYNC_OP_STATE_INVALID_NO_ACCOUNT_ACCESS");
                return SYNC_OP_STATE_INVALID_NO_ACCOUNT_ACCESS;
            }
            if (state == AuthorityInfo.NOT_SYNCABLE) {
                if (isLoggable) {
                    Slog.v(TAG, "    Dropping sync operation: isSyncable == NOT_SYNCABLE");
                }
                logAccountError("SYNC_OP_STATE_INVALID: NOT_SYNCABLE");
                return SYNC_OP_STATE_INVALID_NOT_SYNCABLE;
            }

            final boolean syncEnabled = mSyncStorageEngine.getMasterSyncAutomatically(target.userId)
                    && mSyncStorageEngine.getSyncAutomatically(target.account,
                            target.userId, target.provider);

            // We ignore system settings that specify the sync is invalid if:
            // 1) It's manual - we try it anyway. When/if it fails it will be rescheduled.
            //      or
            // 2) it's an initialisation sync - we just need to connect to it.
            final boolean ignoreSystemConfiguration = op.isIgnoreSettings() || (state < 0);

            // Sync not enabled.
            if (!syncEnabled && !ignoreSystemConfiguration) {
                if (isLoggable) {
                    Slog.v(TAG, "    Dropping sync operation: disallowed by settings/network.");
                }
                logAccountError("SYNC_OP_STATE_INVALID: disallowed by settings/network");
                return SYNC_OP_STATE_INVALID_SYNC_DISABLED;
            }
            return SYNC_OP_STATE_VALID;
        }

        private void logAccountError(String message) {
            if (USE_WTF_FOR_ACCOUNT_ERROR) {
                Slog.wtf(TAG, message);
            } else {
                Slog.e(TAG, message);
            }
        }

        private boolean dispatchSyncOperation(SyncOperation op) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "dispatchSyncOperation: we are going to sync " + op);
                Slog.v(TAG, "num active syncs: " + mActiveSyncContexts.size());
                for (ActiveSyncContext syncContext : mActiveSyncContexts) {
                    Slog.v(TAG, syncContext.toString());
                }
            }
            if (op.isAppStandbyExempted()) {
                final UsageStatsManagerInternal usmi = LocalServices.getService(
                        UsageStatsManagerInternal.class);
                if (usmi != null) {
                    usmi.reportExemptedSyncStart(op.owningPackage,
                            UserHandle.getUserId(op.owningUid));
                }
            }

            // Connect to the sync adapter.
            int targetUid;
            ComponentName targetComponent;
            final SyncStorageEngine.EndPoint info = op.target;
            SyncAdapterType syncAdapterType =
                    SyncAdapterType.newKey(info.provider, info.account.type);
            final RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo;
            syncAdapterInfo = mSyncAdapters.getServiceInfo(syncAdapterType, info.userId);
            if (syncAdapterInfo == null) {
                mLogger.log("dispatchSyncOperation() failed: no sync adapter info for ",
                        syncAdapterType);
                Log.d(TAG, "can't find a sync adapter for " + syncAdapterType
                        + ", removing settings for it");
                mSyncStorageEngine.removeAuthority(info);
                return false;
            }
            targetUid = syncAdapterInfo.uid;
            targetComponent = syncAdapterInfo.componentName;
            ActiveSyncContext activeSyncContext =
                    new ActiveSyncContext(op, insertStartSyncEvent(op), targetUid);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "dispatchSyncOperation: starting " + activeSyncContext);
            }

            activeSyncContext.mSyncInfo = mSyncStorageEngine.addActiveSync(activeSyncContext);
            mActiveSyncContexts.add(activeSyncContext);

            // Post message to begin monitoring this sync's progress.
            postMonitorSyncProgressMessage(activeSyncContext);

            if (!activeSyncContext.bindToSyncAdapter(targetComponent, info.userId)) {
                mLogger.log("dispatchSyncOperation() failed: bind failed. target: ",
                        targetComponent);
                Slog.e(TAG, "Bind attempt failed - target: " + targetComponent);
                closeActiveSyncContext(activeSyncContext);
                return false;
            }

            return true;
        }

        private void runBoundToAdapterH(final ActiveSyncContext activeSyncContext,
                IBinder syncAdapter) {
            final SyncOperation syncOperation = activeSyncContext.mSyncOperation;
            try {
                activeSyncContext.mIsLinkedToDeath = true;
                syncAdapter.linkToDeath(activeSyncContext, 0);

                if (mLogger.enabled()) {
                    mLogger.log("Sync start: account=" + syncOperation.target.account,
                            " authority=", syncOperation.target.provider,
                            " reason=", SyncOperation.reasonToString(null, syncOperation.reason),
                            " extras=", syncOperation.getExtrasAsString(),
                            " adapter=", activeSyncContext.mSyncAdapter);
                }

                activeSyncContext.mSyncAdapter = ISyncAdapter.Stub.asInterface(syncAdapter);
                activeSyncContext.mSyncAdapter
                        .startSync(activeSyncContext, syncOperation.target.provider,
                                syncOperation.target.account, syncOperation.getClonedExtras());

                mLogger.log("Sync is running now...");
            } catch (RemoteException remoteExc) {
                mLogger.log("Sync failed with RemoteException: ", remoteExc.toString());
                Log.d(TAG, "maybeStartNextSync: caught a RemoteException, rescheduling", remoteExc);
                closeActiveSyncContext(activeSyncContext);
                increaseBackoffSetting(syncOperation.target);
                scheduleSyncOperationH(syncOperation);
            } catch (RuntimeException exc) {
                mLogger.log("Sync failed with RuntimeException: ", exc.toString());
                closeActiveSyncContext(activeSyncContext);
                Slog.e(TAG, "Caught RuntimeException while starting the sync "
                        + logSafe(syncOperation), exc);
            }
        }

        /**
         * Cancel the sync for the provided target that matches the given bundle.
         * @param info Can have null fields to indicate all the active syncs for that field.
         * @param extras Can be null to indicate <strong>all</strong> syncs for the given endpoint.
         */
        private void cancelActiveSyncH(SyncStorageEngine.EndPoint info, Bundle extras,
                String why) {
            ArrayList<ActiveSyncContext> activeSyncs =
                    new ArrayList<ActiveSyncContext>(mActiveSyncContexts);
            for (ActiveSyncContext activeSyncContext : activeSyncs) {
                if (activeSyncContext != null) {
                    final SyncStorageEngine.EndPoint opInfo =
                            activeSyncContext.mSyncOperation.target;
                    if (!opInfo.matchesSpec(info)) {
                        continue;
                    }
                    if (extras != null &&
                            !activeSyncContext.mSyncOperation.areExtrasEqual(extras,
                                    /*includeSyncSettings=*/ false)) {
                        continue;
                    }
                    SyncJobService.callJobFinished(activeSyncContext.mSyncOperation.jobId, false,
                            why);
                    runSyncFinishedOrCanceledH(null /* cancel => no result */, activeSyncContext);
                }
            }
        }

        /**
         * Should be called when a one-off instance of a periodic sync completes successfully.
         */
        private void reschedulePeriodicSyncH(SyncOperation syncOperation) {
            // Ensure that the periodic sync wasn't removed.
            SyncOperation periodicSync = null;
            List<SyncOperation> ops = getAllPendingSyncs();
            for (SyncOperation op: ops) {
                if (op.isPeriodic && syncOperation.matchesPeriodicOperation(op)) {
                    periodicSync = op;
                    break;
                }
            }
            if (periodicSync == null) {
                return;
            }
            scheduleSyncOperationH(periodicSync);
        }

        private void runSyncFinishedOrCanceledH(SyncResult syncResult,
                ActiveSyncContext activeSyncContext) {
            final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);

            final SyncOperation syncOperation = activeSyncContext.mSyncOperation;
            final SyncStorageEngine.EndPoint info = syncOperation.target;

            if (activeSyncContext.mIsLinkedToDeath) {
                activeSyncContext.mSyncAdapter.asBinder().unlinkToDeath(activeSyncContext, 0);
                activeSyncContext.mIsLinkedToDeath = false;
            }
            final long elapsedTime = SystemClock.elapsedRealtime() - activeSyncContext.mStartTime;
            String historyMessage;
            int downstreamActivity;
            int upstreamActivity;

            mLogger.log("runSyncFinishedOrCanceledH() op=", syncOperation, " result=", syncResult);

            if (syncResult != null) {
                if (isLoggable) {
                    Slog.v(TAG, "runSyncFinishedOrCanceled [finished]: "
                            + syncOperation + ", result " + syncResult);
                }

                // In the non-canceled case, close the active sync context before doing the rest
                // of the stuff.
                closeActiveSyncContext(activeSyncContext);

                // Note this part is probably okay to do before closeActiveSyncContext()...
                // But moved here to restore OC-dev's behavior.  See b/64597061.
                if (!syncOperation.isPeriodic) {
                    cancelJob(syncOperation, "runSyncFinishedOrCanceledH()-finished");
                }

                if (!syncResult.hasError()) {
                    historyMessage = SyncStorageEngine.MESG_SUCCESS;
                    // TODO: set these correctly when the SyncResult is extended to include it
                    downstreamActivity = 0;
                    upstreamActivity = 0;
                    clearBackoffSetting(syncOperation.target, "sync success");

                    // If the operation completes successfully and it was scheduled due to
                    // a periodic operation failing, we reschedule the periodic operation to
                    // start from now.
                    if (syncOperation.isDerivedFromFailedPeriodicSync()) {
                        reschedulePeriodicSyncH(syncOperation);
                    }
                } else {
                    Log.w(TAG, "failed sync operation "
                            + logSafe(syncOperation) + ", " + syncResult);

                    syncOperation.retries++;
                    if (syncOperation.retries > mConstants.getMaxRetriesWithAppStandbyExemption()) {
                        syncOperation.syncExemptionFlag = ContentResolver.SYNC_EXEMPTION_NONE;
                    }

                    // the operation failed so increase the backoff time
                    increaseBackoffSetting(syncOperation.target);
                    if (!syncOperation.isPeriodic) {
                        // reschedule the sync if so indicated by the syncResult
                        maybeRescheduleSync(syncResult, syncOperation);
                    } else {
                        // create a normal sync instance that will respect adapter backoffs
                        postScheduleSyncMessage(syncOperation.createOneTimeSyncOperation(),
                                0 /* min delay */);
                    }
                    historyMessage = ContentResolver.syncErrorToString(
                            syncResultToErrorNumber(syncResult));
                    // TODO: set these correctly when the SyncResult is extended to include it
                    downstreamActivity = 0;
                    upstreamActivity = 0;
                }
                setDelayUntilTime(syncOperation.target, syncResult.delayUntil);
            } else {
                if (isLoggable) {
                    Slog.v(TAG, "runSyncFinishedOrCanceled [canceled]: " + syncOperation);
                }

                if (!syncOperation.isPeriodic) {
                    cancelJob(syncOperation, "runSyncFinishedOrCanceledH()-canceled");
                }

                if (activeSyncContext.mSyncAdapter != null) {
                    try {
                        mLogger.log("Calling cancelSync for runSyncFinishedOrCanceled ",
                                activeSyncContext, "  adapter=", activeSyncContext.mSyncAdapter);
                        activeSyncContext.mSyncAdapter.cancelSync(activeSyncContext);
                        mLogger.log("Canceled");
                    } catch (RemoteException e) {
                        mLogger.log("RemoteException ", Log.getStackTraceString(e));
                        // we don't need to retry this in this case
                    }
                }
                historyMessage = SyncStorageEngine.MESG_CANCELED;
                downstreamActivity = 0;
                upstreamActivity = 0;

                // In the cancel sync case, close it after calling cancelSync().
                closeActiveSyncContext(activeSyncContext);
            }

            stopSyncEvent(activeSyncContext.mHistoryRowId, syncOperation, historyMessage,
                    upstreamActivity, downstreamActivity, elapsedTime);
            // Check for full-resync and schedule it after closing off the last sync.
            if (syncResult != null && syncResult.tooManyDeletions) {
                installHandleTooManyDeletesNotification(info.account,
                        info.provider, syncResult.stats.numDeletes,
                        info.userId);
            } else {
                mNotificationMgr.cancelAsUser(
                        Integer.toString(info.account.hashCode() ^ info.provider.hashCode()),
                        SystemMessage.NOTE_SYNC_ERROR,
                        new UserHandle(info.userId));
            }
            if (syncResult != null && syncResult.fullSyncRequested) {
                scheduleSyncOperationH(
                        new SyncOperation(info.account, info.userId,
                                syncOperation.owningUid, syncOperation.owningPackage,
                                syncOperation.reason,
                                syncOperation.syncSource, info.provider, new Bundle(),
                                syncOperation.allowParallelSyncs,
                                syncOperation.syncExemptionFlag));
            }
        }

        private void closeActiveSyncContext(ActiveSyncContext activeSyncContext) {
            activeSyncContext.close();
            mActiveSyncContexts.remove(activeSyncContext);
            mSyncStorageEngine.removeActiveSync(activeSyncContext.mSyncInfo,
                    activeSyncContext.mSyncOperation.target.userId);

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "removing all MESSAGE_MONITOR_SYNC & MESSAGE_SYNC_EXPIRED for "
                        + activeSyncContext.toString());
            }
            mSyncHandler.removeMessages(SyncHandler.MESSAGE_MONITOR_SYNC, activeSyncContext);

            mLogger.log("closeActiveSyncContext: ", activeSyncContext);
        }

        /**
         * Convert the error-containing SyncResult into the Sync.History error number. Since
         * the SyncResult may indicate multiple errors at once, this method just returns the
         * most "serious" error.
         * @param syncResult the SyncResult from which to read
         * @return the most "serious" error set in the SyncResult
         * @throws IllegalStateException if the SyncResult does not indicate any errors.
         *   If SyncResult.error() is true then it is safe to call this.
         */
        private int syncResultToErrorNumber(SyncResult syncResult) {
            if (syncResult.syncAlreadyInProgress)
                return ContentResolver.SYNC_ERROR_SYNC_ALREADY_IN_PROGRESS;
            if (syncResult.stats.numAuthExceptions > 0)
                return ContentResolver.SYNC_ERROR_AUTHENTICATION;
            if (syncResult.stats.numIoExceptions > 0)
                return ContentResolver.SYNC_ERROR_IO;
            if (syncResult.stats.numParseExceptions > 0)
                return ContentResolver.SYNC_ERROR_PARSE;
            if (syncResult.stats.numConflictDetectedExceptions > 0)
                return ContentResolver.SYNC_ERROR_CONFLICT;
            if (syncResult.tooManyDeletions)
                return ContentResolver.SYNC_ERROR_TOO_MANY_DELETIONS;
            if (syncResult.tooManyRetries)
                return ContentResolver.SYNC_ERROR_TOO_MANY_RETRIES;
            if (syncResult.databaseError)
                return ContentResolver.SYNC_ERROR_INTERNAL;
            throw new IllegalStateException("we are not in an error state, " + syncResult);
        }

        private void installHandleTooManyDeletesNotification(Account account, String authority,
                long numDeletes, int userId) {
            if (mNotificationMgr == null) return;

            final ProviderInfo providerInfo = mContext.getPackageManager().resolveContentProvider(
                    authority, 0 /* flags */);
            if (providerInfo == null) {
                return;
            }
            CharSequence authorityName = providerInfo.loadLabel(mContext.getPackageManager());

            Intent clickIntent = new Intent(mContext, SyncActivityTooManyDeletes.class);
            clickIntent.putExtra("account", account);
            clickIntent.putExtra("authority", authority);
            clickIntent.putExtra("provider", authorityName.toString());
            clickIntent.putExtra("numDeletes", numDeletes);

            if (!isActivityAvailable(clickIntent)) {
                Log.w(TAG, "No activity found to handle too many deletes.");
                return;
            }

            UserHandle user = new UserHandle(userId);
            final PendingIntent pendingIntent = PendingIntent
                    .getActivityAsUser(mContext, 0, clickIntent,
                            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                            null, user);

            CharSequence tooManyDeletesDescFormat = mContext.getResources().getText(
                    R.string.contentServiceTooManyDeletesNotificationDesc);

            Context contextForUser = getContextForUser(user);
            Notification notification =
                    new Notification.Builder(contextForUser, SystemNotificationChannels.ACCOUNT)
                    .setSmallIcon(R.drawable.stat_notify_sync_error)
                    .setTicker(mContext.getString(R.string.contentServiceSync))
                    .setWhen(System.currentTimeMillis())
                    .setColor(contextForUser.getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                    .setContentTitle(contextForUser.getString(
                            R.string.contentServiceSyncNotificationTitle))
                    .setContentText(
                            String.format(tooManyDeletesDescFormat.toString(), authorityName))
                    .setContentIntent(pendingIntent)
                    .build();
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            mNotificationMgr.notifyAsUser(
                    Integer.toString(account.hashCode() ^ authority.hashCode()),
                    SystemMessage.NOTE_SYNC_ERROR,
                    notification, user);
        }

        /**
         * Checks whether an activity exists on the system image for the given intent.
         *
         * @param intent The intent for an activity.
         * @return Whether or not an activity exists.
         */
        private boolean isActivityAvailable(Intent intent) {
            PackageManager pm = mContext.getPackageManager();
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            int listSize = list.size();
            for (int i = 0; i < listSize; i++) {
                ResolveInfo resolveInfo = list.get(i);
                if ((resolveInfo.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM)
                        != 0) {
                    return true;
                }
            }

            return false;
        }

        public long insertStartSyncEvent(SyncOperation syncOperation) {
            final long now = System.currentTimeMillis();
            EventLog.writeEvent(2720,
                    syncOperation.toEventLog(SyncStorageEngine.EVENT_START));
            return mSyncStorageEngine.insertStartSyncEvent(syncOperation, now);
        }

        public void stopSyncEvent(long rowId, SyncOperation syncOperation, String resultMessage,
                int upstreamActivity, int downstreamActivity, long elapsedTime) {
            EventLog.writeEvent(2720,
                    syncOperation.toEventLog(SyncStorageEngine.EVENT_STOP));
            mSyncStorageEngine.stopSyncEvent(rowId, elapsedTime,
                    resultMessage, downstreamActivity, upstreamActivity,
                    syncOperation.owningPackage, syncOperation.target.userId);
        }
    }

    private boolean isSyncStillActiveH(ActiveSyncContext activeSyncContext) {
        for (ActiveSyncContext sync : mActiveSyncContexts) {
            if (sync == activeSyncContext) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sync extra comparison function.
     * @param b1 bundle to compare
     * @param b2 other bundle to compare
     * @param includeSyncSettings if false, ignore system settings in bundle.
     */
    public static boolean syncExtrasEquals(Bundle b1, Bundle b2, boolean includeSyncSettings) {
        if (b1 == b2) {
            return true;
        }
        // Exit early if we can.
        if (includeSyncSettings && b1.size() != b2.size()) {
            return false;
        }
        Bundle bigger = b1.size() > b2.size() ? b1 : b2;
        Bundle smaller = b1.size() > b2.size() ? b2 : b1;
        for (String key : bigger.keySet()) {
            if (!includeSyncSettings && isSyncSetting(key)) {
                continue;
            }
            if (!smaller.containsKey(key)) {
                return false;
            }
            if (!Objects.equals(bigger.get(key), smaller.get(key))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return true if the provided key is used by the SyncManager in scheduling the sync.
     */
    private static boolean isSyncSetting(String key) {
        if (key == null) {
            return false;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_EXPEDITED)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_SCHEDULE_AS_EXPEDITED_JOB)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_MANUAL)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_UPLOAD)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_EXPECTED_UPLOAD)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_EXPECTED_DOWNLOAD)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_PRIORITY)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_DISALLOW_METERED)) {
            return true;
        }
        if (key.equals(ContentResolver.SYNC_EXTRAS_INITIALIZE)) {
            return true;
        }
//        if (key.equals(ContentResolver.SYNC_EXTRAS_APP_STANDBY_EXEMPTED)) {
//            return true;
//        }
        // No need to check virtual flags such as SYNC_VIRTUAL_EXTRAS_FORCE_FG_SYNC.
        return false;
    }

    static class PrintTable {
        private ArrayList<String[]> mTable = Lists.newArrayList();
        private final int mCols;

        PrintTable(int cols) {
            mCols = cols;
        }

        void set(int row, int col, Object... values) {
            if (col + values.length > mCols) {
                throw new IndexOutOfBoundsException("Table only has " + mCols +
                        " columns. can't set " + values.length + " at column " + col);
            }
            for (int i = mTable.size(); i <= row; i++) {
                final String[] list = new String[mCols];
                mTable.add(list);
                for (int j = 0; j < mCols; j++) {
                    list[j] = "";
                }
            }
            final String[] rowArray = mTable.get(row);
            for (int i = 0; i < values.length; i++) {
                final Object value = values[i];
                rowArray[col + i] = (value == null) ? "" : value.toString();
            }
        }

        void writeTo(PrintWriter out) {
            final String[] formats = new String[mCols];
            int totalLength = 0;
            for (int col = 0; col < mCols; ++col) {
                int maxLength = 0;
                for (Object[] row : mTable) {
                    final int length = row[col].toString().length();
                    if (length > maxLength) {
                        maxLength = length;
                    }
                }
                totalLength += maxLength;
                formats[col] = String.format("%%-%ds", maxLength);
            }
            formats[mCols - 1] = "%s";
            printRow(out, formats, mTable.get(0));
            totalLength += (mCols - 1) * 2;
            for (int i = 0; i < totalLength; ++i) {
                out.print("-");
            }
            out.println();
            for (int i = 1, mTableSize = mTable.size(); i < mTableSize; i++) {
                Object[] row = mTable.get(i);
                printRow(out, formats, row);
            }
        }

        private void printRow(PrintWriter out, String[] formats, Object[] row) {
            for (int j = 0, rowLength = row.length; j < rowLength; j++) {
                out.printf(String.format(formats[j], row[j].toString()));
                out.print("  ");
            }
            out.println();
        }

        public int getNumRows() {
            return mTable.size();
        }
    }

    private Context getContextForUser(UserHandle user) {
        try {
            return mContext.createPackageContextAsUser(mContext.getPackageName(), 0, user);
        } catch (NameNotFoundException e) {
            // Default to mContext, not finding the package system is running as is unlikely.
            return mContext;
        }
    }

    private void cancelJob(SyncOperation op, String why) {
        if (op == null) {
            Slog.wtf(TAG, "Null sync operation detected.");
            return;
        }
        if (op.isPeriodic) {
            mLogger.log("Removing periodic sync ", op, " for ", why);
        }
        getJobScheduler().cancel(op.jobId);
    }

    public void resetTodayStats() {
        mSyncStorageEngine.resetTodayStats(/*force=*/ true);
    }

    private boolean wasPackageEverLaunched(String packageName, int userId) {
        try {
            return mPackageManagerInternal.wasPackageEverLaunched(packageName, userId);
        } catch (IllegalArgumentException e) {
            return false; // Package has been removed.
        }
    }
}
