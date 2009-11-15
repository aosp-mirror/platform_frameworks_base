/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.IBackupAgent;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.provider.Settings;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseArray;

import android.backup.IBackupManager;
import android.backup.IRestoreObserver;
import android.backup.IRestoreSession;
import android.backup.RestoreSet;

import com.android.internal.backup.BackupConstants;
import com.android.internal.backup.LocalTransport;
import com.android.internal.backup.IBackupTransport;

import com.android.server.PackageManagerBackupAgent;
import com.android.server.PackageManagerBackupAgent.Metadata;

import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

class BackupManagerService extends IBackupManager.Stub {
    private static final String TAG = "BackupManagerService";
    private static final boolean DEBUG = false;

    // How often we perform a backup pass.  Privileged external callers can
    // trigger an immediate pass.
    private static final long BACKUP_INTERVAL = AlarmManager.INTERVAL_HOUR;

    // Random variation in backup scheduling time to avoid server load spikes
    private static final int FUZZ_MILLIS = 5 * 60 * 1000;

    // The amount of time between the initial provisioning of the device and
    // the first backup pass.
    private static final long FIRST_BACKUP_INTERVAL = 12 * AlarmManager.INTERVAL_HOUR;

    private static final String RUN_BACKUP_ACTION = "android.backup.intent.RUN";
    private static final String RUN_INITIALIZE_ACTION = "android.backup.intent.INIT";
    private static final String RUN_CLEAR_ACTION = "android.backup.intent.CLEAR";
    private static final int MSG_RUN_BACKUP = 1;
    private static final int MSG_RUN_FULL_BACKUP = 2;
    private static final int MSG_RUN_RESTORE = 3;
    private static final int MSG_RUN_CLEAR = 4;
    private static final int MSG_RUN_INITIALIZE = 5;

    // Event tags -- see system/core/logcat/event-log-tags
    private static final int BACKUP_DATA_CHANGED_EVENT = 2820;
    private static final int BACKUP_START_EVENT = 2821;
    private static final int BACKUP_TRANSPORT_FAILURE_EVENT = 2822;
    private static final int BACKUP_AGENT_FAILURE_EVENT = 2823;
    private static final int BACKUP_PACKAGE_EVENT = 2824;
    private static final int BACKUP_SUCCESS_EVENT = 2825;
    private static final int BACKUP_RESET_EVENT = 2826;
    private static final int BACKUP_INITIALIZE_EVENT = 2827;

    private static final int RESTORE_START_EVENT = 2830;
    private static final int RESTORE_TRANSPORT_FAILURE_EVENT = 2831;
    private static final int RESTORE_AGENT_FAILURE_EVENT = 2832;
    private static final int RESTORE_PACKAGE_EVENT = 2833;
    private static final int RESTORE_SUCCESS_EVENT = 2834;

    // Timeout interval for deciding that a bind or clear-data has taken too long
    static final long TIMEOUT_INTERVAL = 10 * 1000;

    private Context mContext;
    private PackageManager mPackageManager;
    private IActivityManager mActivityManager;
    private PowerManager mPowerManager;
    private AlarmManager mAlarmManager;

    boolean mEnabled;   // access to this is synchronized on 'this'
    boolean mProvisioned;
    PowerManager.WakeLock mWakelock;
    final BackupHandler mBackupHandler = new BackupHandler();
    PendingIntent mRunBackupIntent, mRunInitIntent;
    BroadcastReceiver mRunBackupReceiver, mRunInitReceiver;
    // map UIDs to the set of backup client services within that UID's app set
    final SparseArray<HashSet<ApplicationInfo>> mBackupParticipants
        = new SparseArray<HashSet<ApplicationInfo>>();
    // set of backup services that have pending changes
    class BackupRequest {
        public ApplicationInfo appInfo;
        public boolean fullBackup;

        BackupRequest(ApplicationInfo app, boolean isFull) {
            appInfo = app;
            fullBackup = isFull;
        }

        public String toString() {
            return "BackupRequest{app=" + appInfo + " full=" + fullBackup + "}";
        }
    }
    // Backups that we haven't started yet.
    HashMap<ApplicationInfo,BackupRequest> mPendingBackups
            = new HashMap<ApplicationInfo,BackupRequest>();

    // Pseudoname that we use for the Package Manager metadata "package"
    static final String PACKAGE_MANAGER_SENTINEL = "@pm@";

    // locking around the pending-backup management
    final Object mQueueLock = new Object();

    // The thread performing the sequence of queued backups binds to each app's agent
    // in succession.  Bind notifications are asynchronously delivered through the
    // Activity Manager; use this lock object to signal when a requested binding has
    // completed.
    final Object mAgentConnectLock = new Object();
    IBackupAgent mConnectedAgent;
    volatile boolean mConnecting;
    volatile boolean mBackupOrRestoreInProgress = false;
    volatile long mLastBackupPass;
    volatile long mNextBackupPass;

    // A similar synchronization mechanism around clearing apps' data for restore
    final Object mClearDataLock = new Object();
    volatile boolean mClearingData;

    // Transport bookkeeping
    final HashMap<String,IBackupTransport> mTransports
            = new HashMap<String,IBackupTransport>();
    String mCurrentTransport;
    IBackupTransport mLocalTransport, mGoogleTransport;
    RestoreSession mActiveRestoreSession;

    class RestoreParams {
        public IBackupTransport transport;
        public IRestoreObserver observer;
        public long token;

        RestoreParams(IBackupTransport _transport, IRestoreObserver _obs, long _token) {
            transport = _transport;
            observer = _obs;
            token = _token;
        }
    }

    class ClearParams {
        public IBackupTransport transport;
        public PackageInfo packageInfo;

        ClearParams(IBackupTransport _transport, PackageInfo _info) {
            transport = _transport;
            packageInfo = _info;
        }
    }

    // Where we keep our journal files and other bookkeeping
    File mBaseStateDir;
    File mDataDir;
    File mJournalDir;
    File mJournal;

    // Keep a log of all the apps we've ever backed up
    private File mEverStored;
    HashSet<String> mEverStoredApps = new HashSet<String>();

    // Persistently track the need to do a full init
    static final String INIT_SENTINEL_FILE_NAME = "_need_init_";
    HashSet<String> mPendingInits = new HashSet<String>();  // transport names
    volatile boolean mInitInProgress = false;

