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
 * limitations under the License.
 */

package com.android.server.backup;

import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_BACKUP_IN_FOREGROUND;

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.DEBUG_SCHEDULING;
import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;
import static com.android.server.backup.internal.BackupHandler.MSG_BACKUP_OPERATION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_FULL_CONFIRMATION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_OP_COMPLETE;
import static com.android.server.backup.internal.BackupHandler.MSG_REQUEST_BACKUP;
import static com.android.server.backup.internal.BackupHandler.MSG_RESTORE_OPERATION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_RESTORE_SESSION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_RETRY_CLEAR;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_ADB_BACKUP;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_ADB_RESTORE;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_BACKUP;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_CLEAR;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_RESTORE;
import static com.android.server.backup.internal.BackupHandler.MSG_SCHEDULE_BACKUP_PACKAGE;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IBackupAgent;
import android.app.PendingIntent;
import android.app.backup.BackupAgent;
import android.app.backup.BackupManager;
import android.app.backup.BackupManagerMonitor;
import android.app.backup.FullBackup;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IFullBackupRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.ISelectBackupTransportCallback;
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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
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
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.Preconditions;
import com.android.server.AppWidgetBackupBridge;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.backup.fullbackup.FullBackupEntry;
import com.android.server.backup.fullbackup.PerformFullTransportBackupTask;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.backup.internal.ClearDataObserver;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.internal.Operation;
import com.android.server.backup.internal.PerformInitializeTask;
import com.android.server.backup.internal.RunInitializeReceiver;
import com.android.server.backup.internal.SetupObserver;
import com.android.server.backup.keyvalue.BackupRequest;
import com.android.server.backup.params.AdbBackupParams;
import com.android.server.backup.params.AdbParams;
import com.android.server.backup.params.AdbRestoreParams;
import com.android.server.backup.params.BackupParams;
import com.android.server.backup.params.ClearParams;
import com.android.server.backup.params.ClearRetryParams;
import com.android.server.backup.params.RestoreParams;
import com.android.server.backup.restore.ActiveRestoreSession;
import com.android.server.backup.restore.PerformUnifiedRestoreTask;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportNotRegisteredException;
import com.android.server.backup.utils.AppBackupUtils;
import com.android.server.backup.utils.BackupManagerMonitorUtils;
import com.android.server.backup.utils.BackupObserverUtils;
import com.android.server.backup.utils.FileUtils;
import com.android.server.backup.utils.SparseArrayUtils;

import com.google.android.collect.Sets;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** System service that performs backup/restore operations. */
public class UserBackupManagerService {
    /**
     * Wrapper over {@link PowerManager.WakeLock} to prevent double-free exceptions on release()
     * after quit().
     */
    public static class BackupWakeLock {
        private final PowerManager.WakeLock mPowerManagerWakeLock;
        private boolean mHasQuit = false;
        private int mUserId;

        public BackupWakeLock(PowerManager.WakeLock powerManagerWakeLock, int userId) {
            mPowerManagerWakeLock = powerManagerWakeLock;
            mUserId = userId;
        }

