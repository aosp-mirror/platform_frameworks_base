/*
 * Copyright (C) 2006-2007 The Android Open Source Project
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

package com.android.server.am;

import static android.Manifest.permission.BATTERY_STATS;
import static android.Manifest.permission.DEVICE_POWER;
import static android.Manifest.permission.NETWORK_STACK;
import static android.Manifest.permission.POWER_SAVER;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.os.BatteryStats.POWER_DATA_UNAVAILABLE;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.RequiresNoPermission;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.StatsManager;
import android.app.usage.NetworkStatsManager;
import android.bluetooth.BluetoothActivityEnergyInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.State;
import android.hardware.power.stats.StateResidency;
import android.hardware.power.stats.StateResidencyResult;
import android.net.ConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryConsumer;
import android.os.BatteryManagerInternal;
import android.os.BatteryStats;
import android.os.BatteryStatsInternal;
import android.os.BatteryStatsInternal.CpuWakeupSubsystem;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Binder;
import android.os.BluetoothBatteryStats;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Parcel;
import android.os.ParcelFormatException;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.WakeLockStats;
import android.os.WorkSource;
import android.os.connectivity.CellularBatteryStats;
import android.os.connectivity.GpsBatteryStats;
import android.os.connectivity.WifiActivityEnergyInfo;
import android.os.connectivity.WifiBatteryStats;
import android.os.health.HealthStatsParceler;
import android.os.health.HealthStatsWriter;
import android.os.health.UidHealthStats;
import android.power.PowerStatsInternal;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.telephony.DataConnectionRealTimeInfo;
import android.telephony.ModemActivityInfo;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.StatsEvent;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BinderCallsStats;
import com.android.internal.os.Clock;
import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.CpuScalingPolicyReader;
import com.android.internal.os.MonotonicClock;
import com.android.internal.os.PowerProfile;
import com.android.internal.os.RailStats;
import com.android.internal.os.RpmStats;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.ParseUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.NetworkCapabilitiesUtils;
import com.android.server.LocalServices;
import com.android.server.Watchdog;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.pm.UserManagerInternal;
import com.android.server.power.optimization.Flags;
import com.android.server.power.stats.AggregatedPowerStatsConfig;
import com.android.server.power.stats.BatteryExternalStatsWorker;
import com.android.server.power.stats.BatteryStatsDumpHelperImpl;
import com.android.server.power.stats.BatteryStatsImpl;
import com.android.server.power.stats.BatteryUsageStatsProvider;
import com.android.server.power.stats.CpuPowerStatsProcessor;
import com.android.server.power.stats.MobileRadioPowerStatsProcessor;
import com.android.server.power.stats.PhoneCallPowerStatsProcessor;
import com.android.server.power.stats.PowerStatsAggregator;
import com.android.server.power.stats.PowerStatsExporter;
import com.android.server.power.stats.PowerStatsScheduler;
import com.android.server.power.stats.PowerStatsStore;
import com.android.server.power.stats.PowerStatsUidResolver;
import com.android.server.power.stats.SystemServerCpuThreadReader.SystemServiceCpuThreadTimes;
import com.android.server.power.stats.WifiPowerStatsProcessor;
import com.android.server.power.stats.wakeups.CpuWakeupStats;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * All information we are collecting about things that can happen that impact
 * battery life.
 */
