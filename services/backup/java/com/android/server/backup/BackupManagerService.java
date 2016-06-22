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

package com.android.server.backup;

import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_BACKUP_IN_FOREGROUND;

import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.IBackupAgent;
import android.app.PackageInstallObserver;
import android.app.PendingIntent;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupManager;
import android.app.backup.BackupProgress;
import android.app.backup.BackupTransport;
import android.app.backup.FullBackup;
import android.app.backup.FullBackupDataOutput;
import android.app.backup.IBackupObserver;
import android.app.backup.RestoreDescription;
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
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
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
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.os.Environment.UserEnvironment;
import android.os.storage.IMountService;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.StringBuilderPrinter;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.backup.IObbBackupService;
import com.android.server.AppWidgetBackupBridge;
import com.android.server.EventLogTags;
import com.android.server.SystemConfig;
import com.android.server.SystemService;
import com.android.server.backup.PackageManagerBackupAgent.Metadata;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

import libcore.io.IoUtils;

public class BackupManagerService {

    private static final String TAG = "BackupManagerService";
    static final boolean DEBUG = true;
    static final boolean MORE_DEBUG = false;
    static final boolean DEBUG_SCHEDULING = MORE_DEBUG || true;

    // File containing backup-enabled state.  Contains a single byte;
    // nonzero == enabled.  File missing or contains a zero byte == disabled.
    static final String BACKUP_ENABLE_FILE = "backup_enabled";

    // System-private key used for backing up an app's widget state.  Must
    // begin with U+FFxx by convention (we reserve all keys starting
    // with U+FF00 or higher for system use).
    static final String KEY_WIDGET_STATE = "\uffed\uffedwidget";

    // Historical and current algorithm names
    static final String PBKDF_CURRENT = "PBKDF2WithHmacSHA1";
    static final String PBKDF_FALLBACK = "PBKDF2WithHmacSHA1And8bit";

    // Name and current contents version of the full-backup manifest file
    //
    // Manifest version history:
    //
    // 1 : initial release
    static final String BACKUP_MANIFEST_FILENAME = "_manifest";
    static final int BACKUP_MANIFEST_VERSION = 1;

    // External archive format version history:
    //
    // 1 : initial release
    // 2 : no format change per se; version bump to facilitate PBKDF2 version skew detection
    // 3 : introduced "_meta" metadata file; no other format change per se
    // 4 : added support for new device-encrypted storage locations
    static final int BACKUP_FILE_VERSION = 4;
    static final String BACKUP_FILE_HEADER_MAGIC = "ANDROID BACKUP\n";
    static final int BACKUP_PW_FILE_VERSION = 2;
    static final String BACKUP_METADATA_FILENAME = "_meta";
    static final int BACKUP_METADATA_VERSION = 1;
    static final int BACKUP_WIDGET_METADATA_TOKEN = 0x01FFED01;
    static final boolean COMPRESS_FULL_BACKUPS = true; // should be true in production

    static final String SETTINGS_PACKAGE = "com.android.providers.settings";
    static final String SHARED_BACKUP_AGENT_PACKAGE = "com.android.sharedstoragebackup";
    static final String SERVICE_ACTION_TRANSPORT_HOST = "android.backup.TRANSPORT_HOST";

    // Retry interval for clear/init when the transport is unavailable
    private static final long TRANSPORT_RETRY_INTERVAL = 1 * AlarmManager.INTERVAL_HOUR;

    private static final String RUN_BACKUP_ACTION = "android.app.backup.intent.RUN";
    private static final String RUN_INITIALIZE_ACTION = "android.app.backup.intent.INIT";
    private static final int MSG_RUN_BACKUP = 1;
    private static final int MSG_RUN_ADB_BACKUP = 2;
    private static final int MSG_RUN_RESTORE = 3;
    private static final int MSG_RUN_CLEAR = 4;
    private static final int MSG_RUN_INITIALIZE = 5;
    private static final int MSG_RUN_GET_RESTORE_SETS = 6;
    private static final int MSG_TIMEOUT = 7;
    private static final int MSG_RESTORE_TIMEOUT = 8;
    private static final int MSG_FULL_CONFIRMATION_TIMEOUT = 9;
    private static final int MSG_RUN_ADB_RESTORE = 10;
    private static final int MSG_RETRY_INIT = 11;
    private static final int MSG_RETRY_CLEAR = 12;
    private static final int MSG_WIDGET_BROADCAST = 13;
    private static final int MSG_RUN_FULL_TRANSPORT_BACKUP = 14;
    private static final int MSG_REQUEST_BACKUP = 15;

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
    static final long TIMEOUT_RESTORE_FINISHED_INTERVAL = 30 * 1000;

    // User confirmation timeout for a full backup/restore operation.  It's this long in
    // order to give them time to enter the backup password.
    static final long TIMEOUT_FULL_CONFIRMATION = 60 * 1000;

    // How long between attempts to perform a full-data backup of any given app
    static final long MIN_FULL_BACKUP_INTERVAL = 1000 * 60 * 60 * 24; // one day

    // If an app is busy when we want to do a full-data backup, how long to defer the retry.
    // This is fuzzed, so there are two parameters; backoff_min + Rand[0, backoff_fuzz)
    static final long BUSY_BACKOFF_MIN_MILLIS = 1000 * 60 * 60;  // one hour
    static final int BUSY_BACKOFF_FUZZ = 1000 * 60 * 60 * 2;  // two hours

    Context mContext;
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

    // For debugging, we maintain a progress trace of operations during backup
    static final boolean DEBUG_BACKUP_TRACE = true;
    final List<String> mBackupTrace = new ArrayList<String>();

    // A similar synchronization mechanism around clearing apps' data for restore
    final Object mClearDataLock = new Object();
    volatile boolean mClearingData;

    // Transport bookkeeping
    final ArraySet<ComponentName> mTransportWhitelist;
    final Intent mTransportServiceIntent = new Intent(SERVICE_ACTION_TRANSPORT_HOST);
    final ArrayMap<String,String> mTransportNames
            = new ArrayMap<String,String>();             // component name -> registration name
    final ArrayMap<String,IBackupTransport> mTransports
            = new ArrayMap<String,IBackupTransport>();   // registration name -> binder
    final ArrayMap<String,TransportConnection> mTransportConnections
            = new ArrayMap<String,TransportConnection>();
    String mCurrentTransport;
    ActiveRestoreSession mActiveRestoreSession;

    // Watch the device provisioning operation during setup
    ContentObserver mProvisionedObserver;

