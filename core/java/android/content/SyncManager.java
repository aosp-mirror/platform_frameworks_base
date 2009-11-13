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

package android.content;

import com.google.android.collect.Maps;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.RegisteredServicesCache;
import android.content.pm.ProviderInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Config;
import android.util.EventLog;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Collection;
import java.util.concurrent.CountDownLatch;

/**
 * @hide
 */
class SyncManager implements OnAccountsUpdateListener {
    private static final String TAG = "SyncManager";

    // used during dumping of the Sync history
    private static final long MILLIS_IN_HOUR = 1000 * 60 * 60;
    private static final long MILLIS_IN_DAY = MILLIS_IN_HOUR * 24;
    private static final long MILLIS_IN_WEEK = MILLIS_IN_DAY * 7;
    private static final long MILLIS_IN_4WEEKS = MILLIS_IN_WEEK * 4;

    /** Delay a sync due to local changes this long. In milliseconds */
    private static final long LOCAL_SYNC_DELAY;

    /**
     * If a sync takes longer than this and the sync queue is not empty then we will
     * cancel it and add it back to the end of the sync queue. In milliseconds.
     */
    private static final long MAX_TIME_PER_SYNC;

    static {
        String localSyncDelayString = SystemProperties.get("sync.local_sync_delay");
        long localSyncDelay = 30 * 1000; // 30 seconds
        if (localSyncDelayString != null) {
            try {
                localSyncDelay = Long.parseLong(localSyncDelayString);
            } catch (NumberFormatException nfe) {
                // ignore, use default
            }
        }
        LOCAL_SYNC_DELAY = localSyncDelay;

        String maxTimePerSyncString = SystemProperties.get("sync.max_time_per_sync");
        long maxTimePerSync = 5 * 60 * 1000; // 5 minutes
        if (maxTimePerSyncString != null) {
            try {
                maxTimePerSync = Long.parseLong(maxTimePerSyncString);
            } catch (NumberFormatException nfe) {
                // ignore, use default
            }
        }
        MAX_TIME_PER_SYNC = maxTimePerSync;
    }

    private static final long SYNC_NOTIFICATION_DELAY = 30 * 1000; // 30 seconds

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
     * An error notification is sent if sync of any of the providers has been failing for this long.
     */
    private static final long ERROR_NOTIFICATION_DELAY_MS = 1000 * 60 * 10; // 10 minutes

    private static final String SYNC_WAKE_LOCK = "SyncManagerSyncWakeLock";
    private static final String HANDLE_SYNC_ALARM_WAKE_LOCK = "SyncManagerHandleSyncAlarmWakeLock";

    private Context mContext;

    private String mStatusText = "";
    private long mHeartbeatTime = 0;

    private volatile Account[] mAccounts = null;

    volatile private PowerManager.WakeLock mSyncWakeLock;
    volatile private PowerManager.WakeLock mHandleAlarmWakeLock;
    volatile private boolean mDataConnectionIsConnected = false;
    volatile private boolean mStorageIsLow = false;

    private final NotificationManager mNotificationMgr;
    private AlarmManager mAlarmService = null;
    private HandlerThread mSyncThread;

    private volatile IPackageManager mPackageManager;

    private final SyncStorageEngine mSyncStorageEngine;
    private final SyncQueue mSyncQueue;

    private ActiveSyncContext mActiveSyncContext = null;

    // set if the sync error indicator should be reported.
    private boolean mNeedSyncErrorNotification = false;
    // set if the sync active indicator should be reported
    private boolean mNeedSyncActiveNotification = false;

    private volatile boolean mSyncPollInitialized;
    private final PendingIntent mSyncAlarmIntent;
    private final PendingIntent mSyncPollAlarmIntent;
    // Synchronized on "this". Instead of using this directly one should instead call
    // its accessor, getConnManager().
    private ConnectivityManager mConnManagerDoNotUseDirectly;

    private final SyncAdaptersCache mSyncAdapters;

