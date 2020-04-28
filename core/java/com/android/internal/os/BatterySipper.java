/*
 * Copyright (C) 2009 The Android Open Source Project
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
package com.android.internal.os;

import android.annotation.UnsupportedAppUsage;
import android.os.BatteryStats.Uid;

import java.util.List;

/**
 * Contains power usage of an application, system service, or hardware type.
 */
public class BatterySipper implements Comparable<BatterySipper> {
    @UnsupportedAppUsage
    public int userId;
    @UnsupportedAppUsage
    public Uid uidObj;
    @UnsupportedAppUsage
    public DrainType drainType;

    /**
     * Smeared power from screen usage.
     * We split the screen usage power and smear them among apps, based on activity time.
     */
    public double screenPowerMah;

    /**
     * Smeared power using proportional method.
     *
     * we smear power usage from hidden sippers to all apps proportionally.(except for screen usage)
     *
     * @see BatteryStatsHelper#shouldHideSipper(BatterySipper)
     * @see BatteryStatsHelper#removeHiddenBatterySippers(List)
     */
    public double proportionalSmearMah;

    /**
     * Total power that adding the smeared power.
     *
     * @see #sumPower()
     */
    public double totalSmearedPowerMah;

    /**
     * Total power before smearing
     */
    @UnsupportedAppUsage
    public double totalPowerMah;

    /**
     * Whether we should hide this sipper
     *
     * @see BatteryStatsHelper#shouldHideSipper(BatterySipper)
     */
    public boolean shouldHide;

    /**
     * Generic usage time in milliseconds.
     */
    @UnsupportedAppUsage
    public long usageTimeMs;

    /**
     * Generic power usage in mAh.
     */
    public double usagePowerMah;

    // Subsystem usage times.
    public long audioTimeMs;
    public long bluetoothRunningTimeMs;
    public long cameraTimeMs;
    @UnsupportedAppUsage
    public long cpuFgTimeMs;
    @UnsupportedAppUsage
    public long cpuTimeMs;
    public long flashlightTimeMs;
    @UnsupportedAppUsage
    public long gpsTimeMs;
    public long videoTimeMs;
    @UnsupportedAppUsage
    public long wakeLockTimeMs;
    @UnsupportedAppUsage
    public long wifiRunningTimeMs;

    public long mobileRxPackets;
    public long mobileTxPackets;
    public long mobileActive;
    public int mobileActiveCount;
    public double mobilemspp;         // milliseconds per packet
    public long wifiRxPackets;
    public long wifiTxPackets;
    public long mobileRxBytes;
    public long mobileTxBytes;
    public long wifiRxBytes;
    public long wifiTxBytes;
    public long btRxBytes;
    public long btTxBytes;
    public double percent;
    public double noCoveragePercent;
    @UnsupportedAppUsage
    public String[] mPackages;
    @UnsupportedAppUsage
    public String packageWithHighestDrain;

    // Measured in mAh (milli-ampere per hour).
    // These are included when summed.
    public double audioPowerMah;
    public double bluetoothPowerMah;
    public double cameraPowerMah;
    @UnsupportedAppUsage
    public double cpuPowerMah;
    public double flashlightPowerMah;
    public double gpsPowerMah;
    public double mobileRadioPowerMah;
    public double sensorPowerMah;
    public double videoPowerMah;
    public double wakeLockPowerMah;
    public double wifiPowerMah;

    //                           ****************
    // This list must be kept current with atoms.proto (frameworks/base/cmds/statsd/src/atoms.proto)
    // so the ordinal values (and therefore the order) must never change.
    //                           ****************
    public enum DrainType {
        AMBIENT_DISPLAY,
        @UnsupportedAppUsage
        APP,
        BLUETOOTH,
        CAMERA,
        CELL,
        FLASHLIGHT,
        IDLE,
        MEMORY,
        OVERCOUNTED,
        PHONE,
        SCREEN,
        UNACCOUNTED,
        USER,
        WIFI,
    }

    @UnsupportedAppUsage
    public BatterySipper(DrainType drainType, Uid uid, double value) {
        this.totalPowerMah = value;
        this.drainType = drainType;
        uidObj = uid;
    }

