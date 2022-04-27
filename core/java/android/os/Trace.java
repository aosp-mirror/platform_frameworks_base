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

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

/**
 * Writes trace events to the system trace buffer.  These trace events can be
 * collected and visualized using the Systrace tool.
 *
 * <p>This tracing mechanism is independent of the method tracing mechanism
 * offered by {@link Debug#startMethodTracing}.  In particular, it enables
 * tracing of events that occur across multiple processes.
 * <p>For information about using the Systrace tool, read <a
 * href="{@docRoot}tools/debugging/systrace.html">Analyzing Display and Performance
 * with Systrace</a>.
 */
public final class Trace {
    /*
     * Writes trace events to the kernel trace buffer.  These trace events can be
     * collected using the "atrace" program for offline analysis.
     */

    private static final String TAG = "Trace";

    // These tags must be kept in sync with system/core/include/cutils/trace.h.
    // They should also be added to frameworks/native/cmds/atrace/atrace.cpp.
    /** @hide */
    public static final long TRACE_TAG_NEVER = 0;
    /** @hide */
    public static final long TRACE_TAG_ALWAYS = 1L << 0;
    /** @hide */
    public static final long TRACE_TAG_GRAPHICS = 1L << 1;
    /** @hide */
    public static final long TRACE_TAG_INPUT = 1L << 2;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final long TRACE_TAG_VIEW = 1L << 3;
    /** @hide */
    public static final long TRACE_TAG_WEBVIEW = 1L << 4;
    /** @hide */
    public static final long TRACE_TAG_WINDOW_MANAGER = 1L << 5;
    /** @hide */
    public static final long TRACE_TAG_ACTIVITY_MANAGER = 1L << 6;
    /** @hide */
    public static final long TRACE_TAG_SYNC_MANAGER = 1L << 7;
    /** @hide */
    public static final long TRACE_TAG_AUDIO = 1L << 8;
    /** @hide */
    public static final long TRACE_TAG_VIDEO = 1L << 9;
    /** @hide */
    public static final long TRACE_TAG_CAMERA = 1L << 10;
    /** @hide */
    public static final long TRACE_TAG_HAL = 1L << 11;
    /** @hide */
    @UnsupportedAppUsage
    public static final long TRACE_TAG_APP = 1L << 12;
    /** @hide */
    public static final long TRACE_TAG_RESOURCES = 1L << 13;
    /** @hide */
    public static final long TRACE_TAG_DALVIK = 1L << 14;
    /** @hide */
    public static final long TRACE_TAG_RS = 1L << 15;
    /** @hide */
    public static final long TRACE_TAG_BIONIC = 1L << 16;
    /** @hide */
    public static final long TRACE_TAG_POWER = 1L << 17;
    /** @hide */
    public static final long TRACE_TAG_PACKAGE_MANAGER = 1L << 18;
    /** @hide */
    public static final long TRACE_TAG_SYSTEM_SERVER = 1L << 19;
    /** @hide */
    public static final long TRACE_TAG_DATABASE = 1L << 20;
    /** @hide */
    @SystemApi(client = MODULE_LIBRARIES)
    public static final long TRACE_TAG_NETWORK = 1L << 21;
    /** @hide */
    public static final long TRACE_TAG_ADB = 1L << 22;
    /** @hide */
    public static final long TRACE_TAG_VIBRATOR = 1L << 23;
    /** @hide */
    public static final long TRACE_TAG_AIDL = 1L << 24;
    /** @hide */
    public static final long TRACE_TAG_NNAPI = 1L << 25;
    /** @hide */
    public static final long TRACE_TAG_RRO = 1L << 26;
    /** @hide */
    public static final long TRACE_TAG_APEX_MANAGER = 1L << 18;

    private static final long TRACE_TAG_NOT_READY = 1L << 63;
    private static final int MAX_SECTION_NAME_LEN = 127;

    // Must be volatile to avoid word tearing.
    // This is only kept in case any apps get this by reflection but do not
    // check the return value for null.
    @UnsupportedAppUsage
    private static volatile long sEnabledTags = TRACE_TAG_NOT_READY;

    private static int sZygoteDebugFlags = 0;

    @UnsupportedAppUsage
    @CriticalNative
    private static native long nativeGetEnabledTags();
    private static native void nativeSetAppTracingAllowed(boolean allowed);
    private static native void nativeSetTracingEnabled(boolean allowed);

    @FastNative
    private static native void nativeTraceCounter(long tag, String name, long value);
    @FastNative
    private static native void nativeTraceBegin(long tag, String name);
    @FastNative
    private static native void nativeTraceEnd(long tag);
    @FastNative
    private static native void nativeAsyncTraceBegin(long tag, String name, int cookie);
    @FastNative
    private static native void nativeAsyncTraceEnd(long tag, String name, int cookie);
    @FastNative
    private static native void nativeAsyncTraceForTrackBegin(long tag,
            String trackName, String name, int cookie);
    @FastNative
    private static native void nativeAsyncTraceForTrackEnd(long tag,
            String trackName, int cookie);
    @FastNative
    private static native void nativeInstant(long tag, String name);
    @FastNative
    private static native void nativeInstantForTrack(long tag, String trackName, String name);

