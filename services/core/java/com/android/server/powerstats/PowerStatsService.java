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

package com.android.server.powerstats;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.power.stats.Channel;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.StateResidencyResult;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IPowerStatsService;
import android.os.Looper;
import android.os.PowerMonitor;
import android.os.PowerMonitorReadings;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.power.PowerStatsInternal;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.Clock;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;
import com.android.server.powerstats.PowerStatsHALWrapper.IPowerStatsHALWrapper;
import com.android.server.powerstats.ProtoStreamUtils.ChannelUtils;
import com.android.server.powerstats.ProtoStreamUtils.EnergyConsumerUtils;
import com.android.server.powerstats.ProtoStreamUtils.PowerEntityUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * This class provides a system service that estimates system power usage
 * per subsystem (modem, wifi, gps, display, etc) and provides those power
 * estimates to subscribers.
 */
public class PowerStatsService extends SystemService {
    private static final String TAG = PowerStatsService.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final String DATA_STORAGE_SUBDIR = "powerstats";
    private static final int DATA_STORAGE_VERSION = 0;
    private static final String METER_FILENAME = "log.powerstats.meter." + DATA_STORAGE_VERSION;
    private static final String MODEL_FILENAME = "log.powerstats.model." + DATA_STORAGE_VERSION;
    private static final String RESIDENCY_FILENAME =
            "log.powerstats.residency." + DATA_STORAGE_VERSION;
    private static final String METER_CACHE_FILENAME = "meterCache";
    private static final String MODEL_CACHE_FILENAME = "modelCache";
    private static final String RESIDENCY_CACHE_FILENAME = "residencyCache";
    private static final long MAX_POWER_MONITOR_AGE_MILLIS = 30_000;

    static final String KEY_POWER_MONITOR_API_ENABLED = "power_monitor_api_enabled";

    // The alpha parameter of the Beta distribution used by the random noise generator.
    // The higher this value, the smaller the amount of added noise.
    private static final double INTERVAL_RANDOM_NOISE_GENERATION_ALPHA = 50;
    private static final long MAX_RANDOM_NOISE_UWS = 10_000_000;

    private final Injector mInjector;
    private final Clock mClock;
    private final DeviceConfigInterface mDeviceConfig;
    private final DeviceConfigListener mDeviceConfigListener = new DeviceConfigListener();
    private File mDataStoragePath;

    private Context mContext;
    @Nullable
    private PowerStatsLogger mPowerStatsLogger;
    @Nullable
    private BatteryTrigger mBatteryTrigger;
    @Nullable
    private TimerTrigger mTimerTrigger;
    @Nullable
    private StatsPullAtomCallbackImpl mPullAtomCallback;
    @Nullable
    private PowerStatsInternal mPowerStatsInternal;
    @Nullable
    @GuardedBy("this")
    private Looper mLooper;
    private Handler mHandler;
    @Nullable
    @GuardedBy("this")
    private EnergyConsumer[] mEnergyConsumers = null;
    @Nullable
    @GuardedBy("this")
    private Channel[] mEnergyMeters = null;

    @VisibleForTesting
    static class Injector {
        @GuardedBy("this")
        private IPowerStatsHALWrapper mPowerStatsHALWrapper;

        Clock getClock() {
            return Clock.SYSTEM_CLOCK;
        }

        File createDataStoragePath() {
            return new File(Environment.getDataSystemDeDirectory(UserHandle.USER_SYSTEM),
                DATA_STORAGE_SUBDIR);
        }

        String createMeterFilename() {
            return METER_FILENAME;
        }

        String createModelFilename() {
            return MODEL_FILENAME;
        }

        String createResidencyFilename() {
            return RESIDENCY_FILENAME;
        }

        String createMeterCacheFilename() {
            return METER_CACHE_FILENAME;
        }

        String createModelCacheFilename() {
            return MODEL_CACHE_FILENAME;
        }

        String createResidencyCacheFilename() {
            return RESIDENCY_CACHE_FILENAME;
        }

        IPowerStatsHALWrapper createPowerStatsHALWrapperImpl() {
            return PowerStatsHALWrapper.getPowerStatsHalImpl();
        }

