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

    public static final String UID_BATTERY_CONSUMER_ID_PREFIX = "APP|";
    public static final String AGGREGATE_BATTERY_CONSUMER_ID = "SYS|";

    enum EntryType {
        UID_TOTAL_POWER,
        UID_POWER_MODELED,
        UID_POWER_MEASURED,
        UID_POWER_CUSTOM,
        UID_DURATION,
        DEVICE_TOTAL_POWER,
        DEVICE_POWER_MODELED,
        DEVICE_POWER_MEASURED,
        DEVICE_POWER_CUSTOM,
        DEVICE_DURATION,
    }

    enum ConsumerType {
        UID_BATTERY_CONSUMER,
        DEVICE_POWER_COMPONENT,
    }

    public static class Entry {
        public EntryType entryType;
        public String title;
        public double value1;
        public double value2;
    }

    private BatteryConsumerInfoHelper.BatteryConsumerInfo mBatteryConsumerInfo;
    private final List<Entry> mEntries = new ArrayList<>();

    public BatteryConsumerData(Context context,
            List<BatteryUsageStats> batteryUsageStatsList, String batteryConsumerId) {
        switch (getConsumerType(batteryConsumerId)) {
            case UID_BATTERY_CONSUMER:
                populateForUidBatteryConsumer(context, batteryUsageStatsList, batteryConsumerId);
                break;
            case DEVICE_POWER_COMPONENT:
                populateForAggregateBatteryConsumer(context, batteryUsageStatsList);
                break;
        }
    }

    private void populateForUidBatteryConsumer(
            Context context, List<BatteryUsageStats> batteryUsageStatsList,
            String batteryConsumerId) {
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
                batteryUsageStats, batteryConsumerId, context.getPackageManager());

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

        if (isPowerProfileModelsOnly(requestedBatteryConsumer)) {
            addEntry("Consumed", EntryType.UID_TOTAL_POWER,
                    requestedBatteryConsumer.getConsumedPower(),
                    batteryUsageStats.getAggregateBatteryConsumer(
                            BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                            .getConsumedPower());
        } else {
            addEntry("Consumed (measured)", EntryType.UID_TOTAL_POWER,
                    requestedBatteryConsumer.getConsumedPower(),
                    batteryUsageStats.getAggregateBatteryConsumer(
                            BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                            .getConsumedPower());
            addEntry("Consumed (modeled)", EntryType.UID_TOTAL_POWER,
                    requestedModeledBatteryConsumer.getConsumedPower(),
                    modeledBatteryUsageStats.getAggregateBatteryConsumer(
                            BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS)
                            .getConsumedPower());
        }

        for (int component = 0; component < BatteryConsumer.POWER_COMPONENT_COUNT; component++) {
            final String metricTitle = getPowerMetricTitle(component);
            final int powerModel = requestedBatteryConsumer.getPowerModel(component);
            if (powerModel == BatteryConsumer.POWER_MODEL_POWER_PROFILE
                    || powerModel == BatteryConsumer.POWER_MODEL_UNDEFINED) {
                addEntry(metricTitle, EntryType.UID_POWER_MODELED,
                        requestedBatteryConsumer.getConsumedPower(component),
                        totalPowerByComponentMah[component]
                );
            } else {
                addEntry(metricTitle + " (measured)", EntryType.UID_POWER_MEASURED,
                        requestedBatteryConsumer.getConsumedPower(component),
                        totalPowerByComponentMah[component]
                );
                addEntry(metricTitle + " (modeled)", EntryType.UID_POWER_MODELED,
                        requestedModeledBatteryConsumer.getConsumedPower(component),
                        totalModeledPowerByComponentMah[component]
                );
            }
        }

        for (int component = 0; component < customComponentCount; component++) {
            final String name = requestedBatteryConsumer.getCustomPowerComponentName(
                    BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + component);
            addEntry(name + " (custom)", EntryType.UID_POWER_CUSTOM,
                    requestedBatteryConsumer.getConsumedPowerForCustomComponent(
                            BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + component),
                    totalCustomPowerByComponentMah[component]
            );
        }

        for (int component = 0; component < BatteryConsumer.POWER_COMPONENT_COUNT; component++) {
            final String metricTitle = getTimeMetricTitle(component);
            addEntry(metricTitle, EntryType.UID_DURATION,
                    requestedBatteryConsumer.getUsageDurationMillis(component),
                    totalDurationByComponentMs[component]
            );
        }

        mBatteryConsumerInfo = BatteryConsumerInfoHelper.makeBatteryConsumerInfo(batteryUsageStats,
                batteryConsumerId, context.getPackageManager());
    }

    private void populateForAggregateBatteryConsumer(Context context,
            List<BatteryUsageStats> batteryUsageStatsList) {
        BatteryUsageStats batteryUsageStats = batteryUsageStatsList.get(0);
        BatteryUsageStats modeledBatteryUsageStats = batteryUsageStatsList.get(1);

        final BatteryConsumer deviceBatteryConsumer =
                batteryUsageStats.getAggregateBatteryConsumer(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        BatteryConsumer appsBatteryConsumer =
                batteryUsageStats.getAggregateBatteryConsumer(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);

        BatteryConsumer modeledDeviceBatteryConsumer =
                modeledBatteryUsageStats.getAggregateBatteryConsumer(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        BatteryConsumer modeledAppsBatteryConsumer =
                modeledBatteryUsageStats.getAggregateBatteryConsumer(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);

        if (isPowerProfileModelsOnly(deviceBatteryConsumer)) {
            addEntry("Consumed", EntryType.DEVICE_TOTAL_POWER,
                    deviceBatteryConsumer.getConsumedPower(),
                    appsBatteryConsumer.getConsumedPower());
        } else {
            addEntry("Consumed (measured)", EntryType.DEVICE_TOTAL_POWER,
                    deviceBatteryConsumer.getConsumedPower(),
                    appsBatteryConsumer.getConsumedPower());
            addEntry("Consumed (modeled)", EntryType.DEVICE_TOTAL_POWER,
                    modeledDeviceBatteryConsumer.getConsumedPower(),
                    modeledAppsBatteryConsumer.getConsumedPower());
        }

        mBatteryConsumerInfo = BatteryConsumerInfoHelper.makeBatteryConsumerInfo(batteryUsageStats,
                AGGREGATE_BATTERY_CONSUMER_ID, context.getPackageManager());


        for (int component = 0; component < BatteryConsumer.POWER_COMPONENT_COUNT; component++) {
            final String metricTitle = getPowerMetricTitle(component);
            final int powerModel = deviceBatteryConsumer.getPowerModel(component);
            if (powerModel == BatteryConsumer.POWER_MODEL_POWER_PROFILE
                    || powerModel == BatteryConsumer.POWER_MODEL_UNDEFINED) {
                addEntry(metricTitle, EntryType.DEVICE_POWER_MODELED,
                        deviceBatteryConsumer.getConsumedPower(component),
                        appsBatteryConsumer.getConsumedPower(component));
            } else {
                addEntry(metricTitle + " (measured)", EntryType.DEVICE_POWER_MEASURED,
                        deviceBatteryConsumer.getConsumedPower(component),
                        appsBatteryConsumer.getConsumedPower(component));
                addEntry(metricTitle + " (modeled)", EntryType.DEVICE_POWER_MODELED,
                        modeledDeviceBatteryConsumer.getConsumedPower(component),
                        modeledAppsBatteryConsumer.getConsumedPower(component));
            }
        }

        final int customComponentCount =
                deviceBatteryConsumer.getCustomPowerComponentCount();
        for (int component = 0; component < customComponentCount; component++) {
            final String name = deviceBatteryConsumer.getCustomPowerComponentName(
                    BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + component);
            addEntry(name + " (custom)", EntryType.DEVICE_POWER_CUSTOM,
                    deviceBatteryConsumer.getConsumedPowerForCustomComponent(
                            BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + component),
                    appsBatteryConsumer.getConsumedPowerForCustomComponent(
                            BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID + component));
        }

        for (int component = 0; component < BatteryConsumer.POWER_COMPONENT_COUNT; component++) {
            final String metricTitle = getTimeMetricTitle(component);
            addEntry(metricTitle, EntryType.DEVICE_DURATION,
                    deviceBatteryConsumer.getUsageDurationMillis(component), 0);
        }
    }

    private boolean isPowerProfileModelsOnly(BatteryConsumer batteryConsumer) {
        for (int component = 0; component < BatteryConsumer.POWER_COMPONENT_COUNT; component++) {
            final int powerModel = batteryConsumer.getPowerModel(component);
            if (powerModel != BatteryConsumer.POWER_MODEL_POWER_PROFILE
                    && powerModel != BatteryConsumer.POWER_MODEL_UNDEFINED) {
                return false;
            }
        }
        return true;
    }

    private BatteryConsumer getRequestedBatteryConsumer(BatteryUsageStats batteryUsageStats,
            String batteryConsumerId) {
        for (UidBatteryConsumer consumer : batteryUsageStats.getUidBatteryConsumers()) {
            if (batteryConsumerId(consumer).equals(batteryConsumerId)) {
                return consumer;
            }
        }

        return null;
    }

    static String getPowerMetricTitle(int componentId) {
        return getPowerComponentName(componentId);
    }

    static String getTimeMetricTitle(int componentId) {
        return getPowerComponentName(componentId) + " time";
    }

    private static String getPowerComponentName(int componentId) {
        switch (componentId) {
            case BatteryConsumer.POWER_COMPONENT_CPU:
                return "CPU";
            case BatteryConsumer.POWER_COMPONENT_GNSS:
                return "GNSS";
            case BatteryConsumer.POWER_COMPONENT_WIFI:
                return "Wi-Fi";
            default:
                String componentName = DebugUtils.constantToString(BatteryConsumer.class,
                        "POWER_COMPONENT_", componentId);
                return componentName.charAt(0) + componentName.substring(1).toLowerCase()
                        .replace('_', ' ');
        }
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

    private void addEntry(String title, EntryType entryType, double value1, double value2) {
        Entry entry = new Entry();
        entry.title = title;
        entry.entryType = entryType;
        entry.value1 = value1;
        entry.value2 = value2;
        mEntries.add(entry);
    }

    public BatteryConsumerInfoHelper.BatteryConsumerInfo getBatteryConsumerInfo() {
        return mBatteryConsumerInfo;
    }

    public List<Entry> getEntries() {
        return mEntries;
    }

    public static ConsumerType getConsumerType(String batteryConsumerId) {
        if (batteryConsumerId.startsWith(UID_BATTERY_CONSUMER_ID_PREFIX)) {
            return ConsumerType.UID_BATTERY_CONSUMER;
        }
        return ConsumerType.DEVICE_POWER_COMPONENT;
    }

    public static String batteryConsumerId(UidBatteryConsumer consumer) {
        return UID_BATTERY_CONSUMER_ID_PREFIX + UserHandle.getUserId(consumer.getUid()) + "|"
                + consumer.getUid();
    }
}