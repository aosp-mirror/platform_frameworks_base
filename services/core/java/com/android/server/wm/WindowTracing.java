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

import static com.android.server.wm.WindowManagerTraceFileProto.ENTRY;
import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER;
import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER_H;
import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER_L;
import static com.android.server.wm.WindowManagerTraceProto.ELAPSED_REALTIME_NANOS;
import static com.android.server.wm.WindowManagerTraceProto.WHERE;
import static com.android.server.wm.WindowManagerTraceProto.WINDOW_MANAGER_SERVICE;

import android.annotation.Nullable;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.Choreographer;

import com.android.server.protolog.ProtoLogImpl;
import com.android.server.utils.TraceBuffer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * A class that allows window manager to dump its state continuously to a trace file, such that a
 * time series of window manager state can be analyzed after the fact.
 */
class WindowTracing {

    /**
     * Maximum buffer size, currently defined as 512 KB
     * Size was experimentally defined to fit between 100 to 150 elements.
     */
    private static final int BUFFER_CAPACITY_CRITICAL = 512 * 1024;
    private static final int BUFFER_CAPACITY_TRIM = 2048 * 1024;
    private static final int BUFFER_CAPACITY_ALL = 4096 * 1024;
    private static final String TRACE_FILENAME = "/data/misc/wmtrace/wm_trace.pb";
    private static final String TAG = "WindowTracing";
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    private final WindowManagerService mService;
    private final Choreographer mChoreographer;
    private final WindowManagerGlobalLock mGlobalLock;

    private final Object mEnabledLock = new Object();
    private final File mTraceFile;
    private final com.android.server.utils.TraceBuffer mBuffer;
    private final Choreographer.FrameCallback mFrameCallback = (frameTimeNanos) ->
            log("onFrame" /* where */);

    private @WindowTraceLogLevel int mLogLevel = WindowTraceLogLevel.TRIM;
    private boolean mLogOnFrame = false;
    private boolean mEnabled;
    private volatile boolean mEnabledLockFree;
    private boolean mScheduled;

    static WindowTracing createDefaultAndStartLooper(WindowManagerService service,
            Choreographer choreographer) {
        File file = new File(TRACE_FILENAME);
        return new WindowTracing(file, service, choreographer, BUFFER_CAPACITY_TRIM);
    }

    private WindowTracing(File file, WindowManagerService service, Choreographer choreographer,
            int bufferCapacity) {
        this(file, service, choreographer, service.mGlobalLock, bufferCapacity);
    }

    WindowTracing(File file, WindowManagerService service, Choreographer choreographer,
            WindowManagerGlobalLock globalLock, int bufferCapacity) {
        mChoreographer = choreographer;
        mService = service;
        mGlobalLock = globalLock;
        mTraceFile = file;
        mBuffer = new TraceBuffer(bufferCapacity);
        setLogLevel(WindowTraceLogLevel.TRIM, null /* pw */);
    }

    void startTrace(@Nullable PrintWriter pw) {
        if (IS_USER) {
            logAndPrintln(pw, "Error: Tracing is not supported on user builds.");
            return;
        }
        synchronized (mEnabledLock) {
            ProtoLogImpl.getSingleInstance().startProtoLog(pw);
            logAndPrintln(pw, "Start tracing to " + mTraceFile + ".");
            mBuffer.resetBuffer();
            mEnabled = mEnabledLockFree = true;
        }
        log("trace.enable");
    }

    /**
     * Stops the trace and write the current buffer to disk
     * @param pw Print writer
     */
    void stopTrace(@Nullable PrintWriter pw) {
        stopTrace(pw, true /* writeToFile */);
    }

    /**
     * Stops the trace
     * @param pw Print writer
     * @param writeToFile If the current buffer should be written to disk or not
     */
    void stopTrace(@Nullable PrintWriter pw, boolean writeToFile) {
        if (IS_USER) {
            logAndPrintln(pw, "Error: Tracing is not supported on user builds.");
            return;
        }
        synchronized (mEnabledLock) {
            logAndPrintln(pw, "Stop tracing to " + mTraceFile + ". Waiting for traces to flush.");
            mEnabled = mEnabledLockFree = false;

            if (mEnabled) {
                logAndPrintln(pw, "ERROR: tracing was re-enabled while waiting for flush.");
                throw new IllegalStateException("tracing enabled while waiting for flush.");
            }
            if (writeToFile) {
                writeTraceToFileLocked();
                logAndPrintln(pw, "Trace written to " + mTraceFile + ".");
            }
        }
        ProtoLogImpl.getSingleInstance().stopProtoLog(pw, writeToFile);
    }

    private void setLogLevel(@WindowTraceLogLevel int logLevel, PrintWriter pw) {
        logAndPrintln(pw, "Setting window tracing log level to " + logLevel);
        mLogLevel = logLevel;

        switch (logLevel) {
            case WindowTraceLogLevel.ALL: {
                setBufferCapacity(BUFFER_CAPACITY_ALL, pw);
                break;
            }
            case WindowTraceLogLevel.TRIM: {
                setBufferCapacity(BUFFER_CAPACITY_TRIM, pw);
                break;
            }
            case WindowTraceLogLevel.CRITICAL: {
                setBufferCapacity(BUFFER_CAPACITY_CRITICAL, pw);
                break;
            }
        }
    }

