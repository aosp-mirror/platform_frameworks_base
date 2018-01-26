/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server;

import android.os.SystemClock;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.server.StatLoggerProto.Event;

import java.io.PrintWriter;

/**
 * Simple class to keep track of the number of times certain events happened and their durations for
 * benchmarking.
 *
 * TODO Update shortcut service to switch to it.
 *
 * @hide
 */
public class StatLogger {
    private final Object mLock = new Object();

    private final int SIZE;

    @GuardedBy("mLock")
    private final int[] mCountStats;

    @GuardedBy("mLock")
    private final long[] mDurationStats;

    private final String[] mLabels;

    public StatLogger(String[] eventLabels) {
        SIZE = eventLabels.length;
        mCountStats = new int[SIZE];
        mDurationStats = new long[SIZE];
        mLabels = eventLabels;
    }

    /**
     * Return the current time in the internal time unit.
     * Call it before an event happens, and
     * give it back to the {@link #logDurationStat(int, long)}} after the event.
     */
    public long getTime() {
        return SystemClock.elapsedRealtimeNanos() / 1000;
    }

    /**
     * @see {@link #getTime()}
     */
    public void logDurationStat(int eventId, long start) {
        synchronized (mLock) {
            mCountStats[eventId]++;
            mDurationStats[eventId] += (getTime() - start);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.print(prefix);
            pw.println("Stats:");
            for (int i = 0; i < SIZE; i++) {
                pw.print(prefix);
                pw.print("  ");
                final int count = mCountStats[i];
                final double durationMs = mDurationStats[i] / 1000.0;
                pw.println(String.format("%s: count=%d, total=%.1fms, avg=%.3fms",
                        mLabels[i], count, durationMs,
                        (count == 0 ? 0 : ((double) durationMs) / count)));
            }
        }
    }

    public void dumpProto(ProtoOutputStream proto, long fieldId) {
        synchronized (mLock) {
            final long outer = proto.start(fieldId);

            for (int i = 0; i < mLabels.length; i++) {
                final long inner = proto.start(StatLoggerProto.EVENTS);

                proto.write(Event.EVENT_ID, i);
                proto.write(Event.LABEL, mLabels[i]);
                proto.write(Event.COUNT, mCountStats[i]);
                proto.write(Event.TOTAL_DURATION_MICROS, mDurationStats[i]);

                proto.end(inner);
            }

            proto.end(outer);
        }
    }
}
