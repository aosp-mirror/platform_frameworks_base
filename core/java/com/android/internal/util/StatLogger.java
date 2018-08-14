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

package com.android.internal.util;

import android.os.SystemClock;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.server.StatLoggerProto;
import com.android.server.StatLoggerProto.Event;

import java.io.PrintWriter;

/**
 * Simple class to keep track of the number of times certain events happened and their durations for
 * benchmarking.
 *
 * @hide
 */
public class StatLogger {
    private static final String TAG = "StatLogger";

    private final Object mLock = new Object();

    private final int SIZE;

    @GuardedBy("mLock")
    private final int[] mCountStats;

    @GuardedBy("mLock")
    private final long[] mDurationStats;

    @GuardedBy("mLock")
    private final int[] mCallsPerSecond;

    @GuardedBy("mLock")
    private final long[] mDurationPerSecond;

    @GuardedBy("mLock")
    private final int[] mMaxCallsPerSecond;

    @GuardedBy("mLock")
    private final long[] mMaxDurationPerSecond;

    @GuardedBy("mLock")
    private final long[] mMaxDurationStats;

    @GuardedBy("mLock")
    private long mNextTickTime = SystemClock.elapsedRealtime() + 1000;

    private final String[] mLabels;

    public StatLogger(String[] eventLabels) {
        SIZE = eventLabels.length;
        mCountStats = new int[SIZE];
        mDurationStats = new long[SIZE];
        mCallsPerSecond = new int[SIZE];
        mMaxCallsPerSecond = new int[SIZE];
        mDurationPerSecond = new long[SIZE];
        mMaxDurationPerSecond = new long[SIZE];
        mMaxDurationStats = new long[SIZE];
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
     *
     * @return the duration in microseconds.
     */
    public long logDurationStat(int eventId, long start) {
        synchronized (mLock) {
            final long duration = getTime() - start;
            if (eventId >= 0 && eventId < SIZE) {
                mCountStats[eventId]++;
                mDurationStats[eventId] += duration;
            } else {
                Slog.wtf(TAG, "Invalid event ID: " + eventId);
                return duration;
            }
            if (mMaxDurationStats[eventId] < duration) {
                mMaxDurationStats[eventId] = duration;
            }

            // Keep track of the per-second max.
            final long nowRealtime = SystemClock.elapsedRealtime();
            if (nowRealtime > mNextTickTime) {
                if (mMaxCallsPerSecond[eventId] < mCallsPerSecond[eventId]) {
                    mMaxCallsPerSecond[eventId] = mCallsPerSecond[eventId];
                }
                if (mMaxDurationPerSecond[eventId] < mDurationPerSecond[eventId]) {
                    mMaxDurationPerSecond[eventId] = mDurationPerSecond[eventId];
                }

                mCallsPerSecond[eventId] = 0;
                mDurationPerSecond[eventId] = 0;

                mNextTickTime = nowRealtime + 1000;
            }

            mCallsPerSecond[eventId]++;
            mDurationPerSecond[eventId] += duration;

            return duration;
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        dump(new IndentingPrintWriter(pw, "  ").setIndent(prefix));
    }

    public void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.println("Stats:");
            pw.increaseIndent();
            for (int i = 0; i < SIZE; i++) {
                final int count = mCountStats[i];
                final double durationMs = mDurationStats[i] / 1000.0;

                pw.println(String.format(
                        "%s: count=%d, total=%.1fms, avg=%.3fms, max calls/s=%d max dur/s=%.1fms"
                        + " max time=%.1fms",
                        mLabels[i], count, durationMs,
                        (count == 0 ? 0 : durationMs / count),
                        mMaxCallsPerSecond[i], mMaxDurationPerSecond[i] / 1000.0,
                        mMaxDurationStats[i] / 1000.0));
            }
            pw.decreaseIndent();
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