        IPowerStatsHALWrapper getPowerStatsHALWrapperImpl() {
            synchronized (this) {
                if (mPowerStatsHALWrapper == null) {
                    mPowerStatsHALWrapper = PowerStatsHALWrapper.getPowerStatsHalImpl();
                }
                return mPowerStatsHALWrapper;
            }
        }

        PowerStatsLogger createPowerStatsLogger(Context context, Looper looper,
                File dataStoragePath, String meterFilename, String meterCacheFilename,
                String modelFilename, String modelCacheFilename,
                String residencyFilename, String residencyCacheFilename,
                IPowerStatsHALWrapper powerStatsHALWrapper) {
            return new PowerStatsLogger(context, looper, dataStoragePath,
                meterFilename, meterCacheFilename,
                modelFilename, modelCacheFilename,
                residencyFilename, residencyCacheFilename,
                powerStatsHALWrapper);
        }

        BatteryTrigger createBatteryTrigger(Context context, PowerStatsLogger powerStatsLogger) {
            return new BatteryTrigger(context, powerStatsLogger, true /* trigger enabled */);
        }

        TimerTrigger createTimerTrigger(Context context, PowerStatsLogger powerStatsLogger) {
            return new TimerTrigger(context, powerStatsLogger, true /* trigger enabled */);
        }

        StatsPullAtomCallbackImpl createStatsPullerImpl(Context context,
                PowerStatsInternal powerStatsInternal) {
            return new StatsPullAtomCallbackImpl(context, powerStatsInternal);
        }

        DeviceConfigInterface getDeviceConfig() {
            return DeviceConfigInterface.REAL;
        }

        IntervalRandomNoiseGenerator createIntervalRandomNoiseGenerator() {
            return new IntervalRandomNoiseGenerator(INTERVAL_RANDOM_NOISE_GENERATION_ALPHA);
        }
    }

