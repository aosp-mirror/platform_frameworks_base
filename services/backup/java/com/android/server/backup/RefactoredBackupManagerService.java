/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup;

import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_BACKUP_IN_FOREGROUND;
import static com.android.server.backup.internal.BackupHandler.MSG_BACKUP_OPERATION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_FULL_CONFIRMATION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_OP_COMPLETE;
import static com.android.server.backup.internal.BackupHandler.MSG_REQUEST_BACKUP;
import static com.android.server.backup.internal.BackupHandler.MSG_RESTORE_OPERATION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_RESTORE_SESSION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_RETRY_CLEAR;
import static com.android.server.backup.internal.BackupHandler.MSG_RETRY_INIT;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_ADB_BACKUP;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_ADB_RESTORE;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_BACKUP;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_CLEAR;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_RESTORE;
import static com.android.server.backup.internal.BackupHandler.MSG_SCHEDULE_BACKUP_PACKAGE;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IBackupAgent;
import android.app.PendingIntent;
import android.app.backup.BackupManager;
import android.app.backup.BackupManagerMonitor;
import android.app.backup.FullBackup;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.ISelectBackupTransportCallback;
import android.app.backup.SelectBackupTransportCallback;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.DumpUtils;
import com.android.server.AppWidgetBackupBridge;
import com.android.server.EventLogTags;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.backup.fullbackup.FullBackupEntry;
import com.android.server.backup.fullbackup.PerformFullTransportBackupTask;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.backup.internal.BackupRequest;
import com.android.server.backup.internal.ClearDataObserver;
import com.android.server.backup.internal.Operation;
import com.android.server.backup.internal.PerformInitializeTask;
import com.android.server.backup.internal.ProvisionedObserver;
import com.android.server.backup.internal.RunBackupReceiver;
import com.android.server.backup.internal.RunInitializeReceiver;
import com.android.server.backup.params.AdbBackupParams;
import com.android.server.backup.params.AdbParams;
import com.android.server.backup.params.AdbRestoreParams;
import com.android.server.backup.params.BackupParams;
import com.android.server.backup.params.ClearParams;
import com.android.server.backup.params.ClearRetryParams;
import com.android.server.backup.params.RestoreParams;
import com.android.server.backup.restore.ActiveRestoreSession;
import com.android.server.backup.restore.PerformUnifiedRestoreTask;
import com.android.server.backup.utils.AppBackupUtils;
import com.android.server.backup.utils.BackupManagerMonitorUtils;
import com.android.server.backup.utils.BackupObserverUtils;
import com.android.server.backup.utils.SparseArrayUtils;
import com.android.server.power.BatterySaverPolicy.ServiceType;

import com.google.android.collect.Sets;

