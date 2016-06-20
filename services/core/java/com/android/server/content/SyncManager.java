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

import android.accounts.Account;
import android.accounts.AccountAndUser;
import android.accounts.AccountManager;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ISyncAdapter;
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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.content.pm.RegisteredServicesCache;
import android.content.pm.RegisteredServicesCacheListener;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerInternal;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import com.android.internal.R;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.accounts.AccountManagerService;
import com.android.server.backup.AccountSyncSettingsBackupHelper;
import com.android.server.content.SyncStorageEngine.AuthorityInfo;
import com.android.server.content.SyncStorageEngine.EndPoint;
import com.android.server.content.SyncStorageEngine.OnSyncRequestListener;

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
 * @hide
 */
public class SyncManager {
    static final String TAG = "SyncManager";

    /** Delay a sync due to local changes this long. In milliseconds */
    private static final long LOCAL_SYNC_DELAY;

    static {
        LOCAL_SYNC_DELAY =
                SystemProperties.getLong("sync.local_sync_delay", 30 * 1000 /* 30 seconds */);
    }

    /**
     * When retrying a sync for the first time use this delay. After that
     * the retry time will double until it reached MAX_SYNC_RETRY_TIME.
     * In milliseconds.
     */
    private static final long INITIAL_SYNC_RETRY_TIME_IN_MS = 30 * 1000; // 30 seconds

    /**
     * Default the max sync retry time to this value.
     */
    private static final long DEFAULT_MAX_SYNC_RETRY_TIME_IN_SECONDS = 60 * 60; // one hour

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
     * If a previously scheduled sync becomes ready and we are low on storage, it gets
     * pushed back for this amount of time.
     */
    private static final long SYNC_DELAY_ON_LOW_STORAGE = 60*60*1000;   // 1 hour

    /**
     * If a sync becomes ready and it conflicts with an already running sync, it gets
     * pushed back for this amount of time.
     */
    private static final long SYNC_DELAY_ON_CONFLICT = 10*1000; // 10 seconds

    /**
     * Generate job ids in the range [MIN_SYNC_JOB_ID, MAX_SYNC_JOB_ID) to avoid conflicts with
     * other jobs scheduled by the system process.
     */
    private static final int MIN_SYNC_JOB_ID = 100000;
    private static final int MAX_SYNC_JOB_ID = 110000;

    private static final String SYNC_WAKE_LOCK_PREFIX = "*sync*/";
    private static final String HANDLE_SYNC_ALARM_WAKE_LOCK = "SyncManagerHandleSyncAlarm";
    private static final String SYNC_LOOP_WAKE_LOCK = "SyncLoopWakeLock";

    private Context mContext;

    private static final AccountAndUser[] INITIAL_ACCOUNTS_ARRAY = new AccountAndUser[0];

    // TODO: add better locking around mRunningAccounts
    private volatile AccountAndUser[] mRunningAccounts = INITIAL_ACCOUNTS_ARRAY;

    volatile private PowerManager.WakeLock mHandleAlarmWakeLock;
    volatile private PowerManager.WakeLock mSyncManagerWakeLock;
    volatile private boolean mDataConnectionIsConnected = false;
    volatile private boolean mStorageIsLow = false;
    volatile private boolean mDeviceIsIdle = false;
    volatile private boolean mReportedSyncActive = false;

    private final NotificationManager mNotificationMgr;
    private final IBatteryStats mBatteryStats;
    private JobScheduler mJobScheduler;
    private JobSchedulerInternal mJobSchedulerInternal;
    private SyncJobService mSyncJobService;

    private SyncStorageEngine mSyncStorageEngine;

    protected final ArrayList<ActiveSyncContext> mActiveSyncContexts = Lists.newArrayList();

    // Synchronized on "this". Instead of using this directly one should instead call
    // its accessor, getConnManager().
    private ConnectivityManager mConnManagerDoNotUseDirectly;

    /** Track whether the device has already been provisioned. */
    private volatile boolean mProvisioned;

    protected SyncAdaptersCache mSyncAdapters;

    private final Random mRand;

    private boolean isJobIdInUseLockedH(int jobId, List<JobInfo> pendingJobs) {
        for (JobInfo job: pendingJobs) {
            if (job.getId() == jobId) {
                return true;
            }
        }
        for (ActiveSyncContext asc: mActiveSyncContexts) {
            if (asc.mSyncOperation.jobId == jobId) {
                return true;
            }
        }
        return false;
    }

    private int getUnusedJobIdH() {
        int newJobId;
        do {
            newJobId = MIN_SYNC_JOB_ID + mRand.nextInt(MAX_SYNC_JOB_ID - MIN_SYNC_JOB_ID);
        } while (isJobIdInUseLockedH(newJobId,
                mJobSchedulerInternal.getSystemScheduledPendingJobs()));
        return newJobId;
    }

    private List<SyncOperation> getAllPendingSyncs() {
        verifyJobScheduler();
        List<JobInfo> pendingJobs = mJobSchedulerInternal.getSystemScheduledPendingJobs();
        List<SyncOperation> pendingSyncs = new ArrayList<SyncOperation>(pendingJobs.size());
        for (JobInfo job: pendingJobs) {
            SyncOperation op = SyncOperation.maybeCreateFromJobExtras(job.getExtras());
            if (op != null) {
                pendingSyncs.add(op);
            }
        }
        return pendingSyncs;
    }

