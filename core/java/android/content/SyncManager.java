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

import android.accounts.AccountMonitor;
import android.accounts.AccountMonitorListener;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.provider.Sync;
import android.provider.Settings;
import android.provider.Sync.History;
import android.text.TextUtils;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Observer;
import java.util.Observable;

/**
 * @hide
 */
class SyncManager {
    private static final String TAG = "SyncManager";

    // used during dumping of the Sync history
    private static final long MILLIS_IN_HOUR = 1000 * 60 * 60;
    private static final long MILLIS_IN_DAY = MILLIS_IN_HOUR * 24;
    private static final long MILLIS_IN_WEEK = MILLIS_IN_DAY * 7;
    private static final long MILLIS_IN_4WEEKS = MILLIS_IN_WEEK * 4;

    /** Delay a sync due to local changes this long. In milliseconds */
    private static final long LOCAL_SYNC_DELAY = 30 * 1000; // 30 seconds

    /**
     * If a sync takes longer than this and the sync queue is not empty then we will
     * cancel it and add it back to the end of the sync queue. In milliseconds.
     */
    private static final long MAX_TIME_PER_SYNC = 5 * 60 * 1000; // 5 minutes

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
    private ContentResolver mContentResolver;

    private String mStatusText = "";
    private long mHeartbeatTime = 0;

    private AccountMonitor mAccountMonitor;

    private volatile String[] mAccounts = null;

    volatile private PowerManager.WakeLock mSyncWakeLock;
    volatile private PowerManager.WakeLock mHandleAlarmWakeLock;
    volatile private boolean mDataConnectionIsConnected = false;
    volatile private boolean mStorageIsLow = false;
    private Sync.Settings.QueryMap mSyncSettings;

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

