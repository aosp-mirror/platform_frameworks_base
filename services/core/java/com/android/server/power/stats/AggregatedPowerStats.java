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
import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.text.format.DateFormat;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.os.PowerStats;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.power.stats.AggregatedPowerStatsConfig.PowerComponent;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * This class represents aggregated power stats for a variety of power components (CPU, WiFi,
 * etc) covering a specific period of power usage history.
 */
class AggregatedPowerStats {
    private static final String TAG = "AggregatedPowerStats";
    private static final int MAX_CLOCK_UPDATES = 100;
    private static final String XML_TAG_AGGREGATED_POWER_STATS = "agg-power-stats";

    private final AggregatedPowerStatsConfig mConfig;
    private final SparseArray<PowerComponentAggregatedPowerStats> mPowerComponentStats;
    private final PowerComponentAggregatedPowerStats mGenericPowerComponent;

    static class ClockUpdate {
        public long monotonicTime;
        @CurrentTimeMillisLong
        public long currentTime;
    }

    private final List<ClockUpdate> mClockUpdates = new ArrayList<>();

    @DurationMillisLong
    private long mDurationMs;

    AggregatedPowerStats(@NonNull AggregatedPowerStatsConfig aggregatedPowerStatsConfig) {
        mConfig = aggregatedPowerStatsConfig;
        List<PowerComponent> configs =
                aggregatedPowerStatsConfig.getPowerComponentsAggregatedStatsConfigs();
        mPowerComponentStats = new SparseArray<>(configs.size());
        for (int i = 0; i < configs.size(); i++) {
            PowerComponent powerComponent = configs.get(i);
            mPowerComponentStats.put(powerComponent.getPowerComponentId(),
                    new PowerComponentAggregatedPowerStats(this, powerComponent));
        }
        mGenericPowerComponent = createGenericPowerComponent();
        mPowerComponentStats.put(BatteryConsumer.POWER_COMPONENT_ANY, mGenericPowerComponent);
    }

