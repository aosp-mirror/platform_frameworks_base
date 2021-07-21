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

package com.android.frameworks.core.batterystatsloadtests;

import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.os.UidBatteryConsumer;
import android.util.DebugUtils;
import android.util.Range;

import java.util.ArrayList;
import java.util.List;

public class PowerMetrics {

    enum MetricKind {
        POWER,
        DURATION,
    }

    public static class Metric {
        public String metricName;
        public MetricKind metricKind;
        public String statusKeyPrefix;
        public double value;
        public double total;
    }

    private final double mDrainedPower;

    private List<Metric> mMetrics = new ArrayList<>();

    public PowerMetrics(BatteryUsageStats batteryUsageStats, int uid) {
        final Range<Double> dischargedPowerRange = batteryUsageStats.getDischargedPowerRange();
        mDrainedPower = (dischargedPowerRange.getLower() + dischargedPowerRange.getUpper()) / 2;
        double[] totalPowerPerComponentMah = new double[BatteryConsumer.POWER_COMPONENT_COUNT];
        long[] totalDurationPerComponentMs = new long[BatteryConsumer.POWER_COMPONENT_COUNT];

        UidBatteryConsumer selectedBatteryConsumer = null;
        for (UidBatteryConsumer uidBatteryConsumer : batteryUsageStats.getUidBatteryConsumers()) {
            if (uidBatteryConsumer.getUid() == uid) {
                selectedBatteryConsumer = uidBatteryConsumer;
            }

            for (int component = 0; component < BatteryConsumer.POWER_COMPONENT_COUNT;
                    component++) {
                totalPowerPerComponentMah[component] +=
                        uidBatteryConsumer.getConsumedPower(component);
                totalDurationPerComponentMs[component] +=
                        uidBatteryConsumer.getUsageDurationMillis(component);
            }
        }

        if (selectedBatteryConsumer == null) {
            return;
        }

        for (int component = 0; component < BatteryConsumer.POWER_COMPONENT_COUNT; component++) {
            addMetric(getPowerMetricName(component), MetricKind.POWER,
                    selectedBatteryConsumer.getConsumedPower(component),
                    totalPowerPerComponentMah[component]);
            addMetric(getDurationMetricName(component), MetricKind.DURATION,
                    selectedBatteryConsumer.getUsageDurationMillis(component),
                    totalDurationPerComponentMs[component]);
        }
    }

    static String getDurationMetricName(int componentId) {
        return "DURATION_" + DebugUtils.constantToString(BatteryConsumer.class,
                "POWER_COMPONENT_", componentId);
    }

    static String getPowerMetricName(int componentId) {
        return "POWER_" + DebugUtils.constantToString(BatteryConsumer.class,
                "POWER_COMPONENT_", componentId);
    }

    public List<Metric> getMetrics() {
        return mMetrics;
    }

    public double getDrainedPower() {
        return mDrainedPower;
    }

    private void addMetric(String metricType, MetricKind metricKind, double amount,
            double totalAmount) {
        Metric metric = new Metric();
        metric.metricName = metricType;
        metric.metricKind = metricKind;
        metric.statusKeyPrefix = metricKind.toString().toLowerCase();
        metric.value = amount;
        metric.total = totalAmount;
        mMetrics.add(metric);
    }
}