    public void computeMobilemspp() {
        long packets = mobileRxPackets + mobileTxPackets;
        mobilemspp = packets > 0 ? (mobileActive / (double) packets) : 0;
    }

    @Override
    public int compareTo(BatterySipper other) {
        // Over-counted always goes to the bottom.
        if (drainType != other.drainType) {
            if (drainType == DrainType.OVERCOUNTED) {
                // This is "larger"
                return 1;
            } else if (other.drainType == DrainType.OVERCOUNTED) {
                return -1;
            }
        }
        // Return the flipped value because we want the items in descending order
        return Double.compare(other.totalPowerMah, totalPowerMah);
    }

    /**
     * Gets a list of packages associated with the current user
     */
    @UnsupportedAppUsage
    public String[] getPackages() {
        return mPackages;
    }

    @UnsupportedAppUsage
    public int getUid() {
        // Bail out if the current sipper is not an App sipper.
        if (uidObj == null) {
            return 0;
        }
        return uidObj.getUid();
    }

    /**
     * Add stats from other to this BatterySipper.
     */
    @UnsupportedAppUsage
    public void add(BatterySipper other) {
        totalPowerMah += other.totalPowerMah;
        usageTimeMs += other.usageTimeMs;
        usagePowerMah += other.usagePowerMah;
        audioTimeMs += other.audioTimeMs;
        cpuTimeMs += other.cpuTimeMs;
        gpsTimeMs += other.gpsTimeMs;
        wifiRunningTimeMs += other.wifiRunningTimeMs;
        cpuFgTimeMs += other.cpuFgTimeMs;
        videoTimeMs += other.videoTimeMs;
        wakeLockTimeMs += other.wakeLockTimeMs;
        cameraTimeMs += other.cameraTimeMs;
        flashlightTimeMs += other.flashlightTimeMs;
        bluetoothRunningTimeMs += other.bluetoothRunningTimeMs;
        mobileRxPackets += other.mobileRxPackets;
        mobileTxPackets += other.mobileTxPackets;
        mobileActive += other.mobileActive;
        mobileActiveCount += other.mobileActiveCount;
        wifiRxPackets += other.wifiRxPackets;
        wifiTxPackets += other.wifiTxPackets;
        mobileRxBytes += other.mobileRxBytes;
        mobileTxBytes += other.mobileTxBytes;
        wifiRxBytes += other.wifiRxBytes;
        wifiTxBytes += other.wifiTxBytes;
        btRxBytes += other.btRxBytes;
        btTxBytes += other.btTxBytes;
        audioPowerMah += other.audioPowerMah;
        wifiPowerMah += other.wifiPowerMah;
        gpsPowerMah += other.gpsPowerMah;
        cpuPowerMah += other.cpuPowerMah;
        sensorPowerMah += other.sensorPowerMah;
        mobileRadioPowerMah += other.mobileRadioPowerMah;
        wakeLockPowerMah += other.wakeLockPowerMah;
        cameraPowerMah += other.cameraPowerMah;
        flashlightPowerMah += other.flashlightPowerMah;
        bluetoothPowerMah += other.bluetoothPowerMah;
        screenPowerMah += other.screenPowerMah;
        videoPowerMah += other.videoPowerMah;
        proportionalSmearMah += other.proportionalSmearMah;
        totalSmearedPowerMah += other.totalSmearedPowerMah;
    }

    /**
     * Sum all the powers and store the value into `value`.
     * Also sum the {@code smearedTotalPowerMah} by adding smeared powerMah.
     *
     * @return the sum of all the power in this BatterySipper.
     */
    public double sumPower() {
        totalPowerMah = usagePowerMah + wifiPowerMah + gpsPowerMah + cpuPowerMah +
                sensorPowerMah + mobileRadioPowerMah + wakeLockPowerMah + cameraPowerMah +
                flashlightPowerMah + bluetoothPowerMah + audioPowerMah + videoPowerMah;
        totalSmearedPowerMah = totalPowerMah + screenPowerMah + proportionalSmearMah;

        return totalPowerMah;
    }
}
