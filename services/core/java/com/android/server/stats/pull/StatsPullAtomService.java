/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.app.AppOpsManager.OP_FLAGS_ALL_TRUSTED;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
import static android.os.Debug.getIonHeapsSizeKb;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static android.os.Process.getUidForPid;
import static android.os.storage.VolumeInfo.TYPE_PRIVATE;
import static android.os.storage.VolumeInfo.TYPE_PUBLIC;

import static com.android.server.am.MemoryStatUtil.readMemoryStatFromFilesystem;
import static com.android.server.stats.pull.IonMemoryUtil.readProcessSystemIonHeapSizesFromDebugfs;
import static com.android.server.stats.pull.IonMemoryUtil.readSystemIonHeapSizeFromDebugfs;
import static com.android.server.stats.pull.ProcfsMemoryUtil.forEachPid;
import static com.android.server.stats.pull.ProcfsMemoryUtil.readCmdlineFromProcfs;
import static com.android.server.stats.pull.ProcfsMemoryUtil.readMemorySnapshotFromProcfs;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.app.AppOpsManager;
import android.app.AppOpsManager.HistoricalOps;
import android.app.AppOpsManager.HistoricalOpsRequest;
import android.app.AppOpsManager.HistoricalPackageOps;
import android.app.AppOpsManager.HistoricalUidOps;
import android.app.INotificationManager;
import android.app.ProcessMemoryState;
import android.app.StatsManager;
import android.app.StatsManager.PullAtomMetadata;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.UidTraffic;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.UserInfo;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkStats;
import android.net.wifi.WifiManager;
import android.os.BatteryStats;
import android.os.BatteryStatsInternal;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CoolingDevice;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IPullAtomCallback;
import android.os.IStatsCompanionService;
import android.os.IStatsd;
import android.os.IStoraged;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.os.StatsLogEventWrapper;
import android.os.SynchronousResultReceiver;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Temperature;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.Settings;
import android.stats.storage.StorageEnums;
import android.telephony.ModemActivityInfo;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.StatsEvent;
import android.util.StatsLog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.procstats.IProcessStats;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;
import com.android.internal.os.BinderCallsStats.ExportedCallStat;
import com.android.internal.os.KernelCpuSpeedReader;
import com.android.internal.os.KernelCpuThreadReader;
import com.android.internal.os.KernelCpuThreadReaderDiff;
import com.android.internal.os.KernelCpuThreadReaderSettingsObserver;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidActiveTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidClusterTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidFreqTimeReader;
import com.android.internal.os.KernelCpuUidTimeReader.KernelCpuUidUserSysTimeReader;
import com.android.internal.os.KernelWakelockReader;
import com.android.internal.os.KernelWakelockStats;
import com.android.internal.os.LooperStats;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.StoragedUidIoStatsReader;
import com.android.internal.util.DumpUtils;
import com.android.server.BinderCallsStatsService;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.SystemServiceManager;
import com.android.server.am.MemoryStatUtil.MemoryStat;
import com.android.server.notification.NotificationManagerService;
import com.android.server.role.RoleManagerInternal;
import com.android.server.stats.pull.IonMemoryUtil.IonAllocations;
import com.android.server.stats.pull.ProcfsMemoryUtil.MemorySnapshot;
import com.android.server.storage.DiskStatsFileLogger;
import com.android.server.storage.DiskStatsLoggingService;

import com.google.android.collect.Sets;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SystemService containing PullAtomCallbacks that are registered with statsd.
 *
 * @hide
 */
public class StatsPullAtomService extends SystemService {
    private static final String TAG = "StatsPullAtomService";
    private static final boolean DEBUG = true;

    private static final String RESULT_RECEIVER_CONTROLLER_KEY = "controller_activity";
    /**
     * How long to wait on an individual subsystem to return its stats.
     */
    private static final long EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS = 2000;

    private final Object mNetworkStatsLock = new Object();
    @GuardedBy("mNetworkStatsLock")
    private INetworkStatsService mNetworkStatsService;
    private final Object mThermalLock = new Object();
    @GuardedBy("mThermalLock")
    private IThermalService mThermalService;
    private final Object mStoragedLock = new Object();
    @GuardedBy("mStoragedLock")
    private IStoraged mStorageService;
    private final Object mNotificationStatsLock = new Object();
    @GuardedBy("mNotificationStatsLock")
    private INotificationManager mNotificationManagerService;

    private final Context mContext;
    private StatsManager mStatsManager;
    private StorageManager mStorageManager;

    public StatsPullAtomService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        mStatsManager = (StatsManager) mContext.getSystemService(Context.STATS_MANAGER);
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mTelephony = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mStorageManager = (StorageManager) mContext.getSystemService(StorageManager.class);

        // Used to initialize the CPU Frequency atom.
        PowerProfile powerProfile = new PowerProfile(mContext);
        final int numClusters = powerProfile.getNumCpuClusters();
        mKernelCpuSpeedReaders = new KernelCpuSpeedReader[numClusters];
        int firstCpuOfCluster = 0;
        for (int i = 0; i < numClusters; i++) {
            final int numSpeedSteps = powerProfile.getNumSpeedStepsInCpuCluster(i);
            mKernelCpuSpeedReaders[i] = new KernelCpuSpeedReader(firstCpuOfCluster,
                    numSpeedSteps);
            firstCpuOfCluster += powerProfile.getNumCoresInCpuCluster(i);
        }

