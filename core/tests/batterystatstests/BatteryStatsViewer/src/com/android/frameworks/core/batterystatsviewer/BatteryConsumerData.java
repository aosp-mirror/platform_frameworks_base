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

package com.android.frameworks.core.batterystatsviewer;

import android.content.Context;
import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.util.DebugUtils;

import java.util.ArrayList;
import java.util.List;

public class BatteryConsumerData {

    enum EntryType {
        POWER_MODELED,
        POWER_MEASURED,
        POWER_CUSTOM,
        DURATION,
    }

    public static class Entry {
        public String title;
        public EntryType entryType;
        public double value;
        public double total;
        public boolean isSystemBatteryConsumer;
    }

    private final BatteryConsumerInfoHelper.BatteryConsumerInfo mBatteryConsumerInfo;
    private final List<Entry> mEntries = new ArrayList<>();

    public BatteryConsumerData(Context context,
            List<BatteryUsageStats> batteryUsageStatsList, String batteryConsumerId) {
        BatteryUsageStats batteryUsageStats = batteryUsageStatsList.get(0);
        BatteryUsageStats modeledBatteryUsageStats = batteryUsageStatsList.get(1);

        BatteryConsumer requestedBatteryConsumer = getRequestedBatteryConsumer(batteryUsageStats,
                batteryConsumerId);
        BatteryConsumer requestedModeledBatteryConsumer = getRequestedBatteryConsumer(
                modeledBatteryUsageStats, batteryConsumerId);

        if (requestedBatteryConsumer == null || requestedModeledBatteryConsumer == null) {
            mBatteryConsumerInfo = null;
            return;
        }

        mBatteryConsumerInfo = BatteryConsumerInfoHelper.makeBatteryConsumerInfo(
                requestedBatteryConsumer, batteryConsumerId, context.getPackageManager());

        double[] totalPowerByComponentMah = new double[BatteryConsumer.POWER_COMPONENT_COUNT];
        double[] totalModeledPowerByComponentMah =
                new double[BatteryConsumer.POWER_COMPONENT_COUNT];
        long[] totalDurationByComponentMs = new long[BatteryConsumer.POWER_COMPONENT_COUNT];
        final int customComponentCount =
                requestedBatteryConsumer.getCustomPowerComponentCount();
        double[] totalCustomPowerByComponentMah = new double[customComponentCount];

        computeTotalPower(batteryUsageStats, totalPowerByComponentMah);
        computeTotalPower(modeledBatteryUsageStats, totalModeledPowerByComponentMah);
        computeTotalPowerForCustomComponent(batteryUsageStats, totalCustomPowerByComponentMah);
        computeTotalDuration(batteryUsageStats, totalDurationByComponentMs);

        for (int component = 0; component < BatteryConsumer.POWER_COMPONENT_COUNT; component++) {
            final String metricTitle = getPowerMetricTitle(component);
            final int powerModel = requestedBatteryConsumer.getPowerModel(component);
            if (powerModel == BatteryConsumer.POWER_MODEL_POWER_PROFILE) {
                addEntry(metricTitle, EntryType.POWER_MODELED,
                        requestedBatteryConsumer.getConsumedPower(component),
                        totalPowerByComponentMah[component],
                        mBatteryConsumerInfo.isSystemBatteryConsumer);
            } else {
                addEntry(metricTitle + " (measured)", EntryType.POWER_MEASURED,
                        requestedBatteryConsumer.getConsumedPower(component),
                        totalPowerByComponentMah[component],
                        mBatteryConsumerInfo.isSystemBatteryConsumer);
                addEntry(metricTitle + " (modeled)", EntryType.POWER_MODELED,
                        requestedModeledBatteryConsumer.getConsumedPower(component),
                        totalModeledPowerByComponentMah[component],
                        mBatteryConsumerInfo.isSystemBatteryConsumer);
            }
        }

        for (int component = 0; component < customComponentCount; component++) {
            final String name = requestedBatteryConsumer.getCustomPowerComponentName(
                    BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + component);
            addEntry(name + " (custom)", EntryType.POWER_CUSTOM,
                    requestedBatteryConsumer.getConsumedPowerForCustomComponent(
                            BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + component),
                    totalCustomPowerByComponentMah[component],
                    mBatteryConsumerInfo.isSystemBatteryConsumer);
        }

        for (int component = 0; component < BatteryConsumer.POWER_COMPONENT_COUNT; component++) {
            final String metricTitle = getTimeMetricTitle(component);
            addEntry(metricTitle, EntryType.DURATION,
                    requestedBatteryConsumer.getUsageDurationMillis(component),
                    totalDurationByComponentMs[component],
                    mBatteryConsumerInfo.isSystemBatteryConsumer);
        }
    }