    private final BroadcastReceiver mStorageIntentReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Slog.v(TAG, "Internal storage is low.");
                        }
                        mStorageIsLow = true;
                        cancelActiveSync(
                                SyncStorageEngine.EndPoint.USER_ALL_PROVIDER_ALL_ACCOUNTS_ALL,
                                null /* any sync */);
                    } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Slog.v(TAG, "Internal storage is ok.");
                        }
                        mStorageIsLow = false;
                        rescheduleSyncs(EndPoint.USER_ALL_PROVIDER_ALL_ACCOUNTS_ALL);
                    }
                }
            };

    private final BroadcastReceiver mBootCompletedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mBootCompleted = true;
            // Called because it gets all pending jobs and stores them in mScheduledSyncs cache.
            verifyJobScheduler();
            mSyncHandler.onBootCompleted();
        }
    };

    private final BroadcastReceiver mAccountsUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateRunningAccounts(EndPoint.USER_ALL_PROVIDER_ALL_ACCOUNTS_ALL
                        /* sync all targets */);
        }
    };

    private final PowerManager mPowerManager;

    private final UserManager mUserManager;

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

    private void doDatabaseCleanup() {
        for (UserInfo user : mUserManager.getUsers(true)) {
            // Skip any partially created/removed users
            if (user.partial) continue;
            Account[] accountsForUser = AccountManagerService.getSingleton().getAccounts(
                    user.id, mContext.getOpPackageName());

            mSyncStorageEngine.doDatabaseCleanup(accountsForUser, user.id);
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
                        }
                        clearAllBackoffs();
                    }
                }
            };

    private void clearAllBackoffs() {
        mSyncStorageEngine.clearAllBackoffsLocked();
        rescheduleSyncs(EndPoint.USER_ALL_PROVIDER_ALL_ACCOUNTS_ALL);
    }

    private boolean readDataConnectionState() {
        NetworkInfo networkInfo = getConnectivityManager().getActiveNetworkInfo();
        return (networkInfo != null) && networkInfo.isConnected();
    }

    private BroadcastReceiver mShutdownIntentReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.w(TAG, "Writing sync state before shutdown...");
                    getSyncStorageEngine().writeAllState();
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

    private final SyncHandler mSyncHandler;

    private volatile boolean mBootCompleted = false;
    private volatile boolean mJobServiceReady = false;

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
                            mJobScheduler.cancel(opy.jobId);
                        }
                    }
                }
            }
        });
    }

    private synchronized void verifyJobScheduler() {
        if (mJobScheduler != null) {
            return;
        }
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.d(TAG, "initializing JobScheduler object.");
        }
        mJobScheduler = (JobScheduler) mContext.getSystemService(
                Context.JOB_SCHEDULER_SERVICE);
        mJobSchedulerInternal = LocalServices.getService(JobSchedulerInternal.class);
        // Get all persisted syncs from JobScheduler
        List<JobInfo> pendingJobs = mJobScheduler.getAllPendingJobs();
        for (JobInfo job : pendingJobs) {
            SyncOperation op = SyncOperation.maybeCreateFromJobExtras(job.getExtras());
            if (op != null) {
                if (!op.isPeriodic) {
                    // Set the pending status of this EndPoint to true. Pending icon is
                    // shown on the settings activity.
                    mSyncStorageEngine.markPending(op.target, true);
                }
            }
        }
        cleanupJobs();
    }

    private JobScheduler getJobScheduler() {
        verifyJobScheduler();
        return mJobScheduler;
    }

    /**
     * Should only be created after {@link ContentService#systemReady()} so that
     * {@link PackageManager} is ready to query.
     */
    public SyncManager(Context context, boolean factoryTest) {
        // Initialize the SyncStorageEngine first, before registering observers
        // and creating threads and so on; it may fail if the disk is full.
        mContext = context;

        SyncStorageEngine.init(context);
        mSyncStorageEngine = SyncStorageEngine.getSingleton();
        mSyncStorageEngine.setOnSyncRequestListener(new OnSyncRequestListener() {
            @Override
            public void onSyncRequest(SyncStorageEngine.EndPoint info, int reason, Bundle extras) {
                scheduleSync(info.account, info.userId, reason, info.provider, extras,
                        0 /* no flexMillis */,
                        0 /* run immediately */,
                        false);
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
                removeSyncsForAuthority(removedAuthority);
            }
        });

        mSyncAdapters = new SyncAdaptersCache(mContext);

        mSyncHandler = new SyncHandler(BackgroundThread.get().getLooper());

        mSyncAdapters.setListener(new RegisteredServicesCacheListener<SyncAdapterType>() {
            @Override
            public void onServiceChanged(SyncAdapterType type, int userId, boolean removed) {
                if (!removed) {
                    scheduleSync(null, UserHandle.USER_ALL,
                            SyncOperation.REASON_SERVICE_CHANGED,
                            type.authority, null, 0 /* no delay */, 0 /* no delay */,
                            false /* onlyThoseWithUnkownSyncableState */);
                }
            }
        }, mSyncHandler);

        mRand = new Random(System.currentTimeMillis());

        IntentFilter intentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(mConnectivityIntentReceiver, intentFilter);

        if (!factoryTest) {
            intentFilter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
            intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            context.registerReceiver(mBootCompletedReceiver, intentFilter);
        }

        intentFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        context.registerReceiver(mStorageIntentReceiver, intentFilter);

        intentFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        intentFilter.setPriority(100);
        context.registerReceiver(mShutdownIntentReceiver, intentFilter);

        intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        intentFilter.addAction(Intent.ACTION_USER_STOPPED);
        mContext.registerReceiverAsUser(
                mUserIntentReceiver, UserHandle.ALL, intentFilter, null, null);

        if (!factoryTest) {
            mNotificationMgr = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
        } else {
            mNotificationMgr = null;
        }
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mBatteryStats = IBatteryStats.Stub.asInterface(ServiceManager.getService(
                BatteryStats.SERVICE_NAME));

        // This WakeLock is used to ensure that we stay awake between the time that we receive
        // a sync alarm notification and when we finish processing it. We need to do this
        // because we don't do the work in the alarm handler, rather we do it in a message
        // handler.
        mHandleAlarmWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                HANDLE_SYNC_ALARM_WAKE_LOCK);
        mHandleAlarmWakeLock.setReferenceCounted(false);

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
                                mSyncHandler.onDeviceProvisioned();
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

        // Set up the communication channel between the scheduled job and the sync manager.
        // This is posted to the *main* looper intentionally, to defer calling startService()
        // until after the lengthy primary boot sequence completes on that thread, to avoid
        // spurious ANR triggering.
        final Intent startServiceIntent = new Intent(mContext, SyncJobService.class);
        startServiceIntent.putExtra(SyncJobService.EXTRA_MESSENGER, new Messenger(mSyncHandler));
        new Handler(mContext.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                mContext.startService(startServiceIntent);
            }
        });
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

    public int getIsSyncable(Account account, int userId, String providerName) {
        int isSyncable = mSyncStorageEngine.getIsSyncable(account, userId, providerName);
        UserInfo userInfo = UserManager.get(mContext).getUserInfo(userId);

        // If it's not a restricted user, return isSyncable.
        if (userInfo == null || !userInfo.isRestricted()) return isSyncable;

        // Else check if the sync adapter has opted-in or not.
        RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo =
                mSyncAdapters.getServiceInfo(
                        SyncAdapterType.newKey(providerName, account.type), userId);
        if (syncAdapterInfo == null) return isSyncable;

        PackageInfo pInfo = null;
        try {
            pInfo = AppGlobals.getPackageManager().getPackageInfo(
                    syncAdapterInfo.componentName.getPackageName(), 0, userId);
            if (pInfo == null) return isSyncable;
        } catch (RemoteException re) {
            // Shouldn't happen.
            return isSyncable;
        }
        if (pInfo.restrictedAccountType != null
                && pInfo.restrictedAccountType.equals(account.type)) {
            return isSyncable;
        } else {
            return 0;
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
     * @param beforeRuntimeMillis milliseconds before runtimeMillis that this sync can run.
     * @param runtimeMillis maximum milliseconds in the future to wait before performing sync.
     * @param onlyThoseWithUnkownSyncableState Only sync authorities that have unknown state.
     */
    public void scheduleSync(Account requestedAccount, int userId, int reason,
            String requestedAuthority, Bundle extras, long beforeRuntimeMillis,
            long runtimeMillis, boolean onlyThoseWithUnkownSyncableState) {
        final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
        if (extras == null) {
            extras = new Bundle();
        }
        if (isLoggable) {
            Log.d(TAG, "one-time sync for: " + requestedAccount + " " + extras.toString() + " "
                    + requestedAuthority);
        }

        AccountAndUser[] accounts;
        if (requestedAccount != null && userId != UserHandle.USER_ALL) {
            accounts = new AccountAndUser[] { new AccountAndUser(requestedAccount, userId) };
        } else {
            accounts = mRunningAccounts;
            if (accounts.length == 0) {
                if (isLoggable) {
                    Slog.v(TAG, "scheduleSync: no accounts configured, dropping");
                }
                return;
            }
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
            // This isn't strictly server, since arbitrary callers can (and do) request
            // a non-forced two-way sync on a specific url.
            source = SyncStorageEngine.SOURCE_SERVER;
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
                int isSyncable = getIsSyncable(account.account, account.userId,
                        authority);
                if (isSyncable == AuthorityInfo.NOT_SYNCABLE) {
                    continue;
                }
                final RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo;
                syncAdapterInfo = mSyncAdapters.getServiceInfo(
                        SyncAdapterType.newKey(authority, account.account.type), account.userId);
                if (syncAdapterInfo == null) {
                    continue;
                }
                final int owningUid = syncAdapterInfo.uid;
                final String owningPackage = syncAdapterInfo.componentName.getPackageName();
                try {
                    if (ActivityManagerNative.getDefault().getAppStartMode(owningUid,
                            owningPackage) == ActivityManager.APP_START_MODE_DISABLED) {
                        Slog.w(TAG, "Not scheduling job " + syncAdapterInfo.uid + ":"
                                + syncAdapterInfo.componentName
                                + " -- package not allowed to start");
                        continue;
                    }
                } catch (RemoteException e) {
                }
                final boolean allowParallelSyncs = syncAdapterInfo.type.allowParallelSyncs();
                final boolean isAlwaysSyncable = syncAdapterInfo.type.isAlwaysSyncable();
                if (isSyncable < 0 && isAlwaysSyncable) {
                    mSyncStorageEngine.setIsSyncable(
                            account.account, account.userId, authority, AuthorityInfo.SYNCABLE);
                    isSyncable = AuthorityInfo.SYNCABLE;
                }
                if (onlyThoseWithUnkownSyncableState && isSyncable >= 0) {
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
                    if (isLoggable) {
                        Log.d(TAG, "scheduleSync: sync of " + account + ", " + authority
                                + " is not allowed, dropping request");
                    }
                    continue;
                }
                SyncStorageEngine.EndPoint info =
                        new SyncStorageEngine.EndPoint(
                                account.account, authority, account.userId);
                long delayUntil =
                        mSyncStorageEngine.getDelayUntilTime(info);
                if (isSyncable < 0) {
                    // Initialisation sync.
                    Bundle newExtras = new Bundle();
                    newExtras.putBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, true);
                    if (isLoggable) {
                        Slog.v(TAG, "schedule initialisation Sync:"
                                + ", delay until " + delayUntil
                                + ", run by " + 0
                                + ", flexMillis " + 0
                                + ", source " + source
                                + ", account " + account
                                + ", authority " + authority
                                + ", extras " + newExtras);
                    }
                    postScheduleSyncMessage(
                            new SyncOperation(account.account, account.userId,
                                    owningUid, owningPackage, reason, source,
                                    authority, newExtras, allowParallelSyncs)
                    );
                }
                if (!onlyThoseWithUnkownSyncableState) {
                    if (isLoggable) {
                        Slog.v(TAG, "scheduleSync:"
                                + " delay until " + delayUntil
                                + " run by " + runtimeMillis
                                + " flexMillis " + beforeRuntimeMillis
                                + ", source " + source
                                + ", account " + account
                                + ", authority " + authority
                                + ", extras " + extras);
                    }
                    postScheduleSyncMessage(
                            new SyncOperation(account.account, account.userId,
                                    owningUid, owningPackage, reason, source,
                                    authority, extras, allowParallelSyncs)
                    );
                }
            }
        }
    }

    private void removeSyncsForAuthority(EndPoint info) {
        verifyJobScheduler();
        List<SyncOperation> ops = getAllPendingSyncs();
        for (SyncOperation op: ops) {
            if (op.target.matchesSpec(info)) {
                 getJobScheduler().cancel(op.jobId);
            }
        }
    }

    /**
     * Remove a specific periodic sync identified by its target and extras.
     */
    public void removePeriodicSync(EndPoint target, Bundle extras) {
        Message m = mSyncHandler.obtainMessage(mSyncHandler.MESSAGE_REMOVE_PERIODIC_SYNC, target);
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
                        op.extras, op.periodMillis / 1000, op.flexMillis / 1000));
            }
        }

        return periodicSyncs;
    }

    /**
     * Schedule sync based on local changes to a provider. Occurs within interval
     * [LOCAL_SYNC_DELAY, 2*LOCAL_SYNC_DELAY].
     */
    public void scheduleLocalSync(Account account, int userId, int reason, String authority) {
        final Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true);
        scheduleSync(account, userId, reason, authority, extras,
                LOCAL_SYNC_DELAY /* earliest run time */,
                2 * LOCAL_SYNC_DELAY /* latest sync time. */,
                false /* onlyThoseWithUnkownSyncableState */);
    }

    public SyncAdapterType[] getSyncAdapterTypes(int userId) {
        final Collection<RegisteredServicesCache.ServiceInfo<SyncAdapterType>> serviceInfos;
        serviceInfos = mSyncAdapters.getAllServices(userId);
        SyncAdapterType[] types = new SyncAdapterType[serviceInfos.size()];
        int i = 0;
        for (RegisteredServicesCache.ServiceInfo<SyncAdapterType> serviceInfo : serviceInfos) {
            types[i] = serviceInfo.type;
            ++i;
        }
        return types;
    }

    public String[] getSyncAdapterPackagesForAuthorityAsUser(String authority, int userId) {
        return mSyncAdapters.getSyncAdapterPackagesForAuthority(authority, userId);
    }

    private void sendSyncFinishedOrCanceledMessage(ActiveSyncContext syncContext,
            SyncResult syncResult) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Slog.v(TAG, "sending MESSAGE_SYNC_FINISHED");
        Message msg = mSyncHandler.obtainMessage();
        msg.what = SyncHandler.MESSAGE_SYNC_FINISHED;
        msg.obj = new SyncFinishedOrCancelledMessagePayload(syncContext, syncResult);
        mSyncHandler.sendMessage(msg);
    }

    private void sendCancelSyncsMessage(final SyncStorageEngine.EndPoint info, Bundle extras) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Slog.v(TAG, "sending MESSAGE_CANCEL");
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

    private void postScheduleSyncMessage(SyncOperation syncOperation) {
        mSyncHandler.obtainMessage(mSyncHandler.MESSAGE_SCHEDULE_SYNC, syncOperation)
                .sendToTarget();
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

    private void clearBackoffSetting(EndPoint target) {
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

        rescheduleSyncs(target);
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
            newDelayInMs = previousSettings.second * 2;
        }
        if (newDelayInMs <= 0) {
            // The initial delay is the jitterized INITIAL_SYNC_RETRY_TIME_IN_MS.
            newDelayInMs = jitterize(INITIAL_SYNC_RETRY_TIME_IN_MS,
                    (long)(INITIAL_SYNC_RETRY_TIME_IN_MS * 1.1));
        }

        // Cap the delay.
        long maxSyncRetryTimeInSeconds = Settings.Global.getLong(mContext.getContentResolver(),
                Settings.Global.SYNC_MAX_RETRY_DELAY_IN_SECONDS,
                DEFAULT_MAX_SYNC_RETRY_TIME_IN_SECONDS);
        if (newDelayInMs > maxSyncRetryTimeInSeconds * 1000) {
            newDelayInMs = maxSyncRetryTimeInSeconds * 1000;
        }

        final long backoff = now + newDelayInMs;
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "Backoff until: " + backoff + ", delayTime: " + newDelayInMs);
        }
        mSyncStorageEngine.setBackoff(target, backoff, newDelayInMs);
        rescheduleSyncs(target);
    }

    /**
     * Reschedule all scheduled syncs for this EndPoint. The syncs will be scheduled according
     * to current backoff and delayUntil values of this EndPoint.
     */
    private void rescheduleSyncs(EndPoint target) {
        List<SyncOperation> ops = getAllPendingSyncs();
        int count = 0;
        for (SyncOperation op: ops) {
            if (!op.isPeriodic && op.target.matchesSpec(target)) {
                count++;
                getJobScheduler().cancel(op.jobId);
                postScheduleSyncMessage(op);
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
        rescheduleSyncs(target);
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
    public void cancelActiveSync(SyncStorageEngine.EndPoint info, Bundle extras) {
        sendCancelSyncsMessage(info, extras);
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
        if (!syncOperation.ignoreBackoff()) {
            Pair<Long, Long> backoff = mSyncStorageEngine.getBackoff(syncOperation.target);
            if (backoff == null) {
                Slog.e(TAG, "Couldn't find backoff values for " + syncOperation.target);
                backoff = new Pair<Long, Long>(SyncStorageEngine.NOT_IN_BACKOFF_MODE,
                        SyncStorageEngine.NOT_IN_BACKOFF_MODE);
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
        }

        // Check if duplicate syncs are pending. If found, keep one with least expected run time.
        if (!syncOperation.isPeriodic) {
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
            SyncOperation opWithLeastExpectedRuntime = syncOperation;
            for (SyncOperation op : pending) {
                if (op.isPeriodic) {
                    continue;
                }
                if (op.key.equals(syncOperation.key)) {
                    if (opWithLeastExpectedRuntime.expectedRuntime > op.expectedRuntime) {
                        opWithLeastExpectedRuntime = op;
                    }
                    duplicatesCount++;
                }
            }
            if (duplicatesCount > 1) {
                Slog.e(TAG, "FATAL ERROR! File a bug if you see this.");
            }
            for (SyncOperation op : pending) {
                if (op.isPeriodic) {
                    continue;
                }
                if (op.key.equals(syncOperation.key)) {
                    if (op != opWithLeastExpectedRuntime) {
                        if (isLoggable) {
                            Slog.v(TAG, "Cancelling duplicate sync " + op);
                        }
                        getJobScheduler().cancel(op.jobId);
                    }
                }
            }
            if (opWithLeastExpectedRuntime != syncOperation) {
                // Don't schedule because a duplicate sync with earlier expected runtime exists.
                if (isLoggable) {
                    Slog.v(TAG, "Not scheduling because a duplicate exists.");
                }
                return;
            }
        }

        // Syncs that are re-scheduled shouldn't get a new job id.
        if (syncOperation.jobId == SyncOperation.NO_JOB_ID) {
            syncOperation.jobId = getUnusedJobIdH();
        }

        if (isLoggable) {
            Slog.v(TAG, "scheduling sync operation " + syncOperation.toString());
        }

        int priority = syncOperation.findPriority();

        final int networkType = syncOperation.isNotAllowedOnMetered() ?
                JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY;

        JobInfo.Builder b = new JobInfo.Builder(syncOperation.jobId,
                new ComponentName(mContext, SyncJobService.class))
                .setExtras(syncOperation.toJobInfoExtras())
                .setRequiredNetworkType(networkType)
                .setPersisted(true)
                .setPriority(priority);

        if (syncOperation.isPeriodic) {
            b.setPeriodic(syncOperation.periodMillis, syncOperation.flexMillis);
        } else {
            if (minDelay > 0) {
                b.setMinimumLatency(minDelay);
            }
            getSyncStorageEngine().markPending(syncOperation.target, true);
        }

        if (syncOperation.extras.getBoolean(ContentResolver.SYNC_EXTRAS_REQUIRE_CHARGING)) {
            b.setRequiresCharging(true);
        }

        getJobScheduler().scheduleAsPackage(b.build(), syncOperation.owningPackage,
                syncOperation.target.userId, syncOperation.wakeLockName());
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
                getJobScheduler().cancel(op.jobId);
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
                    && syncExtrasEquals(extras, op.extras, false)) {
                getJobScheduler().cancel(op.jobId);
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

        // The SYNC_EXTRAS_IGNORE_BACKOFF only applies to the first attempt to sync a given
        // request. Retries of the request will always honor the backoff, so clear the
        // flag in case we retry this request.
        if (operation.extras.getBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, false)) {
            operation.extras.remove(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF);
        }

        if (operation.extras.getBoolean(ContentResolver.SYNC_EXTRAS_DO_NOT_RETRY, false)
                && !syncResult.syncAlreadyInProgress) {
            // syncAlreadyInProgress flag is set by AbstractThreadedSyncAdapter. The sync adapter
            // has no way of knowing that a sync error occured. So we DO retry if the error is
            // syncAlreadyInProgress.
            if (isLoggable) {
                Log.d(TAG, "not retrying sync operation because SYNC_EXTRAS_DO_NOT_RETRY was specified "
                        + operation);
            }
        } else if (operation.extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false)
                && !syncResult.syncAlreadyInProgress) {
            // If this was an upward sync then schedule a two-way sync immediately.
            operation.extras.remove(ContentResolver.SYNC_EXTRAS_UPLOAD);
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
            Log.d(TAG, "not retrying sync operation because the error is a hard error: "
                    + operation);
        }
    }

    private void onUserUnlocked(int userId) {
        // Make sure that accounts we're about to use are valid.
        AccountManagerService.getSingleton().validateAccounts(userId);

        mSyncAdapters.invalidateCache(userId);

        EndPoint target = new EndPoint(null, null, userId);
        updateRunningAccounts(target);

        // Schedule sync for any accounts under started user.
        final Account[] accounts = AccountManagerService.getSingleton().getAccounts(userId,
                mContext.getOpPackageName());
        for (Account account : accounts) {
            scheduleSync(account, userId, SyncOperation.REASON_USER_START, null, null,
                    0 /* no delay */, 0 /* No flexMillis */,
                    true /* onlyThoseWithUnknownSyncableState */);
        }
    }

    private void onUserStopped(int userId) {
        updateRunningAccounts(null /* Don't sync any target */);

        cancelActiveSync(
                new SyncStorageEngine.EndPoint(
                        null /* any account */,
                        null /* any authority */,
                        userId),
                null /* any sync. */
        );
    }

    private void onUserRemoved(int userId) {
        updateRunningAccounts(null /* Don't sync any target */);

        // Clean up the storage engine database
        mSyncStorageEngine.doDatabaseCleanup(new Account[0], userId);
        List<SyncOperation> ops = getAllPendingSyncs();
        for (SyncOperation op: ops) {
            if (op.target.userId == userId) {
                getJobScheduler().cancel(op.jobId);
            }
        }
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
            sendSyncFinishedOrCanceledMessage(this, result);
        }

        public void toString(StringBuilder sb) {
            sb.append("startTime ").append(mStartTime)
                    .append(", mTimeoutStartTime ").append(mTimeoutStartTime)
                    .append(", mHistoryRowId ").append(mHistoryRowId)
                    .append(", syncOperation ").append(mSyncOperation);
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
            Intent intent = new Intent();
            intent.setAction("android.content.SyncAdapter");
            intent.setComponent(serviceComponent);
            intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    com.android.internal.R.string.sync_binding_label);
            intent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivityAsUser(
                    mContext, 0, new Intent(Settings.ACTION_SYNC_SETTINGS), 0,
                    null, new UserHandle(userId)));
            mBound = true;
            final boolean bindResult = mContext.bindServiceAsUser(intent, this,
                    Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND
                            | Context.BIND_ALLOW_OOM_MANAGEMENT,
                    new UserHandle(mSyncOperation.target.userId));
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
            toString(sb);
            return sb.toString();
        }

        @Override
        public void binderDied() {
            sendSyncFinishedOrCanceledMessage(this, null);
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        dumpPendingSyncs(pw);
        dumpPeriodicSyncs(pw);
        dumpSyncState(ipw);
        dumpSyncHistory(ipw);
        dumpSyncAdapters(ipw);
    }

    static String formatTime(long time) {
        Time tobj = new Time();
        tobj.set(time);
        return tobj.format("%Y-%m-%d %H:%M:%S");
    }

    protected void dumpPendingSyncs(PrintWriter pw) {
        pw.println("Pending Syncs:");
        List<SyncOperation> pendingSyncs = getAllPendingSyncs();
        int count = 0;
        for (SyncOperation op: pendingSyncs) {
            if (!op.isPeriodic) {
                pw.println(op.dump(null, false));
                count++;
            }
        }
        pw.println("Total: " + count);
        pw.println();
    }

    protected void dumpPeriodicSyncs(PrintWriter pw) {
        pw.println("Periodic Syncs:");
        List<SyncOperation> pendingSyncs = getAllPendingSyncs();
        int count = 0;
        for (SyncOperation op: pendingSyncs) {
            if (op.isPeriodic) {
                pw.println(op.dump(null, false));
                count++;
            }
        }
        pw.println("Total: " + count);
        pw.println();
    }

    protected void dumpSyncState(PrintWriter pw) {
        pw.print("data connected: "); pw.println(mDataConnectionIsConnected);
        pw.print("auto sync: ");
        List<UserInfo> users = getAllUsers();
        if (users != null) {
            for (UserInfo user : users) {
                pw.print("u" + user.id + "="
                        + mSyncStorageEngine.getMasterSyncAutomatically(user.id) + " ");
            }
            pw.println();
        }
        pw.print("memory low: "); pw.println(mStorageIsLow);
        pw.print("device idle: "); pw.println(mDeviceIsIdle);
        pw.print("reported active: "); pw.println(mReportedSyncActive);

        final AccountAndUser[] accounts = AccountManagerService.getSingleton().getAllAccounts();

        pw.print("accounts: ");
        if (accounts != INITIAL_ACCOUNTS_ARRAY) {
            pw.println(accounts.length);
        } else {
            pw.println("not known yet");
        }
        final long now = SystemClock.elapsedRealtime();
        pw.print("now: "); pw.print(now);
        pw.println(" (" + formatTime(System.currentTimeMillis()) + ")");
        pw.println(" (HH:MM:SS)");
        pw.print("uptime: "); pw.print(DateUtils.formatElapsedTime(now / 1000));
        pw.println(" (HH:MM:SS)");
        pw.print("time spent syncing: ");
        pw.print(DateUtils.formatElapsedTime(
                mSyncHandler.mSyncTimeTracker.timeSpentSyncing() / 1000));
        pw.print(" (HH:MM:SS), sync ");
        pw.print(mSyncHandler.mSyncTimeTracker.mLastWasSyncing ? "" : "not ");
        pw.println("in progress");

        pw.println();
        pw.println("Active Syncs: " + mActiveSyncContexts.size());
        final PackageManager pm = mContext.getPackageManager();
        for (SyncManager.ActiveSyncContext activeSyncContext : mActiveSyncContexts) {
            final long durationInSeconds = (now - activeSyncContext.mStartTime) / 1000;
            pw.print("  ");
            pw.print(DateUtils.formatElapsedTime(durationInSeconds));
            pw.print(" - ");
            pw.print(activeSyncContext.mSyncOperation.dump(pm, false));
            pw.println();
        }

        // Join the installed sync adapter with the accounts list and emit for everything.
        pw.println();
        pw.println("Sync Status");
        for (AccountAndUser account : accounts) {
            pw.printf("Account %s u%d %s\n",
                    account.account.name, account.userId, account.account.type);

            pw.println("=======================================================================");
            final PrintTable table = new PrintTable(12);
            table.set(0, 0,
                    "Authority", // 0
                    "Syncable",  // 1
                    "Enabled",   // 2
                    "Delay",     // 3
                    "Loc",       // 4
                    "Poll",      // 5
                    "Per",       // 6
                    "Serv",      // 7
                    "User",      // 8
                    "Tot",       // 9
                    "Time",      // 10
                    "Last Sync" // 11
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
                String authority = settings.target.provider;
                if (authority.length() > 50) {
                    authority = authority.substring(authority.length() - 50);
                }
                table.set(row, 0, authority, settings.syncable, settings.enabled);
                table.set(row, 4,
                        status.numSourceLocal,
                        status.numSourcePoll,
                        status.numSourcePeriodic,
                        status.numSourceServer,
                        status.numSourceUser,
                        status.numSyncs,
                        DateUtils.formatElapsedTime(status.totalElapsedTime / 1000));

                int row1 = row;
                if (settings.delayUntil > now) {
                    table.set(row1++, 12, "D: " + (settings.delayUntil - now) / 1000);
                    if (settings.backoffTime > now) {
                        table.set(row1++, 12, "B: " + (settings.backoffTime - now) / 1000);
                        table.set(row1++, 12, settings.backoffDelay / 1000);
                    }
                }

                if (status.lastSuccessTime != 0) {
                    table.set(row1++, 11, SyncStorageEngine.SOURCES[status.lastSuccessSource]
                            + " " + "SUCCESS");
                    table.set(row1++, 11, formatTime(status.lastSuccessTime));
                }
                if (status.lastFailureTime != 0) {
                    table.set(row1++, 11, SyncStorageEngine.SOURCES[status.lastFailureSource]
                            + " " + "FAILURE");
                    table.set(row1++, 11, formatTime(status.lastFailureTime));
                    //noinspection UnusedAssignment
                    table.set(row1++, 11, status.lastFailureMesg);
                }
            }
            table.writeTo(pw);
        }
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
                final Time time = new Time();
                final long eventTime = item.eventTime;
                time.set(eventTime);

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
                final Time time = new Time();
                final long eventTime = item.eventTime;
                time.set(eventTime);

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

    /**
     * Handles SyncOperation Messages that are posted to the associated
     * HandlerThread.
     */
    class SyncHandler extends Handler {
        // Messages that can be sent on mHandler.
        private static final int MESSAGE_SYNC_FINISHED = 1;
        private static final int MESSAGE_RELEASE_MESSAGES_FROM_QUEUE = 2;
        private static final int MESSAGE_SERVICE_CONNECTED = 4;
        private static final int MESSAGE_SERVICE_DISCONNECTED = 5;
        private static final int MESSAGE_CANCEL = 6;
        static final int MESSAGE_JOBSERVICE_OBJECT = 7;
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

        private List<Message> mUnreadyQueue = new ArrayList<Message>();

        void onBootCompleted() {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "Boot completed.");
            }
            checkIfDeviceReady();
        }

        void onDeviceProvisioned() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "mProvisioned=" + mProvisioned);
            }
            checkIfDeviceReady();
        }

        void checkIfDeviceReady() {
            if (mProvisioned && mBootCompleted && mJobServiceReady) {
                synchronized(this) {
                    mSyncStorageEngine.restoreAllPeriodicSyncs();
                    // Dispatch any stashed messages.
                    obtainMessage(MESSAGE_RELEASE_MESSAGES_FROM_QUEUE).sendToTarget();
                }
            }
        }

        /**
         * Stash any messages that come to the handler before boot is complete or before the device
         * is properly provisioned (i.e. out of set-up wizard).
         * {@link #onBootCompleted()} and {@link SyncHandler#onDeviceProvisioned} both
         * need to come in before we start syncing.
         * @param msg Message to dispatch at a later point.
         * @return true if a message was enqueued, false otherwise. This is to avoid losing the
         * message if we manage to acquire the lock but by the time we do boot has completed.
         */
        private boolean tryEnqueueMessageUntilReadyToRun(Message msg) {
            synchronized (this) {
                if (!mBootCompleted || !mProvisioned || !mJobServiceReady) {
                    // Need to copy the message bc looper will recycle it.
                    Message m = Message.obtain(msg);
                    mUnreadyQueue.add(m);
                    return true;
                } else {
                    return false;
                }
            }
        }

        public SyncHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            try {
                mSyncManagerWakeLock.acquire();
                // We only want to enqueue sync related messages until device is ready.
                // Other messages are handled without enqueuing.
                if (msg.what == MESSAGE_JOBSERVICE_OBJECT) {
                    Slog.i(TAG, "Got SyncJobService instance.");
                    mSyncJobService = (SyncJobService) msg.obj;
                    mJobServiceReady = true;
                    checkIfDeviceReady();
                } else if (msg.what == SyncHandler.MESSAGE_ACCOUNTS_UPDATED) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Slog.v(TAG, "handleSyncHandlerMessage: MESSAGE_ACCOUNTS_UPDATED");
                    }
                    EndPoint targets = (EndPoint) msg.obj;
                    updateRunningAccountsH(targets);
                } else if (msg.what == MESSAGE_RELEASE_MESSAGES_FROM_QUEUE) {
                    if (mUnreadyQueue != null) {
                        for (Message m : mUnreadyQueue) {
                            handleSyncMessage(m);
                        }
                        mUnreadyQueue = null;
                    }
                } else if (tryEnqueueMessageUntilReadyToRun(msg)) {
                    // No work to be done.
                } else {
                    handleSyncMessage(msg);
                }
            } finally {
                mSyncManagerWakeLock.release();
            }
        }

        private void handleSyncMessage(Message msg) {
            final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);

            try {
                mDataConnectionIsConnected = readDataConnectionState();
                switch (msg.what) {
                    case MESSAGE_SCHEDULE_SYNC:
                        SyncOperation op = (SyncOperation) msg.obj;
                        scheduleSyncOperationH(op);
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
                        removePeriodicSyncH((EndPoint)msg.obj, msg.getData());
                        break;

                    case SyncHandler.MESSAGE_CANCEL:
                        SyncStorageEngine.EndPoint endpoint = (SyncStorageEngine.EndPoint) msg.obj;
                        Bundle extras = msg.peekData();
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_CANCEL: "
                                    + endpoint + " bundle: " + extras);
                        }
                        cancelActiveSyncH(endpoint, extras);
                        break;

                    case SyncHandler.MESSAGE_SYNC_FINISHED:
                        SyncFinishedOrCancelledMessagePayload payload =
                                (SyncFinishedOrCancelledMessagePayload) msg.obj;
                        if (!isSyncStillActiveH(payload.activeSyncContext)) {
                            Log.d(TAG, "handleSyncHandlerMessage: dropping since the "
                                    + "sync is no longer active: "
                                    + payload.activeSyncContext);
                            break;
                        }
                        if (isLoggable) {
                            Slog.v(TAG, "syncFinished" + payload.activeSyncContext.mSyncOperation);
                        }
                        mSyncJobService.callJobFinished(
                                payload.activeSyncContext.mSyncOperation.jobId, false);
                        runSyncFinishedOrCanceledH(payload.syncResult,
                                payload.activeSyncContext);
                        break;

                    case SyncHandler.MESSAGE_SERVICE_CONNECTED: {
                        ServiceConnectionData msgData = (ServiceConnectionData) msg.obj;
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
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
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_SERVICE_DISCONNECTED: "
                                    + currentSyncContext);
                        }
                        // Check that this isn't an old message.
                        if (isSyncStillActiveH(currentSyncContext)) {
                            // cancel the sync if we have a syncadapter, which means one is
                            // outstanding
                            try {
                                if (currentSyncContext.mSyncAdapter != null) {
                                    currentSyncContext.mSyncAdapter.cancelSync(currentSyncContext);
                                }
                            } catch (RemoteException e) {
                                // We don't need to retry this in this case.
                            }

                            // Pretend that the sync failed with an IOException,
                            // which is a soft error.
                            SyncResult syncResult = new SyncResult();
                            syncResult.stats.numIoExceptions++;
                            mSyncJobService.callJobFinished(
                                    currentSyncContext.mSyncOperation.jobId, false);
                            runSyncFinishedOrCanceledH(syncResult, currentSyncContext);
                        }
                        break;
                    }

                    case SyncHandler.MESSAGE_MONITOR_SYNC:
                        ActiveSyncContext monitoredSyncContext = (ActiveSyncContext) msg.obj;
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_MONITOR_SYNC: " +
                                    monitoredSyncContext.mSyncOperation.target);
                        }

                        if (isSyncNotUsingNetworkH(monitoredSyncContext)) {
                            Log.w(TAG, String.format(
                                    "Detected sync making no progress for %s. cancelling.",
                                    monitoredSyncContext));
                            mSyncJobService.callJobFinished(
                                    monitoredSyncContext.mSyncOperation.jobId, false);
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
        private void deferSyncH(SyncOperation op, long delay) {
            mSyncJobService.callJobFinished(op.jobId, false);
            if (op.isPeriodic) {
                scheduleSyncOperationH(op.createOneTimeSyncOperation(), delay);
            } else {
                // mSyncJobService.callJobFinished is async, so cancel the job to ensure we don't
                // find the this job in the pending jobs list while looking for duplicates
                // before scheduling it at a later time.
                getJobScheduler().cancel(op.jobId);
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
        private void deferActiveSyncH(ActiveSyncContext asc) {
            SyncOperation op = asc.mSyncOperation;
            runSyncFinishedOrCanceledH(null, asc);
            deferSyncH(op, SYNC_DELAY_ON_CONFLICT);
        }

        private void startSyncH(SyncOperation op) {
            final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
            if (isLoggable) Slog.v(TAG, op.toString());

            if (mStorageIsLow) {
                deferSyncH(op, SYNC_DELAY_ON_LOW_STORAGE);
                return;
            }

            if (op.isPeriodic) {
                // Don't allow this periodic to run if a previous instance failed and is currently
                // scheduled according to some backoff criteria.
                List<SyncOperation> ops = getAllPendingSyncs();
                for (SyncOperation syncOperation: ops) {
                    if (syncOperation.sourcePeriodicId == op.jobId) {
                        mSyncJobService.callJobFinished(op.jobId, false);
                        return;
                    }
                }
                // Don't allow this periodic to run if a previous instance failed and is currently
                // executing according to some backoff criteria.
                for (ActiveSyncContext asc: mActiveSyncContexts) {
                    if (asc.mSyncOperation.sourcePeriodicId == op.jobId) {
                        mSyncJobService.callJobFinished(op.jobId, false);
                        return;
                    }
                }
                // Check for adapter delays.
                if (isAdapterDelayed(op.target)) {
                    deferSyncH(op, 0 /* No minimum delay */);
                    return;
                }
            }

            // Check for conflicting syncs.
            for (ActiveSyncContext asc: mActiveSyncContexts) {
                if (asc.mSyncOperation.isConflict(op)) {
                    // If the provided SyncOperation conflicts with a running one, the lower
                    // priority sync is pre-empted.
                    if (asc.mSyncOperation.findPriority() >= op.findPriority()) {
                        if (isLoggable) {
                            Slog.v(TAG, "Rescheduling sync due to conflict " + op.toString());
                        }
                        deferSyncH(op, SYNC_DELAY_ON_CONFLICT);
                        return;
                    } else {
                        if (isLoggable) {
                            Slog.v(TAG, "Pushing back running sync due to a higher priority sync");
                        }
                        deferActiveSyncH(asc);
                        break;
                    }
                }
            }

            if (isOperationValid(op)) {
                if (!dispatchSyncOperation(op)) {
                    mSyncJobService.callJobFinished(op.jobId, false);
                }
            } else {
                mSyncJobService.callJobFinished(op.jobId, false);
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
            AccountAndUser[] oldAccounts = mRunningAccounts;
            mRunningAccounts = AccountManagerService.getSingleton().getRunningAccounts();
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "Accounts list: ");
                for (AccountAndUser acc : mRunningAccounts) {
                    Slog.v(TAG, acc.toString());
                }
            }
            if (mBootCompleted) {
                doDatabaseCleanup();
            }

            AccountAndUser[] accounts = mRunningAccounts;
            for (ActiveSyncContext currentSyncContext : mActiveSyncContexts) {
                if (!containsAccountAndUser(accounts,
                        currentSyncContext.mSyncOperation.target.account,
                        currentSyncContext.mSyncOperation.target.userId)) {
                    Log.d(TAG, "canceling sync since the account is no longer running");
                    sendSyncFinishedOrCanceledMessage(currentSyncContext,
                            null /* no result since this is a cancel */);
                }
            }

            // On account add, check if there are any settings to be restored.
            for (AccountAndUser aau : mRunningAccounts) {
                if (!containsAccountAndUser(oldAccounts, aau.account, aau.userId)) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Account " + aau.account + " added, checking sync restore data");
                    }
                    AccountSyncSettingsBackupHelper.accountAdded(mContext);
                    break;
                }
            }

            // Cancel all jobs from non-existent accounts.
            AccountAndUser[] allAccounts = AccountManagerService.getSingleton().getAllAccounts();
            List<SyncOperation> ops = getAllPendingSyncs();
            for (SyncOperation op: ops) {
                if (!containsAccountAndUser(allAccounts, op.target.account, op.target.userId)) {
                    getJobScheduler().cancel(op.jobId);
                }
            }

            if (syncTargets != null) {
                scheduleSync(syncTargets.account, syncTargets.userId,
                        SyncOperation.REASON_ACCOUNTS_UPDATED, syncTargets.provider, null, 0, 0,
                        true);
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
                        && syncExtrasEquals(op.extras, extras, true /* includeSyncSettings */)) {
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
                    pollFrequencyMillis, flexMillis);
            scheduleSyncOperationH(op);
            mSyncStorageEngine.reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
        }

        /**
         * Remove this periodic sync operation and all one-off operations initiated by it.
         */
        private void removePeriodicSyncInternalH(SyncOperation syncOperation) {
            // Remove this periodic sync and all one-off syncs initiated by it.
            List<SyncOperation> ops = getAllPendingSyncs();
            for (SyncOperation op: ops) {
                if (op.sourcePeriodicId == syncOperation.jobId || op.jobId == syncOperation.jobId) {
                    ActiveSyncContext asc = findActiveSyncContextH(syncOperation.jobId);
                    if (asc != null) {
                        mSyncJobService.callJobFinished(syncOperation.jobId, false);
                        runSyncFinishedOrCanceledH(null, asc);
                    }
                    getJobScheduler().cancel(op.jobId);
                }
            }
        }

        private void removePeriodicSyncH(EndPoint target, Bundle extras) {
            verifyJobScheduler();
            List<SyncOperation> ops = getAllPendingSyncs();
            for (SyncOperation op: ops) {
                if (op.isPeriodic && op.target.matchesSpec(target)
                        && syncExtrasEquals(op.extras, extras, true /* includeSyncSettings */)) {
                    removePeriodicSyncInternalH(op);
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
        private boolean isOperationValid(SyncOperation op) {
            final boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
            int state;
            final EndPoint target = op.target;
            boolean syncEnabled = mSyncStorageEngine.getMasterSyncAutomatically(target.userId);
            // Drop the sync if the account of this operation no longer exists.
            AccountAndUser[] accounts = mRunningAccounts;
            if (!containsAccountAndUser(accounts, target.account, target.userId)) {
                if (isLoggable) {
                    Slog.v(TAG, "    Dropping sync operation: account doesn't exist.");
                }
                return false;
            }
            // Drop this sync request if it isn't syncable.
            state = getIsSyncable(target.account, target.userId, target.provider);
            if (state == 0) {
                if (isLoggable) {
                    Slog.v(TAG, "    Dropping sync operation: isSyncable == 0.");
                }
                return false;
            }
            syncEnabled = syncEnabled && mSyncStorageEngine.getSyncAutomatically(
                    target.account, target.userId, target.provider);

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
                return false;
            }
            return true;
        }

        private boolean dispatchSyncOperation(SyncOperation op) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "dispatchSyncOperation: we are going to sync " + op);
                Slog.v(TAG, "num active syncs: " + mActiveSyncContexts.size());
                for (ActiveSyncContext syncContext : mActiveSyncContexts) {
                    Slog.v(TAG, syncContext.toString());
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

                activeSyncContext.mSyncAdapter = ISyncAdapter.Stub.asInterface(syncAdapter);
                activeSyncContext.mSyncAdapter
                        .startSync(activeSyncContext, syncOperation.target.provider,
                                syncOperation.target.account, syncOperation.extras);
            } catch (RemoteException remoteExc) {
                Log.d(TAG, "maybeStartNextSync: caught a RemoteException, rescheduling", remoteExc);
                closeActiveSyncContext(activeSyncContext);
                increaseBackoffSetting(syncOperation.target);
                scheduleSyncOperationH(syncOperation);
            } catch (RuntimeException exc) {
                closeActiveSyncContext(activeSyncContext);
                Slog.e(TAG, "Caught RuntimeException while starting the sync " + syncOperation, exc);
            }
        }

        /**
         * Cancel the sync for the provided target that matches the given bundle.
         * @param info Can have null fields to indicate all the active syncs for that field.
         * @param extras Can be null to indicate <strong>all</strong> syncs for the given endpoint.
         */
        private void cancelActiveSyncH(SyncStorageEngine.EndPoint info, Bundle extras) {
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
                            !syncExtrasEquals(activeSyncContext.mSyncOperation.extras,
                                    extras,
                                    false /* no config settings */)) {
                        continue;
                    }
                    mSyncJobService.callJobFinished(activeSyncContext.mSyncOperation.jobId, false);
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
            closeActiveSyncContext(activeSyncContext);
            final long elapsedTime = SystemClock.elapsedRealtime() - activeSyncContext.mStartTime;
            String historyMessage;
            int downstreamActivity;
            int upstreamActivity;

            if (!syncOperation.isPeriodic) {
                // mSyncJobService.jobFinidhed is async, we need to ensure that this job is
                // removed from JobScheduler's pending jobs list before moving forward and
                // potentially rescheduling all pending jobs to respect new backoff values.
                getJobScheduler().cancel(syncOperation.jobId);
            }

            if (syncResult != null) {
                if (isLoggable) {
                    Slog.v(TAG, "runSyncFinishedOrCanceled [finished]: "
                            + syncOperation + ", result " + syncResult);
                }

                if (!syncResult.hasError()) {
                    historyMessage = SyncStorageEngine.MESG_SUCCESS;
                    // TODO: set these correctly when the SyncResult is extended to include it
                    downstreamActivity = 0;
                    upstreamActivity = 0;
                    clearBackoffSetting(syncOperation.target);

                    // If the operation completes successfully and it was scheduled due to
                    // a periodic operation failing, we reschedule the periodic operation to
                    // start from now.
                    if (syncOperation.isDerivedFromFailedPeriodicSync()) {
                        reschedulePeriodicSyncH(syncOperation);
                    }
                } else {
                    Log.d(TAG, "failed sync operation " + syncOperation + ", " + syncResult);
                    // the operation failed so increase the backoff time
                    increaseBackoffSetting(syncOperation.target);
                    if (!syncOperation.isPeriodic) {
                        // reschedule the sync if so indicated by the syncResult
                        maybeRescheduleSync(syncResult, syncOperation);
                    } else {
                        // create a normal sync instance that will respect adapter backoffs
                        postScheduleSyncMessage(syncOperation.createOneTimeSyncOperation());
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
                if (activeSyncContext.mSyncAdapter != null) {
                    try {
                        activeSyncContext.mSyncAdapter.cancelSync(activeSyncContext);
                    } catch (RemoteException e) {
                        // we don't need to retry this in this case
                    }
                }
                historyMessage = SyncStorageEngine.MESG_CANCELED;
                downstreamActivity = 0;
                upstreamActivity = 0;
            }

            stopSyncEvent(activeSyncContext.mHistoryRowId, syncOperation, historyMessage,
                    upstreamActivity, downstreamActivity, elapsedTime);
            // Check for full-resync and schedule it after closing off the last sync.
            if (syncResult != null && syncResult.tooManyDeletions) {
                installHandleTooManyDeletesNotification(info.account,
                        info.provider, syncResult.stats.numDeletes,
                        info.userId);
            } else {
                mNotificationMgr.cancelAsUser(null,
                        info.account.hashCode() ^ info.provider.hashCode(),
                        new UserHandle(info.userId));
            }
            if (syncResult != null && syncResult.fullSyncRequested) {
                scheduleSyncOperationH(
                        new SyncOperation(info.account, info.userId,
                                syncOperation.owningUid, syncOperation.owningPackage,
                                syncOperation.reason,
                                syncOperation.syncSource, info.provider, new Bundle(),
                                syncOperation.allowParallelSyncs));
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
                            PendingIntent.FLAG_CANCEL_CURRENT, null, user);

            CharSequence tooManyDeletesDescFormat = mContext.getResources().getText(
                    R.string.contentServiceTooManyDeletesNotificationDesc);

            Context contextForUser = getContextForUser(user);
            Notification notification = new Notification.Builder(contextForUser)
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
            mNotificationMgr.notifyAsUser(null, account.hashCode() ^ authority.hashCode(),
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
                    resultMessage, downstreamActivity, upstreamActivity);
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
        if (key.equals(ContentResolver.SYNC_EXTRAS_EXPEDITED)) {
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
        return false;
    }

    static class PrintTable {
        private ArrayList<Object[]> mTable = Lists.newArrayList();
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
                final Object[] list = new Object[mCols];
                mTable.add(list);
                for (int j = 0; j < mCols; j++) {
                    list[j] = "";
                }
            }
            System.arraycopy(values, 0, mTable.get(row), col, values.length);
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
}