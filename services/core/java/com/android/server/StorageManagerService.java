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

import static android.Manifest.permission.ACCESS_MTP;
import static android.Manifest.permission.INSTALL_PACKAGES;
import static android.Manifest.permission.MANAGE_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OP_LEGACY_STORAGE;
import static android.app.AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.OP_REQUEST_INSTALL_PACKAGES;
import static android.app.AppOpsManager.OP_WRITE_EXTERNAL_STORAGE;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.storage.OnObbStateChangeListener.ERROR_ALREADY_MOUNTED;
import static android.os.storage.OnObbStateChangeListener.ERROR_COULD_NOT_MOUNT;
import static android.os.storage.OnObbStateChangeListener.ERROR_COULD_NOT_UNMOUNT;
import static android.os.storage.OnObbStateChangeListener.ERROR_NOT_MOUNTED;
import static android.os.storage.OnObbStateChangeListener.ERROR_PERMISSION_DENIED;
import static android.os.storage.OnObbStateChangeListener.MOUNTED;
import static android.os.storage.OnObbStateChangeListener.UNMOUNTED;

import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AnrController;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.admin.SecurityLog;
import android.app.usage.StorageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ProviderInfo;
import android.content.pm.UserInfo;
import android.content.res.ObbInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IStoraged;
import android.os.IVold;
import android.os.IVoldListener;
import android.os.IVoldMountCallback;
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
import android.os.ServiceSpecificException;
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
import android.provider.DeviceConfig;
import android.provider.DocumentsContract;
import android.provider.Downloads;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.storage.ExternalStorageService;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.DataUnit;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsService;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.AppFuseMount;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.FuseUnavailableMountException;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.HexDump;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.pm.Installer;
import com.android.server.pm.UserManagerInternal;
import com.android.server.storage.AppFuseBridge;
import com.android.server.storage.StorageSessionController;
import com.android.server.storage.StorageSessionController.ExternalStorageServiceException;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal.ScreenObserver;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // A system property to control if obb app data isolation is enabled in vold.
    private static final String ANDROID_VOLD_APP_DATA_ISOLATION_ENABLED_PROPERTY =
            "persist.sys.vold_app_data_isolation_enabled";

    // How long we wait to reset storage, if we failed to call onMount on the
    // external storage service.
    public static final int FAILED_MOUNT_RESET_TIMEOUT_SECONDS = 10;

    @GuardedBy("mLock")
    private final Set<Integer> mFuseMountedUser = new ArraySet<>();

    @GuardedBy("mLock")
    private final Set<Integer> mCeStoragePreparedUsers = new ArraySet<>();

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
            if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
                mStorageManagerService.servicesReady();
            } else if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mStorageManagerService.systemReady();
            } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
                mStorageManagerService.bootCompleted();
            }
        }

        @Override
        public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
            int currentUserId = to.getUserIdentifier();
            mStorageManagerService.mCurrentUserId = currentUserId;

            UserManagerInternal umInternal = LocalServices.getService(UserManagerInternal.class);
            if (umInternal.isUserUnlocked(currentUserId)) {
                Slog.d(TAG, "Attempt remount volumes for user: " + currentUserId);
                mStorageManagerService.maybeRemountVolumes(currentUserId);
                mStorageManagerService.mRemountCurrentUserVolumesOnUnlock = false;
            } else {
                Slog.d(TAG, "Attempt remount volumes for user: " + currentUserId + " on unlock");
                mStorageManagerService.mRemountCurrentUserVolumesOnUnlock = true;
            }
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            mStorageManagerService.onUnlockUser(user.getUserIdentifier());
        }

        @Override
        public void onUserStopped(@NonNull TargetUser user) {
            mStorageManagerService.onCleanupUser(user.getUserIdentifier());
        }

        @Override
        public void onUserStopping(@NonNull TargetUser user) {
            mStorageManagerService.onStopUser(user.getUserIdentifier());
        }

        @Override
        public void onUserStarting(TargetUser user) {
            mStorageManagerService.snapshotAndMonitorLegacyStorageAppOp(user.getUserHandle());
        }
    }

    private static final boolean DEBUG_EVENTS = false;
    private static final boolean DEBUG_OBB = false;

    /**
     * We now talk to vold over Binder, and it has its own internal lock to
     * serialize certain calls. All long-running operations have been migrated
     * to be async with callbacks, so we want watchdog to fire if vold wedges.
     */
    private static final boolean WATCHDOG_ENABLE = true;

    /**
     * Our goal is for all Android devices to be usable as development devices,
     * which includes the new Direct Boot mode added in N. For devices that
     * don't have native FBE support, we offer an emulation mode for developer
     * testing purposes, but if it's prohibitively difficult to support this
     * mode, it can be disabled for specific products using this flag.
     */
    private static final boolean EMULATE_FBE_SUPPORTED = true;

    private static final String TAG = "StorageManagerService";
    private static final boolean LOCAL_LOGV = Log.isLoggable(TAG, Log.VERBOSE);

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
    private static final String ATTR_LAST_SEEN_MILLIS = "lastSeenMillis";
    private static final String ATTR_LAST_TRIM_MILLIS = "lastTrimMillis";
    private static final String ATTR_LAST_BENCH_MILLIS = "lastBenchMillis";

    private static final String[] ALL_STORAGE_PERMISSIONS = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    @Nullable public static String sMediaStoreAuthorityProcessName;

    // Run period in hour for smart idle maintenance
    static final int SMART_IDLE_MAINT_PERIOD = 1;

    private final AtomicFile mSettingsFile;
    private final AtomicFile mHourlyWriteFile;

    private static final int MAX_HOURLY_WRITE_RECORDS = 72;

    /**
     * Default config values for smart idle maintenance
     * Actual values will be controlled by DeviceConfig
     */
    // Decide whether smart idle maintenance is enabled or not
    private static final boolean DEFAULT_SMART_IDLE_MAINT_ENABLED = false;
    // Storage lifetime percentage threshold to decide to turn off the feature
    private static final int DEFAULT_LIFETIME_PERCENT_THRESHOLD = 70;
    // Minimum required number of dirty + free segments to trigger GC
    private static final int DEFAULT_MIN_SEGMENTS_THRESHOLD = 512;
    // Determine how much portion of current dirty segments will be GCed
    private static final float DEFAULT_DIRTY_RECLAIM_RATE = 0.5F;
    // Multiplier to amplify the target segment number for GC
    private static final float DEFAULT_SEGMENT_RECLAIM_WEIGHT = 1.0F;
    // Low battery level threshold to decide to turn off the feature
    private static final float DEFAULT_LOW_BATTERY_LEVEL = 20F;
    // Decide whether charging is required to turn on the feature
    private static final boolean DEFAULT_CHARGING_REQUIRED = true;

    private volatile int mLifetimePercentThreshold;
    private volatile int mMinSegmentsThreshold;
    private volatile float mDirtyReclaimRate;
    private volatile float mSegmentReclaimWeight;
    private volatile float mLowBatteryLevel;
    private volatile boolean mChargingRequired;
    private volatile boolean mNeedGC;

    private volatile boolean mPassedLifetimeThresh;
    // Tracking storage hourly write amounts
    private volatile int[] mStorageHourlyWrites;

    /**
     * <em>Never</em> hold the lock while performing downcalls into vold, since
     * unsolicited events can suddenly appear to update data structures.
     */
    private final Object mLock = LockGuard.installNewLock(LockGuard.INDEX_STORAGE);

    /**
     * Similar to {@link #mLock}, never hold this lock while performing downcalls into vold.
     * Also, never hold this while calling into PackageManagerService since it is used in callbacks
     * from PackageManagerService.
     *
     * If both {@link #mLock} and this lock need to be held, {@link #mLock} should be acquired
     * before this.
     *
     * Use -PL suffix for methods that need to called with this lock held.
     */
    private final Object mPackagesLock = new Object();

    /**
     * mLocalUnlockedUsers affects the return value of isUserUnlocked.  If
     * any value in the array changes, then the binder cache for
     * isUserUnlocked must be invalidated.  When adding mutating methods to
     * WatchedLockedUsers, be sure to invalidate the cache in the new
     * methods.
     */
    private class WatchedLockedUsers {
        private int[] users = EmptyArray.INT;
        public WatchedLockedUsers() {
            invalidateIsUserUnlockedCache();
        }
        public void append(int userId) {
            users = ArrayUtils.appendInt(users, userId);
            invalidateIsUserUnlockedCache();
        }
        public void appendAll(int[] userIds) {
            for (int userId : userIds) {
                users = ArrayUtils.appendInt(users, userId);
            }
            invalidateIsUserUnlockedCache();
        }
        public void remove(int userId) {
            users = ArrayUtils.removeInt(users, userId);
            invalidateIsUserUnlockedCache();
        }
        public boolean contains(int userId) {
            return ArrayUtils.contains(users, userId);
        }
        public int[] all() {
            return users;
        }
        @Override
        public String toString() {
            return Arrays.toString(users);
        }
        private void invalidateIsUserUnlockedCache() {
            UserManager.invalidateIsUserUnlockedCache();
        }
    }

    /** Set of users that we know are unlocked. */
    @GuardedBy("mLock")
    private WatchedLockedUsers mLocalUnlockedUsers = new WatchedLockedUsers();
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

    @GuardedBy("mCloudMediaProviders")
    private final SparseArray<String> mCloudMediaProviders = new SparseArray<>();

    private volatile int mMediaStoreAuthorityAppId = -1;

    private volatile int mDownloadsAuthorityAppId = -1;

    private volatile int mExternalStorageAuthorityAppId = -1;

    private volatile int mCurrentUserId = UserHandle.USER_SYSTEM;

    private volatile boolean mRemountCurrentUserVolumesOnUnlock = false;

    private final Installer mInstaller;

    /** Holding lock for AppFuse business */
    private final Object mAppFuseLock = new Object();

    @GuardedBy("mAppFuseLock")
    private int mNextAppFuseName = 0;

    @GuardedBy("mAppFuseLock")
    private AppFuseBridge mAppFuseBridge = null;

    private HashMap<Integer, Integer> mUserSharesMediaWith = new HashMap<>();

    /** Matches known application dir paths. The first group contains the generic part of the path,
     * the second group contains the user id (or null if it's a public volume without users), the
     * third group contains the package name, and the fourth group the remainder of the path.
     */
    public static final Pattern KNOWN_APP_DIR_PATHS = Pattern.compile(
            "(?i)(^/storage/[^/]+/(?:([0-9]+)/)?Android/(?:data|media|obb|sandbox)/)([^/]+)(/.*)?");


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

    private @Nullable VolumeInfo findStorageForUuidAsUser(String volumeUuid,
            @UserIdInt int userId) {
        final StorageManager storage = mContext.getSystemService(StorageManager.class);
        if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, volumeUuid)) {
            return storage.findVolumeById(VolumeInfo.ID_EMULATED_INTERNAL + ";" + userId);
        } else if (Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL, volumeUuid)) {
            return storage.getPrimaryPhysicalVolume();
        } else {
            VolumeInfo info = storage.findVolumeByUuid(volumeUuid);
            if (info == null) {
                Slog.w(TAG, "findStorageForUuidAsUser cannot find volumeUuid:" + volumeUuid);
                return null;
            }
            String emulatedUuid = info.getId().replace("private", "emulated") + ";" + userId;
            return storage.findVolumeById(emulatedUuid);
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

    private final Context mContext;
    private final ContentResolver mResolver;

    private volatile IVold mVold;
    private volatile IStoraged mStoraged;

    private volatile boolean mBootCompleted = false;
    private volatile boolean mDaemonConnected = false;
    private volatile boolean mSecureKeyguardShowing = true;

    private PackageManagerInternal mPmInternal;

    private IPackageManager mIPackageManager;
    private IAppOpsService mIAppOpsService;

    private final Callbacks mCallbacks;
    private final LockPatternUtils mLockPatternUtils;

    private static final String ANR_DELAY_MILLIS_DEVICE_CONFIG_KEY =
            "anr_delay_millis";

    private static final String ANR_DELAY_NOTIFY_EXTERNAL_STORAGE_SERVICE_DEVICE_CONFIG_KEY =
            "anr_delay_notify_external_storage_service";

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

    // Not guarded by a lock.
    private final StorageSessionController mStorageSessionController;

    private final boolean mVoldAppDataIsolationEnabled;

    @GuardedBy("mLock")
    private final Set<Integer> mUidsWithLegacyExternalStorage = new ArraySet<>();
    // Not guarded by lock, always used on the ActivityManager thread
    private final Map<Integer, PackageMonitor> mPackageMonitorsForUser = new ArrayMap<>();


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
    private static final int OBB_FLUSH_MOUNT_STATE = 2;

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
    private static final int H_BOOT_COMPLETED = 13;
    private static final int H_COMPLETE_UNLOCK_USER = 14;
    private static final int H_VOLUME_STATE_CHANGED = 15;
    private static final int H_CLOUD_MEDIA_PROVIDER_CHANGED = 16;

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
                case H_BOOT_COMPLETED: {
                    handleBootCompleted();
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

                    mount(vol);
                    break;
                }
                case H_VOLUME_UNMOUNT: {
                    final VolumeInfo vol = (VolumeInfo) msg.obj;
                    unmount(vol);
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
                    resetIfBootedAndConnected();
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
                case H_COMPLETE_UNLOCK_USER: {
                    completeUnlockUser(msg.arg1);
                    break;
                }
                case H_VOLUME_STATE_CHANGED: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    onVolumeStateChangedAsync((VolumeInfo) args.arg1, args.argi1, args.argi2);
                    args.recycle();
                    break;
                }
                case H_CLOUD_MEDIA_PROVIDER_CHANGED: {
                    final Object listener = msg.obj;
                    if (listener instanceof StorageManagerInternal.CloudProviderChangeListener) {
                        notifyCloudMediaProviderChangedAsync(
                                (StorageManagerInternal.CloudProviderChangeListener) listener);
                    } else {
                        onCloudMediaProviderChangedAsync(msg.arg1);
                    }
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
        if (prepareSmartIdleMaint()) {
            SmartStorageMaintIdler.scheduleSmartIdlePass(mContext, SMART_IDLE_MAINT_PERIOD);
        }

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

        // Schedule zram writeback unless zram is disabled by persist.sys.zram_enabled
        String zramPropValue = SystemProperties.get(ZRAM_ENABLED_PROPERTY);
        if (!zramPropValue.equals("0")
                && mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_zramWriteback)) {
            ZramWriteback.scheduleZramWriteback(mContext);
        }

        configureTranscoding();
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
            // Schedule writeback only if zram is being enabled.
            if (desiredPropertyValue.equals("1")
                    && mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_zramWriteback)) {
                ZramWriteback.scheduleZramWriteback(mContext);
            }
        }
    }

    private void configureTranscoding() {
        // See MediaProvider TranscodeHelper#getBooleanProperty for more information
        boolean transcodeEnabled = false;
        boolean defaultValue = true;

        if (SystemProperties.getBoolean("persist.sys.fuse.transcode_user_control", false)) {
            transcodeEnabled = SystemProperties.getBoolean("persist.sys.fuse.transcode_enabled",
                    defaultValue);
        } else {
            transcodeEnabled = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                    "transcode_enabled", defaultValue);
        }
        SystemProperties.set("sys.fuse.transcode_enabled", String.valueOf(transcodeEnabled));

        if (transcodeEnabled) {
            LocalServices.getService(ActivityManagerInternal.class)
                .registerAnrController(new ExternalStorageServiceAnrController());
        }
    }

    private class ExternalStorageServiceAnrController implements AnrController {
        @Override
        public long getAnrDelayMillis(String packageName, int uid) {
            if (!isAppIoBlocked(uid)) {
                return 0;
            }

            int delay = DeviceConfig.getInt(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                    ANR_DELAY_MILLIS_DEVICE_CONFIG_KEY, 5000);
            Slog.v(TAG, "getAnrDelayMillis for " + packageName + ". " + delay + "ms");
            return delay;
        }

        @Override
        public void onAnrDelayStarted(String packageName, int uid) {
            if (!isAppIoBlocked(uid)) {
                return;
            }

            boolean notifyExternalStorageService = DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                    ANR_DELAY_NOTIFY_EXTERNAL_STORAGE_SERVICE_DEVICE_CONFIG_KEY, true);
            if (notifyExternalStorageService) {
                Slog.d(TAG, "onAnrDelayStarted for " + packageName
                        + ". Notifying external storage service");
                try {
                    mStorageSessionController.notifyAnrDelayStarted(packageName, uid, 0 /* tid */,
                            StorageManager.APP_IO_BLOCKED_REASON_TRANSCODING);
                } catch (ExternalStorageServiceException e) {
                    Slog.e(TAG, "Failed to notify ANR delay started for " + packageName, e);
                }
            } else {
                // TODO(b/170973510): Implement framework spinning dialog for ANR delay
            }
        }

        @Override
        public boolean onAnrDelayCompleted(String packageName, int uid) {
            if (isAppIoBlocked(uid)) {
                Slog.d(TAG, "onAnrDelayCompleted for " + packageName + ". Showing ANR dialog...");
                return true;
            } else {
                Slog.d(TAG, "onAnrDelayCompleted for " + packageName + ". Skipping ANR dialog...");
                return false;
            }
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

                final ProviderInfo provider = mPmInternal.resolveContentProvider(
                        MediaStore.AUTHORITY, PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                        user.id, Process.SYSTEM_UID);
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

    private void initIfBootedAndConnected() {
        Slog.d(TAG, "Thinking about init, mBootCompleted=" + mBootCompleted
                + ", mDaemonConnected=" + mDaemonConnected);
        if (mBootCompleted && mDaemonConnected
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
                        mVold.unlockUserKey(user.id, user.serialNumber, encodeBytes(null));
                    }
                } catch (Exception e) {
                    Slog.wtf(TAG, e);
                }
            }
        }
    }

    private void resetIfBootedAndConnected() {
        Slog.d(TAG, "Thinking about reset, mBootCompleted=" + mBootCompleted
                + ", mDaemonConnected=" + mDaemonConnected);
        if (mBootCompleted && mDaemonConnected) {
            final UserManager userManager = mContext.getSystemService(UserManager.class);
            final List<UserInfo> users = userManager.getUsers();

            mStorageSessionController.onReset(mVold, () -> {
                mHandler.removeCallbacksAndMessages(null);
            });

            final int[] systemUnlockedUsers;
            synchronized (mLock) {
                // make copy as sorting can change order
                systemUnlockedUsers = Arrays.copyOf(mSystemUnlockedUsers,
                        mSystemUnlockedUsers.length);

                mDisks.clear();
                mVolumes.clear();

                addInternalVolumeLocked();
            }

            try {
                // Reset vold to tear down existing disks/volumes and start from
                // a clean state.  Exception: already-unlocked user storage will
                // remain unlocked and is not affected by the reset.
                //
                // TODO(b/135341433): Remove cautious logging when FUSE is stable
                Slog.i(TAG, "Resetting vold...");
                mVold.reset();
                Slog.i(TAG, "Reset vold");

                // Tell vold about all existing and started users
                for (UserInfo user : users) {
                    mVold.onUserAdded(user.id, user.serialNumber);
                }
                for (int userId : systemUnlockedUsers) {
                    mVold.onUserStarted(userId);
                    mStoraged.onUserStarted(userId);
                }
                restoreSystemUnlockedUsers(userManager, users, systemUnlockedUsers);
                mVold.onSecureKeyguardStateChanged(mSecureKeyguardShowing);
                mStorageManagerInternal.onReset(mVold);
            } catch (Exception e) {
                Slog.wtf(TAG, e);
            }
        }
    }

    private void restoreSystemUnlockedUsers(UserManager userManager, List<UserInfo> allUsers,
            int[] systemUnlockedUsers) throws Exception {
        Arrays.sort(systemUnlockedUsers);
        UserManager.invalidateIsUserUnlockedCache();
        for (UserInfo user : allUsers) {
            int userId = user.id;
            if (!userManager.isUserRunning(userId)) {
                continue;
            }
            if (Arrays.binarySearch(systemUnlockedUsers, userId) >= 0) {
                continue;
            }
            boolean unlockingOrUnlocked = userManager.isUserUnlockingOrUnlocked(userId);
            if (!unlockingOrUnlocked) {
                continue;
            }
            Slog.w(TAG, "UNLOCK_USER lost from vold reset, will retry, user:" + userId);
            mVold.onUserStarted(userId);
            mStoraged.onUserStarted(userId);
            mHandler.obtainMessage(H_COMPLETE_UNLOCK_USER, userId, /* arg2 (unusued) */ 0)
                    .sendToTarget();
        }
    }

    // If vold knows that some users have their storage unlocked already (which
    // can happen after a "userspace reboot"), then add those users to
    // mLocalUnlockedUsers.  Do this right away and don't wait until
    // PHASE_BOOT_COMPLETED, since the system may unlock users before then.
    private void restoreLocalUnlockedUsers() {
        final int[] userIds;
        try {
            userIds = mVold.getUnlockedUsers();
        } catch (Exception e) {
            Slog.e(TAG, "Failed to get unlocked users from vold", e);
            return;
        }
        if (!ArrayUtils.isEmpty(userIds)) {
            Slog.d(TAG, "CE storage for users " + Arrays.toString(userIds)
                    + " is already unlocked");
            synchronized (mLock) {
                // Append rather than replace, just in case we're actually
                // reconnecting to vold after it crashed and was restarted, in
                // which case things will be the other way around --- we'll know
                // about the unlocked users but vold won't.
                mLocalUnlockedUsers.appendAll(userIds);
            }
        }
    }

    private void onUnlockUser(int userId) {
        Slog.d(TAG, "onUnlockUser " + userId);

        if (userId != UserHandle.USER_SYSTEM) {
            // Check if this user shares media with another user
            try {
                Context userContext = mContext.createPackageContextAsUser("system", 0,
                        UserHandle.of(userId));
                UserManager um = userContext.getSystemService(UserManager.class);
                if (um != null && um.isMediaSharedWithParent()) {
                    int parentUserId = um.getProfileParent(userId).id;
                    mUserSharesMediaWith.put(userId, parentUserId);
                    mUserSharesMediaWith.put(parentUserId, userId);
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Failed to create user context for user " + userId);
            }
        }
        // We purposefully block here to make sure that user-specific
        // staging area is ready so it's ready for zygote-forked apps to
        // bind mount against.
        try {
            mStorageSessionController.onUnlockUser(userId);
            mVold.onUserStarted(userId);
            mStoraged.onUserStarted(userId);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }

        mHandler.obtainMessage(H_COMPLETE_UNLOCK_USER, userId, /* arg2 (unusued) */ 0)
                .sendToTarget();
        if (mRemountCurrentUserVolumesOnUnlock && userId == mCurrentUserId) {
            maybeRemountVolumes(userId);
            mRemountCurrentUserVolumesOnUnlock = false;
        }
    }

    private void completeUnlockUser(int userId) {
        onKeyguardStateChanged(false);

        // Record user as started so newly mounted volumes kick off events
        // correctly, then synthesize events for any already-mounted volumes.
        synchronized (mLock) {
            for (int unlockedUser : mSystemUnlockedUsers) {
                if (unlockedUser == userId) {
                    // This can happen as restoreAllUnlockedUsers can double post the message.
                    Log.i(TAG, "completeUnlockUser called for already unlocked user:" + userId);
                    return;
                }
            }
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (vol.isVisibleForUser(userId) && vol.isMountedReadable()) {
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

    private void onStopUser(int userId) {
        Slog.i(TAG, "onStopUser " + userId);
        try {
            mStorageSessionController.onUserStopping(userId);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
        PackageMonitor monitor = mPackageMonitorsForUser.remove(userId);
        if (monitor != null) {
            monitor.unregister();
        }
    }

    private void maybeRemountVolumes(int userId) {
        boolean reset = false;
        List<VolumeInfo> volumesToRemount = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final VolumeInfo vol = mVolumes.valueAt(i);
                if (!vol.isPrimary() && vol.isMountedWritable() && vol.isVisible()
                        && vol.getMountUserId() != mCurrentUserId) {
                    // If there's a visible secondary volume mounted,
                    // we need to update the currentUserId and remount
                    vol.mountUserId = mCurrentUserId;
                    volumesToRemount.add(vol);
                }
            }
        }

        for (VolumeInfo vol : volumesToRemount) {
            Slog.i(TAG, "Remounting volume for user: " + userId + ". Volume: " + vol);
            mHandler.obtainMessage(H_VOLUME_UNMOUNT, vol).sendToTarget();
            mHandler.obtainMessage(H_VOLUME_MOUNT, vol).sendToTarget();
        }
    }

    private boolean supportsBlockCheckpoint() throws RemoteException {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        return mVold.supportsBlockCheckpoint();
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
                && mContext.getSystemService(KeyguardManager.class).isDeviceSecure(mCurrentUserId);
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
        initIfBootedAndConnected();
        resetIfBootedAndConnected();
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
        public void onVolumeCreated(String volId, int type, String diskId, String partGuid,
                int userId) {
            synchronized (mLock) {
                final DiskInfo disk = mDisks.get(diskId);
                final VolumeInfo vol = new VolumeInfo(volId, type, disk, partGuid);
                vol.mountUserId = userId;
                mVolumes.put(volId, vol);
                onVolumeCreatedLocked(vol);
            }
        }

        @Override
        public void onVolumeStateChanged(String volId, final int newState) {
            synchronized (mLock) {
                final VolumeInfo vol = mVolumes.get(volId);
                if (vol != null) {
                    final int oldState = vol.state;
                    vol.state = newState;
                    final VolumeInfo vInfo = new VolumeInfo(vol);
                    final SomeArgs args = SomeArgs.obtain();
                    args.arg1 = vInfo;
                    args.argi1 = oldState;
                    args.argi2 = newState;
                    mHandler.obtainMessage(H_VOLUME_STATE_CHANGED, args).sendToTarget();
                    onVolumeStateChangedLocked(vInfo, oldState, newState);
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
            VolumeInfo vol = null;
            synchronized (mLock) {
                vol = mVolumes.remove(volId);
            }

            if (vol != null) {
                mStorageSessionController.onVolumeRemove(vol);
                try {
                    if (vol.type == VolumeInfo.TYPE_PRIVATE) {
                        mInstaller.onPrivateVolumeRemoved(vol.getFsUuid());
                    }
                } catch (Installer.InstallerException e) {
                    Slog.i(TAG, "Failed when private volume unmounted " + vol, e);
                }
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
        if (mPmInternal.isOnlyCoreApps()) {
            Slog.d(TAG, "System booted in core-only mode; ignoring volume " + vol.getId());
            return;
        }
        final ActivityManagerInternal amInternal =
                LocalServices.getService(ActivityManagerInternal.class);

        if (vol.mountUserId >= 0 && !amInternal.isUserRunning(vol.mountUserId, 0)) {
            Slog.d(TAG, "Ignoring volume " + vol.getId() + " because user "
                    + Integer.toString(vol.mountUserId) + " is no longer running.");
            return;
        }

        if (vol.type == VolumeInfo.TYPE_EMULATED) {
            final Context volumeUserContext = mContext.createContextAsUser(
                    UserHandle.of(vol.mountUserId), 0);

            boolean isMediaSharedWithParent =
                    (volumeUserContext != null) ? volumeUserContext.getSystemService(
                            UserManager.class).isMediaSharedWithParent() : false;

            // For all the users where media is shared with parent, creation of emulated volume
            // should not be skipped even if media provider instance is not running in that user
            // space
            if (!isMediaSharedWithParent
                    && !mStorageSessionController.supportsExternalStorage(vol.mountUserId)) {
                Slog.d(TAG, "Ignoring volume " + vol.getId() + " because user "
                        + Integer.toString(vol.mountUserId)
                        + " does not support external storage.");
                return;
            }

            final StorageManager storage = mContext.getSystemService(StorageManager.class);
            final VolumeInfo privateVol = storage.findPrivateForEmulated(vol);

            if ((Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, mPrimaryStorageUuid)
                    && VolumeInfo.ID_PRIVATE_INTERNAL.equals(privateVol.id))
                    || Objects.equals(privateVol.fsUuid, mPrimaryStorageUuid)) {
                Slog.v(TAG, "Found primary storage at " + vol);
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_PRIMARY;
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE_FOR_WRITE;
                mHandler.obtainMessage(H_VOLUME_MOUNT, vol).sendToTarget();
            }

        } else if (vol.type == VolumeInfo.TYPE_PUBLIC) {
            // TODO: only look at first public partition
            if (Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL, mPrimaryStorageUuid)
                    && vol.disk.isDefaultPrimary()) {
                Slog.v(TAG, "Found primary storage at " + vol);
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_PRIMARY;
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE_FOR_WRITE;
            }

            // Adoptable public disks are visible to apps, since they meet
            // public API requirement of being in a stable location.
            if (vol.disk.isAdoptable()) {
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE_FOR_WRITE;
            }

            vol.mountUserId = mCurrentUserId;
            mHandler.obtainMessage(H_VOLUME_MOUNT, vol).sendToTarget();

        } else if (vol.type == VolumeInfo.TYPE_PRIVATE) {
            mHandler.obtainMessage(H_VOLUME_MOUNT, vol).sendToTarget();

        } else if (vol.type == VolumeInfo.TYPE_STUB) {
            if (vol.disk.isStubVisible()) {
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE_FOR_WRITE;
            } else {
                vol.mountFlags |= VolumeInfo.MOUNT_FLAG_VISIBLE_FOR_READ;
            }
            vol.mountUserId = mCurrentUserId;
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
            case VolumeInfo.TYPE_STUB:
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
        if (vol.type == VolumeInfo.TYPE_EMULATED) {
            if (newState != VolumeInfo.STATE_MOUNTED) {
                mFuseMountedUser.remove(vol.getMountUserId());
            } else if (mVoldAppDataIsolationEnabled){
                final int userId = vol.getMountUserId();
                // Async remount app storage so it won't block the main thread.
                new Thread(() -> {

                    // If user 0 has completed unlock, perform a one-time migration of legacy
                    // obb data to its new location. This may take time depending on the size of
                    // the data to be copied so it's done on the StorageManager worker thread.
                    // This needs to be finished before start mounting obb directories.
                    if (userId == 0
                            && Build.VERSION.DEVICE_INITIAL_SDK_INT < Build.VERSION_CODES.Q) {
                        mPmInternal.migrateLegacyObbData();
                    }

                    // Add fuse mounted user after migration to prevent ProcessList tries to
                    // create obb directory before migration is done.
                    mFuseMountedUser.add(userId);

                    Map<Integer, String> pidPkgMap = null;
                    // getProcessesWithPendingBindMounts() could fail when a new app process is
                    // starting and it's not planning to mount storage dirs in zygote, but it's
                    // rare, so we retry 5 times and hope we can get the result successfully.
                    for (int i = 0; i < 5; i++) {
                        try {
                            pidPkgMap = LocalServices.getService(ActivityManagerInternal.class)
                                    .getProcessesWithPendingBindMounts(vol.getMountUserId());
                            break;
                        } catch (IllegalStateException e) {
                            Slog.i(TAG, "Some processes are starting, retry");
                            // Wait 100ms and retry so hope the pending process is started.
                            SystemClock.sleep(100);
                        }
                    }
                    if (pidPkgMap != null) {
                        remountAppStorageDirs(pidPkgMap, userId);
                    } else {
                        Slog.wtf(TAG, "Not able to getStorageNotOptimizedProcesses() after"
                                + " 5 retries");
                    }
                }).start();
            }
        }
    }

    private void onVolumeStateChangedAsync(VolumeInfo vol, int oldState, int newState) {
        synchronized (mLock) {
            // Remember that we saw this volume so we're ready to accept user
            // metadata, or so we can annoy them when a private volume is ejected
            if (!TextUtils.isEmpty(vol.fsUuid)) {
                VolumeRecord rec = mRecords.get(vol.fsUuid);
                if (rec == null) {
                    rec = new VolumeRecord(vol.type, vol.fsUuid);
                    rec.partGuid = vol.partGuid;
                    rec.createdMillis = System.currentTimeMillis();
                    if (vol.type == VolumeInfo.TYPE_PRIVATE) {
                        rec.nickname = vol.disk.getDescription();
                    }
                    mRecords.put(rec.fsUuid, rec);
                } else {
                    // Handle upgrade case where we didn't store partition GUID
                    if (TextUtils.isEmpty(rec.partGuid)) {
                        rec.partGuid = vol.partGuid;
                    }
                }

                rec.lastSeenMillis = System.currentTimeMillis();
                writeSettingsLocked();
            }
        }

        if (newState == VolumeInfo.STATE_MOUNTED) {
            // Private volumes can be unmounted and re-mounted even after a user has
            // been unlocked; on devices that support encryption keys tied to the filesystem,
            // this requires setting up the keys again.
            prepareUserStorageIfNeeded(vol);
        }

        // This is a blocking call to Storage Service which needs to process volume state changed
        // before notifying other listeners.
        // Intentionally called without the mLock to avoid deadlocking from the Storage Service.
        try {
            mStorageSessionController.notifyVolumeStateChanged(vol);
        } catch (ExternalStorageServiceException e) {
            Log.e(TAG, "Failed to notify volume state changed to the Storage Service", e);
        }
        synchronized (mLock) {
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
                    if (vol.isVisibleForUser(userId)) {
                        final StorageVolume userVol = vol.buildStorageVolume(mContext, userId,
                                false);
                        mHandler.obtainMessage(H_VOLUME_BROADCAST, userVol).sendToTarget();

                        mCallbacks.notifyStorageStateChanged(userVol.getPath(), oldStateEnv,
                                newStateEnv);
                    }
                }
            }

            if ((vol.type == VolumeInfo.TYPE_PUBLIC || vol.type == VolumeInfo.TYPE_STUB)
                    && vol.state == VolumeInfo.STATE_EJECTING) {
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
    }

    private void notifyCloudMediaProviderChangedAsync(
            @NonNull StorageManagerInternal.CloudProviderChangeListener listener) {
        synchronized (mCloudMediaProviders) {
            for (int i = mCloudMediaProviders.size() - 1; i >= 0; --i) {
                listener.onCloudProviderChanged(
                        mCloudMediaProviders.keyAt(i), mCloudMediaProviders.valueAt(i));
            }
        }
    }

    private void onCloudMediaProviderChangedAsync(int userId) {
        final String authority;
        synchronized (mCloudMediaProviders) {
            authority = mCloudMediaProviders.get(userId);
        }
        for (StorageManagerInternal.CloudProviderChangeListener listener :
                mStorageManagerInternal.mCloudProviderChangeListeners) {
            listener.onCloudProviderChanged(userId, authority);
        }
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
        if (vol.type == VolumeInfo.TYPE_PUBLIC || vol.type == VolumeInfo.TYPE_PRIVATE
                || vol.type == VolumeInfo.TYPE_STUB) {
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
        final long token = Binder.clearCallingIdentity();
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
        mVoldAppDataIsolationEnabled = SystemProperties.getBoolean(
                ANDROID_VOLD_APP_DATA_ISOLATION_ENABLED_PROPERTY, false);
        mContext = context;
        mResolver = mContext.getContentResolver();
        mCallbacks = new Callbacks(FgThread.get().getLooper());
        mLockPatternUtils = new LockPatternUtils(mContext);

        HandlerThread hthread = new HandlerThread(TAG);
        hthread.start();
        mHandler = new StorageManagerServiceHandler(hthread.getLooper());

        // Add OBB Action Handler to StorageManagerService thread.
        mObbActionHandler = new ObbActionHandler(IoThread.get().getLooper());

        mStorageSessionController = new StorageSessionController(mContext);

        mInstaller = new Installer(mContext);
        mInstaller.onStart();

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
        mHourlyWriteFile = new AtomicFile(
                new File(Environment.getDataSystemDirectory(), "storage-hourly-writes"));

        mStorageHourlyWrites = new int[MAX_HOURLY_WRITE_RECORDS];

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
        connectStoraged();
        connectVold();
    }

    private void connectStoraged() {
        IBinder binder = ServiceManager.getService("storaged");
        if (binder != null) {
            try {
                binder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        Slog.w(TAG, "storaged died; reconnecting");
                        mStoraged = null;
                        connectStoraged();
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

        if (mStoraged == null) {
            BackgroundThread.getHandler().postDelayed(() -> {
                connectStoraged();
            }, DateUtils.SECOND_IN_MILLIS);
        } else {
            onDaemonConnected();
        }
    }

    private void connectVold() {
        IBinder binder = ServiceManager.getService("vold");
        if (binder != null) {
            try {
                binder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        Slog.w(TAG, "vold died; reconnecting");
                        mVold = null;
                        connectVold();
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

        if (mVold == null) {
            BackgroundThread.getHandler().postDelayed(() -> {
                connectVold();
            }, DateUtils.SECOND_IN_MILLIS);
        } else {
            restoreLocalUnlockedUsers();
            onDaemonConnected();
        }
    }

    private void servicesReady() {
        mPmInternal = LocalServices.getService(PackageManagerInternal.class);

        mIPackageManager = IPackageManager.Stub.asInterface(
                ServiceManager.getService("package"));
        mIAppOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));

        ProviderInfo provider = getProviderInfo(MediaStore.AUTHORITY);
        if (provider != null) {
            mMediaStoreAuthorityAppId = UserHandle.getAppId(provider.applicationInfo.uid);
            sMediaStoreAuthorityProcessName = provider.applicationInfo.processName;
        }

        provider = getProviderInfo(Downloads.Impl.AUTHORITY);
        if (provider != null) {
            mDownloadsAuthorityAppId = UserHandle.getAppId(provider.applicationInfo.uid);
        }

        provider = getProviderInfo(DocumentsContract.EXTERNAL_STORAGE_PROVIDER_AUTHORITY);
        if (provider != null) {
            mExternalStorageAuthorityAppId = UserHandle.getAppId(provider.applicationInfo.uid);
        }
    }

    private ProviderInfo getProviderInfo(String authority) {
        return mPmInternal.resolveContentProvider(
                authority, PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.getUserId(UserHandle.USER_SYSTEM), Process.SYSTEM_UID);
    }

    private void updateLegacyStorageApps(String packageName, int uid, boolean hasLegacy) {
        synchronized (mLock) {
            if (hasLegacy) {
                Slog.v(TAG, "Package " + packageName + " has legacy storage");
                mUidsWithLegacyExternalStorage.add(uid);
            } else {
                // TODO(b/149391976): Handle shared user id. Check if there's any other
                // installed app with legacy external storage before removing
                Slog.v(TAG, "Package " + packageName + " does not have legacy storage");
                mUidsWithLegacyExternalStorage.remove(uid);
            }
        }
    }

    private void snapshotAndMonitorLegacyStorageAppOp(UserHandle user) {
        int userId = user.getIdentifier();

        // TODO(b/149391976): Use mIAppOpsService.getPackagesForOps instead of iterating below
        // It should improve performance but the AppOps method doesn't return any app here :(
        // This operation currently takes about ~20ms on a freshly flashed device
        for (ApplicationInfo ai : mPmInternal.getInstalledApplications(MATCH_DIRECT_BOOT_AWARE
                        | MATCH_DIRECT_BOOT_UNAWARE | MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER,
                        userId, Process.myUid())) {
            try {
                boolean hasLegacy = mIAppOpsService.checkOperation(OP_LEGACY_STORAGE, ai.uid,
                        ai.packageName) == MODE_ALLOWED;
                updateLegacyStorageApps(ai.packageName, ai.uid, hasLegacy);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to check legacy op for package " + ai.packageName, e);
            }
        }

        PackageMonitor monitor = new PackageMonitor() {
                @Override
                public void onPackageRemoved(String packageName, int uid) {
                    updateLegacyStorageApps(packageName, uid, false);
                }
            };
        // TODO(b/149391976): Use different handler?
        monitor.register(mContext, user, true, mHandler);
        mPackageMonitorsForUser.put(userId, monitor);
    }

    private static long getLastAccessTime(AppOpsManager manager,
            int uid, String packageName, int[] ops) {
        long maxTime = 0;
        final List<AppOpsManager.PackageOps> pkgs = manager.getOpsForPackage(uid, packageName, ops);
        for (AppOpsManager.PackageOps pkg : CollectionUtils.emptyIfNull(pkgs)) {
            for (AppOpsManager.OpEntry op : CollectionUtils.emptyIfNull(pkg.getOps())) {
                maxTime = Math.max(maxTime, op.getLastAccessTime(
                    AppOpsManager.OP_FLAGS_ALL_TRUSTED));
            }
        }
        return maxTime;
    }

    private void systemReady() {
        LocalServices.getService(ActivityTaskManagerInternal.class)
                .registerScreenObserver(this);

        mHandler.obtainMessage(H_SYSTEM_READY).sendToTarget();
    }

    private void bootCompleted() {
        mBootCompleted = true;
        mHandler.obtainMessage(H_BOOT_COMPLETED).sendToTarget();
    }

    private void handleBootCompleted() {
        initIfBootedAndConnected();
        resetIfBootedAndConnected();
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
            final TypedXmlPullParser in = Xml.resolvePullParser(fis);

            int type;
            while ((type = in.next()) != END_DOCUMENT) {
                if (type == START_TAG) {
                    final String tag = in.getName();
                    if (TAG_VOLUMES.equals(tag)) {
                        final int version = in.getAttributeInt(null, ATTR_VERSION, VERSION_INIT);
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

            TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.startTag(null, TAG_VOLUMES);
            out.attributeInt(null, ATTR_VERSION, VERSION_FIX_PRIMARY);
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

    public static VolumeRecord readVolumeRecord(TypedXmlPullParser in)
            throws IOException, XmlPullParserException {
        final int type = in.getAttributeInt(null, ATTR_TYPE);
        final String fsUuid = readStringAttribute(in, ATTR_FS_UUID);
        final VolumeRecord meta = new VolumeRecord(type, fsUuid);
        meta.partGuid = readStringAttribute(in, ATTR_PART_GUID);
        meta.nickname = readStringAttribute(in, ATTR_NICKNAME);
        meta.userFlags = in.getAttributeInt(null, ATTR_USER_FLAGS);
        meta.createdMillis = in.getAttributeLong(null, ATTR_CREATED_MILLIS, 0);
        meta.lastSeenMillis = in.getAttributeLong(null, ATTR_LAST_SEEN_MILLIS, 0);
        meta.lastTrimMillis = in.getAttributeLong(null, ATTR_LAST_TRIM_MILLIS, 0);
        meta.lastBenchMillis = in.getAttributeLong(null, ATTR_LAST_BENCH_MILLIS, 0);
        return meta;
    }

    public static void writeVolumeRecord(TypedXmlSerializer out, VolumeRecord rec)
            throws IOException {
        out.startTag(null, TAG_VOLUME);
        out.attributeInt(null, ATTR_TYPE, rec.type);
        writeStringAttribute(out, ATTR_FS_UUID, rec.fsUuid);
        writeStringAttribute(out, ATTR_PART_GUID, rec.partGuid);
        writeStringAttribute(out, ATTR_NICKNAME, rec.nickname);
        out.attributeInt(null, ATTR_USER_FLAGS, rec.userFlags);
        out.attributeLong(null, ATTR_CREATED_MILLIS, rec.createdMillis);
        out.attributeLong(null, ATTR_LAST_SEEN_MILLIS, rec.lastSeenMillis);
        out.attributeLong(null, ATTR_LAST_TRIM_MILLIS, rec.lastTrimMillis);
        out.attributeLong(null, ATTR_LAST_BENCH_MILLIS, rec.lastBenchMillis);
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

        mount(vol);
    }

    private void remountAppStorageDirs(Map<Integer, String> pidPkgMap, int userId) {
        for (Entry<Integer, String> entry : pidPkgMap.entrySet()) {
            final int pid = entry.getKey();
            final String packageName = entry.getValue();
            Slog.i(TAG, "Remounting storage for pid: " + pid);
            final String[] sharedPackages =
                    mPmInternal.getSharedUserPackagesForPackage(packageName, userId);
            final int uid = mPmInternal.getPackageUid(packageName, 0 /* flags */, userId);
            final String[] packages =
                    sharedPackages.length != 0 ? sharedPackages : new String[]{packageName};
            try {
                mVold.remountAppStorageDirs(uid, pid, packages);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }
    }

    private void mount(VolumeInfo vol) {
        try {
            // TODO(b/135341433): Remove cautious logging when FUSE is stable
            Slog.i(TAG, "Mounting volume " + vol);
            mVold.mount(vol.id, vol.mountFlags, vol.mountUserId, new IVoldMountCallback.Stub() {
                @Override
                public boolean onVolumeChecking(FileDescriptor fd, String path,
                        String internalPath) {
                    vol.path = path;
                    vol.internalPath = internalPath;
                    ParcelFileDescriptor pfd = new ParcelFileDescriptor(fd);
                    try {
                        mStorageSessionController.onVolumeMount(pfd, vol);
                        return true;
                    } catch (ExternalStorageServiceException e) {
                        Slog.e(TAG, "Failed to mount volume " + vol, e);

                        int nextResetSeconds = FAILED_MOUNT_RESET_TIMEOUT_SECONDS;
                        Slog.i(TAG, "Scheduling reset in " + nextResetSeconds + "s");
                        mHandler.removeMessages(H_RESET);
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(H_RESET),
                                TimeUnit.SECONDS.toMillis(nextResetSeconds));
                        return false;
                    } finally {
                        try {
                            pfd.close();
                        } catch (Exception e) {
                            Slog.e(TAG, "Failed to close FUSE device fd", e);
                        }
                    }
                }
            });
            Slog.i(TAG, "Mounted volume " + vol);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void unmount(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        final VolumeInfo vol = findVolumeByIdOrThrow(volId);
        unmount(vol);
    }

    private void unmount(VolumeInfo vol) {
        try {
            try {
                if (vol.type == VolumeInfo.TYPE_PRIVATE) {
                    mInstaller.onPrivateVolumeRemoved(vol.getFsUuid());
                }
            } catch (Installer.InstallerException e) {
                Slog.e(TAG, "Failed unmount mirror data", e);
            }
            mVold.unmount(vol.id);
            mStorageSessionController.onVolumeUnmount(vol);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    @Override
    public void format(String volId) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);

        final VolumeInfo vol = findVolumeByIdOrThrow(volId);
        final String fsUuid = vol.fsUuid;
        try {
            mVold.format(vol.id, "auto");

            // After a successful format above, we should forget about any
            // records for the old partition, since it'll never appear again
            if (!TextUtils.isEmpty(fsUuid)) {
                forgetVolume(fsUuid);
            }
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

        Objects.requireNonNull(fsUuid);
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

        Objects.requireNonNull(fsUuid);
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

        Objects.requireNonNull(fsUuid);

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
            // Block based checkpoint process runs fstrim. So, if checkpoint is in progress
            // (first boot after OTA), We skip idle maintenance and make sure the last
            // fstrim time is still updated. If file based checkpoints are used, we run
            // idle maintenance (GC + fstrim) regardless of checkpoint status.
            if (!needsCheckpoint() || !supportsBlockCheckpoint()) {
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
            } else {
                Slog.i(TAG, "Skipping fstrim - block based checkpoint in progress");
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    void runIdleMaint(Runnable callback) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);

        try {
            // Block based checkpoint process runs fstrim. So, if checkpoint is in progress
            // (first boot after OTA), We skip idle maintenance and make sure the last
            // fstrim time is still updated. If file based checkpoints are used, we run
            // idle maintenance (GC + fstrim) regardless of checkpoint status.
            if (!needsCheckpoint() || !supportsBlockCheckpoint()) {
                mVold.runIdleMaint(mNeedGC, new IVoldTaskListener.Stub() {
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
            } else {
                Slog.i(TAG, "Skipping idle maintenance - block based checkpoint in progress");
            }
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

    private boolean prepareSmartIdleMaint() {
        /**
         * We can choose whether going with a new storage smart idle maintenance job
         * or falling back to the traditional way using DeviceConfig
         */
        boolean smartIdleMaintEnabled = DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
            "smart_idle_maint_enabled",
            DEFAULT_SMART_IDLE_MAINT_ENABLED);
        if (smartIdleMaintEnabled) {
            mLifetimePercentThreshold = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                "lifetime_threshold", DEFAULT_LIFETIME_PERCENT_THRESHOLD);
            mMinSegmentsThreshold = DeviceConfig.getInt(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                "min_segments_threshold", DEFAULT_MIN_SEGMENTS_THRESHOLD);
            mDirtyReclaimRate = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                "dirty_reclaim_rate", DEFAULT_DIRTY_RECLAIM_RATE);
            mSegmentReclaimWeight = DeviceConfig.getFloat(
                DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                "segment_reclaim_weight", DEFAULT_SEGMENT_RECLAIM_WEIGHT);
            mLowBatteryLevel = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                "low_battery_level", DEFAULT_LOW_BATTERY_LEVEL);
            mChargingRequired = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                "charging_required", DEFAULT_CHARGING_REQUIRED);

            // If we use the smart idle maintenance, we need to turn off GC in the traditional idle
            // maintenance to avoid the conflict
            mNeedGC = false;

            loadStorageHourlyWrites();
            try {
                mVold.refreshLatestWrite();
            } catch (Exception e) {
                Slog.wtf(TAG, e);
            }
            refreshLifetimeConstraint();
        }
        return smartIdleMaintEnabled;
    }

    // Return whether storage lifetime exceeds the threshold
    public boolean isPassedLifetimeThresh() {
        return mPassedLifetimeThresh;
    }

    private void loadStorageHourlyWrites() {
        FileInputStream fis = null;

        try {
            fis = mHourlyWriteFile.openRead();
            ObjectInputStream ois = new ObjectInputStream(fis);
            mStorageHourlyWrites = (int[])ois.readObject();
        } catch (FileNotFoundException e) {
            // Missing data is okay, probably first boot
        } catch (Exception e) {
            Slog.wtf(TAG, "Failed reading write records", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    private int getAverageHourlyWrite() {
        return Arrays.stream(mStorageHourlyWrites).sum() / MAX_HOURLY_WRITE_RECORDS;
    }

    private void updateStorageHourlyWrites(int latestWrite) {
        FileOutputStream fos = null;

        System.arraycopy(mStorageHourlyWrites,0, mStorageHourlyWrites, 1,
                     MAX_HOURLY_WRITE_RECORDS - 1);
        mStorageHourlyWrites[0] = latestWrite;
        try {
            fos = mHourlyWriteFile.startWrite();
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(mStorageHourlyWrites);
            mHourlyWriteFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mHourlyWriteFile.failWrite(fos);
            }
        }
    }

    private boolean checkChargeStatus() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter);

        if (mChargingRequired) {
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (status != BatteryManager.BATTERY_STATUS_CHARGING &&
                status != BatteryManager.BATTERY_STATUS_FULL) {
                Slog.w(TAG, "Battery is not being charged");
                return false;
            }
        }

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float chargePercent = level * 100f / (float)scale;

        if (chargePercent < mLowBatteryLevel) {
            Slog.w(TAG, "Battery level is " + chargePercent + ", which is lower than threshold: " +
                        mLowBatteryLevel);
            return false;
        }
        return true;
    }

    private boolean refreshLifetimeConstraint() {
        int storageLifeTime = 0;

        try {
            storageLifeTime = mVold.getStorageLifeTime();
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return false;
        }

        if (storageLifeTime == -1) {
            Slog.w(TAG, "Failed to get storage lifetime");
            return false;
        } else if (storageLifeTime > mLifetimePercentThreshold) {
            Slog.w(TAG, "Ended smart idle maintenance, because of lifetime(" + storageLifeTime +
                        ")" + ", lifetime threshold(" + mLifetimePercentThreshold + ")");
            mPassedLifetimeThresh = true;
            return false;
        }
        Slog.i(TAG, "Storage lifetime: " + storageLifeTime);
        return true;
    }

    void runSmartIdleMaint(Runnable callback) {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);

        try {
            // Block based checkpoint process runs fstrim. So, if checkpoint is in progress
            // (first boot after OTA), We skip the smart idle maintenance
            if (!needsCheckpoint() || !supportsBlockCheckpoint()) {
                if (!refreshLifetimeConstraint() || !checkChargeStatus()) {
                    return;
                }

                int latestHourlyWrite = mVold.getWriteAmount();
                if (latestHourlyWrite == -1) {
                    Slog.w(TAG, "Failed to get storage hourly write");
                    return;
                }

                updateStorageHourlyWrites(latestHourlyWrite);
                int avgHourlyWrite = getAverageHourlyWrite();

                Slog.i(TAG, "Set smart idle maintenance: " + "latest hourly write: " +
                            latestHourlyWrite + ", average hourly write: " + avgHourlyWrite +
                            ", min segment threshold: " + mMinSegmentsThreshold +
                            ", dirty reclaim rate: " + mDirtyReclaimRate +
                            ", segment reclaim weight:" + mSegmentReclaimWeight);
                mVold.setGCUrgentPace(avgHourlyWrite, mMinSegmentsThreshold, mDirtyReclaimRate,
                                      mSegmentReclaimWeight);
            } else {
                Slog.i(TAG, "Skipping smart idle maintenance - block based checkpoint in progress");
            }
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        } finally {
            if (callback != null) {
                callback.run();
            }
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
                int currentUserId = mCurrentUserId;
                from = findStorageForUuidAsUser(mPrimaryStorageUuid, currentUserId);
                to = findStorageForUuidAsUser(volumeUuid, currentUserId);

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

        final int packageUid = mPmInternal.getPackageUid(packageName,
                PackageManager.MATCH_DEBUG_TRIAGED_MISSING, UserHandle.getUserId(callerUid));

        if (DEBUG_OBB) {
            Slog.d(TAG, "packageName = " + packageName + ", packageUid = " +
                    packageUid + ", callerUid = " + callerUid);
        }

        return callerUid == packageUid;
    }

    @Override
    public String getMountedObbPath(String rawPath) {
        Objects.requireNonNull(rawPath, "rawPath cannot be null");

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
        Objects.requireNonNull(rawPath, "rawPath cannot be null");
        synchronized (mObbMounts) {
            return mObbPathToStateMap.containsKey(rawPath);
        }
    }

    @Override
    public void mountObb(String rawPath, String canonicalPath, IObbActionListener token,
            int nonce, ObbInfo obbInfo) {
        Objects.requireNonNull(rawPath, "rawPath cannot be null");
        Objects.requireNonNull(canonicalPath, "canonicalPath cannot be null");
        Objects.requireNonNull(token, "token cannot be null");
        Objects.requireNonNull(obbInfo, "obbIfno cannot be null");

        final int callingUid = Binder.getCallingUid();
        final ObbState obbState = new ObbState(rawPath, canonicalPath,
                callingUid, token, nonce, null);
        final ObbAction action = new MountObbAction(obbState, callingUid, obbInfo);
        mObbActionHandler.sendMessage(mObbActionHandler.obtainMessage(OBB_RUN_ACTION, action));

        if (DEBUG_OBB)
            Slog.i(TAG, "Send to OBB handler: " + action.toString());
    }

    @Override
    public void unmountObb(String rawPath, boolean force, IObbActionListener token, int nonce) {
        Objects.requireNonNull(rawPath, "rawPath cannot be null");

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

    /**
     * Check whether the device supports filesystem checkpointing.
     *
     * @return true if the device supports filesystem checkpointing, false otherwise.
     */
    @Override
    public boolean supportsCheckpoint() throws RemoteException {
        return mVold.supportsCheckpoint();
    }

    /**
     * Signal that checkpointing partitions should start a checkpoint on the next boot.
     *
     * @param numTries Number of times to try booting in checkpoint mode, before we will boot
     *                 non-checkpoint mode and commit all changes immediately. Callers are
     *                 responsible for ensuring that boot is safe (eg, by rolling back updates).
     */
    @Override
    public void startCheckpoint(int numTries) throws RemoteException {
        // Only the root, system_server and shell processes are permitted to start checkpoints
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID && callingUid != Process.ROOT_UID
                && callingUid != Process.SHELL_UID) {
            throw new SecurityException("no permission to start filesystem checkpoint");
        }

        mVold.startCheckpoint(numTries);
    }

    /**
     * Signal that checkpointing partitions should commit changes
     */
    @Override
    public void commitChanges() throws RemoteException {
        // Only the system process is permitted to commit checkpoints
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new SecurityException("no permission to commit checkpoint changes");
        }

        mVold.commitChanges();
    }

    /**
     * Check if we should be mounting with checkpointing or are checkpointing now
     */
    @Override
    public boolean needsCheckpoint() throws RemoteException {
        enforcePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        return mVold.needsCheckpoint();
    }

    /**
     * Abort the current set of changes and either try again, or abort entirely
     */
    @Override
    public void abortChanges(String message, boolean retry) throws RemoteException {
        // Only the system process is permitted to abort checkpoints
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new SecurityException("no permission to commit checkpoint changes");
        }

        mVold.abortChanges(message, retry);
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
     * Add this secret to the set of ways we can recover a user's disk
     * encryption key.  Changing the secret for a disk encryption key is done in
     * two phases.  First, this method is called to add the new secret binding.
     * Second, fixateNewestUserKeyAuth is called to delete all other bindings.
     * This allows other places where a credential is used, such as Gatekeeper,
     * to be updated between the two calls.
     */
    @Override
    public void addUserKeyAuth(int userId, int serialNumber, byte[] secret) {
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        try {
            mVold.addUserKeyAuth(userId, serialNumber, encodeBytes(secret));
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    /*
     * Store a user's disk encryption key without secret binding.  Removing the
     * secret for a disk encryption key is done in two phases.  First, this
     * method is called to retrieve the key using the provided secret and store
     * it encrypted with a keystore key not bound to the user.  Second,
     * fixateNewestUserKeyAuth is called to delete the key's other bindings.
     */
    @Override
    public void clearUserKeyAuth(int userId, int serialNumber, byte[] secret) {
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        try {
            mVold.clearUserKeyAuth(userId, serialNumber, encodeBytes(secret));
        } catch (Exception e) {
            Slog.wtf(TAG, e);
        }
    }

    /*
     * Delete all bindings of a user's disk encryption key except the most
     * recently added one.
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
    public void unlockUserKey(int userId, int serialNumber, byte[] secret) {
        boolean isFsEncrypted = StorageManager.isFileEncryptedNativeOrEmulated();
        Slog.d(TAG, "unlockUserKey: " + userId
                + " isFileEncryptedNativeOrEmulated: " + isFsEncrypted
                + " hasSecret: " + (secret != null));
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        if (isUserKeyUnlocked(userId)) {
            Slog.d(TAG, "User " + userId + "'s CE storage is already unlocked");
            return;
        }

        if (isFsEncrypted) {
            // When a user has a secure lock screen, a secret is required to
            // unlock the key, so don't bother trying to unlock it without one.
            // This prevents misleading error messages from being logged.  This
            // is also needed for emulated FBE to behave like native FBE.
            if (mLockPatternUtils.isSecure(userId) && ArrayUtils.isEmpty(secret)) {
                Slog.d(TAG, "Not unlocking user " + userId
                        + "'s CE storage yet because a secret is needed");
                return;
            }
            try {
                mVold.unlockUserKey(userId, serialNumber, encodeBytes(secret));
            } catch (Exception e) {
                Slog.wtf(TAG, e);
                return;
            }
        }

        synchronized (mLock) {
            mLocalUnlockedUsers.append(userId);
        }
    }

    @Override
    public void lockUserKey(int userId) {
        //  Do not lock user 0 data for headless system user
        if (userId == UserHandle.USER_SYSTEM
                && UserManager.isHeadlessSystemUserMode()) {
            throw new IllegalArgumentException("Headless system user data cannot be locked..");
        }

        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        if (!isUserKeyUnlocked(userId)) {
            Slog.d(TAG, "User " + userId + "'s CE storage is already locked");
            return;
        }

        try {
            mVold.lockUserKey(userId);
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            return;
        }

        synchronized (mLock) {
            mLocalUnlockedUsers.remove(userId);
        }
    }

    @Override
    public boolean isUserKeyUnlocked(int userId) {
        synchronized (mLock) {
            return mLocalUnlockedUsers.contains(userId);
        }
    }

    private boolean isSystemUnlocked(int userId) {
        synchronized (mLock) {
            return ArrayUtils.contains(mSystemUnlockedUsers, userId);
        }
    }

    private void prepareUserStorageIfNeeded(VolumeInfo vol) {
        if (vol.type != VolumeInfo.TYPE_PRIVATE) {
            return;
        }

        final UserManager um = mContext.getSystemService(UserManager.class);
        final UserManagerInternal umInternal =
                LocalServices.getService(UserManagerInternal.class);

        for (UserInfo user : um.getUsers()) {
            final int flags;
            if (umInternal.isUserUnlockingOrUnlocked(user.id)) {
                flags = StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE;
            } else if (umInternal.isUserRunning(user.id)) {
                flags = StorageManager.FLAG_STORAGE_DE;
            } else {
                continue;
            }

            prepareUserStorageInternal(vol.fsUuid, user.id, user.serialNumber, flags);
        }
    }

    @Override
    public void prepareUserStorage(String volumeUuid, int userId, int serialNumber, int flags) {
        enforcePermission(android.Manifest.permission.STORAGE_INTERNAL);

        prepareUserStorageInternal(volumeUuid, userId, serialNumber, flags);
    }

    private void prepareUserStorageInternal(String volumeUuid, int userId, int serialNumber,
            int flags) {
        try {
            mVold.prepareUserStorage(volumeUuid, userId, serialNumber, flags);
            // After preparing user storage, we should check if we should mount data mirror again,
            // and we do it for user 0 only as we only need to do once for all users.
            if (volumeUuid != null) {
                final StorageManager storage = mContext.getSystemService(StorageManager.class);
                VolumeInfo info = storage.findVolumeByUuid(volumeUuid);
                if (info != null && userId == 0 && info.type == VolumeInfo.TYPE_PRIVATE) {
                    mInstaller.tryMountDataMirror(volumeUuid);
                }
            }
        } catch (Exception e) {
            Slog.wtf(TAG, e);
            // Make sure to re-throw this exception; we must not ignore failure
            // to prepare the user storage as it could indicate that encryption
            // wasn't successfully set up.
            //
            // Very unfortunately, these errors need to be ignored for broken
            // users that already existed on-disk from older Android versions.
            UserManagerInternal umInternal = LocalServices.getService(UserManagerInternal.class);
            if (umInternal.shouldIgnorePrepareStorageErrors(userId)) {
                Slog.wtf(TAG, "ignoring error preparing storage for existing user " + userId
                        + "; device may be insecure!");
                return;
            }
            throw new RuntimeException(e);
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

    @Override
    public void fixupAppDir(String path) {
        final Matcher matcher = KNOWN_APP_DIR_PATHS.matcher(path);
        if (matcher.matches()) {
            if (matcher.group(2) == null) {
                Log.e(TAG, "Asked to fixup an app dir without a userId: " + path);
                return;
            }
            try {
                int userId = Integer.parseInt(matcher.group(2));
                String packageName = matcher.group(3);
                int uid = mContext.getPackageManager().getPackageUidAsUser(packageName, userId);
                try {
                    mVold.fixupAppDir(path + "/", uid);
                } catch (RemoteException | ServiceSpecificException e) {
                    Log.e(TAG, "Failed to fixup app dir for " + packageName, e);
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid userId in path: " + path, e);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Couldn't find package to fixup app dir " + path, e);
            }
        } else {
            Log.e(TAG, "Path " + path + " is not a valid application-specific directory");
        }
    }

    /*
     * Disable storage's app data isolation for testing.
     */
    @Override
    public void disableAppDataIsolation(String pkgName, int pid, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID && callingUid != Process.SHELL_UID) {
            throw new SecurityException("no permission to enable app visibility");
        }
        final String[] sharedPackages =
                mPmInternal.getSharedUserPackagesForPackage(pkgName, userId);
        final int uid = mPmInternal.getPackageUid(pkgName, 0, userId);
        final String[] packages =
                sharedPackages.length != 0 ? sharedPackages : new String[]{pkgName};
        try {
            mVold.unmountAppStorageDirs(uid, pid, packages);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Returns PendingIntent which can be used by Apps with MANAGE_EXTERNAL_STORAGE permission
     * to launch the manageSpaceActivity of the App specified by packageName.
     */
    @Override
    @Nullable
    public PendingIntent getManageSpaceActivityIntent(
            @NonNull String packageName, int requestCode) {
        // Only Apps with MANAGE_EXTERNAL_STORAGE permission which have package visibility for
        // packageName should be able to call this API.
        int originalUid = Binder.getCallingUidOrThrow();
        try {
            // Get package name for calling app and verify it has MANAGE_EXTERNAL_STORAGE permission
            final String[] packagesFromUid = mIPackageManager.getPackagesForUid(originalUid);
            if (packagesFromUid == null) {
                throw new SecurityException("Unknown uid " + originalUid);
            }
            // Checking first entry in packagesFromUid is enough as using "sharedUserId"
            // mechanism is rare and discouraged. Also, Apps that share same UID share the same
            // permissions.
            if (!mStorageManagerInternal.hasExternalStorageAccess(originalUid,
                    packagesFromUid[0])) {
                throw new SecurityException("Only File Manager Apps permitted");
            }
        } catch (RemoteException re) {
            throw new SecurityException("Unknown uid " + originalUid, re);
        }

        ApplicationInfo appInfo;
        try {
            appInfo = mIPackageManager.getApplicationInfo(packageName, 0,
                    UserHandle.getUserId(originalUid));
            if (appInfo == null) {
                throw new IllegalArgumentException(
                        "Invalid packageName");
            }
            if (appInfo.manageSpaceActivityName == null) {
                Log.i(TAG, packageName + " doesn't have a manageSpaceActivity");
                return null;
            }
        } catch (RemoteException e) {
            throw new SecurityException("Only File Manager Apps permitted");
        }

        // We want to call the manageSpaceActivity as a SystemService and clear identity
        // of the calling App
        final long token = Binder.clearCallingIdentity();
        try {
            Context targetAppContext = mContext.createPackageContext(packageName, 0);
            Intent intent = new Intent(Intent.ACTION_DEFAULT);
            intent.setClassName(packageName,
                    appInfo.manageSpaceActivityName);
            intent.setFlags(FLAG_ACTIVITY_NEW_TASK);

            PendingIntent activity = PendingIntent.getActivity(targetAppContext, requestCode,
                    intent,
                    FLAG_ONE_SHOT | FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);
            return activity;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException(
                    "packageName not found");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void notifyAppIoBlocked(String volumeUuid, int uid, int tid, int reason) {
        enforceExternalStorageService();

        mStorageSessionController.notifyAppIoBlocked(volumeUuid, uid, tid, reason);
    }

    @Override
    public void notifyAppIoResumed(String volumeUuid, int uid, int tid, int reason) {
        enforceExternalStorageService();

        mStorageSessionController.notifyAppIoResumed(volumeUuid, uid, tid, reason);
    }

    @Override
    public boolean isAppIoBlocked(String volumeUuid, int uid, int tid,
            @StorageManager.AppIoBlockedReason int reason) {
        return isAppIoBlocked(uid);
    }

    private boolean isAppIoBlocked(int uid) {
        return mStorageSessionController.isAppIoBlocked(uid);
    }

    @Override
    public void setCloudMediaProvider(@Nullable String authority) {
        enforceExternalStorageService();

        final int userId = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mCloudMediaProviders) {
            final String oldAuthority = mCloudMediaProviders.get(userId);
            if (!Objects.equals(authority, oldAuthority)) {
                mCloudMediaProviders.put(userId, authority);
                mHandler.obtainMessage(H_CLOUD_MEDIA_PROVIDER_CHANGED, userId, 0, authority)
                        .sendToTarget();
            }
        }
    }

    @Override
    @Nullable
    public String getCloudMediaProvider() {
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(callingUid);
        final String authority;
        synchronized (mCloudMediaProviders) {
            authority = mCloudMediaProviders.get(userId);
        }
        if (authority == null) {
            return null;
        }
        final ProviderInfo pi = mPmInternal.resolveContentProvider(
                authority, 0, userId, callingUid);
        if (pi == null
                || mPmInternal.filterAppAccess(pi.packageName, callingUid, userId)) {
            return null;
        }
        return authority;
    }

    /**
     * Enforces that the caller is the {@link ExternalStorageService}
     *
     * @throws SecurityException if the caller doesn't have the
     * {@link android.Manifest.permission.WRITE_MEDIA_STORAGE} permission or is not the
     * {@link ExternalStorageService}
     */
    private void enforceExternalStorageService() {
        enforcePermission(android.Manifest.permission.WRITE_MEDIA_STORAGE);
        int callingAppId = UserHandle.getAppId(Binder.getCallingUid());
        if (callingAppId != mMediaStoreAuthorityAppId) {
            throw new SecurityException("Only the ExternalStorageService is permitted");
        }
    }

    /** Not thread safe */
    class AppFuseMountScope extends AppFuseBridge.MountScope {
        private boolean mMounted = false;

        public AppFuseMountScope(int uid, int mountId) {
            super(uid, mountId);
        }

        @Override
        public ParcelFileDescriptor open() throws AppFuseMountException {
            try {
                final FileDescriptor fd = mVold.mountAppFuse(uid, mountId);
                mMounted = true;
                return new ParcelFileDescriptor(fd);
            } catch (Exception e) {
                throw new AppFuseMountException("Failed to mount", e);
            }
        }

        @Override
        public ParcelFileDescriptor openFile(int mountId, int fileId, int flags)
                throws AppFuseMountException {
            try {
                return new ParcelFileDescriptor(
                        mVold.openAppFuseFile(uid, mountId, fileId, flags));
            } catch (Exception e) {
                throw new AppFuseMountException("Failed to open", e);
            }
        }

        @Override
        public void close() throws Exception {
            if (mMounted) {
                mVold.unmountAppFuse(uid, mountId);
                mMounted = false;
            }
        }
    }

    @Override
    public @Nullable AppFuseMount mountProxyFileDescriptorBridge() {
        Slog.v(TAG, "mountProxyFileDescriptorBridge");
        final int uid = Binder.getCallingUid();

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
                            name, mAppFuseBridge.addBridge(new AppFuseMountScope(uid, name)));
                    } catch (FuseUnavailableMountException e) {
                        if (newlyCreated) {
                            // If newly created bridge fails, it's a real error.
                            Slog.e(TAG, "", e);
                            return null;
                        }
                        // It seems the thread of mAppFuseBridge has already been terminated.
                        mAppFuseBridge = null;
                    }
                } catch (AppFuseMountException e) {
                    throw e.rethrowAsParcelableException();
                }
            }
        }
    }

    @Override
    public @Nullable ParcelFileDescriptor openProxyFileDescriptor(
            int mountId, int fileId, int mode) {
        Slog.v(TAG, "mountProxyFileDescriptor");

        // We only support a narrow set of incoming mode flags
        mode &= MODE_READ_WRITE;

        try {
            synchronized (mAppFuseLock) {
                if (mAppFuseBridge == null) {
                    Slog.e(TAG, "FuseBridge has not been created");
                    return null;
                }
                return mAppFuseBridge.openFile(mountId, fileId, mode);
            }
        } catch (FuseUnavailableMountException | InterruptedException error) {
            Slog.v(TAG, "The mount point has already been invalid", error);
            return null;
        }
    }

    @Override
    public void mkdirs(String callingPkg, String appPath) {
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(callingUid);
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
        appOps.checkPackage(callingUid, callingPkg);

        File appFile = null;
        try {
            appFile = new File(appPath).getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve " + appPath + ": " + e);
        }

        appPath = appFile.getAbsolutePath();
        if (!appPath.endsWith("/")) {
            appPath = appPath + "/";
        }
        // Ensure that the path we're asked to create is a known application directory
        // path.
        final Matcher matcher = KNOWN_APP_DIR_PATHS.matcher(appPath);
        if (matcher.matches()) {
            // And that the package dir matches the calling package
            if (!matcher.group(3).equals(callingPkg)) {
                throw new SecurityException("Invalid mkdirs path: " + appFile
                        + " does not contain calling package " + callingPkg);
            }
            // And that the user id part of the path (if any) matches the calling user id,
            // or if for a public volume (no user id), the user matches the current user
            if ((matcher.group(2) != null && !matcher.group(2).equals(Integer.toString(userId)))
                    || (matcher.group(2) == null && userId != mCurrentUserId)) {
                throw new SecurityException("Invalid mkdirs path: " + appFile
                        + " does not match calling user id " + userId);
            }
            try {
                mVold.setupAppDir(appPath, callingUid);
            } catch (RemoteException e) {
                throw new IllegalStateException("Failed to prepare " + appPath + ": " + e);
            }

            return;
        }
        throw new SecurityException("Invalid mkdirs path: " + appFile
                + " is not a known app path.");
    }

    @Override
    public StorageVolume[] getVolumeList(int userId, String callingPackage, int flags) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);

        if (!isUidOwnerOfPackageOrSystem(callingPackage, callingUid)) {
            throw new SecurityException("callingPackage does not match UID");
        }
        if (callingUserId != userId) {
            // Callers can ask for volumes of different users, but only with the correct permissions
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS,
                    "Need INTERACT_ACROSS_USERS to get volumes for another user");
        }

        final boolean forWrite = (flags & StorageManager.FLAG_FOR_WRITE) != 0;
        final boolean realState = (flags & StorageManager.FLAG_REAL_STATE) != 0;
        final boolean includeInvisible = (flags & StorageManager.FLAG_INCLUDE_INVISIBLE) != 0;
        final boolean includeRecent = (flags & StorageManager.FLAG_INCLUDE_RECENT) != 0;
        final boolean includeSharedProfile =
                (flags & StorageManager.FLAG_INCLUDE_SHARED_PROFILE) != 0;

        // Only Apps with MANAGE_EXTERNAL_STORAGE should call the API with includeSharedProfile
        if (includeSharedProfile) {
            try {
                // Get package name for calling app and
                // verify it has MANAGE_EXTERNAL_STORAGE permission
                final String[] packagesFromUid = mIPackageManager.getPackagesForUid(callingUid);
                if (packagesFromUid == null) {
                    throw new SecurityException("Unknown uid " + callingUid);
                }
                // Checking first entry in packagesFromUid is enough as using "sharedUserId"
                // mechanism is rare and discouraged. Also, Apps that share same UID share the same
                // permissions.
                if (!mStorageManagerInternal.hasExternalStorageAccess(callingUid,
                        packagesFromUid[0])) {
                    throw new SecurityException("Only File Manager Apps permitted");
                }
            } catch (RemoteException re) {
                throw new SecurityException("Unknown uid " + callingUid, re);
            }
        }

        // Report all volumes as unmounted until we've recorded that user 0 has unlocked. There
        // are no guarantees that callers will see a consistent view of the volume before that
        // point
        final boolean systemUserUnlocked = isSystemUnlocked(UserHandle.USER_SYSTEM);

        // When the caller is the app actually hosting external storage, we
        // should never attempt to augment the actual storage volume state,
        // otherwise we risk confusing it with race conditions as users go
        // through various unlocked states
        final boolean callerIsMediaStore = UserHandle.isSameApp(callingUid,
                mMediaStoreAuthorityAppId);

        final boolean userIsDemo;
        final boolean userKeyUnlocked;
        final boolean storagePermission;
        final long token = Binder.clearCallingIdentity();
        try {
            userIsDemo = LocalServices.getService(UserManagerInternal.class)
                    .getUserInfo(userId).isDemo();
            storagePermission = mStorageManagerInternal.hasExternalStorage(callingUid,
                    callingPackage);
            userKeyUnlocked = isUserKeyUnlocked(userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        boolean foundPrimary = false;

        final ArrayList<StorageVolume> res = new ArrayList<>();
        final ArraySet<String> resUuids = new ArraySet<>();
        final int userIdSharingMedia = mUserSharesMediaWith.getOrDefault(userId, -1);
        synchronized (mLock) {
            for (int i = 0; i < mVolumes.size(); i++) {
                final String volId = mVolumes.keyAt(i);
                final VolumeInfo vol = mVolumes.valueAt(i);
                switch (vol.getType()) {
                    case VolumeInfo.TYPE_PUBLIC:
                    case VolumeInfo.TYPE_STUB:
                        break;
                    case VolumeInfo.TYPE_EMULATED:
                        if (vol.getMountUserId() == userId) {
                            break;
                        }
                        if (includeSharedProfile && vol.getMountUserId() == userIdSharingMedia) {
                            // If the volume belongs to a user we share media with,
                            // return it too.
                            break;
                        }
                        // Skip if emulated volume not for userId
                    default:
                        continue;
                }

                boolean match = false;
                if (forWrite) {
                    match = vol.isVisibleForWrite(userId)
                            || (includeSharedProfile && vol.isVisibleForWrite(userIdSharingMedia));
                } else {
                    match = vol.isVisibleForUser(userId)
                            || (!vol.isVisible() && includeInvisible && vol.getPath() != null)
                            || (includeSharedProfile && vol.isVisibleForRead(userIdSharingMedia));
                }
                if (!match) continue;

                boolean reportUnmounted = false;
                if (callerIsMediaStore) {
                    // When the caller is the app actually hosting external storage, we
                    // should never attempt to augment the actual storage volume state,
                    // otherwise we risk confusing it with race conditions as users go
                    // through various unlocked states
                } else if (!systemUserUnlocked) {
                    reportUnmounted = true;
                    Slog.w(TAG, "Reporting " + volId + " unmounted due to system locked");
                } else if ((vol.getType() == VolumeInfo.TYPE_EMULATED) && !userKeyUnlocked) {
                    reportUnmounted = true;
                    Slog.w(TAG, "Reporting " + volId + "unmounted due to " + userId + " locked");
                } else if (!storagePermission && !realState) {
                    Slog.w(TAG, "Reporting " + volId + "unmounted due to missing permissions");
                    reportUnmounted = true;
                }

                int volUserId = userId;
                if (volUserId != vol.getMountUserId() && vol.getMountUserId() >= 0) {
                    volUserId = vol.getMountUserId();
                }
                final StorageVolume userVol = vol.buildStorageVolume(mContext, volUserId,
                        reportUnmounted);
                if (vol.isPrimary() && vol.getMountUserId() == userId) {
                    res.add(0, userVol);
                    foundPrimary = true;
                } else {
                    res.add(userVol);
                }
                resUuids.add(userVol.getUuid());
            }

            if (includeRecent) {
                final long lastWeek = System.currentTimeMillis() - DateUtils.WEEK_IN_MILLIS;
                for (int i = 0; i < mRecords.size(); i++) {
                    final VolumeRecord rec = mRecords.valueAt(i);

                    // Skip if we've already included it above
                    if (resUuids.contains(rec.fsUuid)) continue;

                    // Treat as recent if mounted within the last week
                    if (rec.lastSeenMillis > 0 && rec.lastSeenMillis < lastWeek) {
                        final StorageVolume userVol = rec.buildStorageVolume(mContext);
                        res.add(userVol);
                        resUuids.add(userVol.getUuid());
                    }
                }
            }
        }

        // Synthesize a volume for preloaded media under demo users, so that
        // it's scanned into MediaStore
        if (userIsDemo) {
            final String id = "demo";
            final File path = Environment.getDataPreloadsMediaDirectory();
            final boolean primary = false;
            final boolean removable = false;
            final boolean emulated = true;
            final boolean externallyManaged = false;
            final boolean allowMassStorage = false;
            final long maxFileSize = 0;
            final UserHandle user = new UserHandle(userId);
            final String envState = Environment.MEDIA_MOUNTED_READ_ONLY;
            final String description = mContext.getString(android.R.string.unknownName);

            res.add(new StorageVolume(id, path, path, description, primary, removable, emulated,
                    externallyManaged, allowMassStorage, maxFileSize, user, null /*uuid */, id,
                    envState));
        }

        if (!foundPrimary) {
            Slog.w(TAG, "No primary storage defined yet; hacking together a stub");

            final boolean primaryPhysical = SystemProperties.getBoolean(
                    StorageManager.PROP_PRIMARY_PHYSICAL, false);

            final String id = "stub_primary";
            final File path = Environment.getLegacyExternalStorageDirectory();
            final String description = mContext.getString(android.R.string.unknownName);
            final boolean primary = true;
            final boolean removable = primaryPhysical;
            final boolean emulated = !primaryPhysical;
            final boolean externallyManaged = false;
            final boolean allowMassStorage = false;
            final long maxFileSize = 0L;
            final UserHandle owner = new UserHandle(userId);
            final String fsUuid = null;
            final UUID uuid = null;
            final String state = Environment.MEDIA_REMOVED;

            res.add(0, new StorageVolume(id, path, path,
                    description, primary, removable, emulated, externallyManaged,
                    allowMassStorage, maxFileSize, owner, uuid, fsUuid, state));
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

            long usable = 0;
            long lowReserved = 0;
            long fullReserved = 0;
            long cacheClearable = 0;

            if ((flags & StorageManager.FLAG_ALLOCATE_CACHE_ONLY) == 0) {
                usable = path.getUsableSpace();
                lowReserved = storage.getStorageLowBytes(path);
                fullReserved = storage.getStorageFullBytes(path);
            }

            if ((flags & StorageManager.FLAG_ALLOCATE_NON_CACHE_ONLY) == 0
                    && stats.isQuotaSupported(volumeUuid)) {
                final long cacheTotal = stats.getCacheBytes(volumeUuid);
                final long cacheReserved = storage.getStorageCacheBytes(path, flags);
                cacheClearable = Math.max(0, cacheTotal - cacheReserved);
            }

            if ((flags & StorageManager.FLAG_ALLOCATE_AGGRESSIVE) != 0) {
                return Math.max(0, (usable + cacheClearable) - fullReserved);
            } else {
                return Math.max(0, (usable + cacheClearable) - lowReserved);
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

        final long allocatableBytes = getAllocatableBytes(volumeUuid,
                flags | StorageManager.FLAG_ALLOCATE_NON_CACHE_ONLY, callingPackage);
        if (bytes > allocatableBytes) {
            // If we don't have room without taking cache into account, check to see if we'd have
            // room if we included freeable cache space.
            final long cacheClearable = getAllocatableBytes(volumeUuid,
                    flags | StorageManager.FLAG_ALLOCATE_CACHE_ONLY, callingPackage);
            if (bytes > allocatableBytes + cacheClearable) {
                throw new ParcelableException(new IOException("Failed to allocate " + bytes
                    + " because only " + (allocatableBytes + cacheClearable) + " allocatable"));
            }
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

            mPmInternal.freeStorage(volumeUuid, bytes, flags);
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

                    action.execute(this);
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

        ObbState mObbState;

        ObbAction(ObbState obbState) {
            mObbState = obbState;
        }

        public void execute(ObbActionHandler handler) {
            try {
                if (DEBUG_OBB)
                    Slog.i(TAG, "Starting to execute action: " + toString());
                handleExecute();
            } catch (ObbException e) {
                notifyObbStateChange(e);
            }
        }

        abstract void handleExecute() throws ObbException;

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
        private final int mCallingUid;
        private ObbInfo mObbInfo;

        MountObbAction(ObbState obbState, int callingUid, ObbInfo obbInfo) {
            super(obbState);
            mCallingUid = callingUid;
            mObbInfo = obbInfo;
        }

        @Override
        public void handleExecute() throws ObbException {
            warnOnNotMounted();

            if (!isUidOwnerOfPackageOrSystem(mObbInfo.packageName, mCallingUid)) {
                throw new ObbException(ERROR_PERMISSION_DENIED, "Denied attempt to mount OBB "
                        + mObbInfo.filename + " which is owned by " + mObbInfo.packageName);
            }

            final boolean isMounted;
            synchronized (mObbMounts) {
                isMounted = mObbPathToStateMap.containsKey(mObbState.rawPath);
            }
            if (isMounted) {
                throw new ObbException(ERROR_ALREADY_MOUNTED,
                        "Attempt to mount OBB which is already mounted: " + mObbInfo.filename);
            }

            try {
                mObbState.volId = mVold.createObb(mObbState.canonicalPath, mObbState.ownerGid);
                mVold.mount(mObbState.volId, 0, -1, null);

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

    @Override
    public int getExternalStorageMountMode(int uid, String packageName) {
        enforcePermission(android.Manifest.permission.WRITE_MEDIA_STORAGE);
        return mStorageManagerInternal.getExternalStorageMountMode(uid, packageName);
    }

    private int getMountModeInternal(int uid, String packageName) {
        try {
            // Get some easy cases out of the way first
            if (Process.isIsolated(uid) || Process.isSdkSandboxUid(uid)) {
                return StorageManager.MOUNT_MODE_EXTERNAL_NONE;
            }

            final String[] packagesForUid = mIPackageManager.getPackagesForUid(uid);
            if (ArrayUtils.isEmpty(packagesForUid)) {
                // It's possible the package got uninstalled already, so just ignore.
                return StorageManager.MOUNT_MODE_EXTERNAL_NONE;
            }
            if (packageName == null) {
                packageName = packagesForUid[0];
            }

            final long token = Binder.clearCallingIdentity();
            try {
                if (mPmInternal.isInstantApp(packageName, UserHandle.getUserId(uid))) {
                    return StorageManager.MOUNT_MODE_EXTERNAL_NONE;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }

            if (mStorageManagerInternal.isExternalStorageService(uid)) {
                // Determine if caller requires pass_through mount; note that we do this for
                // all processes that share a UID with MediaProvider; but this is fine, since
                // those processes anyway share the same rights as MediaProvider.
                return StorageManager.MOUNT_MODE_EXTERNAL_PASS_THROUGH;
            }

            if ((mDownloadsAuthorityAppId == UserHandle.getAppId(uid)
                    || mExternalStorageAuthorityAppId == UserHandle.getAppId(uid))) {
                // DownloadManager can write in app-private directories on behalf of apps;
                // give it write access to Android/
                // ExternalStorageProvider can access Android/{data,obb} dirs in managed mode
                return StorageManager.MOUNT_MODE_EXTERNAL_ANDROID_WRITABLE;
            }

            final boolean hasMtp = mIPackageManager.checkUidPermission(ACCESS_MTP, uid) ==
                    PERMISSION_GRANTED;
            if (hasMtp) {
                ApplicationInfo ai = mIPackageManager.getApplicationInfo(packageName,
                        0, UserHandle.getUserId(uid));
                if (ai != null && ai.isSignedWithPlatformKey()) {
                    // Platform processes hosting the MTP server should be able to write in Android/
                    return StorageManager.MOUNT_MODE_EXTERNAL_ANDROID_WRITABLE;
                }
            }

            // Determine if caller is holding runtime permission
            final boolean hasWrite = StorageManager.checkPermissionAndCheckOp(mContext, false, 0,
                    uid, packageName, WRITE_EXTERNAL_STORAGE, OP_WRITE_EXTERNAL_STORAGE);

            // We're only willing to give out installer access if they also hold
            // runtime permission; this is a firm CDD requirement
            final boolean hasInstall = mIPackageManager.checkUidPermission(INSTALL_PACKAGES,
                    uid) == PERMISSION_GRANTED;
            boolean hasInstallOp = false;
            // OP_REQUEST_INSTALL_PACKAGES is granted/denied per package but vold can't
            // update mountpoints of a specific package. So, check the appop for all packages
            // sharing the uid and allow same level of storage access for all packages even if
            // one of the packages has the appop granted.
            for (String uidPackageName : packagesForUid) {
                if (mIAppOpsService.checkOperation(
                        OP_REQUEST_INSTALL_PACKAGES, uid, uidPackageName) == MODE_ALLOWED) {
                    hasInstallOp = true;
                    break;
                }
            }
            if ((hasInstall || hasInstallOp) && hasWrite) {
                return StorageManager.MOUNT_MODE_EXTERNAL_INSTALLER;
            }
            return StorageManager.MOUNT_MODE_EXTERNAL_DEFAULT;
        } catch (RemoteException e) {
            // Should not happen
        }
        return StorageManager.MOUNT_MODE_EXTERNAL_NONE;
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

            pw.println();
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

            pw.println();
            pw.println("Local unlocked users: " + mLocalUnlockedUsers);
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
            final Iterator<Entry<String, ObbState>> maps =
                    mObbPathToStateMap.entrySet().iterator();
            while (maps.hasNext()) {
                final Entry<String, ObbState> e = maps.next();
                pw.print(e.getKey());
                pw.print(" -> ");
                pw.println(e.getValue());
            }
            pw.decreaseIndent();
        }

        synchronized (mCloudMediaProviders) {
            pw.println();
            pw.print("Media cloud providers: ");
            pw.println(mCloudMediaProviders);
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
        @GuardedBy("mResetListeners")
        private final List<StorageManagerInternal.ResetListener> mResetListeners =
                new ArrayList<>();

        private final CopyOnWriteArraySet<StorageManagerInternal.CloudProviderChangeListener>
                mCloudProviderChangeListeners = new CopyOnWriteArraySet<>();

        @Override
        public boolean isFuseMounted(int userId) {
            synchronized (mLock) {
                return mFuseMountedUser.contains(userId);
            }
        }

        /**
         * Check if fuse is running in target user, if it's running then setup its storage dirs.
         * Return true if storage dirs are mounted.
         */
        @Override
        public boolean prepareStorageDirs(int userId, Set<String> packageList,
                String processName) {
            synchronized (mLock) {
                if (!mFuseMountedUser.contains(userId)) {
                    Slog.w(TAG, "User " + userId + " is not unlocked yet so skip mounting obb");
                    return false;
                }
            }
            try {
                final IVold vold = IVold.Stub.asInterface(
                        ServiceManager.getServiceOrThrow("vold"));
                for (String pkg : packageList) {
                    final String packageObbDir =
                            String.format(Locale.US, "/storage/emulated/%d/Android/obb/%s/",
                                    userId, pkg);
                    final String packageDataDir =
                            String.format(Locale.US, "/storage/emulated/%d/Android/data/%s/",
                                    userId, pkg);

                    // Create package obb and data dir if it doesn't exist.
                    int appUid = UserHandle.getUid(userId, mPmInternal.getPackage(pkg).getUid());
                    vold.ensureAppDirsCreated(new String[] {packageObbDir, packageDataDir}, appUid);
                }
            } catch (ServiceManager.ServiceNotFoundException | RemoteException e) {
                Slog.e(TAG, "Unable to create obb and data directories for " + processName,e);
                return false;
            }
            return true;
        }

        @Override
        public int getExternalStorageMountMode(int uid, String packageName) {
            final int mode = getMountModeInternal(uid, packageName);
            if (LOCAL_LOGV) {
                Slog.v(TAG, "Resolved mode " + mode + " for " + packageName + "/"
                        + UserHandle.formatUid(uid));
            }
            return mode;
        }

        @Override
        public boolean hasExternalStorageAccess(int uid, String packageName) {
            try {
                final int opMode = mIAppOpsService.checkOperation(
                        OP_MANAGE_EXTERNAL_STORAGE, uid, packageName);
                if (opMode == AppOpsManager.MODE_DEFAULT) {
                    return mIPackageManager.checkUidPermission(
                            MANAGE_EXTERNAL_STORAGE, uid) == PERMISSION_GRANTED;
                }

                return opMode == AppOpsManager.MODE_ALLOWED;
            } catch (RemoteException e) {
                Slog.w("Failed to check MANAGE_EXTERNAL_STORAGE access for " + packageName, e);
            }
            return false;
        }

        @Override
        public void addResetListener(StorageManagerInternal.ResetListener listener) {
            synchronized (mResetListeners) {
                mResetListeners.add(listener);
            }
        }

        public void onReset(IVold vold) {
            synchronized (mResetListeners) {
                for (StorageManagerInternal.ResetListener listener : mResetListeners) {
                    listener.onReset(vold);
                }
            }
        }

        @Override
        public void resetUser(int userId) {
            // TODO(b/145931219): ideally, we only reset storage for the user in question,
            // but for now, reset everything.
            mHandler.obtainMessage(H_RESET).sendToTarget();
        }

        @Override
        public boolean hasLegacyExternalStorage(int uid) {
            synchronized (mLock) {
                return mUidsWithLegacyExternalStorage.contains(uid);
            }
        }

        @Override
        public void prepareAppDataAfterInstall(String packageName, int uid) {
            int userId = UserHandle.getUserId(uid);
            final Environment.UserEnvironment userEnv = new Environment.UserEnvironment(userId);

            // The installer may have downloaded OBBs for this newly installed application;
            // make sure the OBB dir for the application is setup correctly, if it exists.
            File[] packageObbDirs = userEnv.buildExternalStorageAppObbDirs(packageName);
            for (File packageObbDir : packageObbDirs) {
                if (packageObbDir.getPath().startsWith(
                                Environment.getDataPreloadsMediaDirectory().getPath())) {
                    Slog.i(TAG, "Skipping app data preparation for " + packageObbDir);
                    continue;
                }
                try {
                    mVold.fixupAppDir(packageObbDir.getCanonicalPath() + "/", uid);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to get canonical path for " + packageName);
                } catch (RemoteException | ServiceSpecificException e) {
                    // TODO(b/149975102) there is a known case where this fails, when a new
                    // user is setup and we try to fixup app dirs for some existing apps.
                    // For now catch the exception and don't crash.
                    Log.e(TAG, "Failed to fixup app dir for " + packageName, e);
                }
            }
        }

        @Override
        public boolean isExternalStorageService(int uid) {
            return mMediaStoreAuthorityAppId == UserHandle.getAppId(uid);
        }

        @Override
        public void freeCache(String volumeUuid, long freeBytes) {
            try {
                mStorageSessionController.freeCache(volumeUuid, freeBytes);
            } catch (ExternalStorageServiceException e) {
                Log.e(TAG, "Failed to free cache of vol : " + volumeUuid, e);
            }
        }

        public boolean hasExternalStorage(int uid, String packageName) {
            // No need to check for system uid. This avoids a deadlock between
            // PackageManagerService and AppOpsService.
            if (uid == Process.SYSTEM_UID) {
                return true;
            }

            return getExternalStorageMountMode(uid, packageName)
                    != StorageManager.MOUNT_MODE_EXTERNAL_NONE;
        }

        private void killAppForOpChange(int code, int uid) {
            final IActivityManager am = ActivityManager.getService();
            try {
                am.killUid(UserHandle.getAppId(uid), UserHandle.USER_ALL,
                        AppOpsManager.opToName(code) + " changed.");
            } catch (RemoteException e) {
            }
        }

        public void onAppOpsChanged(int code, int uid, @Nullable String packageName, int mode,
                int previousMode) {
            final long token = Binder.clearCallingIdentity();
            try {
                // When using FUSE, we may need to kill the app if the op changes
                switch(code) {
                    case OP_REQUEST_INSTALL_PACKAGES:
                        // In R, we used to kill the app here if it transitioned to/from
                        // MODE_ALLOWED, to make sure the app had the correct (writable) OBB
                        // view. But the majority of apps don't handle OBBs anyway, and for those
                        // that do, they can restart themselves. Therefore, starting from S,
                        // only kill the app when it transitions away from MODE_ALLOWED (eg,
                        // when the permission is taken away).
                        if (previousMode == MODE_ALLOWED && mode != MODE_ALLOWED) {
                            killAppForOpChange(code, uid);
                        }
                        return;
                    case OP_MANAGE_EXTERNAL_STORAGE:
                        if (mode != MODE_ALLOWED) {
                            // Only kill if op is denied, to lose external_storage gid
                            // Killing when op is granted to pickup the gid automatically,
                            // results in a bad UX, especially since the gid only gives access
                            // to unreliable volumes, USB OTGs that are rarely mounted. The app
                            // will get the external_storage gid on next organic restart.
                            killAppForOpChange(code, uid);
                        }
                        return;
                    case OP_LEGACY_STORAGE:
                        updateLegacyStorageApps(packageName, uid, mode == MODE_ALLOWED);
                        return;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public List<String> getPrimaryVolumeIds() {
            final List<String> primaryVolumeIds = new ArrayList<>();
            synchronized (mLock) {
                for (int i = 0; i < mVolumes.size(); i++) {
                    final VolumeInfo vol = mVolumes.valueAt(i);
                    if (vol.isPrimary()) {
                        primaryVolumeIds.add(vol.getId());
                    }
                }
            }
            return primaryVolumeIds;
        }

        @Override
        public void markCeStoragePrepared(int userId) {
            synchronized (mLock) {
                mCeStoragePreparedUsers.add(userId);
            }
        }

        @Override
        public boolean isCeStoragePrepared(int userId) {
            synchronized (mLock) {
                return mCeStoragePreparedUsers.contains(userId);
            }
        }

        @Override
        public void registerCloudProviderChangeListener(
                @NonNull StorageManagerInternal.CloudProviderChangeListener listener) {
            mCloudProviderChangeListeners.add(listener);
            mHandler.obtainMessage(H_CLOUD_MEDIA_PROVIDER_CHANGED, listener);
        }
    }
}
