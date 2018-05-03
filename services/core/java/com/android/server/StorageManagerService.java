/*
 * Copyright (C) 2007 The Android Open Source Project
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

import static android.os.storage.OnObbStateChangeListener.ERROR_ALREADY_MOUNTED;
import static android.os.storage.OnObbStateChangeListener.ERROR_COULD_NOT_MOUNT;
import static android.os.storage.OnObbStateChangeListener.ERROR_COULD_NOT_UNMOUNT;
import static android.os.storage.OnObbStateChangeListener.ERROR_INTERNAL;
import static android.os.storage.OnObbStateChangeListener.ERROR_NOT_MOUNTED;
import static android.os.storage.OnObbStateChangeListener.ERROR_PERMISSION_DENIED;
import static android.os.storage.OnObbStateChangeListener.MOUNTED;
import static android.os.storage.OnObbStateChangeListener.UNMOUNTED;

import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.Manifest;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerInternal.ScreenObserver;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.app.admin.SecurityLog;
import android.app.usage.StorageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.ObbInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IStoraged;
import android.os.IVold;
import android.os.IVoldListener;
import android.os.IVoldTaskListener;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.DiskInfo;
import android.os.storage.IObbActionListener;
import android.os.storage.IStorageEventListener;
import android.os.storage.IStorageManager;
import android.os.storage.IStorageShutdownObserver;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.DataUnit;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IMediaContainerService;
import com.android.internal.os.AppFuseMount;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.FuseUnavailableMountException;
import com.android.internal.os.SomeArgs;
import com.android.internal.os.Zygote;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.HexDump;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.pm.PackageManagerService;
import com.android.server.storage.AppFuseBridge;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Service responsible for various storage media. Connects to {@code vold} to
 * watch for and manage dynamically added storage, such as SD cards and USB mass
 * storage. Also decides how storage should be presented to users on the device.
 */