        /** Acquires the {@link PowerManager.WakeLock} if hasn't been quit. */
        public synchronized void acquire() {
            if (mHasQuit) {
                Slog.v(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "Ignore wakelock acquire after quit: "
                                        + mPowerManagerWakeLock.getTag()));
                return;
            }
            mPowerManagerWakeLock.acquire();
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId, "Acquired wakelock:" + mPowerManagerWakeLock.getTag()));
        }

        /** Releases the {@link PowerManager.WakeLock} if hasn't been quit. */
        public synchronized void release() {
            if (mHasQuit) {
                Slog.v(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "Ignore wakelock release after quit: "
                                        + mPowerManagerWakeLock.getTag()));
                return;
            }
            mPowerManagerWakeLock.release();
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId, "Released wakelock:" + mPowerManagerWakeLock.getTag()));
        }

        /**
         * Returns true if the {@link PowerManager.WakeLock} has been acquired but not yet released.
         */
        public synchronized boolean isHeld() {
            return mPowerManagerWakeLock.isHeld();
        }

        /** Release the {@link PowerManager.WakeLock} till it isn't held. */
        public synchronized void quit() {
            while (mPowerManagerWakeLock.isHeld()) {
                Slog.v(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "Releasing wakelock: " + mPowerManagerWakeLock.getTag()));
                mPowerManagerWakeLock.release();
            }
            mHasQuit = true;
        }
    }

    // Persistently track the need to do a full init.
    private static final String INIT_SENTINEL_FILE_NAME = "_need_init_";

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

    private static final int CURRENT_ANCESTRAL_RECORD_VERSION = 1;

    // Round-robin queue for scheduling full backup passes.
    private static final int SCHEDULE_FILE_VERSION = 1;

    public static final String SETTINGS_PACKAGE = "com.android.providers.settings";
    public static final String SHARED_BACKUP_AGENT_PACKAGE = "com.android.sharedstoragebackup";

    // Pseudoname that we use for the Package Manager metadata "package".
    public static final String PACKAGE_MANAGER_SENTINEL = "@pm@";

    // Retry interval for clear/init when the transport is unavailable
    private static final long TRANSPORT_RETRY_INTERVAL = 1 * AlarmManager.INTERVAL_HOUR;

    public static final String RUN_INITIALIZE_ACTION = "android.app.backup.intent.INIT";
    private static final String BACKUP_FINISHED_ACTION = "android.intent.action.BACKUP_FINISHED";
    private static final String BACKUP_FINISHED_PACKAGE_EXTRA = "packageName";

    // Bookkeeping of in-flight operations. The operation token is the index of the entry in the
    // pending operations list.
    public static final int OP_PENDING = 0;
    private static final int OP_ACKNOWLEDGED = 1;
    private static final int OP_TIMEOUT = -1;

    // Waiting for backup agent to respond during backup operation.
    public static final int OP_TYPE_BACKUP_WAIT = 0;

    // Waiting for backup agent to respond during restore operation.
    public static final int OP_TYPE_RESTORE_WAIT = 1;

    // An entire backup operation spanning multiple packages.
    public static final int OP_TYPE_BACKUP = 2;

    // Time delay for initialization operations that can be delayed so as not to consume too much
    // CPU on bring-up and increase time-to-UI.
    private static final long INITIALIZATION_DELAY_MILLIS = 3000;

    // Timeout interval for deciding that a bind has taken too long.
    private static final long BIND_TIMEOUT_INTERVAL = 10 * 1000;
    // Timeout interval for deciding that a clear-data has taken too long.
    private static final long CLEAR_DATA_TIMEOUT_INTERVAL = 30 * 1000;

    // User confirmation timeout for a full backup/restore operation.  It's this long in
    // order to give them time to enter the backup password.
    private static final long TIMEOUT_FULL_CONFIRMATION = 60 * 1000;

    // If an app is busy when we want to do a full-data backup, how long to defer the retry.
    // This is fuzzed, so there are two parameters; backoff_min + Rand[0, backoff_fuzz)
    private static final long BUSY_BACKOFF_MIN_MILLIS = 1000 * 60 * 60;  // one hour
    private static final int BUSY_BACKOFF_FUZZ = 1000 * 60 * 60 * 2;  // two hours

    private static final String SERIAL_ID_FILE = "serial_id";

    private final @UserIdInt int mUserId;
    private final BackupAgentTimeoutParameters mAgentTimeoutParameters;
    private final TransportManager mTransportManager;

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final IPackageManager mPackageManagerBinder;
    private final IActivityManager mActivityManager;
    private final ActivityManagerInternal mActivityManagerInternal;
    private PowerManager mPowerManager;
    private final AlarmManager mAlarmManager;
    private final IStorageManager mStorageManager;
    private final BackupManagerConstants mConstants;
    private final BackupWakeLock mWakelock;
    private final BackupHandler mBackupHandler;

    private final IBackupManager mBackupManagerBinder;

    private boolean mEnabled;   // access to this is synchronized on 'this'
    private boolean mSetupComplete;
    private boolean mAutoRestore;

    private final PendingIntent mRunInitIntent;

    private final ArraySet<String> mPendingInits = new ArraySet<>();  // transport names

    // map UIDs to the set of participating packages under that UID
    private final SparseArray<HashSet<String>> mBackupParticipants = new SparseArray<>();

    // Backups that we haven't started yet.  Keys are package names.
    private final HashMap<String, BackupRequest> mPendingBackups = new HashMap<>();

    // locking around the pending-backup management
    private final Object mQueueLock = new Object();

    private final UserBackupPreferences mBackupPreferences;

    // The thread performing the sequence of queued backups binds to each app's agent
    // in succession.  Bind notifications are asynchronously delivered through the
    // Activity Manager; use this lock object to signal when a requested binding has
    // completed.
    private final Object mAgentConnectLock = new Object();
    private IBackupAgent mConnectedAgent;
    private volatile boolean mConnecting;

    private volatile boolean mBackupRunning;
    private volatile long mLastBackupPass;

    // A similar synchronization mechanism around clearing apps' data for restore
    private final Object mClearDataLock = new Object();
    private volatile boolean mClearingData;

    // Used by ADB.
    private final BackupPasswordManager mBackupPasswordManager;
    private final SparseArray<AdbParams> mAdbBackupRestoreConfirmations = new SparseArray<>();
    private final SecureRandom mRng = new SecureRandom();

    // Time when we post the transport registration operation
    private final long mRegisterTransportsRequestedTime;

    @GuardedBy("mQueueLock")
    private PerformFullTransportBackupTask mRunningFullBackupTask;

    @GuardedBy("mQueueLock")
    private ArrayList<FullBackupEntry> mFullBackupQueue;

    @GuardedBy("mPendingRestores")
    private boolean mIsRestoreInProgress;

    @GuardedBy("mPendingRestores")
    private final Queue<PerformUnifiedRestoreTask> mPendingRestores = new ArrayDeque<>();

    private ActiveRestoreSession mActiveRestoreSession;

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
     * {@link UserBackupManagerService#waitUntilOperationComplete(int)} is
     * used in various places to 'wait' for notifyAll and detect change of pending state of an
     * operation. So typically, an operation will be removed from this array by:
     * - BackupRestoreTask#handleCancel and
     * - BackupRestoreTask#operationComplete OR waitUntilOperationComplete. Do not remove at both
     * these places because waitUntilOperationComplete relies on the operation being present to
     * determine its completion status.
     *
     * If type of operation is OP_BACKUP, it is a task running backups. It provides a handle to
     * cancel backup tasks.
     */
    @GuardedBy("mCurrentOpLock")
    private final SparseArray<Operation> mCurrentOperations = new SparseArray<>();
    private final Object mCurrentOpLock = new Object();
    private final Random mTokenGenerator = new Random();
    private final AtomicInteger mNextToken = new AtomicInteger();

    // Where we keep our journal files and other bookkeeping.
    private final File mBaseStateDir;
    private final File mDataDir;
    private final File mJournalDir;
    @Nullable
    private DataChangedJournal mJournal;
    private final File mFullBackupScheduleFile;

    // Keep a log of all the apps we've ever backed up.
    private ProcessedPackagesJournal mProcessedPackagesJournal;

    private File mTokenFile;
    private Set<String> mAncestralPackages = null;
    private long mAncestralToken = 0;
    private long mCurrentToken = 0;
    @Nullable private File mAncestralSerialNumberFile;

    private final ContentObserver mSetupObserver;
    private final BroadcastReceiver mRunInitReceiver;

    /**
     * Creates an instance of {@link UserBackupManagerService} and initializes state for it. This
     * includes setting up the directories where we keep our bookkeeping and transport management.
     *
     * @see #createAndInitializeService(int, Context, BackupManagerService, HandlerThread, File,
     * File, TransportManager)
     */
    static UserBackupManagerService createAndInitializeService(
            @UserIdInt int userId,
            Context context,
            BackupManagerService backupManagerService,
            Set<ComponentName> transportWhitelist) {
        String currentTransport =
                Settings.Secure.getStringForUser(
                        context.getContentResolver(), Settings.Secure.BACKUP_TRANSPORT, userId);
        if (TextUtils.isEmpty(currentTransport)) {
            currentTransport = null;
        }

        if (DEBUG) {
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(userId, "Starting with transport " + currentTransport));
        }
        TransportManager transportManager =
                new TransportManager(userId, context, transportWhitelist, currentTransport);

        File baseStateDir = UserBackupManagerFiles.getBaseStateDir(userId);
        File dataDir = UserBackupManagerFiles.getDataDir(userId);

        HandlerThread userBackupThread =
                new HandlerThread("backup-" + userId, Process.THREAD_PRIORITY_BACKGROUND);
        userBackupThread.start();
        if (DEBUG) {
            Slog.d(
                    TAG,
                    addUserIdToLogMessage(userId, "Started thread " + userBackupThread.getName()));
        }

        return createAndInitializeService(
                userId,
                context,
                backupManagerService,
                userBackupThread,
                baseStateDir,
                dataDir,
                transportManager);
    }

    /**
     * Creates an instance of {@link UserBackupManagerService}.
     *
     * @param userId The user which this service is for.
     * @param context The system server context.
     * @param backupManagerService A reference to the proxy to {@link BackupManagerService}.
     * @param userBackupThread The thread running backup/restore operations for the user.
     * @param baseStateDir The directory we store the user's persistent bookkeeping data.
     * @param dataDir The directory we store the user's temporary staging data.
     * @param transportManager The {@link TransportManager} responsible for handling the user's
     *     transports.
     */
    @VisibleForTesting
    public static UserBackupManagerService createAndInitializeService(
            @UserIdInt int userId,
            Context context,
            BackupManagerService backupManagerService,
            HandlerThread userBackupThread,
            File baseStateDir,
            File dataDir,
            TransportManager transportManager) {
        return new UserBackupManagerService(
                userId,
                context,
                backupManagerService,
                userBackupThread,
                baseStateDir,
                dataDir,
                transportManager);
    }

    /**
     * Returns the value of {@link Settings.Secure#USER_SETUP_COMPLETE} for the specified user
     * {@code userId} as a {@code boolean}.
     */
    public static boolean getSetupCompleteSettingForUser(Context context, int userId) {
        return Settings.Secure.getIntForUser(
                context.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE,
                0,
                userId)
                != 0;
    }

    private UserBackupManagerService(
            @UserIdInt int userId,
            Context context,
            BackupManagerService parent,
            HandlerThread userBackupThread,
            File baseStateDir,
            File dataDir,
            TransportManager transportManager) {
        mUserId = userId;
        mContext = Objects.requireNonNull(context, "context cannot be null");
        mPackageManager = context.getPackageManager();
        mPackageManagerBinder = AppGlobals.getPackageManager();
        mActivityManager = ActivityManager.getService();
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);

        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mStorageManager = IStorageManager.Stub.asInterface(ServiceManager.getService("mount"));

        Objects.requireNonNull(parent, "parent cannot be null");
        mBackupManagerBinder = BackupManagerService.asInterface(parent.asBinder());

        mAgentTimeoutParameters = new
                BackupAgentTimeoutParameters(Handler.getMain(), mContext.getContentResolver());
        mAgentTimeoutParameters.start();

        Objects.requireNonNull(userBackupThread, "userBackupThread cannot be null");
        mBackupHandler = new BackupHandler(this, userBackupThread);

        // Set up our bookkeeping
        final ContentResolver resolver = context.getContentResolver();
        mSetupComplete = getSetupCompleteSettingForUser(context, userId);
        mAutoRestore = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.BACKUP_AUTO_RESTORE, 1, userId) != 0;

        mSetupObserver = new SetupObserver(this, mBackupHandler);
        resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE),
                /* notifyForDescendents */ false,
                mSetupObserver,
                mUserId);

        mBaseStateDir = Objects.requireNonNull(baseStateDir, "baseStateDir cannot be null");
        // TODO (b/120424138): Remove once the system user is migrated to use the per-user CE
        // directory. Per-user CE directories are managed by vold.
        if (userId == UserHandle.USER_SYSTEM) {
            mBaseStateDir.mkdirs();
            if (!SELinux.restorecon(mBaseStateDir)) {
                Slog.w(
                        TAG,
                        addUserIdToLogMessage(
                                userId, "SELinux restorecon failed on " + mBaseStateDir));
            }
        }

        // TODO (b/120424138): The system user currently uses the cache which is managed by init.rc
        // Initialization and restorecon is managed by vold for per-user CE directories.
        mDataDir = Objects.requireNonNull(dataDir, "dataDir cannot be null");
        mBackupPasswordManager = new BackupPasswordManager(mContext, mBaseStateDir, mRng);

        // Receiver for transport initialization.
        mRunInitReceiver = new RunInitializeReceiver(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(RUN_INITIALIZE_ACTION);
        context.registerReceiverAsUser(
                mRunInitReceiver,
                UserHandle.of(userId),
                filter,
                android.Manifest.permission.BACKUP,
                /* scheduler */ null);

        Intent initIntent = new Intent(RUN_INITIALIZE_ACTION);
        initIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mRunInitIntent =
                PendingIntent.getBroadcastAsUser(
                        context,
                        /* requestCode */ 0,
                        initIntent,
                        /* flags */ 0,
                        UserHandle.of(userId));

        // Set up the backup-request journaling
        mJournalDir = new File(mBaseStateDir, "pending");
        mJournalDir.mkdirs();   // creates mBaseStateDir along the way
        mJournal = null;        // will be created on first use

        mConstants = new BackupManagerConstants(mBackupHandler, mContext.getContentResolver());
        // We are observing changes to the constants throughout the lifecycle of BMS. This is
        // because we reference the constants in multiple areas of BMS, which otherwise would
        // require frequent starting and stopping.
        mConstants.start();

        // Build our mapping of uid to backup client services.  This implicitly
        // schedules a backup pass on the Package Manager metadata the first
        // time anything needs to be backed up.
        synchronized (mBackupParticipants) {
            addPackageParticipantsLocked(null);
        }

        mTransportManager =
                Objects.requireNonNull(transportManager, "transportManager cannot be null");
        mTransportManager.setOnTransportRegisteredListener(this::onTransportRegistered);
        mRegisterTransportsRequestedTime = SystemClock.elapsedRealtime();
        mBackupHandler.postDelayed(
                mTransportManager::registerTransports, INITIALIZATION_DELAY_MILLIS);

        // Now that we know about valid backup participants, parse any leftover journal files into
        // the pending backup set
        mBackupHandler.postDelayed(this::parseLeftoverJournals, INITIALIZATION_DELAY_MILLIS);

        mBackupPreferences = new UserBackupPreferences(mContext, mBaseStateDir);

        // Power management
        mWakelock = new BackupWakeLock(
                mPowerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "*backup*-" + userId + "-" + userBackupThread.getThreadId()), userId);

        // Set up the various sorts of package tracking we do
        mFullBackupScheduleFile = new File(mBaseStateDir, "fb-schedule");
        initPackageTracking();
    }

    void initializeBackupEnableState() {
        boolean isEnabled = UserBackupManagerFilePersistedSettings.readBackupEnableState(mUserId);
        setBackupEnabled(isEnabled);
    }

    /** Cleans up state when the user of this service is stopped. */
    @VisibleForTesting
    protected void tearDownService() {
        mAgentTimeoutParameters.stop();
        mConstants.stop();
        mContext.getContentResolver().unregisterContentObserver(mSetupObserver);
        mContext.unregisterReceiver(mRunInitReceiver);
        mContext.unregisterReceiver(mPackageTrackingReceiver);
        mBackupHandler.stop();
    }

    public @UserIdInt int getUserId() {
        return mUserId;
    }

    public BackupManagerConstants getConstants() {
        return mConstants;
    }

    public BackupAgentTimeoutParameters getAgentTimeoutParameters() {
        return mAgentTimeoutParameters;
    }

    public Context getContext() {
        return mContext;
    }

    public PackageManager getPackageManager() {
        return mPackageManager;
    }

    public IPackageManager getPackageManagerBinder() {
        return mPackageManagerBinder;
    }

    public IActivityManager getActivityManager() {
        return mActivityManager;
    }

    public AlarmManager getAlarmManager() {
        return mAlarmManager;
    }

    @VisibleForTesting
    void setPowerManager(PowerManager powerManager) {
        mPowerManager = powerManager;
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

    public boolean isSetupComplete() {
        return mSetupComplete;
    }

    public void setSetupComplete(boolean setupComplete) {
        mSetupComplete = setupComplete;
    }

    public BackupWakeLock getWakelock() {
        return mWakelock;
    }

    /**
     * Sets the {@link WorkSource} of the {@link PowerManager.WakeLock} returned by {@link
     * #getWakelock()}.
     */
    @VisibleForTesting
    public void setWorkSource(@Nullable WorkSource workSource) {
        // TODO: This is for testing, unfortunately WakeLock is final and WorkSource is not exposed
        mWakelock.mPowerManagerWakeLock.setWorkSource(workSource);
    }

    public Handler getBackupHandler() {
        return mBackupHandler;
    }

    public PendingIntent getRunInitIntent() {
        return mRunInitIntent;
    }

    public HashMap<String, BackupRequest> getPendingBackups() {
        return mPendingBackups;
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

    public void setLastBackupPass(long lastBackupPass) {
        mLastBackupPass = lastBackupPass;
    }

    public Object getClearDataLock() {
        return mClearDataLock;
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

    public File getDataDir() {
        return mDataDir;
    }

    @VisibleForTesting
    BroadcastReceiver getPackageTrackingReceiver() {
        return mPackageTrackingReceiver;
    }

    @Nullable
    public DataChangedJournal getJournal() {
        return mJournal;
    }

    public void setJournal(@Nullable DataChangedJournal journal) {
        mJournal = journal;
    }

    public SecureRandom getRng() {
        return mRng;
    }

    public void setAncestralPackages(Set<String> ancestralPackages) {
        mAncestralPackages = ancestralPackages;
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

    /** Clear all pending transport initializations. */
    public void clearPendingInits() {
        mPendingInits.clear();
    }

    public void setRunningFullBackupTask(
            PerformFullTransportBackupTask runningFullBackupTask) {
        mRunningFullBackupTask = runningFullBackupTask;
    }

    /**
     *  Utility: build a new random integer token. The low bits are the ordinal of the operation for
     *  near-time uniqueness, and the upper bits are random for app-side unpredictability.
     */
    public int generateRandomIntegerToken() {
        int token = mTokenGenerator.nextInt();
        if (token < 0) token = -token;
        token &= ~0xFF;
        token |= (mNextToken.incrementAndGet() & 0xFF);
        return token;
    }

    /**
     * Construct a backup agent instance for the metadata pseudopackage. This is a process-local
     * non-lifecycle agent instance, so we manually set up the context topology for it.
     */
    public BackupAgent makeMetadataAgent() {
        PackageManagerBackupAgent pmAgent = new PackageManagerBackupAgent(mPackageManager, mUserId);
        pmAgent.attach(mContext);
        pmAgent.onCreate(UserHandle.of(mUserId));
        return pmAgent;
    }

    /**
     * Same as {@link #makeMetadataAgent()} but with explicit package-set configuration.
     */
    public PackageManagerBackupAgent makeMetadataAgent(List<PackageInfo> packages) {
        PackageManagerBackupAgent pmAgent =
                new PackageManagerBackupAgent(mPackageManager, packages, mUserId);
        pmAgent.attach(mContext);
        pmAgent.onCreate(UserHandle.of(mUserId));
        return pmAgent;
    }

    private void initPackageTracking() {
        if (MORE_DEBUG) Slog.v(TAG, addUserIdToLogMessage(mUserId, "` tracking"));

        // Remember our ancestral dataset
        mTokenFile = new File(mBaseStateDir, "ancestral");
        try (DataInputStream tokenStream = new DataInputStream(new BufferedInputStream(
                new FileInputStream(mTokenFile)))) {
            int version = tokenStream.readInt();
            if (version == CURRENT_ANCESTRAL_RECORD_VERSION) {
                mAncestralToken = tokenStream.readLong();
                mCurrentToken = tokenStream.readLong();

                int numPackages = tokenStream.readInt();
                if (numPackages >= 0) {
                    mAncestralPackages = new HashSet<>();
                    for (int i = 0; i < numPackages; i++) {
                        String pkgName = tokenStream.readUTF();
                        mAncestralPackages.add(pkgName);
                    }
                }
            }
        } catch (FileNotFoundException fnf) {
            // Probably innocuous
            Slog.v(TAG, addUserIdToLogMessage(mUserId, "No ancestral data"));
        } catch (IOException e) {
            Slog.w(TAG, addUserIdToLogMessage(mUserId, "Unable to read token file"), e);
        }

        mProcessedPackagesJournal = new ProcessedPackagesJournal(mBaseStateDir);
        mProcessedPackagesJournal.init();

        synchronized (mQueueLock) {
            // Resume the full-data backup queue
            mFullBackupQueue = readFullBackupSchedule();
        }

        // Register for broadcasts about package changes.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(
                mPackageTrackingReceiver,
                UserHandle.of(mUserId),
                filter,
                /* broadcastPermission */ null,
                /* scheduler */ null);

        // Register for events related to sdcard installation.
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiverAsUser(
                mPackageTrackingReceiver,
                UserHandle.of(mUserId),
                sdFilter,
                /* broadcastPermission */ null,
                /* scheduler */ null);
    }

    private ArrayList<FullBackupEntry> readFullBackupSchedule() {
        boolean changed = false;
        ArrayList<FullBackupEntry> schedule = null;
        List<PackageInfo> apps =
                PackageManagerBackupAgent.getStorableApplications(mPackageManager, mUserId);

        if (mFullBackupScheduleFile.exists()) {
            try (FileInputStream fstream = new FileInputStream(mFullBackupScheduleFile);
                 BufferedInputStream bufStream = new BufferedInputStream(fstream);
                 DataInputStream in = new DataInputStream(bufStream)) {
                int version = in.readInt();
                if (version != SCHEDULE_FILE_VERSION) {
                    Slog.e(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId, "Unknown backup schedule version " + version));
                    return null;
                }

                final int numPackages = in.readInt();
                schedule = new ArrayList<>(numPackages);

                // HashSet instead of ArraySet specifically because we want the eventual
                // lookups against O(hundreds) of entries to be as fast as possible, and
                // we discard the set immediately after the scan so the extra memory
                // overhead is transient.
                HashSet<String> foundApps = new HashSet<>(numPackages);

                for (int i = 0; i < numPackages; i++) {
                    String pkgName = in.readUTF();
                    long lastBackup = in.readLong();
                    foundApps.add(pkgName); // all apps that we've addressed already
                    try {
                        PackageInfo pkg = mPackageManager.getPackageInfoAsUser(pkgName, 0, mUserId);
                        if (AppBackupUtils.appGetsFullBackup(pkg)
                                && AppBackupUtils.appIsEligibleForBackup(pkg.applicationInfo,
                                mUserId)) {
                            schedule.add(new FullBackupEntry(pkgName, lastBackup));
                        } else {
                            if (DEBUG) {
                                Slog.i(TAG, addUserIdToLogMessage(mUserId, "Package " + pkgName
                                        + " no longer eligible for full backup"));
                            }
                        }
                    } catch (NameNotFoundException e) {
                        if (DEBUG) {
                            Slog.i(TAG, addUserIdToLogMessage(mUserId, "Package " + pkgName
                                    + " not installed; dropping from full backup"));
                        }
                    }
                }

                // New apps can arrive "out of band" via OTA and similar, so we also need to
                // scan to make sure that we're tracking all full-backup candidates properly
                for (PackageInfo app : apps) {
                    if (AppBackupUtils.appGetsFullBackup(app)
                            && AppBackupUtils.appIsEligibleForBackup(app.applicationInfo,
                            mUserId)) {
                        if (!foundApps.contains(app.packageName)) {
                            if (MORE_DEBUG) {
                                Slog.i(
                                        TAG,
                                        addUserIdToLogMessage(
                                                mUserId,
                                                "New full backup app "
                                                        + app.packageName
                                                        + " found"));
                            }
                            schedule.add(new FullBackupEntry(app.packageName, 0));
                            changed = true;
                        }
                    }
                }

                Collections.sort(schedule);
            } catch (Exception e) {
                Slog.e(TAG, addUserIdToLogMessage(mUserId, "Unable to read backup schedule"), e);
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
                        info.applicationInfo, mUserId)) {
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
                    int numPackages = mFullBackupQueue.size();
                    bufOut.writeInt(numPackages);

                    for (int i = 0; i < numPackages; i++) {
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
                    Slog.e(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId, "Unable to write backup schedule!"),
                            e);
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
                        Slog.i(
                                TAG,
                                addUserIdToLogMessage(
                                        mUserId, "Found stale backup journal, scheduling"));
                        if (MORE_DEBUG) {
                            Slog.i(TAG, addUserIdToLogMessage(mUserId, "  " + packageName));
                        }
                        dataChangedImpl(packageName);
                    });
                } catch (IOException e) {
                    Slog.e(TAG, addUserIdToLogMessage(mUserId, "Can't read " + journal), e);
                }
            }
        }
    }

    public Set<String> getExcludedRestoreKeys(String packageName) {
        return mBackupPreferences.getExcludedRestoreKeysForPackage(packageName);
    }

    /** Used for generating random salts or passwords. */
    public byte[] randomBytes(int bits) {
        byte[] array = new byte[bits / 8];
        mRng.nextBytes(array);
        return array;
    }

    /** For adb backup/restore. */
    public boolean setBackupPassword(String currentPw, String newPw) {
        return mBackupPasswordManager.setBackupPassword(currentPw, newPw);
    }

    /** For adb backup/restore. */
    public boolean hasBackupPassword() {
        return mBackupPasswordManager.hasBackupPassword();
    }

    /** For adb backup/restore. */
    public boolean backupPasswordMatches(String currentPw) {
        return mBackupPasswordManager.backupPasswordMatches(currentPw);
    }

    /**
     * Maintain persistent state around whether need to do an initialize operation. This will lock
     * on {@link #getQueueLock()}.
     */
    public void recordInitPending(
            boolean isPending, String transportName, String transportDirName) {
        synchronized (mQueueLock) {
            if (MORE_DEBUG) {
                Slog.i(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "recordInitPending("
                                        + isPending
                                        + ") on transport "
                                        + transportName));
            }

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
        }
    }

    /**
     * Reset all of our bookkeeping because the backend data has been wiped (for example due to idle
     * expiry), so we must re-upload all saved settings.
     */
    public void resetBackupState(File stateFileDir) {
        synchronized (mQueueLock) {
            mProcessedPackagesJournal.reset();

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
            final int numParticipants = mBackupParticipants.size();
            for (int i = 0; i < numParticipants; i++) {
                HashSet<String> participants = mBackupParticipants.valueAt(i);
                if (participants != null) {
                    for (String packageName : participants) {
                        dataChangedImpl(packageName);
                    }
                }
            }
        }
    }

    private void onTransportRegistered(String transportName, String transportDirName) {
        if (DEBUG) {
            long timeMs = SystemClock.elapsedRealtime() - mRegisterTransportsRequestedTime;
            Slog.d(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "Transport "
                                    + transportName
                                    + " registered "
                                    + timeMs
                                    + "ms after first request (delay = "
                                    + INITIALIZATION_DELAY_MILLIS
                                    + "ms)"));
        }

        File stateDir = new File(mBaseStateDir, transportDirName);
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
    }

    /**
     * A {@link BroadcastReceiver} tracking changes to packages and sd cards in order to update our
     * internal bookkeeping.
     */
    private BroadcastReceiver mPackageTrackingReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (MORE_DEBUG) {
                Slog.d(TAG, addUserIdToLogMessage(mUserId, "Received broadcast " + intent));
            }

            String action = intent.getAction();
            boolean replacing = false;
            boolean added = false;
            boolean changed = false;
            Bundle extras = intent.getExtras();
            String[] packageList = null;

            if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_REMOVED.equals(action)
                    || Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                Uri uri = intent.getData();
                if (uri == null) {
                    return;
                }

                String packageName = uri.getSchemeSpecificPart();
                if (packageName != null) {
                    packageList = new String[] {packageName};
                }

                changed = Intent.ACTION_PACKAGE_CHANGED.equals(action);
                if (changed) {
                    // Look at new transport states for package changed events.
                    String[] components =
                            intent.getStringArrayExtra(
                                    Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);

                    if (MORE_DEBUG) {
                        Slog.i(
                                TAG,
                                addUserIdToLogMessage(
                                        mUserId, "Package " + packageName + " changed"));
                        for (int i = 0; i < components.length; i++) {
                            Slog.i(
                                    TAG,
                                    addUserIdToLogMessage(
                                            mUserId, "   * " + components[i]));
                        }
                    }

                    mBackupHandler.post(
                            () ->
                                    mTransportManager.onPackageChanged(
                                            packageName, components));
                    return;
                }

                added = Intent.ACTION_PACKAGE_ADDED.equals(action);
                replacing = extras.getBoolean(Intent.EXTRA_REPLACING, false);
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
                added = true;
                packageList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                added = false;
                packageList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            }

            if (packageList == null || packageList.length == 0) {
                return;
            }

            int uid = extras.getInt(Intent.EXTRA_UID);
            if (added) {
                synchronized (mBackupParticipants) {
                    if (replacing) {
                        // Remove the entry under the old uid and fall through to re-add. If
                        // an app
                        // just opted into key/value backup, add it as a known participant.
                        removePackageParticipantsLocked(packageList, uid);
                    }
                    addPackageParticipantsLocked(packageList);
                }

                long now = System.currentTimeMillis();
                for (String packageName : packageList) {
                    try {
                        PackageInfo app =
                                mPackageManager.getPackageInfoAsUser(
                                        packageName, /* flags */ 0, mUserId);
                        if (AppBackupUtils.appGetsFullBackup(app)
                                && AppBackupUtils.appIsEligibleForBackup(
                                        app.applicationInfo, mUserId)) {
                            enqueueFullBackup(packageName, now);
                            scheduleNextFullBackupJob(0);
                        } else {
                            // The app might have just transitioned out of full-data into
                            // doing
                            // key/value backups, or might have just disabled backups
                            // entirely. Make
                            // sure it is no longer in the full-data queue.
                            synchronized (mQueueLock) {
                                dequeueFullBackupLocked(packageName);
                            }
                            writeFullBackupScheduleAsync();
                        }

                        mBackupHandler.post(
                                () -> mTransportManager.onPackageAdded(packageName));
                    } catch (NameNotFoundException e) {
                        if (DEBUG) {
                            Slog.w(
                                    TAG,
                                    addUserIdToLogMessage(
                                            mUserId,
                                            "Can't resolve new app " + packageName));
                        }
                    }
                }

                // Whenever a package is added or updated we need to update the package
                // metadata
                // bookkeeping.
                dataChangedImpl(PACKAGE_MANAGER_SENTINEL);
            } else {
                if (!replacing) {
                    // Outright removal. In the full-data case, the app will be dropped from
                    // the
                    // queue when its (now obsolete) name comes up again for backup.
                    synchronized (mBackupParticipants) {
                        removePackageParticipantsLocked(packageList, uid);
                    }
                }

                for (String packageName : packageList) {
                    mBackupHandler.post(
                            () -> mTransportManager.onPackageRemoved(packageName));
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
            if (MORE_DEBUG) {
                Slog.v(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "addPackageParticipantsLocked: #" + packageNames.length));
            }
            for (String packageName : packageNames) {
                addPackageParticipantsLockedInner(packageName, targetApps);
            }
        } else {
            if (MORE_DEBUG) {
                Slog.v(TAG, addUserIdToLogMessage(mUserId, "addPackageParticipantsLocked: all"));
            }
            addPackageParticipantsLockedInner(null, targetApps);
        }
    }

    private void addPackageParticipantsLockedInner(String packageName,
            List<PackageInfo> targetPkgs) {
        if (MORE_DEBUG) {
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId, "Examining " + packageName + " for backup agent"));
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
                if (MORE_DEBUG) Slog.v(TAG, addUserIdToLogMessage(mUserId, "Agent found; added"));

                // Schedule a backup for it on general principles
                if (MORE_DEBUG) {
                    Slog.i(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId, "Scheduling backup for new app " + pkg.packageName));
                }
                Message msg = mBackupHandler
                        .obtainMessage(MSG_SCHEDULE_BACKUP_PACKAGE, pkg.packageName);
                mBackupHandler.sendMessage(msg);
            }
        }
    }

    // Remove the given packages' entries from our known active set.
    private void removePackageParticipantsLocked(String[] packageNames, int oldUid) {
        if (packageNames == null) {
            Slog.w(TAG, addUserIdToLogMessage(mUserId, "removePackageParticipants with null list"));
            return;
        }

        if (MORE_DEBUG) {
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "removePackageParticipantsLocked: uid="
                                    + oldUid
                                    + " #"
                                    + packageNames.length));
        }
        for (String pkg : packageNames) {
            // Known previous UID, so we know which package set to check
            HashSet<String> set = mBackupParticipants.get(oldUid);
            if (set != null && set.contains(pkg)) {
                removePackageFromSetLocked(set, pkg);
                if (set.isEmpty()) {
                    if (MORE_DEBUG) {
                        Slog.v(
                                TAG,
                                addUserIdToLogMessage(
                                        mUserId, "  last one of this uid; purging set"));
                    }
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
            if (MORE_DEBUG) {
                Slog.v(
                        TAG,
                        addUserIdToLogMessage(mUserId, "  removing participant " + packageName));
            }
            set.remove(packageName);
            mPendingBackups.remove(packageName);
        }
    }

    // Returns the set of all applications that define an android:backupAgent attribute
    private List<PackageInfo> allAgentPackages() {
        // !!! TODO: cache this and regenerate only when necessary
        int flags = PackageManager.GET_SIGNING_CERTIFICATES;
        List<PackageInfo> packages = mPackageManager.getInstalledPackagesAsUser(flags, mUserId);
        int numPackages = packages.size();
        for (int a = numPackages - 1; a >= 0; a--) {
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
                    app = mPackageManager.getApplicationInfoAsUser(pkg.packageName,
                            PackageManager.GET_SHARED_LIBRARY_FILES, mUserId);
                    pkg.applicationInfo.sharedLibraryFiles = app.sharedLibraryFiles;
                    pkg.applicationInfo.sharedLibraryInfos = app.sharedLibraryInfos;
                }
            } catch (NameNotFoundException e) {
                packages.remove(a);
            }
        }
        return packages;
    }

    /**
     * Called from the backup tasks: record that the given app has been successfully backed up at
     * least once. This includes both key/value and full-data backups through the transport.
     */
    public void logBackupComplete(String packageName) {
        if (packageName.equals(PACKAGE_MANAGER_SENTINEL)) return;

        for (String receiver : mConstants.getBackupFinishedNotificationReceivers()) {
            final Intent notification = new Intent();
            notification.setAction(BACKUP_FINISHED_ACTION);
            notification.setPackage(receiver);
            notification.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            notification.putExtra(BACKUP_FINISHED_PACKAGE_EXTRA, packageName);
            mContext.sendBroadcastAsUser(notification, UserHandle.of(mUserId));
        }

        mProcessedPackagesJournal.addPackage(packageName);
    }

    /**
     * Persistently record the current and ancestral backup tokens, as well as the set of packages
     * with data available in the ancestral dataset.
     */
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
                if (DEBUG) {
                    Slog.v(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId, "Ancestral packages:  " + mAncestralPackages.size()));
                }
                for (String pkgName : mAncestralPackages) {
                    af.writeUTF(pkgName);
                    if (MORE_DEBUG) Slog.v(TAG, addUserIdToLogMessage(mUserId, "   " + pkgName));
                }
            }
        } catch (IOException e) {
            Slog.w(TAG, addUserIdToLogMessage(mUserId, "Unable to write token file:"), e);
        }
    }

    /** Fires off a backup agent, blocking until it attaches or times out. */
    @Nullable
    public IBackupAgent bindToAgentSynchronous(ApplicationInfo app, int mode) {
        IBackupAgent agent = null;
        synchronized (mAgentConnectLock) {
            mConnecting = true;
            mConnectedAgent = null;
            try {
                if (mActivityManager.bindBackupAgent(app.packageName, mode, mUserId)) {
                    Slog.d(TAG, addUserIdToLogMessage(mUserId, "awaiting agent for " + app));

                    // success; wait for the agent to arrive
                    // only wait 10 seconds for the bind to happen
                    long timeoutMark = System.currentTimeMillis() + BIND_TIMEOUT_INTERVAL;
                    while (mConnecting && mConnectedAgent == null
                            && (System.currentTimeMillis() < timeoutMark)) {
                        try {
                            mAgentConnectLock.wait(5000);
                        } catch (InterruptedException e) {
                            // just bail
                            Slog.w(TAG, addUserIdToLogMessage(mUserId, "Interrupted: " + e));
                            mConnecting = false;
                            mConnectedAgent = null;
                        }
                    }

                    // if we timed out with no connect, abort and move on
                    if (mConnecting) {
                        Slog.w(
                                TAG,
                                addUserIdToLogMessage(mUserId, "Timeout waiting for agent " + app));
                        mConnectedAgent = null;
                    }
                    if (DEBUG) {
                        Slog.i(TAG, addUserIdToLogMessage(mUserId, "got agent " + mConnectedAgent));
                    }
                    agent = mConnectedAgent;
                }
            } catch (RemoteException e) {
                // can't happen - ActivityManager is local
            }
        }
        if (agent == null) {
            mActivityManagerInternal.clearPendingBackup(mUserId);
        }
        return agent;
    }

    /** Unbind from a backup agent. */
    public void unbindAgent(ApplicationInfo app) {
        try {
            mActivityManager.unbindBackupAgent(app);
        } catch (RemoteException e) {
            // Can't happen - activity manager is local
        }
    }

    /**
     * Clear an application's data after a failed restore, blocking until the operation completes or
     * times out.
     */
    public void clearApplicationDataAfterRestoreFailure(String packageName) {
        clearApplicationDataSynchronous(packageName, true, false);
    }

    /**
     * Clear an application's data before restore, blocking until the operation completes or times
     * out.
     */
    public void clearApplicationDataBeforeRestore(String packageName) {
        clearApplicationDataSynchronous(packageName, false, true);
    }

    /**
     * Clear an application's data, blocking until the operation completes or times out.
     *
     * @param checkFlagAllowClearUserDataOnFailedRestore if {@code true} uses
     *    {@link ApplicationInfo#PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE} to decide if
     *    clearing data is allowed after a failed restore.
     *
     * @param keepSystemState if {@code true}, we don't clear system state such as already restored
     *    notification settings, permission grants, etc.
     */
    private void clearApplicationDataSynchronous(String packageName,
            boolean checkFlagAllowClearUserDataOnFailedRestore, boolean keepSystemState) {
        try {
            ApplicationInfo applicationInfo = mPackageManager.getPackageInfoAsUser(
                    packageName, 0, mUserId).applicationInfo;

            boolean shouldClearData;
            if (checkFlagAllowClearUserDataOnFailedRestore
                    && applicationInfo.targetSdkVersion >= Build.VERSION_CODES.Q) {
                shouldClearData = (applicationInfo.privateFlags
                    & ApplicationInfo.PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE) != 0;
            } else {
                shouldClearData =
                    (applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA) != 0;
            }

            if (!shouldClearData) {
                if (MORE_DEBUG) {
                    Slog.i(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId,
                                    "Clearing app data is not allowed so not wiping "
                                            + packageName));
                }
                return;
            }
        } catch (NameNotFoundException e) {
            Slog.w(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId, "Tried to clear data for " + packageName + " but not found"));
            return;
        }

        ClearDataObserver observer = new ClearDataObserver(this);

        synchronized (mClearDataLock) {
            mClearingData = true;
            try {
                mActivityManager.clearApplicationUserData(packageName, keepSystemState, observer,
                        mUserId);
            } catch (RemoteException e) {
                // can't happen because the activity manager is in this process
            }

            // Only wait 30 seconds for the clear data to happen.
            long timeoutMark = System.currentTimeMillis() + CLEAR_DATA_TIMEOUT_INTERVAL;
            while (mClearingData && (System.currentTimeMillis() < timeoutMark)) {
                try {
                    mClearDataLock.wait(5000);
                } catch (InterruptedException e) {
                    // won't happen, but still.
                    mClearingData = false;
                    Slog.w(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId,
                                    "Interrupted while waiting for "
                                            + packageName
                                            + " data to be cleared"),
                            e);
                }
            }

            if (mClearingData) {
                Slog.w(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "Clearing app data for " + packageName + " timed out"));
            }
        }
    }

    /**
     * Get the restore-set token for the best-available restore set for this {@code packageName}:
     * the active set if possible, else the ancestral one. Returns zero if none available.
     */
    public long getAvailableRestoreToken(String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getAvailableRestoreToken");

        long token = mAncestralToken;
        synchronized (mQueueLock) {
            if (mCurrentToken != 0 && mProcessedPackagesJournal.hasBeenProcessed(packageName)) {
                if (MORE_DEBUG) {
                    Slog.i(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId, "App in ever-stored, so using current token"));
                }
                token = mCurrentToken;
            }
        }
        if (MORE_DEBUG) {
            Slog.i(TAG, addUserIdToLogMessage(mUserId, "getAvailableRestoreToken() == " + token));
        }
        return token;
    }

    /**
     * Requests a backup for the inputted {@code packages}.
     *
     * @see #requestBackup(String[], IBackupObserver, IBackupManagerMonitor, int).
     */
    public int requestBackup(String[] packages, IBackupObserver observer, int flags) {
        return requestBackup(packages, observer, null, flags);
    }

    /**
     * Requests a backup for the inputted {@code packages} with a specified {@link
     * IBackupManagerMonitor}.
     */
    public int requestBackup(String[] packages, IBackupObserver observer,
            IBackupManagerMonitor monitor, int flags) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "requestBackup");

        if (packages == null || packages.length < 1) {
            Slog.e(TAG, addUserIdToLogMessage(mUserId, "No packages named for backup request"));
            BackupObserverUtils.sendBackupFinished(observer, BackupManager.ERROR_TRANSPORT_ABORTED);
            monitor = BackupManagerMonitorUtils.monitorEvent(monitor,
                    BackupManagerMonitor.LOG_EVENT_ID_NO_PACKAGES,
                    null, BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT, null);
            throw new IllegalArgumentException("No packages are provided for backup");
        }

        if (!mEnabled || !mSetupComplete) {
            Slog.i(
                    TAG,
                    addUserIdToLogMessage(mUserId, "Backup requested but enabled="
                            + mEnabled
                            + " setupComplete="
                            + mSetupComplete));
            BackupObserverUtils.sendBackupFinished(observer,
                    BackupManager.ERROR_BACKUP_NOT_ALLOWED);
            final int logTag = mSetupComplete
                    ? BackupManagerMonitor.LOG_EVENT_ID_BACKUP_DISABLED
                    : BackupManagerMonitor.LOG_EVENT_ID_DEVICE_NOT_PROVISIONED;
            monitor = BackupManagerMonitorUtils.monitorEvent(monitor, logTag, null,
                    BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY, null);
            return BackupManager.ERROR_BACKUP_NOT_ALLOWED;
        }

        final TransportClient transportClient;
        final String transportDirName;
        try {
            transportDirName =
                    mTransportManager.getTransportDirName(
                            mTransportManager.getCurrentTransportName());
            transportClient =
                    mTransportManager.getCurrentTransportClientOrThrow("BMS.requestBackup()");
        } catch (TransportNotRegisteredException e) {
            BackupObserverUtils.sendBackupFinished(observer, BackupManager.ERROR_TRANSPORT_ABORTED);
            monitor = BackupManagerMonitorUtils.monitorEvent(monitor,
                    BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_IS_NULL,
                    null, BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT, null);
            return BackupManager.ERROR_TRANSPORT_ABORTED;
        }

        OnTaskFinishedListener listener =
                caller -> mTransportManager.disposeOfTransportClient(transportClient, caller);

        ArrayList<String> fullBackupList = new ArrayList<>();
        ArrayList<String> kvBackupList = new ArrayList<>();
        for (String packageName : packages) {
            if (PACKAGE_MANAGER_SENTINEL.equals(packageName)) {
                kvBackupList.add(packageName);
                continue;
            }
            try {
                PackageInfo packageInfo = mPackageManager.getPackageInfoAsUser(packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES, mUserId);
                if (!AppBackupUtils.appIsEligibleForBackup(packageInfo.applicationInfo, mUserId)) {
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
            Slog.i(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "Backup requested for "
                                    + packages.length
                                    + " packages, of them: "
                                    + fullBackupList.size()
                                    + " full backups, "
                                    + kvBackupList.size()
                                    + " k/v backups"));
        }

        boolean nonIncrementalBackup = (flags & BackupManager.FLAG_NON_INCREMENTAL_BACKUP) != 0;

        Message msg = mBackupHandler.obtainMessage(MSG_REQUEST_BACKUP);
        msg.obj = new BackupParams(transportClient, transportDirName, kvBackupList, fullBackupList,
                observer, monitor, listener, true, nonIncrementalBackup);
        mBackupHandler.sendMessage(msg);
        return BackupManager.SUCCESS;
    }

    /** Cancel all running backups. */
    public void cancelBackups() {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "cancelBackups");
        if (MORE_DEBUG) {
            Slog.i(TAG, addUserIdToLogMessage(mUserId, "cancelBackups() called."));
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
            KeyValueBackupJob.schedule(mUserId, mContext, BUSY_BACKOFF_MIN_MILLIS, mConstants);
            FullBackupJob.schedule(mUserId, mContext, 2 * BUSY_BACKOFF_MIN_MILLIS, mConstants);
        } finally {
            Binder.restoreCallingIdentity(oldToken);
        }
    }

    /** Schedule a timeout message for the operation identified by {@code token}. */
    public void prepareOperationTimeout(int token, long interval, BackupRestoreTask callback,
            int operationType) {
        if (operationType != OP_TYPE_BACKUP_WAIT && operationType != OP_TYPE_RESTORE_WAIT) {
            Slog.wtf(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "prepareOperationTimeout() doesn't support operation "
                                    + Integer.toHexString(token)
                                    + " of type "
                                    + operationType));
            return;
        }
        if (MORE_DEBUG) {
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "starting timeout: token="
                                    + Integer.toHexString(token)
                                    + " interval="
                                    + interval
                                    + " callback="
                                    + callback));
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
                Slog.wtf(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "getMessageIdForOperationType called on invalid operation type: "
                                        + operationType));
                return -1;
        }
    }

    /**
     * Add an operation to the list of currently running operations. Used for cancellation,
     * completion and timeout callbacks that act on the operation via the {@code token}.
     */
    public void putOperation(int token, Operation operation) {
        if (MORE_DEBUG) {
            Slog.d(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "Adding operation token="
                                    + Integer.toHexString(token)
                                    + ", operation type="
                                    + operation.type));
        }
        synchronized (mCurrentOpLock) {
            mCurrentOperations.put(token, operation);
        }
    }

    /**
     * Remove an operation from the list of currently running operations. An operation is removed
     * when it is completed, cancelled, or timed out, and thus no longer running.
     */
    public void removeOperation(int token) {
        if (MORE_DEBUG) {
            Slog.d(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId, "Removing operation token=" + Integer.toHexString(token)));
        }
        synchronized (mCurrentOpLock) {
            if (mCurrentOperations.get(token) == null) {
                Slog.w(TAG, addUserIdToLogMessage(mUserId, "Duplicate remove for operation. token="
                        + Integer.toHexString(token)));
            }
            mCurrentOperations.remove(token);
        }
    }

    /** Block until we received an operation complete message (from the agent or cancellation). */
    public boolean waitUntilOperationComplete(int token) {
        if (MORE_DEBUG) {
            Slog.i(TAG, addUserIdToLogMessage(mUserId, "Blocking until operation complete for "
                    + Integer.toHexString(token)));
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
                            Slog.d(
                                    TAG,
                                    addUserIdToLogMessage(
                                            mUserId,
                                            "Unblocked waiting for operation token="
                                                    + Integer.toHexString(token)));
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
            Slog.v(TAG, addUserIdToLogMessage(mUserId, "operation " + Integer.toHexString(token)
                    + " complete: finalState=" + finalState));
        }
        return finalState == OP_ACKNOWLEDGED;
    }

    /** Cancel the operation associated with {@code token}. */
    public void handleCancel(int token, boolean cancelAll) {
        // Notify any synchronous waiters
        Operation op = null;
        synchronized (mCurrentOpLock) {
            op = mCurrentOperations.get(token);
            if (MORE_DEBUG) {
                if (op == null) {
                    Slog.w(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId,
                                    "Cancel of token "
                                            + Integer.toHexString(token)
                                            + " but no op found"));
                }
            }
            int state = (op != null) ? op.state : OP_TIMEOUT;
            if (state == OP_ACKNOWLEDGED) {
                // The operation finished cleanly, so we have nothing more to do.
                if (DEBUG) {
                    Slog.w(TAG, addUserIdToLogMessage(mUserId, "Operation already got an ack."
                            + "Should have been removed from mCurrentOperations."));
                }
                op = null;
                mCurrentOperations.delete(token);
            } else if (state == OP_PENDING) {
                if (DEBUG) {
                    Slog.v(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId, "Cancel: token=" + Integer.toHexString(token)));
                }
                op.state = OP_TIMEOUT;
                // Can't delete op from mCurrentOperations here. waitUntilOperationComplete may be
                // called after we receive cancel here. We need this op's state there.

                // Remove all pending timeout messages of types OP_TYPE_BACKUP_WAIT and
                // OP_TYPE_RESTORE_WAIT. On the other hand, OP_TYPE_BACKUP cannot time out and
                // doesn't require cancellation.
                if (op.type == OP_TYPE_BACKUP_WAIT || op.type == OP_TYPE_RESTORE_WAIT) {
                    mBackupHandler.removeMessages(getMessageIdForOperationType(op.type));
                }
            }
            mCurrentOpLock.notifyAll();
        }

        // If there's a TimeoutHandler for this event, call it
        if (op != null && op.callback != null) {
            if (MORE_DEBUG) {
                Slog.v(TAG, addUserIdToLogMessage(mUserId, "   Invoking cancel on " + op.callback));
            }
            op.callback.handleCancel(cancelAll);
        }
    }

    /** Returns {@code true} if a backup is currently running, else returns {@code false}. */
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

    /** Unbind the backup agent and kill the app if it's a non-system app. */
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
            if (!UserHandle.isCore(app.uid)
                    && !app.packageName.equals("com.android.backupconfirm")) {
                if (MORE_DEBUG) {
                    Slog.d(TAG, addUserIdToLogMessage(mUserId, "Killing agent host process"));
                }
                mActivityManager.killApplicationProcess(app.processName, app.uid);
            } else {
                if (MORE_DEBUG) {
                    Slog.d(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId, "Not killing after operation: " + app.processName));
                }
            }
        } catch (RemoteException e) {
            Slog.d(TAG, addUserIdToLogMessage(mUserId, "Lost app trying to shut down"));
        }
    }

    /** For adb backup/restore. */
    public boolean deviceIsEncrypted() {
        try {
            return mStorageManager.getEncryptionState()
                    != StorageManager.ENCRYPTION_STATE_NONE
                    && mStorageManager.getPasswordType()
                    != StorageManager.CRYPT_TYPE_DEFAULT;
        } catch (Exception e) {
            // If we can't talk to the storagemanager service we have a serious problem; fail
            // "secure" i.e. assuming that the device is encrypted.
            Slog.e(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "Unable to communicate with storagemanager service: "
                                    + e.getMessage()));
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
                final long interval = mConstants.getFullBackupIntervalMilliseconds();
                final long appLatency = (timeSinceLast < interval) ? (interval - timeSinceLast) : 0;
                final long latency = Math.max(transportMinLatency, appLatency);
                FullBackupJob.schedule(mUserId, mContext, latency, mConstants);
            } else {
                if (DEBUG_SCHEDULING) {
                    Slog.i(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId, "Full backup queue empty; not scheduling"));
                }
            }
        }
    }

    /**
     * Remove a package from the full-data queue.
     */
    @GuardedBy("mQueueLock")
    private void dequeueFullBackupLocked(String packageName) {
        final int numPackages = mFullBackupQueue.size();
        for (int i = numPackages - 1; i >= 0; i--) {
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

    private boolean fullBackupAllowable(String transportName) {
        if (!mTransportManager.isTransportRegistered(transportName)) {
            Slog.w(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId, "Transport not registered; full data backup not performed"));
            return false;
        }

        // Don't proceed unless we have already established package metadata
        // for the current dataset via a key/value backup pass.
        try {
            String transportDirName = mTransportManager.getTransportDirName(transportName);
            File stateDir = new File(mBaseStateDir, transportDirName);
            File pmState = new File(stateDir, PACKAGE_MANAGER_SENTINEL);
            if (pmState.length() <= 0) {
                if (DEBUG) {
                    Slog.i(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId,
                                    "Full backup requested but dataset not yet initialized"));
                }
                return false;
            }
        } catch (Exception e) {
            Slog.w(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId, "Unable to get transport name: " + e.getMessage()));
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
     * along as the return value to the scheduled job's onStartJob() callback.
     */
    public boolean beginFullBackup(FullBackupJob scheduledJob) {
        final long now = System.currentTimeMillis();
        final long fullBackupInterval;
        final long keyValueBackupInterval;
        synchronized (mConstants) {
            fullBackupInterval = mConstants.getFullBackupIntervalMilliseconds();
            keyValueBackupInterval = mConstants.getKeyValueBackupIntervalMilliseconds();
        }
        FullBackupEntry entry = null;
        long latency = fullBackupInterval;

        if (!mEnabled || !mSetupComplete) {
            // Backups are globally disabled, so don't proceed.  We also don't reschedule
            // the job driving automatic backups; that job will be scheduled again when
            // the user enables backup.
            if (MORE_DEBUG) {
                Slog.i(TAG, addUserIdToLogMessage(mUserId, "beginFullBackup but enabled=" + mEnabled
                        + " setupComplete=" + mSetupComplete + "; ignoring"));
            }
            return false;
        }

        // Don't run the backup if we're in battery saver mode, but reschedule
        // to try again in the not-so-distant future.
        final PowerSaveState result =
                mPowerManager.getPowerSaveState(ServiceType.FULL_BACKUP);
        if (result.batterySaverEnabled) {
            if (DEBUG) {
                Slog.i(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "Deferring scheduled full backups in battery saver mode"));
            }
            FullBackupJob.schedule(mUserId, mContext, keyValueBackupInterval, mConstants);
            return false;
        }

        if (DEBUG_SCHEDULING) {
            Slog.i(
                    TAG,
                    addUserIdToLogMessage(mUserId, "Beginning scheduled full backup operation"));
        }

        // Great; we're able to run full backup jobs now.  See if we have any work to do.
        synchronized (mQueueLock) {
            if (mRunningFullBackupTask != null) {
                Slog.e(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "Backup triggered but one already/still running!"));
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
                        Slog.i(
                                TAG,
                                addUserIdToLogMessage(
                                        mUserId, "Backup queue empty; doing nothing"));
                    }
                    runBackup = false;
                    break;
                }

                headBusy = false;

                String transportName = mTransportManager.getCurrentTransportName();
                if (!fullBackupAllowable(transportName)) {
                    if (MORE_DEBUG) {
                        Slog.i(
                                TAG,
                                addUserIdToLogMessage(
                                        mUserId, "Preconditions not met; not running full backup"));
                    }
                    runBackup = false;
                    // Typically this means we haven't run a key/value backup yet.  Back off
                    // full-backup operations by the key/value job's run interval so that
                    // next time we run, we are likely to be able to make progress.
                    latency = keyValueBackupInterval;
                }

                if (runBackup) {
                    entry = mFullBackupQueue.get(0);
                    long timeSinceRun = now - entry.lastBackup;
                    runBackup = (timeSinceRun >= fullBackupInterval);
                    if (!runBackup) {
                        // It's too early to back up the next thing in the queue, so bow out
                        if (MORE_DEBUG) {
                            Slog.i(
                                    TAG,
                                    addUserIdToLogMessage(
                                            mUserId,
                                            "Device ready but too early to back up next app"));
                        }
                        // Wait until the next app in the queue falls due for a full data backup
                        latency = fullBackupInterval - timeSinceRun;
                        break;  // we know we aren't doing work yet, so bail.
                    }

                    try {
                        PackageInfo appInfo = mPackageManager.getPackageInfoAsUser(
                                entry.packageName, 0, mUserId);
                        if (!AppBackupUtils.appGetsFullBackup(appInfo)) {
                            // The head app isn't supposed to get full-data backups [any more];
                            // so we cull it and force a loop around to consider the new head
                            // app.
                            if (MORE_DEBUG) {
                                Slog.i(
                                        TAG,
                                        addUserIdToLogMessage(
                                                mUserId,
                                                "Culling package "
                                                        + entry.packageName
                                                        + " in full-backup queue but not"
                                                        + " eligible"));
                            }
                            mFullBackupQueue.remove(0);
                            headBusy = true; // force the while() condition
                            continue;
                        }

                        final int privFlags = appInfo.applicationInfo.privateFlags;
                        headBusy = (privFlags & PRIVATE_FLAG_BACKUP_IN_FOREGROUND) == 0
                                && mActivityManagerInternal.isAppForeground(
                                        appInfo.applicationInfo.uid);

                        if (headBusy) {
                            final long nextEligible = System.currentTimeMillis()
                                    + BUSY_BACKOFF_MIN_MILLIS
                                    + mTokenGenerator.nextInt(BUSY_BACKOFF_FUZZ);
                            if (DEBUG_SCHEDULING) {
                                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                                Slog.i(
                                        TAG,
                                        addUserIdToLogMessage(
                                                mUserId,
                                                "Full backup time but "
                                                        + entry.packageName
                                                        + " is busy; deferring to "
                                                        + sdf.format(new Date(nextEligible))));
                            }
                            // This relocates the app's entry from the head of the queue to
                            // its order-appropriate position further down, so upon looping
                            // a new candidate will be considered at the head.
                            enqueueFullBackup(entry.packageName, nextEligible - fullBackupInterval);
                        }
                    } catch (NameNotFoundException nnf) {
                        // So, we think we want to back this up, but it turns out the package
                        // in question is no longer installed.  We want to drop it from the
                        // queue entirely and move on, but if there's nothing else in the queue
                        // we should bail entirely.  headBusy cannot have been set to true yet.
                        runBackup = (mFullBackupQueue.size() > 1);
                    }
                }
            } while (headBusy);

            if (!runBackup) {
                if (DEBUG_SCHEDULING) {
                    Slog.i(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId,
                                    "Nothing pending full backup; rescheduling +" + latency));
                }
                final long deferTime = latency;     // pin for the closure
                FullBackupJob.schedule(mUserId, mContext, deferTime, mConstants);
                return false;
            }

            // Okay, the top thing is ready for backup now.  Do it.
            mFullBackupQueue.remove(0);
            CountDownLatch latch = new CountDownLatch(1);
            String[] pkg = new String[]{entry.packageName};
            mRunningFullBackupTask = PerformFullTransportBackupTask.newWithCurrentTransport(
                    this,
                    /* observer */ null,
                    pkg,
                    /* updateSchedule */ true,
                    scheduledJob,
                    latch,
                    /* backupObserver */ null,
                    /* monitor */ null,
                    /* userInitiated */ false,
                    "BMS.beginFullBackup()");
            // Acquiring wakelock for PerformFullTransportBackupTask before its start.
            mWakelock.acquire();
            (new Thread(mRunningFullBackupTask)).start();
        }

        return true;
    }

    /**
     * The job scheduler says our constraints don't hold anymore, so tear down any ongoing backup
     * task right away.
     */
    public void endFullBackup() {
        // offload the mRunningFullBackupTask.handleCancel() call to another thread,
        // as we might have to wait for mCancelLock
        Runnable endFullBackupRunnable = new Runnable() {
            @Override
            public void run() {
                PerformFullTransportBackupTask pftbt = null;
                synchronized (mQueueLock) {
                    if (mRunningFullBackupTask != null) {
                        pftbt = mRunningFullBackupTask;
                    }
                }
                if (pftbt != null) {
                    if (DEBUG_SCHEDULING) {
                        Slog.i(
                                TAG,
                                addUserIdToLogMessage(
                                        mUserId, "Telling running backup to stop"));
                    }
                    pftbt.handleCancel(true);
                }
            }
        };
        new Thread(endFullBackupRunnable, "end-full-backup").start();
    }

    /** Used by both incremental and full restore to restore widget data. */
    public void restoreWidgetData(String packageName, byte[] widgetData) {
        // Apply the restored widget state and generate the ID update for the app
        if (MORE_DEBUG) {
            Slog.i(TAG, addUserIdToLogMessage(mUserId, "Incorporating restored widget data"));
        }
        AppWidgetBackupBridge.restoreWidgetState(packageName, widgetData, mUserId);
    }

    // *****************************
    // NEW UNIFIED RESTORE IMPLEMENTATION
    // *****************************

    /** Schedule a backup pass for {@code packageName}. */
    public void dataChangedImpl(String packageName) {
        HashSet<String> targets = dataChangedTargets(packageName);
        dataChangedImpl(packageName, targets);
    }

    private void dataChangedImpl(String packageName, HashSet<String> targets) {
        // Record that we need a backup pass for the caller.  Since multiple callers
        // may share a uid, we need to note all candidates within that uid and schedule
        // a backup pass for each of them.
        if (targets == null) {
            Slog.w(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "dataChanged but no participant pkg='"
                                    + packageName
                                    + "'"
                                    + " uid="
                                    + Binder.getCallingUid()));
            return;
        }

        synchronized (mQueueLock) {
            // Note that this client has made data changes that need to be backed up
            if (targets.contains(packageName)) {
                // Add the caller to the set of pending backups.  If there is
                // one already there, then overwrite it, but no harm done.
                BackupRequest req = new BackupRequest(packageName);
                if (mPendingBackups.put(packageName, req) == null) {
                    if (MORE_DEBUG) {
                        Slog.d(
                                TAG,
                                addUserIdToLogMessage(
                                        mUserId, "Now staging backup of " + packageName));
                    }

                    // Journal this request in case of crash.  The put()
                    // operation returned null when this package was not already
                    // in the set; we want to avoid touching the disk redundantly.
                    writeToJournalLocked(packageName);
                }
            }
        }

        // ...and schedule a backup pass if necessary
        KeyValueBackupJob.schedule(mUserId, mContext, mConstants);
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
            Slog.e(
                    TAG,
                    addUserIdToLogMessage(mUserId, "Can't write " + str + " to backup journal"),
                    e);
            mJournal = null;
        }
    }

    // ----- IBackupManager binder interface -----

    /** Sent from an app's backup agent to let the service know that there's new data to backup. */
    public void dataChanged(final String packageName) {
        final HashSet<String> targets = dataChangedTargets(packageName);
        if (targets == null) {
            Slog.w(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "dataChanged but no participant pkg='"
                                    + packageName
                                    + "'"
                                    + " uid="
                                    + Binder.getCallingUid()));
            return;
        }

        mBackupHandler.post(new Runnable() {
            public void run() {
                dataChangedImpl(packageName, targets);
            }
        });
    }

    /** Run an initialize operation for the given transport. */
    public void initializeTransports(String[] transportNames, IBackupObserver observer) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "initializeTransport");
        Slog.v(
                TAG,
                addUserIdToLogMessage(
                        mUserId, "initializeTransport(): " + Arrays.asList(transportNames)));

        final long oldId = Binder.clearCallingIdentity();
        try {
            mWakelock.acquire();
            OnTaskFinishedListener listener = caller -> mWakelock.release();
            mBackupHandler.post(
                    new PerformInitializeTask(this, transportNames, observer, listener));
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    /**
     * Sets the work profile serial number of the ancestral work profile.
     */
    public void setAncestralSerialNumber(long ancestralSerialNumber) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP,
                "setAncestralSerialNumber");
        Slog.v(
                TAG,
                addUserIdToLogMessage(
                        mUserId, "Setting ancestral work profile id to " + ancestralSerialNumber));
        try (RandomAccessFile af = getAncestralSerialNumberFile()) {
            af.writeLong(ancestralSerialNumber);
        } catch (IOException e) {
            Slog.w(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId, "Unable to write to work profile serial mapping file:"),
                    e);
        }
    }

    /**
     * Returns the work profile serial number of the ancestral device. This will be set by
     * {@link #setAncestralSerialNumber(long)}. Will return {@code -1} if not set.
     */
    public long getAncestralSerialNumber() {
        try (RandomAccessFile af = getAncestralSerialNumberFile()) {
            return af.readLong();
        } catch (IOException e) {
            Slog.w(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId, "Unable to write to work profile serial number file:"),
                    e);
            return -1;
        }
    }

    private RandomAccessFile getAncestralSerialNumberFile() throws FileNotFoundException {
        if (mAncestralSerialNumberFile == null) {
            mAncestralSerialNumberFile = new File(
                UserBackupManagerFiles.getBaseStateDir(getUserId()),
                SERIAL_ID_FILE);
            FileUtils.createNewFile(mAncestralSerialNumberFile);
        }
        return new RandomAccessFile(mAncestralSerialNumberFile, "rwd");
    }

    @VisibleForTesting
    void setAncestralSerialNumberFile(File ancestralSerialNumberFile) {
        mAncestralSerialNumberFile = ancestralSerialNumberFile;
    }


    /** Clear the given package's backup data from the current transport. */
    public void clearBackupData(String transportName, String packageName) {
        if (DEBUG) {
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "clearBackupData() of " + packageName + " on " + transportName));
        }

        PackageInfo info;
        try {
            info = mPackageManager.getPackageInfoAsUser(packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES, mUserId);
        } catch (NameNotFoundException e) {
            Slog.d(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "No such package '" + packageName + "' - not clearing backup data"));
            return;
        }

        // If the caller does not hold the BACKUP permission, it can only request a
        // wipe of its own backed-up data.
        Set<String> apps;
        if ((mContext.checkPermission(android.Manifest.permission.BACKUP, Binder.getCallingPid(),
                Binder.getCallingUid())) == PackageManager.PERMISSION_DENIED) {
            apps = mBackupParticipants.get(Binder.getCallingUid());
        } else {
            // a caller with full permission can ask to back up any participating app
            // !!! TODO: allow data-clear of ANY app?
            if (MORE_DEBUG) {
                Slog.v(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "Privileged caller, allowing clear of other apps"));
            }
            apps = mProcessedPackagesJournal.getPackagesCopy();
        }

        if (apps.contains(packageName)) {
            // found it; fire off the clear request
            if (MORE_DEBUG) {
                Slog.v(
                        TAG,
                        addUserIdToLogMessage(mUserId, "Found the app - running clear process"));
            }
            mBackupHandler.removeMessages(MSG_RETRY_CLEAR);
            synchronized (mQueueLock) {
                TransportClient transportClient =
                        mTransportManager
                                .getTransportClient(transportName, "BMS.clearBackupData()");
                if (transportClient == null) {
                    // transport is currently unregistered -- make sure to retry
                    Message msg = mBackupHandler.obtainMessage(MSG_RETRY_CLEAR,
                            new ClearRetryParams(transportName, packageName));
                    mBackupHandler.sendMessageDelayed(msg, TRANSPORT_RETRY_INTERVAL);
                    return;
                }
                long oldId = Binder.clearCallingIdentity();
                OnTaskFinishedListener listener =
                        caller ->
                                mTransportManager.disposeOfTransportClient(transportClient, caller);
                mWakelock.acquire();
                Message msg = mBackupHandler.obtainMessage(
                        MSG_RUN_CLEAR,
                        new ClearParams(transportClient, info, listener));
                mBackupHandler.sendMessage(msg);
                Binder.restoreCallingIdentity(oldId);
            }
        }
    }

    /**
     * Run a backup pass immediately for any applications that have declared that they have pending
     * updates.
     */
    public void backupNow() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP, "backupNow");

        long oldId = Binder.clearCallingIdentity();
        try {
            final PowerSaveState result =
                    mPowerManager.getPowerSaveState(ServiceType.KEYVALUE_BACKUP);
            if (result.batterySaverEnabled) {
                if (DEBUG) {
                    Slog.v(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId, "Not running backup while in battery save mode"));
                }
                // Try again in several hours.
                KeyValueBackupJob.schedule(mUserId, mContext, mConstants);
            } else {
                if (DEBUG) {
                    Slog.v(TAG, addUserIdToLogMessage(mUserId, "Scheduling immediate backup pass"));
                }

                synchronized (getQueueLock()) {
                    if (getPendingInits().size() > 0) {
                        // If there are pending init operations, we process those and then settle
                        // into the usual periodic backup schedule.
                        if (MORE_DEBUG) {
                            Slog.v(
                                    TAG,
                                    addUserIdToLogMessage(
                                            mUserId, "Init pending at scheduled backup"));
                        }
                        try {
                            getAlarmManager().cancel(mRunInitIntent);
                            mRunInitIntent.send();
                        } catch (PendingIntent.CanceledException ce) {
                            Slog.w(
                                    TAG,
                                    addUserIdToLogMessage(mUserId, "Run init intent cancelled"));
                        }
                        return;
                    }
                }

                // Don't run backups if we're disabled or not yet set up.
                if (!isEnabled() || !isSetupComplete()) {
                    Slog.w(
                            TAG,
                            addUserIdToLogMessage(mUserId, "Backup pass but enabled="  + isEnabled()
                                    + " setupComplete=" + isSetupComplete()));
                    return;
                }

                // Fire the msg that kicks off the whole shebang...
                Message message = mBackupHandler.obtainMessage(MSG_RUN_BACKUP);
                mBackupHandler.sendMessage(message);
                // ...and cancel any pending scheduled job, because we've just superseded it
                KeyValueBackupJob.cancel(mUserId, mContext);
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    /**
     * Used by 'adb backup' to run a backup pass for packages supplied via the command line, writing
     * the resulting data stream to the supplied {@code fd}. This method is synchronous and does not
     * return to the caller until the backup has been completed. It requires on-screen confirmation
     * by the user.
     */
    public void adbBackup(ParcelFileDescriptor fd, boolean includeApks,
            boolean includeObbs, boolean includeShared, boolean doWidgets, boolean doAllApps,
            boolean includeSystem, boolean compress, boolean doKeyValue, String[] pkgList) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "adbBackup");

        final int callingUserHandle = UserHandle.getCallingUserId();
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
            if (!mSetupComplete) {
                Slog.i(TAG, addUserIdToLogMessage(mUserId, "Backup not supported before setup"));
                return;
            }

            if (DEBUG) {
                Slog.v(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "Requesting backup: apks="
                                        + includeApks
                                        + " obb="
                                        + includeObbs
                                        + " shared="
                                        + includeShared
                                        + " all="
                                        + doAllApps
                                        + " system="
                                        + includeSystem
                                        + " includekeyvalue="
                                        + doKeyValue
                                        + " pkgs="
                                        + pkgList));
            }
            Slog.i(TAG, addUserIdToLogMessage(mUserId, "Beginning adb backup..."));

            AdbBackupParams params = new AdbBackupParams(fd, includeApks, includeObbs,
                    includeShared, doWidgets, doAllApps, includeSystem, compress, doKeyValue,
                    pkgList);
            final int token = generateRandomIntegerToken();
            synchronized (mAdbBackupRestoreConfirmations) {
                mAdbBackupRestoreConfirmations.put(token, params);
            }

            // start up the confirmation UI
            if (DEBUG) {
                Slog.d(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "Starting backup confirmation UI, token=" + token));
            }
            if (!startConfirmationUi(token, FullBackup.FULL_BACKUP_INTENT_ACTION)) {
                Slog.e(
                        TAG,
                        addUserIdToLogMessage(mUserId, "Unable to launch backup confirmation UI"));
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
            if (DEBUG) {
                Slog.d(TAG, addUserIdToLogMessage(mUserId, "Waiting for backup completion..."));
            }
            waitForCompletion(params);
        } finally {
            try {
                fd.close();
            } catch (IOException e) {
                Slog.e(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "IO error closing output for adb backup: " + e.getMessage()));
            }
            Binder.restoreCallingIdentity(oldId);
            Slog.d(TAG, addUserIdToLogMessage(mUserId, "Adb backup processing complete."));
        }
    }

    /** Run a full backup pass for the given packages. Used by 'adb shell bmgr'. */
    public void fullTransportBackup(String[] pkgNames) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP,
                "fullTransportBackup");

        final int callingUserHandle = UserHandle.getCallingUserId();
        // TODO: http://b/22388012
        if (callingUserHandle != UserHandle.USER_SYSTEM) {
            throw new IllegalStateException("Restore supported only for the device owner");
        }

        String transportName = mTransportManager.getCurrentTransportName();
        if (!fullBackupAllowable(transportName)) {
            Slog.i(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "Full backup not currently possible -- key/value backup not yet run?"));
        } else {
            if (DEBUG) {
                Slog.d(TAG, addUserIdToLogMessage(mUserId, "fullTransportBackup()"));
            }

            final long oldId = Binder.clearCallingIdentity();
            try {
                CountDownLatch latch = new CountDownLatch(1);
                Runnable task = PerformFullTransportBackupTask.newWithCurrentTransport(
                        this,
                        /* observer */ null,
                        pkgNames,
                        /* updateSchedule */ false,
                        /* runningJob */ null,
                        latch,
                        /* backupObserver */ null,
                        /* monitor */ null,
                        /* userInitiated */ false,
                        "BMS.fullTransportBackup()");
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
            Slog.d(TAG, addUserIdToLogMessage(mUserId, "Done with full transport backup."));
        }
    }

    /**
     * Used by 'adb restore' to run a restore pass, blocking until completion. Requires user
     * confirmation.
     */
    public void adbRestore(ParcelFileDescriptor fd) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "adbRestore");

        final int callingUserHandle = UserHandle.getCallingUserId();
        if (callingUserHandle != UserHandle.USER_SYSTEM) {
            throw new IllegalStateException("Restore supported only for the device owner");
        }

        long oldId = Binder.clearCallingIdentity();

        try {
            if (!mSetupComplete) {
                Slog.i(
                        TAG,
                        addUserIdToLogMessage(mUserId, "Full restore not permitted before setup"));
                return;
            }

            Slog.i(TAG, addUserIdToLogMessage(mUserId, "Beginning restore..."));

            AdbRestoreParams params = new AdbRestoreParams(fd);
            final int token = generateRandomIntegerToken();
            synchronized (mAdbBackupRestoreConfirmations) {
                mAdbBackupRestoreConfirmations.put(token, params);
            }

            // start up the confirmation UI
            if (DEBUG) {
                Slog.d(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "Starting restore confirmation UI, token=" + token));
            }
            if (!startConfirmationUi(token, FullBackup.FULL_RESTORE_INTENT_ACTION)) {
                Slog.e(
                        TAG,
                        addUserIdToLogMessage(mUserId, "Unable to launch restore confirmation"));
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
            if (DEBUG) {
                Slog.d(TAG, addUserIdToLogMessage(mUserId, "Waiting for restore completion..."));
            }
            waitForCompletion(params);
        } finally {
            try {
                fd.close();
            } catch (IOException e) {
                Slog.w(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "Error trying to close fd after adb restore: " + e));
            }
            Binder.restoreCallingIdentity(oldId);
            Slog.i(TAG, addUserIdToLogMessage(mUserId, "adb restore processing complete."));
        }
    }

    /**
     * Excludes keys from KV restore for a given package. The keys won't be part of the data passed
     * to the backup agent during restore.
     */
    public void excludeKeysFromRestore(String packageName, List<String> keys) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "excludeKeysFromRestore");
        mBackupPreferences.addExcludedKeys(packageName, keys);
    }

    private boolean startConfirmationUi(int token, String action) {
        try {
            Intent confIntent = new Intent(action);
            confIntent.setClassName("com.android.backupconfirm",
                    "com.android.backupconfirm.BackupRestoreConfirmation");
            confIntent.putExtra(FullBackup.CONF_TOKEN_INTENT_EXTRA, token);
            confIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            mContext.startActivityAsUser(confIntent, UserHandle.SYSTEM);
        } catch (ActivityNotFoundException e) {
            return false;
        }
        return true;
    }

    private void startConfirmationTimeout(int token, AdbParams params) {
        if (MORE_DEBUG) {
            Slog.d(TAG, addUserIdToLogMessage(mUserId, "Posting conf timeout msg after "
                    + TIMEOUT_FULL_CONFIRMATION + " millis"));
        }
        Message msg = mBackupHandler.obtainMessage(MSG_FULL_CONFIRMATION_TIMEOUT,
                token, 0, params);
        mBackupHandler.sendMessageDelayed(msg, TIMEOUT_FULL_CONFIRMATION);
    }

    private void waitForCompletion(AdbParams params) {
        synchronized (params.latch) {
            while (!params.latch.get()) {
                try {
                    params.latch.wait();
                } catch (InterruptedException e) { /* never interrupted */ }
            }
        }
    }

    /** Called when adb backup/restore has completed. */
    public void signalAdbBackupRestoreCompletion(AdbParams params) {
        synchronized (params.latch) {
            params.latch.set(true);
            params.latch.notifyAll();
        }
    }

    /**
     * Confirm that the previously-requested full backup/restore operation can proceed. This is used
     * to require a user-facing disclosure about the operation.
     */
    public void acknowledgeAdbBackupOrRestore(int token, boolean allow,
            String curPassword, String encPpassword, IFullBackupRestoreObserver observer) {
        if (DEBUG) {
            Slog.d(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "acknowledgeAdbBackupOrRestore : token=" + token + " allow=" + allow));
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

                        if (MORE_DEBUG) {
                            Slog.d(
                                    TAG,
                                    addUserIdToLogMessage(
                                            mUserId, "Sending conf message with verb " + verb));
                        }
                        mWakelock.acquire();
                        Message msg = mBackupHandler.obtainMessage(verb, params);
                        mBackupHandler.sendMessage(msg);
                    } else {
                        Slog.w(
                                TAG,
                                addUserIdToLogMessage(
                                        mUserId, "User rejected full backup/restore operation"));
                        // indicate completion without having actually transferred any data
                        signalAdbBackupRestoreCompletion(params);
                    }
                } else {
                    Slog.w(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId,
                                    "Attempted to ack full backup/restore with invalid token"));
                }
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    /** User-configurable enabling/disabling of backups. */
    public void setBackupEnabled(boolean enable) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setBackupEnabled");

        Slog.i(TAG, addUserIdToLogMessage(mUserId, "Backup enabled => " + enable));

        long oldId = Binder.clearCallingIdentity();
        try {
            boolean wasEnabled = mEnabled;
            synchronized (this) {
                UserBackupManagerFilePersistedSettings.writeBackupEnableState(mUserId, enable);
                mEnabled = enable;
            }

            synchronized (mQueueLock) {
                if (enable && !wasEnabled && mSetupComplete) {
                    // if we've just been enabled, start scheduling backup passes
                    KeyValueBackupJob.schedule(mUserId, mContext, mConstants);
                    scheduleNextFullBackupJob(0);
                } else if (!enable) {
                    // No longer enabled, so stop running backups
                    if (MORE_DEBUG) {
                        Slog.i(TAG, addUserIdToLogMessage(mUserId, "Opting out of backup"));
                    }

                    KeyValueBackupJob.cancel(mUserId, mContext);

                    // This also constitutes an opt-out, so we wipe any data for
                    // this device from the backend.  We start that process with
                    // an alarm in order to guarantee wakelock states.
                    if (wasEnabled && mSetupComplete) {
                        // NOTE: we currently flush every registered transport, not just
                        // the currently-active one.
                        List<String> transportNames = new ArrayList<>();
                        List<String> transportDirNames = new ArrayList<>();
                        mTransportManager.forEachRegisteredTransport(
                                name -> {
                                    final String dirName;
                                    try {
                                        dirName = mTransportManager.getTransportDirName(name);
                                    } catch (TransportNotRegisteredException e) {
                                        // Should never happen
                                        Slog.e(
                                                TAG,
                                                addUserIdToLogMessage(
                                                        mUserId,
                                                        "Unexpected unregistered transport"),
                                                e);
                                        return;
                                    }
                                    transportNames.add(name);
                                    transportDirNames.add(dirName);
                                });

                        // build the set of transports for which we are posting an init
                        for (int i = 0; i < transportNames.size(); i++) {
                            recordInitPending(
                                    true,
                                    transportNames.get(i),
                                    transportDirNames.get(i));
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

    /** Enable/disable automatic restore of app data at install time. */
    public void setAutoRestore(boolean doAutoRestore) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setAutoRestore");

        Slog.i(TAG, addUserIdToLogMessage(mUserId, "Auto restore => " + doAutoRestore));

        final long oldId = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.BACKUP_AUTO_RESTORE, doAutoRestore ? 1 : 0, mUserId);
                mAutoRestore = doAutoRestore;
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    /** Report whether the backup mechanism is currently enabled. */
    public boolean isBackupEnabled() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "isBackupEnabled");
        return mEnabled;    // no need to synchronize just to read it
    }

    /** Report the name of the currently active transport. */
    public String getCurrentTransport() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getCurrentTransport");
        String currentTransport = mTransportManager.getCurrentTransportName();
        if (MORE_DEBUG) {
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId, "... getCurrentTransport() returning " + currentTransport));
        }
        return currentTransport;
    }

    /**
     * Returns the {@link ComponentName} of the host service of the selected transport or {@code
     * null} if no transport selected or if the transport selected is not registered.
     */
    @Nullable
    public ComponentName getCurrentTransportComponent() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BACKUP, "getCurrentTransportComponent");
        long oldId = Binder.clearCallingIdentity();
        try {
            return mTransportManager.getCurrentTransportComponent();
        } catch (TransportNotRegisteredException e) {
            return null;
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    /** Report all known, available backup transports by name. */
    public String[] listAllTransports() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "listAllTransports");

        return mTransportManager.getRegisteredTransportNames();
    }

    /** Report all known, available backup transports by component. */
    public ComponentName[] listAllTransportComponents() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "listAllTransportComponents");
        return mTransportManager.getRegisteredTransportComponents();
    }

    /**
     * Update the attributes of the transport identified by {@code transportComponent}. If the
     * specified transport has not been bound at least once (for registration), this call will be
     * ignored. Only the host process of the transport can change its description, otherwise a
     * {@link SecurityException} will be thrown.
     *
     * @param transportComponent The identity of the transport being described.
     * @param name A {@link String} with the new name for the transport. This is NOT for
     *     identification. MUST NOT be {@code null}.
     * @param configurationIntent An {@link Intent} that can be passed to
     *     {@link Context#startActivity} in order to launch the transport's configuration UI. It may
     *     be {@code null} if the transport does not offer any user-facing configuration UI.
     * @param currentDestinationString A {@link String} describing the destination to which the
     *     transport is currently sending data. MUST NOT be {@code null}.
     * @param dataManagementIntent An {@link Intent} that can be passed to
     *     {@link Context#startActivity} in order to launch the transport's data-management UI. It
     *     may be {@code null} if the transport does not offer any user-facing data
     *     management UI.
     * @param dataManagementLabel A {@link CharSequence} to be used as the label for the transport's
     *     data management affordance. This MUST be {@code null} when dataManagementIntent is
     *     {@code null} and MUST NOT be {@code null} when dataManagementIntent is not {@code null}.
     * @throws SecurityException If the UID of the calling process differs from the package UID of
     *     {@code transportComponent} or if the caller does NOT have BACKUP permission.
     */
    public void updateTransportAttributes(
            ComponentName transportComponent,
            String name,
            @Nullable Intent configurationIntent,
            String currentDestinationString,
            @Nullable Intent dataManagementIntent,
            @Nullable CharSequence dataManagementLabel) {
        updateTransportAttributes(
                Binder.getCallingUid(),
                transportComponent,
                name,
                configurationIntent,
                currentDestinationString,
                dataManagementIntent,
                dataManagementLabel);
    }

    @VisibleForTesting
    void updateTransportAttributes(
            int callingUid,
            ComponentName transportComponent,
            String name,
            @Nullable Intent configurationIntent,
            String currentDestinationString,
            @Nullable Intent dataManagementIntent,
            @Nullable CharSequence dataManagementLabel) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BACKUP, "updateTransportAttributes");

        Objects.requireNonNull(transportComponent, "transportComponent can't be null");
        Objects.requireNonNull(name, "name can't be null");
        Objects.requireNonNull(
                currentDestinationString, "currentDestinationString can't be null");
        Preconditions.checkArgument(
                (dataManagementIntent == null) == (dataManagementLabel == null),
                "dataManagementLabel should be null iff dataManagementIntent is null");

        try {
            int transportUid =
                    mContext.getPackageManager()
                            .getPackageUidAsUser(transportComponent.getPackageName(), 0, mUserId);
            if (callingUid != transportUid) {
                throw new SecurityException("Only the transport can change its description");
            }
        } catch (NameNotFoundException e) {
            throw new SecurityException("Transport package not found", e);
        }

        final long oldId = Binder.clearCallingIdentity();
        try {
            mTransportManager.updateTransportAttributes(
                    transportComponent,
                    name,
                    configurationIntent,
                    currentDestinationString,
                    dataManagementIntent,
                    dataManagementLabel);
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    /**
     * Selects transport {@code transportName} and returns previously selected transport.
     *
     * @deprecated Use {@link #selectBackupTransportAsync(ComponentName,
     * ISelectBackupTransportCallback)} instead.
     */
    @Deprecated
    @Nullable
    public String selectBackupTransport(String transportName) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BACKUP, "selectBackupTransport");

        final long oldId = Binder.clearCallingIdentity();
        try {
            String previousTransportName = mTransportManager.selectTransport(transportName);
            updateStateForTransport(transportName);
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "selectBackupTransport(transport = "
                                    + transportName
                                    + "): previous transport = "
                                    + previousTransportName));
            return previousTransportName;
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    /**
     * Selects transport {@code transportComponent} asynchronously and notifies {@code listener}
     * with the result upon completion.
     */
    public void selectBackupTransportAsync(
            ComponentName transportComponent, ISelectBackupTransportCallback listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BACKUP, "selectBackupTransportAsync");

        final long oldId = Binder.clearCallingIdentity();
        try {
            String transportString = transportComponent.flattenToShortString();
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "selectBackupTransportAsync(transport = " + transportString + ")"));
            mBackupHandler.post(
                    () -> {
                        String transportName = null;
                        int result =
                                mTransportManager.registerAndSelectTransport(transportComponent);
                        if (result == BackupManager.SUCCESS) {
                            try {
                                transportName =
                                        mTransportManager.getTransportName(transportComponent);
                                updateStateForTransport(transportName);
                            } catch (TransportNotRegisteredException e) {
                                Slog.e(
                                        TAG,
                                        addUserIdToLogMessage(
                                                mUserId, "Transport got unregistered"));
                                result = BackupManager.ERROR_TRANSPORT_UNAVAILABLE;
                            }
                        }

                        try {
                            if (transportName != null) {
                                listener.onSuccess(transportName);
                            } else {
                                listener.onFailure(result);
                            }
                        } catch (RemoteException e) {
                            Slog.e(
                                    TAG,
                                    addUserIdToLogMessage(
                                            mUserId,
                                            "ISelectBackupTransportCallback listener not"
                                                + " available"));
                        }
                    });
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    private void updateStateForTransport(String newTransportName) {
        // Publish the name change
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.BACKUP_TRANSPORT, newTransportName, mUserId);

        // And update our current-dataset bookkeeping
        String callerLogString = "BMS.updateStateForTransport()";
        TransportClient transportClient =
                mTransportManager.getTransportClient(newTransportName, callerLogString);
        if (transportClient != null) {
            try {
                IBackupTransport transport = transportClient.connectOrThrow(callerLogString);
                mCurrentToken = transport.getCurrentRestoreSet();
            } catch (Exception e) {
                // Oops.  We can't know the current dataset token, so reset and figure it out
                // when we do the next k/v backup operation on this transport.
                mCurrentToken = 0;
                Slog.w(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "Transport "
                                        + newTransportName
                                        + " not available: current token = 0"));
            }
            mTransportManager.disposeOfTransportClient(transportClient, callerLogString);
        } else {
            Slog.w(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "Transport "
                                    + newTransportName
                                    + " not registered: current token = 0"));
            // The named transport isn't registered, so we can't know what its current dataset token
            // is. Reset as above.
            mCurrentToken = 0;
        }
    }

    /**
     * Supply the configuration intent for the given transport. If the name is not one of the
     * available transports, or if the transport does not supply any configuration UI, the method
     * returns {@code null}.
     */
    public Intent getConfigurationIntent(String transportName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getConfigurationIntent");
        try {
            Intent intent = mTransportManager.getTransportConfigurationIntent(transportName);
            if (MORE_DEBUG) {
                Slog.d(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "getConfigurationIntent() returning intent " + intent));
            }
            return intent;
        } catch (TransportNotRegisteredException e) {
            Slog.e(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "Unable to get configuration intent from transport: "
                                    + e.getMessage()));
            return null;
        }
    }

    /**
     * Supply the current destination string for the given transport. If the name is not one of the
     * registered transports the method will return null.
     *
     * <p>This string is used VERBATIM as the summary text of the relevant Settings item.
     *
     * @param transportName The name of the registered transport.
     * @return The current destination string or null if the transport is not registered.
     */
    public String getDestinationString(String transportName) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BACKUP, "getDestinationString");

        try {
            String string = mTransportManager.getTransportCurrentDestinationString(transportName);
            if (MORE_DEBUG) {
                Slog.d(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "getDestinationString() returning " + string));
            }
            return string;
        } catch (TransportNotRegisteredException e) {
            Slog.e(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "Unable to get destination string from transport: " + e.getMessage()));
            return null;
        }
    }

    /** Supply the manage-data intent for the given transport. */
    public Intent getDataManagementIntent(String transportName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getDataManagementIntent");

        try {
            Intent intent = mTransportManager.getTransportDataManagementIntent(transportName);
            if (MORE_DEBUG) {
                Slog.d(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "getDataManagementIntent() returning intent " + intent));
            }
            return intent;
        } catch (TransportNotRegisteredException e) {
            Slog.e(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "Unable to get management intent from transport: " + e.getMessage()));
            return null;
        }
    }

    /**
     * Supply the menu label for affordances that fire the manage-data intent for the given
     * transport.
     */
    public CharSequence getDataManagementLabel(String transportName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getDataManagementLabel");

        try {
            CharSequence label = mTransportManager.getTransportDataManagementLabel(transportName);
            if (MORE_DEBUG) {
                Slog.d(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "getDataManagementLabel() returning " + label));
            }
            return label;
        } catch (TransportNotRegisteredException e) {
            Slog.e(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "Unable to get management label from transport: " + e.getMessage()));
            return null;
        }
    }

    /**
     * Callback: a requested backup agent has been instantiated. This should only be called from the
     * {@link ActivityManager}.
     */
    public void agentConnected(String packageName, IBinder agentBinder) {
        synchronized (mAgentConnectLock) {
            if (Binder.getCallingUid() == Process.SYSTEM_UID) {
                Slog.d(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "agentConnected pkg=" + packageName + " agent=" + agentBinder));
                mConnectedAgent = IBackupAgent.Stub.asInterface(agentBinder);
                mConnecting = false;
            } else {
                Slog.w(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "Non-system process uid="
                                        + Binder.getCallingUid()
                                        + " claiming agent connected"));
            }
            mAgentConnectLock.notifyAll();
        }
    }

    /**
     * Callback: a backup agent has failed to come up, or has unexpectedly quit. If the agent failed
     * to come up in the first place, the agentBinder argument will be {@code null}. This should
     * only be called from the {@link ActivityManager}.
     */
    public void agentDisconnected(String packageName) {
        // TODO: handle backup being interrupted
        synchronized (mAgentConnectLock) {
            if (Binder.getCallingUid() == Process.SYSTEM_UID) {
                mConnectedAgent = null;
                mConnecting = false;
            } else {
                Slog.w(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "Non-system process uid="
                                        + Binder.getCallingUid()
                                        + " claiming agent disconnected"));
            }
            mAgentConnectLock.notifyAll();
        }
    }

    /**
     * An application being installed will need a restore pass, then the {@link PackageManager} will
     * need to be told when the restore is finished.
     */
    public void restoreAtInstall(String packageName, int token) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            Slog.w(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "Non-system process uid="
                                    + Binder.getCallingUid()
                                    + " attemping install-time restore"));
            return;
        }

        boolean skip = false;

        long restoreSet = getAvailableRestoreToken(packageName);
        if (DEBUG) {
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "restoreAtInstall pkg="
                                    + packageName
                                    + " token="
                                    + Integer.toHexString(token)
                                    + " restoreSet="
                                    + Long.toHexString(restoreSet)));
        }
        if (restoreSet == 0) {
            if (MORE_DEBUG) Slog.i(TAG, addUserIdToLogMessage(mUserId, "No restore set"));
            skip = true;
        }

        TransportClient transportClient =
                mTransportManager.getCurrentTransportClient("BMS.restoreAtInstall()");
        if (transportClient == null) {
            if (DEBUG) Slog.w(TAG, addUserIdToLogMessage(mUserId, "No transport client"));
            skip = true;
        }

        if (!mAutoRestore) {
            if (DEBUG) {
                Slog.w(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "Non-restorable state: auto=" + mAutoRestore));
            }
            skip = true;
        }

        if (!skip) {
            try {
                // okay, we're going to attempt a restore of this package from this restore set.
                // The eventual message back into the Package Manager to run the post-install
                // steps for 'token' will be issued from the restore handling code.

                mWakelock.acquire();

                OnTaskFinishedListener listener = caller -> {
                    mTransportManager.disposeOfTransportClient(transportClient, caller);
                    mWakelock.release();
                };

                if (MORE_DEBUG) {
                    Slog.d(
                            TAG,
                            addUserIdToLogMessage(mUserId, "Restore at install of " + packageName));
                }
                Message msg = mBackupHandler.obtainMessage(MSG_RUN_RESTORE);
                msg.obj =
                        RestoreParams.createForRestoreAtInstall(
                                transportClient,
                                /* observer */ null,
                                /* monitor */ null,
                                restoreSet,
                                packageName,
                                token,
                                listener);
                mBackupHandler.sendMessage(msg);
            } catch (Exception e) {
                // Calling into the transport broke; back off and proceed with the installation.
                Slog.e(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "Unable to contact transport: " + e.getMessage()));
                skip = true;
            }
        }

        if (skip) {
            // Auto-restore disabled or no way to attempt a restore

            if (transportClient != null) {
                mTransportManager.disposeOfTransportClient(
                        transportClient, "BMS.restoreAtInstall()");
            }

            // Tell the PackageManager to proceed with the post-install handling for this package.
            if (DEBUG) Slog.v(TAG, addUserIdToLogMessage(mUserId, "Finishing install immediately"));
            try {
                mPackageManagerBinder.finishPackageInstall(token, false);
            } catch (RemoteException e) { /* can't happen */ }
        }
    }

    /** Hand off a restore session. */
    public IRestoreSession beginRestoreSession(String packageName, String transport) {
        if (DEBUG) {
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "beginRestoreSession: pkg=" + packageName + " transport=" + transport));
        }

        boolean needPermission = true;
        if (transport == null) {
            transport = mTransportManager.getCurrentTransportName();

            if (packageName != null) {
                PackageInfo app = null;
                try {
                    app = mPackageManager.getPackageInfoAsUser(packageName, 0, mUserId);
                } catch (NameNotFoundException nnf) {
                    Slog.w(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId, "Asked to restore nonexistent pkg " + packageName));
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
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.BACKUP, "beginRestoreSession");
        } else {
            if (DEBUG) {
                Slog.d(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "restoring self on current transport; no permission needed"));
            }
        }

        synchronized (this) {
            if (mActiveRestoreSession != null) {
                Slog.i(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId, "Restore session requested but one already active"));
                return null;
            }
            if (mBackupRunning) {
                Slog.i(
                        TAG,
                        addUserIdToLogMessage(
                                mUserId,
                                "Restore session requested but currently running backups"));
                return null;
            }
            mActiveRestoreSession = new ActiveRestoreSession(this, packageName, transport);
            mBackupHandler.sendEmptyMessageDelayed(MSG_RESTORE_SESSION_TIMEOUT,
                    mAgentTimeoutParameters.getRestoreAgentTimeoutMillis());
        }
        return mActiveRestoreSession;
    }

    /** Clear the specified restore session. */
    public void clearRestoreSession(ActiveRestoreSession currentSession) {
        synchronized (this) {
            if (currentSession != mActiveRestoreSession) {
                Slog.e(TAG, addUserIdToLogMessage(mUserId, "ending non-current restore session"));
            } else {
                if (DEBUG) {
                    Slog.v(
                            TAG,
                            addUserIdToLogMessage(
                                    mUserId, "Clearing restore session and halting timeout"));
                }
                mActiveRestoreSession = null;
                mBackupHandler.removeMessages(MSG_RESTORE_SESSION_TIMEOUT);
            }
        }
    }

    /**
     * Note that a currently-active backup agent has notified us that it has completed the given
     * outstanding asynchronous backup/restore operation.
     */
    public void opComplete(int token, long result) {
        if (MORE_DEBUG) {
            Slog.v(
                    TAG,
                    addUserIdToLogMessage(
                            mUserId,
                            "opComplete: " + Integer.toHexString(token) + " result=" + result));
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
                        Slog.w(
                                TAG,
                                addUserIdToLogMessage(
                                        mUserId,
                                        "Received duplicate ack for token="
                                                + Integer.toHexString(token)));
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

    /** Checks if the package is eligible for backup. */
    public boolean isAppEligibleForBackup(String packageName) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BACKUP, "isAppEligibleForBackup");

        long oldToken = Binder.clearCallingIdentity();
        try {
            String callerLogString = "BMS.isAppEligibleForBackup";
            TransportClient transportClient =
                    mTransportManager.getCurrentTransportClient(callerLogString);
            boolean eligible =
                    AppBackupUtils.appIsRunningAndEligibleForBackupWithTransport(
                            transportClient, packageName, mPackageManager, mUserId);
            if (transportClient != null) {
                mTransportManager.disposeOfTransportClient(transportClient, callerLogString);
            }
            return eligible;
        } finally {
            Binder.restoreCallingIdentity(oldToken);
        }
    }

    /** Returns the inputted packages that are eligible for backup. */
    public String[] filterAppsEligibleForBackup(String[] packages) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BACKUP, "filterAppsEligibleForBackup");

        long oldToken = Binder.clearCallingIdentity();
        try {
            String callerLogString = "BMS.filterAppsEligibleForBackup";
            TransportClient transportClient =
                    mTransportManager.getCurrentTransportClient(callerLogString);
            List<String> eligibleApps = new LinkedList<>();
            for (String packageName : packages) {
                if (AppBackupUtils
                        .appIsRunningAndEligibleForBackupWithTransport(
                                transportClient, packageName, mPackageManager, mUserId)) {
                    eligibleApps.add(packageName);
                }
            }
            if (transportClient != null) {
                mTransportManager.disposeOfTransportClient(transportClient, callerLogString);
            }
            return eligibleApps.toArray(new String[eligibleApps.size()]);
        } finally {
            Binder.restoreCallingIdentity(oldToken);
        }
    }

    /** Prints service state for 'dumpsys backup'. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        long identityToken = Binder.clearCallingIdentity();
        try {
            if (args != null) {
                for (String arg : args) {
                    if ("agents".startsWith(arg)) {
                        dumpAgents(pw);
                        return;
                    } else if ("transportclients".equals(arg.toLowerCase())) {
                        mTransportManager.dumpTransportClients(pw);
                        return;
                    } else if ("transportstats".equals(arg.toLowerCase())) {
                        mTransportManager.dumpTransportStats(pw);
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
        // Add prefix for only non-system users so that system user dumpsys is the same as before
        String userPrefix = mUserId == UserHandle.USER_SYSTEM ? "" : "User " + mUserId + ":";
        synchronized (mQueueLock) {
            pw.println(userPrefix + "Backup Manager is " + (mEnabled ? "enabled" : "disabled")
                    + " / " + (!mSetupComplete ? "not " : "") + "setup complete / "
                    + (this.mPendingInits.size() == 0 ? "not " : "") + "pending init");
            pw.println("Auto-restore is " + (mAutoRestore ? "enabled" : "disabled"));
            if (mBackupRunning) pw.println("Backup currently running");
            pw.println(isBackupOperationInProgress() ? "Backup in progress" : "No backups running");
            pw.println("Last backup pass started: " + mLastBackupPass
                    + " (now = " + System.currentTimeMillis() + ')');
            pw.println("  next scheduled: " + KeyValueBackupJob.nextScheduled(mUserId));

            pw.println(userPrefix + "Transport whitelist:");
            for (ComponentName transport : mTransportManager.getTransportWhitelist()) {
                pw.print("    ");
                pw.println(transport.flattenToShortString());
            }

            pw.println(userPrefix + "Available transports:");
            final String[] transports = listAllTransports();
            if (transports != null) {
                for (String t : transports) {
                    pw.println((t.equals(mTransportManager.getCurrentTransportName()) ? "  * "
                            : "    ") + t);
                    try {
                        File dir = new File(mBaseStateDir,
                                mTransportManager.getTransportDirName(t));
                        pw.println("       destination: "
                                + mTransportManager.getTransportCurrentDestinationString(t));
                        pw.println("       intent: "
                                + mTransportManager.getTransportConfigurationIntent(t));
                        for (File f : dir.listFiles()) {
                            pw.println(
                                    "       " + f.getName() + " - " + f.length() + " state bytes");
                        }
                    } catch (Exception e) {
                        Slog.e(TAG, addUserIdToLogMessage(mUserId, "Error in transport"), e);
                        pw.println("        Error: " + e);
                    }
                }
            }

            mTransportManager.dumpTransportClients(pw);

            pw.println(userPrefix + "Pending init: " + mPendingInits.size());
            for (String s : mPendingInits) {
                pw.println("    " + s);
            }

            pw.print(userPrefix + "Ancestral: ");
            pw.println(Long.toHexString(mAncestralToken));
            pw.print(userPrefix + "Current:   ");
            pw.println(Long.toHexString(mCurrentToken));

            int numPackages = mBackupParticipants.size();
            pw.println(userPrefix + "Participants:");
            for (int i = 0; i < numPackages; i++) {
                int uid = mBackupParticipants.keyAt(i);
                pw.print("  uid: ");
                pw.println(uid);
                HashSet<String> participants = mBackupParticipants.valueAt(i);
                for (String app : participants) {
                    pw.println("    " + app);
                }
            }

            pw.println(userPrefix + "Ancestral packages: "
                    + (mAncestralPackages == null ? "none" : mAncestralPackages.size()));
            if (mAncestralPackages != null) {
                for (String pkg : mAncestralPackages) {
                    pw.println("    " + pkg);
                }
            }

            Set<String> processedPackages = mProcessedPackagesJournal.getPackagesCopy();
            pw.println(userPrefix + "Ever backed up: " + processedPackages.size());
            for (String pkg : processedPackages) {
                pw.println("    " + pkg);
            }

            pw.println(userPrefix + "Pending key/value backup: " + mPendingBackups.size());
            for (BackupRequest req : mPendingBackups.values()) {
                pw.println("    " + req);
            }

            pw.println(userPrefix + "Full backup queue:" + mFullBackupQueue.size());
            for (FullBackupEntry entry : mFullBackupQueue) {
                pw.print("    ");
                pw.print(entry.lastBackup);
                pw.print(" : ");
                pw.println(entry.packageName);
            }
        }
    }

    private static String addUserIdToLogMessage(int userId, String message) {
        return "[UserID:" + userId + "] " + message;
    }


    public IBackupManager getBackupManagerBinder() {
        return mBackupManagerBinder;
    }
}
