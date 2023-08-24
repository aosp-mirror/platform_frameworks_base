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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.DurationMillisLong;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.os.PowerStats;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This class represents aggregated power stats for a variety of power components (CPU, WiFi,
 * etc) covering a specific period of power usage history.
 */
class AggregatedPowerStats {
    private static final String TAG = "AggregatedPowerStats";
    private static final int MAX_CLOCK_UPDATES = 100;
    private final PowerComponentAggregatedPowerStats[] mPowerComponentStats;

    static class ClockUpdate {
        public long monotonicTime;
        @CurrentTimeMillisLong public long currentTime;
    }

    private final List<ClockUpdate> mClockUpdates = new ArrayList<>();

    @DurationMillisLong
    private long mDurationMs;

    AggregatedPowerStats(PowerComponentAggregatedPowerStats... powerComponentAggregatedPowerStats) {
        this.mPowerComponentStats = powerComponentAggregatedPowerStats;
    }

    /**
     * Records a mapping of monotonic time to wall-clock time. Since wall-clock time can change,
     * there may be multiple clock updates in one set of aggregated stats.
     *
     * @param monotonicTime monotonic time in milliseconds, see
     * {@link com.android.internal.os.MonotonicClock}
     * @param currentTime   current time in milliseconds, see {@link System#currentTimeMillis()}
     */
    void addClockUpdate(long monotonicTime, @CurrentTimeMillisLong long currentTime) {
        ClockUpdate clockUpdate = new ClockUpdate();
        clockUpdate.monotonicTime = monotonicTime;
        clockUpdate.currentTime = currentTime;
        if (mClockUpdates.size() < MAX_CLOCK_UPDATES) {
            mClockUpdates.add(clockUpdate);
        } else {
            Slog.i(TAG, "Too many clock updates. Replacing the previous update with "
                        + DateFormat.format("yyyy-MM-dd-HH-mm-ss", currentTime));
            mClockUpdates.set(mClockUpdates.size() - 1, clockUpdate);
        }
    }

    /**
     * Start time according to {@link com.android.internal.os.MonotonicClock}
     */
    long getStartTime() {
        if (mClockUpdates.isEmpty()) {
            return 0;
        } else {
            return mClockUpdates.get(0).monotonicTime;
        }
    }

    List<ClockUpdate> getClockUpdates() {
        return mClockUpdates;
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
        mClockUpdates.clear();
        mDurationMs = 0;
        for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
            stats.reset();
        }
    }

    void dump(PrintWriter pw) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        StringBuilder sb = new StringBuilder();
        long baseTime = 0;
        for (int i = 0; i < mClockUpdates.size(); i++) {
            ClockUpdate clockUpdate = mClockUpdates.get(i);
            sb.setLength(0);
            if (i == 0) {
                baseTime = clockUpdate.monotonicTime;
                sb.append("Start time: ")
                        .append(DateFormat.format("yyyy-MM-dd-HH-mm-ss", clockUpdate.currentTime))
                        .append(" (")
                        .append(baseTime)
                        .append(") duration: ")
                        .append(mDurationMs);
                ipw.println(sb);
            } else {
                sb.setLength(0);
                sb.append("Clock update:  ");
                TimeUtils.formatDuration(
                        clockUpdate.monotonicTime - baseTime, sb,
                        TimeUtils.HUNDRED_DAY_FIELD_LEN + 3);
                sb.append(" ").append(
                        DateFormat.format("yyyy-MM-dd-HH-mm-ss", clockUpdate.currentTime));
                ipw.increaseIndent();
                ipw.println(sb);
                ipw.decreaseIndent();
            }
        }

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
