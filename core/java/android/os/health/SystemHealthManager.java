/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os.health;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.hardware.power.CpuHeadroomResult;
import android.hardware.power.GpuHeadroomResult;
import android.os.BatteryStats;
import android.os.Build;
import android.os.Bundle;
import android.os.CpuHeadroomParams;
import android.os.CpuHeadroomParamsInternal;
import android.os.GpuHeadroomParams;
import android.os.GpuHeadroomParamsInternal;
import android.os.IHintManager;
import android.os.IPowerStatsService;
import android.os.OutcomeReceiver;
import android.os.PowerMonitor;
import android.os.PowerMonitorReadings;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SynchronousResultReceiver;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.app.IBatteryStats;
import com.android.server.power.optimization.Flags;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Provides access to data about how various system resources are used by applications.
 * @more
 * <p>
 * If you are going to be using this class to log your application's resource usage,
 * please consider the amount of resources (battery, network, etc) that will be used
 * by the logging itself.  It can be substantial.
 * <p>
 * <b>Battery Usage</b><br>
 * Since Android version {@link android.os.Build.VERSION_CODES#Q}, the statistics related to power
 * (battery) usage are recorded since the device was last considered fully charged (for previous
 * versions, it is instead since the device was last unplugged).
 * It is expected that applications schedule more work to do while the device is
 * plugged in (e.g. using {@link android.app.job.JobScheduler JobScheduler}), and
 * while that can affect charging rates, it is still preferable to actually draining
 * the battery.
 * <p>
 * <b>CPU/GPU Usage</b><br>
 * CPU/GPU headroom APIs are designed to be best used by applications with consistent and intense
 * workload such as games to query the remaining capacity headroom over a short period and perform
 * optimization accordingly. Due to the nature of the fast job scheduling and frequency scaling of
 * CPU and GPU, the headroom by nature will have "TOCTOU" problem which makes it less suitable for
 * apps with inconsistent or low workload to take any useful action but simply monitoring. And to
 * avoid oscillation it's not recommended to adjust workload too frequent (on each polling request)
 * or too aggressively. As the headroom calculation is more based on reflecting past history usage
 * than predicting future capacity. Take game as an example, if the API returns CPU headroom of 0 in
 * one scenario (especially if it's constant across multiple calls), or some value significantly
 * smaller than other scenarios, then it can reason that the recent performance result is more CPU
 * bottlenecked. Then reducing the CPU workload intensity can help reserve some headroom to handle
 * the load variance better, which can result in less frame drops or smooth FPS value. On the other
 * hand, if the API returns large CPU headroom constantly, the app can be more confident to increase
 * the workload and expect higher possibility of device meeting its performance expectation.
 * App can also use thermal APIs to read the current thermal status and headroom first, then poll
 * the CPU and GPU headroom if the device is (about to) getting thermal throttled. If the CPU/GPU
 * headrooms provide enough significance such as one valued at 0 while the other at 100, then it can
 * be used to infer that reducing CPU workload could be more efficient to cool down the device.
 * There is a caveat that the power controller may scale down the frequency of the CPU and GPU due
 * to thermal and other reasons, which can result in a higher than usual percentage usage of the
 * capacity.
 */
@SystemService(Context.SYSTEM_HEALTH_SERVICE)
public class SystemHealthManager {
    private static final String TAG = "SystemHealthManager";
    @NonNull
    private final IBatteryStats mBatteryStats;
    @Nullable
    private final IPowerStatsService mPowerStats;
    @Nullable
    private final IHintManager mHintManager;
    @Nullable
    private final IHintManager.HintManagerClientData mHintManagerClientData;
    private List<PowerMonitor> mPowerMonitorsInfo;
    private final Object mPowerMonitorsLock = new Object();
    private static final long TAKE_UID_SNAPSHOT_TIMEOUT_MILLIS = 10_000;

    private static class PendingUidSnapshots {
        public int[] uids;
        public SynchronousResultReceiver resultReceiver;
    }

    private final PendingUidSnapshots mPendingUidSnapshots = new PendingUidSnapshots();

    /**
     * Construct a new SystemHealthManager object.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public SystemHealthManager() {
        this(IBatteryStats.Stub.asInterface(ServiceManager.getService(BatteryStats.SERVICE_NAME)),
                IPowerStatsService.Stub.asInterface(
                        ServiceManager.getService(Context.POWER_STATS_SERVICE)),
                IHintManager.Stub.asInterface(
                        ServiceManager.getService(Context.PERFORMANCE_HINT_SERVICE)));
    }

    /** {@hide} */
    public SystemHealthManager(@NonNull IBatteryStats batteryStats,
            @Nullable IPowerStatsService powerStats, @Nullable IHintManager hintManager) {
        mBatteryStats = batteryStats;
        mPowerStats = powerStats;
        mHintManager = hintManager;
        IHintManager.HintManagerClientData data = null;
        if (mHintManager != null) {
            try {
                data = mHintManager.getClientData();
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get hint manager client data", e);
            }
        }
        mHintManagerClientData = data;
    }

    /**
     * Provides an estimate of available CPU capacity headroom of the device.
     * <p>
     * The value can be used by the calling application to determine if the workload was CPU bound
     * and then take action accordingly to ensure that the workload can be completed smoothly. It
     * can also be used with the thermal status and headroom to determine if reducing the CPU bound
     * workload can help reduce the device temperature to avoid thermal throttling.
     * <p>
     * If the params are valid, each call will perform at least one synchronous binder transaction
     * that can take more than 1ms. So it's not recommended to call or wait for this on critical
     * threads. Some devices may implement this as an on-demand API with lazy initialization, so the
     * caller should expect higher latency when making the first call (especially with non-default
     * params) since app starts or after changing params, as the device may need to change its data
     * collection.
     *
     * @param  params params to customize the CPU headroom calculation, or null to use default.
     * @return a single value headroom or a {@code Float.NaN} if it's temporarily unavailable due to
     *         server error or not enough user CPU workload.
     *         Each valid value ranges from [0, 100], where 0 indicates no more cpu resources can be
     *         granted
     * @throws UnsupportedOperationException if the API is unsupported.
     * @throws IllegalArgumentException if the params are invalid.
     * @throws SecurityException if the TIDs of the params don't belong to the same process.
     * @throws IllegalStateException if the TIDs of the params don't have the same affinity setting.
     */
    @FlaggedApi(android.os.Flags.FLAG_CPU_GPU_HEADROOMS)
    public @FloatRange(from = 0f, to = 100f) float getCpuHeadroom(
            @Nullable CpuHeadroomParams params) {
        if (mHintManager == null || mHintManagerClientData == null
                || !mHintManagerClientData.supportInfo.headroom.isCpuSupported) {
            throw new UnsupportedOperationException();
        }
        if (params != null) {
            if (params.mInternal.tids != null && (params.mInternal.tids.length == 0
                    || params.mInternal.tids.length
                    > mHintManagerClientData.maxCpuHeadroomThreads)) {
                throw new IllegalArgumentException(
                        "Invalid number of TIDs: " + params.mInternal.tids.length);
            }
            if (params.mInternal.calculationWindowMillis
                    < mHintManagerClientData.supportInfo.headroom.cpuMinCalculationWindowMillis
                    || params.mInternal.calculationWindowMillis
                    > mHintManagerClientData.supportInfo.headroom.cpuMaxCalculationWindowMillis) {
                throw new IllegalArgumentException(
                        "Invalid calculation window: "
                        + params.mInternal.calculationWindowMillis + ", expect range: ["
                        + mHintManagerClientData.supportInfo.headroom.cpuMinCalculationWindowMillis
                        + ", "
                        + mHintManagerClientData.supportInfo.headroom.cpuMaxCalculationWindowMillis
                        + "]");
            }
        }
        try {
            final CpuHeadroomResult ret = mHintManager.getCpuHeadroom(
                    params != null ? params.mInternal : new CpuHeadroomParamsInternal());
            if (ret == null || ret.getTag() != CpuHeadroomResult.globalHeadroom) {
                return Float.NaN;
            }
            return ret.getGlobalHeadroom();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the maximum number of TIDs this device supports for getting CPU headroom.
     * <p>
     * See {@link CpuHeadroomParams#setTids(int...)}.
     *
     * @return the maximum size of TIDs supported
     * @throws UnsupportedOperationException if the CPU headroom API is unsupported.
     */
    @FlaggedApi(android.os.Flags.FLAG_CPU_GPU_HEADROOMS)
    public @IntRange(from = 1) int getMaxCpuHeadroomTidsSize() {
        if (mHintManager == null || mHintManagerClientData == null
                || !mHintManagerClientData.supportInfo.headroom.isCpuSupported) {
            throw new UnsupportedOperationException();
        }
        return mHintManagerClientData.maxCpuHeadroomThreads;
    }

    /**
     * Provides an estimate of available GPU capacity headroom of the device.
     * <p>
     * The value can be used by the calling application to determine if the workload was GPU bound
     * and then take action accordingly to ensure that the workload can be completed smoothly. It
     * can also be used with the thermal status and headroom to determine if reducing the GPU bound
     * workload can help reduce the device temperature to avoid thermal throttling.
     * <p>
     * If the params are valid, each call will perform at least one synchronous binder transaction
     * that can take more than 1ms. So it's not recommended to call or wait for this on critical
     * threads. Some devices may implement this as an on-demand API with lazy initialization, so the
     * caller should expect higher latency when making the first call (especially with non-default
     * params) since app starts or after changing params, as the device may need to change its data
     * collection.
     *
     * @param  params params to customize the GPU headroom calculation, or null to use default.
     * @return a single value headroom or a {@code Float.NaN} if it's temporarily unavailable.
     *         Each valid value ranges from [0, 100], where 0 indicates no more cpu resources can be
     *         granted.
     * @throws UnsupportedOperationException if the API is unsupported.
     * @throws IllegalArgumentException if the params are invalid.
     */
    @FlaggedApi(android.os.Flags.FLAG_CPU_GPU_HEADROOMS)
    public @FloatRange(from = 0f, to = 100f) float getGpuHeadroom(
            @Nullable GpuHeadroomParams params) {
        if (mHintManager == null || mHintManagerClientData == null
                || !mHintManagerClientData.supportInfo.headroom.isGpuSupported) {
            throw new UnsupportedOperationException();
        }
        if (params != null) {
            if (params.mInternal.calculationWindowMillis
                    < mHintManagerClientData.supportInfo.headroom.gpuMinCalculationWindowMillis
                    || params.mInternal.calculationWindowMillis
                    > mHintManagerClientData.supportInfo.headroom.gpuMaxCalculationWindowMillis) {
                throw new IllegalArgumentException(
                        "Invalid calculation window: "
                        + params.mInternal.calculationWindowMillis + ", expect range: ["
                        + mHintManagerClientData.supportInfo.headroom.gpuMinCalculationWindowMillis
                        + ", "
                        + mHintManagerClientData.supportInfo.headroom.gpuMaxCalculationWindowMillis
                        + "]");
            }
        }
        try {
            final GpuHeadroomResult ret = mHintManager.getGpuHeadroom(
                    params != null ? params.mInternal : new GpuHeadroomParamsInternal());
            if (ret == null || ret.getTag() != GpuHeadroomResult.globalHeadroom) {
                return Float.NaN;
            }
            return ret.getGlobalHeadroom();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the range of the calculation window size for CPU headroom.
     * <p>
     * In API version 36, the range will be a superset of [50, 10000].
     * <p>
     * See {@link CpuHeadroomParams#setCalculationWindowMillis(int)}.
     *
     * @return the range of the calculation window size supported in milliseconds.
     * @throws UnsupportedOperationException if the CPU headroom API is unsupported.
     */
    @FlaggedApi(android.os.Flags.FLAG_CPU_GPU_HEADROOMS)
    @NonNull
    public Pair<Integer, Integer> getCpuHeadroomCalculationWindowRange() {
        if (mHintManager == null || mHintManagerClientData == null
                || !mHintManagerClientData.supportInfo.headroom.isCpuSupported) {
            throw new UnsupportedOperationException();
        }
        return new Pair<>(
                mHintManagerClientData.supportInfo.headroom.cpuMinCalculationWindowMillis,
                mHintManagerClientData.supportInfo.headroom.cpuMaxCalculationWindowMillis);
    }

    /**
     * Gets the range of the calculation window size for GPU headroom.
     * <p>
     * In API version 36, the range will be a superset of [50, 10000].
     * <p>
     * See {@link GpuHeadroomParams#setCalculationWindowMillis(int)}.
     *
     * @return the range of the calculation window size supported in milliseconds.
     * @throws UnsupportedOperationException if the GPU headroom API is unsupported.
     */
    @FlaggedApi(android.os.Flags.FLAG_CPU_GPU_HEADROOMS)
    @NonNull
    public Pair<Integer, Integer> getGpuHeadroomCalculationWindowRange() {
        if (mHintManager == null || mHintManagerClientData == null
                || !mHintManagerClientData.supportInfo.headroom.isGpuSupported) {
            throw new UnsupportedOperationException();
        }
        return new Pair<>(
                mHintManagerClientData.supportInfo.headroom.gpuMinCalculationWindowMillis,
                mHintManagerClientData.supportInfo.headroom.gpuMaxCalculationWindowMillis);
    }

    /**
     * Gets minimum polling interval for calling {@link #getCpuHeadroom(CpuHeadroomParams)} in
     * milliseconds.
     * <p>
     * The {@link #getCpuHeadroom(CpuHeadroomParams)} API may return cached result if called more
     * frequent than the interval.
     *
     * @throws UnsupportedOperationException if the API is unsupported.
     */
    @FlaggedApi(android.os.Flags.FLAG_CPU_GPU_HEADROOMS)
    public long getCpuHeadroomMinIntervalMillis() {
        if (mHintManager == null || mHintManagerClientData == null
                || !mHintManagerClientData.supportInfo.headroom.isCpuSupported) {
            throw new UnsupportedOperationException();
        }
        return mHintManagerClientData.supportInfo.headroom.cpuMinIntervalMillis;
    }

    /**
     * Gets minimum polling interval for calling {@link #getGpuHeadroom(GpuHeadroomParams)} in
     * milliseconds.
     * <p>
     * The {@link #getGpuHeadroom(GpuHeadroomParams)} API may return cached result if called more
     * frequent than the interval.
     *
     * @throws UnsupportedOperationException if the API is unsupported.
     */
    @FlaggedApi(android.os.Flags.FLAG_CPU_GPU_HEADROOMS)
    public long getGpuHeadroomMinIntervalMillis() {
        if (mHintManager == null || mHintManagerClientData == null
                || !mHintManagerClientData.supportInfo.headroom.isGpuSupported) {
            throw new UnsupportedOperationException();
        }
        return mHintManagerClientData.supportInfo.headroom.gpuMinIntervalMillis;
    }

    /**
     * Obtain a SystemHealthManager object for the supplied context.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static SystemHealthManager from(Context context) {
        return (SystemHealthManager) context.getSystemService(Context.SYSTEM_HEALTH_SERVICE);
    }

    /**
     * Return a {@link HealthStats} object containing a snapshot of system health
     * metrics for the given uid (user-id, which in usually corresponds to application).
     *
     * @param uid User ID for a given application.
     * @return A {@link HealthStats} object containing the metrics for the requested
     * application. The keys for this HealthStats object will be from the {@link UidHealthStats}
     * class.
     * @more An application must hold the {@link android.Manifest.permission#BATTERY_STATS
     * android.permission.BATTERY_STATS} permission in order to retrieve any HealthStats
     * other than its own.
     * @see Process#myUid() Process.myUid()
     */
    public HealthStats takeUidSnapshot(int uid) {
        if (!Flags.onewayBatteryStatsService()) {
            try {
                final HealthStatsParceler parceler = mBatteryStats.takeUidSnapshot(uid);
                return parceler.getHealthStats();
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
        final HealthStats[] result = takeUidSnapshots(new int[]{uid});
        if (result != null && result.length >= 1) {
            return result[0];
        }
        return null;
    }

    /**
     * Return a {@link HealthStats} object containing a snapshot of system health
     * metrics for the application calling this API. This method is the same as calling
     * {@code takeUidSnapshot(Process.myUid())}.
     *
     * @return A {@link HealthStats} object containing the metrics for this application. The keys
     * for this HealthStats object will be from the {@link UidHealthStats} class.
     */
    public HealthStats takeMyUidSnapshot() {
        return takeUidSnapshot(Process.myUid());
    }

    /**
     * Return a {@link HealthStats} object containing a snapshot of system health
     * metrics for the given uids (user-id, which in usually corresponds to application).
     *
     * @param uids An array of User IDs to retrieve.
     * @return An array of {@link HealthStats} objects containing the metrics for each of
     * the requested uids. The keys for this HealthStats object will be from the
     * {@link UidHealthStats} class.
     * @more An application must hold the {@link android.Manifest.permission#BATTERY_STATS
     * android.permission.BATTERY_STATS} permission in order to retrieve any HealthStats
     * other than its own.
     */
    public HealthStats[] takeUidSnapshots(int[] uids) {
        if (!Flags.onewayBatteryStatsService()) {
            try {
                final HealthStatsParceler[] parcelers = mBatteryStats.takeUidSnapshots(uids);
                final int count = uids.length;
                final HealthStats[] results = new HealthStats[count];
                for (int i = 0; i < count; i++) {
                    results[i] = parcelers[i].getHealthStats();
                }
                return results;
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        SynchronousResultReceiver resultReceiver;
        synchronized (mPendingUidSnapshots) {
            if (Arrays.equals(mPendingUidSnapshots.uids, uids)) {
                resultReceiver = mPendingUidSnapshots.resultReceiver;
            } else {
                mPendingUidSnapshots.uids = Arrays.copyOf(uids, uids.length);
                mPendingUidSnapshots.resultReceiver = resultReceiver =
                        new SynchronousResultReceiver("takeUidSnapshots");
                try {
                    mBatteryStats.takeUidSnapshotsAsync(uids, resultReceiver);
                } catch (RemoteException ex) {
                    throw ex.rethrowFromSystemServer();
                }
            }
        }

        SynchronousResultReceiver.Result result;
        try {
            result = resultReceiver.awaitResult(TAKE_UID_SNAPSHOT_TIMEOUT_MILLIS);
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        } finally {
            synchronized (mPendingUidSnapshots) {
                if (mPendingUidSnapshots.resultReceiver == resultReceiver) {
                    mPendingUidSnapshots.uids = null;
                    mPendingUidSnapshots.resultReceiver = null;
                }
            }
        }

        final HealthStats[] results = new HealthStats[uids.length];
        if (result.bundle != null) {
            HealthStatsParceler[] parcelers = result.bundle.getParcelableArray(
                    IBatteryStats.KEY_UID_SNAPSHOTS, HealthStatsParceler.class);
            if (parcelers != null && parcelers.length == uids.length) {
                for (int i = 0; i < parcelers.length; i++) {
                    results[i] = parcelers[i].getHealthStats();
                }
            }
        }
        return results;
    }

    /**
     * Asynchronously retrieves a list of supported  {@link PowerMonitor}'s, which include raw ODPM
     * (on-device power rail monitor) rails and modeled energy consumers.  If ODPM is unsupported
     * on this device this method delivers an empty list.
     *
     * @param executor optional Handler to deliver the callback. If not supplied, the callback
     *                 may be invoked on an arbitrary thread.
     * @param onResult callback for the result
     */
    @FlaggedApi("com.android.server.power.optimization.power_monitor_api")
    public void getSupportedPowerMonitors(@Nullable Executor executor,
            @NonNull Consumer<List<PowerMonitor>> onResult) {
        final List<PowerMonitor> result;
        synchronized (mPowerMonitorsLock) {
            if (mPowerMonitorsInfo != null) {
                result = mPowerMonitorsInfo;
            } else if (mPowerStats == null) {
                mPowerMonitorsInfo = List.of();
                result = mPowerMonitorsInfo;
            } else {
                result = null;
            }
        }
        if (result != null) {
            if (executor != null) {
                executor.execute(() -> onResult.accept(result));
            } else {
                onResult.accept(result);
            }
            return;
        }
        try {
            mPowerStats.getSupportedPowerMonitors(new ResultReceiver(null) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    PowerMonitor[] array = resultData.getParcelableArray(
                            IPowerStatsService.KEY_MONITORS, PowerMonitor.class);
                    List<PowerMonitor> result = array != null ? Arrays.asList(array) : List.of();
                    synchronized (mPowerMonitorsLock) {
                        mPowerMonitorsInfo = result;
                    }
                    if (executor != null) {
                        executor.execute(() -> onResult.accept(result));
                    } else {
                        onResult.accept(result);
                    }
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static final Comparator<PowerMonitor> POWER_MONITOR_COMPARATOR =
            Comparator.comparingInt(pm -> pm.index);

    /**
     * Asynchronously retrieves the accumulated power consumption reported by the specified power
     * monitors.
     *
     * @param powerMonitors power monitors to be retrieved.
     * @param executor      optional Executor to deliver the callbacks. If not supplied, the
     *                      callback may be invoked on an arbitrary thread.
     * @param onResult      callback for the result
     */
    @FlaggedApi("com.android.server.power.optimization.power_monitor_api")
    public void getPowerMonitorReadings(@NonNull List<PowerMonitor> powerMonitors,
            @Nullable Executor executor,
            @NonNull OutcomeReceiver<PowerMonitorReadings, RuntimeException> onResult) {
        if (mPowerStats == null) {
            IllegalArgumentException error =
                    new IllegalArgumentException("Unsupported power monitor");
            if (executor != null) {
                executor.execute(() -> onResult.onError(error));
            } else {
                onResult.onError(error);
            }
            return;
        }

        PowerMonitor[] powerMonitorsArray =
                powerMonitors.toArray(new PowerMonitor[powerMonitors.size()]);
        Arrays.sort(powerMonitorsArray, POWER_MONITOR_COMPARATOR);
        int[] indices = new int[powerMonitors.size()];
        for (int i = 0; i < powerMonitors.size(); i++) {
            indices[i] = powerMonitorsArray[i].index;
        }
        try {
            mPowerStats.getPowerMonitorReadings(indices, new ResultReceiver(null) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    if (resultCode == IPowerStatsService.RESULT_SUCCESS) {
                        PowerMonitorReadings result = new PowerMonitorReadings(powerMonitorsArray,
                                resultData.getLongArray(IPowerStatsService.KEY_ENERGY),
                                resultData.getLongArray(IPowerStatsService.KEY_TIMESTAMPS));
                        if (executor != null) {
                            executor.execute(() -> onResult.onResult(result));
                        } else {
                            onResult.onResult(result);
                        }
                    } else {
                        RuntimeException error;
                        if (resultCode == IPowerStatsService.RESULT_UNSUPPORTED_POWER_MONITOR) {
                            error = new IllegalArgumentException("Unsupported power monitor");
                        } else {
                            error = new IllegalStateException(
                                    "Unrecognized result code " + resultCode);
                        }
                        if (executor != null) {
                            executor.execute(() -> onResult.onError(error));
                        } else {
                            onResult.onError(error);
                        }
                    }
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