    private final IBinder mService = new IPowerStatsService.Stub() {

        @Override
        public void getSupportedPowerMonitors(@NonNull ResultReceiver resultReceiver) {
            if (Flags.verifyNonNullArguments()) {
                Objects.requireNonNull(resultReceiver);
            }
            getHandler().post(() -> getSupportedPowerMonitorsImpl(resultReceiver));
        }

        @Override
        public void getPowerMonitorReadings(@NonNull int[] powerMonitorIds,
                @NonNull ResultReceiver resultReceiver) {
            if (Flags.verifyNonNullArguments()) {
                Objects.requireNonNull(powerMonitorIds);
                Objects.requireNonNull(resultReceiver);
            }
            int callingUid = Binder.getCallingUid();
            getHandler().post(() ->
                    getPowerMonitorReadingsImpl(powerMonitorIds, resultReceiver, callingUid));
        }

        @Override
        protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
                @Nullable String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            if (mPowerStatsLogger == null) {
                Slog.e(TAG, "PowerStats HAL is not initialized.  No data available.");
            } else {
                if (args.length > 0 && "--proto".equals(args[0])) {
                    if ("model".equals(args[1])) {
                        mPowerStatsLogger.writeModelDataToFile(fd);
                    } else if ("meter".equals(args[1])) {
                        mPowerStatsLogger.writeMeterDataToFile(fd);
                    } else if ("residency".equals(args[1])) {
                        mPowerStatsLogger.writeResidencyDataToFile(fd);
                    }
                } else {
                    IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
                    ipw.println("PowerStatsService dumpsys: available PowerEntities");
                    PowerEntity[] powerEntity = getPowerStatsHal().getPowerEntityInfo();
                    ipw.increaseIndent();
                    PowerEntityUtils.dumpsys(powerEntity, ipw);
                    ipw.decreaseIndent();

                    ipw.println("PowerStatsService dumpsys: available Channels");
                    Channel[] channel = getPowerStatsHal().getEnergyMeterInfo();
                    ipw.increaseIndent();
                    ChannelUtils.dumpsys(channel, ipw);
                    ipw.decreaseIndent();

                    ipw.println("PowerStatsService dumpsys: available EnergyConsumers");
                    EnergyConsumer[] energyConsumer = getPowerStatsHal().getEnergyConsumerInfo();
                    ipw.increaseIndent();
                    EnergyConsumerUtils.dumpsys(energyConsumer, ipw);
                    ipw.decreaseIndent();

                    ipw.println("PowerStatsService dumpsys: PowerStatsLogger stats");
                    ipw.increaseIndent();
                    mPowerStatsLogger.dump(ipw);
                    ipw.decreaseIndent();

                }
            }
        }
    };

    @VisibleForTesting
    IPowerStatsService getIPowerStatsServiceForTest() {
        return (IPowerStatsService) mService;
    }

    private class DeviceConfigListener implements DeviceConfig.OnPropertiesChangedListener {
        public Executor mExecutor = new HandlerExecutor(getHandler());

        void startListening() {
            mDeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_BATTERY_STATS,
                    mExecutor, this);
        }

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            refreshFlags();
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            onSystemServicesReady();
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            onBootCompleted();
        }
    }

    @Override
    public void onStart() {
        if (getPowerStatsHal().isInitialized()) {
            publishLocalService(PowerStatsInternal.class, getPowerStatsInternal());
        }
        publishBinderService(Context.POWER_STATS_SERVICE, mService);
    }

    /**
     * Returns the PowerStatsInternal associated with this service, maybe creating it if needed.
     */
    @VisibleForTesting
    public PowerStatsInternal getPowerStatsInternal() {
        if (mPowerStatsInternal == null) {
            mPowerStatsInternal = new LocalService();
        }
        return mPowerStatsInternal;
    }

    private void onSystemServicesReady() {
        mPullAtomCallback = mInjector.createStatsPullerImpl(mContext, mPowerStatsInternal);
        mDeviceConfigListener.startListening();
        refreshFlags();
    }

    @VisibleForTesting
    public boolean getDeleteMeterDataOnBoot() {
        return mPowerStatsLogger.getDeleteMeterDataOnBoot();
    }

    @VisibleForTesting
    public boolean getDeleteModelDataOnBoot() {
        return mPowerStatsLogger.getDeleteModelDataOnBoot();
    }

    @VisibleForTesting
    public boolean getDeleteResidencyDataOnBoot() {
        return mPowerStatsLogger.getDeleteResidencyDataOnBoot();
    }

    private void onBootCompleted() {
        if (getPowerStatsHal().isInitialized()) {
            if (DEBUG) Slog.d(TAG, "Starting PowerStatsService loggers");
            mDataStoragePath = mInjector.createDataStoragePath();

            // Only start logger and triggers if initialization is successful.
            mPowerStatsLogger = mInjector.createPowerStatsLogger(mContext, getLooper(),
                    mDataStoragePath, mInjector.createMeterFilename(),
                    mInjector.createMeterCacheFilename(), mInjector.createModelFilename(),
                    mInjector.createModelCacheFilename(), mInjector.createResidencyFilename(),
                    mInjector.createResidencyCacheFilename(), getPowerStatsHal());
            mBatteryTrigger = mInjector.createBatteryTrigger(mContext, mPowerStatsLogger);
            mTimerTrigger = mInjector.createTimerTrigger(mContext, mPowerStatsLogger);
        } else {
            Slog.e(TAG, "Failed to start PowerStatsService loggers");
        }
    }

    private IPowerStatsHALWrapper getPowerStatsHal() {
        return mInjector.getPowerStatsHALWrapperImpl();
    }

    private Looper getLooper() {
        synchronized (this) {
            if (mLooper == null) {
                HandlerThread thread = new HandlerThread(TAG);
                thread.start();
                return thread.getLooper();
            }
            return mLooper;
        }
    }

    private Handler getHandler() {
        synchronized (this) {
            if (mHandler == null) {
                mHandler = new Handler(getLooper());
            }
            return mHandler;
        }
    }

    private EnergyConsumer[] getEnergyConsumerInfo() {
        synchronized (this) {
            if (mEnergyConsumers == null) {
                mEnergyConsumers = getPowerStatsHal().getEnergyConsumerInfo();
            }
            return mEnergyConsumers;
        }
    }

    private Channel[] getEnergyMeterInfo() {
        synchronized (this) {
            if (mEnergyMeters == null) {
                mEnergyMeters = getPowerStatsHal().getEnergyMeterInfo();
            }
            return mEnergyMeters;
        }
    }

    public PowerStatsService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    public PowerStatsService(Context context, Injector injector) {
        super(context);
        mContext = context;
        mInjector = injector;
        mClock = injector.getClock();
        mDeviceConfig = injector.getDeviceConfig();
    }

    void refreshFlags() {
        setPowerMonitorApiEnabled(mDeviceConfig.getBoolean(DeviceConfig.NAMESPACE_BATTERY_STATS,
                KEY_POWER_MONITOR_API_ENABLED, true));
    }

    private final class LocalService extends PowerStatsInternal {

        @Override
        public EnergyConsumer[] getEnergyConsumerInfo() {
            return getPowerStatsHal().getEnergyConsumerInfo();
        }

        @Override
        public CompletableFuture<EnergyConsumerResult[]> getEnergyConsumedAsync(
                int[] energyConsumerIds) {
            final CompletableFuture<EnergyConsumerResult[]> future = new CompletableFuture<>();
            getHandler().post(
                    () -> PowerStatsService.this.getEnergyConsumedAsync(future, energyConsumerIds));
            return future;
        }

        @Override
        public PowerEntity[] getPowerEntityInfo() {
            return getPowerStatsHal().getPowerEntityInfo();
        }

        @Override
        public CompletableFuture<StateResidencyResult[]> getStateResidencyAsync(
                int[] powerEntityIds) {
            final CompletableFuture<StateResidencyResult[]> future = new CompletableFuture<>();
            getHandler().post(
                    () -> PowerStatsService.this.getStateResidencyAsync(future, powerEntityIds));
            return future;
        }

        @Override
        public Channel[] getEnergyMeterInfo() {
            return getPowerStatsHal().getEnergyMeterInfo();
        }

        @Override
        public CompletableFuture<EnergyMeasurement[]> readEnergyMeterAsync(
                int[] channelIds) {
            final CompletableFuture<EnergyMeasurement[]> future = new CompletableFuture<>();
            getHandler().post(
                    () -> PowerStatsService.this.readEnergyMeterAsync(future, channelIds));
            return future;
        }
    }

    private void getEnergyConsumedAsync(CompletableFuture<EnergyConsumerResult[]> future,
            int[] energyConsumerIds) {
        EnergyConsumerResult[] results;
        try {
            results = getPowerStatsHal().getEnergyConsumed(energyConsumerIds);
        } catch (Exception e) {
            future.completeExceptionally(e);
            return;
        }

        // STOPSHIP(253292374): Remove once missing EnergyConsumer results issue is resolved.
        EnergyConsumer[] energyConsumers = getEnergyConsumerInfo();
        if (energyConsumers != null) {
            final int expectedLength;
            if (energyConsumerIds.length == 0) {
                // Empty request is a request for all available EnergyConsumers.
                expectedLength = energyConsumers.length;
            } else {
                expectedLength = energyConsumerIds.length;
            }

            if (results == null || expectedLength != results.length) {
                // Mismatch in requested/received energy consumer data.
                StringBuilder sb = new StringBuilder();
                sb.append("Requested ids:");
                if (energyConsumerIds.length == 0) {
                    sb.append("ALL");
                }
                sb.append("[");
                for (int i = 0; i < energyConsumerIds.length; i++) {
                    final int id = energyConsumerIds[i];
                    sb.append(id);
                    sb.append("(type:");
                    sb.append(energyConsumers[id].type);
                    sb.append(",ord:");
                    sb.append(energyConsumers[id].ordinal);
                    sb.append(",name:");
                    sb.append(energyConsumers[id].name);
                    sb.append(")");
                    if (i != expectedLength - 1) {
                        sb.append(", ");
                    }
                }
                sb.append("]");

                sb.append(", Received result ids:");
                if (results == null) {
                    sb.append("null");
                } else {
                    sb.append("[");
                    final int resultLength = results.length;
                    for (int i = 0; i < resultLength; i++) {
                        final int id = results[i].id;
                        sb.append(id);
                        sb.append("(type:");
                        sb.append(energyConsumers[id].type);
                        sb.append(",ord:");
                        sb.append(energyConsumers[id].ordinal);
                        sb.append(",name:");
                        sb.append(energyConsumers[id].name);
                        sb.append(")");
                        if (i != resultLength - 1) {
                            sb.append(", ");
                        }
                    }
                    sb.append("]");
                }
                Slog.wtf(TAG, "Missing result from getEnergyConsumedAsync call. " + sb);
            }
        }
        future.complete(results);
    }

    private void getStateResidencyAsync(CompletableFuture<StateResidencyResult[]> future,
            int[] powerEntityIds) {
        try {
            future.complete(getPowerStatsHal().getStateResidency(powerEntityIds));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    private void readEnergyMeterAsync(CompletableFuture<EnergyMeasurement[]> future,
            int[] channelIds) {
        try {
            future.complete(getPowerStatsHal().readEnergyMeter(channelIds));
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }

    private static class PowerMonitorState {
        public final PowerMonitor powerMonitor;
        public final int id;
        public long timestampMs;
        public long energyUws = PowerMonitorReadings.ENERGY_UNAVAILABLE;
        public long prevEnergyUws;

        private PowerMonitorState(PowerMonitor powerMonitor, int id) {
            this.powerMonitor = powerMonitor;
            this.id = id;
        }
    }

    private boolean mPowerMonitorApiEnabled = true;
    private volatile PowerMonitor[] mPowerMonitors;
    private PowerMonitorState[] mPowerMonitorStates;
    private IntervalRandomNoiseGenerator mIntervalRandomNoiseGenerator;

    private void setPowerMonitorApiEnabled(boolean powerMonitorApiEnabled) {
        if (powerMonitorApiEnabled != mPowerMonitorApiEnabled) {
            mPowerMonitorApiEnabled = powerMonitorApiEnabled;
            mPowerMonitors = null;
            mPowerMonitorStates = null;
        }
    }

    private void ensurePowerMonitors() {
        if (mPowerMonitors != null) {
            return;
        }

        synchronized (this) {
            if (mPowerMonitors != null) {
                return;
            }

            if (mIntervalRandomNoiseGenerator == null) {
                mIntervalRandomNoiseGenerator = mInjector.createIntervalRandomNoiseGenerator();
            }

            if (!mPowerMonitorApiEnabled) {
                mPowerMonitors = new PowerMonitor[0];
                mPowerMonitorStates = new PowerMonitorState[0];
                return;
            }

            List<PowerMonitor> monitors = new ArrayList<>();
            List<PowerMonitorState> states = new ArrayList<>();

            int index = 0;

            Channel[] channels = getEnergyMeterInfo();
            if (channels != null) {
                for (Channel channel : channels) {
                    PowerMonitor monitor = new PowerMonitor(index++,
                            PowerMonitor.POWER_MONITOR_TYPE_MEASUREMENT,
                            getChannelName(channel));
                    monitors.add(monitor);
                    states.add(new PowerMonitorState(monitor, channel.id));
                }
            }
            EnergyConsumer[] energyConsumers = getEnergyConsumerInfo();
            if (energyConsumers != null) {
                for (EnergyConsumer consumer : energyConsumers) {
                    PowerMonitor monitor = new PowerMonitor(index++,
                            PowerMonitor.POWER_MONITOR_TYPE_CONSUMER,
                            getEnergyConsumerName(consumer, energyConsumers));
                    monitors.add(monitor);
                    states.add(new PowerMonitorState(monitor, consumer.id));
                }
            }
            mPowerMonitors = monitors.toArray(new PowerMonitor[monitors.size()]);
            mPowerMonitorStates = states.toArray(new PowerMonitorState[monitors.size()]);
        }
    }

    @NonNull
    private String getChannelName(Channel c) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(c.name).append("]:");
        if (c.subsystem != null) {
            sb.append(c.subsystem);
        }
        return sb.toString();
    }

    @NonNull
    private String getEnergyConsumerName(EnergyConsumer consumer,
            EnergyConsumer[] energyConsumers) {
        StringBuilder sb = new StringBuilder();
        switch (consumer.type) {
            case EnergyConsumerType.BLUETOOTH:
                sb.append("BLUETOOTH");
                break;
            case EnergyConsumerType.CPU_CLUSTER:
                sb.append("CPU");
                break;
            case EnergyConsumerType.DISPLAY:
                sb.append("DISPLAY");
                break;
            case EnergyConsumerType.GNSS:
                sb.append("GNSS");
                break;
            case EnergyConsumerType.MOBILE_RADIO:
                sb.append("MOBILE_RADIO");
                break;
            case EnergyConsumerType.WIFI:
                sb.append("WIFI");
                break;
            case EnergyConsumerType.CAMERA:
                sb.append("CAMERA");
                break;
            default:
                if (consumer.name != null && !consumer.name.isBlank()) {
                    sb.append(consumer.name.toUpperCase(Locale.ENGLISH));
                } else {
                    sb.append("CONSUMER_").append(consumer.type);
                }
                break;
        }
        boolean hasOrdinal = consumer.ordinal != 0;
        if (!hasOrdinal) {
            // See if any other EnergyConsumer of the same type has an ordinal
            for (EnergyConsumer aConsumer : energyConsumers) {
                if (aConsumer.type == consumer.type && aConsumer.ordinal != 0) {
                    hasOrdinal = true;
                    break;
                }
            }
        }
        if (hasOrdinal) {
            sb.append('/').append(consumer.ordinal);
        }
        return sb.toString();
    }

    /**
     * Returns names of supported power monitors, including Channels and EnergyConsumers.
     */
    @VisibleForTesting
    public void getSupportedPowerMonitorsImpl(ResultReceiver resultReceiver) {
        ensurePowerMonitors();
        Bundle result = new Bundle();
        result.putParcelableArray(IPowerStatsService.KEY_MONITORS, mPowerMonitors);
        resultReceiver.send(IPowerStatsService.RESULT_SUCCESS, result);
    }

    /**
     * Returns the latest readings for the specified power monitors.
     */
    @VisibleForTesting
    public void getPowerMonitorReadingsImpl(@NonNull int[] powerMonitorIndices,
            ResultReceiver resultReceiver, int callingUid) {
        ensurePowerMonitors();

        long earliestTimestamp = Long.MAX_VALUE;
        PowerMonitorState[] powerMonitorStates = new PowerMonitorState[powerMonitorIndices.length];
        for (int i = 0; i < powerMonitorIndices.length; i++) {
            int index = powerMonitorIndices[i];
            if (index < 0 || index >= mPowerMonitorStates.length) {
                resultReceiver.send(IPowerStatsService.RESULT_UNSUPPORTED_POWER_MONITOR, null);
                return;
            }

            powerMonitorStates[i] = mPowerMonitorStates[index];
            if (mPowerMonitorStates[index] != null
                    && mPowerMonitorStates[index].timestampMs < earliestTimestamp) {
                earliestTimestamp = mPowerMonitorStates[index].timestampMs;
            }
        }

        if (earliestTimestamp == 0
                || mClock.elapsedRealtime() - earliestTimestamp > MAX_POWER_MONITOR_AGE_MILLIS) {
            updateEnergyConsumers(powerMonitorStates);
            updateEnergyMeasurements(powerMonitorStates);
            mIntervalRandomNoiseGenerator.refresh();
        }

        long[] energy = new long[powerMonitorStates.length];
        long[] timestamps = new long[powerMonitorStates.length];
        for (int i = 0; i < powerMonitorStates.length; i++) {
            PowerMonitorState state = powerMonitorStates[i];
            if (state.energyUws != PowerMonitorReadings.ENERGY_UNAVAILABLE
                    && state.prevEnergyUws != PowerMonitorReadings.ENERGY_UNAVAILABLE) {
                energy[i] = mIntervalRandomNoiseGenerator.addNoise(
                        Math.max(state.prevEnergyUws, state.energyUws - MAX_RANDOM_NOISE_UWS),
                        state.energyUws, callingUid);
                if (DEBUG) {
                    Log.d(TAG, String.format(Locale.ENGLISH,
                            "Monitor=%s timestamp=%d energy=%d"
                                    + " uid=%d noise=%.1f%% returned=%d",
                            state.powerMonitor.getName(),
                            state.timestampMs,
                            state.energyUws,
                            callingUid,
                            state.energyUws != state.prevEnergyUws
                                    ? (state.energyUws - energy[i]) * 100.0
                                            / (state.energyUws - state.prevEnergyUws)
                                    : 0,
                            energy[i]));
                }
            } else {
                energy[i] = state.energyUws;
            }
            timestamps[i] = state.timestampMs;
        }

        Bundle result = new Bundle();
        result.putLongArray(IPowerStatsService.KEY_ENERGY, energy);
        result.putLongArray(IPowerStatsService.KEY_TIMESTAMPS, timestamps);
        resultReceiver.send(IPowerStatsService.RESULT_SUCCESS, result);
    }

    private void updateEnergyConsumers(PowerMonitorState[] powerMonitorStates) {
        int[] ids = collectIds(powerMonitorStates, PowerMonitor.POWER_MONITOR_TYPE_CONSUMER);
        if (ids == null) {
            return;
        }

        EnergyConsumerResult[] energyConsumerResults = getPowerStatsHal().getEnergyConsumed(ids);
        if (energyConsumerResults == null) {
            return;
        }

        for (PowerMonitorState powerMonitorState : powerMonitorStates) {
            if (powerMonitorState.powerMonitor.getType()
                    == PowerMonitor.POWER_MONITOR_TYPE_CONSUMER) {
                for (EnergyConsumerResult energyConsumerResult : energyConsumerResults) {
                    if (energyConsumerResult.id == powerMonitorState.id) {
                        powerMonitorState.prevEnergyUws = powerMonitorState.energyUws;
                        powerMonitorState.energyUws = energyConsumerResult.energyUWs;
                        powerMonitorState.timestampMs = energyConsumerResult.timestampMs;
                        break;
                    }
                }
            }
        }
    }

    private void updateEnergyMeasurements(PowerMonitorState[] powerMonitorStates) {
        int[] ids = collectIds(powerMonitorStates, PowerMonitor.POWER_MONITOR_TYPE_MEASUREMENT);
        if (ids == null) {
            return;
        }

        EnergyMeasurement[] energyMeasurements = getPowerStatsHal().readEnergyMeter(ids);
        if (energyMeasurements == null) {
            return;
        }

        for (PowerMonitorState powerMonitorState : powerMonitorStates) {
            if (powerMonitorState.powerMonitor.getType()
                    == PowerMonitor.POWER_MONITOR_TYPE_MEASUREMENT) {
                for (EnergyMeasurement energyMeasurement : energyMeasurements) {
                    if (energyMeasurement.id == powerMonitorState.id) {
                        powerMonitorState.prevEnergyUws = powerMonitorState.energyUws;
                        powerMonitorState.energyUws = energyMeasurement.energyUWs;
                        powerMonitorState.timestampMs = energyMeasurement.timestampMs;
                        break;
                    }
                }
            }
        }
    }

    @Nullable
    private int[] collectIds(PowerMonitorState[] powerMonitorStates,
            @PowerMonitor.PowerMonitorType int type) {
        int count = 0;
        for (PowerMonitorState monitorState : powerMonitorStates) {
            if (monitorState.powerMonitor.getType() == type) {
                count++;
            }
        }

        if (count == 0) {
            return null;
        }

        int[] ids = new int[count];
        int index = 0;
        for (PowerMonitorState monitorState : powerMonitorStates) {
            if (monitorState.powerMonitor.getType() == type) {
                ids[index++] = monitorState.id;
            }
        }
        return ids;
    }
}
