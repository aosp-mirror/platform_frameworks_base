/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.brightness.clamper;

import static com.android.server.display.brightness.clamper.BrightnessPowerClamper.PowerChangeListener;

import android.annotation.Nullable;
import android.hardware.power.stats.EnergyConsumer;
import android.hardware.power.stats.EnergyConsumerResult;
import android.hardware.power.stats.EnergyConsumerType;
import android.os.IThermalService;
import android.os.RemoteException;
import android.os.Temperature;
import android.power.PowerStatsInternal;
import android.util.IntArray;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Monitors the display consumed power and helps make informed decision,
 * regarding overconsumption.
 */
public class PmicMonitor {
    private static final String TAG = "PmicMonitor";

    // The executor to periodically monitor the display power.
    private final ScheduledExecutorService mExecutor;
    private final long mPowerMonitorPeriodConfigMillis;
    private final PowerStatsInternal mPowerStatsInternal;
    @VisibleForTesting final IThermalService mThermalService;
    @VisibleForTesting PowerChangeListener mPowerChangeListener;
    private ScheduledFuture<?> mPmicMonitorFuture;
    private float mLastEnergyConsumed = 0;
    private float mCurrentTotalAvgPower = 0;
    private Temperature mCurrentTemperature;
    private long mCurrentTimestampMillis = 0;
    private float[] mAvgPowerCircularList;
    private int mPowerListStart = 0;
    private int mPowerListEnd = 0;

    PmicMonitor(PowerChangeListener listener,
                IThermalService thermalService,
                int pollingMaxTimeMillis,
                int pollingMinTimeMillis) {
        mPowerChangeListener = listener;
        mThermalService = thermalService;
        mPowerStatsInternal = LocalServices.getService(PowerStatsInternal.class);
        mAvgPowerCircularList = new float[pollingMaxTimeMillis / pollingMinTimeMillis];
        // start a periodic worker thread.
        mExecutor = Executors.newSingleThreadScheduledExecutor();
        mPowerMonitorPeriodConfigMillis = pollingMinTimeMillis;
    }

    @Nullable
    private Temperature getDisplayTemperature() {
        Temperature retTemperature = null;
        try {
            Temperature[] temperatures;
            // TODO b/279114539 Try DISPLAY first and then fallback to SKIN.
            temperatures = mThermalService.getCurrentTemperaturesWithType(
                        Temperature.TYPE_SKIN);
            if (temperatures.length > 1) {
                Slog.w(TAG, "Multiple skin temperatures not allowed!");
            }
            if (temperatures.length > 0) {
                retTemperature = temperatures[0];
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "getDisplayTemperature failed" + e);
        }
        return retTemperature;
    }

    private void capturePeriodicDisplayPower() {
        final EnergyConsumer[] energyConsumers = mPowerStatsInternal.getEnergyConsumerInfo();
        if (energyConsumers == null || energyConsumers.length == 0) {
            return;
        }
        final IntArray energyConsumerIds = new IntArray();
        for (int i = 0; i < energyConsumers.length; i++) {
            if (energyConsumers[i].type == EnergyConsumerType.DISPLAY) {
                energyConsumerIds.add(energyConsumers[i].id);
            }
        }

        if (energyConsumerIds.size() == 0) {
            Slog.w(TAG, "DISPLAY energyConsumerIds size is null");
            return;
        }
        CompletableFuture<EnergyConsumerResult[]> futureECRs =
                mPowerStatsInternal.getEnergyConsumedAsync(energyConsumerIds.toArray());
        if (futureECRs == null) {
            Slog.w(TAG, "Energy consumers results are null");
            return;
        }

        EnergyConsumerResult[] displayResults;
        try {
            displayResults = futureECRs.get();
        } catch (InterruptedException e) {
            Slog.w(TAG, "timeout or interrupt reading getEnergyConsumedAsync failed", e);
            displayResults = null;
        } catch (ExecutionException e) {
            Slog.wtf(TAG, "exception reading getEnergyConsumedAsync: ", e);
            displayResults = null;
        }

        if (displayResults == null || displayResults.length == 0) {
            Slog.w(TAG, "displayResults are null");
            return;
        }
        // Support for only 1 display rail.
        float energyConsumed = (displayResults[0].energyUWs - mLastEnergyConsumed);
        float timeIntervalSeconds =
                (displayResults[0].timestampMs - mCurrentTimestampMillis) / 1000.f;
        // energy consumed is received in microwatts-seconds.
        float currentPower = energyConsumed / timeIntervalSeconds;
        // convert power received in microwatts to milliwatts.
        currentPower = currentPower / 1000.f;

        // capture thermal state.
        Temperature displayTemperature = getDisplayTemperature();
        boolean isBufferFull = false;
        mAvgPowerCircularList[mPowerListEnd] = currentPower;
        mCurrentTotalAvgPower += currentPower;
        mPowerListEnd =
            (mPowerListEnd + 1) % mAvgPowerCircularList.length;
        if (mPowerListStart == mPowerListEnd) {
            isBufferFull = true;
        }

        mCurrentTemperature = displayTemperature;
        mLastEnergyConsumed = displayResults[0].energyUWs;
        mCurrentTimestampMillis = displayResults[0].timestampMs;

        if (mCurrentTemperature != null && isBufferFull) {
            mPowerChangeListener.onChanged(mCurrentTotalAvgPower / mAvgPowerCircularList.length,
                                            mCurrentTemperature.getStatus());
        }

        // average power long-term list is full, reset values for next cycle.
        if (isBufferFull) {
            mCurrentTotalAvgPower = mCurrentTotalAvgPower - mAvgPowerCircularList[mPowerListStart];
            mPowerListStart = (mPowerListStart + 1) % mAvgPowerCircularList.length;
        }
    }

    /**
    * Start polling the power IC.
    */
    public void start() {
        if (mPowerStatsInternal == null) {
            Slog.w(TAG, "Power stats service not found for monitoring.");
            return;
        }
        if (mThermalService == null) {
            Slog.w(TAG, "Thermal service not found.");
            return;
        }
        if (mPmicMonitorFuture == null) {
            mPmicMonitorFuture = mExecutor.scheduleAtFixedRate(
                                    this::capturePeriodicDisplayPower,
                                    mPowerMonitorPeriodConfigMillis,
                                    mPowerMonitorPeriodConfigMillis,
                                    TimeUnit.MILLISECONDS);
        } else {
            Slog.e(TAG, "PMIC already scheduled, stop() called before start.");
        }
    }

    /**
     * Stop polling to power IC.
     */
    public void stop() {
        if (mPmicMonitorFuture != null) {
            mPmicMonitorFuture.cancel(true);
            mPmicMonitorFuture = null;
        }
    }

    /**
     * Updates PMIC configuration.
     */
    public void updateConfiguration() {
        if (mPmicMonitorFuture != null) {
            mPmicMonitorFuture.cancel(true);
            mPmicMonitorFuture = null;
        }
    }

    /**
     * Returns if PMIC monitor is stopped.
     */
    public boolean isStopped() {
        return mPmicMonitorFuture == null;
    }

    /**
     * Shutdown power IC service and worker thread.
     */
    public void shutdown() {
        mExecutor.shutdownNow();
    }
}
