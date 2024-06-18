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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.BatteryStats;
import android.os.Build;
import android.os.Bundle;
import android.os.IPowerStatsService;
import android.os.OutcomeReceiver;
import android.os.PowerMonitor;
import android.os.PowerMonitorReadings;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;

import com.android.internal.app.IBatteryStats;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
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
 */
@SystemService(Context.SYSTEM_HEALTH_SERVICE)
public class SystemHealthManager {
    @NonNull
    private final IBatteryStats mBatteryStats;
    @Nullable
    private final IPowerStatsService mPowerStats;
    private List<PowerMonitor> mPowerMonitorsInfo;
    private final Object mPowerMonitorsLock = new Object();

    /**
     * Construct a new SystemHealthManager object.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public SystemHealthManager() {
        this(IBatteryStats.Stub.asInterface(ServiceManager.getService(BatteryStats.SERVICE_NAME)),
                IPowerStatsService.Stub.asInterface(
                        ServiceManager.getService(Context.POWER_STATS_SERVICE)));
    }

    /** {@hide} */
    public SystemHealthManager(@NonNull IBatteryStats batteryStats,
            @Nullable IPowerStatsService powerStats) {
        mBatteryStats = batteryStats;
        mPowerStats = powerStats;
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
        try {
            final HealthStatsParceler parceler = mBatteryStats.takeUidSnapshot(uid);
            return parceler.getHealthStats();
        } catch (RemoteException ex) {
            throw new RuntimeException(ex);
        }
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
        try {
            final HealthStatsParceler[] parcelers = mBatteryStats.takeUidSnapshots(uids);
            final HealthStats[] results = new HealthStats[uids.length];
            final int N = uids.length;
            for (int i = 0; i < N; i++) {
                results[i] = parcelers[i].getHealthStats();
            }
            return results;
        } catch (RemoteException ex) {
            throw new RuntimeException(ex);
        }
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
                        executor.execute(()-> onResult.accept(result));
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
