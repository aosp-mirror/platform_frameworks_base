/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.util;

import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Helper class for reporting boot and shutdown timing metrics.
 * <p>Note: This class is not thread-safe. Use a separate copy for other threads</p>
 * @hide
 */
public class TimingsTraceLog {
    // Debug boot time for every step if it's non-user build.
    private static final boolean DEBUG_BOOT_TIME = !Build.IS_USER;
    private final Deque<Pair<String, Long>> mStartTimes =
            DEBUG_BOOT_TIME ? new ArrayDeque<>() : null;
    private final String mTag;
    private long mTraceTag;
    private long mThreadId;

    public TimingsTraceLog(String tag, long traceTag) {
        mTag = tag;
        mTraceTag = traceTag;
        mThreadId = Thread.currentThread().getId();
    }

    /**
     * Begin tracing named section
     * @param name name to appear in trace
     */
    public void traceBegin(String name) {
        assertSameThread();
        Trace.traceBegin(mTraceTag, name);
        if (DEBUG_BOOT_TIME) {
            mStartTimes.push(Pair.create(name, SystemClock.elapsedRealtime()));
        }
    }

    /**
     * End tracing previously {@link #traceBegin(String) started} section.
     * Also {@link #logDuration logs} the duration.
     */
    public void traceEnd() {
        assertSameThread();
        Trace.traceEnd(mTraceTag);
        if (!DEBUG_BOOT_TIME) {
            return;
        }
        if (mStartTimes.peek() == null) {
            Slog.w(mTag, "traceEnd called more times than traceBegin");
            return;
        }
        Pair<String, Long> event = mStartTimes.pop();
        logDuration(event.first, (SystemClock.elapsedRealtime() - event.second));
    }

    private void assertSameThread() {
        final Thread currentThread = Thread.currentThread();
        if (currentThread.getId() != mThreadId) {
            throw new IllegalStateException("Instance of TimingsTraceLog can only be called from "
                    + "the thread it was created on (tid: " + mThreadId + "), but was from "
                    + currentThread.getName() + " (tid: " + currentThread.getId() + ")");
        }
    }

    /**
     * Log the duration so it can be parsed by external tools for performance reporting
     */
    public void logDuration(String name, long timeMs) {
        Slog.d(mTag, name + " took to complete: " + timeMs + "ms");
    }
}