    private Trace() {
    }

    /**
     * Returns true if a trace tag is enabled.
     *
     * @param traceTag The trace tag to check.
     * @return True if the trace tag is valid.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static boolean isTagEnabled(long traceTag) {
        long tags = nativeGetEnabledTags();
        return (tags & traceTag) != 0;
    }

    /**
     * Writes trace message to indicate the value of a given counter.
     *
     * @param traceTag The trace tag.
     * @param counterName The counter name to appear in the trace.
     * @param counterValue The counter value.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static void traceCounter(long traceTag, @NonNull String counterName, int counterValue) {
        if (isTagEnabled(traceTag)) {
            nativeTraceCounter(traceTag, counterName, counterValue);
        }
    }

    /**
     * From Android S, this is no-op.
     *
     * Before, set whether application tracing is allowed for this process.  This is intended to be
     * set once at application start-up time based on whether the application is debuggable.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static void setAppTracingAllowed(boolean allowed) {
        nativeSetAppTracingAllowed(allowed);
    }

    /**
     * Set whether tracing is enabled in this process.
     * @hide
     */
    public static void setTracingEnabled(boolean enabled, int debugFlags) {
        nativeSetTracingEnabled(enabled);
        sZygoteDebugFlags = debugFlags;
    }

    /**
     * Writes a trace message to indicate that a given section of code has
     * begun. Must be followed by a call to {@link #traceEnd} using the same
     * tag.
     *
     * @param traceTag The trace tag.
     * @param methodName The method name to appear in the trace.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static void traceBegin(long traceTag, @NonNull String methodName) {
        if (isTagEnabled(traceTag)) {
            nativeTraceBegin(traceTag, methodName);
        }
    }

    /**
     * Writes a trace message to indicate that the current method has ended.
     * Must be called exactly once for each call to {@link #traceBegin} using the same tag.
     *
     * @param traceTag The trace tag.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static void traceEnd(long traceTag) {
        if (isTagEnabled(traceTag)) {
            nativeTraceEnd(traceTag);
        }
    }

    /**
     * Writes a trace message to indicate that a given section of code has
     * begun. Must be followed by a call to {@link #asyncTraceEnd} using the same
     * tag. Unlike {@link #traceBegin(long, String)} and {@link #traceEnd(long)},
     * asynchronous events do not need to be nested. The name and cookie used to
     * begin an event must be used to end it.
     *
     * @param traceTag The trace tag.
     * @param methodName The method name to appear in the trace.
     * @param cookie Unique identifier for distinguishing simultaneous events
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static void asyncTraceBegin(long traceTag, @NonNull String methodName, int cookie) {
        if (isTagEnabled(traceTag)) {
            nativeAsyncTraceBegin(traceTag, methodName, cookie);
        }
    }

    /**
     * Writes a trace message to indicate that the current method has ended.
     * Must be called exactly once for each call to {@link #asyncTraceBegin(long, String, int)}
     * using the same tag, name and cookie.
     *
     * @param traceTag The trace tag.
     * @param methodName The method name to appear in the trace.
     * @param cookie Unique identifier for distinguishing simultaneous events
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public static void asyncTraceEnd(long traceTag, @NonNull String methodName, int cookie) {
        if (isTagEnabled(traceTag)) {
            nativeAsyncTraceEnd(traceTag, methodName, cookie);
        }
    }


    /**
     * Writes a trace message to indicate that a given section of code has
     * begun. Must be followed by a call to {@link #asyncTraceForTrackEnd} using the same
     * track name and cookie.
     * This function operates exactly like {@link #asyncTraceBegin(long, String, int)},
     * except with the inclusion of a track name argument for where this method should appear.
     * The cookie must be unique on the trackName level, not the methodName level
     *
     * @param traceTag The trace tag.
     * @param trackName The track where the event should appear in the trace.
     * @param methodName The method name to appear in the trace.
     * @param cookie Unique identifier for distinguishing simultaneous events
     *
     * @hide
     */
    public static void asyncTraceForTrackBegin(long traceTag,
            @NonNull String trackName, @NonNull String methodName, int cookie) {
        if (isTagEnabled(traceTag)) {
            nativeAsyncTraceForTrackBegin(traceTag, trackName, methodName, cookie);
        }
    }

    /**
     * Writes a trace message to indicate that the current method has ended.
     * Must be called exactly once for each call to
     * {@link #asyncTraceForTrackBegin(long, String, String, int)}
     * using the same tag, track name, and cookie.
     *
     * @param traceTag The trace tag.
     * @param trackName The track where the event should appear in the trace.
     * @param cookie Unique identifier for distinguishing simultaneous events
     *
     * @hide
     */
    public static void asyncTraceForTrackEnd(long traceTag,
            @NonNull String trackName, int cookie) {
        if (isTagEnabled(traceTag)) {
            nativeAsyncTraceForTrackEnd(traceTag, trackName, cookie);
        }
    }

