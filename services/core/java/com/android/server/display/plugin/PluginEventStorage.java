/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.plugin;

import android.util.IndentingPrintWriter;

import com.android.internal.util.RingBuffer;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class PluginEventStorage {
    private static final long TIME_FRAME_LENGTH = 60_000;
    private static final long MIN_EVENT_DELAY = 500;
    private static final int MAX_TIME_FRAMES = 10;
    // not thread safe
    private static final SimpleDateFormat sDateFormat = new SimpleDateFormat(
            "MM-dd HH:mm:ss.SSS", Locale.US);

    RingBuffer<TimeFrame> mEvents = new RingBuffer<>(
            TimeFrame::new, TimeFrame[]::new, MAX_TIME_FRAMES);

    private final Map<PluginType<?>, Long> mEventTimes = new HashMap<>();
    private long mTimeFrameStart = 0;
    private final Map<PluginType<?>, EventCounter> mCounters = new HashMap<>();

    <T> void onValueUpdated(PluginType<T> type) {
        long eventTime = System.currentTimeMillis();
        if (eventTime - TIME_FRAME_LENGTH > mTimeFrameStart) { // event is in next TimeFrame
            closeCurrentTimeFrame();
            mTimeFrameStart = eventTime;
        }
        updateCurrentTimeFrame(type, eventTime);
    }

    private void closeCurrentTimeFrame() {
        if (!mCounters.isEmpty()) {
            mEvents.append(new TimeFrame(
                    mTimeFrameStart, mTimeFrameStart + TIME_FRAME_LENGTH, mCounters));
            mCounters.clear();
        }
    }

    private <T> void updateCurrentTimeFrame(PluginType<T> type, long eventTime) {
        EventCounter counter = mCounters.get(type);
        long previousTimestamp = mEventTimes.getOrDefault(type, 0L);
        if (counter == null) {
            counter = new EventCounter();
            mCounters.put(type, counter);
        }
        counter.increase(eventTime, previousTimestamp);
        mEventTimes.put(type, eventTime);
    }

    List<TimeFrame> getTimeFrames() {
        List<TimeFrame> timeFrames = new ArrayList<>(Arrays.stream(mEvents.toArray()).toList());
        timeFrames.add(new TimeFrame(
                mTimeFrameStart, System.currentTimeMillis(), mCounters));
        return timeFrames;
    }

    static class TimeFrame {
        private final long mStart;
        private final long mEnd;
        private final  Map<PluginType<?>, EventCounter> mCounters;

        private TimeFrame() {
            this(0, 0, Map.of());
        }

        private TimeFrame(long start, long end, Map<PluginType<?>, EventCounter> counters) {
            mStart = start;
            mEnd = end;
            mCounters = new HashMap<>(counters);
        }

        @SuppressWarnings("JavaUtilDate")
        void dump(PrintWriter pw) {
            pw.append("TimeFrame:[")
                    .append(sDateFormat.format(new Date(mStart)))
                    .append(" - ")
                    .append(sDateFormat.format(new Date(mEnd)))
                    .println("]:");
            IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "    ");
            if (mCounters.isEmpty()) {
                ipw.println("NO EVENTS");
            } else {
                for (Map.Entry<PluginType<?>, EventCounter> entry: mCounters.entrySet()) {
                    ipw.append(entry.getKey().mName).append(" -> {");
                    entry.getValue().dump(ipw);
                    ipw.println("}");
                }
            }
        }
    }

    private static class EventCounter {
        private int mEventCounter = 0;
        private int mFastEventCounter = 0;

        private void increase(long timestamp, long previousTimestamp) {
            mEventCounter++;
            if (timestamp - previousTimestamp < MIN_EVENT_DELAY) {
                mFastEventCounter++;
            }
        }

        private void dump(PrintWriter pw) {
            pw.append("Count:").append(String.valueOf(mEventCounter))
                    .append("; Fast:").append(String.valueOf(mFastEventCounter));
        }
    }
}
