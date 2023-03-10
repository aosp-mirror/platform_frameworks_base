/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;

import java.util.Arrays;
import java.util.Comparator;

/**
 * A collection of energy measurements from Power Monitors.
 *
 * @hide
 */
public final class PowerMonitorReadings {
    public static final int ENERGY_UNAVAILABLE = -1;

    @NonNull
    private final PowerMonitor[] mPowerMonitors;
    @NonNull
    private final long[] mEnergyUws;
    @NonNull
    private final long[] mTimestampsMs;

    private static final Comparator<PowerMonitor> POWER_MONITOR_COMPARATOR =
            Comparator.comparingInt(pm -> pm.index);

    /**
     * @param powerMonitors array of power monitor (ODPM) rails, sorted by PowerMonitor.index
     * @hide
     */
    public PowerMonitorReadings(PowerMonitor[] powerMonitors,
            long[] energyUws, long[] timestampsMs) {
        mPowerMonitors = powerMonitors;
        mEnergyUws = energyUws;
        mTimestampsMs = timestampsMs;
    }

    /**
     * Returns energy consumed by the specified power monitor since boot in microwatt-seconds.
     * Does not persist across reboots.
     * Represents total energy: both on-battery and plugged-in.
     */
    public long getConsumedEnergyUws(PowerMonitor powerMonitor) {
        int offset = Arrays.binarySearch(mPowerMonitors, powerMonitor, POWER_MONITOR_COMPARATOR);
        if (offset >= 0) {
            return mEnergyUws[offset];
        }
        return ENERGY_UNAVAILABLE;
    }

    /**
     * Elapsed realtime when the snapshot was taken.
     */
    public long getTimestampMs(PowerMonitor powerMonitor) {
        int offset = Arrays.binarySearch(mPowerMonitors, powerMonitor, POWER_MONITOR_COMPARATOR);
        if (offset >= 0) {
            return mTimestampsMs[offset];
        }
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(" monitors: [");
        for (int i = 0; i < mPowerMonitors.length; i++) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append(mPowerMonitors[i].name)
                    .append(" = ").append(mEnergyUws[i])
                    .append(" (").append(mTimestampsMs[i]).append(')');
        }
        sb.append("]");
        return sb.toString();
    }
}