    public BackupManagerService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mActivityManager = ActivityManagerNative.getDefault();

        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        // Set up our bookkeeping
        boolean areEnabled = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.BACKUP_ENABLED, 0) != 0;
        mProvisioned = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.BACKUP_PROVISIONED, 0) != 0;
        mBaseStateDir = new File(Environment.getDataDirectory(), "backup");
        mDataDir = Environment.getDownloadCacheDirectory();

        // Alarm receivers for scheduled backups & initialization operations
        mRunBackupReceiver = new RunBackupReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(RUN_BACKUP_ACTION);
        context.registerReceiver(mRunBackupReceiver, filter,
                android.Manifest.permission.BACKUP, null);

        mRunInitReceiver = new RunInitializeReceiver();
        filter = new IntentFilter();
        filter.addAction(RUN_INITIALIZE_ACTION);
        context.registerReceiver(mRunInitReceiver, filter,
                android.Manifest.permission.BACKUP, null);

        Intent backupIntent = new Intent(RUN_BACKUP_ACTION);
        backupIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mRunBackupIntent = PendingIntent.getBroadcast(context, MSG_RUN_BACKUP, backupIntent, 0);

        Intent initIntent = new Intent(RUN_INITIALIZE_ACTION);
        backupIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mRunInitIntent = PendingIntent.getBroadcast(context, MSG_RUN_INITIALIZE, initIntent, 0);

        // Set up the backup-request journaling
        mJournalDir = new File(mBaseStateDir, "pending");
        mJournalDir.mkdirs();   // creates mBaseStateDir along the way
        mJournal = null;        // will be created on first use

        // Set up the various sorts of package tracking we do
        initPackageTracking();

        // Build our mapping of uid to backup client services.  This implicitly
        // schedules a backup pass on the Package Manager metadata the first
        // time anything needs to be backed up.
        synchronized (mBackupParticipants) {
            addPackageParticipantsLocked(null);
        }

        // Set up our transport options and initialize the default transport
        // TODO: Have transports register themselves somehow?
        // TODO: Don't create transports that we don't need to?
        mLocalTransport = new LocalTransport(context);  // This is actually pretty cheap
        ComponentName localName = new ComponentName(context, LocalTransport.class);
        registerTransport(localName.flattenToShortString(), mLocalTransport);

        mGoogleTransport = null;
        mCurrentTransport = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.BACKUP_TRANSPORT);
        if ("".equals(mCurrentTransport)) {
            mCurrentTransport = null;
        }
        if (DEBUG) Log.v(TAG, "Starting with transport " + mCurrentTransport);

        // Attach to the Google backup transport.  When this comes up, it will set
        // itself as the current transport because we explicitly reset mCurrentTransport
        // to null.
        Intent intent = new Intent().setComponent(new ComponentName(
                "com.google.android.backup",
                "com.google.android.backup.BackupTransportService"));
        context.bindService(intent, mGoogleConnection, Context.BIND_AUTO_CREATE);

        // Now that we know about valid backup participants, parse any
        // leftover journal files into the pending backup set
        parseLeftoverJournals();

        // Power management
        mWakelock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "backup");

        // Start the backup passes going
        setBackupEnabled(areEnabled);
    }

    private class RunBackupReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            if (RUN_BACKUP_ACTION.equals(intent.getAction())) {
                synchronized (mQueueLock) {
                    if (mPendingInits.size() > 0) {
                        // If there are pending init operations, we process those
                        // and then settle into the usual periodic backup schedule.
                        if (DEBUG) Log.v(TAG, "Init pending at scheduled backup");
                        try {
                            mAlarmManager.cancel(mRunInitIntent);
                            mRunInitIntent.send();
                        } catch (PendingIntent.CanceledException ce) {
                            Log.e(TAG, "Run init intent cancelled");
                            // can't really do more than bail here
                        }
                    } else {
                        // Don't run backups now if we're disabled, not yet
                        // fully set up, in the middle of a backup already,
                        // or racing with an initialize pass.
                        if (mEnabled && mProvisioned
                                && !mBackupOrRestoreInProgress && !mInitInProgress) {
                            if (DEBUG) Log.v(TAG, "Running a backup pass");
                            mBackupOrRestoreInProgress = true;

                            // Acquire the wakelock and pass it to the backup thread.  it will
                            // be released once backup concludes.
                            mWakelock.acquire();

                            Message msg = mBackupHandler.obtainMessage(MSG_RUN_BACKUP);
                            mBackupHandler.sendMessage(msg);
                        } else {
                            Log.w(TAG, "Backup pass but e=" + mEnabled + " p=" + mProvisioned
                                    + " b=" + mBackupOrRestoreInProgress + " i=" + mInitInProgress);
                        }
                    }
                }
            }
        }
    }

    private class RunInitializeReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            if (RUN_INITIALIZE_ACTION.equals(intent.getAction())) {
                synchronized (mQueueLock) {
                    if (DEBUG) Log.v(TAG, "Running a device init");
                    mInitInProgress = true;

                    // Acquire the wakelock and pass it to the init thread.  it will
                    // be released once init concludes.
                    mWakelock.acquire();

                    Message msg = mBackupHandler.obtainMessage(MSG_RUN_INITIALIZE);
                    mBackupHandler.sendMessage(msg);
                }
            }
        }
    }

    private void initPackageTracking() {
        if (DEBUG) Log.v(TAG, "Initializing package tracking");

        // Keep a log of what apps we've ever backed up.  Because we might have
        // rebooted in the middle of an operation that was removing something from
        // this log, we sanity-check its contents here and reconstruct it.
        mEverStored = new File(mBaseStateDir, "processed");
        File tempProcessedFile = new File(mBaseStateDir, "processed.new");

        // If we were in the middle of removing something from the ever-backed-up
        // file, there might be a transient "processed.new" file still present.
        // Ignore it -- we'll validate "processed" against the current package set.
        if (tempProcessedFile.exists()) {
            tempProcessedFile.delete();
        }

        // If there are previous contents, parse them out then start a new
        // file to continue the recordkeeping.
        if (mEverStored.exists()) {
            RandomAccessFile temp = null;
            RandomAccessFile in = null;

            try {
                temp = new RandomAccessFile(tempProcessedFile, "rws");
                in = new RandomAccessFile(mEverStored, "r");

                while (true) {
                    PackageInfo info;
                    String pkg = in.readUTF();
                    try {
                        info = mPackageManager.getPackageInfo(pkg, 0);
                        mEverStoredApps.add(pkg);
                        temp.writeUTF(pkg);
                        if (DEBUG) Log.v(TAG, "   + " + pkg);
                    } catch (NameNotFoundException e) {
                        // nope, this package was uninstalled; don't include it
                        if (DEBUG) Log.v(TAG, "   - " + pkg);
                    }
                }
            } catch (EOFException e) {
                // Once we've rewritten the backup history log, atomically replace the
                // old one with the new one then reopen the file for continuing use.
                if (!tempProcessedFile.renameTo(mEverStored)) {
                    Log.e(TAG, "Error renaming " + tempProcessedFile + " to " + mEverStored);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error in processed file", e);
            } finally {
                try { if (temp != null) temp.close(); } catch (IOException e) {}
                try { if (in != null) in.close(); } catch (IOException e) {}
            }
        }

        // Register for broadcasts about package install, etc., so we can
        // update the provider list.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    private void parseLeftoverJournals() {
        for (File f : mJournalDir.listFiles()) {
            if (mJournal == null || f.compareTo(mJournal) != 0) {
                // This isn't the current journal, so it must be a leftover.  Read
                // out the package names mentioned there and schedule them for
                // backup.
                RandomAccessFile in = null;
                try {
                    Log.i(TAG, "Found stale backup journal, scheduling:");
                    in = new RandomAccessFile(f, "r");
                    while (true) {
                        String packageName = in.readUTF();
                        Log.i(TAG, "    + " + packageName);
                        dataChanged(packageName);
                    }
                } catch (EOFException e) {
                    // no more data; we're done
                } catch (Exception e) {
                    Log.e(TAG, "Can't read " + f, e);
                } finally {
                    // close/delete the file
                    try { if (in != null) in.close(); } catch (IOException e) {}
                    f.delete();
                }
            }
        }
    }

    // Maintain persistent state around whether need to do an initialize operation.
    // Must be called with the queue lock held.
    void recordInitPendingLocked(boolean isPending, String transportName) {
        if (DEBUG) Log.i(TAG, "recordInitPendingLocked: " + isPending
                + " on transport " + transportName);
        try {
            IBackupTransport transport = getTransport(transportName);
            String transportDirName = transport.transportDirName();
            File stateDir = new File(mBaseStateDir, transportDirName);
            File initPendingFile = new File(stateDir, INIT_SENTINEL_FILE_NAME);

            if (isPending) {
                // We need an init before we can proceed with sending backup data.
                // Record that with an entry in our set of pending inits, as well as
                // journaling it via creation of a sentinel file.
                mPendingInits.add(transportName);
                try {
                    (new FileOutputStream(initPendingFile)).close();
                } catch (IOException ioe) {
                    // Something is badly wrong with our permissions; just try to move on
                }
            } else {
                // No more initialization needed; wipe the journal and reset our state.
                initPendingFile.delete();
                mPendingInits.remove(transportName);
            }
        } catch (RemoteException e) {
            // can't happen; the transport is local
        }
    }

    // Reset all of our bookkeeping, in response to having been told that
    // the backend data has been wiped [due to idle expiry, for example],
    // so we must re-upload all saved settings.
    void resetBackupState(File stateFileDir) {
        synchronized (mQueueLock) {
            // Wipe the "what we've ever backed up" tracking
            mEverStoredApps.clear();
            mEverStored.delete();

            // Remove all the state files
            for (File sf : stateFileDir.listFiles()) {
                // ... but don't touch the needs-init sentinel
                if (!sf.getName().equals(INIT_SENTINEL_FILE_NAME)) {
                    sf.delete();
                }
            }

            // Enqueue a new backup of every participant
            int N = mBackupParticipants.size();
            for (int i=0; i<N; i++) {
                int uid = mBackupParticipants.keyAt(i);
                HashSet<ApplicationInfo> participants = mBackupParticipants.valueAt(i);
                for (ApplicationInfo app: participants) {
                    dataChanged(app.packageName);
                }
            }
        }
    }

    // Add a transport to our set of available backends
    private void registerTransport(String name, IBackupTransport transport) {
        synchronized (mTransports) {
            if (DEBUG) Log.v(TAG, "Registering transport " + name + " = " + transport);
            mTransports.put(name, transport);
        }

        // If the init sentinel file exists, we need to be sure to perform the init
        // as soon as practical.  We also create the state directory at registration
        // time to ensure it's present from the outset.
        try {
            String transportName = transport.transportDirName();
            File stateDir = new File(mBaseStateDir, transportName);
            stateDir.mkdirs();

            File initSentinel = new File(stateDir, INIT_SENTINEL_FILE_NAME);
            if (initSentinel.exists()) {
                synchronized (mQueueLock) {
                    mPendingInits.add(transportName);

                    // TODO: pick a better starting time than now + 1 minute
                    long delay = 1000 * 60; // one minute, in milliseconds
                    mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + delay, mRunInitIntent);
                }
            }
        } catch (RemoteException e) {
            // can't happen, the transport is local
        }
    }

    // ----- Track installation/removal of packages -----
    BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "Received broadcast " + intent);

            Uri uri = intent.getData();
            if (uri == null) {
                return;
            }
            String pkgName = uri.getSchemeSpecificPart();
            if (pkgName == null) {
                return;
            }

            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                synchronized (mBackupParticipants) {
                    Bundle extras = intent.getExtras();
                    if (extras != null && extras.getBoolean(Intent.EXTRA_REPLACING, false)) {
                        // The package was just upgraded
                        updatePackageParticipantsLocked(pkgName);
                    } else {
                        // The package was just added
                        addPackageParticipantsLocked(pkgName);
                    }
                }
            }
            else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                Bundle extras = intent.getExtras();
                if (extras != null && extras.getBoolean(Intent.EXTRA_REPLACING, false)) {
                    // The package is being updated.  We'll receive a PACKAGE_ADDED shortly.
                } else {
                    synchronized (mBackupParticipants) {
                        removePackageParticipantsLocked(pkgName);
                    }
                }
            }
        }
    };

    // ----- Track connection to GoogleBackupTransport service -----
    ServiceConnection mGoogleConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Log.v(TAG, "Connected to Google transport");
            mGoogleTransport = IBackupTransport.Stub.asInterface(service);
            registerTransport(name.flattenToShortString(), mGoogleTransport);
        }

        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Log.v(TAG, "Disconnected from Google transport");
            mGoogleTransport = null;
            registerTransport(name.flattenToShortString(), null);
        }
    };

    // ----- Run the actual backup process asynchronously -----

    private class BackupHandler extends Handler {
        public void handleMessage(Message msg) {

            switch (msg.what) {
            case MSG_RUN_BACKUP:
            {
                mLastBackupPass = System.currentTimeMillis();
                mNextBackupPass = mLastBackupPass + BACKUP_INTERVAL;

                IBackupTransport transport = getTransport(mCurrentTransport);
                if (transport == null) {
                    Log.v(TAG, "Backup requested but no transport available");
                    synchronized (mQueueLock) {
                        mBackupOrRestoreInProgress = false;
                    }
                    mWakelock.release();
                    break;
                }

                // snapshot the pending-backup set and work on that
                ArrayList<BackupRequest> queue = new ArrayList<BackupRequest>();
                synchronized (mQueueLock) {
                    // Do we have any work to do?
                    if (mPendingBackups.size() > 0) {
                        for (BackupRequest b: mPendingBackups.values()) {
                            queue.add(b);
                        }
                        Log.v(TAG, "clearing pending backups");
                        mPendingBackups.clear();

                        // Start a new backup-queue journal file too
                        File oldJournal = mJournal;
                        mJournal = null;

                        // At this point, we have started a new journal file, and the old
                        // file identity is being passed to the backup processing thread.
                        // When it completes successfully, that old journal file will be
                        // deleted.  If we crash prior to that, the old journal is parsed
                        // at next boot and the journaled requests fulfilled.
                        (new PerformBackupThread(transport, queue, oldJournal)).start();
                    } else {
                        Log.v(TAG, "Backup requested but nothing pending");
                        synchronized (mQueueLock) {
                            mBackupOrRestoreInProgress = false;
                        }
                        mWakelock.release();
                    }
                }
                break;
            }

            case MSG_RUN_FULL_BACKUP:
                break;

            case MSG_RUN_RESTORE:
            {
                RestoreParams params = (RestoreParams)msg.obj;
                Log.d(TAG, "MSG_RUN_RESTORE observer=" + params.observer);
                (new PerformRestoreThread(params.transport, params.observer,
                        params.token)).start();
                break;
            }

            case MSG_RUN_CLEAR:
            {
                ClearParams params = (ClearParams)msg.obj;
                (new PerformClearThread(params.transport, params.packageInfo)).start();
                break;
            }

            case MSG_RUN_INITIALIZE:
            {
                HashSet<String> queue;

                // Snapshot the pending-init queue and work on that
                synchronized (mQueueLock) {
                    queue = new HashSet<String>(mPendingInits);
                    mPendingInits.clear();
                }

                (new PerformInitializeThread(queue)).start();
                break;
            }
            }
        }
    }

    // Add the backup agents in the given package to our set of known backup participants.
    // If 'packageName' is null, adds all backup agents in the whole system.
    void addPackageParticipantsLocked(String packageName) {
        // Look for apps that define the android:backupAgent attribute
        if (DEBUG) Log.v(TAG, "addPackageParticipantsLocked: " + packageName);
        List<PackageInfo> targetApps = allAgentPackages();
        addPackageParticipantsLockedInner(packageName, targetApps);
    }

    private void addPackageParticipantsLockedInner(String packageName,
            List<PackageInfo> targetPkgs) {
        if (DEBUG) {
            Log.v(TAG, "Adding " + targetPkgs.size() + " backup participants:");
            for (PackageInfo p : targetPkgs) {
                Log.v(TAG, "    " + p + " agent=" + p.applicationInfo.backupAgentName
                        + " uid=" + p.applicationInfo.uid
                        + " killAfterRestore="
                        + (((p.applicationInfo.flags & ApplicationInfo.FLAG_KILL_AFTER_RESTORE) != 0) ? "true" : "false")
                        + " restoreNeedsApplication="
                        + (((p.applicationInfo.flags & ApplicationInfo.FLAG_RESTORE_NEEDS_APPLICATION) != 0) ? "true" : "false")
                        );
            }
        }

        for (PackageInfo pkg : targetPkgs) {
            if (packageName == null || pkg.packageName.equals(packageName)) {
                int uid = pkg.applicationInfo.uid;
                HashSet<ApplicationInfo> set = mBackupParticipants.get(uid);
                if (set == null) {
                    set = new HashSet<ApplicationInfo>();
                    mBackupParticipants.put(uid, set);
                }
                set.add(pkg.applicationInfo);

                // If we've never seen this app before, schedule a backup for it
                if (!mEverStoredApps.contains(pkg.packageName)) {
                    if (DEBUG) Log.i(TAG, "New app " + pkg.packageName
                            + " never backed up; scheduling");
                    dataChanged(pkg.packageName);
                }
            }
        }
    }

    // Remove the given package's entry from our known active set.  If
    // 'packageName' is null, *all* participating apps will be removed.
    void removePackageParticipantsLocked(String packageName) {
        if (DEBUG) Log.v(TAG, "removePackageParticipantsLocked: " + packageName);
        List<PackageInfo> allApps = null;
        if (packageName != null) {
            allApps = new ArrayList<PackageInfo>();
            try {
                int flags = PackageManager.GET_SIGNATURES;
                allApps.add(mPackageManager.getPackageInfo(packageName, flags));
            } catch (Exception e) {
                // just skip it (???)
            }
        } else {
            // all apps with agents
            allApps = allAgentPackages();
        }
        removePackageParticipantsLockedInner(packageName, allApps);
    }

    private void removePackageParticipantsLockedInner(String packageName,
            List<PackageInfo> agents) {
        if (DEBUG) {
            Log.v(TAG, "removePackageParticipantsLockedInner (" + packageName
                    + ") removing " + agents.size() + " entries");
            for (PackageInfo p : agents) {
                Log.v(TAG, "    - " + p);
            }
        }
        for (PackageInfo pkg : agents) {
            if (packageName == null || pkg.packageName.equals(packageName)) {
                int uid = pkg.applicationInfo.uid;
                HashSet<ApplicationInfo> set = mBackupParticipants.get(uid);
                if (set != null) {
                    // Find the existing entry with the same package name, and remove it.
                    // We can't just remove(app) because the instances are different.
                    for (ApplicationInfo entry: set) {
                        if (entry.packageName.equals(pkg.packageName)) {
                            set.remove(entry);
                            removeEverBackedUp(pkg.packageName);
                            break;
                        }
                    }
                    if (set.size() == 0) {
                        mBackupParticipants.delete(uid);
                    }
                }
            }
        }
    }

    // Returns the set of all applications that define an android:backupAgent attribute
    List<PackageInfo> allAgentPackages() {
        // !!! TODO: cache this and regenerate only when necessary
        int flags = PackageManager.GET_SIGNATURES;
        List<PackageInfo> packages = mPackageManager.getInstalledPackages(flags);
        int N = packages.size();
        for (int a = N-1; a >= 0; a--) {
            PackageInfo pkg = packages.get(a);
            try {
                ApplicationInfo app = pkg.applicationInfo;
                if (((app.flags&ApplicationInfo.FLAG_ALLOW_BACKUP) == 0)
                        || app.backupAgentName == null
                        || (mPackageManager.checkPermission(android.Manifest.permission.BACKUP_DATA,
                                pkg.packageName) != PackageManager.PERMISSION_GRANTED)) {
                    packages.remove(a);
                }
                else {
                    // we will need the shared library path, so look that up and store it here
                    app = mPackageManager.getApplicationInfo(pkg.packageName,
                            PackageManager.GET_SHARED_LIBRARY_FILES);
                    pkg.applicationInfo.sharedLibraryFiles = app.sharedLibraryFiles;
                }
            } catch (NameNotFoundException e) {
                packages.remove(a);
            }
        }
        return packages;
    }

    // Reset the given package's known backup participants.  Unlike add/remove, the update
    // action cannot be passed a null package name.
    void updatePackageParticipantsLocked(String packageName) {
        if (packageName == null) {
            Log.e(TAG, "updatePackageParticipants called with null package name");
            return;
        }
        if (DEBUG) Log.v(TAG, "updatePackageParticipantsLocked: " + packageName);

        // brute force but small code size
        List<PackageInfo> allApps = allAgentPackages();
        removePackageParticipantsLockedInner(packageName, allApps);
        addPackageParticipantsLockedInner(packageName, allApps);
    }

    // Called from the backup thread: record that the given app has been successfully
    // backed up at least once
    void logBackupComplete(String packageName) {
        if (packageName.equals(PACKAGE_MANAGER_SENTINEL)) return;

        synchronized (mEverStoredApps) {
            if (!mEverStoredApps.add(packageName)) return;

            RandomAccessFile out = null;
            try {
                out = new RandomAccessFile(mEverStored, "rws");
                out.seek(out.length());
                out.writeUTF(packageName);
            } catch (IOException e) {
                Log.e(TAG, "Can't log backup of " + packageName + " to " + mEverStored);
            } finally {
                try { if (out != null) out.close(); } catch (IOException e) {}
            }
        }
    }

    // Remove our awareness of having ever backed up the given package
    void removeEverBackedUp(String packageName) {
        if (DEBUG) Log.v(TAG, "Removing backed-up knowledge of " + packageName + ", new set:");

        synchronized (mEverStoredApps) {
            // Rewrite the file and rename to overwrite.  If we reboot in the middle,
            // we'll recognize on initialization time that the package no longer
            // exists and fix it up then.
            File tempKnownFile = new File(mBaseStateDir, "processed.new");
            RandomAccessFile known = null;
            try {
                known = new RandomAccessFile(tempKnownFile, "rws");
                mEverStoredApps.remove(packageName);
                for (String s : mEverStoredApps) {
                    known.writeUTF(s);
                    if (DEBUG) Log.v(TAG, "    " + s);
                }
                known.close();
                known = null;
                if (!tempKnownFile.renameTo(mEverStored)) {
                    throw new IOException("Can't rename " + tempKnownFile + " to " + mEverStored);
                }
            } catch (IOException e) {
                // Bad: we couldn't create the new copy.  For safety's sake we
                // abandon the whole process and remove all what's-backed-up
                // state entirely, meaning we'll force a backup pass for every
                // participant on the next boot or [re]install.
                Log.w(TAG, "Error rewriting " + mEverStored, e);
                mEverStoredApps.clear();
                tempKnownFile.delete();
                mEverStored.delete();
            } finally {
                try { if (known != null) known.close(); } catch (IOException e) {}
            }
        }
    }

    // Return the given transport
    private IBackupTransport getTransport(String transportName) {
        synchronized (mTransports) {
            IBackupTransport transport = mTransports.get(transportName);
            if (transport == null) {
                Log.w(TAG, "Requested unavailable transport: " + transportName);
            }
            return transport;
        }
    }

    // fire off a backup agent, blocking until it attaches or times out
    IBackupAgent bindToAgentSynchronous(ApplicationInfo app, int mode) {
        IBackupAgent agent = null;
        synchronized(mAgentConnectLock) {
            mConnecting = true;
            mConnectedAgent = null;
            try {
                if (mActivityManager.bindBackupAgent(app, mode)) {
                    Log.d(TAG, "awaiting agent for " + app);

                    // success; wait for the agent to arrive
                    // only wait 10 seconds for the clear data to happen
                    long timeoutMark = System.currentTimeMillis() + TIMEOUT_INTERVAL;
                    while (mConnecting && mConnectedAgent == null
                            && (System.currentTimeMillis() < timeoutMark)) {
                        try {
                            mAgentConnectLock.wait(5000);
                        } catch (InterruptedException e) {
                            // just bail
                            return null;
                        }
                    }

                    // if we timed out with no connect, abort and move on
                    if (mConnecting == true) {
                        Log.w(TAG, "Timeout waiting for agent " + app);
                        return null;
                    }
                    agent = mConnectedAgent;
                }
            } catch (RemoteException e) {
                // can't happen
            }
        }
        return agent;
    }

    // clear an application's data, blocking until the operation completes or times out
    void clearApplicationDataSynchronous(String packageName) {
        // Don't wipe packages marked allowClearUserData=false
        try {
            PackageInfo info = mPackageManager.getPackageInfo(packageName, 0);
            if ((info.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA) == 0) {
                if (DEBUG) Log.i(TAG, "allowClearUserData=false so not wiping "
                        + packageName);
                return;
            }
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Tried to clear data for " + packageName + " but not found");
            return;
        }

        ClearDataObserver observer = new ClearDataObserver();

        synchronized(mClearDataLock) {
            mClearingData = true;
            /* This is causing some critical processes to be killed during setup.
               Temporarily revert this change until we find a better solution.
            try {
                mActivityManager.clearApplicationUserData(packageName, observer);
            } catch (RemoteException e) {
                // can't happen because the activity manager is in this process
            }
            */
            mPackageManager.clearApplicationUserData(packageName, observer);

            // only wait 10 seconds for the clear data to happen
            long timeoutMark = System.currentTimeMillis() + TIMEOUT_INTERVAL;
            while (mClearingData && (System.currentTimeMillis() < timeoutMark)) {
                try {
                    mClearDataLock.wait(5000);
                } catch (InterruptedException e) {
                    // won't happen, but still.
                    mClearingData = false;
                }
            }
        }
    }

    class ClearDataObserver extends IPackageDataObserver.Stub {
        public void onRemoveCompleted(String packageName, boolean succeeded) {
            synchronized(mClearDataLock) {
                mClearingData = false;
                mClearDataLock.notifyAll();
            }
        }
    }

    // ----- Back up a set of applications via a worker thread -----

    class PerformBackupThread extends Thread {
        private static final String TAG = "PerformBackupThread";
        IBackupTransport mTransport;
        ArrayList<BackupRequest> mQueue;
        File mStateDir;
        File mJournal;

        public PerformBackupThread(IBackupTransport transport, ArrayList<BackupRequest> queue,
                File journal) {
            mTransport = transport;
            mQueue = queue;
            mJournal = journal;

            try {
                mStateDir = new File(mBaseStateDir, transport.transportDirName());
            } catch (RemoteException e) {
                // can't happen; the transport is local
            }
        }

        @Override
        public void run() {
            int status = BackupConstants.TRANSPORT_OK;
            long startRealtime = SystemClock.elapsedRealtime();
            if (DEBUG) Log.v(TAG, "Beginning backup of " + mQueue.size() + " targets");

            // Backups run at background priority
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            try {
                EventLog.writeEvent(BACKUP_START_EVENT, mTransport.transportDirName());

                // If we haven't stored package manager metadata yet, we must init the transport.
                File pmState = new File(mStateDir, PACKAGE_MANAGER_SENTINEL);
                if (status == BackupConstants.TRANSPORT_OK && pmState.length() <= 0) {
                    Log.i(TAG, "Initializing (wiping) backup state and transport storage");
                    resetBackupState(mStateDir);  // Just to make sure.
                    status = mTransport.initializeDevice();
                    if (status == BackupConstants.TRANSPORT_OK) {
                        EventLog.writeEvent(BACKUP_INITIALIZE_EVENT);
                    } else {
                        EventLog.writeEvent(BACKUP_TRANSPORT_FAILURE_EVENT, "(initialize)");
                        Log.e(TAG, "Transport error in initializeDevice()");
                    }
                }

                // The package manager doesn't have a proper <application> etc, but since
                // it's running here in the system process we can just set up its agent
                // directly and use a synthetic BackupRequest.  We always run this pass
                // because it's cheap and this way we guarantee that we don't get out of
                // step even if we're selecting among various transports at run time.
                if (status == BackupConstants.TRANSPORT_OK) {
                    PackageManagerBackupAgent pmAgent = new PackageManagerBackupAgent(
                            mPackageManager, allAgentPackages());
                    BackupRequest pmRequest = new BackupRequest(new ApplicationInfo(), false);
                    pmRequest.appInfo.packageName = PACKAGE_MANAGER_SENTINEL;
                    status = processOneBackup(pmRequest,
                            IBackupAgent.Stub.asInterface(pmAgent.onBind()), mTransport);
                }

                if (status == BackupConstants.TRANSPORT_OK) {
                    // Now run all the backups in our queue
                    status = doQueuedBackups(mTransport);
                }

                if (status == BackupConstants.TRANSPORT_OK) {
                    // Tell the transport to finish everything it has buffered
                    status = mTransport.finishBackup();
                    if (status == BackupConstants.TRANSPORT_OK) {
                        int millis = (int) (SystemClock.elapsedRealtime() - startRealtime);
                        EventLog.writeEvent(BACKUP_SUCCESS_EVENT, mQueue.size(), millis);
                    } else {
                        EventLog.writeEvent(BACKUP_TRANSPORT_FAILURE_EVENT, "(finish)");
                        Log.e(TAG, "Transport error in finishBackup()");
                    }
                }

                if (status == BackupConstants.TRANSPORT_NOT_INITIALIZED) {
                    // The backend reports that our dataset has been wiped.  We need to
                    // reset all of our bookkeeping and instead run a new backup pass for
                    // everything.  This must come after mBackupOrRestoreInProgress is cleared.
                    EventLog.writeEvent(BACKUP_RESET_EVENT, mTransport.transportDirName());
                    resetBackupState(mStateDir);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in backup thread", e);
                status = BackupConstants.TRANSPORT_ERROR;
            } finally {
                // If things went wrong, we need to re-stage the apps we had expected
                // to be backing up in this pass.  This journals the package names in
                // the current active pending-backup file, not in the we are holding
                // here in mJournal.
                if (status != BackupConstants.TRANSPORT_OK) {
                    Log.w(TAG, "Backup pass unsuccessful, restaging");
                    for (BackupRequest req : mQueue) {
                        dataChanged(req.appInfo.packageName);
                    }

                    // We also want to reset the backup schedule based on whatever
                    // the transport suggests by way of retry/backoff time.
                    try {
                        startBackupAlarmsLocked(mTransport.requestBackupTime());
                    } catch (RemoteException e) { /* cannot happen */ }
                }

                // Either backup was successful, in which case we of course do not need
                // this pass's journal any more; or it failed, in which case we just
                // re-enqueued all of these packages in the current active journal.
                // Either way, we no longer need this pass's journal.
                if (mJournal != null && !mJournal.delete()) {
                    Log.e(TAG, "Unable to remove backup journal file " + mJournal);
                }

                // Only once we're entirely finished do we indicate our completion
                // and release the wakelock
                synchronized (mQueueLock) {
                    mBackupOrRestoreInProgress = false;
                }

                if (status == BackupConstants.TRANSPORT_NOT_INITIALIZED) {
                    // This must come after mBackupOrRestoreInProgress is cleared.
                    backupNow();
                }

                mWakelock.release();
            }
        }

        private int doQueuedBackups(IBackupTransport transport) {
            for (BackupRequest request : mQueue) {
                Log.d(TAG, "starting agent for backup of " + request);

                // Don't run backup, even if requested, if the target app does not have
                // the requisite permission
                if (mPackageManager.checkPermission(android.Manifest.permission.BACKUP_DATA,
                        request.appInfo.packageName) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "Skipping backup of unprivileged package "
                            + request.appInfo.packageName);
                    continue;
                }

                IBackupAgent agent = null;
                int mode = (request.fullBackup)
                        ? IApplicationThread.BACKUP_MODE_FULL
                        : IApplicationThread.BACKUP_MODE_INCREMENTAL;
                try {
                    agent = bindToAgentSynchronous(request.appInfo, mode);
                    if (agent != null) {
                        int result = processOneBackup(request, agent, transport);
                        if (result != BackupConstants.TRANSPORT_OK) return result;
                    }
                } catch (SecurityException ex) {
                    // Try for the next one.
                    Log.d(TAG, "error in bind/backup", ex);
                } finally {
                    try {  // unbind even on timeout, just in case
                        mActivityManager.unbindBackupAgent(request.appInfo);
                    } catch (RemoteException e) {}
                }
            }

            return BackupConstants.TRANSPORT_OK;
        }

        private int processOneBackup(BackupRequest request, IBackupAgent agent,
                IBackupTransport transport) {
            final String packageName = request.appInfo.packageName;
            if (DEBUG) Log.d(TAG, "processOneBackup doBackup() on " + packageName);

            File savedStateName = new File(mStateDir, packageName);
            File backupDataName = new File(mDataDir, packageName + ".data");
            File newStateName = new File(mStateDir, packageName + ".new");

            ParcelFileDescriptor savedState = null;
            ParcelFileDescriptor backupData = null;
            ParcelFileDescriptor newState = null;

            PackageInfo packInfo;
            try {
                // Look up the package info & signatures.  This is first so that if it
                // throws an exception, there's no file setup yet that would need to
                // be unraveled.
                if (packageName.equals(PACKAGE_MANAGER_SENTINEL)) {
                    // The metadata 'package' is synthetic
                    packInfo = new PackageInfo();
                    packInfo.packageName = packageName;
                } else {
                    packInfo = mPackageManager.getPackageInfo(packageName,
                        PackageManager.GET_SIGNATURES);
                }

                // In a full backup, we pass a null ParcelFileDescriptor as
                // the saved-state "file"
                if (!request.fullBackup) {
                    savedState = ParcelFileDescriptor.open(savedStateName,
                            ParcelFileDescriptor.MODE_READ_ONLY |
                            ParcelFileDescriptor.MODE_CREATE);  // Make an empty file if necessary
                }

                backupData = ParcelFileDescriptor.open(backupDataName,
                        ParcelFileDescriptor.MODE_READ_WRITE |
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE);

                newState = ParcelFileDescriptor.open(newStateName,
                        ParcelFileDescriptor.MODE_READ_WRITE |
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE);

                // Run the target's backup pass
                agent.doBackup(savedState, backupData, newState);
                logBackupComplete(packageName);
                if (DEBUG) Log.v(TAG, "doBackup() success");
            } catch (Exception e) {
                Log.e(TAG, "Error backing up " + packageName, e);
                EventLog.writeEvent(BACKUP_AGENT_FAILURE_EVENT, packageName, e.toString());
                backupDataName.delete();
                newStateName.delete();
                return BackupConstants.TRANSPORT_ERROR;
            } finally {
                try { if (savedState != null) savedState.close(); } catch (IOException e) {}
                try { if (backupData != null) backupData.close(); } catch (IOException e) {}
                try { if (newState != null) newState.close(); } catch (IOException e) {}
                savedState = backupData = newState = null;
            }

            // Now propagate the newly-backed-up data to the transport
            int result = BackupConstants.TRANSPORT_OK;
            try {
                int size = (int) backupDataName.length();
                if (size > 0) {
                    if (result == BackupConstants.TRANSPORT_OK) {
                        backupData = ParcelFileDescriptor.open(backupDataName,
                                ParcelFileDescriptor.MODE_READ_ONLY);
                        result = transport.performBackup(packInfo, backupData);
                    }

                    // TODO - We call finishBackup() for each application backed up, because
                    // we need to know now whether it succeeded or failed.  Instead, we should
                    // hold off on finishBackup() until the end, which implies holding off on
                    // renaming *all* the output state files (see below) until that happens.

                    if (result == BackupConstants.TRANSPORT_OK) {
                        result = transport.finishBackup();
                    }
                } else {
                    if (DEBUG) Log.i(TAG, "no backup data written; not calling transport");
                }

                // After successful transport, delete the now-stale data
                // and juggle the files so that next time we supply the agent
                // with the new state file it just created.
                if (result == BackupConstants.TRANSPORT_OK) {
                    backupDataName.delete();
                    newStateName.renameTo(savedStateName);
                    EventLog.writeEvent(BACKUP_PACKAGE_EVENT, packageName, size);
                } else {
                    EventLog.writeEvent(BACKUP_TRANSPORT_FAILURE_EVENT, packageName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Transport error backing up " + packageName, e);
                EventLog.writeEvent(BACKUP_TRANSPORT_FAILURE_EVENT, packageName);
                result = BackupConstants.TRANSPORT_ERROR;
            } finally {
                try { if (backupData != null) backupData.close(); } catch (IOException e) {}
            }

            return result;
        }
    }


    // ----- Restore handling -----

    private boolean signaturesMatch(Signature[] storedSigs, PackageInfo target) {
        // If the target resides on the system partition, we allow it to restore
        // data from the like-named package in a restore set even if the signatures
        // do not match.  (Unlike general applications, those flashed to the system
        // partition will be signed with the device's platform certificate, so on
        // different phones the same system app will have different signatures.)
        if ((target.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            if (DEBUG) Log.v(TAG, "System app " + target.packageName + " - skipping sig check");
            return true;
        }

        // Allow unsigned apps, but not signed on one device and unsigned on the other
        // !!! TODO: is this the right policy?
        Signature[] deviceSigs = target.signatures;
        if (DEBUG) Log.v(TAG, "signaturesMatch(): stored=" + storedSigs
                + " device=" + deviceSigs);
        if ((storedSigs == null || storedSigs.length == 0)
                && (deviceSigs == null || deviceSigs.length == 0)) {
            return true;
        }
        if (storedSigs == null || deviceSigs == null) {
            return false;
        }

        // !!! TODO: this demands that every stored signature match one
        // that is present on device, and does not demand the converse.
        // Is this this right policy?
        int nStored = storedSigs.length;
        int nDevice = deviceSigs.length;

        for (int i=0; i < nStored; i++) {
            boolean match = false;
            for (int j=0; j < nDevice; j++) {
                if (storedSigs[i].equals(deviceSigs[j])) {
                    match = true;
                    break;
                }
            }
            if (!match) {
                return false;
            }
        }
        return true;
    }

    class PerformRestoreThread extends Thread {
        private IBackupTransport mTransport;
        private IRestoreObserver mObserver;
        private long mToken;
        private File mStateDir;

        class RestoreRequest {
            public PackageInfo app;
            public int storedAppVersion;

            RestoreRequest(PackageInfo _app, int _version) {
                app = _app;
                storedAppVersion = _version;
            }
        }

        PerformRestoreThread(IBackupTransport transport, IRestoreObserver observer,
                long restoreSetToken) {
            mTransport = transport;
            Log.d(TAG, "PerformRestoreThread mObserver=" + mObserver);
            mObserver = observer;
            mToken = restoreSetToken;

            try {
                mStateDir = new File(mBaseStateDir, transport.transportDirName());
            } catch (RemoteException e) {
                // can't happen; the transport is local
            }
        }

        @Override
        public void run() {
            long startRealtime = SystemClock.elapsedRealtime();
            if (DEBUG) Log.v(TAG, "Beginning restore process mTransport=" + mTransport
                    + " mObserver=" + mObserver + " mToken=" + Long.toHexString(mToken));
            /**
             * Restore sequence:
             *
             * 1. get the restore set description for our identity
             * 2. for each app in the restore set:
             *    2.a. if it's restorable on this device, add it to the restore queue
             * 3. for each app in the restore queue:
             *    3.a. clear the app data
             *    3.b. get the restore data for the app from the transport
             *    3.c. launch the backup agent for the app
             *    3.d. agent.doRestore() with the data from the server
             *    3.e. unbind the agent [and kill the app?]
             * 4. shut down the transport
             *
             * On errors, we try our best to recover and move on to the next
             * application, but if necessary we abort the whole operation --
             * the user is waiting, after al.
             */

            int error = -1; // assume error

            // build the set of apps to restore
            try {
                // TODO: Log this before getAvailableRestoreSets, somehow
                EventLog.writeEvent(RESTORE_START_EVENT, mTransport.transportDirName(), mToken);

                // Get the list of all packages which have backup enabled.
                // (Include the Package Manager metadata pseudo-package first.)
                ArrayList<PackageInfo> restorePackages = new ArrayList<PackageInfo>();
                PackageInfo omPackage = new PackageInfo();
                omPackage.packageName = PACKAGE_MANAGER_SENTINEL;
                restorePackages.add(omPackage);

                List<PackageInfo> agentPackages = allAgentPackages();
                restorePackages.addAll(agentPackages);

                // let the observer know that we're running
                if (mObserver != null) {
                    try {
                        // !!! TODO: get an actual count from the transport after
                        // its startRestore() runs?
                        mObserver.restoreStarting(restorePackages.size());
                    } catch (RemoteException e) {
                        Log.d(TAG, "Restore observer died at restoreStarting");
                        mObserver = null;
                    }
                }

                if (mTransport.startRestore(mToken, restorePackages.toArray(new PackageInfo[0])) !=
                        BackupConstants.TRANSPORT_OK) {
                    Log.e(TAG, "Error starting restore operation");
                    EventLog.writeEvent(RESTORE_TRANSPORT_FAILURE_EVENT);
                    return;
                }

                String packageName = mTransport.nextRestorePackage();
                if (packageName == null) {
                    Log.e(TAG, "Error getting first restore package");
                    EventLog.writeEvent(RESTORE_TRANSPORT_FAILURE_EVENT);
                    return;
                } else if (packageName.equals("")) {
                    Log.i(TAG, "No restore data available");
                    int millis = (int) (SystemClock.elapsedRealtime() - startRealtime);
                    EventLog.writeEvent(RESTORE_SUCCESS_EVENT, 0, millis);
                    return;
                } else if (!packageName.equals(PACKAGE_MANAGER_SENTINEL)) {
                    Log.e(TAG, "Expected restore data for \"" + PACKAGE_MANAGER_SENTINEL
                          + "\", found only \"" + packageName + "\"");
                    EventLog.writeEvent(RESTORE_AGENT_FAILURE_EVENT, PACKAGE_MANAGER_SENTINEL,
                            "Package manager data missing");
                    return;
                }

                // Pull the Package Manager metadata from the restore set first
                PackageManagerBackupAgent pmAgent = new PackageManagerBackupAgent(
                        mPackageManager, agentPackages);
                processOneRestore(omPackage, 0, IBackupAgent.Stub.asInterface(pmAgent.onBind()));

                // Verify that the backup set includes metadata.  If not, we can't do
                // signature/version verification etc, so we simply do not proceed with
                // the restore operation.
                if (!pmAgent.hasMetadata()) {
                    Log.e(TAG, "No restore metadata available, so not restoring settings");
                    EventLog.writeEvent(RESTORE_AGENT_FAILURE_EVENT, PACKAGE_MANAGER_SENTINEL,
                            "Package manager restore metadata missing");
                    return;
                }

                int count = 0;
                for (;;) {
                    packageName = mTransport.nextRestorePackage();

                    if (packageName == null) {
                        Log.e(TAG, "Error getting next restore package");
                        EventLog.writeEvent(RESTORE_TRANSPORT_FAILURE_EVENT);
                        return;
                    } else if (packageName.equals("")) {
                        break;
                    }

                    if (mObserver != null) {
                        try {
                            mObserver.onUpdate(count);
                        } catch (RemoteException e) {
                            Log.d(TAG, "Restore observer died in onUpdate");
                            mObserver = null;
                        }
                    }

                    Metadata metaInfo = pmAgent.getRestoredMetadata(packageName);
                    if (metaInfo == null) {
                        Log.e(TAG, "Missing metadata for " + packageName);
                        EventLog.writeEvent(RESTORE_AGENT_FAILURE_EVENT, packageName,
                                "Package metadata missing");
                        continue;
                    }

                    PackageInfo packageInfo;
                    try {
                        int flags = PackageManager.GET_SIGNATURES;
                        packageInfo = mPackageManager.getPackageInfo(packageName, flags);
                    } catch (NameNotFoundException e) {
                        Log.e(TAG, "Invalid package restoring data", e);
                        EventLog.writeEvent(RESTORE_AGENT_FAILURE_EVENT, packageName,
                                "Package missing on device");
                        continue;
                    }

                    if (metaInfo.versionCode > packageInfo.versionCode) {
                        String message = "Version " + metaInfo.versionCode
                                + " > installed version " + packageInfo.versionCode;
                        Log.w(TAG, "Package " + packageName + ": " + message);
                        EventLog.writeEvent(RESTORE_AGENT_FAILURE_EVENT, packageName, message);
                        continue;
                    }

                    if (!signaturesMatch(metaInfo.signatures, packageInfo)) {
                        Log.w(TAG, "Signature mismatch restoring " + packageName);
                        EventLog.writeEvent(RESTORE_AGENT_FAILURE_EVENT, packageName,
                                "Signature mismatch");
                        continue;
                    }

                    if (DEBUG) Log.v(TAG, "Package " + packageName
                            + " restore version [" + metaInfo.versionCode
                            + "] is compatible with installed version ["
                            + packageInfo.versionCode + "]");

                    // Now perform the actual restore:  first clear the app's data
                    // if appropriate
                    clearApplicationDataSynchronous(packageName);

                    // Then set up and bind the agent (with a restricted Application object
                    // unless the application says otherwise)
                    boolean useRealApp = (packageInfo.applicationInfo.flags
                            & ApplicationInfo.FLAG_RESTORE_NEEDS_APPLICATION) != 0;
                    if (DEBUG && useRealApp) {
                        Log.v(TAG, "agent requires real Application subclass for restore");
                    }
                    IBackupAgent agent = bindToAgentSynchronous(
                            packageInfo.applicationInfo,
                            (useRealApp ? IApplicationThread.BACKUP_MODE_INCREMENTAL
                                    : IApplicationThread.BACKUP_MODE_RESTORE));
                    if (agent == null) {
                        Log.w(TAG, "Can't find backup agent for " + packageName);
                        EventLog.writeEvent(RESTORE_AGENT_FAILURE_EVENT, packageName,
                                "Restore agent missing");
                        continue;
                    }

                    // And then finally run the restore on this agent
                    try {
                        processOneRestore(packageInfo, metaInfo.versionCode, agent);
                        ++count;
                    } finally {
                        // unbind and tidy up even on timeout or failure, just in case
                        mActivityManager.unbindBackupAgent(packageInfo.applicationInfo);

                        // The agent was probably running with a stub Application object,
                        // which isn't a valid run mode for the main app logic.  Shut
                        // down the app so that next time it's launched, it gets the
                        // usual full initialization.
                        if ((packageInfo.applicationInfo.flags
                                & ApplicationInfo.FLAG_KILL_AFTER_RESTORE) != 0) {
                            if (DEBUG) Log.d(TAG, "Restore complete, killing host process of "
                                    + packageInfo.applicationInfo.processName);
                            mActivityManager.killApplicationProcess(
                                    packageInfo.applicationInfo.processName,
                                    packageInfo.applicationInfo.uid);
                        }
                    }
                }

                // if we get this far, report success to the observer
                error = 0;
                int millis = (int) (SystemClock.elapsedRealtime() - startRealtime);
                EventLog.writeEvent(RESTORE_SUCCESS_EVENT, count, millis);
            } catch (Exception e) {
                Log.e(TAG, "Error in restore thread", e);
            } finally {
                if (DEBUG) Log.d(TAG, "finishing restore mObserver=" + mObserver);

                try {
                    mTransport.finishRestore();
                } catch (RemoteException e) {
                    Log.e(TAG, "Error finishing restore", e);
                }

                if (mObserver != null) {
                    try {
                        mObserver.restoreFinished(error);
                    } catch (RemoteException e) {
                        Log.d(TAG, "Restore observer died at restoreFinished");
                    }
                }

                // done; we can finally release the wakelock
                synchronized (mQueueLock) {
                    mBackupOrRestoreInProgress = false;
                }
                mWakelock.release();
            }
        }

        // Do the guts of a restore of one application, using mTransport.getRestoreData().
        void processOneRestore(PackageInfo app, int appVersionCode, IBackupAgent agent) {
            // !!! TODO: actually run the restore through mTransport
            final String packageName = app.packageName;

            if (DEBUG) Log.d(TAG, "processOneRestore packageName=" + packageName);

            // Don't restore to unprivileged packages
            if (mPackageManager.checkPermission(android.Manifest.permission.BACKUP_DATA,
                    packageName) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Skipping restore of unprivileged package " + packageName);
            }

            // !!! TODO: get the dirs from the transport
            File backupDataName = new File(mDataDir, packageName + ".restore");
            File newStateName = new File(mStateDir, packageName + ".new");
            File savedStateName = new File(mStateDir, packageName);

            ParcelFileDescriptor backupData = null;
            ParcelFileDescriptor newState = null;

            try {
                // Run the transport's restore pass
                backupData = ParcelFileDescriptor.open(backupDataName,
                            ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

                if (mTransport.getRestoreData(backupData) != BackupConstants.TRANSPORT_OK) {
                    Log.e(TAG, "Error getting restore data for " + packageName);
                    EventLog.writeEvent(RESTORE_TRANSPORT_FAILURE_EVENT);
                    return;
                }

                // Okay, we have the data.  Now have the agent do the restore.
                backupData.close();
                backupData = ParcelFileDescriptor.open(backupDataName,
                            ParcelFileDescriptor.MODE_READ_ONLY);

                newState = ParcelFileDescriptor.open(newStateName,
                            ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

                agent.doRestore(backupData, appVersionCode, newState);

                // if everything went okay, remember the recorded state now
                //
                // !!! TODO: the restored data should be migrated on the server
                // side into the current dataset.  In that case the new state file
                // we just created would reflect the data already extant in the
                // backend, so there'd be nothing more to do.  Until that happens,
                // however, we need to make sure that we record the data to the
                // current backend dataset.  (Yes, this means shipping the data over
                // the wire in both directions.  That's bad, but consistency comes
                // first, then efficiency.)  Once we introduce server-side data
                // migration to the newly-restored device's dataset, we will change
                // the following from a discard of the newly-written state to the
                // "correct" operation of renaming into the canonical state blob.
                newStateName.delete();                      // TODO: remove; see above comment
                //newStateName.renameTo(savedStateName);    // TODO: replace with this

                int size = (int) backupDataName.length();
                EventLog.writeEvent(RESTORE_PACKAGE_EVENT, packageName, size);
            } catch (Exception e) {
                Log.e(TAG, "Error restoring data for " + packageName, e);
                EventLog.writeEvent(RESTORE_AGENT_FAILURE_EVENT, packageName, e.toString());

                // If the agent fails restore, it might have put the app's data
                // into an incoherent state.  For consistency we wipe its data
                // again in this case before propagating the exception
                clearApplicationDataSynchronous(packageName);
            } finally {
                backupDataName.delete();
                try { if (backupData != null) backupData.close(); } catch (IOException e) {}
                try { if (newState != null) newState.close(); } catch (IOException e) {}
                backupData = newState = null;
            }
        }
    }

    class PerformClearThread extends Thread {
        IBackupTransport mTransport;
        PackageInfo mPackage;

        PerformClearThread(IBackupTransport transport, PackageInfo packageInfo) {
            mTransport = transport;
            mPackage = packageInfo;
        }

        @Override
        public void run() {
            try {
                // Clear the on-device backup state to ensure a full backup next time
                File stateDir = new File(mBaseStateDir, mTransport.transportDirName());
                File stateFile = new File(stateDir, mPackage.packageName);
                stateFile.delete();

                // Tell the transport to remove all the persistent storage for the app
                // TODO - need to handle failures
                mTransport.clearBackupData(mPackage);
            } catch (RemoteException e) {
                // can't happen; the transport is local
            } finally {
                try {
                    // TODO - need to handle failures
                    mTransport.finishBackup();
                } catch (RemoteException e) {
                    // can't happen; the transport is local
                }

                // Last but not least, release the cpu
                synchronized (mQueueLock) {
                    mBackupOrRestoreInProgress = false;
                }
                mWakelock.release();
            }
        }
    }

    class PerformInitializeThread extends Thread {
        HashSet<String> mQueue;

        PerformInitializeThread(HashSet<String> transportNames) {
            mQueue = transportNames;
        }

        @Override
        public void run() {
            try {
                for (String transportName : mQueue) {
                    IBackupTransport transport = getTransport(transportName);
                    if (transport == null) {
                        Log.e(TAG, "Requested init for " + transportName + " but not found");
                        continue;
                    }

                    Log.i(TAG, "Initializing (wiping) backup transport storage: " + transportName);
                    EventLog.writeEvent(BACKUP_START_EVENT, transport.transportDirName());
                    long startRealtime = SystemClock.elapsedRealtime();
                    int status = transport.initializeDevice();

                    if (status == BackupConstants.TRANSPORT_OK) {
                        status = transport.finishBackup();
                    }

                    // Okay, the wipe really happened.  Clean up our local bookkeeping.
                    if (status == BackupConstants.TRANSPORT_OK) {
                        Log.i(TAG, "Device init successful");
                        int millis = (int) (SystemClock.elapsedRealtime() - startRealtime);
                        EventLog.writeEvent(BACKUP_INITIALIZE_EVENT);
                        resetBackupState(new File(mBaseStateDir, transport.transportDirName()));
                        EventLog.writeEvent(BACKUP_SUCCESS_EVENT, 0, millis);
                        synchronized (mQueueLock) {
                            recordInitPendingLocked(false, transportName);
                        }
                    } else {
                        // If this didn't work, requeue this one and try again
                        // after a suitable interval
                        Log.e(TAG, "Transport error in initializeDevice()");
                        EventLog.writeEvent(BACKUP_TRANSPORT_FAILURE_EVENT, "(initialize)");
                        synchronized (mQueueLock) {
                            recordInitPendingLocked(true, transportName);
                        }
                        // do this via another alarm to make sure of the wakelock states
                        long delay = transport.requestBackupTime();
                        if (DEBUG) Log.w(TAG, "init failed on "
                                + transportName + " resched in " + delay);
                        mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                                System.currentTimeMillis() + delay, mRunInitIntent);
                    }
                }
            } catch (RemoteException e) {
                // can't happen; the transports are local
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error performing init", e);
            } finally {
                // Done; indicate that we're finished and release the wakelock
                synchronized (mQueueLock) {
                    mInitInProgress = false;
                }
                mWakelock.release();
            }
        }
    }


    // ----- IBackupManager binder interface -----

    public void dataChanged(String packageName) {
        // Record that we need a backup pass for the caller.  Since multiple callers
        // may share a uid, we need to note all candidates within that uid and schedule
        // a backup pass for each of them.
        EventLog.writeEvent(BACKUP_DATA_CHANGED_EVENT, packageName);

        // If the caller does not hold the BACKUP permission, it can only request a
        // backup of its own data.
        HashSet<ApplicationInfo> targets;
        if ((mContext.checkPermission(android.Manifest.permission.BACKUP, Binder.getCallingPid(),
                Binder.getCallingUid())) == PackageManager.PERMISSION_DENIED) {
            targets = mBackupParticipants.get(Binder.getCallingUid());
        } else {
            // a caller with full permission can ask to back up any participating app
            // !!! TODO: allow backup of ANY app?
            targets = new HashSet<ApplicationInfo>();
            int N = mBackupParticipants.size();
            for (int i = 0; i < N; i++) {
                HashSet<ApplicationInfo> s = mBackupParticipants.valueAt(i);
                if (s != null) {
                    targets.addAll(s);
                }
            }
        }
        if (targets != null) {
            synchronized (mQueueLock) {
                // Note that this client has made data changes that need to be backed up
                for (ApplicationInfo app : targets) {
                    // validate the caller-supplied package name against the known set of
                    // packages associated with this uid
                    if (app.packageName.equals(packageName)) {
                        // Add the caller to the set of pending backups.  If there is
                        // one already there, then overwrite it, but no harm done.
                        BackupRequest req = new BackupRequest(app, false);
                        if (mPendingBackups.put(app, req) == null) {
                            // Journal this request in case of crash.  The put()
                            // operation returned null when this package was not already
                            // in the set; we want to avoid touching the disk redundantly.
                            writeToJournalLocked(packageName);

                            if (DEBUG) {
                                int numKeys = mPendingBackups.size();
                                Log.d(TAG, "Now awaiting backup for " + numKeys + " participants:");
                                for (BackupRequest b : mPendingBackups.values()) {
                                    Log.d(TAG, "    + " + b + " agent=" + b.appInfo.backupAgentName);
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Log.w(TAG, "dataChanged but no participant pkg='" + packageName + "'"
                    + " uid=" + Binder.getCallingUid());
        }
    }

    private void writeToJournalLocked(String str) {
        RandomAccessFile out = null;
        try {
            if (mJournal == null) mJournal = File.createTempFile("journal", null, mJournalDir);
            out = new RandomAccessFile(mJournal, "rws");
            out.seek(out.length());
            out.writeUTF(str);
        } catch (IOException e) {
            Log.e(TAG, "Can't write " + str + " to backup journal", e);
            mJournal = null;
        } finally {
            try { if (out != null) out.close(); } catch (IOException e) {}
        }
    }

    // Clear the given package's backup data from the current transport
    public void clearBackupData(String packageName) {
        if (DEBUG) Log.v(TAG, "clearBackupData() of " + packageName);
        PackageInfo info;
        try {
            info = mPackageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        } catch (NameNotFoundException e) {
            Log.d(TAG, "No such package '" + packageName + "' - not clearing backup data");
            return;
        }

        // If the caller does not hold the BACKUP permission, it can only request a
        // wipe of its own backed-up data.
        HashSet<ApplicationInfo> apps;
        if ((mContext.checkPermission(android.Manifest.permission.BACKUP, Binder.getCallingPid(),
                Binder.getCallingUid())) == PackageManager.PERMISSION_DENIED) {
            apps = mBackupParticipants.get(Binder.getCallingUid());
        } else {
            // a caller with full permission can ask to back up any participating app
            // !!! TODO: allow data-clear of ANY app?
            if (DEBUG) Log.v(TAG, "Privileged caller, allowing clear of other apps");
            apps = new HashSet<ApplicationInfo>();
            int N = mBackupParticipants.size();
            for (int i = 0; i < N; i++) {
                HashSet<ApplicationInfo> s = mBackupParticipants.valueAt(i);
                if (s != null) {
                    apps.addAll(s);
                }
            }
        }

        // now find the given package in the set of candidate apps
        for (ApplicationInfo app : apps) {
            if (app.packageName.equals(packageName)) {
                if (DEBUG) Log.v(TAG, "Found the app - running clear process");
                // found it; fire off the clear request
                synchronized (mQueueLock) {
                    long oldId = Binder.clearCallingIdentity();
                    mWakelock.acquire();
                    Message msg = mBackupHandler.obtainMessage(MSG_RUN_CLEAR,
                            new ClearParams(getTransport(mCurrentTransport), info));
                    mBackupHandler.sendMessage(msg);
                    Binder.restoreCallingIdentity(oldId);
                }
                break;
            }
        }
    }

    // Run a backup pass immediately for any applications that have declared
    // that they have pending updates.
    public void backupNow() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP, "backupNow");

        if (DEBUG) Log.v(TAG, "Scheduling immediate backup pass");
        synchronized (mQueueLock) {
            // Because the alarms we are using can jitter, and we want an *immediate*
            // backup pass to happen, we restart the timer beginning with "next time,"
            // then manually fire the backup trigger intent ourselves.
            startBackupAlarmsLocked(BACKUP_INTERVAL);
            try {
                mRunBackupIntent.send();
            } catch (PendingIntent.CanceledException e) {
                // should never happen
                Log.e(TAG, "run-backup intent cancelled!");
            }
        }
    }

    // Enable/disable the backup service
    public void setBackupEnabled(boolean enable) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setBackupEnabled");

        Log.i(TAG, "Backup enabled => " + enable);

        boolean wasEnabled = mEnabled;
        synchronized (this) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.BACKUP_ENABLED, enable ? 1 : 0);
            mEnabled = enable;
        }

        synchronized (mQueueLock) {
            if (enable && !wasEnabled && mProvisioned) {
                // if we've just been enabled, start scheduling backup passes
                startBackupAlarmsLocked(BACKUP_INTERVAL);
            } else if (!enable) {
                // No longer enabled, so stop running backups
                if (DEBUG) Log.i(TAG, "Opting out of backup");

                mAlarmManager.cancel(mRunBackupIntent);

                // This also constitutes an opt-out, so we wipe any data for
                // this device from the backend.  We start that process with
                // an alarm in order to guarantee wakelock states.
                if (wasEnabled && mProvisioned) {
                    // NOTE: we currently flush every registered transport, not just
                    // the currently-active one.
                    HashSet<String> allTransports;
                    synchronized (mTransports) {
                        allTransports = new HashSet<String>(mTransports.keySet());
                    }
                    // build the set of transports for which we are posting an init
                    for (String transport : allTransports) {
                        recordInitPendingLocked(true, transport);
                    }
                    mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(),
                            mRunInitIntent);
                }
            }
        }
    }

    // Mark the backup service as having been provisioned
    public void setBackupProvisioned(boolean available) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setBackupProvisioned");

        boolean wasProvisioned = mProvisioned;
        synchronized (this) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.BACKUP_PROVISIONED, available ? 1 : 0);
            mProvisioned = available;
        }

        synchronized (mQueueLock) {
            if (available && !wasProvisioned && mEnabled) {
                // we're now good to go, so start the backup alarms
                startBackupAlarmsLocked(FIRST_BACKUP_INTERVAL);
            } else if (!available) {
                // No longer enabled, so stop running backups
                Log.w(TAG, "Backup service no longer provisioned");
                mAlarmManager.cancel(mRunBackupIntent);
            }
        }
    }

    private void startBackupAlarmsLocked(long delayBeforeFirstBackup) {
        // We used to use setInexactRepeating(), but that may be linked to
        // backups running at :00 more often than not, creating load spikes.
        // Schedule at an exact time for now, and also add a bit of "fuzz".

        Random random = new Random();
        long when = System.currentTimeMillis() + delayBeforeFirstBackup +
                random.nextInt(FUZZ_MILLIS);
        mAlarmManager.setRepeating(AlarmManager.RTC_WAKEUP, when,
                BACKUP_INTERVAL + random.nextInt(FUZZ_MILLIS), mRunBackupIntent);
        mNextBackupPass = when;
    }

    // Report whether the backup mechanism is currently enabled
    public boolean isBackupEnabled() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP, "isBackupEnabled");
        return mEnabled;    // no need to synchronize just to read it
    }

    // Report the name of the currently active transport
    public String getCurrentTransport() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getCurrentTransport");
        Log.v(TAG, "... getCurrentTransport() returning " + mCurrentTransport);
        return mCurrentTransport;
    }

    // Report all known, available backup transports
    public String[] listAllTransports() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP, "listAllTransports");

        String[] list = null;
        ArrayList<String> known = new ArrayList<String>();
        for (Map.Entry<String, IBackupTransport> entry : mTransports.entrySet()) {
            if (entry.getValue() != null) {
                known.add(entry.getKey());
            }
        }

        if (known.size() > 0) {
            list = new String[known.size()];
            known.toArray(list);
        }
        return list;
    }

    // Select which transport to use for the next backup operation.  If the given
    // name is not one of the available transports, no action is taken and the method
    // returns null.
    public String selectBackupTransport(String transport) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP, "selectBackupTransport");

        synchronized (mTransports) {
            String prevTransport = null;
            if (mTransports.get(transport) != null) {
                prevTransport = mCurrentTransport;
                mCurrentTransport = transport;
                Settings.Secure.putString(mContext.getContentResolver(),
                        Settings.Secure.BACKUP_TRANSPORT, transport);
                Log.v(TAG, "selectBackupTransport() set " + mCurrentTransport
                        + " returning " + prevTransport);
            } else {
                Log.w(TAG, "Attempt to select unavailable transport " + transport);
            }
            return prevTransport;
        }
    }

    // Callback: a requested backup agent has been instantiated.  This should only
    // be called from the Activity Manager.
    public void agentConnected(String packageName, IBinder agentBinder) {
        synchronized(mAgentConnectLock) {
            if (Binder.getCallingUid() == Process.SYSTEM_UID) {
                Log.d(TAG, "agentConnected pkg=" + packageName + " agent=" + agentBinder);
                IBackupAgent agent = IBackupAgent.Stub.asInterface(agentBinder);
                mConnectedAgent = agent;
                mConnecting = false;
            } else {
                Log.w(TAG, "Non-system process uid=" + Binder.getCallingUid()
                        + " claiming agent connected");
            }
            mAgentConnectLock.notifyAll();
        }
    }

    // Callback: a backup agent has failed to come up, or has unexpectedly quit.
    // If the agent failed to come up in the first place, the agentBinder argument
    // will be null.  This should only be called from the Activity Manager.
    public void agentDisconnected(String packageName) {
        // TODO: handle backup being interrupted
        synchronized(mAgentConnectLock) {
            if (Binder.getCallingUid() == Process.SYSTEM_UID) {
                mConnectedAgent = null;
                mConnecting = false;
            } else {
                Log.w(TAG, "Non-system process uid=" + Binder.getCallingUid()
                        + " claiming agent disconnected");
            }
            mAgentConnectLock.notifyAll();
        }
    }

    // Hand off a restore session
    public IRestoreSession beginRestoreSession(String transport) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP, "beginRestoreSession");

        synchronized(this) {
            if (mActiveRestoreSession != null) {
                Log.d(TAG, "Restore session requested but one already active");
                return null;
            }
            mActiveRestoreSession = new RestoreSession(transport);
        }
        return mActiveRestoreSession;
    }

    // ----- Restore session -----

    class RestoreSession extends IRestoreSession.Stub {
        private static final String TAG = "RestoreSession";

        private IBackupTransport mRestoreTransport = null;
        RestoreSet[] mRestoreSets = null;

        RestoreSession(String transport) {
            mRestoreTransport = getTransport(transport);
        }

        // --- Binder interface ---
        public synchronized RestoreSet[] getAvailableRestoreSets() {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                    "getAvailableRestoreSets");

            try {
                if (mRestoreTransport == null) {
                    Log.w(TAG, "Null transport getting restore sets");
                    return null;
                }
                if (mRestoreSets == null) { // valid transport; do the one-time fetch
                    mRestoreSets = mRestoreTransport.getAvailableRestoreSets();
                    if (mRestoreSets == null) EventLog.writeEvent(RESTORE_TRANSPORT_FAILURE_EVENT);
                }
                return mRestoreSets;
            } catch (Exception e) {
                Log.e(TAG, "Error in getAvailableRestoreSets", e);
                return null;
            }
        }

        public synchronized int performRestore(long token, IRestoreObserver observer) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                    "performRestore");

            if (DEBUG) Log.d(TAG, "performRestore token=" + Long.toHexString(token)
                    + " observer=" + observer);

            if (mRestoreTransport == null || mRestoreSets == null) {
                Log.e(TAG, "Ignoring performRestore() with no restore set");
                return -1;
            }

            synchronized (mQueueLock) {
                if (mBackupOrRestoreInProgress) {
                    Log.e(TAG, "Backup pass in progress, restore aborted");
                    return -1;
                }

                for (int i = 0; i < mRestoreSets.length; i++) {
                    if (token == mRestoreSets[i].token) {
                        long oldId = Binder.clearCallingIdentity();
                        // Suppress backups until the restore operation is finished
                        mBackupOrRestoreInProgress = true;
                        mWakelock.acquire();
                        Message msg = mBackupHandler.obtainMessage(MSG_RUN_RESTORE);
                        msg.obj = new RestoreParams(mRestoreTransport, observer, token);
                        mBackupHandler.sendMessage(msg);
                        Binder.restoreCallingIdentity(oldId);
                        return 0;
                    }
                }
            }

            Log.w(TAG, "Restore token " + Long.toHexString(token) + " not found");
            return -1;
        }

        public synchronized void endRestoreSession() {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                    "endRestoreSession");

            if (DEBUG) Log.d(TAG, "endRestoreSession");

            synchronized (this) {
                try {
                    if (mRestoreTransport != null) mRestoreTransport.finishRestore();
                } catch (Exception e) {
                    Log.e(TAG, "Error in finishRestore", e);
                } finally {
                    mRestoreTransport = null;
                }
            }

            synchronized (BackupManagerService.this) {
                if (BackupManagerService.this.mActiveRestoreSession == this) {
                    BackupManagerService.this.mActiveRestoreSession = null;
                } else {
                    Log.e(TAG, "ending non-current restore session");
                }
            }
        }
    }


    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mQueueLock) {
            pw.println("Backup Manager is " + (mEnabled ? "enabled" : "disabled")
                    + " / " + (!mProvisioned ? "not " : "") + "provisioned / "
                    + (!mBackupOrRestoreInProgress ? "not " : "") + "in progress / "
                    + (this.mPendingInits.size() == 0 ? "not " : "") + "pending init / "
                    + (!mInitInProgress ? "not " : "") + "initializing");
            pw.println("Last backup pass: " + mLastBackupPass
                    + " (now = " + System.currentTimeMillis() + ')');
            pw.println("  next scheduled: " + mNextBackupPass);

            pw.println("Available transports:");
            for (String t : listAllTransports()) {
                pw.println((t.equals(mCurrentTransport) ? "  * " : "    ") + t);
                try {
                    File dir = new File(mBaseStateDir, getTransport(t).transportDirName());
                    for (File f : dir.listFiles()) {
                        pw.println("       " + f.getName() + " - " + f.length() + " state bytes");
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Error in transportDirName()", e);
                    pw.println("        Error: " + e);
                }
            }

            pw.println("Pending init: " + mPendingInits.size());
            for (String s : mPendingInits) {
                pw.println("    " + s);
            }

            int N = mBackupParticipants.size();
            pw.println("Participants:");
            for (int i=0; i<N; i++) {
                int uid = mBackupParticipants.keyAt(i);
                pw.print("  uid: ");
                pw.println(uid);
                HashSet<ApplicationInfo> participants = mBackupParticipants.valueAt(i);
                for (ApplicationInfo app: participants) {
                    pw.println("    " + app.packageName);
                }
            }

            pw.println("Ever backed up: " + mEverStoredApps.size());
            for (String pkg : mEverStoredApps) {
                pw.println("    " + pkg);
            }

            pw.println("Pending backup: " + mPendingBackups.size());
            for (BackupRequest req : mPendingBackups.values()) {
                pw.println("    " + req);
            }
        }
    }
}
