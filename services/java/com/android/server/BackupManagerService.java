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
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.IBackupAgent;
import android.app.PendingIntent;
import android.app.backup.RestoreSet;
import android.app.backup.IBackupManager;
import android.app.backup.IRestoreObserver;
import android.app.backup.IRestoreSession;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.backup.BackupConstants;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.backup.LocalTransport;
import com.android.server.PackageManagerBackupAgent.Metadata;

import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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

    private static final String RUN_BACKUP_ACTION = "android.app.backup.intent.RUN";
    private static final String RUN_INITIALIZE_ACTION = "android.app.backup.intent.INIT";
    private static final String RUN_CLEAR_ACTION = "android.app.backup.intent.CLEAR";
    private static final int MSG_RUN_BACKUP = 1;
    private static final int MSG_RUN_FULL_BACKUP = 2;
    private static final int MSG_RUN_RESTORE = 3;
    private static final int MSG_RUN_CLEAR = 4;
    private static final int MSG_RUN_INITIALIZE = 5;
    private static final int MSG_RUN_GET_RESTORE_SETS = 6;
    private static final int MSG_TIMEOUT = 7;

    // Timeout interval for deciding that a bind or clear-data has taken too long
    static final long TIMEOUT_INTERVAL = 10 * 1000;

    // Timeout intervals for agent backup & restore operations
    static final long TIMEOUT_BACKUP_INTERVAL = 30 * 1000;
    static final long TIMEOUT_RESTORE_INTERVAL = 60 * 1000;

    private Context mContext;
    private PackageManager mPackageManager;
    IPackageManager mPackageManagerBinder;
    private IActivityManager mActivityManager;
    private PowerManager mPowerManager;
    private AlarmManager mAlarmManager;
    IBackupManager mBackupManagerBinder;

    boolean mEnabled;   // access to this is synchronized on 'this'
    boolean mProvisioned;
    boolean mAutoRestore;
    PowerManager.WakeLock mWakelock;
    HandlerThread mHandlerThread = new HandlerThread("backup", Process.THREAD_PRIORITY_BACKGROUND);
    BackupHandler mBackupHandler;
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
    ActiveRestoreSession mActiveRestoreSession;

    class RestoreGetSetsParams {
        public IBackupTransport transport;
        public ActiveRestoreSession session;
        public IRestoreObserver observer;

        RestoreGetSetsParams(IBackupTransport _transport, ActiveRestoreSession _session,
                IRestoreObserver _observer) {
            transport = _transport;
            session = _session;
            observer = _observer;
        }
    }

    class RestoreParams {
        public IBackupTransport transport;
        public IRestoreObserver observer;
        public long token;
        public PackageInfo pkgInfo;
        public int pmToken; // in post-install restore, the PM's token for this transaction
        public boolean needFullBackup;

        RestoreParams(IBackupTransport _transport, IRestoreObserver _obs,
                long _token, PackageInfo _pkg, int _pmToken, boolean _needFullBackup) {
            transport = _transport;
            observer = _obs;
            token = _token;
            pkgInfo = _pkg;
            pmToken = _pmToken;
            needFullBackup = _needFullBackup;
        }

        RestoreParams(IBackupTransport _transport, IRestoreObserver _obs, long _token,
                boolean _needFullBackup) {
            transport = _transport;
            observer = _obs;
            token = _token;
            pkgInfo = null;
            pmToken = 0;
            needFullBackup = _needFullBackup;
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

    // Bookkeeping of in-flight operations for timeout etc. purposes.  The operation
    // token is the index of the entry in the pending-operations list.
    static final int OP_PENDING = 0;
    static final int OP_ACKNOWLEDGED = 1;
    static final int OP_TIMEOUT = -1;

    final SparseIntArray mCurrentOperations = new SparseIntArray();
    final Object mCurrentOpLock = new Object();
    final Random mTokenGenerator = new Random();

    // Where we keep our journal files and other bookkeeping
    File mBaseStateDir;
    File mDataDir;
    File mJournalDir;
    File mJournal;

    // Keep a log of all the apps we've ever backed up, and what the
    // dataset tokens are for both the current backup dataset and
    // the ancestral dataset.
    private File mEverStored;
    HashSet<String> mEverStoredApps = new HashSet<String>();

    static final int CURRENT_ANCESTRAL_RECORD_VERSION = 1;  // increment when the schema changes
    File mTokenFile;
    Set<String> mAncestralPackages = null;
    long mAncestralToken = 0;
    long mCurrentToken = 0;

    // Persistently track the need to do a full init
    static final String INIT_SENTINEL_FILE_NAME = "_need_init_";
    HashSet<String> mPendingInits = new HashSet<String>();  // transport names

    // ----- Asynchronous backup/restore handler thread -----

    private class BackupHandler extends Handler {
        public BackupHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {

            switch (msg.what) {
            case MSG_RUN_BACKUP:
            {
                mLastBackupPass = System.currentTimeMillis();
                mNextBackupPass = mLastBackupPass + BACKUP_INTERVAL;

                IBackupTransport transport = getTransport(mCurrentTransport);
                if (transport == null) {
                    Slog.v(TAG, "Backup requested but no transport available");
                    mWakelock.release();
                    break;
                }

                // snapshot the pending-backup set and work on that
                ArrayList<BackupRequest> queue = new ArrayList<BackupRequest>();
                File oldJournal = mJournal;
                synchronized (mQueueLock) {
                    // Do we have any work to do?  Construct the work queue
                    // then release the synchronization lock to actually run
                    // the backup.
                    if (mPendingBackups.size() > 0) {
                        for (BackupRequest b: mPendingBackups.values()) {
                            queue.add(b);
                        }
                        if (DEBUG) Slog.v(TAG, "clearing pending backups");
                        mPendingBackups.clear();

                        // Start a new backup-queue journal file too
                        mJournal = null;

                    }
                }

                if (queue.size() > 0) {
                    // At this point, we have started a new journal file, and the old
                    // file identity is being passed to the backup processing thread.
                    // When it completes successfully, that old journal file will be
                    // deleted.  If we crash prior to that, the old journal is parsed
                    // at next boot and the journaled requests fulfilled.
                    (new PerformBackupTask(transport, queue, oldJournal)).run();
                } else {
                    Slog.v(TAG, "Backup requested but nothing pending");
                    mWakelock.release();
                }
                break;
            }

            case MSG_RUN_FULL_BACKUP:
                break;

            case MSG_RUN_RESTORE:
            {
                RestoreParams params = (RestoreParams)msg.obj;
                Slog.d(TAG, "MSG_RUN_RESTORE observer=" + params.observer);
                (new PerformRestoreTask(params.transport, params.observer,
                        params.token, params.pkgInfo, params.pmToken,
                        params.needFullBackup)).run();
                break;
            }

            case MSG_RUN_CLEAR:
            {
                ClearParams params = (ClearParams)msg.obj;
                (new PerformClearTask(params.transport, params.packageInfo)).run();
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

                (new PerformInitializeTask(queue)).run();
                break;
            }

            case MSG_RUN_GET_RESTORE_SETS:
            {
                // Like other async operations, this is entered with the wakelock held
                RestoreSet[] sets = null;
                RestoreGetSetsParams params = (RestoreGetSetsParams)msg.obj;
                try {
                    sets = params.transport.getAvailableRestoreSets();
                    // cache the result in the active session
                    synchronized (params.session) {
                        params.session.mRestoreSets = sets;
                    }
                    if (sets == null) EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                } catch (Exception e) {
                    Slog.e(TAG, "Error from transport getting set list");
                } finally {
                    if (params.observer != null) {
                        try {
                            params.observer.restoreSetsAvailable(sets);
                        } catch (RemoteException re) {
                            Slog.e(TAG, "Unable to report listing to observer");
                        } catch (Exception e) {
                            Slog.e(TAG, "Restore observer threw", e);
                        }
                    }

                    mWakelock.release();
                }
                break;
            }

            case MSG_TIMEOUT:
            {
                synchronized (mCurrentOpLock) {
                    final int token = msg.arg1;
                    int state = mCurrentOperations.get(token, OP_TIMEOUT);
                    if (state == OP_PENDING) {
                        if (DEBUG) Slog.v(TAG, "TIMEOUT: token=" + token);
                        mCurrentOperations.put(token, OP_TIMEOUT);
                    }
                    mCurrentOpLock.notifyAll();
                }
                break;
            }
            }
        }
    }

    // ----- Main service implementation -----

    public BackupManagerService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mPackageManagerBinder = AppGlobals.getPackageManager();
        mActivityManager = ActivityManagerNative.getDefault();

        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        mBackupManagerBinder = asInterface(asBinder());

        // spin up the backup/restore handler thread
        mHandlerThread = new HandlerThread("backup", Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        mBackupHandler = new BackupHandler(mHandlerThread.getLooper());

        // Set up our bookkeeping
        boolean areEnabled = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.BACKUP_ENABLED, 0) != 0;
        mProvisioned = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.BACKUP_PROVISIONED, 0) != 0;
        mAutoRestore = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.BACKUP_AUTO_RESTORE, 1) != 0;
        // If Encrypted file systems is enabled or disabled, this call will return the
        // correct directory.
        mBaseStateDir = new File(Environment.getSecureDataDirectory(), "backup");
        mBaseStateDir.mkdirs();
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
        if (DEBUG) Slog.v(TAG, "Starting with transport " + mCurrentTransport);

        // Attach to the Google backup transport.  When this comes up, it will set
        // itself as the current transport because we explicitly reset mCurrentTransport
        // to null.
        ComponentName transportComponent = new ComponentName("com.google.android.backup",
                "com.google.android.backup.BackupTransportService");
        try {
            // If there's something out there that is supposed to be the Google
            // backup transport, make sure it's legitimately part of the OS build
            // and not an app lying about its package name.
            ApplicationInfo info = mPackageManager.getApplicationInfo(
                    transportComponent.getPackageName(), 0);
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                if (DEBUG) Slog.v(TAG, "Binding to Google transport");
                Intent intent = new Intent().setComponent(transportComponent);
                context.bindService(intent, mGoogleConnection, Context.BIND_AUTO_CREATE);
            } else {
                Slog.w(TAG, "Possible Google transport spoof: ignoring " + info);
            }
        } catch (PackageManager.NameNotFoundException nnf) {
            // No such package?  No binding.
            if (DEBUG) Slog.v(TAG, "Google transport not present");
        }

        // Now that we know about valid backup participants, parse any
        // leftover journal files into the pending backup set
        parseLeftoverJournals();

        // Power management
        mWakelock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*backup*");

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
                        if (DEBUG) Slog.v(TAG, "Init pending at scheduled backup");
                        try {
                            mAlarmManager.cancel(mRunInitIntent);
                            mRunInitIntent.send();
                        } catch (PendingIntent.CanceledException ce) {
                            Slog.e(TAG, "Run init intent cancelled");
                            // can't really do more than bail here
                        }
                    } else {
                        // Don't run backups now if we're disabled or not yet
                        // fully set up.
                        if (mEnabled && mProvisioned) {
                            if (DEBUG) Slog.v(TAG, "Running a backup pass");

                            // Acquire the wakelock and pass it to the backup thread.  it will
                            // be released once backup concludes.
                            mWakelock.acquire();

                            Message msg = mBackupHandler.obtainMessage(MSG_RUN_BACKUP);
                            mBackupHandler.sendMessage(msg);
                        } else {
                            Slog.w(TAG, "Backup pass but e=" + mEnabled + " p=" + mProvisioned);
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
                    if (DEBUG) Slog.v(TAG, "Running a device init");

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
        if (DEBUG) Slog.v(TAG, "Initializing package tracking");

        // Remember our ancestral dataset
        mTokenFile = new File(mBaseStateDir, "ancestral");
        try {
            RandomAccessFile tf = new RandomAccessFile(mTokenFile, "r");
            int version = tf.readInt();
            if (version == CURRENT_ANCESTRAL_RECORD_VERSION) {
                mAncestralToken = tf.readLong();
                mCurrentToken = tf.readLong();

                int numPackages = tf.readInt();
                if (numPackages >= 0) {
                    mAncestralPackages = new HashSet<String>();
                    for (int i = 0; i < numPackages; i++) {
                        String pkgName = tf.readUTF();
                        mAncestralPackages.add(pkgName);
                    }
                }
            }
        } catch (FileNotFoundException fnf) {
            // Probably innocuous
            Slog.v(TAG, "No ancestral data");
        } catch (IOException e) {
            Slog.w(TAG, "Unable to read token file", e);
        }

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
                        if (DEBUG) Slog.v(TAG, "   + " + pkg);
                    } catch (NameNotFoundException e) {
                        // nope, this package was uninstalled; don't include it
                        if (DEBUG) Slog.v(TAG, "   - " + pkg);
                    }
                }
            } catch (EOFException e) {
                // Once we've rewritten the backup history log, atomically replace the
                // old one with the new one then reopen the file for continuing use.
                if (!tempProcessedFile.renameTo(mEverStored)) {
                    Slog.e(TAG, "Error renaming " + tempProcessedFile + " to " + mEverStored);
                }
            } catch (IOException e) {
                Slog.e(TAG, "Error in processed file", e);
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
        // Register for events related to sdcard installation.
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiver(mBroadcastReceiver, sdFilter);
    }

    private void parseLeftoverJournals() {
        for (File f : mJournalDir.listFiles()) {
            if (mJournal == null || f.compareTo(mJournal) != 0) {
                // This isn't the current journal, so it must be a leftover.  Read
                // out the package names mentioned there and schedule them for
                // backup.
                RandomAccessFile in = null;
                try {
                    Slog.i(TAG, "Found stale backup journal, scheduling");
                    in = new RandomAccessFile(f, "r");
                    while (true) {
                        String packageName = in.readUTF();
                        Slog.i(TAG, "  " + packageName);
                        dataChangedImpl(packageName);
                    }
                } catch (EOFException e) {
                    // no more data; we're done
                } catch (Exception e) {
                    Slog.e(TAG, "Can't read " + f, e);
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
        if (DEBUG) Slog.i(TAG, "recordInitPendingLocked: " + isPending
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

            mCurrentToken = 0;
            writeRestoreTokens();

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
                    dataChangedImpl(app.packageName);
                }
            }
        }
    }

    // Add a transport to our set of available backends.  If 'transport' is null, this
    // is an unregistration, and the transport's entry is removed from our bookkeeping.
    private void registerTransport(String name, IBackupTransport transport) {
        synchronized (mTransports) {
            if (DEBUG) Slog.v(TAG, "Registering transport " + name + " = " + transport);
            if (transport != null) {
                mTransports.put(name, transport);
            } else {
                mTransports.remove(name);
                if ((mCurrentTransport != null) && mCurrentTransport.equals(name)) {
                    mCurrentTransport = null;
                }
                // Nothing further to do in the unregistration case
                return;
            }
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
            if (DEBUG) Slog.d(TAG, "Received broadcast " + intent);

            String action = intent.getAction();
            boolean replacing = false;
            boolean added = false;
            Bundle extras = intent.getExtras();
            String pkgList[] = null;
            if (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
                    Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                Uri uri = intent.getData();
                if (uri == null) {
                    return;
                }
                String pkgName = uri.getSchemeSpecificPart();
                if (pkgName != null) {
                    pkgList = new String[] { pkgName };
                }
                added = Intent.ACTION_PACKAGE_ADDED.equals(action);
                replacing = extras.getBoolean(Intent.EXTRA_REPLACING, false);
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
                added = true;
                pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                added = false;
                pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            }
            if (pkgList == null || pkgList.length == 0) {
                return;
            }
            if (added) {
                synchronized (mBackupParticipants) {
                    for (String pkgName : pkgList) {
                        if (replacing) {
                            // The package was just upgraded
                            updatePackageParticipantsLocked(pkgName);
                        } else {
                            // The package was just added
                            addPackageParticipantsLocked(pkgName);
                        }
                    }
                }
            } else {
                if (replacing) {
                    // The package is being updated.  We'll receive a PACKAGE_ADDED shortly.
                } else {
                    synchronized (mBackupParticipants) {
                        for (String pkgName : pkgList) {
                            removePackageParticipantsLocked(pkgName);
                        }
                    }
                }
            }
        }
    };

    // ----- Track connection to GoogleBackupTransport service -----
    ServiceConnection mGoogleConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Slog.v(TAG, "Connected to Google transport");
            mGoogleTransport = IBackupTransport.Stub.asInterface(service);
            registerTransport(name.flattenToShortString(), mGoogleTransport);
        }

        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Slog.v(TAG, "Disconnected from Google transport");
            mGoogleTransport = null;
            registerTransport(name.flattenToShortString(), null);
        }
    };

    // Add the backup agents in the given package to our set of known backup participants.
    // If 'packageName' is null, adds all backup agents in the whole system.
    void addPackageParticipantsLocked(String packageName) {
        // Look for apps that define the android:backupAgent attribute
        if (DEBUG) Slog.v(TAG, "addPackageParticipantsLocked: " + packageName);
        List<PackageInfo> targetApps = allAgentPackages();
        addPackageParticipantsLockedInner(packageName, targetApps);
    }

    private void addPackageParticipantsLockedInner(String packageName,
            List<PackageInfo> targetPkgs) {
        if (DEBUG) {
            Slog.v(TAG, "Adding " + targetPkgs.size() + " backup participants:");
            for (PackageInfo p : targetPkgs) {
                Slog.v(TAG, "    " + p + " agent=" + p.applicationInfo.backupAgentName
                        + " uid=" + p.applicationInfo.uid
                        + " killAfterRestore="
                        + (((p.applicationInfo.flags & ApplicationInfo.FLAG_KILL_AFTER_RESTORE) != 0) ? "true" : "false")
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
                    if (DEBUG) Slog.i(TAG, "New app " + pkg.packageName
                            + " never backed up; scheduling");
                    dataChangedImpl(pkg.packageName);
                }
            }
        }
    }

    // Remove the given package's entry from our known active set.  If
    // 'packageName' is null, *all* participating apps will be removed.
    void removePackageParticipantsLocked(String packageName) {
        if (DEBUG) Slog.v(TAG, "removePackageParticipantsLocked: " + packageName);
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
            Slog.v(TAG, "removePackageParticipantsLockedInner (" + packageName
                    + ") removing " + agents.size() + " entries");
            for (PackageInfo p : agents) {
                Slog.v(TAG, "    - " + p);
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
                        || app.backupAgentName == null) {
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
            Slog.e(TAG, "updatePackageParticipants called with null package name");
            return;
        }
        if (DEBUG) Slog.v(TAG, "updatePackageParticipantsLocked: " + packageName);

        // brute force but small code size
        List<PackageInfo> allApps = allAgentPackages();
        removePackageParticipantsLockedInner(packageName, allApps);
        addPackageParticipantsLockedInner(packageName, allApps);
    }

    // Called from the backup task: record that the given app has been successfully
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
                Slog.e(TAG, "Can't log backup of " + packageName + " to " + mEverStored);
            } finally {
                try { if (out != null) out.close(); } catch (IOException e) {}
            }
        }
    }

    // Remove our awareness of having ever backed up the given package
    void removeEverBackedUp(String packageName) {
        if (DEBUG) Slog.v(TAG, "Removing backed-up knowledge of " + packageName + ", new set:");

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
                    if (DEBUG) Slog.v(TAG, "    " + s);
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
                Slog.w(TAG, "Error rewriting " + mEverStored, e);
                mEverStoredApps.clear();
                tempKnownFile.delete();
                mEverStored.delete();
            } finally {
                try { if (known != null) known.close(); } catch (IOException e) {}
            }
        }
    }

    // Persistently record the current and ancestral backup tokens as well
    // as the set of packages with data [supposedly] available in the
    // ancestral dataset.
    void writeRestoreTokens() {
        try {
            RandomAccessFile af = new RandomAccessFile(mTokenFile, "rwd");

            // First, the version number of this record, for futureproofing
            af.writeInt(CURRENT_ANCESTRAL_RECORD_VERSION);

            // Write the ancestral and current tokens
            af.writeLong(mAncestralToken);
            af.writeLong(mCurrentToken);

            // Now write the set of ancestral packages
            if (mAncestralPackages == null) {
                af.writeInt(-1);
            } else {
                af.writeInt(mAncestralPackages.size());
                if (DEBUG) Slog.v(TAG, "Ancestral packages:  " + mAncestralPackages.size());
                for (String pkgName : mAncestralPackages) {
                    af.writeUTF(pkgName);
                    if (DEBUG) Slog.v(TAG, "   " + pkgName);
                }
            }
            af.close();
        } catch (IOException e) {
            Slog.w(TAG, "Unable to write token file:", e);
        }
    }

    // Return the given transport
    private IBackupTransport getTransport(String transportName) {
        synchronized (mTransports) {
            IBackupTransport transport = mTransports.get(transportName);
            if (transport == null) {
                Slog.w(TAG, "Requested unavailable transport: " + transportName);
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
                    Slog.d(TAG, "awaiting agent for " + app);

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
                        Slog.w(TAG, "Timeout waiting for agent " + app);
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
                if (DEBUG) Slog.i(TAG, "allowClearUserData=false so not wiping "
                        + packageName);
                return;
            }
        } catch (NameNotFoundException e) {
            Slog.w(TAG, "Tried to clear data for " + packageName + " but not found");
            return;
        }

        ClearDataObserver observer = new ClearDataObserver();

        synchronized(mClearDataLock) {
            mClearingData = true;
            try {
                mActivityManager.clearApplicationUserData(packageName, observer);
            } catch (RemoteException e) {
                // can't happen because the activity manager is in this process
            }

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

    // Get the restore-set token for the best-available restore set for this package:
    // the active set if possible, else the ancestral one.  Returns zero if none available.
    long getAvailableRestoreToken(String packageName) {
        long token = mAncestralToken;
        synchronized (mQueueLock) {
            if (mEverStoredApps.contains(packageName)) {
                token = mCurrentToken;
            }
        }
        return token;
    }

    // -----
    // Utility methods used by the asynchronous-with-timeout backup/restore operations
    boolean waitUntilOperationComplete(int token) {
        int finalState = OP_PENDING;
        synchronized (mCurrentOpLock) {
            try {
                while ((finalState = mCurrentOperations.get(token, OP_TIMEOUT)) == OP_PENDING) {
                    try {
                        mCurrentOpLock.wait();
                    } catch (InterruptedException e) {}
                }
            } catch (IndexOutOfBoundsException e) {
                // the operation has been mysteriously cleared from our
                // bookkeeping -- consider this a success and ignore it.
            }
        }
        mBackupHandler.removeMessages(MSG_TIMEOUT);
        if (DEBUG) Slog.v(TAG, "operation " + Integer.toHexString(token)
                + " complete: finalState=" + finalState);
        return finalState == OP_ACKNOWLEDGED;
    }

    void prepareOperationTimeout(int token, long interval) {
        if (DEBUG) Slog.v(TAG, "starting timeout: token=" + Integer.toHexString(token)
                + " interval=" + interval);
        mCurrentOperations.put(token, OP_PENDING);
        Message msg = mBackupHandler.obtainMessage(MSG_TIMEOUT, token, 0);
        mBackupHandler.sendMessageDelayed(msg, interval);
    }

    // ----- Back up a set of applications via a worker thread -----

    class PerformBackupTask implements Runnable {
        private static final String TAG = "PerformBackupThread";
        IBackupTransport mTransport;
        ArrayList<BackupRequest> mQueue;
        File mStateDir;
        File mJournal;

        public PerformBackupTask(IBackupTransport transport, ArrayList<BackupRequest> queue,
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

        public void run() {
            int status = BackupConstants.TRANSPORT_OK;
            long startRealtime = SystemClock.elapsedRealtime();
            if (DEBUG) Slog.v(TAG, "Beginning backup of " + mQueue.size() + " targets");

            // Backups run at background priority
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

            try {
                EventLog.writeEvent(EventLogTags.BACKUP_START, mTransport.transportDirName());

                // If we haven't stored package manager metadata yet, we must init the transport.
                File pmState = new File(mStateDir, PACKAGE_MANAGER_SENTINEL);
                if (status == BackupConstants.TRANSPORT_OK && pmState.length() <= 0) {
                    Slog.i(TAG, "Initializing (wiping) backup state and transport storage");
                    resetBackupState(mStateDir);  // Just to make sure.
                    status = mTransport.initializeDevice();
                    if (status == BackupConstants.TRANSPORT_OK) {
                        EventLog.writeEvent(EventLogTags.BACKUP_INITIALIZE);
                    } else {
                        EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, "(initialize)");
                        Slog.e(TAG, "Transport error in initializeDevice()");
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
                        EventLog.writeEvent(EventLogTags.BACKUP_SUCCESS, mQueue.size(), millis);
                    } else {
                        EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, "(finish)");
                        Slog.e(TAG, "Transport error in finishBackup()");
                    }
                }

                if (status == BackupConstants.TRANSPORT_NOT_INITIALIZED) {
                    // The backend reports that our dataset has been wiped.  We need to
                    // reset all of our bookkeeping and instead run a new backup pass for
                    // everything.
                    EventLog.writeEvent(EventLogTags.BACKUP_RESET, mTransport.transportDirName());
                    resetBackupState(mStateDir);
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error in backup thread", e);
                status = BackupConstants.TRANSPORT_ERROR;
            } finally {
                // If everything actually went through and this is the first time we've
                // done a backup, we can now record what the current backup dataset token
                // is.
                if ((mCurrentToken == 0) && (status == BackupConstants.TRANSPORT_OK)) {
                    try {
                        mCurrentToken = mTransport.getCurrentRestoreSet();
                    } catch (RemoteException e) { /* cannot happen */ }
                    writeRestoreTokens();
                }

                // If things went wrong, we need to re-stage the apps we had expected
                // to be backing up in this pass.  This journals the package names in
                // the current active pending-backup file, not in the we are holding
                // here in mJournal.
                if (status != BackupConstants.TRANSPORT_OK) {
                    Slog.w(TAG, "Backup pass unsuccessful, restaging");
                    for (BackupRequest req : mQueue) {
                        dataChangedImpl(req.appInfo.packageName);
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
                    Slog.e(TAG, "Unable to remove backup journal file " + mJournal);
                }

                // Only once we're entirely finished do we release the wakelock
                if (status == BackupConstants.TRANSPORT_NOT_INITIALIZED) {
                    backupNow();
                }

                mWakelock.release();
            }
        }

        private int doQueuedBackups(IBackupTransport transport) {
            for (BackupRequest request : mQueue) {
                Slog.d(TAG, "starting agent for backup of " + request);

                IBackupAgent agent = null;
                int mode = (request.fullBackup)
                        ? IApplicationThread.BACKUP_MODE_FULL
                        : IApplicationThread.BACKUP_MODE_INCREMENTAL;
                try {
                    mWakelock.setWorkSource(new WorkSource(request.appInfo.uid));
                    agent = bindToAgentSynchronous(request.appInfo, mode);
                    if (agent != null) {
                        int result = processOneBackup(request, agent, transport);
                        if (result != BackupConstants.TRANSPORT_OK) return result;
                    }
                } catch (SecurityException ex) {
                    // Try for the next one.
                    Slog.d(TAG, "error in bind/backup", ex);
                } finally {
                    try {  // unbind even on timeout, just in case
                        mActivityManager.unbindBackupAgent(request.appInfo);
                    } catch (RemoteException e) {}
                }
            }

            mWakelock.setWorkSource(null);

            return BackupConstants.TRANSPORT_OK;
        }

        private int processOneBackup(BackupRequest request, IBackupAgent agent,
                IBackupTransport transport) {
            final String packageName = request.appInfo.packageName;
            if (DEBUG) Slog.d(TAG, "processOneBackup doBackup() on " + packageName);

            File savedStateName = new File(mStateDir, packageName);
            File backupDataName = new File(mDataDir, packageName + ".data");
            File newStateName = new File(mStateDir, packageName + ".new");

            ParcelFileDescriptor savedState = null;
            ParcelFileDescriptor backupData = null;
            ParcelFileDescriptor newState = null;

            PackageInfo packInfo;
            int token = mTokenGenerator.nextInt();
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

                // Initiate the target's backup pass
                prepareOperationTimeout(token, TIMEOUT_BACKUP_INTERVAL);
                agent.doBackup(savedState, backupData, newState, token, mBackupManagerBinder);
                boolean success = waitUntilOperationComplete(token);

                if (!success) {
                    // timeout -- bail out into the failed-transaction logic
                    throw new RuntimeException("Backup timeout");
                }

                logBackupComplete(packageName);
                if (DEBUG) Slog.v(TAG, "doBackup() success");
            } catch (Exception e) {
                Slog.e(TAG, "Error backing up " + packageName, e);
                EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, packageName, e.toString());
                backupDataName.delete();
                newStateName.delete();
                return BackupConstants.TRANSPORT_ERROR;
            } finally {
                try { if (savedState != null) savedState.close(); } catch (IOException e) {}
                try { if (backupData != null) backupData.close(); } catch (IOException e) {}
                try { if (newState != null) newState.close(); } catch (IOException e) {}
                savedState = backupData = newState = null;
                synchronized (mCurrentOpLock) {
                    mCurrentOperations.clear();
                }
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
                    if (DEBUG) Slog.i(TAG, "no backup data written; not calling transport");
                }

                // After successful transport, delete the now-stale data
                // and juggle the files so that next time we supply the agent
                // with the new state file it just created.
                if (result == BackupConstants.TRANSPORT_OK) {
                    backupDataName.delete();
                    newStateName.renameTo(savedStateName);
                    EventLog.writeEvent(EventLogTags.BACKUP_PACKAGE, packageName, size);
                } else {
                    EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, packageName);
                }
            } catch (Exception e) {
                Slog.e(TAG, "Transport error backing up " + packageName, e);
                EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, packageName);
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
            if (DEBUG) Slog.v(TAG, "System app " + target.packageName + " - skipping sig check");
            return true;
        }

        // Allow unsigned apps, but not signed on one device and unsigned on the other
        // !!! TODO: is this the right policy?
        Signature[] deviceSigs = target.signatures;
        if (DEBUG) Slog.v(TAG, "signaturesMatch(): stored=" + storedSigs
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

    class PerformRestoreTask implements Runnable {
        private IBackupTransport mTransport;
        private IRestoreObserver mObserver;
        private long mToken;
        private PackageInfo mTargetPackage;
        private File mStateDir;
        private int mPmToken;
        private boolean mNeedFullBackup;

        class RestoreRequest {
            public PackageInfo app;
            public int storedAppVersion;

            RestoreRequest(PackageInfo _app, int _version) {
                app = _app;
                storedAppVersion = _version;
            }
        }

        PerformRestoreTask(IBackupTransport transport, IRestoreObserver observer,
                long restoreSetToken, PackageInfo targetPackage, int pmToken,
                boolean needFullBackup) {
            mTransport = transport;
            mObserver = observer;
            mToken = restoreSetToken;
            mTargetPackage = targetPackage;
            mPmToken = pmToken;
            mNeedFullBackup = needFullBackup;

            try {
                mStateDir = new File(mBaseStateDir, transport.transportDirName());
            } catch (RemoteException e) {
                // can't happen; the transport is local
            }
        }

        public void run() {
            long startRealtime = SystemClock.elapsedRealtime();
            if (DEBUG) Slog.v(TAG, "Beginning restore process mTransport=" + mTransport
                    + " mObserver=" + mObserver + " mToken=" + Long.toHexString(mToken)
                    + " mTargetPackage=" + mTargetPackage + " mPmToken=" + mPmToken);

            PackageManagerBackupAgent pmAgent = null;
            int error = -1; // assume error

            // build the set of apps to restore
            try {
                // TODO: Log this before getAvailableRestoreSets, somehow
                EventLog.writeEvent(EventLogTags.RESTORE_START, mTransport.transportDirName(), mToken);

                // Get the list of all packages which have backup enabled.
                // (Include the Package Manager metadata pseudo-package first.)
                ArrayList<PackageInfo> restorePackages = new ArrayList<PackageInfo>();
                PackageInfo omPackage = new PackageInfo();
                omPackage.packageName = PACKAGE_MANAGER_SENTINEL;
                restorePackages.add(omPackage);

                List<PackageInfo> agentPackages = allAgentPackages();
                if (mTargetPackage == null) {
                    restorePackages.addAll(agentPackages);
                } else {
                    // Just one package to attempt restore of
                    restorePackages.add(mTargetPackage);
                }

                // let the observer know that we're running
                if (mObserver != null) {
                    try {
                        // !!! TODO: get an actual count from the transport after
                        // its startRestore() runs?
                        mObserver.restoreStarting(restorePackages.size());
                    } catch (RemoteException e) {
                        Slog.d(TAG, "Restore observer died at restoreStarting");
                        mObserver = null;
                    }
                }

                if (mTransport.startRestore(mToken, restorePackages.toArray(new PackageInfo[0])) !=
                        BackupConstants.TRANSPORT_OK) {
                    Slog.e(TAG, "Error starting restore operation");
                    EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                    return;
                }

                String packageName = mTransport.nextRestorePackage();
                if (packageName == null) {
                    Slog.e(TAG, "Error getting first restore package");
                    EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                    return;
                } else if (packageName.equals("")) {
                    Slog.i(TAG, "No restore data available");
                    int millis = (int) (SystemClock.elapsedRealtime() - startRealtime);
                    EventLog.writeEvent(EventLogTags.RESTORE_SUCCESS, 0, millis);
                    return;
                } else if (!packageName.equals(PACKAGE_MANAGER_SENTINEL)) {
                    Slog.e(TAG, "Expected restore data for \"" + PACKAGE_MANAGER_SENTINEL
                          + "\", found only \"" + packageName + "\"");
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, PACKAGE_MANAGER_SENTINEL,
                            "Package manager data missing");
                    return;
                }

                // Pull the Package Manager metadata from the restore set first
                pmAgent = new PackageManagerBackupAgent(
                        mPackageManager, agentPackages);
                processOneRestore(omPackage, 0, IBackupAgent.Stub.asInterface(pmAgent.onBind()),
                        mNeedFullBackup);

                // Verify that the backup set includes metadata.  If not, we can't do
                // signature/version verification etc, so we simply do not proceed with
                // the restore operation.
                if (!pmAgent.hasMetadata()) {
                    Slog.e(TAG, "No restore metadata available, so not restoring settings");
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, PACKAGE_MANAGER_SENTINEL,
                            "Package manager restore metadata missing");
                    return;
                }

                int count = 0;
                for (;;) {
                    packageName = mTransport.nextRestorePackage();

                    if (packageName == null) {
                        Slog.e(TAG, "Error getting next restore package");
                        EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                        return;
                    } else if (packageName.equals("")) {
                        if (DEBUG) Slog.v(TAG, "No next package, finishing restore");
                        break;
                    }

                    if (mObserver != null) {
                        try {
                            mObserver.onUpdate(count, packageName);
                        } catch (RemoteException e) {
                            Slog.d(TAG, "Restore observer died in onUpdate");
                            mObserver = null;
                        }
                    }

                    Metadata metaInfo = pmAgent.getRestoredMetadata(packageName);
                    if (metaInfo == null) {
                        Slog.e(TAG, "Missing metadata for " + packageName);
                        EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                                "Package metadata missing");
                        continue;
                    }

                    PackageInfo packageInfo;
                    try {
                        int flags = PackageManager.GET_SIGNATURES;
                        packageInfo = mPackageManager.getPackageInfo(packageName, flags);
                    } catch (NameNotFoundException e) {
                        Slog.e(TAG, "Invalid package restoring data", e);
                        EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                                "Package missing on device");
                        continue;
                    }

                    if (metaInfo.versionCode > packageInfo.versionCode) {
                        // Data is from a "newer" version of the app than we have currently
                        // installed.  If the app has not declared that it is prepared to
                        // handle this case, we do not attempt the restore.
                        if ((packageInfo.applicationInfo.flags
                                & ApplicationInfo.FLAG_RESTORE_ANY_VERSION) == 0) {
                            String message = "Version " + metaInfo.versionCode
                                    + " > installed version " + packageInfo.versionCode;
                            Slog.w(TAG, "Package " + packageName + ": " + message);
                            EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                                    packageName, message);
                            continue;
                        } else {
                            if (DEBUG) Slog.v(TAG, "Version " + metaInfo.versionCode
                                    + " > installed " + packageInfo.versionCode
                                    + " but restoreAnyVersion");
                        }
                    }

                    if (!signaturesMatch(metaInfo.signatures, packageInfo)) {
                        Slog.w(TAG, "Signature mismatch restoring " + packageName);
                        EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                                "Signature mismatch");
                        continue;
                    }

                    if (DEBUG) Slog.v(TAG, "Package " + packageName
                            + " restore version [" + metaInfo.versionCode
                            + "] is compatible with installed version ["
                            + packageInfo.versionCode + "]");

                    // Then set up and bind the agent
                    IBackupAgent agent = bindToAgentSynchronous(
                            packageInfo.applicationInfo,
                            IApplicationThread.BACKUP_MODE_INCREMENTAL);
                    if (agent == null) {
                        Slog.w(TAG, "Can't find backup agent for " + packageName);
                        EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                                "Restore agent missing");
                        continue;
                    }

                    // And then finally run the restore on this agent
                    try {
                        processOneRestore(packageInfo, metaInfo.versionCode, agent,
                                mNeedFullBackup);
                        ++count;
                    } finally {
                        // unbind and tidy up even on timeout or failure, just in case
                        mActivityManager.unbindBackupAgent(packageInfo.applicationInfo);

                        // The agent was probably running with a stub Application object,
                        // which isn't a valid run mode for the main app logic.  Shut
                        // down the app so that next time it's launched, it gets the
                        // usual full initialization.  Note that this is only done for
                        // full-system restores: when a single app has requested a restore,
                        // it is explicitly not killed following that operation.
                        if (mTargetPackage == null && (packageInfo.applicationInfo.flags
                                & ApplicationInfo.FLAG_KILL_AFTER_RESTORE) != 0) {
                            if (DEBUG) Slog.d(TAG, "Restore complete, killing host process of "
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
                EventLog.writeEvent(EventLogTags.RESTORE_SUCCESS, count, millis);
            } catch (Exception e) {
                Slog.e(TAG, "Error in restore thread", e);
            } finally {
                if (DEBUG) Slog.d(TAG, "finishing restore mObserver=" + mObserver);

                try {
                    mTransport.finishRestore();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error finishing restore", e);
                }

                if (mObserver != null) {
                    try {
                        mObserver.restoreFinished(error);
                    } catch (RemoteException e) {
                        Slog.d(TAG, "Restore observer died at restoreFinished");
                    }
                }

                // If this was a restoreAll operation, record that this was our
                // ancestral dataset, as well as the set of apps that are possibly
                // restoreable from the dataset
                if (mTargetPackage == null && pmAgent != null) {
                    mAncestralPackages = pmAgent.getRestoredPackages();
                    mAncestralToken = mToken;
                    writeRestoreTokens();
                }

                // We must under all circumstances tell the Package Manager to
                // proceed with install notifications if it's waiting for us.
                if (mPmToken > 0) {
                    if (DEBUG) Slog.v(TAG, "finishing PM token " + mPmToken);
                    try {
                        mPackageManagerBinder.finishPackageInstall(mPmToken);
                    } catch (RemoteException e) { /* can't happen */ }
                }

                // done; we can finally release the wakelock
                mWakelock.release();
            }
        }

        // Do the guts of a restore of one application, using mTransport.getRestoreData().
        void processOneRestore(PackageInfo app, int appVersionCode, IBackupAgent agent,
                boolean needFullBackup) {
            // !!! TODO: actually run the restore through mTransport
            final String packageName = app.packageName;

            if (DEBUG) Slog.d(TAG, "processOneRestore packageName=" + packageName);

            // !!! TODO: get the dirs from the transport
            File backupDataName = new File(mDataDir, packageName + ".restore");
            File newStateName = new File(mStateDir, packageName + ".new");
            File savedStateName = new File(mStateDir, packageName);

            ParcelFileDescriptor backupData = null;
            ParcelFileDescriptor newState = null;

            int token = mTokenGenerator.nextInt();
            try {
                // Run the transport's restore pass
                backupData = ParcelFileDescriptor.open(backupDataName,
                            ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

                if (mTransport.getRestoreData(backupData) != BackupConstants.TRANSPORT_OK) {
                    Slog.e(TAG, "Error getting restore data for " + packageName);
                    EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
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

                // Kick off the restore, checking for hung agents
                prepareOperationTimeout(token, TIMEOUT_RESTORE_INTERVAL);
                agent.doRestore(backupData, appVersionCode, newState, token, mBackupManagerBinder);
                boolean success = waitUntilOperationComplete(token);

                if (!success) {
                    throw new RuntimeException("restore timeout");
                }

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
                EventLog.writeEvent(EventLogTags.RESTORE_PACKAGE, packageName, size);
            } catch (Exception e) {
                Slog.e(TAG, "Error restoring data for " + packageName, e);
                EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName, e.toString());

                // If the agent fails restore, it might have put the app's data
                // into an incoherent state.  For consistency we wipe its data
                // again in this case before propagating the exception
                clearApplicationDataSynchronous(packageName);
            } finally {
                backupDataName.delete();
                try { if (backupData != null) backupData.close(); } catch (IOException e) {}
                try { if (newState != null) newState.close(); } catch (IOException e) {}
                backupData = newState = null;
                mCurrentOperations.delete(token);

                // If we know a priori that we'll need to perform a full post-restore backup
                // pass, clear the new state file data.  This means we're discarding work that
                // was just done by the app's agent, but this way the agent doesn't need to
                // take any special action based on global device state.
                if (needFullBackup) {
                    newStateName.delete();
                }
            }
        }
    }

    class PerformClearTask implements Runnable {
        IBackupTransport mTransport;
        PackageInfo mPackage;

        PerformClearTask(IBackupTransport transport, PackageInfo packageInfo) {
            mTransport = transport;
            mPackage = packageInfo;
        }

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
                mWakelock.release();
            }
        }
    }

    class PerformInitializeTask implements Runnable {
        HashSet<String> mQueue;

        PerformInitializeTask(HashSet<String> transportNames) {
            mQueue = transportNames;
        }

        public void run() {
            try {
                for (String transportName : mQueue) {
                    IBackupTransport transport = getTransport(transportName);
                    if (transport == null) {
                        Slog.e(TAG, "Requested init for " + transportName + " but not found");
                        continue;
                    }

                    Slog.i(TAG, "Initializing (wiping) backup transport storage: " + transportName);
                    EventLog.writeEvent(EventLogTags.BACKUP_START, transport.transportDirName());
                    long startRealtime = SystemClock.elapsedRealtime();
                    int status = transport.initializeDevice();

                    if (status == BackupConstants.TRANSPORT_OK) {
                        status = transport.finishBackup();
                    }

                    // Okay, the wipe really happened.  Clean up our local bookkeeping.
                    if (status == BackupConstants.TRANSPORT_OK) {
                        Slog.i(TAG, "Device init successful");
                        int millis = (int) (SystemClock.elapsedRealtime() - startRealtime);
                        EventLog.writeEvent(EventLogTags.BACKUP_INITIALIZE);
                        resetBackupState(new File(mBaseStateDir, transport.transportDirName()));
                        EventLog.writeEvent(EventLogTags.BACKUP_SUCCESS, 0, millis);
                        synchronized (mQueueLock) {
                            recordInitPendingLocked(false, transportName);
                        }
                    } else {
                        // If this didn't work, requeue this one and try again
                        // after a suitable interval
                        Slog.e(TAG, "Transport error in initializeDevice()");
                        EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, "(initialize)");
                        synchronized (mQueueLock) {
                            recordInitPendingLocked(true, transportName);
                        }
                        // do this via another alarm to make sure of the wakelock states
                        long delay = transport.requestBackupTime();
                        if (DEBUG) Slog.w(TAG, "init failed on "
                                + transportName + " resched in " + delay);
                        mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                                System.currentTimeMillis() + delay, mRunInitIntent);
                    }
                }
            } catch (RemoteException e) {
                // can't happen; the transports are local
            } catch (Exception e) {
                Slog.e(TAG, "Unexpected error performing init", e);
            } finally {
                // Done; release the wakelock
                mWakelock.release();
            }
        }
    }

    private void dataChangedImpl(String packageName) {
        HashSet<ApplicationInfo> targets = dataChangedTargets(packageName);
        dataChangedImpl(packageName, targets);
    }

    private void dataChangedImpl(String packageName, HashSet<ApplicationInfo> targets) {
        // Record that we need a backup pass for the caller.  Since multiple callers
        // may share a uid, we need to note all candidates within that uid and schedule
        // a backup pass for each of them.
        EventLog.writeEvent(EventLogTags.BACKUP_DATA_CHANGED, packageName);

        if (targets == null) {
            Slog.w(TAG, "dataChanged but no participant pkg='" + packageName + "'"
                   + " uid=" + Binder.getCallingUid());
            return;
        }

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
                            Slog.d(TAG, "Now awaiting backup for " + numKeys + " participants:");
                            for (BackupRequest b : mPendingBackups.values()) {
                                Slog.d(TAG, "    + " + b + " agent=" + b.appInfo.backupAgentName);
                            }
                        }
                    }
                }
            }
        }
    }

    // Note: packageName is currently unused, but may be in the future
    private HashSet<ApplicationInfo> dataChangedTargets(String packageName) {
        // If the caller does not hold the BACKUP permission, it can only request a
        // backup of its own data.
        if ((mContext.checkPermission(android.Manifest.permission.BACKUP, Binder.getCallingPid(),
                Binder.getCallingUid())) == PackageManager.PERMISSION_DENIED) {
            synchronized (mBackupParticipants) {
                return mBackupParticipants.get(Binder.getCallingUid());
            }
        }

        // a caller with full permission can ask to back up any participating app
        // !!! TODO: allow backup of ANY app?
        HashSet<ApplicationInfo> targets = new HashSet<ApplicationInfo>();
        synchronized (mBackupParticipants) {
            int N = mBackupParticipants.size();
            for (int i = 0; i < N; i++) {
                HashSet<ApplicationInfo> s = mBackupParticipants.valueAt(i);
                if (s != null) {
                    targets.addAll(s);
                }
            }
        }
        return targets;
    }

    private void writeToJournalLocked(String str) {
        RandomAccessFile out = null;
        try {
            if (mJournal == null) mJournal = File.createTempFile("journal", null, mJournalDir);
            out = new RandomAccessFile(mJournal, "rws");
            out.seek(out.length());
            out.writeUTF(str);
        } catch (IOException e) {
            Slog.e(TAG, "Can't write " + str + " to backup journal", e);
            mJournal = null;
        } finally {
            try { if (out != null) out.close(); } catch (IOException e) {}
        }
    }

    // ----- IBackupManager binder interface -----

    public void dataChanged(final String packageName) {
        final HashSet<ApplicationInfo> targets = dataChangedTargets(packageName);
        if (targets == null) {
            Slog.w(TAG, "dataChanged but no participant pkg='" + packageName + "'"
                   + " uid=" + Binder.getCallingUid());
            return;
        }

        mBackupHandler.post(new Runnable() {
                public void run() {
                    dataChangedImpl(packageName, targets);
                }
            });
    }

    // Clear the given package's backup data from the current transport
    public void clearBackupData(String packageName) {
        if (DEBUG) Slog.v(TAG, "clearBackupData() of " + packageName);
        PackageInfo info;
        try {
            info = mPackageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        } catch (NameNotFoundException e) {
            Slog.d(TAG, "No such package '" + packageName + "' - not clearing backup data");
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
            if (DEBUG) Slog.v(TAG, "Privileged caller, allowing clear of other apps");
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
                if (DEBUG) Slog.v(TAG, "Found the app - running clear process");
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

        if (DEBUG) Slog.v(TAG, "Scheduling immediate backup pass");
        synchronized (mQueueLock) {
            // Because the alarms we are using can jitter, and we want an *immediate*
            // backup pass to happen, we restart the timer beginning with "next time,"
            // then manually fire the backup trigger intent ourselves.
            startBackupAlarmsLocked(BACKUP_INTERVAL);
            try {
                mRunBackupIntent.send();
            } catch (PendingIntent.CanceledException e) {
                // should never happen
                Slog.e(TAG, "run-backup intent cancelled!");
            }
        }
    }

    // Enable/disable the backup service
    public void setBackupEnabled(boolean enable) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setBackupEnabled");

        Slog.i(TAG, "Backup enabled => " + enable);

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
                if (DEBUG) Slog.i(TAG, "Opting out of backup");

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

    // Enable/disable automatic restore of app data at install time
    public void setAutoRestore(boolean doAutoRestore) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
        "setBackupEnabled");

        Slog.i(TAG, "Auto restore => " + doAutoRestore);

        synchronized (this) {
            Settings.Secure.putInt(mContext.getContentResolver(),
                    Settings.Secure.BACKUP_AUTO_RESTORE, doAutoRestore ? 1 : 0);
            mAutoRestore = doAutoRestore;
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
                Slog.w(TAG, "Backup service no longer provisioned");
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
        if (DEBUG) Slog.v(TAG, "... getCurrentTransport() returning " + mCurrentTransport);
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
                Slog.v(TAG, "selectBackupTransport() set " + mCurrentTransport
                        + " returning " + prevTransport);
            } else {
                Slog.w(TAG, "Attempt to select unavailable transport " + transport);
            }
            return prevTransport;
        }
    }

    // Callback: a requested backup agent has been instantiated.  This should only
    // be called from the Activity Manager.
    public void agentConnected(String packageName, IBinder agentBinder) {
        synchronized(mAgentConnectLock) {
            if (Binder.getCallingUid() == Process.SYSTEM_UID) {
                Slog.d(TAG, "agentConnected pkg=" + packageName + " agent=" + agentBinder);
                IBackupAgent agent = IBackupAgent.Stub.asInterface(agentBinder);
                mConnectedAgent = agent;
                mConnecting = false;
            } else {
                Slog.w(TAG, "Non-system process uid=" + Binder.getCallingUid()
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
                Slog.w(TAG, "Non-system process uid=" + Binder.getCallingUid()
                        + " claiming agent disconnected");
            }
            mAgentConnectLock.notifyAll();
        }
    }

    // An application being installed will need a restore pass, then the Package Manager
    // will need to be told when the restore is finished.
    public void restoreAtInstall(String packageName, int token) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            Slog.w(TAG, "Non-system process uid=" + Binder.getCallingUid()
                    + " attemping install-time restore");
            return;
        }

        long restoreSet = getAvailableRestoreToken(packageName);
        if (DEBUG) Slog.v(TAG, "restoreAtInstall pkg=" + packageName
                + " token=" + Integer.toHexString(token));

        if (mAutoRestore && mProvisioned && restoreSet != 0) {
            // okay, we're going to attempt a restore of this package from this restore set.
            // The eventual message back into the Package Manager to run the post-install
            // steps for 'token' will be issued from the restore handling code.

            // We can use a synthetic PackageInfo here because:
            //   1. We know it's valid, since the Package Manager supplied the name
            //   2. Only the packageName field will be used by the restore code
            PackageInfo pkg = new PackageInfo();
            pkg.packageName = packageName;

            mWakelock.acquire();
            Message msg = mBackupHandler.obtainMessage(MSG_RUN_RESTORE);
            msg.obj = new RestoreParams(getTransport(mCurrentTransport), null,
                    restoreSet, pkg, token, true);
            mBackupHandler.sendMessage(msg);
        } else {
            // Auto-restore disabled or no way to attempt a restore; just tell the Package
            // Manager to proceed with the post-install handling for this package.
            if (DEBUG) Slog.v(TAG, "No restore set -- skipping restore");
            try {
                mPackageManagerBinder.finishPackageInstall(token);
            } catch (RemoteException e) { /* can't happen */ }
        }
    }

    // Hand off a restore session
    public IRestoreSession beginRestoreSession(String packageName, String transport) {
        if (DEBUG) Slog.v(TAG, "beginRestoreSession: pkg=" + packageName
                + " transport=" + transport);

        boolean needPermission = true;
        if (transport == null) {
            transport = mCurrentTransport;

            if (packageName != null) {
                PackageInfo app = null;
                try {
                    app = mPackageManager.getPackageInfo(packageName, 0);
                } catch (NameNotFoundException nnf) {
                    Slog.w(TAG, "Asked to restore nonexistent pkg " + packageName);
                    throw new IllegalArgumentException("Package " + packageName + " not found");
                }

                if (app.applicationInfo.uid == Binder.getCallingUid()) {
                    // So: using the current active transport, and the caller has asked
                    // that its own package will be restored.  In this narrow use case
                    // we do not require the caller to hold the permission.
                    needPermission = false;
                }
            }
        }

        if (needPermission) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                    "beginRestoreSession");
        } else {
            if (DEBUG) Slog.d(TAG, "restoring self on current transport; no permission needed");
        }

        synchronized(this) {
            if (mActiveRestoreSession != null) {
                Slog.d(TAG, "Restore session requested but one already active");
                return null;
            }
            mActiveRestoreSession = new ActiveRestoreSession(packageName, transport);
        }
        return mActiveRestoreSession;
    }

    // Note that a currently-active backup agent has notified us that it has
    // completed the given outstanding asynchronous backup/restore operation.
    public void opComplete(int token) {
        synchronized (mCurrentOpLock) {
            if (DEBUG) Slog.v(TAG, "opComplete: " + Integer.toHexString(token));
            mCurrentOperations.put(token, OP_ACKNOWLEDGED);
            mCurrentOpLock.notifyAll();
        }
    }

    // ----- Restore session -----

    class ActiveRestoreSession extends IRestoreSession.Stub {
        private static final String TAG = "RestoreSession";

        private String mPackageName;
        private IBackupTransport mRestoreTransport = null;
        RestoreSet[] mRestoreSets = null;

        ActiveRestoreSession(String packageName, String transport) {
            mPackageName = packageName;
            mRestoreTransport = getTransport(transport);
        }

        // --- Binder interface ---
        public synchronized int getAvailableRestoreSets(IRestoreObserver observer) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                    "getAvailableRestoreSets");
            if (observer == null) {
                throw new IllegalArgumentException("Observer must not be null");
            }

            long oldId = Binder.clearCallingIdentity();
            try {
                if (mRestoreTransport == null) {
                    Slog.w(TAG, "Null transport getting restore sets");
                    return -1;
                }
                // spin off the transport request to our service thread
                mWakelock.acquire();
                Message msg = mBackupHandler.obtainMessage(MSG_RUN_GET_RESTORE_SETS,
                        new RestoreGetSetsParams(mRestoreTransport, this, observer));
                mBackupHandler.sendMessage(msg);
                return 0;
            } catch (Exception e) {
                Slog.e(TAG, "Error in getAvailableRestoreSets", e);
                return -1;
            } finally {
                Binder.restoreCallingIdentity(oldId);
            }
        }

        public synchronized int restoreAll(long token, IRestoreObserver observer) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                    "performRestore");

            if (DEBUG) Slog.d(TAG, "restoreAll token=" + Long.toHexString(token)
                    + " observer=" + observer);

            if (mRestoreTransport == null || mRestoreSets == null) {
                Slog.e(TAG, "Ignoring restoreAll() with no restore set");
                return -1;
            }

            if (mPackageName != null) {
                Slog.e(TAG, "Ignoring restoreAll() on single-package session");
                return -1;
            }

            synchronized (mQueueLock) {
                for (int i = 0; i < mRestoreSets.length; i++) {
                    if (token == mRestoreSets[i].token) {
                        long oldId = Binder.clearCallingIdentity();
                        mWakelock.acquire();
                        Message msg = mBackupHandler.obtainMessage(MSG_RUN_RESTORE);
                        msg.obj = new RestoreParams(mRestoreTransport, observer, token, true);
                        mBackupHandler.sendMessage(msg);
                        Binder.restoreCallingIdentity(oldId);
                        return 0;
                    }
                }
            }

            Slog.w(TAG, "Restore token " + Long.toHexString(token) + " not found");
            return -1;
        }

        public synchronized int restorePackage(String packageName, IRestoreObserver observer) {
            if (DEBUG) Slog.v(TAG, "restorePackage pkg=" + packageName + " obs=" + observer);

            if (mPackageName != null) {
                if (! mPackageName.equals(packageName)) {
                    Slog.e(TAG, "Ignoring attempt to restore pkg=" + packageName
                            + " on session for package " + mPackageName);
                    return -1;
                }
            }

            PackageInfo app = null;
            try {
                app = mPackageManager.getPackageInfo(packageName, 0);
            } catch (NameNotFoundException nnf) {
                Slog.w(TAG, "Asked to restore nonexistent pkg " + packageName);
                return -1;
            }

            // If the caller is not privileged and is not coming from the target
            // app's uid, throw a permission exception back to the caller.
            int perm = mContext.checkPermission(android.Manifest.permission.BACKUP,
                    Binder.getCallingPid(), Binder.getCallingUid());
            if ((perm == PackageManager.PERMISSION_DENIED) &&
                    (app.applicationInfo.uid != Binder.getCallingUid())) {
                Slog.w(TAG, "restorePackage: bad packageName=" + packageName
                        + " or calling uid=" + Binder.getCallingUid());
                throw new SecurityException("No permission to restore other packages");
            }

            // If the package has no backup agent, we obviously cannot proceed
            if (app.applicationInfo.backupAgentName == null) {
                Slog.w(TAG, "Asked to restore package " + packageName + " with no agent");
                return -1;
            }

            // So far so good; we're allowed to try to restore this package.  Now
            // check whether there is data for it in the current dataset, falling back
            // to the ancestral dataset if not.
            long token = getAvailableRestoreToken(packageName);

            // If we didn't come up with a place to look -- no ancestral dataset and
            // the app has never been backed up from this device -- there's nothing
            // to do but return failure.
            if (token == 0) {
                if (DEBUG) Slog.w(TAG, "No data available for this package; not restoring");
                return -1;
            }

            // Ready to go:  enqueue the restore request and claim success
            long oldId = Binder.clearCallingIdentity();
            mWakelock.acquire();
            Message msg = mBackupHandler.obtainMessage(MSG_RUN_RESTORE);
            msg.obj = new RestoreParams(mRestoreTransport, observer, token, app, 0, false);
            mBackupHandler.sendMessage(msg);
            Binder.restoreCallingIdentity(oldId);
            return 0;
        }

        public synchronized void endRestoreSession() {
            if (DEBUG) Slog.d(TAG, "endRestoreSession");

            synchronized (this) {
                long oldId = Binder.clearCallingIdentity();
                try {
                    if (mRestoreTransport != null) mRestoreTransport.finishRestore();
                } catch (Exception e) {
                    Slog.e(TAG, "Error in finishRestore", e);
                } finally {
                    mRestoreTransport = null;
                    Binder.restoreCallingIdentity(oldId);
                }
            }

            synchronized (BackupManagerService.this) {
                if (BackupManagerService.this.mActiveRestoreSession == this) {
                    BackupManagerService.this.mActiveRestoreSession = null;
                } else {
                    Slog.e(TAG, "ending non-current restore session");
                }
            }
        }
    }


    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mQueueLock) {
            pw.println("Backup Manager is " + (mEnabled ? "enabled" : "disabled")
                    + " / " + (!mProvisioned ? "not " : "") + "provisioned / "
                    + (this.mPendingInits.size() == 0 ? "not " : "") + "pending init");
            pw.println("Auto-restore is " + (mAutoRestore ? "enabled" : "disabled"));
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
                    Slog.e(TAG, "Error in transportDirName()", e);
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

            pw.println("Ancestral packages: "
                    + (mAncestralPackages == null ? "none" : mAncestralPackages.size()));
            if (mAncestralPackages != null) {
                for (String pkg : mAncestralPackages) {
                    pw.println("    " + pkg);
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
