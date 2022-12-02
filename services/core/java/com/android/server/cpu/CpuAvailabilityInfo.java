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

    /** The latest average CPU availability percent. */
    public final int latestAvgAvailabilityPercent;

    /** The past N-second average CPU availability percent. */
    public final int pastNSecAvgAvailabilityPercent;

    /** The duration over which the {@link pastNSecAvgAvailabilityPercent} was calculated. */
    public final int avgAvailabilityDurationSec;

    @Override
    public String toString() {
        return "CpuAvailabilityInfo{" + "cpuset=" + cpuset + ", latestAvgAvailabilityPercent="
                + latestAvgAvailabilityPercent + ", pastNSecAvgAvailabilityPercent="
                + pastNSecAvgAvailabilityPercent + ", avgAvailabilityDurationSec="
                + avgAvailabilityDurationSec + '}';
    }

    CpuAvailabilityInfo(int cpuset, int latestAvgAvailabilityPercent,
            int pastNSecAvgAvailabilityPercent, int avgAvailabilityDurationSec) {
        this.cpuset = Preconditions.checkArgumentInRange(cpuset, CPUSET_ALL, CPUSET_BACKGROUND,
                "cpuset");
        this.latestAvgAvailabilityPercent = latestAvgAvailabilityPercent;
        this.pastNSecAvgAvailabilityPercent = pastNSecAvgAvailabilityPercent;
        this.avgAvailabilityDurationSec = avgAvailabilityDurationSec;
    }
}
