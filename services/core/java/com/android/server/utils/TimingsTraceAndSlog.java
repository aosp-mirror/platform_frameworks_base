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
package com.android.server.utils;

import android.annotation.NonNull;
import android.os.Trace;
import android.util.Slog;
import android.util.TimingsTraceLog;

/**
 * Helper class for reporting boot and shutdown timing metrics, also logging to {@link Slog}.
 */
public final class TimingsTraceAndSlog extends TimingsTraceLog {

    /**
    * Tag for timing measurement of main thread.
    */
    public static final String SYSTEM_SERVER_TIMING_TAG = "SystemServerTiming";

    /**
     * Tag for timing measurement of non-main asynchronous operations.
     */
    private static final String SYSTEM_SERVER_TIMING_ASYNC_TAG = SYSTEM_SERVER_TIMING_TAG + "Async";

    /**
     * Set this to a positive value to get a {@Slog.w} log for any trace that took longer than it.
     */
    private static final long BOTTLENECK_DURATION_MS = -1;

    private final String mTag;

    /**
     * Creates a new {@link TimingsTraceAndSlog} for async operations.
     */
    @NonNull
    public static TimingsTraceAndSlog newAsyncLog() {
        return new TimingsTraceAndSlog(SYSTEM_SERVER_TIMING_ASYNC_TAG,
                Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    /**
     * Default constructor using {@code system_server} tags.
     */
    public TimingsTraceAndSlog() {
        this(SYSTEM_SERVER_TIMING_TAG);
    }

    /**
     * Custom constructor using {@code system_server} trace tag.
     *
     * @param tag {@code logcat} tag
     */
    public TimingsTraceAndSlog(@NonNull String tag) {
        this(tag, Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    /**
     * Custom constructor.
     *
     * @param tag {@code logcat} tag
     * @param traceTag {@code atrace} tag
     */
    public TimingsTraceAndSlog(@NonNull String tag, long traceTag) {
        super(tag, traceTag);
        mTag = tag;
    }

    @Override
    public void traceBegin(@NonNull String name) {
        Slog.d(mTag, name);
        super.traceBegin(name);
    }

    @Override
    public void logDuration(String name, long timeMs) {
        super.logDuration(name, timeMs);
        if (BOTTLENECK_DURATION_MS > 0 && timeMs >= BOTTLENECK_DURATION_MS) {
            Slog.w(mTag, "Slow duration for " + name + ": " + timeMs + "ms");
        }
    }

    @Override
    public String toString() {
        return "TimingsTraceAndSlog[" + mTag + "]";
    }
}