    private BatteryConsumer getRequestedBatteryConsumer(BatteryUsageStats batteryUsageStats,
            String batteryConsumerId) {
        for (int scope = 0;
                scope < BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_COUNT;
                scope++) {
            if (batteryConsumerId(scope).equals(batteryConsumerId)) {
                return batteryUsageStats.getAggregateBatteryConsumer(scope);
            }
        }

        for (BatteryConsumer consumer : batteryUsageStats.getUidBatteryConsumers()) {
            if (batteryConsumerId(consumer).equals(batteryConsumerId)) {
                return consumer;
            }
        }

        return null;
    }

    static String getPowerMetricTitle(int componentId) {
        final String componentName = DebugUtils.constantToString(BatteryConsumer.class,
                "POWER_COMPONENT_", componentId);
        return componentName.charAt(0) + componentName.substring(1).toLowerCase().replace('_', ' ')
                + " power";
    }

    static String getTimeMetricTitle(int componentId) {
        final String componentName = DebugUtils.constantToString(BatteryConsumer.class,
                "POWER_COMPONENT_", componentId);
        return componentName.charAt(0) + componentName.substring(1).toLowerCase().replace('_', ' ')
                + " time";
    }

    private void computeTotalPower(BatteryUsageStats batteryUsageStats,
            double[] powerByComponentMah) {
        final BatteryConsumer consumer =
                batteryUsageStats.getAggregateBatteryConsumer(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        for (int component = 0; component < BatteryConsumer.POWER_COMPONENT_COUNT; component++) {
            powerByComponentMah[component] += consumer.getConsumedPower(component);
        }
    }

    private void computeTotalPowerForCustomComponent(
            BatteryUsageStats batteryUsageStats, double[] powerByComponentMah) {
        final BatteryConsumer consumer =
                batteryUsageStats.getAggregateBatteryConsumer(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        final int customComponentCount = consumer.getCustomPowerComponentCount();
        for (int component = 0;
                component < Math.min(customComponentCount, powerByComponentMah.length);
                component++) {
            powerByComponentMah[component] += consumer.getConsumedPowerForCustomComponent(
                    BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + component);
        }
    }

    private void computeTotalDuration(BatteryUsageStats batteryUsageStats,
            long[] durationByComponentMs) {
        for (BatteryConsumer consumer : batteryUsageStats.getUidBatteryConsumers()) {
            for (int component = 0; component < BatteryConsumer.POWER_COMPONENT_COUNT;
                    component++) {
                durationByComponentMs[component] += consumer.getUsageDurationMillis(component);
            }
        }
    }

    private void addEntry(String title, EntryType entryType, double amount, double totalAmount,
            boolean isSystemBatteryConsumer) {
        Entry entry = new Entry();
        entry.title = title;
        entry.entryType = entryType;
        entry.value = amount;
        entry.total = totalAmount;
        entry.isSystemBatteryConsumer = isSystemBatteryConsumer;
        mEntries.add(entry);
    }

    public BatteryConsumerInfoHelper.BatteryConsumerInfo getBatteryConsumerInfo() {
        return mBatteryConsumerInfo;
    }

    public List<Entry> getEntries() {
        return mEntries;
    }

    public static String batteryConsumerId(BatteryConsumer consumer) {
        if (consumer instanceof UidBatteryConsumer) {
            return "APP|"
                    + UserHandle.getUserId(((UidBatteryConsumer) consumer).getUid()) + "|"
                    + ((UidBatteryConsumer) consumer).getUid();
        } else {
            return "";
        }
    }

    public static String batteryConsumerId(
            @BatteryUsageStats.AggregateBatteryConsumerScope int scope) {
        return "SYS|" + scope;
    }
}