    /**
     * @deprecated use asyncTraceForTrackEnd without methodName argument
     *
     * @hide
     */
    @Deprecated
    public static void asyncTraceForTrackEnd(long traceTag,
            @NonNull String trackName, @NonNull String methodName, int cookie) {
        if (isTagEnabled(traceTag)) {
            nativeAsyncTraceForTrackEnd(traceTag, trackName, cookie);
        }
    }

    /**
     * Writes a trace message to indicate that a given section of code was invoked.
     *
     * @param traceTag The trace tag.
     * @param methodName The method name to appear in the trace.
     * @hide
     */
    public static void instant(long traceTag, String methodName) {
        if (methodName == null) {
            throw new IllegalArgumentException("methodName cannot be null");
        }
        if (isTagEnabled(traceTag)) {
            nativeInstant(traceTag, methodName);
        }
    }

    /**
     * Writes a trace message to indicate that a given section of code was invoked.
     *
     * @param traceTag The trace tag.
     * @param trackName The track where the event should appear in the trace.
     * @param methodName The method name to appear in the trace.
     * @hide
     */
    public static void instantForTrack(long traceTag, String trackName, String methodName) {
        if (trackName == null) {
            throw new IllegalArgumentException("trackName cannot be null");
        }
        if (methodName == null) {
            throw new IllegalArgumentException("methodName cannot be null");
        }
        if (isTagEnabled(traceTag)) {
            nativeInstantForTrack(traceTag, trackName, methodName);
        }
    }

    /**
     * Checks whether or not tracing is currently enabled. This is useful to avoid intermediate
     * string creation for trace sections that require formatting. It is not necessary
     * to guard all Trace method calls as they internally already check this. However it is
     * recommended to use this to prevent creating any temporary objects that would then be
     * passed to those methods to reduce runtime cost when tracing isn't enabled.
     *
     * @return true if tracing is currently enabled, false otherwise
     */
    public static boolean isEnabled() {
        return isTagEnabled(TRACE_TAG_APP);
    }

    /**
     * Writes a trace message to indicate that a given section of code has begun. This call must
     * be followed by a corresponding call to {@link #endSection()} on the same thread.
     *
     * <p class="note"> At this time the vertical bar character '|', newline character '\n', and
     * null character '\0' are used internally by the tracing mechanism.  If sectionName contains
     * these characters they will be replaced with a space character in the trace.
     *
     * @param sectionName The name of the code section to appear in the trace.  This may be at
     * most 127 Unicode code units long.
     */
    public static void beginSection(@NonNull String sectionName) {
        if (isTagEnabled(TRACE_TAG_APP)) {
            if (sectionName.length() > MAX_SECTION_NAME_LEN) {
                throw new IllegalArgumentException("sectionName is too long");
            }
            nativeTraceBegin(TRACE_TAG_APP, sectionName);
        }
    }

    /**
     * Writes a trace message to indicate that a given section of code has ended. This call must
     * be preceeded by a corresponding call to {@link #beginSection(String)}. Calling this method
     * will mark the end of the most recently begun section of code, so care must be taken to
     * ensure that beginSection / endSection pairs are properly nested and called from the same
     * thread.
     */
    public static void endSection() {
        if (isTagEnabled(TRACE_TAG_APP)) {
            nativeTraceEnd(TRACE_TAG_APP);
        }
    }

    /**
     * Writes a trace message to indicate that a given section of code has
     * begun. Must be followed by a call to {@link #endAsyncSection(String, int)} with the same
     * methodName and cookie. Unlike {@link #beginSection(String)} and {@link #endSection()},
     * asynchronous events do not need to be nested. The name and cookie used to
     * begin an event must be used to end it.
     *
     * @param methodName The method name to appear in the trace.
     * @param cookie Unique identifier for distinguishing simultaneous events
     */
    public static void beginAsyncSection(@NonNull String methodName, int cookie) {
        asyncTraceBegin(TRACE_TAG_APP, methodName, cookie);
    }

    /**
     * Writes a trace message to indicate that the current method has ended.
     * Must be called exactly once for each call to {@link #beginAsyncSection(String, int)}
     * using the same name and cookie.
     *
     * @param methodName The method name to appear in the trace.
     * @param cookie Unique identifier for distinguishing simultaneous events
     */
    public static void endAsyncSection(@NonNull String methodName, int cookie) {
        asyncTraceEnd(TRACE_TAG_APP, methodName, cookie);
    }

    /**
     * Writes trace message to indicate the value of a given counter.
     *
     * @param counterName The counter name to appear in the trace.
     * @param counterValue The counter value.
     */
    public static void setCounter(@NonNull String counterName, long counterValue) {
        if (isTagEnabled(TRACE_TAG_APP)) {
            nativeTraceCounter(TRACE_TAG_APP, counterName, counterValue);
        }
    }
}
