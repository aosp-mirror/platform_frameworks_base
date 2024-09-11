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

package com.android.internal.inputmethod;

import static android.tracing.perfetto.DataSourceParams.PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_STALL_AND_ABORT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.internal.perfetto.protos.Inputmethodeditor.InputMethodClientsTraceProto;
import android.internal.perfetto.protos.Inputmethodeditor.InputMethodManagerServiceTraceProto;
import android.internal.perfetto.protos.Inputmethodeditor.InputMethodServiceTraceProto;
import android.internal.perfetto.protos.TracePacketOuterClass.TracePacket;
import android.internal.perfetto.protos.WinscopeExtensionsImplOuterClass.WinscopeExtensionsImpl;
import android.os.SystemClock;
import android.os.Trace;
import android.tracing.inputmethod.InputMethodDataSource;
import android.tracing.perfetto.DataSourceParams;
import android.tracing.perfetto.InitArguments;
import android.tracing.perfetto.Producer;
import android.util.proto.ProtoOutputStream;
import android.view.inputmethod.InputMethodManager;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An implementation of {@link ImeTracing} for perfetto tracing.
 */
final class ImeTracingPerfettoImpl extends ImeTracing {
    private final AtomicInteger mTracingSessionsCount = new AtomicInteger(0);
    private final AtomicBoolean mIsClientDumpInProgress = new AtomicBoolean(false);
    private final AtomicBoolean mIsServiceDumpInProgress = new AtomicBoolean(false);
    private final AtomicBoolean mIsManagerServiceDumpInProgress = new AtomicBoolean(false);
    private final InputMethodDataSource mDataSource = new InputMethodDataSource(
            mTracingSessionsCount::incrementAndGet,
            mTracingSessionsCount::decrementAndGet);

    ImeTracingPerfettoImpl() {
        Producer.init(InitArguments.DEFAULTS);
        DataSourceParams params =
                new DataSourceParams.Builder()
                        .setBufferExhaustedPolicy(
                                PERFETTO_DS_BUFFER_EXHAUSTED_POLICY_STALL_AND_ABORT)
                        .setNoFlush(true)
                        .setWillNotifyOnStop(false)
                        .build();
        mDataSource.register(params);
    }


    @Override
    public void triggerClientDump(String where, InputMethodManager immInstance,
            @Nullable byte[] icProto) {
        if (!isEnabled() || !isAvailable()) {
            return;
        }

        if (!mIsClientDumpInProgress.compareAndSet(false, true)) {
            return;
        }

        if (immInstance == null) {
            return;
        }

        try {
            Trace.beginSection("inputmethod_client_dump");
            mDataSource.trace((ctx) -> {
                final ProtoOutputStream os = ctx.newTracePacket();
                os.write(TracePacket.TIMESTAMP, SystemClock.elapsedRealtimeNanos());
                final long tokenWinscopeExtensions =
                        os.start(TracePacket.WINSCOPE_EXTENSIONS);
                final long tokenExtensionsField =
                        os.start(WinscopeExtensionsImpl.INPUTMETHOD_CLIENTS);
                os.write(InputMethodClientsTraceProto.WHERE, where);
                final long tokenClient =
                        os.start(InputMethodClientsTraceProto.CLIENT);
                immInstance.dumpDebug(os, icProto);
                os.end(tokenClient);
                os.end(tokenExtensionsField);
                os.end(tokenWinscopeExtensions);
            });
        } finally {
            mIsClientDumpInProgress.set(false);
            Trace.endSection();
        }
    }

    @Override
    public void triggerServiceDump(String where,
            @NonNull ServiceDumper dumper, @Nullable byte[] icProto) {
        if (!isEnabled() || !isAvailable()) {
            return;
        }

        if (!mIsServiceDumpInProgress.compareAndSet(false, true)) {
            return;
        }

        try {
            Trace.beginSection("inputmethod_service_dump");
            mDataSource.trace((ctx) -> {
                final ProtoOutputStream os = ctx.newTracePacket();
                os.write(TracePacket.TIMESTAMP, SystemClock.elapsedRealtimeNanos());
                final long tokenWinscopeExtensions =
                        os.start(TracePacket.WINSCOPE_EXTENSIONS);
                final long tokenExtensionsField =
                        os.start(WinscopeExtensionsImpl.INPUTMETHOD_SERVICE);
                os.write(InputMethodServiceTraceProto.WHERE, where);
                dumper.dumpToProto(os, icProto);
                os.end(tokenExtensionsField);
                os.end(tokenWinscopeExtensions);
            });
        } finally {
            mIsServiceDumpInProgress.set(false);
            Trace.endSection();
        }
    }

    @Override
    public void triggerManagerServiceDump(@NonNull String where, @NonNull ServiceDumper dumper) {
        if (!isEnabled() || !isAvailable()) {
            return;
        }

        if (!mIsManagerServiceDumpInProgress.compareAndSet(false, true)) {
            return;
        }

        try {
            Trace.beginSection("inputmethod_manager_service_dump");
            mDataSource.trace((ctx) -> {
                final ProtoOutputStream os = ctx.newTracePacket();
                os.write(TracePacket.TIMESTAMP, SystemClock.elapsedRealtimeNanos());
                final long tokenWinscopeExtensions =
                        os.start(TracePacket.WINSCOPE_EXTENSIONS);
                final long tokenExtensionsField =
                        os.start(WinscopeExtensionsImpl.INPUTMETHOD_MANAGER_SERVICE);
                os.write(InputMethodManagerServiceTraceProto.WHERE, where);
                dumper.dumpToProto(os, null);
                os.end(tokenExtensionsField);
                os.end(tokenWinscopeExtensions);
            });
        } finally {
            mIsManagerServiceDumpInProgress.set(false);
            Trace.endSection();
        }
    }

    @Override
    public boolean isEnabled() {
        return mTracingSessionsCount.get() > 0;
    }

    @Override
    public void startTrace(@Nullable PrintWriter pw) {
        // Intentionally left empty. Tracing start/stop is managed through Perfetto.
    }

    @Override
    public void stopTrace(@Nullable PrintWriter pw) {
        // Intentionally left empty. Tracing start/stop is managed through Perfetto.
    }

    @Override
    public void addToBuffer(ProtoOutputStream proto, int source) {
        // Intentionally left empty. Only used for legacy tracing.
    }
}
