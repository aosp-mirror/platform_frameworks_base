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

import android.os.Process;

import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;

import java.util.ArrayList;
import java.util.List;

public class PowerMetrics {
    private static final String PACKAGE_CALENDAR_PROVIDER = "com.android.providers.calendar";
    private static final String PACKAGE_MEDIA_PROVIDER = "com.android.providers.media";
    private static final String PACKAGE_SYSTEMUI = "com.android.systemui";
    private static final String[] PACKAGES_SYSTEM = {PACKAGE_MEDIA_PROVIDER,
            PACKAGE_CALENDAR_PROVIDER, PACKAGE_SYSTEMUI};

    enum MetricKind {
        POWER,
        DURATION,
    }

    public static final String METRIC_APP_POWER = "appPower";
    public static final String METRIC_APP_POWER_EXCLUDE_SYSTEM_FROM_TOTAL = "appPowerExcludeSystem";
    public static final String METRIC_APP_POWER_EXCLUDE_SMEARED = "appPowerExcludeSmeared";
    public static final String METRIC_SCREEN_POWER = "screenPower";
    public static final String METRIC_WIFI_POWER = "wifiPower";
    public static final String METRIC_SYSTEM_SERVICE_CPU_POWER = "systemService";
    public static final String METRIC_OTHER_POWER = "otherPower";
    public static final String METRIC_CPU_POWER = "cpuPower";
    public static final String METRIC_RAM_POWER = "ramPower";
    public static final String METRIC_WAKELOCK_POWER = "wakelockPower";
    public static final String METRIC_MOBILE_RADIO_POWER = "mobileRadioPower";
    public static final String METRIC_BLUETOOTH_POWER = "bluetoothPower";
    public static final String METRIC_GPS_POWER = "gpsPower";
    public static final String METRIC_CAMERA_POWER = "cameraPower";
    public static final String METRIC_FLASHLIGHT_POWER = "flashlightPower";
    public static final String METRIC_SENSORS_POWER = "sensorsPower";
    public static final String METRIC_AUDIO_POWER = "audioPower";
    public static final String METRIC_VIDEO_POWER = "videoPower";
    public static final String METRIC_CPU_TIME = "cpuTime";
    public static final String METRIC_CPU_FOREGROUND_TIME = "cpuForegroundTime";
    public static final String METRIC_WAKELOCK_TIME = "wakelockTime";
    public static final String METRIC_WIFI_RUNNING_TIME = "wifiRunningTime";
    public static final String METRIC_BLUETOOTH_RUNNING_TIME = "bluetoothRunningTime";
    public static final String METRIC_GPS_TIME = "gpsTime";
    public static final String METRIC_CAMERA_TIME = "cameraTime";
    public static final String METRIC_FLASHLIGHT_TIME = "flashlightTime";
    public static final String METRIC_AUDIO_TIME = "audioTime";
    public static final String METRIC_VIDEO_TIME = "videoTime";

    public static class Metric {
        public String metricType;
        public MetricKind metricKind;
        public String title;
        public double value;
        public double total;
    }

    private final double mMinDrainedPower;
    private final double mMaxDrainedPower;

    private List<Metric> mMetrics = new ArrayList<>();

