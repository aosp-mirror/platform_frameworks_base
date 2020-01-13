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

package com.android.server.stats;

import static android.app.AppOpsManager.OP_FLAGS_ALL_TRUSTED;
import static android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED;
import static android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static android.os.Process.getUidForPid;
import static android.os.storage.VolumeInfo.TYPE_PRIVATE;
import static android.os.storage.VolumeInfo.TYPE_PUBLIC;

import static com.android.server.am.MemoryStatUtil.readMemoryStatFromFilesystem;
import static com.android.server.stats.IonMemoryUtil.readProcessSystemIonHeapSizesFromDebugfs;
import static com.android.server.stats.IonMemoryUtil.readSystemIonHeapSizeFromDebugfs;
import static com.android.server.stats.ProcfsMemoryUtil.forEachPid;
import static com.android.server.stats.ProcfsMemoryUtil.readCmdlineFromProcfs;
import static com.android.server.stats.ProcfsMemoryUtil.readMemorySnapshotFromProcfs;

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
import com.android.server.stats.IonMemoryUtil.IonAllocations;
import com.android.server.stats.ProcfsMemoryUtil.MemorySnapshot;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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

    private final Context mContext;
    private StatsManager mStatsManager;

    public StatsPullAtomService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        mStatsManager = (StatsManager) mContext.getSystemService(Context.STATS_MANAGER);

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
        registerProcessSystemIonHeapSize();
        registerTemperature();
        registerCoolingDevice();
        registerBinderCalls();
        registerBinderCallsExceptions();
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
        registerDangerousPermissionState();
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
    private void registerWifiBytesTransfer() {
        int tagId = StatsLog.WIFI_BYTES_TRANSFER;
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {2, 3, 4, 5})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullWifiBytesTransfer(atomTag, data),
                Executors.newSingleThreadExecutor()
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
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {3, 4, 5, 6})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullWifiBytesTransferBackground(atomTag, data),
                Executors.newSingleThreadExecutor()
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
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {2, 3, 4, 5})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullMobileBytesTransfer(atomTag, data),
                Executors.newSingleThreadExecutor()
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
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {3, 4, 5, 6})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullMobileBytesTransferBackground(atomTag, data),
                Executors.newSingleThreadExecutor()
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
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {2, 3})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullBluetoothBytesTransfer(atomTag, data),
                Executors.newSingleThreadExecutor()
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
                (atomTag, data) -> pullKernelWakelock(atomTag, data),
                Executors.newSingleThreadExecutor()
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
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {3})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullCpuTimePerFreq(atomTag, data),
                Executors.newSingleThreadExecutor()
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
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {2, 3})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullCpuTimePerUid(atomTag, data),
                Executors.newSingleThreadExecutor()
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
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {4})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullCpuTimeperUidFreq(atomTag, data),
                Executors.newSingleThreadExecutor()
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
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {2})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullCpuActiveTime(atomTag, data),
                Executors.newSingleThreadExecutor()
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
        PullAtomMetadata metadata = PullAtomMetadata.newBuilder()
                .setAdditiveFields(new int[] {3})
                .build();
        mStatsManager.registerPullAtomCallback(
                tagId,
                metadata,
                (atomTag, data) -> pullCpuClusterTime(atomTag, data),
                Executors.newSingleThreadExecutor()
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
        // No op.
    }

    private void pullWifiActivityInfo() {
        // No op.
    }

    private void registerModemActivityInfo() {
        // No op.
    }

    private void pullModemActivityInfo() {
        // No op.
    }

    private void registerBluetoothActivityInfo() {
        int tagId = StatsLog.BLUETOOTH_ACTIVITY_INFO;
        mStatsManager.registerPullAtomCallback(
                tagId,
                /* metadata */ null,
                (atomTag, data) -> pullBluetoothActivityInfo(atomTag, data),
                Executors.newSingleThreadExecutor()
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

    private void registerSystemElapsedRealtime() {
        // No op.
    }

    private void pullSystemElapsedRealtime() {
        // No op.
    }

    private void registerSystemUptime() {
        // No op.
    }

    private void pullSystemUptime() {
        // No op.
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
        // No op.
    }

    private void pullProcessMemoryState() {
        // No op.
    }

    private void registerProcessMemoryHighWaterMark() {
        // No op.
    }

    private void pullProcessMemoryHighWaterMark() {
        // No op.
    }

    private void registerProcessMemorySnapshot() {
        // No op.
    }

    private void pullProcessMemorySnapshot() {
        // No op.
    }

    private void registerSystemIonHeapSize() {
        // No op.
    }

    private void pullSystemIonHeapSize() {
        // No op.
    }

    private void registerProcessSystemIonHeapSize() {
        // No op.
    }

    private void pullProcessSystemIonHeapSize() {
        // No op.
    }

    private void registerTemperature() {
        // No op.
    }

    private void pullTemperature() {
        // No op.
    }

    private void registerCoolingDevice() {
        // No op.
    }

    private void pullCooldownDevice() {
        // No op.
    }

    private void registerBinderCalls() {
        // No op.
    }

    private void pullBinderCalls() {
        // No op.
    }

    private void registerBinderCallsExceptions() {
        // No op.
    }

    private void pullBinderCallsExceptions() {
        // No op.
    }

    private void registerLooperStats() {
        // No op.
    }

    private void pullLooperStats() {
        // No op.
    }

    private void registerDiskStats() {
        // No op.
    }

    private void pullDiskStats() {
        // No op.
    }

    private void registerDirectoryUsage() {
        // No op.
    }

    private void pullDirectoryUsage() {
        // No op.
    }

    private void registerAppSize() {
        // No op.
    }

    private void pullAppSize() {
        // No op.
    }

    private void registerCategorySize() {
        // No op.
    }

    private void pullCategorySize() {
        // No op.
    }

    private void registerNumFingerprintsEnrolled() {
        // No op.
    }

    private void pullNumFingerprintsEnrolled() {
        // No op.
    }

    private void registerNumFacesEnrolled() {
        // No op.
    }

    private void pullNumFacesEnrolled() {
        // No op.
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

    private void registerDiskIO() {
        // No op.
    }

    private void pullDiskIO() {
        // No op.
    }

    private void registerPowerProfile() {
        int tagId = StatsLog.POWER_PROFILE;
        mStatsManager.registerPullAtomCallback(
                tagId,
                /* PullAtomMetadata */ null,
                (atomTag, data) -> pullPowerProfile(atomTag, data),
                Executors.newSingleThreadExecutor()
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

    private void registerProcessCpuTime() {
        // No op.
    }

    private void pullProcessCpuTime() {
        // No op.
    }

    private void registerCpuTimePerThreadFreq() {
        // No op.
    }

    private void pullCpuTimePerThreadFreq() {
        // No op.
    }

    private void registerDeviceCalculatedPowerUse() {
        // No op.
    }

    private void pullDeviceCalculatedPowerUse() {
        // No op.
    }

    private void registerDeviceCalculatedPowerBlameUid() {
        // No op.
    }

    private void pullDeviceCalculatedPowerBlameUid() {
        // No op.
    }

    private void registerDeviceCalculatedPowerBlameOther() {
        // No op.
    }

    private void pullDeviceCalculatedPowerBlameOther() {
        // No op.
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
        // No op.
    }

    private void pullBuildInformation() {
        // No op.
    }

    private void registerRoleHolder() {
        // No op.
    }

    private void pullRoleHolder() {
        // No op.
    }

    private void registerDangerousPermissionState() {
        // No op.
    }

    private void pullDangerousPermissionState() {
        // No op.
    }

    private void registerTimeZoneDataInfo() {
        // No op.
    }

    private void pullTimeZoneDataInfo() {
        // No op.
    }

    private void registerExternalStorageInfo() {
        // No op.
    }

    private void pullExternalStorageInfo() {
        // No op.
    }

    private void registerAppsOnExternalStorageInfo() {
        // No op.
    }

    private void pullAppsOnExternalStorageInfo() {
        // No op.
    }

    private void registerFaceSettings() {
        // No op.
    }

    private void pullRegisterFaceSettings() {
        // No op.
    }

    private void registerAppOps() {
        // No op.
    }

    private void pullAppOps() {
        // No op.
    }

    private void registerNotificationRemoteViews() {
        // No op.
    }

    private void pullNotificationRemoteViews() {
        // No op.
    }

    private void registerDangerousPermissionStateSampled() {
        // No op.
    }

    private void pullDangerousPermissionStateSampled() {
        // No op.
    }
}