import java.io.BufferedInputStream;
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
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class RefactoredBackupManagerService implements BackupManagerServiceInterface {

    public static final String TAG = "BackupManagerService";
    public static final boolean DEBUG = true;
    public static final boolean MORE_DEBUG = false;
    public static final boolean DEBUG_SCHEDULING = MORE_DEBUG || true;

    // File containing backup-enabled state.  Contains a single byte;
    // nonzero == enabled.  File missing or contains a zero byte == disabled.
    private static final String BACKUP_ENABLE_FILE = "backup_enabled";

    // System-private key used for backing up an app's widget state.  Must
    // begin with U+FFxx by convention (we reserve all keys starting
    // with U+FF00 or higher for system use).
    public static final String KEY_WIDGET_STATE = "\uffed\uffedwidget";

    // Name and current contents version of the full-backup manifest file
    //
    // Manifest version history:
    //
    // 1 : initial release
    public static final String BACKUP_MANIFEST_FILENAME = "_manifest";
    public static final int BACKUP_MANIFEST_VERSION = 1;

    // External archive format version history:
    //
    // 1 : initial release
    // 2 : no format change per se; version bump to facilitate PBKDF2 version skew detection
    // 3 : introduced "_meta" metadata file; no other format change per se
    // 4 : added support for new device-encrypted storage locations
    // 5 : added support for key-value packages
    public static final int BACKUP_FILE_VERSION = 5;
    public static final String BACKUP_FILE_HEADER_MAGIC = "ANDROID BACKUP\n";
    public static final String BACKUP_METADATA_FILENAME = "_meta";
    public static final int BACKUP_METADATA_VERSION = 1;
    public static final int BACKUP_WIDGET_METADATA_TOKEN = 0x01FFED01;

    private static final boolean COMPRESS_FULL_BACKUPS = true; // should be true in production

    public static final String SETTINGS_PACKAGE = "com.android.providers.settings";
    public static final String SHARED_BACKUP_AGENT_PACKAGE = "com.android.sharedstoragebackup";
    private static final String SERVICE_ACTION_TRANSPORT_HOST = "android.backup.TRANSPORT_HOST";

    // Retry interval for clear/init when the transport is unavailable
    private static final long TRANSPORT_RETRY_INTERVAL = 1 * AlarmManager.INTERVAL_HOUR;

    public static final String RUN_BACKUP_ACTION = "android.app.backup.intent.RUN";
    public static final String RUN_INITIALIZE_ACTION = "android.app.backup.intent.INIT";

    // Timeout interval for deciding that a bind or clear-data has taken too long
    private static final long TIMEOUT_INTERVAL = 10 * 1000;

    // Timeout intervals for agent backup & restore operations
    public static final long TIMEOUT_BACKUP_INTERVAL = 30 * 1000;
    public static final long TIMEOUT_FULL_BACKUP_INTERVAL = 5 * 60 * 1000;
    public static final long TIMEOUT_SHARED_BACKUP_INTERVAL = 30 * 60 * 1000;
    public static final long TIMEOUT_RESTORE_INTERVAL = 60 * 1000;
    public static final long TIMEOUT_RESTORE_FINISHED_INTERVAL = 30 * 1000;

    // User confirmation timeout for a full backup/restore operation.  It's this long in
    // order to give them time to enter the backup password.
    private static final long TIMEOUT_FULL_CONFIRMATION = 60 * 1000;

    // How long between attempts to perform a full-data backup of any given app
    private static final long MIN_FULL_BACKUP_INTERVAL = 1000 * 60 * 60 * 24; // one day

    // If an app is busy when we want to do a full-data backup, how long to defer the retry.
    // This is fuzzed, so there are two parameters; backoff_min + Rand[0, backoff_fuzz)
    private static final long BUSY_BACKOFF_MIN_MILLIS = 1000 * 60 * 60;  // one hour
    private static final int BUSY_BACKOFF_FUZZ = 1000 * 60 * 60 * 2;  // two hours

    private Context mContext;
    private PackageManager mPackageManager;
    private IPackageManager mPackageManagerBinder;
    private IActivityManager mActivityManager;
    private PowerManager mPowerManager;
    private AlarmManager mAlarmManager;
    private IStorageManager mStorageManager;

    private IBackupManager mBackupManagerBinder;

    private final TransportManager mTransportManager;

    private boolean mEnabled;   // access to this is synchronized on 'this'
    private boolean mProvisioned;
    private boolean mAutoRestore;
    private PowerManager.WakeLock mWakelock;
    private HandlerThread mHandlerThread;
    private BackupHandler mBackupHandler;
    private PendingIntent mRunBackupIntent;
    private PendingIntent mRunInitIntent;
    private BroadcastReceiver mRunBackupReceiver;
    private BroadcastReceiver mRunInitReceiver;
    // map UIDs to the set of participating packages under that UID
    private final SparseArray<HashSet<String>> mBackupParticipants
            = new SparseArray<>();

    // Backups that we haven't started yet.  Keys are package names.
    private HashMap<String, BackupRequest> mPendingBackups
            = new HashMap<>();

    // Pseudoname that we use for the Package Manager metadata "package"
    public static final String PACKAGE_MANAGER_SENTINEL = "@pm@";

    // locking around the pending-backup management
    private final Object mQueueLock = new Object();

    // The thread performing the sequence of queued backups binds to each app's agent
    // in succession.  Bind notifications are asynchronously delivered through the
    // Activity Manager; use this lock object to signal when a requested binding has
    // completed.
    private final Object mAgentConnectLock = new Object();
    private IBackupAgent mConnectedAgent;
    private volatile boolean mBackupRunning;
    private volatile boolean mConnecting;
    private volatile long mLastBackupPass;

    // For debugging, we maintain a progress trace of operations during backup
    public static final boolean DEBUG_BACKUP_TRACE = true;
    private final List<String> mBackupTrace = new ArrayList<>();

    // A similar synchronization mechanism around clearing apps' data for restore
    private final Object mClearDataLock = new Object();
    private volatile boolean mClearingData;

    private final BackupPasswordManager mBackupPasswordManager;

    @GuardedBy("mPendingRestores")
    private boolean mIsRestoreInProgress;
    @GuardedBy("mPendingRestores")
    private final Queue<PerformUnifiedRestoreTask> mPendingRestores = new ArrayDeque<>();

    private ActiveRestoreSession mActiveRestoreSession;

    // Watch the device provisioning operation during setup
    private ContentObserver mProvisionedObserver;

    // The published binder is actually to a singleton trampoline object that calls
    // through to the proper code.  This indirection lets us turn down the heavy
    // implementation object on the fly without disturbing binders that have been
    // cached elsewhere in the system.
    static Trampoline sInstance;

    static Trampoline getInstance() {
        // Always constructed during system bringup, so no need to lazy-init
        return sInstance;
    }

    public Context getContext() {
        return mContext;
    }

    public void setContext(Context context) {
        mContext = context;
    }

    public PackageManager getPackageManager() {
        return mPackageManager;
    }

    public void setPackageManager(PackageManager packageManager) {
        mPackageManager = packageManager;
    }

    public IPackageManager getPackageManagerBinder() {
        return mPackageManagerBinder;
    }

    public void setPackageManagerBinder(IPackageManager packageManagerBinder) {
        mPackageManagerBinder = packageManagerBinder;
    }

    public IActivityManager getActivityManager() {
        return mActivityManager;
    }

    public void setActivityManager(IActivityManager activityManager) {
        mActivityManager = activityManager;
    }

    public AlarmManager getAlarmManager() {
        return mAlarmManager;
    }

    public void setAlarmManager(AlarmManager alarmManager) {
        mAlarmManager = alarmManager;
    }

    public void setBackupManagerBinder(IBackupManager backupManagerBinder) {
        mBackupManagerBinder = backupManagerBinder;
    }

    public TransportManager getTransportManager() {
        return mTransportManager;
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    public boolean isProvisioned() {
        return mProvisioned;
    }

    public void setProvisioned(boolean provisioned) {
        mProvisioned = provisioned;
    }

    public PowerManager.WakeLock getWakelock() {
        return mWakelock;
    }

    public void setWakelock(PowerManager.WakeLock wakelock) {
        mWakelock = wakelock;
    }

    public BackupHandler getBackupHandler() {
        return mBackupHandler;
    }

    public void setBackupHandler(BackupHandler backupHandler) {
        mBackupHandler = backupHandler;
    }

    public PendingIntent getRunInitIntent() {
        return mRunInitIntent;
    }

    public void setRunInitIntent(PendingIntent runInitIntent) {
        mRunInitIntent = runInitIntent;
    }

    public HashMap<String, BackupRequest> getPendingBackups() {
        return mPendingBackups;
    }

    public void setPendingBackups(
            HashMap<String, BackupRequest> pendingBackups) {
        mPendingBackups = pendingBackups;
    }

    public Object getQueueLock() {
        return mQueueLock;
    }

    public boolean isBackupRunning() {
        return mBackupRunning;
    }

    public void setBackupRunning(boolean backupRunning) {
        mBackupRunning = backupRunning;
    }

    public long getLastBackupPass() {
        return mLastBackupPass;
    }

    public void setLastBackupPass(long lastBackupPass) {
        mLastBackupPass = lastBackupPass;
    }

    public Object getClearDataLock() {
        return mClearDataLock;
    }

    public boolean isClearingData() {
        return mClearingData;
    }

    public void setClearingData(boolean clearingData) {
        mClearingData = clearingData;
    }

    public boolean isRestoreInProgress() {
        return mIsRestoreInProgress;
    }

    public void setRestoreInProgress(boolean restoreInProgress) {
        mIsRestoreInProgress = restoreInProgress;
    }

    public Queue<PerformUnifiedRestoreTask> getPendingRestores() {
        return mPendingRestores;
    }

    public ActiveRestoreSession getActiveRestoreSession() {
        return mActiveRestoreSession;
    }

    public void setActiveRestoreSession(
            ActiveRestoreSession activeRestoreSession) {
        mActiveRestoreSession = activeRestoreSession;
    }

    public SparseArray<Operation> getCurrentOperations() {
        return mCurrentOperations;
    }

    public Object getCurrentOpLock() {
        return mCurrentOpLock;
    }

    public SparseArray<AdbParams> getAdbBackupRestoreConfirmations() {
        return mAdbBackupRestoreConfirmations;
    }

    public File getBaseStateDir() {
        return mBaseStateDir;
    }

    public void setBaseStateDir(File baseStateDir) {
        mBaseStateDir = baseStateDir;
    }

    public File getDataDir() {
        return mDataDir;
    }

    public void setDataDir(File dataDir) {
        mDataDir = dataDir;
    }

    public DataChangedJournal getJournal() {
        return mJournal;
    }

    public void setJournal(@Nullable DataChangedJournal journal) {
        mJournal = journal;
    }

    public SecureRandom getRng() {
        return mRng;
    }

    public Set<String> getAncestralPackages() {
        return mAncestralPackages;
    }

    public void setAncestralPackages(Set<String> ancestralPackages) {
        mAncestralPackages = ancestralPackages;
    }

    public long getAncestralToken() {
        return mAncestralToken;
    }

    public void setAncestralToken(long ancestralToken) {
        mAncestralToken = ancestralToken;
    }

    public long getCurrentToken() {
        return mCurrentToken;
    }

    public void setCurrentToken(long currentToken) {
        mCurrentToken = currentToken;
    }

    public ArraySet<String> getPendingInits() {
        return mPendingInits;
    }

    public void clearPendingInits() {
        mPendingInits.clear();
    }

    public PerformFullTransportBackupTask getRunningFullBackupTask() {
        return mRunningFullBackupTask;
    }

    public void setRunningFullBackupTask(
            PerformFullTransportBackupTask runningFullBackupTask) {
        mRunningFullBackupTask = runningFullBackupTask;
    }

    public static final class Lifecycle extends SystemService {

        public Lifecycle(Context context) {
            super(context);
            sInstance = new Trampoline(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.BACKUP_SERVICE, sInstance);
        }

        @Override
        public void onUnlockUser(int userId) {
            if (userId == UserHandle.USER_SYSTEM) {
                sInstance.initialize(userId);

                // Migrate legacy setting
                if (!backupSettingMigrated(userId)) {
                    if (DEBUG) {
                        Slog.i(TAG, "Backup enable apparently not migrated");
                    }
                    final ContentResolver r = sInstance.mContext.getContentResolver();
                    final int enableState = Settings.Secure.getIntForUser(r,
                            Settings.Secure.BACKUP_ENABLED, -1, userId);
                    if (enableState >= 0) {
                        if (DEBUG) {
                            Slog.i(TAG, "Migrating enable state " + (enableState != 0));
                        }
                        writeBackupEnableState(enableState != 0, userId);
                        Settings.Secure.putStringForUser(r,
                                Settings.Secure.BACKUP_ENABLED, null, userId);
                    } else {
                        if (DEBUG) {
                            Slog.i(TAG, "Backup not yet configured; retaining null enable state");
                        }
                    }
                }

                try {
                    sInstance.setBackupEnabled(readBackupEnableState(userId));
                } catch (RemoteException e) {
                    // can't happen; it's a local object
                }
            }
        }
    }

    // Bookkeeping of in-flight operations for timeout etc. purposes.  The operation
    // token is the index of the entry in the pending-operations list.
    public static final int OP_PENDING = 0;
    private static final int OP_ACKNOWLEDGED = 1;
    private static final int OP_TIMEOUT = -1;

    // Waiting for backup agent to respond during backup operation.
    public static final int OP_TYPE_BACKUP_WAIT = 0;

    // Waiting for backup agent to respond during restore operation.
    public static final int OP_TYPE_RESTORE_WAIT = 1;

    // An entire backup operation spanning multiple packages.
    public static final int OP_TYPE_BACKUP = 2;

    /**
     * mCurrentOperations contains the list of currently active operations.
     *
     * If type of operation is OP_TYPE_WAIT, it are waiting for an ack or timeout.
     * An operation wraps a BackupRestoreTask within it.
     * It's the responsibility of this task to remove the operation from this array.
     *
     * A BackupRestore task gets notified of ack/timeout for the operation via
     * BackupRestoreTask#handleCancel, BackupRestoreTask#operationComplete and notifyAll called
     * on the mCurrentOpLock.
     * {@link RefactoredBackupManagerService#waitUntilOperationComplete(int)} is
     * used in various places to 'wait' for notifyAll and detect change of pending state of an
     * operation. So typically, an operation will be removed from this array by:
     *   - BackupRestoreTask#handleCancel and
     *   - BackupRestoreTask#operationComplete OR waitUntilOperationComplete. Do not remove at both
     *     these places because waitUntilOperationComplete relies on the operation being present to
     *     determine its completion status.
     *
     * If type of operation is OP_BACKUP, it is a task running backups. It provides a handle to
     * cancel backup tasks.
     */
    @GuardedBy("mCurrentOpLock")
    private final SparseArray<Operation> mCurrentOperations = new SparseArray<>();
    private final Object mCurrentOpLock = new Object();
    private final Random mTokenGenerator = new Random();

    private final SparseArray<AdbParams> mAdbBackupRestoreConfirmations = new SparseArray<>();

    // Where we keep our journal files and other bookkeeping
    private File mBaseStateDir;
    private File mDataDir;
    private File mJournalDir;
    @Nullable private DataChangedJournal mJournal;

    private final SecureRandom mRng = new SecureRandom();

    // Keep a log of all the apps we've ever backed up, and what the
    // dataset tokens are for both the current backup dataset and
    // the ancestral dataset.
    private File mEverStored;
    private HashSet<String> mEverStoredApps = new HashSet<>();

    private static final int CURRENT_ANCESTRAL_RECORD_VERSION = 1;
    // increment when the schema changes
    private File mTokenFile;
    private Set<String> mAncestralPackages = null;
    private long mAncestralToken = 0;
    private long mCurrentToken = 0;

    // Persistently track the need to do a full init
    private static final String INIT_SENTINEL_FILE_NAME = "_need_init_";
    private ArraySet<String> mPendingInits = new ArraySet<>();  // transport names

    // Round-robin queue for scheduling full backup passes
    private static final int SCHEDULE_FILE_VERSION = 1; // current version of the schedule file

    private File mFullBackupScheduleFile;
    // If we're running a schedule-driven full backup, this is the task instance doing it

    @GuardedBy("mQueueLock")
    private PerformFullTransportBackupTask mRunningFullBackupTask;

    @GuardedBy("mQueueLock")
    private ArrayList<FullBackupEntry> mFullBackupQueue;

    // Utility: build a new random integer token
    @Override
    public int generateRandomIntegerToken() {
        int token;
        do {
            synchronized (mTokenGenerator) {
                token = mTokenGenerator.nextInt();
            }
        } while (token < 0);
        return token;
    }

    // ----- Debug-only backup operation trace -----
    public void addBackupTrace(String s) {
        if (DEBUG_BACKUP_TRACE) {
            synchronized (mBackupTrace) {
                mBackupTrace.add(s);
            }
        }
    }

    public void clearBackupTrace() {
        if (DEBUG_BACKUP_TRACE) {
            synchronized (mBackupTrace) {
                mBackupTrace.clear();
            }
        }
    }

    // ----- Main service implementation -----

    public RefactoredBackupManagerService(Context context, Trampoline parent) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mPackageManagerBinder = AppGlobals.getPackageManager();
        mActivityManager = ActivityManager.getService();

        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mStorageManager = IStorageManager.Stub.asInterface(ServiceManager.getService("mount"));

        mBackupManagerBinder = Trampoline.asInterface(parent.asBinder());

        // spin up the backup/restore handler thread
        mHandlerThread = new HandlerThread("backup", Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        mBackupHandler = new BackupHandler(this, mHandlerThread.getLooper());

        // Set up our bookkeeping
        final ContentResolver resolver = context.getContentResolver();
        mProvisioned = Settings.Global.getInt(resolver,
                Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        mAutoRestore = Settings.Secure.getInt(resolver,
                Settings.Secure.BACKUP_AUTO_RESTORE, 1) != 0;

        mProvisionedObserver = new ProvisionedObserver(this, mBackupHandler);
        resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED),
                false, mProvisionedObserver);

        // If Encrypted file systems is enabled or disabled, this call will return the
        // correct directory.
        mBaseStateDir = new File(Environment.getDataDirectory(), "backup");
        mBaseStateDir.mkdirs();
        if (!SELinux.restorecon(mBaseStateDir)) {
            Slog.e(TAG, "SELinux restorecon failed on " + mBaseStateDir);
        }

        // This dir on /cache is managed directly in init.rc
        mDataDir = new File(Environment.getDownloadCacheDirectory(), "backup_stage");

        mBackupPasswordManager = new BackupPasswordManager(mContext, mBaseStateDir, mRng);

        // Alarm receivers for scheduled backups & initialization operations
        mRunBackupReceiver = new RunBackupReceiver(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(RUN_BACKUP_ACTION);
        context.registerReceiver(mRunBackupReceiver, filter,
                android.Manifest.permission.BACKUP, null);

        mRunInitReceiver = new RunInitializeReceiver(this);
        filter = new IntentFilter();
        filter.addAction(RUN_INITIALIZE_ACTION);
        context.registerReceiver(mRunInitReceiver, filter,
                android.Manifest.permission.BACKUP, null);

        Intent backupIntent = new Intent(RUN_BACKUP_ACTION);
        backupIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mRunBackupIntent = PendingIntent.getBroadcast(context, 0, backupIntent, 0);

        Intent initIntent = new Intent(RUN_INITIALIZE_ACTION);
        initIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mRunInitIntent = PendingIntent.getBroadcast(context, 0, initIntent, 0);

        // Set up the backup-request journaling
        mJournalDir = new File(mBaseStateDir, "pending");
        mJournalDir.mkdirs();   // creates mBaseStateDir along the way
        mJournal = null;        // will be created on first use

        // Set up the various sorts of package tracking we do
        mFullBackupScheduleFile = new File(mBaseStateDir, "fb-schedule");
        initPackageTracking();

        // Build our mapping of uid to backup client services.  This implicitly
        // schedules a backup pass on the Package Manager metadata the first
        // time anything needs to be backed up.
        synchronized (mBackupParticipants) {
            addPackageParticipantsLocked(null);
        }

        // Set up our transport options and initialize the default transport
        // TODO: Don't create transports that we don't need to?
        SystemConfig systemConfig = SystemConfig.getInstance();
        Set<ComponentName> transportWhitelist = systemConfig.getBackupTransportWhitelist();

        String transport = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.BACKUP_TRANSPORT);
        if (TextUtils.isEmpty(transport)) {
            transport = null;
        }
        String currentTransport = transport;
        if (DEBUG) Slog.v(TAG, "Starting with transport " + currentTransport);

        mTransportManager = new TransportManager(context, transportWhitelist, currentTransport,
                mTransportBoundListener, mHandlerThread.getLooper());
        mTransportManager.registerAllTransports();

        // Now that we know about valid backup participants, parse any
        // leftover journal files into the pending backup set
        mBackupHandler.post(() -> parseLeftoverJournals());

        // Power management
        mWakelock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*backup*");
    }

    private void initPackageTracking() {
        if (MORE_DEBUG) Slog.v(TAG, "` tracking");

        // Remember our ancestral dataset
        mTokenFile = new File(mBaseStateDir, "ancestral");
        try (RandomAccessFile tf = new RandomAccessFile(mTokenFile, "r")) {
            int version = tf.readInt();
            if (version == CURRENT_ANCESTRAL_RECORD_VERSION) {
                mAncestralToken = tf.readLong();
                mCurrentToken = tf.readLong();

                int numPackages = tf.readInt();
                if (numPackages >= 0) {
                    mAncestralPackages = new HashSet<>();
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
            try (RandomAccessFile temp = new RandomAccessFile(tempProcessedFile, "rws");
                 RandomAccessFile in = new RandomAccessFile(mEverStored, "r")) {
                // Loop until we hit EOF
                while (true) {
                    String pkg = in.readUTF();
                    try {
                        // is this package still present?
                        mPackageManager.getPackageInfo(pkg, 0);
                        // if we get here then yes it is; remember it
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
            }
        }

        synchronized (mQueueLock) {
            // Resume the full-data backup queue
            mFullBackupQueue = readFullBackupSchedule();
        }

        // Register for broadcasts about package install, etc., so we can
        // update the provider list.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);
        // Register for events related to sdcard installation.
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiver(mBroadcastReceiver, sdFilter);
    }

    private ArrayList<FullBackupEntry> readFullBackupSchedule() {
        boolean changed = false;
        ArrayList<FullBackupEntry> schedule = null;
        List<PackageInfo> apps =
                PackageManagerBackupAgent.getStorableApplications(mPackageManager);

        if (mFullBackupScheduleFile.exists()) {
            try (FileInputStream fstream = new FileInputStream(mFullBackupScheduleFile);
                 BufferedInputStream bufStream = new BufferedInputStream(fstream);
                 DataInputStream in = new DataInputStream(bufStream)) {
                int version = in.readInt();
                if (version != SCHEDULE_FILE_VERSION) {
                    Slog.e(TAG, "Unknown backup schedule version " + version);
                    return null;
                }

                final int N = in.readInt();
                schedule = new ArrayList<>(N);

                // HashSet instead of ArraySet specifically because we want the eventual
                // lookups against O(hundreds) of entries to be as fast as possible, and
                // we discard the set immediately after the scan so the extra memory
                // overhead is transient.
                HashSet<String> foundApps = new HashSet<>(N);

                for (int i = 0; i < N; i++) {
                    String pkgName = in.readUTF();
                    long lastBackup = in.readLong();
                    foundApps.add(pkgName); // all apps that we've addressed already
                    try {
                        PackageInfo pkg = mPackageManager.getPackageInfo(pkgName, 0);
                        if (AppBackupUtils.appGetsFullBackup(pkg)
                                && AppBackupUtils.appIsEligibleForBackup(
                                pkg.applicationInfo)) {
                            schedule.add(new FullBackupEntry(pkgName, lastBackup));
                        } else {
                            if (DEBUG) {
                                Slog.i(TAG, "Package " + pkgName
                                        + " no longer eligible for full backup");
                            }
                        }
                    } catch (NameNotFoundException e) {
                        if (DEBUG) {
                            Slog.i(TAG, "Package " + pkgName
                                    + " not installed; dropping from full backup");
                        }
                    }
                }

                // New apps can arrive "out of band" via OTA and similar, so we also need to
                // scan to make sure that we're tracking all full-backup candidates properly
                for (PackageInfo app : apps) {
                    if (AppBackupUtils.appGetsFullBackup(app)
                            && AppBackupUtils.appIsEligibleForBackup(
                            app.applicationInfo)) {
                        if (!foundApps.contains(app.packageName)) {
                            if (MORE_DEBUG) {
                                Slog.i(TAG, "New full backup app " + app.packageName + " found");
                            }
                            schedule.add(new FullBackupEntry(app.packageName, 0));
                            changed = true;
                        }
                    }
                }

                Collections.sort(schedule);
            } catch (Exception e) {
                Slog.e(TAG, "Unable to read backup schedule", e);
                mFullBackupScheduleFile.delete();
                schedule = null;
            }
        }

        if (schedule == null) {
            // no prior queue record, or unable to read it.  Set up the queue
            // from scratch.
            changed = true;
            schedule = new ArrayList<>(apps.size());
            for (PackageInfo info : apps) {
                if (AppBackupUtils.appGetsFullBackup(info) && AppBackupUtils.appIsEligibleForBackup(
                        info.applicationInfo)) {
                    schedule.add(new FullBackupEntry(info.packageName, 0));
                }
            }
        }

        if (changed) {
            writeFullBackupScheduleAsync();
        }
        return schedule;
    }

    private Runnable mFullBackupScheduleWriter = new Runnable() {
        @Override
        public void run() {
            synchronized (mQueueLock) {
                try {
                    ByteArrayOutputStream bufStream = new ByteArrayOutputStream(4096);
                    DataOutputStream bufOut = new DataOutputStream(bufStream);
                    bufOut.writeInt(SCHEDULE_FILE_VERSION);

                    // version 1:
                    //
                    // [int] # of packages in the queue = N
                    // N * {
                    //     [utf8] package name
                    //     [long] last backup time for this package
                    //     }
                    int N = mFullBackupQueue.size();
                    bufOut.writeInt(N);

                    for (int i = 0; i < N; i++) {
                        FullBackupEntry entry = mFullBackupQueue.get(i);
                        bufOut.writeUTF(entry.packageName);
                        bufOut.writeLong(entry.lastBackup);
                    }
                    bufOut.flush();

                    AtomicFile af = new AtomicFile(mFullBackupScheduleFile);
                    FileOutputStream out = af.startWrite();
                    out.write(bufStream.toByteArray());
                    af.finishWrite(out);
                } catch (Exception e) {
                    Slog.e(TAG, "Unable to write backup schedule!", e);
                }
            }
        }
    };

    private void writeFullBackupScheduleAsync() {
        mBackupHandler.removeCallbacks(mFullBackupScheduleWriter);
        mBackupHandler.post(mFullBackupScheduleWriter);
    }

    private void parseLeftoverJournals() {
        ArrayList<DataChangedJournal> journals = DataChangedJournal.listJournals(mJournalDir);
        for (DataChangedJournal journal : journals) {
            if (!journal.equals(mJournal)) {
                try {
                    journal.forEach(packageName -> {
                        Slog.i(TAG, "Found stale backup journal, scheduling");
                        if (MORE_DEBUG) Slog.i(TAG, "  " + packageName);
                        dataChangedImpl(packageName);
                    });
                } catch (IOException e) {
                    Slog.e(TAG, "Can't read " + journal, e);
                }
            }
        }
    }

    // Used for generating random salts or passwords
    public byte[] randomBytes(int bits) {
        byte[] array = new byte[bits / 8];
        mRng.nextBytes(array);
        return array;
    }

    @Override
    public boolean setBackupPassword(String currentPw, String newPw) {
        return mBackupPasswordManager.setBackupPassword(currentPw, newPw);
    }

    @Override
    public boolean hasBackupPassword() {
        return mBackupPasswordManager.hasBackupPassword();
    }

    public boolean backupPasswordMatches(String currentPw) {
        return mBackupPasswordManager.backupPasswordMatches(currentPw);
    }

    // Maintain persistent state around whether need to do an initialize operation.
    // Must be called with the queue lock held.
    public void recordInitPendingLocked(boolean isPending, String transportName) {
        if (MORE_DEBUG) {
            Slog.i(TAG, "recordInitPendingLocked: " + isPending
                    + " on transport " + transportName);
        }
        mBackupHandler.removeMessages(MSG_RETRY_INIT);

        try {
            IBackupTransport transport = mTransportManager.getTransportBinder(transportName);
            if (transport != null) {
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
                return; // done; don't fall through to the error case
            }
        } catch (Exception e) {
            // transport threw when asked its name; fall through to the lookup-failed case
            Slog.e(TAG, "Transport " + transportName + " failed to report name: "
                    + e.getMessage());
        }

        // The named transport doesn't exist or threw.  This operation is
        // important, so we record the need for a an init and post a message
        // to retry the init later.
        if (isPending) {
            mPendingInits.add(transportName);
            mBackupHandler.sendMessageDelayed(
                    mBackupHandler.obtainMessage(MSG_RETRY_INIT,
                            (isPending ? 1 : 0),
                            0,
                            transportName),
                    TRANSPORT_RETRY_INTERVAL);
        }
    }

    // Reset all of our bookkeeping, in response to having been told that
    // the backend data has been wiped [due to idle expiry, for example],
    // so we must re-upload all saved settings.
    public void resetBackupState(File stateFileDir) {
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
            for (int i = 0; i < N; i++) {
                HashSet<String> participants = mBackupParticipants.valueAt(i);
                if (participants != null) {
                    for (String packageName : participants) {
                        dataChangedImpl(packageName);
                    }
                }
            }
        }
    }

    private TransportManager.TransportBoundListener mTransportBoundListener =
            new TransportManager.TransportBoundListener() {
                @Override
                public boolean onTransportBound(IBackupTransport transport) {
                    // If the init sentinel file exists, we need to be sure to perform the init
                    // as soon as practical.  We also create the state directory at registration
                    // time to ensure it's present from the outset.
                    String name = null;
                    try {
                        name = transport.name();
                        String transportDirName = transport.transportDirName();
                        File stateDir = new File(mBaseStateDir, transportDirName);
                        stateDir.mkdirs();

                        File initSentinel = new File(stateDir, INIT_SENTINEL_FILE_NAME);
                        if (initSentinel.exists()) {
                            synchronized (mQueueLock) {
                                mPendingInits.add(name);

                                // TODO: pick a better starting time than now + 1 minute
                                long delay = 1000 * 60; // one minute, in milliseconds
                                mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                                        System.currentTimeMillis() + delay, mRunInitIntent);
                            }
                        }
                        return true;
                    } catch (Exception e) {
                        // the transport threw when asked its file naming prefs; declare it invalid
                        Slog.w(TAG, "Failed to regiser transport: " + name);
                        return false;
                    }
                }
            };

    // ----- Track installation/removal of packages -----
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (MORE_DEBUG) Slog.d(TAG, "Received broadcast " + intent);

            String action = intent.getAction();
            boolean replacing = false;
            boolean added = false;
            boolean changed = false;
            Bundle extras = intent.getExtras();
            String pkgList[] = null;
            if (Intent.ACTION_PACKAGE_ADDED.equals(action) ||
                    Intent.ACTION_PACKAGE_REMOVED.equals(action) ||
                    Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                Uri uri = intent.getData();
                if (uri == null) {
                    return;
                }
                final String pkgName = uri.getSchemeSpecificPart();
                if (pkgName != null) {
                    pkgList = new String[]{pkgName};
                }
                changed = Intent.ACTION_PACKAGE_CHANGED.equals(action);

                // At package-changed we only care about looking at new transport states
                if (changed) {
                    final String[] components =
                            intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);

                    if (MORE_DEBUG) {
                        Slog.i(TAG, "Package " + pkgName + " changed; rechecking");
                        for (int i = 0; i < components.length; i++) {
                            Slog.i(TAG, "   * " + components[i]);
                        }
                    }

                    mBackupHandler.post(
                            () -> mTransportManager.onPackageChanged(pkgName, components));
                    return; // nothing more to do in the PACKAGE_CHANGED case
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
                        // under the old uid and fall through to re-add.  If an app
                        // just added key/value backup participation, this picks it up
                        // as a known participant.
                        removePackageParticipantsLocked(pkgList, uid);
                    }
                    addPackageParticipantsLocked(pkgList);
                }
                // If they're full-backup candidates, add them there instead
                final long now = System.currentTimeMillis();
                for (final String packageName : pkgList) {
                    try {
                        PackageInfo app = mPackageManager.getPackageInfo(packageName, 0);
                        if (AppBackupUtils.appGetsFullBackup(app)
                                && AppBackupUtils.appIsEligibleForBackup(
                                app.applicationInfo)) {
                            enqueueFullBackup(packageName, now);
                            scheduleNextFullBackupJob(0);
                        } else {
                            // The app might have just transitioned out of full-data into
                            // doing key/value backups, or might have just disabled backups
                            // entirely.  Make sure it is no longer in the full-data queue.
                            synchronized (mQueueLock) {
                                dequeueFullBackupLocked(packageName);
                            }
                            writeFullBackupScheduleAsync();
                        }

                        mBackupHandler.post(
                                () -> mTransportManager.onPackageAdded(packageName));

                    } catch (NameNotFoundException e) {
                        // doesn't really exist; ignore it
                        if (DEBUG) {
                            Slog.w(TAG, "Can't resolve new app " + packageName);
                        }
                    }
                }

                // Whenever a package is added or updated we need to update
                // the package metadata bookkeeping.
                dataChangedImpl(PACKAGE_MANAGER_SENTINEL);
            } else {
                if (replacing) {
                    // The package is being updated.  We'll receive a PACKAGE_ADDED shortly.
                } else {
                    // Outright removal.  In the full-data case, the app will be dropped
                    // from the queue when its (now obsolete) name comes up again for
                    // backup.
                    synchronized (mBackupParticipants) {
                        removePackageParticipantsLocked(pkgList, uid);
                    }
                }
                for (final String pkgName : pkgList) {
                    mBackupHandler.post(
                            () -> mTransportManager.onPackageRemoved(pkgName));
                }
            }
        }
    };

    // Add the backup agents in the given packages to our set of known backup participants.
    // If 'packageNames' is null, adds all backup agents in the whole system.
    private void addPackageParticipantsLocked(String[] packageNames) {
        // Look for apps that define the android:backupAgent attribute
        List<PackageInfo> targetApps = allAgentPackages();
        if (packageNames != null) {
            if (MORE_DEBUG) Slog.v(TAG, "addPackageParticipantsLocked: #" + packageNames.length);
            for (String packageName : packageNames) {
                addPackageParticipantsLockedInner(packageName, targetApps);
            }
        } else {
            if (MORE_DEBUG) Slog.v(TAG, "addPackageParticipantsLocked: all");
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
                    set = new HashSet<>();
                    mBackupParticipants.put(uid, set);
                }
                set.add(pkg.packageName);
                if (MORE_DEBUG) Slog.v(TAG, "Agent found; added");

                // Schedule a backup for it on general principles
                if (MORE_DEBUG) Slog.i(TAG, "Scheduling backup for new app " + pkg.packageName);
                Message msg = mBackupHandler
                        .obtainMessage(MSG_SCHEDULE_BACKUP_PACKAGE, pkg.packageName);
                mBackupHandler.sendMessage(msg);
            }
        }
    }

    // Remove the given packages' entries from our known active set.
    private void removePackageParticipantsLocked(String[] packageNames, int oldUid) {
        if (packageNames == null) {
            Slog.w(TAG, "removePackageParticipants with null list");
            return;
        }

        if (MORE_DEBUG) {
            Slog.v(TAG, "removePackageParticipantsLocked: uid=" + oldUid
                    + " #" + packageNames.length);
        }
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
    private List<PackageInfo> allAgentPackages() {
        // !!! TODO: cache this and regenerate only when necessary
        int flags = PackageManager.GET_SIGNATURES;
        List<PackageInfo> packages = mPackageManager.getInstalledPackages(flags);
        int N = packages.size();
        for (int a = N - 1; a >= 0; a--) {
            PackageInfo pkg = packages.get(a);
            try {
                ApplicationInfo app = pkg.applicationInfo;
                if (((app.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) == 0)
                        || app.backupAgentName == null
                        || (app.flags & ApplicationInfo.FLAG_FULL_BACKUP_ONLY) != 0) {
                    packages.remove(a);
                } else {
                    // we will need the shared library path, so look that up and store it here.
                    // This is used implicitly when we pass the PackageInfo object off to
                    // the Activity Manager to launch the app for backup/restore purposes.
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

    // Called from the backup tasks: record that the given app has been successfully
    // backed up at least once.  This includes both key/value and full-data backups
    // through the transport.
    public void logBackupComplete(String packageName) {
        if (packageName.equals(PACKAGE_MANAGER_SENTINEL)) return;

        synchronized (mEverStoredApps) {
            if (!mEverStoredApps.add(packageName)) return;

            try (RandomAccessFile out = new RandomAccessFile(mEverStored, "rws")) {
                out.seek(out.length());
                out.writeUTF(packageName);
            } catch (IOException e) {
                Slog.e(TAG, "Can't log backup of " + packageName + " to " + mEverStored);
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
            try (RandomAccessFile known = new RandomAccessFile(tempKnownFile, "rws")) {
                mEverStoredApps.remove(packageName);
                for (String s : mEverStoredApps) {
                    known.writeUTF(s);
                    if (MORE_DEBUG) Slog.v(TAG, "    " + s);
                }
                known.close();
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
            }
        }
    }

    // Persistently record the current and ancestral backup tokens as well
    // as the set of packages with data [supposedly] available in the
    // ancestral dataset.
    public void writeRestoreTokens() {
        try (RandomAccessFile af = new RandomAccessFile(mTokenFile, "rwd")) {
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
        } catch (IOException e) {
            Slog.w(TAG, "Unable to write token file:", e);
        }
    }

    // What name is this transport registered under...?
    private String getTransportName(IBackupTransport transport) {
        if (MORE_DEBUG) {
            Slog.v(TAG, "Searching for transport name of " + transport);
        }
        return mTransportManager.getTransportName(transport);
    }

    // fire off a backup agent, blocking until it attaches or times out
    @Override
    public IBackupAgent bindToAgentSynchronous(ApplicationInfo app, int mode) {
        IBackupAgent agent = null;
        synchronized (mAgentConnectLock) {
            mConnecting = true;
            mConnectedAgent = null;
            try {
                if (mActivityManager.bindBackupAgent(app.packageName, mode,
                        UserHandle.USER_OWNER)) {
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
                            Slog.w(TAG, "Interrupted: " + e);
                            mConnecting = false;
                            mConnectedAgent = null;
                        }
                    }

                    // if we timed out with no connect, abort and move on
                    if (mConnecting == true) {
                        Slog.w(TAG, "Timeout waiting for agent " + app);
                        mConnectedAgent = null;
                    }
                    if (DEBUG) Slog.i(TAG, "got agent " + mConnectedAgent);
                    agent = mConnectedAgent;
                }
            } catch (RemoteException e) {
                // can't happen - ActivityManager is local
            }
        }
        if (agent == null) {
            try {
                mActivityManager.clearPendingBackup();
            } catch (RemoteException e) {
                // can't happen - ActivityManager is local
            }
        }
        return agent;
    }

    // clear an application's data, blocking until the operation completes or times out
    public void clearApplicationDataSynchronous(String packageName) {
        // Don't wipe packages marked allowClearUserData=false
        try {
            PackageInfo info = mPackageManager.getPackageInfo(packageName, 0);
            if ((info.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA) == 0) {
                if (MORE_DEBUG) {
                    Slog.i(TAG, "allowClearUserData=false so not wiping "
                            + packageName);
                }
                return;
            }
        } catch (NameNotFoundException e) {
            Slog.w(TAG, "Tried to clear data for " + packageName + " but not found");
            return;
        }

        ClearDataObserver observer = new ClearDataObserver(this);

        synchronized (mClearDataLock) {
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

    // Get the restore-set token for the best-available restore set for this package:
    // the active set if possible, else the ancestral one.  Returns zero if none available.
    @Override
    public long getAvailableRestoreToken(String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getAvailableRestoreToken");

        long token = mAncestralToken;
        synchronized (mQueueLock) {
            if (mEverStoredApps.contains(packageName)) {
                if (MORE_DEBUG) {
                    Slog.i(TAG, "App in ever-stored, so using current token");
                }
                token = mCurrentToken;
            }
        }
        if (MORE_DEBUG) Slog.i(TAG, "getAvailableRestoreToken() == " + token);
        return token;
    }

    @Override
    public int requestBackup(String[] packages, IBackupObserver observer, int flags) {
        return requestBackup(packages, observer, null, flags);
    }

    @Override
    public int requestBackup(String[] packages, IBackupObserver observer,
            IBackupManagerMonitor monitor, int flags) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "requestBackup");

        if (packages == null || packages.length < 1) {
            Slog.e(TAG, "No packages named for backup request");
            BackupObserverUtils.sendBackupFinished(observer, BackupManager.ERROR_TRANSPORT_ABORTED);
            monitor = BackupManagerMonitorUtils.monitorEvent(monitor,
                    BackupManagerMonitor.LOG_EVENT_ID_NO_PACKAGES,
                    null, BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT, null);
            throw new IllegalArgumentException("No packages are provided for backup");
        }

        if (!mEnabled || !mProvisioned) {
            Slog.i(TAG, "Backup requested but e=" + mEnabled + " p=" +mProvisioned);
            BackupObserverUtils.sendBackupFinished(observer, BackupManager.ERROR_BACKUP_NOT_ALLOWED);
            final int logTag = mProvisioned
                    ? BackupManagerMonitor.LOG_EVENT_ID_BACKUP_DISABLED
                    : BackupManagerMonitor.LOG_EVENT_ID_DEVICE_NOT_PROVISIONED;
            monitor = BackupManagerMonitorUtils.monitorEvent(monitor, logTag, null,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY, null);
            return BackupManager.ERROR_BACKUP_NOT_ALLOWED;
        }

        IBackupTransport transport = mTransportManager.getCurrentTransportBinder();
        if (transport == null) {
            BackupObserverUtils.sendBackupFinished(observer, BackupManager.ERROR_TRANSPORT_ABORTED);
            monitor = BackupManagerMonitorUtils.monitorEvent(monitor,
                    BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_IS_NULL,
                    null, BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT, null);
            return BackupManager.ERROR_TRANSPORT_ABORTED;
        }

        ArrayList<String> fullBackupList = new ArrayList<>();
        ArrayList<String> kvBackupList = new ArrayList<>();
        for (String packageName : packages) {
            if (PACKAGE_MANAGER_SENTINEL.equals(packageName)) {
                kvBackupList.add(packageName);
                continue;
            }
            try {
                PackageInfo packageInfo = mPackageManager.getPackageInfo(packageName,
                        PackageManager.GET_SIGNATURES);
                if (!AppBackupUtils.appIsEligibleForBackup(packageInfo.applicationInfo)) {
                    BackupObserverUtils.sendBackupOnPackageResult(observer, packageName,
                            BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                    continue;
                }
                if (AppBackupUtils.appGetsFullBackup(packageInfo)) {
                    fullBackupList.add(packageInfo.packageName);
                } else {
                    kvBackupList.add(packageInfo.packageName);
                }
            } catch (NameNotFoundException e) {
                BackupObserverUtils.sendBackupOnPackageResult(observer, packageName,
                        BackupManager.ERROR_PACKAGE_NOT_FOUND);
            }
        }
        EventLog.writeEvent(EventLogTags.BACKUP_REQUESTED, packages.length, kvBackupList.size(),
                fullBackupList.size());
        if (MORE_DEBUG) {
            Slog.i(TAG, "Backup requested for " + packages.length + " packages, of them: " +
                    fullBackupList.size() + " full backups, " + kvBackupList.size()
                    + " k/v backups");
        }

        String dirName;
        try {
            dirName = transport.transportDirName();
        } catch (Exception e) {
            Slog.e(TAG, "Transport unavailable while attempting backup: " + e.getMessage());
            BackupObserverUtils.sendBackupFinished(observer, BackupManager.ERROR_TRANSPORT_ABORTED);
            return BackupManager.ERROR_TRANSPORT_ABORTED;
        }

        boolean nonIncrementalBackup = (flags & BackupManager.FLAG_NON_INCREMENTAL_BACKUP) != 0;

        Message msg = mBackupHandler.obtainMessage(MSG_REQUEST_BACKUP);
        msg.obj = new BackupParams(transport, dirName, kvBackupList, fullBackupList, observer,
                monitor, true, nonIncrementalBackup);
        mBackupHandler.sendMessage(msg);
        return BackupManager.SUCCESS;
    }

    // Cancel all running backups.
    @Override
    public void cancelBackups() {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "cancelBackups");
        if (MORE_DEBUG) {
            Slog.i(TAG, "cancelBackups() called.");
        }
        final long oldToken = Binder.clearCallingIdentity();
        try {
            List<Integer> operationsToCancel = new ArrayList<>();
            synchronized (mCurrentOpLock) {
                for (int i = 0; i < mCurrentOperations.size(); i++) {
                    Operation op = mCurrentOperations.valueAt(i);
                    int token = mCurrentOperations.keyAt(i);
                    if (op.type == OP_TYPE_BACKUP) {
                        operationsToCancel.add(token);
                    }
                }
            }
            for (Integer token : operationsToCancel) {
                handleCancel(token, true /* cancelAll */);
            }
            // We don't want the backup jobs to kick in any time soon.
            // Reschedules them to run in the distant future.
            KeyValueBackupJob.schedule(mContext, BUSY_BACKOFF_MIN_MILLIS);
            FullBackupJob.schedule(mContext, 2 * BUSY_BACKOFF_MIN_MILLIS);
        } finally {
            Binder.restoreCallingIdentity(oldToken);
        }
    }

    @Override
    public void prepareOperationTimeout(int token, long interval, BackupRestoreTask callback,
            int operationType) {
        if (operationType != OP_TYPE_BACKUP_WAIT && operationType != OP_TYPE_RESTORE_WAIT) {
            Slog.wtf(TAG, "prepareOperationTimeout() doesn't support operation " +
                    Integer.toHexString(token) + " of type " + operationType);
            return;
        }
        if (MORE_DEBUG) {
            Slog.v(TAG, "starting timeout: token=" + Integer.toHexString(token)
                    + " interval=" + interval + " callback=" + callback);
        }

        synchronized (mCurrentOpLock) {
            mCurrentOperations.put(token, new Operation(OP_PENDING, callback, operationType));
            Message msg = mBackupHandler.obtainMessage(getMessageIdForOperationType(operationType),
                    token, 0, callback);
            mBackupHandler.sendMessageDelayed(msg, interval);
        }
    }

    private int getMessageIdForOperationType(int operationType) {
        switch (operationType) {
            case OP_TYPE_BACKUP_WAIT:
                return MSG_BACKUP_OPERATION_TIMEOUT;
            case OP_TYPE_RESTORE_WAIT:
                return MSG_RESTORE_OPERATION_TIMEOUT;
            default:
                Slog.wtf(TAG, "getMessageIdForOperationType called on invalid operation type: " +
                        operationType);
                return -1;
        }
    }

    public void removeOperation(int token) {
        if (MORE_DEBUG) {
            Slog.d(TAG, "Removing operation token=" + Integer.toHexString(token));
        }
        synchronized (mCurrentOpLock) {
            if (mCurrentOperations.get(token) == null) {
                Slog.w(TAG, "Duplicate remove for operation. token=" +
                        Integer.toHexString(token));
            }
            mCurrentOperations.remove(token);
        }
    }

    // synchronous waiter case
    @Override
    public boolean waitUntilOperationComplete(int token) {
        if (MORE_DEBUG) {
            Slog.i(TAG, "Blocking until operation complete for "
                    + Integer.toHexString(token));
        }
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
                        } catch (InterruptedException e) {
                        }
                        // When the wait is notified we loop around and recheck the current state
                    } else {
                        if (MORE_DEBUG) {
                            Slog.d(TAG, "Unblocked waiting for operation token=" +
                                    Integer.toHexString(token));
                        }
                        // No longer pending; we're done
                        finalState = op.state;
                        break;
                    }
                }
            }
        }

        removeOperation(token);
        if (op != null) {
            mBackupHandler.removeMessages(getMessageIdForOperationType(op.type));
        }
        if (MORE_DEBUG) {
            Slog.v(TAG, "operation " + Integer.toHexString(token)
                    + " complete: finalState=" + finalState);
        }
        return finalState == OP_ACKNOWLEDGED;
    }

    public void handleCancel(int token, boolean cancelAll) {
        // Notify any synchronous waiters
        Operation op = null;
        synchronized (mCurrentOpLock) {
            op = mCurrentOperations.get(token);
            if (MORE_DEBUG) {
                if (op == null) {
                    Slog.w(TAG, "Cancel of token " + Integer.toHexString(token)
                            + " but no op found");
                }
            }
            int state = (op != null) ? op.state : OP_TIMEOUT;
            if (state == OP_ACKNOWLEDGED) {
                // The operation finished cleanly, so we have nothing more to do.
                if (DEBUG) {
                    Slog.w(TAG, "Operation already got an ack." +
                            "Should have been removed from mCurrentOperations.");
                }
                op = null;
                mCurrentOperations.delete(token);
            } else if (state == OP_PENDING) {
                if (DEBUG) Slog.v(TAG, "Cancel: token=" + Integer.toHexString(token));
                op.state = OP_TIMEOUT;
                // Can't delete op from mCurrentOperations here. waitUntilOperationComplete may be
                // called after we receive cancel here. We need this op's state there.

                // Remove all pending timeout messages for this operation type.
                mBackupHandler.removeMessages(getMessageIdForOperationType(op.type));
            }
            mCurrentOpLock.notifyAll();
        }

        // If there's a TimeoutHandler for this event, call it
        if (op != null && op.callback != null) {
            if (MORE_DEBUG) {
                Slog.v(TAG, "   Invoking cancel on " + op.callback);
            }
            op.callback.handleCancel(cancelAll);
        }
    }

    // ----- Back up a set of applications via a worker thread -----

    public boolean isBackupOperationInProgress() {
        synchronized (mCurrentOpLock) {
            for (int i = 0; i < mCurrentOperations.size(); i++) {
                Operation op = mCurrentOperations.valueAt(i);
                if (op.type == OP_TYPE_BACKUP && op.state == OP_PENDING) {
                    return true;
                }
            }
        }
        return false;
    }


    @Override
    public void tearDownAgentAndKill(ApplicationInfo app) {
        if (app == null) {
            // Null means the system package, so just quietly move on.  :)
            return;
        }

        try {
            // unbind and tidy up even on timeout or failure, just in case
            mActivityManager.unbindBackupAgent(app);

            // The agent was running with a stub Application object, so shut it down.
            // !!! We hardcode the confirmation UI's package name here rather than use a
            //     manifest flag!  TODO something less direct.
            if (app.uid >= Process.FIRST_APPLICATION_UID
                    && !app.packageName.equals("com.android.backupconfirm")) {
                if (MORE_DEBUG) Slog.d(TAG, "Killing agent host process");
                mActivityManager.killApplicationProcess(app.processName, app.uid);
            } else {
                if (MORE_DEBUG) Slog.d(TAG, "Not killing after operation: " + app.processName);
            }
        } catch (RemoteException e) {
            Slog.d(TAG, "Lost app trying to shut down");
        }
    }

    public boolean deviceIsEncrypted() {
        try {
            return mStorageManager.getEncryptionState()
                    != StorageManager.ENCRYPTION_STATE_NONE
                    && mStorageManager.getPasswordType()
                    != StorageManager.CRYPT_TYPE_DEFAULT;
        } catch (Exception e) {
            // If we can't talk to the storagemanager service we have a serious problem; fail
            // "secure" i.e. assuming that the device is encrypted.
            Slog.e(TAG, "Unable to communicate with storagemanager service: " + e.getMessage());
            return true;
        }
    }

    // ----- Full-data backup scheduling -----

    /**
     * Schedule a job to tell us when it's a good time to run a full backup
     */
    public void scheduleNextFullBackupJob(long transportMinLatency) {
        synchronized (mQueueLock) {
            if (mFullBackupQueue.size() > 0) {
                // schedule the next job at the point in the future when the least-recently
                // backed up app comes due for backup again; or immediately if it's already
                // due.
                final long upcomingLastBackup = mFullBackupQueue.get(0).lastBackup;
                final long timeSinceLast = System.currentTimeMillis() - upcomingLastBackup;
                final long appLatency = (timeSinceLast < MIN_FULL_BACKUP_INTERVAL)
                        ? (MIN_FULL_BACKUP_INTERVAL - timeSinceLast) : 0;
                final long latency = Math.max(transportMinLatency, appLatency);
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        FullBackupJob.schedule(mContext, latency);
                    }
                };
                mBackupHandler.postDelayed(r, 2500);
            } else {
                if (DEBUG_SCHEDULING) {
                    Slog.i(TAG, "Full backup queue empty; not scheduling");
                }
            }
        }
    }

    /**
     * Remove a package from the full-data queue.
     */
    private void dequeueFullBackupLocked(String packageName) {
        final int N = mFullBackupQueue.size();
        for (int i = N - 1; i >= 0; i--) {
            final FullBackupEntry e = mFullBackupQueue.get(i);
            if (packageName.equals(e.packageName)) {
                mFullBackupQueue.remove(i);
            }
        }
    }

    /**
     * Enqueue full backup for the given app, with a note about when it last ran.
     */
    public void enqueueFullBackup(String packageName, long lastBackedUp) {
        FullBackupEntry newEntry = new FullBackupEntry(packageName, lastBackedUp);
        synchronized (mQueueLock) {
            // First, sanity check that we aren't adding a duplicate.  Slow but
            // straightforward; we'll have at most on the order of a few hundred
            // items in this list.
            dequeueFullBackupLocked(packageName);

            // This is also slow but easy for modest numbers of apps: work backwards
            // from the end of the queue until we find an item whose last backup
            // time was before this one, then insert this new entry after it.  If we're
            // adding something new we don't bother scanning, and just prepend.
            int which = -1;
            if (lastBackedUp > 0) {
                for (which = mFullBackupQueue.size() - 1; which >= 0; which--) {
                    final FullBackupEntry entry = mFullBackupQueue.get(which);
                    if (entry.lastBackup <= lastBackedUp) {
                        mFullBackupQueue.add(which + 1, newEntry);
                        break;
                    }
                }
            }
            if (which < 0) {
                // this one is earlier than any existing one, so prepend
                mFullBackupQueue.add(0, newEntry);
            }
        }
        writeFullBackupScheduleAsync();
    }

    private boolean fullBackupAllowable(IBackupTransport transport) {
        if (transport == null) {
            Slog.w(TAG, "Transport not present; full data backup not performed");
            return false;
        }

        // Don't proceed unless we have already established package metadata
        // for the current dataset via a key/value backup pass.
        try {
            File stateDir = new File(mBaseStateDir, transport.transportDirName());
            File pmState = new File(stateDir, PACKAGE_MANAGER_SENTINEL);
            if (pmState.length() <= 0) {
                if (DEBUG) {
                    Slog.i(TAG, "Full backup requested but dataset not yet initialized");
                }
                return false;
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unable to get transport name: " + e.getMessage());
            return false;
        }

        return true;
    }

    /**
     * Conditions are right for a full backup operation, so run one.  The model we use is
     * to perform one app backup per scheduled job execution, and to reschedule the job
     * with zero latency as long as conditions remain right and we still have work to do.
     *
     * <p>This is the "start a full backup operation" entry point called by the scheduled job.
     *
     * @return Whether ongoing work will continue.  The return value here will be passed
     *         along as the return value to the scheduled job's onStartJob() callback.
     */
    @Override
    public boolean beginFullBackup(FullBackupJob scheduledJob) {
        long now = System.currentTimeMillis();
        FullBackupEntry entry = null;
        long latency = MIN_FULL_BACKUP_INTERVAL;

        if (!mEnabled || !mProvisioned) {
            // Backups are globally disabled, so don't proceed.  We also don't reschedule
            // the job driving automatic backups; that job will be scheduled again when
            // the user enables backup.
            if (MORE_DEBUG) {
                Slog.i(TAG, "beginFullBackup but e=" + mEnabled
                        + " p=" + mProvisioned + "; ignoring");
            }
            return false;
        }

        // Don't run the backup if we're in battery saver mode, but reschedule
        // to try again in the not-so-distant future.
        final PowerSaveState result =
                mPowerManager.getPowerSaveState(ServiceType.FULL_BACKUP);
        if (result.batterySaverEnabled) {
            if (DEBUG) Slog.i(TAG, "Deferring scheduled full backups in battery saver mode");
            FullBackupJob.schedule(mContext, KeyValueBackupJob.BATCH_INTERVAL);
            return false;
        }

        if (DEBUG_SCHEDULING) {
            Slog.i(TAG, "Beginning scheduled full backup operation");
        }

        // Great; we're able to run full backup jobs now.  See if we have any work to do.
        synchronized (mQueueLock) {
            if (mRunningFullBackupTask != null) {
                Slog.e(TAG, "Backup triggered but one already/still running!");
                return false;
            }

            // At this point we think that we have work to do, but possibly not right now.
            // Any exit without actually running backups will also require that we
            // reschedule the job.
            boolean runBackup = true;
            boolean headBusy;

            do {
                // Recheck each time, because culling due to ineligibility may
                // have emptied the queue.
                if (mFullBackupQueue.size() == 0) {
                    // no work to do so just bow out
                    if (DEBUG) {
                        Slog.i(TAG, "Backup queue empty; doing nothing");
                    }
                    runBackup = false;
                    break;
                }

                headBusy = false;

                if (!fullBackupAllowable(mTransportManager.getCurrentTransportBinder())) {
                    if (MORE_DEBUG) {
                        Slog.i(TAG, "Preconditions not met; not running full backup");
                    }
                    runBackup = false;
                    // Typically this means we haven't run a key/value backup yet.  Back off
                    // full-backup operations by the key/value job's run interval so that
                    // next time we run, we are likely to be able to make progress.
                    latency = KeyValueBackupJob.BATCH_INTERVAL;
                }

                if (runBackup) {
                    entry = mFullBackupQueue.get(0);
                    long timeSinceRun = now - entry.lastBackup;
                    runBackup = (timeSinceRun >= MIN_FULL_BACKUP_INTERVAL);
                    if (!runBackup) {
                        // It's too early to back up the next thing in the queue, so bow out
                        if (MORE_DEBUG) {
                            Slog.i(TAG, "Device ready but too early to back up next app");
                        }
                        // Wait until the next app in the queue falls due for a full data backup
                        latency = MIN_FULL_BACKUP_INTERVAL - timeSinceRun;
                        break;  // we know we aren't doing work yet, so bail.
                    }

                    try {
                        PackageInfo appInfo = mPackageManager.getPackageInfo(entry.packageName, 0);
                        if (!AppBackupUtils.appGetsFullBackup(appInfo)) {
                            // The head app isn't supposed to get full-data backups [any more];
                            // so we cull it and force a loop around to consider the new head
                            // app.
                            if (MORE_DEBUG) {
                                Slog.i(TAG, "Culling package " + entry.packageName
                                        + " in full-backup queue but not eligible");
                            }
                            mFullBackupQueue.remove(0);
                            headBusy = true; // force the while() condition
                            continue;
                        }

                        final int privFlags = appInfo.applicationInfo.privateFlags;
                        headBusy = (privFlags & PRIVATE_FLAG_BACKUP_IN_FOREGROUND) == 0
                                && mActivityManager.isAppForeground(appInfo.applicationInfo.uid);

                        if (headBusy) {
                            final long nextEligible = System.currentTimeMillis()
                                    + BUSY_BACKOFF_MIN_MILLIS
                                    + mTokenGenerator.nextInt(BUSY_BACKOFF_FUZZ);
                            if (DEBUG_SCHEDULING) {
                                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                                Slog.i(TAG, "Full backup time but " + entry.packageName
                                        + " is busy; deferring to "
                                        + sdf.format(new Date(nextEligible)));
                            }
                            // This relocates the app's entry from the head of the queue to
                            // its order-appropriate position further down, so upon looping
                            // a new candidate will be considered at the head.
                            enqueueFullBackup(entry.packageName,
                                    nextEligible - MIN_FULL_BACKUP_INTERVAL);
                        }
                    } catch (NameNotFoundException nnf) {
                        // So, we think we want to back this up, but it turns out the package
                        // in question is no longer installed.  We want to drop it from the
                        // queue entirely and move on, but if there's nothing else in the queue
                        // we should bail entirely.  headBusy cannot have been set to true yet.
                        runBackup = (mFullBackupQueue.size() > 1);
                    } catch (RemoteException e) {
                        // Cannot happen; the Activity Manager is in the same process
                    }
                }
            } while (headBusy);

            if (!runBackup) {
                if (DEBUG_SCHEDULING) {
                    Slog.i(TAG, "Nothing pending full backup; rescheduling +" + latency);
                }
                final long deferTime = latency;     // pin for the closure
                mBackupHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        FullBackupJob.schedule(mContext, deferTime);
                    }
                });
                return false;
            }

            // Okay, the top thing is ready for backup now.  Do it.
            mFullBackupQueue.remove(0);
            CountDownLatch latch = new CountDownLatch(1);
            String[] pkg = new String[]{entry.packageName};
            mRunningFullBackupTask = new PerformFullTransportBackupTask(this, null, pkg, true,
                    scheduledJob, latch, null, null, false /* userInitiated */);
            // Acquiring wakelock for PerformFullTransportBackupTask before its start.
            mWakelock.acquire();
            (new Thread(mRunningFullBackupTask)).start();
        }

        return true;
    }

    // The job scheduler says our constraints don't hold any more,
    // so tear down any ongoing backup task right away.
    @Override
    public void endFullBackup() {
        synchronized (mQueueLock) {
            if (mRunningFullBackupTask != null) {
                if (DEBUG_SCHEDULING) {
                    Slog.i(TAG, "Telling running backup to stop");
                }
                mRunningFullBackupTask.handleCancel(true);
            }
        }
    }

    // Used by both incremental and full restore
    public void restoreWidgetData(String packageName, byte[] widgetData) {
        // Apply the restored widget state and generate the ID update for the app
        // TODO: http://b/22388012
        if (MORE_DEBUG) {
            Slog.i(TAG, "Incorporating restored widget data");
        }
        AppWidgetBackupBridge.restoreWidgetState(packageName, widgetData, UserHandle.USER_SYSTEM);
    }

    // *****************************
    // NEW UNIFIED RESTORE IMPLEMENTATION
    // *****************************

    public void dataChangedImpl(String packageName) {
        HashSet<String> targets = dataChangedTargets(packageName);
        dataChangedImpl(packageName, targets);
    }

    private void dataChangedImpl(String packageName, HashSet<String> targets) {
        // Record that we need a backup pass for the caller.  Since multiple callers
        // may share a uid, we need to note all candidates within that uid and schedule
        // a backup pass for each of them.
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
                    if (MORE_DEBUG) Slog.d(TAG, "Now staging backup of " + packageName);

                    // Journal this request in case of crash.  The put()
                    // operation returned null when this package was not already
                    // in the set; we want to avoid touching the disk redundantly.
                    writeToJournalLocked(packageName);
                }
            }
        }

        // ...and schedule a backup pass if necessary
        KeyValueBackupJob.schedule(mContext);
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
        if (PACKAGE_MANAGER_SENTINEL.equals(packageName)) {
            return Sets.newHashSet(PACKAGE_MANAGER_SENTINEL);
        } else {
            synchronized (mBackupParticipants) {
                return SparseArrayUtils.union(mBackupParticipants);
            }
        }
    }

    private void writeToJournalLocked(String str) {
        try {
            if (mJournal == null) mJournal = DataChangedJournal.newJournal(mJournalDir);
            mJournal.addPackage(str);
        } catch (IOException e) {
            Slog.e(TAG, "Can't write " + str + " to backup journal", e);
            mJournal = null;
        }
    }

    // ----- IBackupManager binder interface -----

    @Override
    public void dataChanged(final String packageName) {
        final int callingUserHandle = UserHandle.getCallingUserId();
        if (callingUserHandle != UserHandle.USER_SYSTEM) {
            // TODO: http://b/22388012
            // App is running under a non-owner user profile.  For now, we do not back
            // up data from secondary user profiles.
            // TODO: backups for all user profiles although don't add backup for profiles
            // without adding admin control in DevicePolicyManager.
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

    // Run an initialize operation for the given transport
    @Override
    public void initializeTransports(String[] transportNames, IBackupObserver observer) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "initializeTransport");
        if (MORE_DEBUG || true) {
            Slog.v(TAG, "initializeTransport(): " + Arrays.asList(transportNames));
        }

        final long oldId = Binder.clearCallingIdentity();
        try {
            mWakelock.acquire();
            mBackupHandler.post(new PerformInitializeTask(this, transportNames, observer));
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    // Clear the given package's backup data from the current transport
    @Override
    public void clearBackupData(String transportName, String packageName) {
        if (DEBUG) Slog.v(TAG, "clearBackupData() of " + packageName + " on " + transportName);
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
            if (MORE_DEBUG) Slog.v(TAG, "Privileged caller, allowing clear of other apps");
            apps = SparseArrayUtils.union(mBackupParticipants);
        }

        // Is the given app an available participant?
        if (apps.contains(packageName)) {
            // found it; fire off the clear request
            if (MORE_DEBUG) Slog.v(TAG, "Found the app - running clear process");
            mBackupHandler.removeMessages(MSG_RETRY_CLEAR);
            synchronized (mQueueLock) {
                final IBackupTransport transport =
                        mTransportManager.getTransportBinder(transportName);
                if (transport == null) {
                    // transport is currently unavailable -- make sure to retry
                    Message msg = mBackupHandler.obtainMessage(MSG_RETRY_CLEAR,
                            new ClearRetryParams(transportName, packageName));
                    mBackupHandler.sendMessageDelayed(msg, TRANSPORT_RETRY_INTERVAL);
                    return;
                }
                long oldId = Binder.clearCallingIdentity();
                mWakelock.acquire();
                Message msg = mBackupHandler.obtainMessage(MSG_RUN_CLEAR,
                        new ClearParams(transport, info));
                mBackupHandler.sendMessage(msg);
                Binder.restoreCallingIdentity(oldId);
            }
        }
    }

    // Run a backup pass immediately for any applications that have declared
    // that they have pending updates.
    @Override
    public void backupNow() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP, "backupNow");

        final PowerSaveState result =
                mPowerManager.getPowerSaveState(ServiceType.KEYVALUE_BACKUP);
        if (result.batterySaverEnabled) {
            if (DEBUG) Slog.v(TAG, "Not running backup while in battery save mode");
            KeyValueBackupJob.schedule(mContext);   // try again in several hours
        } else {
            if (DEBUG) Slog.v(TAG, "Scheduling immediate backup pass");
            synchronized (mQueueLock) {
                // Fire the intent that kicks off the whole shebang...
                try {
                    mRunBackupIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    // should never happen
                    Slog.e(TAG, "run-backup intent cancelled!");
                }

                // ...and cancel any pending scheduled job, because we've just superseded it
                KeyValueBackupJob.cancel(mContext);
            }
        }
    }

    public boolean deviceIsProvisioned() {
        final ContentResolver resolver = mContext.getContentResolver();
        return (Settings.Global.getInt(resolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0);
    }

    // Run a backup pass for the given packages, writing the resulting data stream
    // to the supplied file descriptor.  This method is synchronous and does not return
    // to the caller until the backup has been completed.
    //
    // This is the variant used by 'adb backup'; it requires on-screen confirmation
    // by the user because it can be used to offload data over untrusted USB.
    @Override
    public void adbBackup(ParcelFileDescriptor fd, boolean includeApks, boolean includeObbs,
            boolean includeShared, boolean doWidgets, boolean doAllApps, boolean includeSystem,
            boolean compress, boolean doKeyValue, String[] pkgList) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "adbBackup");

        final int callingUserHandle = UserHandle.getCallingUserId();
        // TODO: http://b/22388012
        if (callingUserHandle != UserHandle.USER_SYSTEM) {
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
                Slog.i(TAG, "Backup not supported before setup");
                return;
            }

            if (DEBUG) {
                Slog.v(TAG, "Requesting backup: apks=" + includeApks + " obb=" + includeObbs
                        + " shared=" + includeShared + " all=" + doAllApps + " system="
                        + includeSystem + " includekeyvalue=" + doKeyValue + " pkgs=" + pkgList);
            }
            Slog.i(TAG, "Beginning adb backup...");

            AdbBackupParams params = new AdbBackupParams(fd, includeApks, includeObbs,
                    includeShared, doWidgets, doAllApps, includeSystem, compress, doKeyValue,
                    pkgList);
            final int token = generateRandomIntegerToken();
            synchronized (mAdbBackupRestoreConfirmations) {
                mAdbBackupRestoreConfirmations.put(token, params);
            }

            // start up the confirmation UI
            if (DEBUG) Slog.d(TAG, "Starting backup confirmation UI, token=" + token);
            if (!startConfirmationUi(token, FullBackup.FULL_BACKUP_INTENT_ACTION)) {
                Slog.e(TAG, "Unable to launch backup confirmation UI");
                mAdbBackupRestoreConfirmations.delete(token);
                return;
            }

            // make sure the screen is lit for the user interaction
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_OTHER,
                    0);

            // start the confirmation countdown
            startConfirmationTimeout(token, params);

            // wait for the backup to be performed
            if (DEBUG) Slog.d(TAG, "Waiting for backup completion...");
            waitForCompletion(params);
        } finally {
            try {
                fd.close();
            } catch (IOException e) {
                Slog.e(TAG, "IO error closing output for adb backup: " + e.getMessage());
            }
            Binder.restoreCallingIdentity(oldId);
            Slog.d(TAG, "Adb backup processing complete.");
        }
    }

    @Override
    public void fullTransportBackup(String[] pkgNames) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP,
                "fullTransportBackup");

        final int callingUserHandle = UserHandle.getCallingUserId();
        // TODO: http://b/22388012
        if (callingUserHandle != UserHandle.USER_SYSTEM) {
            throw new IllegalStateException("Restore supported only for the device owner");
        }

        if (!fullBackupAllowable(mTransportManager.getCurrentTransportBinder())) {
            Slog.i(TAG, "Full backup not currently possible -- key/value backup not yet run?");
        } else {
            if (DEBUG) {
                Slog.d(TAG, "fullTransportBackup()");
            }

            final long oldId = Binder.clearCallingIdentity();
            try {
                CountDownLatch latch = new CountDownLatch(1);
                PerformFullTransportBackupTask task = new PerformFullTransportBackupTask(this, null,
                        pkgNames, false, null, latch, null, null, false /* userInitiated */);
                // Acquiring wakelock for PerformFullTransportBackupTask before its start.
                mWakelock.acquire();
                (new Thread(task, "full-transport-master")).start();
                do {
                    try {
                        latch.await();
                        break;
                    } catch (InterruptedException e) {
                        // Just go back to waiting for the latch to indicate completion
                    }
                } while (true);

                // We just ran a backup on these packages, so kick them to the end of the queue
                final long now = System.currentTimeMillis();
                for (String pkg : pkgNames) {
                    enqueueFullBackup(pkg, now);
                }
            } finally {
                Binder.restoreCallingIdentity(oldId);
            }
        }

        if (DEBUG) {
            Slog.d(TAG, "Done with full transport backup.");
        }
    }

    @Override
    public void adbRestore(ParcelFileDescriptor fd) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "adbRestore");

        final int callingUserHandle = UserHandle.getCallingUserId();
        // TODO: http://b/22388012
        if (callingUserHandle != UserHandle.USER_SYSTEM) {
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

            Slog.i(TAG, "Beginning restore...");

            AdbRestoreParams params = new AdbRestoreParams(fd);
            final int token = generateRandomIntegerToken();
            synchronized (mAdbBackupRestoreConfirmations) {
                mAdbBackupRestoreConfirmations.put(token, params);
            }

            // start up the confirmation UI
            if (DEBUG) Slog.d(TAG, "Starting restore confirmation UI, token=" + token);
            if (!startConfirmationUi(token, FullBackup.FULL_RESTORE_INTENT_ACTION)) {
                Slog.e(TAG, "Unable to launch restore confirmation");
                mAdbBackupRestoreConfirmations.delete(token);
                return;
            }

            // make sure the screen is lit for the user interaction
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_OTHER,
                    0);

            // start the confirmation countdown
            startConfirmationTimeout(token, params);

            // wait for the restore to be performed
            if (DEBUG) Slog.d(TAG, "Waiting for restore completion...");
            waitForCompletion(params);
        } finally {
            try {
                fd.close();
            } catch (IOException e) {
                Slog.w(TAG, "Error trying to close fd after adb restore: " + e);
            }
            Binder.restoreCallingIdentity(oldId);
            Slog.i(TAG, "adb restore processing complete.");
        }
    }

    private boolean startConfirmationUi(int token, String action) {
        try {
            Intent confIntent = new Intent(action);
            confIntent.setClassName("com.android.backupconfirm",
                    "com.android.backupconfirm.BackupRestoreConfirmation");
            confIntent.putExtra(FullBackup.CONF_TOKEN_INTENT_EXTRA, token);
            confIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivityAsUser(confIntent, UserHandle.SYSTEM);
        } catch (ActivityNotFoundException e) {
            return false;
        }
        return true;
    }

    private void startConfirmationTimeout(int token, AdbParams params) {
        if (MORE_DEBUG) {
            Slog.d(TAG, "Posting conf timeout msg after "
                    + TIMEOUT_FULL_CONFIRMATION + " millis");
        }
        Message msg = mBackupHandler.obtainMessage(MSG_FULL_CONFIRMATION_TIMEOUT,
                token, 0, params);
        mBackupHandler.sendMessageDelayed(msg, TIMEOUT_FULL_CONFIRMATION);
    }

    private void waitForCompletion(AdbParams params) {
        synchronized (params.latch) {
            while (params.latch.get() == false) {
                try {
                    params.latch.wait();
                } catch (InterruptedException e) { /* never interrupted */ }
            }
        }
    }

    public void signalAdbBackupRestoreCompletion(AdbParams params) {
        synchronized (params.latch) {
            params.latch.set(true);
            params.latch.notifyAll();
        }
    }

    // Confirm that the previously-requested full backup/restore operation can proceed.  This
    // is used to require a user-facing disclosure about the operation.
    @Override
    public void acknowledgeAdbBackupOrRestore(int token, boolean allow,
            String curPassword, String encPpassword, IFullBackupRestoreObserver observer) {
        if (DEBUG) {
            Slog.d(TAG, "acknowledgeAdbBackupOrRestore : token=" + token
                    + " allow=" + allow);
        }

        // TODO: possibly require not just this signature-only permission, but even
        // require that the specific designated confirmation-UI app uid is the caller?
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP,
                "acknowledgeAdbBackupOrRestore");

        long oldId = Binder.clearCallingIdentity();
        try {

            AdbParams params;
            synchronized (mAdbBackupRestoreConfirmations) {
                params = mAdbBackupRestoreConfirmations.get(token);
                if (params != null) {
                    mBackupHandler.removeMessages(MSG_FULL_CONFIRMATION_TIMEOUT, params);
                    mAdbBackupRestoreConfirmations.delete(token);

                    if (allow) {
                        final int verb = params instanceof AdbBackupParams
                                ? MSG_RUN_ADB_BACKUP
                                : MSG_RUN_ADB_RESTORE;

                        params.observer = observer;
                        params.curPassword = curPassword;

                        params.encryptPassword = encPpassword;

                        if (MORE_DEBUG) Slog.d(TAG, "Sending conf message with verb " + verb);
                        mWakelock.acquire();
                        Message msg = mBackupHandler.obtainMessage(verb, params);
                        mBackupHandler.sendMessage(msg);
                    } else {
                        Slog.w(TAG, "User rejected full backup/restore operation");
                        // indicate completion without having actually transferred any data
                        signalAdbBackupRestoreCompletion(params);
                    }
                } else {
                    Slog.w(TAG, "Attempted to ack full backup/restore with invalid token");
                }
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    private static boolean backupSettingMigrated(int userId) {
        File base = new File(Environment.getDataDirectory(), "backup");
        File enableFile = new File(base, BACKUP_ENABLE_FILE);
        return enableFile.exists();
    }

    private static boolean readBackupEnableState(int userId) {
        File base = new File(Environment.getDataDirectory(), "backup");
        File enableFile = new File(base, BACKUP_ENABLE_FILE);
        if (enableFile.exists()) {
            try (FileInputStream fin = new FileInputStream(enableFile)) {
                int state = fin.read();
                return state != 0;
            } catch (IOException e) {
                // can't read the file; fall through to assume disabled
                Slog.e(TAG, "Cannot read enable state; assuming disabled");
            }
        } else {
            if (DEBUG) {
                Slog.i(TAG, "isBackupEnabled() => false due to absent settings file");
            }
        }
        return false;
    }

    private static void writeBackupEnableState(boolean enable, int userId) {
        File base = new File(Environment.getDataDirectory(), "backup");
        File enableFile = new File(base, BACKUP_ENABLE_FILE);
        File stage = new File(base, BACKUP_ENABLE_FILE + "-stage");
        try (FileOutputStream fout = new FileOutputStream(stage)) {
            fout.write(enable ? 1 : 0);
            fout.close();
            stage.renameTo(enableFile);
            // will be synced immediately by the try-with-resources call to close()
        } catch (IOException | RuntimeException e) {
            // Whoops; looks like we're doomed.  Roll everything out, disabled,
            // including the legacy state.
            Slog.e(TAG, "Unable to record backup enable state; reverting to disabled: "
                    + e.getMessage());

            final ContentResolver r = sInstance.mContext.getContentResolver();
            Settings.Secure.putStringForUser(r,
                    Settings.Secure.BACKUP_ENABLED, null, userId);
            enableFile.delete();
            stage.delete();
        }
    }

    // Enable/disable backups
    @Override
    public void setBackupEnabled(boolean enable) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setBackupEnabled");

        Slog.i(TAG, "Backup enabled => " + enable);

        long oldId = Binder.clearCallingIdentity();
        try {
            boolean wasEnabled = mEnabled;
            synchronized (this) {
                writeBackupEnableState(enable, UserHandle.USER_SYSTEM);
                mEnabled = enable;
            }

            synchronized (mQueueLock) {
                if (enable && !wasEnabled && mProvisioned) {
                    // if we've just been enabled, start scheduling backup passes
                    KeyValueBackupJob.schedule(mContext);
                    scheduleNextFullBackupJob(0);
                } else if (!enable) {
                    // No longer enabled, so stop running backups
                    if (MORE_DEBUG) Slog.i(TAG, "Opting out of backup");

                    KeyValueBackupJob.cancel(mContext);

                    // This also constitutes an opt-out, so we wipe any data for
                    // this device from the backend.  We start that process with
                    // an alarm in order to guarantee wakelock states.
                    if (wasEnabled && mProvisioned) {
                        // NOTE: we currently flush every registered transport, not just
                        // the currently-active one.
                        String[] allTransports = mTransportManager.getBoundTransportNames();
                        // build the set of transports for which we are posting an init
                        for (String transport : allTransports) {
                            recordInitPendingLocked(true, transport);
                        }
                        mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(),
                                mRunInitIntent);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    // Enable/disable automatic restore of app data at install time
    @Override
    public void setAutoRestore(boolean doAutoRestore) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setAutoRestore");

        Slog.i(TAG, "Auto restore => " + doAutoRestore);

        final long oldId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                Settings.Secure.putInt(mContext.getContentResolver(),
                        Settings.Secure.BACKUP_AUTO_RESTORE, doAutoRestore ? 1 : 0);
                mAutoRestore = doAutoRestore;
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    // Mark the backup service as having been provisioned
    @Override
    public void setBackupProvisioned(boolean available) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setBackupProvisioned");
        /*
         * This is now a no-op; provisioning is simply the device's own setup state.
         */
    }

    // Report whether the backup mechanism is currently enabled
    @Override
    public boolean isBackupEnabled() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "isBackupEnabled");
        return mEnabled;    // no need to synchronize just to read it
    }

    // Report the name of the currently active transport
    @Override
    public String getCurrentTransport() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getCurrentTransport");
        String currentTransport = mTransportManager.getCurrentTransportName();
        if (MORE_DEBUG) Slog.v(TAG, "... getCurrentTransport() returning " + currentTransport);
        return currentTransport;
    }

    // Report all known, available backup transports
    @Override
    public String[] listAllTransports() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "listAllTransports");

        return mTransportManager.getBoundTransportNames();
    }

    @Override
    public ComponentName[] listAllTransportComponents() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "listAllTransportComponents");
        return mTransportManager.getAllTransportCompenents();
    }

    @Override
    public String[] getTransportWhitelist() {
        // No permission check, intentionally.
        Set<ComponentName> whitelistedComponents = mTransportManager.getTransportWhitelist();
        String[] whitelistedTransports = new String[whitelistedComponents.size()];
        int i = 0;
        for (ComponentName component : whitelistedComponents) {
            whitelistedTransports[i] = component.flattenToShortString();
            i++;
        }
        return whitelistedTransports;
    }

    // Select which transport to use for the next backup operation.
    @Override
    public String selectBackupTransport(String transport) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "selectBackupTransport");

        final long oldId = Binder.clearCallingIdentity();
        try {
            String prevTransport = mTransportManager.selectTransport(transport);
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.BACKUP_TRANSPORT, transport);
            Slog.v(TAG, "selectBackupTransport() set " + mTransportManager.getCurrentTransportName()
                    + " returning " + prevTransport);
            return prevTransport;
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    @Override
    public void selectBackupTransportAsync(final ComponentName transport,
            final ISelectBackupTransportCallback listener) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "selectBackupTransportAsync");

        final long oldId = Binder.clearCallingIdentity();

        Slog.v(TAG, "selectBackupTransportAsync() called with transport " +
                transport.flattenToShortString());

        mTransportManager.ensureTransportReady(transport, new SelectBackupTransportCallback() {
            @Override
            public void onSuccess(String transportName) {
                mTransportManager.selectTransport(transportName);
                Settings.Secure.putString(mContext.getContentResolver(),
                        Settings.Secure.BACKUP_TRANSPORT,
                        mTransportManager.getCurrentTransportName());
                Slog.v(TAG, "Transport successfully selected: " + transport.flattenToShortString());
                try {
                    listener.onSuccess(transportName);
                } catch (RemoteException e) {
                    // Nothing to do here.
                }
            }

            @Override
            public void onFailure(int reason) {
                Slog.v(TAG, "Failed to select transport: " + transport.flattenToShortString());
                try {
                    listener.onFailure(reason);
                } catch (RemoteException e) {
                    // Nothing to do here.
                }
            }
        });

        Binder.restoreCallingIdentity(oldId);
    }

    // Supply the configuration Intent for the given transport.  If the name is not one
    // of the available transports, or if the transport does not supply any configuration
    // UI, the method returns null.
    @Override
    public Intent getConfigurationIntent(String transportName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getConfigurationIntent");

        final IBackupTransport transport = mTransportManager.getTransportBinder(transportName);
        if (transport != null) {
            try {
                final Intent intent = transport.configurationIntent();
                if (MORE_DEBUG) {
                    Slog.d(TAG, "getConfigurationIntent() returning config intent "
                            + intent);
                }
                return intent;
            } catch (Exception e) {
                /* fall through to return null */
                Slog.e(TAG, "Unable to get configuration intent from transport: " + e.getMessage());
            }
        }

        return null;
    }

    // Supply the configuration summary string for the given transport.  If the name is
    // not one of the available transports, or if the transport does not supply any
    // summary / destination string, the method can return null.
    //
    // This string is used VERBATIM as the summary text of the relevant Settings item!
    @Override
    public String getDestinationString(String transportName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getDestinationString");

        final IBackupTransport transport = mTransportManager.getTransportBinder(transportName);
        if (transport != null) {
            try {
                final String text = transport.currentDestinationString();
                if (MORE_DEBUG) Slog.d(TAG, "getDestinationString() returning " + text);
                return text;
            } catch (Exception e) {
                /* fall through to return null */
                Slog.e(TAG, "Unable to get string from transport: " + e.getMessage());
            }
        }

        return null;
    }

    // Supply the manage-data intent for the given transport.
    @Override
    public Intent getDataManagementIntent(String transportName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getDataManagementIntent");

        final IBackupTransport transport = mTransportManager.getTransportBinder(transportName);
        if (transport != null) {
            try {
                final Intent intent = transport.dataManagementIntent();
                if (MORE_DEBUG) {
                    Slog.d(TAG, "getDataManagementIntent() returning intent "
                            + intent);
                }
                return intent;
            } catch (Exception e) {
                /* fall through to return null */
                Slog.e(TAG, "Unable to get management intent from transport: " + e.getMessage());
            }
        }

        return null;
    }

    // Supply the menu label for affordances that fire the manage-data intent
    // for the given transport.
    @Override
    public String getDataManagementLabel(String transportName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getDataManagementLabel");

        final IBackupTransport transport = mTransportManager.getTransportBinder(transportName);
        if (transport != null) {
            try {
                final String text = transport.dataManagementLabel();
                if (MORE_DEBUG) Slog.d(TAG, "getDataManagementLabel() returning " + text);
                return text;
            } catch (Exception e) {
                /* fall through to return null */
                Slog.e(TAG, "Unable to get management label from transport: " + e.getMessage());
            }
        }

        return null;
    }

    // Callback: a requested backup agent has been instantiated.  This should only
    // be called from the Activity Manager.
    @Override
    public void agentConnected(String packageName, IBinder agentBinder) {
        synchronized (mAgentConnectLock) {
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
    @Override
    public void agentDisconnected(String packageName) {
        // TODO: handle backup being interrupted
        synchronized (mAgentConnectLock) {
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
    @Override
    public void restoreAtInstall(String packageName, int token) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            Slog.w(TAG, "Non-system process uid=" + Binder.getCallingUid()
                    + " attemping install-time restore");
            return;
        }

        boolean skip = false;

        long restoreSet = getAvailableRestoreToken(packageName);
        if (DEBUG) {
            Slog.v(TAG, "restoreAtInstall pkg=" + packageName
                    + " token=" + Integer.toHexString(token)
                    + " restoreSet=" + Long.toHexString(restoreSet));
        }
        if (restoreSet == 0) {
            if (MORE_DEBUG) Slog.i(TAG, "No restore set");
            skip = true;
        }

        // Do we have a transport to fetch data for us?
        IBackupTransport transport = mTransportManager.getCurrentTransportBinder();
        if (transport == null) {
            if (DEBUG) Slog.w(TAG, "No transport");
            skip = true;
        }

        if (!mAutoRestore) {
            if (DEBUG) {
                Slog.w(TAG, "Non-restorable state: auto=" + mAutoRestore);
            }
            skip = true;
        }

        if (!skip) {
            try {
                // okay, we're going to attempt a restore of this package from this restore set.
                // The eventual message back into the Package Manager to run the post-install
                // steps for 'token' will be issued from the restore handling code.

                // This can throw and so *must* happen before the wakelock is acquired
                String dirName = transport.transportDirName();

                mWakelock.acquire();
                if (MORE_DEBUG) {
                    Slog.d(TAG, "Restore at install of " + packageName);
                }
                Message msg = mBackupHandler.obtainMessage(MSG_RUN_RESTORE);
                msg.obj = new RestoreParams(transport, dirName, null, null,
                        restoreSet, packageName, token);
                mBackupHandler.sendMessage(msg);
            } catch (Exception e) {
                // Calling into the transport broke; back off and proceed with the installation.
                Slog.e(TAG, "Unable to contact transport: " + e.getMessage());
                skip = true;
            }
        }

        if (skip) {
            // Auto-restore disabled or no way to attempt a restore; just tell the Package
            // Manager to proceed with the post-install handling for this package.
            if (DEBUG) Slog.v(TAG, "Finishing install immediately");
            try {
                mPackageManagerBinder.finishPackageInstall(token, false);
            } catch (RemoteException e) { /* can't happen */ }
        }
    }

    // Hand off a restore session
    @Override
    public IRestoreSession beginRestoreSession(String packageName, String transport) {
        if (DEBUG) {
            Slog.v(TAG, "beginRestoreSession: pkg=" + packageName
                    + " transport=" + transport);
        }

        boolean needPermission = true;
        if (transport == null) {
            transport = mTransportManager.getCurrentTransportName();

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

        synchronized (this) {
            if (mActiveRestoreSession != null) {
                Slog.i(TAG, "Restore session requested but one already active");
                return null;
            }
            if (mBackupRunning) {
                Slog.i(TAG, "Restore session requested but currently running backups");
                return null;
            }
            mActiveRestoreSession = new ActiveRestoreSession(this, packageName, transport);
            mBackupHandler.sendEmptyMessageDelayed(MSG_RESTORE_SESSION_TIMEOUT,
                    TIMEOUT_RESTORE_INTERVAL);
        }
        return mActiveRestoreSession;
    }

    public void clearRestoreSession(ActiveRestoreSession currentSession) {
        synchronized (this) {
            if (currentSession != mActiveRestoreSession) {
                Slog.e(TAG, "ending non-current restore session");
            } else {
                if (DEBUG) Slog.v(TAG, "Clearing restore session and halting timeout");
                mActiveRestoreSession = null;
                mBackupHandler.removeMessages(MSG_RESTORE_SESSION_TIMEOUT);
            }
        }
    }

    // Note that a currently-active backup agent has notified us that it has
    // completed the given outstanding asynchronous backup/restore operation.
    @Override
    public void opComplete(int token, long result) {
        if (MORE_DEBUG) {
            Slog.v(TAG, "opComplete: " + Integer.toHexString(token) + " result=" + result);
        }
        Operation op = null;
        synchronized (mCurrentOpLock) {
            op = mCurrentOperations.get(token);
            if (op != null) {
                if (op.state == OP_TIMEOUT) {
                    // The operation already timed out, and this is a late response.  Tidy up
                    // and ignore it; we've already dealt with the timeout.
                    op = null;
                    mCurrentOperations.delete(token);
                } else if (op.state == OP_ACKNOWLEDGED) {
                    if (DEBUG) {
                        Slog.w(TAG, "Received duplicate ack for token=" +
                                Integer.toHexString(token));
                    }
                    op = null;
                    mCurrentOperations.remove(token);
                } else if (op.state == OP_PENDING) {
                    // Can't delete op from mCurrentOperations. waitUntilOperationComplete can be
                    // called after we we receive this call.
                    op.state = OP_ACKNOWLEDGED;
                }
            }
            mCurrentOpLock.notifyAll();
        }

        // The completion callback, if any, is invoked on the handler
        if (op != null && op.callback != null) {
            Pair<BackupRestoreTask, Long> callbackAndResult = Pair.create(op.callback, result);
            Message msg = mBackupHandler.obtainMessage(MSG_OP_COMPLETE, callbackAndResult);
            mBackupHandler.sendMessage(msg);
        }
    }

    // We also avoid backups of 'disabled' apps
    private static boolean appIsDisabled(ApplicationInfo app, PackageManager pm) {
        switch (pm.getApplicationEnabledSetting(app.packageName)) {
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return true;

            default:
                return false;
        }
    }

    @Override
    public boolean isAppEligibleForBackup(String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "isAppEligibleForBackup");
        try {
            PackageInfo packageInfo = mPackageManager.getPackageInfo(packageName,
                    PackageManager.GET_SIGNATURES);
            if (!AppBackupUtils.appIsEligibleForBackup(packageInfo.applicationInfo) ||
                    AppBackupUtils.appIsStopped(packageInfo.applicationInfo) ||
                    appIsDisabled(packageInfo.applicationInfo, mPackageManager)) {
                return false;
            }
            IBackupTransport transport = mTransportManager.getCurrentTransportBinder();
            if (transport != null) {
                try {
                    return transport.isAppEligibleForBackup(packageInfo,
                            AppBackupUtils.appGetsFullBackup(packageInfo));
                } catch (Exception e) {
                    Slog.e(TAG, "Unable to ask about eligibility: " + e.getMessage());
                }
            }
            // If transport is not present we couldn't tell that the package is not eligible.
            return true;
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;

        long identityToken = Binder.clearCallingIdentity();
        try {
            if (args != null) {
                for (String arg : args) {
                    if ("-h".equals(arg)) {
                        pw.println("'dumpsys backup' optional arguments:");
                        pw.println("  -h       : this help text");
                        pw.println("  a[gents] : dump information about defined backup agents");
                        return;
                    } else if ("agents".startsWith(arg)) {
                        dumpAgents(pw);
                        return;
                    }
                }
            }
            dumpInternal(pw);
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
    }

    private void dumpAgents(PrintWriter pw) {
        List<PackageInfo> agentPackages = allAgentPackages();
        pw.println("Defined backup agents:");
        for (PackageInfo pkg : agentPackages) {
            pw.print("  ");
            pw.print(pkg.packageName);
            pw.println(':');
            pw.print("      ");
            pw.println(pkg.applicationInfo.backupAgentName);
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
            pw.println("  next scheduled: " + KeyValueBackupJob.nextScheduled());

            pw.println("Transport whitelist:");
            for (ComponentName transport : mTransportManager.getTransportWhitelist()) {
                pw.print("    ");
                pw.println(transport.flattenToShortString());
            }

            pw.println("Available transports:");
            final String[] transports = listAllTransports();
            if (transports != null) {
                for (String t : listAllTransports()) {
                    pw.println((t.equals(mTransportManager.getCurrentTransportName()) ? "  * "
                            : "    ") + t);
                    try {
                        IBackupTransport transport = mTransportManager.getTransportBinder(t);
                        File dir = new File(mBaseStateDir, transport.transportDirName());
                        pw.println("       destination: " + transport.currentDestinationString());
                        pw.println("       intent: " + transport.configurationIntent());
                        for (File f : dir.listFiles()) {
                            pw.println(
                                    "       " + f.getName() + " - " + f.length() + " state bytes");
                        }
                    } catch (Exception e) {
                        Slog.e(TAG, "Error in transport", e);
                        pw.println("        Error: " + e);
                    }
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

            pw.print("Ancestral: ");
            pw.println(Long.toHexString(mAncestralToken));
            pw.print("Current:   ");
            pw.println(Long.toHexString(mCurrentToken));

            int N = mBackupParticipants.size();
            pw.println("Participants:");
            for (int i = 0; i < N; i++) {
                int uid = mBackupParticipants.keyAt(i);
                pw.print("  uid: ");
                pw.println(uid);
                HashSet<String> participants = mBackupParticipants.valueAt(i);
                for (String app : participants) {
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

            pw.println("Pending key/value backup: " + mPendingBackups.size());
            for (BackupRequest req : mPendingBackups.values()) {
                pw.println("    " + req);
            }

            pw.println("Full backup queue:" + mFullBackupQueue.size());
            for (FullBackupEntry entry : mFullBackupQueue) {
                pw.print("    ");
                pw.print(entry.lastBackup);
                pw.print(" : ");
                pw.println(entry.packageName);
            }
        }
    }


    @Override
    public IBackupManager getBackupManagerBinder() {
        return mBackupManagerBinder;
    }

}
