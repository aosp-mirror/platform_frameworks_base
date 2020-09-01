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

package android.perftests.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Utility to get the slice of tracing_mark_write S,F,B,E (Async: start, finish, Sync: begin, end).
 * Use {@link #visit(String)} to process the trace in text form. The filtered results can be
 * obtained by {@link #forAllSlices(BiConsumer)}.
 *
 * @see android.os.Trace
 */
public class TraceMarkParser {
    /** All slices by the name of {@link TraceMarkLine}. */
    private final Map<String, List<TraceMarkSlice>> mSlicesMap = new HashMap<>();
    /** The nested depth of each task-pid. */
    private final Map<String, Integer> mDepthMap = new HashMap<>();
    /** The start trace lines that haven't matched the corresponding end. */
    private final Map<String, TraceMarkLine> mPendingStarts = new HashMap<>();

    private final Predicate<TraceMarkLine> mTraceLineFilter;

    private static final long MICROS_PER_SECOND = 1000L * 1000L;

    public TraceMarkParser(Predicate<TraceMarkLine> traceLineFilter) {
        mTraceLineFilter = traceLineFilter;
    }

    /** Only accept the trace event with the given names. */
    public TraceMarkParser(String... traceNames) {
        this(line -> {
            for (String name : traceNames) {
                if (name.equals(line.name)) {
                    return true;
                }
            }
            return false;
        });
    }

    /** Computes {@link TraceMarkSlice} by the given trace line. */
    public void visit(String textTraceLine) {
        final TraceMarkLine line = TraceMarkLine.parse(textTraceLine);
        if (line == null) {
            return;
        }

        if (line.isAsync) {
            // Async-trace contains name in the start and finish event.
            if (mTraceLineFilter.test(line)) {
                if (line.isBegin) {
                    mPendingStarts.put(line.name, line);
                } else {
                    final TraceMarkLine start = mPendingStarts.remove(line.name);
                    if (start != null) {
                        addSlice(start, line);
                    }
                }
            }
            return;
        }

        int depth = 1;
        if (line.isBegin) {
            final Integer existingDepth = mDepthMap.putIfAbsent(line.taskPid, 1);
            if (existingDepth != null) {
                mDepthMap.put(line.taskPid, depth = existingDepth + 1);
            }
            // Sync-trace only contains name in the begin event.
            if (mTraceLineFilter.test(line)) {
                mPendingStarts.put(getSyncPendingStartKey(line, depth), line);
            }
        } else {
            final Integer existingDepth = mDepthMap.get(line.taskPid);
            if (existingDepth != null) {
                depth = existingDepth;
                mDepthMap.put(line.taskPid, existingDepth - 1);
            }
            final TraceMarkLine begin = mPendingStarts.remove(getSyncPendingStartKey(line, depth));
            if (begin != null) {
                addSlice(begin, line);
            }
        }
    }

    private static String getSyncPendingStartKey(TraceMarkLine line, int depth) {
        return line.taskPid + "@" + depth;
    }

    private void addSlice(TraceMarkLine begin, TraceMarkLine end) {
        mSlicesMap.computeIfAbsent(
                begin.name, k -> new ArrayList<>()).add(new TraceMarkSlice(begin, end));
    }

    public void forAllSlices(BiConsumer<String, List<TraceMarkSlice>> consumer) {
        for (Map.Entry<String, List<TraceMarkSlice>> entry : mSlicesMap.entrySet()) {
            consumer.accept(entry.getKey(), entry.getValue());
        }
    }

    public void reset() {
        mSlicesMap.clear();
        mDepthMap.clear();
        mPendingStarts.clear();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        forAllSlices((key, slices) -> {
            double totalMs = 0;
            for (TraceMarkSlice s : slices) {
                totalMs += s.getDurationInSeconds() * 1000;
            }
            sb.append(key).append(" count=").append(slices.size()).append(" avg=")
                    .append(totalMs / slices.size()).append("ms\n");
        });

        if (!mPendingStarts.isEmpty()) {
            sb.append("[Warning] Unresolved events:").append(mPendingStarts).append("\n");
        }
        return sb.toString();
    }

    static double microsecondToSeconds(long ms) {
        return (ms * 1.0d) / MICROS_PER_SECOND;
    }

    public static class TraceMarkSlice {
        public final TraceMarkLine begin;
        public final TraceMarkLine end;

        TraceMarkSlice(TraceMarkLine begin, TraceMarkLine end) {
            this.begin = begin;
            this.end = end;
        }

        public double getDurationInSeconds() {
            return microsecondToSeconds(end.timestamp - begin.timestamp);
        }

        public long getDurationInMicroseconds() {
            return end.timestamp - begin.timestamp;
        }
    }

    // taskPid                               timestamp                           name
    // # Async:
    // Binder:129_F-349  ( 1296) [003] ...1  12.2776: tracing_mark_write: S|1296|launching: a.test|0
    // android.anim-135  ( 1296) [005] ...1  12.3361: tracing_mark_write: F|1296|launching: a.test|0
    // # Normal:
    // Binder:129_6-315  ( 1296) [007] ...1  97.4576: tracing_mark_write: B|1296|relayoutWindow: xxx
    // ... there may have other nested begin/end
    // Binder:129_6-315  ( 1296) [007] ...1  97.4580: tracing_mark_write: E|1296
    public static class TraceMarkLine {
        static final String EVENT_KEYWORD = ": tracing_mark_write: ";
        static final char ASYNC_START = 'S';
        static final char ASYNC_FINISH = 'F';
        static final char SYNC_BEGIN = 'B';
        static final char SYNC_END = 'E';

        public final String taskPid;
        public final long timestamp; // in microseconds
        public final String name;
        public final boolean isAsync;
        public final boolean isBegin;

        TraceMarkLine(String rawLine, int typePos, int type) throws IllegalArgumentException {
            taskPid = rawLine.substring(0, rawLine.indexOf('(')).trim();
            final int timeEnd = rawLine.indexOf(':', taskPid.length());
            if (timeEnd < 0) {
                throw new IllegalArgumentException("Timestamp end not found");
            }
            final int timeBegin = rawLine.lastIndexOf(' ', timeEnd);
            if (timeBegin < 0) {
                throw new IllegalArgumentException("Timestamp start not found");
            }
            timestamp = parseMicroseconds(rawLine.substring(timeBegin, timeEnd));
            isAsync = type == ASYNC_START || type == ASYNC_FINISH;
            isBegin = type == ASYNC_START || type == SYNC_BEGIN;

            if (!isAsync && !isBegin) {
                name = "";
            } else {
                // Get the position of the second '|' from "S|1234|name".
                final int nameBegin = rawLine.indexOf('|', typePos + 2) + 1;
                if (nameBegin == 0) {
                    throw new IllegalArgumentException("Name begin not found");
                }
                if (isAsync) {
                    // Get the name from "S|1234|name|0".
                    name = rawLine.substring(nameBegin, rawLine.lastIndexOf('|'));
                } else {
                    name = rawLine.substring(nameBegin);
                }
            }
        }

        static TraceMarkLine parse(String rawLine) {
            final int eventPos = rawLine.indexOf(EVENT_KEYWORD);
            if (eventPos < 0) {
                return null;
            }
            final int typePos = eventPos + EVENT_KEYWORD.length();
            if (typePos >= rawLine.length()) {
                return null;
            }
            final int type = rawLine.charAt(typePos);
            if (type != ASYNC_START && type != ASYNC_FINISH
                    && type != SYNC_BEGIN  && type != SYNC_END) {
                return null;
            }

            try {
                return new TraceMarkLine(rawLine, typePos, type);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
            return null;
        }

        /**
         * Parse the timestamp from atrace output, the format will be like:
         * 84962.920719  where the decimal part will be always exactly 6 digits.
         * ^^^^^ ^^^^^^
         * |     |
         * sec   microsec
         */
        static long parseMicroseconds(String line) {
            int end = line.length();
            long t = 0;
            for (int i = 0; i < end; i++) {
                char c = line.charAt(i);
                if (c >= '0' && c <= '9') {
                    t = t * 10 + (c - '0');
                }
            }
            return t;
        }

        @Override
        public String toString() {
            return "TraceMarkLine{pid=" + taskPid + " time="
                    + microsecondToSeconds(timestamp) + " name=" + name
                    + " async=" + isAsync + " begin=" + isBegin + "}";
        }
    }
}
