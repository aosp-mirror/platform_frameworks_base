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

    private final Context mContext;
    private StatsManager mStatsManager;

    public StatsPullAtomService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        mStatsManager = (StatsManager) mContext.getSystemService(Context.STATS_MANAGER);
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mTelephony = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);

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

    private void registerSystemElapsedRealtime() {
        // No op.
    }

    private void pullSystemElapsedRealtime() {
        // No op.
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
