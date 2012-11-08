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
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackup;
import android.app.backup.RestoreSet;
import android.app.backup.IBackupManager;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreObserver;
import android.app.backup.IRestoreSession;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
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
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.Environment.UserEnvironment;
import android.os.storage.IMountService;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StringBuilderPrinter;

import com.android.internal.backup.BackupConstants;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.backup.LocalTransport;
import com.android.server.PackageManagerBackupAgent.Metadata;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

class BackupManagerService extends IBackupManager.Stub {
    private static final String TAG = "BackupManagerService";
    private static final boolean DEBUG = false;
    private static final boolean MORE_DEBUG = false;

    // Name and current contents version of the full-backup manifest file
    static final String BACKUP_MANIFEST_FILENAME = "_manifest";
    static final int BACKUP_MANIFEST_VERSION = 1;
    static final String BACKUP_FILE_HEADER_MAGIC = "ANDROID BACKUP\n";
    static final int BACKUP_FILE_VERSION = 1;
    static final boolean COMPRESS_FULL_BACKUPS = true; // should be true in production

    static final String SHARED_BACKUP_AGENT_PACKAGE = "com.android.sharedstoragebackup";

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
    private static final int MSG_RESTORE_TIMEOUT = 8;
    private static final int MSG_FULL_CONFIRMATION_TIMEOUT = 9;
    private static final int MSG_RUN_FULL_RESTORE = 10;

    // backup task state machine tick
    static final int MSG_BACKUP_RESTORE_STEP = 20;
    static final int MSG_OP_COMPLETE = 21;

    // Timeout interval for deciding that a bind or clear-data has taken too long
    static final long TIMEOUT_INTERVAL = 10 * 1000;

    // Timeout intervals for agent backup & restore operations
    static final long TIMEOUT_BACKUP_INTERVAL = 30 * 1000;
    static final long TIMEOUT_FULL_BACKUP_INTERVAL = 5 * 60 * 1000;
    static final long TIMEOUT_SHARED_BACKUP_INTERVAL = 30 * 60 * 1000;
    static final long TIMEOUT_RESTORE_INTERVAL = 60 * 1000;

    // User confirmation timeout for a full backup/restore operation.  It's this long in
    // order to give them time to enter the backup password.
    static final long TIMEOUT_FULL_CONFIRMATION = 60 * 1000;

    private Context mContext;
    private PackageManager mPackageManager;
    IPackageManager mPackageManagerBinder;
    private IActivityManager mActivityManager;
    private PowerManager mPowerManager;
    private AlarmManager mAlarmManager;
    private IMountService mMountService;
    IBackupManager mBackupManagerBinder;

    boolean mEnabled;   // access to this is synchronized on 'this'
    boolean mProvisioned;
    boolean mAutoRestore;
    PowerManager.WakeLock mWakelock;
    HandlerThread mHandlerThread;
    BackupHandler mBackupHandler;
    PendingIntent mRunBackupIntent, mRunInitIntent;
    BroadcastReceiver mRunBackupReceiver, mRunInitReceiver;
    // map UIDs to the set of participating packages under that UID
    final SparseArray<HashSet<String>> mBackupParticipants
            = new SparseArray<HashSet<String>>();
    // set of backup services that have pending changes
    class BackupRequest {
        public String packageName;

        BackupRequest(String pkgName) {
            packageName = pkgName;
        }

        public String toString() {
            return "BackupRequest{pkg=" + packageName + "}";
        }
    }
    // Backups that we haven't started yet.  Keys are package names.
    HashMap<String,BackupRequest> mPendingBackups
            = new HashMap<String,BackupRequest>();

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
    volatile boolean mBackupRunning;
    volatile boolean mConnecting;
    volatile long mLastBackupPass;
    volatile long mNextBackupPass;

    // For debugging, we maintain a progress trace of operations during backup
    static final boolean DEBUG_BACKUP_TRACE = true;
    final List<String> mBackupTrace = new ArrayList<String>();

    // A similar synchronization mechanism around clearing apps' data for restore
    final Object mClearDataLock = new Object();
    volatile boolean mClearingData;

    // Transport bookkeeping
    final HashMap<String,IBackupTransport> mTransports
            = new HashMap<String,IBackupTransport>();
    String mCurrentTransport;
    IBackupTransport mLocalTransport, mGoogleTransport;
    ActiveRestoreSession mActiveRestoreSession;

    // Watch the device provisioning operation during setup
    ContentObserver mProvisionedObserver;

    class ProvisionedObserver extends ContentObserver {
        public ProvisionedObserver(Handler handler) {
            super(handler);
        }

        public void onChange(boolean selfChange) {
            final boolean wasProvisioned = mProvisioned;
            final boolean isProvisioned = deviceIsProvisioned();
            // latch: never unprovision
            mProvisioned = wasProvisioned || isProvisioned;
            if (MORE_DEBUG) {
                Slog.d(TAG, "Provisioning change: was=" + wasProvisioned
                        + " is=" + isProvisioned + " now=" + mProvisioned);
            }

            synchronized (mQueueLock) {
                if (mProvisioned && !wasProvisioned && mEnabled) {
                    // we're now good to go, so start the backup alarms
                    if (MORE_DEBUG) Slog.d(TAG, "Now provisioned, so starting backups");
                    startBackupAlarmsLocked(FIRST_BACKUP_INTERVAL);
                }
            }
        }
    }

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
        public String[] filterSet;

        RestoreParams(IBackupTransport _transport, IRestoreObserver _obs,
                long _token, PackageInfo _pkg, int _pmToken, boolean _needFullBackup) {
            transport = _transport;
            observer = _obs;
            token = _token;
            pkgInfo = _pkg;
            pmToken = _pmToken;
            needFullBackup = _needFullBackup;
            filterSet = null;
        }

        RestoreParams(IBackupTransport _transport, IRestoreObserver _obs, long _token,
                boolean _needFullBackup) {
            transport = _transport;
            observer = _obs;
            token = _token;
            pkgInfo = null;
            pmToken = 0;
            needFullBackup = _needFullBackup;
            filterSet = null;
        }

        RestoreParams(IBackupTransport _transport, IRestoreObserver _obs, long _token,
                String[] _filterSet, boolean _needFullBackup) {
            transport = _transport;
            observer = _obs;
            token = _token;
            pkgInfo = null;
            pmToken = 0;
            needFullBackup = _needFullBackup;
            filterSet = _filterSet;
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

    class FullParams {
        public ParcelFileDescriptor fd;
        public final AtomicBoolean latch;
        public IFullBackupRestoreObserver observer;
        public String curPassword;     // filled in by the confirmation step
        public String encryptPassword;

        FullParams() {
            latch = new AtomicBoolean(false);
        }
    }

    class FullBackupParams extends FullParams {
        public boolean includeApks;
        public boolean includeShared;
        public boolean allApps;
        public boolean includeSystem;
        public String[] packages;

        FullBackupParams(ParcelFileDescriptor output, boolean saveApks, boolean saveShared,
                boolean doAllApps, boolean doSystem, String[] pkgList) {
            fd = output;
            includeApks = saveApks;
            includeShared = saveShared;
            allApps = doAllApps;
            includeSystem = doSystem;
            packages = pkgList;
        }
    }

    class FullRestoreParams extends FullParams {
        FullRestoreParams(ParcelFileDescriptor input) {
            fd = input;
        }
    }

    // Bookkeeping of in-flight operations for timeout etc. purposes.  The operation
    // token is the index of the entry in the pending-operations list.
    static final int OP_PENDING = 0;
    static final int OP_ACKNOWLEDGED = 1;
    static final int OP_TIMEOUT = -1;

    class Operation {
        public int state;
        public BackupRestoreTask callback;

        Operation(int initialState, BackupRestoreTask callbackObj) {
            state = initialState;
            callback = callbackObj;
        }
    }
    final SparseArray<Operation> mCurrentOperations = new SparseArray<Operation>();
    final Object mCurrentOpLock = new Object();
    final Random mTokenGenerator = new Random();

    final SparseArray<FullParams> mFullConfirmations = new SparseArray<FullParams>();

    // Where we keep our journal files and other bookkeeping
    File mBaseStateDir;
    File mDataDir;
    File mJournalDir;
    File mJournal;

    // Backup password, if any, and the file where it's saved.  What is stored is not the
    // password text itself; it's the result of a PBKDF2 hash with a randomly chosen (but
    // persisted) salt.  Validation is performed by running the challenge text through the
    // same PBKDF2 cycle with the persisted salt; if the resulting derived key string matches
    // the saved hash string, then the challenge text matches the originally supplied
    // password text.
    private final SecureRandom mRng = new SecureRandom();
    private String mPasswordHash;
    private File mPasswordHashFile;
    private byte[] mPasswordSalt;

    // Configuration of PBKDF2 that we use for generating pw hashes and intermediate keys
    static final int PBKDF2_HASH_ROUNDS = 10000;
    static final int PBKDF2_KEY_SIZE = 256;     // bits
    static final int PBKDF2_SALT_SIZE = 512;    // bits
    static final String ENCRYPTION_ALGORITHM_NAME = "AES-256";

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

    // Utility: build a new random integer token
    int generateToken() {
        int token;
        do {
            synchronized (mTokenGenerator) {
                token = mTokenGenerator.nextInt();
            }
        } while (token < 0);
        return token;
    }

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
                    synchronized (mQueueLock) {
                        mBackupRunning = false;
                    }
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

                // At this point, we have started a new journal file, and the old
                // file identity is being passed to the backup processing task.
                // When it completes successfully, that old journal file will be
                // deleted.  If we crash prior to that, the old journal is parsed
                // at next boot and the journaled requests fulfilled.
                if (queue.size() > 0) {
                    // Spin up a backup state sequence and set it running
                    PerformBackupTask pbt = new PerformBackupTask(transport, queue, oldJournal);
                    Message pbtMessage = obtainMessage(MSG_BACKUP_RESTORE_STEP, pbt);
                    sendMessage(pbtMessage);
                } else {
                    Slog.v(TAG, "Backup requested but nothing pending");
                    synchronized (mQueueLock) {
                        mBackupRunning = false;
                    }
                    mWakelock.release();
                }
                break;
            }

            case MSG_BACKUP_RESTORE_STEP:
            {
                try {
                    BackupRestoreTask task = (BackupRestoreTask) msg.obj;
                    if (MORE_DEBUG) Slog.v(TAG, "Got next step for " + task + ", executing");
                    task.execute();
                } catch (ClassCastException e) {
                    Slog.e(TAG, "Invalid backup task in flight, obj=" + msg.obj);
                }
                break;
            }

            case MSG_OP_COMPLETE:
            {
                try {
                    BackupRestoreTask task = (BackupRestoreTask) msg.obj;
                    task.operationComplete();
                } catch (ClassCastException e) {
                    Slog.e(TAG, "Invalid completion in flight, obj=" + msg.obj);
                }
                break;
            }

            case MSG_RUN_FULL_BACKUP:
            {
                // TODO: refactor full backup to be a looper-based state machine
                // similar to normal backup/restore.
                FullBackupParams params = (FullBackupParams)msg.obj;
                PerformFullBackupTask task = new PerformFullBackupTask(params.fd,
                        params.observer, params.includeApks,
                        params.includeShared, params.curPassword, params.encryptPassword,
                        params.allApps, params.includeSystem, params.packages, params.latch);
                (new Thread(task)).start();
                break;
            }

            case MSG_RUN_RESTORE:
            {
                RestoreParams params = (RestoreParams)msg.obj;
                Slog.d(TAG, "MSG_RUN_RESTORE observer=" + params.observer);
                PerformRestoreTask task = new PerformRestoreTask(
                        params.transport, params.observer,
                        params.token, params.pkgInfo, params.pmToken,
                        params.needFullBackup, params.filterSet);
                Message restoreMsg = obtainMessage(MSG_BACKUP_RESTORE_STEP, task);
                sendMessage(restoreMsg);
                break;
            }

