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

import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.Manifest;
import android.annotation.Nullable;
import android.app.ActivityManagerNative;
import android.app.AppOpsManager;
import android.app.IActivityManager;
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
import android.net.Uri;
import android.os.Binder;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.DiskInfo;
import android.os.storage.IMountService;
import android.os.storage.IMountServiceListener;
import android.os.storage.IMountShutdownObserver;
import android.os.storage.IObbActionListener;
import android.os.storage.MountServiceInternal;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageResultCode;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.Xml;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IMediaContainerService;
import com.android.internal.os.SomeArgs;
import com.android.internal.os.Zygote;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.NativeDaemonConnector.Command;
import com.android.server.NativeDaemonConnector.SensitiveArg;
import com.android.server.pm.PackageManagerService;

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
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
class MountService extends IMountService.Stub
        implements INativeDaemonConnectorCallbacks, Watchdog.Monitor {

    // Static direct instance pointer for the tightly-coupled idle service to use
    static MountService sSelf = null;

    public static class Lifecycle extends SystemService {
        private MountService mMountService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mMountService = new MountService(getContext());
            publishBinderService("mount", mMountService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mMountService.systemReady();
            }
        }

        @Override
        public void onStartUser(int userHandle) {
            mMountService.onStartUser(userHandle);
        }

        @Override
        public void onCleanupUser(int userHandle) {
            mMountService.onCleanupUser(userHandle);
        }
    }

    private static final boolean DEBUG_EVENTS = false;
    private static final boolean DEBUG_OBB = false;

    // Disable this since it messes up long-running cryptfs operations.
    private static final boolean WATCHDOG_ENABLE = false;

    private static final String TAG = "MountService";

    private static final String TAG_STORAGE_BENCHMARK = "storage_benchmark";
    private static final String TAG_STORAGE_TRIM = "storage_trim";

    private static final String VOLD_TAG = "VoldConnector";
    private static final String CRYPTD_TAG = "CryptdConnector";

    /** Maximum number of ASEC containers allowed to be mounted. */
    private static final int MAX_CONTAINERS = 250;

    /** Magic value sent by MoveTask.cpp */
    private static final int MOVE_STATUS_COPY_FINISHED = 82;

    /*
     * Internal vold response code constants
     */
    class VoldResponseCode {
        /*
         * 100 series - Requestion action was initiated; expect another reply
         *              before proceeding with a new command.
         */
        public static final int VolumeListResult               = 110;
        public static final int AsecListResult                 = 111;
        public static final int StorageUsersListResult         = 112;
        public static final int CryptfsGetfieldResult          = 113;

        /*
         * 200 series - Requestion action has been successfully completed.
         */
        public static final int ShareStatusResult              = 210;
        public static final int AsecPathResult                 = 211;
        public static final int ShareEnabledResult             = 212;

        /*
         * 400 series - Command was accepted, but the requested action
         *              did not take place.
         */
        public static final int OpFailedNoMedia                = 401;
        public static final int OpFailedMediaBlank             = 402;
        public static final int OpFailedMediaCorrupt           = 403;
        public static final int OpFailedVolNotMounted          = 404;
        public static final int OpFailedStorageBusy            = 405;
        public static final int OpFailedStorageNotFound        = 406;

        /*
         * 600 series - Unsolicited broadcasts.
         */
        public static final int DISK_CREATED = 640;
        public static final int DISK_SIZE_CHANGED = 641;
        public static final int DISK_LABEL_CHANGED = 642;
        public static final int DISK_SCANNED = 643;
        public static final int DISK_SYS_PATH_CHANGED = 644;
        public static final int DISK_DESTROYED = 649;

        public static final int VOLUME_CREATED = 650;
        public static final int VOLUME_STATE_CHANGED = 651;
        public static final int VOLUME_FS_TYPE_CHANGED = 652;
        public static final int VOLUME_FS_UUID_CHANGED = 653;
        public static final int VOLUME_FS_LABEL_CHANGED = 654;
        public static final int VOLUME_PATH_CHANGED = 655;
        public static final int VOLUME_INTERNAL_PATH_CHANGED = 656;
        public static final int VOLUME_DESTROYED = 659;

        public static final int MOVE_STATUS = 660;
        public static final int BENCHMARK_RESULT = 661;
        public static final int TRIM_RESULT = 662;
    }

    private static final int VERSION_INIT = 1;
    private static final int VERSION_ADD_PRIMARY = 2;
    private static final int VERSION_FIX_PRIMARY = 3;

    private static final String TAG_VOLUMES = "volumes";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_PRIMARY_STORAGE_UUID = "primaryStorageUuid";
    private static final String ATTR_FORCE_ADOPTABLE = "forceAdoptable";
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
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private int[] mStartedUsers = EmptyArray.INT;

    /** Map from disk ID to disk */
    @GuardedBy("mLock")
    private ArrayMap<String, DiskInfo> mDisks = new ArrayMap<>();
    /** Map from volume ID to disk */
    @GuardedBy("mLock")
    private ArrayMap<String, VolumeInfo> mVolumes = new ArrayMap<>();

    /** Map from UUID to record */
    @GuardedBy("mLock")
    private ArrayMap<String, VolumeRecord> mRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private String mPrimaryStorageUuid;
    @GuardedBy("mLock")
    private boolean mForceAdoptable;

    /** Map from disk ID to latches */
    @GuardedBy("mLock")
    private ArrayMap<String, CountDownLatch> mDiskScanLatches = new ArrayMap<>();

    @GuardedBy("mLock")
    private IPackageMoveObserver mMoveCallback;
    @GuardedBy("mLock")
    private String mMoveTargetUuid;

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
    private final NativeDaemonConnector mConnector;
    private final NativeDaemonConnector mCryptConnector;

    private volatile boolean mSystemReady = false;
    private volatile boolean mDaemonConnected = false;

    private PackageManagerService mPms;

    private final Callbacks mCallbacks;

    // Two connectors - mConnector & mCryptConnector
    private final CountDownLatch mConnectedSignal = new CountDownLatch(2);
    private final CountDownLatch mAsecsScanned = new CountDownLatch(1);

    private final Object mUnmountLock = new Object();
    @GuardedBy("mUnmountLock")
    private CountDownLatch mUnmountSignal;

    /**
     * Private hash of currently mounted secure containers.
     * Used as a lock in methods to manipulate secure containers.
     */
    final private HashSet<String> mAsecMountSet = new HashSet<String>();

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
    private final MountServiceInternalImpl mMountServiceInternal = new MountServiceInternalImpl();

    class ObbState implements IBinder.DeathRecipient {
        public ObbState(String rawPath, String canonicalPath, int callingUid,
                IObbActionListener token, int nonce) {
            this.rawPath = rawPath;
            this.canonicalPath = canonicalPath.toString();

            final int userId = UserHandle.getUserId(callingUid);
            this.ownerPath = buildObbPath(canonicalPath, userId, false);
            this.voldPath = buildObbPath(canonicalPath, userId, true);

            this.ownerGid = UserHandle.getSharedAppGid(callingUid);
            this.token = token;
            this.nonce = nonce;
        }

        final String rawPath;
        final String canonicalPath;
        final String ownerPath;
        final String voldPath;

        final int ownerGid;

        // Token of remote Binder caller
        final IObbActionListener token;

        // Identifier to pass back to the token
        final int nonce;

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
            sb.append(",ownerPath=").append(ownerPath);
            sb.append(",voldPath=").append(voldPath);
            sb.append(",ownerGid=").append(ownerGid);
            sb.append(",token=").append(token);
            sb.append(",binder=").append(getBinder());
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
    };

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

    class MountServiceHandler extends Handler {
        public MountServiceHandler(Looper looper) {
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
                    if (!isReady()) {
                        Slog.i(TAG, "fstrim requested, but no daemon connection yet; trying again");
                        sendMessageDelayed(obtainMessage(H_FSTRIM, msg.obj),
                                DateUtils.SECOND_IN_MILLIS);
                        break;
                    }

                    Slog.i(TAG, "Running fstrim idle maintenance");

                    // Remember when we kicked it off
                    try {
                        mLastMaintenance = System.currentTimeMillis();
                        mLastMaintenanceFile.setLastModified(mLastMaintenance);
                    } catch (Exception e) {
                        Slog.e(TAG, "Unable to record last fstrim!");
                    }

                    final boolean shouldBenchmark = shouldBenchmark();
                    try {
                        // This method must be run on the main (handler) thread,
                        // so it is safe to directly call into vold.
                        mConnector.execute("fstrim", shouldBenchmark ? "dotrimbench" : "dotrim");
                    } catch (NativeDaemonConnectorException ndce) {
                        Slog.e(TAG, "Failed to run fstrim!");
                    }

                    // invoke the completion callback, if any
                    // TODO: fstrim is non-blocking, so remove this useless callback
                    Runnable callback = (Runnable) msg.obj;
                    if (callback != null) {
                        callback.run();
                    }
                    break;
                }
                case H_SHUTDOWN: {
                    final IMountShutdownObserver obs = (IMountShutdownObserver) msg.obj;
                    boolean success = false;
                    try {
                        success = mConnector.execute("volume", "shutdown").isClassOk();
                    } catch (NativeDaemonConnectorException ignored) {
                    }
                    if (obs != null) {
                        try {
                            obs.onShutDownComplete(success ? 0 : -1);
                        } catch (RemoteException ignored) {
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
                        mConnector.execute("volume", "mount", vol.id, vol.mountFlags,
                                vol.mountUserId);
                    } catch (NativeDaemonConnectorException ignored) {
                    }
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
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
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

            try {
                if (Intent.ACTION_USER_ADDED.equals(action)) {
                    final UserManager um = mContext.getSystemService(UserManager.class);
                    final int userSerialNumber = um.getUserSerialNumber(userId);
                    mConnector.execute("volume", "user_added", userId, userSerialNumber);
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    mConnector.execute("volume", "user_removed", userId);
                }
            } catch (NativeDaemonConnectorException e) {
                Slog.w(TAG, "Failed to send user details to vold", e);
            }
        }
    };

    @Override
    public void waitForAsecScan() {
        waitForLatch(mAsecsScanned, "mAsecsScanned");
    }

    private void waitForReady() {
        waitForLatch(mConnectedSignal, "mConnectedSignal");
    }

    private void waitForLatch(CountDownLatch latch, String condition) {
        try {
            waitForLatch(latch, condition, -1);
        } catch (TimeoutException ignored) {
        }
    }

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

    private boolean isReady() {
        try {
            return mConnectedSignal.await(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void handleSystemReady() {
        synchronized (mLock) {
            resetIfReadyAndConnectedLocked();
        }

        // Start scheduling nominally-daily fstrim operations
        MountServiceIdler.scheduleIdlePass(mContext);
    }

    /**
     * MediaProvider has a ton of code that makes assumptions about storage
     * paths never changing, so we outright kill them to pick up new state.
     */
    @Deprecated
    private void killMediaProvider() {
        final long token = Binder.clearCallingIdentity();
        try {
            final ProviderInfo provider = mPms.resolveContentProvider(MediaStore.AUTHORITY, 0,
                    UserHandle.USER_OWNER);
            if (provider != null) {
                final IActivityManager am = ActivityManagerNative.getDefault();
                try {
                    am.killApplicationWithAppId(provider.applicationInfo.packageName,
                            UserHandle.getAppId(provider.applicationInfo.uid), "vold reset");
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void addInternalVolume() {
        // Create a stub volume that represents internal storage
        final VolumeInfo internal = new VolumeInfo(VolumeInfo.ID_PRIVATE_INTERNAL,
                VolumeInfo.TYPE_PRIVATE, null, null);
        internal.state = VolumeInfo.STATE_MOUNTED;
        internal.path = Environment.getDataDirectory().getAbsolutePath();
        mVolumes.put(internal.id, internal);
    }

    private void resetIfReadyAndConnectedLocked() {
        Slog.d(TAG, "Thinking about reset, mSystemReady=" + mSystemReady
                + ", mDaemonConnected=" + mDaemonConnected);
        if (mSystemReady && mDaemonConnected) {
            killMediaProvider();

            mDisks.clear();
            mVolumes.clear();

            addInternalVolume();

            try {
                mConnector.execute("volume", "reset");

                // Tell vold about all existing and started users
                final UserManager um = mContext.getSystemService(UserManager.class);
                final List<UserInfo> users = um.getUsers();
                for (UserInfo user : users) {
                    mConnector.execute("volume", "user_added", user.id, user.serialNumber);
                }
                for (int userId : mStartedUsers) {
                    mConnector.execute("volume", "user_started", userId);
                }
            } catch (NativeDaemonConnectorException e) {
                Slog.w(TAG, "Failed to reset vold", e);
            }
        }
    }

    private void onStartUser(int userId) {
        Slog.d(TAG, "onStartUser " + userId);

        // We purposefully block here to make sure that user-specific
        // staging area is ready so it's ready for zygote-forked apps to
        // bind mount against.
        try {
            mConnector.execute("volume", "user_started", userId);
        } catch (NativeDaemonConnectorException ignored) {
        }

        // Record user as started so newly mounted volumes kick off events
        // correctly, then synthesize events for any already-mounted volumes.
        synchronized (mVolumes) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (vol.isVisibleForRead(userId) && vol.isMountedReadable()) {
                    final StorageVolume userVol = vol.buildStorageVolume(mContext, userId, false);
                    mHandler.obtainMessage(H_VOLUME_BROADCAST, userVol).sendToTarget();

                    final String envState = VolumeInfo.getEnvironmentForState(vol.getState());
                    mCallbacks.notifyStorageStateChanged(userVol.getPath(), envState, envState);
                }
            }
            mStartedUsers = ArrayUtils.appendInt(mStartedUsers, userId);
        }
    }

    private void onCleanupUser(int userId) {
        Slog.d(TAG, "onCleanupUser " + userId);

        try {
            mConnector.execute("volume", "user_stopped", userId);
        } catch (NativeDaemonConnectorException ignored) {
        }

        synchronized (mVolumes) {
            mStartedUsers = ArrayUtils.removeInt(mStartedUsers, userId);
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

    /**
     * Callback from NativeDaemonConnector
     */
    @Override
    public void onDaemonConnected() {
        mDaemonConnected = true;
        mHandler.obtainMessage(H_DAEMON_CONNECTED).sendToTarget();
    }

    private void handleDaemonConnected() {
        synchronized (mLock) {
            resetIfReadyAndConnectedLocked();
        }

        /*
         * Now that we've done our initialization, release
         * the hounds!
         */
        mConnectedSignal.countDown();
        if (mConnectedSignal.getCount() != 0) {
            // More daemons need to connect
            return;
        }

        // On an encrypted device we can't see system properties yet, so pull
        // the system locale out of the mount service.
        if ("".equals(SystemProperties.get("vold.encrypt_progress"))) {
            copyLocaleFromMountService();
        }

        // Let package manager load internal ASECs.
        mPms.scanAvailableAsecs();

        // Notify people waiting for ASECs to be scanned that it's done.
        mAsecsScanned.countDown();
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
            ActivityManagerNative.getDefault().updateConfiguration(config);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error setting system locale from mount service", e);
        }

        // Temporary workaround for http://b/17945169.
        Slog.d(TAG, "Setting system properties to " + systemLocale + " from mount service");
        SystemProperties.set("persist.sys.locale", locale.toLanguageTag());
    }

    /**
     * Callback from NativeDaemonConnector
     */
    @Override
    public boolean onCheckHoldWakeLock(int code) {
        return false;
    }

    /**
     * Callback from NativeDaemonConnector
     */
    @Override
    public boolean onEvent(int code, String raw, String[] cooked) {
        synchronized (mLock) {
            return onEventLocked(code, raw, cooked);
        }
    }

    private boolean onEventLocked(int code, String raw, String[] cooked) {
        switch (code) {
            case VoldResponseCode.DISK_CREATED: {
                if (cooked.length != 3) break;
                final String id = cooked[1];
                int flags = Integer.parseInt(cooked[2]);
                if (SystemProperties.getBoolean(StorageManager.PROP_FORCE_ADOPTABLE, false)
                        || mForceAdoptable) {
                    flags |= DiskInfo.FLAG_ADOPTABLE;
                }
                mDisks.put(id, new DiskInfo(id, flags));
                break;
            }
            case VoldResponseCode.DISK_SIZE_CHANGED: {
                if (cooked.length != 3) break;
                final DiskInfo disk = mDisks.get(cooked[1]);
                if (disk != null) {
                    disk.size = Long.parseLong(cooked[2]);
                }
                break;
            }
            case VoldResponseCode.DISK_LABEL_CHANGED: {
                final DiskInfo disk = mDisks.get(cooked[1]);
                if (disk != null) {
                    final StringBuilder builder = new StringBuilder();
                    for (int i = 2; i < cooked.length; i++) {
                        builder.append(cooked[i]).append(' ');
                    }
                    disk.label = builder.toString().trim();
                }
                break;
            }
            case VoldResponseCode.DISK_SCANNED: {
                if (cooked.length != 2) break;
                final DiskInfo disk = mDisks.get(cooked[1]);
                if (disk != null) {
                    onDiskScannedLocked(disk);
                }
                break;
            }
            case VoldResponseCode.DISK_SYS_PATH_CHANGED: {
                if (cooked.length != 3) break;
                final DiskInfo disk = mDisks.get(cooked[1]);
                if (disk != null) {
                    disk.sysPath = cooked[2];
                }
                break;
            }
            case VoldResponseCode.DISK_DESTROYED: {
                if (cooked.length != 2) break;
                final DiskInfo disk = mDisks.remove(cooked[1]);
                if (disk != null) {
                    mCallbacks.notifyDiskDestroyed(disk);
                }
                break;
            }

            case VoldResponseCode.VOLUME_CREATED: {
                final String id = cooked[1];
                final int type = Integer.parseInt(cooked[2]);
                final String diskId = TextUtils.nullIfEmpty(cooked[3]);
                final String partGuid = TextUtils.nullIfEmpty(cooked[4]);

                final DiskInfo disk = mDisks.get(diskId);
                final VolumeInfo vol = new VolumeInfo(id, type, disk, partGuid);
                mVolumes.put(id, vol);
                onVolumeCreatedLocked(vol);
                break;
            }
            case VoldResponseCode.VOLUME_STATE_CHANGED: {
                if (cooked.length != 3) break;
                final VolumeInfo vol = mVolumes.get(cooked[1]);
                if (vol != null) {
                    final int oldState = vol.state;
                    final int newState = Integer.parseInt(cooked[2]);
                    vol.state = newState;
                    onVolumeStateChangedLocked(vol, oldState, newState);
                }
                break;
            }
            case VoldResponseCode.VOLUME_FS_TYPE_CHANGED: {
                if (cooked.length != 3) break;
                final VolumeInfo vol = mVolumes.get(cooked[1]);
                if (vol != null) {
                    vol.fsType = cooked[2];
                }
                break;
            }
            case VoldResponseCode.VOLUME_FS_UUID_CHANGED: {
                if (cooked.length != 3) break;
                final VolumeInfo vol = mVolumes.get(cooked[1]);
                if (vol != null) {
                    vol.fsUuid = cooked[2];
                }
                break;
            }
            case VoldResponseCode.VOLUME_FS_LABEL_CHANGED: {
                final VolumeInfo vol = mVolumes.get(cooked[1]);
                if (vol != null) {
                    final StringBuilder builder = new StringBuilder();
                    for (int i = 2; i < cooked.length; i++) {
                        builder.append(cooked[i]).append(' ');
                    }
                    vol.fsLabel = builder.toString().trim();
                }
                // TODO: notify listeners that label changed
                break;
            }
            case VoldResponseCode.VOLUME_PATH_CHANGED: {
                if (cooked.length != 3) break;
                final VolumeInfo vol = mVolumes.get(cooked[1]);
                if (vol != null) {
                    vol.path = cooked[2];
                }
                break;
            }
            case VoldResponseCode.VOLUME_INTERNAL_PATH_CHANGED: {
                if (cooked.length != 3) break;
                final VolumeInfo vol = mVolumes.get(cooked[1]);
                if (vol != null) {
                    vol.internalPath = cooked[2];
                }
                break;
            }
            case VoldResponseCode.VOLUME_DESTROYED: {
                if (cooked.length != 2) break;
                mVolumes.remove(cooked[1]);
                break;
            }

            case VoldResponseCode.MOVE_STATUS: {
                final int status = Integer.parseInt(cooked[1]);
                onMoveStatusLocked(status);
                break;
            }
            case VoldResponseCode.BENCHMARK_RESULT: {
                if (cooked.length != 7) break;
                final String path = cooked[1];
                final String ident = cooked[2];
                final long create = Long.parseLong(cooked[3]);
                final long drop = Long.parseLong(cooked[4]);
                final long run = Long.parseLong(cooked[5]);
                final long destroy = Long.parseLong(cooked[6]);

                final DropBoxManager dropBox = mContext.getSystemService(DropBoxManager.class);
                dropBox.addText(TAG_STORAGE_BENCHMARK, scrubPath(path)
                        + " " + ident + " " + create + " " + run + " " + destroy);

                final VolumeRecord rec = findRecordForPath(path);
                if (rec != null) {
                    rec.lastBenchMillis = System.currentTimeMillis();
                    writeSettingsLocked();
                }

                break;
            }
            case VoldResponseCode.TRIM_RESULT: {
                if (cooked.length != 4) break;
                final String path = cooked[1];
                final long bytes = Long.parseLong(cooked[2]);
                final long time = Long.parseLong(cooked[3]);

                final DropBoxManager dropBox = mContext.getSystemService(DropBoxManager.class);
                dropBox.addText(TAG_STORAGE_TRIM, scrubPath(path)
                        + " " + bytes + " " + time);

                final VolumeRecord rec = findRecordForPath(path);
                if (rec != null) {
                    rec.lastTrimMillis = System.currentTimeMillis();
                    writeSettingsLocked();
                }

                break;
            }

            default: {
                Slog.d(TAG, "Unhandled vold event " + code);
            }
        }

        return true;
    }

    private void onDiskScannedLocked(DiskInfo disk) {
        int volumeCount = 0;
        for (int i = 0; i < mVolumes.size(); i++) {
            final VolumeInfo vol = mVolumes.valueAt(i);
            if (Objects.equals(disk.id, vol.getDiskId())) {
                volumeCount++;
            }
        }

        final Intent intent = new Intent(DiskInfo.ACTION_DISK_SCANNED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
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

    private void onVolumeCreatedLocked(VolumeInfo vol) {
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

            vol.mountUserId = UserHandle.USER_OWNER;
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

        if (isBroadcastWorthy(vol)) {
            final Intent intent = new Intent(VolumeInfo.ACTION_VOLUME_STATE_CHANGED);
            intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.id);
            intent.putExtra(VolumeInfo.EXTRA_VOLUME_STATE, newState);
            intent.putExtra(VolumeRecord.EXTRA_FS_UUID, vol.fsUuid);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mHandler.obtainMessage(H_INTERNAL_BROADCAST, intent).sendToTarget();
        }

        final String oldStateEnv = VolumeInfo.getEnvironmentForState(oldState);
        final String newStateEnv = VolumeInfo.getEnvironmentForState(newState);

        if (!Objects.equals(oldStateEnv, newStateEnv)) {
            // Kick state changed event towards all started users. Any users
            // started after this point will trigger additional
            // user-specific broadcasts.
            for (int userId : mStartedUsers) {
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
    }

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
        if (vol.type == VolumeInfo.TYPE_PUBLIC || vol.type == VolumeInfo.TYPE_PRIVATE) {
            final UserManager userManager = mContext.getSystemService(UserManager.class);
            return userManager.hasUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
                    Binder.getCallingUserHandle());
        } else {
            return false;
        }
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
     * Constructs a new MountService instance
     *
     * @param context  Binder context for this service
     */
    public MountService(Context context) {
        sSelf = this;

        mContext = context;
        mCallbacks = new Callbacks(FgThread.get().getLooper());

        // XXX: This will go away soon in favor of IMountServiceObserver
        mPms = (PackageManagerService) ServiceManager.getService("package");

        HandlerThread hthread = new HandlerThread(TAG);
        hthread.start();
        mHandler = new MountServiceHandler(hthread.getLooper());

        // Add OBB Action Handler to MountService thread.
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
                new File(Environment.getSystemSecureDirectory(), "storage.xml"));

        synchronized (mLock) {
            readSettingsLocked();
        }

        LocalServices.addService(MountServiceInternal.class, mMountServiceInternal);

        /*
         * Create the connection to vold with a maximum queue of twice the
         * amount of containers we'd ever expect to have. This keeps an
         * "asec list" from blocking a thread repeatedly.
         */

        mConnector = new NativeDaemonConnector(this, "vold", MAX_CONTAINERS * 2, VOLD_TAG, 25,
                null);
        mConnector.setDebug(true);

        Thread thread = new Thread(mConnector, VOLD_TAG);
        thread.start();

        // Reuse parameters from first connector since they are tested and safe
        mCryptConnector = new NativeDaemonConnector(this, "cryptd",
                MAX_CONTAINERS * 2, CRYPTD_TAG, 25, null);
        mCryptConnector.setDebug(true);

        Thread crypt_thread = new Thread(mCryptConnector, CRYPTD_TAG);
        crypt_thread.start();

        final IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_ADDED);
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(mUserReceiver, userFilter, null, mHandler);

        addInternalVolume();

        // Add ourself to the Watchdog monitors if enabled.
        if (WATCHDOG_ENABLE) {
            Watchdog.getInstance().addMonitor(this);
        }
    }

    private void systemReady() {
        mSystemReady = true;
        mHandler.obtainMessage(H_SYSTEM_READY).sendToTarget();
    }

    private String getDefaultPrimaryStorageUuid() {
        if (SystemProperties.getBoolean(StorageManager.PROP_PRIMARY_PHYSICAL, false)) {
            return StorageManager.UUID_PRIMARY_PHYSICAL;
        } else {
            return StorageManager.UUID_PRIVATE_INTERNAL;
        }
    }

    private void readSettingsLocked() {
        mRecords.clear();
        mPrimaryStorageUuid = getDefaultPrimaryStorageUuid();
        mForceAdoptable = false;

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
                        mForceAdoptable = readBooleanAttribute(in, ATTR_FORCE_ADOPTABLE, false);

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
            writeBooleanAttribute(out, ATTR_FORCE_ADOPTABLE, mForceAdoptable);
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
    public void registerListener(IMountServiceListener listener) {
        mCallbacks.register(listener);
    }

    @Override
    public void unregisterListener(IMountServiceListener listener) {
        mCallbacks.unregister(listener);
    }

    @Override
    public void shutdown(final IMountShutdownObserver observer) {
        enforcePermission(android.Manifest.permission.SHUTDOWN);

        Slog.i(TAG, "Shutting down");
        mHandler.obtainMessage(H_SHUTDOWN, observer).sendToTarget();
    }

    @Override
    public boolean isUsbMassStorageConnected() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setUsbMassStorageEnabled(boolean enable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isUsbMassStorageEnabled() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getVolumeState(String mountPoint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isExternalStorageEmulated() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int mountVolume(String path) {
        mount(findVolumeIdForPathOrThrow(path));
        return 0;
    }

    @Override
    public void unmountVolume(String path, boolean force, boolean removeEncryption) {
        unmount(findVolumeIdForPathOrThrow(path));
    }

    @Override
    public int formatVolume(String path) {
        format(findVolumeIdForPathOrThrow(path));
        return 0;
    }

    @Override
    public void mount(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        final VolumeInfo vol = findVolumeByIdOrThrow(volId);
        if (isMountDisallowed(vol)) {
            throw new SecurityException("Mounting " + volId + " restricted by policy");
        }
        try {
            mConnector.execute("volume", "mount", vol.id, vol.mountFlags, vol.mountUserId);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void unmount(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        final VolumeInfo vol = findVolumeByIdOrThrow(volId);

        // TODO: expand PMS to know about multiple volumes
        if (vol.isPrimaryPhysical()) {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mUnmountLock) {
                    mUnmountSignal = new CountDownLatch(1);
                    mPms.updateExternalMediaStatus(false, true);
                    waitForLatch(mUnmountSignal, "mUnmountSignal");
                    mUnmountSignal = null;
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        try {
            mConnector.execute("volume", "unmount", vol.id);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void format(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        waitForReady();

        final VolumeInfo vol = findVolumeByIdOrThrow(volId);
        try {
            mConnector.execute("volume", "format", vol.id, "auto");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public long benchmark(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        waitForReady();

        try {
            // TODO: make benchmark async so we don't block other commands
            final NativeDaemonEvent res = mConnector.execute(3 * DateUtils.MINUTE_IN_MILLIS,
                    "volume", "benchmark", volId);
            return Long.parseLong(res.getMessage());
        } catch (NativeDaemonTimeoutException e) {
            return Long.MAX_VALUE;
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void partitionPublic(String diskId) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        waitForReady();

        final CountDownLatch latch = findOrCreateDiskScanLatch(diskId);
        try {
            mConnector.execute("volume", "partition", diskId, "public");
            waitForLatch(latch, "partitionPublic", 3 * DateUtils.MINUTE_IN_MILLIS);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        } catch (TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void partitionPrivate(String diskId) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        enforceAdminUser();
        waitForReady();

        final CountDownLatch latch = findOrCreateDiskScanLatch(diskId);
        try {
            mConnector.execute("volume", "partition", diskId, "private");
            waitForLatch(latch, "partitionPrivate", 3 * DateUtils.MINUTE_IN_MILLIS);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        } catch (TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void partitionMixed(String diskId, int ratio) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        enforceAdminUser();
        waitForReady();

        final CountDownLatch latch = findOrCreateDiskScanLatch(diskId);
        try {
            mConnector.execute("volume", "partition", diskId, "mixed", ratio);
            waitForLatch(latch, "partitionMixed", 3 * DateUtils.MINUTE_IN_MILLIS);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        } catch (TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void setVolumeNickname(String fsUuid, String nickname) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

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
        waitForReady();

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
        waitForReady();

        Preconditions.checkNotNull(fsUuid);
        synchronized (mLock) {
            final VolumeRecord rec = mRecords.remove(fsUuid);
            if (rec != null && !TextUtils.isEmpty(rec.partGuid)) {
                forgetPartition(rec.partGuid);
            }
            mCallbacks.notifyVolumeForgotten(fsUuid);

            // If this had been primary storage, revert back to internal and
            // reset vold so we bind into new volume into place.
            if (Objects.equals(mPrimaryStorageUuid, fsUuid)) {
                mPrimaryStorageUuid = getDefaultPrimaryStorageUuid();
                resetIfReadyAndConnectedLocked();
            }

            writeSettingsLocked();
        }
    }

    @Override
    public void forgetAllVolumes() {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        synchronized (mLock) {
            for (int i = 0; i < mRecords.size(); i++) {
                final String fsUuid = mRecords.keyAt(i);
                final VolumeRecord rec = mRecords.valueAt(i);
                if (!TextUtils.isEmpty(rec.partGuid)) {
                    forgetPartition(rec.partGuid);
                }
                mCallbacks.notifyVolumeForgotten(fsUuid);
            }
            mRecords.clear();

            if (!Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, mPrimaryStorageUuid)) {
                mPrimaryStorageUuid = getDefaultPrimaryStorageUuid();
            }

            writeSettingsLocked();
            resetIfReadyAndConnectedLocked();
        }
    }

    private void forgetPartition(String partGuid) {
        try {
            mConnector.execute("volume", "forget_partition", partGuid);
        } catch (NativeDaemonConnectorException e) {
            Slog.w(TAG, "Failed to forget key for " + partGuid + ": " + e);
        }
    }

    private void remountUidExternalStorage(int uid, int mode) {
        waitForReady();

        String modeName = "none";
        switch (mode) {
            case Zygote.MOUNT_EXTERNAL_DEFAULT: {
                modeName = "default";
            } break;

            case Zygote.MOUNT_EXTERNAL_READ: {
                modeName = "read";
            } break;

            case Zygote.MOUNT_EXTERNAL_WRITE: {
                modeName = "write";
            } break;
        }

        try {
            mConnector.execute("volume", "remount_uid", uid, modeName);
        } catch (NativeDaemonConnectorException e) {
            Slog.w(TAG, "Failed to remount UID " + uid + " as " + modeName + ": " + e);
        }
    }

    @Override
    public void setDebugFlags(int flags, int mask) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        synchronized (mLock) {
            if ((mask & StorageManager.DEBUG_FORCE_ADOPTABLE) != 0) {
                mForceAdoptable = (flags & StorageManager.DEBUG_FORCE_ADOPTABLE) != 0;
            }

            writeSettingsLocked();
            resetIfReadyAndConnectedLocked();
        }
    }

    @Override
    public String getPrimaryStorageUuid() {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        synchronized (mLock) {
            return mPrimaryStorageUuid;
        }
    }

    @Override
    public void setPrimaryStorageUuid(String volumeUuid, IPackageMoveObserver callback) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        synchronized (mLock) {
            if (Objects.equals(mPrimaryStorageUuid, volumeUuid)) {
                throw new IllegalArgumentException("Primary storage already at " + volumeUuid);
            }

            if (mMoveCallback != null) {
                throw new IllegalStateException("Move already in progress");
            }
            mMoveCallback = callback;
            mMoveTargetUuid = volumeUuid;

            // When moving to/from primary physical volume, we probably just nuked
            // the current storage location, so we have nothing to move.
            if (Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL, mPrimaryStorageUuid)
                    || Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL, volumeUuid)) {
                Slog.d(TAG, "Skipping move to/from primary physical");
                onMoveStatusLocked(MOVE_STATUS_COPY_FINISHED);
                onMoveStatusLocked(PackageManager.MOVE_SUCCEEDED);
                resetIfReadyAndConnectedLocked();

            } else {
                final VolumeInfo from = findStorageForUuid(mPrimaryStorageUuid);
                final VolumeInfo to = findStorageForUuid(volumeUuid);

                if (from == null) {
                    Slog.w(TAG, "Failing move due to missing from volume " + mPrimaryStorageUuid);
                    onMoveStatusLocked(PackageManager.MOVE_FAILED_INTERNAL_ERROR);
                    return;
                } else if (to == null) {
                    Slog.w(TAG, "Failing move due to missing to volume " + volumeUuid);
                    onMoveStatusLocked(PackageManager.MOVE_FAILED_INTERNAL_ERROR);
                    return;
                }

                try {
                    mConnector.execute("volume", "move_storage", from.id, to.id);
                } catch (NativeDaemonConnectorException e) {
                    throw e.rethrowAsParcelableException();
                }
            }
        }
    }

    @Override
    public int[] getStorageUsers(String path) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();
        try {
            final String[] r = NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("storage", "users", path),
                    VoldResponseCode.StorageUsersListResult);

            // FMT: <pid> <process name>
            int[] data = new int[r.length];
            for (int i = 0; i < r.length; i++) {
                String[] tok = r[i].split(" ");
                try {
                    data[i] = Integer.parseInt(tok[0]);
                } catch (NumberFormatException nfe) {
                    Slog.e(TAG, String.format("Error parsing pid %s", tok[0]));
                    return new int[0];
                }
            }
            return data;
        } catch (NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to retrieve storage users list", e);
            return new int[0];
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

    public String[] getSecureContainerList() {
        enforcePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        try {
            return NativeDaemonEvent.filterMessageList(
                    mConnector.executeForList("asec", "list"), VoldResponseCode.AsecListResult);
        } catch (NativeDaemonConnectorException e) {
            return new String[0];
        }
    }

    public int createSecureContainer(String id, int sizeMb, String fstype, String key,
            int ownerUid, boolean external) {
        enforcePermission(android.Manifest.permission.ASEC_CREATE);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "create", id, sizeMb, fstype, new SensitiveArg(key),
                    ownerUid, external ? "1" : "0");
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.add(id);
            }
        }
        return rc;
    }

    @Override
    public int resizeSecureContainer(String id, int sizeMb, String key) {
        enforcePermission(android.Manifest.permission.ASEC_CREATE);
        waitForReady();
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "resize", id, sizeMb, new SensitiveArg(key));
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int finalizeSecureContainer(String id) {
        enforcePermission(android.Manifest.permission.ASEC_CREATE);
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "finalize", id);
            /*
             * Finalization does a remount, so no need
             * to update mAsecMountSet
             */
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int fixPermissionsSecureContainer(String id, int gid, String filename) {
        enforcePermission(android.Manifest.permission.ASEC_CREATE);
        warnOnNotMounted();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "fixperms", id, gid, filename);
            /*
             * Fix permissions does a remount, so no need to update
             * mAsecMountSet
             */
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int destroySecureContainer(String id, boolean force) {
        enforcePermission(android.Manifest.permission.ASEC_DESTROY);
        waitForReady();
        warnOnNotMounted();

        /*
         * Force a GC to make sure AssetManagers in other threads of the
         * system_server are cleaned up. We have to do this since AssetManager
         * instances are kept as a WeakReference and it's possible we have files
         * open on the external storage.
         */
        Runtime.getRuntime().gc();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            final Command cmd = new Command("asec", "destroy", id);
            if (force) {
                cmd.appendArg("force");
            }
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedStorageBusy;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                if (mAsecMountSet.contains(id)) {
                    mAsecMountSet.remove(id);
                }
            }
        }

        return rc;
    }

    public int mountSecureContainer(String id, String key, int ownerUid, boolean readOnly) {
        enforcePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            if (mAsecMountSet.contains(id)) {
                return StorageResultCode.OperationFailedStorageMounted;
            }
        }

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "mount", id, new SensitiveArg(key), ownerUid,
                    readOnly ? "ro" : "rw");
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code != VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.add(id);
            }
        }
        return rc;
    }

    public int unmountSecureContainer(String id, boolean force) {
        enforcePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            if (!mAsecMountSet.contains(id)) {
                return StorageResultCode.OperationFailedStorageNotMounted;
            }
         }

        /*
         * Force a GC to make sure AssetManagers in other threads of the
         * system_server are cleaned up. We have to do this since AssetManager
         * instances are kept as a WeakReference and it's possible we have files
         * open on the external storage.
         */
        Runtime.getRuntime().gc();

        int rc = StorageResultCode.OperationSucceeded;
        try {
            final Command cmd = new Command("asec", "unmount", id);
            if (force) {
                cmd.appendArg("force");
            }
            mConnector.execute(cmd);
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedStorageBusy;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.remove(id);
            }
        }
        return rc;
    }

    public boolean isSecureContainerMounted(String id) {
        enforcePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            return mAsecMountSet.contains(id);
        }
    }

    public int renameSecureContainer(String oldId, String newId) {
        enforcePermission(android.Manifest.permission.ASEC_RENAME);
        waitForReady();
        warnOnNotMounted();

        synchronized (mAsecMountSet) {
            /*
             * Because a mounted container has active internal state which cannot be
             * changed while active, we must ensure both ids are not currently mounted.
             */
            if (mAsecMountSet.contains(oldId) || mAsecMountSet.contains(newId)) {
                return StorageResultCode.OperationFailedStorageMounted;
            }
        }

        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.execute("asec", "rename", oldId, newId);
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }

        return rc;
    }

    public String getSecureContainerPath(String id) {
        enforcePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("asec", "path", id);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                Slog.i(TAG, String.format("Container '%s' not found", id));
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    public String getSecureContainerFilesystemPath(String id) {
        enforcePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("asec", "fspath", id);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                Slog.i(TAG, String.format("Container '%s' not found", id));
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    @Override
    public void finishMediaUpdate() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("no permission to call finishMediaUpdate()");
        }
        if (mUnmountSignal != null) {
            mUnmountSignal.countDown();
        } else {
            Slog.w(TAG, "Odd, nobody asked to unmount?");
        }
    }

    private boolean isUidOwnerOfPackageOrSystem(String packageName, int callerUid) {
        if (callerUid == android.os.Process.SYSTEM_UID) {
            return true;
        }

        if (packageName == null) {
            return false;
        }

        final int packageUid = mPms.getPackageUid(packageName, UserHandle.getUserId(callerUid));

        if (DEBUG_OBB) {
            Slog.d(TAG, "packageName = " + packageName + ", packageUid = " +
                    packageUid + ", callerUid = " + callerUid);
        }

        return callerUid == packageUid;
    }

    public String getMountedObbPath(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        waitForReady();
        warnOnNotMounted();

        final ObbState state;
        synchronized (mObbMounts) {
            state = mObbPathToStateMap.get(rawPath);
        }
        if (state == null) {
            Slog.w(TAG, "Failed to find OBB mounted at " + rawPath);
            return null;
        }

        final NativeDaemonEvent event;
        try {
            event = mConnector.execute("obb", "path", state.voldPath);
            event.checkCode(VoldResponseCode.AsecPathResult);
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                return null;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
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
        final ObbState obbState = new ObbState(rawPath, canonicalPath, callingUid, token, nonce);
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
            final ObbState newState = new ObbState(
                    rawPath, existingState.canonicalPath, callingUid, token, nonce);
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

        waitForReady();

        final NativeDaemonEvent event;
        try {
            event = mCryptConnector.execute("cryptfs", "cryptocomplete");
            return Integer.parseInt(event.getMessage());
        } catch (NumberFormatException e) {
            // Bad result - unexpected.
            Slog.w(TAG, "Unable to parse result from cryptfs cryptocomplete");
            return ENCRYPTION_STATE_ERROR_UNKNOWN;
        } catch (NativeDaemonConnectorException e) {
            // Something bad happened.
            Slog.w(TAG, "Error in communicating with cryptfs in validating");
            return ENCRYPTION_STATE_ERROR_UNKNOWN;
        }
    }

    @Override
    public int decryptStorage(String password) {
        if (TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
                "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "decrypting storage...");
        }

        final NativeDaemonEvent event;
        try {
            event = mCryptConnector.execute("cryptfs", "checkpw", new SensitiveArg(password));

            final int code = Integer.parseInt(event.getMessage());
            if (code == 0) {
                // Decrypt was successful. Post a delayed message before restarting in order
                // to let the UI to clear itself
                mHandler.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            mCryptConnector.execute("cryptfs", "restart");
                        } catch (NativeDaemonConnectorException e) {
                            Slog.e(TAG, "problem executing in background", e);
                        }
                    }
                }, 1000); // 1 second
            }

            return code;
        } catch (NativeDaemonConnectorException e) {
            // Decryption failed
            return e.getCode();
        }
    }

    public int encryptStorage(int type, String password) {
        if (TextUtils.isEmpty(password) && type != StorageManager.CRYPT_TYPE_DEFAULT) {
            throw new IllegalArgumentException("password cannot be empty");
        }

        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "encrypting storage...");
        }

        try {
            if (type == StorageManager.CRYPT_TYPE_DEFAULT) {
                mCryptConnector.execute("cryptfs", "enablecrypto", "inplace",
                                CRYPTO_TYPES[type]);
            } else {
                mCryptConnector.execute("cryptfs", "enablecrypto", "inplace",
                                CRYPTO_TYPES[type], new SensitiveArg(password));
            }
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }

        return 0;
    }

    /** Set the password for encrypting the master key.
     *  @param type One of the CRYPTO_TYPE_XXX consts defined in StorageManager.
     *  @param password The password to set.
     */
    public int changeEncryptionPassword(int type, String password) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CRYPT_KEEPER,
            "no permission to access the crypt keeper");

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "changing encryption password...");
        }

        try {
            NativeDaemonEvent event = mCryptConnector.execute("cryptfs", "changepw", CRYPTO_TYPES[type],
                        new SensitiveArg(password));
            return Integer.parseInt(event.getMessage());
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
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

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "validating encryption password...");
        }

        final NativeDaemonEvent event;
        try {
            event = mCryptConnector.execute("cryptfs", "verifypw", new SensitiveArg(password));
            Slog.i(TAG, "cryptfs verifypw => " + event.getMessage());
            return Integer.parseInt(event.getMessage());
        } catch (NativeDaemonConnectorException e) {
            // Encryption failed
            return e.getCode();
        }
    }

    /**
     * Get the type of encryption used to encrypt the master key.
     * @return The type, one of the CRYPT_TYPE_XXX consts from StorageManager.
     */
    @Override
    public int getPasswordType() {

        waitForReady();

        final NativeDaemonEvent event;
        try {
            event = mCryptConnector.execute("cryptfs", "getpwtype");
            for (int i = 0; i < CRYPTO_TYPES.length; ++i) {
                if (CRYPTO_TYPES[i].equals(event.getMessage()))
                    return i;
            }

            throw new IllegalStateException("unexpected return from cryptfs");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    /**
     * Set a field in the crypto header.
     * @param field field to set
     * @param contents contents to set in field
     */
    @Override
    public void setField(String field, String contents) throws RemoteException {

        waitForReady();

        final NativeDaemonEvent event;
        try {
            event = mCryptConnector.execute("cryptfs", "setfield", field, contents);
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    /**
     * Gets a field from the crypto header.
     * @param field field to get
     * @return contents of field
     */
    @Override
    public String getField(String field) throws RemoteException {

        waitForReady();

        final NativeDaemonEvent event;
        try {
            final String[] contents = NativeDaemonEvent.filterMessageList(
                    mCryptConnector.executeForList("cryptfs", "getfield", field),
                    VoldResponseCode.CryptfsGetfieldResult);
            String result = new String();
            for (String content : contents) {
                result += content;
            }
            return result;
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public String getPassword() throws RemoteException {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE,
                "only keyguard can retrieve password");
        if (!isReady()) {
            return new String();
        }

        final NativeDaemonEvent event;
        try {
            event = mCryptConnector.execute("cryptfs", "getpw");
            if ("-1".equals(event.getMessage())) {
                // -1 equals no password
                return null;
            }
            return event.getMessage();
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Invalid response to getPassword");
            return null;
        }
    }

    @Override
    public void clearPassword() throws RemoteException {
        if (!isReady()) {
            return;
        }

        final NativeDaemonEvent event;
        try {
            event = mCryptConnector.execute("cryptfs", "clearpw");
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public void createNewUserDir(int userHandle, String path) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only SYSTEM_UID can create user directories");
        }

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "Creating new user dir");
        }

        try {
            NativeDaemonEvent event = mCryptConnector.execute(
                "cryptfs", "createnewuserdir", userHandle, path);
            if (!"0".equals(event.getMessage())) {
                String error = "createnewuserdir sent unexpected message: "
                    + event.getMessage();
                Slog.e(TAG,  error);
                // ext4enc:TODO is this the right exception?
                throw new NativeDaemonConnectorException(error);
            }
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    // ext4enc:TODO duplication between this and createNewUserDir is nasty
    @Override
    public void deleteUserKey(int userHandle) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only SYSTEM_UID can delete user keys");
        }

        waitForReady();

        if (DEBUG_EVENTS) {
            Slog.i(TAG, "Deleting user key");
        }

        try {
            NativeDaemonEvent event = mCryptConnector.execute(
                "cryptfs", "deleteuserkey", userHandle);
            if (!"0".equals(event.getMessage())) {
                String error = "deleteuserkey sent unexpected message: "
                    + event.getMessage();
                Slog.e(TAG,  error);
                // ext4enc:TODO is this the right exception?
                throw new NativeDaemonConnectorException(error);
            }
        } catch (NativeDaemonConnectorException e) {
            throw e.rethrowAsParcelableException();
        }
    }

    @Override
    public int mkdirs(String callingPkg, String appPath) {
        final int userId = UserHandle.getUserId(Binder.getCallingUid());
        final UserEnvironment userEnv = new UserEnvironment(userId);

        // Validate that reported package name belongs to caller
        final AppOpsManager appOps = (AppOpsManager) mContext.getSystemService(
                Context.APP_OPS_SERVICE);
        appOps.checkPackage(Binder.getCallingUid(), callingPkg);

        File appFile = null;
        try {
            appFile = new File(appPath).getCanonicalFile();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to resolve " + appPath + ": " + e);
            return -1;
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
                mConnector.execute("volume", "mkdirs", appPath);
                return 0;
            } catch (NativeDaemonConnectorException e) {
                return e.getCode();
            }
        }

        throw new SecurityException("Invalid mkdirs path: " + appFile);
    }

    @Override
    public StorageVolume[] getVolumeList(int uid, String packageName, int flags) {
        final boolean forWrite = (flags & StorageManager.FLAG_FOR_WRITE) != 0;

        final ArrayList<StorageVolume> res = new ArrayList<>();
        boolean foundPrimary = false;

        final int userId = UserHandle.getUserId(uid);
        final boolean reportUnmounted;
        final long identity = Binder.clearCallingIdentity();
        try {
            reportUnmounted = !mMountServiceInternal.hasExternalStorage(
                    uid, packageName);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (forWrite ? vol.isVisibleForWrite(userId) : vol.isVisibleForRead(userId)) {
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
            final long mtpReserveSize = 0L;
            final boolean allowMassStorage = false;
            final long maxFileSize = 0L;
            final UserHandle owner = new UserHandle(userId);
            final String uuid = null;
            final String state = Environment.MEDIA_REMOVED;

            res.add(0, new StorageVolume(id, StorageVolume.STORAGE_ID_INVALID, path,
                    description, primary, removable, emulated, mtpReserveSize,
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
                            + "This indicates an error in the MountService logic.");
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
                            Slog.e(TAG, "Failed to bind to media container service");
                            action.handleError();
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
                        Slog.e(TAG, "Cannot bind to media container service");
                        for (ObbAction action : mActions) {
                            // Indicate service bind error
                            action.handleError();
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
                            Slog.e(TAG, "Failed to bind to media container service");
                            for (ObbAction action : mActions) {
                                // Indicate service bind error
                                action.handleError();
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
                    UserHandle.OWNER)) {
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
                    Slog.w(TAG, "Failed to invoke remote methods on default container service. Giving up");
                    mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
                    handleError();
                    return;
                } else {
                    handleExecute();
                    if (DEBUG_OBB)
                        Slog.i(TAG, "Posting install MCS_UNBIND");
                    mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
                }
            } catch (RemoteException e) {
                if (DEBUG_OBB)
                    Slog.i(TAG, "Posting install MCS_RECONNECT");
                mObbActionHandler.sendEmptyMessage(OBB_MCS_RECONNECT);
            } catch (Exception e) {
                if (DEBUG_OBB)
                    Slog.d(TAG, "Error handling OBB action", e);
                handleError();
                mObbActionHandler.sendEmptyMessage(OBB_MCS_UNBIND);
            }
        }

        abstract void handleExecute() throws RemoteException, IOException;
        abstract void handleError();

        protected ObbInfo getObbInfo() throws IOException {
            ObbInfo obbInfo;
            try {
                obbInfo = mContainerService.getObbInfo(mObbState.ownerPath);
            } catch (RemoteException e) {
                Slog.d(TAG, "Couldn't call DefaultContainerService to fetch OBB info for "
                        + mObbState.ownerPath);
                obbInfo = null;
            }
            if (obbInfo == null) {
                throw new IOException("Couldn't read OBB file: " + mObbState.ownerPath);
            }
            return obbInfo;
        }

        protected void sendNewStatusOrIgnore(int status) {
            if (mObbState == null || mObbState.token == null) {
                return;
            }

            try {
                mObbState.token.onObbResult(mObbState.rawPath, mObbState.nonce, status);
            } catch (RemoteException e) {
                Slog.w(TAG, "MountServiceListener went away while calling onObbStateChanged");
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
        public void handleExecute() throws IOException, RemoteException {
            waitForReady();
            warnOnNotMounted();

            final ObbInfo obbInfo = getObbInfo();

            if (!isUidOwnerOfPackageOrSystem(obbInfo.packageName, mCallingUid)) {
                Slog.w(TAG, "Denied attempt to mount OBB " + obbInfo.filename
                        + " which is owned by " + obbInfo.packageName);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_PERMISSION_DENIED);
                return;
            }

            final boolean isMounted;
            synchronized (mObbMounts) {
                isMounted = mObbPathToStateMap.containsKey(mObbState.rawPath);
            }
            if (isMounted) {
                Slog.w(TAG, "Attempt to mount OBB which is already mounted: " + obbInfo.filename);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_ALREADY_MOUNTED);
                return;
            }

            final String hashedKey;
            if (mKey == null) {
                hashedKey = "none";
            } else {
                try {
                    SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");

                    KeySpec ks = new PBEKeySpec(mKey.toCharArray(), obbInfo.salt,
                            PBKDF2_HASH_ROUNDS, CRYPTO_ALGORITHM_KEY_SIZE);
                    SecretKey key = factory.generateSecret(ks);
                    BigInteger bi = new BigInteger(key.getEncoded());
                    hashedKey = bi.toString(16);
                } catch (NoSuchAlgorithmException e) {
                    Slog.e(TAG, "Could not load PBKDF2 algorithm", e);
                    sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
                    return;
                } catch (InvalidKeySpecException e) {
                    Slog.e(TAG, "Invalid key spec when loading PBKDF2 algorithm", e);
                    sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
                    return;
                }
            }

            int rc = StorageResultCode.OperationSucceeded;
            try {
                mConnector.execute("obb", "mount", mObbState.voldPath, new SensitiveArg(hashedKey),
                        mObbState.ownerGid);
            } catch (NativeDaemonConnectorException e) {
                int code = e.getCode();
                if (code != VoldResponseCode.OpFailedStorageBusy) {
                    rc = StorageResultCode.OperationFailedInternalError;
                }
            }

            if (rc == StorageResultCode.OperationSucceeded) {
                if (DEBUG_OBB)
                    Slog.d(TAG, "Successfully mounted OBB " + mObbState.voldPath);

                synchronized (mObbMounts) {
                    addObbStateLocked(mObbState);
                }

                sendNewStatusOrIgnore(OnObbStateChangeListener.MOUNTED);
            } else {
                Slog.e(TAG, "Couldn't mount OBB file: " + rc);

                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_COULD_NOT_MOUNT);
            }
        }

        @Override
        public void handleError() {
            sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
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
        public void handleExecute() throws IOException {
            waitForReady();
            warnOnNotMounted();

            final ObbInfo obbInfo = getObbInfo();

            final ObbState existingState;
            synchronized (mObbMounts) {
                existingState = mObbPathToStateMap.get(mObbState.rawPath);
            }

            if (existingState == null) {
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_NOT_MOUNTED);
                return;
            }

            if (existingState.ownerGid != mObbState.ownerGid) {
                Slog.w(TAG, "Permission denied attempting to unmount OBB " + existingState.rawPath
                        + " (owned by GID " + existingState.ownerGid + ")");
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_PERMISSION_DENIED);
                return;
            }

            int rc = StorageResultCode.OperationSucceeded;
            try {
                final Command cmd = new Command("obb", "unmount", mObbState.voldPath);
                if (mForceUnmount) {
                    cmd.appendArg("force");
                }
                mConnector.execute(cmd);
            } catch (NativeDaemonConnectorException e) {
                int code = e.getCode();
                if (code == VoldResponseCode.OpFailedStorageBusy) {
                    rc = StorageResultCode.OperationFailedStorageBusy;
                } else if (code == VoldResponseCode.OpFailedStorageNotFound) {
                    // If it's not mounted then we've already won.
                    rc = StorageResultCode.OperationSucceeded;
                } else {
                    rc = StorageResultCode.OperationFailedInternalError;
                }
            }

            if (rc == StorageResultCode.OperationSucceeded) {
                synchronized (mObbMounts) {
                    removeObbStateLocked(existingState);
                }

                sendNewStatusOrIgnore(OnObbStateChangeListener.UNMOUNTED);
            } else {
                Slog.w(TAG, "Could not unmount OBB: " + existingState);
                sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_COULD_NOT_UNMOUNT);
            }
        }

        @Override
        public void handleError() {
            sendNewStatusOrIgnore(OnObbStateChangeListener.ERROR_INTERNAL);
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

    @VisibleForTesting
    public static String buildObbPath(final String canonicalPath, int userId, boolean forVold) {
        // TODO: allow caller to provide Environment for full testing
        // TODO: extend to support OBB mounts on secondary external storage

        // Only adjust paths when storage is emulated
        if (!Environment.isExternalStorageEmulated()) {
            return canonicalPath;
        }

        String path = canonicalPath.toString();

        // First trim off any external storage prefix
        final UserEnvironment userEnv = new UserEnvironment(userId);

        // /storage/emulated/0
        final String externalPath = userEnv.getExternalStorageDirectory().getAbsolutePath();
        // /storage/emulated_legacy
        final String legacyExternalPath = Environment.getLegacyExternalStorageDirectory()
                .getAbsolutePath();

        if (path.startsWith(externalPath)) {
            path = path.substring(externalPath.length() + 1);
        } else if (path.startsWith(legacyExternalPath)) {
            path = path.substring(legacyExternalPath.length() + 1);
        } else {
            return canonicalPath;
        }

        // Handle special OBB paths on emulated storage
        final String obbPath = "Android/obb";
        if (path.startsWith(obbPath)) {
            path = path.substring(obbPath.length() + 1);

            final UserEnvironment ownerEnv = new UserEnvironment(UserHandle.USER_OWNER);
            return new File(ownerEnv.buildExternalStorageAndroidObbDirs()[0], path)
                    .getAbsolutePath();
        }

        // Handle normal external storage paths
        return new File(userEnv.getExternalStorageDirectory(), path).getAbsolutePath();
    }

    private static class Callbacks extends Handler {
        private static final int MSG_STORAGE_STATE_CHANGED = 1;
        private static final int MSG_VOLUME_STATE_CHANGED = 2;
        private static final int MSG_VOLUME_RECORD_CHANGED = 3;
        private static final int MSG_VOLUME_FORGOTTEN = 4;
        private static final int MSG_DISK_SCANNED = 5;
        private static final int MSG_DISK_DESTROYED = 6;

        private final RemoteCallbackList<IMountServiceListener>
                mCallbacks = new RemoteCallbackList<>();

        public Callbacks(Looper looper) {
            super(looper);
        }

        public void register(IMountServiceListener callback) {
            mCallbacks.register(callback);
        }

        public void unregister(IMountServiceListener callback) {
            mCallbacks.unregister(callback);
        }

        @Override
        public void handleMessage(Message msg) {
            final SomeArgs args = (SomeArgs) msg.obj;
            final int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                final IMountServiceListener callback = mCallbacks.getBroadcastItem(i);
                try {
                    invokeCallback(callback, msg.what, args);
                } catch (RemoteException ignored) {
                }
            }
            mCallbacks.finishBroadcast();
            args.recycle();
        }

        private void invokeCallback(IMountServiceListener callback, int what, SomeArgs args)
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
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

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
            pw.println("Force adoptable: " + mForceAdoptable);
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
        pw.println("mConnection:");
        pw.increaseIndent();
        mConnector.dump(fd, pw, args);
        pw.decreaseIndent();

        pw.println();
        pw.print("Last maintenance: ");
        pw.println(TimeUtils.formatForLogging(mLastMaintenance));
    }

    /** {@inheritDoc} */
    @Override
    public void monitor() {
        if (mConnector != null) {
            mConnector.monitor();
        }
        if (mCryptConnector != null) {
            mCryptConnector.monitor();
        }
    }

    private final class MountServiceInternalImpl extends MountServiceInternal {
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
