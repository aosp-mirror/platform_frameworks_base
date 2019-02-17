/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.util.ArrayMap;
import android.util.Slog;

import java.util.Map;

/** Rail Stats Power Monitoring Class */
public final class RailStats {
    private static final String TAG = "RailStats";

    private static final String WIFI_SUBSYSTEM = "wifi";
    private static final String CELLULAR_SUBSYSTEM = "cellular";

    private Map<Long, RailInfoData> mRailInfoData = new ArrayMap<>();

    private long mCellularTotalEnergyUseduWs = 0;
    private long mWifiTotalEnergyUseduWs = 0;
    private boolean mRailStatsAvailability = true;

    /** Updates the rail data map of all power monitor rails being monitored
     * Function is called from native side
     * @param index
     * @param railName
     * @param subSystemName
     * @param timestampSinceBootMs
     * @param energyUsedSinceBootuWs
     */
    public void updateRailData(long index, String railName, String subSystemName,
            long timestampSinceBootMs, long energyUsedSinceBootuWs) {
        if (!(subSystemName.equals(WIFI_SUBSYSTEM) || subSystemName.equals(CELLULAR_SUBSYSTEM))) {
            return;
        }
        RailInfoData node = mRailInfoData.get(index);
        if (node == null) {
            mRailInfoData.put(index, new RailInfoData(index, railName, subSystemName,
                    timestampSinceBootMs, energyUsedSinceBootuWs));
            if (subSystemName.equals(WIFI_SUBSYSTEM)) {
                mWifiTotalEnergyUseduWs += energyUsedSinceBootuWs;
                return;
            }
            if (subSystemName.equals(CELLULAR_SUBSYSTEM)) {
                mCellularTotalEnergyUseduWs += energyUsedSinceBootuWs;
            }
            return;
        }
        long timeSinceLastLogMs = timestampSinceBootMs - node.timestampSinceBootMs;
        long energyUsedSinceLastLoguWs = energyUsedSinceBootuWs - node.energyUsedSinceBootuWs;
        if (timeSinceLastLogMs < 0 || energyUsedSinceLastLoguWs < 0) {
            energyUsedSinceLastLoguWs = node.energyUsedSinceBootuWs;
        }
        node.timestampSinceBootMs = timestampSinceBootMs;
        node.energyUsedSinceBootuWs = energyUsedSinceBootuWs;
        if (subSystemName.equals(WIFI_SUBSYSTEM)) {
            mWifiTotalEnergyUseduWs += energyUsedSinceLastLoguWs;
            return;
        }
        if (subSystemName.equals(CELLULAR_SUBSYSTEM)) {
            mCellularTotalEnergyUseduWs += energyUsedSinceLastLoguWs;
        }
    }

    /** resets the cellular total energy used aspect.
     */
    public void resetCellularTotalEnergyUsed() {
        mCellularTotalEnergyUseduWs = 0;
    }

    /** resets the wifi total energy used aspect.
     */
    public void resetWifiTotalEnergyUsed() {
        mWifiTotalEnergyUseduWs = 0;
    }

    public long getCellularTotalEnergyUseduWs() {
        return mCellularTotalEnergyUseduWs;
    }

    public long getWifiTotalEnergyUseduWs() {
        return mWifiTotalEnergyUseduWs;
    }

    /** reset the total energy subsystems
     *
     */
    public void reset() {
        mCellularTotalEnergyUseduWs = 0;
        mWifiTotalEnergyUseduWs = 0;
    }

    public RailStats getRailStats() {
        return this;
    }

    public void setRailStatsAvailability(boolean railStatsAvailability) {
        mRailStatsAvailability = railStatsAvailability;
    }

    public boolean isRailStatsAvailable() {
        return mRailStatsAvailability;
    }

    /** Container class to contain rail data information */
    public static class RailInfoData {
        private static final String TAG = "RailInfoData";
        public long index;
        public String railName;
        public String subSystemName;
        public long timestampSinceBootMs;
        public long energyUsedSinceBootuWs;

        private RailInfoData(long index, String railName, String subSystemName,
                long timestampSinceBootMs, long energyUsedSinceBoot) {
            this.index = index;
            this.railName = railName;
            this.subSystemName = subSystemName;
            this.timestampSinceBootMs = timestampSinceBootMs;
            this.energyUsedSinceBootuWs = energyUsedSinceBoot;
        }

        /** print the rail data
         *
         */
        public void printData() {
            Slog.d(TAG, "Index = " + index);
            Slog.d(TAG, "RailName = " + railName);
            Slog.d(TAG, "SubSystemName = " + subSystemName);
            Slog.d(TAG, "TimestampSinceBootMs = " + timestampSinceBootMs);
            Slog.d(TAG, "EnergyUsedSinceBootuWs = " + energyUsedSinceBootuWs);
        }
    }
}
