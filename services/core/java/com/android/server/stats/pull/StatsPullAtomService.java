/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.stats.pull;

import static android.app.AppOpsManager.OP_FLAG_SELF;
import static android.app.AppOpsManager.OP_FLAG_TRUSTED_PROXIED;
import static android.app.AppProtoEnums.HOSTING_COMPONENT_TYPE_EMPTY;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
import static android.hardware.display.HdrConversionMode.HDR_CONVERSION_PASSTHROUGH;
import static android.hardware.display.HdrConversionMode.HDR_CONVERSION_UNSUPPORTED;
import static android.hardware.graphics.common.Hdr.DOLBY_VISION;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkStats.METERED_YES;
import static android.net.NetworkTemplate.MATCH_ETHERNET;
import static android.net.NetworkTemplate.MATCH_MOBILE;
import static android.net.NetworkTemplate.MATCH_PROXY;
import static android.net.NetworkTemplate.MATCH_WIFI;
import static android.net.NetworkTemplate.OEM_MANAGED_ALL;
import static android.net.NetworkTemplate.OEM_MANAGED_PAID;
import static android.net.NetworkTemplate.OEM_MANAGED_PRIVATE;
import static android.os.Debug.getIonHeapsSizeKb;
import static android.os.Process.INVALID_UID;
import static android.os.Process.LAST_SHARED_APPLICATION_GID;
import static android.os.Process.SYSTEM_UID;
import static android.os.Process.getUidForPid;
import static android.os.storage.VolumeInfo.TYPE_PRIVATE;
import static android.os.storage.VolumeInfo.TYPE_PUBLIC;
import static android.provider.Settings.Global.NETSTATS_UID_BUCKET_DURATION;
import static android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID;
import static android.util.MathUtils.constrain;
import static android.view.Display.HdrCapabilities.HDR_TYPE_INVALID;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_STATS__GESTURE_SHORTCUT_TYPE__TRIPLE_TAP;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_STATS__HARDWARE_SHORTCUT_TYPE__VOLUME_KEY;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_STATS__QS_SHORTCUT_TYPE__QUICK_SETTINGS;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_STATS__SOFTWARE_SHORTCUT_TYPE__A11Y_BUTTON;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_STATS__SOFTWARE_SHORTCUT_TYPE__A11Y_FLOATING_MENU;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_STATS__SOFTWARE_SHORTCUT_TYPE__A11Y_GESTURE;
import static com.android.internal.util.FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_STATS__SOFTWARE_SHORTCUT_TYPE__UNKNOWN_TYPE;
import static com.android.internal.util.FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER__OPPORTUNISTIC_DATA_SUB__NOT_OPPORTUNISTIC;
import static com.android.internal.util.FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER__OPPORTUNISTIC_DATA_SUB__OPPORTUNISTIC;
import static com.android.internal.util.FrameworkStatsLog.TIME_ZONE_DETECTOR_STATE__DETECTION_MODE__GEO;
import static com.android.internal.util.FrameworkStatsLog.TIME_ZONE_DETECTOR_STATE__DETECTION_MODE__MANUAL;
import static com.android.internal.util.FrameworkStatsLog.TIME_ZONE_DETECTOR_STATE__DETECTION_MODE__TELEPHONY;
import static com.android.internal.util.FrameworkStatsLog.TIME_ZONE_DETECTOR_STATE__DETECTION_MODE__UNKNOWN;
import static com.android.server.am.MemoryStatUtil.readMemoryStatFromFilesystem;
import static com.android.server.stats.Flags.addMobileBytesTransferByProcStatePuller;
import static com.android.server.stats.Flags.applyNetworkStatsPollRateLimit;
import static com.android.server.stats.pull.IonMemoryUtil.readProcessSystemIonHeapSizesFromDebugfs;
import static com.android.server.stats.pull.IonMemoryUtil.readSystemIonHeapSizeFromDebugfs;
import static com.android.server.stats.pull.ProcfsMemoryUtil.getProcessCmdlines;
import static com.android.server.stats.pull.ProcfsMemoryUtil.readCmdlineFromProcfs;
import static com.android.server.stats.pull.ProcfsMemoryUtil.readMemorySnapshotFromProcfs;
import static com.android.server.stats.pull.netstats.NetworkStatsUtils.fromPublicNetworkStats;
import static com.android.server.stats.pull.netstats.NetworkStatsUtils.isAddEntriesSupported;

import static libcore.io.IoUtils.closeQuietly;

import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.AppOpsManager.HistoricalOp;
import android.app.AppOpsManager.HistoricalOps;
import android.app.AppOpsManager.HistoricalOpsRequest;
import android.app.AppOpsManager.HistoricalPackageOps;
import android.app.AppOpsManager.HistoricalUidOps;
import android.app.INotificationManager;
import android.app.PendingIntentStats;
import android.app.ProcessMemoryState;
import android.app.RuntimeAppOpAccessMessage;
import android.app.StatsManager;
import android.app.StatsManager.PullAtomMetadata;
import android.app.usage.NetworkStatsManager;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.UidTraffic;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IncrementalStatesInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PermissionInfo;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.display.DisplayManager;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.media.AudioManager;
import android.media.MediaDrm;
import android.media.UnsupportedSchemeException;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.BatteryProperty;
import android.os.BatteryStats;
import android.os.BatteryStatsInternal;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CoolingDevice;
import android.os.Environment;
import android.os.IStoraged;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.StatFs;
import android.os.SynchronousResultReceiver;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Temperature;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.os.incremental.IncrementalManager;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.security.metrics.CrashStats;
import android.security.metrics.IKeystoreMetrics;
import android.security.metrics.KeyCreationWithAuthInfo;
import android.security.metrics.KeyCreationWithGeneralInfo;
import android.security.metrics.KeyCreationWithPurposeAndModesInfo;
import android.security.metrics.KeyOperationWithGeneralInfo;
import android.security.metrics.KeyOperationWithPurposeAndModesInfo;
import android.security.metrics.Keystore2AtomWithOverflow;
import android.security.metrics.KeystoreAtom;
import android.security.metrics.KeystoreAtomPayload;
import android.security.metrics.RkpErrorStats;
import android.security.metrics.StorageStats;
import android.stats.storage.StorageEnums;
import android.telephony.ModemActivityInfo;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.StatsEvent;
import android.util.proto.ProtoOutputStream;
import android.uwb.UwbActivityEnergyInfo;
import android.uwb.UwbManager;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.procstats.IProcessStats;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.app.procstats.StatsEventOutput;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BinderCallsStats.ExportedCallStat;
import com.android.internal.os.KernelAllocationStats;
import com.android.internal.os.KernelCpuBpfTracking;
import com.android.internal.os.KernelCpuThreadReader;
import com.android.internal.os.KernelCpuThreadReaderDiff;
import com.android.internal.os.KernelCpuThreadReaderSettingsObserver;
import com.android.internal.os.KernelCpuTotalBpfMapReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidActiveTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidClusterTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidUserSysTimeReader;
import com.android.internal.os.KernelSingleProcessCpuThreadReader.ProcessCpuUsage;
import com.android.internal.os.LooperStats;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.SelectedProcessCpuThreadReader;
import com.android.internal.os.StoragedUidIoStatsReader;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.role.RoleManagerLocal;
import com.android.server.BinderCallsStatsService;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.PinnerService;
import com.android.server.PinnerService.PinnedFileStats;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.am.MemoryStatUtil.MemoryStat;
import com.android.server.health.HealthServiceWrapper;
import com.android.server.notification.NotificationManagerService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.power.stats.KernelWakelockReader;
import com.android.server.power.stats.KernelWakelockStats;
import com.android.server.power.stats.SystemServerCpuThreadReader.SystemServiceCpuThreadTimes;
import com.android.server.stats.pull.IonMemoryUtil.IonAllocations;
import com.android.server.stats.pull.ProcfsMemoryUtil.MemorySnapshot;
import com.android.server.stats.pull.netstats.NetworkStatsExt;
import com.android.server.stats.pull.netstats.SubInfo;
import com.android.server.storage.DiskStatsFileLogger;
import com.android.server.storage.DiskStatsLoggingService;
import com.android.server.timezonedetector.MetricsTimeZoneDetectorState;
import com.android.server.timezonedetector.TimeZoneDetectorInternal;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * SystemService containing PullAtomCallbacks that are registered with statsd.
 *
 * @hide
 */
public class StatsPullAtomService extends SystemService {
    private static final String TAG = "StatsPullAtomService";
    private static final boolean DEBUG = true;

    // Random seed stable for StatsPullAtomService life cycle - can be used for stable sampling
    private static final int RANDOM_SEED = new Random().nextInt();

    private static final int DIMENSION_KEY_SIZE_HARD_LIMIT = 800;
    private static final int DIMENSION_KEY_SIZE_SOFT_LIMIT = 500;
    private static final long APP_OPS_SAMPLING_INITIALIZATION_DELAY_MILLIS = 45000;
    private static final int APP_OPS_SIZE_ESTIMATE = 2000;

    private static final String RESULT_RECEIVER_CONTROLLER_KEY = "controller_activity";
    /**
     * How long to wait on an individual subsystem to return its stats.
     */
    private static final long EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS = 2000;
    private static final long MILLIS_PER_SEC = 1000;
    private static final long MILLI_AMP_HR_TO_NANO_AMP_SECS = 1_000_000L * 3600L;

    /**
     * The default bucket duration used when query a snapshot from NetworkStatsService.
     * The value should be sync with NetworkStatsService#DefaultNetworkStatsSettings#getUidConfig.
     */
    private static final long NETSTATS_UID_DEFAULT_BUCKET_DURATION_MS = HOURS.toMillis(2);

    /**
     * Polling NetworkStats is a heavy operation and it should be done sparingly. Atom pulls may
     * happen in bursts, but these should be infrequent. The poll rate limit ensures that data is
     * sufficiently fresh (i.e. not stale) while reducing system load during atom pull bursts.
     */
    private static final long NETSTATS_POLL_RATE_LIMIT_MS = 15000;

    private static final int CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES = 8;
    private static final int OP_FLAGS_PULLED = OP_FLAG_SELF | OP_FLAG_TRUSTED_PROXIED;
    private static final String COMMON_PERMISSION_PREFIX = "android.permission.";
    private static final String APP_OPS_TARGET_COLLECTION_SIZE = "app_ops_target_collection_size";
    private static final String DANGEROUS_PERMISSION_STATE_SAMPLE_RATE =
            "dangerous_permission_state_sample_rate";

    /** Parameters relating to ProcStats data upload. */
    // Maximum shards to use when generating StatsEvent objects from ProcStats.
    private static final int MAX_PROCSTATS_SHARDS = 5;
    // Should match MAX_PAYLOAD_SIZE in StatsEvent, minus a small amount for overhead/metadata.
    private static final int MAX_PROCSTATS_SHARD_SIZE = 48 * 1024; // 48 KB
    // In ProcessStats, we measure the size of a raw ProtoOutputStream, before compaction. This
    // typically runs 35-45% larger than the compacted size that will be written to StatsEvent.
    // Hence, we can allow a little more room in each shard before moving to the next. Make this
    // 20% as a conservative estimate.
    private static final int MAX_PROCSTATS_RAW_SHARD_SIZE = (int) (MAX_PROCSTATS_SHARD_SIZE * 1.20);

    /**
     * Threshold to filter out small CPU times at frequency per UID. Those small values appear
     * because of more precise accounting in a BPF program. Discarding them reduces the data by at
     * least 20% with negligible error.
     */
    private static final int MIN_CPU_TIME_PER_UID_FREQ = 10;

    /** Number of entries in CpuCyclesPerUidCluster atom stored in an array for each cluster. */
    private static final int CPU_CYCLES_PER_UID_CLUSTER_VALUES = 3;

    private final Object mThermalLock = new Object();
    @GuardedBy("mThermalLock")
    private IThermalService mThermalService;

    private final Object mStoragedLock = new Object();
    @GuardedBy("mStoragedLock")
    private IStoraged mStorageService;

    private final Object mNotificationStatsLock = new Object();
    @GuardedBy("mNotificationStatsLock")
    private INotificationManager mNotificationManagerService;

    @GuardedBy("mProcStatsLock")
    private IProcessStats mProcessStatsService;

    @GuardedBy("mProcessCpuTimeLock")
    private ProcessCpuTracker mProcessCpuTracker;

    @GuardedBy("mDebugElapsedClockLock")
    private long mDebugElapsedClockPreviousValue = 0;
    @GuardedBy("mDebugElapsedClockLock")
    private long mDebugElapsedClockPullCount = 0;

    @GuardedBy("mDebugFailingElapsedClockLock")
    private long mDebugFailingElapsedClockPreviousValue = 0;
    @GuardedBy("mDebugFailingElapsedClockLock")
    private long mDebugFailingElapsedClockPullCount = 0;

    private final Context mContext;
    private StatsManager mStatsManager;
    private StorageManager mStorageManager;
    private WifiManager mWifiManager;
    private TelephonyManager mTelephony;
    private UwbManager mUwbManager;
    private SubscriptionManager mSubscriptionManager;

    /**
     * NetworkStatsManager initialization happens from one thread before any worker thread
     * is going to access the networkStatsManager instance:
     * - @initNetworkStatsManager() - initialization happens no worker thread to access are
     *   active yet
     * - @initAndRegisterNetworkStatsPullers Network stats dependant pullers can only be
     *   initialized after service is ready. Worker thread is spawn here only after the
     *   initialization is completed in a thread safe way (no async access expected)
     */
    private NetworkStatsManager mNetworkStatsManager = null;

    @GuardedBy("mKernelWakelockLock")
    private KernelWakelockReader mKernelWakelockReader;
    @GuardedBy("mKernelWakelockLock")
    private KernelWakelockStats mTmpWakelockStats;

    @GuardedBy("mDiskIoLock")
    private StoragedUidIoStatsReader mStoragedUidIoStatsReader;

    // Disables throttler on CPU time readers.
    @GuardedBy("mCpuTimePerUidLock")
    private KernelCpuUidUserSysTimeReader mCpuUidUserSysTimeReader;
    @GuardedBy("mCpuTimePerUidFreqLock")
    private KernelCpuUidFreqTimeReader mCpuUidFreqTimeReader;
    @GuardedBy("mCpuActiveTimeLock")
    private KernelCpuUidActiveTimeReader mCpuUidActiveTimeReader;
    @GuardedBy("mClusterTimeLock")
    private KernelCpuUidClusterTimeReader mCpuUidClusterTimeReader;

    @GuardedBy("mProcStatsLock")
    private File mBaseDir;

    @GuardedBy("mHealthHalLock")
    private HealthServiceWrapper mHealthService;

    @Nullable
    @GuardedBy("mCpuTimePerThreadFreqLock")
    private KernelCpuThreadReaderDiff mKernelCpuThreadReader;

    private StatsPullAtomCallbackImpl mStatsCallbackImpl;

    @GuardedBy("mAttributedAppOpsLock")
    private int mAppOpsSamplingRate = 0;
    private final Object mDangerousAppOpsListLock = new Object();
    @GuardedBy("mDangerousAppOpsListLock")
    private final ArraySet<Integer> mDangerousAppOpsList = new ArraySet<>();

    // Baselines that stores list of NetworkStats right after initializing, with associated
    // information. This is used to calculate difference when pulling BytesTransfer atoms.
    @NonNull
    @GuardedBy("mDataBytesTransferLock")
    private final ArrayList<NetworkStatsExt> mNetworkStatsBaselines = new ArrayList<>();

    @GuardedBy("mDataBytesTransferLock")
    private long mLastNetworkStatsPollTime = -NETSTATS_POLL_RATE_LIMIT_MS;

    // Listener for monitoring subscriptions changed event.
    private StatsSubscriptionsListener mStatsSubscriptionsListener;
    // List that stores SubInfo of subscriptions that ever appeared since boot.
    @GuardedBy("mDataBytesTransferLock")
    private final ArrayList<SubInfo> mHistoricalSubs = new ArrayList<>();

    private SelectedProcessCpuThreadReader mSurfaceFlingerProcessCpuThreadReader;

    // Only access via getIKeystoreMetricsService
    @GuardedBy("mKeystoreLock")
    private IKeystoreMetrics mIKeystoreMetrics;

    private AggregatedMobileDataStatsPuller mAggregatedMobileDataStatsPuller = null;

    /**
     * Whether or not to enable the new puller with aggregation by process state per uid on a
     * system server side.
     */
    public static final boolean ENABLE_MOBILE_DATA_STATS_AGGREGATED_PULLER =
                addMobileBytesTransferByProcStatePuller();

    // Puller locks
    private final Object mDataBytesTransferLock = new Object();
    private final Object mBluetoothBytesTransferLock = new Object();
    private final Object mKernelWakelockLock = new Object();
    private final Object mCpuTimePerClusterFreqLock = new Object();
    private final Object mCpuTimePerUidLock = new Object();
    private final Object mCpuTimePerUidFreqLock = new Object();
    private final Object mCpuActiveTimeLock = new Object();
    private final Object mCpuClusterTimeLock = new Object();
    private final Object mWifiActivityInfoLock = new Object();
    private final Object mModemActivityInfoLock = new Object();
    private final Object mBluetoothActivityInfoLock = new Object();
    private final Object mUwbActivityInfoLock = new Object();
    private final Object mSystemElapsedRealtimeLock = new Object();
    private final Object mSystemUptimeLock = new Object();
    private final Object mProcessMemoryStateLock = new Object();
    private final Object mProcessMemoryHighWaterMarkLock = new Object();
    private final Object mSystemIonHeapSizeLock = new Object();
    private final Object mIonHeapSizeLock = new Object();
    private final Object mProcessSystemIonHeapSizeLock = new Object();
    private final Object mTemperatureLock = new Object();
    private final Object mCooldownDeviceLock = new Object();
    private final Object mBinderCallsStatsLock = new Object();
    private final Object mBinderCallsStatsExceptionsLock = new Object();
    private final Object mLooperStatsLock = new Object();
    private final Object mDiskStatsLock = new Object();
    private final Object mDirectoryUsageLock = new Object();
    private final Object mAppSizeLock = new Object();
    private final Object mCategorySizeLock = new Object();
    private final Object mNumBiometricsEnrolledLock = new Object();
    private final Object mProcStatsLock = new Object();
    private final Object mDiskIoLock = new Object();
    private final Object mPowerProfileLock = new Object();
    private final Object mProcessCpuTimeLock = new Object();
    private final Object mCpuTimePerThreadFreqLock = new Object();
    private final Object mDeviceCalculatedPowerUseLock = new Object();
    private final Object mDebugElapsedClockLock = new Object();
    private final Object mDebugFailingElapsedClockLock = new Object();
    private final Object mBuildInformationLock = new Object();
    private final Object mRoleHolderLock = new Object();
    private final Object mTimeZoneDataInfoLock = new Object();
    private final Object mTimeZoneDetectionInfoLock = new Object();
    private final Object mExternalStorageInfoLock = new Object();
    private final Object mAppsOnExternalStorageInfoLock = new Object();
    private final Object mFaceSettingsLock = new Object();
    private final Object mAppOpsLock = new Object();
    private final Object mRuntimeAppOpAccessMessageLock = new Object();
    private final Object mNotificationRemoteViewsLock = new Object();
    private final Object mDangerousPermissionStateLock = new Object();
    private final Object mHealthHalLock = new Object();
    private final Object mAttributedAppOpsLock = new Object();
    private final Object mSettingsStatsLock = new Object();
    private final Object mInstalledIncrementalPackagesLock = new Object();
    private final Object mKeystoreLock = new Object();

    public StatsPullAtomService(Context context) {
        super(context);
        mContext = context;
    }

    private final class StatsPullAtomServiceInternalImpl extends StatsPullAtomServiceInternal {

        @Override
        public void noteUidProcessState(int uid, int state) {
            if (ENABLE_MOBILE_DATA_STATS_AGGREGATED_PULLER
                    && mAggregatedMobileDataStatsPuller != null) {
                final long elapsedRealtime = SystemClock.elapsedRealtime();
                final long uptime = SystemClock.uptimeMillis();
                mAggregatedMobileDataStatsPuller.noteUidProcessState(uid, state, elapsedRealtime,
                        uptime);
            }
        }
    }

    private native void initializeNativePullers();