public final class BatteryStatsService extends IBatteryStats.Stub
        implements PowerManagerInternal.LowPowerModeListener,
        BatteryStatsImpl.PlatformIdleStateCallback,
        BatteryStatsImpl.EnergyStatsRetriever,
        Watchdog.Monitor {
    static final String TAG = "BatteryStatsService";
    static final String TRACE_TRACK_WAKEUP_REASON = "wakeup_reason";
    static final boolean DBG = false;

    private static IBatteryStats sService;

    private final PowerProfile mPowerProfile;
    private final CpuScalingPolicies mCpuScalingPolicies;
    private final MonotonicClock mMonotonicClock;
    private final BatteryStatsImpl.BatteryStatsConfig mBatteryStatsConfig;
    final BatteryStatsImpl mStats;
    final CpuWakeupStats mCpuWakeupStats;
    private final PowerStatsStore mPowerStatsStore;
    private final PowerStatsScheduler mPowerStatsScheduler;
    private final BatteryStatsImpl.UserInfoProvider mUserManagerUserInfoProvider;
    private final Context mContext;
    private final BatteryExternalStatsWorker mWorker;
    private final BatteryUsageStatsProvider mBatteryUsageStatsProvider;
    private final AtomicFile mConfigFile;
    private final BatteryStats.BatteryStatsDumpHelper mDumpHelper;
    private final PowerStatsUidResolver mPowerStatsUidResolver;
    private final AggregatedPowerStatsConfig mAggregatedPowerStatsConfig;

    private volatile boolean mMonitorEnabled = true;

    private native void getRailEnergyPowerStats(RailStats railStats);
    private CharsetDecoder mDecoderStat = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith("?");
    private static final int MAX_LOW_POWER_STATS_SIZE = 32768;
    private static final int POWER_STATS_QUERY_TIMEOUT_MILLIS = 2000;
    private static final String DEVICE_CONFIG_NAMESPACE = "backstage_power";
    private static final String MIN_CONSUMED_POWER_THRESHOLD_KEY = "min_consumed_power_threshold";
    private static final String EMPTY = "Empty";

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;
    private final Object mLock = new Object();

    private final Object mPowerStatsLock = new Object();
    @GuardedBy("mPowerStatsLock")
    private PowerStatsInternal mPowerStatsInternal = null;
    @GuardedBy("mPowerStatsLock")
    private Map<Integer, String> mEntityNames = new HashMap();
    @GuardedBy("mPowerStatsLock")
    private Map<Integer, Map<Integer, String>> mStateNames = new HashMap();

    @GuardedBy("mStats")
    private int mLastPowerStateFromRadio = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
    @GuardedBy("mStats")
    private int mLastPowerStateFromWifi = DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
    private final INetworkManagementEventObserver mActivityChangeObserver =
            new BaseNetworkObserver() {
                @Override
                public void interfaceClassDataActivityChanged(int transportType, boolean active,
                        long tsNanos, int uid) {
                    final int powerState = active
                            ? DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH
                            : DataConnectionRealTimeInfo.DC_POWER_STATE_LOW;
                    final long timestampNanos;
                    if (tsNanos <= 0) {
                        timestampNanos = SystemClock.elapsedRealtimeNanos();
                    } else {
                        timestampNanos = tsNanos;
                    }

                    switch (transportType) {
                        case NetworkCapabilities.TRANSPORT_CELLULAR:
                            noteMobileRadioPowerState(powerState, timestampNanos, uid);
                            break;
                        case NetworkCapabilities.TRANSPORT_WIFI:
                            noteWifiRadioPowerState(powerState, timestampNanos, uid);
                            break;
                        default:
                            Slog.d(TAG, "Received unexpected transport in "
                                    + "interfaceClassDataActivityChanged unexpected type: "
                                    + transportType);
                    }
                }
            };

    private BatteryManagerInternal mBatteryManagerInternal;

    private void populatePowerEntityMaps() {
        PowerEntity[] entities = mPowerStatsInternal.getPowerEntityInfo();
        if (entities == null) {
            return;
        }

        for (int i = 0; i < entities.length; i++) {
            final PowerEntity entity = entities[i];
            Map<Integer, String> states = new HashMap();
            for (int j = 0; j < entity.states.length; j++) {
                final State state = entity.states[j];
                states.put(state.id, state.name);
            }

            mEntityNames.put(entity.id, entity.name);
            mStateNames.put(entity.id, states);
        }
    }

    /**
     * Replaces the information in the given rpmStats with up-to-date information.
     */
    @Override
    public void fillLowPowerStats(RpmStats rpmStats) {
        synchronized (mPowerStatsLock) {
            if (mPowerStatsInternal == null || mEntityNames.isEmpty() || mStateNames.isEmpty()) {
                return;
            }
        }

        final StateResidencyResult[] results;
        try {
            results = mPowerStatsInternal.getStateResidencyAsync(new int[0])
                    .get(POWER_STATS_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to getStateResidencyAsync", e);
            return;
        }

        if (results == null) return;

        for (int i = 0; i < results.length; i++) {
            final StateResidencyResult result = results[i];
            RpmStats.PowerStateSubsystem subsystem =
                    rpmStats.getSubsystem(mEntityNames.get(result.id));

            for (int j = 0; j < result.stateResidencyData.length; j++) {
                final StateResidency stateResidency = result.stateResidencyData[j];
                subsystem.putState(mStateNames.get(result.id).get(stateResidency.id),
                        stateResidency.totalTimeInStateMs,
                        (int) stateResidency.totalStateEntryCount);
            }
        }
    }

    @Override
    public void fillRailDataStats(RailStats railStats) {
        if (DBG) Slog.d(TAG, "begin getRailEnergyPowerStats");
        try {
            getRailEnergyPowerStats(railStats);
        } finally {
            if (DBG) Slog.d(TAG, "end getRailEnergyPowerStats");
        }
    }

    @Override
    public String getSubsystemLowPowerStats() {
        synchronized (mPowerStatsLock) {
            if (mPowerStatsInternal == null || mEntityNames.isEmpty() || mStateNames.isEmpty()) {
                return EMPTY;
            }
        }

        final StateResidencyResult[] results;
        try {
            results = mPowerStatsInternal.getStateResidencyAsync(new int[0])
                    .get(POWER_STATS_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to getStateResidencyAsync", e);
            return EMPTY;
        }

        if (results == null || results.length == 0) return EMPTY;

        int charsLeft = MAX_LOW_POWER_STATS_SIZE;
        StringBuilder builder = new StringBuilder("SubsystemPowerState");
        for (int i = 0; i < results.length; i++) {
            final StateResidencyResult result = results[i];
            StringBuilder subsystemBuilder = new StringBuilder();
            subsystemBuilder.append(" subsystem_" + i);
            subsystemBuilder.append(" name=" + mEntityNames.get(result.id));

            for (int j = 0; j < result.stateResidencyData.length; j++) {
                final StateResidency stateResidency = result.stateResidencyData[j];
                subsystemBuilder.append(" state_" + j);
                subsystemBuilder.append(" name=" + mStateNames.get(result.id).get(
                        stateResidency.id));
                subsystemBuilder.append(" time=" + stateResidency.totalTimeInStateMs);
                subsystemBuilder.append(" count=" + stateResidency.totalStateEntryCount);
                subsystemBuilder.append(" last entry=" + stateResidency.lastEntryTimestampMs);
            }

            if (subsystemBuilder.length() <= charsLeft) {
                charsLeft -= subsystemBuilder.length();
                builder.append(subsystemBuilder);
            } else {
                Slog.e(TAG, "getSubsystemLowPowerStats: buffer not enough");
                break;
            }
        }

        return builder.toString();
    }

    private ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(@NonNull Network network,
                @NonNull NetworkCapabilities networkCapabilities) {
            final String state = networkCapabilities.hasCapability(NET_CAPABILITY_NOT_SUSPENDED)
                    ? "CONNECTED" : "SUSPENDED";
            noteConnectivityChanged(NetworkCapabilitiesUtils.getDisplayTransport(
                    networkCapabilities.getTransportTypes()), state);
        }

        @Override
        public void onLost(Network network) {
            noteConnectivityChanged(-1, "DISCONNECTED");
        }
    };

    BatteryStatsService(Context context, File systemDir) {
        mContext = context;
        mUserManagerUserInfoProvider = new BatteryStatsImpl.UserInfoProvider() {
            private UserManagerInternal umi;
            @Override
            public int[] getUserIds() {
                if (umi == null) {
                    umi = LocalServices.getService(UserManagerInternal.class);
                }
                return (umi != null) ? umi.getUserIds() : null;
            }
        };
        mHandlerThread = new HandlerThread("batterystats-handler");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mMonotonicClock = new MonotonicClock(new File(systemDir, "monotonic_clock.xml"));
        mPowerProfile = new PowerProfile(context);
        mCpuScalingPolicies = new CpuScalingPolicyReader().read();

        final boolean resetOnUnplugHighBatteryLevel = context.getResources().getBoolean(
                com.android.internal.R.bool.config_batteryStatsResetOnUnplugHighBatteryLevel);
        final boolean resetOnUnplugAfterSignificantCharge = context.getResources().getBoolean(
                com.android.internal.R.bool.config_batteryStatsResetOnUnplugAfterSignificantCharge);
        final long powerStatsThrottlePeriodCpu = context.getResources().getInteger(
                com.android.internal.R.integer.config_defaultPowerStatsThrottlePeriodCpu);
        final long powerStatsThrottlePeriodMobileRadio = context.getResources().getInteger(
                com.android.internal.R.integer.config_defaultPowerStatsThrottlePeriodMobileRadio);
        final long powerStatsThrottlePeriodWifi = context.getResources().getInteger(
                com.android.internal.R.integer.config_defaultPowerStatsThrottlePeriodWifi);
        mBatteryStatsConfig =
                new BatteryStatsImpl.BatteryStatsConfig.Builder()
                        .setResetOnUnplugHighBatteryLevel(resetOnUnplugHighBatteryLevel)
                        .setResetOnUnplugAfterSignificantCharge(resetOnUnplugAfterSignificantCharge)
                        .setPowerStatsThrottlePeriodMillis(
                                BatteryConsumer.POWER_COMPONENT_CPU,
                                powerStatsThrottlePeriodCpu)
                        .setPowerStatsThrottlePeriodMillis(
                                BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                                powerStatsThrottlePeriodMobileRadio)
                        .setPowerStatsThrottlePeriodMillis(
                                BatteryConsumer.POWER_COMPONENT_WIFI,
                                powerStatsThrottlePeriodWifi)
                        .build();
        mPowerStatsUidResolver = new PowerStatsUidResolver();
        mStats = new BatteryStatsImpl(mBatteryStatsConfig, Clock.SYSTEM_CLOCK, mMonotonicClock,
                systemDir, mHandler, this, this, mUserManagerUserInfoProvider, mPowerProfile,
                mCpuScalingPolicies, mPowerStatsUidResolver);
        mWorker = new BatteryExternalStatsWorker(context, mStats);
        mStats.setExternalStatsSyncLocked(mWorker);
        mStats.setRadioScanningTimeoutLocked(mContext.getResources().getInteger(
                com.android.internal.R.integer.config_radioScanningTimeout) * 1000L);
        if (!Flags.disableSystemServicePowerAttr()) {
            mStats.startTrackingSystemServerCpuTime();
        }

        mAggregatedPowerStatsConfig = createAggregatedPowerStatsConfig();
        mPowerStatsStore = new PowerStatsStore(systemDir, mHandler, mAggregatedPowerStatsConfig);
        mPowerStatsScheduler = createPowerStatsScheduler(mContext);
        PowerStatsExporter powerStatsExporter =
                new PowerStatsExporter(mPowerStatsStore,
                        new PowerStatsAggregator(mAggregatedPowerStatsConfig, mStats.getHistory()));
        mBatteryUsageStatsProvider = new BatteryUsageStatsProvider(context,
                powerStatsExporter, mPowerProfile, mCpuScalingPolicies,
                mPowerStatsStore, Clock.SYSTEM_CLOCK);
        mStats.saveBatteryUsageStatsOnReset(mBatteryUsageStatsProvider, mPowerStatsStore);
        mDumpHelper = new BatteryStatsDumpHelperImpl(mBatteryUsageStatsProvider);
        mCpuWakeupStats = new CpuWakeupStats(context, R.xml.irq_device_map, mHandler);
        mConfigFile = new AtomicFile(new File(systemDir, "battery_usage_stats_config"));
    }

    private PowerStatsScheduler createPowerStatsScheduler(Context context) {
        final long aggregatedPowerStatsSpanDuration = context.getResources().getInteger(
                com.android.internal.R.integer.config_aggregatedPowerStatsSpanDuration);
        final long powerStatsAggregationPeriod = context.getResources().getInteger(
                com.android.internal.R.integer.config_powerStatsAggregationPeriod);
        PowerStatsScheduler.AlarmScheduler alarmScheduler =
                (triggerAtMillis, tag, onAlarmListener, aHandler) -> {
                    AlarmManager alarmManager = mContext.getSystemService(AlarmManager.class);
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME, triggerAtMillis, tag,
                            onAlarmListener, aHandler);
                };
        return new PowerStatsScheduler(mStats::schedulePowerStatsSampleCollection,
                new PowerStatsAggregator(mAggregatedPowerStatsConfig,
                        mStats.getHistory()), aggregatedPowerStatsSpanDuration,
                powerStatsAggregationPeriod, mPowerStatsStore, alarmScheduler, Clock.SYSTEM_CLOCK,
                mMonotonicClock, () -> mStats.getHistory().getStartTime(), mHandler);
    }

    private AggregatedPowerStatsConfig createAggregatedPowerStatsConfig() {
        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_CPU)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessor(
                        new CpuPowerStatsProcessor(mPowerProfile, mCpuScalingPolicies));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessor(
                        new MobileRadioPowerStatsProcessor(mPowerProfile));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_PHONE,
                        BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO)
                .setProcessor(new PhoneCallPowerStatsProcessor());

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_WIFI)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessor(
                        new WifiPowerStatsProcessor(mPowerProfile));
        return config;
    }

    /**
     * Creates an instance of BatteryStatsService and restores data from stored state.
     */
    public static BatteryStatsService create(Context context, File systemDir, Handler handler,
            BatteryStatsImpl.BatteryCallback callback) {
        BatteryStatsService service = new BatteryStatsService(context, systemDir);
        service.mStats.setCallback(callback);
        synchronized (service.mStats) {
            service.mStats.readLocked();
        }
        service.scheduleWriteToDisk();
        return service;
    }

    public void publish() {
        LocalServices.addService(BatteryStatsInternal.class, new LocalService());
        ServiceManager.addService(BatteryStats.SERVICE_NAME, asBinder());
    }

    public void systemServicesReady() {
        mStats.setPowerStatsCollectorEnabled(BatteryConsumer.POWER_COMPONENT_CPU,
                Flags.streamlinedBatteryStats());
        mBatteryUsageStatsProvider.setPowerStatsExporterEnabled(
                BatteryConsumer.POWER_COMPONENT_CPU,
                Flags.streamlinedBatteryStats());

        mStats.setPowerStatsCollectorEnabled(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                Flags.streamlinedConnectivityBatteryStats());
        mBatteryUsageStatsProvider.setPowerStatsExporterEnabled(
                BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO,
                Flags.streamlinedConnectivityBatteryStats());

        mStats.setPowerStatsCollectorEnabled(BatteryConsumer.POWER_COMPONENT_WIFI,
                Flags.streamlinedConnectivityBatteryStats());
        mBatteryUsageStatsProvider.setPowerStatsExporterEnabled(
                BatteryConsumer.POWER_COMPONENT_WIFI,
                Flags.streamlinedConnectivityBatteryStats());

        mWorker.systemServicesReady();
        mStats.systemServicesReady(mContext);
        mCpuWakeupStats.systemServicesReady();
        final INetworkManagementService nms = INetworkManagementService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE));
        final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        try {
            if (!SdkLevel.isAtLeastV()) {
                // On V+ devices, ConnectivityService calls BatteryStats API to update
                // RadioPowerState change. So BatteryStatsService registers the callback only on
                // pre V devices.
                nms.registerObserver(mActivityChangeObserver);
            }
            cm.registerDefaultNetworkCallback(mNetworkCallback);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not register INetworkManagement event observer " + e);
        }

        synchronized (mPowerStatsLock) {
            mPowerStatsInternal = LocalServices.getService(PowerStatsInternal.class);
            if (mPowerStatsInternal != null) {
                populatePowerEntityMaps();
            } else {
                Slog.e(TAG, "Could not register PowerStatsInternal");
            }
        }
        mBatteryManagerInternal = LocalServices.getService(BatteryManagerInternal.class);

        Watchdog.getInstance().addMonitor(this);

        final DataConnectionStats dataConnectionStats = new DataConnectionStats(mContext, mHandler);
        dataConnectionStats.startMonitoring();

        registerStatsCallbacks();
    }

    /**
     * Notifies BatteryStatsService that the system server is ready.
     */
    public void onSystemReady() {
        mStats.onSystemReady(mContext);
        mPowerStatsScheduler.start(Flags.streamlinedBatteryStats());
    }

    private final class LocalService extends BatteryStatsInternal {
        @Override
        public String[] getWifiIfaces() {
            return mStats.getWifiIfaces().clone();
        }

        @Override
        public String[] getMobileIfaces() {
            return mStats.getMobileIfaces().clone();
        }

        @Override
        public SystemServiceCpuThreadTimes getSystemServiceCpuThreadTimes() {
            return mStats.getSystemServiceCpuThreadTimes();
        }

        @Override
        public List<BatteryUsageStats> getBatteryUsageStats(List<BatteryUsageStatsQuery> queries) {
            return BatteryStatsService.this.getBatteryUsageStats(queries);
        }

        @Override
        public void noteJobsDeferred(int uid, int numDeferred, long sinceLast) {
            if (DBG) Slog.d(TAG, "Jobs deferred " + uid + ": " + numDeferred + " " + sinceLast);
            BatteryStatsService.this.noteJobsDeferred(uid, numDeferred, sinceLast);
        }

        private int transportToSubsystem(NetworkCapabilities nc) {
            if (nc.hasTransport(TRANSPORT_WIFI)) {
                return CPU_WAKEUP_SUBSYSTEM_WIFI;
            } else if (nc.hasTransport(TRANSPORT_CELLULAR)) {
                return CPU_WAKEUP_SUBSYSTEM_CELLULAR_DATA;
            }
            return CPU_WAKEUP_SUBSYSTEM_UNKNOWN;
        }

        @Override
        public void noteCpuWakingNetworkPacket(Network network, long elapsedMillis, int uid) {
            if (uid < 0) {
                Slog.e(TAG, "Invalid uid for waking network packet: " + uid);
                return;
            }
            final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
            final NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            final int subsystem = transportToSubsystem(nc);

            if (subsystem == CPU_WAKEUP_SUBSYSTEM_UNKNOWN) {
                Slog.wtf(TAG, "Could not map transport for network: " + network
                        + " while attributing wakeup by packet sent to uid: " + uid);
                return;
            }
            noteCpuWakingActivity(subsystem, elapsedMillis, uid);
        }

        @Override
        public void noteBinderCallStats(int workSourceUid, long incrementatCallCount,
                Collection<BinderCallsStats.CallStat> callStats) {
            synchronized (BatteryStatsService.this.mLock) {
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        mStats::noteBinderCallStats, workSourceUid, incrementatCallCount, callStats,
                        SystemClock.elapsedRealtime(), SystemClock.uptimeMillis()));
            }
        }

        @Override
        public void noteBinderThreadNativeIds(int[] binderThreadNativeTids) {
            synchronized (BatteryStatsService.this.mLock) {
                mStats.noteBinderThreadNativeIds(binderThreadNativeTids);
            }
        }

        @Override
        public void noteWakingSoundTrigger(long elapsedMillis, int uid) {
            noteCpuWakingActivity(CPU_WAKEUP_SUBSYSTEM_SOUND_TRIGGER, elapsedMillis, uid);
        }

        @Override
        public void noteWakingAlarmBatch(long elapsedMillis, int... uids) {
            noteCpuWakingActivity(CPU_WAKEUP_SUBSYSTEM_ALARM, elapsedMillis, uids);
        }
    }

    /**
     * Reports any activity that could potentially have caused the CPU to wake up.
     * Accepts a timestamp to allow free ordering between the event and its reporting.
     *
     * <p>
     * This method can be called multiple times for the same wakeup and then all attribution
     * reported will be unioned as long as all reports are made within a small amount of cpu uptime
     * after the wakeup is reported to batterystats.
     *
     * @param subsystem The subsystem this activity should be attributed to.
     * @param elapsedMillis The time when this activity happened in the elapsed timebase.
     * @param uids The uid (or uids) that should be blamed for this activity.
     */
    void noteCpuWakingActivity(@CpuWakeupSubsystem int subsystem, long elapsedMillis, int... uids) {
        Objects.requireNonNull(uids);
        mHandler.post(() -> mCpuWakeupStats.noteWakingActivity(subsystem, elapsedMillis, uids));
    }

    @Override
    public void monitor() {
        if (!mMonitorEnabled) {
            return;
        }
        synchronized (mLock) {
        }
        synchronized (mStats) {
        }
    }

    private static void awaitUninterruptibly(Future<?> future) {
        while (true) {
            try {
                future.get();
                return;
            } catch (ExecutionException | CancellationException e) {
                return;
            } catch (InterruptedException e) {
                // Keep looping
            }
        }
    }

    private void syncStats(String reason, int flags) {
        awaitUninterruptibly(mWorker.scheduleSync(reason, flags));
    }

    private void awaitCompletion() {
        final CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
        }
    }

    /**
     * At the time when the constructor runs, the power manager has not yet been
     * initialized.  So we initialize the low power observer later.
     */
    public void initPowerManagement() {
        final PowerManagerInternal powerMgr = LocalServices.getService(PowerManagerInternal.class);
        powerMgr.registerLowPowerModeObserver(this);
        synchronized (mStats) {
            mStats.notePowerSaveModeLockedInit(
                    powerMgr.getLowPowerState(ServiceType.BATTERY_STATS).batterySaverEnabled,
                    SystemClock.elapsedRealtime(), SystemClock.uptimeMillis());
        }
        (new WakeupReasonThread()).start();
    }

    public void shutdown() {
        Slog.w("BatteryStats", "Writing battery stats before shutdown...");

        // Drain the handler queue to make sure we've handled all pending works.
        awaitCompletion();

        syncStats("shutdown", BatteryExternalStatsWorker.UPDATE_ALL);

        synchronized (mStats) {
            mStats.shutdownLocked();
        }

        // Shutdown the thread we made.
        mWorker.shutdown();

        // To insure continuity, write the monotonic timeshift after writing the last history event
        mMonotonicClock.write();
    }

    public static IBatteryStats getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(BatteryStats.SERVICE_NAME);
        sService = asInterface(b);
        return sService;
    }

    @Override
    public int getServiceType() {
        return ServiceType.BATTERY_STATS;
    }

    @Override
    public void onLowPowerModeChanged(final PowerSaveState result) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePowerSaveModeLocked(result.batterySaverEnabled,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    /**
     * @return the current statistics object, which may be modified
     * to reflect events that affect battery usage.  You must lock the
     * stats object before doing anything with it.
     */
    public BatteryStatsImpl getActiveStatistics() {
        return mStats;
    }

    /**
     * Schedules a write to disk to occur. This will cause the BatteryStatsImpl
     * object to update with the latest info, then write to disk.
     */
    public void scheduleWriteToDisk() {
        synchronized (mLock) {
            // We still schedule it on the handler so we'll have all existing pending works done.
            mHandler.post(() -> {
                mWorker.scheduleWrite();
            });
        }
    }

    // These are for direct use by the activity manager...

    /**
     * Remove a UID from the BatteryStats and BatteryStats' external dependencies.
     */
    void removeUid(final int uid) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            mHandler.post(() -> {
                mCpuWakeupStats.onUidRemoved(uid);
                synchronized (mStats) {
                    mStats.removeUidStatsLocked(uid, elapsedRealtime);
                }
            });
        }
    }

    void onCleanupUser(final int userId) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.onCleanupUserLocked(userId, elapsedRealtime);
                }
            });
        }
    }

    void onUserRemoved(final int userId) {
        synchronized (mLock) {
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.onUserRemovedLocked(userId);
                }
            });
        }
    }

    void addIsolatedUid(final int isolatedUid, final int appUid) {
        mPowerStatsUidResolver.noteIsolatedUidAdded(isolatedUid, appUid);
        FrameworkStatsLog.write(FrameworkStatsLog.ISOLATED_UID_CHANGED, appUid, isolatedUid,
                FrameworkStatsLog.ISOLATED_UID_CHANGED__EVENT__CREATED);
    }

    void removeIsolatedUid(final int isolatedUid, final int appUid) {
        mPowerStatsUidResolver.noteIsolatedUidRemoved(isolatedUid, appUid);
        FrameworkStatsLog.write(FrameworkStatsLog.ISOLATED_UID_CHANGED, -1, isolatedUid,
                FrameworkStatsLog.ISOLATED_UID_CHANGED__EVENT__REMOVED);
    }

    void noteProcessStart(final String name, final int uid) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteProcessStartLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED, uid, name,
                FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED__STATE__STARTED);
    }

    void noteProcessCrash(String name, int uid) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteProcessCrashLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED, uid, name,
                FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED__STATE__CRASHED);
    }

    void noteProcessAnr(String name, int uid) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteProcessAnrLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    void noteProcessFinish(String name, int uid) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteProcessFinishLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED, uid, name,
                FrameworkStatsLog.PROCESS_LIFE_CYCLE_STATE_CHANGED__STATE__FINISHED);
    }

    /** @param state Process state from ActivityManager.java. */
    void noteUidProcessState(int uid, int state) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                mCpuWakeupStats.noteUidProcessState(uid, state);
                synchronized (mStats) {
                    mStats.noteUidProcessStateLocked(uid, state, elapsedRealtime, uptime);
                }
            });
        }
    }

    // Public interface...

    /**
     * Returns BatteryUsageStats, which contains power attribution data on a per-subsystem
     * and per-UID basis.
     */
    @Override
    @EnforcePermission(BATTERY_STATS)
    public List<BatteryUsageStats> getBatteryUsageStats(List<BatteryUsageStatsQuery> queries) {
        super.getBatteryUsageStats_enforcePermission();

        awaitCompletion();

        if (BatteryUsageStatsProvider.shouldUpdateStats(queries,
                SystemClock.elapsedRealtime(),
                mWorker.getLastCollectionTimeStamp())) {
            syncStats("get-stats", BatteryExternalStatsWorker.UPDATE_ALL);
            if (Flags.streamlinedBatteryStats()) {
                mStats.collectPowerStatsSamples();
            }
        }

        return mBatteryUsageStatsProvider.getBatteryUsageStats(mStats, queries);
    }

    /** Register callbacks for statsd pulled atoms. */
    private void registerStatsCallbacks() {
        final StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        final StatsPullAtomCallbackImpl pullAtomCallback = new StatsPullAtomCallbackImpl();

        statsManager.setPullAtomCallback(
                FrameworkStatsLog.BATTERY_USAGE_STATS_SINCE_RESET,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR, pullAtomCallback);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.BATTERY_USAGE_STATS_SINCE_RESET_USING_POWER_PROFILE_MODEL,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR, pullAtomCallback);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.BATTERY_USAGE_STATS_BEFORE_RESET,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR, pullAtomCallback);
    }

    /** StatsPullAtomCallback for pulling BatteryUsageStats data. */
    private class StatsPullAtomCallbackImpl implements StatsManager.StatsPullAtomCallback {
        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            final BatteryUsageStats bus;
            switch (atomTag) {
                case FrameworkStatsLog.BATTERY_USAGE_STATS_SINCE_RESET:
                    @SuppressLint("MissingPermission")
                    final double minConsumedPowerThreshold =
                            DeviceConfig.getFloat(DEVICE_CONFIG_NAMESPACE,
                                    MIN_CONSUMED_POWER_THRESHOLD_KEY, 0);
                    final BatteryUsageStatsQuery querySinceReset =
                            new BatteryUsageStatsQuery.Builder()
                                    .setMaxStatsAgeMs(0)
                                    .includeProcessStateData()
                                    .includeVirtualUids()
                                    .includePowerModels()
                                    .setMinConsumedPowerThreshold(minConsumedPowerThreshold)
                                    .build();
                    bus = getBatteryUsageStats(List.of(querySinceReset)).get(0);
                    break;
                case FrameworkStatsLog.BATTERY_USAGE_STATS_SINCE_RESET_USING_POWER_PROFILE_MODEL:
                    final BatteryUsageStatsQuery queryPowerProfile =
                            new BatteryUsageStatsQuery.Builder()
                                    .setMaxStatsAgeMs(0)
                                    .includeProcessStateData()
                                    .includeVirtualUids()
                                    .powerProfileModeledOnly()
                                    .includePowerModels()
                                    .build();
                    bus = getBatteryUsageStats(List.of(queryPowerProfile)).get(0);
                    break;
                case FrameworkStatsLog.BATTERY_USAGE_STATS_BEFORE_RESET:
                    final long sessionStart =
                            getLastBatteryUsageStatsBeforeResetAtomPullTimestamp();
                    final long sessionEnd;
                    synchronized (mStats) {
                        sessionEnd = mStats.getStartClockTime();
                    }
                    final BatteryUsageStatsQuery queryBeforeReset =
                            new BatteryUsageStatsQuery.Builder()
                                    .setMaxStatsAgeMs(0)
                                    .includeProcessStateData()
                                    .includeVirtualUids()
                                    .aggregateSnapshots(sessionStart, sessionEnd)
                                    .build();
                    bus = getBatteryUsageStats(List.of(queryBeforeReset)).get(0);
                    setLastBatteryUsageStatsBeforeResetAtomPullTimestamp(sessionEnd);
                    break;
                default:
                    throw new UnsupportedOperationException("Unknown tagId=" + atomTag);
            }
            final byte[] statsProto = bus.getStatsProto();

            data.add(FrameworkStatsLog.buildStatsEvent(atomTag, statsProto));

            return StatsManager.PULL_SUCCESS;
        }
    }

    @Override
    @RequiresNoPermission
    public boolean isCharging() {
        synchronized (mStats) {
            return mStats.isCharging();
        }
    }

    @Override
    @RequiresNoPermission
    public long computeBatteryTimeRemaining() {
        synchronized (mStats) {
            long time = mStats.computeBatteryTimeRemaining(SystemClock.elapsedRealtime());
            return time >= 0 ? (time/1000) : time;
        }
    }

    @Override
    @RequiresNoPermission
    public long computeChargeTimeRemaining() {
        synchronized (mStats) {
            long time = mStats.computeChargeTimeRemaining(SystemClock.elapsedRealtime());
            return time >= 0 ? (time/1000) : time;
        }
    }

    @Override
    @EnforcePermission(BATTERY_STATS)
    public long computeBatteryScreenOffRealtimeMs() {
        super.computeBatteryScreenOffRealtimeMs_enforcePermission();

        synchronized (mStats) {
            final long curTimeUs = SystemClock.elapsedRealtimeNanos() / 1000;
            long timeUs = mStats.computeBatteryScreenOffRealtime(curTimeUs,
                    BatteryStats.STATS_SINCE_CHARGED);
            return timeUs / 1000;
        }
    }

    @Override
    @EnforcePermission(BATTERY_STATS)
    public long getScreenOffDischargeMah() {
        super.getScreenOffDischargeMah_enforcePermission();

        synchronized (mStats) {
            long dischargeUah = mStats.getUahDischargeScreenOff(BatteryStats.STATS_SINCE_CHARGED);
            return dischargeUah / 1000;
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteEvent(final int code, final String name, final int uid) {
        super.noteEvent_enforcePermission();

        if (name == null) {
            // TODO(b/194733136): Replace with an IllegalArgumentException throw.
            Slog.wtfStack(TAG, "noteEvent called with null name. code = " + code);
            return;
        }

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteEventLocked(code, name, uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteSyncStart(final String name, final int uid) {
        super.noteSyncStart_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteSyncStartLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SYNC_STATE_CHANGED, uid, null,
                name, FrameworkStatsLog.SYNC_STATE_CHANGED__STATE__ON);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteSyncFinish(final String name, final int uid) {
        super.noteSyncFinish_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteSyncFinishLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SYNC_STATE_CHANGED, uid, null,
                name, FrameworkStatsLog.SYNC_STATE_CHANGED__STATE__OFF);
    }

    /** A scheduled job was started. */
    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteJobStart(final String name, final int uid) {
        super.noteJobStart_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteJobStartLocked(name, uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    /** A scheduled job was finished. */
    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteJobFinish(final String name, final int uid, final int stopReason) {
        super.noteJobFinish_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteJobFinishLocked(name, uid, stopReason, elapsedRealtime, uptime);
                }
            });
        }
    }

    void noteJobsDeferred(final int uid, final int numDeferred, final long sinceLast) {
        // No need to enforce calling permission, as it is called from an internal interface
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteJobsDeferredLocked(uid, numDeferred, sinceLast,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteWakupAlarm(final String name, final int uid, final WorkSource workSource,
            final String tag) {
        mContext.enforceCallingOrSelfPermission(UPDATE_DEVICE_STATS, "noteWakupAlarm");
        final WorkSource localWs = workSource != null ? new WorkSource(workSource) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWakupAlarmLocked(name, uid, localWs, tag,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteAlarmStart(final String name, final WorkSource workSource, final int uid) {
        mContext.enforceCallingOrSelfPermission(UPDATE_DEVICE_STATS, "noteAlarmStart");
        final WorkSource localWs = workSource != null ? new WorkSource(workSource) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteAlarmStartLocked(name, localWs, uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    public void noteAlarmFinish(final String name, final WorkSource workSource, final int uid) {
        mContext.enforceCallingOrSelfPermission(UPDATE_DEVICE_STATS, "noteAlarmFinish");
        final WorkSource localWs = workSource != null ? new WorkSource(workSource) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteAlarmFinishLocked(name, localWs, uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStartWakelock(final int uid, final int pid, final String name,
            final String historyName, final int type, final boolean unimportantForLogging) {
        super.noteStartWakelock_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStartWakeLocked(uid, pid, null, name, historyName, type,
                            unimportantForLogging, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStopWakelock(final int uid, final int pid, final String name,
            final String historyName, final int type) {
        super.noteStopWakelock_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStopWakeLocked(uid, pid, null, name, historyName, type,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStartWakelockFromSource(final WorkSource ws, final int pid, final String name,
            final String historyName, final int type, final boolean unimportantForLogging) {
        super.noteStartWakelockFromSource_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStartWakeFromSourceLocked(localWs, pid, name, historyName,
                            type, unimportantForLogging, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteChangeWakelockFromSource(final WorkSource ws, final int pid, final String name,
            final String historyName, final int type, final WorkSource newWs, final int newPid,
            final String newName, final String newHistoryName, final int newType,
            final boolean newUnimportantForLogging) {
        super.noteChangeWakelockFromSource_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        final WorkSource localNewWs = newWs != null ? new WorkSource(newWs) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteChangeWakelockFromSourceLocked(localWs, pid, name, historyName, type,
                            localNewWs, newPid, newName, newHistoryName, newType,
                            newUnimportantForLogging, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStopWakelockFromSource(final WorkSource ws, final int pid, final String name,
            final String historyName, final int type) {
        super.noteStopWakelockFromSource_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStopWakeFromSourceLocked(localWs, pid, name, historyName, type,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteLongPartialWakelockStart(final String name, final String historyName,
            final int uid) {
        super.noteLongPartialWakelockStart_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteLongPartialWakelockStart(name, historyName, uid,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteLongPartialWakelockStartFromSource(final String name, final String historyName,
            final WorkSource workSource) {
        super.noteLongPartialWakelockStartFromSource_enforcePermission();

        final WorkSource localWs = workSource != null ? new WorkSource(workSource) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteLongPartialWakelockStartFromSource(name, historyName, localWs,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteLongPartialWakelockFinish(final String name, final String historyName,
            final int uid) {
        super.noteLongPartialWakelockFinish_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteLongPartialWakelockFinish(name, historyName, uid,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteLongPartialWakelockFinishFromSource(final String name, final String historyName,
            final WorkSource workSource) {
        super.noteLongPartialWakelockFinishFromSource_enforcePermission();

        final WorkSource localWs = workSource != null ? new WorkSource(workSource) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteLongPartialWakelockFinishFromSource(name, historyName, localWs,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStartSensor(final int uid, final int sensor) {
        super.noteStartSensor_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStartSensorLocked(uid, sensor, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SENSOR_STATE_CHANGED, uid,
                null, sensor, FrameworkStatsLog.SENSOR_STATE_CHANGED__STATE__ON);
    }

    @Override
    public void noteWakeupSensorEvent(long elapsedNanos, int uid, int sensorHandle) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID) {
            throw new SecurityException("Calling uid " + callingUid + " is not system uid");
        }
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

        final SensorManager sm = mContext.getSystemService(SensorManager.class);
        final Sensor sensor = sm.getSensorByHandle(sensorHandle);
        if (sensor == null) {
            Slog.w(TAG, "Unknown sensor handle " + sensorHandle
                    + " received in noteWakeupSensorEvent");
            return;
        }
        if (uid < 0) {
            Slog.wtf(TAG, "Invalid uid " + uid + " for sensor event with sensor: " + sensor);
            return;
        }
        // TODO (b/278319756): Also pipe in Sensor type for more usefulness.
        noteCpuWakingActivity(BatteryStatsInternal.CPU_WAKEUP_SUBSYSTEM_SENSOR, elapsedMillis, uid);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStopSensor(final int uid, final int sensor) {
        super.noteStopSensor_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteStopSensorLocked(uid, sensor, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.SENSOR_STATE_CHANGED, uid,
                null, sensor, FrameworkStatsLog.SENSOR_STATE_CHANGED__STATE__OFF);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteVibratorOn(final int uid, final long durationMillis) {
        super.noteVibratorOn_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteVibratorOnLocked(uid, durationMillis, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteVibratorOff(final int uid) {
        super.noteVibratorOff_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteVibratorOffLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteGpsChanged(final WorkSource oldWs, final WorkSource newWs) {
        super.noteGpsChanged_enforcePermission();

        final WorkSource localOldWs = oldWs != null ? new WorkSource(oldWs) : null;
        final WorkSource localNewWs = newWs != null ? new WorkSource(newWs) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteGpsChangedLocked(localOldWs, localNewWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteGpsSignalQuality(final int signalLevel) {
        super.noteGpsSignalQuality_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteGpsSignalQualityLocked(signalLevel, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteScreenState(final int state) {
        super.noteScreenState_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            final long currentTime = System.currentTimeMillis();
            mHandler.post(() -> {
                if (DBG) Slog.d(TAG, "begin noteScreenState");
                synchronized (mStats) {
                    mStats.noteScreenStateLocked(0, state, elapsedRealtime, uptime, currentTime);
                }
                if (DBG) Slog.d(TAG, "end noteScreenState");
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.SCREEN_STATE_CHANGED, state);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteScreenBrightness(final int brightness) {
        super.noteScreenBrightness_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteScreenBrightnessLocked(0, brightness, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.SCREEN_BRIGHTNESS_CHANGED, brightness);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteUserActivity(final int uid, final int event) {
        super.noteUserActivity_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteUserActivityLocked(uid, event, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWakeUp(final String reason, final int reasonUid) {
        super.noteWakeUp_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWakeUpLocked(reason, reasonUid, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteInteractive(final boolean interactive) {
        super.noteInteractive_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteInteractiveLocked(interactive, elapsedRealtime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteConnectivityChanged(final int type, final String extra) {
        super.noteConnectivityChanged_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteConnectivityChangedLocked(type, extra, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteMobileRadioPowerState(final int powerState, final long timestampNs,
            final int uid) {
        super.noteMobileRadioPowerState_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    // Ignore if no power state change.
                    if (mLastPowerStateFromRadio == powerState) return;

                    mLastPowerStateFromRadio = powerState;
                    mStats.noteMobileRadioPowerStateLocked(powerState, timestampNs, uid,
                            elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(
                FrameworkStatsLog.MOBILE_RADIO_POWER_STATE_CHANGED, uid, null, powerState);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void notePhoneOn() {
        super.notePhoneOn_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePhoneOnLocked(elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void notePhoneOff() {
        super.notePhoneOff_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePhoneOffLocked(elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void notePhoneSignalStrength(final SignalStrength signalStrength) {
        super.notePhoneSignalStrength_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePhoneSignalStrengthLocked(signalStrength, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void notePhoneDataConnectionState(final int dataType, final boolean hasData,
            final int serviceType, @NetworkRegistrationInfo.NRState final int nrState,
            final int nrFrequency) {
        super.notePhoneDataConnectionState_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePhoneDataConnectionStateLocked(dataType, hasData, serviceType,
                            nrState, nrFrequency, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void notePhoneState(final int state) {
        super.notePhoneState_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                int simState = mContext.getSystemService(TelephonyManager.class).getSimState();
                synchronized (mStats) {
                    mStats.notePhoneStateLocked(state, simState, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiOn() {
        super.noteWifiOn_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiOnLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED,
                FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED__STATE__ON);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiOff() {
        super.noteWifiOff_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiOffLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED,
                FrameworkStatsLog.WIFI_ENABLED_STATE_CHANGED__STATE__OFF);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStartAudio(final int uid) {
        super.noteStartAudio_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteAudioOnLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.AUDIO_STATE_CHANGED, uid,
                null, FrameworkStatsLog.AUDIO_STATE_CHANGED__STATE__ON);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStopAudio(final int uid) {
        super.noteStopAudio_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteAudioOffLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.AUDIO_STATE_CHANGED, uid,
                null, FrameworkStatsLog.AUDIO_STATE_CHANGED__STATE__OFF);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStartVideo(final int uid) {
        super.noteStartVideo_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteVideoOnLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED,
                uid, null, FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__ON);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStopVideo(final int uid) {
        super.noteStopVideo_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteVideoOffLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED,
                uid, null, FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__OFF);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteResetAudio() {
        super.noteResetAudio_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteResetAudioLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.AUDIO_STATE_CHANGED, -1, null,
                FrameworkStatsLog.AUDIO_STATE_CHANGED__STATE__RESET);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteResetVideo() {
        super.noteResetVideo_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteResetVideoLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED, -1,
                null, FrameworkStatsLog.MEDIA_CODEC_STATE_CHANGED__STATE__RESET);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteFlashlightOn(final int uid) {
        super.noteFlashlightOn_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFlashlightOnLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED, uid,
                null, FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED__STATE__ON);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteFlashlightOff(final int uid) {
        super.noteFlashlightOff_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFlashlightOffLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED, uid,
                null, FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED__STATE__OFF);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStartCamera(final int uid) {
        super.noteStartCamera_enforcePermission();

        if (DBG) Slog.d(TAG, "begin noteStartCamera");
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteCameraOnLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        if (DBG) Slog.d(TAG, "end noteStartCamera");
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.CAMERA_STATE_CHANGED, uid,
                null, FrameworkStatsLog.CAMERA_STATE_CHANGED__STATE__ON);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteStopCamera(final int uid) {
        super.noteStopCamera_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteCameraOffLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.CAMERA_STATE_CHANGED, uid,
                null, FrameworkStatsLog.CAMERA_STATE_CHANGED__STATE__OFF);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteResetCamera() {
        super.noteResetCamera_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteResetCameraLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.CAMERA_STATE_CHANGED, -1,
                null, FrameworkStatsLog.CAMERA_STATE_CHANGED__STATE__RESET);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteResetFlashlight() {
        super.noteResetFlashlight_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteResetFlashlightLocked(elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED, -1,
                null, FrameworkStatsLog.FLASHLIGHT_STATE_CHANGED__STATE__RESET);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiRadioPowerState(final int powerState, final long tsNanos, final int uid) {
        super.noteWifiRadioPowerState_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                // There was a change in WiFi power state.
                // Collect data now for the past activity.
                synchronized (mStats) {
                    // Ignore if no power state change.
                    if (mLastPowerStateFromWifi == powerState) return;

                    mLastPowerStateFromWifi = powerState;
                    if (mStats.isOnBattery()) {
                        final String type =
                                (powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_HIGH
                                || powerState == DataConnectionRealTimeInfo.DC_POWER_STATE_MEDIUM)
                                ? "active" : "inactive";
                        mWorker.scheduleSync("wifi-data: " + type,
                                BatteryExternalStatsWorker.UPDATE_WIFI);
                    }
                    mStats.noteWifiRadioPowerState(powerState, tsNanos, uid,
                            elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write_non_chained(
                FrameworkStatsLog.WIFI_RADIO_POWER_STATE_CHANGED, uid, null, powerState);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiRunning(final WorkSource ws) {
        super.noteWifiRunning_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiRunningLocked(localWs, elapsedRealtime, uptime);
                }
            });
        }
        // TODO: Log WIFI_RUNNING_STATE_CHANGED in a better spot to include Hotspot too.
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                ws, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__ON);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiRunningChanged(final WorkSource oldWs, final WorkSource newWs) {
        super.noteWifiRunningChanged_enforcePermission();

        final WorkSource localOldWs = oldWs != null ? new WorkSource(oldWs) : null;
        final WorkSource localNewWs = newWs != null ? new WorkSource(newWs) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiRunningChangedLocked(
                            localOldWs, localNewWs, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                newWs, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__ON);
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                oldWs, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__OFF);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiStopped(final WorkSource ws) {
        super.noteWifiStopped_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : ws;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiStoppedLocked(localWs, elapsedRealtime, uptime);
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED,
                ws, FrameworkStatsLog.WIFI_RUNNING_STATE_CHANGED__STATE__OFF);
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiState(final int wifiState, final String accessPoint) {
        super.noteWifiState_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiStateLocked(wifiState, accessPoint, elapsedRealtime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiSupplicantStateChanged(final int supplState, final boolean failedAuth) {
        super.noteWifiSupplicantStateChanged_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiSupplicantStateChangedLocked(supplState, failedAuth,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiRssiChanged(final int newRssi) {
        super.noteWifiRssiChanged_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiRssiChangedLocked(newRssi, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteFullWifiLockAcquired(final int uid) {
        super.noteFullWifiLockAcquired_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFullWifiLockAcquiredLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteFullWifiLockReleased(final int uid) {
        super.noteFullWifiLockReleased_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFullWifiLockReleasedLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiScanStarted(final int uid) {
        super.noteWifiScanStarted_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiScanStartedLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiScanStopped(final int uid) {
        super.noteWifiScanStopped_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiScanStoppedLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiMulticastEnabled(final int uid) {
        super.noteWifiMulticastEnabled_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiMulticastEnabledLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiMulticastDisabled(final int uid) {
        super.noteWifiMulticastDisabled_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiMulticastDisabledLocked(uid, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteFullWifiLockAcquiredFromSource(final WorkSource ws) {
        super.noteFullWifiLockAcquiredFromSource_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFullWifiLockAcquiredFromSourceLocked(
                            localWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteFullWifiLockReleasedFromSource(final WorkSource ws) {
        super.noteFullWifiLockReleasedFromSource_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteFullWifiLockReleasedFromSourceLocked(
                            localWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiScanStartedFromSource(final WorkSource ws) {
        super.noteWifiScanStartedFromSource_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiScanStartedFromSourceLocked(localWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiScanStoppedFromSource(final WorkSource ws) {
        super.noteWifiScanStoppedFromSource_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiScanStoppedFromSourceLocked(localWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiBatchedScanStartedFromSource(final WorkSource ws, final int csph) {
        super.noteWifiBatchedScanStartedFromSource_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiBatchedScanStartedFromSourceLocked(localWs, csph,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiBatchedScanStoppedFromSource(final WorkSource ws) {
        super.noteWifiBatchedScanStoppedFromSource_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteWifiBatchedScanStoppedFromSourceLocked(
                            localWs, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(anyOf = {NETWORK_STACK, PERMISSION_MAINLINE_NETWORK_STACK})
    public void noteNetworkInterfaceForTransports(final String iface, int[] transportTypes) {
        super.noteNetworkInterfaceForTransports_enforcePermission();

        synchronized (mLock) {
            mHandler.post(() -> {
                mStats.noteNetworkInterfaceForTransports(iface, transportTypes);
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteNetworkStatsEnabled() {
        // During device boot, qtaguid isn't enabled until after the inital
        // loading of battery stats. Now that they're enabled, take our initial
        // snapshot for future delta calculation.
        super.noteNetworkStatsEnabled_enforcePermission();

        synchronized (mLock) {
            // Still schedule it on the handler to make sure we have existing pending works done
            mHandler.post(() -> {
                mWorker.scheduleSync("network-stats-enabled",
                        BatteryExternalStatsWorker.UPDATE_RADIO
                        | BatteryExternalStatsWorker.UPDATE_WIFI);
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteDeviceIdleMode(final int mode, final String activeReason, final int activeUid) {
        super.noteDeviceIdleMode_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteDeviceIdleModeLocked(mode, activeReason, activeUid,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void notePackageInstalled(final String pkgName, final long versionCode) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePackageInstalledLocked(pkgName, versionCode,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    public void notePackageUninstalled(final String pkgName) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.notePackageUninstalledLocked(pkgName, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteBleScanStarted(final WorkSource ws, final boolean isUnoptimized) {
        super.noteBleScanStarted_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteBluetoothScanStartedFromSourceLocked(localWs, isUnoptimized,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteBleScanStopped(final WorkSource ws, final boolean isUnoptimized) {
        super.noteBleScanStopped_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteBluetoothScanStoppedFromSourceLocked(localWs, isUnoptimized,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteBleScanReset() {
        super.noteBleScanReset_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteResetBluetoothScanLocked(elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteBleScanResults(final WorkSource ws, final int numNewResults) {
        super.noteBleScanResults_enforcePermission();

        final WorkSource localWs = ws != null ? new WorkSource(ws) : null;
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteBluetoothScanResultsFromSourceLocked(localWs, numNewResults,
                            elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteWifiControllerActivity(final WifiActivityEnergyInfo info) {
        super.noteWifiControllerActivity_enforcePermission();

        if (info == null || !info.isValid()) {
            Slog.e(TAG, "invalid wifi data given: " + info);
            return;
        }

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            final NetworkStatsManager networkStatsManager = mContext.getSystemService(
                    NetworkStatsManager.class);
            mHandler.post(() -> {
                mStats.updateWifiState(info, POWER_DATA_UNAVAILABLE, elapsedRealtime, uptime,
                        networkStatsManager);
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteBluetoothControllerActivity(final BluetoothActivityEnergyInfo info) {
        super.noteBluetoothControllerActivity_enforcePermission();

        if (info == null || !info.isValid()) {
            Slog.e(TAG, "invalid bluetooth data given: " + info);
            return;
        }

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.updateBluetoothStateLocked(
                            info, POWER_DATA_UNAVAILABLE, elapsedRealtime, uptime);
                }
            });
        }
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void noteModemControllerActivity(final ModemActivityInfo info) {
        super.noteModemControllerActivity_enforcePermission();

        if (info == null) {
            Slog.e(TAG, "invalid modem data given: " + info);
            return;
        }

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            final NetworkStatsManager networkStatsManager = mContext.getSystemService(
                    NetworkStatsManager.class);
            mHandler.post(() -> {
                mStats.noteModemControllerActivity(info, POWER_DATA_UNAVAILABLE, elapsedRealtime,
                        uptime, networkStatsManager);
            });
        }
    }

    public boolean isOnBattery() {
        return mStats.isOnBattery();
    }

    @Override
    @EnforcePermission(UPDATE_DEVICE_STATS)
    public void setBatteryState(final int status, final int health, final int plugType,
            final int level, final int temp, final int volt, final int chargeUAh,
            final int chargeFullUAh, final long chargeTimeToFullSeconds) {
        super.setBatteryState_enforcePermission();

        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            final long currentTime = System.currentTimeMillis();
            // We still schedule this task over the handler thread to make sure we've had
            // all existing pending work handled before setting the battery state
            mHandler.post(() -> {
                // BatteryService calls us here and we may update external state. It would be wrong
                // to block such a low level service like BatteryService on external stats like WiFi
                mWorker.scheduleRunnable(() -> {
                    synchronized (mStats) {
                        final boolean onBattery = BatteryStatsImpl.isOnBattery(plugType, status);
                        if (mStats.isOnBattery() == onBattery) {
                            // The battery state has not changed, so we don't need to sync external
                            // stats immediately.
                            mStats.setBatteryStateLocked(status, health, plugType, level, temp,
                                    volt, chargeUAh, chargeFullUAh, chargeTimeToFullSeconds,
                                    elapsedRealtime, uptime, currentTime);
                            return;
                        }
                    }

                    // Sync external stats first as the battery has changed states. If we don't sync
                    // before changing the state, we may not collect the relevant data later.
                    // Order here is guaranteed since we're scheduling from the same thread and we
                    // are using a single threaded executor.
                    mWorker.scheduleSync("battery-state", BatteryExternalStatsWorker.UPDATE_ALL);
                    mWorker.scheduleRunnable(() -> {
                        synchronized (mStats) {
                            mStats.setBatteryStateLocked(status, health, plugType, level, temp,
                                    volt, chargeUAh, chargeFullUAh, chargeTimeToFullSeconds,
                                    elapsedRealtime, uptime, currentTime);
                        }
                    });
                });
            });
        }
    }

    @Override
    @EnforcePermission(BATTERY_STATS)
    public long getAwakeTimeBattery() {
        super.getAwakeTimeBattery_enforcePermission();

        return mStats.getAwakeTimeBattery();
    }

    @Override
    @EnforcePermission(BATTERY_STATS)
    public long getAwakeTimePlugged() {
        super.getAwakeTimePlugged_enforcePermission();

        return mStats.getAwakeTimePlugged();
    }

    final class WakeupReasonThread extends Thread {
        private static final int MAX_REASON_SIZE = 512;
        private CharsetDecoder mDecoder;
        private ByteBuffer mUtf8Buffer;
        private CharBuffer mUtf16Buffer;

        WakeupReasonThread() {
            super("BatteryStats_wakeupReason");
        }

        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

            mDecoder = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .replaceWith("?");

            mUtf8Buffer = ByteBuffer.allocateDirect(MAX_REASON_SIZE);
            mUtf16Buffer = CharBuffer.allocate(MAX_REASON_SIZE);

            try {
                String reason;
                while ((reason = waitWakeup()) != null) {
                    final long nowElapsed = SystemClock.elapsedRealtime();
                    final long nowUptime = SystemClock.uptimeMillis();

                    Trace.instantForTrack(Trace.TRACE_TAG_POWER, TRACE_TRACK_WAKEUP_REASON,
                            nowElapsed + " " + reason);

                    // Wait for the completion of pending works if there is any
                    awaitCompletion();
                    mCpuWakeupStats.noteWakeupTimeAndReason(nowElapsed, nowUptime, reason);
                    synchronized (mStats) {
                        mStats.noteWakeupReasonLocked(reason, nowElapsed, nowUptime);
                    }
                }
            } catch (RuntimeException e) {
                Slog.e(TAG, "Failure reading wakeup reasons", e);
            }
        }

        private String waitWakeup() {
            mUtf8Buffer.clear();
            mUtf16Buffer.clear();
            mDecoder.reset();

            int bytesWritten = nativeWaitWakeup(mUtf8Buffer);
            if (bytesWritten < 0) {
                return null;
            } else if (bytesWritten == 0) {
                return "unknown";
            }

            // Set the buffer's limit to the number of bytes written.
            mUtf8Buffer.limit(bytesWritten);

            // Decode the buffer from UTF-8 to UTF-16.
            // Unmappable characters will be replaced.
            mDecoder.decode(mUtf8Buffer, mUtf16Buffer, true);
            mUtf16Buffer.flip();

            // Create a String from the UTF-16 buffer.
            return mUtf16Buffer.toString();
        }
    }

    private static native int nativeWaitWakeup(ByteBuffer outBuffer);

    private void dumpHelp(PrintWriter pw) {
        pw.println("Battery stats (batterystats) dump options:");
        pw.println("  [--checkin] [--proto] [--history] [--history-start] [--charged] [-c]");
        pw.println("  [--daily] [--reset] [--reset-all] [--write] [--new-daily] [--read-daily]");
        pw.println("  [-h] [<package.name>]");
        pw.println("  --checkin: generate output for a checkin report; will write (and clear) the");
        pw.println("             last old completed stats when they had been reset.");
        pw.println("  -c: write the current stats in checkin format.");
        pw.println("  --proto: write the current aggregate stats (without history) in proto format.");
        pw.println("  --history: show only history data.");
        pw.println("  --history-start <num>: show only history data starting at given time offset.");
        pw.println("  --history-create-events <num>: create <num> of battery history events.");
        pw.println("  --charged: only output data since last charged.");
        pw.println("  --daily: only output full daily data.");
        pw.println("  --reset: reset the stats, clearing all current data.");
        pw.println("  --reset-all: reset the stats, clearing all current and past data.");
        pw.println("  --write: force write current collected stats to disk.");
        pw.println("  --new-daily: immediately create and write new daily stats record.");
        pw.println("  --read-daily: read-load last written daily stats.");
        pw.println("  --settings: dump the settings key/values related to batterystats");
        pw.println("  --cpu: dump cpu stats for debugging purpose");
        pw.println("  --wakeups: dump CPU wakeup history and attribution.");
        pw.println("  --power-profile: dump the power profile constants");
        pw.println("  --usage: write battery usage stats. Optional arguments:");
        pw.println("     --proto: output as a binary protobuffer");
        pw.println("     --model power-profile: use the power profile model"
                + " even if measured energy is available");
        if (Flags.streamlinedBatteryStats()) {
            pw.println("  --sample: collect and dump a sample of stats for debugging purpose");
        }
        pw.println("  <package.name>: optional name of package to filter output by.");
        pw.println("  -h: print this help text.");
        pw.println("Battery stats (batterystats) commands:");
        pw.println("  enable|disable <option>");
        pw.println("    Enable or disable a running option.  Option state is not saved across boots.");
        pw.println("    Options are:");
        pw.println("      full-history: include additional detailed events in battery history:");
        pw.println("          wake_lock_in, alarms and proc events");
        pw.println("      no-auto-reset: don't automatically reset stats when unplugged");
        pw.println("      pretend-screen-off: pretend the screen is off, even if screen state changes");
    }

    private void dumpSettings(PrintWriter pw) {
        // Wait for the completion of pending works if there is any
        awaitCompletion();
        synchronized (mStats) {
            mStats.dumpConstantsLocked(pw);

            pw.println("Flags:");
            pw.println("    " + Flags.FLAG_STREAMLINED_BATTERY_STATS
                    + ": " + Flags.streamlinedBatteryStats());
        }
    }

    private void dumpCpuStats(PrintWriter pw) {
        // Wait for the completion of pending works if there is any
        awaitCompletion();
        synchronized (mStats) {
            mStats.dumpCpuStatsLocked(pw);
        }
    }

    private void dumpStatsSample(PrintWriter pw) {
        mStats.dumpStatsSample(pw);
    }

    private void dumpAggregatedStats(PrintWriter pw) {
        mPowerStatsScheduler.aggregateAndDumpPowerStats(pw);
    }

    private void dumpPowerStatsStore(PrintWriter pw) {
        mPowerStatsStore.dump(new IndentingPrintWriter(pw, "  "));
    }

    private void dumpPowerStatsStoreTableOfContents(PrintWriter pw) {
        mPowerStatsStore.dumpTableOfContents(new IndentingPrintWriter(pw, "  "));
    }

    private void dumpMeasuredEnergyStats(PrintWriter pw) {
        // Wait for the completion of pending works if there is any
        awaitCompletion();
        syncStats("dump", BatteryExternalStatsWorker.UPDATE_ALL);
        synchronized (mStats) {
            mStats.dumpEnergyConsumerStatsLocked(pw);
        }
    }

    private void dumpPowerProfile(PrintWriter pw) {
        synchronized (mStats) {
            mStats.dumpPowerProfileLocked(pw);
        }
    }

    private void dumpUsageStats(FileDescriptor fd, PrintWriter pw, int model,
            boolean proto) {
        awaitCompletion();
        syncStats("dump", BatteryExternalStatsWorker.UPDATE_ALL);

        BatteryUsageStatsQuery.Builder builder = new BatteryUsageStatsQuery.Builder()
                .setMaxStatsAgeMs(0)
                .includeProcessStateData()
                .includePowerModels();
        if (model == BatteryConsumer.POWER_MODEL_POWER_PROFILE) {
            builder.powerProfileModeledOnly();
        }
        BatteryUsageStatsQuery query = builder.build();
        synchronized (mStats) {
            mStats.prepareForDumpLocked();
        }
        if (Flags.streamlinedBatteryStats()) {
            // Important: perform this operation outside the mStats lock, because it will
            // need to access BatteryStats from a handler thread
            mStats.collectPowerStatsSamples();
        }

        BatteryUsageStats batteryUsageStats =
                mBatteryUsageStatsProvider.getBatteryUsageStats(mStats, query);
        if (proto) {
            batteryUsageStats.dumpToProto(fd);
        } else {
            batteryUsageStats.dump(pw, "");
        }
    }

    private int doEnableOrDisable(PrintWriter pw, int i, String[] args, boolean enable) {
        i++;
        if (i >= args.length) {
            pw.println("Missing option argument for " + (enable ? "--enable" : "--disable"));
            dumpHelp(pw);
            return -1;
        }
        if ("full-wake-history".equals(args[i]) || "full-history".equals(args[i])) {
            // Wait for the completion of pending works if there is any
            awaitCompletion();
            synchronized (mStats) {
                mStats.setRecordAllHistoryLocked(enable);
            }
        } else if ("no-auto-reset".equals(args[i])) {
            // Wait for the completion of pending works if there is any
            awaitCompletion();
            synchronized (mStats) {
                mStats.setNoAutoReset(enable);
            }
        } else if ("pretend-screen-off".equals(args[i])) {
            // Wait for the completion of pending works if there is any
            awaitCompletion();
            synchronized (mStats) {
                mStats.setPretendScreenOff(enable);
            }
        } else {
            pw.println("Unknown enable/disable option: " + args[i]);
            dumpHelp(pw);
            return -1;
        }
        return i;
    }


    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // If the monitor() method is already holding a lock on mStats, no harm done: we will
        // just wait for mStats in the dumpUnmonitored method below.  In fact, we would want
        // Watchdog to catch the service in the act in that situation.  We just don't want the
        // dump method itself to be blamed for holding the lock for too long.
        mMonitorEnabled = false;
        try {
            dumpUnmonitored(fd, pw, args);
        } finally {
            mMonitorEnabled = true;
        }
    }

    private void dumpUnmonitored(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;

        int flags = 0;
        boolean useCheckinFormat = false;
        boolean toProto = false;
        boolean isRealCheckin = false;
        boolean noOutput = false;
        boolean writeData = false;
        long historyStart = -1;
        int reqUid = -1;
        if (args != null) {
            for (int i=0; i<args.length; i++) {
                String arg = args[i];
                if ("--checkin".equals(arg)) {
                    useCheckinFormat = true;
                    isRealCheckin = true;
                } else if ("--history".equals(arg)) {
                    flags |= BatteryStats.DUMP_HISTORY_ONLY;
                } else if ("--history-start".equals(arg)) {
                    flags |= BatteryStats.DUMP_HISTORY_ONLY;
                    i++;
                    if (i >= args.length) {
                        pw.println("Missing time argument for --history-since");
                        dumpHelp(pw);
                        return;
                    }
                    historyStart = ParseUtils.parseLong(args[i], 0);
                    writeData = true;
                } else if ("--history-create-events".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("Missing events argument for --history-create-events");
                        dumpHelp(pw);
                        return;
                    }
                    final long events = ParseUtils.parseLong(args[i], 0);
                    awaitCompletion();
                    synchronized (mStats) {
                        mStats.createFakeHistoryEvents(events);
                        pw.println("Battery history create events started.");
                        noOutput = true;
                    }
                } else if ("-c".equals(arg)) {
                    useCheckinFormat = true;
                    flags |= BatteryStats.DUMP_INCLUDE_HISTORY;
                } else if ("--proto".equals(arg)) {
                    toProto = true;
                } else if ("--charged".equals(arg)) {
                    flags |= BatteryStats.DUMP_CHARGED_ONLY;
                } else if ("--daily".equals(arg)) {
                    flags |= BatteryStats.DUMP_DAILY_ONLY;
                } else if ("--reset-all".equals(arg)) {
                    awaitCompletion();
                    synchronized (mStats) {
                        mStats.resetAllStatsAndHistoryLocked(
                                BatteryStatsImpl.RESET_REASON_ADB_COMMAND);
                        mPowerStatsStore.reset();
                        pw.println("Battery stats and history reset.");
                        noOutput = true;
                    }
                } else if ("--reset".equals(arg)) {
                    awaitCompletion();
                    synchronized (mStats) {
                        mStats.resetAllStatsAndHistoryLocked(
                                BatteryStatsImpl.RESET_REASON_ADB_COMMAND);
                        pw.println("Battery stats reset.");
                        noOutput = true;
                    }
                } else if ("--write".equals(arg)) {
                    awaitCompletion();
                    syncStats("dump", BatteryExternalStatsWorker.UPDATE_ALL);
                    synchronized (mStats) {
                        mStats.writeSyncLocked();
                        pw.println("Battery stats written.");
                        noOutput = true;
                    }
                } else if ("--new-daily".equals(arg)) {
                    awaitCompletion();
                    synchronized (mStats) {
                        mStats.recordDailyStatsLocked();
                        pw.println("New daily stats written.");
                        noOutput = true;
                    }
                } else if ("--read-daily".equals(arg)) {
                    awaitCompletion();
                    synchronized (mStats) {
                        mStats.readDailyStatsLocked();
                        pw.println("Last daily stats read.");
                        noOutput = true;
                    }
                } else if ("--enable".equals(arg) || "enable".equals(arg)) {
                    i = doEnableOrDisable(pw, i, args, true);
                    if (i < 0) {
                        return;
                    }
                    pw.println("Enabled: " + args[i]);
                    return;
                } else if ("--disable".equals(arg) || "disable".equals(arg)) {
                    i = doEnableOrDisable(pw, i, args, false);
                    if (i < 0) {
                        return;
                    }
                    pw.println("Disabled: " + args[i]);
                    return;
                } else if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("--settings".equals(arg)) {
                    dumpSettings(pw);
                    return;
                } else if ("--cpu".equals(arg)) {
                    dumpCpuStats(pw);
                    return;
                } else  if ("--measured-energy".equals(arg)) {
                    dumpMeasuredEnergyStats(pw);
                    return;
                } else if ("--power-profile".equals(arg)) {
                    dumpPowerProfile(pw);
                    return;
                } else if ("--usage".equals(arg)) {
                    int model = BatteryConsumer.POWER_MODEL_UNDEFINED;
                    boolean proto = false;
                    for (int j = i + 1; j < args.length; j++) {
                        switch (args[j]) {
                            case "--proto":
                                proto = true;
                                break;
                            case "--model": {
                                if (j + 1 < args.length) {
                                    j++;
                                    if ("power-profile".equals(args[j])) {
                                        model = BatteryConsumer.POWER_MODEL_POWER_PROFILE;
                                    } else {
                                        pw.println("Unknown power model: " + args[j]);
                                        dumpHelp(pw);
                                        return;
                                    }
                                } else {
                                    pw.println("--model without a value");
                                    dumpHelp(pw);
                                    return;
                                }
                                break;
                            }
                        }
                    }
                    dumpUsageStats(fd, pw, model, proto);
                    return;
                } else if ("--wakeups".equals(arg)) {
                    mCpuWakeupStats.dump(new IndentingPrintWriter(pw, "  "),
                            SystemClock.elapsedRealtime());
                    return;
                } else if (Flags.streamlinedBatteryStats() && "--sample".equals(arg)) {
                    dumpStatsSample(pw);
                    return;
                } else if (Flags.streamlinedBatteryStats() && "--aggregated".equals(arg)) {
                    dumpAggregatedStats(pw);
                    return;
                } else if ("--store".equals(arg)) {
                    dumpPowerStatsStore(pw);
                    return;
                } else if ("--store-toc".equals(arg)) {
                    dumpPowerStatsStoreTableOfContents(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    flags |= BatteryStats.DUMP_VERBOSE;
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    dumpHelp(pw);
                    return;
                } else {
                    // Not an option, last argument must be a package name.
                    try {
                        reqUid = mContext.getPackageManager().getPackageUidAsUser(arg,
                                UserHandle.getCallingUserId());
                    } catch (PackageManager.NameNotFoundException e) {
                        pw.println("Unknown package: " + arg);
                        dumpHelp(pw);
                        return;
                    }
                }
            }
        }
        if (noOutput) {
            return;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            if (BatteryStats.checkWifiOnly(mContext)) {
                flags |= BatteryStats.DUMP_DEVICE_WIFI_ONLY;
            }
            awaitCompletion();
            // Fetch data from external sources and update the BatteryStatsImpl object with them.
            syncStats("dump", BatteryExternalStatsWorker.UPDATE_ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        if (reqUid >= 0) {
            // By default, if the caller is only interested in a specific package, then
            // we only dump the aggregated data since charged.
            if ((flags&(BatteryStats.DUMP_HISTORY_ONLY|BatteryStats.DUMP_CHARGED_ONLY)) == 0) {
                flags |= BatteryStats.DUMP_CHARGED_ONLY;
                // Also if they are doing -c, we don't want history.
                flags &= ~BatteryStats.DUMP_INCLUDE_HISTORY;
            }
        }

        if (toProto) {
            List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(
                    PackageManager.MATCH_ANY_USER | PackageManager.MATCH_ALL);
            if (isRealCheckin) {
                // For a real checkin, first we want to prefer to use the last complete checkin
                // file if there is one.
                synchronized (mStats.mCheckinFile) {
                    if (mStats.mCheckinFile.exists()) {
                        try {
                            byte[] raw = mStats.mCheckinFile.readFully();
                            if (raw != null) {
                                Parcel in = Parcel.obtain();
                                in.unmarshall(raw, 0, raw.length);
                                in.setDataPosition(0);
                                BatteryStatsImpl checkinStats = new BatteryStatsImpl(
                                        mBatteryStatsConfig, Clock.SYSTEM_CLOCK, mMonotonicClock,
                                        null, mStats.mHandler, null, null,
                                        mUserManagerUserInfoProvider, mPowerProfile,
                                        mCpuScalingPolicies, new PowerStatsUidResolver());
                                checkinStats.readSummaryFromParcel(in);
                                in.recycle();
                                checkinStats.dumpProtoLocked(mContext, fd, apps, flags,
                                        historyStart, mDumpHelper);
                                mStats.mCheckinFile.delete();
                                return;
                            }
                        } catch (IOException | ParcelFormatException e) {
                            Slog.w(TAG, "Failure reading checkin file "
                                    + mStats.mCheckinFile.getBaseFile(), e);
                        }
                    }
                }
            }
            if (DBG) Slog.d(TAG, "begin dumpProtoLocked from UID " + Binder.getCallingUid());
            awaitCompletion();
            synchronized (mStats) {
                mStats.dumpProtoLocked(mContext, fd, apps, flags, historyStart, mDumpHelper);
                if (writeData) {
                    mStats.writeAsyncLocked();
                }
            }
            if (DBG) Slog.d(TAG, "end dumpProtoLocked");
        } else if (useCheckinFormat) {
            List<ApplicationInfo> apps = mContext.getPackageManager().getInstalledApplications(
                    PackageManager.MATCH_ANY_USER | PackageManager.MATCH_ALL);
            if (isRealCheckin) {
                // For a real checkin, first we want to prefer to use the last complete checkin
                // file if there is one.
                synchronized (mStats.mCheckinFile) {
                    if (mStats.mCheckinFile.exists()) {
                        try {
                            byte[] raw = mStats.mCheckinFile.readFully();
                            if (raw != null) {
                                Parcel in = Parcel.obtain();
                                in.unmarshall(raw, 0, raw.length);
                                in.setDataPosition(0);
                                BatteryStatsImpl checkinStats = new BatteryStatsImpl(
                                        mBatteryStatsConfig, Clock.SYSTEM_CLOCK, mMonotonicClock,
                                        null, mStats.mHandler, null, null,
                                        mUserManagerUserInfoProvider, mPowerProfile,
                                        mCpuScalingPolicies, new PowerStatsUidResolver());
                                checkinStats.readSummaryFromParcel(in);
                                in.recycle();
                                checkinStats.dumpCheckin(mContext, pw, apps, flags,
                                        historyStart, mDumpHelper);
                                mStats.mCheckinFile.delete();
                                return;
                            }
                        } catch (IOException | ParcelFormatException e) {
                            Slog.w(TAG, "Failure reading checkin file "
                                    + mStats.mCheckinFile.getBaseFile(), e);
                        }
                    }
                }
            }
            if (DBG) Slog.d(TAG, "begin dumpCheckin from UID " + Binder.getCallingUid());
            awaitCompletion();
            mStats.dumpCheckin(mContext, pw, apps, flags, historyStart, mDumpHelper);
            if (writeData) {
                mStats.writeAsyncLocked();
            }
            if (DBG) Slog.d(TAG, "end dumpCheckin");
        } else {
            if (DBG) Slog.d(TAG, "begin dump from UID " + Binder.getCallingUid());
            awaitCompletion();

            mStats.dump(mContext, pw, flags, reqUid, historyStart, mDumpHelper);
            if (writeData) {
                mStats.writeAsyncLocked();
            }
            pw.println();
            mCpuWakeupStats.dump(new IndentingPrintWriter(pw, "  "), SystemClock.elapsedRealtime());

            if (DBG) Slog.d(TAG, "end dump");
        }
    }

    /**
     * Gets a snapshot of cellular stats
     * @hide
     */
    @Override
    @EnforcePermission(anyOf = {UPDATE_DEVICE_STATS, BATTERY_STATS})
    public CellularBatteryStats getCellularBatteryStats() {
        // Wait for the completion of pending works if there is any
        super.getCellularBatteryStats_enforcePermission();

        awaitCompletion();
        synchronized (mStats) {
            return mStats.getCellularBatteryStats();
        }
    }

    /**
     * Gets a snapshot of Wifi stats
     * @hide
     */
    @Override
    @EnforcePermission(anyOf = {UPDATE_DEVICE_STATS, BATTERY_STATS})
    public WifiBatteryStats getWifiBatteryStats() {
        // Wait for the completion of pending works if there is any
        super.getWifiBatteryStats_enforcePermission();

        awaitCompletion();
        synchronized (mStats) {
            return mStats.getWifiBatteryStats();
        }
    }

    /**
     * Gets a snapshot of Gps stats
     * @hide
     */
    @Override
    @EnforcePermission(BATTERY_STATS)
    public GpsBatteryStats getGpsBatteryStats() {
        // Wait for the completion of pending works if there is any
        super.getGpsBatteryStats_enforcePermission();

        awaitCompletion();
        synchronized (mStats) {
            return mStats.getGpsBatteryStats();
        }
    }

    /**
     * Gets a snapshot of wake lock stats
     * @hide
     */
    @Override
    @EnforcePermission(BATTERY_STATS)
    public WakeLockStats getWakeLockStats() {
        // Wait for the completion of pending works if there is any
        super.getWakeLockStats_enforcePermission();

        awaitCompletion();
        synchronized (mStats) {
            return mStats.getWakeLockStats();
        }
    }

    /**
     * Gets a snapshot of Bluetooth stats
     * @hide
     */
    @Override
    @EnforcePermission(BATTERY_STATS)
    public BluetoothBatteryStats getBluetoothBatteryStats() {
        // Wait for the completion of pending works if there is any
        super.getBluetoothBatteryStats_enforcePermission();

        awaitCompletion();
        synchronized (mStats) {
            return mStats.getBluetoothBatteryStats();
        }
    }

    /**
     * Gets a snapshot of the system health for a particular uid.
     */
    @Override
    public HealthStatsParceler takeUidSnapshot(int requestUid) {
        if (requestUid != Binder.getCallingUid()) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.BATTERY_STATS, null);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            // Wait for the completion of pending works if there is any
            awaitCompletion();
            if (shouldCollectExternalStats()) {
                syncStats("get-health-stats-for-uids", BatteryExternalStatsWorker.UPDATE_ALL);
            }
            synchronized (mStats) {
                return getHealthStatsForUidLocked(requestUid);
            }
        } catch (Exception ex) {
            Slog.w(TAG, "Crashed while writing for takeUidSnapshot(" + requestUid + ")", ex);
            throw ex;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Gets a snapshot of the system health for a number of uids.
     */
    @Override
    public HealthStatsParceler[] takeUidSnapshots(int[] requestUids) {
        if (!onlyCaller(requestUids)) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.BATTERY_STATS, null);
        }
        final long ident = Binder.clearCallingIdentity();
        int i=-1;
        try {
            // Wait for the completion of pending works if there is any
            awaitCompletion();
            if (shouldCollectExternalStats()) {
                syncStats("get-health-stats-for-uids", BatteryExternalStatsWorker.UPDATE_ALL);
            }
            synchronized (mStats) {
                final int N = requestUids.length;
                final HealthStatsParceler[] results = new HealthStatsParceler[N];
                for (i=0; i<N; i++) {
                    results[i] = getHealthStatsForUidLocked(requestUids[i]);
                }
                return results;
            }
        } catch (Exception ex) {
            if (DBG) Slog.d(TAG, "Crashed while writing for takeUidSnapshots("
                    + Arrays.toString(requestUids) + ") i=" + i, ex);
            throw ex;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean shouldCollectExternalStats() {
        return (SystemClock.elapsedRealtime() - mWorker.getLastCollectionTimeStamp())
                > mStats.getExternalStatsCollectionRateLimitMs();
    }

    /**
     * Returns whether the Binder.getCallingUid is the only thing in requestUids.
     */
    private static boolean onlyCaller(int[] requestUids) {
        final int caller = Binder.getCallingUid();
        final int N = requestUids.length;
        for (int i=0; i<N; i++) {
            if (requestUids[i] != caller) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets a HealthStatsParceler for the given uid. You should probably call
     * updateExternalStatsSync first.
     */
    HealthStatsParceler getHealthStatsForUidLocked(int requestUid) {
        final HealthStatsBatteryStatsWriter writer = new HealthStatsBatteryStatsWriter();
        final HealthStatsWriter uidWriter = new HealthStatsWriter(UidHealthStats.CONSTANTS);
        final BatteryStats.Uid uid = mStats.getUidStats().get(requestUid);
        if (uid != null) {
            writer.writeUid(uidWriter, mStats, uid);
        }
        return new HealthStatsParceler(uidWriter);
    }

    /**
     * Delay for sending ACTION_CHARGING after device is plugged in.
     *
     * @hide
     */
    @EnforcePermission(POWER_SAVER)
    public boolean setChargingStateUpdateDelayMillis(int delayMillis) {
        super.setChargingStateUpdateDelayMillis_enforcePermission();

        final long ident = Binder.clearCallingIdentity();

        try {
            final ContentResolver contentResolver = mContext.getContentResolver();
            return Settings.Global.putLong(contentResolver,
                    Settings.Global.BATTERY_CHARGING_STATE_UPDATE_DELAY,
                    delayMillis);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void updateForegroundTimeIfOnBattery(final String packageName, final int uid,
            final long cpuTimeDiff) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                if (!isOnBattery()) {
                    return;
                }
                synchronized (mStats) {
                    final BatteryStatsImpl.Uid.Proc ps =
                            mStats.getProcessStatsLocked(uid, packageName, elapsedRealtime, uptime);
                    if (ps != null) {
                        ps.addForegroundTimeLocked(cpuTimeDiff);
                    }
                }
            });
        }
    }

    void noteCurrentTimeChanged() {
        synchronized (mLock) {
            final long currentTime = System.currentTimeMillis();
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteCurrentTimeChangedLocked(currentTime, elapsedRealtime, uptime);
                }
            });
        }
    }

    void updateBatteryStatsOnActivityUsage(final String packageName, final String className,
            final int uid, final int userId, final boolean resumed) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    if (resumed) {
                        mStats.noteActivityResumedLocked(uid, elapsedRealtime, uptime);
                    } else {
                        mStats.noteActivityPausedLocked(uid, elapsedRealtime, uptime);
                    }
                }
            });
        }
        FrameworkStatsLog.write(FrameworkStatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED,
                uid, packageName, className,
                resumed
                        ? FrameworkStatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED__STATE__FOREGROUND
                        : FrameworkStatsLog.ACTIVITY_FOREGROUND_STATE_CHANGED__STATE__BACKGROUND);
    }

    void noteProcessDied(final int uid, final int pid) {
        synchronized (mLock) {
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.noteProcessDiedLocked(uid, pid);
                }
            });
        }
    }

    void reportExcessiveCpu(final int uid, final String processName, final long uptimeSince,
            long cputimeUsed) {
        synchronized (mLock) {
            mHandler.post(() -> {
                synchronized (mStats) {
                    mStats.reportExcessiveCpuLocked(uid, processName, uptimeSince, cputimeUsed);
                }
            });
        }
    }

    void noteServiceStartRunning(int uid, String pkg, String name) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    final BatteryStatsImpl.Uid.Pkg.Serv stats = mStats.getServiceStatsLocked(uid,
                            pkg, name, elapsedRealtime, uptime);
                    stats.startRunningLocked(uptime);
                }
            });
        }
    }

    void noteServiceStopRunning(int uid, String pkg, String name) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    final BatteryStatsImpl.Uid.Pkg.Serv stats = mStats.getServiceStatsLocked(uid,
                            pkg, name, elapsedRealtime, uptime);
                    stats.stopRunningLocked(uptime);
                }
            });
        }
    }

    void noteServiceStartLaunch(int uid, String pkg, String name) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    final BatteryStatsImpl.Uid.Pkg.Serv stats = mStats.getServiceStatsLocked(uid,
                            pkg, name, elapsedRealtime, uptime);
                    stats.startLaunchedLocked(uptime);
                }
            });
        }
    }

    void noteServiceStopLaunch(int uid, String pkg, String name) {
        synchronized (mLock) {
            final long elapsedRealtime = SystemClock.elapsedRealtime();
            final long uptime = SystemClock.uptimeMillis();
            mHandler.post(() -> {
                synchronized (mStats) {
                    final BatteryStatsImpl.Uid.Pkg.Serv stats = mStats.getServiceStatsLocked(uid,
                            pkg, name, elapsedRealtime, uptime);
                    stats.stopLaunchedLocked(uptime);
                }
            });
        }
    }

    private static final String BATTERY_USAGE_STATS_BEFORE_RESET_TIMESTAMP_PROPERTY =
            "BATTERY_USAGE_STATS_BEFORE_RESET_TIMESTAMP";

    /**
     * Saves the supplied timestamp of the BATTERY_USAGE_STATS_BEFORE_RESET statsd atom pull
     * in persistent file.
     */
    public void setLastBatteryUsageStatsBeforeResetAtomPullTimestamp(long timestamp) {
        synchronized (mConfigFile) {
            Properties props = new Properties();
            try (InputStream in = mConfigFile.openRead()) {
                props.load(in);
            } catch (IOException e) {
                Slog.e(TAG, "Cannot load config file " + mConfigFile, e);
            }
            props.put(BATTERY_USAGE_STATS_BEFORE_RESET_TIMESTAMP_PROPERTY,
                    String.valueOf(timestamp));
            FileOutputStream out = null;
            try {
                out = mConfigFile.startWrite();
                props.store(out, "Statsd atom pull timestamps");
                mConfigFile.finishWrite(out);
            } catch (IOException e) {
                mConfigFile.failWrite(out);
                Slog.e(TAG, "Cannot save config file " + mConfigFile, e);
            }
        }
    }

    /**
     * Retrieves the previously saved timestamp of the last BATTERY_USAGE_STATS_BEFORE_RESET
     * statsd atom pull.
     */
    public long getLastBatteryUsageStatsBeforeResetAtomPullTimestamp() {
        synchronized (mConfigFile) {
            Properties props = new Properties();
            try (InputStream in = mConfigFile.openRead()) {
                props.load(in);
            } catch (IOException e) {
                Slog.e(TAG, "Cannot load config file " + mConfigFile, e);
            }
            return Long.parseLong(
                    props.getProperty(BATTERY_USAGE_STATS_BEFORE_RESET_TIMESTAMP_PROPERTY, "0"));
        }
    }

    /**
     * Sets battery AC charger to enabled/disabled, and freezes the battery state.
     */
    @Override
    @EnforcePermission(DEVICE_POWER)
    public void setChargerAcOnline(boolean online, boolean forceUpdate) {
        super.setChargerAcOnline_enforcePermission();

        mBatteryManagerInternal.setChargerAcOnline(online, forceUpdate);
    }

    /**
     * Sets battery level, and freezes the battery state.
     */
    @Override
    @EnforcePermission(DEVICE_POWER)
    public void setBatteryLevel(int level, boolean forceUpdate) {
        super.setBatteryLevel_enforcePermission();

        mBatteryManagerInternal.setBatteryLevel(level, forceUpdate);
    }

    /**
     * Unplugs battery, and freezes the battery state.
     */
    @Override
    @EnforcePermission(DEVICE_POWER)
    public void unplugBattery(boolean forceUpdate) {
        super.unplugBattery_enforcePermission();

        mBatteryManagerInternal.unplugBattery(forceUpdate);
    }

    /**
     * Unfreezes battery state, returning to current hardware values.
     */
    @Override
    @EnforcePermission(DEVICE_POWER)
    public void resetBattery(boolean forceUpdate) {
        super.resetBattery_enforcePermission();

        mBatteryManagerInternal.resetBattery(forceUpdate);
    }

    /**
     * Suspend charging even if plugged in.
     */
    @Override
    @EnforcePermission(DEVICE_POWER)
    public void suspendBatteryInput() {
        super.suspendBatteryInput_enforcePermission();

        mBatteryManagerInternal.suspendBatteryInput();
    }
}
