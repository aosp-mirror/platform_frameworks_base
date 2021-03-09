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
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.Process;
import android.os.SystemBatteryConsumer;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;

import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;

import java.util.ArrayList;
import java.util.List;

public class BatteryConsumerData {
    private static final String PACKAGE_CALENDAR_PROVIDER = "com.android.providers.calendar";
    private static final String PACKAGE_MEDIA_PROVIDER = "com.android.providers.media";
    private static final String PACKAGE_SYSTEMUI = "com.android.systemui";
    private static final String[] PACKAGES_SYSTEM = {PACKAGE_MEDIA_PROVIDER,
            PACKAGE_CALENDAR_PROVIDER, PACKAGE_SYSTEMUI};

    // Unit conversion:
    //   mAh = uC * (1/1000)(milli/micro) * (1/3600)(hours/second)
    private static final double UC_2_MAH = (1.0 / 1000)  * (1.0 / 3600);

    enum EntryType {
        POWER,
        DURATION,
    }

    public static class Entry {
        public String title;
        public EntryType entryType;
        public double value;
        public double total;
    }

    private final BatteryConsumerInfoHelper.BatteryConsumerInfo mBatteryConsumerInfo;
    private final List<Entry> mEntries = new ArrayList<>();

    public BatteryConsumerData(Context context, BatteryStatsHelper batteryStatsHelper,
            List<BatteryUsageStats> batteryUsageStatsList, String batteryConsumerId) {
        BatteryUsageStats batteryUsageStats = batteryUsageStatsList.get(0);
        BatteryUsageStats powerProfileModeledUsageStats = batteryUsageStatsList.get(1);
        List<BatterySipper> usageList = batteryStatsHelper.getUsageList();
        BatteryStats batteryStats = batteryStatsHelper.getStats();

        double totalPowerMah = 0;
        double totalSmearedPowerMah = 0;
        double totalPowerExcludeSystemMah = 0;
        double totalScreenPower = 0;
        double totalProportionalSmearMah = 0;
        double totalCpuPowerMah = 0;
        double totalSystemServiceCpuPowerMah = 0;
        double totalUsagePowerMah = 0;
        double totalWakeLockPowerMah = 0;
        double totalMobileRadioPowerMah = 0;
        double totalWifiPowerMah = 0;
        double totalBluetoothPowerMah = 0;
        double totalGpsPowerMah = 0;
        double totalCameraPowerMah = 0;
        double totalFlashlightPowerMah = 0;
        double totalSensorPowerMah = 0;
        double totalAudioPowerMah = 0;
        double totalVideoPowerMah = 0;

        long totalCpuTimeMs = 0;
        long totalCpuFgTimeMs = 0;
        long totalWakeLockTimeMs = 0;
        long totalWifiRunningTimeMs = 0;
        long totalBluetoothRunningTimeMs = 0;
        long totalGpsTimeMs = 0;
        long totalCameraTimeMs = 0;
        long totalFlashlightTimeMs = 0;
        long totalAudioTimeMs = 0;
        long totalVideoTimeMs = 0;

        BatterySipper requestedBatterySipper = null;
        for (BatterySipper sipper : usageList) {
            if (sipper.drainType == BatterySipper.DrainType.SCREEN) {
                totalScreenPower = sipper.sumPower();
            }

            if (batteryConsumerId(sipper).equals(batteryConsumerId)) {
                requestedBatterySipper = sipper;
            }

            totalPowerMah += sipper.sumPower();
            totalSmearedPowerMah += sipper.totalSmearedPowerMah;
            totalProportionalSmearMah += sipper.proportionalSmearMah;

            if (!isSystemSipper(sipper)) {
                totalPowerExcludeSystemMah += sipper.totalSmearedPowerMah;
            }

            totalCpuPowerMah += sipper.cpuPowerMah;
            totalSystemServiceCpuPowerMah += sipper.systemServiceCpuPowerMah;
            totalUsagePowerMah += sipper.usagePowerMah;
            totalWakeLockPowerMah += sipper.wakeLockPowerMah;
            totalMobileRadioPowerMah += sipper.mobileRadioPowerMah;
            totalWifiPowerMah += sipper.wifiPowerMah;
            totalBluetoothPowerMah += sipper.bluetoothPowerMah;
            totalGpsPowerMah += sipper.gpsPowerMah;
            totalCameraPowerMah += sipper.cameraPowerMah;
            totalFlashlightPowerMah += sipper.flashlightPowerMah;
            totalSensorPowerMah += sipper.sensorPowerMah;
            totalAudioPowerMah += sipper.audioPowerMah;
            totalVideoPowerMah += sipper.videoPowerMah;

            totalCpuTimeMs += sipper.cpuTimeMs;
            totalCpuFgTimeMs += sipper.cpuFgTimeMs;
            totalWakeLockTimeMs += sipper.wakeLockTimeMs;
            totalWifiRunningTimeMs += sipper.wifiRunningTimeMs;
            totalBluetoothRunningTimeMs += sipper.bluetoothRunningTimeMs;
            totalGpsTimeMs += sipper.gpsTimeMs;
            totalCameraTimeMs += sipper.cameraTimeMs;
            totalFlashlightTimeMs += sipper.flashlightTimeMs;
            totalAudioTimeMs += sipper.audioTimeMs;
            totalVideoTimeMs += sipper.videoTimeMs;
        }

        BatteryConsumer requestedBatteryConsumer = null;

        for (BatteryConsumer consumer : batteryUsageStats.getUidBatteryConsumers()) {
            if (batteryConsumerId(consumer).equals(batteryConsumerId)) {
                requestedBatteryConsumer = consumer;
            }
        }

        double totalModeledCpuPowerMah = 0;
        BatteryConsumer requestedBatteryConsumerPowerProfileModeled = null;
        for (BatteryConsumer consumer : powerProfileModeledUsageStats.getUidBatteryConsumers()) {
            if (batteryConsumerId(consumer).equals(batteryConsumerId)) {
                requestedBatteryConsumerPowerProfileModeled = consumer;
            }

            totalModeledCpuPowerMah += consumer.getConsumedPower(
                    BatteryConsumer.POWER_COMPONENT_CPU);
        }

        if (requestedBatterySipper == null) {
            mBatteryConsumerInfo = null;
            return;
        }

        if (requestedBatteryConsumer == null) {
            for (BatteryConsumer consumer : batteryUsageStats.getSystemBatteryConsumers()) {
                if (batteryConsumerId(consumer).equals(batteryConsumerId)) {
                    requestedBatteryConsumer = consumer;
                    break;
                }
            }
        }

        mBatteryConsumerInfo = BatteryConsumerInfoHelper.makeBatteryConsumerInfo(
                context.getPackageManager(), requestedBatterySipper);
        long totalScreenMeasuredChargeUC =
                batteryStats.getScreenOnMeasuredBatteryConsumptionUC();
        long uidScreenMeasuredChargeUC =
                requestedBatterySipper.uidObj.getScreenOnMeasuredBatteryConsumptionUC();

        addEntry("Total power", EntryType.POWER,
                requestedBatterySipper.totalSmearedPowerMah, totalSmearedPowerMah);
        maybeAddMeasuredEnergyEntry(requestedBatterySipper.drainType, batteryStats);

        addEntry("... excluding system", EntryType.POWER,
                requestedBatterySipper.totalSmearedPowerMah, totalPowerExcludeSystemMah);
        addEntry("Screen, smeared", EntryType.POWER,
                requestedBatterySipper.screenPowerMah, totalScreenPower);
        if (uidScreenMeasuredChargeUC != BatteryStats.POWER_DATA_UNAVAILABLE
                && totalScreenMeasuredChargeUC != BatteryStats.POWER_DATA_UNAVAILABLE) {
            final double measuredCharge = UC_2_MAH * uidScreenMeasuredChargeUC;
            final double totalMeasuredCharge = UC_2_MAH * totalScreenMeasuredChargeUC;
            addEntry("Screen, measured", EntryType.POWER,
                    measuredCharge, totalMeasuredCharge);
        }
        addEntry("Other, smeared", EntryType.POWER,
                requestedBatterySipper.proportionalSmearMah, totalProportionalSmearMah);
        addEntry("Excluding smeared", EntryType.POWER,
                requestedBatterySipper.totalPowerMah, totalPowerMah);
        if (requestedBatteryConsumer != null) {
            addEntry("CPU", EntryType.POWER,
                    requestedBatteryConsumer.getConsumedPower(BatteryConsumer.POWER_COMPONENT_CPU),
                    totalCpuPowerMah);
            if (requestedBatteryConsumerPowerProfileModeled != null) {
                addEntry("CPU (modeled)", EntryType.POWER,
                        requestedBatteryConsumerPowerProfileModeled.getConsumedPower(
                                BatteryConsumer.POWER_COMPONENT_CPU),
                        totalModeledCpuPowerMah);
            }
        } else {
            addEntry("CPU (sipper)", EntryType.POWER,
                    requestedBatterySipper.cpuPowerMah, totalCpuPowerMah);
        }
        addEntry("System services", EntryType.POWER,
                requestedBatterySipper.systemServiceCpuPowerMah, totalSystemServiceCpuPowerMah);
        if (requestedBatteryConsumer != null) {
            addEntry("Usage", EntryType.POWER,
                    requestedBatteryConsumer.getConsumedPower(
                            BatteryConsumer.POWER_COMPONENT_USAGE), totalUsagePowerMah);
        } else {
            addEntry("Usage (sipper)", EntryType.POWER,
                    requestedBatterySipper.usagePowerMah, totalUsagePowerMah);
        }
        addEntry("Wake lock", EntryType.POWER,
                requestedBatterySipper.wakeLockPowerMah, totalWakeLockPowerMah);
        addEntry("Mobile radio", EntryType.POWER,
                requestedBatterySipper.mobileRadioPowerMah, totalMobileRadioPowerMah);
        addEntry("WiFi", EntryType.POWER,
                requestedBatterySipper.wifiPowerMah, totalWifiPowerMah);
        addEntry("Bluetooth", EntryType.POWER,
                requestedBatterySipper.bluetoothPowerMah, totalBluetoothPowerMah);
        addEntry("GPS", EntryType.POWER,
                requestedBatterySipper.gpsPowerMah, totalGpsPowerMah);
        addEntry("Camera", EntryType.POWER,
                requestedBatterySipper.cameraPowerMah, totalCameraPowerMah);
        addEntry("Flashlight", EntryType.POWER,
                requestedBatterySipper.flashlightPowerMah, totalFlashlightPowerMah);
        addEntry("Sensors", EntryType.POWER,
                requestedBatterySipper.sensorPowerMah, totalSensorPowerMah);
        addEntry("Audio", EntryType.POWER,
                requestedBatterySipper.audioPowerMah, totalAudioPowerMah);
        addEntry("Video", EntryType.POWER,
                requestedBatterySipper.videoPowerMah, totalVideoPowerMah);

        addEntry("CPU time", EntryType.DURATION,
                requestedBatterySipper.cpuTimeMs, totalCpuTimeMs);
        addEntry("CPU foreground time", EntryType.DURATION,
                requestedBatterySipper.cpuFgTimeMs, totalCpuFgTimeMs);
        addEntry("Wake lock time", EntryType.DURATION,
                requestedBatterySipper.wakeLockTimeMs, totalWakeLockTimeMs);
        addEntry("WiFi running time", EntryType.DURATION,
                requestedBatterySipper.wifiRunningTimeMs, totalWifiRunningTimeMs);
        addEntry("Bluetooth time", EntryType.DURATION,
                requestedBatterySipper.bluetoothRunningTimeMs, totalBluetoothRunningTimeMs);
        addEntry("GPS time", EntryType.DURATION,
                requestedBatterySipper.gpsTimeMs, totalGpsTimeMs);
        addEntry("Camera time", EntryType.DURATION,
                requestedBatterySipper.cameraTimeMs, totalCameraTimeMs);
        addEntry("Flashlight time", EntryType.DURATION,
                requestedBatterySipper.flashlightTimeMs, totalFlashlightTimeMs);
        addEntry("Audio time", EntryType.DURATION,
                requestedBatterySipper.audioTimeMs, totalAudioTimeMs);
        addEntry("Video time", EntryType.DURATION,
                requestedBatterySipper.videoTimeMs, totalVideoTimeMs);
    }

