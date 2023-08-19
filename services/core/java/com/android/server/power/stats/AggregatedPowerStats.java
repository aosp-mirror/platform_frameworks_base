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

package com.android.server.power.stats;

import android.annotation.DurationMillisLong;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.IndentingPrintWriter;

import com.android.internal.os.PowerStats;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * This class represents aggregated power stats for a variety of power components (CPU, WiFi,
 * etc) covering a specific period of power usage history.
 */
class AggregatedPowerStats {
    private final PowerComponentAggregatedPowerStats[] mPowerComponentStats;

    // See MonotonicClock
    private long mStartTime;

    @DurationMillisLong
    private long mDurationMs;

    AggregatedPowerStats(PowerComponentAggregatedPowerStats... powerComponentAggregatedPowerStats) {
        this.mPowerComponentStats = powerComponentAggregatedPowerStats;
    }

    /**
     * @param startTime monotonic time
     */
    void setStartTime(long startTime) {
        mStartTime = startTime;
    }

    /**
     * Start time according to {@link com.android.internal.os.MonotonicClock}
     */
    public long getStartTime() {
        return mStartTime;
    }

    void setDuration(long durationMs) {
        mDurationMs = durationMs;
    }

    @DurationMillisLong
    public long getDuration() {
        return mDurationMs;
    }

    PowerComponentAggregatedPowerStats getPowerComponentStats(int powerComponentId) {
        for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
            if (stats.powerComponentId == powerComponentId) {
                return stats;
            }
        }
        return null;
    }

    void setDeviceState(@PowerStatsAggregator.TrackedState int stateId, int state, long time) {
        for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
            stats.setState(stateId, state, time);
        }
    }

    void setUidState(int uid, @PowerStatsAggregator.TrackedState int stateId, int state,
            long time) {
        for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
            stats.setUidState(uid, stateId, state, time);
        }
    }

    boolean isCompatible(PowerStats powerStats) {
        int powerComponentId = powerStats.descriptor.powerComponentId;
        for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
            if (stats.powerComponentId == powerComponentId && !stats.isCompatible(powerStats)) {
                return false;
            }
        }
        return true;
    }

    void addPowerStats(PowerStats powerStats, long time) {
        int powerComponentId = powerStats.descriptor.powerComponentId;
        for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
            if (stats.powerComponentId == powerComponentId) {
                stats.addPowerStats(powerStats, time);
            }
        }
    }

    void reset() {
        mStartTime = 0;
        mDurationMs = 0;
        for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
            stats.reset();
        }
    }

    void dump(PrintWriter pw) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.print("Start time: ");
        ipw.print(DateFormat.format("yyyy-MM-dd-HH-mm-ss", mStartTime));
        ipw.print(" duration: ");
        ipw.print(mDurationMs);
        ipw.println();

        ipw.println("Device");
        ipw.increaseIndent();
        for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
            stats.dumpDevice(ipw);
        }
        ipw.decreaseIndent();

        Set<Integer> uids = new HashSet<>();
        for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
            stats.collectUids(uids);
        }

        Integer[] allUids = uids.toArray(new Integer[uids.size()]);
        Arrays.sort(allUids);
        for (int uid : allUids) {
            ipw.println(UserHandle.formatUid(uid));
            ipw.increaseIndent();
            for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
                stats.dumpUid(ipw, uid);
            }
            ipw.decreaseIndent();
        }
    }
}