    /**
     * Use of this StatsPullAtomCallbackImpl means we avoid one class per tagId, which we would
     * get if we used lambdas.
     *
     * The pull methods are intentionally left to be package private to avoid the creation
     * of synthetic methods to save unnecessary bytecode.
     */
    private class StatsPullAtomCallbackImpl implements StatsManager.StatsPullAtomCallback {
        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_SYSTEM_SERVER)) {
                Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "StatsPull-" + atomTag);
            }
            try {
                switch (atomTag) {
                    case FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_PROC_STATE:
                        if (ENABLE_MOBILE_DATA_STATS_AGGREGATED_PULLER
                                && mAggregatedMobileDataStatsPuller != null) {
                            return mAggregatedMobileDataStatsPuller.pullDataBytesTransfer(data);
                        }
                    case FrameworkStatsLog.WIFI_BYTES_TRANSFER:
                    case FrameworkStatsLog.WIFI_BYTES_TRANSFER_BY_FG_BG:
                    case FrameworkStatsLog.MOBILE_BYTES_TRANSFER:
                    case FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG:
                    case FrameworkStatsLog.PROXY_BYTES_TRANSFER_BY_FG_BG:
                    case FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED:
                    case FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER:
                    case FrameworkStatsLog.OEM_MANAGED_BYTES_TRANSFER:
                        synchronized (mDataBytesTransferLock) {
                            return pullDataBytesTransferLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BLUETOOTH_BYTES_TRANSFER:
                        synchronized (mBluetoothBytesTransferLock) {
                            return pullBluetoothBytesTransferLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.KERNEL_WAKELOCK:
                        synchronized (mKernelWakelockLock) {
                            return pullKernelWakelockLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_TIME_PER_CLUSTER_FREQ:
                        synchronized (mCpuTimePerClusterFreqLock) {
                            return pullCpuTimePerClusterFreqLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_TIME_PER_UID:
                        synchronized (mCpuTimePerUidLock) {
                            return pullCpuTimePerUidLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_CYCLES_PER_UID_CLUSTER:
                        // Use the same lock as CPU_TIME_PER_UID_FREQ because data is pulled from
                        // the same source.
                        synchronized (mCpuTimePerUidFreqLock) {
                            return pullCpuCyclesPerUidClusterLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_TIME_PER_UID_FREQ:
                        synchronized (mCpuTimePerUidFreqLock) {
                            return pullCpuTimePerUidFreqLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_CYCLES_PER_THREAD_GROUP_CLUSTER:
                        return pullCpuCyclesPerThreadGroupCluster(atomTag, data);
                    case FrameworkStatsLog.CPU_ACTIVE_TIME:
                        synchronized (mCpuActiveTimeLock) {
                            return pullCpuActiveTimeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_CLUSTER_TIME:
                        synchronized (mCpuClusterTimeLock) {
                            return pullCpuClusterTimeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.WIFI_ACTIVITY_INFO:
                        synchronized (mWifiActivityInfoLock) {
                            return pullWifiActivityInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.MODEM_ACTIVITY_INFO:
                        synchronized (mModemActivityInfoLock) {
                            return pullModemActivityInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BLUETOOTH_ACTIVITY_INFO:
                        synchronized (mBluetoothActivityInfoLock) {
                            return pullBluetoothActivityInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.UWB_ACTIVITY_INFO:
                        synchronized (mUwbActivityInfoLock) {
                            return pullUwbActivityInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.SYSTEM_ELAPSED_REALTIME:
                        synchronized (mSystemElapsedRealtimeLock) {
                            return pullSystemElapsedRealtimeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.SYSTEM_UPTIME:
                        synchronized (mSystemUptimeLock) {
                            return pullSystemUptimeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_MEMORY_STATE:
                        synchronized (mProcessMemoryStateLock) {
                            return pullProcessMemoryStateLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_MEMORY_HIGH_WATER_MARK:
                        synchronized (mProcessMemoryHighWaterMarkLock) {
                            return pullProcessMemoryHighWaterMarkLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_MEMORY_SNAPSHOT:
                        return pullProcessMemorySnapshot(atomTag, data);
                    case FrameworkStatsLog.SYSTEM_ION_HEAP_SIZE:
                        synchronized (mSystemIonHeapSizeLock) {
                            return pullSystemIonHeapSizeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.ION_HEAP_SIZE:
                        synchronized (mIonHeapSizeLock) {
                            return pullIonHeapSizeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_SYSTEM_ION_HEAP_SIZE:
                        synchronized (mProcessSystemIonHeapSizeLock) {
                            return pullProcessSystemIonHeapSizeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_DMABUF_MEMORY:
                        return pullProcessDmabufMemory(atomTag, data);
                    case FrameworkStatsLog.SYSTEM_MEMORY:
                        return pullSystemMemory(atomTag, data);
                    case FrameworkStatsLog.VMSTAT:
                        return pullVmStat(atomTag, data);
                    case FrameworkStatsLog.TEMPERATURE:
                        synchronized (mTemperatureLock) {
                            return pullTemperatureLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.COOLING_DEVICE:
                        synchronized (mCooldownDeviceLock) {
                            return pullCooldownDeviceLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BINDER_CALLS:
                        synchronized (mBinderCallsStatsLock) {
                            return pullBinderCallsStatsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BINDER_CALLS_EXCEPTIONS:
                        synchronized (mBinderCallsStatsExceptionsLock) {
                            return pullBinderCallsStatsExceptionsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.LOOPER_STATS:
                        synchronized (mLooperStatsLock) {
                            return pullLooperStatsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DISK_STATS:
                        synchronized (mDiskStatsLock) {
                            return pullDiskStatsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DIRECTORY_USAGE:
                        synchronized (mDirectoryUsageLock) {
                            return pullDirectoryUsageLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.APP_SIZE:
                        synchronized (mAppSizeLock) {
                            return pullAppSizeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CATEGORY_SIZE:
                        synchronized (mCategorySizeLock) {
                            return pullCategorySizeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.NUM_FINGERPRINTS_ENROLLED:
                        synchronized (mNumBiometricsEnrolledLock) {
                            return pullNumBiometricsEnrolledLocked(
                                    BiometricsProtoEnums.MODALITY_FINGERPRINT, atomTag, data);
                        }
                    case FrameworkStatsLog.NUM_FACES_ENROLLED:
                        synchronized (mNumBiometricsEnrolledLock) {
                            return pullNumBiometricsEnrolledLocked(
                                    BiometricsProtoEnums.MODALITY_FACE, atomTag, data);
                        }
                    case FrameworkStatsLog.PROC_STATS:
                        synchronized (mProcStatsLock) {
                            return pullProcStatsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROC_STATS_PKG_PROC:
                        synchronized (mProcStatsLock) {
                            return pullProcStatsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_STATE:
                        synchronized (mProcStatsLock) {
                            return pullProcessStateLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_ASSOCIATION:
                        synchronized (mProcStatsLock) {
                            return pullProcessAssociationLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DISK_IO:
                        synchronized (mDiskIoLock) {
                            return pullDiskIOLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.POWER_PROFILE:
                        synchronized (mPowerProfileLock) {
                            return pullPowerProfileLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.PROCESS_CPU_TIME:
                        synchronized (mProcessCpuTimeLock) {
                            return pullProcessCpuTimeLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.CPU_TIME_PER_THREAD_FREQ:
                        synchronized (mCpuTimePerThreadFreqLock) {
                            return pullCpuTimePerThreadFreqLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DEVICE_CALCULATED_POWER_USE:
                        synchronized (mDeviceCalculatedPowerUseLock) {
                            return pullDeviceCalculatedPowerUseLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DEBUG_ELAPSED_CLOCK:
                        synchronized (mDebugElapsedClockLock) {
                            return pullDebugElapsedClockLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DEBUG_FAILING_ELAPSED_CLOCK:
                        synchronized (mDebugFailingElapsedClockLock) {
                            return pullDebugFailingElapsedClockLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BUILD_INFORMATION:
                        synchronized (mBuildInformationLock) {
                            return pullBuildInformationLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.ROLE_HOLDER:
                        synchronized (mRoleHolderLock) {
                            return pullRoleHolderLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.DANGEROUS_PERMISSION_STATE:
                        // fall-through - same call covers two cases
                    case FrameworkStatsLog.DANGEROUS_PERMISSION_STATE_SAMPLED:
                        synchronized (mDangerousPermissionStateLock) {
                            return pullDangerousPermissionStateLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.TIME_ZONE_DATA_INFO:
                        synchronized (mTimeZoneDataInfoLock) {
                            return pullTimeZoneDataInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.TIME_ZONE_DETECTOR_STATE:
                        synchronized (mTimeZoneDetectionInfoLock) {
                            return pullTimeZoneDetectorStateLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.EXTERNAL_STORAGE_INFO:
                        synchronized (mExternalStorageInfoLock) {
                            return pullExternalStorageInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.APPS_ON_EXTERNAL_STORAGE_INFO:
                        synchronized (mAppsOnExternalStorageInfoLock) {
                            return pullAppsOnExternalStorageInfoLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.FACE_SETTINGS:
                        synchronized (mFaceSettingsLock) {
                            return pullFaceSettingsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.APP_OPS:
                        synchronized (mAppOpsLock) {
                            return pullAppOpsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.RUNTIME_APP_OP_ACCESS:
                        synchronized (mRuntimeAppOpAccessMessageLock) {
                            return pullRuntimeAppOpAccessMessageLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.NOTIFICATION_REMOTE_VIEWS:
                        synchronized (mNotificationRemoteViewsLock) {
                            return pullNotificationRemoteViewsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.BATTERY_LEVEL:
                    case FrameworkStatsLog.REMAINING_BATTERY_CAPACITY:
                    case FrameworkStatsLog.FULL_BATTERY_CAPACITY:
                    case FrameworkStatsLog.BATTERY_VOLTAGE:
                    case FrameworkStatsLog.BATTERY_CYCLE_COUNT:
                    case FrameworkStatsLog.BATTERY_HEALTH:
                        synchronized (mHealthHalLock) {
                            return pullHealthHalLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.ATTRIBUTED_APP_OPS:
                        synchronized (mAttributedAppOpsLock) {
                            return pullAttributedAppOpsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.SETTING_SNAPSHOT:
                        synchronized (mSettingsStatsLock) {
                            return pullSettingsStatsLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.INSTALLED_INCREMENTAL_PACKAGE:
                        synchronized (mInstalledIncrementalPackagesLock) {
                            return pullInstalledIncrementalPackagesLocked(atomTag, data);
                        }
                    case FrameworkStatsLog.KEYSTORE2_STORAGE_STATS:
                    case FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_GENERAL_INFO:
                    case FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_AUTH_INFO:
                    case FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_PURPOSE_AND_MODES_INFO:
                    case FrameworkStatsLog.KEYSTORE2_ATOM_WITH_OVERFLOW:
                    case FrameworkStatsLog.KEYSTORE2_KEY_OPERATION_WITH_PURPOSE_AND_MODES_INFO:
                    case FrameworkStatsLog.KEYSTORE2_KEY_OPERATION_WITH_GENERAL_INFO:
                    case FrameworkStatsLog.RKP_ERROR_STATS:
                    case FrameworkStatsLog.KEYSTORE2_CRASH_STATS:
                        return pullKeystoreAtoms(atomTag, data);
                    case FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_STATS:
                        return pullAccessibilityShortcutStatsLocked(data);
                    case FrameworkStatsLog.ACCESSIBILITY_FLOATING_MENU_STATS:
                        return pullAccessibilityFloatingMenuStatsLocked(atomTag, data);
                    case FrameworkStatsLog.MEDIA_CAPABILITIES:
                        return pullMediaCapabilitiesStats(atomTag, data);
                    case FrameworkStatsLog.PINNED_FILE_SIZES_PER_PACKAGE:
                        return pullSystemServerPinnerStats(atomTag, data);
                    case FrameworkStatsLog.PENDING_INTENTS_PER_PACKAGE:
                        return pullPendingIntentsPerPackage(atomTag, data);
                    case FrameworkStatsLog.HDR_CAPABILITIES:
                        return pullHdrCapabilities(atomTag, data);
                    case FrameworkStatsLog.CACHED_APPS_HIGH_WATERMARK:
                        return pullCachedAppsHighWatermark(atomTag, data);
                    default:
                        throw new UnsupportedOperationException("Unknown tagId=" + atomTag);
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
            }
        }
    }

    @Override
    public void onStart() {
        if (ENABLE_MOBILE_DATA_STATS_AGGREGATED_PULLER) {
            LocalServices.addService(StatsPullAtomServiceInternal.class,
                    new StatsPullAtomServiceInternalImpl());
        }
    }

    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            BackgroundThread.getHandler().post(() -> {
                initializeNativePullers(); // Initialize pullers that need JNI.
                initializePullersState();
                registerPullers();
                registerEventListeners();
            });
        } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            initNetworkStatsManager();
            BackgroundThread.getHandler().post(() -> {
                // Network stats related pullers can only be initialized after service is ready.
                initAndRegisterNetworkStatsPullers();
                // For services that are not ready at boot phase PHASE_SYSTEM_SERVICES_READY
                initAndRegisterDeferredPullers();
            });
        }
    }

    // We do not hold locks within this function because it is guaranteed to be called before the
    // pullers are ever run, as the pullers are not yet registered with statsd.
    void initializePullersState() {
        // Get Context Managers
        mStatsManager = (StatsManager) mContext.getSystemService(Context.STATS_MANAGER);
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mTelephony = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mSubscriptionManager = (SubscriptionManager)
                mContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mStatsSubscriptionsListener = new StatsSubscriptionsListener(mSubscriptionManager);
        mStorageManager = (StorageManager) mContext.getSystemService(StorageManager.class);

        // Initialize DiskIO
        mStoragedUidIoStatsReader = new StoragedUidIoStatsReader();

        // Initialize PROC_STATS
        mBaseDir = new File(SystemServiceManager.ensureSystemDir(), "stats_pull");
        mBaseDir.mkdirs();

        // Disables throttler on CPU time readers.
        mCpuUidUserSysTimeReader = new KernelCpuUidUserSysTimeReader(false);
        mCpuUidFreqTimeReader = new KernelCpuUidFreqTimeReader(false);
        mCpuUidActiveTimeReader = new KernelCpuUidActiveTimeReader(false);
        mCpuUidClusterTimeReader = new KernelCpuUidClusterTimeReader(false);

        // Initialize state for KERNEL_WAKELOCK
        mKernelWakelockReader = new KernelWakelockReader();
        mTmpWakelockStats = new KernelWakelockStats();

        // Used for CPU_TIME_PER_THREAD_FREQ
        mKernelCpuThreadReader =
                KernelCpuThreadReaderSettingsObserver.getSettingsModifiedReader(mContext);

        // Initialize HealthService
        try {
            mHealthService = HealthServiceWrapper.create(null);
        } catch (RemoteException | NoSuchElementException e) {
            Slog.e(TAG, "failed to initialize healthHalWrapper");
        }

        // Initialize list of AppOps related to DangerousPermissions
        PackageManager pm = mContext.getPackageManager();
        for (int op = 0; op < AppOpsManager._NUM_OP; op++) {
            String perm = AppOpsManager.opToPermission(op);
            if (perm == null) {
                continue;
            } else {
                PermissionInfo permInfo;
                try {
                    permInfo = pm.getPermissionInfo(perm, 0);
                    if (permInfo.getProtection() == PROTECTION_DANGEROUS) {
                        mDangerousAppOpsList.add(op);
                    }
                } catch (PackageManager.NameNotFoundException exception) {
                    continue;
                }
            }
        }

        mSurfaceFlingerProcessCpuThreadReader =
                new SelectedProcessCpuThreadReader("/system/bin/surfaceflinger");

        getIKeystoreMetricsService();
    }

    void registerEventListeners() {
        final ConnectivityManager connectivityManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        // Default NetworkRequest should cover all transport types.
        final NetworkRequest request = new NetworkRequest.Builder().build();
        connectivityManager.registerNetworkCallback(request, new ConnectivityStatsCallback());

        // Enable push notifications of throttling from vendor thermal
        // management subsystem via thermalservice.
        IThermalService thermalService = getIThermalService();
        if (thermalService != null) {
            try {
                thermalService.registerThermalEventListener(new ThermalEventListener());
                Slog.i(TAG, "register thermal listener successfully");
            } catch (RemoteException e) {
                Slog.i(TAG, "failed to register thermal listener");
            }
        }
    }

    void registerPullers() {
        if (DEBUG) {
            Slog.d(TAG, "Registering pullers with statsd");
        }
        mStatsCallbackImpl = new StatsPullAtomCallbackImpl();
        registerBluetoothBytesTransfer();
        registerKernelWakelock();
        registerCpuTimePerClusterFreq();
        registerCpuTimePerUid();
        registerCpuCyclesPerUidCluster();
        registerCpuTimePerUidFreq();
        registerCpuCyclesPerThreadGroupCluster();
        registerCpuActiveTime();
        registerCpuClusterTime();
        registerWifiActivityInfo();
        registerModemActivityInfo();
        registerBluetoothActivityInfo();
        registerSystemElapsedRealtime();
        registerSystemUptime();
        registerProcessMemoryState();
        registerProcessMemoryHighWaterMark();
        registerProcessMemorySnapshot();
        registerSystemIonHeapSize();
        registerIonHeapSize();
        registerProcessSystemIonHeapSize();
        registerSystemMemory();
        registerProcessDmabufMemory();
        registerVmStat();
        registerTemperature();
        registerCoolingDevice();
        registerBinderCallsStats();
        registerBinderCallsStatsExceptions();
        registerLooperStats();
        registerDiskStats();
        registerDirectoryUsage();
        registerAppSize();
        registerCategorySize();
        registerNumFingerprintsEnrolled();
        registerNumFacesEnrolled();
        registerProcStats();
        registerProcStatsPkgProc();
        registerProcessState();
        registerProcessAssociation();
        registerDiskIO();
        registerPowerProfile();
        registerProcessCpuTime();
        registerCpuTimePerThreadFreq();
        registerDeviceCalculatedPowerUse();
        registerDebugElapsedClock();
        registerDebugFailingElapsedClock();
        registerBuildInformation();
        registerRoleHolder();
        registerTimeZoneDataInfo();
        registerTimeZoneDetectorState();
        registerExternalStorageInfo();
        registerAppsOnExternalStorageInfo();
        registerFaceSettings();
        registerAppOps();
        registerAttributedAppOps();
        registerRuntimeAppOpAccessMessage();
        registerNotificationRemoteViews();
        registerDangerousPermissionState();
        registerDangerousPermissionStateSampled();
        registerBatteryLevel();
        registerRemainingBatteryCapacity();
        registerFullBatteryCapacity();
        registerBatteryVoltage();
        registerBatteryCycleCount();
        registerBatteryHealth();
        registerSettingsStats();
        registerInstalledIncrementalPackages();
        registerKeystoreStorageStats();
        registerKeystoreKeyCreationWithGeneralInfo();
        registerKeystoreKeyCreationWithAuthInfo();
        registerKeystoreKeyCreationWithPurposeModesInfo();
        registerKeystoreAtomWithOverflow();
        registerKeystoreKeyOperationWithPurposeAndModesInfo();
        registerKeystoreKeyOperationWithGeneralInfo();
        registerRkpErrorStats();
        registerKeystoreCrashStats();
        registerAccessibilityShortcutStats();
        registerAccessibilityFloatingMenuStats();
        registerMediaCapabilitiesStats();
        registerPendingIntentsPerPackagePuller();
        registerPinnerServiceStats();
        registerHdrCapabilitiesPuller();
        registerCachedAppsHighWatermarkPuller();
    }

    private void initMobileDataStatsPuller() {
        if (DEBUG) {
            Slog.d(TAG,
                    "ENABLE_MOBILE_DATA_STATS_AGGREGATED_PULLER = "
                            + ENABLE_MOBILE_DATA_STATS_AGGREGATED_PULLER);
        }
        if (ENABLE_MOBILE_DATA_STATS_AGGREGATED_PULLER) {
            mAggregatedMobileDataStatsPuller =
                    new AggregatedMobileDataStatsPuller(
                            mContext.getSystemService(NetworkStatsManager.class));
        }
    }

    /**
     * Calling getNetworkStatsManager() before PHASE_THIRD_PARTY_APPS_CAN_START is unexpected
     * Callers use before PHASE_THIRD_PARTY_APPS_CAN_START stage is not legit
     */
    @NonNull
    private NetworkStatsManager getNetworkStatsManager() {
        if (mNetworkStatsManager == null) {
            throw new IllegalStateException("NetworkStatsManager is not ready");
        }
        return mNetworkStatsManager;
    }

    private void initNetworkStatsManager() {
        mNetworkStatsManager = mContext.getSystemService(NetworkStatsManager.class);
    }

    private void initAndRegisterNetworkStatsPullers() {
        if (DEBUG) {
            Slog.d(TAG, "Registering NetworkStats pullers with statsd");
        }

        boolean canQueryTypeProxy = canQueryNetworkStatsForTypeProxy();

        // Initialize NetworkStats baselines.
        synchronized (mDataBytesTransferLock) {
            mNetworkStatsBaselines.addAll(
                    collectNetworkStatsSnapshotForAtomLocked(
                            FrameworkStatsLog.WIFI_BYTES_TRANSFER));
            mNetworkStatsBaselines.addAll(
                    collectNetworkStatsSnapshotForAtomLocked(
                            FrameworkStatsLog.WIFI_BYTES_TRANSFER_BY_FG_BG));
            mNetworkStatsBaselines.addAll(
                    collectNetworkStatsSnapshotForAtomLocked(
                            FrameworkStatsLog.MOBILE_BYTES_TRANSFER));
            mNetworkStatsBaselines.addAll(collectNetworkStatsSnapshotForAtomLocked(
                    FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG));
            mNetworkStatsBaselines.addAll(collectNetworkStatsSnapshotForAtomLocked(
                    FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED));
            mNetworkStatsBaselines.addAll(
                    collectNetworkStatsSnapshotForAtomLocked(
                            FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER));
            mNetworkStatsBaselines.addAll(
                    collectNetworkStatsSnapshotForAtomLocked(
                            FrameworkStatsLog.OEM_MANAGED_BYTES_TRANSFER));
            if (canQueryTypeProxy) {
                mNetworkStatsBaselines.addAll(collectNetworkStatsSnapshotForAtomLocked(
                        FrameworkStatsLog.PROXY_BYTES_TRANSFER_BY_FG_BG));
            }
        }

        // Listen to subscription changes to record historical subscriptions that activated before
        // pulling, this is used by {@code DATA_USAGE_BYTES_TRANSFER}.
        mSubscriptionManager.addOnSubscriptionsChangedListener(
                BackgroundThread.getExecutor(), mStatsSubscriptionsListener);

        registerWifiBytesTransfer();
        registerWifiBytesTransferBackground();
        registerMobileBytesTransfer();
        registerMobileBytesTransferBackground();
        if (ENABLE_MOBILE_DATA_STATS_AGGREGATED_PULLER) {
            initMobileDataStatsPuller();
            registerMobileBytesTransferByProcState();
        }
        registerBytesTransferByTagAndMetered();
        registerDataUsageBytesTransfer();
        registerOemManagedBytesTransfer();
        if (canQueryTypeProxy) {
            registerProxyBytesTransferBackground();
        }
    }

    private void registerMobileBytesTransferByProcState() {
        final int tagId = FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_PROC_STATE;
        PullAtomMetadata metadata =
                new PullAtomMetadata.Builder().setAdditiveFields(new int[] {3, 4, 5, 6}).build();
        mStatsManager.setPullAtomCallback(tagId, metadata, DIRECT_EXECUTOR, mStatsCallbackImpl);
    }

    private void initAndRegisterDeferredPullers() {
        mUwbManager = mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_UWB)
            ? mContext.getSystemService(UwbManager.class) : null;

        registerUwbActivityInfo();
    }

    private IThermalService getIThermalService() {
        synchronized (mThermalLock) {
            if (mThermalService == null) {
                mThermalService = IThermalService.Stub.asInterface(
                        ServiceManager.getService(Context.THERMAL_SERVICE));
                if (mThermalService != null) {
                    try {
                        mThermalService.asBinder().linkToDeath(() -> {
                            synchronized (mThermalLock) {
                                mThermalService = null;
                            }
                        }, /* flags */ 0);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "linkToDeath with thermalService failed", e);
                        mThermalService = null;
                    }
                }
            }
            return mThermalService;
        }
    }

    private IKeystoreMetrics getIKeystoreMetricsService() {
        synchronized (mKeystoreLock) {
            if (mIKeystoreMetrics == null) {
                mIKeystoreMetrics = IKeystoreMetrics.Stub.asInterface(
                        ServiceManager.getService("android.security.metrics"));
                if (mIKeystoreMetrics != null) {
                    try {
                        mIKeystoreMetrics.asBinder().linkToDeath(() -> {
                            synchronized (mKeystoreLock) {
                                mIKeystoreMetrics = null;
                            }
                        }, /* flags */ 0);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "linkToDeath with IKeystoreMetrics failed", e);
                        mIKeystoreMetrics = null;
                    }
                }
            }
            return mIKeystoreMetrics;
        }
    }

    private IStoraged getIStoragedService() {
        synchronized (mStoragedLock) {
            if (mStorageService == null) {
                mStorageService = IStoraged.Stub.asInterface(
                        ServiceManager.getService("storaged"));
            }
            if (mStorageService != null) {
                try {
                    mStorageService.asBinder().linkToDeath(() -> {
                        synchronized (mStoragedLock) {
                            mStorageService = null;
                        }
                    }, /* flags */ 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "linkToDeath with storagedService failed", e);
                    mStorageService = null;
                }
            }
        }
        return mStorageService;
    }

    private INotificationManager getINotificationManagerService() {
        synchronized (mNotificationStatsLock) {
            if (mNotificationManagerService == null) {
                mNotificationManagerService = INotificationManager.Stub.asInterface(
                        ServiceManager.getService(Context.NOTIFICATION_SERVICE));
            }
            if (mNotificationManagerService != null) {
                try {
                    mNotificationManagerService.asBinder().linkToDeath(() -> {
                        synchronized (mNotificationStatsLock) {
                            mNotificationManagerService = null;
                        }
                    }, /* flags */ 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "linkToDeath with notificationManager failed", e);
                    mNotificationManagerService = null;
                }
            }
        }
        return mNotificationManagerService;
    }

    private IProcessStats getIProcessStatsService() {
        synchronized (mProcStatsLock) {
            if (mProcessStatsService == null) {
                mProcessStatsService = IProcessStats.Stub.asInterface(
                        ServiceManager.getService(ProcessStats.SERVICE_NAME));
            }
            if (mProcessStatsService != null) {
                try {
                    mProcessStatsService.asBinder().linkToDeath(() -> {
                        synchronized (mProcStatsLock) {
                            mProcessStatsService = null;
                        }
                    }, /* flags */ 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "linkToDeath with ProcessStats failed", e);
                    mProcessStatsService = null;
                }
            }
        }
        return mProcessStatsService;
    }

    private void registerWifiBytesTransfer() {
        int tagId = FrameworkStatsLog.WIFI_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{2, 3, 4, 5})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    @GuardedBy("mDataBytesTransferLock")
    @NonNull
    private List<NetworkStatsExt> collectNetworkStatsSnapshotForAtomLocked(int atomTag) {
        List<NetworkStatsExt> ret = new ArrayList<>();
        switch (atomTag) {
            case FrameworkStatsLog.WIFI_BYTES_TRANSFER: {
                final NetworkStats stats = getUidNetworkStatsSnapshotForTransportLocked(
                        TRANSPORT_WIFI);
                if (stats != null) {
                    ret.add(new NetworkStatsExt(sliceNetworkStatsByUid(stats),
                            new int[]{TRANSPORT_WIFI}, /*slicedByFgbg=*/false));
                }
                break;
            }
            case FrameworkStatsLog.WIFI_BYTES_TRANSFER_BY_FG_BG: {
                final NetworkStats stats = getUidNetworkStatsSnapshotForTransportLocked(
                        TRANSPORT_WIFI);
                if (stats != null) {
                    ret.add(new NetworkStatsExt(sliceNetworkStatsByUidAndFgbg(stats),
                            new int[]{TRANSPORT_WIFI}, /*slicedByFgbg=*/true));
                }
                break;
            }
            case FrameworkStatsLog.MOBILE_BYTES_TRANSFER: {
                final NetworkStats stats =
                        getUidNetworkStatsSnapshotForTransportLocked(TRANSPORT_CELLULAR);
                if (stats != null) {
                    ret.add(new NetworkStatsExt(sliceNetworkStatsByUid(stats),
                            new int[]{TRANSPORT_CELLULAR}, /*slicedByFgbg=*/false));
                }
                break;
            }
            case FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG: {
                final NetworkStats stats =
                        getUidNetworkStatsSnapshotForTransportLocked(TRANSPORT_CELLULAR);
                if (stats != null) {
                    ret.add(new NetworkStatsExt(sliceNetworkStatsByUidAndFgbg(stats),
                            new int[]{TRANSPORT_CELLULAR}, /*slicedByFgbg=*/true));
                }
                break;
            }
            case FrameworkStatsLog.PROXY_BYTES_TRANSFER_BY_FG_BG: {
                final NetworkStats stats = getUidNetworkStatsSnapshotForTemplateLocked(
                        new NetworkTemplate.Builder(MATCH_PROXY).build(),  /*includeTags=*/false);
                if (stats != null) {
                    ret.add(new NetworkStatsExt(sliceNetworkStatsByUidAndFgbg(stats),
                            new int[]{TRANSPORT_BLUETOOTH},
                            /*slicedByFgbg=*/true, /*slicedByTag=*/false,
                            /*slicedByMetered=*/false, TelephonyManager.NETWORK_TYPE_UNKNOWN,
                            /*subInfo=*/null, OEM_MANAGED_ALL, /*isTypeProxy=*/true));
                }
                break;
            }
            case FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED: {
                final NetworkStats wifiStats = getUidNetworkStatsSnapshotForTemplateLocked(
                        new NetworkTemplate.Builder(MATCH_WIFI).build(), /*includeTags=*/true);
                final NetworkStats cellularStats = getUidNetworkStatsSnapshotForTemplateLocked(
                        new NetworkTemplate.Builder(MATCH_MOBILE)
                                .setMeteredness(METERED_YES).build(), /*includeTags=*/true);
                if (wifiStats != null && cellularStats != null) {
                    final NetworkStats stats = wifiStats.add(cellularStats);
                    ret.add(new NetworkStatsExt(sliceNetworkStatsByUidTagAndMetered(stats),
                            new int[]{TRANSPORT_WIFI, TRANSPORT_CELLULAR},
                            /*slicedByFgbg=*/false, /*slicedByTag=*/true,
                            /*slicedByMetered=*/true, TelephonyManager.NETWORK_TYPE_UNKNOWN,
                            /*subInfo=*/null, OEM_MANAGED_ALL, /*isTypeProxy=*/false));
                }
                break;
            }
            case FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER: {
                for (final SubInfo subInfo : mHistoricalSubs) {
                    ret.addAll(getDataUsageBytesTransferSnapshotForSubLocked(subInfo));
                }
                break;
            }
            case FrameworkStatsLog.OEM_MANAGED_BYTES_TRANSFER: {
                ret.addAll(getDataUsageBytesTransferSnapshotForOemManagedLocked());
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown atomTag " + atomTag);
        }
        return ret;
    }

    @GuardedBy("mDataBytesTransferLock")
    private int pullDataBytesTransferLocked(int atomTag, @NonNull List<StatsEvent> pulledData) {
        final List<NetworkStatsExt> current = collectNetworkStatsSnapshotForAtomLocked(atomTag);

        if (current == null) {
            Slog.e(TAG, "current snapshot is null for " + atomTag + ", return.");
            return StatsManager.PULL_SKIP;
        }

        for (final NetworkStatsExt item : current) {
            final NetworkStatsExt baseline = CollectionUtils.find(mNetworkStatsBaselines,
                    it -> it.hasSameSlicing(item));

            // No matched baseline indicates error has occurred during initialization stage,
            // skip reporting anything since the snapshot is invalid.
            if (baseline == null) {
                Slog.e(TAG, "baseline is null for " + atomTag + ", return.");
                return StatsManager.PULL_SKIP;
            }

            final NetworkStatsExt diff = new NetworkStatsExt(
                    removeEmptyEntries(item.stats.subtract(baseline.stats)), item.transports,
                    item.slicedByFgbg, item.slicedByTag, item.slicedByMetered, item.ratType,
                    item.subInfo, item.oemManaged, item.isTypeProxy);

            // If no diff, skip.
            if (!diff.stats.iterator().hasNext()) continue;

            switch (atomTag) {
                case FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED:
                    addBytesTransferByTagAndMeteredAtoms(diff, pulledData);
                    break;
                case FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER:
                    addDataUsageBytesTransferAtoms(diff, pulledData);
                    break;
                case FrameworkStatsLog.OEM_MANAGED_BYTES_TRANSFER:
                    addOemDataUsageBytesTransferAtoms(diff, pulledData);
                    break;
                default:
                    addNetworkStats(atomTag, pulledData, diff);
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    @NonNull
    private static NetworkStats removeEmptyEntries(NetworkStats stats) {
        final ArrayList<NetworkStats.Entry> entries = new ArrayList<>();
        for (NetworkStats.Entry e : stats) {
            if (e.getRxBytes() != 0 || e.getRxPackets() != 0 || e.getTxBytes() != 0
                    || e.getTxPackets() != 0 || e.getOperations() != 0) {
                entries.add(e);
            }
        }
        if (isAddEntriesSupported()) {
            return new NetworkStats(0, entries.size()).addEntries(entries);
        } else {
            NetworkStats outputStats = new NetworkStats(0L, 1);
            for (NetworkStats.Entry e : entries) {
                outputStats = outputStats.addEntry(e);
            }
            return outputStats;
        }
    }

    private void addNetworkStats(int atomTag, @NonNull List<StatsEvent> ret,
            @NonNull NetworkStatsExt statsExt) {
        for (NetworkStats.Entry entry : statsExt.stats) {
            StatsEvent statsEvent;
            if (statsExt.slicedByFgbg) {
                // MobileBytesTransferByFgBg atom or WifiBytesTransferByFgBg atom.
                statsEvent = FrameworkStatsLog.buildStatsEvent(
                        atomTag, entry.getUid(),
                        (entry.getSet() > 0), entry.getRxBytes(), entry.getRxPackets(),
                        entry.getTxBytes(), entry.getTxPackets());
            } else {
                // MobileBytesTransfer atom or WifiBytesTransfer atom.
                statsEvent = FrameworkStatsLog.buildStatsEvent(
                        atomTag, entry.getUid(), entry.getRxBytes(),
                        entry.getRxPackets(), entry.getTxBytes(), entry.getTxPackets());
            }
            ret.add(statsEvent);
        }
    }

    private void addBytesTransferByTagAndMeteredAtoms(@NonNull NetworkStatsExt statsExt,
            @NonNull List<StatsEvent> pulledData) {
        // Workaround for 5G NSA mode, see {@link NetworkStatsManager#NETWORK_TYPE_5G_NSA}.
        // 5G NSA mode means the primary cell is LTE with a secondary connection to an
        // NR cell. To mitigate risk, NetworkStats is currently storing this state as
        // a fake RAT type rather than storing the boolean separately.
        final boolean is5GNsa = statsExt.ratType == NetworkStatsManager.NETWORK_TYPE_5G_NSA;

        for (NetworkStats.Entry entry : statsExt.stats) {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED, entry.getUid(),
                    entry.getMetered() == NetworkStats.METERED_YES, entry.getTag(),
                    entry.getRxBytes(), entry.getRxPackets(), entry.getTxBytes(),
                    entry.getTxPackets(),
                    is5GNsa ? TelephonyManager.NETWORK_TYPE_LTE : statsExt.ratType));
        }
    }

    private void addDataUsageBytesTransferAtoms(@NonNull NetworkStatsExt statsExt,
            @NonNull List<StatsEvent> pulledData) {

        // Workaround for 5G NSA mode, see {@link NetworkStatsManager#NETWORK_TYPE_5G_NSA}.
        // 5G NSA mode means the primary cell is LTE with a secondary connection to an
        // NR cell. To mitigate risk, NetworkStats is currently storing this state as
        // a fake RAT type rather than storing the boolean separately.
        final boolean is5GNsa = statsExt.ratType == NetworkStatsManager.NETWORK_TYPE_5G_NSA;
        // Report NR connected in 5G non-standalone mode, or if the RAT type is NR to begin with.
        final boolean isNR = is5GNsa || statsExt.ratType == TelephonyManager.NETWORK_TYPE_NR;

        for (NetworkStats.Entry entry : statsExt.stats) {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER,
                    entry.getSet(), entry.getRxBytes(), entry.getRxPackets(),
                    entry.getTxBytes(), entry.getTxPackets(),
                    is5GNsa ? TelephonyManager.NETWORK_TYPE_LTE : statsExt.ratType,
                    // Fill information about subscription, these cannot be null since invalid data
                    // would be filtered when adding into subInfo list.
                    statsExt.subInfo.mcc, statsExt.subInfo.mnc, statsExt.subInfo.carrierId,
                    statsExt.subInfo.isOpportunistic
                            ? DATA_USAGE_BYTES_TRANSFER__OPPORTUNISTIC_DATA_SUB__OPPORTUNISTIC
                            : DATA_USAGE_BYTES_TRANSFER__OPPORTUNISTIC_DATA_SUB__NOT_OPPORTUNISTIC,
                    isNR));
        }
    }

    private void addOemDataUsageBytesTransferAtoms(@NonNull NetworkStatsExt statsExt,
            @NonNull List<StatsEvent> pulledData) {
        final int oemManaged = statsExt.oemManaged;
        for (final int transport : statsExt.transports) {
            for (NetworkStats.Entry entry : statsExt.stats) {
                pulledData.add(FrameworkStatsLog.buildStatsEvent(
                        FrameworkStatsLog.OEM_MANAGED_BYTES_TRANSFER, entry.getUid(),
                        (entry.getSet() > 0), oemManaged, transport, entry.getRxBytes(),
                        entry.getRxPackets(), entry.getTxBytes(), entry.getTxPackets()));
            }
        }
    }

    @GuardedBy("mDataBytesTransferLock")
    @NonNull
    private List<NetworkStatsExt> getDataUsageBytesTransferSnapshotForOemManagedLocked() {
        final List<Pair<Integer, Integer>> matchRulesAndTransports = List.of(
                new Pair(MATCH_ETHERNET, TRANSPORT_ETHERNET),
                new Pair(MATCH_MOBILE, TRANSPORT_CELLULAR),
                new Pair(MATCH_WIFI, TRANSPORT_WIFI)
        );
        final int[] oemManagedTypes = new int[]{OEM_MANAGED_PAID | OEM_MANAGED_PRIVATE,
                OEM_MANAGED_PAID, OEM_MANAGED_PRIVATE};

        final List<NetworkStatsExt> ret = new ArrayList<>();

        for (Pair<Integer, Integer> ruleAndTransport : matchRulesAndTransports) {
            final Integer matchRule = ruleAndTransport.first;
            for (final int oemManaged : oemManagedTypes) {
                // Subscriber Ids and Wifi Network Keys will not be set since the purpose is to
                // slice statistics of different OEM managed networks among all network types.
                // Thus, specifying networks through their identifiers are not needed.
                final NetworkTemplate template = new NetworkTemplate.Builder(matchRule)
                        .setOemManaged(oemManaged).build();
                final NetworkStats stats = getUidNetworkStatsSnapshotForTemplateLocked(
                        template, false);
                final Integer transport = ruleAndTransport.second;
                if (stats != null) {
                    ret.add(new NetworkStatsExt(sliceNetworkStatsByUidAndFgbg(stats),
                            new int[]{transport}, /*slicedByFgbg=*/true, /*slicedByTag=*/false,
                            /*slicedByMetered=*/false, TelephonyManager.NETWORK_TYPE_UNKNOWN,
                            /*subInfo=*/null, oemManaged, /*isTypeProxy=*/false));
                }
            }
        }

        return ret;
    }

    /**
     * Create a snapshot of NetworkStats for a given transport.
     */
    @GuardedBy("mDataBytesTransferLock")
    @Nullable
    private NetworkStats getUidNetworkStatsSnapshotForTransportLocked(int transport) {
        NetworkTemplate template = null;
        switch (transport) {
            case TRANSPORT_CELLULAR:
                template = new NetworkTemplate.Builder(MATCH_MOBILE)
                        .setMeteredness(METERED_YES).build();
                break;
            case TRANSPORT_WIFI:
                template = new NetworkTemplate.Builder(MATCH_WIFI).build();
                break;
            default:
                Log.wtf(TAG, "Unexpected transport.");
        }
        return getUidNetworkStatsSnapshotForTemplateLocked(template, /*includeTags=*/false);
    }

    /**
     * Check if it is possible to query NetworkStats for TYPE_PROXY. This should only be possible
     * if the build includes r.android.com/2828315
     * @return true if querying for TYPE_PROXY is allowed
     */
    private static boolean canQueryNetworkStatsForTypeProxy() {
        try {
            new NetworkTemplate.Builder(MATCH_PROXY).build();
            return true;
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Querying network stats for TYPE_PROXY is not allowed");
            return false;
        }
    }

    /**
     * Create a snapshot of NetworkStats since boot for the given template, but add 1 bucket
     * duration before boot as a buffer to ensure at least one full bucket will be included.
     * Note that this should be only used to calculate diff since the snapshot might contains
     * some traffic before boot.
     */
    @GuardedBy("mDataBytesTransferLock")
    @Nullable
    private NetworkStats getUidNetworkStatsSnapshotForTemplateLocked(
            @NonNull NetworkTemplate template, boolean includeTags) {
        final long elapsedMillisSinceBoot = SystemClock.elapsedRealtime();
        final long currentTimeInMillis = MICROSECONDS.toMillis(SystemClock.currentTimeMicro());
        final long bucketDuration = Settings.Global.getLong(mContext.getContentResolver(),
                NETSTATS_UID_BUCKET_DURATION, NETSTATS_UID_DEFAULT_BUCKET_DURATION_MS);

        // Set startTime before boot so that NetworkStats includes at least one full bucket.
        // Set endTime in the future so that NetworkStats includes everything in the active bucket.
        final long startTime = currentTimeInMillis - elapsedMillisSinceBoot - bucketDuration;
        final long endTime = currentTimeInMillis + bucketDuration;

        // NetworkStatsManager#forceUpdate updates stats for all networks
        if (applyNetworkStatsPollRateLimit()) {
            // The new way: rate-limit force-polling for all NetworkStats queries
            if (elapsedMillisSinceBoot - mLastNetworkStatsPollTime >= NETSTATS_POLL_RATE_LIMIT_MS) {
                mLastNetworkStatsPollTime = elapsedMillisSinceBoot;
                getNetworkStatsManager().forceUpdate();
            }
        } else {
            // The old way: force-poll only on WiFi queries. Data for other queries can be stale
            // if there was no recent poll beforehand (e.g. for WiFi or scheduled poll)
            if (template.getMatchRule() == MATCH_WIFI && template.getSubscriberIds().isEmpty()) {
                getNetworkStatsManager().forceUpdate();
            }
        }

        final android.app.usage.NetworkStats queryNonTaggedStats =
                getNetworkStatsManager().querySummary(template, startTime, endTime);

        final NetworkStats nonTaggedStats =
                fromPublicNetworkStats(queryNonTaggedStats);
        queryNonTaggedStats.close();
        if (!includeTags) return nonTaggedStats;

        final android.app.usage.NetworkStats queryTaggedStats =
                getNetworkStatsManager().queryTaggedSummary(template, startTime, endTime);
        final NetworkStats taggedStats =
                fromPublicNetworkStats(queryTaggedStats);
        queryTaggedStats.close();
        return nonTaggedStats.add(taggedStats);
    }

    @GuardedBy("mDataBytesTransferLock")
    @NonNull
    private List<NetworkStatsExt> getDataUsageBytesTransferSnapshotForSubLocked(
            @NonNull SubInfo subInfo) {
        final List<NetworkStatsExt> ret = new ArrayList<>();
        for (final int ratType : getAllCollapsedRatTypes()) {
            final NetworkTemplate template =
                    new NetworkTemplate.Builder(MATCH_MOBILE)
                            .setSubscriberIds(Set.of(subInfo.subscriberId))
                            .setRatType(ratType)
                            .setMeteredness(METERED_YES).build();
            final NetworkStats stats =
                    getUidNetworkStatsSnapshotForTemplateLocked(template, /*includeTags=*/false);
            if (stats != null) {
                ret.add(new NetworkStatsExt(sliceNetworkStatsByFgbg(stats),
                        new int[]{TRANSPORT_CELLULAR}, /*slicedByFgbg=*/true,
                        /*slicedByTag=*/false, /*slicedByMetered=*/false, ratType, subInfo,
                        OEM_MANAGED_ALL, /*isTypeProxy=*/false));
            }
        }
        return ret;
    }

    /**
     * Return all supported collapsed RAT types that could be returned by
     * {@link android.app.usage.NetworkStatsManager#getCollapsedRatType(int)}.
     */
    @NonNull
    private static int[] getAllCollapsedRatTypes() {
        final int[] ratTypes = TelephonyManager.getAllNetworkTypes();
        final HashSet<Integer> collapsedRatTypes = new HashSet<>();
        for (final int ratType : ratTypes) {
            collapsedRatTypes.add(NetworkStatsManager.getCollapsedRatType(ratType));
        }
        // Add NETWORK_TYPE_5G_NSA to the returned list since 5G NSA is a virtual RAT type and
        // it is not in TelephonyManager#NETWORK_TYPE_* constants.
        // See {@link NetworkStatsManager#NETWORK_TYPE_5G_NSA}.
        collapsedRatTypes.add(
                NetworkStatsManager.getCollapsedRatType(NetworkStatsManager.NETWORK_TYPE_5G_NSA));
        // Ensure that unknown type is returned.
        collapsedRatTypes.add(TelephonyManager.NETWORK_TYPE_UNKNOWN);
        return com.android.net.module.util.CollectionUtils.toIntArray(collapsedRatTypes);
    }

    @NonNull
    private NetworkStats sliceNetworkStatsByUid(@NonNull NetworkStats stats) {
        return sliceNetworkStats(stats,
                (entry) -> {
                    return new NetworkStats.Entry(null /* IFACE_ALL */, entry.getUid(),
                            NetworkStats.SET_ALL, NetworkStats.TAG_NONE,
                            NetworkStats.METERED_ALL, NetworkStats.ROAMING_ALL,
                            NetworkStats.DEFAULT_NETWORK_ALL,
                            entry.getRxBytes(), entry.getRxPackets(),
                            entry.getTxBytes(), entry.getTxPackets(), 0);
                });
    }

    @NonNull
    private NetworkStats sliceNetworkStatsByFgbg(@NonNull NetworkStats stats) {
        return sliceNetworkStats(stats,
                (entry) -> {
                    return new NetworkStats.Entry(null /* IFACE_ALL */, NetworkStats.UID_ALL,
                            entry.getSet(), NetworkStats.TAG_NONE,
                            NetworkStats.METERED_ALL, NetworkStats.ROAMING_ALL,
                            NetworkStats.DEFAULT_NETWORK_ALL,
                            entry.getRxBytes(), entry.getRxPackets(),
                            entry.getTxBytes(), entry.getTxPackets(), 0);
                });
    }

    @NonNull
    private NetworkStats sliceNetworkStatsByUidAndFgbg(@NonNull NetworkStats stats) {
        return sliceNetworkStats(stats,
                (entry) -> {
                    return new NetworkStats.Entry(null /* IFACE_ALL */, entry.getUid(),
                            entry.getSet(), NetworkStats.TAG_NONE,
                            NetworkStats.METERED_ALL, NetworkStats.ROAMING_ALL,
                            NetworkStats.DEFAULT_NETWORK_ALL,
                            entry.getRxBytes(), entry.getRxPackets(),
                            entry.getTxBytes(), entry.getTxPackets(), 0);
                });
    }

    @NonNull
    private NetworkStats sliceNetworkStatsByUidTagAndMetered(@NonNull NetworkStats stats) {
        return sliceNetworkStats(stats,
                (entry) -> {
                    return new NetworkStats.Entry(null /* IFACE_ALL */, entry.getUid(),
                            NetworkStats.SET_ALL, entry.getTag(),
                            entry.getMetered(), NetworkStats.ROAMING_ALL,
                            NetworkStats.DEFAULT_NETWORK_ALL,
                            entry.getRxBytes(), entry.getRxPackets(),
                            entry.getTxBytes(), entry.getTxPackets(), 0);
                });
    }

    /**
     * Slices NetworkStats along the dimensions specified in the slicer lambda and aggregates over
     * non-sliced dimensions.
     *
     * This function iterates through each NetworkStats.Entry, sets its dimensions equal to the
     * default state (with the presumption that we don't want to slice on anything), and then
     * applies the slicer lambda to allow users to control which dimensions to slice on.
     *
     * @param slicer An operation taking one parameter, NetworkStats.Entry, that should be used to
     *               get the state from entry to replace the default value.
     *               This is useful for slicing by particular dimensions. For example, if we wished
     *               to slice by uid and tag, we could write the following lambda:
     *               (entry) -> {
     *               return new NetworkStats.Entry(null, entry.getUid(),
     *               NetworkStats.SET_ALL, entry.getTag(),
     *               NetworkStats.METERED_ALL, NetworkStats.ROAMING_ALL,
     *               NetworkStats.DEFAULT_NETWORK_ALL,
     *               entry.getRxBytes(), entry.getRxPackets(),
     *               entry.getTxBytes(), entry.getTxPackets(), 0);
     *               }
     * @return new NeworkStats object appropriately sliced
     */
    @NonNull
    private NetworkStats sliceNetworkStats(@NonNull NetworkStats stats,
            @NonNull Function<NetworkStats.Entry, NetworkStats.Entry> slicer) {
        final ArrayList<NetworkStats.Entry> entries = new ArrayList();
        for (NetworkStats.Entry e : stats) {
            entries.add(slicer.apply(e));
        }
        if (isAddEntriesSupported()) {
            return new NetworkStats(0, entries.size()).addEntries(entries);
        } else {
            NetworkStats outputStats = new NetworkStats(0L, 1);
            for (NetworkStats.Entry e : entries) {
                outputStats = outputStats.addEntry(e);
            }
            return outputStats;
        }
    }

    private void registerWifiBytesTransferBackground() {
        int tagId = FrameworkStatsLog.WIFI_BYTES_TRANSFER_BY_FG_BG;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{3, 4, 5, 6})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerMobileBytesTransfer() {
        int tagId = FrameworkStatsLog.MOBILE_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{2, 3, 4, 5})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerMobileBytesTransferBackground() {
        int tagId = FrameworkStatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{3, 4, 5, 6})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerProxyBytesTransferBackground() {
        int tagId = FrameworkStatsLog.PROXY_BYTES_TRANSFER_BY_FG_BG;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{3, 4, 5, 6})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerBytesTransferByTagAndMetered() {
        int tagId = FrameworkStatsLog.BYTES_TRANSFER_BY_TAG_AND_METERED;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{4, 5, 6, 7})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerDataUsageBytesTransfer() {
        int tagId = FrameworkStatsLog.DATA_USAGE_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{2, 3, 4, 5})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerOemManagedBytesTransfer() {
        int tagId = FrameworkStatsLog.OEM_MANAGED_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{5, 6, 7, 8})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerBluetoothBytesTransfer() {
        int tagId = FrameworkStatsLog.BLUETOOTH_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{2, 3})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    /**
     * Helper method to extract the Parcelable controller info from a
     * SynchronousResultReceiver.
     */
    private static <T extends Parcelable> T awaitControllerInfo(
            @Nullable SynchronousResultReceiver receiver) {
        if (receiver == null) {
            return null;
        }

        try {
            final SynchronousResultReceiver.Result result =
                    receiver.awaitResult(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS);
            if (result.bundle != null) {
                // This is the final destination for the Bundle.
                result.bundle.setDefusable(true);

                final T data = result.bundle.getParcelable(RESULT_RECEIVER_CONTROLLER_KEY);
                if (data != null) {
                    return data;
                }
            }
        } catch (TimeoutException e) {
            Slog.w(TAG, "timeout reading " + receiver.getName() + " stats");
        }
        return null;
    }

    private BluetoothActivityEnergyInfo fetchBluetoothData() {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            SynchronousResultReceiver bluetoothReceiver =
                    new SynchronousResultReceiver("bluetooth");
            adapter.requestControllerActivityEnergyInfo(
                    Runnable::run,
                    new BluetoothAdapter.OnBluetoothActivityEnergyInfoCallback() {
                        @Override
                        public void onBluetoothActivityEnergyInfoAvailable(
                                BluetoothActivityEnergyInfo info) {
                            Bundle bundle = new Bundle();
                            bundle.putParcelable(
                                    BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, info);
                            bluetoothReceiver.send(0, bundle);
                        }

                        @Override
                        public void onBluetoothActivityEnergyInfoError(int errorCode) {
                            Slog.w(TAG, "error reading Bluetooth stats: " + errorCode);
                            Bundle bundle = new Bundle();
                            bundle.putParcelable(
                                    BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, null);
                            bluetoothReceiver.send(0, bundle);
                        }
                    }
            );
            return awaitControllerInfo(bluetoothReceiver);
        } else {
            Slog.e(TAG, "Failed to get bluetooth adapter!");
            return null;
        }
    }

    int pullBluetoothBytesTransferLocked(int atomTag, List<StatsEvent> pulledData) {
        BluetoothActivityEnergyInfo info = fetchBluetoothData();
        if (info == null) {
            return StatsManager.PULL_SKIP;
        }
        for (UidTraffic traffic : info.getUidTraffic()) {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    atomTag, traffic.getUid(), traffic.getRxBytes(), traffic.getTxBytes()));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerKernelWakelock() {
        int tagId = FrameworkStatsLog.KERNEL_WAKELOCK;
        mStatsManager.setPullAtomCallback(
                tagId,
                /* PullAtomMetadata */ null,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullKernelWakelockLocked(int atomTag, List<StatsEvent> pulledData) {
        final KernelWakelockStats wakelockStats =
                mKernelWakelockReader.readKernelWakelockStats(mTmpWakelockStats);
        for (Map.Entry<String, KernelWakelockStats.Entry> ent : wakelockStats.entrySet()) {
            String name = ent.getKey();
            KernelWakelockStats.Entry kws = ent.getValue();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    atomTag, name, kws.count, kws.version, kws.totalTimeUs));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuTimePerClusterFreq() {
        if (KernelCpuBpfTracking.isSupported()) {
            int tagId = FrameworkStatsLog.CPU_TIME_PER_CLUSTER_FREQ;
            PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                    .setAdditiveFields(new int[]{3})
                    .build();
            mStatsManager.setPullAtomCallback(
                    tagId,
                    metadata,
                    DIRECT_EXECUTOR,
                    mStatsCallbackImpl
            );
        }
    }

    int pullCpuTimePerClusterFreqLocked(int atomTag, List<StatsEvent> pulledData) {
        int[] freqsClusters = KernelCpuBpfTracking.getFreqsClusters();
        long[] freqs = KernelCpuBpfTracking.getFreqs();
        long[] timesMs = KernelCpuTotalBpfMapReader.read();
        if (timesMs == null) {
            return StatsManager.PULL_SKIP;
        }
        for (int freqIndex = 0; freqIndex < timesMs.length; ++freqIndex) {
            int cluster = freqsClusters[freqIndex];
            int freq = (int) freqs[freqIndex];
            long timeMs = timesMs[freqIndex];
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, cluster, freq, timeMs));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuTimePerUid() {
        int tagId = FrameworkStatsLog.CPU_TIME_PER_UID;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{2, 3})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCpuTimePerUidLocked(int atomTag, List<StatsEvent> pulledData) {
        mCpuUidUserSysTimeReader.readAbsolute((uid, timesUs) -> {
            long userTimeUs = timesUs[0], systemTimeUs = timesUs[1];
            pulledData.add(
                    FrameworkStatsLog.buildStatsEvent(atomTag, uid, userTimeUs, systemTimeUs));
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuCyclesPerUidCluster() {
        // If eBPF tracking is not support, the procfs fallback is used if the kernel knows about
        // CPU frequencies.
        if (KernelCpuBpfTracking.isSupported() || KernelCpuBpfTracking.getClusters() > 0) {
            int tagId = FrameworkStatsLog.CPU_CYCLES_PER_UID_CLUSTER;
            PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                    .setAdditiveFields(new int[]{3, 4, 5})
                    .build();
            mStatsManager.setPullAtomCallback(
                    tagId,
                    metadata,
                    DIRECT_EXECUTOR,
                    mStatsCallbackImpl
            );
        }
    }

    int pullCpuCyclesPerUidClusterLocked(int atomTag, List<StatsEvent> pulledData) {
        PowerProfile powerProfile = new PowerProfile(mContext);
        int[] freqsClusters = KernelCpuBpfTracking.getFreqsClusters();
        int clusters = KernelCpuBpfTracking.getClusters();
        long[] freqs = KernelCpuBpfTracking.getFreqs();
        double[] freqsPowers = new double[freqs.length];
        // Initialize frequency power mapping.
        {
            int freqClusterIndex = 0;
            int lastCluster = -1;
            for (int freqIndex = 0; freqIndex < freqs.length; ++freqIndex, ++freqClusterIndex) {
                int cluster = freqsClusters[freqIndex];
                if (cluster != lastCluster) {
                    freqClusterIndex = 0;
                }
                lastCluster = cluster;

                freqsPowers[freqIndex] =
                        powerProfile.getAveragePowerForCpuCore(cluster, freqClusterIndex);
            }
        }

        // Aggregate 0: mcycles, 1: runtime ms, 2: power profile estimate for the same uids for
        // each cluster.
        SparseArray<double[]> aggregated = new SparseArray<>();
        mCpuUidFreqTimeReader.readAbsolute((uid, cpuFreqTimeMs) -> {
            if (UserHandle.isIsolated(uid)) {
                // Skip individual isolated uids because they are recycled and quickly removed from
                // the underlying data source.
                return;
            } else if (UserHandle.isSharedAppGid(uid)) {
                // All shared app gids are accounted together.
                uid = LAST_SHARED_APPLICATION_GID;
            } else {
                // Everything else is accounted under their base uid.
                uid = UserHandle.getAppId(uid);
            }

            double[] values = aggregated.get(uid);
            if (values == null) {
                values = new double[clusters * CPU_CYCLES_PER_UID_CLUSTER_VALUES];
                aggregated.put(uid, values);
            }

            for (int freqIndex = 0; freqIndex < cpuFreqTimeMs.length; ++freqIndex) {
                int cluster = freqsClusters[freqIndex];
                long timeMs = cpuFreqTimeMs[freqIndex];
                values[cluster * CPU_CYCLES_PER_UID_CLUSTER_VALUES] += freqs[freqIndex] * timeMs;
                values[cluster * CPU_CYCLES_PER_UID_CLUSTER_VALUES + 1] += timeMs;
                values[cluster * CPU_CYCLES_PER_UID_CLUSTER_VALUES + 2] +=
                        freqsPowers[freqIndex] * timeMs;
            }
        });

        int size = aggregated.size();
        for (int i = 0; i < size; ++i) {
            int uid = aggregated.keyAt(i);
            double[] values = aggregated.valueAt(i);
            for (int cluster = 0; cluster < clusters; ++cluster) {
                pulledData.add(FrameworkStatsLog.buildStatsEvent(
                        atomTag, uid, cluster,
                        (long) (values[cluster * CPU_CYCLES_PER_UID_CLUSTER_VALUES] / 1e6),
                        (long) values[cluster * CPU_CYCLES_PER_UID_CLUSTER_VALUES + 1],
                        (long) (values[cluster * CPU_CYCLES_PER_UID_CLUSTER_VALUES + 2] / 1e3)));
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuTimePerUidFreq() {
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        int tagId = FrameworkStatsLog.CPU_TIME_PER_UID_FREQ;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{3})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCpuTimePerUidFreqLocked(int atomTag, List<StatsEvent> pulledData) {
        // Aggregate times for the same uids.
        SparseArray<long[]> aggregated = new SparseArray<>();
        mCpuUidFreqTimeReader.readAbsolute((uid, cpuFreqTimeMs) -> {
            if (UserHandle.isIsolated(uid)) {
                // Skip individual isolated uids because they are recycled and quickly removed from
                // the underlying data source.
                return;
            } else if (UserHandle.isSharedAppGid(uid)) {
                // All shared app gids are accounted together.
                uid = LAST_SHARED_APPLICATION_GID;
            } else {
                // Everything else is accounted under their base uid.
                uid = UserHandle.getAppId(uid);
            }

            long[] aggCpuFreqTimeMs = aggregated.get(uid);
            if (aggCpuFreqTimeMs == null) {
                aggCpuFreqTimeMs = new long[cpuFreqTimeMs.length];
                aggregated.put(uid, aggCpuFreqTimeMs);
            }
            for (int freqIndex = 0; freqIndex < cpuFreqTimeMs.length; ++freqIndex) {
                aggCpuFreqTimeMs[freqIndex] += cpuFreqTimeMs[freqIndex];
            }
        });

        int size = aggregated.size();
        for (int i = 0; i < size; ++i) {
            int uid = aggregated.keyAt(i);
            long[] aggCpuFreqTimeMs = aggregated.valueAt(i);
            for (int freqIndex = 0; freqIndex < aggCpuFreqTimeMs.length; ++freqIndex) {
                if (aggCpuFreqTimeMs[freqIndex] >= MIN_CPU_TIME_PER_UID_FREQ) {
                    pulledData.add(FrameworkStatsLog.buildStatsEvent(
                            atomTag, uid, freqIndex, aggCpuFreqTimeMs[freqIndex]));
                }
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuCyclesPerThreadGroupCluster() {
        if (KernelCpuBpfTracking.isSupported()
                && !com.android.server.power.optimization.Flags.disableSystemServicePowerAttr()) {
            int tagId = FrameworkStatsLog.CPU_CYCLES_PER_THREAD_GROUP_CLUSTER;
            PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                    .setAdditiveFields(new int[]{3, 4})
                    .build();
            mStatsManager.setPullAtomCallback(
                    tagId,
                    metadata,
                    DIRECT_EXECUTOR,
                    mStatsCallbackImpl
            );
        }
    }

    int pullCpuCyclesPerThreadGroupCluster(int atomTag, List<StatsEvent> pulledData) {
        if (com.android.server.power.optimization.Flags.disableSystemServicePowerAttr()) {
            return StatsManager.PULL_SKIP;
        }

        SystemServiceCpuThreadTimes times = LocalServices.getService(BatteryStatsInternal.class)
                .getSystemServiceCpuThreadTimes();
        if (times == null) {
            return StatsManager.PULL_SKIP;
        }

        addCpuCyclesPerThreadGroupClusterAtoms(atomTag, pulledData,
                FrameworkStatsLog.CPU_CYCLES_PER_THREAD_GROUP_CLUSTER__THREAD_GROUP__SYSTEM_SERVER,
                times.threadCpuTimesUs);
        addCpuCyclesPerThreadGroupClusterAtoms(atomTag, pulledData,
                FrameworkStatsLog.CPU_CYCLES_PER_THREAD_GROUP_CLUSTER__THREAD_GROUP__SYSTEM_SERVER_BINDER,
                times.binderThreadCpuTimesUs);

        ProcessCpuUsage surfaceFlingerTimes = mSurfaceFlingerProcessCpuThreadReader.readAbsolute();
        if (surfaceFlingerTimes != null && surfaceFlingerTimes.threadCpuTimesMillis != null) {
            long[] surfaceFlingerTimesUs =
                    new long[surfaceFlingerTimes.threadCpuTimesMillis.length];
            for (int i = 0; i < surfaceFlingerTimesUs.length; ++i) {
                surfaceFlingerTimesUs[i] = surfaceFlingerTimes.threadCpuTimesMillis[i] * 1_000;
            }
            addCpuCyclesPerThreadGroupClusterAtoms(atomTag, pulledData,
                    FrameworkStatsLog.CPU_CYCLES_PER_THREAD_GROUP_CLUSTER__THREAD_GROUP__SURFACE_FLINGER,
                    surfaceFlingerTimesUs);
        }

        return StatsManager.PULL_SUCCESS;
    }

    private static void addCpuCyclesPerThreadGroupClusterAtoms(
            int atomTag, List<StatsEvent> pulledData, int threadGroup, long[] cpuTimesUs) {
        int[] freqsClusters = KernelCpuBpfTracking.getFreqsClusters();
        int clusters = KernelCpuBpfTracking.getClusters();
        long[] freqs = KernelCpuBpfTracking.getFreqs();
        long[] aggregatedCycles = new long[clusters];
        long[] aggregatedTimesUs = new long[clusters];
        for (int i = 0; i < cpuTimesUs.length; ++i) {
            aggregatedCycles[freqsClusters[i]] += freqs[i] * cpuTimesUs[i] / 1_000;
            aggregatedTimesUs[freqsClusters[i]] += cpuTimesUs[i];
        }
        for (int cluster = 0; cluster < clusters; ++cluster) {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    atomTag, threadGroup, cluster, aggregatedCycles[cluster] / 1_000_000L,
                    aggregatedTimesUs[cluster] / 1_000));
        }
    }

    private void registerCpuActiveTime() {
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        int tagId = FrameworkStatsLog.CPU_ACTIVE_TIME;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{2})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCpuActiveTimeLocked(int atomTag, List<StatsEvent> pulledData) {
        mCpuUidActiveTimeReader.readAbsolute((uid, cpuActiveTimesMs) -> {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, uid, cpuActiveTimesMs));
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuClusterTime() {
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        int tagId = FrameworkStatsLog.CPU_CLUSTER_TIME;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{3})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCpuClusterTimeLocked(int atomTag, List<StatsEvent> pulledData) {
        mCpuUidClusterTimeReader.readAbsolute((uid, cpuClusterTimesMs) -> {
            for (int i = 0; i < cpuClusterTimesMs.length; i++) {
                pulledData.add(
                        FrameworkStatsLog.buildStatsEvent(atomTag, uid, i, cpuClusterTimesMs[i]));
            }
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerWifiActivityInfo() {
        int tagId = FrameworkStatsLog.WIFI_ACTIVITY_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullWifiActivityInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        try {
            SynchronousResultReceiver wifiReceiver = new SynchronousResultReceiver("wifi");
            mWifiManager.getWifiActivityEnergyInfoAsync(
                    new Executor() {
                        @Override
                        public void execute(Runnable runnable) {
                            // run the listener on the binder thread, if it was run on the main
                            // thread it would deadlock since we would be waiting on ourselves
                            runnable.run();
                        }
                    },
                    info -> {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, info);
                        wifiReceiver.send(0, bundle);
                    }
            );
            final WifiActivityEnergyInfo wifiInfo = awaitControllerInfo(wifiReceiver);
            if (wifiInfo == null) {
                return StatsManager.PULL_SKIP;
            }
            pulledData.add(
                    FrameworkStatsLog.buildStatsEvent(atomTag, wifiInfo.getTimeSinceBootMillis(),
                            wifiInfo.getStackState(), wifiInfo.getControllerTxDurationMillis(),
                            wifiInfo.getControllerRxDurationMillis(),
                            wifiInfo.getControllerIdleDurationMillis(),
                            wifiInfo.getControllerEnergyUsedMicroJoules()));
        } catch (RuntimeException e) {
            Slog.e(TAG, "failed to getWifiActivityEnergyInfoAsync", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerModemActivityInfo() {
        int tagId = FrameworkStatsLog.MODEM_ACTIVITY_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullModemActivityInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        try {
            CompletableFuture<ModemActivityInfo> modemFuture = new CompletableFuture<>();
            mTelephony.requestModemActivityInfo(Runnable::run,
                    new OutcomeReceiver<ModemActivityInfo,
                            TelephonyManager.ModemActivityInfoException>() {
                        @Override
                        public void onResult(ModemActivityInfo result) {
                            modemFuture.complete(result);
                        }

                        @Override
                        public void onError(TelephonyManager.ModemActivityInfoException e) {
                            Slog.w(TAG, "error reading modem stats:" + e);
                            modemFuture.complete(null);
                        }
                    });

            ModemActivityInfo modemInfo;
            try {
                modemInfo = modemFuture.get(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (TimeoutException | InterruptedException e) {
                Slog.w(TAG, "timeout or interrupt reading modem stats: " + e);
                return StatsManager.PULL_SKIP;
            } catch (ExecutionException e) {
                Slog.w(TAG, "exception reading modem stats: " + e.getCause());
                return StatsManager.PULL_SKIP;
            }

            if (modemInfo == null) {
                return StatsManager.PULL_SKIP;
            }
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    modemInfo.getTimestampMillis(),
                    modemInfo.getSleepTimeMillis(), modemInfo.getIdleTimeMillis(),
                    modemInfo.getTransmitDurationMillisAtPowerLevel(0),
                    modemInfo.getTransmitDurationMillisAtPowerLevel(1),
                    modemInfo.getTransmitDurationMillisAtPowerLevel(2),
                    modemInfo.getTransmitDurationMillisAtPowerLevel(3),
                    modemInfo.getTransmitDurationMillisAtPowerLevel(4),
                    modemInfo.getReceiveTimeMillis(),
                    -1 /*`energy_used` field name deprecated, use -1 to indicate as unused.*/));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerBluetoothActivityInfo() {
        int tagId = FrameworkStatsLog.BLUETOOTH_ACTIVITY_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                /* metadata */ null,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullBluetoothActivityInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        BluetoothActivityEnergyInfo info = fetchBluetoothData();
        if (info == null) {
            return StatsManager.PULL_SKIP;
        }
        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, info.getTimestampMillis(),
                info.getBluetoothStackState(), info.getControllerTxTimeMillis(),
                info.getControllerRxTimeMillis(), info.getControllerIdleTimeMillis(),
                info.getControllerEnergyUsed()));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerUwbActivityInfo() {
        if (mUwbManager == null) {
            return;
        }
        int tagId = FrameworkStatsLog.UWB_ACTIVITY_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullUwbActivityInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        try {
            SynchronousResultReceiver uwbReceiver = new SynchronousResultReceiver("uwb");
            mUwbManager.getUwbActivityEnergyInfoAsync(Runnable::run,
                    info -> {
                        Bundle bundle = new Bundle();
                        bundle.putParcelable(BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, info);
                        uwbReceiver.send(0, bundle);
                }
            );
            final UwbActivityEnergyInfo uwbInfo = awaitControllerInfo(uwbReceiver);
            if (uwbInfo == null) {
                return StatsManager.PULL_SKIP;
            }
            pulledData.add(
                    FrameworkStatsLog.buildStatsEvent(atomTag,
                            uwbInfo.getControllerTxDurationMillis(),
                            uwbInfo.getControllerRxDurationMillis(),
                            uwbInfo.getControllerIdleDurationMillis(),
                            uwbInfo.getControllerWakeCount()));
        } catch (RuntimeException e) {
            Slog.e(TAG, "failed to getUwbActivityEnergyInfoAsync", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerSystemElapsedRealtime() {
        int tagId = FrameworkStatsLog.SYSTEM_ELAPSED_REALTIME;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setCoolDownMillis(MILLIS_PER_SEC)
                .setTimeoutMillis(MILLIS_PER_SEC / 2)
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullSystemElapsedRealtimeLocked(int atomTag, List<StatsEvent> pulledData) {
        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, SystemClock.elapsedRealtime()));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerSystemUptime() {
        int tagId = FrameworkStatsLog.SYSTEM_UPTIME;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullSystemUptimeLocked(int atomTag, List<StatsEvent> pulledData) {
        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, SystemClock.uptimeMillis()));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessMemoryState() {
        int tagId = FrameworkStatsLog.PROCESS_MEMORY_STATE;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{4, 5, 6, 7, 8})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullProcessMemoryStateLocked(int atomTag, List<StatsEvent> pulledData) {
        List<ProcessMemoryState> processMemoryStates =
                LocalServices.getService(ActivityManagerInternal.class)
                        .getMemoryStateForProcesses();
        for (ProcessMemoryState processMemoryState : processMemoryStates) {
            final MemoryStat memoryStat = readMemoryStatFromFilesystem(processMemoryState.uid,
                    processMemoryState.pid);
            if (memoryStat == null) {
                continue;
            }
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, processMemoryState.uid,
                    processMemoryState.processName, processMemoryState.oomScore, memoryStat.pgfault,
                    memoryStat.pgmajfault, memoryStat.rssInBytes, memoryStat.cacheInBytes,
                    memoryStat.swapInBytes, -1 /*unused*/, -1 /*unused*/, -1 /*unused*/));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessMemoryHighWaterMark() {
        int tagId = FrameworkStatsLog.PROCESS_MEMORY_HIGH_WATER_MARK;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullProcessMemoryHighWaterMarkLocked(int atomTag, List<StatsEvent> pulledData) {
        List<ProcessMemoryState> managedProcessList =
                LocalServices.getService(ActivityManagerInternal.class)
                        .getMemoryStateForProcesses();
        for (ProcessMemoryState managedProcess : managedProcessList) {
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(managedProcess.pid);
            if (snapshot == null) {
                continue;
            }
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, managedProcess.uid,
                    managedProcess.processName,
                    // RSS high-water mark in bytes.
                    snapshot.rssHighWaterMarkInKilobytes * 1024L,
                    snapshot.rssHighWaterMarkInKilobytes));
        }
        // Complement the data with native system processes
        SparseArray<String> processCmdlines = getProcessCmdlines();
        managedProcessList.forEach(managedProcess -> processCmdlines.delete(managedProcess.pid));
        int size = processCmdlines.size();
        for (int i = 0; i < size; ++i) {
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(processCmdlines.keyAt(i));
            if (snapshot == null) {
                continue;
            }
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, snapshot.uid,
                    processCmdlines.valueAt(i),
                    // RSS high-water mark in bytes.
                    snapshot.rssHighWaterMarkInKilobytes * 1024L,
                    snapshot.rssHighWaterMarkInKilobytes));
        }
        // Invoke rss_hwm_reset binary to reset RSS HWM counters for all processes.
        SystemProperties.set("sys.rss_hwm_reset.on", "1");
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessMemorySnapshot() {
        int tagId = FrameworkStatsLog.PROCESS_MEMORY_SNAPSHOT;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullProcessMemorySnapshot(int atomTag, List<StatsEvent> pulledData) {
        List<ProcessMemoryState> managedProcessList =
                LocalServices.getService(ActivityManagerInternal.class)
                        .getMemoryStateForProcesses();
        KernelAllocationStats.ProcessGpuMem[] gpuAllocations =
                KernelAllocationStats.getGpuAllocations();
        SparseIntArray gpuMemPerPid = new SparseIntArray(gpuAllocations.length);
        for (KernelAllocationStats.ProcessGpuMem processGpuMem : gpuAllocations) {
            gpuMemPerPid.put(processGpuMem.pid, processGpuMem.gpuMemoryKb);
        }
        for (ProcessMemoryState managedProcess : managedProcessList) {
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(managedProcess.pid);
            if (snapshot == null) {
                continue;
            }
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, managedProcess.uid,
                    managedProcess.processName, managedProcess.pid, managedProcess.oomScore,
                    snapshot.rssInKilobytes, snapshot.anonRssInKilobytes, snapshot.swapInKilobytes,
                    snapshot.anonRssInKilobytes + snapshot.swapInKilobytes,
                    gpuMemPerPid.get(managedProcess.pid), managedProcess.hasForegroundServices,
                    snapshot.rssShmemKilobytes, managedProcess.mHostingComponentTypes,
                    managedProcess.mHistoricalHostingComponentTypes));
        }
        // Complement the data with native system processes. Given these measurements can be taken
        // in response to LMKs happening, we want to first collect the managed app stats (to
        // maximize the probability that a heavyweight process will be sampled before it dies).
        SparseArray<String> processCmdlines = getProcessCmdlines();
        managedProcessList.forEach(managedProcess -> processCmdlines.delete(managedProcess.pid));
        int size = processCmdlines.size();
        for (int i = 0; i < size; ++i) {
            int pid = processCmdlines.keyAt(i);
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(pid);
            if (snapshot == null) {
                continue;
            }
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, snapshot.uid,
                    processCmdlines.valueAt(i), pid,
                    -1001 /*Placeholder for native processes, OOM_SCORE_ADJ_MIN - 1.*/,
                    snapshot.rssInKilobytes, snapshot.anonRssInKilobytes, snapshot.swapInKilobytes,
                    snapshot.anonRssInKilobytes + snapshot.swapInKilobytes,
                    gpuMemPerPid.get(pid), false /* has_foreground_services */,
                    snapshot.rssShmemKilobytes,
                    // Native processes don't really have a hosting component type.
                    HOSTING_COMPONENT_TYPE_EMPTY,
                    HOSTING_COMPONENT_TYPE_EMPTY));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerSystemIonHeapSize() {
        int tagId = FrameworkStatsLog.SYSTEM_ION_HEAP_SIZE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullSystemIonHeapSizeLocked(int atomTag, List<StatsEvent> pulledData) {
        final long systemIonHeapSizeInBytes = readSystemIonHeapSizeFromDebugfs();
        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, systemIonHeapSizeInBytes));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerIonHeapSize() {
        if (!new File("/sys/kernel/ion/total_heaps_kb").exists()) {
            return;
        }
        int tagId = FrameworkStatsLog.ION_HEAP_SIZE;
        mStatsManager.setPullAtomCallback(
                tagId,
                /* PullAtomMetadata */ null,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullIonHeapSizeLocked(int atomTag, List<StatsEvent> pulledData) {
        int ionHeapSizeInKilobytes = (int) getIonHeapsSizeKb();
        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, ionHeapSizeInKilobytes));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessSystemIonHeapSize() {
        int tagId = FrameworkStatsLog.PROCESS_SYSTEM_ION_HEAP_SIZE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullProcessSystemIonHeapSizeLocked(int atomTag, List<StatsEvent> pulledData) {
        List<IonAllocations> result = readProcessSystemIonHeapSizesFromDebugfs();
        for (IonAllocations allocations : result) {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, getUidForPid(allocations.pid),
                    readCmdlineFromProcfs(allocations.pid),
                    (int) (allocations.totalSizeInBytes / 1024), allocations.count,
                    (int) (allocations.maxSizeInBytes / 1024)));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessDmabufMemory() {
        int tagId = FrameworkStatsLog.PROCESS_DMABUF_MEMORY;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullProcessDmabufMemory(int atomTag, List<StatsEvent> pulledData) {
        KernelAllocationStats.ProcessDmabuf[] procBufs =
                KernelAllocationStats.getDmabufAllocations();

        if (procBufs == null) {
            return StatsManager.PULL_SKIP;
        }
        for (KernelAllocationStats.ProcessDmabuf procBuf : procBufs) {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    atomTag,
                    procBuf.uid,
                    procBuf.processName,
                    procBuf.oomScore,
                    procBuf.retainedSizeKb,
                    procBuf.retainedBuffersCount,
                    0, /* mapped_dmabuf_kb - deprecated */
                    0, /* mapped_dmabuf_count - deprecated */
                    procBuf.surfaceFlingerSizeKb,
                    procBuf.surfaceFlingerCount
            ));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerSystemMemory() {
        int tagId = FrameworkStatsLog.SYSTEM_MEMORY;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullSystemMemory(int atomTag, List<StatsEvent> pulledData) {
        SystemMemoryUtil.Metrics metrics = SystemMemoryUtil.getMetrics();
        pulledData.add(
                FrameworkStatsLog.buildStatsEvent(
                        atomTag,
                        metrics.unreclaimableSlabKb,
                        metrics.vmallocUsedKb,
                        metrics.pageTablesKb,
                        metrics.kernelStackKb,
                        metrics.totalIonKb,
                        metrics.unaccountedKb,
                        metrics.gpuTotalUsageKb,
                        metrics.gpuPrivateAllocationsKb,
                        metrics.dmaBufTotalExportedKb,
                        metrics.shmemKb,
                        metrics.totalKb,
                        metrics.freeKb,
                        metrics.availableKb,
                        metrics.activeKb,
                        metrics.inactiveKb,
                        metrics.activeAnonKb,
                        metrics.inactiveAnonKb,
                        metrics.activeFileKb,
                        metrics.inactiveFileKb,
                        metrics.swapTotalKb,
                        metrics.swapFreeKb,
                        metrics.cmaTotalKb,
                        metrics.cmaFreeKb));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerVmStat() {
        int tagId = FrameworkStatsLog.VMSTAT;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullVmStat(int atomTag, List<StatsEvent> pulledData) {
        ProcfsMemoryUtil.VmStat vmStat = ProcfsMemoryUtil.readVmStat();
        if (vmStat != null) {
            pulledData.add(
                    FrameworkStatsLog.buildStatsEvent(
                            atomTag,
                            vmStat.oomKillCount));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerTemperature() {
        int tagId = FrameworkStatsLog.TEMPERATURE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullTemperatureLocked(int atomTag, List<StatsEvent> pulledData) {
        IThermalService thermalService = getIThermalService();
        if (thermalService == null) {
            return StatsManager.PULL_SKIP;
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            Temperature temperatures[] = thermalService.getCurrentTemperatures();
            for (Temperature temp : temperatures) {
                pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, temp.getType(),
                        temp.getName(), (int) (temp.getValue() * 10), temp.getStatus()));
            }
        } catch (RemoteException e) {
            // Should not happen.
            Slog.e(TAG, "Disconnected from thermal service. Cannot pull temperatures.");
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCoolingDevice() {
        int tagId = FrameworkStatsLog.COOLING_DEVICE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCooldownDeviceLocked(int atomTag, List<StatsEvent> pulledData) {
        IThermalService thermalService = getIThermalService();
        if (thermalService == null) {
            return StatsManager.PULL_SKIP;
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            CoolingDevice devices[] = thermalService.getCurrentCoolingDevices();
            for (CoolingDevice device : devices) {
                pulledData.add(FrameworkStatsLog.buildStatsEvent(
                        atomTag, device.getType(), device.getName(), (int) (device.getValue())));
            }
        } catch (RemoteException e) {
            // Should not happen.
            Slog.e(TAG, "Disconnected from thermal service. Cannot pull temperatures.");
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerBinderCallsStats() {
        int tagId = FrameworkStatsLog.BINDER_CALLS;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{4, 5, 6, 8, 12})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullBinderCallsStatsLocked(int atomTag, List<StatsEvent> pulledData) {
        BinderCallsStatsService.Internal binderStats =
                LocalServices.getService(BinderCallsStatsService.Internal.class);
        if (binderStats == null) {
            Slog.e(TAG, "failed to get binderStats");
            return StatsManager.PULL_SKIP;
        }

        List<ExportedCallStat> callStats = binderStats.getExportedCallStats();
        binderStats.reset();
        for (ExportedCallStat callStat : callStats) {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, callStat.workSourceUid,
                    callStat.className, callStat.methodName, callStat.callCount,
                    callStat.exceptionCount, callStat.latencyMicros, callStat.maxLatencyMicros,
                    callStat.cpuTimeMicros, callStat.maxCpuTimeMicros, callStat.maxReplySizeBytes,
                    callStat.maxRequestSizeBytes, callStat.recordedCallCount,
                    callStat.screenInteractive, callStat.callingUid));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerBinderCallsStatsExceptions() {
        int tagId = FrameworkStatsLog.BINDER_CALLS_EXCEPTIONS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullBinderCallsStatsExceptionsLocked(int atomTag, List<StatsEvent> pulledData) {
        BinderCallsStatsService.Internal binderStats =
                LocalServices.getService(BinderCallsStatsService.Internal.class);
        if (binderStats == null) {
            Slog.e(TAG, "failed to get binderStats");
            return StatsManager.PULL_SKIP;
        }

        ArrayMap<String, Integer> exceptionStats = binderStats.getExportedExceptionStats();
        // TODO: decouple binder calls exceptions with the rest of the binder calls data so that we
        // can reset the exception stats.
        for (Map.Entry<String, Integer> entry : exceptionStats.entrySet()) {
            pulledData.add(
                    FrameworkStatsLog.buildStatsEvent(atomTag, entry.getKey(), entry.getValue()));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerLooperStats() {
        int tagId = FrameworkStatsLog.LOOPER_STATS;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{5, 6, 7, 8, 9})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullLooperStatsLocked(int atomTag, List<StatsEvent> pulledData) {
        LooperStats looperStats = LocalServices.getService(LooperStats.class);
        if (looperStats == null) {
            return StatsManager.PULL_SKIP;
        }

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        looperStats.reset();
        for (LooperStats.ExportedEntry entry : entries) {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, entry.workSourceUid,
                    entry.handlerClassName, entry.threadName, entry.messageName, entry.messageCount,
                    entry.exceptionCount, entry.recordedMessageCount, entry.totalLatencyMicros,
                    entry.cpuUsageMicros, entry.isInteractive, entry.maxCpuUsageMicros,
                    entry.maxLatencyMicros, entry.recordedDelayMessageCount, entry.delayMillis,
                    entry.maxDelayMillis));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDiskStats() {
        int tagId = FrameworkStatsLog.DISK_STATS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDiskStatsLocked(int atomTag, List<StatsEvent> pulledData) {
        // Run a quick-and-dirty performance test: write 512 bytes
        byte[] junk = new byte[512];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) i;  // Write nonzero bytes

        File tmp = new File(Environment.getDataDirectory(), "system/statsdperftest.tmp");
        FileOutputStream fos = null;
        IOException error = null;

        long before = SystemClock.elapsedRealtime();
        try {
            fos = new FileOutputStream(tmp);
            fos.write(junk);
        } catch (IOException e) {
            error = e;
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (IOException e) {
                // Do nothing.
            }
        }

        long latency = SystemClock.elapsedRealtime() - before;
        if (tmp.exists()) tmp.delete();

        if (error != null) {
            Slog.e(TAG, "Error performing diskstats latency test");
            latency = -1;
        }
        // File based encryption.
        boolean fileBased = StorageManager.isFileEncrypted();

        //Recent disk write speed. Binder call to storaged.
        int writeSpeed = -1;
        IStoraged storaged = getIStoragedService();
        if (storaged == null) {
            return StatsManager.PULL_SKIP;
        }
        try {
            writeSpeed = storaged.getRecentPerf();
        } catch (RemoteException e) {
            Slog.e(TAG, "storaged not found");
        }

        // Add info pulledData.
        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, latency, fileBased, writeSpeed));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDirectoryUsage() {
        int tagId = FrameworkStatsLog.DIRECTORY_USAGE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDirectoryUsageLocked(int atomTag, List<StatsEvent> pulledData) {
        StatFs statFsData = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        StatFs statFsSystem = new StatFs(Environment.getRootDirectory().getAbsolutePath());
        StatFs statFsCache = new StatFs(Environment.getDownloadCacheDirectory().getAbsolutePath());
        StatFs metadataFsSystem = new StatFs(Environment.getMetadataDirectory().getAbsolutePath());

        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                FrameworkStatsLog.DIRECTORY_USAGE__DIRECTORY__DATA, statFsData.getAvailableBytes(),
                statFsData.getTotalBytes()));

        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                FrameworkStatsLog.DIRECTORY_USAGE__DIRECTORY__CACHE,
                statFsCache.getAvailableBytes(), statFsCache.getTotalBytes()));

        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                FrameworkStatsLog.DIRECTORY_USAGE__DIRECTORY__SYSTEM,
                statFsSystem.getAvailableBytes(), statFsSystem.getTotalBytes()));

        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                FrameworkStatsLog.DIRECTORY_USAGE__DIRECTORY__METADATA,
                metadataFsSystem.getAvailableBytes(), metadataFsSystem.getTotalBytes()));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerAppSize() {
        int tagId = FrameworkStatsLog.APP_SIZE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullAppSizeLocked(int atomTag, List<StatsEvent> pulledData) {
        try {
            String jsonStr = IoUtils.readFileAsString(DiskStatsLoggingService.DUMPSYS_CACHE_PATH);
            JSONObject json = new JSONObject(jsonStr);
            long cache_time = json.optLong(DiskStatsFileLogger.LAST_QUERY_TIMESTAMP_KEY, -1L);
            JSONArray pkg_names = json.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY);
            JSONArray app_sizes = json.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY);
            JSONArray app_data_sizes = json.getJSONArray(DiskStatsFileLogger.APP_DATA_KEY);
            JSONArray app_cache_sizes = json.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY);
            // Validity check: Ensure all 4 lists have the same length.
            int length = pkg_names.length();
            if (app_sizes.length() != length || app_data_sizes.length() != length
                    || app_cache_sizes.length() != length) {
                Slog.e(TAG, "formatting error in diskstats cache file!");
                return StatsManager.PULL_SKIP;
            }
            for (int i = 0; i < length; i++) {
                pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, pkg_names.getString(i),
                        app_sizes.optLong(i, /* fallback */ -1L),
                        app_data_sizes.optLong(i, /* fallback */ -1L),
                        app_cache_sizes.optLong(i, /* fallback */ -1L), cache_time));
            }
        } catch (IOException | JSONException e) {
            Slog.w(TAG, "Unable to read diskstats cache file within pullAppSize");
            return StatsManager.PULL_SKIP;
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCategorySize() {
        int tagId = FrameworkStatsLog.CATEGORY_SIZE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCategorySizeLocked(int atomTag, List<StatsEvent> pulledData) {
        try {
            String jsonStr = IoUtils.readFileAsString(DiskStatsLoggingService.DUMPSYS_CACHE_PATH);
            JSONObject json = new JSONObject(jsonStr);
            long cacheTime = json.optLong(
                    DiskStatsFileLogger.LAST_QUERY_TIMESTAMP_KEY, /* fallback */ -1L);

            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__APP_SIZE,
                    json.optLong(DiskStatsFileLogger.APP_SIZE_AGG_KEY, /* fallback */ -1L),
                    cacheTime));

            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__APP_DATA_SIZE,
                    json.optLong(DiskStatsFileLogger.APP_DATA_SIZE_AGG_KEY, /* fallback */ -1L),
                    cacheTime));

            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__APP_CACHE_SIZE,
                    json.optLong(DiskStatsFileLogger.APP_CACHE_AGG_KEY, /* fallback */ -1L),
                    cacheTime));

            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__PHOTOS,
                    json.optLong(DiskStatsFileLogger.PHOTOS_KEY, /* fallback */ -1L), cacheTime));

            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__VIDEOS,
                    json.optLong(DiskStatsFileLogger.VIDEOS_KEY, /* fallback */ -1L), cacheTime));

            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__AUDIO,
                    json.optLong(DiskStatsFileLogger.AUDIO_KEY, /* fallback */ -1L), cacheTime));

            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__DOWNLOADS,
                    json.optLong(DiskStatsFileLogger.DOWNLOADS_KEY, /* fallback */ -1L),
                    cacheTime));

            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__SYSTEM,
                    json.optLong(DiskStatsFileLogger.SYSTEM_KEY, /* fallback */ -1L), cacheTime));

            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    FrameworkStatsLog.CATEGORY_SIZE__CATEGORY__OTHER,
                    json.optLong(DiskStatsFileLogger.MISC_KEY, /* fallback */ -1L), cacheTime));
        } catch (IOException | JSONException e) {
            Slog.w(TAG, "Unable to read diskstats cache file within pullCategorySize");
            return StatsManager.PULL_SKIP;
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerNumFingerprintsEnrolled() {
        int tagId = FrameworkStatsLog.NUM_FINGERPRINTS_ENROLLED;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerNumFacesEnrolled() {
        int tagId = FrameworkStatsLog.NUM_FACES_ENROLLED;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private int pullNumBiometricsEnrolledLocked(int modality, int atomTag,
            List<StatsEvent> pulledData) {
        final PackageManager pm = mContext.getPackageManager();
        FingerprintManager fingerprintManager = null;
        FaceManager faceManager = null;

        if (pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            fingerprintManager = mContext.getSystemService(FingerprintManager.class);
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_FACE)) {
            faceManager = mContext.getSystemService(FaceManager.class);
        }

        if (modality == BiometricsProtoEnums.MODALITY_FINGERPRINT && fingerprintManager == null) {
            return StatsManager.PULL_SKIP;
        }
        if (modality == BiometricsProtoEnums.MODALITY_FACE && faceManager == null) {
            return StatsManager.PULL_SKIP;
        }
        UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager == null) {
            return StatsManager.PULL_SKIP;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            for (UserInfo user : userManager.getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();
                int numEnrolled = 0;
                if (modality == BiometricsProtoEnums.MODALITY_FINGERPRINT) {
                    numEnrolled = fingerprintManager.getEnrolledFingerprints(userId).size();
                } else if (modality == BiometricsProtoEnums.MODALITY_FACE) {
                    numEnrolled = faceManager.getEnrolledFaces(userId).size();
                } else {
                    return StatsManager.PULL_SKIP;
                }
                pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, userId, numEnrolled));
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcStats() {
        int tagId = FrameworkStatsLog.PROC_STATS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerProcStatsPkgProc() {
        int tagId = FrameworkStatsLog.PROC_STATS_PKG_PROC;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerProcessState() {
        int tagId = FrameworkStatsLog.PROCESS_STATE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl);
    }

    private void registerProcessAssociation() {
        int tagId = FrameworkStatsLog.PROCESS_ASSOCIATION;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl);
    }

    @GuardedBy("mProcStatsLock")
    private ProcessStats getStatsFromProcessStatsService(int atomTag) {
        IProcessStats processStatsService = getIProcessStatsService();
        if (processStatsService == null) {
            return null;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            // force procstats to flush & combine old files into one store
            long lastHighWaterMark = readProcStatsHighWaterMark(atomTag);
            ProcessStats procStats = new ProcessStats(false);
            // Force processStatsService to aggregate all in-storage and in-memory data.
            long highWaterMark =
                    processStatsService.getCommittedStatsMerged(
                            lastHighWaterMark,
                            ProcessStats.REPORT_ALL, // ignored since committedStats below is null.
                            true,
                            null, // committedStats
                            procStats);
            new File(
                            mBaseDir.getAbsolutePath()
                                    + "/"
                                    + highWaterMarkFilePrefix(atomTag)
                                    + "_"
                                    + lastHighWaterMark)
                    .delete();
            new File(
                            mBaseDir.getAbsolutePath()
                                    + "/"
                                    + highWaterMarkFilePrefix(atomTag)
                                    + "_"
                                    + highWaterMark)
                    .createNewFile();
            return procStats;
        } catch (RemoteException | IOException e) {
            Slog.e(TAG, "Getting procstats failed: ", e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @GuardedBy("mProcStatsLock")
    private int pullProcStatsLocked(int atomTag, List<StatsEvent> pulledData) {
        ProcessStats procStats = getStatsFromProcessStatsService(atomTag);
        if (procStats == null) {
            return StatsManager.PULL_SKIP;
        }
        ProtoOutputStream[] protoStreams = new ProtoOutputStream[MAX_PROCSTATS_SHARDS];
        for (int i = 0; i < protoStreams.length; i++) {
            protoStreams[i] = new ProtoOutputStream();
        }
        procStats.dumpAggregatedProtoForStatsd(protoStreams, MAX_PROCSTATS_RAW_SHARD_SIZE);
        for (int i = 0; i < protoStreams.length; i++) {
            byte[] bytes = protoStreams[i].getBytes(); // cache the value
            if (bytes.length > 0) {
                pulledData.add(
                        FrameworkStatsLog.buildStatsEvent(
                                atomTag,
                                bytes,
                                // This is a shard ID, and is specified in the metric definition to
                                // be
                                // a dimension. This will result in statsd using RANDOM_ONE_SAMPLE
                                // to
                                // keep all the shards, as it thinks each shard is a different
                                // dimension
                                // of data.
                                i));
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    @GuardedBy("mProcStatsLock")
    private int pullProcessStateLocked(int atomTag, List<StatsEvent> pulledData) {
        ProcessStats procStats = getStatsFromProcessStatsService(atomTag);
        if (procStats == null) {
            return StatsManager.PULL_SKIP;
        }
        procStats.dumpProcessState(atomTag, new StatsEventOutput(pulledData));
        return StatsManager.PULL_SUCCESS;
    }

    @GuardedBy("mProcStatsLock")
    private int pullProcessAssociationLocked(int atomTag, List<StatsEvent> pulledData) {
        ProcessStats procStats = getStatsFromProcessStatsService(atomTag);
        if (procStats == null) {
            return StatsManager.PULL_SKIP;
        }
        procStats.dumpProcessAssociation(atomTag, new StatsEventOutput(pulledData));
        return StatsManager.PULL_SUCCESS;
    }

    private String highWaterMarkFilePrefix(int atomTag) {
        // For backward compatibility, use the legacy ProcessStats enum value as the prefix for
        // PROC_STATS and PROC_STATS_PKG_PROC.
        if (atomTag == FrameworkStatsLog.PROC_STATS) {
            return String.valueOf(ProcessStats.REPORT_ALL);
        }
        if (atomTag == FrameworkStatsLog.PROC_STATS_PKG_PROC) {
            return String.valueOf(ProcessStats.REPORT_PKG_PROC_STATS);
        }
        return "atom-" + atomTag;
    }

    // read high watermark for section
    @GuardedBy("mProcStatsLock")
    private long readProcStatsHighWaterMark(int atomTag) {
        try {
            File[] files =
                    mBaseDir.listFiles(
                            (d, name) -> {
                                return name.toLowerCase()
                                        .startsWith(highWaterMarkFilePrefix(atomTag) + '_');
                            });
            if (files == null || files.length == 0) {
                return 0;
            }
            if (files.length > 1) {
                Slog.e(TAG, "Only 1 file expected for high water mark. Found " + files.length);
            }
            return Long.valueOf(files[0].getName().split("_")[1]);
        } catch (SecurityException e) {
            Slog.e(TAG, "Failed to get procstats high watermark file.", e);
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Failed to parse file name.", e);
        }
        return 0;
    }

    private void registerDiskIO() {
        int tagId = FrameworkStatsLog.DISK_IO;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
                .setCoolDownMillis(3 * MILLIS_PER_SEC)
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDiskIOLocked(int atomTag, List<StatsEvent> pulledData) {
        mStoragedUidIoStatsReader.readAbsolute(
                (uid, fgCharsRead, fgCharsWrite, fgBytesRead, fgBytesWrite, bgCharsRead,
                        bgCharsWrite, bgBytesRead, bgBytesWrite, fgFsync, bgFsync) -> {
                    pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, uid, fgCharsRead,
                            fgCharsWrite, fgBytesRead, fgBytesWrite, bgCharsRead, bgCharsWrite,
                            bgBytesRead, bgBytesWrite, fgFsync, bgFsync));
                });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerPowerProfile() {
        int tagId = FrameworkStatsLog.POWER_PROFILE;
        mStatsManager.setPullAtomCallback(
                tagId,
                /* PullAtomMetadata */ null,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullPowerProfileLocked(int atomTag, List<StatsEvent> pulledData) {
        PowerProfile powerProfile = new PowerProfile(mContext);
        ProtoOutputStream proto = new ProtoOutputStream();
        powerProfile.dumpDebug(proto);
        proto.flush();
        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, proto.getBytes()));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessCpuTime() {
        int tagId = FrameworkStatsLog.PROCESS_CPU_TIME;
        // Min cool-down is 5 sec, in line with what ActivityManagerService uses.
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setCoolDownMillis(5 * MILLIS_PER_SEC)
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullProcessCpuTimeLocked(int atomTag, List<StatsEvent> pulledData) {
        if (mProcessCpuTracker == null) {
            mProcessCpuTracker = new ProcessCpuTracker(false);
            mProcessCpuTracker.init();
        }
        mProcessCpuTracker.update();
        for (int i = 0; i < mProcessCpuTracker.countStats(); i++) {
            ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    atomTag, st.uid, st.name, st.base_utime, st.base_stime));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuTimePerThreadFreq() {
        int tagId = FrameworkStatsLog.CPU_TIME_PER_THREAD_FREQ;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{7, 9, 11, 13, 15, 17, 19, 21})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullCpuTimePerThreadFreqLocked(int atomTag, List<StatsEvent> pulledData) {
        if (this.mKernelCpuThreadReader == null) {
            Slog.e(TAG, "mKernelCpuThreadReader is null");
            return StatsManager.PULL_SKIP;
        }
        ArrayList<KernelCpuThreadReader.ProcessCpuUsage> processCpuUsages =
                this.mKernelCpuThreadReader.getProcessCpuUsageDiffed();
        if (processCpuUsages == null) {
            Slog.e(TAG, "processCpuUsages is null");
            return StatsManager.PULL_SKIP;
        }
        int[] cpuFrequencies = mKernelCpuThreadReader.getCpuFrequenciesKhz();
        if (cpuFrequencies.length > CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES) {
            String message = "Expected maximum " + CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES
                    + " frequencies, but got " + cpuFrequencies.length;
            Slog.w(TAG, message);
            return StatsManager.PULL_SKIP;
        }
        for (int i = 0; i < processCpuUsages.size(); i++) {
            KernelCpuThreadReader.ProcessCpuUsage processCpuUsage = processCpuUsages.get(i);
            ArrayList<KernelCpuThreadReader.ThreadCpuUsage> threadCpuUsages =
                    processCpuUsage.threadCpuUsages;
            for (int j = 0; j < threadCpuUsages.size(); j++) {
                KernelCpuThreadReader.ThreadCpuUsage threadCpuUsage = threadCpuUsages.get(j);
                if (threadCpuUsage.usageTimesMillis.length != cpuFrequencies.length) {
                    String message = "Unexpected number of usage times,"
                            + " expected " + cpuFrequencies.length
                            + " but got " + threadCpuUsage.usageTimesMillis.length;
                    Slog.w(TAG, message);
                    return StatsManager.PULL_SKIP;
                }

                int[] frequencies = new int[CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES];
                int[] usageTimesMillis = new int[CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES];
                for (int k = 0; k < CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES; k++) {
                    if (k < cpuFrequencies.length) {
                        frequencies[k] = cpuFrequencies[k];
                        usageTimesMillis[k] = threadCpuUsage.usageTimesMillis[k];
                    } else {
                        // If we have no more frequencies to write, we still must write empty data.
                        // We know that this data is empty (and not just zero) because all
                        // frequencies are expected to be greater than zero
                        frequencies[k] = 0;
                        usageTimesMillis[k] = 0;
                    }
                }
                pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, processCpuUsage.uid,
                        processCpuUsage.processId, threadCpuUsage.threadId,
                        processCpuUsage.processName, threadCpuUsage.threadName, frequencies[0],
                        usageTimesMillis[0], frequencies[1], usageTimesMillis[1], frequencies[2],
                        usageTimesMillis[2], frequencies[3], usageTimesMillis[3], frequencies[4],
                        usageTimesMillis[4], frequencies[5], usageTimesMillis[5], frequencies[6],
                        usageTimesMillis[6], frequencies[7], usageTimesMillis[7]));
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private long milliAmpHrsToNanoAmpSecs(double mAh) {
        return (long) (mAh * MILLI_AMP_HR_TO_NANO_AMP_SECS + 0.5);
    }

    private void registerDeviceCalculatedPowerUse() {
        int tagId = FrameworkStatsLog.DEVICE_CALCULATED_POWER_USE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDeviceCalculatedPowerUseLocked(int atomTag, List<StatsEvent> pulledData) {
        final BatteryStatsManager bsm = mContext.getSystemService(BatteryStatsManager.class);
        try {
            final BatteryUsageStats stats = bsm.getBatteryUsageStats();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    atomTag, milliAmpHrsToNanoAmpSecs(stats.getConsumedPower())));
            return StatsManager.PULL_SUCCESS;
        } catch (Exception e) {
            Log.e(TAG, "Could not obtain battery usage stats", e);
            return StatsManager.PULL_SKIP;
        }
    }

    private void registerDebugElapsedClock() {
        int tagId = FrameworkStatsLog.DEBUG_ELAPSED_CLOCK;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{1, 2, 3, 4})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDebugElapsedClockLocked(int atomTag, List<StatsEvent> pulledData) {
        final long elapsedMillis = SystemClock.elapsedRealtime();
        final long clockDiffMillis = mDebugElapsedClockPreviousValue == 0
                ? 0 : elapsedMillis - mDebugElapsedClockPreviousValue;

        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, mDebugElapsedClockPullCount,
                elapsedMillis,
                // Log it twice to be able to test multi-value aggregation from ValueMetric.
                elapsedMillis, clockDiffMillis, 1 /* always set */));

        if (mDebugElapsedClockPullCount % 2 == 1) {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, mDebugElapsedClockPullCount,
                    elapsedMillis,
                    // Log it twice to be able to test multi-value aggregation from ValueMetric.
                    elapsedMillis, clockDiffMillis, 2 /* set on odd pulls */));
        }

        mDebugElapsedClockPullCount++;
        mDebugElapsedClockPreviousValue = elapsedMillis;
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDebugFailingElapsedClock() {
        int tagId = FrameworkStatsLog.DEBUG_FAILING_ELAPSED_CLOCK;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[]{1, 2, 3, 4})
                .build();
        mStatsManager.setPullAtomCallback(
                tagId,
                metadata,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDebugFailingElapsedClockLocked(int atomTag, List<StatsEvent> pulledData) {
        final long elapsedMillis = SystemClock.elapsedRealtime();
        // Fails every 5 buckets.
        if (mDebugFailingElapsedClockPullCount++ % 5 == 0) {
            mDebugFailingElapsedClockPreviousValue = elapsedMillis;
            Slog.e(TAG, "Failing debug elapsed clock");
            return StatsManager.PULL_SKIP;
        }

        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                mDebugFailingElapsedClockPullCount, elapsedMillis,
                // Log it twice to be able to test multi-value aggregation from ValueMetric.
                elapsedMillis,
                mDebugFailingElapsedClockPreviousValue == 0
                        ? 0
                        : elapsedMillis - mDebugFailingElapsedClockPreviousValue));

        mDebugFailingElapsedClockPreviousValue = elapsedMillis;
        return StatsManager.PULL_SUCCESS;
    }

    private void registerBuildInformation() {
        int tagId = FrameworkStatsLog.BUILD_INFORMATION;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullBuildInformationLocked(int atomTag, List<StatsEvent> pulledData) {
        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, Build.FINGERPRINT, Build.BRAND,
                Build.PRODUCT, Build.DEVICE, Build.VERSION.RELEASE_OR_CODENAME, Build.ID,
                Build.VERSION.INCREMENTAL, Build.TYPE, Build.TAGS));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerRoleHolder() {
        int tagId = FrameworkStatsLog.ROLE_HOLDER;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    // Add a RoleHolder atom for each package that holds a role.
    int pullRoleHolderLocked(int atomTag, List<StatsEvent> pulledData) {
        final long callingToken = Binder.clearCallingIdentity();
        try {
            PackageManager pm = mContext.getPackageManager();
            RoleManagerLocal roleManagerLocal = LocalManagerRegistry.getManager(
                    RoleManagerLocal.class);

            List<UserInfo> users = mContext.getSystemService(UserManager.class).getUsers();

            int numUsers = users.size();
            for (int userNum = 0; userNum < numUsers; userNum++) {
                int userId = users.get(userNum).getUserHandle().getIdentifier();

                Map<String, Set<String>> roles = roleManagerLocal.getRolesAndHolders(userId);

                for (Map.Entry<String, Set<String>> roleEntry : roles.entrySet()) {
                    String roleName = roleEntry.getKey();
                    Set<String> packageNames = roleEntry.getValue();

                    if (!packageNames.isEmpty()) {
                        for (String packageName : packageNames) {
                            PackageInfo pkg;
                            try {
                                pkg = pm.getPackageInfoAsUser(packageName, 0, userId);
                            } catch (PackageManager.NameNotFoundException e) {
                                Slog.w(TAG, "Role holder " + packageName + " not found");
                                return StatsManager.PULL_SKIP;
                            }

                            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                                    atomTag, pkg.applicationInfo.uid, packageName, roleName));
                        }
                    } else {
                        // Ensure that roles set to None are logged with an empty state.
                        pulledData.add(FrameworkStatsLog.buildStatsEvent(
                                atomTag, INVALID_UID, "", roleName));
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDangerousPermissionState() {
        int tagId = FrameworkStatsLog.DANGEROUS_PERMISSION_STATE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullDangerousPermissionStateLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        float samplingRate = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_PERMISSIONS,
                DANGEROUS_PERMISSION_STATE_SAMPLE_RATE, 0.015f);
        Set<Integer> reportedUids = new HashSet<>();
        try {
            PackageManager pm = mContext.getPackageManager();

            List<UserInfo> users = mContext.getSystemService(UserManager.class).getUsers();

            int numUsers = users.size();
            for (int userNum = 0; userNum < numUsers; userNum++) {
                UserHandle user = users.get(userNum).getUserHandle();

                List<PackageInfo> pkgs = pm.getInstalledPackagesAsUser(
                        PackageManager.GET_PERMISSIONS, user.getIdentifier());

                int numPkgs = pkgs.size();
                for (int pkgNum = 0; pkgNum < numPkgs; pkgNum++) {
                    PackageInfo pkg = pkgs.get(pkgNum);

                    if (pkg.requestedPermissions == null) {
                        continue;
                    }

                    if (reportedUids.contains(pkg.applicationInfo.uid)) {
                        // do not report same uid twice
                        continue;
                    }
                    reportedUids.add(pkg.applicationInfo.uid);

                    if (atomTag == FrameworkStatsLog.DANGEROUS_PERMISSION_STATE_SAMPLED
                            && ThreadLocalRandom.current().nextFloat() > samplingRate) {
                        continue;
                    }

                    int numPerms = pkg.requestedPermissions.length;
                    for (int permNum = 0; permNum < numPerms; permNum++) {
                        String permName = pkg.requestedPermissions[permNum];

                        PermissionInfo permissionInfo;
                        int permissionFlags = 0;
                        try {
                            permissionInfo = pm.getPermissionInfo(permName, 0);
                            permissionFlags =
                                    pm.getPermissionFlags(permName, pkg.packageName, user);
                        } catch (PackageManager.NameNotFoundException ignored) {
                            continue;
                        }

                        if (permName.startsWith(COMMON_PERMISSION_PREFIX)) {
                            permName = permName.substring(COMMON_PERMISSION_PREFIX.length());
                        }

                        StatsEvent e;
                        if (atomTag == FrameworkStatsLog.DANGEROUS_PERMISSION_STATE) {
                            e = FrameworkStatsLog.buildStatsEvent(atomTag, permName,
                                    pkg.applicationInfo.uid, "",
                                    (pkg.requestedPermissionsFlags[permNum]
                                            & REQUESTED_PERMISSION_GRANTED)
                                            != 0,
                                    permissionFlags, permissionInfo.getProtection()
                                            | permissionInfo.getProtectionFlags());
                        } else {
                            // DangeorusPermissionStateSampled atom.
                            e = FrameworkStatsLog.buildStatsEvent(atomTag, permName,
                                    pkg.applicationInfo.uid,
                                    (pkg.requestedPermissionsFlags[permNum]
                                            & REQUESTED_PERMISSION_GRANTED)
                                            != 0,
                                    permissionFlags, permissionInfo.getProtection()
                                            | permissionInfo.getProtectionFlags());
                        }
                        pulledData.add(e);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not read permissions", t);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerTimeZoneDataInfo() {
        int tagId = FrameworkStatsLog.TIME_ZONE_DATA_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullTimeZoneDataInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        String tzDbVersion = "Unknown";
        try {
            tzDbVersion = android.icu.util.TimeZone.getTZDataVersion();
        } catch (MissingResourceException e) {
            Slog.e(TAG, "Getting tzdb version failed: ", e);
            return StatsManager.PULL_SKIP;
        }

        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, tzDbVersion));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerTimeZoneDetectorState() {
        int tagId = FrameworkStatsLog.TIME_ZONE_DETECTOR_STATE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullTimeZoneDetectorStateLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        try {
            TimeZoneDetectorInternal timeZoneDetectorInternal =
                    LocalServices.getService(TimeZoneDetectorInternal.class);
            MetricsTimeZoneDetectorState metricsState =
                    timeZoneDetectorInternal.generateMetricsState();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    metricsState.isTelephonyDetectionSupported(),
                    metricsState.isGeoDetectionSupported(),
                    metricsState.getUserLocationEnabledSetting(),
                    metricsState.getAutoDetectionEnabledSetting(),
                    metricsState.getGeoDetectionEnabledSetting(),
                    convertToMetricsDetectionMode(metricsState.getDetectionMode()),
                    metricsState.getDeviceTimeZoneIdOrdinal(),
                    convertTimeZoneSuggestionToProtoBytes(
                            metricsState.getLatestManualSuggestion()),
                    convertTimeZoneSuggestionToProtoBytes(
                            metricsState.getLatestTelephonySuggestion()),
                    convertTimeZoneSuggestionToProtoBytes(
                            metricsState.getLatestGeolocationSuggestion()),
                    metricsState.isTelephonyTimeZoneFallbackSupported(),
                    metricsState.getDeviceTimeZoneId(),
                    metricsState.isEnhancedMetricsCollectionEnabled(),
                    metricsState.getGeoDetectionRunInBackgroundEnabled()
            ));
        } catch (RuntimeException e) {
            Slog.e(TAG, "Getting time zone detection state failed: ", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private static int convertToMetricsDetectionMode(
            @MetricsTimeZoneDetectorState.DetectionMode int detectionMode) {
        switch (detectionMode) {
            case MetricsTimeZoneDetectorState.DETECTION_MODE_MANUAL:
                return TIME_ZONE_DETECTOR_STATE__DETECTION_MODE__MANUAL;
            case MetricsTimeZoneDetectorState.DETECTION_MODE_GEO:
                return TIME_ZONE_DETECTOR_STATE__DETECTION_MODE__GEO;
            case MetricsTimeZoneDetectorState.DETECTION_MODE_TELEPHONY:
                return TIME_ZONE_DETECTOR_STATE__DETECTION_MODE__TELEPHONY;
            default:
                return TIME_ZONE_DETECTOR_STATE__DETECTION_MODE__UNKNOWN;
        }
    }

    @Nullable
    private static byte[] convertTimeZoneSuggestionToProtoBytes(
            @Nullable MetricsTimeZoneDetectorState.MetricsTimeZoneSuggestion suggestion) {
        if (suggestion == null) {
            return null;
        }

        // We don't get access to the atoms.proto definition for nested proto fields, so we use
        // an identically specified proto.
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ProtoOutputStream protoOutputStream = new ProtoOutputStream(byteArrayOutputStream);
        int typeProtoValue = suggestion.isCertain()
                ? android.app.time.MetricsTimeZoneSuggestion.CERTAIN
                : android.app.time.MetricsTimeZoneSuggestion.UNCERTAIN;
        protoOutputStream.write(android.app.time.MetricsTimeZoneSuggestion.TYPE,
                typeProtoValue);
        if (suggestion.isCertain()) {
            for (int zoneIdOrdinal : suggestion.getZoneIdOrdinals()) {
                protoOutputStream.write(
                        android.app.time.MetricsTimeZoneSuggestion.TIME_ZONE_ORDINALS,
                        zoneIdOrdinal);
            }
            String[] zoneIds = suggestion.getZoneIds();
            if (zoneIds != null) {
                for (String zoneId : zoneIds) {
                    protoOutputStream.write(
                            android.app.time.MetricsTimeZoneSuggestion.TIME_ZONE_IDS,
                            zoneId);
                }
            }
        }
        protoOutputStream.flush();
        closeQuietly(byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
    }

    private void registerExternalStorageInfo() {
        int tagId = FrameworkStatsLog.EXTERNAL_STORAGE_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullExternalStorageInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        if (mStorageManager == null) {
            return StatsManager.PULL_SKIP;
        }

        List<VolumeInfo> volumes = mStorageManager.getVolumes();
        for (VolumeInfo vol : volumes) {
            final String envState = VolumeInfo.getEnvironmentForState(vol.getState());
            final DiskInfo diskInfo = vol.getDisk();
            if (diskInfo != null && envState.equals(Environment.MEDIA_MOUNTED)) {
                // Get the type of the volume, if it is adoptable or portable.
                int volumeType = FrameworkStatsLog.EXTERNAL_STORAGE_INFO__VOLUME_TYPE__OTHER;
                if (vol.getType() == TYPE_PUBLIC) {
                    volumeType = FrameworkStatsLog.EXTERNAL_STORAGE_INFO__VOLUME_TYPE__PUBLIC;
                } else if (vol.getType() == TYPE_PRIVATE) {
                    volumeType = FrameworkStatsLog.EXTERNAL_STORAGE_INFO__VOLUME_TYPE__PRIVATE;
                }

                // Get the type of external storage inserted in the device (sd cards, usb, etc.)
                int externalStorageType;
                if (diskInfo.isSd()) {
                    externalStorageType = StorageEnums.SD_CARD;
                } else if (diskInfo.isUsb()) {
                    externalStorageType = StorageEnums.USB;
                } else {
                    externalStorageType = StorageEnums.OTHER;
                }

                pulledData.add(FrameworkStatsLog.buildStatsEvent(
                        atomTag, externalStorageType, volumeType, diskInfo.size));
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerAppsOnExternalStorageInfo() {
        int tagId = FrameworkStatsLog.APPS_ON_EXTERNAL_STORAGE_INFO;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullAppsOnExternalStorageInfoLocked(int atomTag, List<StatsEvent> pulledData) {
        if (mStorageManager == null) {
            return StatsManager.PULL_SKIP;
        }

        PackageManager pm = mContext.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(/*flags=*/ 0);
        for (ApplicationInfo appInfo : apps) {
            UUID storageUuid = appInfo.storageUuid;
            if (storageUuid == null) {
                continue;
            }

            VolumeInfo volumeInfo = mStorageManager.findVolumeByUuid(
                    appInfo.storageUuid.toString());
            if (volumeInfo == null) {
                continue;
            }

            DiskInfo diskInfo = volumeInfo.getDisk();
            if (diskInfo == null) {
                continue;
            }

            int externalStorageType = -1;
            if (diskInfo.isSd()) {
                externalStorageType = StorageEnums.SD_CARD;
            } else if (diskInfo.isUsb()) {
                externalStorageType = StorageEnums.USB;
            } else if (appInfo.isExternal()) {
                externalStorageType = StorageEnums.OTHER;
            }

            // App is installed on external storage.
            if (externalStorageType != -1) {
                pulledData.add(FrameworkStatsLog.buildStatsEvent(
                        atomTag, externalStorageType, appInfo.packageName));
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerFaceSettings() {
        int tagId = FrameworkStatsLog.FACE_SETTINGS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullFaceSettingsLocked(int atomTag, List<StatsEvent> pulledData) {
        final long callingToken = Binder.clearCallingIdentity();
        try {
            UserManager manager = mContext.getSystemService(UserManager.class);
            if (manager == null) {
                return StatsManager.PULL_SKIP;
            }
            List<UserInfo> users = manager.getUsers();
            int numUsers = users.size();
            for (int userNum = 0; userNum < numUsers; userNum++) {
                int userId = users.get(userNum).getUserHandle().getIdentifier();

                int unlockKeyguardEnabled = Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.FACE_UNLOCK_KEYGUARD_ENABLED, 1, userId);
                int unlockDismissesKeyguard = Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD, 1, userId);
                int unlockAttentionRequired = Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.FACE_UNLOCK_ATTENTION_REQUIRED, 0, userId);
                int unlockAppEnabled = Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.FACE_UNLOCK_APP_ENABLED, 1, userId);
                int unlockAlwaysRequireConfirmation = Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION, 0, userId);
                int unlockDiversityRequired = Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.FACE_UNLOCK_DIVERSITY_REQUIRED, 1, userId);

                pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                        unlockKeyguardEnabled != 0, unlockDismissesKeyguard != 0,
                        unlockAttentionRequired != 0, unlockAppEnabled != 0,
                        unlockAlwaysRequireConfirmation != 0, unlockDiversityRequired != 0));
            }
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerAppOps() {
        int tagId = FrameworkStatsLog.APP_OPS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerRuntimeAppOpAccessMessage() {
        int tagId = FrameworkStatsLog.RUNTIME_APP_OP_ACCESS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private class AppOpEntry {
        public final String mPackageName;
        public final String mAttributionTag;
        public final int mUid;
        public final HistoricalOp mOp;
        public final int mHash;

        AppOpEntry(String packageName, @Nullable String attributionTag, HistoricalOp op, int uid) {
            mPackageName = packageName;
            mAttributionTag = attributionTag;
            mUid = uid;
            mOp = op;
            mHash = ((packageName.hashCode() + RANDOM_SEED) & 0x7fffffff) % 100;
        }
    }

    int pullAppOpsLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        try {
            AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);

            CompletableFuture<HistoricalOps> ops = new CompletableFuture<>();
            HistoricalOpsRequest histOpsRequest = new HistoricalOpsRequest.Builder(0,
                    Long.MAX_VALUE).setFlags(OP_FLAGS_PULLED).build();
            appOps.getHistoricalOps(histOpsRequest, AsyncTask.THREAD_POOL_EXECUTOR, ops::complete);
            HistoricalOps histOps = ops.get(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);

            List<AppOpEntry> opsList = processHistoricalOps(histOps, atomTag, 100);
            int samplingRate = sampleAppOps(pulledData, opsList, atomTag, 100);
            if (samplingRate != 100) {
                Slog.e(TAG, "Atom 10060 downsampled - too many dimensions");
            }
        } catch (Throwable t) {
            // TODO: catch exceptions at a more granular level
            Slog.e(TAG, "Could not read appops", t);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private int sampleAppOps(List<StatsEvent> pulledData, List<AppOpEntry> opsList, int atomTag,
            int samplingRate) {
        int nOps = opsList.size();
        for (int i = 0; i < nOps; i++) {
            AppOpEntry entry = opsList.get(i);
            if (entry.mHash >= samplingRate) {
                continue;
            }
            StatsEvent e;
            if (atomTag == FrameworkStatsLog.ATTRIBUTED_APP_OPS) {
                e = FrameworkStatsLog.buildStatsEvent(atomTag, entry.mUid, entry.mPackageName,
                        entry.mAttributionTag, entry.mOp.getOpCode(),
                        entry.mOp.getForegroundAccessCount(OP_FLAGS_PULLED),
                        entry.mOp.getBackgroundAccessCount(OP_FLAGS_PULLED),
                        entry.mOp.getForegroundRejectCount(OP_FLAGS_PULLED),
                        entry.mOp.getBackgroundRejectCount(OP_FLAGS_PULLED),
                        entry.mOp.getForegroundAccessDuration(OP_FLAGS_PULLED),
                        entry.mOp.getBackgroundAccessDuration(OP_FLAGS_PULLED),
                        mDangerousAppOpsList.contains(entry.mOp.getOpCode()), samplingRate);
            } else {
                // AppOps atom.
                e = FrameworkStatsLog.buildStatsEvent(atomTag, entry.mUid, entry.mPackageName,
                        entry.mOp.getOpCode(), entry.mOp.getForegroundAccessCount(OP_FLAGS_PULLED),
                        entry.mOp.getBackgroundAccessCount(OP_FLAGS_PULLED),
                        entry.mOp.getForegroundRejectCount(OP_FLAGS_PULLED),
                        entry.mOp.getBackgroundRejectCount(OP_FLAGS_PULLED),
                        entry.mOp.getForegroundAccessDuration(OP_FLAGS_PULLED),
                        entry.mOp.getBackgroundAccessDuration(OP_FLAGS_PULLED),
                        mDangerousAppOpsList.contains(entry.mOp.getOpCode()));
            }
            pulledData.add(e);
        }
        if (pulledData.size() > DIMENSION_KEY_SIZE_HARD_LIMIT) {
            int adjustedSamplingRate = constrain(
                    samplingRate * DIMENSION_KEY_SIZE_SOFT_LIMIT / pulledData.size(), 0,
                    samplingRate - 1);
            pulledData.clear();
            return sampleAppOps(pulledData, opsList, atomTag, adjustedSamplingRate);
        }
        return samplingRate;
    }

    private void registerAttributedAppOps() {
        int tagId = FrameworkStatsLog.ATTRIBUTED_APP_OPS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullAttributedAppOpsLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        try {
            AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
            CompletableFuture<HistoricalOps> ops = new CompletableFuture<>();
            HistoricalOpsRequest histOpsRequest =
                    new HistoricalOpsRequest.Builder(0, Long.MAX_VALUE).setFlags(
                            OP_FLAGS_PULLED).build();

            appOps.getHistoricalOps(histOpsRequest, AsyncTask.THREAD_POOL_EXECUTOR, ops::complete);
            HistoricalOps histOps = ops.get(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);

            if (mAppOpsSamplingRate == 0) {
                mContext.getMainThreadHandler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            estimateAppOpsSamplingRate();
                        } catch (Throwable e) {
                            Slog.e(TAG, "AppOps sampling ratio estimation failed: ", e);
                            synchronized (mAttributedAppOpsLock) {
                                mAppOpsSamplingRate = min(mAppOpsSamplingRate, 10);
                            }
                        }
                    }
                }, APP_OPS_SAMPLING_INITIALIZATION_DELAY_MILLIS);
                mAppOpsSamplingRate = 100;
            }

            List<AppOpEntry> opsList =
                    processHistoricalOps(histOps, atomTag, mAppOpsSamplingRate);

            int newSamplingRate = sampleAppOps(pulledData, opsList, atomTag, mAppOpsSamplingRate);

            mAppOpsSamplingRate = min(mAppOpsSamplingRate, newSamplingRate);
        } catch (Throwable t) {
            // TODO: catch exceptions at a more granular level
            Slog.e(TAG, "Could not read appops", t);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void estimateAppOpsSamplingRate() throws Exception {
        int appOpsTargetCollectionSize = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_PERMISSIONS, APP_OPS_TARGET_COLLECTION_SIZE,
                APP_OPS_SIZE_ESTIMATE);
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);

        CompletableFuture<HistoricalOps> ops = new CompletableFuture<>();
        HistoricalOpsRequest histOpsRequest =
                new HistoricalOpsRequest.Builder(
                        Math.max(Instant.now().minus(1, ChronoUnit.DAYS).toEpochMilli(), 0),
                        Long.MAX_VALUE).setFlags(
                        OP_FLAGS_PULLED).build();
        appOps.getHistoricalOps(histOpsRequest, AsyncTask.THREAD_POOL_EXECUTOR, ops::complete);
        HistoricalOps histOps = ops.get(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS);
        List<AppOpEntry> opsList =
                processHistoricalOps(histOps, FrameworkStatsLog.ATTRIBUTED_APP_OPS, 100);

        long estimatedSize = 0;
        int nOps = opsList.size();
        for (int i = 0; i < nOps; i++) {
            AppOpEntry entry = opsList.get(i);
            estimatedSize += 32 + entry.mPackageName.length() + (entry.mAttributionTag == null ? 1
                    : entry.mAttributionTag.length());

        }
        int estimatedSamplingRate = (int) constrain(
                appOpsTargetCollectionSize * 100 / estimatedSize, 0, 100);
        synchronized (mAttributedAppOpsLock) {
            mAppOpsSamplingRate = min(mAppOpsSamplingRate, estimatedSamplingRate);
        }
    }

    private List<AppOpEntry> processHistoricalOps(
            HistoricalOps histOps, int atomTag, int samplingRatio) {
        List<AppOpEntry> opsList = new ArrayList<>();
        for (int uidIdx = 0; uidIdx < histOps.getUidCount(); uidIdx++) {
            final HistoricalUidOps uidOps = histOps.getUidOpsAt(uidIdx);
            final int uid = uidOps.getUid();
            for (int pkgIdx = 0; pkgIdx < uidOps.getPackageCount(); pkgIdx++) {
                final HistoricalPackageOps packageOps = uidOps.getPackageOpsAt(pkgIdx);
                if (atomTag == FrameworkStatsLog.ATTRIBUTED_APP_OPS) {
                    for (int attributionIdx = 0;
                            attributionIdx < packageOps.getAttributedOpsCount(); attributionIdx++) {
                        final AppOpsManager.AttributedHistoricalOps attributedOps =
                                packageOps.getAttributedOpsAt(attributionIdx);
                        for (int opIdx = 0; opIdx < attributedOps.getOpCount(); opIdx++) {
                            final AppOpsManager.HistoricalOp op = attributedOps.getOpAt(opIdx);
                            processHistoricalOp(op, opsList, uid, samplingRatio,
                                    packageOps.getPackageName(), attributedOps.getTag());
                        }
                    }
                } else if (atomTag == FrameworkStatsLog.APP_OPS) {
                    for (int opIdx = 0; opIdx < packageOps.getOpCount(); opIdx++) {
                        final AppOpsManager.HistoricalOp op = packageOps.getOpAt(opIdx);
                        processHistoricalOp(op, opsList, uid, samplingRatio,
                                packageOps.getPackageName(), null);
                    }
                }
            }
        }
        return opsList;
    }

    private void processHistoricalOp(AppOpsManager.HistoricalOp op,
            List<AppOpEntry> opsList, int uid, int samplingRatio, String packageName,
            @Nullable String attributionTag) {
        int firstChar = 0;
        if (attributionTag != null && attributionTag.startsWith(packageName)) {
            firstChar = packageName.length();
            if (firstChar < attributionTag.length() && attributionTag.charAt(firstChar) == '.') {
                firstChar++;
            }
        }
        AppOpEntry entry = new AppOpEntry(packageName,
                attributionTag == null ? null : attributionTag.substring(firstChar), op,
                uid);
        if (entry.mHash < samplingRatio) {
            opsList.add(entry);
        }
    }

    int pullRuntimeAppOpAccessMessageLocked(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
        try {
            AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);

            RuntimeAppOpAccessMessage message = appOps.collectRuntimeAppOpAccessMessage();
            if (message == null) {
                Slog.i(TAG, "No runtime appop access message collected");
                return StatsManager.PULL_SUCCESS;
            }

            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, message.getUid(),
                    message.getPackageName(), "",
                    message.getAttributionTag() == null ? "" : message.getAttributionTag(),
                    message.getMessage(), message.getSamplingStrategy(),
                    AppOpsManager.strOpToOp(message.getOp())));
        } catch (Throwable t) {
            // TODO: catch exceptions at a more granular level
            Slog.e(TAG, "Could not read runtime appop access message", t);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    static void unpackStreamedData(int atomTag, List<StatsEvent> pulledData,
            List<ParcelFileDescriptor> statsFiles) throws IOException {
        InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(statsFiles.get(0));
        int[] len = new int[1];
        byte[] stats = readFully(stream, len);
        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, Arrays.copyOf(stats, len[0])));
    }

    static byte[] readFully(InputStream stream, int[] outLen) throws IOException {
        int pos = 0;
        final int initialAvail = stream.available();
        byte[] data = new byte[initialAvail > 0 ? (initialAvail + 1) : 16384];
        while (true) {
            int amt = stream.read(data, pos, data.length - pos);
            if (DEBUG) {
                Slog.i(TAG, "Read " + amt + " bytes at " + pos + " of avail " + data.length);
            }
            if (amt < 0) {
                if (DEBUG) {
                    Slog.i(TAG, "**** FINISHED READING: pos=" + pos + " len=" + data.length);
                }
                outLen[0] = pos;
                return data;
            }
            pos += amt;
            if (pos >= data.length) {
                byte[] newData = new byte[pos + 16384];
                if (DEBUG) {
                    Slog.i(TAG, "Copying " + pos + " bytes to new array len " + newData.length);
                }
                System.arraycopy(data, 0, newData, 0, pos);
                data = newData;
            }
        }
    }

    private void registerNotificationRemoteViews() {
        int tagId = FrameworkStatsLog.NOTIFICATION_REMOTE_VIEWS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullNotificationRemoteViewsLocked(int atomTag, List<StatsEvent> pulledData) {
        INotificationManager notificationManagerService = getINotificationManagerService();
        if (notificationManagerService == null) {
            return StatsManager.PULL_SKIP;
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // determine last pull tine. Copy file trick from pullProcStats?
            long wallClockNanos = SystemClock.currentTimeMicro() * 1000L;
            long lastNotificationStatsNs = wallClockNanos -
                    TimeUnit.NANOSECONDS.convert(1, TimeUnit.DAYS);

            List<ParcelFileDescriptor> statsFiles = new ArrayList<>();
            notificationManagerService.pullStats(lastNotificationStatsNs,
                    NotificationManagerService.REPORT_REMOTE_VIEWS, true, statsFiles);
            if (statsFiles.size() != 1) {
                return StatsManager.PULL_SKIP;
            }
            unpackStreamedData(atomTag, pulledData, statsFiles);
        } catch (IOException e) {
            Slog.e(TAG, "Getting notistats failed: ", e);
            return StatsManager.PULL_SKIP;
        } catch (RemoteException e) {
            Slog.e(TAG, "Getting notistats failed: ", e);
            return StatsManager.PULL_SKIP;
        } catch (SecurityException e) {
            Slog.e(TAG, "Getting notistats failed: ", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDangerousPermissionStateSampled() {
        int tagId = FrameworkStatsLog.DANGEROUS_PERMISSION_STATE_SAMPLED;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerBatteryLevel() {
        int tagId = FrameworkStatsLog.BATTERY_LEVEL;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerRemainingBatteryCapacity() {
        int tagId = FrameworkStatsLog.REMAINING_BATTERY_CAPACITY;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerFullBatteryCapacity() {
        int tagId = FrameworkStatsLog.FULL_BATTERY_CAPACITY;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerBatteryVoltage() {
        int tagId = FrameworkStatsLog.BATTERY_VOLTAGE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerBatteryCycleCount() {
        int tagId = FrameworkStatsLog.BATTERY_CYCLE_COUNT;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerBatteryHealth() {
        int tagId = FrameworkStatsLog.BATTERY_HEALTH;
        mStatsManager.setPullAtomCallback(tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR, mStatsCallbackImpl);
    }

    @GuardedBy("mHealthHalLock")
    private int pullHealthHalLocked(int atomTag, List<StatsEvent> pulledData) {
        if (mHealthService == null) {
            return StatsManager.PULL_SKIP;
        }
        android.hardware.health.HealthInfo healthInfo;
        try {
            healthInfo = mHealthService.getHealthInfo();
        } catch (RemoteException | IllegalStateException e) {
            return StatsManager.PULL_SKIP;
        }
        if (healthInfo == null) {
            return StatsManager.PULL_SKIP;
        }

        int pulledValue;
        switch (atomTag) {
            case FrameworkStatsLog.BATTERY_LEVEL:
                pulledValue = healthInfo.batteryLevel;
                break;
            case FrameworkStatsLog.REMAINING_BATTERY_CAPACITY:
                pulledValue = healthInfo.batteryChargeCounterUah;
                break;
            case FrameworkStatsLog.FULL_BATTERY_CAPACITY:
                pulledValue = healthInfo.batteryFullChargeUah;
                break;
            case FrameworkStatsLog.BATTERY_VOLTAGE:
                pulledValue = healthInfo.batteryVoltageMillivolts;
                break;
            case FrameworkStatsLog.BATTERY_CYCLE_COUNT:
                pulledValue = healthInfo.batteryCycleCount;
                break;
            case FrameworkStatsLog.BATTERY_HEALTH:
                android.hardware.health.BatteryHealthData bhd;
                try {
                    bhd = mHealthService.getBatteryHealthData();
                } catch (RemoteException | IllegalStateException e) {
                    return StatsManager.PULL_SKIP;
                }
                if (bhd == null) {
                    return StatsManager.PULL_SKIP;
                }

                StatsEvent batteryHealthEvent;
                try {
                    BatteryProperty chargeStatusProperty = new BatteryProperty();
                    BatteryProperty chargePolicyProperty = new BatteryProperty();

                    if (0 > mHealthService.getProperty(
                                BatteryManager.BATTERY_PROPERTY_STATUS, chargeStatusProperty)) {
                        return StatsManager.PULL_SKIP;
                    }
                    if (0 > mHealthService.getProperty(
                                BatteryManager.BATTERY_PROPERTY_CHARGING_POLICY,
                                chargePolicyProperty)) {
                        return StatsManager.PULL_SKIP;
                    }
                    int chargeStatus = (int) chargeStatusProperty.getLong();
                    int chargePolicy = (int) chargePolicyProperty.getLong();
                    batteryHealthEvent = BatteryHealthUtility.buildStatsEvent(
                            atomTag, bhd, chargeStatus, chargePolicy);
                    pulledData.add(batteryHealthEvent);

                    return StatsManager.PULL_SUCCESS;
                } catch (RemoteException | IllegalStateException e) {
                    Slog.e(TAG, "Failed to add pulled data", e);
                } catch (NoSuchAlgorithmException e) {
                    Slog.e(TAG, "Could not find message digest algorithm", e);
                }
                return StatsManager.PULL_SKIP;
            default:
                return StatsManager.PULL_SKIP;
        }
        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, pulledValue));
        return StatsManager.PULL_SUCCESS;
    }

    private void registerSettingsStats() {
        int tagId = FrameworkStatsLog.SETTING_SNAPSHOT;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullSettingsStatsLocked(int atomTag, List<StatsEvent> pulledData) {
        UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager == null) {
            return StatsManager.PULL_SKIP;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            for (UserInfo user : userManager.getUsers()) {
                final int userId = user.getUserHandle().getIdentifier();

                if (userId == UserHandle.USER_SYSTEM) {
                    pulledData.addAll(SettingsStatsUtil.logGlobalSettings(mContext, atomTag,
                            UserHandle.USER_SYSTEM));
                }
                pulledData.addAll(SettingsStatsUtil.logSystemSettings(mContext, atomTag, userId));
                pulledData.addAll(SettingsStatsUtil.logSecureSettings(mContext, atomTag, userId));
            }
        } catch (Exception e) {
            Slog.e(TAG, "failed to pullSettingsStats", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerInstalledIncrementalPackages() {
        int tagId = FrameworkStatsLog.INSTALLED_INCREMENTAL_PACKAGE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullInstalledIncrementalPackagesLocked(int atomTag, List<StatsEvent> pulledData) {
        final PackageManager pm = mContext.getPackageManager();
        final PackageManagerInternal pmIntenral =
                LocalServices.getService(PackageManagerInternal.class);
        if (!pm.hasSystemFeature(PackageManager.FEATURE_INCREMENTAL_DELIVERY)) {
            // Incremental is not enabled on this device. The result list will be empty.
            return StatsManager.PULL_SUCCESS;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            final int[] userIds = LocalServices.getService(UserManagerInternal.class).getUserIds();
            for (int userId : userIds) {
                final List<PackageInfo> installedPackages = pm.getInstalledPackagesAsUser(
                        0, userId);
                for (PackageInfo pi : installedPackages) {
                    if (IncrementalManager.isIncrementalPath(
                            pi.applicationInfo.getBaseCodePath())) {
                        final IncrementalStatesInfo info = pmIntenral.getIncrementalStatesInfo(
                                pi.packageName, SYSTEM_UID, userId);
                        pulledData.add(
                                FrameworkStatsLog.buildStatsEvent(atomTag, pi.applicationInfo.uid,
                                        info.isLoading(), info.getLoadingCompletedTime()));
                    }
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "failed to pullInstalledIncrementalPackagesLocked", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerKeystoreStorageStats() {
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.KEYSTORE2_STORAGE_STATS,
                null, // use default PullAtomMetadata values,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl);
    }

    private void registerKeystoreKeyCreationWithGeneralInfo() {
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_GENERAL_INFO,
                null, // use default PullAtomMetadata values,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl);
    }

    private void registerKeystoreKeyCreationWithAuthInfo() {
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_AUTH_INFO,
                null, // use default PullAtomMetadata values,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl);
    }

    private void registerKeystoreKeyCreationWithPurposeModesInfo() {
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_PURPOSE_AND_MODES_INFO,
                null, // use default PullAtomMetadata values,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl);
    }

    private void registerKeystoreAtomWithOverflow() {
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.KEYSTORE2_ATOM_WITH_OVERFLOW,
                null, // use default PullAtomMetadata values,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl);
    }

    private void registerKeystoreKeyOperationWithPurposeAndModesInfo() {
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.KEYSTORE2_KEY_OPERATION_WITH_PURPOSE_AND_MODES_INFO,
                null, // use default PullAtomMetadata values,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl);
    }

    private void registerKeystoreKeyOperationWithGeneralInfo() {
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.KEYSTORE2_KEY_OPERATION_WITH_GENERAL_INFO,
                null, // use default PullAtomMetadata values,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl);
    }

    private void registerRkpErrorStats() {
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.RKP_ERROR_STATS,
                null, // use default PullAtomMetadata values,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl);
    }

    private void registerKeystoreCrashStats() {
        mStatsManager.setPullAtomCallback(
                FrameworkStatsLog.KEYSTORE2_CRASH_STATS,
                null, // use default PullAtomMetadata values,
                DIRECT_EXECUTOR,
                mStatsCallbackImpl);
    }

    private void registerAccessibilityShortcutStats() {
        int tagId = FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_STATS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerAccessibilityFloatingMenuStats() {
        int tagId = FrameworkStatsLog.ACCESSIBILITY_FLOATING_MENU_STATS;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerMediaCapabilitiesStats() {
        int tagId = FrameworkStatsLog.MEDIA_CAPABILITIES;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int parseKeystoreStorageStats(KeystoreAtom[] atoms, List<StatsEvent> pulledData) {
        for (KeystoreAtom atomWrapper : atoms) {
            if (atomWrapper.payload.getTag() != KeystoreAtomPayload.storageStats) {
                return StatsManager.PULL_SKIP;
            }
            StorageStats atom = atomWrapper.payload.getStorageStats();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.KEYSTORE2_STORAGE_STATS, atom.storage_type,
                    atom.size, atom.unused_size));
        }
        return StatsManager.PULL_SUCCESS;
    }

    int parseKeystoreKeyCreationWithGeneralInfo(KeystoreAtom[] atoms, List<StatsEvent> pulledData) {
        for (KeystoreAtom atomWrapper : atoms) {
            if (atomWrapper.payload.getTag()
                    != KeystoreAtomPayload.keyCreationWithGeneralInfo) {
                return StatsManager.PULL_SKIP;
            }
            KeyCreationWithGeneralInfo atom = atomWrapper.payload.getKeyCreationWithGeneralInfo();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_GENERAL_INFO, atom.algorithm,
                    atom.key_size, atom.ec_curve, atom.key_origin, atom.error_code,
                    atom.attestation_requested, atomWrapper.count));
        }
        return StatsManager.PULL_SUCCESS;
    }

    int parseKeystoreKeyCreationWithAuthInfo(KeystoreAtom[] atoms, List<StatsEvent> pulledData) {
        for (KeystoreAtom atomWrapper : atoms) {
            if (atomWrapper.payload.getTag() != KeystoreAtomPayload.keyCreationWithAuthInfo) {
                return StatsManager.PULL_SKIP;
            }
            KeyCreationWithAuthInfo atom = atomWrapper.payload.getKeyCreationWithAuthInfo();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_AUTH_INFO, atom.user_auth_type,
                    atom.log10_auth_key_timeout_seconds, atom.security_level, atomWrapper.count));
        }
        return StatsManager.PULL_SUCCESS;
    }


    int parseKeystoreKeyCreationWithPurposeModesInfo(KeystoreAtom[] atoms,
            List<StatsEvent> pulledData) {
        for (KeystoreAtom atomWrapper : atoms) {
            if (atomWrapper.payload.getTag()
                    != KeystoreAtomPayload.keyCreationWithPurposeAndModesInfo) {
                return StatsManager.PULL_SKIP;
            }
            KeyCreationWithPurposeAndModesInfo atom =
                    atomWrapper.payload.getKeyCreationWithPurposeAndModesInfo();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_PURPOSE_AND_MODES_INFO,
                    atom.algorithm, atom.purpose_bitmap,
                    atom.padding_mode_bitmap, atom.digest_bitmap, atom.block_mode_bitmap,
                    atomWrapper.count));
        }
        return StatsManager.PULL_SUCCESS;
    }

    int parseKeystoreAtomWithOverflow(KeystoreAtom[] atoms, List<StatsEvent> pulledData) {
        for (KeystoreAtom atomWrapper : atoms) {
            if (atomWrapper.payload.getTag()
                    != KeystoreAtomPayload.keystore2AtomWithOverflow) {
                return StatsManager.PULL_SKIP;
            }
            Keystore2AtomWithOverflow atom = atomWrapper.payload.getKeystore2AtomWithOverflow();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.KEYSTORE2_ATOM_WITH_OVERFLOW, atom.atom_id,
                    atomWrapper.count));
        }
        return StatsManager.PULL_SUCCESS;
    }

    int parseKeystoreKeyOperationWithPurposeModesInfo(KeystoreAtom[] atoms,
            List<StatsEvent> pulledData) {
        for (KeystoreAtom atomWrapper : atoms) {
            if (atomWrapper.payload.getTag()
                    != KeystoreAtomPayload.keyOperationWithPurposeAndModesInfo) {
                return StatsManager.PULL_SKIP;
            }
            KeyOperationWithPurposeAndModesInfo atom =
                    atomWrapper.payload.getKeyOperationWithPurposeAndModesInfo();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.KEYSTORE2_KEY_OPERATION_WITH_PURPOSE_AND_MODES_INFO,
                    atom.purpose, atom.padding_mode_bitmap, atom.digest_bitmap,
                    atom.block_mode_bitmap, atomWrapper.count));
        }
        return StatsManager.PULL_SUCCESS;
    }

    int parseKeystoreKeyOperationWithGeneralInfo(KeystoreAtom[] atoms,
            List<StatsEvent> pulledData) {
        for (KeystoreAtom atomWrapper : atoms) {
            if (atomWrapper.payload.getTag()
                    != KeystoreAtomPayload.keyOperationWithGeneralInfo) {
                return StatsManager.PULL_SKIP;
            }
            KeyOperationWithGeneralInfo atom = atomWrapper.payload.getKeyOperationWithGeneralInfo();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.KEYSTORE2_KEY_OPERATION_WITH_GENERAL_INFO, atom.outcome,
                    atom.error_code, atom.key_upgraded, atom.security_level, atomWrapper.count));
        }
        return StatsManager.PULL_SUCCESS;
    }

    int parseRkpErrorStats(KeystoreAtom[] atoms,
            List<StatsEvent> pulledData) {
        for (KeystoreAtom atomWrapper : atoms) {
            if (atomWrapper.payload.getTag() != KeystoreAtomPayload.rkpErrorStats) {
                return StatsManager.PULL_SKIP;
            }
            RkpErrorStats atom = atomWrapper.payload.getRkpErrorStats();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.RKP_ERROR_STATS, atom.rkpError, atomWrapper.count,
                    atom.security_level));
        }
        return StatsManager.PULL_SUCCESS;
    }

    int parseKeystoreCrashStats(KeystoreAtom[] atoms,
            List<StatsEvent> pulledData) {
        for (KeystoreAtom atomWrapper : atoms) {
            if (atomWrapper.payload.getTag() != KeystoreAtomPayload.crashStats) {
                return StatsManager.PULL_SKIP;
            }
            CrashStats atom = atomWrapper.payload.getCrashStats();
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    FrameworkStatsLog.KEYSTORE2_CRASH_STATS, atom.count_of_crash_events));
        }
        return StatsManager.PULL_SUCCESS;
    }

    int pullKeystoreAtoms(int atomTag, List<StatsEvent> pulledData) {
        IKeystoreMetrics keystoreMetricsService = getIKeystoreMetricsService();
        if (keystoreMetricsService == null) {
            Slog.w(TAG, "Keystore service is null");
            return StatsManager.PULL_SKIP;
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            KeystoreAtom[] atoms = keystoreMetricsService.pullMetrics(atomTag);
            switch (atomTag) {
                case FrameworkStatsLog.KEYSTORE2_STORAGE_STATS:
                    return parseKeystoreStorageStats(atoms, pulledData);
                case FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_GENERAL_INFO:
                    return parseKeystoreKeyCreationWithGeneralInfo(atoms, pulledData);
                case FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_AUTH_INFO:
                    return parseKeystoreKeyCreationWithAuthInfo(atoms, pulledData);
                case FrameworkStatsLog.KEYSTORE2_KEY_CREATION_WITH_PURPOSE_AND_MODES_INFO:
                    return parseKeystoreKeyCreationWithPurposeModesInfo(atoms, pulledData);
                case FrameworkStatsLog.KEYSTORE2_ATOM_WITH_OVERFLOW:
                    return parseKeystoreAtomWithOverflow(atoms, pulledData);
                case FrameworkStatsLog.KEYSTORE2_KEY_OPERATION_WITH_PURPOSE_AND_MODES_INFO:
                    return parseKeystoreKeyOperationWithPurposeModesInfo(atoms, pulledData);
                case FrameworkStatsLog.KEYSTORE2_KEY_OPERATION_WITH_GENERAL_INFO:
                    return parseKeystoreKeyOperationWithGeneralInfo(atoms, pulledData);
                case FrameworkStatsLog.RKP_ERROR_STATS:
                    return parseRkpErrorStats(atoms, pulledData);
                case FrameworkStatsLog.KEYSTORE2_CRASH_STATS:
                    return parseKeystoreCrashStats(atoms, pulledData);
                default:
                    Slog.w(TAG, "Unsupported keystore atom: " + atomTag);
                    return StatsManager.PULL_SKIP;
            }
        } catch (RemoteException e) {
            // Should not happen.
            Slog.e(TAG, "Disconnected from keystore service. Cannot pull.", e);
            return StatsManager.PULL_SKIP;
        } catch (ServiceSpecificException e) {
            Slog.e(TAG, "pulling keystore metrics failed", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    /**
     * Pulls ACCESSIBILITY_SHORTCUT_STATS atom
     */
    int pullAccessibilityShortcutStatsLocked(List<StatsEvent> pulledData) {
        UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager == null) {
            return StatsManager.PULL_SKIP;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            final ContentResolver resolver = mContext.getContentResolver();
            for (UserInfo userInfo : userManager.getUsers()) {
                final int userId = userInfo.getUserHandle().getIdentifier();

                if (isAccessibilityShortcutUser(mContext, userId)) {
                    final int software_shortcut_type = convertToAccessibilityShortcutType(
                            Settings.Secure.getIntForUser(resolver,
                                    Settings.Secure.ACCESSIBILITY_BUTTON_MODE, 0, userId));
                    final String software_shortcut_list = Settings.Secure.getStringForUser(resolver,
                            Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, userId);
                    final int software_shortcut_service_num = countAccessibilityServices(
                            software_shortcut_list);

                    final String hardware_shortcut_list = Settings.Secure.getStringForUser(resolver,
                            Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, userId);
                    final int hardware_shortcut_service_num = countAccessibilityServices(
                            hardware_shortcut_list);

                    final String qs_shortcut_list = Settings.Secure.getStringForUser(resolver,
                            Settings.Secure.ACCESSIBILITY_QS_TARGETS, userId);
                    final boolean qs_shortcut_enabled = !TextUtils.isEmpty(qs_shortcut_list);

                    // only allow magnification to use it for now
                    final int triple_tap_service_num = Settings.Secure.getIntForUser(resolver,
                            Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED, 0, userId);
                    pulledData.add(FrameworkStatsLog.buildStatsEvent(
                            FrameworkStatsLog.ACCESSIBILITY_SHORTCUT_STATS,
                            software_shortcut_type, software_shortcut_service_num,
                            ACCESSIBILITY_SHORTCUT_STATS__HARDWARE_SHORTCUT_TYPE__VOLUME_KEY,
                            hardware_shortcut_service_num,
                            ACCESSIBILITY_SHORTCUT_STATS__GESTURE_SHORTCUT_TYPE__TRIPLE_TAP,
                            triple_tap_service_num,
                            ACCESSIBILITY_SHORTCUT_STATS__QS_SHORTCUT_TYPE__QUICK_SETTINGS,
                            qs_shortcut_enabled));
                }
            }
        } catch (RuntimeException e) {
            Slog.e(TAG, "pulling accessibility shortcuts stats failed at getUsers", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    int pullAccessibilityFloatingMenuStatsLocked(int atomTag, List<StatsEvent> pulledData) {
        UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager == null) {
            return StatsManager.PULL_SKIP;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            final ContentResolver resolver = mContext.getContentResolver();
            final int defaultSize = 0;
            final int defaultIconType = 0;
            final int defaultFadeEnabled = 1;
            final float defaultOpacity = 0.55f;

            for (UserInfo userInfo : userManager.getUsers()) {
                final int userId = userInfo.getUserHandle().getIdentifier();

                if (isAccessibilityFloatingMenuUser(mContext, userId)) {
                    final int size = Settings.Secure.getIntForUser(resolver,
                            Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE, defaultSize, userId);
                    final int type = Settings.Secure.getIntForUser(resolver,
                            Settings.Secure.ACCESSIBILITY_FLOATING_MENU_ICON_TYPE,
                            defaultIconType, userId);
                    final boolean fadeEnabled = (Settings.Secure.getIntForUser(resolver,
                            Settings.Secure.ACCESSIBILITY_FLOATING_MENU_FADE_ENABLED,
                            defaultFadeEnabled, userId)) == 1;
                    final float opacity = Settings.Secure.getFloatForUser(resolver,
                            Settings.Secure.ACCESSIBILITY_FLOATING_MENU_OPACITY,
                            defaultOpacity, userId);

                    pulledData.add(
                            FrameworkStatsLog.buildStatsEvent(atomTag, size, type, fadeEnabled,
                                    opacity));
                }
            }
        } catch (RuntimeException e) {
            Slog.e(TAG, "pulling accessibility floating menu stats failed at getUsers", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    int pullMediaCapabilitiesStats(int atomTag, List<StatsEvent> pulledData) {
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return StatsManager.PULL_SKIP;
        }
        AudioManager audioManager = mContext.getSystemService(AudioManager.class);
        if (audioManager == null) {
            return StatsManager.PULL_SKIP;
        }

        // get the surround sound metrics information
        Map<Integer, Boolean> surroundEncodingsMap = audioManager.getSurroundFormats();
        byte[] surroundEncodings = toBytes(new ArrayList(surroundEncodingsMap.keySet()));
        byte[] sinkSurroundEncodings = toBytes(audioManager.getReportedSurroundFormats());
        List<Integer> disabledSurroundEncodingsList = new ArrayList<>();
        List<Integer> enabledSurroundEncodingsList = new ArrayList<>();
        for (int surroundEncoding : surroundEncodingsMap.keySet()) {
            if (!audioManager.isSurroundFormatEnabled(surroundEncoding)) {
                disabledSurroundEncodingsList.add(surroundEncoding);
            } else {
                enabledSurroundEncodingsList.add(surroundEncoding);
            }
        }
        byte[] disabledSurroundEncodings = toBytes(disabledSurroundEncodingsList);
        byte[] enabledSurroundEncodings = toBytes(enabledSurroundEncodingsList);
        int surroundOutputMode = audioManager.getEncodedSurroundMode();

        DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        // get the display capabilities metrics information
        Display.HdrCapabilities hdrCapabilities = display.getHdrCapabilities();
        byte[] sinkHdrFormats = new byte[]{};
        if (hdrCapabilities != null) {
            sinkHdrFormats = toBytes(hdrCapabilities.getSupportedHdrTypes());
        }
        byte[] sinkDisplayModes = toBytes(display.getSupportedModes());
        int hdcpLevel = -1;
        List<UUID> uuids = MediaDrm.getSupportedCryptoSchemes();
        try {
            if (!uuids.isEmpty()) {
                MediaDrm mediaDrm = new MediaDrm(uuids.get(0));
                hdcpLevel = mediaDrm.getConnectedHdcpLevel();
            }
        } catch (UnsupportedSchemeException exception) {
            Slog.e(TAG, "pulling hdcp level failed.", exception);
            hdcpLevel = -1;
        }

        // get the display settings metrics information
        int matchContentFrameRateUserPreference =
                displayManager.getMatchContentFrameRateUserPreference();
        byte[] userDisabledHdrTypes = toBytes(displayManager.getUserDisabledHdrTypes());
        Display.Mode userPreferredDisplayMode =
                displayManager.getGlobalUserPreferredDisplayMode();
        int userPreferredWidth = userPreferredDisplayMode != null
                ? userPreferredDisplayMode.getPhysicalWidth() : -1;
        int userPreferredHeight = userPreferredDisplayMode != null
                ? userPreferredDisplayMode.getPhysicalHeight() : -1;
        float userPreferredRefreshRate = userPreferredDisplayMode != null
                ? userPreferredDisplayMode.getRefreshRate() : 0.0f;
        boolean hasUserDisabledAllm = false;
        try {
            hasUserDisabledAllm = Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.MINIMAL_POST_PROCESSING_ALLOWED,
                    1) == 0;
        } catch (Settings.SettingNotFoundException exception) {
            Slog.e(
                    TAG, "unable to find setting for MINIMAL_POST_PROCESSING_ALLOWED.",
                    exception);
            hasUserDisabledAllm = false;
        }

        pulledData.add(
                FrameworkStatsLog.buildStatsEvent(
                        atomTag, surroundEncodings, sinkSurroundEncodings,
                        disabledSurroundEncodings, enabledSurroundEncodings, surroundOutputMode,
                        sinkHdrFormats, sinkDisplayModes, hdcpLevel,
                        matchContentFrameRateUserPreference, userDisabledHdrTypes,
                        userPreferredWidth, userPreferredHeight, userPreferredRefreshRate,
                        hasUserDisabledAllm));

        return StatsManager.PULL_SUCCESS;
    }

    private void registerPendingIntentsPerPackagePuller() {
        int tagId = FrameworkStatsLog.PENDING_INTENTS_PER_PACKAGE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private int pullHdrCapabilities(int atomTag, List<StatsEvent> pulledData) {
        DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);

        int hdrConversionMode = displayManager.getHdrConversionMode().getConversionMode();
        int preferredHdrType = displayManager.getHdrConversionMode().getPreferredHdrOutputType();
        boolean userDisabledHdrConversion = hdrConversionMode == HDR_CONVERSION_PASSTHROUGH;
        int forceHdrFormat = preferredHdrType == HDR_TYPE_INVALID ? 0 : preferredHdrType;
        boolean hasDolbyVisionIssue = hasDolbyVisionIssue(display);
        byte[] hdrOutputTypes = toBytes(displayManager.getSupportedHdrOutputTypes());
        boolean hdrOutputControlSupported = hdrConversionMode != HDR_CONVERSION_UNSUPPORTED;

        pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag, hdrOutputTypes,
                userDisabledHdrConversion, forceHdrFormat, hasDolbyVisionIssue,
                hdrOutputControlSupported));

        return StatsManager.PULL_SUCCESS;
    }

    private int pullCachedAppsHighWatermark(int atomTag, List<StatsEvent> pulledData) {
        pulledData.add((StatsEvent) LocalServices.getService(ActivityManagerInternal.class)
                .getCachedAppsHighWatermarkStats(atomTag, true));
        return StatsManager.PULL_SUCCESS;
    }

    private boolean hasDolbyVisionIssue(Display display) {
        AtomicInteger modesSupportingDolbyVision = new AtomicInteger();
        Arrays.stream(display.getSupportedModes())
                .map(Display.Mode::getSupportedHdrTypes)
                .filter(types -> Arrays.stream(types).anyMatch(hdrType -> hdrType == DOLBY_VISION))
                .forEach(ignored -> modesSupportingDolbyVision.incrementAndGet());

        if (modesSupportingDolbyVision.get() != 0
                && modesSupportingDolbyVision.get() < display.getSupportedModes().length) {
            return true;
        }

        return false;
    }

    private int pullPendingIntentsPerPackage(int atomTag, List<StatsEvent> pulledData) {
        List<PendingIntentStats> pendingIntentStats =
                LocalServices.getService(ActivityManagerInternal.class).getPendingIntentStats();
        for (PendingIntentStats stats : pendingIntentStats) {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(
                    atomTag, stats.uid, stats.count, stats.sizeKb));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerPinnerServiceStats() {
        int tagId = FrameworkStatsLog.PINNED_FILE_SIZES_PER_PACKAGE;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerHdrCapabilitiesPuller() {
        int tagId = FrameworkStatsLog.HDR_CAPABILITIES;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    private void registerCachedAppsHighWatermarkPuller() {
        final int tagId = FrameworkStatsLog.CACHED_APPS_HIGH_WATERMARK;
        mStatsManager.setPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                mStatsCallbackImpl
        );
    }

    int pullSystemServerPinnerStats(int atomTag, List<StatsEvent> pulledData) {
        PinnerService pinnerService = LocalServices.getService(PinnerService.class);
        List<PinnedFileStats> pinnedFileStats = pinnerService.dumpDataForStatsd();
        for (PinnedFileStats pfstats : pinnedFileStats) {
            pulledData.add(FrameworkStatsLog.buildStatsEvent(atomTag,
                    pfstats.uid, pfstats.filename, pfstats.sizeKb));
        }
        return StatsManager.PULL_SUCCESS;
    }

    private byte[] toBytes(List<Integer> audioEncodings) {
        ProtoOutputStream protoOutputStream = new ProtoOutputStream();
        for (int audioEncoding : audioEncodings) {
            protoOutputStream.write(
                    ProtoOutputStream.FIELD_COUNT_REPEATED | ProtoOutputStream.FIELD_TYPE_ENUM | 1,
                    audioEncoding);
        }
        return protoOutputStream.getBytes();
    }

    private byte[] toBytes(int[] array) {
        ProtoOutputStream protoOutputStream = new ProtoOutputStream();
        for (int element : array) {
            protoOutputStream.write(
                    ProtoOutputStream.FIELD_COUNT_REPEATED | ProtoOutputStream.FIELD_TYPE_ENUM | 1,
                    element);
        }
        return protoOutputStream.getBytes();
    }

    private byte[] toBytes(Display.Mode[] displayModes) {
        Map<Integer, Integer> modeGroupIds = createModeGroups(displayModes);
        ProtoOutputStream protoOutputStream = new ProtoOutputStream();
        for (Display.Mode element : displayModes) {
            ProtoOutputStream protoOutputStreamMode = new ProtoOutputStream();
            protoOutputStreamMode.write(
                    ProtoOutputStream.FIELD_COUNT_SINGLE | ProtoOutputStream.FIELD_TYPE_INT32 | 1,
                    element.getPhysicalHeight());
            protoOutputStreamMode.write(
                    ProtoOutputStream.FIELD_COUNT_SINGLE | ProtoOutputStream.FIELD_TYPE_INT32 | 2,
                    element.getPhysicalWidth());
            protoOutputStreamMode.write(
                    ProtoOutputStream.FIELD_COUNT_SINGLE | ProtoOutputStream.FIELD_TYPE_FLOAT | 3,
                    element.getRefreshRate());
            protoOutputStreamMode.write(
                    ProtoOutputStream.FIELD_COUNT_SINGLE | ProtoOutputStream.FIELD_TYPE_INT32 | 4,
                    modeGroupIds.get(element.getModeId()));
            protoOutputStream.write(
                    ProtoOutputStream.FIELD_COUNT_REPEATED
                            | ProtoOutputStream.FIELD_TYPE_MESSAGE | 1,
                    protoOutputStreamMode.getBytes());
        }
        return protoOutputStream.getBytes();
    }

    // Returns map modeId -> groupId such that all modes with the same group have alternative
    // refresh rates
    private Map<Integer, Integer> createModeGroups(Display.Mode[] supportedModes) {
        Map<Integer, Integer> modeGroupIds = new ArrayMap<>();
        int groupId = 1;
        for (Display.Mode mode : supportedModes) {
            if (modeGroupIds.containsKey(mode.getModeId())) {
                continue;
            }
            modeGroupIds.put(mode.getModeId(), groupId);
            for (float refreshRate : mode.getAlternativeRefreshRates()) {
                int alternativeModeId = findModeId(supportedModes, mode.getPhysicalWidth(),
                        mode.getPhysicalHeight(), refreshRate);
                if (alternativeModeId != -1 && !modeGroupIds.containsKey(alternativeModeId)) {
                    modeGroupIds.put(alternativeModeId, groupId);
                }
            }
            groupId++;
        }
        return modeGroupIds;
    }

    private int findModeId(Display.Mode[] modes, int width, int height, float refreshRate) {
        for (Display.Mode mode : modes) {
            if (mode.matches(width, height, refreshRate)) {
                return mode.getModeId();
            }
        }
        return -1;
    }

    /**
     * Counts how many accessibility services (including features) there are in the colon-separated
     * string list.
     *
     * @param semicolonList colon-separated string, it should be
     *                      {@link Settings.Secure#ACCESSIBILITY_BUTTON_TARGETS} or
     *                      {@link Settings.Secure#ACCESSIBILITY_SHORTCUT_TARGET_SERVICE}.
     * @return The number of accessibility services
     */
    private int countAccessibilityServices(String semicolonList) {
        if (TextUtils.isEmpty(semicolonList)) {
            return 0;
        }
        final int semiColonNums = (int) semicolonList.chars().filter(ch -> ch == ':').count();
        return TextUtils.isEmpty(semicolonList) ? 0 : semiColonNums + 1;
    }

    private boolean isAccessibilityShortcutUser(Context context, @UserIdInt int userId) {
        final ContentResolver resolver = context.getContentResolver();

        final String software_shortcut_list = Settings.Secure.getStringForUser(resolver,
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, userId);
        final String hardware_shortcut_list = Settings.Secure.getStringForUser(resolver,
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, userId);
        final String qs_shortcut_list = Settings.Secure.getStringForUser(resolver,
                Settings.Secure.ACCESSIBILITY_QS_TARGETS, userId);
        final boolean hardware_shortcut_dialog_shown = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN, 0, userId) == 1;
        final boolean software_shortcut_enabled = !TextUtils.isEmpty(software_shortcut_list);
        final boolean hardware_shortcut_enabled =
                hardware_shortcut_dialog_shown && !TextUtils.isEmpty(hardware_shortcut_list);
        final boolean qs_shortcut_enabled = !TextUtils.isEmpty(qs_shortcut_list);
        final boolean triple_tap_shortcut_enabled = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED, 0, userId) == 1;

        return software_shortcut_enabled || hardware_shortcut_enabled
                || triple_tap_shortcut_enabled || qs_shortcut_enabled;
    }

    private boolean isAccessibilityFloatingMenuUser(Context context, @UserIdInt int userId) {
        final ContentResolver resolver = context.getContentResolver();
        final int mode = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.ACCESSIBILITY_BUTTON_MODE, 0, userId);
        final String software_string = Settings.Secure.getStringForUser(resolver,
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, userId);

        return (mode == Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU)
                && !TextUtils.isEmpty(software_string);
    }

    private int convertToAccessibilityShortcutType(int shortcutType) {
        switch (shortcutType) {
            case Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR:
                return ACCESSIBILITY_SHORTCUT_STATS__SOFTWARE_SHORTCUT_TYPE__A11Y_BUTTON;
            case Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU:
                return ACCESSIBILITY_SHORTCUT_STATS__SOFTWARE_SHORTCUT_TYPE__A11Y_FLOATING_MENU;
            case Settings.Secure.ACCESSIBILITY_BUTTON_MODE_GESTURE:
                return ACCESSIBILITY_SHORTCUT_STATS__SOFTWARE_SHORTCUT_TYPE__A11Y_GESTURE;
            default:
                return ACCESSIBILITY_SHORTCUT_STATS__SOFTWARE_SHORTCUT_TYPE__UNKNOWN_TYPE;
        }
    }

    // Thermal event received from vendor thermal management subsystem
    private static final class ThermalEventListener extends IThermalEventListener.Stub {
        @Override
        public void notifyThrottling(Temperature temp) {
            FrameworkStatsLog.write(FrameworkStatsLog.THERMAL_THROTTLING_SEVERITY_STATE_CHANGED,
                    temp.getType(), temp.getName(), (int) (temp.getValue() * 10), temp.getStatus());
        }
    }

    private static final class ConnectivityStatsCallback extends
            ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            FrameworkStatsLog.write(FrameworkStatsLog.CONNECTIVITY_STATE_CHANGED,
                    network.getNetId(),
                    FrameworkStatsLog.CONNECTIVITY_STATE_CHANGED__STATE__CONNECTED);
        }

        @Override
        public void onLost(Network network) {
            FrameworkStatsLog.write(FrameworkStatsLog.CONNECTIVITY_STATE_CHANGED,
                    network.getNetId(),
                    FrameworkStatsLog.CONNECTIVITY_STATE_CHANGED__STATE__DISCONNECTED);
        }
    }

    private final class StatsSubscriptionsListener
            extends SubscriptionManager.OnSubscriptionsChangedListener {
        @NonNull
        private final SubscriptionManager mSm;

        StatsSubscriptionsListener(@NonNull SubscriptionManager sm) {
            mSm = sm;
        }

        @Override
        public void onSubscriptionsChanged() {
            synchronized (mDataBytesTransferLock) {
                onSubscriptionsChangedLocked();
            }
        }

        @GuardedBy("mDataBytesTransferLock")
        private void onSubscriptionsChangedLocked() {
            final List<SubscriptionInfo> currentSubs = mSm.getCompleteActiveSubscriptionInfoList();
            for (final SubscriptionInfo sub : currentSubs) {
                final SubInfo match = CollectionUtils.find(mHistoricalSubs,
                        (SubInfo it) -> it.subId == sub.getSubscriptionId());
                // SubInfo exists, ignore.
                if (match != null) continue;

                // Ignore if no valid mcc, mnc, imsi, carrierId.
                final int subId = sub.getSubscriptionId();
                final String mcc = sub.getMccString();
                final String mnc = sub.getMncString();
                final String subscriberId = mTelephony.getSubscriberId(subId);
                if (TextUtils.isEmpty(subscriberId) || TextUtils.isEmpty(mcc)
                        || TextUtils.isEmpty(mnc) || sub.getCarrierId() == UNKNOWN_CARRIER_ID) {
                    Slog.e(TAG, "subInfo of subId " + subId + " is invalid, ignored.");
                    continue;
                }

                final SubInfo subInfo = new SubInfo(subId, sub.getCarrierId(), mcc, mnc,
                        subscriberId, sub.isOpportunistic());
                Slog.i(TAG, "subId " + subId + " added into historical sub list");

                mHistoricalSubs.add(subInfo);
                // Since getting snapshot when pulling will also include data before boot,
                // query stats as baseline to prevent double count is needed.
                mNetworkStatsBaselines.addAll(
                        getDataUsageBytesTransferSnapshotForSubLocked(subInfo));
            }
        }
    }
}
