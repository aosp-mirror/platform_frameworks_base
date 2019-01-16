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
import static com.android.server.wm.WindowManagerTraceProto.ELAPSED_REALTIME_NANOS;
import static com.android.server.wm.WindowManagerTraceProto.WHERE;
import static com.android.server.wm.WindowManagerTraceProto.WINDOW_MANAGER_SERVICE;

import android.annotation.Nullable;
import android.content.Context;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

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
    private static final int WINDOW_TRACE_BUFFER_SIZE = 512 * 1024;
    private static final String TAG = "WindowTracing";

    private final Object mLock = new Object();
    private final WindowTraceBuffer.Builder mBufferBuilder;

    private WindowTraceBuffer mTraceBuffer;

    private @WindowTraceLogLevel int mWindowTraceLogLevel = WindowTraceLogLevel.TRIM;
    private boolean mContinuousMode;
    private boolean mEnabled;
    private volatile boolean mEnabledLockFree;

    WindowTracing(File file) {
        mBufferBuilder = new WindowTraceBuffer.Builder()
                .setTraceFile(file)
                .setBufferCapacity(WINDOW_TRACE_BUFFER_SIZE);
    }

    void startTrace(@Nullable PrintWriter pw) throws IOException {
        if (IS_USER) {
            logAndPrintln(pw, "Error: Tracing is not supported on user builds.");
            return;
        }
        synchronized (mLock) {
            logAndPrintln(pw, "Start tracing to " + mBufferBuilder.getFile() + ".");
            if (mTraceBuffer != null) {
                writeTraceToFileLocked();
            }
            mTraceBuffer = mBufferBuilder
                    .setContinuousMode(mContinuousMode)
                    .build();
            mEnabled = mEnabledLockFree = true;
        }
    }

    private void logAndPrintln(@Nullable PrintWriter pw, String msg) {
        Log.i(TAG, msg);
        if (pw != null) {
            pw.println(msg);
            pw.flush();
        }
    }

    void stopTrace(@Nullable PrintWriter pw) {
        if (IS_USER) {
            logAndPrintln(pw, "Error: Tracing is not supported on user builds.");
            return;
        }
        synchronized (mLock) {
            logAndPrintln(pw, "Stop tracing to " + mBufferBuilder.getFile()
                    + ". Waiting for traces to flush.");
            mEnabled = mEnabledLockFree = false;

            synchronized (mLock) {
                if (mEnabled) {
                    logAndPrintln(pw, "ERROR: tracing was re-enabled while waiting for flush.");
                    throw new IllegalStateException("tracing enabled while waiting for flush.");
                }
                writeTraceToFileLocked();
                mTraceBuffer = null;
            }
            logAndPrintln(pw, "Trace written to " + mBufferBuilder.getFile() + ".");
        }
    }

    private void setContinuousMode(boolean continuous, PrintWriter pw) {
        logAndPrintln(pw, "Setting window tracing continuous mode to " + continuous);

        if (mEnabled) {
            logAndPrintln(pw, "Trace is currently active, change will take effect once the "
                    + "trace is restarted.");
        }
        mContinuousMode = continuous;
        mWindowTraceLogLevel = (continuous) ? WindowTraceLogLevel.CRITICAL :
                WindowTraceLogLevel.TRIM;
    }

    private void appendTraceEntry(ProtoOutputStream proto) {
        if (!mEnabledLockFree) {
            return;
        }

        mTraceBuffer.add(proto);
    }

    boolean isEnabled() {
        return mEnabledLockFree;
    }

    static WindowTracing createDefaultAndStartLooper(Context context) {
        File file = new File("/data/misc/wmtrace/wm_trace.pb");
        return new WindowTracing(file);
    }

    int onShellCommand(ShellCommand shell) {
        PrintWriter pw = shell.getOutPrintWriter();
        try {
            String cmd = shell.getNextArgRequired();
            switch (cmd) {
                case "start":
                    startTrace(pw);
                    return 0;
                case "stop":
                    stopTrace(pw);
                    return 0;
                case "continuous":
                    setContinuousMode(Boolean.valueOf(shell.getNextArgRequired()), pw);
                    return 0;
                default:
                    pw.println("Unknown command: " + cmd);
                    return -1;
            }
        } catch (IOException e) {
            logAndPrintln(pw, e.toString());
            throw new RuntimeException(e);
        }
    }

    void traceStateLocked(String where, WindowManagerService service) {
        if (!isEnabled()) {
            return;
        }

        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "writeToBufferLocked");
        try {
            ProtoOutputStream os = new ProtoOutputStream();
            long tokenOuter = os.start(ENTRY);
            os.write(ELAPSED_REALTIME_NANOS, SystemClock.elapsedRealtimeNanos());
            os.write(WHERE, where);

            Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "writeToProtoLocked");
            try {
                long tokenInner = os.start(WINDOW_MANAGER_SERVICE);
                service.writeToProtoLocked(os, mWindowTraceLogLevel);
                os.end(tokenInner);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
            }
            os.end(tokenOuter);
            appendTraceEntry(os);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
    }

    /**
     * Writes the trace buffer to disk. This method has no internal synchronization and should be
     * externally synchronized
     */
    private void writeTraceToFileLocked() {
        if (mTraceBuffer == null) {
            return;
        }

        try {
            mTraceBuffer.dump();
        } catch (IOException e) {
            Log.e(TAG, "Unable to write buffer to file", e);
        } catch (InterruptedException e) {
            Log.e(TAG, "Unable to interrupt window tracing file write thread", e);
        }
    }

    /**
     * Writes the trace buffer to disk and clones it into a new file for the bugreport.
     * This method is synchronized with {@code #startTrace(PrintWriter)} and
     * {@link #stopTrace(PrintWriter)}.
     */
    void writeTraceToFile() {
        synchronized (mLock) {
            writeTraceToFileLocked();
        }
    }
}
