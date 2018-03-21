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

import android.content.Context;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.annotation.Nullable;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * A class that allows window manager to dump its state continuously to a trace file, such that a
 * time series of window manager state can be analyzed after the fact.
 */
class WindowTracing {

    private static final String TAG = "WindowTracing";
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    private final Object mLock = new Object();
    private final File mTraceFile;
    private final BlockingQueue<ProtoOutputStream> mWriteQueue = new ArrayBlockingQueue<>(200);

    private boolean mEnabled;
    private volatile boolean mEnabledLockFree;

    WindowTracing(File file) {
        mTraceFile = file;
    }

    void startTrace(@Nullable PrintWriter pw) throws IOException {
        if (IS_USER){
            logAndPrintln(pw, "Error: Tracing is not supported on user builds.");
            return;
        }
        synchronized (mLock) {
            logAndPrintln(pw, "Start tracing to " + mTraceFile + ".");
            mWriteQueue.clear();
            mTraceFile.delete();
            try (OutputStream os = new FileOutputStream(mTraceFile)) {
                mTraceFile.setReadable(true, false);
                ProtoOutputStream proto = new ProtoOutputStream(os);
                proto.write(MAGIC_NUMBER, MAGIC_NUMBER_VALUE);
                proto.flush();
            }
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
        if (IS_USER){
            logAndPrintln(pw, "Error: Tracing is not supported on user builds.");
            return;
        }
        synchronized (mLock) {
            logAndPrintln(pw, "Stop tracing to " + mTraceFile + ". Waiting for traces to flush.");
            mEnabled = mEnabledLockFree = false;
            while (!mWriteQueue.isEmpty()) {
                if (mEnabled) {
                    logAndPrintln(pw, "ERROR: tracing was re-enabled while waiting for flush.");
                    throw new IllegalStateException("tracing enabled while waiting for flush.");
                }
                try {
                    mLock.wait();
                    mLock.notify();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            logAndPrintln(pw, "Trace written to " + mTraceFile + ".");
        }
    }

    void appendTraceEntry(ProtoOutputStream proto) {
        if (!mEnabledLockFree) {
            return;
        }

        if (!mWriteQueue.offer(proto)) {
            Log.e(TAG, "Dropping window trace entry, queue full");
        }
    }

    void loop() {
        for (;;) {
            loopOnce();
        }
    }

    @VisibleForTesting
    void loopOnce() {
        ProtoOutputStream proto;
        try {
            proto = mWriteQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        synchronized (mLock) {
            try {
                Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "writeToFile");
                try (OutputStream os = new FileOutputStream(mTraceFile, true /* append */)) {
                    os.write(proto.getBytes());
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to write file " + mTraceFile, e);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
            }
            mLock.notify();
        }
    }

    boolean isEnabled() {
        return mEnabledLockFree;
    }

    static WindowTracing createDefaultAndStartLooper(Context context) {
        File file = new File("/data/misc/wmtrace/wm_trace.pb");
        WindowTracing windowTracing = new WindowTracing(file);
        if (!IS_USER){
            new Thread(windowTracing::loop, "window_tracing").start();
        }
        return windowTracing;
    }

    int onShellCommand(ShellCommand shell, String cmd) {
        PrintWriter pw = shell.getOutPrintWriter();
        try {
            switch (cmd) {
                case "start":
                    startTrace(pw);
                    return 0;
                case "stop":
                    stopTrace(pw);
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
        ProtoOutputStream os = new ProtoOutputStream();
        long tokenOuter = os.start(ENTRY);
        os.write(ELAPSED_REALTIME_NANOS, SystemClock.elapsedRealtimeNanos());
        os.write(WHERE, where);

        Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "writeToProtoLocked");
        try {
            long tokenInner = os.start(WINDOW_MANAGER_SERVICE);
            service.writeToProtoLocked(os, true /* trim */);
            os.end(tokenInner);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
        }
        os.end(tokenOuter);
        appendTraceEntry(os);
        Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
    }
}