class StorageManagerService extends IStorageManager.Stub
        implements Watchdog.Monitor, ScreenObserver {

    // Static direct instance pointer for the tightly-coupled idle service to use
    static StorageManagerService sSelf = null;

    /* Read during boot to decide whether to enable zram when available */
    private static final String ZRAM_ENABLED_PROPERTY =
        "persist.sys.zram_enabled";

    public static class Lifecycle extends SystemService {
        private StorageManagerService mStorageManagerService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mStorageManagerService = new StorageManagerService(getContext());
            publishBinderService("mount", mStorageManagerService);
            mStorageManagerService.start();
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mStorageManagerService.systemReady();
            } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
                mStorageManagerService.bootCompleted();
            }
        }

        @Override
        public void onSwitchUser(int userHandle) {
            mStorageManagerService.mCurrentUserId = userHandle;
        }

        @Override
        public void onUnlockUser(int userHandle) {
            mStorageManagerService.onUnlockUser(userHandle);
        }

        @Override
        public void onCleanupUser(int userHandle) {
            mStorageManagerService.onCleanupUser(userHandle);
        }
    }

    private static final boolean DEBUG_EVENTS = false;
    private static final boolean DEBUG_OBB = false;

    // Disable this since it messes up long-running cryptfs operations.
    private static final boolean WATCHDOG_ENABLE = false;

    /**
     * Our goal is for all Android devices to be usable as development devices,
     * which includes the new Direct Boot mode added in N. For devices that
     * don't have native FBE support, we offer an emulation mode for developer
     * testing purposes, but if it's prohibitively difficult to support this
     * mode, it can be disabled for specific products using this flag.
     */
    private static final boolean EMULATE_FBE_SUPPORTED = true;

    private static final String TAG = "StorageManagerService";

    private static final String TAG_STORAGE_BENCHMARK = "storage_benchmark";
    private static final String TAG_STORAGE_TRIM = "storage_trim";

    /** Magic value sent by MoveTask.cpp */
    private static final int MOVE_STATUS_COPY_FINISHED = 82;

    private static final int VERSION_INIT = 1;
    private static final int VERSION_ADD_PRIMARY = 2;
    private static final int VERSION_FIX_PRIMARY = 3;

    private static final String TAG_VOLUMES = "volumes";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_PRIMARY_STORAGE_UUID = "primaryStorageUuid";
    private static final String TAG_VOLUME = "volume";
    private static final String ATTR_TYPE = "type";
    private static final String ATTR_FS_UUID = "fsUuid";
    private static final String ATTR_PART_GUID = "partGuid";
    private static final String ATTR_NICKNAME = "nickname";
    private static final String ATTR_USER_FLAGS = "userFlags";
    private static final String ATTR_CREATED_MILLIS = "createdMillis";
    private static final String ATTR_LAST_TRIM_MILLIS = "lastTrimMillis";
    private static final String ATTR_LAST_BENCH_MILLIS = "lastBenchMillis";

    private final AtomicFile mSettingsFile;

    /**
     * <em>Never</em> hold the lock while performing downcalls into vold, since
     * unsolicited events can suddenly appear to update data structures.
     */
    private final Object mLock = LockGuard.installNewLock(LockGuard.INDEX_STORAGE);

    /** Set of users that we know are unlocked. */
    @GuardedBy("mLock")
    private int[] mLocalUnlockedUsers = EmptyArray.INT;
    /** Set of users that system knows are unlocked. */
    @GuardedBy("mLock")
    private int[] mSystemUnlockedUsers = EmptyArray.INT;

    /** Map from disk ID to disk */
    @GuardedBy("mLock")
    private ArrayMap<String, DiskInfo> mDisks = new ArrayMap<>();
    /** Map from volume ID to disk */
    @GuardedBy("mLock")
    private final ArrayMap<String, VolumeInfo> mVolumes = new ArrayMap<>();

    /** Map from UUID to record */
    @GuardedBy("mLock")
    private ArrayMap<String, VolumeRecord> mRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private String mPrimaryStorageUuid;

    /** Map from disk ID to latches */
    @GuardedBy("mLock")
    private ArrayMap<String, CountDownLatch> mDiskScanLatches = new ArrayMap<>();

    @GuardedBy("mLock")
    private IPackageMoveObserver mMoveCallback;
    @GuardedBy("mLock")
    private String mMoveTargetUuid;

    private volatile int mCurrentUserId = UserHandle.USER_SYSTEM;

    /** Holding lock for AppFuse business */
    private final Object mAppFuseLock = new Object();

    @GuardedBy("mAppFuseLock")
    private int mNextAppFuseName = 0;

    @GuardedBy("mAppFuseLock")
    private AppFuseBridge mAppFuseBridge = null;

    private VolumeInfo findVolumeByIdOrThrow(String id) {
        synchronized (mLock) {
            final VolumeInfo vol = mVolumes.get(id);
            if (vol != null) {
                return vol;
            }
        }
        throw new IllegalArgumentException("No volume found for ID " + id);
    }

    private String findVolumeIdForPathOrThrow(String path) {
        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (vol.path != null && path.startsWith(vol.path)) {
                    return vol.id;
                }
            }
        }
        throw new IllegalArgumentException("No volume found for path " + path);
    }

    private VolumeRecord findRecordForPath(String path) {
        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (vol.path != null && path.startsWith(vol.path)) {
                    return mRecords.get(vol.fsUuid);
                }
            }
        }
        return null;
    }

    private String scrubPath(String path) {
        if (path.startsWith(Environment.getDataDirectory().getAbsolutePath())) {
            return "internal";
        }
        final VolumeRecord rec = findRecordForPath(path);
        if (rec == null || rec.createdMillis == 0) {
            return "unknown";
        } else {
            return "ext:" + (int) ((System.currentTimeMillis() - rec.createdMillis)
                    / DateUtils.WEEK_IN_MILLIS) + "w";
        }
    }

    private @Nullable VolumeInfo findStorageForUuid(String volumeUuid) {
        final StorageManager storage = mContext.getSystemService(StorageManager.class);
        if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, volumeUuid)) {
            return storage.findVolumeById(VolumeInfo.ID_EMULATED_INTERNAL);
        } else if (Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL, volumeUuid)) {
            return storage.getPrimaryPhysicalVolume();
        } else {
            return storage.findEmulatedForPrivate(storage.findVolumeByUuid(volumeUuid));
        }
    }

    private boolean shouldBenchmark() {
        final long benchInterval = Settings.Global.getLong(mContext.getContentResolver(),
                Settings.Global.STORAGE_BENCHMARK_INTERVAL, DateUtils.WEEK_IN_MILLIS);
        if (benchInterval == -1) {
            return false;
        } else if (benchInterval == 0) {
            return true;
        }

        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                final VolumeRecord rec = mRecords.get(vol.fsUuid);
                if (vol.isMountedWritable() && rec != null) {
                    final long benchAge = System.currentTimeMillis() - rec.lastBenchMillis;
                    if (benchAge >= benchInterval) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private CountDownLatch findOrCreateDiskScanLatch(String diskId) {
        synchronized (mLock) {
            CountDownLatch latch = mDiskScanLatches.get(diskId);
            if (latch == null) {
                latch = new CountDownLatch(1);
                mDiskScanLatches.put(diskId, latch);
            }
            return latch;
        }
    }

    /** List of crypto types.
      * These must match CRYPT_TYPE_XXX in cryptfs.h AND their
      * corresponding commands in CommandListener.cpp */
    public static final String[] CRYPTO_TYPES
        = { "password", "default", "pattern", "pin" };

    private final Context mContext;

    private volatile IVold mVold;
    private volatile IStoraged mStoraged;

    private volatile boolean mSystemReady = false;
    private volatile boolean mBootCompleted = false;
    private volatile boolean mDaemonConnected = false;
    private volatile boolean mSecureKeyguardShowing = true;

    private PackageManagerService mPms;

    private final Callbacks mCallbacks;
    private final LockPatternUtils mLockPatternUtils;

    /**
     * The size of the crypto algorithm key in bits for OBB files. Currently
     * Twofish is used which takes 128-bit keys.
     */
    private static final int CRYPTO_ALGORITHM_KEY_SIZE = 128;

    /**
     * The number of times to run SHA1 in the PBKDF2 function for OBB files.
     * 1024 is reasonably secure and not too slow.
     */
    private static final int PBKDF2_HASH_ROUNDS = 1024;

    /**
     * Mounted OBB tracking information. Used to track the current state of all
     * OBBs.
     */
    final private Map<IBinder, List<ObbState>> mObbMounts = new HashMap<IBinder, List<ObbState>>();

    /** Map from raw paths to {@link ObbState}. */
    final private Map<String, ObbState> mObbPathToStateMap = new HashMap<String, ObbState>();

    // Not guarded by a lock.
    private final StorageManagerInternalImpl mStorageManagerInternal
            = new StorageManagerInternalImpl();

    class ObbState implements IBinder.DeathRecipient {
        public ObbState(String rawPath, String canonicalPath, int callingUid,
                IObbActionListener token, int nonce, String volId) {
            this.rawPath = rawPath;
            this.canonicalPath = canonicalPath;
            this.ownerGid = UserHandle.getSharedAppGid(callingUid);
            this.token = token;
            this.nonce = nonce;
            this.volId = volId;
        }

        final String rawPath;
        final String canonicalPath;

        final int ownerGid;

        // Token of remote Binder caller
        final IObbActionListener token;

        // Identifier to pass back to the token
        final int nonce;

        String volId;

        public IBinder getBinder() {
            return token.asBinder();
        }

        @Override
        public void binderDied() {
            ObbAction action = new UnmountObbAction(this, true);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));
        }

        public void link() throws RemoteException {
            getBinder().linkToDeath(this, 0);
        }

        public void unlink() {
            getBinder().unlinkToDeath(this, 0);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ObbState{");
            sb.append("rawPath=").append(rawPath);
            sb.append(",canonicalPath=").append(canonicalPath);
            sb.append(",ownerGid=").append(ownerGid);
            sb.append(",token=").append(token);
            sb.append(",binder=").append(getBinder());
            sb.append(",volId=").append(volId);
            sb.append('}');
            return sb.toString();
        }
    }

    // OBB Action Handler
    final private ObbActionHandler mObbActionHandler;

    // OBB action handler messages
    private static final int OBB_RUN_ACTION = 1;
    private static final int OBB_MCS_BOUND = 2;
    private static final int OBB_MCS_UNBIND = 3;
    private static final int OBB_MCS_RECONNECT = 4;
    private static final int OBB_FLUSH_MOUNT_STATE = 5;

    /*
     * Default Container Service information
     */
    static final ComponentName DEFAULT_CONTAINER_COMPONENT = new ComponentName(
            "com.android.defcontainer", "com.android.defcontainer.DefaultContainerService");

    final private DefaultContainerConnection mDefContainerConn = new DefaultContainerConnection();

    class DefaultContainerConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG_OBB)
                Slog.i(TAG, "onServiceConnected");
            IMediaContainerService imcs = IMediaContainerService.Stub.asInterface(service);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_MCS_BOUND, imcs));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG_OBB)
                Slog.i(TAG, "onServiceDisconnected");
        }
    }

    // Used in the ObbActionHandler
    private IMediaContainerService mContainerService = null;

    // Last fstrim operation tracking
    private static final String LAST_FSTRIM_FILE = "last-fstrim";
    private final File mLastMaintenanceFile;
    private long mLastMaintenance;

    // Handler messages
    private static final int H_SYSTEM_READY = 1;
    private static final int H_DAEMON_CONNECTED = 2;
    private static final int H_SHUTDOWN = 3;
    private static final int H_FSTRIM = 4;
    private static final int H_VOLUME_MOUNT = 5;
    private static final int H_VOLUME_BROADCAST = 6;
    private static final int H_INTERNAL_BROADCAST = 7;
    private static final int H_VOLUME_UNMOUNT = 8;
    private static final int H_PARTITION_FORGET = 9;
    private static final int H_RESET = 10;
    private static final int H_RUN_IDLE_MAINT = 11;
    private static final int H_ABORT_IDLE_MAINT = 12;

    class StorageManagerServiceHandler extends Handler {
        public StorageManagerServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case H_SYSTEM_READY: {
                    handleSystemReady();
                    break;
                }
                case H_DAEMON_CONNECTED: {
                    handleDaemonConnected();
                    break;
                }
                case H_FSTRIM: {
                    Slog.i(TAG, "Running fstrim idle maintenance");

                    // Remember when we kicked it off
                    try {
                        mLastMaintenance = System.currentTimeMillis();
                        mLastMaintenanceFile.setLastModified(mLastMaintenance);
                    } catch (Exception e) {
                        Slog.e(TAG, "Unable to record last fstrim!");
                    }

                    // TODO: Reintroduce shouldBenchmark() test
                    fstrim(0, null);

                    // invoke the completion callback, if any
                    // TODO: fstrim is non-blocking, so remove this useless callback
                    Runnable callback = (Runnable) msg.obj;
                    if (callback != null) {
                        callback.run();
                    }
                    break;
                }
                case H_SHUTDOWN: {
                    final IStorageShutdownObserver obs = (IStorageShutdownObserver) msg.obj;
                    boolean success = false;
                    try {
                        mVold.shutdown();
                        success = true;
                    } catch (Exception e) {
                        Slog.wtf(TAG, e);
                    }
                    if (obs != null) {
                        try {
                            obs.onShutDownComplete(success ? 0 : -1);
                        } catch (Exception ignored) {
                        }
                    }
                    break;
                }
                case H_VOLUME_MOUNT: {
                    final VolumeInfo vol = (VolumeInfo) msg.obj;
                    if (isMountDisallowed(vol)) {
                        Slog.i(TAG, "Ignoring mount " + vol.getId() + " due to policy");
                        break;
                    }
                    try {
                        mVold.mount(vol.id, vol.mountFlags, vol.mountUserId);
                    } catch (Exception e) {
                        Slog.wtf(TAG, e);
                    }
                    break;
                }
                case H_VOLUME_UNMOUNT: {
                    final VolumeInfo vol = (VolumeInfo) msg.obj;
                    unmount(vol.getId());
                    break;
                }
                case H_VOLUME_BROADCAST: {
                    final StorageVolume userVol = (StorageVolume) msg.obj;
                    final String envState = userVol.getState();
                    Slog.d(TAG, "Volume " + userVol.getId() + " broadcasting " + envState + " to "
                            + userVol.getOwner());

                    final String action = VolumeInfo.getBroadcastForEnvironment(envState);
                    if (action != null) {
                        final Intent intent = new Intent(action,
                                Uri.fromFile(userVol.getPathFile()));
                        intent.putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, userVol);
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                        mContext.sendBroadcastAsUser(intent, userVol.getOwner());
                    }
                    break;
                }
                case H_INTERNAL_BROADCAST: {
                    // Internal broadcasts aimed at system components, not for
                    // third-party apps.
                    final Intent intent = (Intent) msg.obj;
                    mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                            android.Manifest.permission.WRITE_MEDIA_STORAGE);
                    break;
                }
                case H_PARTITION_FORGET: {
                    final VolumeRecord rec = (VolumeRecord) msg.obj;
                    forgetPartition(rec.partGuid, rec.fsUuid);
                    break;
                }
                case H_RESET: {
                    resetIfReadyAndConnected();
                    break;
                }
                case H_RUN_IDLE_MAINT: {
                    Slog.i(TAG, "Running idle maintenance");
                    runIdleMaint((Runnable)msg.obj);
                    break;
                }
                case H_ABORT_IDLE_MAINT: {
                    Slog.i(TAG, "Aborting idle maintenance");
                    abortIdleMaint((Runnable)msg.obj);
                    break;
                }

            }
        }
    }

    private final Handler mHandler;

    private BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            Preconditions.checkArgument(userId >= 0);

            try {
                if (Intent.ACTION_USER_ADDED.equals(action)) {
                    final UserManager um = mContext.getSystemService(UserManager.class);
                    final int userSerialNumber = um.getUserSerialNumber(userId);
                    mVold.onUserAdded(userId, userSerialNumber);
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    synchronized (mVolumes) {
                        final int size = mVolumes.size();
                        for (int i = 0; i < size; i++) {
                            final VolumeInfo vol = mVolumes.valueAt(i);
                            if (vol.mountUserId == userId) {
                                vol.mountUserId = UserHandle.USER_NULL;
                                mHandler.obtainMessage(H_VOLUME_UNMOUNT, vol).sendToTarget();
                            }
                        }
                    }
                    mVold.onUserRemoved(userId);
                }
            } catch (Exception e) {
                Slog.wtf(TAG, e);
            }
        }
    };

    private void waitForLatch(CountDownLatch latch, String condition, long timeoutMillis)
            throws TimeoutException {
        final long startMillis = SystemClock.elapsedRealtime();
        while (true) {
            try {
                if (latch.await(5000, TimeUnit.MILLISECONDS)) {
                    return;
                } else {
                    Slog.w(TAG, "Thread " + Thread.currentThread().getName()
                            + " still waiting for " + condition + "...");
                }
            } catch (InterruptedException e) {
                Slog.w(TAG, "Interrupt while waiting for " + condition);
            }
            if (timeoutMillis > 0 && SystemClock.elapsedRealtime() > startMillis + timeoutMillis) {
                throw new TimeoutException("Thread " + Thread.currentThread().getName()
                        + " gave up waiting for " + condition + " after " + timeoutMillis + "ms");
            }
        }
    }

    private void handleSystemReady() {
        initIfReadyAndConnected();
        resetIfReadyAndConnected();

        // Start scheduling nominally-daily fstrim operations
        MountServiceIdler.scheduleIdlePass(mContext);

        // Toggle zram-enable system property in response to settings
        mContext.getContentResolver().registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ZRAM_ENABLED),
            false /*notifyForDescendants*/,
            new ContentObserver(null /* current thread */) {
                @Override
                public void onChange(boolean selfChange) {
                    refreshZramSettings();
                }
            });
        refreshZramSettings();
    }

    /**
     * Update the zram_enabled system property (which init reads to
     * decide whether to enable zram) to reflect the zram_enabled
     * preference (which we can change for experimentation purposes).
     */
    private void refreshZramSettings() {
        String propertyValue = SystemProperties.get(ZRAM_ENABLED_PROPERTY);
        if ("".equals(propertyValue)) {
            return;  // System doesn't have zram toggling support
        }
        String desiredPropertyValue =
            Settings.Global.getInt(mContext.getContentResolver(),
                                   Settings.Global.ZRAM_ENABLED,
                                   1) != 0
            ? "1" : "0";
        if (!desiredPropertyValue.equals(propertyValue)) {
            // Avoid redundant disk writes by setting only if we're
            // changing the property value. There's no race: we're the
            // sole writer.
            SystemProperties.set(ZRAM_ENABLED_PROPERTY, desiredPropertyValue);
        }
    }

    /**
     * MediaProvider has a ton of code that makes assumptions about storage
     * paths never changing, so we outright kill them to pick up new state.
     */
    @Deprecated
    private void killMediaProvider(List<UserInfo> users) {
        if (users == null) return;

        final long token = Binder.clearCallingIdentity();
        try {
            for (UserInfo user : users) {
                // System user does not have media provider, so skip.
                if (user.isSystemOnly()) continue;

                final ProviderInfo provider = mPms.resolveContentProvider(MediaStore.AUTHORITY,
                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        user.id);
                if (provider != null) {
                    final IActivityManager am = ActivityManager.getService();
                    try {
                        am.killApplication(provider.applicationInfo.packageName,
                                UserHandle.getAppId(provider.applicationInfo.uid),
                                UserHandle.USER_ALL, "vold reset");
                        // We only need to run this once. It will kill all users' media processes.
                        break;
                    } catch (RemoteException e) {
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @GuardedBy("mLock")
    private void addInternalVolumeLocked() {
        // Create a stub volume that represents internal storage
        final VolumeInfo internal = new VolumeInfo(VolumeInfo.ID_PRIVATE_INTERNAL,
                VolumeInfo.TYPE_PRIVATE, null, null);
        internal.state = VolumeInfo.STATE_MOUNTED;
        internal.path = Environment.getDataDirectory().getAbsolutePath();
        mVolumes.put(internal.id, internal);
    }

    private void initIfReadyAndConnected() {
        Slog.d(TAG, "Thinking about init, mSystemReady=" + mSystemReady
                + ", mDaemonConnected=" + mDaemonConnected);
        if (mSystemReady && mDaemonConnected
                && !StorageManager.isFileEncryptedNativeOnly()) {
            // When booting a device without native support, make sure that our
            // user directories are locked or unlocked based on the current
            // emulation status.
            final boolean initLocked = StorageManager.isFileEncryptedEmulatedOnly();
            Slog.d(TAG, "Setting up emulation state, initlocked=" + initLocked);
            final List<UserInfo> users = mContext.getSystemService(UserManager.class).getUsers();
            for (UserInfo user : users) {
                try {
                    if (initLocked) {
                        mVold.lockUserKey(user.id);
                    } else {
                        mVold.unlockUserKey(user.id, user.serialNumber, encodeBytes(null),
                                encodeBytes(null));
                    }
                } catch (Exception e) {
                    Slog.wtf(TAG, e);
                }
            }
        }
    }

    private void resetIfReadyAndConnected() {
        Slog.d(TAG, "Thinking about reset, mSystemReady=" + mSystemReady
                + ", mDaemonConnected=" + mDaemonConnected);
        if (mSystemReady && mDaemonConnected) {
            final List<UserInfo> users = mContext.getSystemService(UserManager.class).getUsers();
            killMediaProvider(users);

            final int[] systemUnlockedUsers;
            synchronized (mLock) {
                systemUnlockedUsers = mSystemUnlockedUsers;

                mDisks.clear();
                mVolumes.clear();

                addInternalVolumeLocked();
            }

            try {
                mVold.reset();

                // Tell vold about all existing and started users
                for (UserInfo user : users) {
                    mVold.onUserAdded(user.id, user.serialNumber);
                }
                for (int userId : systemUnlockedUsers) {
                    mVold.onUserStarted(userId);
                    mStoraged.onUserStarted(userId);
                }
                mVold.onSecureKeyguardStateChanged(mSecureKeyguardShowing);
            } catch (Exception e) {
                Slog.wtf(TAG, e);
            }
        }
    }

    private void onUnlockUser(int userId) {
        Slog.d(TAG, "onUnlockUser " + userId);

        // We purposefully block here to make sure that user-specific
        // staging area is ready so it's ready for zygote-forked apps to
        // bind mount against.
        try {
            mVold.onUserStarted(userId);
            mStoraged.onUserStarted(userId);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }

        // Record user as started so newly mounted volumes kick off events
        // correctly, then synthesize events for any already-mounted volumes.
        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (vol.isVisibleForRead(userId) && vol.isMountedReadable()) {
                    final StorageVolume userVol = vol.buildStorageVolume(mContext, userId, false);
                    mHandler.obtainMessage(H_VOLUME_BROADCAST, userVol).sendToTarget();

                    final String envState = VolumeInfo.getEnvironmentForState(vol.getState());
                    mCallbacks.notifyStorageStateChanged(userVol.getPath(), envState, envState);
                }
            }
            mSystemUnlockedUsers = ArrayUtils.appendInt(mSystemUnlockedUsers, userId);
        }
    }

    private void onCleanupUser(int userId) {
        Slog.d(TAG, "onCleanupUser " + userId);

        try {
            mVold.onUserStopped(userId);
            mStoraged.onUserStopped(userId);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }

        synchronized (mLock) {
            mSystemUnlockedUsers = ArrayUtils.removeInt(mSystemUnlockedUsers, userId);
        }
    }

    @Override
    public void onAwakeStateChanged(boolean isAwake) {
        // Ignored
    }

    @Override
    public void onKeyguardStateChanged(boolean isShowing) {
        // Push down current secure keyguard status so that we ignore malicious
        // USB devices while locked.
        mSecureKeyguardShowing = isShowing
                && mContext.getSystemService(KeyguardManager.class).isDeviceSecure();
        try {
            mVold.onSecureKeyguardStateChanged(mSecureKeyguardShowing);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    void runIdleMaintenance(Runnable callback) {
        mHandler.sendMessage(mHandler.obtainMessage(H_FSTRIM, callback));
    }

    // Binder entry point for kicking off an immediate fstrim
    @Override
    public void runMaintenance() {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        runIdleMaintenance(null);
    }

    @Override
    public long lastMaintenance() {
        return mLastMaintenance;
    }

    public void onDaemonConnected() {
        mDaemonConnected = true;
        mHandler.obtainMessage(H_DAEMON_CONNECTED).sendToTarget();
    }

    private void handleDaemonConnected() {
        initIfReadyAndConnected();
        resetIfReadyAndConnected();

        // On an encrypted device we can't see system properties yet, so pull
        // the system locale out of the mount service.
        if ("".equals(SystemProperties.get("vold.encrypt_progress"))) {
            copyLocaleFromMountService();
        }
    }

    private void copyLocaleFromMountService() {
        String systemLocale;
        try {
            systemLocale = getField(StorageManager.SYSTEM_LOCALE_KEY);
        } catch (RemoteException e) {
            return;
        }
        if (TextUtils.isEmpty(systemLocale)) {
            return;
        }

        Slog.d(TAG, "Got locale " + systemLocale + " from mount service");
        Locale locale = Locale.forLanguageTag(systemLocale);
        Configuration config = new Configuration();
        config.setLocale(locale);
        try {
            ActivityManager.getService().updatePersistentConfiguration(config);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error setting system locale from mount service", e);
        }

        // Temporary workaround for http://b/17945169.
        Slog.d(TAG, "Setting system properties to " + systemLocale + " from mount service");
        SystemProperties.set("persist.sys.locale", locale.toLanguageTag());
    }

    private final IVoldListener mListener = new IVoldListener.Stub() {
        @Override
        public void onDiskCreated(String diskId, int flags) {
            synchronized (mLock) {
                final String value = SystemProperties.get(StorageManager.PROP_ADOPTABLE);
                switch (value) {
                    case "force_on":
                        flags |= DiskInfo.FLAG_ADOPTABLE;
                        break;
                    case "force_off":
                        flags &= ~DiskInfo.FLAG_ADOPTABLE;
                        break;
                }
                mDisks.put(diskId, new DiskInfo(diskId, flags));
            }
        }

        @Override
        public void onDiskScanned(String diskId) {
            synchronized (mLock) {
                final DiskInfo disk = mDisks.get(diskId);
                if (disk != null) {
                    onDiskScannedLocked(disk);
                }
            }
        }

        @Override
        public void onDiskMetadataChanged(String diskId, long sizeBytes, String label,
                String sysPath) {
            synchronized (mLock) {
                final DiskInfo disk = mDisks.get(diskId);
                if (disk != null) {
                    disk.size = sizeBytes;
                    disk.label = label;
                    disk.sysPath = sysPath;
                }
            }
        }

        @Override
        public void onDiskDestroyed(String diskId) {
            synchronized (mLock) {
                final DiskInfo disk = mDisks.remove(diskId);
                if (disk != null) {
                    mCallbacks.notifyDiskDestroyed(disk);
                }
            }
        }

        @Override
        public void onVolumeCreated(String volId, int type, String diskId, String partGuid) {
            synchronized (mLock) {
                final DiskInfo disk = mDisks.get(diskId);
                final VolumeInfo vol = new VolumeInfo(volId, type, disk, partGuid);
                mVolumes.put(volId, vol);
                onVolumeCreatedLocked(vol);
            }
        }

        @Override
        public void onVolumeStateChanged(String volId, int state) {
            synchronized (mLock) {
                final VolumeInfo vol = mVolumes.get(volId);
                if (vol != null) {
                    final int oldState = vol.state;
                    final int newState = state;
                    vol.state = newState;
                    onVolumeStateChangedLocked(vol, oldState, newState);
                }
            }
        }

        @Override
        public void onVolumeMetadataChanged(String volId, String fsType, String fsUuid,
                String fsLabel) {
            synchronized (mLock) {
                final VolumeInfo vol = mVolumes.get(volId);
                if (vol != null) {
                    vol.fsType = fsType;
                    vol.fsUuid = fsUuid;
                    vol.fsLabel = fsLabel;
                }
            }
        }

        @Override
        public void onVolumePathChanged(String volId, String path) {
            synchronized (mLock) {
                final VolumeInfo vol = mVolumes.get(volId);
                if (vol != null) {
                    vol.path = path;
                }
            }
        }

        @Override
        public void onVolumeInternalPathChanged(String volId, String internalPath) {
            synchronized (mLock) {
                final VolumeInfo vol = mVolumes.get(volId);
                if (vol != null) {
                    vol.internalPath = internalPath;
                }
            }
        }

        @Override
        public void onVolumeDestroyed(String volId) {
            synchronized (mLock) {
                mVolumes.remove(volId);
            }
        }
    };

    @GuardedBy("mLock")
    private void onDiskScannedLocked(DiskInfo disk) {
        int volumeCount = 0;
        for (int i = 0; i < mVolumes.size(); i++) {
            final VolumeInfo vol = mVolumes.valueAt(i);
            if (Objects.equals(disk.id, vol.getDiskId())) {
                volumeCount++;
            }
        }

        final Intent intent = new Intent(DiskInfo.ACTION_DISK_SCANNED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.putExtra(DiskInfo.EXTRA_DISK_ID, disk.id);
        intent.putExtra(DiskInfo.EXTRA_VOLUME_COUNT, volumeCount);
        mHandler.obtainMessage(H_INTERNAL_BROADCAST, intent).sendToTarget();

        final CountDownLatch latch = mDiskScanLatches.remove(disk.id);
        if (latch != null) {
            latch.countDown();
        }

        disk.volumeCount = volumeCount;
        mCallbacks.notifyDiskScanned(disk, volumeCount);
    }

    @GuardedBy("mLock")
    private void onVolumeCreatedLocked(VolumeInfo vol) {
        if (mPms.isOnlyCoreApps()) {
            Slog.d(TAG, "System booted in core-only mode; ignoring volume " + vol.getId());
            return;
        }

        if (vol.type == VolumeInfo.TYPE_EMULATED) {
            final StorageManager storage = mContext.getSystemService(StorageManager.class);
            final VolumeInfo privateVol = storage.findPrivateForEmulated(vol);

            if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, mPrimaryStorageUuid)
                    && VolumeInfo.ID_PRIVATE_INTERNAL.equals(privateVol.id)) {
                Slog.v(TAG, "Found primary storage at " + vol);
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_PRIMARY;
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE;
                mHandler.obtainMessage(H_VOLUME_MOUNT, vol).sendToTarget();

            } else if (Objects.equals(privateVol.fsUuid, mPrimaryStorageUuid)) {
                Slog.v(TAG, "Found primary storage at " + vol);
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_PRIMARY;
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE;
                mHandler.obtainMessage(H_VOLUME_MOUNT, vol).sendToTarget();
            }

        } else if (vol.type == VolumeInfo.TYPE_PUBLIC) {
            // TODO: only look at first public partition
            if (Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL, mPrimaryStorageUuid)
                    && vol.disk.isDefaultPrimary()) {
                Slog.v(TAG, "Found primary storage at " + vol);
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_PRIMARY;
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE;
            }

            // Adoptable public disks are visible to apps, since they meet
            // public API requirement of being in a stable location.
            if (vol.disk.isAdoptable()) {
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE;
            }

            vol.mountUserId = mCurrentUserId;
            mHandler.obtainMessage(H_VOLUME_MOUNT, vol).sendToTarget();

        } else if (vol.type == VolumeInfo.TYPE_PRIVATE) {
            mHandler.obtainMessage(H_VOLUME_MOUNT, vol).sendToTarget();

        } else {
            Slog.d(TAG, "Skipping automatic mounting of " + vol);
        }
    }

    private boolean isBroadcastWorthy(VolumeInfo vol) {
        switch (vol.getType()) {
            case VolumeInfo.TYPE_PRIVATE:
            case VolumeInfo.TYPE_PUBLIC:
            case VolumeInfo.TYPE_EMULATED:
                break;
            default:
                return false;
        }

        switch (vol.getState()) {
            case VolumeInfo.STATE_MOUNTED:
            case VolumeInfo.STATE_MOUNTED_READ_ONLY:
            case VolumeInfo.STATE_EJECTING:
            case VolumeInfo.STATE_UNMOUNTED:
            case VolumeInfo.STATE_UNMOUNTABLE:
            case VolumeInfo.STATE_BAD_REMOVAL:
                break;
            default:
                return false;
        }

        return true;
    }

    @GuardedBy("mLock")
    private void onVolumeStateChangedLocked(VolumeInfo vol, int oldState, int newState) {
        // Remember that we saw this volume so we're ready to accept user
        // metadata, or so we can annoy them when a private volume is ejected
        if (vol.isMountedReadable() && !TextUtils.isEmpty(vol.fsUuid)) {
            VolumeRecord rec = mRecords.get(vol.fsUuid);
            if (rec == null) {
                rec = new VolumeRecord(vol.type, vol.fsUuid);
                rec.partGuid = vol.partGuid;
                rec.createdMillis = System.currentTimeMillis();
                if (vol.type == VolumeInfo.TYPE_PRIVATE) {
                    rec.nickname = vol.disk.getDescription();
                }
                mRecords.put(rec.fsUuid, rec);
                writeSettingsLocked();
            } else {
                // Handle upgrade case where we didn't store partition GUID
                if (TextUtils.isEmpty(rec.partGuid)) {
                    rec.partGuid = vol.partGuid;
                    writeSettingsLocked();
                }
            }
        }

        mCallbacks.notifyVolumeStateChanged(vol, oldState, newState);

        // Do not broadcast before boot has completed to avoid launching the
        // processes that receive the intent unnecessarily.
        if (mBootCompleted && isBroadcastWorthy(vol)) {
            final Intent intent = new Intent(VolumeInfo.ACTION_VOLUME_STATE_CHANGED);
            intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.id);
            intent.putExtra(VolumeInfo.EXTRA_VOLUME_STATE, newState);
            intent.putExtra(VolumeRecord.EXTRA_FS_UUID, vol.fsUuid);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                    | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            mHandler.obtainMessage(H_INTERNAL_BROADCAST, intent).sendToTarget();
        }

        final String oldStateEnv = VolumeInfo.getEnvironmentForState(oldState);
        final String newStateEnv = VolumeInfo.getEnvironmentForState(newState);

        if (!Objects.equals(oldStateEnv, newStateEnv)) {
            // Kick state changed event towards all started users. Any users
            // started after this point will trigger additional
            // user-specific broadcasts.
            for (int userId : mSystemUnlockedUsers) {
                if (vol.isVisibleForRead(userId)) {
                    final StorageVolume userVol = vol.buildStorageVolume(mContext, userId, false);
                    mHandler.obtainMessage(H_VOLUME_BROADCAST, userVol).sendToTarget();

                    mCallbacks.notifyStorageStateChanged(userVol.getPath(), oldStateEnv,
                            newStateEnv);
                }
            }
        }

        if (vol.type == VolumeInfo.TYPE_PUBLIC && vol.state == VolumeInfo.STATE_EJECTING) {
            // TODO: this should eventually be handled by new ObbVolume state changes
            /*
             * Some OBBs might have been unmounted when this volume was
             * unmounted, so send a message to the handler to let it know to
             * remove those from the list of mounted OBBS.
             */
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(
                    OBB_FLUSH_MOUNT_STATE, vol.path));
        }
        maybeLogMediaMount(vol, newState);
    }

    private void maybeLogMediaMount(VolumeInfo vol, int newState) {
        if (!SecurityLog.isLoggingEnabled()) {
            return;
        }

        final DiskInfo disk = vol.getDisk();
        if (disk == null || (disk.flags & (DiskInfo.FLAG_SD | DiskInfo.FLAG_USB)) == 0) {
            return;
        }

        // Sometimes there is a newline character.
        final String label = disk.label != null ? disk.label.trim() : "";

        if (newState == VolumeInfo.STATE_MOUNTED
                || newState == VolumeInfo.STATE_MOUNTED_READ_ONLY) {
            SecurityLog.writeEvent(SecurityLog.TAG_MEDIA_MOUNT, vol.path, label);
        } else if (newState == VolumeInfo.STATE_UNMOUNTED
                || newState == VolumeInfo.STATE_BAD_REMOVAL) {
            SecurityLog.writeEvent(SecurityLog.TAG_MEDIA_UNMOUNT, vol.path, label);
        }
    }

    @GuardedBy("mLock")
    private void onMoveStatusLocked(int status) {
        if (mMoveCallback == null) {
            Slog.w(TAG, "Odd, status but no move requested");
            return;
        }

        // TODO: estimate remaining time
        try {
            mMoveCallback.onStatusChanged(-1, status, -1);
        } catch (RemoteException ignored) {
        }

        // We've finished copying and we're about to clean up old data, so
        // remember that move was successful if we get rebooted
        if (status == MOVE_STATUS_COPY_FINISHED) {
            Slog.d(TAG, "Move to " + mMoveTargetUuid + " copy phase finshed; persisting");

            mPrimaryStorageUuid = mMoveTargetUuid;
            writeSettingsLocked();
        }

        if (PackageManager.isMoveStatusFinished(status)) {
            Slog.d(TAG, "Move to " + mMoveTargetUuid + " finished with status " + status);

            mMoveCallback = null;
            mMoveTargetUuid = null;
        }
    }

    private void enforcePermission(String perm) {
        mContext.enforceCallingOrSelfPermission(perm, perm);
    }

    /**
     * Decide if volume is mountable per device policies.
     */
    private boolean isMountDisallowed(VolumeInfo vol) {
        UserManager userManager = mContext.getSystemService(UserManager.class);

        boolean isUsbRestricted = false;
        if (vol.disk != null && vol.disk.isUsb()) {
            isUsbRestricted = userManager.hasUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER,
                    Binder.getCallingUserHandle());
        }

        boolean isTypeRestricted = false;
        if (vol.type == VolumeInfo.TYPE_PUBLIC || vol.type == VolumeInfo.TYPE_PRIVATE) {
            isTypeRestricted = userManager
                    .hasUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
                    Binder.getCallingUserHandle());
        }

        return isUsbRestricted || isTypeRestricted;
    }

    private void enforceAdminUser() {
        UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        final int callingUserId = UserHandle.getCallingUserId();
        boolean isAdmin;
        long token = Binder.clearCallingIdentity();
        try {
            isAdmin = um.getUserInfo(callingUserId).isAdmin();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (!isAdmin) {
            throw new SecurityException("Only admin users can adopt sd cards");
        }
    }

    /**
     * Constructs a new StorageManagerService instance
     *
     * @param context  Binder context for this service
     */
    public StorageManagerService(Context context) {
        sSelf = this;

        mContext = context;
        mCallbacks = new Callbacks(FgThread.get().getLooper());
        mLockPatternUtils = new LockPatternUtils(mContext);

        // XXX: This will go away soon in favor of IMountServiceObserver
        mPms = (PackageManagerService) ServiceManager.getService("package");

        HandlerThread hthread = new HandlerThread(TAG);
        hthread.start();
        mHandler = new StorageManagerServiceHandler(hthread.getLooper());

        // Add OBB Action Handler to StorageManagerService thread.
        mObbActionHandler = new ObbActionHandler(IoThread.get().getLooper());

        // Initialize the last-fstrim tracking if necessary
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        mLastMaintenanceFile = new File(systemDir, LAST_FSTRIM_FILE);
        if (!mLastMaintenanceFile.exists()) {
            // Not setting mLastMaintenance here means that we will force an
            // fstrim during reboot following the OTA that installs this code.
            try {
                (new FileOutputStream(mLastMaintenanceFile)).close();
            } catch (IOException e) {
                Slog.e(TAG, "Unable to create fstrim record " + mLastMaintenanceFile.getPath());
            }
        } else {
            mLastMaintenance = mLastMaintenanceFile.lastModified();
        }

        mSettingsFile = new AtomicFile(
                new File(Environment.getDataSystemDirectory(), "storage.xml"), "storage-settings");

        synchronized (mLock) {
            readSettingsLocked();
        }

        LocalServices.addService(StorageManagerInternal.class, mStorageManagerInternal);

        final IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(mUserReceiver, userFilter, null, mHandler);

        synchronized (mLock) {
            addInternalVolumeLocked();
        }

        // Add ourself to the Watchdog monitors if enabled.
        if (WATCHDOG_ENABLE) {
            Watchdog.getInstance().addMonitor(this);
        }
    }

    private void start() {
        connect();
    }

    private void connect() {
        IBinder binder = ServiceManager.getService("storaged");
        if (binder != null) {
            try {
                binder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        Slog.w(TAG, "storaged died; reconnecting");
                        mStoraged = null;
                        connect();
                    }
                }, 0);
            } catch (RemoteException e) {
                binder = null;
            }
        }

        if (binder != null) {
            mStoraged = IStoraged.Stub.asInterface(binder);
        } else {
            Slog.w(TAG, "storaged not found; trying again");
        }

        binder = ServiceManager.getService("vold");
        if (binder != null) {
            try {
                binder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        Slog.w(TAG, "vold died; reconnecting");
                        mVold = null;
                        connect();
                    }
                }, 0);
            } catch (RemoteException e) {
                binder = null;
            }
        }

        if (binder != null) {
            mVold = IVold.Stub.asInterface(binder);
            try {
                mVold.setListener(mListener);
            } catch (RemoteException e) {
                mVold = null;
                Slog.w(TAG, "vold listener rejected; trying again", e);
            }
        } else {
            Slog.w(TAG, "vold not found; trying again");
        }

        if (mStoraged == null || mVold == null) {
            BackgroundThread.getHandler().postDelayed(() -> {
                connect();
            }, DateUtils.SECOND_IN_MILLIS);
        } else {
            onDaemonConnected();
        }
    }

    private void systemReady() {
        LocalServices.getService(ActivityManagerInternal.class)
                .registerScreenObserver(this);

        mSystemReady = true;
        mHandler.obtainMessage(H_SYSTEM_READY).sendToTarget();
    }

    private void bootCompleted() {
        mBootCompleted = true;
    }

    private String getDefaultPrimaryStorageUuid() {
        if (SystemProperties.getBoolean(StorageManager.PROP_PRIMARY_PHYSICAL, false)) {
            return StorageManager.UUID_PRIMARY_PHYSICAL;
        } else {
            return StorageManager.UUID_PRIVATE_INTERNAL;
        }
    }

    @GuardedBy("mLock")
    private void readSettingsLocked() {
        mRecords.clear();
        mPrimaryStorageUuid = getDefaultPrimaryStorageUuid();

        FileInputStream fis = null;
        try {
            fis = mSettingsFile.openRead();
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, StandardCharsets.UTF_8.name());

            int type;
            while ((type = in.next()) != END_DOCUMENT) {
                if (type == START_TAG) {
                    final String tag = in.getName();
                    if (TAG_VOLUMES.equals(tag)) {
                        final int version = readIntAttribute(in, ATTR_VERSION, VERSION_INIT);
                        final boolean primaryPhysical = SystemProperties.getBoolean(
                                StorageManager.PROP_PRIMARY_PHYSICAL, false);
                        final boolean validAttr = (version >= VERSION_FIX_PRIMARY)
                                || (version >= VERSION_ADD_PRIMARY && !primaryPhysical);
                        if (validAttr) {
                            mPrimaryStorageUuid = readStringAttribute(in,
                                    ATTR_PRIMARY_STORAGE_UUID);
                        }

                    } else if (TAG_VOLUME.equals(tag)) {
                        final VolumeRecord rec = readVolumeRecord(in);
                        mRecords.put(rec.fsUuid, rec);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            // Missing metadata is okay, probably first boot
        } catch (IOException e) {
            Slog.wtf(TAG, "Failed reading metadata", e);
        } catch (XmlPullParserException e) {
            Slog.wtf(TAG, "Failed reading metadata", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    @GuardedBy("mLock")
    private void writeSettingsLocked() {
        FileOutputStream fos = null;
        try {
            fos = mSettingsFile.startWrite();

            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);
            out.startTag(null, TAG_VOLUMES);
            writeIntAttribute(out, ATTR_VERSION, VERSION_FIX_PRIMARY);
            writeStringAttribute(out, ATTR_PRIMARY_STORAGE_UUID, mPrimaryStorageUuid);
            final int size = mRecords.size();
            for (int i = 0; i < size; i++) {
                final VolumeRecord rec = mRecords.valueAt(i);
                writeVolumeRecord(out, rec);
            }
            out.endTag(null, TAG_VOLUMES);
            out.endDocument();

            mSettingsFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mSettingsFile.failWrite(fos);
            }
        }
    }

    public static VolumeRecord readVolumeRecord(XmlPullParser in) throws IOException {
        final int type = readIntAttribute(in, ATTR_TYPE);
        final String fsUuid = readStringAttribute(in, ATTR_FS_UUID);
        final VolumeRecord meta = new VolumeRecord(type, fsUuid);
        meta.partGuid = readStringAttribute(in, ATTR_PART_GUID);
        meta.nickname = readStringAttribute(in, ATTR_NICKNAME);
        meta.userFlags = readIntAttribute(in, ATTR_USER_FLAGS);
        meta.createdMillis = readLongAttribute(in, ATTR_CREATED_MILLIS);
        meta.lastTrimMillis = readLongAttribute(in, ATTR_LAST_TRIM_MILLIS);
        meta.lastBenchMillis = readLongAttribute(in, ATTR_LAST_BENCH_MILLIS);
        return meta;
    }

    public static void writeVolumeRecord(XmlSerializer out, VolumeRecord rec) throws IOException {
        out.startTag(null, TAG_VOLUME);
        writeIntAttribute(out, ATTR_TYPE, rec.type);
        writeStringAttribute(out, ATTR_FS_UUID, rec.fsUuid);
        writeStringAttribute(out, ATTR_PART_GUID, rec.partGuid);
        writeStringAttribute(out, ATTR_NICKNAME, rec.nickname);
        writeIntAttribute(out, ATTR_USER_FLAGS, rec.userFlags);
        writeLongAttribute(out, ATTR_CREATED_MILLIS, rec.createdMillis);
        writeLongAttribute(out, ATTR_LAST_TRIM_MILLIS, rec.lastTrimMillis);
        writeLongAttribute(out, ATTR_LAST_BENCH_MILLIS, rec.lastBenchMillis);
        out.endTag(null, TAG_VOLUME);
    }

    /**
     * Exposed API calls below here
     */

    @Override
    public void registerListener(IStorageEventListener listener) {
        mCallbacks.register(listener);
    }

    @Override
    public void unregisterListener(IStorageEventListener listener) {
        mCallbacks.unregister(listener);
    }

    @Override
    public void shutdown(final IStorageShutdownObserver observer) {
        enforcePermission(android.Manifest.permission.SHUTDOWN);

        Slog.i(TAG, "Shutting down");
        mHandler.obtainMessage(H_SHUTDOWN, observer).sendToTarget();
    }

    @Override
    public void mount(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        final VolumeInfo vol = findVolumeByIdOrThrow(volId);
        if (isMountDisallowed(vol)) {
            throw new SecurityException("Mounting " + volId + " restricted by policy");
        }
        try {
            mVold.mount(vol.id, vol.mountFlags, vol.mountUserId);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void unmount(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        final VolumeInfo vol = findVolumeByIdOrThrow(volId);
        try {
            mVold.unmount(vol.id);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void format(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);

        final VolumeInfo vol = findVolumeByIdOrThrow(volId);
        try {
            mVold.format(vol.id, "auto");
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void benchmark(String volId, IVoldTaskListener listener) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);

        try {
            mVold.benchmark(volId, new IVoldTaskListener.Stub() {
                @Override
                public void onStatus(int status, PersistableBundle extras) {
                    dispatchOnStatus(listener, status, extras);
                }

                @Override
                public void onFinished(int status, PersistableBundle extras) {
                    dispatchOnFinished(listener, status, extras);

                    final String path = extras.getString("path");
                    final String ident = extras.getString("ident");
                    final long create = extras.getLong("create");
                    final long run = extras.getLong("run");
                    final long destroy = extras.getLong("destroy");

                    final DropBoxManager dropBox = mContext.getSystemService(DropBoxManager.class);
                    dropBox.addText(TAG_STORAGE_BENCHMARK, scrubPath(path)
                            + " " + ident + " " + create + " " + run + " " + destroy);

                    synchronized (mLock) {
                        final VolumeRecord rec = findRecordForPath(path);
                        if (rec != null) {
                            rec.lastBenchMillis = System.currentTimeMillis();
                            writeSettingsLocked();
                        }
                    }
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    @Override
    public void partitionPublic(String diskId) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);

        final CountDownLatch latch = findOrCreateDiskScanLatch(diskId);
        try {
            mVold.partition(diskId, IVold.PARTITION_TYPE_PUBLIC, -1);
            waitForLatch(latch, "partitionPublic", 3 * DateUtils.MINUTE_IN_MILLIS);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void partitionPrivate(String diskId) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        enforceAdminUser();

        final CountDownLatch latch = findOrCreateDiskScanLatch(diskId);
        try {
            mVold.partition(diskId, IVold.PARTITION_TYPE_PRIVATE, -1);
            waitForLatch(latch, "partitionPrivate", 3 * DateUtils.MINUTE_IN_MILLIS);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void partitionMixed(String diskId, int ratio) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        enforceAdminUser();

        final CountDownLatch latch = findOrCreateDiskScanLatch(diskId);
        try {
            mVold.partition(diskId, IVold.PARTITION_TYPE_MIXED, ratio);
            waitForLatch(latch, "partitionMixed", 3 * DateUtils.MINUTE_IN_MILLIS);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void setVolumeNickname(String fsUuid, String nickname) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        Preconditions.checkNotNull(fsUuid);
        synchronized (mLock) {
            final VolumeRecord rec = mRecords.get(fsUuid);
            rec.nickname = nickname;
            mCallbacks.notifyVolumeRecordChanged(rec);
            writeSettingsLocked();
        }
    }

    @Override
    public void setVolumeUserFlags(String fsUuid, int flags, int mask) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        Preconditions.checkNotNull(fsUuid);
        synchronized (mLock) {
            final VolumeRecord rec = mRecords.get(fsUuid);
            rec.userFlags = (rec.userFlags & ~mask) | (flags & mask);
            mCallbacks.notifyVolumeRecordChanged(rec);
            writeSettingsLocked();
        }
    }

    @Override
    public void forgetVolume(String fsUuid) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        Preconditions.checkNotNull(fsUuid);

        synchronized (mLock) {
            final VolumeRecord rec = mRecords.remove(fsUuid);
            if (rec != null && !TextUtils.isEmpty(rec.partGuid)) {
                mHandler.obtainMessage(H_PARTITION_FORGET, rec).sendToTarget();
            }
            mCallbacks.notifyVolumeForgotten(fsUuid);

            // If this had been primary storage, revert back to internal and
            // reset vold so we bind into new volume into place.
            if (Objects.equals(mPrimaryStorageUuid, fsUuid)) {
                mPrimaryStorageUuid = getDefaultPrimaryStorageUuid();
                mHandler.obtainMessage(H_RESET).sendToTarget();
            }

            writeSettingsLocked();
        }
    }

    @Override
    public void forgetAllVolumes() {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        synchronized (mLock) {
            for (int i = 0; i < mRecords.size(); i++) {
                final String fsUuid = mRecords.keyAt(i);
                final VolumeRecord rec = mRecords.valueAt(i);
                if (!TextUtils.isEmpty(rec.partGuid)) {
                    mHandler.obtainMessage(H_PARTITION_FORGET, rec).sendToTarget();
                }
                mCallbacks.notifyVolumeForgotten(fsUuid);
            }
            mRecords.clear();

            if (!Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, mPrimaryStorageUuid)) {
                mPrimaryStorageUuid = getDefaultPrimaryStorageUuid();
            }

            writeSettingsLocked();
            mHandler.obtainMessage(H_RESET).sendToTarget();
        }
    }

    private void forgetPartition(String partGuid, String fsUuid) {
        try {
            mVold.forgetPartition(partGuid, fsUuid);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void fstrim(int flags, IVoldTaskListener listener) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);

        try {
            mVold.fstrim(flags, new IVoldTaskListener.Stub() {
                @Override
                public void onStatus(int status, PersistableBundle extras) {
                    dispatchOnStatus(listener, status, extras);

                    // Ignore trim failures
                    if (status != 0) return;

                    final String path = extras.getString("path");
                    final long bytes = extras.getLong("bytes");
                    final long time = extras.getLong("time");

                    final DropBoxManager dropBox = mContext.getSystemService(DropBoxManager.class);
                    dropBox.addText(TAG_STORAGE_TRIM, scrubPath(path) + " " + bytes + " " + time);

                    synchronized (mLock) {
                        final VolumeRecord rec = findRecordForPath(path);
                        if (rec != null) {
                            rec.lastTrimMillis = System.currentTimeMillis();
                            writeSettingsLocked();
                        }
                    }
                }

                @Override
                public void onFinished(int status, PersistableBundle extras) {
                    dispatchOnFinished(listener, status, extras);

                    // TODO: benchmark when desired
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    void runIdleMaint(Runnable callback) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);

        try {
            mVold.runIdleMaint(new IVoldTaskListener.Stub() {
                @Override
                public void onStatus(int status, PersistableBundle extras) {
                    // Not currently used
                }
                @Override
                public void onFinished(int status, PersistableBundle extras) {
                    if (callback != null) {
                        BackgroundThread.getHandler().post(callback);
                    }
                }
            });
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void runIdleMaintenance() {
        runIdleMaint(null);
    }

    void abortIdleMaint(Runnable callback) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);

        try {
            mVold.abortIdleMaint(new IVoldTaskListener.Stub() {
                @Override
                public void onStatus(int status, PersistableBundle extras) {
                    // Not currently used
                }
                @Override
                public void onFinished(int status, PersistableBundle extras) {
                    if (callback != null) {
                        BackgroundThread.getHandler().post(callback);
                    }
                }
            });
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void abortIdleMaintenance() {
        abortIdleMaint(null);
    }

    private void remountUidExternalStorage(int uid, int mode) {
        try {
            mVold.remountUid(uid, mode);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void setDebugFlags(int flags, int mask) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        if ((mask & StorageManager.DEBUG_EMULATE_FBE) != 0) {
            if (!EMULATE_FBE_SUPPORTED) {
                throw new IllegalStateException(
                        "Emulation not supported on this device");
            }
            if (StorageManager.isFileEncryptedNativeOnly()) {
                throw new IllegalStateException(
                        "Emulation not supported on device with native FBE");
            }
            if (mLockPatternUtils.isCredentialRequiredToDecrypt(false)) {
                throw new IllegalStateException(
                        "Emulation requires disabling 'Secure start-up' in Settings > Security");
            }

            final long token = Binder.clearCallingIdentity();
            try {
                final boolean emulateFbe = (flags & StorageManager.DEBUG_EMULATE_FBE) != 0;
                SystemProperties.set(StorageManager.PROP_EMULATE_FBE, Boolean.toString(emulateFbe));

                // Perform hard reboot to kick policy into place
                mContext.getSystemService(PowerManager.class).reboot(null);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        if ((mask & (StorageManager.DEBUG_ADOPTABLE_FORCE_ON
                | StorageManager.DEBUG_ADOPTABLE_FORCE_OFF)) != 0) {
            final String value;
            if ((flags & StorageManager.DEBUG_ADOPTABLE_FORCE_ON) != 0) {
                value = "force_on";
            } else if ((flags & StorageManager.DEBUG_ADOPTABLE_FORCE_OFF) != 0) {
                value = "force_off";
            } else {
                value = "";
            }

            final long token = Binder.clearCallingIdentity();
            try {
                SystemProperties.set(StorageManager.PROP_ADOPTABLE, value);

                // Reset storage to kick new setting into place
                mHandler.obtainMessage(H_RESET).sendToTarget();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        if ((mask & (StorageManager.DEBUG_SDCARDFS_FORCE_ON
                | StorageManager.DEBUG_SDCARDFS_FORCE_OFF)) != 0) {
            final String value;
            if ((flags & StorageManager.DEBUG_SDCARDFS_FORCE_ON) != 0) {
                value = "force_on";
            } else if ((flags & StorageManager.DEBUG_SDCARDFS_FORCE_OFF) != 0) {
                value = "force_off";
            } else {
                value = "";
            }

            final long token = Binder.clearCallingIdentity();
            try {
                SystemProperties.set(StorageManager.PROP_SDCARDFS, value);

                // Reset storage to kick new setting into place
                mHandler.obtainMessage(H_RESET).sendToTarget();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        if ((mask & StorageManager.DEBUG_VIRTUAL_DISK) != 0) {
            final boolean enabled = (flags & StorageManager.DEBUG_VIRTUAL_DISK) != 0;

            final long token = Binder.clearCallingIdentity();
            try {
                SystemProperties.set(StorageManager.PROP_VIRTUAL_DISK, Boolean.toString(enabled));

                // Reset storage to kick new setting into place
                mHandler.obtainMessage(H_RESET).sendToTarget();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override
    public String getPrimaryStorageUuid() {
        synchronized (mLock) {
            return mPrimaryStorageUuid;
        }
    }

    @Override
    public void setPrimaryStorageUuid(String volumeUuid, IPackageMoveObserver callback) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        final VolumeInfo from;
        final VolumeInfo to;

        synchronized (mLock) {
            if (Objects.equals(mPrimaryStorageUuid, volumeUuid)) {
                throw new IllegalArgumentException("Primary storage already at " + volumeUuid);
            }

            if (mMoveCallback != null) {
                throw new IllegalStateException("Move already in progress");
            }
            mMoveCallback = callback;
            mMoveTargetUuid = volumeUuid;

            // We need all the users unlocked to move their primary storage
            final List<UserInfo> users = mContext.getSystemService(UserManager.class).getUsers();
            for (UserInfo user : users) {
                if (StorageManager.isFileEncryptedNativeOrEmulated()
                        && !isUserKeyUnlocked(user.id)) {
                    Slog.w(TAG, "Failing move due to locked user " + user.id);
                    onMoveStatusLocked(PackageManager.MOVE_FAILED_LOCKED_USER);
                    return;
                }
            }

            // When moving to/from primary physical volume, we probably just nuked
            // the current storage location, so we have nothing to move.
            if (Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL, mPrimaryStorageUuid)
                    || Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL, volumeUuid)) {
                Slog.d(TAG, "Skipping move to/from primary physical");
                onMoveStatusLocked(MOVE_STATUS_COPY_FINISHED);
                onMoveStatusLocked(PackageManager.MOVE_SUCCEEDED);
                mHandler.obtainMessage(H_RESET).sendToTarget();
                return;

            } else {
                from = findStorageForUuid(mPrimaryStorageUuid);
                to = findStorageForUuid(volumeUuid);

                if (from == null) {
                    Slog.w(TAG, "Failing move due to missing from volume " + mPrimaryStorageUuid);
                    onMoveStatusLocked(PackageManager.MOVE_FAILED_INTERNAL_ERROR);
                    return;
                } else if (to == null) {
                    Slog.w(TAG, "Failing move due to missing to volume " + volumeUuid);
                    onMoveStatusLocked(PackageManager.MOVE_FAILED_INTERNAL_ERROR);
                    return;
                }
            }
        }

        try {
            mVold.moveStorage(from.id, to.id, new IVoldTaskListener.Stub() {
                @Override
                public void onStatus(int status, PersistableBundle extras) {
                    synchronized (mLock) {
                        onMoveStatusLocked(status);
                    }
                }

                @Override
                public void onFinished(int status, PersistableBundle extras) {
                    // Not currently used
                }
            });
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    private void warnOnNotMounted() {
        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (vol.isPrimary() && vol.isMountedWritable()) {
                    // Cool beans, we have a mounted primary volume
                    return;
                }
            }
        }

        Slog.w(TAG, "No primary storage mounted!");
    }

    private boolean isUidOwnerOfPackageOrSystem(String packageName, int callerUid) {
        if (callerUid == android.os.Process.SYSTEM_UID) {
            return true;
        }

        if (packageName == null) {
            return false;
        }

        final int packageUid = mPms.getPackageUid(packageName,
                PackageManager.MATCH_DEBUG_TRIAGED_MISSING, UserHandle.getUserId(callerUid));

        if (DEBUG_OBB) {
            Slog.d(TAG, "packageName = " + packageName + ", packageUid = " +
                    packageUid + ", callerUid = " + callerUid);
        }

        return callerUid == packageUid;
    }

    @Override
    public String getMountedObbPath(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        warnOnNotMounted();

        final ObbState state;
        synchronized (mObbMounts) {
            state = mObbPathToStateMap.get(rawPath);
        }
        if (state == null) {
            Slog.w(TAG, "Failed to find OBB mounted at " + rawPath);
            return null;
        }

        return findVolumeByIdOrThrow(state.volId).getPath().getAbsolutePath();
    }

    @Override
    public boolean isObbMounted(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        synchronized (mObbMounts) {
            return mObbPathToStateMap.containsKey(rawPath);
        }
    }

    @Override
    public void mountObb(
            String rawPath, String canonicalPath, String key, IObbActionListener token, int nonce) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        Preconditions.checkNotNull(canonicalPath, "canonicalPath cannot be null");
        Preconditions.checkNotNull(token, "token cannot be null");

        final int callingUid = Binder.getCallingUid();
        final ObbState obbState = new ObbState(rawPath, canonicalPath,
                callingUid, token, nonce, null);
        final ObbAction action = new MountObbAction(obbState, key, callingUid);
        mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));

        if (DEBUG_OBB)
            Slog.i(TAG, "Send to OBB handler: " + action.toString());
    }

    @Override
    public void unmountObb(String rawPath, boolean force, IObbActionListener token, int nonce) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        final ObbState existingState;
        synchronized (mObbMounts) {
            existingState = mObbPathToStateMap.get(rawPath);
        }

        if (existingState != null) {
            // TODO: separate state object from request data
            final int callingUid = Binder.getCallingUid();
            final ObbState newState = new ObbState(rawPath, existingState.canonicalPath,
                    callingUid, token, nonce, existingState.volId);
            final ObbAction action = new UnmountObbAction(newState, force);
            mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));

            if (DEBUG_OBB)
                Slog.i(TAG, "Send to OBB handler: " + action.toString());
        } else {
            Slog.w(TAG, "Unknown OBB mount at " + rawPath);
        }
    }

    @Override
    public int getEncryptionState() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
                "no permission to access the crypt keeper");

        try {
            return mVold.fdeComplete();
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return StorageManager.ENCRYPTION_STATE_ERROR_UNKNOWN;
        }
    }

    @Override
    public int decryptStorage(String password) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
                "no permission to access the crypt keeper");

        if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "decrypting storage...");
        }

        try {
            mVold.fdeCheckPassword(password);
            mHandler.postDelayed(() -> {
                try {
                    mVold.fdeRestart();
                } catch (Exception e) {
                    Slog.wtf(TAG, e);
                }
            }, DateUtils.SECOND_IN_MILLIS);
            return 0;
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return StorageManager.ENCRYPTION_STATE_ERROR_UNKNOWN;
        }
    }

    @Override
    public int encryptStorage(int type, String password) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        if (type == StorageManager.CRYPT_TYPE_DEFAULT) {
            password = "";
        } else if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "encrypting storage...");
        }

        try {
            mVold.fdeEnable(type, password, 0);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return -1;
        }

        return 0;
    }

    /** Set the password for encrypting the master key.
     *  @param type One of the CRYPTO_TYPE_XXX consts defined in StorageManager.
     *  @param password The password to set.
     */
    @Override
    public int changeEncryptionPassword(int type, String password) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        if (StorageManager.isFileEncryptedNativeOnly()) {
            // Not supported on FBE devices
            return -1;
        }

        if (type == StorageManager.CRYPT_TYPE_DEFAULT) {
            password = "";
        } else if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "changing encryption password...");
        }

        try {
            mVold.fdeChangePassword(type, password);
            return 0;
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return -1;
        }
    }

    /**
     * Validate a user-supplied password string with cryptfs
     */
    @Override
    public int verifyEncryptionPassword(String password) throws RemoteException {
        // Only the system process is permitted to validate passwords
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new SecurityException("no permission to access the crypt keeper");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "validating encryption password...");
        }

        try {
            mVold.fdeVerifyPassword(password);
            return 0;
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return -1;
        }
    }

    /**
     * Get the type of encryption used to encrypt the master key.
     * @return The type, one of the CRYPT_TYPE_XXX consts from StorageManager.
     */
    @Override
    public int getPasswordType() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        try {
            return mVold.fdeGetPasswordType();
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return -1;
        }
    }

    /**
     * Set a field in the crypto header.
     * @param field field to set
     * @param contents contents to set in field
     */
    @Override
    public void setField(String field, String contents) throws RemoteException {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        if (StorageManager.isFileEncryptedNativeOnly()) {
            // Not supported on FBE devices
            return;
        }

        try {
            mVold.fdeSetField(field, contents);
            return;
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return;
        }
    }

    /**
     * Gets a field from the crypto header.
     * @param field field to get
     * @return contents of field
     */
    @Override
    public String getField(String field) throws RemoteException {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        if (StorageManager.isFileEncryptedNativeOnly()) {
            // Not supported on FBE devices
            return null;
        }

        try {
            return mVold.fdeGetField(field);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return null;
        }
    }

    /**
     * Is userdata convertible to file based encryption?
     * @return non zero for convertible
     */
    @Override
    public boolean isConvertibleToFBE() throws RemoteException {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        try {
            return mVold.isConvertibleToFbe();
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return false;
        }
    }

    @Override
    public String getPassword() throws RemoteException {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
                "only keyguard can retrieve password");

        try {
            return mVold.fdeGetPassword();
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return null;
        }
    }

    @Override
    public void clearPassword() throws RemoteException {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
                "only keyguard can clear password");

        try {
            mVold.fdeClearPassword();
            return;
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return;
        }
    }

    @Override
    public void createUserKey(int userId, int serialNumber, boolean ephemeral) {
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        try {
            mVold.createUserKey(userId, serialNumber, ephemeral);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void destroyUserKey(int userId) {
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        try {
            mVold.destroyUserKey(userId);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    private String encodeBytes(byte[] bytes) {
        if (ArrayUtils.isEmpty(bytes)) {
            return "!";
        } else {
            return HexDump.toHexString(bytes);
        }
    }

    /*
     * Add this token/secret pair to the set of ways we can recover a disk encryption key.
     * Changing the token/secret for a disk encryption key is done in two phases: first, adding
     * a new token/secret pair with this call, then delting all other pairs with
     * fixateNewestUserKeyAuth. This allows other places where a credential is used, such as
     * Gatekeeper, to be updated between the two calls.
     */
    @Override
    public void addUserKeyAuth(int userId, int serialNumber, byte[] token, byte[] secret) {
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        try {
            mVold.addUserKeyAuth(userId, serialNumber, encodeBytes(token), encodeBytes(secret));
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    /*
     * Delete all disk encryption token/secret pairs except the most recently added one
     */
    @Override
    public void fixateNewestUserKeyAuth(int userId) {
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        try {
            mVold.fixateNewestUserKeyAuth(userId);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void unlockUserKey(int userId, int serialNumber, byte[] token, byte[] secret) {
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        if (StorageManager.isFileEncryptedNativeOrEmulated()) {
            // When a user has secure lock screen, require secret to actually unlock.
            // This check is mostly in place for emulation mode.
            if (mLockPatternUtils.isSecure(userId) && ArrayUtils.isEmpty(secret)) {
                throw new IllegalStateException("Secret required to unlock secure user " + userId);
            }

            try {
                mVold.unlockUserKey(userId, serialNumber, encodeBytes(token),
                        encodeBytes(secret));
            } catch (Exception e) {
                Slog.wtf(TAG, e);
                return;
            }
        }

        synchronized (mLock) {
            mLocalUnlockedUsers = ArrayUtils.appendInt(mLocalUnlockedUsers, userId);
        }
    }

    @Override
    public void lockUserKey(int userId) {
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        try {
            mVold.lockUserKey(userId);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return;
        }

        synchronized (mLock) {
            mLocalUnlockedUsers = ArrayUtils.removeInt(mLocalUnlockedUsers, userId);
        }
    }

    @Override
    public boolean isUserKeyUnlocked(int userId) {
        synchronized (mLock) {
            return ArrayUtils.contains(mLocalUnlockedUsers, userId);
        }
    }

    @Override
    public void prepareUserStorage(String volumeUuid, int userId, int serialNumber, int flags) {
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        try {
            mVold.prepareUserStorage(volumeUuid, userId, serialNumber, flags);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void destroyUserStorage(String volumeUuid, int userId, int flags) {
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        try {
            mVold.destroyUserStorage(volumeUuid, userId, flags);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    class AppFuseMountScope extends AppFuseBridge.MountScope {
        boolean opened = false;

        public AppFuseMountScope(int uid, int pid, int mountId) {
            super(uid, pid, mountId);
        }

        @Override
        public ParcelFileDescriptor open() throws NativeDaemonConnectorException {
            try {
                return new ParcelFileDescriptor(
                        mVold.mountAppFuse(uid, Process.myPid(), mountId));
            } catch (Exception e) {
                throw new NativeDaemonConnectorException("Failed to mount", e);
            }
        }

        @Override
        public void close() throws Exception {
            if (opened) {
                mVold.unmountAppFuse(uid, Process.myPid(), mountId);
                opened = false;
            }
        }
    }

    @Override
    public @Nullable AppFuseMount mountProxyFileDescriptorBridge() {
        Slog.v(TAG, "mountProxyFileDescriptorBridge");
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();

        while (true) {
            synchronized (mAppFuseLock) {
                boolean newlyCreated = false;
                if (mAppFuseBridge == null) {
                    mAppFuseBridge = new AppFuseBridge();
                    new Thread(mAppFuseBridge, AppFuseBridge.TAG).start();
                    newlyCreated = true;
                }
                try {
                    final int name = mNextAppFuseName++;
                    try {
                        return new AppFuseMount(
                            name, mAppFuseBridge.addBridge(new AppFuseMountScope(uid, pid, name)));
                    } catch (FuseUnavailableMountException e) {
                        if (newlyCreated) {
                            // If newly created bridge fails, it's a real error.
                            Slog.e(TAG, "", e);
                            return null;
                        }
                        // It seems the thread of mAppFuseBridge has already been terminated.
                        mAppFuseBridge = null;
                    }
                } catch (NativeDaemonConnectorException e) {
                    throw e.rethrowAsParcelableException();
                }
            }
        }
    }

    @Override
    public @Nullable ParcelFileDescriptor openProxyFileDescriptor(
            int mountId, int fileId, int mode) {
        Slog.v(TAG, "mountProxyFileDescriptor");
        final int pid = Binder.getCallingPid();
        try {
            synchronized (mAppFuseLock) {
                if (mAppFuseBridge == null) {
                    Slog.e(TAG, "FuseBridge has not been created");
                    return null;
                }
                return mAppFuseBridge.openFile(pid, mountId, fileId, mode);
            }
        } catch (FuseUnavailableMountException | InterruptedException error) {
            Slog.v(TAG, "The mount point has already been invalid", error);
            return null;
        }
    }

    @Override
    public void mkdirs(String callingPkg, String appPath) {
        final int userId = UserHandle.getUserId(Binder.getCallingUid());
        final UserEnvironment userEnv = new UserEnvironment(userId);
        final String propertyName = "sys.user." + userId + ".ce_available";

        // Ignore requests to create directories while storage is locked
        if (!isUserKeyUnlocked(userId)) {
            throw new IllegalStateException("Failed to prepare " + appPath);
        }

        // Ignore requests to create directories if CE storage is not available
        if ((userId == UserHandle.USER_SYSTEM)
                && !SystemProperties.getBoolean(propertyName, false)) {
            throw new IllegalStateException("Failed to prepare " + appPath);
        }

        // Validate that reported package name belongs to caller
        final AppOpsManager appOps = (AppOpsManager) mContext.getSystemService(
                Context.APP_OPS_SERVICE);
        appOps.checkPackage(Binder.getCallingUid(), callingPkg);

        File appFile = null;
        try {
            appFile = new File(appPath).getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve " + appPath + ": " + e);
        }

        // Try translating the app path into a vold path, but require that it
        // belong to the calling package.
        if (FileUtils.contains(userEnv.buildExternalStorageAppDataDirs(callingPkg), appFile) ||
                FileUtils.contains(userEnv.buildExternalStorageAppObbDirs(callingPkg), appFile) ||
                FileUtils.contains(userEnv.buildExternalStorageAppMediaDirs(callingPkg), appFile)) {
            appPath = appFile.getAbsolutePath();
            if (!appPath.endsWith("/")) {
                appPath = appPath + "/";
            }

            try {
                mVold.mkdirs(appPath);
                return;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to prepare " + appPath + ": " + e);
            }
        }

        throw new SecurityException("Invalid mkdirs path: " + appFile);
    }

    @Override
    public StorageVolume[] getVolumeList(int uid, String packageName, int flags) {
        final int userId = UserHandle.getUserId(uid);

        final boolean forWrite = (flags & StorageManager.FLAG_FOR_WRITE) != 0;
        final boolean realState = (flags & StorageManager.FLAG_REAL_STATE) != 0;
        final boolean includeInvisible = (flags & StorageManager.FLAG_INCLUDE_INVISIBLE) != 0;

        final boolean userKeyUnlocked;
        final boolean storagePermission;
        final long token = Binder.clearCallingIdentity();
        try {
            userKeyUnlocked = isUserKeyUnlocked(userId);
            storagePermission = mStorageManagerInternal.hasExternalStorage(uid, packageName);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        boolean foundPrimary = false;

        final ArrayList<StorageVolume> res = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                switch (vol.getType()) {
                    case VolumeInfo.TYPE_PUBLIC:
                    case VolumeInfo.TYPE_EMULATED:
                        break;
                    default:
                        continue;
                }

                boolean match = false;
                if (forWrite) {
                    match = vol.isVisibleForWrite(userId);
                } else {
                    match = vol.isVisibleForRead(userId)
                            || (includeInvisible && vol.getPath() != null);
                }
                if (!match) continue;

                boolean reportUnmounted = false;
                if ((vol.getType() == VolumeInfo.TYPE_EMULATED) && !userKeyUnlocked) {
                    reportUnmounted = true;
                } else if (!storagePermission && !realState) {
                    reportUnmounted = true;
                }

                final StorageVolume userVol = vol.buildStorageVolume(mContext, userId,
                        reportUnmounted);
                if (vol.isPrimary()) {
                    res.add(0, userVol);
                    foundPrimary = true;
                } else {
                    res.add(userVol);
                }
            }
        }

        if (!foundPrimary) {
            Log.w(TAG, "No primary storage defined yet; hacking together a stub");

            final boolean primaryPhysical = SystemProperties.getBoolean(
                    StorageManager.PROP_PRIMARY_PHYSICAL, false);

            final String id = "stub_primary";
            final File path = Environment.getLegacyExternalStorageDirectory();
            final String description = mContext.getString(android.R.string.unknownName);
            final boolean primary = true;
            final boolean removable = primaryPhysical;
            final boolean emulated = !primaryPhysical;
            final boolean allowMassStorage = false;
            final long maxFileSize = 0L;
            final UserHandle owner = new UserHandle(userId);
            final String uuid = null;
            final String state = Environment.MEDIA_REMOVED;

            res.add(0, new StorageVolume(id, path,
                    description, primary, removable, emulated,
                    allowMassStorage, maxFileSize, owner, uuid, state));
        }

        return res.toArray(new StorageVolume[res.size()]);
    }

    @Override
    public DiskInfo[] getDisks() {
        synchronized (mLock) {
            final DiskInfo[] res = new DiskInfo[mDisks.size()];
            for (int i = 0; i < mDisks.size(); i++) {
                res[i] = mDisks.valueAt(i);
            }
            return res;
        }
    }

    @Override
    public VolumeInfo[] getVolumes(int flags) {
        synchronized (mLock) {
            final VolumeInfo[] res = new VolumeInfo[mVolumes.size()];
            for (int i = 0; i < mVolumes.size(); i++) {
                res[i] = mVolumes.valueAt(i);
            }
            return res;
        }
    }

    @Override
    public VolumeRecord[] getVolumeRecords(int flags) {
        synchronized (mLock) {
            final VolumeRecord[] res = new VolumeRecord[mRecords.size()];
            for (int i = 0; i < mRecords.size(); i++) {
                res[i] = mRecords.valueAt(i);
            }
            return res;
        }
    }

    @Override
    public long getCacheQuotaBytes(String volumeUuid, int uid) {
        if (uid != Binder.getCallingUid()) {
            mContext.enforceCallingPermission(android.Manifest.permission.STORAGE_INTERNAL, TAG);
        }
        final long token = Binder.clearCallingIdentity();
        final StorageStatsManager stats = mContext.getSystemService(StorageStatsManager.class);
        try {
            return stats.getCacheQuotaBytes(volumeUuid, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public long getCacheSizeBytes(String volumeUuid, int uid) {
        if (uid != Binder.getCallingUid()) {
            mContext.enforceCallingPermission(android.Manifest.permission.STORAGE_INTERNAL, TAG);
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return mContext.getSystemService(StorageStatsManager.class)
                    .queryStatsForUid(volumeUuid, uid).getCacheBytes();
        } catch (IOException e) {
            throw new ParcelableException(e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private int adjustAllocateFlags(int flags, int callingUid, String callingPackage) {
        // Require permission to allocate aggressively
        if ((flags & StorageManager.FLAG_ALLOCATE_AGGRESSIVE) != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ALLOCATE_AGGRESSIVE, TAG);
        }

        // Apps normally can't directly defy reserved space
        flags &= ~StorageManager.FLAG_ALLOCATE_DEFY_ALL_RESERVED;
        flags &= ~StorageManager.FLAG_ALLOCATE_DEFY_HALF_RESERVED;

        // However, if app is actively using the camera, then we're willing to
        // clear up to half of the reserved cache space, since the user might be
        // trying to capture an important memory.
        final AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        final long token = Binder.clearCallingIdentity();
        try {
            if (appOps.isOperationActive(AppOpsManager.OP_CAMERA, callingUid, callingPackage)) {
                Slog.d(TAG, "UID " + callingUid + " is actively using camera;"
                        + " letting them defy reserved cached data");
                flags |= StorageManager.FLAG_ALLOCATE_DEFY_HALF_RESERVED;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return flags;
    }

    @Override
    public long getAllocatableBytes(String volumeUuid, int flags, String callingPackage) {
        flags = adjustAllocateFlags(flags, Binder.getCallingUid(), callingPackage);

        final StorageManager storage = mContext.getSystemService(StorageManager.class);
        final StorageStatsManager stats = mContext.getSystemService(StorageStatsManager.class);
        final long token = Binder.clearCallingIdentity();
        try {
            // In general, apps can allocate as much space as they want, except
            // we never let them eat into either the minimum cache space or into
            // the low disk warning space. To avoid user confusion, this logic
            // should be kept in sync with getFreeBytes().
            final File path = storage.findPathForUuid(volumeUuid);

            final long usable = path.getUsableSpace();
            final long lowReserved = storage.getStorageLowBytes(path);
            final long fullReserved = storage.getStorageFullBytes(path);

            if (stats.isQuotaSupported(volumeUuid)) {
                final long cacheTotal = stats.getCacheBytes(volumeUuid);
                final long cacheReserved = storage.getStorageCacheBytes(path, flags);
                final long cacheClearable = Math.max(0, cacheTotal - cacheReserved);

                if ((flags & StorageManager.FLAG_ALLOCATE_AGGRESSIVE) != 0) {
                    return Math.max(0, (usable + cacheClearable) - fullReserved);
                } else {
                    return Math.max(0, (usable + cacheClearable) - lowReserved);
                }
            } else {
                // When we don't have fast quota information, we ignore cached
                // data and only consider unused bytes.
                if ((flags & StorageManager.FLAG_ALLOCATE_AGGRESSIVE) != 0) {
                    return Math.max(0, usable - fullReserved);
                } else {
                    return Math.max(0, usable - lowReserved);
                }
            }
        } catch (IOException e) {
            throw new ParcelableException(e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void allocateBytes(String volumeUuid, long bytes, int flags, String callingPackage) {
        flags = adjustAllocateFlags(flags, Binder.getCallingUid(), callingPackage);

        final long allocatableBytes = getAllocatableBytes(volumeUuid, flags, callingPackage);
        if (bytes > allocatableBytes) {
            throw new ParcelableException(new IOException("Failed to allocate " + bytes
                    + " because only " + allocatableBytes + " allocatable"));
        }

        final StorageManager storage = mContext.getSystemService(StorageManager.class);
        final long token = Binder.clearCallingIdentity();
        try {
            // Free up enough disk space to satisfy both the requested allocation
            // and our low disk warning space.
            final File path = storage.findPathForUuid(volumeUuid);
            if ((flags & StorageManager.FLAG_ALLOCATE_AGGRESSIVE) != 0) {
                bytes += storage.getStorageFullBytes(path);
            } else {
                bytes += storage.getStorageLowBytes(path);
            }

            mPms.freeStorage(volumeUuid, bytes, flags);
        } catch (IOException e) {
            throw new ParcelableException(e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void addObbStateLocked(ObbState obbState) throws RemoteException {
        final IBinder binder = obbState.getBinder();
        List<ObbState> obbStates = mObbMounts.get(binder);

        if (obbStates == null) {
            obbStates = new ArrayList<ObbState>();
            mObbMounts.put(binder, obbStates);
        } else {
            for (final ObbState o : obbStates) {
                if (o.rawPath.equals(obbState.rawPath)) {
                    throw new IllegalStateException("Attempt to add ObbState twice. "
                            + "This indicates an error in the StorageManagerService logic.");
                }
            }
        }

        obbStates.add(obbState);
        try {
            obbState.link();
        } catch (RemoteException e) {
            /*
             * The binder died before we could link it, so clean up our state
             * and return failure.
             */
            obbStates.remove(obbState);
            if (obbStates.isEmpty()) {
                mObbMounts.remove(binder);
            }

            // Rethrow the error so mountObb can get it
            throw e;
        }

        mObbPathToStateMap.put(obbState.rawPath, obbState);
    }

    private void removeObbStateLocked(ObbState obbState) {
        final IBinder binder = obbState.getBinder();
        final List<ObbState> obbStates = mObbMounts.get(binder);
        if (obbStates != null) {
            if (obbStates.remove(obbState)) {
                obbState.unlink();
            }
            if (obbStates.isEmpty()) {
                mObbMounts.remove(binder);
            }
        }

        mObbPathToStateMap.remove(obbState.rawPath);
    }

    private class ObbActionHandler extends Handler {
        private boolean mBound = false;
        private final List<ObbAction> mActions = new LinkedList<ObbAction>();

        ObbActionHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case OBB_RUN_ACTION: {
                    final ObbAction action = (ObbAction) msg.obj;

                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_RUN_ACTION: " + action.toString());

                    // If a bind was already initiated we don't really
                    // need to do anything. The pending install
                    // will be processed later on.
                    if (!mBound) {
                        // If this is the only one pending we might
                        // have to bind to the service again.
                        if (!connectToService()) {
                            action.notifyObbStateChange(new ObbException(ERROR_INTERNAL,
                                    "Failed to bind to media container service"));
                            return;
                        }
                    }

                    mActions.add(action);
                    break;
                }
                case OBB_MCS_BOUND: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_BOUND");
                    if (msg.obj != null) {
                        mContainerService = (IMediaContainerService) msg.obj;
                    }
                    if (mContainerService == null) {
                        // Something seriously wrong. Bail out
                        for (ObbAction action : mActions) {
                            // Indicate service bind error
                            action.notifyObbStateChange(new ObbException(ERROR_INTERNAL,
                                    "Failed to bind to media container service"));
                        }
                        mActions.clear();
                    } else if (mActions.size() > 0) {
                        final ObbAction action = mActions.get(0);
                        if (action != null) {
                            action.execute(this);
                        }
                    } else {
                        // Should never happen ideally.
                        Slog.w(TAG, "Empty queue");
                    }
                    break;
                }
                case OBB_MCS_RECONNECT: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_RECONNECT");
                    if (mActions.size() > 0) {
                        if (mBound) {
                            disconnectService();
                        }
                        if (!connectToService()) {
                            for (ObbAction action : mActions) {
                                // Indicate service bind error
                                action.notifyObbStateChange(new ObbException(ERROR_INTERNAL,
                                        "Failed to bind to media container service"));
                            }
                            mActions.clear();
                        }
                    }
                    break;
                }
                case OBB_MCS_UNBIND: {
                    if (DEBUG_OBB)
                        Slog.i(TAG, "OBB_MCS_UNBIND");

                    // Delete pending install
                    if (mActions.size() > 0) {
                        mActions.remove(0);
                    }
                    if (mActions.size() == 0) {
                        if (mBound) {
                            disconnectService();
                        }
                    } else {
                        // There are more pending requests in queue.
                        // Just post MCS_BOUND message to trigger processing
                        // of next pending install.
                        mObbActionHandler.sendEmptyMessage(OBB_MCS_BOUND);
                    }
                    break;
                }
                case OBB_FLUSH_MOUNT_STATE: {
                    final String path = (String) msg.obj;

                    if (DEBUG_OBB)
                        Slog.i(TAG, "Flushing all OBB state for path " + path);

                    synchronized (mObbMounts) {
                        final List<ObbState> obbStatesToRemove = new LinkedList<ObbState>();

                        final Iterator<ObbState> i = mObbPathToStateMap.values().iterator();
                        while (i.hasNext()) {
                            final ObbState state = i.next();

                            /*
                             * If this entry's source file is in the volume path
                             * that got unmounted, remove it because it's no
                             * longer valid.
                             */
                            if (state.canonicalPath.startsWith(path)) {
                                obbStatesToRemove.add(state);
                            }
                        }

                        for (final ObbState obbState : obbStatesToRemove) {
                            if (DEBUG_OBB)
                                Slog.i(TAG, "Removing state for " + obbState.rawPath);

                            removeObbStateLocked(obbState);

                            try {
                                obbState.token.onObbResult(obbState.rawPath, obbState.nonce,
                                        OnObbStateChangeListener.UNMOUNTED);
                            } catch (RemoteException e) {
                                Slog.i(TAG, "Couldn't send unmount notification for  OBB: "
                                        + obbState.rawPath);
                            }
                        }
                    }
                    break;
                }
            }
        }

        private boolean connectToService() {
            if (DEBUG_OBB)
                Slog.i(TAG, "Trying to bind to DefaultContainerService");

            Intent service = new Intent().setComponent(DEFAULT_CONTAINER_COMPONENT);
            if (mContext.bindServiceAsUser(service, mDefContainerConn, Context.BIND_AUTO_CREATE,
                    UserHandle.SYSTEM)) {
                mBound = true;
                return true;
            }
            return false;
        }

        private void disconnectService() {
            mContainerService = null;
            mBound = false;
            mContext.unbindService(mDefContainerConn);
        }
    }

    private static class ObbException extends Exception {
        public final int status;

        public ObbException(int status, String message) {
            super(message);
            this.status = status;
        }

        public ObbException(int status, Throwable cause) {
            super(cause.getMessage(), cause);
            this.status = status;
        }
    }

    abstract class ObbAction {
        private static final int MAX_RETRIES = 3;
        private int mRetries;

        ObbState mObbState;

        ObbAction(ObbState obbState) {
            mObbState = obbState;
        }

        public void execute(ObbActionHandler handler) {
            try {
                if (DEBUG_OBB)
                    Slog.i(TAG, "Starting to execute action: " + toString());
                mRetries++;
                if (mRetries > MAX_RETRIES) {
                    mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
                    notifyObbStateChange(new ObbException(ERROR_INTERNAL,
                            "Failed to bind to media container service"));
                } else {
                    handleExecute();
                    if (DEBUG_OBB)
                        Slog.i(TAG, "Posting install MCS_UNBIND");
                    mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
                }
            } catch (ObbException e) {
                notifyObbStateChange(e);
                mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
            }
        }

        abstract void handleExecute() throws ObbException;

        protected ObbInfo getObbInfo() throws ObbException {
            final ObbInfo obbInfo;
            try {
                obbInfo = mContainerService.getObbInfo(mObbState.canonicalPath);
            } catch (Exception e) {
                throw new ObbException(ERROR_PERMISSION_DENIED, e);
            }
            if (obbInfo != null) {
                return obbInfo;
            } else {
                throw new ObbException(ERROR_INTERNAL,
                        "Missing OBB info for: " + mObbState.canonicalPath);
            }
        }

        protected void notifyObbStateChange(ObbException e) {
            Slog.w(TAG, e);
            notifyObbStateChange(e.status);
        }

        protected void notifyObbStateChange(int status) {
            if (mObbState == null || mObbState.token == null) {
                return;
            }

            try {
                mObbState.token.onObbResult(mObbState.rawPath, mObbState.nonce, status);
            } catch (RemoteException e) {
                Slog.w(TAG, "StorageEventListener went away while calling onObbStateChanged");
            }
        }
    }

    class MountObbAction extends ObbAction {
        private final String mKey;
        private final int mCallingUid;

        MountObbAction(ObbState obbState, String key, int callingUid) {
            super(obbState);
            mKey = key;
            mCallingUid = callingUid;
        }

        @Override
        public void handleExecute() throws ObbException {
            warnOnNotMounted();

            final ObbInfo obbInfo = getObbInfo();

            if (!isUidOwnerOfPackageOrSystem(obbInfo.packageName, mCallingUid)) {
                throw new ObbException(ERROR_PERMISSION_DENIED, "Denied attempt to mount OBB "
                        + obbInfo.filename + " which is owned by " + obbInfo.packageName);
            }

            final boolean isMounted;
            synchronized (mObbMounts) {
                isMounted = mObbPathToStateMap.containsKey(mObbState.rawPath);
            }
            if (isMounted) {
                throw new ObbException(ERROR_ALREADY_MOUNTED,
                        "Attempt to mount OBB which is already mounted: " + obbInfo.filename);
            }

            final String hashedKey;
            final String binderKey;
            if (mKey == null) {
                hashedKey = "none";
                binderKey = "";
            } else {
                try {
                    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");

                    KeySpec ks = new PBEKeySpec(mKey.toCharArray(), obbInfo.salt,
                            PBKDF2_HASH_ROUNDS, CRYPTO_ALGORITHM_KEY_SIZE);
                    SecretKey key = factory.generateSecret(ks);
                    BigInteger bi = new BigInteger(key.getEncoded());
                    hashedKey = bi.toString(16);
                    binderKey = hashedKey;
                } catch (GeneralSecurityException e) {
                    throw new ObbException(ERROR_INTERNAL, e);
                }
            }

            try {
                mObbState.volId = mVold.createObb(mObbState.canonicalPath, binderKey,
                        mObbState.ownerGid);
                mVold.mount(mObbState.volId, 0, -1);

                if (DEBUG_OBB)
                    Slog.d(TAG, "Successfully mounted OBB " + mObbState.canonicalPath);

                synchronized (mObbMounts) {
                    addObbStateLocked(mObbState);
                }

                notifyObbStateChange(MOUNTED);
            } catch (Exception e) {
                throw new ObbException(ERROR_COULD_NOT_MOUNT, e);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("MountObbAction{");
            sb.append(mObbState);
            sb.append('}');
            return sb.toString();
        }
    }

    class UnmountObbAction extends ObbAction {
        private final boolean mForceUnmount;

        UnmountObbAction(ObbState obbState, boolean force) {
            super(obbState);
            mForceUnmount = force;
        }

        @Override
        public void handleExecute() throws ObbException {
            warnOnNotMounted();

            final ObbState existingState;
            synchronized (mObbMounts) {
                existingState = mObbPathToStateMap.get(mObbState.rawPath);
            }

            if (existingState == null) {
                throw new ObbException(ERROR_NOT_MOUNTED, "Missing existingState");
            }

            if (existingState.ownerGid != mObbState.ownerGid) {
                notifyObbStateChange(new ObbException(ERROR_PERMISSION_DENIED,
                        "Permission denied to unmount OBB " + existingState.rawPath
                                + " (owned by GID " + existingState.ownerGid + ")"));
                return;
            }

            try {
                mVold.unmount(mObbState.volId);
                mVold.destroyObb(mObbState.volId);
                mObbState.volId = null;

                synchronized (mObbMounts) {
                    removeObbStateLocked(existingState);
                }

                notifyObbStateChange(UNMOUNTED);
            } catch (Exception e) {
                throw new ObbException(ERROR_COULD_NOT_UNMOUNT, e);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("UnmountObbAction{");
            sb.append(mObbState);
            sb.append(",force=");
            sb.append(mForceUnmount);
            sb.append('}');
            return sb.toString();
        }
    }

    private void dispatchOnStatus(IVoldTaskListener listener, int status,
            PersistableBundle extras) {
        if (listener != null) {
            try {
                listener.onStatus(status, extras);
            } catch (RemoteException ignored) {
            }
        }
    }

    private void dispatchOnFinished(IVoldTaskListener listener, int status,
            PersistableBundle extras) {
        if (listener != null) {
            try {
                listener.onFinished(status, extras);
            } catch (RemoteException ignored) {
            }
        }
    }

    private static class Callbacks extends Handler {
        private static final int MSG_STORAGE_STATE_CHANGED = 1;
        private static final int MSG_VOLUME_STATE_CHANGED = 2;
        private static final int MSG_VOLUME_RECORD_CHANGED = 3;
        private static final int MSG_VOLUME_FORGOTTEN = 4;
        private static final int MSG_DISK_SCANNED = 5;
        private static final int MSG_DISK_DESTROYED = 6;

        private final RemoteCallbackList<IStorageEventListener>
                mCallbacks = new RemoteCallbackList<>();

        public Callbacks(Looper looper) {
            super(looper);
        }

        public void register(IStorageEventListener callback) {
            mCallbacks.register(callback);
        }

        public void unregister(IStorageEventListener callback) {
            mCallbacks.unregister(callback);
        }

        @Override
        public void handleMessage(Message msg) {
            final SomeArgs args = (SomeArgs) msg.obj;
            final int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                final IStorageEventListener callback = mCallbacks.getBroadcastItem(i);
                try {
                    invokeCallback(callback, msg.what, args);
                } catch (RemoteException ignored) {
                }
            }
            mCallbacks.finishBroadcast();
            args.recycle();
        }

        private void invokeCallback(IStorageEventListener callback, int what, SomeArgs args)
                throws RemoteException {
            switch (what) {
                case MSG_STORAGE_STATE_CHANGED: {
                    callback.onStorageStateChanged((String) args.arg1, (String) args.arg2,
                            (String) args.arg3);
                    break;
                }
                case MSG_VOLUME_STATE_CHANGED: {
                    callback.onVolumeStateChanged((VolumeInfo) args.arg1, args.argi2, args.argi3);
                    break;
                }
                case MSG_VOLUME_RECORD_CHANGED: {
                    callback.onVolumeRecordChanged((VolumeRecord) args.arg1);
                    break;
                }
                case MSG_VOLUME_FORGOTTEN: {
                    callback.onVolumeForgotten((String) args.arg1);
                    break;
                }
                case MSG_DISK_SCANNED: {
                    callback.onDiskScanned((DiskInfo) args.arg1, args.argi2);
                    break;
                }
                case MSG_DISK_DESTROYED: {
                    callback.onDiskDestroyed((DiskInfo) args.arg1);
                    break;
                }
            }
        }

        private void notifyStorageStateChanged(String path, String oldState, String newState) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = path;
            args.arg2 = oldState;
            args.arg3 = newState;
            obtainMessage(MSG_STORAGE_STATE_CHANGED, args).sendToTarget();
        }

        private void notifyVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = vol.clone();
            args.argi2 = oldState;
            args.argi3 = newState;
            obtainMessage(MSG_VOLUME_STATE_CHANGED, args).sendToTarget();
        }

        private void notifyVolumeRecordChanged(VolumeRecord rec) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = rec.clone();
            obtainMessage(MSG_VOLUME_RECORD_CHANGED, args).sendToTarget();
        }

        private void notifyVolumeForgotten(String fsUuid) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = fsUuid;
            obtainMessage(MSG_VOLUME_FORGOTTEN, args).sendToTarget();
        }

        private void notifyDiskScanned(DiskInfo disk, int volumeCount) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = disk.clone();
            args.argi2 = volumeCount;
            obtainMessage(MSG_DISK_SCANNED, args).sendToTarget();
        }

        private void notifyDiskDestroyed(DiskInfo disk) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = disk.clone();
            obtainMessage(MSG_DISK_DESTROYED, args).sendToTarget();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ", 160);
        synchronized (mLock) {
            pw.println("Disks:");
            pw.increaseIndent();
            for (int i = 0; i < mDisks.size(); i++) {
                final DiskInfo disk = mDisks.valueAt(i);
                disk.dump(pw);
            }
            pw.decreaseIndent();

            pw.println();
            pw.println("Volumes:");
            pw.increaseIndent();
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (VolumeInfo.ID_PRIVATE_INTERNAL.equals(vol.id)) continue;
                vol.dump(pw);
            }
            pw.decreaseIndent();

            pw.println();
            pw.println("Records:");
            pw.increaseIndent();
            for (int i = 0; i < mRecords.size(); i++) {
                final VolumeRecord note = mRecords.valueAt(i);
                note.dump(pw);
            }
            pw.decreaseIndent();

            pw.println();
            pw.println("Primary storage UUID: " + mPrimaryStorageUuid);
            final Pair<String, Long> pair = StorageManager.getPrimaryStoragePathAndSize();
            if (pair == null) {
                pw.println("Internal storage total size: N/A");
            } else {
                pw.print("Internal storage (");
                pw.print(pair.first);
                pw.print(") total size: ");
                pw.print(pair.second);
                pw.print(" (");
                pw.print(DataUnit.MEBIBYTES.toBytes(pair.second));
                pw.println(" MiB)");
            }
            pw.println("Local unlocked users: " + Arrays.toString(mLocalUnlockedUsers));
            pw.println("System unlocked users: " + Arrays.toString(mSystemUnlockedUsers));
        }

        synchronized (mObbMounts) {
            pw.println();
            pw.println("mObbMounts:");
            pw.increaseIndent();
            final Iterator<Entry<IBinder, List<ObbState>>> binders = mObbMounts.entrySet()
                    .iterator();
            while (binders.hasNext()) {
                Entry<IBinder, List<ObbState>> e = binders.next();
                pw.println(e.getKey() + ":");
                pw.increaseIndent();
                final List<ObbState> obbStates = e.getValue();
                for (final ObbState obbState : obbStates) {
                    pw.println(obbState);
                }
                pw.decreaseIndent();
            }
            pw.decreaseIndent();

            pw.println();
            pw.println("mObbPathToStateMap:");
            pw.increaseIndent();
            final Iterator<Entry<String, ObbState>> maps = mObbPathToStateMap.entrySet().iterator();
            while (maps.hasNext()) {
                final Entry<String, ObbState> e = maps.next();
                pw.print(e.getKey());
                pw.print(" -> ");
                pw.println(e.getValue());
            }
            pw.decreaseIndent();
        }

        pw.println();
        pw.print("Last maintenance: ");
        pw.println(TimeUtils.formatForLogging(mLastMaintenance));
    }

    /** {@inheritDoc} */
    @Override
    public void monitor() {
        try {
            mVold.monitor();
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    private final class StorageManagerInternalImpl extends StorageManagerInternal {
        // Not guarded by a lock.
        private final CopyOnWriteArrayList<ExternalStorageMountPolicy> mPolicies =
                new CopyOnWriteArrayList<>();

        @Override
        public void addExternalStoragePolicy(ExternalStorageMountPolicy policy) {
            // No locking - CopyOnWriteArrayList
            mPolicies.add(policy);
        }

        @Override
        public void onExternalStoragePolicyChanged(int uid, String packageName) {
            final int mountMode = getExternalStorageMountMode(uid, packageName);
            remountUidExternalStorage(uid, mountMode);
        }

        @Override
        public int getExternalStorageMountMode(int uid, String packageName) {
            // No locking - CopyOnWriteArrayList
            int mountMode = Integer.MAX_VALUE;
            for (ExternalStorageMountPolicy policy : mPolicies) {
                final int policyMode = policy.getMountMode(uid, packageName);
                if (policyMode == Zygote.MOUNT_EXTERNAL_NONE) {
                    return Zygote.MOUNT_EXTERNAL_NONE;
                }
                mountMode = Math.min(mountMode, policyMode);
            }
            if (mountMode == Integer.MAX_VALUE) {
                return Zygote.MOUNT_EXTERNAL_NONE;
            }
            return mountMode;
        }

        public boolean hasExternalStorage(int uid, String packageName) {
            // No need to check for system uid. This avoids a deadlock between
            // PackageManagerService and AppOpsService.
            if (uid == Process.SYSTEM_UID) {
                return true;
            }
            // No locking - CopyOnWriteArrayList
            for (ExternalStorageMountPolicy policy : mPolicies) {
                final boolean policyHasStorage = policy.hasExternalStorage(uid, packageName);
                if (!policyHasStorage) {
                    return false;
                }
            }
            return true;
        }
    }
}
