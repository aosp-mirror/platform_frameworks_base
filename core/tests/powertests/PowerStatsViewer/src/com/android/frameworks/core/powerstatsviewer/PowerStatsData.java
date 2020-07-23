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

package com.android.frameworks.core.powerstatsviewer;

import android.content.Context;
import android.os.Process;

import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;

import java.util.ArrayList;
import java.util.List;

public class PowerStatsData {
    private static final String PACKAGE_CALENDAR_PROVIDER = "com.android.providers.calendar";
    private static final String PACKAGE_MEDIA_PROVIDER = "com.android.providers.media";
    private static final String PACKAGE_SYSTEMUI = "com.android.systemui";
    private static final String[] PACKAGES_SYSTEM = {PACKAGE_MEDIA_PROVIDER,
            PACKAGE_CALENDAR_PROVIDER, PACKAGE_SYSTEMUI};

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

    private final AppInfoHelper.AppInfo mAppInfo;
    private final List<Entry> mEntries = new ArrayList<>();

    public PowerStatsData(Context context, BatteryStatsHelper batteryStatsHelper,
            int uid) {
        List<BatterySipper> usageList = batteryStatsHelper.getUsageList();

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

        BatterySipper uidSipper = null;
        for (BatterySipper sipper : usageList) {
            if (sipper.drainType == BatterySipper.DrainType.SCREEN) {
                totalScreenPower = sipper.sumPower();
            }

            if (isHiddenDrainType(sipper.drainType)) {
                continue;
            }

            if (sipper.drainType == BatterySipper.DrainType.APP && sipper.getUid() == uid) {
                uidSipper = sipper;
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

        mAppInfo = AppInfoHelper.makeApplicationInfo(context.getPackageManager(), uid, uidSipper);

        if (uidSipper == null) {
            return;
        }

        addEntry("Total power", EntryType.POWER,
                uidSipper.totalSmearedPowerMah, totalSmearedPowerMah);
        addEntry("... excluding system", EntryType.POWER,
                uidSipper.totalSmearedPowerMah, totalPowerExcludeSystemMah);
        addEntry("Screen, smeared", EntryType.POWER,
                uidSipper.screenPowerMah, totalScreenPower);
        addEntry("Other, smeared", EntryType.POWER,
                uidSipper.proportionalSmearMah, totalProportionalSmearMah);
        addEntry("Excluding smeared", EntryType.POWER,
                uidSipper.totalPowerMah, totalPowerMah);
        addEntry("CPU", EntryType.POWER,
                uidSipper.cpuPowerMah, totalCpuPowerMah);
        addEntry("System services", EntryType.POWER,
                uidSipper.systemServiceCpuPowerMah, totalSystemServiceCpuPowerMah);
        addEntry("RAM", EntryType.POWER,
                uidSipper.usagePowerMah, totalUsagePowerMah);
        addEntry("Wake lock", EntryType.POWER,
                uidSipper.wakeLockPowerMah, totalWakeLockPowerMah);
        addEntry("Mobile radio", EntryType.POWER,
                uidSipper.mobileRadioPowerMah, totalMobileRadioPowerMah);
        addEntry("WiFi", EntryType.POWER,
                uidSipper.wifiPowerMah, totalWifiPowerMah);
        addEntry("Bluetooth", EntryType.POWER,
                uidSipper.bluetoothPowerMah, totalBluetoothPowerMah);
        addEntry("GPS", EntryType.POWER,
                uidSipper.gpsPowerMah, totalGpsPowerMah);
        addEntry("Camera", EntryType.POWER,
                uidSipper.cameraPowerMah, totalCameraPowerMah);
        addEntry("Flashlight", EntryType.POWER,
                uidSipper.flashlightPowerMah, totalFlashlightPowerMah);
        addEntry("Sensors", EntryType.POWER,
                uidSipper.sensorPowerMah, totalSensorPowerMah);
        addEntry("Audio", EntryType.POWER,
                uidSipper.audioPowerMah, totalAudioPowerMah);
        addEntry("Video", EntryType.POWER,
                uidSipper.videoPowerMah, totalVideoPowerMah);

        addEntry("CPU time", EntryType.DURATION,
                uidSipper.cpuTimeMs, totalCpuTimeMs);
        addEntry("CPU foreground time", EntryType.DURATION,
                uidSipper.cpuFgTimeMs, totalCpuFgTimeMs);
        addEntry("Wake lock time", EntryType.DURATION,
                uidSipper.wakeLockTimeMs, totalWakeLockTimeMs);
        addEntry("WiFi running time", EntryType.DURATION,
                uidSipper.wifiRunningTimeMs, totalWifiRunningTimeMs);
        addEntry("Bluetooth time", EntryType.DURATION,
                uidSipper.bluetoothRunningTimeMs, totalBluetoothRunningTimeMs);
        addEntry("GPS time", EntryType.DURATION,
                uidSipper.gpsTimeMs, totalGpsTimeMs);
        addEntry("Camera time", EntryType.DURATION,
                uidSipper.cameraTimeMs, totalCameraTimeMs);
        addEntry("Flashlight time", EntryType.DURATION,
                uidSipper.flashlightTimeMs, totalFlashlightTimeMs);
        addEntry("Audio time", EntryType.DURATION,
                uidSipper.audioTimeMs, totalAudioTimeMs);
        addEntry("Video time", EntryType.DURATION,
                uidSipper.videoTimeMs, totalVideoTimeMs);
    }

    protected boolean isHiddenDrainType(BatterySipper.DrainType drainType) {
        return drainType == BatterySipper.DrainType.IDLE
                || drainType == BatterySipper.DrainType.CELL
                || drainType == BatterySipper.DrainType.SCREEN
                || drainType == BatterySipper.DrainType.UNACCOUNTED
                || drainType == BatterySipper.DrainType.OVERCOUNTED
                || drainType == BatterySipper.DrainType.BLUETOOTH
                || drainType == BatterySipper.DrainType.WIFI;
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

    public AppInfoHelper.AppInfo getAppInfo() {
        return mAppInfo;
    }

    public List<Entry> getEntries() {
        return mEntries;
    }
}
