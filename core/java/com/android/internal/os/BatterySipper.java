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

import android.os.BatteryStats.Uid;

/**
 * Contains power usage of an application, system service, or hardware type.
 */
public class BatterySipper implements Comparable<BatterySipper> {
    public int userId;
    public Uid uidObj;
    public double value;
    public double[] values;
    public DrainType drainType;
    public long usageTime;
    public long cpuTime;
    public long gpsTime;
    public long wifiRunningTime;
    public long cpuFgTime;
    public long wakeLockTime;
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
    public double percent;
    public double noCoveragePercent;
    public String[] mPackages;
    public String packageWithHighestDrain;

    public enum DrainType {
        IDLE,
        CELL,
        PHONE,
        WIFI,
        BLUETOOTH,
        FLASHLIGHT,
        SCREEN,
        APP,
        USER,
        UNACCOUNTED,
        OVERCOUNTED
    }

    public BatterySipper(DrainType drainType, Uid uid, double[] values) {
        this.values = values;
        if (values != null) value = values[0];
        this.drainType = drainType;
        uidObj = uid;
    }

    public double[] getValues() {
        return values;
    }

    public void computeMobilemspp() {
        long packets = mobileRxPackets+mobileTxPackets;
        mobilemspp = packets > 0 ? (mobileActive / (double)packets) : 0;
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
        return Double.compare(other.value, value);
    }

    /**
     * Gets a list of packages associated with the current user
     */
    public String[] getPackages() {
        return mPackages;
    }

    public int getUid() {
        // Bail out if the current sipper is not an App sipper.
        if (uidObj == null) {
            return 0;
        }
        return uidObj.getUid();
    }
}
