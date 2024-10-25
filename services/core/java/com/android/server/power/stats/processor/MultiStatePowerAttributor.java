/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.power.stats.processor;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.SensorManager;
import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BatteryStatsHistory;
import com.android.internal.os.CpuScalingPolicies;
import com.android.internal.os.PowerProfile;
import com.android.server.power.stats.PowerAttributor;
import com.android.server.power.stats.PowerStatsSpan;
import com.android.server.power.stats.PowerStatsStore;

import java.util.List;
import java.util.function.DoubleSupplier;

public class MultiStatePowerAttributor implements PowerAttributor {
    private static final String TAG = "MultiStatePowerAttributor";

    private final PowerStatsStore mPowerStatsStore;
    private final PowerStatsExporter mPowerStatsExporter;
    private final PowerStatsAggregator mPowerStatsAggregator;
    private final SparseBooleanArray mPowerStatsExporterEnabled = new SparseBooleanArray();

    public MultiStatePowerAttributor(Context context, PowerStatsStore powerStatsStore,
            @NonNull PowerProfile powerProfile, @NonNull CpuScalingPolicies cpuScalingPolicies,
            @NonNull DoubleSupplier batteryCapacitySupplier) {
        this(powerStatsStore, new PowerStatsAggregator(
                createAggregatedPowerStatsConfig(context, powerProfile, cpuScalingPolicies,
                        batteryCapacitySupplier)));
    }

    @VisibleForTesting
    MultiStatePowerAttributor(PowerStatsStore powerStatsStore,
            PowerStatsAggregator powerStatsAggregator) {
        mPowerStatsStore = powerStatsStore;
        mPowerStatsAggregator = powerStatsAggregator;
        mPowerStatsStore.addSectionReader(
                new AggregatedPowerStatsSection.Reader(mPowerStatsAggregator.getConfig()));
        mPowerStatsExporter = new PowerStatsExporter(mPowerStatsStore, mPowerStatsAggregator);
        setPowerComponentSupported(BatteryConsumer.POWER_COMPONENT_BASE, true);
    }

