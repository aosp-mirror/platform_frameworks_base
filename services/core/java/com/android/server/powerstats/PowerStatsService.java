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

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.power.stats.Channel;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyMeasurement;
import android.hardware.power.stats.PowerEntity;
import android.hardware.power.stats.StateResidencyResult;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.power.PowerStatsInternal;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.SystemService;
import com.android.server.powerstats.PowerStatsHALWrapper.IPowerStatsHALWrapper;
import com.android.server.powerstats.ProtoStreamUtils.ChannelUtils;
import com.android.server.powerstats.ProtoStreamUtils.EnergyConsumerUtils;
import com.android.server.powerstats.ProtoStreamUtils.PowerEntityUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.concurrent.CompletableFuture;

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

    private final Injector mInjector;
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

    @VisibleForTesting
    static class Injector {
        @GuardedBy("this")
        private IPowerStatsHALWrapper mPowerStatsHALWrapper;

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

        PowerStatsLogger createPowerStatsLogger(Context context, File dataStoragePath,
                String meterFilename, String meterCacheFilename,
                String modelFilename, String modelCacheFilename,
                String residencyFilename, String residencyCacheFilename,
                IPowerStatsHALWrapper powerStatsHALWrapper) {
            return new PowerStatsLogger(context, dataStoragePath,
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
    }

    private final class BinderService extends Binder {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
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
                } else if (args.length == 0) {
                    pw.println("PowerStatsService dumpsys: available PowerEntities");
                    PowerEntity[] powerEntity = getPowerStatsHal().getPowerEntityInfo();
                    PowerEntityUtils.dumpsys(powerEntity, pw);

                    pw.println("PowerStatsService dumpsys: available Channels");
                    Channel[] channel = getPowerStatsHal().getEnergyMeterInfo();
                    ChannelUtils.dumpsys(channel, pw);

                    pw.println("PowerStatsService dumpsys: available EnergyConsumers");
                    EnergyConsumer[] energyConsumer = getPowerStatsHal().getEnergyConsumerInfo();
                    EnergyConsumerUtils.dumpsys(energyConsumer, pw);
                }
            }
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
            mPowerStatsInternal = new LocalService();
            publishLocalService(PowerStatsInternal.class, mPowerStatsInternal);
        }
        publishBinderService(Context.POWER_STATS_SERVICE, new BinderService());
    }

    private void onSystemServicesReady() {
        mPullAtomCallback = mInjector.createStatsPullerImpl(mContext, mPowerStatsInternal);
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
            mPowerStatsLogger = mInjector.createPowerStatsLogger(mContext, mDataStoragePath,
                mInjector.createMeterFilename(), mInjector.createMeterCacheFilename(),
                mInjector.createModelFilename(), mInjector.createModelCacheFilename(),
                mInjector.createResidencyFilename(), mInjector.createResidencyCacheFilename(),
                getPowerStatsHal());
            mBatteryTrigger = mInjector.createBatteryTrigger(mContext, mPowerStatsLogger);
            mTimerTrigger = mInjector.createTimerTrigger(mContext, mPowerStatsLogger);
        } else {
            Slog.e(TAG, "Failed to start PowerStatsService loggers");
        }
    }

    private IPowerStatsHALWrapper getPowerStatsHal() {
        return mInjector.getPowerStatsHALWrapperImpl();
    }

    public PowerStatsService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    public PowerStatsService(Context context, Injector injector) {
        super(context);
        mContext = context;
        mInjector = injector;
    }

    private final class LocalService extends PowerStatsInternal {
        private final Handler mHandler;

        LocalService() {
            HandlerThread thread = new HandlerThread(TAG);
            thread.start();
            mHandler = new Handler(thread.getLooper());
        }


        @Override
        public EnergyConsumer[] getEnergyConsumerInfo() {
            return getPowerStatsHal().getEnergyConsumerInfo();
        }

        @Override
        public CompletableFuture<EnergyConsumerResult[]> getEnergyConsumedAsync(
                int[] energyConsumerIds) {
            final CompletableFuture<EnergyConsumerResult[]> future = new CompletableFuture<>();
            mHandler.sendMessage(
                    PooledLambda.obtainMessage(PowerStatsService.this::getEnergyConsumedAsync,
                            future, energyConsumerIds));
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
            mHandler.sendMessage(
                    PooledLambda.obtainMessage(PowerStatsService.this::getStateResidencyAsync,
                            future, powerEntityIds));
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
            mHandler.sendMessage(
                    PooledLambda.obtainMessage(PowerStatsService.this::readEnergyMeterAsync,
                            future, channelIds));
            return future;
        }
    }

    private void getEnergyConsumedAsync(CompletableFuture<EnergyConsumerResult[]> future,
            int[] energyConsumerIds) {
        future.complete(getPowerStatsHal().getEnergyConsumed(energyConsumerIds));
    }

    private void getStateResidencyAsync(CompletableFuture<StateResidencyResult[]> future,
            int[] powerEntityIds) {
        future.complete(getPowerStatsHal().getStateResidency(powerEntityIds));
    }

    private void readEnergyMeterAsync(CompletableFuture<EnergyMeasurement[]> future,
            int[] channelIds) {
        future.complete(getPowerStatsHal().readEnergyMeter(channelIds));
    }
}
