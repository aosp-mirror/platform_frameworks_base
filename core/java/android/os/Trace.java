/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.os;

/**
 * Writes trace events to the kernel trace buffer.  These trace events can be
 * collected using the "atrace" program for offline analysis.
 *
 * This tracing mechanism is independent of the method tracing mechanism
 * offered by {@link Debug#startMethodTracing}.  In particular, it enables
 * tracing of events that occur across processes.
 *
 * @hide
 */
public final class Trace {
    // These tags must be kept in sync with frameworks/native/include/utils/Trace.h.
    public static final long TRACE_TAG_NEVER = 0;
    public static final long TRACE_TAG_ALWAYS = 1L << 0;
    public static final long TRACE_TAG_GRAPHICS = 1L << 1;
    public static final long TRACE_TAG_INPUT = 1L << 2;
    public static final long TRACE_TAG_VIEW = 1L << 3;
    public static final long TRACE_TAG_WEBVIEW = 1L << 4;
    public static final long TRACE_TAG_WINDOW_MANAGER = 1L << 5;
    public static final long TRACE_TAG_ACTIVITY_MANAGER = 1L << 6;
    public static final long TRACE_TAG_SYNC_MANAGER = 1L << 7;
    public static final long TRACE_TAG_AUDIO = 1L << 8;
    public static final long TRACE_TAG_VIDEO = 1L << 9;

    public static final int TRACE_FLAGS_START_BIT = 1;
    public static final String[] TRACE_TAGS = {
        "Graphics", "Input", "View", "WebView", "Window Manager",
        "Activity Manager", "Sync Manager", "Audio", "Video",
    };

    public static final String PROPERTY_TRACE_TAG_ENABLEFLAGS = "debug.atrace.tags.enableflags";

    private static long sEnabledTags = nativeGetEnabledTags();

    private static native long nativeGetEnabledTags();
    private static native void nativeTraceCounter(long tag, String name, int value);
    private static native void nativeTraceBegin(long tag, String name);
    private static native void nativeTraceEnd(long tag);

    static {
        SystemProperties.addChangeCallback(new Runnable() {
            @Override public void run() {
                sEnabledTags = nativeGetEnabledTags();
            }
        });
    }

    private Trace() {
    }

    /**
     * Returns true if a trace tag is enabled.
     *
     * @param traceTag The trace tag to check.
     * @return True if the trace tag is valid.
     */
    public static boolean isTagEnabled(long traceTag) {
        return (sEnabledTags & traceTag) != 0;
    }

    /**
     * Writes trace message to indicate the value of a given counter.
     *
     * @param traceTag The trace tag.
     * @param counterName The counter name to appear in the trace.
     * @param counterValue The counter value.
     */
    public static void traceCounter(long traceTag, String counterName, int counterValue) {
        if ((sEnabledTags & traceTag) != 0) {
            nativeTraceCounter(traceTag, counterName, counterValue);
        }
    }

    /**
     * Writes a trace message to indicate that a given method has begun.
     * Must be followed by a call to {@link #traceEnd} using the same tag.
     *
     * @param traceTag The trace tag.
     * @param methodName The method name to appear in the trace.
     */
    public static void traceBegin(long traceTag, String methodName) {
        if ((sEnabledTags & traceTag) != 0) {
            nativeTraceBegin(traceTag, methodName);
        }
    }

    /**
     * Writes a trace message to indicate that the current method has ended.
     * Must be called exactly once for each call to {@link #traceBegin} using the same tag.
     *
     * @param traceTag The trace tag.
     */
    public static void traceEnd(long traceTag) {
        if ((sEnabledTags & traceTag) != 0) {
            nativeTraceEnd(traceTag);
        }
    }
}