    public PowerMetrics(BatteryStatsHelper batteryStatsHelper, int uid) {
        mMinDrainedPower = batteryStatsHelper.getMinDrainedPower();
        mMaxDrainedPower = batteryStatsHelper.getMaxDrainedPower();

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

        if (uidSipper == null) {
            return;
        }

        addMetric(METRIC_APP_POWER, MetricKind.POWER, "Total power",
                uidSipper.totalSmearedPowerMah, totalSmearedPowerMah);
        addMetric(METRIC_APP_POWER_EXCLUDE_SYSTEM_FROM_TOTAL, MetricKind.POWER,
                "Total power excluding system",
                uidSipper.totalSmearedPowerMah, totalPowerExcludeSystemMah);
        addMetric(METRIC_SCREEN_POWER, MetricKind.POWER, "Screen, smeared",
                uidSipper.screenPowerMah, totalScreenPower);
        addMetric(METRIC_OTHER_POWER, MetricKind.POWER, "Other, smeared",
                uidSipper.proportionalSmearMah, totalProportionalSmearMah);
        addMetric(METRIC_APP_POWER_EXCLUDE_SMEARED, MetricKind.POWER, "Excluding smeared",
                uidSipper.totalPowerMah, totalPowerMah);
        addMetric(METRIC_CPU_POWER, MetricKind.POWER, "CPU",
                uidSipper.cpuPowerMah, totalCpuPowerMah);
        addMetric(METRIC_SYSTEM_SERVICE_CPU_POWER, MetricKind.POWER, "System services",
                uidSipper.systemServiceCpuPowerMah, totalSystemServiceCpuPowerMah);
        addMetric(METRIC_RAM_POWER, MetricKind.POWER, "RAM",
                uidSipper.usagePowerMah, totalUsagePowerMah);
        addMetric(METRIC_WAKELOCK_POWER, MetricKind.POWER, "Wake lock",
                uidSipper.wakeLockPowerMah, totalWakeLockPowerMah);
        addMetric(METRIC_MOBILE_RADIO_POWER, MetricKind.POWER, "Mobile radio",
                uidSipper.mobileRadioPowerMah, totalMobileRadioPowerMah);
        addMetric(METRIC_WIFI_POWER, MetricKind.POWER, "WiFi",
                uidSipper.wifiPowerMah, totalWifiPowerMah);
        addMetric(METRIC_BLUETOOTH_POWER, MetricKind.POWER, "Bluetooth",
                uidSipper.bluetoothPowerMah, totalBluetoothPowerMah);
        addMetric(METRIC_GPS_POWER, MetricKind.POWER, "GPS",
                uidSipper.gpsPowerMah, totalGpsPowerMah);
        addMetric(METRIC_CAMERA_POWER, MetricKind.POWER, "Camera",
                uidSipper.cameraPowerMah, totalCameraPowerMah);
        addMetric(METRIC_FLASHLIGHT_POWER, MetricKind.POWER, "Flashlight",
                uidSipper.flashlightPowerMah, totalFlashlightPowerMah);
        addMetric(METRIC_SENSORS_POWER, MetricKind.POWER, "Sensors",
                uidSipper.sensorPowerMah, totalSensorPowerMah);
        addMetric(METRIC_AUDIO_POWER, MetricKind.POWER, "Audio",
                uidSipper.audioPowerMah, totalAudioPowerMah);
        addMetric(METRIC_VIDEO_POWER, MetricKind.POWER, "Video",
                uidSipper.videoPowerMah, totalVideoPowerMah);

        addMetric(METRIC_CPU_TIME, MetricKind.DURATION, "CPU time",
                uidSipper.cpuTimeMs, totalCpuTimeMs);
        addMetric(METRIC_CPU_FOREGROUND_TIME, MetricKind.DURATION, "CPU foreground time",
                uidSipper.cpuFgTimeMs, totalCpuFgTimeMs);
        addMetric(METRIC_WAKELOCK_TIME, MetricKind.DURATION, "Wake lock time",
                uidSipper.wakeLockTimeMs, totalWakeLockTimeMs);
        addMetric(METRIC_WIFI_RUNNING_TIME, MetricKind.DURATION, "WiFi running time",
                uidSipper.wifiRunningTimeMs, totalWifiRunningTimeMs);
        addMetric(METRIC_BLUETOOTH_RUNNING_TIME, MetricKind.DURATION, "Bluetooth time",
                uidSipper.bluetoothRunningTimeMs, totalBluetoothRunningTimeMs);
        addMetric(METRIC_GPS_TIME, MetricKind.DURATION, "GPS time",
                uidSipper.gpsTimeMs, totalGpsTimeMs);
        addMetric(METRIC_CAMERA_TIME, MetricKind.DURATION, "Camera time",
                uidSipper.cameraTimeMs, totalCameraTimeMs);
        addMetric(METRIC_FLASHLIGHT_TIME, MetricKind.DURATION, "Flashlight time",
                uidSipper.flashlightTimeMs, totalFlashlightTimeMs);
        addMetric(METRIC_AUDIO_TIME, MetricKind.DURATION, "Audio time",
                uidSipper.audioTimeMs, totalAudioTimeMs);
        addMetric(METRIC_VIDEO_TIME, MetricKind.DURATION, "Video time",
                uidSipper.videoTimeMs, totalVideoTimeMs);
    }

    public List<Metric> getMetrics() {
        return mMetrics;
    }

    public double getMinDrainedPower() {
        return mMinDrainedPower;
    }

    public double getMaxDrainedPower() {
        return mMaxDrainedPower;
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

    private void addMetric(String metricType, MetricKind metricKind, String title, double amount,
            double totalAmount) {
        Metric metric = new Metric();
        metric.metricType = metricType;
        metric.metricKind = metricKind;
        metric.title = title;
        metric.value = amount;
        metric.total = totalAmount;
        mMetrics.add(metric);
    }
}