    private BroadcastReceiver mStorageIntentReceiver =
            new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    ensureContentResolver();
                    String action = intent.getAction();
                    if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "Internal storage is low.");
                        }
                        mStorageIsLow = true;
                        cancelActiveSync(null /* no url */);
                    } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "Internal storage is ok.");
                        }
                        mStorageIsLow = false;
                        sendCheckAlarmsMessage();
                    }
                }
            };

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

    private static final String ACTION_SYNC_ALARM = "android.content.syncmanager.SYNC_ALARM";
    private static final String SYNC_POLL_ALARM = "android.content.syncmanager.SYNC_POLL_ALARM";
    private final SyncHandler mSyncHandler;

    private static final String[] SYNC_ACTIVE_PROJECTION = new String[]{
            Sync.Active.ACCOUNT,
            Sync.Active.AUTHORITY,
            Sync.Active.START_TIME,
    };

    private static final String[] SYNC_PENDING_PROJECTION = new String[]{
            Sync.Pending.ACCOUNT,
            Sync.Pending.AUTHORITY
    };

    private static final int MAX_SYNC_POLL_DELAY_SECONDS = 36 * 60 * 60; // 36 hours
    private static final int MIN_SYNC_POLL_DELAY_SECONDS = 24 * 60 * 60; // 24 hours

    private static final String SYNCMANAGER_PREFS_FILENAME = "/data/system/syncmanager.prefs";

    public SyncManager(Context context, boolean factoryTest) {
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

        mSyncAlarmIntent = PendingIntent.getBroadcast(
                mContext, 0 /* ignored */, new Intent(ACTION_SYNC_ALARM), 0);

        mSyncPollAlarmIntent = PendingIntent.getBroadcast(
                mContext, 0 /* ignored */, new Intent(SYNC_POLL_ALARM), 0);

        IntentFilter intentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(mConnectivityIntentReceiver, intentFilter);

        intentFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        context.registerReceiver(mStorageIntentReceiver, intentFilter);

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

        if (!factoryTest) {
            AccountMonitorListener listener = new AccountMonitorListener() {
                public void onAccountsUpdated(String[] accounts) {
                    final boolean hadAccountsAlready = mAccounts != null;
                    // copy the accounts into a new array and change mAccounts to point to it
                    String[] newAccounts = new String[accounts.length];
                    System.arraycopy(accounts, 0, newAccounts, 0, accounts.length);
                    mAccounts = newAccounts;

                    // if a sync is in progress yet it is no longer in the accounts list, cancel it
                    ActiveSyncContext activeSyncContext = mActiveSyncContext;
                    if (activeSyncContext != null) {
                        if (!ArrayUtils.contains(newAccounts,
                                activeSyncContext.mSyncOperation.account)) {
                            Log.d(TAG, "canceling sync since the account has been removed");
                            sendSyncFinishedOrCanceledMessage(activeSyncContext,
                                    null /* no result since this is a cancel */);
                        }
                    }

                    // we must do this since we don't bother scheduling alarms when
                    // the accounts are not set yet
                    sendCheckAlarmsMessage();

                    mSyncStorageEngine.doDatabaseCleanup(accounts);

                    if (hadAccountsAlready && mAccounts.length > 0) {
                        // request a sync so that if the password was changed we will retry any sync
                        // that failed when it was wrong
                        startSync(null /* all providers */, null /* no extras */);
                    }
                }
            };
            mAccountMonitor = new AccountMonitor(context, listener);
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
        scheduleSync(null /* sync all syncable providers */, new Bundle(), 0 /* no delay */);
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

    private Sync.Settings.QueryMap getSyncSettings() {
        if (mSyncSettings == null) {
            mSyncSettings = new Sync.Settings.QueryMap(mContext.getContentResolver(), true,
                    new Handler());
            mSyncSettings.addObserver(new Observer(){
                public void update(Observable o, Object arg) {
                    // force the sync loop to run if the settings change
                    sendCheckAlarmsMessage();
                }
            });
        }
        return mSyncSettings;
    }

    private void ensureContentResolver() {
        if (mContentResolver == null) {
            mContentResolver = mContext.getContentResolver();
        }
    }

    private void ensureAlarmService() {
        if (mAlarmService == null) {
            mAlarmService = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        }
    }

    public String getSyncingAccount() {
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
     * @param url The Uri of a specific provider to be synced, or
     *          null to sync all providers.
     * @param extras a Map of SyncAdapter-specific information to control
*          syncs of a specific provider. Can be null. Is ignored
*          if the url is null.
     * @param delay how many milliseconds in the future to wait before performing this
     *   sync. -1 means to make this the next sync to perform.
     */
    public void scheduleSync(Uri url, Bundle extras, long delay) {
        boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
        if (isLoggable) {
            Log.v(TAG, "scheduleSync:"
                    + " delay " + delay
                    + ", url " + ((url == null) ? "(null)" : url)
                    + ", extras " + ((extras == null) ? "(null)" : extras));
        }

        if (!isSyncEnabled()) {
            if (isLoggable) {
                Log.v(TAG, "not syncing because sync is disabled");
            }
            setStatusText("Sync is disabled.");
            return;
        }

        if (mAccounts == null) setStatusText("The accounts aren't known yet.");
        if (!mDataConnectionIsConnected) setStatusText("No data connection");
        if (mStorageIsLow) setStatusText("Memory low");

        if (extras == null) extras = new Bundle();

        Boolean expedited = extras.getBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, false);
        if (expedited) {
            delay = -1; // this means schedule at the front of the queue
        }

        String[] accounts;
        String accountFromExtras = extras.getString(ContentResolver.SYNC_EXTRAS_ACCOUNT);
        if (!TextUtils.isEmpty(accountFromExtras)) {
            accounts = new String[]{accountFromExtras};
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
        final boolean force = extras.getBoolean(ContentResolver.SYNC_EXTRAS_FORCE, false);

        int source;
        if (uploadOnly) {
            source = Sync.History.SOURCE_LOCAL;
        } else if (force) {
            source = Sync.History.SOURCE_USER;
        } else if (url == null) {
            source = Sync.History.SOURCE_POLL;
        } else {
            // this isn't strictly server, since arbitrary callers can (and do) request
            // a non-forced two-way sync on a specific url
            source = Sync.History.SOURCE_SERVER;
        }

        List<String> names = new ArrayList<String>();
        List<ProviderInfo> providers = new ArrayList<ProviderInfo>();
        populateProvidersList(url, names, providers);

        final int numProviders = providers.size();
        for (int i = 0; i < numProviders; i++) {
            if (!providers.get(i).isSyncable) continue;
            final String name = names.get(i);
            for (String account : accounts) {
                scheduleSyncOperation(new SyncOperation(account, source, name, extras, delay));
                // TODO: remove this when Calendar supports multiple accounts. Until then
                // pretend that only the first account exists when syncing calendar.
                if ("calendar".equals(name)) {
                    break;
                }
            }
        }
    }

    private void setStatusText(String message) {
        mStatusText = message;
    }

    private void populateProvidersList(Uri url, List<String> names, List<ProviderInfo> providers) {
        try {
            final IPackageManager packageManager = getPackageManager();
            if (url == null) {
                packageManager.querySyncProviders(names, providers);
            } else {
                final String authority = url.getAuthority();
                ProviderInfo info = packageManager.resolveContentProvider(url.getAuthority(), 0);
                if (info != null) {
                    // only set this provider if the requested authority is the primary authority
                    String[] providerNames = info.authority.split(";");
                    if (url.getAuthority().equals(providerNames[0])) {
                        names.add(authority);
                        providers.add(info);
                    }
                }
            }
        } catch (RemoteException ex) {
            // we should really never get this, but if we do then clear the lists, which
            // will result in the dropping of the sync request
            Log.e(TAG, "error trying to get the ProviderInfo for " + url, ex);
            names.clear();
            providers.clear();
        }
    }

    public void scheduleLocalSync(Uri url) {
        final Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true);
        scheduleSync(url, extras, LOCAL_SYNC_DELAY);
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

    /**
     * Initiate a sync for this given URL, or pass null for a full sync.
     *
     * <p>You'll start getting callbacks after this.
     *
     * @param url The Uri of a specific provider to be synced, or
     *          null to sync all providers.
     * @param extras a Map of SyncAdapter specific information to control
     *          syncs of a specific provider. Can be null. Is ignored
     */
    public void startSync(Uri url, Bundle extras) {
        scheduleSync(url, extras, 0 /* no delay */);
    }

    public void updateHeartbeatTime() {
        mHeartbeatTime = SystemClock.elapsedRealtime();
        ensureContentResolver();
        mContentResolver.notifyChange(Sync.Active.CONTENT_URI,
                null /* this change wasn't made through an observer */);
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

        if (syncOperation.delay == 0) {
            // The initial delay is the jitterized INITIAL_SYNC_RETRY_TIME_IN_MS
            newDelayInMs = jitterize(INITIAL_SYNC_RETRY_TIME_IN_MS,
                    (long)(INITIAL_SYNC_RETRY_TIME_IN_MS * 1.1));
        } else {
            // Subsequent delays are the double of the previous delay
            newDelayInMs = syncOperation.delay * 2;
        }

        // Cap the delay
        ensureContentResolver();
        long maxSyncRetryTimeInSeconds = Settings.Gservices.getLong(mContentResolver,
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
     * Cancel the active sync if it matches the uri. The uri corresponds to the one passed
     * in to startSync().
     * @param uri If non-null, the active sync is only canceled if it matches the uri.
     *   If null, any active sync is canceled.
     */
    public void cancelActiveSync(Uri uri) {
        ActiveSyncContext activeSyncContext = mActiveSyncContext;
        if (activeSyncContext != null) {
            // if a Uri was specified then only cancel the sync if it matches the the uri
            if (uri != null) {
                if (!uri.getAuthority().equals(activeSyncContext.mSyncOperation.authority)) {
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
     * Remove any scheduled sync operations that match uri. The uri corresponds to the one passed
     * in to startSync().
     * @param uri If non-null, only operations that match the uri are cleared.
     *   If null, all operations are cleared.
     */
    public void clearScheduledSyncOperations(Uri uri) {
        synchronized (mSyncQueue) {
            mSyncQueue.clear(null, uri != null ? uri.getAuthority() : null);
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
        final String account;
        int syncSource;
        String authority;
        Bundle extras;
        final String key;
        long earliestRunTime;
        long delay;
        Long rowId = null;

        SyncOperation(String account, int source, String authority, Bundle extras, long delay) {
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
            if (rowId != null) sb.append(" rowId: ").append(rowId);
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
    class ActiveSyncContext extends ISyncContext.Stub {
        final SyncOperation mSyncOperation;
        final long mHistoryRowId;
        final IContentProvider mContentProvider;
        final ISyncAdapter mSyncAdapter;
        final long mStartTime;
        long mTimeoutStartTime;

        public ActiveSyncContext(SyncOperation syncOperation, IContentProvider contentProvider,
                ISyncAdapter syncAdapter, long historyRowId) {
            super();
            mSyncOperation = syncOperation;
            mHistoryRowId = historyRowId;
            mContentProvider = contentProvider;
            mSyncAdapter = syncAdapter;
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

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            toString(sb);
            return sb.toString();
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw) {
        StringBuilder sb = new StringBuilder();
        dumpSyncState(sb);
        sb.append("\n");
        if (isSyncEnabled()) {
            dumpSyncHistory(sb);
        }
        pw.println(sb.toString());
    }

    protected void dumpSyncState(StringBuilder sb) {
        sb.append("sync enabled: ").append(isSyncEnabled()).append("\n");
        sb.append("data connected: ").append(mDataConnectionIsConnected).append("\n");
        sb.append("memory low: ").append(mStorageIsLow).append("\n");

        final String[] accounts = mAccounts;
        sb.append("accounts: ");
        if (accounts != null) {
            sb.append(accounts.length);
        } else {
            sb.append("none");
        }
        sb.append("\n");
        final long now = SystemClock.elapsedRealtime();
        sb.append("now: ").append(now).append("\n");
        sb.append("uptime: ").append(DateUtils.formatElapsedTime(now/1000)).append(" (HH:MM:SS)\n");
        sb.append("time spent syncing : ")
                .append(DateUtils.formatElapsedTime(
                        mSyncHandler.mSyncTimeTracker.timeSpentSyncing() / 1000))
                .append(" (HH:MM:SS), sync ")
                .append(mSyncHandler.mSyncTimeTracker.mLastWasSyncing ? "" : "not ")
                .append("in progress").append("\n");
        if (mSyncHandler.mAlarmScheduleTime != null) {
            sb.append("next alarm time: ").append(mSyncHandler.mAlarmScheduleTime)
                    .append(" (")
                    .append(DateUtils.formatElapsedTime((mSyncHandler.mAlarmScheduleTime-now)/1000))
                    .append(" (HH:MM:SS) from now)\n");
        } else {
            sb.append("no alarm is scheduled (there had better not be any pending syncs)\n");
        }

        sb.append("active sync: ").append(mActiveSyncContext).append("\n");

        sb.append("notification info: ");
        mSyncHandler.mSyncNotificationInfo.toString(sb);
        sb.append("\n");

        synchronized (mSyncQueue) {
            sb.append("sync queue: ");
            mSyncQueue.dump(sb);
        }

        Cursor c = mSyncStorageEngine.query(Sync.Active.CONTENT_URI,
                SYNC_ACTIVE_PROJECTION, null, null, null);
        sb.append("\n");
        try {
            if (c.moveToNext()) {
                final long durationInSeconds = (now - c.getLong(2)) / 1000;
                sb.append("Active sync: ").append(c.getString(0))
                        .append(" ").append(c.getString(1))
                        .append(", duration is ")
                        .append(DateUtils.formatElapsedTime(durationInSeconds)).append(".\n");
            } else {
                sb.append("No sync is in progress.\n");
            }
        } finally {
            c.close();
        }

        c = mSyncStorageEngine.query(Sync.Pending.CONTENT_URI,
                SYNC_PENDING_PROJECTION, null, null, "account, authority");
        sb.append("\nPending Syncs\n");
        try {
            if (c.getCount() != 0) {
                dumpSyncPendingHeader(sb);
                while (c.moveToNext()) {
                    dumpSyncPendingRow(sb, c);
                }
                dumpSyncPendingFooter(sb);
            } else {
                sb.append("none\n");
            }
        } finally {
            c.close();
        }

        String currentAccount = null;
        c = mSyncStorageEngine.query(Sync.Status.CONTENT_URI,
                STATUS_PROJECTION, null, null, "account, authority");
        sb.append("\nSync history by account and authority\n");
        try {
            while (c.moveToNext()) {
                if (!TextUtils.equals(currentAccount, c.getString(0))) {
                    if (currentAccount != null) {
                        dumpSyncHistoryFooter(sb);
                    }
                    currentAccount = c.getString(0);
                    dumpSyncHistoryHeader(sb, currentAccount);
                }

                dumpSyncHistoryRow(sb, c);
            }
            if (c.getCount() > 0) dumpSyncHistoryFooter(sb);
        } finally {
            c.close();
        }
    }

    private void dumpSyncHistoryHeader(StringBuilder sb, String account) {
        sb.append(" Account: ").append(account).append("\n");
        sb.append("  ___________________________________________________________________________________________________________________________\n");
        sb.append(" |                 |             num times synced           |   total  |         last success          |                     |\n");
        sb.append(" | authority       | local |  poll | server |  user | total | duration |  source |               time  |   result if failing |\n");
    }

    private static String[] STATUS_PROJECTION = new String[]{
            Sync.Status.ACCOUNT, // 0
            Sync.Status.AUTHORITY, // 1
            Sync.Status.NUM_SYNCS, // 2
            Sync.Status.TOTAL_ELAPSED_TIME, // 3
            Sync.Status.NUM_SOURCE_LOCAL, // 4
            Sync.Status.NUM_SOURCE_POLL, // 5
            Sync.Status.NUM_SOURCE_SERVER, // 6
            Sync.Status.NUM_SOURCE_USER, // 7
            Sync.Status.LAST_SUCCESS_SOURCE, // 8
            Sync.Status.LAST_SUCCESS_TIME, // 9
            Sync.Status.LAST_FAILURE_SOURCE, // 10
            Sync.Status.LAST_FAILURE_TIME, // 11
            Sync.Status.LAST_FAILURE_MESG // 12
    };

    private void dumpSyncHistoryRow(StringBuilder sb, Cursor c) {
        boolean hasSuccess = !c.isNull(9);
        boolean hasFailure = !c.isNull(11);
        Time timeSuccess = new Time();
        if (hasSuccess) timeSuccess.set(c.getLong(9));
        Time timeFailure = new Time();
        if (hasFailure) timeFailure.set(c.getLong(11));
        sb.append(String.format(" | %-15s | %5d | %5d | %6d | %5d | %5d | %8s | %7s | %19s | %19s |\n",
                c.getString(1),
                c.getLong(4),
                c.getLong(5),
                c.getLong(6),
                c.getLong(7),
                c.getLong(2),
                DateUtils.formatElapsedTime(c.getLong(3)/1000),
                hasSuccess ? Sync.History.SOURCES[c.getInt(8)] : "",
                hasSuccess ? timeSuccess.format("%Y-%m-%d %H:%M:%S") : "",
                hasFailure ? History.mesgToString(c.getString(12)) : ""));
    }

    private void dumpSyncHistoryFooter(StringBuilder sb) {
        sb.append(" |___________________________________________________________________________________________________________________________|\n");
    }

    private void dumpSyncPendingHeader(StringBuilder sb) {
        sb.append(" ____________________________________________________\n");
        sb.append(" | account                        | authority       |\n");
    }

    private void dumpSyncPendingRow(StringBuilder sb, Cursor c) {
        sb.append(String.format(" | %-30s | %-15s |\n", c.getString(0), c.getString(1)));
    }

    private void dumpSyncPendingFooter(StringBuilder sb) {
        sb.append(" |__________________________________________________|\n");
    }

    protected void dumpSyncHistory(StringBuilder sb) {
        Cursor c = mSyncStorageEngine.query(Sync.History.CONTENT_URI, null, "event=?",
                new String[]{String.valueOf(Sync.History.EVENT_STOP)},
                Sync.HistoryColumns.EVENT_TIME + " desc");
        try {
            long numSyncsLastHour = 0, durationLastHour = 0;
            long numSyncsLastDay = 0, durationLastDay = 0;
            long numSyncsLastWeek = 0, durationLastWeek = 0;
            long numSyncsLast4Weeks = 0, durationLast4Weeks = 0;
            long numSyncsTotal = 0, durationTotal = 0;

            long now = System.currentTimeMillis();
            int indexEventTime = c.getColumnIndexOrThrow(Sync.History.EVENT_TIME);
            int indexElapsedTime = c.getColumnIndexOrThrow(Sync.History.ELAPSED_TIME);
            while (c.moveToNext()) {
                long duration = c.getLong(indexElapsedTime);
                long endTime = c.getLong(indexEventTime) + duration;
                long millisSinceStart = now - endTime;
                numSyncsTotal++;
                durationTotal += duration;
                if (millisSinceStart < MILLIS_IN_HOUR) {
                    numSyncsLastHour++;
                    durationLastHour += duration;
                }
                if (millisSinceStart < MILLIS_IN_DAY) {
                    numSyncsLastDay++;
                    durationLastDay += duration;
                }
                if (millisSinceStart < MILLIS_IN_WEEK) {
                    numSyncsLastWeek++;
                    durationLastWeek += duration;
                }
                if (millisSinceStart < MILLIS_IN_4WEEKS) {
                    numSyncsLast4Weeks++;
                    durationLast4Weeks += duration;
                }
            }
            dumpSyncIntervalHeader(sb);
            dumpSyncInterval(sb, "hour", MILLIS_IN_HOUR, numSyncsLastHour, durationLastHour);
            dumpSyncInterval(sb, "day", MILLIS_IN_DAY, numSyncsLastDay, durationLastDay);
            dumpSyncInterval(sb, "week", MILLIS_IN_WEEK, numSyncsLastWeek, durationLastWeek);
            dumpSyncInterval(sb, "4 weeks",
                    MILLIS_IN_4WEEKS, numSyncsLast4Weeks, durationLast4Weeks);
            dumpSyncInterval(sb, "total", 0, numSyncsTotal, durationTotal);
            dumpSyncIntervalFooter(sb);
        } finally {
            c.close();
        }
    }

    private void dumpSyncIntervalHeader(StringBuilder sb) {
        sb.append("Sync Stats\n");
        sb.append(" ___________________________________________________________\n");
        sb.append(" |          |        |   duration in sec   |               |\n");
        sb.append(" | interval |  count |  average |    total | % of interval |\n");
    }

    private void dumpSyncInterval(StringBuilder sb, String label,
            long interval, long numSyncs, long duration) {
        sb.append(String.format(" | %-8s | %6d | %8.1f | %8.1f",
                label, numSyncs, ((float)duration/numSyncs)/1000, (float)duration/1000));
        if (interval > 0) {
            sb.append(String.format(" | %13.2f |\n", ((float)duration/interval)*100.0));
        } else {
            sb.append(String.format(" | %13s |\n", "na"));
        }
    }

    private void dumpSyncIntervalFooter(StringBuilder sb) {
        sb.append(" |_________________________________________________________|\n");
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

    /**
     * Handles SyncOperation Messages that are posted to the associated
     * HandlerThread.
     */
    class SyncHandler extends Handler {
        // Messages that can be sent on mHandler
        private static final int MESSAGE_SYNC_FINISHED = 1;
        private static final int MESSAGE_SYNC_ALARM = 2;
        private static final int MESSAGE_CHECK_ALARMS = 3;

        public final SyncNotificationInfo mSyncNotificationInfo = new SyncNotificationInfo();
        private Long mAlarmScheduleTime = null;
        public final SyncTimeTracker mSyncTimeTracker = new SyncTimeTracker();

        // used to track if we have installed the error notification so that we don't reinstall
        // it if sync is still failing
        private boolean mErrorNotificationInstalled = false;

        /**
         * Used to keep track of whether a sync notification is active and who it is for.
         */
        class SyncNotificationInfo {
            // only valid if isActive is true
            public String account;

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
            handleSyncHandlerMessage(msg);
        }

        private void handleSyncHandlerMessage(Message msg) {
            try {
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
            String[] accounts = mAccounts;
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
            SyncOperation syncOperation;
            final Sync.Settings.QueryMap syncSettings = getSyncSettings();
            final ConnectivityManager connManager = (ConnectivityManager)
                    mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            final boolean backgroundDataSetting = connManager.getBackgroundDataSetting();
            synchronized (mSyncQueue) {
                while (true) {
                    syncOperation = mSyncQueue.head();
                    if (syncOperation == null) {
                        if (isLoggable) {
                            Log.v(TAG, "runStateIdle: no more sync operations, returning");
                        }
                        return;
                    }

                    // Sync is disabled, drop this operation.
                    if (!isSyncEnabled()) {
                        if (isLoggable) {
                            Log.v(TAG, "runStateIdle: sync disabled, dropping " + syncOperation);
                        }
                        mSyncQueue.popHead();
                        continue;
                    }

                    // skip the sync if it isn't a force and the settings are off for this provider
                    final boolean force = syncOperation.extras.getBoolean(
                            ContentResolver.SYNC_EXTRAS_FORCE, false);
                    if (!force && (!backgroundDataSetting
                            || !syncSettings.getListenForNetworkTickles()
                            || !syncSettings.getSyncProviderAutomatically(
                                    syncOperation.authority))) {
                        if (isLoggable) {
                            Log.v(TAG, "runStateIdle: sync off, dropping " + syncOperation);
                        }
                        mSyncQueue.popHead();
                        continue;
                    }

                    // skip the sync if the account of this operation no longer exists
                    if (!ArrayUtils.contains(accounts, syncOperation.account)) {
                        mSyncQueue.popHead();
                        if (isLoggable) {
                            Log.v(TAG, "runStateIdle: account not present, dropping "
                                    + syncOperation);
                        }
                        continue;
                    }

                    // go ahead and try to sync this syncOperation
                    if (isLoggable) {
                        Log.v(TAG, "runStateIdle: found sync candidate: " + syncOperation);
                    }
                    break;
                }

                // If the first SyncOperation isn't ready to run schedule a wakeup and
                // get out.
                final long now = SystemClock.elapsedRealtime();
                if (syncOperation.earliestRunTime > now) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "runStateIdle: the time is " + now + " yet the next "
                                + "sync operation is for " + syncOperation.earliestRunTime
                                + ": " + syncOperation);
                    }
                    return;
                }

                // We will do this sync. Remove it from the queue and run it outside of the
                // synchronized block.
                if (isLoggable) {
                    Log.v(TAG, "runStateIdle: we are going to sync " + syncOperation);
                }
                mSyncQueue.popHead();
            }

            String providerName = syncOperation.authority;
            ensureContentResolver();
            IContentProvider contentProvider;

            // acquire the provider and update the sync history
            try {
                contentProvider = mContentResolver.acquireProvider(providerName);
                if (contentProvider == null) {
                    Log.e(TAG, "Provider " + providerName + " doesn't exist");
                    return;
                }
                if (contentProvider.getSyncAdapter() == null) {
                    Log.e(TAG, "Provider " + providerName + " isn't syncable, " + contentProvider);
                    return;
                }
            } catch (RemoteException remoteExc) {
                Log.e(TAG, "Caught a RemoteException while preparing for sync, rescheduling "
                        + syncOperation, remoteExc);
                rescheduleWithDelay(syncOperation);
                return;
            } catch (RuntimeException exc) {
                Log.e(TAG, "Caught a RuntimeException while validating sync of " + providerName,
                        exc);
                return;
            }

            final long historyRowId = insertStartSyncEvent(syncOperation);

            try {
                ISyncAdapter syncAdapter = contentProvider.getSyncAdapter();
                ActiveSyncContext activeSyncContext = new ActiveSyncContext(syncOperation,
                        contentProvider, syncAdapter, historyRowId);
                mSyncWakeLock.acquire();
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "starting sync of " + syncOperation);
                }
                syncAdapter.startSync(activeSyncContext, syncOperation.account,
                        syncOperation.extras);
                mActiveSyncContext = activeSyncContext;
                mSyncStorageEngine.setActiveSync(mActiveSyncContext);
            } catch (RemoteException remoteExc) {
                if (Config.LOGD) {
                    Log.d(TAG, "runStateIdle: caught a RemoteException, rescheduling", remoteExc);
                }
                mActiveSyncContext = null;
                mSyncStorageEngine.setActiveSync(mActiveSyncContext);
                rescheduleWithDelay(syncOperation);
            } catch (RuntimeException exc) {
                mActiveSyncContext = null;
                mSyncStorageEngine.setActiveSync(mActiveSyncContext);
                Log.e(TAG, "Caught a RuntimeException while starting the sync " + syncOperation,
                        exc);
            }

            // no need to schedule an alarm, as that will be done by our caller.
        }

        private void runSyncFinishedOrCanceled(SyncResult syncResult) {
            boolean isLoggable = Log.isLoggable(TAG, Log.VERBOSE);
            if (isLoggable) Log.v(TAG, "runSyncFinishedOrCanceled");
            ActiveSyncContext activeSyncContext = mActiveSyncContext;
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
                    historyMessage = History.MESG_SUCCESS;
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
                try {
                    activeSyncContext.mSyncAdapter.cancelSync();
                } catch (RemoteException e) {
                    // we don't need to retry this in this case
                }
                historyMessage = History.MESG_CANCELED;
                downstreamActivity = 0;
                upstreamActivity = 0;
            }

            stopSyncEvent(activeSyncContext.mHistoryRowId, syncOperation, historyMessage,
                    upstreamActivity, downstreamActivity, elapsedTime);

            mContentResolver.releaseProvider(activeSyncContext.mContentProvider);

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
            if (syncResult.syncAlreadyInProgress) return History.ERROR_SYNC_ALREADY_IN_PROGRESS;
            if (syncResult.stats.numAuthExceptions > 0) return History.ERROR_AUTHENTICATION;
            if (syncResult.stats.numIoExceptions > 0) return History.ERROR_IO;
            if (syncResult.stats.numParseExceptions > 0) return History.ERROR_PARSE;
            if (syncResult.stats.numConflictDetectedExceptions > 0) return History.ERROR_CONFLICT;
            if (syncResult.tooManyDeletions) return History.ERROR_TOO_MANY_DELETIONS;
            if (syncResult.tooManyRetries) return History.ERROR_TOO_MANY_RETRIES;
            if (syncResult.databaseError) return History.ERROR_INTERNAL;
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
                    final boolean syncIsForced = syncOperation.extras
                            .getBoolean(ContentResolver.SYNC_EXTRAS_FORCE, false);
                    shouldInstall = timeToShowNotification || syncIsForced;
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
            syncStateIntent.putExtra("active", mNeedSyncActiveNotification);
            syncStateIntent.putExtra("failing", mNeedSyncErrorNotification);
            mContext.sendBroadcast(syncStateIntent);
        }

        private void installHandleTooManyDeletesNotification(String account, String authority,
                long numDeletes) {
            if (mNotificationMgr == null) return;
            Intent clickIntent = new Intent();
            clickIntent.setClassName("com.android.providers.subscribedfeeds",
                    "com.android.settings.SyncActivityTooManyDeletes");
            clickIntent.putExtra("account", account);
            clickIntent.putExtra("provider", authority);
            clickIntent.putExtra("numDeletes", numDeletes);

            if (!isActivityAvailable(clickIntent)) {
                Log.w(TAG, "No activity found to handle too many deletes.");
                return;
            }

            final PendingIntent pendingIntent = PendingIntent
                    .getActivity(mContext, 0, clickIntent, PendingIntent.FLAG_CANCEL_CURRENT);

            CharSequence tooManyDeletesDescFormat = mContext.getResources().getText(
                    R.string.contentServiceTooManyDeletesNotificationDesc);

            String[] authorities = authority.split(";");
            Notification notification =
                new Notification(R.drawable.stat_notify_sync_error,
                        mContext.getString(R.string.contentServiceSync),
                        System.currentTimeMillis());
            notification.setLatestEventInfo(mContext,
                    mContext.getString(R.string.contentServiceSyncNotificationTitle),
                    String.format(tooManyDeletesDescFormat.toString(), authorities[0]),
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

            EventLog.writeEvent(2720, syncOperation.authority, Sync.History.EVENT_START, source);

            return mSyncStorageEngine.insertStartSyncEvent(
                    syncOperation.account, syncOperation.authority, now, source);
        }

        public void stopSyncEvent(long rowId, SyncOperation syncOperation, String resultMessage,
                int upstreamActivity, int downstreamActivity, long elapsedTime) {
            EventLog.writeEvent(2720, syncOperation.authority, Sync.History.EVENT_STOP, syncOperation.syncSource);

            mSyncStorageEngine.stopSyncEvent(rowId, elapsedTime, resultMessage,
                    downstreamActivity, upstreamActivity);
        }
    }

    static class SyncQueue {
        private SyncStorageEngine mSyncStorageEngine;
        private final String[] COLUMNS = new String[]{
                "_id",
                "authority",
                "account",
                "extras",
                "source"
        };
        private static final int COLUMN_ID = 0;
        private static final int COLUMN_AUTHORITY = 1;
        private static final int COLUMN_ACCOUNT = 2;
        private static final int COLUMN_EXTRAS = 3;
        private static final int COLUMN_SOURCE = 4;

        private static final boolean DEBUG_CHECK_DATA_CONSISTENCY = false;

        // A priority queue of scheduled SyncOperations that is designed to make it quick
        // to find the next SyncOperation that should be considered for running.
        private final PriorityQueue<SyncOperation> mOpsByWhen = new PriorityQueue<SyncOperation>();

        // A Map of SyncOperations operationKey -> SyncOperation that is designed for
        // quick lookup of an enqueued SyncOperation.
        private final HashMap<String, SyncOperation> mOpsByKey = Maps.newHashMap();

        public SyncQueue(SyncStorageEngine syncStorageEngine) {
            mSyncStorageEngine = syncStorageEngine;
            Cursor cursor = mSyncStorageEngine.getPendingSyncsCursor(COLUMNS);
            try {
                while (cursor.moveToNext()) {
                    add(cursorToOperation(cursor),
                            true /* this is being added from the database */);
                }
            } finally {
                cursor.close();
                if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(true /* check the DB */);
            }
        }

        public boolean add(SyncOperation operation) {
            return add(new SyncOperation(operation),
                    false /* this is not coming from the database */);
        }

        private boolean add(SyncOperation operation, boolean fromDatabase) {
            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(!fromDatabase);

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
            // and this operation isn't forced, ignore this operation
            if (existingOperation != null && existingOperation.delay > 0) {
                if (!operation.extras.getBoolean(ContentResolver.SYNC_EXTRAS_FORCE, false)) {
                    return false;
                }
            }

            if (existingOperation != null
                    && operation.earliestRunTime >= existingOperation.earliestRunTime) {
                if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(!fromDatabase);
                return false;
            }

            if (existingOperation != null) {
                removeByKey(operationKey);
            }

            if (operation.rowId == null) {
                byte[] extrasData = null;
                Parcel parcel = Parcel.obtain();
                try {
                    operation.extras.writeToParcel(parcel, 0);
                    extrasData = parcel.marshall();
                } finally {
                    parcel.recycle();
                }
                ContentValues values = new ContentValues();
                values.put("account", operation.account);
                values.put("authority", operation.authority);
                values.put("source", operation.syncSource);
                values.put("extras", extrasData);
                Uri pendingUri = mSyncStorageEngine.insertIntoPending(values);
                operation.rowId = pendingUri == null ? null : ContentUris.parseId(pendingUri);
                if (operation.rowId == null) {
                    throw new IllegalStateException("error adding pending sync operation "
                            + operation);
                }
            }

            if (DEBUG_CHECK_DATA_CONSISTENCY) {
                debugCheckDataStructures(
                        false /* don't compare with the DB, since we know
                               it is inconsistent right now */ );
            }
            mOpsByKey.put(operationKey, operation);
            mOpsByWhen.add(operation);
            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(!fromDatabase);
            return true;
        }

        public void removeByKey(String operationKey) {
            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(true /* check the DB */);
            SyncOperation operationToRemove = mOpsByKey.remove(operationKey);
            if (!mOpsByWhen.remove(operationToRemove)) {
                throw new IllegalStateException(
                        "unable to find " + operationToRemove + " in mOpsByWhen");
            }

            if (mSyncStorageEngine.deleteFromPending(operationToRemove.rowId) != 1) {
                throw new IllegalStateException("unable to find pending row for "
                        + operationToRemove);
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

            if (mSyncStorageEngine.deleteFromPending(operation.rowId) != 1) {
                throw new IllegalStateException("unable to find pending row for " + operation);
            }

            if (DEBUG_CHECK_DATA_CONSISTENCY) debugCheckDataStructures(true /* check the DB */);
        }

        public void clear(String account, String authority) {
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

                if (mSyncStorageEngine.deleteFromPending(syncOperation.rowId) != 1) {
                    throw new IllegalStateException("unable to find pending row for "
                            + syncOperation);
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
                // check that the DB contains the same rows as the in-memory data structures
                Cursor cursor = mSyncStorageEngine.getPendingSyncsCursor(COLUMNS);
                try {
                    if (mOpsByKey.size() != cursor.getCount()) {
                        StringBuilder sb = new StringBuilder();
                        DatabaseUtils.dumpCursor(cursor, sb);
                        sb.append("\n");
                        dump(sb);
                        throw new IllegalStateException("DB size mismatch: "
                                + mOpsByKey .size() + " != " + cursor.getCount() + "\n"
                                + sb.toString());
                    }
                } finally {
                    cursor.close();
                }
            }
        }

        private SyncOperation cursorToOperation(Cursor cursor) {
            byte[] extrasData = cursor.getBlob(COLUMN_EXTRAS);
            Bundle extras;
            Parcel parcel = Parcel.obtain();
            try {
                parcel.unmarshall(extrasData, 0, extrasData.length);
                parcel.setDataPosition(0);
                extras = parcel.readBundle();
            } catch (RuntimeException e) {
                // A RuntimeException is thrown if we were unable to parse the parcel.
                // Create an empty parcel in this case.
                extras = new Bundle();
            } finally {
                parcel.recycle();
            }

            SyncOperation syncOperation = new SyncOperation(
                    cursor.getString(COLUMN_ACCOUNT),
                    cursor.getInt(COLUMN_SOURCE),
                    cursor.getString(COLUMN_AUTHORITY),
                    extras,
                    0 /* delay */);
            syncOperation.rowId = cursor.getLong(COLUMN_ID);
            return syncOperation;
        }
    }
}