    private PowerComponentAggregatedPowerStats createGenericPowerComponent() {
        PowerComponent config = new PowerComponent(BatteryConsumer.POWER_COMPONENT_ANY);
        config.trackDeviceStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN)
                .trackUidStates(
                        AggregatedPowerStatsConfig.STATE_POWER,
                        AggregatedPowerStatsConfig.STATE_SCREEN,
                        AggregatedPowerStatsConfig.STATE_PROCESS_STATE);
        PowerComponentAggregatedPowerStats stats =
                new PowerComponentAggregatedPowerStats(this, config);
        stats.setPowerStatsDescriptor(
                new PowerStats.Descriptor(BatteryConsumer.POWER_COMPONENT_ANY, 0, null, 0, 0,
                        new PersistableBundle()));
        return stats;
    }

    /**
     * Records a mapping of monotonic time to wall-clock time. Since wall-clock time can change,
     * there may be multiple clock updates in one set of aggregated stats.
     *
     * @param monotonicTime monotonic time in milliseconds, see
     *                      {@link com.android.internal.os.MonotonicClock}
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

    List<PowerComponentAggregatedPowerStats> getPowerComponentStats() {
        List<PowerComponentAggregatedPowerStats> list = new ArrayList<>(
                mPowerComponentStats.size());
        for (int i = 0; i < mPowerComponentStats.size(); i++) {
            PowerComponentAggregatedPowerStats stats = mPowerComponentStats.valueAt(i);
            if (stats != mGenericPowerComponent) {
                list.add(stats);
            }
        }
        return list;
    }

    PowerComponentAggregatedPowerStats getPowerComponentStats(int powerComponentId) {
        return mPowerComponentStats.get(powerComponentId);
    }

    void start(long timestampMs) {
        for (int i = 0; i < mPowerComponentStats.size(); i++) {
            PowerComponentAggregatedPowerStats component = mPowerComponentStats.valueAt(i);
            component.getConfig().getProcessor().start(component, timestampMs);
        }
    }

    void setDeviceState(@AggregatedPowerStatsConfig.TrackedState int stateId, int state,
            long time) {
        for (int i = 0; i < mPowerComponentStats.size(); i++) {
            mPowerComponentStats.valueAt(i).setState(stateId, state, time);
        }
    }

    void setUidState(int uid, @AggregatedPowerStatsConfig.TrackedState int stateId, int state,
            long time) {
        for (int i = 0; i < mPowerComponentStats.size(); i++) {
            mPowerComponentStats.valueAt(i).setUidState(uid, stateId, state, time);
        }
    }

    boolean isCompatible(PowerStats powerStats) {
        int powerComponentId = powerStats.descriptor.powerComponentId;
        PowerComponentAggregatedPowerStats stats = mPowerComponentStats.get(powerComponentId);
        return stats != null && stats.isCompatible(powerStats);
    }

    void addPowerStats(PowerStats powerStats, long time) {
        int powerComponentId = powerStats.descriptor.powerComponentId;
        PowerComponentAggregatedPowerStats stats = mPowerComponentStats.get(powerComponentId);
        if (stats == null) {
            PowerComponent powerComponent = mConfig.createPowerComponent(powerComponentId);
            if (powerComponent == null) {
                return;
            }

            stats = new PowerComponentAggregatedPowerStats(this, powerComponent);
            stats.setPowerStatsDescriptor(powerStats.descriptor);
            stats.copyStatesFrom(mGenericPowerComponent);
            mPowerComponentStats.put(powerComponentId, stats);
        }

        PowerStatsProcessor processor = stats.getConfig().getProcessor();
        processor.addPowerStats(stats, powerStats, time);
    }

    public void noteStateChange(BatteryStats.HistoryItem item) {
        for (int i = 0; i < mPowerComponentStats.size(); i++) {
            PowerComponentAggregatedPowerStats stats = mPowerComponentStats.valueAt(i);
            stats.getConfig().getProcessor().noteStateChange(stats, item);
        }
    }

    void finish(long timestampMs) {
        for (int i = 0; i < mPowerComponentStats.size(); i++) {
            PowerComponentAggregatedPowerStats component = mPowerComponentStats.valueAt(i);
            component.getConfig().getProcessor().finish(component, timestampMs);
        }
    }

    void reset() {
        mClockUpdates.clear();
        mDurationMs = 0;
        for (int i = 0; i < mPowerComponentStats.size(); i++) {
            mPowerComponentStats.valueAt(i).reset();
        }
    }

    public void writeXml(TypedXmlSerializer serializer) throws IOException {
        serializer.startTag(null, XML_TAG_AGGREGATED_POWER_STATS);
        for (int i = 0; i < mPowerComponentStats.size(); i++) {
            PowerComponentAggregatedPowerStats stats = mPowerComponentStats.valueAt(i);
            if (stats != mGenericPowerComponent) {
                stats.writeXml(serializer);
            }
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
                    case PowerComponentAggregatedPowerStats.XML_TAG_POWER_COMPONENT: {
                        if (!inElement) {
                            break;
                        }

                        int powerComponentId = parser.getAttributeInt(null,
                                PowerComponentAggregatedPowerStats.XML_ATTR_ID);

                        PowerComponentAggregatedPowerStats powerComponentStats =
                                stats.getPowerComponentStats(powerComponentId);
                        if (powerComponentStats == null) {
                            PowerComponent powerComponent =
                                    aggregatedPowerStatsConfig.createPowerComponent(
                                            powerComponentId);
                            if (powerComponent != null) {
                                powerComponentStats = new PowerComponentAggregatedPowerStats(stats,
                                        powerComponent);
                                stats.mPowerComponentStats.put(powerComponentId,
                                        powerComponentStats);
                            }
                        }
                        if (powerComponentStats != null) {
                            if (!powerComponentStats.readFromXml(parser)) {
                                skipToEnd = true;
                            }
                        }
                        break;
                    }
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
                        .append(formatDateTime(clockUpdate.currentTime))
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
                sb.append(" ").append(formatDateTime(clockUpdate.currentTime));
                ipw.increaseIndent();
                ipw.println(sb);
                ipw.decreaseIndent();
            }
        }

        ipw.println("Device");
        ipw.increaseIndent();
        for (int i = 0; i < mPowerComponentStats.size(); i++) {
            mPowerComponentStats.valueAt(i).dumpDevice(ipw);
        }
        ipw.decreaseIndent();

        Set<Integer> uids = new HashSet<>();
        for (int i = 0; i < mPowerComponentStats.size(); i++) {
            mPowerComponentStats.valueAt(i).collectUids(uids);
        }

        Integer[] allUids = uids.toArray(new Integer[uids.size()]);
        Arrays.sort(allUids);
        for (int uid : allUids) {
            ipw.println(UserHandle.formatUid(uid));
            ipw.increaseIndent();
            for (int i = 0; i < mPowerComponentStats.size(); i++) {
                mPowerComponentStats.valueAt(i).dumpUid(ipw, uid);
            }
            ipw.decreaseIndent();
        }
    }

    private static String formatDateTime(long timeInMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        format.getCalendar().setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date(timeInMillis));
    }

    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        dump(new IndentingPrintWriter(sw));
        return sw.toString();
    }
}