    // The published binder is actually to a singleton trampoline object that calls
    // through to the proper code.  This indirection lets us turn down the heavy
    // implementation object on the fly without disturbing binders that have been
    // cached elsewhere in the system.
    static Trampoline sInstance;
    static Trampoline getInstance() {
        // Always constructed during system bringup, so no need to lazy-init
        return sInstance;
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
                    KeyValueBackupJob.schedule(mContext);
                    scheduleNextFullBackupJob(0);
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
        public String dirName;
        public IRestoreObserver observer;
        public long token;
        public PackageInfo pkgInfo;
        public int pmToken; // in post-install restore, the PM's token for this transaction
        public boolean isSystemRestore;
        public String[] filterSet;

        /**
         * Restore a single package; no kill after restore
         */
        RestoreParams(IBackupTransport _transport, String _dirName, IRestoreObserver _obs,
                long _token, PackageInfo _pkg) {
            transport = _transport;
            dirName = _dirName;
            observer = _obs;
            token = _token;
            pkgInfo = _pkg;
            pmToken = 0;
            isSystemRestore = false;
            filterSet = null;
        }

        /**
         * Restore at install: PM token needed, kill after restore
         */
        RestoreParams(IBackupTransport _transport, String _dirName, IRestoreObserver _obs,
                long _token, String _pkgName, int _pmToken) {
            transport = _transport;
            dirName = _dirName;
            observer = _obs;
            token = _token;
            pkgInfo = null;
            pmToken = _pmToken;
            isSystemRestore = false;
            filterSet = new String[] { _pkgName };
        }

        /**
         * Restore everything possible.  This is the form that Setup Wizard or similar
         * restore UXes use.
         */
        RestoreParams(IBackupTransport _transport, String _dirName, IRestoreObserver _obs,
                long _token) {
            transport = _transport;
            dirName = _dirName;
            observer = _obs;
            token = _token;
            pkgInfo = null;
            pmToken = 0;
            isSystemRestore = true;
            filterSet = null;
        }

        /**
         * Restore some set of packages.  Leave this one up to the caller to specify
         * whether it's to be considered a system-level restore.
         */
        RestoreParams(IBackupTransport _transport, String _dirName, IRestoreObserver _obs,
                long _token, String[] _filterSet, boolean _isSystemRestore) {
            transport = _transport;
            dirName = _dirName;
            observer = _obs;
            token = _token;
            pkgInfo = null;
            pmToken = 0;
            isSystemRestore = _isSystemRestore;
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

    class ClearRetryParams {
        public String transportName;
        public String packageName;

        ClearRetryParams(String transport, String pkg) {
            transportName = transport;
            packageName = pkg;
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
        public boolean includeObbs;
        public boolean includeShared;
        public boolean doWidgets;
        public boolean allApps;
        public boolean includeSystem;
        public boolean doCompress;
        public String[] packages;

        FullBackupParams(ParcelFileDescriptor output, boolean saveApks, boolean saveObbs,
                boolean saveShared, boolean alsoWidgets, boolean doAllApps, boolean doSystem,
                boolean compress, String[] pkgList) {
            fd = output;
            includeApks = saveApks;
            includeObbs = saveObbs;
            includeShared = saveShared;
            doWidgets = alsoWidgets;
            allApps = doAllApps;
            includeSystem = doSystem;
            doCompress = compress;
            packages = pkgList;
        }
    }

    class FullRestoreParams extends FullParams {
        FullRestoreParams(ParcelFileDescriptor input) {
            fd = input;
        }
    }

    class BackupParams {
        public IBackupTransport transport;
        public String dirName;
        public ArrayList<String> kvPackages;
        public ArrayList<String> fullPackages;
        public IBackupObserver observer;
        public boolean userInitiated;

        BackupParams(IBackupTransport transport, String dirName, ArrayList<String> kvPackages,
                ArrayList<String> fullPackages, IBackupObserver observer, boolean userInitiated) {
            this.transport = transport;
            this.dirName = dirName;
            this.kvPackages = kvPackages;
            this.fullPackages = fullPackages;
            this.observer = observer;
            this.userInitiated = userInitiated;
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
    private int mPasswordVersion;
    private File mPasswordVersionFile;
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

    // Round-robin queue for scheduling full backup passes
    static final int SCHEDULE_FILE_VERSION = 1; // current version of the schedule file
    class FullBackupEntry implements Comparable<FullBackupEntry> {
        String packageName;
        long lastBackup;

        FullBackupEntry(String pkg, long when) {
            packageName = pkg;
            lastBackup = when;
        }

        @Override
        public int compareTo(FullBackupEntry other) {
            if (lastBackup < other.lastBackup) return -1;
            else if (lastBackup > other.lastBackup) return 1;
            else return 0;
        }
    }

    File mFullBackupScheduleFile;
    // If we're running a schedule-driven full backup, this is the task instance doing it

    @GuardedBy("mQueueLock")
    PerformFullTransportBackupTask mRunningFullBackupTask;

    @GuardedBy("mQueueLock")
    ArrayList<FullBackupEntry> mFullBackupQueue;

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

    // High level policy: apps are generally ineligible for backup if certain conditions apply
    public static boolean appIsEligibleForBackup(ApplicationInfo app) {
        // 1. their manifest states android:allowBackup="false"
        if ((app.flags&ApplicationInfo.FLAG_ALLOW_BACKUP) == 0) {
            return false;
        }

        // 2. they run as a system-level uid but do not supply their own backup agent
        if ((app.uid < Process.FIRST_APPLICATION_UID) && (app.backupAgentName == null)) {
            return false;
        }

        // 3. it is the special shared-storage backup package used for 'adb backup'
        if (app.packageName.equals(BackupManagerService.SHARED_BACKUP_AGENT_PACKAGE)) {
            return false;
        }

        return true;
    }

    // Checks if the app is in a stopped state, that means it won't receive broadcasts.
    private static boolean appIsStopped(ApplicationInfo app) {
        return ((app.flags & ApplicationInfo.FLAG_STOPPED) != 0);
    }

    /* does *not* check overall backup eligibility policy! */
    private static boolean appGetsFullBackup(PackageInfo pkg) {
        if (pkg.applicationInfo.backupAgentName != null) {
            // If it has an agent, it gets full backups only if it says so
            return (pkg.applicationInfo.flags & ApplicationInfo.FLAG_FULL_BACKUP_ONLY) != 0;
        }

        // No agent or fullBackupOnly="true" means we do indeed perform full-data backups for it
        return true;
    }

    /* adb backup: is this app only capable of doing key/value?  We say otherwise if
     * the app has a backup agent and does not say fullBackupOnly, *unless* it
     * is a package that we know _a priori_ explicitly supports both key/value and
     * full-data backup.
     */
    private static boolean appIsKeyValueOnly(PackageInfo pkg) {
        if ("com.android.providers.settings".equals(pkg.packageName)) {
            return false;
        }

        return !appGetsFullBackup(pkg);
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
                boolean staged = true;
                if (queue.size() > 0) {
                    // Spin up a backup state sequence and set it running
                    try {
                        String dirName = transport.transportDirName();
                        PerformBackupTask pbt = new PerformBackupTask(transport, dirName,
                                queue, oldJournal, null, null, false);
                        Message pbtMessage = obtainMessage(MSG_BACKUP_RESTORE_STEP, pbt);
                        sendMessage(pbtMessage);
                    } catch (RemoteException e) {
                        // unable to ask the transport its dir name -- transient failure, since
                        // the above check succeeded.  Try again next time.
                        Slog.e(TAG, "Transport became unavailable attempting backup");
                        staged = false;
                    }
                } else {
                    Slog.v(TAG, "Backup requested but nothing pending");
                    staged = false;
                }

                if (!staged) {
                    // if we didn't actually hand off the wakelock, rewind until next time
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
                    Pair<BackupRestoreTask, Long> taskWithResult =
                            (Pair<BackupRestoreTask, Long>) msg.obj;
                    taskWithResult.first.operationComplete(taskWithResult.second);
                } catch (ClassCastException e) {
                    Slog.e(TAG, "Invalid completion in flight, obj=" + msg.obj);
                }
                break;
            }

            case MSG_RUN_ADB_BACKUP:
            {
                // TODO: refactor full backup to be a looper-based state machine
                // similar to normal backup/restore.
                FullBackupParams params = (FullBackupParams)msg.obj;
                PerformAdbBackupTask task = new PerformAdbBackupTask(params.fd,
                        params.observer, params.includeApks, params.includeObbs,
                        params.includeShared, params.doWidgets,
                        params.curPassword, params.encryptPassword,
                        params.allApps, params.includeSystem, params.doCompress,
                        params.packages, params.latch);
                (new Thread(task, "adb-backup")).start();
                break;
            }

            case MSG_RUN_FULL_TRANSPORT_BACKUP:
            {
                PerformFullTransportBackupTask task = (PerformFullTransportBackupTask) msg.obj;
                (new Thread(task, "transport-backup")).start();
                break;
            }

            case MSG_RUN_RESTORE:
            {
                RestoreParams params = (RestoreParams)msg.obj;
                Slog.d(TAG, "MSG_RUN_RESTORE observer=" + params.observer);
                BackupRestoreTask task = new PerformUnifiedRestoreTask(params.transport,
                        params.observer, params.token, params.pkgInfo, params.pmToken,
                        params.isSystemRestore, params.filterSet);
                Message restoreMsg = obtainMessage(MSG_BACKUP_RESTORE_STEP, task);
                sendMessage(restoreMsg);
                break;
            }

            case MSG_RUN_ADB_RESTORE:
            {
                // TODO: refactor full restore to be a looper-based state machine
                // similar to normal backup/restore.
                FullRestoreParams params = (FullRestoreParams)msg.obj;
                PerformAdbRestoreTask task = new PerformAdbRestoreTask(params.fd,
                        params.curPassword, params.encryptPassword,
                        params.observer, params.latch);
                (new Thread(task, "adb-restore")).start();
                break;
            }

            case MSG_RUN_CLEAR:
            {
                ClearParams params = (ClearParams)msg.obj;
                (new PerformClearTask(params.transport, params.packageInfo)).run();
                break;
            }

            case MSG_RETRY_CLEAR:
            {
                // reenqueues if the transport remains unavailable
                ClearRetryParams params = (ClearRetryParams)msg.obj;
                clearBackupData(params.transportName, params.packageName);
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

            case MSG_RETRY_INIT:
            {
                synchronized (mQueueLock) {
                    recordInitPendingLocked(msg.arg1 != 0, (String)msg.obj);
                    mAlarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(),
                            mRunInitIntent);
                }
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
                        mActiveRestoreSession.markTimedOut();
                        post(mActiveRestoreSession.new EndRestoreRunnable(
                                BackupManagerService.this, mActiveRestoreSession));
                    }
                }
                break;
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

            case MSG_WIDGET_BROADCAST:
            {
                final Intent intent = (Intent) msg.obj;
                mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
                break;
            }

            case MSG_REQUEST_BACKUP:
            {
                BackupParams params = (BackupParams)msg.obj;
                if (MORE_DEBUG) {
                    Slog.d(TAG, "MSG_REQUEST_BACKUP observer=" + params.observer);
                }
                ArrayList<BackupRequest> kvQueue = new ArrayList<>();
                for (String packageName : params.kvPackages) {
                    kvQueue.add(new BackupRequest(packageName));
                }
                mBackupRunning = true;
                mWakelock.acquire();

                PerformBackupTask pbt = new PerformBackupTask(params.transport, params.dirName,
                    kvQueue, null, params.observer, params.fullPackages, true);
                Message pbtMessage = obtainMessage(MSG_BACKUP_RESTORE_STEP, pbt);
                sendMessage(pbtMessage);
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

    public BackupManagerService(Context context, Trampoline parent) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mPackageManagerBinder = AppGlobals.getPackageManager();
        mActivityManager = ActivityManagerNative.getDefault();

        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mMountService = IMountService.Stub.asInterface(ServiceManager.getService("mount"));

        mBackupManagerBinder = Trampoline.asInterface(parent.asBinder());

        // spin up the backup/restore handler thread
        mHandlerThread = new HandlerThread("backup", Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        mBackupHandler = new BackupHandler(mHandlerThread.getLooper());

        // Set up our bookkeeping
        final ContentResolver resolver = context.getContentResolver();
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
        mBaseStateDir = new File(Environment.getDataDirectory(), "backup");
        mBaseStateDir.mkdirs();
        if (!SELinux.restorecon(mBaseStateDir)) {
            Slog.e(TAG, "SELinux restorecon failed on " + mBaseStateDir);
        }

        // This dir on /cache is managed directly in init.rc
        mDataDir = new File(Environment.getDownloadCacheDirectory(), "backup_stage");

        mPasswordVersion = 1;       // unless we hear otherwise
        mPasswordVersionFile = new File(mBaseStateDir, "pwversion");
        if (mPasswordVersionFile.exists()) {
            FileInputStream fin = null;
            DataInputStream in = null;
            try {
                fin = new FileInputStream(mPasswordVersionFile);
                in = new DataInputStream(fin);
                mPasswordVersion = in.readInt();
            } catch (IOException e) {
                Slog.e(TAG, "Unable to read backup pw version");
            } finally {
                try {
                    if (in != null) in.close();
                    if (fin != null) fin.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Error closing pw version files");
                }
            }
        }

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
        mTransportWhitelist = systemConfig.getBackupTransportWhitelist();

        String transport = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.BACKUP_TRANSPORT);
        if (TextUtils.isEmpty(transport)) {
            transport = null;
        }
        mCurrentTransport = transport;
        if (DEBUG) Slog.v(TAG, "Starting with transport " + mCurrentTransport);

        // Find all transport hosts and bind to their services
        // TODO: http://b/22388012
        List<ResolveInfo> hosts = mPackageManager.queryIntentServicesAsUser(
                mTransportServiceIntent, 0, UserHandle.USER_SYSTEM);
        if (DEBUG) {
            Slog.v(TAG, "Found transports: " + ((hosts == null) ? "null" : hosts.size()));
        }
        if (hosts != null) {
            for (int i = 0; i < hosts.size(); i++) {
                final ServiceInfo transportService = hosts.get(i).serviceInfo;
                if (MORE_DEBUG) {
                    Slog.v(TAG, "   " + transportService.packageName + "/" + transportService.name);
                }
                tryBindTransport(transportService);
            }
        }

        // Now that we know about valid backup participants, parse any
        // leftover journal files into the pending backup set
        parseLeftoverJournals();

        // Power management
        mWakelock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*backup*");
    }

    private class RunBackupReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            if (RUN_BACKUP_ACTION.equals(intent.getAction())) {
                synchronized (mQueueLock) {
                    if (mPendingInits.size() > 0) {
                        // If there are pending init operations, we process those
                        // and then settle into the usual periodic backup schedule.
                        if (MORE_DEBUG) Slog.v(TAG, "Init pending at scheduled backup");
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
        if (MORE_DEBUG) Slog.v(TAG, "` tracking");

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
            } finally {
                try { if (temp != null) temp.close(); } catch (IOException e) {}
                try { if (in != null) in.close(); } catch (IOException e) {}
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
            FileInputStream fstream = null;
            BufferedInputStream bufStream = null;
            DataInputStream in = null;
            try {
                fstream = new FileInputStream(mFullBackupScheduleFile);
                bufStream = new BufferedInputStream(fstream);
                in = new DataInputStream(bufStream);

                int version = in.readInt();
                if (version != SCHEDULE_FILE_VERSION) {
                    Slog.e(TAG, "Unknown backup schedule version " + version);
                    return null;
                }

                final int N = in.readInt();
                schedule = new ArrayList<FullBackupEntry>(N);

                // HashSet instead of ArraySet specifically because we want the eventual
                // lookups against O(hundreds) of entries to be as fast as possible, and
                // we discard the set immediately after the scan so the extra memory
                // overhead is transient.
                HashSet<String> foundApps = new HashSet<String>(N);

                for (int i = 0; i < N; i++) {
                    String pkgName = in.readUTF();
                    long lastBackup = in.readLong();
                    foundApps.add(pkgName); // all apps that we've addressed already
                    try {
                        PackageInfo pkg = mPackageManager.getPackageInfo(pkgName, 0);
                        if (appGetsFullBackup(pkg) && appIsEligibleForBackup(pkg.applicationInfo)) {
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
                    if (appGetsFullBackup(app) && appIsEligibleForBackup(app.applicationInfo)) {
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
            } finally {
                IoUtils.closeQuietly(in);
                IoUtils.closeQuietly(bufStream);
                IoUtils.closeQuietly(fstream);
            }
        }

        if (schedule == null) {
            // no prior queue record, or unable to read it.  Set up the queue
            // from scratch.
            changed = true;
            schedule = new ArrayList<FullBackupEntry>(apps.size());
            for (PackageInfo info : apps) {
                if (appGetsFullBackup(info) && appIsEligibleForBackup(info.applicationInfo)) {
                    schedule.add(new FullBackupEntry(info.packageName, 0));
                }
            }
        }

        if (changed) {
            writeFullBackupScheduleAsync();
        }
        return schedule;
    }

    Runnable mFullBackupScheduleWriter = new Runnable() {
        @Override public void run() {
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
                        if (MORE_DEBUG) Slog.i(TAG, "  " + packageName);
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

    private SecretKey buildPasswordKey(String algorithm, String pw, byte[] salt, int rounds) {
        return buildCharArrayKey(algorithm, pw.toCharArray(), salt, rounds);
    }

    private SecretKey buildCharArrayKey(String algorithm, char[] pwArray, byte[] salt, int rounds) {
        try {
            SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(algorithm);
            KeySpec ks = new PBEKeySpec(pwArray, salt, rounds, PBKDF2_KEY_SIZE);
            return keyFactory.generateSecret(ks);
        } catch (InvalidKeySpecException e) {
            Slog.e(TAG, "Invalid key spec for PBKDF2!");
        } catch (NoSuchAlgorithmException e) {
            Slog.e(TAG, "PBKDF2 unavailable!");
        }
        return null;
    }

    private String buildPasswordHash(String algorithm, String pw, byte[] salt, int rounds) {
        SecretKey key = buildPasswordKey(algorithm, pw, salt, rounds);
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

    private byte[] makeKeyChecksum(String algorithm, byte[] pwBytes, byte[] salt, int rounds) {
        char[] mkAsChar = new char[pwBytes.length];
        for (int i = 0; i < pwBytes.length; i++) {
            mkAsChar[i] = (char) pwBytes[i];
        }

        Key checksum = buildCharArrayKey(algorithm, mkAsChar, salt, rounds);
        return checksum.getEncoded();
    }

    // Used for generating random salts or passwords
    private byte[] randomBytes(int bits) {
        byte[] array = new byte[bits / 8];
        mRng.nextBytes(array);
        return array;
    }

    boolean passwordMatchesSaved(String algorithm, String candidatePw, int rounds) {
        if (mPasswordHash == null) {
            // no current password case -- require that 'currentPw' be null or empty
            if (candidatePw == null || "".equals(candidatePw)) {
                return true;
            } // else the non-empty candidate does not match the empty stored pw
        } else {
            // hash the stated current pw and compare to the stored one
            if (candidatePw != null && candidatePw.length() > 0) {
                String currentPwHash = buildPasswordHash(algorithm, candidatePw, mPasswordSalt, rounds);
                if (mPasswordHash.equalsIgnoreCase(currentPwHash)) {
                    // candidate hash matches the stored hash -- the password matches
                    return true;
                }
            } // else the stored pw is nonempty but the candidate is empty; no match
        }
        return false;
    }

    public boolean setBackupPassword(String currentPw, String newPw) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setBackupPassword");

        // When processing v1 passwords we may need to try two different PBKDF2 checksum regimes
        final boolean pbkdf2Fallback = (mPasswordVersion < BACKUP_PW_FILE_VERSION);

        // If the supplied pw doesn't hash to the the saved one, fail.  The password
        // might be caught in the legacy crypto mismatch; verify that too.
        if (!passwordMatchesSaved(PBKDF_CURRENT, currentPw, PBKDF2_HASH_ROUNDS)
                && !(pbkdf2Fallback && passwordMatchesSaved(PBKDF_FALLBACK,
                        currentPw, PBKDF2_HASH_ROUNDS))) {
            return false;
        }

        // Snap up to current on the pw file version
        mPasswordVersion = BACKUP_PW_FILE_VERSION;
        FileOutputStream pwFout = null;
        DataOutputStream pwOut = null;
        try {
            pwFout = new FileOutputStream(mPasswordVersionFile);
            pwOut = new DataOutputStream(pwFout);
            pwOut.writeInt(mPasswordVersion);
        } catch (IOException e) {
            Slog.e(TAG, "Unable to write backup pw version; password not changed");
            return false;
        } finally {
            try {
                if (pwOut != null) pwOut.close();
                if (pwFout != null) pwFout.close();
            } catch (IOException e) {
                Slog.w(TAG, "Unable to close pw version record");
            }
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
            String newPwHash = buildPasswordHash(PBKDF_CURRENT, newPw, salt, PBKDF2_HASH_ROUNDS);

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

    public boolean hasBackupPassword() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "hasBackupPassword");

        return mPasswordHash != null && mPasswordHash.length() > 0;
    }

    private boolean backupPasswordMatches(String currentPw) {
        if (hasBackupPassword()) {
            final boolean pbkdf2Fallback = (mPasswordVersion < BACKUP_PW_FILE_VERSION);
            if (!passwordMatchesSaved(PBKDF_CURRENT, currentPw, PBKDF2_HASH_ROUNDS)
                    && !(pbkdf2Fallback && passwordMatchesSaved(PBKDF_FALLBACK,
                            currentPw, PBKDF2_HASH_ROUNDS))) {
                if (DEBUG) Slog.w(TAG, "Backup password mismatch; aborting");
                return false;
            }
        }
        return true;
    }

    // Maintain persistent state around whether need to do an initialize operation.
    // Must be called with the queue lock held.
    void recordInitPendingLocked(boolean isPending, String transportName) {
        if (MORE_DEBUG) Slog.i(TAG, "recordInitPendingLocked: " + isPending
                + " on transport " + transportName);
        mBackupHandler.removeMessages(MSG_RETRY_INIT);

        try {
            IBackupTransport transport = getTransport(transportName);
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
        } catch (RemoteException e) {
            // transport threw when asked its name; fall through to the lookup-failed case
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
    private void registerTransport(String name, String component, IBackupTransport transport) {
        synchronized (mTransports) {
            if (DEBUG) Slog.v(TAG, "Registering transport "
                    + component + "::" + name + " = " + transport);
            if (transport != null) {
                mTransports.put(name, transport);
                mTransportNames.put(component, name);
            } else {
                mTransports.remove(mTransportNames.get(component));
                mTransportNames.remove(component);
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
                    mPendingInits.add(name);

                    // TODO: pick a better starting time than now + 1 minute
                    long delay = 1000 * 60; // one minute, in milliseconds
                    mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + delay, mRunInitIntent);
                }
            }
        } catch (RemoteException e) {
            // the transport threw when asked its file naming prefs; declare it invalid
            Slog.e(TAG, "Unable to register transport as " + name);
            mTransportNames.remove(component);
            mTransports.remove(name);
        }
    }

    // ----- Track installation/removal of packages -----
    BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
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
                String pkgName = uri.getSchemeSpecificPart();
                if (pkgName != null) {
                    pkgList = new String[] { pkgName };
                }
                changed = Intent.ACTION_PACKAGE_CHANGED.equals(action);

                // At package-changed we only care about looking at new transport states
                if (changed) {
                    try {
                        String[] components =
                                intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);

                        if (MORE_DEBUG) {
                            Slog.i(TAG, "Package " + pkgName + " changed; rechecking");
                            for (int i = 0; i < components.length; i++) {
                                Slog.i(TAG, "   * " + components[i]);
                            }
                        }

                        // In general we need to try to bind any time we see a component enable
                        // state change, because that change may have made a transport available.
                        // However, because we currently only support a single transport component
                        // per package, we can skip the bind attempt if the change (a) affects a
                        // package known to host a transport, but (b) does not affect the known
                        // transport component itself.
                        //
                        // In addition, if the change *is* to a known transport component, we need
                        // to unbind it before retrying the binding.
                        boolean tryBind = true;
                        synchronized (mTransports) {
                            TransportConnection conn = mTransportConnections.get(pkgName);
                            if (conn != null) {
                                // We have a bound transport in this package; do we need to rebind it?
                                final ServiceInfo svc = conn.mTransport;
                                ComponentName svcName =
                                        new ComponentName(svc.packageName, svc.name);
                                if (svc.packageName.equals(pkgName)) {
                                    final String className = svcName.getClassName();
                                    if (MORE_DEBUG) {
                                        Slog.i(TAG, "Checking need to rebind " + className);
                                    }
                                    // See whether it's the transport component within this package
                                    boolean isTransport = false;
                                    for (int i = 0; i < components.length; i++) {
                                        if (className.equals(components[i])) {
                                            // Okay, it's an existing transport component.
                                            final String flatName = svcName.flattenToShortString();
                                            mContext.unbindService(conn);
                                            mTransportConnections.remove(pkgName);
                                            mTransports.remove(mTransportNames.get(flatName));
                                            mTransportNames.remove(flatName);
                                            isTransport = true;
                                            break;
                                        }
                                    }
                                    if (!isTransport) {
                                        // A non-transport component within a package that is hosting
                                        // a bound transport
                                        tryBind = false;
                                    }
                                }
                            }
                        }
                        // and now (re)bind as appropriate
                        if (tryBind) {
                            if (MORE_DEBUG) {
                                Slog.i(TAG, "Yes, need to recheck binding");
                            }
                            PackageInfo app = mPackageManager.getPackageInfo(pkgName, 0);
                            checkForTransportAndBind(app);
                        }
                    } catch (NameNotFoundException e) {
                        // Nope, can't find it - just ignore
                        if (MORE_DEBUG) {
                            Slog.w(TAG, "Can't find changed package " + pkgName);
                        }
                    }
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
                for (String packageName : pkgList) {
                    try {
                        PackageInfo app = mPackageManager.getPackageInfo(packageName, 0);
                        if (appGetsFullBackup(app) && appIsEligibleForBackup(app.applicationInfo)) {
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

                        // Transport maintenance: rebind to known existing transports that have
                        // just been updated; and bind to any newly-installed transport services.
                        synchronized (mTransports) {
                            final TransportConnection conn = mTransportConnections.get(packageName);
                            if (conn != null) {
                                if (MORE_DEBUG) {
                                    Slog.i(TAG, "Transport package changed; rebinding");
                                }
                                bindTransport(conn.mTransport);
                            } else {
                                checkForTransportAndBind(app);
                            }
                        }

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
            }
        }
    };

    // ----- Track connection to transports service -----
    class TransportConnection implements ServiceConnection {
        ServiceInfo mTransport;

        public TransportConnection(ServiceInfo transport) {
            mTransport = transport;
        }

        @Override
        public void onServiceConnected(ComponentName component, IBinder service) {
            if (DEBUG) Slog.v(TAG, "Connected to transport " + component);
            final String name = component.flattenToShortString();
            try {
                IBackupTransport transport = IBackupTransport.Stub.asInterface(service);
                registerTransport(transport.name(), name, transport);
                EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, name, 1);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to register transport " + component);
                EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, name, 0);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName component) {
            if (DEBUG) Slog.v(TAG, "Disconnected from transport " + component);
            final String name = component.flattenToShortString();
            EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, name, 0);
            registerTransport(null, name, null);
        }
    };

    // Check whether the given package hosts a transport, and bind if so
    void checkForTransportAndBind(PackageInfo pkgInfo) {
        Intent intent = new Intent(mTransportServiceIntent)
                .setPackage(pkgInfo.packageName);
        // TODO: http://b/22388012
        List<ResolveInfo> hosts = mPackageManager.queryIntentServicesAsUser(
                intent, 0, UserHandle.USER_SYSTEM);
        if (hosts != null) {
            final int N = hosts.size();
            for (int i = 0; i < N; i++) {
                final ServiceInfo info = hosts.get(i).serviceInfo;
                tryBindTransport(info);
            }
        }
    }

    // Verify that the service exists and is hosted by a privileged app, then proceed to bind
    boolean tryBindTransport(ServiceInfo info) {
        try {
            PackageInfo packInfo = mPackageManager.getPackageInfo(info.packageName, 0);
            if ((packInfo.applicationInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED)
                    != 0) {
                return bindTransport(info);
            } else {
                Slog.w(TAG, "Transport package " + info.packageName + " not privileged");
            }
        } catch (NameNotFoundException e) {
            Slog.w(TAG, "Problem resolving transport package " + info.packageName);
        }
        return false;
    }

    // Actually bind; presumes that we have already validated the transport service
    boolean bindTransport(ServiceInfo transport) {
        ComponentName svcName = new ComponentName(transport.packageName, transport.name);
        if (!mTransportWhitelist.contains(svcName)) {
            Slog.w(TAG, "Proposed transport " + svcName + " not whitelisted; ignoring");
            return false;
        }

        if (MORE_DEBUG) {
            Slog.i(TAG, "Binding to transport host " + svcName);
        }
        Intent intent = new Intent(mTransportServiceIntent);
        intent.setComponent(svcName);

        TransportConnection connection;
        synchronized (mTransports) {
            connection = mTransportConnections.get(transport.packageName);
            if (null == connection) {
                connection = new TransportConnection(transport);
                mTransportConnections.put(transport.packageName, connection);
            } else {
                // This is a rebind due to package upgrade.  The service won't be
                // automatically relaunched for us until we explicitly rebind, but
                // we need to unbind the now-orphaned original connection.
                mContext.unbindService(connection);
            }
        }
        // TODO: http://b/22388012
        return mContext.bindServiceAsUser(intent,
                connection, Context.BIND_AUTO_CREATE,
                UserHandle.SYSTEM);
    }

    // Add the backup agents in the given packages to our set of known backup participants.
    // If 'packageNames' is null, adds all backup agents in the whole system.
    void addPackageParticipantsLocked(String[] packageNames) {
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
                    set = new HashSet<String>();
                    mBackupParticipants.put(uid, set);
                }
                set.add(pkg.packageName);
                if (MORE_DEBUG) Slog.v(TAG, "Agent found; added");

                // Schedule a backup for it on general principles
                if (MORE_DEBUG) Slog.i(TAG, "Scheduling backup for new app " + pkg.packageName);
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

        if (MORE_DEBUG) Slog.v(TAG, "removePackageParticipantsLocked: uid=" + oldUid
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
                        || app.backupAgentName == null
                        || (app.flags&ApplicationInfo.FLAG_FULL_BACKUP_ONLY) != 0) {
                    packages.remove(a);
                }
                else {
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

    // What name is this transport registered under...?
    private String getTransportName(IBackupTransport transport) {
        if (MORE_DEBUG) {
            Slog.v(TAG, "Searching for transport name of " + transport);
        }
        synchronized (mTransports) {
            final int N = mTransports.size();
            for (int i = 0; i < N; i++) {
                if (mTransports.valueAt(i).equals(transport)) {
                    if (MORE_DEBUG) {
                        Slog.v(TAG, "  Name found: " + mTransports.keyAt(i));
                    }
                    return mTransports.keyAt(i);
                }
            }
        }
        return null;
    }

    // fire off a backup agent, blocking until it attaches or times out
    IBackupAgent bindToAgentSynchronous(ApplicationInfo app, int mode) {
        IBackupAgent agent = null;
        synchronized(mAgentConnectLock) {
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
                // can't happen - ActivityManager is local
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

    public int requestBackup(String[] packages, IBackupObserver observer) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "requestBackup");

        if (packages == null || packages.length < 1) {
            Slog.e(TAG, "No packages named for backup request");
            sendBackupFinished(observer, BackupManager.ERROR_TRANSPORT_ABORTED);
            throw new IllegalArgumentException("No packages are provided for backup");
        }

        IBackupTransport transport = getTransport(mCurrentTransport);
        if (transport == null) {
            sendBackupFinished(observer, BackupManager.ERROR_TRANSPORT_ABORTED);
            return BackupManager.ERROR_TRANSPORT_ABORTED;
        }

        ArrayList<String> fullBackupList = new ArrayList<>();
        ArrayList<String> kvBackupList = new ArrayList<>();
        for (String packageName : packages) {
            try {
                PackageInfo packageInfo = mPackageManager.getPackageInfo(packageName,
                        PackageManager.GET_SIGNATURES);
                if (!appIsEligibleForBackup(packageInfo.applicationInfo)) {
                    sendBackupOnPackageResult(observer, packageName,
                            BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                    continue;
                }
                if (appGetsFullBackup(packageInfo)) {
                    fullBackupList.add(packageInfo.packageName);
                } else {
                    kvBackupList.add(packageInfo.packageName);
                }
            } catch (NameNotFoundException e) {
                sendBackupOnPackageResult(observer, packageName,
                        BackupManager.ERROR_PACKAGE_NOT_FOUND);
            }
        }
        EventLog.writeEvent(EventLogTags.BACKUP_REQUESTED, packages.length, kvBackupList.size(),
                fullBackupList.size());
        if (MORE_DEBUG) {
            Slog.i(TAG, "Backup requested for " + packages.length + " packages, of them: " +
                fullBackupList.size() + " full backups, " + kvBackupList.size() + " k/v backups");
        }

        String dirName;
        try {
            dirName = transport.transportDirName();
        } catch (RemoteException e) {
            Slog.e(TAG, "Transport became unavailable while attempting backup");
            sendBackupFinished(observer, BackupManager.ERROR_TRANSPORT_ABORTED);
            return BackupManager.ERROR_TRANSPORT_ABORTED;
        }
        Message msg = mBackupHandler.obtainMessage(MSG_REQUEST_BACKUP);
        msg.obj = new BackupParams(transport, dirName, kvBackupList, fullBackupList, observer,
                true);
        mBackupHandler.sendMessage(msg);
        return BackupManager.SUCCESS;
    }

    // -----
    // Interface and methods used by the asynchronous-with-timeout backup/restore operations

    interface BackupRestoreTask {
        // Execute one tick of whatever state machine the task implements
        void execute();

        // An operation that wanted a callback has completed
        void operationComplete(long result);

        // An operation that wanted a callback has timed out
        void handleTimeout();
    }

    void prepareOperationTimeout(int token, long interval, BackupRestoreTask callback) {
        if (MORE_DEBUG) Slog.v(TAG, "starting timeout: token=" + Integer.toHexString(token)
                + " interval=" + interval + " callback=" + callback);
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
            if (state == OP_ACKNOWLEDGED) {
                // The operation finished cleanly, so we have nothing more to do.
                if (MORE_DEBUG) {
                    Slog.v(TAG, "handleTimeout() after success; cleanup happens now");
                }
                op = null;
                mCurrentOperations.delete(token);
            } else if (state == OP_PENDING) {
                if (DEBUG) Slog.v(TAG, "TIMEOUT: token=" + Integer.toHexString(token));
                op.state = OP_TIMEOUT;
                // Leaves the object in place for later ack
            }
            mCurrentOpLock.notifyAll();
        }

        // If there's a TimeoutHandler for this event, call it
        if (op != null && op.callback != null) {
            if (MORE_DEBUG) {
                Slog.v(TAG, "   Invoking timeout on " + op.callback);
            }
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
        ArrayList<String> mPendingFullBackups;
        IBackupObserver mObserver;

        // carried information about the current in-flight operation
        IBackupAgent mAgentBinder;
        PackageInfo mCurrentPackage;
        File mSavedStateName;
        File mBackupDataName;
        File mNewStateName;
        ParcelFileDescriptor mSavedState;
        ParcelFileDescriptor mBackupData;
        ParcelFileDescriptor mNewState;
        int mStatus;
        boolean mFinished;
        boolean mUserInitiated;

        public PerformBackupTask(IBackupTransport transport, String dirName,
                ArrayList<BackupRequest> queue, File journal, IBackupObserver observer,
                ArrayList<String> pendingFullBackups, boolean userInitiated) {
            mTransport = transport;
            mOriginalQueue = queue;
            mJournal = journal;
            mObserver = observer;
            mPendingFullBackups = pendingFullBackups;
            mUserInitiated = userInitiated;

            mStateDir = new File(mBaseStateDir, dirName);

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

            mAgentBinder = null;
            mStatus = BackupTransport.TRANSPORT_OK;

            // Sanity check: if the queue is empty we have no work to do.
            if (mOriginalQueue.isEmpty() && mPendingFullBackups.isEmpty()) {
                Slog.w(TAG, "Backup begun with an empty queue - nothing to do.");
                addBackupTrace("queue empty at begin");
                sendBackupFinished(mObserver, BackupManager.SUCCESS);
                executeNextState(BackupState.FINAL);
                return;
            }

            // We need to retain the original queue contents in case of transport
            // failure, but we want a working copy that we can manipulate along
            // the way.
            mQueue = (ArrayList<BackupRequest>) mOriginalQueue.clone();

            // The app metadata pseudopackage might also be represented in the
            // backup queue if apps have been added/removed since the last time
            // we performed a backup.  Drop it from the working queue now that
            // we're committed to evaluating it for backup regardless.
            for (int i = 0; i < mQueue.size(); i++) {
                if (PACKAGE_MANAGER_SENTINEL.equals(mQueue.get(i).packageName)) {
                    if (MORE_DEBUG) {
                        Slog.i(TAG, "Metadata in queue; eliding");
                    }
                    mQueue.remove(i);
                    break;
                }
            }

            if (DEBUG) Slog.v(TAG, "Beginning backup of " + mQueue.size() + " targets");

            File pmState = new File(mStateDir, PACKAGE_MANAGER_SENTINEL);
            try {
                final String transportName = mTransport.transportDirName();
                EventLog.writeEvent(EventLogTags.BACKUP_START, transportName);

                // If we haven't stored package manager metadata yet, we must init the transport.
                if (mStatus == BackupTransport.TRANSPORT_OK && pmState.length() <= 0) {
                    Slog.i(TAG, "Initializing (wiping) backup state and transport storage");
                    addBackupTrace("initializing transport " + transportName);
                    resetBackupState(mStateDir);  // Just to make sure.
                    mStatus = mTransport.initializeDevice();

                    addBackupTrace("transport.initializeDevice() == " + mStatus);
                    if (mStatus == BackupTransport.TRANSPORT_OK) {
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
                if (mStatus == BackupTransport.TRANSPORT_OK) {
                    PackageManagerBackupAgent pmAgent = new PackageManagerBackupAgent(
                            mPackageManager);
                    mStatus = invokeAgentForBackup(PACKAGE_MANAGER_SENTINEL,
                            IBackupAgent.Stub.asInterface(pmAgent.onBind()), mTransport);
                    addBackupTrace("PMBA invoke: " + mStatus);

                    // Because the PMBA is a local instance, it has already executed its
                    // backup callback and returned.  Blow away the lingering (spurious)
                    // pending timeout message for it.
                    mBackupHandler.removeMessages(MSG_TIMEOUT);
                }

                if (mStatus == BackupTransport.TRANSPORT_NOT_INITIALIZED) {
                    // The backend reports that our dataset has been wiped.  Note this in
                    // the event log; the no-success code below will reset the backup
                    // state as well.
                    EventLog.writeEvent(EventLogTags.BACKUP_RESET, mTransport.transportDirName());
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error in backup thread", e);
                addBackupTrace("Exception in backup thread: " + e);
                mStatus = BackupTransport.TRANSPORT_ERROR;
            } finally {
                // If we've succeeded so far, invokeAgentForBackup() will have run the PM
                // metadata and its completion/timeout callback will continue the state
                // machine chain.  If it failed that won't happen; we handle that now.
                addBackupTrace("exiting prelim: " + mStatus);
                if (mStatus != BackupTransport.TRANSPORT_OK) {
                    // if things went wrong at this point, we need to
                    // restage everything and try again later.
                    resetBackupState(mStateDir);  // Just to make sure.
                    // In case of any other error, it's backup transport error.
                    sendBackupFinished(mObserver, BackupManager.ERROR_TRANSPORT_ABORTED);
                    executeNextState(BackupState.FINAL);
                }
            }
        }

        // Transport has been initialized and the PM metadata submitted successfully
        // if that was warranted.  Now we process the single next thing in the queue.
        void invokeNextAgent() {
            mStatus = BackupTransport.TRANSPORT_OK;
            addBackupTrace("invoke q=" + mQueue.size());

            // Sanity check that we have work to do.  If not, skip to the end where
            // we reestablish the wakelock invariants etc.
            if (mQueue.isEmpty()) {
                if (MORE_DEBUG) Slog.i(TAG, "queue now empty");
                executeNextState(BackupState.FINAL);
                return;
            }

            // pop the entry we're going to process on this step
            BackupRequest request = mQueue.get(0);
            mQueue.remove(0);

            Slog.d(TAG, "starting key/value backup of " + request);
            addBackupTrace("launch agent for " + request.packageName);

            // Verify that the requested app exists; it might be something that
            // requested a backup but was then uninstalled.  The request was
            // journalled and rather than tamper with the journal it's safer
            // to sanity-check here.  This also gives us the classname of the
            // package's backup agent.
            try {
                mCurrentPackage = mPackageManager.getPackageInfo(request.packageName,
                        PackageManager.GET_SIGNATURES);
                if (!appIsEligibleForBackup(mCurrentPackage.applicationInfo)) {
                    // The manifest has changed but we had a stale backup request pending.
                    // This won't happen again because the app won't be requesting further
                    // backups.
                    Slog.i(TAG, "Package " + request.packageName
                            + " no longer supports backup; skipping");
                    addBackupTrace("skipping - not eligible, completion is noop");
                    // Shouldn't happen in case of requested backup, as pre-check was done in
                    // #requestBackup(), except to app update done concurrently
                    sendBackupOnPackageResult(mObserver, mCurrentPackage.packageName,
                            BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                    executeNextState(BackupState.RUNNING_QUEUE);
                    return;
                }

                if (appGetsFullBackup(mCurrentPackage)) {
                    // It's possible that this app *formerly* was enqueued for key/value backup,
                    // but has since been updated and now only supports the full-data path.
                    // Don't proceed with a key/value backup for it in this case.
                    Slog.i(TAG, "Package " + request.packageName
                            + " requests full-data rather than key/value; skipping");
                    addBackupTrace("skipping - fullBackupOnly, completion is noop");
                    // Shouldn't happen in case of requested backup, as pre-check was done in
                    // #requestBackup()
                    sendBackupOnPackageResult(mObserver, mCurrentPackage.packageName,
                            BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                    executeNextState(BackupState.RUNNING_QUEUE);
                    return;
                }

                if (appIsStopped(mCurrentPackage.applicationInfo)) {
                    // The app has been force-stopped or cleared or just installed,
                    // and not yet launched out of that state, so just as it won't
                    // receive broadcasts, we won't run it for backup.
                    addBackupTrace("skipping - stopped");
                    sendBackupOnPackageResult(mObserver, mCurrentPackage.packageName,
                            BackupManager.ERROR_BACKUP_NOT_ALLOWED);
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
                        mAgentBinder = agent;
                        mStatus = invokeAgentForBackup(request.packageName, agent, mTransport);
                        // at this point we'll either get a completion callback from the
                        // agent, or a timeout message on the main handler.  either way, we're
                        // done here as long as we're successful so far.
                    } else {
                        // Timeout waiting for the agent
                        mStatus = BackupTransport.AGENT_ERROR;
                    }
                } catch (SecurityException ex) {
                    // Try for the next one.
                    Slog.d(TAG, "error in bind/backup", ex);
                    mStatus = BackupTransport.AGENT_ERROR;
                            addBackupTrace("agent SE");
                }
            } catch (NameNotFoundException e) {
                Slog.d(TAG, "Package does not exist; skipping");
                addBackupTrace("no such package");
                mStatus = BackupTransport.AGENT_UNKNOWN;
            } finally {
                mWakelock.setWorkSource(null);

                // If there was an agent error, no timeout/completion handling will occur.
                // That means we need to direct to the next state ourselves.
                if (mStatus != BackupTransport.TRANSPORT_OK) {
                    BackupState nextState = BackupState.RUNNING_QUEUE;
                    mAgentBinder = null;

                    // An agent-level failure means we reenqueue this one agent for
                    // a later retry, but otherwise proceed normally.
                    if (mStatus == BackupTransport.AGENT_ERROR) {
                        if (MORE_DEBUG) Slog.i(TAG, "Agent failure for " + request.packageName
                                + " - restaging");
                        dataChangedImpl(request.packageName);
                        mStatus = BackupTransport.TRANSPORT_OK;
                        if (mQueue.isEmpty()) nextState = BackupState.FINAL;
                        sendBackupOnPackageResult(mObserver, mCurrentPackage.packageName,
                                BackupManager.ERROR_AGENT_FAILURE);
                    } else if (mStatus == BackupTransport.AGENT_UNKNOWN) {
                        // Failed lookup of the app, so we couldn't bring up an agent, but
                        // we're otherwise fine.  Just drop it and go on to the next as usual.
                        mStatus = BackupTransport.TRANSPORT_OK;
                        sendBackupOnPackageResult(mObserver, mCurrentPackage.packageName,
                                BackupManager.ERROR_PACKAGE_NOT_FOUND);
                    } else {
                        // Transport-level failure means we reenqueue everything
                        revertAndEndBackup();
                        nextState = BackupState.FINAL;
                    }

                    executeNextState(nextState);
                } else {
                    // success case
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
            if ((mCurrentToken == 0) && (mStatus == BackupTransport.TRANSPORT_OK)) {
                addBackupTrace("success; recording token");
                try {
                    mCurrentToken = mTransport.getCurrentRestoreSet();
                    writeRestoreTokens();
                } catch (RemoteException e) {
                    // nothing for it at this point, unfortunately, but this will be
                    // recorded the next time we fully succeed.
                    addBackupTrace("transport threw returning token");
                }
            }

            // Set up the next backup pass - at this point we can set mBackupRunning
            // to false to allow another pass to fire, because we're done with the
            // state machine sequence and the wakelock is refcounted.
            synchronized (mQueueLock) {
                mBackupRunning = false;
                if (mStatus == BackupTransport.TRANSPORT_NOT_INITIALIZED) {
                    // Make sure we back up everything and perform the one-time init
                    if (MORE_DEBUG) Slog.d(TAG, "Server requires init; rerunning");
                    addBackupTrace("init required; rerunning");
                    try {
                        final String name = getTransportName(mTransport);
                        if (name != null) {
                            mPendingInits.add(name);
                        } else {
                            if (DEBUG) {
                                Slog.w(TAG, "Couldn't find name of transport " + mTransport
                                        + " for init");
                            }
                        }
                    } catch (Exception e) {
                        Slog.w(TAG, "Failed to query transport name heading for init", e);
                        // swallow it and proceed; we don't rely on this
                    }
                    clearMetadata();
                    backupNow();
                }
            }

            clearBackupTrace();

            if (mStatus == BackupTransport.TRANSPORT_OK &&
                    mPendingFullBackups != null && !mPendingFullBackups.isEmpty()) {
                Slog.d(TAG, "Starting full backups for: " + mPendingFullBackups);
                CountDownLatch latch = new CountDownLatch(1);
                String[] fullBackups =
                        mPendingFullBackups.toArray(new String[mPendingFullBackups.size()]);
                PerformFullTransportBackupTask task =
                        new PerformFullTransportBackupTask(/*fullBackupRestoreObserver*/ null,
                                fullBackups, /*updateSchedule*/ false, /*runningJob*/ null, latch,
                                mObserver, mUserInitiated);
                // Acquiring wakelock for PerformFullTransportBackupTask before its start.
                mWakelock.acquire();
                (new Thread(task, "full-transport-requested")).start();
            } else {
                switch (mStatus) {
                    case BackupTransport.TRANSPORT_OK:
                        sendBackupFinished(mObserver, BackupManager.SUCCESS);
                        break;
                    case BackupTransport.TRANSPORT_NOT_INITIALIZED:
                        sendBackupFinished(mObserver, BackupManager.ERROR_TRANSPORT_ABORTED);
                        break;
                    case BackupTransport.TRANSPORT_ERROR:
                    default:
                        sendBackupFinished(mObserver, BackupManager.ERROR_TRANSPORT_ABORTED);
                        break;
                }
            }
            Slog.i(BackupManagerService.TAG, "K/V backup pass finished.");
            // Only once we're entirely finished do we release the wakelock for k/v backup.
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
            if (MORE_DEBUG) Slog.d(TAG, "data file: " + mBackupDataName);

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

                if (!SELinux.restorecon(mBackupDataName)) {
                    Slog.e(TAG, "SELinux restorecon failed on " + mBackupDataName);
                }

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
                return BackupTransport.AGENT_ERROR;
            }

            // At this point the agent is off and running.  The next thing to happen will
            // either be a callback from the agent, at which point we'll process its data
            // for transport, or a timeout.  Either way the next phase will happen in
            // response to the TimeoutHandler interface callbacks.
            addBackupTrace("invoke success");
            return BackupTransport.TRANSPORT_OK;
        }

        public void failAgent(IBackupAgent agent, String message) {
            try {
                agent.fail(message);
            } catch (Exception e) {
                Slog.w(TAG, "Error conveying failure to " + mCurrentPackage.packageName);
            }
        }

        // SHA-1 a byte array and return the result in hex
        private String SHA1Checksum(byte[] input) {
            final byte[] checksum;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                checksum = md.digest(input);
            } catch (NoSuchAlgorithmException e) {
                Slog.e(TAG, "Unable to use SHA-1!");
                return "00";
            }

            StringBuffer sb = new StringBuffer(checksum.length * 2);
            for (int i = 0; i < checksum.length; i++) {
                sb.append(Integer.toHexString(checksum[i]));
            }
            return sb.toString();
        }

        private void writeWidgetPayloadIfAppropriate(FileDescriptor fd, String pkgName)
                throws IOException {
            // TODO: http://b/22388012
            byte[] widgetState = AppWidgetBackupBridge.getWidgetState(pkgName,
                    UserHandle.USER_SYSTEM);
            // has the widget state changed since last time?
            final File widgetFile = new File(mStateDir, pkgName + "_widget");
            final boolean priorStateExists = widgetFile.exists();

            if (MORE_DEBUG) {
                if (priorStateExists || widgetState != null) {
                    Slog.i(TAG, "Checking widget update: state=" + (widgetState != null)
                            + " prior=" + priorStateExists);
                }
            }

            if (!priorStateExists && widgetState == null) {
                // no prior state, no new state => nothing to do
                return;
            }

            // if the new state is not null, we might need to compare checksums to
            // determine whether to update the widget blob in the archive.  If the
            // widget state *is* null, we know a priori at this point that we simply
            // need to commit a deletion for it.
            String newChecksum = null;
            if (widgetState != null) {
                newChecksum = SHA1Checksum(widgetState);
                if (priorStateExists) {
                    final String priorChecksum;
                    try (
                        FileInputStream fin = new FileInputStream(widgetFile);
                        DataInputStream in = new DataInputStream(fin)
                    ) {
                        priorChecksum = in.readUTF();
                    }
                    if (Objects.equals(newChecksum, priorChecksum)) {
                        // Same checksum => no state change => don't rewrite the widget data
                        return;
                    }
                }
            } // else widget state *became* empty, so we need to commit a deletion

            BackupDataOutput out = new BackupDataOutput(fd);
            if (widgetState != null) {
                try (
                    FileOutputStream fout = new FileOutputStream(widgetFile);
                    DataOutputStream stateOut = new DataOutputStream(fout)
                ) {
                    stateOut.writeUTF(newChecksum);
                }

                out.writeEntityHeader(KEY_WIDGET_STATE, widgetState.length);
                out.writeEntityData(widgetState, widgetState.length);
            } else {
                // Widget state for this app has been removed; commit a deletion
                out.writeEntityHeader(KEY_WIDGET_STATE, -1);
                widgetFile.delete();
            }
        }

        @Override
        public void operationComplete(long unusedResult) {
            // The agent reported back to us!

            if (mBackupData == null) {
                // This callback was racing with our timeout, so we've cleaned up the
                // agent state already and are on to the next thing.  We have nothing
                // further to do here: agent state having been cleared means that we've
                // initiated the appropriate next operation.
                final String pkg = (mCurrentPackage != null)
                        ? mCurrentPackage.packageName : "[none]";
                if (MORE_DEBUG) {
                    Slog.i(TAG, "Callback after agent teardown: " + pkg);
                }
                addBackupTrace("late opComplete; curPkg = " + pkg);
                return;
            }

            final String pkgName = mCurrentPackage.packageName;
            final long filepos = mBackupDataName.length();
            FileDescriptor fd = mBackupData.getFileDescriptor();
            try {
                // If it's a 3rd party app, see whether they wrote any protected keys
                // and complain mightily if they are attempting shenanigans.
                if (mCurrentPackage.applicationInfo != null &&
                        (mCurrentPackage.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) == 0) {
                    ParcelFileDescriptor readFd = ParcelFileDescriptor.open(mBackupDataName,
                            ParcelFileDescriptor.MODE_READ_ONLY);
                    BackupDataInput in = new BackupDataInput(readFd.getFileDescriptor());
                    try {
                        while (in.readNextHeader()) {
                            final String key = in.getKey();
                            if (key != null && key.charAt(0) >= 0xff00) {
                                // Not okay: crash them and bail.
                                failAgent(mAgentBinder, "Illegal backup key: " + key);
                                addBackupTrace("illegal key " + key + " from " + pkgName);
                                EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, pkgName,
                                        "bad key");
                                mBackupHandler.removeMessages(MSG_TIMEOUT);
                                sendBackupOnPackageResult(mObserver, pkgName,
                                        BackupManager.ERROR_AGENT_FAILURE);
                                agentErrorCleanup();
                                // agentErrorCleanup() implicitly executes next state properly
                                return;
                            }
                            in.skipEntityData();
                        }
                    } finally {
                        if (readFd != null) {
                            readFd.close();
                        }
                    }
                }

                // Piggyback the widget state payload, if any
                writeWidgetPayloadIfAppropriate(fd, pkgName);
            } catch (IOException e) {
                // Hard disk error; recovery/failure policy TBD.  For now roll back,
                // but we may want to consider this a transport-level failure (i.e.
                // we're in such a bad state that we can't contemplate doing backup
                // operations any more during this pass).
                Slog.w(TAG, "Unable to save widget state for " + pkgName);
                try {
                    Os.ftruncate(fd, filepos);
                } catch (ErrnoException ee) {
                    Slog.w(TAG, "Unable to roll back!");
                }
            }

            // Spin the data off to the transport and proceed with the next stage.
            if (MORE_DEBUG) Slog.v(TAG, "operationComplete(): sending data to transport for "
                    + pkgName);
            mBackupHandler.removeMessages(MSG_TIMEOUT);
            clearAgentState();
            addBackupTrace("operation complete");

            ParcelFileDescriptor backupData = null;
            mStatus = BackupTransport.TRANSPORT_OK;
            long size = 0;
            try {
                size = mBackupDataName.length();
                if (size > 0) {
                    if (mStatus == BackupTransport.TRANSPORT_OK) {
                        backupData = ParcelFileDescriptor.open(mBackupDataName,
                                ParcelFileDescriptor.MODE_READ_ONLY);
                        addBackupTrace("sending data to transport");
                        int flags = mUserInitiated ? BackupTransport.FLAG_USER_INITIATED : 0;
                        mStatus = mTransport.performBackup(mCurrentPackage, backupData, flags);
                    }

                    // TODO - We call finishBackup() for each application backed up, because
                    // we need to know now whether it succeeded or failed.  Instead, we should
                    // hold off on finishBackup() until the end, which implies holding off on
                    // renaming *all* the output state files (see below) until that happens.

                    addBackupTrace("data delivered: " + mStatus);
                    if (mStatus == BackupTransport.TRANSPORT_OK) {
                        addBackupTrace("finishing op on transport");
                        mStatus = mTransport.finishBackup();
                        addBackupTrace("finished: " + mStatus);
                    } else if (mStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
                        addBackupTrace("transport rejected package");
                    }
                } else {
                    if (MORE_DEBUG) Slog.i(TAG, "no backup data written; not calling transport");
                    addBackupTrace("no data to send");
                }

                if (mStatus == BackupTransport.TRANSPORT_OK) {
                    // After successful transport, delete the now-stale data
                    // and juggle the files so that next time we supply the agent
                    // with the new state file it just created.
                    mBackupDataName.delete();
                    mNewStateName.renameTo(mSavedStateName);
                    sendBackupOnPackageResult(mObserver, pkgName, BackupManager.SUCCESS);
                    EventLog.writeEvent(EventLogTags.BACKUP_PACKAGE, pkgName, size);
                    logBackupComplete(pkgName);
                } else if (mStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
                    // The transport has rejected backup of this specific package.  Roll it
                    // back but proceed with running the rest of the queue.
                    mBackupDataName.delete();
                    mNewStateName.delete();
                    sendBackupOnPackageResult(mObserver, pkgName,
                            BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED);
                    EventLogTags.writeBackupAgentFailure(pkgName, "Transport rejected");
                } else if (mStatus == BackupTransport.TRANSPORT_QUOTA_EXCEEDED) {
                    sendBackupOnPackageResult(mObserver, pkgName,
                            BackupManager.ERROR_TRANSPORT_QUOTA_EXCEEDED);
                    EventLog.writeEvent(EventLogTags.BACKUP_QUOTA_EXCEEDED, pkgName);
                } else {
                    // Actual transport-level failure to communicate the data to the backend
                    sendBackupOnPackageResult(mObserver, pkgName,
                            BackupManager.ERROR_TRANSPORT_ABORTED);
                    EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, pkgName);
                }
            } catch (Exception e) {
                sendBackupOnPackageResult(mObserver, pkgName,
                        BackupManager.ERROR_TRANSPORT_ABORTED);
                Slog.e(TAG, "Transport error backing up " + pkgName, e);
                EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_FAILURE, pkgName);
                mStatus = BackupTransport.TRANSPORT_ERROR;
            } finally {
                try { if (backupData != null) backupData.close(); } catch (IOException e) {}
            }

            final BackupState nextState;
            if (mStatus == BackupTransport.TRANSPORT_OK
                    || mStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
                // Success or single-package rejection.  Proceed with the next app if any,
                // otherwise we're done.
                nextState = (mQueue.isEmpty()) ? BackupState.FINAL : BackupState.RUNNING_QUEUE;
            } else if (mStatus == BackupTransport.TRANSPORT_QUOTA_EXCEEDED) {
                if (MORE_DEBUG) {
                    Slog.d(TAG, "Package " + mCurrentPackage.packageName +
                            " hit quota limit on k/v backup");
                }
                if (mAgentBinder != null) {
                    try {
                        long quota = mTransport.getBackupQuota(mCurrentPackage.packageName, false);
                        mAgentBinder.doQuotaExceeded(size, quota);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to contact backup agent for quota exceeded");
                    }
                }
                nextState = (mQueue.isEmpty()) ? BackupState.FINAL : BackupState.RUNNING_QUEUE;
            } else {
                // Any other error here indicates a transport-level failure.  That means
                // we need to halt everything and reschedule everything for next time.
                revertAndEndBackup();
                nextState = BackupState.FINAL;
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

            // We want to reset the backup schedule based on whatever the transport suggests
            // by way of retry/backoff time.
            long delay;
            try {
                delay = mTransport.requestBackupTime();
            } catch (Exception e) {
                Slog.w(TAG, "Unable to contact transport for recommended backoff");
                delay = 0;  // use the scheduler's default
            }
            KeyValueBackupJob.schedule(mContext, delay);

            for (BackupRequest request : mOriginalQueue) {
                dataChangedImpl(request.packageName);
            }

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
            synchronized (mCurrentOpLock) {
                // Current-operation callback handling requires the validity of these various
                // bits of internal state as an invariant of the operation still being live.
                // This means we make sure to clear all of the state in unison inside the lock.
                mCurrentOperations.clear();
                mSavedState = mBackupData = mNewState = null;
            }

            // If this was a pseudopackage there's no associated Activity Manager state
            if (mCurrentPackage.applicationInfo != null) {
                addBackupTrace("unbinding " + mCurrentPackage.packageName);
                try {  // unbind even on timeout, just in case
                    mActivityManager.unbindBackupAgent(mCurrentPackage.applicationInfo);
                } catch (RemoteException e) { /* can't happen; activity manager is local */ }
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


    // ----- Full backup/restore to a file/socket -----

    class FullBackupObbConnection implements ServiceConnection {
        volatile IObbBackupService mService;

        FullBackupObbConnection() {
            mService = null;
        }

        public void establish() {
            if (MORE_DEBUG) Slog.i(TAG, "Initiating bind of OBB service on " + this);
            Intent obbIntent = new Intent().setComponent(new ComponentName(
                    "com.android.sharedstoragebackup",
                    "com.android.sharedstoragebackup.ObbBackupService"));
            BackupManagerService.this.mContext.bindServiceAsUser(
                    obbIntent, this, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
        }

        public void tearDown() {
            BackupManagerService.this.mContext.unbindService(this);
        }

        public boolean backupObbs(PackageInfo pkg, OutputStream out) {
            boolean success = false;
            waitForConnection();

            ParcelFileDescriptor[] pipes = null;
            try {
                pipes = ParcelFileDescriptor.createPipe();
                int token = generateToken();
                prepareOperationTimeout(token, TIMEOUT_FULL_BACKUP_INTERVAL, null);
                mService.backupObbs(pkg.packageName, pipes[1], token, mBackupManagerBinder);
                routeSocketDataToOutput(pipes[0], out);
                success = waitUntilOperationComplete(token);
            } catch (Exception e) {
                Slog.w(TAG, "Unable to back up OBBs for " + pkg, e);
            } finally {
                try {
                    out.flush();
                    if (pipes != null) {
                        if (pipes[0] != null) pipes[0].close();
                        if (pipes[1] != null) pipes[1].close();
                    }
                } catch (IOException e) {
                    Slog.w(TAG, "I/O error closing down OBB backup", e);
                }
            }
            return success;
        }

        public void restoreObbFile(String pkgName, ParcelFileDescriptor data,
                long fileSize, int type, String path, long mode, long mtime,
                int token, IBackupManager callbackBinder) {
            waitForConnection();

            try {
                mService.restoreObbFile(pkgName, data, fileSize, type, path, mode, mtime,
                        token, callbackBinder);
            } catch (Exception e) {
                Slog.w(TAG, "Unable to restore OBBs for " + pkgName, e);
            }
        }

        private void waitForConnection() {
            synchronized (this) {
                while (mService == null) {
                    if (MORE_DEBUG) Slog.i(TAG, "...waiting for OBB service binding...");
                    try {
                        this.wait();
                    } catch (InterruptedException e) { /* never interrupted */ }
                }
                if (MORE_DEBUG) Slog.i(TAG, "Connected to OBB service; continuing");
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (this) {
                mService = IObbBackupService.Stub.asInterface(service);
                if (MORE_DEBUG) Slog.i(TAG, "OBB service connection " + mService
                        + " connected on " + this);
                this.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (this) {
                mService = null;
                if (MORE_DEBUG) Slog.i(TAG, "OBB service connection disconnected on " + this);
                this.notifyAll();
            }
        }
        
    }

    private void routeSocketDataToOutput(ParcelFileDescriptor inPipe, OutputStream out)
            throws IOException {
        // We do not take close() responsibility for the pipe FD
        FileInputStream raw = new FileInputStream(inPipe.getFileDescriptor());
        DataInputStream in = new DataInputStream(raw);

        byte[] buffer = new byte[32 * 1024];
        int chunkTotal;
        while ((chunkTotal = in.readInt()) > 0) {
            while (chunkTotal > 0) {
                int toRead = (chunkTotal > buffer.length) ? buffer.length : chunkTotal;
                int nRead = in.read(buffer, 0, toRead);
                out.write(buffer, 0, nRead);
                chunkTotal -= nRead;
            }
        }
    }

    void tearDownAgentAndKill(ApplicationInfo app) {
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

    // Core logic for performing one package's full backup, gathering the tarball from the
    // application and emitting it to the designated OutputStream.

    // Callout from the engine to an interested participant that might need to communicate
    // with the agent prior to asking it to move data
    interface FullBackupPreflight {
        /**
         * Perform the preflight operation necessary for the given package.
         * @param pkg The name of the package being proposed for full-data backup
         * @param agent Live BackupAgent binding to the target app's agent
         * @return BackupTransport.TRANSPORT_OK to proceed with the backup operation,
         *         or one of the other BackupTransport.* error codes as appropriate
         */
        int preflightFullBackup(PackageInfo pkg, IBackupAgent agent);

        long getExpectedSizeOrErrorCode();
    };

    class FullBackupEngine {
        OutputStream mOutput;
        FullBackupPreflight mPreflightHook;
        BackupRestoreTask mTimeoutMonitor;
        IBackupAgent mAgent;
        File mFilesDir;
        File mManifestFile;
        File mMetadataFile;
        boolean mIncludeApks;
        PackageInfo mPkg;

        class FullBackupRunner implements Runnable {
            PackageInfo mPackage;
            byte[] mWidgetData;
            IBackupAgent mAgent;
            ParcelFileDescriptor mPipe;
            int mToken;
            boolean mSendApk;
            boolean mWriteManifest;

            FullBackupRunner(PackageInfo pack, IBackupAgent agent, ParcelFileDescriptor pipe,
                             int token, boolean sendApk, boolean writeManifest, byte[] widgetData)
                    throws IOException {
                mPackage = pack;
                mWidgetData = widgetData;
                mAgent = agent;
                mPipe = ParcelFileDescriptor.dup(pipe.getFileDescriptor());
                mToken = token;
                mSendApk = sendApk;
                mWriteManifest = writeManifest;
            }

            @Override
            public void run() {
                try {
                    FullBackupDataOutput output = new FullBackupDataOutput(mPipe);

                    if (mWriteManifest) {
                        final boolean writeWidgetData = mWidgetData != null;
                        if (MORE_DEBUG) Slog.d(TAG, "Writing manifest for " + mPackage.packageName);
                        writeAppManifest(mPackage, mManifestFile, mSendApk, writeWidgetData);
                        FullBackup.backupToTar(mPackage.packageName, null, null,
                                mFilesDir.getAbsolutePath(),
                                mManifestFile.getAbsolutePath(),
                                output);
                        mManifestFile.delete();

                        // We only need to write a metadata file if we have widget data to stash
                        if (writeWidgetData) {
                            writeMetadata(mPackage, mMetadataFile, mWidgetData);
                            FullBackup.backupToTar(mPackage.packageName, null, null,
                                    mFilesDir.getAbsolutePath(),
                                    mMetadataFile.getAbsolutePath(),
                                    output);
                            mMetadataFile.delete();
                        }
                    }

                    if (mSendApk) {
                        writeApkToBackup(mPackage, output);
                    }

                    if (DEBUG) Slog.d(TAG, "Calling doFullBackup() on " + mPackage.packageName);
                    prepareOperationTimeout(mToken, TIMEOUT_FULL_BACKUP_INTERVAL,
                            mTimeoutMonitor /* in parent class */);
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

        FullBackupEngine(OutputStream output, FullBackupPreflight preflightHook, PackageInfo pkg,
                         boolean alsoApks, BackupRestoreTask timeoutMonitor) {
            mOutput = output;
            mPreflightHook = preflightHook;
            mPkg = pkg;
            mIncludeApks = alsoApks;
            mTimeoutMonitor = timeoutMonitor;
            mFilesDir = new File("/data/system");
            mManifestFile = new File(mFilesDir, BACKUP_MANIFEST_FILENAME);
            mMetadataFile = new File(mFilesDir, BACKUP_METADATA_FILENAME);
        }

        public int preflightCheck() throws RemoteException {
            if (mPreflightHook == null) {
                if (MORE_DEBUG) {
                    Slog.v(TAG, "No preflight check");
                }
                return BackupTransport.TRANSPORT_OK;
            }
            if (initializeAgent()) {
                int result = mPreflightHook.preflightFullBackup(mPkg, mAgent);
                if (MORE_DEBUG) {
                    Slog.v(TAG, "preflight returned " + result);
                }
                return result;
            } else {
                Slog.w(TAG, "Unable to bind to full agent for " + mPkg.packageName);
                return BackupTransport.AGENT_ERROR;
            }
        }

        public int backupOnePackage() throws RemoteException {
            int result = BackupTransport.AGENT_ERROR;

            if (initializeAgent()) {
                ParcelFileDescriptor[] pipes = null;
                try {
                    pipes = ParcelFileDescriptor.createPipe();

                    ApplicationInfo app = mPkg.applicationInfo;
                    final boolean isSharedStorage =
                            mPkg.packageName.equals(SHARED_BACKUP_AGENT_PACKAGE);
                    final boolean sendApk = mIncludeApks
                            && !isSharedStorage
                            && ((app.privateFlags & ApplicationInfo.PRIVATE_FLAG_FORWARD_LOCK) == 0)
                            && ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 ||
                            (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);

                    // TODO: http://b/22388012
                    byte[] widgetBlob = AppWidgetBackupBridge.getWidgetState(mPkg.packageName,
                            UserHandle.USER_SYSTEM);

                    final int token = generateToken();
                    FullBackupRunner runner = new FullBackupRunner(mPkg, mAgent, pipes[1],
                            token, sendApk, !isSharedStorage, widgetBlob);
                    pipes[1].close();   // the runner has dup'd it
                    pipes[1] = null;
                    Thread t = new Thread(runner, "app-data-runner");
                    t.start();

                    // Now pull data from the app and stuff it into the output
                    routeSocketDataToOutput(pipes[0], mOutput);

                    if (!waitUntilOperationComplete(token)) {
                        Slog.e(TAG, "Full backup failed on package " + mPkg.packageName);
                    } else {
                        if (MORE_DEBUG) {
                            Slog.d(TAG, "Full package backup success: " + mPkg.packageName);
                        }
                        result = BackupTransport.TRANSPORT_OK;
                    }
                } catch (IOException e) {
                    Slog.e(TAG, "Error backing up " + mPkg.packageName + ": " + e.getMessage());
                    result = BackupTransport.AGENT_ERROR;
                } finally {
                    try {
                        // flush after every package
                        mOutput.flush();
                        if (pipes != null) {
                            if (pipes[0] != null) pipes[0].close();
                            if (pipes[1] != null) pipes[1].close();
                        }
                    } catch (IOException e) {
                        Slog.w(TAG, "Error bringing down backup stack");
                        result = BackupTransport.TRANSPORT_ERROR;
                    }
                }
            } else {
                Slog.w(TAG, "Unable to bind to full agent for " + mPkg.packageName);
            }
            tearDown();
            return result;
        }

        public void sendQuotaExceeded(final long backupDataBytes, final long quotaBytes) {
            if (initializeAgent()) {
                try {
                    mAgent.doQuotaExceeded(backupDataBytes, quotaBytes);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Remote exception while telling agent about quota exceeded");
                }
            }
        }

        private boolean initializeAgent() {
            if (mAgent == null) {
                if (MORE_DEBUG) {
                    Slog.d(TAG, "Binding to full backup agent : " + mPkg.packageName);
                }
                mAgent = bindToAgentSynchronous(mPkg.applicationInfo,
                        IApplicationThread.BACKUP_MODE_FULL);
            }
            return mAgent != null;
        }

        private void writeApkToBackup(PackageInfo pkg, FullBackupDataOutput output) {
            // Forward-locked apps, system-bundled .apks, etc are filtered out before we get here
            // TODO: handle backing up split APKs
            final String appSourceDir = pkg.applicationInfo.getBaseCodePath();
            final String apkDir = new File(appSourceDir).getParent();
            FullBackup.backupToTar(pkg.packageName, FullBackup.APK_TREE_TOKEN, null,
                    apkDir, appSourceDir, output);

            // TODO: migrate this to SharedStorageBackup, since AID_SYSTEM
            // doesn't have access to external storage.

            // Save associated .obb content if it exists and we did save the apk
            // check for .obb and save those too
            // TODO: http://b/22388012
            final UserEnvironment userEnv = new UserEnvironment(UserHandle.USER_SYSTEM);
            final File obbDir = userEnv.buildExternalStorageAppObbDirs(pkg.packageName)[0];
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

        private void writeAppManifest(PackageInfo pkg, File manifestFile,
                boolean withApk, boolean withWidgets) throws IOException {
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

            // We want the manifest block in the archive stream to be idempotent:
            // each time we generate a backup stream for the app, we want the manifest
            // block to be identical.  The underlying tar mechanism sees it as a file,
            // though, and will propagate its mtime, causing the tar header to vary.
            // Avoid this problem by pinning the mtime to zero.
            manifestFile.setLastModified(0);
        }

        // Widget metadata format. All header entries are strings ending in LF:
        //
        // Version 1 header:
        //     BACKUP_METADATA_VERSION, currently "1"
        //     package name
        //
        // File data (all integers are binary in network byte order)
        // *N: 4 : integer token identifying which metadata blob
        //     4 : integer size of this blob = N
        //     N : raw bytes of this metadata blob
        //
        // Currently understood blobs (always in network byte order):
        //
        //     widgets : metadata token = 0x01FFED01 (BACKUP_WIDGET_METADATA_TOKEN)
        //
        // Unrecognized blobs are *ignored*, not errors.
        private void writeMetadata(PackageInfo pkg, File destination, byte[] widgetData)
                throws IOException {
            StringBuilder b = new StringBuilder(512);
            StringBuilderPrinter printer = new StringBuilderPrinter(b);
            printer.println(Integer.toString(BACKUP_METADATA_VERSION));
            printer.println(pkg.packageName);

            FileOutputStream fout = new FileOutputStream(destination);
            BufferedOutputStream bout = new BufferedOutputStream(fout);
            DataOutputStream out = new DataOutputStream(bout);
            bout.write(b.toString().getBytes());    // bypassing DataOutputStream

            if (widgetData != null && widgetData.length > 0) {
                out.writeInt(BACKUP_WIDGET_METADATA_TOKEN);
                out.writeInt(widgetData.length);
                out.write(widgetData);
            }
            bout.flush();
            out.close();

            // As with the manifest file, guarantee idempotence of the archive metadata
            // for the widget block by using a fixed mtime on the transient file.
            destination.setLastModified(0);
        }

        private void tearDown() {
            if (mPkg != null) {
                tearDownAgentAndKill(mPkg.applicationInfo);
            }
        }
    }

    // Generic driver skeleton for full backup operations
    abstract class FullBackupTask implements Runnable {
        IFullBackupRestoreObserver mObserver;

        FullBackupTask(IFullBackupRestoreObserver observer) {
            mObserver = observer;
        }

        // wrappers for observer use
        final void sendStartBackup() {
            if (mObserver != null) {
                try {
                    mObserver.onStartBackup();
                } catch (RemoteException e) {
                    Slog.w(TAG, "full backup observer went away: startBackup");
                    mObserver = null;
                }
            }
        }

        final void sendOnBackupPackage(String name) {
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

        final void sendEndBackup() {
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

    boolean deviceIsEncrypted() {
        try {
            return mMountService.getEncryptionState()
                     != IMountService.ENCRYPTION_STATE_NONE
                && mMountService.getPasswordType()
                     != StorageManager.CRYPT_TYPE_DEFAULT;
        } catch (Exception e) {
            // If we can't talk to the mount service we have a serious problem; fail
            // "secure" i.e. assuming that the device is encrypted.
            Slog.e(TAG, "Unable to communicate with mount service: " + e.getMessage());
            return true;
        }
    }

    // Full backup task variant used for adb backup
    class PerformAdbBackupTask extends FullBackupTask implements BackupRestoreTask {
        FullBackupEngine mBackupEngine;
        final AtomicBoolean mLatch;

        ParcelFileDescriptor mOutputFile;
        DeflaterOutputStream mDeflater;
        boolean mIncludeApks;
        boolean mIncludeObbs;
        boolean mIncludeShared;
        boolean mDoWidgets;
        boolean mAllApps;
        boolean mIncludeSystem;
        boolean mCompress;
        ArrayList<String> mPackages;
        PackageInfo mCurrentTarget;
        String mCurrentPassword;
        String mEncryptPassword;

        PerformAdbBackupTask(ParcelFileDescriptor fd, IFullBackupRestoreObserver observer, 
                boolean includeApks, boolean includeObbs, boolean includeShared,
                boolean doWidgets, String curPassword, String encryptPassword, boolean doAllApps,
                boolean doSystem, boolean doCompress, String[] packages, AtomicBoolean latch) {
            super(observer);
            mLatch = latch;

            mOutputFile = fd;
            mIncludeApks = includeApks;
            mIncludeObbs = includeObbs;
            mIncludeShared = includeShared;
            mDoWidgets = doWidgets;
            mAllApps = doAllApps;
            mIncludeSystem = doSystem;
            mPackages = (packages == null)
                    ? new ArrayList<String>()
                    : new ArrayList<String>(Arrays.asList(packages));
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
            if (MORE_DEBUG) {
                Slog.w(TAG, "Encrypting backup with passphrase=" + mEncryptPassword);
            }
            mCompress = doCompress;
        }

        void addPackagesToSet(TreeMap<String, PackageInfo> set, List<String> pkgNames) {
            for (String pkgName : pkgNames) {
                if (!set.containsKey(pkgName)) {
                    try {
                        PackageInfo info = mPackageManager.getPackageInfo(pkgName,
                                PackageManager.GET_SIGNATURES);
                        set.put(pkgName, info);
                    } catch (NameNotFoundException e) {
                        Slog.w(TAG, "Unknown package " + pkgName + ", skipping");
                    }
                }
            }
        }

        private OutputStream emitAesBackupHeader(StringBuilder headerbuf,
                OutputStream ofstream) throws Exception {
            // User key will be used to encrypt the master key.
            byte[] newUserSalt = randomBytes(PBKDF2_SALT_SIZE);
            SecretKey userKey = buildPasswordKey(PBKDF_CURRENT, mEncryptPassword, newUserSalt,
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
            byte[] checksum = makeKeyChecksum(PBKDF_CURRENT, masterKeySpec.getEncoded(),
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

        private void finalizeBackup(OutputStream out) {
            try {
                // A standard 'tar' EOF sequence: two 512-byte blocks of all zeroes.
                byte[] eof = new byte[512 * 2]; // newly allocated == zero filled
                out.write(eof);
            } catch (IOException e) {
                Slog.w(TAG, "Error attempting to finalize backup stream");
            }
        }

        @Override
        public void run() {
            Slog.i(TAG, "--- Performing full-dataset adb backup ---");

            TreeMap<String, PackageInfo> packagesToBackup = new TreeMap<String, PackageInfo>();
            FullBackupObbConnection obbConnection = new FullBackupObbConnection();
            obbConnection.establish();  // we'll want this later

            sendStartBackup();

            // doAllApps supersedes the package set if any
            if (mAllApps) {
                List<PackageInfo> allPackages = mPackageManager.getInstalledPackages(
                        PackageManager.GET_SIGNATURES);
                for (int i = 0; i < allPackages.size(); i++) {
                    PackageInfo pkg = allPackages.get(i);
                    // Exclude system apps if we've been asked to do so
                    if (mIncludeSystem == true
                            || ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0)) {
                        packagesToBackup.put(pkg.packageName, pkg);
                    }
                }
            }

            // If we're doing widget state as well, ensure that we have all the involved
            // host & provider packages in the set
            if (mDoWidgets) {
                // TODO: http://b/22388012
                List<String> pkgs =
                        AppWidgetBackupBridge.getWidgetParticipants(UserHandle.USER_SYSTEM);
                if (pkgs != null) {
                    if (MORE_DEBUG) {
                        Slog.i(TAG, "Adding widget participants to backup set:");
                        StringBuilder sb = new StringBuilder(128);
                        sb.append("   ");
                        for (String s : pkgs) {
                            sb.append(' ');
                            sb.append(s);
                        }
                        Slog.i(TAG, sb.toString());
                    }
                    addPackagesToSet(packagesToBackup, pkgs);
                }
            }

            // Now process the command line argument packages, if any. Note that explicitly-
            // named system-partition packages will be included even if includeSystem was
            // set to false.
            if (mPackages != null) {
                addPackagesToSet(packagesToBackup, mPackages);
            }

            // Now we cull any inapplicable / inappropriate packages from the set.  This
            // includes the special shared-storage agent package; we handle that one
            // explicitly at the end of the backup pass.
            Iterator<Entry<String, PackageInfo>> iter = packagesToBackup.entrySet().iterator();
            while (iter.hasNext()) {
                PackageInfo pkg = iter.next().getValue();
                if (!appIsEligibleForBackup(pkg.applicationInfo)
                        || appIsStopped(pkg.applicationInfo)
                        || appIsKeyValueOnly(pkg)) {
                    iter.remove();
                }
            }

            // flatten the set of packages now so we can explicitly control the ordering
            ArrayList<PackageInfo> backupQueue =
                    new ArrayList<PackageInfo>(packagesToBackup.values());
            FileOutputStream ofstream = new FileOutputStream(mOutputFile.getFileDescriptor());
            OutputStream out = null;

            PackageInfo pkg = null;
            try {
                boolean encrypting = (mEncryptPassword != null && mEncryptPassword.length() > 0);

                // Only allow encrypted backups of encrypted devices
                if (deviceIsEncrypted() && !encrypting) {
                    Slog.e(TAG, "Unencrypted backup of encrypted device; aborting");
                    return;
                }

                OutputStream finalOutput = ofstream;

                // Verify that the given password matches the currently-active
                // backup password, if any
                if (!backupPasswordMatches(mCurrentPassword)) {
                    if (DEBUG) Slog.w(TAG, "Backup password mismatch; aborting");
                    return;
                }

                // Write the global file header.  All strings are UTF-8 encoded; lines end
                // with a '\n' byte.  Actual backup data begins immediately following the
                // final '\n'.
                //
                // line 1: "ANDROID BACKUP"
                // line 2: backup file format version, currently "2"
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
                headerbuf.append(mCompress ? "\n1\n" : "\n0\n");

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
                    if (mCompress) {
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
                        backupQueue.add(pkg);
                    } catch (NameNotFoundException e) {
                        Slog.e(TAG, "Unable to find shared-storage backup handler");
                    }
                }

                // Now actually run the constructed backup sequence
                int N = backupQueue.size();
                for (int i = 0; i < N; i++) {
                    pkg = backupQueue.get(i);
                    final boolean isSharedStorage =
                            pkg.packageName.equals(SHARED_BACKUP_AGENT_PACKAGE);

                    mBackupEngine = new FullBackupEngine(out, null, pkg, mIncludeApks, this);
                    sendOnBackupPackage(isSharedStorage ? "Shared storage" : pkg.packageName);

                    // Don't need to check preflight result as there is no preflight hook.
                    mCurrentTarget = pkg;
                    mBackupEngine.backupOnePackage();

                    // after the app's agent runs to handle its private filesystem
                    // contents, back up any OBB content it has on its behalf.
                    if (mIncludeObbs) {
                        boolean obbOkay = obbConnection.backupObbs(pkg, out);
                        if (!obbOkay) {
                            throw new RuntimeException("Failure writing OBB stack for " + pkg);
                        }
                    }
                }

                // Done!
                finalizeBackup(out);
            } catch (RemoteException e) {
                Slog.e(TAG, "App died during full backup");
            } catch (Exception e) {
                Slog.e(TAG, "Internal exception during full backup", e);
            } finally {
                try {
                    if (out != null) out.close();
                    mOutputFile.close();
                } catch (IOException e) {
                    /* nothing we can do about this */
                }
                synchronized (mCurrentOpLock) {
                    mCurrentOperations.clear();
                }
                synchronized (mLatch) {
                    mLatch.set(true);
                    mLatch.notifyAll();
                }
                sendEndBackup();
                obbConnection.tearDown();
                if (DEBUG) Slog.d(TAG, "Full backup pass complete.");
                mWakelock.release();
            }
        }

        // BackupRestoreTask methods, used for timeout handling
        @Override
        public void execute() {
            // Unused
        }

        @Override
        public void operationComplete(long result) {
            // Unused
        }

        @Override
        public void handleTimeout() {
            final PackageInfo target = mCurrentTarget;
            if (DEBUG) {
                Slog.w(TAG, "adb backup timeout of " + target);
            }
            if (target != null) {
                tearDownAgentAndKill(mCurrentTarget.applicationInfo);
            }
        }
    }

    // Full backup task extension used for transport-oriented operation
    class PerformFullTransportBackupTask extends FullBackupTask {
        static final String TAG = "PFTBT";
        ArrayList<PackageInfo> mPackages;
        boolean mUpdateSchedule;
        CountDownLatch mLatch;
        AtomicBoolean mKeepRunning;     // signal from job scheduler
        FullBackupJob mJob;             // if a scheduled job needs to be finished afterwards
        IBackupObserver mBackupObserver;
        boolean mUserInitiated;

        PerformFullTransportBackupTask(IFullBackupRestoreObserver observer, 
                String[] whichPackages, boolean updateSchedule,
                FullBackupJob runningJob, CountDownLatch latch, IBackupObserver backupObserver,
                boolean userInitiated) {
            super(observer);
            mUpdateSchedule = updateSchedule;
            mLatch = latch;
            mKeepRunning = new AtomicBoolean(true);
            mJob = runningJob;
            mPackages = new ArrayList<PackageInfo>(whichPackages.length);
            mBackupObserver = backupObserver;
            mUserInitiated = userInitiated;

            for (String pkg : whichPackages) {
                try {
                    PackageInfo info = mPackageManager.getPackageInfo(pkg,
                            PackageManager.GET_SIGNATURES);
                    if (!appIsEligibleForBackup(info.applicationInfo)) {
                        // Cull any packages that have indicated that backups are not permitted,
                        // that run as system-domain uids but do not define their own backup agents,
                        // as well as any explicit mention of the 'special' shared-storage agent
                        // package (we handle that one at the end).
                        if (MORE_DEBUG) {
                            Slog.d(TAG, "Ignoring ineligible package " + pkg);
                        }
                        sendBackupOnPackageResult(mBackupObserver, pkg,
                            BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                        continue;
                    } else if (!appGetsFullBackup(info)) {
                        // Cull any packages that are found in the queue but now aren't supposed
                        // to get full-data backup operations.
                        if (MORE_DEBUG) {
                            Slog.d(TAG, "Ignoring full-data backup of key/value participant "
                                    + pkg);
                        }
                        sendBackupOnPackageResult(mBackupObserver, pkg,
                                BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                        continue;
                    } else if (appIsStopped(info.applicationInfo)) {
                        // Cull any packages in the 'stopped' state: they've either just been
                        // installed or have explicitly been force-stopped by the user.  In both
                        // cases we do not want to launch them for backup.
                        if (MORE_DEBUG) {
                            Slog.d(TAG, "Ignoring stopped package " + pkg);
                        }
                        sendBackupOnPackageResult(mBackupObserver, pkg,
                                BackupManager.ERROR_BACKUP_NOT_ALLOWED);
                        continue;
                    }
                    mPackages.add(info);
                } catch (NameNotFoundException e) {
                    Slog.i(TAG, "Requested package " + pkg + " not found; ignoring");
                }
            }
        }

        public void setRunning(boolean running) {
            mKeepRunning.set(running);
        }

        @Override
        public void run() {
            // data from the app, passed to us for bridging to the transport
            ParcelFileDescriptor[] enginePipes = null;

            // Pipe through which we write data to the transport
            ParcelFileDescriptor[] transportPipes = null;

            long backoff = 0;
            int backupRunStatus = BackupManager.SUCCESS;

            try {
                if (!mEnabled || !mProvisioned) {
                    // Backups are globally disabled, so don't proceed.
                    if (DEBUG) {
                        Slog.i(TAG, "full backup requested but e=" + mEnabled
                                + " p=" + mProvisioned + "; ignoring");
                    }
                    mUpdateSchedule = false;
                    backupRunStatus = BackupManager.ERROR_BACKUP_NOT_ALLOWED;
                    return;
                }

                IBackupTransport transport = getTransport(mCurrentTransport);
                if (transport == null) {
                    Slog.w(TAG, "Transport not present; full data backup not performed");
                    backupRunStatus = BackupManager.ERROR_TRANSPORT_ABORTED;
                    return;
                }

                // Set up to send data to the transport
                final int N = mPackages.size();
                final byte[] buffer = new byte[8192];
                for (int i = 0; i < N; i++) {
                    PackageInfo currentPackage = mPackages.get(i);
                    String packageName = currentPackage.packageName;
                    if (DEBUG) {
                        Slog.i(TAG, "Initiating full-data transport backup of " + packageName);
                    }
                    EventLog.writeEvent(EventLogTags.FULL_BACKUP_PACKAGE, packageName);

                    transportPipes = ParcelFileDescriptor.createPipe();

                    // Tell the transport the data's coming
                    int flags = mUserInitiated ? BackupTransport.FLAG_USER_INITIATED : 0;
                    int backupPackageStatus = transport.performFullBackup(currentPackage,
                            transportPipes[0], flags);
                    if (backupPackageStatus == BackupTransport.TRANSPORT_OK) {
                        // The transport has its own copy of the read end of the pipe,
                        // so close ours now
                        transportPipes[0].close();
                        transportPipes[0] = null;

                        // Now set up the backup engine / data source end of things
                        enginePipes = ParcelFileDescriptor.createPipe();
                        SinglePackageBackupRunner backupRunner =
                                new SinglePackageBackupRunner(enginePipes[1], currentPackage,
                                        transport);
                        // The runner dup'd the pipe half, so we close it here
                        enginePipes[1].close();
                        enginePipes[1] = null;

                        // Spin off the runner to fetch the app's data and pipe it
                        // into the engine pipes
                        (new Thread(backupRunner, "package-backup-bridge")).start();

                        // Read data off the engine pipe and pass it to the transport
                        // pipe until we hit EOD on the input stream.  We do not take
                        // close() responsibility for these FDs into these stream wrappers.
                        FileInputStream in = new FileInputStream(
                                enginePipes[0].getFileDescriptor());
                        FileOutputStream out = new FileOutputStream(
                                transportPipes[1].getFileDescriptor());
                        long totalRead = 0;
                        final long preflightResult = backupRunner.getPreflightResultBlocking();
                        // Preflight result is negative if some error happened on preflight.
                        if (preflightResult < 0) {
                            if (MORE_DEBUG) {
                                Slog.d(TAG, "Backup error after preflight of package "
                                        + packageName + ": " + preflightResult
                                        + ", not running backup.");
                            }
                            backupPackageStatus = (int) preflightResult;
                        } else {
                            int nRead = 0;
                            do {
                                if (!mKeepRunning.get()) {
                                    if (DEBUG_SCHEDULING) {
                                        Slog.i(TAG, "Full backup task told to stop");
                                    }
                                    break;
                                }
                                nRead = in.read(buffer);
                                if (MORE_DEBUG) {
                                    Slog.v(TAG, "in.read(buffer) from app: " + nRead);
                                }
                                if (nRead > 0) {
                                    out.write(buffer, 0, nRead);
                                    backupPackageStatus = transport.sendBackupData(nRead);
                                    totalRead += nRead;
                                    if (mBackupObserver != null && preflightResult > 0) {
                                        sendBackupOnUpdate(mBackupObserver, packageName,
                                                new BackupProgress(preflightResult, totalRead));
                                    }
                                }
                            } while (nRead > 0
                                    && backupPackageStatus == BackupTransport.TRANSPORT_OK);

                            // Despite preflight succeeded, package still can hit quota on flight.
                            if (backupPackageStatus == BackupTransport.TRANSPORT_QUOTA_EXCEEDED) {
                                long quota = transport.getBackupQuota(packageName, true);
                                Slog.w(TAG, "Package hit quota limit in-flight " + packageName
                                        + ": " + totalRead + " of " + quota);
                                backupRunner.sendQuotaExceeded(totalRead, quota);
                            }
                        }

                        // If we've lost our running criteria, tell the transport to cancel
                        // and roll back this (partial) backup payload; otherwise tell it
                        // that we've reached the clean finish state.
                        if (!mKeepRunning.get()) {
                            backupPackageStatus = BackupTransport.TRANSPORT_ERROR;
                            transport.cancelFullBackup();
                        } else {
                            // If we were otherwise in a good state, now interpret the final
                            // result based on what finishBackup() returns.  If we're in a
                            // failure case already, preserve that result and ignore whatever
                            // finishBackup() reports.
                            final int finishResult = transport.finishBackup();
                            if (backupPackageStatus == BackupTransport.TRANSPORT_OK) {
                                backupPackageStatus = finishResult;
                            }
                        }

                        // A transport-originated error here means that we've hit an error that the
                        // runner doesn't know about, so it's still moving data but we're pulling the
                        // rug out from under it.  Don't ask for its result:  we already know better
                        // and we'll hang if we block waiting for it, since it relies on us to
                        // read back the data it's writing into the engine.  Just proceed with
                        // a graceful failure.  The runner/engine mechanism will tear itself
                        // down cleanly when we close the pipes from this end.  Transport-level
                        // errors take precedence over agent/app-specific errors for purposes of
                        // determining our course of action.
                        if (backupPackageStatus == BackupTransport.TRANSPORT_OK) {
                            // We still could fail in backup runner thread, getting result from there.
                            int backupRunnerResult = backupRunner.getBackupResultBlocking();
                            if (backupRunnerResult != BackupTransport.TRANSPORT_OK) {
                                // If there was an error in runner thread and
                                // not TRANSPORT_ERROR here, overwrite it.
                                backupPackageStatus = backupRunnerResult;
                            }
                        } else {
                            if (MORE_DEBUG) {
                                Slog.i(TAG, "Transport-level failure; cancelling agent work");
                            }
                        }

                        if (MORE_DEBUG) {
                            Slog.i(TAG, "Done delivering backup data: result="
                                    + backupPackageStatus);
                        }

                        if (backupPackageStatus != BackupTransport.TRANSPORT_OK) {
                            Slog.e(TAG, "Error " + backupPackageStatus + " backing up "
                                    + packageName);
                        }

                        // Also ask the transport how long it wants us to wait before
                        // moving on to the next package, if any.
                        backoff = transport.requestFullBackupTime();
                        if (DEBUG_SCHEDULING) {
                            Slog.i(TAG, "Transport suggested backoff=" + backoff);
                        }

                    }

                    // Roll this package to the end of the backup queue if we're
                    // in a queue-driven mode (regardless of success/failure)
                    if (mUpdateSchedule) {
                        enqueueFullBackup(packageName, System.currentTimeMillis());
                    }

                    if (backupPackageStatus == BackupTransport.TRANSPORT_PACKAGE_REJECTED) {
                        sendBackupOnPackageResult(mBackupObserver, packageName,
                                BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED);
                        if (DEBUG) {
                            Slog.i(TAG, "Transport rejected backup of " + packageName
                                    + ", skipping");
                        }
                        EventLog.writeEvent(EventLogTags.FULL_BACKUP_AGENT_FAILURE, packageName,
                                "transport rejected");
                        // Do nothing, clean up, and continue looping.
                    } else if (backupPackageStatus == BackupTransport.TRANSPORT_QUOTA_EXCEEDED) {
                        sendBackupOnPackageResult(mBackupObserver, packageName,
                                BackupManager.ERROR_TRANSPORT_QUOTA_EXCEEDED);
                        if (DEBUG) {
                            Slog.i(TAG, "Transport quota exceeded for package: " + packageName);
                            EventLog.writeEvent(EventLogTags.FULL_BACKUP_QUOTA_EXCEEDED,
                                    packageName);
                        }
                        // Do nothing, clean up, and continue looping.
                    } else if (backupPackageStatus == BackupTransport.AGENT_ERROR) {
                        sendBackupOnPackageResult(mBackupObserver, packageName,
                                BackupManager.ERROR_AGENT_FAILURE);
                        Slog.w(TAG, "Application failure for package: " + packageName);
                        EventLog.writeEvent(EventLogTags.BACKUP_AGENT_FAILURE, packageName);
                        tearDownAgentAndKill(currentPackage.applicationInfo);
                        // Do nothing, clean up, and continue looping.
                    } else if (backupPackageStatus != BackupTransport.TRANSPORT_OK) {
                        sendBackupOnPackageResult(mBackupObserver, packageName,
                            BackupManager.ERROR_TRANSPORT_ABORTED);
                        Slog.w(TAG, "Transport failed; aborting backup: " + backupPackageStatus);
                        EventLog.writeEvent(EventLogTags.FULL_BACKUP_TRANSPORT_FAILURE);
                        // Abort entire backup pass.
                        backupRunStatus = BackupManager.ERROR_TRANSPORT_ABORTED;
                        return;
                    } else {
                        // Success!
                        sendBackupOnPackageResult(mBackupObserver, packageName,
                                BackupManager.SUCCESS);
                        EventLog.writeEvent(EventLogTags.FULL_BACKUP_SUCCESS, packageName);
                        logBackupComplete(packageName);
                    }
                    cleanUpPipes(transportPipes);
                    cleanUpPipes(enginePipes);
                }
            } catch (Exception e) {
                backupRunStatus = BackupManager.ERROR_TRANSPORT_ABORTED;
                Slog.w(TAG, "Exception trying full transport backup", e);
            } finally {
                if (DEBUG) {
                    Slog.i(TAG, "Full backup completed with status: " + backupRunStatus);
                }
                sendBackupFinished(mBackupObserver, backupRunStatus);

                cleanUpPipes(transportPipes);
                cleanUpPipes(enginePipes);

                if (mJob != null) {
                    mJob.finishBackupPass();
                }

                synchronized (mQueueLock) {
                    mRunningFullBackupTask = null;
                }

                mLatch.countDown();

                // Now that we're actually done with schedule-driven work, reschedule
                // the next pass based on the new queue state.
                if (mUpdateSchedule) {
                    scheduleNextFullBackupJob(backoff);
                }
                Slog.i(BackupManagerService.TAG, "Full data backup pass finished.");
                mWakelock.release();
            }
        }

        void cleanUpPipes(ParcelFileDescriptor[] pipes) {
            if (pipes != null) {
                if (pipes[0] != null) {
                    ParcelFileDescriptor fd = pipes[0];
                    pipes[0] = null;
                    try {
                        fd.close();
                    } catch (IOException e) {
                        Slog.w(TAG, "Unable to close pipe!");
                    }
                }
                if (pipes[1] != null) {
                    ParcelFileDescriptor fd = pipes[1];
                    pipes[1] = null;
                    try {
                        fd.close();
                    } catch (IOException e) {
                        Slog.w(TAG, "Unable to close pipe!");
                    }
                }
            }
        }

        // Run the backup and pipe it back to the given socket -- expects to run on
        // a standalone thread.  The  runner owns this half of the pipe, and closes
        // it to indicate EOD to the other end.
        class SinglePackageBackupPreflight implements BackupRestoreTask, FullBackupPreflight {
            final AtomicLong mResult = new AtomicLong(BackupTransport.AGENT_ERROR);
            final CountDownLatch mLatch = new CountDownLatch(1);
            final IBackupTransport mTransport;

            public SinglePackageBackupPreflight(IBackupTransport transport) {
                mTransport = transport;
            }

            @Override
            public int preflightFullBackup(PackageInfo pkg, IBackupAgent agent) {
                int result;
                try {
                    final int token = generateToken();
                    prepareOperationTimeout(token, TIMEOUT_FULL_BACKUP_INTERVAL, this);
                    addBackupTrace("preflighting");
                    if (MORE_DEBUG) {
                        Slog.d(TAG, "Preflighting full payload of " + pkg.packageName);
                    }
                    agent.doMeasureFullBackup(token, mBackupManagerBinder);

                    // Now wait to get our result back.  If this backstop timeout is reached without
                    // the latch being thrown, flow will continue as though a result or "normal"
                    // timeout had been produced.  In case of a real backstop timeout, mResult
                    // will still contain the value it was constructed with, AGENT_ERROR, which
                    // intentionaly falls into the "just report failure" code.
                    mLatch.await(TIMEOUT_FULL_BACKUP_INTERVAL, TimeUnit.MILLISECONDS);

                    long totalSize = mResult.get();
                    // If preflight timed out, mResult will contain error code as int.
                    if (totalSize < 0) {
                        return (int) totalSize;
                    }
                    if (MORE_DEBUG) {
                        Slog.v(TAG, "Got preflight response; size=" + totalSize);
                    }

                    result = mTransport.checkFullBackupSize(totalSize);
                    if (result == BackupTransport.TRANSPORT_QUOTA_EXCEEDED) {
                        final long quota = mTransport.getBackupQuota(pkg.packageName, true);
                        if (MORE_DEBUG) {
                            Slog.d(TAG, "Package hit quota limit on preflight " +
                                    pkg.packageName + ": " + totalSize + " of " + quota);
                        }
                        agent.doQuotaExceeded(totalSize, quota);
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Exception preflighting " + pkg.packageName + ": " + e.getMessage());
                    result = BackupTransport.AGENT_ERROR;
                }
                return result;
            }

            @Override
            public void execute() {
                // Unused in this case
            }

            @Override
            public void operationComplete(long result) {
                // got the callback, and our preflightFullBackup() method is waiting for the result
                if (MORE_DEBUG) {
                    Slog.i(TAG, "Preflight op complete, result=" + result);
                }
                mResult.set(result);
                mLatch.countDown();
            }

            @Override
            public void handleTimeout() {
                if (MORE_DEBUG) {
                    Slog.i(TAG, "Preflight timeout; failing");
                }
                mResult.set(BackupTransport.AGENT_ERROR);
                mLatch.countDown();
            }

            @Override
            public long getExpectedSizeOrErrorCode() {
                try {
                    mLatch.await(TIMEOUT_FULL_BACKUP_INTERVAL, TimeUnit.MILLISECONDS);
                    return mResult.get();
                } catch (InterruptedException e) {
                    return BackupTransport.NO_MORE_DATA;
                }
            }
        }

        class SinglePackageBackupRunner implements Runnable, BackupRestoreTask {
            final ParcelFileDescriptor mOutput;
            final PackageInfo mTarget;
            final FullBackupPreflight mPreflight;
            final CountDownLatch mPreflightLatch;
            final CountDownLatch mBackupLatch;
            private FullBackupEngine mEngine;
            private volatile int mPreflightResult;
            private volatile int mBackupResult;

            SinglePackageBackupRunner(ParcelFileDescriptor output, PackageInfo target,
                    IBackupTransport transport) throws IOException {
                mOutput = ParcelFileDescriptor.dup(output.getFileDescriptor());
                mTarget = target;
                mPreflight = new SinglePackageBackupPreflight(transport);
                mPreflightLatch = new CountDownLatch(1);
                mBackupLatch = new CountDownLatch(1);
                mPreflightResult = BackupTransport.AGENT_ERROR;
                mBackupResult = BackupTransport.AGENT_ERROR;
            }

            @Override
            public void run() {
                FileOutputStream out = new FileOutputStream(mOutput.getFileDescriptor());
                mEngine = new FullBackupEngine(out, mPreflight, mTarget, false, this);
                try {
                    try {
                        mPreflightResult = mEngine.preflightCheck();
                    } finally {
                        mPreflightLatch.countDown();
                    }
                    // If there is no error on preflight, continue backup.
                    if (mPreflightResult == BackupTransport.TRANSPORT_OK) {
                        mBackupResult = mEngine.backupOnePackage();
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Exception during full package backup of " + mTarget.packageName);
                } finally {
                    mBackupLatch.countDown();
                    try {
                        mOutput.close();
                    } catch (IOException e) {
                        Slog.w(TAG, "Error closing transport pipe in runner");
                    }
                }
            }

            public void sendQuotaExceeded(final long backupDataBytes, final long quotaBytes) {
                mEngine.sendQuotaExceeded(backupDataBytes, quotaBytes);
            }

            // If preflight succeeded, returns positive number - preflight size,
            // otherwise return negative error code.
            long getPreflightResultBlocking() {
                try {
                    mPreflightLatch.await(TIMEOUT_FULL_BACKUP_INTERVAL, TimeUnit.MILLISECONDS);
                    if (mPreflightResult == BackupTransport.TRANSPORT_OK) {
                        return mPreflight.getExpectedSizeOrErrorCode();
                    } else {
                        return mPreflightResult;
                    }
                } catch (InterruptedException e) {
                    return BackupTransport.AGENT_ERROR;
                }
            }

            int getBackupResultBlocking() {
                try {
                    mBackupLatch.await(TIMEOUT_FULL_BACKUP_INTERVAL, TimeUnit.MILLISECONDS);
                    return mBackupResult;
                } catch (InterruptedException e) {
                    return BackupTransport.AGENT_ERROR;
                }
            }


            // BackupRestoreTask interface: specifically, timeout detection

            @Override
            public void execute() { /* intentionally empty */ }

            @Override
            public void operationComplete(long result) { /* intentionally empty */ }

            @Override
            public void handleTimeout() {
                if (DEBUG) {
                    Slog.w(TAG, "Full backup timeout of " + mTarget.packageName);
                }
                tearDownAgentAndKill(mTarget.applicationInfo);
            }
        }
    }

    // ----- Full-data backup scheduling -----

    /**
     * Schedule a job to tell us when it's a good time to run a full backup
     */
    void scheduleNextFullBackupJob(long transportMinLatency) {
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
                    @Override public void run() {
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
    void dequeueFullBackupLocked(String packageName) {
        final int N = mFullBackupQueue.size();
        for (int i = N-1; i >= 0; i--) {
            final FullBackupEntry e = mFullBackupQueue.get(i);
            if (packageName.equals(e.packageName)) {
                mFullBackupQueue.remove(i);
            }
        }
    }

    /**
     * Enqueue full backup for the given app, with a note about when it last ran.
     */
    void enqueueFullBackup(String packageName, long lastBackedUp) {
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
            Slog.w(TAG, "Unable to contact transport");
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
    boolean beginFullBackup(FullBackupJob scheduledJob) {
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
        if (mPowerManager.isPowerSaveMode()) {
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

                if (!fullBackupAllowable(getTransport(mCurrentTransport))) {
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
                        if (!appGetsFullBackup(appInfo)) {
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
                    @Override public void run() {
                        FullBackupJob.schedule(mContext, deferTime);
                    }
                });
                return false;
            }

            // Okay, the top thing is ready for backup now.  Do it.
            mFullBackupQueue.remove(0);
            CountDownLatch latch = new CountDownLatch(1);
            String[] pkg = new String[] {entry.packageName};
            mRunningFullBackupTask = new PerformFullTransportBackupTask(null, pkg, true,
                    scheduledJob, latch, null, false /* userInitiated */);
            // Acquiring wakelock for PerformFullTransportBackupTask before its start.
            mWakelock.acquire();
            (new Thread(mRunningFullBackupTask)).start();
        }

        return true;
    }

    // The job scheduler says our constraints don't hold any more,
    // so tear down any ongoing backup task right away.
    void endFullBackup() {
        synchronized (mQueueLock) {
            if (mRunningFullBackupTask != null) {
                if (DEBUG_SCHEDULING) {
                    Slog.i(TAG, "Telling running backup to stop");
                }
                mRunningFullBackupTask.setRunning(false);
            }
        }
    }

    // ----- Restore infrastructure -----

    abstract class RestoreEngine {
        static final String TAG = "RestoreEngine";

        public static final int SUCCESS = 0;
        public static final int TARGET_FAILURE = -2;
        public static final int TRANSPORT_FAILURE = -3;

        private AtomicBoolean mRunning = new AtomicBoolean(false);
        private AtomicInteger mResult = new AtomicInteger(SUCCESS);

        public boolean isRunning() {
            return mRunning.get();
        }

        public void setRunning(boolean stillRunning) {
            synchronized (mRunning) {
                mRunning.set(stillRunning);
                mRunning.notifyAll();
            }
        }

        public int waitForResult() {
            synchronized (mRunning) {
                while (isRunning()) {
                    try {
                        mRunning.wait();
                    } catch (InterruptedException e) {}
                }
            }
            return getResult();
        }

        public int getResult() {
            return mResult.get();
        }

        public void setResult(int result) {
            mResult.set(result);
        }

        // TODO: abstract restore state and APIs
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

    // Full restore engine, used by both adb restore and transport-based full restore
    class FullRestoreEngine extends RestoreEngine {
        // Task in charge of monitoring timeouts
        BackupRestoreTask mMonitorTask;

        // Dedicated observer, if any
        IFullBackupRestoreObserver mObserver;

        // Where we're delivering the file data as we go
        IBackupAgent mAgent;

        // Are we permitted to only deliver a specific package's metadata?
        PackageInfo mOnlyPackage;

        boolean mAllowApks;
        boolean mAllowObbs;

        // Which package are we currently handling data for?
        String mAgentPackage;

        // Info for working with the target app process
        ApplicationInfo mTargetApp;

        // Machinery for restoring OBBs
        FullBackupObbConnection mObbConnection = null;

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

        // How much data have we moved?
        long mBytes;

        // Working buffer
        byte[] mBuffer;

        // Pipes for moving data
        ParcelFileDescriptor[] mPipes = null;

        // Widget blob to be restored out-of-band
        byte[] mWidgetData = null;

        // Runner that can be placed in a separate thread to do in-process
        // invocations of the full restore API asynchronously. Used by adb restore.
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

        public FullRestoreEngine(BackupRestoreTask monitorTask, IFullBackupRestoreObserver observer,
                PackageInfo onlyPackage, boolean allowApks, boolean allowObbs) {
            mMonitorTask = monitorTask;
            mObserver = observer;
            mOnlyPackage = onlyPackage;
            mAllowApks = allowApks;
            mAllowObbs = allowObbs;
            mBuffer = new byte[32 * 1024];
            mBytes = 0;
        }

        public IBackupAgent getAgent() {
            return mAgent;
        }

        public byte[] getWidgetData() {
            return mWidgetData;
        }

        public boolean restoreOneFile(InputStream instream, boolean mustKillAgent) {
            if (!isRunning()) {
                Slog.w(TAG, "Restore engine used after halting");
                return false;
            }

            FileMetadata info;
            try {
                if (MORE_DEBUG) {
                    Slog.v(TAG, "Reading tar header for restoring file");
                }
                info = readTarHeaders(instream);
                if (info != null) {
                    if (MORE_DEBUG) {
                        dumpFileMetadata(info);
                    }

                    final String pkg = info.packageName;
                    if (!pkg.equals(mAgentPackage)) {
                        // In the single-package case, it's a semantic error to expect
                        // one app's data but see a different app's on the wire
                        if (mOnlyPackage != null) {
                            if (!pkg.equals(mOnlyPackage.packageName)) {
                                Slog.w(TAG, "Expected data for " + mOnlyPackage
                                        + " but saw " + pkg);
                                setResult(RestoreEngine.TRANSPORT_FAILURE);
                                setRunning(false);
                                return false;
                            }
                        }

                        // okay, change in package; set up our various
                        // bookkeeping if we haven't seen it yet
                        if (!mPackagePolicies.containsKey(pkg)) {
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                        }

                        // Clean up the previous agent relationship if necessary,
                        // and let the observer know we're considering a new app.
                        if (mAgent != null) {
                            if (DEBUG) Slog.d(TAG, "Saw new package; finalizing old one");
                            // Now we're really done
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
                    } else if (info.path.equals(BACKUP_METADATA_FILENAME)) {
                        // Metadata blobs!
                        readMetadata(info, instream);
                        skipTarPadding(info.size, instream);
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

                        // Is it a *file* we need to drop?
                        if (!isRestorableFile(info)) {
                            okay = false;
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
                                        if (MORE_DEBUG) Slog.d(TAG, "backup agent ("
                                                + mTargetApp.backupAgentName + ") => no clear");
                                    }
                                    mClearedPackages.add(pkg);
                                } else {
                                    if (MORE_DEBUG) {
                                        Slog.d(TAG, "We've initialized this app already; no clear required");
                                    }
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
                                Slog.e(TAG, "Unable to create agent for " + pkg);
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
                                prepareOperationTimeout(token, TIMEOUT_FULL_BACKUP_INTERVAL,
                                        mMonitorTask);

                                if (info.domain.equals(FullBackup.OBB_TREE_TOKEN)) {
                                    if (DEBUG) Slog.d(TAG, "Restoring OBB file for " + pkg
                                            + " : " + info.path);
                                    mObbConnection.restoreObbFile(pkg, mPipes[0],
                                            info.size, info.type, info.path, info.mode,
                                            info.mtime, token, mBackupManagerBinder);
                                } else {
                                    if (MORE_DEBUG) Slog.d(TAG, "Invoking agent to restore file "
                                            + info.path);
                                    // fire up the app's agent listening on the socket.  If
                                    // the agent is running in the system process we can't
                                    // just invoke it asynchronously, so we provide a thread
                                    // for it here.
                                    if (mTargetApp.processName.equals("system")) {
                                        Slog.d(TAG, "system process agent - spinning a thread");
                                        RestoreFileRunnable runner = new RestoreFileRunnable(
                                                mAgent, info, mPipes[0], token);
                                        new Thread(runner, "restore-sys-runner").start();
                                    } else {
                                        mAgent.doRestoreFile(mPipes[0], info.size, info.type,
                                                info.domain, info.path, info.mode, info.mtime,
                                                token, mBackupManagerBinder);
                                    }
                                }
                            } catch (IOException e) {
                                // couldn't dup the socket for a process-local restore
                                Slog.d(TAG, "Couldn't establish restore");
                                agentSuccess = false;
                                okay = false;
                            } catch (RemoteException e) {
                                // whoops, remote entity went away.  We'll eat the content
                                // ourselves, then, and not copy it over.
                                Slog.e(TAG, "Agent crashed during full restore");
                                agentSuccess = false;
                                okay = false;
                            }

                            // Copy over the data if the agent is still good
                            if (okay) {
                                if (MORE_DEBUG) {
                                    Slog.v(TAG, "  copying to restore agent: "
                                            + toCopy + " bytes");
                                }
                                boolean pipeOkay = true;
                                FileOutputStream pipe = new FileOutputStream(
                                        mPipes[1].getFileDescriptor());
                                while (toCopy > 0) {
                                    int toRead = (toCopy > mBuffer.length)
                                            ? mBuffer.length : (int)toCopy;
                                    int nRead = instream.read(mBuffer, 0, toRead);
                                    if (nRead >= 0) mBytes += nRead;
                                    if (nRead <= 0) break;
                                    toCopy -= nRead;

                                    // send it to the output pipe as long as things
                                    // are still good
                                    if (pipeOkay) {
                                        try {
                                            pipe.write(mBuffer, 0, nRead);
                                        } catch (IOException e) {
                                            Slog.e(TAG, "Failed to write to restore pipe: "
                                                    + e.getMessage());
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
                                Slog.w(TAG, "Agent failure; ending restore");
                                mBackupHandler.removeMessages(MSG_TIMEOUT);
                                tearDownPipes();
                                tearDownAgent(mTargetApp);
                                mAgent = null;
                                mPackagePolicies.put(pkg, RestorePolicy.IGNORE);

                                // If this was a single-package restore, we halt immediately
                                // with an agent error under these circumstances
                                if (mOnlyPackage != null) {
                                    setResult(RestoreEngine.TARGET_FAILURE);
                                    setRunning(false);
                                    return false;
                                }
                            }
                        }

                        // Problems setting up the agent communication, an explicitly
                        // dropped file, or an already-ignored package: skip to the
                        // next stream entry by reading and discarding this file.
                        if (!okay) {
                            if (MORE_DEBUG) Slog.d(TAG, "[discarding file content]");
                            long bytesToConsume = (info.size + 511) & ~511;
                            while (bytesToConsume > 0) {
                                int toRead = (bytesToConsume > mBuffer.length)
                                        ? mBuffer.length : (int)bytesToConsume;
                                long nRead = instream.read(mBuffer, 0, toRead);
                                if (nRead >= 0) mBytes += nRead;
                                if (nRead <= 0) break;
                                bytesToConsume -= nRead;
                            }
                        }
                    }
                }
            } catch (IOException e) {
                if (DEBUG) Slog.w(TAG, "io exception on restore socket read: " + e.getMessage());
                setResult(RestoreEngine.TRANSPORT_FAILURE);
                info = null;
            }

            // If we got here we're either running smoothly or we've finished
            if (info == null) {
                if (MORE_DEBUG) {
                    Slog.i(TAG, "No [more] data for this package; tearing down");
                }
                tearDownPipes();
                setRunning(false);
                if (mustKillAgent) {
                    tearDownAgent(mTargetApp);
                }
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
                tearDownAgentAndKill(app);
                mAgent = null;
            }
        }

        void handleTimeout() {
            tearDownPipes();
            setResult(RestoreEngine.TARGET_FAILURE);
            setRunning(false);
        }

        class RestoreInstallObserver extends PackageInstallObserver {
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
            public void onPackageInstalled(String packageName, int returnCode,
                    String msg, Bundle extras) {
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
                                if (signaturesMatch(sigs, pkg)) {
                                    // If this is a system-uid app without a declared backup agent,
                                    // don't restore any of the file data.
                                    if ((pkg.applicationInfo.uid < Process.FIRST_APPLICATION_UID)
                                            && (pkg.applicationInfo.backupAgentName == null)) {
                                        Slog.w(TAG, "Installed app " + info.packageName
                                                + " has restricted uid and no agent");
                                        okay = false;
                                    }
                                } else {
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
                if (MORE_DEBUG) {
                    Slog.i(TAG, "Skipping tar padding: " + needed + " bytes");
                }
                byte[] buffer = new byte[needed];
                if (readExactly(instream, buffer, 0, needed) == needed) {
                    mBytes += needed;
                } else throw new IOException("Unexpected EOF in padding");
            }
        }

        // Read a widget metadata file, returning the restored blob
        void readMetadata(FileMetadata info, InputStream instream) throws IOException {
            // Fail on suspiciously large widget dump files
            if (info.size > 64 * 1024) {
                throw new IOException("Metadata too big; corrupt? size=" + info.size);
            }

            byte[] buffer = new byte[(int) info.size];
            if (readExactly(instream, buffer, 0, (int)info.size) == info.size) {
                mBytes += info.size;
            } else throw new IOException("Unexpected EOF in widget data");

            String[] str = new String[1];
            int offset = extractLine(buffer, 0, str);
            int version = Integer.parseInt(str[0]);
            if (version == BACKUP_MANIFEST_VERSION) {
                offset = extractLine(buffer, offset, str);
                final String pkg = str[0];
                if (info.packageName.equals(pkg)) {
                    // Data checks out -- the rest of the buffer is a concatenation of
                    // binary blobs as described in the comment at writeAppWidgetData()
                    ByteArrayInputStream bin = new ByteArrayInputStream(buffer,
                            offset, buffer.length - offset);
                    DataInputStream in = new DataInputStream(bin);
                    while (bin.available() > 0) {
                        int token = in.readInt();
                        int size = in.readInt();
                        if (size > 64 * 1024) {
                            throw new IOException("Datum "
                                    + Integer.toHexString(token)
                                    + " too big; corrupt? size=" + info.size);
                        }
                        switch (token) {
                            case BACKUP_WIDGET_METADATA_TOKEN:
                            {
                                if (MORE_DEBUG) {
                                    Slog.i(TAG, "Got widget metadata for " + info.packageName);
                                }
                                mWidgetData = new byte[size];
                                in.read(mWidgetData);
                                break;
                            }
                            default:
                            {
                                if (DEBUG) {
                                    Slog.i(TAG, "Ignoring metadata blob "
                                            + Integer.toHexString(token)
                                            + " for " + info.packageName);
                                }
                                in.skipBytes(size);
                                break;
                            }
                        }
                    }
                } else {
                    Slog.w(TAG, "Metadata mismatch: package " + info.packageName
                            + " but widget data for " + pkg);
                }
            } else {
                Slog.w(TAG, "Unsupported metadata version " + version);
            }
        }

        // Returns a policy constant
        RestorePolicy readAppManifest(FileMetadata info, InputStream instream)
                throws IOException {
            // Fail on suspiciously large manifest files
            if (info.size > 64 * 1024) {
                throw new IOException("Restore manifest too big; corrupt? size=" + info.size);
            }

            byte[] buffer = new byte[(int) info.size];
            if (MORE_DEBUG) {
                Slog.i(TAG, "   readAppManifest() looking for " + info.size + " bytes, "
                        + mBytes + " already consumed");
            }
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
                        // This is the platform version, which we don't use, but we parse it
                        // as a safety against corruption in the manifest.
                        Integer.parseInt(str[0]);
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
                                                if (mAllowApks) {
                                                    Slog.i(TAG, "Data version " + version
                                                            + " is newer than installed version "
                                                            + pkgInfo.versionCode
                                                            + " - requiring apk");
                                                    policy = RestorePolicy.ACCEPT_IF_APK;
                                                } else {
                                                    Slog.i(TAG, "Data requires newer version "
                                                            + version + "; ignoring");
                                                    policy = RestorePolicy.IGNORE;
                                                }
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
                                if (mAllowApks) {
                                    if (DEBUG) Slog.i(TAG, "Package " + info.packageName
                                            + " not installed; requiring apk in dataset");
                                    policy = RestorePolicy.ACCEPT_IF_APK;
                                } else {
                                    policy = RestorePolicy.IGNORE;
                                }
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
            if (MORE_DEBUG) {
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
                b.append(new SimpleDateFormat("MMM dd HH:mm:ss ").format(stamp));

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
                            if (MORE_DEBUG) Slog.w(TAG, "Saw type=0 in tar header block, info=" + info);
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

                        // if it's a manifest or metadata payload we're done, otherwise parse
                        // out the domain into which the file will be restored
                        if (!info.path.equals(BACKUP_MANIFEST_FILENAME)
                                && !info.path.equals(BACKUP_METADATA_FILENAME)) {
                            slash = info.path.indexOf('/');
                            if (slash < 0) {
                                throw new IOException("Illegal semantic path in non-manifest "
                                        + info.path);
                            }
                            info.domain = info.path.substring(0, slash);
                            info.path = info.path.substring(slash + 1);
                        }
                    }
                } catch (IOException e) {
                    if (DEBUG) {
                        Slog.e(TAG, "Parse error in header: " + e.getMessage());
                        if (MORE_DEBUG) {
                            HEXLOG(block);
                        }
                    }
                    throw e;
                }
            }
            return info;
        }

        private boolean isRestorableFile(FileMetadata info) {
            if (FullBackup.CACHE_TREE_TOKEN.equals(info.domain)) {
                if (MORE_DEBUG) {
                    Slog.i(TAG, "Dropping cache file path " + info.path);
                }
                return false;
            }

            if (FullBackup.ROOT_TREE_TOKEN.equals(info.domain)) {
                // It's possible this is "no-backup" dir contents in an archive stream
                // produced on a device running a version of the OS that predates that
                // API.  Respect the no-backup intention and don't let the data get to
                // the app.
                if (info.path.startsWith("no_backup/")) {
                    if (MORE_DEBUG) {
                        Slog.i(TAG, "Dropping no_backup file path " + info.path);
                    }
                    return false;
                }
            }

            // The path needs to be canonical
            if (info.path.contains("..") || info.path.contains("//")) {
                if (MORE_DEBUG) {
                    Slog.w(TAG, "Dropping invalid path " + info.path);
                }
                return false;
            }

            // Otherwise we think this file is good to go
            return true;
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
if (MORE_DEBUG) Slog.i(TAG, "  ... readExactly(" + size + ") called");
            int soFar = 0;
            while (soFar < size) {
                int nRead = in.read(buffer, offset + soFar, size - soFar);
                if (nRead <= 0) {
                    if (MORE_DEBUG) Slog.w(TAG, "- wanted exactly " + size + " but got only " + soFar);
                    break;
                }
                soFar += nRead;
if (MORE_DEBUG) Slog.v(TAG, "   + got " + nRead + "; now wanting " + (size - soFar));
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
                    throw new IOException("Invalid number in header: '" + (char)b
                            + "' for radix " + radix);
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

    // ***** end new engine class ***

    // Used for synchronizing doRestoreFinished during adb restore
    class AdbRestoreFinishedLatch implements BackupRestoreTask {
        static final String TAG = "AdbRestoreFinishedLatch";
        final CountDownLatch mLatch;

        AdbRestoreFinishedLatch() {
            mLatch = new CountDownLatch(1);
        }

        void await() {
            boolean latched = false;
            try {
                latched = mLatch.await(TIMEOUT_FULL_BACKUP_INTERVAL, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupted!");
            }
        }

        @Override
        public void execute() {
            // Unused
        }

        @Override
        public void operationComplete(long result) {
            if (MORE_DEBUG) {
                Slog.w(TAG, "adb onRestoreFinished() complete");
            }
            mLatch.countDown();
        }

        @Override
        public void handleTimeout() {
            if (DEBUG) {
                Slog.w(TAG, "adb onRestoreFinished() timed out");
            }
            mLatch.countDown();
        }
    }

    class PerformAdbRestoreTask implements Runnable {
        ParcelFileDescriptor mInputFile;
        String mCurrentPassword;
        String mDecryptPassword;
        IFullBackupRestoreObserver mObserver;
        AtomicBoolean mLatchObject;
        IBackupAgent mAgent;
        String mAgentPackage;
        ApplicationInfo mTargetApp;
        FullBackupObbConnection mObbConnection = null;
        ParcelFileDescriptor[] mPipes = null;
        byte[] mWidgetData = null;

        long mBytes;

        // Runner that can be placed on a separate thread to do in-process invocation
        // of the "restore finished" API asynchronously.  Used by adb restore.
        class RestoreFinishedRunnable implements Runnable {
            final IBackupAgent mAgent;
            final int mToken;

            RestoreFinishedRunnable(IBackupAgent agent, int token) {
                mAgent = agent;
                mToken = token;
            }

            @Override
            public void run() {
                try {
                    mAgent.doRestoreFinished(mToken, mBackupManagerBinder);
                } catch (RemoteException e) {
                    // never happens; this is used only for local binder calls
                }
            }
        }

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

        PerformAdbRestoreTask(ParcelFileDescriptor fd, String curPassword, String decryptPassword,
                IFullBackupRestoreObserver observer, AtomicBoolean latch) {
            mInputFile = fd;
            mCurrentPassword = curPassword;
            mDecryptPassword = decryptPassword;
            mObserver = observer;
            mLatchObject = latch;
            mAgent = null;
            mAgentPackage = null;
            mTargetApp = null;
            mObbConnection = new FullBackupObbConnection();

            // Which packages we've already wiped data on.  We prepopulate this
            // with a whitelist of packages known to be unclearable.
            mClearedPackages.add("android");
            mClearedPackages.add(SETTINGS_PACKAGE);
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
            mObbConnection.establish();
            sendStartRestore();

            // Are we able to restore shared-storage data?
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                mPackagePolicies.put(SHARED_BACKUP_AGENT_PACKAGE, RestorePolicy.ACCEPT);
            }

            FileInputStream rawInStream = null;
            DataInputStream rawDataIn = null;
            try {
                if (!backupPasswordMatches(mCurrentPassword)) {
                    if (DEBUG) Slog.w(TAG, "Backup password mismatch; aborting");
                    return;
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
                    final int archiveVersion = Integer.parseInt(s);
                    if (archiveVersion <= BACKUP_FILE_VERSION) {
                        // okay, it's a version we recognize.  if it's version 1, we may need
                        // to try two different PBKDF2 regimes to compare checksums.
                        final boolean pbkdf2Fallback = (archiveVersion == 1);

                        s = readHeaderLine(rawInStream);
                        compressed = (Integer.parseInt(s) != 0);
                        s = readHeaderLine(rawInStream);
                        if (s.equals("none")) {
                            // no more header to parse; we're good to go
                            okay = true;
                        } else if (mDecryptPassword != null && mDecryptPassword.length() > 0) {
                            preCompressStream = decodeAesHeaderAndInitialize(s, pbkdf2Fallback,
                                    rawInStream);
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
                tearDownAgent(mTargetApp, true);

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
                mObbConnection.tearDown();
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

        InputStream attemptMasterKeyDecryption(String algorithm, byte[] userSalt, byte[] ckSalt,
                int rounds, String userIvHex, String masterKeyBlobHex, InputStream rawInStream,
                boolean doLog) {
            InputStream result = null;

            try {
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                SecretKey userKey = buildPasswordKey(algorithm, mDecryptPassword, userSalt,
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
                byte[] calculatedCk = makeKeyChecksum(algorithm, mk, ckSalt, rounds);
                if (Arrays.equals(calculatedCk, mkChecksum)) {
                    ivSpec = new IvParameterSpec(IV);
                    c.init(Cipher.DECRYPT_MODE,
                            new SecretKeySpec(mk, "AES"),
                            ivSpec);
                    // Only if all of the above worked properly will 'result' be assigned
                    result = new CipherInputStream(rawInStream, c);
                } else if (doLog) Slog.w(TAG, "Incorrect password");
            } catch (InvalidAlgorithmParameterException e) {
                if (doLog) Slog.e(TAG, "Needed parameter spec unavailable!", e);
            } catch (BadPaddingException e) {
                // This case frequently occurs when the wrong password is used to decrypt
                // the master key.  Use the identical "incorrect password" log text as is
                // used in the checksum failure log in order to avoid providing additional
                // information to an attacker.
                if (doLog) Slog.w(TAG, "Incorrect password");
            } catch (IllegalBlockSizeException e) {
                if (doLog) Slog.w(TAG, "Invalid block size in master key");
            } catch (NoSuchAlgorithmException e) {
                if (doLog) Slog.e(TAG, "Needed decryption algorithm unavailable!");
            } catch (NoSuchPaddingException e) {
                if (doLog) Slog.e(TAG, "Needed padding mechanism unavailable!");
            } catch (InvalidKeyException e) {
                if (doLog) Slog.w(TAG, "Illegal password; aborting");
            }

            return result;
        }

        InputStream decodeAesHeaderAndInitialize(String encryptionName, boolean pbkdf2Fallback,
                InputStream rawInStream) {
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
                    result = attemptMasterKeyDecryption(PBKDF_CURRENT, userSalt, ckSalt,
                            rounds, userIvHex, masterKeyBlobHex, rawInStream, false);
                    if (result == null && pbkdf2Fallback) {
                        result = attemptMasterKeyDecryption(PBKDF_FALLBACK, userSalt, ckSalt,
                                rounds, userIvHex, masterKeyBlobHex, rawInStream, true);
                    }
                } else Slog.w(TAG, "Unsupported encryption method: " + encryptionName);
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
                            if (DEBUG) Slog.d(TAG, "Saw new package; finalizing old one");
                            // Now we're really done
                            tearDownPipes();
                            tearDownAgent(mTargetApp, true);
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
                    } else if (info.path.equals(BACKUP_METADATA_FILENAME)) {
                        // Metadata blobs!
                        readMetadata(info, instream);
                        skipTarPadding(info.size, instream);
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

                        // The path needs to be canonical
                        if (info.path.contains("..") || info.path.contains("//")) {
                            if (MORE_DEBUG) {
                                Slog.w(TAG, "Dropping invalid path " + info.path);
                            }
                            okay = false;
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
                                prepareOperationTimeout(token, TIMEOUT_FULL_BACKUP_INTERVAL, null);
                                if (info.domain.equals(FullBackup.OBB_TREE_TOKEN)) {
                                    if (DEBUG) Slog.d(TAG, "Restoring OBB file for " + pkg
                                            + " : " + info.path);
                                    mObbConnection.restoreObbFile(pkg, mPipes[0],
                                            info.size, info.type, info.path, info.mode,
                                            info.mtime, token, mBackupManagerBinder);
                                } else {
                                    if (DEBUG) Slog.d(TAG, "Invoking agent to restore file "
                                            + info.path);
                                    // fire up the app's agent listening on the socket.  If
                                    // the agent is running in the system process we can't
                                    // just invoke it asynchronously, so we provide a thread
                                    // for it here.
                                    if (mTargetApp.processName.equals("system")) {
                                        Slog.d(TAG, "system process agent - spinning a thread");
                                        RestoreFileRunnable runner = new RestoreFileRunnable(
                                                mAgent, info, mPipes[0], token);
                                        new Thread(runner, "restore-sys-runner").start();
                                    } else {
                                        mAgent.doRestoreFile(mPipes[0], info.size, info.type,
                                                info.domain, info.path, info.mode, info.mtime,
                                                token, mBackupManagerBinder);
                                    }
                                }
                            } catch (IOException e) {
                                // couldn't dup the socket for a process-local restore
                                Slog.d(TAG, "Couldn't establish restore");
                                agentSuccess = false;
                                okay = false;
                            } catch (RemoteException e) {
                                // whoops, remote entity went away.  We'll eat the content
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
                                if (DEBUG) {
                                    Slog.d(TAG, "Agent failure restoring " + pkg + "; now ignoring");
                                }
                                mBackupHandler.removeMessages(MSG_TIMEOUT);
                                tearDownPipes();
                                tearDownAgent(mTargetApp, false);
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

        void tearDownAgent(ApplicationInfo app, boolean doRestoreFinished) {
            if (mAgent != null) {
                try {
                    // In the adb restore case, we do restore-finished here
                    if (doRestoreFinished) {
                        final int token = generateToken();
                        final AdbRestoreFinishedLatch latch = new AdbRestoreFinishedLatch();
                        prepareOperationTimeout(token, TIMEOUT_FULL_BACKUP_INTERVAL, latch);
                        if (mTargetApp.processName.equals("system")) {
                            if (MORE_DEBUG) {
                                Slog.d(TAG, "system agent - restoreFinished on thread");
                            }
                            Runnable runner = new RestoreFinishedRunnable(mAgent, token);
                            new Thread(runner, "restore-sys-finished-runner").start();
                        } else {
                            mAgent.doRestoreFinished(token, mBackupManagerBinder);
                        }

                        latch.await();
                    }

                    // unbind and tidy up even on timeout or failure, just in case
                    mActivityManager.unbindBackupAgent(app);

                    // The agent was running with a stub Application object, so shut it down.
                    // !!! We hardcode the confirmation UI's package name here rather than use a
                    //     manifest flag!  TODO something less direct.
                    if (app.uid >= Process.FIRST_APPLICATION_UID
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

        class RestoreInstallObserver extends PackageInstallObserver {
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
            public void onPackageInstalled(String packageName, int returnCode,
                    String msg, Bundle extras) {
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
                                if (signaturesMatch(sigs, pkg)) {
                                    // If this is a system-uid app without a declared backup agent,
                                    // don't restore any of the file data.
                                    if ((pkg.applicationInfo.uid < Process.FIRST_APPLICATION_UID)
                                            && (pkg.applicationInfo.backupAgentName == null)) {
                                        Slog.w(TAG, "Installed app " + info.packageName
                                                + " has restricted uid and no agent");
                                        okay = false;
                                    }
                                } else {
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

        // Read a widget metadata file, returning the restored blob
        void readMetadata(FileMetadata info, InputStream instream) throws IOException {
            // Fail on suspiciously large widget dump files
            if (info.size > 64 * 1024) {
                throw new IOException("Metadata too big; corrupt? size=" + info.size);
            }

            byte[] buffer = new byte[(int) info.size];
            if (readExactly(instream, buffer, 0, (int)info.size) == info.size) {
                mBytes += info.size;
            } else throw new IOException("Unexpected EOF in widget data");

            String[] str = new String[1];
            int offset = extractLine(buffer, 0, str);
            int version = Integer.parseInt(str[0]);
            if (version == BACKUP_MANIFEST_VERSION) {
                offset = extractLine(buffer, offset, str);
                final String pkg = str[0];
                if (info.packageName.equals(pkg)) {
                    // Data checks out -- the rest of the buffer is a concatenation of
                    // binary blobs as described in the comment at writeAppWidgetData()
                    ByteArrayInputStream bin = new ByteArrayInputStream(buffer,
                            offset, buffer.length - offset);
                    DataInputStream in = new DataInputStream(bin);
                    while (bin.available() > 0) {
                        int token = in.readInt();
                        int size = in.readInt();
                        if (size > 64 * 1024) {
                            throw new IOException("Datum "
                                    + Integer.toHexString(token)
                                    + " too big; corrupt? size=" + info.size);
                        }
                        switch (token) {
                            case BACKUP_WIDGET_METADATA_TOKEN:
                            {
                                if (MORE_DEBUG) {
                                    Slog.i(TAG, "Got widget metadata for " + info.packageName);
                                }
                                mWidgetData = new byte[size];
                                in.read(mWidgetData);
                                break;
                            }
                            default:
                            {
                                if (DEBUG) {
                                    Slog.i(TAG, "Ignoring metadata blob "
                                            + Integer.toHexString(token)
                                            + " for " + info.packageName);
                                }
                                in.skipBytes(size);
                                break;
                            }
                        }
                    }
                } else {
                    Slog.w(TAG, "Metadata mismatch: package " + info.packageName
                            + " but widget data for " + pkg);
                }
            } else {
                Slog.w(TAG, "Unsupported metadata version " + version);
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
                        // This is the platform version, which we don't use, but we parse it
                        // as a safety against corruption in the manifest.
                        Integer.parseInt(str[0]);
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
                b.append(new SimpleDateFormat("MMM dd HH:mm:ss ").format(stamp));

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

                        // if it's a manifest or metadata payload we're done, otherwise parse
                        // out the domain into which the file will be restored
                        if (!info.path.equals(BACKUP_MANIFEST_FILENAME)
                                && !info.path.equals(BACKUP_METADATA_FILENAME)) {
                            slash = info.path.indexOf('/');
                            if (slash < 0) throw new IOException("Illegal semantic path in non-manifest " + info.path);
                            info.domain = info.path.substring(0, slash);
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

    // Old style: directly match the stored vs on device signature blocks
    static boolean signaturesMatch(Signature[] storedSigs, PackageInfo target) {
        if (target == null) {
            return false;
        }

        // If the target resides on the system partition, we allow it to restore
        // data from the like-named package in a restore set even if the signatures
        // do not match.  (Unlike general applications, those flashed to the system
        // partition will be signed with the device's platform certificate, so on
        // different phones the same system app will have different signatures.)
        if ((target.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            if (MORE_DEBUG) Slog.v(TAG, "System app " + target.packageName + " - skipping sig check");
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

    // Used by both incremental and full restore
    void restoreWidgetData(String packageName, byte[] widgetData) {
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

    // states of the unified-restore state machine
    enum UnifiedRestoreState {
        INITIAL,
        RUNNING_QUEUE,
        RESTORE_KEYVALUE,
        RESTORE_FULL,
        RESTORE_FINISHED,
        FINAL
    }

    class PerformUnifiedRestoreTask implements BackupRestoreTask {
        // Transport we're working with to do the restore
        private IBackupTransport mTransport;

        // Where per-transport saved state goes
        File mStateDir;

        // Restore observer; may be null
        private IRestoreObserver mObserver;

        // Token identifying the dataset to the transport
        private long mToken;

        // When this is a restore-during-install, this is the token identifying the
        // operation to the Package Manager, and we must ensure that we let it know
        // when we're finished.
        private int mPmToken;

        // When this is restore-during-install, we need to tell the package manager
        // whether we actually launched the app, because this affects notifications
        // around externally-visible state transitions.
        private boolean mDidLaunch;

        // Is this a whole-system restore, i.e. are we establishing a new ancestral
        // dataset to base future restore-at-install operations from?
        private boolean mIsSystemRestore;

        // If this is a single-package restore, what package are we interested in?
        private PackageInfo mTargetPackage;

        // In all cases, the calculated list of packages that we are trying to restore
        private List<PackageInfo> mAcceptSet;

        // Our bookkeeping about the ancestral dataset
        private PackageManagerBackupAgent mPmAgent;

        // Currently-bound backup agent for restore + restoreFinished purposes
        private IBackupAgent mAgent;

        // What sort of restore we're doing now
        private RestoreDescription mRestoreDescription;

        // The package we're currently restoring
        private PackageInfo mCurrentPackage;

        // Widget-related data handled as part of this restore operation
        private byte[] mWidgetData;

        // Number of apps restored in this pass
        private int mCount;

        // When did we start?
        private long mStartRealtime;

        // State machine progress
        private UnifiedRestoreState mState;

        // How are things going?
        private int mStatus;

        // Done?
        private boolean mFinished;

        // Key/value: bookkeeping about staged data and files for agent access
        private File mBackupDataName;
        private File mStageName;
        private File mSavedStateName;
        private File mNewStateName;
        ParcelFileDescriptor mBackupData;
        ParcelFileDescriptor mNewState;

        // Invariant: mWakelock is already held, and this task is responsible for
        // releasing it at the end of the restore operation.
        PerformUnifiedRestoreTask(IBackupTransport transport, IRestoreObserver observer,
                long restoreSetToken, PackageInfo targetPackage, int pmToken,
                boolean isFullSystemRestore, String[] filterSet) {
            mState = UnifiedRestoreState.INITIAL;
            mStartRealtime = SystemClock.elapsedRealtime();

            mTransport = transport;
            mObserver = observer;
            mToken = restoreSetToken;
            mPmToken = pmToken;
            mTargetPackage = targetPackage;
            mIsSystemRestore = isFullSystemRestore;
            mFinished = false;
            mDidLaunch = false;

            if (targetPackage != null) {
                // Single package restore
                mAcceptSet = new ArrayList<PackageInfo>();
                mAcceptSet.add(targetPackage);
            } else {
                // Everything possible, or a target set
                if (filterSet == null) {
                    // We want everything and a pony
                    List<PackageInfo> apps =
                            PackageManagerBackupAgent.getStorableApplications(mPackageManager);
                    filterSet = packagesToNames(apps);
                    if (DEBUG) {
                        Slog.i(TAG, "Full restore; asking about " + filterSet.length + " apps");
                    }
                }

                mAcceptSet = new ArrayList<PackageInfo>(filterSet.length);

                // Pro tem, we insist on moving the settings provider package to last place.
                // Keep track of whether it's in the list, and bump it down if so.  We also
                // want to do the system package itself first if it's called for.
                boolean hasSystem = false;
                boolean hasSettings = false;
                for (int i = 0; i < filterSet.length; i++) {
                    try {
                        PackageInfo info = mPackageManager.getPackageInfo(filterSet[i], 0);
                        if ("android".equals(info.packageName)) {
                            hasSystem = true;
                            continue;
                        }
                        if (SETTINGS_PACKAGE.equals(info.packageName)) {
                            hasSettings = true;
                            continue;
                        }

                        if (appIsEligibleForBackup(info.applicationInfo)) {
                            mAcceptSet.add(info);
                        }
                    } catch (NameNotFoundException e) {
                        // requested package name doesn't exist; ignore it
                    }
                }
                if (hasSystem) {
                    try {
                        mAcceptSet.add(0, mPackageManager.getPackageInfo("android", 0));
                    } catch (NameNotFoundException e) {
                        // won't happen; we know a priori that it's valid
                    }
                }
                if (hasSettings) {
                    try {
                        mAcceptSet.add(mPackageManager.getPackageInfo(SETTINGS_PACKAGE, 0));
                    } catch (NameNotFoundException e) {
                        // this one is always valid too
                    }
                }
            }

            if (MORE_DEBUG) {
                Slog.v(TAG, "Restore; accept set size is " + mAcceptSet.size());
                for (PackageInfo info : mAcceptSet) {
                    Slog.v(TAG, "   " + info.packageName);
                }
            }
        }

        private String[] packagesToNames(List<PackageInfo> apps) {
            final int N = apps.size();
            String[] names = new String[N];
            for (int i = 0; i < N; i++) {
                names[i] = apps.get(i).packageName;
            }
            return names;
        }

        // Execute one tick of whatever state machine the task implements
        @Override
        public void execute() {
            if (MORE_DEBUG) Slog.v(TAG, "*** Executing restore step " + mState);
            switch (mState) {
                case INITIAL:
                    startRestore();
                    break;

                case RUNNING_QUEUE:
                    dispatchNextRestore();
                    break;

                case RESTORE_KEYVALUE:
                    restoreKeyValue();
                    break;

                case RESTORE_FULL:
                    restoreFull();
                    break;

                case RESTORE_FINISHED:
                    restoreFinished();
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

        /*
         * SKETCH OF OPERATION
         * 
         * create one of these PerformUnifiedRestoreTask objects, telling it which
         * dataset & transport to address, and then parameters within the restore
         * operation: single target package vs many, etc.
         *
         * 1. transport.startRestore(token, list-of-packages).  If we need @pm@  it is
         * always placed first and the settings provider always placed last [for now].
         * 
         * 1a [if we needed @pm@ then nextRestorePackage() and restore the PMBA inline]
         * 
         *   [ state change => RUNNING_QUEUE ]
         * 
         * NOW ITERATE:
         * 
         * { 3. t.nextRestorePackage()
         *   4. does the metadata for this package allow us to restore it?
         *      does the on-disk app permit us to restore it? [re-check allowBackup etc]
         *   5. is this a key/value dataset?  => key/value agent restore
         *       [ state change => RESTORE_KEYVALUE ]
         *       5a. spin up agent
         *       5b. t.getRestoreData() to stage it properly
         *       5c. call into agent to perform restore
         *       5d. tear down agent
         *       [ state change => RUNNING_QUEUE ]
         * 
         *   6. else it's a stream dataset:
         *       [ state change => RESTORE_FULL ]
         *       6a. instantiate the engine for a stream restore: engine handles agent lifecycles
         *       6b. spin off engine runner on separate thread
         *       6c. ITERATE getNextFullRestoreDataChunk() and copy data to engine runner socket
         *       [ state change => RUNNING_QUEUE ]
         * }
         * 
         *   [ state change => FINAL ]
         * 
         * 7. t.finishRestore(), release wakelock, etc.
         * 
         * 
         */

        // state INITIAL : set up for the restore and read the metadata if necessary
        private  void startRestore() {
            sendStartRestore(mAcceptSet.size());

            // If we're starting a full-system restore, set up to begin widget ID remapping
            if (mIsSystemRestore) {
                // TODO: http://b/22388012
                AppWidgetBackupBridge.restoreStarting(UserHandle.USER_SYSTEM);
            }

            try {
                String transportDir = mTransport.transportDirName();
                mStateDir = new File(mBaseStateDir, transportDir);

                // Fetch the current metadata from the dataset first
                PackageInfo pmPackage = new PackageInfo();
                pmPackage.packageName = PACKAGE_MANAGER_SENTINEL;
                mAcceptSet.add(0, pmPackage);

                PackageInfo[] packages = mAcceptSet.toArray(new PackageInfo[0]);
                mStatus = mTransport.startRestore(mToken, packages);
                if (mStatus != BackupTransport.TRANSPORT_OK) {
                    Slog.e(TAG, "Transport error " + mStatus + "; no restore possible");
                    mStatus = BackupTransport.TRANSPORT_ERROR;
                    executeNextState(UnifiedRestoreState.FINAL);
                    return;
                }

                RestoreDescription desc = mTransport.nextRestorePackage();
                if (desc == null) {
                    Slog.e(TAG, "No restore metadata available; halting");
                    mStatus = BackupTransport.TRANSPORT_ERROR;
                    executeNextState(UnifiedRestoreState.FINAL);
                    return;
                }
                if (!PACKAGE_MANAGER_SENTINEL.equals(desc.getPackageName())) {
                    Slog.e(TAG, "Required metadata but got " + desc.getPackageName());
                    mStatus = BackupTransport.TRANSPORT_ERROR;
                    executeNextState(UnifiedRestoreState.FINAL);
                    return;
                }

                // Pull the Package Manager metadata from the restore set first
                mCurrentPackage = new PackageInfo();
                mCurrentPackage.packageName = PACKAGE_MANAGER_SENTINEL;
                mPmAgent = new PackageManagerBackupAgent(mPackageManager, null);
                mAgent = IBackupAgent.Stub.asInterface(mPmAgent.onBind());
                if (MORE_DEBUG) {
                    Slog.v(TAG, "initiating restore for PMBA");
                }
                initiateOneRestore(mCurrentPackage, 0);
                // The PM agent called operationComplete() already, because our invocation
                // of it is process-local and therefore synchronous.  That means that the
                // next-state message (RUNNING_QUEUE) is already enqueued.  Only if we're
                // unable to proceed with running the queue do we remove that pending
                // message and jump straight to the FINAL state.  Because this was
                // synchronous we also know that we should cancel the pending timeout
                // message.
                mBackupHandler.removeMessages(MSG_TIMEOUT);

                // Verify that the backup set includes metadata.  If not, we can't do
                // signature/version verification etc, so we simply do not proceed with
                // the restore operation.
                if (!mPmAgent.hasMetadata()) {
                    Slog.e(TAG, "No restore metadata available, so not restoring");
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                            PACKAGE_MANAGER_SENTINEL,
                            "Package manager restore metadata missing");
                    mStatus = BackupTransport.TRANSPORT_ERROR;
                    mBackupHandler.removeMessages(MSG_BACKUP_RESTORE_STEP, this);
                    executeNextState(UnifiedRestoreState.FINAL);
                    return;
                }

                // Success; cache the metadata and continue as expected with the
                // next state already enqueued

            } catch (RemoteException e) {
                // If we lost the transport at any time, halt
                Slog.e(TAG, "Unable to contact transport for restore");
                mStatus = BackupTransport.TRANSPORT_ERROR;
                mBackupHandler.removeMessages(MSG_BACKUP_RESTORE_STEP, this);
                executeNextState(UnifiedRestoreState.FINAL);
                return;
            }
        }

        // state RUNNING_QUEUE : figure out what the next thing to be restored is,
        // and fire the appropriate next step
        private void dispatchNextRestore() {
            UnifiedRestoreState nextState = UnifiedRestoreState.FINAL;
            try {
                mRestoreDescription = mTransport.nextRestorePackage();
                final String pkgName = (mRestoreDescription != null)
                        ? mRestoreDescription.getPackageName() : null;
                if (pkgName == null) {
                    Slog.e(TAG, "Failure getting next package name");
                    EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                    nextState = UnifiedRestoreState.FINAL;
                    return;
                } else if (mRestoreDescription == RestoreDescription.NO_MORE_PACKAGES) {
                    // Yay we've reached the end cleanly
                    if (DEBUG) {
                        Slog.v(TAG, "No more packages; finishing restore");
                    }
                    int millis = (int) (SystemClock.elapsedRealtime() - mStartRealtime);
                    EventLog.writeEvent(EventLogTags.RESTORE_SUCCESS, mCount, millis);
                    nextState = UnifiedRestoreState.FINAL;
                    return;
                }

                if (DEBUG) {
                    Slog.i(TAG, "Next restore package: " + mRestoreDescription);
                }
                sendOnRestorePackage(pkgName);

                Metadata metaInfo = mPmAgent.getRestoredMetadata(pkgName);
                if (metaInfo == null) {
                    Slog.e(TAG, "No metadata for " + pkgName);
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, pkgName,
                            "Package metadata missing");
                    nextState = UnifiedRestoreState.RUNNING_QUEUE;
                    return;
                }

                try {
                    mCurrentPackage = mPackageManager.getPackageInfo(
                            pkgName, PackageManager.GET_SIGNATURES);
                } catch (NameNotFoundException e) {
                    // Whoops, we thought we could restore this package but it
                    // turns out not to be present.  Skip it.
                    Slog.e(TAG, "Package not present: " + pkgName);
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, pkgName,
                            "Package missing on device");
                    nextState = UnifiedRestoreState.RUNNING_QUEUE;
                    return;
                }

                if (metaInfo.versionCode > mCurrentPackage.versionCode) {
                    // Data is from a "newer" version of the app than we have currently
                    // installed.  If the app has not declared that it is prepared to
                    // handle this case, we do not attempt the restore.
                    if ((mCurrentPackage.applicationInfo.flags
                            & ApplicationInfo.FLAG_RESTORE_ANY_VERSION) == 0) {
                        String message = "Version " + metaInfo.versionCode
                                + " > installed version " + mCurrentPackage.versionCode;
                        Slog.w(TAG, "Package " + pkgName + ": " + message);
                        EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                                pkgName, message);
                        nextState = UnifiedRestoreState.RUNNING_QUEUE;
                        return;
                    } else {
                        if (DEBUG) Slog.v(TAG, "Version " + metaInfo.versionCode
                                + " > installed " + mCurrentPackage.versionCode
                                + " but restoreAnyVersion");
                    }
                }

                if (MORE_DEBUG) Slog.v(TAG, "Package " + pkgName
                        + " restore version [" + metaInfo.versionCode
                        + "] is compatible with installed version ["
                        + mCurrentPackage.versionCode + "]");

                // Reset per-package preconditions and fire the appropriate next state
                mWidgetData = null;
                final int type = mRestoreDescription.getDataType();
                if (type == RestoreDescription.TYPE_KEY_VALUE) {
                    nextState = UnifiedRestoreState.RESTORE_KEYVALUE;
                } else if (type == RestoreDescription.TYPE_FULL_STREAM) {
                    nextState = UnifiedRestoreState.RESTORE_FULL;
                } else {
                    // Unknown restore type; ignore this package and move on
                    Slog.e(TAG, "Unrecognized restore type " + type);
                    nextState = UnifiedRestoreState.RUNNING_QUEUE;
                    return;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Can't get next target from transport; ending restore");
                EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                nextState = UnifiedRestoreState.FINAL;
                return;
            } finally {
                executeNextState(nextState);
            }
        }

        // state RESTORE_KEYVALUE : restore one package via key/value API set
        private void restoreKeyValue() {
            // Initiating the restore will pass responsibility for the state machine's
            // progress to the agent callback, so we do not always execute the
            // next state here.
            final String packageName = mCurrentPackage.packageName;
            // Validate some semantic requirements that apply in this way
            // only to the key/value restore API flow
            if (mCurrentPackage.applicationInfo.backupAgentName == null
                    || "".equals(mCurrentPackage.applicationInfo.backupAgentName)) {
                if (MORE_DEBUG) {
                    Slog.i(TAG, "Data exists for package " + packageName
                            + " but app has no agent; skipping");
                }
                EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                        "Package has no agent");
                executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
                return;
            }

            Metadata metaInfo = mPmAgent.getRestoredMetadata(packageName);
            if (!BackupUtils.signaturesMatch(metaInfo.sigHashes, mCurrentPackage)) {
                Slog.w(TAG, "Signature mismatch restoring " + packageName);
                EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                        "Signature mismatch");
                executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
                return;
            }

            // Good to go!  Set up and bind the agent...
            mAgent = bindToAgentSynchronous(
                    mCurrentPackage.applicationInfo,
                    IApplicationThread.BACKUP_MODE_INCREMENTAL);
            if (mAgent == null) {
                Slog.w(TAG, "Can't find backup agent for " + packageName);
                EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE, packageName,
                        "Restore agent missing");
                executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
                return;
            }

            // Whatever happens next, we've launched the target app now; remember that.
            mDidLaunch = true;

            // And then finally start the restore on this agent
            try {
                initiateOneRestore(mCurrentPackage, metaInfo.versionCode);
                ++mCount;
            } catch (Exception e) {
                Slog.e(TAG, "Error when attempting restore: " + e.toString());
                keyValueAgentErrorCleanup();
                executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
            }
        }

        // Guts of a key/value restore operation
        void initiateOneRestore(PackageInfo app, int appVersionCode) {
            final String packageName = app.packageName;

            if (DEBUG) Slog.d(TAG, "initiateOneRestore packageName=" + packageName);

            // !!! TODO: get the dirs from the transport
            mBackupDataName = new File(mDataDir, packageName + ".restore");
            mStageName = new File(mDataDir, packageName + ".stage");
            mNewStateName = new File(mStateDir, packageName + ".new");
            mSavedStateName = new File(mStateDir, packageName);

            // don't stage the 'android' package where the wallpaper data lives.  this is
            // an optimization: we know there's no widget data hosted/published by that
            // package, and this way we avoid doing a spurious copy of MB-sized wallpaper
            // data following the download.
            boolean staging = !packageName.equals("android");
            ParcelFileDescriptor stage;
            File downloadFile = (staging) ? mStageName : mBackupDataName;

            final int token = generateToken();
            try {
                // Run the transport's restore pass
                stage = ParcelFileDescriptor.open(downloadFile,
                        ParcelFileDescriptor.MODE_READ_WRITE |
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE);

                if (mTransport.getRestoreData(stage) != BackupTransport.TRANSPORT_OK) {
                    // Transport-level failure, so we wind everything up and
                    // terminate the restore operation.
                    Slog.e(TAG, "Error getting restore data for " + packageName);
                    EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                    stage.close();
                    downloadFile.delete();
                    executeNextState(UnifiedRestoreState.FINAL);
                    return;
                }

                // We have the data from the transport. Now we extract and strip
                // any per-package metadata (typically widget-related information)
                // if appropriate
                if (staging) {
                    stage.close();
                    stage = ParcelFileDescriptor.open(downloadFile,
                            ParcelFileDescriptor.MODE_READ_ONLY);

                    mBackupData = ParcelFileDescriptor.open(mBackupDataName,
                            ParcelFileDescriptor.MODE_READ_WRITE |
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE);

                    BackupDataInput in = new BackupDataInput(stage.getFileDescriptor());
                    BackupDataOutput out = new BackupDataOutput(mBackupData.getFileDescriptor());
                    byte[] buffer = new byte[8192]; // will grow when needed
                    while (in.readNextHeader()) {
                        final String key = in.getKey();
                        final int size = in.getDataSize();

                        // is this a special key?
                        if (key.equals(KEY_WIDGET_STATE)) {
                            if (DEBUG) {
                                Slog.i(TAG, "Restoring widget state for " + packageName);
                            }
                            mWidgetData = new byte[size];
                            in.readEntityData(mWidgetData, 0, size);
                        } else {
                            if (size > buffer.length) {
                                buffer = new byte[size];
                            }
                            in.readEntityData(buffer, 0, size);
                            out.writeEntityHeader(key, size);
                            out.writeEntityData(buffer, size);
                        }
                    }

                    mBackupData.close();
                }

                // Okay, we have the data.  Now have the agent do the restore.
                stage.close();

                mBackupData = ParcelFileDescriptor.open(mBackupDataName,
                        ParcelFileDescriptor.MODE_READ_ONLY);

                mNewState = ParcelFileDescriptor.open(mNewStateName,
                        ParcelFileDescriptor.MODE_READ_WRITE |
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE);

                // Kick off the restore, checking for hung agents.  The timeout or
                // the operationComplete() callback will schedule the next step,
                // so we do not do that here.
                prepareOperationTimeout(token, TIMEOUT_RESTORE_INTERVAL, this);
                mAgent.doRestore(mBackupData, appVersionCode, mNewState,
                        token, mBackupManagerBinder);
            } catch (Exception e) {
                Slog.e(TAG, "Unable to call app for restore: " + packageName, e);
                EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                        packageName, e.toString());
                keyValueAgentErrorCleanup();    // clears any pending timeout messages as well

                // After a restore failure we go back to running the queue.  If there
                // are no more packages to be restored that will be handled by the
                // next step.
                executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
            }
        }

        // state RESTORE_FULL : restore one package via streaming engine
        private void restoreFull() {
            // None of this can run on the work looper here, so we spin asynchronous
            // work like this:
            //
            //   StreamFeederThread: read data from mTransport.getNextFullRestoreDataChunk()
            //                       write it into the pipe to the engine
            //   EngineThread: FullRestoreEngine thread communicating with the target app
            //
            // When finished, StreamFeederThread executes next state as appropriate on the
            // backup looper, and the overall unified restore task resumes
            try {
                StreamFeederThread feeder = new StreamFeederThread();
                if (MORE_DEBUG) {
                    Slog.i(TAG, "Spinning threads for stream restore of "
                            + mCurrentPackage.packageName);
                }
                new Thread(feeder, "unified-stream-feeder").start();

                // At this point the feeder is responsible for advancing the restore
                // state, so we're done here.
            } catch (IOException e) {
                // Unable to instantiate the feeder thread -- we need to bail on the
                // current target.  We haven't asked the transport for data yet, though,
                // so we can do that simply by going back to running the restore queue.
                Slog.e(TAG, "Unable to construct pipes for stream restore!");
                executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
            }
        }

        // state RESTORE_FINISHED : provide the "no more data" signpost callback at the end
        private void restoreFinished() {
            try {
                final int token = generateToken();
                prepareOperationTimeout(token, TIMEOUT_RESTORE_FINISHED_INTERVAL, this);
                mAgent.doRestoreFinished(token, mBackupManagerBinder);
                // If we get this far, the callback or timeout will schedule the
                // next restore state, so we're done
            } catch (Exception e) {
                final String packageName = mCurrentPackage.packageName;
                Slog.e(TAG, "Unable to finalize restore of " + packageName);
                EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                        packageName, e.toString());
                keyValueAgentErrorCleanup();
                executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
            }
        }

        class StreamFeederThread extends RestoreEngine implements Runnable, BackupRestoreTask {
            final String TAG = "StreamFeederThread";
            FullRestoreEngine mEngine;
            EngineThread mEngineThread;

            // pipe through which we read data from the transport. [0] read, [1] write
            ParcelFileDescriptor[] mTransportPipes;

            // pipe through which the engine will read data.  [0] read, [1] write
            ParcelFileDescriptor[] mEnginePipes;

            public StreamFeederThread() throws IOException {
                mTransportPipes = ParcelFileDescriptor.createPipe();
                mEnginePipes = ParcelFileDescriptor.createPipe();
                setRunning(true);
            }

            @Override
            public void run() {
                UnifiedRestoreState nextState = UnifiedRestoreState.RUNNING_QUEUE;
                int status = BackupTransport.TRANSPORT_OK;

                EventLog.writeEvent(EventLogTags.FULL_RESTORE_PACKAGE,
                        mCurrentPackage.packageName);

                mEngine = new FullRestoreEngine(this, null, mCurrentPackage, false, false);
                mEngineThread = new EngineThread(mEngine, mEnginePipes[0]);

                ParcelFileDescriptor eWriteEnd = mEnginePipes[1];
                ParcelFileDescriptor tReadEnd = mTransportPipes[0];
                ParcelFileDescriptor tWriteEnd = mTransportPipes[1];

                int bufferSize = 32 * 1024;
                byte[] buffer = new byte[bufferSize];
                FileOutputStream engineOut = new FileOutputStream(eWriteEnd.getFileDescriptor());
                FileInputStream transportIn = new FileInputStream(tReadEnd.getFileDescriptor());

                // spin up the engine and start moving data to it
                new Thread(mEngineThread, "unified-restore-engine").start();

                try {
                    while (status == BackupTransport.TRANSPORT_OK) {
                        // have the transport write some of the restoring data to us
                        int result = mTransport.getNextFullRestoreDataChunk(tWriteEnd);
                        if (result > 0) {
                            // The transport wrote this many bytes of restore data to the
                            // pipe, so pass it along to the engine.
                            if (MORE_DEBUG) {
                                Slog.v(TAG, "  <- transport provided chunk size " + result);
                            }
                            if (result > bufferSize) {
                                bufferSize = result;
                                buffer = new byte[bufferSize];
                            }
                            int toCopy = result;
                            while (toCopy > 0) {
                                int n = transportIn.read(buffer, 0, toCopy);
                                engineOut.write(buffer, 0, n);
                                toCopy -= n;
                                if (MORE_DEBUG) {
                                    Slog.v(TAG, "  -> wrote " + n + " to engine, left=" + toCopy);
                                }
                            }
                        } else if (result == BackupTransport.NO_MORE_DATA) {
                            // Clean finish.  Wind up and we're done!
                            if (MORE_DEBUG) {
                                Slog.i(TAG, "Got clean full-restore EOF for "
                                        + mCurrentPackage.packageName);
                            }
                            status = BackupTransport.TRANSPORT_OK;
                            break;
                        } else {
                            // Transport reported some sort of failure; the fall-through
                            // handling will deal properly with that.
                            Slog.e(TAG, "Error " + result + " streaming restore for "
                                    + mCurrentPackage.packageName);
                            EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                            status = result;
                        }
                    }
                    if (MORE_DEBUG) Slog.v(TAG, "Done copying to engine, falling through");
                } catch (IOException e) {
                    // We lost our ability to communicate via the pipes.  That's worrying
                    // but potentially recoverable; abandon this package's restore but
                    // carry on with the next restore target.
                    Slog.e(TAG, "Unable to route data for restore");
                    EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                            mCurrentPackage.packageName, "I/O error on pipes");
                    status = BackupTransport.AGENT_ERROR;
                } catch (RemoteException e) {
                    // The transport went away; terminate the whole operation.  Closing
                    // the sockets will wake up the engine and it will then tidy up the
                    // remote end.
                    Slog.e(TAG, "Transport failed during restore");
                    EventLog.writeEvent(EventLogTags.RESTORE_TRANSPORT_FAILURE);
                    status = BackupTransport.TRANSPORT_ERROR;
                } finally {
                    // Close the transport pipes and *our* end of the engine pipe,
                    // but leave the engine thread's end open so that it properly
                    // hits EOF and winds up its operations.
                    IoUtils.closeQuietly(mEnginePipes[1]);
                    IoUtils.closeQuietly(mTransportPipes[0]);
                    IoUtils.closeQuietly(mTransportPipes[1]);

                    // Don't proceed until the engine has wound up operations
                    mEngineThread.waitForResult();

                    // Now we're really done with this one too
                    IoUtils.closeQuietly(mEnginePipes[0]);

                    // In all cases we want to remember whether we launched
                    // the target app as part of our work so far.
                    mDidLaunch = (mEngine.getAgent() != null);

                    // If we hit a transport-level error, we are done with everything;
                    // if we hit an agent error we just go back to running the queue.
                    if (status == BackupTransport.TRANSPORT_OK) {
                        // Clean finish means we issue the restore-finished callback
                        nextState = UnifiedRestoreState.RESTORE_FINISHED;

                        // the engine bound the target's agent, so recover that binding
                        // to use for the callback.
                        mAgent = mEngine.getAgent();

                        // and the restored widget data, if any
                        mWidgetData = mEngine.getWidgetData();
                    } else {
                        // Something went wrong somewhere.  Whether it was at the transport
                        // level is immaterial; we need to tell the transport to bail
                        try {
                            mTransport.abortFullRestore();
                        } catch (RemoteException e) {
                            // transport itself is dead; make sure we handle this as a
                            // fatal error
                            status = BackupTransport.TRANSPORT_ERROR;
                        }

                        // We also need to wipe the current target's data, as it's probably
                        // in an incoherent state.
                        clearApplicationDataSynchronous(mCurrentPackage.packageName);

                        // Schedule the next state based on the nature of our failure
                        if (status == BackupTransport.TRANSPORT_ERROR) {
                            nextState = UnifiedRestoreState.FINAL;
                        } else {
                            nextState = UnifiedRestoreState.RUNNING_QUEUE;
                        }
                    }
                    executeNextState(nextState);
                    setRunning(false);
                }
            }

            // BackupRestoreTask interface, specifically for timeout handling

            @Override
            public void execute() { /* intentionally empty */ }

            @Override
            public void operationComplete(long result) { /* intentionally empty */ }

            // The app has timed out handling a restoring file
            @Override
            public void handleTimeout() {
                if (DEBUG) {
                    Slog.w(TAG, "Full-data restore target timed out; shutting down");
                }
                mEngineThread.handleTimeout();

                IoUtils.closeQuietly(mEnginePipes[1]);
                mEnginePipes[1] = null;
                IoUtils.closeQuietly(mEnginePipes[0]);
                mEnginePipes[0] = null;
            }
        }

        class EngineThread implements Runnable {
            FullRestoreEngine mEngine;
            FileInputStream mEngineStream;

            EngineThread(FullRestoreEngine engine, ParcelFileDescriptor engineSocket) {
                mEngine = engine;
                engine.setRunning(true);
                // We *do* want this FileInputStream to own the underlying fd, so that
                // when we are finished with it, it closes this end of the pipe in a way
                // that signals its other end.
                mEngineStream = new FileInputStream(engineSocket.getFileDescriptor(), true);
            }

            public boolean isRunning() {
                return mEngine.isRunning();
            }

            public int waitForResult() {
                return mEngine.waitForResult();
            }

            @Override
            public void run() {
                try {
                    while (mEngine.isRunning()) {
                        // Tell it to be sure to leave the agent instance up after finishing
                        mEngine.restoreOneFile(mEngineStream, false);
                    }
                } finally {
                    // Because mEngineStream adopted its underlying FD, this also
                    // closes this end of the pipe.
                    IoUtils.closeQuietly(mEngineStream);
                }
            }

            public void handleTimeout() {
                IoUtils.closeQuietly(mEngineStream);
                mEngine.handleTimeout();
            }
        }

        // state FINAL : tear everything down and we're done.
        private void finalizeRestore() {
            if (MORE_DEBUG) Slog.d(TAG, "finishing restore mObserver=" + mObserver);

            try {
                mTransport.finishRestore();
            } catch (Exception e) {
                Slog.e(TAG, "Error finishing restore", e);
            }

            // Tell the observer we're done
            if (mObserver != null) {
                try {
                    mObserver.restoreFinished(mStatus);
                } catch (RemoteException e) {
                    Slog.d(TAG, "Restore observer died at restoreFinished");
                }
            }

            // Clear any ongoing session timeout.
            mBackupHandler.removeMessages(MSG_RESTORE_TIMEOUT);

            // If we have a PM token, we must under all circumstances be sure to
            // handshake when we've finished.
            if (mPmToken > 0) {
                if (MORE_DEBUG) Slog.v(TAG, "finishing PM token " + mPmToken);
                try {
                    mPackageManagerBinder.finishPackageInstall(mPmToken, mDidLaunch);
                } catch (RemoteException e) { /* can't happen */ }
            } else {
                // We were invoked via an active restore session, not by the Package
                // Manager, so start up the session timeout again.
                mBackupHandler.sendEmptyMessageDelayed(MSG_RESTORE_TIMEOUT,
                        TIMEOUT_RESTORE_INTERVAL);
            }

            // Kick off any work that may be needed regarding app widget restores
            // TODO: http://b/22388012
            AppWidgetBackupBridge.restoreFinished(UserHandle.USER_SYSTEM);

            // If this was a full-system restore, record the ancestral
            // dataset information
            if (mIsSystemRestore && mPmAgent != null) {
                mAncestralPackages = mPmAgent.getRestoredPackages();
                mAncestralToken = mToken;
                writeRestoreTokens();
            }

            // done; we can finally release the wakelock and be legitimately done.
            Slog.i(TAG, "Restore complete.");
            mWakelock.release();
        }

        void keyValueAgentErrorCleanup() {
            // If the agent fails restore, it might have put the app's data
            // into an incoherent state.  For consistency we wipe its data
            // again in this case before continuing with normal teardown
            clearApplicationDataSynchronous(mCurrentPackage.packageName);
            keyValueAgentCleanup();
        }

        // TODO: clean up naming; this is now used at finish by both k/v and stream restores
        void keyValueAgentCleanup() {
            mBackupDataName.delete();
            mStageName.delete();
            try { if (mBackupData != null) mBackupData.close(); } catch (IOException e) {}
            try { if (mNewState != null) mNewState.close(); } catch (IOException e) {}
            mBackupData = mNewState = null;

            // if everything went okay, remember the recorded state now
            //
            // !!! TODO: the restored data could be migrated on the server
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
                    //
                    // We execute this kill when these conditions hold:
                    //    1. it's not a system-uid process,
                    //    2. the app did not request its own restore (mTargetPackage == null), and either
                    //    3a. the app is a full-data target (TYPE_FULL_STREAM) or
                    //     b. the app does not state android:killAfterRestore="false" in its manifest
                    final int appFlags = mCurrentPackage.applicationInfo.flags;
                    final boolean killAfterRestore =
                            (mCurrentPackage.applicationInfo.uid >= Process.FIRST_APPLICATION_UID)
                            && ((mRestoreDescription.getDataType() == RestoreDescription.TYPE_FULL_STREAM)
                                    || ((appFlags & ApplicationInfo.FLAG_KILL_AFTER_RESTORE) != 0));

                    if (mTargetPackage == null && killAfterRestore) {
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

        @Override
        public void operationComplete(long unusedResult) {
            if (MORE_DEBUG) {
                Slog.i(TAG, "operationComplete() during restore: target="
                        + mCurrentPackage.packageName
                        + " state=" + mState);
            }

            final UnifiedRestoreState nextState;
            switch (mState) {
                case INITIAL:
                    // We've just (manually) restored the PMBA.  It doesn't need the
                    // additional restore-finished callback so we bypass that and go
                    // directly to running the queue.
                    nextState = UnifiedRestoreState.RUNNING_QUEUE;
                    break;

                case RESTORE_KEYVALUE:
                case RESTORE_FULL: {
                    // Okay, we've just heard back from the agent that it's done with
                    // the restore itself.  We now have to send the same agent its
                    // doRestoreFinished() callback, so roll into that state.
                    nextState = UnifiedRestoreState.RESTORE_FINISHED;
                    break;
                }

                case RESTORE_FINISHED: {
                    // Okay, we're done with this package.  Tidy up and go on to the next
                    // app in the queue.
                    int size = (int) mBackupDataName.length();
                    EventLog.writeEvent(EventLogTags.RESTORE_PACKAGE,
                            mCurrentPackage.packageName, size);

                    // Just go back to running the restore queue
                    keyValueAgentCleanup();

                    // If there was widget state associated with this app, get the OS to
                    // incorporate it into current bookeeping and then pass that along to
                    // the app as part of the restore-time work.
                    if (mWidgetData != null) {
                        restoreWidgetData(mCurrentPackage.packageName, mWidgetData);
                    }

                    nextState = UnifiedRestoreState.RUNNING_QUEUE;
                    break;
                }

                default: {
                    // Some kind of horrible semantic error; we're in an unexpected state.
                    // Back off hard and wind up.
                    Slog.e(TAG, "Unexpected restore callback into state " + mState);
                    keyValueAgentErrorCleanup();
                    nextState = UnifiedRestoreState.FINAL;
                    break;
                }
            }

            executeNextState(nextState);
        }

        // A call to agent.doRestore() or agent.doRestoreFinished() has timed out
        @Override
        public void handleTimeout() {
            Slog.e(TAG, "Timeout restoring application " + mCurrentPackage.packageName);
            EventLog.writeEvent(EventLogTags.RESTORE_AGENT_FAILURE,
                    mCurrentPackage.packageName, "restore timeout");
            // Handle like an agent that threw on invocation: wipe it and go on to the next
            keyValueAgentErrorCleanup();
            executeNextState(UnifiedRestoreState.RUNNING_QUEUE);
        }

        void executeNextState(UnifiedRestoreState nextState) {
            if (MORE_DEBUG) Slog.i(TAG, " => executing next step on "
                    + this + " nextState=" + nextState);
            mState = nextState;
            Message msg = mBackupHandler.obtainMessage(MSG_BACKUP_RESTORE_STEP, this);
            mBackupHandler.sendMessage(msg);
        }

        // restore observer support
        void sendStartRestore(int numPackages) {
            if (mObserver != null) {
                try {
                    mObserver.restoreStarting(numPackages);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Restore observer went away: startRestore");
                    mObserver = null;
                }
            }
        }

        void sendOnRestorePackage(String name) {
            if (mObserver != null) {
                if (mObserver != null) {
                    try {
                        mObserver.onUpdate(mCount, name);
                    } catch (RemoteException e) {
                        Slog.d(TAG, "Restore observer died in onUpdate");
                        mObserver = null;
                    }
                }
            }
        }

        void sendEndRestore() {
            if (mObserver != null) {
                try {
                    mObserver.restoreFinished(mStatus);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Restore observer went away: endRestore");
                    mObserver = null;
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

                    if (status == BackupTransport.TRANSPORT_OK) {
                        status = transport.finishBackup();
                    }

                    // Okay, the wipe really happened.  Clean up our local bookkeeping.
                    if (status == BackupTransport.TRANSPORT_OK) {
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
                        Slog.w(TAG, "Init failed on " + transportName + " resched in " + delay);
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
        HashSet<String> targets = new HashSet<String>();
        if (PACKAGE_MANAGER_SENTINEL.equals(packageName)) {
            targets.add(PACKAGE_MANAGER_SENTINEL);
        } else {
            synchronized (mBackupParticipants) {
                int N = mBackupParticipants.size();
                for (int i = 0; i < N; i++) {
                    HashSet<String> s = mBackupParticipants.valueAt(i);
                    if (s != null) {
                        targets.addAll(s);
                    }
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

    // Clear the given package's backup data from the current transport
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
            // found it; fire off the clear request
            if (MORE_DEBUG) Slog.v(TAG, "Found the app - running clear process");
            mBackupHandler.removeMessages(MSG_RETRY_CLEAR);
            synchronized (mQueueLock) {
                final IBackupTransport transport = getTransport(transportName);
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
    public void backupNow() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP, "backupNow");

        if (mPowerManager.isPowerSaveMode()) {
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

    boolean deviceIsProvisioned() {
        final ContentResolver resolver = mContext.getContentResolver();
        return (Settings.Global.getInt(resolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0);
    }

    // Run a *full* backup pass for the given packages, writing the resulting data stream
    // to the supplied file descriptor.  This method is synchronous and does not return
    // to the caller until the backup has been completed.
    //
    // This is the variant used by 'adb backup'; it requires on-screen confirmation
    // by the user because it can be used to offload data over untrusted USB.
    public void fullBackup(ParcelFileDescriptor fd, boolean includeApks,
            boolean includeObbs, boolean includeShared, boolean doWidgets,
            boolean doAllApps, boolean includeSystem, boolean compress, String[] pkgList) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "fullBackup");

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
                Slog.i(TAG, "Full backup not supported before setup");
                return;
            }

            if (DEBUG) Slog.v(TAG, "Requesting full backup: apks=" + includeApks
                    + " obb=" + includeObbs + " shared=" + includeShared + " all=" + doAllApps
                    + " system=" + includeSystem + " pkgs=" + pkgList);
            Slog.i(TAG, "Beginning full backup...");

            FullBackupParams params = new FullBackupParams(fd, includeApks, includeObbs,
                    includeShared, doWidgets, doAllApps, includeSystem, compress, pkgList);
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
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_OTHER,
                    0);

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

    public void fullTransportBackup(String[] pkgNames) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP,
                "fullTransportBackup");

        final int callingUserHandle = UserHandle.getCallingUserId();
        // TODO: http://b/22388012
        if (callingUserHandle != UserHandle.USER_SYSTEM) {
            throw new IllegalStateException("Restore supported only for the device owner");
        }

        if (!fullBackupAllowable(getTransport(mCurrentTransport))) {
            Slog.i(TAG, "Full backup not currently possible -- key/value backup not yet run?");
        } else {
            if (DEBUG) {
                Slog.d(TAG, "fullTransportBackup()");
            }

            final long oldId = Binder.clearCallingIdentity();
            try {
                CountDownLatch latch = new CountDownLatch(1);
                PerformFullTransportBackupTask task = new PerformFullTransportBackupTask(null,
                        pkgNames, false, null, latch, null, false /* userInitiated */);
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

    public void fullRestore(ParcelFileDescriptor fd) {
        mContext.enforceCallingPermission(android.Manifest.permission.BACKUP, "fullRestore");

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
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_OTHER,
                    0);

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
            mContext.startActivityAsUser(confIntent, UserHandle.SYSTEM);
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
        FileOutputStream fout = null;
        try {
            fout = new FileOutputStream(stage);
            fout.write(enable ? 1 : 0);
            fout.close();
            stage.renameTo(enableFile);
            // will be synced immediately by the try-with-resources call to close()
        } catch (IOException|RuntimeException e) {
            // Whoops; looks like we're doomed.  Roll everything out, disabled,
            // including the legacy state.
            Slog.e(TAG, "Unable to record backup enable state; reverting to disabled: "
                    + e.getMessage());

            final ContentResolver r = sInstance.mContext.getContentResolver();
            Settings.Secure.putStringForUser(r,
                    Settings.Secure.BACKUP_ENABLED, null, userId);
            enableFile.delete();
            stage.delete();
        } finally {
            IoUtils.closeQuietly(fout);
        }
    }

    // Enable/disable backups
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
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    // Enable/disable automatic restore of app data at install time
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
    public void setBackupProvisioned(boolean available) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setBackupProvisioned");
        /*
         * This is now a no-op; provisioning is simply the device's own setup state.
         */
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

    public String[] getTransportWhitelist() {
        // No permission check, intentionally.
        String[] whitelist = new String[mTransportWhitelist.size()];
        for (int i = mTransportWhitelist.size() - 1; i >= 0; i--) {
            whitelist[i] = mTransportWhitelist.valueAt(i).flattenToShortString();
        }
        return whitelist;
    }

    // Select which transport to use for the next backup operation.
    public String selectBackupTransport(String transport) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "selectBackupTransport");

        synchronized (mTransports) {
            final long oldId = Binder.clearCallingIdentity();
            try {
                String prevTransport = mCurrentTransport;
                mCurrentTransport = transport;
                Settings.Secure.putString(mContext.getContentResolver(),
                        Settings.Secure.BACKUP_TRANSPORT, transport);
                Slog.v(TAG, "selectBackupTransport() set " + mCurrentTransport
                        + " returning " + prevTransport);
                return prevTransport;
            } finally {
                Binder.restoreCallingIdentity(oldId);
            }
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

    // Supply the manage-data intent for the given transport.
    public Intent getDataManagementIntent(String transportName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getDataManagementIntent");

        synchronized (mTransports) {
            final IBackupTransport transport = mTransports.get(transportName);
            if (transport != null) {
                try {
                    final Intent intent = transport.dataManagementIntent();
                    if (MORE_DEBUG) Slog.d(TAG, "getDataManagementIntent() returning intent "
                            + intent);
                    return intent;
                } catch (RemoteException e) {
                    /* fall through to return null */
                }
            }
        }

        return null;
    }

    // Supply the menu label for affordances that fire the manage-data intent
    // for the given transport.
    public String getDataManagementLabel(String transportName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "getDataManagementLabel");

        synchronized (mTransports) {
            final IBackupTransport transport = mTransports.get(transportName);
            if (transport != null) {
                try {
                    final String text = transport.dataManagementLabel();
                    if (MORE_DEBUG) Slog.d(TAG, "getDataManagementLabel() returning " + text);
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

        boolean skip = false;

        long restoreSet = getAvailableRestoreToken(packageName);
        if (DEBUG) Slog.v(TAG, "restoreAtInstall pkg=" + packageName
                + " token=" + Integer.toHexString(token)
                + " restoreSet=" + Long.toHexString(restoreSet));
        if (restoreSet == 0) {
            if (MORE_DEBUG) Slog.i(TAG, "No restore set");
            skip = true;
        }

        // Do we have a transport to fetch data for us?
        IBackupTransport transport = getTransport(mCurrentTransport);
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
                msg.obj = new RestoreParams(transport, dirName, null,
                        restoreSet, packageName, token);
                mBackupHandler.sendMessage(msg);
            } catch (RemoteException e) {
                // Binding to the transport broke; back off and proceed with the installation.
                Slog.e(TAG, "Unable to contact transport");
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
                Slog.i(TAG, "Restore session requested but one already active");
                return null;
            }
            if (mBackupRunning) {
                Slog.i(TAG, "Restore session requested but currently running backups");
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
                } else {
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

    public boolean isAppEligibleForBackup(String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "isAppEligibleForBackup");
        try {
            PackageInfo packageInfo = mPackageManager.getPackageInfo(packageName,
                    PackageManager.GET_SIGNATURES);
            if (!appIsEligibleForBackup(packageInfo.applicationInfo) ||
                    appIsStopped(packageInfo.applicationInfo)) {
                return false;
            }
            IBackupTransport transport = getTransport(mCurrentTransport);
            if (transport != null) {
                try {
                    return transport.isAppEligibleForBackup(packageInfo,
                        appGetsFullBackup(packageInfo));
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to contact transport");
                }
            }
            // If transport is not present we couldn't tell that the package is not eligible.
            return true;
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    // ----- Restore session -----

    class ActiveRestoreSession extends IRestoreSession.Stub {
        private static final String TAG = "RestoreSession";

        private String mPackageName;
        private IBackupTransport mRestoreTransport = null;
        RestoreSet[] mRestoreSets = null;
        boolean mEnded = false;
        boolean mTimedOut = false;

        ActiveRestoreSession(String packageName, String transport) {
            mPackageName = packageName;
            mRestoreTransport = getTransport(transport);
        }

        public void markTimedOut() {
            mTimedOut = true;
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

            if (mTimedOut) {
                Slog.i(TAG, "Session already timed out");
                return -1;
            }

            long oldId = Binder.clearCallingIdentity();
            try {
                if (mRestoreTransport == null) {
                    Slog.w(TAG, "Null transport getting restore sets");
                    return -1;
                }

                // We know we're doing legit work now, so halt the timeout
                // until we're done.  It gets started again when the result
                // comes in.
                mBackupHandler.removeMessages(MSG_RESTORE_TIMEOUT);

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

            if (mTimedOut) {
                Slog.i(TAG, "Session already timed out");
                return -1;
            }

            if (mRestoreTransport == null || mRestoreSets == null) {
                Slog.e(TAG, "Ignoring restoreAll() with no restore set");
                return -1;
            }

            if (mPackageName != null) {
                Slog.e(TAG, "Ignoring restoreAll() on single-package session");
                return -1;
            }

            String dirName;
            try {
                dirName = mRestoreTransport.transportDirName();
            } catch (RemoteException e) {
                // Transport went AWOL; fail.
                Slog.e(TAG, "Unable to contact transport for restore");
                return -1;
            }

            synchronized (mQueueLock) {
                for (int i = 0; i < mRestoreSets.length; i++) {
                    if (token == mRestoreSets[i].token) {
                        // Real work, so stop the session timeout until we finalize the restore
                        mBackupHandler.removeMessages(MSG_RESTORE_TIMEOUT);

                        long oldId = Binder.clearCallingIdentity();
                        mWakelock.acquire();
                        if (MORE_DEBUG) {
                            Slog.d(TAG, "restoreAll() kicking off");
                        }
                        Message msg = mBackupHandler.obtainMessage(MSG_RUN_RESTORE);
                        msg.obj = new RestoreParams(mRestoreTransport, dirName,
                                observer, token);
                        mBackupHandler.sendMessage(msg);
                        Binder.restoreCallingIdentity(oldId);
                        return 0;
                    }
                }
            }

            Slog.w(TAG, "Restore token " + Long.toHexString(token) + " not found");
            return -1;
        }

        // Restores of more than a single package are treated as 'system' restores
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

            if (mTimedOut) {
                Slog.i(TAG, "Session already timed out");
                return -1;
            }

            if (mRestoreTransport == null || mRestoreSets == null) {
                Slog.e(TAG, "Ignoring restoreAll() with no restore set");
                return -1;
            }

            if (mPackageName != null) {
                Slog.e(TAG, "Ignoring restoreAll() on single-package session");
                return -1;
            }

            String dirName;
            try {
                dirName = mRestoreTransport.transportDirName();
            } catch (RemoteException e) {
                // Transport went AWOL; fail.
                Slog.e(TAG, "Unable to contact transport for restore");
                return -1;
            }

            synchronized (mQueueLock) {
                for (int i = 0; i < mRestoreSets.length; i++) {
                    if (token == mRestoreSets[i].token) {
                        // Stop the session timeout until we finalize the restore
                        mBackupHandler.removeMessages(MSG_RESTORE_TIMEOUT);

                        long oldId = Binder.clearCallingIdentity();
                        mWakelock.acquire();
                        if (MORE_DEBUG) {
                            Slog.d(TAG, "restoreSome() of " + packages.length + " packages");
                        }
                        Message msg = mBackupHandler.obtainMessage(MSG_RUN_RESTORE);
                        msg.obj = new RestoreParams(mRestoreTransport, dirName, observer, token,
                                packages, packages.length > 1);
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

            if (mTimedOut) {
                Slog.i(TAG, "Session already timed out");
                return -1;
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

            // So far so good; we're allowed to try to restore this package.
            long oldId = Binder.clearCallingIdentity();
            try {
                // Check whether there is data for it in the current dataset, falling back
                // to the ancestral dataset if not.
                long token = getAvailableRestoreToken(packageName);
                if (DEBUG) Slog.v(TAG, "restorePackage pkg=" + packageName
                        + " token=" + Long.toHexString(token));

                // If we didn't come up with a place to look -- no ancestral dataset and
                // the app has never been backed up from this device -- there's nothing
                // to do but return failure.
                if (token == 0) {
                    if (DEBUG) Slog.w(TAG, "No data available for this package; not restoring");
                    return -1;
                }

                String dirName;
                try {
                    dirName = mRestoreTransport.transportDirName();
                } catch (RemoteException e) {
                    // Transport went AWOL; fail.
                    Slog.e(TAG, "Unable to contact transport for restore");
                    return -1;
                }

                // Stop the session timeout until we finalize the restore
                mBackupHandler.removeMessages(MSG_RESTORE_TIMEOUT);

                // Ready to go:  enqueue the restore request and claim success
                mWakelock.acquire();
                if (MORE_DEBUG) {
                    Slog.d(TAG, "restorePackage() : " + packageName);
                }
                Message msg = mBackupHandler.obtainMessage(MSG_RUN_RESTORE);
                msg.obj = new RestoreParams(mRestoreTransport, dirName, observer, token, app);
                mBackupHandler.sendMessage(msg);
            } finally {
                Binder.restoreCallingIdentity(oldId);
            }
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
                    mSession.mRestoreTransport = null;
                    mSession.mEnded = true;
                }

                // clean up the BackupManagerImpl side of the bookkeeping
                // and cancel any pending timeout message
                mBackupManager.clearRestoreSession(mSession);
            }
        }

        public synchronized void endRestoreSession() {
            if (DEBUG) Slog.d(TAG, "endRestoreSession");

            if (mTimedOut) {
                Slog.i(TAG, "Session already timed out");
                return;
            }

            if (mEnded) {
                throw new IllegalStateException("Restore session already ended");
            }

            mBackupHandler.post(new EndRestoreRunnable(BackupManagerService.this, this));
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

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
            pw.print(pkg.packageName); pw.println(':');
            pw.print("      "); pw.println(pkg.applicationInfo.backupAgentName);
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
            for (ComponentName transport : mTransportWhitelist) {
                pw.print("    ");
                pw.println(transport.flattenToShortString());
            }

            pw.println("Available transports:");
            final String[] transports = listAllTransports();
            if (transports != null) {
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

            pw.print("Ancestral: "); pw.println(Long.toHexString(mAncestralToken));
            pw.print("Current:   "); pw.println(Long.toHexString(mCurrentToken));

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

            pw.println("Pending key/value backup: " + mPendingBackups.size());
            for (BackupRequest req : mPendingBackups.values()) {
                pw.println("    " + req);
            }

            pw.println("Full backup queue:" + mFullBackupQueue.size());
            for (FullBackupEntry entry : mFullBackupQueue) {
                pw.print("    "); pw.print(entry.lastBackup);
                pw.print(" : "); pw.println(entry.packageName);
            }
        }
    }

    private static void sendBackupOnUpdate(IBackupObserver observer, String packageName,
            BackupProgress progress) {
        if (observer != null) {
            try {
                observer.onUpdate(packageName, progress);
            } catch (RemoteException e) {
                if (DEBUG) {
                    Slog.w(TAG, "Backup observer went away: onUpdate");
                }
            }
        }
    }

    private static void sendBackupOnPackageResult(IBackupObserver observer, String packageName,
            int status) {
        if (observer != null) {
            try {
                observer.onResult(packageName, status);
            } catch (RemoteException e) {
                if (DEBUG) {
                    Slog.w(TAG, "Backup observer went away: onResult");
                }
            }
        }
    }

    private static void sendBackupFinished(IBackupObserver observer, int status) {
        if (observer != null) {
            try {
                observer.backupFinished(status);
            } catch (RemoteException e) {
                if (DEBUG) {
                    Slog.w(TAG, "Backup observer went away: backupFinished");
                }
            }
        }
    }
}