    private void setLogFrequency(boolean onFrame, PrintWriter pw) {
        logAndPrintln(pw, "Setting window tracing log frequency to "
                + ((onFrame) ? "frame" : "transaction"));
        mLogOnFrame = onFrame;
    }

    private void setBufferCapacity(int capacity, PrintWriter pw) {
        logAndPrintln(pw, "Setting window tracing buffer capacity to " + capacity + "bytes");
        mBuffer.setCapacity(capacity);
    }

    boolean isEnabled() {
        return mEnabledLockFree;
    }

    int onShellCommand(ShellCommand shell) {
        PrintWriter pw = shell.getOutPrintWriter();
        String cmd = shell.getNextArgRequired();
        switch (cmd) {
            case "start":
                startTrace(pw);
                return 0;
            case "stop":
                stopTrace(pw);
                return 0;
            case "status":
                logAndPrintln(pw, getStatus());
                return 0;
            case "frame":
                setLogFrequency(true /* onFrame */, pw);
                mBuffer.resetBuffer();
                return 0;
            case "transaction":
                setLogFrequency(false /* onFrame */, pw);
                mBuffer.resetBuffer();
                return 0;
            case "level":
                String logLevelStr = shell.getNextArgRequired().toLowerCase();
                switch (logLevelStr) {
                    case "all": {
                        setLogLevel(WindowTraceLogLevel.ALL, pw);
                        break;
                    }
                    case "trim": {
                        setLogLevel(WindowTraceLogLevel.TRIM, pw);
                        break;
                    }
                    case "critical": {
                        setLogLevel(WindowTraceLogLevel.CRITICAL, pw);
                        break;
                    }
                    default: {
                        setLogLevel(WindowTraceLogLevel.TRIM, pw);
                        break;
                    }
                }
                mBuffer.resetBuffer();
                return 0;
            case "size":
                setBufferCapacity(Integer.parseInt(shell.getNextArgRequired()) * 1024, pw);
                mBuffer.resetBuffer();
                return 0;
            default:
                pw.println("Unknown command: " + cmd);
                pw.println("Window manager trace options:");
                pw.println("  start: Start logging");
                pw.println("  stop: Stop logging");
                pw.println("  frame: Log trace once per frame");
                pw.println("  transaction: Log each transaction");
                pw.println("  size: Set the maximum log size (in KB)");
                pw.println("  status: Print trace status");
                pw.println("  level [lvl]: Set the log level between");
                pw.println("    lvl may be one of:");
                pw.println("      critical: Only visible windows with reduced information");
                pw.println("      trim: All windows with reduced");
                pw.println("      all: All window and information");
                return -1;
        }
    }

    String getStatus() {
        return "Status: "
                + ((isEnabled()) ? "Enabled" : "Disabled")
                + "\n"
                + "Log level: "
                + mLogLevel
                + "\n"
                + mBuffer.getStatus();
    }

    /**
     * If tracing is enabled, log the current state or schedule the next frame to be logged,
     * according to {@link #mLogOnFrame}.
     *
     * @param where Logging point descriptor
     */
    void logState(String where) {
        if (!isEnabled()) {
            return;
        }

        if (mLogOnFrame) {
            schedule();
        } else {
            log(where);
        }
    }

    /**
     * Schedule the log to trace the next frame
     */
    private void schedule() {
        if (mScheduled) {
            return;
        }

        mScheduled = true;
        mChoreographer.postFrameCallback(mFrameCallback);
    }

    /**
     * Write the current frame to the buffer
     *
     * @param where Logging point descriptor
     */
    private void log(String where) {
        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "traceStateLocked");
        try {
            ProtoOutputStream os = new ProtoOutputStream();
            long tokenOuter = os.start(ENTRY);
            os.write(ELAPSED_REALTIME_NANOS, SystemClock.elapsedRealtimeNanos());
            os.write(WHERE, where);

            long tokenInner = os.start(WINDOW_MANAGER_SERVICE);
            synchronized (mGlobalLock) {
                Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "writeToProtoLocked");
                try {
                    mService.writeToProtoLocked(os, mLogLevel);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
                }
            }
            os.end(tokenInner);
            os.end(tokenOuter);
            mBuffer.add(os);
            mScheduled = false;
        } catch (Exception e) {
            Log.wtf(TAG, "Exception while tracing state", e);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }

    /**
     * Writes the trace buffer to new file for the bugreport.
     *
     * This method is synchronized with {@code #startTrace(PrintWriter)} and
     * {@link #stopTrace(PrintWriter)}.
     */
    void writeTraceToFile() {
        synchronized (mEnabledLock) {
            writeTraceToFileLocked();
        }
        ProtoLogImpl.getSingleInstance().writeProtoLogToFile();
    }

    private void logAndPrintln(@Nullable PrintWriter pw, String msg) {
        Log.i(TAG, msg);
        if (pw != null) {
            pw.println(msg);
            pw.flush();
        }
    }

    /**
     * Writes the trace buffer to disk. This method has no internal synchronization and should be
     * externally synchronized
     */
    private void writeTraceToFileLocked() {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "writeTraceToFileLocked");
            ProtoOutputStream proto = new ProtoOutputStream();
            proto.write(MAGIC_NUMBER, MAGIC_NUMBER_VALUE);
            mBuffer.writeTraceToFile(mTraceFile, proto);
        } catch (IOException e) {
            Log.e(TAG, "Unable to write buffer to file", e);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }
}
