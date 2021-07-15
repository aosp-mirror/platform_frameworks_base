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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryUsageStats;
import android.os.Process;
import android.os.UidBatteryConsumer;

import androidx.annotation.NonNull;

import java.util.List;

class BatteryConsumerInfoHelper {

    private static final String SYSTEM_SERVER_PACKAGE_NAME = "android";

    public static class BatteryConsumerInfo {
        public String id;
        public CharSequence label;
        public double powerMah;
        public ApplicationInfo iconInfo;
        public CharSequence packages;
        public CharSequence details;
        public BatteryConsumerData.ConsumerType consumerType;
    }

    @NonNull
    public static BatteryConsumerInfo makeBatteryConsumerInfo(
            @NonNull BatteryUsageStats batteryUsageStats, String batteryConsumerId,
            PackageManager packageManager) {
        BatteryConsumerData.ConsumerType consumerType = BatteryConsumerData.getConsumerType(
                batteryConsumerId);
        switch (consumerType) {
            case UID_BATTERY_CONSUMER:
                final List<UidBatteryConsumer> consumers =
                        batteryUsageStats.getUidBatteryConsumers();
                for (UidBatteryConsumer consumer : consumers) {
                    if (BatteryConsumerData.batteryConsumerId(consumer).equals(batteryConsumerId)) {
                        return makeBatteryConsumerInfo(consumer, packageManager);
                    }
                }
                break;
            case DEVICE_POWER_COMPONENT:
                return makeAggregateBatteryConsumerInfo(batteryUsageStats);
        }

        BatteryConsumerInfo info = new BatteryConsumerInfo();
        info.id = batteryConsumerId;
        return info;
    }

    private static BatteryConsumerInfo makeBatteryConsumerInfo(
            UidBatteryConsumer uidBatteryConsumer, PackageManager packageManager) {
        BatteryConsumerInfo info = new BatteryConsumerInfo();
        info.consumerType = BatteryConsumerData.ConsumerType.UID_BATTERY_CONSUMER;
        info.id = BatteryConsumerData.batteryConsumerId(uidBatteryConsumer);
        info.powerMah = uidBatteryConsumer.getConsumedPower();
        int uid = uidBatteryConsumer.getUid();
        info.details = String.format("UID: %d", uid);
        String packageWithHighestDrain = uidBatteryConsumer.getPackageWithHighestDrain();
        if (uid == Process.ROOT_UID) {
            info.label = "<root>";
        } else {
            String[] packages = packageManager.getPackagesForUid(uid);
            String primaryPackageName = null;
            if (uid == Process.SYSTEM_UID) {
                primaryPackageName = SYSTEM_SERVER_PACKAGE_NAME;
            } else if (packages != null) {
                for (String name : packages) {
                    primaryPackageName = name;
                    if (name.equals(packageWithHighestDrain)) {
                        break;
                    }
                }
            }

            if (primaryPackageName != null) {
                try {
                    ApplicationInfo applicationInfo =
                            packageManager.getApplicationInfo(primaryPackageName, 0);
                    info.label = applicationInfo.loadLabel(packageManager);
                    info.iconInfo = applicationInfo;
                } catch (PackageManager.NameNotFoundException e) {
                    info.label = primaryPackageName;
                }
            } else if (packageWithHighestDrain != null) {
                info.label = packageWithHighestDrain;
            }

            if (packages != null && packages.length > 0) {
                StringBuilder sb = new StringBuilder();
                if (primaryPackageName != null) {
                    sb.append(primaryPackageName);
                }
                for (String packageName : packages) {
                    if (packageName.equals(primaryPackageName)) {
                        continue;
                    }

                    if (sb.length() != 0) {
                        sb.append(", ");
                    }
                    sb.append(packageName);
                }

                info.packages = sb;
            }
        }
        // Default the app icon to System Server. This includes root, dex2oat and other UIDs.
        if (info.iconInfo == null) {
            try {
                info.iconInfo =
                        packageManager.getApplicationInfo(SYSTEM_SERVER_PACKAGE_NAME, 0);
            } catch (PackageManager.NameNotFoundException nameNotFoundException) {
                // Won't happen
            }
        }
        return info;
    }

    private static BatteryConsumerInfo makeAggregateBatteryConsumerInfo(
            BatteryUsageStats batteryUsageStats) {
        BatteryConsumerInfo info = new BatteryConsumerInfo();
        info.consumerType = BatteryConsumerData.ConsumerType.DEVICE_POWER_COMPONENT;
        info.id = BatteryConsumerData.AGGREGATE_BATTERY_CONSUMER_ID;
        info.powerMah = batteryUsageStats.getConsumedPower();
        info.label = "Device";
        return info;
    }
}
