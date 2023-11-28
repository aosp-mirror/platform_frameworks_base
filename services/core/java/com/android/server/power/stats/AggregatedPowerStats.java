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
import android.annotation.NonNull;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.os.PowerStats;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringWriter;
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
    private static final String XML_TAG_AGGREGATED_POWER_STATS = "agg-power-stats";

    private final PowerComponentAggregatedPowerStats[] mPowerComponentStats;

    static class ClockUpdate {
        public long monotonicTime;
        @CurrentTimeMillisLong public long currentTime;
    }

    private final List<ClockUpdate> mClockUpdates = new ArrayList<>();

    @DurationMillisLong
    private long mDurationMs;

    AggregatedPowerStats(AggregatedPowerStatsConfig aggregatedPowerStatsConfig) {
        List<AggregatedPowerStatsConfig.PowerComponent> configs =
                aggregatedPowerStatsConfig.getPowerComponentsAggregatedStatsConfigs();
        mPowerComponentStats = new PowerComponentAggregatedPowerStats[configs.size()];
        for (int i = 0; i < configs.size(); i++) {
            mPowerComponentStats[i] = new PowerComponentAggregatedPowerStats(configs.get(i));
        }
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

    void setDeviceState(@AggregatedPowerStatsConfig.TrackedState int stateId, int state,
            long time) {
        for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
            stats.setState(stateId, state, time);
        }
    }

    void setUidState(int uid, @AggregatedPowerStatsConfig.TrackedState int stateId, int state,
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

    public void writeXml(TypedXmlSerializer serializer) throws IOException {
        serializer.startTag(null, XML_TAG_AGGREGATED_POWER_STATS);
        for (PowerComponentAggregatedPowerStats stats : mPowerComponentStats) {
            stats.writeXml(serializer);
        }
        serializer.endTag(null, XML_TAG_AGGREGATED_POWER_STATS);
        serializer.flush();
    }

    @NonNull
    public static AggregatedPowerStats createFromXml(
            TypedXmlPullParser parser, AggregatedPowerStatsConfig aggregatedPowerStatsConfig)
            throws XmlPullParserException, IOException {
        AggregatedPowerStats stats = new AggregatedPowerStats(aggregatedPowerStatsConfig);
        boolean inElement = false;
        boolean skipToEnd = false;
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT
                   && !(eventType == XmlPullParser.END_TAG
                        && parser.getName().equals(XML_TAG_AGGREGATED_POWER_STATS))) {
            if (!skipToEnd && eventType == XmlPullParser.START_TAG) {
                switch (parser.getName()) {
                    case XML_TAG_AGGREGATED_POWER_STATS:
                        inElement = true;
                        break;
                    case PowerComponentAggregatedPowerStats.XML_TAG_POWER_COMPONENT:
                        if (!inElement) {
                            break;
                        }

                        int powerComponentId = parser.getAttributeInt(null,
                                PowerComponentAggregatedPowerStats.XML_ATTR_ID);
                        for (PowerComponentAggregatedPowerStats powerComponent :
                                stats.mPowerComponentStats) {
                            if (powerComponent.powerComponentId == powerComponentId) {
                                if (!powerComponent.readFromXml(parser)) {
                                    skipToEnd = true;
                                }
                                break;
                            }
                        }
                        break;
                }
            }
            eventType = parser.next();
        }
        return stats;
    }

    void dump(IndentingPrintWriter ipw) {
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

    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        dump(new IndentingPrintWriter(sw));
        return sw.toString();
    }
}