    private BroadcastReceiver mStorageIntentReceiver =
            new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "Internal storage is low.");
                        }
                        mStorageIsLow = true;
                        cancelActiveSync(null /* any account */, null /* any authority */);
                    } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "Internal storage is ok.");
                        }
                        mStorageIsLow = false;
                        sendCheckAlarmsMessage();
                    }
                }
            };

    private BroadcastReceiver mBootCompletedReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            mSyncHandler.onBootCompleted();
        }
    };

    private BroadcastReceiver mBackgroundDataSettingChanged = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (getConnectivityManager().getBackgroundDataSetting()) {
                scheduleSync(null /* account */, null /* authority */, new Bundle(), 0 /* delay */,
                        false /* onlyThoseWithUnknownSyncableState */);
            }
        }
    };

    public void onAccountsUpdated(Account[] accounts) {
        // remember if this was the first time this was called after an update
        final boolean justBootedUp = mAccounts == null;
        mAccounts = accounts;

        // if a sync is in progress yet it is no longer in the accounts list,
        // cancel it
        ActiveSyncContext activeSyncContext = mActiveSyncContext;
        if (activeSyncContext != null) {
            if (!ArrayUtils.contains(accounts, activeSyncContext.mSyncOperation.account)) {
                Log.d(TAG, "canceling sync since the account has been removed");
                sendSyncFinishedOrCanceledMessage(activeSyncContext,
                        null /* no result since this is a cancel */);
            }
        }

        // we must do this since we don't bother scheduling alarms when
        // the accounts are not set yet
        sendCheckAlarmsMessage();

        mSyncStorageEngine.doDatabaseCleanup(accounts);

        if (accounts.length > 0) {
            // If this is the first time this was called after a bootup then
            // the accounts haven't really changed, instead they were just loaded
            // from the AccountManager. Otherwise at least one of the accounts
            // has a change.
            //
            // If there was a real account change then force a sync of all accounts.
            // This is a bit of overkill, but at least it will end up retrying syncs
            // that failed due to an authentication failure and thus will recover if the
            // account change was a password update.
            //
            // If this was the bootup case then don't sync everything, instead only
            // sync those that have an unknown syncable state, which will give them
            // a chance to set their syncable state.
            boolean onlyThoseWithUnkownSyncableState = justBootedUp;
            scheduleSync(null, null, null, 0 /* no delay */, onlyThoseWithUnkownSyncableState);
        }
    }

    private BroadcastReceiver mConnectivityIntentReceiver =
            new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            NetworkInfo networkInfo =
                    intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            NetworkInfo.State state = (networkInfo == null ? NetworkInfo.State.UNKNOWN :
                    networkInfo.getState());
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "received connectivity action.  network info: " + networkInfo);
            }

            // only pay attention to the CONNECTED and DISCONNECTED states.
            // if connected, we are connected.
            // if disconnected, we may not be connected.  in some cases, we may be connected on
            // a different network.
            // e.g., if switching from GPRS to WiFi, we may receive the CONNECTED to WiFi and
            // DISCONNECTED for GPRS in any order.  if we receive the CONNECTED first, and then
            // a DISCONNECTED, we want to make sure we set mDataConnectionIsConnected to true
            // since we still have a WiFi connection.
            switch (state) {
                case CONNECTED:
                    mDataConnectionIsConnected = true;
                    break;
                case DISCONNECTED:
                    if (intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) {
                        mDataConnectionIsConnected = false;
                    } else {
                        mDataConnectionIsConnected = true;
                    }
                    break;
                default:
                    // ignore the rest of the states -- leave our boolean alone.
            }
            if (mDataConnectionIsConnected) {
                initializeSyncPoll();
                sendCheckAlarmsMessage();
            }
        }
    };

    private BroadcastReceiver mShutdownIntentReceiver =
            new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            Log.w(TAG, "Writing sync state before shutdown...");
            getSyncStorageEngine().writeAllState();
        }
    };

    private static final String ACTION_SYNC_ALARM = "android.content.syncmanager.SYNC_ALARM";
    private static final String SYNC_POLL_ALARM = "android.content.syncmanager.SYNC_POLL_ALARM";
    private final SyncHandler mSyncHandler;

    private static final int MAX_SYNC_POLL_DELAY_SECONDS = 36 * 60 * 60; // 36 hours
    private static final int MIN_SYNC_POLL_DELAY_SECONDS = 24 * 60 * 60; // 24 hours

    private static final String SYNCMANAGER_PREFS_FILENAME = "/data/system/syncmanager.prefs";

    private final boolean mFactoryTest;

    private volatile boolean mBootCompleted = false;

    private ConnectivityManager getConnectivityManager() {
        synchronized (this) {
            if (mConnManagerDoNotUseDirectly == null) {
                mConnManagerDoNotUseDirectly = (ConnectivityManager)mContext.getSystemService(
                        Context.CONNECTIVITY_SERVICE);
            }
            return mConnManagerDoNotUseDirectly;
        }
    }

    public SyncManager(Context context, boolean factoryTest) {
        mFactoryTest = factoryTest;

        // Initialize the SyncStorageEngine first, before registering observers
        // and creating threads and so on; it may fail if the disk is full.
        SyncStorageEngine.init(context);
        mSyncStorageEngine = SyncStorageEngine.getSingleton();
        mSyncQueue = new SyncQueue(mSyncStorageEngine);

        mContext = context;

        mSyncThread = new HandlerThread("SyncHandlerThread", Process.THREAD_PRIORITY_BACKGROUND);
        mSyncThread.start();
        mSyncHandler = new SyncHandler(mSyncThread.getLooper());

        mPackageManager = null;

        mSyncAdapters = new SyncAdaptersCache(mContext);

        mSyncAlarmIntent = PendingIntent.getBroadcast(
                mContext, 0 /* ignored */, new Intent(ACTION_SYNC_ALARM), 0);

        mSyncPollAlarmIntent = PendingIntent.getBroadcast(
                mContext, 0 /* ignored */, new Intent(SYNC_POLL_ALARM), 0);

        IntentFilter intentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(mConnectivityIntentReceiver, intentFilter);

        if (!factoryTest) {
            intentFilter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
            context.registerReceiver(mBootCompletedReceiver, intentFilter);
        }

        intentFilter = new IntentFilter(ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED);
        context.registerReceiver(mBackgroundDataSettingChanged, intentFilter);

        intentFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        context.registerReceiver(mStorageIntentReceiver, intentFilter);

        intentFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        intentFilter.setPriority(100);
        context.registerReceiver(mShutdownIntentReceiver, intentFilter);

        if (!factoryTest) {
            mNotificationMgr = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            context.registerReceiver(new SyncAlarmIntentReceiver(),
                    new IntentFilter(ACTION_SYNC_ALARM));
        } else {
            mNotificationMgr = null;
        }
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mSyncWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, SYNC_WAKE_LOCK);
        mSyncWakeLock.setReferenceCounted(false);

        // This WakeLock is used to ensure that we stay awake between the time that we receive
        // a sync alarm notification and when we finish processing it. We need to do this
        // because we don't do the work in the alarm handler, rather we do it in a message
        // handler.
        mHandleAlarmWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                HANDLE_SYNC_ALARM_WAKE_LOCK);
        mHandleAlarmWakeLock.setReferenceCounted(false);

        mSyncStorageEngine.addStatusChangeListener(
                ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS, new ISyncStatusObserver.Stub() {
            public void onStatusChanged(int which) {
                // force the sync loop to run if the settings change
                sendCheckAlarmsMessage();
            }
        });

        if (!factoryTest) {
            AccountManager.get(mContext).addOnAccountsUpdatedListener(SyncManager.this,
                mSyncHandler, false /* updateImmediately */);
            // do this synchronously to ensure we have the accounts before this call returns
            onAccountsUpdated(AccountManager.get(mContext).getAccounts());
        }
    }

    private synchronized void initializeSyncPoll() {
        if (mSyncPollInitialized) return;
        mSyncPollInitialized = true;

        mContext.registerReceiver(new SyncPollAlarmReceiver(), new IntentFilter(SYNC_POLL_ALARM));

        // load the next poll time from shared preferences
        long absoluteAlarmTime = readSyncPollTime();

        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "initializeSyncPoll: absoluteAlarmTime is " + absoluteAlarmTime);
        }

        // Convert absoluteAlarmTime to elapsed realtime. If this time was in the past then
        // schedule the poll immediately, if it is too far in the future then cap it at
        // MAX_SYNC_POLL_DELAY_SECONDS.
        long absoluteNow = System.currentTimeMillis();
        long relativeNow = SystemClock.elapsedRealtime();
        long relativeAlarmTime = relativeNow;
        if (absoluteAlarmTime > absoluteNow) {
            long delayInMs = absoluteAlarmTime - absoluteNow;
            final int maxDelayInMs = MAX_SYNC_POLL_DELAY_SECONDS * 1000;
            if (delayInMs > maxDelayInMs) {
                delayInMs = MAX_SYNC_POLL_DELAY_SECONDS * 1000;
            }
            relativeAlarmTime += delayInMs;
        }

        // schedule an alarm for the next poll time
        scheduleSyncPollAlarm(relativeAlarmTime);
    }

    private void scheduleSyncPollAlarm(long relativeAlarmTime) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "scheduleSyncPollAlarm: relativeAlarmTime is " + relativeAlarmTime
                    + ", now is " + SystemClock.elapsedRealtime()
                    + ", delay is " + (relativeAlarmTime - SystemClock.elapsedRealtime()));
        }
        ensureAlarmService();
        mAlarmService.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, relativeAlarmTime,
                mSyncPollAlarmIntent);
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

    private void handleSyncPollAlarm() {
        // determine the next poll time
        long delayMs = jitterize(MIN_SYNC_POLL_DELAY_SECONDS, MAX_SYNC_POLL_DELAY_SECONDS) * 1000;
        long nextRelativePollTimeMs = SystemClock.elapsedRealtime() + delayMs;

        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "handleSyncPollAlarm: delay " + delayMs);

        // write the absolute time to shared preferences
        writeSyncPollTime(System.currentTimeMillis() + delayMs);

        // schedule an alarm for the next poll time
        scheduleSyncPollAlarm(nextRelativePollTimeMs);

        // perform a poll
        scheduleSync(null /* sync all syncable accounts */, null /* sync all syncable providers */,
                new Bundle(), 0 /* no delay */, false /* onlyThoseWithUnkownSyncableState */);
    }

    private void writeSyncPollTime(long when) {
        File f = new File(SYNCMANAGER_PREFS_FILENAME);
        DataOutputStream str = null;
        try {
            str = new DataOutputStream(new FileOutputStream(f));
            str.writeLong(when);
        } catch (FileNotFoundException e) {
            Log.w(TAG, "error writing to file " + f, e);
        } catch (IOException e) {
            Log.w(TAG, "error writing to file " + f, e);
        } finally {
            if (str != null) {
                try {
                    str.close();
                } catch (IOException e) {
                    Log.w(TAG, "error closing file " + f, e);
                }
            }
        }
    }

    private long readSyncPollTime() {
        File f = new File(SYNCMANAGER_PREFS_FILENAME);

        DataInputStream str = null;
        try {
            str = new DataInputStream(new FileInputStream(f));
            return str.readLong();
        } catch (FileNotFoundException e) {
            writeSyncPollTime(0);
        } catch (IOException e) {
            Log.w(TAG, "error reading file " + f, e);
        } finally {
            if (str != null) {
                try {
                    str.close();
                } catch (IOException e) {
                    Log.w(TAG, "error closing file " + f, e);
                }
            }
        }
        return 0;
    }

    public ActiveSyncContext getActiveSyncContext() {
        return mActiveSyncContext;
    }

    public SyncStorageEngine getSyncStorageEngine() {
        return mSyncStorageEngine;
    }

    private void ensureAlarmService() {
        if (mAlarmService == null) {
            mAlarmService = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        }
    }

    public Account getSyncingAccount() {
        ActiveSyncContext activeSyncContext = mActiveSyncContext;
        return (activeSyncContext != null) ? activeSyncContext.mSyncOperation.account : null;
    }

    /**
     * Returns whether or not sync is enabled.  Sync can be enabled by
     * setting the system property "ro.config.sync" to the value "yes".
     * This is normally done at boot time on builds that support sync.
     * @return true if sync is enabled
     */
    private boolean isSyncEnabled() {
        // Require the precise value "yes" to discourage accidental activation.
        return "yes".equals(SystemProperties.get("ro.config.sync"));
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
     * @param requestedAuthority the authority to sync, may be null to indicate all authorities
     * @param extras a Map of SyncAdapter-specific information to control
*          syncs of a specific provider. Can be null. Is ignored
*          if the url is null.
     * @param delay how many milliseconds in the future to wait before performing this
     * @param onlyThoseWithUnkownSyncableState
     */
    public void scheduleSync(Account requestedAccount, String requestedAuthority,
            Bundle extras, long delay, boolean onlyThoseWithUnkownSyncableState) {
        boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);

        if (mAccounts == null) {
            Log.e(TAG, "scheduleSync: the accounts aren't known yet, this should never happen");
            return;
        }

        if (!isSyncEnabled()) {
            if (isLoggable) {
                Log.v(TAG, "not syncing because sync is disabled");
            }
            setStatusText("Sync is disabled.");
            return;
        }

        final boolean backgroundDataUsageAllowed = !mBootCompleted ||
                getConnectivityManager().getBackgroundDataSetting();

        if (!mDataConnectionIsConnected) setStatusText("No data connection");
        if (mStorageIsLow) setStatusText("Memory low");

        if (extras == null) extras = new Bundle();

        Boolean expedited = extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false);
        if (expedited) {
            delay = -1; // this means schedule at the front of the queue
        }

        Account[] accounts;
        if (requestedAccount != null) {
            accounts = new Account[]{requestedAccount};
        } else {
            // if the accounts aren't configured yet then we can't support an account-less
            // sync request
            accounts = mAccounts;
            if (accounts == null) {
                // not ready yet
                if (isLoggable) {
                    Log.v(TAG, "scheduleSync: no accounts yet, dropping");
                }
                return;
            }
            if (accounts.length == 0) {
                if (isLoggable) {
                    Log.v(TAG, "scheduleSync: no accounts configured, dropping");
                }
                setStatusText("No accounts are configured.");
                return;
            }
        }

        final boolean uploadOnly = extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false);
        final boolean manualSync = extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);

        int source;
        if (uploadOnly) {
            source = SyncStorageEngine.SOURCE_LOCAL;
        } else if (manualSync) {
            source = SyncStorageEngine.SOURCE_USER;
        } else if (requestedAuthority == null) {
            source = SyncStorageEngine.SOURCE_POLL;
        } else {
            // this isn't strictly server, since arbitrary callers can (and do) request
            // a non-forced two-way sync on a specific url
            source = SyncStorageEngine.SOURCE_SERVER;
        }

        // Compile a list of authorities that have sync adapters.
        // For each authority sync each account that matches a sync adapter.
        final HashSet<String> syncableAuthorities = new HashSet<String>();
        for (RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapter :
                mSyncAdapters.getAllServices()) {
            syncableAuthorities.add(syncAdapter.type.authority);
        }

        // if the url was specified then replace the list of authorities with just this authority
        // or clear it if this authority isn't syncable
        if (requestedAuthority != null) {
            final boolean hasSyncAdapter = syncableAuthorities.contains(requestedAuthority);
            syncableAuthorities.clear();
            if (hasSyncAdapter) syncableAuthorities.add(requestedAuthority);
        }

        final boolean masterSyncAutomatically = mSyncStorageEngine.getMasterSyncAutomatically();

        for (String authority : syncableAuthorities) {
            for (Account account : accounts) {
                int isSyncable = mSyncStorageEngine.getIsSyncable(account, authority);
                if (isSyncable == 0) {
                    continue;
                }
                if (onlyThoseWithUnkownSyncableState && isSyncable >= 0) {
                    continue;
                }
                final RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo =
                        mSyncAdapters.getServiceInfo(
                                SyncAdapterType.newKey(authority, account.type));
                if (syncAdapterInfo != null) {
                    if (!syncAdapterInfo.type.supportsUploading() && uploadOnly) {
                        continue;
                    }

                    // make this an initialization sync if the isSyncable state is unknown
                    Bundle extrasCopy = extras;
                    long delayCopy = delay;
                    if (isSyncable < 0) {
                        extrasCopy = new Bundle(extras);
                        extrasCopy.putBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, true);
                        delayCopy = -1; // expedite this
                    } else {
                        final boolean syncAutomatically = masterSyncAutomatically
                                && mSyncStorageEngine.getSyncAutomatically(account, authority);
                        boolean syncAllowed =
                                manualSync || (backgroundDataUsageAllowed && syncAutomatically);
                        if (!syncAllowed) {
                            if (isLoggable) {
                                Log.d(TAG, "scheduleSync: sync of " + account + ", " + authority
                                        + " is not allowed, dropping request");
                            }
                            continue;
                        }
                    }
                    if (isLoggable) {
                        Log.v(TAG, "scheduleSync:"
                                + " delay " + delayCopy
                                + ", source " + source
                                + ", account " + account
                                + ", authority " + authority
                                + ", extras " + extrasCopy);
                    }
                    scheduleSyncOperation(
                            new SyncOperation(account, source, authority, extrasCopy, delayCopy));
                }
            }
        }
    }

    private void setStatusText(String message) {
        mStatusText = message;
    }

    public void scheduleLocalSync(Account account, String authority) {
        final Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true);
        scheduleSync(account, authority, extras, LOCAL_SYNC_DELAY,
                false /* onlyThoseWithUnkownSyncableState */);
    }

    private IPackageManager getPackageManager() {
        // Don't bother synchronizing on this. The worst that can happen is that two threads
        // can try to get the package manager at the same time but only one result gets
        // used. Since there is only one package manager in the system this doesn't matter.
        if (mPackageManager == null) {
            IBinder b = ServiceManager.getService("package");
            mPackageManager = IPackageManager.Stub.asInterface(b);
        }
        return mPackageManager;
    }

    public SyncAdapterType[] getSyncAdapterTypes() {
        final Collection<RegisteredServicesCache.ServiceInfo<SyncAdapterType>> serviceInfos =
                mSyncAdapters.getAllServices();
        SyncAdapterType[] types = new SyncAdapterType[serviceInfos.size()];
        int i = 0;
        for (RegisteredServicesCache.ServiceInfo<SyncAdapterType> serviceInfo : serviceInfos) {
            types[i] = serviceInfo.type;
            ++i;
        }
        return types;
    }

    public void updateHeartbeatTime() {
        mHeartbeatTime = SystemClock.elapsedRealtime();
        mSyncStorageEngine.reportActiveChange();
    }

    private void sendSyncAlarmMessage() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "sending MESSAGE_SYNC_ALARM");
        mSyncHandler.sendEmptyMessage(SyncHandler.MESSAGE_SYNC_ALARM);
    }

    private void sendCheckAlarmsMessage() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "sending MESSAGE_CHECK_ALARMS");
        mSyncHandler.sendEmptyMessage(SyncHandler.MESSAGE_CHECK_ALARMS);
    }

    private void sendSyncFinishedOrCanceledMessage(ActiveSyncContext syncContext,
            SyncResult syncResult) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "sending MESSAGE_SYNC_FINISHED");
        Message msg = mSyncHandler.obtainMessage();
        msg.what = SyncHandler.MESSAGE_SYNC_FINISHED;
        msg.obj = new SyncHandlerMessagePayload(syncContext, syncResult);
        mSyncHandler.sendMessage(msg);
    }

    class SyncHandlerMessagePayload {
        public final ActiveSyncContext activeSyncContext;
        public final SyncResult syncResult;

        SyncHandlerMessagePayload(ActiveSyncContext syncContext, SyncResult syncResult) {
            this.activeSyncContext = syncContext;
            this.syncResult = syncResult;
        }
    }

    class SyncAlarmIntentReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            mHandleAlarmWakeLock.acquire();
            sendSyncAlarmMessage();
        }
    }

    class SyncPollAlarmReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            handleSyncPollAlarm();
        }
    }

    private void rescheduleImmediately(SyncOperation syncOperation) {
        SyncOperation rescheduledSyncOperation = new SyncOperation(syncOperation);
        rescheduledSyncOperation.setDelay(0);
        scheduleSyncOperation(rescheduledSyncOperation);
    }

    private long rescheduleWithDelay(SyncOperation syncOperation) {
        long newDelayInMs;

        if (syncOperation.delay <= 0) {
            // The initial delay is the jitterized INITIAL_SYNC_RETRY_TIME_IN_MS
            newDelayInMs = jitterize(INITIAL_SYNC_RETRY_TIME_IN_MS,
                    (long)(INITIAL_SYNC_RETRY_TIME_IN_MS * 1.1));
        } else {
            // Subsequent delays are the double of the previous delay
            newDelayInMs = syncOperation.delay * 2;
        }

        // Cap the delay
        long maxSyncRetryTimeInSeconds = Settings.Gservices.getLong(mContext.getContentResolver(),
                Settings.Gservices.SYNC_MAX_RETRY_DELAY_IN_SECONDS,
                DEFAULT_MAX_SYNC_RETRY_TIME_IN_SECONDS);
        if (newDelayInMs > maxSyncRetryTimeInSeconds * 1000) {
            newDelayInMs = maxSyncRetryTimeInSeconds * 1000;
        }

        SyncOperation rescheduledSyncOperation = new SyncOperation(syncOperation);
        rescheduledSyncOperation.setDelay(newDelayInMs);
        scheduleSyncOperation(rescheduledSyncOperation);
        return newDelayInMs;
    }

    /**
     * Cancel the active sync if it matches the authority and account.
     * @param account limit the cancelations to syncs with this account, if non-null
     * @param authority limit the cancelations to syncs with this authority, if non-null
     */
    public void cancelActiveSync(Account account, String authority) {
        ActiveSyncContext activeSyncContext = mActiveSyncContext;
        if (activeSyncContext != null) {
            // if an authority was specified then only cancel the sync if it matches
            if (account != null) {
                if (!account.equals(activeSyncContext.mSyncOperation.account)) {
                    return;
                }
            }
            // if an account was specified then only cancel the sync if it matches
            if (authority != null) {
                if (!authority.equals(activeSyncContext.mSyncOperation.authority)) {
                    return;
                }
            }
            sendSyncFinishedOrCanceledMessage(activeSyncContext,
                    null /* no result since this is a cancel */);
        }
    }

    /**
     * Create and schedule a SyncOperation.
     *
     * @param syncOperation the SyncOperation to schedule
     */
    public void scheduleSyncOperation(SyncOperation syncOperation) {
        // If this operation is expedited and there is a sync in progress then
        // reschedule the current operation and send a cancel for it.
        final boolean expedited = syncOperation.delay < 0;
        final ActiveSyncContext activeSyncContext = mActiveSyncContext;
        if (expedited && activeSyncContext != null) {
            final boolean activeIsExpedited = activeSyncContext.mSyncOperation.delay < 0;
            final boolean hasSameKey =
                    activeSyncContext.mSyncOperation.key.equals(syncOperation.key);
            // This request is expedited and there is a sync in progress.
            // Interrupt the current sync only if it is not expedited and if it has a different
            // key than the one we are scheduling.
            if (!activeIsExpedited && !hasSameKey) {
                rescheduleImmediately(activeSyncContext.mSyncOperation);
                sendSyncFinishedOrCanceledMessage(activeSyncContext,
                        null /* no result since this is a cancel */);
            }
        }

        boolean operationEnqueued;
        synchronized (mSyncQueue) {
            operationEnqueued = mSyncQueue.add(syncOperation);
        }

        if (operationEnqueued) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "scheduleSyncOperation: enqueued " + syncOperation);
            }
            sendCheckAlarmsMessage();
        } else {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "scheduleSyncOperation: dropping duplicate sync operation "
                        + syncOperation);
            }
        }
    }

    /**
     * Remove scheduled sync operations.
     * @param account limit the removals to operations with this account, if non-null
     * @param authority limit the removals to operations with this authority, if non-null
     */
    public void clearScheduledSyncOperations(Account account, String authority) {
        synchronized (mSyncQueue) {
            mSyncQueue.clear(account, authority);
        }
    }

    void maybeRescheduleSync(SyncResult syncResult, SyncOperation previousSyncOperation) {
        boolean isLoggable = Log.isLoggable(TAG, Log.DEBUG);
        if (isLoggable) {
            Log.d(TAG, "encountered error(s) during the sync: " + syncResult + ", "
                    + previousSyncOperation);
        }

        // If the operation succeeded to some extent then retry immediately.
        // If this was a two-way sync then retry soft errors with an exponential backoff.
        // If this was an upward sync then schedule a two-way sync immediately.
        // Otherwise do not reschedule.

        if (syncResult.madeSomeProgress()) {
            if (isLoggable) {
                Log.d(TAG, "retrying sync operation immediately because "
                        + "even though it had an error it achieved some success");
            }
            rescheduleImmediately(previousSyncOperation);
        } else if (previousSyncOperation.extras.getBoolean(
                ContentResolver.SYNC_EXTRAS_UPLOAD, false)) {
            final SyncOperation newSyncOperation = new SyncOperation(previousSyncOperation);
            newSyncOperation.extras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, false);
            newSyncOperation.setDelay(0);
            if (Config.LOGD) {
                Log.d(TAG, "retrying sync operation as a two-way sync because an upload-only sync "
                        + "encountered an error: " + previousSyncOperation);
            }
            scheduleSyncOperation(newSyncOperation);
        } else if (syncResult.hasSoftError()) {
            long delay = rescheduleWithDelay(previousSyncOperation);
            if (delay >= 0) {
                if (isLoggable) {
                    Log.d(TAG, "retrying sync operation in " + delay + " ms because "
                            + "it encountered a soft error: " + previousSyncOperation);
                }
            }
        } else {
            if (Config.LOGD) {
                Log.d(TAG, "not retrying sync operation because the error is a hard error: "
                        + previousSyncOperation);
            }
        }
    }

    /**
     * Value type that represents a sync operation.
     */
    static class SyncOperation implements Comparable {
        final Account account;
        int syncSource;
        String authority;
        Bundle extras;
        final String key;
        long earliestRunTime;
        long delay;
        SyncStorageEngine.PendingOperation pendingOperation;

        SyncOperation(Account account, int source, String authority, Bundle extras, long delay) {
            this.account = account;
            this.syncSource = source;
            this.authority = authority;
            this.extras = new Bundle(extras);
            this.setDelay(delay);
            this.key = toKey();
        }

        SyncOperation(SyncOperation other) {
            this.account = other.account;
            this.syncSource = other.syncSource;
            this.authority = other.authority;
            this.extras = new Bundle(other.extras);
            this.delay = other.delay;
            this.earliestRunTime = other.earliestRunTime;
            this.key = toKey();
        }

        public void setDelay(long delay) {
            this.delay = delay;
            if (delay >= 0) {
                this.earliestRunTime = SystemClock.elapsedRealtime() + delay;
            } else {
                this.earliestRunTime = 0;
            }
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("authority: ").append(authority);
            sb.append(" account: ").append(account);
            sb.append(" extras: ");
            extrasToStringBuilder(extras, sb);
            sb.append(" syncSource: ").append(syncSource);
            sb.append(" when: ").append(earliestRunTime);
            sb.append(" delay: ").append(delay);
            sb.append(" key: {").append(key).append("}");
            if (pendingOperation != null) sb.append(" pendingOperation: ").append(pendingOperation);
            return sb.toString();
        }

        private String toKey() {
            StringBuilder sb = new StringBuilder();
            sb.append("authority: ").append(authority);
            sb.append(" account: ").append(account);
            sb.append(" extras: ");
            extrasToStringBuilder(extras, sb);
            return sb.toString();
        }

        private static void extrasToStringBuilder(Bundle bundle, StringBuilder sb) {
            sb.append("[");
            for (String key : bundle.keySet()) {
                sb.append(key).append("=").append(bundle.get(key)).append(" ");
            }
            sb.append("]");
        }

        public int compareTo(Object o) {
            SyncOperation other = (SyncOperation)o;
            if (earliestRunTime == other.earliestRunTime) {
                return 0;
            }
            return (earliestRunTime < other.earliestRunTime) ? -1 : 1;
        }
    }

    /**
     * @hide
     */
    class ActiveSyncContext extends ISyncContext.Stub implements ServiceConnection {
        final SyncOperation mSyncOperation;
        final long mHistoryRowId;
        ISyncAdapter mSyncAdapter;
        final long mStartTime;
        long mTimeoutStartTime;

        public ActiveSyncContext(SyncOperation syncOperation,
                long historyRowId) {
            super();
            mSyncOperation = syncOperation;
            mHistoryRowId = historyRowId;
            mSyncAdapter = null;
            mStartTime = SystemClock.elapsedRealtime();
            mTimeoutStartTime = mStartTime;
        }

        public void sendHeartbeat() {
            // ignore this call if it corresponds to an old sync session
            if (mActiveSyncContext == this) {
                SyncManager.this.updateHeartbeatTime();
            }
        }

        public void onFinished(SyncResult result) {
            // include "this" in the message so that the handler can ignore it if this
            // ActiveSyncContext is no longer the mActiveSyncContext at message handling
            // time
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
            msg.obj = new ServiceConnectionData(this, ISyncAdapter.Stub.asInterface(service));
            mSyncHandler.sendMessage(msg);
        }

        public void onServiceDisconnected(ComponentName name) {
            Message msg = mSyncHandler.obtainMessage();
            msg.what = SyncHandler.MESSAGE_SERVICE_DISCONNECTED;
            msg.obj = new ServiceConnectionData(this, null);
            mSyncHandler.sendMessage(msg);
        }

        boolean bindToSyncAdapter(RegisteredServicesCache.ServiceInfo info) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "bindToSyncAdapter: " + info.componentName + ", connection " + this);
            }
            Intent intent = new Intent();
            intent.setAction("android.content.SyncAdapter");
            intent.setComponent(info.componentName);
            intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    com.android.internal.R.string.sync_binding_label);
            intent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                    mContext, 0, new Intent(Settings.ACTION_SYNC_SETTINGS), 0));
            return mContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
        }

        void unBindFromSyncAdapter() {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.d(TAG, "unBindFromSyncAdapter: connection " + this);
            }
            mContext.unbindService(this);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            toString(sb);
            return sb.toString();
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw) {
        StringBuilder sb = new StringBuilder();
        dumpSyncState(pw, sb);
        if (isSyncEnabled()) {
            dumpSyncHistory(pw, sb);
        }

        pw.println();
        pw.println("SyncAdapters:");
        for (RegisteredServicesCache.ServiceInfo info : mSyncAdapters.getAllServices()) {
            pw.println("  " + info);
        }
    }

    static String formatTime(long time) {
        Time tobj = new Time();
        tobj.set(time);
        return tobj.format("%Y-%m-%d %H:%M:%S");
    }

    protected void dumpSyncState(PrintWriter pw, StringBuilder sb) {
        pw.print("sync enabled: "); pw.println(isSyncEnabled());
        pw.print("data connected: "); pw.println(mDataConnectionIsConnected);
        pw.print("memory low: "); pw.println(mStorageIsLow);

        final Account[] accounts = mAccounts;
        pw.print("accounts: ");
        if (accounts != null) {
            pw.println(accounts.length);
        } else {
            pw.println("none");
        }
        final long now = SystemClock.elapsedRealtime();
        pw.print("now: "); pw.println(now);
        pw.print("uptime: "); pw.print(DateUtils.formatElapsedTime(now/1000));
                pw.println(" (HH:MM:SS)");
        pw.print("time spent syncing: ");
                pw.print(DateUtils.formatElapsedTime(
                        mSyncHandler.mSyncTimeTracker.timeSpentSyncing() / 1000));
                pw.print(" (HH:MM:SS), sync ");
                pw.print(mSyncHandler.mSyncTimeTracker.mLastWasSyncing ? "" : "not ");
                pw.println("in progress");
        if (mSyncHandler.mAlarmScheduleTime != null) {
            pw.print("next alarm time: "); pw.print(mSyncHandler.mAlarmScheduleTime);
                    pw.print(" (");
                    pw.print(DateUtils.formatElapsedTime((mSyncHandler.mAlarmScheduleTime-now)/1000));
                    pw.println(" (HH:MM:SS) from now)");
        } else {
            pw.println("no alarm is scheduled (there had better not be any pending syncs)");
        }

        pw.print("active sync: "); pw.println(mActiveSyncContext);

        pw.print("notification info: ");
        sb.setLength(0);
        mSyncHandler.mSyncNotificationInfo.toString(sb);
        pw.println(sb.toString());

        synchronized (mSyncQueue) {
            pw.print("sync queue: ");
            sb.setLength(0);
            mSyncQueue.dump(sb);
            pw.println(sb.toString());
        }

        ActiveSyncInfo active = mSyncStorageEngine.getActiveSync();
        if (active != null) {
            SyncStorageEngine.AuthorityInfo authority
                    = mSyncStorageEngine.getAuthority(active.authorityId);
            final long durationInSeconds = (now - active.startTime) / 1000;
            pw.print("Active sync: ");
                    pw.print(authority != null ? authority.account : "<no account>");
                    pw.print(" ");
                    pw.print(authority != null ? authority.authority : "<no account>");
                    pw.print(", duration is ");
                    pw.println(DateUtils.formatElapsedTime(durationInSeconds));
        } else {
            pw.println("No sync is in progress.");
        }

        ArrayList<SyncStorageEngine.PendingOperation> ops
                = mSyncStorageEngine.getPendingOperations();
        if (ops != null && ops.size() > 0) {
            pw.println();
            pw.println("Pending Syncs");
            final int N = ops.size();
            for (int i=0; i<N; i++) {
                SyncStorageEngine.PendingOperation op = ops.get(i);
                pw.print("  #"); pw.print(i); pw.print(": account=");
                pw.print(op.account.name); pw.print(":");
                pw.print(op.account.type); pw.print(" authority=");
                pw.println(op.authority);
                if (op.extras != null && op.extras.size() > 0) {
                    sb.setLength(0);
                    SyncOperation.extrasToStringBuilder(op.extras, sb);
                    pw.print("    extras: "); pw.println(sb.toString());
                }
            }
        }

        HashSet<Account> processedAccounts = new HashSet<Account>();
        ArrayList<SyncStatusInfo> statuses
                = mSyncStorageEngine.getSyncStatus();
        if (statuses != null && statuses.size() > 0) {
            pw.println();
            pw.println("Sync Status");
            final int N = statuses.size();
            for (int i=0; i<N; i++) {
                SyncStatusInfo status = statuses.get(i);
                SyncStorageEngine.AuthorityInfo authority
                        = mSyncStorageEngine.getAuthority(status.authorityId);
                if (authority != null) {
                    Account curAccount = authority.account;

                    if (processedAccounts.contains(curAccount)) {
                        continue;
                    }

                    processedAccounts.add(curAccount);

                    pw.print("  Account "); pw.print(authority.account.name);
                            pw.print(" "); pw.print(authority.account.type);
                            pw.println(":");
                    for (int j=i; j<N; j++) {
                        status = statuses.get(j);
                        authority = mSyncStorageEngine.getAuthority(status.authorityId);
                        if (!curAccount.equals(authority.account)) {
                            continue;
                        }
                        pw.print("    "); pw.print(authority.authority);
                        pw.println(":");
                        pw.print("      count: local="); pw.print(status.numSourceLocal);
                                pw.print(" poll="); pw.print(status.numSourcePoll);
                                pw.print(" server="); pw.print(status.numSourceServer);
                                pw.print(" user="); pw.print(status.numSourceUser);
                                pw.print(" total="); pw.println(status.numSyncs);
                        pw.print("      total duration: ");
                                pw.println(DateUtils.formatElapsedTime(
                                        status.totalElapsedTime/1000));
                        if (status.lastSuccessTime != 0) {
                            pw.print("      SUCCESS: source=");
                                    pw.print(SyncStorageEngine.SOURCES[
                                            status.lastSuccessSource]);
                                    pw.print(" time=");
                                    pw.println(formatTime(status.lastSuccessTime));
                        } else {
                            pw.print("      FAILURE: source=");
                                    pw.print(SyncStorageEngine.SOURCES[
                                            status.lastFailureSource]);
                                    pw.print(" initialTime=");
                                    pw.print(formatTime(status.initialFailureTime));
                                    pw.print(" lastTime=");
                                    pw.println(formatTime(status.lastFailureTime));
                            pw.print("      message: "); pw.println(status.lastFailureMesg);
                        }
                    }
                }
            }
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

    protected void dumpSyncHistory(PrintWriter pw, StringBuilder sb) {
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

        ArrayList<SyncStorageEngine.SyncHistoryItem> items
                = mSyncStorageEngine.getSyncHistory();
        if (items != null && items.size() > 0) {
            pw.println();
            pw.println("Recent Sync History");
            final int N = items.size();
            for (int i=0; i<N; i++) {
                SyncStorageEngine.SyncHistoryItem item = items.get(i);
                SyncStorageEngine.AuthorityInfo authority
                        = mSyncStorageEngine.getAuthority(item.authorityId);
                pw.print("  #"); pw.print(i+1); pw.print(": ");
                        if (authority != null) {
                            pw.print(authority.account.name);
                            pw.print(":");
                            pw.print(authority.account.type);
                            pw.print(" ");
                            pw.print(authority.authority);
                        } else {
                            pw.print("<no account>");
                        }
                Time time = new Time();
                time.set(item.eventTime);
                pw.print(" "); pw.print(SyncStorageEngine.SOURCES[item.source]);
                        pw.print(" @ ");
                        pw.print(formatTime(item.eventTime));
                        pw.print(" for ");
                        dumpTimeSec(pw, item.elapsedTime);
                        pw.println();
                if (item.event != SyncStorageEngine.EVENT_STOP
                        || item.upstreamActivity !=0
                        || item.downstreamActivity != 0) {
                    pw.print("    event="); pw.print(item.event);
                            pw.print(" upstreamActivity="); pw.print(item.upstreamActivity);
                            pw.print(" downstreamActivity="); pw.println(item.downstreamActivity);
                }
                if (item.mesg != null
                        && !SyncStorageEngine.MESG_SUCCESS.equals(item.mesg)) {
                    pw.print("    mesg="); pw.println(item.mesg);
                }
            }
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
            final boolean isSyncInProgress = mActiveSyncContext != null;
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
        public final ISyncAdapter syncAdapter;
        ServiceConnectionData(ActiveSyncContext activeSyncContext, ISyncAdapter syncAdapter) {
            this.activeSyncContext = activeSyncContext;
            this.syncAdapter = syncAdapter;
        }
    }

    /**
     * Handles SyncOperation Messages that are posted to the associated
     * HandlerThread.
     */
    class SyncHandler extends Handler {
        // Messages that can be sent on mHandler
        private static final int MESSAGE_SYNC_FINISHED = 1;
        private static final int MESSAGE_SYNC_ALARM = 2;
        private static final int MESSAGE_CHECK_ALARMS = 3;
        private static final int MESSAGE_SERVICE_CONNECTED = 4;
        private static final int MESSAGE_SERVICE_DISCONNECTED = 5;

        public final SyncNotificationInfo mSyncNotificationInfo = new SyncNotificationInfo();
        private Long mAlarmScheduleTime = null;
        public final SyncTimeTracker mSyncTimeTracker = new SyncTimeTracker();

        // used to track if we have installed the error notification so that we don't reinstall
        // it if sync is still failing
        private boolean mErrorNotificationInstalled = false;
        private volatile CountDownLatch mReadyToRunLatch = new CountDownLatch(1);

        public void onBootCompleted() {
            mBootCompleted = true;
            if (mReadyToRunLatch != null) {
                mReadyToRunLatch.countDown();
            }
        }

        private void waitUntilReadyToRun() {
            CountDownLatch latch = mReadyToRunLatch;
            if (latch != null) {
                while (true) {
                    try {
                        latch.await();
                        mReadyToRunLatch = null;
                        return;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        /**
         * Used to keep track of whether a sync notification is active and who it is for.
         */
        class SyncNotificationInfo {
            // only valid if isActive is true
            public Account account;

            // only valid if isActive is true
            public String authority;

            // true iff the notification manager has been asked to send the notification
            public boolean isActive = false;

            // Set when we transition from not running a sync to running a sync, and cleared on
            // the opposite transition.
            public Long startTime = null;

            public void toString(StringBuilder sb) {
                sb.append("account ").append(account)
                        .append(", authority ").append(authority)
                        .append(", isActive ").append(isActive)
                        .append(", startTime ").append(startTime);
            }

            @Override
            public String toString() {
                StringBuilder sb = new StringBuilder();
                toString(sb);
                return sb.toString();
            }
        }

        public SyncHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            try {
                waitUntilReadyToRun();
                switch (msg.what) {
                    case SyncHandler.MESSAGE_SYNC_FINISHED:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "handleSyncHandlerMessage: MESSAGE_SYNC_FINISHED");
                        }
                        SyncHandlerMessagePayload payload = (SyncHandlerMessagePayload)msg.obj;
                        if (mActiveSyncContext != payload.activeSyncContext) {
                            if (Config.LOGD) {
                                Log.d(TAG, "handleSyncHandlerMessage: sync context doesn't match, "
                                        + "dropping: mActiveSyncContext " + mActiveSyncContext
                                        + " != " + payload.activeSyncContext);
                            }
                            return;
                        }
                        runSyncFinishedOrCanceled(payload.syncResult);

                        // since we are no longer syncing, check if it is time to start a new sync
                        runStateIdle();
                        break;

                    case SyncHandler.MESSAGE_SERVICE_CONNECTED: {
                        ServiceConnectionData msgData = (ServiceConnectionData)msg.obj;
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_SERVICE_CONNECTED: "
                                    + msgData.activeSyncContext
                                    + " active is " + mActiveSyncContext);
                        }
                        // check that this isn't an old message
                        if (mActiveSyncContext == msgData.activeSyncContext) {
                            runBoundToSyncAdapter(msgData.syncAdapter);
                        }
                        break;
                    }

                    case SyncHandler.MESSAGE_SERVICE_DISCONNECTED: {
                        ServiceConnectionData msgData = (ServiceConnectionData)msg.obj;
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.d(TAG, "handleSyncHandlerMessage: MESSAGE_SERVICE_DISCONNECTED: "
                                    + msgData.activeSyncContext
                                    + " active is " + mActiveSyncContext);
                        }
                        // check that this isn't an old message
                        if (mActiveSyncContext == msgData.activeSyncContext) {
                            // cancel the sync if we have a syncadapter, which means one is
                            // outstanding
                            if (mActiveSyncContext.mSyncAdapter != null) {
                                try {
                                    mActiveSyncContext.mSyncAdapter.cancelSync(mActiveSyncContext);
                                } catch (RemoteException e) {
                                    // we don't need to retry this in this case
                                }
                            }

                            // pretend that the sync failed with an IOException,
                            // which is a soft error
                            SyncResult syncResult = new SyncResult();
                            syncResult.stats.numIoExceptions++;
                            runSyncFinishedOrCanceled(syncResult);

                            // since we are no longer syncing, check if it is time to start a new
                            // sync
                            runStateIdle();
                        }

                        break;
                    }

                    case SyncHandler.MESSAGE_SYNC_ALARM: {
                        boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
                        if (isLoggable) {
                            Log.v(TAG, "handleSyncHandlerMessage: MESSAGE_SYNC_ALARM");
                        }
                        mAlarmScheduleTime = null;
                        try {
                            if (mActiveSyncContext != null) {
                                if (isLoggable) {
                                    Log.v(TAG, "handleSyncHandlerMessage: sync context is active");
                                }
                                runStateSyncing();
                            }

                            // if the above call to runStateSyncing() resulted in the end of a sync,
                            // check if it is time to start a new sync
                            if (mActiveSyncContext == null) {
                                if (isLoggable) {
                                    Log.v(TAG, "handleSyncHandlerMessage: "
                                            + "sync context is not active");
                                }
                                runStateIdle();
                            }
                        } finally {
                            mHandleAlarmWakeLock.release();
                        }
                        break;
                    }

                    case SyncHandler.MESSAGE_CHECK_ALARMS:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "handleSyncHandlerMessage: MESSAGE_CHECK_ALARMS");
                        }
                        // we do all the work for this case in the finally block
                        break;
                }
            } finally {
                final boolean isSyncInProgress = mActiveSyncContext != null;
                if (!isSyncInProgress) {
                    mSyncWakeLock.release();
                }
                manageSyncNotification();
                manageErrorNotification();
                manageSyncAlarm();
                mSyncTimeTracker.update();
            }
        }

        private void runStateSyncing() {
            // if the sync timeout has been reached then cancel it

            ActiveSyncContext activeSyncContext = mActiveSyncContext;

            final long now = SystemClock.elapsedRealtime();
            if (now > activeSyncContext.mTimeoutStartTime + MAX_TIME_PER_SYNC) {
                SyncOperation nextSyncOperation;
                synchronized (mSyncQueue) {
                    nextSyncOperation = mSyncQueue.head();
                }
                if (nextSyncOperation != null && nextSyncOperation.earliestRunTime <= now) {
                    if (Config.LOGD) {
                        Log.d(TAG, "canceling and rescheduling sync because it ran too long: "
                                + activeSyncContext.mSyncOperation);
                    }
                    rescheduleImmediately(activeSyncContext.mSyncOperation);
                    sendSyncFinishedOrCanceledMessage(activeSyncContext,
                            null /* no result since this is a cancel */);
                } else {
                    activeSyncContext.mTimeoutStartTime = now + MAX_TIME_PER_SYNC;
                }
            }

            // no need to schedule an alarm, as that will be done by our caller.
        }

        private void runStateIdle() {
            boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
            if (isLoggable) Log.v(TAG, "runStateIdle");

            // If we aren't ready to run (e.g. the data connection is down), get out.
            if (!mDataConnectionIsConnected) {
                if (isLoggable) {
                    Log.v(TAG, "runStateIdle: no data connection, skipping");
                }
                setStatusText("No data connection");
                return;
            }

            if (mStorageIsLow) {
                if (isLoggable) {
                    Log.v(TAG, "runStateIdle: memory low, skipping");
                }
                setStatusText("Memory low");
                return;
            }

            // If the accounts aren't known yet then we aren't ready to run. We will be kicked
            // when the account lookup request does complete.
            Account[] accounts = mAccounts;
            if (accounts == null) {
                if (isLoggable) {
                    Log.v(TAG, "runStateIdle: accounts not known, skipping");
                }
                setStatusText("Accounts not known yet");
                return;
            }

            // Otherwise consume SyncOperations from the head of the SyncQueue until one is
            // found that is runnable (not disabled, etc). If that one is ready to run then
            // start it, otherwise just get out.
            SyncOperation op;
            final boolean backgroundDataUsageAllowed =
                    getConnectivityManager().getBackgroundDataSetting();
            synchronized (mSyncQueue) {
                while (true) {
                    op = mSyncQueue.head();
                    if (op == null) {
                        if (isLoggable) {
                            Log.v(TAG, "runStateIdle: no more sync operations, returning");
                        }
                        return;
                    }

                    // Sync is disabled, drop this operation.
                    if (!isSyncEnabled()) {
                        if (isLoggable) {
                            Log.v(TAG, "runStateIdle: sync disabled, dropping " + op);
                        }
                        mSyncQueue.popHead();
                        continue;
                    }

                    // skip the sync if it isn't manual and auto sync is disabled
                    final boolean manualSync = op.extras.getBoolean(
                            ContentResolver.SYNC_EXTRAS_MANUAL, false);
                    final boolean syncAutomatically =
                            mSyncStorageEngine.getSyncAutomatically(op.account, op.authority)
                                    && mSyncStorageEngine.getMasterSyncAutomatically();
                    boolean syncAllowed =
                            manualSync || (backgroundDataUsageAllowed && syncAutomatically);
                    int isSyncable = mSyncStorageEngine.getIsSyncable(op.account, op.authority);
                    if (isSyncable == 0) {
                        // if not syncable, don't allow
                        syncAllowed = false;
                    } else if (isSyncable < 0) {
                        // if the syncable state is unknown, only allow initialization syncs
                        syncAllowed =
                                op.extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, false);
                    }
                    if (!syncAllowed) {
                        if (isLoggable) {
                            Log.v(TAG, "runStateIdle: sync off, dropping " + op);
                        }
                        mSyncQueue.popHead();
                        continue;
                    }

                    // skip the sync if the account of this operation no longer exists
                    if (!ArrayUtils.contains(accounts, op.account)) {
                        mSyncQueue.popHead();
                        if (isLoggable) {
                            Log.v(TAG, "runStateIdle: account not present, dropping " + op);
                        }
                        continue;
                    }

                    // go ahead and try to sync this syncOperation
                    if (isLoggable) {
                        Log.v(TAG, "runStateIdle: found sync candidate: " + op);
                    }
                    break;
                }

                // If the first SyncOperation isn't ready to run schedule a wakeup and
                // get out.
                final long now = SystemClock.elapsedRealtime();
                if (op.earliestRunTime > now) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "runStateIdle: the time is " + now + " yet the next "
                                + "sync operation is for " + op.earliestRunTime + ": " + op);
                    }
                    return;
                }

                // We will do this sync. Remove it from the queue and run it outside of the
                // synchronized block.
                if (isLoggable) {
                    Log.v(TAG, "runStateIdle: we are going to sync " + op);
                }
                mSyncQueue.popHead();
            }

            // connect to the sync adapter
            SyncAdapterType syncAdapterType = SyncAdapterType.newKey(op.authority, op.account.type);
            RegisteredServicesCache.ServiceInfo<SyncAdapterType> syncAdapterInfo =
                    mSyncAdapters.getServiceInfo(syncAdapterType);
            if (syncAdapterInfo == null) {
                if (Config.LOGD) {
                    Log.d(TAG, "can't find a sync adapter for " + syncAdapterType);
                }
                runStateIdle();
                return;
            }

            ActiveSyncContext activeSyncContext =
                    new ActiveSyncContext(op, insertStartSyncEvent(op));
            mActiveSyncContext = activeSyncContext;
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "runStateIdle: setting mActiveSyncContext to " + mActiveSyncContext);
            }
            mSyncStorageEngine.setActiveSync(mActiveSyncContext);
            if (!activeSyncContext.bindToSyncAdapter(syncAdapterInfo)) {
                Log.e(TAG, "Bind attempt failed to " + syncAdapterInfo);
                mActiveSyncContext = null;
                mSyncStorageEngine.setActiveSync(mActiveSyncContext);
                runStateIdle();
                return;
            }

            mSyncWakeLock.acquire();
            // no need to schedule an alarm, as that will be done by our caller.

            // the next step will occur when we get either a timeout or a
            // MESSAGE_SERVICE_CONNECTED or MESSAGE_SERVICE_DISCONNECTED message
        }

        private void runBoundToSyncAdapter(ISyncAdapter syncAdapter) {
            mActiveSyncContext.mSyncAdapter = syncAdapter;
            final SyncOperation syncOperation = mActiveSyncContext.mSyncOperation;
            try {
                syncAdapter.startSync(mActiveSyncContext, syncOperation.authority,
                        syncOperation.account, syncOperation.extras);
            } catch (RemoteException remoteExc) {
                if (Config.LOGD) {
                    Log.d(TAG, "runStateIdle: caught a RemoteException, rescheduling", remoteExc);
                }
                mActiveSyncContext.unBindFromSyncAdapter();
                mActiveSyncContext = null;
                mSyncStorageEngine.setActiveSync(mActiveSyncContext);
                rescheduleWithDelay(syncOperation);
            } catch (RuntimeException exc) {
                mActiveSyncContext.unBindFromSyncAdapter();
                mActiveSyncContext = null;
                mSyncStorageEngine.setActiveSync(mActiveSyncContext);
                Log.e(TAG, "Caught a RuntimeException while starting the sync " + syncOperation,
                        exc);
            }
        }

        private void runSyncFinishedOrCanceled(SyncResult syncResult) {
            boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
            if (isLoggable) Log.v(TAG, "runSyncFinishedOrCanceled");
            final ActiveSyncContext activeSyncContext = mActiveSyncContext;
            mActiveSyncContext = null;
            mSyncStorageEngine.setActiveSync(mActiveSyncContext);

            final SyncOperation syncOperation = activeSyncContext.mSyncOperation;

            final long elapsedTime = SystemClock.elapsedRealtime() - activeSyncContext.mStartTime;

            String historyMessage;
            int downstreamActivity;
            int upstreamActivity;
            if (syncResult != null) {
                if (isLoggable) {
                    Log.v(TAG, "runSyncFinishedOrCanceled: is a finished: operation "
                            + syncOperation + ", result " + syncResult);
                }

                if (!syncResult.hasError()) {
                    if (isLoggable) {
                        Log.v(TAG, "finished sync operation " + syncOperation);
                    }
                    historyMessage = SyncStorageEngine.MESG_SUCCESS;
                    // TODO: set these correctly when the SyncResult is extended to include it
                    downstreamActivity = 0;
                    upstreamActivity = 0;
                } else {
                    maybeRescheduleSync(syncResult, syncOperation);
                    if (Config.LOGD) {
                        Log.d(TAG, "failed sync operation " + syncOperation);
                    }
                    historyMessage = Integer.toString(syncResultToErrorNumber(syncResult));
                    // TODO: set these correctly when the SyncResult is extended to include it
                    downstreamActivity = 0;
                    upstreamActivity = 0;
                }
            } else {
                if (isLoggable) {
                    Log.v(TAG, "runSyncFinishedOrCanceled: is a cancel: operation "
                            + syncOperation);
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

            activeSyncContext.unBindFromSyncAdapter();

            if (syncResult != null && syncResult.tooManyDeletions) {
                installHandleTooManyDeletesNotification(syncOperation.account,
                        syncOperation.authority, syncResult.stats.numDeletes);
            } else {
                mNotificationMgr.cancel(
                        syncOperation.account.hashCode() ^ syncOperation.authority.hashCode());
            }

            if (syncResult != null && syncResult.fullSyncRequested) {
                scheduleSyncOperation(new SyncOperation(syncOperation.account,
                        syncOperation.syncSource, syncOperation.authority, new Bundle(), 0));
            }
            // no need to schedule an alarm, as that will be done by our caller.
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

        private void manageSyncNotification() {
            boolean shouldCancel;
            boolean shouldInstall;

            if (mActiveSyncContext == null) {
                mSyncNotificationInfo.startTime = null;

                // we aren't syncing. if the notification is active then remember that we need
                // to cancel it and then clear out the info
                shouldCancel = mSyncNotificationInfo.isActive;
                shouldInstall = false;
            } else {
                // we are syncing
                final SyncOperation syncOperation = mActiveSyncContext.mSyncOperation;

                final long now = SystemClock.elapsedRealtime();
                if (mSyncNotificationInfo.startTime == null) {
                    mSyncNotificationInfo.startTime = now;
                }

                // cancel the notification if it is up and the authority or account is wrong
                shouldCancel = mSyncNotificationInfo.isActive &&
                        (!syncOperation.authority.equals(mSyncNotificationInfo.authority)
                        || !syncOperation.account.equals(mSyncNotificationInfo.account));

                // there are four cases:
                // - the notification is up and there is no change: do nothing
                // - the notification is up but we should cancel since it is stale:
                //   need to install
                // - the notification is not up but it isn't time yet: don't install
                // - the notification is not up and it is time: need to install

                if (mSyncNotificationInfo.isActive) {
                    shouldInstall = shouldCancel;
                } else {
                    final boolean timeToShowNotification =
                            now > mSyncNotificationInfo.startTime + SYNC_NOTIFICATION_DELAY;
                    // show the notification immediately if this is a manual sync
                    final boolean manualSync = syncOperation.extras
                            .getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false);
                    shouldInstall = timeToShowNotification || manualSync;
                }
            }

            if (shouldCancel && !shouldInstall) {
                mNeedSyncActiveNotification = false;
                sendSyncStateIntent();
                mSyncNotificationInfo.isActive = false;
            }

            if (shouldInstall) {
                SyncOperation syncOperation = mActiveSyncContext.mSyncOperation;
                mNeedSyncActiveNotification = true;
                sendSyncStateIntent();
                mSyncNotificationInfo.isActive = true;
                mSyncNotificationInfo.account = syncOperation.account;
                mSyncNotificationInfo.authority = syncOperation.authority;
            }
        }

        /**
         * Check if there were any long-lasting errors, if so install the error notification,
         * otherwise cancel the error notification.
         */
        private void manageErrorNotification() {
            //
            long when = mSyncStorageEngine.getInitialSyncFailureTime();
            if ((when > 0) && (when + ERROR_NOTIFICATION_DELAY_MS < System.currentTimeMillis())) {
                if (!mErrorNotificationInstalled) {
                    mNeedSyncErrorNotification = true;
                    sendSyncStateIntent();
                }
                mErrorNotificationInstalled = true;
            } else {
                if (mErrorNotificationInstalled) {
                    mNeedSyncErrorNotification = false;
                    sendSyncStateIntent();
                }
                mErrorNotificationInstalled = false;
            }
        }

        private void manageSyncAlarm() {
            // in each of these cases the sync loop will be kicked, which will cause this
            // method to be called again
            if (!mDataConnectionIsConnected) return;
            if (mAccounts == null) return;
            if (mStorageIsLow) return;

            // Compute the alarm fire time:
            // - not syncing: time of the next sync operation
            // - syncing, no notification: time from sync start to notification create time
            // - syncing, with notification: time till timeout of the active sync operation
            Long alarmTime = null;
            ActiveSyncContext activeSyncContext = mActiveSyncContext;
            if (activeSyncContext == null) {
                SyncOperation syncOperation;
                synchronized (mSyncQueue) {
                    syncOperation = mSyncQueue.head();
                }
                if (syncOperation != null) {
                    alarmTime = syncOperation.earliestRunTime;
                }
            } else {
                final long notificationTime =
                        mSyncHandler.mSyncNotificationInfo.startTime + SYNC_NOTIFICATION_DELAY;
                final long timeoutTime =
                        mActiveSyncContext.mTimeoutStartTime + MAX_TIME_PER_SYNC;
                if (mSyncHandler.mSyncNotificationInfo.isActive) {
                    alarmTime = timeoutTime;
                } else {
                    alarmTime = Math.min(notificationTime, timeoutTime);
                }
            }

            // adjust the alarmTime so that we will wake up when it is time to
            // install the error notification
            if (!mErrorNotificationInstalled) {
                long when = mSyncStorageEngine.getInitialSyncFailureTime();
                if (when > 0) {
                    when += ERROR_NOTIFICATION_DELAY_MS;
                    // convert when fron absolute time to elapsed run time
                    long delay = when - System.currentTimeMillis();
                    when = SystemClock.elapsedRealtime() + delay;
                    alarmTime = alarmTime != null ? Math.min(alarmTime, when) : when;
                }
            }

            // determine if we need to set or cancel the alarm
            boolean shouldSet = false;
            boolean shouldCancel = false;
            final boolean alarmIsActive = mAlarmScheduleTime != null;
            final boolean needAlarm = alarmTime != null;
            if (needAlarm) {
                if (!alarmIsActive || alarmTime < mAlarmScheduleTime) {
                    shouldSet = true;
                }
            } else {
                shouldCancel = alarmIsActive;
            }

            // set or cancel the alarm as directed
            ensureAlarmService();
            if (shouldSet) {
                mAlarmScheduleTime = alarmTime;
                mAlarmService.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarmTime,
                        mSyncAlarmIntent);
            } else if (shouldCancel) {
                mAlarmScheduleTime = null;
                mAlarmService.cancel(mSyncAlarmIntent);
            }
        }

        private void sendSyncStateIntent() {
            Intent syncStateIntent = new Intent(Intent.ACTION_SYNC_STATE_CHANGED);
            syncStateIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            syncStateIntent.putExtra("active", mNeedSyncActiveNotification);
            syncStateIntent.putExtra("failing", mNeedSyncErrorNotification);
            mContext.sendBroadcast(syncStateIntent);
        }

        private void installHandleTooManyDeletesNotification(Account account, String authority,
                long numDeletes) {
            if (mNotificationMgr == null) return;

            final ProviderInfo providerInfo = mContext.getPackageManager().resolveContentProvider(
                    authority, 0 /* flags */);
            if (providerInfo == null) {
                return;
            }
            CharSequence authorityName = providerInfo.loadLabel(mContext.getPackageManager());

            Intent clickIntent = new Intent();
            clickIntent.setClassName("com.android.providers.subscribedfeeds",
                    "com.android.settings.SyncActivityTooManyDeletes");
            clickIntent.putExtra("account", account);
            clickIntent.putExtra("authority", authority);
            clickIntent.putExtra("provider", authorityName.toString());
            clickIntent.putExtra("numDeletes", numDeletes);

            if (!isActivityAvailable(clickIntent)) {
                Log.w(TAG, "No activity found to handle too many deletes.");
                return;
            }

            final PendingIntent pendingIntent = PendingIntent
                    .getActivity(mContext, 0, clickIntent, PendingIntent.FLAG_CANCEL_CURRENT);

            CharSequence tooManyDeletesDescFormat = mContext.getResources().getText(
                    R.string.contentServiceTooManyDeletesNotificationDesc);

            Notification notification =
                new Notification(R.drawable.stat_notify_sync_error,
                        mContext.getString(R.string.contentServiceSync),
                        System.currentTimeMillis());
            notification.setLatestEventInfo(mContext,
                    mContext.getString(R.string.contentServiceSyncNotificationTitle),
                    String.format(tooManyDeletesDescFormat.toString(), authorityName),
                    pendingIntent);
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            mNotificationMgr.notify(account.hashCode() ^ authority.hashCode(), notification);
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
            final int source = syncOperation.syncSource;
            final long now = System.currentTimeMillis();

            EventLog.writeEvent(2720, syncOperation.authority,
                                SyncStorageEngine.EVENT_START, source,
                                syncOperation.account.name.hashCode());

            return mSyncStorageEngine.insertStartSyncEvent(
                    syncOperation.account, syncOperation.authority, now, source);
        }

        public void stopSyncEvent(long rowId, SyncOperation syncOperation, String resultMessage,
                int upstreamActivity, int downstreamActivity, long elapsedTime) {
            EventLog.writeEvent(2720, syncOperation.authority,
                                SyncStorageEngine.EVENT_STOP, syncOperation.syncSource,
                                syncOperation.account.name.hashCode());

            mSyncStorageEngine.stopSyncEvent(rowId, elapsedTime, resultMessage,
                    downstreamActivity, upstreamActivity);
        }
    }

    static class SyncQueue {
        private SyncStorageEngine mSyncStorageEngine;

        private static final boolean DEBUG_CHECK_DATA_CONSISTENCY = false;

        // A priority queue of scheduled SyncOperations that is designed to make it quick
        // to find the next SyncOperation that should be considered for running.
        private final PriorityQueue<SyncOperation> mOpsByWhen = new PriorityQueue<SyncOperation>();

        // A Map of SyncOperations operationKey -> SyncOperation that is designed for
        // quick lookup of an enqueued SyncOperation.
        private final HashMap<String, SyncOperation> mOpsByKey = Maps.newHashMap();

        public SyncQueue(SyncStorageEngine syncStorageEngine) {
            mSyncStorageEngine = syncStorageEngine;
            ArrayList<SyncStorageEngine.PendingOperation> ops
                    = mSyncStorageEngine.getPendingOperations();
            final int N = ops.size();
            for (int i=0; i<N; i++) {
                SyncStorageEngine.PendingOperation op = ops.get(i);
                SyncOperation syncOperation = new SyncOperation(
                        op.account, op.syncSource, op.authority, op.extras, 0);
                syncOperation.pendingOperation = op;
                add(syncOperation, op);
            }

            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(true /* check the DB */);
        }

        public boolean add(SyncOperation operation) {
            return add(new SyncOperation(operation),
                    null /* this is not coming from the database */);
        }

        private boolean add(SyncOperation operation,
                SyncStorageEngine.PendingOperation pop) {
            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(pop == null);

            // If this operation is expedited then set its earliestRunTime to be immediately
            // before the head of the list, or not if none are in the list.
            if (operation.delay < 0) {
                SyncOperation headOperation = head();
                if (headOperation != null) {
                    operation.earliestRunTime = Math.min(SystemClock.elapsedRealtime(),
                            headOperation.earliestRunTime - 1);
                } else {
                    operation.earliestRunTime = SystemClock.elapsedRealtime();
                }
            }

            // - if an operation with the same key exists and this one should run earlier,
            //   delete the old one and add the new one
            // - if an operation with the same key exists and if this one should run
            //   later, ignore it
            // - if no operation exists then add the new one
            final String operationKey = operation.key;
            SyncOperation existingOperation = mOpsByKey.get(operationKey);

            // if this operation matches an existing operation that is being retried (delay > 0)
            // and this isn't a manual sync operation, ignore this operation
            if (existingOperation != null && existingOperation.delay > 0) {
                if (!operation.extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, false)) {
                    return false;
                }
            }

            if (existingOperation != null
                    && operation.earliestRunTime >= existingOperation.earliestRunTime) {
                if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(pop == null);
                return false;
            }

            if (existingOperation != null) {
                removeByKey(operationKey);
            }

            operation.pendingOperation = pop;
            if (operation.pendingOperation == null) {
                pop = new SyncStorageEngine.PendingOperation(
                                operation.account, operation.syncSource,
                                operation.authority, operation.extras);
                pop = mSyncStorageEngine.insertIntoPending(pop);
                if (pop == null) {
                    throw new IllegalStateException("error adding pending sync operation "
                            + operation);
                }
                operation.pendingOperation = pop;
            }

            if (DEBUG_CHECK_DATA_CONSISTENCY) {
                debugCheckDataStructures(
                        false /* don't compare with the DB, since we know
                               it is inconsistent right now */ );
            }
            mOpsByKey.put(operationKey, operation);
            mOpsByWhen.add(operation);
            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(pop == null);
            return true;
        }

        public void removeByKey(String operationKey) {
            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(true /* check the DB */);
            SyncOperation operationToRemove = mOpsByKey.remove(operationKey);
            if (!mOpsByWhen.remove(operationToRemove)) {
                throw new IllegalStateException(
                        "unable to find " + operationToRemove + " in mOpsByWhen");
            }

            if (!mSyncStorageEngine.deleteFromPending(operationToRemove.pendingOperation)) {
                final String errorMessage = "unable to find pending row for " + operationToRemove;
                Log.e(TAG, errorMessage, new IllegalStateException(errorMessage));
            }

            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(true /* check the DB */);
        }

        public SyncOperation head() {
            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(true /* check the DB */);
            return mOpsByWhen.peek();
        }

        public void popHead() {
            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(true /* check the DB */);
            SyncOperation operation = mOpsByWhen.remove();
            if (mOpsByKey.remove(operation.key) == null) {
                throw new IllegalStateException("unable to find " + operation + " in mOpsByKey");
            }

            if (!mSyncStorageEngine.deleteFromPending(operation.pendingOperation)) {
                final String errorMessage = "unable to find pending row for " + operation;
                Log.e(TAG, errorMessage, new IllegalStateException(errorMessage));
            }

            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(true /* check the DB */);
        }

        public void clear(Account account, String authority) {
            Iterator<Map.Entry<String, SyncOperation>> entries = mOpsByKey.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, SyncOperation> entry = entries.next();
                SyncOperation syncOperation = entry.getValue();
                if (account != null && !syncOperation.account.equals(account)) continue;
                if (authority != null && !syncOperation.authority.equals(authority)) continue;

                if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(true /* check the DB */);
                entries.remove();
                if (!mOpsByWhen.remove(syncOperation)) {
                    throw new IllegalStateException(
                            "unable to find " + syncOperation + " in mOpsByWhen");
                }

                if (!mSyncStorageEngine.deleteFromPending(syncOperation.pendingOperation)) {
                    final String errorMessage = "unable to find pending row for " + syncOperation;
                    Log.e(TAG, errorMessage, new IllegalStateException(errorMessage));
                }

                if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(true /* check the DB */);
            }
        }

        public void dump(StringBuilder sb) {
            sb.append("SyncQueue: ").append(mOpsByWhen.size()).append(" operation(s)\n");
            for (SyncOperation operation : mOpsByWhen) {
                sb.append(operation).append("\n");
            }
        }

        private void debugCheckDataStructures(boolean checkDatabase) {
            if (mOpsByKey.size() != mOpsByWhen.size()) {
                throw new IllegalStateException("size mismatch: "
                        + mOpsByKey .size() + " != " + mOpsByWhen.size());
            }
            for (SyncOperation operation : mOpsByWhen) {
                if (!mOpsByKey.containsKey(operation.key)) {
                    throw new IllegalStateException(
                        "operation " + operation + " is in mOpsByWhen but not mOpsByKey");
                }
            }
            for (Map.Entry<String, SyncOperation> entry : mOpsByKey.entrySet()) {
                final SyncOperation operation = entry.getValue();
                final String key = entry.getKey();
                if (!key.equals(operation.key)) {
                    throw new IllegalStateException(
                        "operation " + operation + " in mOpsByKey doesn't match key " + key);
                }
                if (!mOpsByWhen.contains(operation)) {
                    throw new IllegalStateException(
                        "operation " + operation + " is in mOpsByKey but not mOpsByWhen");
                }
            }

            if (checkDatabase) {
                final int N = mSyncStorageEngine.getPendingOperationCount();
                if (mOpsByKey.size() != N) {
                    ArrayList<SyncStorageEngine.PendingOperation> ops
                            = mSyncStorageEngine.getPendingOperations();
                    StringBuilder sb = new StringBuilder();
                    for (int i=0; i<N; i++) {
                        SyncStorageEngine.PendingOperation op = ops.get(i);
                        sb.append("#");
                        sb.append(i);
                        sb.append(": account=");
                        sb.append(op.account);
                        sb.append(" syncSource=");
                        sb.append(op.syncSource);
                        sb.append(" authority=");
                        sb.append(op.authority);
                        sb.append("\n");
                    }
                    dump(sb);
                    throw new IllegalStateException("DB size mismatch: "
                            + mOpsByKey.size() + " != " + N + "\n"
                            + sb.toString());
                }
            }
        }
    }
}