            case MSG_RUN_FULL_RESTORE:
            {
                // TODO: refactor full restore to be a looper-based state machine
                // similar to normal backup/restore.
                FullRestoreParams params = (FullRestoreParams)msg.obj;
                PerformFullRestoreTask task = new PerformFullRestoreTask(params.fd,
                        params.curPassword, params.encryptPassword,
                        params.observer, params.latch);
                (new Thread(task)).start();
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

                    // Done: reset the session timeout clock
                    removeMessages(MSG_RESTORE_TIMEOUT);
                    sendEmptyMessageDelayed(MSG_RESTORE_TIMEOUT, TIMEOUT_RESTORE_INTERVAL);

                    mWakelock.release();
                }
                break;
            }

            case MSG_TIMEOUT:
            {
                handleTimeout(msg.arg1, msg.obj);
                break;
            }

            case MSG_RESTORE_TIMEOUT:
            {
                synchronized (BackupManagerService.this) {
                    if (mActiveRestoreSession != null) {
                        // Client app left the restore session dangling.  We know that it
                        // can't be in the middle of an actual restore operation because
                        // the timeout is suspended while a restore is in progress.  Clean
                        // up now.
                        Slog.w(TAG, "Restore session timed out; aborting");
                        post(mActiveRestoreSession.new EndRestoreRunnable(
                                BackupManagerService.this, mActiveRestoreSession));
                    }
                }
            }

            case MSG_FULL_CONFIRMATION_TIMEOUT:
            {
                synchronized (mFullConfirmations) {
                    FullParams params = mFullConfirmations.get(msg.arg1);
                    if (params != null) {
                        Slog.i(TAG, "Full backup/restore timed out waiting for user confirmation");

                        // Release the waiter; timeout == completion
                        signalFullBackupRestoreCompletion(params);

                        // Remove the token from the set
                        mFullConfirmations.delete(msg.arg1);

                        // Report a timeout to the observer, if any
                        if (params.observer != null) {
                            try {
                                params.observer.onTimeout();
                            } catch (RemoteException e) {
                                /* don't care if the app has gone away */
                            }
                        }
                    } else {
                        Slog.d(TAG, "couldn't find params for token " + msg.arg1);
                    }
                }
                break;
            }
            }
        }
    }

    // ----- Debug-only backup operation trace -----
    void addBackupTrace(String s) {
        if (DEBUG_BACKUP_TRACE) {
            synchronized (mBackupTrace) {
                mBackupTrace.add(s);
            }
        }
    }

    void clearBackupTrace() {
        if (DEBUG_BACKUP_TRACE) {
            synchronized (mBackupTrace) {
                mBackupTrace.clear();
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
        mMountService = IMountService.Stub.asInterface(ServiceManager.getService("mount"));

        mBackupManagerBinder = asInterface(asBinder());

        // spin up the backup/restore handler thread
        mHandlerThread = new HandlerThread("backup", Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        mBackupHandler = new BackupHandler(mHandlerThread.getLooper());

        // Set up our bookkeeping
        final ContentResolver resolver = context.getContentResolver();
        boolean areEnabled = Settings.Secure.getInt(resolver,
                Settings.Secure.BACKUP_ENABLED, 0) != 0;
        mProvisioned = Settings.Global.getInt(resolver,
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        mAutoRestore = Settings.Secure.getInt(resolver,
                Settings.Secure.BACKUP_AUTO_RESTORE, 1) != 0;

        mProvisionedObserver = new ProvisionedObserver(mBackupHandler);
        resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                false, mProvisionedObserver);

        // If Encrypted file systems is enabled or disabled, this call will return the
        // correct directory.
        mBaseStateDir = new File(Environment.getSecureDataDirectory(), "backup");
        mBaseStateDir.mkdirs();
        mDataDir = Environment.getDownloadCacheDirectory();

        mPasswordHashFile = new File(mBaseStateDir, "pwhash");
        if (mPasswordHashFile.exists()) {
            FileInputStream fin = null;
            DataInputStream in = null;
            try {
                fin = new FileInputStream(mPasswordHashFile);
                in = new DataInputStream(new BufferedInputStream(fin));
                // integer length of the salt array, followed by the salt,
                // then the hex pw hash string
                int saltLen = in.readInt();
                byte[] salt = new byte[saltLen];
                in.readFully(salt);
                mPasswordHash = in.readUTF();
                mPasswordSalt = salt;
            } catch (IOException e) {
                Slog.e(TAG, "Unable to read saved backup pw hash");
            } finally {
                try {
                    if (in != null) in.close();
                    if (fin != null) fin.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Unable to close streams");
                }
            }
        }

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
                context.bindService(intent, mGoogleConnection, Context.BIND_AUTO_CREATE,
                        UserHandle.USER_OWNER);
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
                            if (!mBackupRunning) {
                                if (DEBUG) Slog.v(TAG, "Running a backup pass");

                                // Acquire the wakelock and pass it to the backup thread.  it will
                                // be released once backup concludes.
                                mBackupRunning = true;
                                mWakelock.acquire();

                                Message msg = mBackupHandler.obtainMessage(MSG_RUN_BACKUP);
                                mBackupHandler.sendMessage(msg);
                            } else {
                                Slog.i(TAG, "Backup time but one already running");
                            }
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
            tf.close();
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
                        if (MORE_DEBUG) Slog.v(TAG, "   + " + pkg);
                    } catch (NameNotFoundException e) {
                        // nope, this package was uninstalled; don't include it
                        if (MORE_DEBUG) Slog.v(TAG, "   - " + pkg);
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

    private SecretKey buildPasswordKey(String pw, byte[] salt, int rounds) {
        return buildCharArrayKey(pw.toCharArray(), salt, rounds);
    }

    private SecretKey buildCharArrayKey(char[] pwArray, byte[] salt, int rounds) {
        try {
            SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            KeySpec ks = new PBEKeySpec(pwArray, salt, rounds, PBKDF2_KEY_SIZE);
            return keyFactory.generateSecret(ks);
        } catch (InvalidKeySpecException e) {
            Slog.e(TAG, "Invalid key spec for PBKDF2!");
        } catch (NoSuchAlgorithmException e) {
            Slog.e(TAG, "PBKDF2 unavailable!");
        }
        return null;
    }

    private String buildPasswordHash(String pw, byte[] salt, int rounds) {
        SecretKey key = buildPasswordKey(pw, salt, rounds);
        if (key != null) {
            return byteArrayToHex(key.getEncoded());
        }
        return null;
    }

    private String byteArrayToHex(byte[] data) {
        StringBuilder buf = new StringBuilder(data.length * 2);
        for (int i = 0; i < data.length; i++) {
            buf.append(Byte.toHexString(data[i], true));
        }
        return buf.toString();
    }

    private byte[] hexToByteArray(String digits) {
        final int bytes = digits.length() / 2;
        if (2*bytes != digits.length()) {
            throw new IllegalArgumentException("Hex string must have an even number of digits");
        }

        byte[] result = new byte[bytes];
        for (int i = 0; i < digits.length(); i += 2) {
            result[i/2] = (byte) Integer.parseInt(digits.substring(i, i+2), 16);
        }
        return result;
    }

    private byte[] makeKeyChecksum(byte[] pwBytes, byte[] salt, int rounds) {
        char[] mkAsChar = new char[pwBytes.length];
        for (int i = 0; i < pwBytes.length; i++) {
            mkAsChar[i] = (char) pwBytes[i];
        }

        Key checksum = buildCharArrayKey(mkAsChar, salt, rounds);
        return checksum.getEncoded();
    }

    // Used for generating random salts or passwords
    private byte[] randomBytes(int bits) {
        byte[] array = new byte[bits / 8];
        mRng.nextBytes(array);
        return array;
    }

    // Backup password management
    boolean passwordMatchesSaved(String candidatePw, int rounds) {
        // First, on an encrypted device we require matching the device pw
        final boolean isEncrypted;
        try {
            isEncrypted = (mMountService.getEncryptionState() != MountService.ENCRYPTION_STATE_NONE);
            if (isEncrypted) {
                if (DEBUG) {
                    Slog.i(TAG, "Device encrypted; verifying against device data pw");
                }
                // 0 means the password validated
                // -2 means device not encrypted
                // Any other result is either password failure or an error condition,
                // so we refuse the match
                final int result = mMountService.verifyEncryptionPassword(candidatePw);
                if (result == 0) {
                    if (MORE_DEBUG) Slog.d(TAG, "Pw verifies");
                    return true;
                } else if (result != -2) {
                    if (MORE_DEBUG) Slog.d(TAG, "Pw mismatch");
                    return false;
                } else {
                    // ...else the device is supposedly not encrypted.  HOWEVER, the
                    // query about the encryption state said that the device *is*
                    // encrypted, so ... we may have a problem.  Log it and refuse
                    // the backup.
                    Slog.e(TAG, "verified encryption state mismatch against query; no match allowed");
                    return false;
                }
            }
        } catch (Exception e) {
            // Something went wrong talking to the mount service.  This is very bad;
            // assume that we fail password validation.
            return false;
        }

        if (mPasswordHash == null) {
            // no current password case -- require that 'currentPw' be null or empty
            if (candidatePw == null || "".equals(candidatePw)) {
                return true;
            } // else the non-empty candidate does not match the empty stored pw
        } else {
            // hash the stated current pw and compare to the stored one
            if (candidatePw != null && candidatePw.length() > 0) {
                String currentPwHash = buildPasswordHash(candidatePw, mPasswordSalt, rounds);
                if (mPasswordHash.equalsIgnoreCase(currentPwHash)) {
                    // candidate hash matches the stored hash -- the password matches
                    return true;
                }
            } // else the stored pw is nonempty but the candidate is empty; no match
        }
        return false;
    }

    @Override
    public boolean setBackupPassword(String currentPw, String newPw) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setBackupPassword");

        // If the supplied pw doesn't hash to the the saved one, fail
        if (!passwordMatchesSaved(currentPw, PBKDF2_HASH_ROUNDS)) {
            return false;
        }

        // Clearing the password is okay
        if (newPw == null || newPw.isEmpty()) {
            if (mPasswordHashFile.exists()) {
                if (!mPasswordHashFile.delete()) {
                    // Unable to delete the old pw file, so fail
                    Slog.e(TAG, "Unable to clear backup password");
                    return false;
                }
            }
            mPasswordHash = null;
            mPasswordSalt = null;
            return true;
        }

        try {
            // Okay, build the hash of the new backup password
            byte[] salt = randomBytes(PBKDF2_SALT_SIZE);
            String newPwHash = buildPasswordHash(newPw, salt, PBKDF2_HASH_ROUNDS);

            OutputStream pwf = null, buffer = null;
            DataOutputStream out = null;
            try {
                pwf = new FileOutputStream(mPasswordHashFile);
                buffer = new BufferedOutputStream(pwf);
                out = new DataOutputStream(buffer);
                // integer length of the salt array, followed by the salt,
                // then the hex pw hash string
                out.writeInt(salt.length);
                out.write(salt);
                out.writeUTF(newPwHash);
                out.flush();
                mPasswordHash = newPwHash;
                mPasswordSalt = salt;
                return true;
            } finally {
                if (out != null) out.close();
                if (buffer != null) buffer.close();
                if (pwf != null) pwf.close();
            }
        } catch (IOException e) {
            Slog.e(TAG, "Unable to set backup password");
        }
        return false;
    }

    @Override
    public boolean hasBackupPassword() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "hasBackupPassword");

        try {
            return (mMountService.getEncryptionState() != IMountService.ENCRYPTION_STATE_NONE)
                || (mPasswordHash != null && mPasswordHash.length() > 0);
        } catch (Exception e) {
            // If we can't talk to the mount service we have a serious problem; fail
            // "secure" i.e. assuming that we require a password
            return true;
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
        }

        // Enqueue a new backup of every participant
        synchronized (mBackupParticipants) {
            final int N = mBackupParticipants.size();
            for (int i=0; i<N; i++) {
                HashSet<String> participants = mBackupParticipants.valueAt(i);
                if (participants != null) {
                    for (String packageName : participants) {
                        dataChangedImpl(packageName);
                    }
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

            final int uid = extras.getInt(Intent.EXTRA_UID);
            if (added) {
                synchronized (mBackupParticipants) {
                    if (replacing) {
                        // This is the package-replaced case; we just remove the entry
                        // under the old uid and fall through to re-add.
                        removePackageParticipantsLocked(pkgList, uid);
                    }
                    addPackageParticipantsLocked(pkgList);
                }
            } else {
                if (replacing) {
                    // The package is being updated.  We'll receive a PACKAGE_ADDED shortly.
                } else {
                    synchronized (mBackupParticipants) {
                        removePackageParticipantsLocked(pkgList, uid);
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

    // Add the backup agents in the given packages to our set of known backup participants.
    // If 'packageNames' is null, adds all backup agents in the whole system.
    void addPackageParticipantsLocked(String[] packageNames) {
        // Look for apps that define the android:backupAgent attribute
        List<PackageInfo> targetApps = allAgentPackages();
        if (packageNames != null) {
            if (DEBUG) Slog.v(TAG, "addPackageParticipantsLocked: #" + packageNames.length);
            for (String packageName : packageNames) {
                addPackageParticipantsLockedInner(packageName, targetApps);
            }
        } else {
            if (DEBUG) Slog.v(TAG, "addPackageParticipantsLocked: all");
            addPackageParticipantsLockedInner(null, targetApps);
        }
    }

    private void addPackageParticipantsLockedInner(String packageName,
            List<PackageInfo> targetPkgs) {
        if (MORE_DEBUG) {
            Slog.v(TAG, "Examining " + packageName + " for backup agent");
        }

        for (PackageInfo pkg : targetPkgs) {
            if (packageName == null || pkg.packageName.equals(packageName)) {
                int uid = pkg.applicationInfo.uid;
                HashSet<String> set = mBackupParticipants.get(uid);
                if (set == null) {
                    set = new HashSet<String>();
                    mBackupParticipants.put(uid, set);
                }
                set.add(pkg.packageName);
                if (MORE_DEBUG) Slog.v(TAG, "Agent found; added");

                // Schedule a backup for it on general principles
                if (DEBUG) Slog.i(TAG, "Scheduling backup for new app " + pkg.packageName);
                dataChangedImpl(pkg.packageName);
            }
        }
    }

    // Remove the given packages' entries from our known active set.
    void removePackageParticipantsLocked(String[] packageNames, int oldUid) {
        if (packageNames == null) {
            Slog.w(TAG, "removePackageParticipants with null list");
            return;
        }

        if (DEBUG) Slog.v(TAG, "removePackageParticipantsLocked: uid=" + oldUid
                + " #" + packageNames.length);
        for (String pkg : packageNames) {
            // Known previous UID, so we know which package set to check
            HashSet<String> set = mBackupParticipants.get(oldUid);
            if (set != null && set.contains(pkg)) {
                removePackageFromSetLocked(set, pkg);
                if (set.isEmpty()) {
                    if (MORE_DEBUG) Slog.v(TAG, "  last one of this uid; purging set");
                    mBackupParticipants.remove(oldUid);
                }
            }
        }
    }

    private void removePackageFromSetLocked(final HashSet<String> set,
            final String packageName) {
        if (set.contains(packageName)) {
            // Found it.  Remove this one package from the bookkeeping, and
            // if it's the last participating app under this uid we drop the
            // (now-empty) set as well.
            // Note that we deliberately leave it 'known' in the "ever backed up"
            // bookkeeping so that its current-dataset data will be retrieved
            // if the app is subsequently reinstalled
            if (MORE_DEBUG) Slog.v(TAG, "  removing participant " + packageName);
            set.remove(packageName);
            mPendingBackups.remove(packageName);
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
        if (DEBUG) Slog.v(TAG, "Removing backed-up knowledge of " + packageName);
        if (MORE_DEBUG) Slog.v(TAG, "New set:");

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
                    if (MORE_DEBUG) Slog.v(TAG, "    " + s);
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
                    if (MORE_DEBUG) Slog.v(TAG, "   " + pkgName);
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
                    // only wait 10 seconds for the bind to happen
                    long timeoutMark = System.currentTimeMillis() + TIMEOUT_INTERVAL;
                    while (mConnecting && mConnectedAgent == null
                            && (System.currentTimeMillis() < timeoutMark)) {
                        try {
                            mAgentConnectLock.wait(5000);
                        } catch (InterruptedException e) {
                            // just bail
                            if (DEBUG) Slog.w(TAG, "Interrupted: " + e);
                            mActivityManager.clearPendingBackup();
                            return null;
                        }
                    }

                    // if we timed out with no connect, abort and move on
                    if (mConnecting == true) {
                        Slog.w(TAG, "Timeout waiting for agent " + app);
                        mActivityManager.clearPendingBackup();
                        return null;
                    }
                    if (DEBUG) Slog.i(TAG, "got agent " + mConnectedAgent);
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
                if (MORE_DEBUG) Slog.i(TAG, "allowClearUserData=false so not wiping "
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
                mActivityManager.clearApplicationUserData(packageName, observer, 0);
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
    // Interface and methods used by the asynchronous-with-timeout backup/restore operations

    interface BackupRestoreTask {
        // Execute one tick of whatever state machine the task implements
        void execute();

        // An operation that wanted a callback has completed
        void operationComplete();

        // An operation that wanted a callback has timed out
        void handleTimeout();
    }

    void prepareOperationTimeout(int token, long interval, BackupRestoreTask callback) {
        if (MORE_DEBUG) Slog.v(TAG, "starting timeout: token=" + Integer.toHexString(token)
                + " interval=" + interval);
        synchronized (mCurrentOpLock) {
            mCurrentOperations.put(token, new Operation(OP_PENDING, callback));

            Message msg = mBackupHandler.obtainMessage(MSG_TIMEOUT, token, 0, callback);
            mBackupHandler.sendMessageDelayed(msg, interval);
        }
    }

    // synchronous waiter case
    boolean waitUntilOperationComplete(int token) {
        if (MORE_DEBUG) Slog.i(TAG, "Blocking until operation complete for "
                + Integer.toHexString(token));
        int finalState = OP_PENDING;
        Operation op = null;
        synchronized (mCurrentOpLock) {
            while (true) {
                op = mCurrentOperations.get(token);
                if (op == null) {
                    // mysterious disappearance: treat as success with no callback
                    break;
                } else {
                    if (op.state == OP_PENDING) {
                        try {
                            mCurrentOpLock.wait();
                        } catch (InterruptedException e) {}
                        // When the wait is notified we loop around and recheck the current state
                    } else {
                        // No longer pending; we're done
                        finalState = op.state;
                        break;
                    }
                }
            }
        }

        mBackupHandler.removeMessages(MSG_TIMEOUT);
        if (MORE_DEBUG) Slog.v(TAG, "operation " + Integer.toHexString(token)
                + " complete: finalState=" + finalState);
        return finalState == OP_ACKNOWLEDGED;
    }

    void handleTimeout(int token, Object obj) {
        // Notify any synchronous waiters
        Operation op = null;
        synchronized (mCurrentOpLock) {
            op = mCurrentOperations.get(token);
            if (MORE_DEBUG) {
                if (op == null) Slog.w(TAG, "Timeout of token " + Integer.toHexString(token)
                        + " but no op found");
            }
            int state = (op != null) ? op.state : OP_TIMEOUT;
            if (state == OP_PENDING) {
                if (DEBUG) Slog.v(TAG, "TIMEOUT: token=" + Integer.toHexString(token));
                op.state = OP_TIMEOUT;
                mCurrentOperations.put(token, op);
            }
            mCurrentOpLock.notifyAll();
        }

        // If there's a TimeoutHandler for this event, call it
        if (op != null && op.callback != null) {
            op.callback.handleTimeout();
        }
    }

    // ----- Back up a set of applications via a worker thread -----

    enum BackupState {
        INITIAL,
        RUNNING_QUEUE,
        FINAL
    }

    class PerformBackupTask implements BackupRestoreTask {
        private static final String TAG = "PerformBackupTask";

        IBackupTransport mTransport;
        ArrayList<BackupRequest> mQueue;
        ArrayList<BackupRequest> mOriginalQueue;
        File mStateDir;
        File mJournal;
        BackupState mCurrentState;

        // carried information about the current in-flight operation
        PackageInfo mCurrentPackage;
        File mSavedStateName;
        File mBackupDataName;
        File mNewStateName;
        ParcelFileDescriptor mSavedState;
        ParcelFileDescriptor mBackupData;
        ParcelFileDescriptor mNewState;
        int mStatus;
        boolean mFinished;

        public PerformBackupTask(IBackupTransport transport, ArrayList<BackupRequest> queue,
                File journal) {
            mTransport = transport;
            mOriginalQueue = queue;
            mJournal = journal;

            try {
                mStateDir = new File(mBaseStateDir, transport.transportDirName());
            } catch (RemoteException e) {
                // can't happen; the transport is local
            }

            mCurrentState = BackupState.INITIAL;
            mFinished = false;

            addBackupTrace("STATE => INITIAL");
        }

        // Main entry point: perform one chunk of work, updating the state as appropriate
        // and reposting the next chunk to the primary backup handler thread.
        @Override
        public void execute() {
            switch (mCurrentState) {
                case INITIAL:
                    beginBackup();
                    break;

                case RUNNING_QUEUE:
                    invokeNextAgent();
                    break;

                case FINAL:
                    if (!mFinished) finalizeBackup();
                    else {
                        Slog.e(TAG, "Duplicate finish");
                    }
                    mFinished = true;
                    break;
            }
        }

        // We're starting a backup pass.  Initialize the transport and send
        // the PM metadata blob if we haven't already.
        void beginBackup() {
            if (DEBUG_BACKUP_TRACE) {
                clearBackupTrace();
                StringBuilder b = new StringBuilder(256);
                b.append("beginBackup: [");
                for (BackupRequest req : mOriginalQueue) {
                    b.append(' ');
                    b.append(req.packageName);
                }
                b.append(" ]");
                addBackupTrace(b.toString());
            }

            mStatus = BackupConstants.TRANSPORT_OK;

            // Sanity check: if the queue is empty we have no work to do.
            if (mOriginalQueue.isEmpty()) {
                Slog.w(TAG, "Backup begun with an empty queue - nothing to do.");
                addBackupTrace("queue empty at begin");
                executeNextState(BackupState.FINAL);
                return;
            }

            // We need to retain the original queue contents in case of transport
            // failure, but we want a working copy that we can manipulate along
            // the way.
            mQueue = (ArrayList<BackupRequest>) mOriginalQueue.clone();

            if (DEBUG) Slog.v(TAG, "Beginning backup of " + mQueue.size() + " targets");

            File pmState = new File(mStateDir, PACKAGE_MANAGER_SENTINEL);
            try {
                final String transportName = mTransport.transportDirName();
                EventLog.writeEvent(EventLogTags.BACKUP_START, transportName);

                // If we haven't stored package manager metadata yet, we must init the transport.
                if (mStatus == BackupConstants.TRANSPORT_OK && pmState.length() <= 0) {
                    Slog.i(TAG, "Initializing (wiping) backup state and transport storage");
                    addBackupTrace("initializing transport " + transportName);
                    resetBackupState(mStateDir);  // Just to make sure.
                    mStatus = mTransport.initializeDevice();

                    addBackupTrace("transport.initializeDevice() == " + mStatus);
                    if (mStatus == BackupConstants.TRANSPORT_OK) {
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
                if (mStatus == BackupConstants.TRANSPORT_OK) {
                    PackageManagerBackupAgent pmAgent = new PackageManagerBackupAgent(
                            mPackageManager, allAgentPackages());
                    mStatus = invokeAgentForBackup(PACKAGE_MANAGER_SENTINEL,
                            IBackupAgent.Stub.asInterface(pmAgent.onBind()), mTransport);
                    addBackupTrace("PMBA invoke: " + mStatus);
                }

                if (mStatus == BackupConstants.TRANSPORT_NOT_INITIALIZED) {
                    // The backend reports that our dataset has been wiped.  Note this in
                    // the event log; the no-success code below will reset the backup
                    // state as well.
                    EventLog.writeEvent(EventLogTags.BACKUP_RESET, mTransport.transportDirName());
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error in backup thread", e);
                addBackupTrace("Exception in backup thread: " + e);
                mStatus = BackupConstants.TRANSPORT_ERROR;
            } finally {
                // If we've succeeded so far, invokeAgentForBackup() will have run the PM
                // metadata and its completion/timeout callback will continue the state
                // machine chain.  If it failed that won't happen; we handle that now.
                addBackupTrace("exiting prelim: " + mStatus);
                if (mStatus != BackupConstants.TRANSPORT_OK) {
                    // if things went wrong at this point, we need to
                    // restage everything and try again later.
                    resetBackupState(mStateDir);  // Just to make sure.
                    executeNextState(BackupState.FINAL);
                }
            }
        }

        // Transport has been initialized and the PM metadata submitted successfully
        // if that was warranted.  Now we process the single next thing in the queue.
        void invokeNextAgent() {
            mStatus = BackupConstants.TRANSPORT_OK;
            addBackupTrace("invoke q=" + mQueue.size());

            // Sanity check that we have work to do.  If not, skip to the end where
            // we reestablish the wakelock invariants etc.
            if (mQueue.isEmpty()) {
                if (DEBUG) Slog.i(TAG, "queue now empty");
                executeNextState(BackupState.FINAL);
                return;
            }

            // pop the entry we're going to process on this step
            BackupRequest request = mQueue.get(0);
            mQueue.remove(0);

            Slog.d(TAG, "starting agent for backup of " + request);
            addBackupTrace("launch agent for " + request.packageName);

            // Verify that the requested app exists; it might be something that
            // requested a backup but was then uninstalled.  The request was
            // journalled and rather than tamper with the journal it's safer
            // to sanity-check here.  This also gives us the classname of the
            // package's backup agent.
            try {
                mCurrentPackage = mPackageManager.getPackageInfo(request.packageName,
                        PackageManager.GET_SIGNATURES);
                if (mCurrentPackage.applicationInfo.backupAgentName == null) {
                    // The manifest has changed but we had a stale backup request pending.
                    // This won't happen again because the app won't be requesting further
                    // backups.
                    Slog.i(TAG, "Package " + request.packageName
                            + " no longer supports backup; skipping");
                    addBackupTrace("skipping - no agent, completion is noop");
                    executeNextState(BackupState.RUNNING_QUEUE);
                    return;
                }

                IBackupAgent agent = null;
                try {
                    mWakelock.setWorkSource(new WorkSource(mCurrentPackage.applicationInfo.uid));
                    agent = bindToAgentSynchronous(mCurrentPackage.applicationInfo,
                            IApplicationThread.BACKUP_MODE_INCREMENTAL);
                    addBackupTrace("agent bound; a? = " + (agent != null));
                    if (agent != null) {
                        mStatus = invokeAgentForBackup(request.packageName, agent, mTransport);
                        // at this point we'll either get a completion callback from the
                        // agent, or a timeout message on the main handler.  either way, we're
                        // done here as long as we're successful so far.
                    } else {
                        // Timeout waiting for the agent
                        mStatus = BackupConstants.AGENT_ERROR;
                    }
                } catch (SecurityException ex) {
                    // Try for the next one.
                    Slog.d(TAG, "error in bind/backup", ex);
                    mStatus = BackupConstants.AGENT_ERROR;
                            addBackupTrace("agent SE");
                }
            } catch (NameNotFoundException e) {
                Slog.d(TAG, "Package does not exist; skipping");
                addBackupTrace("no such package");
                mStatus = BackupConstants.AGENT_UNKNOWN;
            } finally {
                mWakelock.setWorkSource(null);

                // If there was an agent error, no timeout/completion handling will occur.
                // That means we need to direct to the next state ourselves.
                if (mStatus != BackupConstants.TRANSPORT_OK) {
                    BackupState nextState = BackupState.RUNNING_QUEUE;

                    // An agent-level failure means we reenqueue this one agent for
                    // a later retry, but otherwise proceed normally.
                    if (mStatus == BackupConstants.AGENT_ERROR) {
                        if (MORE_DEBUG) Slog.i(TAG, "Agent failure for " + request.packageName
                                + " - restaging");
                        dataChangedImpl(request.packageName);
                        mStatus = BackupConstants.TRANSPORT_OK;
                        if (mQueue.isEmpty()) nextState = BackupState.FINAL;
                    } else if (mStatus == BackupConstants.AGENT_UNKNOWN) {
                        // Failed lookup of the app, so we couldn't bring up an agent, but
                        // we're otherwise fine.  Just drop it and go on to the next as usual.
                        mStatus = BackupConstants.TRANSPORT_OK;
                    } else {
                        // Transport-level failure means we reenqueue everything
                        revertAndEndBackup();
                        nextState = BackupState.FINAL;
                    }

                    executeNextState(nextState);
                } else {
                    addBackupTrace("expecting completion/timeout callback");
                }
            }
        }

        void finalizeBackup() {
            addBackupTrace("finishing");

            // Either backup was successful, in which case we of course do not need
            // this pass's journal any more; or it failed, in which case we just
            // re-enqueued all of these packages in the current active journal.
            // Either way, we no longer need this pass's journal.
            if (mJournal != null && !mJournal.delete()) {
                Slog.e(TAG, "Unable to remove backup journal file " + mJournal);
            }

            // If everything actually went through and this is the first time we've
            // done a backup, we can now record what the current backup dataset token
            // is.
            if ((mCurrentToken == 0) && (mStatus == BackupConstants.TRANSPORT_OK)) {
                addBackupTrace("success; recording token");
                try {
                    mCurrentToken = mTransport.getCurrentRestoreSet();
                } catch (RemoteException e) {} // can't happen
                writeRestoreTokens();
            }

            // Set up the next backup pass - at this point we can set mBackupRunning
            // to false to allow another pass to fire, because we're done with the
            // state machine sequence and the wakelock is refcounted.
            synchronized (mQueueLock) {
                mBackupRunning = false;
                if (mStatus == BackupConstants.TRANSPORT_NOT_INITIALIZED) {
                    // Make sure we back up everything and perform the one-time init
                    clearMetadata();
                    if (DEBUG) Slog.d(TAG, "Server requires init; rerunning");
                    addBackupTrace("init required; rerunning");
                    backupNow();
                }
            }

            // Only once we're entirely finished do we release the wakelock
            clearBackupTrace();
            Slog.i(TAG, "Backup pass finished.");
            mWakelock.release();
        }

        // Remove the PM metadata state. This will generate an init on the next pass.
        void clearMetadata() {
            final File pmState = new File(mStateDir, PACKAGE_MANAGER_SENTINEL);
            if (pmState.exists()) pmState.delete();
        }

        // Invoke an agent's doBackup() and start a timeout message spinning on the main
        // handler in case it doesn't get back to us.
        int invokeAgentForBackup(String packageName, IBackupAgent agent,
                IBackupTransport transport) {
            if (DEBUG) Slog.d(TAG, "invokeAgentForBackup on " + packageName);
            addBackupTrace("invoking " + packageName);

            mSavedStateName = new File(mStateDir, packageName);
            mBackupDataName = new File(mDataDir, packageName + ".data");
            mNewStateName = new File(mStateDir, packageName + ".new");

            mSavedState = null;
            mBackupData = null;
            mNewState = null;

            final int token = generateToken();
            try {
                // Look up the package info & signatures.  This is first so that if it
                // throws an exception, there's no file setup yet that would need to
                // be unraveled.
                if (packageName.equals(PACKAGE_MANAGER_SENTINEL)) {
                    // The metadata 'package' is synthetic; construct one and make
                    // sure our global state is pointed at it
                    mCurrentPackage = new PackageInfo();
                    mCurrentPackage.packageName = packageName;
                }

                // In a full backup, we pass a null ParcelFileDescriptor as
                // the saved-state "file". This is by definition an incremental,
                // so we build a saved state file to pass.
                mSavedState = ParcelFileDescriptor.open(mSavedStateName,
                        ParcelFileDescriptor.MODE_READ_ONLY |
                        ParcelFileDescriptor.MODE_CREATE);  // Make an empty file if necessary

                mBackupData = ParcelFileDescriptor.open(mBackupDataName,
                        ParcelFileDescriptor.MODE_READ_WRITE |
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE);

                mNewState = ParcelFileDescriptor.open(mNewStateName,
                        ParcelFileDescriptor.MODE_READ_WRITE |
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE);

                // Initiate the target's backup pass
                addBackupTrace("setting timeout");
                prepareOperationTimeout(token, TIMEOUT_BACKUP_INTERVAL, this);
                addBackupTrace("calling agent doBackup()");
                agent.doBackup(mSavedState, mBackupData, mNewState, token, mBackupManagerBinder);
            } catch (Exception e) {
                Slog.e(TAG, "Error invoking for backup on " + packageName);
                addBackupTrace("exception: " + e);
                EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, packageName,
                        e.toString());
                agentErrorCleanup();
                return BackupConstants.AGENT_ERROR;
            }

            // At this point the agent is off and running.  The next thing to happen will
            // either be a callback from the agent, at which point we'll process its data
            // for transport, or a timeout.  Either way the next phase will happen in
            // response to the TimeoutHandler interface callbacks.
            addBackupTrace("invoke success");
            return BackupConstants.TRANSPORT_OK;
        }

        @Override
        public void operationComplete() {
            // Okay, the agent successfully reported back to us.  Spin the data off to the
            // transport and proceed with the next stage.
            if (MORE_DEBUG) Slog.v(TAG, "operationComplete(): sending data to transport for "
                    + mCurrentPackage.packageName);
            mBackupHandler.removeMessages(MSG_TIMEOUT);
            clearAgentState();
            addBackupTrace("operation complete");

            ParcelFileDescriptor backupData = null;
            mStatus = BackupConstants.TRANSPORT_OK;
            try {
                int size = (int) mBackupDataName.length();
                if (size > 0) {
                    if (mStatus == BackupConstants.TRANSPORT_OK) {
                        backupData = ParcelFileDescriptor.open(mBackupDataName,
                                ParcelFileDescriptor.MODE_READ_ONLY);
                        addBackupTrace("sending data to transport");
                        mStatus = mTransport.performBackup(mCurrentPackage, backupData);
                    }

                    // TODO - We call finishBackup() for each application backed up, because
                    // we need to know now whether it succeeded or failed.  Instead, we should
                    // hold off on finishBackup() until the end, which implies holding off on
                    // renaming *all* the output state files (see below) until that happens.

                    addBackupTrace("data delivered: " + mStatus);
                    if (mStatus == BackupConstants.TRANSPORT_OK) {
                        addBackupTrace("finishing op on transport");
                        mStatus = mTransport.finishBackup();
                        addBackupTrace("finished: " + mStatus);
                    }
                } else {
                    if (DEBUG) Slog.i(TAG, "no backup data written; not calling transport");
                    addBackupTrace("no data to send");
                }

                // After successful transport, delete the now-stale data
                // and juggle the files so that next time we supply the agent
                // with the new state file it just created.
                if (mStatus == BackupConstants.TRANSPORT_OK) {
                    mBackupDataName.delete();
                    mNewStateName.renameTo(mSavedStateName);
                    EventLog.writeEvent(EventLogTags.BACKUP_PACKAGE,
                            mCurrentPackage.packageName, size);
                    logBackupComplete(mCurrentPackage.packageName);
                } else {
                    EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE,
                            mCurrentPackage.packageName);
                }
            } catch (Exception e) {
                Slog.e(TAG, "Transport error backing up " + mCurrentPackage.packageName, e);
                EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE,
                        mCurrentPackage.packageName);
                mStatus = BackupConstants.TRANSPORT_ERROR;
            } finally {
                try { if (backupData != null) backupData.close(); } catch (IOException e) {}
            }

            // If we encountered an error here it's a transport-level failure.  That
            // means we need to halt everything and reschedule everything for next time.
            final BackupState nextState;
            if (mStatus != BackupConstants.TRANSPORT_OK) {
                revertAndEndBackup();
                nextState = BackupState.FINAL;
            } else {
                // Success!  Proceed with the next app if any, otherwise we're done.
                nextState = (mQueue.isEmpty()) ? BackupState.FINAL : BackupState.RUNNING_QUEUE;
            }

            executeNextState(nextState);
        }

        @Override
        public void handleTimeout() {
            // Whoops, the current agent timed out running doBackup().  Tidy up and restage
            // it for the next time we run a backup pass.
            // !!! TODO: keep track of failure counts per agent, and blacklist those which
            // fail repeatedly (i.e. have proved themselves to be buggy).
            Slog.e(TAG, "Timeout backing up " + mCurrentPackage.packageName);
            EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, mCurrentPackage.packageName,
                    "timeout");
            addBackupTrace("timeout of " + mCurrentPackage.packageName);
            agentErrorCleanup();
            dataChangedImpl(mCurrentPackage.packageName);
        }

        void revertAndEndBackup() {
            if (MORE_DEBUG) Slog.i(TAG, "Reverting backup queue - restaging everything");
            addBackupTrace("transport error; reverting");
            for (BackupRequest request : mOriginalQueue) {
                dataChangedImpl(request.packageName);
            }
            // We also want to reset the backup schedule based on whatever
            // the transport suggests by way of retry/backoff time.
            restartBackupAlarm();
        }

        void agentErrorCleanup() {
            mBackupDataName.delete();
            mNewStateName.delete();
            clearAgentState();

            executeNextState(mQueue.isEmpty() ? BackupState.FINAL : BackupState.RUNNING_QUEUE);
        }

        // Cleanup common to both success and failure cases
        void clearAgentState() {
            try { if (mSavedState != null) mSavedState.close(); } catch (IOException e) {}
            try { if (mBackupData != null) mBackupData.close(); } catch (IOException e) {}
            try { if (mNewState != null) mNewState.close(); } catch (IOException e) {}
            mSavedState = mBackupData = mNewState = null;
            synchronized (mCurrentOpLock) {
                mCurrentOperations.clear();
            }

            // If this was a pseudopackage there's no associated Activity Manager state
            if (mCurrentPackage.applicationInfo != null) {
                addBackupTrace("unbinding " + mCurrentPackage.packageName);
                try {  // unbind even on timeout, just in case
                    mActivityManager.unbindBackupAgent(mCurrentPackage.applicationInfo);
                } catch (RemoteException e) {}
            }
        }

        void restartBackupAlarm() {
            addBackupTrace("setting backup trigger");
            synchronized (mQueueLock) {
                try {
                    startBackupAlarmsLocked(mTransport.requestBackupTime());
                } catch (RemoteException e) { /* cannot happen */ }
            }
        }

        void executeNextState(BackupState nextState) {
            if (MORE_DEBUG) Slog.i(TAG, " => executing next step on "
                    + this + " nextState=" + nextState);
            addBackupTrace("executeNextState => " + nextState);
            mCurrentState = nextState;
            Message msg = mBackupHandler.obtainMessage(MSG_BACKUP_RESTORE_STEP, this);
            mBackupHandler.sendMessage(msg);
        }
    }


    // ----- Full backup to a file/socket -----

    class PerformFullBackupTask implements Runnable {
        ParcelFileDescriptor mOutputFile;
        DeflaterOutputStream mDeflater;
        IFullBackupRestoreObserver mObserver;
        boolean mIncludeApks;
        boolean mIncludeShared;
        boolean mAllApps;
        final boolean mIncludeSystem;
        String[] mPackages;
        String mCurrentPassword;
        String mEncryptPassword;
        AtomicBoolean mLatchObject;
        File mFilesDir;
        File mManifestFile;

        class FullBackupRunner implements Runnable {
            PackageInfo mPackage;
            IBackupAgent mAgent;
            ParcelFileDescriptor mPipe;
            int mToken;
            boolean mSendApk;
            boolean mWriteManifest;

            FullBackupRunner(PackageInfo pack, IBackupAgent agent, ParcelFileDescriptor pipe,
                    int token, boolean sendApk, boolean writeManifest)  throws IOException {
                mPackage = pack;
                mAgent = agent;
                mPipe = ParcelFileDescriptor.dup(pipe.getFileDescriptor());
                mToken = token;
                mSendApk = sendApk;
                mWriteManifest = writeManifest;
            }

            @Override
            public void run() {
                try {
                    BackupDataOutput output = new BackupDataOutput(
                            mPipe.getFileDescriptor());

                    if (mWriteManifest) {
                        if (MORE_DEBUG) Slog.d(TAG, "Writing manifest for " + mPackage.packageName);
                        writeAppManifest(mPackage, mManifestFile, mSendApk);
                        FullBackup.backupToTar(mPackage.packageName, null, null,
                                mFilesDir.getAbsolutePath(),
                                mManifestFile.getAbsolutePath(),
                                output);
                    }

                    if (mSendApk) {
                        writeApkToBackup(mPackage, output);
                    }

                    if (DEBUG) Slog.d(TAG, "Calling doFullBackup() on " + mPackage.packageName);
                    prepareOperationTimeout(mToken, TIMEOUT_FULL_BACKUP_INTERVAL, null);
                    mAgent.doFullBackup(mPipe, mToken, mBackupManagerBinder);
                } catch (IOException e) {
                    Slog.e(TAG, "Error running full backup for " + mPackage.packageName);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote agent vanished during full backup of "
                            + mPackage.packageName);
                } finally {
                    try {
                        mPipe.close();
                    } catch (IOException e) {}
                }
            }
        }

        PerformFullBackupTask(ParcelFileDescriptor fd, IFullBackupRestoreObserver observer, 
                boolean includeApks, boolean includeShared, String curPassword,
                String encryptPassword, boolean doAllApps, boolean doSystem, String[] packages,
                AtomicBoolean latch) {
            mOutputFile = fd;
            mObserver = observer;
            mIncludeApks = includeApks;
            mIncludeShared = includeShared;
            mAllApps = doAllApps;
            mIncludeSystem = doSystem;
            mPackages = packages;
            mCurrentPassword = curPassword;
            // when backing up, if there is a current backup password, we require that
            // the user use a nonempty encryption password as well.  if one is supplied
            // in the UI we use that, but if the UI was left empty we fall back to the
            // current backup password (which was supplied by the user as well).
            if (encryptPassword == null || "".equals(encryptPassword)) {
                mEncryptPassword = curPassword;
            } else {
                mEncryptPassword = encryptPassword;
            }
            mLatchObject = latch;

            mFilesDir = new File("/data/system");
            mManifestFile = new File(mFilesDir, BACKUP_MANIFEST_FILENAME);
        }

        @Override
        public void run() {
            List<PackageInfo> packagesToBackup = new ArrayList<PackageInfo>();

            Slog.i(TAG, "--- Performing full-dataset backup ---");
            sendStartBackup();

            // doAllApps supersedes the package set if any
            if (mAllApps) {
                packagesToBackup = mPackageManager.getInstalledPackages(
                        PackageManager.GET_SIGNATURES);
                // Exclude system apps if we've been asked to do so
                if (mIncludeSystem == false) {
                    for (int i = 0; i < packagesToBackup.size(); ) {
                        PackageInfo pkg = packagesToBackup.get(i);
                        if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                            packagesToBackup.remove(i);
                        } else {
                            i++;
                        }
                    }
                }
            }

            // Now process the command line argument packages, if any. Note that explicitly-
            // named system-partition packages will be included even if includeSystem was
            // set to false.
            if (mPackages != null) {
                for (String pkgName : mPackages) {
                    try {
                        packagesToBackup.add(mPackageManager.getPackageInfo(pkgName,
                                PackageManager.GET_SIGNATURES));
                    } catch (NameNotFoundException e) {
                        Slog.w(TAG, "Unknown package " + pkgName + ", skipping");
                    }
                }
            }

            // Cull any packages that have indicated that backups are not permitted, as well
            // as any explicit mention of the 'special' shared-storage agent package (we
            // handle that one at the end).
            for (int i = 0; i < packagesToBackup.size(); ) {
                PackageInfo pkg = packagesToBackup.get(i);
                if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) == 0
                        || pkg.packageName.equals(SHARED_BACKUP_AGENT_PACKAGE)) {
                    packagesToBackup.remove(i);
                } else {
                    i++;
                }
            }

            // Cull any packages that run as system-domain uids but do not define their
            // own backup agents
            for (int i = 0; i < packagesToBackup.size(); ) {
                PackageInfo pkg = packagesToBackup.get(i);
                if ((pkg.applicationInfo.uid < Process.FIRST_APPLICATION_UID)
                        && (pkg.applicationInfo.backupAgentName == null)) {
                    if (MORE_DEBUG) {
                        Slog.i(TAG, "... ignoring non-agent system package " + pkg.packageName);
                    }
                    packagesToBackup.remove(i);
                } else {
                    i++;
                }
            }

            FileOutputStream ofstream = new FileOutputStream(mOutputFile.getFileDescriptor());
            OutputStream out = null;

            PackageInfo pkg = null;
            try {
                boolean encrypting = (mEncryptPassword != null && mEncryptPassword.length() > 0);
                boolean compressing = COMPRESS_FULL_BACKUPS;
                OutputStream finalOutput = ofstream;

                // Verify that the given password matches the currently-active
                // backup password, if any
                if (hasBackupPassword()) {
                    if (!passwordMatchesSaved(mCurrentPassword, PBKDF2_HASH_ROUNDS)) {
                        if (DEBUG) Slog.w(TAG, "Backup password mismatch; aborting");
                        return;
                    }
                }

                // Write the global file header.  All strings are UTF-8 encoded; lines end
                // with a '\n' byte.  Actual backup data begins immediately following the
                // final '\n'.
                //
                // line 1: "ANDROID BACKUP"
                // line 2: backup file format version, currently "1"
                // line 3: compressed?  "0" if not compressed, "1" if compressed.
                // line 4: name of encryption algorithm [currently only "none" or "AES-256"]
                //
                // When line 4 is not "none", then additional header data follows:
                //
                // line 5: user password salt [hex]
                // line 6: master key checksum salt [hex]
                // line 7: number of PBKDF2 rounds to use (same for user & master) [decimal]
                // line 8: IV of the user key [hex]
                // line 9: master key blob [hex]
                //     IV of the master key, master key itself, master key checksum hash
                //
                // The master key checksum is the master key plus its checksum salt, run through
                // 10k rounds of PBKDF2.  This is used to verify that the user has supplied the
                // correct password for decrypting the archive:  the master key decrypted from
                // the archive using the user-supplied password is also run through PBKDF2 in
                // this way, and if the result does not match the checksum as stored in the
                // archive, then we know that the user-supplied password does not match the
                // archive's.
                StringBuilder headerbuf = new StringBuilder(1024);

                headerbuf.append(BACKUP_FILE_HEADER_MAGIC);
                headerbuf.append(BACKUP_FILE_VERSION); // integer, no trailing \n
                headerbuf.append(compressing ? "\n1\n" : "\n0\n");

                try {
                    // Set up the encryption stage if appropriate, and emit the correct header
                    if (encrypting) {
                        finalOutput = emitAesBackupHeader(headerbuf, finalOutput);
                    } else {
                        headerbuf.append("none\n");
                    }

                    byte[] header = headerbuf.toString().getBytes("UTF-8");
                    ofstream.write(header);

                    // Set up the compression stage feeding into the encryption stage (if any)
                    if (compressing) {
                        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
                        finalOutput = new DeflaterOutputStream(finalOutput, deflater, true);
                    }

                    out = finalOutput;
                } catch (Exception e) {
                    // Should never happen!
                    Slog.e(TAG, "Unable to emit archive header", e);
                    return;
                }

                // Shared storage if requested
                if (mIncludeShared) {
                    try {
                        pkg = mPackageManager.getPackageInfo(SHARED_BACKUP_AGENT_PACKAGE, 0);
                        packagesToBackup.add(pkg);
                    } catch (NameNotFoundException e) {
                        Slog.e(TAG, "Unable to find shared-storage backup handler");
                    }
                }

                // Now back up the app data via the agent mechanism
                int N = packagesToBackup.size();
                for (int i = 0; i < N; i++) {
                    pkg = packagesToBackup.get(i);
                    backupOnePackage(pkg, out);
                }

                // Done!
                finalizeBackup(out);
            } catch (RemoteException e) {
                Slog.e(TAG, "App died during full backup");
            } catch (Exception e) {
                Slog.e(TAG, "Internal exception during full backup", e);
            } finally {
                tearDown(pkg);
                try {
                    if (out != null) out.close();
                    mOutputFile.close();
                } catch (IOException e) {
                    /* nothing we can do about this */
                }
                synchronized (mCurrentOpLock) {
                    mCurrentOperations.clear();
                }
                synchronized (mLatchObject) {
                    mLatchObject.set(true);
                    mLatchObject.notifyAll();
                }
                sendEndBackup();
                if (DEBUG) Slog.d(TAG, "Full backup pass complete.");
                mWakelock.release();
            }
        }

        private OutputStream emitAesBackupHeader(StringBuilder headerbuf,
                OutputStream ofstream) throws Exception {
            // User key will be used to encrypt the master key.
            byte[] newUserSalt = randomBytes(PBKDF2_SALT_SIZE);
            SecretKey userKey = buildPasswordKey(mEncryptPassword, newUserSalt,
                    PBKDF2_HASH_ROUNDS);

            // the master key is random for each backup
            byte[] masterPw = new byte[256 / 8];
            mRng.nextBytes(masterPw);
            byte[] checksumSalt = randomBytes(PBKDF2_SALT_SIZE);

            // primary encryption of the datastream with the random key
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec masterKeySpec = new SecretKeySpec(masterPw, "AES");
            c.init(Cipher.ENCRYPT_MODE, masterKeySpec);
            OutputStream finalOutput = new CipherOutputStream(ofstream, c);

            // line 4: name of encryption algorithm
            headerbuf.append(ENCRYPTION_ALGORITHM_NAME);
            headerbuf.append('\n');
            // line 5: user password salt [hex]
            headerbuf.append(byteArrayToHex(newUserSalt));
            headerbuf.append('\n');
            // line 6: master key checksum salt [hex]
            headerbuf.append(byteArrayToHex(checksumSalt));
            headerbuf.append('\n');
            // line 7: number of PBKDF2 rounds used [decimal]
            headerbuf.append(PBKDF2_HASH_ROUNDS);
            headerbuf.append('\n');

            // line 8: IV of the user key [hex]
            Cipher mkC = Cipher.getInstance("AES/CBC/PKCS5Padding");
            mkC.init(Cipher.ENCRYPT_MODE, userKey);

            byte[] IV = mkC.getIV();
            headerbuf.append(byteArrayToHex(IV));
            headerbuf.append('\n');

            // line 9: master IV + key blob, encrypted by the user key [hex].  Blob format:
            //    [byte] IV length = Niv
            //    [array of Niv bytes] IV itself
            //    [byte] master key length = Nmk
            //    [array of Nmk bytes] master key itself
            //    [byte] MK checksum hash length = Nck
            //    [array of Nck bytes] master key checksum hash
            //
            // The checksum is the (master key + checksum salt), run through the
            // stated number of PBKDF2 rounds
            IV = c.getIV();
            byte[] mk = masterKeySpec.getEncoded();
            byte[] checksum = makeKeyChecksum(masterKeySpec.getEncoded(),
                    checksumSalt, PBKDF2_HASH_ROUNDS);

            ByteArrayOutputStream blob = new ByteArrayOutputStream(IV.length + mk.length
                    + checksum.length + 3);
            DataOutputStream mkOut = new DataOutputStream(blob);
            mkOut.writeByte(IV.length);
            mkOut.write(IV);
            mkOut.writeByte(mk.length);
            mkOut.write(mk);
            mkOut.writeByte(checksum.length);
            mkOut.write(checksum);
            mkOut.flush();
            byte[] encryptedMk = mkC.doFinal(blob.toByteArray());
            headerbuf.append(byteArrayToHex(encryptedMk));
            headerbuf.append('\n');

            return finalOutput;
        }

        private void backupOnePackage(PackageInfo pkg, OutputStream out)
                throws RemoteException {
            Slog.d(TAG, "Binding to full backup agent : " + pkg.packageName);

            IBackupAgent agent = bindToAgentSynchronous(pkg.applicationInfo,
                    IApplicationThread.BACKUP_MODE_FULL);
            if (agent != null) {
                ParcelFileDescriptor[] pipes = null;
                try {
                    pipes = ParcelFileDescriptor.createPipe();

                    ApplicationInfo app = pkg.applicationInfo;
                    final boolean isSharedStorage = pkg.packageName.equals(SHARED_BACKUP_AGENT_PACKAGE);
                    final boolean sendApk = mIncludeApks
                            && !isSharedStorage
                            && ((app.flags & ApplicationInfo.FLAG_FORWARD_LOCK) == 0)
                            && ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 ||
                                (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);

                    sendOnBackupPackage(isSharedStorage ? "Shared storage" : pkg.packageName);

                    final int token = generateToken();
                    FullBackupRunner runner = new FullBackupRunner(pkg, agent, pipes[1],
                            token, sendApk, !isSharedStorage);
                    pipes[1].close();   // the runner has dup'd it
                    pipes[1] = null;
                    Thread t = new Thread(runner);
                    t.start();

                    // Now pull data from the app and stuff it into the compressor
                    try {
                        FileInputStream raw = new FileInputStream(pipes[0].getFileDescriptor());
                        DataInputStream in = new DataInputStream(raw);

                        byte[] buffer = new byte[16 * 1024];
                        int chunkTotal;
                        while ((chunkTotal = in.readInt()) > 0) {
                            while (chunkTotal > 0) {
                                int toRead = (chunkTotal > buffer.length)
                                        ? buffer.length : chunkTotal;
                                int nRead = in.read(buffer, 0, toRead);
                                out.write(buffer, 0, nRead);
                                chunkTotal -= nRead;
                            }
                        }
                    } catch (IOException e) {
                        Slog.i(TAG, "Caught exception reading from agent", e);
                    }

                    if (!waitUntilOperationComplete(token)) {
                        Slog.e(TAG, "Full backup failed on package " + pkg.packageName);
                    } else {
                        if (DEBUG) Slog.d(TAG, "Full package backup success: " + pkg.packageName);
                    }

                } catch (IOException e) {
                    Slog.e(TAG, "Error backing up " + pkg.packageName, e);
                } finally {
                    try {
                        // flush after every package
                        out.flush();
                        if (pipes != null) {
                            if (pipes[0] != null) pipes[0].close();
                            if (pipes[1] != null) pipes[1].close();
                        }
                    } catch (IOException e) {
                        Slog.w(TAG, "Error bringing down backup stack");
                    }
                }
            } else {
                Slog.w(TAG, "Unable to bind to full agent for " + pkg.packageName);
            }
            tearDown(pkg);
        }

        private void writeApkToBackup(PackageInfo pkg, BackupDataOutput output) {
            // Forward-locked apps, system-bundled .apks, etc are filtered out before we get here
            final String appSourceDir = pkg.applicationInfo.sourceDir;
            final String apkDir = new File(appSourceDir).getParent();
            FullBackup.backupToTar(pkg.packageName, FullBackup.APK_TREE_TOKEN, null,
                    apkDir, appSourceDir, output);

            // TODO: migrate this to SharedStorageBackup, since AID_SYSTEM
            // doesn't have access to external storage.

            // Save associated .obb content if it exists and we did save the apk
            // check for .obb and save those too
            final UserEnvironment userEnv = new UserEnvironment(UserHandle.USER_OWNER);
            final File obbDir = userEnv.getExternalStorageAppObbDirectory(pkg.packageName);
            if (obbDir != null) {
                if (MORE_DEBUG) Log.i(TAG, "obb dir: " + obbDir.getAbsolutePath());
                File[] obbFiles = obbDir.listFiles();
                if (obbFiles != null) {
                    final String obbDirName = obbDir.getAbsolutePath();
                    for (File obb : obbFiles) {
                        FullBackup.backupToTar(pkg.packageName, FullBackup.OBB_TREE_TOKEN, null,
                                obbDirName, obb.getAbsolutePath(), output);
                    }
                }
            }
        }

        private void finalizeBackup(OutputStream out) {
            try {
                // A standard 'tar' EOF sequence: two 512-byte blocks of all zeroes.
                byte[] eof = new byte[512 * 2]; // newly allocated == zero filled
                out.write(eof);
            } catch (IOException e) {
                Slog.w(TAG, "Error attempting to finalize backup stream");
            }
        }

        private void writeAppManifest(PackageInfo pkg, File manifestFile, boolean withApk)
                throws IOException {
            // Manifest format. All data are strings ending in LF:
            //     BACKUP_MANIFEST_VERSION, currently 1
            //
            // Version 1:
            //     package name
            //     package's versionCode
            //     platform versionCode
            //     getInstallerPackageName() for this package (maybe empty)
            //     boolean: "1" if archive includes .apk; any other string means not
            //     number of signatures == N
            // N*:    signature byte array in ascii format per Signature.toCharsString()
            StringBuilder builder = new StringBuilder(4096);
            StringBuilderPrinter printer = new StringBuilderPrinter(builder);

            printer.println(Integer.toString(BACKUP_MANIFEST_VERSION));
            printer.println(pkg.packageName);
            printer.println(Integer.toString(pkg.versionCode));
            printer.println(Integer.toString(Build.VERSION.SDK_INT));

            String installerName = mPackageManager.getInstallerPackageName(pkg.packageName);
            printer.println((installerName != null) ? installerName : "");

            printer.println(withApk ? "1" : "0");
            if (pkg.signatures == null) {
                printer.println("0");
            } else {
                printer.println(Integer.toString(pkg.signatures.length));
                for (Signature sig : pkg.signatures) {
                    printer.println(sig.toCharsString());
                }
            }

            FileOutputStream outstream = new FileOutputStream(manifestFile);
            outstream.write(builder.toString().getBytes());
            outstream.close();
        }

        private void tearDown(PackageInfo pkg) {
            if (pkg != null) {
                final ApplicationInfo app = pkg.applicationInfo;
                if (app != null) {
                    try {
                        // unbind and tidy up even on timeout or failure, just in case
                        mActivityManager.unbindBackupAgent(app);

                        // The agent was running with a stub Application object, so shut it down.
                        if (app.uid != Process.SYSTEM_UID
                                && app.uid != Process.PHONE_UID) {
                            if (MORE_DEBUG) Slog.d(TAG, "Backup complete, killing host process");
                            mActivityManager.killApplicationProcess(app.processName, app.uid);
                        } else {
                            if (MORE_DEBUG) Slog.d(TAG, "Not killing after restore: " + app.processName);
                        }
                    } catch (RemoteException e) {
                        Slog.d(TAG, "Lost app trying to shut down");
                    }
                }
            }
        }

        // wrappers for observer use
        void sendStartBackup() {
            if (mObserver != null) {
                try {
                    mObserver.onStartBackup();
                } catch (RemoteException e) {
                    Slog.w(TAG, "full backup observer went away: startBackup");
                    mObserver = null;
                }
            }
        }

        void sendOnBackupPackage(String name) {
            if (mObserver != null) {
                try {
                    // TODO: use a more user-friendly name string
                    mObserver.onBackupPackage(name);
                } catch (RemoteException e) {
                    Slog.w(TAG, "full backup observer went away: backupPackage");
                    mObserver = null;
                }
            }
        }

        void sendEndBackup() {
            if (mObserver != null) {
                try {
                    mObserver.onEndBackup();
                } catch (RemoteException e) {
                    Slog.w(TAG, "full backup observer went away: endBackup");
                    mObserver = null;
                }
            }
        }
    }


    // ----- Full restore from a file/socket -----

    // Description of a file in the restore datastream
    static class FileMetadata {
        String packageName;             // name of the owning app
        String installerPackageName;    // name of the market-type app that installed the owner
        int type;                       // e.g. BackupAgent.TYPE_DIRECTORY
        String domain;                  // e.g. FullBackup.DATABASE_TREE_TOKEN
        String path;                    // subpath within the semantic domain
        long mode;                      // e.g. 0666 (actually int)
        long mtime;                     // last mod time, UTC time_t (actually int)
        long size;                      // bytes of content

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("FileMetadata{");
            sb.append(packageName); sb.append(',');
            sb.append(type); sb.append(',');
            sb.append(domain); sb.append(':'); sb.append(path); sb.append(',');
            sb.append(size);
            sb.append('}');
            return sb.toString();
        }
    }

    enum RestorePolicy {
        IGNORE,
        ACCEPT,
        ACCEPT_IF_APK
    }

    class PerformFullRestoreTask implements Runnable {
        ParcelFileDescriptor mInputFile;
        String mCurrentPassword;
        String mDecryptPassword;
        IFullBackupRestoreObserver mObserver;
        AtomicBoolean mLatchObject;
        IBackupAgent mAgent;
        String mAgentPackage;
        ApplicationInfo mTargetApp;
        ParcelFileDescriptor[] mPipes = null;

        long mBytes;

        // possible handling states for a given package in the restore dataset
        final HashMap<String, RestorePolicy> mPackagePolicies
                = new HashMap<String, RestorePolicy>();

        // installer package names for each encountered app, derived from the manifests
        final HashMap<String, String> mPackageInstallers = new HashMap<String, String>();

        // Signatures for a given package found in its manifest file
        final HashMap<String, Signature[]> mManifestSignatures
                = new HashMap<String, Signature[]>();

        // Packages we've already wiped data on when restoring their first file
        final HashSet<String> mClearedPackages = new HashSet<String>();

        PerformFullRestoreTask(ParcelFileDescriptor fd, String curPassword, String decryptPassword,
                IFullBackupRestoreObserver observer, AtomicBoolean latch) {
            mInputFile = fd;
            mCurrentPassword = curPassword;
            mDecryptPassword = decryptPassword;
            mObserver = observer;
            mLatchObject = latch;
            mAgent = null;
            mAgentPackage = null;
            mTargetApp = null;

            // Which packages we've already wiped data on.  We prepopulate this
            // with a whitelist of packages known to be unclearable.
            mClearedPackages.add("android");
            mClearedPackages.add("com.android.providers.settings");

        }

        class RestoreFileRunnable implements Runnable {
            IBackupAgent mAgent;
            FileMetadata mInfo;
            ParcelFileDescriptor mSocket;
            int mToken;

            RestoreFileRunnable(IBackupAgent agent, FileMetadata info,
                    ParcelFileDescriptor socket, int token) throws IOException {
                mAgent = agent;
                mInfo = info;
                mToken = token;

                // This class is used strictly for process-local binder invocations.  The
                // semantics of ParcelFileDescriptor differ in this case; in particular, we
                // do not automatically get a 'dup'ed descriptor that we can can continue
                // to use asynchronously from the caller.  So, we make sure to dup it ourselves
                // before proceeding to do the restore.
                mSocket = ParcelFileDescriptor.dup(socket.getFileDescriptor());
            }

            @Override
            public void run() {
                try {
                    mAgent.doRestoreFile(mSocket, mInfo.size, mInfo.type,
                            mInfo.domain, mInfo.path, mInfo.mode, mInfo.mtime,
                            mToken, mBackupManagerBinder);
                } catch (RemoteException e) {
                    // never happens; this is used strictly for local binder calls
                }
            }
        }

        @Override
        public void run() {
            Slog.i(TAG, "--- Performing full-dataset restore ---");
            sendStartRestore();

            // Are we able to restore shared-storage data?
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                mPackagePolicies.put(SHARED_BACKUP_AGENT_PACKAGE, RestorePolicy.ACCEPT);
            }

            FileInputStream rawInStream = null;
            DataInputStream rawDataIn = null;
            try {
                if (hasBackupPassword()) {
                    if (!passwordMatchesSaved(mCurrentPassword, PBKDF2_HASH_ROUNDS)) {
                        if (DEBUG) Slog.w(TAG, "Backup password mismatch; aborting");
                        return;
                    }
                }

                mBytes = 0;
                byte[] buffer = new byte[32 * 1024];
                rawInStream = new FileInputStream(mInputFile.getFileDescriptor());
                rawDataIn = new DataInputStream(rawInStream);

                // First, parse out the unencrypted/uncompressed header
                boolean compressed = false;
                InputStream preCompressStream = rawInStream;
                final InputStream in;

                boolean okay = false;
                final int headerLen = BACKUP_FILE_HEADER_MAGIC.length();
                byte[] streamHeader = new byte[headerLen];
                rawDataIn.readFully(streamHeader);
                byte[] magicBytes = BACKUP_FILE_HEADER_MAGIC.getBytes("UTF-8");
                if (Arrays.equals(magicBytes, streamHeader)) {
                    // okay, header looks good.  now parse out the rest of the fields.
                    String s = readHeaderLine(rawInStream);
                    if (Integer.parseInt(s) == BACKUP_FILE_VERSION) {
                        // okay, it's a version we recognize
                        s = readHeaderLine(rawInStream);
                        compressed = (Integer.parseInt(s) != 0);
                        s = readHeaderLine(rawInStream);
                        if (s.equals("none")) {
                            // no more header to parse; we're good to go
                            okay = true;
                        } else if (mDecryptPassword != null && mDecryptPassword.length() > 0) {
                            preCompressStream = decodeAesHeaderAndInitialize(s, rawInStream);
                            if (preCompressStream != null) {
                                okay = true;
                            }
                        } else Slog.w(TAG, "Archive is encrypted but no password given");
                    } else Slog.w(TAG, "Wrong header version: " + s);
                } else Slog.w(TAG, "Didn't read the right header magic");

                if (!okay) {
                    Slog.w(TAG, "Invalid restore data; aborting.");
                    return;
                }

                // okay, use the right stream layer based on compression
                in = (compressed) ? new InflaterInputStream(preCompressStream) : preCompressStream;

                boolean didRestore;
                do {
                    didRestore = restoreOneFile(in, buffer);
                } while (didRestore);

                if (MORE_DEBUG) Slog.v(TAG, "Done consuming input tarfile, total bytes=" + mBytes);
            } catch (IOException e) {
                Slog.e(TAG, "Unable to read restore input");
            } finally {
                tearDownPipes();
                tearDownAgent(mTargetApp);

                try {
                    if (rawDataIn != null) rawDataIn.close();
                    if (rawInStream != null) rawInStream.close();
                    mInputFile.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Close of restore data pipe threw", e);
                    /* nothing we can do about this */
                }
                synchronized (mCurrentOpLock) {
                    mCurrentOperations.clear();
                }
                synchronized (mLatchObject) {
                    mLatchObject.set(true);
                    mLatchObject.notifyAll();
                }
                sendEndRestore();
                Slog.d(TAG, "Full restore pass complete.");
                mWakelock.release();
            }
        }

        String readHeaderLine(InputStream in) throws IOException {
            int c;
            StringBuilder buffer = new StringBuilder(80);
            while ((c = in.read()) >= 0) {
                if (c == '\n') break;   // consume and discard the newlines
                buffer.append((char)c);
            }
            return buffer.toString();
        }

        InputStream decodeAesHeaderAndInitialize(String encryptionName, InputStream rawInStream) {
            InputStream result = null;
            try {
                if (encryptionName.equals(ENCRYPTION_ALGORITHM_NAME)) {

                    String userSaltHex = readHeaderLine(rawInStream); // 5
                    byte[] userSalt = hexToByteArray(userSaltHex);

                    String ckSaltHex = readHeaderLine(rawInStream); // 6
                    byte[] ckSalt = hexToByteArray(ckSaltHex);

                    int rounds = Integer.parseInt(readHeaderLine(rawInStream)); // 7
                    String userIvHex = readHeaderLine(rawInStream); // 8

                    String masterKeyBlobHex = readHeaderLine(rawInStream); // 9

                    // decrypt the master key blob
                    Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    SecretKey userKey = buildPasswordKey(mDecryptPassword, userSalt,
                            rounds);
                    byte[] IV = hexToByteArray(userIvHex);
                    IvParameterSpec ivSpec = new IvParameterSpec(IV);
                    c.init(Cipher.DECRYPT_MODE,
                            new SecretKeySpec(userKey.getEncoded(), "AES"),
                            ivSpec);
                    byte[] mkCipher = hexToByteArray(masterKeyBlobHex);
                    byte[] mkBlob = c.doFinal(mkCipher);

                    // first, the master key IV
                    int offset = 0;
                    int len = mkBlob[offset++];
                    IV = Arrays.copyOfRange(mkBlob, offset, offset + len);
                    offset += len;
                    // then the master key itself
                    len = mkBlob[offset++];
                    byte[] mk = Arrays.copyOfRange(mkBlob,
                            offset, offset + len);
                    offset += len;
                    // and finally the master key checksum hash
                    len = mkBlob[offset++];
                    byte[] mkChecksum = Arrays.copyOfRange(mkBlob,
                            offset, offset + len);

                    // now validate the decrypted master key against the checksum
                    byte[] calculatedCk = makeKeyChecksum(mk, ckSalt, rounds);
                    if (Arrays.equals(calculatedCk, mkChecksum)) {
                        ivSpec = new IvParameterSpec(IV);
                        c.init(Cipher.DECRYPT_MODE,
                                new SecretKeySpec(mk, "AES"),
                                ivSpec);
                        // Only if all of the above worked properly will 'result' be assigned
                        result = new CipherInputStream(rawInStream, c);
                    } else Slog.w(TAG, "Incorrect password");
                } else Slog.w(TAG, "Unsupported encryption method: " + encryptionName);
            } catch (InvalidAlgorithmParameterException e) {
                Slog.e(TAG, "Needed parameter spec unavailable!", e);
            } catch (BadPaddingException e) {
                // This case frequently occurs when the wrong password is used to decrypt
                // the master key.  Use the identical "incorrect password" log text as is
                // used in the checksum failure log in order to avoid providing additional
                // information to an attacker.
                Slog.w(TAG, "Incorrect password");
            } catch (IllegalBlockSizeException e) {
                Slog.w(TAG, "Invalid block size in master key");
            } catch (NoSuchAlgorithmException e) {
                Slog.e(TAG, "Needed decryption algorithm unavailable!");
            } catch (NoSuchPaddingException e) {
                Slog.e(TAG, "Needed padding mechanism unavailable!");
            } catch (InvalidKeyException e) {
                Slog.w(TAG, "Illegal password; aborting");
            } catch (NumberFormatException e) {
                Slog.w(TAG, "Can't parse restore data header");
            } catch (IOException e) {
                Slog.w(TAG, "Can't read input header");
            }

            return result;
        }

        boolean restoreOneFile(InputStream instream, byte[] buffer) {
            FileMetadata info;
            try {
                info = readTarHeaders(instream);
                if (info != null) {
                    if (MORE_DEBUG) {
                        dumpFileMetadata(info);
                    }

                    final String pkg = info.packageName;
                    if (!pkg.equals(mAgentPackage)) {
                        // okay, change in package; set up our various
                        // bookkeeping if we haven't seen it yet
                        if (!mPackagePolicies.containsKey(pkg)) {
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                        }

                        // Clean up the previous agent relationship if necessary,
                        // and let the observer know we're considering a new app.
                        if (mAgent != null) {
                            if (DEBUG) Slog.d(TAG, "Saw new package; tearing down old one");
                            tearDownPipes();
                            tearDownAgent(mTargetApp);
                            mTargetApp = null;
                            mAgentPackage = null;
                        }
                    }

                    if (info.path.equals(BACKUP_MANIFEST_FILENAME)) {
                        mPackagePolicies.put(pkg, readAppManifest(info, instream));
                        mPackageInstallers.put(pkg, info.installerPackageName);
                        // We've read only the manifest content itself at this point,
                        // so consume the footer before looping around to the next
                        // input file
                        skipTarPadding(info.size, instream);
                        sendOnRestorePackage(pkg);
                    } else {
                        // Non-manifest, so it's actual file data.  Is this a package
                        // we're ignoring?
                        boolean okay = true;
                        RestorePolicy policy = mPackagePolicies.get(pkg);
                        switch (policy) {
                            case IGNORE:
                                okay = false;
                                break;

                            case ACCEPT_IF_APK:
                                // If we're in accept-if-apk state, then the first file we
                                // see MUST be the apk.
                                if (info.domain.equals(FullBackup.APK_TREE_TOKEN)) {
                                    if (DEBUG) Slog.d(TAG, "APK file; installing");
                                    // Try to install the app.
                                    String installerName = mPackageInstallers.get(pkg);
                                    okay = installApk(info, installerName, instream);
                                    // good to go; promote to ACCEPT
                                    mPackagePolicies.put(pkg, (okay)
                                            ? RestorePolicy.ACCEPT
                                            : RestorePolicy.IGNORE);
                                    // At this point we've consumed this file entry
                                    // ourselves, so just strip the tar footer and
                                    // go on to the next file in the input stream
                                    skipTarPadding(info.size, instream);
                                    return true;
                                } else {
                                    // File data before (or without) the apk.  We can't
                                    // handle it coherently in this case so ignore it.
                                    mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                                    okay = false;
                                }
                                break;

                            case ACCEPT:
                                if (info.domain.equals(FullBackup.APK_TREE_TOKEN)) {
                                    if (DEBUG) Slog.d(TAG, "apk present but ACCEPT");
                                    // we can take the data without the apk, so we
                                    // *want* to do so.  skip the apk by declaring this
                                    // one file not-okay without changing the restore
                                    // policy for the package.
                                    okay = false;
                                }
                                break;

                            default:
                                // Something has gone dreadfully wrong when determining
                                // the restore policy from the manifest.  Ignore the
                                // rest of this package's data.
                                Slog.e(TAG, "Invalid policy from manifest");
                                okay = false;
                                mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                                break;
                        }

                        // If the policy is satisfied, go ahead and set up to pipe the
                        // data to the agent.
                        if (DEBUG && okay && mAgent != null) {
                            Slog.i(TAG, "Reusing existing agent instance");
                        }
                        if (okay && mAgent == null) {
                            if (DEBUG) Slog.d(TAG, "Need to launch agent for " + pkg);

                            try {
                                mTargetApp = mPackageManager.getApplicationInfo(pkg, 0);

                                // If we haven't sent any data to this app yet, we probably
                                // need to clear it first.  Check that.
                                if (!mClearedPackages.contains(pkg)) {
                                    // apps with their own backup agents are
                                    // responsible for coherently managing a full
                                    // restore.
                                    if (mTargetApp.backupAgentName == null) {
                                        if (DEBUG) Slog.d(TAG, "Clearing app data preparatory to full restore");
                                        clearApplicationDataSynchronous(pkg);
                                    } else {
                                        if (DEBUG) Slog.d(TAG, "backup agent ("
                                                + mTargetApp.backupAgentName + ") => no clear");
                                    }
                                    mClearedPackages.add(pkg);
                                } else {
                                    if (DEBUG) Slog.d(TAG, "We've initialized this app already; no clear required");
                                }

                                // All set; now set up the IPC and launch the agent
                                setUpPipes();
                                mAgent = bindToAgentSynchronous(mTargetApp,
                                        IApplicationThread.BACKUP_MODE_RESTORE_FULL);
                                mAgentPackage = pkg;
                            } catch (IOException e) {
                                // fall through to error handling
                            } catch (NameNotFoundException e) {
                                // fall through to error handling
                            }

                            if (mAgent == null) {
                                if (DEBUG) Slog.d(TAG, "Unable to create agent for " + pkg);
                                okay = false;
                                tearDownPipes();
                                mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                            }
                        }

                        // Sanity check: make sure we never give data to the wrong app.  This
                        // should never happen but a little paranoia here won't go amiss.
                        if (okay && !pkg.equals(mAgentPackage)) {
                            Slog.e(TAG, "Restoring data for " + pkg
                                    + " but agent is for " + mAgentPackage);
                            okay = false;
                        }

                        // At this point we have an agent ready to handle the full
                        // restore data as well as a pipe for sending data to
                        // that agent.  Tell the agent to start reading from the
                        // pipe.
                        if (okay) {
                            boolean agentSuccess = true;
                            long toCopy = info.size;
                            final int token = generateToken();
                            try {
                                if (DEBUG) Slog.d(TAG, "Invoking agent to restore file "
                                        + info.path);
                                prepareOperationTimeout(token, TIMEOUT_FULL_BACKUP_INTERVAL, null);
                                // fire up the app's agent listening on the socket.  If
                                // the agent is running in the system process we can't
                                // just invoke it asynchronously, so we provide a thread
                                // for it here.
                                if (mTargetApp.processName.equals("system")) {
                                    Slog.d(TAG, "system process agent - spinning a thread");
                                    RestoreFileRunnable runner = new RestoreFileRunnable(
                                            mAgent, info, mPipes[0], token);
                                    new Thread(runner).start();
                                } else {
                                    mAgent.doRestoreFile(mPipes[0], info.size, info.type,
                                            info.domain, info.path, info.mode, info.mtime,
                                            token, mBackupManagerBinder);
                                }
                            } catch (IOException e) {
                                // couldn't dup the socket for a process-local restore
                                Slog.d(TAG, "Couldn't establish restore");
                                agentSuccess = false;
                                okay = false;
                            } catch (RemoteException e) {
                                // whoops, remote agent went away.  We'll eat the content
                                // ourselves, then, and not copy it over.
                                Slog.e(TAG, "Agent crashed during full restore");
                                agentSuccess = false;
                                okay = false;
                            }

                            // Copy over the data if the agent is still good
                            if (okay) {
                                boolean pipeOkay = true;
                                FileOutputStream pipe = new FileOutputStream(
                                        mPipes[1].getFileDescriptor());
                                while (toCopy > 0) {
                                    int toRead = (toCopy > buffer.length)
                                    ? buffer.length : (int)toCopy;
                                    int nRead = instream.read(buffer, 0, toRead);
                                    if (nRead >= 0) mBytes += nRead;
                                    if (nRead <= 0) break;
                                    toCopy -= nRead;

                                    // send it to the output pipe as long as things
                                    // are still good
                                    if (pipeOkay) {
                                        try {
                                            pipe.write(buffer, 0, nRead);
                                        } catch (IOException e) {
                                            Slog.e(TAG, "Failed to write to restore pipe", e);
                                            pipeOkay = false;
                                        }
                                    }
                                }

                                // done sending that file!  Now we just need to consume
                                // the delta from info.size to the end of block.
                                skipTarPadding(info.size, instream);

                                // and now that we've sent it all, wait for the remote
                                // side to acknowledge receipt
                                agentSuccess = waitUntilOperationComplete(token);
                            }

                            // okay, if the remote end failed at any point, deal with
                            // it by ignoring the rest of the restore on it
                            if (!agentSuccess) {
                                mBackupHandler.removeMessages(MSG_TIMEOUT);
                                tearDownPipes();
                                tearDownAgent(mTargetApp);
                                mAgent = null;
                                mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                            }
                        }

                        // Problems setting up the agent communication, or an already-
                        // ignored package: skip to the next tar stream entry by
                        // reading and discarding this file.
                        if (!okay) {
                            if (DEBUG) Slog.d(TAG, "[discarding file content]");
                            long bytesToConsume = (info.size + 511) & ~511;
                            while (bytesToConsume > 0) {
                                int toRead = (bytesToConsume > buffer.length)
                                ? buffer.length : (int)bytesToConsume;
                                long nRead = instream.read(buffer, 0, toRead);
                                if (nRead >= 0) mBytes += nRead;
                                if (nRead <= 0) break;
                                bytesToConsume -= nRead;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                if (DEBUG) Slog.w(TAG, "io exception on restore socket read", e);
                // treat as EOF
                info = null;
            }

            return (info != null);
        }

        void setUpPipes() throws IOException {
            mPipes = ParcelFileDescriptor.createPipe();
        }

        void tearDownPipes() {
            if (mPipes != null) {
                try {
                    mPipes[0].close();
                    mPipes[0] = null;
                    mPipes[1].close();
                    mPipes[1] = null;
                } catch (IOException e) {
                    Slog.w(TAG, "Couldn't close agent pipes", e);
                }
                mPipes = null;
            }
        }

        void tearDownAgent(ApplicationInfo app) {
            if (mAgent != null) {
                try {
                    // unbind and tidy up even on timeout or failure, just in case
                    mActivityManager.unbindBackupAgent(app);

                    // The agent was running with a stub Application object, so shut it down.
                    // !!! We hardcode the confirmation UI's package name here rather than use a
                    //     manifest flag!  TODO something less direct.
                    if (app.uid != Process.SYSTEM_UID
                            && !app.packageName.equals("com.android.backupconfirm")) {
                        if (DEBUG) Slog.d(TAG, "Killing host process");
                        mActivityManager.killApplicationProcess(app.processName, app.uid);
                    } else {
                        if (DEBUG) Slog.d(TAG, "Not killing after full restore");
                    }
                } catch (RemoteException e) {
                    Slog.d(TAG, "Lost app trying to shut down");
                }
                mAgent = null;
            }
        }

        class RestoreInstallObserver extends IPackageInstallObserver.Stub {
            final AtomicBoolean mDone = new AtomicBoolean();
            String mPackageName;
            int mResult;

            public void reset() {
                synchronized (mDone) {
                    mDone.set(false);
                }
            }

            public void waitForCompletion() {
                synchronized (mDone) {
                    while (mDone.get() == false) {
                        try {
                            mDone.wait();
                        } catch (InterruptedException e) { }
                    }
                }
            }

            int getResult() {
                return mResult;
            }

            @Override
            public void packageInstalled(String packageName, int returnCode)
                    throws RemoteException {
                synchronized (mDone) {
                    mResult = returnCode;
                    mPackageName = packageName;
                    mDone.set(true);
                    mDone.notifyAll();
                }
            }
        }

        class RestoreDeleteObserver extends IPackageDeleteObserver.Stub {
            final AtomicBoolean mDone = new AtomicBoolean();
            int mResult;

            public void reset() {
                synchronized (mDone) {
                    mDone.set(false);
                }
            }

            public void waitForCompletion() {
                synchronized (mDone) {
                    while (mDone.get() == false) {
                        try {
                            mDone.wait();
                        } catch (InterruptedException e) { }
                    }
                }
            }

            @Override
            public void packageDeleted(String packageName, int returnCode) throws RemoteException {
                synchronized (mDone) {
                    mResult = returnCode;
                    mDone.set(true);
                    mDone.notifyAll();
                }
            }
        }

        final RestoreInstallObserver mInstallObserver = new RestoreInstallObserver();
        final RestoreDeleteObserver mDeleteObserver = new RestoreDeleteObserver();

        boolean installApk(FileMetadata info, String installerPackage, InputStream instream) {
            boolean okay = true;

            if (DEBUG) Slog.d(TAG, "Installing from backup: " + info.packageName);

            // The file content is an .apk file.  Copy it out to a staging location and
            // attempt to install it.
            File apkFile = new File(mDataDir, info.packageName);
            try {
                FileOutputStream apkStream = new FileOutputStream(apkFile);
                byte[] buffer = new byte[32 * 1024];
                long size = info.size;
                while (size > 0) {
                    long toRead = (buffer.length < size) ? buffer.length : size;
                    int didRead = instream.read(buffer, 0, (int)toRead);
                    if (didRead >= 0) mBytes += didRead;
                    apkStream.write(buffer, 0, didRead);
                    size -= didRead;
                }
                apkStream.close();

                // make sure the installer can read it
                apkFile.setReadable(true, false);

                // Now install it
                Uri packageUri = Uri.fromFile(apkFile);
                mInstallObserver.reset();
                mPackageManager.installPackage(packageUri, mInstallObserver,
                        PackageManager.INSTALL_REPLACE_EXISTING | PackageManager.INSTALL_FROM_ADB,
                        installerPackage);
                mInstallObserver.waitForCompletion();

                if (mInstallObserver.getResult() != PackageManager.INSTALL_SUCCEEDED) {
                    // The only time we continue to accept install of data even if the
                    // apk install failed is if we had already determined that we could
                    // accept the data regardless.
                    if (mPackagePolicies.get(info.packageName) != RestorePolicy.ACCEPT) {
                        okay = false;
                    }
                } else {
                    // Okay, the install succeeded.  Make sure it was the right app.
                    boolean uninstall = false;
                    if (!mInstallObserver.mPackageName.equals(info.packageName)) {
                        Slog.w(TAG, "Restore stream claimed to include apk for "
                                + info.packageName + " but apk was really "
                                + mInstallObserver.mPackageName);
                        // delete the package we just put in place; it might be fraudulent
                        okay = false;
                        uninstall = true;
                    } else {
                        try {
                            PackageInfo pkg = mPackageManager.getPackageInfo(info.packageName,
                                    PackageManager.GET_SIGNATURES);
                            if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) == 0) {
                                Slog.w(TAG, "Restore stream contains apk of package "
                                        + info.packageName + " but it disallows backup/restore");
                                okay = false;
                            } else {
                                // So far so good -- do the signatures match the manifest?
                                Signature[] sigs = mManifestSignatures.get(info.packageName);
                                if (!signaturesMatch(sigs, pkg)) {
                                    Slog.w(TAG, "Installed app " + info.packageName
                                            + " signatures do not match restore manifest");
                                    okay = false;
                                    uninstall = true;
                                }
                            }
                        } catch (NameNotFoundException e) {
                            Slog.w(TAG, "Install of package " + info.packageName
                                    + " succeeded but now not found");
                            okay = false;
                        }
                    }

                    // If we're not okay at this point, we need to delete the package
                    // that we just installed.
                    if (uninstall) {
                        mDeleteObserver.reset();
                        mPackageManager.deletePackage(mInstallObserver.mPackageName,
                                mDeleteObserver, 0);
                        mDeleteObserver.waitForCompletion();
                    }
                }
            } catch (IOException e) {
                Slog.e(TAG, "Unable to transcribe restored apk for install");
                okay = false;
            } finally {
                apkFile.delete();
            }

            return okay;
        }

        // Given an actual file content size, consume the post-content padding mandated
        // by the tar format.
        void skipTarPadding(long size, InputStream instream) throws IOException {
            long partial = (size + 512) % 512;
            if (partial > 0) {
                final int needed = 512 - (int)partial;
                byte[] buffer = new byte[needed];
                if (readExactly(instream, buffer, 0, needed) == needed) {
                    mBytes += needed;
                } else throw new IOException("Unexpected EOF in padding");
            }
        }

        // Returns a policy constant; takes a buffer arg to reduce memory churn
        RestorePolicy readAppManifest(FileMetadata info, InputStream instream)
                throws IOException {
            // Fail on suspiciously large manifest files
            if (info.size > 64 * 1024) {
                throw new IOException("Restore manifest too big; corrupt? size=" + info.size);
            }

            byte[] buffer = new byte[(int) info.size];
            if (readExactly(instream, buffer, 0, (int)info.size) == info.size) {
                mBytes += info.size;
            } else throw new IOException("Unexpected EOF in manifest");

            RestorePolicy policy = RestorePolicy.IGNORE;
            String[] str = new String[1];
            int offset = 0;

            try {
                offset = extractLine(buffer, offset, str);
                int version = Integer.parseInt(str[0]);
                if (version == BACKUP_MANIFEST_VERSION) {
                    offset = extractLine(buffer, offset, str);
                    String manifestPackage = str[0];
                    // TODO: handle <original-package>
                    if (manifestPackage.equals(info.packageName)) {
                        offset = extractLine(buffer, offset, str);
                        version = Integer.parseInt(str[0]);  // app version
                        offset = extractLine(buffer, offset, str);
                        int platformVersion = Integer.parseInt(str[0]);
                        offset = extractLine(buffer, offset, str);
                        info.installerPackageName = (str[0].length() > 0) ? str[0] : null;
                        offset = extractLine(buffer, offset, str);
                        boolean hasApk = str[0].equals("1");
                        offset = extractLine(buffer, offset, str);
                        int numSigs = Integer.parseInt(str[0]);
                        if (numSigs > 0) {
                            Signature[] sigs = new Signature[numSigs];
                            for (int i = 0; i < numSigs; i++) {
                                offset = extractLine(buffer, offset, str);
                                sigs[i] = new Signature(str[0]);
                            }
                            mManifestSignatures.put(info.packageName, sigs);

                            // Okay, got the manifest info we need...
                            try {
                                PackageInfo pkgInfo = mPackageManager.getPackageInfo(
                                        info.packageName, PackageManager.GET_SIGNATURES);
                                // Fall through to IGNORE if the app explicitly disallows backup
                                final int flags = pkgInfo.applicationInfo.flags;
                                if ((flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0) {
                                    // Restore system-uid-space packages only if they have
                                    // defined a custom backup agent
                                    if ((pkgInfo.applicationInfo.uid >= Process.FIRST_APPLICATION_UID)
                                            || (pkgInfo.applicationInfo.backupAgentName != null)) {
                                        // Verify signatures against any installed version; if they
                                        // don't match, then we fall though and ignore the data.  The
                                        // signatureMatch() method explicitly ignores the signature
                                        // check for packages installed on the system partition, because
                                        // such packages are signed with the platform cert instead of
                                        // the app developer's cert, so they're different on every
                                        // device.
                                        if (signaturesMatch(sigs, pkgInfo)) {
                                            if (pkgInfo.versionCode >= version) {
                                                Slog.i(TAG, "Sig + version match; taking data");
                                                policy = RestorePolicy.ACCEPT;
                                            } else {
                                                // The data is from a newer version of the app than
                                                // is presently installed.  That means we can only
                                                // use it if the matching apk is also supplied.
                                                Slog.d(TAG, "Data version " + version
                                                        + " is newer than installed version "
                                                        + pkgInfo.versionCode + " - requiring apk");
                                                policy = RestorePolicy.ACCEPT_IF_APK;
                                            }
                                        } else {
                                            Slog.w(TAG, "Restore manifest signatures do not match "
                                                    + "installed application for " + info.packageName);
                                        }
                                    } else {
                                        Slog.w(TAG, "Package " + info.packageName
                                                + " is system level with no agent");
                                    }
                                } else {
                                    if (DEBUG) Slog.i(TAG, "Restore manifest from "
                                            + info.packageName + " but allowBackup=false");
                                }
                            } catch (NameNotFoundException e) {
                                // Okay, the target app isn't installed.  We can process
                                // the restore properly only if the dataset provides the
                                // apk file and we can successfully install it.
                                if (DEBUG) Slog.i(TAG, "Package " + info.packageName
                                        + " not installed; requiring apk in dataset");
                                policy = RestorePolicy.ACCEPT_IF_APK;
                            }

                            if (policy == RestorePolicy.ACCEPT_IF_APK && !hasApk) {
                                Slog.i(TAG, "Cannot restore package " + info.packageName
                                        + " without the matching .apk");
                            }
                        } else {
                            Slog.i(TAG, "Missing signature on backed-up package "
                                    + info.packageName);
                        }
                    } else {
                        Slog.i(TAG, "Expected package " + info.packageName
                                + " but restore manifest claims " + manifestPackage);
                    }
                } else {
                    Slog.i(TAG, "Unknown restore manifest version " + version
                            + " for package " + info.packageName);
                }
            } catch (NumberFormatException e) {
                Slog.w(TAG, "Corrupt restore manifest for package " + info.packageName);
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, e.getMessage());
            }

            return policy;
        }

        // Builds a line from a byte buffer starting at 'offset', and returns
        // the index of the next unconsumed data in the buffer.
        int extractLine(byte[] buffer, int offset, String[] outStr) throws IOException {
            final int end = buffer.length;
            if (offset >= end) throw new IOException("Incomplete data");

            int pos;
            for (pos = offset; pos < end; pos++) {
                byte c = buffer[pos];
                // at LF we declare end of line, and return the next char as the
                // starting point for the next time through
                if (c == '\n') {
                    break;
                }
            }
            outStr[0] = new String(buffer, offset, pos - offset);
            pos++;  // may be pointing an extra byte past the end but that's okay
            return pos;
        }

        void dumpFileMetadata(FileMetadata info) {
            if (DEBUG) {
                StringBuilder b = new StringBuilder(128);

                // mode string
                b.append((info.type == BackupAgent.TYPE_DIRECTORY) ? 'd' : '-');
                b.append(((info.mode & 0400) != 0) ? 'r' : '-');
                b.append(((info.mode & 0200) != 0) ? 'w' : '-');
                b.append(((info.mode & 0100) != 0) ? 'x' : '-');
                b.append(((info.mode & 0040) != 0) ? 'r' : '-');
                b.append(((info.mode & 0020) != 0) ? 'w' : '-');
                b.append(((info.mode & 0010) != 0) ? 'x' : '-');
                b.append(((info.mode & 0004) != 0) ? 'r' : '-');
                b.append(((info.mode & 0002) != 0) ? 'w' : '-');
                b.append(((info.mode & 0001) != 0) ? 'x' : '-');
                b.append(String.format(" %9d ", info.size));

                Date stamp = new Date(info.mtime);
                b.append(new SimpleDateFormat("MMM dd kk:mm:ss ").format(stamp));

                b.append(info.packageName);
                b.append(" :: ");
                b.append(info.domain);
                b.append(" :: ");
                b.append(info.path);

                Slog.i(TAG, b.toString());
            }
        }
        // Consume a tar file header block [sequence] and accumulate the relevant metadata
        FileMetadata readTarHeaders(InputStream instream) throws IOException {
            byte[] block = new byte[512];
            FileMetadata info = null;

            boolean gotHeader = readTarHeader(instream, block);
            if (gotHeader) {
                try {
                    // okay, presume we're okay, and extract the various metadata
                    info = new FileMetadata();
                    info.size = extractRadix(block, 124, 12, 8);
                    info.mtime = extractRadix(block, 136, 12, 8);
                    info.mode = extractRadix(block, 100, 8, 8);

                    info.path = extractString(block, 345, 155); // prefix
                    String path = extractString(block, 0, 100);
                    if (path.length() > 0) {
                        if (info.path.length() > 0) info.path += '/';
                        info.path += path;
                    }

                    // tar link indicator field: 1 byte at offset 156 in the header.
                    int typeChar = block[156];
                    if (typeChar == 'x') {
                        // pax extended header, so we need to read that
                        gotHeader = readPaxExtendedHeader(instream, info);
                        if (gotHeader) {
                            // and after a pax extended header comes another real header -- read
                            // that to find the real file type
                            gotHeader = readTarHeader(instream, block);
                        }
                        if (!gotHeader) throw new IOException("Bad or missing pax header");

                        typeChar = block[156];
                    }

                    switch (typeChar) {
                        case '0': info.type = BackupAgent.TYPE_FILE; break;
                        case '5': {
                            info.type = BackupAgent.TYPE_DIRECTORY;
                            if (info.size != 0) {
                                Slog.w(TAG, "Directory entry with nonzero size in header");
                                info.size = 0;
                            }
                            break;
                        }
                        case 0: {
                            // presume EOF
                            if (DEBUG) Slog.w(TAG, "Saw type=0 in tar header block, info=" + info);
                            return null;
                        }
                        default: {
                            Slog.e(TAG, "Unknown tar entity type: " + typeChar);
                            throw new IOException("Unknown entity type " + typeChar);
                        }
                    }

                    // Parse out the path
                    //
                    // first: apps/shared/unrecognized
                    if (FullBackup.SHARED_PREFIX.regionMatches(0,
                            info.path, 0, FullBackup.SHARED_PREFIX.length())) {
                        // File in shared storage.  !!! TODO: implement this.
                        info.path = info.path.substring(FullBackup.SHARED_PREFIX.length());
                        info.packageName = SHARED_BACKUP_AGENT_PACKAGE;
                        info.domain = FullBackup.SHARED_STORAGE_TOKEN;
                        if (DEBUG) Slog.i(TAG, "File in shared storage: " + info.path);
                    } else if (FullBackup.APPS_PREFIX.regionMatches(0,
                            info.path, 0, FullBackup.APPS_PREFIX.length())) {
                        // App content!  Parse out the package name and domain

                        // strip the apps/ prefix
                        info.path = info.path.substring(FullBackup.APPS_PREFIX.length());

                        // extract the package name
                        int slash = info.path.indexOf('/');
                        if (slash < 0) throw new IOException("Illegal semantic path in " + info.path);
                        info.packageName = info.path.substring(0, slash);
                        info.path = info.path.substring(slash+1);

                        // if it's a manifest we're done, otherwise parse out the domains
                        if (!info.path.equals(BACKUP_MANIFEST_FILENAME)) {
                            slash = info.path.indexOf('/');
                            if (slash < 0) throw new IOException("Illegal semantic path in non-manifest " + info.path);
                            info.domain = info.path.substring(0, slash);
                            // validate that it's one of the domains we understand
                            if (!info.domain.equals(FullBackup.APK_TREE_TOKEN)
                                    && !info.domain.equals(FullBackup.DATA_TREE_TOKEN)
                                    && !info.domain.equals(FullBackup.DATABASE_TREE_TOKEN)
                                    && !info.domain.equals(FullBackup.ROOT_TREE_TOKEN)
                                    && !info.domain.equals(FullBackup.SHAREDPREFS_TREE_TOKEN)
                                    && !info.domain.equals(FullBackup.OBB_TREE_TOKEN)
                                    && !info.domain.equals(FullBackup.CACHE_TREE_TOKEN)) {
                                throw new IOException("Unrecognized domain " + info.domain);
                            }

                            info.path = info.path.substring(slash + 1);
                        }
                    }
                } catch (IOException e) {
                    if (DEBUG) {
                        Slog.e(TAG, "Parse error in header: " + e.getMessage());
                        HEXLOG(block);
                    }
                    throw e;
                }
            }
            return info;
        }

        private void HEXLOG(byte[] block) {
            int offset = 0;
            int todo = block.length;
            StringBuilder buf = new StringBuilder(64);
            while (todo > 0) {
                buf.append(String.format("%04x   ", offset));
                int numThisLine = (todo > 16) ? 16 : todo;
                for (int i = 0; i < numThisLine; i++) {
                    buf.append(String.format("%02x ", block[offset+i]));
                }
                Slog.i("hexdump", buf.toString());
                buf.setLength(0);
                todo -= numThisLine;
                offset += numThisLine;
            }
        }

        // Read exactly the given number of bytes into a buffer at the stated offset.
        // Returns false if EOF is encountered before the requested number of bytes
        // could be read.
        int readExactly(InputStream in, byte[] buffer, int offset, int size)
                throws IOException {
            if (size <= 0) throw new IllegalArgumentException("size must be > 0");

            int soFar = 0;
            while (soFar < size) {
                int nRead = in.read(buffer, offset + soFar, size - soFar);
                if (nRead <= 0) {
                    if (MORE_DEBUG) Slog.w(TAG, "- wanted exactly " + size + " but got only " + soFar);
                    break;
                }
                soFar += nRead;
            }
            return soFar;
        }

        boolean readTarHeader(InputStream instream, byte[] block) throws IOException {
            final int got = readExactly(instream, block, 0, 512);
            if (got == 0) return false;     // Clean EOF
            if (got < 512) throw new IOException("Unable to read full block header");
            mBytes += 512;
            return true;
        }

        // overwrites 'info' fields based on the pax extended header
        boolean readPaxExtendedHeader(InputStream instream, FileMetadata info)
                throws IOException {
            // We should never see a pax extended header larger than this
            if (info.size > 32*1024) {
                Slog.w(TAG, "Suspiciously large pax header size " + info.size
                        + " - aborting");
                throw new IOException("Sanity failure: pax header size " + info.size);
            }

            // read whole blocks, not just the content size
            int numBlocks = (int)((info.size + 511) >> 9);
            byte[] data = new byte[numBlocks * 512];
            if (readExactly(instream, data, 0, data.length) < data.length) {
                throw new IOException("Unable to read full pax header");
            }
            mBytes += data.length;

            final int contentSize = (int) info.size;
            int offset = 0;
            do {
                // extract the line at 'offset'
                int eol = offset+1;
                while (eol < contentSize && data[eol] != ' ') eol++;
                if (eol >= contentSize) {
                    // error: we just hit EOD looking for the end of the size field
                    throw new IOException("Invalid pax data");
                }
                // eol points to the space between the count and the key
                int linelen = (int) extractRadix(data, offset, eol - offset, 10);
                int key = eol + 1;  // start of key=value
                eol = offset + linelen - 1; // trailing LF
                int value;
                for (value = key+1; data[value] != '=' && value <= eol; value++);
                if (value > eol) {
                    throw new IOException("Invalid pax declaration");
                }

                // pax requires that key/value strings be in UTF-8
                String keyStr = new String(data, key, value-key, "UTF-8");
                // -1 to strip the trailing LF
                String valStr = new String(data, value+1, eol-value-1, "UTF-8");

                if ("path".equals(keyStr)) {
                    info.path = valStr;
                } else if ("size".equals(keyStr)) {
                    info.size = Long.parseLong(valStr);
                } else {
                    if (DEBUG) Slog.i(TAG, "Unhandled pax key: " + key);
                }

                offset += linelen;
            } while (offset < contentSize);

            return true;
        }

        long extractRadix(byte[] data, int offset, int maxChars, int radix)
                throws IOException {
            long value = 0;
            final int end = offset + maxChars;
            for (int i = offset; i < end; i++) {
                final byte b = data[i];
                // Numeric fields in tar can terminate with either NUL or SPC
                if (b == 0 || b == ' ') break;
                if (b < '0' || b > ('0' + radix - 1)) {
                    throw new IOException("Invalid number in header: '" + (char)b + "' for radix " + radix);
                }
                value = radix * value + (b - '0');
            }
            return value;
        }

        String extractString(byte[] data, int offset, int maxChars) throws IOException {
            final int end = offset + maxChars;
            int eos = offset;
            // tar string fields terminate early with a NUL
            while (eos < end && data[eos] != 0) eos++;
            return new String(data, offset, eos-offset, "US-ASCII");
        }

        void sendStartRestore() {
            if (mObserver != null) {
                try {
                    mObserver.onStartRestore();
                } catch (RemoteException e) {
                    Slog.w(TAG, "full restore observer went away: startRestore");
                    mObserver = null;
                }
            }
        }

        void sendOnRestorePackage(String name) {
            if (mObserver != null) {
                try {
                    // TODO: use a more user-friendly name string
                    mObserver.onRestorePackage(name);
                } catch (RemoteException e) {
                    Slog.w(TAG, "full restore observer went away: restorePackage");
                    mObserver = null;
                }
            }
        }

        void sendEndRestore() {
            if (mObserver != null) {
                try {
                    mObserver.onEndRestore();
                } catch (RemoteException e) {
                    Slog.w(TAG, "full restore observer went away: endRestore");
                    mObserver = null;
                }
            }
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
        if (MORE_DEBUG) Slog.v(TAG, "signaturesMatch(): stored=" + storedSigs
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

    enum RestoreState {
        INITIAL,
        DOWNLOAD_DATA,
        PM_METADATA,
        RUNNING_QUEUE,
        FINAL
    }

    class PerformRestoreTask implements BackupRestoreTask {
        private IBackupTransport mTransport;
        private IRestoreObserver mObserver;
        private long mToken;
        private PackageInfo mTargetPackage;
        private File mStateDir;
        private int mPmToken;
        private boolean mNeedFullBackup;
        private HashSet<String> mFilterSet;
        private long mStartRealtime;
        private PackageManagerBackupAgent mPmAgent;
        private List<PackageInfo> mAgentPackages;
        private ArrayList<PackageInfo> mRestorePackages;
        private RestoreState mCurrentState;
        private int mCount;
        private boolean mFinished;
        private int mStatus;
        private File mBackupDataName;
        private File mNewStateName;
        private File mSavedStateName;
        private ParcelFileDescriptor mBackupData;
        private ParcelFileDescriptor mNewState;
        private PackageInfo mCurrentPackage;


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
                boolean needFullBackup, String[] filterSet) {
            mCurrentState = RestoreState.INITIAL;
            mFinished = false;
            mPmAgent = null;

            mTransport = transport;
            mObserver = observer;
            mToken = restoreSetToken;
            mTargetPackage = targetPackage;
            mPmToken = pmToken;
            mNeedFullBackup = needFullBackup;

            if (filterSet != null) {
                mFilterSet = new HashSet<String>();
                for (String pkg : filterSet) {
                    mFilterSet.add(pkg);
                }
            } else {
                mFilterSet = null;
            }

            try {
                mStateDir = new File(mBaseStateDir, transport.transportDirName());
            } catch (RemoteException e) {
                // can't happen; the transport is local
            }
        }

        // Execute one tick of whatever state machine the task implements
        @Override
        public void execute() {
            if (MORE_DEBUG) Slog.v(TAG, "*** Executing restore step: " + mCurrentState);
            switch (mCurrentState) {
                case INITIAL:
                    beginRestore();
                    break;

                case DOWNLOAD_DATA:
                    downloadRestoreData();
                    break;

                case PM_METADATA:
                    restorePmMetadata();
                    break;

                case RUNNING_QUEUE:
                    restoreNextAgent();
                    break;

                case FINAL:
                    if (!mFinished) finalizeRestore();
                    else {
                        Slog.e(TAG, "Duplicate finish");
                    }
                    mFinished = true;
                    break;
            }
        }

        // Initialize and set up for the PM metadata restore, which comes first
        void beginRestore() {
            // Don't account time doing the restore as inactivity of the app
            // that has opened a restore session.
            mBackupHandler.removeMessages(MSG_RESTORE_TIMEOUT);

            // Assume error until we successfully init everything
            mStatus = BackupConstants.TRANSPORT_ERROR;

            try {
                // TODO: Log this before getAvailableRestoreSets, somehow
                EventLog.writeEvent(EventLogTags.RESTORE_START, mTransport.transportDirName(), mToken);

                // Get the list of all packages which have backup enabled.
                // (Include the Package Manager metadata pseudo-package first.)
                mRestorePackages = new ArrayList<PackageInfo>();
                PackageInfo omPackage = new PackageInfo();
                omPackage.packageName = PACKAGE_MANAGER_SENTINEL;
                mRestorePackages.add(omPackage);

                mAgentPackages = allAgentPackages();
                if (mTargetPackage == null) {
                    // if there's a filter set, strip out anything that isn't
                    // present before proceeding
                    if (mFilterSet != null) {
                        for (int i = mAgentPackages.size() - 1; i >= 0; i--) {
                            final PackageInfo pkg = mAgentPackages.get(i);
                            if (! mFilterSet.contains(pkg.packageName)) {
                                mAgentPackages.remove(i);
                            }
                        }
                        if (MORE_DEBUG) {
                            Slog.i(TAG, "Post-filter package set for restore:");
                            for (PackageInfo p : mAgentPackages) {
                                Slog.i(TAG, "    " + p);
                            }
                        }
                    }
                    mRestorePackages.addAll(mAgentPackages);
                } else {
                    // Just one package to attempt restore of
                    mRestorePackages.add(mTargetPackage);
                }

                // let the observer know that we're running
                if (mObserver != null) {
                    try {
                        // !!! TODO: get an actual count from the transport after
                        // its startRestore() runs?
                        mObserver.restoreStarting(mRestorePackages.size());
                    } catch (RemoteException e) {
                        Slog.d(TAG, "Restore observer died at restoreStarting");
                        mObserver = null;
                    }
                }
            } catch (RemoteException e) {
                // Something has gone catastrophically wrong with the transport
                Slog.e(TAG, "Error communicating with transport for restore");
                executeNextState(RestoreState.FINAL);
                return;
            }

            mStatus = BackupConstants.TRANSPORT_OK;
            executeNextState(RestoreState.DOWNLOAD_DATA);
        }

        void downloadRestoreData() {
            // Note that the download phase can be very time consuming, but we're executing
            // it inline here on the looper.  This is "okay" because it is not calling out to
            // third party code; the transport is "trusted," and so we assume it is being a
            // good citizen and timing out etc when appropriate.
            //
            // TODO: when appropriate, move the download off the looper and rearrange the
            //       error handling around that.
            try {
                mStatus = mTransport.startRestore(mToken,
                        mRestorePackages.toArray(new PackageInfo[0]));
                if (mStatus != BackupConstants.TRANSPORT_OK) {
                    Slog.e(TAG, "Error starting restore operation");
                    EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                    executeNextState(RestoreState.FINAL);
                    return;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Error communicating with transport for restore");
                EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                mStatus = BackupConstants.TRANSPORT_ERROR;
                executeNextState(RestoreState.FINAL);
                return;
            }

            // Successful download of the data to be parceled out to the apps, so off we go.
            executeNextState(RestoreState.PM_METADATA);
        }

        void restorePmMetadata() {
            try {
                String packageName = mTransport.nextRestorePackage();
                if (packageName == null) {
                    Slog.e(TAG, "Error getting first restore package");
                    EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                    mStatus = BackupConstants.TRANSPORT_ERROR;
                    executeNextState(RestoreState.FINAL);
                    return;
                } else if (packageName.equals("")) {
                    Slog.i(TAG, "No restore data available");
                    int millis = (int) (SystemClock.elapsedRealtime() - mStartRealtime);
                    EventLog.writeEvent(EventLogTags.RESTORE_SUCCESS, 0, millis);
                    mStatus = BackupConstants.TRANSPORT_OK;
                    executeNextState(RestoreState.FINAL);
                    return;
                } else if (!packageName.equals(PACKAGE_MANAGER_SENTINEL)) {
                    Slog.e(TAG, "Expected restore data for \"" + PACKAGE_MANAGER_SENTINEL
                            + "\", found only \"" + packageName + "\"");
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, PACKAGE_MANAGER_SENTINEL,
                            "Package manager data missing");
                    executeNextState(RestoreState.FINAL);
                    return;
                }

                // Pull the Package Manager metadata from the restore set first
                PackageInfo omPackage = new PackageInfo();
                omPackage.packageName = PACKAGE_MANAGER_SENTINEL;
                mPmAgent = new PackageManagerBackupAgent(
                        mPackageManager, mAgentPackages);
                initiateOneRestore(omPackage, 0, IBackupAgent.Stub.asInterface(mPmAgent.onBind()),
                        mNeedFullBackup);
                // The PM agent called operationComplete() already, because our invocation
                // of it is process-local and therefore synchronous.  That means that a
                // RUNNING_QUEUE message is already enqueued.  Only if we're unable to
                // proceed with running the queue do we remove that pending message and
                // jump straight to the FINAL state.

                // Verify that the backup set includes metadata.  If not, we can't do
                // signature/version verification etc, so we simply do not proceed with
                // the restore operation.
                if (!mPmAgent.hasMetadata()) {
                    Slog.e(TAG, "No restore metadata available, so not restoring settings");
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, PACKAGE_MANAGER_SENTINEL,
                    "Package manager restore metadata missing");
                    mStatus = BackupConstants.TRANSPORT_ERROR;
                    mBackupHandler.removeMessages(MSG_BACKUP_RESTORE_STEP, this);
                    executeNextState(RestoreState.FINAL);
                    return;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Error communicating with transport for restore");
                EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                mStatus = BackupConstants.TRANSPORT_ERROR;
                mBackupHandler.removeMessages(MSG_BACKUP_RESTORE_STEP, this);
                executeNextState(RestoreState.FINAL);
                return;
            }

            // Metadata is intact, so we can now run the restore queue.  If we get here,
            // we have already enqueued the necessary next-step message on the looper.
        }

        void restoreNextAgent() {
            try {
                String packageName = mTransport.nextRestorePackage();

                if (packageName == null) {
                    Slog.e(TAG, "Error getting next restore package");
                    EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                    executeNextState(RestoreState.FINAL);
                    return;
                } else if (packageName.equals("")) {
                    if (DEBUG) Slog.v(TAG, "No next package, finishing restore");
                    int millis = (int) (SystemClock.elapsedRealtime() - mStartRealtime);
                    EventLog.writeEvent(EventLogTags.RESTORE_SUCCESS, mCount, millis);
                    executeNextState(RestoreState.FINAL);
                    return;
                }

                if (mObserver != null) {
                    try {
                        mObserver.onUpdate(mCount, packageName);
                    } catch (RemoteException e) {
                        Slog.d(TAG, "Restore observer died in onUpdate");
                        mObserver = null;
                    }
                }

                Metadata metaInfo = mPmAgent.getRestoredMetadata(packageName);
                if (metaInfo == null) {
                    Slog.e(TAG, "Missing metadata for " + packageName);
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                            "Package metadata missing");
                    executeNextState(RestoreState.RUNNING_QUEUE);
                    return;
                }

                PackageInfo packageInfo;
                try {
                    int flags = PackageManager.GET_SIGNATURES;
                    packageInfo = mPackageManager.getPackageInfo(packageName, flags);
                } catch (NameNotFoundException e) {
                    Slog.e(TAG, "Invalid package restoring data", e);
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                            "Package missing on device");
                    executeNextState(RestoreState.RUNNING_QUEUE);
                    return;
                }

                if (packageInfo.applicationInfo.backupAgentName == null
                        || "".equals(packageInfo.applicationInfo.backupAgentName)) {
                    if (DEBUG) {
                        Slog.i(TAG, "Data exists for package " + packageName
                                + " but app has no agent; skipping");
                    }
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                            "Package has no agent");
                    executeNextState(RestoreState.RUNNING_QUEUE);
                    return;
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
                        executeNextState(RestoreState.RUNNING_QUEUE);
                        return;
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
                    executeNextState(RestoreState.RUNNING_QUEUE);
                    return;
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
                    executeNextState(RestoreState.RUNNING_QUEUE);
                    return;
                }

                // And then finally start the restore on this agent
                try {
                    initiateOneRestore(packageInfo, metaInfo.versionCode, agent, mNeedFullBackup);
                    ++mCount;
                } catch (Exception e) {
                    Slog.e(TAG, "Error when attempting restore: " + e.toString());
                    agentErrorCleanup();
                    executeNextState(RestoreState.RUNNING_QUEUE);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to fetch restore data from transport");
                mStatus = BackupConstants.TRANSPORT_ERROR;
                executeNextState(RestoreState.FINAL);
            }
        }

        void finalizeRestore() {
            if (MORE_DEBUG) Slog.d(TAG, "finishing restore mObserver=" + mObserver);

            try {
                mTransport.finishRestore();
            } catch (RemoteException e) {
                Slog.e(TAG, "Error finishing restore", e);
            }

            if (mObserver != null) {
                try {
                    mObserver.restoreFinished(mStatus);
                } catch (RemoteException e) {
                    Slog.d(TAG, "Restore observer died at restoreFinished");
                }
            }

            // If this was a restoreAll operation, record that this was our
            // ancestral dataset, as well as the set of apps that are possibly
            // restoreable from the dataset
            if (mTargetPackage == null && mPmAgent != null) {
                mAncestralPackages = mPmAgent.getRestoredPackages();
                mAncestralToken = mToken;
                writeRestoreTokens();
            }

            // We must under all circumstances tell the Package Manager to
            // proceed with install notifications if it's waiting for us.
            if (mPmToken > 0) {
                if (MORE_DEBUG) Slog.v(TAG, "finishing PM token " + mPmToken);
                try {
                    mPackageManagerBinder.finishPackageInstall(mPmToken);
                } catch (RemoteException e) { /* can't happen */ }
            }

            // Furthermore we need to reset the session timeout clock
            mBackupHandler.removeMessages(MSG_RESTORE_TIMEOUT);
            mBackupHandler.sendEmptyMessageDelayed(MSG_RESTORE_TIMEOUT,
                    TIMEOUT_RESTORE_INTERVAL);

            // done; we can finally release the wakelock
            Slog.i(TAG, "Restore complete.");
            mWakelock.release();
        }

        // Call asynchronously into the app, passing it the restore data.  The next step
        // after this is always a callback, either operationComplete() or handleTimeout().
        void initiateOneRestore(PackageInfo app, int appVersionCode, IBackupAgent agent,
                boolean needFullBackup) {
            mCurrentPackage = app;
            final String packageName = app.packageName;

            if (DEBUG) Slog.d(TAG, "initiateOneRestore packageName=" + packageName);

            // !!! TODO: get the dirs from the transport
            mBackupDataName = new File(mDataDir, packageName + ".restore");
            mNewStateName = new File(mStateDir, packageName + ".new");
            mSavedStateName = new File(mStateDir, packageName);

            final int token = generateToken();
            try {
                // Run the transport's restore pass
                mBackupData = ParcelFileDescriptor.open(mBackupDataName,
                            ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

                if (mTransport.getRestoreData(mBackupData) != BackupConstants.TRANSPORT_OK) {
                    // Transport-level failure, so we wind everything up and
                    // terminate the restore operation.
                    Slog.e(TAG, "Error getting restore data for " + packageName);
                    EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                    mBackupData.close();
                    mBackupDataName.delete();
                    executeNextState(RestoreState.FINAL);
                    return;
                }

                // Okay, we have the data.  Now have the agent do the restore.
                mBackupData.close();
                mBackupData = ParcelFileDescriptor.open(mBackupDataName,
                            ParcelFileDescriptor.MODE_READ_ONLY);

                mNewState = ParcelFileDescriptor.open(mNewStateName,
                            ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

                // Kick off the restore, checking for hung agents
                prepareOperationTimeout(token, TIMEOUT_RESTORE_INTERVAL, this);
                agent.doRestore(mBackupData, appVersionCode, mNewState, token, mBackupManagerBinder);
            } catch (Exception e) {
                Slog.e(TAG, "Unable to call app for restore: " + packageName, e);
                EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName, e.toString());
                agentErrorCleanup();    // clears any pending timeout messages as well

                // After a restore failure we go back to running the queue.  If there
                // are no more packages to be restored that will be handled by the
                // next step.
                executeNextState(RestoreState.RUNNING_QUEUE);
            }
        }

        void agentErrorCleanup() {
            // If the agent fails restore, it might have put the app's data
            // into an incoherent state.  For consistency we wipe its data
            // again in this case before continuing with normal teardown
            clearApplicationDataSynchronous(mCurrentPackage.packageName);
            agentCleanup();
        }

        void agentCleanup() {
            mBackupDataName.delete();
            try { if (mBackupData != null) mBackupData.close(); } catch (IOException e) {}
            try { if (mNewState != null) mNewState.close(); } catch (IOException e) {}
            mBackupData = mNewState = null;

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
            mNewStateName.delete();                      // TODO: remove; see above comment
            //mNewStateName.renameTo(mSavedStateName);   // TODO: replace with this

            // If this wasn't the PM pseudopackage, tear down the agent side
            if (mCurrentPackage.applicationInfo != null) {
                // unbind and tidy up even on timeout or failure
                try {
                    mActivityManager.unbindBackupAgent(mCurrentPackage.applicationInfo);

                    // The agent was probably running with a stub Application object,
                    // which isn't a valid run mode for the main app logic.  Shut
                    // down the app so that next time it's launched, it gets the
                    // usual full initialization.  Note that this is only done for
                    // full-system restores: when a single app has requested a restore,
                    // it is explicitly not killed following that operation.
                    if (mTargetPackage == null && (mCurrentPackage.applicationInfo.flags
                            & ApplicationInfo.FLAG_KILL_AFTER_RESTORE) != 0) {
                        if (DEBUG) Slog.d(TAG, "Restore complete, killing host process of "
                                + mCurrentPackage.applicationInfo.processName);
                        mActivityManager.killApplicationProcess(
                                mCurrentPackage.applicationInfo.processName,
                                mCurrentPackage.applicationInfo.uid);
                    }
                } catch (RemoteException e) {
                    // can't happen; we run in the same process as the activity manager
                }
            }

            // The caller is responsible for reestablishing the state machine; our
            // responsibility here is to clear the decks for whatever comes next.
            mBackupHandler.removeMessages(MSG_TIMEOUT, this);
            synchronized (mCurrentOpLock) {
                mCurrentOperations.clear();
            }
        }

        // A call to agent.doRestore() has been positively acknowledged as complete
        @Override
        public void operationComplete() {
            int size = (int) mBackupDataName.length();
            EventLog.writeEvent(EventLogTags.RESTORE_PACKAGE, mCurrentPackage.packageName, size);
            // Just go back to running the restore queue
            agentCleanup();

            executeNextState(RestoreState.RUNNING_QUEUE);
        }

        // A call to agent.doRestore() has timed out
        @Override
        public void handleTimeout() {
            Slog.e(TAG, "Timeout restoring application " + mCurrentPackage.packageName);
            EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                    mCurrentPackage.packageName, "restore timeout");
            // Handle like an agent that threw on invocation: wipe it and go on to the next
            agentErrorCleanup();
            executeNextState(RestoreState.RUNNING_QUEUE);
        }

        void executeNextState(RestoreState nextState) {
            if (MORE_DEBUG) Slog.i(TAG, " => executing next step on "
                    + this + " nextState=" + nextState);
            mCurrentState = nextState;
            Message msg = mBackupHandler.obtainMessage(MSG_BACKUP_RESTORE_STEP, this);
            mBackupHandler.sendMessage(msg);
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
            } catch (Exception e) {
                Slog.e(TAG, "Transport threw attempting to clear data for " + mPackage);
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
        HashSet<String> targets = dataChangedTargets(packageName);
        dataChangedImpl(packageName, targets);
    }

    private void dataChangedImpl(String packageName, HashSet<String> targets) {
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
            if (targets.contains(packageName)) {
                // Add the caller to the set of pending backups.  If there is
                // one already there, then overwrite it, but no harm done.
                BackupRequest req = new BackupRequest(packageName);
                if (mPendingBackups.put(packageName, req) == null) {
                    if (DEBUG) Slog.d(TAG, "Now staging backup of " + packageName);

                    // Journal this request in case of crash.  The put()
                    // operation returned null when this package was not already
                    // in the set; we want to avoid touching the disk redundantly.
                    writeToJournalLocked(packageName);

                    if (MORE_DEBUG) {
                        int numKeys = mPendingBackups.size();
                        Slog.d(TAG, "Now awaiting backup for " + numKeys + " participants:");
                        for (BackupRequest b : mPendingBackups.values()) {
                            Slog.d(TAG, "    + " + b);
                        }
                    }
                }
            }
        }
    }

    // Note: packageName is currently unused, but may be in the future
    private HashSet<String> dataChangedTargets(String packageName) {
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
        HashSet<String> targets = new HashSet<String>();
        synchronized (mBackupParticipants) {
            int N = mBackupParticipants.size();
            for (int i = 0; i < N; i++) {
                HashSet<String> s = mBackupParticipants.valueAt(i);
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
        final int callingUserHandle = UserHandle.getCallingUserId();
        if (callingUserHandle != UserHandle.USER_OWNER) {
            // App is running under a non-owner user profile.  For now, we do not back
            // up data from secondary user profiles.
            // TODO: backups for all user profiles.
            if (MORE_DEBUG) {
                Slog.v(TAG, "dataChanged(" + packageName + ") ignored because it's user "
                        + callingUserHandle);
            }
            return;
        }

        final HashSet<String> targets = dataChangedTargets(packageName);
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
        HashSet<String> apps;
        if ((mContext.checkPermission(android.Manifest.permission.BACKUP, Binder.getCallingPid(),
                Binder.getCallingUid())) == PackageManager.PERMISSION_DENIED) {
            apps = mBackupParticipants.get(Binder.getCallingUid());
        } else {
            // a caller with full permission can ask to back up any participating app
            // !!! TODO: allow data-clear of ANY app?
            if (DEBUG) Slog.v(TAG, "Privileged caller, allowing clear of other apps");
            apps = new HashSet<String>();
            int N = mBackupParticipants.size();
            for (int i = 0; i < N; i++) {
                HashSet<String> s = mBackupParticipants.valueAt(i);
                if (s != null) {
                    apps.addAll(s);
                }
            }
        }

        // Is the given app an available participant?
        if (apps.contains(packageName)) {
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

    boolean deviceIsProvisioned() {
        final ContentResolver resolver = mContext.getContentResolver();
        return (Settings.Global.getInt(resolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0);
    }

    // Run a *full* backup pass for the given package, writing the resulting data stream
    // to the supplied file descriptor.  This method is synchronous and does not return
    // to the caller until the backup has been completed.
    public void fullBackup(ParcelFileDescriptor fd, boolean includeApks, boolean includeShared,
            boolean doAllApps, boolean includeSystem, String[] pkgList) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "fullBackup");

        final int callingUserHandle = UserHandle.getCallingUserId();
        if (callingUserHandle != UserHandle.USER_OWNER) {
            throw new IllegalStateException("Backup supported only for the device owner");
        }

        // Validate
        if (!doAllApps) {
            if (!includeShared) {
                // If we're backing up shared data (sdcard or equivalent), then we can run
                // without any supplied app names.  Otherwise, we'd be doing no work, so
                // report the error.
                if (pkgList == null || pkgList.length == 0) {
                    throw new IllegalArgumentException(
                            "Backup requested but neither shared nor any apps named");
                }
            }
        }

        long oldId = Binder.clearCallingIdentity();
        try {
            // Doesn't make sense to do a full backup prior to setup
            if (!deviceIsProvisioned()) {
                Slog.i(TAG, "Full backup not supported before setup");
                return;
            }

            if (DEBUG) Slog.v(TAG, "Requesting full backup: apks=" + includeApks
                    + " shared=" + includeShared + " all=" + doAllApps
                    + " pkgs=" + pkgList);
            Slog.i(TAG, "Beginning full backup...");

            FullBackupParams params = new FullBackupParams(fd, includeApks, includeShared,
                    doAllApps, includeSystem, pkgList);
            final int token = generateToken();
            synchronized (mFullConfirmations) {
                mFullConfirmations.put(token, params);
            }

            // start up the confirmation UI
            if (DEBUG) Slog.d(TAG, "Starting backup confirmation UI, token=" + token);
            if (!startConfirmationUi(token, FullBackup.FULL_BACKUP_INTENT_ACTION)) {
                Slog.e(TAG, "Unable to launch full backup confirmation");
                mFullConfirmations.delete(token);
                return;
            }

            // make sure the screen is lit for the user interaction
            mPowerManager.userActivity(SystemClock.uptimeMillis(), false);

            // start the confirmation countdown
            startConfirmationTimeout(token, params);

            // wait for the backup to be performed
            if (DEBUG) Slog.d(TAG, "Waiting for full backup completion...");
            waitForCompletion(params);
        } finally {
            try {
                fd.close();
            } catch (IOException e) {
                // just eat it
            }
            Binder.restoreCallingIdentity(oldId);
            Slog.d(TAG, "Full backup processing complete.");
        }
    }

    public void fullRestore(ParcelFileDescriptor fd) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "fullRestore");

        final int callingUserHandle = UserHandle.getCallingUserId();
        if (callingUserHandle != UserHandle.USER_OWNER) {
            throw new IllegalStateException("Restore supported only for the device owner");
        }

        long oldId = Binder.clearCallingIdentity();

        try {
            // Check whether the device has been provisioned -- we don't handle
            // full restores prior to completing the setup process.
            if (!deviceIsProvisioned()) {
                Slog.i(TAG, "Full restore not permitted before setup");
                return;
            }

            Slog.i(TAG, "Beginning full restore...");

            FullRestoreParams params = new FullRestoreParams(fd);
            final int token = generateToken();
            synchronized (mFullConfirmations) {
                mFullConfirmations.put(token, params);
            }

            // start up the confirmation UI
            if (DEBUG) Slog.d(TAG, "Starting restore confirmation UI, token=" + token);
            if (!startConfirmationUi(token, FullBackup.FULL_RESTORE_INTENT_ACTION)) {
                Slog.e(TAG, "Unable to launch full restore confirmation");
                mFullConfirmations.delete(token);
                return;
            }

            // make sure the screen is lit for the user interaction
            mPowerManager.userActivity(SystemClock.uptimeMillis(), false);

            // start the confirmation countdown
            startConfirmationTimeout(token, params);

            // wait for the restore to be performed
            if (DEBUG) Slog.d(TAG, "Waiting for full restore completion...");
            waitForCompletion(params);
        } finally {
            try {
                fd.close();
            } catch (IOException e) {
                Slog.w(TAG, "Error trying to close fd after full restore: " + e);
            }
            Binder.restoreCallingIdentity(oldId);
            Slog.i(TAG, "Full restore processing complete.");
        }
    }

    boolean startConfirmationUi(int token, String action) {
        try {
            Intent confIntent = new Intent(action);
            confIntent.setClassName("com.android.backupconfirm",
                    "com.android.backupconfirm.BackupRestoreConfirmation");
            confIntent.putExtra(FullBackup.CONF_TOKEN_INTENT_EXTRA, token);
            confIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(confIntent);
        } catch (ActivityNotFoundException e) {
            return false;
        }
        return true;
    }

    void startConfirmationTimeout(int token, FullParams params) {
        if (MORE_DEBUG) Slog.d(TAG, "Posting conf timeout msg after "
                + TIMEOUT_FULL_CONFIRMATION + " millis");
        Message msg = mBackupHandler.obtainMessage(MSG_FULL_CONFIRMATION_TIMEOUT,
                token, 0, params);
        mBackupHandler.sendMessageDelayed(msg, TIMEOUT_FULL_CONFIRMATION);
    }

    void waitForCompletion(FullParams params) {
        synchronized (params.latch) {
            while (params.latch.get() == false) {
                try {
                    params.latch.wait();
                } catch (InterruptedException e) { /* never interrupted */ }
            }
        }
    }

    void signalFullBackupRestoreCompletion(FullParams params) {
        synchronized (params.latch) {
            params.latch.set(true);
            params.latch.notifyAll();
        }
    }

    // Confirm that the previously-requested full backup/restore operation can proceed.  This
    // is used to require a user-facing disclosure about the operation.
    @Override
    public void acknowledgeFullBackupOrRestore(int token, boolean allow,
            String curPassword, String encPpassword, IFullBackupRestoreObserver observer) {
        if (DEBUG) Slog.d(TAG, "acknowledgeFullBackupOrRestore : token=" + token
                + " allow=" + allow);

        // TODO: possibly require not just this signature-only permission, but even
        // require that the specific designated confirmation-UI app uid is the caller?
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "acknowledgeFullBackupOrRestore");

        long oldId = Binder.clearCallingIdentity();
        try {

            FullParams params;
            synchronized (mFullConfirmations) {
                params = mFullConfirmations.get(token);
                if (params != null) {
                    mBackupHandler.removeMessages(MSG_FULL_CONFIRMATION_TIMEOUT, params);
                    mFullConfirmations.delete(token);

                    if (allow) {
                        final int verb = params instanceof FullBackupParams
                                ? MSG_RUN_FULL_BACKUP
                                : MSG_RUN_FULL_RESTORE;

                        params.observer = observer;
                        params.curPassword = curPassword;

                        boolean isEncrypted;
                        try {
                            isEncrypted = (mMountService.getEncryptionState() != MountService.ENCRYPTION_STATE_NONE);
                            if (isEncrypted) Slog.w(TAG, "Device is encrypted; forcing enc password");
                        } catch (RemoteException e) {
                            // couldn't contact the mount service; fail "safe" and assume encryption
                            Slog.e(TAG, "Unable to contact mount service!");
                            isEncrypted = true;
                        }
                        params.encryptPassword = (isEncrypted) ? curPassword : encPpassword;

                        if (DEBUG) Slog.d(TAG, "Sending conf message with verb " + verb);
                        mWakelock.acquire();
                        Message msg = mBackupHandler.obtainMessage(verb, params);
                        mBackupHandler.sendMessage(msg);
                    } else {
                        Slog.w(TAG, "User rejected full backup/restore operation");
                        // indicate completion without having actually transferred any data
                        signalFullBackupRestoreCompletion(params);
                    }
                } else {
                    Slog.w(TAG, "Attempted to ack full backup/restore with invalid token");
                }
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
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
                "setAutoRestore");

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
        /*
         * This is now a no-op; provisioning is simply the device's own setup state.
         */
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
        if (MORE_DEBUG) Slog.v(TAG, "... getCurrentTransport() returning " + mCurrentTransport);
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

    // Supply the configuration Intent for the given transport.  If the name is not one
    // of the available transports, or if the transport does not supply any configuration
    // UI, the method returns null.
    public Intent getConfigurationIntent(String transportName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getConfigurationIntent");

        synchronized (mTransports) {
            final IBackupTransport transport = mTransports.get(transportName);
            if (transport != null) {
                try {
                    final Intent intent = transport.configurationIntent();
                    if (MORE_DEBUG) Slog.d(TAG, "getConfigurationIntent() returning config intent "
                            + intent);
                    return intent;
                } catch (RemoteException e) {
                    /* fall through to return null */
                }
            }
        }

        return null;
    }

    // Supply the configuration summary string for the given transport.  If the name is
    // not one of the available transports, or if the transport does not supply any
    // summary / destination string, the method can return null.
    //
    // This string is used VERBATIM as the summary text of the relevant Settings item!
    public String getDestinationString(String transportName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getDestinationString");

        synchronized (mTransports) {
            final IBackupTransport transport = mTransports.get(transportName);
            if (transport != null) {
                try {
                    final String text = transport.currentDestinationString();
                    if (MORE_DEBUG) Slog.d(TAG, "getDestinationString() returning " + text);
                    return text;
                } catch (RemoteException e) {
                    /* fall through to return null */
                }
            }
        }

        return null;
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
                + " token=" + Integer.toHexString(token)
                + " restoreSet=" + Long.toHexString(restoreSet));

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
            mBackupHandler.sendEmptyMessageDelayed(MSG_RESTORE_TIMEOUT, TIMEOUT_RESTORE_INTERVAL);
        }
        return mActiveRestoreSession;
    }

    void clearRestoreSession(ActiveRestoreSession currentSession) {
        synchronized(this) {
            if (currentSession != mActiveRestoreSession) {
                Slog.e(TAG, "ending non-current restore session");
            } else {
                if (DEBUG) Slog.v(TAG, "Clearing restore session and halting timeout");
                mActiveRestoreSession = null;
                mBackupHandler.removeMessages(MSG_RESTORE_TIMEOUT);
            }
        }
    }

    // Note that a currently-active backup agent has notified us that it has
    // completed the given outstanding asynchronous backup/restore operation.
    @Override
    public void opComplete(int token) {
        if (MORE_DEBUG) Slog.v(TAG, "opComplete: " + Integer.toHexString(token));
        Operation op = null;
        synchronized (mCurrentOpLock) {
            op = mCurrentOperations.get(token);
            if (op != null) {
                op.state = OP_ACKNOWLEDGED;
            }
            mCurrentOpLock.notifyAll();
        }

        // The completion callback, if any, is invoked on the handler
        if (op != null && op.callback != null) {
            Message msg = mBackupHandler.obtainMessage(MSG_OP_COMPLETE, op.callback);
            mBackupHandler.sendMessage(msg);
        }
    }

    // ----- Restore session -----

    class ActiveRestoreSession extends IRestoreSession.Stub {
        private static final String TAG = "RestoreSession";

        private String mPackageName;
        private IBackupTransport mRestoreTransport = null;
        RestoreSet[] mRestoreSets = null;
        boolean mEnded = false;

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

            if (mEnded) {
                throw new IllegalStateException("Restore session already ended");
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

            if (mEnded) {
                throw new IllegalStateException("Restore session already ended");
            }

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

        public synchronized int restoreSome(long token, IRestoreObserver observer,
                String[] packages) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                    "performRestore");

            if (DEBUG) {
                StringBuilder b = new StringBuilder(128);
                b.append("restoreSome token=");
                b.append(Long.toHexString(token));
                b.append(" observer=");
                b.append(observer.toString());
                b.append(" packages=");
                if (packages == null) {
                    b.append("null");
                } else {
                    b.append('{');
                    boolean first = true;
                    for (String s : packages) {
                        if (!first) {
                            b.append(", ");
                        } else first = false;
                        b.append(s);
                    }
                    b.append('}');
                }
                Slog.d(TAG, b.toString());
            }

            if (mEnded) {
                throw new IllegalStateException("Restore session already ended");
            }

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
                        msg.obj = new RestoreParams(mRestoreTransport, observer, token,
                                packages, true);
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

            if (mEnded) {
                throw new IllegalStateException("Restore session already ended");
            }

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

        // Posted to the handler to tear down a restore session in a cleanly synchronized way
        class EndRestoreRunnable implements Runnable {
            BackupManagerService mBackupManager;
            ActiveRestoreSession mSession;

            EndRestoreRunnable(BackupManagerService manager, ActiveRestoreSession session) {
                mBackupManager = manager;
                mSession = session;
            }

            public void run() {
                // clean up the session's bookkeeping
                synchronized (mSession) {
                    try {
                        if (mSession.mRestoreTransport != null) {
                            mSession.mRestoreTransport.finishRestore();
                        }
                    } catch (Exception e) {
                        Slog.e(TAG, "Error in finishRestore", e);
                    } finally {
                        mSession.mRestoreTransport = null;
                        mSession.mEnded = true;
                    }
                }

                // clean up the BackupManagerService side of the bookkeeping
                // and cancel any pending timeout message
                mBackupManager.clearRestoreSession(mSession);
            }
        }

        public synchronized void endRestoreSession() {
            if (DEBUG) Slog.d(TAG, "endRestoreSession");

            if (mEnded) {
                throw new IllegalStateException("Restore session already ended");
            }

            mBackupHandler.post(new EndRestoreRunnable(BackupManagerService.this, this));
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

        long identityToken = Binder.clearCallingIdentity();
        try {
            dumpInternal(pw);
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
    }

    private void dumpInternal(PrintWriter pw) {
        synchronized (mQueueLock) {
            pw.println("Backup Manager is " + (mEnabled ? "enabled" : "disabled")
                    + " / " + (!mProvisioned ? "not " : "") + "provisioned / "
                    + (this.mPendingInits.size() == 0 ? "not " : "") + "pending init");
            pw.println("Auto-restore is " + (mAutoRestore ? "enabled" : "disabled"));
            if (mBackupRunning) pw.println("Backup currently running");
            pw.println("Last backup pass started: " + mLastBackupPass
                    + " (now = " + System.currentTimeMillis() + ')');
            pw.println("  next scheduled: " + mNextBackupPass);

            pw.println("Available transports:");
            for (String t : listAllTransports()) {
                pw.println((t.equals(mCurrentTransport) ? "  * " : "    ") + t);
                try {
                    IBackupTransport transport = getTransport(t);
                    File dir = new File(mBaseStateDir, transport.transportDirName());
                    pw.println("       destination: " + transport.currentDestinationString());
                    pw.println("       intent: " + transport.configurationIntent());
                    for (File f : dir.listFiles()) {
                        pw.println("       " + f.getName() + " - " + f.length() + " state bytes");
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Error in transport", e);
                    pw.println("        Error: " + e);
                }
            }

            pw.println("Pending init: " + mPendingInits.size());
            for (String s : mPendingInits) {
                pw.println("    " + s);
            }

            if (DEBUG_BACKUP_TRACE) {
                synchronized (mBackupTrace) {
                    if (!mBackupTrace.isEmpty()) {
                        pw.println("Most recent backup trace:");
                        for (String s : mBackupTrace) {
                            pw.println("   " + s);
                        }
                    }
                }
            }

            int N = mBackupParticipants.size();
            pw.println("Participants:");
            for (int i=0; i<N; i++) {
                int uid = mBackupParticipants.keyAt(i);
                pw.print("  uid: ");
                pw.println(uid);
                HashSet<String> participants = mBackupParticipants.valueAt(i);
                for (String app: participants) {
                    pw.println("    " + app);
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
