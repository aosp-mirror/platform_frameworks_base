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

import android.util.Log;

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
    private static final String TAG = "Trace";

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
    public static final long TRACE_TAG_CAMERA = 1L << 10;
    private static final long TRACE_TAG_NOT_READY = 1L << 63;

    public static final int TRACE_FLAGS_START_BIT = 1;
    public static final String[] TRACE_TAGS = {
        "Graphics", "Input", "View", "WebView", "Window Manager",
        "Activity Manager", "Sync Manager", "Audio", "Video", "Camera",
    };

    public static final String PROPERTY_TRACE_TAG_ENABLEFLAGS = "debug.atrace.tags.enableflags";

    // Must be volatile to avoid word tearing.
    private static volatile long sEnabledTags = TRACE_TAG_NOT_READY;

    private static native long nativeGetEnabledTags();
    private static native void nativeTraceCounter(long tag, String name, int value);
    private static native void nativeTraceBegin(long tag, String name);
    private static native void nativeTraceEnd(long tag);

    static {
        // We configure two separate change callbacks, one in Trace.cpp and one here.  The
        // native callback reads the tags from the system property, and this callback
        // reads the value that the native code retrieved.  It's essential that the native
        // callback executes first.
        //
        // The system provides ordering through a priority level.  Callbacks made through
        // SystemProperties.addChangeCallback currently have a negative priority, while
        // our native code is using a priority of zero.
        SystemProperties.addChangeCallback(new Runnable() {
            @Override public void run() {
                cacheEnabledTags();
            }
        });
    }

    private Trace() {
    }

    /**
     * Caches a copy of the enabled-tag bits.  The "master" copy is held by the native code,
     * and comes from the PROPERTY_TRACE_TAG_ENABLEFLAGS property.
     * <p>
     * If the native code hasn't yet read the property, we will cause it to do one-time
     * initialization.  We don't want to do this during class init, because this class is
     * preloaded, so all apps would be stuck with whatever the zygote saw.  (The zygote
     * doesn't see the system-property update broadcasts.)
     * <p>
     * We want to defer initialization until the first use by an app, post-zygote.
     * <p>
     * We're okay if multiple threads call here simultaneously -- the native state is
     * synchronized, and sEnabledTags is volatile (prevents word tearing).
     */
    private static long cacheEnabledTags() {
        long tags = nativeGetEnabledTags();
        if (tags == TRACE_TAG_NOT_READY) {
            Log.w(TAG, "Unexpected value from nativeGetEnabledTags: " + tags);
            // keep going
        }
        sEnabledTags = tags;
        return tags;
    }

    /**
     * Returns true if a trace tag is enabled.
     *
     * @param traceTag The trace tag to check.
     * @return True if the trace tag is valid.
     */
    public static boolean isTagEnabled(long traceTag) {
        long tags = sEnabledTags;
        if (tags == TRACE_TAG_NOT_READY) {
            tags = cacheEnabledTags();
        }
        return (tags & traceTag) != 0;
    }

    /**
     * Writes trace message to indicate the value of a given counter.
     *
     * @param traceTag The trace tag.
     * @param counterName The counter name to appear in the trace.
     * @param counterValue The counter value.
     */
    public static void traceCounter(long traceTag, String counterName, int counterValue) {
        if (isTagEnabled(traceTag)) {
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
        if (isTagEnabled(traceTag)) {
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
        if (isTagEnabled(traceTag)) {
            nativeTraceEnd(traceTag);
        }
    }
}
