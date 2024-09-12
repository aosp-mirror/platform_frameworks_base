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

import android.annotation.Nullable;
import android.internal.perfetto.protos.TracePacketOuterClass.TracePacket;
import android.internal.perfetto.protos.WinscopeExtensionsImplOuterClass.WinscopeExtensionsImpl;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.Choreographer;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;

class WindowTracingPerfetto extends WindowTracing {
    private static final String TAG = "WindowTracing";
    private static final String PRODUCTION_DATA_SOURCE_NAME = "android.windowmanager";

    private final AtomicInteger mCountSessionsOnFrame = new AtomicInteger();
    private final AtomicInteger mCountSessionsOnTransaction = new AtomicInteger();
    private final WindowTracingDataSource mDataSource;

    WindowTracingPerfetto(WindowManagerService service, Choreographer choreographer) {
        this(service, choreographer, service.mGlobalLock, PRODUCTION_DATA_SOURCE_NAME);
    }

    @VisibleForTesting
    WindowTracingPerfetto(WindowManagerService service, Choreographer choreographer,
            WindowManagerGlobalLock globalLock, String dataSourceName) {
        super(service, choreographer, globalLock);
        mDataSource = new WindowTracingDataSource(this, dataSourceName);
    }

    @Override
    void setLogLevel(@WindowTracingLogLevel int logLevel, PrintWriter pw) {
        logAndPrintln(pw, "Log level must be configured through perfetto");
    }

    @Override
    void setLogFrequency(boolean onFrame, PrintWriter pw) {
        logAndPrintln(pw, "Log frequency must be configured through perfetto");
    }

    @Override
    void setBufferCapacity(int capacity, PrintWriter pw) {
        logAndPrintln(pw, "Buffer capacity must be configured through perfetto");
    }

    @Override
    boolean isEnabled() {
        return (mCountSessionsOnFrame.get() + mCountSessionsOnTransaction.get()) > 0;
    }

    @Override
    int onShellCommand(ShellCommand shell) {
        PrintWriter pw = shell.getOutPrintWriter();
        pw.println("Shell commands are ignored."
                + " Any type of action should be performed through perfetto.");
        return -1;
    }

    @Override
    String getStatus() {
        return "Status: "
                + ((isEnabled()) ? "Enabled" : "Disabled")
                + "\n"
                + "Sessions logging 'on frame': " + mCountSessionsOnFrame.get()
                + "\n"
                + "Sessions logging 'on transaction': " + mCountSessionsOnTransaction.get()
                + "\n";
    }

    @Override
    protected void startTraceInternal(@Nullable PrintWriter pw) {
        logAndPrintln(pw, "Tracing must be started through perfetto");
    }

    @Override
    protected void stopTraceInternal(@Nullable PrintWriter pw) {
        logAndPrintln(pw, "Tracing must be stopped through perfetto");
    }

    @Override
    protected void saveForBugreportInternal(@Nullable PrintWriter pw) {
        logAndPrintln(pw, "Tracing snapshot for bugreport must be handled through perfetto");
    }

    @Override
    protected void log(String where) {
        try {
            boolean isStartLogEvent = where == WHERE_START_TRACING;
            boolean isOnFrameLogEvent = where == WHERE_ON_FRAME;

            mDataSource.trace((context) -> {
                WindowTracingDataSource.Config dataSourceConfig =
                        context.getCustomTlsState().mConfig;

                if (isStartLogEvent) {
                    boolean isDataSourceStarting = context.getCustomTlsState()
                            .mIsStarting.compareAndSet(true, false);
                    if (!isDataSourceStarting) {
                        return;
                    }
                } else if (isOnFrameLogEvent) {
                    boolean isDataSourceLoggingOnFrame =
                            dataSourceConfig.mLogFrequency == WindowTracingLogFrequency.FRAME;
                    if (!isDataSourceLoggingOnFrame) {
                        return;
                    }
                } else if (dataSourceConfig.mLogFrequency
                        == WindowTracingLogFrequency.SINGLE_DUMP) {
                    // If it is a dump, write only the start log event and skip the following ones
                    return;
                }

                ProtoOutputStream os = context.newTracePacket();
                long timestamp = SystemClock.elapsedRealtimeNanos();
                os.write(TracePacket.TIMESTAMP, timestamp);
                final long tokenWinscopeExtensions =
                        os.start(TracePacket.WINSCOPE_EXTENSIONS);
                final long tokenExtensionsField =
                        os.start(WinscopeExtensionsImpl.WINDOWMANAGER);
                dumpToProto(os, dataSourceConfig.mLogLevel, where, timestamp);
                os.end(tokenExtensionsField);
                os.end(tokenWinscopeExtensions);
            });
        } catch (Exception e) {
            Log.wtf(TAG, "Exception while tracing state", e);
        }
    }

    @Override
    protected boolean shouldLogOnFrame() {
        return mCountSessionsOnFrame.get() > 0;
    }

    @Override
    protected boolean shouldLogOnTransaction() {
        return mCountSessionsOnTransaction.get() > 0;
    }

    void onStart(WindowTracingDataSource.Config config) {
        if (config.mLogFrequency == WindowTracingLogFrequency.FRAME) {
            mCountSessionsOnFrame.incrementAndGet();
        } else if (config.mLogFrequency == WindowTracingLogFrequency.TRANSACTION) {
            mCountSessionsOnTransaction.incrementAndGet();
        }

        Log.i(TAG, "Started with logLevel: " + config.mLogLevel
                + " logFrequency: " + config.mLogFrequency);
        log(WHERE_START_TRACING);
    }

    void onStop(WindowTracingDataSource.Config config) {
        if (config.mLogFrequency == WindowTracingLogFrequency.FRAME) {
            mCountSessionsOnFrame.decrementAndGet();
        } else if (config.mLogFrequency == WindowTracingLogFrequency.TRANSACTION) {
            mCountSessionsOnTransaction.decrementAndGet();
        }
        Log.i(TAG, "Stopped");
    }
}