    private boolean isSystemSipper(BatterySipper sipper) {
        final int uid = sipper.uidObj == null ? -1 : sipper.getUid();
        if (uid >= Process.ROOT_UID && uid < Process.FIRST_APPLICATION_UID) {
            return true;
        } else if (sipper.mPackages != null) {
            for (final String packageName : sipper.mPackages) {
                for (final String systemPackage : PACKAGES_SYSTEM) {
                    if (systemPackage.equals(packageName)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void addEntry(String title, EntryType entryType, double amount, double totalAmount) {
        Entry entry = new Entry();
        entry.title = title;
        entry.entryType = entryType;
        entry.value = amount;
        entry.total = totalAmount;
        mEntries.add(entry);
    }

    private void maybeAddMeasuredEnergyEntry(BatterySipper.DrainType drainType,
            BatteryStats batteryStats) {
        switch (drainType) {
            case AMBIENT_DISPLAY:
                final long totalDozeMeasuredChargeUC =
                        batteryStats.getScreenDozeMeasuredBatteryConsumptionUC();
                if (totalDozeMeasuredChargeUC != BatteryStats.POWER_DATA_UNAVAILABLE) {
                    final double measuredCharge = UC_2_MAH * totalDozeMeasuredChargeUC;
                    addEntry("Measured ambient display power", EntryType.POWER, measuredCharge,
                            measuredCharge);
                }
                break;
            case SCREEN:
                final long totalScreenMeasuredChargeUC =
                        batteryStats.getScreenOnMeasuredBatteryConsumptionUC();
                if (totalScreenMeasuredChargeUC != BatteryStats.POWER_DATA_UNAVAILABLE) {
                    final double measuredCharge = UC_2_MAH * totalScreenMeasuredChargeUC;
                    addEntry("Measured screen power", EntryType.POWER, measuredCharge,
                            measuredCharge);
                }
                break;
        }
    }

    public BatteryConsumerInfoHelper.BatteryConsumerInfo getBatteryConsumerInfo() {
        return mBatteryConsumerInfo;
    }

    public List<Entry> getEntries() {
        return mEntries;
    }

    public static String batteryConsumerId(BatterySipper sipper) {
        return sipper.drainType + "|" + sipper.userId + "|" + sipper.getUid();
    }

    public static String batteryConsumerId(BatteryConsumer consumer) {
        if (consumer instanceof UidBatteryConsumer) {
            return BatterySipper.DrainType.APP + "|"
                    + UserHandle.getUserId(((UidBatteryConsumer) consumer).getUid()) + "|"
                    + ((UidBatteryConsumer) consumer).getUid();
        } else if (consumer instanceof SystemBatteryConsumer) {
            return ((SystemBatteryConsumer) consumer).getDrainType() + "|0|0";
        } else {
            return "";
        }
    }
}
