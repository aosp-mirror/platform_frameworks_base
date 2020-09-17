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

import android.annotation.NonNull;
import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Helper class for reporting boot and shutdown timing metrics.
 *
 * <p><b>NOTE:</b> This class is not thread-safe. Use a separate copy for other threads.
 *
 * @hide
 */
public class TimingsTraceLog {
    // Debug boot time for every step if it's non-user build.
    private static final boolean DEBUG_BOOT_TIME = !Build.IS_USER;

    // Maximum number of nested calls that are stored
    private static final int MAX_NESTED_CALLS = 10;

    private final String[] mStartNames;
    private final long[] mStartTimes;

    private final String mTag;
    private final long mTraceTag;
    private final long mThreadId;
    private final int mMaxNestedCalls;

    private int mCurrentLevel = -1;

    public TimingsTraceLog(String tag, long traceTag) {
        this(tag, traceTag, DEBUG_BOOT_TIME ? MAX_NESTED_CALLS : -1);
    }

    @VisibleForTesting
    public TimingsTraceLog(String tag, long traceTag, int maxNestedCalls) {
        mTag = tag;
        mTraceTag = traceTag;
        mThreadId = Thread.currentThread().getId();
        mMaxNestedCalls = maxNestedCalls;
        if (maxNestedCalls > 0) {
            mStartNames = new String[maxNestedCalls];
            mStartTimes = new long[maxNestedCalls];
        } else {
            mStartNames = null;
            mStartTimes = null;
        }
    }

    /**
     * Begin tracing named section
     * @param name name to appear in trace
     */
    public void traceBegin(String name) {
        assertSameThread();
        Trace.traceBegin(mTraceTag, name);

        if (!DEBUG_BOOT_TIME) return;

        if (mCurrentLevel + 1 >= mMaxNestedCalls) {
            Slog.w(mTag, "not tracing duration of '" + name + "' because already reached "
                    + mMaxNestedCalls + " levels");
            return;
        }

        mCurrentLevel++;
        mStartNames[mCurrentLevel] = name;
        mStartTimes[mCurrentLevel] = SystemClock.elapsedRealtime();
    }

    /**
     * End tracing previously {@link #traceBegin(String) started} section.
     *
     * <p>Also {@link #logDuration logs} the duration.
     */
    public void traceEnd() {
        assertSameThread();
        Trace.traceEnd(mTraceTag);

        if (!DEBUG_BOOT_TIME) return;

        if (mCurrentLevel < 0) {
            Slog.w(mTag, "traceEnd called more times than traceBegin");
            return;
        }

        final String name = mStartNames[mCurrentLevel];
        final long duration = SystemClock.elapsedRealtime() - mStartTimes[mCurrentLevel];
        mCurrentLevel--;

        logDuration(name, duration);
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
     * Logs a duration so it can be parsed by external tools for performance reporting.
     */
    public void logDuration(String name, long timeMs) {
        Slog.d(mTag, name + " took to complete: " + timeMs + "ms");
    }

    /**
     * Gets the names of the traces that {@link #traceBegin(String) have begun} but
     * {@link #traceEnd() have not finished} yet.
     *
     * <p><b>NOTE:</b> this method is expensive and it should not be used in "production" - it
     * should only be used for debugging purposes during development (and/or guarded by
     * static {@code DEBUG} constants that are {@code false}).
     */
    @NonNull
    public final List<String> getUnfinishedTracesForDebug() {
        if (mStartTimes == null || mCurrentLevel < 0) return Collections.emptyList();
        final ArrayList<String> list = new ArrayList<>(mCurrentLevel + 1);
        for (int i = 0; i <= mCurrentLevel; i++) {
            list.add(mStartNames[i]);
        }
        return list;
    }
}
