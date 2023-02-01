/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.cpu;

import static com.android.server.cpu.CpuAvailabilityMonitoringConfig.CPUSET_ALL;
import static com.android.server.cpu.CpuAvailabilityMonitoringConfig.CPUSET_BACKGROUND;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/** CPU availability information. */
public final class CpuAvailabilityInfo {
    /** Constant to indicate missing CPU availability percent. */
    public static final int MISSING_CPU_AVAILABILITY_PERCENT = -1;

    /**
     * The CPUSET whose availability info is recorded in this object.
     *
     * <p>The contained value is one of the CPUSET_* constants from the
     * {@link CpuAvailabilityMonitoringConfig}.
     */
    @CpuAvailabilityMonitoringConfig.Cpuset
    public final int cpuset;

    /** Uptime (in milliseconds) when the data in this object was captured. */
    public final long dataTimestampUptimeMillis;

    /** The latest average CPU availability percent. */
    public final int latestAvgAvailabilityPercent;

    /**
     * The past N-millisecond average CPU availability percent.
     *
     * <p>When there is not enough data to calculate the past N-millisecond average, this field will
     * contain the value {@link MISSING_CPU_AVAILABILITY_PERCENT}.
     */
    public final int pastNMillisAvgAvailabilityPercent;

    /** The duration over which the {@link pastNMillisAvgAvailabilityPercent} was calculated. */
    public final long pastNMillisDuration;

    @Override
    public String toString() {
        return "CpuAvailabilityInfo{" + "cpuset = " + cpuset + ", dataTimestampUptimeMillis = "
                + dataTimestampUptimeMillis + ", latestAvgAvailabilityPercent = "
                + latestAvgAvailabilityPercent + ", pastNMillisAvgAvailabilityPercent = "
                + pastNMillisAvgAvailabilityPercent + ", pastNMillisDuration = "
                + pastNMillisDuration + '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CpuAvailabilityInfo)) {
            return false;
        }
        CpuAvailabilityInfo info = (CpuAvailabilityInfo) obj;
        return cpuset == info.cpuset && dataTimestampUptimeMillis == info.dataTimestampUptimeMillis
                && latestAvgAvailabilityPercent == info.latestAvgAvailabilityPercent
                && pastNMillisAvgAvailabilityPercent == info.pastNMillisAvgAvailabilityPercent
                && pastNMillisDuration == info.pastNMillisDuration;
    }

    @Override
    public int hashCode() {
        return Objects.hash(cpuset, dataTimestampUptimeMillis, latestAvgAvailabilityPercent,
                pastNMillisAvgAvailabilityPercent, pastNMillisDuration);
    }

    CpuAvailabilityInfo(int cpuset, long dataTimestampUptimeMillis,
            int latestAvgAvailabilityPercent, int pastNMillisAvgAvailabilityPercent,
            long pastNMillisDuration) {
        this.cpuset = Preconditions.checkArgumentInRange(cpuset, CPUSET_ALL, CPUSET_BACKGROUND,
                "cpuset");
        this.dataTimestampUptimeMillis =
                Preconditions.checkArgumentNonnegative(dataTimestampUptimeMillis);
        this.latestAvgAvailabilityPercent = Preconditions.checkArgumentNonnegative(
                latestAvgAvailabilityPercent);
        this.pastNMillisAvgAvailabilityPercent = pastNMillisAvgAvailabilityPercent;
        this.pastNMillisDuration = Preconditions.checkArgumentNonnegative(
                pastNMillisDuration);
    }
}