        // Used for CPU_TIME_PER_THREAD_FREQ
        mKernelCpuThreadReader =
                KernelCpuThreadReaderSettingsObserver.getSettingsModifiedReader(mContext);
    }

    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            BackgroundThread.getHandler().post(() -> {
                registerAllPullers();
            });
        }
    }

    void registerAllPullers() {
        if (DEBUG) {
            Slog.d(TAG, "Registering all pullers with statsd");
        }
        registerWifiBytesTransfer();
        registerWifiBytesTransferBackground();
        registerMobileBytesTransfer();
        registerMobileBytesTransferBackground();
        registerBluetoothBytesTransfer();
        registerKernelWakelock();
        registerCpuTimePerFreq();
        registerCpuTimePerUid();
        registerCpuTimePerUidFreq();
        registerCpuActiveTime();
        registerCpuClusterTime();
        registerWifiActivityInfo();
        registerModemActivityInfo();
        registerBluetoothActivityInfo();
        registerSystemElapsedRealtime();
        registerSystemUptime();
        registerRemainingBatteryCapacity();
        registerFullBatteryCapacity();
        registerBatteryVoltage();
        registerBatteryLevel();
        registerBatteryCycleCount();
        registerProcessMemoryState();
        registerProcessMemoryHighWaterMark();
        registerProcessMemorySnapshot();
        registerSystemIonHeapSize();
        registerIonHeapSize();
        registerProcessSystemIonHeapSize();
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
        registerDiskIO();
        registerPowerProfile();
        registerProcessCpuTime();
        registerCpuTimePerThreadFreq();
        registerDeviceCalculatedPowerUse();
        registerDeviceCalculatedPowerBlameUid();
        registerDeviceCalculatedPowerBlameOther();
        registerDebugElapsedClock();
        registerDebugFailingElapsedClock();
        registerBuildInformation();
        registerRoleHolder();
        registerTimeZoneDataInfo();
        registerExternalStorageInfo();
        registerAppsOnExternalStorageInfo();
        registerFaceSettings();
        registerAppOps();
        registerNotificationRemoteViews();
        registerDangerousPermissionState();
        registerDangerousPermissionStateSampled();
    }

    private INetworkStatsService getINetworkStatsService() {
        synchronized (mNetworkStatsLock) {
            if (mNetworkStatsService == null) {
                mNetworkStatsService = INetworkStatsService.Stub.asInterface(
                        ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
                if (mNetworkStatsService != null) {
                    try {
                        mNetworkStatsService.asBinder().linkToDeath(() -> {
                            synchronized (mNetworkStatsLock) {
                                mNetworkStatsService = null;
                            }
                        }, /* flags */ 0);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "linkToDeath with NetworkStatsService failed", e);
                        mNetworkStatsService = null;
                    }
                }

            }
            return mNetworkStatsService;
        }
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

    private void registerWifiBytesTransfer() {
        int tagId = StatsLog.WIFI_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2, 3, 4, 5})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullWifiBytesTransfer(atomTag, data)
        );
    }

    private int pullWifiBytesTransfer(int atomTag, List<StatsEvent> pulledData) {
        INetworkStatsService networkStatsService = getINetworkStatsService();
        if (networkStatsService == null) {
            Slog.e(TAG, "NetworkStats Service is not available!");
            return StatsManager.PULL_SKIP;
        }
        long token = Binder.clearCallingIdentity();
        try {
            // TODO: Consider caching the following call to get BatteryStatsInternal.
            BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
            String[] ifaces = bs.getWifiIfaces();
            if (ifaces.length == 0) {
                return StatsManager.PULL_SKIP;
            }
            // Combine all the metrics per Uid into one record.
            NetworkStats stats = networkStatsService.getDetailedUidStats(ifaces).groupedByUid();
            addNetworkStats(atomTag, pulledData, stats, false);
        } catch (RemoteException e) {
            Slog.e(TAG, "Pulling netstats for wifi bytes has error", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void addNetworkStats(
            int tag, List<StatsEvent> ret, NetworkStats stats, boolean withFGBG) {
        int size = stats.size();
        NetworkStats.Entry entry = new NetworkStats.Entry(); // For recycling
        for (int j = 0; j < size; j++) {
            stats.getValues(j, entry);
            StatsEvent.Builder e = StatsEvent.newBuilder();
            e.setAtomId(tag);
            e.writeInt(entry.uid);
            if (withFGBG) {
                e.writeInt(entry.set);
            }
            e.writeLong(entry.rxBytes);
            e.writeLong(entry.rxPackets);
            e.writeLong(entry.txBytes);
            e.writeLong(entry.txPackets);
            ret.add(e.build());
        }
    }

    /**
     * Allows rollups per UID but keeping the set (foreground/background) slicing.
     * Adapted from groupedByUid in frameworks/base/core/java/android/net/NetworkStats.java
     */
    private NetworkStats rollupNetworkStatsByFGBG(NetworkStats stats) {
        final NetworkStats ret = new NetworkStats(stats.getElapsedRealtime(), 1);

        final NetworkStats.Entry entry = new NetworkStats.Entry();
        entry.iface = NetworkStats.IFACE_ALL;
        entry.tag = NetworkStats.TAG_NONE;
        entry.metered = NetworkStats.METERED_ALL;
        entry.roaming = NetworkStats.ROAMING_ALL;

        int size = stats.size();
        NetworkStats.Entry recycle = new NetworkStats.Entry(); // Used for retrieving values
        for (int i = 0; i < size; i++) {
            stats.getValues(i, recycle);

            // Skip specific tags, since already counted in TAG_NONE
            if (recycle.tag != NetworkStats.TAG_NONE) continue;

            entry.set = recycle.set; // Allows slicing by background/foreground
            entry.uid = recycle.uid;
            entry.rxBytes = recycle.rxBytes;
            entry.rxPackets = recycle.rxPackets;
            entry.txBytes = recycle.txBytes;
            entry.txPackets = recycle.txPackets;
            // Operations purposefully omitted since we don't use them for statsd.
            ret.combineValues(entry);
        }
        return ret;
    }

    private void registerWifiBytesTransferBackground() {
        int tagId = StatsLog.WIFI_BYTES_TRANSFER_BY_FG_BG;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {3, 4, 5, 6})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullWifiBytesTransferBackground(atomTag, data)
        );
    }

    private int pullWifiBytesTransferBackground(int atomTag, List<StatsEvent> pulledData) {
        INetworkStatsService networkStatsService = getINetworkStatsService();
        if (networkStatsService == null) {
            Slog.e(TAG, "NetworkStats Service is not available!");
            return StatsManager.PULL_SKIP;
        }
        long token = Binder.clearCallingIdentity();
        try {
            BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
            String[] ifaces = bs.getWifiIfaces();
            if (ifaces.length == 0) {
                return StatsManager.PULL_SKIP;
            }
            NetworkStats stats = rollupNetworkStatsByFGBG(
                    networkStatsService.getDetailedUidStats(ifaces));
            addNetworkStats(atomTag, pulledData, stats, true);
        } catch (RemoteException e) {
            Slog.e(TAG, "Pulling netstats for wifi bytes w/ fg/bg has error", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerMobileBytesTransfer() {
        int tagId = StatsLog.MOBILE_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2, 3, 4, 5})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullMobileBytesTransfer(atomTag, data)
        );
    }

    private int pullMobileBytesTransfer(int atomTag, List<StatsEvent> pulledData) {
        INetworkStatsService networkStatsService = getINetworkStatsService();
        if (networkStatsService == null) {
            Slog.e(TAG, "NetworkStats Service is not available!");
            return StatsManager.PULL_SKIP;
        }
        long token = Binder.clearCallingIdentity();
        try {
            BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
            String[] ifaces = bs.getMobileIfaces();
            if (ifaces.length == 0) {
                return StatsManager.PULL_SKIP;
            }
            // Combine all the metrics per Uid into one record.
            NetworkStats stats = networkStatsService.getDetailedUidStats(ifaces).groupedByUid();
            addNetworkStats(atomTag, pulledData, stats, false);
        } catch (RemoteException e) {
            Slog.e(TAG, "Pulling netstats for mobile bytes has error", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerMobileBytesTransferBackground() {
        int tagId = StatsLog.MOBILE_BYTES_TRANSFER_BY_FG_BG;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {3, 4, 5, 6})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullMobileBytesTransferBackground(atomTag, data)
        );
    }

    private int pullMobileBytesTransferBackground(int atomTag, List<StatsEvent> pulledData) {
        INetworkStatsService networkStatsService = getINetworkStatsService();
        if (networkStatsService == null) {
            Slog.e(TAG, "NetworkStats Service is not available!");
            return StatsManager.PULL_SKIP;
        }
        long token = Binder.clearCallingIdentity();
        try {
            BatteryStatsInternal bs = LocalServices.getService(BatteryStatsInternal.class);
            String[] ifaces = bs.getMobileIfaces();
            if (ifaces.length == 0) {
                return StatsManager.PULL_SKIP;
            }
            NetworkStats stats = rollupNetworkStatsByFGBG(
                    networkStatsService.getDetailedUidStats(ifaces));
            addNetworkStats(atomTag, pulledData, stats, true);
        } catch (RemoteException e) {
            Slog.e(TAG, "Pulling netstats for mobile bytes w/ fg/bg has error", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerBluetoothBytesTransfer() {
        int tagId = StatsLog.BLUETOOTH_BYTES_TRANSFER;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2, 3})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullBluetoothBytesTransfer(atomTag, data)
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
            Slog.e(TAG, "no controller energy info supplied for " + receiver.getName());
        } catch (TimeoutException e) {
            Slog.w(TAG, "timeout reading " + receiver.getName() + " stats");
        }
        return null;
    }

    private synchronized BluetoothActivityEnergyInfo fetchBluetoothData() {
        // TODO: Investigate whether the synchronized keyword is needed.
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            SynchronousResultReceiver bluetoothReceiver = new SynchronousResultReceiver(
                    "bluetooth");
            adapter.requestControllerActivityEnergyInfo(bluetoothReceiver);
            return awaitControllerInfo(bluetoothReceiver);
        } else {
            Slog.e(TAG, "Failed to get bluetooth adapter!");
            return null;
        }
    }

    private int pullBluetoothBytesTransfer(int atomTag, List<StatsEvent> pulledData) {
        BluetoothActivityEnergyInfo info = fetchBluetoothData();
        if (info == null || info.getUidTraffic() == null) {
            return StatsManager.PULL_SKIP;
        }
        for (UidTraffic traffic : info.getUidTraffic()) {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(traffic.getUid())
                    .writeLong(traffic.getRxBytes())
                    .writeLong(traffic.getTxBytes())
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private final KernelWakelockReader mKernelWakelockReader = new KernelWakelockReader();
    private final KernelWakelockStats mTmpWakelockStats = new KernelWakelockStats();

    private void registerKernelWakelock() {
        int tagId = StatsLog.KERNEL_WAKELOCK;
        mStatsManager.registerPullAtomCallback(
                tagId,
                /* PullAtomMetadata */ null,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullKernelWakelock(atomTag, data)
        );
    }

    private int pullKernelWakelock(int atomTag, List<StatsEvent> pulledData) {
        final KernelWakelockStats wakelockStats =
                mKernelWakelockReader.readKernelWakelockStats(mTmpWakelockStats);
        for (Map.Entry<String, KernelWakelockStats.Entry> ent : wakelockStats.entrySet()) {
            String name = ent.getKey();
            KernelWakelockStats.Entry kws = ent.getValue();
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeString(name)
                    .writeInt(kws.mCount)
                    .writeInt(kws.mVersion)
                    .writeLong(kws.mTotalTime)
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private KernelCpuSpeedReader[] mKernelCpuSpeedReaders;
    // Disables throttler on CPU time readers.
    private KernelCpuUidUserSysTimeReader mCpuUidUserSysTimeReader =
            new KernelCpuUidUserSysTimeReader(false);
    private KernelCpuUidFreqTimeReader mCpuUidFreqTimeReader =
            new KernelCpuUidFreqTimeReader(false);
    private KernelCpuUidActiveTimeReader mCpuUidActiveTimeReader =
            new KernelCpuUidActiveTimeReader(false);
    private KernelCpuUidClusterTimeReader mCpuUidClusterTimeReader =
            new KernelCpuUidClusterTimeReader(false);

    private void registerCpuTimePerFreq() {
        int tagId = StatsLog.CPU_TIME_PER_FREQ;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {3})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullCpuTimePerFreq(atomTag, data)
        );
    }

    private int pullCpuTimePerFreq(int atomTag, List<StatsEvent> pulledData) {
        for (int cluster = 0; cluster < mKernelCpuSpeedReaders.length; cluster++) {
            long[] clusterTimeMs = mKernelCpuSpeedReaders[cluster].readAbsolute();
            if (clusterTimeMs != null) {
                for (int speed = clusterTimeMs.length - 1; speed >= 0; --speed) {
                    StatsEvent e = StatsEvent.newBuilder()
                            .setAtomId(atomTag)
                            .writeInt(cluster)
                            .writeInt(speed)
                            .writeLong(clusterTimeMs[speed])
                            .build();
                    pulledData.add(e);
                }
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuTimePerUid() {
        int tagId = StatsLog.CPU_TIME_PER_UID;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2, 3})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullCpuTimePerUid(atomTag, data)
        );
    }

    private int pullCpuTimePerUid(int atomTag, List<StatsEvent> pulledData) {
        mCpuUidUserSysTimeReader.readAbsolute((uid, timesUs) -> {
            long userTimeUs = timesUs[0], systemTimeUs = timesUs[1];
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(uid)
                    .writeLong(userTimeUs)
                    .writeLong(systemTimeUs)
                    .build();
            pulledData.add(e);
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuTimePerUidFreq() {
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        int tagId = StatsLog.CPU_TIME_PER_UID_FREQ;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {4})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullCpuTimeperUidFreq(atomTag, data)
        );
    }

    private int pullCpuTimeperUidFreq(int atomTag, List<StatsEvent> pulledData) {
        mCpuUidFreqTimeReader.readAbsolute((uid, cpuFreqTimeMs) -> {
            for (int freqIndex = 0; freqIndex < cpuFreqTimeMs.length; ++freqIndex) {
                if (cpuFreqTimeMs[freqIndex] != 0) {
                    StatsEvent e = StatsEvent.newBuilder()
                            .setAtomId(atomTag)
                            .writeInt(uid)
                            .writeInt(freqIndex)
                            .writeLong(cpuFreqTimeMs[freqIndex])
                            .build();
                    pulledData.add(e);
                }
            }
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuActiveTime() {
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        int tagId = StatsLog.CPU_ACTIVE_TIME;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {2})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullCpuActiveTime(atomTag, data)
        );
    }

    private int pullCpuActiveTime(int atomTag, List<StatsEvent> pulledData) {
        mCpuUidActiveTimeReader.readAbsolute((uid, cpuActiveTimesMs) -> {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(uid)
                    .writeLong(cpuActiveTimesMs)
                    .build();
            pulledData.add(e);
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCpuClusterTime() {
        // the throttling is 3sec, handled in
        // frameworks/base/core/java/com/android/internal/os/KernelCpuProcReader
        int tagId = StatsLog.CPU_CLUSTER_TIME;
        PullAtomMetadata metadata = new PullAtomMetadata.Builder()
                .setAdditiveFields(new int[] {3})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullCpuClusterTime(atomTag, data)
        );
    }

    private int pullCpuClusterTime(int atomTag, List<StatsEvent> pulledData) {
        mCpuUidClusterTimeReader.readAbsolute((uid, cpuClusterTimesMs) -> {
            for (int i = 0; i < cpuClusterTimesMs.length; i++) {
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(uid)
                        .writeInt(i)
                        .writeLong(cpuClusterTimesMs[i])
                        .build();
                pulledData.add(e);
            }
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerWifiActivityInfo() {
        int tagId = StatsLog.WIFI_ACTIVITY_INFO;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullWifiActivityInfo(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private WifiManager mWifiManager;
    private TelephonyManager mTelephony;

    private int pullWifiActivityInfo(int atomTag, List<StatsEvent> pulledData) {
        long token = Binder.clearCallingIdentity();
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
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeLong(wifiInfo.getTimeSinceBootMillis())
                    .writeInt(wifiInfo.getStackState())
                    .writeLong(wifiInfo.getControllerTxDurationMillis())
                    .writeLong(wifiInfo.getControllerRxDurationMillis())
                    .writeLong(wifiInfo.getControllerIdleDurationMillis())
                    .writeLong(wifiInfo.getControllerEnergyUsedMicroJoules())
                    .build();
            pulledData.add(e);
        } catch (RuntimeException e) {
            Slog.e(TAG, "failed to getWifiActivityEnergyInfoAsync", e);
            return StatsManager.PULL_SKIP;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerModemActivityInfo() {
        int tagId = StatsLog.MODEM_ACTIVITY_INFO;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullModemActivityInfo(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullModemActivityInfo(int atomTag, List<StatsEvent> pulledData) {
        long token = Binder.clearCallingIdentity();
        try {
            SynchronousResultReceiver modemReceiver = new SynchronousResultReceiver("telephony");
            mTelephony.requestModemActivityInfo(modemReceiver);
            final ModemActivityInfo modemInfo = awaitControllerInfo(modemReceiver);
            if (modemInfo == null) {
                return StatsManager.PULL_SKIP;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeLong(modemInfo.getTimestamp())
                    .writeLong(modemInfo.getSleepTimeMillis())
                    .writeLong(modemInfo.getIdleTimeMillis())
                    .writeLong(modemInfo.getTransmitPowerInfo().get(0).getTimeInMillis())
                    .writeLong(modemInfo.getTransmitPowerInfo().get(1).getTimeInMillis())
                    .writeLong(modemInfo.getTransmitPowerInfo().get(2).getTimeInMillis())
                    .writeLong(modemInfo.getTransmitPowerInfo().get(3).getTimeInMillis())
                    .writeLong(modemInfo.getTransmitPowerInfo().get(4).getTimeInMillis())
                    .writeLong(modemInfo.getReceiveTimeMillis())
                    .build();
            pulledData.add(e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerBluetoothActivityInfo() {
        int tagId = StatsLog.BLUETOOTH_ACTIVITY_INFO;
        mStatsManager.registerPullAtomCallback(
                tagId,
                /* metadata */ null,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullBluetoothActivityInfo(atomTag, data)
        );
    }

    private int pullBluetoothActivityInfo(int atomTag, List<StatsEvent> pulledData) {
        BluetoothActivityEnergyInfo info = fetchBluetoothData();
        if (info == null) {
            return StatsManager.PULL_SKIP;
        }
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(info.getTimeStamp())
                .writeInt(info.getBluetoothStackState())
                .writeLong(info.getControllerTxTimeMillis())
                .writeLong(info.getControllerRxTimeMillis())
                .writeLong(info.getControllerIdleTimeMillis())
                .writeLong(info.getControllerEnergyUsed())
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private static final long NS_PER_SEC = 1000000000;

    private void registerSystemElapsedRealtime() {
        int tagId = StatsLog.SYSTEM_ELAPSED_REALTIME;
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setCoolDownNs(NS_PER_SEC)
                .setTimeoutNs(NS_PER_SEC / 2)
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullSystemElapsedRealtime(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullSystemElapsedRealtime(int atomTag, List<StatsEvent> pulledData) {
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(SystemClock.elapsedRealtime())
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerSystemUptime() {
        int tagId = StatsLog.SYSTEM_UPTIME;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullSystemUptime(atomTag, data)
        );
    }

    private int pullSystemUptime(int atomTag, List<StatsEvent> pulledData) {
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(SystemClock.elapsedRealtime())
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerRemainingBatteryCapacity() {
        // No op.
    }

    private void pullRemainingBatteryCapacity() {
        // No op.
    }

    private void registerFullBatteryCapacity() {
        // No op.
    }

    private void pullFullBatteryCapacity() {
        // No op.
    }

    private void registerBatteryVoltage() {
        // No op.
    }

    private void pullBatteryVoltage() {
        // No op.
    }

    private void registerBatteryLevel() {
        // No op.
    }

    private void pullBatteryLevel() {
        // No op.
    }

    private void registerBatteryCycleCount() {
        // No op.
    }

    private void pullBatteryCycleCount() {
        // No op.
    }

    private void registerProcessMemoryState() {
        int tagId = StatsLog.PROCESS_MEMORY_STATE;
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {4, 5, 6, 7, 8})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullProcessMemoryState(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullProcessMemoryState(int atomTag, List<StatsEvent> pulledData) {
        List<ProcessMemoryState> processMemoryStates =
                LocalServices.getService(ActivityManagerInternal.class)
                        .getMemoryStateForProcesses();
        for (ProcessMemoryState processMemoryState : processMemoryStates) {
            final MemoryStat memoryStat = readMemoryStatFromFilesystem(processMemoryState.uid,
                    processMemoryState.pid);
            if (memoryStat == null) {
                continue;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(processMemoryState.uid)
                    .writeString(processMemoryState.processName)
                    .writeInt(processMemoryState.oomScore)
                    .writeLong(memoryStat.pgfault)
                    .writeLong(memoryStat.pgmajfault)
                    .writeLong(memoryStat.rssInBytes)
                    .writeLong(memoryStat.cacheInBytes)
                    .writeLong(memoryStat.swapInBytes)
                    .writeLong(-1)  // unused
                    .writeLong(-1)  // unused
                    .writeInt(-1)  // unused
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    /**
     * Which native processes to snapshot memory for.
     *
     * <p>Processes are matched by their cmdline in procfs. Example: cat /proc/pid/cmdline returns
     * /system/bin/statsd for the stats daemon.
     */
    private static final Set<String> MEMORY_INTERESTING_NATIVE_PROCESSES = Sets.newHashSet(
            "/system/bin/statsd",  // Stats daemon.
            "/system/bin/surfaceflinger",
            "/system/bin/apexd",  // APEX daemon.
            "/system/bin/audioserver",
            "/system/bin/cameraserver",
            "/system/bin/drmserver",
            "/system/bin/healthd",
            "/system/bin/incidentd",
            "/system/bin/installd",
            "/system/bin/lmkd",  // Low memory killer daemon.
            "/system/bin/logd",
            "media.codec",
            "media.extractor",
            "media.metrics",
            "/system/bin/mediadrmserver",
            "/system/bin/mediaserver",
            "/system/bin/performanced",
            "/system/bin/tombstoned",
            "/system/bin/traced",  // Perfetto.
            "/system/bin/traced_probes",  // Perfetto.
            "webview_zygote",
            "zygote",
            "zygote64");

    /**
     * Lowest available uid for apps.
     *
     * <p>Used to quickly discard memory snapshots of the zygote forks from native process
     * measurements.
     */
    private static final int MIN_APP_UID = 10_000;

    private static boolean isAppUid(int uid) {
        return uid >= MIN_APP_UID;
    }

    private void registerProcessMemoryHighWaterMark() {
        int tagId = StatsLog.PROCESS_MEMORY_HIGH_WATER_MARK;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullProcessMemoryHighWaterMark(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullProcessMemoryHighWaterMark(int atomTag, List<StatsEvent> pulledData) {
        List<ProcessMemoryState> managedProcessList =
                LocalServices.getService(ActivityManagerInternal.class)
                        .getMemoryStateForProcesses();
        for (ProcessMemoryState managedProcess : managedProcessList) {
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(managedProcess.pid);
            if (snapshot == null) {
                continue;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(managedProcess.uid)
                    .writeString(managedProcess.processName)
                    // RSS high-water mark in bytes.
                    .writeLong(snapshot.rssHighWaterMarkInKilobytes * 1024L)
                    .writeInt(snapshot.rssHighWaterMarkInKilobytes)
                    .build();
            pulledData.add(e);
        }
        forEachPid((pid, cmdLine) -> {
            if (!MEMORY_INTERESTING_NATIVE_PROCESSES.contains(cmdLine)) {
                return;
            }
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(pid);
            if (snapshot == null) {
                return;
            }
            // Sometimes we get here a process that is not included in the whitelist. It comes
            // from forking the zygote for an app. We can ignore that sample because this process
            // is collected by ProcessMemoryState.
            if (isAppUid(snapshot.uid)) {
                return;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(snapshot.uid)
                    .writeString(cmdLine)
                    // RSS high-water mark in bytes.
                    .writeLong(snapshot.rssHighWaterMarkInKilobytes * 1024L)
                    .writeInt(snapshot.rssHighWaterMarkInKilobytes)
                    .build();
            pulledData.add(e);
        });
        // Invoke rss_hwm_reset binary to reset RSS HWM counters for all processes.
        SystemProperties.set("sys.rss_hwm_reset.on", "1");
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessMemorySnapshot() {
        int tagId = StatsLog.PROCESS_MEMORY_SNAPSHOT;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullProcessMemorySnapshot(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullProcessMemorySnapshot(int atomTag, List<StatsEvent> pulledData) {
        List<ProcessMemoryState> managedProcessList =
                LocalServices.getService(ActivityManagerInternal.class)
                        .getMemoryStateForProcesses();
        for (ProcessMemoryState managedProcess : managedProcessList) {
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(managedProcess.pid);
            if (snapshot == null) {
                continue;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .writeInt(managedProcess.uid)
                    .writeString(managedProcess.processName)
                    .writeInt(managedProcess.pid)
                    .writeInt(managedProcess.oomScore)
                    .writeInt(snapshot.rssInKilobytes)
                    .writeInt(snapshot.anonRssInKilobytes)
                    .writeInt(snapshot.swapInKilobytes)
                    .writeInt(snapshot.anonRssInKilobytes + snapshot.swapInKilobytes)
                    .build();
            pulledData.add(e);
        }
        forEachPid((pid, cmdLine) -> {
            if (!MEMORY_INTERESTING_NATIVE_PROCESSES.contains(cmdLine)) {
                return;
            }
            final MemorySnapshot snapshot = readMemorySnapshotFromProcfs(pid);
            if (snapshot == null) {
                return;
            }
            // Sometimes we get here a process that is not included in the whitelist. It comes
            // from forking the zygote for an app. We can ignore that sample because this process
            // is collected by ProcessMemoryState.
            if (isAppUid(snapshot.uid)) {
                return;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(snapshot.uid)
                    .writeString(cmdLine)
                    .writeInt(pid)
                    .writeInt(-1001)  // Placeholder for native processes, OOM_SCORE_ADJ_MIN - 1.
                    .writeInt(snapshot.rssInKilobytes)
                    .writeInt(snapshot.anonRssInKilobytes)
                    .writeInt(snapshot.swapInKilobytes)
                    .writeInt(snapshot.anonRssInKilobytes + snapshot.swapInKilobytes)
                    .build();
            pulledData.add(e);
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerSystemIonHeapSize() {
        int tagId = StatsLog.SYSTEM_ION_HEAP_SIZE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullSystemIonHeapSize(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullSystemIonHeapSize(int atomTag, List<StatsEvent> pulledData) {
        final long systemIonHeapSizeInBytes = readSystemIonHeapSizeFromDebugfs();
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(systemIonHeapSizeInBytes)
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerIonHeapSize() {
        int tagId = StatsLog.ION_HEAP_SIZE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                /* PullAtomMetadata */ null,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullIonHeapSize(atomTag, data)
        );
    }

    private int pullIonHeapSize(int atomTag, List<StatsEvent> pulledData) {
        int ionHeapSizeInKilobytes = (int) getIonHeapsSizeKb();
        StatsEvent e = StatsEvent.newBuilder()
              .setAtomId(atomTag)
              .writeInt(ionHeapSizeInKilobytes)
              .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcessSystemIonHeapSize() {
        int tagId = StatsLog.PROCESS_SYSTEM_ION_HEAP_SIZE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullProcessSystemIonHeapSize(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullProcessSystemIonHeapSize(int atomTag, List<StatsEvent> pulledData) {
        List<IonAllocations> result = readProcessSystemIonHeapSizesFromDebugfs();
        for (IonAllocations allocations : result) {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(getUidForPid(allocations.pid))
                    .writeString(readCmdlineFromProcfs(allocations.pid))
                    .writeInt((int) (allocations.totalSizeInBytes / 1024))
                    .writeInt(allocations.count)
                    .writeInt((int) (allocations.maxSizeInBytes / 1024))
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerTemperature() {
        int tagId = StatsLog.TEMPERATURE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullTemperature(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullTemperature(int atomTag, List<StatsEvent> pulledData) {
        IThermalService thermalService = getIThermalService();
        if (thermalService == null) {
            return StatsManager.PULL_SKIP;
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            List<Temperature> temperatures = thermalService.getCurrentTemperatures();
            for (Temperature temp : temperatures) {
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(temp.getType())
                        .writeString(temp.getName())
                        .writeInt((int) (temp.getValue() * 10))
                        .writeInt(temp.getStatus())
                        .build();
                pulledData.add(e);
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
        int tagId = StatsLog.COOLING_DEVICE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullCooldownDevice(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullCooldownDevice(int atomTag, List<StatsEvent> pulledData) {
        IThermalService thermalService = getIThermalService();
        if (thermalService == null) {
            return StatsManager.PULL_SKIP;
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            List<CoolingDevice> devices = thermalService.getCurrentCoolingDevices();
            for (CoolingDevice device : devices) {
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(device.getType())
                        .writeString(device.getName())
                        .writeInt((int) (device.getValue()))
                        .build();
                pulledData.add(e);
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
        int tagId = StatsLog.BINDER_CALLS;
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {4, 5, 6, 8, 12})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullBinderCallsStats(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullBinderCallsStats(int atomTag, List<StatsEvent> pulledData) {
        BinderCallsStatsService.Internal binderStats =
                LocalServices.getService(BinderCallsStatsService.Internal.class);
        if (binderStats == null) {
            Slog.e(TAG, "failed to get binderStats");
            return StatsManager.PULL_SKIP;
        }

        List<ExportedCallStat> callStats = binderStats.getExportedCallStats();
        binderStats.reset();
        for (ExportedCallStat callStat : callStats) {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(callStat.workSourceUid)
                    .writeString(callStat.className)
                    .writeString(callStat.methodName)
                    .writeLong(callStat.callCount)
                    .writeLong(callStat.exceptionCount)
                    .writeLong(callStat.latencyMicros)
                    .writeLong(callStat.maxLatencyMicros)
                    .writeLong(callStat.cpuTimeMicros)
                    .writeLong(callStat.maxCpuTimeMicros)
                    .writeLong(callStat.maxReplySizeBytes)
                    .writeLong(callStat.maxRequestSizeBytes)
                    .writeLong(callStat.recordedCallCount)
                    .writeInt(callStat.screenInteractive ? 1 : 0)
                    .writeInt(callStat.callingUid)
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerBinderCallsStatsExceptions() {
        int tagId = StatsLog.BINDER_CALLS_EXCEPTIONS;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullBinderCallsStatsExceptions(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullBinderCallsStatsExceptions(int atomTag, List<StatsEvent> pulledData) {
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
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeString(entry.getKey())
                    .writeInt(entry.getValue())
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerLooperStats() {
        int tagId = StatsLog.LOOPER_STATS;
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {5, 6, 7, 8, 9})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullLooperStats(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullLooperStats(int atomTag, List<StatsEvent> pulledData) {
        LooperStats looperStats = LocalServices.getService(LooperStats.class);
        if (looperStats == null) {
            return StatsManager.PULL_SKIP;
        }

        List<LooperStats.ExportedEntry> entries = looperStats.getEntries();
        looperStats.reset();
        for (LooperStats.ExportedEntry entry : entries) {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(entry.workSourceUid)
                    .writeString(entry.handlerClassName)
                    .writeString(entry.threadName)
                    .writeString(entry.messageName)
                    .writeLong(entry.messageCount)
                    .writeLong(entry.exceptionCount)
                    .writeLong(entry.recordedMessageCount)
                    .writeLong(entry.totalLatencyMicros)
                    .writeLong(entry.cpuUsageMicros)
                    .writeBoolean(entry.isInteractive)
                    .writeLong(entry.maxCpuUsageMicros)
                    .writeLong(entry.maxLatencyMicros)
                    .writeLong(entry.recordedDelayMessageCount)
                    .writeLong(entry.delayMillis)
                    .writeLong(entry.maxDelayMillis)
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDiskStats() {
        int tagId = StatsLog.DISK_STATS;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullDiskStats(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullDiskStats(int atomTag, List<StatsEvent> pulledData) {
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
        boolean fileBased = StorageManager.isFileEncryptedNativeOnly();

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
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(latency)
                .writeBoolean(fileBased)
                .writeInt(writeSpeed)
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDirectoryUsage() {
        int tagId = StatsLog.DIRECTORY_USAGE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullDirectoryUsage(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullDirectoryUsage(int atomTag, List<StatsEvent> pulledData) {
        StatFs statFsData = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        StatFs statFsSystem = new StatFs(Environment.getRootDirectory().getAbsolutePath());
        StatFs statFsCache = new StatFs(Environment.getDownloadCacheDirectory().getAbsolutePath());

        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeInt(StatsLog.DIRECTORY_USAGE__DIRECTORY__DATA)
                .writeLong(statFsData.getAvailableBytes())
                .writeLong(statFsData.getTotalBytes())
                .build();
        pulledData.add(e);

        e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeInt(StatsLog.DIRECTORY_USAGE__DIRECTORY__CACHE)
                .writeLong(statFsCache.getAvailableBytes())
                .writeLong(statFsCache.getTotalBytes())
                .build();
        pulledData.add(e);

        e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeInt(StatsLog.DIRECTORY_USAGE__DIRECTORY__SYSTEM)
                .writeLong(statFsSystem.getAvailableBytes())
                .writeLong(statFsSystem.getTotalBytes())
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerAppSize() {
        int tagId = StatsLog.APP_SIZE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullAppSize(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullAppSize(int atomTag, List<StatsEvent> pulledData) {
        try {
            String jsonStr = IoUtils.readFileAsString(DiskStatsLoggingService.DUMPSYS_CACHE_PATH);
            JSONObject json = new JSONObject(jsonStr);
            long cache_time = json.optLong(DiskStatsFileLogger.LAST_QUERY_TIMESTAMP_KEY, -1L);
            JSONArray pkg_names = json.getJSONArray(DiskStatsFileLogger.PACKAGE_NAMES_KEY);
            JSONArray app_sizes = json.getJSONArray(DiskStatsFileLogger.APP_SIZES_KEY);
            JSONArray app_data_sizes = json.getJSONArray(DiskStatsFileLogger.APP_DATA_KEY);
            JSONArray app_cache_sizes = json.getJSONArray(DiskStatsFileLogger.APP_CACHES_KEY);
            // Sanity check: Ensure all 4 lists have the same length.
            int length = pkg_names.length();
            if (app_sizes.length() != length || app_data_sizes.length() != length
                    || app_cache_sizes.length() != length) {
                Slog.e(TAG, "formatting error in diskstats cache file!");
                return StatsManager.PULL_SKIP;
            }
            for (int i = 0; i < length; i++) {
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeString(pkg_names.getString(i))
                        .writeLong(app_sizes.optLong(i, /* fallback */ -1L))
                        .writeLong(app_data_sizes.optLong(i, /* fallback */ -1L))
                        .writeLong(app_cache_sizes.optLong(i, /* fallback */ -1L))
                        .writeLong(cache_time)
                        .build();
                pulledData.add(e);
            }
        } catch (IOException | JSONException e) {
            Slog.e(TAG, "exception reading diskstats cache file", e);
            return StatsManager.PULL_SKIP;
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerCategorySize() {
        int tagId = StatsLog.CATEGORY_SIZE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullCategorySize(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullCategorySize(int atomTag, List<StatsEvent> pulledData) {
        try {
            String jsonStr = IoUtils.readFileAsString(DiskStatsLoggingService.DUMPSYS_CACHE_PATH);
            JSONObject json = new JSONObject(jsonStr);
            long cacheTime = json.optLong(
                    DiskStatsFileLogger.LAST_QUERY_TIMESTAMP_KEY, /* fallback */ -1L);

            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__APP_SIZE)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.APP_SIZE_AGG_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__APP_DATA_SIZE)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.APP_DATA_SIZE_AGG_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__APP_CACHE_SIZE)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.APP_CACHE_AGG_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__PHOTOS)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.PHOTOS_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__VIDEOS)
                    .writeLong(
                            json.optLong(DiskStatsFileLogger.VIDEOS_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__AUDIO)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.AUDIO_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__DOWNLOADS)
                    .writeLong(
                            json.optLong(DiskStatsFileLogger.DOWNLOADS_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__SYSTEM)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.SYSTEM_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);

            e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(StatsLog.CATEGORY_SIZE__CATEGORY__OTHER)
                    .writeLong(json.optLong(
                            DiskStatsFileLogger.MISC_KEY, /* fallback */ -1L))
                    .writeLong(cacheTime)
                    .build();
            pulledData.add(e);
        } catch (IOException | JSONException e) {
            Slog.e(TAG, "exception reading diskstats cache file", e);
            return StatsManager.PULL_SKIP;
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerNumFingerprintsEnrolled() {
        int tagId = StatsLog.NUM_FINGERPRINTS_ENROLLED;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullNumBiometricsEnrolled(
                        BiometricsProtoEnums.MODALITY_FINGERPRINT, atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private void registerNumFacesEnrolled() {
        int tagId = StatsLog.NUM_FACES_ENROLLED;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullNumBiometricsEnrolled(
                        BiometricsProtoEnums.MODALITY_FACE, atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullNumBiometricsEnrolled(int modality, int atomTag, List<StatsEvent> pulledData) {
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
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(userId)
                        .writeInt(numEnrolled)
                        .build();
                pulledData.add(e);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerProcStats() {
        // No op.
    }

    private void pullProcStats() {
        // No op.
    }

    private void registerProcStatsPkgProc() {
        // No op.
    }

    private void pullProcStatsPkgProc() {
        // No op.
    }

    private StoragedUidIoStatsReader mStoragedUidIoStatsReader =
            new StoragedUidIoStatsReader();

    private void registerDiskIO() {
        int tagId = StatsLog.DISK_IO;
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
                .setCoolDownNs(3 * NS_PER_SEC)
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullDiskIO(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullDiskIO(int atomTag, List<StatsEvent> pulledData) {
        mStoragedUidIoStatsReader.readAbsolute((uid, fgCharsRead, fgCharsWrite, fgBytesRead,
                fgBytesWrite, bgCharsRead, bgCharsWrite, bgBytesRead, bgBytesWrite,
                fgFsync, bgFsync) -> {
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(uid)
                    .writeLong(fgCharsRead)
                    .writeLong(fgCharsWrite)
                    .writeLong(fgBytesRead)
                    .writeLong(fgBytesWrite)
                    .writeLong(bgCharsRead)
                    .writeLong(bgCharsWrite)
                    .writeLong(bgBytesRead)
                    .writeLong(bgBytesWrite)
                    .writeLong(fgFsync)
                    .writeLong(bgFsync)
                    .build();
            pulledData.add(e);
        });
        return StatsManager.PULL_SUCCESS;
    }

    private void registerPowerProfile() {
        int tagId = StatsLog.POWER_PROFILE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                /* PullAtomMetadata */ null,
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullPowerProfile(atomTag, data)
        );
    }

    private int pullPowerProfile(int atomTag, List<StatsEvent> pulledData) {
        PowerProfile powerProfile = new PowerProfile(mContext);
        ProtoOutputStream proto = new ProtoOutputStream();
        powerProfile.dumpDebug(proto);
        proto.flush();
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeByteArray(proto.getBytes())
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private final Object mCpuTrackerLock = new Object();
    @GuardedBy("mCpuTrackerLock")
    private ProcessCpuTracker mProcessCpuTracker;

    private void registerProcessCpuTime() {
        int tagId = StatsLog.PROCESS_CPU_TIME;
        // Min cool-down is 5 sec, inline with what ActivityManagerService uses.
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setCoolDownNs(5 * NS_PER_SEC)
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullProcessCpuTime(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullProcessCpuTime(int atomTag, List<StatsEvent> pulledData) {
        synchronized (mCpuTrackerLock) {
            if (mProcessCpuTracker == null) {
                mProcessCpuTracker = new ProcessCpuTracker(false);
                mProcessCpuTracker.init();
            }
            mProcessCpuTracker.update();
            for (int i = 0; i < mProcessCpuTracker.countStats(); i++) {
                ProcessCpuTracker.Stats st = mProcessCpuTracker.getStats(i);
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(st.uid)
                        .writeString(st.name)
                        .writeLong(st.base_utime)
                        .writeLong(st.base_stime)
                        .build();
                pulledData.add(e);
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    @Nullable
    private KernelCpuThreadReaderDiff mKernelCpuThreadReader;
    private static final int CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES = 8;

    private void registerCpuTimePerThreadFreq() {
        int tagId = StatsLog.CPU_TIME_PER_THREAD_FREQ;
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {7, 9, 11, 13, 15, 17, 19, 21})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullCpuTimePerThreadFreq(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullCpuTimePerThreadFreq(int atomTag, List<StatsEvent> pulledData) {
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

                StatsEvent.Builder e = StatsEvent.newBuilder();
                e.setAtomId(atomTag);
                e.writeInt(processCpuUsage.uid);
                e.writeInt(processCpuUsage.processId);
                e.writeInt(threadCpuUsage.threadId);
                e.writeString(processCpuUsage.processName);
                e.writeString(threadCpuUsage.threadName);
                for (int k = 0; k < CPU_TIME_PER_THREAD_FREQ_MAX_NUM_FREQUENCIES; k++) {
                    if (k < cpuFrequencies.length) {
                        e.writeInt(cpuFrequencies[k]);
                        e.writeInt(threadCpuUsage.usageTimesMillis[k]);
                    } else {
                        // If we have no more frequencies to write, we still must write empty data.
                        // We know that this data is empty (and not just zero) because all
                        // frequencies are expected to be greater than zero
                        e.writeInt(0);
                        e.writeInt(0);
                    }
                }
                pulledData.add(e.build());
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    // TODO: move to top of file when all migrations are complete
    private BatteryStatsHelper mBatteryStatsHelper = null;
    private static final int MAX_BATTERY_STATS_HELPER_FREQUENCY_MS = 1000;
    private long mBatteryStatsHelperTimestampMs = -MAX_BATTERY_STATS_HELPER_FREQUENCY_MS;
    private static final long MILLI_AMP_HR_TO_NANO_AMP_SECS = 1_000_000L * 3600L;

    private BatteryStatsHelper getBatteryStatsHelper() {
        if (mBatteryStatsHelper == null) {
            final long callingToken = Binder.clearCallingIdentity();
            try {
                // clearCallingIdentity required for BatteryStatsHelper.checkWifiOnly().
                mBatteryStatsHelper = new BatteryStatsHelper(mContext, false);
            } finally {
                Binder.restoreCallingIdentity(callingToken);
            }
            mBatteryStatsHelper.create((Bundle) null);
        }
        long currentTime = SystemClock.elapsedRealtime();
        if (currentTime - mBatteryStatsHelperTimestampMs >= MAX_BATTERY_STATS_HELPER_FREQUENCY_MS) {
            // Load BatteryStats and do all the calculations.
            mBatteryStatsHelper.refreshStats(BatteryStats.STATS_SINCE_CHARGED, UserHandle.USER_ALL);
            // Calculations are done so we don't need to save the raw BatteryStats data in RAM.
            mBatteryStatsHelper.clearStats();
            mBatteryStatsHelperTimestampMs = currentTime;
        }
        return mBatteryStatsHelper;
    }

    private long milliAmpHrsToNanoAmpSecs(double mAh) {
        return (long) (mAh * MILLI_AMP_HR_TO_NANO_AMP_SECS + 0.5);
    }

    private void registerDeviceCalculatedPowerUse() {
        int tagId = StatsLog.DEVICE_CALCULATED_POWER_USE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullDeviceCalculatedPowerUse(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullDeviceCalculatedPowerUse(int atomTag, List<StatsEvent> pulledData) {
        BatteryStatsHelper bsHelper = getBatteryStatsHelper();
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeLong(milliAmpHrsToNanoAmpSecs(bsHelper.getComputedPower()))
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDeviceCalculatedPowerBlameUid() {
        int tagId = StatsLog.DEVICE_CALCULATED_POWER_BLAME_UID;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullDeviceCalculatedPowerBlameUid(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullDeviceCalculatedPowerBlameUid(int atomTag, List<StatsEvent> pulledData) {
        final List<BatterySipper> sippers = getBatteryStatsHelper().getUsageList();
        if (sippers == null) {
            return StatsManager.PULL_SKIP;
        }

        for (BatterySipper bs : sippers) {
            if (bs.drainType != bs.drainType.APP) {
                continue;
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(bs.uidObj.getUid())
                    .writeLong(milliAmpHrsToNanoAmpSecs(bs.totalPowerMah))
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDeviceCalculatedPowerBlameOther() {
        int tagId = StatsLog.DEVICE_CALCULATED_POWER_BLAME_OTHER;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullDeviceCalculatedPowerBlameOther(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullDeviceCalculatedPowerBlameOther(int atomTag, List<StatsEvent> pulledData) {
        final List<BatterySipper> sippers = getBatteryStatsHelper().getUsageList();
        if (sippers == null) {
            return StatsManager.PULL_SKIP;
        }

        for (BatterySipper bs : sippers) {
            if (bs.drainType == bs.drainType.APP) {
                continue; // This is a separate atom; see pullDeviceCalculatedPowerBlameUid().
            }
            if (bs.drainType == bs.drainType.USER) {
                continue; // This is not supported. We purposefully calculate over USER_ALL.
            }
            StatsEvent e = StatsEvent.newBuilder()
                    .setAtomId(atomTag)
                    .writeInt(bs.drainType.ordinal())
                    .writeLong(milliAmpHrsToNanoAmpSecs(bs.totalPowerMah))
                    .build();
            pulledData.add(e);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDebugElapsedClock() {
        // No op.
    }

    private void pullDebugElapsedClock() {
        // No op.
    }

    private void registerDebugFailingElapsedClock() {
        // No op.
    }

    private void pullDebugFailingElapsedClock() {
        // No op.
    }

    private void registerBuildInformation() {
        int tagId = StatsLog.BUILD_INFORMATION;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                BackgroundThread.getExecutor(),
                (atomTag, data) -> pullBuildInformation(atomTag, data)
        );
    }

    private int pullBuildInformation(int atomTag, List<StatsEvent> pulledData) {
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeString(Build.FINGERPRINT)
                .writeString(Build.BRAND)
                .writeString(Build.PRODUCT)
                .writeString(Build.DEVICE)
                .writeString(Build.VERSION.RELEASE)
                .writeString(Build.ID)
                .writeString(Build.VERSION.INCREMENTAL)
                .writeString(Build.TYPE)
                .writeString(Build.TAGS)
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerRoleHolder() {
        int tagId = StatsLog.ROLE_HOLDER;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullRoleHolder(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    // Add a RoleHolder atom for each package that holds a role.
    private int pullRoleHolder(int atomTag, List<StatsEvent> pulledData) {
        long callingToken = Binder.clearCallingIdentity();
        try {
            PackageManager pm = mContext.getPackageManager();
            RoleManagerInternal rmi = LocalServices.getService(RoleManagerInternal.class);

            List<UserInfo> users = mContext.getSystemService(UserManager.class).getUsers();

            int numUsers = users.size();
            for (int userNum = 0; userNum < numUsers; userNum++) {
                int userId = users.get(userNum).getUserHandle().getIdentifier();

                ArrayMap<String, ArraySet<String>> roles = rmi.getRolesAndHolders(userId);

                int numRoles = roles.size();
                for (int roleNum = 0; roleNum < numRoles; roleNum++) {
                    String roleName = roles.keyAt(roleNum);
                    ArraySet<String> holders = roles.valueAt(roleNum);

                    int numHolders = holders.size();
                    for (int holderNum = 0; holderNum < numHolders; holderNum++) {
                        String holderName = holders.valueAt(holderNum);

                        PackageInfo pkg;
                        try {
                            pkg = pm.getPackageInfoAsUser(holderName, 0, userId);
                        } catch (PackageManager.NameNotFoundException e) {
                            Slog.w(TAG, "Role holder " + holderName + " not found");
                            return StatsManager.PULL_SKIP;
                        }

                        StatsEvent e = StatsEvent.newBuilder()
                                .setAtomId(atomTag)
                                .writeInt(pkg.applicationInfo.uid)
                                .writeString(holderName)
                                .writeString(roleName)
                                .build();
                        pulledData.add(e);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerDangerousPermissionState() {
        int tagId = StatsLog.DANGEROUS_PERMISSION_STATE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullDangerousPermissionState(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullDangerousPermissionState(int atomTag, List<StatsEvent> pulledData) {
        final long token = Binder.clearCallingIdentity();
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

                    if (atomTag == StatsLog.DANGEROUS_PERMISSION_STATE_SAMPLED
                            && ThreadLocalRandom.current().nextFloat() > 0.2f) {
                        continue;
                    }

                    int numPerms = pkg.requestedPermissions.length;
                    for (int permNum  = 0; permNum < numPerms; permNum++) {
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

                        if (permissionInfo.getProtection() != PROTECTION_DANGEROUS) {
                            continue;
                        }

                        StatsEvent.Builder e = StatsEvent.newBuilder();
                        e.setAtomId(atomTag);
                        e.writeString(permName);
                        e.writeInt(pkg.applicationInfo.uid);
                        if (atomTag == StatsLog.DANGEROUS_PERMISSION_STATE) {
                            e.writeString("");
                        }
                        e.writeBoolean((pkg.requestedPermissionsFlags[permNum]
                                & REQUESTED_PERMISSION_GRANTED) != 0);
                        e.writeInt(permissionFlags);

                        pulledData.add(e.build());
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
        int tagId = StatsLog.TIME_ZONE_DATA_INFO;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullTimeZoneDataInfo(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullTimeZoneDataInfo(int atomTag, List<StatsEvent> pulledData) {
        String tzDbVersion = "Unknown";
        try {
            tzDbVersion = android.icu.util.TimeZone.getTZDataVersion();
        } catch (MissingResourceException e) {
            Slog.e(TAG, "Getting tzdb version failed: ", e);
            return StatsManager.PULL_SKIP;
        }

        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeString(tzDbVersion)
                .build();
        pulledData.add(e);
        return StatsManager.PULL_SUCCESS;
    }

    private void registerExternalStorageInfo() {
        int tagId = StatsLog.EXTERNAL_STORAGE_INFO;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullExternalStorageInfo(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullExternalStorageInfo(int atomTag, List<StatsEvent> pulledData) {
        if (mStorageManager == null) {
            return StatsManager.PULL_SKIP;
        }

        List<VolumeInfo> volumes = mStorageManager.getVolumes();
        for (VolumeInfo vol : volumes) {
            final String envState = VolumeInfo.getEnvironmentForState(vol.getState());
            final DiskInfo diskInfo = vol.getDisk();
            if (diskInfo != null && envState.equals(Environment.MEDIA_MOUNTED)) {
                // Get the type of the volume, if it is adoptable or portable.
                int volumeType = StatsLog.EXTERNAL_STORAGE_INFO__VOLUME_TYPE__OTHER;
                if (vol.getType() == TYPE_PUBLIC) {
                    volumeType = StatsLog.EXTERNAL_STORAGE_INFO__VOLUME_TYPE__PUBLIC;
                } else if (vol.getType() == TYPE_PRIVATE) {
                    volumeType = StatsLog.EXTERNAL_STORAGE_INFO__VOLUME_TYPE__PRIVATE;
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

                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(externalStorageType)
                        .writeInt(volumeType)
                        .writeLong(diskInfo.size)
                        .build();
                pulledData.add(e);
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerAppsOnExternalStorageInfo() {
        int tagId = StatsLog.APPS_ON_EXTERNAL_STORAGE_INFO;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullAppsOnExternalStorageInfo(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullAppsOnExternalStorageInfo(int atomTag, List<StatsEvent> pulledData) {
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
                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeInt(externalStorageType)
                        .writeString(appInfo.packageName)
                        .build();
                pulledData.add(e);
            }
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerFaceSettings() {
        int tagId = StatsLog.FACE_SETTINGS;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullFaceSettings(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullFaceSettings(int atomTag, List<StatsEvent> pulledData) {
        final long callingToken = Binder.clearCallingIdentity();
        try {
            List<UserInfo> users = mContext.getSystemService(UserManager.class).getUsers();
            int numUsers = users.size();
            for (int userNum = 0; userNum < numUsers; userNum++) {
                int userId = users.get(userNum).getUserHandle().getIdentifier();

                int unlockKeyguardEnabled = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_KEYGUARD_ENABLED, 1, userId);
                int unlockDismissesKeyguard = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD, 0, userId);
                int unlockAttentionRequired = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_ATTENTION_REQUIRED, 1, userId);
                int unlockAppEnabled = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_APP_ENABLED, 1, userId);
                int unlockAlwaysRequireConfirmation = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION, 0, userId);
                int unlockDiversityRequired = Settings.Secure.getIntForUser(
                          mContext.getContentResolver(),
                          Settings.Secure.FACE_UNLOCK_DIVERSITY_REQUIRED, 1, userId);

                StatsEvent e = StatsEvent.newBuilder()
                        .setAtomId(atomTag)
                        .writeBoolean(unlockKeyguardEnabled != 0)
                        .writeBoolean(unlockDismissesKeyguard != 0)
                        .writeBoolean(unlockAttentionRequired != 0)
                        .writeBoolean(unlockAppEnabled != 0)
                        .writeBoolean(unlockAlwaysRequireConfirmation != 0)
                        .writeBoolean(unlockDiversityRequired != 0)
                        .build();
                pulledData.add(e);
            }
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
        return StatsManager.PULL_SUCCESS;
    }

    private void registerAppOps() {
        int tagId = StatsLog.APP_OPS;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullAppOps(atomTag, data),
                BackgroundThread.getExecutor()
        );

    }

    private int pullAppOps(int atomTag, List<StatsEvent> pulledData) {
        long token = Binder.clearCallingIdentity();
        try {
            AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);

            CompletableFuture<HistoricalOps> ops = new CompletableFuture<>();
            HistoricalOpsRequest histOpsRequest =
                    new HistoricalOpsRequest.Builder(0, Long.MAX_VALUE).build();
            appOps.getHistoricalOps(histOpsRequest, mContext.getMainExecutor(), ops::complete);

            HistoricalOps histOps = ops.get(EXTERNAL_STATS_SYNC_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);

            for (int uidIdx = 0; uidIdx < histOps.getUidCount(); uidIdx++) {
                final HistoricalUidOps uidOps = histOps.getUidOpsAt(uidIdx);
                final int uid = uidOps.getUid();
                for (int pkgIdx = 0; pkgIdx < uidOps.getPackageCount(); pkgIdx++) {
                    final HistoricalPackageOps packageOps = uidOps.getPackageOpsAt(pkgIdx);
                    for (int opIdx = 0; opIdx < packageOps.getOpCount(); opIdx++) {
                        final AppOpsManager.HistoricalOp op  = packageOps.getOpAt(opIdx);

                        StatsEvent.Builder e = StatsEvent.newBuilder();
                        e.setAtomId(atomTag);
                        e.writeInt(uid);
                        e.writeString(packageOps.getPackageName());
                        e.writeInt(op.getOpCode());
                        e.writeLong(op.getForegroundAccessCount(OP_FLAGS_ALL_TRUSTED));
                        e.writeLong(op.getBackgroundAccessCount(OP_FLAGS_ALL_TRUSTED));
                        e.writeLong(op.getForegroundRejectCount(OP_FLAGS_ALL_TRUSTED));
                        e.writeLong(op.getBackgroundRejectCount(OP_FLAGS_ALL_TRUSTED));
                        e.writeLong(op.getForegroundAccessDuration(OP_FLAGS_ALL_TRUSTED));
                        e.writeLong(op.getBackgroundAccessDuration(OP_FLAGS_ALL_TRUSTED));

                        String perm = AppOpsManager.opToPermission(op.getOpCode());
                        if (perm == null) {
                            e.writeBoolean(false);
                        } else {
                            PermissionInfo permInfo;
                            try {
                                permInfo = mContext.getPackageManager().getPermissionInfo(perm, 0);
                                e.writeBoolean(permInfo.getProtection() == PROTECTION_DANGEROUS);
                            } catch (PackageManager.NameNotFoundException exception) {
                                e.writeBoolean(false);
                            }
                        }

                        pulledData.add(e.build());
                    }
                }
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

    static void unpackStreamedData(int atomTag, List<StatsEvent> pulledData,
            List<ParcelFileDescriptor> statsFiles) throws IOException {
        InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(statsFiles.get(0));
        int[] len = new int[1];
        byte[] stats = readFully(stream, len);
        StatsEvent e = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeByteArray(Arrays.copyOf(stats, len[0]))
                .build();
        pulledData.add(e);
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
        int tagId = StatsLog.NOTIFICATION_REMOTE_VIEWS;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullNotificationRemoteViews(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }

    private int pullNotificationRemoteViews(int atomTag, List<StatsEvent> pulledData) {
        INotificationManager notificationManagerService = getINotificationManagerService();
        if (notificationManagerService == null) {
            return StatsManager.PULL_SKIP;
        }
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // determine last pull tine. Copy file trick from pullProcessStats?
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
        int tagId = StatsLog.DANGEROUS_PERMISSION_STATE_SAMPLED;
        mStatsManager.registerPullAtomCallback(
                tagId,
                null, // use default PullAtomMetadata values
                (atomTag, data) -> pullDangerousPermissionState(atomTag, data),
                BackgroundThread.getExecutor()
        );
    }
}