    private static AggregatedPowerStatsConfig createAggregatedPowerStatsConfig(Context context,
            PowerProfile powerProfile, CpuScalingPolicies cpuScalingPolicies,
            DoubleSupplier batteryCapacitySupplier) {
        AggregatedPowerStatsConfig config = new AggregatedPowerStatsConfig();
        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_BASE)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(() -> new BasePowerStatsProcessor(batteryCapacitySupplier));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_WAKELOCK)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new WakelockPowerStatsProcessor(powerProfile));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_CPU,
                        BatteryConsumer.POWER_COMPONENT_WAKELOCK)
                .setProcessorSupplier(
                        () -> new CpuPowerStatsProcessor(powerProfile, cpuScalingPolicies));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_SCREEN)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .setProcessorSupplier(
                        () -> new ScreenPowerStatsProcessor(powerProfile));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_AMBIENT_DISPLAY,
                        BatteryConsumer.POWER_COMPONENT_SCREEN)
                .setProcessorSupplier(AmbientDisplayPowerStatsProcessor::new);

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new MobileRadioPowerStatsProcessor(powerProfile));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_PHONE,
                        BatteryConsumer.POWER_COMPONENT_MOBILE_RADIO)
                .setProcessorSupplier(PhoneCallPowerStatsProcessor::new);

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_WIFI)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new WifiPowerStatsProcessor(powerProfile));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_BLUETOOTH)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new BluetoothPowerStatsProcessor(powerProfile));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_AUDIO)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new AudioPowerStatsProcessor(powerProfile));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_VIDEO)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new VideoPowerStatsProcessor(powerProfile));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_FLASHLIGHT)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new FlashlightPowerStatsProcessor(powerProfile));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_CAMERA)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new CameraPowerStatsProcessor(powerProfile));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_GNSS)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(
                        () -> new GnssPowerStatsProcessor(powerProfile));

        config.trackPowerComponent(BatteryConsumer.POWER_COMPONENT_SENSORS)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE)
                .setProcessorSupplier(() -> new SensorPowerStatsProcessor(
                        () -> context.getSystemService(SensorManager.class)));

        config.trackCustomPowerComponents(CustomEnergyConsumerPowerStatsProcessor::new)
                .trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE);
        return config;
    }

    /**
     * Marks the specified power component as supported by this PowerAttributor
     */
    public void setPowerComponentSupported(@BatteryConsumer.PowerComponentId int powerComponentId,
            boolean enabled) {
        mPowerStatsExporterEnabled.put(powerComponentId, enabled);
        mPowerStatsExporter.setPowerComponentEnabled(powerComponentId, enabled);
    }

    @Override
    public boolean isPowerComponentSupported(
            @BatteryConsumer.PowerComponentId int powerComponentId) {
        return mPowerStatsExporterEnabled.get(powerComponentId);
    }

    @Override
    public void estimatePowerConsumption(BatteryUsageStats.Builder batteryUsageStatsBuilder,
            BatteryStatsHistory batteryHistory, long monotonicStartTime, long monotonicEndTime) {
        mPowerStatsExporter.exportAggregatedPowerStats(batteryUsageStatsBuilder, batteryHistory,
                monotonicStartTime, monotonicEndTime);
    }

    @Override
    public void dumpEstimatedPowerConsumption(IndentingPrintWriter ipw,
            BatteryStatsHistory batteryStatsHistory,
            long startTime, long endTime) {
        mPowerStatsAggregator.aggregatePowerStats(batteryStatsHistory, startTime, endTime,
                stats -> {
                    // Create a PowerStatsSpan for consistency of the textual output
                    PowerStatsSpan span = createPowerStatsSpan(stats);
                    if (span != null) {
                        span.dump(ipw);
                    }
                });
    }

    @Override
    public long storeEstimatedPowerConsumption(BatteryStatsHistory batteryStatsHistory,
            long startTime, long endTimeMs) {
        long[] lastSavedMonotonicTime = new long[1];
        mPowerStatsAggregator.aggregatePowerStats(batteryStatsHistory, startTime, endTimeMs,
                stats -> {
                    storeAggregatedPowerStats(stats);
                    lastSavedMonotonicTime[0] = stats.getStartTime() + stats.getDuration();
                });
        return lastSavedMonotonicTime[0];
    }

    @VisibleForTesting
    void storeAggregatedPowerStats(AggregatedPowerStats stats) {
        PowerStatsSpan span = createPowerStatsSpan(stats);
        if (span == null) {
            return;
        }
        mPowerStatsStore.storePowerStatsSpan(span);
    }

    private static PowerStatsSpan createPowerStatsSpan(AggregatedPowerStats stats) {
        List<AggregatedPowerStats.ClockUpdate> clockUpdates = stats.getClockUpdates();
        if (clockUpdates.isEmpty()) {
            Slog.w(TAG, "No clock updates in aggregated power stats " + stats);
            return null;
        }

        long monotonicTime = clockUpdates.get(0).monotonicTime;
        long durationSum = 0;
        PowerStatsSpan span = new PowerStatsSpan(monotonicTime);
        for (int i = 0; i < clockUpdates.size(); i++) {
            AggregatedPowerStats.ClockUpdate clockUpdate = clockUpdates.get(i);
            long duration;
            if (i == clockUpdates.size() - 1) {
                duration = stats.getDuration() - durationSum;
            } else {
                duration = clockUpdate.monotonicTime - monotonicTime;
            }
            span.addTimeFrame(clockUpdate.monotonicTime, clockUpdate.currentTime, duration);
            monotonicTime = clockUpdate.monotonicTime;
            durationSum += duration;
        }

        span.addSection(new AggregatedPowerStatsSection(stats));
        return span;
    }

    @Override
    public long getLastSavedEstimatesPowerConsumptionTimestamp() {
        long timestamp = -1;
        for (PowerStatsSpan.Metadata metadata : mPowerStatsStore.getTableOfContents()) {
            if (metadata.getSections().contains(AggregatedPowerStatsSection.TYPE)) {
                for (PowerStatsSpan.TimeFrame timeFrame : metadata.getTimeFrames()) {
                    long endMonotonicTime = timeFrame.startMonotonicTime + timeFrame.duration;
                    if (endMonotonicTime > timestamp) {
                        timestamp = endMonotonicTime;
                    }
                }
            }
        }
        return timestamp;
    }
}
