/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.Build.IS_USER;

import static com.android.server.wm.WindowManagerTraceProto.ELAPSED_REALTIME_NANOS;
import static com.android.server.wm.WindowManagerTraceProto.WHERE;
import static com.android.server.wm.WindowManagerTraceProto.WINDOW_MANAGER_SERVICE;

import android.annotation.Nullable;
import android.os.ShellCommand;
import android.os.Trace;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.Choreographer;

import com.android.internal.protolog.LegacyProtoLogImpl;
import com.android.internal.protolog.ProtoLog;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A class that allows window manager to dump its state continuously, such that a
 * time series of window manager state can be analyzed after the fact.
 */
abstract class WindowTracing {
    protected static final String TAG = "WindowTracing";
    protected static final String WHERE_START_TRACING = "trace.enable";
    protected static final String WHERE_ON_FRAME = "onFrame";

    private final WindowManagerService mService;
    private final Choreographer mChoreographer;
    private final WindowManagerGlobalLock mGlobalLock;

    private final Choreographer.FrameCallback mFrameCallback = (frameTimeNanos) ->
            log(WHERE_ON_FRAME);

    private AtomicBoolean mScheduled = new AtomicBoolean(false);


    static WindowTracing createDefaultAndStartLooper(WindowManagerService service,
            Choreographer choreographer) {
        if (!android.tracing.Flags.perfettoWmTracing()) {
            return new WindowTracingLegacy(service, choreographer);
        }
        return new WindowTracingPerfetto(service, choreographer);
    }

    protected WindowTracing(WindowManagerService service, Choreographer choreographer,
            WindowManagerGlobalLock globalLock) {
        mChoreographer = choreographer;
        mService = service;
        mGlobalLock = globalLock;
    }

    void startTrace(@Nullable PrintWriter pw) {
        if (IS_USER) {
            logAndPrintln(pw, "Error: Tracing is not supported on user builds.");
            return;
        }
        if (!android.tracing.Flags.perfettoProtologTracing()) {
            ((LegacyProtoLogImpl) ProtoLog.getSingleInstance()).startProtoLog(pw);
        }
        startTraceInternal(pw);
    }

    void stopTrace(@Nullable PrintWriter pw) {
        if (IS_USER) {
            logAndPrintln(pw, "Error: Tracing is not supported on user builds.");
            return;
        }
        if (!android.tracing.Flags.perfettoProtologTracing()) {
            ((LegacyProtoLogImpl) ProtoLog.getSingleInstance()).stopProtoLog(pw, true);
        }
        stopTraceInternal(pw);
    }

    /**
     * If legacy tracing is enabled (either WM or ProtoLog):
     * 1. Stop tracing
     * 2. Write trace to disk (to be picked by dumpstate)
     * 3. Restart tracing
     *
     * @param pw Print writer
     */
    void saveForBugreport(@Nullable PrintWriter pw) {
        if (IS_USER) {
            logAndPrintln(pw, "Error: Tracing is not supported on user builds.");
            return;
        }
        if (!android.tracing.Flags.perfettoProtologTracing()
                && ProtoLog.getSingleInstance().isProtoEnabled()) {
            ((LegacyProtoLogImpl) ProtoLog.getSingleInstance()).stopProtoLog(pw, true);
            ((LegacyProtoLogImpl) ProtoLog.getSingleInstance()).startProtoLog(pw);
        }
        saveForBugreportInternal(pw);
    }

    abstract void setLogLevel(@WindowTracingLogLevel int logLevel, PrintWriter pw);
    abstract void setLogFrequency(boolean onFrame, PrintWriter pw);
    abstract void setBufferCapacity(int capacity, PrintWriter pw);
    abstract boolean isEnabled();
    abstract int onShellCommand(ShellCommand shell);
    abstract String getStatus();

    /**
     * If tracing is enabled, log the current state or schedule the next frame to be logged,
     * according to the configuration in the derived tracing class.
     *
     * @param where Logging point descriptor
     */
    void logState(String where) {
        if (!isEnabled()) {
            return;
        }

        if (shouldLogOnTransaction()) {
            log(where);
        }

        if (shouldLogOnFrame()) {
            schedule();
        }
    }

    /**
     * Schedule the log to trace the next frame
     */
    private void schedule() {
        if (!mScheduled.compareAndSet(false, true)) {
            return;
        }

        mChoreographer.postFrameCallback(mFrameCallback);
    }

    /**
     * Write the current frame to proto
     *
     * @param os Proto stream buffer
     * @param logLevel Log level
     * @param where Logging point descriptor
     * @param elapsedRealtimeNanos Timestamp
     */
    protected void dumpToProto(ProtoOutputStream os, @WindowTracingLogLevel int logLevel,
            String where, long elapsedRealtimeNanos) {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "traceStateLocked");
        try {
            os.write(ELAPSED_REALTIME_NANOS, elapsedRealtimeNanos);
            os.write(WHERE, where);

            long token = os.start(WINDOW_MANAGER_SERVICE);
            synchronized (mGlobalLock) {
                Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "dumpDebugLocked");
                try {
                    mService.dumpDebugLocked(os, logLevel);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
                }
            }
            os.end(token);
        } catch (Exception e) {
            Log.wtf(TAG, "Exception while tracing state", e);
        } finally {
            boolean isOnFrameLogEvent = where == WHERE_ON_FRAME;
            if (isOnFrameLogEvent) {
                mScheduled.set(false);
            }
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }

    protected void logAndPrintln(@Nullable PrintWriter pw, String msg) {
        Log.i(TAG, msg);
        if (pw != null) {
            pw.println(msg);
            pw.flush();
        }
    }

    protected abstract void startTraceInternal(@Nullable PrintWriter pw);
    protected abstract void stopTraceInternal(@Nullable PrintWriter pw);
    protected abstract void saveForBugreportInternal(@Nullable PrintWriter pw);
    protected abstract void log(String where);
    protected abstract boolean shouldLogOnFrame();
    protected abstract boolean shouldLogOnTransaction();
}
