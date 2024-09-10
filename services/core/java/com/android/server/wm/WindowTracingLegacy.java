/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerTraceFileProto.ENTRY;
import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER;
import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER_H;
import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER_L;
import static com.android.server.wm.WindowManagerTraceFileProto.REAL_TO_ELAPSED_TIME_OFFSET_NANOS;

import android.annotation.Nullable;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.Choreographer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.TraceBuffer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

class WindowTracingLegacy extends WindowTracing {

    /**
     * Maximum buffer size, currently defined as 5 MB
     * Size was experimentally defined to fit between 100 to 150 elements.
     */
    private static final int BUFFER_CAPACITY_CRITICAL = 5120 * 1024; // 5 MB
    private static final int BUFFER_CAPACITY_TRIM = 10240 * 1024; // 10 MB
    private static final int BUFFER_CAPACITY_ALL = 20480 * 1024; // 20 MB
    static final String WINSCOPE_EXT = ".winscope";
    private static final String TRACE_FILENAME = "/data/misc/wmtrace/wm_trace" + WINSCOPE_EXT;
    private static final String TAG = "WindowTracing";
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    private final Object mEnabledLock = new Object();
    private final File mTraceFile;
    private final TraceBuffer mBuffer;

    private boolean mEnabled;
    private volatile boolean mEnabledLockFree;

    protected @WindowTracingLogLevel int mLogLevel = WindowTracingLogLevel.TRIM;
    protected boolean mLogOnFrame = false;

    WindowTracingLegacy(WindowManagerService service, Choreographer choreographer) {
        this(new File(TRACE_FILENAME), service, choreographer,
                service.mGlobalLock, BUFFER_CAPACITY_TRIM);
    }

    @VisibleForTesting
    WindowTracingLegacy(File traceFile, WindowManagerService service, Choreographer choreographer,
            WindowManagerGlobalLock globalLock, int bufferSize) {
        super(service, choreographer, globalLock);
        mTraceFile = traceFile;
        mBuffer = new TraceBuffer(bufferSize);
    }

    @Override
    void setLogLevel(@WindowTracingLogLevel int logLevel, PrintWriter pw) {
        logAndPrintln(pw, "Setting window tracing log level to " + logLevel);
        mLogLevel = logLevel;

        switch (logLevel) {
            case WindowTracingLogLevel.ALL: {
                setBufferCapacity(BUFFER_CAPACITY_ALL, pw);
                break;
            }
            case WindowTracingLogLevel.TRIM: {
                setBufferCapacity(BUFFER_CAPACITY_TRIM, pw);
                break;
            }
            case WindowTracingLogLevel.CRITICAL: {
                setBufferCapacity(BUFFER_CAPACITY_CRITICAL, pw);
                break;
            }
        }
    }

    @Override
    void setLogFrequency(boolean onFrame, PrintWriter pw) {
        logAndPrintln(pw, "Setting window tracing log frequency to "
                + ((onFrame) ? "frame" : "transaction"));
        mLogOnFrame = onFrame;
    }

    @Override
    void setBufferCapacity(int capacity, PrintWriter pw) {
        logAndPrintln(pw, "Setting window tracing buffer capacity to " + capacity + "bytes");
        mBuffer.setCapacity(capacity);
    }

    @Override
    boolean isEnabled() {
        return mEnabledLockFree;
    }

    @Override
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
            case "save-for-bugreport":
                saveForBugreport(pw);
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
                        setLogLevel(WindowTracingLogLevel.ALL, pw);
                        break;
                    }
                    case "trim": {
                        setLogLevel(WindowTracingLogLevel.TRIM, pw);
                        break;
                    }
                    case "critical": {
                        setLogLevel(WindowTracingLogLevel.CRITICAL, pw);
                        break;
                    }
                    default: {
                        setLogLevel(WindowTracingLogLevel.TRIM, pw);
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
                pw.println("  save-for-bugreport: Save logging data to file if it's running.");
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

    @Override
    String getStatus() {
        return "Status: "
                + ((isEnabled()) ? "Enabled" : "Disabled")
                + "\n"
                + "Log level: "
                + mLogLevel
                + "\n"
                + mBuffer.getStatus();
    }

    @Override
    protected void startTraceInternal(@Nullable PrintWriter pw) {
        synchronized (mEnabledLock) {
            logAndPrintln(pw, "Start tracing to " + mTraceFile + ".");
            mBuffer.resetBuffer();
            mEnabled = mEnabledLockFree = true;
        }
        log(WHERE_START_TRACING);
    }

    @Override
    protected void stopTraceInternal(@Nullable PrintWriter pw) {
        synchronized (mEnabledLock) {
            logAndPrintln(pw, "Stop tracing to " + mTraceFile + ". Waiting for traces to flush.");
            mEnabled = mEnabledLockFree = false;

            if (mEnabled) {
                logAndPrintln(pw, "ERROR: tracing was re-enabled while waiting for flush.");
                throw new IllegalStateException("tracing enabled while waiting for flush.");
            }
            writeTraceToFileLocked();
            logAndPrintln(pw, "Trace written to " + mTraceFile + ".");
        }
    }

    @Override
    protected void saveForBugreportInternal(@Nullable PrintWriter pw) {
        synchronized (mEnabledLock) {
            if (!mEnabled) {
                return;
            }
            mEnabled = mEnabledLockFree = false;
            logAndPrintln(pw, "Stop tracing to " + mTraceFile + ". Waiting for traces to flush.");
            writeTraceToFileLocked();
            logAndPrintln(pw, "Trace written to " + mTraceFile + ".");
            logAndPrintln(pw, "Start tracing to " + mTraceFile + ".");
            mBuffer.resetBuffer();
            mEnabled = mEnabledLockFree = true;
        }
    }

    @Override
    protected void log(String where) {
        try {
            ProtoOutputStream os = new ProtoOutputStream();
            long token = os.start(ENTRY);
            dumpToProto(os, mLogLevel, where, SystemClock.elapsedRealtimeNanos());
            os.end(token);
            mBuffer.add(os);
        } catch (Exception e) {
            Log.wtf(TAG, "Exception while tracing state", e);
        }
    }

    @Override
    protected boolean shouldLogOnFrame() {
        return mLogOnFrame;
    }

    @Override
    protected boolean shouldLogOnTransaction() {
        return !mLogOnFrame;
    }

    private void writeTraceToFileLocked() {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "writeTraceToFileLocked");
            ProtoOutputStream proto = new ProtoOutputStream();
            proto.write(MAGIC_NUMBER, MAGIC_NUMBER_VALUE);
            long timeOffsetNs =
                    TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
                    - SystemClock.elapsedRealtimeNanos();
            proto.write(REAL_TO_ELAPSED_TIME_OFFSET_NANOS, timeOffsetNs);
            mBuffer.writeTraceToFile(mTraceFile, proto);
        } catch (IOException e) {
            Log.e(TAG, "Unable to write buffer to file", e);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }
}
